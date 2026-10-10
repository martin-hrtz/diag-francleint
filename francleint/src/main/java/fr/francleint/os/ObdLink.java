package fr.francleint.os;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
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

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Liaison Bluetooth avec le boîtier OBD « francleint-OBD » (ESP32, écoute seule de la Clio 4).
 * Le boîtier envoie 5 fois par seconde une ligne JSON terminée par \n (découpée en morceaux de 180 octets) :
 *   v vitesse km/h · r régime · e eau °C · q couple Nm · hp chevaux · a accélérateur % · f frein 0..100
 *   s volant ≈ degrés · g rapport (-1 = marche arrière, 0 = neutre/embrayé) · km kilométrage
 *   cg/cd clignotant gauche/droit · vl veilleuses · co codes · pl pleins phares
 *   pc/pp porte conducteur/passager · cf coffre · vr verrouillé · ar marche arrière
 *   em embrayage 0/1/2 · fm frein à main · ce ceinture conducteur · dg dégivrage · lv lave-glace · lr 1 limiteur / 2 régulateur
 * L'appli ne fait QUE lire : on n'écrit jamais rien vers le boîtier.
 */
class ObdLink {

    static final String NAME = "francleint-OBD";
    static final UUID SVC = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");   // boîtier → écran
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    interface Listener { void onCar(ObdLink o); }

    private final Context ctx;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    private final SharedPreferences prefs;
    private BluetoothGatt gatt;
    private boolean scanning;
    private long attemptAt = 0, lastScan = 0;
    private final StringBuilder buf = new StringBuilder();

    volatile String state = "en attente";
    volatile long at = 0;                 // dernière ligne reçue
    volatile JSONObject car = new JSONObject();

    ObdLink(Context ctx, Listener l) {
        this.ctx = ctx;
        this.listener = l;
        this.prefs = ctx.getSharedPreferences("obd", Context.MODE_PRIVATE);
    }

    /** Données de moins de 3 s : le boîtier est là et la voiture parle. */
    boolean fresh() { return System.currentTimeMillis() - at < 3000; }

    double num(String k, double def) { JSONObject c = car; return c.has(k) ? c.optDouble(k, def) : def; }
    boolean on(String k) { return car.optInt(k, 0) == 1; }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                long now = System.currentTimeMillis();
                boolean silent = gatt != null && at > 0 && now - at > 20000 && now - attemptAt > 20000;
                if (silent) { close(); state = "boîtier muet, reconnexion"; }
                if (gatt == null && !scanning && now - attemptAt > 15000) {
                    String addr = prefs.getString("addr", null);
                    // Adresse connue : on la rappelle directement (pas de recherche Bluetooth, qui gênait les LED).
                    // Recherche seulement si aucun boîtier n'a jamais été vu, et au plus une fois par minute.
                    if (addr != null) connect(addr);
                    else if (now - lastScan > 60000) { lastScan = now; scan(); }
                }
            } catch (Exception ignored) { }
            h.postDelayed(this, 5000);
        }
    };

    void start() {
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, 5000);
        if (adapter == null) { state = "pas de Bluetooth"; return; }
        String addr = prefs.getString("addr", null);
        if (addr != null) connect(addr); else scan();
    }

    void stop() {
        h.removeCallbacks(watchdog);
        stopScan();
        close();
    }

    private void close() {
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { } }
        gatt = null;
    }

    private void scan() {
        if (adapter == null || gatt != null || scanning) return;
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) { state = "Bluetooth éteint"; return; }
        scanning = true;
        attemptAt = System.currentTimeMillis();
        state = "recherche du boîtier";
        try { sc.startScan(scanCb); } catch (Exception e) { scanning = false; state = "recherche impossible"; return; }
        h.postDelayed(() -> { stopScan(); if (gatt == null) state = "boîtier introuvable (contact coupé ?)"; }, 10000);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { adapter.getBluetoothLeScanner().stopScan(scanCb); } catch (Exception ignored) { }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            String n = r.getDevice().getName();
            if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
            if (NAME.equals(n) && gatt == null) { stopScan(); connect(r.getDevice().getAddress()); }
        }
    };

    private void connect(String address) {
        stopScan();
        close();
        try {
            BluetoothDevice d = adapter.getRemoteDevice(address);
            attemptAt = System.currentTimeMillis();
            state = "connexion au boîtier";
            prefs.edit().putString("addr", address).apply();
            gatt = Build.VERSION.SDK_INT >= 23 ? d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) : d.connectGatt(ctx, false, cb);
        } catch (Exception e) { state = "connexion impossible"; gatt = null; }
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                state = "connecté";
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) { }   // liaison radio la plus rapide
                try { g.requestMtu(185); } catch (Exception e) { g.discoverServices(); }
            } else {
                try { g.close(); } catch (Exception ignored) { }
                if (g == gatt) gatt = null;
                state = "déconnecté (contact coupé ?)";
                attemptAt = System.currentTimeMillis() - 10000;   // le chien de garde relance dans ≈ 5 s
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) { g.discoverServices(); }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SVC);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(TX);
            if (c == null) { state = "boîtier inconnu"; return; }
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d != null) { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }  // abonnement local, rien n'est envoyé à la voiture
            state = "reçoit la voiture";
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            synchronized (buf) {
                buf.append(new String(v, StandardCharsets.UTF_8));
                if (buf.length() > 2000) buf.setLength(0);
                int k;
                while ((k = buf.indexOf("\n")) >= 0) {
                    String line = buf.substring(0, k).trim();
                    buf.delete(0, k + 1);
                    if (!line.startsWith("{\"v\"")) continue;          // seules les lignes « voiture »
                    try {
                        car = new JSONObject(line);
                        at = System.currentTimeMillis();
                        if (listener != null) listener.onCar(ObdLink.this);   // direct : pas d'attente derrière l'écran
                    } catch (Exception ignored) { }
                }
            }
        }
    };

    /** Résumé pour l'écran (status JSON). */
    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", fresh());
            o.put("state", state);
            if (fresh()) {
                JSONObject c = car;
                java.util.Iterator<String> it = c.keys();
                while (it.hasNext()) { String k = it.next(); o.put(k, c.get(k)); }
            }
        } catch (Exception ignored) { }
        return o;
    }
}
