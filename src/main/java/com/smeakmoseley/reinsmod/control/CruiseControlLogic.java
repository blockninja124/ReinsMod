package com.smeakmoseley.reinsmod.control;

import com.smeakmoseley.reinsmod.capability.reined.IReinedAnimal;
import com.smeakmoseley.reinsmod.capability.reined.ReinedAnimalProvider;
import com.smeakmoseley.reinsmod.network.CruiseControlPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class CruiseControlLogic {
    private CruiseControlLogic() {}

    private static final double CRUISE_SCAN_RADIUS = 48.0;

    public static void handle(ServerPlayer player, CruiseControlPacket msg) {
        UUID id = player.getUUID();
        int tick = player.serverLevel().getServer().getTickCount();

        ServerControlState.Control c = ServerControlState.get(id);

        // Ensure control exists
        if (c == null) {
            ServerControlState.update(
                    id,
                    0, 0,
                    player.getYRot(),
                    false,
                    false,
                    tick
            );
            c = ServerControlState.get(id);
            if (c == null) return;
        }

        if (!msg.toggle) return;

        // From here on, use a truly-final reference
        final ServerControlState.Control control = c;

        control.cruiseEnabled = !control.cruiseEnabled;

        if (control.cruiseEnabled) {
            control.cruiseSprint = msg.sprint;
            control.cruiseSetTick = tick;

            // final set: clear + refill (no reassignment)
            control.cruiseAnimalIds.clear();

            // Snapshot nearby animals (avoid concurrent modification surprises)
            Set<Animal> animals = new HashSet<>(
                    player.serverLevel().getEntitiesOfClass(
                            Animal.class,
                            player.getBoundingBox().inflate(CRUISE_SCAN_RADIUS)
                    )
            );

            for (Animal animal : animals) {
                IReinedAnimal cap = animal.getCapability(ReinedAnimalProvider.CAPABILITY).orElse(null);
                if (cap == null) continue;

                if (!cap.hasReins()) continue;
                if (!id.equals(cap.getOwner())) continue;

                control.cruiseAnimalIds.add(animal.getUUID());
            }

            player.sendSystemMessage(
                    Component.literal("§aAnimals put in automatic travel mode"
                            + (control.cruiseSprint ? " §7(sprinting)" : " §7(walking)"))
            );

        } else {
            control.cruiseAnimalIds.clear();

            player.sendSystemMessage(
                    Component.literal("§cAnimals taken out of automatic travel mode")
            );
        }
    }
}
