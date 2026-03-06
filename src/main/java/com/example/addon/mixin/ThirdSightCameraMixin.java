package com.example.addon.mixin;

import com.example.addon.modules.ThirdSight;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class ThirdSightCameraMixin {

    /**
     * Intercept the distance passed to clipToSpace so we always get
     * our configured distance and blocks never pull the camera closer.
     */
    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void onClipToSpace(float desiredDistance, CallbackInfoReturnable<Float> cir) {
        ThirdSight module = Modules.get().get(ThirdSight.class);
        if (module == null || !module.isActive()) return;
        cir.setReturnValue((float) module.distance.get().doubleValue());
    }

    /**
     * When free-look is on, intercept the yaw argument passed to
     * Camera#setRotation inside Camera#update and replace it with our
     * independent cameraYaw. This is the same technique Meteor's FreeLook
     * uses — modifying the rotation argument at the point it's set, rather
     * than overwriting it after the fact, so vanilla's camera positioning
     * logic uses our angle from the start.
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
        if (module == null || !module.isActive() || !module.freeLook.get()) return yaw;
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
        if (module == null || !module.isActive() || !module.freeLook.get()) return pitch;
        return module.cameraPitch;
    }
}