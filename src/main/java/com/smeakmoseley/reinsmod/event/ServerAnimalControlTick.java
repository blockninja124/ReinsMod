package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.capability.reined.ReinedAnimalProvider;
import com.smeakmoseley.reinsmod.control.ServerControlState;
import com.smeakmoseley.reinsmod.item.ModItems;
import com.smeakmoseley.reinsmod.vs.ShipLeashDetection;
import com.smeakmoseley.reinsmod.vs.ShipLeashInfo;
import com.smeakmoseley.reinsmod.vs.ShipRopeConstraint;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = ReinsMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerAnimalControlTick {

    // =====================
    // Movement tuning
    // =====================
    private static final float WALK_SPEED = 0.20f;
    private static final float SPRINT_MULT = 1.80f;

    private static final float CONTROL_STEP = 1.0f;
    private static final float MAX_TURN_DEG_PER_TICK = 18.0f;

    // =====================
    // Cruise control
    // =====================
    private static final int CRUISE_TIMEOUT_TICKS = 20 * 60; // 60s safety

    // =====================
    // Scans
    // =====================
    private static final double NEAR_SCAN_RADIUS = 48.0;
    private static final int CRUISE_REFRESH_EVERY_TICKS = 20; // add nearby animals once/sec while cruising

    // =====================
    // Whip warning cooldown (avoid spamming if VS rope detection fails)
    // =====================
    private static final Map<UUID, Boolean> WHIP_WARNED_NO_SHIP = new ConcurrentHashMap<>();

    // =====================
    // Ship leash grace
    // =====================
    private static final int SHIP_LEASH_GRACE_TICKS = 10;
    private static final Map<UUID, Integer> SHIP_LEASH_GRACE = new ConcurrentHashMap<>();

    public final Set<UUID> cruiseAnimalIds = new HashSet<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        int nowTick = event.getServer().getTickCount();

        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer player : level.players()) {

                boolean holdingWhip = player.getMainHandItem().is(ModItems.WHIP.get());

                // If not holding whip, we only keep control if cruise is enabled (automatic mode).
                if (!holdingWhip) {
                    ServerControlState.Control c = ServerControlState.get(player.getUUID());
                    if (c == null || !c.cruiseEnabled) {
                        ServerControlState.clear(player.getUUID());
                        continue;
                    }
                }

                ServerControlState.Control control =
                        ServerControlState.getRecentOrCruise(
                                player.getUUID(),
                                nowTick,
                                5,
                                CRUISE_TIMEOUT_TICKS
                        );

                if (control == null) continue;

                // If cruise timed out naturally, clear it
                if (control.cruiseEnabled && (nowTick - control.cruiseSetTick) > CRUISE_TIMEOUT_TICKS) {
                    control.cruiseEnabled = false;
                    control.cruiseAnimalIds.clear();
                    player.sendSystemMessage(Component.literal("§cAnimals taken out of automatic travel mode §8(timeout)"));
                    continue;
                }

                // ---------------------------------------------------------
                // 1) Cruise: drive cached animals by UUID (distance independent)
                // ---------------------------------------------------------
                if (control.cruiseEnabled && !control.cruiseAnimalIds.isEmpty()) {
                    // Copy to avoid CME if we remove while iterating
                    List<UUID> ids = new ArrayList<>(control.cruiseAnimalIds);

                    for (UUID aid : ids) {
                        Entity e = player.serverLevel().getEntity(aid);
                        if (!(e instanceof Animal animal)) {
                            control.cruiseAnimalIds.remove(aid);
                            continue;
                        }

                        // Ensure still owned+reined
                        boolean ok = animal.getCapability(ReinedAnimalProvider.CAPABILITY)
                                .map(cap -> cap.hasReins() && player.getUUID().equals(cap.getOwner()))
                                .orElse(false);

                        if (!ok) {
                            control.cruiseAnimalIds.remove(aid);
                            continue;
                        }

                        applyControlToAnimal(player, level, animal, control);
                    }
                }

                // ---------------------------------------------------------
                // 2) Near scan: also control nearby animals (manual + cruise),
                //    and while cruising, "pick up" new nearby animals.
                // ---------------------------------------------------------
                boolean doRefresh = control.cruiseEnabled && (nowTick % CRUISE_REFRESH_EVERY_TICKS == 0);

                level.getEntitiesOfClass(
                        Animal.class,
                        player.getBoundingBox().inflate(NEAR_SCAN_RADIUS)
                ).forEach(animal -> {
                    animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {
                        if (!cap.hasReins()) return;
                        if (!player.getUUID().equals(cap.getOwner())) return;

                        if (doRefresh) {
                            control.cruiseAnimalIds.add(animal.getUUID());
                        }

                        applyControlToAnimal(player, level, animal, control);
                    });
                });
            }
        }
    }

    private static void applyControlToAnimal(ServerPlayer player, ServerLevel level, Animal animal, ServerControlState.Control control) {
        animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {

            UUID id = animal.getUUID();

            // ---------------------------------------------------------
            // Ship leash detection (eventual consistency)
            // ---------------------------------------------------------
            if (animal.isLeashed()) {
                Optional<ShipLeashInfo> infoOpt = ShipLeashDetection.detectFenceOnShip(animal);

                if (infoOpt.isPresent()) {
                    ShipLeashInfo info = infoOpt.get();
                    cap.setLeashedToShip(true);
                    cap.setShipFencePos(info.fencePos);
                    cap.setShipAnchorPos(info.anchorPos);
                    SHIP_LEASH_GRACE.remove(id);
                } else {
                    int grace = SHIP_LEASH_GRACE.getOrDefault(id, 0);
                    if (grace < SHIP_LEASH_GRACE_TICKS) {
                        SHIP_LEASH_GRACE.put(id, grace + 1);
                        // Don't bail out of control; just treat ship leash as not confirmed yet.
                        cap.setLeashedToShip(false);
                        cap.setShipFencePos(null);
                        cap.setShipAnchorPos(null);
                    } else {
                        cap.setLeashedToShip(false);
                        cap.setShipFencePos(null);
                        cap.setShipAnchorPos(null);
                        SHIP_LEASH_GRACE.remove(id);
                    }
                    cap.setLeashedToShip(false);
                    cap.setShipFencePos(null);
                    cap.setShipAnchorPos(null);
                    SHIP_LEASH_GRACE.remove(id);
                }
            } else {
                cap.setLeashedToShip(false);
                cap.setShipFencePos(null);
                cap.setShipAnchorPos(null);
                SHIP_LEASH_GRACE.remove(id);
            }

            // ---------------------------------------------------------
            // AI suppression (keeps heading stable during cruise)
            // ---------------------------------------------------------
            Mob mob = (Mob) animal;
            mob.setTarget(null);
            mob.getNavigation().stop();


            // ---------------------------------------------------------
            // Step assist
            // ---------------------------------------------------------
            animal.setMaxUpStep(CONTROL_STEP);

            // ---------------------------------------------------------
            // Yaw control (camera-driven) - compute once and keep newYaw
            // ---------------------------------------------------------
            float targetYaw = control.yaw;
            float currentYaw = animal.getYRot();

            float deltaYaw = Mth.wrapDegrees(targetYaw - currentYaw);
            deltaYaw = Mth.clamp(deltaYaw, -MAX_TURN_DEG_PER_TICK, MAX_TURN_DEG_PER_TICK);

            // newYaw is computed once and reused after movement
            float newYaw = currentYaw + deltaYaw;
            animal.setYRot(newYaw);
            animal.setYHeadRot(newYaw);
            try { animal.yBodyRot = newYaw; } catch (Throwable ignored) {}


            // ---------------------------------------------------------
            // Effective inputs (CRUISE OVERRIDE)
            // ---------------------------------------------------------
            float effForward = control.forward;
            float effStrafe  = control.strafe;
            boolean effSprint = control.sprint;

            if (control.cruiseEnabled) {
                effForward = 1.0f;
                effStrafe  = 0.0f;
                effSprint  = control.cruiseSprint;
            }

            // ---------------------------------------------------------
            // Direction vectors
            // ---------------------------------------------------------
            float yawRad = (float) Math.toRadians(animal.getYRot());

            Vec3 forward = new Vec3(
                    -Math.sin(yawRad),
                    0,
                    Math.cos(yawRad)
            );

            Vec3 right = new Vec3(
                    -forward.z,
                    0,
                    forward.x
            );

            float speed = WALK_SPEED * (effSprint ? SPRINT_MULT : 1.0f);

            Vec3 moveXZ = forward.scale(effForward)
                    .add(right.scale(effStrafe))
                    .scale(speed);

            // ---------------------------------------------------------
            // Ship rope constraint
            // ---------------------------------------------------------
            if (cap.isLeashedToShip()) {
                Vec3 anchorShipyard = cap.getShipAnchorPos();
                BlockPos fencePos = cap.getShipFencePos();
                if (anchorShipyard != null && fencePos != null) {
                    moveXZ = ShipRopeConstraint.applyRigid(
                            level,
                            animal,
                            fencePos,
                            anchorShipyard,
                            moveXZ
                    );
                }
            }

            // ---------------------------------------------------------
            // Apply motion
            // ---------------------------------------------------------
            Vec3 dm = animal.getDeltaMovement();
            Vec3 move = new Vec3(moveXZ.x, dm.y, moveXZ.z);

            animal.setDeltaMovement(move);
            animal.move(MoverType.SELF, move);
            animal.hurtMarked = true;

            // ---------------------------------------------------------
            // Re-assert yaw after move to prevent physics/VS from overwriting it.
            // We already computed 'newYaw' above, reuse it here.
            // ---------------------------------------------------------
            animal.setYRot(newYaw);
            animal.setYHeadRot(newYaw);
            try { animal.yBodyRot = newYaw; } catch (Throwable ignored) {}
        });
    }
}
