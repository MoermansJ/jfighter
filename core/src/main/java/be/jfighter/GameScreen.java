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
    private float tacticalZoom = 1.4f; // wide default; Z cycles 1.0 / 1.4 / 1.9 (#146)
    private float cupolaCd;      // MG-46 cupolas share a cadence clock (#119)
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
        {"LMB", "select squadron / give order"},
        {"RMB", "carrier waypoint / deselect"},
        {"TAB", "take a fighter's stick"},
        {"R", "fighter rocket pod (on the stick)"},
        {"V", "deck monitor: crew, power, doors"},
        {"Z", "cycle tactical zoom"},
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
    private enum Obj { ELIMINATE, SURVIVE, INTERCEPT }
    private Obj objective = Obj.ELIMINATE;
    private boolean objectiveDone;
    private float surviveT;
    private float waveT;
    private float shake;   // camera trauma, decays fast
    private boolean beaming;
    private final Array<Shard> shards = new Array<>();
    private final Array<Blast> blasts = new Array<>();

    // carrier ops (#117): friendly fighters flying in squadrons, dispatched by mouse
    private static final int SQUADRON_COUNT = 2;
    private static final int FIGHTERS_PER_SQUADRON = 3;
    private static final float FIGHTER_HP = 24f;
    private static final float CARRIER_HIT_RADIUS = 80f;

    private static class Fighter {
        final Player body;
        float hp = FIGHTER_HP;
        final int squadron;
        final Weapon gun = new Weapon(Weapon.Type.LIGHT_CANNON);
        int rockets = 2;      // pod rounds per sortie (#126), restocked at the carrier
        float rocketCd;
        Enemy engaging; // current dogfight target

        Fighter(float x, float y, int squadron) {
            body = new Player(x, y);
            body.rotation = MathUtils.random(360f);
            body.thrustMult = 1.5f; // interceptors: quick and twitchy (#132)
            body.turnMult = 1.8f;
            this.squadron = squadron;
        }

        float centerX() {
            return body.x + Player.WIDTH / 2f;
        }

        float centerY() {
            return body.y + Player.HEIGHT / 2f;
        }
    }

    private static class Squadron {
        int mode; // 0 escort carrier, 1 move to point, 2 attack target
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
        int alive = 0;
        for (Fighter f : fighters) {
            if (f.squadron == s) alive++;
        }
        return alive;
    }

    private com.badlogic.gdx.math.Rectangle squadronTabRect(int s) {
        return new com.badlogic.gdx.math.Rectangle(10, HUD_H - 142 - s * 38, 150, 34);
    }

    /** HUD-space mouse position (the HUD ortho spans the whole window). */
    private Vector2 hudMouse() {
        return new Vector2(Gdx.input.getX() / (float) Gdx.graphics.getWidth() * HUD_W,
            HUD_H - Gdx.input.getY() / (float) Gdx.graphics.getHeight() * HUD_H);
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
        boolean boss;   // heavy multi-section flagship (#106)
        boolean runner; // intercept objective: flees for the arena edge (#104)

        float radius() {
            return boss ? 40f : ENEMY_RADIUS;
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
        deckView = new ShipDeckView(state);
        // squadrons launch with the carrier on the field
        for (int s = 0; s < SQUADRON_COUNT; s++) {
            squadrons[s] = new Squadron();
            for (int f = 0; f < FIGHTERS_PER_SQUADRON; f++) {
                fighters.add(new Fighter(player.x + MathUtils.random(-90f, 90f),
                    player.y + MathUtils.random(-90f, 90f), s));
            }
        }
        boolean bossFight = state.sector >= 3
            && state.map.getCurrentNode().id == state.map.lastNodeId;
        if (bossFight) {
            spawnBoss();
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

    private void spawnEnemies() {
        int count = Difficulty.enemyCount(state.sector);
        for (int i = 0; i < count; i++) {
            float x, y;
            do {
                x = MathUtils.random(ARENA_WIDTH * 0.45f, ARENA_WIDTH - 80f);
                y = MathUtils.random(60f, ARENA_HEIGHT - 60f);
            } while (tooCloseToOthers(x, y));
            Enemy e = new Enemy(x, y, Difficulty.enemyHp(state.sector));
            e.maxHp = e.hp;
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
        if (defeatT < 0) pause.handleEscape();
        if (!pause.isOpen()) {
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
            effects.spawnExhaust(player, delta);
            for (Enemy e : enemies) effects.spawnExhaust(e.body, delta);
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
            state.weaponEnergy = Math.min(state.maxWeaponEnergy,
                state.weaponEnergy + (2f + 4f * state.power[GameState.PWR_WEAPONS]) * delta);
            if (Dev.MODE) {
                state.ammoLight = Math.max(state.ammoLight, 600);
                state.ammoHeavy = Math.max(state.ammoHeavy, 24);
                state.ammoRockets = Math.max(state.ammoRockets, 12);
            }
            // the mothership: ponderous, all reactor and crew (#117)
            player.thrustMult = state.thrustMult() * (1f + 0.04f * state.roomStats[0])
                * (0.6f + 0.2f * state.power[GameState.PWR_ENGINES]) * 0.4f;
            player.turnMult = (helmCritT > 0 ? 0.4f : 1f) * 0.24f; // swinging a city block (#148)
            effects.setCarrierHull(true);
            game.sfx.setThrusterLevel(controlledBody().thrustLevel);
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

        ScreenUtils.clear(0, 0, 0, 1f);
        viewport.apply();
        effects.applyZoom(viewport, controlledBody(), delta);
        ((com.badlogic.gdx.graphics.OrthographicCamera) viewport.getCamera()).zoom *= tacticalZoom;
        // follow camera, clamped inside the arena
        com.badlogic.gdx.graphics.OrthographicCamera cam =
            (com.badlogic.gdx.graphics.OrthographicCamera) viewport.getCamera();
        float halfW = viewport.getWorldWidth() * cam.zoom / 2f;
        float halfH = viewport.getWorldHeight() * cam.zoom / 2f;
        Player followed = controlledBody();
        cam.position.x = MathUtils.clamp(followed.x + Player.WIDTH / 2f, halfW, ARENA_WIDTH - halfW);
        cam.position.y = MathUtils.clamp(followed.y + Player.HEIGHT / 2f, halfH, ARENA_HEIGHT - halfH);
        if (shake > 0) {
            float mag = shake * shake * 9f; // trauma curve: small hits barely move it
            cam.position.x += MathUtils.random(-mag, mag);
            cam.position.y += MathUtils.random(-mag, mag);
            shake = Math.max(0f, shake - delta * 2.2f);
        }
        cam.update();
        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);

        effects.renderBackground(shapeRenderer);
        drawArenaBounds();
        effects.renderAutopilot(shapeRenderer);
        drawEnemies();
        drawFighters();
        drawEffects();
        drawThreatMarkers();
        drawEdgeArrows();
        drawCritToasts();
        // mount barrels + aiming aids (#140): every selected/auto mount shows its bearing;
        // manual mounts get a range arc and an aim-line crosshair
        if (defeatT < 0 && !weapons.isEmpty()) {
            float bx = player.x + Player.WIDTH / 2f;
            float by = player.y + Player.HEIGHT / 2f;
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
            for (Weapon aw : weapons) {
                if (!aw.selected && !aw.auto) continue;
                float barrelRot = player.rotation + (aw.type.turretArc > 0 ? aw.turret : 0f);
                float fxv = -MathUtils.sinDeg(barrelRot);
                float fyv = MathUtils.cosDeg(barrelRot);
                float rxv = MathUtils.cosDeg(barrelRot);
                float ryv = MathUtils.sinDeg(barrelRot);
                shapeRenderer.setColor(0.6f, 0.65f, 0.7f, 1f);
                int barrels = aw.type == Weapon.Type.CANNON_155 ? state.cannon155Tier() : 1;
                for (int bIdx = 0; bIdx < barrels; bIdx++) {
                    float lat = (bIdx - (barrels - 1) / 2f) * 7f;
                    float recoil = aw.type == Weapon.Type.CANNON_155 ? aw.barrelRecoil[bIdx] * 7f : 0f;
                    shapeRenderer.line(bx + rxv * lat + fxv * (14f - recoil),
                        by + ryv * lat + fyv * (14f - recoil),
                        bx + rxv * lat + fxv * (30f - recoil),
                        by + ryv * lat + fyv * (30f - recoil));
                }
                if (!aw.selected) continue; // aids are for the manual group only
                float range = weaponRange(aw.type);
                shapeRenderer.setColor(0.25f, 0.65f, 0.75f, 1f);
                float arcHalf = aw.type.turretArc > 0 ? aw.type.turretArc : 5f;
                float a0 = player.rotation - arcHalf + 90f; // shape arc() is x-axis based
                // reach arc at max range across the slew limits
                shapeRenderer.arc(bx, by, range, a0, arcHalf * 2f, Math.max(8, (int) (arcHalf / 6f)));
                if (aw.type.turretArc > 0 && aw.type.turretArc < 180f) {
                    // arc limit spokes
                    for (int sgn = -1; sgn <= 1; sgn += 2) {
                        float lr = player.rotation + sgn * arcHalf;
                        shapeRenderer.line(bx, by,
                            bx - MathUtils.sinDeg(lr) * range, by + MathUtils.cosDeg(lr) * range);
                    }
                }
                // aim line out to reach, capped with a crosshair where the round lands
                float ex = bx + fxv * range;
                float ey = by + fyv * range;
                shapeRenderer.setColor(0.35f, 0.8f, 0.9f, 1f);
                shapeRenderer.line(bx + fxv * 34f, by + fyv * 34f, ex, ey);
                shapeRenderer.line(ex - 6, ey, ex + 6, ey);
                shapeRenderer.line(ex, ey - 6, ex, ey + 6);
            }
            shapeRenderer.end();
        }
        if (defeatT < 0) {
            effects.renderShip(shapeRenderer, player);
            float hullFrac = state.hull / state.maxHull;
            if (hullFrac < 0.95f) {
                shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
                transform.setToTranslation(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f, 0)
                    .rotate(0, 0, 1, player.rotation);
                shapeRenderer.setTransformMatrix(transform);
                java.util.Random r = new java.util.Random(1337);
                int marks = Math.min(6, 1 + (int) ((1f - hullFrac) * 6f));
                shapeRenderer.setColor(0.45f, 0.1f, 0.08f, 1f);
                for (int m = 0; m < marks; m++) {
                    float mx = -18f + r.nextFloat() * 36f;
                    float my = -10f + r.nextFloat() * 24f;
                    float ang = r.nextFloat() * 360f;
                    float len = 3f + r.nextFloat() * 4f;
                    shapeRenderer.line(mx, my, mx + MathUtils.cosDeg(ang) * len,
                        my + MathUtils.sinDeg(ang) * len);
                }
                shapeRenderer.setTransformMatrix(transform.idt());
                shapeRenderer.end();
            }
            if (shieldFlash > 0) {
                shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
                float a = shieldFlash / 0.4f;
                shapeRenderer.setColor(0.4f * a, 0.8f * a, a, 1f);
                shapeRenderer.circle(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f,
                    88f + 8f * (1f - a), 32);
                shapeRenderer.end();
            }
        }

        // projectiles (filled mode, per-projectile transform); size scales with caliber
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (Projectile p : projectiles) {
            transform.setToTranslation(p.x, p.y, 0).rotate(0, 0, 1, p.rotation);
            shapeRenderer.setTransformMatrix(transform);
            if (p.rocket) {
                shapeRenderer.setColor(0.85f, 0.85f, 0.9f, 1f);
                shapeRenderer.rect(-2, -8, 4, 16);
                shapeRenderer.setColor(1f, 0.55f, 0.15f, 1f);
                shapeRenderer.rect(-1.5f, -13, 3, 5); // flame
            } else {
                float s = 0.6f + p.damage * 0.05f;
                shapeRenderer.setColor(Color.GREEN);
                shapeRenderer.rect(-2 * s, -7 * s, 4 * s, 14 * s);
                shapeRenderer.circle(0, 7 * s, 2 * s, 8);
                shapeRenderer.circle(0, -7 * s, 2 * s, 8);
            }
        }
        shapeRenderer.setTransformMatrix(transform.idt());
        shapeRenderer.end();

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
        if (pause.isOpen()
                && pause.render(shapeRenderer, batch, font, hudMatrix, viewport, objectiveDone)) {
            game.setScreen(new OverworldScreen(game, state));
        }
    }

    /** Pool readout for the weapon cards: shared ammo pools, or the energy budget. */
    private String ammoLabel(Weapon.Type t) {
        switch (t.ammoKind) {
            case LIGHT: return "L " + state.ammoLight;
            case HEAVY: return "H " + state.ammoHeavy;
            case ROCKET: return "R " + state.ammoRockets;
            default: return "E " + Math.round(state.weaponEnergy / state.maxWeaponEnergy * 100) + "%";
        }
    }

    private void drawHud() {
        shapeRenderer.setProjectionMatrix(hudMatrix);
        effects.renderThrottleHud(shapeRenderer, player);

        // radar: arena at a glance
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        radar.frame(shapeRenderer);
        if (defeatT < 0) {
            shapeRenderer.setColor(Color.GREEN);
            radar.dot(shapeRenderer, player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f, 2.5f);
        }
        shapeRenderer.setColor(0.95f, 0.25f, 0.2f, 1f);
        for (Enemy e : enemies) {
            radar.dot(shapeRenderer, e.centerX(), e.centerY(), 2f);
        }
        shapeRenderer.setColor(0.35f, 0.85f, 0.5f, 1f);
        for (Fighter f : fighters) {
            radar.dot(shapeRenderer, f.centerX(), f.centerY(), 1.1f);
        }
        shapeRenderer.setColor(1f, 0.6f, 0.15f, 1f);
        for (Projectile p : projectiles) {
            if (p.rocket) radar.dot(shapeRenderer, p.x, p.y, 1.3f);
        }
        shapeRenderer.end();
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int s = 0; s < SQUADRON_COUNT; s++) {
            if (squadronAlive(s) == 0) continue;
            com.badlogic.gdx.math.Rectangle tr = squadronTabRect(s);
            if (s == selectedSquadron) shapeRenderer.setColor(Color.WHITE);
            else shapeRenderer.setColor(0.3f, 0.42f, 0.46f, 1f);
            shapeRenderer.rect(tr.x, tr.y, tr.width, tr.height);
        }
        radar.border(shapeRenderer);
        com.badlogic.gdx.graphics.OrthographicCamera rcam =
            (com.badlogic.gdx.graphics.OrthographicCamera) viewport.getCamera();
        radar.viewRect(shapeRenderer, rcam.position.x, rcam.position.y,
            viewport.getWorldWidth() * rcam.zoom, viewport.getWorldHeight() * rcam.zoom);
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
            String ammoText = ammoLabel(w.type);
            font.setColor(ammoText.endsWith(" 0") ? Color.RED : Color.GRAY);
            font.draw(batch, ammoText, x + 4, 26);
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
        font.setColor(enemies.size > 0 ? Color.RED : Color.GRAY);
        String objLine = "Hostiles: " + enemies.size;
        if (objective == Obj.SURVIVE && !objectiveDone) objLine = "SURVIVE " + (int) Math.ceil(surviveT) + "s";
        else if (objective == Obj.INTERCEPT && !objectiveDone) objLine = "INTERCEPT THE RUNNER";
        font.draw(batch, objLine, 10, HUD_H - 35);
        font.setColor(controlled < 0 ? Color.GRAY : Color.ORANGE);
        font.draw(batch, "Helm: " + (controlled < 0 ? "MOTHERSHIP" : "FIGHTER " + (controlled + 1)),
            10, HUD_H - 60);
        // squadron tabs (#134): callsign + fraction health; wiped squadrons drop off
        Fonts.scale(font, 0.95f);
        if (!deckOpen) {
            font.setColor(Color.GRAY);
            StringBuilder pw = new StringBuilder("PWR");
            for (int i = 0; i < GameState.POWER_SYSTEMS.length; i++) {
                pw.append(' ').append(GameState.POWER_SYSTEMS[i].charAt(0)).append(state.power[i]);
            }
            pw.append("   [V] deck");
            font.draw(batch, pw.toString(), 10, HUD_H - 226);
        }
        for (int s = 0; s < SQUADRON_COUNT; s++) {
            int alive = squadronAlive(s);
            if (alive == 0) continue;
            com.badlogic.gdx.math.Rectangle tr = squadronTabRect(s);
            String mode = squadrons[s] == null ? "" : squadrons[s].mode == 2 ? " ATK"
                : squadrons[s].mode == 1 ? " MOV" : " ESC";
            font.setColor(s == selectedSquadron ? Color.WHITE : Color.GRAY);
            font.draw(batch, state.squadronNames[s] + " " + alive + "/" + FIGHTERS_PER_SQUADRON + mode,
                tr.x + 5, tr.y + 29);
            CrewMember lead = state.squadronLeader(s);
            font.setColor(lead != null ? new Color(0.4f, 0.8f, 0.9f, 1f) : Color.DARK_GRAY);
            font.draw(batch, lead != null ? "LEAD: " + lead.name : "no leader", tr.x + 5, tr.y + 13);
        }
        Fonts.scale(font, 1.4f);
        if (objectiveDone) {
            font.setColor(Color.GREEN);
            String msg = "OBJECTIVE COMPLETE — ESC to return";
            GlyphLayout gl = new GlyphLayout(font, msg);
            font.draw(batch, msg, (HUD_W - gl.width) / 2f, HUD_H / 2f);
        }
        font.setColor(Color.WHITE);
        font.draw(batch, (player.throttle * 10) + "%",
            HUD_W - SpaceEffects.THROTTLE_HUD_MARGIN - SpaceEffects.THROTTLE_BLOCK_W,
            SpaceEffects.THROTTLE_HUD_MARGIN
                + Player.THROTTLE_STEPS * (SpaceEffects.THROTTLE_BLOCK_H + SpaceEffects.THROTTLE_BLOCK_GAP) + 20);
        // #147: one quiet line at the bottom, fading in through the long build-up
        if (state.map.getCurrentNode().stormy && stormTimer < STORM_BUILDUP && defeatT < 0) {
            float ramp = 1f - stormTimer / STORM_BUILDUP;
            font.setColor(1f, 0.65f, 0.25f, 0.25f + 0.75f * ramp);
            GlyphLayout sr = new GlyphLayout(font, "WARNING: Solar activity detected");
            font.draw(batch, sr, (HUD_W - sr.width) / 2f, 46);
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
        Dev.drawIndicator(batch, font, HUD_W, HUD_H);
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
        if (Gdx.input.isKeyJustPressed(Input.Keys.Z)) {
            tacticalZoom = tacticalZoom > 1.7f ? 1f : tacticalZoom > 1.2f ? 1.9f : 1.4f;
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
            if (!tabHit) {
                Vector2 at = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
                handleCommandClick(at.x, at.y);
            }
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT)) {
            if (selectedSquadron != -1) {
                selectedSquadron = -1; // stand down the selection
            } else {
                // carrier helm: waypoint for the mothership
                Vector2 at = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
                effects.setAutopilotTarget(at.x, at.y);
                game.sfx.playPing();
            }
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

    private void updateProjectiles(float delta) {
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
                    showHudToast("FRIENDLY HIT — CHECK FIRE");
                    if (hitF.hp <= 0) {
                        d.friends.removeValue(hitF, true);
                        spawnDeathEffect(hitF.centerX(), hitF.centerY(), hitF.body.vx, hitF.body.vy);
                        int fi = fighters.indexOf(hitF, true);
                        if (fi != -1) {
                            if (controlled == fi) controlled = -1;
                            else if (controlled > fi) controlled--;
                            fighters.removeIndex(fi);
                        }
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
        showHudToast(msg);
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
    }

    private void updateObjective(float delta) {
        if (objective == Obj.SURVIVE && !objectiveDone) {
            surviveT -= delta;
            waveT -= delta;
            if (waveT <= 0 && enemies.size < 6) {
                waveT = 12f;
                Enemy e = new Enemy(MathUtils.randomBoolean() ? 60f : ARENA_WIDTH - 60f,
                    MathUtils.random(100f, ARENA_HEIGHT - 100f), Difficulty.enemyHp(state.sector));
                e.maxHp = e.hp;
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

    /** Dead ships leave salvageable wreck sections; fly close to tractor them in (#105). */
    private void updateWrecks() {
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        for (int i = wrecks.size - 1; i >= 0; i--) {
            float[] wk = wrecks.get(i);
            wk[0] += wk[2] * Gdx.graphics.getDeltaTime();
            wk[1] += wk[3] * Gdx.graphics.getDeltaTime();
            wk[4] += wk[5] * Gdx.graphics.getDeltaTime();
            if (defeatT < 0 && Vector2.dst(cx, cy, wk[0], wk[1]) < 70f) {
                int paid = Math.round(wk[6] * Difficulty.rewardFactor(state.sector));
                state.credits += paid;
                wrecksCollected++;
                showHudToast("+" + paid + " SALVAGE");
                addSparks(wk[0], wk[1], 0, 0, 6);
                wrecks.removeIndex(i);
                game.sfx.playCatch();
            }
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

        // gunnery (#96): fire when roughly on target and in range
        float aimErr = (((MathUtils.atan2(-dx, dy) * MathUtils.radiansToDegrees)
            - e.body.rotation) % 360f + 540f) % 360f - 180f;
        if (e.gun.ready() && dist < 520f && Math.abs(aimErr) < 9f && MathUtils.random() < 0.85f) {
            e.gun.fire();
            e.gun.cooldown = e.gun.type.reload * MathUtils.random(1.6f, 2.4f); // slower than the player
            float jitter = MathUtils.random(-4f, 4f);
            float fx = -MathUtils.sinDeg(e.body.rotation);
            float fy = MathUtils.cosDeg(e.body.rotation);
            projectiles.add(new Projectile(cx + fx * 20f, cy + fy * 20f,
                e.body.rotation + jitter, e.body,
                e.gun.type.speed, e.gun.type.damage, 0f, 0f, false));
            game.sfx.playCannon(0);
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
            if (latched(f)) continue;
            for (Enemy e : enemies) {
                if (e.boss || latched(e)) continue;
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
                    if (!o.boss && !latched(o)
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

    /** One hidden round roll: lead pilot skill, craft condition and weaponry decide. */
    private void resolveDogfightRound(Dogfight d) {
        float fp = 0f;
        for (Fighter f : d.friends) {
            fp += 1f + f.hp / FIGHTER_HP + f.rockets * 0.25f;
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
        fp += leadBonus * d.friends.size * 0.4f;
        float ep = 0f;
        for (Enemy e : d.foes) {
            ep += 1f + e.hp / e.maxHp + 0.3f * (Difficulty.factor(state.sector) - 1f);
        }
        if (MathUtils.random() < fp / (fp + ep)) {
            // a raider goes down
            Enemy dead = d.foes.random();
            d.foes.removeValue(dead, true);
            int idx = enemies.indexOf(dead, true);
            if (idx != -1) killEnemy(idx);
        } else {
            // we lose a craft — the leader's is always the last hull flying (#142)
            Fighter loss = null;
            for (Fighter f : d.friends) {
                boolean leaderCraft = fighterSlot(f) == 0 && state.squadronLeader(f.squadron) != null;
                if (!leaderCraft) {
                    loss = f;
                    break;
                }
            }
            if (loss == null) loss = d.friends.first(); // only lead craft left
            d.friends.removeValue(loss, true);
            spawnDeathEffect(loss.centerX(), loss.centerY(), loss.body.vx, loss.body.vy);
            int idx = fighters.indexOf(loss, true);
            if (idx != -1) {
                if (controlled == idx) controlled = -1;
                else if (controlled > idx) controlled--;
                fighters.removeIndex(idx);
            }
            // leadership persists to the final resolution — then goes down with the ship
            CrewMember lead = state.squadronLeader(loss.squadron);
            if (lead != null && squadronAlive(loss.squadron) == 0) {
                lead.hp = 0f;
                showHudToast(lead.name + " LOST WITH " + state.squadronNames[loss.squadron]);
            }
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
                sq.mode = 2;
                sq.target = e;
                game.sfx.playPing();
                return;
            }
        }
        if (Vector2.dst(x, y, player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f)
                < CARRIER_HIT_RADIUS + 16f) {
            sq.mode = 0; // return to the carrier: escort + repair
            game.sfx.playPing();
            return;
        }
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
            if (latched(f)) continue; // the dogfight owns this craft (#141)
            if (i != controlled) flyFighter(f, delta);
            f.body.updatePosition(delta);
            bounceOffWalls(f.body);
            effects.spawnExhaust(f.body, delta);
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
                projectiles.add(new Projectile(cx + fxv * 20f, cy + fyv * 20f,
                    f.body.rotation + MathUtils.random(-3f, 3f), f.body,
                    f.gun.type.speed, f.gun.type.damage, 0f, 0f, false));
            }
        } else {
            float tx = sq.mode == 1 ? sq.ox : player.x + Player.WIDTH / 2f;
            float ty = sq.mode == 1 ? sq.oy : player.y + Player.HEIGHT / 2f;
            // small per-fighter spread so the squadron doesn't stack
            int slot = fighterSlot(f);
            tx += MathUtils.cosDeg(slot * 120f) * 30f;
            ty += MathUtils.sinDeg(slot * 120f) * 30f;
            float dx = tx - cx;
            float dy = ty - cy;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < 24f) {
                wantThrottle = 0;
                // hold the move-order heading instead of drifting around (#132)
                gx = -MathUtils.sinDeg(sq.mode == 1 ? sq.orderDir : f.body.rotation);
                gy = MathUtils.cosDeg(sq.mode == 1 ? sq.orderDir : f.body.rotation);
                if (sq.mode == 0) {
                    f.hp = Math.min(FIGHTER_HP, f.hp + 2.5f * delta); // deck crews patch them up
                    f.rockets = 2; // and rearm the pods
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

    private int fighterSlot(Fighter f) {
        int slot = 0;
        for (Fighter o : fighters) {
            if (o == f) break;
            if (o.squadron == f.squadron) slot++;
        }
        return slot;
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
    private void updateCupolas(float delta) {
        int cupolas = state.mothership.countMounts(Mothership.MOUNT_MG46);
        if (cupolas == 0 || defeatT >= 0 || enemies.isEmpty()) return;
        cupolaCd -= delta;
        if (cupolaCd > 0) return;
        float cx = player.x + Player.WIDTH / 2f;
        float cy = player.y + Player.HEIGHT / 2f;
        Enemy target = null;
        float best = 380f;
        for (Enemy e : enemies) {
            float d = Vector2.dst(cx, cy, e.centerX(), e.centerY());
            if (d < best) {
                best = d;
                target = e;
            }
        }
        if (target == null || !state.spendAmmo(Weapon.Type.LIGHT_CANNON, 1)) return;
        cupolaCd = 60f / (1200f * cupolas); // combined rate of fire across mounted cupolas
        float aim = MathUtils.atan2(-(target.centerX() - cx), target.centerY() - cy)
            * MathUtils.radiansToDegrees + MathUtils.random(-3.5f, 3.5f);
        projectiles.add(new Projectile(cx - MathUtils.sinDeg(aim) * 22f,
            cy + MathUtils.cosDeg(aim) * 22f, aim, player, 560f, 1.5f, 0f, 0f, false));
        if (MathUtils.randomBoolean(0.2f)) game.sfx.playCannon(0);
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

    /** Bearing from the ship to the cursor, in world degrees. */
    private float cursorBearing(Player body) {
        Vector2 cursor = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
        float cdx = cursor.x - (body.x + Player.WIDTH / 2f);
        float cdy = cursor.y - (body.y + Player.HEIGHT / 2f);
        return MathUtils.atan2(-cdx, cdy) * MathUtils.radiansToDegrees;
    }

    /** Effective reach of a mount, for auto acquisition and the range indicators (#140). */
    private static float weaponRange(Weapon.Type t) {
        return t.speed > 0 ? t.speed * 0.85f : 420f;
    }

    /** Slews the mount toward a desired bearing within its arc; returns the barrel rotation. */
    private float slewMount(Weapon w, Player body, float desired, float delta) {
        if (w.type.turretArc <= 0) return body.rotation;
        float offset = ((desired - body.rotation) % 360f + 540f) % 360f - 180f;
        offset = MathUtils.clamp(offset, -w.type.turretArc, w.type.turretArc);
        float slew = 240f * delta; // mount turn-rate limit
        w.turret += MathUtils.clamp(offset - w.turret, -slew, slew);
        w.turret = MathUtils.clamp(w.turret, -w.type.turretArc, w.type.turretArc);
        return body.rotation + w.turret;
    }

    /** AUTO mounts (#139): acquire the nearest hostile in range, slew, and fire when aligned. */
    private boolean autoFireMount(Weapon w, Player body, float delta) {
        float cx = body.x + Player.WIDTH / 2f;
        float cy = body.y + Player.HEIGHT / 2f;
        Enemy target = null;
        float best = weaponRange(w.type);
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
            case CANNON_155: {
                int tier = state.cannon155Tier();
                if (held && w.ready() && state.spendAmmo(w.type, tier)) {
                    w.fire();
                    w.cooldown = w.type.reload / ((1f + 0.08f * state.roomStats[4])
                        * (0.7f + 0.15f * state.power[GameState.PWR_WEAPONS]));
                    w.burstLeft = tier;
                }
                if (w.burstLeft > 0 && w.burstTimer <= 0) {
                    // ripple fire: one round per barrel, left to right
                    int barrel = tier - w.burstLeft;
                    w.burstLeft--;
                    w.burstTimer = 0.09f;
                    w.barrelRecoil[barrel] = 1f;
                    float lat = (barrel - (tier - 1) / 2f) * 7f;
                    float rxv = MathUtils.cosDeg(fireRotation);
                    float ryv = MathUtils.sinDeg(fireRotation);
                    float bx2 = nx + rxv * lat;
                    float by2 = ny + ryv * lat;
                    projectiles.add(new Projectile(bx2, by2, fireRotation, body,
                        w.type.speed, w.type.damage, 0f, 0f, false));
                    addBlast(bx2, by2, 0f, 150f, 16f);
                    addShockwave(bx2, by2, 20f);
                    addShake(0.16f);
                    game.sfx.playCannon(2);
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
        state.credits += Math.round((dead.boss ? 300 : CREDITS_PER_KILL)
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
        for (int i = 0; i < fighters.size; i++) {
            Fighter f = fighters.get(i);
            if (i == controlled) shapeRenderer.setColor(Color.WHITE);
            else if (f.squadron == 0) shapeRenderer.setColor(0.25f, 0.85f, 0.4f, 1f);
            else shapeRenderer.setColor(0.3f, 0.8f, 0.75f, 1f);
            transform.setToTranslation(f.centerX(), f.centerY(), 0)
                .rotate(0, 0, 1, f.body.rotation).scale(0.38f, 0.38f, 1f);
            shapeRenderer.setTransformMatrix(transform);
            ShipRenderer.drawB2(shapeRenderer);
            if (f.body.thrustLevel > 0.02f) {
                shapeRenderer.setColor(1f, 0.55f, 0.15f, 1f);
                ShipRenderer.drawExhaust(shapeRenderer, f.body.thrustLevel);
            }
        }
        shapeRenderer.setTransformMatrix(transform.idt());
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
        for (int i = 0; i < enemies.size; i++) {
            Enemy e = enemies.get(i);
            if (i == controlled) shapeRenderer.setColor(Color.ORANGE);
            else if (e.runner) shapeRenderer.setColor(1f, 0.75f, 0.2f, 1f);
            else shapeRenderer.setColor(0.95f, 0.25f, 0.2f, 1f);
            transform.setToTranslation(e.centerX(), e.centerY(), 0).rotate(0, 0, 1, e.body.rotation);
            if (e.boss) transform.scale(2.2f, 2.2f, 1f);
            else transform.scale(0.7f, 0.7f, 1f); // fighter-class hostiles read small vs the carrier
            shapeRenderer.setTransformMatrix(transform);
            ShipRenderer.drawB2Core(shapeRenderer);
            if (e.leftWingHp > 0) ShipRenderer.drawB2Wing(shapeRenderer, true);
            if (e.rightWingHp > 0) ShipRenderer.drawB2Wing(shapeRenderer, false);
            drawDamageMarks(e);
            if (e.body.thrustLevel > 0.02f) {
                float flick = 0.8f + 0.35f * MathUtils.random();
                shapeRenderer.setColor(1f, 0.5f, 0.12f, 1f);
                ShipRenderer.drawExhaust(shapeRenderer, e.body.thrustLevel * flick);
                shapeRenderer.setColor(1f, 0.85f, 0.4f, 1f);
                ShipRenderer.drawExhaust(shapeRenderer, e.body.thrustLevel * flick * 0.55f);
            }
        }
        // the carrier's persistent battle scars (#123)
        if (!state.damageMarks.isEmpty() && defeatT < 0) {
            shapeRenderer.setColor(0.32f, 0.22f, 0.14f, 1f);
            transform.setToTranslation(player.x + Player.WIDTH / 2f, player.y + Player.HEIGHT / 2f, 0)
                .rotate(0, 0, 1, player.rotation);
            shapeRenderer.setTransformMatrix(transform);
            for (float[] mk : state.damageMarks) {
                shapeRenderer.circle(mk[0], mk[1], mk[2], 8);
                shapeRenderer.circle(mk[0] + mk[2] * 0.4f, mk[1] - mk[2] * 0.3f, mk[2] * 0.5f, 6);
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
