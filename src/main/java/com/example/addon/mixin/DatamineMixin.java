package com.example.addon.mixin;

import com.example.addon.modules.Datamine;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects vanilla block breaking to Datamine.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class DatamineMixin {

    @Shadow
    private int blockBreakingCooldown;

    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void onAttack(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
        this.datamine$mine(pos, side, info);
    }

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void onUpdate(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
        this.datamine$mine(pos, side, info);
    }

    @Unique
    private void datamine$mine(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
        MinecraftClient client = MinecraftClient.getInstance();
        Datamine module = Modules.get().get(Datamine.class);

        if (module == null || !module.isActive() ||
            client.player == null || client.player.isCreative()) {
            return;
        }

        if (module.bypass(pos)) {
            // If the block is fast enough to bypass the queue, let vanilla handle it
            // but remove the 5-tick cooldown so it breaks instantly.
            this.blockBreakingCooldown = 0;
            return;
        }

        // Otherwise, route it into the Datamine packet queue
        module.mine(pos, side);
        info.setReturnValue(true);
    }
}