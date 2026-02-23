package com.example.addon.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.client.network.ClientPlayerEntity;

@Mixin(ClientPlayerEntity.class)
public class ServerHealthCareMixin {
    // The Healthcare module primarily uses Meteor Client's event system.
    // This mixin file is created as requested but is not strictly required for the module's current logic.
}