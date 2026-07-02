package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

public class Player {
    public static final float WIDTH = 40f;
    public static final float HEIGHT = 60f;
    public static final float MAX_SPEED = 280f;

    public static final int THROTTLE_STEPS = 10;

    private static final float MAX_THRUST = 176f;
    private static final float THRUST_SPINUP = 1.12f;  // fraction of full thrust gained per second
    private static final float THRUST_SPINDOWN = 2.0f; // fraction lost per second when throttling down
    // rotation has inertia too: keys apply torque, spin decays slowly
    private static final float ANGULAR_ACCEL = 420f;      // deg/s² while a rotate key is held
    private static final float MAX_ANGULAR_SPEED = 240f;  // deg/s
    private static final float ANGULAR_DAMPING = 1.6f;    // exponential decay per second
    // same physics as the cargo: drag slows the ship, but it never sits fully still
    private static final float DRAG = 1.2f;      // exponential damping per second (matches CARGO_DRAG)
    private static final float MIN_DRIFT = 4f;   // px/s floor (matches CARGO_MIN_DRIFT)

    public float x;
    public float y;
    public float vx;
    public float vy;
    public float rotation;
    public float thrustMult = 1f; // engine tuning upgrades scale the burn
    public float turnMult = 1f;   // helm damage cripples turning
    public float angularVel;
    public int throttle;      // 0..THROTTLE_STEPS, set in 10% steps
    public float thrustLevel; // 0 to 1, ramps toward throttle setting

    public Player(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void throttleUp() {
        throttle = Math.min(THROTTLE_STEPS, throttle + 1);
    }

    public void throttleDown() {
        throttle = Math.max(0, throttle - 1);
    }

    /** Burns continuously at the throttle setting; thrustLevel ramps toward it. */
    public void updateThrust(float delta, boolean hasFuel) {
        float target = hasFuel ? throttle / (float) THROTTLE_STEPS : 0f;
        if (thrustLevel < target) {
            thrustLevel = Math.min(target, thrustLevel + THRUST_SPINUP * delta);
        } else {
            thrustLevel = Math.max(target, thrustLevel - THRUST_SPINDOWN * delta);
        }
        if (thrustLevel > 0f) {
            vx += -MathUtils.sinDeg(rotation) * MAX_THRUST * thrustMult * thrustLevel * delta;
            vy +=  MathUtils.cosDeg(rotation) * MAX_THRUST * thrustMult * thrustLevel * delta;
            clampSpeed();
        }
    }

    public void updatePosition(float delta) {
        rotation += angularVel * delta;
        angularVel *= (float) Math.exp(-ANGULAR_DAMPING * delta);
        float damping = (float) Math.exp(-DRAG * delta);
        vx *= damping;
        vy *= damping;
        keepDrifting();
        x += vx * delta;
        y += vy * delta;
    }

    /** Drag never stops the ship completely: below the drift floor, velocity is scaled back up. */
    private void keepDrifting() {
        float speed2 = vx * vx + vy * vy;
        if (speed2 >= MIN_DRIFT * MIN_DRIFT) return;
        if (speed2 < 0.0001f) {
            float angle = MathUtils.random(360f);
            vx = MathUtils.cosDeg(angle) * MIN_DRIFT;
            vy = MathUtils.sinDeg(angle) * MIN_DRIFT;
            return;
        }
        float scale = MIN_DRIFT / (float) Math.sqrt(speed2);
        vx *= scale;
        vy *= scale;
    }

    /**
     * Wraps with period = world size so a ghost drawn one world-width away lines up
     * seamlessly with where the ship reappears. Returns true when a wrap happened.
     */
    public boolean wrapAround(float worldWidth, float worldHeight) {
        boolean wrapped = false;
        if (x > worldWidth) { x -= worldWidth; wrapped = true; }
        else if (x < -WIDTH) { x += worldWidth; wrapped = true; }
        if (y > worldHeight) { y -= worldHeight; wrapped = true; }
        else if (y < -HEIGHT) { y += worldHeight; wrapped = true; }
        return wrapped;
    }

    public void rotateLeft(float delta) {
        angularVel = Math.min(MAX_ANGULAR_SPEED, angularVel + ANGULAR_ACCEL * turnMult * delta);
    }

    public void rotateRight(float delta) {
        angularVel = Math.max(-MAX_ANGULAR_SPEED, angularVel - ANGULAR_ACCEL * turnMult * delta);
    }

    private void clampSpeed() {
        float speed = (float) Math.sqrt(vx * vx + vy * vy);
        if (speed > MAX_SPEED) {
            vx = vx / speed * MAX_SPEED;
            vy = vy / speed * MAX_SPEED;
        }
    }
}
