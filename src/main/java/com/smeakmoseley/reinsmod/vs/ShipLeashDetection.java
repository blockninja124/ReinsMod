package com.smeakmoseley.reinsmod.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.phys.Vec3;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Optional;

public class ShipLeashDetection {

    public static Optional<ShipLeashInfo> detectFenceOnShip(Animal animal) {
        if (!(animal.level() instanceof ServerLevel level)) return Optional.empty();

        Entity holder = animal.getLeashHolder();
        if (!(holder instanceof LeashFenceKnotEntity knot)) return Optional.empty();

        BlockPos fencePos = knot.blockPosition();
        Vec3 knotPosRaw = knot.position();

        // Try both: knot pos and fence center
        Ship ship = VSGameUtilsKt.getShipManagingPos(level, knotPosRaw);
        if (ship == null) {
            ship = VSGameUtilsKt.getShipManagingPos(level, knotPosRaw);
        }
        if (ship == null) return Optional.empty();

        // IMPORTANT: store raw; later code already tries both interpretations
        return Optional.of(new ShipLeashInfo(animal, fencePos, knotPosRaw));
    }
}