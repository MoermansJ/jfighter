package be.jfighter;

import com.badlogic.gdx.math.MathUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Procedurally generated sector map: nodes are laid out in jittered columns from
 * HOME (node 0, left) to the final node (right). Every column node gets at least
 * one edge forward and one back, so a path from node 0 to the last node always
 * exists by construction. Decals are cosmetic scan rings / markers / hazards,
 * generated once so the map looks the same every time it is shown.
 */
public class OverworldMap {
    public static class Decal {
        public enum Kind { RING, CROSS, HAZARD }

        public final Kind kind;
        public final float x, y, size;

        Decal(Kind kind, float x, float y, float size) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }

    // map area within the bottom section of the overworld screen
    public static final float AREA_LEFT = 90f;
    public static final float AREA_RIGHT = 880f;
    public static final float AREA_BOTTOM = 45f;
    public static final float AREA_TOP = 225f;
    private static final int COLUMNS = 5;
    private static final float MIN_Y_SEPARATION = 55f;

    private static final String[] SECTOR_NAMES = {
        "Draco", "Perseus", "Lyra", "Orion", "Cygnus", "Vela",
        "Auriga", "Corvus", "Hydra", "Phoenix", "Cepheus", "Antlia"
    };

    private final Map<Integer, Node> nodes = new HashMap<>();
    private final List<Decal> decals = new ArrayList<>();
    public final int lastNodeId;
    public final String sectorName;
    private int currentNodeId = 0;

    public OverworldMap() {
        sectorName = SECTOR_NAMES[MathUtils.random(SECTOR_NAMES.length - 1)] + " Sector";
        lastNodeId = generate();
        generateDecals();
    }

    private int generate() {
        List<List<Integer>> columns = new ArrayList<>();
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        Map<Integer, Set<Integer>> adj = new HashMap<>();

        // column 0: HOME
        List<Integer> first = new ArrayList<>();
        first.add(0);
        columns.add(first);
        xs.add(AREA_LEFT + MathUtils.random(0f, 20f));
        ys.add((AREA_BOTTOM + AREA_TOP) / 2f + MathUtils.random(-30f, 30f));
        adj.put(0, new HashSet<>());
        int id = 1;

        for (int c = 1; c < COLUMNS; c++) {
            boolean lastColumn = c == COLUMNS - 1;
            int count = lastColumn ? 1 : MathUtils.random(2, 3);
            float baseX = MathUtils.lerp(AREA_LEFT, AREA_RIGHT, c / (COLUMNS - 1f));
            List<Integer> column = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                float x = MathUtils.clamp(baseX + MathUtils.random(-45f, 45f), AREA_LEFT, AREA_RIGHT);
                float y = pickY(column, ys);
                xs.add(x);
                ys.add(y);
                adj.put(id, new HashSet<>());
                column.add(id++);
            }
            columns.add(column);
        }

        // edges between adjacent columns: everyone connects forward, everyone is reachable
        for (int c = 0; c < COLUMNS - 1; c++) {
            List<Integer> cur = columns.get(c);
            List<Integer> next = columns.get(c + 1);
            for (int n : cur) {
                link(adj, n, nearest(next, n, xs, ys, -1));
            }
            for (int m : next) {
                if (!hasNeighbourIn(adj.get(m), cur)) {
                    link(adj, nearest(cur, m, xs, ys, -1), m);
                }
            }
            // occasional extra route for variety
            if (next.size() > 1 && MathUtils.randomBoolean(0.4f)) {
                int n = cur.get(MathUtils.random(cur.size() - 1));
                int firstChoice = nearest(next, n, xs, ys, -1);
                int second = nearest(next, n, xs, ys, firstChoice);
                if (second != -1) link(adj, n, second);
            }
        }

        // types: HOME first, COMBAT last, at least one trader and one loot in between
        Node.Type[] types = new Node.Type[id];
        types[0] = Node.Type.HOME;
        types[id - 1] = Node.Type.COMBAT;
        List<Integer> middle = new ArrayList<>();
        for (int i = 1; i < id - 1; i++) middle.add(i);
        Collections.shuffle(middle);
        for (int k = 0; k < middle.size(); k++) {
            if (k == 0) types[middle.get(k)] = Node.Type.TRADER;
            else if (k == 1) types[middle.get(k)] = Node.Type.LOOT;
            else {
                int roll = MathUtils.random(9);
                types[middle.get(k)] = roll < 3 ? Node.Type.TRADER : roll < 6 ? Node.Type.LOOT : Node.Type.COMBAT;
            }
        }

        for (int i = 0; i < id; i++) {
            List<Integer> connections = new ArrayList<>(adj.get(i));
            Collections.sort(connections);
            nodes.put(i, new Node(i, xs.get(i), ys.get(i), types[i], connections));
        }
        return id - 1;
    }

    /** A y position keeping some distance from column mates — irregular, not evenly spaced. */
    private static float pickY(List<Integer> column, List<Float> ys) {
        for (int attempt = 0; attempt < 30; attempt++) {
            float y = MathUtils.random(AREA_BOTTOM, AREA_TOP);
            boolean clear = true;
            for (int other : column) {
                if (Math.abs(ys.get(other) - y) < MIN_Y_SEPARATION) {
                    clear = false;
                    break;
                }
            }
            if (clear) return y;
        }
        return MathUtils.random(AREA_BOTTOM, AREA_TOP);
    }

    private static int nearest(List<Integer> candidates, int from, List<Float> xs, List<Float> ys, int exclude) {
        int best = -1;
        float bestD2 = Float.MAX_VALUE;
        for (int cand : candidates) {
            if (cand == exclude) continue;
            float dx = xs.get(cand) - xs.get(from);
            float dy = ys.get(cand) - ys.get(from);
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = cand;
            }
        }
        return best;
    }

    private static void link(Map<Integer, Set<Integer>> adj, int a, int b) {
        if (a == -1 || b == -1 || a == b) return;
        adj.get(a).add(b);
        adj.get(b).add(a);
    }

    private static boolean hasNeighbourIn(Set<Integer> neighbours, List<Integer> column) {
        for (int n : column) {
            if (neighbours.contains(n)) return true;
        }
        return false;
    }

    private void generateDecals() {
        for (int i = 0; i < 3; i++) {
            decals.add(new Decal(Decal.Kind.RING,
                MathUtils.random(AREA_LEFT, AREA_RIGHT), MathUtils.random(AREA_BOTTOM, AREA_TOP),
                MathUtils.random(18f, 48f)));
        }
        for (int i = 0; i < 4; i++) {
            decals.add(new Decal(Decal.Kind.CROSS,
                MathUtils.random(AREA_LEFT, AREA_RIGHT), MathUtils.random(AREA_BOTTOM, AREA_TOP), 5f));
        }
        for (int i = 0; i < 2; i++) {
            decals.add(new Decal(Decal.Kind.HAZARD,
                MathUtils.random(AREA_LEFT, AREA_RIGHT), MathUtils.random(AREA_BOTTOM, AREA_TOP), 9f));
        }
    }

    public List<Decal> getDecals() {
        return decals;
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
