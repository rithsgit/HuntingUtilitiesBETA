package com.example.addon.mixin;

import com.example.addon.modules.PortalTracker;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * This mixin helps PortalTracker detect portal block changes more efficiently
 * by marking chunks as dirty when portal-related blocks are modified.
 */
@Mixin(World.class)
public abstract class PortalTrackerMixin {

    /**
     * Monitor portal block state changes and mark chunks for re-scanning.
     */
    @Inject(
        method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
        at = @At("RETURN")
    )
    private void onSetBlockState(
        BlockPos pos,
        BlockState newState,
        int flags,
        int maxUpdateDepth,
        CallbackInfoReturnable<Boolean> cir
    ) {
        // Only process if the block state actually changed
        if (!cir.getReturnValue()) return;

        // Check if this is a portal-related block
        boolean isPortalBlock = newState.isOf(Blocks.NETHER_PORTAL) ||
                               newState.isOf(Blocks.END_PORTAL) ||
                               newState.isOf(Blocks.END_GATEWAY) ||
                               newState.isOf(Blocks.END_PORTAL_FRAME);

        if (isPortalBlock) {
            PortalTracker tracker = Modules.get().get(PortalTracker.class);
            if (tracker != null && tracker.isActive()) {
                // Mark the chunk as dirty so it gets re-scanned
                tracker.markChunkDirty(new ChunkPos(pos));
            }
        }
    }
}
