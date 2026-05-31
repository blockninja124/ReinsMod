package com.smeakmoseley.reinsmod.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.phys.Vec3;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

public final class ShipPullPhysics {

    private static final double SLACK = 3.5;
    private static final double SPRING = 80.0;
    private static final double DAMPING = 18.0;
    private static final double MAX_FORCE = 2500.0;

    private static final double MAX_REASONABLE_DIST = 64.0;

    private ShipPullPhysics() {}

    public static void tickLeashPull(ServerLevel level, Animal animal, BlockPos fencePos) {

        LeashFenceKnotEntity knot = level.getEntitiesOfClass(
                LeashFenceKnotEntity.class,
                animal.getBoundingBox().inflate(64)
        ).stream()
                .filter(k -> k.blockPosition().equals(fencePos))
                .findFirst()
                .orElse(null);

        if (knot == null) return;

        Vec3 anchorShipyard = knot.position();

        Ship ship = VSGameUtilsKt.getShipManagingPos(level, anchorShipyard);
        if (ship == null) return;

        Vec3 anchorWorld = VSGameUtilsKt.toWorldCoordinates(ship, anchorShipyard);

        Vec3 delta = animal.position().subtract(anchorWorld);
        delta = new Vec3(delta.x, 0, delta.z);

        double dist = delta.length();
        if (dist > MAX_REASONABLE_DIST || dist < 1.0e-6) return;
        if (dist <= SLACK) return;

        Vec3 dir = delta.normalize();
        double stretch = dist - SLACK;

        double relVel = animal.getDeltaMovement().dot(dir);

        double forceMag = (stretch * SPRING) + (relVel * DAMPING);
        forceMag = Math.max(0, Math.min(MAX_FORCE, forceMag));

        Vec3 force = dir.scale(forceMag);

        VsShipForces.applyWorldForce(ship, force, null);
    }
}
