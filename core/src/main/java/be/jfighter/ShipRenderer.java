package be.jfighter;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Draws the B-2 wireframe around the local origin. Caller sets the
 * transform matrix (translation + rotation) and Line shape type first.
 */
public final class ShipRenderer {

    private ShipRenderer() {
    }

    public static void drawB2(ShapeRenderer shapes) {
        // leading edges
        shapes.line(0, 20, 28, 0);
        shapes.line(0, 20, -28, 0);
        // trailing edge W (right)
        shapes.line(28, 0, 22, -12);
        shapes.line(22, -12, 14, -4);
        shapes.line(14, -4, 6, -16);
        shapes.line(6, -16, 0, -10);
        // trailing edge W (left)
        shapes.line(-28, 0, -22, -12);
        shapes.line(-22, -12, -14, -4);
        shapes.line(-14, -4, -6, -16);
        shapes.line(-6, -16, 0, -10);
        // centerline + cockpit
        shapes.line(0, 18, 0, -10);
        shapes.circle(0, 14, 3, 8);
        // engine exhaust slots
        shapes.line(-14, -2, -10, -10);
        shapes.line(-10, -2, -6, -10);
        shapes.line(6, -10, 10, -2);
        shapes.line(10, -10, 14, -2);
    }

    public static void drawExhaust(ShapeRenderer shapes, float thrustLevel) {
        float sz = thrustLevel * 14f;
        shapes.line(-14, -10, -10, -10 - sz);
        shapes.line(-6,  -10, -10, -10 - sz);
        shapes.line(6,   -10,  10, -10 - sz);
        shapes.line(14,  -10,  10, -10 - sz);
    }
}
