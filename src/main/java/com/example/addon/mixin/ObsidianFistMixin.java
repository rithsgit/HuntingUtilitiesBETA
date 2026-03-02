package com.example.addon.mixin;

import com.example.addon.modules.ObsidianFist;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ObsidianFistMixin {

    @Shadow private int blockBreakingCooldown;

    /**
     * Zeroes the block-breaking cooldown every tick that ObsidianFist is active.
     *
     * updateBlockBreakingProgress() returns early if blockBreakingCooldown > 0,
     * which would otherwise limit break attempts to once every few ticks.
     * Injecting at HEAD means we zero it before that guard is read.
     *
     * attackBlock() does NOT check this cooldown, so no inject is needed there.
     */
    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"))
    private void onUpdateBlockBreakingProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        ObsidianFist module = Modules.get().get(ObsidianFist.class);
        if (module != null && module.isActive()) {
            this.blockBreakingCooldown = 0;
        }
    }
}