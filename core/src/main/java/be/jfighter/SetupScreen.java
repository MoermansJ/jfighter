package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

/**
 * Pre-game setup (#93): pick a hull, meet the generated crew, toggle run
 * modifiers, reroll until the roster feels right, then LAUNCH. The GameState
 * is built here, so what you review is exactly what you fly with.
 */
public class SetupScreen implements Screen {
    private final JFighter game;
    private GameState draft;
    private ShipHull hull = ShipHull.CARRIER;

    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private FitViewport viewport;
    private BitmapFont font;
    private final Matrix4 transform = new Matrix4();

    private static final Rectangle LAUNCH_BTN = new Rectangle(700, 40, 200, 44);
    private static final Rectangle REROLL_BTN = new Rectangle(480, 40, 200, 44);
    private static final Rectangle BACK_BTN = new Rectangle(60, 40, 160, 44);
    // one ship for now (#136): SCOUT/FREIGHTER stay behind the curtain until they earn their cards
    private static final Rectangle SHIP_CARD = new Rectangle(60, 330, 400, 160);
    private static final String[][] MODS = {
        {GameState.MOD_IRON, "IRON — no free HOME repairs, +2 salvage"},
        {GameState.MOD_DARK, "DARK — the fog never lifts, loot +30%"},
        {GameState.MOD_OVERLOAD, "OVERLOAD — +1 reactor unit, storms everywhere"},
    };

    public SetupScreen(JFighter game) {
        this.game = game;
    }

    @Override
    public void show() {
        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
        viewport = new FitViewport(JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        font = game.fonts.font;
        Fonts.scale(font, 1.4f);
        reroll();
    }

    private void reroll() {
        java.util.Set<String> keep = draft != null ? draft.modifiers : java.util.Set.of();
        draft = new GameState(hull);
        draft.modifiers.addAll(keep);
    }

    private Rectangle modRect(int i) {
        return new Rectangle(500, 300 - i * 34, 410, 28);
    }

    @Override
    public void render(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.setScreen(new TitleScreen(game));
            return;
        }
        Vector3 mouse = viewport.getCamera().unproject(
            new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0),
            viewport.getScreenX(), viewport.getScreenY(),
            viewport.getScreenWidth(), viewport.getScreenHeight());
        boolean click = Gdx.input.isButtonJustPressed(Input.Buttons.LEFT);

        if (click) {
            for (int i = 0; i < MODS.length; i++) {
                if (modRect(i).contains(mouse.x, mouse.y)) {
                    String key = MODS[i][0];
                    if (!draft.modifiers.remove(key)) draft.modifiers.add(key);
                }
            }
            if (REROLL_BTN.contains(mouse.x, mouse.y)) reroll();
            if (BACK_BTN.contains(mouse.x, mouse.y)) {
                game.setScreen(new TitleScreen(game));
                return;
            }
            if (LAUNCH_BTN.contains(mouse.x, mouse.y)) {
                draft.applyModifiers();
                game.currentRun = draft;
                game.setScreen(new OverworldScreen(game, draft));
                return;
            }
        }

        ScreenUtils.clear(0.03f, 0.04f, 0.07f, 1f);
        viewport.apply();
        shapes.setProjectionMatrix(viewport.getCamera().combined);
        batch.setProjectionMatrix(viewport.getCamera().combined);

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(Color.CYAN);
        shapes.rect(SHIP_CARD.x, SHIP_CARD.y, SHIP_CARD.width, SHIP_CARD.height);
        // ship portrait: the same carrier silhouette that fights on the combat screen (#137)
        transform.setToTranslation(SHIP_CARD.x + 90, SHIP_CARD.y + SHIP_CARD.height / 2f, 0)
            .rotate(0, 0, 1, -90f).scale(0.62f, 0.62f, 1f);
        shapes.setTransformMatrix(transform);
        shapes.setColor(Color.GREEN);
        ShipRenderer.drawCarrier(shapes);
        shapes.setTransformMatrix(transform.idt());
        for (int i = 0; i < MODS.length; i++) {
            Rectangle r = modRect(i);
            boolean on = draft.modifiers.contains(MODS[i][0]);
            shapes.setColor(on ? Color.YELLOW : new Color(0.25f, 0.3f, 0.35f, 1f));
            shapes.rect(r.x, r.y, r.width, r.height);
        }
        shapes.setColor(Color.GREEN);
        shapes.rect(LAUNCH_BTN.x, LAUNCH_BTN.y, LAUNCH_BTN.width, LAUNCH_BTN.height);
        shapes.setColor(Color.GRAY);
        shapes.rect(REROLL_BTN.x, REROLL_BTN.y, REROLL_BTN.width, REROLL_BTN.height);
        shapes.rect(BACK_BTN.x, BACK_BTN.y, BACK_BTN.width, BACK_BTN.height);
        shapes.end();

        batch.begin();
        Fonts.scale(font, 2f);
        font.setColor(Color.WHITE);
        font.draw(batch, "OUTFITTING", 60, 525);
        // ship card text, clear of the portrait (#138: measured columns, no stacking)
        Fonts.scale(font, 1.4f);
        font.setColor(Color.CYAN);
        font.draw(batch, hull.label, SHIP_CARD.x + 180, SHIP_CARD.y + SHIP_CARD.height - 16);
        Fonts.scale(font, 0.95f);
        font.setColor(Color.GRAY);
        font.draw(batch, hull.blurb, SHIP_CARD.x + 180, SHIP_CARD.y + SHIP_CARD.height - 44);
        font.draw(batch, "HULL " + (int) hull.maxHull, SHIP_CARD.x + 180, SHIP_CARD.y + SHIP_CARD.height - 72);
        font.draw(batch, "THRUST " + Math.round(hull.thrustMult * 100) + "%",
            SHIP_CARD.x + 180, SHIP_CARD.y + SHIP_CARD.height - 92);
        font.draw(batch, "CREW " + hull.crewCount, SHIP_CARD.x + 180, SHIP_CARD.y + SHIP_CARD.height - 112);
        // right column, top: the mothership's socket diagram (#119)
        Mothership fit = Mothership.forHull(hull);
        Fonts.scale(font, 1.4f);
        font.setColor(Color.GRAY);
        font.draw(batch, "ARMAMENT — " + fit.model, 500, 490);
        Fonts.scale(font, 0.95f);
        float sy = 464;
        for (Mothership.Socket s : fit.sockets) {
            font.setColor(s.mount != null ? Color.WHITE : Color.DARK_GRAY);
            font.draw(batch, "[" + s.size.name().charAt(0) + "] "
                + (s.mount != null ? s.mount : "— EMPTY —"), 500, sy);
            sy -= 22;
        }
        // left column, below the card: crew review
        Fonts.scale(font, 1.4f);
        font.setColor(Color.GRAY);
        font.draw(batch, "CREW MANIFEST", 60, 300);
        Fonts.scale(font, 1.1f);
        float y = 272;
        for (CrewMember c : draft.crew) {
            font.setColor(Color.WHITE);
            font.draw(batch, c.name, 60, y);
            font.setColor(Color.GRAY);
            font.draw(batch, c.primary + " +" + CrewMember.PRIMARY_BONUS
                + "  " + c.secondary + " +" + CrewMember.SECONDARY_BONUS
                + "  " + c.trait, 210, y);
            y -= 26;
        }
        Fonts.scale(font, 1.4f);
        // right column, bottom: modifiers
        font.setColor(Color.GRAY);
        font.draw(batch, "RUN MODIFIERS", 500, 356);
        Fonts.scale(font, 0.95f);
        for (int i = 0; i < MODS.length; i++) {
            Rectangle r = modRect(i);
            boolean on = draft.modifiers.contains(MODS[i][0]);
            font.setColor(on ? Color.YELLOW : Color.GRAY);
            font.draw(batch, (on ? "[x] " : "[ ] ") + MODS[i][1], r.x + 8, r.y + 19);
        }
        Fonts.scale(font, 1.4f);
        font.setColor(Color.GREEN);
        GlyphLayout lg = new GlyphLayout(font, "LAUNCH");
        font.draw(batch, lg, LAUNCH_BTN.x + (LAUNCH_BTN.width - lg.width) / 2f, LAUNCH_BTN.y + 29);
        font.setColor(Color.LIGHT_GRAY);
        GlyphLayout rg = new GlyphLayout(font, "REROLL CREW");
        font.draw(batch, rg, REROLL_BTN.x + (REROLL_BTN.width - rg.width) / 2f, REROLL_BTN.y + 29);
        GlyphLayout bg = new GlyphLayout(font, "BACK");
        font.draw(batch, bg, BACK_BTN.x + (BACK_BTN.width - bg.width) / 2f, BACK_BTN.y + 29);
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
        shapes.dispose();
    }
}
