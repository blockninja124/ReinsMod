package com.smeakmoseley.reinsmod.item;

import com.smeakmoseley.reinsmod.capability.controller.AnimalControllerProvider;
import com.smeakmoseley.reinsmod.control.ServerControlState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class WhipItem extends Item {

    public WhipItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide) return;
        if (!(entity instanceof Player player)) return;

        player.getCapability(AnimalControllerProvider.CAPABILITY).ifPresent(cap -> {

            boolean wasControlling = cap.isControlling();

            // Update controlling state
            if (wasControlling != selected) {
                cap.setControlling(selected);

                // ✅ If we just EQUIPPED the whip, auto-disable cruise
                if (!wasControlling && selected) {
                    if (ServerControlState.isCruising(player.getUUID())) {
                        ServerControlState.disableCruise(player.getUUID());
                        player.sendSystemMessage(
                                Component.literal("§cAnimals taken out of automatic travel mode")
                        );
                    }
                }
            }
        });
    }
}
