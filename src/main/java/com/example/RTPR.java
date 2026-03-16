package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;

public class RTPR implements ModInitializer {
    public static final String MOD_ID = "rtpr";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static int TELEPORT_INTERVAL = 5;
    private static int TELEPORT_RADIUS = 100_000;
    private static final int MAX_TELEPORT_ATTEMPTS = 60;
    private static final int USED_POSITIONS_CAP = 500;
    // Countdown is now 10 seconds — the pre-cache fires immediately when the
    // countdown starts, so there is plenty of time to find a position.
    private static final int COUNTDOWN_SECONDS = 10;
    private static final int COMBAT_COOLDOWN_SECONDS = 10;
    // How often the "teleport coming" reminder flashes on the action bar.
    private static final int REMINDER_INTERVAL_SECONDS = 30;
    private static final int MIN_DISTANCE_BETWEEN_TELEPORTS = 2000;
    // Preferred distance ring from the player's current position.
    // Candidates inside MIN_PLAYER_DISTANCE are always rejected.
    // Candidates between MIN_PLAYER_DISTANCE and MAX_PLAYER_DISTANCE are preferred.
    // Candidates beyond MAX_PLAYER_DISTANCE are accepted as a fallback.
    private static final int MIN_PLAYER_DISTANCE = 500;
    private static final int MAX_PLAYER_DISTANCE = 1000;
    // Minimum distance from world origin (0, 0) — keeps players away from spawn.
    private static final int MIN_ORIGIN_DISTANCE = 250;
    private static boolean enabled = true;

    // Pool needs enough threads to handle 11 scheduled tasks per player simultaneously
    // (one per second of the countdown). 8 is a reasonable ceiling for a typical server.
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);
    private static ScheduledFuture<?> scheduledTask = null;
    private static ScheduledFuture<?> reminderTask = null;
    private static MinecraftServer serverInstance = null;
    // Stores both the cached position and the dimension it was found in,
    // so doTeleport() can detect if the player changed dimension mid-countdown.
    private record CachedPosition(BlockPos pos, ResourceKey<net.minecraft.world.level.Level> dimension) {}
    private static final ConcurrentHashMap<UUID, CachedPosition> preCachedPositions = new ConcurrentHashMap<>();

    // Ordered eviction log — lets us reliably drop the oldest entry when the cap is hit.
    private static final java.util.Deque<BlockPos> usedPositionsOrder =
        new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final Set<BlockPos> usedPositions = ConcurrentHashMap.newKeySet();

    private static final ResourceLocation[] LOOT_TABLES = {
        ResourceLocation.withDefaultNamespace("chests/abandoned_mineshaft"),
        ResourceLocation.withDefaultNamespace("chests/desert_pyramid"),
        ResourceLocation.withDefaultNamespace("chests/jungle_temple"),
        ResourceLocation.withDefaultNamespace("chests/stronghold_corridor"),
        ResourceLocation.withDefaultNamespace("chests/nether_bridge"),
        ResourceLocation.withDefaultNamespace("chests/woodland_mansion"),
        ResourceLocation.withDefaultNamespace("chests/pillager_outpost"),
        ResourceLocation.withDefaultNamespace("chests/ruined_portal"),
        ResourceLocation.withDefaultNamespace("chests/shipwreck_treasure")
    };

    // OP loot tables used on the rare 1-in-20 roll.
    private static final ResourceLocation[] OP_LOOT_TABLES = {
        ResourceLocation.withDefaultNamespace("chests/end_city_treasure"),      // elytra, diamond gear
        ResourceLocation.withDefaultNamespace("chests/stronghold_library"),     // max enchant books
        ResourceLocation.withDefaultNamespace("chests/bastion_treasure"),       // netherite, ancient debris
        ResourceLocation.withDefaultNamespace("chests/woodland_mansion"),       // totem, golden apple
        ResourceLocation.withDefaultNamespace("chests/buried_treasure")         // heart of the sea, diamonds
    };

    // 1-in-20 chance of an OP chest spawning instead of a normal one.
    private static final int OP_CHEST_CHANCE = 20;

    @Override
    public void onInitialize() {
        LOGGER.info("RTPR mod initialized!");
        RTPRServer.register();
        RTPRCommands.register();
    }

    public static ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public static MinecraftServer getServer() {
        return serverInstance;
    }

    public static int getTeleportInterval() {
        return TELEPORT_INTERVAL;
    }

    public static ScheduledFuture<?> getScheduledTask() {
        return scheduledTask;
    }

    public static void serverStarted(MinecraftServer server) {
        serverInstance = server;
        LOGGER.info("RTPR server started, scheduling teleports.");
        RTPRServer.applySpawnRates(server);
        scheduleRandomTeleport(server);
    }

    public static void scheduleRandomTeleport(MinecraftServer server) {
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(false);
        }
        if (reminderTask != null && !reminderTask.isCancelled()) {
            reminderTask.cancel(false);
        }

        long intervalSeconds = TELEPORT_INTERVAL * 60L;
        // Clamp the initial delay so it never goes negative if the interval is very short.
        long initialDelay = Math.max(0, intervalSeconds - COUNTDOWN_SECONDS);

        // Track when the next teleport will fire so reminders can show accurate time remaining.
        final AtomicLong nextTeleportAt = new AtomicLong(System.currentTimeMillis() + initialDelay * 1000L);

        scheduledTask = scheduler.scheduleAtFixedRate(() -> {
            if (!enabled) return;
            if (server == null) {
                LOGGER.warn("MinecraftServer instance is null.");
                return;
            }
            // Update the next-fire timestamp for the following interval.
            nextTeleportAt.set(System.currentTimeMillis() + intervalSeconds * 1000L);
            LOGGER.info("Starting countdown for all players.");
            server.getPlayerList().getPlayers().forEach(player -> {
                startCountdown(server, player, COUNTDOWN_SECONDS);
            });
        }, initialDelay, intervalSeconds, TimeUnit.SECONDS);

        // Reminder: flash the action bar every REMINDER_INTERVAL_SECONDS showing time until teleport.
        // Stops firing once the 10-second countdown is active (within COUNTDOWN_SECONDS of the event).
        reminderTask = scheduler.scheduleAtFixedRate(() -> {
            if (!enabled) return;
            if (server == null) return;

            long secsUntil = Math.max(0, (nextTeleportAt.get() - System.currentTimeMillis()) / 1000L);
            // Don't show the reminder if the 10s countdown is already running.
            if (secsUntil <= COUNTDOWN_SECONDS) return;

            String timeStr = secsUntil >= 60
                ? (secsUntil / 60) + "m " + (secsUntil % 60) + "s"
                : secsUntil + "s";

            server.execute(() -> {
                server.getPlayerList().getPlayers().forEach(player -> {
                    GameType gameMode = player.gameMode.getGameModeForPlayer();
                    if (gameMode == GameType.SPECTATOR || gameMode == GameType.CREATIVE) return;

                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                        Component.literal("Next teleport in " + timeStr).withStyle(ChatFormatting.GRAY)
                    ));
                });
            });
        }, REMINDER_INTERVAL_SECONDS, REMINDER_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public static void startCountdown(MinecraftServer server, ServerPlayer player, int seconds) {
        // Pre-calculate position immediately at start of countdown.
        // With a 10-second window this is still plenty of time.
        // Note: if the player changes dimension before the teleport fires,
        // doTeleport() will discard the cached position and find a fresh one.
        scheduler.execute(() -> {
            ServerLevel world = player.serverLevel();
            if (world == null) return;
            BlockPos playerPos = player.blockPosition();
            BlockPos pos = findSafeRandomPosition(world, playerPos);
            preCachedPositions.put(player.getUUID(), new CachedPosition(pos, world.dimension()));
            LOGGER.info("Pre-cached teleport position for {}: {}", player.getName().getString(), pos);
        });

        for (int i = seconds; i >= 0; i--) {
            final int timeLeft = i;
            scheduler.schedule(() -> {
                server.execute(() -> {
                    if (!player.isAlive()) return;

                    GameType gameMode = player.gameMode.getGameModeForPlayer();
                    if (gameMode == GameType.SPECTATOR || gameMode == GameType.CREATIVE) return;

                    if (timeLeft == 0) {
                        doTeleport(server, player);
                    } else {
                        // Show subtitle for every tick of the 10-second countdown.
                        // Red for the last 3 seconds, yellow otherwise.
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                            Component.literal(" ")
                        ));
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                            Component.literal("Teleporting in " + timeLeft + "s")
                                .withStyle(timeLeft <= 3 ? ChatFormatting.RED : ChatFormatting.YELLOW)
                        ));
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 25, 5));

                        // Play a pling sound on each of the final 3 ticks.
                        if (timeLeft <= 3) {
                            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                                net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value()),
                                net.minecraft.sounds.SoundSource.MASTER,
                                player.getX(), player.getY(), player.getZ(),
                                1.0f, 1.5f, player.level().random.nextLong()
                            ));
                        }
                    }
                });
            }, (long)(seconds - i), TimeUnit.SECONDS);
        }
    }

    private static boolean isInCombat(ServerPlayer player) {
        return (player.level().getGameTime() - player.getLastHurtByMobTimestamp()) < (COMBAT_COOLDOWN_SECONDS * 20L);
    }

    public static void doTeleport(MinecraftServer server, ServerPlayer player) {
        ServerLevel world = player.serverLevel();
        if (world == null) {
            LOGGER.warn("Player's world is null, cannot teleport.");
            return;
        }

        // Peek first — only remove once we're certain we're going to use it.
        // If combat delays the teleport we re-enter doTeleport later and the
        // cached position must still be available.
        CachedPosition cached = preCachedPositions.get(player.getUUID());

        // Discard the cached position if the player changed dimension during the
        // countdown — it was found for a different world and is no longer valid.
        if (cached != null && !cached.dimension().equals(world.dimension())) {
            LOGGER.info("Player {} changed dimension during countdown, discarding cached position.",
                player.getName().getString());
            preCachedPositions.remove(player.getUUID());
            cached = null;
        }

        if (isInCombat(player)) {
            LOGGER.info("Player {} is in combat, delaying teleport by {}s.",
                player.getName().getString(), COMBAT_COOLDOWN_SECONDS);
            player.sendSystemMessage(Component.literal(
                "Teleport delayed — you are in combat!")
                .withStyle(ChatFormatting.RED));
            scheduler.schedule(() -> {
                server.execute(() -> doTeleport(server, player));
            }, COMBAT_COOLDOWN_SECONDS, TimeUnit.SECONDS);
            return;
        }

        // Safe to consume the cache now.
        if (cached != null) preCachedPositions.remove(player.getUUID());

        if (cached != null) {
            LOGGER.info("Using pre-cached position for {}: {}", player.getName().getString(), cached.pos());
            executeTeleport(world, player, cached.pos());
        } else {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                Component.literal(" ")
            ));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                Component.literal("Searching for location...")
                    .withStyle(ChatFormatting.GRAY)
            ));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 60, 10));

            scheduler.execute(() -> {
                try {
                    BlockPos playerPos = player.blockPosition();
                    BlockPos fallbackPos = findSafeRandomPosition(world, playerPos);
                    server.execute(() -> executeTeleport(world, player, fallbackPos));
                } catch (Exception e) {
                    LOGGER.error("Failed to find teleport position: ", e);
                }
            });
        }
    }

    /** Shared teleport execution — used by both the cached and fallback paths. */
    private static void executeTeleport(ServerLevel world, ServerPlayer player, BlockPos pos) {
        try {
            player.teleportTo(world, pos.getX(), pos.getY(), pos.getZ(),
                    java.util.Set.of(), player.getYRot(), player.getXRot(), true);
            player.sendSystemMessage(Component.literal("You have been randomly teleported!")
                    .withStyle(ChatFormatting.GREEN));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundClearTitlesPacket(true));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT),
                net.minecraft.sounds.SoundSource.MASTER,
                pos.getX(), pos.getY(), pos.getZ(),
                1.0f, 1.0f, java.util.concurrent.ThreadLocalRandom.current().nextLong()
            ));
            spawnLootChest(world, pos, player);
            LOGGER.info("Teleported {} to {}", player.getName().getString(), pos);
        } catch (Exception e) {
            LOGGER.error("An error occurred during teleportation: ", e);
            player.sendSystemMessage(Component.literal("Teleportation failed. See server logs.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static void spawnLootChest(ServerLevel world, BlockPos playerPos, ServerPlayer player) {
        try {
            java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
            boolean isOp = rng.nextInt(OP_CHEST_CHANCE) == 0;

            ResourceLocation[] pool = isOp ? OP_LOOT_TABLES : LOOT_TABLES;
            ResourceLocation lootTable = pool[rng.nextInt(pool.length)];

            // Use a trapped chest for OP drops so players can tell at a glance.
            net.minecraft.world.level.block.state.BlockState chestBlock = isOp
                ? Blocks.TRAPPED_CHEST.defaultBlockState()
                : Blocks.CHEST.defaultBlockState();

            BlockPos chestPos = findChestPlacement(world, playerPos);
            world.setBlock(chestPos, chestBlock, 3);

            if (world.getBlockEntity(chestPos) instanceof RandomizableContainerBlockEntity chest) {
                ResourceKey<net.minecraft.world.level.storage.loot.LootTable> key =
                    ResourceKey.create(Registries.LOOT_TABLE, lootTable);
                chest.setLootTable(key, rng.nextLong());
                LOGGER.info("Spawned {} chest at {} with loot table {}",
                    isOp ? "OP" : "normal", chestPos, lootTable);
            }

            if (isOp) {
                player.sendSystemMessage(Component.literal("✦ You got lucky — an OP chest spawned!")
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to spawn loot chest: ", e);
        }
    }

    /**
     * Finds a safe adjacent block to place the loot chest next to the player.
     * Tries the four cardinal horizontal neighbours first; falls back to directly
     * above if all are occupied.
     */
    private static BlockPos findChestPlacement(ServerLevel world, BlockPos playerPos) {
        BlockPos[] candidates = {
            playerPos.north(),
            playerPos.south(),
            playerPos.east(),
            playerPos.west()
        };
        for (BlockPos candidate : candidates) {
            if (world.getBlockState(candidate).isAir()) {
                return candidate;
            }
        }
        // Last resort — above the player (original behaviour).
        return playerPos.above();
    }

    private static BlockPos findSafeRandomPosition(ServerLevel world, BlockPos playerPos) {
        final long maxCoord = TELEPORT_RADIUS; // long to prevent overflow in arithmetic below

        final boolean isNether = world.dimension().equals(net.minecraft.world.level.Level.NETHER);
        final boolean isEnd    = world.dimension().equals(net.minecraft.world.level.Level.END);

        LOGGER.info("Finding teleport position in {} (radius {})", world.dimension().location(), maxCoord);

        final long minDistSq       = (long) MIN_DISTANCE_BETWEEN_TELEPORTS * (long) MIN_DISTANCE_BETWEEN_TELEPORTS;
        final long minOriginDistSq = (long) MIN_ORIGIN_DISTANCE   * (long) MIN_ORIGIN_DISTANCE;
        final long minPlayerDistSq = (long) MIN_PLAYER_DISTANCE   * (long) MIN_PLAYER_DISTANCE;
        final java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();

        // Three-pass strategy:
        //   Pass 0 — preferred ring: 500–1000 blocks from the player (most teleports land here).
        //   Pass 1 — mid range: 1000–5000 blocks from the player, still avoids repeat locations.
        //   Pass 2 — full world radius: anywhere outside MIN_PLAYER_DISTANCE, ignores used-position
        //            history (last resort before the fallback retry).
        final int PASS_PREFERRED_BUDGET = MAX_TELEPORT_ATTEMPTS * 2;
        final int PASS_MID_BUDGET       = MAX_TELEPORT_ATTEMPTS;
        final int PASS_FULL_BUDGET      = MAX_TELEPORT_ATTEMPTS;

        for (int pass = 0; pass < 3; pass++) {
            int budget = (pass == 0) ? PASS_PREFERRED_BUDGET
                       : (pass == 1) ? PASS_MID_BUDGET
                       :               PASS_FULL_BUDGET;

            for (int i = 0; i < budget; i++) {
                final long x, z;

                if (pass == 0) {
                    // Uniform sample within the 500–1000 block ring around the player.
                    double angle = rng.nextDouble() * 2 * Math.PI;
                    double dist  = MIN_PLAYER_DISTANCE
                                 + rng.nextDouble() * (MAX_PLAYER_DISTANCE - MIN_PLAYER_DISTANCE);
                    x = Math.max(-maxCoord, Math.min(maxCoord,
                            (long)(playerPos.getX() + dist * Math.cos(angle))));
                    z = Math.max(-maxCoord, Math.min(maxCoord,
                            (long)(playerPos.getZ() + dist * Math.sin(angle))));
                } else if (pass == 1) {
                    // Uniform sample within a 1000–5000 block ring around the player.
                    double angle = rng.nextDouble() * 2 * Math.PI;
                    double dist  = MAX_PLAYER_DISTANCE
                                 + rng.nextDouble() * (5_000 - MAX_PLAYER_DISTANCE);
                    x = Math.max(-maxCoord, Math.min(maxCoord,
                            (long)(playerPos.getX() + dist * Math.cos(angle))));
                    z = Math.max(-maxCoord, Math.min(maxCoord,
                            (long)(playerPos.getZ() + dist * Math.sin(angle))));
                } else {
                    // Uniform sample across the full world radius using angle+distance
                    // to avoid the square-corner bias of a flat nextInt approach.
                    double angle = rng.nextDouble() * 2 * Math.PI;
                    double dist  = MIN_PLAYER_DISTANCE
                                 + rng.nextDouble() * (maxCoord - MIN_PLAYER_DISTANCE);
                    x = Math.max(-maxCoord, Math.min(maxCoord,
                            (long)(playerPos.getX() + dist * Math.cos(angle))));
                    z = Math.max(-maxCoord, Math.min(maxCoord,
                            (long)(playerPos.getZ() + dist * Math.sin(angle))));
                }

                BlockPos pos = resolveToSurface(world, (int) x, (int) z, isNether, isEnd);
                if (pos == null) continue;

                // Origin exclusion zone.
                long ox = pos.getX(), oz = pos.getZ();
                if (ox * ox + oz * oz < minOriginDistSq) continue;

                // Must be far enough from the player's current position.
                long pdx = pos.getX() - playerPos.getX();
                long pdz = pos.getZ() - playerPos.getZ();
                if (pdx * pdx + pdz * pdz < minPlayerDistSq) continue;

                // Pass 2 skips the used-position check — we just need somewhere valid.
                if (pass < 2) {
                    boolean tooClose = false;
                    for (BlockPos used : usedPositions) {
                        long dx = pos.getX() - used.getX();
                        long dz = pos.getZ() - used.getZ();
                        if (dx * dx + dz * dz < minDistSq) {
                            tooClose = true;
                            break;
                        }
                    }
                    if (tooClose) continue;
                }

                recordUsedPosition(pos);
                LOGGER.info("Found position (pass {}) for player at {}: {}", pass, playerPos, pos);
                return pos;
            }
        }

        // All three passes exhausted — clear history so the world opens back up,
        // then do one final sweep across the preferred ring followed by the full radius.
        LOGGER.warn("All passes exhausted, clearing position history and doing final sweep.");
        usedPositions.clear();
        usedPositionsOrder.clear();

        for (int i = 0; i < MAX_TELEPORT_ATTEMPTS * 2; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            // First half of attempts stays in the preferred ring; second half goes wide.
            double dist = (i < MAX_TELEPORT_ATTEMPTS)
                ? MIN_PLAYER_DISTANCE + rng.nextDouble() * (MAX_PLAYER_DISTANCE - MIN_PLAYER_DISTANCE)
                : MIN_PLAYER_DISTANCE + rng.nextDouble() * (maxCoord - MIN_PLAYER_DISTANCE);

            long cx = Math.max(-maxCoord, Math.min(maxCoord, (long)(playerPos.getX() + dist * Math.cos(angle))));
            long cz = Math.max(-maxCoord, Math.min(maxCoord, (long)(playerPos.getZ() + dist * Math.sin(angle))));

            long ox = cx, oz = cz;
            if (ox * ox + oz * oz < minOriginDistSq) continue;
            long pdx = cx - playerPos.getX(), pdz = cz - playerPos.getZ();
            if (pdx * pdx + pdz * pdz < minPlayerDistSq) continue;

            BlockPos pos = resolveToSurface(world, (int) cx, (int) cz, isNether, isEnd);
            if (pos == null) continue;

            LOGGER.warn("Final sweep found position: {}", pos);
            return pos;
        }

        // Absolute last resort — guaranteed outside both exclusion zones.
        LOGGER.error("All position search attempts failed, using minimum-offset fallback.");
        if (isNether) {
            return new BlockPos(playerPos.getX() + MIN_PLAYER_DISTANCE, 64, playerPos.getZ());
        } else {
            int fx = (int) Math.min(maxCoord, (long) playerPos.getX() + MIN_PLAYER_DISTANCE);
            int fz = playerPos.getZ();
            int y  = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fx, fz);
            return new BlockPos(fx, Math.max(y, 1), fz);
        }
    }

    /**
     * Resolves an (x, z) coordinate to a safe standing position for the given dimension.
     * Returns null if no safe position can be found at that column.
     */
    private static BlockPos resolveToSurface(ServerLevel world, int x, int z,
                                              boolean isNether, boolean isEnd) {
        if (isNether) {
            // Only query block states in loaded chunks to stay thread-safe.
            if (!world.hasChunk(x >> 4, z >> 4)) return null;
            for (int y = 30; y < 110; y++) {
                BlockPos c = new BlockPos(x, y, z);
                if (world.getBlockState(c).isAir()
                        && world.getBlockState(c.above()).isAir()
                        && world.getBlockState(c.below()).isSolid()) {
                    return c;
                }
            }
            return null;
        }

        // Overworld and End: heightmap is safe to call on any column.
        int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= 0) return null;
        BlockPos candidate = new BlockPos(x, y, z);
        // Verify the block below is actually solid on its top face before landing on it.
        if (!world.getBlockState(candidate.below()).isFaceSturdy(
                world, candidate.below(), net.minecraft.core.Direction.UP)) return null;
        return candidate;
    }

    /** Records a used position, evicting the oldest entry when the cap is reached. */
    private static void recordUsedPosition(BlockPos pos) {
        usedPositions.add(pos);
        usedPositionsOrder.addLast(pos);
        if (usedPositionsOrder.size() > USED_POSITIONS_CAP) {
            BlockPos oldest = usedPositionsOrder.pollFirst();
            if (oldest != null) usedPositions.remove(oldest);
        }
    }

    public static void serverStopped() {
        LOGGER.info("RTPR server stopping, shutting down scheduler.");
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        if (reminderTask != null) {
            reminderTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static void setTeleportInterval(int minutes) {
        TELEPORT_INTERVAL = minutes;
        if (serverInstance != null) {
            scheduleRandomTeleport(serverInstance);
        }
    }

    public static void setTeleportRadius(int blocks) {
        TELEPORT_RADIUS = blocks;
    }

    public static boolean toggleEnabled() {
        enabled = !enabled;
        return enabled;
    }
}