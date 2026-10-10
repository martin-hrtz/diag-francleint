package fr.francleint.os;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

/**
 * Son moteur factice, synthétisé en direct à partir du VRAI régime et du VRAI couple (boîtier OBD).
 * 0.9 TCe = 3 cylindres 4 temps : 1 explosion tous les 240° → fréquence d'explosion = régime / 40 Hz.
 * Le son est discret en croisière et monte quand on appuie (couple). Il se mélange à la musique
 * (pas de prise de « focus audio » : CarPlay continue de jouer). Coupé par défaut, réglable dans les Réglages.
 */
final class EngineSound {

    private static final int RATE = 22050;
    private volatile boolean enabled = false;
    private volatile float volume = 0.6f;       // 0 → 1, réglage utilisateur
    private volatile float rpm = 0, load = 0;    // valeurs cibles
    private volatile long lastData = 0;
    private Thread thread;

    void setEnabled(boolean on) {
        enabled = on;
        if (on && thread == null) { thread = new Thread(this::run, "son-moteur"); thread.start(); }
    }

    void setVolume(float v) { volume = Math.max(0, Math.min(1, v)); }

    /** Appelé à chaque ligne du boîtier : régime (tr/min) et charge 0 → 1 (couple / couple maxi). */
    void update(double r, double l) {
        rpm = (float) r;
        load = (float) Math.max(0, Math.min(1, l));
        lastData = System.currentTimeMillis();
    }

    void stop() { enabled = false; }

    private void run() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufLen = Math.max(min, 1024);
        AudioTrack t;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(bufLen * 2).setTransferMode(AudioTrack.MODE_STREAM).build();
            } else {
                t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufLen * 2, AudioTrack.MODE_STREAM);
            }
        } catch (Exception e) { thread = null; return; }

        short[] buf = new short[512];          // ≈ 23 ms par bloc : réaction rapide
        double phase = 0, r = 0, l = 0, amp = 0, lp = 0, noiseLp = 0;
        long seed = 1234567;
        boolean playing = false;
        while (true) {
            boolean live = enabled && System.currentTimeMillis() - lastData < 1500 && rpm > 400;
            if (!live) {
                if (playing) { try { t.pause(); t.flush(); } catch (Exception ignored) { } playing = false; }
                try { Thread.sleep(enabled ? 150 : 400); } catch (InterruptedException e) { break; }
                amp = 0;
                continue;
            }
            if (!playing) { try { t.play(); } catch (Exception ignored) { } playing = true; }
            float tr = rpm, tl = load, vol = volume;
            for (int i = 0; i < buf.length; i++) {
                r += (tr - r) * 0.0015;              // lissage ≈ 30 ms
                l += (tl - l) * 0.0010;
                double targetAmp = vol * (0.10 + 0.90 * l * l);   // presque muet en croisière, plein quand on charge
                amp += (targetAmp - amp) * 0.0008;
                double f = r / 40.0;                 // fréquence d'explosion (3 cylindres)
                phase += 2 * Math.PI * f / RATE;
                if (phase > 2 * Math.PI * 1000) phase -= 2 * Math.PI * 1000;
                // Grondement : fondamentale + demi-ordre (vilebrequin) + harmoniques, saturé doucement
                double x = Math.sin(phase) + 0.55 * Math.sin(phase * 0.5) + 0.45 * Math.sin(2 * phase)
                        + 0.25 * Math.sin(3 * phase) + 0.15 * Math.sin(4 * phase);
                // Souffle d'admission / turbo : bruit filtré, monte avec la charge et le régime
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                double n = ((seed >>> 33) / (double) (1L << 31)) - 1.0;
                noiseLp += (n - noiseLp) * (0.05 + 0.25 * Math.min(1, r / 6000));
                x += noiseLp * (0.15 + 0.9 * l) * Math.min(1, r / 2500);
                x = Math.tanh(x * (1.2 + 1.5 * l)) * 0.8;
                lp += (x - lp) * 0.35;                // adoucit les aigus
                buf[i] = (short) Math.max(-32767, Math.min(32767, lp * amp * 26000));
            }
            try { t.write(buf, 0, buf.length); } catch (Exception e) { break; }
        }
        try { t.stop(); t.release(); } catch (Exception ignored) { }
        thread = null;
    }
}
