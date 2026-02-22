package com.smeakmoseley.reinsmod;

import com.mojang.logging.LogUtils;
import com.smeakmoseley.reinsmod.item.ModItems;
import com.smeakmoseley.reinsmod.network.NetworkHandler;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(ReinsMod.MODID)
public class ReinsMod {

    public static final String MODID = "reinsmod";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ReinsMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModItems.register(modEventBus);
        NetworkHandler.init();

        // ✅ Only attempt client bootstrap on client, and do it via reflection so the server never links client classes.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                Class.forName("com.smeakmoseley.reinsmod.client.ClientBootstrap")
                        .getMethod("init")
                        .invoke(null);
                LOGGER.info("[ReinsMod] ClientBootstrap initialized");
            } catch (Throwable t) {
                LOGGER.error("[ReinsMod] Failed to init client bootstrap", t);
            }
        }

        LOGGER.info("[ReinsMod] Loaded");
    }
}