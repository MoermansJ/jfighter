package be.jfighter;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

/** Central switch for all development overrides. Every dev behaviour hangs off MODE. */
public final class Dev {
    /**
     * Master dev switch: escape rolls always succeed, fuel/credits are infinite,
     * ??? nodes are revealed, instances stay escapable/restartable.
     */
    public static final boolean MODE = true;

    private Dev() {
    }

    /** Persistent on-screen marker so a dev build is never mistaken for the real game. Call inside a batch. */
    public static void drawIndicator(SpriteBatch batch, BitmapFont font, float screenW, float screenH) {
        if (!MODE) return;
        float oldScaleX = font.getData().scaleX;
        float oldScaleY = font.getData().scaleY;
        Fonts.scale(font, 1f);
        font.setColor(1f, 0.3f, 0.25f, 1f);
        GlyphLayout gl = new GlyphLayout(font, "DEV");
        font.draw(batch, gl, (screenW - gl.width) / 2f, screenH - 8);
        font.getData().setScale(oldScaleX, oldScaleY); // raw restore: values already include the unit
    }
}
