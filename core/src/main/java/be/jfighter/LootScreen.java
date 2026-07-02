package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
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
    private static final float NET_MERGE_DIST = 10f;  // nets closer than this tangle together
    private static final float NET_LINK_REST = 3f;    // tangled strands cinch this close: nets clump into a blob
    private static final int NET_LINKS_PER_FRAME = 8; // new tangle links allowed per net pair per frame
    private static final float NET_STICK_DIST = 3f;   // extra reach beyond cargo radius for sticking
    private static final float CARGO_TOW_FACTOR = 0.3f;  // how much rope correction moves a crate
    private static final float CARGO_TOW_IMPULSE = 2f;   // velocity a crate picks up from rope pull

    private static final int LOOT_COUNT = 6;
    private static final int CREDITS_PER_LOOT = 25;
    private static final float CATCH_FLASH_TIME = 1.5f;

    private static final float SHIP_MASS = 1f;
    private static final float CARGO_MASS = 4f;    // heavy: bumps barely move it, and shove you back
    private static final float CARGO_DRAG = 1.2f;  // exponential damping per second
    private static final float CARGO_MIN_DRIFT = 4f; // derelicts never sit fully still
    private static final float RESTITUTION = 0.4f;
    private static final float SHIP_RADIUS = 18f;
    private static final float CARGO_RADIUS = 11f;

    // towing hook: fires forward, latches onto cargo, becomes a tow line
    private static final float HOOK_SPEED = 420f;
    private static final float HOOK_RETRACT_SPEED = 560f;
    private static final float HOOK_MAX_LEN = 160f;
    private static final float HOOK_MIN_TOW = 34f;
    private static final float NOSE_OFFSET = 20f; // hook mounts at the ship's nose

    // derelict hulk: gravity well in the middle of the arena
    private static final float HULK_X = ARENA_WIDTH / 2f;
    private static final float HULK_Y = ARENA_HEIGHT / 2f;
    private static final float HULK_RADIUS = 36f;
    private static final float HULK_GRAVITY = 200000f;   // accel = gravity / distance², px/s²
    private static final float HULK_GRAVITY_CAP = 45f;   // max pull, close in
    private static final float HULK_RESTITUTION = 0.35f;
    private static final float HULK_SPIN = 6f;           // deg/s slow tumble
    private static final float THUD_COOLDOWN = 0.15f;
    // gravity indicator: rings that fall inward toward the well
    private static final float GRAVITY_RING_OUTER = 160f; // roughly where the pull becomes noticeable
    private static final int GRAVITY_RING_COUNT = 3;
    private static final float GRAVITY_RING_SPEED = 0.18f; // ring cycles per second
    private static final float EVENT_HORIZON_RADIUS = 16f; // dark core at the centre of the well

    // exit zone at the player's insertion point
    private static final float EXIT_X = 90f;
    private static final float EXIT_Y = ARENA_HEIGHT / 2f;
    private static final float EXIT_RADIUS = 70f;

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
    private final Array<Loot> lootItems = new Array<>();
    private float catchFlash;
    private float hulkRotation;

    private enum HookState { STOWED, EXTENDING, RETRACTING, LATCHED }

    private HookState hookState = HookState.STOWED;
    private float hookLen;
    private Loot hooked;
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
        final float lightPhase = MathUtils.random(MathUtils.PI2);

        Loot(float x, float y, float vx, float vy, float spin) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.spin = spin;
        }
    }

    public LootScreen(JFighter game, GameState state) {
        this.game = game;
        this.state = state;
    }

    @Override
    public void show() {
        viewport = new FitViewport(ARENA_WIDTH, ARENA_HEIGHT);
        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont();
        font.getData().setScale(1.4f);
        player = new Player(EXIT_X - Player.WIDTH / 2f, EXIT_Y - Player.HEIGHT / 2f);
        effects = new SpaceEffects(ARENA_WIDTH, ARENA_HEIGHT);
        hudMatrix.setToOrtho2D(0, 0, HUD_W, HUD_H);
        buildHulkShape();
        spawnLoot();
    }

    private void buildHulkShape() {
        int verts = 10;
        hulkShape = new float[verts * 2];
        for (int i = 0; i < verts; i++) {
            float angle = i * 360f / verts;
            float r = HULK_RADIUS + MathUtils.random(-10f, 10f);
            hulkShape[i * 2] = MathUtils.cosDeg(angle) * r;
            hulkShape[i * 2 + 1] = MathUtils.sinDeg(angle) * r;
        }
    }

    private void spawnLoot() {
        lootItems.clear();
        for (int i = 0; i < LOOT_COUNT; i++) {
            float x, y;
            do {
                x = MathUtils.random(320f, ARENA_WIDTH - 60f);
                y = MathUtils.random(60f, ARENA_HEIGHT - 60f);
            } while (Vector2.dst(x, y, HULK_X, HULK_Y) < HULK_RADIUS + 90f
                    || Vector2.dst(x, y, EXIT_X, EXIT_Y) < EXIT_RADIUS + 60f);
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
        // stop rendering once the screen switches: hide() disposed our resources
        if (handleInput(delta)) return;
        effects.handleFlightInput(player, delta);
        applyHulkGravity(delta);
        player.updatePosition(delta);
        if (player.wrapAround(ARENA_WIDTH, ARENA_HEIGHT)) {
            cutDeployedNet(); // the net snaps loose when the ship crosses the border
            releaseHook();    // and the tow line snaps too
        }
        updateHook(delta);
        updateLoot(delta);
        collideCargoWithCargo();
        collideShipWithCargo();
        collideWithHulk();
        effects.update(player, delta);

        payOutDeployedNet();
        updateNetPoints(delta);
        solveNetConstraints();
        pushNetPoints();
        stickNetsToCargo();
        mergeNets();
        collectAtExit();
        if (catchFlash > 0) catchFlash -= delta;

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
        effects.renderShip(shapes, player);
        drawWorldLabels();
        drawHud();
    }

    /** Returns true when the screen was switched and rendering must stop. */
    private boolean handleInput(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.setScreen(new OverworldScreen(game, state));
            return true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            if (deployed == null) {
                deployed = new Net(); // start paying out a fresh net
            } else {
                cutDeployedNet();     // cut: the net floats free
            }
        }
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

    private void releaseHook() {
        hooked = null;
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
        if (deployed == null || deployed.pts.size >= MAX_TRAIL_POINTS) return;
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
        for (Net net : allNets()) {
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
                p.vx *= damping;
                p.vy *= damping;
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

    /** The hulk shoves loose strands aside; the ship flies through nets freely. */
    private void pushNetPoints() {
        for (Net net : allNets()) {
            for (NetPoint p : net.pts) {
                if (p.attached != null) continue;
                pushPoint(p, HULK_X, HULK_Y, HULK_RADIUS + 4f);
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
        float reach = CARGO_RADIUS + NET_STICK_DIST;
        for (Net net : allNets()) {
            for (NetPoint p : net.pts) {
                if (p.attached != null) continue;
                for (Loot crate : lootItems) {
                    float dx = p.x - crate.x;
                    float dy = p.y - crate.y;
                    float d2 = dx * dx + dy * dy;
                    if (d2 >= reach * reach) continue;
                    float dist = (float) Math.sqrt(d2);
                    float nx = dist > 0.001f ? dx / dist : 1f;
                    float ny = dist > 0.001f ? dy / dist : 0f;
                    p.attached = crate;
                    p.attachDx = nx * (CARGO_RADIUS + 2f);
                    p.attachDy = ny * (CARGO_RADIUS + 2f);
                    p.x = crate.x + p.attachDx;
                    p.y = crate.y + p.attachDy;
                    break;
                }
            }
        }
    }

    /** Different nets that touch click together at every contact, cinching into one blob. */
    private void mergeNets() {
        Array<Net> nets = allNets();
        for (int a = 0; a < nets.size; a++) {
            for (int b = a + 1; b < nets.size; b++) {
                linkPair(nets.get(a), nets.get(b));
            }
        }
    }

    private void linkPair(Net na, Net nb) {
        int made = 0;
        for (int i = 0; i < na.pts.size && made < NET_LINKS_PER_FRAME; i++) {
            NetPoint p = na.pts.get(i);
            if (p.linked) continue;
            for (int j = 0; j < nb.pts.size; j++) {
                NetPoint q = nb.pts.get(j);
                if (q.linked) continue;
                float dx = p.x - q.x;
                float dy = p.y - q.y;
                if (dx * dx + dy * dy < NET_MERGE_DIST * NET_MERGE_DIST) {
                    p.linked = true;
                    q.linked = true;
                    links.add(new NetLink(p, q));
                    made++;
                    break;
                }
            }
        }
    }

    /** Cargo dragged into the exit zone is delivered. */
    private void collectAtExit() {
        for (int i = lootItems.size - 1; i >= 0; i--) {
            Loot crate = lootItems.get(i);
            if (Vector2.dst(crate.x, crate.y, EXIT_X, EXIT_Y) >= EXIT_RADIUS) continue;
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
            state.credits += CREDITS_PER_LOOT;
            catchFlash = CATCH_FLASH_TIME;
            game.sfx.playCatch();
        }
    }

    private float noseX() {
        return player.x + Player.WIDTH / 2f - MathUtils.sinDeg(player.rotation) * NOSE_OFFSET;
    }

    private float noseY() {
        return player.y + Player.HEIGHT / 2f + MathUtils.cosDeg(player.rotation) * NOSE_OFFSET;
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
                // latch onto the first crate the tip touches
                float tipX = noseX() - MathUtils.sinDeg(player.rotation) * hookLen;
                float tipY = noseY() + MathUtils.cosDeg(player.rotation) * hookLen;
                float reach = CARGO_RADIUS + 4f;
                for (Loot crate : lootItems) {
                    float dx = crate.x - tipX;
                    float dy = crate.y - tipY;
                    if (dx * dx + dy * dy < reach * reach) {
                        hooked = crate;
                        hookState = HookState.LATCHED;
                        float cx = player.x + Player.WIDTH / 2f;
                        float cy = player.y + Player.HEIGHT / 2f;
                        towLen = Math.max(HOOK_MIN_TOW, Vector2.dst(cx, cy, crate.x, crate.y));
                        game.sfx.playThud(0.3f);
                        break;
                    }
                }
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
                if (hooked == null || !lootItems.contains(hooked, true)) {
                    releaseHook(); // the crate was delivered or caught out from under us
                    break;
                }
                towCrate();
                break;
            default:
                break;
        }
    }

    /** Tow line: pure rope, resists stretching only. The heavy crate yanks the light ship. */
    private void towCrate() {
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        float dx = hooked.x - cx;
        float dy = hooked.y - cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist <= towLen || dist == 0f) return;

        float nx = dx / dist;
        float ny = dy / dist;
        float excess = dist - towLen;
        float invShip = 1f / SHIP_MASS;
        float invCargo = 1f / CARGO_MASS;
        float shipShare = invShip / (invShip + invCargo);
        player.x += nx * excess * shipShare;
        player.y += ny * excess * shipShare;
        hooked.x -= nx * excess * (1f - shipShare);
        hooked.y -= ny * excess * (1f - shipShare);

        // kill the separating radial velocity, mass-weighted (an inelastic tug)
        float relVn = (hooked.vx - player.vx) * nx + (hooked.vy - player.vy) * ny;
        if (relVn > 0f) {
            float impulse = relVn / (invShip + invCargo);
            player.vx += impulse * invShip * nx;
            player.vy += impulse * invShip * ny;
            hooked.vx -= impulse * invCargo * nx;
            hooked.vy -= impulse * invCargo * ny;
        }
    }

    private void applyHulkGravity(float delta) {
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        player.vx += hulkPullX(cx, cy) * delta;
        player.vy += hulkPullY(cx, cy) * delta;
        for (Loot loot : lootItems) {
            loot.vx += hulkPullX(loot.x, loot.y) * delta;
            loot.vy += hulkPullY(loot.x, loot.y) * delta;
        }
    }

    private static float hulkPullX(float x, float y) {
        float dx = HULK_X - x;
        float dy = HULK_Y - y;
        float d2 = Math.max(dx * dx + dy * dy, 1f);
        float a = Math.min(HULK_GRAVITY_CAP, HULK_GRAVITY / d2);
        return dx / (float) Math.sqrt(d2) * a;
    }

    private static float hulkPullY(float x, float y) {
        float dx = HULK_X - x;
        float dy = HULK_Y - y;
        float d2 = Math.max(dx * dx + dy * dy, 1f);
        float a = Math.min(HULK_GRAVITY_CAP, HULK_GRAVITY / d2);
        return dy / (float) Math.sqrt(d2) * a;
    }

    private void updateLoot(float delta) {
        float damping = (float) Math.exp(-CARGO_DRAG * delta);
        for (Loot loot : lootItems) {
            loot.vx *= damping;
            loot.vy *= damping;
            loot.spin *= damping;
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
        float minDist = 2 * CARGO_RADIUS;
        for (int i = 0; i < lootItems.size; i++) {
            for (int j = i + 1; j < lootItems.size; j++) {
                Loot a = lootItems.get(i);
                Loot b = lootItems.get(j);
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
        float minDist = SHIP_RADIUS + CARGO_RADIUS;

        for (Loot loot : lootItems) {
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
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        float shipDist = HULK_RADIUS + SHIP_RADIUS;
        float dx = cx - HULK_X;
        float dy = cy - HULK_Y;
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

        float cargoDist = HULK_RADIUS + CARGO_RADIUS;
        for (Loot loot : lootItems) {
            dx = loot.x - HULK_X;
            dy = loot.y - HULK_Y;
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
                EXIT_X + MathUtils.cosDeg(a0) * EXIT_RADIUS, EXIT_Y + MathUtils.sinDeg(a0) * EXIT_RADIUS,
                EXIT_X + MathUtils.cosDeg(a1) * EXIT_RADIUS, EXIT_Y + MathUtils.sinDeg(a1) * EXIT_RADIUS);
        }
        shapes.end();
    }

    private void drawHulk(float delta) {
        // gravity rings: they drift inward and brighten, like matter falling into the well
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < GRAVITY_RING_COUNT; i++) {
            float phase = (effects.time() * GRAVITY_RING_SPEED + i / (float) GRAVITY_RING_COUNT) % 1f;
            float r = MathUtils.lerp(GRAVITY_RING_OUTER, HULK_RADIUS + 6f, phase);
            float b = 0.08f + 0.22f * phase;
            shapes.setColor(0.8f * b, 0.7f * b, b, 1f); // faint violet
            shapes.circle(HULK_X, HULK_Y, r, 48);
        }
        shapes.end();

        hulkRotation += HULK_SPIN * delta;
        transform.setToTranslation(HULK_X, HULK_Y, 0).rotate(0, 0, 1, hulkRotation);
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
        shapes.circle(0, 0, EVENT_HORIZON_RADIUS, 24);
        shapes.end();
        float pulse = 0.65f + 0.25f * MathUtils.sin(effects.time() * 2f);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.7f * pulse, 0.85f * pulse, pulse, 1f);
        shapes.circle(0, 0, EVENT_HORIZON_RADIUS, 24);
        shapes.end();
        shapes.setTransformMatrix(identity);
    }

    private void drawNets() {
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        // free nets: dim orange
        for (Net net : freeNets) {
            shapes.setColor(0.75f, 0.45f, 0.15f, 1f);
            for (int i = 1; i < net.pts.size; i++) {
                NetPoint p = net.pts.get(i - 1);
                NetPoint q = net.pts.get(i);
                shapes.line(p.x, p.y, q.x, q.y);
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
        shapes.setColor(Color.YELLOW);
        for (Loot loot : lootItems) {
            transform.setToTranslation(loot.x, loot.y, 0).rotate(0, 0, 1, loot.rotation);
            shapes.setTransformMatrix(transform);
            // crate: square with an X through it
            shapes.rect(-8, -8, 16, 16);
            shapes.line(-8, -8, 8, 8);
            shapes.line(-8, 8, 8, -8);
        }
        shapes.end();

        // blinking salvage beacons, one corner of each crate
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Loot loot : lootItems) {
            float pulse = 0.2f + 0.8f * (0.5f + 0.5f * MathUtils.sin(effects.time() * 2.5f + loot.lightPhase));
            float cos = MathUtils.cosDeg(loot.rotation);
            float sin = MathUtils.sinDeg(loot.rotation);
            float lx = loot.x + 8 * cos - 8 * sin;
            float ly = loot.y + 8 * sin + 8 * cos;
            shapes.setColor(pulse, 0.75f * pulse, 0.15f * pulse, 1f);
            shapes.circle(lx, ly, 1.6f, 6);
        }
        shapes.end();
    }

    private void drawHook() {
        if (hookState == HookState.STOWED) return;
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        float nx = noseX();
        float ny = noseY();
        float tipX, tipY;
        if (hookState == HookState.LATCHED && hooked != null) {
            tipX = hooked.x;
            tipY = hooked.y;
            shapes.setColor(1f, 0.75f, 0.25f, 1f); // taut tow line
        } else {
            tipX = nx - MathUtils.sinDeg(player.rotation) * hookLen;
            tipY = ny + MathUtils.cosDeg(player.rotation) * hookLen;
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
        font.draw(batch, "EXIT", EXIT_X - gl.width / 2f, EXIT_Y + EXIT_RADIUS + 24);
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

        if (catchFlash > 0) {
            font.setColor(Color.GREEN);
            String msg = "Delivered! +" + CREDITS_PER_LOOT;
            GlyphLayout gl = new GlyphLayout(font, msg);
            font.draw(batch, msg, (HUD_W - gl.width) / 2f, HUD_H - 60);
        }
        if (lootItems.isEmpty()) {
            font.setColor(Color.GREEN);
            String msg = "All cargo delivered! Press ESC to return";
            GlyphLayout gl = new GlyphLayout(font, msg);
            font.draw(batch, msg, (HUD_W - gl.width) / 2f, HUD_H / 2f);
        } else {
            font.setColor(Color.GRAY);
            font.draw(batch, "SPACE: deploy / cut net - E: tow hook - drag cargo to EXIT - ESC to leave", 10, 25);
        }
        batch.end();
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
        font.dispose();
    }
}
