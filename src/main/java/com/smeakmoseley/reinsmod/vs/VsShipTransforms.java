package com.smeakmoseley.reinsmod.vs;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts between shipyard ("ship") space and world space.
 *
 * IMPORTANT:
 *  - Many VS objects expose BOTH shipToWorld and worldToShip matrices.
 *  - Names are not reliable enough for reflection (both contain ship+world tokens).
 *  - So we round-trip test to determine which matrix is which.
 */
public final class VsShipTransforms {
    private static final boolean DEBUG = false;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<Class<?>, Resolved> CACHE = new ConcurrentHashMap<>();

    private static final class Resolved {
        final Method getTransform;               // nullable
        final boolean matricesLiveOnTransform;   // if true, matrix getters are on transform object

        // Raw candidates we found by name scoring
        final Method candA;                      // nullable
        final Method candB;                      // nullable

        // Once decided using live instance:
        volatile boolean decided = false;
        volatile Method shipToWorldGetter = null;
        volatile Method worldToShipGetter = null;

        Resolved(Method getTransform, boolean matricesLiveOnTransform, Method candA, Method candB) {
            this.getTransform = getTransform;
            this.matricesLiveOnTransform = matricesLiveOnTransform;
            this.candA = candA;
            this.candB = candB;
        }
    }

    public static void clearCacheFor(Class<?> shipClass) {
        CACHE.remove(shipClass);
    }

    private VsShipTransforms() {}

    /** Convert a shipyard-space position to world-space. Returns null if unresolved. */
    public static Vec3 shipyardToWorld(Object shipObj, Vec3 shipyardPos) {
        if (shipObj == null || shipyardPos == null) return null;

        try {
            Resolved r = CACHE.computeIfAbsent(shipObj.getClass(), VsShipTransforms::resolve);
            if (r == null) return null;

            Object holder = r.matricesLiveOnTransform ? (r.getTransform != null ? r.getTransform.invoke(shipObj) : null) : shipObj;
            if (holder == null) return null;

            decideDirectionIfNeeded(r, holder, shipyardPos);

            // Prefer direct ship->world getter
            Matrix4dc stw = getMatrix(r.shipToWorldGetter, holder);
            if (stw != null) {
                Vector3d v = new Vector3d(shipyardPos.x, shipyardPos.y, shipyardPos.z);
                new Matrix4d(stw).transformPosition(v);
                return new Vec3(v.x, v.y, v.z);
            }

            // Else invert world->ship
            Matrix4dc wts = getMatrix(r.worldToShipGetter, holder);
            if (wts != null) {
                Matrix4d inv = new Matrix4d(wts).invert();
                Vector3d v = new Vector3d(shipyardPos.x, shipyardPos.y, shipyardPos.z);
                inv.transformPosition(v);
                return new Vec3(v.x, v.y, v.z);
            }

            return null;
        } catch (Throwable t) {
            if (DEBUG) {
                LOGGER.error("[ReinsMod VS] Error converting shipyard to world", t);
            }
            return null;
        }
    }

    /** Convert world-space to shipyard/ship-space. Returns null if unresolved. */
    public static Vec3 worldToShipyard(Object shipObj, Vec3 worldPos) {
        if (shipObj == null || worldPos == null) return null;

        try {
            Resolved r = CACHE.computeIfAbsent(shipObj.getClass(), VsShipTransforms::resolve);
            if (r == null) return null;

            Object holder = r.matricesLiveOnTransform ? (r.getTransform != null ? r.getTransform.invoke(shipObj) : null) : shipObj;
            if (holder == null) return null;

            // We don't strictly need decideDirection here, but it helps fill getters.
            decideDirectionIfNeeded(r, holder, worldPos);

            // Prefer direct world->ship getter
            Matrix4dc wts = getMatrix(r.worldToShipGetter, holder);
            if (wts != null) {
                Vector3d v = new Vector3d(worldPos.x, worldPos.y, worldPos.z);
                new Matrix4d(wts).transformPosition(v);
                return new Vec3(v.x, v.y, v.z);
            }

            // Else invert ship->world
            Matrix4dc stw = getMatrix(r.shipToWorldGetter, holder);
            if (stw != null) {
                Matrix4d inv = new Matrix4d(stw).invert();
                Vector3d v = new Vector3d(worldPos.x, worldPos.y, worldPos.z);
                inv.transformPosition(v);
                return new Vec3(v.x, v.y, v.z);
            }

            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- resolution ----------------

    private static Resolved resolve(Class<?> shipClass) {
        try {
            // 1) Collect all matrix getters on ship
            List<Method> shipMats = findAllMatrixGetters(shipClass);

            // 2) Maybe matrices are on a transform object
            Method getTransform = findTransformGetter(shipClass);
            List<Method> transformMats = Collections.emptyList();

            boolean onTransform = false;
            if (shipMats.isEmpty() && getTransform != null) {
                getTransform.setAccessible(true);
                onTransform = true;
                transformMats = findAllMatrixGetters(getTransform.getReturnType());
            }

            List<Method> mats = onTransform ? transformMats : shipMats;
            if (mats.isEmpty()) return null;

            // Choose two best distinct candidates by name scoring
            Method candA = pickBest(mats, true);
            Method candB = pickBest(mats, false);

            // If they ended up equal, drop B
            if (candA != null && candA.equals(candB)) candB = null;

            if (candA != null) candA.setAccessible(true);
            if (candB != null) candB.setAccessible(true);

            if (DEBUG) {
                if (getTransform != null) {
                    LOGGER.info("[ReinsMod VS] Transform getter on {}: {}", shipClass.getName(), getTransform.getName());
                }
                LOGGER.info("[ReinsMod VS] Matrix candidates on {}: A={} B={}",
                        shipClass.getName(),
                        (candA == null ? "null" : candA.getName()),
                        (candB == null ? "null" : candB.getName())
                );
            }

            return new Resolved(getTransform, onTransform, candA, candB);

        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Method> findAllMatrixGetters(Class<?> cls) {
        List<Method> out = new ArrayList<>();
        for (Method m : cls.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (m.getParameterCount() != 0) continue;
            if (!Matrix4dc.class.isAssignableFrom(m.getReturnType())) continue;

            String n = m.getName().toLowerCase(Locale.ROOT);
            if (isBannedName(n)) continue;

            out.add(m);
        }
        return out;
    }

    private static Method pickBest(List<Method> mats, boolean preferShipToWorld) {
        Method best = null;
        int bestScore = Integer.MIN_VALUE;

        for (Method m : mats) {
            String n = m.getName().toLowerCase(Locale.ROOT);
            int score = scoreMatrixName(n, preferShipToWorld);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    private static int scoreMatrixName(String n, boolean shipToWorld) {
        int s = 0;

        // Strong signals (mutually exclusive preferred)
        boolean hasSTW = n.contains("shiptoworld") || n.contains("ship_to_world") || n.contains("ship2world");
        boolean hasWTS = n.contains("worldtoship") || n.contains("world_to_ship") || n.contains("world2ship");

        if (shipToWorld) {
            if (hasSTW) s += 1000;
            if (hasWTS) s -= 400; // penalize wrong direction
        } else {
            if (hasWTS) s += 1000;
            if (hasSTW) s -= 400;
        }

        // Weaker signals
        if (n.contains("matrix")) s += 50;
        if (n.contains("transform")) s += 20;
        if (n.contains("get")) s += 10;

        // Still allow fallback if names are generic
        if (n.contains("ship") && n.contains("world")) s += 15;

        return s;
    }

    private static Method findTransformGetter(Class<?> shipClass) {
        Method best = null;
        int bestScore = Integer.MIN_VALUE;

        for (Method m : shipClass.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (m.getParameterCount() != 0) continue;

            String n = m.getName().toLowerCase(Locale.ROOT);
            if (!(n.startsWith("get") || n.startsWith("is"))) continue;
            if (!n.contains("transform")) continue;
            if (isBannedName(n)) continue;

            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt == void.class) continue;

            int score = 0;
            if (n.contains("shiptransform")) score += 200;
            if (n.equals("gettransform")) score += 150;
            score += 50;

            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    private static boolean isBannedName(String lower) {
        return lower.contains("shipify")
                || lower.contains("create")
                || lower.contains("construct")
                || lower.contains("assemble")
                || lower.contains("spawn")
                || lower.contains("build")
                || lower.contains("generate")
                || lower.contains("convert")
                || lower.contains("init")
                || lower.contains("load")
                || lower.contains("save");
    }

    private static Matrix4dc getMatrix(Method getter, Object holder) {
        if (getter == null || holder == null) return null;
        try {
            Object o = getter.invoke(holder);
            return (o instanceof Matrix4dc m) ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static double getShipMass(Object ship) {
        try {
            Method inertia = ship.getClass().getMethod("getInertiaData");
            Object inertiaData = inertia.invoke(ship);

            Method mass = inertiaData.getClass().getMethod("getMass");
            return ((Number) mass.invoke(inertiaData)).doubleValue();
        } catch (Throwable t) {
            return 20_000.0; // safe fallback
        }
    }

    /**
     * Decide which candidate is ship->world vs world->ship by doing a round-trip:
     *  option1: ship --A--> world --B--> ship  (error e1)
     *  option2: ship --B--> world --A--> ship  (error e2)
     * pick smaller error.
     */
    private static void decideDirectionIfNeeded(Resolved r, Object holder, Vec3 samplePos) {
        if (r.decided) return;
        if (samplePos.lengthSqr() < 1.0) {
            return; // too close to origin / meaningless sample
        }

        synchronized (r) {
            if (r.decided) return;

            Method A = r.candA;
            Method B = r.candB;

            // If only one, decide by name and rely on inversion fallback
            if (B == null) {
                r.shipToWorldGetter = A;
                r.worldToShipGetter = null;
                r.decided = true;
                return;
            }

            Matrix4dc mA = getMatrix(A, holder);
            Matrix4dc mB = getMatrix(B, holder);

            if (mA == null && mB == null) {
                r.decided = true;
                return;
            }

            // If one is null, keep the other as shipToWorld (caller may invert)
            if (mA != null && mB == null) {
                r.shipToWorldGetter = A;
                r.worldToShipGetter = null;
                r.decided = true;
                return;
            }
            if (mA == null) { // mB != null
                r.shipToWorldGetter = B;
                r.worldToShipGetter = null;
                r.decided = true;
                return;
            }

            // Both present: round-trip test
            Vector3d ship = new Vector3d(samplePos.x, samplePos.y, samplePos.z);

            double e1 = roundTripError(mA, mB, ship);
            double e2 = roundTripError(mB, mA, ship);

            if (e2 < e1) {
                r.shipToWorldGetter = B;
                r.worldToShipGetter = A;
                if (DEBUG) {
                    LOGGER.info("[ReinsMod VS] Matrix direction swap: shipToWorld={} worldToShip={} (e1={} e2={})",
                            B.getName(), A.getName(), fmt(e1), fmt(e2));
                }
            } else {
                r.shipToWorldGetter = A;
                r.worldToShipGetter = B;
                if (DEBUG) {
                LOGGER.info("[ReinsMod VS] Matrix direction ok: shipToWorld={} worldToShip={} (e1={} e2={})",
                            A.getName(), B.getName(), fmt(e1), fmt(e2));
                }
            }

            r.decided = true;
        }
    }

    private static double roundTripError(Matrix4dc shipToWorld, Matrix4dc worldToShip, Vector3d shipPos) {
        Vector3d v = new Vector3d(shipPos);
        new Matrix4d(shipToWorld).transformPosition(v);     // ship -> world
        new Matrix4d(worldToShip).transformPosition(v);     // world -> ship
        return v.distance(shipPos);
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.4f", d);
    }
}