package com.smeakmoseley.reinsmod.client;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.item.ModItems;
import com.smeakmoseley.reinsmod.network.AnimalControlInputPacket;
import com.smeakmoseley.reinsmod.network.CruiseControlPacket;
import com.smeakmoseley.reinsmod.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

public final class ClientInputEvents {

    private static boolean wasUseDown = false;

    private ClientInputEvents() {}

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        boolean holdingWhip = player.getMainHandItem().is(ModItems.WHIP.get());
        if (!holdingWhip) {
            ClientSprintIntent.reset();
            wasUseDown = mc.options.keyUse.isDown();
            return;
        }

        ClientSprintIntent.tick();

        boolean useDown = mc.options.keyUse.isDown();
        boolean usePressedThisTick = useDown && !wasUseDown;
        wasUseDown = useDown;

        boolean wDown = mc.options.keyUp.isDown();
        if (usePressedThisTick && wDown) {
            boolean sprint = ClientSprintIntent.get();
            NetworkHandler.CHANNEL.sendToServer(new CruiseControlPacket(true, sprint));
        }

        float forward = 0f;
        if (mc.options.keyUp.isDown()) forward += 1f;
        if (mc.options.keyDown.isDown()) forward -= 1f;

        float strafe = 0f;
        if (mc.options.keyLeft.isDown())  strafe -= 1f;
        if (mc.options.keyRight.isDown()) strafe += 1f;

        boolean sprint = ClientSprintIntent.get();
        boolean jump = mc.options.keyJump.isDown();

        float yaw = computeYawFromCameraLook(mc);

        NetworkHandler.CHANNEL.sendToServer(
                new AnimalControlInputPacket(forward, strafe, yaw, sprint, jump)
        );
    }

    private static float computeYawFromCameraLook(Minecraft mc) {
        Vector3f look = mc.gameRenderer.getMainCamera().getLookVector();
        float lx = look.x();
        float lz = look.z();
        float xzLen2 = lx * lx + lz * lz;
        if (xzLen2 < 1.0e-6f) {
            return mc.gameRenderer.getMainCamera().getYRot();
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-lx, lz));
        return Mth.wrapDegrees(yaw);
    }
}