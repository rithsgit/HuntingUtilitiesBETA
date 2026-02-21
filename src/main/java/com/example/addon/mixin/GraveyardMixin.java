package com.example.addon.mixin;

import com.example.addon.modules.Graveyard;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.MinecraftClient;
import meteordevelopment.orbit.EventHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class GraveyardMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTickHead(CallbackInfo ci) {
        // Runs at the VERY beginning of each client tick
        // Usually prefer TickEvent.Pre instead — but useful if you need to act before almost everything

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        Graveyard graveyard = Modules.get().get(Graveyard.class);
        if (graveyard == null || !graveyard.isActive()) return;

        // Example: force a manual update / extra logic every tick
        // (most likely NOT needed if you're already using TickEvent.Pre in the module)
        // graveyard.someExtraTickLogic();   // ← add method in Graveyard if needed

        // Or example: check something very early
        // if (someCondition) mc.options.sneakKey.setPressed(true);
    }

    // Optional: run code at the END of the tick (after most game logic)
    @Inject(method = "tick", at = @At("TAIL"))
    private void onClientTickTail(CallbackInfo ci) {
        // Rarely needed — TAIL runs after render tick, entity updates, etc.
        // Prefer this only for very late operations (e.g. final cleanup)
    }
}