package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;

/**
 * Bottom-left keycap-style controls help, shared by the flight instances:
 * an [H] show-controls prompt that expands into the screen's control scheme.
 */
public class ControlsHelp {
    private static final float ROW_H = 27f;

    private final String[][] controls; // {key, description} rows
    private boolean show;

    public ControlsHelp(String[][] controls) {
        this.controls = controls;
    }

    /** Call once per frame from the screen's input handling; H toggles. */
    public void handleInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.H)) show = !show;
    }

    /** Draws boxes + text through the given HUD projection. Call outside any batch/shape pass. */
    public void draw(ShapeRenderer shapes, SpriteBatch batch, BitmapFont font, Matrix4 hudMatrix) {
        Array<String[]> rows = new Array<>();
        if (show) {
            rows.add(new String[]{"H", "hide controls"});
            for (String[] c : controls) rows.add(c);
        } else {
            rows.add(new String[]{"H", "show controls"});
        }
        float[] capW = new float[rows.size];
        for (int i = 0; i < rows.size; i++) {
            capW[i] = new GlyphLayout(font, rows.get(i)[0]).width + 12f;
        }

        shapes.setProjectionMatrix(hudMatrix);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.55f, 0.55f, 0.55f, 1f);
        for (int i = 0; i < rows.size; i++) {
            float y = 10 + (rows.size - 1 - i) * ROW_H;
            shapes.rect(10, y, capW[i], 22);
            shapes.line(12, y + 3, 8 + capW[i], y + 3); // keycap base edge
        }
        shapes.end();

        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        for (int i = 0; i < rows.size; i++) {
            float y = 10 + (rows.size - 1 - i) * ROW_H;
            font.setColor(0.85f, 0.85f, 0.85f, 1f);
            font.draw(batch, rows.get(i)[0], 16, y + 19);
            font.setColor(0.55f, 0.55f, 0.55f, 1f);
            font.draw(batch, rows.get(i)[1], 10 + capW[i] + 10, y + 19);
        }
        batch.end();
    }
}
