package com.example.addon.mixin;

import com.example.addon.modules.RocketPilot;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class RocketPilotMixin {
    @Shadow protected abstract void setPos(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow public abstract net.minecraft.util.math.Vec3d getPos();

    @Inject(method = "update", at = @At("RETURN"))
    private void onUpdate(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        RocketPilot rocketPilot = Modules.get().get(RocketPilot.class);
        if (rocketPilot != null && rocketPilot.isActive() && rocketPilot.useFreeLookY.get()) {
            if (focusedEntity instanceof LivingEntity living && living.isGliding()) {
                setPos(getPos().x, rocketPilot.freeLookY.get(), getPos().z);
                setRotation(rocketPilot.freeLookYaw, rocketPilot.freeLookPitch);
            }
        }
    }
}