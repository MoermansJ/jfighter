package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

public class OverworldScreen implements Screen {
    private static final float NODE_RADIUS = 18f;
    private static final float DIALOG_W = 200f;
    private static final float DIALOG_H = 100f;
    private static final float BTN_W = 72f;
    private static final float BTN_H = 28f;

    private final JFighter game;
    private final GameState state;

    private FitViewport viewport;
    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;

    private Node hoveredNode;
    private Node selectedNode; // node whose dialog is open

    public OverworldScreen(JFighter game, GameState state) {
        this.game = game;
        this.state = state;
    }

    @Override
    public void show() {
        viewport = new FitViewport(640, 480);
        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont();
        font.getData().setScale(1.4f);
    }

    @Override
    public void render(float delta) {
        updateHover();
        handleInput();

        ScreenUtils.clear(0, 0, 0, 1f);
        viewport.apply();

        shapes.setProjectionMatrix(viewport.getCamera().combined);

        // connection lines
        shapes.begin(ShapeRenderer.ShapeType.Line);
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
        font.setColor(Color.WHITE);
        for (Node n : state.map.allNodes()) {
            String label = n.type.name();
            GlyphLayout gl = new GlyphLayout(font, label);
            font.draw(batch, label, n.x - gl.width / 2f, n.y - NODE_RADIUS - 6f);
        }
        font.setColor(Color.YELLOW);
        font.draw(batch, "Credits: " + state.credits, 10, 470);
        batch.end();
    }

    private void drawDialog(Node node) {
        float dx = node.x - DIALOG_W / 2f;
        float dy = node.y + NODE_RADIUS + 10f;
        if (dy + DIALOG_H > 470) dy = node.y - NODE_RADIUS - 10f - DIALOG_H;

        // dialog background
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.1f, 0.1f, 0.1f, 1f);
        shapes.rect(dx, dy, DIALOG_W, DIALOG_H);
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(Color.GREEN);
        shapes.rect(dx, dy, DIALOG_W, DIALOG_H);

        // enter button
        Rectangle ebl = enterBtn(dx, dy);
        shapes.setColor(Color.GREEN);
        shapes.rect(ebl.x, ebl.y, ebl.width, ebl.height);
        // cancel button
        Rectangle cbl = cancelBtn(dx, dy);
        shapes.setColor(Color.GRAY);
        shapes.rect(cbl.x, cbl.y, cbl.width, cbl.height);
        shapes.end();

        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();
        font.setColor(Color.WHITE);
        String title = "Enter " + node.type.name() + "?";
        GlyphLayout gl = new GlyphLayout(font, title);
        font.draw(batch, title, dx + (DIALOG_W - gl.width) / 2f, dy + DIALOG_H - 10f);

        font.setColor(Color.GREEN);
        Rectangle eb = enterBtn(dx, dy);
        GlyphLayout eg = new GlyphLayout(font, "ENTER");
        font.draw(batch, "ENTER", eb.x + (BTN_W - eg.width) / 2f, eb.y + (BTN_H + eg.height) / 2f);

        font.setColor(Color.LIGHT_GRAY);
        Rectangle cb = cancelBtn(dx, dy);
        GlyphLayout cg = new GlyphLayout(font, "CANCEL");
        font.draw(batch, "CANCEL", cb.x + (BTN_W - cg.width) / 2f, cb.y + (BTN_H + cg.height) / 2f);
        batch.end();
    }

    private Rectangle enterBtn(float dx, float dy) {
        return new Rectangle(dx + 14f, dy + 14f, BTN_W, BTN_H);
    }

    private Rectangle cancelBtn(float dx, float dy) {
        return new Rectangle(dx + DIALOG_W - BTN_W - 14f, dy + 14f, BTN_W, BTN_H);
    }

    private void updateHover() {
        Vector3 mouse = viewport.getCamera().unproject(
            new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0),
            viewport.getScreenX(), viewport.getScreenY(),
            viewport.getScreenWidth(), viewport.getScreenHeight());
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

    private void handleInput() {
        if (!Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) return;

        Vector3 mouse = viewport.getCamera().unproject(
            new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0),
            viewport.getScreenX(), viewport.getScreenY(),
            viewport.getScreenWidth(), viewport.getScreenHeight());

        if (selectedNode != null) {
            float dx = selectedNode.x - DIALOG_W / 2f;
            float dy = selectedNode.y + NODE_RADIUS + 10f;
            if (dy + DIALOG_H > 470) dy = selectedNode.y - NODE_RADIUS - 10f - DIALOG_H;

            if (enterBtn(dx, dy).contains(mouse.x, mouse.y)) {
                enterNode(selectedNode);
                return;
            }
            if (cancelBtn(dx, dy).contains(mouse.x, mouse.y)) {
                selectedNode = null;
                return;
            }
            // click outside dialog = close it
            selectedNode = null;
            return;
        }

        if (hoveredNode != null && state.map.isReachable(hoveredNode.id)) {
            selectedNode = hoveredNode;
        }
    }

    private void enterNode(Node node) {
        state.map.setCurrentNode(node.id);
        selectedNode = null;
        switch (node.type) {
            case COMBAT: game.setScreen(new GameScreen(game, state)); break;
            case TRADER: game.setScreen(new TraderScreen(game, state)); break;
            case LOOT:   game.setScreen(new LootScreen(game, state)); break;
            default: break; // HOME: stay on map
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
