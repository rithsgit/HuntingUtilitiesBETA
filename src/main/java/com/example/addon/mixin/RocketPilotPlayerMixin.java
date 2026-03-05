package com.example.addon.mixin;

import com.example.addon.modules.RocketPilot;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class RocketPilotPlayerMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void onChangeLookDirection(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        RocketPilot rp = Modules.get().get(RocketPilot.class);
        if (rp != null && rp.isActive() && rp.useFreeLookY.get() && ((ClientPlayerEntity)(Object)this).isGliding()) {
            rp.freeLookYaw += (float)cursorDeltaX * 0.15f;
            rp.freeLookPitch += (float)cursorDeltaY * 0.15f;
            rp.freeLookPitch = MathHelper.clamp(rp.freeLookPitch, -90.0F, 90.0F);
            ci.cancel();
        }
    }
}