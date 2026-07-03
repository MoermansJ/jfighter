package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;

import java.util.HashMap;
import java.util.Map;

/**
 * Live "camera feed" of the carrier deck, Nox-style: a steep top-down oblique
 * projection of a flat deck plan, cutaway walls, mostly-dark rooms with flickering
 * light pools that brighten when occupied, and crew figures that physically walk
 * to their stations via the spine corridor.
 *
 * Everything is simulated in flat deck coordinates; {@link #px}/{@link #py} are the
 * only place the projection exists, so the view angle is one constant away from
 * changing. Crew rendering is isolated in {@link #drawFigure} for later sprite swap.
 */
public class ShipDeckView {
    public static final String[] ROOM_NAMES = {
        "ENGINE ROOM", "CARGO HOLD", "HANGAR BAY", "QUARTERS", "WEAPONS ROOM", "BRIDGE", "MEDICAL BAY", "LIFE SUPPORT"
    };
    // deck space: x right, y "north" (away from camera). Corridor spans y 45..65.
    private static final float[][] ROOMS = {
        {10, 65, 90, 45},    // ENGINE ROOM (north-west)
        {40, 0, 100, 45},    // CARGO HOLD (south)
        {105, 65, 140, 45},  // HANGAR BAY (north)
        {160, 0, 100, 45},   // QUARTERS (south)
        {250, 65, 80, 45},   // WEAPONS ROOM (north)
        {340, 65, 80, 45},   // BRIDGE (north, toward the nose)
        {280, 0, 80, 45},    // MEDICAL BAY (south)
        {412, 0, 40, 45},    // LIFE SUPPORT (south, aft of the first airlock)
    };
    private static final boolean[] ROOM_AMBER = {true, false, true, false, false, false, false, false}; // engine + hangar glow warm
    // the skill governing each room's single station (null = no station, e.g. quarters)
    private static final Skill[] ROOM_SKILL = {
        Skill.ENGINEERING, null /* cargo hold: no station */, Skill.FLIGHT_OPS, null,
        Skill.GUNNERY, Skill.HELM, Skill.MEDICINE, Skill.SYSTEMS
    };
    private static final float CORRIDOR_X1 = 5f, CORRIDOR_X2 = 455f;
    private static final float CORRIDOR_Y1 = 45f, CORRIDOR_Y2 = 65f;
    private static final float HULL_TOP = 115f, HULL_BOTTOM = -5f, NOSE_X = 520f, TAPER_X = 455f;
    // airlocks: spur corridor off the main corridor into a chamber on the hull.
    // {spurX1, spurX2, spurY1, spurY2, chamberX1, chamberX2, chamberY1, chamberY2, north(1)/south(0)}
    private static final float[][] AIRLOCKS = {
        {382, 398, 20, 45, 370, 410, -4, 20, 0}, // A: south hull, between medical bay and life support
        {430, 446, 65, 85, 422, 454, 85, 109, 1}, // B: north hull, aft of the bridge
    };
    private static final float DOOR_HALF = 9f;
    // doors: 0..ROOMS-1 = room corridor doors, then inner+outer per airlock
    private static final int DOOR_COUNT = ROOMS.length + 2 * AIRLOCKS.length;
    private static final float DOOR_CREW_RADIUS = 14f; // automatic doors open for crew this close

    // oxygen compartments: rooms, corridor(+spurs), one chamber per airlock
    private static final int COMP_CORRIDOR = ROOMS.length;
    private static final float OXY_FLOW = 0.8f;   // equalisation rate through an open door, per second
    private static final float VENT_FLOW = 3.2f;  // venting to space is faster
    // Regeneration must lose decisively to an open vent chain, or rooms never
    // reach lethal vacuum and suffocation can't trigger. Full refill ~3.5 min.
    private static final float OXY_REGEN = 0.005f; // life-support regen per second (will scale with crew stats later)

    // crew health: vacuum suffocates, the medbay heals
    private static final int ROOM_MEDBAY = 6;
    private static final float SUFFOCATE_THRESHOLD = 0.10f; // compartment counts as airless below this
    private static final float SUFFOCATE_DPS = 8f;
    private static final float MEDBAY_HEAL_PER_SEC = 4f;
    private static final float FALL_DURATION = 0.7f; // seconds for a dead crew member to keel over

    private static int innerDoor(int airlock) {
        return ROOMS.length + 2 * airlock;
    }

    private static int outerDoor(int airlock) {
        return ROOMS.length + 2 * airlock + 1;
    }

    private static boolean isOuterDoor(int door) {
        return door >= ROOMS.length && (door - ROOMS.length) % 2 == 1;
    }

    private static int chamberComp(int airlock) {
        return ROOMS.length + 1 + airlock;
    }

    // Nox projection: steep top-down oblique. Depth squashes, height rises on screen.
    // Scale/offsets centre the hull in the monitor with a little border margin.
    private static final float SCALE = 1.5f;
    private static final float SQUASH = 0.85f;
    private static final float Z_FACTOR = 0.55f;
    private static final float OFFSET_X = 86f;
    private static final float OFFSET_Y = 333f;

    // monitor bezel around the feed; the deck + stars render inside the screen rect
    private static final float BEZ_X1 = 8f, BEZ_X2 = 952f, BEZ_Y1 = 278f, BEZ_Y2 = 532f;
    private static final float SCR_X1 = 20f, SCR_X2 = 940f, SCR_Y1 = 290f, SCR_Y2 = 524f;
    // console colour buttons (blue/green/red) on the bottom bezel strip
    private static final float[] SCHEME_BTN_X = {BEZ_X1 + 22, BEZ_X1 + 42, BEZ_X1 + 62};
    private static final float SCHEME_BTN_Y = (BEZ_Y1 + SCR_Y1) / 2f;
    // door control cluster (#112/#116), bottom-left inside the screen
    private static final float DOOR_BTN_W = 92f;
    private static final float DOOR_BTN_H = 18f;
    private static final float DOOR_BTN_Y = SCR_Y1 + 6f;
    private int openAllStage;   // 0 = default, 1 = inner doors held open, 2 = outer too
    private int ventArm = -1;   // airlock awaiting vent confirmation
    private float ventArmT;
    // background starfield drifting past the cruising ship (screen coords, wraps inside the screen rect)
    private static final int[] BG_STAR_COUNT = {26, 14};
    private static final float[] BG_STAR_SPEED = {4f, 9f};
    private static final float[] BG_STAR_SIZE = {0.8f, 1.3f};
    private static final float[] BG_STAR_ALPHA = {0.25f, 0.4f};
    private static final float WALL_H = 26f;    // deck-z units
    private static final float STUB_H = 6f;     // cutaway walls facing the camera

    private static final float WALK_SPEED = 55f; // deck units/s
    private static final float FIGURE_H = 22f;   // deck-z units, head top
    private static final float BRIGHT_LERP = 2f; // room light fade speed
    private static final float SCAN_PERIOD = 7f; // seconds per scanline sweep

    private final GameState state;
    private final Map<CrewMember, Sim> sims = new HashMap<>();
    private final Array<CrewMember> figureList = new Array<>();
    private final java.util.Set<CrewMember> engaged = new java.util.HashSet<>();

    /** Everyone walking the deck: the crew plus any boarder. */
    private Array<CrewMember> figures() {
        figureList.clear();
        for (CrewMember c : state.crew) figureList.add(c);
        if (state.boarder != null) figureList.add(state.boarder);
        return figureList;
    }
    private final float[] roomBrightness = new float[ROOMS.length];
    private float corridorBrightness;
    private float time;
    private int highlightRoom = -1;
    private CrewMember selectedCrew;
    private CrewMember hoveredCrew;
    private final boolean[] doorOpen = new boolean[DOOR_COUNT]; // effective state this frame
    private final float[] compVolume = new float[ROOMS.length + 1 + AIRLOCKS.length];
    private final float[][] bgStarX = new float[BG_STAR_COUNT.length][];
    private final float[][] bgStarY = new float[BG_STAR_COUNT.length][];
    private final float[] bgStarOff = new float[BG_STAR_COUNT.length];

    private static float doorBtnX(int i) {
        return SCR_X1 + 8f + i * (DOOR_BTN_W + 8f);
    }

    /** Which door-control button is at (x,y): 0 open-all, 1 close-all, 2 vent A, 3 vent B; -1 none. */
    public int doorButtonAt(float x, float y) {
        if (y < DOOR_BTN_Y || y > DOOR_BTN_Y + DOOR_BTN_H) return -1;
        for (int i = 0; i < 4; i++) {
            if (x >= doorBtnX(i) && x <= doorBtnX(i) + DOOR_BTN_W) return i;
        }
        return -1;
    }

    /** OPEN ALL escalates inner -> outer; CLOSE ALL resets to automatic; venting needs a confirm click. */
    public void pressDoorButton(int btn) {
        switch (btn) {
            case 0:
                openAllStage = Math.min(2, openAllStage + 1);
                applyOpenAll();
                break;
            case 1:
                openAllStage = 0;
                ventArm = -1;
                java.util.Arrays.fill(state.doorHeldOpen, false);
                break;
            default: {
                int a = btn - 2;
                if (ventArm == a && ventArmT > 0) {
                    // confirmed: open the airlock chain, corridor air goes overboard
                    state.doorHeldOpen[innerDoor(a)] = true;
                    state.doorHeldOpen[outerDoor(a)] = true;
                    ventArm = -1;
                } else {
                    ventArm = a;
                    ventArmT = 1.5f;
                }
            }
        }
    }

    private void applyOpenAll() {
        if (openAllStage >= 1) {
            for (int i = 0; i < ROOMS.length; i++) {
                state.doorHeldOpen[i] = true;
            }
            for (int a = 0; a < AIRLOCKS.length; a++) {
                state.doorHeldOpen[innerDoor(a)] = true;
            }
        }
        if (openAllStage >= 2) {
            for (int a = 0; a < AIRLOCKS.length; a++) {
                state.doorHeldOpen[outerDoor(a)] = true;
            }
        }
    }

    private String doorBtnLabel(int i) {
        switch (i) {
            case 0: return openAllStage == 0 ? "OPEN INNER" : openAllStage == 1 ? "OPEN OUTER" : "ALL OPEN";
            case 1: return "CLOSE ALL";
            case 2: return ventArm == 0 ? "VENT A ?!" : "VENT A";
            default: return ventArm == 1 ? "VENT B ?!" : "VENT B";
        }
    }

    /** Which console colour button is at (x,y): 0 blue, 1 green, 2 red, -1 none. */
    public int schemeButtonAt(float x, float y) {
        for (int i = 0; i < SCHEME_BTN_X.length; i++) {
            float dx = x - SCHEME_BTN_X[i];
            float dy = y - SCHEME_BTN_Y;
            if (dx * dx + dy * dy <= 49f) return i;
        }
        return -1;
    }

    /** Deck position of a door's centre. */
    private static float doorPosX(int door) {
        if (door < ROOMS.length) return doorX(door);
        float[] al = AIRLOCKS[(door - ROOMS.length) / 2];
        return isOuterDoor(door) ? (al[4] + al[5]) / 2f : (al[0] + al[1]) / 2f;
    }

    private static float doorPosY(int door) {
        if (door < ROOMS.length) return ROOMS[door][1] >= CORRIDOR_Y2 ? CORRIDOR_Y2 : CORRIDOR_Y1;
        float[] al = AIRLOCKS[(door - ROOMS.length) / 2];
        boolean north = al[8] == 1;
        // inner door sits at the chamber's corridor-side edge, outer at its hull-side edge
        if (isOuterDoor(door)) return north ? al[7] : al[6];
        return north ? al[6] : al[7];
    }

    private static class Sim {
        float x, y, walkPhase;
        int plannedStation = Integer.MIN_VALUE;
        final Array<Vector2> path = new Array<>();
        boolean moving;
        float fallT;                                     // 0 upright .. 1 flat on the floor
        final int fallDir = MathUtils.randomSign();      // which way the body keels over
    }

    public ShipDeckView(GameState state) {
        this.state = state;
        for (int l = 0; l < BG_STAR_COUNT.length; l++) {
            bgStarX[l] = new float[BG_STAR_COUNT[l]];
            bgStarY[l] = new float[BG_STAR_COUNT[l]];
            for (int i = 0; i < BG_STAR_COUNT[l]; i++) {
                bgStarX[l][i] = MathUtils.random(SCR_X1, SCR_X2);
                bgStarY[l][i] = MathUtils.random(SCR_Y1 + 4, SCR_Y2 - 4);
            }
        }
        for (int i = 0; i < ROOMS.length; i++) {
            compVolume[i] = ROOMS[i][2] * ROOMS[i][3];
        }
        compVolume[COMP_CORRIDOR] = (CORRIDOR_X2 - CORRIDOR_X1) * (CORRIDOR_Y2 - CORRIDOR_Y1);
        for (int a = 0; a < AIRLOCKS.length; a++) {
            float[] al = AIRLOCKS[a];
            compVolume[COMP_CORRIDOR] += (al[1] - al[0]) * (al[3] - al[2]); // spur is corridor volume
            compVolume[chamberComp(a)] = (al[5] - al[4]) * (al[7] - al[6]);
        }
        buildGrid();
        for (CrewMember c : figures()) {
            Sim sim = new Sim();
            if (c.deckX < 0) {
                Vector2 spot = stationSpot(effectiveStation(c), c);
                c.deckX = spot.x;
                c.deckY = spot.y;
            }
            sim.x = c.deckX;
            sim.y = c.deckY;
            sim.fallT = c.isDead() ? 1f : 0f;
            sims.put(c, sim);
        }
    }

    /** Room to highlight while the player is stationing a crew member (-1 = none). */
    public void setHighlight(int room) {
        highlightRoom = room;
    }

    /** Crew focus for avatar highlighting: strong for selected, subtle for hovered. */
    public void setFocus(CrewMember selected, CrewMember hovered) {
        this.selectedCrew = selected;
        this.hoveredCrew = hovered;
    }

    /** Which door is under this screen position (room corridor doors + airlock doors), or -1. */
    public int doorAt(float screenX, float screenY) {
        float x = (screenX - OFFSET_X) / SCALE;
        float y = (screenY - OFFSET_Y) / (SQUASH * SCALE);
        for (int d = 0; d < DOOR_COUNT; d++) {
            if (Math.abs(x - doorPosX(d)) <= DOOR_HALF + 2 && Math.abs(y - doorPosY(d)) <= 5) return d;
        }
        return -1;
    }

    /**
     * Toggling a door on marks it held-open; toggling it off returns it to
     * automatic behaviour (opens for nearby crew, closes behind them).
     */
    public void toggleDoor(int door) {
        state.doorHeldOpen[door] = !state.doorHeldOpen[door];
    }

    /**
     * Order for a selected figure at a screen point: clicking near a console mans the
     * station (queue semantics), any other walkable spot is a free-move order (#88).
     * Returns true when an order was issued.
     */
    public boolean orderAtScreen(CrewMember c, float screenX, float screenY) {
        float x = (screenX - OFFSET_X) / SCALE;
        float y = (screenY - OFFSET_Y) / (SQUASH * SCALE);
        int room = roomAtDeck(x, y);
        if (room != -1 && ROOM_SKILL[room] != null) {
            float[] r = ROOMS[room];
            boolean north = r[1] >= CORRIDOR_Y2;
            float consX = r[0] + r[2] / 2f;
            float consY = north ? r[1] + r[3] - 12 : r[1] + 12;
            if (Vector2.dst(x, y, consX, consY) < 22f) {
                c.freeX = -1;
                c.station = room;
                c.assignedAt = state.nextStationSeq();
                return true;
            }
        }
        if (compAtDeck(x, y) == -1) return false;
        orderFreeMove(c, x, y);
        return true;
    }

    /** Walk to an exact deck spot and hold there (clears any station assignment). */
    public void orderFreeMove(CrewMember c, float dx, float dy) {
        c.freeX = dx;
        c.freeY = dy;
        c.station = -1;
        Sim sim = sims.get(c);
        if (sim != null) {
            sim.path.clear();
            pathBetween(sim.x, sim.y, dx, dy, sim.path);
            sim.path.add(new Vector2(dx, dy));
            sim.plannedStation = -2; // free-move sentinel
        }
    }

    /** Which crew member's figure is under this screen position, or null. */
    public CrewMember crewAt(float screenX, float screenY) {
        for (CrewMember c : figures()) {
            Sim sim = sims.get(c);
            if (sim == null) continue;
            float bx = px(sim.x);
            float y0 = py(sim.y, 0) - 4;
            float y1 = py(sim.y, FIGURE_H) + 4;
            if (Math.abs(screenX - bx) <= 7 && screenY >= y0 && screenY <= y1) return c;
        }
        return null;
    }

    /** Which compartment is under this screen position, or -1. */
    public int roomAt(float screenX, float screenY) {
        float x = (screenX - OFFSET_X) / SCALE;
        float y = (screenY - OFFSET_Y) / (SQUASH * SCALE);
        return roomAtDeck(x, y);
    }

    private static int roomAtDeck(float x, float y) {
        for (int i = 0; i < ROOMS.length; i++) {
            float[] r = ROOMS[i];
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) return i;
        }
        return -1;
    }

    private static int effectiveStation(CrewMember c) {
        return c.station >= 0 ? c.station : 3; // off-duty crew hang out in the quarters
    }

    /**
     * The crew member manning this room's station: the earliest-assigned living
     * crew explicitly stationed there (FIFO — moving or losing the holder promotes
     * the next in line automatically).
     */
    public CrewMember stationHolder(int room) {
        if (ROOM_SKILL[room] == null) return null;
        CrewMember holder = null;
        for (CrewMember c : state.crew) {
            if (c.isDead() || c.station != room) continue;
            if (holder == null || c.assignedAt < holder.assignedAt) holder = c;
        }
        return holder;
    }

    /** Ship stat for a room: +1 flat for a manned station, plus the holder's skill bonus, once at their post. */
    public int roomStat(int room) {
        CrewMember holder = stationHolder(room);
        if (holder == null) return 0;
        Sim sim = sims.get(holder);
        if (sim == null || roomAtDeck(sim.x, sim.y) != room) return 0; // still walking to the post
        return state.roomTier[room] + holder.bonusFor(ROOM_SKILL[room]); // tier is the base (T1 = +1)
    }

    /** Where this crew member stands: the station holder at the console, the rest queue side by side. */
    private Vector2 stationSpot(int room, CrewMember crew) {
        float[] r = ROOMS[room];
        if (crew == stationHolder(room)) {
            boolean north = r[1] >= CORRIDOR_Y2;
            return new Vector2(r[0] + r[2] / 2f, north ? r[1] + r[3] - 12 : r[1] + 12);
        }
        CrewMember holder = stationHolder(room);
        int slot = 0;
        int count = 0;
        for (CrewMember c : state.crew) {
            if (c == holder || effectiveStation(c) != room) continue;
            if (c == crew) slot = count;
            count++;
        }
        float cx = r[0] + r[2] / 2f + (slot - (count - 1) / 2f) * 14f;
        float cy = r[1] + r[3] * 0.45f;
        return new Vector2(cx, cy);
    }

    private static float doorX(int room) {
        float[] r = ROOMS[room];
        return MathUtils.clamp(r[0] + r[2] / 2f, r[0] + 10, r[0] + r[2] - 10);
    }

    /** Just inside the room, at its corridor-side edge. */
    private static float doorApproachY(int room) {
        float[] r = ROOMS[room];
        boolean north = r[1] >= CORRIDOR_Y2;
        return north ? r[1] + 6 : r[1] + r[3] - 6;
    }

    public void update(float delta) {
        time += delta;
        if (ventArmT > 0) {
            ventArmT -= delta;
            if (ventArmT <= 0) ventArm = -1; // confirmation window lapsed
        }
        // stars drift aft (the carrier cruises nose-first, to the right)
        for (int l = 0; l < BG_STAR_COUNT.length; l++) {
            bgStarOff[l] -= BG_STAR_SPEED[l] * delta;
        }
        boolean[] occupied = new boolean[ROOMS.length];
        boolean corridorOccupied = false;

        // hand-to-hand: the boarder engages crew in the same compartment within viewing
        // range (auto-initiation; personality traits could gate this later)
        engaged.clear();
        CrewMember boarder = state.boarder;
        if (boarder != null && !boarder.isDead()) {
            Sim bs = sims.get(boarder);
            if (bs != null) {
                int broom = roomAtDeck(bs.x, bs.y);
                for (CrewMember c : state.crew) {
                    if (c.isDead()) continue;
                    Sim cs = sims.get(c);
                    if (cs == null || roomAtDeck(cs.x, cs.y) != broom) continue;
                    float ddx = cs.x - bs.x;
                    float ddy = cs.y - bs.y;
                    if (ddx * ddx + ddy * ddy > 30f * 30f) continue; // out of viewing range
                    engaged.add(c);
                    engaged.add(boarder);
                    float boarderDps = 6f + 2f * boarder.bonusFor(Skill.COMBAT);
                    float crewDps = 6f + 2f * c.bonusFor(Skill.COMBAT);
                    c.hp = Math.max(0f, c.hp - boarderDps * delta);
                    c.damageFlash = 0.4f;
                    boarder.hp = Math.max(0f, boarder.hp - crewDps * delta);
                    boarder.damageFlash = 0.4f;
                }
                // left alone in a manned-type room, the boarder wrecks its station
                if (!engaged.contains(boarder) && broom != -1 && ROOM_SKILL[broom] != null) {
                    state.roomIntegrity[broom] =
                        Math.max(0f, state.roomIntegrity[broom] - 0.02f * delta);
                }
            }
        }

        for (CrewMember c : figures()) {
            Sim sim = sims.computeIfAbsent(c, k -> {
                Sim s = new Sim();
                s.x = c.deckX >= 0 ? c.deckX : 40;
                s.y = c.deckY >= 0 ? c.deckY : (CORRIDOR_Y1 + CORRIDOR_Y2) / 2f;
                s.fallT = c.isDead() ? 1f : 0f;
                return s;
            });
            c.damageFlash = Math.max(0f, c.damageFlash - delta);
            if (c.isDead()) {
                sim.moving = false;
                sim.fallT = Math.min(1f, sim.fallT + delta / FALL_DURATION);
                continue; // the dead don't walk, breathe, or run consoles
            }
            if (c.station >= 0) c.freeX = -1; // a station assignment overrides a free order
            if (engaged.contains(c)) {
                sim.moving = false; // locked in the melee
            } else if (c.freeX >= 0) {
                moveAlongPath(sim, delta); // free-move: no station re-path fights the order
            } else {
                if (sim.plannedStation != effectiveStation(c)) planPath(c, sim);
                moveAlongPath(sim, delta);
                // queue promotion: if my spot changed (e.g. I now man the station), walk to it
                if (sim.path.size == 0) {
                    Vector2 spot = stationSpot(effectiveStation(c), c);
                    float sdx = spot.x - sim.x;
                    float sdy = spot.y - sim.y;
                    if (sdx * sdx + sdy * sdy > 9f) planPath(c, sim);
                }
            }
            c.deckX = sim.x;
            c.deckY = sim.y;

            int room = roomAtDeck(sim.x, sim.y);
            if (room != -1) occupied[room] = true;
            else corridorOccupied = true;

            // breathe: airless compartments drain hp, the medbay restores it
            float oxy = room != -1 ? state.oxygen[room] : state.oxygen[COMP_CORRIDOR];
            if (oxy < SUFFOCATE_THRESHOLD) {
                c.hp = Math.max(0f, c.hp - SUFFOCATE_DPS * delta);
                c.damageFlash = 0.6f;
            } else if (room == ROOM_MEDBAY) {
                c.hp = Math.min(CrewMember.MAX_HP,
                    c.hp + MEDBAY_HEAL_PER_SEC * (1f + 0.3f * roomStat(ROOM_MEDBAY)) * delta);
            }
        }

        for (int i = 0; i < ROOMS.length; i++) {
            float target = occupied[i] ? 1f : 0.25f;
            roomBrightness[i] = MathUtils.lerp(roomBrightness[i], target, Math.min(1f, BRIGHT_LERP * delta));
        }
        corridorBrightness = MathUtils.lerp(corridorBrightness,
            corridorOccupied ? 0.8f : 0.3f, Math.min(1f, BRIGHT_LERP * delta));

        updateDoors();
        updateOxygen(delta);
    }

    /** Effective door state: held open by the player, or automatically open for nearby crew. */
    private void updateDoors() {
        for (int d = 0; d < DOOR_COUNT; d++) {
            boolean open = state.doorHeldOpen[d];
            if (!open && !isOuterDoor(d)) { // outer hull doors never auto-open
                float dx = doorPosX(d);
                float dy = doorPosY(d);
                for (CrewMember c : state.crew) {
                    Sim sim = sims.get(c);
                    if (sim == null) continue;
                    float ddx = sim.x - dx;
                    float ddy = sim.y - dy;
                    if (ddx * ddx + ddy * ddy < DOOR_CREW_RADIUS * DOOR_CREW_RADIUS) {
                        open = true;
                        break;
                    }
                }
            }
            doorOpen[d] = open;
        }
    }

    /**
     * Open doors equalise oxygen between compartments; open outer doors vent their
     * chamber to space. With several airlocks chained into the same rooms, each open
     * outer door is an independent drain, so the bleed stacks. Life support slowly
     * regenerates every compartment.
     */
    private void updateOxygen(float delta) {
        float k = Math.min(1f, OXY_FLOW * delta);
        for (int room = 0; room < ROOMS.length; room++) {
            if (doorOpen[room]) equalise(room, COMP_CORRIDOR, k);
        }
        float[] o = state.oxygen;
        for (int a = 0; a < AIRLOCKS.length; a++) {
            if (doorOpen[innerDoor(a)]) equalise(COMP_CORRIDOR, chamberComp(a), k);
            if (doorOpen[outerDoor(a)]) {
                o[chamberComp(a)] = MathUtils.lerp(o[chamberComp(a)], 0f, Math.min(1f, VENT_FLOW * delta));
            }
        }
        for (int i = 0; i < o.length; i++) {
            o[i] = Math.min(1f, o[i] + OXY_REGEN * (1f + roomStat(7)) * delta); // life support crew boost regen
        }
        // battle-damaged rooms leak air until a crew member (engineer, ideally) patches them
        for (int i = 0; i < ROOMS.length; i++) {
            float integ = state.roomIntegrity[i];
            if (integ >= 1f) continue;
            o[i] = Math.max(0f, o[i] - 0.02f * (1f - integ) * delta);
            for (CrewMember c : state.crew) {
                if (c.isDead()) continue;
                Sim sim = sims.get(c);
                if (sim != null && roomAtDeck(sim.x, sim.y) == i) {
                    state.roomIntegrity[i] = Math.min(1f,
                        state.roomIntegrity[i] + (0.03f + 0.015f * c.bonusFor(Skill.ENGINEERING)) * delta);
                }
            }
        }
    }

    /** Mixes two compartments toward their volume-weighted average. */
    private void equalise(int a, int b, float k) {
        float[] o = state.oxygen;
        float mix = (o[a] * compVolume[a] + o[b] * compVolume[b]) / (compVolume[a] + compVolume[b]);
        o[a] = MathUtils.lerp(o[a], mix, k);
        o[b] = MathUtils.lerp(o[b], mix, k);
    }

    /** Ship-wide oxygen fraction, volume-weighted (0..1). */
    public float totalOxygen() {
        float sum = 0f, vol = 0f;
        for (int i = 0; i < state.oxygen.length; i++) {
            sum += state.oxygen[i] * compVolume[i];
            vol += compVolume[i];
        }
        return sum / vol;
    }

    /** Crew in the console tint; hostiles inverted so they always stand out (#40). */
    private void setFigureColor(ShapeRenderer shapes, CrewMember c) {
        Color col = figureColor(c);
        if (c.hostile) Palette.setInverted(shapes, col.r, col.g, col.b, col.a);
        else Palette.set(shapes, col);
    }

    private void planPath(CrewMember c, Sim sim) {
        int target = effectiveStation(c);
        Vector2 spot = stationSpot(target, c);
        sim.path.clear();
        if (!pathBetween(sim.x, sim.y, spot.x, spot.y, sim.path)) {
            // fallback: the old corridor-waypoint route
            int cur = roomAtDeck(sim.x, sim.y);
            float corridorMid = (CORRIDOR_Y1 + CORRIDOR_Y2) / 2f;
            if (cur != target) {
                if (cur != -1) {
                    float dx = doorX(cur);
                    sim.path.add(new Vector2(dx, doorApproachY(cur)));
                    sim.path.add(new Vector2(dx, corridorMid));
                } else {
                    sim.path.add(new Vector2(sim.x, corridorMid));
                }
                float tx = doorX(target);
                sim.path.add(new Vector2(tx, corridorMid));
                sim.path.add(new Vector2(tx, doorApproachY(target)));
            }
        }
        sim.path.add(spot);
        sim.plannedStation = target;
    }

    // --- tile grid (#41): every walkable area of the deck is reachable via BFS across cells;
    // doors gate the crossings between compartments ---
    private static final float CELL = 5f;
    private static final float GRID_X0 = 0f;
    private static final float GRID_Y0 = -10f;
    private static final int GRID_W = 106;
    private static final int GRID_H = 27;
    private final boolean[][] walkable = new boolean[GRID_W][GRID_H];
    private final boolean[][] doorway = new boolean[GRID_W][GRID_H];
    private final int[][] compOf = new int[GRID_W][GRID_H];

    private void buildGrid() {
        for (int gx = 0; gx < GRID_W; gx++) {
            for (int gy = 0; gy < GRID_H; gy++) {
                float x = GRID_X0 + (gx + 0.5f) * CELL;
                float y = GRID_Y0 + (gy + 0.5f) * CELL;
                int comp = compAtDeck(x, y);
                compOf[gx][gy] = comp;
                walkable[gx][gy] = comp != -1;
            }
        }
        for (int d = 0; d < DOOR_COUNT; d++) {
            float dx = doorPosX(d);
            float dy = doorPosY(d);
            for (int gx = 0; gx < GRID_W; gx++) {
                for (int gy = 0; gy < GRID_H; gy++) {
                    float x = GRID_X0 + (gx + 0.5f) * CELL;
                    float y = GRID_Y0 + (gy + 0.5f) * CELL;
                    if (Math.abs(x - dx) <= DOOR_HALF + 2f && Math.abs(y - dy) <= 6f && walkable[gx][gy]) {
                        doorway[gx][gy] = true;
                    }
                }
            }
        }
    }

    /** Compartment under a deck point: room index, corridor (+spurs), chamber, or -1 (hull). */
    private static int compAtDeck(float x, float y) {
        int room = roomAtDeck(x, y);
        if (room != -1) return room;
        if (x >= CORRIDOR_X1 && x <= CORRIDOR_X2 && y >= CORRIDOR_Y1 && y <= CORRIDOR_Y2) {
            return COMP_CORRIDOR;
        }
        for (int a = 0; a < AIRLOCKS.length; a++) {
            float[] al = AIRLOCKS[a];
            if (x >= al[0] && x <= al[1] && y >= al[2] && y <= al[3]) return COMP_CORRIDOR;
            if (x >= al[4] && x <= al[5] && y >= al[6] && y <= al[7]) return chamberComp(a);
        }
        return -1;
    }

    /** BFS a route between two deck points; waypoints (cell centres, collinear-compressed) land in out. */
    public boolean pathBetween(float sx, float sy, float tx, float ty, Array<Vector2> out) {
        int scx = nearestWalkableX(sx, sy);
        int scy = nearestWalkableY(sx, sy);
        int tcx = nearestWalkableX(tx, ty);
        int tcy = nearestWalkableY(tx, ty);
        if (scx < 0 || tcx < 0) return false;
        int start = scx * GRID_H + scy;
        int goal = tcx * GRID_H + tcy;
        int[] prev = new int[GRID_W * GRID_H];
        java.util.Arrays.fill(prev, -2);
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        prev[start] = -1;
        queue.add(start);
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (cur == goal) break;
            int cx = cur / GRID_H;
            int cy = cur % GRID_H;
            for (int k = 0; k < 4; k++) {
                int nx = cx + dx[k];
                int ny = cy + dy[k];
                if (nx < 0 || ny < 0 || nx >= GRID_W || ny >= GRID_H) continue;
                int idx = nx * GRID_H + ny;
                if (prev[idx] != -2 || !walkable[nx][ny]) continue;
                if (compOf[cx][cy] != compOf[nx][ny]
                        && !doorway[cx][cy] && !doorway[nx][ny]) {
                    continue; // compartment crossings only happen at doors
                }
                prev[idx] = cur;
                queue.add(idx);
            }
        }
        if (prev[goal] == -2) return false;
        Array<Vector2> cells = new Array<>();
        for (int at = goal; at != -1; at = prev[at]) {
            cells.add(new Vector2(GRID_X0 + (at / GRID_H + 0.5f) * CELL,
                GRID_Y0 + (at % GRID_H + 0.5f) * CELL));
        }
        cells.reverse();
        // compress collinear runs so figures walk clean straight lines
        for (int i = 1; i < cells.size; i++) {
            Vector2 a = cells.get(i - 1);
            Vector2 b = cells.get(i);
            while (i + 1 < cells.size) {
                Vector2 nxt = cells.get(i + 1);
                boolean sameDir = MathUtils.isEqual(nxt.x - b.x, b.x - a.x, 0.01f)
                    && MathUtils.isEqual(nxt.y - b.y, b.y - a.y, 0.01f);
                if (!sameDir) break;
                cells.removeIndex(i);
                b = cells.get(i);
            }
        }
        out.addAll(cells);
        return true;
    }

    private int nearestWalkableX(float x, float y) {
        int[] c = nearestWalkable(x, y);
        return c == null ? -1 : c[0];
    }

    private int nearestWalkableY(float x, float y) {
        int[] c = nearestWalkable(x, y);
        return c == null ? -1 : c[1];
    }

    private int[] nearestWalkable(float x, float y) {
        int gx = MathUtils.clamp((int) ((x - GRID_X0) / CELL), 0, GRID_W - 1);
        int gy = MathUtils.clamp((int) ((y - GRID_Y0) / CELL), 0, GRID_H - 1);
        for (int ring = 0; ring <= 2; ring++) {
            for (int ax = gx - ring; ax <= gx + ring; ax++) {
                for (int ay = gy - ring; ay <= gy + ring; ay++) {
                    if (ax < 0 || ay < 0 || ax >= GRID_W || ay >= GRID_H) continue;
                    if (walkable[ax][ay]) return new int[]{ax, ay};
                }
            }
        }
        return null;
    }

    private void moveAlongPath(Sim sim, float delta) {
        float budget = WALK_SPEED * delta;
        sim.moving = sim.path.size > 0;
        while (budget > 0 && sim.path.size > 0) {
            Vector2 wp = sim.path.first();
            float dx = wp.x - sim.x;
            float dy = wp.y - sim.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist <= budget) {
                sim.x = wp.x;
                sim.y = wp.y;
                budget -= dist;
                sim.path.removeIndex(0);
            } else {
                sim.x += dx / dist * budget;
                sim.y += dy / dist * budget;
                budget = 0;
            }
        }
        if (sim.moving) sim.walkPhase += delta * 11f;
    }

    // --- projection ---

    private static float wrap(float v, float span) {
        return ((v % span) + span) % span;
    }

    /** Shared console scanline: a double line sweeping top to bottom (both monitor screens use it). */
    public static void drawScanline(ShapeRenderer shapes, float time,
                                    float x1, float x2, float yBottom, float yTop) {
        float scan = yTop - (time % SCAN_PERIOD) / SCAN_PERIOD * (yTop - yBottom);
        Palette.set(shapes, 0.3f, 0.9f, 1f, 0.10f);
        shapes.line(x1, scan + 2, x2, scan + 2);
        Palette.set(shapes, 0.3f, 0.9f, 1f, 0.22f);
        shapes.line(x1, scan, x2, scan);
    }

    /** Deck-space line on the floor (z = 0). */
    private static void dl(ShapeRenderer shapes, float x1, float y1, float x2, float y2) {
        shapes.line(px(x1), py(y1, 0), px(x2), py(y2, 0));
    }

    /** Deck-space circle on the floor: projected as a squashed ellipse. */
    private static void deckCircle(ShapeRenderer shapes, float cx, float cy, float rad) {
        shapes.ellipse(px(cx - rad), py(cy, 0) - rad * SQUASH * SCALE,
            rad * 2 * SCALE, rad * 2 * SQUASH * SCALE);
    }

    /** Deck-space rectangle outline on the floor. */
    private static void deckRectOutline(ShapeRenderer shapes, float x, float y, float w, float h) {
        dl(shapes, x, y, x + w, y);
        dl(shapes, x + w, y, x + w, y + h);
        dl(shapes, x + w, y + h, x, y + h);
        dl(shapes, x, y + h, x, y);
    }

    /** Room furniture, pipework and corridor markings — kept faint so figures stay readable. */
    private void drawDeckDetail(ShapeRenderer shapes) {
        // corridor: dashed centreline
        Palette.set(shapes, 0.1f, 0.2f, 0.26f, 0.8f);
        float midY = (CORRIDOR_Y1 + CORRIDOR_Y2) / 2f;
        for (float x = CORRIDOR_X1 + 6; x < CORRIDOR_X2 - 10; x += 18) {
            dl(shapes, x, midY, x + 9, midY);
        }
        // corridor: pipe run along the north edge
        Palette.set(shapes, 0.14f, 0.24f, 0.3f, 0.7f);
        dl(shapes, CORRIDOR_X1 + 4, CORRIDOR_Y2 - 3, CORRIDOR_X2 - 4, CORRIDOR_Y2 - 3);
        for (float x = CORRIDOR_X1 + 30; x < CORRIDOR_X2 - 10; x += 60) {
            dl(shapes, x, CORRIDOR_Y2 - 3, x, CORRIDOR_Y2 - 1); // pipe clamps
        }

        // engine room: reactor ring + feed pipes
        float b0 = roomBrightness[0];
        Palette.set(shapes, 0.45f * b0 + 0.2f, 0.3f * b0 + 0.14f, 0.1f, 0.85f);
        deckCircle(shapes, 60, 95, 9);
        deckCircle(shapes, 60, 95, 5);
        dl(shapes, 16, 90, 51, 93);
        dl(shapes, 16, 100, 51, 97);

        // cargo hold: pallet squares
        Palette.set(shapes, 0.16f, 0.3f, 0.36f, 0.85f);
        deckRectOutline(shapes, 52, 8, 13, 13);
        deckRectOutline(shapes, 52, 26, 13, 13);
        deckRectOutline(shapes, 70, 16, 13, 13);
        deckRectOutline(shapes, 116, 8, 13, 13);

        // quarters: bunks along the south wall
        Palette.set(shapes, 0.18f, 0.3f, 0.36f, 0.85f);
        for (int k = 0; k < 3; k++) {
            float bx = 172 + k * 28;
            deckRectOutline(shapes, bx, 5, 18, 9);
            dl(shapes, bx + 4, 5, bx + 4, 14); // pillow line
        }

        // medical bay: two beds
        Palette.set(shapes, 0.2f, 0.34f, 0.38f, 0.85f);
        deckRectOutline(shapes, 292, 6, 18, 8);
        deckRectOutline(shapes, 320, 6, 18, 8);

        // weapons room: ammo racks
        Palette.set(shapes, 0.24f, 0.28f, 0.3f, 0.85f);
        for (int k = 0; k < 2; k++) {
            float ry = 74 + k * 14;
            dl(shapes, 258, ry, 290, ry);
            dl(shapes, 258, ry + 4, 290, ry + 4);
        }

        // bridge: console arc facing the nose
        float b5 = roomBrightness[5];
        Palette.set(shapes, 0.2f * b5 + 0.1f, 0.5f * b5 + 0.15f, 0.65f * b5 + 0.2f, 0.9f);
        dl(shapes, 398, 78, 408, 84);
        dl(shapes, 408, 84, 408, 92);
        dl(shapes, 408, 92, 398, 98);

        // life support: O2 tanks
        Palette.set(shapes, 0.18f, 0.34f, 0.38f, 0.85f);
        deckCircle(shapes, 422, 12, 4);
        deckCircle(shapes, 432, 12, 4);
        deckCircle(shapes, 442, 12, 4);
    }

    /** Faint function glyph per room: gear, crate, chevron, bunk, crosshair, helm, cross, O2. */
    private void drawRoomIcons(ShapeRenderer shapes) {
        for (int i = 0; i < ROOMS.length; i++) {
            float[] r = ROOMS[i];
            float b = roomBrightness[i];
            Palette.set(shapes, 0.18f + 0.2f * b, 0.32f + 0.25f * b, 0.4f + 0.25f * b, 0.45f);
            float cx = r[0] + r[2] / 2f;
            float cy = r[1] + r[3] * 0.3f;
            float s = 6f;
            switch (i) {
                case 0: // engine: gear
                    deckCircle(shapes, cx, cy, s * 0.6f);
                    dl(shapes, cx - s, cy, cx + s, cy);
                    dl(shapes, cx, cy - s, cx, cy + s);
                    break;
                case 1: // cargo: crate with an X
                    dl(shapes, cx - s, cy - s, cx + s, cy - s);
                    dl(shapes, cx + s, cy - s, cx + s, cy + s);
                    dl(shapes, cx + s, cy + s, cx - s, cy + s);
                    dl(shapes, cx - s, cy + s, cx - s, cy - s);
                    dl(shapes, cx - s, cy - s, cx + s, cy + s);
                    dl(shapes, cx - s, cy + s, cx + s, cy - s);
                    break;
                case 2: // hangar: launch chevron
                    dl(shapes, cx - s, cy - s, cx, cy + s);
                    dl(shapes, cx, cy + s, cx + s, cy - s);
                    break;
                case 3: // quarters: bunk
                    dl(shapes, cx - s, cy - s * 0.6f, cx + s, cy - s * 0.6f);
                    dl(shapes, cx + s, cy - s * 0.6f, cx + s, cy + s * 0.6f);
                    dl(shapes, cx + s, cy + s * 0.6f, cx - s, cy + s * 0.6f);
                    dl(shapes, cx - s, cy + s * 0.6f, cx - s, cy - s * 0.6f);
                    dl(shapes, cx - s * 0.4f, cy - s * 0.6f, cx - s * 0.4f, cy + s * 0.6f); // pillow
                    break;
                case 4: // weapons: crosshair
                    deckCircle(shapes, cx, cy, s * 0.7f);
                    dl(shapes, cx - s, cy, cx - s * 0.4f, cy);
                    dl(shapes, cx + s * 0.4f, cy, cx + s, cy);
                    dl(shapes, cx, cy - s, cx, cy - s * 0.4f);
                    dl(shapes, cx, cy + s * 0.4f, cx, cy + s);
                    break;
                case 5: // bridge: helm wheel
                    deckCircle(shapes, cx, cy, s * 0.8f);
                    dl(shapes, cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f);
                    dl(shapes, cx - s * 0.6f, cy + s * 0.6f, cx + s * 0.6f, cy - s * 0.6f);
                    break;
                case 6: // medbay: cross
                    dl(shapes, cx - s, cy, cx + s, cy);
                    dl(shapes, cx, cy - s, cx, cy + s);
                    dl(shapes, cx - s * 0.5f, cy - s * 0.5f, cx - s * 0.5f, cy + s * 0.5f);
                    dl(shapes, cx + s * 0.5f, cy - s * 0.5f, cx + s * 0.5f, cy + s * 0.5f);
                    break;
                default: // life support: O2 bubbles
                    deckCircle(shapes, cx, cy - 2, s * 0.6f);
                    deckCircle(shapes, cx + s * 0.6f, cy + s * 0.6f, s * 0.3f);
                    break;
            }
        }
    }

    private static float px(float x) {
        return OFFSET_X + x * SCALE;
    }

    private static float py(float y, float z) {
        return OFFSET_Y + (y * SQUASH + z * Z_FACTOR) * SCALE;
    }

    // --- rendering ---

    public void renderShapes(ShapeRenderer shapes) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // pass 1, filled: bezel, screen, stars, floors, light pools, back-wall faces, figure shadows
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // monitor bezel frame (four strips between outer and screen rects)
        Palette.set(shapes, 0.14f, 0.15f, 0.17f, 1f);
        shapes.rect(BEZ_X1, BEZ_Y1, BEZ_X2 - BEZ_X1, SCR_Y1 - BEZ_Y1);
        shapes.rect(BEZ_X1, SCR_Y2, BEZ_X2 - BEZ_X1, BEZ_Y2 - SCR_Y2);
        shapes.rect(BEZ_X1, SCR_Y1, SCR_X1 - BEZ_X1, SCR_Y2 - SCR_Y1);
        shapes.rect(SCR_X2, SCR_Y1, BEZ_X2 - SCR_X2, SCR_Y2 - SCR_Y1);
        // screen background
        Palette.set(shapes, 0.004f, 0.01f, 0.02f, 1f);
        shapes.rect(SCR_X1, SCR_Y1, SCR_X2 - SCR_X1, SCR_Y2 - SCR_Y1);
        // parallax stars sliding past the hull
        for (int l = 0; l < BG_STAR_COUNT.length; l++) {
            Palette.set(shapes, 0.8f, 0.85f, 1f, BG_STAR_ALPHA[l]);
            float span = SCR_X2 - SCR_X1;
            for (int i = 0; i < BG_STAR_COUNT[l]; i++) {
                float sx = SCR_X1 + wrap(bgStarX[l][i] - SCR_X1 + bgStarOff[l], span);
                shapes.circle(sx, bgStarY[l][i], BG_STAR_SIZE[l], 6);
            }
        }
        // hull floor silhouette
        Palette.set(shapes, 0.015f, 0.03f, 0.045f, 1f);
        fillDeckPoly(shapes);
        // corridor (floor colours redden as oxygen bleeds out)
        float cb = corridorBrightness;
        setFloorColor(shapes, 0.02f + 0.05f * cb, 0.04f + 0.08f * cb, 0.06f + 0.11f * cb, state.oxygen[COMP_CORRIDOR]);
        fillDeckRect(shapes, CORRIDOR_X1, CORRIDOR_Y1, CORRIDOR_X2 - CORRIDOR_X1, CORRIDOR_Y2 - CORRIDOR_Y1);
        // airlock spurs + chambers
        for (int a = 0; a < AIRLOCKS.length; a++) {
            float[] al = AIRLOCKS[a];
            setFloorColor(shapes, 0.035f, 0.06f, 0.09f, state.oxygen[COMP_CORRIDOR]);
            fillDeckRect(shapes, al[0], al[2], al[1] - al[0], al[3] - al[2]);
            setFloorColor(shapes, 0.035f, 0.06f, 0.09f, state.oxygen[chamberComp(a)]);
            fillDeckRect(shapes, al[4], al[6], al[5] - al[4], al[7] - al[6]);
        }
        // room floors
        for (int i = 0; i < ROOMS.length; i++) {
            float[] r = ROOMS[i];
            float b = roomBrightness[i];
            setFloorColor(shapes, 0.02f + 0.06f * b, 0.04f + 0.10f * b, 0.06f + 0.16f * b, state.oxygen[i]);
            fillDeckRect(shapes, r[0], r[1], r[2], r[3]);
        }
        // light pools over each room's console, flickering
        for (int i = 0; i < ROOMS.length; i++) {
            float[] r = ROOMS[i];
            float b = roomBrightness[i];
            float flicker = 0.85f + 0.15f * MathUtils.sin(time * 13f + i * 7f) * MathUtils.sin(time * 5.3f + i * 3f);
            float cx = r[0] + r[2] / 2f;
            float cy = r[1] + r[3] * 0.7f;
            for (int ring = 3; ring >= 1; ring--) {
                float alpha = 0.045f * b * flicker;
                if (ROOM_AMBER[i]) Palette.set(shapes, 1f, 0.7f, 0.3f, alpha);
                else Palette.set(shapes, 0.3f, 0.8f, 1f, alpha);
                shapes.ellipse(px(cx) - ring * 11, py(cy, 0) - ring * 6.5f, ring * 22, ring * 13);
            }
        }
        // back-wall faces (far bulkheads, away from camera): projected as screen rects
        Palette.set(shapes, 0.045f, 0.075f, 0.105f, 0.95f);
        for (float[] r : ROOMS) {
            if (r[1] >= CORRIDOR_Y2) { // north rooms
                shapes.rect(px(r[0]), py(r[1] + r[3], 0), r[2] * SCALE, WALL_H * Z_FACTOR * SCALE);
            }
        }
        shapes.rect(px(CORRIDOR_X1), py(HULL_TOP, 0), (TAPER_X - CORRIDOR_X1) * SCALE, WALL_H * Z_FACTOR * SCALE);
        // figure shadows
        Palette.set(shapes, 0f, 0f, 0f, 0.5f);
        for (CrewMember c : figures()) {
            Sim sim = sims.get(c);
            if (sim == null) continue;
            shapes.ellipse(px(sim.x) - 5, py(sim.y, 0) - 2.5f, 10, 5);
        }
        shapes.end();

        // pass 2, lines: outlines, wall edges, props, figures, scanline
        shapes.begin(ShapeRenderer.ShapeType.Line);
        // bezel edges + corner accents
        Palette.set(shapes, 0.32f, 0.34f, 0.38f, 1f);
        shapes.rect(BEZ_X1, BEZ_Y1, BEZ_X2 - BEZ_X1, BEZ_Y2 - BEZ_Y1);
        Palette.set(shapes, 0.06f, 0.07f, 0.09f, 1f);
        shapes.rect(SCR_X1, SCR_Y1, SCR_X2 - SCR_X1, SCR_Y2 - SCR_Y1);
        Palette.set(shapes, 0.45f, 0.48f, 0.52f, 1f);
        for (float[] c : new float[][] {{BEZ_X1 + 6, BEZ_Y1 + 6}, {BEZ_X2 - 6, BEZ_Y1 + 6},
                                        {BEZ_X1 + 6, BEZ_Y2 - 6}, {BEZ_X2 - 6, BEZ_Y2 - 6}}) {
            shapes.circle(c[0], c[1], 2.5f, 8); // corner screws
        }
        // free-move destination marker for the selected figure
        if (selectedCrew != null && selectedCrew.freeX >= 0) {
            float mxp = px(selectedCrew.freeX);
            float myp = py(selectedCrew.freeY, 0);
            float pulse = 3.5f + 1.2f * MathUtils.sin(time * 5f);
            Palette.set(shapes, 0.4f, 0.9f, 1f, 0.8f);
            shapes.line(mxp - pulse, myp, mxp + pulse, myp);
            shapes.line(mxp, myp - pulse * SQUASH, mxp, myp + pulse * SQUASH);
        }
        // door control cluster
        for (int i = 0; i < 4; i++) {
            boolean hot = (i == 0 && openAllStage == 2) || (i >= 2 && ventArm == i - 2);
            if (hot) Palette.set(shapes, 0.9f, 0.25f, 0.2f, 1f);
            else Palette.set(shapes, 0.3f, 0.45f, 0.52f, 1f);
            shapes.rect(doorBtnX(i), DOOR_BTN_Y, DOOR_BTN_W, DOOR_BTN_H);
        }
        // hull outline
        Palette.set(shapes, 0.2f, 0.4f, 0.5f, 1f);
        lineDeckPoly(shapes);
        // rooms
        for (int i = 0; i < ROOMS.length; i++) {
            float[] r = ROOMS[i];
            float b = roomBrightness[i];
            if (i == highlightRoom) Palette.set(shapes, 0.5f, 0.9f, 1f, 1f);
            else Palette.set(shapes, 0.1f + 0.25f * b, 0.2f + 0.35f * b, 0.28f + 0.4f * b, 1f);
            drawRoomOutline(shapes, i);
        }
        // corridor edges, with gaps at every room door (and the airlock spur on the south edge)
        Palette.set(shapes, 0.12f, 0.22f, 0.28f, 1f);
        drawGappedCorridorEdge(shapes, CORRIDOR_Y2, true);
        drawGappedCorridorEdge(shapes, CORRIDOR_Y1, false);
        for (int a = 0; a < AIRLOCKS.length; a++) {
            drawAirlock(shapes, a);
        }
        // room corridor doors
        for (int i = 0; i < ROOMS.length; i++) {
            float d = doorX(i);
            drawDoor(shapes, d - DOOR_HALF, d + DOOR_HALF, doorPosY(i), doorOpen[i], state.doorHeldOpen[i]);
        }
        // consoles: a bright strip against each room's hull-side wall
        for (int i = 0; i < ROOMS.length; i++) {
            float[] r = ROOMS[i];
            float b = roomBrightness[i];
            if (ROOM_AMBER[i]) Palette.set(shapes, 0.9f * b, 0.6f * b, 0.2f * b, 1f);
            else Palette.set(shapes, 0.25f * b, 0.7f * b, 0.9f * b, 1f);
            float cx = r[0] + r[2] / 2f;
            float wy = r[1] >= CORRIDOR_Y2 ? r[1] + r[3] - 3 : r[1] + 3;
            shapes.rect(px(cx - 8), py(wy, 4), 16 * SCALE, 3);
        }
        // floor icons: a faint function glyph on each room's deck plating
        drawRoomIcons(shapes);
        // furniture, pipes and floor markings
        drawDeckDetail(shapes);
        // hangar bay: the carrier's two craft parked side by side (B-2 fighter + pincer pod)
        float hb = 0.25f + 0.35f * roomBrightness[2];
        Palette.set(shapes, hb * 0.8f, hb * 1.3f, hb * 1.6f, 1f);
        drawParkedCraft(shapes, 150, 87, 0.38f, false);
        drawParkedCraft(shapes, 205, 86, 0.32f, true);
        // crew figures (+ selection/hover rings at their feet)
        for (CrewMember c : figures()) {
            Sim sim = sims.get(c);
            if (sim == null) continue;
            if (c == selectedCrew) {
                Palette.set(shapes, 1f, 0.9f, 0.3f, 0.9f);
                shapes.ellipse(px(sim.x) - 7, py(sim.y, 0) - 3.5f, 14, 7);
            } else if (c == hoveredCrew) {
                Palette.set(shapes, 0.7f, 0.95f, 1f, 0.45f);
                shapes.ellipse(px(sim.x) - 6, py(sim.y, 0) - 3f, 12, 6);
            }
            drawFigure(shapes, c, sim);
        }
        // scanline sweep, top to bottom of the monitor screen
        drawScanline(shapes, time, SCR_X1 + 4, SCR_X2 - 4, SCR_Y1 + 4, SCR_Y2 - 4);
        shapes.end();

        // pass 3, filled: heads + REC dot
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (CrewMember c : figures()) {
            Sim sim = sims.get(c);
            if (sim == null) continue;
            setFigureColor(shapes, c);
            shapes.circle(bodyX(sim, FIGURE_H - 3), bodyY(sim, FIGURE_H - 3), 2.6f, 10);
        }
        Palette.set(shapes, 0.9f, 0.15f, 0.1f, 1f);
        shapes.circle(896, SCR_Y1 + 8, 3.5f, 10); // record dot, left of the LIVE label
        // console colour buttons on the bottom bezel strip (raw colours, never tinted)
        for (int i = 0; i < SCHEME_BTN_X.length; i++) {
            if (Palette.scheme().ordinal() == i) {
                shapes.setColor(0.7f, 0.72f, 0.75f, 1f);
                shapes.circle(SCHEME_BTN_X[i], SCHEME_BTN_Y, 6f, 12); // active ring
            }
            if (i == 0) shapes.setColor(0.25f, 0.55f, 0.95f, 1f);
            else if (i == 1) shapes.setColor(0.25f, 0.85f, 0.35f, 1f);
            else shapes.setColor(0.9f, 0.25f, 0.2f, 1f);
            shapes.circle(SCHEME_BTN_X[i], SCHEME_BTN_Y, 4f, 12);
        }
        // airlock door status lights: green = open, red = sealed
        for (int a = 0; a < AIRLOCKS.length; a++) {
            float[] al = AIRLOCKS[a];
            setDoorLightColor(shapes, doorOpen[innerDoor(a)]);
            shapes.circle(px(al[1] + 6), py(doorPosY(innerDoor(a)), 3), 2.2f, 8);
            setDoorLightColor(shapes, doorOpen[outerDoor(a)]);
            shapes.circle(px(al[5] + 6), py(doorPosY(outerDoor(a)), 3), 2.2f, 8);
        }
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Body-axis direction: upright (0,1), rotating to horizontal as the fall progresses. */
    private static float fallDx(Sim sim) {
        return MathUtils.sinDeg(90f * sim.fallT * sim.fallT) * sim.fallDir;
    }

    private static float fallDy(Sim sim) {
        return MathUtils.cosDeg(90f * sim.fallT * sim.fallT);
    }

    /** Screen position of a point at body-height h, following the fall rotation around the feet. */
    private float bodyX(Sim sim, float h) {
        return px(sim.x) + h * Z_FACTOR * SCALE * fallDx(sim);
    }

    private float bodyY(Sim sim, float h) {
        return py(sim.y, 0) + h * Z_FACTOR * SCALE * fallDy(sim);
    }

    /** Stick-figure crew: swap this method's body for sprite rendering later. */
    private void drawFigure(ShapeRenderer shapes, CrewMember c, Sim sim) {
        float bx = px(sim.x);
        float by = py(sim.y, 0);
        float hipX = bodyX(sim, FIGURE_H * 0.45f), hipY = bodyY(sim, FIGURE_H * 0.45f);
        float neckX = bodyX(sim, FIGURE_H - 5), neckY = bodyY(sim, FIGURE_H - 5);
        // limbs spread perpendicular to the body axis
        float sideX = fallDy(sim), sideY = -fallDx(sim);
        setFigureColor(shapes, c);
        float swing = sim.moving ? MathUtils.sin(sim.walkPhase) * 3.5f : 0f;
        shapes.line(hipX, hipY, bx - (2 + swing) * sideX, by - (2 + swing) * sideY);  // legs
        shapes.line(hipX, hipY, bx + (2 + swing) * sideX, by + (2 + swing) * sideY);
        shapes.line(hipX, hipY, neckX, neckY);                                        // torso
        float armSwing = sim.moving ? MathUtils.sin(sim.walkPhase + MathUtils.PI) * 2.5f : 0f;
        shapes.line(neckX, neckY, hipX - (3 + armSwing) * sideX, hipY - (3 + armSwing) * sideY); // arms
        shapes.line(neckX, neckY, hipX + (3 + armSwing) * sideX, hipY + (3 + armSwing) * sideY);
    }

    /** Blink phase for damage indicators; shared with the roster's health bars. */
    public boolean damageBlinkOn() {
        return MathUtils.sin(time * 25f) > 0f;
    }

    private Color figureColor(CrewMember c) {
        if (c.damageFlash > 0 && damageBlinkOn()) return new Color(1f, 0.25f, 0.2f, 1f);
        if (c == selectedCrew) return new Color(1f, 0.9f, 0.3f, 1f);
        if (c.isDead()) return new Color(0.5f, 0.5f, 0.55f, 1f);
        if (c == hoveredCrew) return new Color(0.75f, 0.97f, 1f, 1f);
        return new Color(0.45f, 0.9f, 1f, 1f);
    }

    /** Room labels, crew nametags, and the camera-feed overlay. Caller has font at scale 1. */
    public void renderText(SpriteBatch batch, BitmapFont font) {
        Fonts.scale(font, 0.8f); // room/airlock labels stay unobtrusive
        for (int i = 0; i < ROOMS.length; i++) {
            float[] r = ROOMS[i];
            float b = roomBrightness[i];
            String label = ROOM_NAMES[i];
            if (state.roomTier[i] > 1) label += " T" + state.roomTier[i];
            if (state.roomIntegrity[i] < 1f) label += " [DMG]";
            int stat = roomStat(i);
            if (stat > 0) label += " +" + stat;
            if (stat > 0) Palette.set(font, 0.35f, 0.85f, 0.5f, 1f);
            else Palette.set(font, 0.25f + 0.35f * b, 0.45f + 0.35f * b, 0.55f + 0.35f * b, 1f);
            GlyphLayout gl = new GlyphLayout(font, label);
            font.draw(batch, label, px(r[0] + r[2] / 2f) - gl.width / 2f, py(r[1] + 10, 0));
        }
        // airlock labels (redden while the outer door is open or the chamber has vented)
        for (int a = 0; a < AIRLOCKS.length; a++) {
            float[] al = AIRLOCKS[a];
            if (doorOpen[outerDoor(a)] || state.oxygen[chamberComp(a)] < 0.5f) Palette.set(font, 0.9f, 0.35f, 0.3f, 1f);
            else Palette.set(font, 0.35f, 0.55f, 0.65f, 1f);
            String label = "AIRLOCK " + (char) ('A' + a);
            GlyphLayout airGl = new GlyphLayout(font, label);
            font.draw(batch, label, px((al[4] + al[5]) / 2f) - airGl.width / 2f, py((al[6] + al[7]) / 2f + 3, 0));
        }

        // door control cluster labels
        for (int i = 0; i < 4; i++) {
            boolean hot = (i == 0 && openAllStage == 2) || (i >= 2 && ventArm == i - 2);
            if (hot) Palette.set(font, 0.95f, 0.35f, 0.3f, 1f);
            else Palette.set(font, 0.5f, 0.7f, 0.78f, 1f);
            GlyphLayout dbl = new GlyphLayout(font, doorBtnLabel(i));
            font.draw(batch, dbl, doorBtnX(i) + (DOOR_BTN_W - dbl.width) / 2f, DOOR_BTN_Y + 14f);
        }
        Fonts.scale(font, 1f);

        // feed overlay, right-aligned in the bottom-right corner inside the bezel
        float overlayRight = SCR_X2 - 10;
        GlyphLayout liveGl = new GlyphLayout(font, "LIVE");
        Palette.set(font, 0.9f, 0.2f, 0.15f, 1f);
        font.draw(batch, liveGl, overlayRight - liveGl.width, SCR_Y1 + 12);
    }

    // --- deck-space drawing helpers (projection applied here) ---

    private void fillDeckRect(ShapeRenderer shapes, float x, float y, float w, float h) {
        shapes.rect(px(x), py(y, 0), w * SCALE, h * SQUASH * SCALE);
    }

    private void drawRoomOutline(ShapeRenderer shapes, int i) {
        float[] r = ROOMS[i];
        float x1 = px(r[0]), x2 = px(r[0] + r[2]);
        float yNear = py(r[1], 0), yFar = py(r[1] + r[3], 0);
        boolean north = r[1] >= CORRIDOR_Y2; // north rooms: corridor side = near edge
        float doorPx = px(doorX(i));
        float doorHalf = 9 * SCALE;
        float corridorEdge = north ? yNear : yFar;
        float otherEdge = north ? yFar : yNear;

        // side edges
        shapes.line(x1, yNear, x1, yFar);
        shapes.line(x2, yNear, x2, yFar);
        // hull-side edge; north rooms also get their back wall's raised top edge
        if (north) {
            float top = py(r[1] + r[3], WALL_H);
            shapes.line(x1, top, x2, top);
            shapes.line(x1, yFar, x1, top);
            shapes.line(x2, yFar, x2, top);
        } else {
            shapes.line(x1, otherEdge, x2, otherEdge);
        }
        // corridor-side edge: cutaway wall with a door gap and short posts
        shapes.line(x1, corridorEdge, doorPx - doorHalf, corridorEdge);
        shapes.line(doorPx + doorHalf, corridorEdge, x2, corridorEdge);
        float stubTop = corridorEdge + STUB_H * Z_FACTOR * SCALE;
        shapes.line(doorPx - doorHalf, corridorEdge, doorPx - doorHalf, stubTop);
        shapes.line(doorPx + doorHalf, corridorEdge, doorPx + doorHalf, stubTop);
    }

    /** One corridor edge as segments, skipping room doors and airlock spur mouths on that side. */
    private void drawGappedCorridorEdge(ShapeRenderer shapes, float y, boolean north) {
        Array<float[]> gaps = new Array<>();
        for (int i = 0; i < ROOMS.length; i++) {
            if ((ROOMS[i][1] >= CORRIDOR_Y2) != north) continue;
            float d = doorX(i);
            gaps.add(new float[] {d - DOOR_HALF, d + DOOR_HALF});
        }
        for (float[] al : AIRLOCKS) {
            if ((al[8] == 1) == north) gaps.add(new float[] {al[0], al[1]});
        }
        gaps.sort((g1, g2) -> Float.compare(g1[0], g2[0]));
        float lineY = py(y, 0);
        float cursor = CORRIDOR_X1;
        for (float[] g : gaps) {
            shapes.line(px(cursor), lineY, px(g[0]), lineY);
            cursor = g[1];
        }
        shapes.line(px(cursor), lineY, px(CORRIDOR_X2), lineY);
    }

    private void drawAirlock(ShapeRenderer shapes, int a) {
        float[] al = AIRLOCKS[a];
        boolean north = al[8] == 1;
        float innerY = north ? al[6] : al[7]; // chamber edge facing the corridor
        float outerY = north ? al[7] : al[6]; // chamber edge in the hull
        Palette.set(shapes, 0.12f, 0.22f, 0.28f, 1f);
        // spur walls
        shapes.line(px(al[0]), py(al[2], 0), px(al[0]), py(al[3], 0));
        shapes.line(px(al[1]), py(al[2], 0), px(al[1]), py(al[3], 0));
        // chamber sides
        shapes.line(px(al[4]), py(al[6], 0), px(al[4]), py(al[7], 0));
        shapes.line(px(al[5]), py(al[6], 0), px(al[5]), py(al[7], 0));
        // corridor-side edge, gap where the spur enters
        shapes.line(px(al[4]), py(innerY, 0), px(al[0]), py(innerY, 0));
        shapes.line(px(al[1]), py(innerY, 0), px(al[5]), py(innerY, 0));
        // hull-side edge, gap for the outer door
        float mid = (al[4] + al[5]) / 2f;
        shapes.line(px(al[4]), py(outerY, 0), px(mid - 15), py(outerY, 0));
        shapes.line(px(mid + 15), py(outerY, 0), px(al[5]), py(outerY, 0));
        // doors
        drawDoor(shapes, al[0], al[1], innerY, doorOpen[innerDoor(a)], state.doorHeldOpen[innerDoor(a)]);
        drawDoor(shapes, mid - 15, mid + 15, outerY, doorOpen[outerDoor(a)], state.doorHeldOpen[outerDoor(a)]);
    }

    /** Floor colour, blended toward warning red as the compartment's oxygen drops. */
    private static void setFloorColor(ShapeRenderer shapes, float r, float g, float b, float oxy) {
        float deficit = 1f - oxy;
        Palette.set(shapes, 
            MathUtils.lerp(r, 0.30f, deficit),
            MathUtils.lerp(g, 0.02f, deficit),
            MathUtils.lerp(b, 0.03f, deficit), 1f);
    }

    private static void setDoorLightColor(ShapeRenderer shapes, boolean open) {
        if (open) Palette.set(shapes, 0.3f, 0.9f, 0.45f, 1f);
        else Palette.set(shapes, 0.9f, 0.2f, 0.15f, 1f);
    }

    /**
     * A door line: solid amber when sealed, retracted posts when open —
     * green for automatic operation, bright white-green when held open by the player.
     */
    private void drawDoor(ShapeRenderer shapes, float x1, float x2, float y, boolean open, boolean held) {
        float lineY = py(y, 0);
        if (open) {
            if (held) Palette.set(shapes, 0.75f, 1f, 0.8f, 1f);
            else Palette.set(shapes, 0.3f, 0.9f, 0.45f, 1f);
            float stub = (x2 - x1) * 0.15f;
            shapes.line(px(x1), lineY, px(x1 + stub), lineY);
            shapes.line(px(x2 - stub), lineY, px(x2), lineY);
            if (held) { // small raised posts mark a latched-open door
                shapes.line(px(x1 + stub), lineY, px(x1 + stub), lineY + 3f);
                shapes.line(px(x2 - stub), lineY, px(x2 - stub), lineY + 3f);
            }
        } else {
            Palette.set(shapes, 0.95f, 0.7f, 0.2f, 1f);
            shapes.line(px(x1), lineY, px(x2), lineY);
            shapes.line(px(x1), lineY + 1.5f, px(x2), lineY + 1.5f); // doubled: reads as a sealed hatch
        }
    }

    /** A craft parked on the hangar floor: nose toward the carrier's bow, squashed by the deck projection. */
    private void drawParkedCraft(ShapeRenderer shapes, float cx, float cy, float scale, boolean pincer) {
        craftTransform.setToTranslation(px(cx), py(cy, 0), 0)
            .scale(scale * SCALE, scale * SCALE * SQUASH, 1f)
            .rotate(0, 0, 1, -90f);
        shapes.setTransformMatrix(craftTransform);
        if (pincer) ShipRenderer.drawPincer(shapes, 0f);
        else ShipRenderer.drawB2(shapes);
        shapes.setTransformMatrix(craftIdentity);
    }

    private final Matrix4 craftTransform = new Matrix4();
    private final Matrix4 craftIdentity = new Matrix4();

    private void fillDeckPoly(ShapeRenderer shapes) {
        // hull as two triangles + nose triangle (filled polys must be convex pieces)
        float lx = px(-16), rx = px(TAPER_X), nx = px(NOSE_X);
        float by = py(HULL_BOTTOM, 0), ty = py(HULL_TOP, 0), my = py(55, 0);
        shapes.triangle(lx, by, rx, by, rx, ty);
        shapes.triangle(lx, by, rx, ty, lx, ty);
        shapes.triangle(rx, by, nx, my, rx, ty);
    }

    private void lineDeckPoly(ShapeRenderer shapes) {
        float lx = px(-16), rx = px(TAPER_X), nx = px(NOSE_X);
        float by = py(HULL_BOTTOM, 0), ty = py(HULL_TOP, 0), my = py(55, 0);
        shapes.line(lx, by, rx, by);
        shapes.line(rx, by, nx, my);
        shapes.line(nx, my, rx, ty);
        shapes.line(rx, ty, lx, ty);
        shapes.line(lx, ty, lx, by);
        // hull top wall edge
        float top = py(HULL_TOP, WALL_H);
        shapes.line(lx, top, rx, top);
    }
}
