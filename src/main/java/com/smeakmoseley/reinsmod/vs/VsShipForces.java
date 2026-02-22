package com.smeakmoseley.reinsmod.vs;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class VsShipForces {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile String SUMMARY = "unresolved";

    // Forces smaller than this are treated as "no-op"
    private static final double FORCE_EPS_SQR = 1.0e-6;

    // Set true temporarily while testing dedicated server
    private static final boolean DEBUG = false;

    private VsShipForces() {}

    public static String resolutionSummary() {
        return SUMMARY;
    }

    /**
     * Applies a WORLD-space force to a VS ship using GameToPhysicsAdapter (GTPA).
     *
     * @param shipObj    usually org.valkyrienskies.core.impl.game.ships.ShipData (or similar)
     * @param forceWorld force in WORLD space
     * @param worldPos   position in WORLD space where force is applied (null = COM)
     */
    public static boolean applyWorldForce(Object shipObj, Vec3 forceWorld, Vec3 worldPos) {
        if (shipObj == null || forceWorld == null) return false;

        if (forceWorld.lengthSqr() <= FORCE_EPS_SQR) {
            SUMMARY = "skipped_zero_force";
            return false;
        }

        try {
            // 1) shipId (ShipId is basically a long in VS)
            long shipId = readLongProperty(shipObj, "id", "getId");

            // 2) Dimension handle (can be String OR ResourceKey OR ResourceLocation depending on side/build)
            Object dimObj = readAnyProperty(shipObj, "chunkClaimDimension", "getChunkClaimDimension");
            if (dimObj == null) {
                SUMMARY = "no_dimension_obj";
                return false;
            }

            // 3) ValkyrienSkiesMod.getOrCreateGTPA(...)
            Class<?> vsm = Class.forName("org.valkyrienskies.mod.common.ValkyrienSkiesMod");

            Method getOrCreate = findGetOrCreateGTPA(vsm, dimObj);
            if (getOrCreate == null) {
                // fallback: try String form
                String dimString = normalizeDimToString(dimObj);
                if (dimString == null || dimString.isBlank()) {
                    SUMMARY = "no_gtpa_getter_for_dim:" + dimObj.getClass().getName();
                    return false;
                }
                getOrCreate = findGetOrCreateGTPA(vsm, dimString);
                if (getOrCreate == null) {
                    SUMMARY = "no_gtpa_getter";
                    return false;
                }
                dimObj = dimString;
            }

            Object gtpa = getOrCreate.invoke(null, dimObj);
            if (gtpa == null) {
                SUMMARY = "no_gtpa";
                return false;
            }

            // 4) Wake ship ONLY because we have a real force
            tryInvokeSetStatic(gtpa, shipId, false);

            // 5) Call gtpa.applyWorldForce(shipId, Vector3dc, Vector3dc?)
            Vector3d f = new Vector3d(forceWorld.x, forceWorld.y, forceWorld.z);
            Vector3d p = (worldPos == null) ? null : new Vector3d(worldPos.x, worldPos.y, worldPos.z);

            Method applyWorldForce = findApplyWorldForce(gtpa.getClass());
            if (applyWorldForce == null) {
                SUMMARY = "no_apply_world_force";
                return false;
            }

            applyWorldForce.invoke(gtpa, shipId, f, p);

            SUMMARY = "ok_gtpa";
            if (DEBUG) {
                LOGGER.info("[ReinsMod VS] applyWorldForce OK shipId={} dimType={} dim={} f=({}, {}, {})",
                        shipId,
                        dimObj.getClass().getSimpleName(),
                        String.valueOf(dimObj),
                        forceWorld.x, forceWorld.y, forceWorld.z);
            }
            return true;

        } catch (Throwable t) {
            SUMMARY = "invoke_failed:" + t.getClass().getSimpleName();
            LOGGER.error("[ReinsMod VS] applyWorldForce FAILED summary={}", SUMMARY);
            return false;
        }
    }

    // ------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------

    private static Method findGetOrCreateGTPA(Class<?> vsm, Object dimObj) {
        if (dimObj == null) return null;

        // Try exact runtime type first
        Method m = tryGet(vsm, "getOrCreateGTPA", dimObj.getClass());
        if (m != null) return m;

        // Common VS dimension representations
        m = tryGet(vsm, "getOrCreateGTPA", net.minecraft.resources.ResourceKey.class);
        if (m != null) return m;

        m = tryGet(vsm, "getOrCreateGTPA", net.minecraft.resources.ResourceLocation.class);
        if (m != null) return m;

        m = tryGet(vsm, "getOrCreateGTPA", String.class);
        if (m != null) return m;

        // Last resort
        return tryGet(vsm, "getOrCreateGTPA", Object.class);
    }

    private static Method tryGet(Class<?> owner, String name, Class<?> p0) {
        try {
            Method m = owner.getMethod(name, p0);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findApplyWorldForce(Class<?> gtpaClass) {
        try {
            Class<?> v3dc = Class.forName("org.joml.Vector3dc");
            Method m = gtpaClass.getMethod("applyWorldForce", long.class, v3dc, v3dc);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {}

        // fallback (older/odd builds)
        for (Method m : gtpaClass.getMethods()) {
            try {
                if (!m.getName().equals("applyWorldForce")) continue;
                if (m.getParameterCount() != 3) continue;
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void tryInvokeSetStatic(Object gtpa, long shipId, boolean isStatic) {
        try {
            for (Method m : gtpa.getClass().getMethods()) {
                if (!m.getName().equals("setStatic")) continue;
                if (m.getParameterCount() != 2) continue;

                Class<?>[] p = m.getParameterTypes();
                boolean firstOk = (p[0] == long.class) || (p[0] == Long.class);
                boolean secondOk = (p[1] == boolean.class) || (p[1] == Boolean.class);

                if (firstOk && secondOk) {
                    m.setAccessible(true);
                    m.invoke(gtpa, shipId, isStatic);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static long readLongProperty(Object obj, String fieldName, String getterName) throws Exception {
        try {
            Method m = obj.getClass().getMethod(getterName);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            if (v instanceof Number n) return n.longValue();
        } catch (NoSuchMethodException ignored) {}

        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        Object v = f.get(obj);
        if (v instanceof Number n) return n.longValue();

        throw new IllegalStateException("Cannot read long property " + fieldName + "/" + getterName);
    }

    private static Object readAnyProperty(Object obj, String fieldName, String getterName) {
        try {
            Method m = obj.getClass().getMethod(getterName);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable ignored) {}

        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Best-effort normalization for when VS expects a String dimension id.
     * Handles common types used in 1.20.1.
     */
    private static String normalizeDimToString(Object dimObj) {
        if (dimObj == null) return null;

        if (dimObj instanceof String s) return s;

        // ResourceKey<Level> -> its location
        if (dimObj instanceof ResourceKey<?> rk) {
            ResourceLocation loc = rk.location();
            return loc != null ? loc.toString() : null;
        }

        // ResourceLocation -> string
        if (dimObj instanceof ResourceLocation rl) return rl.toString();

        // Sometimes it’s literally a Level key wrapped
        if (dimObj instanceof Level lvl) {
            try {
                ResourceKey<Level> key = lvl.dimension();
                return key.location().toString();
            } catch (Throwable ignored) {}
        }

        // last resort: parse a minecraft:overworld-like token from toString()
        String s = dimObj.toString();
        if (s == null) return null;

        int idx = s.indexOf("minecraft:");
        if (idx >= 0) {
            int end = idx;
            while (end < s.length()) {
                char c = s.charAt(end);
                if (Character.isWhitespace(c) || c == ']' || c == ')' || c == ',') break;
                end++;
            }
            return s.substring(idx, end);
        }

        return s;
    }
}