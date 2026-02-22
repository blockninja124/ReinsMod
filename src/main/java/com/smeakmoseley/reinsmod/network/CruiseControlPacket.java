package com.smeakmoseley.reinsmod.network;

import net.minecraft.network.FriendlyByteBuf;

public class CruiseControlPacket {
    public final boolean toggle;
    public final boolean sprint;

    public CruiseControlPacket(boolean toggle, boolean sprint) {
        this.toggle = toggle;
        this.sprint = sprint;
    }

    public static void encode(CruiseControlPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.toggle);
        buf.writeBoolean(msg.sprint);
    }

    public static CruiseControlPacket decode(FriendlyByteBuf buf) {
        return new CruiseControlPacket(buf.readBoolean(), buf.readBoolean());
    }
}
