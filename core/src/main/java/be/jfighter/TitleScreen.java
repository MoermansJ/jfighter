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
    private static final Rectangle BUTTON = new Rectangle(220, 80, 200, 40);

    private final JFighter game;
    private SpriteBatch batch;
    private FitViewport viewport;
    private Texture background;
    private BitmapFont font;
    private GlyphLayout buttonLayout;

    public TitleScreen(JFighter game) {
        this.game = game;
    }

    @Override
    public void show() {
        batch = new SpriteBatch();
        viewport = new FitViewport(640, 480);
        background = new Texture(Gdx.files.internal("jfightertitle640.png"));
        font = new BitmapFont();
        font.getData().setScale(2f);
        buttonLayout = new GlyphLayout(font, "START GAME");
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

        if (hovered && Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            game.setScreen(new GameScreen());
        }

        batch.begin();
        batch.draw(background, 0, 0, 640, 480);
        font.setColor(hovered ? Color.YELLOW : Color.WHITE);
        float textX = BUTTON.x + (BUTTON.width - buttonLayout.width) / 2f;
        float textY = BUTTON.y + (BUTTON.height + buttonLayout.height) / 2f;
        font.draw(batch, buttonLayout, textX, textY);
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
