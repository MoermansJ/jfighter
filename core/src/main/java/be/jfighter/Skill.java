package be.jfighter;

/** Crew skills; each stationed room is influenced by exactly one skill. */
public enum Skill {
    HELM,        // bridge
    GUNNERY,     // weapons room
    ENGINEERING, // engine room
    MEDICINE,    // medical bay
    SYSTEMS,     // life support
    LOGISTICS,   // encounters + (former) cargo hold
    FLIGHT_OPS,  // hangar bay
    COMBAT       // hand-to-hand fighting on the deck
}
