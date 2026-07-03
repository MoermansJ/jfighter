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

    /** The mothership, top-down (#117/#132): an elongated dagger with layered plates. */
    public static void drawCarrier(ShapeRenderer shapes) {
        // dagger hull outline (nose +y): long taper to a point
        shapes.line(0, 120, 14, 60);
        shapes.line(14, 60, 30, -20);
        shapes.line(30, -20, 36, -70);
        shapes.line(36, -70, -36, -70);
        shapes.line(-36, -70, -30, -20);
        shapes.line(-30, -20, -14, 60);
        shapes.line(-14, 60, 0, 120);
        // layered hull plates: inner terrace lines echoing the taper
        shapes.line(0, 104, 10, 52);
        shapes.line(10, 52, 22, -18);
        shapes.line(22, -18, 26, -62);
        shapes.line(0, 104, -10, 52);
        shapes.line(-10, 52, -22, -18);
        shapes.line(-22, -18, -26, -62);
        shapes.line(0, 88, 6, 44);
        shapes.line(6, 44, 14, -16);
        shapes.line(0, 88, -6, 44);
        shapes.line(-6, 44, -14, -16);
        // spine
        shapes.line(0, 112, 0, -60);
        // superstructure terraces + bridge tower, aft
        shapes.line(-16, -30, 16, -30);
        shapes.line(-12, -30, -12, -44);
        shapes.line(12, -30, 12, -44);
        shapes.line(-12, -44, 12, -44);
        shapes.line(-6, -44, -6, -54);
        shapes.line(6, -44, 6, -54);
        shapes.line(-6, -54, 6, -54);
        shapes.circle(0, -49, 2.5f, 8); // bridge globe
        // hangar notch, portside midships
        shapes.line(-30, -8, -20, -8);
        shapes.line(-20, -8, -20, -32);
        shapes.line(-20, -32, -32, -32);
        // engine array
        shapes.line(-26, -70, -26, -80);
        shapes.line(-14, -70, -14, -84);
        shapes.line(0, -70, 0, -86);
        shapes.line(14, -70, 14, -84);
        shapes.line(26, -70, 26, -80);
        // cupola mounts on the flanks
        shapes.circle(-24, 8, 3f, 8);
        shapes.circle(24, 8, 3f, 8);
        // heavy mount ring, forward third
        shapes.circle(0, 56, 5f, 10);
        // medium sockets, midships
        shapes.circle(-16, 22, 3.2f, 8);
        shapes.circle(16, 22, 3.2f, 8);
    }

    public static void drawExhaust(ShapeRenderer shapes, float thrustLevel) {
        float sz = thrustLevel * 14f;
        shapes.line(-14, -10, -10, -10 - sz);
        shapes.line(-6,  -10, -10, -10 - sz);
        shapes.line(6,   -10,  10, -10 - sz);
        shapes.line(14,  -10,  10, -10 - sz);
    }
}
