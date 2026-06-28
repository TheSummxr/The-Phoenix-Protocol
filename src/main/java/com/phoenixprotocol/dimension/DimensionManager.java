package com.phoenixprotocol.dimension;

import com.phoenixprotocol.mixin.MinecraftServerAccessor;
import com.phoenixprotocol.mixin.ServerLevelMixin;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/**
 * Manages the dynamic lifecycle (creation, deletion, file wiping) of the hardcore dimensions.
 */
public class DimensionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("PhoenixProtocol");

    public static final ResourceKey<Level> LIMBO = ResourceKey.create(Registries.DIMENSION, Identifier.parse("phoenixprotocol:limbo"));

    public static long wipeVanillaDimensions(MinecraftServer server, long newSeed, boolean rerollIfOcean) {
        MinecraftServerAccessor serverAccessor = (MinecraftServerAccessor) server;
        Map<ResourceKey<Level>, ServerLevel> worlds = serverAccessor.getLevels();

        ServerLevel overworld = worlds.get(Level.OVERWORLD);
        ServerLevel nether = worlds.get(Level.NETHER);
        ServerLevel end = worlds.get(Level.END);

        try {
            if (overworld != null) { overworld.save(null, true, false); overworld.close(); }
            if (nether != null) { nether.save(null, true, false); nether.close(); }
            if (end != null) { end.save(null, true, false); end.close(); }
        } catch (Exception e) {
            LOGGER.error("[PhoenixProtocol] Error saving/closing vanilla dimensions during wipe", e);
        }

        worlds.remove(Level.OVERWORLD);
        worlds.remove(Level.NETHER);
        worlds.remove(Level.END);
        
        server.saveAllChunks(true, true, true);

        // Delete folders based on actual dimension paths
        net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess storageAccess = ((com.phoenixprotocol.mixin.MinecraftServerAccessor) server).getStorageSource();
        
        // Wipe Overworld
        Path overworldDir = storageAccess.getDimensionPath(Level.OVERWORLD);
        deleteFolder(overworldDir.resolve("region"));
        deleteFolder(overworldDir.resolve("entities"));
        deleteFolder(overworldDir.resolve("poi"));
        
        // Wipe Nether
        Path netherDir = storageAccess.getDimensionPath(Level.NETHER);
        deleteFolder(netherDir.resolve("region"));
        deleteFolder(netherDir.resolve("entities"));
        deleteFolder(netherDir.resolve("poi"));
        
        // Wipe End
        Path endDir = storageAccess.getDimensionPath(Level.END);
        deleteFolder(endDir.resolve("region"));
        deleteFolder(endDir.resolve("entities"));
        deleteFolder(endDir.resolve("poi"));

        // Global stats & player data
        Path rootWorldDir = storageAccess.getLevelPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        deleteFolder(rootWorldDir.resolve("stats"));
        deleteFolder(rootWorldDir.resolve("advancements"));
        deleteFolder(rootWorldDir.resolve("playerdata"));
        deleteFolder(rootWorldDir.resolve("players/stats"));
        deleteFolder(rootWorldDir.resolve("players/advancements"));
        deleteFolder(rootWorldDir.resolve("players/data"));
        
        LOGGER.info("[PhoenixProtocol] Vanilla dimension files wiped.");

        // Randomize seed and reset time/weather
        ServerLevelData worldData = server.getWorldData().overworldData();
        worldData.setGameTime(0);

        net.minecraft.world.level.levelgen.WorldOptions options = server.getWorldGenSettings().options();
        ((com.phoenixprotocol.mixin.WorldOptionsAccessor) (Object) options).setSeed(newSeed);

        // Recreate them
        RegistryAccess registryManager = server.registryAccess();
        LevelStem overworldStem = registryManager.lookupOrThrow(Registries.LEVEL_STEM).getValueOrThrow(LevelStem.OVERWORLD);
        LevelStem netherStem = registryManager.lookupOrThrow(Registries.LEVEL_STEM).getValueOrThrow(LevelStem.NETHER);
        LevelStem endStem = registryManager.lookupOrThrow(Registries.LEVEL_STEM).getValueOrThrow(LevelStem.END);

        ServerLevel newOverworld = null;
        boolean isOcean = true;
        int attempts = 0;
        
        while (isOcean && attempts < 100) {
            newOverworld = new ServerLevel(server, serverAccessor.getExecutor(), serverAccessor.getStorageSource(), worldData, Level.OVERWORLD, overworldStem, false, newSeed, java.util.List.of(), true);
            
            if (!rerollIfOcean) {
                isOcean = false;
                break;
            }
            
            String biomeStr = newOverworld.getBiome(net.minecraft.core.BlockPos.ZERO).toString();
            if (biomeStr.contains("ocean")) {
                newSeed = new java.util.Random().nextLong();
                ((com.phoenixprotocol.mixin.WorldOptionsAccessor) (Object) options).setSeed(newSeed);
                attempts++;
            } else {
                isOcean = false;
            }
        }
        
        if (attempts > 0) {
            LOGGER.info("[PhoenixProtocol] Rerolled seed {} times to avoid ocean spawn.", attempts);
        }

        newOverworld.resetWeatherCycle();
        ServerLevel newNether = new ServerLevel(server, serverAccessor.getExecutor(), serverAccessor.getStorageSource(), new DerivedLevelData(server.getWorldData(), worldData), Level.NETHER, netherStem, false, newSeed, java.util.List.of(), false);
        ServerLevel newEnd = new ServerLevel(server, serverAccessor.getExecutor(), serverAccessor.getStorageSource(), new DerivedLevelData(server.getWorldData(), worldData), Level.END, endStem, false, newSeed, java.util.List.of(), false);

        worlds.put(Level.OVERWORLD, newOverworld);
        worlds.put(Level.NETHER, newNether);
        worlds.put(Level.END, newEnd);
        
        server.saveAllChunks(true, true, true);
        LOGGER.info("[PhoenixProtocol] Vanilla dimensions recreated with new seed: {}", newSeed);
        return newSeed;
    }

    private static void deleteFolder(Path dir) {
        LOGGER.warn("[PhoenixProtocol] Attempting to delete folder: {}", dir.toAbsolutePath());
        if (!Files.exists(dir)) {
            LOGGER.warn("[PhoenixProtocol] Folder does not exist, skipping: {}", dir);
            return;
        }
        try {
            final java.util.concurrent.atomic.AtomicInteger deletedFiles = new java.util.concurrent.atomic.AtomicInteger(0);
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    for (int i = 0; i < 5; i++) {
                        try {
                            Files.delete(file);
                            deletedFiles.incrementAndGet();
                            return FileVisitResult.CONTINUE;
                        } catch (IOException e) {
                            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                        }
                    }
                    LOGGER.warn("[PhoenixProtocol] Could not delete file: {}", file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exc) {
                    for (int i = 0; i < 5; i++) {
                        try {
                            Files.delete(directory);
                            return FileVisitResult.CONTINUE;
                        } catch (IOException e) {
                            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                        }
                    }
                    LOGGER.warn("[PhoenixProtocol] Could not delete directory: {}", directory);
                    return FileVisitResult.CONTINUE;
                }
            });
            LOGGER.warn("[PhoenixProtocol] Successfully deleted {} files in {}", deletedFiles.get(), dir);
        } catch (IOException e) {
            LOGGER.error("[PhoenixProtocol] Failed to walk folder for deletion: {}", dir, e);
        }
    }
}
