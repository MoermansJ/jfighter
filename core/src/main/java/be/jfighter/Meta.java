package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

/** Persistent meta-progression: salvage earned across runs buys permanent starting perks. */
public final class Meta {
    public static final String PERK_FUEL = "perkFuel";       // +2 starting fuel per level
    public static final String PERK_CREDITS = "perkCredits"; // +100 starting credits per level
    public static final String PERK_HULL = "perkHull";       // +15 max hull per level
    public static final int PERK_COST = 3;

    private Meta() {
    }

    private static Preferences prefs() {
        return Gdx.app.getPreferences("jfighter");
    }

    public static int salvage() {
        return prefs().getInteger("salvage", 0);
    }

    public static void addSalvage(int n) {
        prefs().putInteger("salvage", salvage() + n).flush();
    }

    public static int perkLevel(String key) {
        return prefs().getInteger(key, 0);
    }

    /** Returns true when the perk was bought (enough salvage). */
    public static boolean buyPerk(String key) {
        if (salvage() < PERK_COST) return false;
        Preferences p = prefs();
        p.putInteger("salvage", salvage() - PERK_COST);
        p.putInteger(key, perkLevel(key) + 1);
        p.flush();
        return true;
    }
}
