package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

public class TitleScreen implements Screen {
    private static final Rectangle RESUME_BUTTON =
        new Rectangle((JFighter.WORLD_WIDTH - 200) / 2f, 140, 200, 40);
    private static final Rectangle BUTTON =
        new Rectangle((JFighter.WORLD_WIDTH - 200) / 2f, 90, 200, 40);
    private static final Rectangle OPTIONS_BUTTON =
        new Rectangle((JFighter.WORLD_WIDTH - 200) / 2f, 40, 200, 40);

    private final JFighter game;
    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private static final String[][] PERKS = {
        {Meta.PERK_FUEL, "+2 starting fuel"},
        {Meta.PERK_CREDITS, "+100 starting credits"},
        {Meta.PERK_HULL, "+15 max hull"},
    };
    private FitViewport viewport;
    private Texture background;
    private BitmapFont font;
    private GlyphLayout buttonLayout;
    private GlyphLayout optionsLayout;
    private GlyphLayout resumeLayout;

    public TitleScreen(JFighter game) {
        this.game = game;
    }

    @Override
    public void show() {
        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
        viewport = new FitViewport(JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        background = new Texture(Gdx.files.internal("jfightertitle640.png"));
        font = game.fonts.font;
        Fonts.scale(font, 2f);
        buttonLayout = new GlyphLayout(font, game.currentRun != null ? "NEW GAME" : "START GAME");
        optionsLayout = new GlyphLayout(font, "OPTIONS");
        resumeLayout = new GlyphLayout(font, "RESUME");
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
        boolean resumeHovered = game.currentRun != null && RESUME_BUTTON.contains(mouse.x, mouse.y);

        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            if (resumeHovered) {
                game.setScreen(new OverworldScreen(game, game.currentRun));
                return;
            }
            if (hovered) {
                game.setScreen(new SetupScreen(game)); // outfitting first; LAUNCH commits the run
                return;
            }
            if (optionsHovered) {
                game.setScreen(new OptionsScreen(game));
                return;
            }
            for (int i = 0; i < PERKS.length; i++) {
                if (perkRect(i).contains(mouse.x, mouse.y)) {
                    Meta.buyPerk(PERKS[i][0]);
                }
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
        if (game.currentRun != null) {
            font.setColor(resumeHovered ? Color.YELLOW : Color.GREEN);
            float resX = RESUME_BUTTON.x + (RESUME_BUTTON.width - resumeLayout.width) / 2f;
            float resY = RESUME_BUTTON.y + (RESUME_BUTTON.height + resumeLayout.height) / 2f;
            font.draw(batch, resumeLayout, resX, resY);
        }
        // meta-progression perk shop, bottom-left
        Fonts.scale(font, 1f);
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "SALVAGE: " + Meta.salvage(), 16, 190);
        for (int i = 0; i < PERKS.length; i++) {
            Rectangle r = perkRect(i);
            boolean can = Meta.salvage() >= Meta.PERK_COST;
            font.setColor(can ? Color.WHITE : Color.GRAY);
            font.draw(batch, PERKS[i][1] + "  L" + Meta.perkLevel(PERKS[i][0])
                + "  (" + Meta.PERK_COST + " salv)", r.x + 6, r.y + r.height - 6);
        }
        Fonts.scale(font, 2f);
        Dev.drawIndicator(batch, font, JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        batch.end();

        shapes.setProjectionMatrix(viewport.getCamera().combined);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < PERKS.length; i++) {
            Rectangle r = perkRect(i);
            shapes.setColor(0.35f, 0.4f, 0.45f, 1f);
            shapes.rect(r.x, r.y, r.width, r.height);
        }
        shapes.end();
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

    private static Rectangle perkRect(int i) {
        return new Rectangle(12, 156 - i * 30, 250, 26);
    }

    @Override
    public void dispose() {
        batch.dispose();
        shapes.dispose();
        background.dispose();
    }
}
