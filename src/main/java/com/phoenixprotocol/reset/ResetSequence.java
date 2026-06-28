package com.phoenixprotocol.reset;

import com.phoenixprotocol.PhoenixProtocolMod;
import com.phoenixprotocol.config.HardcoreConfig;
import com.phoenixprotocol.death.DeathTracker;
import com.phoenixprotocol.dimension.DimensionManager;
import com.phoenixprotocol.lobby.LobbyBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Random;

public class ResetSequence {

    private static final Logger LOGGER = LoggerFactory.getLogger("PhoenixProtocol");
    private static final BlockPos LOBBY_POSITION = new BlockPos(0, 100, 0);

    public static void execute(
            MinecraftServer server,
            ServerPlayer triggerPlayer,
            HardcoreConfig config,
            DeathTracker deathTracker
    ) {
        LOGGER.warn("[PhoenixProtocol] ===== WORLD RESET SEQUENCE INITIATED =====");
        LOGGER.warn("[PhoenixProtocol] Triggered by: {}", triggerPlayer.getName().getString());

        long startTime = System.currentTimeMillis();

        try {
            if (triggerPlayer != null) {
                String banType = config.getInstigatorBanType();
                if ("PERM".equalsIgnoreCase(banType) || "TEMP".equalsIgnoreCase(banType)) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "ban " + triggerPlayer.getName().getString() + " You wiped the server!");
                }
            }

            step1_broadcast(server, triggerPlayer);
            step2_evacuate(server);
            step3_clearPlayers(server, config);
            long newSeed = step4_destroyDimensions(server, config);
            step5_wipePlayerFiles(server, config);
            int resetNumber = step6_updateState(config, deathTracker);
            step7_rebuildDimensions(server, newSeed);
            step8_applyChunkTickets(server);
            step9_returnPlayers(server);
            step9b_resyncPlayers(server);
            step10_announce(server, resetNumber);

            long elapsed = System.currentTimeMillis() - startTime;
            LOGGER.info("[PhoenixProtocol] ===== RESET COMPLETE in {}ms (Reset #{}) =====", elapsed, resetNumber);

        } catch (Exception e) {
            LOGGER.error("[PhoenixProtocol] !!!!! CRITICAL ERROR DURING RESET !!!!!", e);
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("[PhoenixProtocol] CRITICAL ERROR during reset! Check server logs.")
                            .withStyle(ChatFormatting.RED),
                    false
            );
        }
    }

    public static void reviveDeadPlayers(MinecraftServer server) {
        LOGGER.info("[PhoenixProtocol] Pre-Step: Removing dead players to prevent ghost entities");
        List<ServerPlayer> deadPlayers = new java.util.ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isDeadOrDying()) {
                deadPlayers.add(player);
            }
        }
        for (ServerPlayer player : deadPlayers) {
            LOGGER.info("[PhoenixProtocol]   Kicking dead player: {}", player.getName().getString());
            // Disconnect the player — this triggers async removal
            player.connection.disconnect(Component.literal("§cWorld is resetting!\n§fPlease reconnect to join the new world."));
            // Immediately remove their entity from the world so no ghost lingers
            // when dimensions are destroyed. This is the key fix — disconnect() alone
            // is async and may not finish before dimensions are wiped.
            ServerLevel level = (ServerLevel) player.level();
            if (level != null) {
                level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION);
            }
            // Remove from the PlayerList's tracking so the server doesn't think they're still online
            server.getPlayerList().remove(player);
        }
    }

    private static void step1_broadcast(MinecraftServer server, ServerPlayer triggerPlayer) {
        LOGGER.info("[PhoenixProtocol] Step 1: Broadcasting reset announcement");

        String playerName = triggerPlayer.getName().getString();

        Component chatMessage = Component.literal("☠ " + playerName + " has reached the death limit! WORLD RESET INITIATED! ☠")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
        server.getPlayerList().broadcastSystemMessage(chatMessage, false);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("☠ WORLD RESET ☠").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
            ));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal(playerName + " sealed your fate.").withStyle(ChatFormatting.RED)
            ));
        }
    }

    private static void step2_evacuate(MinecraftServer server) {
        LOGGER.info("[PhoenixProtocol] Step 2: Evacuating players to Limbo");

        ServerLevel lobby = server.getLevel(DimensionManager.LIMBO);
        if (lobby == null) {
            LOGGER.error("[PhoenixProtocol] Limbo dimension not found! Cannot evacuate.");
            return;
        }

        List<ServerPlayer> players = new java.util.ArrayList<>(server.getPlayerList().getPlayers());

        int evacuated = 0;
        for (ServerPlayer player : players) {
            // Dead players should already be kicked in reviveDeadPlayers pre-step.
            // If somehow one slipped through (died during countdown after pre-step),
            // kick them now rather than leaving a broken entity in a doomed dimension.
            if (player.isDeadOrDying()) {
                LOGGER.warn("[PhoenixProtocol]   Player {} is still dead during evacuation, kicking", player.getName().getString());
                player.connection.disconnect(Component.literal("§cWorld is resetting!\n§fPlease reconnect to join the new world."));
                ServerLevel level = (ServerLevel) player.level();
                if (level != null) {
                    level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION);
                }
                server.getPlayerList().remove(player);
                continue;
            }
            
            ResourceKey<Level> currentDim = player.level().dimension();
            if (currentDim.equals(Level.OVERWORLD) || currentDim.equals(Level.NETHER) || currentDim.equals(Level.END)) {
                player.teleportTo(lobby,
                        LOBBY_POSITION.getX() + 0.5,
                        LOBBY_POSITION.getY(),
                        LOBBY_POSITION.getZ() + 0.5,
                        java.util.Collections.emptySet(),
                        player.getYRot(),
                        player.getXRot(),
                        false
                );
                evacuated++;
            }
        }

        LOGGER.info("[PhoenixProtocol]   {} players evacuated to Limbo", evacuated);
    }

    private static void step3_clearPlayers(MinecraftServer server, HardcoreConfig config) {
        LOGGER.info("[PhoenixProtocol] Step 3: Clearing all player data");

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Inventory inventory = player.getInventory();
            inventory.clearContent();

            PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
            enderChest.clearContent();

            player.setExperienceLevels(0);
            player.setExperiencePoints(0);

            // All players should be alive at this point (force-respawned in pre-step/step2)
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0f);
            player.removeAllEffects();
            
            player.setGameMode(server.getDefaultGameType());
            
            if (config.isWipeStats()) {
                com.phoenixprotocol.mixin.StatsCounterAccessor accessor = (com.phoenixprotocol.mixin.StatsCounterAccessor) player.getStats();
                if (accessor.getStats() != null) {
                    for (net.minecraft.stats.Stat<?> stat : new java.util.ArrayList<>(accessor.getStats().keySet())) {
                        player.resetStat(stat); // Sets to 0 on the client temporarily
                    }
                }
            }
        }
        
        if (config.isWipeStats()) {
            // ACTUALLY clear the stats map for ALL players cached in memory (including offline ones)
            java.util.Map<java.util.UUID, net.minecraft.stats.ServerStatsCounter> allStats = ((com.phoenixprotocol.mixin.PlayerListAccessor) server.getPlayerList()).getStats();
            for (net.minecraft.stats.ServerStatsCounter counter : allStats.values()) {
                com.phoenixprotocol.mixin.StatsCounterAccessor accessor = (com.phoenixprotocol.mixin.StatsCounterAccessor) counter;
                if (accessor.getStats() != null) {
                    accessor.getStats().clear();
                }
                // Also mark dirty so it saves empty if something triggers a save
                counter.markAllDirty();
            }
        }
        
        if (config.isWipeAdvancements()) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "advancement revoke @a everything");
        }
    }

    private static long step4_destroyDimensions(MinecraftServer server, HardcoreConfig config) {
        LOGGER.info("[PhoenixProtocol] Step 4: Wiping vanilla dimensions");
        long initialSeed = config.isChangeSeedOnReset() ? new Random().nextLong() : server.getWorldGenSettings().options().seed();
        long newSeed = DimensionManager.wipeVanillaDimensions(server, initialSeed, config.isChangeSeedOnReset());
        return newSeed;
    }

    private static void step5_wipePlayerFiles(MinecraftServer server, HardcoreConfig config) {
        LOGGER.info("[PhoenixProtocol] Step 5: Wiping player data files from disk");

        Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);

        deleteDirectoryContents(worldDir.resolve("playerdata"), "playerdata");
        deleteDirectoryContents(worldDir.resolve("players/data"), "playerdata (players/data)");
        
        if (config.isWipeAdvancements()) {
            deleteDirectoryContents(worldDir.resolve("advancements"), "advancements");
            deleteDirectoryContents(worldDir.resolve("players/advancements"), "advancements (players/advancements)");
            ((com.phoenixprotocol.mixin.PlayerListAccessor) server.getPlayerList()).getAdvancements().clear();
        }
        
        if (config.isWipeStats()) {
            deleteDirectoryContents(worldDir.resolve("stats"), "stats");
            deleteDirectoryContents(worldDir.resolve("players/stats"), "stats (players/stats)");
            ((com.phoenixprotocol.mixin.PlayerListAccessor) server.getPlayerList()).getStats().clear();
        }

        // Restore online players into the PlayerList caches so their data saves correctly when they eventually log out
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (config.isWipeAdvancements()) {
                ((com.phoenixprotocol.mixin.PlayerListAccessor) server.getPlayerList()).getAdvancements().put(player.getUUID(), player.getAdvancements());
            }
            if (config.isWipeStats()) {
                ((com.phoenixprotocol.mixin.PlayerListAccessor) server.getPlayerList()).getStats().put(player.getUUID(), player.getStats());
            }
        }
    }

    private static void deleteDirectoryContents(Path dir, String name) {
        if (!Files.exists(dir)) return;

        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    if (!directory.equals(dir)) {
                        Files.delete(directory);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            LOGGER.info("[PhoenixProtocol]   Wiped {} folder", name);
        } catch (IOException e) {
            LOGGER.error("[PhoenixProtocol]   Failed to wipe {} folder", name, e);
        }
    }

    private static int step6_updateState(HardcoreConfig config, DeathTracker deathTracker) {
        LOGGER.info("[PhoenixProtocol] Step 6: Updating config and resetting death counts");
        int resetNumber = config.incrementResetCount();
        deathTracker.resetCycleCounts();
        return resetNumber;
    }

    private static void step7_rebuildDimensions(MinecraftServer server, long newSeed) {
        LOGGER.info("[PhoenixProtocol] Step 7: Rebuilding skipped (handled in step 4), resetting time to 0");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 0");
    }

    private static BlockPos findSafeSpawn(ServerLevel overworld) {
        int[] radiuses = {0, 1, 2, 3, 4, 5, 8, 10, 15, 20};
        
        for (int r : radiuses) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // Only check the perimeter of the current radius
                    if (Math.abs(dx) != r && Math.abs(dz) != r && r != 0) continue;
                    
                    int bx = dx * 16;
                    int bz = dz * 16;
                    
                    // Query the terrain generator for the base height at this coordinate without forcing a chunk load
                    int y = overworld.getChunkSource().getGenerator().getBaseHeight(
                            bx, bz,
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            overworld,
                            overworld.getChunkSource().randomState()
                    );
                    
                    // Overworld sea level is 63. If height is 64 or above, it is solid land (not an ocean or river).
                    if (y >= 64) {
                        return new BlockPos(bx, y, bz);
                    }
                }
            }
        }
        
        // Fallback if somehow no land is found within 20 chunks (320 blocks)
        int defaultY = overworld.getChunkSource().getGenerator().getBaseHeight(
                0, 0,
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                overworld,
                overworld.getChunkSource().randomState()
        );
        return new BlockPos(0, defaultY > -64 ? defaultY : 100, 0);
    }

    private static void step8_applyChunkTickets(MinecraftServer server) {
        LOGGER.info("[PhoenixProtocol] Step 8: Finding safe spawn and applying tickets");

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        BlockPos safeSpawn = findSafeSpawn(overworld);
        overworld.setRespawnData(new net.minecraft.world.level.storage.LevelData.RespawnData(net.minecraft.core.GlobalPos.of(Level.OVERWORLD, safeSpawn), 0.0f, 0.0f));

        ChunkPos spawnChunk = new ChunkPos(safeSpawn.getX() >> 4, safeSpawn.getZ() >> 4);
        overworld.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.UNKNOWN, spawnChunk, 11);
    }

    private static void step9_returnPlayers(MinecraftServer server) {
        LOGGER.info("[PhoenixProtocol] Step 9: Returning players to new world");

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        BlockPos spawnPos = overworld.getRespawnData().pos();
        int spawnX = spawnPos.getX();
        int spawnY = spawnPos.getY();
        int spawnZ = spawnPos.getZ();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.teleportTo(overworld,
                    spawnX + 0.5,
                    spawnY + 0.1,
                    spawnZ + 0.5,
                    java.util.Collections.emptySet(),
                    0.0f,
                    0.0f,
                    false
            );
        }
    }

    /**
     * Forces a full client-side state resync for all connected players.
     * After being teleported across multiple dimensions during a reset, the client
     * can get desynced on gamemode, abilities, health, and inventory.
     * This step re-sends all critical state packets to fix that.
     */
    private static void step9b_resyncPlayers(MinecraftServer server) {
        LOGGER.info("[PhoenixProtocol] Step 9b: Resyncing all player client states");

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Force gamemode — ensures the player isn't stuck in spectator/adventure
            player.setGameMode(server.getDefaultGameType());

            // Resync abilities (fly, invulnerability, build speed, etc.)
            player.onUpdateAbilities();

            // Resync health, food, and saturation
            player.connection.send(new ClientboundSetHealthPacket(
                    player.getHealth(),
                    player.getFoodData().getFoodLevel(),
                    player.getFoodData().getSaturationLevel()
            ));

            // Resync inventory contents
            player.inventoryMenu.broadcastChanges();

            // Force position confirmation — makes the client acknowledge its location
            player.connection.teleport(
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot()
            );
        }
    }

    private static void step10_announce(MinecraftServer server, int resetNumber) {
        LOGGER.info("[PhoenixProtocol] Step 10: Announcing new world (Reset #{})", resetNumber);

        Component chatMessage = Component.literal("✦ A new world has been born! Good luck. (Reset #" + resetNumber + ") ✦")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
        server.getPlayerList().broadcastSystemMessage(chatMessage, false);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(20, 80, 20));
            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("✦ NEW WORLD ✦").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
            ));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal("Reset #" + resetNumber + " — Don't die this time.").withStyle(ChatFormatting.DARK_GREEN)
            ));
        }
    }
}
