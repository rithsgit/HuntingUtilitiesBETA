package com.example.addon.commands;

import com.example.addon.modules.GridLock;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

public class GridLockCommand extends Command {

    public GridLockCommand() {
        super("gridlock", "Controls the GridLock module.", "gl");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        GridLock m = Modules.get().get(GridLock.class);
        if (m == null) {
            error("GridLock module not found.");
            return;
        }

        // Default: show status
        builder.executes(context -> {
            info("GridLock is %s.", m.isActive() ? "§aON" : "§cOFF");
            return 1;
        });

        // .gridlock on
        builder.then(literal("on").executes(ctx -> {
            if (!m.isActive()) m.toggle();
            info("GridLock §aenabled§r.");
            return 1;
        }));

        // .gridlock off
        builder.then(literal("off").executes(ctx -> {
            if (m.isActive()) m.toggle();
            info("GridLock §cdisabled§r.");
            return 1;
        }));

        // .gridlock toggle
        builder.then(literal("toggle").executes(ctx -> {
            m.toggle();
            info("GridLock %s.", m.isActive() ? "§aON" : "§cOFF");
            return 1;
        }));

        // .gridlock status
        builder.then(literal("status").executes(ctx -> {
            info("GridLock: %s", m.isActive() ? "§aActive" : "§cInactive");
            info("Pattern: §e%s", m.pattern.get().name());
            info("Spacing: §e%d chunks", m.chunkSpacing.get());
            return 1;
        }));

        // .gridlock help
        builder.then(literal("help").executes(ctx -> {
            info(".gridlock / .gl usage:");
            info("  on / off / toggle");
            info("  status");
            info("  help");
            return 1;
        }));

        // .gridlock pause
        builder.then(literal("pause").executes(ctx -> {
            m.setPaused(true);
            info("GridLock paused.");
            return 1;
        }));

        // .gridlock resume
        builder.then(literal("resume").executes(ctx -> {
            m.setPaused(false);
            info("GridLock resumed.");
            return 1;
        }));
    }
}
