package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

public class Projectile {
    public static final float SPEED = 400f;
    public static final float RADIUS = 5f;

    public float x;
    public float y;
    private final float vx;
    private final float vy;

    public Projectile(float originX, float originY, float rotation) {
        this.x = originX;
        this.y = originY;
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
