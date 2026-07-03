package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.MathUtils;

/**
 * Procedurally synthesised sound effects: a looping thruster rumble, a net-catch
 * twang, and a muffled cargo thud. WAV files are generated on first run into
 * ~/.jfighter/sfx/ and loaded from there. If anything fails (no audio device,
 * unwritable disk) the game keeps running silently.
 */
public class SoundFx {
    public static final float THRUSTER_VOLUME = 0.5f; // at full thrust
    public static final float CATCH_VOLUME = 0.7f;
    public static final float THUD_MAX_VOLUME = 0.9f;

    private static final int RATE = 22050;

    private Sound thruster;
    private Sound twang;
    private Sound thud;
    private final Sound[] cannons = new Sound[3];
    private Sound laser;
    private Sound rocket;
    private Sound clamp;
    private Sound snap;
    private Sound ping;
    private Sound beamLoop;
    private long beamId = -1;
    private long thrusterId = -1;
    private boolean ready;

    public SoundFx() {
        try {
            FileHandle dir = Gdx.files.external(".jfighter/sfx/");
            thruster = load(dir.child("thruster.wav"), synthThruster());
            twang = load(dir.child("twang.wav"), synthTwang());
            thud = load(dir.child("thud.wav"), synthThud());
            cannons[0] = load(dir.child("cannon0.wav"), synthCannon(0.09f, 340f, 60f));
            cannons[1] = load(dir.child("cannon1.wav"), synthCannon(0.16f, 200f, 90f));
            cannons[2] = load(dir.child("cannon2.wav"), synthCannon(0.3f, 110f, 140f));
            laser = load(dir.child("laser.wav"), synthLaser());
            rocket = load(dir.child("rocket.wav"), synthRocket());
            clamp = load(dir.child("clamp.wav"), synthClamp());
            snap = load(dir.child("snap.wav"), synthSnap());
            ping = load(dir.child("ping.wav"), synthPing());
            beamLoop = load(dir.child("beamloop.wav"), synthBeamLoop());
            ready = true;
        } catch (Exception e) {
            Gdx.app.error("SoundFx", "audio disabled: " + e.getMessage());
        }
    }

    private static Sound load(FileHandle file, float[] samples) {
        if (!file.exists()) writeWav(file, samples);
        return Gdx.audio.newSound(file);
    }

    public void startThruster() {
        if (ready && thrusterId == -1) thrusterId = thruster.loop(0f);
    }

    public void setThrusterLevel(float level) {
        if (ready && thrusterId != -1) thruster.setVolume(thrusterId, level * THRUSTER_VOLUME);
    }

    public void stopThruster() {
        if (ready && thrusterId != -1) {
            thruster.stop(thrusterId);
            thrusterId = -1;
        }
    }

    public void playCatch() {
        if (ready) twang.play(CATCH_VOLUME);
    }

    /** strength 0..1 scales the thud volume. */
    public void playThud(float strength) {
        if (ready) thud.play(MathUtils.clamp(strength, 0.15f, THUD_MAX_VOLUME));
    }

    /** Snappy cannon report; caliber 0 = light, 2 = heavy. */
    public void playCannon(int caliber) {
        if (ready) cannons[MathUtils.clamp(caliber, 0, 2)].play(0.4f + 0.2f * caliber);
    }

    public void playLaser() {
        if (ready) laser.play(0.4f);
    }

    public void playRocket() {
        if (ready) rocket.play(0.55f);
    }

    public void playClamp() {
        if (ready) clamp.play(0.55f);
    }

    public void playSnap() {
        if (ready) snap.play(0.5f);
    }

    public void playPing() {
        if (ready) ping.play(0.35f);
    }

    public void startBeam() {
        if (ready && beamId == -1) beamId = beamLoop.loop(0.35f);
    }

    public void stopBeam() {
        if (ready && beamId != -1) {
            beamLoop.stop(beamId);
            beamId = -1;
        }
    }

    public void dispose() {
        if (!ready) return;
        stopBeam();
        stopThruster();
        thruster.dispose();
        twang.dispose();
        thud.dispose();
        for (Sound c : cannons) c.dispose();
        laser.dispose();
        rocket.dispose();
        clamp.dispose();
        snap.dispose();
        ping.dispose();
        beamLoop.dispose();
    }

    // --- synthesis ---

    /** 1s loop of low-passed noise: an engine rumble with no tonal seam. */
    private static float[] synthThruster() {
        int n = RATE;
        float[] s = new float[n];
        float y = 0f;
        for (int i = 0; i < n; i++) {
            float white = MathUtils.random(-1f, 1f);
            y += 0.08f * (white - y); // low-pass: keeps only the low rumble
            s[i] = y;
        }
        // crossfade the tail into the head so the loop point doesn't click
        int f = RATE / 20;
        for (int i = 0; i < f; i++) {
            float t = i / (float) f;
            s[n - f + i] = s[n - f + i] * (1f - t) + s[i] * t;
        }
        return normalise(s, 0.9f);
    }

    /** Descending plucked tone for a successful net catch. */
    private static float[] synthTwang() {
        int n = (int) (RATE * 0.45f);
        float[] s = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            double freq = 320 * Math.exp(-t * 2.2);
            phase += 2 * Math.PI * freq / RATE;
            s[i] = (float) ((Math.sin(phase) + 0.4 * Math.sin(2 * phase)) * Math.exp(-t * 8));
        }
        return normalise(s, 0.8f);
    }

    /** Low muffled boom for bumping heavy cargo — space thud, all bass. */
    private static float[] synthThud() {
        int n = (int) (RATE * 0.35f);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float body = (float) (Math.sin(2 * Math.PI * 64 * t + 2 * Math.exp(-t * 30)) * Math.exp(-t * 12));
            float knock = MathUtils.random(-1f, 1f) * (float) Math.exp(-t * 120) * 0.25f;
            s[i] = body + knock;
        }
        return normalise(s, 0.9f);
    }

    /** HighFleet-style cannon: click attack, noise crack, low tonal punch. */
    private static float[] synthCannon(float dur, float punchHz, float crackDecay) {
        int n = (int) (RATE * (dur + 0.1f));
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float crack = MathUtils.random(-1f, 1f) * (float) Math.exp(-t * crackDecay);
            float punch = (float) (Math.sin(2 * Math.PI * punchHz * t * (1 - t * 0.9))
                * Math.exp(-t / dur * 6));
            float click = i < 40 ? 1f - i / 40f : 0f;
            s[i] = crack * 0.7f + punch + click * 0.5f;
        }
        return normalise(s, 0.9f);
    }

    /** Zap: fast descending square sweep. */
    private static float[] synthLaser() {
        int n = (int) (RATE * 0.16f);
        float[] s = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            double freq = 1500 * Math.exp(-t * 14) + 180;
            phase += 2 * Math.PI * freq / RATE;
            float sq = Math.sin(phase) > 0 ? 1f : -1f;
            s[i] = sq * (float) Math.exp(-t * 18);
        }
        return normalise(s, 0.7f);
    }

    /** Whoosh: band-passed noise swelling then trailing off. */
    private static float[] synthRocket() {
        int n = (int) (RATE * 0.55f);
        float[] s = new float[n];
        float y = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float white = MathUtils.random(-1f, 1f);
            y += 0.25f * (white - y);
            float env = (float) (Math.sin(Math.PI * Math.min(1, t / 0.55f)) * Math.exp(-t * 2));
            s[i] = y * env;
        }
        return normalise(s, 0.85f);
    }

    /** Metallic double click for the pincer jaws. */
    private static float[] synthClamp() {
        int n = (int) (RATE * 0.22f);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float k1 = (float) (Math.sin(2 * Math.PI * 900 * t) * Math.exp(-t * 60));
            float t2 = t - 0.09f;
            float k2 = t2 > 0 ? (float) (Math.sin(2 * Math.PI * 620 * t2) * Math.exp(-t2 * 50)) : 0f;
            s[i] = k1 + k2;
        }
        return normalise(s, 0.75f);
    }

    /** Bright short pluck for a strand snapping or splicing. */
    private static float[] synthSnap() {
        int n = (int) (RATE * 0.2f);
        float[] s = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            double freq = 640 * Math.exp(-t * 5);
            phase += 2 * Math.PI * freq / RATE;
            s[i] = (float) ((Math.sin(phase) + 0.3 * Math.sin(3 * phase)) * Math.exp(-t * 22));
        }
        return normalise(s, 0.7f);
    }

    /** Soft console ping for the autopilot. */
    private static float[] synthPing() {
        int n = (int) (RATE * 0.14f);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            s[i] = (float) (Math.sin(2 * Math.PI * 880 * t) * Math.exp(-t * 26));
        }
        return normalise(s, 0.5f);
    }

    /** Continuous beam hum: tonal core + crackle, loop-crossfaded like the thruster. */
    private static float[] synthBeamLoop() {
        int n = RATE / 2;
        float[] s = new float[n];
        float y = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float hum = (float) (Math.sin(2 * Math.PI * 180 * t) * 0.5
                + Math.sin(2 * Math.PI * 273 * t) * 0.3);
            float white = MathUtils.random(-1f, 1f);
            y += 0.5f * (white - y);
            s[i] = hum + y * 0.25f;
        }
        int fkeep = RATE / 24;
        for (int i = 0; i < fkeep; i++) {
            float t = i / (float) fkeep;
            s[n - fkeep + i] = s[n - fkeep + i] * (1f - t) + s[i] * t;
        }
        return normalise(s, 0.6f);
    }

    private static float[] normalise(float[] s, float peak) {
        float max = 0f;
        for (float v : s) max = Math.max(max, Math.abs(v));
        if (max == 0f) return s;
        for (int i = 0; i < s.length; i++) s[i] = s[i] / max * peak;
        return s;
    }

    /** Minimal 16-bit mono PCM WAV writer. */
    private static void writeWav(FileHandle file, float[] samples) {
        int dataLen = samples.length * 2;
        byte[] b = new byte[44 + dataLen];
        putAscii(b, 0, "RIFF");
        putIntLE(b, 4, 36 + dataLen);
        putAscii(b, 8, "WAVE");
        putAscii(b, 12, "fmt ");
        putIntLE(b, 16, 16);
        putShortLE(b, 20, 1);  // PCM
        putShortLE(b, 22, 1);  // mono
        putIntLE(b, 24, RATE);
        putIntLE(b, 28, RATE * 2);
        putShortLE(b, 32, 2);  // block align
        putShortLE(b, 34, 16); // bits per sample
        putAscii(b, 36, "data");
        putIntLE(b, 40, dataLen);
        for (int i = 0; i < samples.length; i++) {
            short v = (short) (MathUtils.clamp(samples[i], -1f, 1f) * Short.MAX_VALUE);
            putShortLE(b, 44 + i * 2, v);
        }
        file.writeBytes(b, false);
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putShortLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}
