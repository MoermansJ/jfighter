package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

public class Projectile {
    public static final float SPEED = 400f;

    public float x;
    public float y;
    public final float rotation;
    public final Object shooter; // the firing ship never hits itself
    private final float vx;
    private final float vy;

    public Projectile(float originX, float originY, float rotation, Object shooter) {
        this.x = originX;
        this.y = originY;
        this.rotation = rotation;
        this.shooter = shooter;
        this.vx = -MathUtils.sinDeg(rotation);
        this.vy = MathUtils.cosDeg(rotation);
    }

    public void update(float delta) {
        x += vx * SPEED * delta;
        y += vy * SPEED * delta;
    }

    public boolean isOutOfBounds(float worldWidth, float worldHeight) {
        return x < 0 || x > worldWidth || y < 0 || y > worldHeight;
    }
}
