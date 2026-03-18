package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
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

    // ---------------------------------------------------------------------------
    // Config — all tunable values live here. Wire Config.reload() to a TOML file.
    // ---------------------------------------------------------------------------
    public static final class Config {
        public static int teleportIntervalMinutes     = 5;
        public static int teleportRadius              = 100_000;
        public static int maxTeleportAttempts         = 60;
        public static int usedPositionsCap            = 500;
        public static int countdownSeconds            = 10;
        public static int combatCooldownSeconds       = 10;
        public static int reminderIntervalSeconds     = 30;
        public static int minDistanceBetweenTeleports = 2_000;
        public static int minPlayerDistance           = 500;
        public static int maxPlayerDistance           = 1_000;
        public static int minOriginDistance           = 250;
        public static int opChestChance               = 20;
        // Spawn cap bonuses (also referenced by RTPRServer)
        public static int monsterSpawnBonus           = 10;
        public static int passiveSpawnBonus           = 2;

        public static void reload() {
            // Stub — replace with your config library (e.g. Cloth Config / simple TOML).
            // Example: teleportIntervalMinutes = myConfig.getInt("teleportInterval", 5);
        }
    }

    // ---------------------------------------------------------------------------
    // Scheduler — pool sized to handle countdown tasks for a typical server load.
    // ---------------------------------------------------------------------------
    private static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    private static ScheduledFuture<?> scheduledTask = null;
    private static ScheduledFuture<?> reminderTask  = null;
    private static MinecraftServer    serverInstance = null;
    private static boolean            enabled        = true;

    // ---------------------------------------------------------------------------
    // Per-player cached positions — cleaned up on disconnect.
    // ---------------------------------------------------------------------------
    private record CachedPosition(
            BlockPos pos,
            ResourceKey<net.minecraft.world.level.Level> dimension) {}

    private static final ConcurrentHashMap<UUID, CachedPosition> preCachedPositions =
            new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------------
    // Position history
    // ---------------------------------------------------------------------------
    private static final PositionHistory usedPositions = new PositionHistory(Config.usedPositionsCap);

    // ---------------------------------------------------------------------------
    // Loot tables
    // ---------------------------------------------------------------------------
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

    private static final ResourceLocation[] OP_LOOT_TABLES = {
        ResourceLocation.withDefaultNamespace("chests/end_city_treasure")
    };

    // ---------------------------------------------------------------------------
    // ModInitializer
    // ---------------------------------------------------------------------------
    @Override
    public void onInitialize() {
        LOGGER.info("RTPR mod initialized!");
        RTPRServer.register();
        RTPRCommands.register();
    }

    // ---------------------------------------------------------------------------
    // Accessors used by RTPRCommands / RTPRServer
    // ---------------------------------------------------------------------------
    public static ScheduledExecutorService getScheduler()        { return scheduler;                      }
    public static MinecraftServer          getServer()           { return serverInstance;                 }
    public static int                      getTeleportInterval() { return Config.teleportIntervalMinutes; }
    public static ScheduledFuture<?>       getScheduledTask()    { return scheduledTask;                  }

    // ---------------------------------------------------------------------------
    // Server lifecycle
    // ---------------------------------------------------------------------------
    public static void serverStarted(MinecraftServer server) {
        serverInstance = server;
        Config.reload();
        LOGGER.info("RTPR server started, scheduling teleports.");
        RTPRServer.applySpawnRates(server);
        scheduleRandomTeleport(server);
    }

    public static void serverStopped() {
        LOGGER.info("RTPR server stopping, shutting down scheduler.");
        RTPRServer.revertSpawnRates();
        cancelTasks();
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

    /** Call this from your player-disconnect event handler. */
    public static void onPlayerDisconnect(ServerPlayer player) {
        UUID id = player.getUUID();
        if (preCachedPositions.remove(id) != null) {
            LOGGER.info("Cleaned up cached position for disconnected player: {}",
                    player.getName().getString());
        }
    }

    /** Call this from your player-join event handler. */
    public static void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        if (!enabled) return;
        if (scheduledTask == null || scheduledTask.isCancelled()) return;

        long delay = scheduledTask.getDelay(TimeUnit.SECONDS);
        if (delay <= 0) return;

        long secsUntil = Math.max(0, delay - Config.countdownSeconds);
        if (secsUntil <= 0) return;

        String timeStr = secsUntil >= 60
                ? (secsUntil / 60) + "m " + (secsUntil % 60) + "s"
                : secsUntil + "s";

        player.sendSystemMessage(
                Component.literal("Next random teleport in " + timeStr + ".")
                        .withStyle(ChatFormatting.GRAY));
    }

    // ---------------------------------------------------------------------------
    // Scheduling
    // ---------------------------------------------------------------------------
    public static void scheduleRandomTeleport(MinecraftServer server) {
        cancelTasks();

        long intervalSeconds = (long) Config.teleportIntervalMinutes * 60L;
        long initialDelay    = Math.max(0, intervalSeconds - Config.countdownSeconds);

        final AtomicLong nextTeleportAt =
                new AtomicLong(System.currentTimeMillis() + initialDelay * 1_000L);

        scheduledTask = scheduler.scheduleAtFixedRate(() -> {
            if (!enabled || server == null) return;
            nextTeleportAt.set(System.currentTimeMillis() + intervalSeconds * 1_000L);
            LOGGER.info("Starting countdown for all players.");
            server.getPlayerList().getPlayers()
                  .forEach(player -> startCountdown(server, player, Config.countdownSeconds));
        }, initialDelay, intervalSeconds, TimeUnit.SECONDS);

        reminderTask = scheduler.scheduleAtFixedRate(() -> {
            if (!enabled || server == null) return;
            long secsUntil = Math.max(0,
                    (nextTeleportAt.get() - System.currentTimeMillis()) / 1_000L);
            if (secsUntil <= Config.countdownSeconds) return;

            String timeStr = secsUntil >= 60
                    ? (secsUntil / 60) + "m " + (secsUntil % 60) + "s"
                    : secsUntil + "s";

            server.execute(() ->
                server.getPlayerList().getPlayers().forEach(player -> {
                    GameType gm = player.gameMode.getGameModeForPlayer();
                    if (gm == GameType.SPECTATOR || gm == GameType.CREATIVE) return;
                    player.connection.send(
                        new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                            Component.literal("Next teleport in " + timeStr)
                                     .withStyle(ChatFormatting.GRAY)));
                })
            );
        }, Config.reminderIntervalSeconds, Config.reminderIntervalSeconds, TimeUnit.SECONDS);
    }

    private static void cancelTasks() {
        if (scheduledTask != null && !scheduledTask.isCancelled()) scheduledTask.cancel(false);
        if (reminderTask  != null && !reminderTask.isCancelled())  reminderTask.cancel(false);
    }

    // ---------------------------------------------------------------------------
    // Countdown & teleport
    // ---------------------------------------------------------------------------
    public static void startCountdown(MinecraftServer server, ServerPlayer player, int seconds) {
        // Pre-cache a position immediately — the countdown window gives plenty of time.
        scheduler.execute(() -> {
            ServerLevel world = player.serverLevel();
            if (world == null) return;
            BlockPos pos = findSafeRandomPosition(world, player.blockPosition());
            if (pos != null) {
                preCachedPositions.put(player.getUUID(),
                        new CachedPosition(pos, world.dimension()));
                LOGGER.info("Pre-cached position for {}: {}", player.getName().getString(), pos);
            } else {
                LOGGER.warn("Pre-cache failed for {} — will retry at teleport time.",
                        player.getName().getString());
            }
        });

        for (int i = seconds; i >= 0; i--) {
            final int timeLeft = i;
            scheduler.schedule(() -> server.execute(() -> {
                if (!player.isAlive()) return;
                GameType gm = player.gameMode.getGameModeForPlayer();
                if (gm == GameType.SPECTATOR || gm == GameType.CREATIVE) return;

                if (timeLeft == 0) {
                    doTeleport(server, player);
                } else {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                            Component.literal(" ")));
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                            Component.literal("Teleporting in " + timeLeft + "s")
                                    .withStyle(timeLeft <= 5 ? ChatFormatting.RED : ChatFormatting.YELLOW)));
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 25, 5));

                    if (timeLeft <= 5) {
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                            net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value()),
                            net.minecraft.sounds.SoundSource.MASTER,
                            player.getX(), player.getY(), player.getZ(),
                            1.0f, 1.5f, player.level().random.nextLong()));
                    }
                }
            }), (long)(seconds - timeLeft), TimeUnit.SECONDS);
        }
    }

    /**
     * Returns true if the player has taken damage from any source (mob or player)
     * within the combat cooldown window.
     */
    private static boolean isInCombat(ServerPlayer player) {
        long gameTime      = player.level().getGameTime();
        long cooldownTicks = Config.combatCooldownSeconds * 20L;
        long lastMob       = player.getLastHurtByMobTimestamp();
        long lastPlayer    = player.getLastHurtByPlayerTime();
        long lastHurt      = Math.max(lastMob, lastPlayer);
        return (gameTime - lastHurt) < cooldownTicks;
    }

    public static void doTeleport(MinecraftServer server, ServerPlayer player) {
        ServerLevel world = player.serverLevel();
        if (world == null) {
            LOGGER.warn("Player {} has a null world, cannot teleport.", player.getName().getString());
            return;
        }

        CachedPosition cached = preCachedPositions.get(player.getUUID());

        // Discard cache if the player changed dimension during the countdown.
        if (cached != null && !cached.dimension().equals(world.dimension())) {
            LOGGER.info("Player {} changed dimension — discarding cached position.",
                    player.getName().getString());
            preCachedPositions.remove(player.getUUID());
            cached = null;
        }

        if (isInCombat(player)) {
            LOGGER.info("Player {} is in combat, delaying teleport by {}s.",
                    player.getName().getString(), Config.combatCooldownSeconds);
            player.sendSystemMessage(
                    Component.literal("Teleport delayed — you are in combat!")
                             .withStyle(ChatFormatting.RED));
            scheduler.schedule(
                    () -> server.execute(() -> doTeleport(server, player)),
                    Config.combatCooldownSeconds, TimeUnit.SECONDS);
            return;
        }

        if (cached != null) preCachedPositions.remove(player.getUUID());

        if (cached != null) {
            LOGGER.info("Using cached position for {}: {}", player.getName().getString(), cached.pos());
            executeTeleport(world, player, cached.pos());
        } else {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                    Component.literal(" ")));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                    Component.literal("Searching for location...").withStyle(ChatFormatting.GRAY)));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 60, 10));

            scheduler.execute(() -> {
                try {
                    BlockPos pos = findSafeRandomPosition(world, player.blockPosition());
                    server.execute(() -> {
                        if (pos != null) {
                            executeTeleport(world, player, pos);
                        } else {
                            player.sendSystemMessage(
                                    Component.literal("Could not find a safe teleport destination.")
                                             .withStyle(ChatFormatting.RED));
                            player.connection.send(
                                    new net.minecraft.network.protocol.game.ClientboundClearTitlesPacket(true));
                        }
                    });
                } catch (Exception e) {
                    LOGGER.error("Failed to find fallback teleport position: ", e);
                }
            });
        }
    }

    private static void executeTeleport(ServerLevel world, ServerPlayer player, BlockPos pos) {
        try {
            player.teleportTo(world, pos.getX(), pos.getY(), pos.getZ(),
                    java.util.Set.of(), player.getYRot(), player.getXRot(), true);
            player.sendSystemMessage(
                    Component.literal("You have been randomly teleported!")
                             .withStyle(ChatFormatting.GREEN));
            player.connection.send(
                    new net.minecraft.network.protocol.game.ClientboundClearTitlesPacket(true));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT),
                net.minecraft.sounds.SoundSource.MASTER,
                pos.getX(), pos.getY(), pos.getZ(),
                1.0f, 1.0f, ThreadLocalRandom.current().nextLong()));
            spawnLootChest(world, pos, player);
            LOGGER.info("Teleported {} to {}", player.getName().getString(), pos);
        } catch (Exception e) {
            LOGGER.error("Teleportation error for {}: ", player.getName().getString(), e);
            player.sendSystemMessage(
                    Component.literal("Teleportation failed. See server logs.")
                             .withStyle(ChatFormatting.RED));
        }
    }

    // ---------------------------------------------------------------------------
    // Loot chest
    // ---------------------------------------------------------------------------
    private static void spawnLootChest(ServerLevel world, BlockPos playerPos, ServerPlayer player) {
        try {
            ThreadLocalRandom rng   = ThreadLocalRandom.current();
            boolean isOp            = rng.nextInt(Config.opChestChance) == 0;
            ResourceLocation[] pool = isOp ? OP_LOOT_TABLES : LOOT_TABLES;
            ResourceLocation lootTable = pool[rng.nextInt(pool.length)];

            net.minecraft.world.level.block.state.BlockState chestBlock = isOp
                    ? Blocks.TRAPPED_CHEST.defaultBlockState()
                    : Blocks.CHEST.defaultBlockState();

            BlockPos chestPos = findChestPlacement(world, playerPos);
            if (chestPos == null) {
                LOGGER.warn("Could not find a valid chest placement near {}", playerPos);
                return;
            }

            world.setBlock(chestPos, chestBlock, 3);

            if (world.getBlockEntity(chestPos) instanceof RandomizableContainerBlockEntity chest) {
                ResourceKey<net.minecraft.world.level.storage.loot.LootTable> key =
                        ResourceKey.create(Registries.LOOT_TABLE, lootTable);
                chest.setLootTable(key, rng.nextLong());
                LOGGER.info("Spawned {} chest at {} ({})",
                        isOp ? "OP" : "normal", chestPos, lootTable);
            }

            if (isOp) {
                player.sendSystemMessage(
                        Component.literal("✦ You got lucky — an OP chest spawned!")
                                 .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to spawn loot chest: ", e);
        }
    }

    /**
     * Finds a safe adjacent block for chest placement.
     * Skips liquid positions to avoid underwater or lava chests.
     * Returns null if no valid position is found.
     */
    private static BlockPos findChestPlacement(ServerLevel world, BlockPos playerPos) {
        BlockPos[] candidates = {
            playerPos.north(), playerPos.south(),
            playerPos.east(),  playerPos.west(),
            playerPos.above()
        };
        for (BlockPos candidate : candidates) {
            var state = world.getBlockState(candidate);
            if (state.isAir()) return candidate;
            if (state.canBeReplaced() && !state.getFluidState().isSource()) return candidate;
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // Position finding
    // ---------------------------------------------------------------------------

    /** Samples a point in a ring of radius [minDist, maxDist] around {@code origin}. */
    private static long[] sampleRing(BlockPos origin, double minDist, double maxDist,
                                      long maxCoord, ThreadLocalRandom rng) {
        double angle = rng.nextDouble() * 2 * Math.PI;
        double dist  = minDist + rng.nextDouble() * (maxDist - minDist);
        long x = Math.max(-maxCoord, Math.min(maxCoord, (long)(origin.getX() + dist * Math.cos(angle))));
        long z = Math.max(-maxCoord, Math.min(maxCoord, (long)(origin.getZ() + dist * Math.sin(angle))));
        return new long[]{ x, z };
    }

    private static BlockPos findSafeRandomPosition(ServerLevel world, BlockPos playerPos) {
        final long maxCoord    = Config.teleportRadius;
        final boolean isNether = world.dimension().equals(net.minecraft.world.level.Level.NETHER);
        final boolean isEnd    = world.dimension().equals(net.minecraft.world.level.Level.END);

        LOGGER.info("Finding teleport position in {} (radius {})",
                world.dimension().location(), maxCoord);

        final long minDistSq       = sq(Config.minDistanceBetweenTeleports);
        final long minOriginDistSq = sq(Config.minOriginDistance);
        final long minPlayerDistSq = sq(Config.minPlayerDistance);
        final ThreadLocalRandom rng = ThreadLocalRandom.current();

        // The End is mostly void — give it significantly more attempts to find a
        // solid island column.
        int attemptsMult = isEnd ? 5 : 1;

        // Pass 0 — preferred ring : 500–1000 blocks from player
        // Pass 1 — mid ring       : 1000–5000 blocks from player
        // Pass 2 — full radius    : anywhere beyond MIN_PLAYER_DISTANCE (skips history)
        final int[] budgets = {
            Config.maxTeleportAttempts * 2 * attemptsMult,
            Config.maxTeleportAttempts     * attemptsMult,
            Config.maxTeleportAttempts     * attemptsMult
        };

        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < budgets[pass]; i++) {
                long[] xz = switch (pass) {
                    case 0 -> sampleRing(playerPos, Config.minPlayerDistance,
                                         Config.maxPlayerDistance, maxCoord, rng);
                    case 1 -> sampleRing(playerPos, Config.maxPlayerDistance,
                                         5_000, maxCoord, rng);
                    default -> sampleRing(playerPos, Config.minPlayerDistance,
                                         maxCoord, maxCoord, rng);
                };

                BlockPos pos = resolveToSurface(world, (int) xz[0], (int) xz[1], isNether, isEnd);
                if (pos == null) continue;
                if (!passesExclusionZones(pos, playerPos, minOriginDistSq, minPlayerDistSq)) continue;
                if (pass < 2 && usedPositions.isTooClose(pos, minDistSq)) continue;

                usedPositions.record(pos);
                LOGGER.info("Found position (pass {}) for player at {}: {}", pass, playerPos, pos);
                return pos;
            }
        }

        // All passes exhausted — clear history and do a final wide sweep.
        LOGGER.warn("All passes exhausted, clearing position history and doing final sweep.");
        usedPositions.clear();

        for (int i = 0; i < Config.maxTeleportAttempts * 2 * attemptsMult; i++) {
            double minD = Config.minPlayerDistance;
            double maxD = (i < Config.maxTeleportAttempts) ? Config.maxPlayerDistance : maxCoord;
            long[] xz   = sampleRing(playerPos, minD, maxD, maxCoord, rng);

            if (!passesExclusionZones(
                    new BlockPos((int) xz[0], 0, (int) xz[1]),
                    playerPos, minOriginDistSq, minPlayerDistSq)) continue;

            BlockPos pos = resolveToSurface(world, (int) xz[0], (int) xz[1], isNether, isEnd);
            if (pos == null) continue;

            LOGGER.warn("Final sweep found position: {}", pos);
            return pos;
        }

        // End-specific last resort -- do a dedicated outer-island spiral search.
        // Outer End islands spawn in a ring roughly 1000-20000 blocks from origin
        // on a fixed noise pattern, so we sample that ring explicitly and force-load
        // chunks so block data is actually available.
        if (isEnd) {
            LOGGER.warn("End dimension fallback: scanning outer island ring.");
            BlockPos islandPos = findEndIsland(world, rng, maxCoord);
            if (islandPos != null) {
                LOGGER.warn("End island fallback found position: {}", islandPos);
                return islandPos;
            }
        }

        LOGGER.error("All position search attempts failed.");
        return null;
    }

    /**
     * Dedicated outer End island finder.
     *
     * End outer islands generate on a noise grid with islands appearing roughly
     * every 100 blocks in a ring between 1000 and 20000 blocks from the origin.
     * This method samples that ring, force-loads each candidate chunk so block
     * data is available, then does a full column scan to confirm solid ground.
     *
     * Force-loading is done via world.getChunk() with a FULL chunk status, which
     * is safe to call off the main thread during position pre-caching.
     */
    private static BlockPos findEndIsland(ServerLevel world, ThreadLocalRandom rng, long maxCoord) {
        // Islands appear reliably between 1000 and 20000 blocks out.
        final double MIN_ISLAND_DIST = 1_000;
        final double MAX_ISLAND_DIST = Math.min(20_000, maxCoord);
        final int ISLAND_ATTEMPTS    = 200;

        for (int i = 0; i < ISLAND_ATTEMPTS; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist  = MIN_ISLAND_DIST + rng.nextDouble() * (MAX_ISLAND_DIST - MIN_ISLAND_DIST);
            int cx = (int)(dist * Math.cos(angle));
            int cz = (int)(dist * Math.sin(angle));

            // Snap to the nearest 16-block chunk boundary to improve hit rate --
            // End islands tend to be chunk-aligned due to the noise generator.
            cx = (cx >> 4) << 4;
            cz = (cz >> 4) << 4;

            // Force-load the chunk so getBlockState calls return real data.
            // getChunk with FULL status generates the chunk if not already present.
            try {
                world.getChunk(cx >> 4, cz >> 4,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            } catch (Exception e) {
                LOGGER.debug("Could not load End chunk at ({}, {}): {}", cx >> 4, cz >> 4, e.getMessage());
                continue;
            }

            // Scan a small local area around the sampled point to find a solid column.
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int sx = cx + dx;
                    int sz = cz + dz;

                    int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
                    if (y <= world.getMinY()) continue;

                    // Top-down scan to find the actual solid surface.
                    boolean hasSolid = false;
                    for (int scanY = y; scanY > world.getMinY(); scanY--) {
                        if (world.getBlockState(new BlockPos(sx, scanY, sz)).isCollisionShapeFullBlock(world, new BlockPos(sx, scanY, sz))) {
                            y = scanY + 1;
                            hasSolid = true;
                            break;
                        }
                    }
                    if (!hasSolid) continue;

                    BlockPos candidate = new BlockPos(sx, y, sz);

                    // Verify sturdy floor and standing room.
                    if (!world.getBlockState(candidate.below()).isFaceSturdy(
                            world, candidate.below(), net.minecraft.core.Direction.UP)) continue;
                    if (world.getBlockState(candidate).isCollisionShapeFullBlock(world, candidate))        continue;
                    if (world.getBlockState(candidate.above()).isCollisionShapeFullBlock(world, candidate.above())) continue;

                    return candidate;
                }
            }
        }
        return null;
    }

    /** Returns true if {@code pos} clears both the origin and player exclusion zones. */
    private static boolean passesExclusionZones(BlockPos pos, BlockPos playerPos,
                                                 long minOriginDistSq, long minPlayerDistSq) {
        long ox = pos.getX(), oz = pos.getZ();
        if (ox * ox + oz * oz < minOriginDistSq) return false;
        long pdx = pos.getX() - playerPos.getX();
        long pdz = pos.getZ() - playerPos.getZ();
        return pdx * pdx + pdz * pdz >= minPlayerDistSq;
    }

    /**
     * Resolves an (x, z) coordinate to a safe standing position.
     *
     * Overworld / End:
     *   Uses MOTION_BLOCKING_NO_LEAVES heightmap to find the surface Y.
     *   Returns null if:
     *     - The heightmap returns a value at or below min build height (void column).
     *     - [End only] A top-down scan finds no solid block in the column.
     *     - The block below the surface isn't sturdy on its top face.
     *     - The landing spot or block above it is solid (no room to stand).
     *
     * Nether:
     *   Scans upward from Y=30 to Y=110 for an air gap with a solid floor.
     *   Only processes already-loaded chunks to stay thread-safe.
     */
    private static BlockPos resolveToSurface(ServerLevel world, int x, int z,
                                              boolean isNether, boolean isEnd) {
        if (isNether) {
            if (!world.hasChunk(x >> 4, z >> 4)) return null;
            for (int y = 30; y < 110; y++) {
                BlockPos c = new BlockPos(x, y, z);
                if (world.getBlockState(c).isAir()
                        && world.getBlockState(c.above()).isAir()
                        && world.getBlockState(c.below()).isCollisionShapeFullBlock(world, c.below())) {
                    return c;
                }
            }
            return null;
        }

        // --- Overworld and End ---
        int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

        // A heightmap result at or below min build height means the column is void.
        if (y <= world.getMinY()) return null;

        // End-specific: do a top-down scan to confirm there is actually a solid block
        // in this column. The heightmap can return a low but non-zero value even over
        // void if only air or non-blocking blocks are present.
        if (isEnd) {
            boolean hasSolid = false;
            for (int scanY = y; scanY > world.getMinY(); scanY--) {
                if (world.getBlockState(new BlockPos(x, scanY, z)).isCollisionShapeFullBlock(world, new BlockPos(x, scanY, z))) {
                    hasSolid = true;
                    y = scanY + 1; // stand on top of the confirmed solid block
                    break;
                }
            }
            // No solid block found in the column — this is void, reject it.
            if (!hasSolid) return null;
        }

        BlockPos candidate = new BlockPos(x, y, z);

        // The block directly below must be sturdy so the player won't fall through.
        if (!world.getBlockState(candidate.below()).isFaceSturdy(
                world, candidate.below(), net.minecraft.core.Direction.UP)) return null;

        // The landing spot and the block above must not be solid — player needs room to stand.
        if (world.getBlockState(candidate).isCollisionShapeFullBlock(world, candidate))        return null;
        if (world.getBlockState(candidate.above()).isCollisionShapeFullBlock(world, candidate.above())) return null;

        return candidate;
    }

    // ---------------------------------------------------------------------------
    // Commands API
    // ---------------------------------------------------------------------------

    /**
     * Forces an OP loot chest to spawn at the player's current position.
     * Called by /rtpr opchest <player>. Runs on the server thread via server.execute().
     */
    public static void spawnOpChestAt(net.minecraft.server.MinecraftServer server, ServerPlayer player) {
        server.execute(() -> {
            try {
                ServerLevel world = player.serverLevel();
                BlockPos chestPos = findChestPlacement(world, player.blockPosition());
                if (chestPos == null) {
                    player.sendSystemMessage(
                            Component.literal("No space found to place the OP chest.")
                                     .withStyle(ChatFormatting.RED));
                    LOGGER.warn("spawnOpChestAt: no valid placement near {}", player.blockPosition());
                    return;
                }

                world.setBlock(chestPos, net.minecraft.world.level.block.Blocks.TRAPPED_CHEST.defaultBlockState(), 3);

                if (world.getBlockEntity(chestPos) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity chest) {
                    ResourceLocation lootTable = OP_LOOT_TABLES[ThreadLocalRandom.current().nextInt(OP_LOOT_TABLES.length)];
                    ResourceKey<net.minecraft.world.level.storage.loot.LootTable> key =
                            ResourceKey.create(Registries.LOOT_TABLE, lootTable);
                    chest.setLootTable(key, ThreadLocalRandom.current().nextLong());
                    LOGGER.info("spawnOpChestAt: spawned OP chest at {} for {}", chestPos, player.getName().getString());
                }

                player.sendSystemMessage(
                        Component.literal("An OP chest has been granted to you!")
                                 .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            } catch (Exception e) {
                LOGGER.error("spawnOpChestAt failed for {}: ", player.getName().getString(), e);
            }
        });
    }

    public static void setTeleportInterval(int minutes) {
        Config.teleportIntervalMinutes = minutes;
        if (serverInstance != null) scheduleRandomTeleport(serverInstance);
    }

    public static void setTeleportRadius(int blocks) {
        Config.teleportRadius = blocks;
    }

    public static boolean toggleEnabled() {
        enabled = !enabled;
        return enabled;
    }

    // ---------------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------------
    private static long sq(long v) { return v * v; }
}