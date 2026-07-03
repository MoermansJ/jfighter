package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

/** Display options: windowed size presets + fullscreen. ESC or BACK returns to the title. */
public class OptionsScreen implements Screen {
    private static final float ROW_HEIGHT = 44;
    private static final float ROW_WIDTH = 320;

    private final JFighter game;
    private SpriteBatch batch;
    private FitViewport viewport;
    private BitmapFont font;

    private String[] labels;
    private Rectangle[] rows;

    public OptionsScreen(JFighter game) {
        this.game = game;
    }

    @Override
    public void show() {
        batch = new SpriteBatch();
        viewport = new FitViewport(JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        font = game.fonts.font;
        Fonts.scale(font, 1.5f);

        int n = JFighter.WINDOW_SIZES.length;
        labels = new String[n + 3]; // presets + fullscreen + volume + back
        rows = new Rectangle[labels.length];
        float x = (JFighter.WORLD_WIDTH - ROW_WIDTH) / 2f;
        float top = 400;
        for (int i = 0; i < n; i++) {
            labels[i] = JFighter.WINDOW_SIZES[i][0] + " x " + JFighter.WINDOW_SIZES[i][1];
            rows[i] = new Rectangle(x, top - i * ROW_HEIGHT, ROW_WIDTH, ROW_HEIGHT);
        }
        labels[n] = "FULLSCREEN";
        rows[n] = new Rectangle(x, top - n * ROW_HEIGHT, ROW_WIDTH, ROW_HEIGHT);
        labels[n + 1] = volumeLabel();
        rows[n + 1] = new Rectangle(x, top - (n + 1) * ROW_HEIGHT, ROW_WIDTH, ROW_HEIGHT);
        labels[n + 2] = "BACK";
        rows[n + 2] = new Rectangle(x, top - (n + 2) * ROW_HEIGHT - 20, ROW_WIDTH, ROW_HEIGHT);
    }

    @Override
    public void render(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.setScreen(new TitleScreen(game));
            return;
        }

        ScreenUtils.clear(0.05f, 0.05f, 0.1f, 1f);
        viewport.apply();
        batch.setProjectionMatrix(viewport.getCamera().combined);

        Vector3 mouse = viewport.getCamera().unproject(
            new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0),
            viewport.getScreenX(), viewport.getScreenY(),
            viewport.getScreenWidth(), viewport.getScreenHeight()
        );
        int hovered = -1;
        for (int i = 0; i < rows.length; i++) {
            if (rows[i].contains(mouse.x, mouse.y)) hovered = i;
        }

        if (hovered >= 0 && Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            int n = JFighter.WINDOW_SIZES.length;
            if (hovered < n) {
                game.setWindowSize(JFighter.WINDOW_SIZES[hovered][0], JFighter.WINDOW_SIZES[hovered][1]);
            } else if (hovered == n) {
                game.setFullscreen(true);
            } else if (hovered == n + 1) {
                // cycle master volume in 20% steps
                SoundFx.masterVolume = (Math.round(SoundFx.masterVolume * 5) % 5 + 1) / 5f;
                Gdx.app.getPreferences("jfighter").putFloat("volume", SoundFx.masterVolume).flush();
                labels[n + 1] = volumeLabel();
            } else {
                game.setScreen(new TitleScreen(this.game));
                return;
            }
        }

        boolean fullscreen = Gdx.graphics.isFullscreen();
        int curW = Gdx.graphics.getWidth();
        int curH = Gdx.graphics.getHeight();

        batch.begin();
        Fonts.scale(font, 2f);
        font.setColor(Color.WHITE);
        GlyphLayout title = new GlyphLayout(font, "DISPLAY OPTIONS");
        font.draw(batch, title, (JFighter.WORLD_WIDTH - title.width) / 2f, 480);
        Fonts.scale(font, 1.5f);

        int n = JFighter.WINDOW_SIZES.length;
        for (int i = 0; i < labels.length; i++) {
            boolean active = !fullscreen && i < n
                && JFighter.WINDOW_SIZES[i][0] == curW && JFighter.WINDOW_SIZES[i][1] == curH;
            if (i == n) active = fullscreen;
            String text = (active ? "> " : "") + labels[i] + (active ? " <" : "");
            font.setColor(i == hovered ? Color.YELLOW : active ? Color.GREEN : Color.WHITE);
            GlyphLayout layout = new GlyphLayout(font, text);
            font.draw(batch, layout,
                rows[i].x + (rows[i].width - layout.width) / 2f,
                rows[i].y + (rows[i].height + layout.height) / 2f);
        }
        font.setColor(Color.GRAY);
        font.draw(batch, "F11 toggles fullscreen anywhere", 20, 30);
        batch.end();
    }

    private static String volumeLabel() {
        return "VOLUME " + Math.round(SoundFx.masterVolume * 100) + "%";
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
    }
}
