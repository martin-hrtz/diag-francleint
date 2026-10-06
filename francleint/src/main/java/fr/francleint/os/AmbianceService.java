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

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("ambiance", MODE_PRIVATE);
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
        boolean welcome = now - startedAt < 10_000;
        boolean call = inCall;

        // Simulation depuis l'écran de contrôle
        switch (sim) {
            case "soleil": a = 1.0; parked = highway = welcome = call = false; break;
            case "pluie": a = 0.33; parked = highway = welcome = call = false; break;
            case "nuit": a = 0.0; parked = highway = welcome = call = false; break;
            case "autoroute": a = 0.6; highway = true; parked = welcome = call = false; break;
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
            level = Math.max(level, 0.6) * Math.min(1, (now - startedAt) / 3000.0);
            context = "accueil";
        } else if (call) {
            color = targetColor;                  // on fige la couleur
            level = level * 0.5;
            context = "appel";
        } else if (parked) {
            color = parkColor();
            context = "à l'arrêt";
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
        targetColor = color;
        targetLevel = clamp(level, 0, 1);

        // Luminosité de l'écran : 12 % la nuit → 100 % en plein soleil
        setScreen(0.12 + 0.88 * a);
    }

    // ---------- Fondus, 10 fois par seconde ----------

    private final Runnable fade = new Runnable() {
        @Override public void run() {
            double k = 0.12; // ~2 s pour faire 90 % du chemin
            cr += (targetColor[0] - cr) * k;
            cg += (targetColor[1] - cg) * k;
            cb += (targetColor[2] - cb) * k;
            cl += (targetLevel - cl) * k;
            led.color((int) Math.round(cr * cl), (int) Math.round(cg * cl), (int) Math.round(cb * cl));
            h.postDelayed(this, 100);
        }
    };

    private void setScreen(double v) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(this)) return;
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
            o.put("context", context);
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
