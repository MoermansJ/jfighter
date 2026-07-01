package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

public class Player {
    public static final float WIDTH = 40f;
    public static final float HEIGHT = 60f;
    public static final float SPEED = 200f;
    public static final float ROTATION_SPEED = 180f;

    public float x;
    public float y;
    public float rotation;

    public Player(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void moveForward(float delta) {
        x += -MathUtils.sinDeg(rotation) * SPEED * delta;
        y += MathUtils.cosDeg(rotation) * SPEED * delta;
    }

    public void moveBackward(float delta) {
        x -= -MathUtils.sinDeg(rotation) * SPEED * delta;
        y -= MathUtils.cosDeg(rotation) * SPEED * delta;
    }

    public void rotateLeft(float delta) {
        rotation += ROTATION_SPEED * delta;
    }

    public void rotateRight(float delta) {
        rotation -= ROTATION_SPEED * delta;
    }
}
