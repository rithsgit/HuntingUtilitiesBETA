package com.example.addon;

import com.example.addon.modules.DungeonAssistant;
import com.example.addon.modules.ElytraAssistant;
import com.example.addon.modules.Graveyard;
import com.example.addon.modules.GridLock;
import com.example.addon.modules.Inventory101;
import com.example.addon.modules.LavaMarker;
import com.example.addon.modules.LootLens;
import com.example.addon.modules.Mobanom;
import com.example.addon.modules.ObsidianFist;
import com.example.addon.modules.PortalMaker;
import com.example.addon.modules.PortalTracker;
import com.example.addon.modules.RocketPilot;
import com.example.addon.modules.ServerHealthcareSystem;
import com.example.addon.modules.SignScanner;
import com.example.addon.modules.Timethrottle;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HuntingUtilities extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger(HuntingUtilities.class);
    public static final Category CATEGORY = new Category("Hunting Utilities");
    public static final HudGroup HUD_GROUP = new HudGroup("Hunting Utilities");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Hunting Utilities");

        // Modules
        Modules modules = Modules.get();
        modules.add(new DungeonAssistant());
        modules.add(new ElytraAssistant());
        modules.add(new Graveyard());
        modules.add(new GridLock());
        modules.add(new Inventory101());
        modules.add(new LavaMarker());
        modules.add(new LootLens());
        modules.add(new ObsidianFist());
        modules.add(new PortalMaker());
        modules.add(new PortalTracker());
        modules.add(new RocketPilot());
        modules.add(new ServerHealthcareSystem());
        modules.add(new SignScanner());
        Modules.get().add(new Timethrottle());
        Modules.get().add(new Mobanom());
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