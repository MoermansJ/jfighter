package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

public class GameScreen implements Screen {
    private static final float WORLD_WIDTH = 640f;
    private static final float WORLD_HEIGHT = 480f;

    private SpriteBatch batch;
    private FitViewport viewport;
    private Player player;
    private Texture playerTexture;

    @Override
    public void show() {
        batch = new SpriteBatch();
        viewport = new FitViewport(WORLD_WIDTH, WORLD_HEIGHT);
        player = new Player(100f, (WORLD_HEIGHT - Player.HEIGHT) / 2f);
        playerTexture = new Texture(Gdx.files.internal("player.png"));
    }

    @Override
    public void render(float delta) {
        handleInput(delta);
        clampPlayer();

        ScreenUtils.clear(0.08f, 0.08f, 0.12f, 1f);
        viewport.apply();
        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();
        batch.draw(playerTexture,
            player.x, player.y,
            Player.WIDTH / 2f, Player.HEIGHT / 2f,
            Player.WIDTH, Player.HEIGHT,
            1f, 1f,
            player.rotation,
            0, 0, playerTexture.getWidth(), playerTexture.getHeight(),
            false, false);
        batch.end();
    }

    private void handleInput(float delta) {
        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP)) {
            player.moveUp(delta);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            player.moveDown(delta);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            player.rotateLeft(delta);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            player.rotateRight(delta);
        }
    }

    private void clampPlayer() {
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
        batch.dispose();
        playerTexture.dispose();
    }
}
