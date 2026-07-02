package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Console colour scheme shared by the deck monitor and the sector map. All console
 * chrome is authored in the default blue and remapped through {@link #set}: pressing
 * a colour button restyles both stacked screens as one unit. Hostiles use
 * {@link #setInverted} so they always contrast with the active scheme.
 */
public final class Palette {
    public enum Scheme { BLUE, GREEN, RED }

    private static Scheme scheme;

    private Palette() {
    }

    public static Scheme scheme() {
        if (scheme == null) {
            try {
                scheme = Scheme.valueOf(
                    Gdx.app.getPreferences("jfighter").getString("consoleScheme", "BLUE"));
            } catch (IllegalArgumentException e) {
                scheme = Scheme.BLUE;
            }
        }
        return scheme;
    }

    public static void setScheme(Scheme s) {
        scheme = s;
        Gdx.app.getPreferences("jfighter").putString("consoleScheme", s.name()).flush();
    }

    /** Remaps a blue-authored colour into the active scheme. */
    public static void set(ShapeRenderer shapes, float r, float g, float b, float a) {
        switch (scheme()) {
            case GREEN: shapes.setColor(r, b, g, a); break;
            case RED:   shapes.setColor(b, g * 0.55f, r * 0.55f, a); break;
            default:    shapes.setColor(r, g, b, a);
        }
    }

    public static void set(ShapeRenderer shapes, com.badlogic.gdx.graphics.Color c) {
        set(shapes, c.r, c.g, c.b, c.a);
    }

    public static void set(BitmapFont font, float r, float g, float b, float a) {
        switch (scheme()) {
            case GREEN: font.setColor(r, b, g, a); break;
            case RED:   font.setColor(b, g * 0.55f, r * 0.55f, a); break;
            default:    font.setColor(r, g, b, a);
        }
    }

    /** The complementary tone of the active scheme — hostiles always stand out. */
    public static void setInverted(ShapeRenderer shapes, float r, float g, float b, float a) {
        switch (scheme()) {
            case GREEN: shapes.setColor(b, r * 0.5f, b * 0.9f, a); break;   // magenta on green
            case RED:   shapes.setColor(r * 0.5f, g, b, a); break;          // cyan on red
            default:    shapes.setColor(b, g * 0.6f, r * 0.4f, a);          // orange on blue
        }
    }
}
