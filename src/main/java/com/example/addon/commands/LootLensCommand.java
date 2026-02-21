package com.example.addon.commands;

import com.example.addon.modules.LootLens;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

public class LootLensCommand extends Command {
    
    public LootLensCommand() {
        super("loot-lens", "Allows you to toggle the Loot Lens module.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            LootLens module = Modules.get().get(LootLens.class);
            if (module != null) {
                module.toggle();
                info("Toggled Loot Lens.");
            }
            return SINGLE_SUCCESS;
        });
    }
}