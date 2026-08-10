package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class Graveyard extends Module {
    private final SettingGroup sgGeneral     = settings.getDefaultGroup();
    private final SettingGroup sgEnchantment = settings.createGroup("Enchantment Filter");

    // ── General ───────────────────────────────────────────────────────────────

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Detection range in blocks.")
        .defaultValue(32)
        .min(16)
        .max(256)
        .sliderRange(16, 256)
        .build()
    );

    private final Setting<Boolean> showBeam = sgGeneral.add(new BoolSetting.Builder()
        .name("show-beam")
        .description("Show beam above found items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> beamColor = sgGeneral.add(new ColorSetting.Builder()
        .name("beam-color")
        .description("Color of the beam.")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .visible(showBeam::get)
        .build()
    );

    private final Setting<Double> beamWidth = sgGeneral.add(new DoubleSetting.Builder()
        .name("beam-width")
        .description("Beam thickness (blocks).")
        .defaultValue(0.15)
        .min(0.05)
        .max(0.5)
        .sliderRange(0.05, 0.5)
        .visible(showBeam::get)
        .build()
    );

    private final Setting<Boolean> onlyNearest = sgGeneral.add(new BoolSetting.Builder()
        .name("only-nearest")
        .description("Only highlight and notify about the closest item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> notification = sgGeneral.add(new BoolSetting.Builder()
        .name("notification")
        .description("Send chat messages and play sound when new whitelisted items or XP orbs are found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sortByDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-by-distance")
        .description("If enabled, prioritizes closest items.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Item>> whitelistedItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("whitelisted-items")
        .description("Items to look for on the ground, like diamond swords and valuable gear.")
        .defaultValue(List.of(Items.ELYTRA, Items.TOTEM_OF_UNDYING, Items.BOW,
            Items.FLINT_AND_STEEL,
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.FIREWORK_ROCKET, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE,
            Items.DIAMOND_SHOVEL,
            Items.DIAMOND_SWORD,
            Items.DIAMOND_HOE,
            Items.SHULKER_BOX,
            Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX, Items.LIGHT_BLUE_SHULKER_BOX,
            Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX, Items.GRAY_SHULKER_BOX,
            Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX, Items.BLUE_SHULKER_BOX,
            Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BLACK_SHULKER_BOX,
            Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_SWORD, Items.NETHERITE_HOE,
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS
        ))
        .build()
    );

    // ── XP Orbs ───────────────────────────────────────────────────────────────

    private final Setting<Boolean> detectXpOrbs = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-xp-orbs")
        .description("Detects Experience Orbs on the ground and creates a beam and notifier.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> xpBeamColor = sgGeneral.add(new ColorSetting.Builder()
        .name("xp-beam-color")
        .description("Color of the beam for Experience Orbs.")
        .defaultValue(new SettingColor(255, 255, 0, 200))
        .visible(() -> detectXpOrbs.get() && showBeam.get())
        .build()
    );

    // ── Enchantment Filter ────────────────────────────────────────────────────

    private final Setting<Boolean> enchantedOnly = sgEnchantment.add(new BoolSetting.Builder()
        .name("enchanted-only")
        .description("Only highlight whitelisted items if they are enchanted. Items that cannot be enchanted (shulker boxes, totems, etc.) are always shown regardless.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> enchantedBeamColor = sgEnchantment.add(new ColorSetting.Builder()
        .name("enchanted-beam-color")
        .description("Beam color override for enchanted items when enchanted-only is off, so both plain and enchanted items can be told apart visually.")
        .defaultValue(new SettingColor(180, 80, 255, 220))
        .visible(() -> !enchantedOnly.get() && showBeam.get())
        .build()
    );

    private final Setting<Boolean> separateEnchantedColor = sgEnchantment.add(new BoolSetting.Builder()
        .name("separate-enchanted-color")
        .description("Use the enchanted beam color above to visually distinguish enchanted items from plain ones.")
        .defaultValue(false)
        .visible(() -> !enchantedOnly.get() && showBeam.get())
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<ItemEntity> itemsToRender            = new ArrayList<>();
    private final List<ExperienceOrbEntity> xpOrbsToRender  = new ArrayList<>();
    private final Set<Integer>     notifiedItemEntities     = new HashSet<>();
    private long lastXpNotifyTime = 0;
    private static final long XP_NOTIFY_COOLDOWN_MS = 3000;

    public Graveyard() {
        super(Tim.CATEGORY, "graveyard", "Highlights valuable items and XP on the ground.");
    }

    @Override
    public void onActivate() {
        notifiedItemEntities.clear();
        itemsToRender.clear();
        xpOrbsToRender.clear();
        lastXpNotifyTime = 0;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        notifiedItemEntities.removeIf(id -> mc.world.getEntityById(id) == null);
        itemsToRender.clear();
        xpOrbsToRender.clear();

        Box searchArea = new Box(mc.player.getBlockPos()).expand(range.get());

        List<ItemEntity> matching = mc.world.getEntitiesByClass(
            ItemEntity.class,
            searchArea,
            e -> {
                ItemStack stack = e.getStack();
                if (!whitelistedItems.get().contains(stack.getItem())) return false;
                if (enchantedOnly.get() && canBeEnchanted(stack) && !isEnchanted(stack)) return false;
                return true;
            }
        );

        if (sortByDistance.get()) {
            matching.sort(Comparator.comparingDouble(e -> mc.player.squaredDistanceTo(e)));
        }

        if (!matching.isEmpty()) {
            if (onlyNearest.get()) {
                ItemEntity closest = matching.get(0);
                itemsToRender.add(closest);
                notifyIfNew(closest);
            } else {
                itemsToRender.addAll(matching);
                for (ItemEntity item : matching) notifyIfNew(item);
            }
        }

        if (detectXpOrbs.get()) {
            List<ExperienceOrbEntity> xpOrbs = mc.world.getEntitiesByClass(
                ExperienceOrbEntity.class,
                searchArea,
                e -> true
            );

            if (!xpOrbs.isEmpty()) {
                xpOrbsToRender.addAll(xpOrbs);
                if (notification.get()) {
                    long now = System.currentTimeMillis();
                    if (now - lastXpNotifyTime > XP_NOTIFY_COOLDOWN_MS) {
                        lastXpNotifyTime = now;
                        info("Found XP orbs nearby!");
                        mc.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
                    }
                }
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!showBeam.get() || (itemsToRender.isEmpty() && xpOrbsToRender.isEmpty())) return;

        double  halfWidth  = beamWidth.get() / 2.0;
        double  topOfWorld = mc.world.getHeight();
        boolean useSplit   = separateEnchantedColor.get() && !enchantedOnly.get();

        for (ItemEntity item : itemsToRender) {
            SettingColor c = (useSplit && isEnchanted(item.getStack()))
                ? enchantedBeamColor.get()
                : beamColor.get();

            Vec3d pos  = item.getPos();
            Box   beam = new Box(
                pos.x - halfWidth, pos.y, pos.z - halfWidth,
                pos.x + halfWidth, topOfWorld, pos.z + halfWidth
            );
            event.renderer.box(beam, c, c, ShapeMode.Both, 0);
        }

        for (ExperienceOrbEntity orb : xpOrbsToRender) {
            SettingColor c = xpBeamColor.get();
            Vec3d pos  = orb.getPos();
            Box   beam = new Box(
                pos.x - halfWidth, pos.y, pos.z - halfWidth,
                pos.x + halfWidth, topOfWorld, pos.z + halfWidth
            );
            event.renderer.box(beam, c, c, ShapeMode.Both, 0);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isEnchanted(ItemStack stack) {
        var enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants != null && !enchants.isEmpty()) return true;
        var stored = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
        return stored != null && !stored.isEmpty();
    }

    private boolean canBeEnchanted(ItemStack stack) {
        return stack.isEnchantable();
    }

    private void notifyIfNew(ItemEntity item) {
        int id = item.getId();
        if (!notifiedItemEntities.add(id)) return;

        if (notification.get()) {
            String name = item.getStack().getName().getString();
            info("Found: %s", name);
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.0f);
        }
    }
}