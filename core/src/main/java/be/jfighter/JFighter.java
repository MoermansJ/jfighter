package be.jfighter;

import com.badlogic.gdx.Game;

public class JFighter extends Game {
    @Override
    public void create() {
        setScreen(new TitleScreen());
    }
}
