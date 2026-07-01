package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

public class TraderScreen implements Screen {
    private final JFighter game;
    private final GameState state;

    private FitViewport viewport;
    private SpriteBatch batch;
    private BitmapFont font;

    public TraderScreen(JFighter game, GameState state) {
        this.game = game;
        this.state = state;
    }

    @Override
    public void show() {
        viewport = new FitViewport(640, 480);
        batch = new SpriteBatch();
        font = new BitmapFont();
        font.getData().setScale(2f);
    }

    @Override
    public void render(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.setScreen(new OverworldScreen(game, state));
            return;
        }

        ScreenUtils.clear(0, 0.05f, 0.1f, 1f);
        viewport.apply();
        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();
        font.setColor(Color.CYAN);
        font.draw(batch, "TRADER", 220, 300);
        font.getData().setScale(1.4f);
        font.setColor(Color.WHITE);
        font.draw(batch, "Credits: " + state.credits, 220, 240);
        font.draw(batch, "Press ESC to return to map", 160, 160);
        batch.end();
    }

    @Override
    public void resize(int width, int height) { viewport.update(width, height, true); }
    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() { dispose(); }

    @Override
    public void dispose() {
        batch.dispose();
        font.dispose();
    }
}
