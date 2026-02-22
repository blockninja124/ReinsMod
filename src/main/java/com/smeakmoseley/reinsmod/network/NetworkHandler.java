package com.smeakmoseley.reinsmod.network;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.control.AnimalControlLogic;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ReinsMod.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    public static void init() {
        CHANNEL.registerMessage(
                0,
                AnimalControlInputPacket.class,
                AnimalControlInputPacket::encode,
                AnimalControlInputPacket::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        if (ctx.get().getSender() != null) {
                            AnimalControlLogic.handleInput(
                                    ctx.get().getSender(),
                                    msg
                            );
                        }
                    });
                    ctx.get().setPacketHandled(true);
                }
        );
        CHANNEL.registerMessage(
                1,
                CruiseControlPacket.class,
                CruiseControlPacket::encode,
                CruiseControlPacket::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        if (ctx.get().getSender() != null) {
                            com.smeakmoseley.reinsmod.control.CruiseControlLogic.handle(
                                    ctx.get().getSender(), msg
                            );
                        }
                    });
                    ctx.get().setPacketHandled(true);
                }
        );
    }
}
