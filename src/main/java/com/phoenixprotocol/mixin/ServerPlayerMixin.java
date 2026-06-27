package com.phoenixprotocol.mixin;

import com.phoenixprotocol.PhoenixProtocolMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.phoenixprotocol.dimension.DimensionManager;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "die", at = @At("TAIL"))
    private void onHardcoreDeath(DamageSource damageSource, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;

        com.phoenixprotocol.config.HardcoreConfig config = PhoenixProtocolMod.getInstance().getConfig();

        if (config != null) {
            if (!config.isCountPvpDeaths()) {
                if (damageSource.getEntity() instanceof net.minecraft.world.entity.player.Player) {
                    return;
                }
            }

            String dimId = player.level().dimension().toString();
            for (String exempt : config.getExemptDimensions()) {
                if (dimId.contains(exempt)) {
                    return;
                }
            }
        }

        PhoenixProtocolMod.getInstance().handlePlayerDeath(player);
    }
}
