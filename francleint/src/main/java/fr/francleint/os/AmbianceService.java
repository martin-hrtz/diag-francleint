package fr.francleint.os;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Le moteur : tourne en fond, lit le contexte (soleil, météo, phares, vitesse, appel)
 * et règle la couleur des LED et la luminosité de l'écran, avec des fondus doux.
 */
public class AmbianceService extends Service {

    static volatile AmbianceService instance;

    // Couleurs de base (pleine intensité)
    static final int[] WARM = {255, 150, 70};      // accueil
    static final int[] AMBER = {255, 130, 20};     // signal « moteur chaud » : 2 clignotements
    static final int[] DAY = {190, 215, 255};      // jour : blanc froid
    static final int[] NIGHT = {20, 170, 85};      // nuit : vert sapin (version LED)
    static final int[] ROAD = {25, 70, 220};       // autoroute : bleu nuit

    // Mode sport : réagit à l'EFFORT du moteur, pas à l'accélération brute.
    // Clio 4 phase 2 0.9 TCe 75 ch : 55 kW, 120 Nm, 0-100 en 12,3 s, ≈ 1150 kg avec le conducteur.
    // À chaque vitesse on calcule l'accélération MAXIMALE possible (puissance / vitesse − air − roulement),
    // et on compare : 100 % = pied au plancher, quelle que soit la vitesse.
    static final double MASS = 1150;       // kg (voiture + conducteur)
    static final double P_MAX = 55000;     // W
    static final double ETA = 0.85;        // rendement de la transmission
    static final double CDA = 0.70;        // Cx × surface frontale (m²)
    static final double CRR = 0.012;       // résistance au roulement
    static final double A_LOW = 2.2;       // m/s² : plafond réaliste en 1re/2e (0-100 en 12,3 s ≈ 2,3 m/s² de moyenne)
    static final double EFFORT_START = 0.12;   // en dessous : conduite normale, bleu
    static final double EFFORT_RED = 0.75;     // au-dessus : rouge
    static final double HUE_BASE = 225;    // bleu en roulant
    static final double HUE_SPAN = 135;    // bleu → violet → magenta → rouge (225° → 360°)

    /** Couleur pure (jamais de blanc) à partir d'une teinte en degrés. */
    static int[] hue(double h) {
        h = ((h % 360) + 360) % 360;
        double x = 1 - Math.abs((h / 60) % 2 - 1);
        double r, g, b;
        if (h < 60) { r = 1; g = x; b = 0; } else if (h < 120) { r = x; g = 1; b = 0; }
        else if (h < 180) { r = 0; g = 1; b = x; } else if (h < 240) { r = 0; g = x; b = 1; }
        else if (h < 300) { r = x; g = 0; b = 1; } else { r = 1; g = 0; b = x; }
        return new int[]{(int) Math.round(r * 255), (int) Math.round(g * 255), (int) Math.round(b * 255)};
    }

    private volatile boolean sportActive = false;   // on roule, mode sport, rien de prioritaire
    private volatile double effort = 0;              // 0 → 1 : part de la puissance utilisée
    private volatile int estHp = 0;                  // puissance estimée (ch), pour l'écran

    static double resist(double v) { return 0.5 * 1.2 * CDA * v * v + CRR * MASS * 9.81; }   // N

    /** Accélération maximale possible à la vitesse v (m/s). */
    static double aMax(double v) {
        double fromPower = (ETA * P_MAX / Math.max(v, 1) - resist(v)) / MASS;
        return Math.max(0.15, Math.min(A_LOW, fromPower));
    }

    private double lastGpsV = -1;          // dernière vitesse GPS (m/s)
    private long lastGpsT = 0;
    private volatile double accel = 0;     // accélération mesurée (m/s²)
    private volatile long accelAt = 0;
    // Anticipation : la mesure GPS décrit la seconde PASSÉE. On prolonge la tendance (jerk) pour estimer l'effort MAINTENANT.
    private volatile double prevAccel = 0, jerk = 0, lastVm = 0;
    private static final double LOOKAHEAD = 0.6;     // s gagnées sur le GPS (≈ moitié de l'intervalle + délai de la puce)
    private double power = 0;              // 0 → 1, lissé
    static final int[] TURN = {255, 100, 0};       // clignotant : orange

    // Clignotant : tenu 1,2 s après le dernier signal (le voyant clignote, on ne veut pas que les LED clignotent)
    private volatile long blinkerUntil = 0;
    volatile boolean blinker = false;
    private double blinkMix = 0;           // 0 = couleur normale, 1 = orange clignotant (fondu)

    /** À appeler à chaque signal de clignotant (source branchée pendant la séance voiture). */
    void blinkerSignal() { blinkerUntil = System.currentTimeMillis() + 1200; }

    boolean obdOk() { return obd != null && obd.fresh(); }

    /** Compteur de l'écran d'accueil, 4 fois / s. */
    String live() {
        try {
            JSONObject o = new JSONObject();
            boolean ok = obdOk();
            o.put("speed", hasFix || ok ? Math.round(speedKmh) : -1);
            o.put("power", Math.round(power * 100));
            o.put("sport", sport);
            o.put("engineWarm", engine == null || engine.warm);
            o.put("hp", ok ? (int) Math.max(0, obd.num("hp", 0)) : System.currentTimeMillis() - accelAt < 2200 ? estHp : 0);
            if (ok) o.put("nm", (int) Math.round(obd.num("q", 0)));
            o.put("ledColor", String.format("#%02X%02X%02X", (int) cr, (int) cg, (int) cb));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Nouvelle ligne du boîtier OBD (5 fois / s, tout de suite pour un clignotant). */
    private void onCar(ObdLink o) {
        if (o.on("cg") || o.on("cd")) blinkerSignal();
        double v = o.num("v", -1);
        if (v >= 0) speedKmh = v;
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private Led led;
    private final Sky sky = new Sky();
    Trip trip;
    Engine engine;
    ObdLink obd;                           // boîtier OBD (écoute seule de la Clio) : vraies données quand il est là
    private volatile long warmFlashAt = 0;
    private SharedPreferences prefs;

    private long startedAt;
    private double lat = 47.24, lon = 6.02; // Besançon tant que le GPS n'a rien
    private boolean hasFix;
    private double speedKmh;
    private long stillSince = 0;
    private long lastWeather = 0;

    // État courant (fondus) et cible
    private double cr, cg, cb, cl;          // couleur et intensité affichées
    private int[] targetColor = WARM;
    private double targetLevel = 0;
    private int lastScreen = -1;
    private long lastScreenAt = 0;

    volatile String context = "accueil";
    volatile double ambient = 0;
    volatile double elevation = 0;
    volatile boolean lights = false;
    volatile boolean inCall = false;
    volatile String sim = "auto";
    volatile boolean sport = true;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("ambiance", MODE_PRIVATE);
        sport = prefs.getBoolean("sport", true);
        trip = new Trip(prefs);
        engine = new Engine(prefs);
        startForegroundCompat();
        startedAt = System.currentTimeMillis();
        led = new Led(this);
        led.start();
        obd = new ObdLink(this, this::onCar);
        obd.start();
        startLocation();
        h.post(tick);
        h.post(fade);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(PROX_TICK);
        registerReceiver(screen, f);
    }

    // ---------- iPhone qui arrive → l'écran se réveille tout seul (avant même d'ouvrir la porte) ----------
    private long phoneGoneAt = 0;
    private PowerManager.WakeLock arrival;

    // Distance estimée par la force du signal Bluetooth de l'iPhone (dBm) :
    //   ≈ -55 : collé à la voiture · ≈ -65 : ~1,5 m · ≈ -80 et moins : loin ou derrière un mur / un plancher
    private volatile int lastRssi = -127;          // dernier signal Apple le plus fort entendu
    private volatile boolean measuring = false;
    private PowerManager.WakeLock scanLock;
    private long arriveScanUntil = 0;

    int wakeRssi() { return prefs.getInt("wakeRssi", -65); }

    private void phoneEvent(Intent i, boolean connected) {
        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        BluetoothClass bc = d != null ? d.getBluetoothClass() : null;
        if (bc == null || bc.getMajorDeviceClass() != BluetoothClass.Device.Major.PHONE) return;   // pas le boîtier LED
        long now = System.currentTimeMillis();
        if (!connected) { phoneGoneAt = now; phoneHere = false; cancelProxTick(); return; }
        phoneHere = true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean asleep = pm != null && !pm.isInteractive();
        if (!asleep || !prefs.getBoolean("wakeOnPhone", true)) return;
        // Le téléphone vient de se connecter, écran endormi : on écoute 2 min la force du signal.
        // On ne réveille que s'il est vraiment PRÈS (assez fort) — à travers un plancher, le signal reste faible.
        try {
            if (scanLock == null) scanLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "francleint:approche");
            if (!scanLock.isHeld()) scanLock.acquire(125_000);
        } catch (Exception ignored) { }
        arriveScanUntil = now + 120_000;
        startProximity();
        scheduleProxTick(125_000);
    }

    // Téléphone resté connecté toute la nuit (ex. dans la chambre au-dessus) : petite écoute de 6 s
    // toutes les 45 s tant que l'écran dort — c'est quand tu descends que le signal devient fort.
    static final String PROX_TICK = "fr.francleint.os.PROX";
    private volatile boolean phoneHere = false;

    private PendingIntent proxIntent() {
        return PendingIntent.getBroadcast(this, 7, new Intent(PROX_TICK).setPackage(getPackageName()),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private void scheduleProxTick(long inMs) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            long at = System.currentTimeMillis() + inMs;
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, proxIntent());
            else am.setExact(AlarmManager.RTC_WAKEUP, at, proxIntent());
        } catch (Exception ignored) { }
    }

    private void cancelProxTick() {
        try { ((AlarmManager) getSystemService(ALARM_SERVICE)).cancel(proxIntent()); } catch (Exception ignored) { }
    }

    private void proxTick() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!phoneHere || pm == null || pm.isInteractive() || !prefs.getBoolean("wakeOnPhone", true)) return;
        try {
            if (scanLock == null) scanLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "francleint:approche");
            if (!scanLock.isHeld()) scanLock.acquire(7_000);
        } catch (Exception ignored) { }
        arriveScanUntil = System.currentTimeMillis() + 6_000;
        startProximity();
        h.postDelayed(this::stopProximityIfDone, 6_100);
        scheduleProxTick(45_000);
    }

    private final ScanCallback prox = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            if (r.getScanRecord() == null || r.getScanRecord().getManufacturerSpecificData(0x004C) == null) return;   // appareils Apple
            int rssi = r.getRssi();
            if (rssi > lastRssi) lastRssi = rssi;
            if (arriveScanUntil > 0 && rssi >= wakeRssi()) { arriveScanUntil = 0; stopProximity(); wakeForArrival(); }
        }
    };

    private void startProximity() {
        try {
            BluetoothLeScanner sc = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();
            if (sc == null || measuring) return;
            lastRssi = -127;
            measuring = true;
            sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), prox);
            h.postDelayed(this::stopProximityIfDone, 121_000);
        } catch (Exception e) { measuring = false; }
    }

    private void stopProximityIfDone() { if (System.currentTimeMillis() >= arriveScanUntil) stopProximity(); }

    private void stopProximity() {
        try { BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner().stopScan(prox); } catch (Exception ignored) { }
        measuring = false;
        try { if (scanLock != null && scanLock.isHeld()) scanLock.release(); } catch (Exception ignored) { }
    }

    private void wakeForArrival() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        try {
            if (arrival != null && arrival.isHeld()) arrival.release();
            arrival = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "francleint:arrivee");
            arrival.acquire(120_000);   // 2 min : si le contact n'est pas mis, l'écran se rendort tout seul
        } catch (Exception ignored) { }
        screenOffAt = 0;
        startedAt = System.currentTimeMillis();
        HomeActivity.welcome(this);
    }

    /** Réglage : « je suis à côté de la voiture » → on mesure 8 s et on règle le seuil un peu en dessous. */
    void calibrate() {
        arriveScanUntil = 0;
        startProximity();
        h.postDelayed(() -> {
            if (lastRssi > -110) prefs.edit().putInt("wakeRssi", lastRssi - 6).apply();
            stopProximity();
        }, 8000);
    }

    void setWakeOnPhone(boolean on) { prefs.edit().putBoolean("wakeOnPhone", on).apply(); }

    // Contact mis : l'écran se rallume après une veille → accueil (LED + animation « Bonjour »)
    private long screenOffAt = 0;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (PROX_TICK.equals(a)) { proxTick(); return; }
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a) || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                phoneEvent(i, BluetoothDevice.ACTION_ACL_CONNECTED.equals(a));
                return;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(a)) { screenOffAt = System.currentTimeMillis(); if (phoneHere) scheduleProxTick(45_000); return; }
            cancelProxTick();
            if (screenOffAt == 0 || System.currentTimeMillis() - screenOffAt < 60_000) return;   // simple extinction rapide : rien
            screenOffAt = 0;
            startedAt = System.currentTimeMillis();
            HomeActivity.welcome(c);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screen); } catch (Exception ignored) { }
        removeOverlay();
        led.stop();
        if (obd != null) obd.stop();
        if (trip != null) trip.save();
        if (engine != null) engine.save(System.currentTimeMillis());
        instance = null;
        super.onDestroy();
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("amb", "Ambiance", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, "amb");
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, AmbianceActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setContentTitle("Ambiance francleint").setContentText("LED et écran s'adaptent à la lumière")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(pi).setOngoing(true);
        startForeground(1, b.build());
    }

    // ---------- Entrées ----------

    private void startLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            LocationListener l = new LocationListener() {
                @Override public void onLocationChanged(Location loc) {
                    lat = loc.getLatitude(); lon = loc.getLongitude(); hasFix = true;
                    if (!obdOk()) speedKmh = loc.hasSpeed() ? loc.getSpeed() * 3.6 : speedKmh;   // le boîtier OBD est plus juste
                    // Point GPS imprécis (> 25 m) : ignoré pour l'effort, sinon il crée des « faux » coups d'accélérateur
                    if (LocationManager.GPS_PROVIDER.equals(loc.getProvider()) && loc.hasSpeed()
                            && !(loc.hasAccuracy() && loc.getAccuracy() > 25)) {
                        long t = loc.getTime();
                        double dt = (t - lastGpsT) / 1000.0;
                        if (lastGpsV >= 0 && dt > 0.3 && dt < 3) {
                            double a = (loc.getSpeed() - lastGpsV) / dt;
                            // Anti-fantômes : léger lissage (60 % nouvelle mesure) + zone morte de ±0,15 m/s² (bruit GPS)
                            boolean fresh = System.currentTimeMillis() - accelAt < 2500;
                            double af = fresh ? 0.6 * a + 0.4 * accel : a;
                            if (Math.abs(af) < 0.15) af = 0;
                            prevAccel = accel; accel = af;
                            accelAt = System.currentTimeMillis();
                            double vm = (loc.getSpeed() + lastGpsV) / 2;        // vitesse moyenne sur l'intervalle
                            lastVm = loc.getSpeed();
                            // Plancher 0,7 m/s² : à haute vitesse le max réel est faible, le bruit GPS y paraissait énorme
                            effort = accel > 0 ? clamp(accel / Math.max(aMax(vm), 0.7), 0, 1) : 0;
                            estHp = accel > 0 ? (int) Math.round((MASS * accel + resist(vm)) * vm / ETA / 735.5) : 0;
                        }
                        lastGpsV = loc.getSpeed(); lastGpsT = t;
                        long nowMs = System.currentTimeMillis();
                        boolean freshA = nowMs - accelAt < 2500;
                        if (trip != null) trip.onFix(nowMs, loc.getSpeed(), freshA ? accel : 0, freshA ? effort : 0);
                    }
                }
                @Override public void onStatusChanged(String p, int s, Bundle e) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, l, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000, 0, l, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            // pas de permission : on garde Besançon et on n'a pas la vitesse
        }
    }

    /** Phares allumés : propriété système du firmware de l'écran (fil « ILL »). */
    private static boolean headlights() {
        String v = prop("forfan_light_state");
        if (v.isEmpty()) return false;
        v = v.toLowerCase();
        return !(v.equals("off") || v.equals("0") || v.equals("false"));
    }

    static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getMethod("get", String.class);
            Object r = m.invoke(null, key);
            return r == null ? "" : r.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean calling() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int m = am.getMode();
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION;
    }

    // ---------- Décision, toutes les secondes ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { decide(); } catch (Exception ignored) { }
            h.postDelayed(this, 1000);
        }
    };

    private void decide() {
        long now = System.currentTimeMillis();
        if (trip != null) trip.tick(now);
        if (showroomUntil > 0 && (now > showroomUntil || speedKmh > 8)) showroomUntil = 0;   // on roule : fin du show

        if (now - lastWeather > 10 * 60_000) {
            lastWeather = now;
            final double la = lat, lo = lon;
            new Thread(() -> sky.refresh(la, lo)).start();
        }

        elevation = Sky.sunElevation(lat, lon, now);
        lights = headlights() || (obdOk() && (obd.on("co") || obd.on("pl")));
        inCall = calling();

        // Lumière dehors estimée, de 0 (nuit noire) à 1 (plein soleil)
        double sun = clamp((elevation + 6) / 18.0, 0, 1);     // -6° → 0, +12° → 1
        double a = sun * sky.factor();
        if (lights) a = Math.min(a, 0.15);                       // phares allumés : tunnel, pluie, nuit

        if (speedKmh < 2) { if (stillSince == 0) stillSince = now; } else stillSince = 0;
        boolean parked = (hasFix || obdOk()) && stillSince > 0 && now - stillSince > 60_000;
        boolean highway = speedKmh > 110;
        boolean welcome = now - startedAt < 4_000;
        boolean call = inCall;

        // Simulation depuis l'écran de contrôle
        switch (sim) {
            case "soleil": a = 1.0; parked = highway = welcome = call = false; break;
            case "pluie": a = 0.33; parked = highway = welcome = call = false; break;
            case "nuit": a = 0.0; parked = highway = welcome = call = false; break;
            case "autoroute": a = 0.6; highway = true; parked = welcome = call = false; break;
            case "arret": a = 0.6; parked = true; highway = welcome = call = false; break;
            case "acc1": case "acc2": parked = welcome = call = false; break;
            case "appel": call = true; welcome = false; break;
            case "accueil": welcome = true; startedAt = now - 1; sim = "auto"; break;
            default: break;
        }
        ambient = a;

        // Moteur froid : LED bleues fixes, pas de mode sport. Chaud : 2 clignotements ambre puis sport activé tout seul.
        double water = obdOk() ? obd.num("e", -100) : -100;
        if (water <= -40) engine.water = -100;
        boolean became = water > -40 ? engine.real(now, water)                      // vraie température d'eau
                : engine.tick(now, (hasFix || obdOk()) && speedKmh > 5, sky.temp);   // sinon : estimation
        if (became) {
            warmFlashAt = now;
            setSport(true);
        }
        boolean cold = !engine.warm && "auto".equals(sim);

        double level = 0.02 + 0.98 * a;          // nuit 2 % (minimum) → plein soleil 100 %
        int[] color;
        if (welcome) {
            color = cold ? hue(HUE_BASE) : WARM;
            level = Math.max(level, 0.6) * Math.min(1, (now - startedAt) / 800.0);
            context = "accueil";
        } else if (call) {
            color = targetColor;                  // on fige la couleur
            level = level * 0.5;
            context = "appel";
        } else if (cold) {
            color = hue(HUE_BASE);                 // moteur froid : bleu, couleur bloquée
            context = "moteur froid";
        } else if (parked) {
            color = parkColor();
            context = "à l'arrêt";
        } else if (sport && (hasFix && speedKmh > 5 || sim.startsWith("acc"))) {
            color = hue(HUE_BASE);                 // en roulant : bleu, l'accélération pousse vers le rouge
            context = "conduite";
        } else if (albumNow() != null && "auto".equals(sim)) {
            color = albumColor;                    // option : la couleur de la pochette du morceau en cours
            context = "musique";
        } else if (highway) {
            color = ROAD;
            level = Math.min(level, 0.6);
            context = "autoroute";
        } else if (prefs.getBoolean("skyLed", true) && "auto".equals(sim)) {
            color = skyColor(elevation, a);            // option : les LED suivent la couleur du ciel
            context = elevation > 10 ? "jour" : elevation > 0 ? "heure dorée" : elevation > -8 ? "heure bleue" : "nuit";
        } else if (a < 0.2) {
            color = NIGHT;
            context = a < 0.02 ? "nuit" : "crépuscule";
        } else {
            color = DAY;
            context = a < 0.5 ? "jour gris" : "jour";
        }
        sportActive = "conduite".equals(context);
        targetColor = color;
        targetLevel = clamp(level, 0, 1);

        // Luminosité de l'écran : 12 % la nuit → 100 % en plein soleil
        setScreen(0.04 + 0.96 * a);
    }

    /** Effort estimé à l'instant présent : dernière mesure + tendance prolongée de LOOKAHEAD s (plafonnée, jamais d'effort inventé à l'arrêt). */
    private double predictedEffort(long now) {
        // L'anticipation par tendance a été retirée : elle prolongeait l'accélération après le lâcher de pédale
        // (et l'inverse). On affiche la dernière mesure fiable, tenue jusqu'à la suivante.
        return effort;
    }

    // ---------- Fondus, 10 fois par seconde ----------

    private final Runnable fade = new Runnable() {
        @Override public void run() {
            blinker = System.currentTimeMillis() < blinkerUntil || "clignotant".equals(sim);
            double k = 0.30;                 // fondu ≈ 0,1 s (invisible)
            // Clignotant : fondu doux vers l'orange (≈ 0,3 s) et retour aussi doux, jamais de coupure franche
            blinkMix += ((blinker ? 1 : 0) - blinkMix) * 0.12;
            int[] tc = targetColor;
            boolean show = showroomUntil > 0;
            if (show && !blinker) {
                // Mode Showroom : vague de couleurs qui fait le tour du cercle en 9 s, avec une respiration
                long tt = System.currentTimeMillis();
                tc = hue((tt % 9000) / 9000.0 * 360);
                double br = 0.75 + 0.25 * Math.sin(tt / 700.0);
                cr = tc[0] * br; cg = tc[1] * br; cb = tc[2] * br; cl = 1;
                led.color((int) Math.round(cr), (int) Math.round(cg), (int) Math.round(cb));
                curDim += (0 - curDim) * 0.05;
                applyOverlay();
                h.postDelayed(this, 30);
                return;
            }
            long fl = System.currentTimeMillis() - warmFlashAt;
            if (fl >= 0 && fl < 1500 && !blinker) {
                // Moteur chaud : 2 clignotements ambre (allumé 350 ms, éteint 250 ms), puis on repart du noir
                boolean on = fl < 350 || (fl >= 600 && fl < 950);
                double v = on ? 1 : 0;
                cr = AMBER[0] * v; cg = AMBER[1] * v; cb = AMBER[2] * v; cl = 1;
                led.color((int) Math.round(cr), (int) Math.round(cg), (int) Math.round(cb));
                applyOverlay();
                h.postDelayed(this, 30);
                return;
            }
            double tl = targetLevel;
            // Accélération → teinte (bleu → violet → magenta → rouge), intensité inchangée
            long now = System.currentTimeMillis();
            double eff = "acc1".equals(sim) ? 0.45 : "acc2".equals(sim) ? 0.9
                    : obdOk() ? clamp(obd.num("q", 0) / 135.0, 0, 1)            // couple réel / couple max du 0.9 TCe
                    : (now - accelAt < 2200 ? predictedEffort(now) : 0);   // un point GPS en retard ne fait plus « retomber » la couleur
            double want = sportActive
                    ? clamp((eff - EFFORT_START) / (EFFORT_RED - EFFORT_START), 0, 1) : 0;
            // Monte en glissant (≈ 0,4 s pour tout le dégradé), redescend doucement (≈ 2,5 s du rouge au bleu).
            // Si tu ré-accélères pendant la descente, ça repart de la couleur actuelle.
            if (want > power) power = Math.min(want, power + (obdOk() ? 0.15 : 0.075));   // boîtier : signal propre, on suit plus vite
            else power = Math.max(want, power - 0.012);
            if (sportActive) {
                tc = hue(HUE_BASE + HUE_SPAN * power);
                k = 1.0;                                 // la teinte est déjà progressive : pas de mélange RGB (évite le blanc)
            }
            cr += (tc[0] - cr) * k;
            cg += (tc[1] - cg) * k;
            cb += (tc[2] - cb) * k;
            cl += (tl - cl) * k;
            double m = blinkMix < 0.01 ? 0 : blinkMix > 0.99 ? 1 : blinkMix, tlv = Math.max(cl, 0.5);
            led.color((int) Math.round(cr * cl * (1 - m) + TURN[0] * tlv * m),
                      (int) Math.round(cg * cl * (1 - m) + TURN[1] * tlv * m),
                      (int) Math.round(cb * cl * (1 - m) + TURN[2] * tlv * m));
            curDim += (targetDim - curDim) * 0.025;
            applyOverlay();
            h.postDelayed(this, 30);   // ≈ 33 images/seconde
        }
    };

    // ---------- Filtre sombre (comme « Réduire luminosité ») ----------

    private View overlay;
    private double targetDim = 0, curDim = 0;
    volatile int dimPct = 0;

    boolean canOverlay() { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this); }

    private void applyOverlay() {
        dimPct = (int) Math.round(curDim * 100);
        if (!canOverlay() || !prefs.getBoolean("dim", true)) { removeOverlay(); return; }
        if (curDim < 0.01) { if (overlay != null) overlay.setAlpha(0f); return; }
        if (overlay == null) {
            try {
                overlay = new View(this);
                overlay.setBackgroundColor(Color.BLACK);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : 2006 /* TYPE_SYSTEM_OVERLAY */,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(overlay, lp);
            } catch (Exception e) { overlay = null; return; }
        }
        overlay.setAlpha((float) curDim);
    }

    private void removeOverlay() {
        if (overlay == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlay); } catch (Exception ignored) { }
        overlay = null;
    }

    // ---------- Option : LED couleur du ciel ----------

    static final int[] GOLD = {255, 140, 40};      // heure dorée
    static final int[] DUSK = {255, 70, 90};       // coucher : rose-rouge
    static final int[] BLUE = {40, 70, 255};       // heure bleue

    static int[] mix(int[] x, int[] y, double t) {
        t = Math.max(0, Math.min(1, t));
        return new int[]{(int) Math.round(x[0] + (y[0] - x[0]) * t), (int) Math.round(x[1] + (y[1] - x[1]) * t), (int) Math.round(x[2] + (y[2] - x[2]) * t)};
    }

    /** Couleur du ciel selon la hauteur du soleil (°) : blanc froid → doré → rose → bleu → vert nuit. Ciel couvert : on reste neutre. */
    static int[] skyColor(double elev, double light) {
        if (elev > 12) return DAY;
        if (elev > 4) return mix(GOLD, DAY, (elev - 4) / 8);
        if (elev > 0) return mix(DUSK, GOLD, elev / 4);
        if (elev > -6) return mix(BLUE, DUSK, (elev + 6) / 6);
        if (elev > -12) return mix(NIGHT, BLUE, (elev + 12) / 6);
        return NIGHT;
    }

    void setSkyLed(boolean on) { prefs.edit().putBoolean("skyLed", on).apply(); }

    // ---------- Mode Showroom ----------

    volatile long showroomUntil = 0;

    /** Lance (10 min max) ou arrête le show. Refusé en roulant. */
    boolean showroom(boolean on) {
        if (on && speedKmh > 8) return false;
        showroomUntil = on ? System.currentTimeMillis() + 10 * 60_000 : 0;
        return true;
    }

    // ---------- Option : LED couleur de la pochette ----------

    private volatile int[] albumColor = null;
    private String albumKey = "";

    void setAlbumLed(boolean on) { prefs.edit().putBoolean("albumLed", on).apply(); }

    /** Couleur de la pochette si l'option est active et qu'un morceau joue ; sinon null. */
    private int[] albumNow() {
        if (!prefs.getBoolean("albumLed", false)) return null;
        android.media.session.MediaController mc = Art.controller(this);
        if (!Art.playing(mc)) return null;
        android.media.MediaMetadata md = mc.getMetadata();
        String k = Art.key(md);
        if (!k.equals(albumKey)) { albumKey = k; albumColor = Art.vivid(Art.bitmap(md)); }
        return albumColor;
    }

    void setDim(boolean on) { prefs.edit().putBoolean("dim", on).apply(); }
    void setSport(boolean on) { sport = on; prefs.edit().putBoolean("sport", on).apply(); }

    private void setScreen(double v) {
        // Le vrai rétroéclairage descend jusqu'à 25 % (le contraste reste bon).
        // En dessous, le filtre sombre prend le relais, seulement la nuit.
        boolean canWrite = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
        if (canWrite) targetDim = clamp((0.30 - v) / 0.30, 0, 1) * 0.70;
        else targetDim = clamp(1 - v, 0, 1) * 0.6;
        if (!canWrite) return;
        v = Math.max(v, 0.25);
        int target = (int) Math.round(clamp(v, 0.05, 1) * 255);
        if (lastScreen < 0) lastScreen = target;
        long now = System.currentTimeMillis();
        if (now - lastScreenAt < 500) return;
        lastScreenAt = now;
        int step = (int) Math.signum(target - lastScreen) * Math.max(1, Math.abs(target - lastScreen) / 6);
        int next = Math.abs(target - lastScreen) <= 2 ? target : lastScreen + step;
        try {
            ContentResolver cr0 = getContentResolver();
            Settings.System.putInt(cr0, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(cr0, Settings.System.SCREEN_BRIGHTNESS, next);
            lastScreen = next;
        } catch (Exception ignored) { }
    }

    int[] parkColor() {
        int c = prefs.getInt("park", 0xFFB070);
        return new int[]{(c >> 16) & 255, (c >> 8) & 255, c & 255};
    }

    void setParkColor(int rgb) { prefs.edit().putInt("park", rgb).apply(); }

    void setSim(String s) { sim = s == null ? "auto" : s; }

    void ledReset() { led.reset(); }

    void ledTest(int rgb) { led.test((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255); }

    void ledProto(int p) { led.setProtocol(p); }

    void ledConnect(String addr) {
        String name;
        synchronized (led.seen) { name = led.seen.get(addr); }
        led.connect(addr, name == null ? "" : name);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---------- Pour l'écran de contrôle ----------

    String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("context", blinker ? "clignotant" : context);
            o.put("sim", sim);
            o.put("ambient", Math.round(ambient * 100));
            o.put("elevation", Math.round(elevation));
            o.put("weather", sky.summary);
            if (!Double.isNaN(sky.temp)) o.put("temp", Math.round(sky.temp));
            o.put("lat", lat);
            o.put("lon", lon);
            o.put("lights", lights);
            o.put("call", inCall);
            o.put("speed", hasFix || obdOk() ? Math.round(speedKmh) : -1);
            if (obd != null) o.put("obd", obd.toJson());
            if (obdOk()) o.put("nm", (int) Math.round(obd.num("q", 0)));
            o.put("gps", hasFix);
            o.put("ledLevel", Math.round(cl * 100));
            o.put("ledColor", String.format("#%02X%02X%02X", (int) cr, (int) cg, (int) cb));
            o.put("screen", lastScreen < 0 ? -1 : Math.round(lastScreen / 2.55));
            o.put("canWrite", Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this));
            o.put("ledState", led.state);
            o.put("ledName", led.deviceName);
            o.put("ledReady", led.ready());
            o.put("gatt", led.gattInfo);
            o.put("proto", led.protocol());
            o.put("dim", dimPct);
            o.put("dimOn", prefs.getBoolean("dim", true));
            o.put("sport", sport);
            if (engine != null) {
                o.put("engineWarm", engine.warm);
                o.put("enginePct", engine.pct());
                o.put("engineMin", engine.water > -40 ? 0 : engine.minutesLeft(sky.temp));
                if (obdOk() && engine.water > -40) o.put("water", Math.round(engine.water));
            }
            o.put("albumLed", prefs.getBoolean("albumLed", false));
            o.put("skyLed", prefs.getBoolean("skyLed", true));
            o.put("showroom", showroomUntil > 0);
            if (trip != null) o.put("trip", trip.toJson());
            o.put("wakeOnPhone", prefs.getBoolean("wakeOnPhone", true));
            o.put("wakeRssi", wakeRssi());
            o.put("rssi", lastRssi);
            o.put("measuring", measuring);
            o.put("accel", Math.round((System.currentTimeMillis() - accelAt < 2500 ? accel : 0) * 10) / 10.0);
            o.put("power", Math.round(power * 100));
            o.put("hp", obdOk() ? (int) Math.max(0, obd.num("hp", 0)) : System.currentTimeMillis() - accelAt < 2200 ? estHp : 0);
            o.put("canOverlay", canOverlay());
            boolean locOn = false;
            try { locOn = ((LocationManager) getSystemService(LOCATION_SERVICE)).isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
            o.put("locOn", locOn);
            JSONArray seen = new JSONArray();
            synchronized (led.seen) {
                for (Map.Entry<String, String> e : led.seen.entrySet()) {
                    JSONObject d = new JSONObject();
                    d.put("addr", e.getKey());
                    d.put("name", e.getValue());
                    seen.put(d);
                }
            }
            o.put("seen", seen);
            int p = prefs.getInt("park", 0xFFB070);
            o.put("park", String.format("#%06X", p));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    static void start(Context c) {
        Intent i = new Intent(c, AmbianceService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
}
