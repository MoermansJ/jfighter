package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

public class GameScreen implements Screen {
    private static final float WORLD_WIDTH = JFighter.WORLD_WIDTH;
    private static final float WORLD_HEIGHT = JFighter.WORLD_HEIGHT;

    private final JFighter game;
    private final GameState state;

    private ShapeRenderer shapeRenderer;
    private SpriteBatch batch;
    private BitmapFont font;
    private FitViewport viewport;
    private Player player;
    private SpaceEffects effects;
    private final Array<Projectile> projectiles = new Array<>();
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 hudMatrix = new Matrix4(); // HUD ignores camera zoom

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
        viewport = new FitViewport(WORLD_WIDTH, WORLD_HEIGHT);
        player = new Player(100f, (WORLD_HEIGHT - Player.HEIGHT) / 2f);
        effects = new SpaceEffects(WORLD_WIDTH, WORLD_HEIGHT);
        hudMatrix.setToOrtho2D(0, 0, WORLD_WIDTH, WORLD_HEIGHT);
    }

    @Override
    public void render(float delta) {
        // stop rendering once the screen switches: hide() disposed our resources
        if (handleInput(delta)) return;
        effects.handleFlightInput(player, delta);
        player.updatePosition(delta);
        player.wrapAround(WORLD_WIDTH, WORLD_HEIGHT);
        effects.update(player, delta);
        updateProjectiles(delta);

        ScreenUtils.clear(0, 0, 0, 1f);
        viewport.apply();
        effects.applyZoom(viewport, player, delta);
        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);

        effects.renderBackground(shapeRenderer);
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
        font.draw(batch, "Credits: " + state.credits, 10, WORLD_HEIGHT - 10);
        font.setColor(Color.WHITE);
        font.draw(batch, (player.throttle * 10) + "%",
            WORLD_WIDTH - SpaceEffects.THROTTLE_HUD_MARGIN - SpaceEffects.THROTTLE_BLOCK_W,
            SpaceEffects.THROTTLE_HUD_MARGIN
                + Player.THROTTLE_STEPS * (SpaceEffects.THROTTLE_BLOCK_H + SpaceEffects.THROTTLE_BLOCK_GAP) + 20);
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
        return false;
    }

    private void updateProjectiles(float delta) {
        for (int i = projectiles.size - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            p.update(delta);
            if (p.isOutOfBounds(WORLD_WIDTH, WORLD_HEIGHT)) {
                projectiles.removeIndex(i);
            }
        }
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
