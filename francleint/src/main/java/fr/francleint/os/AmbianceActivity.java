package fr.francleint.os;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

/** Écran de contrôle : montre ce que le moteur voit et décide, et permet de simuler. */
public class AmbianceActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        web.addJavascriptInterface(new Bridge(), "Amb");
        web.loadUrl("file:///android_asset/www/ambiance/index.html");
        setContentView(web);
        immersive();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        // On démarre dans tous les cas : sans GPS, le moteur se base sur Besançon et l'heure.
        AmbianceService.start(this);
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

    class Bridge {
        @JavascriptInterface
        public String status() {
            AmbianceService s = AmbianceService.instance;
            return s == null ? "{\"ledState\":\"démarrage\"}" : s.status();
        }

        @JavascriptInterface
        public void sim(String mode) {
            AmbianceService s = AmbianceService.instance;
            if (s != null) s.setSim(mode);
        }

        @JavascriptInterface
        public void park(String hex) {
            AmbianceService s = AmbianceService.instance;
            if (s != null) s.setParkColor(Integer.parseInt(hex.replace("#", ""), 16));
        }

        @JavascriptInterface
        public void ledTest(String hex) {
            AmbianceService s = AmbianceService.instance;
            if (s != null) s.ledTest(Integer.parseInt(hex.replace("#", ""), 16));
        }

        @JavascriptInterface
        public void ledProto(int p) {
            AmbianceService s = AmbianceService.instance;
            if (s != null) s.ledProto(p);
        }

        @JavascriptInterface
        public void allowOverlay() {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }

        @JavascriptInterface
        public void setDim(boolean on) {
            AmbianceService s = AmbianceService.instance;
            if (s != null) s.setDim(on);
        }

        @JavascriptInterface
        public void calibrate() {
            AmbianceService s = AmbianceService.instance;
            if (s != null) s.calibrate();
        }

        @JavascriptInterface
        public void setWakeOnPhone(boolean on) {
            AmbianceService s = AmbianceService.instance;
            if (s != null) s.setWakeOnPhone(on);
        }

        @JavascriptInterface
        public void setSport(boolean on) {
            AmbianceService s = AmbianceService.instance;
            if (s != null) s.setSport(on);
        }

        @JavascriptInterface
        public void ledReset() {
            AmbianceService s = AmbianceService.instance;
            if (s != null) s.ledReset();
        }

        @JavascriptInterface
        public void ledConnect(String addr) {
            AmbianceService s = AmbianceService.instance;
            if (s != null) s.ledConnect(addr);
        }

        @JavascriptInterface
        public void allowScreen() {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }

        @JavascriptInterface
        public void quit() {
            runOnUiThread(AmbianceActivity.this::finish);
        }
    }
}
