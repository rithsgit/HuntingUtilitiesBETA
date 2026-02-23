package com.example.addon.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientPlayerEntity.class)
public class ServerHealthcareSystemMixin {
    // The ServerHealthcareSystem module primarily uses Meteor Client's event system.
    // This mixin file is a placeholder and not strictly required for the module's current logic.
}