package be.jfighter;

import java.util.ArrayList;
import java.util.List;

/**
 * Mothership armament (#119): size-classed weapon sockets with per-model layouts.
 * The heavy socket holds the aimed 155mm; MG-46 cupolas are automatic close-defence
 * turrets (1200 rpm, light ballistic); medium sockets map to trader-bought weapons.
 */
public class Mothership {
    public enum SocketSize { LIGHT, MEDIUM, HEAVY }

    public static final String MOUNT_155 = "155MM CANNON";
    public static final String MOUNT_MG46 = "MG-46 CUPOLA";
    public static final String MOUNT_AC20 = "20MM AUTOCANNON";

    public static class Socket {
        public final SocketSize size;
        public String mount; // display name; null = empty

        Socket(SocketSize size, String mount) {
            this.size = size;
            this.mount = mount;
        }
    }

    public final String model;
    public final List<Socket> sockets = new ArrayList<>();

    private Mothership(String model) {
        this.model = model;
    }

    /** Model A: 1x heavy (155mm T1), 2x light (MG-46), a 20mm autocannon + 1 empty medium. */
    public static Mothership modelA() {
        Mothership m = new Mothership("MODEL A");
        m.sockets.add(new Socket(SocketSize.HEAVY, MOUNT_155));
        m.sockets.add(new Socket(SocketSize.LIGHT, MOUNT_MG46));
        m.sockets.add(new Socket(SocketSize.LIGHT, MOUNT_MG46));
        m.sockets.add(new Socket(SocketSize.MEDIUM, MOUNT_AC20));
        m.sockets.add(new Socket(SocketSize.MEDIUM, null));
        return m;
    }

    /** Hull-specific fits until more models exist. */
    public static Mothership forHull(ShipHull hull) {
        switch (hull) {
            case SCOUT: {
                Mothership m = new Mothership("SCOUT FIT");
                m.sockets.add(new Socket(SocketSize.LIGHT, MOUNT_MG46));
                m.sockets.add(new Socket(SocketSize.LIGHT, MOUNT_MG46));
                m.sockets.add(new Socket(SocketSize.MEDIUM, null));
                return m;
            }
            case FREIGHTER: {
                Mothership m = new Mothership("FREIGHTER FIT");
                m.sockets.add(new Socket(SocketSize.HEAVY, MOUNT_155));
                m.sockets.add(new Socket(SocketSize.LIGHT, MOUNT_MG46));
                m.sockets.add(new Socket(SocketSize.MEDIUM, null));
                m.sockets.add(new Socket(SocketSize.MEDIUM, null));
                return m;
            }
            default:
                return modelA();
        }
    }

    public int countMounts(String mount) {
        int n = 0;
        for (Socket s : sockets) {
            if (mount.equals(s.mount)) n++;
        }
        return n;
    }
}
