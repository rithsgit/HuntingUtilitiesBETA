package com.example.addon.hud;

import com.example.addon.modules.GridLock;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;

public class GridLockHud extends HudElement {

    public static final HudElementInfo<GridLockHud> INFO = new HudElementInfo<>(
        null,
        "Grid Lock",
        "grid-lock",
        "Displays GridLock pattern status and current target.",
        GridLockHud::new
    );

    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Renders text with shadow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Base text color.")
        .defaultValue(new SettingColor(255, 140, 0, 220))
        .build()
    );

    private final MinecraftClient mc = MinecraftClient.getInstance();

    public GridLockHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        GridLock gl = Modules.get().get(GridLock.class);
        if (gl == null || !gl.isActive() || mc.player == null) {
            setSize(0, 0);
            return;
        }

        Color baseColor = color.get();
        double lineHeight = renderer.textHeight(shadow.get()) + 2;
        double yPos = y;
        double maxWidth = 0;

        // Line 1: Module + pattern
        String line1 = String.format("GridLock | %s | Spacing: %d chunks",
            gl.pattern.get().name(), gl.chunkSpacing.get());
        renderer.text(line1, x, yPos, baseColor, shadow.get());
        maxWidth = Math.max(maxWidth, renderer.textWidth(line1, shadow.get()));
        yPos += lineHeight;

        // Line 2: Player position
        String line2 = String.format("Pos: %.0f, %.0f, %.0f",
            mc.player.getX(), mc.player.getY(), mc.player.getZ());
        renderer.text(line2, x, yPos, baseColor, shadow.get());
        maxWidth = Math.max(maxWidth, renderer.textWidth(line2, shadow.get()));
        yPos += lineHeight;

        setSize(maxWidth, yPos - y - 2);
    }
}
