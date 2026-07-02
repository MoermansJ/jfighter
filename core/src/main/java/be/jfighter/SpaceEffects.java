package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.viewport.FitViewport;

/**
 * Everything that makes flight feel like space, shared by GameScreen and LootScreen:
 * parallax starfield, drifting debris, RCS puffs, nav lights, speed-based camera
 * zoom, edge-wrap ghosting, velocity indicator, and fuel-burning flight controls.
 * All tuning constants live at the top of this class.
 */
public class SpaceEffects {
    // throttle HUD (stack of blocks, bottom right)
    public static final float THROTTLE_BLOCK_W = 26f;
    public static final float THROTTLE_BLOCK_H = 9f;
    public static final float THROTTLE_BLOCK_GAP = 4f;
    public static final float THROTTLE_HUD_MARGIN = 20f;
    // camera
    public static final float ZOOM_AMOUNT = 0.15f; // extra zoom-out at top speed
    public static final float ZOOM_LERP = 3f;      // zoom smoothing speed
    // velocity indicator
    public static final float VEL_LOOKAHEAD = 0.4f;   // seconds of drift previewed
    public static final float VEL_MIN_SPEED = 25f;    // hidden below this speed
    // starfield: three parallax layers, far to near
    private static final int[] STAR_COUNTS = {70, 45, 25};
    private static final float[] STAR_PARALLAX = {0.12f, 0.3f, 0.55f};
    private static final float[] STAR_SIZE = {0.9f, 1.4f, 2f};
    private static final float[] STAR_BRIGHT = {0.35f, 0.55f, 0.85f};
    private static final float MARGIN = 80f; // stars/debris extend past the world for zoom-out
    // twinkles: a star flares up for a second or two
    private static final float TWINKLE_CHANCE_PER_SEC = 1.2f; // average new twinkles per second
    private static final int MAX_TWINKLES = 4;
    private static final float TWINKLE_MIN_DUR = 1f;
    private static final float TWINKLE_MAX_DUR = 2f;
    private static final float TWINKLE_BOOST = 1.6f; // extra brightness/size at peak
    // planets: distant, barely moving
    private static final float PLANET_PARALLAX = 0.06f;
    // debris
    private static final int DEBRIS_COUNT = 8;
    // nav lights
    private static final float LIGHT_PULSE_HZ = 0.8f;
    // RCS puffs
    private static final float PUFF_LIFE = 0.35f;
    private static final float PUFF_JET_SPEED = 55f;
    // autopilot: click-placed waypoint; steers the heading, the player flies the throttle
    public static final float AUTOPILOT_TURN_GAIN = 4f;      // deg/s of turn per degree of heading error
    public static final float AUTOPILOT_ARRIVE_RADIUS = 30f; // waypoint clears when the ship gets this close

    private final float worldW;
    private final float worldH;
    private final float spanW;
    private final float spanH;
    private final int[] starCount = new int[STAR_COUNTS.length];

    private final float[][] starX = new float[STAR_COUNTS.length][];
    private final float[][] starY = new float[STAR_COUNTS.length][];
    private final float[] layerOffX = new float[STAR_COUNTS.length];
    private final float[] layerOffY = new float[STAR_COUNTS.length];
    private final Array<Fragment> debris = new Array<>();
    private final Array<Puff> puffs = new Array<>();
    private final Array<Twinkle> twinkles = new Array<>();
    private final Array<Planet> planets = new Array<>();
    private float planetOffX;
    private float planetOffY;
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 identity = new Matrix4();
    private float time;
    private float zoom = 1f;
    private boolean autopilotActive;
    private float autopilotX;
    private float autopilotY;

    private static class Fragment {
        float x, y, vx, vy, rotation, spin;
        float[] pts; // local polyline: x0,y0,x1,y1,...
    }

    private static class Puff {
        float x, y, vx, vy, life;
    }

    private static class Twinkle {
        int layer, idx;
        float t, duration;
    }

    private static class Planet {
        float x, y, radius;
        float r, g, b;
        boolean ring;
    }

    /** worldW/worldH: the instance's arena size (may be larger than the screen resolution). */
    public SpaceEffects(float worldW, float worldH) {
        this.worldW = worldW;
        this.worldH = worldH;
        this.spanW = worldW + 2 * MARGIN;
        this.spanH = worldH + 2 * MARGIN;
        // keep star/debris density constant when the arena grows
        float areaScale = (worldW * worldH) / (JFighter.WORLD_WIDTH * JFighter.WORLD_HEIGHT);
        for (int l = 0; l < STAR_COUNTS.length; l++) {
            starCount[l] = Math.max(1, Math.round(STAR_COUNTS[l] * areaScale));
            starX[l] = new float[starCount[l]];
            starY[l] = new float[starCount[l]];
            for (int i = 0; i < starCount[l]; i++) {
                starX[l][i] = MathUtils.random(spanW);
                starY[l][i] = MathUtils.random(spanH);
            }
        }
        // one big ringed gas giant, one small rocky world, well apart
        Planet giant = new Planet();
        giant.radius = MathUtils.random(45f, 60f);
        giant.r = 0.18f; giant.g = 0.11f; giant.b = 0.08f; // rust
        giant.ring = true;
        giant.x = MathUtils.random(spanW);
        giant.y = MathUtils.random(spanH);
        planets.add(giant);

        Planet rocky = new Planet();
        rocky.radius = MathUtils.random(20f, 30f);
        rocky.r = 0.08f; rocky.g = 0.11f; rocky.b = 0.18f; // slate blue
        do {
            rocky.x = MathUtils.random(spanW);
            rocky.y = MathUtils.random(spanH);
        } while (com.badlogic.gdx.math.Vector2.dst(giant.x, giant.y, rocky.x, rocky.y) < 300f);
        planets.add(rocky);

        int debrisCount = Math.max(2, Math.round(DEBRIS_COUNT * areaScale));
        for (int i = 0; i < debrisCount; i++) {
            Fragment f = new Fragment();
            f.x = MathUtils.random(spanW) - MARGIN;
            f.y = MathUtils.random(spanH) - MARGIN;
            float angle = MathUtils.random(360f);
            float speed = MathUtils.random(4f, 14f);
            f.vx = MathUtils.cosDeg(angle) * speed;
            f.vy = MathUtils.sinDeg(angle) * speed;
            f.rotation = MathUtils.random(360f);
            f.spin = MathUtils.random(-25f, 25f);
            int points = MathUtils.random(3, 4);
            f.pts = new float[points * 2];
            for (int p = 0; p < points; p++) {
                float size = MathUtils.random(4f, 14f);
                f.pts[p * 2] = MathUtils.random(-size, size);
                f.pts[p * 2 + 1] = MathUtils.random(-size, size);
            }
            debris.add(f);
        }
    }

    public float time() {
        return time;
    }

    /**
     * Common flight controls: UP/DOWN step the throttle in 10% increments, rotation is
     * torque with RCS puffs. Fuel is an overworld travel resource; instances fly free.
     */
    public void handleFlightInput(Player player, float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.W) || Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            player.throttleUp();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.S) || Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            player.throttleDown();
        }
        player.updateThrust(delta, true);
        boolean manualLeft = Gdx.input.isKeyPressed(Input.Keys.LEFT);
        boolean manualRight = Gdx.input.isKeyPressed(Input.Keys.RIGHT);
        if (manualLeft || manualRight) clearAutopilot(); // taking the stick disengages
        if (manualLeft) {
            player.rotateLeft(delta);
            spawnRcsPuff(player, 1);
        }
        if (manualRight) {
            player.rotateRight(delta);
            spawnRcsPuff(player, -1);
        }
        if (autopilotActive) steerAutopilot(player, delta);
    }

    public void setAutopilotTarget(float x, float y) {
        autopilotActive = true;
        autopilotX = x;
        autopilotY = y;
    }

    public void clearAutopilot() {
        autopilotActive = false;
    }

    public boolean autopilotActive() {
        return autopilotActive;
    }

    /** Turns the ship toward the waypoint with RCS torque; the throttle stays under manual control.
     *  Always paths inside the borders — the autopilot never steers for a wrap-around shortcut. */
    private void steerAutopilot(Player player, float delta) {
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        float dx = autopilotX - cx;
        float dy = autopilotY - cy;
        if (dx * dx + dy * dy < AUTOPILOT_ARRIVE_RADIUS * AUTOPILOT_ARRIVE_RADIUS) {
            clearAutopilot();
            return;
        }
        // ship forward is (-sin r, cos r), so the heading that points at (dx,dy) is atan2(-dx, dy)
        float desired = MathUtils.atan2(-dx, dy) * MathUtils.radiansToDegrees;
        float error = ((desired - player.rotation) % 360f + 540f) % 360f - 180f;
        float targetVel = MathUtils.clamp(error * AUTOPILOT_TURN_GAIN, -200f, 200f);
        if (player.angularVel < targetVel - 2f) {
            player.rotateLeft(delta);
            spawnRcsPuff(player, 1);
        } else if (player.angularVel > targetVel + 2f) {
            player.rotateRight(delta);
            spawnRcsPuff(player, -1);
        }
    }

    /** Waypoint marker: pulsing cyan ring + crosshair. World coords; call inside the world shape pass. */
    public void renderAutopilot(ShapeRenderer shapes) {
        if (!autopilotActive) return;
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        float pulse = 0.6f + 0.4f * MathUtils.sin(time * 4f);
        shapes.setColor(0.3f * pulse, 0.85f * pulse, 0.95f * pulse, 1f);
        float r = 10f + 3f * MathUtils.sin(time * 4f);
        shapes.circle(autopilotX, autopilotY, r, 24);
        shapes.line(autopilotX - r - 5, autopilotY, autopilotX - r + 3, autopilotY);
        shapes.line(autopilotX + r - 3, autopilotY, autopilotX + r + 5, autopilotY);
        shapes.line(autopilotX, autopilotY - r - 5, autopilotX, autopilotY - r + 3);
        shapes.line(autopilotX, autopilotY + r - 3, autopilotX, autopilotY + r + 5);
        shapes.end();
    }

    /** Throttle stack, bottom right: filled blocks up to the current setting. Uses HUD projection (screen coords). */
    public void renderThrottleHud(ShapeRenderer shapes, Player player) {
        float x = JFighter.WORLD_WIDTH - THROTTLE_HUD_MARGIN - THROTTLE_BLOCK_W;
        float step = THROTTLE_BLOCK_H + THROTTLE_BLOCK_GAP;
        shapes.setTransformMatrix(identity);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < player.throttle; i++) {
            // green at idle shading toward orange at full burn
            float t = i / (float) (Player.THROTTLE_STEPS - 1);
            shapes.setColor(0.2f + 0.8f * t, 0.9f - 0.5f * t, 0.15f, 1f);
            shapes.rect(x, THROTTLE_HUD_MARGIN + i * step, THROTTLE_BLOCK_W, THROTTLE_BLOCK_H);
        }
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.45f, 0.45f, 0.45f, 1f);
        for (int i = 0; i < Player.THROTTLE_STEPS; i++) {
            shapes.rect(x, THROTTLE_HUD_MARGIN + i * step, THROTTLE_BLOCK_W, THROTTLE_BLOCK_H);
        }
        shapes.end();
    }

    public void update(Player player, float delta) {
        time += delta;
        for (int l = 0; l < STAR_COUNTS.length; l++) {
            layerOffX[l] -= player.vx * STAR_PARALLAX[l] * delta;
            layerOffY[l] -= player.vy * STAR_PARALLAX[l] * delta;
        }
        planetOffX -= player.vx * PLANET_PARALLAX * delta;
        planetOffY -= player.vy * PLANET_PARALLAX * delta;

        // occasionally a star flares for a moment
        if (twinkles.size < MAX_TWINKLES && MathUtils.random() < TWINKLE_CHANCE_PER_SEC * delta) {
            Twinkle tw = new Twinkle();
            tw.layer = MathUtils.random(starCount.length - 1);
            tw.idx = MathUtils.random(starCount[tw.layer] - 1);
            tw.duration = MathUtils.random(TWINKLE_MIN_DUR, TWINKLE_MAX_DUR);
            twinkles.add(tw);
        }
        for (int i = twinkles.size - 1; i >= 0; i--) {
            Twinkle tw = twinkles.get(i);
            tw.t += delta;
            if (tw.t >= tw.duration) twinkles.removeIndex(i);
        }
        for (Fragment f : debris) {
            f.x = wrap(f.x + f.vx * delta, spanW);
            f.y = wrap(f.y + f.vy * delta, spanH);
            f.rotation += f.spin * delta;
        }
        for (int i = puffs.size - 1; i >= 0; i--) {
            Puff p = puffs.get(i);
            p.life -= delta;
            if (p.life <= 0) {
                puffs.removeIndex(i);
                continue;
            }
            p.x += p.vx * delta;
            p.y += p.vy * delta;
        }
    }

    /** Zooms out slightly with speed. Call after viewport.apply(), before setting projection matrices. */
    public void applyZoom(FitViewport viewport, Player player, float delta) {
        float speed = (float) Math.sqrt(player.vx * player.vx + player.vy * player.vy);
        float target = 1f + speed / Player.MAX_SPEED * ZOOM_AMOUNT;
        zoom = MathUtils.lerp(zoom, target, Math.min(1f, ZOOM_LERP * delta));
        OrthographicCamera cam = (OrthographicCamera) viewport.getCamera();
        cam.zoom = zoom;
        cam.update();
    }

    /** Starfield, planets, and drifting debris; draw first, under everything. */
    public void renderBackground(ShapeRenderer shapes) {
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int l = 0; l < STAR_COUNTS.length; l++) {
            float b = STAR_BRIGHT[l];
            shapes.setColor(b, b, Math.min(1f, b + 0.08f), 1f);
            for (int i = 0; i < starCount[l]; i++) {
                float px = wrap(starX[l][i] + layerOffX[l], spanW) - MARGIN;
                float py = wrap(starY[l][i] + layerOffY[l], spanH) - MARGIN;
                shapes.circle(px, py, STAR_SIZE[l], 6);
            }
        }
        // twinkling stars redrawn bigger and brighter
        for (Twinkle tw : twinkles) {
            float phase = MathUtils.sin(MathUtils.PI * tw.t / tw.duration); // 0 -> 1 -> 0
            float b = Math.min(1f, STAR_BRIGHT[tw.layer] * (1f + TWINKLE_BOOST * phase));
            shapes.setColor(b, b, Math.min(1f, b + 0.05f), 1f);
            float px = wrap(starX[tw.layer][tw.idx] + layerOffX[tw.layer], spanW) - MARGIN;
            float py = wrap(starY[tw.layer][tw.idx] + layerOffY[tw.layer], spanH) - MARGIN;
            shapes.circle(px, py, STAR_SIZE[tw.layer] * (1f + 0.9f * phase), 8);
        }
        // planet discs
        for (Planet pl : planets) {
            float px = wrap(pl.x + planetOffX, spanW) - MARGIN;
            float py = wrap(pl.y + planetOffY, spanH) - MARGIN;
            shapes.setColor(pl.r, pl.g, pl.b, 1f);
            shapes.circle(px, py, pl.radius, 48);
        }
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        // sparkle rays on strong twinkles
        for (Twinkle tw : twinkles) {
            float phase = MathUtils.sin(MathUtils.PI * tw.t / tw.duration);
            if (phase < 0.35f) continue;
            float px = wrap(starX[tw.layer][tw.idx] + layerOffX[tw.layer], spanW) - MARGIN;
            float py = wrap(starY[tw.layer][tw.idx] + layerOffY[tw.layer], spanH) - MARGIN;
            float len = 7f * phase;
            float b = Math.min(1f, STAR_BRIGHT[tw.layer] * (1f + TWINKLE_BOOST * phase));
            shapes.setColor(b, b, b, 1f);
            shapes.line(px - len, py, px + len, py);
            shapes.line(px, py - len, px, py + len);
        }
        // planet rims, cloud bands, rings
        for (Planet pl : planets) {
            float px = wrap(pl.x + planetOffX, spanW) - MARGIN;
            float py = wrap(pl.y + planetOffY, spanH) - MARGIN;
            float r = pl.radius;
            shapes.setColor(Math.min(1f, pl.r * 2.4f), Math.min(1f, pl.g * 2.4f), Math.min(1f, pl.b * 2.4f), 1f);
            shapes.circle(px, py, r, 48);
            for (float frac : new float[] {-0.45f, 0.05f, 0.5f}) {
                float dy = r * frac;
                float chord = (float) Math.sqrt(r * r - dy * dy) * 0.94f;
                shapes.line(px - chord, py + dy, px + chord, py + dy);
            }
            if (pl.ring) {
                shapes.ellipse(px - r * 1.7f, py - r * 0.35f, r * 3.4f, r * 0.7f, 40);
            }
        }
        shapes.setColor(0.32f, 0.34f, 0.32f, 1f);
        for (Fragment f : debris) {
            transform.setToTranslation(f.x - MARGIN, f.y - MARGIN, 0).rotate(0, 0, 1, f.rotation);
            shapes.setTransformMatrix(transform);
            for (int p = 0; p + 3 < f.pts.length; p += 2) {
                shapes.line(f.pts[p], f.pts[p + 1], f.pts[p + 2], f.pts[p + 3]);
            }
        }
        shapes.end();
        shapes.setTransformMatrix(identity);
    }

    /** Ship + exhaust with wrap ghosts, velocity indicator, RCS puffs, and nav lights. */
    public void renderShip(ShapeRenderer shapes, Player player) {
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;

        // ghost offsets: draw again across the border while partially wrapped
        float[] dxs = {0, 0};
        float[] dys = {0, 0};
        int nx = 1, ny = 1;
        if (player.x > worldW - Player.WIDTH) dxs[nx++] = -worldW;
        else if (player.x < 0) dxs[nx++] = worldW;
        if (player.y > worldH - Player.HEIGHT) dys[ny++] = -worldH;
        else if (player.y < 0) dys[ny++] = worldH;

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(com.badlogic.gdx.graphics.Color.GREEN);
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                transform.setToTranslation(cx + dxs[i], cy + dys[j], 0).rotate(0, 0, 1, player.rotation);
                shapes.setTransformMatrix(transform);
                ShipRenderer.drawB2(shapes);
                if (player.thrustLevel > 0.02f) ShipRenderer.drawExhaust(shapes, player.thrustLevel);
            }
        }
        // velocity indicator: where you're drifting
        shapes.setTransformMatrix(identity);
        float speed = (float) Math.sqrt(player.vx * player.vx + player.vy * player.vy);
        if (speed > VEL_MIN_SPEED) {
            shapes.setColor(0.2f, 0.55f, 0.6f, 1f);
            float tx = cx + player.vx * VEL_LOOKAHEAD;
            float ty = cy + player.vy * VEL_LOOKAHEAD;
            shapes.line(cx, cy, tx, ty);
            shapes.circle(tx, ty, 3f, 8);
        }
        shapes.end();

        // filled pass: RCS puffs + blinking nav lights (red left / green right)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Puff p : puffs) {
            float frac = p.life / PUFF_LIFE;
            shapes.setColor(0.7f * frac, 0.85f * frac, frac, 1f);
            shapes.circle(p.x, p.y, 1.6f * frac + 0.6f, 6);
        }
        float pulse = 0.3f + 0.7f * (0.5f + 0.5f * MathUtils.sin(time * LIGHT_PULSE_HZ * MathUtils.PI2));
        float cos = MathUtils.cosDeg(player.rotation);
        float sin = MathUtils.sinDeg(player.rotation);
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                float gx = cx + dxs[i];
                float gy = cy + dys[j];
                shapes.setColor(pulse, 0.1f * pulse, 0.1f * pulse, 1f);
                shapes.circle(gx - 28 * cos, gy - 28 * sin, 1.8f, 6);
                shapes.setColor(0.1f * pulse, pulse, 0.15f * pulse, 1f);
                shapes.circle(gx + 28 * cos, gy + 28 * sin, 1.8f, 6);
            }
        }
        shapes.end();
        shapes.setTransformMatrix(identity);
    }

    private void spawnRcsPuff(Player player, int turnDir) {
        // turning left fires the jet on the right wingtip, and vice versa
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        float cos = MathUtils.cosDeg(player.rotation);
        float sin = MathUtils.sinDeg(player.rotation);
        float tipX = cx - turnDir * 28 * cos;
        float tipY = cy - turnDir * 28 * sin;
        float fx = -MathUtils.sinDeg(player.rotation);
        float fy = MathUtils.cosDeg(player.rotation);
        addPuff(tipX, tipY,
            player.vx - fx * PUFF_JET_SPEED + MathUtils.random(-8f, 8f),
            player.vy - fy * PUFF_JET_SPEED + MathUtils.random(-8f, 8f));
    }

    private void addPuff(float x, float y, float vx, float vy) {
        Puff p = new Puff();
        p.x = x;
        p.y = y;
        p.vx = vx;
        p.vy = vy;
        p.life = PUFF_LIFE;
        puffs.add(p);
    }

    private static float wrap(float v, float span) {
        return ((v % span) + span) % span;
    }
}
