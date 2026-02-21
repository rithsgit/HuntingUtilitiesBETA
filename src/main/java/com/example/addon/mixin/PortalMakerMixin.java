package com.example.addon.mixin;

import com.example.addon.modules.PortalMaker;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlintAndSteelItem.class)
public abstract class PortalMakerMixin {

    @Inject(
        method = "useOnBlock",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onUseOnBlock(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        PortalMaker portalMaker = Modules.get().get(PortalMaker.class);
        if (portalMaker == null || !portalMaker.isActive()) {
            return;
        }

        World world = context.getWorld();
        BlockPos clickedPos = context.getBlockPos();
        BlockPos firePos = clickedPos.offset(context.getSide());

        // Only proceed if we're trying to place fire inside what should be portal interior space
        boolean isPortalRelated = portalMaker.portalFramePositions.stream()
            .anyMatch(framePos -> {
                BlockPos up1 = framePos.up(1);
                BlockPos up2 = framePos.up(2);
                BlockPos up3 = framePos.up(3);

                return firePos.equals(up1) ||
                       firePos.equals(up2) ||
                       firePos.equals(up3);
            });

        if (!isPortalRelated) {
            return;
        }

        BlockState currentState = world.getBlockState(firePos);

        // Only place fire if the spot is air or replaceable (grass, vines, etc.)
        if (!currentState.isAir() && !currentState.isReplaceable()) {
            return;
        }

        // Place the fire block
        BlockState fireState = Blocks.FIRE.getDefaultState();
        world.setBlockState(firePos, fireState, 11); // 11 = notify neighbors + send to clients

        // Play flint & steel sound for feedback (client + server)
        world.playSound(
            context.getPlayer(),
            firePos,
            SoundEvents.ITEM_FLINTANDSTEEL_USE,
            SoundCategory.BLOCKS,
            1.0F,
            world.getRandom().nextFloat() * 0.4F + 0.8F
        );

        // Only damage the item on the server (prevents double-damage desync)
        if (!world.isClient && context.getPlayer() != null) {
            EquipmentSlot slot = context.getHand() == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            context.getStack().damage(1, context.getPlayer(), slot);
        }

        // Mark as success and cancel vanilla logic
        cir.setReturnValue(ActionResult.SUCCESS);
        cir.cancel();
    }
}