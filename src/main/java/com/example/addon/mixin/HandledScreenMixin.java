package com.example.addon.mixin;

import com.example.addon.modules.DungeonAssistant;
import com.example.addon.modules.LootLens;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin extends Screen {
    @Shadow protected int backgroundWidth;
    @Shadow protected int x;
    @Shadow protected int y;

    protected HandledScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        LootLens ll = Modules.get().get(LootLens.class);
        // LootLens (higher priority)
        // If LootLens exists and is active -> adds buttons using addLootLensButtons()
        boolean llHandled = ll != null && ll.isActive();
        if (llHandled) {
			addLootLensButtons(ll);
        }

        // DungeonAssistant (fallback)
        // Only adds buttons if LootLens did not handle it
        if (!llHandled) {
            DungeonAssistant da = Modules.get().get(DungeonAssistant.class);
            if (da != null && da.isActive()) {
                addDungeonAssistantButtons(da);
            }
        }
    }

    private void addDungeonAssistantButtons(DungeonAssistant da) {
        // Skips entirely if the current screen is the player inventory (InventoryScreen)
        if ((Object) this instanceof InventoryScreen) return;

        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        int containerSlots = screen.getScreenHandler().slots.size() - 36;
        if (containerSlots <= 0) return;
		
        int buttonX = this.x + this.backgroundWidth + 5;
        int buttonY = this.y + 5;

        // Steal button
        this.addDrawableChild(new ButtonWidget.Builder(Text.literal("S"), button -> {
            for (int i = 0; i < containerSlots; i++) {
                if (screen.getScreenHandler().getSlot(i).hasStack()) {
                    client.interactionManager.clickSlot(
                        screen.getScreenHandler().syncId, i, 0,
                        SlotActionType.QUICK_MOVE, client.player);
                }
            }
        }).dimensions(buttonX, buttonY, 20, 20).build());

		// Dump button
        this.addDrawableChild(new ButtonWidget.Builder(Text.literal("D"), button -> {
            int end = screen.getScreenHandler().slots.size() - 9;
            for (int i = containerSlots; i < end; i++) {
                if (screen.getScreenHandler().getSlot(i).getStack().isEmpty()) continue;
                client.interactionManager.clickSlot(
                    screen.getScreenHandler().syncId, i, 0,
                    SlotActionType.QUICK_MOVE, client.player);
            }
        }).dimensions(buttonX, buttonY + 25, 20, 20).build());
    }

    private void addLootLensButtons(LootLens ll) {
		if ((Object) this instanceof InventoryScreen) return;

        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        int containerSlots = screen.getScreenHandler().slots.size() - 36;
        if (containerSlots <= 0) return;

        int buttonX = this.x + this.backgroundWidth + 5;
        int buttonY = this.y + 5;

		// Steal button
        this.addDrawableChild(new ButtonWidget.Builder(Text.literal("S"), button -> {
            for (int i = 0; i < containerSlots; i++) {
                if (screen.getScreenHandler().getSlot(i).hasStack()) {
                    client.interactionManager.clickSlot(
                        screen.getScreenHandler().syncId, i, 0,
                        SlotActionType.QUICK_MOVE, client.player);
                }
            }
        }).dimensions(buttonX, buttonY, 20, 20).build());

		// Dump button
        this.addDrawableChild(new ButtonWidget.Builder(Text.literal("D"), button -> {
            int end = screen.getScreenHandler().slots.size() - 9;
            for (int i = containerSlots; i < end; i++) {
                if (screen.getScreenHandler().getSlot(i).getStack().isEmpty()) continue;
                client.interactionManager.clickSlot(
                    screen.getScreenHandler().syncId, i, 0,
                    SlotActionType.QUICK_MOVE, client.player);
            }
        }).dimensions(buttonX, buttonY + 25, 20, 20).build());
    }
}
