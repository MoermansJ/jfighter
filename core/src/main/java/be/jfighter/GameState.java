package be.jfighter;

public class GameState {
    public final OverworldMap map;
    public int credits;

    public GameState() {
        this.map = new OverworldMap();
        this.credits = 500;
    }
}
