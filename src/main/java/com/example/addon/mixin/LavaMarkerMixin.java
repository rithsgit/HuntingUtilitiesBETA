package com.example.addon.mixin;

import com.example.addon.modules.LavaMarker;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class LavaMarkerMixin {
    @Inject(
        method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
        at = @At("HEAD")
    )
    private void onSetBlockState(BlockPos pos, BlockState newState, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        LavaMarker lavaMarker = Modules.get().get(LavaMarker.class);
        if (lavaMarker != null && lavaMarker.isActive()) {
            World world = (World) (Object) this;
            BlockState oldState = world.getBlockState(pos);

            lavaMarker.onBlockUpdate(pos, oldState, newState);
        }
    }
}