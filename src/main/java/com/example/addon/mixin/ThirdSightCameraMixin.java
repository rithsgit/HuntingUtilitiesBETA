package com.example.addon.mixin;

import com.example.addon.modules.ThirdSight;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class ThirdSightCameraMixin {

    // Shadow the protected method so we can call it from inject handlers.
    @org.spongepowered.asm.mixin.Shadow
    protected abstract void moveBy(float forward, float up, float right);

    /**
     * Intercept the distance passed to clipToSpace so we always get
     * our configured distance and blocks never pull the camera closer.
     */
    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void onClipToSpace(float desiredDistance, CallbackInfoReturnable<Float> cir) {
        ThirdSight module = Modules.get().get(ThirdSight.class);
        if (module == null || !module.isActive()) return;
        cir.setReturnValue((float) module.getDistance());
    }

    /**
     * When free-look or BirdsEye is active, replace the yaw passed to
     * Camera#setRotation with our independent cameraYaw so vanilla's
     * camera positioning logic uses our angle from the start.
     */
    @ModifyArg(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"
        ),
        index = 0
    )
    private float modifyCameraYaw(float yaw) {
        ThirdSight module = Modules.get().get(ThirdSight.class);
        if (module == null || !module.isFreeLookActive()) return yaw;
        return module.cameraYaw;
    }

    @ModifyArg(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"
        ),
        index = 1
    )
    private float modifyCameraPitch(float pitch) {
        ThirdSight module = Modules.get().get(ThirdSight.class);
        if (module == null || !module.isFreeLookActive()) return pitch;
        return module.cameraPitch;
    }

    /**
     * After Camera#update has fully positioned the camera, apply the lateral
     * shoulder offset by moving along the camera's right axis.
     * Camera#moveBy signature: moveBy(forward, up, right).
     */
    @Inject(method = "update", at = @At("RETURN"))
    private void onUpdateReturn(CallbackInfo ci) {
        ThirdSight module = Modules.get().get(ThirdSight.class);
        if (module == null || !module.isActive() || module.lateralOffset == 0f) return;
        moveBy(0, 0, module.lateralOffset);
    }
}