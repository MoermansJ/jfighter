package be.jfighter;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Corner minimap shared by the flight instances: the arena scaled into a small
 * HUD box (top-right). Screens call frame(), then dot()/ring() per object, then
 * border() — all inside their HUD projection.
 */
public class Radar {
    private final float x;
    private final float y;
    private final float w;
    private final float h;
    private final float arenaW;
    private final float arenaH;

    public Radar(float arenaW, float arenaH) {
        this.arenaW = arenaW;
        this.arenaH = arenaH;
        this.w = 150f;
        this.h = w * arenaH / arenaW;
        this.x = JFighter.WORLD_WIDTH - w - 12f;
        this.y = JFighter.WORLD_HEIGHT - h - 46f;
    }

    /** Dark backing plate; call in a Filled pass. */
    public void frame(ShapeRenderer shapes) {
        shapes.setColor(0.02f, 0.04f, 0.05f, 1f);
        shapes.rect(x, y, w, h);
    }

    /** A blip at world coords; call in a Filled pass after frame(). Caller sets the colour. */
    public void dot(ShapeRenderer shapes, float wx, float wy, float r) {
        shapes.circle(px(wx), py(wy), r, 6);
    }

    /** A hollow marker at world coords; call in a Line pass. Caller sets the colour. */
    public void ring(ShapeRenderer shapes, float wx, float wy, float r) {
        shapes.circle(px(wx), py(wy), r, 10);
    }

    /** Border; call in a Line pass. */
    public void border(ShapeRenderer shapes) {
        shapes.setColor(0.3f, 0.4f, 0.45f, 1f);
        shapes.rect(x, y, w, h);
    }

    private float px(float wx) {
        return x + wx / arenaW * w;
    }

    private float py(float wy) {
        return y + wy / arenaH * h;
    }
}
