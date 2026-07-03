package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

/**
 * A fired shell or rocket. Direction is re-derived from rotation every frame so
 * rockets can accelerate and homing rockets can steer with a limited turn rate.
 */
public class Projectile {
    public float x;
    public float y;
    public float rotation;
    public final Object shooter; // the firing ship never hits itself
    public final float damage;
    public final boolean rocket; // rockets trail flame and splash on impact
    public Player target;        // homing target, or null
    public float life = 7f;
    // interception failure modes (#125): 0 = healthy, 1 = engine-out drift, 2 = guidance-hit corkscrew
    public int failMode;
    public float failT;   // time until the failed rocket fizzles/detonates
    public float smokeT;  // smoke-puff emission clock

    private float speed;
    private float accel;
    private float turnRate; // deg/s toward the target; 0 = dumb-fire

    /** Classic cannon shell. */
    public Projectile(float originX, float originY, float rotation, Object shooter) {
        this(originX, originY, rotation, shooter, 400f, 10f, 0f, 0f, false);
    }

    public Projectile(float originX, float originY, float rotation, Object shooter,
                      float speed, float damage, float accel, float turnRate, boolean rocket) {
        this.x = originX;
        this.y = originY;
        this.rotation = rotation;
        this.shooter = shooter;
        this.speed = speed;
        this.damage = damage;
        this.accel = accel;
        this.turnRate = turnRate;
        this.rocket = rocket;
    }

    /** An intercepting hit cripples the rocket instead of always killing it. */
    public void cripple(int mode) {
        failMode = mode;
        if (mode == 1) { // engine-out: thrust dies, it drifts off ballistically and fizzles
            accel = -60f;
            turnRate = 0f;
            target = null;
            failT = MathUtils.random(1.5f, 3f);
        } else { // guidance-hit: veers into a corkscrew, then detonates
            turnRate = 0f;
            target = null;
            failT = MathUtils.random(0.6f, 1.4f);
        }
    }

    public void update(float delta) {
        if (failMode == 2) rotation += 260f * delta; // corkscrewing off course
        if (failMode == 1) speed = Math.max(40f, speed);
        if (turnRate > 0 && target != null) {
            float dx = target.x + Player.WIDTH / 2f - x;
            float dy = target.y + Player.HEIGHT / 2f - y;
            float desired = MathUtils.atan2(-dx, dy) * MathUtils.radiansToDegrees;
            float err = ((desired - rotation) % 360f + 540f) % 360f - 180f;
            float maxTurn = turnRate * delta;
            rotation += MathUtils.clamp(err, -maxTurn, maxTurn);
        }
        speed += accel * delta;
        x += -MathUtils.sinDeg(rotation) * speed * delta;
        y += MathUtils.cosDeg(rotation) * speed * delta;
        life -= delta;
    }

    public float velX() {
        return -MathUtils.sinDeg(rotation) * speed;
    }

    public float velY() {
        return MathUtils.cosDeg(rotation) * speed;
    }

    public boolean isOutOfBounds(float worldWidth, float worldHeight) {
        return life <= 0 || x < 0 || x > worldWidth || y < 0 || y > worldHeight;
    }
}
