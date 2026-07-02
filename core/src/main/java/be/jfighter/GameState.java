package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

import java.util.ArrayList;
import java.util.List;

public class GameState {
    private static final String[] SURNAMES = {
        "Jones", "Riggs", "Rockefeller", "Vance", "Okafor", "Petrov", "Tanaka", "Silva",
        "Moreau", "Lindgren", "Castillo", "Adeyemi", "Novak", "Byrne", "Haddad", "Kowalski"
    };
    public final OverworldMap map;
    public final List<CrewMember> crew = new ArrayList<>();
    public int credits;
    public float fuel;
    public float maxFuel;
    // oxygen simulation — compartments: rooms 0..7, corridor (8), airlock chambers (9, 10)
    public final float[] oxygen = {1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f};
    // doors 0..7 = each room's corridor door, then inner/outer per airlock (8,9 and 10,11).
    // true = player holds it open; false = automatic (opens for passing crew, closes behind)
    public final boolean[] doorHeldOpen = new boolean[12];

    public GameState() {
        this.map = new OverworldMap();
        this.credits = 500;
        this.maxFuel = 100f;
        this.fuel = maxFuel;
        // roguelite: every run starts with a fresh random crew (the map is random too;
        // the ship layout stays fixed for now)
        List<String> names = new ArrayList<>(List.of(SURNAMES));
        Skill[] skills = Skill.values();
        for (int i = 0; i < 3; i++) {
            String surname = names.remove(MathUtils.random(names.size() - 1));
            String name = (char) ('A' + MathUtils.random(25)) + ". " + surname;
            Skill primary = skills[MathUtils.random(skills.length - 1)];
            Skill secondary;
            do {
                secondary = skills[MathUtils.random(skills.length - 1)];
            } while (secondary == primary);
            crew.add(new CrewMember(name, primary, secondary));
        }
    }

    private long stationSeq;

    /** FIFO stamp for station queues: lower = assigned earlier. */
    public long nextStationSeq() {
        return stationSeq++;
    }
}
