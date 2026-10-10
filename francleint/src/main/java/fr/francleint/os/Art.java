package fr.francleint.os;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Morceau en cours : lecteur actif, pochette, et sa couleur dominante (pour les LED). */
final class Art {

    private Art() { }

    /** Le lecteur qui joue (sinon le premier trouvé). Null sans l'autorisation « Afficher la musique ». */
    static MediaController controller(Context c) {
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = m.getActiveSessions(new ComponentName(c, MediaListener.class));
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return mc;
            }
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean playing(MediaController mc) {
        PlaybackState st = mc == null ? null : mc.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    static Bitmap bitmap(MediaMetadata md) {
        if (md == null) return null;
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    /** Clé du morceau (titre + artiste) pour ne recalculer qu'au changement. */
    static String key(MediaMetadata md) {
        if (md == null) return "";
        return md.getString(MediaMetadata.METADATA_KEY_TITLE) + "|" + md.getString(MediaMetadata.METADATA_KEY_ARTIST);
    }

    /** Pochette réduite en JPEG base64, prête pour la page. */
    static String dataUri(Bitmap b) {
        if (b == null) return "";
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 160, 160, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 82, out);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Couleur dominante VIVE de la pochette (les gris, noirs et blancs sont ignorés),
     * poussée en saturation pour rendre bien sur les LED. Null si la pochette est grise.
     */
    static int[] vivid(Bitmap b) {
        if (b == null) return null;
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 24, 24, true);
            double[] w = new double[36];
            double[][] sum = new double[36][3];
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = s.getPixel(x, y);
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] < 0.25f || hsv[2] < 0.20f) continue;
                    double wt = hsv[1] * hsv[2];
                    int bin = Math.min(35, (int) (hsv[0] / 10));
                    w[bin] += wt;
                    sum[bin][0] += Color.red(p) * wt;
                    sum[bin][1] += Color.green(p) * wt;
                    sum[bin][2] += Color.blue(p) * wt;
                }
            }
            int best = -1;
            double bw = 0;
            for (int i = 0; i < 36; i++) {
                double v = w[i] + 0.5 * (w[(i + 35) % 36] + w[(i + 1) % 36]);   // lisse entre teintes voisines
                if (v > bw) { bw = v; best = i; }
            }
            if (best < 0 || w[best] < 8) return null;   // trop peu de couleur : pochette grise
            int r = (int) (sum[best][0] / w[best]), g = (int) (sum[best][1] / w[best]), bl = (int) (sum[best][2] / w[best]);
            Color.RGBToHSV(r, g, bl, hsv);
            hsv[1] = Math.max(hsv[1], 0.8f);
            hsv[2] = 1f;
            int c = Color.HSVToColor(hsv);
            return new int[]{Color.red(c), Color.green(c), Color.blue(c)};
        } catch (Exception e) {
            return null;
        }
    }
}
