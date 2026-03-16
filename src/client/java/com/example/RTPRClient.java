package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

public class RTPRClient implements ClientModInitializer {

    private static volatile int timerSeconds = -1;

    @Override
    public void onInitializeClient() {
        RTPR.LOGGER.info("RTPRClient onInitializeClient called!");

        ClientPlayNetworking.registerGlobalReceiver(RTPRServer.TimerPayload.TYPE, (payload, context) -> {
            RTPR.LOGGER.info("Client received timer packet: {}", payload.timeLeft());
            context.client().execute(() -> {
                int newTime = payload.timeLeft();
                timerSeconds = newTime;

                Minecraft mc = Minecraft.getInstance();

                if (newTime == -1) {
                    if (mc.player != null) {
                        mc.getSoundManager().play(SimpleSoundInstance.forUI(
                            SoundEvents.ENDERMAN_TELEPORT, 1.0f, 1.0f));
                    }
                } else if (newTime <= 30 && newTime > 0 && mc.player != null) {
                    float pitch = newTime <= 10 ? 1.5f : 1.0f;
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, pitch));
                }
            });
        });

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (timerSeconds < 1) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            int color = timerSeconds <= 30 ? 0xFFFF3333 : 0xFFFFFFFF;
            String text = "Teleport in: " + timerSeconds + "s";

            int screenWidth = mc.getWindow().getGuiScaledWidth();
            int x = screenWidth / 2 - mc.font.width(text) / 2;
            int y = 10;

            drawContext.pose().pushPose();
            drawContext.pose().translate(0, 0, 999);
            drawContext.fill(
                x - 3, y - 3,
                x + mc.font.width(text) + 3,
                y + mc.font.lineHeight + 3,
                0xFF000000
            );
            drawContext.drawString(mc.font, text, x, y, color, false);
            drawContext.pose().popPose();
        });
    }
}