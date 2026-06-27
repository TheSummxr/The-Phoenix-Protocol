package com.hardcorereset.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> getLevels();

    @Accessor("storageSource")
    net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess getStorageSource();

    @Accessor("executor")
    java.util.concurrent.Executor getExecutor();
}
