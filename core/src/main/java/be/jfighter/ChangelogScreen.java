package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

/** What's-new screen (#115): renders assets/changelog.txt; scroll with UP/DOWN, leave with ESC. */
public class ChangelogScreen implements Screen {
    private final JFighter game;
    private SpriteBatch batch;
    private FitViewport viewport;
    private BitmapFont font;
    private String[] lines = {};
    private float scroll;

    public ChangelogScreen(JFighter game) {
        this.game = game;
    }

    @Override
    public void show() {
        batch = new SpriteBatch();
        viewport = new FitViewport(JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        font = game.fonts.font;
        try {
            lines = Gdx.files.internal("changelog.txt").readString("UTF-8").split("\n");
        } catch (Exception e) {
            lines = new String[]{"changelog.txt missing"};
        }
    }

    @Override
    public void render(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.justTouched()) {
            game.setScreen(new TitleScreen(game));
            return;
        }
        float maxScroll = Math.max(0, lines.length * 22f - 420f);
        if (Gdx.input.isKeyPressed(Input.Keys.DOWN)) scroll = Math.min(maxScroll, scroll + 260f * delta);
        if (Gdx.input.isKeyPressed(Input.Keys.UP)) scroll = Math.max(0, scroll - 260f * delta);

        ScreenUtils.clear(0.02f, 0.03f, 0.05f, 1f);
        viewport.apply();
        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();
        Fonts.scale(font, 1.1f);
        float y = 500 + scroll;
        for (String line : lines) {
            if (y < 560 && y > 20) {
                if (line.startsWith("==")) font.setColor(Color.CYAN);
                else if (line.startsWith("BATCH")) font.setColor(Color.WHITE);
                else font.setColor(Color.GRAY);
                font.draw(batch, line, 90, y);
            }
            y -= 22;
        }
        Fonts.scale(font, 1f);
        font.setColor(Color.DARK_GRAY);
        font.draw(batch, "UP/DOWN to scroll — ESC or click to return", 90, 26);
        Fonts.scale(font, 1.4f);
        Dev.drawIndicator(batch, font, JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        batch.end();
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() { dispose(); }

    @Override
    public void dispose() {
        batch.dispose();
    }
}
