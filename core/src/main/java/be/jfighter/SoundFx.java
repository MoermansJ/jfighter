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
    /** Master volume 0..1, persisted from the options screen (#114). */
    public static float masterVolume = 1f;
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
    private Sound report;
    private final Sound[] explosions = new Sound[3];
    private Sound klaxon;
    private long klaxonId = -1;
    private long beamId = -1;
    private long thrusterId = -1;
    private boolean ready;

    public SoundFx() {
        try {
            FileHandle dir = Gdx.files.external(".jfighter/sfx/");
            thruster = load(dir.child("thruster2.wav"), synthThruster()); // v2: seamless loop (#133)
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
            beamLoop = load(dir.child("beamloop2.wav"), synthBeamLoop()); // v2: seamless loop (#133)
            report = load(dir.child("report155.wav"), synthReport());
            explosions[0] = load(dir.child("boom_a.wav"), synthExplosion(0.55f, 45f, 7f));
            explosions[1] = load(dir.child("boom_b.wav"), synthExplosion(0.8f, 32f, 4.5f));
            explosions[2] = load(dir.child("boom_c.wav"), synthExplosion(0.4f, 60f, 10f));
            klaxon = load(dir.child("klaxon.wav"), synthKlaxon());
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
        if (ready && thrusterId != -1) thruster.setVolume(thrusterId, level * THRUSTER_VOLUME * masterVolume);
    }

    public void stopThruster() {
        if (ready && thrusterId != -1) {
            thruster.stop(thrusterId);
            thrusterId = -1;
        }
    }

    public void playCatch() {
        if (ready) twang.play(CATCH_VOLUME * masterVolume);
    }

    /** strength 0..1 scales the thud volume. */
    public void playThud(float strength) {
        if (ready) thud.play(MathUtils.clamp(strength, 0.15f, THUD_MAX_VOLUME) * masterVolume);
    }

    /** Snappy cannon report; caliber 0 = light, 2 = heavy. */
    // #187: the player sits inside the hull — exterior reports arrive muffled
    private static final float EXT_VOL = 0.7f;
    private static final float EXT_PITCH = 0.8f;

    public void playCannon(int caliber) {
        if (ready) {
            cannons[MathUtils.clamp(caliber, 0, 2)]
                .play((0.4f + 0.2f * caliber) * EXT_VOL * masterVolume, EXT_PITCH, 0f);
        }
    }

    public void playLaser() {
        if (ready) laser.play(0.4f * EXT_VOL * masterVolume, EXT_PITCH, 0f);
    }

    public void playRocket() {
        if (ready) rocket.play(0.55f * EXT_VOL * masterVolume, EXT_PITCH, 0f);
    }

    public void playClamp() {
        if (ready) clamp.play(0.55f * masterVolume);
    }

    public void playSnap() {
        if (ready) snap.play(0.5f * masterVolume);
    }

    public void playPing() {
        if (ready) ping.play(0.35f * masterVolume);
    }

    /** The contact monitor's sonar pulse: the same ping, quiet and regular. */
    public void playSonar() {
        if (ready) ping.play(0.1f * masterVolume, 0.75f, 0f); // lower pitch, under the mix
    }

    /** Near-death alarm (#182): starts/stops the looping klaxon. */
    public void startAlarm() {
        if (ready && klaxonId == -1) klaxonId = klaxon.loop(0.4f * masterVolume);
    }

    public void stopAlarm() {
        if (ready && klaxonId != -1) {
            klaxon.stop(klaxonId);
            klaxonId = -1;
        }
    }

    /** Hull hit with the shield down: one of a few different blasts, varied per impact. */
    public void playExplosion(float strength) {
        if (!ready) return;
        Sound s = explosions[MathUtils.random(explosions.length - 1)];
        // hull hits are structural: felt more than heard, deep through the deck (#187)
        s.play(MathUtils.clamp(strength, 0.3f, 0.85f) * masterVolume,
            MathUtils.random(0.65f, 0.85f), MathUtils.random(-0.3f, 0.3f));
    }

    /** The 155's muzzle blast (#155): a proper artillery report. */
    public void playReport() {
        if (ready) report.play(0.7f * masterVolume);
    }

    public void startBeam() {
        if (ready && beamId == -1) beamId = beamLoop.loop(0.35f * masterVolume);
    }

    public void stopBeam() {
        if (ready && beamId != -1) {
            beamLoop.stop(beamId);
            beamId = -1;
        }
    }

    public void dispose() {
        if (!ready) return;
        stopAlarm();
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
        report.dispose();
        for (Sound s : explosions) s.dispose();
        klaxon.dispose();
    }

    // --- synthesis ---

    /** 1s loop of low-passed noise: an engine rumble with no tonal seam. */
    private static float[] synthThruster() {
        // seamless loop (#133): synthesise past the end, then equal-power blend the
        // natural continuation over the head — the seam carries the same character
        // on both sides instead of the old short linear fade's pitch dip
        int n = RATE * 2;
        int f = RATE / 2;
        float[] raw = new float[n + f];
        float y = 0f;
        for (int i = 0; i < raw.length; i++) {
            float white = MathUtils.random(-1f, 1f);
            y += 0.08f * (white - y); // low-pass: keeps only the low rumble
            raw[i] = y;
        }
        float[] s = new float[n];
        System.arraycopy(raw, 0, s, 0, n);
        for (int i = 0; i < f; i++) {
            float t = i / (float) f;
            float head = (float) Math.sin(t * Math.PI / 2);
            float cont = (float) Math.cos(t * Math.PI / 2);
            s[i] = s[i] * head + raw[n + i] * cont;
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

    /** Continuous beam hum: whole tone cycles so the sines loop exactly; noise blended seamlessly. */
    private static float[] synthBeamLoop() {
        int n = RATE / 2;
        int f = RATE / 8;
        // frequencies snapped to whole cycles per buffer: the tonal core loops perfectly
        double f1 = Math.round(180.0 * n / RATE) * (double) RATE / n;
        double f2 = Math.round(273.0 * n / RATE) * (double) RATE / n;
        float[] noise = new float[n + f];
        float y = 0f;
        for (int i = 0; i < noise.length; i++) {
            float white = MathUtils.random(-1f, 1f);
            y += 0.5f * (white - y);
            noise[i] = y;
        }
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float ns = noise[i];
            if (i < f) { // equal-power blend of the noise continuation over the head
                float tt = i / (float) f;
                ns = noise[i] * (float) Math.sin(tt * Math.PI / 2)
                    + noise[n + i] * (float) Math.cos(tt * Math.PI / 2);
            }
            s[i] = (float) (Math.sin(2 * Math.PI * f1 * t) * 0.5
                + Math.sin(2 * Math.PI * f2 * t) * 0.3) + ns * 0.25f;
        }
        return normalise(s, 0.6f);
    }

    /** Two-tone emergency klaxon: alternating wail, loops cleanly on whole cycles. */
    private static float[] synthKlaxon() {
        int n = RATE; // one second: two half-second tones
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            double freq = (i < n / 2) ? 620 : 460;
            float env = 1f;
            int seg = i % (n / 2);
            if (seg < 400) env = seg / 400f;                 // soft attack per tone
            else if (seg > n / 2 - 400) env = (n / 2 - seg) / 400f;
            s[i] = (float) (Math.sin(2 * Math.PI * freq * t)
                + 0.35 * Math.sin(2 * Math.PI * freq * 2 * t)) * env;
        }
        return normalise(s, 0.5f);
    }

    /** Parameterised explosion: crackle attack, rumbling body, tonal thump. */
    private static float[] synthExplosion(float dur, float thumpHz, float decay) {
        int n = (int) (RATE * dur);
        float[] s = new float[n];
        float y = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float white = MathUtils.random(-1f, 1f);
            y += 0.12f * (white - y);
            float rumble = (float) (y * Math.exp(-t * decay));
            float thump = (float) (Math.sin(2 * Math.PI * thumpHz * t)
                * 0.6 * Math.exp(-t * decay * 1.4));
            float crackle = t < 0.05f ? white * (1f - t / 0.05f) * 0.7f : 0f;
            s[i] = rumble + thump + crackle;
        }
        return normalise(s, 0.95f);
    }

    /** Artillery muzzle blast: sharp crack into a rolling low boom. */
    private static float[] synthReport() {
        int n = (int) (RATE * 0.7f);
        float[] s = new float[n];
        float y = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float crack = t < 0.03f ? MathUtils.random(-1f, 1f) * (1f - t / 0.03f) : 0f;
            float white = MathUtils.random(-1f, 1f);
            y += 0.06f * (white - y);
            float boom = (float) (y * Math.exp(-t * 5.0)
                + Math.sin(2 * Math.PI * 55 * t) * 0.5 * Math.exp(-t * 6.0));
            s[i] = crack * 0.9f + boom;
        }
        return normalise(s, 0.95f);
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
