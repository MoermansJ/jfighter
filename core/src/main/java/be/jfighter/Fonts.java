package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.utils.Disposable;

/**
 * Shared HUD font: JetBrains Mono generated via FreeType at a pixel size matched to
 * the backbuffer, pre-scaled so {@code Fonts.scale(font, 1f)} equals the old default
 * BitmapFont size in 960x540 virtual units — crisp at any window size and the
 * monospaced/technical console look for all GUI labels.
 */
public class Fonts implements Disposable {
    private static final float BASE_PX = 15f; // the old default BitmapFont cap size
    private static float unit = 1f; // virtual scale factor that lands on BASE_PX

    public final BitmapFont font;

    public Fonts() {
        float ss = Math.max(1f, Gdx.graphics.getBackBufferHeight() / JFighter.WORLD_HEIGHT);
        FreeTypeFontGenerator gen =
            new FreeTypeFontGenerator(Gdx.files.internal("JetBrainsMono-Regular.ttf"));
        FreeTypeFontGenerator.FreeTypeFontParameter p =
            new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = Math.round(BASE_PX * ss);
        p.minFilter = Texture.TextureFilter.Linear;
        p.magFilter = Texture.TextureFilter.Linear;
        font = gen.generateFont(p);
        gen.dispose();
        font.setUseIntegerPositions(false);
        unit = 1f / ss;
        scale(font, 1f);
    }

    /** Sets the font to s times the base HUD size — use instead of raw setScale. */
    public static void scale(BitmapFont font, float s) {
        font.getData().setScale(s * unit);
    }

    @Override
    public void dispose() {
        font.dispose();
    }
}
