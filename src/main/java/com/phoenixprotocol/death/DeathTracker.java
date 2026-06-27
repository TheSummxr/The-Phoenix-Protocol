package com.phoenixprotocol.death;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.phoenixprotocol.config.HardcoreConfig;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player deaths.
 */
public class DeathTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("PhoenixProtocol");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private final Map<UUID, Integer> cycleDeaths = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lifetimeDeaths = new ConcurrentHashMap<>();
    private final Path statsFile;

    public DeathTracker(Path runDirectory) {
        Path configDir = runDirectory.resolve("config").resolve("phoenixprotocol");
        this.statsFile = configDir.resolve("death_stats.json");
        loadStats();
    }

    public int recordDeath(ServerPlayer player) {
        UUID uuid = player.getUUID();
        int newCycleCount = cycleDeaths.getOrDefault(uuid, 0) + 1;
        cycleDeaths.put(uuid, newCycleCount);

        lifetimeDeaths.put(uuid, lifetimeDeaths.getOrDefault(uuid, 0) + 1);
        saveStats();

        return newCycleCount;
    }

    public boolean shouldTriggerReset(ServerPlayer player, HardcoreConfig config) {
        int limit = config.getDeathLimit(player);
        int current = cycleDeaths.getOrDefault(player.getUUID(), 0);
        return current >= limit;
    }

    public int getCycleDeaths(UUID uuid) {
        return cycleDeaths.getOrDefault(uuid, 0);
    }

    public int getLifetimeDeaths(UUID uuid) {
        return lifetimeDeaths.getOrDefault(uuid, 0);
    }

    public void resetCycleCounts() {
        cycleDeaths.clear();
        LOGGER.info("[PhoenixProtocol] Cycle death counts have been reset");
    }

    public void resetPlayerCycleCount(UUID uuid) {
        cycleDeaths.remove(uuid);
        LOGGER.info("[PhoenixProtocol] Cycle death count for {} has been reset", uuid);
    }

    private void loadStats() {
        if (!Files.exists(statsFile)) return;
        try (Reader reader = Files.newBufferedReader(statsFile)) {
            Map<UUID, Integer> loaded = GSON.fromJson(reader,
                    new TypeToken<Map<UUID, Integer>>(){}.getType());
            if (loaded != null) {
                lifetimeDeaths.putAll(loaded);
            }
        } catch (IOException e) {
            LOGGER.error("[PhoenixProtocol] Failed to load death stats", e);
        }
    }

    public void saveStats() {
        try {
            Files.createDirectories(statsFile.getParent());
            try (Writer writer = Files.newBufferedWriter(statsFile)) {
                GSON.toJson(lifetimeDeaths, writer);
            }
        } catch (IOException e) {
            LOGGER.error("[PhoenixProtocol] Failed to save death stats", e);
        }
    }
}
