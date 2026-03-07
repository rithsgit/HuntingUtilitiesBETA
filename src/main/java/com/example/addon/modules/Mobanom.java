package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
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
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;

import java.util.HashSet;
import java.util.Set;

public class Mobanom extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");
    private final SettingGroup sgItemAnomaly = settings.createGroup("Item Anomaly");

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    // Overworld Colors
    private final Setting<SettingColor> overworldSideColor = sgColors.add(new ColorSetting.Builder()
        .name("overworld-side-color")
        .description("Side color for Overworld mobs in other dimensions.")
        .defaultValue(new SettingColor(0, 255, 0, 75))
        .build()
    );

    private final Setting<SettingColor> overworldLineColor = sgColors.add(new ColorSetting.Builder()
        .name("overworld-line-color")
        .description("Line color for Overworld mobs in other dimensions.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .build()
    );

    // Nether Colors
    private final Setting<SettingColor> netherSideColor = sgColors.add(new ColorSetting.Builder()
        .name("nether-side-color")
        .description("Side color for Nether mobs in other dimensions.")
        .defaultValue(new SettingColor(255, 0, 0, 75))
        .build()
    );

    private final Setting<SettingColor> netherLineColor = sgColors.add(new ColorSetting.Builder()
        .name("nether-line-color")
        .description("Line color for Nether mobs in other dimensions.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    // End Colors
    private final Setting<SettingColor> endSideColor = sgColors.add(new ColorSetting.Builder()
        .name("end-side-color")
        .description("Side color for End mobs in other dimensions.")
        .defaultValue(new SettingColor(255, 0, 255, 75))
        .build()
    );

    private final Setting<SettingColor> endLineColor = sgColors.add(new ColorSetting.Builder()
        .name("end-line-color")
        .description("Line color for End mobs in other dimensions.")
        .defaultValue(new SettingColor(255, 0, 255, 255))
        .build()
    );

    // Item Anomaly Settings
    private final Setting<Boolean> detectUnnaturalItems = sgItemAnomaly.add(new BoolSetting.Builder()
        .name("detect-unnatural-items")
        .description("Detects mobs holding or wearing player-like items (elytra, shulkers, high-level enchanted gear).")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> itemAnomalySideColor = sgItemAnomaly.add(new ColorSetting.Builder()
        .name("item-anomaly-side-color")
        .description("The side color for mobs with unnatural items.")
        .defaultValue(new SettingColor(0, 255, 255, 75))
        .visible(detectUnnaturalItems::get)
        .build()
    );

    private final Setting<SettingColor> itemAnomalyLineColor = sgItemAnomaly.add(new ColorSetting.Builder()
        .name("item-anomaly-line-color")
        .description("The line color for mobs with unnatural items.")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .visible(detectUnnaturalItems::get)
        .build()
    );

    private final Setting<Boolean> detectChestedAnimals = sgItemAnomaly.add(new BoolSetting.Builder()
        .name("detect-chested-animals")
        .description("Detects animals (donkeys, llamas, etc.) carrying a chest.")
        .defaultValue(true)
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
        .defaultValue(128)
        .min(1)
        .sliderMax(256)
        .build()
    );

    private static final Set<EntityType<?>> NETHER_NATIVES = Set.of(
        EntityType.GHAST, EntityType.ZOMBIFIED_PIGLIN, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE,
        EntityType.HOGLIN, EntityType.ZOGLIN, EntityType.MAGMA_CUBE, EntityType.BLAZE,
        EntityType.WITHER_SKELETON, EntityType.SKELETON, EntityType.ENDERMAN, EntityType.STRIDER
    );

    private static final Set<EntityType<?>> END_NATIVES = Set.of(
        EntityType.ENDERMAN, EntityType.SHULKER, EntityType.ENDER_DRAGON
    );

    private static final Set<EntityType<?>> OVERWORLD_ANOMALIES = Set.of(
        EntityType.GHAST, EntityType.BLAZE, EntityType.WITHER_SKELETON, EntityType.MAGMA_CUBE,
        EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.HOGLIN, EntityType.ZOGLIN, EntityType.STRIDER,
        EntityType.SHULKER, EntityType.ENDER_DRAGON, EntityType.ZOMBIFIED_PIGLIN, EntityType.WITHER
    );

    private final Set<Integer> notifiedEntities = new HashSet<>();
    private final Set<Integer> highlightedEntities = new HashSet<>();

    public Mobanom() {
        super(HuntingUtilities.CATEGORY, "mobanom", "Detects and highlights mobs in the wrong dimension or with unnatural items.");
    }

    @Override
    public void onActivate() {
        notifiedEntities.clear();
        highlightedEntities.clear();
    }

    @Override
    public void onDeactivate() {
        if (mc.world != null) {
            for (int id : highlightedEntities) {
                Entity entity = mc.world.getEntityById(id);
                if (entity != null) {
                    entity.setGlowing(false);
                    clearEntityTeam(entity);
                }
            }
        }
        highlightedEntities.clear();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        notifiedEntities.removeIf(id -> mc.world.getEntityById(id) == null);

        String dim = mc.world.getRegistryKey().getValue().toString();
        Set<Integer> currentAnomalies = new HashSet<>();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof MobEntity mob)) continue;
            if (ignoredEntities.get().contains(mob.getType())) continue;
            if (mc.player.distanceTo(mob) > range.get()) continue;

            boolean isDimensionAnomaly = isAnomaly(mob.getType(), dim);
            boolean isItemAnomaly = detectUnnaturalItems.get() && hasUnnaturalItems(mob);
            boolean isChestedAnomaly = detectChestedAnimals.get() && hasChestAttachment(mob);

            if (isDimensionAnomaly || isItemAnomaly || isChestedAnomaly) {
                currentAnomalies.add(mob.getId());
                highlightedEntities.add(mob.getId());

                SettingColor sideColor = getSideColorForAnomaly(mob, isItemAnomaly || isChestedAnomaly);
                SettingColor lineColor = getColorForAnomaly(mob, isItemAnomaly || isChestedAnomaly);

                // Draw the bounding box around the mob
                event.renderer.box(mob.getBoundingBox(), sideColor, lineColor, shapeMode.get(), 0);

                // Glowing outline (best-effort client-side)
                mob.setGlowing(true);
                setEntityTeam(mob, getNearestColor(lineColor));

                if (chatNotification.get() && notifiedEntities.add(mob.getId())) {
                    if (isChestedAnomaly) {
                        info("Chested animal detected: " + mob.getType().getName().getString());
                    } else if (isItemAnomaly) {
                        info("Item anomaly detected: " + mob.getType().getName().getString());
                    } else {
                        info("Dimension anomaly detected: " + mob.getType().getName().getString());
                    }
                }
            }
        }

        highlightedEntities.removeIf(id -> {
            if (!currentAnomalies.contains(id)) {
                Entity entity = mc.world.getEntityById(id);
                if (entity != null) {
                    entity.setGlowing(false);
                    clearEntityTeam(entity);
                }
                return true;
            }
            return false;
        });
    }

    private boolean isAnomaly(EntityType<?> type, String dimension) {
        switch (dimension) {
            case "minecraft:the_nether":
                return !NETHER_NATIVES.contains(type);
            case "minecraft:the_end":
                return !END_NATIVES.contains(type);
            case "minecraft:overworld":
                return OVERWORLD_ANOMALIES.contains(type);
            default:
                return false;
        }
    }

    private boolean hasUnnaturalItems(MobEntity mob) {
        for (ItemStack stack : mob.getArmorItems()) {
            if (isUnnatural(stack)) return true;
        }
        if (isUnnatural(mob.getMainHandStack())) return true;
        if (isUnnatural(mob.getOffHandStack())) return true;

        return false;
    }

    private boolean isUnnatural(ItemStack stack) {
        if (stack.isEmpty()) return false;

        Item item = stack.getItem();

        // Check for specific items like Elytra or Shulker Boxes
        if (item == Items.ELYTRA) return true;
        if (item instanceof BlockItem && ((BlockItem) item).getBlock() instanceof ShulkerBoxBlock) return true;

        // Check for enchantments
        ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants == null || enchants.isEmpty()) return false;

        // Netherite gear with any enchantment is unnatural for mobs
        if (item.toString().startsWith("netherite_")) return true;

        // Check for specific "player-only" enchantments or levels
        for (RegistryEntry<Enchantment> enchantmentEntry : enchants.getEnchantments()) {
            Enchantment enchantment = enchantmentEntry.value();
            if (enchantment == null) continue;
            int level = enchants.getLevel(enchantmentEntry);

            // Mending is a strong indicator of player gear
            if (enchantmentEntry.matchesKey(Enchantments.MENDING)) return true;

            // Enchantment level is higher than vanilla max
            if (level > enchantment.getMaxLevel()) return true;
        }

        return false;
    }

    private boolean hasChestAttachment(MobEntity mob) {
        if (mob instanceof AbstractDonkeyEntity donkey) {
            return donkey.hasChest();
        }
        if (mob instanceof LlamaEntity llama) {
            return llama.hasChest();
        }
        return false;
    }

    private SettingColor getSideColorForAnomaly(MobEntity mob, boolean isItemAnomaly) {
        if (isItemAnomaly) return itemAnomalySideColor.get();
        if (END_NATIVES.contains(mob.getType())) return endSideColor.get();
        if (NETHER_NATIVES.contains(mob.getType())) return netherSideColor.get();
        return overworldSideColor.get();
    }

    private SettingColor getColorForAnomaly(MobEntity mob, boolean isItemAnomaly) {
        if (isItemAnomaly) return itemAnomalyLineColor.get();
        if (END_NATIVES.contains(mob.getType())) return endLineColor.get();
        if (NETHER_NATIVES.contains(mob.getType())) return netherLineColor.get();
        return overworldLineColor.get();
    }

    private Formatting getNearestColor(SettingColor color) {
        Formatting best = Formatting.WHITE;
        double minDistance = Double.MAX_VALUE;
        for (Formatting f : Formatting.values()) {
            if (!f.isColor()) continue;
            Integer rgb = f.getColorValue();
            if (rgb == null) continue;
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            double dist = Math.pow(r - color.r, 2) + Math.pow(g - color.g, 2) + Math.pow(b - color.b, 2);
            if (dist < minDistance) {
                minDistance = dist;
                best = f;
            }
        }
        return best;
    }

    private void setEntityTeam(Entity entity, Formatting color) {
        Scoreboard scoreboard = mc.world.getScoreboard();
        String teamName = "mobanom_" + color.getName();
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.addTeam(teamName);
            team.setColor(color);
        }
        if (entity.getScoreboardTeam() == null || !entity.getScoreboardTeam().getName().equals(teamName)) {
            scoreboard.addScoreHolderToTeam(entity.getNameForScoreboard(), team);
        }
    }

    private void clearEntityTeam(Entity entity) {
        Scoreboard scoreboard = mc.world.getScoreboard();
        Team currentTeam = entity.getScoreboardTeam();
        if (currentTeam != null && currentTeam.getName().startsWith("mobanom_")) {
            scoreboard.removeScoreHolderFromTeam(entity.getNameForScoreboard(), currentTeam);
        }
    }
}
