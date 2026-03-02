package com.example.addon.mixin;

import com.example.addon.modules.PortalTracker;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V"))
    private void onSetScreen(MinecraftClient client, Screen screen) {
        PortalTracker portalTracker = Modules.get().get(PortalTracker.class);
        if (portalTracker != null && portalTracker.isActive() && portalTracker.portalGui.get() && screen == null) {
            return;
        }
        client.setScreen(screen);
    }
}