package be.jfighter;

/**
 * A weapon slot: definition (Type) + runtime state (cooldown, ammo, burst queue).
 * Calibers diversify damage vs reload: bigger hits harder and cycles slower.
 */
public class Weapon {
    /** Ballistic kinds burn consumable pools; ENERGY draws on ship energy instead (#120). */
    public enum AmmoKind { LIGHT, HEAVY, ROCKET, ENERGY }

    public enum Type {
        LIGHT_CANNON("20MM", 5f, 520f, 0.15f, 60f, AmmoKind.LIGHT, 1),
        AUTOCANNON_20("20MM AC", 6f, 540f, 0.09f, 60f, AmmoKind.LIGHT, 1), // medium-socket autocannon (#135)
        MEDIUM_CANNON("57MM", 12f, 440f, 0.55f, 45f, AmmoKind.LIGHT, 2),
        CANNON_155("155MM", 34f, 700f, 6.5f, 180f, AmmoKind.HEAVY, 1), // artillery cycle: slow reload, fast shells
        ROCKET("RKT", 40f, 240f, 2.2f, 0f, AmmoKind.ROCKET, 1),
        HOMING_ROCKET("H-RKT", 35f, 220f, 3.0f, 0f, AmmoKind.ROCKET, 1),
        BEAM_LASER("BEAM", 25f, 0f, 0f, 40f, AmmoKind.ENERGY, 12),    // cost = energy per second
        BURST_LASER("PULSE", 6f, 0f, 0.8f, 40f, AmmoKind.ENERGY, 4);  // cost = energy per pulse

        public final String label;
        public final float damage;
        public final float speed;     // projectile speed (0 = hitscan)
        public final float reload;    // seconds between shots
        public final float turretArc; // half-angle the mount can slew (0 = fixed forward)
        public final AmmoKind ammoKind;
        public final int ammoCost;    // pool units per shot (energy: per second / per pulse)

        Type(String label, float damage, float speed, float reload, float turretArc,
             AmmoKind ammoKind, int ammoCost) {
            this.label = label;
            this.damage = damage;
            this.speed = speed;
            this.reload = reload;
            this.turretArc = turretArc;
            this.ammoKind = ammoKind;
            this.ammoCost = ammoCost;
        }

        public boolean isRocket() {
            return this == ROCKET || this == HOMING_ROCKET;
        }

        public boolean isLaser() {
            return this == BEAM_LASER || this == BURST_LASER;
        }
    }

    public final Type type;
    public float cooldown;
    public boolean selected; // part of the manual fire group (#139)
    public boolean auto;     // aims and fires itself at enemies in range (#139)
    public int burstLeft;  // queued pulses for BURST_LASER
    public float burstTimer;
    public float turret;   // current mount offset from the nose, clamped to the arc
    public final float[] barrelRecoil = new float[3]; // 155mm barrels slide back on firing

    public Weapon(Type type) {
        this.type = type;
    }

    public boolean ready() {
        return cooldown <= 0;
    }

    public void update(float delta) {
        if (cooldown > 0) cooldown -= delta;
        if (burstTimer > 0) burstTimer -= delta;
        for (int i = 0; i < barrelRecoil.length; i++) {
            if (barrelRecoil[i] > 0) barrelRecoil[i] = Math.max(0f, barrelRecoil[i] - 4f * delta);
        }
    }

    public void fire() {
        cooldown = type.reload;
    }
}
