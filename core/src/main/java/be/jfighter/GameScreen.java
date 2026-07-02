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
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 hudMatrix = new Matrix4(); // HUD ignores camera zoom

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
        font = new BitmapFont();
        font.getData().setScale(1.4f);
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
        // stop rendering once the screen switches: hide() disposed our resources
        if (handleInput(delta)) return;
        effects.handleFlightInput(player, delta);
        player.updatePosition(delta);
        bounceOffWalls(player);
        for (Enemy e : enemies) {
            e.body.updatePosition(delta);
            bounceOffWalls(e.body);
        }
        effects.update(player, delta);
        updateProjectiles(delta);

        ScreenUtils.clear(0, 0, 0, 1f);
        viewport.apply();
        effects.applyZoom(viewport, player, delta);
        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);

        effects.renderBackground(shapeRenderer);
        drawArenaBounds();
        effects.renderAutopilot(shapeRenderer);
        drawEnemies();
        effects.renderShip(shapeRenderer, player);

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

        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        font.setColor(Color.YELLOW);
        font.draw(batch, "Credits: " + state.credits, 10, HUD_H - 10);
        font.setColor(enemies.size > 0 ? Color.RED : Color.GRAY);
        font.draw(batch, "Hostiles: " + enemies.size, 10, HUD_H - 35);
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
    }

    /** Returns true when the screen was switched and rendering must stop. */
    private boolean handleInput(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.setScreen(new OverworldScreen(game, state));
            return true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            projectiles.add(new Projectile(
                player.x + Player.WIDTH / 2f,
                player.y + Player.HEIGHT / 2f,
                player.rotation));
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            Vector2 target = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
            effects.setAutopilotTarget(target.x, target.y);
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT)) {
            effects.clearAutopilot();
        }
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
            for (int j = enemies.size - 1; j >= 0; j--) {
                Enemy e = enemies.get(j);
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

    private void killEnemy(int index) {
        enemies.removeIndex(index);
        state.credits += CREDITS_PER_KILL;
        game.sfx.playCatch();
        if (enemies.isEmpty()) {
            // combat resolved: all hostiles destroyed
            state.map.getCurrentNode().completed = true;
        }
    }

    /** Hostile wireframes, drawn in red; same hull as the player until variants exist. */
    private void drawEnemies() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(0.95f, 0.25f, 0.2f, 1f);
        for (Enemy e : enemies) {
            transform.setToTranslation(e.centerX(), e.centerY(), 0).rotate(0, 0, 1, e.body.rotation);
            shapeRenderer.setTransformMatrix(transform);
            ShipRenderer.drawB2(shapeRenderer);
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
        font.dispose();
    }
}
