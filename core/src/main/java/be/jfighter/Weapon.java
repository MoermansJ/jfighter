package be.jfighter;

/**
 * A weapon slot: definition (Type) + runtime state (cooldown, ammo, burst queue).
 * Calibers diversify damage vs reload: bigger hits harder and cycles slower.
 */
public class Weapon {
    public enum Type {
        LIGHT_CANNON("20MM", 5f, 520f, 0.15f, -1),
        MEDIUM_CANNON("57MM", 12f, 440f, 0.55f, -1),
        HEAVY_CANNON("130MM", 30f, 380f, 1.6f, -1),
        ROCKET("RKT", 40f, 240f, 2.2f, 12),
        HOMING_ROCKET("H-RKT", 35f, 220f, 3.0f, 8),
        BEAM_LASER("BEAM", 25f, 0f, 0f, -1),    // damage = dps while held on target
        BURST_LASER("PULSE", 6f, 0f, 0.8f, -1); // one trigger = 3 quick pulses

        public final String label;
        public final float damage;
        public final float speed;  // projectile speed (0 = hitscan)
        public final float reload; // seconds between shots
        public final int ammo;     // -1 = infinite

        Type(String label, float damage, float speed, float reload, int ammo) {
            this.label = label;
            this.damage = damage;
            this.speed = speed;
            this.reload = reload;
            this.ammo = ammo;
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
    public int ammo;       // -1 = infinite
    public int burstLeft;  // queued pulses for BURST_LASER
    public float burstTimer;

    public Weapon(Type type) {
        this.type = type;
        this.ammo = type.ammo;
    }

    public boolean ready() {
        return cooldown <= 0 && ammo != 0;
    }

    public void update(float delta) {
        if (cooldown > 0) cooldown -= delta;
        if (burstTimer > 0) burstTimer -= delta;
    }

    public void fire() {
        cooldown = type.reload;
        if (ammo > 0) ammo--;
    }
}
