package com.example.addon.hud;

import com.example.addon.modules.PortalTracker;

import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class PortalTrackerHud extends HudElement {
    
    public static final HudElementInfo<PortalTrackerHud> INFO = new HudElementInfo<>(
        null, "Portal Tracker",
        "portal-tracker",
        "Shows the number of portals found.",
        PortalTrackerHud::new
    );

    public PortalTrackerHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        PortalTracker module = Modules.get().get(PortalTracker.class);

        if (module == null || !module.isActive()) return;

        int count = module.getTotalPortals();
        if (count == 0) return;

        String text = "Portal Tracker: " + count;
        setSize(renderer.textWidth(text, true), renderer.textHeight(true));

        renderer.quad(x, y, getWidth(), getHeight(), new Color(0, 0, 0, 150));
        renderer.text(text, x, y, Color.CYAN, true);
    }
}
