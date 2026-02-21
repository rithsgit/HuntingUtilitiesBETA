package com.example.addon.commands;

import com.example.addon.modules.RocketPilot;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;

public class RocketPilotCommand extends Command {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    public RocketPilotCommand() {
        super("rocketpilot", "Controls the RocketPilot module.", "rp");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        RocketPilot m = Modules.get().get(RocketPilot.class);
        if (m == null) {
            error("RocketPilot module not found.");
            return;
        }

        // Default: show status
        builder.executes(context -> {
            info("RocketPilot is %s.", m.isActive() ? "§aON" : "§cOFF");
            return 1;
        });

        // .rocketpilot on
        builder.then(literal("on").executes(ctx -> {
            if (!m.isActive()) m.toggle();
            info("RocketPilot §aenabled§r.");
            return 1;
        }));

        // .rocketpilot off
        builder.then(literal("off").executes(ctx -> {
            if (m.isActive()) m.toggle();
            info("RocketPilot §cdisabled§r.");
            return 1;
        }));

        // .rocketpilot toggle
        builder.then(literal("toggle").executes(ctx -> {
            m.toggle();
            info("RocketPilot %s.", m.isActive() ? "§aON" : "§cOFF");
            return 1;
        }));

        // .rocketpilot y [value]
        builder.then(literal("y")
            .then(argument("value", DoubleArgumentType.doubleArg(-64, 320))
                .executes(ctx -> {
                    double v = DoubleArgumentType.getDouble(ctx, "value");
                    m.targetY.set(v);
                    info("Target Y set to %.1f", v);
                    return 1;
                }))
            .executes(ctx -> {
                info("Current target Y: %.1f", m.targetY.get());
                return 1;
            })
        );

        // .rocketpilot status
        builder.then(literal("status").executes(ctx -> {
            if (mc.player == null || !m.isActive() || !mc.player.isGliding()) {
                info("RocketPilot: inactive or not elytra flying.");
                return 1;
            }

            double cy = mc.player.getY();
            double dy = cy - m.targetY.get();
            String mode = dy < -1 ? "Ascent" : (dy > 1 ? "Descent" : "Level");

            info("Y: %.1f (Δ%.1f) %s", cy, dy, mode);
            info("Fireworks: %d", getTotalRockets());

            if (m.durabilityMonitor.get()) {
                info("Elytra: %.1f%% (~%ds)", m.getDurabilityPercent(), estimateSeconds());
            }

            return 1;
        }));

        // .rocketpilot help
        builder.then(literal("help").executes(ctx -> {
            info(".rocketpilot / .rp usage:");
            info("  on / off / toggle");
            info("  y [value]");
            info("  status");
            info("  help");
            return 1;
        }));
    }

    private long getTotalRockets() {
        long cnt = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(Items.FIREWORK_ROCKET)) {
                cnt += inv.getStack(i).getCount();
            }
        }
        if (mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            cnt += mc.player.getOffHandStack().getCount();
        }
        return cnt;
    }

    private int estimateSeconds() {
        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) {
            return 0;
        }

        int remaining = elytra.getMaxDamage() - elytra.getDamage();

        RegistryEntry<net.minecraft.enchantment.Enchantment> unbreakingEntry = mc.world.getRegistryManager()
            .getOrThrow(RegistryKeys.ENCHANTMENT)
            .getOptional(Enchantments.UNBREAKING)
            .orElse(null);
        int unbreakingLevel = unbreakingEntry != null ? EnchantmentHelper.getLevel(unbreakingEntry, elytra) : 0;

        // Rough estimate
        return (int) (remaining * (1.0 + unbreakingLevel * 0.5));
}
}