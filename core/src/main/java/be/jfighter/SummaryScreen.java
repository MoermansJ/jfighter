package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

/** End-of-run summary (victory retirement or ship lost): the run's numbers, then back to the title. */
public class SummaryScreen implements Screen {
    private final JFighter game;
    private final GameState state;
    private final String title;
    private final boolean won;

    private SpriteBatch batch;
    private FitViewport viewport;
    private BitmapFont font;
    private int salvageEarned;

    public SummaryScreen(JFighter game, GameState state, boolean won) {
        this.game = game;
        this.state = state;
        this.won = won;
        this.title = won ? "RUN COMPLETE" : "SHIP LOST";
    }

    @Override
    public void show() {
        batch = new SpriteBatch();
        viewport = new FitViewport(JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        font = game.fonts.font;
        // meta-progression: the run pays out persistent salvage
        salvageEarned = state.sectorsCleared + state.credits / 300;
        if (salvageEarned > 0) Meta.addSalvage(salvageEarned);
    }

    @Override
    public void render(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.ENTER)
                || Gdx.input.justTouched()) {
            game.setScreen(new TitleScreen(game));
            return;
        }

        ScreenUtils.clear(0.02f, 0.03f, 0.05f, 1f);
        viewport.apply();
        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();

        Fonts.scale(font, 2.2f);
        font.setColor(won ? Color.GREEN : new Color(0.95f, 0.3f, 0.25f, 1f));
        GlyphLayout gl = new GlyphLayout(font, title);
        font.draw(batch, gl, (JFighter.WORLD_WIDTH - gl.width) / 2f, 470);

        Fonts.scale(font, 1.4f);
        String[][] rows = {
            {"Sectors cleared", String.valueOf(state.sectorsCleared)},
            {"Nodes visited", String.valueOf(state.nodesVisited)},
            {"Instances completed", String.valueOf(state.instancesCompleted)},
            {"Cargo delivered", String.valueOf(state.cargoDelivered)},
            {"Hostiles destroyed", String.valueOf(state.hostilesDestroyed)},
            {"Credits amassed", String.valueOf(state.credits)},
            {"Crew lost", String.valueOf(state.crewLost())},
            {"Salvage earned", "+" + salvageEarned + "  (total " + Meta.salvage() + ")"},
        };
        float y = 390;
        for (String[] row : rows) {
            font.setColor(Color.GRAY);
            font.draw(batch, row[0], 320, y);
            font.setColor(Color.WHITE);
            GlyphLayout vgl = new GlyphLayout(font, row[1]);
            font.draw(batch, vgl, 640 - vgl.width, y);
            y -= 34;
        }

        font.setColor(Color.GRAY);
        Fonts.scale(font, 1f);
        GlyphLayout hint = new GlyphLayout(font, "click or press ENTER to return to the title");
        font.draw(batch, hint, (JFighter.WORLD_WIDTH - hint.width) / 2f, 80);
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

    @Override
    public void hide() {
        dispose();
    }

    @Override
    public void dispose() {
        batch.dispose();
    }
}
