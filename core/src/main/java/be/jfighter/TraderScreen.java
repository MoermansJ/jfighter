package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

import java.util.ArrayList;
import java.util.List;

/**
 * Trade barge: browsable categories (supplies / ship upgrades / compartments / crew).
 * The upgrade stock is randomized per visit, early-game weighted toward cheap,
 * unowned upgrades; supplies always carry fuel so a visit is never pointless.
 */
public class TraderScreen implements Screen {
    private static final int FUEL_PRICE = 5;
    private static final float FUEL_AMOUNT = 10f;
    private static final int HIRE_PRICE = 150;
    private static final int MAX_CREW = 6;
    private static final float ROW_H = 46f;
    private static final float LIST_X = 120f;
    private static final float LIST_W = 720f;
    private static final float LIST_TOP = 380f;

    private static final String[] TABS = {"SUPPLIES", "UPGRADES", "COMPARTMENTS", "CREW"};

    private final JFighter game;
    private final GameState state;

    private FitViewport viewport;
    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private BitmapFont font;
    private int tab;

    // per-visit stock rolls
    private final List<ShipUpgrade> upgradeStock = new ArrayList<>();
    private final List<Integer> compartmentStock = new ArrayList<>();

    private interface Buy {
        void run();
    }

    private static class Item {
        final String name;
        final String detail;
        final int price;
        final boolean available;
        final Buy buy;

        Item(String name, String detail, int price, boolean available, Buy buy) {
            this.name = name;
            this.detail = detail;
            this.price = price;
            this.available = available;
            this.buy = buy;
        }
    }

    public TraderScreen(JFighter game, GameState state) {
        this.game = game;
        this.state = state;
    }

    @Override
    public void show() {
        viewport = new FitViewport(JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
        font = game.fonts.font;
        Fonts.scale(font, 1.4f);
        rollStock();
    }

    /** Random selection, weighted: early game prefers cheap upgrades the player doesn't own yet. */
    private void rollStock() {
        upgradeStock.clear();
        List<ShipUpgrade> pool = new ArrayList<>(List.of(ShipUpgrade.values()));
        java.util.Collections.shuffle(pool);
        pool.sort((a, b) -> Float.compare(stockWeight(a), stockWeight(b)));
        for (int i = 0; i < 3 && i < pool.size(); i++) {
            upgradeStock.add(pool.get(i));
        }
        compartmentStock.clear();
        while (compartmentStock.size() < 3) {
            int room = MathUtils.random(ShipDeckView.ROOM_NAMES.length - 1);
            if (!compartmentStock.contains(room)) compartmentStock.add(room);
        }
    }

    private float stockWeight(ShipUpgrade u) {
        float w = MathUtils.random(); // base shuffle noise
        w += state.upgradeLevel(u) * 0.8f;              // owned drifts out of stock
        if (state.sector <= 1 && u.basePrice > 110) w += 0.6f; // early game: mostly cheap tiers
        return w;
    }

    private List<Item> items() {
        List<Item> list = new ArrayList<>();
        switch (tab) {
            case 0: { // supplies
                boolean fuelFull = state.fuel >= state.maxFuel;
                list.add(new Item("Fuel cells", "+" + (int) FUEL_AMOUNT + " fuel", FUEL_PRICE,
                    !fuelFull, () -> state.fuel = Math.min(state.maxFuel, state.fuel + FUEL_AMOUNT)));
                int missing = Math.round(state.maxHull - state.hull);
                int repairPrice = Math.max(10, missing);
                list.add(new Item("Hull repairs", "restore full hull", repairPrice,
                    missing > 0, () -> state.hull = state.maxHull));
                break;
            }
            case 1: { // ship upgrades
                for (ShipUpgrade u : upgradeStock) {
                    int level = state.upgradeLevel(u);
                    list.add(new Item(u.label + " L" + (level + 1), u.effect, u.priceAt(level),
                        true, () -> state.buyUpgrade(u)));
                }
                break;
            }
            case 2: { // compartment tiers
                for (int room : compartmentStock) {
                    int tier = state.roomTier[room];
                    int price = 80 * tier;
                    final int r = room;
                    list.add(new Item(ShipDeckView.ROOM_NAMES[room] + "  T" + tier + " -> T" + (tier + 1),
                        "+1 station effectiveness", price, tier < 3, () -> state.roomTier[r]++));
                }
                break;
            }
            default: { // crew
                boolean room = state.crew.size() < MAX_CREW;
                list.add(new Item("Hire a deckhand", "random skills, reports to quarters", HIRE_PRICE,
                    room, state::hireCrew));
                break;
            }
        }
        return list;
    }

    @Override
    public void render(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.setScreen(new OverworldScreen(game, state));
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT)) tab = (tab + TABS.length - 1) % TABS.length;
        if (Gdx.input.isKeyJustPressed(Input.Keys.RIGHT)) tab = (tab + 1) % TABS.length;

        Vector3 mouse = viewport.getCamera().unproject(
            new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0),
            viewport.getScreenX(), viewport.getScreenY(),
            viewport.getScreenWidth(), viewport.getScreenHeight());
        boolean click = Gdx.input.isButtonJustPressed(Input.Buttons.LEFT);

        // tab hitboxes
        int hoveredTab = -1;
        for (int i = 0; i < TABS.length; i++) {
            if (tabRect(i).contains(mouse.x, mouse.y)) hoveredTab = i;
        }
        if (click && hoveredTab != -1) tab = hoveredTab;

        List<Item> list = items();
        int hoveredItem = -1;
        for (int i = 0; i < list.size(); i++) {
            if (itemRect(i).contains(mouse.x, mouse.y)) hoveredItem = i;
        }
        if (click && hoveredItem != -1) {
            Item it = list.get(hoveredItem);
            if (it.available && state.credits >= it.price) {
                state.credits -= it.price;
                it.buy.run();
                game.sfx.playCatch();
                list = items(); // refresh rows after the purchase
            }
        }

        ScreenUtils.clear(0, 0.04f, 0.08f, 1f);
        viewport.apply();
        shapes.setProjectionMatrix(viewport.getCamera().combined);
        batch.setProjectionMatrix(viewport.getCamera().combined);

        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < TABS.length; i++) {
            Rectangle r = tabRect(i);
            shapes.setColor(i == tab ? Color.CYAN : i == hoveredTab ? Color.WHITE
                : new Color(0.25f, 0.35f, 0.4f, 1f));
            shapes.rect(r.x, r.y, r.width, r.height);
        }
        for (int i = 0; i < list.size(); i++) {
            Rectangle r = itemRect(i);
            Item it = list.get(i);
            boolean can = it.available && state.credits >= it.price;
            shapes.setColor(!can ? new Color(0.25f, 0.28f, 0.3f, 1f)
                : i == hoveredItem ? Color.YELLOW : new Color(0.35f, 0.55f, 0.6f, 1f));
            shapes.rect(r.x, r.y, r.width, r.height);
        }
        shapes.end();

        batch.begin();
        Fonts.scale(font, 2f);
        font.setColor(Color.CYAN);
        font.draw(batch, "TRADE BARGE", 120, 500);
        Fonts.scale(font, 1.4f);
        font.setColor(Color.YELLOW);
        GlyphLayout cr = new GlyphLayout(font, "Credits: " + state.credits);
        font.draw(batch, cr, 840 - cr.width, 495);
        font.setColor(Color.WHITE);
        GlyphLayout fu = new GlyphLayout(font, "Fuel: " + (int) state.fuel + "/" + (int) state.maxFuel
            + "   Hull: " + (int) state.hull + "/" + (int) state.maxHull);
        font.draw(batch, fu, 840 - fu.width, 470);

        for (int i = 0; i < TABS.length; i++) {
            Rectangle r = tabRect(i);
            font.setColor(i == tab ? Color.CYAN : Color.GRAY);
            GlyphLayout gl = new GlyphLayout(font, TABS[i]);
            font.draw(batch, gl, r.x + (r.width - gl.width) / 2f, r.y + (r.height + gl.height) / 2f);
        }
        for (int i = 0; i < list.size(); i++) {
            Rectangle r = itemRect(i);
            Item it = list.get(i);
            boolean can = it.available && state.credits >= it.price;
            font.setColor(can ? Color.WHITE : Color.DARK_GRAY);
            font.draw(batch, it.name, r.x + 12, r.y + r.height - 8);
            Fonts.scale(font, 1f);
            font.setColor(can ? Color.GRAY : Color.DARK_GRAY);
            font.draw(batch, it.available ? it.detail : "(unavailable)", r.x + 12, r.y + 16);
            Fonts.scale(font, 1.4f);
            font.setColor(can ? Color.YELLOW : Color.DARK_GRAY);
            GlyphLayout pg = new GlyphLayout(font, it.price + " cr");
            font.draw(batch, pg, r.x + r.width - pg.width - 12, r.y + (r.height + pg.height) / 2f);
        }
        font.setColor(Color.GRAY);
        Fonts.scale(font, 1f);
        font.draw(batch, "LEFT/RIGHT or click to browse — ESC to undock", 120, 40);
        Fonts.scale(font, 1.4f);
        Dev.drawIndicator(batch, font, JFighter.WORLD_WIDTH, JFighter.WORLD_HEIGHT);
        batch.end();
    }

    private Rectangle tabRect(int i) {
        float w = 180f;
        return new Rectangle(LIST_X + i * (w + 6f), 410, w, 34);
    }

    private Rectangle itemRect(int i) {
        return new Rectangle(LIST_X, LIST_TOP - (i + 1) * (ROW_H + 8f), LIST_W, ROW_H);
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
