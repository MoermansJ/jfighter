package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;

public class GameScreen implements Screen {
    // a massively larger battlespace: several screens across; the camera follows the
    // controlled ship and the radar carries the overview (densities scale via SpaceEffects)
    private static final float ARENA_WIDTH = 3600f;
    private static final float ARENA_HEIGHT = 2025f;
    private static final float VIEW_WIDTH = 1440f;  // camera window
    private static final float VIEW_HEIGHT = 810f;
    // HUD renders through its own 960x540 ortho matrix, unaffected by camera zoom
    private static final float HUD_W = JFighter.WORLD_WIDTH;
    private static final float HUD_H = JFighter.WORLD_HEIGHT;

    // solid arena walls: no wrapping or clipping through the border
    private static final float WALL_RESTITUTION = 0.5f;

    // enemy ships: idle drifters for now — targets to fly against and shoot at
    private static final float ENEMY_RADIUS = 18f;
    private static final float ENEMY_HP = 30f;
    private static final int CREDITS_PER_KILL = 40;

    // ship integrity: shields absorb first and recharge after a quiet spell
    private static final float SHIP_HIT_RADIUS = 18f;
    private static final float HIT_DAMAGE = 10f;
    private static final float SHIELD_RECHARGE_DELAY = 3f; // seconds without a hit
    private static final float SHIELD_RECHARGE_RATE = 8f;  // points per second
    private static final float DEFEAT_DELAY = 1.6f;        // linger on the wreck before the summary

    private final JFighter game;
    private final GameState state;

    private ShapeRenderer shapeRenderer;
    private SpriteBatch batch;
    private BitmapFont font;
    private FitViewport viewport;
    private Player player;
    private SpaceEffects effects;
    private final Array<Projectile> projectiles = new Array<>();
    private final Array<Enemy> enemies = new Array<>();
    // manual dev control: -1 = the fighter, otherwise an enemy index (no AI yet — TAB hands over the stick)
    private int controlled = -1;
    private float shieldSince;   // time since the last hit (drives recharge)
    private float shieldFlash;   // shimmer ring on shield hits
    // combat deck overlay (#121): the carrier's live deck monitor, mid-battle
    private ShipDeckView deckView;
    private boolean deckOpen;
    private CrewMember deckSelected;
    private float tacticalZoom = 1.4f; // Z cycles 1.0 / 1.4 / 1.9 / 10 (#146/#160)
    private static final float SPLASH_155 = 55f;
    private float scopeSweep;    // instrument clock (plot scanline)
    private static final float SONAR_PERIOD = 2.6f; // base seconds between pings
    private float sonarT = SONAR_PERIOD; // pulsing sonar on the contact monitor
    // sensor warfare (#185/#186/#192)
    private boolean sonarActive = true;
    private final Array<float[]> decoys = new Array<>(); // {x, y, vx, vy, life, seed}
    private float jamT;
    private float jamNextT = MathUtils.random(18f, 30f);

    /** How good the picture is: bridge crew sharpens it, passive mode and jamming blur it. */
    private float sensorQuality() {
        float q = 0.55f + 0.15f * state.roomStats[5];
        if (!sonarActive) q *= 0.6f;
        if (jamT > 0) q *= 0.5f;
        return MathUtils.clamp(q, 0.2f, 1f);
    }

    private float pingPeriod() {
        return (sonarActive ? SONAR_PERIOD : SONAR_PERIOD * 2f) / Math.max(0.4f, sensorQuality());
    }

    /** Plot position error for un-firm contacts: quality shrinks it, jamming blows it up. */
    private float contactJitter(int seed, boolean xAxis) {
        float q = sensorQuality();
        float base = (1f - q) * 55f * MathUtils.sin(scopeSweep * (xAxis ? 0.045f : 0.06f) + seed);
        float jam = jamT > 0 ? 120f * MathUtils.sin(scopeSweep * 0.4f + seed * (xAxis ? 3 : 7)) : 0f;
        return base + jam;
    }
    private float viewZoom = 1f; // legacy world-view zoom; the desk view keeps it at 1

    // bridge desk monitors (#162)
    private static final float RAD_CX = 170f;
    private static final float RAD_CY = 352f;
    private static final float RAD_R = 145f;
    private static final float RADAR_RANGE = 2400f; // world units at the scope edge
    private static final float PLOT_X = 350f;
    private static final float PLOT_Y = 270f;
    private static final float PLOT_W = 420f;
    private static final float PLOT_H = PLOT_W * ARENA_HEIGHT / ARENA_WIDTH;
    private static final float CREW_S = 0.32f; // deck monitor scale on the crew screen
    private static final float CREW_X = 350f;
    private static final float CREW_Y = 148f;
    private final Vector2 lastAim = new Vector2(ARENA_WIDTH / 2f, ARENA_HEIGHT / 2f);
    private final Vector2 carrierWaypoint = new Vector2(-1, -1);
    private final Array<Vector2> carrierQueue = new Array<>(); // chained helm legs (#191)

    private float plotPX(float wx) {
        return PLOT_X + wx / ARENA_WIDTH * PLOT_W;
    }

    private float plotPY(float wy) {
        return PLOT_Y + wy / ARENA_HEIGHT * PLOT_H;
    }

    private boolean inPlot(float hx, float hy) {
        return hx >= PLOT_X && hx <= PLOT_X + PLOT_W && hy >= PLOT_Y && hy <= PLOT_Y + PLOT_H;
    }

    private Vector2 plotToWorld(float hx, float hy) {
        return new Vector2((hx - PLOT_X) / PLOT_W * ARENA_WIDTH,
            (hy - PLOT_Y) / PLOT_H * ARENA_HEIGHT);
    }

    private boolean inCrew(float hx, float hy) {
        return hx >= CREW_X && hx <= CREW_X + 960f * CREW_S
            && hy >= CREW_Y && hy <= CREW_Y + 270f * CREW_S;
    }
    // desk instruments (#176-#189) + end-of-battle bookkeeping
    private final Array<String> signalLog = new Array<>();
    private final Array<Float> signalLogAge = new Array<>();
    private boolean endModal;
    private float endModalDelay = -1f;
    private String endHeadline = "";
    private int creditsAtStart;
    private boolean alarmSilenced;
    private float hullAtSilence;
    private float ammoFlashL;
    private float ammoFlashH;
    private float ammoFlashR;
    private int lastAmmoL;
    private int lastAmmoH;
    private int lastAmmoR;
    private float cupolaCd;      // MG-46 cupolas share a cadence clock (#119)
    private int cupolaBurstLeft; // rounds left in the current 5-8 round burst
    private float cupolaGapT;    // one-second breath between bursts
    private int cupolaBursts;    // bursts fired from the current belt
    private float cupolaReloadT; // 5s belt change
    private float fireCritT;     // fighter ablaze: hull dot (#99)
    private float helmCritT;     // fighter helm crippled: poor turning
    private String hudToast;
    private float hudToastT;
    private float defeatT = -1f; // >= 0 once the fighter is destroyed
    private static final float STORM_INTERVAL = 60f;   // #147: a flare a minute
    private static final float STORM_BUILDUP = 12f;    // long ramp before it hits
    private float stormTimer = MathUtils.random(20f, 40f); // stormy nodes: next radiation wave
    private float stormFlash;
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 hudMatrix = new Matrix4(); // HUD ignores camera zoom
    private final PauseMenu pause = new PauseMenu();
    private final Radar radar = new Radar(ARENA_WIDTH, ARENA_HEIGHT);
    private final Array<Weapon> weapons = new Array<>();
    private Enemy lockTarget; // auto-aim target for homing rockets
    private final Array<float[]> beams = new Array<>(); // {x1,y1,x2,y2,life,maxLife} laser flashes
    private final ControlsHelp controlsHelp = new ControlsHelp(new String[][]{
        {"SPACE", "fire selected mounts"},
        {"1-4", "toggle mount in the fire group"},
        {"SHIFT+1-4", "toggle mount AUTO"},
        {"UP/DOWN", "throttle"},
        {"LEFT/RIGHT", "turn"},
        {"LMB", "select / order on the PLOT monitor"},
        {"RMB", "carrier waypoint on the PLOT / deselect"},
        {"SHIFT+click", "chain waypoints"},
        {"G", "sonar active/passive"},
        {"N", "silence the alarm"},
        {"TAB", "take a fighter's stick"},
        {"R", "fighter rocket pod (on the stick)"},
        {"V", "deck monitor: crew, power, doors"},
        {"ESC", "pause menu"},
    });

    // death effects: assembled from sparks, tumbling hull shards and blast rings,
    // with a random kind and jittered parameters so no two kills look identical
    private enum DeathKind { FIREBALL, BREAKUP, CHAIN, OVERLOAD }

    private static class Spark {
        float x, y, vx, vy, life, maxLife;
        boolean gray; // smoke instead of fire (#125 trails)
    }

    private static class Shard {
        float x, y, vx, vy, rotation, spin, len, life, maxLife;
    }

    private static class Blast {
        float x, y, age, delay, speed, maxR;
    }

    private static class WingDebris {
        float x, y, vx, vy, rotation, spin, life = 25f;
        boolean left;
    }

    private static class CritToast {
        Enemy e;
        String text;
        float t = 2.5f;
    }

    private final Array<Spark> sparks = new Array<>();
    private final Array<CritToast> critToasts = new Array<>();
    private final Array<WingDebris> wingDebris = new Array<>();
    private final Array<float[]> shockwaves = new Array<>(); // {x, y, age, maxR, jitterSeed}
    private final Array<float[]> wrecks = new Array<>(); // {x, y, vx, vy, rot, spin, value} (#105)
    private int wrecksCollected; // feeds the post-battle salvage bonus (#124)
    private String salvageToast;
    private float salvageToastT;
    private enum Obj { ELIMINATE, SURVIVE, INTERCEPT, MOTHERSHIP }
    private Obj objective = Obj.ELIMINATE;
    private boolean objectiveDone;
    private float surviveT;
    private float waveT;
    private float shake;   // camera trauma, decays fast
    private boolean beaming;
    private final Array<Shard> shards = new Array<>();
    private final Array<Blast> blasts = new Array<>();

    // carrier ops (#117/#151): squads are the atomic fighter unit, dispatched by mouse
    private static final int SQUADRON_COUNT = 2;
    private static final int FIGHTERS_PER_SQUADRON = 3;
    private static final float FIGHTER_HP = 24f; // pooled per craft in the squad
    private static final float CARRIER_HIT_RADIUS = 80f;

    /** A whole friendly squadron flying as one unit: pooled strength/hp, squad weapons. */
    private static class Fighter {
        final Player body;
        int strength = FIGHTERS_PER_SQUADRON;
        float hp = FIGHTERS_PER_SQUADRON * FIGHTER_HP;
        final int squadron;
        final Weapon gun = new Weapon(Weapon.Type.LIGHT_CANNON);
        int rockets = 4;      // pod rounds per sortie (#126), restocked at the carrier
        float rocketCd;
        Enemy engaging; // current dogfight target
        // embark cycle (#154): 0 deployed, 1 stowing, 2 embarked, 3 launching
        int dock;
        float dockAnimT;

        Fighter(float x, float y, int squadron) {
            body = new Player(x, y);
            body.rotation = MathUtils.random(360f);
            body.thrustMult = 0.9f;  // slower fleet-wide (#153), still nimbler than the carrier
            body.turnMult = 1.2f;
            this.squadron = squadron;
        }

        float centerX() {
            return body.x + Player.WIDTH / 2f;
        }

        float centerY() {
            return body.y + Player.HEIGHT / 2f;
        }
    }

    /** Formation slot offset in unit space (rotated by heading when drawn). */
    private static float formX(int slot) {
        return (slot % 2 == 1 ? -1 : 1) * ((slot + 1) / 2) * 15f;
    }

    private static float formY(int slot) {
        return -((slot + 1) / 2) * 13f;
    }

    /** Deploy an embarked squad out of the hangar notch (#154). */
    private void launchSquad(Fighter f) {
        f.dock = 3;
        f.dockAnimT = 0.8f;
        // roll out of the portside hangar notch
        float ang = player.rotation;
        float ox = -34f * MathUtils.cosDeg(ang) - (-14f) * MathUtils.sinDeg(ang);
        float oy = -34f * MathUtils.sinDeg(ang) + (-14f) * MathUtils.cosDeg(ang);
        f.body.x = player.x + ox;
        f.body.y = player.y + oy;
        f.body.rotation = player.rotation - 90f;
        f.body.vx = player.vx;
        f.body.vy = player.vy;
        game.sfx.playClamp();
    }

    /** A squad wiped outside the round system: remove it and settle the leader's fate. */
    private void loseSquad(Fighter f) {
        int idx = fighters.indexOf(f, true);
        if (idx != -1) {
            if (controlled == idx) controlled = -1;
            else if (controlled > idx) controlled--;
            fighters.removeIndex(idx);
        }
        for (Dogfight d : dogfights) {
            d.friends.removeValue(f, true);
        }
        CrewMember lead = state.squadronLeader(f.squadron);
        if (lead != null) {
            lead.hp = 0f;
            showHudToast(lead.name + " LOST WITH " + state.squadronNames[f.squadron]);
        }
    }

    /** Pooled hp crossing a craft boundary pops one silhouette out of the formation. */
    private void updateSquadStrength(Fighter f) {
        int should = Math.max(0, (int) Math.ceil(f.hp / FIGHTER_HP));
        while (f.strength > should) {
            f.strength--;
            float ang = f.body.rotation;
            float ox = formX(f.strength) * MathUtils.cosDeg(ang) - formY(f.strength) * MathUtils.sinDeg(ang);
            float oy = formX(f.strength) * MathUtils.sinDeg(ang) + formY(f.strength) * MathUtils.cosDeg(ang);
            spawnDeathEffect(f.centerX() + ox, f.centerY() + oy, f.body.vx, f.body.vy);
        }
    }

    private static class Squadron {
        int mode; // 0 escort carrier, 1 move to point, 2 attack target
        final Array<Vector2> queue = new Array<>(); // chained waypoints (#191)
        float ox, oy;
        float orderDir; // heading of the last move order; idlers keep pointing this way (#132)
        Enemy target;
    }

    private final Array<Fighter> fighters = new Array<>();
    private final Squadron[] squadrons = new Squadron[SQUADRON_COUNT];
    private int selectedSquadron = -1;

    /** A latched fighter-vs-fighter engagement (#141): resolves in timed rounds. */
    private static class Dogfight {
        float x, y;
        final Array<Fighter> friends = new Array<>();
        final Array<Enemy> foes = new Array<>();
        float roundT = ROUND_TIME;
        float anim;
        int rounds;
    }

    private static final float ROUND_TIME = 2.6f;
    private static final float DOGFIGHT_RADIUS = 44f;
    private final Array<Dogfight> dogfights = new Array<>();

    private boolean latched(Fighter f) {
        for (Dogfight d : dogfights) {
            if (d.friends.contains(f, true)) return true;
        }
        return false;
    }

    private boolean latched(Enemy e) {
        for (Dogfight d : dogfights) {
            if (d.foes.contains(e, true)) return true;
        }
        return false;
    }

    private int squadronAlive(int s) {
        for (Fighter f : fighters) {
            if (f.squadron == s) return f.strength;
        }
        return 0;
    }

    private com.badlogic.gdx.math.Rectangle squadronTabRect(int s) {
        return new com.badlogic.gdx.math.Rectangle(10, HUD_H - 142 - s * 38, 150, 34);
    }

    /** HUD-space mouse position (the HUD ortho spans the whole window). */
    private Vector2 hudMouse() {
        return new Vector2(Gdx.input.getX() / (float) Gdx.graphics.getWidth() * HUD_W,
            HUD_H - Gdx.input.getY() / (float) Gdx.graphics.getHeight() * HUD_H);
    }

    /** Enemy squad armaments (#152): rolled per squad from the instance's available pool. */
    private enum SquadArms {
        MG(0.12f, 2f, 560f, 380f),
        AUTOCANNON(0.35f, 5f, 540f, 480f),
        CANNON(1.8f, 12f, 460f, 540f),
        ROCKETS(4f, 30f, 240f, 620f),
        HOMING(5f, 28f, 220f, 700f),
        TORPEDO(9f, 45f, 150f, 1400f);

        final float reload;
        final float damage;
        final float speed;
        final float range;

        SquadArms(float reload, float damage, float speed, float range) {
            this.reload = reload;
            this.damage = damage;
            this.speed = speed;
            this.range = range;
        }

        boolean rocket() {
            return this == ROCKETS || this == HOMING || this == TORPEDO;
        }
    }

    private final Array<SquadArms> instanceArms = new Array<>(); // this battle's arms market

    /** Random availability (#152): each instance fields a different slice of the arsenal. */
    private void rollInstanceArms() {
        instanceArms.clear();
        Array<SquadArms> pool = new Array<>(SquadArms.values());
        // deep sectors unlock the fancy ordnance more often
        if (state.sector < 2 && MathUtils.randomBoolean(0.6f)) pool.removeValue(SquadArms.TORPEDO, true);
        if (state.sector < 2 && MathUtils.randomBoolean(0.5f)) pool.removeValue(SquadArms.HOMING, true);
        pool.shuffle();
        int keep = MathUtils.random(3, Math.min(5, pool.size));
        for (int i = 0; i < keep; i++) {
            instanceArms.add(pool.get(i));
        }
    }

    private SquadArms rollArms() {
        if (instanceArms.isEmpty()) rollInstanceArms();
        return instanceArms.random();
    }

    private static class Enemy {
        final Player body; // reuses ship physics: drag keeps them slowly adrift
        float hp;
        float maxHp;
        final long seed = MathUtils.random.nextLong(); // deterministic damage-mark placement
        boolean onFire;      // crit: damage over time + flames
        boolean engineOut;   // crit: no thrust
        boolean helmDamaged; // crit: crippled turning
        // destructible sections (#73): wings shear off before the core gives out
        float leftWingHp = 12f;
        float rightWingHp = 12f;
        int strength = 1;   // squads pool craft into one unit (#151); capitals stay 1
        boolean recognized; // #167: unknown blip until a visual confirms the class
        final int blipSeed = MathUtils.random(999);
        SquadArms arms = SquadArms.MG; // rolled loadout (#152)
        float armsCd;
        boolean boss;       // heavy multi-section flagship (#106)
        boolean mothership; // hostile carrier fielding its own fighters (#145)
        boolean runner;     // intercept objective: flees for the arena edge (#104)

        float radius() {
            return mothership ? 78f : boss ? 40f : ENEMY_RADIUS;
        }
        // AI (#95): simple approach/orbit/break-off state machine with a light cannon (#96)
        int ai; // 0 approach, 1 orbit, 2 break off
        float aiT = MathUtils.random(1f, 3f);
        int orbitDir = MathUtils.randomSign();
        final Weapon gun = new Weapon(Weapon.Type.LIGHT_CANNON);

        Enemy(float x, float y, float maxHp) {
            hp = maxHp;
            body = new Player(x, y);
            body.rotation = MathUtils.random(360f);
            float angle = MathUtils.random(360f);
            float speed = MathUtils.random(8f, 22f);
            body.vx = MathUtils.cosDeg(angle) * speed;
            body.vy = MathUtils.sinDeg(angle) * speed;
        }

        float centerX() {
            return body.x + Player.WIDTH / 2f;
        }

        float centerY() {
            return body.y + Player.HEIGHT / 2f;
        }
    }

    public GameScreen(JFighter game, GameState state) {
        this.game = game;
        this.state = state;
    }

    @Override
    public void show() {
        shapeRenderer = new ShapeRenderer();
        batch = new SpriteBatch();
        font = game.fonts.font;
        Fonts.scale(font, 1.4f);
        viewport = new FitViewport(VIEW_WIDTH, VIEW_HEIGHT);
        player = new Player(120f, (ARENA_HEIGHT - Player.HEIGHT) / 2f);
        player.thrustMult = state.thrustMult() * (1f + 0.04f * state.roomStats[0])
            * (0.6f + 0.2f * state.power[GameState.PWR_ENGINES]); // engine crew + reactor power
        effects = new SpaceEffects(ARENA_WIDTH, ARENA_HEIGHT);
        hudMatrix.setToOrtho2D(0, 0, HUD_W, HUD_H);
        for (Weapon.Type t : state.loadout) {
            Weapon wp = new Weapon(t);
            wp.auto = t.ammoKind == Weapon.AmmoKind.LIGHT; // light/medium feeders mind themselves (#139)
            wp.selected = !wp.auto;
            weapons.add(wp);
        }
        state.weaponEnergy = state.maxWeaponEnergy; // fresh capacitors each engagement
        if (state.sector >= 3) { // ECM pickets seed false returns (#192)
            int n = MathUtils.random(1, 2);
            for (int i = 0; i < n; i++) {
                decoys.add(new float[]{MathUtils.random(ARENA_WIDTH * 0.5f, ARENA_WIDTH - 100f),
                    MathUtils.random(100f, ARENA_HEIGHT - 100f),
                    MathUtils.random(-14f, 14f), MathUtils.random(-14f, 14f),
                    40f, MathUtils.random(999f)});
            }
        }
        deckView = new ShipDeckView(state);
        deckView.setLifesigns(true); // combat overlay reads crew as heartbeat pips (#159)
        creditsAtStart = state.credits;
        lastAmmoL = state.ammoLight;
        lastAmmoH = state.ammoHeavy;
        lastAmmoR = state.ammoRockets;
        // squadrons launch with the carrier on the field
        for (int s = 0; s < SQUADRON_COUNT; s++) {
            squadrons[s] = new Squadron();
            fighters.add(new Fighter(player.x + MathUtils.random(-90f, 90f),
                player.y + MathUtils.random(-90f, 90f), s));
        }
        boolean bossFight = state.sector >= 3
            && state.map.getCurrentNode().id == state.map.lastNodeId;
        if (bossFight) {
            spawnBoss();
        } else if (state.sector >= 2 && MathUtils.randomBoolean(0.3f)) {
            // capital engagement (#145): an enemy carrier launching fighters in waves
            objective = Obj.MOTHERSHIP;
            waveT = 4f;
            Enemy ms = new Enemy(ARENA_WIDTH - 320f, ARENA_HEIGHT / 2f,
                Difficulty.enemyHp(state.sector) * 8f);
            ms.maxHp = ms.hp;
            ms.mothership = true;
            ms.leftWingHp = 80f;
            ms.rightWingHp = 80f;
            enemies.add(ms);
            addLog("LARGE SIGNATURE DETECTED");
        } else {
            spawnEnemies();
            float roll = MathUtils.random();
            if (roll < 0.25f) {
                objective = Obj.SURVIVE;
                surviveT = 60f;
                waveT = 12f;
            } else if (roll < 0.5f && enemies.size > 1) {
                objective = Obj.INTERCEPT;
                enemies.get(MathUtils.random(enemies.size - 1)).runner = true;
            }
        }
        game.sfx.startThruster();
    }

    /** Sector 3+: the jump gate is guarded by a heavy flagship (#106). */
    private void spawnBoss() {
        Enemy boss = new Enemy(ARENA_WIDTH - 300f, ARENA_HEIGHT / 2f,
            Difficulty.enemyHp(state.sector) * 6f);
        boss.maxHp = boss.hp;
        boss.boss = true;
        boss.leftWingHp = 60f;
        boss.rightWingHp = 60f;
        enemies.add(boss);
    }

    /** Pooled hp crossing a craft boundary blows one silhouette out of the hostile formation. */
    private void updateEnemyStrength(Enemy e) {
        if (e.boss || e.mothership) return;
        float perCraft = e.maxHp / Math.max(1, spawnStrengthOf(e));
        int should = Math.max(0, (int) Math.ceil(e.hp / perCraft));
        while (e.strength > should) {
            e.strength--;
            float ang = e.body.rotation;
            float ox = formX(e.strength) * MathUtils.cosDeg(ang) - formY(e.strength) * MathUtils.sinDeg(ang);
            float oy = formX(e.strength) * MathUtils.sinDeg(ang) + formY(e.strength) * MathUtils.cosDeg(ang);
            spawnDeathEffect(e.centerX() + ox, e.centerY() + oy, e.body.vx, e.body.vy);
        }
    }

    private int spawnStrengthOf(Enemy e) {
        return Math.max(1, Math.round(e.maxHp / Difficulty.enemyHp(state.sector)));
    }

    private void spawnEnemies() {
        int craft = Difficulty.enemyCount(state.sector) + 2;
        while (craft > 0) {
            int size = Math.min(craft, MathUtils.random(2, 3));
            craft -= size;
            float x, y;
            do {
                x = MathUtils.random(ARENA_WIDTH * 0.72f, ARENA_WIDTH - 80f); // out in the dark (#190)
                y = MathUtils.random(60f, ARENA_HEIGHT - 60f);
            } while (tooCloseToOthers(x, y));
            Enemy e = new Enemy(x, y, Difficulty.enemyHp(state.sector) * size);
            e.maxHp = e.hp;
            e.strength = size;
            e.arms = rollArms();
            e.body.thrustMult = 0.65f; // fleet-wide slowdown (#153)
            e.body.turnMult = 0.85f;
            enemies.add(e);
        }
    }

    private boolean tooCloseToOthers(float x, float y) {
        for (Enemy e : enemies) {
            if (Vector2.dst(x, y, e.body.x, e.body.y) < 140f) return true;
        }
        return false;
    }

    @Override
    public void render(float delta) {
        if (defeatT >= 0) {
            defeatT += delta;
            if (defeatT >= DEFEAT_DELAY) {
                game.currentRun = null; // run lost
                SaveGame.clear();
                game.setScreen(new SummaryScreen(game, state, false));
                return;
            }
        }
        for (int i = 0; i < signalLogAge.size; i++) {
            signalLogAge.set(i, signalLogAge.get(i) + delta);
        }
        // near-death klaxon (#182)
        boolean losingHp = fireCritT > 0;
        boolean nearDeath = state.hull < state.maxHull * 0.25f && defeatT < 0;
        if (alarmSilenced && state.hull < hullAtSilence - 1f) alarmSilenced = false; // fresh damage re-arms
        if (nearDeath && (enemies.size > 0 || losingHp) && !alarmSilenced && !endModal) {
            game.sfx.startAlarm();
        } else {
            game.sfx.stopAlarm();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.N) && !alarmSilenced) {
            alarmSilenced = true;
            hullAtSilence = state.hull;
            addLog("ALARM SILENCED");
        }
        // end-of-combat modal (#179): a beat after the objective, the desk stands down
        if (objectiveDone && !endModal && defeatT < 0) {
            if (endModalDelay < 0) endModalDelay = 1.6f;
            endModalDelay -= delta;
            if (endModalDelay <= 0) endModal = true;
        }
        if (endModal) {
            if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)
                    || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
                game.sfx.stopAlarm();
                game.setScreen(new OverworldScreen(game, state));
                return;
            }
        }
        if (defeatT < 0 && !endModal) pause.handleEscape();
        if (!pause.isOpen() && !endModal) {
            // stop rendering once the screen switches: hide() disposed our resources
            if (defeatT < 0 && handleInput(delta)) return;
            if (defeatT < 0) effects.handleFlightInput(controlledBody(), delta);
            shieldSince += delta;
            if (shieldSince >= SHIELD_RECHARGE_DELAY && state.shield < state.maxShield) {
                state.shield = Math.min(state.maxShield,
                    state.shield + (SHIELD_RECHARGE_RATE + state.shieldRechargeBonus())
                        * (state.power[GameState.PWR_SHIELDS] / 2f) * delta);
            }
            if (shieldFlash > 0) shieldFlash -= delta;
            player.updatePosition(delta);
            bounceOffWalls(player);
            updateFighters(delta);
            for (int i = enemies.size - 1; i >= 0; i--) {
                Enemy e = enemies.get(i);
                if (latched(e)) continue;
                updateEnemyAi(e, delta);
                e.body.updatePosition(delta);
                bounceOffWalls(e.body);
                if (e.onFire) {
                    e.hp -= 2.5f * delta; // burning until it gives out
                    if (MathUtils.random() < 14f * delta) {
                        addSparks(e.centerX() + MathUtils.random(-8f, 8f),
                            e.centerY() + MathUtils.random(-8f, 8f), e.body.vx, e.body.vy, 1);
                    }
                    if (e.hp <= 0) killEnemy(i);
                }
            }
            for (int i = critToasts.size - 1; i >= 0; i--) {
                CritToast ct = critToasts.get(i);
                ct.t -= delta;
                if (ct.t <= 0) critToasts.removeIndex(i);
            }
            for (int i = wingDebris.size - 1; i >= 0; i--) {
                WingDebris wd = wingDebris.get(i);
                wd.life -= delta;
                if (wd.life <= 0) {
                    wingDebris.removeIndex(i);
                    continue;
                }
                wd.x += wd.vx * delta;
                wd.y += wd.vy * delta;
                wd.rotation += wd.spin * delta;
            }
            effects.update(player, delta);
            // fighter condition: wings, crits and power feed the handling every frame
            if (fireCritT > 0) {
                fireCritT -= delta;
                state.hull = Math.max(1f, state.hull - 3f * delta);
                if (MathUtils.random() < 10f * delta) {
                    addSparks(player.x + Player.WIDTH / 2f + MathUtils.random(-8f, 8f),
                        player.y + Player.HEIGHT / 2f + MathUtils.random(-8f, 8f),
                        player.vx, player.vy, 1);
                }
            }
            if (helmCritT > 0) helmCritT -= delta;
            if (hudToastT > 0) hudToastT -= delta;
            if (state.ammoLight != lastAmmoL) { ammoFlashL = 0.3f; lastAmmoL = state.ammoLight; }
            if (state.ammoHeavy != lastAmmoH) { ammoFlashH = 0.3f; lastAmmoH = state.ammoHeavy; }
            if (state.ammoRockets != lastAmmoR) { ammoFlashR = 0.3f; lastAmmoR = state.ammoRockets; }
            if (ammoFlashL > 0) ammoFlashL -= delta;
            if (ammoFlashH > 0) ammoFlashH -= delta;
            if (ammoFlashR > 0) ammoFlashR -= delta;
            if (carrierWaypoint.x >= 0 && Vector2.dst(carrierWaypoint.x, carrierWaypoint.y,
                    player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f) < 60f) {
                if (carrierQueue.size > 0) { // the helm takes the next leg (#191)
                    Vector2 next = carrierQueue.removeIndex(0);
                    carrierWaypoint.set(next.x, next.y);
                    effects.setAutopilotTarget(next.x, next.y);
                } else {
                    carrierWaypoint.set(-1, -1);
                }
            }
            state.weaponEnergy = Math.min(state.maxWeaponEnergy,
                state.weaponEnergy + (2f + 4f * state.power[GameState.PWR_WEAPONS]) * delta);
            if (Dev.MODE) {
                state.ammoLight = Math.max(state.ammoLight, 600);
                state.ammoHeavy = Math.max(state.ammoHeavy, 24);
                state.ammoRockets = Math.max(state.ammoRockets, 12);
            }
            // the mothership: ponderous, all reactor and crew (#117)
            player.thrustMult = state.thrustMult() * (1f + 0.04f * state.roomStats[0])
                * (0.6f + 0.2f * state.power[GameState.PWR_ENGINES]) * 0.28f; // capital pace (#178)
            player.turnMult = (helmCritT > 0 ? 0.4f : 1f) * 0.24f; // swinging a city block (#148)
            effects.setCarrierHull(true);
            game.sfx.setThrusterLevel(controlledBody().thrustLevel);
            updateSensors(delta);
            updateDogfights(delta);
            deckView.update(delta); // the deck lives through the battle (#121)
            deckView.setFocus(deckSelected, null);
            updateObjective(delta);
            updateWrecks();
            updateCupolas(delta);
            fireWeapons(delta);
            updateProjectiles(delta);
            updateEffects(delta);
            if (state.map.getCurrentNode().stormy && defeatT < 0) {
                stormTimer -= delta;
                if (stormTimer <= 0) {
                    // solar radiation event: a wave knocks a chunk off the shields
                    stormTimer = STORM_INTERVAL;
                    stormFlash = 0.7f;
                    damagePlayer(8f);
                }
            }
            if (stormFlash > 0) stormFlash -= delta;
        }

        ScreenUtils.clear(0.012f, 0.018f, 0.026f, 1f);
        viewport.apply();
        if (shake > 0) shake = Math.max(0f, shake - delta * 2.2f); // rattles the desk lamps someday
        if (shieldFlash > 0) shieldFlash -= delta;
        scopeSweep = (scopeSweep + 55f * delta) % 360f;
        sonarT += delta;
        if (sonarT >= pingPeriod()) {
            sonarT -= pingPeriod();
            if (sonarActive) game.sfx.playSonar(); // passive listening makes no noise (#186)
        }

        // the bridge desk (#162): everything is read off instrument monitors
        drawDesk();
        drawRadarMonitor();
        drawPlotMonitor();
        drawCrewMonitor();
        drawShieldGauge();
        drawThrustLever();
        drawDeckConsole();
        drawCardiographPanel();
        drawHullIndicator();
        drawSignalLog();
        drawAnnunciator();

        drawHud();

        if (deckOpen) {
            shapeRenderer.setProjectionMatrix(hudMatrix);
            deckView.renderShapes(shapeRenderer);
            batch.setProjectionMatrix(hudMatrix);
            batch.begin();
            deckView.renderText(batch, font);
            Fonts.scale(font, 0.95f);
            font.setColor(Color.GRAY);
            font.draw(batch, "[V] close deck", 12, 286);
            Fonts.scale(font, 1.4f);
            batch.end();
        }
        if (endModal) drawEndModal();
        if (pause.isOpen()
                && pause.render(shapeRenderer, batch, font, hudMatrix, viewport, objectiveDone)) {
            game.setScreen(new OverworldScreen(game, state));
        }
    }

    /** End-of-combat modal (#179): the sim stands frozen behind it; one way out. */
    private void drawEndModal() {
        float mw = 380f;
        float mh = 190f;
        float mx = (HUD_W - mw) / 2f;
        float my = (HUD_H - mh) / 2f;
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(0.015f, 0.03f, 0.04f, 1f);
        shapeRenderer.rect(mx, my, mw, mh);
        shapeRenderer.end();
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        Palette.set(shapeRenderer, 0.3f, 0.6f, 0.7f, 1f);
        shapeRenderer.rect(mx, my, mw, mh);
        shapeRenderer.rect(mx + 3, my + 3, mw - 6, mh - 6);
        shapeRenderer.setColor(0.3f, 0.8f, 0.4f, 1f);
        shapeRenderer.rect(mx + mw / 2f - 70f, my + 18f, 140f, 32f);
        shapeRenderer.end();
        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        font.setColor(Color.GREEN);
        GlyphLayout hl = new GlyphLayout(font, endHeadline.isEmpty() ? "ENGAGEMENT RESOLVED" : endHeadline);
        font.draw(batch, hl, mx + (mw - hl.width) / 2f, my + mh - 22f);
        Fonts.scale(font, 0.95f);
        font.setColor(Color.YELLOW);
        int earned = state.credits - creditsAtStart;
        GlyphLayout cg = new GlyphLayout(font, "CREDITS " + (earned >= 0 ? "+" : "") + earned);
        font.draw(batch, cg, mx + (mw - cg.width) / 2f, my + mh - 62f);
        font.setColor(Color.GRAY);
        GlyphLayout sg = new GlyphLayout(font, salvageToast == null ? "" : salvageToast);
        font.draw(batch, sg, mx + (mw - sg.width) / 2f, my + mh - 84f);
        Fonts.scale(font, 1.4f);
        font.setColor(Color.GREEN);
        GlyphLayout lg = new GlyphLayout(font, "LEAVE");
        font.draw(batch, lg, mx + (mw - lg.width) / 2f, my + 40f);
        batch.end();
    }

    /**
     * The plot/command monitor (#162): the whole arena as a clickable chart —
     * every contact, every projectile with its trajectory projection, and all
     * command markers live here.
     */
    private void drawPlotMonitor() {
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(0.008f, 0.022f, 0.03f, 1f);
        shapeRenderer.rect(PLOT_X, PLOT_Y, PLOT_W, PLOT_H);
        shapeRenderer.end();
        // everything on the glass stays on the glass (#166)
        float sx = Gdx.graphics.getWidth() / (float) HUD_W;
        float sy = Gdx.graphics.getHeight() / (float) HUD_H;
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor((int) (PLOT_X * sx), (int) (PLOT_Y * sy),
            (int) (PLOT_W * sx) + 1, (int) (PLOT_H * sy) + 1);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        // plotting grid
        Palette.set(shapeRenderer, 0.05f, 0.1f, 0.12f, 1f);
        for (float gx = 300; gx < ARENA_WIDTH; gx += 300) {
            shapeRenderer.line(plotPX(gx), PLOT_Y, plotPX(gx), PLOT_Y + PLOT_H);
        }
        for (float gy = 300; gy < ARENA_HEIGHT; gy += 300) {
            shapeRenderer.line(PLOT_X, plotPY(gy), PLOT_X + PLOT_W, plotPY(gy));
        }
        float kx = PLOT_W / ARENA_WIDTH;
        float ox = player.x + Player.WIDTH / 2f;
        float oy = player.y + Player.HEIGHT / 2f;
        // aureola: auto-engagement reach (#156)
        Palette.set(shapeRenderer, 0.12f, 0.26f, 0.3f, 1f);
        float lastR = -1f;
        for (Weapon aw : weapons) {
            if (!aw.auto) continue;
            float r = autoRange(aw.type);
            if (Math.abs(r - lastR) < 1f) continue;
            lastR = r;
            shapeRenderer.circle(plotPX(ox), plotPY(oy), r * kx, 40);
        }
        if (state.mothership.countMounts(Mothership.MOUNT_MG46) > 0) {
            shapeRenderer.circle(plotPX(ox), plotPY(oy), 380f * 1.75f * kx, 40);
        }
        // shield status ring (#158)
        if (defeatT < 0 && state.shield > 0.5f) {
            float frac = state.shield / state.maxShield;
            shapeRenderer.setColor(0.1f + 0.2f * frac, 0.25f + 0.35f * frac, 0.5f + 0.4f * frac, 1f);
            shapeRenderer.circle(plotPX(ox), plotPY(oy), 96f * kx + 3f, 20);
        }
        // trajectory projections for other calibers (#162); 155 shells trail instead
        for (Projectile p : projectiles) {
            float vx = p.velX();
            float vy = p.velY();
            float spd = Math.max(1f, (float) Math.sqrt(vx * vx + vy * vy));
            boolean hostile = p.shooter != player;
            if (p.fuseT >= 0) {
                // fired shell: the line draws BEHIND it, launch point to here
                shapeRenderer.setColor(0.38f, 0.4f, 0.42f, 1f);
                shapeRenderer.line(plotPX(p.originX), plotPY(p.originY), plotPX(p.x), plotPY(p.y));
                // the shell itself + predicted burst point with splash forecast
                shapeRenderer.setColor(0.9f, 0.85f, 0.6f, 1f);
                shapeRenderer.circle(plotPX(p.x), plotPY(p.y), 2f, 6);
                shapeRenderer.setColor(0.38f, 0.4f, 0.42f, 1f);
                float exw = p.x + vx / spd * spd * p.fuseT;
                float eyw = p.y + vy / spd * spd * p.fuseT;
                shapeRenderer.circle(plotPX(exw), plotPY(eyw), SPLASH_155 * kx, 12);
                continue;
            }
            if (!p.rocket) continue; // small/medium gunfire stays off the plot
            float reach = spd * Math.min(p.life, 3.5f);
            float exw = p.x + vx / spd * reach;
            float eyw = p.y + vy / spd * reach;
            if (hostile) shapeRenderer.setColor(0.4f, 0.14f, 0.12f, 1f);
            else shapeRenderer.setColor(0.16f, 0.3f, 0.34f, 1f);
            shapeRenderer.line(plotPX(p.x), plotPY(p.y), plotPX(exw), plotPY(eyw));
            if (hostile) shapeRenderer.setColor(1f, 0.45f, 0.35f, 1f);
            else shapeRenderer.setColor(0.65f, 0.9f, 0.75f, 1f);
            shapeRenderer.circle(plotPX(p.x), plotPY(p.y), 2.2f, 6);
            shapeRenderer.setColor(hostile ? 0.4f : 0.16f, hostile ? 0.14f : 0.3f,
                hostile ? 0.12f : 0.34f, 1f);
            shapeRenderer.circle(plotPX(exw), plotPY(eyw), SPLASH_155 * kx, 12);
            // incoming ordnance warning: homing on us, or dumb-fired but on a collision course
            if (hostile && ordnanceThreatensUs(p) && MathUtils.sin(scopeSweep * 0.5f) > -0.2f) {
                shapeRenderer.setColor(1f, 0.9f, 0.2f, 1f);
                float bx = plotPX(p.x);
                float by = plotPY(p.y);
                float s2 = 7f;
                float leg = 3f;
                for (int cx2 = -1; cx2 <= 1; cx2 += 2) {
                    for (int cy2 = -1; cy2 <= 1; cy2 += 2) {
                        shapeRenderer.line(bx + cx2 * s2, by + cy2 * s2,
                            bx + cx2 * (s2 - leg), by + cy2 * s2);
                        shapeRenderer.line(bx + cx2 * s2, by + cy2 * s2,
                            bx + cx2 * s2, by + cy2 * (s2 - leg));
                    }
                }
            }
        }
        // 155 fire control (#157): the LANDING ZONE the salvo could scatter into
        boolean heavySel = false;
        for (Weapon aw : weapons) {
            if (aw.selected && aw.type == Weapon.Type.CANNON_155) heavySel = true;
        }
        if (heavySel && defeatT < 0) {
            float dist = Vector2.dst(ox, oy, lastAim.x, lastAim.y);
            float zone = dispersion155(dist);
            shapeRenderer.setColor(0.45f, 0.47f, 0.5f, 1f);
            shapeRenderer.circle(plotPX(lastAim.x), plotPY(lastAim.y), zone * kx, 20);
            shapeRenderer.setColor(0.28f, 0.3f, 0.32f, 1f); // worst case: zone edge + splash
            shapeRenderer.circle(plotPX(lastAim.x), plotPY(lastAim.y), (zone + SPLASH_155) * kx, 24);
            // heavy barrel bearing (#173): watch the slow slew walk onto the zone
            for (Weapon aw : weapons) {
                if (!aw.selected || aw.type != Weapon.Type.CANNON_155) continue;
                float barrel = player.rotation + aw.turret;
                shapeRenderer.setColor(0.75f, 0.7f, 0.45f, 1f);
                shapeRenderer.line(plotPX(ox), plotPY(oy),
                    plotPX(ox) - MathUtils.sinDeg(barrel) * dist * kx,
                    plotPY(oy) + MathUtils.cosDeg(barrel) * dist * kx);
            }
        }
        // carrier course projection (#183): dashed track along the velocity, time ticks
        if (defeatT < 0) {
            float cvx = player.vx;
            float cvy = player.vy;
            float cspd = (float) Math.sqrt(cvx * cvx + cvy * cvy);
            if (cspd > 6f) {
                Palette.set(shapeRenderer, 0.25f, 0.6f, 0.5f, 1f);
                for (int seg = 0; seg < 12; seg++) { // dashed: 10s of track
                    float t1 = seg * 10f / 12f;
                    float t2 = t1 + 10f / 24f;
                    shapeRenderer.line(plotPX(ox + cvx * t1), plotPY(oy + cvy * t1),
                        plotPX(ox + cvx * t2), plotPY(oy + cvy * t2));
                }
                for (int tick = 1; tick <= 2; tick++) { // 5s marks
                    float tt = tick * 5f;
                    float txp = plotPX(ox + cvx * tt);
                    float typ = plotPY(oy + cvy * tt);
                    shapeRenderer.line(txp - 2.5f, typ - 2.5f, txp + 2.5f, typ + 2.5f);
                    shapeRenderer.line(txp - 2.5f, typ + 2.5f, txp + 2.5f, typ - 2.5f);
                }
                if (carrierWaypoint.x >= 0) { // the helm's intent bends toward the waypoint
                    Palette.set(shapeRenderer, 0.18f, 0.4f, 0.36f, 1f);
                    shapeRenderer.line(plotPX(ox + cvx * 3f), plotPY(oy + cvy * 3f),
                        plotPX(carrierWaypoint.x), plotPY(carrierWaypoint.y));
                }
            }
        }
        // aim cones for the light/medium batteries
        for (Weapon aw : weapons) {
            if (aw.type.ammoKind != Weapon.AmmoKind.LIGHT || (!aw.auto && !aw.selected)) continue;
            float reach = (aw.auto ? autoRange(aw.type) : weaponRange(aw.type)) * kx;
            float bearing = player.rotation + (aw.type.turretArc > 0 ? aw.turret : 0f);
            Palette.set(shapeRenderer, 0.16f, 0.36f, 0.42f, 1f);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                float a = bearing + sgn * 7f;
                shapeRenderer.line(plotPX(ox), plotPY(oy),
                    plotPX(ox) - MathUtils.sinDeg(a) * reach, plotPY(oy) + MathUtils.cosDeg(a) * reach);
            }
        }
        // the cupolas' cone follows whatever they're tracking, inside the 170-degree mask (#171)
        if (state.mothership.countMounts(Mothership.MOUNT_MG46) > 0 && cupolaReloadT <= 0) {
            float reachM = 380f * 1.75f * kx;
            Palette.set(shapeRenderer, 0.1f, 0.22f, 0.26f, 1f);
            for (int sgn = -1; sgn <= 1; sgn += 2) { // mask edges
                float a = player.rotation + sgn * 85f;
                shapeRenderer.line(plotPX(ox), plotPY(oy),
                    plotPX(ox) - MathUtils.sinDeg(a) * 380f * 1.75f * 0.4f,
                    plotPY(oy) + MathUtils.cosDeg(a) * 380f * 1.75f * 0.4f);
            }
            Enemy near = null;
            float bestD = 380f * 1.75f;
            for (Enemy e : enemies) {
                float dd = Vector2.dst(ox, oy, e.centerX(), e.centerY());
                if (dd >= bestD) continue;
                float bearing2 = MathUtils.atan2(-(e.centerX() - ox), e.centerY() - oy)
                    * MathUtils.radiansToDegrees;
                float off2 = ((bearing2 - player.rotation) % 360f + 540f) % 360f - 180f;
                if (Math.abs(off2) > 85f) continue;
                bestD = dd;
                near = e;
            }
            if (near != null) {
                float bearing = MathUtils.atan2(-(near.centerX() - ox), near.centerY() - oy)
                    * MathUtils.radiansToDegrees;
                float reach = 380f * 1.75f * kx;
                Palette.set(shapeRenderer, 0.2f, 0.42f, 0.38f, 1f);
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    float a = bearing + sgn * 5f;
                    shapeRenderer.line(plotPX(ox), plotPY(oy),
                        plotPX(ox) - MathUtils.sinDeg(a) * reach, plotPY(oy) + MathUtils.cosDeg(a) * reach);
                }
            }
        }
        // dogfight furballs
        Palette.set(shapeRenderer, 0.8f, 0.6f, 0.25f, 1f);
        for (Dogfight d : dogfights) {
            shapeRenderer.circle(plotPX(d.x), plotPY(d.y),
                (DOGFIGHT_RADIUS + 6f) * kx + 2f * MathUtils.sin(d.anim * 3f), 16);
        }
        // shockwaves read as blooms
        for (float[] swv : shockwaves) {
            float t = swv[2] / 0.3f;
            shapeRenderer.setColor(1f, 0.9f * (1 - t) + 0.1f, 0.4f * (1 - t), 1f);
            shapeRenderer.circle(plotPX(swv[0]), plotPY(swv[1]), swv[3] * t * kx + 1f, 10);
        }
        // wrecks
        shapeRenderer.setColor(0.4f, 0.38f, 0.32f, 1f);
        for (float[] wk : wrecks) {
            shapeRenderer.circle(plotPX(wk[0]), plotPY(wk[1]), 2f, 6);
        }
        // decoys and un-firm contacts read as anonymous returns (#167/#192)
        Palette.set(shapeRenderer, 0.5f, 0.55f, 0.5f, 1f);
        for (float[] dc : decoys) {
            float dxp = plotPX(dc[0] + contactJitter((int) dc[5], true));
            float dyp = plotPY(dc[1] + contactJitter((int) dc[5], false));
            shapeRenderer.circle(dxp, dyp, 3f, 8);
        }
        // hostiles: diamonds (one per craft is soup at this scale — one glyph, size by strength)
        for (Enemy e : enemies) {
            if (!e.recognized) { // unknown contact: neutral blip, position un-firm
                Palette.set(shapeRenderer, 0.5f, 0.55f, 0.5f, 1f);
                shapeRenderer.circle(plotPX(e.centerX() + contactJitter(e.blipSeed, true)),
                    plotPY(e.centerY() + contactJitter(e.blipSeed, false)),
                    e.mothership || e.boss ? 5f : 3f, 8);
                continue;
            }
            float px = plotPX(e.centerX() + (jamT > 0 ? contactJitter(e.blipSeed, true) : 0f));
            float py = plotPY(e.centerY() + (jamT > 0 ? contactJitter(e.blipSeed, false) : 0f));
            float ms = e.mothership ? 9f : e.boss ? 7f : 3f + 0.7f * e.strength;
            if (e.arms == SquadArms.TORPEDO && !e.boss && !e.mothership) {
                shapeRenderer.setColor(1f, 0.4f, 0.5f, 1f);
            } else if (e.runner) {
                shapeRenderer.setColor(1f, 0.75f, 0.2f, 1f);
            } else {
                shapeRenderer.setColor(0.95f, 0.25f, 0.2f, 1f);
            }
            shapeRenderer.line(px - ms, py, px, py + ms);
            shapeRenderer.line(px, py + ms, px + ms, py);
            shapeRenderer.line(px + ms, py, px, py - ms);
            shapeRenderer.line(px, py - ms, px - ms, py);
            if (e.mothership) shapeRenderer.circle(px, py, ms + 3f, 12);
        }
        // own squads: chevrons, selection ring, order markers
        for (Fighter f : fighters) {
            if (f.dock == 2) continue;
            float px = plotPX(f.centerX());
            float py = plotPY(f.centerY());
            float ms = 3f + 0.6f * f.strength;
            float ang = f.body.rotation;
            float fxm = -MathUtils.sinDeg(ang);
            float fym = MathUtils.cosDeg(ang);
            float rxm = MathUtils.cosDeg(ang);
            float rym = MathUtils.sinDeg(ang);
            shapeRenderer.setColor(f.squadron == 0
                ? new Color(0.25f, 0.85f, 0.4f, 1f) : new Color(0.3f, 0.8f, 0.75f, 1f));
            shapeRenderer.line(px - rxm * ms - fxm * ms, py - rym * ms - fym * ms, px + fxm * ms, py + fym * ms);
            shapeRenderer.line(px + rxm * ms - fxm * ms, py + rym * ms - fym * ms, px + fxm * ms, py + fym * ms);
            if (f.squadron == selectedSquadron) {
                shapeRenderer.setColor(Color.WHITE);
                shapeRenderer.circle(px, py, ms + 4f, 12);
            }
        }
        if (selectedSquadron != -1) {
            Squadron sq = squadrons[selectedSquadron];
            if (sq.mode == 1) {
                shapeRenderer.setColor(0.4f, 0.9f, 1f, 1f);
                float mx = plotPX(sq.ox);
                float my = plotPY(sq.oy);
                shapeRenderer.line(mx - 3, my - 3, mx + 3, my + 3);
                shapeRenderer.line(mx - 3, my + 3, mx + 3, my - 3);
            } else if (sq.mode == 2 && sq.target != null) {
                shapeRenderer.setColor(1f, 0.4f, 0.3f, 1f);
                shapeRenderer.circle(plotPX(sq.target.centerX()), plotPY(sq.target.centerY()), 7f, 12);
            }
        }
        // carrier waypoint + chained legs
        if (carrierWaypoint.x >= 0) {
            Palette.set(shapeRenderer, 0.35f, 0.7f, 0.8f, 1f);
            float wx = plotPX(carrierWaypoint.x);
            float wy = plotPY(carrierWaypoint.y);
            shapeRenderer.circle(wx, wy, 4f, 10);
            shapeRenderer.line(wx - 6, wy, wx + 6, wy);
            Palette.set(shapeRenderer, 0.22f, 0.45f, 0.5f, 1f);
            float lx = wx;
            float ly = wy;
            for (Vector2 q : carrierQueue) {
                float qx = plotPX(q.x);
                float qy = plotPY(q.y);
                shapeRenderer.line(lx, ly, qx, qy);
                shapeRenderer.circle(qx, qy, 2.5f, 8);
                lx = qx;
                ly = qy;
            }
        }
        // selected squadron's chained legs
        if (selectedSquadron != -1 && squadrons[selectedSquadron].queue.size > 0) {
            Squadron sqc = squadrons[selectedSquadron];
            Palette.set(shapeRenderer, 0.3f, 0.6f, 0.75f, 1f);
            float lx = plotPX(sqc.ox);
            float ly = plotPY(sqc.oy);
            for (Vector2 q : sqc.queue) {
                float qx = plotPX(q.x);
                float qy = plotPY(q.y);
                shapeRenderer.line(lx, ly, qx, qy);
                shapeRenderer.circle(qx, qy, 2f, 8);
                lx = qx;
                ly = qy;
            }
        }
        // own ship: mini silhouette
        if (defeatT < 0) {
            Palette.set(shapeRenderer, 0.4f, 0.95f, 0.6f, 1f);
            transform.set(hudMatrix).translate(plotPX(ox), plotPY(oy), 0)
                .rotate(0, 0, 1, player.rotation).scale(0.09f, 0.09f, 1f);
            shapeRenderer.setProjectionMatrix(transform);
            ShipRenderer.drawCarrier(shapeRenderer);
            shapeRenderer.setProjectionMatrix(hudMatrix);
        }
        shapeRenderer.end();
        Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_SCISSOR_TEST);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        // frame + scanline
        Palette.set(shapeRenderer, 0.2f, 0.4f, 0.5f, 1f);
        shapeRenderer.rect(PLOT_X, PLOT_Y, PLOT_W, PLOT_H);
        ShipDeckView.drawScanline(shapeRenderer, scopeSweep * 0.1f, PLOT_X + 2, PLOT_X + PLOT_W - 2,
            PLOT_Y + 2, PLOT_Y + PLOT_H - 2);
        // command cursor glyphs (#154), now on the chart
        if (selectedSquadron != -1 && defeatT < 0) {
            Vector2 hm = hudMouse();
            if (inPlot(hm.x, hm.y)) {
                Vector2 wp = plotToWorld(hm.x, hm.y);
                boolean overCarrier = Vector2.dst(wp.x, wp.y, ox, oy) < CARRIER_HIT_RADIUS + 16f;
                boolean docked = false;
                for (Fighter f : fighters) {
                    if (f.squadron == selectedSquadron && f.dock == 2) docked = true;
                }
                if (overCarrier) {
                    shapeRenderer.setColor(0.4f, 0.9f, 1f, 1f);
                    shapeRenderer.line(hm.x + 10, hm.y + 12, hm.x + 10, hm.y + 5);
                    shapeRenderer.line(hm.x + 7, hm.y + 8, hm.x + 10, hm.y + 5);
                    shapeRenderer.line(hm.x + 13, hm.y + 8, hm.x + 10, hm.y + 5);
                    shapeRenderer.line(hm.x + 5, hm.y + 3, hm.x + 15, hm.y + 3);
                } else if (docked) {
                    shapeRenderer.setColor(0.4f, 1f, 0.6f, 1f);
                    shapeRenderer.line(hm.x + 10, hm.y + 4, hm.x + 10, hm.y + 11);
                    shapeRenderer.line(hm.x + 7, hm.y + 8, hm.x + 10, hm.y + 11);
                    shapeRenderer.line(hm.x + 13, hm.y + 8, hm.x + 10, hm.y + 11);
                    shapeRenderer.line(hm.x + 5, hm.y + 3, hm.x + 15, hm.y + 3);
                }
            }
        }
        shapeRenderer.end();
        // impact countdowns over live 155 solutions
        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.85f);
        font.setColor(0.62f, 0.64f, 0.66f, 1f);
        for (Projectile p : projectiles) {
            if (p.fuseT < 0) continue;
            font.draw(batch, String.format("%.1f", p.fuseT), plotPX(p.x) + 5, plotPY(p.y) + 4);
        }
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /** The vital-signs monitor (#162): the deck view scaled onto its own desk screen. */
    private void drawCrewMonitor() {
        deckView.setCompact(true); // doors/power stay off this little screen
        // map the deck monitor's native region (0..960 x 270..540) into the desk screen
        Matrix4 m = hudMatrix.cpy()
            .translate(CREW_X, CREW_Y, 0)
            .scale(CREW_S, CREW_S, 1f)
            .translate(0, -270f, 0);
        shapeRenderer.setProjectionMatrix(m);
        deckView.renderShapes(shapeRenderer);
        shapeRenderer.setProjectionMatrix(hudMatrix);
        batch.setProjectionMatrix(m);
        batch.begin();
        deckView.renderText(batch, font);
        batch.end();
        batch.setProjectionMatrix(hudMatrix);
        deckView.setCompact(false);
        // left-to-right CRT scan band (#175): the EKG chart feed look
        float mw = 960f * CREW_S;
        float mh = 270f * CREW_S;
        float scanX = CREW_X + (scopeSweep * 2.4f % mw);
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        Palette.set(shapeRenderer, 0.3f, 0.9f, 1f, 0.22f);
        shapeRenderer.line(scanX, CREW_Y + 2, scanX, CREW_Y + mh - 2);
        shapeRenderer.line(scanX + 1, CREW_Y + 2, scanX + 1, CREW_Y + mh - 2);
        Palette.set(shapeRenderer, 0.3f, 0.9f, 1f, 0.1f);
        shapeRenderer.line(scanX - 2, CREW_Y + 2, scanX - 2, CREW_Y + mh - 2);
        shapeRenderer.line(scanX + 3, CREW_Y + 2, scanX + 3, CREW_Y + mh - 2);
        shapeRenderer.end();
    }

    // desk deck-console geometry: power rows + door buttons on the desk surface
    private static final float PWRD_X = 15f;
    private static final float PWRD_Y0 = 106f; // top row, stepping down 16px
    private static final float[] DOOR_BTN_XS = {195f, 266f, 195f, 266f};
    private static final float[] DOOR_BTN_YS = {88f, 88f, 58f, 58f};
    private static final float DOOR_BTN_W2 = 63f;
    private static final float DOOR_BTN_H2 = 22f;

    /** Power distribution + door cluster as desk hardware (moved off the vital-signs screen). */
    private void drawDeckConsole() {
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < GameState.POWER_SYSTEMS.length; i++) {
            float ry = PWRD_Y0 - i * 16f;
            Palette.set(shapeRenderer, 0.3f, 0.45f, 0.52f, 1f);
            shapeRenderer.rect(PWRD_X + 58f, ry, 10f, 10f);   // [-]
            shapeRenderer.rect(PWRD_X + 130f, ry, 10f, 10f);  // [+]
            shapeRenderer.line(PWRD_X + 60.5f, ry + 5f, PWRD_X + 65.5f, ry + 5f);
            shapeRenderer.line(PWRD_X + 132.5f, ry + 5f, PWRD_X + 137.5f, ry + 5f);
            shapeRenderer.line(PWRD_X + 135f, ry + 2.5f, PWRD_X + 135f, ry + 7.5f);
            for (int p = 0; p < GameState.POWER_CAP[i]; p++) {
                float pxp = PWRD_X + 72f + p * 18f;
                if (p < state.power[i]) {
                    Palette.set(shapeRenderer, 0.35f, 0.85f, 0.5f, 1f);
                    shapeRenderer.rect(pxp, ry + 2f, 14f, 6f);
                    shapeRenderer.rect(pxp + 1, ry + 3f, 12f, 4f);
                } else {
                    Palette.set(shapeRenderer, 0.2f, 0.3f, 0.35f, 1f);
                    shapeRenderer.rect(pxp, ry + 2f, 14f, 6f);
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            if (deckView.doorButtonHot(i)) shapeRenderer.setColor(0.9f, 0.25f, 0.2f, 1f);
            else Palette.set(shapeRenderer, 0.3f, 0.45f, 0.52f, 1f);
            shapeRenderer.rect(DOOR_BTN_XS[i], DOOR_BTN_YS[i], DOOR_BTN_W2, DOOR_BTN_H2);
        }
        shapeRenderer.end();
        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.8f);
        for (int i = 0; i < GameState.POWER_SYSTEMS.length; i++) {
            float ry = PWRD_Y0 - i * 16f;
            Palette.set(font, 0.5f, 0.7f, 0.78f, 1f);
            font.draw(batch, GameState.POWER_SYSTEMS[i], PWRD_X, ry + 9f);
        }
        Palette.set(font, 0.75f, 0.85f, 0.9f, 1f);
        font.draw(batch, "REACTOR " + (state.reactorUnits - state.unallocatedPower())
            + "/" + state.reactorUnits, PWRD_X, PWRD_Y0 + 24f);
        for (int i = 0; i < 4; i++) {
            if (deckView.doorButtonHot(i)) Palette.set(font, 0.95f, 0.35f, 0.3f, 1f);
            else Palette.set(font, 0.5f, 0.7f, 0.78f, 1f);
            GlyphLayout dl = new GlyphLayout(font, deckView.doorButtonLabel(i));
            font.draw(batch, dl, DOOR_BTN_XS[i] + (DOOR_BTN_W2 - dl.width) / 2f, DOOR_BTN_YS[i] + 15f);
        }
        Palette.set(font, 0.4f, 0.62f, 0.7f, 1f);
        font.draw(batch, "DOORS", DOOR_BTN_XS[0], DOOR_BTN_YS[0] + 42f);
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /** Clicks on the desk deck-console: power +/- and the door cluster. */
    private boolean handleDeckConsoleClick(float hx, float hy) {
        for (int i = 0; i < GameState.POWER_SYSTEMS.length; i++) {
            float ry = PWRD_Y0 - i * 16f;
            if (hy < ry - 2f || hy > ry + 12f) continue;
            if (hx >= PWRD_X + 56f && hx <= PWRD_X + 70f) {
                deckView.pressPowerButton(i * 2);
                return true;
            }
            if (hx >= PWRD_X + 128f && hx <= PWRD_X + 142f) {
                deckView.pressPowerButton(i * 2 + 1);
                return true;
            }
        }
        for (int i = 0; i < 4; i++) {
            if (hx >= DOOR_BTN_XS[i] && hx <= DOOR_BTN_XS[i] + DOOR_BTN_W2
                    && hy >= DOOR_BTN_YS[i] && hy <= DOOR_BTN_YS[i] + DOOR_BTN_H2) {
                deckView.pressDoorButton(i);
                return true;
            }
        }
        return false;
    }

    // thrust lever geometry (#desk): a real handle you grab and set
    private static final float LEV_X = 915f;
    private static final float LEV_Y0 = 240f;
    private static final float LEV_H = 180f;

    /** Grabbing the lever (mouse held on it) sets the carrier's throttle step directly. */
    private void handleThrustLever() {
        if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT) || controlled >= 0 || defeatT >= 0) return;
        Vector2 hm = hudMouse();
        if (hm.x < LEV_X - 26f || hm.x > LEV_X + 26f
                || hm.y < LEV_Y0 - 16f || hm.y > LEV_Y0 + LEV_H + 16f) return;
        float frac = MathUtils.clamp((hm.y - LEV_Y0) / LEV_H, 0f, 1f);
        player.throttle = Math.round(frac * Player.THROTTLE_STEPS);
    }

    /** The thrust lever: a slotted quadrant with a grip handle riding the throttle step. */
    private void drawThrustLever() {
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        // slot
        Palette.set(shapeRenderer, 0.25f, 0.42f, 0.5f, 1f);
        shapeRenderer.line(LEV_X - 3f, LEV_Y0, LEV_X - 3f, LEV_Y0 + LEV_H);
        shapeRenderer.line(LEV_X + 3f, LEV_Y0, LEV_X + 3f, LEV_Y0 + LEV_H);
        shapeRenderer.line(LEV_X - 3f, LEV_Y0, LEV_X + 3f, LEV_Y0);
        shapeRenderer.line(LEV_X - 3f, LEV_Y0 + LEV_H, LEV_X + 3f, LEV_Y0 + LEV_H);
        // step notches; the burn zone reads hotter
        for (int i = 0; i <= Player.THROTTLE_STEPS; i++) {
            float y = LEV_Y0 + LEV_H * i / (float) Player.THROTTLE_STEPS;
            if (i >= 8) shapeRenderer.setColor(0.8f, 0.45f, 0.2f, 1f);
            else Palette.set(shapeRenderer, 0.18f, 0.3f, 0.36f, 1f);
            shapeRenderer.line(LEV_X - (i % 5 == 0 ? 14f : 9f), y, LEV_X - 5f, y);
        }
        shapeRenderer.end();
        // handle: filled grip riding the current step; actual thrust ghost behind it
        float hy = LEV_Y0 + LEV_H * player.throttle / (float) Player.THROTTLE_STEPS;
        float ghostY = LEV_Y0 + LEV_H * player.thrustLevel; // the engines catching up
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        Palette.set(shapeRenderer, 0.12f, 0.24f, 0.28f, 1f);
        shapeRenderer.rect(LEV_X - 8f, ghostY - 1.5f, 16f, 3f);
        Palette.set(shapeRenderer, 0.35f, 0.7f, 0.8f, 1f);
        shapeRenderer.rect(LEV_X - 16f, hy - 5f, 32f, 10f);
        shapeRenderer.setColor(0.05f, 0.1f, 0.12f, 1f);
        for (int i = 0; i < 3; i++) { // grip lines
            shapeRenderer.rect(LEV_X - 12f, hy - 3f + i * 3f, 24f, 1f);
        }
        shapeRenderer.end();
        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.95f);
        Palette.set(font, 0.4f, 0.62f, 0.7f, 1f);
        GlyphLayout tl = new GlyphLayout(font, "THRUST");
        font.draw(batch, tl, LEV_X - tl.width / 2f, LEV_Y0 + LEV_H + 26f);
        font.setColor(Color.GRAY);
        GlyphLayout pv = new GlyphLayout(font, (player.throttle * 10) + "%");
        font.draw(batch, pv, LEV_X - pv.width / 2f, LEV_Y0 - 12f);
        // actual way on the ship, in knots (#180)
        float kn = (float) Math.sqrt(player.vx * player.vx + player.vy * player.vy) * 0.15f;
        Palette.set(font, 0.4f, 0.62f, 0.7f, 1f);
        GlyphLayout kg = new GlyphLayout(font, Math.round(kn) + " kn");
        font.draw(batch, kg, LEV_X - kg.width / 2f, LEV_Y0 - 26f);
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /** Shield flask on the desk: a vertical vial of plasma that drains as the charge falls. */
    private void drawShieldGauge() {
        float fx = 845f;   // tube centre line
        float fy = 250f;   // bottom of the tube (reserve bulb)
        float fh = 150f;   // tube height
        float fw = 30f;    // tube bore
        float frac = state.maxShield > 0 ? state.shield / state.maxShield : 0f;
        float t = scopeSweep * 0.4f; // slosh clock
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(0.01f, 0.02f, 0.03f, 1f);
        shapeRenderer.rect(fx - fw / 2f, fy, fw, fh);
        shapeRenderer.circle(fx, fy, fw / 2f + 4f, 18);
        if (frac > 0.01f) {
            float level = fh * frac;
            float flick = 0.85f + 0.15f * MathUtils.sin(t * 6f);
            float lowPulse = frac < 0.25f ? 0.5f + 0.5f * MathUtils.sin(t * 12f) : 1f;
            shapeRenderer.setColor(0.3f * flick * lowPulse, 0.55f * flick * lowPulse, 1f * flick, 1f);
            shapeRenderer.circle(fx, fy, fw / 2f + 1f, 18); // the reserve bulb stays lit
            shapeRenderer.rect(fx - fw / 2f + 2f, fy, fw - 4f, Math.max(0f, level - 4f));
            // sloshing surface on top of the plasma
            float sy = fy + level - 4f;
            for (int i = 0; i < 5; i++) {
                float wob = MathUtils.sin(t * 5f + i * 1.7f) * 2.2f;
                shapeRenderer.rect(fx - fw / 2f + 2f + i * (fw - 4f) / 5f, sy,
                    (fw - 4f) / 5f, Math.max(0.5f, 3.5f + wob));
            }
            // plasma glints rising through the liquid
            for (int i = 0; i < 3; i++) {
                float gyp = fy + ((t * 14f + i * 43f) % Math.max(8f, level - 8f));
                float gxp = fx + MathUtils.sin(t * 3f + i * 2.1f) * (fw / 2f - 6f);
                shapeRenderer.setColor(0.7f, 0.85f, 1f, 1f);
                shapeRenderer.circle(gxp, gyp, 1.4f, 6);
            }
        }
        shapeRenderer.end();
        // flask glass: tube walls, round reserve bulb at the base, neck cap on top
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        Palette.set(shapeRenderer, 0.25f, 0.42f, 0.5f, 1f);
        shapeRenderer.line(fx - fw / 2f, fy, fx - fw / 2f, fy + fh);
        shapeRenderer.line(fx + fw / 2f, fy, fx + fw / 2f, fy + fh);
        shapeRenderer.circle(fx, fy, fw / 2f + 4f, 20);
        shapeRenderer.line(fx - fw / 2f, fy + fh, fx + fw / 2f, fy + fh);
        shapeRenderer.line(fx - fw / 2f + 4f, fy + fh + 3f, fx + fw / 2f - 4f, fy + fh + 3f); // cap
        // graduation ticks up the side
        Palette.set(shapeRenderer, 0.18f, 0.3f, 0.36f, 1f);
        for (int i = 1; i < 4; i++) {
            shapeRenderer.line(fx + fw / 2f, fy + fh * i / 4f, fx + fw / 2f + 5f, fy + fh * i / 4f);
        }
        shapeRenderer.end();
        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.95f);
        Palette.set(font, 0.4f, 0.62f, 0.7f, 1f);
        GlyphLayout sl = new GlyphLayout(font, "SHIELD");
        font.draw(batch, sl, fx - sl.width / 2f, fy + fh + 26f);
        font.setColor(frac < 0.25f ? Color.RED : Color.GRAY);
        GlyphLayout pv = new GlyphLayout(font, Math.round(frac * 100) + "%");
        font.draw(batch, pv, fx - pv.width / 2f, fy - 18f);
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /** Crew cardiographs (#176): a name and a live EKG trace per crew member. */
    private void drawCardiographPanel() {
        float px0 = 668f;
        float pw = 122f;
        float rowH = 15f;
        float top = CREW_Y + 270f * CREW_S;
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        Palette.set(shapeRenderer, 0.22f, 0.36f, 0.42f, 1f);
        shapeRenderer.rect(px0 - 6, CREW_Y - 6, pw + 74, top - CREW_Y + 12);
        for (int i = 0; i < state.crew.size() && i < 6; i++) {
            CrewMember c = state.crew.get(i);
            float baseY = top - 10f - i * rowH;
            float bpm = deckView.bpmFor(c);
            if (c.isDead()) {
                shapeRenderer.setColor(0.35f, 0.37f, 0.39f, 1f); // flat grey trace
                shapeRenderer.line(px0 + 62, baseY, px0 + 62 + pw, baseY);
                continue;
            }
            if (bpm >= 170f) shapeRenderer.setColor(0.95f, 0.3f, 0.25f, 1f);
            else if (bpm >= 110f) shapeRenderer.setColor(0.95f, 0.75f, 0.3f, 1f);
            else Palette.set(shapeRenderer, 0.3f, 0.9f, 0.5f, 1f);
            float prevY = baseY;
            for (int sx2 = 0; sx2 < pw; sx2 += 3) {
                float phase = ((scopeSweep * 0.6f * bpm / 60f) - sx2 * 0.045f) % 1f;
                if (phase < 0) phase += 1f;
                float y = baseY + ekgY(phase) * 5f;
                shapeRenderer.line(px0 + 62 + sx2 - 3, prevY, px0 + 62 + sx2, y);
                prevY = y;
            }
        }
        shapeRenderer.end();
        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.8f);
        for (int i = 0; i < state.crew.size() && i < 6; i++) {
            CrewMember c = state.crew.get(i);
            font.setColor(c.isDead() ? Color.DARK_GRAY : Color.GRAY);
            String nm = c.name.length() > 9 ? c.name.substring(0, 9) : c.name;
            font.draw(batch, nm, px0, top - 6f - i * rowH);
        }
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /** Classic PQRST-ish waveform: mostly flat with a sharp systole spike. */
    private static float ekgY(float phase) {
        if (phase < 0.06f) return phase / 0.06f * 0.3f;             // P bump
        if (phase < 0.1f) return 0.3f - (phase - 0.06f) / 0.04f * 0.3f;
        if (phase < 0.14f) return -(phase - 0.1f) / 0.04f * 0.4f;   // Q dip
        if (phase < 0.2f) return -0.4f + (phase - 0.14f) / 0.06f * 2.4f; // R spike
        if (phase < 0.26f) return 2f - (phase - 0.2f) / 0.06f * 2.6f;    // S drop
        if (phase < 0.32f) return -0.6f + (phase - 0.26f) / 0.06f * 0.6f;
        if (phase < 0.5f) return MathUtils.sin((phase - 0.32f) / 0.18f * MathUtils.PI) * 0.35f; // T wave
        return 0f;
    }

    /** Hull integrity (#177): a segmented strip that reddens as plates give out. */
    private void drawHullIndicator() {
        float hx0 = 700f;
        float hy0 = 505f;
        float frac = state.hull / state.maxHull;
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 10; i++) {
            boolean lit = frac > i / 10f;
            if (!lit) shapeRenderer.setColor(0.08f, 0.1f, 0.11f, 1f);
            else if (frac < 0.25f) shapeRenderer.setColor(0.9f, 0.25f, 0.2f, 1f);
            else if (frac < 0.55f) shapeRenderer.setColor(0.85f, 0.6f, 0.2f, 1f);
            else shapeRenderer.setColor(0.35f, 0.75f, 0.45f, 1f);
            shapeRenderer.rect(hx0 + i * 20f, hy0, 16f, 10f);
        }
        shapeRenderer.end();
        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.8f);
        Palette.set(font, 0.4f, 0.62f, 0.7f, 1f);
        font.draw(batch, "HULL " + Math.round(frac * 100) + "%", hx0, hy0 + 24f);
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /** Signal log (#184): the last few battle events, dimming with age. */
    private void drawSignalLog() {
        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.8f);
        for (int i = 0; i < signalLog.size; i++) {
            float age = signalLogAge.get(i);
            float a = MathUtils.clamp(1.2f - age * 0.08f, 0.25f, 1f);
            Palette.set(font, 0.35f * a + 0.1f, 0.6f * a + 0.1f, 0.65f * a + 0.1f, 1f);
            font.draw(batch, "> " + signalLog.get(i), 665, 128 - (signalLog.size - 1 - i) * 13f);
        }
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /** Damage-control annunciator (#188): a warning lamp per room, plus intruders. */
    private void drawAnnunciator() {
        float ax0 = 640f;
        float ay0 = 12f;
        boolean blinkOn = MathUtils.sin(scopeSweep * 0.6f) > 0f;
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 8; i++) {
            boolean fire = deckView.roomBurning(i);
            boolean breach = state.roomIntegrity[i] < 0.5f;
            boolean stn = state.stationHealth[i] <= 0f;
            boolean o2 = state.oxygen[i] < 0.3f;
            boolean warn = fire || breach || stn || o2;
            if (warn && (blinkOn || !fire)) {
                if (fire) shapeRenderer.setColor(1f, 0.4f, 0.1f, 1f);
                else if (breach || o2) shapeRenderer.setColor(0.9f, 0.2f, 0.15f, 1f);
                else shapeRenderer.setColor(0.85f, 0.7f, 0.2f, 1f);
            } else {
                shapeRenderer.setColor(0.07f, 0.1f, 0.11f, 1f);
            }
            shapeRenderer.rect(ax0 + i * 30f, ay0, 24f, 12f);
        }
        // intruder lamp
        boolean intruders = false;
        for (CrewMember b : state.boarders) {
            if (!b.isDead()) intruders = true;
        }
        if (intruders && blinkOn) shapeRenderer.setColor(1f, 0.15f, 0.1f, 1f);
        else shapeRenderer.setColor(0.07f, 0.1f, 0.11f, 1f);
        shapeRenderer.rect(ax0 + 240f, ay0, 24f, 12f);
        shapeRenderer.end();
        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.7f);
        Palette.set(font, 0.35f, 0.5f, 0.56f, 1f);
        String[] codes = {"ENG", "CRG", "HGR", "QRT", "WPN", "BRG", "MED", "LIF"};
        for (int i = 0; i < 8; i++) {
            font.draw(batch, codes[i], ax0 + i * 30f + 3f, ay0 + 24f);
        }
        font.draw(batch, "INT", ax0 + 243f, ay0 + 24f);
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /** Desk furniture: panel seams, monitor bezels and stands (#162). */
    private void drawDesk() {
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        Palette.set(shapeRenderer, 0.1f, 0.14f, 0.16f, 1f);
        shapeRenderer.line(0, 130, HUD_W, 130);   // desk edge
        shapeRenderer.line(0, 126, HUD_W, 126);
        Palette.set(shapeRenderer, 0.07f, 0.1f, 0.12f, 1f);
        for (int i = 1; i < 6; i++) {
            shapeRenderer.line(i * 160f, 0, i * 160f, 122); // desk plate seams
        }
        // monitor bezels + stands
        Palette.set(shapeRenderer, 0.22f, 0.36f, 0.42f, 1f);
        shapeRenderer.rect(RAD_CX - RAD_R - 14, RAD_CY - RAD_R - 14, RAD_R * 2 + 28, RAD_R * 2 + 28);
        shapeRenderer.rect(PLOT_X - 8, PLOT_Y - 8, PLOT_W + 16, PLOT_H + 16);
        shapeRenderer.rect(CREW_X - 8, CREW_Y - 8, 960 * CREW_S + 16, 270 * CREW_S + 16);
        Palette.set(shapeRenderer, 0.14f, 0.22f, 0.26f, 1f);
        shapeRenderer.line(RAD_CX - 24, RAD_CY - RAD_R - 14, RAD_CX - 34, 130);
        shapeRenderer.line(RAD_CX + 24, RAD_CY - RAD_R - 14, RAD_CX + 34, 130);
        shapeRenderer.line(PLOT_X + PLOT_W / 2f - 30, PLOT_Y - 8, PLOT_X + PLOT_W / 2f - 30, CREW_Y + 270 * CREW_S + 8);
        shapeRenderer.line(PLOT_X + PLOT_W / 2f + 30, PLOT_Y - 8, PLOT_X + PLOT_W / 2f + 30, CREW_Y + 270 * CREW_S + 8);
        shapeRenderer.end();
        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.95f);
        Palette.set(font, 0.4f, 0.62f, 0.7f, 1f);
        font.draw(batch, "SONAR", RAD_CX - RAD_R - 10, RAD_CY + RAD_R + 26);
        font.draw(batch, "PLOT", PLOT_X, PLOT_Y + PLOT_H + 20);
        font.draw(batch, "VITAL SIGNS", CREW_X, CREW_Y + 270 * CREW_S + 20);
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /**
     * The radar monitor (#162): circular scope centred on own ship. The sweep
     * reveals signatures; blips decay behind it, phosphor-style.
     */
    private void drawRadarMonitor() {
        float ox = player.x + Player.WIDTH / 2f;
        float oy = player.y + Player.HEIGHT / 2f;
        float k = RAD_R / RADAR_RANGE;
        shapeRenderer.setProjectionMatrix(hudMatrix);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(0.008f, 0.03f, 0.028f, 1f);
        shapeRenderer.circle(RAD_CX, RAD_CY, RAD_R, 48);
        shapeRenderer.end();
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        Palette.set(shapeRenderer, 0.1f, 0.24f, 0.26f, 1f);
        for (int r = 1; r <= 3; r++) {
            shapeRenderer.circle(RAD_CX, RAD_CY, RAD_R * r / 3f, 40);
        }
        shapeRenderer.line(RAD_CX - RAD_R, RAD_CY, RAD_CX + RAD_R, RAD_CY);
        shapeRenderer.line(RAD_CX, RAD_CY - RAD_R, RAD_CX, RAD_CY + RAD_R);
        // sonar pulse: an expanding ring, bright at the wavefront with a fading wake
        float pulseR = sonarT / pingPeriod() * RAD_R;
        Palette.set(shapeRenderer, 0.25f, 0.6f, 0.55f, 1f);
        shapeRenderer.circle(RAD_CX, RAD_CY, pulseR, 44);
        if (pulseR > 8f) {
            Palette.set(shapeRenderer, 0.12f, 0.32f, 0.3f, 1f);
            shapeRenderer.circle(RAD_CX, RAD_CY, pulseR - 6f, 40);
        }
        // own ship: a bare centre dot — the sonar is a pure contact instrument (#169)
        Palette.set(shapeRenderer, 0.4f, 0.95f, 0.6f, 1f);
        shapeRenderer.circle(RAD_CX, RAD_CY, 2f, 8);
        shapeRenderer.end();
        // signatures, phosphor-decayed behind the sweep
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (Enemy e : enemies) {
            float sz = e.mothership ? 4.5f : e.boss ? 3.5f : 1.6f + 0.5f * e.strength;
            radarBlip(e.centerX() - ox, e.centerY() - oy, k, sz,
                e.arms == SquadArms.TORPEDO ? 1f : 0.95f, 0.3f, e.arms == SquadArms.TORPEDO ? 0.45f : 0.22f);
        }
        for (Fighter f : fighters) {
            if (f.dock == 2) continue;
            radarBlip(f.centerX() - ox, f.centerY() - oy, k, 1.4f + 0.4f * f.strength, 0.3f, 0.9f, 0.45f);
        }
        for (Projectile p : projectiles) {
            float sz = p.rocket ? 1.6f : 0.8f;
            radarBlip(p.x - ox, p.y - oy, k, sz, p.rocket ? 1f : 0.6f, p.rocket ? 0.55f : 0.7f, 0.35f);
        }
        for (float[] wk : wrecks) {
            radarBlip(wk[0] - ox, wk[1] - oy, k, 1.2f, 0.45f, 0.42f, 0.36f);
        }
        for (float[] dc : decoys) {
            radarBlip(dc[0] - ox, dc[1] - oy, k, 2f, 0.8f, 0.5f, 0.3f); // decoys ping true
        }
        if (jamT > 0) { // jamming: the scope fills with garbage
            for (int i = 0; i < 26; i++) {
                float a = MathUtils.random(360f);
                float r = MathUtils.random(RAD_R - 8f);
                shapeRenderer.setColor(0.5f, 0.5f, 0.45f, 1f);
                shapeRenderer.circle(RAD_CX + MathUtils.cosDeg(a) * r,
                    RAD_CY + MathUtils.sinDeg(a) * r, MathUtils.random(0.6f, 1.6f), 5);
            }
        }
        shapeRenderer.end();
    }

    /** A line segment on the radar, both ends clamped to the scope radius. */
    private void radarSeg(float x1, float y1, float x2, float y2, float k) {
        // cheap clip: sample the segment and draw the in-range part as dashes
        float steps = 24;
        for (int i = 0; i < steps; i++) {
            float t1 = i / steps;
            float t2 = (i + 0.6f) / steps;
            float ax = (x1 + (x2 - x1) * t1) * k;
            float ay = (y1 + (y2 - y1) * t1) * k;
            float bx2 = (x1 + (x2 - x1) * t2) * k;
            float by2 = (y1 + (y2 - y1) * t2) * k;
            if (ax * ax + ay * ay > RAD_R * RAD_R || bx2 * bx2 + by2 * by2 > RAD_R * RAD_R) continue;
            shapeRenderer.line(RAD_CX + ax, RAD_CY + ay, RAD_CX + bx2, RAD_CY + by2);
        }
    }

    /** A phosphor blip: lit by the sonar wavefront, decaying until the next ping. */
    private void radarBlip(float relX, float relY, float k, float size, float r, float g, float b) {
        float sx = relX * k;
        float sy = relY * k;
        float range = (float) Math.sqrt(sx * sx + sy * sy);
        if (range > RAD_R - 4) return; // beyond scope range
        // time since the expanding ring crossed this blip's range
        float period = pingPeriod();
        float sincePass = sonarT - range / RAD_R * period;
        if (sincePass < 0) sincePass += period;
        float glow = Math.max(0.12f, 1f - sincePass / period);
        // a brief hot flash right on the wavefront
        if (sincePass < 0.12f) glow = 1.2f;
        shapeRenderer.setColor(Math.min(1f, r * glow), Math.min(1f, g * glow), Math.min(1f, b * glow), 1f);
        shapeRenderer.circle(RAD_CX + sx, RAD_CY + sy, size * (sincePass < 0.12f ? 1.35f : 1f), 6);
    }

    /** A small clockwatch: face + a minute hand that sweeps down with the timer. */
    private void drawClockIcon(float cx, float cy, float remaining, float total) {
        shapeRenderer.setColor(0.85f, 0.75f, 0.35f, 1f);
        shapeRenderer.circle(cx, cy, 4.5f, 12);
        shapeRenderer.line(cx, cy + 4.5f, cx, cy + 6f); // crown
        float hand = 90f + 360f * (1f - MathUtils.clamp(remaining / Math.max(0.01f, total), 0f, 1f));
        shapeRenderer.line(cx, cy, cx + MathUtils.cosDeg(hand) * 3.5f, cy + MathUtils.sinDeg(hand) * 3.5f);
        shapeRenderer.line(cx, cy, cx + MathUtils.cosDeg(90f) * 2f, cy + MathUtils.sinDeg(90f) * 2f);
    }

    /** Mechanical drum counter (#189): boxed digits that rattle while the pool drains. */
    private void drawDrumCounter(Weapon.Type t, float x, float y) {
        int value;
        float flash;
        char tag;
        switch (t.ammoKind) {
            case LIGHT: value = state.ammoLight; flash = ammoFlashL; tag = 'L'; break;
            case HEAVY: value = state.ammoHeavy; flash = ammoFlashH; tag = 'H'; break;
            case ROCKET: value = state.ammoRockets; flash = ammoFlashR; tag = 'R'; break;
            default:
                font.setColor(state.weaponEnergy < 5f ? Color.RED : Color.GRAY);
                font.draw(batch, "E " + Math.round(state.weaponEnergy / state.maxWeaponEnergy * 100)
                    + "%", x, y + 11);
                return;
        }
        String digits = String.format("%04d", Math.min(9999, Math.max(0, value)));
        font.setColor(Color.GRAY);
        font.draw(batch, String.valueOf(tag), x, y + 11);
        for (int dIdx = 0; dIdx < 4; dIdx++) {
            float jit = flash > 0 ? MathUtils.random(-1.2f, 1.2f) : 0f; // drums rolling
            font.setColor(value == 0 ? Color.RED : new Color(0.8f, 0.82f, 0.85f, 1f));
            font.draw(batch, String.valueOf(digits.charAt(dIdx)), x + 10 + dIdx * 8f, y + 11 + jit);
        }
    }

    private void drawHud() {
        shapeRenderer.setProjectionMatrix(hudMatrix);

        // radar: arena at a glance

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int s = 0; s < SQUADRON_COUNT; s++) {
            if (squadronAlive(s) == 0) continue;
            com.badlogic.gdx.math.Rectangle tr = squadronTabRect(s);
            if (s == selectedSquadron) shapeRenderer.setColor(Color.WHITE);
            else shapeRenderer.setColor(0.3f, 0.42f, 0.46f, 1f);
            shapeRenderer.rect(tr.x, tr.y, tr.width, tr.height);
        }
        shapeRenderer.end();

        // hull + shield bars
        float barW = 120f;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(0.22f, 0.08f, 0.08f, 1f);
        shapeRenderer.rect(10, HUD_H - 86, barW, 7);
        shapeRenderer.setColor(0.85f, 0.3f, 0.25f, 1f);
        shapeRenderer.rect(10, HUD_H - 86, barW * state.hull / state.maxHull, 7);
        shapeRenderer.setColor(0.05f, 0.1f, 0.18f, 1f);
        shapeRenderer.rect(10, HUD_H - 98, barW, 7);
        shapeRenderer.setColor(0.35f, 0.7f, 1f, 1f);
        shapeRenderer.rect(10, HUD_H - 98, barW * state.shield / state.maxShield, 7);
        shapeRenderer.end();

        // weapon cards along the bottom centre
        float cardW = 78f;
        float cardH = 34f;
        float cardsX = (HUD_W - weapons.size * (cardW + 6f)) / 2f;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < weapons.size; i++) {
            Weapon w = weapons.get(i);
            float x = cardsX + i * (cardW + 6f);
            shapeRenderer.setColor(0.03f, 0.05f, 0.06f, 1f);
            shapeRenderer.rect(x, 8, cardW, cardH);
            // reload sweep along the card bottom
            float frac = w.type.reload <= 0 ? 1f : MathUtils.clamp(1f - w.cooldown / w.type.reload, 0f, 1f);
            shapeRenderer.setColor(frac >= 1f ? 0.25f : 0.6f, frac >= 1f ? 0.7f : 0.5f, 0.2f, 1f);
            shapeRenderer.rect(x + 2, 10, (cardW - 4) * frac, 3);
        }
        shapeRenderer.end();
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < weapons.size; i++) {
            Weapon cw = weapons.get(i);
            float x = cardsX + i * (cardW + 6f);
            if (cw.selected) shapeRenderer.setColor(Color.WHITE);
            else shapeRenderer.setColor(0.3f, 0.32f, 0.35f, 1f);
            shapeRenderer.rect(x, 8, cardW, cardH);
            // AUTO lamp, top-right corner of the card
            if (cw.auto) shapeRenderer.setColor(0.3f, 0.9f, 0.4f, 1f);
            else shapeRenderer.setColor(0.2f, 0.24f, 0.26f, 1f);
            shapeRenderer.rect(x + cardW - 12, 8 + cardH - 8, 8, 4);
            // reloading: a clockwatch face, hands ticking down
            if (cw.cooldown > 1.2f) {
                drawClockIcon(x + cardW - 10, 22, cw.cooldown, cw.type == Weapon.Type.AUTOCANNON_20
                    ? 5f : cw.type.reload);
            }
        }
        // MG-46 status chip beside the cards
        if (state.mothership.countMounts(Mothership.MOUNT_MG46) > 0) {
            float chipX = cardsX + weapons.size * (cardW + 6f);
            if (cupolaReloadT > 0) shapeRenderer.setColor(0.7f, 0.3f, 0.2f, 1f);
            else shapeRenderer.setColor(0.3f, 0.32f, 0.35f, 1f);
            shapeRenderer.rect(chipX, 8, 60, cardH);
            if (cupolaReloadT > 0) drawClockIcon(chipX + 50, 22, cupolaReloadT, 5f);
        }
        shapeRenderer.end();

        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        Fonts.scale(font, 0.9f);
        for (int i = 0; i < weapons.size; i++) {
            Weapon w = weapons.get(i);
            float x = cardsX + i * (cardW + 6f);
            font.setColor(w.selected ? Color.WHITE : w.auto ? new Color(0.4f, 0.8f, 0.5f, 1f) : Color.GRAY);
            String cardLabel = (i + 1) + " " + w.type.label + (w.auto ? " A" : "");
            if (w.type == Weapon.Type.CANNON_155) cardLabel += " T" + state.cannon155Tier();
            font.draw(batch, cardLabel, x + 4, 38);
            drawDrumCounter(w.type, x + 4, 15);
            if (w.cooldown > 1.2f) { // countdown beside the clockwatch
                font.setColor(0.85f, 0.75f, 0.35f, 1f);
                font.draw(batch, String.format("%.1f", w.cooldown), x + cardW - 34, 26);
            }
        }
        if (state.mothership.countMounts(Mothership.MOUNT_MG46) > 0) {
            float chipX = cardsX + weapons.size * (cardW + 6f);
            font.setColor(cupolaReloadT > 0 ? new Color(0.95f, 0.5f, 0.35f, 1f) : Color.GRAY);
            font.draw(batch, "MG-46", chipX + 4, 38);
            if (cupolaReloadT > 0) {
                font.setColor(0.85f, 0.75f, 0.35f, 1f);
                font.draw(batch, String.format("%.1f", cupolaReloadT), chipX + 4, 26);
            } else {
                font.setColor(Color.GRAY);
                font.draw(batch, cupolaGapT > 0 ? "..." : "AUTO", chipX + 4, 26);
            }
        }
        font.setColor(Color.GRAY);
        font.draw(batch, "HULL", 136, HUD_H - 78);
        font.draw(batch, "SHLD", 136, HUD_H - 90);
        Fonts.scale(font, 1.4f);
        if (defeatT >= 0) {
            font.setColor(Color.RED);
            GlyphLayout lost = new GlyphLayout(font, "SHIP LOST");
            font.draw(batch, lost, (HUD_W - lost.width) / 2f, HUD_H / 2f + 40);
        }
        font.setColor(Color.YELLOW);
        font.draw(batch, "Credits: " + state.credits, 10, HUD_H - 10);
        // objective line only when there is something beyond the default to say (#168)
        font.setColor(Color.RED);
        if (objective == Obj.SURVIVE && !objectiveDone) {
            font.draw(batch, "SURVIVE " + (int) Math.ceil(surviveT) + "s", 10, HUD_H - 35);
        } else if (objective == Obj.INTERCEPT && !objectiveDone) {
            font.draw(batch, "INTERCEPT THE RUNNER", 10, HUD_H - 35);
        } else if (objective == Obj.MOTHERSHIP && !objectiveDone) {
            font.draw(batch, "DESTROY THE MOTHERSHIP", 10, HUD_H - 35);
        }
        if (controlled >= 0) { // only flag the unusual state (#168)
            font.setColor(Color.ORANGE);
            font.draw(batch, "ON THE STICK: FIGHTER " + (controlled + 1), 10, HUD_H - 60);
        }
        // squadron tabs (#134): callsign + fraction health; wiped squadrons drop off
        Fonts.scale(font, 0.95f);
        for (int s = 0; s < SQUADRON_COUNT; s++) {
            int alive = squadronAlive(s);
            if (alive == 0) continue;
            com.badlogic.gdx.math.Rectangle tr = squadronTabRect(s);
            String mode = squadrons[s] == null ? "" : squadrons[s].mode == 2 ? " ATK"
                : squadrons[s].mode == 1 ? " MOV" : squadrons[s].mode == 3 ? " DOCK" : " ESC";
            for (Fighter f : fighters) {
                if (f.squadron == s && f.dock == 2) mode = " DOCKED";
            }
            font.setColor(s == selectedSquadron ? Color.WHITE : Color.GRAY);
            font.draw(batch, state.squadronNames[s] + " " + alive + "/" + FIGHTERS_PER_SQUADRON + mode,
                tr.x + 5, tr.y + 29);
            CrewMember lead = state.squadronLeader(s);
            if (lead != null) {
                font.setColor(0.4f, 0.8f, 0.9f, 1f);
                font.draw(batch, "LEAD: " + lead.name, tr.x + 5, tr.y + 13);
            }
        }
        Fonts.scale(font, 1.4f);
        // #147: one quiet line at the bottom, fading in through the long build-up
        if (state.map.getCurrentNode().stormy && stormTimer < STORM_BUILDUP && defeatT < 0) {
            float ramp = 1f - stormTimer / STORM_BUILDUP;
            font.setColor(1f, 0.65f, 0.25f, 0.25f + 0.75f * ramp);
            GlyphLayout sr = new GlyphLayout(font, "WARNING: Solar activity detected");
            font.draw(batch, sr, (HUD_W - sr.width) / 2f, 46);
        }
        // artillery readouts (#157): range and time-of-flight beside the plot cursor
        boolean heavySelected = false;
        for (Weapon aw : weapons) {
            if (aw.selected && aw.type == Weapon.Type.CANNON_155) heavySelected = true;
        }
        Vector2 hm = hudMouse();
        if (heavySelected && defeatT < 0 && inPlot(hm.x, hm.y)) {
            float dist = Vector2.dst(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f,
                lastAim.x, lastAim.y);
            Fonts.scale(font, 0.95f);
            font.setColor(0.62f, 0.64f, 0.66f, 1f);
            font.draw(batch, "DST " + Math.round(dist), hm.x + 18, hm.y + 4);
            font.draw(batch, "ToF " + String.format("%.1fs", dist / Weapon.Type.CANNON_155.speed),
                hm.x + 18, hm.y - 10);
            Fonts.scale(font, 1.4f);
        }
        if (hudToastT > 0) {
            font.setColor(1f, 0.5f, 0.2f, 1f);
            GlyphLayout ht = new GlyphLayout(font, hudToast);
            font.draw(batch, ht, (HUD_W - ht.width) / 2f, HUD_H - 95);
        }
        if (salvageToastT > 0) {
            salvageToastT -= Gdx.graphics.getDeltaTime();
            font.setColor(0.8f, 0.85f, 0.5f, 1f);
            Fonts.scale(font, 1.1f);
            GlyphLayout st = new GlyphLayout(font, salvageToast);
            font.draw(batch, st, (HUD_W - st.width) / 2f, HUD_H - 118);
            Fonts.scale(font, 1.4f);
        }
        if (Dev.MODE) { // bottom-right on the combat desk (#168)
            Fonts.scale(font, 0.95f);
            font.setColor(1f, 0.25f, 0.2f, 1f);
            font.draw(batch, "DEV", HUD_W - 42, 20);
            Fonts.scale(font, 1.4f);
        }
        batch.end();

        if (stormFlash > 0) {
            Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
            shapeRenderer.setProjectionMatrix(hudMatrix);
            shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
            shapeRenderer.setColor(1f, 0.5f, 0.15f, 0.18f * (stormFlash / 0.7f));
            shapeRenderer.rect(0, 0, HUD_W, HUD_H);
            shapeRenderer.end();
        }

        controlsHelp.draw(shapeRenderer, batch, font, hudMatrix);
    }

    /** Returns true when the screen was switched and rendering must stop. */
    private boolean handleInput(float delta) {
        boolean shift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
            || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
        for (int k = 0; k < weapons.size && k < 9; k++) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1 + k)) {
                Weapon wk = weapons.get(k);
                if (shift) {
                    wk.auto = !wk.auto; // SHIFT+key: hand the mount to the gunners or take it back
                    if (wk.auto) wk.selected = false;
                } else {
                    wk.selected = !wk.selected; // multi-select manual fire group (#139)
                    if (wk.selected) wk.auto = false;
                }
            }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.G)) {
            sonarActive = !sonarActive;
            addLog(sonarActive ? "SONAR ACTIVE — WE ARE LOUD" : "SONAR PASSIVE — RUNNING QUIET");
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.V)) {
            deckOpen = !deckOpen;
            if (!deckOpen) deckSelected = null;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            // take the stick: carrier -> fighter 0 -> fighter 1 -> ... -> carrier
            controlled = controlled + 1 >= fighters.size ? -1 : controlled + 1;
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            // squadron tabs double as selection buttons (#134)
            Vector2 hm = hudMouse();
            boolean tabHit = false;
            if (deckOpen && hm.y >= 270) {
                handleDeckClick(hm.x, hm.y);
                tabHit = true;
            }
            for (int s = 0; s < SQUADRON_COUNT; s++) {
                if (squadronAlive(s) > 0 && squadronTabRect(s).contains(hm.x, hm.y)) {
                    selectedSquadron = s;
                    game.sfx.playPing();
                    tabHit = true;
                }
            }
            if (!tabHit && handleDeckConsoleClick(hm.x, hm.y)) {
                tabHit = true;
            }
            if (!tabHit && inCrew(hm.x, hm.y)) {
                // vital-signs monitor click: crew selection/orders only
                handleCrewMonitorClick((hm.x - CREW_X) / CREW_S, (hm.y - CREW_Y) / CREW_S + 270f);
                tabHit = true;
            }
            if (!tabHit && inPlot(hm.x, hm.y)) {
                Vector2 at = plotToWorld(hm.x, hm.y);
                handleCommandClick(at.x, at.y);
            }
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT)) {
            Vector2 hm2 = hudMouse();
            if (selectedSquadron != -1) {
                selectedSquadron = -1; // stand down the selection
            } else if (inPlot(hm2.x, hm2.y)) {
                // carrier helm: waypoint plotted on the chart; SHIFT chains legs (#191)
                Vector2 at = plotToWorld(hm2.x, hm2.y);
                boolean shiftHeld = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                    || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
                if (shiftHeld && carrierWaypoint.x >= 0) {
                    carrierQueue.add(new Vector2(at.x, at.y));
                } else {
                    carrierQueue.clear();
                    carrierWaypoint.set(at.x, at.y);
                    effects.setAutopilotTarget(at.x, at.y);
                }
                game.sfx.playPing();
            }
        }
        handleThrustLever();
        // the plot cursor is the standing aim point for manual gunnery
        {
            Vector2 hmAim = hudMouse();
            if (inPlot(hmAim.x, hmAim.y)) lastAim.set(plotToWorld(hmAim.x, hmAim.y));
        }
        controlsHelp.handleInput();
        return false;
    }

    /** The arena has solid walls: ships bounce off instead of wrapping through. */
    private static void bounceOffWalls(Player ship) {
        if (ship.x < 0) {
            ship.x = 0;
            ship.vx = Math.abs(ship.vx) * WALL_RESTITUTION;
        } else if (ship.x > ARENA_WIDTH - Player.WIDTH) {
            ship.x = ARENA_WIDTH - Player.WIDTH;
            ship.vx = -Math.abs(ship.vx) * WALL_RESTITUTION;
        }
        if (ship.y < 0) {
            ship.y = 0;
            ship.vy = Math.abs(ship.vy) * WALL_RESTITUTION;
        } else if (ship.y > ARENA_HEIGHT - Player.HEIGHT) {
            ship.y = ARENA_HEIGHT - Player.HEIGHT;
            ship.vy = -Math.abs(ship.vy) * WALL_RESTITUTION;
        }
    }

    /** Faint boundary so the wall reads before you hit it. */
    private void drawArenaBounds() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(0.35f, 0.12f, 0.1f, 1f);
        shapeRenderer.rect(2, 2, ARENA_WIDTH - 4, ARENA_HEIGHT - 4);
        shapeRenderer.end();
    }

    /** Point defence (#125): friendly rounds can clip hostile rockets out of the air. */
    private void interceptRockets(float delta) {
        for (int i = projectiles.size - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            if (!p.rocket || p.failMode != 0) continue;
            for (int j = projectiles.size - 1; j >= 0; j--) {
                if (i == j) continue;
                Projectile q = projectiles.get(j);
                if (q.rocket || q.shooter == p.shooter) continue;
                float dx = q.x - p.x;
                float dy = q.y - p.y;
                if (dx * dx + dy * dy > 10f * 10f) continue;
                projectiles.removeIndex(j);
                if (j < i) i--;
                float roll = MathUtils.random();
                if (roll < 0.4f) { // clean detonation
                    impact(p);
                    projectiles.removeIndex(i);
                } else {
                    p.cripple(roll < 0.7f ? 1 : 2);
                    addSparks(p.x, p.y, p.velX() * 0.3f, p.velY() * 0.3f, 5);
                }
                break;
            }
        }
        // failure clocks + smoke trails
        for (int i = projectiles.size - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            if (p.failMode == 0) continue;
            p.failT -= delta;
            p.smokeT -= delta;
            if (p.smokeT <= 0) {
                p.smokeT = 0.05f;
                Spark s = new Spark();
                s.x = p.x;
                s.y = p.y;
                s.vx = MathUtils.random(-12f, 12f);
                s.vy = MathUtils.random(-12f, 12f);
                s.life = p.failMode == 1 ? 0.9f : 0.5f;
                s.maxLife = s.life;
                s.gray = true; // sputtering smoke, not fire
                sparks.add(s);
            }
            if (p.failT <= 0) {
                if (p.failMode == 2) impact(p); // the diverted rocket still goes off
                else addSparks(p.x, p.y, 0, 0, 4); // engine-out just fizzles
                projectiles.removeIndex(i);
            }
        }
    }

    /**
     * Collision-course test for hostile rockets/torpedoes: homing at us counts,
     * and so does any dumb round whose closest point of approach clips the hull.
     */
    private boolean ordnanceThreatensUs(Projectile p) {
        if (p.target == player) return true;
        // relative geometry: does its track pass within the hull envelope soon?
        float rx = (player.x + Player.WIDTH / 2f) - p.x;
        float ry = (player.y + Player.HEIGHT / 2f) - p.y;
        float vx = p.velX() - player.vx;
        float vy = p.velY() - player.vy;
        float v2 = vx * vx + vy * vy;
        if (v2 < 1f) return false;
        float tca = (rx * vx + ry * vy) / v2; // time of closest approach
        if (tca < 0 || tca > 6f) return false; // receding, or too far out to sweat
        float cx = rx - vx * tca;
        float cy = ry - vy * tca;
        float missR = CARRIER_HIT_RADIUS + 30f;
        return cx * cx + cy * cy < missR * missR;
    }

    /** Shell dispersion radius: long shots scatter wider. */
    private static float dispersion155(float dist) {
        return 30f + dist * 0.055f;
    }

    /** Timed 155 air-burst (#157): splash at the fuse point. */
    private void burstShell(Projectile p) {
        addBlast(p.x, p.y, 0f, 240f, 40f);
        addShockwave(p.x, p.y, SPLASH_155);
        addShake(0.25f);
        addSparks(p.x, p.y, 0, 0, 10);
        game.sfx.playThud(0.5f);
        for (int j = enemies.size - 1; j >= 0; j--) {
            Enemy e = enemies.get(j);
            if (Vector2.dst(p.x, p.y, e.centerX(), e.centerY()) < SPLASH_155 + e.radius()) {
                damageEnemyAt(e, p.damage, e.centerX(), e.centerY());
            }
        }
    }

    private void updateProjectiles(float delta) {
        for (int i = projectiles.size - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            if (p.fuseT >= 0) {
                p.fuseT -= delta;
                if (p.fuseT <= 0) {
                    projectiles.removeIndex(i);
                    burstShell(p);
                }
            }
        }
        interceptRockets(delta);
        // firing into a dogfight is a gamble (#143)
        outer:
        for (int i = projectiles.size - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            for (Dogfight d : dogfights) {
                if (d.friends.size == 0 || d.foes.size == 0) continue;
                float dx = p.x - d.x;
                float dy = p.y - d.y;
                float dist2 = dx * dx + dy * dy;
                if (dist2 > DOGFIGHT_RADIUS * DOGFIGHT_RADIUS) continue;
                boolean participant = false;
                for (Fighter f : d.friends) {
                    if (p.shooter == f.body) participant = true;
                }
                for (Enemy e : d.foes) {
                    if (p.shooter == e.body) participant = true;
                }
                if (participant) continue;
                // risk scales with weapon class and how deep into the swirl the round lands
                float depth = 1f - (float) Math.sqrt(dist2) / DOGFIGHT_RADIUS;
                float base = p.rocket || p.damage >= 20f ? 0.45f : 0.2f;
                float ffChance = base * (0.5f + 0.5f * depth);
                projectiles.removeIndex(i);
                impact(p);
                if (p.shooter == player && MathUtils.random() < ffChance) {
                    Fighter hitF = d.friends.random();
                    hitF.hp -= p.damage * 0.7f;
                    updateSquadStrength(hitF);
                    showHudToast("FRIENDLY HIT — CHECK FIRE");
                    if (hitF.strength <= 0) {
                        d.friends.removeValue(hitF, true);
                        loseSquad(hitF);
                    }
                } else {
                    Enemy hitE = d.foes.random();
                    damageEnemyAt(hitE, p.damage, p.x, p.y);
                }
                continue outer;
            }
        }
        for (int i = projectiles.size - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            p.update(delta);
            if (p.rocket && MathUtils.random() < 40f * delta) {
                addSparks(p.x + MathUtils.sinDeg(p.rotation) * 8f,
                    p.y - MathUtils.cosDeg(p.rotation) * 8f, 0, 0, 1);
            }
            if (p.isOutOfBounds(ARENA_WIDTH, ARENA_HEIGHT)) {
                projectiles.removeIndex(i);
                continue;
            }
            // the fighter takes hits from anything it didn't fire itself
            if (defeatT < 0 && p.shooter != player) {
                float pdx = p.x - (player.x + Player.WIDTH / 2f);
                float pdy = p.y - (player.y + Player.HEIGHT / 2f);
                if (pdx * pdx + pdy * pdy < SHIP_HIT_RADIUS * SHIP_HIT_RADIUS) {
                    projectiles.removeIndex(i);
                    damagePlayer(HIT_DAMAGE);
                    continue;
                }
            }
            for (int j = enemies.size - 1; j >= 0; j--) {
                Enemy e = enemies.get(j);
                if (p.shooter == e.body) continue; // no self-hits
                float dx = p.x - e.centerX();
                float dy = p.y - e.centerY();
                if (dx * dx + dy * dy < e.radius() * e.radius()) {
                    projectiles.removeIndex(i);
                    impact(p);
                    if (!p.rocket) damageEnemyAt(e, p.damage, p.x, p.y); // rockets damage via their splash
                    break;
                }
            }
        }
    }

    private void completeObjective(String msg, int bonus) {
        if (objectiveDone) return;
        objectiveDone = true;
        state.map.getCurrentNode().completed = true;
        state.instancesCompleted++;
        state.awardCrewXp(5f);
        if (bonus > 0) state.credits += Math.round(bonus * Difficulty.rewardFactor(state.sector));
        endHeadline = msg;
        showHudToast(msg);
        // every drifting wreck is swept up at battle's end (#163)
        for (float[] wk : wrecks) {
            state.credits += Math.round(wk[6] * Difficulty.rewardFactor(state.sector));
            wrecksCollected++;
        }
        wrecks.clear();
        // battlefield sweep (#124): ammo comes back, parts patch the hull on the spot
        int lightBack = 30 + 15 * wrecksCollected;
        int heavyBack = 1 + wrecksCollected / 2;
        int rocketsBack = wrecksCollected / 2;
        int parts = 1 + wrecksCollected;
        state.ammoLight += lightBack;
        state.ammoHeavy += heavyBack;
        state.ammoRockets += rocketsBack;
        state.hull = Math.min(state.maxHull, state.hull + parts * 4f);
        for (int i = 0; i < parts && !state.damageMarks.isEmpty(); i++) {
            state.damageMarks.remove(state.damageMarks.size() - 1); // patched plates lose their scars
        }
        salvageToast = "SWEEP: +" + lightBack + " LIGHT  +" + heavyBack + " HEAVY"
            + (rocketsBack > 0 ? "  +" + rocketsBack + " RKT" : "")
            + "  +" + parts + " PARTS (hull +" + parts * 4 + ")";
        salvageToastT = 6f;
        addLog(salvageToast);
    }

    private void updateObjective(float delta) {
        if (objective == Obj.SURVIVE && !objectiveDone) {
            surviveT -= delta;
            waveT -= delta;
            if (waveT <= 0 && enemies.size < 4) {
                waveT = 12f;
                int size = MathUtils.random(2, 3);
                Enemy e = new Enemy(MathUtils.randomBoolean() ? 60f : ARENA_WIDTH - 60f,
                    MathUtils.random(100f, ARENA_HEIGHT - 100f), Difficulty.enemyHp(state.sector) * size);
                e.maxHp = e.hp;
                e.strength = size;
                e.arms = rollArms();
                e.body.thrustMult = 0.65f;
                e.body.turnMult = 0.85f;
                enemies.add(e);
            }
            if (surviveT <= 0) {
                completeObjective("SIGNAL SENT — HOSTILES WITHDRAWING", 60);
                for (Enemy e : enemies) {
                    e.ai = 2;
                    e.aiT = 999f; // they run for good
                }
            }
        }
        if (objective == Obj.MOTHERSHIP && !objectiveDone) {
            Enemy carrier = null;
            for (Enemy e : enemies) {
                if (e.mothership) carrier = e;
            }
            if (carrier == null) {
                completeObjective("HOSTILE MOTHERSHIP DESTROYED", 150);
                for (Enemy e : enemies) { // her escorts scatter
                    e.ai = 2;
                    e.aiT = 999f;
                }
            } else {
                waveT -= delta;
                if (waveT <= 0 && enemies.size < 4) {
                    // the next launch round rolls out of her hangar: a full squad
                    waveT = 18f;
                    int size = MathUtils.random(2, 3);
                    Enemy e = new Enemy(carrier.centerX() + MathUtils.random(-60f, -20f),
                        carrier.centerY() + MathUtils.random(-70f, 70f),
                        Difficulty.enemyHp(state.sector) * size);
                    e.maxHp = e.hp;
                    e.strength = size;
                    e.arms = rollArms();
                    e.body.thrustMult = 0.65f;
                    e.body.turnMult = 0.85f;
                    enemies.add(e);
                    showHudToast("LAUNCH DETECTED — NEW WAVE INBOUND");
                }
            }
        }
        if (objective == Obj.INTERCEPT && !objectiveDone) {
            for (int i = enemies.size - 1; i >= 0; i--) {
                Enemy e = enemies.get(i);
                if (e.runner && e.body.x > ARENA_WIDTH - 80f) {
                    enemies.removeIndex(i);
                    completeObjective("TARGET ESCAPED — NO BOUNTY", 0);
                }
            }
        }
    }

    /** Dead ships leave wreck returns; they drift until the end-of-battle sweep (#163). */
    private void updateWrecks() {
        for (float[] wk : wrecks) {
            wk[0] += wk[2] * Gdx.graphics.getDeltaTime();
            wk[1] += wk[3] * Gdx.graphics.getDeltaTime();
            wk[4] += wk[5] * Gdx.graphics.getDeltaTime();
        }
    }

    /** Approach to range, orbit while firing, break off when mauled; re-engage after recovery. */
    private void updateEnemyAi(Enemy e, float delta) {
        e.gun.update(delta);
        if (defeatT >= 0) return; // nothing left to fight
        float cx = e.centerX();
        float cy = e.centerY();
        // hunt the nearest friendly: a fighter close by beats the distant carrier
        float px = player.x + Player.WIDTH / 2f;
        float py = player.y + Player.HEIGHT / 2f;
        float best = Vector2.dst(cx, cy, px, py);
        for (Fighter f : fighters) {
            if (f.dock != 0) continue; // can't chase what's in the hangar
            float d = Vector2.dst(cx, cy, f.centerX(), f.centerY());
            if (d < best) {
                best = d;
                px = f.centerX();
                py = f.centerY();
            }
        }
        float dx = px - cx;
        float dy = py - cy;
        float dist = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));

        if (e.mothership) {
            float pxm = player.x + Player.WIDTH / 2f;
            float pym = player.y + Player.HEIGHT / 2f;
            float dxm = pxm - e.centerX();
            float dym = pym - e.centerY();
            float distM = Math.max(1f, (float) Math.sqrt(dxm * dxm + dym * dym));
            float desiredM = MathUtils.atan2(-dxm, dym) * MathUtils.radiansToDegrees;
            float errM = ((desiredM - e.body.rotation) % 360f + 540f) % 360f - 180f;
            if (errM > 6f) e.body.rotateLeft(delta * 0.4f);
            else if (errM < -6f) e.body.rotateRight(delta * 0.4f);
            int wantM = distM > 700f ? 4 : 0; // closes to stand-off range, then holds
            if (e.body.throttle < wantM) e.body.throttleUp();
            else if (e.body.throttle > wantM) e.body.throttleDown();
            e.body.updateThrust(delta, true);
            // her forward guns speak when the carrier drifts into range
            e.gun.update(delta);
            if (e.gun.ready() && distM < 620f && Math.abs(errM) < 12f) {
                e.gun.fire();
                e.gun.cooldown = 1.4f;
                projectiles.add(new Projectile(e.centerX() - MathUtils.sinDeg(desiredM) * 60f,
                    e.centerY() + MathUtils.cosDeg(desiredM) * 60f, desiredM + MathUtils.random(-3f, 3f),
                    e.body, 420f, 12f, 0f, 0f, false));
                game.sfx.playCannon(1);
            }
            return;
        }
        if (e.runner) {
            // intercept target: burn for the far edge, no fighting back
            float desiredR = 90f; // heading straight +x
            float errR = ((desiredR - e.body.rotation) % 360f + 540f) % 360f - 180f;
            if (errR > 4f) e.body.rotateLeft(delta);
            else if (errR < -4f) e.body.rotateRight(delta);
            if (e.body.throttle < 10) e.body.throttleUp();
            e.body.updateThrust(delta, true);
            return;
        }
        e.aiT -= delta;
        boolean mauled = !e.boss
            && (e.hp < e.maxHp * 0.3f || e.leftWingHp <= 0 || e.rightWingHp <= 0);
        if (e.ai != 2 && mauled && MathUtils.random() < 0.5f * delta) {
            e.ai = 2;
            e.aiT = MathUtils.random(3f, 5f);
        } else if (e.ai == 2 && e.aiT <= 0) {
            e.ai = 0;
        } else if (e.ai == 0 && e.arms == SquadArms.TORPEDO && dist < 950f) {
            e.ai = 1; // torpedo boats hold the stand-off ring
            e.aiT = MathUtils.random(4f, 7f);
            e.orbitDir = MathUtils.randomSign();
        } else if (e.ai == 0 && dist < 420f) {
            e.ai = 1;
            e.aiT = MathUtils.random(3f, 6f);
            e.orbitDir = MathUtils.randomSign();
        } else if (e.ai == 1 && (e.aiT <= 0 || dist > 620f)) {
            e.ai = 0;
        }

        // desired heading per state
        float hx;
        float hy;
        if (e.ai == 2) { // run for space
            hx = -dx / dist;
            hy = -dy / dist;
        } else if (e.ai == 1) { // strafe around the fighter, drifting slightly inward
            hx = -dy / dist * e.orbitDir + dx / dist * 0.25f;
            hy = dx / dist * e.orbitDir + dy / dist * 0.25f;
        } else {
            hx = dx / dist;
            hy = dy / dist;
        }
        float desired = MathUtils.atan2(-hx, hy) * MathUtils.radiansToDegrees;
        float err = ((desired - e.body.rotation) % 360f + 540f) % 360f - 180f;
        if (err > 4f) e.body.rotateLeft(delta);
        else if (err < -4f) e.body.rotateRight(delta);
        int wantThrottle = e.ai == 2 ? 10 : e.ai == 1 ? 5 : dist > 700f ? 9 : 7;
        if (e.body.throttle < wantThrottle) e.body.throttleUp();
        else if (e.body.throttle > wantThrottle) e.body.throttleDown();
        e.body.updateThrust(delta, true);

        // gunnery (#96/#152): the squad's rolled loadout decides cadence, reach and ordnance
        float aimErr = (((MathUtils.atan2(-dx, dy) * MathUtils.radiansToDegrees)
            - e.body.rotation) % 360f + 540f) % 360f - 180f;
        e.armsCd -= delta;
        SquadArms arms = e.arms;
        boolean inRange = dist < arms.range && (arms != SquadArms.TORPEDO || dist > 500f);
        if (e.armsCd <= 0 && inRange && Math.abs(aimErr) < (arms.rocket() ? 14f : 9f)
                && MathUtils.random() < 0.85f) {
            if (!e.recognized) {
                e.recognized = true; // muzzle flash gives it away
                addLog("CONTACT CLASSIFIED: " + arms.name() + " SQUAD (FIRING)");
            }
            e.armsCd = arms.reload * MathUtils.random(1.1f, 1.6f);
            float fx = -MathUtils.sinDeg(e.body.rotation);
            float fy = MathUtils.cosDeg(e.body.rotation);
            float exJit = sonarActive ? 2.4f : 4.5f; // pinging paints us for them (#186)
            if (arms.rocket()) {
                Projectile p = new Projectile(cx + fx * 22f, cy + fy * 22f,
                    e.body.rotation + MathUtils.random(-exJit, exJit), e.body,
                    arms.speed, arms.damage,
                    arms == SquadArms.TORPEDO ? 60f : 200f,
                    arms == SquadArms.HOMING ? 120f : 0f, true);
                if (arms == SquadArms.HOMING) p.target = player;
                if (arms == SquadArms.TORPEDO) p.life = 14f; // long, slow run at the carrier
                projectiles.add(p);
                game.sfx.playRocket();
            } else {
                int volley = arms == SquadArms.MG ? 1 : e.strength; // MGs stream, guns volley
                for (int k = 0; k < volley; k++) {
                    float jitter = MathUtils.random(-4f, 4f);
                    projectiles.add(new Projectile(
                        cx + formX(k) + fx * 20f, cy + formY(k) + fy * 20f,
                        e.body.rotation + jitter, e.body,
                        arms.speed, arms.damage, 0f, 0f, false));
                }
                if (MathUtils.randomBoolean(arms == SquadArms.MG ? 0.25f : 1f)) {
                    game.sfx.playCannon(arms == SquadArms.CANNON ? 1 : 0);
                }
            }
        }
    }

    /** Positional damage: side hits chew the carrier's armor sections before the hull (#99/#117). */
    private void damagePlayerAt(float dmg, float hx, float hy) {
        float dx = hx - (player.x + Player.WIDTH / 2f);
        float dy = hy - (player.y + Player.HEIGHT / 2f);
        float cos = MathUtils.cosDeg(-player.rotation);
        float sin = MathUtils.sinDeg(-player.rotation);
        float lx = dx * cos - dy * sin;
        if (state.shield <= 0) {
            if (lx < -9f && state.leftWingHp > 0) {
                state.leftWingHp -= dmg * 0.7f;
                if (state.leftWingHp <= 0) shearPlayerWing(true);
            } else if (lx > 9f && state.rightWingHp > 0) {
                state.rightWingHp -= dmg * 0.7f;
                if (state.rightWingHp <= 0) shearPlayerWing(false);
            }
        }
        damagePlayer(dmg);
    }

    private void shearPlayerWing(boolean left) {
        WingDebris wd = new WingDebris();
        wd.x = player.x + Player.WIDTH / 2f;
        wd.y = player.y + Player.HEIGHT / 2f;
        wd.vx = player.vx + MathUtils.random(-30f, 30f);
        wd.vy = player.vy + MathUtils.random(-30f, 30f);
        wd.rotation = player.rotation;
        wd.spin = MathUtils.random(-160f, 160f);
        wd.left = left;
        wingDebris.add(wd);
        addShockwave(wd.x, wd.y, 40f);
        addShake(0.4f);
        showHudToast(left ? "PORT ARMOR SECTION LOST" : "STARBOARD ARMOR SECTION LOST");
        game.sfx.playThud(0.5f);
    }

    private void showHudToast(String text) {
        hudToast = text;
        hudToastT = 2.5f;
        addLog(text);
    }

    /** Signal log (#184): a short rolling history of battle events. */
    private void addLog(String line) {
        signalLog.add(line);
        signalLogAge.add(0f);
        if (signalLog.size > 6) {
            signalLog.removeIndex(0);
            signalLogAge.removeIndex(0);
        }
    }

    /** Shields soak damage first (with spill-over); 0 hull destroys the fighter and ends the run. */
    private void damagePlayer(float dmg) {
        shieldSince = 0f;
        if (state.shield > 0) {
            float soaked = Math.min(state.shield, dmg);
            state.shield -= soaked;
            dmg -= soaked;
            shieldFlash = 0.4f;
        }
        if (dmg > 0) {
            state.hull -= dmg;
            state.addDamageMark(); // the hit stays visible until repaired (#123)
            addShake(0.3f);
            game.sfx.playExplosion(0.3f + dmg * 0.02f); // unshielded hits land loud
            if (MathUtils.random() < 0.1f) {
                if (MathUtils.randomBoolean()) {
                    fireCritT = 6f;
                    showHudToast("FIRE ABOARD THE FIGHTER");
                } else {
                    helmCritT = 8f;
                    showHudToast("HELM DAMAGED");
                }
            }
            // the hit carries through to the deck: a random room starts leaking (#12)
            int room = MathUtils.random(7);
            state.roomIntegrity[room] = Math.max(0f, state.roomIntegrity[room] - 0.25f);
            if (MathUtils.random() < 0.35f) state.pendingFireRoom = room; // and sometimes burning (#110)
            game.sfx.playThud(0.4f);
            if (state.hull <= 0) {
                state.hull = 0;
                spawnDeathEffect(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f,
                    player.vx, player.vy);
                defeatT = 0f;
            }
        } else {
            game.sfx.playThud(0.2f);
        }
    }

    /**
     * Dogfights (#141): contact latches both sides into a swirling engagement that
     * resolves over timed rounds; hidden rolls weigh pilot lead (#142), craft state
     * and remaining weaponry. Leaders are the last to go down.
     */
    private void updateDogfights(float delta) {
        // latch on contact
        for (Fighter f : fighters) {
            if (latched(f) || f.dock != 0) continue;
            for (Enemy e : enemies) {
                if (e.boss || e.mothership || latched(e)) continue;
                if (Vector2.dst(f.centerX(), f.centerY(), e.centerX(), e.centerY()) > 55f) continue;
                Dogfight d = new Dogfight();
                d.x = (f.centerX() + e.centerX()) / 2f;
                d.y = (f.centerY() + e.centerY()) / 2f;
                // everyone close gets pulled into the furball
                for (Fighter o : fighters) {
                    if (!latched(o) && Vector2.dst(d.x, d.y, o.centerX(), o.centerY()) < 130f) {
                        d.friends.add(o);
                        if (controlled == fighters.indexOf(o, true)) controlled = -1; // stick torn away
                    }
                }
                for (Enemy o : enemies) {
                    if (!o.boss && !o.mothership && !latched(o)
                            && Vector2.dst(d.x, d.y, o.centerX(), o.centerY()) < 130f) {
                        d.foes.add(o);
                    }
                }
                dogfights.add(d);
                game.sfx.playCannon(0);
                break;
            }
        }
        for (int di = dogfights.size - 1; di >= 0; di--) {
            Dogfight d = dogfights.get(di);
            d.anim += delta;
            // close-quarters swirl: participants orbit the engagement point
            for (int i = 0; i < d.friends.size; i++) {
                Fighter f = d.friends.get(i);
                float ang = d.anim * 190f + i * (360f / Math.max(1, d.friends.size));
                float r = 22f + (i % 2) * 12f;
                f.body.x = d.x + MathUtils.cosDeg(ang) * r - Player.WIDTH / 2f;
                f.body.y = d.y + MathUtils.sinDeg(ang) * r - Player.HEIGHT / 2f;
                f.body.rotation = ang + 180f; // tangent, nose into the turn
            }
            for (int i = 0; i < d.foes.size; i++) {
                Enemy e = d.foes.get(i);
                float ang = -d.anim * 170f + i * (360f / Math.max(1, d.foes.size)) + 45f;
                float r = 30f + (i % 2) * 10f;
                e.body.x = d.x + MathUtils.cosDeg(ang) * r - Player.WIDTH / 2f;
                e.body.y = d.y + MathUtils.sinDeg(ang) * r - Player.HEIGHT / 2f;
                e.body.rotation = ang - 180f;
            }
            // timed rounds
            d.roundT -= delta;
            if (d.roundT <= 0) {
                d.roundT = ROUND_TIME;
                d.rounds++;
                resolveDogfightRound(d);
            }
            // disengage: a side wiped, or a rout after a long scrap
            if (d.foes.size == 0 || d.friends.size == 0 || d.rounds >= 6) {
                if (d.rounds >= 6) {
                    for (Enemy e : d.foes) { // the raiders break off for good
                        e.ai = 2;
                        e.aiT = 999f;
                    }
                }
                dogfights.removeIndex(di);
            }
        }
    }

    /** One hidden round roll: lead pilot skill, squad condition and weaponry decide (#151). */
    private void resolveDogfightRound(Dogfight d) {
        float fp = 0f;
        for (Fighter f : d.friends) {
            fp += f.strength * (1f + f.rockets * 0.1f);
        }
        // the best involved lead pilot sharpens the whole element
        float leadBonus = 0f;
        for (Fighter f : d.friends) {
            CrewMember lead = state.squadronLeader(f.squadron);
            if (lead != null) {
                leadBonus = Math.max(leadBonus,
                    0.6f * lead.bonusFor(Skill.COMBAT) + 0.4f * lead.level);
            }
        }
        fp += leadBonus * fp * 0.25f;
        float ep = 0f;
        for (Enemy e : d.foes) {
            ep += e.strength * (1f + 0.3f * (Difficulty.factor(state.sector) - 1f));
        }
        if (MathUtils.random() < fp / (fp + ep)) {
            // a raider craft goes down: one strength point off a random hostile squad
            Enemy hit = d.foes.random();
            hit.hp -= hit.maxHp / Math.max(1, FIGHTERS_PER_SQUADRON);
            updateEnemyStrength(hit);
            if (hit.hp <= 0) {
                d.foes.removeValue(hit, true);
                int idx = enemies.indexOf(hit, true);
                if (idx != -1) killEnemy(idx);
            }
        } else {
            // we lose a craft — the leader flies the last hull of the squad (#142)
            Fighter loss = d.friends.random();
            loss.hp -= FIGHTER_HP;
            updateSquadStrength(loss);
            if (loss.strength <= 0) {
                d.friends.removeValue(loss, true);
                int idx = fighters.indexOf(loss, true);
                if (idx != -1) {
                    if (controlled == idx) controlled = -1;
                    else if (controlled > idx) controlled--;
                    fighters.removeIndex(idx);
                }
                // leadership persisted to the final resolution — now it goes down too
                CrewMember lead = state.squadronLeader(loss.squadron);
                if (lead != null) {
                    lead.hp = 0f;
                    showHudToast(lead.name + " LOST WITH " + state.squadronNames[loss.squadron]);
                }
            }
        }
    }

    /** Crew-only clicks for the little vital-signs screen: select figures, order them, toggle doors. */
    private void handleCrewMonitorClick(float x, float y) {
        CrewMember c = deckView.crewAt(x, y);
        if (c != null && !c.hostile) {
            deckSelected = c;
            return;
        }
        int d = deckView.doorAt(x, y);
        if (d != -1) {
            state.doorHeldOpen[d] = !state.doorHeldOpen[d];
            return;
        }
        if (deckSelected != null) {
            deckView.orderAtScreen(deckSelected, x, y);
            deckSelected = null;
        }
    }

    /** Clicks on the mid-battle deck monitor: power, doors, crew selection and orders (#121/#122). */
    private void handleDeckClick(float x, float y) {
        int pwr = deckView.powerButtonAt(x, y);
        if (pwr != -1) {
            deckView.pressPowerButton(pwr);
            return;
        }
        int door = deckView.doorButtonAt(x, y);
        if (door != -1) {
            deckView.pressDoorButton(door);
            return;
        }
        CrewMember c = deckView.crewAt(x, y);
        if (c != null && !c.hostile) {
            deckSelected = c;
            return;
        }
        int d = deckView.doorAt(x, y);
        if (d != -1) {
            state.doorHeldOpen[d] = !state.doorHeldOpen[d];
            return;
        }
        if (deckSelected != null) {
            deckView.orderAtScreen(deckSelected, x, y);
            deckSelected = null;
        }
    }

    /** LMB: select a squadron, then order it — hostiles = attack, the carrier = escort, space = move. */
    private void handleCommandClick(float x, float y) {
        // clicking a friendly fighter selects its squadron
        for (Fighter f : fighters) {
            if (Vector2.dst(x, y, f.centerX(), f.centerY()) < 26f) {
                selectedSquadron = f.squadron;
                game.sfx.playPing();
                return;
            }
        }
        if (selectedSquadron == -1) return;
        Squadron sq = squadrons[selectedSquadron];
        for (Enemy e : enemies) {
            if (Vector2.dst(x, y, e.centerX(), e.centerY()) < e.radius() + 14f) {
                for (Fighter f : fighters) {
                    if (f.squadron == selectedSquadron && f.dock == 2) launchSquad(f);
                }
                sq.mode = 2;
                sq.target = e;
                game.sfx.playPing();
                return;
            }
        }
        if (Vector2.dst(x, y, player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f)
                < CARRIER_HIT_RADIUS + 16f) {
            sq.mode = 3; // stow order (#154): fly home and embark
            game.sfx.playPing();
            return;
        }
        // an order into open space launches an embarked squad first (#154)
        for (Fighter f : fighters) {
            if (f.squadron == selectedSquadron && f.dock == 2) {
                launchSquad(f);
            }
        }
        boolean shift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
            || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
        if (shift && sq.mode == 1) { // chain the leg onto the plan (#191)
            sq.queue.add(new Vector2(x, y));
            game.sfx.playPing();
            return;
        }
        sq.queue.clear();
        sq.mode = 1;
        // remember the direction of travel so the squadron holds it after arriving
        float scx = 0f;
        float scy = 0f;
        int n = 0;
        for (Fighter f : fighters) {
            if (f.squadron == selectedSquadron) {
                scx += f.centerX();
                scy += f.centerY();
                n++;
            }
        }
        if (n > 0) sq.orderDir = MathUtils.atan2(-(x - scx / n), y - scy / n) * MathUtils.radiansToDegrees;
        sq.ox = x;
        sq.oy = y;
        game.sfx.playPing();
    }

    /** Squadron AI: follow orders, pick fights nearby, heal alongside the carrier. */
    private void updateFighters(float delta) {
        for (int i = fighters.size - 1; i >= 0; i--) {
            Fighter f = fighters.get(i);
            f.gun.update(delta);
            // embark cycle (#154)
            if (f.dock == 1) { // stowing: shrink into the hangar
                f.dockAnimT -= delta;
                if (f.dockAnimT <= 0) f.dock = 2;
                continue;
            }
            if (f.dock == 2) { // safe aboard: fast repair and rearm, rides with the carrier
                f.body.x = player.x;
                f.body.y = player.y;
                f.hp = Math.min(f.strength * FIGHTER_HP, f.hp + 8f * delta);
                f.rockets = 4;
                continue;
            }
            if (f.dock == 3) { // launching: roll out of the hangar notch
                f.dockAnimT -= delta;
                if (f.dockAnimT <= 0) f.dock = 0;
                continue;
            }
            if (latched(f)) continue; // the dogfight owns this craft (#141)
            if (i != controlled) flyFighter(f, delta);
            f.body.updatePosition(delta);
            bounceOffWalls(f.body);
            // stow order: arriving at the carrier tucks the squad in
            Squadron sq = squadrons[f.squadron];
            if (sq.mode == 3 && Vector2.dst(f.centerX(), f.centerY(),
                    player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f) < CARRIER_HIT_RADIUS) {
                f.dock = 1;
                f.dockAnimT = 0.8f;
                game.sfx.playClamp();
            }
        }
    }

    private void flyFighter(Fighter f, float delta) {
        Squadron sq = squadrons[f.squadron];
        if (sq.target != null && !enemies.contains(sq.target, true)) {
            sq.target = null;
            if (sq.mode == 2) sq.mode = 0; // target destroyed: fall back to the carrier
        }
        float cx = f.centerX();
        float cy = f.centerY();
        // pick an engagement: ordered target first, otherwise anything close
        Enemy fight = sq.mode == 2 ? sq.target : null;
        if (fight == null) {
            float best = 350f;
            for (Enemy e : enemies) {
                float d = Vector2.dst(cx, cy, e.centerX(), e.centerY());
                if (d < best) {
                    best = d;
                    fight = e;
                }
            }
        }
        f.engaging = fight;
        float gx;
        float gy;
        int wantThrottle;
        if (fight != null) {
            float dx = fight.centerX() - cx;
            float dy = fight.centerY() - cy;
            float dist = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
            if (dist > 260f) {
                gx = dx / dist;
                gy = dy / dist;
                wantThrottle = 9;
            } else { // orbit and shoot
                gx = -dy / dist + dx / dist * 0.2f;
                gy = dx / dist + dy / dist * 0.2f;
                wantThrottle = 6;
            }
            // rocket pod (#126): saved for big or standoff targets
            f.rocketCd -= delta;
            float aimErr = (((MathUtils.atan2(-dx, dy) * MathUtils.radiansToDegrees)
                - f.body.rotation) % 360f + 540f) % 360f - 180f;
            if (f.rockets > 0 && f.rocketCd <= 0 && Math.abs(aimErr) < 5f
                    && dist > 280f && dist < 620f && (fight.boss || dist > 400f)) {
                f.rockets--;
                f.rocketCd = 2.5f;
                float fxv = -MathUtils.sinDeg(f.body.rotation);
                float fyv = MathUtils.cosDeg(f.body.rotation);
                projectiles.add(new Projectile(cx + fxv * 22f, cy + fyv * 22f,
                    f.body.rotation, f.body, Weapon.Type.ROCKET.speed,
                    Weapon.Type.ROCKET.damage, 160f, 0f, true));
                game.sfx.playRocket();
            }
            // gunnery
            if (f.gun.ready() && dist < 480f && Math.abs(aimErr) < 8f) {
                f.gun.fire();
                f.gun.cooldown = f.gun.type.reload * 1.6f; // wingmen shoot calmer than you do
                float fxv = -MathUtils.sinDeg(f.body.rotation);
                float fyv = MathUtils.cosDeg(f.body.rotation);
                for (int slot = 0; slot < f.strength; slot++) { // a volley, one per craft
                    float ang = f.body.rotation;
                    float ox = formX(slot) * MathUtils.cosDeg(ang) - formY(slot) * MathUtils.sinDeg(ang);
                    float oy = formX(slot) * MathUtils.sinDeg(ang) + formY(slot) * MathUtils.cosDeg(ang);
                    projectiles.add(new Projectile(cx + ox + fxv * 16f, cy + oy + fyv * 16f,
                        f.body.rotation + MathUtils.random(-3f, 3f), f.body,
                        f.gun.type.speed, f.gun.type.damage, 0f, 0f, false));
                }
            }
        } else {
            float tx = sq.mode == 1 ? sq.ox : player.x + Player.WIDTH / 2f;
            float ty = sq.mode == 1 ? sq.oy : player.y + Player.HEIGHT / 2f;
            if (sq.mode == 3) { // heading in to embark: aim for the hangar itself
                tx = player.x + Player.WIDTH / 2f;
                ty = player.y + Player.HEIGHT / 2f;
            }
            float dx = tx - cx;
            float dy = ty - cy;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < 24f) {
                if (sq.mode == 1 && sq.queue.size > 0) { // next leg of the chain (#191)
                    Vector2 next = sq.queue.removeIndex(0);
                    sq.orderDir = MathUtils.atan2(-(next.x - sq.ox), next.y - sq.oy)
                        * MathUtils.radiansToDegrees;
                    sq.ox = next.x;
                    sq.oy = next.y;
                }
                wantThrottle = 0;
                // hold the move-order heading instead of drifting around (#132)
                gx = -MathUtils.sinDeg(sq.mode == 1 ? sq.orderDir : f.body.rotation);
                gy = MathUtils.cosDeg(sq.mode == 1 ? sq.orderDir : f.body.rotation);
                if (sq.mode == 0) {
                    f.hp = Math.min(f.strength * FIGHTER_HP, f.hp + 2.5f * delta); // patched up alongside
                    f.rockets = 4; // and rearm the pods
                }
            } else {
                gx = dx / dist;
                gy = dy / dist;
                wantThrottle = dist > 400f ? 9 : 6;
            }
        }
        float desired = MathUtils.atan2(-gx, gy) * MathUtils.radiansToDegrees;
        float err = ((desired - f.body.rotation) % 360f + 540f) % 360f - 180f;
        if (err > 4f) f.body.rotateLeft(delta);
        else if (err < -4f) f.body.rotateRight(delta);
        if (f.body.throttle < wantThrottle) f.body.throttleUp();
        else if (f.body.throttle > wantThrottle) f.body.throttleDown();
        f.body.updateThrust(delta, true);
    }

    private Player controlledBody() {
        return controlled < 0 || controlled >= fighters.size ? player : fighters.get(controlled).body;
    }

    /** Damage routed by hit position: outer hits shear wings off before the core takes it. */
    private void damageEnemyAt(Enemy e, float dmg, float hx, float hy) {
        // hit position in ship-local coords
        float dx = hx - e.centerX();
        float dy = hy - e.centerY();
        float cos = MathUtils.cosDeg(-e.body.rotation);
        float sin = MathUtils.sinDeg(-e.body.rotation);
        float lx = dx * cos - dy * sin;
        if (lx < -9f && e.leftWingHp > 0) {
            e.leftWingHp -= dmg;
            if (e.leftWingHp <= 0) shearWing(e, true);
            else game.sfx.playThud(0.2f);
            return;
        }
        if (lx > 9f && e.rightWingHp > 0) {
            e.rightWingHp -= dmg;
            if (e.rightWingHp <= 0) shearWing(e, false);
            else game.sfx.playThud(0.2f);
            return;
        }
        damageEnemy(e, dmg);
    }

    /** The wing tears free as tumbling debris; the ship flies on, crippled. */
    private void shearWing(Enemy e, boolean left) {
        WingDebris wd = new WingDebris();
        wd.x = e.centerX();
        wd.y = e.centerY();
        wd.vx = e.body.vx + MathUtils.random(-30f, 30f);
        wd.vy = e.body.vy + MathUtils.random(-30f, 30f);
        wd.rotation = e.body.rotation;
        wd.spin = MathUtils.random(-160f, 160f);
        wd.left = left;
        wingDebris.add(wd);
        e.body.thrustMult *= 0.55f;
        e.body.turnMult *= 0.55f;
        addSparks(wd.x, wd.y, e.body.vx, e.body.vy, 10);
        addShockwave(wd.x, wd.y, 40f);
        toast(e, left ? "LEFT WING SHEARED" : "WING SHEARED");
        game.sfx.playThud(0.4f);
    }

    private void damageEnemy(Enemy e, float dmg) {
        e.hp -= dmg;
        if (e.hp <= 0) {
            killEnemy(enemies.indexOf(e, true));
            return;
        }
        game.sfx.playThud(0.2f);
        // critical hits: fires and knocked-out subsystems, announced by a tooltip
        if (MathUtils.random() < 0.14f) {
            int roll = MathUtils.random(2);
            if (roll == 0 && !e.onFire) {
                e.onFire = true;
                toast(e, "FIRE");
            } else if (roll == 1 && !e.engineOut) {
                e.engineOut = true;
                e.body.thrustMult = 0f;
                toast(e, "ENGINE OUT");
            } else if (!e.helmDamaged) {
                e.helmDamaged = true;
                e.body.turnMult = 0.3f;
                toast(e, "HELM DAMAGED");
            }
        }
    }

    private void toast(Enemy e, String text) {
        CritToast ct = new CritToast();
        ct.e = e;
        ct.text = text;
        critToasts.add(ct);
    }

    /** Impact feedback; rockets blast and splash everything nearby. */
    private void impact(Projectile p) {
        if (p.rocket) {
            addBlast(p.x, p.y, 0f, 220f, 42f);
            addShockwave(p.x, p.y, 60f);
            addShake(0.3f);
            addSparks(p.x, p.y, 0, 0, 14);
            game.sfx.playThud(0.45f);
            for (int j = enemies.size - 1; j >= 0; j--) {
                if (j >= enemies.size) continue;
                Enemy e = enemies.get(j);
                float d = Vector2.dst(p.x, p.y, e.centerX(), e.centerY());
                if (d < 70f) damageEnemy(e, p.damage * (1f - d / 90f));
            }
            float pd = Vector2.dst(p.x, p.y, player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f);
            if (defeatT < 0 && pd < 70f && p.shooter != player) damagePlayer(p.damage * (1f - pd / 90f));
        } else {
            addSparks(p.x, p.y, 0, 0, 2 + (int) (p.damage * 0.3f));
        }
    }

    /**
     * MG-46 cupolas (#119): automatic close-defence turrets. Each mounted cupola
     * streams 1200 rpm at the nearest hostile in range, one light round per shot.
     */
    /** Recognition, decoys and jamming (#167/#190/#192). */
    private void updateSensors(float delta) {
        float q = sensorQuality();
        // visual confirmation: a friendly gets close enough to classify the contact
        for (Enemy e : enemies) {
            if (e.recognized) continue;
            boolean seen = Vector2.dst(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f,
                e.centerX(), e.centerY()) < 620f * q;
            for (Fighter f : fighters) {
                if (f.dock == 0 && Vector2.dst(f.centerX(), f.centerY(),
                        e.centerX(), e.centerY()) < 480f * q) {
                    seen = true;
                }
            }
            if (seen) {
                e.recognized = true;
                addLog("CONTACT CLASSIFIED: " + (e.mothership ? "HOSTILE CAPITAL"
                    : e.boss ? "FLAGSHIP" : e.arms.name() + " SQUAD"));
            }
        }
        // decoys dissolve on visual
        for (int i = decoys.size - 1; i >= 0; i--) {
            float[] d = decoys.get(i);
            d[0] += d[2] * delta;
            d[1] += d[3] * delta;
            d[4] -= delta;
            boolean burned = d[4] <= 0
                || Vector2.dst(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f,
                    d[0], d[1]) < 350f;
            for (Fighter f : fighters) {
                if (f.dock == 0 && Vector2.dst(f.centerX(), f.centerY(), d[0], d[1]) < 350f) {
                    burned = true;
                }
            }
            if (burned) {
                decoys.removeIndex(i);
                addLog("CONTACT FADED — DECOY");
            }
        }
        // jamming bursts in deep sectors (#192)
        if (state.sector >= 3 && !objectiveDone) {
            if (jamT > 0) {
                jamT -= delta;
            } else {
                jamNextT -= delta;
                if (jamNextT <= 0) {
                    jamT = 3f / Math.max(0.4f, q);
                    jamNextT = MathUtils.random(18f, 30f);
                    addLog("JAMMING BURST DETECTED");
                }
            }
        }
    }

    /** Burst size (#172): green gunners hold the trigger, veterans fire tight salvos. */
    private int mgBurstSize() {
        int sloppy = Math.max(0, 2 - state.roomStats[4]); // unmanned/low-skill gunnery wastes belt
        return MathUtils.random(5, 8) + sloppy * 3;
    }

    /** Round jitter (#172): tighter dispersion under a skilled gunnery hand (#171 cone). */
    private float lightJitter() {
        return Math.max(1.2f, 2.4f - 0.35f * state.roomStats[4]);
    }

    private void updateCupolas(float delta) {
        int cupolas = state.mothership.countMounts(Mothership.MOUNT_MG46);
        if (cupolas == 0 || defeatT >= 0) return;
        if (cupolaReloadT > 0) { // belt change
            cupolaReloadT -= delta;
            return;
        }
        if (cupolaGapT > 0) { // breath between bursts
            cupolaGapT -= delta;
            return;
        }
        if (enemies.isEmpty()) return;
        cupolaCd -= delta;
        if (cupolaCd > 0) return;
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        Enemy target = null;
        float best = 380f * 1.75f; // cupola screen, halved to a close-in net
        for (Enemy e : enemies) {
            float d = Vector2.dst(cx, cy, e.centerX(), e.centerY());
            if (d >= best) continue;
            // the superstructure masks the cupolas to a 170-degree forward arc (#171)
            float bearing = MathUtils.atan2(-(e.centerX() - cx), e.centerY() - cy)
                * MathUtils.radiansToDegrees;
            float off = ((bearing - player.rotation) % 360f + 540f) % 360f - 180f;
            if (Math.abs(off) > 85f) continue;
            best = d;
            target = e;
        }
        if (target == null || !state.spendAmmo(Weapon.Type.LIGHT_CANNON, 1)) return;
        if (cupolaBurstLeft <= 0) cupolaBurstLeft = mgBurstSize(); // new burst
        cupolaCd = 60f / (1200f * cupolas); // combined rate of fire across mounted cupolas
        cupolaBurstLeft--;
        float aim = MathUtils.atan2(-(target.centerX() - cx), target.centerY() - cy)
            * MathUtils.radiansToDegrees + MathUtils.random(-lightJitter(), lightJitter());
        projectiles.add(new Projectile(cx - MathUtils.sinDeg(aim) * 22f,
            cy + MathUtils.cosDeg(aim) * 22f, aim, player, 560f, 1.5f, 0f, 0f, false));
        if (MathUtils.randomBoolean(0.2f)) game.sfx.playCannon(0);
        if (cupolaBurstLeft == 0) {
            cupolaBursts++;
            if (cupolaBursts >= 4) { // the belt runs dry
                cupolaBursts = 0;
                cupolaReloadT = 5f;
            } else {
                cupolaGapT = 1f;
            }
        }
    }

    /** Per-frame weapon step: cooldowns, lock-on, and firing the active weapon while SPACE is held. */
    private void fireWeapons(float delta) {
        for (Weapon w : weapons) w.update(delta);
        updateLockTarget();
        if (defeatT >= 0 || weapons.isEmpty()) return;
        if (controlled >= 0 && controlled < fighters.size) {
            // on the stick of a fighter: fly and shoot its own cannon
            Fighter f = fighters.get(controlled);
            if (Gdx.input.isKeyJustPressed(Input.Keys.R) && f.rockets > 0) {
                f.rockets--;
                float rxv = -MathUtils.sinDeg(f.body.rotation);
                float ryv = MathUtils.cosDeg(f.body.rotation);
                projectiles.add(new Projectile(f.centerX() + rxv * 22f, f.centerY() + ryv * 22f,
                    f.body.rotation, f.body, Weapon.Type.ROCKET.speed,
                    Weapon.Type.ROCKET.damage, 160f, 0f, true));
                game.sfx.playRocket();
            }
            if (Gdx.input.isKeyPressed(Input.Keys.SPACE) && f.gun.ready()) {
                f.gun.fire();
                float fxv = -MathUtils.sinDeg(f.body.rotation);
                float fyv = MathUtils.cosDeg(f.body.rotation);
                projectiles.add(new Projectile(f.centerX() + fxv * 20f, f.centerY() + fyv * 20f,
                    f.body.rotation, f.body, f.gun.type.speed, f.gun.type.damage, 0f, 0f, false));
                game.sfx.playCannon(0);
            }
            if (beaming) {
                beaming = false;
                game.sfx.stopBeam();
            }
            return;
        }
        Player body = controlledBody();
        boolean held = Gdx.input.isKeyPressed(Input.Keys.SPACE);
        boolean anyBeam = false;
        for (Weapon w : weapons) {
            if (w.auto) {
                anyBeam |= autoFireMount(w, body, delta);
            } else if (w.selected) {
                float rot = slewMount(w, body, cursorBearing(body), delta);
                anyBeam |= fireMount(w, body, rot, held, delta);
            }
        }
        if (beaming && !anyBeam) {
            beaming = false;
            game.sfx.stopBeam();
        }
    }

    /** Bearing from the ship to the plotted aim point, in world degrees. */
    private float cursorBearing(Player body) {
        float cdx = lastAim.x - (body.x + Player.WIDTH / 2f);
        float cdy = lastAim.y - (body.y + Player.HEIGHT / 2f);
        return MathUtils.atan2(-cdx, cdy) * MathUtils.radiansToDegrees;
    }

    /** Effective reach of a mount, for auto acquisition and the range indicators (#140). */
    private static float weaponRange(Weapon.Type t) {
        return t.speed > 0 ? t.speed * 0.85f : 420f;
    }

    /** AUTO engagement reach (#156): light/medium feeders defend a larger bubble. */
    private static float autoRange(Weapon.Type t) {
        if (t == Weapon.Type.AUTOCANNON_20) return weaponRange(t) * 3.5f * 2f / 3f; // trimmed a third
        return weaponRange(t) * (t.ammoKind == Weapon.AmmoKind.LIGHT ? 3.5f : 1f);
    }

    /** Traverse rate by mount class (#170): the heavies take real time to lay. */
    private static float slewRate(Weapon.Type t) {
        switch (t) {
            case CANNON_155: return 40f;
            case AUTOCANNON_20:
            case MEDIUM_CANNON: return 90f;
            default: return 240f;
        }
    }

    /** Slews the mount toward a desired bearing within its arc; returns the barrel rotation. */
    private float slewMount(Weapon w, Player body, float desired, float delta) {
        if (w.type.turretArc <= 0) return body.rotation;
        float offset = ((desired - body.rotation) % 360f + 540f) % 360f - 180f;
        offset = MathUtils.clamp(offset, -w.type.turretArc, w.type.turretArc);
        float slew = slewRate(w.type) * delta; // mount turn-rate limit
        w.turret += MathUtils.clamp(offset - w.turret, -slew, slew);
        w.turret = MathUtils.clamp(w.turret, -w.type.turretArc, w.type.turretArc);
        return body.rotation + w.turret;
    }

    /** AUTO mounts (#139): acquire the nearest hostile in range, slew, and fire when aligned. */
    private boolean autoFireMount(Weapon w, Player body, float delta) {
        float cx = body.x + Player.WIDTH / 2f;
        float cy = body.y + Player.HEIGHT / 2f;
        Enemy target = null;
        float best = autoRange(w.type);
        for (Enemy e : enemies) {
            float d = Vector2.dst(cx, cy, e.centerX(), e.centerY());
            if (d < best) {
                best = d;
                target = e;
            }
        }
        if (target == null) return false;
        float desired = MathUtils.atan2(-(target.centerX() - cx), target.centerY() - cy)
            * MathUtils.radiansToDegrees;
        float rot = slewMount(w, body, desired, delta);
        float aimErr = ((desired - rot) % 360f + 540f) % 360f - 180f;
        return fireMount(w, body, rot, Math.abs(aimErr) < 6f, delta);
    }

    /** Runs one mount's firing logic; returns true when its beam burned this frame. */
    private boolean fireMount(Weapon w, Player body, float fireRotation, boolean held, float delta) {
        float fx = -MathUtils.sinDeg(fireRotation);
        float fy = MathUtils.cosDeg(fireRotation);
        float nx = body.x + Player.WIDTH / 2f + fx * 20f;
        float ny = body.y + Player.HEIGHT / 2f + fy * 20f;
        switch (w.type) {
            case BEAM_LASER:
                if (held && state.weaponEnergy > 0f) {
                    state.weaponEnergy = Math.max(0f, state.weaponEnergy - w.type.ammoCost * delta);
                    fireBeam(body, nx, ny, fireRotation, w.type.damage * delta, true);
                    if (!beaming) {
                        beaming = true;
                        game.sfx.startBeam();
                    }
                    return true;
                }
                break;
            case BURST_LASER:
                if (held && w.ready() && state.spendAmmo(w.type, 3)) {
                    w.fire();
                    w.burstLeft = 3;
                }
                if (w.burstLeft > 0 && w.burstTimer <= 0) {
                    w.burstLeft--;
                    w.burstTimer = 0.07f;
                    fireBeam(body, nx, ny, fireRotation + MathUtils.random(-2f, 2f), w.type.damage, false);
                    game.sfx.playLaser();
                }
                break;
            case AUTOCANNON_20:
                if (w.burstGap > 0) break;
                if (held && w.ready() && w.burstLeft == 0) {
                    w.burstLeft = 3 + Math.max(0, 2 - state.roomStats[4]); // sloppier hands overshoot (#172)
                }
                if (w.burstLeft > 0 && w.burstTimer <= 0 && state.spendAmmo(w.type, 1)) {
                    w.burstTimer = 0.09f;
                    w.burstLeft--;
                    projectiles.add(new Projectile(nx, ny,
                        fireRotation + MathUtils.random(-lightJitter(), lightJitter()), body,
                        w.type.speed, w.type.damage, 0f, 0f, false));
                    addBlast(nx, ny, 0f, 150f, 6f);
                    game.sfx.playCannon(0);
                    if (w.burstLeft == 0) {
                        w.magCount += 3;
                        if (w.magCount >= 9) { // three bursts, then a fresh magazine
                            w.magCount = 0;
                            w.cooldown = 5f;
                        } else {
                            w.burstGap = 1f;
                        }
                    }
                }
                break;
            case CANNON_155: {
                int tier = state.cannon155Tier();
                if (held && w.ready() && state.spendAmmo(w.type, tier)) {
                    w.fire();
                    // the reload clock runs on its own — shells in the air don't gate it.
                    // gunnery crew work the hoists: each stat point shaves the cycle
                    w.cooldown = w.type.reload / ((1f + 0.12f * state.roomStats[4])
                        * (0.7f + 0.15f * state.power[GameState.PWR_WEAPONS]));
                    w.burstLeft = tier;
                }
                if (w.burstLeft > 0 && w.burstTimer <= 0) {
                    // ripple fire: one round per barrel, left to right
                    int barrel = tier - w.burstLeft;
                    w.burstLeft--;
                    w.burstTimer = 0.18f; // measured salvo cadence
                    w.barrelRecoil[barrel] = 1f;
                    float lat = (barrel - (tier - 1) / 2f) * 7f;
                    float rxv = MathUtils.cosDeg(fireRotation);
                    float ryv = MathUtils.sinDeg(fireRotation);
                    float bx2 = nx + rxv * lat;
                    float by2 = ny + ryv * lat;
                    // dispersion: the shell actually splashes somewhere in the landing zone
                    float aimDist0 = Vector2.dst(bx2, by2, lastAim.x, lastAim.y);
                    float disp = dispersion155(aimDist0);
                    float offA = MathUtils.random(360f);
                    float offR = (float) Math.sqrt(MathUtils.random()) * disp;
                    float tx = lastAim.x + MathUtils.cosDeg(offA) * offR;
                    float ty = lastAim.y + MathUtils.sinDeg(offA) * offR;
                    float shotRot = MathUtils.atan2(-(tx - bx2), ty - by2) * MathUtils.radiansToDegrees;
                    Projectile shell = new Projectile(bx2, by2, shotRot, body,
                        w.type.speed, w.type.damage, 0f, 0f, false);
                    shell.originX = bx2;
                    shell.originY = by2;
                    shell.fuseT = Vector2.dst(bx2, by2, tx, ty) / w.type.speed;
                    projectiles.add(shell);
                    addBlast(bx2, by2, 0f, 150f, 16f);
                    addShockwave(bx2, by2, 20f);
                    addShake(0.22f);
                    game.sfx.playReport();
                }
                break;
            }
            default:
                if (held && w.ready() && state.spendAmmo(w.type, 1)) {
                    w.fire();
                    // gunnery crew and weapons power shorten the cycle
                    w.cooldown = w.type.reload / ((1f + 0.08f * state.roomStats[4])
                        * (0.7f + 0.15f * state.power[GameState.PWR_WEAPONS]));
                    Projectile p = new Projectile(nx, ny, fireRotation, body,
                        w.type.speed, w.type.damage,
                        w.type.isRocket() ? 260f : 0f,
                        w.type == Weapon.Type.HOMING_ROCKET ? 160f : 0f,
                        w.type.isRocket());
                    if (w.type == Weapon.Type.HOMING_ROCKET && lockTarget != null) p.target = lockTarget.body;
                    projectiles.add(p);
                    // muzzle flash scaled by caliber
                    addBlast(nx, ny, 0f, 150f, 5f + w.type.damage * 0.35f);
                    if (w.type.isRocket()) game.sfx.playRocket();
                    else game.sfx.playCannon(w.type == Weapon.Type.LIGHT_CANNON
                        || w.type == Weapon.Type.AUTOCANNON_20 ? 0
                        : w.type == Weapon.Type.MEDIUM_CANNON ? 1 : 2);
                }
        }
        return false;
    }

    /** Hitscan laser: damages the first enemy along the ray and leaves a beam flash. */
    private void fireBeam(Player body, float ox, float oy, float rotation, float damage, boolean continuous) {
        float fx = -MathUtils.sinDeg(rotation);
        float fy = MathUtils.cosDeg(rotation);
        float maxLen = 520f;
        Enemy hit = null;
        float hitT = maxLen;
        for (Enemy e : enemies) {
            if (e.body == body) continue;
            float rx = e.centerX() - ox;
            float ry = e.centerY() - oy;
            float t = rx * fx + ry * fy;
            if (t < 0 || t > hitT) continue;
            float px = ox + fx * t - e.centerX();
            float py = oy + fy * t - e.centerY();
            if (px * px + py * py < e.radius() * e.radius()) {
                hit = e;
                hitT = t;
            }
        }
        float ex = ox + fx * hitT;
        float ey = oy + fy * hitT;
        beams.add(new float[]{ox, oy, ex, ey, continuous ? 0.05f : 0.14f, continuous ? 0.05f : 0.14f});
        if (hit != null) {
            addSparks(ex, ey, 0, 0, continuous ? 1 : 4);
            damageEnemyAt(hit, damage, ex, ey);
        }
    }

    /** Homing rockets acquire the nearest enemy in a forward cone. */
    private void updateLockTarget() {
        lockTarget = null;
        boolean homingSelected = false;
        for (Weapon lw : weapons) {
            if ((lw.selected || lw.auto) && lw.type == Weapon.Type.HOMING_ROCKET) homingSelected = true;
        }
        if (!homingSelected) return;
        Player body = controlledBody();
        float fx = -MathUtils.sinDeg(body.rotation);
        float fy = MathUtils.cosDeg(body.rotation);
        float best = 700f;
        for (Enemy e : enemies) {
            if (e.body == body) continue;
            float dx = e.centerX() - (body.x + Player.WIDTH / 2f);
            float dy = e.centerY() - (body.y + Player.HEIGHT / 2f);
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > best || d < 1f) continue;
            float dot = (dx * fx + dy * fy) / d;
            if (dot < 0.64f) continue; // ~50 degree cone
            best = d;
            lockTarget = e;
        }
    }

    private void killEnemy(int index) {
        addShockwave(enemies.get(index).centerX(), enemies.get(index).centerY(),
            enemies.get(index).boss ? 160f : 90f);
        addShake(0.45f);
        for (int i = critToasts.size - 1; i >= 0; i--) {
            if (critToasts.get(i).e == enemies.get(index)) critToasts.removeIndex(i);
        }
        for (Projectile p : projectiles) {
            if (p.target == enemies.get(index).body) p.target = null; // lock dies with the ship
        }
        for (Dogfight d : dogfights) {
            d.foes.removeValue(enemies.get(index), true); // fell out of the furball
        }
        Enemy e = enemies.get(index);
        spawnDeathEffect(e.centerX(), e.centerY(), e.body.vx, e.body.vy);
        Enemy dead = enemies.get(index);
        wrecks.add(new float[]{dead.centerX(), dead.centerY(),
            dead.body.vx * 0.4f, dead.body.vy * 0.4f,
            dead.body.rotation, MathUtils.random(-40f, 40f),
            dead.boss ? 150f : MathUtils.random(18f, 40f)});
        enemies.removeIndex(index);
        state.credits += Math.round((dead.mothership ? 400 : dead.boss ? 300 : CREDITS_PER_KILL)
            * Difficulty.rewardFactor(state.sector));
        state.hostilesDestroyed++;
        game.sfx.playCatch();
        if (dead.runner) {
            completeObjective("RUNNER DESTROYED — BOUNTY PAID", 100);
        } else if (objective == Obj.ELIMINATE && enemies.isEmpty()) {
            completeObjective(dead.boss ? "FLAGSHIP DESTROYED — THE GATE IS CLEAR"
                : "HOSTILES ELIMINATED", 0);
        } else if (objective == Obj.INTERCEPT && enemies.isEmpty()) {
            completeObjective("HOSTILES ELIMINATED", 0);
        }
    }

    private void addShockwave(float x, float y, float maxR) {
        shockwaves.add(new float[]{x, y, 0f, maxR, MathUtils.random(1000f)});
    }

    private void addShake(float amount) {
        shake = Math.min(1f, shake + amount);
    }

    /** Picks a random destruction from the pool, parameters jittered per kill. */
    private void spawnDeathEffect(float x, float y, float vx, float vy) {
        DeathKind kind = DeathKind.values()[MathUtils.random(DeathKind.values().length - 1)];
        switch (kind) {
            case FIREBALL:
                addBlast(x, y, 0f, MathUtils.random(150f, 220f), MathUtils.random(40f, 65f));
                addSparks(x, y, vx, vy, MathUtils.random(16, 24));
                break;
            case BREAKUP:
                addShards(x, y, vx, vy, MathUtils.random(4, 6));
                addSparks(x, y, vx, vy, MathUtils.random(5, 9));
                break;
            case CHAIN: {
                int pops = MathUtils.random(3, 4);
                for (int i = 0; i < pops; i++) {
                    addBlast(x + MathUtils.random(-14f, 14f), y + MathUtils.random(-14f, 14f),
                        i * MathUtils.random(0.08f, 0.14f), MathUtils.random(90f, 130f),
                        MathUtils.random(12f, 20f));
                }
                addBlast(x, y, pops * 0.12f + 0.1f, MathUtils.random(170f, 230f), MathUtils.random(45f, 60f));
                addSparks(x, y, vx, vy, MathUtils.random(10, 16));
                break;
            }
            case OVERLOAD:
                addBlast(x, y, 0f, MathUtils.random(18f, 26f), MathUtils.random(12f, 16f)); // slow swell
                addBlast(x, y, MathUtils.random(0.4f, 0.6f), MathUtils.random(260f, 340f),
                    MathUtils.random(55f, 75f)); // then the sharp blast
                addSparks(x, y, vx, vy, MathUtils.random(12, 18));
                break;
        }
    }

    private void addBlast(float x, float y, float delay, float speed, float maxR) {
        Blast b = new Blast();
        b.x = x;
        b.y = y;
        b.age = -delay;
        b.speed = speed;
        b.maxR = maxR;
        blasts.add(b);
    }

    private void addSparks(float x, float y, float vx, float vy, int count) {
        for (int i = 0; i < count; i++) {
            Spark s = new Spark();
            s.x = x;
            s.y = y;
            float angle = MathUtils.random(360f);
            float speed = MathUtils.random(40f, 160f);
            s.vx = vx * 0.4f + MathUtils.cosDeg(angle) * speed;
            s.vy = vy * 0.4f + MathUtils.sinDeg(angle) * speed;
            s.maxLife = s.life = MathUtils.random(0.35f, 0.9f);
            sparks.add(s);
        }
    }

    private void addShards(float x, float y, float vx, float vy, int count) {
        for (int i = 0; i < count; i++) {
            Shard f = new Shard();
            f.x = x + MathUtils.random(-8f, 8f);
            f.y = y + MathUtils.random(-8f, 8f);
            float angle = MathUtils.random(360f);
            float speed = MathUtils.random(20f, 70f);
            f.vx = vx * 0.6f + MathUtils.cosDeg(angle) * speed;
            f.vy = vy * 0.6f + MathUtils.sinDeg(angle) * speed;
            f.rotation = MathUtils.random(360f);
            f.spin = MathUtils.random(-240f, 240f);
            f.len = MathUtils.random(6f, 16f);
            f.maxLife = f.life = MathUtils.random(1f, 1.7f);
            shards.add(f);
        }
    }

    private void updateEffects(float delta) {
        for (int i = sparks.size - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.life -= delta;
            if (s.life <= 0) {
                sparks.removeIndex(i);
                continue;
            }
            s.x += s.vx * delta;
            s.y += s.vy * delta;
        }
        for (int i = shards.size - 1; i >= 0; i--) {
            Shard f = shards.get(i);
            f.life -= delta;
            if (f.life <= 0) {
                shards.removeIndex(i);
                continue;
            }
            f.x += f.vx * delta;
            f.y += f.vy * delta;
            f.rotation += f.spin * delta;
        }
        for (int i = blasts.size - 1; i >= 0; i--) {
            Blast b = blasts.get(i);
            b.age += delta;
            if (b.age > 0 && b.age * b.speed > b.maxR) blasts.removeIndex(i);
        }
        for (int i = beams.size - 1; i >= 0; i--) {
            float[] beam = beams.get(i);
            beam[4] -= delta;
            if (beam[4] <= 0) beams.removeIndex(i);
        }
        for (int i = shockwaves.size - 1; i >= 0; i--) {
            float[] swv = shockwaves.get(i);
            swv[2] += delta;
            if (swv[2] > 0.3f) shockwaves.removeIndex(i);
        }
    }

    private void drawEffects() {
        drawLockMarker();
        if (sparks.size == 0 && shards.size == 0 && blasts.size == 0 && beams.size == 0) return;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        // solar build-up: the whole arena warms up as the flare charges (#147)
        if (state.map.getCurrentNode().stormy && stormTimer < STORM_BUILDUP && defeatT < 0) {
            float ramp = (1f - stormTimer / STORM_BUILDUP) * 0.5f + (stormFlash > 0 ? stormFlash : 0f);
            shapeRenderer.setColor(0.5f * ramp, 0.25f * ramp, 0.05f * ramp, 1f);
            float inset = 6f;
            shapeRenderer.rect(inset, inset, ARENA_WIDTH - 2 * inset, ARENA_HEIGHT - 2 * inset);
            shapeRenderer.rect(inset * 3, inset * 3, ARENA_WIDTH - 6 * inset, ARENA_HEIGHT - 6 * inset);
        }
        // mini shockwaves: crisp leading edge + fading trail, slightly off-round
        for (float[] swv : shockwaves) {
            float t = swv[2] / 0.3f;
            float r = swv[3] * t;
            float a = 1f - t;
            float jx = 1f + 0.06f * MathUtils.sin(swv[4]);
            shapeRenderer.setColor(1f, 0.9f * a + 0.1f, 0.5f * a, 1f);
            shapeRenderer.ellipse(swv[0] - r * jx, swv[1] - r / jx, r * 2 * jx, r * 2 / jx);
            shapeRenderer.setColor(0.7f * a, 0.4f * a, 0.2f * a, 1f);
            float r2 = Math.max(0f, r - 4f);
            shapeRenderer.ellipse(swv[0] - r2 * jx, swv[1] - r2 / jx, r2 * 2 * jx, r2 * 2 / jx);
        }
        // laser flashes: dim glow + bright core
        for (float[] beam : beams) {
            float a = beam[4] / beam[5];
            shapeRenderer.setColor(0.5f * a, 0.1f * a, 0.6f * a, 1f);
            shapeRenderer.line(beam[0], beam[1] + 1.5f, beam[2], beam[3] + 1.5f);
            shapeRenderer.line(beam[0], beam[1] - 1.5f, beam[2], beam[3] - 1.5f);
            shapeRenderer.setColor(a, 0.5f + 0.5f * a, a, 1f);
            shapeRenderer.line(beam[0], beam[1], beam[2], beam[3]);
        }
        for (Spark s : sparks) {
            float f = s.life / s.maxLife;
            if (s.gray) shapeRenderer.setColor(0.55f * f + 0.1f, 0.55f * f + 0.1f, 0.58f * f + 0.1f, 1f);
            else shapeRenderer.setColor(1f, 0.65f * f + 0.1f, 0.15f * f, 1f);
            shapeRenderer.line(s.x, s.y, s.x - s.vx * 0.05f, s.y - s.vy * 0.05f);
        }
        for (Shard f : shards) {
            float a = f.life / f.maxLife;
            shapeRenderer.setColor(0.9f * a, 0.3f * a, 0.2f * a, 1f);
            float cos = MathUtils.cosDeg(f.rotation);
            float sin = MathUtils.sinDeg(f.rotation);
            shapeRenderer.line(f.x - cos * f.len, f.y - sin * f.len, f.x + cos * f.len, f.y + sin * f.len);
        }
        for (Blast b : blasts) {
            if (b.age <= 0) continue;
            float r = b.age * b.speed;
            float a = 1f - r / b.maxR;
            shapeRenderer.setColor(1f, 0.75f * a + 0.15f, 0.2f * a, 1f);
            shapeRenderer.circle(b.x, b.y, r, 32);
        }
        shapeRenderer.end();
    }

    /** Seeded scorch nicks accumulate as hp drops, so damage is visible and stable. */
    private void drawDamageMarks(Enemy e) {
        float frac = e.hp / e.maxHp;
        if (frac >= 0.98f) return;
        java.util.Random r = new java.util.Random(e.seed);
        int marks = Math.min(6, 1 + (int) ((1f - frac) * 6f));
        shapeRenderer.setColor(0.45f, 0.1f, 0.08f, 1f);
        for (int m = 0; m < marks; m++) {
            float mx = -18f + r.nextFloat() * 36f;
            float my = -10f + r.nextFloat() * 24f;
            float ang = r.nextFloat() * 360f;
            float len = 3f + r.nextFloat() * 4f;
            shapeRenderer.line(mx, my, mx + MathUtils.cosDeg(ang) * len, my + MathUtils.sinDeg(ang) * len);
        }
        // status glyphs beside the craft (drawn in local space just off the wing)
        float gx = 26f;
        if (e.onFire) {
            shapeRenderer.setColor(1f, 0.55f, 0.15f, 1f);
            shapeRenderer.line(gx, 0, gx + 2, 6);
            shapeRenderer.line(gx + 2, 6, gx + 4, 1);
            shapeRenderer.line(gx + 4, 1, gx + 6, 5);
            gx += 10f;
        }
        if (e.engineOut) {
            shapeRenderer.setColor(0.9f, 0.3f, 0.25f, 1f);
            shapeRenderer.line(gx, 0, gx + 5, 5);
            shapeRenderer.line(gx, 5, gx + 5, 0);
            gx += 10f;
        }
        if (e.helmDamaged) {
            shapeRenderer.setColor(0.9f, 0.7f, 0.2f, 1f);
            shapeRenderer.line(gx, 2, gx + 2, 4);
            shapeRenderer.line(gx + 2, 4, gx + 4, 2);
            shapeRenderer.line(gx + 4, 2, gx + 6, 4);
        }
    }

    /** Crit tooltips floating beside the affected craft. */
    private void drawCritToasts() {
        if (critToasts.size == 0) return;
        batch.setProjectionMatrix(viewport.getCamera().combined);
        batch.begin();
        Fonts.scale(font, 1f);
        for (CritToast ct : critToasts) {
            float a = Math.min(1f, ct.t / 0.6f);
            font.setColor(0f, 0f, 0f, a);
            font.draw(batch, ct.text, ct.e.centerX() + 25f, ct.e.centerY() + 25f - 1);
            font.setColor(1f, 0.6f, 0.2f, a);
            font.draw(batch, ct.text, ct.e.centerX() + 24f, ct.e.centerY() + 25f);
        }
        Fonts.scale(font, 1.4f);
        batch.end();
    }

    /** Warning diamonds over incoming rounds on a collision course with the fighter. */
    private void drawThreatMarkers() {
        if (defeatT >= 0) return;
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        boolean began = false;
        for (Projectile p : projectiles) {
            if (p.shooter == player) continue;
            float rx = p.x - cx;
            float ry = p.y - cy;
            float vx = p.velX() - player.vx;
            float vy = p.velY() - player.vy;
            float v2 = vx * vx + vy * vy;
            if (v2 < 1f) continue;
            float tca = -(rx * vx + ry * vy) / v2;
            if (tca < 0 || tca > 1.2f) continue;
            float qx = rx + vx * tca;
            float qy = ry + vy * tca;
            if (qx * qx + qy * qy > 64f * 64f) continue;
            if (!began) {
                shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
                began = true;
            }
            float pulse = 0.6f + 0.4f * MathUtils.sin(shieldSince * 20f + p.x);
            shapeRenderer.setColor(1f * pulse, 0.15f, 0.1f, 1f);
            float r = 7f + p.damage * 0.15f;
            shapeRenderer.line(p.x - r, p.y, p.x, p.y + r);
            shapeRenderer.line(p.x, p.y + r, p.x + r, p.y);
            shapeRenderer.line(p.x + r, p.y, p.x, p.y - r);
            shapeRenderer.line(p.x, p.y - r, p.x - r, p.y);
        }
        if (began) shapeRenderer.end();
    }

    /** Screen-edge chevrons pointing at off-screen enemies and incoming ordnance (#108). */
    private void drawEdgeArrows() {
        if (defeatT >= 0) return;
        com.badlogic.gdx.graphics.OrthographicCamera cam =
            (com.badlogic.gdx.graphics.OrthographicCamera) viewport.getCamera();
        float halfW = viewport.getWorldWidth() * cam.zoom / 2f - 26f;
        float halfH = viewport.getWorldHeight() * cam.zoom / 2f - 26f;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (Enemy e : enemies) {
            drawEdgeArrow(cam, halfW, halfH, e.centerX(), e.centerY(), 0.55f, 0.15f, 0.12f);
        }
        for (Projectile p : projectiles) {
            if (p.shooter == player || !p.rocket) continue;
            drawEdgeArrow(cam, halfW, halfH, p.x, p.y, 1f, 0.2f, 0.15f);
        }
        shapeRenderer.end();
    }

    private void drawEdgeArrow(com.badlogic.gdx.graphics.OrthographicCamera cam,
                               float halfW, float halfH, float tx, float ty,
                               float r, float gcol, float bcol) {
        float dx = tx - cam.position.x;
        float dy = ty - cam.position.y;
        if (Math.abs(dx) <= halfW && Math.abs(dy) <= halfH) return; // on screen
        // clamp direction to the view rectangle border
        float scaleX = Math.abs(dx) > 0.01f ? halfW / Math.abs(dx) : Float.MAX_VALUE;
        float scaleY = Math.abs(dy) > 0.01f ? halfH / Math.abs(dy) : Float.MAX_VALUE;
        float s = Math.min(scaleX, scaleY);
        float ex = cam.position.x + dx * s;
        float ey = cam.position.y + dy * s;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float nx = dx / len;
        float ny = dy / len;
        shapeRenderer.setColor(r, gcol, bcol, 1f);
        // chevron pointing outward
        shapeRenderer.line(ex - nx * 10 - ny * 6, ey - ny * 10 + nx * 6, ex, ey);
        shapeRenderer.line(ex - nx * 10 + ny * 6, ey - ny * 10 - nx * 6, ex, ey);
    }

    /** Lock-on brackets around the homing target, so auto-aim shows its hand. */
    private void drawLockMarker() {
        if (lockTarget == null) return;
        float cx = lockTarget.centerX();
        float cy = lockTarget.centerY();
        float r = lockTarget.radius() + 8f;
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(1f, 0.65f, 0.15f, 1f);
        for (int q = 0; q < 4; q++) {
            float sx = (q % 2 == 0) ? -1 : 1;
            float sy = (q < 2) ? -1 : 1;
            shapeRenderer.line(cx + sx * r, cy + sy * r, cx + sx * (r - 7), cy + sy * r);
            shapeRenderer.line(cx + sx * r, cy + sy * r, cx + sx * r, cy + sy * (r - 7));
        }
        shapeRenderer.end();
    }

    /** Friendly squadrons: green hulls, squadron tint, selection rings and order markers. */
    private void drawFighters() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        // scope signatures (#161): one chevron per craft in the formation, phosphor streaks
        for (int i = 0; i < fighters.size; i++) {
            Fighter f = fighters.get(i);
            if (f.dock == 2) continue; // tucked away in the hangar
            float dockScale = 1f;
            if (f.dock == 1) dockScale = f.dockAnimT / 0.8f;
            else if (f.dock == 3) dockScale = 1f - f.dockAnimT / 0.8f;
            float ms = Math.max(8f, 5.5f * viewZoom) * dockScale;
            if (i == controlled) shapeRenderer.setColor(Color.WHITE);
            else if (f.squadron == 0) shapeRenderer.setColor(0.25f, 0.85f, 0.4f, 1f);
            else shapeRenderer.setColor(0.3f, 0.8f, 0.75f, 1f);
            float ang = f.body.rotation;
            float fxm = -MathUtils.sinDeg(ang);
            float fym = MathUtils.cosDeg(ang);
            float rxm = MathUtils.cosDeg(ang);
            float rym = MathUtils.sinDeg(ang);
            for (int slot = 0; slot < f.strength; slot++) {
                float cx = f.centerX() + formX(slot) * rxm + formY(slot) * fxm;
                float cy = f.centerY() + formX(slot) * rym + formY(slot) * fym;
                shapeRenderer.line(cx - rxm * ms - fxm * ms, cy - rym * ms - fym * ms,
                    cx + fxm * ms, cy + fym * ms);
                shapeRenderer.line(cx + rxm * ms - fxm * ms, cy + rym * ms - fym * ms,
                    cx + fxm * ms, cy + fym * ms);
            }
            float spd = (float) Math.sqrt(f.body.vx * f.body.vx + f.body.vy * f.body.vy);
            if (spd > 10f && f.dock == 0) { // phosphor streak
                shapeRenderer.setColor(0.16f, 0.38f, 0.24f, 1f);
                shapeRenderer.line(f.centerX(), f.centerY(),
                    f.centerX() - f.body.vx * 0.5f, f.centerY() - f.body.vy * 0.5f);
            }
        }
        shapeRenderer.setTransformMatrix(transform.idt());
        // command cursor (#154): stow/launch state beside the pointer
        if (selectedSquadron != -1 && defeatT < 0) {
            Vector2 cur = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
            boolean overCarrier = Vector2.dst(cur.x, cur.y, player.x + Player.WIDTH / 2f,
                player.y + Player.HEIGHT / 2f) < CARRIER_HIT_RADIUS + 16f;
            boolean docked = false;
            for (Fighter f : fighters) {
                if (f.squadron == selectedSquadron && f.dock == 2) docked = true;
            }
            if (overCarrier) { // stow: arrow down into a bracket
                shapeRenderer.setColor(0.4f, 0.9f, 1f, 1f);
                shapeRenderer.line(cur.x + 16, cur.y + 20, cur.x + 16, cur.y + 8);
                shapeRenderer.line(cur.x + 12, cur.y + 12, cur.x + 16, cur.y + 8);
                shapeRenderer.line(cur.x + 20, cur.y + 12, cur.x + 16, cur.y + 8);
                shapeRenderer.line(cur.x + 9, cur.y + 8, cur.x + 9, cur.y + 4);
                shapeRenderer.line(cur.x + 9, cur.y + 4, cur.x + 23, cur.y + 4);
                shapeRenderer.line(cur.x + 23, cur.y + 4, cur.x + 23, cur.y + 8);
            } else if (docked) { // launch: arrow up out of a bracket
                shapeRenderer.setColor(0.4f, 1f, 0.6f, 1f);
                shapeRenderer.line(cur.x + 16, cur.y + 6, cur.x + 16, cur.y + 18);
                shapeRenderer.line(cur.x + 12, cur.y + 14, cur.x + 16, cur.y + 18);
                shapeRenderer.line(cur.x + 20, cur.y + 14, cur.x + 16, cur.y + 18);
                shapeRenderer.line(cur.x + 9, cur.y + 6, cur.x + 23, cur.y + 6);
            }
        }
        // dogfight furballs: faint engagement ring + snapping tracer exchanges (#141)
        for (Dogfight d : dogfights) {
            shapeRenderer.setColor(0.8f, 0.6f, 0.25f, 1f);
            shapeRenderer.circle(d.x, d.y, DOGFIGHT_RADIUS + 6f + 3f * MathUtils.sin(d.anim * 3f), 24);
            for (int k = 0; k < 3; k++) {
                if (!MathUtils.randomBoolean(0.35f)) continue;
                float a1 = MathUtils.random(360f);
                float a2 = a1 + MathUtils.random(90f, 260f);
                float r1 = MathUtils.random(10f, DOGFIGHT_RADIUS);
                float r2 = MathUtils.random(10f, DOGFIGHT_RADIUS);
                shapeRenderer.setColor(1f, 0.85f, 0.4f, 1f);
                shapeRenderer.line(d.x + MathUtils.cosDeg(a1) * r1, d.y + MathUtils.sinDeg(a1) * r1,
                    d.x + MathUtils.cosDeg(a2) * r2, d.y + MathUtils.sinDeg(a2) * r2);
            }
        }
        // selection rings + the selected squadron's order marker
        if (selectedSquadron != -1) {
            shapeRenderer.setColor(Color.WHITE);
            for (Fighter f : fighters) {
                if (f.squadron == selectedSquadron) {
                    shapeRenderer.circle(f.centerX(), f.centerY(), 14f, 16);
                }
            }
            Squadron sq = squadrons[selectedSquadron];
            if (sq.mode == 1) {
                shapeRenderer.setColor(0.4f, 0.9f, 1f, 1f);
                shapeRenderer.line(sq.ox - 8, sq.oy - 8, sq.ox + 8, sq.oy + 8);
                shapeRenderer.line(sq.ox - 8, sq.oy + 8, sq.ox + 8, sq.oy - 8);
            } else if (sq.mode == 2 && sq.target != null) {
                shapeRenderer.setColor(1f, 0.4f, 0.3f, 1f);
                shapeRenderer.circle(sq.target.centerX(), sq.target.centerY(),
                    sq.target.radius() + 14f, 20);
            }
        }
        shapeRenderer.end();
    }

    /** Hostile wireframes, drawn in red; same hull as the player until variants exist. */
    private void drawEnemies() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (Enemy e : enemies) {
            if (e.mothership || e.boss) continue; // capitals draw as full returns below
            float ms = Math.max(7f, 5f * viewZoom);
            if (e.arms == SquadArms.TORPEDO) shapeRenderer.setColor(1f, 0.4f, 0.5f, 1f);
            else if (e.runner) shapeRenderer.setColor(1f, 0.75f, 0.2f, 1f);
            else shapeRenderer.setColor(0.95f, 0.25f, 0.2f, 1f);
            // one diamond per craft in the formation
            float ang = e.body.rotation;
            float fxm = -MathUtils.sinDeg(ang);
            float fym = MathUtils.cosDeg(ang);
            float rxm = MathUtils.cosDeg(ang);
            float rym = MathUtils.sinDeg(ang);
            for (int slot = 0; slot < Math.max(1, e.strength); slot++) {
                float cx = e.centerX() + formX(slot) * rxm + formY(slot) * fxm;
                float cy = e.centerY() + formX(slot) * rym + formY(slot) * fym;
                shapeRenderer.line(cx - ms, cy, cx, cy + ms);
                shapeRenderer.line(cx, cy + ms, cx + ms, cy);
                shapeRenderer.line(cx + ms, cy, cx, cy - ms);
                shapeRenderer.line(cx, cy - ms, cx - ms, cy);
            }
            float spd = (float) Math.sqrt(e.body.vx * e.body.vx + e.body.vy * e.body.vy);
            if (spd > 10f) {
                shapeRenderer.setColor(0.4f, 0.14f, 0.12f, 1f);
                shapeRenderer.line(e.centerX(), e.centerY(),
                    e.centerX() - e.body.vx * 0.5f, e.centerY() - e.body.vy * 0.5f);
            }
        }
        for (int i = 0; i < enemies.size; i++) {
            Enemy e = enemies.get(i);
            if (i == controlled) shapeRenderer.setColor(Color.ORANGE);
            else if (e.runner) shapeRenderer.setColor(1f, 0.75f, 0.2f, 1f);
            else if (e.arms == SquadArms.TORPEDO) shapeRenderer.setColor(1f, 0.4f, 0.5f, 1f); // priority!
            else if (e.arms == SquadArms.HOMING) shapeRenderer.setColor(1f, 0.3f, 0.35f, 1f);
            else shapeRenderer.setColor(0.95f, 0.25f, 0.2f, 1f);
            if (!e.boss && !e.mothership) continue; // squads are scope glyphs, drawn above
            for (int slot = 0; slot < Math.max(1, e.strength); slot++) {
                transform.setToTranslation(e.centerX(), e.centerY(), 0).rotate(0, 0, 1, e.body.rotation);
                if (e.boss) transform.scale(2.2f, 2.2f, 1f);
                else if (!e.mothership) {
                    transform.translate(formX(slot), formY(slot), 0).scale(0.42f, 0.42f, 1f);
                }
                shapeRenderer.setTransformMatrix(transform);
                if (e.mothership) {
                    ShipRenderer.drawCarrier(shapeRenderer);
                } else if (e.boss) {
                    ShipRenderer.drawB2Core(shapeRenderer);
                    if (e.leftWingHp > 0) ShipRenderer.drawB2Wing(shapeRenderer, true);
                    if (e.rightWingHp > 0) ShipRenderer.drawB2Wing(shapeRenderer, false);
                } else {
                    ShipRenderer.drawB2(shapeRenderer);
                }
                if (e.boss || e.mothership) break;
            }
            drawDamageMarks(e);
            if (e.body.thrustLevel > 0.02f) {
                float flick = 0.8f + 0.35f * MathUtils.random();
                shapeRenderer.setColor(1f, 0.5f, 0.12f, 1f);
                ShipRenderer.drawExhaust(shapeRenderer, e.body.thrustLevel * flick);
                shapeRenderer.setColor(1f, 0.85f, 0.4f, 1f);
                ShipRenderer.drawExhaust(shapeRenderer, e.body.thrustLevel * flick * 0.55f);
            }
        }
        // standing shield bubble (#158): presence tracks the shield fraction
        if (defeatT < 0 && state.maxShield > 0) {
            float frac = state.shield / state.maxShield;
            if (frac > 0.01f) {
                float glow = 0.12f + 0.4f * frac + 0.4f * Math.max(0f, shieldFlash);
                float flick = 1f + 0.04f * MathUtils.sin(shieldSince * 7f);
                shapeRenderer.setColor(0.25f * glow, 0.55f * glow, 0.9f * glow, 1f);
                shapeRenderer.circle(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f,
                    96f * flick, 40);
                shapeRenderer.setColor(0.15f * glow, 0.35f * glow, 0.6f * glow, 1f);
                shapeRenderer.circle(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f,
                    101f * flick, 40);
            }
        }
        // the carrier's persistent battle scars (#123), on the own-ship marker
        if (!state.damageMarks.isEmpty() && defeatT < 0) {
            shapeRenderer.setColor(0.45f, 0.3f, 0.18f, 1f);
            transform.setToTranslation(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f, 0)
                .rotate(0, 0, 1, player.rotation).scale(0.62f, 0.62f, 1f);
            shapeRenderer.setTransformMatrix(transform);
            for (float[] mk : state.damageMarks) {
                shapeRenderer.circle(mk[0], mk[1], mk[2], 8);
            }
        }
        shapeRenderer.setTransformMatrix(transform.idt());
        shapeRenderer.end();
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}

    @Override
    public void hide() {
        dispose();
    }

    @Override
    public void dispose() {
        game.sfx.stopBeam();
        game.sfx.stopThruster();
        shapeRenderer.dispose();
        batch.dispose();
    }
}
