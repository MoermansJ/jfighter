package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

public class GameScreen implements Screen {
    private static final float WORLD_WIDTH = 640f;
    private static final float WORLD_HEIGHT = 480f;

    private ShapeRenderer shapeRenderer;
    private FitViewport viewport;
    private Player player;
    private final Array<Projectile> projectiles = new Array<>();
    private final Matrix4 playerTransform = new Matrix4();

    @Override
    public void show() {
        shapeRenderer = new ShapeRenderer();
        viewport = new FitViewport(WORLD_WIDTH, WORLD_HEIGHT);
        player = new Player(100f, (WORLD_HEIGHT - Player.HEIGHT) / 2f);
    }

    @Override
    public void render(float delta) {
        handleInput(delta);
        clampPlayer();
        updateProjectiles(delta);

        ScreenUtils.clear(0, 0, 0, 1f);
        viewport.apply();

        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);

        // draw wireframe player with rotation transform
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        playerTransform.setToTranslation(cx, cy, 0).rotate(0, 0, 1, player.rotation);
        shapeRenderer.setTransformMatrix(playerTransform);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(Color.GREEN);
        drawWireframeFighter();
        shapeRenderer.end();

        // draw projectiles in world space (reset transform)
        shapeRenderer.setTransformMatrix(new Matrix4());
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(Color.GREEN);
        for (Projectile p : projectiles) {
            shapeRenderer.circle(p.x, p.y, Projectile.RADIUS);
        }
        shapeRenderer.end();
    }

    private void drawWireframeFighter() {
        // B-2 Spirit top-down view; +Y = nose/forward

        // leading edges (swept back ~33 degrees)
        shapeRenderer.line(0, 20, 28, 0);
        shapeRenderer.line(0, 20, -28, 0);

        // trailing edge — characteristic W notch (right side)
        shapeRenderer.line(28, 0, 22, -12);
        shapeRenderer.line(22, -12, 14, -4);
        shapeRenderer.line(14, -4, 6, -16);
        shapeRenderer.line(6, -16, 0, -10);

        // trailing edge — W notch (left side, mirrored)
        shapeRenderer.line(-28, 0, -22, -12);
        shapeRenderer.line(-22, -12, -14, -4);
        shapeRenderer.line(-14, -4, -6, -16);
        shapeRenderer.line(-6, -16, 0, -10);

        // centerline spine
        shapeRenderer.line(0, 18, 0, -10);

        // cockpit canopy
        shapeRenderer.circle(0, 14, 3, 8);

        // engine exhaust slots (2 per side on the trailing section)
        shapeRenderer.line(-14, -2, -10, -10);
        shapeRenderer.line(-10, -2, -6, -10);
        shapeRenderer.line(6, -10, 10, -2);
        shapeRenderer.line(10, -10, 14, -2);
    }

    private void handleInput(float delta) {
        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP)) {
            player.moveForward(delta);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            player.moveBackward(delta);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            player.rotateLeft(delta);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            player.rotateRight(delta);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            projectiles.add(new Projectile(
                player.x + Player.WIDTH / 2f,
                player.y + Player.HEIGHT / 2f,
                player.rotation));
        }
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

    private void clampPlayer() {
        player.x = Math.max(0, Math.min(player.x, WORLD_WIDTH - Player.WIDTH));
        player.y = Math.max(0, Math.min(player.y, WORLD_HEIGHT - Player.HEIGHT));
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
    }
}
