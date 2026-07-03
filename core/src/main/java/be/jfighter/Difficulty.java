package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

/** Central difficulty curve: everything scales off the sector counter. */
public final class Difficulty {
    private Difficulty() {
    }

    /** General scaling factor: 1.0 in sector 1, +25% per sector. */
    public static float factor(int sector) {
        return 1f + 0.25f * (sector - 1);
    }

    public static int enemyCount(int sector) {
        return MathUtils.random(2, 3) + Math.min(4, sector - 1);
    }

    public static float enemyHp(int sector) {
        return 30f * factor(sector);
    }

    /** Escape gets harder deeper in: -4% per sector, floored. */
    public static float escapePenalty(int sector) {
        return Math.min(0.25f, 0.04f * (sector - 1));
    }

    public static int maxMines(int sector) {
        return Math.min(5, 1 + sector);
    }

    /** Reward side of the curve: loot and kills pay more out deep. */
    public static float rewardFactor(int sector) {
        return 1f + 0.2f * (sector - 1);
    }
}
