package com.example.addon.mixin;

import com.example.addon.modules.ThirdSight;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class ThirdSightMouseMixin {

    @Shadow private MinecraftClient client;
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void onUpdateMouse(CallbackInfo ci) {
        ThirdSight module = Modules.get().get(ThirdSight.class);
        if (module == null || !module.isActive() || !module.freeLook.get()) return;
        if (client.player == null || client.currentScreen != null) return;

        ci.cancel();

        // Vanilla's base sensitivity curve keeps the feel consistent with
        // normal mouse movement. Our sensitivity slider multiplies on top of
        // that — at the default of 8 it matches roughly normal third person
        // feel, higher values orbit faster.
        double vanillaSens = client.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double scale       = vanillaSens * vanillaSens * vanillaSens * module.sensitivity.get();

        double dx = cursorDeltaX * scale;
        double dy = cursorDeltaY * scale;

        if (client.options.getInvertYMouse().getValue()) dy = -dy;

        module.cameraYaw  += (float) dx;
        module.cameraPitch = Math.max(-90.0f, Math.min(90.0f, module.cameraPitch + (float) dy));
    }
}