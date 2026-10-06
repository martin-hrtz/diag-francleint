package fr.francleint.os;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pont entre la page web et Android. Tout est en LECTURE SEULE,
 * sauf l'écriture du rapport dans Téléchargements / sur la clé USB.
 */
public class DiagBridge {

    private final DiagActivity act;

    DiagBridge(DiagActivity act) {
        this.act = act;
    }

    // ---------- Collecte ----------

    @JavascriptInterface
    public String collect() {
        JSONObject o = new JSONObject();
        try {
            o.put("android_affiche", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("android_reel", realAndroid(Build.VERSION.SDK_INT));
            o.put("build_id", Build.ID);
            o.put("build_display", Build.DISPLAY);
            o.put("build_fingerprint", Build.FINGERPRINT);
            o.put("build_time", Build.TIME);
            o.put("modele", Build.MODEL);
            o.put("fabricant", Build.MANUFACTURER);
            o.put("carte", Build.BOARD);
            o.put("hardware", Build.HARDWARE);
            o.put("plateforme", prop("ro.board.platform"));
            o.put("puce_hint", firstNonEmpty(prop("ro.hardware.chipname"),
                    prop("ro.board.platform"), prop("ro.product.board"), Build.HARDWARE));
            o.put("coeurs", Runtime.getRuntime().availableProcessors());
            o.put("cpuinfo", readFile("/proc/cpuinfo", 6000));

            ActivityManager am = (ActivityManager) act.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            o.put("ram_totale", mi.totalMem);
            o.put("ram_libre", mi.availMem);
            o.put("ram_faible", mi.lowMemory);

            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            o.put("stockage_total", st.getTotalBytes());
            o.put("stockage_libre", st.getAvailableBytes());

            o.put("allume_depuis_ms", SystemClock.elapsedRealtime());
            o.put("temperatures", thermal());

            WifiManager wm = (WifiManager) act.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            JSONObject wifi = new JSONObject();
            if (wm != null) {
                wifi.put("actif", wm.isWifiEnabled());
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    if (Build.VERSION.SDK_INT >= 21) wifi.put("frequence_mhz", wi.getFrequency());
                    wifi.put("signal_dbm", wi.getRssi());
                    wifi.put("debit_mbps", wi.getLinkSpeed());
                }
                if (Build.VERSION.SDK_INT >= 21) wifi.put("5ghz_supporte", wm.is5GHzBandSupported());
            }
            o.put("wifi", wifi);

            JSONObject bt = new JSONObject();
            try {
                BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                bt.put("present", ba != null);
                if (ba != null) bt.put("actif", ba.isEnabled());
            } catch (Exception ignored) { }
            o.put("bluetooth", bt);

            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            o.put("ecran", dm.widthPixels + "x" + dm.heightPixels + " @" + dm.densityDpi + "dpi");

            o.put("applis", packages());
            o.put("entrees", inputDevices());
            o.put("proprietes", allProps());
        } catch (Exception e) {
            try { o.put("erreur", e.toString()); } catch (Exception ignored) { }
        }
        return o.toString();
    }

    private static String realAndroid(int sdk) {
        switch (sdk) {
            case 21: return "5.0";
            case 22: return "5.1";
            case 23: return "6";
            case 24: return "7.0";
            case 25: return "7.1";
            case 26: return "8.0";
            case 27: return "8.1";
            case 28: return "9";
            case 29: return "10";
            case 30: return "11";
            case 31: return "12";
            case 32: return "12L";
            case 33: return "13";
            case 34: return "14";
            case 35: return "15";
            default: return "API " + sdk;
        }
    }

    private JSONArray packages() throws Exception {
        PackageManager pm = act.getPackageManager();
        List<PackageInfo> list = pm.getInstalledPackages(0);
        JSONArray arr = new JSONArray();
        for (PackageInfo p : list) {
            JSONObject a = new JSONObject();
            a.put("id", p.packageName);
            a.put("version", p.versionName == null ? "" : p.versionName);
            boolean sys = p.applicationInfo != null
                    && (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            a.put("systeme", sys);
            a.put("active", p.applicationInfo == null || p.applicationInfo.enabled);
            try {
                a.put("nom", String.valueOf(p.applicationInfo.loadLabel(pm)));
            } catch (Exception ignored) { }
            arr.put(a);
        }
        return arr;
    }

    private JSONArray inputDevices() throws Exception {
        JSONArray arr = new JSONArray();
        InputManager im = (InputManager) act.getSystemService(Context.INPUT_SERVICE);
        if (im == null) return arr;
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d != null) arr.put(d.getName());
        }
        return arr;
    }

    private JSONArray thermal() throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < 12; i++) {
            String t = readFile("/sys/class/thermal/thermal_zone" + i + "/temp", 32).trim();
            if (t.isEmpty()) continue;
            JSONObject z = new JSONObject();
            z.put("zone", readFile("/sys/class/thermal/thermal_zone" + i + "/type", 64).trim());
            z.put("valeur", t);
            arr.put(z);
        }
        return arr;
    }

    private static String prop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String allProps() {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null && sb.length() < 60000) sb.append(line).append('\n');
            r.close();
        } catch (Exception ignored) { }
        return sb.toString();
    }

    private static String readFile(String path, int max) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            String line;
            while ((line = r.readLine()) != null && sb.length() < max) sb.append(line).append('\n');
            r.close();
        } catch (Exception ignored) { }
        return sb.toString();
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.trim().isEmpty()) return s.trim();
        return "";
    }

    // ---------- Test du volant ----------

    @JavascriptInterface
    public void keyTest(boolean on) {
        act.setKeyTest(on);
    }

    // ---------- Export ----------

    /** Écrit le rapport dans Téléchargements et sur chaque clé USB branchée. */
    @JavascriptInterface
    public String export(String text) {
        String name = "diag-francleint-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.FRANCE).format(new Date()) + ".txt";
        JSONArray ok = new JSONArray();
        JSONArray ko = new JSONArray();

        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        write(new File(dl, name), text, ok, ko);

        File[] dirs = act.getExternalFilesDirs(null);
        if (dirs != null) {
            for (int i = 1; i < dirs.length; i++) {
                if (dirs[i] != null) write(new File(dirs[i], name), text, ok, ko);
            }
        }
        JSONObject res = new JSONObject();
        try {
            res.put("fichier", name);
            res.put("ok", ok);
            res.put("erreurs", ko);
        } catch (Exception ignored) { }
        return res.toString();
    }

    private static void write(File f, String text, JSONArray ok, JSONArray ko) {
        try {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(text);
            w.close();
            ok.put(f.getAbsolutePath());
        } catch (Exception e) {
            ko.put(f.getAbsolutePath() + " : " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void quit() {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.finish();
            }
        });
    }
}
