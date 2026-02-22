package com.smeakmoseley.reinsmod.control;

import com.smeakmoseley.reinsmod.network.AnimalControlInputPacket;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class AnimalControlLogic {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final boolean DEBUG = false;

    public static void handleInput(ServerPlayer player, AnimalControlInputPacket msg) {
        if (DEBUG) {
            LOGGER.info("[ReinsMod] Received AnimalControlInput from {} forward={} strafe={} yaw={}",
                player.getName().getString(), msg.forward, msg.strafe, msg.yaw);
        }
        int tick = player.serverLevel().getServer().getTickCount();
        ServerControlState.update(
                player.getUUID(),
                msg.forward,
                msg.strafe,
                msg.yaw,
                msg.sprint,
                msg.jump,
                tick
        );
    }
}
