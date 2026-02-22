package com.smeakmoseley.reinsmod.client;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public final class ClientWhipMovementLock {

    private ClientWhipMovementLock() {}

    public static void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (!player.getMainHandItem().is(ModItems.WHIP.get())) return;

        // Zero movement inputs
        event.getInput().leftImpulse = 0.0f;    // A/D
        event.getInput().forwardImpulse = 0.0f; // W/S
        event.getInput().jumping = false;       // spacebar

        // IMPORTANT: do NOT touch shiftKeyDown (crouch) so shifting still works.
    }
}