package be.jfighter;

public class Player {
    public static final float WIDTH = 40f;
    public static final float HEIGHT = 60f;
    public static final float SPEED = 200f;

    public float x;
    public float y;

    public Player(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void moveUp(float delta) {
        y += SPEED * delta;
    }

    public void moveDown(float delta) {
        y -= SPEED * delta;
    }
}
