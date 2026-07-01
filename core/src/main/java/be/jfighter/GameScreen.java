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
        // all coords are local to the player center; +Y = forward (up)
        // head
        shapeRenderer.circle(0, 22, 7, 12);
        // neck + spine
        shapeRenderer.line(0, 15, 0, -6);
        // shoulders
        shapeRenderer.line(-12, 12, 12, 12);
        // left arm: upper arm then forearm raised in guard
        shapeRenderer.line(-12, 12, -18, 4);
        shapeRenderer.line(-18, 4, -14, 14);
        // right arm: upper arm then forearm punching forward
        shapeRenderer.line(12, 12, 18, 4);
        shapeRenderer.line(18, 4, 16, 14);
        // hips
        shapeRenderer.line(-8, -6, 8, -6);
        // left leg
        shapeRenderer.line(-8, -6, -10, -18);
        shapeRenderer.line(-10, -18, -8, -28);
        // right leg
        shapeRenderer.line(8, -6, 10, -18);
        shapeRenderer.line(10, -18, 8, -28);
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
