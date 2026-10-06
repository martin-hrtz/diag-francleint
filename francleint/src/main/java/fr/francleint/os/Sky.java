package fr.francleint.os;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Le ciel : hauteur du soleil (calculée hors ligne) et météo en direct (Open-Meteo, gratuit, sans compte). */
class Sky {

    /** Hauteur du soleil au-dessus de l'horizon, en degrés. */
    static double sunElevation(double lat, double lon, long timeMs) {
        double d = timeMs / 86400000.0 + 2440587.5 - 2451545.0;
        double g = Math.toRadians(norm(357.529 + 0.98560028 * d));
        double q = norm(280.459 + 0.98564736 * d);
        double L = Math.toRadians(norm(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)));
        double e = Math.toRadians(23.439 - 0.00000036 * d);
        double ra = Math.atan2(Math.cos(e) * Math.sin(L), Math.cos(L));
        double dec = Math.asin(Math.sin(e) * Math.sin(L));
        double gmstH = ((18.697374558 + 24.06570982441908 * d) % 24 + 24) % 24;
        double lst = Math.toRadians(norm(gmstH * 15 + lon));
        double ha = lst - ra;
        double la = Math.toRadians(lat);
        return Math.toDegrees(Math.asin(Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(ha)));
    }

    private static double norm(double deg) { return ((deg % 360) + 360) % 360; }

    // ---- météo ----
    volatile long weatherAt = 0;
    volatile int cloud = -1;          // % de nuages
    volatile double rain = 0;         // mm/h
    volatile int code = -1;           // code météo WMO
    volatile String summary = "inconnue";

    /** À appeler hors du fil principal. */
    void refresh(double lat, double lon) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=cloud_cover,precipitation,weather_code",
                    lat, lon));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
            cloud = cur.optInt("cloud_cover", -1);
            rain = cur.optDouble("precipitation", 0);
            code = cur.optInt("weather_code", -1);
            weatherAt = System.currentTimeMillis();
            summary = describe();
        } catch (Exception e) {
            if (weatherAt == 0) summary = "indisponible (pas d'internet ?)";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    boolean fresh() { return weatherAt > 0 && System.currentTimeMillis() - weatherAt < 3600_000; }

    /** Facteur de clarté dû à la météo : 1 = ciel dégagé, 0,35 = pluie / brouillard. */
    double factor() {
        if (!fresh()) return 1.0;
        double f = 1.0 - 0.45 * Math.max(0, cloud) / 100.0;
        boolean wet = rain > 0.1 || code >= 45; // brouillard, bruine, pluie, neige, orage
        if (wet) f = Math.min(f, 0.35);
        return f;
    }

    private String describe() {
        if (code >= 95) return "orage";
        if (code >= 71 && code <= 86) return "neige";
        if (code >= 51 || rain > 0.1) return "pluie";
        if (code == 45 || code == 48) return "brouillard";
        if (cloud >= 70) return "couvert";
        if (cloud >= 30) return "nuageux";
        return "dégagé";
    }
}
