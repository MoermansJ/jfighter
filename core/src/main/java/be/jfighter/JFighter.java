package be.jfighter;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Preferences;

public class JFighter extends Game {
    // shared 16:9 virtual resolution; every screen's FitViewport uses this
    public static final float WORLD_WIDTH = 960f;
    public static final float WORLD_HEIGHT = 540f;

    // default windowed size (16:9, matches the launcher)
    public static final int WINDOW_WIDTH = 1440;
    public static final int WINDOW_HEIGHT = 810;

    // windowed presets offered in the options menu (16:9)
    public static final int[][] WINDOW_SIZES = {
        {1280, 720}, {1440, 810}, {1600, 900}, {1920, 1080},
        {2560, 1440}, {3200, 1800}, {3840, 2160}
    };

    public SoundFx sfx;
    public Fonts fonts;

    @Override
    public void create() {
        sfx = new SoundFx();
        applySavedDisplayMode();
        fonts = new Fonts(); // after the display mode: sized to the real backbuffer
        setScreen(new TitleScreen(this));
    }

    @Override
    public void render() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.F11)) {
            setFullscreen(!Gdx.graphics.isFullscreen());
        }
        super.render();
    }

    private Preferences prefs() {
        return Gdx.app.getPreferences("jfighter");
    }

    private void applySavedDisplayMode() {
        Preferences p = prefs();
        if (p.getBoolean("fullscreen", false)) {
            Gdx.graphics.setFullscreenMode(Gdx.graphics.getDisplayMode());
        } else {
            int w = p.getInteger("windowWidth", WINDOW_WIDTH);
            int h = p.getInteger("windowHeight", WINDOW_HEIGHT);
            if (w != Gdx.graphics.getWidth() || h != Gdx.graphics.getHeight()) {
                Gdx.graphics.setWindowedMode(w, h);
            }
        }
    }

    public void setFullscreen(boolean fullscreen) {
        Preferences p = prefs();
        if (fullscreen) {
            Gdx.graphics.setFullscreenMode(Gdx.graphics.getDisplayMode());
        } else {
            Gdx.graphics.setWindowedMode(
                p.getInteger("windowWidth", WINDOW_WIDTH),
                p.getInteger("windowHeight", WINDOW_HEIGHT));
        }
        p.putBoolean("fullscreen", fullscreen);
        p.flush();
    }

    public void setWindowSize(int width, int height) {
        Gdx.graphics.setWindowedMode(width, height);
        Preferences p = prefs();
        p.putBoolean("fullscreen", false);
        p.putInteger("windowWidth", width);
        p.putInteger("windowHeight", height);
        p.flush();
    }

    @Override
    public void dispose() {
        super.dispose();
        sfx.dispose();
        fonts.dispose();
    }
}
