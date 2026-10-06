package fr.francleint.os;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

/** L'écran d'accueil francleint (launcher). */
public class HomeActivity extends Activity {

    static final String[] CARPLAY = {"com.suding.speedplay", "com.ts.carplayapp"};
    static final String[] CAMERA = {"com.autochips.avmplayer"};

    private WebView web;
    private SharedPreferences prefs;
    private static boolean autoLaunched = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        } else {
            AmbianceService.start(this);
        }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(0xFF0A0F0C);
        web.addJavascriptInterface(new HomeBridge(), "Home");
        web.loadUrl("file:///android_asset/www/home/index.html");
        setContentView(web);
        immersive();

        // Juste après le démarrage de l'écran : on ouvre CarPlay tout seul.
        if (!autoLaunched && prefs.getBoolean("autoCarplay", true) && SystemClock.elapsedRealtime() < 5 * 60_000) {
            autoLaunched = true;
            new Handler(Looper.getMainLooper()).postDelayed(() -> launchFirst(CARPLAY), 4000);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        AmbianceService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onHome && window.onHome()", null);
    }

    @Override
    public void onBackPressed() {
        // Écran d'accueil : le retour ferme seulement les pages internes.
        web.evaluateJavascript("window.onBack && window.onBack()", null);
    }

    private void immersive() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    // ---------- Lancements ----------

    boolean launchPkg(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); return true; } catch (Exception e) { return false; }
    }

    void launchFirst(String[] pkgs) {
        for (String p : pkgs) if (launchPkg(p)) return;
        toast("Appli introuvable sur cet écran");
    }

    boolean tryStart(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return true; } catch (Exception e) { return false; }
    }

    void launchRadio() {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = getPackageManager().queryIntentActivities(main, 0);
        for (ResolveInfo r : all) {
            String label = String.valueOf(r.loadLabel(getPackageManager())).toLowerCase();
            String name = (r.activityInfo.packageName + "/" + r.activityInfo.name).toLowerCase();
            if (label.contains("radio") || name.contains("radio")) {
                Intent i = new Intent(Intent.ACTION_MAIN).setComponent(
                        new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
                if (tryStart(i)) return;
            }
        }
        if (!launchPkg("com.ts.MainUI")) toast("Radio introuvable");
    }

    void openLockSettings() {
        Intent[] tries = {
                new Intent("android.app.action.SET_NEW_PASSWORD"),
                new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.password.ChooseLockGeneric")),
                new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.ChooseLockGeneric")),
                new Intent(Settings.ACTION_SECURITY_SETTINGS),
        };
        for (Intent i : tries) if (tryStart(i)) return;
        toast("Menu de verrouillage introuvable");
    }

    void toast(final String t) {
        runOnUiThread(() -> Toast.makeText(this, t, Toast.LENGTH_SHORT).show());
    }

    // ---------- Musique ----------

    MediaController controller() {
        try {
            MediaSessionManager m = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = m.getActiveSessions(new ComponentName(this, MediaListener.class));
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return c;
            }
            return list.isEmpty() ? null : list.get(0);
        } catch (SecurityException e) {
            return null;
        }
    }

    boolean mediaAllowed() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    void mediaKey(int code) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    static String icon(Drawable d) {
        try {
            int s = 96;
            Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, s, s);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- Pont avec la page ----------

    class HomeBridge {

        @JavascriptInterface
        public String status() {
            try {
                JSONObject o = new JSONObject();
                AmbianceService a = AmbianceService.instance;
                o.put("ambient", a == null ? -1 : Math.round(a.ambient * 100));
                o.put("context", a == null ? "" : a.context);

                // iPhone connecté (Bluetooth audio ou téléphone)
                boolean bt = false;
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad != null && ad.isEnabled()) {
                    bt = ad.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                            || ad.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED;
                }
                o.put("phone", bt);

                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                String wifi = "";
                if (wm != null && wm.isWifiEnabled()) {
                    WifiInfo wi = wm.getConnectionInfo();
                    if (wi != null && wi.getNetworkId() != -1 && Build.VERSION.SDK_INT >= 21) {
                        wifi = wi.getFrequency() > 4900 ? "5 GHz" : "2,4 GHz";
                    }
                }
                o.put("wifi", wifi);

                o.put("mediaAllowed", mediaAllowed());
                MediaController c = controller();
                if (c != null && c.getMetadata() != null) {
                    MediaMetadata md = c.getMetadata();
                    o.put("title", md.getString(MediaMetadata.METADATA_KEY_TITLE));
                    o.put("artist", md.getString(MediaMetadata.METADATA_KEY_ARTIST));
                    PlaybackState st = c.getPlaybackState();
                    o.put("playing", st != null && st.getState() == PlaybackState.STATE_PLAYING);
                }
                o.put("autoCarplay", prefs.getBoolean("autoCarplay", true));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void open(String what) {
            runOnUiThread(() -> {
                switch (what) {
                    case "carplay": launchFirst(CARPLAY); break;
                    case "radio": launchRadio(); break;
                    case "camera": launchFirst(CAMERA); break;
                    case "ambiance": tryStart(new Intent(HomeActivity.this, AmbianceActivity.class)); break;
                    case "diag": tryStart(new Intent(HomeActivity.this, DiagActivity.class)); break;
                    case "lock": openLockSettings(); break;
                    case "wifi": tryStart(new Intent(Settings.ACTION_WIFI_SETTINGS)); break;
                    case "bluetooth": tryStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); break;
                    case "android": tryStart(new Intent(Settings.ACTION_SETTINGS)); break;
                    case "home": if (!tryStart(new Intent(Settings.ACTION_HOME_SETTINGS))) tryStart(new Intent(Settings.ACTION_SETTINGS)); break;
                    case "media": tryStart(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); break;
                    case "stock": launchPkg("com.ts.MainUI"); break;
                    case "miravision": if (!launchPkg("com.mediatek.miravision.ui")) toast("MiraVision introuvable"); break;
                    default: break;
                }
            });
        }

        @JavascriptInterface
        public void launch(String pkg) {
            runOnUiThread(() -> { if (!launchPkg(pkg)) toast("Impossible d'ouvrir"); });
        }

        @JavascriptInterface
        public String apps() {
            JSONArray arr = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> all = pm.queryIntentActivities(main, 0);
                Collections.sort(all, new ResolveInfo.DisplayNameComparator(pm));
                for (ResolveInfo r : all) {
                    if (r.activityInfo.packageName.equals(getPackageName())) continue;
                    JSONObject o = new JSONObject();
                    o.put("pkg", r.activityInfo.packageName);
                    o.put("name", String.valueOf(r.loadLabel(pm)));
                    o.put("icon", icon(r.loadIcon(pm)));
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface
        public void media(String action) {
            switch (action) {
                case "prev": mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
                case "next": mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); break;
                default: mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            }
        }

        @JavascriptInterface
        public void updateCheck() {
            new Thread(() -> Updater.check(HomeActivity.this)).start();
        }

        @JavascriptInterface
        public void updateInstall() {
            new Thread(() -> Updater.install(HomeActivity.this)).start();
        }

        @JavascriptInterface
        public String updateStatus() {
            try {
                JSONObject o = new JSONObject();
                o.put("current", Updater.current(HomeActivity.this));
                o.put("state", Updater.state);
                o.put("progress", Updater.progress);
                o.put("available", Updater.available(HomeActivity.this));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setAutoCarplay(boolean on) {
            prefs.edit().putBoolean("autoCarplay", on).apply();
        }
    }
}
