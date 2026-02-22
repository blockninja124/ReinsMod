package com.smeakmoseley.reinsmod.client;

import net.minecraftforge.common.MinecraftForge;

public final class ClientBootstrap {
    private ClientBootstrap() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(ClientInputEvents::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ClientWhipMovementLock::onMovementInput);
        MinecraftForge.EVENT_BUS.addListener(ClientWhipFovFix::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ClientWhipFovFix::onFovModifier);
    }
}