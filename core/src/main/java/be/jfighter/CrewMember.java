package be.jfighter;

/**
 * A carrier crew member with a primary skill (+2) and a different secondary
 * skill (+1). Skills boost the stat of a room whose station they man.
 * Station is an index into ShipDeckView.ROOM_NAMES, -1 = unassigned;
 * assignedAt orders the FIFO queue for a room's single station.
 */
public class CrewMember {
    public static final float MAX_HP = 100f;
    public static final int PRIMARY_BONUS = 2;
    public static final int SECONDARY_BONUS = 1;

    public final String name;
    public final Skill primary;
    public final Skill secondary;
    public int station = -1;
    public long assignedAt;
    public float hp = MAX_HP;
    // position on the carrier deck (ShipDeckView deck coordinates); -1 = not placed yet
    public float deckX = -1;
    public float deckY = -1;

    public CrewMember(String name, Skill primary, Skill secondary) {
        this.name = name;
        this.primary = primary;
        this.secondary = secondary;
    }

    /** Skill bonus this crew member contributes to a station of the given skill. */
    public int bonusFor(Skill skill) {
        if (skill == primary) return PRIMARY_BONUS;
        if (skill == secondary) return SECONDARY_BONUS;
        return 0;
    }

    public boolean isDead() {
        return hp <= 0;
    }

    public char initial() {
        return name.charAt(0);
    }
}
