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
    // combat arena matches the salvage arena, so both instances read as the same universe
    // (same starfield/debris density and dressing via SpaceEffects area scaling)
    private static final float ARENA_WIDTH = 1440f;
    private static final float ARENA_HEIGHT = 810f;
    // HUD renders through its own 960x540 ortho matrix, unaffected by camera zoom
    private static final float HUD_W = JFighter.WORLD_WIDTH;
    private static final float HUD_H = JFighter.WORLD_HEIGHT;

    // solid arena walls: no wrapping or clipping through the border
    private static final float WALL_RESTITUTION = 0.5f;

    // enemy ships: idle drifters for now — targets to fly against and shoot at
    private static final float ENEMY_RADIUS = 18f;
    private static final int ENEMY_HP = 3;
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
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 hudMatrix = new Matrix4(); // HUD ignores camera zoom
    private final ControlsHelp controlsHelp = new ControlsHelp(new String[][]{
        {"SPACE", "fire"},
        {"UP/DOWN", "throttle"},
        {"LEFT/RIGHT", "turn"},
        {"LMB", "set autopilot"},
        {"RMB", "cancel autopilot"},
        {"TAB", "switch controlled ship (dev)"},
        {"ESC", "leave instance"},
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

    private final Array<Spark> sparks = new Array<>();
    private final Array<Shard> shards = new Array<>();
    private final Array<Blast> blasts = new Array<>();

    private static class Enemy {
        final Player body; // reuses ship physics: drag keeps them slowly adrift
        int hp = ENEMY_HP;

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
        viewport = new FitViewport(ARENA_WIDTH, ARENA_HEIGHT);
        player = new Player(120f, (ARENA_HEIGHT - Player.HEIGHT) / 2f);
        effects = new SpaceEffects(ARENA_WIDTH, ARENA_HEIGHT);
        hudMatrix.setToOrtho2D(0, 0, HUD_W, HUD_H);
        spawnEnemies();
    }

    private void spawnEnemies() {
        int count = MathUtils.random(2, 3);
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
                game.setScreen(new SummaryScreen(game, state, false));
                return;
            }
        }
        // stop rendering once the screen switches: hide() disposed our resources
        if (defeatT < 0 && handleInput(delta)) return;
        if (defeatT < 0) effects.handleFlightInput(controlledBody(), delta);
        shieldSince += delta;
        if (shieldSince >= SHIELD_RECHARGE_DELAY && state.shield < state.maxShield) {
            state.shield = Math.min(state.maxShield, state.shield + SHIELD_RECHARGE_RATE * delta);
        }
        if (shieldFlash > 0) shieldFlash -= delta;
        player.updatePosition(delta);
        bounceOffWalls(player);
        for (Enemy e : enemies) {
            e.body.updatePosition(delta);
            bounceOffWalls(e.body);
        }
        effects.update(player, delta);
        effects.spawnExhaust(player, delta);
        for (Enemy e : enemies) effects.spawnExhaust(e.body, delta);
        updateProjectiles(delta);
        updateEffects(delta);

        ScreenUtils.clear(0, 0, 0, 1f);
        viewport.apply();
        effects.applyZoom(viewport, player, delta);
        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);

        effects.renderBackground(shapeRenderer);
        drawArenaBounds();
        effects.renderAutopilot(shapeRenderer);
        drawEnemies();
        drawEffects();
        if (defeatT < 0) {
            effects.renderShip(shapeRenderer, player);
            if (shieldFlash > 0) {
                shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
                float a = shieldFlash / 0.4f;
                shapeRenderer.setColor(0.4f * a, 0.8f * a, a, 1f);
                shapeRenderer.circle(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f,
                    26f + 6f * (1f - a), 24);
                shapeRenderer.end();
            }
        }

        // projectile capsules (filled mode, per-projectile transform)
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(Color.GREEN);
        for (Projectile p : projectiles) {
            transform.setToTranslation(p.x, p.y, 0).rotate(0, 0, 1, p.rotation);
            shapeRenderer.setTransformMatrix(transform);
            shapeRenderer.rect(-2, -7, 4, 14);
            shapeRenderer.circle(0,  7, 2, 8);
            shapeRenderer.circle(0, -7, 2, 8);
        }
        shapeRenderer.end();

        drawHud();
    }

    private void drawHud() {
        shapeRenderer.setProjectionMatrix(hudMatrix);
        effects.renderThrottleHud(shapeRenderer, player);

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

        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.9f);
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
        Dev.drawIndicator(batch, font, HUD_W, HUD_H);
        batch.end();

        controlsHelp.draw(shapeRenderer, batch, font, hudMatrix);
    }

    /** Returns true when the screen was switched and rendering must stop. */
    private boolean handleInput(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.setScreen(new OverworldScreen(game, state));
            return true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            Player shooter = controlledBody();
            projectiles.add(new Projectile(
                shooter.x + Player.WIDTH / 2f,
                shooter.y + Player.HEIGHT / 2f,
                shooter.rotation, shooter));
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            // cycle control: fighter -> enemy 0 -> enemy 1 -> ... -> fighter
            controlled = controlled + 1 >= enemies.size ? -1 : controlled + 1;
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            Vector2 target = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
            effects.setAutopilotTarget(target.x, target.y);
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
                    if (--e.hp <= 0) {
                        killEnemy(j);
                    } else {
                        game.sfx.playThud(0.25f);
                    }
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

    private void killEnemy(int index) {
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
    }

    private void drawEffects() {
        if (sparks.size == 0 && shards.size == 0 && blasts.size == 0) return;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
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

    /** Hostile wireframes, drawn in red; same hull as the player until variants exist. */
    private void drawEnemies() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < enemies.size; i++) {
            Enemy e = enemies.get(i);
            shapeRenderer.setColor(i == controlled ? Color.ORANGE : new Color(0.95f, 0.25f, 0.2f, 1f));
            transform.setToTranslation(e.centerX(), e.centerY(), 0).rotate(0, 0, 1, e.body.rotation);
            shapeRenderer.setTransformMatrix(transform);
            ShipRenderer.drawB2(shapeRenderer);
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
        shapeRenderer.dispose();
        batch.dispose();
    }
}
