package com.smeakmoseley.reinsmod.vs;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;

/**
 * Dedicated-server safe ship lookup for Valkyrien Skies.
 *
 * IMPORTANT:
 *  - Do NOT load VS utility *Kt classes (they may reference client-only Minecraft classes).
 *  - Do NOT enumerate methods on those utilities.
 *
 * This implementation relies on the fact that in many VS builds, ServerLevel/Level is mixed in
 * with a direct instance getter:
 *     level.getShipObjectWorld()
 * Kotlin can still call this as `level.shipObjectWorld` (property syntax), which matches your
 * Weather2Compat snippet.
 */
public final class VsShipAccess {
    private static final Logger LOGGER = LogUtils.getLogger();

    private enum PosKind { BLOCKPOS, VEC3, JOML3D }

    private static volatile boolean RESOLVED = false;

    // Level/ServerLevel -> ShipObjectWorld
    private static volatile Method GET_SHIP_OBJECT_WORLD = null;

    // ShipObjectWorld -> ship lookup (optional)
    private static volatile Method SOW_LOOKUP = null;
    private static volatile PosKind SOW_LOOKUP_KIND = null;

    // ShipObjectWorld -> loadedShips (fallback)
    private static volatile Method SOW_GET_LOADED_SHIPS = null;

    private VsShipAccess() {}

    public static Optional<Object> getShipManagingPos(ServerLevel level, Vec3 worldPos) {
        if (level == null || worldPos == null) return Optional.empty();

        ensureResolved(level);

        Object sow = getShipObjectWorld(level);
        if (sow == null) return Optional.empty();

        // 1) Prefer a direct lookup method if present
        Object ship = tryLookupOnSow(sow, level, worldPos);
        if (ship != null) return Optional.of(ship);

        // 2) Fallback: nearest from loadedShips (works for leash knot/fence use-case)
        ship = tryNearestFromLoadedShips(sow, worldPos);
        if (ship != null) return Optional.of(ship);

        return Optional.empty();
    }

    public static Optional<Object> getShipManagingPos(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return Optional.empty();
        return getShipManagingPos(level, Vec3.atCenterOf(pos));
    }

    // ------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------

    private static void ensureResolved(ServerLevel level) {
        if (RESOLVED) return;
        synchronized (VsShipAccess.class) {
            if (RESOLVED) return;

            // 1) Bind level.getShipObjectWorld() by walking class chain
            GET_SHIP_OBJECT_WORLD = findNoArgMethod(level.getClass(), "getShipObjectWorld");

            Object sow = (GET_SHIP_OBJECT_WORLD != null) ? getShipObjectWorld(level) : null;

            if (GET_SHIP_OBJECT_WORLD != null) {
                LOGGER.info("[ReinsMod VS] Bound Level.getShipObjectWorld() on {}", level.getClass().getName());
            } else {
                LOGGER.warn("[ReinsMod VS] Could not find getShipObjectWorld() on ServerLevel/Level class chain.");
            }

            // 2) If we got SOW, resolve optional lookup + loadedShips access
            if (sow != null) {
                resolveSowMethods(sow.getClass());
            } else {
                SOW_LOOKUP = null;
                SOW_LOOKUP_KIND = null;
                SOW_GET_LOADED_SHIPS = null;
            }

            if (SOW_LOOKUP != null) {
                LOGGER.info("[ReinsMod VS] ShipObjectWorld lookup bound: {}.{}({})",
                        SOW_LOOKUP.getDeclaringClass().getName(),
                        SOW_LOOKUP.getName(),
                        SOW_LOOKUP.getParameterTypes()[0].getSimpleName());
            } else {
                LOGGER.warn("[ReinsMod VS] ShipObjectWorld lookup method NOT resolved (will use loadedShips fallback).");
            }

            if (SOW_GET_LOADED_SHIPS != null) {
                LOGGER.info("[ReinsMod VS] ShipObjectWorld loadedShips getter bound: {}.{}()",
                        SOW_GET_LOADED_SHIPS.getDeclaringClass().getName(),
                        SOW_GET_LOADED_SHIPS.getName());
            } else {
                LOGGER.warn("[ReinsMod VS] ShipObjectWorld loadedShips getter NOT resolved (cannot fallback).");
            }

            RESOLVED = true;
        }
    }

    private static Method findNoArgMethod(Class<?> start, String name) {
        Class<?> c = start;
        while (c != null) {
            try {
                Method m = c.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object getShipObjectWorld(ServerLevel level) {
        if (GET_SHIP_OBJECT_WORLD == null) return null;
        try {
            return GET_SHIP_OBJECT_WORLD.invoke(level);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void resolveSowMethods(Class<?> sowClass) {
        // Try exact signatures only (no enumeration)

        // --- direct lookup methods on SOW ---
        // Common names across builds:
        String[] names = {"getShipManagingPos", "getShipAtPos", "getShipAtPosition", "getShipAt"};

        // A) (Vec3)
        for (String n : names) {
            Method m = tryInstance(sowClass, n, Vec3.class);
            if (m != null) { SOW_LOOKUP = m; SOW_LOOKUP_KIND = PosKind.VEC3; break; }
        }

        // B) (BlockPos)
        if (SOW_LOOKUP == null) {
            for (String n : names) {
                Method m = tryInstance(sowClass, n, BlockPos.class);
                if (m != null) { SOW_LOOKUP = m; SOW_LOOKUP_KIND = PosKind.BLOCKPOS; break; }
            }
        }

        // C) (Vector3dc)
        if (SOW_LOOKUP == null) {
            Class<?> v3dc = tryLoad("org.joml.Vector3dc");
            if (v3dc != null) {
                for (String n : names) {
                    Method m = tryInstance(sowClass, n, v3dc);
                    if (m != null) { SOW_LOOKUP = m; SOW_LOOKUP_KIND = PosKind.JOML3D; break; }
                }
            }
        }

        // D) Some builds might require (Level, Vec3) or (ServerLevel, Vec3)
        if (SOW_LOOKUP == null) {
            for (String n : names) {
                Method m = tryInstance(sowClass, n, Level.class, Vec3.class);
                if (m != null) { SOW_LOOKUP = m; SOW_LOOKUP_KIND = PosKind.VEC3; break; }
            }
        }
        if (SOW_LOOKUP == null) {
            for (String n : names) {
                Method m = tryInstance(sowClass, n, ServerLevel.class, Vec3.class);
                if (m != null) { SOW_LOOKUP = m; SOW_LOOKUP_KIND = PosKind.VEC3; break; }
            }
        }

        // --- loadedShips getter (fallback) ---
        Method ls = tryInstanceNoArgs(sowClass, "getLoadedShips");
        if (ls == null) ls = tryInstanceNoArgs(sowClass, "loadedShips");
        if (ls == null) ls = tryInstanceNoArgs(sowClass, "getShips");
        if (ls == null) ls = tryInstanceNoArgs(sowClass, "ships");
        SOW_GET_LOADED_SHIPS = ls;
    }

    private static Method tryInstance(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            if (Modifier.isStatic(m.getModifiers())) return null;
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method tryInstanceNoArgs(Class<?> owner, String name) {
        return tryInstance(owner, name);
    }

    private static Class<?> tryLoad(String cn) {
        try { return Class.forName(cn); } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------
    // Using ShipObjectWorld
    // ------------------------------------------------------------

    private static Object tryLookupOnSow(Object sow, ServerLevel level, Vec3 worldPos) {
        if (sow == null || SOW_LOOKUP == null) return null;

        try {
            Object result;

            Class<?>[] p = SOW_LOOKUP.getParameterTypes();
            // Handle either (pos) or (level, pos)
            if (p.length == 1) {
                result = switch (SOW_LOOKUP_KIND) {
                    case VEC3 -> SOW_LOOKUP.invoke(sow, worldPos);
                    case JOML3D -> SOW_LOOKUP.invoke(sow, new Vector3d(worldPos.x, worldPos.y, worldPos.z));
                    case BLOCKPOS -> SOW_LOOKUP.invoke(sow, BlockPos.containing(worldPos));
                };
            } else if (p.length == 2) {
                Object posArg = switch (SOW_LOOKUP_KIND) {
                    case VEC3 -> worldPos;
                    case JOML3D -> new Vector3d(worldPos.x, worldPos.y, worldPos.z);
                    case BLOCKPOS -> BlockPos.containing(worldPos);
                };
                result = SOW_LOOKUP.invoke(sow, level, posArg);
            } else {
                return null;
            }

            if (result == null) return null;
            if (result instanceof Optional<?> opt) return opt.orElse(null);
            return result;

        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object tryNearestFromLoadedShips(Object sow, Vec3 worldPos) {
        if (sow == null || SOW_GET_LOADED_SHIPS == null) return null;

        try {
            Object loaded = SOW_GET_LOADED_SHIPS.invoke(sow);
            if (loaded == null) return null;

            Iterable<?> it = asIterable(loaded);
            if (it == null) return null;

            Object bestShip = null;
            double bestD2 = Double.POSITIVE_INFINITY;

            for (Object ship : it) {
                if (ship == null) continue;

                Vec3 shipPos = tryShipWorldPos(ship);
                if (shipPos == null) continue;

                double dx = shipPos.x - worldPos.x;
                double dz = shipPos.z - worldPos.z;
                double d2 = dx * dx + dz * dz;

                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestShip = ship;
                }
            }

            return bestShip;

        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Iterable<?> asIterable(Object o) {
        if (o instanceof Iterable<?> i) return i;
        if (o != null && o.getClass().isArray()) {
            return () -> new Iterator<>() {
                final int len = java.lang.reflect.Array.getLength(o);
                int idx = 0;
                @Override public boolean hasNext() { return idx < len; }
                @Override public Object next() { return java.lang.reflect.Array.get(o, idx++); }
            };
        }
        return null;
    }

    /**
     * Best-effort ship WORLD position used for nearest-ship fallback.
     * Tries common patterns with exact method calls (no enumeration).
     */
    private static Vec3 tryShipWorldPos(Object ship) {
        // getPosition(): Vector3dc or Vector3d
        try {
            Method m = ship.getClass().getMethod("getPosition");
            Object v = m.invoke(ship);
            Vec3 vv = toVec3(v);
            if (vv != null) return vv;
        } catch (Throwable ignored) {}

        // getTransform().getPosition()
        try {
            Method gt = ship.getClass().getMethod("getTransform");
            Object tr = gt.invoke(ship);
            if (tr != null) {
                try {
                    Method gp = tr.getClass().getMethod("getPosition");
                    Object v = gp.invoke(tr);
                    Vec3 vv = toVec3(v);
                    if (vv != null) return vv;
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}

        // shipToWorld + inertia COM path is version-dependent; skip here to avoid heavy reflection.

        return null;
    }

    private static Vec3 toVec3(Object v) {
        if (v == null) return null;

        if (v instanceof Vector3d d) return new Vec3(d.x, d.y, d.z);

        // Vector3dc: has x(), y(), z()
        try {
            if (v.getClass().getName().equals("org.joml.Vector3dc")) {
                Method x = v.getClass().getMethod("x");
                Method y = v.getClass().getMethod("y");
                Method z = v.getClass().getMethod("z");
                return new Vec3(
                        ((Number) x.invoke(v)).doubleValue(),
                        ((Number) y.invoke(v)).doubleValue(),
                        ((Number) z.invoke(v)).doubleValue()
                );
            }
        } catch (Throwable ignored) {}

        return null;
    }
}