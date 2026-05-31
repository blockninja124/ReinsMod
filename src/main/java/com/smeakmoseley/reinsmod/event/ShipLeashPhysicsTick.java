package com.smeakmoseley.reinsmod.event;

import com.mojang.logging.LogUtils;
import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.capability.reined.ReinedAnimalProvider;
import com.smeakmoseley.reinsmod.control.ServerControlState;
import com.smeakmoseley.reinsmod.vs.VsShipForces;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(
        modid = ReinsMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ShipLeashPhysicsTick {

    private static final double SCAN_RADIUS = 96.0;

    // Rope geometry
    private static final double SLACK = 2.5;
    private static final double PLAYER_SLACK = 0.25;

    // Spring leash (passive / stretch component)
    private static final double SPRING = 2800.0;
    private static final double DAMPING = 280.0;

    // Player intent pulling – proportional to mass, no light-ship favoritism
    private static final double BASE_INTENT_FORCE_PER_TON = 30_000.0;
    private static final double INTENT_FORCE_MASS_EXPONENT = 0.75;
    private static final double MAX_INTENT_FORCE = 8_000_000.0;
    private static final double MIN_INTENT_FORCE = 5_000.0;

    // Smooth + rate limit (pull force only)
    private static final double FORCE_SMOOTHING = 0.75;
    private static final double MIN_FORCE_MASS_MULT = 2.0;
    private static final double FORCE_RATE_LIMIT_MULT_LIGHT = 400.0;
    private static final double FORCE_RATE_LIMIT_MULT_HEAVY = 220.0;

    // Safety rule: shipSpeedAlong must NOT exceed allowedAlong + eps
    private static final double VELOCITY_EPS = 0.015;

    // Hysteresis band to prevent brake/pull pumping
    private static final double SPEED_HYST = 0.05; // tune 0.03–0.08

    // Braking controller (COM braking only)
    private static final double BRAKE_GAIN = 1.25; // tune 0.8–2.0
    private static final double MAX_FORCE = 1_000_000.0;

    // STOP-INTENT braking (player released movement input)
    // When player is controlling but not giving movement input, brake any forward ship motion along reins.
    private static final double STOP_BRAKE_GAIN = 2.6;      // stronger than BRAKE_GAIN
    private static final double STOP_BRAKE_DEADZONE = 0.005; // ignore jitter (blocks/tick)
    private static final double STOP_MAX_FORCE = 2_000_000.0;

    private static final double MAX_REASONABLE_DIST = 128.0;

    // --- SHAFT braking (prevents overrunning animals like a carriage shaft) ---
    private static final double SHAFT_LENGTH = 3.2;        // desired minimum gap (blocks) from anchor to animal
    private static final double SHAFT_SOFT_ZONE = 1.6;     // ramp distance (blocks) before "hard" shaft
    private static final double SHAFT_GAIN = 6.0;          // how aggressively it ramps once inside soft zone
    private static final double SHAFT_DAMP = 2.0;          // damping along travel direction
    private static final double SHAFT_MAX_FORCE = 3_500_000.0; // cap for shaft brake

    // --- commanded speed constants (match ServerAnimalControlTick) ---
    private static final double CMD_WALK_SPEED = 0.20;
    private static final double CMD_SPRINT_MULT = 1.80;

    // "no input" threshold (stick noise guard)
    private static final double INPUT_MAG_EPS = 0.05;

    // VS velocity is *likely* blocks/sec, Minecraft movement is blocks/tick
    private static final double SEC_PER_TICK = 1.0 / 20.0;

    private static final ConcurrentHashMap<UUID, Double> LAST_PULL_FORCE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> LAST_BAD_ANCHOR_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Double> LAST_SHAFT_BRAKE = new ConcurrentHashMap<>();

    // ✅ Turn on for debugging
    private static final boolean DEBUG = false;

    private static final int CRUISE_TIMEOUT_TICKS = 20 * 60; // 60 seconds

    private static final Logger LOGGER = LogUtils.getLogger();

    private ShipLeashPhysicsTick() {}

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        // Run every other tick
        if ((level.getServer().getTickCount() & 1) != 0) return;

        int nowTick = level.getServer().getTickCount();
        Set<Integer> seen = new HashSet<>();

        for (ServerPlayer player : level.players()) {
            AABB scan = player.getBoundingBox().inflate(SCAN_RADIUS);

            for (Animal animal : level.getEntitiesOfClass(Animal.class, scan)) {
                if (!seen.add(animal.getId())) continue;

                animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {
                    if (!cap.hasReins()) return;
                    if (!cap.isLeashedToShip()) return;
                    if (!animal.isLeashed()) return;

                    if (!(animal.getLeashHolder() instanceof LeashFenceKnotEntity knot)) return;

                    if (DEBUG && nowTick % 40 == 0) {
                        LOGGER.info("[ReinsMod] ShipLeashPhysicsTick sees leashed animal={} hasReins={} leashedToShip={} owner={}",
                                animal.getUUID(),
                                cap.hasReins(),
                                cap.isLeashedToShip(),
                                cap.getOwner());
                    }

                    Vec3 knotPos = knot.position();

                    // Try ship lookup at knot position, then at the fence block center as fallback
                    Vec3 fenceCenterWorld = Vec3.atCenterOf(knot.blockPosition());
                    Ship ship0 = VSGameUtilsKt.getShipManagingPos(level, knotPos);
                    if (ship0 == null) {
                        ship0 = VSGameUtilsKt.getShipManagingPos(level, fenceCenterWorld);
                    }
                    if (ship0 == null) return;

                    // --- IMPORTANT: minimal dedicated-server fix (NO physics/feel changes) ---
                    // Dedicated can report knotPos in a non-world space (shipyard/physics). We *only* fix anchor resolution.
                    // We also prefer the capability anchor/fence if present (these are what you set when leashing to ship),
                    // which keeps behavior identical to single-player.
                    Vec3 capAnchor = cap.getShipAnchorPos(); // may be null
                    BlockPos capFence = cap.getShipFencePos(); // may be null
                    Vec3 capFenceCenter = capFence != null ? Vec3.atCenterOf(capFence) : null;

                    AnchorSolve solved = resolveAnchorWorld(level, ship0, knotPos, fenceCenterWorld, capAnchor, capFenceCenter, animal.position());
                    if (!solved.ok) {
                        maybeWarnBadAnchor(player, animal.getUUID(), knotPos, capAnchor, fenceCenterWorld, solved.mode);
                        return;
                    }

                    Ship ship = solved.shipObj;
                    if (ship == null) return;

                    Vec3 anchorWorld = solved.anchorWorld;

                    Vec3 delta = animal.position().subtract(anchorWorld);
                    delta = new Vec3(delta.x, 0.0, delta.z);
                    double dist = delta.length();
                    if (dist < 1.0e-6 || dist > MAX_REASONABLE_DIST) return;

                    Vec3 dir = delta.scale(1.0 / dist);

                    UUID owner = cap.getOwner();
                    ServerControlState.Control ctl = (owner == null)
                            ? null
                            : ServerControlState.getRecentOrCruise(owner, nowTick, 5, CRUISE_TIMEOUT_TICKS);

                    boolean playerControlled = ctl != null;

                    double commandedSpeed = 0.0;
                    double inputMag = 0.0;

                    if (playerControlled) {
                        if (ctl.cruiseEnabled) {
                            inputMag = 1.0;
                            commandedSpeed = CMD_WALK_SPEED * (ctl.cruiseSprint ? CMD_SPRINT_MULT : 1.0);
                        } else {
                            inputMag = Math.sqrt((double) ctl.forward * ctl.forward + (double) ctl.strafe * ctl.strafe);
                            if (inputMag > 1.0) inputMag = 1.0;

                            double mult = ctl.sprint ? CMD_SPRINT_MULT : 1.0;
                            commandedSpeed = CMD_WALK_SPEED * mult * inputMag;
                        }
                    }

                    boolean hasInput = playerControlled && inputMag > INPUT_MAG_EPS;

                    double slack = playerControlled ? PLAYER_SLACK : SLACK;
                    double stretch = dist - slack;

                    Vec3 animalVel = animal.getDeltaMovement();
                    double animalSpeedAlong = animalVel.dot(dir);

                    Vec3 shipVelWorldPerSec = VectorConversionsMCKt.toMinecraft(ship.getVelocity());
                    double shipSpeedAlong = shipVelWorldPerSec.dot(dir) * SEC_PER_TICK;

                    double shipMass = 0;
                    if (ship instanceof ServerShip serverShip) {
                        shipMass = serverShip.getInertiaData().getMass()*4;
                    }

                    if (shipMass <= 0) shipMass = 20_000.0;

                    boolean cruiseEnabled = (ctl != null && ctl.cruiseEnabled);
                    boolean hasDriveIntent = true;//cruiseEnabled || hasInput;

                    // Only brake when there is NO intent to move
                    if (!hasDriveIntent) {
                        // Travel direction: where the ship is currently moving in XZ
                        Vec3 vXZ = new Vec3(shipVelWorldPerSec.x, 0.0, shipVelWorldPerSec.z);
                        double speed = vXZ.length();

                        if (speed > STOP_BRAKE_DEADZONE) {
                            Vec3 travelDir = vXZ.scale(1.0 / speed);

                            // Shaft logic: are we moving toward the animal?
                            Vec3 toAnimal = new Vec3(
                                    animal.position().x - anchorWorld.x,
                                    0.0,
                                    animal.position().z - anchorWorld.z
                            );
                            double gap = toAnimal.length();

                            if (gap > 1.0e-6) {
                                Vec3 toAnimalDir = toAnimal.scale(1.0 / gap);

                                double closing = travelDir.dot(toAnimalDir);

                                if (closing > 0.15) {
                                    double penetration = (SHAFT_LENGTH + SHAFT_SOFT_ZONE) - gap;

                                    if (penetration > 0.0) {
                                        double t = Math.min(1.0, penetration / SHAFT_SOFT_ZONE);
                                        double ramp = t * t;

                                        double vAlong = vXZ.dot(travelDir);
                                        double damp = SHAFT_DAMP * vAlong;

                                        double baseBrake = shipMass * speed * STOP_BRAKE_GAIN;
                                        double shaftExtra = shipMass * (SHAFT_GAIN * ramp) * speed + shipMass * damp;

                                        double targetBrake = Math.min(baseBrake + shaftExtra, SHAFT_MAX_FORCE);

                                        UUID key = animal.getUUID();
                                        double prev = LAST_SHAFT_BRAKE.getOrDefault(key, targetBrake);
                                        double applied = prev + (targetBrake - prev) * 0.65;
                                        LAST_SHAFT_BRAKE.put(key, applied);

                                        VsShipForces.applyWorldForce(ship, travelDir.scale(-applied), null);

                                        LAST_PULL_FORCE.put(key, 0.0);
                                        return;
                                    }
                                }
                            }

                            // Fallback: normal stop brake if not in shaft zone / not closing
                            Vec3 dirBrake = travelDir;
                            double brakeMag = Math.min(shipMass * speed * STOP_BRAKE_GAIN, STOP_MAX_FORCE);
                            VsShipForces.applyWorldForce(ship, dirBrake.scale(-brakeMag), null);
                        }

                        LAST_PULL_FORCE.put(animal.getUUID(), 0.0);
                        return;
                    }

                    double allowedAlong = Math.max(commandedSpeed, Math.max(0.0, animalSpeedAlong)) + VELOCITY_EPS;

                    double hi = allowedAlong + SPEED_HYST;
                    double lo = Math.max(0.0, allowedAlong - SPEED_HYST);

                    UUID key = animal.getUUID();

                    // If ship is going too fast along reins direction, brake
                    if (shipSpeedAlong > hi) {
                        double excess = shipSpeedAlong - allowedAlong;
                        double brakeMag = Math.min(excess * shipMass * BRAKE_GAIN, MAX_FORCE);
                        VsShipForces.applyWorldForce(ship, dir.scale(-brakeMag), null);
                        LAST_PULL_FORCE.put(key, 0.0);
                        return;
                    }

                    // If within hysteresis band, do nothing
                    if (shipSpeedAlong >= lo) {
                        LAST_PULL_FORCE.put(key, 0.0);
                        return;
                    }

                    double minForce = Math.max(1.0, shipMass * MIN_FORCE_MASS_MULT);
                    double targetForce = 0.0;

                    if (playerControlled && (hasInput || ctl.cruiseEnabled)) {
                        double shipTons = shipMass / 1000.0;
                        double intentBase = commandedSpeed * BASE_INTENT_FORCE_PER_TON;
                        double massFactor = Math.pow(shipTons, INTENT_FORCE_MASS_EXPONENT);

                        double intentForce = Math.min(
                                MAX_INTENT_FORCE,
                                Math.max(MIN_INTENT_FORCE, intentBase * massFactor)
                        );

                        double stretchForce = Math.max(0.0, stretch) * SPRING * 6.0;
                        targetForce = Math.max(intentForce + stretchForce, minForce);

                    } else if (stretch > 0.0) {
                        double relVel = animalVel.dot(dir);
                        targetForce = Math.max((stretch * SPRING) + (relVel * DAMPING), 0.0);
                    }

                    double prevForce = LAST_PULL_FORCE.getOrDefault(key, targetForce);
                    double smoothed = prevForce + (targetForce - prevForce) * FORCE_SMOOTHING;

                    double rateLimit = shipMass < 80_000 ? FORCE_RATE_LIMIT_MULT_LIGHT : FORCE_RATE_LIMIT_MULT_HEAVY;
                    double maxDelta = shipMass * rateLimit;
                    double df = Math.max(-maxDelta, Math.min(maxDelta, smoothed - prevForce));

                    double forceMag = Math.min(MAX_FORCE, Math.max(0.0, prevForce + df));
                    LAST_PULL_FORCE.put(key, forceMag);

                    if (forceMag > 0.0) {
                        boolean ok = VsShipForces.applyWorldForce(ship, dir.scale(forceMag), anchorWorld);

                        if (DEBUG && nowTick % 20 == 0) {
                            LOGGER.info("[ReinsMod VS] applyWorldForce ok={} summary={} forceMag={} mode={} dist={}",
                                    ok, VsShipForces.resolutionSummary(), forceMag, solved.mode, dist);
                        }
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------
    // Anchor resolution helpers (ONLY change vs original feel)
    // ------------------------------------------------------------

    private static final class AnchorSolve {
        final boolean ok;
        final Vec3 anchorWorld;
        final Ship shipObj;
        final String mode;

        AnchorSolve(boolean ok, Vec3 anchorWorld, Ship shipObj, String mode) {
            this.ok = ok;
            this.anchorWorld = anchorWorld;
            this.shipObj = shipObj;
            this.mode = mode;
        }
    }

    private static final class BestAnchor {
        Vec3 anchorWorld = null;
        Ship shipObj = null;
        String mode = "none";
        double dist = Double.POSITIVE_INFINITY;
    }

    private static final double HUGE_COORD = 1_000_000.0;

    private static boolean looksNonWorld(Vec3 p) {
        if (p == null) return true;
        return Math.abs(p.x) > HUGE_COORD || Math.abs(p.z) > HUGE_COORD || !Double.isFinite(p.x) || !Double.isFinite(p.z);
    }

    /**
     * Dedicated-server-safe anchor resolution, without changing leash feel:
     *
     * 1) Prefer capability anchor/fence (these are what your mod recorded when leashing)
     * 2) Try shipyard->world transform for knotPos / fenceCenter (dedicated often stores knot in shipyard space)
     * 3) As a last resort, accept raw knotPos/fenceCenter if they look world-ish
     *
     * We pick the candidate with smallest horizontal distance to the animal.
     */
    private static AnchorSolve resolveAnchorWorld(
            ServerLevel level,
            Ship ship0,
            Vec3 knotPos,
            Vec3 fenceCenterWorld,
            Vec3 capAnchorWorld,
            Vec3 capFenceCenterWorld,
            Vec3 animalWorldPos
    ) {
        BestAnchor best = new BestAnchor();

        // (1) Prefer capability stored positions (keeps behavior closest to SP)
        considerAnchor(level, ship0, animalWorldPos, capAnchorWorld, "cap_anchor_world", best);
        considerAnchor(level, ship0, animalWorldPos, capFenceCenterWorld, "cap_fence_center_world", best);

        // (2) Try shipyard->world transform (works when knot/fence are in shipyard space)
        try {
            if (knotPos != null) {
                Vec3 a = VectorConversionsMCKt.toMinecraft(ship0.getShipToWorld().transformPosition(VectorConversionsMCKt.toJOML(knotPos)));
                considerAnchor(level, ship0, animalWorldPos, a, "knot_shipyard_to_world", best);
            }
        } catch (Throwable ignored) {}

        try {
            if (fenceCenterWorld != null) {
                Vec3 a = VectorConversionsMCKt.toMinecraft(ship0.getShipToWorld().transformPosition(VectorConversionsMCKt.toJOML(fenceCenterWorld)));
                considerAnchor(level, ship0, animalWorldPos, a, "fence_shipyard_to_world", best);
            }
        } catch (Throwable ignored) {}

        // (3) Only accept raw positions if they look like real world coords (prevents -28 million anchors)
        if (!looksNonWorld(knotPos)) {
            considerAnchor(level, ship0, animalWorldPos, knotPos, "knot_as_world", best);
        }
        if (!looksNonWorld(fenceCenterWorld)) {
            considerAnchor(level, ship0, animalWorldPos, fenceCenterWorld, "fence_as_world", best);
        }

        if (best.anchorWorld == null) return new AnchorSolve(false, null, ship0, "no_candidate");
        if (!Double.isFinite(best.dist)) return new AnchorSolve(false, null, ship0, "nan_dist");

        if (best.dist > MAX_REASONABLE_DIST) {
            return new AnchorSolve(false, best.anchorWorld, best.shipObj, "all_bad:" + best.mode);
        }

        return new AnchorSolve(true, best.anchorWorld, best.shipObj, best.mode);
    }

    private static void considerAnchor(ServerLevel level, Ship fallbackShip, Vec3 animalWorldPos,
                                       Vec3 anchorWorld, String mode, BestAnchor best) {
        if (anchorWorld == null) return;

        Ship shipHere = VSGameUtilsKt.getShipManagingPos(level, anchorWorld);
        if (shipHere == null) {
            shipHere = fallbackShip;
        }

        Vec3 d = animalWorldPos.subtract(anchorWorld);
        d = new Vec3(d.x, 0.0, d.z);
        double dist = d.length();
        if (!Double.isFinite(dist)) return;

        if (dist < best.dist) {
            best.dist = dist;
            best.anchorWorld = anchorWorld;
            best.shipObj = shipHere;
            best.mode = mode;
        }
    }

    private static void maybeWarnBadAnchor(ServerPlayer p, UUID animalId, Vec3 knotPos, Vec3 capAnchor, Vec3 fenceCenter, String reason) {
        int now = p.tickCount;
        int last = LAST_BAD_ANCHOR_TICK.getOrDefault(animalId, -999999);
        if (now - last < 40) return;

        LAST_BAD_ANCHOR_TICK.put(animalId, now);

        if (DEBUG) {
            LOGGER.warn("[ReinsMod] bad_anchor animal={} knotPos={} capAnchor={} fenceCenter={} reason={}",
                    animalId, fmt(knotPos), fmt(capAnchor), fmt(fenceCenter), reason);
        }
    }

    @SuppressWarnings("unused")
    private static void cleanup(Animal animal) {
        UUID id = animal.getUUID();
        LAST_PULL_FORCE.remove(id);
        LAST_BAD_ANCHOR_TICK.remove(id);
        animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {
            cap.setLeashedToShip(false);
            cap.setShipFencePos(null);
            cap.setShipAnchorPos(null);
        });
    }

    private static String fmt(Vec3 v) {
        if (v == null) return "null";
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}