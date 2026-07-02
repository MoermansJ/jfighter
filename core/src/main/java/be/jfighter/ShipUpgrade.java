package be.jfighter;

/** Purchasable ship upgrades (repeatable levels; price scales with owned level). */
public enum ShipUpgrade {
    HULL_PLATING("Hull plating", "+25 max hull", 120),
    SHIELD_CAPACITY("Shield capacitor", "+20 max shield", 110),
    SHIELD_RECHARGE("Shield recycler", "+3/s shield recharge", 100),
    NET_LENGTH("Net spool", "+25% net length", 90),
    PINCER_HOLD("Hold extension", "+1 pincer capacity", 90),
    ENGINE_TUNING("Engine tuning", "+10% thrust", 130);

    public final String label;
    public final String effect;
    public final int basePrice;

    ShipUpgrade(String label, String effect, int basePrice) {
        this.label = label;
        this.effect = effect;
        this.basePrice = basePrice;
    }

    public int priceAt(int ownedLevel) {
        return basePrice * (ownedLevel + 1);
    }
}
