package be.jfighter;

import java.util.List;

public class Node {
    public enum Type { HOME, COMBAT, TRADER, LOOT }

    public final int id;
    public final float x;
    public final float y;
    public final Type type;
    public final List<Integer> connections;
    public boolean visited;   // the player has been here: its type is known for the rest of the run
    public boolean completed; // the instance here is resolved: it cannot be initiated again (traders never complete)
    public boolean stormy;    // near a star: instances here get solar radiation events

    public Node(int id, float x, float y, Type type, List<Integer> connections) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.type = type;
        this.connections = connections;
    }
}
