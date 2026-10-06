package fr.francleint.os;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pilote Bluetooth du boîtier LED.
 * Langage « ELK-BLEDOM » (celui de la plupart des boîtiers gérés par Lotus Lantern) :
 *   allumer   7E 00 04 F0 00 01 FF 00 EF
 *   couleur   7E 00 05 03 RR GG BB 00 EF
 *   lumino    7E 00 01 NN 00 00 00 00 EF   (NN = 0..100)
 * Si le boîtier parle autrement, l'enregistrement Bluetooth de Lotus Lantern
 * donnera les bons octets et il suffira de changer ces trois méthodes.
 */
class Led {

    static final UUID SVC = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    static final UUID CHR = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    private final SharedPreferences prefs;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chr;
    private boolean scanning;
    private int lastR = -1, lastG = -1, lastB = -1;

    volatile String state = "en attente";
    volatile String deviceName = "";
    volatile String gattInfo = "";
    final Map<String, String> seen = new LinkedHashMap<>(); // adresse -> nom

    Led(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("led", Context.MODE_PRIVATE);
    }

    boolean ready() { return chr != null && gatt != null; }

    void start() {
        if (adapter == null) { state = "pas de Bluetooth"; return; }
        if (!adapter.isEnabled()) { try { adapter.enable(); } catch (Exception ignored) { } }
        String saved = prefs.getString("addr", null);
        if (saved != null) connect(saved, prefs.getString("name", ""));
        else scan();
    }

    void stop() {
        stopScan();
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { } }
        gatt = null; chr = null;
    }

    /** Oublie le boîtier mémorisé et relance la recherche. */
    void reset() {
        prefs.edit().clear().apply();
        stop();
        state = "recherche";
        scan();
    }

    private static boolean looksLikeLed(String n) {
        String u = n.toUpperCase();
        return u.startsWith("ELK") || u.startsWith("MELK") || u.contains("BLEDOM")
                || u.contains("LOTUS") || u.startsWith("ELK-B");
    }

    void scan() {
        if (adapter == null || gatt != null || scanning) return;
        final BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) { state = "Bluetooth éteint"; h.postDelayed(this::scan, 3000); return; }
        scanning = true;
        state = "recherche";
        try { sc.startScan(scanCb); } catch (Exception e) { state = "recherche impossible : " + e.getMessage(); scanning = false; return; }
        h.postDelayed(() -> {
            stopScan();
            if (gatt == null) { state = "boîtier introuvable, nouvel essai"; h.postDelayed(this::scan, 4000); }
        }, 12000);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { adapter.getBluetoothLeScanner().stopScan(scanCb); } catch (Exception ignored) { }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String n = d.getName();
            if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
            if (n == null) n = "";
            synchronized (seen) { if (seen.size() < 40 || seen.containsKey(d.getAddress())) seen.put(d.getAddress(), n); }
            if (!n.isEmpty() && looksLikeLed(n) && gatt == null) {
                stopScan();
                connect(d.getAddress(), n);
            }
        }
    };

    /** Connexion à un appareil précis (choisi automatiquement ou à la main). */
    void connect(String address, String name) {
        stopScan();
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) { } gatt = null; chr = null; }
        try {
            BluetoothDevice d = adapter.getRemoteDevice(address);
            deviceName = name == null ? "" : name;
            state = "connexion à " + deviceName;
            prefs.edit().putString("addr", address).putString("name", deviceName).apply();
            if (Build.VERSION.SDK_INT >= 23) gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            else gatt = d.connectGatt(ctx, false, gattCb);
        } catch (Exception e) {
            state = "connexion impossible";
            h.postDelayed(this::scan, 3000);
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state = "connecté, lecture";
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close(); } catch (Exception ignored) { }
                if (g == gatt) { gatt = null; chr = null; }
                state = "déconnecté, reconnexion";
                busy = false;
                lastR = lastG = lastB = -1;
                final String addr = prefs.getString("addr", null);
                h.postDelayed(() -> {
                    if (gatt != null) return;
                    if (addr != null) connect(addr, prefs.getString("name", "")); else scan();
                }, 3000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            StringBuilder info = new StringBuilder();
            BluetoothGattCharacteristic found = null, fallback = null;
            for (BluetoothGattService s : g.getServices()) {
                info.append(s.getUuid().toString(), 4, 8).append(':');
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    boolean writable = (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
                    info.append(c.getUuid().toString(), 4, 8).append(writable ? "w " : " ");
                    if (SVC.equals(s.getUuid()) && CHR.equals(c.getUuid())) found = c;
                    if (writable && fallback == null && !s.getUuid().toString().startsWith("00001800")
                            && !s.getUuid().toString().startsWith("00001801")) fallback = c;
                }
                info.append("| ");
            }
            gattInfo = info.toString();
            chr = found != null ? found : fallback;
            if (chr == null) { state = "boîtier connecté mais langage inconnu"; return; }
            state = "connecté";
            busy = false;
            powerOn();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            busy = false;
            h.post(Led.this::pump);
        }
    };

    // ---- Envoi : un seul message à la fois, le plus récent gagne ----

    private volatile boolean busy = false;
    private byte[] pendingCmd;       // commande ponctuelle (allumage)
    private byte[] pendingColor;     // dernière couleur voulue
    private long lastSendAt = 0;
    private long testUntil = 0;
    private byte[] lastColorSent;

    /** 0 = langage A (7E 00 … 00 EF), 1 = langage B (7E 07 … 10 EF) */
    int protocol() { return prefs.getInt("proto", 0); }

    void setProtocol(int p) {
        prefs.edit().putInt("proto", p).apply();
        powerOn();
        lastR = lastG = lastB = -1;
    }

    private void powerOn() {
        if (protocol() == 0) queueCmd(new byte[]{0x7E, 0x00, 0x04, (byte) 0xF0, 0x00, 0x01, (byte) 0xFF, 0x00, (byte) 0xEF});
        else queueCmd(new byte[]{0x7E, 0x04, 0x04, (byte) 0xF0, 0x00, 0x01, (byte) 0xFF, 0x00, (byte) 0xEF});
    }

    private byte[] colorCmd(int r, int g, int b) {
        if (protocol() == 0) return new byte[]{0x7E, 0x00, 0x05, 0x03, (byte) r, (byte) g, (byte) b, 0x00, (byte) 0xEF};
        return new byte[]{0x7E, 0x07, 0x05, 0x03, (byte) r, (byte) g, (byte) b, 0x10, (byte) 0xEF};
    }

    private synchronized void queueCmd(byte[] b) { pendingCmd = b; h.post(this::pump); }

    private synchronized void pump() {
        if (gatt == null || chr == null) return;
        long now = System.currentTimeMillis();
        if (busy && now - lastSendAt < 400) return;      // on attend la réponse du boîtier
        byte[] next = pendingCmd != null ? pendingCmd : pendingColor;
        if (next == null) return;
        boolean noResp = (chr.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                && (chr.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0;
        try {
            chr.setWriteType(noResp ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            chr.setValue(next);
            boolean ok = gatt.writeCharacteristic(chr);
            if (!ok) { h.postDelayed(this::pump, 60); return; }
            lastSendAt = now;
            if (next == pendingCmd) pendingCmd = null;
            else { lastColorSent = pendingColor; pendingColor = null; }
            if (noResp) { busy = false; if (pendingCmd != null || pendingColor != null) h.postDelayed(this::pump, 40); }
            else busy = true;
        } catch (Exception e) {
            h.postDelayed(this::pump, 200);
        }
    }

    /** Couleur voulue par le moteur (déjà multipliée par l'intensité). Renvoyée toutes les 3 s par sécurité. */
    void color(int r, int g, int b) {
        if (!ready() || System.currentTimeMillis() < testUntil) return;
        boolean same = r == lastR && g == lastG && b == lastB;
        if (same && System.currentTimeMillis() - lastSendAt < 3000) return;
        lastR = r; lastG = g; lastB = b;
        synchronized (this) { pendingColor = colorCmd(r, g, b); }
        h.post(this::pump);
    }

    /** Test manuel : impose une couleur pendant 8 s, le moteur reprend la main ensuite. */
    void test(int r, int g, int b) {
        testUntil = System.currentTimeMillis() + 8000;
        powerOn();
        synchronized (this) { pendingColor = colorCmd(r, g, b); }
        h.postDelayed(this::pump, 80);
        lastR = lastG = lastB = -1;
    }
}
