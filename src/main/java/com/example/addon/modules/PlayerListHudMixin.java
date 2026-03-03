package com.example.addon.mixin;

import com.example.addon.modules.ServerHealthcareSystem;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Iterator;
import java.util.List;

@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {
    @Unique
    private int huntingUtilities$color = -1;

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V"), locals = LocalCapture.CAPTURE_FAILHARD)
    private void onBeforeDraw(DrawContext context, int scaledWidth, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci, List list, int i, int j, Iterator var10, int k, PlayerListEntry playerListEntry) {
        huntingUtilities$color = -1; // Reset
        ServerHealthcareSystem shs = Modules.get().get(ServerHealthcareSystem.class);
        if (shs != null && shs.isActive()) {
            String playerName = playerListEntry.getProfile().getName();

            if (shs.shouldColorFriendInTab() && shs.isFriend(playerName)) {
                huntingUtilities$color = shs.getFriendTabColor().getPacked();
            } else if (shs.shouldColorEnemyInTab() && shs.isEnemy(playerName)) {
                huntingUtilities$color = shs.getEnemyTabColor().getPacked();
            }
        }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V"))
    private void redirectDraw(DrawContext instance, TextRenderer textRenderer, Text text, int x, int y, int color) {
        int finalColor = (huntingUtilities$color != -1) ? huntingUtilities$color : color;
        instance.drawTextWithShadow(textRenderer, text, x, y, finalColor);
    }
}