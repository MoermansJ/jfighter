package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

import java.util.ArrayList;
import java.util.List;

public class GameState {
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
        Weapon.Type.LIGHT_CANNON, Weapon.Type.ROCKET));

    /** 155mm barrel count: 1 stock, up to 3 via trader barrel upgrades (#91). */
    public int cannon155Tier() {
        return 1 + Math.min(2, upgradeLevel(ShipUpgrade.M155_BARRELS));
    }
    // ship integrity: shields absorb before hull; 0 hull = run lost
    public float hull = 100f;
    public float maxHull = 100f;
    public float shield = 50f;
    public float maxShield = 50f;
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
        return 1f + 0.1f * upgradeLevel(ShipUpgrade.ENGINE_TUNING);
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
        this.map = new OverworldMap();
        this.credits = 500 + 100 * Meta.perkLevel(Meta.PERK_CREDITS);
        this.maxFuel = 100f;
        this.fuel = 8f + 2 * Meta.perkLevel(Meta.PERK_FUEL); // scarce: each node hop costs 1
        this.maxHull += 15 * Meta.perkLevel(Meta.PERK_HULL);
        this.hull = maxHull;
        // roguelite: every run starts with a fresh random crew (the map is random too;
        // the ship layout stays fixed for now)
        List<String> names = new ArrayList<>(List.of(SURNAMES));
        Skill[] skills = Skill.values();
        for (int i = 0; i < 4; i++) {
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
            boarder = new CrewMember("INTRUDER", Skill.COMBAT,
                skills[MathUtils.random(skills.length - 2)]);
            boarder.hostile = true;
            boarder.station = 1; // stands in the (stationless) cargo hold
            boarder.deckX = 90;
            boarder.deckY = 22;
        }
    }

    // dev-mode boarder (#42): a hostile figure in the cargo hold, player-controllable
    public CrewMember boarder;
    // set by combat when a hit sparks a blaze aboard; consumed by ShipDeckView (#110)
    public int pendingFireRoom = -1;

    private long stationSeq;

    /** FIFO stamp for station queues: lower = assigned earlier. */
    public long nextStationSeq() {
        return stationSeq++;
    }
}
