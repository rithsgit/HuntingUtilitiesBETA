package com.example.addon.hud;

import com.example.addon.modules.RocketPilot;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Formatting;

public class RocketPilotHud extends HudElement {

    public static final HudElementInfo<RocketPilotHud> INFO = new HudElementInfo<>(
        null,
        "Rocket Pilot",
        "rocket-pilot",
        "Displays RocketPilot status and flight information.",
        RocketPilotHud::new
    );

    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Renders text with shadow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Base text color.")
        .defaultValue(new SettingColor(255, 170, 0, 220))
        .build()
    );

    private final Setting<Boolean> showDurability = sgGeneral.add(new BoolSetting.Builder()
        .name("show-durability")
        .description("Show elytra durability percentage and estimated flight time.")
        .defaultValue(true)
        .build()
    );

    private final MinecraftClient mc = MinecraftClient.getInstance();

    public RocketPilotHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        RocketPilot pilot = Modules.get().get(RocketPilot.class);
        if (pilot == null || !pilot.isActive() || mc.player == null || !mc.player.isGliding()) {
            setSize(0, 0);
            return;
        }

        Color baseColor = color.get();
        double lineHeight = renderer.textHeight(shadow.get()) + 2;
        double yPos = y;
        double maxWidth = 0;

        // Line 1: Mode & altitude info
        double currentY = mc.player.getY();
        double deltaY = currentY - pilot.targetY.get();
        String mode = deltaY < -1 ? "Ascent" : (deltaY > 1 ? "Descent" : "Level");
        String line1 = String.format("RocketPilot | Y: %.1f Δ%.1f %s", currentY, deltaY, mode);
        renderer.text(line1, x, yPos, baseColor, shadow.get());
        maxWidth = Math.max(maxWidth, renderer.textWidth(line1, shadow.get()));
        yPos += lineHeight;

        // Line 2: Fireworks count
        long fireworks = getFireworksCount();
        String fwText = "Fireworks: " + fireworks;
        if (fireworks <= pilot.minRocketsWarning.get()) {
            fwText += " §cLOW";
        }
        renderer.text(fwText, x, yPos, baseColor, shadow.get());
        maxWidth = Math.max(maxWidth, renderer.textWidth(fwText, shadow.get()));
        yPos += lineHeight;

        // Line 3: Elytra durability (colored)
        if (showDurability.get()) {
            double durabilityPercent = pilot.getDurabilityPercent();
            int estimatedSeconds = estimateFlightSeconds();

            Formatting colorCode = durabilityPercent > 50 ? Formatting.GREEN :
                                   durabilityPercent > pilot.warnThreshold.get() ? Formatting.YELLOW :
                                   Formatting.RED;

            String duraText = colorCode + String.format("Elytra: %.1f%% (~%ds)", durabilityPercent, estimatedSeconds);

            renderer.text(duraText, x, yPos, null, shadow.get());  // null = parse § formatting codes
            maxWidth = Math.max(maxWidth, renderer.textWidth(duraText, shadow.get()));
            yPos += lineHeight;
        }

        setSize(maxWidth, yPos - y - 2);
    }

    private long getFireworksCount() {
        if (mc.player == null) return 0;

        long count = 0;
        var inventory = mc.player.getInventory();

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(Items.FIREWORK_ROCKET)) {
                count += stack.getCount();
            }
        }

        ItemStack offhand = mc.player.getOffHandStack();
        if (offhand.isOf(Items.FIREWORK_ROCKET)) {
            count += offhand.getCount();
        }

        return count;
    }

    private int estimateFlightSeconds() {
        if (mc.player == null || mc.world == null) return 0;

        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) return 0;

        int remainingDurability = elytra.getMaxDamage() - elytra.getDamage();
        if (remainingDurability <= 0) return 0;

        RegistryEntry<net.minecraft.enchantment.Enchantment> unbreakingEntry = mc.world.getRegistryManager()
            .getOrThrow(RegistryKeys.ENCHANTMENT)
            .getOptional(Enchantments.UNBREAKING)
            .orElse(null);

        int unbreakingLevel = unbreakingEntry != null ? EnchantmentHelper.getLevel(unbreakingEntry, elytra) : 0;

        // Approximate durability drain per second during active rocketing
        // Base ~0.9–1.1 dur/sec, Unbreaking III roughly doubles flight time
        double drainRate = 0.95 - (unbreakingLevel * 0.23);
        drainRate = Math.max(drainRate, 0.18); // prevent division by near-zero

        return (int) (remainingDurability / drainRate);
    }
}