package fr.francleint.os;

import android.content.SharedPreferences;

/**
 * Moteur froid / chaud, ESTIMÉ en attendant le boîtier OBD (qui donnera la vraie température d'eau).
 * On vise l'HUILE, plus lente que l'eau : un 0.9 TCe turbo n'aime pas être poussé avant ≈ 80 °C d'huile.
 *
 * chaleur 0 → 1 : monte quand on roule (plus vite s'il fait doux), un peu au ralenti,
 * redescend moteur coupé (≈ 45 min pour perdre les 2/3). Sauvegardée : l'écran est tué à chaque coupure.
 */
final class Engine {

    static final double TAU_COOL_MIN = 45;   // refroidissement moteur coupé
    static final double IDLE_RATE = 0.35;    // au ralenti, le moteur chauffe ≈ 3 fois moins vite
    static final double STAY_WARM = 0.6;     // déjà chaud : on reste « chaud » tant qu'on est au-dessus

    private final SharedPreferences p;
    double heat;          // 0 = froid, 1 = chaud
    boolean warm;
    long lastTick, lastSave;

    Engine(SharedPreferences p) {
        this.p = p;
        long now = System.currentTimeMillis();
        long at = p.getLong("e_at", 0);
        heat = p.getFloat("e_heat", 0);
        if (at > 0 && now > at) heat *= Math.exp(-(now - at) / 60000.0 / TAU_COOL_MIN);
        warm = heat >= STAY_WARM && p.getBoolean("e_warm", false);
    }

    /** Minutes de route pour être chaud, selon la température dehors (NaN = inconnue). */
    static double warmupMin(double outsideC) {
        double t = Double.isNaN(outsideC) ? 10 : outsideC;
        return Math.max(7, Math.min(22, 10 + (15 - t) * 0.4));   // 25 °C → 7 min · 15 °C → 10 · 0 °C → 16 · -10 °C → 20
    }

    /**
     * Toutes les secondes. Renvoie true À L'INSTANT où le moteur devient chaud (pour le clignotement).
     */
    boolean tick(long now, boolean moving, double outsideC) {
        double dt = lastTick == 0 ? 0 : Math.min(5, (now - lastTick) / 1000.0);
        lastTick = now;
        heat = Math.min(1, heat + dt / (warmupMin(outsideC) * 60) * (moving ? 1 : IDLE_RATE));
        boolean became = false;
        if (!warm && heat >= 1) { warm = true; became = true; }
        else if (warm && heat < STAY_WARM) warm = false;
        if (became || now - lastSave > 15000) save(now);
        return became;
    }

    /** Vraie température d'eau du boîtier OBD (°C), -100 = inconnue. */
    double water = -100;
    static final double WARM_C = 80, COLD_C = 60;   // chaud à 80 °C d'eau, redevient froid sous 60 °C

    /**
     * Version boîtier OBD : on suit la vraie température d'eau au lieu de l'estimation.
     * La chaleur estimée est recalée dessus, pour rester juste si le boîtier se tait.
     */
    boolean real(long now, double waterC) {
        water = waterC;
        lastTick = now;
        heat = Math.max(0, Math.min(1, (waterC - 20) / (WARM_C - 20)));
        boolean became = false;
        if (!warm && waterC >= WARM_C) { warm = true; became = true; }
        else if (warm && waterC < COLD_C) warm = false;
        if (became || now - lastSave > 15000) save(now);
        return became;
    }

    /** 0 → 100 % de chauffe, pour l'écran. */
    int pct() { return warm ? 100 : (int) Math.floor(heat * 100); }

    /** Minutes restantes estimées en roulant. */
    int minutesLeft(double outsideC) { return warm ? 0 : (int) Math.ceil((1 - heat) * warmupMin(outsideC)); }

    void save(long now) {
        lastSave = now;
        p.edit().putFloat("e_heat", (float) heat).putLong("e_at", now).putBoolean("e_warm", warm).apply();
    }
}
