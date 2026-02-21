package com.example.addon.mixin;

import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.MinecraftClient;

/**
 * Mixin stub for DungeonAssistant.
 * Container-button injection is handled by HandledScreenMixin.
 * Add targeted injections here if specific client-level hooks are needed.
 */
@Mixin(MinecraftClient.class)
public class DungeonAssistantMixin {
    // Reserved for future DungeonAssistant-specific mixin hooks.
}
