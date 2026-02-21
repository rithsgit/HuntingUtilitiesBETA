package com.example.addon.hud;

import com.example.addon.modules.DungeonAssistant;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.Map;

public class DungeonAssistantHud extends HudElement {
    public static final HudElementInfo<DungeonAssistantHud> INFO = new HudElementInfo<>(
        null, "Dungeon Assistant",
        "dungeon-assistant-hud",
        "Displays dungeon element counts.",
        DungeonAssistantHud::new
    );

    public DungeonAssistantHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        DungeonAssistant module = Modules.get().get(DungeonAssistant.class);
        if (module == null || !module.isActive()) return;

        Map<DungeonAssistant.TargetType, Integer> counts = module.getTargetCounts();
        int total = module.getTotalTargets();

        if (total == 0) return;

        double width = renderer.textWidth("Dungeon Assistant", true);
        double height = renderer.textHeight(true) * 6 + 10;
        setSize(width, height);

        renderer.quad(x, y, getWidth(), getHeight(), new Color(0, 0, 0, 150));

        double yPos = y + 2;
        renderer.text("§6Dungeon Assistant", x + 2, yPos, Color.ORANGE, true);
        yPos += renderer.textHeight(true) + 2;

        renderer.text("§cSpawners: §f" + counts.getOrDefault(DungeonAssistant.TargetType.SPAWNER, 0), x + 2, yPos, Color.WHITE, true);
        yPos += renderer.textHeight(true);

        renderer.text("§eChests: §f" + counts.getOrDefault(DungeonAssistant.TargetType.CHEST, 0), x + 2, yPos, Color.WHITE, true);
        yPos += renderer.textHeight(true);

        renderer.text("§6Minecarts: §f" + counts.getOrDefault(DungeonAssistant.TargetType.CHEST_MINECART, 0), x + 2, yPos, Color.WHITE, true);
        yPos += renderer.textHeight(true);

        renderer.text("§aFiltered Blocks: §f" + counts.getOrDefault(DungeonAssistant.TargetType.CUSTOM_BLOCK, 0), x + 2, yPos, Color.WHITE, true);
        yPos += renderer.textHeight(true);

        renderer.text("§7Total: §f" + total, x + 2, yPos, Color.WHITE, true);
    }
}
