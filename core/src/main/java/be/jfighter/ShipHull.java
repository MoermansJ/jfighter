package be.jfighter;

/** Selectable hulls (#102): stat spreads around the shared deck layout. */
public enum ShipHull {
    CARRIER("VANGUARD CARRIER", "the balanced workhorse", 100f, 1.0f, 4),
    SCOUT("SWIFT SCOUT", "thin hull, hot engines, lean crew", 70f, 1.25f, 3),
    FREIGHTER("ATLAS FREIGHTER", "armoured and roomy, slow to burn", 140f, 0.85f, 5);

    public final String label;
    public final String blurb;
    public final float maxHull;
    public final float thrustMult;
    public final int crewCount;

    ShipHull(String label, String blurb, float maxHull, float thrustMult, int crewCount) {
        this.label = label;
        this.blurb = blurb;
        this.maxHull = maxHull;
        this.thrustMult = thrustMult;
        this.crewCount = crewCount;
    }
}
