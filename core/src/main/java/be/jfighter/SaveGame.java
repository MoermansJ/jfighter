package be.jfighter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * On-disk save of the current run (~/.jfighter/save.json): written every time the
 * overworld shows, deleted when the run ends. The JSON is hand-written (numbers,
 * strings, arrays only) and read back with JsonReader, so no reflection surprises.
 */
public final class SaveGame {
    private SaveGame() {
    }

    private static FileHandle file() {
        return Gdx.files.external(".jfighter/save.json");
    }

    public static void clear() {
        try {
            file().delete();
        } catch (Exception ignored) {
        }
    }

    public static void save(GameState s) {
        try {
            StringBuilder b = new StringBuilder(8192);
            b.append('{');
            num(b, "credits", s.credits);
            num(b, "fuel", s.fuel);
            num(b, "maxFuel", s.maxFuel);
            num(b, "hull", s.hull);
            num(b, "maxHull", s.maxHull);
            num(b, "shield", s.shield);
            num(b, "maxShield", s.maxShield);
            num(b, "sector", s.sector);
            num(b, "nodesVisited", s.nodesVisited);
            num(b, "instancesCompleted", s.instancesCompleted);
            num(b, "cargoDelivered", s.cargoDelivered);
            num(b, "hostilesDestroyed", s.hostilesDestroyed);
            num(b, "sectorsCleared", s.sectorsCleared);
            intArray(b, "roomTier", s.roomTier);
            floatArray(b, "roomIntegrity", s.roomIntegrity);
            floatArray(b, "oxygen", s.oxygen);
            b.append("\"doorHeldOpen\":[");
            for (int i = 0; i < s.doorHeldOpen.length; i++) {
                if (i > 0) b.append(',');
                b.append(s.doorHeldOpen[i]);
            }
            b.append("],");
            b.append("\"upgrades\":{");
            boolean first = true;
            for (ShipUpgrade u : ShipUpgrade.values()) {
                int level = s.upgradeLevel(u);
                if (level == 0) continue;
                if (!first) b.append(',');
                b.append('"').append(u.name()).append("\":").append(level);
                first = false;
            }
            b.append("},");
            b.append("\"crew\":[");
            for (int i = 0; i < s.crew.size(); i++) {
                if (i > 0) b.append(',');
                crewJson(b, s.crew.get(i));
            }
            b.append("],");
            if (s.boarder != null) {
                b.append("\"boarder\":");
                crewJson(b, s.boarder);
                b.append(',');
            }
            mapJson(b, s.map);
            b.append('}');
            file().writeString(b.toString(), false);
        } catch (Exception e) {
            Gdx.app.error("SaveGame", "save failed: " + e.getMessage());
        }
    }

    private static void crewJson(StringBuilder b, CrewMember c) {
        b.append("{\"name\":\"").append(c.name).append('"');
        b.append(",\"primary\":\"").append(c.primary.name()).append('"');
        b.append(",\"secondary\":\"").append(c.secondary.name()).append('"');
        b.append(",\"station\":").append(c.station);
        b.append(",\"hp\":").append(c.hp);
        b.append(",\"deckX\":").append(c.deckX);
        b.append(",\"deckY\":").append(c.deckY);
        b.append(",\"freeX\":").append(c.freeX);
        b.append(",\"freeY\":").append(c.freeY);
        b.append(",\"hostile\":").append(c.hostile);
        b.append('}');
    }

    private static void mapJson(StringBuilder b, OverworldMap map) {
        b.append("\"map\":{");
        b.append("\"sectorName\":\"").append(map.sectorName).append('"');
        b.append(",\"lastNodeId\":").append(map.lastNodeId);
        b.append(",\"currentNodeId\":").append(map.getCurrentNode().id);
        b.append(",\"nodes\":[");
        boolean first = true;
        for (Node n : map.allNodes()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(n.id)
                .append(",\"x\":").append(n.x)
                .append(",\"y\":").append(n.y)
                .append(",\"type\":\"").append(n.type.name()).append('"')
                .append(",\"visited\":").append(n.visited)
                .append(",\"completed\":").append(n.completed)
                .append(",\"stormy\":").append(n.stormy)
                .append(",\"meteoric\":").append(n.meteoric)
                .append(",\"connections\":[");
            for (int k = 0; k < n.connections.size(); k++) {
                if (k > 0) b.append(',');
                b.append(n.connections.get(k));
            }
            b.append("]}");
        }
        b.append("],\"nebulas\":[");
        List<OverworldMap.Nebula> nebs = map.getNebulas();
        for (int i = 0; i < nebs.size(); i++) {
            if (i > 0) b.append(',');
            OverworldMap.Nebula n = nebs.get(i);
            b.append('[').append(n.x).append(',').append(n.y).append(',').append(n.radius).append(']');
        }
        b.append("],\"obstacles\":[");
        List<OverworldMap.Obstacle> obs = map.getObstacles();
        for (int i = 0; i < obs.size(); i++) {
            if (i > 0) b.append(',');
            OverworldMap.Obstacle o = obs.get(i);
            b.append("[\"").append(o.kind.name()).append("\",")
                .append(o.x).append(',').append(o.y).append(',').append(o.radius).append(']');
        }
        b.append("]}");
    }

    private static void num(StringBuilder b, String key, float v) {
        b.append('"').append(key).append("\":").append(v).append(',');
    }

    private static void num(StringBuilder b, String key, int v) {
        b.append('"').append(key).append("\":").append(v).append(',');
    }

    private static void intArray(StringBuilder b, String key, int[] arr) {
        b.append('"').append(key).append("\":[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) b.append(',');
            b.append(arr[i]);
        }
        b.append("],");
    }

    private static void floatArray(StringBuilder b, String key, float[] arr) {
        b.append('"').append(key).append("\":[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) b.append(',');
            b.append(arr[i]);
        }
        b.append("],");
    }

    /** Restores the saved run, or null when there is none (or it fails to parse). */
    public static GameState load() {
        try {
            if (!file().exists()) return null;
            JsonValue r = new JsonReader().parse(file());
            GameState s = new GameState();
            s.crew.clear();
            s.boarder = null;
            s.credits = r.getInt("credits");
            s.fuel = r.getFloat("fuel");
            s.maxFuel = r.getFloat("maxFuel");
            s.hull = r.getFloat("hull");
            s.maxHull = r.getFloat("maxHull");
            s.shield = r.getFloat("shield");
            s.maxShield = r.getFloat("maxShield");
            s.sector = r.getInt("sector");
            s.nodesVisited = r.getInt("nodesVisited");
            s.instancesCompleted = r.getInt("instancesCompleted");
            s.cargoDelivered = r.getInt("cargoDelivered");
            s.hostilesDestroyed = r.getInt("hostilesDestroyed");
            s.sectorsCleared = r.getInt("sectorsCleared");
            readInts(r.get("roomTier"), s.roomTier);
            readFloats(r.get("roomIntegrity"), s.roomIntegrity);
            readFloats(r.get("oxygen"), s.oxygen);
            JsonValue doors = r.get("doorHeldOpen");
            for (int i = 0; i < s.doorHeldOpen.length && i < doors.size; i++) {
                s.doorHeldOpen[i] = doors.getBoolean(i);
            }
            for (JsonValue u = r.get("upgrades").child; u != null; u = u.next) {
                s.upgrades.put(ShipUpgrade.valueOf(u.name), u.asInt());
            }
            for (JsonValue c = r.get("crew").child; c != null; c = c.next) {
                s.crew.add(readCrew(c));
            }
            if (r.has("boarder")) s.boarder = readCrew(r.get("boarder"));
            s.map = readMap(r.get("map"));
            return s;
        } catch (Exception e) {
            Gdx.app.error("SaveGame", "load failed: " + e.getMessage());
            return null;
        }
    }

    private static CrewMember readCrew(JsonValue c) {
        CrewMember m = new CrewMember(c.getString("name"),
            Skill.valueOf(c.getString("primary")), Skill.valueOf(c.getString("secondary")));
        m.station = c.getInt("station");
        m.hp = c.getFloat("hp");
        m.deckX = c.getFloat("deckX");
        m.deckY = c.getFloat("deckY");
        m.freeX = c.getFloat("freeX", -1f);
        m.freeY = c.getFloat("freeY", -1f);
        m.hostile = c.getBoolean("hostile");
        return m;
    }

    private static OverworldMap readMap(JsonValue m) {
        List<Node> nodeList = new ArrayList<>();
        for (JsonValue n = m.get("nodes").child; n != null; n = n.next) {
            List<Integer> conns = new ArrayList<>();
            for (JsonValue k = n.get("connections").child; k != null; k = k.next) {
                conns.add(k.asInt());
            }
            Node node = new Node(n.getInt("id"), n.getFloat("x"), n.getFloat("y"),
                Node.Type.valueOf(n.getString("type")), conns);
            node.visited = n.getBoolean("visited");
            node.completed = n.getBoolean("completed");
            node.stormy = n.getBoolean("stormy");
            node.meteoric = n.getBoolean("meteoric");
            nodeList.add(node);
        }
        List<OverworldMap.Nebula> nebs = new ArrayList<>();
        for (JsonValue n = m.get("nebulas").child; n != null; n = n.next) {
            nebs.add(new OverworldMap.Nebula(n.getFloat(0), n.getFloat(1), n.getFloat(2)));
        }
        List<OverworldMap.Obstacle> obs = new ArrayList<>();
        for (JsonValue o = m.get("obstacles").child; o != null; o = o.next) {
            obs.add(new OverworldMap.Obstacle(
                OverworldMap.Obstacle.Kind.valueOf(o.getString(0)),
                o.getFloat(1), o.getFloat(2), o.getFloat(3)));
        }
        return new OverworldMap(m.getString("sectorName"), m.getInt("lastNodeId"),
            m.getInt("currentNodeId"), nodeList, nebs, obs);
    }

    private static void readInts(JsonValue arr, int[] into) {
        for (int i = 0; i < into.length && i < arr.size; i++) {
            into[i] = arr.getInt(i);
        }
    }

    private static void readFloats(JsonValue arr, float[] into) {
        for (int i = 0; i < into.length && i < arr.size; i++) {
            into[i] = arr.getFloat(i);
        }
    }
}
