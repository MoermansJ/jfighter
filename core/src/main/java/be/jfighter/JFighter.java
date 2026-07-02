package be.jfighter;

import com.badlogic.gdx.Game;

public class JFighter extends Game {
    // shared 16:9 virtual resolution; every screen's FitViewport uses this
    public static final float WORLD_WIDTH = 960f;
    public static final float WORLD_HEIGHT = 540f;

    public SoundFx sfx;

    @Override
    public void create() {
        sfx = new SoundFx();
        setScreen(new TitleScreen(this));
    }

    @Override
    public void dispose() {
        super.dispose();
        sfx.dispose();
    }
}
