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

    /** Salvage craft for the loot instance: stubby pod with two forward pincer jaws. Same engine layout as the B-2. */
    public static void drawPincer(ShapeRenderer shapes) {
        // hull pod
        shapes.line(-12, 12, 12, 12);
        shapes.line(12, 12, 16, 0);
        shapes.line(16, 0, 12, -10);
        shapes.line(12, -10, -12, -10);
        shapes.line(-12, -10, -16, 0);
        shapes.line(-16, 0, -12, 12);
        shapes.circle(0, 4, 4, 10); // cockpit
        // pincer arms: outer edge curling in at the jaw tips
        shapes.line(12, 12, 20, 20);
        shapes.line(20, 20, 22, 30);
        shapes.line(22, 30, 16, 38);
        shapes.line(16, 38, 10, 40);
        shapes.line(-12, 12, -20, 20);
        shapes.line(-20, 20, -22, 30);
        shapes.line(-22, 30, -16, 38);
        shapes.line(-16, 38, -10, 40);
        // inner jaw edges
        shapes.line(12, 12, 14, 24);
        shapes.line(14, 24, 8, 34);
        shapes.line(-12, 12, -14, 24);
        shapes.line(-14, 24, -8, 34);
        // engine exhaust slots (nozzles match drawExhaust)
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
