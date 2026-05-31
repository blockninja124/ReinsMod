package com.smeakmoseley.reinsmod;

import com.mojang.logging.LogUtils;
import com.smeakmoseley.reinsmod.client.ClientBootstrap;
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

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientBootstrap.init();
        }

        LOGGER.info("[ReinsMod] Loaded");
    }
}