package com.smeakmoseley.reinsmod.vs;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.util.GameToPhysicsAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class VsShipForces {

    private static volatile String SUMMARY = "unresolved";

    // Forces smaller than this are treated as "no-op"
    private static final double FORCE_EPS_SQR = 1.0e-6;

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
    public static boolean applyWorldForce(Ship shipObj, Vec3 forceWorld, Vec3 worldPos) {
        if (!(shipObj instanceof ServerShip serverShip) || forceWorld == null) return false;

        if (forceWorld.lengthSqr() <= FORCE_EPS_SQR) {
            SUMMARY = "skipped_zero_force";
            return false;
        }

        long shipId = shipObj.getId();
        String dimObj = shipObj.getChunkClaimDimension();

        GameToPhysicsAdapter gtpa = ValkyrienSkiesMod.getOrCreateGTPA(dimObj);

        serverShip.setStatic(false);

        Vector3d f = new Vector3d(forceWorld.x, forceWorld.y, forceWorld.z);
        Vector3d p = (worldPos == null) ? null : new Vector3d(worldPos.x, worldPos.y, worldPos.z);

        gtpa.applyWorldForce(shipId, f, p);

        return true;
    }
}