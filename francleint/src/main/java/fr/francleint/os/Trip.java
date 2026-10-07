package fr.francleint.os;

import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Le trajet en cours et le dernier trajet terminé, mesurés au GPS.
 * Consommation estimée par la physique (puissance aux roues → carburant), sans boîtier OBD.
 * Tout est sauvegardé : la veille de l'écran ferme l'appli, le trajet reprend ou se clôt au réveil.
 */
final class Trip {

    // Carburant : rendement moteur moyen en usage réel, frottements/ralenti, énergie de l'essence
    static final double ENGINE_EFF = 0.30;     // part de l'énergie de l'essence transformée en travail
    static final double FRICTION_W = 3000;     // pertes à vitesse nulle (ralenti, accessoires) ≈ 0,35 L/h
    static final double KWH_PER_L = 8.9;       // énergie d'un litre d'essence
    static final long END_AFTER_MS = 120_000;  // 2 min sans rouler : trajet terminé
    static final double MIN_KM = 0.3;          // en dessous : manœuvre, pas un trajet

    private final SharedPreferences p;

    boolean active;
    long startAt, lastMoveAt, lastFixAt;
    double km, movingS, maxKmh, maxEffort, effortSum, fuelKwh;

    Trip(SharedPreferences p) {
        this.p = p;
        active = p.getBoolean("t_active", false);
        startAt = p.getLong("t_start", 0);
        lastMoveAt = p.getLong("t_move", 0);
        km = p.getFloat("t_km", 0);
        movingS = p.getFloat("t_mov", 0);
        maxKmh = p.getFloat("t_max", 0);
        maxEffort = p.getFloat("t_maxe", 0);
        effortSum = p.getFloat("t_esum", 0);
        fuelKwh = p.getFloat("t_fuel", 0);
        // Appli relancée longtemps après le dernier mouvement : le trajet d'avant est fini
        if (active && System.currentTimeMillis() - lastMoveAt > END_AFTER_MS) finish(lastMoveAt);
    }

    /** Un point GPS fiable : vitesse (m/s), accélération lissée (m/s²), effort (0→1). */
    void onFix(long now, double v, double accel, double effort) {
        double dt = lastFixAt == 0 ? 0 : (now - lastFixAt) / 1000.0;
        lastFixAt = now;
        if (!active) {
            if (v < 3) return;                                   // départ à partir de ≈ 10 km/h
            active = true; startAt = now; lastMoveAt = now;
            km = movingS = maxKmh = maxEffort = effortSum = fuelKwh = 0;
            dt = 0;
        }
        if (dt <= 0 || dt > 3) return;                           // trou de réception : on ne compte pas
        if (v > 0.5) { lastMoveAt = now; movingS += dt; effortSum += effort * dt; }
        km += v * dt / 1000.0;
        maxKmh = Math.max(maxKmh, v * 3.6);
        maxEffort = Math.max(maxEffort, effort);
        double wheel = Math.max(0, (AmbianceService.MASS * accel + AmbianceService.resist(v)) * v);   // W aux roues
        double fuelW = wheel / (AmbianceService.ETA * ENGINE_EFF) + FRICTION_W;
        fuelKwh += fuelW * dt / 3_600_000.0;
        if ((int) (now / 15000) != (int) ((now - (long) (dt * 1000)) / 15000)) save();   // sauvegarde toutes les 15 s
    }

    /** Toutes les secondes : clôt le trajet après 2 min d'arrêt. */
    void tick(long now) {
        if (active && now - lastMoveAt > END_AFTER_MS) finish(lastMoveAt);
    }

    private void finish(long endAt) {
        active = false;
        if (km >= MIN_KM) {
            try {
                JSONObject o = summary(endAt);
                o.put("endAt", endAt);
                p.edit().putString("t_last", o.toString()).apply();
            } catch (Exception ignored) { }
        }
        save();
    }

    double liters() { return fuelKwh / KWH_PER_L; }

    JSONObject summary(long until) throws Exception {
        JSONObject o = new JSONObject();
        o.put("km", Math.round(km * 10) / 10.0);
        o.put("min", Math.max(1, Math.round((until - startAt) / 60000.0)));
        o.put("maxKmh", Math.round(maxKmh));
        o.put("avgKmh", movingS > 30 ? Math.round(km / (movingS / 3600.0)) : 0);
        o.put("maxEffort", Math.round(maxEffort * 100));
        o.put("avgEffort", movingS > 0 ? Math.round(effortSum / movingS * 100) : 0);
        o.put("liters", Math.round(liters() * 100) / 100.0);
        o.put("l100", km > 0.5 ? Math.round(liters() / km * 1000) / 10.0 : 0);
        return o;
    }

    /** Pour la page : trajet en cours (si actif) et dernier trajet terminé. */
    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            if (active) o.put("now", summary(System.currentTimeMillis()));
            String last = p.getString("t_last", "");
            if (!last.isEmpty()) o.put("last", new JSONObject(last));
        } catch (Exception ignored) { }
        return o;
    }

    void save() {
        p.edit().putBoolean("t_active", active).putLong("t_start", startAt).putLong("t_move", lastMoveAt)
                .putFloat("t_km", (float) km).putFloat("t_mov", (float) movingS).putFloat("t_max", (float) maxKmh)
                .putFloat("t_maxe", (float) maxEffort).putFloat("t_esum", (float) effortSum).putFloat("t_fuel", (float) fuelKwh)
                .apply();
    }
}
