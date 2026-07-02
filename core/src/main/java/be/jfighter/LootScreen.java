package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

/**
 * Looting instance. The net is a rope: it pays out behind the ship, sticks to cargo
 * it touches, and can be cut loose (SPACE) to float free. Free nets tangle with each
 * other into one blob. Deliver cargo by dragging it into the EXIT zone at the
 * insertion point. A derelict hulk in the centre pulls everything toward it.
 */
public class LootScreen implements Screen {
    // the arena is larger than the screen resolution; the FitViewport scales it down
    private static final float ARENA_WIDTH = 1440f;
    private static final float ARENA_HEIGHT = 810f;
    // HUD is drawn in screen coords, independent of arena size
    private static final float HUD_W = JFighter.WORLD_WIDTH;
    private static final float HUD_H = JFighter.WORLD_HEIGHT;

    // net rope physics
    private static final float MIN_SEGMENT = 6f;      // distance flown before a new net point pays out
    private static final float NET_MAX_LENGTH = 50 * Player.HEIGHT; // 50 ship lengths
    private static final int MAX_TRAIL_POINTS = (int) (NET_MAX_LENGTH / MIN_SEGMENT);
    private static final float NET_REST_LEN = 8f;     // rope segment max length before it pulls
    private static final int NET_ITERATIONS = 2;      // constraint solver passes per frame
    private static final float NET_DRAG = 1.0f;       // damping on free-floating net points
    private static final float NET_MERGE_DIST = 10f;  // nets closer than this connect
    private static final float NET_LINK_REST = 3f;    // tied strand ends cinch this close to the ring
    private static final float NET_STICK_DIST = 3f;   // extra reach beyond cargo radius for sticking
    private static final float CARGO_TOW_FACTOR = 0.3f;  // how much rope correction moves a crate
    private static final float CARGO_TOW_IMPULSE = 2f;   // velocity a crate picks up from rope pull

    private static final int LOOT_COUNT_MIN = 4, LOOT_COUNT_MAX = 8;
    private int lastDeliveredValue = 25; // shown by the catch flash
    private static final float CATCH_FLASH_TIME = 1.5f;

    private static final float SHIP_MASS = 1f;
    private static final float CARGO_MASS = 4f;    // heavy: bumps barely move it, and shove you back
    private static final float CARGO_DRAG = 1.2f;  // exponential damping per second
    private static final float CARGO_MIN_DRIFT = 4f; // derelicts never sit fully still
    private static final float RESTITUTION = 0.4f;
    private static final float SHIP_RADIUS = 23f; // matches the 1.3x pincer hull
    private static final float CARGO_RADIUS = 11f;

    // pincer claw: cargo near the jaws is captured automatically; F ejects the stow forward in a net
    private static final int PINCER_CAPACITY = 3;
    private static final float PINCER_RANGE = 36f; // capture reach around the claw centre
    private static final float PINCER_CLAW_FORWARD = 39f; // claw centre this far ahead of the ship centre
    // cargo hold: a circular bay between the jaws where downscaled crates rattle around
    private static final float HOLD_CENTER_FWD = 44f;  // hold centre ahead of the ship centre
    private static final float HOLD_RADIUS = 26f;      // big enough for three downscaled crates
    private static final float HELD_SCALE = 0.7f;      // caught cargo shrinks to fit the hold
    private static final float HOLD_RESTITUTION = 0.75f;
    private static final float HOLD_DAMPING = 0.5f;    // per-second velocity decay inside the hold
    private static final float HOLD_SLOSH = 0.6f;      // how much ship acceleration stirs the cargo
    private static final float EJECT_SPEED = 140f;     // forward speed of the ejected bundle
    private static final float EJECT_COOLDOWN = 1.2f;  // capture pause after ejecting so the bundle gets away
    private static final float EJECT_GLIDE_TIME = 4f;  // seconds of reduced drag so the bundle keeps its momentum
    private static final float EJECT_GLIDE_DRAG = 0.15f; // drag multiplier while gliding
    private static final float GRAB_PULSE_DECAY = 2.5f; // per-second fade of the jaw-snap animation

    // tractor hook: fires backward from the tail, latches onto free nets, becomes a tow line
    private static final float HOOK_SPEED = 420f;
    private static final float HOOK_RETRACT_SPEED = 560f;
    private static final float HOOK_MAX_LEN = 160f;
    private static final float HOOK_MIN_TOW = 34f;
    private static final float HOOK_CATCH_DIST = 9f; // how close the tip must pass to a net point

    // loop closing: a free net whose strand crosses itself seals into a clean ring
    private static final int LOOP_MIN_SEGMENTS = 16;  // rope distance between crossing points to count as a loop
    private static final float LOOP_ROUNDING = 1.5f;  // per-second pull of a closed ring toward a true circle
    private static final float RING_SHRINK_RATE = 0.15f;   // drawstring cinch: fraction of radius lost per second
    private static final float RING_MIN_FRACTION = 0.45f;  // shrink floor as a fraction of the radius at sealing

    // disintegration sparks (empty catches burn away)
    private static final float SPARK_MIN_LIFE = 0.4f;
    private static final float SPARK_MAX_LIFE = 1.0f;

    // derelict hulk: gravity well in the middle of the arena
    // gravity object + layout: randomised per instance in show() for map variety
    private float hulkX;
    private float hulkY;
    private float hulkRadius;
    private float hulkGravity;    // accel = gravity / distance², px/s²
    private float hulkGravityCap; // max pull, close in
    private float exitY;
    private int lootCount;
    private final Array<float[]> asteroids = new Array<>();      // {x, y, radius}
    private final Array<float[]> asteroidShapes = new Array<>(); // polygon per asteroid
    private static final float HULK_RESTITUTION = 0.35f;
    private static final float HULK_SPIN = 6f;           // deg/s slow tumble
    private static final float THUD_COOLDOWN = 0.15f;
    // gravity indicator: rings that fall inward toward the well
    private static final int GRAVITY_RING_COUNT = 3;
    private static final float GRAVITY_RING_SPEED = 0.18f; // ring cycles per second

    // exit zone at the player's insertion point
    private static final float EXIT_X = 90f;
    private static final float EXIT_RADIUS = 70f;

    /** Where the pull becomes noticeable, scaled to the object's size. */
    private float gravityRingOuter() {
        return hulkRadius * 3.2f + 40f;
    }

    /** Dark core at the centre of the well, scaled to the object's size. */
    private float eventHorizonRadius() {
        return hulkRadius * 0.45f;
    }

    private final JFighter game;
    private final GameState state;

    private FitViewport viewport;
    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;
    private Player player;
    private SpaceEffects effects;
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 identity = new Matrix4();
    private final Matrix4 hudMatrix = new Matrix4();

    private Net deployed; // null when stowed
    private final Array<Net> freeNets = new Array<>();
    private final Array<NetLink> links = new Array<>();
    private final Array<Net> netScratch = new Array<>();
    private final Array<Spark> sparks = new Array<>();
    private final int[] contact = new int[2];
    private final Array<Loot> pincerHeld = new Array<>();
    private float grabPulse;     // jaw-snap animation, spikes on capture
    private float ejectCooldown; // capture disabled briefly after an eject
    private float prevShipVx, prevShipVy; // for hold slosh from ship acceleration
    private final PauseMenu pause = new PauseMenu();
    private final ControlsHelp controlsHelp = new ControlsHelp(new String[][]{
        {"SPACE", "cast / cut net"},
        {"E", "tractor hook"},
        {"F", "eject stowage"},
        {"LMB", "set autopilot"},
        {"RMB", "cancel autopilot"},
        {"ESC", "pause menu"},
    });
    private final Array<Loot> lootItems = new Array<>();
    private float catchFlash;
    private float hulkRotation;

    private enum HookState { STOWED, EXTENDING, RETRACTING, LATCHED }

    private HookState hookState = HookState.STOWED;
    private float hookLen;
    private NetPoint hookedPoint;
    private Net hookedNet;
    private float towLen;
    private float[] hulkShape;
    private float lastThudTime = -1f;

    private static class NetPoint {
        float x, y, vx, vy;
        Loot attached;            // crate this point sticks to, or null
        float attachDx, attachDy; // offset from crate centre while attached
        boolean linked;           // already tangled with another net

        NetPoint(float x, float y, float vx, float vy) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
        }
    }

    private static class Net {
        final Array<NetPoint> pts = new Array<>();
        boolean closed;      // strand crossed itself and sealed into a ring
        float ringRadius;    // current drawstring target radius while closed
        float minRadius;     // shrink floor, proportional to the size at sealing
        float glideT;        // remaining low-drag time after an eject
        final Array<Loot> contents = new Array<>(); // crates caught when the ring sealed
    }

    private static class Spark {
        float x, y, vx, vy, life, maxLife;
    }

    private static class NetLink {
        final NetPoint a, b;

        NetLink(NetPoint a, NetPoint b) {
            this.a = a;
            this.b = b;
        }
    }

    private static class Loot {
        float x, y, vx, vy, rotation, spin;
        float glideT; // remaining low-drag time after an eject
        float holdX, holdY, holdVx, holdVy; // hold-local state while trapped in the pincer
        final float lightPhase = MathUtils.random(MathUtils.PI2);
        final int tier;    // 0 small, 1 medium, 2 large — bigger pays more
        final int shape;   // 0 crate, 1 elongated container, 2 hex barrel
        final float radius;
        final int value;

        Loot(float x, float y, float vx, float vy, float spin) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.spin = spin;
            float roll = MathUtils.random();
            tier = roll < 0.4f ? 0 : roll < 0.8f ? 1 : 2;
            radius = tier == 0 ? 8f : tier == 1 ? 11f : 15f;
            value = tier == 0 ? 15 : tier == 1 ? 25 : 45;
            shape = MathUtils.random(2);
        }
    }

    public LootScreen(JFighter game, GameState state) {
        this.game = game;
        this.state = state;
    }

    @Override
    public void show() {
        randomizeLayout();
        viewport = new FitViewport(ARENA_WIDTH, ARENA_HEIGHT);
        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        font = game.fonts.font;
        Fonts.scale(font, 1.4f);
        player = new Player(EXIT_X - Player.WIDTH / 2f, exitY - Player.HEIGHT / 2f);
        player.thrustMult = state.thrustMult() * (1f + 0.04f * state.roomStats[0]); // engine crew
        effects = new SpaceEffects(ARENA_WIDTH, ARENA_HEIGHT);
        effects.setPincerHull(true);
        hudMatrix.setToOrtho2D(0, 0, HUD_W, HUD_H);
        buildHulkShape();
        spawnLoot();
    }

    /** Every visit rolls a different arena: gravity object, exit, cargo count, asteroid field. */
    private void randomizeLayout() {
        exitY = ARENA_HEIGHT * MathUtils.random(0.25f, 0.75f);
        lootCount = MathUtils.random(LOOT_COUNT_MIN, LOOT_COUNT_MAX);
        hulkRadius = MathUtils.random(26f, 50f);
        hulkGravity = MathUtils.random(120000f, 320000f);
        hulkGravityCap = MathUtils.random(30f, 60f);
        do {
            hulkX = MathUtils.random(420f, ARENA_WIDTH - 160f);
            hulkY = MathUtils.random(150f, ARENA_HEIGHT - 150f);
        } while (Vector2.dst(hulkX, hulkY, EXIT_X, exitY) < EXIT_RADIUS + hulkRadius + 240f);

        asteroids.clear();
        asteroidShapes.clear();
        int rocks = MathUtils.random(0, 4);
        for (int i = 0; i < rocks; i++) {
            for (int attempt = 0; attempt < 30; attempt++) {
                float r = MathUtils.random(14f, 30f);
                float x = MathUtils.random(300f, ARENA_WIDTH - 60f);
                float y = MathUtils.random(60f, ARENA_HEIGHT - 60f);
                if (Vector2.dst(x, y, EXIT_X, exitY) < EXIT_RADIUS + r + 90f) continue;
                if (Vector2.dst(x, y, hulkX, hulkY) < hulkRadius + r + 120f) continue;
                boolean clear = true;
                for (float[] a : asteroids) {
                    if (Vector2.dst(x, y, a[0], a[1]) < a[2] + r + 70f) {
                        clear = false;
                        break;
                    }
                }
                if (!clear) continue;
                asteroids.add(new float[]{x, y, r});
                int verts = 9;
                float[] shape = new float[verts * 2];
                for (int v = 0; v < verts; v++) {
                    float angle = v * 360f / verts;
                    float rr = r + MathUtils.random(-r * 0.3f, r * 0.25f);
                    shape[v * 2] = MathUtils.cosDeg(angle) * rr;
                    shape[v * 2 + 1] = MathUtils.sinDeg(angle) * rr;
                }
                asteroidShapes.add(shape);
                break;
            }
        }
    }

    /** Cargo pulled past the event horizon is lost: it burns up in a spark flash. */
    private void consumeCargoAtHorizon() {
        float eh = eventHorizonRadius();
        for (int i = lootItems.size - 1; i >= 0; i--) {
            Loot crate = lootItems.get(i);
            if (isHeld(crate)) continue;
            if (Vector2.dst(crate.x, crate.y, hulkX, hulkY) >= eh + crate.radius * 0.5f) continue;
            for (Net net : allNets()) {
                for (NetPoint p : net.pts) {
                    if (p.attached == crate) p.attached = null;
                }
            }
            removeFromCatches(crate);
            lootItems.removeIndex(i);
            burstSparks(crate.x, crate.y, 16);
            game.sfx.playThud(0.35f);
        }
    }

    /** Net spool upgrades extend how much line can be paid out. */
    private int maxTrailPoints() {
        return Math.round(MAX_TRAIL_POINTS * state.netLengthMult());
    }

    /** Hold extensions raise how many crates the pincer can stow. */
    private int pincerCapacity() {
        return PINCER_CAPACITY + state.pincerCapacityBonus();
    }

    private boolean nearAsteroid(float x, float y, float margin) {
        for (float[] a : asteroids) {
            if (Vector2.dst(x, y, a[0], a[1]) < a[2] + margin) return true;
        }
        return false;
    }

    private void buildHulkShape() {
        int verts = 10;
        hulkShape = new float[verts * 2];
        for (int i = 0; i < verts; i++) {
            float angle = i * 360f / verts;
            float r = hulkRadius + MathUtils.random(-10f, 10f);
            hulkShape[i * 2] = MathUtils.cosDeg(angle) * r;
            hulkShape[i * 2 + 1] = MathUtils.sinDeg(angle) * r;
        }
    }

    private void spawnLoot() {
        lootItems.clear();
        for (int i = 0; i < lootCount; i++) {
            float x, y;
            do {
                x = MathUtils.random(320f, ARENA_WIDTH - 60f);
                y = MathUtils.random(60f, ARENA_HEIGHT - 60f);
            } while (Vector2.dst(x, y, hulkX, hulkY) < hulkRadius + 90f
                    || Vector2.dst(x, y, EXIT_X, exitY) < EXIT_RADIUS + 60f
                    || nearAsteroid(x, y, 40f));
            float angle = MathUtils.random(360f);
            float speed = MathUtils.random(CARGO_MIN_DRIFT, 15f); // derelict: barely adrift
            lootItems.add(new Loot(x, y,
                MathUtils.cosDeg(angle) * speed,
                MathUtils.sinDeg(angle) * speed,
                MathUtils.random(-60f, 60f)));
        }
    }

    @Override
    public void render(float delta) {
        pause.handleEscape();
        if (!pause.isOpen()) {
        // stop rendering once the screen switches: hide() disposed our resources
        if (handleInput(delta)) return;
        effects.handleFlightInput(player, delta);
        applyHulkGravity(delta);
        player.updatePosition(delta);
        if (engagedWithCargo()) {
            // pulling, pushing or carrying something: the border is a wall, no wrap
            bounceOffArenaEdges();
        } else if (player.wrapAround(ARENA_WIDTH, ARENA_HEIGHT)) {
            cutDeployedNet(); // the net snaps loose when the ship crosses the border
            releaseHook();    // and the tow line snaps too
        }
        updateHook(delta);
        updateLoot(delta);
        consumeCargoAtHorizon();
        collideCargoWithCargo();
        collideShipWithCargo();
        collideWithHulk();
        interactShipWithBlobs();
        updatePincer(delta);
        effects.update(player, delta);
        effects.spawnExhaust(player, delta);

        payOutDeployedNet();
        updateNetPoints(delta);
        solveNetConstraints();
        pushNetPoints();
        stickNetsToCargo();
        sealCastLoop();
        connectCastNet();
        interactNets();
        closeLoops();
        dissolveEmptyCatches();
        roundClosedLoops(delta);
        updateSparks(delta);
        collectAtExit();
        if (catchFlash > 0) catchFlash -= delta;
        }

        ScreenUtils.clear(0, 0, 0.05f, 1f);
        viewport.apply();
        effects.applyZoom(viewport, player, delta);
        shapes.setProjectionMatrix(viewport.getCamera().combined);

        effects.renderBackground(shapes);
        drawExitZone();
        drawHulk(delta);
        drawNets();
        drawLoot();
        drawHook();
        drawSparks();
        effects.renderAutopilot(shapes);
        effects.renderShip(shapes, player);
        drawWorldLabels();
        drawHud();

        if (pause.isOpen()
                && pause.render(shapes, batch, font, hudMatrix, viewport, lootItems.isEmpty())) {
            game.setScreen(new OverworldScreen(game, state));
        }
    }

    /** Returns true when the screen was switched and rendering must stop. */
    private boolean handleInput(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            if (deployed == null) {
                deployed = new Net(); // start paying out a fresh net
            } else {
                cutDeployedNet();     // cut: the net floats free
            }
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            Vector2 target = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
            effects.setAutopilotTarget(target.x, target.y);
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT)) {
            effects.clearAutopilot();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F)) {
            ejectPincer();
        }
        controlsHelp.handleInput();
        if (Gdx.input.isKeyJustPressed(Input.Keys.E)) {
            if (hookState == HookState.STOWED) {
                hookState = HookState.EXTENDING;
                hookLen = 0f;
            } else {
                releaseHook(); // extending, latched, or retracting: let go / reel in
            }
        }
        return false;
    }

    private boolean isHeld(Loot crate) {
        return pincerHeld.contains(crate, true);
    }

    /** Engaged = paying out a net, towing with the hook, or carrying stowage. */
    private boolean engagedWithCargo() {
        return deployed != null || hookState == HookState.LATCHED || pincerHeld.size > 0;
    }

    private void bounceOffArenaEdges() {
        if (player.x < 0) {
            player.x = 0;
            player.vx = Math.abs(player.vx) * 0.5f;
        } else if (player.x > ARENA_WIDTH - Player.WIDTH) {
            player.x = ARENA_WIDTH - Player.WIDTH;
            player.vx = -Math.abs(player.vx) * 0.5f;
        }
        if (player.y < 0) {
            player.y = 0;
            player.vy = Math.abs(player.vy) * 0.5f;
        } else if (player.y > ARENA_HEIGHT - Player.HEIGHT) {
            player.y = ARENA_HEIGHT - Player.HEIGHT;
            player.vy = -Math.abs(player.vy) * 0.5f;
        }
    }

    /** Free cargo drifting near the open jaws is captured automatically (netted cargo is left alone). */
    private void autoCapture() {
        if (ejectCooldown > 0f || pincerHeld.size >= pincerCapacity()) return;
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        float fx = -MathUtils.sinDeg(player.rotation);
        float fy = MathUtils.cosDeg(player.rotation);
        float clawX = cx + fx * PINCER_CLAW_FORWARD;
        float clawY = cy + fy * PINCER_CLAW_FORWARD;
        for (Loot crate : lootItems) {
            if (pincerHeld.size >= pincerCapacity()) break;
            if (isHeld(crate) || isNetted(crate)) continue;
            float dx = crate.x - clawX;
            float dy = crate.y - clawY;
            if (dx * dx + dy * dy < PINCER_RANGE * PINCER_RANGE) {
                stowInHold(crate, cx, cy, fx, fy);
                grabPulse = 1f;
                game.sfx.playThud(0.3f);
            }
        }
    }

    /** Cargo counts as netted when it sits in a sealed ring's catch or a strand is stuck to it. */
    private boolean isNetted(Loot crate) {
        for (Net net : allNets()) {
            if (net.closed && net.contents.contains(crate, true)) return true;
            for (NetPoint p : net.pts) {
                if (p.attached == crate) return true;
            }
        }
        return false;
    }

    /** Enters the hold keeping its relative motion, so it rattles around in there. */
    private void stowInHold(Loot crate, float cx, float cy, float fx, float fy) {
        float rx = fy, ry = -fx;
        float hx = cx + fx * HOLD_CENTER_FWD;
        float hy = cy + fy * HOLD_CENTER_FWD;
        float dx = crate.x - hx;
        float dy = crate.y - hy;
        crate.holdX = dx * rx + dy * ry;
        crate.holdY = dx * fx + dy * fy;
        float maxR = HOLD_RADIUS - crate.radius * HELD_SCALE;
        float d = (float) Math.sqrt(crate.holdX * crate.holdX + crate.holdY * crate.holdY);
        if (d > maxR && d > 0.001f) {
            crate.holdX *= maxR / d;
            crate.holdY *= maxR / d;
        }
        float dvx = crate.vx - player.vx;
        float dvy = crate.vy - player.vy;
        crate.holdVx = dvx * rx + dvy * ry;
        crate.holdVy = dvx * fx + dvy * fy;
        pincerHeld.add(crate);
    }

    /**
     * Sealed blobs are rigid to the craft: pushing with the arms moves the whole blob
     * efficiently without deforming it, and a craft caught inside clips straight to
     * the outside with no interaction.
     */
    private void interactShipWithBlobs() {
        float shipX = player.x + Player.WIDTH / 2f;
        float shipY = player.y + Player.HEIGHT / 2f;
        for (Net net : freeNets) {
            if (!net.closed || net.pts.size < 3) continue;
            float cx = 0, cy = 0;
            for (NetPoint p : net.pts) {
                cx += p.x;
                cy += p.y;
            }
            cx /= net.pts.size;
            cy /= net.pts.size;
            float r = net.ringRadius;
            float dx = shipX - cx;
            float dy = shipY - cy;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist >= r + SHIP_RADIUS) continue;

            if (dist < r) {
                // caught inside: pop out through the nearest edge, nothing else happens
                float nx = dist > 0.001f ? dx / dist : 1f;
                float ny = dist > 0.001f ? dy / dist : 0f;
                float target = r + SHIP_RADIUS + 2f;
                player.x += nx * (target - dist);
                player.y += ny * (target - dist);
                shipX = player.x + Player.WIDTH / 2f;
                shipY = player.y + Player.HEIGHT / 2f;
                continue;
            }

            // pushing from outside: shove blob and catch rigidly, matching the ship's push speed
            float nx = -dx / dist;
            float ny = -dy / dist;
            float overlap = r + SHIP_RADIUS - dist;
            float vn = player.vx * nx + player.vy * ny;
            for (NetPoint p : net.pts) {
                p.x += nx * overlap;
                p.y += ny * overlap;
                float pvn = p.vx * nx + p.vy * ny;
                if (pvn < vn) {
                    p.vx += (vn - pvn) * nx;
                    p.vy += (vn - pvn) * ny;
                }
            }
            for (Loot crate : net.contents) {
                crate.x += nx * overlap;
                crate.y += ny * overlap;
                float cvn = crate.vx * nx + crate.vy * ny;
                if (cvn < vn) {
                    crate.vx += (vn - cvn) * nx;
                    crate.vy += (vn - cvn) * ny;
                }
            }
        }
    }

    /** The crate leaves every sealed ring's catch; a stow net that just lost its last item collapses. */
    private void removeFromCatches(Loot crate) {
        for (int i = freeNets.size - 1; i >= 0; i--) {
            Net net = freeNets.get(i);
            if (!net.closed) continue;
            if (net.contents.removeValue(crate, true) && net.contents.isEmpty()) {
                disintegrate(net);
            }
        }
    }

    /** F: the stow is ejected forward at moderate speed, bound together in a sealed net. */
    private void ejectPincer() {
        if (pincerHeld.size == 0) return;
        float fx = -MathUtils.sinDeg(player.rotation);
        float fy = MathUtils.cosDeg(player.rotation);
        float vx = player.vx + fx * EJECT_SPEED;
        float vy = player.vy + fy * EJECT_SPEED;
        float cx = 0, cy = 0;
        for (Loot crate : pincerHeld) {
            crate.vx = vx;
            crate.vy = vy;
            crate.glideT = EJECT_GLIDE_TIME;
            cx += crate.x;
            cy += crate.y;
        }
        cx /= pincerHeld.size;
        cy /= pincerHeld.size;
        netEjectedCargo(cx, cy, vx, vy);
        pincerHeld.clear();
        ejectCooldown = EJECT_COOLDOWN;
        grabPulse = 1f;
        game.sfx.playThud(0.35f);
    }

    /** Wraps the ejected clump in a ready-made sealed ring that travels with it. */
    private void netEjectedCargo(float cx, float cy, float vx, float vy) {
        float biggest = CARGO_RADIUS;
        for (Loot crate : pincerHeld) biggest = Math.max(biggest, crate.radius);
        float radius = biggest + 16f + 10f * pincerHeld.size;
        int n = Math.max(LOOP_MIN_SEGMENTS + 2, Math.round(radius * MathUtils.PI2 / NET_REST_LEN * 1.15f));
        Net net = new Net();
        for (int i = 0; i < n; i++) {
            float angle = i * 360f / n;
            net.pts.add(new NetPoint(
                cx + MathUtils.cosDeg(angle) * radius,
                cy + MathUtils.sinDeg(angle) * radius,
                vx, vy));
        }
        net.closed = true;
        net.contents.addAll(pincerHeld);
        net.ringRadius = radius;
        net.minRadius = radius * RING_MIN_FRACTION;
        net.glideT = EJECT_GLIDE_TIME;
        freeNets.add(net);
    }

    /**
     * Held crates rattle around the circular hold between the jaws: they bounce off
     * the hold wall and each other, and ship acceleration sloshes them about.
     */
    private void updatePincer(float delta) {
        if (ejectCooldown > 0f) ejectCooldown -= delta;
        autoCapture();
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        float fx = -MathUtils.sinDeg(player.rotation); // forward
        float fy = MathUtils.cosDeg(player.rotation);
        float rx = fy; // right = forward rotated -90°
        float ry = -fx;
        // ship acceleration this frame stirs the hold (expressed in hold-local coords)
        float ax = player.vx - prevShipVx;
        float ay = player.vy - prevShipVy;
        prevShipVx = player.vx;
        prevShipVy = player.vy;
        float sloshX = -(ax * rx + ay * ry) * HOLD_SLOSH;
        float sloshY = -(ax * fx + ay * fy) * HOLD_SLOSH;

        float damping = (float) Math.exp(-HOLD_DAMPING * delta);
        for (Loot crate : pincerHeld) {
            crate.holdVx = crate.holdVx * damping + sloshX;
            crate.holdVy = crate.holdVy * damping + sloshY;
            crate.holdX += crate.holdVx * delta;
            crate.holdY += crate.holdVy * delta;
        }
        // held-held bumps: separate and exchange normal velocity
        for (int i = 0; i < pincerHeld.size; i++) {
            for (int j = i + 1; j < pincerHeld.size; j++) {
                Loot a = pincerHeld.get(i);
                Loot b = pincerHeld.get(j);
                float dx = b.holdX - a.holdX;
                float dy = b.holdY - a.holdY;
                float d2 = dx * dx + dy * dy;
                float minDist = (a.radius + b.radius) * HELD_SCALE;
                if (d2 >= minDist * minDist || d2 == 0f) continue;
                float d = (float) Math.sqrt(d2);
                float nx = dx / d;
                float ny = dy / d;
                float overlap = (minDist - d) / 2f;
                a.holdX -= nx * overlap;
                a.holdY -= ny * overlap;
                b.holdX += nx * overlap;
                b.holdY += ny * overlap;
                float rvn = (a.holdVx - b.holdVx) * nx + (a.holdVy - b.holdVy) * ny;
                if (rvn > 0f) {
                    float impulse = (1f + HOLD_RESTITUTION) * rvn / 2f;
                    a.holdVx -= impulse * nx;
                    a.holdVy -= impulse * ny;
                    b.holdVx += impulse * nx;
                    b.holdVy += impulse * ny;
                }
            }
        }
        // hold wall: bounce back inside
        for (Loot crate : pincerHeld) {
            float maxR = HOLD_RADIUS - crate.radius * HELD_SCALE;
            float d = (float) Math.sqrt(crate.holdX * crate.holdX + crate.holdY * crate.holdY);
            if (d > maxR && d > 0.001f) {
                float nx = crate.holdX / d;
                float ny = crate.holdY / d;
                crate.holdX = nx * maxR;
                crate.holdY = ny * maxR;
                float vn = crate.holdVx * nx + crate.holdVy * ny;
                if (vn > 0f) {
                    crate.holdVx -= (1f + HOLD_RESTITUTION) * vn * nx;
                    crate.holdVy -= (1f + HOLD_RESTITUTION) * vn * ny;
                }
            }
        }
        // place in the world, riding with the ship
        float hx = cx + fx * HOLD_CENTER_FWD;
        float hy = cy + fy * HOLD_CENTER_FWD;
        for (Loot crate : pincerHeld) {
            crate.x = hx + rx * crate.holdX + fx * crate.holdY;
            crate.y = hy + ry * crate.holdX + fy * crate.holdY;
            crate.vx = player.vx;
            crate.vy = player.vy;
            crate.rotation += crate.spin * delta; // keeps tumbling in the hold
        }
        // jaw animation: base pinch from a full hold plus a snap pulse on capture/eject
        if (grabPulse > 0f) grabPulse = Math.max(0f, grabPulse - GRAB_PULSE_DECAY * delta);
        effects.setPincerGrab(0.25f * pincerHeld.size / pincerCapacity() + 0.75f * grabPulse);
    }

    private void releaseHook() {
        hookedPoint = null;
        hookedNet = null;
        if (hookState != HookState.STOWED) hookState = HookState.RETRACTING;
    }

    private void cutDeployedNet() {
        if (deployed == null) return;
        if (deployed.pts.size >= 3) {
            freeNets.add(deployed);
        } else {
            removeLinksFor(deployed); // stub too short to matter, discard it
        }
        deployed = null;
    }

    private void removeLinksFor(Net net) {
        for (int i = links.size - 1; i >= 0; i--) {
            NetLink link = links.get(i);
            if (net.pts.contains(link.a, true) || net.pts.contains(link.b, true)) {
                link.a.linked = false;
                link.b.linked = false;
                links.removeIndex(i);
            }
        }
    }

    private Array<Net> allNets() {
        netScratch.clear();
        if (deployed != null) netScratch.add(deployed);
        netScratch.addAll(freeNets);
        return netScratch;
    }

    private float tailX() {
        return player.x + Player.WIDTH / 2f + MathUtils.sinDeg(player.rotation) * 16f;
    }

    private float tailY() {
        return player.y + Player.HEIGHT / 2f - MathUtils.cosDeg(player.rotation) * 16f;
    }

    /** New rope points appear at the ship's tail as it moves; stops when fully paid out. */
    private void payOutDeployedNet() {
        if (deployed == null || deployed.pts.size >= maxTrailPoints()) return;
        float tx = tailX();
        float ty = tailY();
        if (deployed.pts.size == 0) {
            deployed.pts.add(new NetPoint(tx, ty, player.vx, player.vy));
            return;
        }
        NetPoint last = deployed.pts.peek();
        float dx = tx - last.x;
        float dy = ty - last.y;
        if (dx * dx + dy * dy >= MIN_SEGMENT * MIN_SEGMENT) {
            deployed.pts.add(new NetPoint(tx, ty, player.vx * 0.5f, player.vy * 0.5f));
        }
    }

    private void updateNetPoints(float delta) {
        float damping = (float) Math.exp(-NET_DRAG * delta);
        float glideDamping = (float) Math.exp(-NET_DRAG * EJECT_GLIDE_DRAG * delta);
        for (Net net : allNets()) {
            float d = damping;
            if (net.glideT > 0f) {
                net.glideT -= delta;
                d = glideDamping; // the wrap net glides with its ejected cargo
            }
            for (NetPoint p : net.pts) {
                if (p.attached != null) {
                    p.x = p.attached.x + p.attachDx;
                    p.y = p.attached.y + p.attachDy;
                    p.vx = p.attached.vx;
                    p.vy = p.attached.vy;
                    continue;
                }
                p.x += p.vx * delta;
                p.y += p.vy * delta;
                p.vx *= d;
                p.vy *= d;
            }
        }
    }

    /** Rope behaviour: segments only resist stretching. The deployed net is anchored at the ship's tail. */
    private void solveNetConstraints() {
        for (int iter = 0; iter < NET_ITERATIONS; iter++) {
            for (Net net : allNets()) {
                for (int i = 1; i < net.pts.size; i++) {
                    constrain(net.pts.get(i - 1), net.pts.get(i), NET_REST_LEN);
                }
                if (net.closed && net.pts.size > 2) {
                    constrain(net.pts.peek(), net.pts.first(), NET_REST_LEN);
                }
            }
            for (NetLink link : links) {
                constrain(link.a, link.b, NET_LINK_REST);
            }
            if (deployed != null && deployed.pts.size > 0) {
                // anchor: the newest point stays within reach of the tail (the ship is immovable to the net)
                NetPoint last = deployed.pts.peek();
                float dx = last.x - tailX();
                float dy = last.y - tailY();
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > NET_REST_LEN) {
                    moveEndpoint(last, -dx / dist * (dist - NET_REST_LEN), -dy / dist * (dist - NET_REST_LEN));
                }
            }
        }
    }

    private void constrain(NetPoint a, NetPoint b, float rest) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float d2 = dx * dx + dy * dy;
        if (d2 <= rest * rest || d2 == 0f) return;
        float dist = (float) Math.sqrt(d2);
        float excess = dist - rest;
        float nx = dx / dist;
        float ny = dy / dist;
        moveEndpoint(a, nx * excess * 0.5f, ny * excess * 0.5f);
        moveEndpoint(b, -nx * excess * 0.5f, -ny * excess * 0.5f);
    }

    /** Attached points transfer rope pull to their crate (which is much heavier than the net). */
    private void moveEndpoint(NetPoint p, float mx, float my) {
        if (p.attached == null) {
            p.x += mx;
            p.y += my;
            return;
        }
        Loot crate = p.attached;
        crate.x += mx * CARGO_TOW_FACTOR;
        crate.y += my * CARGO_TOW_FACTOR;
        crate.vx += mx * CARGO_TOW_IMPULSE;
        crate.vy += my * CARGO_TOW_IMPULSE;
        p.x = crate.x + p.attachDx;
        p.y = crate.y + p.attachDy;
    }

    /** The hulk shoves loose strands aside; sealed rings clip on nothing but their catch. */
    private void pushNetPoints() {
        for (Net net : allNets()) {
            if (net.closed) continue;
            for (NetPoint p : net.pts) {
                if (p.attached != null) continue;
                pushPoint(p, hulkX, hulkY, hulkRadius + 4f);
                for (float[] a : asteroids) {
                    pushPoint(p, a[0], a[1], a[2] + 3f);
                }
            }
        }
    }

    private static void pushPoint(NetPoint p, float cx, float cy, float radius) {
        float dx = p.x - cx;
        float dy = p.y - cy;
        float d2 = dx * dx + dy * dy;
        if (d2 >= radius * radius || d2 == 0f) return;
        float scale = radius / (float) Math.sqrt(d2);
        p.x = cx + dx * scale;
        p.y = cy + dy * scale;
    }

    /** Net points touching a crate stick to its surface; rope tension then tows the crate. */
    private void stickNetsToCargo() {
        for (Net net : allNets()) {
            for (NetPoint p : net.pts) {
                if (p.attached != null) continue;
                for (Loot crate : lootItems) {
                    if (isHeld(crate)) continue; // carried cargo can't be netted
                    // a sealed ring only grabs its own catch
                    if (net.closed && !net.contents.contains(crate, true)) continue;
                    float reach = crate.radius + NET_STICK_DIST;
                    float dx = p.x - crate.x;
                    float dy = p.y - crate.y;
                    float d2 = dx * dx + dy * dy;
                    if (d2 >= reach * reach) continue;
                    float dist = (float) Math.sqrt(d2);
                    float nx = dist > 0.001f ? dx / dist : 1f;
                    float ny = dist > 0.001f ? dy / dist : 0f;
                    p.attached = crate;
                    p.attachDx = nx * (crate.radius + 2f);
                    p.attachDy = ny * (crate.radius + 2f);
                    p.x = crate.x + p.attachDx;
                    p.y = crate.y + p.attachDy;
                    break;
                }
            }
        }
    }

    /** A free net whose strand crosses itself seals into a clean ring: tails outside the loop are trimmed. */
    private void closeLoops() {
        for (int n = freeNets.size - 1; n >= 0; n--) {
            Net net = freeNets.get(n);
            if (net.closed || net.pts.size < LOOP_MIN_SEGMENTS + 2) continue;
            outer:
            for (int i = 0; i < net.pts.size - LOOP_MIN_SEGMENTS; i++) {
                NetPoint p = net.pts.get(i);
                for (int j = i + LOOP_MIN_SEGMENTS; j < net.pts.size; j++) {
                    NetPoint q = net.pts.get(j);
                    float dx = p.x - q.x;
                    float dy = p.y - q.y;
                    if (dx * dx + dy * dy < NET_MERGE_DIST * NET_MERGE_DIST) {
                        closeLoop(net, i, j);
                        break outer;
                    }
                }
            }
        }
    }

    private void closeLoop(Net net, int start, int end) {
        for (int k = net.pts.size - 1; k > end; k--) dropPoint(net, k);
        for (int k = start - 1; k >= 0; k--) dropPoint(net, k);
        net.closed = true;
        sealRing(net);
    }

    /** On sealing: record the catch, set the drawstring shrink floor, burn away if the catch is empty. */
    private void sealRing(Net net) {
        captureContents(net);
        float radius = net.pts.size * NET_REST_LEN / MathUtils.PI2;
        net.ringRadius = radius;
        net.minRadius = radius * RING_MIN_FRACTION;
        boolean anyAttached = false;
        for (NetPoint p : net.pts) {
            if (p.attached != null) {
                anyAttached = true;
                break;
            }
        }
        if (net.contents.isEmpty() && !anyAttached) disintegrate(net); // an empty loop burns away
    }

    /** The crates sitting inside the ring polygon at sealing time are its catch. */
    private void captureContents(Net net) {
        net.contents.clear();
        float[] poly = new float[net.pts.size * 2];
        for (int i = 0; i < net.pts.size; i++) {
            poly[i * 2] = net.pts.get(i).x;
            poly[i * 2 + 1] = net.pts.get(i).y;
        }
        for (Loot crate : lootItems) {
            if (Intersector.isPointInPolygon(poly, 0, poly.length, crate.x, crate.y)) {
                net.contents.add(crate);
            }
        }
    }

    /** Removes a point cleanly: any tangle links through it dissolve, and the hook lets go of it. */
    private void dropPoint(Net net, int index) {
        NetPoint p = net.pts.removeIndex(index);
        if (p == hookedPoint) releaseHook();
        for (int i = links.size - 1; i >= 0; i--) {
            NetLink link = links.get(i);
            if (link.a == p || link.b == p) {
                link.a.linked = false;
                link.b.linked = false;
                links.removeIndex(i);
            }
        }
    }

    /** Closed rings relax toward a true circle (skipping points snagged on cargo) and cinch like a drawstring. */
    private void roundClosedLoops(float delta) {
        float t = Math.min(1f, LOOP_ROUNDING * delta);
        for (Net net : freeNets) {
            if (!net.closed || net.pts.size < 3) continue;
            net.ringRadius = Math.max(net.minRadius,
                net.ringRadius - net.ringRadius * RING_SHRINK_RATE * delta);
            float cx = 0, cy = 0;
            for (NetPoint p : net.pts) {
                cx += p.x;
                cy += p.y;
            }
            cx /= net.pts.size;
            cy /= net.pts.size;
            float radius = net.ringRadius;
            for (NetPoint p : net.pts) {
                if (p.attached != null) continue;
                float dx = p.x - cx;
                float dy = p.y - cy;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < 0.001f) continue;
                p.x += (cx + dx / d * radius - p.x) * t;
                p.y += (cy + dy / d * radius - p.y) * t;
            }
        }
    }

    /**
     * Net-to-net contact behaviour depends on what touches: two open strands splice
     * end-to-end, an open strand ties its ends onto a sealed ring, and two sealed
     * rings pop into one big ring. The deployed tether joins in once it is cut free.
     */
    /**
     * Casting over your own payline: the loop portion seals into a ring on the spot,
     * while the remainder stays attached to the ship as the ongoing cast.
     */
    private void sealCastLoop() {
        if (deployed == null || deployed.pts.size < LOOP_MIN_SEGMENTS + 8) return;
        int n = deployed.pts.size;
        // skip the freshest points near the tail so the payout doesn't self-trigger
        for (int i = 0; i < n - LOOP_MIN_SEGMENTS - 6; i++) {
            NetPoint p = deployed.pts.get(i);
            for (int j = i + LOOP_MIN_SEGMENTS; j < n - 6; j++) {
                NetPoint q = deployed.pts.get(j);
                float dx = p.x - q.x;
                float dy = p.y - q.y;
                if (dx * dx + dy * dy < NET_MERGE_DIST * NET_MERGE_DIST) {
                    spliceCastLoop(i, j);
                    return;
                }
            }
        }
    }

    private void spliceCastLoop(int start, int end) {
        Net ring = new Net();
        for (int k = start; k <= end; k++) {
            ring.pts.add(deployed.pts.get(k));
        }
        Array<NetPoint> rest = new Array<>();
        for (int k = end + 1; k < deployed.pts.size; k++) {
            rest.add(deployed.pts.get(k));
        }
        // the short tail paid out before the loop is trimmed away
        for (int k = 0; k < start; k++) {
            releasePointLinks(deployed.pts.get(k));
        }
        deployed.pts.clear();
        deployed.pts.addAll(rest);
        ring.closed = true;
        freeNets.add(ring);
        sealRing(ring); // empty catches burn away immediately, as usual
        game.sfx.playThud(0.3f);
    }

    private void releasePointLinks(NetPoint p) {
        for (int i = links.size - 1; i >= 0; i--) {
            NetLink link = links.get(i);
            if (link.a == p || link.b == p) {
                link.a.linked = false;
                link.b.linked = false;
                links.removeIndex(i);
            }
        }
    }

    /** Casting over a loose strand auto-cuts the payline and splices it on. */
    private void connectCastNet() {
        if (deployed == null || deployed.pts.size < 3) return;
        for (Net other : freeNets) {
            if (other.closed) continue;
            if (findContact(deployed, other)) {
                Net cast = deployed;
                deployed = null; // auto-cut
                freeNets.add(cast);
                spliceNets(cast, other, contact[0], contact[1]);
                return;
            }
        }
    }

    private void interactNets() {
        for (int a = 0; a < freeNets.size; a++) {
            for (int b = a + 1; b < freeNets.size; b++) {
                Net na = freeNets.get(a);
                Net nb = freeNets.get(b);
                if (!na.closed && !nb.closed) {
                    if (findContact(na, nb)) {
                        spliceNets(na, nb, contact[0], contact[1]);
                        return; // the arrays changed; resume scanning next frame
                    }
                } else if (na.closed && nb.closed) {
                    if (findContact(na, nb)) {
                        popMerge(na, nb);
                        return;
                    }
                } else if (na.closed) {
                    tieStrandToRing(nb, na);
                } else {
                    tieStrandToRing(na, nb);
                }
            }
        }
    }

    /** First pair of points from different nets within merge distance; indices land in {@code contact}. */
    private boolean findContact(Net na, Net nb) {
        for (int i = 0; i < na.pts.size; i++) {
            NetPoint p = na.pts.get(i);
            for (int j = 0; j < nb.pts.size; j++) {
                NetPoint q = nb.pts.get(j);
                float dx = p.x - q.x;
                float dy = p.y - q.y;
                if (dx * dx + dy * dy < NET_MERGE_DIST * NET_MERGE_DIST) {
                    contact[0] = i;
                    contact[1] = j;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Two open strands connect end-to-end: the joint slides to the nearest end of each
     * (the rope constraints cinch the spliced ends together), leaving one longer strand
     * whose free ends keep floating until they find something to connect to.
     */
    private void spliceNets(Net na, Net nb, int i, int j) {
        if (i < na.pts.size - 1 - i) na.pts.reverse();  // joining end of A becomes its last point
        if (j >= nb.pts.size - 1 - j) nb.pts.reverse(); // joining end of B becomes its first point
        na.pts.addAll(nb.pts);
        if (hookedNet == nb) hookedNet = na;
        freeNets.removeValue(nb, true);
        game.sfx.playThud(0.2f);
    }

    /** An open strand just ties on: each floating end within reach knots itself to the ring. */
    private void tieStrandToRing(Net strand, Net ring) {
        if (strand.pts.size == 0) return;
        tieEndToRing(strand.pts.first(), ring);
        tieEndToRing(strand.pts.peek(), ring);
    }

    private void tieEndToRing(NetPoint end, Net ring) {
        if (end.linked) return;
        for (NetPoint q : ring.pts) {
            float dx = end.x - q.x;
            float dy = end.y - q.y;
            if (dx * dx + dy * dy < NET_MERGE_DIST * NET_MERGE_DIST) {
                end.linked = true;
                links.add(new NetLink(end, q));
                return;
            }
        }
    }

    /** Two sealed rings pop into one massive ring: inner barriers dissolve, points reorder around the merged centre. */
    private void popMerge(Net na, Net nb) {
        for (int i = links.size - 1; i >= 0; i--) {
            NetLink link = links.get(i);
            boolean aInside = na.pts.contains(link.a, true) || nb.pts.contains(link.a, true);
            boolean bInside = na.pts.contains(link.b, true) || nb.pts.contains(link.b, true);
            if (aInside && bInside) {
                link.a.linked = false;
                link.b.linked = false;
                links.removeIndex(i);
            }
        }
        na.pts.addAll(nb.pts);
        if (hookedNet == nb) hookedNet = na;
        freeNets.removeValue(nb, true);

        float cx = 0, cy = 0;
        for (NetPoint p : na.pts) {
            cx += p.x;
            cy += p.y;
        }
        cx /= na.pts.size;
        cy /= na.pts.size;
        final float fcx = cx, fcy = cy;
        na.pts.sort((p, q) -> Float.compare(
            MathUtils.atan2(p.y - fcy, p.x - fcx),
            MathUtils.atan2(q.y - fcy, q.x - fcx)));
        na.closed = true;
        sealRing(na); // re-captures the combined catch and resets the drawstring floor
        burstSparks(cx, cy, 24);
        game.sfx.playThud(0.4f);
    }

    /** A strand tied down at both ends that caught nothing is an empty catch: it burns away. */
    private void dissolveEmptyCatches() {
        for (int n = freeNets.size - 1; n >= 0; n--) {
            Net net = freeNets.get(n);
            if (net.closed || net.pts.size < 2) continue;
            if (!net.pts.first().linked || !net.pts.peek().linked) continue;
            boolean caught = false;
            for (NetPoint p : net.pts) {
                if (p.attached != null) {
                    caught = true;
                    break;
                }
            }
            if (!caught) disintegrate(net);
        }
    }

    /** The net burns away: every point flares into a drifting spark. */
    private void disintegrate(Net net) {
        for (NetPoint p : net.pts) {
            spawnSpark(p.x, p.y, p.vx * 0.5f, p.vy * 0.5f);
        }
        removeLinksFor(net);
        if (hookedNet == net) releaseHook();
        freeNets.removeValue(net, true);
        game.sfx.playThud(0.25f);
    }

    private void burstSparks(float x, float y, int count) {
        for (int i = 0; i < count; i++) {
            spawnSpark(x, y, 0, 0);
        }
    }

    private void spawnSpark(float x, float y, float vx, float vy) {
        Spark s = new Spark();
        s.x = x;
        s.y = y;
        float angle = MathUtils.random(360f);
        float speed = MathUtils.random(25f, 75f);
        s.vx = vx + MathUtils.cosDeg(angle) * speed;
        s.vy = vy + MathUtils.sinDeg(angle) * speed;
        s.maxLife = s.life = MathUtils.random(SPARK_MIN_LIFE, SPARK_MAX_LIFE);
        sparks.add(s);
    }

    private void updateSparks(float delta) {
        for (int i = sparks.size - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.life -= delta;
            if (s.life <= 0) {
                sparks.removeIndex(i);
                continue;
            }
            s.x += s.vx * delta;
            s.y += s.vy * delta;
        }
    }

    /** Faint criss-cross chords over a sealed ring's interior: the mesh that makes it read as a catch. */
    private void drawFishnetMesh(Net net) {
        int n = net.pts.size;
        int step = Math.max(2, n / 14);
        int span = n / 4;
        if (span < 2) return;
        shapes.setColor(0.45f, 0.28f, 0.1f, 1f);
        for (int i = 0; i < n; i += step) {
            NetPoint p = net.pts.get(i);
            NetPoint q = net.pts.get((i + span) % n);
            NetPoint r = net.pts.get((i - span + n) % n);
            shapes.line(p.x, p.y, q.x, q.y);
            shapes.line(p.x, p.y, r.x, r.y);
        }
    }

    private void drawSparks() {
        if (sparks.size == 0) return;
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (Spark s : sparks) {
            float f = s.life / s.maxLife;
            shapes.setColor(0.95f * f + 0.05f, 0.6f * f, 0.15f * f, 1f);
            shapes.line(s.x, s.y, s.x - s.vx * 0.06f, s.y - s.vy * 0.06f);
        }
        shapes.end();
    }

    /** Cargo dragged into the exit zone is delivered. */
    private void collectAtExit() {
        for (int i = lootItems.size - 1; i >= 0; i--) {
            Loot crate = lootItems.get(i);
            if (Vector2.dst(crate.x, crate.y, EXIT_X, exitY) >= EXIT_RADIUS) continue;
            for (Net net : allNets()) {
                for (NetPoint p : net.pts) {
                    if (p.attached == crate) {
                        p.attached = null;
                        p.vx = crate.vx;
                        p.vy = crate.vy;
                    }
                }
            }
            lootItems.removeIndex(i);
            pincerHeld.removeValue(crate, true);
            removeFromCatches(crate); // a net delivered empty collapses too
            state.credits += crate.value;
            state.cargoDelivered++;
            lastDeliveredValue = crate.value;
            catchFlash = CATCH_FLASH_TIME;
            game.sfx.playCatch();
            if (lootItems.isEmpty()) {
                state.map.getCurrentNode().completed = true; // all cargo delivered: this site is stripped
                state.instancesCompleted++;
            }
        }
    }

    /** Tip of the hook while extending/retracting: straight out the back from the tail. */
    private float hookTipX() {
        return tailX() + MathUtils.sinDeg(player.rotation) * hookLen;
    }

    private float hookTipY() {
        return tailY() - MathUtils.cosDeg(player.rotation) * hookLen;
    }

    private void updateHook(float delta) {
        switch (hookState) {
            case EXTENDING: {
                hookLen += HOOK_SPEED * delta;
                if (hookLen >= HOOK_MAX_LEN) {
                    hookLen = HOOK_MAX_LEN;
                    hookState = HookState.RETRACTING;
                    break;
                }
                latchNearestNetPoint(hookTipX(), hookTipY());
                break;
            }
            case RETRACTING:
                hookLen -= HOOK_RETRACT_SPEED * delta;
                if (hookLen <= 0f) {
                    hookLen = 0f;
                    hookState = HookState.STOWED;
                }
                break;
            case LATCHED:
                if (hookedNet == null || !freeNets.contains(hookedNet, true)
                        || hookedPoint == null || !hookedNet.pts.contains(hookedPoint, true)) {
                    releaseHook(); // the strand was trimmed or the net vanished
                    break;
                }
                towNet();
                break;
            default:
                break;
        }
    }

    /** Latch onto the first free-net point the extending tip passes (the deployed tether is excluded). */
    private void latchNearestNetPoint(float tipX, float tipY) {
        for (Net net : freeNets) {
            for (NetPoint p : net.pts) {
                float dx = p.x - tipX;
                float dy = p.y - tipY;
                if (dx * dx + dy * dy < HOOK_CATCH_DIST * HOOK_CATCH_DIST) {
                    hookedPoint = p;
                    hookedNet = net;
                    hookState = HookState.LATCHED;
                    towLen = Math.max(HOOK_MIN_TOW, Vector2.dst(tailX(), tailY(), p.x, p.y));
                    game.sfx.playThud(0.3f);
                    return;
                }
            }
        }
    }

    /** Tow line: pure rope, resists stretching only. Pull propagates through the net's constraints. */
    private void towNet() {
        float tx = tailX();
        float ty = tailY();
        float dx = hookedPoint.x - tx;
        float dy = hookedPoint.y - ty;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist <= towLen || dist == 0f) return;

        float nx = dx / dist;
        float ny = dy / dist;
        float excess = dist - towLen;
        moveEndpoint(hookedPoint, -nx * excess, -ny * excess);

        // kill the strand's separating radial velocity (an inelastic tug on a light rope)
        float relVn = (hookedPoint.vx - player.vx) * nx + (hookedPoint.vy - player.vy) * ny;
        if (relVn > 0f) {
            hookedPoint.vx -= relVn * nx;
            hookedPoint.vy -= relVn * ny;
        }
    }

    private void applyHulkGravity(float delta) {
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        player.vx += hulkPullX(cx, cy) * delta;
        player.vy += hulkPullY(cx, cy) * delta;
        for (Loot loot : lootItems) {
            if (isHeld(loot)) continue;
            loot.vx += hulkPullX(loot.x, loot.y) * delta;
            loot.vy += hulkPullY(loot.x, loot.y) * delta;
        }
    }

    private float hulkPullX(float x, float y) {
        float dx = hulkX - x;
        float dy = hulkY - y;
        float d2 = Math.max(dx * dx + dy * dy, 1f);
        float a = Math.min(hulkGravityCap, hulkGravity / d2);
        return dx / (float) Math.sqrt(d2) * a;
    }

    private float hulkPullY(float x, float y) {
        float dx = hulkX - x;
        float dy = hulkY - y;
        float d2 = Math.max(dx * dx + dy * dy, 1f);
        float a = Math.min(hulkGravityCap, hulkGravity / d2);
        return dy / (float) Math.sqrt(d2) * a;
    }

    private void updateLoot(float delta) {
        float damping = (float) Math.exp(-CARGO_DRAG * delta);
        float glideDamping = (float) Math.exp(-CARGO_DRAG * EJECT_GLIDE_DRAG * delta);
        for (Loot loot : lootItems) {
            if (isHeld(loot)) continue; // pinned in the pincer jaws
            float d = damping;
            if (loot.glideT > 0f) {
                loot.glideT -= delta;
                d = glideDamping; // ejected stowage keeps its momentum
            }
            loot.vx *= d;
            loot.vy *= d;
            loot.spin *= d;
            keepDrifting(loot);
            loot.x += loot.vx * delta;
            loot.y += loot.vy * delta;
            loot.rotation += loot.spin * delta;
            if (loot.x < 20f) { loot.x = 20f; loot.vx = Math.abs(loot.vx); }
            if (loot.x > ARENA_WIDTH - 20f) { loot.x = ARENA_WIDTH - 20f; loot.vx = -Math.abs(loot.vx); }
            if (loot.y < 20f) { loot.y = 20f; loot.vy = Math.abs(loot.vy); }
            if (loot.y > ARENA_HEIGHT - 20f) { loot.y = ARENA_HEIGHT - 20f; loot.vy = -Math.abs(loot.vy); }
        }
    }

    /** Drag never stops cargo completely: below the minimum drift speed, velocity is scaled back up. */
    private static void keepDrifting(Loot loot) {
        float speed2 = loot.vx * loot.vx + loot.vy * loot.vy;
        if (speed2 >= CARGO_MIN_DRIFT * CARGO_MIN_DRIFT) return;
        if (speed2 < 0.0001f) {
            // fully stopped (e.g. pinned by collisions): nudge off in a random direction
            float angle = MathUtils.random(360f);
            loot.vx = MathUtils.cosDeg(angle) * CARGO_MIN_DRIFT;
            loot.vy = MathUtils.sinDeg(angle) * CARGO_MIN_DRIFT;
            return;
        }
        float scale = CARGO_MIN_DRIFT / (float) Math.sqrt(speed2);
        loot.vx *= scale;
        loot.vy *= scale;
    }

    /** Equal-mass crate bumps: separate the overlap and exchange momentum along the contact normal. */
    private void collideCargoWithCargo() {
        for (int i = 0; i < lootItems.size; i++) {
            for (int j = i + 1; j < lootItems.size; j++) {
                Loot a = lootItems.get(i);
                Loot b = lootItems.get(j);
                if (isHeld(a) || isHeld(b)) continue;
                float minDist = a.radius + b.radius;
                float dx = b.x - a.x;
                float dy = b.y - a.y;
                float d2 = dx * dx + dy * dy;
                if (d2 >= minDist * minDist || d2 == 0f) continue;
                float dist = (float) Math.sqrt(d2);
                float nx = dx / dist;
                float ny = dy / dist;
                float overlap = (minDist - dist) / 2f;
                a.x -= nx * overlap;
                a.y -= ny * overlap;
                b.x += nx * overlap;
                b.y += ny * overlap;
                float rvn = (a.vx - b.vx) * nx + (a.vy - b.vy) * ny;
                if (rvn <= 0f) continue; // already separating
                float impulse = (1f + RESTITUTION) * rvn / 2f;
                a.vx -= impulse * nx;
                a.vy -= impulse * ny;
                b.vx += impulse * nx;
                b.vy += impulse * ny;
            }
        }
    }

    /** Circle-vs-circle bump between ship and cargo; impulse split by mass, so heavy cargo shoves the ship back. */
    private void collideShipWithCargo() {
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;

        for (Loot loot : lootItems) {
            if (isHeld(loot)) continue;
            float minDist = SHIP_RADIUS + loot.radius;
            float dx = loot.x - cx;
            float dy = loot.y - cy;
            float dist2 = dx * dx + dy * dy;
            if (dist2 >= minDist * minDist || dist2 == 0f) continue;

            float dist = (float) Math.sqrt(dist2);
            float nx = dx / dist; // normal: ship -> cargo
            float ny = dy / dist;

            // separate overlap, weighted by inverse mass (light ship gets pushed out more)
            float overlap = minDist - dist;
            float invShip = 1f / SHIP_MASS;
            float invCargo = 1f / CARGO_MASS;
            float shipShare = invShip / (invShip + invCargo);
            player.x -= nx * overlap * shipShare;
            player.y -= ny * overlap * shipShare;
            loot.x += nx * overlap * (1f - shipShare);
            loot.y += ny * overlap * (1f - shipShare);

            float velAlongNormal = (player.vx - loot.vx) * nx + (player.vy - loot.vy) * ny;
            if (velAlongNormal <= 0f) continue; // already separating

            float impulse = (1f + RESTITUTION) * velAlongNormal / (invShip + invCargo);
            player.vx -= impulse * invShip * nx;
            player.vy -= impulse * invShip * ny;
            loot.vx += impulse * invCargo * nx;
            loot.vy += impulse * invCargo * ny;
            thud(velAlongNormal / 200f);
        }
    }

    /** The hulk is immovable: ship and cargo bounce off it. */
    private void collideWithHulk() {
        // the well doesn't bounce cargo: crates spiral in and burn at the horizon (#8)
        collideCircle(hulkX, hulkY, hulkRadius, false);
        for (float[] a : asteroids) {
            collideCircle(a[0], a[1], a[2], true);
        }
    }

    /** Immovable circle obstacle: shoves the ship (and optionally cargo) out with restitution. */
    private void collideCircle(float obX, float obY, float obR, boolean includeCargo) {
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        float shipDist = obR + SHIP_RADIUS;
        float dx = cx - obX;
        float dy = cy - obY;
        float d2 = dx * dx + dy * dy;
        if (d2 < shipDist * shipDist && d2 > 0f) {
            float d = (float) Math.sqrt(d2);
            float nx = dx / d;
            float ny = dy / d;
            player.x += nx * (shipDist - d);
            player.y += ny * (shipDist - d);
            float vn = player.vx * nx + player.vy * ny;
            if (vn < 0) {
                player.vx -= (1f + HULK_RESTITUTION) * vn * nx;
                player.vy -= (1f + HULK_RESTITUTION) * vn * ny;
                thud(-vn / 200f);
            }
        }

        if (!includeCargo) return;
        for (Loot loot : lootItems) {
            float cargoDist = obR + loot.radius;
            dx = loot.x - obX;
            dy = loot.y - obY;
            d2 = dx * dx + dy * dy;
            if (d2 >= cargoDist * cargoDist || d2 == 0f) continue;
            float d = (float) Math.sqrt(d2);
            float nx = dx / d;
            float ny = dy / d;
            loot.x += nx * (cargoDist - d);
            loot.y += ny * (cargoDist - d);
            float vn = loot.vx * nx + loot.vy * ny;
            if (vn < 0) {
                loot.vx -= (1f + HULK_RESTITUTION) * vn * nx;
                loot.vy -= (1f + HULK_RESTITUTION) * vn * ny;
            }
        }
    }

    private void thud(float strength) {
        if (effects.time() - lastThudTime < THUD_COOLDOWN) return;
        lastThudTime = effects.time();
        game.sfx.playThud(strength);
    }

    private void drawExitZone() {
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.2f, 0.7f, 0.3f, 1f);
        int segments = 32;
        for (int i = 0; i < segments; i += 2) { // dashed circle
            float a0 = i * 360f / segments;
            float a1 = (i + 1) * 360f / segments;
            shapes.line(
                EXIT_X + MathUtils.cosDeg(a0) * EXIT_RADIUS, exitY + MathUtils.sinDeg(a0) * EXIT_RADIUS,
                EXIT_X + MathUtils.cosDeg(a1) * EXIT_RADIUS, exitY + MathUtils.sinDeg(a1) * EXIT_RADIUS);
        }
        shapes.end();
    }

    private void drawHulk(float delta) {
        // gravity rings: they drift inward and brighten, like matter falling into the well
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < GRAVITY_RING_COUNT; i++) {
            float phase = (effects.time() * GRAVITY_RING_SPEED + i / (float) GRAVITY_RING_COUNT) % 1f;
            float r = MathUtils.lerp(gravityRingOuter(), hulkRadius + 6f, phase);
            float b = 0.08f + 0.22f * phase;
            shapes.setColor(0.8f * b, 0.7f * b, b, 1f); // faint violet
            shapes.circle(hulkX, hulkY, r, 48);
        }
        shapes.end();

        // asteroid field: immovable rocks that snag nets and bounce anything
        shapes.setColor(0.42f, 0.38f, 0.33f, 1f);
        for (int i = 0; i < asteroids.size; i++) {
            float[] a = asteroids.get(i);
            transform.setToTranslation(a[0], a[1], 0);
            shapes.setTransformMatrix(transform);
            shapes.polygon(asteroidShapes.get(i));
            shapes.circle(a[2] * 0.3f, -a[2] * 0.2f, a[2] * 0.22f, 8); // crater
        }
        shapes.setTransformMatrix(identity);

        hulkRotation += HULK_SPIN * delta;
        transform.setToTranslation(hulkX, hulkY, 0).rotate(0, 0, 1, hulkRotation);
        shapes.setTransformMatrix(transform);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.5f, 0.48f, 0.4f, 1f);
        shapes.polygon(hulkShape);
        // a couple of hull scars
        shapes.line(-14, 8, 10, -4);
        shapes.line(-4, -18, 6, 14);
        shapes.end();

        // event horizon: a small dark core with a bright boundary ring
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0, 0, 0, 1f);
        shapes.circle(0, 0, eventHorizonRadius(), 24);
        shapes.end();
        float pulse = 0.65f + 0.25f * MathUtils.sin(effects.time() * 2f);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.7f * pulse, 0.85f * pulse, pulse, 1f);
        shapes.circle(0, 0, eventHorizonRadius(), 24);
        shapes.end();
        shapes.setTransformMatrix(identity);
    }

    private void drawNets() {
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        // free nets: dim orange
        for (Net net : freeNets) {
            // sealed rings glow a little brighter than loose strands
            if (net.closed) shapes.setColor(0.95f, 0.6f, 0.2f, 1f);
            else shapes.setColor(0.75f, 0.45f, 0.15f, 1f);
            for (int i = 1; i < net.pts.size; i++) {
                NetPoint p = net.pts.get(i - 1);
                NetPoint q = net.pts.get(i);
                shapes.line(p.x, p.y, q.x, q.y);
            }
            if (net.closed && net.pts.size > 2) {
                shapes.line(net.pts.peek().x, net.pts.peek().y, net.pts.first().x, net.pts.first().y);
                drawFishnetMesh(net);
            }
        }
        // deployed net: yellow, fading toward the loose end, plus the line to the ship's tail
        if (deployed != null && deployed.pts.size > 0) {
            for (int i = 1; i < deployed.pts.size; i++) {
                NetPoint p = deployed.pts.get(i - 1);
                NetPoint q = deployed.pts.get(i);
                float fade = i / (float) deployed.pts.size; // dim toward the loose end
                shapes.setColor(1f, 1f, 0.3f, 1f);
                shapes.getColor().mul(0.25f + 0.75f * fade);
                shapes.line(p.x, p.y, q.x, q.y);
            }
            NetPoint last = deployed.pts.peek();
            shapes.setColor(1f, 1f, 0.3f, 1f);
            shapes.line(last.x, last.y, tailX(), tailY());
        }
        // tangle links
        shapes.setColor(0.9f, 0.7f, 0.4f, 1f);
        for (NetLink link : links) {
            shapes.line(link.a.x, link.a.y, link.b.x, link.b.y);
        }
        shapes.end();
    }

    private void drawLoot() {
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (Loot loot : lootItems) {
            // tier tint: small pale, medium yellow, large hot
            if (loot.tier == 0) shapes.setColor(0.85f, 0.85f, 0.55f, 1f);
            else if (loot.tier == 1) shapes.setColor(Color.YELLOW);
            else shapes.setColor(1f, 0.7f, 0.2f, 1f);
            transform.setToTranslation(loot.x, loot.y, 0).rotate(0, 0, 1, loot.rotation);
            if (isHeld(loot)) transform.scale(HELD_SCALE, HELD_SCALE, 1f); // shrunk to fit the hold
            shapes.setTransformMatrix(transform);
            float r = loot.radius;
            switch (loot.shape) {
                case 1: { // elongated container with rib lines
                    shapes.rect(-r * 1.2f, -r * 0.6f, r * 2.4f, r * 1.2f);
                    shapes.line(-r * 0.4f, -r * 0.6f, -r * 0.4f, r * 0.6f);
                    shapes.line(r * 0.4f, -r * 0.6f, r * 0.4f, r * 0.6f);
                    break;
                }
                case 2: { // hex barrel with a core ring
                    float hr = r * 0.95f;
                    for (int k = 0; k < 6; k++) {
                        float a0 = k * 60f;
                        float a1 = (k + 1) * 60f;
                        shapes.line(MathUtils.cosDeg(a0) * hr, MathUtils.sinDeg(a0) * hr,
                            MathUtils.cosDeg(a1) * hr, MathUtils.sinDeg(a1) * hr);
                    }
                    shapes.circle(0, 0, r * 0.45f, 10);
                    break;
                }
                default: { // classic crate with an X through it
                    float h = r * 0.75f;
                    shapes.rect(-h, -h, 2 * h, 2 * h);
                    shapes.line(-h, -h, h, h);
                    shapes.line(-h, h, h, -h);
                }
            }
        }
        shapes.end();

        // blinking salvage beacons, one corner of each crate
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Loot loot : lootItems) {
            float pulse = 0.2f + 0.8f * (0.5f + 0.5f * MathUtils.sin(effects.time() * 2.5f + loot.lightPhase));
            float s = loot.radius * 0.72f * (isHeld(loot) ? HELD_SCALE : 1f);
            float cos = MathUtils.cosDeg(loot.rotation);
            float sin = MathUtils.sinDeg(loot.rotation);
            float lx = loot.x + s * cos - s * sin;
            float ly = loot.y + s * sin + s * cos;
            shapes.setColor(pulse, 0.75f * pulse, 0.15f * pulse, 1f);
            shapes.circle(lx, ly, 1.6f, 6);
        }
        shapes.end();
    }

    private void drawHook() {
        if (hookState == HookState.STOWED) return;
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        float nx = tailX();
        float ny = tailY();
        float tipX, tipY;
        if (hookState == HookState.LATCHED && hookedPoint != null) {
            tipX = hookedPoint.x;
            tipY = hookedPoint.y;
            shapes.setColor(1f, 0.75f, 0.25f, 1f); // taut tow line
        } else {
            tipX = hookTipX();
            tipY = hookTipY();
            shapes.setColor(0.4f, 0.85f, 0.95f, 1f);
        }
        shapes.line(nx, ny, tipX, tipY);
        // hook barbs at the tip, perpendicular to the line
        float dx = tipX - nx;
        float dy = tipY - ny;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 0.001f) {
            float ux = dx / len;
            float uy = dy / len;
            shapes.line(tipX, tipY, tipX - ux * 6 - uy * 4, tipY - uy * 6 + ux * 4);
            shapes.line(tipX, tipY, tipX - ux * 6 + uy * 4, tipY - uy * 6 - ux * 4);
        }
        shapes.end();
    }

    private void drawWorldLabels() {
        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();
        font.setColor(0.3f, 0.9f, 0.4f, 1f);
        GlyphLayout gl = new GlyphLayout(font, "EXIT");
        font.draw(batch, "EXIT", EXIT_X - gl.width / 2f, exitY + EXIT_RADIUS + 24);
        batch.end();
    }

    private void drawHud() {
        shapes.setProjectionMatrix(hudMatrix);
        effects.renderThrottleHud(shapes, player);

        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        font.setColor(Color.WHITE);
        font.draw(batch, (player.throttle * 10) + "%",
            HUD_W - SpaceEffects.THROTTLE_HUD_MARGIN - SpaceEffects.THROTTLE_BLOCK_W,
            SpaceEffects.THROTTLE_HUD_MARGIN
                + Player.THROTTLE_STEPS * (SpaceEffects.THROTTLE_BLOCK_H + SpaceEffects.THROTTLE_BLOCK_GAP) + 20);
        font.setColor(Color.YELLOW);
        font.draw(batch, "Credits: " + state.credits, 10, HUD_H - 10);
        font.setColor(Color.WHITE);
        font.draw(batch, "Cargo left: " + lootItems.size, 10, HUD_H - 35);
        font.setColor(deployed != null ? Color.YELLOW : Color.GRAY);
        font.draw(batch, deployed != null ? "Net: DEPLOYED" : "Net: stowed", 10, HUD_H - 60);
        font.setColor(pincerHeld.size > 0 ? Color.YELLOW : Color.GRAY);
        font.draw(batch, "Pincer: " + pincerHeld.size + "/" + pincerCapacity(), 10, HUD_H - 85);

        if (catchFlash > 0) {
            font.setColor(Color.GREEN);
            String msg = "Delivered! +" + lastDeliveredValue;
            GlyphLayout gl = new GlyphLayout(font, msg);
            font.draw(batch, msg, (HUD_W - gl.width) / 2f, HUD_H - 60);
        }
        if (lootItems.isEmpty()) {
            font.setColor(Color.GREEN);
            String msg = "All cargo delivered! Press ESC to return";
            GlyphLayout gl = new GlyphLayout(font, msg);
            font.draw(batch, msg, (HUD_W - gl.width) / 2f, HUD_H / 2f);
        }
        Dev.drawIndicator(batch, font, HUD_W, HUD_H);
        batch.end();

        controlsHelp.draw(shapes, batch, font, hudMatrix);
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override public void pause() {}
    @Override public void resume() {}

    @Override
    public void hide() {
        dispose();
    }

    @Override
    public void dispose() {
        shapes.dispose();
        batch.dispose();
    }
}
