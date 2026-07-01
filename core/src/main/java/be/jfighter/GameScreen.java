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
    private final Matrix4 transform = new Matrix4();

    @Override
    public void show() {
        shapeRenderer = new ShapeRenderer();
        viewport = new FitViewport(WORLD_WIDTH, WORLD_HEIGHT);
        player = new Player(100f, (WORLD_HEIGHT - Player.HEIGHT) / 2f);
    }

    @Override
    public void render(float delta) {
        handleInput(delta);
        player.updatePosition(delta);
        clampPlayer();
        updateProjectiles(delta);

        ScreenUtils.clear(0, 0, 0, 1f);
        viewport.apply();
        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);

        // player wireframe (line mode, rotated transform)
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        transform.setToTranslation(cx, cy, 0).rotate(0, 0, 1, player.rotation);
        shapeRenderer.setTransformMatrix(transform);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(Color.GREEN);
        drawB2();
        if (player.thrustLevel > 0.02f) drawExhaust();
        shapeRenderer.end();

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
    }

    private void drawB2() {
        // leading edges
        shapeRenderer.line(0, 20, 28, 0);
        shapeRenderer.line(0, 20, -28, 0);
        // trailing edge W (right)
        shapeRenderer.line(28, 0, 22, -12);
        shapeRenderer.line(22, -12, 14, -4);
        shapeRenderer.line(14, -4, 6, -16);
        shapeRenderer.line(6, -16, 0, -10);
        // trailing edge W (left)
        shapeRenderer.line(-28, 0, -22, -12);
        shapeRenderer.line(-22, -12, -14, -4);
        shapeRenderer.line(-14, -4, -6, -16);
        shapeRenderer.line(-6, -16, 0, -10);
        // centerline + cockpit
        shapeRenderer.line(0, 18, 0, -10);
        shapeRenderer.circle(0, 14, 3, 8);
        // engine exhaust slots
        shapeRenderer.line(-14, -2, -10, -10);
        shapeRenderer.line(-10, -2, -6, -10);
        shapeRenderer.line(6, -10, 10, -2);
        shapeRenderer.line(10, -10, 14, -2);
    }

    private void drawExhaust() {
        float sz = player.thrustLevel * 14f;
        shapeRenderer.line(-14, -10, -10, -10 - sz);
        shapeRenderer.line(-6,  -10, -10, -10 - sz);
        shapeRenderer.line(6,   -10,  10, -10 - sz);
        shapeRenderer.line(14,  -10,  10, -10 - sz);
    }

    private void handleInput(float delta) {
        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP)) {
            player.applyThrust(delta);
        } else {
            player.spinDownThrust(delta);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            player.applyBrake(delta);
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
        if (player.x < 0) { player.x = 0; if (player.vx < 0) player.vx = 0; }
        if (player.x > WORLD_WIDTH - Player.WIDTH)  { player.x = WORLD_WIDTH - Player.WIDTH;  if (player.vx > 0) player.vx = 0; }
        if (player.y < 0) { player.y = 0; if (player.vy < 0) player.vy = 0; }
        if (player.y > WORLD_HEIGHT - Player.HEIGHT) { player.y = WORLD_HEIGHT - Player.HEIGHT; if (player.vy > 0) player.vy = 0; }
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
