package com.example;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;

public class RTPRCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                Commands.literal("rtpr")
                    .requires(source -> source.hasPermission(2))

                    // /rtpr toggle
                    .then(Commands.literal("toggle")
                        .executes(ctx -> executeToggle(ctx.getSource())))

                    // /rtpr interval <minutes>
                    .then(Commands.literal("interval")
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 60))
                            .executes(ctx -> executeInterval(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "minutes")
                            ))))

                    // /rtpr radius <blocks>
                    .then(Commands.literal("radius")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(500, 100_000))
                            .executes(ctx -> executeRadius(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "blocks")
                            ))))

                    // /rtpr teleport <player>
                    .then(Commands.literal("teleport")
                        .then(Commands.argument("player", EntityArgument.players())
                            .executes(ctx -> executeTeleport(
                                ctx.getSource(),
                                EntityArgument.getPlayers(ctx, "player")
                            ))))

                    // /rtpr opchest <player>
                    // Forces an OP loot chest to spawn at the target player's current position.
                    .then(Commands.literal("opchest")
                        .then(Commands.argument("player", EntityArgument.players())
                            .executes(ctx -> executeOpChest(
                                ctx.getSource(),
                                EntityArgument.getPlayers(ctx, "player")
                            ))))
            );
        });
    }

    private static int executeToggle(CommandSourceStack source) {
        boolean nowEnabled = RTPR.toggleEnabled();
        source.sendSuccess(() -> Component.literal(
            "RTPR teleporting is now " + (nowEnabled ? "enabled" : "disabled") + ".")
            .withStyle(nowEnabled ? ChatFormatting.GREEN : ChatFormatting.RED), true);
        return 1;
    }

    private static int executeInterval(CommandSourceStack source, int minutes) {
        RTPR.setTeleportInterval(minutes);
        source.sendSuccess(() -> Component.literal(
            "RTPR teleport interval set to " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".")
            .withStyle(ChatFormatting.YELLOW), true);
        RTPR.LOGGER.info("Teleport interval changed to {} minutes by {}", minutes, source.getTextName());
        return 1;
    }

    private static int executeRadius(CommandSourceStack source, int blocks) {
        RTPR.setTeleportRadius(blocks);
        source.sendSuccess(() -> Component.literal(
            "RTPR teleport radius set to " + blocks + " blocks.")
            .withStyle(ChatFormatting.YELLOW), true);
        RTPR.LOGGER.info("Teleport radius changed to {} blocks by {}", blocks, source.getTextName());
        return 1;
    }

    private static int executeTeleport(CommandSourceStack source, Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            RTPR.startCountdown(source.getServer(), player, 10);
            source.sendSuccess(() -> Component.literal(
                "Triggered teleport countdown for " + player.getName().getString() + ".")
                .withStyle(ChatFormatting.GREEN), true);
            RTPR.LOGGER.info("{} manually triggered teleport for {}",
                source.getTextName(), player.getName().getString());
        }
        return players.size();
    }

    private static int executeOpChest(CommandSourceStack source, Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            RTPR.spawnOpChestAt(source.getServer(), player);
            source.sendSuccess(() -> Component.literal(
                "Spawned OP chest for " + player.getName().getString() + ".")
                .withStyle(ChatFormatting.GOLD), true);
            RTPR.LOGGER.info("{} manually spawned OP chest for {}",
                source.getTextName(), player.getName().getString());
        }
        return players.size();
    }
}