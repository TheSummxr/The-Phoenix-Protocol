package com.hardcorereset.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the mod's JSON configuration file at {@code config/hardcorereset/config.json}.
 * <p>
 * Configuration schema:
 * <pre>{@code
 * {
 *   "total_world_resets": 0,
 *   "default_death_limit": 3,
 *   "player_limits": {
 *     "player_uuid_or_name": 1,
 *     "another_player": 5
 *   }
 * }
 * }</pre>
 * <p>
 * Player limits are checked by UUID first (as a string), then by player name.
 * If neither is found, {@code default_death_limit} is used.
 */
public class HardcoreConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("HardcoreReset");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Config Fields ──────────────────────────────────────────────────────

    /** Total number of world resets that have occurred since the server was first set up. */
    private int total_world_resets = 0;

    /** Default death limit for players not listed in {@code player_limits}. */
    private int default_death_limit = 1;

    private int reset_countdown_seconds = 10;
    
    private List<String> exempt_dimensions = new ArrayList<>();
    
    private boolean count_pvp_deaths = true;
    
    private boolean wipe_advancements = true;
    
    private boolean wipe_stats = true;
    
    private boolean change_seed_on_reset = true;
    
    private String instigator_ban_type = "NONE";
    
    private int instigator_temp_ban_minutes = 60;

    /**
     * Per-player death limit overrides.
     * <p>
     * Keys can be UUIDs (as strings) or player usernames.
     * Values are the number of deaths in a single cycle before a reset triggers.
     * <p>
     * Example: {@code {"Steve": 1, "550e8400-e29b-41d4-a716-446655440000": 5}}
     */
    private Map<String, Integer> player_limits = new HashMap<>();

    // ── Transient Fields ───────────────────────────────────────────────────

    /** Filesystem path to the config file (not serialized). */
    private transient Path configPath;

    // ── Constructors ───────────────────────────────────────────────────────

    /** Default constructor for Gson deserialization. */
    public HardcoreConfig() {
    }

    // ── I/O ────────────────────────────────────────────────────────────────

    /**
     * Loads the config from disk, or creates a default config if the file doesn't exist.
     *
     * @param serverRunDir the server's root directory (where {@code config/} lives)
     * @return the loaded or newly-created config instance
     */
    public static HardcoreConfig load(Path serverRunDir) {
        Path configDir = serverRunDir.resolve("config").resolve("hardcorereset");
        Path configFile = configDir.resolve("config.json");

        HardcoreConfig config;

        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                config = GSON.fromJson(json, HardcoreConfig.class);
                if (config == null) {
                    config = new HardcoreConfig();
                }
                LOGGER.info("[HardcoreReset] Loaded config: {} resets total, default death limit = {}",
                        config.total_world_resets, config.default_death_limit);
            } catch (Exception e) {
                LOGGER.error("[HardcoreReset] Failed to read config.json, using defaults", e);
                config = new HardcoreConfig();
            }
        } else {
            LOGGER.info("[HardcoreReset] No config found, creating default config.json");
            config = new HardcoreConfig();

            // Add example entries so the server operator understands the format
            config.player_limits.put("ExamplePlayer", 1);
            config.player_limits.put("AnotherPlayer", 5);
            config.exempt_dimensions.add("minecraft:the_end");
        }

        config.configPath = configFile;
        config.save();  // Always write back so the file exists with current state

        return config;
    }

    /**
     * Writes the current config state to disk.
     * Creates parent directories if necessary.
     */
    public void save() {
        if (configPath == null) {
            LOGGER.warn("[HardcoreReset] Cannot save config: path is null");
            return;
        }

        try {
            Files.createDirectories(configPath.getParent());
            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
        } catch (IOException e) {
            LOGGER.error("[HardcoreReset] Failed to save config.json", e);
        }
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    /**
     * Gets the death limit for a specific player.
     * <p>
     * Lookup order:
     * <ol>
     *   <li>Player's UUID (as string) in {@code player_limits}</li>
     *   <li>Player's display name in {@code player_limits}</li>
     *   <li>{@code default_death_limit} as fallback</li>
     * </ol>
     *
     * @param player the server player entity
     * @return the number of deaths allowed per cycle before a reset
     */
    public int getDeathLimit(ServerPlayer player) {
        // Check by UUID first
        String uuid = player.getStringUUID();
        if (player_limits.containsKey(uuid)) {
            return player_limits.get(uuid);
        }

        // Then check by display name
        String name = player.getName().getString();
        if (player_limits.containsKey(name)) {
            return player_limits.get(name);
        }

        // Fallback to default
        return default_death_limit;
    }

    /**
     * Increments the total world reset counter and saves to disk.
     *
     * @return the new total reset count
     */
    public int incrementResetCount() {
        total_world_resets++;
        save();
        LOGGER.info("[HardcoreReset] World reset #{} recorded", total_world_resets);
        return total_world_resets;
    }

    /**
     * @return the total number of world resets that have occurred
     */
    public int getTotalWorldResets() {
        return total_world_resets;
    }

    /**
     * @return the default death limit for unrecognized players
     */
    public int getDefaultDeathLimit() {
        return default_death_limit;
    }

    /**
     * @return the per-player death limit overrides map
     */
    public Map<String, Integer> getPlayerLimits() {
        return player_limits;
    }

    public int getResetCountdownSeconds() {
        return reset_countdown_seconds;
    }

    public List<String> getExemptDimensions() {
        return exempt_dimensions;
    }

    public boolean isCountPvpDeaths() {
        return count_pvp_deaths;
    }

    public boolean isWipeAdvancements() {
        return wipe_advancements;
    }

    public boolean isWipeStats() {
        return wipe_stats;
    }

    public boolean isChangeSeedOnReset() {
        return change_seed_on_reset;
    }

    public String getInstigatorBanType() {
        return instigator_ban_type;
    }

    public int getInstigatorTempBanMinutes() {
        return instigator_temp_ban_minutes;
    }

    public void setTotalWorldResets(int count) {
        this.total_world_resets = count;
        save();
    }

    public void setDefaultDeathLimit(int limit) {
        this.default_death_limit = limit;
        save();
    }

    public void setResetCountdownSeconds(int seconds) {
        this.reset_countdown_seconds = seconds;
        save();
    }

    public void setCountPvpDeaths(boolean count) {
        this.count_pvp_deaths = count;
        save();
    }

    public void setWipeAdvancements(boolean wipe) {
        this.wipe_advancements = wipe;
        save();
    }

    public void setWipeStats(boolean wipe) {
        this.wipe_stats = wipe;
        save();
    }

    public void setChangeSeedOnReset(boolean change) {
        this.change_seed_on_reset = change;
        save();
    }

    public void setInstigatorBanType(String type) {
        this.instigator_ban_type = type;
        save();
    }

    public void setInstigatorTempBanMinutes(int minutes) {
        this.instigator_temp_ban_minutes = minutes;
        save();
    }

    public boolean addExemptDimension(String dimensionId) {
        if (!this.exempt_dimensions.contains(dimensionId)) {
            this.exempt_dimensions.add(dimensionId);
            save();
            return true;
        }
        return false;
    }

    public boolean removeExemptDimension(String dimensionId) {
        if (this.exempt_dimensions.contains(dimensionId)) {
            this.exempt_dimensions.remove(dimensionId);
            save();
            return true;
        }
        return false;
    }

    public void setPlayerLimit(String identifier, int limit) {
        this.player_limits.put(identifier, limit);
        save();
    }

    public boolean removePlayerLimit(String identifier) {
        if (this.player_limits.containsKey(identifier)) {
            this.player_limits.remove(identifier);
            save();
            return true;
        }
        return false;
    }
}
