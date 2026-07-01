package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

public class Player {
    public static final float WIDTH = 40f;
    public static final float HEIGHT = 60f;
    public static final float ROTATION_SPEED = 180f;

    private static final float MAX_THRUST = 220f;
    private static final float THRUST_SPINUP = 0.7f;   // fraction of full thrust gained per second
    private static final float THRUST_SPINDOWN = 2.0f; // fraction lost per second when released
    private static final float MAX_SPEED = 280f;
    private static final float BRAKE_FORCE = 140f;

    public float x;
    public float y;
    public float vx;
    public float vy;
    public float rotation;
    public float thrustLevel; // 0 to 1

    public Player(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void applyThrust(float delta) {
        thrustLevel = Math.min(1f, thrustLevel + THRUST_SPINUP * delta);
        vx += -MathUtils.sinDeg(rotation) * MAX_THRUST * thrustLevel * delta;
        vy +=  MathUtils.cosDeg(rotation) * MAX_THRUST * thrustLevel * delta;
        clampSpeed();
    }

    public void spinDownThrust(float delta) {
        thrustLevel = Math.max(0f, thrustLevel - THRUST_SPINDOWN * delta);
    }

    public void applyBrake(float delta) {
        vx -= -MathUtils.sinDeg(rotation) * BRAKE_FORCE * delta;
        vy -=  MathUtils.cosDeg(rotation) * BRAKE_FORCE * delta;
        clampSpeed();
    }

    public void updatePosition(float delta) {
        x += vx * delta;
        y += vy * delta;
    }

    public void rotateLeft(float delta) {
        rotation += ROTATION_SPEED * delta;
    }

    public void rotateRight(float delta) {
        rotation -= ROTATION_SPEED * delta;
    }

    private void clampSpeed() {
        float speed = (float) Math.sqrt(vx * vx + vy * vy);
        if (speed > MAX_SPEED) {
            vx = vx / speed * MAX_SPEED;
            vy = vy / speed * MAX_SPEED;
        }
    }
}
