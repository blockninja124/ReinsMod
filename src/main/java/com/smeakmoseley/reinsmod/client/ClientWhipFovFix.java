package com.smeakmoseley.reinsmod.client;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public final class ClientWhipFovFix {

    private static int graceTicks = 0;
    private static final int GRACE_TICKS_ON_RELEASE = 5;

    private ClientWhipFovFix() {}

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            graceTicks = 0;
            return;
        }

        boolean holdingWhip = player.getMainHandItem().is(ModItems.WHIP.get());
        if (holdingWhip) {
            graceTicks = GRACE_TICKS_ON_RELEASE;
        } else if (graceTicks > 0) {
            graceTicks--;
        }
    }

    public static void onFovModifier(ComputeFovModifierEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        boolean holdingWhip = player.getMainHandItem().is(ModItems.WHIP.get());
        if (holdingWhip || graceTicks > 0) {
            event.setNewFovModifier(1.0f);
        }
    }
}