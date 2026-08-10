package com.example.addon.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.utils.GlowingRegistry;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AbstractDonkeyEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

public class Mobanom extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum AnomalyType {
        DIMENSION_NETHER,
        DIMENSION_END,
        DIMENSION_OVERWORLD,
        ITEM,
        CHESTED
    }

    public enum HighlightMode {
        Wireframe("Wireframe"),
        Spectral("Spectral"),
        Pulse("Pulse");

        private final String title;
        HighlightMode(String title) { this.title = title; }

        @Override public String toString() { return title; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral     = settings.getDefaultGroup();
    private final SettingGroup sgColors      = settings.createGroup("Colors");
    private final SettingGroup sgItemAnomaly = settings.createGroup("Item Anomaly");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<HighlightMode> highlightMode = sgGeneral.add(new EnumSetting.Builder<HighlightMode>()
        .name("highlight-mode")
        .description("How anomalous mobs are outlined. Wireframe draws a box outline; Spectral uses the vanilla glow pipeline; Pulse uses a fading bloom effect.")
        .defaultValue(HighlightMode.Wireframe)
        .build()
    );

    private final Setting<Boolean> chatNotification = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notification")
        .description("Notify in chat when an anomaly is detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Set<EntityType<?>>> ignoredEntities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("ignored-entities")
        .description("Entities to ignore.")
        .defaultValue(new HashSet<>())
        .build()
    );

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("The range to detect anomalies.")
        .defaultValue(128).min(1).sliderMax(256)
        .build()
    );

    // ── Highlight rendering ───────────────────────────────────────────────────

    private final Setting<Integer> glowLayers = sgGeneral.add(new IntSetting.Builder()
        .name("glow-layers")
        .description("Number of bloom layers rendered around each mob.")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> highlightMode.get() == HighlightMode.Wireframe || highlightMode.get() == HighlightMode.Pulse)
        .build()
    );

    private final Setting<Double> glowSpread = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow-spread")
        .description("How far each bloom layer expands outward (in blocks).")
        .defaultValue(0.05).min(0.01).sliderMax(0.2)
        .visible(() -> highlightMode.get() == HighlightMode.Wireframe || highlightMode.get() == HighlightMode.Pulse)
        .build()
    );

    private final Setting<Integer> glowBaseAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("glow-base-alpha")
        .description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(60).min(10).sliderMax(150)
        .visible(() -> highlightMode.get() == HighlightMode.Wireframe)
        .build()
    );

    private final Setting<Double> pulseSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("pulse-speed")
        .description("Pulse cycle speed. 1.0 = one full fade in/out per second.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMax(3.0)
        .visible(() -> highlightMode.get() == HighlightMode.Pulse)
        .build()
    );

    private final Setting<Integer> pulseMinAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("pulse-min-alpha")
        .description("Lowest alpha reached during the pulse (0 = invisible).")
        .defaultValue(15).min(0).max(255).sliderMax(100)
        .visible(() -> highlightMode.get() == HighlightMode.Pulse)
        .build()
    );

    private final Setting<Integer> pulseMaxAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("pulse-max-alpha")
        .description("Peak alpha reached during the pulse.")
        .defaultValue(220).min(50).max(255).sliderMax(255)
        .visible(() -> highlightMode.get() == HighlightMode.Pulse)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Colors
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<SettingColor> overworldLineColor = sgColors.add(new ColorSetting.Builder()
        .name("overworld-line-color")
        .description("Glow color for Overworld mobs found in other dimensions.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .build()
    );

    private final Setting<SettingColor> netherLineColor = sgColors.add(new ColorSetting.Builder()
        .name("nether-line-color")
        .description("Glow color for Nether mobs found in other dimensions.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    private final Setting<SettingColor> endLineColor = sgColors.add(new ColorSetting.Builder()
        .name("end-line-color")
        .description("Glow color for End mobs found in other dimensions.")
        .defaultValue(new SettingColor(255, 0, 255, 255))
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Item Anomaly
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> detectUnnaturalItems = sgItemAnomaly.add(new BoolSetting.Builder()
        .name("detect-unnatural-items")
        .description("Detects mobs holding or wearing player-like items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> itemAnomalyLineColor = sgItemAnomaly.add(new ColorSetting.Builder()
        .name("item-anomaly-line-color")
        .description("The glow color for mobs with unnatural items.")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .visible(detectUnnaturalItems::get)
        .build()
    );

    private final Setting<Boolean> detectPumpkins = sgItemAnomaly.add(new BoolSetting.Builder()
        .name("detect-pumpkins")
        .description("Detects mobs wearing carved pumpkins or jack-o'-lanterns.")
        .defaultValue(true)
        .visible(detectUnnaturalItems::get)
        .build()
    );

    private final Setting<Boolean> detectChestedAnimals = sgItemAnomaly.add(new BoolSetting.Builder()
        .name("detect-chested-animals")
        .description("Detects animals (donkeys, llamas, etc.) carrying a chest.")
        .defaultValue(true)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Native-origin sets
    //
    // Each set lists the mobs NATIVE to that dimension.
    // Detection is then trivial: a mob is anomalous if it is NOT in the native
    // set for the dimension the player is currently in.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Mobs native ONLY to the Overworld. Anomalous in the Nether and the End.
     *
     * Note: ENDERMAN is intentionally absent — it spawns naturally in both
     * the Overworld and the End, so it is not anomalous in either dimension.
     * SPIDER / CAVE_SPIDER also spawn in Nether fortresses, so they are excluded.
     */
    private static final Set<EntityType<?>> OVERWORLD_NATIVES = Set.of(
        // Passives
        EntityType.ALLAY,
        EntityType.AXOLOTL,
        EntityType.BAT,
        EntityType.CAMEL,
        EntityType.CAT,
        EntityType.CHICKEN,
        EntityType.COD,
        EntityType.COW,
        EntityType.DONKEY,
        EntityType.FOX,
        EntityType.FROG,
        EntityType.GLOW_SQUID,
        EntityType.HORSE,
        EntityType.MOOSHROOM,
        EntityType.MULE,
        EntityType.OCELOT,
        EntityType.PARROT,
        EntityType.PIG,
        EntityType.RABBIT,
        EntityType.SALMON,
        EntityType.SHEEP,
        EntityType.SNIFFER,
        EntityType.SNOW_GOLEM,
        EntityType.SQUID,
        EntityType.TADPOLE,
        EntityType.TROPICAL_FISH,
        EntityType.TURTLE,
        EntityType.VILLAGER,
        EntityType.WANDERING_TRADER,
        // Neutrals (Overworld-only)
        EntityType.BEE,
        EntityType.IRON_GOLEM,
        EntityType.POLAR_BEAR,
        EntityType.WOLF,
        // Hostiles (Overworld-only)
        EntityType.ZOMBIE,
        EntityType.CREEPER,
        EntityType.WITCH,
        EntityType.PHANTOM,
        EntityType.DROWNED,
        EntityType.HUSK,
        EntityType.STRAY,
        EntityType.ZOMBIE_VILLAGER,
        EntityType.VINDICATOR,
        EntityType.EVOKER,
        EntityType.PILLAGER,
        EntityType.RAVAGER,
        EntityType.VEX,
        EntityType.ILLUSIONER,
        EntityType.GUARDIAN,
        EntityType.ELDER_GUARDIAN,
        EntityType.SILVERFISH,
        EntityType.ZOMBIE_HORSE
        // SKELETON and SKELETON_HORSE omitted — spawn naturally in Nether fortresses
    );

    /**
     * Mobs native ONLY to the Nether. Anomalous in the Overworld and the End.
     *
     * Note: SPIDER / CAVE_SPIDER spawn in Nether fortresses so are excluded
     * from OVERWORLD_NATIVES but are NOT listed here either — they are
     * multi-dimensional and never flagged as anomalous.
     */
    private static final Set<EntityType<?>> NETHER_NATIVES = Set.of(
        EntityType.GHAST,
        EntityType.BLAZE,
        EntityType.WITHER_SKELETON,
        EntityType.MAGMA_CUBE,
        EntityType.PIGLIN,
        EntityType.PIGLIN_BRUTE,
        EntityType.HOGLIN,
        EntityType.ZOGLIN,
        EntityType.STRIDER
        // ZOMBIFIED_PIGLIN omitted — it is the result of a Piglin entering the
        // Overworld, so finding one there is expected, not anomalous.
    );

    /**
     * Mobs native ONLY to the End. Anomalous in the Overworld and the Nether.
     *
     * Note: ENDERMAN spawns in Overworld, Nether, and End — excluded from all
     * sets so it is never flagged.
     */
    private static final Set<EntityType<?>> END_NATIVES = Set.of(
        EntityType.SHULKER,
        EntityType.ENDERMITE,
        EntityType.ENDER_DRAGON
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private final Map<Integer, AnomalyType> highlightedEntities = new HashMap<>();
    private final Set<Integer>              notifiedEntities    = new HashSet<>();

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public Mobanom() {
        super(Tim.CATEGORY, "mobanom", "Detects and highlights mobs in the wrong dimension or with unnatural items.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        highlightedEntities.clear();
        notifiedEntities.clear();
        GlowingRegistry.clear();
    }

    @Override
    public void onDeactivate() {
        highlightedEntities.clear();
        notifiedEntities.clear();
        GlowingRegistry.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        highlightedEntities.clear();
        GlowingRegistry.clear();
        notifiedEntities.removeIf(id -> mc.world.getEntityById(id) == null);

        String dim = mc.world.getRegistryKey().getValue().toString();
        boolean spectral = highlightMode.get() == HighlightMode.Spectral;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof MobEntity mob)) continue;
            if (ignoredEntities.get().contains(mob.getType())) continue;
            if (mc.player.distanceTo(mob) > range.get()) continue;

            AnomalyType type = resolveAnomalyType(mob, dim);
            if (type == null) continue;

            highlightedEntities.put(mob.getId(), type);

            if (spectral) {
                SettingColor c = getColorForType(type);
                GlowingRegistry.add(mob.getId(), (255 << 24) | (c.r << 16) | (c.g << 8) | c.b);
            }

            if (chatNotification.get() && notifiedEntities.add(mob.getId())) {
                String mobName = mob.getType().getName().getString();
                switch (type) {
                    case CHESTED -> info("Chested animal detected: "    + mobName);
                    case ITEM    -> info("Item anomaly detected: "      + mobName);
                    default      -> info("Dimension anomaly detected: " + mobName);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Render
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null || highlightedEntities.isEmpty()) return;

        boolean wireframe = highlightMode.get() == HighlightMode.Wireframe;
        boolean pulse = highlightMode.get() == HighlightMode.Pulse;

        for (Map.Entry<Integer, AnomalyType> entry : highlightedEntities.entrySet()) {
            Entity entity = mc.world.getEntityById(entry.getKey());
            if (!(entity instanceof MobEntity mob)) continue;

            SettingColor color = getColorForType(entry.getValue());

            if (wireframe) {
                renderGlowLayers(event, mob.getBoundingBox(), color);
                event.renderer.box(mob.getBoundingBox(), withAlpha(color, 0), color, ShapeMode.Lines, 0);
            } else if (pulse) {
                renderPulseBox(event, mob.getBoundingBox(), color);
            }
            // Spectral: outline handled entirely by GlowingRegistry — nothing to draw here.
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Bloom & Pulse Rendering
    // ═══════════════════════════════════════════════════════════════════════════

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int    layers    = glowLayers.get();
        double spread    = glowSpread.get();
        int    baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            int layerAlpha   = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i - 1) / layers)));
            event.renderer.box(box.expand(expansion), withAlpha(color, layerAlpha), withAlpha(color, 0), ShapeMode.Sides, 0);
        }
    }

    private float getPulseFactor() {
        double speed = pulseSpeed.get();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float)((Math.sin(phase) + 1.0) * 0.5);
    }

    private int applyPulse(int baseAlpha) {
        float f = getPulseFactor();
        int min = pulseMinAlpha.get();
        int max = pulseMaxAlpha.get();
        return Math.min(255, Math.max(0, (int)(min + (max - min) * f)));
    }

    private SettingColor pulseColor(SettingColor base) {
        return withAlpha(base, applyPulse(base.a));
    }

    private void renderPulseBox(Render3DEvent event, Box box, SettingColor base) {
        int pa = applyPulse(base.a);
        SettingColor pColor = withAlpha(base, pa);
        int layers = glowLayers.get();
        double spread = glowSpread.get();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double)(i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int)(pa * taper));
            event.renderer.box(box.expand(expansion),
                withAlpha(pColor, layerAlpha), withAlpha(pColor, 0), ShapeMode.Sides, 0);
        }
        event.renderer.box(box, withAlpha(pColor, pa / 3), pColor, ShapeMode.Both, 0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Detection Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private AnomalyType resolveAnomalyType(MobEntity mob, String dimension) {
        if (detectChestedAnimals.get() && hasChestAttachment(mob)) return AnomalyType.CHESTED;
        if (detectUnnaturalItems.get() && hasUnnaturalItems(mob))  return AnomalyType.ITEM;

        EntityType<?> type = mob.getType();

        return switch (dimension) {
            case "minecraft:overworld" -> {
                // Only flag mobs whose home is NOT the Overworld
                if (NETHER_NATIVES.contains(type)) yield AnomalyType.DIMENSION_NETHER;
                if (END_NATIVES.contains(type))    yield AnomalyType.DIMENSION_END;
                yield null;
            }
            case "minecraft:the_nether" -> {
                // Only flag mobs whose home is NOT the Nether
                if (OVERWORLD_NATIVES.contains(type)) yield AnomalyType.DIMENSION_OVERWORLD;
                if (END_NATIVES.contains(type))       yield AnomalyType.DIMENSION_END;
                yield null;
            }
            case "minecraft:the_end" -> {
                // Only flag mobs whose home is NOT the End
                if (OVERWORLD_NATIVES.contains(type)) yield AnomalyType.DIMENSION_OVERWORLD;
                if (NETHER_NATIVES.contains(type))    yield AnomalyType.DIMENSION_NETHER;
                yield null;
            }
            default -> null;
        };
    }

    private boolean hasUnnaturalItems(MobEntity mob) {
        for (ItemStack stack : mob.getArmorItems()) {
            if (isUnnatural(stack)) return true;
        }
        // Piglins (including babies) naturally spawn holding crossbows or nothing.
        // Zombified Piglins always hold a golden sword — skip both.
        boolean skipMainHand = (mob.getType() == EntityType.PIGLIN
                                    && mob.getMainHandStack().isOf(Items.CROSSBOW))
                            || (mob.getType() == EntityType.ZOMBIFIED_PIGLIN
                                    && mob.getMainHandStack().isOf(Items.GOLDEN_SWORD));
        if (!skipMainHand && isUnnatural(mob.getMainHandStack())) return true;
        if (isUnnatural(mob.getOffHandStack())) return true;
        return false;
    }

    private boolean isUnnatural(ItemStack stack) {
        if (stack.isEmpty()) return false;

        Item item = stack.getItem();

        if (item == Items.ELYTRA) return true;
        if (item instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) return true;
        if (detectPumpkins.get() && (item == Items.CARVED_PUMPKIN || item == Items.JACK_O_LANTERN)) return true;

        Identifier itemId = Registries.ITEM.getId(item);
        if (itemId.getNamespace().equals("minecraft") && itemId.getPath().startsWith("netherite_")) return true;

        ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants == null || enchants.isEmpty()) return false;

        for (RegistryEntry<Enchantment> enchantmentEntry : enchants.getEnchantments()) {
            Enchantment enchantment = enchantmentEntry.value();
            if (enchantment == null) continue;
            if (enchantmentEntry.matchesKey(Enchantments.MENDING)) return true;
            if (enchants.getLevel(enchantmentEntry) > enchantment.getMaxLevel()) return true;
        }

        return false;
    }

    private boolean hasChestAttachment(MobEntity mob) {
        if (mob instanceof AbstractDonkeyEntity donkey) return donkey.hasChest();
        if (mob instanceof LlamaEntity llama)           return llama.hasChest();
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Color Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private SettingColor getColorForType(AnomalyType type) {
        return switch (type) {
            case ITEM, CHESTED       -> itemAnomalyLineColor.get();
            case DIMENSION_END       -> endLineColor.get();
            case DIMENSION_NETHER    -> netherLineColor.get();
            case DIMENSION_OVERWORLD -> overworldLineColor.get();
        };
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    public Map<Integer, AnomalyType> getAnomalies() {
        return highlightedEntities;
    }
}