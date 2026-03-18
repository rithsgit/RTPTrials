package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

public class RTPRClient implements ClientModInitializer {

    // -1 means no active countdown — HUD is hidden.
    private static volatile int timerSeconds = -1;

    // Tracks the last value we received so we can detect a transition to 0
    // (teleport fired) and play the completion sound exactly once.
    private static volatile int lastTimerSeconds = -1;

    @Override
    public void onInitializeClient() {
        RTPR.LOGGER.info("RTPRClient onInitializeClient called!");

        // Receive countdown ticks from the server.
        ClientPlayNetworking.registerGlobalReceiver(RTPRServer.TimerPayload.TYPE, (payload, context) -> {
            RTPR.LOGGER.info("Client received timer packet: {}", payload.timeLeft());
            context.client().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) return;

                int newTime = payload.timeLeft();
                lastTimerSeconds = timerSeconds;
                timerSeconds = newTime;

                // Teleport just fired — play the completion sound once.
                if (newTime == 0 && lastTimerSeconds > 0) {
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(
                            SoundEvents.ENDERMAN_TELEPORT, 1.0f, 1.0f));
                    // Hide the HUD immediately after teleport.
                    timerSeconds = -1;
                    return;
                }

                // Pling sound during countdown — pitch rises as time runs out.
                if (newTime > 0 && newTime <= 5) {
                    float pitch = 0.8f + ((5 - newTime) * 0.1f); // 0.8 → 1.2 over 5 ticks
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(
                            SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, pitch));
                }
            });
        });

        // Reset timer state cleanly when the player disconnects so stale
        // HUD data doesn't linger into the next session.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    timerSeconds     = -1;
                    lastTimerSeconds = -1;
                    RTPR.LOGGER.info("RTPRClient: reset timer on disconnect.");
                });

        // HUD overlay -- renders a centred countdown box at the top of the screen.
        HudLayerRegistrationCallback.EVENT.register(layeredDrawer ->
            layeredDrawer.attachLayerAfter(
                IdentifiedLayer.MISC_OVERLAYS,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("rtpr", "countdown_hud"),
                (drawContext, tickDelta) -> {
                    if (timerSeconds < 1) return;

                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return;

                    // Colour shifts from white -> yellow -> orange -> red as time runs out.
                    int color;
                    if (timerSeconds <= 5)       color = 0xFFFF3333; // red
                    else if (timerSeconds <= 10) color = 0xFFFFAA00; // orange
                    else if (timerSeconds <= 30) color = 0xFFFFFF55; // yellow
                    else                         color = 0xFFFFFFFF; // white

                    String text = "Teleport in: " + timerSeconds + "s";

                    int screenWidth = mc.getWindow().getGuiScaledWidth();
                    int textWidth   = mc.font.width(text);
                    int x           = screenWidth / 2 - textWidth / 2;
                    int y           = 10;
                    int padding     = 3;

                    drawContext.pose().pushPose();
                    drawContext.pose().translate(0, 0, 200);
                    drawContext.fill(
                        x - padding,
                        y - padding,
                        x + textWidth + padding,
                        y + mc.font.lineHeight + padding,
                        0xAA000000
                    );
                    drawContext.drawString(mc.font, text, x, y, color, true);
                    drawContext.pose().popPose();
                }
            )
        );
    }
}