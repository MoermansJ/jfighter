package be.jfighter;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class OverworldMap {
    private final Map<Integer, Node> nodes = new HashMap<>();
    private int currentNodeId = 0;

    public OverworldMap() {
        // Linear progression: HOME → TRADER → COMBAT
        add(new Node(0, 120f, 240f, Node.Type.HOME,   Arrays.asList(1)));
        add(new Node(1, 320f, 240f, Node.Type.TRADER, Arrays.asList(0, 2)));
        add(new Node(2, 520f, 240f, Node.Type.COMBAT, Arrays.asList(1)));
    }

    private void add(Node node) {
        nodes.put(node.id, node);
    }

    public Node getNode(int id) {
        return nodes.get(id);
    }

    public Node getCurrentNode() {
        return nodes.get(currentNodeId);
    }

    public void setCurrentNode(int id) {
        currentNodeId = id;
    }

    public boolean isReachable(int id) {
        if (id == currentNodeId) return true;
        return getCurrentNode().connections.contains(id);
    }

    public Iterable<Node> allNodes() {
        return nodes.values();
    }
}
