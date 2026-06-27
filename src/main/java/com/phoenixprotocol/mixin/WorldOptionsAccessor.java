package com.phoenixprotocol.mixin;

import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldOptions.class)
public interface WorldOptionsAccessor {

    @Mutable
    @Accessor("seed")
    void setSeed(long seed);
}
