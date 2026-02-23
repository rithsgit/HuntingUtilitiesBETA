package com.example.addon;

import com.example.addon.commands.*;
import com.example.addon.hud.*;
import com.example.addon.modules.*;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HuntingUtilities extends MeteorAddon {

    public static final Logger LOG = LoggerFactory.getLogger("Hunting Utilities");
    public static final Category CATEGORY = new Category("Hunting Utilities");

    @Override
    public void onInitialize() {
        LOG.info("Hunting Utilities addon initialized.");

        // ────────────────────────────────────────────────
        // Modules
        // ────────────────────────────────────────────────
        Modules.get().add(new DungeonAssistant());
        Modules.get().add(new RocketPilot());
        Modules.get().add(new LootLens());
        Modules.get().add(new ElytraAssistant());
        Modules.get().add(new PortalTracker());
        Modules.get().add(new PortalMaker());
        Modules.get().add(new GridLock());        
        Modules.get().add(new Graveyard());
        Modules.get().add(new ServerHealthcareSystem());


        // ────────────────────────────────────────────────
        // Commands
        // ────────────────────────────────────────────────
        Commands.add(new DungeonAssistantCommand());
        Commands.add(new LootLensCommand());
        Commands.add(new PortalTrackerCommand());
        Commands.add(new PortalMakerCommand());
        Commands.add(new RocketPilotCommand());
        Commands.add(new GridLockCommand());
        // ────────────────────────────────────────────────
        // HUD elements
        // ────────────────────────────────────────────────
        Hud.get().register(DungeonAssistantHud.INFO);
        Hud.get().register(LootLensHud.INFO);
        Hud.get().register(PortalTrackerHud.INFO);
        Hud.get().register(PortalMakerHud.INFO);
        Hud.get().register(RocketPilotHud.INFO);
        Hud.get().register(GridLockHud.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}