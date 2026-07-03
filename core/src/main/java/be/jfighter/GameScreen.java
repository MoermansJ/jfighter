package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

public class GameScreen implements Screen {
    // a massively larger battlespace: several screens across; the camera follows the
    // controlled ship and the radar carries the overview (densities scale via SpaceEffects)
    private static final float ARENA_WIDTH = 3600f;
    private static final float ARENA_HEIGHT = 2025f;
    private static final float VIEW_WIDTH = 1440f;  // camera window
    private static final float VIEW_HEIGHT = 810f;
    // HUD renders through its own 960x540 ortho matrix, unaffected by camera zoom
    private static final float HUD_W = JFighter.WORLD_WIDTH;
    private static final float HUD_H = JFighter.WORLD_HEIGHT;

    // solid arena walls: no wrapping or clipping through the border
    private static final float WALL_RESTITUTION = 0.5f;

    // enemy ships: idle drifters for now — targets to fly against and shoot at
    private static final float ENEMY_RADIUS = 18f;
    private static final float ENEMY_HP = 30f;
    private static final int CREDITS_PER_KILL = 40;

    // ship integrity: shields absorb first and recharge after a quiet spell
    private static final float SHIP_HIT_RADIUS = 18f;
    private static final float HIT_DAMAGE = 10f;
    private static final float SHIELD_RECHARGE_DELAY = 3f; // seconds without a hit
    private static final float SHIELD_RECHARGE_RATE = 8f;  // points per second
    private static final float DEFEAT_DELAY = 1.6f;        // linger on the wreck before the summary

    private final JFighter game;
    private final GameState state;

    private ShapeRenderer shapeRenderer;
    private SpriteBatch batch;
    private BitmapFont font;
    private FitViewport viewport;
    private Player player;
    private SpaceEffects effects;
    private final Array<Projectile> projectiles = new Array<>();
    private final Array<Enemy> enemies = new Array<>();
    // manual dev control: -1 = the fighter, otherwise an enemy index (no AI yet — TAB hands over the stick)
    private int controlled = -1;
    private float shieldSince;   // time since the last hit (drives recharge)
    private float shieldFlash;   // shimmer ring on shield hits
    private float defeatT = -1f; // >= 0 once the fighter is destroyed
    private float stormTimer = MathUtils.random(6f, 11f); // stormy nodes: next radiation wave
    private float stormFlash;
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 hudMatrix = new Matrix4(); // HUD ignores camera zoom
    private final PauseMenu pause = new PauseMenu();
    private final Radar radar = new Radar(ARENA_WIDTH, ARENA_HEIGHT);
    private final Array<Weapon> weapons = new Array<>();
    private int activeWeapon;
    private Enemy lockTarget; // auto-aim target for homing rockets
    private final Array<float[]> beams = new Array<>(); // {x1,y1,x2,y2,life,maxLife} laser flashes
    private final ControlsHelp controlsHelp = new ControlsHelp(new String[][]{
        {"SPACE", "fire"},
        {"UP/DOWN", "throttle"},
        {"LEFT/RIGHT", "turn"},
        {"LMB", "set autopilot"},
        {"RMB", "cancel autopilot"},
        {"TAB", "switch controlled ship (dev)"},
        {"ESC", "pause menu"},
    });

    // death effects: assembled from sparks, tumbling hull shards and blast rings,
    // with a random kind and jittered parameters so no two kills look identical
    private enum DeathKind { FIREBALL, BREAKUP, CHAIN, OVERLOAD }

    private static class Spark {
        float x, y, vx, vy, life, maxLife;
    }

    private static class Shard {
        float x, y, vx, vy, rotation, spin, len, life, maxLife;
    }

    private static class Blast {
        float x, y, age, delay, speed, maxR;
    }

    private static class WingDebris {
        float x, y, vx, vy, rotation, spin, life = 25f;
        boolean left;
    }

    private static class CritToast {
        Enemy e;
        String text;
        float t = 2.5f;
    }

    private final Array<Spark> sparks = new Array<>();
    private final Array<CritToast> critToasts = new Array<>();
    private final Array<WingDebris> wingDebris = new Array<>();
    private final Array<Shard> shards = new Array<>();
    private final Array<Blast> blasts = new Array<>();

    private static class Enemy {
        final Player body; // reuses ship physics: drag keeps them slowly adrift
        float hp = ENEMY_HP;
        final long seed = MathUtils.random.nextLong(); // deterministic damage-mark placement
        boolean onFire;      // crit: damage over time + flames
        boolean engineOut;   // crit: no thrust
        boolean helmDamaged; // crit: crippled turning
        // destructible sections (#73): wings shear off before the core gives out
        float leftWingHp = 12f;
        float rightWingHp = 12f;

        Enemy(float x, float y) {
            body = new Player(x, y);
            body.rotation = MathUtils.random(360f);
            float angle = MathUtils.random(360f);
            float speed = MathUtils.random(8f, 22f);
            body.vx = MathUtils.cosDeg(angle) * speed;
            body.vy = MathUtils.sinDeg(angle) * speed;
        }

        float centerX() {
            return body.x + Player.WIDTH / 2f;
        }

        float centerY() {
            return body.y + Player.HEIGHT / 2f;
        }
    }

    public GameScreen(JFighter game, GameState state) {
        this.game = game;
        this.state = state;
    }

    @Override
    public void show() {
        shapeRenderer = new ShapeRenderer();
        batch = new SpriteBatch();
        font = game.fonts.font;
        Fonts.scale(font, 1.4f);
        viewport = new FitViewport(VIEW_WIDTH, VIEW_HEIGHT);
        player = new Player(120f, (ARENA_HEIGHT - Player.HEIGHT) / 2f);
        player.thrustMult = state.thrustMult() * (1f + 0.04f * state.roomStats[0])
            * (0.6f + 0.2f * state.power[GameState.PWR_ENGINES]); // engine crew + reactor power
        effects = new SpaceEffects(ARENA_WIDTH, ARENA_HEIGHT);
        hudMatrix.setToOrtho2D(0, 0, HUD_W, HUD_H);
        for (Weapon.Type t : state.loadout) weapons.add(new Weapon(t));
        spawnEnemies();
        game.sfx.startThruster();
    }

    private void spawnEnemies() {
        int count = MathUtils.random(3, 5);
        for (int i = 0; i < count; i++) {
            float x, y;
            do {
                x = MathUtils.random(ARENA_WIDTH * 0.45f, ARENA_WIDTH - 80f);
                y = MathUtils.random(60f, ARENA_HEIGHT - 60f);
            } while (tooCloseToOthers(x, y));
            enemies.add(new Enemy(x, y));
        }
    }

    private boolean tooCloseToOthers(float x, float y) {
        for (Enemy e : enemies) {
            if (Vector2.dst(x, y, e.body.x, e.body.y) < 140f) return true;
        }
        return false;
    }

    @Override
    public void render(float delta) {
        if (defeatT >= 0) {
            defeatT += delta;
            if (defeatT >= DEFEAT_DELAY) {
                game.currentRun = null; // run lost
                SaveGame.clear();
                game.setScreen(new SummaryScreen(game, state, false));
                return;
            }
        }
        if (defeatT < 0) pause.handleEscape();
        if (!pause.isOpen()) {
            // stop rendering once the screen switches: hide() disposed our resources
            if (defeatT < 0 && handleInput(delta)) return;
            if (defeatT < 0) effects.handleFlightInput(controlledBody(), delta);
            shieldSince += delta;
            if (shieldSince >= SHIELD_RECHARGE_DELAY && state.shield < state.maxShield) {
                state.shield = Math.min(state.maxShield,
                    state.shield + (SHIELD_RECHARGE_RATE + state.shieldRechargeBonus())
                        * (state.power[GameState.PWR_SHIELDS] / 2f) * delta);
            }
            if (shieldFlash > 0) shieldFlash -= delta;
            player.updatePosition(delta);
            bounceOffWalls(player);
            for (int i = enemies.size - 1; i >= 0; i--) {
                Enemy e = enemies.get(i);
                e.body.updatePosition(delta);
                bounceOffWalls(e.body);
                if (e.onFire) {
                    e.hp -= 2.5f * delta; // burning until it gives out
                    if (MathUtils.random() < 14f * delta) {
                        addSparks(e.centerX() + MathUtils.random(-8f, 8f),
                            e.centerY() + MathUtils.random(-8f, 8f), e.body.vx, e.body.vy, 1);
                    }
                    if (e.hp <= 0) killEnemy(i);
                }
            }
            for (int i = critToasts.size - 1; i >= 0; i--) {
                CritToast ct = critToasts.get(i);
                ct.t -= delta;
                if (ct.t <= 0) critToasts.removeIndex(i);
            }
            for (int i = wingDebris.size - 1; i >= 0; i--) {
                WingDebris wd = wingDebris.get(i);
                wd.life -= delta;
                if (wd.life <= 0) {
                    wingDebris.removeIndex(i);
                    continue;
                }
                wd.x += wd.vx * delta;
                wd.y += wd.vy * delta;
                wd.rotation += wd.spin * delta;
            }
            effects.update(player, delta);
            effects.spawnExhaust(player, delta);
            for (Enemy e : enemies) effects.spawnExhaust(e.body, delta);
            game.sfx.setThrusterLevel(controlledBody().thrustLevel);
            fireWeapons(delta);
            updateProjectiles(delta);
            updateEffects(delta);
            if (state.map.getCurrentNode().stormy && defeatT < 0) {
                stormTimer -= delta;
                if (stormTimer <= 0) {
                    // solar radiation event: a wave knocks a chunk off the shields
                    stormTimer = MathUtils.random(7f, 12f);
                    stormFlash = 0.7f;
                    damagePlayer(8f);
                }
            }
            if (stormFlash > 0) stormFlash -= delta;
        }

        ScreenUtils.clear(0, 0, 0, 1f);
        viewport.apply();
        effects.applyZoom(viewport, controlledBody(), delta);
        // follow camera, clamped inside the arena
        com.badlogic.gdx.graphics.OrthographicCamera cam =
            (com.badlogic.gdx.graphics.OrthographicCamera) viewport.getCamera();
        float halfW = viewport.getWorldWidth() * cam.zoom / 2f;
        float halfH = viewport.getWorldHeight() * cam.zoom / 2f;
        Player followed = controlledBody();
        cam.position.x = MathUtils.clamp(followed.x + Player.WIDTH / 2f, halfW, ARENA_WIDTH - halfW);
        cam.position.y = MathUtils.clamp(followed.y + Player.HEIGHT / 2f, halfH, ARENA_HEIGHT - halfH);
        cam.update();
        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);

        effects.renderBackground(shapeRenderer);
        drawArenaBounds();
        effects.renderAutopilot(shapeRenderer);
        drawEnemies();
        drawEffects();
        drawThreatMarkers();
        drawCritToasts();
        // active mount barrel: shows where the turret is actually pointing
        if (defeatT < 0 && !weapons.isEmpty()) {
            Weapon aw = weapons.get(activeWeapon);
            float barrelRot = player.rotation + (aw.type.turretArc > 0 ? aw.turret : 0f);
            float bx = player.x + Player.WIDTH / 2f;
            float by = player.y + Player.HEIGHT / 2f;
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
            shapeRenderer.setColor(0.6f, 0.65f, 0.7f, 1f);
            shapeRenderer.line(bx - MathUtils.sinDeg(barrelRot) * 14f, by + MathUtils.cosDeg(barrelRot) * 14f,
                bx - MathUtils.sinDeg(barrelRot) * 30f, by + MathUtils.cosDeg(barrelRot) * 30f);
            shapeRenderer.end();
        }
        if (defeatT < 0) {
            effects.renderShip(shapeRenderer, player);
            float hullFrac = state.hull / state.maxHull;
            if (hullFrac < 0.95f) {
                shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
                transform.setToTranslation(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f, 0)
                    .rotate(0, 0, 1, player.rotation);
                shapeRenderer.setTransformMatrix(transform);
                java.util.Random r = new java.util.Random(1337);
                int marks = Math.min(6, 1 + (int) ((1f - hullFrac) * 6f));
                shapeRenderer.setColor(0.45f, 0.1f, 0.08f, 1f);
                for (int m = 0; m < marks; m++) {
                    float mx = -18f + r.nextFloat() * 36f;
                    float my = -10f + r.nextFloat() * 24f;
                    float ang = r.nextFloat() * 360f;
                    float len = 3f + r.nextFloat() * 4f;
                    shapeRenderer.line(mx, my, mx + MathUtils.cosDeg(ang) * len,
                        my + MathUtils.sinDeg(ang) * len);
                }
                shapeRenderer.setTransformMatrix(transform.idt());
                shapeRenderer.end();
            }
            if (shieldFlash > 0) {
                shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
                float a = shieldFlash / 0.4f;
                shapeRenderer.setColor(0.4f * a, 0.8f * a, a, 1f);
                shapeRenderer.circle(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f,
                    26f + 6f * (1f - a), 24);
                shapeRenderer.end();
            }
        }

        // projectiles (filled mode, per-projectile transform); size scales with caliber
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (Projectile p : projectiles) {
            transform.setToTranslation(p.x, p.y, 0).rotate(0, 0, 1, p.rotation);
            shapeRenderer.setTransformMatrix(transform);
            if (p.rocket) {
                shapeRenderer.setColor(0.85f, 0.85f, 0.9f, 1f);
                shapeRenderer.rect(-2, -8, 4, 16);
                shapeRenderer.setColor(1f, 0.55f, 0.15f, 1f);
                shapeRenderer.rect(-1.5f, -13, 3, 5); // flame
            } else {
                float s = 0.6f + p.damage * 0.05f;
                shapeRenderer.setColor(Color.GREEN);
                shapeRenderer.rect(-2 * s, -7 * s, 4 * s, 14 * s);
                shapeRenderer.circle(0, 7 * s, 2 * s, 8);
                shapeRenderer.circle(0, -7 * s, 2 * s, 8);
            }
        }
        shapeRenderer.setTransformMatrix(transform.idt());
        shapeRenderer.end();

        drawHud();

        if (pause.isOpen()
                && pause.render(shapeRenderer, batch, font, hudMatrix, viewport, enemies.isEmpty())) {
            game.setScreen(new OverworldScreen(game, state));
        }
    }

    private void drawHud() {
        shapeRenderer.setProjectionMatrix(hudMatrix);
        effects.renderThrottleHud(shapeRenderer, player);

        // radar: arena at a glance
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        radar.frame(shapeRenderer);
        if (defeatT < 0) {
            shapeRenderer.setColor(Color.GREEN);
            radar.dot(shapeRenderer, player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f, 2.5f);
        }
        shapeRenderer.setColor(0.95f, 0.25f, 0.2f, 1f);
        for (Enemy e : enemies) {
            radar.dot(shapeRenderer, e.centerX(), e.centerY(), 2f);
        }
        shapeRenderer.setColor(1f, 0.6f, 0.15f, 1f);
        for (Projectile p : projectiles) {
            if (p.rocket) radar.dot(shapeRenderer, p.x, p.y, 1.3f);
        }
        shapeRenderer.end();
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        radar.border(shapeRenderer);
        shapeRenderer.end();

        // hull + shield bars
        float barW = 120f;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(0.22f, 0.08f, 0.08f, 1f);
        shapeRenderer.rect(10, HUD_H - 86, barW, 7);
        shapeRenderer.setColor(0.85f, 0.3f, 0.25f, 1f);
        shapeRenderer.rect(10, HUD_H - 86, barW * state.hull / state.maxHull, 7);
        shapeRenderer.setColor(0.05f, 0.1f, 0.18f, 1f);
        shapeRenderer.rect(10, HUD_H - 98, barW, 7);
        shapeRenderer.setColor(0.35f, 0.7f, 1f, 1f);
        shapeRenderer.rect(10, HUD_H - 98, barW * state.shield / state.maxShield, 7);
        shapeRenderer.end();

        // weapon cards along the bottom centre
        float cardW = 78f;
        float cardH = 34f;
        float cardsX = (HUD_W - weapons.size * (cardW + 6f)) / 2f;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < weapons.size; i++) {
            Weapon w = weapons.get(i);
            float x = cardsX + i * (cardW + 6f);
            shapeRenderer.setColor(0.03f, 0.05f, 0.06f, 1f);
            shapeRenderer.rect(x, 8, cardW, cardH);
            // reload sweep along the card bottom
            float frac = w.type.reload <= 0 ? 1f : MathUtils.clamp(1f - w.cooldown / w.type.reload, 0f, 1f);
            shapeRenderer.setColor(frac >= 1f ? 0.25f : 0.6f, frac >= 1f ? 0.7f : 0.5f, 0.2f, 1f);
            shapeRenderer.rect(x + 2, 10, (cardW - 4) * frac, 3);
        }
        shapeRenderer.end();
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < weapons.size; i++) {
            float x = cardsX + i * (cardW + 6f);
            if (i == activeWeapon) shapeRenderer.setColor(Color.WHITE);
            else shapeRenderer.setColor(0.3f, 0.32f, 0.35f, 1f);
            shapeRenderer.rect(x, 8, cardW, cardH);
        }
        shapeRenderer.end();

        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.9f);
        for (int i = 0; i < weapons.size; i++) {
            Weapon w = weapons.get(i);
            float x = cardsX + i * (cardW + 6f);
            font.setColor(i == activeWeapon ? Color.WHITE : Color.GRAY);
            font.draw(batch, (i + 1) + " " + w.type.label, x + 4, 38);
            font.setColor(w.ammo == 0 ? Color.RED : Color.GRAY);
            font.draw(batch, w.ammo < 0 ? "\u221E" : String.valueOf(w.ammo), x + 4, 26);
        }
        font.setColor(Color.GRAY);
        font.draw(batch, "HULL", 136, HUD_H - 78);
        font.draw(batch, "SHLD", 136, HUD_H - 90);
        Fonts.scale(font, 1.4f);
        if (defeatT >= 0) {
            font.setColor(Color.RED);
            GlyphLayout lost = new GlyphLayout(font, "SHIP LOST");
            font.draw(batch, lost, (HUD_W - lost.width) / 2f, HUD_H / 2f + 40);
        }
        font.setColor(Color.YELLOW);
        font.draw(batch, "Credits: " + state.credits, 10, HUD_H - 10);
        font.setColor(enemies.size > 0 ? Color.RED : Color.GRAY);
        font.draw(batch, "Hostiles: " + enemies.size, 10, HUD_H - 35);
        font.setColor(controlled < 0 ? Color.GRAY : Color.ORANGE);
        font.draw(batch, "Controlling: " + (controlled < 0 ? "FIGHTER" : "ENEMY " + (controlled + 1)),
            10, HUD_H - 60);
        if (enemies.isEmpty()) {
            font.setColor(Color.GREEN);
            String msg = "HOSTILES ELIMINATED — press ESC to return";
            GlyphLayout gl = new GlyphLayout(font, msg);
            font.draw(batch, msg, (HUD_W - gl.width) / 2f, HUD_H / 2f);
        }
        font.setColor(Color.WHITE);
        font.draw(batch, (player.throttle * 10) + "%",
            HUD_W - SpaceEffects.THROTTLE_HUD_MARGIN - SpaceEffects.THROTTLE_BLOCK_W,
            SpaceEffects.THROTTLE_HUD_MARGIN
                + Player.THROTTLE_STEPS * (SpaceEffects.THROTTLE_BLOCK_H + SpaceEffects.THROTTLE_BLOCK_GAP) + 20);
        if (stormFlash > 0) {
            font.setColor(1f, 0.6f, 0.2f, 1f);
            GlyphLayout sr = new GlyphLayout(font, "SOLAR RADIATION");
            font.draw(batch, sr, (HUD_W - sr.width) / 2f, HUD_H - 70);
        }
        Dev.drawIndicator(batch, font, HUD_W, HUD_H);
        batch.end();

        if (stormFlash > 0) {
            Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
            shapeRenderer.setProjectionMatrix(hudMatrix);
            shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
            shapeRenderer.setColor(1f, 0.5f, 0.15f, 0.18f * (stormFlash / 0.7f));
            shapeRenderer.rect(0, 0, HUD_W, HUD_H);
            shapeRenderer.end();
        }

        controlsHelp.draw(shapeRenderer, batch, font, hudMatrix);
    }

    /** Returns true when the screen was switched and rendering must stop. */
    private boolean handleInput(float delta) {
        for (int k = 0; k < weapons.size && k < 9; k++) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1 + k)) activeWeapon = k;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            // cycle control: fighter -> enemy 0 -> enemy 1 -> ... -> fighter
            controlled = controlled + 1 >= enemies.size ? -1 : controlled + 1;
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            Vector2 target = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
            effects.setAutopilotTarget(target.x, target.y);
            game.sfx.playPing();
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT)) {
            effects.clearAutopilot();
        }
        controlsHelp.handleInput();
        return false;
    }

    /** The arena has solid walls: ships bounce off instead of wrapping through. */
    private static void bounceOffWalls(Player ship) {
        if (ship.x < 0) {
            ship.x = 0;
            ship.vx = Math.abs(ship.vx) * WALL_RESTITUTION;
        } else if (ship.x > ARENA_WIDTH - Player.WIDTH) {
            ship.x = ARENA_WIDTH - Player.WIDTH;
            ship.vx = -Math.abs(ship.vx) * WALL_RESTITUTION;
        }
        if (ship.y < 0) {
            ship.y = 0;
            ship.vy = Math.abs(ship.vy) * WALL_RESTITUTION;
        } else if (ship.y > ARENA_HEIGHT - Player.HEIGHT) {
            ship.y = ARENA_HEIGHT - Player.HEIGHT;
            ship.vy = -Math.abs(ship.vy) * WALL_RESTITUTION;
        }
    }

    /** Faint boundary so the wall reads before you hit it. */
    private void drawArenaBounds() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(0.35f, 0.12f, 0.1f, 1f);
        shapeRenderer.rect(2, 2, ARENA_WIDTH - 4, ARENA_HEIGHT - 4);
        shapeRenderer.end();
    }

    private void updateProjectiles(float delta) {
        for (int i = projectiles.size - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            p.update(delta);
            if (p.rocket && MathUtils.random() < 40f * delta) {
                addSparks(p.x + MathUtils.sinDeg(p.rotation) * 8f,
                    p.y - MathUtils.cosDeg(p.rotation) * 8f, 0, 0, 1);
            }
            if (p.isOutOfBounds(ARENA_WIDTH, ARENA_HEIGHT)) {
                projectiles.removeIndex(i);
                continue;
            }
            // the fighter takes hits from anything it didn't fire itself
            if (defeatT < 0 && p.shooter != player) {
                float pdx = p.x - (player.x + Player.WIDTH / 2f);
                float pdy = p.y - (player.y + Player.HEIGHT / 2f);
                if (pdx * pdx + pdy * pdy < SHIP_HIT_RADIUS * SHIP_HIT_RADIUS) {
                    projectiles.removeIndex(i);
                    damagePlayer(HIT_DAMAGE);
                    continue;
                }
            }
            for (int j = enemies.size - 1; j >= 0; j--) {
                Enemy e = enemies.get(j);
                if (p.shooter == e.body) continue; // no self-hits
                float dx = p.x - e.centerX();
                float dy = p.y - e.centerY();
                if (dx * dx + dy * dy < ENEMY_RADIUS * ENEMY_RADIUS) {
                    projectiles.removeIndex(i);
                    impact(p);
                    if (!p.rocket) damageEnemyAt(e, p.damage, p.x, p.y); // rockets damage via their splash
                    break;
                }
            }
        }
    }

    /** Shields soak damage first (with spill-over); 0 hull destroys the fighter and ends the run. */
    private void damagePlayer(float dmg) {
        shieldSince = 0f;
        if (state.shield > 0) {
            float soaked = Math.min(state.shield, dmg);
            state.shield -= soaked;
            dmg -= soaked;
            shieldFlash = 0.4f;
        }
        if (dmg > 0) {
            state.hull -= dmg;
            // the hit carries through to the deck: a random room starts leaking (#12)
            int room = MathUtils.random(7);
            state.roomIntegrity[room] = Math.max(0f, state.roomIntegrity[room] - 0.25f);
            game.sfx.playThud(0.4f);
            if (state.hull <= 0) {
                state.hull = 0;
                spawnDeathEffect(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f,
                    player.vx, player.vy);
                defeatT = 0f;
            }
        } else {
            game.sfx.playThud(0.2f);
        }
    }

    private Player controlledBody() {
        return controlled < 0 || controlled >= enemies.size ? player : enemies.get(controlled).body;
    }

    /** Damage routed by hit position: outer hits shear wings off before the core takes it. */
    private void damageEnemyAt(Enemy e, float dmg, float hx, float hy) {
        // hit position in ship-local coords
        float dx = hx - e.centerX();
        float dy = hy - e.centerY();
        float cos = MathUtils.cosDeg(-e.body.rotation);
        float sin = MathUtils.sinDeg(-e.body.rotation);
        float lx = dx * cos - dy * sin;
        if (lx < -9f && e.leftWingHp > 0) {
            e.leftWingHp -= dmg;
            if (e.leftWingHp <= 0) shearWing(e, true);
            else game.sfx.playThud(0.2f);
            return;
        }
        if (lx > 9f && e.rightWingHp > 0) {
            e.rightWingHp -= dmg;
            if (e.rightWingHp <= 0) shearWing(e, false);
            else game.sfx.playThud(0.2f);
            return;
        }
        damageEnemy(e, dmg);
    }

    /** The wing tears free as tumbling debris; the ship flies on, crippled. */
    private void shearWing(Enemy e, boolean left) {
        WingDebris wd = new WingDebris();
        wd.x = e.centerX();
        wd.y = e.centerY();
        wd.vx = e.body.vx + MathUtils.random(-30f, 30f);
        wd.vy = e.body.vy + MathUtils.random(-30f, 30f);
        wd.rotation = e.body.rotation;
        wd.spin = MathUtils.random(-160f, 160f);
        wd.left = left;
        wingDebris.add(wd);
        e.body.thrustMult *= 0.55f;
        e.body.turnMult *= 0.55f;
        addSparks(wd.x, wd.y, e.body.vx, e.body.vy, 10);
        toast(e, left ? "LEFT WING SHEARED" : "WING SHEARED");
        game.sfx.playThud(0.4f);
    }

    private void damageEnemy(Enemy e, float dmg) {
        e.hp -= dmg;
        if (e.hp <= 0) {
            killEnemy(enemies.indexOf(e, true));
            return;
        }
        game.sfx.playThud(0.2f);
        // critical hits: fires and knocked-out subsystems, announced by a tooltip
        if (MathUtils.random() < 0.14f) {
            int roll = MathUtils.random(2);
            if (roll == 0 && !e.onFire) {
                e.onFire = true;
                toast(e, "FIRE");
            } else if (roll == 1 && !e.engineOut) {
                e.engineOut = true;
                e.body.thrustMult = 0f;
                toast(e, "ENGINE OUT");
            } else if (!e.helmDamaged) {
                e.helmDamaged = true;
                e.body.turnMult = 0.3f;
                toast(e, "HELM DAMAGED");
            }
        }
    }

    private void toast(Enemy e, String text) {
        CritToast ct = new CritToast();
        ct.e = e;
        ct.text = text;
        critToasts.add(ct);
    }

    /** Impact feedback; rockets blast and splash everything nearby. */
    private void impact(Projectile p) {
        if (p.rocket) {
            addBlast(p.x, p.y, 0f, 220f, 42f);
            addSparks(p.x, p.y, 0, 0, 14);
            game.sfx.playThud(0.45f);
            for (int j = enemies.size - 1; j >= 0; j--) {
                if (j >= enemies.size) continue;
                Enemy e = enemies.get(j);
                float d = Vector2.dst(p.x, p.y, e.centerX(), e.centerY());
                if (d < 70f) damageEnemy(e, p.damage * (1f - d / 90f));
            }
            float pd = Vector2.dst(p.x, p.y, player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f);
            if (defeatT < 0 && pd < 70f && p.shooter != player) damagePlayer(p.damage * (1f - pd / 90f));
        } else {
            addSparks(p.x, p.y, 0, 0, 2 + (int) (p.damage * 0.3f));
        }
    }

    /** Per-frame weapon step: cooldowns, lock-on, and firing the active weapon while SPACE is held. */
    private void fireWeapons(float delta) {
        for (Weapon w : weapons) w.update(delta);
        updateLockTarget();
        if (defeatT >= 0 || weapons.isEmpty()) return;
        Weapon w = weapons.get(activeWeapon);
        Player body = controlledBody();
        boolean held = Gdx.input.isKeyPressed(Input.Keys.SPACE);
        // turreted mounts track the cursor within their arc; fixed mounts stay on the nose
        float fireRotation = body.rotation;
        if (w.type.turretArc > 0) {
            Vector2 cursor = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
            float cdx = cursor.x - (body.x + Player.WIDTH / 2f);
            float cdy = cursor.y - (body.y + Player.HEIGHT / 2f);
            float desired = MathUtils.atan2(-cdx, cdy) * MathUtils.radiansToDegrees;
            float offset = ((desired - body.rotation) % 360f + 540f) % 360f - 180f;
            offset = MathUtils.clamp(offset, -w.type.turretArc, w.type.turretArc);
            float slew = 240f * delta; // mount turn-rate limit
            w.turret += MathUtils.clamp(offset - w.turret, -slew, slew);
            w.turret = MathUtils.clamp(w.turret, -w.type.turretArc, w.type.turretArc);
            fireRotation = body.rotation + w.turret;
        }
        float fx = -MathUtils.sinDeg(fireRotation);
        float fy = MathUtils.cosDeg(fireRotation);
        float nx = body.x + Player.WIDTH / 2f + fx * 20f;
        float ny = body.y + Player.HEIGHT / 2f + fy * 20f;
        switch (w.type) {
            case BEAM_LASER:
                if (held) fireBeam(body, nx, ny, fireRotation, w.type.damage * delta, true);
                break;
            case BURST_LASER:
                if (held && w.ready()) {
                    w.fire();
                    w.burstLeft = 3;
                }
                if (w.burstLeft > 0 && w.burstTimer <= 0) {
                    w.burstLeft--;
                    w.burstTimer = 0.07f;
                    fireBeam(body, nx, ny, fireRotation + MathUtils.random(-2f, 2f), w.type.damage, false);
                    game.sfx.playLaser();
                }
                break;
            default:
                if (held && w.ready()) {
                    w.fire();
                    // gunnery crew and weapons power shorten the cycle
                    w.cooldown = w.type.reload / ((1f + 0.08f * state.roomStats[4])
                        * (0.7f + 0.15f * state.power[GameState.PWR_WEAPONS]));
                    Projectile p = new Projectile(nx, ny, fireRotation, body,
                        w.type.speed, w.type.damage,
                        w.type.isRocket() ? 260f : 0f,
                        w.type == Weapon.Type.HOMING_ROCKET ? 160f : 0f,
                        w.type.isRocket());
                    if (w.type == Weapon.Type.HOMING_ROCKET && lockTarget != null) p.target = lockTarget.body;
                    projectiles.add(p);
                    // muzzle flash scaled by caliber
                    addBlast(nx, ny, 0f, 150f, 5f + w.type.damage * 0.35f);
                    if (w.type.isRocket()) game.sfx.playRocket();
                    else game.sfx.playCannon(w.type == Weapon.Type.LIGHT_CANNON ? 0
                        : w.type == Weapon.Type.MEDIUM_CANNON ? 1 : 2);
                }
        }
    }

    /** Hitscan laser: damages the first enemy along the ray and leaves a beam flash. */
    private void fireBeam(Player body, float ox, float oy, float rotation, float damage, boolean continuous) {
        float fx = -MathUtils.sinDeg(rotation);
        float fy = MathUtils.cosDeg(rotation);
        float maxLen = 520f;
        Enemy hit = null;
        float hitT = maxLen;
        for (Enemy e : enemies) {
            if (e.body == body) continue;
            float rx = e.centerX() - ox;
            float ry = e.centerY() - oy;
            float t = rx * fx + ry * fy;
            if (t < 0 || t > hitT) continue;
            float px = ox + fx * t - e.centerX();
            float py = oy + fy * t - e.centerY();
            if (px * px + py * py < ENEMY_RADIUS * ENEMY_RADIUS) {
                hit = e;
                hitT = t;
            }
        }
        float ex = ox + fx * hitT;
        float ey = oy + fy * hitT;
        beams.add(new float[]{ox, oy, ex, ey, continuous ? 0.05f : 0.14f, continuous ? 0.05f : 0.14f});
        if (hit != null) {
            addSparks(ex, ey, 0, 0, continuous ? 1 : 4);
            damageEnemyAt(hit, damage, ex, ey);
        }
    }

    /** Homing rockets acquire the nearest enemy in a forward cone. */
    private void updateLockTarget() {
        lockTarget = null;
        if (weapons.isEmpty() || weapons.get(activeWeapon).type != Weapon.Type.HOMING_ROCKET) return;
        Player body = controlledBody();
        float fx = -MathUtils.sinDeg(body.rotation);
        float fy = MathUtils.cosDeg(body.rotation);
        float best = 700f;
        for (Enemy e : enemies) {
            if (e.body == body) continue;
            float dx = e.centerX() - (body.x + Player.WIDTH / 2f);
            float dy = e.centerY() - (body.y + Player.HEIGHT / 2f);
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > best || d < 1f) continue;
            float dot = (dx * fx + dy * fy) / d;
            if (dot < 0.64f) continue; // ~50 degree cone
            best = d;
            lockTarget = e;
        }
    }

    private void killEnemy(int index) {
        for (int i = critToasts.size - 1; i >= 0; i--) {
            if (critToasts.get(i).e == enemies.get(index)) critToasts.removeIndex(i);
        }
        for (Projectile p : projectiles) {
            if (p.target == enemies.get(index).body) p.target = null; // lock dies with the ship
        }
        if (controlled == index) controlled = -1;
        else if (controlled > index) controlled--;
        Enemy e = enemies.get(index);
        spawnDeathEffect(e.centerX(), e.centerY(), e.body.vx, e.body.vy);
        enemies.removeIndex(index);
        state.credits += CREDITS_PER_KILL;
        state.hostilesDestroyed++;
        game.sfx.playCatch();
        if (enemies.isEmpty()) {
            // combat resolved: all hostiles destroyed
            state.map.getCurrentNode().completed = true;
            state.instancesCompleted++;
        }
    }

    /** Picks a random destruction from the pool, parameters jittered per kill. */
    private void spawnDeathEffect(float x, float y, float vx, float vy) {
        DeathKind kind = DeathKind.values()[MathUtils.random(DeathKind.values().length - 1)];
        switch (kind) {
            case FIREBALL:
                addBlast(x, y, 0f, MathUtils.random(150f, 220f), MathUtils.random(40f, 65f));
                addSparks(x, y, vx, vy, MathUtils.random(16, 24));
                break;
            case BREAKUP:
                addShards(x, y, vx, vy, MathUtils.random(4, 6));
                addSparks(x, y, vx, vy, MathUtils.random(5, 9));
                break;
            case CHAIN: {
                int pops = MathUtils.random(3, 4);
                for (int i = 0; i < pops; i++) {
                    addBlast(x + MathUtils.random(-14f, 14f), y + MathUtils.random(-14f, 14f),
                        i * MathUtils.random(0.08f, 0.14f), MathUtils.random(90f, 130f),
                        MathUtils.random(12f, 20f));
                }
                addBlast(x, y, pops * 0.12f + 0.1f, MathUtils.random(170f, 230f), MathUtils.random(45f, 60f));
                addSparks(x, y, vx, vy, MathUtils.random(10, 16));
                break;
            }
            case OVERLOAD:
                addBlast(x, y, 0f, MathUtils.random(18f, 26f), MathUtils.random(12f, 16f)); // slow swell
                addBlast(x, y, MathUtils.random(0.4f, 0.6f), MathUtils.random(260f, 340f),
                    MathUtils.random(55f, 75f)); // then the sharp blast
                addSparks(x, y, vx, vy, MathUtils.random(12, 18));
                break;
        }
    }

    private void addBlast(float x, float y, float delay, float speed, float maxR) {
        Blast b = new Blast();
        b.x = x;
        b.y = y;
        b.age = -delay;
        b.speed = speed;
        b.maxR = maxR;
        blasts.add(b);
    }

    private void addSparks(float x, float y, float vx, float vy, int count) {
        for (int i = 0; i < count; i++) {
            Spark s = new Spark();
            s.x = x;
            s.y = y;
            float angle = MathUtils.random(360f);
            float speed = MathUtils.random(40f, 160f);
            s.vx = vx * 0.4f + MathUtils.cosDeg(angle) * speed;
            s.vy = vy * 0.4f + MathUtils.sinDeg(angle) * speed;
            s.maxLife = s.life = MathUtils.random(0.35f, 0.9f);
            sparks.add(s);
        }
    }

    private void addShards(float x, float y, float vx, float vy, int count) {
        for (int i = 0; i < count; i++) {
            Shard f = new Shard();
            f.x = x + MathUtils.random(-8f, 8f);
            f.y = y + MathUtils.random(-8f, 8f);
            float angle = MathUtils.random(360f);
            float speed = MathUtils.random(20f, 70f);
            f.vx = vx * 0.6f + MathUtils.cosDeg(angle) * speed;
            f.vy = vy * 0.6f + MathUtils.sinDeg(angle) * speed;
            f.rotation = MathUtils.random(360f);
            f.spin = MathUtils.random(-240f, 240f);
            f.len = MathUtils.random(6f, 16f);
            f.maxLife = f.life = MathUtils.random(1f, 1.7f);
            shards.add(f);
        }
    }

    private void updateEffects(float delta) {
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
        for (int i = shards.size - 1; i >= 0; i--) {
            Shard f = shards.get(i);
            f.life -= delta;
            if (f.life <= 0) {
                shards.removeIndex(i);
                continue;
            }
            f.x += f.vx * delta;
            f.y += f.vy * delta;
            f.rotation += f.spin * delta;
        }
        for (int i = blasts.size - 1; i >= 0; i--) {
            Blast b = blasts.get(i);
            b.age += delta;
            if (b.age > 0 && b.age * b.speed > b.maxR) blasts.removeIndex(i);
        }
        for (int i = beams.size - 1; i >= 0; i--) {
            float[] beam = beams.get(i);
            beam[4] -= delta;
            if (beam[4] <= 0) beams.removeIndex(i);
        }
    }

    private void drawEffects() {
        drawLockMarker();
        if (sparks.size == 0 && shards.size == 0 && blasts.size == 0 && beams.size == 0) return;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        // laser flashes: dim glow + bright core
        for (float[] beam : beams) {
            float a = beam[4] / beam[5];
            shapeRenderer.setColor(0.5f * a, 0.1f * a, 0.6f * a, 1f);
            shapeRenderer.line(beam[0], beam[1] + 1.5f, beam[2], beam[3] + 1.5f);
            shapeRenderer.line(beam[0], beam[1] - 1.5f, beam[2], beam[3] - 1.5f);
            shapeRenderer.setColor(a, 0.5f + 0.5f * a, a, 1f);
            shapeRenderer.line(beam[0], beam[1], beam[2], beam[3]);
        }
        for (Spark s : sparks) {
            float f = s.life / s.maxLife;
            shapeRenderer.setColor(1f, 0.65f * f + 0.1f, 0.15f * f, 1f);
            shapeRenderer.line(s.x, s.y, s.x - s.vx * 0.05f, s.y - s.vy * 0.05f);
        }
        for (Shard f : shards) {
            float a = f.life / f.maxLife;
            shapeRenderer.setColor(0.9f * a, 0.3f * a, 0.2f * a, 1f);
            float cos = MathUtils.cosDeg(f.rotation);
            float sin = MathUtils.sinDeg(f.rotation);
            shapeRenderer.line(f.x - cos * f.len, f.y - sin * f.len, f.x + cos * f.len, f.y + sin * f.len);
        }
        for (Blast b : blasts) {
            if (b.age <= 0) continue;
            float r = b.age * b.speed;
            float a = 1f - r / b.maxR;
            shapeRenderer.setColor(1f, 0.75f * a + 0.15f, 0.2f * a, 1f);
            shapeRenderer.circle(b.x, b.y, r, 32);
        }
        shapeRenderer.end();
    }

    /** Seeded scorch nicks accumulate as hp drops, so damage is visible and stable. */
    private void drawDamageMarks(Enemy e) {
        float frac = e.hp / ENEMY_HP;
        if (frac >= 0.98f) return;
        java.util.Random r = new java.util.Random(e.seed);
        int marks = Math.min(6, 1 + (int) ((1f - frac) * 6f));
        shapeRenderer.setColor(0.45f, 0.1f, 0.08f, 1f);
        for (int m = 0; m < marks; m++) {
            float mx = -18f + r.nextFloat() * 36f;
            float my = -10f + r.nextFloat() * 24f;
            float ang = r.nextFloat() * 360f;
            float len = 3f + r.nextFloat() * 4f;
            shapeRenderer.line(mx, my, mx + MathUtils.cosDeg(ang) * len, my + MathUtils.sinDeg(ang) * len);
        }
        // status glyphs beside the craft (drawn in local space just off the wing)
        float gx = 26f;
        if (e.onFire) {
            shapeRenderer.setColor(1f, 0.55f, 0.15f, 1f);
            shapeRenderer.line(gx, 0, gx + 2, 6);
            shapeRenderer.line(gx + 2, 6, gx + 4, 1);
            shapeRenderer.line(gx + 4, 1, gx + 6, 5);
            gx += 10f;
        }
        if (e.engineOut) {
            shapeRenderer.setColor(0.9f, 0.3f, 0.25f, 1f);
            shapeRenderer.line(gx, 0, gx + 5, 5);
            shapeRenderer.line(gx, 5, gx + 5, 0);
            gx += 10f;
        }
        if (e.helmDamaged) {
            shapeRenderer.setColor(0.9f, 0.7f, 0.2f, 1f);
            shapeRenderer.line(gx, 2, gx + 2, 4);
            shapeRenderer.line(gx + 2, 4, gx + 4, 2);
            shapeRenderer.line(gx + 4, 2, gx + 6, 4);
        }
    }

    /** Crit tooltips floating beside the affected craft. */
    private void drawCritToasts() {
        if (critToasts.size == 0) return;
        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();
        Fonts.scale(font, 1f);
        for (CritToast ct : critToasts) {
            float a = Math.min(1f, ct.t / 0.6f);
            font.setColor(0f, 0f, 0f, a);
            font.draw(batch, ct.text, ct.e.centerX() + 25f, ct.e.centerY() + 25f - 1);
            font.setColor(1f, 0.6f, 0.2f, a);
            font.draw(batch, ct.text, ct.e.centerX() + 24f, ct.e.centerY() + 25f);
        }
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /** Warning diamonds over incoming rounds on a collision course with the fighter. */
    private void drawThreatMarkers() {
        if (defeatT >= 0) return;
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        boolean began = false;
        for (Projectile p : projectiles) {
            if (p.shooter == player) continue;
            float rx = p.x - cx;
            float ry = p.y - cy;
            float vx = p.velX() - player.vx;
            float vy = p.velY() - player.vy;
            float v2 = vx * vx + vy * vy;
            if (v2 < 1f) continue;
            float tca = -(rx * vx + ry * vy) / v2;
            if (tca < 0 || tca > 1.2f) continue;
            float qx = rx + vx * tca;
            float qy = ry + vy * tca;
            if (qx * qx + qy * qy > 36f * 36f) continue;
            if (!began) {
                shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
                began = true;
            }
            float pulse = 0.6f + 0.4f * MathUtils.sin(shieldSince * 20f + p.x);
            shapeRenderer.setColor(1f * pulse, 0.15f, 0.1f, 1f);
            float r = 7f + p.damage * 0.15f;
            shapeRenderer.line(p.x - r, p.y, p.x, p.y + r);
            shapeRenderer.line(p.x, p.y + r, p.x + r, p.y);
            shapeRenderer.line(p.x + r, p.y, p.x, p.y - r);
            shapeRenderer.line(p.x, p.y - r, p.x - r, p.y);
        }
        if (began) shapeRenderer.end();
    }

    /** Lock-on brackets around the homing target, so auto-aim shows its hand. */
    private void drawLockMarker() {
        if (lockTarget == null) return;
        float cx = lockTarget.centerX();
        float cy = lockTarget.centerY();
        float r = ENEMY_RADIUS + 8f;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(1f, 0.65f, 0.15f, 1f);
        for (int q = 0; q < 4; q++) {
            float sx = (q % 2 == 0) ? -1 : 1;
            float sy = (q < 2) ? -1 : 1;
            shapeRenderer.line(cx + sx * r, cy + sy * r, cx + sx * (r - 7), cy + sy * r);
            shapeRenderer.line(cx + sx * r, cy + sy * r, cx + sx * r, cy + sy * (r - 7));
        }
        shapeRenderer.end();
    }

    /** Hostile wireframes, drawn in red; same hull as the player until variants exist. */
    private void drawEnemies() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < enemies.size; i++) {
            Enemy e = enemies.get(i);
            shapeRenderer.setColor(i == controlled ? Color.ORANGE : new Color(0.95f, 0.25f, 0.2f, 1f));
            transform.setToTranslation(e.centerX(), e.centerY(), 0).rotate(0, 0, 1, e.body.rotation);
            shapeRenderer.setTransformMatrix(transform);
            ShipRenderer.drawB2Core(shapeRenderer);
            if (e.leftWingHp > 0) ShipRenderer.drawB2Wing(shapeRenderer, true);
            if (e.rightWingHp > 0) ShipRenderer.drawB2Wing(shapeRenderer, false);
            drawDamageMarks(e);
            if (e.body.thrustLevel > 0.02f) {
                float flick = 0.8f + 0.35f * MathUtils.random();
                shapeRenderer.setColor(1f, 0.5f, 0.12f, 1f);
                ShipRenderer.drawExhaust(shapeRenderer, e.body.thrustLevel * flick);
                shapeRenderer.setColor(1f, 0.85f, 0.4f, 1f);
                ShipRenderer.drawExhaust(shapeRenderer, e.body.thrustLevel * flick * 0.55f);
            }
        }
        shapeRenderer.setTransformMatrix(transform.idt());
        shapeRenderer.end();
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}

    @Override
    public void hide() {
        dispose();
    }

    @Override
    public void dispose() {
        game.sfx.stopThruster();
        shapeRenderer.dispose();
        batch.dispose();
    }
}
