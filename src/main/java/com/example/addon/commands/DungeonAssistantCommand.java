package com.example.addon.commands;

import com.example.addon.modules.DungeonAssistant;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import java.util.Map;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class DungeonAssistantCommand extends Command {
    public DungeonAssistantCommand() {
        super("dungeon-assistant", "Displays dungeon element statistics.", "da");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            DungeonAssistant module = Modules.get().get(DungeonAssistant.class);

            if (module == null) {
                error("DungeonAssistant module not found.");
                return SINGLE_SUCCESS;
            }

            if (!module.isActive()) {
                warning("DungeonAssistant is not active. Enable it first.");
                return SINGLE_SUCCESS;
            }

            Map<DungeonAssistant.TargetType, Integer> counts = module.getTargetCounts();
            int total = module.getTotalTargets();

            info("§6=== Dungeon Assistant Stats ===");
            info("§cSpawners: §f" + counts.getOrDefault(DungeonAssistant.TargetType.SPAWNER, 0));
            info("§eChests: §f" + counts.getOrDefault(DungeonAssistant.TargetType.CHEST, 0));
            info("§6Chest Minecarts: §f" + counts.getOrDefault(DungeonAssistant.TargetType.CHEST_MINECART, 0));
            info("§aFiltered Blocks: §f" + counts.getOrDefault(DungeonAssistant.TargetType.CUSTOM_BLOCK, 0));
            info("§b§lTotal: §f" + total);

            return SINGLE_SUCCESS;
        });
    }
}
