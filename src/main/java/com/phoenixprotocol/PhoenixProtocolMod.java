package com.phoenixprotocol;

import com.phoenixprotocol.config.HardcoreConfig;
import com.phoenixprotocol.death.DeathTracker;
import com.phoenixprotocol.dimension.DimensionManager;
import com.phoenixprotocol.lobby.LobbyBuilder;
import com.phoenixprotocol.reset.ResetSequence;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class PhoenixProtocolMod implements DedicatedServerModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("PhoenixProtocol");
    private static PhoenixProtocolMod INSTANCE;

    public static PhoenixProtocolMod getInstance() {
        return INSTANCE;
    }

    private HardcoreConfig config;
    private DeathTracker deathTracker;
    private MinecraftServer server;
    private final AtomicReference<ServerPlayer> pendingResetTrigger = new AtomicReference<>(null);
    private final AtomicBoolean resetInProgress = new AtomicBoolean(false);
    private int resetCountdown = -1;

    @Override
    public void onInitializeServer() {
        INSTANCE = this;
        LOGGER.info("[PhoenixProtocol] ═══════════════════════════════════════════");
        LOGGER.info("[PhoenixProtocol]   The Phoenix Protocol Initializing (Mojmap)");
        LOGGER.info("[PhoenixProtocol] ═══════════════════════════════════════════");

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
        
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            onPlayerJoin(handler.getPlayer());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            if (deathTracker != null) {
                deathTracker.saveStats();
            }
        });

        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
    }

    private void registerCommands(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(net.minecraft.commands.Commands.literal("phoenixprotocol")
            .requires(source -> source.getEntity() == null || (source.isPlayer() && source.getServer().getPlayerList().isOp(source.getPlayer().nameAndId())))
            .then(net.minecraft.commands.Commands.literal("config")
                .then(net.minecraft.commands.Commands.literal("set")
                    .then(net.minecraft.commands.Commands.literal("total_world_resets")
                        .then(net.minecraft.commands.Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                            .executes(context -> {
                                int val = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "value");
                                config.setTotalWorldResets(val);
                                context.getSource().sendSuccess(() -> Component.literal("Set total_world_resets to " + val), true);
                                return 1;
                            })))
                    .then(net.minecraft.commands.Commands.literal("default_death_limit")
                        .then(net.minecraft.commands.Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(context -> {
                                int val = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "value");
                                config.setDefaultDeathLimit(val);
                                context.getSource().sendSuccess(() -> Component.literal("Set default_death_limit to " + val), true);
                                return 1;
                            })))
                    .then(net.minecraft.commands.Commands.literal("reset_countdown_seconds")
                        .then(net.minecraft.commands.Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                            .executes(context -> {
                                int val = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "value");
                                config.setResetCountdownSeconds(val);
                                context.getSource().sendSuccess(() -> Component.literal("Set reset_countdown_seconds to " + val), true);
                                return 1;
                            })))
                    .then(net.minecraft.commands.Commands.literal("count_pvp_deaths")
                        .then(net.minecraft.commands.Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(context -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "value");
                                config.setCountPvpDeaths(val);
                                context.getSource().sendSuccess(() -> Component.literal("Set count_pvp_deaths to " + val), true);
                                return 1;
                            })))
                    .then(net.minecraft.commands.Commands.literal("wipe_advancements")
                        .then(net.minecraft.commands.Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(context -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "value");
                                config.setWipeAdvancements(val);
                                context.getSource().sendSuccess(() -> Component.literal("Set wipe_advancements to " + val), true);
                                return 1;
                            })))
                    .then(net.minecraft.commands.Commands.literal("wipe_stats")
                        .then(net.minecraft.commands.Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(context -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "value");
                                config.setWipeStats(val);
                                context.getSource().sendSuccess(() -> Component.literal("Set wipe_stats to " + val), true);
                                return 1;
                            })))
                    .then(net.minecraft.commands.Commands.literal("change_seed_on_reset")
                        .then(net.minecraft.commands.Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(context -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "value");
                                config.setChangeSeedOnReset(val);
                                context.getSource().sendSuccess(() -> Component.literal("Set change_seed_on_reset to " + val), true);
                                return 1;
                            })))
                    .then(net.minecraft.commands.Commands.literal("instigator_ban_type")
                        .then(net.minecraft.commands.Commands.argument("value", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(context -> {
                                String val = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "value");
                                config.setInstigatorBanType(val);
                                context.getSource().sendSuccess(() -> Component.literal("Set instigator_ban_type to " + val), true);
                                return 1;
                            })))
                    .then(net.minecraft.commands.Commands.literal("instigator_temp_ban_minutes")
                        .then(net.minecraft.commands.Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                            .executes(context -> {
                                int val = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "value");
                                config.setInstigatorTempBanMinutes(val);
                                context.getSource().sendSuccess(() -> Component.literal("Set instigator_temp_ban_minutes to " + val), true);
                                return 1;
                            })))
                )
                .then(net.minecraft.commands.Commands.literal("dimension")
                    .then(net.minecraft.commands.Commands.literal("add")
                        .then(net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.string())
                            .executes(context -> {
                                String val = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id");
                                if (config.addExemptDimension(val)) {
                                    context.getSource().sendSuccess(() -> Component.literal("Added " + val + " to exempt_dimensions"), true);
                                } else {
                                    context.getSource().sendFailure(Component.literal(val + " is already in exempt_dimensions"));
                                }
                                return 1;
                            })))
                    .then(net.minecraft.commands.Commands.literal("remove")
                        .then(net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.string())
                            .executes(context -> {
                                String val = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id");
                                if (config.removeExemptDimension(val)) {
                                    context.getSource().sendSuccess(() -> Component.literal("Removed " + val + " from exempt_dimensions"), true);
                                } else {
                                    context.getSource().sendFailure(Component.literal(val + " is not in exempt_dimensions"));
                                }
                                return 1;
                            })))
                )
            )
            .then(net.minecraft.commands.Commands.literal("player")
                .then(net.minecraft.commands.Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                    .then(net.minecraft.commands.Commands.literal("set_limit")
                        .then(net.minecraft.commands.Commands.argument("lives", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                            .executes(context -> {
                                ServerPlayer p = net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
                                int lives = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "lives");
                                
                                config.setPlayerLimit(p.getStringUUID(), lives);
                                
                                if (deathTracker != null) {
                                    deathTracker.resetPlayerCycleCount(p.getUUID());
                                }
                                
                                context.getSource().sendSuccess(() -> Component.literal("Set lives limit for " + p.getName().getString() + " to " + lives + " and reset their current cycle deaths."), true);
                                return 1;
                            })
                        )
                    )
                    .then(net.minecraft.commands.Commands.literal("clear_limit")
                        .executes(context -> {
                            ServerPlayer p = net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
                            if (config.removePlayerLimit(p.getStringUUID())) {
                                context.getSource().sendSuccess(() -> Component.literal("Cleared custom limit for " + p.getName().getString()), true);
                            } else {
                                context.getSource().sendFailure(Component.literal("No custom limit set for " + p.getName().getString()));
                            }
                            return 1;
                        })
                    )
                )
            )
        );
    }

    private void onServerStarted(MinecraftServer server) {
        this.server = server;
        config = HardcoreConfig.load(server.getServerDirectory());
        deathTracker = new DeathTracker(server.getServerDirectory());

        ServerLevel limbo = server.getLevel(DimensionManager.LIMBO);
        if (limbo != null) {
            LobbyBuilder.build(limbo);
        } else {
            LOGGER.error("[PhoenixProtocol] Limbo dimension not found during startup!");
        }
    }

    private void onEndServerTick(MinecraftServer server) {
        if (resetCountdown > 0) {
            if (resetCountdown == 40) {
                // Revive dead players 2 seconds early to give clients time to settle
                ResetSequence.reviveDeadPlayers(server);
            }
            resetCountdown--;
            if (resetCountdown > 0 && resetCountdown % 20 == 0) {
                int seconds = resetCountdown / 20;
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("§cWorld reset in " + seconds + "..."), false
                );
            }
        } else if (resetCountdown == 0) {
            resetCountdown = -1;
            ServerPlayer trigger = pendingResetTrigger.getAndSet(null);
            if (trigger == null) return;
            
            if (!resetInProgress.compareAndSet(false, true)) {
                return;
            }

            try {
                ResetSequence.execute(server, trigger, config, deathTracker);
            } finally {
                resetInProgress.set(false);
            }
        }
    }

    private void onPlayerJoin(ServerPlayer player) {


        int cycleDeaths = deathTracker.getCycleDeaths(player.getUUID());
        int limit = config.getDeathLimit(player);
        int remaining = Math.max(0, limit - cycleDeaths);

        player.sendSystemMessage(
                Component.literal("§7[§4PhoenixProtocol§7] §fYou have §c" + remaining
                        + "§f " + (remaining == 1 ? "life" : "lives") + " remaining this cycle.")
        );
    }

    public void handlePlayerDeath(ServerPlayer player) {
        if (config == null || deathTracker == null) return;

        int cycleDeaths = deathTracker.recordDeath(player);
        int limit = config.getDeathLimit(player);
        int remaining = Math.max(0, limit - cycleDeaths);

        Component deathMessage = Component.literal("§7[§4PhoenixProtocol§7] §f" + player.getName().getString()
                + " has died! §c" + remaining + "§f "
                + (remaining == 1 ? "life" : "lives") + " remaining.");
        server.getPlayerList().broadcastSystemMessage(deathMessage, false);

        if (deathTracker.shouldTriggerReset(player, config)) {
            if (pendingResetTrigger.compareAndSet(null, player)) {
                resetCountdown = config.getResetCountdownSeconds() * 20;
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("§4§lWORLD RESET INITIATED!").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false
                );
            }
        }
    }

    public HardcoreConfig getConfig() {
        return config;
    }

    public DeathTracker getDeathTracker() {
        return deathTracker;
    }
}
