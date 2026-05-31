package com.smeakmoseley.reinsmod.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.phys.Vec3;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

public final class ShipRopeConstraint {

    private static final double ROPE_LEN = 2.5;
    private static final double EPS = 1.0e-4;

    private static final double MAX_REASONABLE_DIST = 256.0;

    private ShipRopeConstraint() {}

    /**
     * Rigid rein: clamp the animal's move so that after moving,
     * horizontal distance to anchorWorld <= ROPE_LEN.
     *
     * IMPORTANT:
     *  - In survival, LeashFenceKnotEntity.position() is often WORLD space.
     *  - In some VS contexts, you may see shipyard-managed coordinates.
     *  - So we try BOTH interpretations and pick the best.
     *
     * @param fencePos  the fence block that owns the knot (selects correct knot)
     * @param anchorRaw cached anchor as stored (may be shipyard OR world)
     */
    public static Vec3 applyRigid(ServerLevel level,
                                  Animal animal,
                                  BlockPos fencePos,
                                  Vec3 anchorRaw,
                                  Vec3 desiredMove) {
        if (level == null || animal == null || fencePos == null || anchorRaw == null || desiredMove == null) {
            return desiredMove;
        }

        // Find the knot for THIS fence pos (avoid “wrong knot” when multiple exist)
        LeashFenceKnotEntity knot = level.getEntitiesOfClass(
                LeashFenceKnotEntity.class,
                animal.getBoundingBox().inflate(64)
        ).stream()
                .filter(k -> k.blockPosition().equals(fencePos))
                .findFirst()
                .orElse(null);

        if (knot == null) return desiredMove;

        // We use the knot position to locate the ship (this is world position)
        Vec3 knotWorldPos = knot.position();

        Ship ship0 = VSGameUtilsKt.getShipManagingPos(level, knotWorldPos);
        if (ship0 == null) return desiredMove;

        // Pick a sane anchorWorld using best-candidate logic
        Vec3 anchorWorld = chooseBestAnchorWorld(level, ship0, animal, anchorRaw);
        if (anchorWorld == null) return desiredMove;

        Vec3 pos0 = animal.position();
        Vec3 pos1 = pos0.add(desiredMove);

        // Horizontal constraint only
        Vec3 d = pos1.subtract(anchorWorld);
        d = new Vec3(d.x, 0.0, d.z);

        double dist = d.length();
        if (dist < EPS) return desiredMove;
        if (dist > MAX_REASONABLE_DIST) return desiredMove; // sanity
        if (dist <= ROPE_LEN + EPS) return desiredMove;

        Vec3 dir = d.scale(1.0 / dist);

        Vec3 clampedPos1 = new Vec3(
                anchorWorld.x + dir.x * ROPE_LEN,
                pos1.y,
                anchorWorld.z + dir.z * ROPE_LEN
        );

        return clampedPos1.subtract(pos0);
    }

    /**
     * Try interpreting anchorRaw as:
     *  A) shipyard-managed (transform to world)
     *  B) already world
     *
     * Choose the candidate with smallest horizontal distance to the animal
     * that is also "reasonable".
     */
    private static Vec3 chooseBestAnchorWorld(ServerLevel level, Ship fallbackShip, Animal animal, Vec3 anchorRaw) {
        Vec3 animalPos = animal.position();

        // Candidate A: treat as shipyard -> world
        Vec3 a = VSGameUtilsKt.toWorldCoordinates(fallbackShip, anchorRaw);

        // Candidate B: treat as already world
        Vec3 b = anchorRaw;

        Vec3 best = null;
        double bestDist = Double.POSITIVE_INFINITY;

        bestDist = considerCandidate(level, fallbackShip, animalPos, a, bestDist);
        if (bestDist != Double.POSITIVE_INFINITY) best = a;

        double bd = considerCandidate(level, fallbackShip, animalPos, b, bestDist);
        if (bd < bestDist) {
            bestDist = bd;
            best = b;
        }

        if (best == null) return null;
        if (!Double.isFinite(bestDist)) return null;
        if (bestDist > MAX_REASONABLE_DIST) return null;

        return best;
    }

    private static double considerCandidate(ServerLevel level, Object fallbackShip, Vec3 animalPos, Vec3 anchorWorld, double currentBest) {
        if (anchorWorld == null) return currentBest;

        // Ensure the anchorWorld is actually on/near a ship-managed region; if not, still allow fallbackShip
        Ship shipHere = VSGameUtilsKt.getShipManagingPos(level, anchorWorld);
        if (shipHere == null) return currentBest;

        Vec3 d = animalPos.subtract(anchorWorld);
        d = new Vec3(d.x, 0.0, d.z);
        double dist = d.length();
        if (!Double.isFinite(dist)) return currentBest;

        return Math.min(currentBest, dist);
    }
}
