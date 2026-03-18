package com.example;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.Map;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;

public class RTPRServer {

    // ---------------------------------------------------------------------------
    // Config — centralised so values are easy to tweak without hunting the code.
    // In a full implementation these would be read from a TOML/JSON config file.
    // ---------------------------------------------------------------------------
    public static final class Config {
        public static int monsterSpawnBonus  = 10; // vanilla MONSTER  cap: 70 → 80
        public static int passiveSpawnBonus  =  2; // vanilla CREATURE cap: 10 → 12

        /** Reload from disk (stub — wire up to your config library here). */
        public static void reload() {
            // e.g. monsterSpawnBonus = FabricLoader...config.get("monsterBonus");
        }
    }

    // ---------------------------------------------------------------------------
    // Original caps — stored so we can revert when the mod is disabled/unloaded.
    // ---------------------------------------------------------------------------
    private static final Map<MobCategory, Integer> ORIGINAL_CAPS = new EnumMap<>(MobCategory.class);
    private static boolean capsApplied = false;

    // ---------------------------------------------------------------------------
    // Network payload
    // ---------------------------------------------------------------------------
    public record TimerPayload(int timeLeft) implements CustomPacketPayload {
        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath("rtpr", "timer_sync");
        public static final CustomPacketPayload.Type<TimerPayload> TYPE =
                new CustomPacketPayload.Type<>(ID);
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

    // ---------------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------------
    public static void register() {
        PayloadTypeRegistry.playS2C().register(TimerPayload.TYPE, TimerPayload.CODEC);
    }

    public static void sendTimerSync(ServerPlayer player, int timeLeft) {
        RTPR.LOGGER.info("Sending timer packet to {}: {}s remaining", player.getName().getString(), timeLeft);
        ServerPlayNetworking.send(player, new TimerPayload(timeLeft));
    }

    // ---------------------------------------------------------------------------
    // Spawn cap management
    // ---------------------------------------------------------------------------

    /**
     * Applies RTPR spawn-cap bonuses using config values.
     * Stores originals so {@link #revertSpawnRates()} can undo the changes.
     * Safe to call multiple times — will not double-apply.
     */
    public static void applySpawnRates(MinecraftServer server) {
        if (capsApplied) {
            RTPR.LOGGER.warn("applySpawnRates() called while caps are already applied — skipping.");
            return;
        }

        Config.reload(); // pick up any config changes before applying

        boolean allOk = true;
        allOk &= adjustMobCategoryCap(MobCategory.MONSTER,  Config.monsterSpawnBonus);
        allOk &= adjustMobCategoryCap(MobCategory.CREATURE, Config.passiveSpawnBonus);

        if (allOk) {
            capsApplied = true;
            RTPR.LOGGER.info("RTPR spawn caps applied successfully.");
        } else {
            RTPR.LOGGER.error("One or more spawn cap adjustments failed. Check logs above.");
            // Attempt a clean rollback so the server isn't left in a partial state.
            revertSpawnRates();
        }
    }

    /**
     * Reverts all modified MobCategory caps back to their original values.
     * Call this on server stop or when the mod is disabled at runtime.
     */
    public static void revertSpawnRates() {
        if (ORIGINAL_CAPS.isEmpty()) {
            RTPR.LOGGER.info("revertSpawnRates() called but no originals stored — nothing to revert.");
            return;
        }

        for (Map.Entry<MobCategory, Integer> entry : ORIGINAL_CAPS.entrySet()) {
            MobCategory category = entry.getKey();
            int original         = entry.getValue();

            try {
                Field capField = findCapField(category, -1); // -1 = find by name fallback
                if (capField == null) {
                    RTPR.LOGGER.error("Cannot revert cap for MobCategory.{} — field not found.", category.name());
                    continue;
                }
                unlockFinalField(capField);
                int current = capField.getInt(category);
                capField.setInt(category, original);
                RTPR.LOGGER.info("RTPR spawn cap reverted: MobCategory.{} {} → {}",
                        category.name(), current, original);
            } catch (Exception e) {
                RTPR.LOGGER.error("Failed to revert spawn cap for MobCategory.{}: {}",
                        category.name(), e.getMessage());
            }
        }

        ORIGINAL_CAPS.clear();
        capsApplied = false;
        RTPR.LOGGER.info("RTPR spawn caps reverted.");
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * Adds {@code bonus} to the per-chunk spawn cap of {@code category}.
     *
     * @return true if the adjustment succeeded, false otherwise.
     */
    private static boolean adjustMobCategoryCap(MobCategory category, int bonus) {
        try {
            int expectedCap = category.getMaxInstancesPerChunk();
            Field capField  = findCapField(category, expectedCap);

            if (capField == null) {
                RTPR.LOGGER.error("Could not locate spawn-cap field for MobCategory.{} " +
                        "(expected value: {}). Mojang may have renamed it.", category.name(), expectedCap);
                return false;
            }

            unlockFinalField(capField);

            int oldValue = capField.getInt(category);

            // Guard: store the original only on first apply.
            ORIGINAL_CAPS.putIfAbsent(category, oldValue);

            capField.setInt(category, oldValue + bonus);
            int newValue = capField.getInt(category);

            // Verify the write actually took effect.
            if (newValue != oldValue + bonus) {
                RTPR.LOGGER.error("Spawn cap write verification failed for MobCategory.{}: " +
                        "expected {} but got {}.", category.name(), oldValue + bonus, newValue);
                return false;
            }

            RTPR.LOGGER.info("RTPR spawn cap: MobCategory.{} {} → {} (+{})",
                    category.name(), oldValue, newValue, bonus);
            return true;

        } catch (Exception e) {
            RTPR.LOGGER.error("Exception adjusting spawn cap for MobCategory.{}: {}",
                    category.name(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Finds the {@code int} field on {@link MobCategory} whose current value
     * matches {@code expectedCap}.
     *
     * <p>Passing {@code expectedCap = -1} skips value-matching and falls back to
     * returning the first non-ordinal int field (used during revert when the value
     * has already been changed).
     *
     * <p>The check {@code expectedCap > 6} guards against accidentally matching
     * the ordinal field (values 0–6 for the enum constants). This was brittle when
     * Mojang might set a cap to ≤ 6, so we now explicitly skip fields named
     * "ordinal" as a belt-and-braces fix.
     */
    private static Field findCapField(MobCategory category, int expectedCap) {
        for (Field field : MobCategory.class.getDeclaredFields()) {
            if (field.getType() != int.class) continue;
            if (field.getName().equals("ordinal"))  continue; // always skip ordinal

            try {
                field.setAccessible(true);
                int value = field.getInt(category);

                if (expectedCap == -1) {
                    // Fallback mode: return first non-ordinal int field.
                    return field;
                }

                // Normal mode: match by value, but only when the cap is sane (> 6).
                if (value == expectedCap && expectedCap > 6) {
                    return field;
                }
            } catch (IllegalAccessException ignored) {}
        }
        return null;
    }

    /**
     * Makes a field accessible and attempts to strip its {@code final} modifier.
     *
     * <p>On Java 12+ the {@code modifiers} field of {@link Field} is not directly
     * accessible via normal reflection. Fabric's launch args typically include the
     * necessary {@code --add-opens} for Java 17/21, so {@code setAccessible(true)}
     * alone usually suffices. We attempt the modifier strip and log a warning if it
     * fails rather than hard-crashing, since the write may still succeed.
     */
    private static void unlockFinalField(Field field) {
        field.setAccessible(true);
        try {
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Expected on Java 12+ without explicit --add-opens for java.lang.reflect.
            // Fabric's default launch args usually cover this; log at DEBUG level only.
            RTPR.LOGGER.debug("Could not strip final modifier from field '{}' ({}). " +
                    "Proceeding — write may still succeed via setAccessible.", field.getName(), e.getMessage());
        }
    }
}