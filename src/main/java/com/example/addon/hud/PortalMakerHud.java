package com.example.addon.hud;

import com.example.addon.modules.PortalMaker;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

public class PortalMakerHud extends HudElement {
    public static final HudElementInfo<PortalMakerHud> INFO = new HudElementInfo<>(
        null, "Portal Maker HUD",
        "portal-maker-hud",
        "Displays the progress of the Portal Maker module.",
        PortalMakerHud::new
    );

    public PortalMakerHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        PortalMaker module = Modules.get().get(PortalMaker.class);
        if (module == null || !module.isActive()) return;

        if (module.portalFramePositions.isEmpty()) return;

        int total = module.portalFramePositions.size();
        int placed = 0;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) {
            for (BlockPos pos : module.portalFramePositions) {
                if (mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
                    placed++;
                }
            }
        }

        String text = "Portal: " + placed + "/" + total;

        setSize(renderer.textWidth(text, true), renderer.textHeight(true));

        renderer.quad(x, y, getWidth(), getHeight(), new Color(0, 0, 0, 150));
        renderer.text(text, x, y, Color.MAGENTA, true);
    }
}