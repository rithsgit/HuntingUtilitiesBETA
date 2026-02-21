package com.example.addon.hud;

import com.example.addon.modules.LootLens;

import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class LootLensHud extends HudElement {
    
    public static final HudElementInfo<LootLensHud> INFO = new HudElementInfo<>(
        null, "Loot Lens",
        "loot-lens-hud",
        "Shows nearby container count.",
        LootLensHud::new
    );

    public LootLensHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        LootLens module = Modules.get().get(LootLens.class);

        if (module == null || !module.isActive()) return;

        int count = module.getTotalContainers();
        if (count == 0) return;

        String text = "Containers: " + count;
        setSize(renderer.textWidth(text, true), renderer.textHeight(true));

        renderer.quad(x, y, getWidth(), getHeight(), new Color(0, 0, 0, 150));
        renderer.text(text, x, y, Color.WHITE, true);
    }
}
