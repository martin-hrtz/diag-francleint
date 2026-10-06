package fr.francleint.os;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mises à jour depuis l'appli : regarde la dernière version publiée sur GitHub,
 * la télécharge et lance l'installation par-dessus (même signature).
 */
class Updater {

    /** Dépôts publics où sont publiés les APK, dans l'ordre. */
    static final String[] REPOS = {"martin-hrtz/francleint-app", "martin-hrtz/diag-francleint"};
    static final String ASSET = "francleint.apk";

    static volatile String state = "";      // texte affiché
    static volatile int progress = -1;      // 0..100 pendant le téléchargement
    static volatile int latest = -1;
    static volatile String url = null;

    static int current(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "francleint");
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Hors du fil principal. */
    static void check(Context ctx) {
        state = "Recherche…";
        latest = -1;
        url = null;
        for (String repo : REPOS) {
            try {
                JSONObject rel = new JSONObject(get("https://api.github.com/repos/" + repo + "/releases/latest"));
                String tag = rel.optString("tag_name", "");          // ex. « v1.17 »
                int n = Integer.parseInt(tag.replaceAll(".*\\.", "").replaceAll("[^0-9]", ""));
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (ASSET.equals(a.optString("name"))) {
                        latest = n;
                        url = a.getString("browser_download_url");
                    }
                }
                if (url != null) break;
            } catch (Exception ignored) {
                // dépôt absent ou privé : on essaie le suivant
            }
        }
        int cur = current(ctx);
        if (url == null) state = "Impossible de vérifier (pas d'internet ?)";
        else if (latest > cur) state = "Version 1." + latest + " disponible";
        else state = "À jour (version 1." + cur + ")";
    }

    static boolean available(Context ctx) { return url != null && latest > current(ctx); }

    /** Hors du fil principal : télécharge puis ouvre l'installateur. */
    static void install(Context ctx) {
        if (!available(ctx)) return;
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            state = "Autorise francleint à installer, puis réessaie";
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return;
        }
        File f = new File(ctx.getCacheDir(), "update.apk");
        try {
            state = "Téléchargement…";
            progress = 0;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int total = c.getContentLength();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[16384];
                int n, done = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) progress = (int) (done * 100L / total);
                }
            } finally {
                c.disconnect();
            }
            progress = -1;
            state = "Installation…";
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("content://" + ctx.getPackageName() + ".files/update.apk"),
                    "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            progress = -1;
            state = "Échec du téléchargement : " + e.getMessage();
        }
    }
}
