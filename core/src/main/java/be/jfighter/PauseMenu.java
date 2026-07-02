package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.viewport.Viewport;

/**
 * ESC pause overlay for the flight instances. Instances can't be abandoned until
 * resolved (combat: hostiles cleared; loot: cargo dealt with) — leaving early is a
 * dev-mode override. The sim freezes while the menu is open.
 */
public class PauseMenu {
    private static final float W = JFighter.WORLD_WIDTH;
    private static final float H = JFighter.WORLD_HEIGHT;
    private final Rectangle resumeBtn = new Rectangle(W / 2f - 120, 262, 240, 38);
    private final Rectangle leaveBtn = new Rectangle(W / 2f - 120, 212, 240, 38);
    private boolean open;

    /** ESC toggles the menu. Call once per frame before anything else consumes ESC. */
    public void handleEscape() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) open = !open;
    }

    public boolean isOpen() {
        return open;
    }

    /** Draws the overlay and handles its clicks. Returns true when LEAVE was chosen. */
    public boolean render(ShapeRenderer shapes, SpriteBatch batch, BitmapFont font,
                          Matrix4 hudMatrix, Viewport viewport, boolean resolved) {
        boolean canLeave = resolved || Dev.MODE;
        float mx = hudX(viewport);
        float my = hudY(viewport);
        boolean overResume = resumeBtn.contains(mx, my);
        boolean overLeave = canLeave && leaveBtn.contains(mx, my);
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            if (overResume) {
                open = false;
            } else if (overLeave) {
                open = false;
                return true;
            }
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(hudMatrix);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.6f);
        shapes.rect(0, 0, W, H);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(overResume ? Color.YELLOW : Color.WHITE);
        shapes.rect(resumeBtn.x, resumeBtn.y, resumeBtn.width, resumeBtn.height);
        if (canLeave) {
            shapes.setColor(overLeave ? Color.YELLOW : resolved ? Color.GREEN : Color.ORANGE);
            shapes.rect(leaveBtn.x, leaveBtn.y, leaveBtn.width, leaveBtn.height);
        }
        shapes.end();

        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 2f);
        font.setColor(Color.WHITE);
        GlyphLayout title = new GlyphLayout(font, "PAUSED");
        font.draw(batch, title, (W - title.width) / 2f, 380);
        Fonts.scale(font, 1.4f);
        drawCentered(batch, font, "RESUME", resumeBtn, overResume ? Color.YELLOW : Color.WHITE);
        if (canLeave) {
            String label = resolved ? "LEAVE INSTANCE" : "LEAVE (DEV)";
            drawCentered(batch, font, label, leaveBtn,
                overLeave ? Color.YELLOW : resolved ? Color.GREEN : Color.ORANGE);
        } else {
            font.setColor(Color.GRAY);
            GlyphLayout locked = new GlyphLayout(font, "unresolved — no way out but through");
            font.draw(batch, locked, (W - locked.width) / 2f, leaveBtn.y + 26);
        }
        batch.end();
        return false;
    }

    private void drawCentered(SpriteBatch batch, BitmapFont font, String text, Rectangle r, Color c) {
        font.setColor(c);
        GlyphLayout gl = new GlyphLayout(font, text);
        font.draw(batch, gl, r.x + (r.width - gl.width) / 2f, r.y + (r.height + gl.height) / 2f);
    }

    /** Mouse position in 960x540 HUD coords, derived from the viewport's screen rect. */
    private static float hudX(Viewport viewport) {
        return (Gdx.input.getX() - viewport.getScreenX()) / (float) viewport.getScreenWidth() * W;
    }

    private static float hudY(Viewport viewport) {
        float fromBottom = Gdx.graphics.getHeight() - Gdx.input.getY();
        return (fromBottom - viewport.getScreenY()) / (float) viewport.getScreenHeight() * H;
    }
}
