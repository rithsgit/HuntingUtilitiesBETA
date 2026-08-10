package com.example.addon.mixin;

import com.example.addon.modules.Handmold;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PotionItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HandmoldMixin {

    private static final ThreadLocal<Boolean> RENDERING_CENTERED = ThreadLocal.withInitial(() -> false);

    @Inject(
        method = "renderFirstPersonItem",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onRenderFirstPersonItem(
        AbstractClientPlayerEntity player,
        float tickDelta,
        float pitch,
        Hand hand,
        float swingProgress,
        ItemStack item,
        float equipProgress,
        MatrixStack matrices,
        VertexConsumerProvider vertexConsumers,
        int light,
        CallbackInfo ci
    ) {
        // Prevent re-entry when we invoke the original ourselves
        if (RENDERING_CENTERED.get()) return;

        Handmold mod = Modules.get().get(Handmold.class);
        if (mod == null || !mod.isActive()) return;

        boolean isMain = hand == Hand.MAIN_HAND;

        // ── Hide checks ───────────────────────────────────────────────────────
        if (isMain && mod.shouldHideEmptyMainhand() && item.isEmpty()) {
            ci.cancel();
            return;
        }
        if (!isMain && mod.shouldHideOffhandCompletely()) {
            ci.cancel();
            return;
        }

        // ── Read the correct hand's settings ─────────────────────────────────
        double tx    = isMain ? mod.getMainX()     : mod.getOffX();
        double ty    = isMain ? mod.getMainY()     : mod.getOffY();
        double tz    = isMain ? mod.getMainZ()     : mod.getOffZ();
        double scale = isMain ? mod.getMainScale() : mod.getOffScale();
        double rotX  = isMain ? mod.getMainRotX()  : mod.getOffRotX();
        double rotY  = isMain ? mod.getMainRotY()  : mod.getOffRotY();
        double rotZ  = isMain ? mod.getMainRotZ()  : mod.getOffRotZ();

        // Only intercept if something is actually changed — otherwise let
        // vanilla run untouched so default rendering is never affected
        boolean hasTransform = tx != 0 || ty != 0 || tz != 0
            || scale != 1.0
            || rotX != 0 || rotY != 0 || rotZ != 0;

        // ── Eating/drinking centering ─────────────────────────────────────────
        boolean isCentering = false;
        float   extraOffset = 0f;
        if (player.isUsingItem() && player.getActiveHand() == hand) {
            boolean isFood      = item.get(DataComponentTypes.FOOD) != null;
            boolean isDrinkable = item.getItem() instanceof PotionItem
                || item.isOf(Items.MILK_BUCKET)
                || item.isOf(Items.HONEY_BOTTLE);
            if (isFood || isDrinkable) {
                isCentering = true;
                switch (mod.getEatPosition()) {
                    case StayInPlace ->
                        // No movement — stay at configured X the whole time
                        extraOffset = (float) tx;
                    case Custom -> {
                        // Lerp from the custom target X toward the configured X
                        // as equipProgress goes 0→1, so the hand always moves inward.
                        // Defaults to 0.0 (center) unless changed by the user.
                        double target = mod.getEatTargetX();
                        extraOffset = (float)(target + (tx - target) * equipProgress);
                    }
                }
            }
        }

        if (!hasTransform && !isCentering) return;

        // ── Cancel vanilla, apply our transforms, re-invoke ───────────────────
        ci.cancel();

        matrices.push();

        if (isCentering) {
            matrices.translate(extraOffset, ty, tz);
        } else {
            matrices.translate(tx, ty, tz);
        }

        matrices.scale((float) scale, (float) scale, (float) scale);
        if (rotX != 0) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) rotX));
        if (rotY != 0) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) rotY));
        if (rotZ != 0) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) rotZ));

        RENDERING_CENTERED.set(true);
        try {
            ((HeldItemRendererAccessor)(Object)this).invokeRenderFirstPersonItem(
                player, tickDelta, pitch, hand,
                isCentering ? 0.0f : swingProgress,
                item, equipProgress, matrices, vertexConsumers, light
            );
        } finally {
            RENDERING_CENTERED.set(false);
            matrices.pop();
        }
    }
}