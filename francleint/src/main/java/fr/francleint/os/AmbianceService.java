package fr.francleint.os;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
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
    static final int[] DAY = {190, 215, 255};      // jour : blanc froid
    static final int[] NIGHT = {20, 170, 85};      // nuit : vert sapin (version LED)
    static final int[] ROAD = {25, 70, 220};       // autoroute : bleu nuit

    // Mode sport : couleur selon la vitesse (km/h → couleur), dégradé continu entre les paliers
    static final double[] SPD = {0, 30, 50, 80, 110, 130};
    static final int[][] SPD_C = {
            {0, 230, 120},    //   0 : vert
            {0, 220, 200},    //  30 : vert d'eau
            {0, 120, 255},    //  50 : bleu (ville)
            {130, 40, 255},   //  80 : violet (route)
            {255, 0, 150},    // 110 : magenta (voie rapide)
            {255, 0, 0}};     // 130 : rouge (autoroute)

    static int[] speedColor(double v) {
        if (v <= SPD[0]) return SPD_C[0];
        for (int i = 1; i < SPD.length; i++) {
            if (v <= SPD[i]) {
                double t = (v - SPD[i - 1]) / (SPD[i] - SPD[i - 1]);
                int[] a = SPD_C[i - 1], b = SPD_C[i];
                return new int[]{(int) (a[0] + (b[0] - a[0]) * t), (int) (a[1] + (b[1] - a[1]) * t), (int) (a[2] + (b[2] - a[2]) * t)};
            }
        }
        return SPD_C[SPD.length - 1];
    }

    private double smoothSpeed = 0;   // vitesse lissée (le GPS saute un peu)
    static final int[] TURN = {255, 100, 0};       // clignotant : orange

    // Clignotant : tenu 1,2 s après le dernier signal (le voyant clignote, on ne veut pas que les LED clignotent)
    private volatile long blinkerUntil = 0;
    volatile boolean blinker = false;

    /** À appeler à chaque signal de clignotant (source branchée pendant la séance voiture). */
    void blinkerSignal() { blinkerUntil = System.currentTimeMillis() + 1200; }

    private final Handler h = new Handler(Looper.getMainLooper());
    private Led led;
    private final Sky sky = new Sky();
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
    private volatile boolean pulse = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("ambiance", MODE_PRIVATE);
        sport = prefs.getBoolean("sport", true);
        startForegroundCompat();
        startedAt = System.currentTimeMillis();
        led = new Led(this);
        led.start();
        startLocation();
        h.post(tick);
        h.post(fade);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        removeOverlay();
        led.stop();
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
                    speedKmh = loc.hasSpeed() ? loc.getSpeed() * 3.6 : speedKmh;
                }
                @Override public void onStatusChanged(String p, int s, Bundle e) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, l, Looper.getMainLooper());
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

        if (now - lastWeather > 10 * 60_000) {
            lastWeather = now;
            final double la = lat, lo = lon;
            new Thread(() -> sky.refresh(la, lo)).start();
        }

        elevation = Sky.sunElevation(lat, lon, now);
        lights = headlights();
        inCall = calling();

        // Lumière dehors estimée, de 0 (nuit noire) à 1 (plein soleil)
        double sun = clamp((elevation + 6) / 18.0, 0, 1);     // -6° → 0, +12° → 1
        double a = sun * sky.factor();
        if (lights) a = Math.min(a, 0.15);                       // phares allumés : tunnel, pluie, nuit

        if (speedKmh < 2) { if (stillSince == 0) stillSince = now; } else stillSince = 0;
        boolean parked = hasFix && stillSince > 0 && now - stillSince > 60_000;
        boolean highway = speedKmh > 110;
        double v = hasFix ? speedKmh : -1;
        boolean welcome = now - startedAt < 4_000;
        boolean call = inCall;

        // Simulation depuis l'écran de contrôle
        switch (sim) {
            case "soleil": a = 1.0; parked = highway = welcome = call = false; break;
            case "pluie": a = 0.33; parked = highway = welcome = call = false; break;
            case "nuit": a = 0.0; parked = highway = welcome = call = false; break;
            case "autoroute": a = 0.6; highway = true; v = 120; parked = welcome = call = false; break;
            case "v30": a = 0.6; v = 30; parked = highway = welcome = call = false; break;
            case "v50": a = 0.6; v = 50; parked = highway = welcome = call = false; break;
            case "v90": a = 0.6; v = 90; parked = highway = welcome = call = false; break;
            case "v130": a = 0.6; v = 135; parked = highway = welcome = call = false; break;
            case "arret": a = 0.6; parked = true; highway = welcome = call = false; break;
            case "appel": call = true; welcome = false; break;
            case "accueil": welcome = true; startedAt = now - 1; sim = "auto"; break;
            default: break;
        }
        ambient = a;

        double level = 0.06 + 0.94 * a;          // nuit 6 % → plein soleil 100 %
        int[] color;
        if (welcome) {
            color = WARM;
            level = Math.max(level, 0.6) * Math.min(1, (now - startedAt) / 800.0);
            context = "accueil";
        } else if (call) {
            color = targetColor;                  // on fige la couleur
            level = level * 0.5;
            context = "appel";
        } else if (parked) {
            color = parkColor();
            context = "à l'arrêt";
        } else if (v >= 0 && sport && (v > 3 || !"auto".equals(sim))) {
            // Mode sport : la couleur suit la vitesse, l'intensité monte avec
            smoothSpeed += (v - smoothSpeed) * ("auto".equals(sim) ? 0.35 : 1.0);
            double f = clamp(smoothSpeed / 130.0, 0, 1);
            color = speedColor(smoothSpeed);
            level = level * (0.65 + 0.35 * f);                 // +35 % à fond, reste doux la nuit
            pulse = smoothSpeed >= 130;                          // respiration lente au-delà de 130
            context = Math.round(smoothSpeed) + " km/h";
        } else if (highway) {
            color = ROAD;
            level = Math.min(level, 0.6);
            context = "autoroute";
        } else if (a < 0.2) {
            color = NIGHT;
            context = a < 0.02 ? "nuit" : "crépuscule";
        } else {
            color = DAY;
            context = a < 0.5 ? "jour gris" : "jour";
        }
        if (!context.endsWith("km/h")) pulse = false;
        targetColor = color;
        targetLevel = clamp(level, 0, 1);

        // Luminosité de l'écran : 12 % la nuit → 100 % en plein soleil
        setScreen(0.12 + 0.88 * a);
    }

    // ---------- Fondus, 10 fois par seconde ----------

    private final Runnable fade = new Runnable() {
        @Override public void run() {
            blinker = System.currentTimeMillis() < blinkerUntil || "clignotant".equals(sim);
            double k = blinker ? 0.8 : 0.35; // clignotant : quasi instantané ; sinon ~0,6 s
            int[] tc = blinker ? TURN : targetColor;
            double tl = blinker ? Math.max(targetLevel, 0.5) : targetLevel;
            if (pulse && !blinker) tl *= 0.85 + 0.15 * Math.sin(System.currentTimeMillis() / 1000.0 * Math.PI);
            cr += (tc[0] - cr) * k;
            cg += (tc[1] - cg) * k;
            cb += (tc[2] - cb) * k;
            cl += (tl - cl) * k;
            led.color((int) Math.round(cr * cl), (int) Math.round(cg * cl), (int) Math.round(cb * cl));
            curDim += (targetDim - curDim) * 0.08;
            applyOverlay();
            h.postDelayed(this, 100);
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

    void setDim(boolean on) { prefs.edit().putBoolean("dim", on).apply(); }
    void setSport(boolean on) { sport = on; prefs.edit().putBoolean("sport", on).apply(); }

    private void setScreen(double v) {
        // Le vrai rétroéclairage descend jusqu'à 30 % (le contraste reste bon).
        // En dessous, le filtre sombre prend le relais, seulement la nuit.
        boolean canWrite = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
        if (canWrite) targetDim = clamp((0.30 - v) / 0.30, 0, 1) * 0.55;
        else targetDim = clamp(1 - v, 0, 1) * 0.6;
        if (!canWrite) return;
        v = Math.max(v, 0.30);
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
            o.put("lights", lights);
            o.put("call", inCall);
            o.put("speed", hasFix ? Math.round(speedKmh) : -1);
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
