package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

import java.util.ArrayList;
import java.util.List;

public class GameState {
    public ShipHull shipHull = ShipHull.CARRIER;
    public Mothership mothership = Mothership.modelA();
    // optional run modifiers chosen at setup (#103)
    public final java.util.Set<String> modifiers = new java.util.HashSet<>();
    public static final String MOD_IRON = "IRON";         // no HOME repairs, +2 salvage
    public static final String MOD_DARK = "DARK";         // the fog never lifts, richer loot
    public static final String MOD_OVERLOAD = "OVERLOAD"; // +1 reactor unit, storms everywhere

    private static final String[] SURNAMES = {
        "Jones", "Riggs", "Rockefeller", "Vance", "Okafor", "Petrov", "Tanaka", "Silva",
        "Moreau", "Lindgren", "Castillo", "Adeyemi", "Novak", "Byrne", "Haddad", "Kowalski"
    };
    public OverworldMap map; // replaced when the run jumps to a new sector
    public final List<CrewMember> crew = new ArrayList<>();
    public int credits;
    public float fuel;
    public float maxFuel;
    public int sector = 1;
    // fighter weapon loadout (#98): 4 slots max, bought/sold at the trader
    public static final int LOADOUT_SLOTS = 4;
    public final List<Weapon.Type> loadout = new ArrayList<>(List.of(
        Weapon.Type.CANNON_155, Weapon.Type.AUTOCANNON_20)); // Model A's default sockets (#119/#135)

    // ammunition pools (#120): ballistic weapons burn these, bought at the trader
    public int ammoLight = 600;
    public int ammoHeavy = 24;
    public int ammoRockets = 12;
    // ship energy budget for energy weapons; recharge scales with WEAPONS power
    public float weaponEnergy = 100f;
    public float maxWeaponEnergy = 100f;

    /** Spends ballistic ammo or ship energy; returns false when dry. */
    public boolean spendAmmo(Weapon.Type t, int shots) {
        int cost = t.ammoCost * shots;
        switch (t.ammoKind) {
            case LIGHT:
                if (ammoLight < cost) return false;
                ammoLight -= cost;
                return true;
            case HEAVY:
                if (ammoHeavy < cost) return false;
                ammoHeavy -= cost;
                return true;
            case ROCKET:
                if (ammoRockets < cost) return false;
                ammoRockets -= cost;
                return true;
            default:
                if (weaponEnergy < cost) return false;
                weaponEnergy -= cost;
                return true;
        }
    }

    /** 155mm barrel count: Model A ships the full tier-3 triple mount. */
    public int cannon155Tier() {
        return 3;
    }
    // ship integrity: shields absorb before hull; 0 hull = run lost
    public float hull = 100f;
    public float maxHull = 100f;
    public float shield = 50f;
    public float maxShield = 50f;
    // fighter wing sections (#99): shear off under fire, restored by repairs
    public float leftWingHp = 15f;
    public float rightWingHp = 15f;
    // persistent battle scars (#123): carrier-local scorch marks {x, y, size}, kept until repaired
    public final List<float[]> damageMarks = new ArrayList<>();

    public void addDamageMark() {
        if (damageMarks.size() >= 40) return;
        damageMarks.add(new float[]{MathUtils.random(-28f, 28f),
            MathUtils.random(-62f, 105f), MathUtils.random(2.5f, 6.5f)});
    }

    public void repairWings() {
        leftWingHp = 15f;
        rightWingHp = 15f;
        damageMarks.clear(); // repairs scrub the scars too
    }
    // run stats for the end-of-run summary
    public int nodesVisited;
    public int instancesCompleted;
    public int cargoDelivered;
    public int hostilesDestroyed;
    public int sectorsCleared;

    /** Jump gate at END: a fresh sector map, everything else carries over. */
    public void advanceSector() {
        sector++;
        sectorsCleared++;
        map = new OverworldMap();
        if (modifiers.contains(MOD_OVERLOAD)) {
            for (Node n : map.allNodes()) n.stormy = true;
        }
    }

    // ship upgrades (#30): owned levels; effects read where the systems use their tuning
    public final java.util.Map<ShipUpgrade, Integer> upgrades =
        new java.util.EnumMap<>(ShipUpgrade.class);
    // per-compartment tier (#34): 1 = stock; scales the room's station effect
    public final int[] roomTier = {1, 1, 1, 1, 1, 1, 1, 1};
    // manned-station snapshot taken when entering an instance (#7: crew stats matter)
    public final int[] roomStats = new int[8];
    // reactor power (#90): a fixed pool of units invested on demand into ship systems
    public static final String[] POWER_SYSTEMS = {"LIFE SUP", "SHIELDS", "ENGINES", "WEAPONS", "MEDBAY"};
    public static final int[] POWER_CAP = {3, 3, 3, 3, 2};
    public static final int PWR_LIFE = 0;
    public static final int PWR_SHIELDS = 1;
    public static final int PWR_ENGINES = 2;
    public static final int PWR_WEAPONS = 3;
    public static final int PWR_MEDBAY = 4;
    public int reactorUnits = 8;
    public final int[] power = {2, 2, 2, 1, 1};

    public int unallocatedPower() {
        int used = 0;
        for (int p : power) used += p;
        return reactorUnits - used;
    }

    /** Invest (+1) or divert (-1) a unit; respects the pool and per-system caps. */
    public void adjustPower(int system, int delta) {
        if (delta > 0 && (unallocatedPower() <= 0 || power[system] >= POWER_CAP[system])) return;
        if (delta < 0 && power[system] <= 0) return;
        power[system] += delta;
    }
    // combat damage leaks into the deck (#12): rooms below 1.0 bleed oxygen until repaired
    public final float[] roomIntegrity = {1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f};

    public int upgradeLevel(ShipUpgrade u) {
        return upgrades.getOrDefault(u, 0);
    }

    public void buyUpgrade(ShipUpgrade u) {
        upgrades.merge(u, 1, Integer::sum);
        if (u == ShipUpgrade.HULL_PLATING) {
            maxHull += 25;
            hull += 25;
        } else if (u == ShipUpgrade.SHIELD_CAPACITY) {
            maxShield += 20;
            shield += 20;
        }
    }

    public float shieldRechargeBonus() {
        return 3f * upgradeLevel(ShipUpgrade.SHIELD_RECHARGE);
    }

    public float netLengthMult() {
        return 1f + 0.25f * upgradeLevel(ShipUpgrade.NET_LENGTH);
    }

    public int pincerCapacityBonus() {
        return upgradeLevel(ShipUpgrade.PINCER_HOLD);
    }

    public float thrustMult() {
        return shipHull.thrustMult * (1f + 0.1f * upgradeLevel(ShipUpgrade.ENGINE_TUNING));
    }

    /** Applies the chosen run modifiers' start-of-run effects (#103). */
    public void applyModifiers() {
        if (modifiers.contains(MOD_OVERLOAD)) {
            reactorUnits = 9;
            for (Node n : map.allNodes()) n.stormy = true;
        }
    }

    /** XP payout to all living crew (#101). */
    public void awardCrewXp(float amount) {
        for (CrewMember c : crew) {
            if (!c.isDead()) c.gainXp(amount);
        }
    }

    /** A new random deckhand, same generation rules as the starting crew. */
    public void hireCrew() {
        String surname = SURNAMES[MathUtils.random(SURNAMES.length - 1)];
        String name = (char) ('A' + MathUtils.random(25)) + ". " + surname;
        Skill[] skills = Skill.values();
        Skill primary = skills[MathUtils.random(skills.length - 1)];
        Skill secondary;
        do {
            secondary = skills[MathUtils.random(skills.length - 1)];
        } while (secondary == primary);
        crew.add(new CrewMember(name, primary, secondary));
    }

    public int crewLost() {
        int lost = 0;
        for (CrewMember c : crew) {
            if (c.isDead()) lost++;
        }
        return lost;
    }
    // oxygen simulation — compartments: rooms 0..7, corridor (8), airlock chambers (9, 10)
    public final float[] oxygen = {1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f};
    // doors 0..7 = each room's corridor door, then inner/outer per airlock (8,9 and 10,11).
    // true = player holds it open; false = automatic (opens for passing crew, closes behind)
    public final boolean[] doorHeldOpen = new boolean[12];

    public GameState() {
        this(ShipHull.CARRIER);
    }

    public GameState(ShipHull hullType) {
        this.shipHull = hullType;
        this.mothership = Mothership.forHull(hullType);
        int callsign = MathUtils.random(CALLSIGNS.length - 1);
        squadronNames[0] = CALLSIGNS[callsign];
        squadronNames[1] = CALLSIGNS[(callsign + 1 + MathUtils.random(CALLSIGNS.length - 3)) % CALLSIGNS.length];
        // default leaders: the two best scrappers fly lead until reassigned
        int bestA = -1;
        int bestB = -1;
        for (int i = 0; i < crew.size(); i++) {
            int cb = crew.get(i).bonusFor(Skill.COMBAT);
            if (bestA == -1 || cb > crew.get(bestA).bonusFor(Skill.COMBAT)) {
                bestB = bestA;
                bestA = i;
            } else if (bestB == -1 || cb > crew.get(bestB).bonusFor(Skill.COMBAT)) {
                bestB = i;
            }
        }
        squadronLeaders[0] = bestA;
        squadronLeaders[1] = bestB;
        this.map = new OverworldMap();
        this.credits = 500 + 100 * Meta.perkLevel(Meta.PERK_CREDITS);
        this.maxFuel = 100f;
        this.fuel = 8f + 2 * Meta.perkLevel(Meta.PERK_FUEL); // scarce: each node hop costs 1
        this.maxHull = hullType.maxHull + 15 * Meta.perkLevel(Meta.PERK_HULL);
        this.hull = maxHull;
        // roguelite: every run starts with a fresh random crew (the map is random too;
        // the ship layout stays fixed for now)
        List<String> names = new ArrayList<>(List.of(SURNAMES));
        Skill[] skills = Skill.values();
        for (int i = 0; i < hullType.crewCount; i++) {
            String surname = names.remove(MathUtils.random(names.size() - 1));
            String name = (char) ('A' + MathUtils.random(25)) + ". " + surname;
            Skill primary = skills[MathUtils.random(skills.length - 1)];
            Skill secondary;
            do {
                secondary = skills[MathUtils.random(skills.length - 1)];
            } while (secondary == primary);
            crew.add(new CrewMember(name, primary, secondary));
        }
        if (Dev.MODE) {
            CrewMember intruder = new CrewMember("INTRUDER", Skill.COMBAT,
                skills[MathUtils.random(skills.length - 2)]);
            intruder.hostile = true;
            intruder.station = 1; // stands in the (stationless) cargo hold
            intruder.deckX = 90;
            intruder.deckY = 22;
            boarders.add(intruder);
        }
    }

    // hostile boarders aboard the carrier (#42 dev intruder, #97 boarding events)
    public final List<CrewMember> boarders = new ArrayList<>();

    /** Legacy accessor: the first living boarder, or null. */
    public CrewMember boarder() {
        for (CrewMember b : boarders) {
            if (!b.isDead()) return b;
        }
        return boarders.isEmpty() ? null : boarders.get(0);
    }

    /** A boarding party forces its way in through an airlock (#97). */
    public void spawnBoarders(int count) {
        Skill[] skills = Skill.values();
        for (int i = 0; i < count; i++) {
            CrewMember b = new CrewMember("BOARDER", Skill.COMBAT,
                skills[MathUtils.random(skills.length - 2)]);
            b.hostile = true;
            b.station = MathUtils.random(7); // they head for a room to wreck
            b.deckX = 388 + MathUtils.random(-6f, 6f); // airlock A chamber
            b.deckY = 8 + i * 6;
            boarders.add(b);
        }
    }
    // set by combat when a hit sparks a blaze aboard; consumed by ShipDeckView (#110)
    public int pendingFireRoom = -1;
    // station condition per room (#127): 1 = intact, 0 = destroyed (rebuilt by crew before manning)
    public final float[] stationHealth = {1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f};
    // squadron callsigns (#134): sci-fi NATO flavour, rolled once per run
    private static final String[] CALLSIGNS = {
        "ALFA-9", "BRAVAR", "CHARON", "DELTIC", "ECHO-V", "FOXTAR", "GOLIAD", "HELIX",
        "INDRA", "JULETT", "KILO-7", "LIMBUS", "MIRAGE", "NOVAK", "OSCURA", "QUASAR"};
    public final String[] squadronNames = new String[2];
    // squadron leaders (#142): crew indices flying lead, -1 = unassigned
    public final int[] squadronLeaders = {-1, -1};

    /** The living crew member leading this squadron, or null. */
    public CrewMember squadronLeader(int squadron) {
        int idx = squadronLeaders[squadron];
        if (idx < 0 || idx >= crew.size()) return null;
        CrewMember c = crew.get(idx);
        return c.isDead() ? null : c;
    }

    private long stationSeq;

    /** FIFO stamp for station queues: lower = assigned earlier. */
    public long nextStationSeq() {
        return stationSeq++;
    }
}
