package com.example.addon.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.addon.modules.RocketPilot;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayerEntity;

@Mixin(ClientPlayerEntity.class)
public abstract class RocketPilotMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickHead(CallbackInfo ci) {
        // Logic moved to RocketPilot module
    }

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void adjustPitch(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        RocketPilot module = Modules.get().get(RocketPilot.class);

        if (module == null || !module.isActive() || !player.isGliding()) {
            return;
        }
        // Logic moved to RocketPilot module
    }
}