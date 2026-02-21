package com.example.addon.commands;

import com.example.addon.modules.PortalTracker;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

public class PortalTrackerCommand extends Command {
    
    public PortalTrackerCommand() {
        super("portal-tracker", "Allows you to see the total portals tracked by Portal Tracker.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            PortalTracker module = Modules.get().get(PortalTracker.class);
            if (module == null || !module.isActive()) {
                error("Portal Tracker is not active.");
                return SINGLE_SUCCESS;
            }

            info("Total Portals: " + module.getTotalPortals());
            info("Created Portals: " + module.getTotalCreated());
            return SINGLE_SUCCESS;
        });
    }
}
