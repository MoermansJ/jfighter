package be.jfighter;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;

/**
 * Draws the B-2 wireframe around the local origin. Caller sets the
 * transform matrix (translation + rotation) and Line shape type first.
 */
public final class ShipRenderer {

    private ShipRenderer() {
    }

    public static void drawB2(ShapeRenderer shapes) {
        drawB2Core(shapes);
        drawB2Wing(shapes, false);
        drawB2Wing(shapes, true);
    }

    /** Fuselage: centre section, cockpit, inner trailing edge. */
    public static void drawB2Core(ShapeRenderer shapes) {
        shapes.line(0, 20, 14, 10); // inner leading edges
        shapes.line(0, 20, -14, 10);
        shapes.line(14, -4, 6, -16);
        shapes.line(6, -16, 0, -10);
        shapes.line(-14, -4, -6, -16);
        shapes.line(-6, -16, 0, -10);
        shapes.line(0, 18, 0, -10);
        shapes.circle(0, 14, 3, 8);
        shapes.line(-10, -2, -6, -10);
        shapes.line(6, -10, 10, -2);
    }

    /** One outer wing; mirrored for the left side. Sections can be shot away (#73). */
    public static void drawB2Wing(ShapeRenderer shapes, boolean left) {
        float s = left ? -1f : 1f;
        shapes.line(14 * s, 10, 28 * s, 0);  // outer leading edge
        shapes.line(28 * s, 0, 22 * s, -12); // wingtip
        shapes.line(22 * s, -12, 14 * s, -4);
        shapes.line(14 * s, -4, 14 * s, 10); // wing root
        shapes.line(14 * s, -2, 10 * s, -10); // outboard engine slot
    }

    /**
     * Salvage craft for the loot instance: stubby pod with two forward pincer jaws.
     * Same engine layout as the B-2. {@code grab} 0..1 pinches the arms inward.
     */
    public static void drawPincer(ShapeRenderer shapes, float grab) {
        // hull pod
        shapes.line(-12, 12, 12, 12);
        shapes.line(12, 12, 16, 0);
        shapes.line(16, 0, 12, -10);
        shapes.line(12, -10, -12, -10);
        shapes.line(-12, -10, -16, 0);
        shapes.line(-16, 0, -12, 12);
        shapes.circle(0, 4, 4, 10); // cockpit
        drawPincerArm(shapes, 1f, grab);
        drawPincerArm(shapes, -1f, grab);
        // engine exhaust slots (nozzles match drawExhaust)
        shapes.line(-14, -2, -10, -10);
        shapes.line(-10, -2, -6, -10);
        shapes.line(6, -10, 10, -2);
        shapes.line(10, -10, 14, -2);
    }

    // arm polylines in right-side coords; mirrored via side, rotated inward around the shoulder by grab
    private static final float[][] ARM_OUTER = {{20, 20}, {22, 30}, {16, 38}, {10, 40}};
    private static final float[][] ARM_INNER = {{14, 24}, {8, 34}};
    private static final float ARM_CLOSE_DEG = 20f;

    private static void drawPincerArm(ShapeRenderer shapes, float side, float grab) {
        float deg = ARM_CLOSE_DEG * grab * side; // CCW closes the right arm, CW the left
        float cos = MathUtils.cosDeg(deg);
        float sin = MathUtils.sinDeg(deg);
        float ox = 12 * side, oy = 12;
        drawArmLine(shapes, ARM_OUTER, side, ox, oy, cos, sin);
        drawArmLine(shapes, ARM_INNER, side, ox, oy, cos, sin);
    }

    private static void drawArmLine(ShapeRenderer shapes, float[][] pts, float side,
                                    float ox, float oy, float cos, float sin) {
        float px = ox, py = oy;
        for (float[] p : pts) {
            float lx = p[0] * side - ox;
            float ly = p[1] - oy;
            float rx = ox + lx * cos - ly * sin;
            float ry = oy + lx * sin + ly * cos;
            shapes.line(px, py, rx, ry);
            px = rx;
            py = ry;
        }
    }

    /** The mothership, top-down (#117): long wedge hull, bridge, engine blocks, hangar notch. */
    public static void drawCarrier(ShapeRenderer shapes) {
        // hull outline (nose +y)
        shapes.line(0, 68, 22, 40);
        shapes.line(22, 40, 28, -10);
        shapes.line(28, -10, 20, -48);
        shapes.line(20, -48, -20, -48);
        shapes.line(-20, -48, -28, -10);
        shapes.line(-28, -10, -22, 40);
        shapes.line(-22, 40, 0, 68);
        // bridge
        shapes.line(-8, 26, 8, 26);
        shapes.line(8, 26, 6, 12);
        shapes.line(6, 12, -6, 12);
        shapes.line(-6, 12, -8, 26);
        shapes.circle(0, 20, 2.5f, 8);
        // spine
        shapes.line(0, 60, 0, -40);
        // hangar notch, portside
        shapes.line(-28, -2, -18, -2);
        shapes.line(-18, -2, -18, -22);
        shapes.line(-18, -22, -28, -22);
        // engine blocks
        shapes.line(-16, -48, -16, -56);
        shapes.line(-6, -48, -6, -58);
        shapes.line(6, -48, 6, -58);
        shapes.line(16, -48, 16, -56);
        // cupola mounts
        shapes.circle(-20, 18, 3f, 8);
        shapes.circle(20, 18, 3f, 8);
        // heavy mount ring, forward
        shapes.circle(0, 44, 4.5f, 10);
    }

    public static void drawExhaust(ShapeRenderer shapes, float thrustLevel) {
        float sz = thrustLevel * 14f;
        shapes.line(-14, -10, -10, -10 - sz);
        shapes.line(-6,  -10, -10, -10 - sz);
        shapes.line(6,   -10,  10, -10 - sz);
        shapes.line(14,  -10,  10, -10 - sz);
    }
}
