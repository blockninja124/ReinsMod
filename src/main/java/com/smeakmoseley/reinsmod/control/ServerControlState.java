package com.smeakmoseley.reinsmod.control;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerControlState {

    public static class Control {
        public float forward;
        public float strafe;
        public float yaw;
        public boolean sprint;
        public boolean jump;

        // when we last received input for this player (server tick)
        public int lastInputTick;

        public boolean cruiseEnabled;
        public boolean cruiseSprint;
        public int cruiseSetTick;

        // ✅ animals that should keep being controlled while cruising (even if player moves away)
        public final Set<UUID> cruiseAnimalIds = new HashSet<>();
    }

    private static final ConcurrentHashMap<UUID, Control> STATE = new ConcurrentHashMap<>();

    public static void update(UUID playerId,
                              float forward, float strafe, float yaw,
                              boolean sprint, boolean jump,
                              int serverTick) {

        Control c = STATE.computeIfAbsent(playerId, id -> new Control());
        c.forward = forward;
        c.strafe = strafe;
        c.yaw = yaw;
        c.sprint = sprint;
        c.jump = jump;
        c.lastInputTick = serverTick;
    }

    public static Control get(UUID playerId) {
        return STATE.get(playerId);
    }

    public static Control getRecent(UUID playerId, int serverTick, int graceTicks) {
        Control c = STATE.get(playerId);
        if (c == null) return null;
        return (serverTick - c.lastInputTick) <= graceTicks ? c : null;
    }

    public static void clear(UUID playerId) {
        STATE.remove(playerId);
    }

    public static boolean isCruising(UUID playerId) {
        Control c = STATE.get(playerId);
        return c != null && c.cruiseEnabled;
    }

    public static Control getRecentOrCruise(UUID playerId, int serverTick, int graceTicks, int cruiseTimeoutTicks) {
        Control c = STATE.get(playerId);
        if (c == null) return null;

        if ((serverTick - c.lastInputTick) <= graceTicks) return c;

        if (c.cruiseEnabled && (serverTick - c.cruiseSetTick) <= cruiseTimeoutTicks) return c;

        return null;
    }

    public static void disableCruise(UUID playerId) {
        Control c = STATE.get(playerId);
        if (c == null) return;

        c.cruiseEnabled = false;
        c.cruiseSprint = false;
        c.cruiseSetTick = 0;

        // only if you have this field:
        if (c.cruiseAnimalIds != null) c.cruiseAnimalIds.clear();
    }

}
