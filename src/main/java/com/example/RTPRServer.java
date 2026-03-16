package com.example;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;

import java.lang.reflect.Field;

public class RTPRServer {

    // Spawn cap adjustments applied once on server start by mutating the
    // MobCategory enum's maxInstancesPerChunk field via reflection.
    // Hostile mobs (MONSTER): vanilla default 70, RTPR raises by 10 → 80.
    // Passive mobs (CREATURE): vanilla default 10, RTPR raises by 2  → 12.
    private static final int MONSTER_SPAWN_BONUS = 10;
    private static final int PASSIVE_SPAWN_BONUS = 2;

    public record TimerPayload(int timeLeft) implements CustomPacketPayload {
        public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("rtpr", "timer_sync");
        public static final CustomPacketPayload.Type<TimerPayload> TYPE = new CustomPacketPayload.Type<>(ID);
        public static final StreamCodec<FriendlyByteBuf, TimerPayload> CODEC =
                StreamCodec.of(
                        (buf, payload) -> buf.writeInt(payload.timeLeft()),
                        buf -> new TimerPayload(buf.readInt())
                );

        @Override
        public CustomPacketPayload.Type<TimerPayload> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TimerPayload.TYPE, TimerPayload.CODEC);
    }

    public static void sendTimerSync(ServerPlayer player, int timeLeft) {
        RTPR.LOGGER.info("Sending packet to client: {}", timeLeft);
        ServerPlayNetworking.send(player, new TimerPayload(timeLeft));
    }

    /**
     * Applies RTPR spawn cap bonuses by mutating the MobCategory enum fields directly.
     * Called once from RTPR.serverStarted() after the server is ready.
     *
     * MobCategory stores per-chunk mob caps as a final int field. There are no public
     * setters or GameRule constants for these in 1.21, so reflection is the only option
     * without a mixin. The field is made accessible before writing.
     *
     * Categories modified:
     *   MobCategory.MONSTER  (hostile mobs — zombies, skeletons, creepers...) +10
     *   MobCategory.CREATURE (passive animals — cows, pigs, sheep...)         +2
     */
    public static void applySpawnRates(MinecraftServer server) {
        adjustMobCategoryCap(MobCategory.MONSTER,  MONSTER_SPAWN_BONUS);
        adjustMobCategoryCap(MobCategory.CREATURE, PASSIVE_SPAWN_BONUS);
    }

    /**
     * Adds a bonus to the per-chunk spawn cap of the given MobCategory.
     * Locates the cap field by matching its current value against the category's
     * public getMaxInstancesPerChunk() result, then writes back via reflection.
     */
    private static void adjustMobCategoryCap(MobCategory category, int bonus) {
        try {
            Field capField = findCapField(category);
            if (capField == null) {
                RTPR.LOGGER.error("Could not find spawn cap field for MobCategory.{}", category.name());
                return;
            }

            capField.setAccessible(true);

            // Strip the final modifier to allow writing to the enum field.
            try {
                Field modifiersField = Field.class.getDeclaredField("modifiers");
                modifiersField.setAccessible(true);
                modifiersField.setInt(capField, capField.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
            } catch (NoSuchFieldException e) {
                // Java 12+ internals moved — setAccessible alone is usually sufficient
                // on JVM versions used for Minecraft (Java 17/21 with Fabric's --add-opens).
                RTPR.LOGGER.warn("Could not strip final modifier; attempting write anyway.");
            }

            int oldValue = capField.getInt(category);
            capField.setInt(category, oldValue + bonus);
            int newValue = capField.getInt(category);

            RTPR.LOGGER.info("RTPR spawn cap: MobCategory.{} {} → {} (+{})",
                category.name(), oldValue, newValue, bonus);

        } catch (Exception e) {
            RTPR.LOGGER.error("Failed to adjust spawn cap for MobCategory.{}: {}", category.name(), e.getMessage());
        }
    }

    /**
     * Finds the int field on MobCategory whose value matches the category's
     * getMaxInstancesPerChunk() result. Skips the ordinal field (always 0–6).
     */
    private static Field findCapField(MobCategory category) {
        int expectedCap = category.getMaxInstancesPerChunk();
        for (Field field : MobCategory.class.getDeclaredFields()) {
            if (field.getType() != int.class) continue;
            try {
                field.setAccessible(true);
                if (field.getInt(category) == expectedCap && expectedCap > 6) {
                    return field;
                }
            } catch (IllegalAccessException ignored) {}
        }
        return null;
    }
}