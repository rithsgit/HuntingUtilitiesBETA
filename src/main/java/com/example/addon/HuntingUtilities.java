package com.example.addon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.addon.commands.DungeonAssistantCommand;
import com.example.addon.commands.GridLockCommand;
import com.example.addon.commands.LootLensCommand;
import com.example.addon.commands.PortalMakerCommand;
import com.example.addon.commands.PortalTrackerCommand;
import com.example.addon.commands.RocketPilotCommand;
import com.example.addon.hud.DungeonAssistantHud;
import com.example.addon.hud.GridLockHud;
import com.example.addon.hud.LootLensHud;
import com.example.addon.hud.PortalMakerHud;
import com.example.addon.hud.PortalTrackerHud;
import com.example.addon.hud.RocketPilotHud;
import com.example.addon.modules.DungeonAssistant;
import com.example.addon.modules.ElytraAssistant;
import com.example.addon.modules.Graveyard;
import com.example.addon.modules.GridLock;
import com.example.addon.modules.LavaMarker;
import com.example.addon.modules.LootLens;
import com.example.addon.modules.ObsidianFist;
import com.example.addon.modules.PortalMaker;
import com.example.addon.modules.PortalTracker;
import com.example.addon.modules.RocketPilot;
import com.example.addon.modules.ServerHealthcareSystem;
import com.example.addon.modules.SignScanner;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class HuntingUtilities extends MeteorAddon {

    public static final Logger LOG = LoggerFactory.getLogger("Hunting Utilities");
    public static final Category CATEGORY = new Category("Hunting Utilities");
    public static final HudGroup HUD_GROUP = new HudGroup("Hunting Utilities");

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
        Modules.get().add(new ObsidianFist());
        Modules.get().add(new SignScanner());
        Modules.get().add(new LavaMarker());


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