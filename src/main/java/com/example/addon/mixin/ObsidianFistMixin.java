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

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"))
    private void onUpdateBlockBreakingProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        ObsidianFist obsidianFist = Modules.get().get(ObsidianFist.class);
        if (obsidianFist != null && obsidianFist.isActive()) {
            this.blockBreakingCooldown = 0;
        }
    }

    @Inject(method = "attackBlock", at = @At("HEAD"))
    private void onAttackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        ObsidianFist obsidianFist = Modules.get().get(ObsidianFist.class);
        if (obsidianFist != null && obsidianFist.isActive()) {
            this.blockBreakingCooldown = 0;
        }
    }
}
