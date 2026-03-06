package com.example.addon.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private {@code cursorDeltaX} and {@code cursorDeltaY} fields
 * from {@link Mouse} so {@link ThirdSightMouseMixin} can read raw mouse
 * movement without using reflection.
 *
 * Register in mixins.<modid>.json under "client":
 *   "IMouseAccessor"
 */
@Mixin(Mouse.class)
public interface IMouseAccessor {

    @Accessor("cursorDeltaX")
    double getCursorDeltaX();

    @Accessor("cursorDeltaY")
    double getCursorDeltaY();
}