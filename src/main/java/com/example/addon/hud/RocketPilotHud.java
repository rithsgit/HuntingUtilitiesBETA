package com.example.addon.hud;

import com.example.addon.HuntingUtilities;
import com.example.addon.modules.RocketPilot;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class RocketPilotHud extends HudElement {
    public static final HudElementInfo<RocketPilotHud> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "rocket-pilot-hud",
        "Displays RocketPilot status.",
        RocketPilotHud::new
    );

    public RocketPilotHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        RocketPilot rp = Modules.get().get(RocketPilot.class);
        if (!rp.isActive()) return;

        String status = "Normal";
        if (rp.pitch40Mode.get()) status = "Pitch40";
        else if (rp.oscillationMode.get()) status = "Oscillation";
        else if (rp.drunkMode.get()) status = "Drunk";

        renderer.text("RocketPilot: " + status, x, y, Color.WHITE, true);
    }
}