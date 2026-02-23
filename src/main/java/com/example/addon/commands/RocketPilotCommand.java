package com.example.addon.commands;

import com.example.addon.modules.RocketPilot;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class RocketPilotCommand extends Command {
    public RocketPilotCommand() {
        super("rocket-pilot", "rp");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            Modules.get().get(RocketPilot.class).toggle();
            return SINGLE_SUCCESS;
        });
    }
}