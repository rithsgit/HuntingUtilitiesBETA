package com.example.addon.mixin;

import com.example.addon.modules.ElytraAssistant;
import com.example.addon.modules.RocketPilot;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class ElytraAssistantSwingMixin {
    @Inject(method = "swingHand", at = @At("HEAD"), cancellable = true)
    private void onSwingHand(Hand hand, CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isOf(Items.FIREWORK_ROCKET)) {
            // Check ElytraAssistant (for middle-click rockets)
            ElytraAssistant ea = Modules.get().get(ElytraAssistant.class);
            if (ea != null && ea.shouldSilentRocket()) {
                ci.cancel();
                return;
            }

            // Check RocketPilot
            RocketPilot rp = Modules.get().get(RocketPilot.class);
            if (rp != null && rp.isActive() && rp.silentRockets.get()) {
                ci.cancel();
            }
        }
    }
}