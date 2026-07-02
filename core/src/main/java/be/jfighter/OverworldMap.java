package be.jfighter;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

import java.util.ArrayDeque;
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

    /** A massive body on the map (black hole or star) that severs nearby routes. */
    public static class Obstacle {
        public enum Kind { BLACK_HOLE, STAR }

        public final Kind kind;
        public final float x, y, radius;

        Obstacle(Kind kind, float x, float y, float radius) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }

    /** A dark cloud on the map that severs the routes running through it. */
    public static class Nebula {
        public final float x, y, radius;

        Nebula(float x, float y, float radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }

    // map area within the bottom section of the overworld screen
    public static final float AREA_LEFT = 90f;
    public static final float AREA_RIGHT = 880f;
    public static final float AREA_BOTTOM = 45f;
    public static final float AREA_TOP = 225f;
    private static final int COLUMNS = 5;
    private static final float MIN_Y_SEPARATION = 55f;
    private static final float NEIGHBOUR_DIST = 230f;  // nodes this close in adjacent columns connect
    private static final float ODD_PATH_CHANCE = 0.3f; // per column-pair chance of a column-skipping link

    private static final String[] SECTOR_NAMES = {
        "Draco", "Perseus", "Lyra", "Orion", "Cygnus", "Vela",
        "Auriga", "Corvus", "Hydra", "Phoenix", "Cepheus", "Antlia"
    };

    private final Map<Integer, Node> nodes = new HashMap<>();
    private final List<Decal> decals = new ArrayList<>();
    private final List<Nebula> nebulas = new ArrayList<>();
    private final List<Obstacle> obstacles = new ArrayList<>();
    public final int lastNodeId;
    public final String sectorName;
    private int currentNodeId = 0;

    public OverworldMap() {
        sectorName = SECTOR_NAMES[MathUtils.random(SECTOR_NAMES.length - 1)] + " Sector";
        lastNodeId = generate();
        nodes.get(0).visited = true; // HOME is known from the start
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

        // same-column links: vertical neighbours connect
        for (List<Integer> column : columns) {
            List<Integer> sorted = new ArrayList<>(column);
            sorted.sort((a, b) -> Float.compare(ys.get(a), ys.get(b)));
            for (int k = 1; k < sorted.size(); k++) {
                link(adj, sorted.get(k - 1), sorted.get(k));
            }
        }

        // adjacent-column links: anything close enough connects both ways,
        // with a guaranteed forward and backward link per node
        for (int c = 0; c < COLUMNS - 1; c++) {
            List<Integer> cur = columns.get(c);
            List<Integer> next = columns.get(c + 1);
            for (int n : cur) {
                for (int m : next) {
                    float dx = xs.get(m) - xs.get(n);
                    float dy = ys.get(m) - ys.get(n);
                    if (dx * dx + dy * dy < NEIGHBOUR_DIST * NEIGHBOUR_DIST) link(adj, n, m);
                }
            }
            for (int n : cur) {
                if (!hasNeighbourIn(adj.get(n), next)) link(adj, n, nearest(next, n, xs, ys, -1));
            }
            for (int m : next) {
                if (!hasNeighbourIn(adj.get(m), cur)) link(adj, nearest(cur, m, xs, ys, -1), m);
            }
        }

        // odd paths: the occasional long route that skips a column
        for (int c = 0; c + 2 < COLUMNS; c++) {
            if (MathUtils.randomBoolean(ODD_PATH_CHANCE)) {
                List<Integer> cur = columns.get(c);
                int n = cur.get(MathUtils.random(cur.size() - 1));
                link(adj, n, nearest(columns.get(c + 2), n, xs, ys, -1));
            }
        }

        // nebulas sever routes through them, but everything stays reachable
        // and a quick-escape route (about half the nodes) always survives
        placeNebulasAndPrune(xs, ys, adj, id);

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
            Node node = new Node(i, xs.get(i), ys.get(i), types[i], connections);
            // solar storms: nodes close to a star suffer radiation events in their instances
            for (Obstacle o : obstacles) {
                if (o.kind == Obstacle.Kind.STAR
                        && Vector2.dst(o.x, o.y, node.x, node.y) < o.radius + 130f) {
                    node.stormy = true;
                }
            }
            nodes.put(i, node);
        }
        // one meteor-cloud node per sector (a middle node): its instances get showers
        if (id > 3) {
            nodes.get(1 + MathUtils.random(id - 3)).meteoric = true;
        }
        return id - 1;
    }

    /**
     * Drops 0-2 nebulas onto clear patches of the map and severs the edges running
     * through them. Cut edges are restored as needed so every node stays reachable
     * and the shortest HOME→END route needs no more than about half the nodes.
     */
    private void placeNebulasAndPrune(List<Float> xs, List<Float> ys, Map<Integer, Set<Integer>> adj, int nodeCount) {
        int wanted = MathUtils.random(0, 2);
        for (int i = 0; i < wanted; i++) {
            for (int attempt = 0; attempt < 25; attempt++) {
                float r = MathUtils.random(30f, 55f);
                float x = MathUtils.random(AREA_LEFT + 60f, AREA_RIGHT - 60f);
                float y = MathUtils.random(AREA_BOTTOM + 15f, AREA_TOP - 15f);
                boolean clear = true;
                for (int n = 0; n < nodeCount && clear; n++) {
                    clear = Vector2.dst(x, y, xs.get(n), ys.get(n)) > r + 20f;
                }
                if (clear) {
                    nebulas.add(new Nebula(x, y, r));
                    break;
                }
            }
        }
        // at most one massive body per sector: a black hole or a star, never near HOME/END
        if (MathUtils.randomBoolean(0.55f)) {
            Obstacle.Kind kind = MathUtils.randomBoolean()
                ? Obstacle.Kind.BLACK_HOLE : Obstacle.Kind.STAR;
            float r = kind == Obstacle.Kind.BLACK_HOLE
                ? MathUtils.random(22f, 34f) : MathUtils.random(30f, 44f);
            for (int attempt = 0; attempt < 30; attempt++) {
                float x = MathUtils.random(AREA_LEFT + 90f, AREA_RIGHT - 90f);
                float y = MathUtils.random(AREA_BOTTOM + 20f, AREA_TOP - 20f);
                boolean clear = Vector2.dst(x, y, xs.get(0), ys.get(0)) > r + 140f
                    && Vector2.dst(x, y, xs.get(nodeCount - 1), ys.get(nodeCount - 1)) > r + 140f;
                for (int n = 0; n < nodeCount && clear; n++) {
                    clear = Vector2.dst(x, y, xs.get(n), ys.get(n)) > r + 24f;
                }
                if (clear) {
                    obstacles.add(new Obstacle(kind, x, y, r));
                    break;
                }
            }
        }
        if (nebulas.isEmpty() && obstacles.isEmpty()) return;

        List<int[]> cut = new ArrayList<>();
        for (int a = 0; a < nodeCount; a++) {
            for (int b : new ArrayList<>(adj.get(a))) {
                if (b <= a) continue;
                if (crossesNebula(xs.get(a), ys.get(a), xs.get(b), ys.get(b))) {
                    adj.get(a).remove(b);
                    adj.get(b).remove(a);
                    cut.add(new int[]{a, b});
                }
            }
        }
        // restore cuts until every node is reachable again
        while (!cut.isEmpty() && reachableCount(adj, nodeCount) < nodeCount) {
            int[] edge = cut.remove(cut.size() - 1);
            link(adj, edge[0], edge[1]);
        }
        // quick escape: the shortest route may need at most about half the nodes
        int hopLimit = Math.max(2, nodeCount / 2);
        while (!cut.isEmpty() && hops(adj, nodeCount, nodeCount - 1) > hopLimit) {
            int[] edge = cut.remove(cut.size() - 1);
            link(adj, edge[0], edge[1]);
        }
    }

    private boolean crossesNebula(float x1, float y1, float x2, float y2) {
        for (Nebula n : nebulas) {
            if (Intersector.intersectSegmentCircle(
                    new Vector2(x1, y1), new Vector2(x2, y2),
                    new Vector2(n.x, n.y), n.radius * n.radius)) {
                return true;
            }
        }
        for (Obstacle o : obstacles) {
            float r = o.radius * 1.25f; // exclusion zone reaches past the body
            if (Intersector.intersectSegmentCircle(
                    new Vector2(x1, y1), new Vector2(x2, y2),
                    new Vector2(o.x, o.y), r * r)) {
                return true;
            }
        }
        return false;
    }

    /** Number of nodes reachable from HOME. */
    private static int reachableCount(Map<Integer, Set<Integer>> adj, int nodeCount) {
        boolean[] seen = new boolean[nodeCount];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        seen[0] = true;
        int count = 1;
        while (!queue.isEmpty()) {
            for (int m : adj.get(queue.poll())) {
                if (!seen[m]) {
                    seen[m] = true;
                    count++;
                    queue.add(m);
                }
            }
        }
        return count;
    }

    /** BFS hop count from HOME to the target, or MAX_VALUE when unreachable. */
    private static int hops(Map<Integer, Set<Integer>> adj, int nodeCount, int target) {
        int[] dist = new int[nodeCount];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        dist[0] = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        while (!queue.isEmpty()) {
            int n = queue.poll();
            for (int m : adj.get(n)) {
                if (dist[m] == Integer.MAX_VALUE) {
                    dist[m] = dist[n] + 1;
                    queue.add(m);
                }
            }
        }
        return dist[target];
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

    public List<Nebula> getNebulas() {
        return nebulas;
    }

    public List<Obstacle> getObstacles() {
        return obstacles;
    }

    public Node getNode(int id) {
        return nodes.get(id);
    }

    public Node getCurrentNode() {
        return nodes.get(currentNodeId);
    }

    public void setCurrentNode(int id) {
        currentNodeId = id;
        nodes.get(id).visited = true;
    }

    public boolean isReachable(int id) {
        if (id == currentNodeId) return true;
        return getCurrentNode().connections.contains(id);
    }

    public Iterable<Node> allNodes() {
        return nodes.values();
    }
}
