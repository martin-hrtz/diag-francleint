package fr.francleint.os;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Relance le moteur à chaque démarrage de l'écran. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try { AmbianceService.start(c); } catch (Exception ignored) { }
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) HomeActivity.welcome(c);
    }
}
