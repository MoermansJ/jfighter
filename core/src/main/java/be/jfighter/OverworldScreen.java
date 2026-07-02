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
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

public class OverworldScreen implements Screen {
    private static final float WORLD_WIDTH = JFighter.WORLD_WIDTH;
    private static final float WORLD_HEIGHT = JFighter.WORLD_HEIGHT;
    private static final float MAP_TOP = 270f; // divider: sector map below, ship blueprint above
    private static final float NODE_RADIUS = 18f;
    private static final int TRAVEL_FUEL_COST = 1;

    // crew roster panel, top-left of the deck-view section (inside the monitor bezel)
    private static final float ROSTER_X = 32f;
    private static final float ROSTER_TOP = 494f;   // baseline of the first row
    private static final float ROSTER_ROW_H = 25f;
    private static final float ROSTER_W = 240f;
    private static final float DIALOG_W = 340f;
    private static final float DIALOG_H = 160f;

    private final JFighter game;
    private final GameState state;

    private FitViewport viewport;
    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;

    private Node hoveredNode;
    private Node selectedNode;      // node whose dialog is open
    private CrewMember selectedCrew; // crew member being (re)stationed
    private CrewMember hoveredCrew;
    private int hoveredRoom = -1;
    private ShipDeckView deckView;
    private boolean victory;
    private final Matrix4 identity = new Matrix4();

    private static final Rectangle VICTORY_BTN =
        new Rectangle(WORLD_WIDTH / 2f - 110, WORLD_HEIGHT / 2f - 60, 220, 36);

    public OverworldScreen(JFighter game, GameState state) {
        this.game = game;
        this.state = state;
    }

    @Override
    public void show() {
        viewport = new FitViewport(WORLD_WIDTH, WORLD_HEIGHT);
        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont();
        font.getData().setScale(1.4f);
        deckView = new ShipDeckView(state);
    }

    @Override
    public void render(float delta) {
        updateHover();
        // stop rendering once the screen switches: hide() disposed our resources
        if (handleInput()) return;

        ScreenUtils.clear(0, 0, 0, 1f);
        viewport.apply();

        shapes.setProjectionMatrix(viewport.getCamera().combined);

        deckView.setHighlight(selectedCrew != null ? hoveredRoom : -1);
        deckView.setFocus(selectedCrew, hoveredCrew);
        deckView.update(delta);
        deckView.renderShapes(shapes);
        drawRoster();

        // section divider
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.25f, 0.25f, 0.25f, 1f);
        shapes.line(0, MAP_TOP, WORLD_WIDTH, MAP_TOP);

        drawMapDecals();

        // connection lines
        shapes.setColor(Color.DARK_GRAY);
        for (Node n : state.map.allNodes()) {
            for (int cid : n.connections) {
                if (cid > n.id) {
                    Node c = state.map.getNode(cid);
                    shapes.line(n.x, n.y, c.x, c.y);
                }
            }
        }
        shapes.end();

        // node fills + rings
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Node n : state.map.allNodes()) {
            shapes.setColor(nodeColor(n));
            shapes.circle(n.x, n.y, NODE_RADIUS, 24);
        }
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        // objective marker on the final node
        Node last = state.map.getNode(state.map.lastNodeId);
        shapes.setColor(0.9f, 0.6f, 0.2f, 1f);
        shapes.circle(last.x, last.y, NODE_RADIUS + 5f, 24);
        Node current = state.map.getCurrentNode();
        for (Node n : state.map.allNodes()) {
            if (n == current) {
                shapes.setColor(Color.WHITE);
                shapes.circle(n.x, n.y, NODE_RADIUS + 6f, 24);
            } else if (n == hoveredNode && state.map.isReachable(n.id)) {
                shapes.setColor(Color.WHITE);
                shapes.circle(n.x, n.y, NODE_RADIUS + 4f, 24);
            }
        }
        shapes.end();

        // dialog
        if (selectedNode != null) {
            drawDialog(selectedNode);
        }

        // labels + credits
        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();
        font.getData().setScale(1f);
        font.setColor(Color.WHITE);
        for (Node n : state.map.allNodes()) {
            String label = n.id == state.map.lastNodeId ? "END" : n.type.name();
            GlyphLayout gl = new GlyphLayout(font, label);
            font.draw(batch, label, n.x - gl.width / 2f, n.y - NODE_RADIUS - 6f);
        }
        font.getData().setScale(1.4f);
        font.setColor(Color.YELLOW);
        GlyphLayout creditsGl = new GlyphLayout(font, "Credits: " + state.credits);
        font.draw(batch, creditsGl, 930 - creditsGl.width, 516);
        font.setColor(state.fuel < 1f ? Color.RED : Color.WHITE);
        String fuelText = "Fuel: " + (int) state.fuel + (state.fuel < 1f ? "  — OUT OF FUEL" : "");
        GlyphLayout fuelGl = new GlyphLayout(font, fuelText);
        font.draw(batch, fuelGl, 930 - fuelGl.width, 491);
        font.setColor(Color.GRAY);
        font.draw(batch, state.map.sectorName.toUpperCase()
            + "  —  travel costs " + TRAVEL_FUEL_COST + " fuel", 10, MAP_TOP - 8);

        // deck view labels + feed overlay
        font.getData().setScale(1f);
        deckView.renderText(batch, font);

        // crew roster
        font.setColor(Color.GRAY);
        font.draw(batch, "CREW", ROSTER_X, ROSTER_TOP + 22);
        for (int i = 0; i < state.crew.size(); i++) {
            CrewMember c = state.crew.get(i);
            float baseline = ROSTER_TOP - i * ROSTER_ROW_H;
            font.setColor(Color.BLACK);
            font.draw(batch, String.valueOf(c.initial()), ROSTER_X + 8, baseline + 1);
            if (c == selectedCrew) font.setColor(Color.YELLOW);
            else if (c.isDead()) font.setColor(0.75f, 0.25f, 0.2f, 1f);
            else font.setColor(Color.WHITE);
            font.draw(batch, c.isDead() ? c.name + "  - KIA" : c.name, ROSTER_X + 26, baseline);
        }
        // selected crew details + hint
        float detailY = ROSTER_TOP - state.crew.size() * ROSTER_ROW_H - 6;
        if (selectedCrew != null) {
            font.setColor(Color.WHITE);
            font.draw(batch, selectedCrew.primary + " +" + CrewMember.PRIMARY_BONUS
                + "   " + selectedCrew.secondary + " +" + CrewMember.SECONDARY_BONUS
                + "   HP " + (int) Math.ceil(selectedCrew.hp), ROSTER_X, detailY);
            font.setColor(Color.GRAY);
            String station = selectedCrew.station < 0 ? "unassigned" : ShipDeckView.ROOM_NAMES[selectedCrew.station];
            font.draw(batch, "Station: " + station, ROSTER_X, detailY - 22);
            font.draw(batch, "Click a compartment to station", ROSTER_X, detailY - 44);
        } else {
            font.setColor(Color.GRAY);
            font.draw(batch, "Click a crewmember to select", ROSTER_X, detailY);
        }
        font.getData().setScale(1.4f);
        batch.end();

        if (victory) drawVictory();
    }

    private void drawVictory() {
        float w = 340, h = 170;
        float x = (WORLD_WIDTH - w) / 2f;
        float y = (WORLD_HEIGHT - h) / 2f - 20;

        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.05f, 0.08f, 0.06f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(Color.GREEN);
        shapes.rect(x, y, w, h);
        shapes.rect(VICTORY_BTN.x, VICTORY_BTN.y, VICTORY_BTN.width, VICTORY_BTN.height);
        shapes.end();

        batch.begin();
        font.getData().setScale(2f);
        font.setColor(Color.GREEN);
        GlyphLayout title = new GlyphLayout(font, "VICTORY");
        font.draw(batch, title, (WORLD_WIDTH - title.width) / 2f, y + h - 24);
        font.getData().setScale(1.4f);
        font.setColor(Color.WHITE);
        GlyphLayout sub = new GlyphLayout(font, "You reached the end of the sector");
        font.draw(batch, sub, (WORLD_WIDTH - sub.width) / 2f, y + h - 70);
        font.setColor(Color.GREEN);
        GlyphLayout btn = new GlyphLayout(font, "RETURN TO TITLE");
        font.draw(batch, btn, VICTORY_BTN.x + (VICTORY_BTN.width - btn.width) / 2f,
            VICTORY_BTN.y + (VICTORY_BTN.height + btn.height) / 2f);
        batch.end();
    }

    /** Sci-fi dressing for the map section: faint grid plus scan rings, markers, and hazard triangles. */
    private void drawMapDecals() {
        // grid
        shapes.setColor(0.07f, 0.09f, 0.11f, 1f);
        for (float x = 96; x < WORLD_WIDTH; x += 96) {
            shapes.line(x, 0, x, MAP_TOP);
        }
        for (float y = 68; y < MAP_TOP; y += 68) {
            shapes.line(0, y, WORLD_WIDTH, y);
        }
        for (OverworldMap.Decal d : state.map.getDecals()) {
            switch (d.kind) {
                case RING:
                    shapes.setColor(0.1f, 0.2f, 0.24f, 1f);
                    shapes.circle(d.x, d.y, d.size, 32);
                    break;
                case CROSS:
                    shapes.setColor(0.18f, 0.26f, 0.28f, 1f);
                    shapes.line(d.x - d.size, d.y, d.x + d.size, d.y);
                    shapes.line(d.x, d.y - d.size, d.x, d.y + d.size);
                    break;
                case HAZARD:
                    shapes.setColor(0.32f, 0.2f, 0.05f, 1f);
                    shapes.line(d.x - d.size, d.y - d.size * 0.7f, d.x + d.size, d.y - d.size * 0.7f);
                    shapes.line(d.x - d.size, d.y - d.size * 0.7f, d.x, d.y + d.size);
                    shapes.line(d.x + d.size, d.y - d.size * 0.7f, d.x, d.y + d.size);
                    break;
            }
        }
    }

    /** Roster panel frame + crew dots (stationed crew are drawn by the deck view itself). */
    private void drawRoster() {
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        if (selectedCrew != null) {
            Rectangle row = rosterRow(state.crew.indexOf(selectedCrew));
            shapes.setColor(Color.YELLOW);
            shapes.rect(row.x, row.y, row.width, row.height);
        }
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < state.crew.size(); i++) {
            CrewMember c = state.crew.get(i);
            if (c == selectedCrew) shapes.setColor(Color.YELLOW);
            else if (c.isDead()) shapes.setColor(0.45f, 0.45f, 0.5f, 1f);
            else shapes.setColor(0.3f, 0.65f, 0.8f, 1f);
            shapes.circle(ROSTER_X + 12, ROSTER_TOP - i * ROSTER_ROW_H - 5, 7, 16);
        }
        shapes.end();
    }

    private float dialogX(Node node) {
        return MathUtils.clamp(node.x - DIALOG_W / 2f, 10f, WORLD_WIDTH - DIALOG_W - 10f);
    }

    private float dialogY(Node node) {
        float dy = node.y + NODE_RADIUS + 10f;
        if (dy + DIALOG_H > MAP_TOP - 14) dy = node.y - NODE_RADIUS - 10f - DIALOG_H;
        return MathUtils.clamp(dy, 8f, MAP_TOP - 14 - DIALOG_H);
    }

    /** The ship's AI briefs the captain on the encounter at this node. */
    private String encounterPrompt(Node.Type type) {
        switch (type) {
            case LOOT:
                return "Captain, I have detected a cluster of derelict cargo in our vicinity. How shall we proceed?";
            case TRADER:
                return "Captain, a licensed trade barge is hailing us. Their holds are open for business.";
            case COMBAT:
                return "Captain, hostile fighters are dropping from cruise on an intercept vector.";
            default:
                return "Captain, awaiting your orders.";
        }
    }

    private String encounterOption1(Node.Type type) {
        switch (type) {
            case LOOT:   return "1. Dispatch a swarm of tractor drones to collect the cluster.";
            case TRADER: return "1. Dock and browse their wares.";
            case COMBAT: return "1. Scramble to the guns.";
            default:     return "1. Proceed.";
        }
    }

    private String encounterOption2(Node.Type type) {
        switch (type) {
            case LOOT:   return "2. Ignore the cluster.";
            case TRADER: return "2. Ignore the hail.";
            case COMBAT: return "2. Burn hard and slip past them.";
            default:     return "2. Hold position.";
        }
    }

    private void drawDialog(Node node) {
        float dx = dialogX(node);
        float dy = dialogY(node);

        // dialog background
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.06f, 0.08f, 0.09f, 1f);
        shapes.rect(dx, dy, DIALOG_W, DIALOG_H);
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.3f, 0.75f, 0.85f, 1f);
        shapes.rect(dx, dy, DIALOG_W, DIALOG_H);
        Rectangle o1 = option1Btn(dx, dy);
        shapes.setColor(Color.GREEN);
        shapes.rect(o1.x, o1.y, o1.width, o1.height);
        Rectangle o2 = option2Btn(dx, dy);
        shapes.setColor(Color.GRAY);
        shapes.rect(o2.x, o2.y, o2.width, o2.height);
        shapes.end();

        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();
        font.getData().setScale(1f);
        font.setColor(0.55f, 0.85f, 0.95f, 1f); // the ship AI speaks in console cyan
        font.draw(batch, encounterPrompt(node.type),
            dx + 12, dy + DIALOG_H - 10, DIALOG_W - 24, Align.left, true);
        font.setColor(0.5f, 1f, 0.6f, 1f);
        font.draw(batch, encounterOption1(node.type),
            o1.x + 8, o1.y + o1.height - 8, o1.width - 16, Align.left, true);
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, encounterOption2(node.type),
            o2.x + 8, o2.y + o2.height - 8, o2.width - 16, Align.left, true);
        font.getData().setScale(1.4f);
        batch.end();
    }

    private Rectangle option1Btn(float dx, float dy) {
        return new Rectangle(dx + 10f, dy + 46f, DIALOG_W - 20f, 48f);
    }

    private Rectangle option2Btn(float dx, float dy) {
        return new Rectangle(dx + 10f, dy + 8f, DIALOG_W - 20f, 32f);
    }

    private Rectangle rosterRow(int i) {
        return new Rectangle(ROSTER_X, ROSTER_TOP - i * ROSTER_ROW_H - 18, ROSTER_W, ROSTER_ROW_H - 1);
    }

    private void updateHover() {
        Vector3 mouse = viewport.getCamera().unproject(
            new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0),
            viewport.getScreenX(), viewport.getScreenY(),
            viewport.getScreenWidth(), viewport.getScreenHeight());
        hoveredRoom = deckView.roomAt(mouse.x, mouse.y);
        hoveredCrew = deckView.crewAt(mouse.x, mouse.y);
        hoveredNode = null;
        for (Node n : state.map.allNodes()) {
            float dx = mouse.x - n.x;
            float dy = mouse.y - n.y;
            if (dx * dx + dy * dy <= NODE_RADIUS * NODE_RADIUS) {
                hoveredNode = n;
                break;
            }
        }
    }

    /** Returns true when the screen was switched and rendering must stop. */
    private boolean handleInput() {
        if (!Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) return false;

        Vector3 mouse = viewport.getCamera().unproject(
            new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0),
            viewport.getScreenX(), viewport.getScreenY(),
            viewport.getScreenWidth(), viewport.getScreenHeight());

        // victory modal swallows all input until the run is closed out
        if (victory) {
            if (VICTORY_BTN.contains(mouse.x, mouse.y)) {
                game.setScreen(new TitleScreen(game));
                return true;
            }
            return false;
        }

        if (selectedNode != null) {
            float dx = dialogX(selectedNode);
            float dy = dialogY(selectedNode);

            if (option1Btn(dx, dy).contains(mouse.x, mouse.y)) {
                return enterNode(selectedNode); // engage the encounter
            }
            if (option2Btn(dx, dy).contains(mouse.x, mouse.y)) {
                // ignore the encounter: still travel to the node, just don't enter the instance
                Node node = selectedNode;
                selectedNode = null;
                travelTo(node);
                return false;
            }
            // click outside the dialog = dismiss it
            selectedNode = null;
            return false;
        }

        // blueprint section: crew stationing
        if (mouse.y > MAP_TOP) {
            for (int i = 0; i < state.crew.size(); i++) {
                if (rosterRow(i).contains(mouse.x, mouse.y)) {
                    CrewMember clicked = state.crew.get(i);
                    selectedCrew = clicked == selectedCrew ? null : clicked;
                    return false;
                }
            }
            // airlock doors toggle on click, without disturbing crew selection
            int door = deckView.doorAt(mouse.x, mouse.y);
            if (door != -1) {
                deckView.toggleDoor(door);
                return false;
            }
            // clicking a figure selects them — unless someone is already selected,
            // in which case the click keeps its stationing meaning
            if (selectedCrew == null && hoveredCrew != null) {
                selectedCrew = hoveredCrew;
                return false;
            }
            if (selectedCrew != null) {
                if (hoveredRoom != -1) {
                    selectedCrew.station = hoveredRoom;
                    selectedCrew.assignedAt = state.nextStationSeq(); // back of the station queue
                }
                selectedCrew = null; // stationed, or clicked empty space = deselect
            }
            return false;
        }

        if (hoveredNode != null && state.map.isReachable(hoveredNode.id)) {
            if (hoveredNode.id == state.map.lastNodeId) {
                // reaching END wins the run
                if (travelTo(hoveredNode)) victory = true;
            } else if (hoveredNode.type == Node.Type.HOME) {
                // HOME has nothing to enter: travel there directly, no dialog
                travelTo(hoveredNode);
            } else {
                selectedNode = hoveredNode;
            }
        }
        return false;
    }

    /** Moving to another node costs fuel; returns false when the tank is dry. */
    private boolean travelTo(Node node) {
        if (node.id == state.map.getCurrentNode().id) return true; // already here, no cost
        if (state.fuel < TRAVEL_FUEL_COST) return false;
        state.fuel -= TRAVEL_FUEL_COST;
        state.map.setCurrentNode(node.id);
        return true;
    }

    /** Returns true when a new screen was set. */
    private boolean enterNode(Node node) {
        selectedNode = null;
        if (!travelTo(node)) return false; // out of fuel: stay put
        switch (node.type) {
            case COMBAT: game.setScreen(new GameScreen(game, state)); return true;
            case TRADER: game.setScreen(new TraderScreen(game, state)); return true;
            case LOOT:   game.setScreen(new LootScreen(game, state)); return true;
            default: return false; // HOME: stay on map
        }
    }

    private Color nodeColor(Node n) {
        switch (n.type) {
            case HOME:   return Color.GRAY;
            case COMBAT: return Color.RED;
            case TRADER: return Color.CYAN;
            case LOOT:   return Color.YELLOW;
            default:     return Color.WHITE;
        }
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
        shapes.dispose();
        batch.dispose();
        font.dispose();
    }
}
