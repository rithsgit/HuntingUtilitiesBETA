package com.example.addon.mixin;

import com.example.addon.modules.Datamine;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    
    @Inject(method = "onBlockUpdate", at = @At("HEAD"))
    private void onBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        Datamine datamine = Modules.get().get(Datamine.class);
        if (datamine != null && datamine.isActive()) {
            // Tell Datamine the server officially confirmed this block changed
            datamine.onServerBlockUpdate(packet.getPos(), packet.getState());
        }
    }
}