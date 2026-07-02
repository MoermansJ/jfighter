package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

public class TitleScreen implements Screen {
    private static final Rectangle BUTTON =
        new Rectangle((JFighter.WORLD_WIDTH - 200) / 2f, 90, 200, 40);
    private static final Rectangle OPTIONS_BUTTON =
        new Rectangle((JFighter.WORLD_WIDTH - 200) / 2f, 40, 200, 40);

    private final JFighter game;
    private SpriteBatch batch;
    private FitViewport viewport;
    private Texture background;
    private BitmapFont font;
    private GlyphLayout buttonLayout;
    private GlyphLayout optionsLayout;

    public TitleScreen(JFighter game) {
        this.game = game;
    }

    @Override
    public void show() {
        batch = new SpriteBatch();
        viewport = new FitViewport(JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        background = new Texture(Gdx.files.internal("jfightertitle640.png"));
        font = new BitmapFont();
        font.getData().setScale(2f);
        buttonLayout = new GlyphLayout(font, "START GAME");
        optionsLayout = new GlyphLayout(font, "OPTIONS");
    }

    @Override
    public void render(float delta) {
        ScreenUtils.clear(0.05f, 0.05f, 0.1f, 1f);
        viewport.apply();
        batch.setProjectionMatrix(viewport.getCamera().combined);

        Vector3 mouse = viewport.getCamera().unproject(
            new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0),
            viewport.getScreenX(), viewport.getScreenY(),
            viewport.getScreenWidth(), viewport.getScreenHeight()
        );
        boolean hovered = BUTTON.contains(mouse.x, mouse.y);
        boolean optionsHovered = OPTIONS_BUTTON.contains(mouse.x, mouse.y);

        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            if (hovered) {
                game.setScreen(new OverworldScreen(game, new GameState()));
                return;
            }
            if (optionsHovered) {
                game.setScreen(new OptionsScreen(game));
                return;
            }
        }

        batch.begin();
        // 4:3 title art stretched over the 16:9 world until it's re-exported
        batch.draw(background, 0, 0, JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        batch.end();

        batch.begin();
        font.setColor(hovered ? Color.YELLOW : Color.WHITE);
        float textX = BUTTON.x + (BUTTON.width - buttonLayout.width) / 2f;
        float textY = BUTTON.y + (BUTTON.height + buttonLayout.height) / 2f;
        font.draw(batch, buttonLayout, textX, textY);
        font.setColor(optionsHovered ? Color.YELLOW : Color.WHITE);
        float optX = OPTIONS_BUTTON.x + (OPTIONS_BUTTON.width - optionsLayout.width) / 2f;
        float optY = OPTIONS_BUTTON.y + (OPTIONS_BUTTON.height + optionsLayout.height) / 2f;
        font.draw(batch, optionsLayout, optX, optY);
        batch.end();
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
        background.dispose();
        font.dispose();
    }
}
