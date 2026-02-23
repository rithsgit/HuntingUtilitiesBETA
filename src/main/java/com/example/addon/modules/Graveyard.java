package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.HuntingUtilities;

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
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class Graveyard extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

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
        .description("Send chat messages and play sound when new whitelisted items are found.")
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
            Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX,
            Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_SWORD, Items.NETHERITE_HOE,
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS
        ))
        .build()
    );

    // Cache for items to render
    private final List<ItemEntity> itemsToRender = new ArrayList<>();
    private final Set<Integer> notifiedItemEntities = new HashSet<>();

    public Graveyard() {
        super(HuntingUtilities.CATEGORY, "graveyard", "Highlights valuable items on the ground.");
    }

    @Override
    public void onActivate() {
        notifiedItemEntities.clear();
        itemsToRender.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        // Remove invalid IDs from the notification cache & rendering list
        notifiedItemEntities.removeIf(id -> mc.world.getEntityById(id) == null);

        // Clear previous frame data
        itemsToRender.clear();

        // Define search area
        Box searchArea = new Box(mc.player.getBlockPos()).expand(range.get());

        // Get matching items
        List<ItemEntity> matching = mc.world.getEntitiesByClass(
            ItemEntity.class,
            searchArea,
            e -> whitelistedItems.get().contains(e.getStack().getItem())
        );

        if (matching.isEmpty()) return;

        // Sort if enabled
        if (sortByDistance.get()) {
            matching.sort(Comparator.comparingDouble(e -> mc.player.squaredDistanceTo(e)));
        }




        if (onlyNearest.get() ) {
            ItemEntity closest = matching.get(0);
            itemsToRender.add(closest);
            notifyIfNew(closest);
        } else {
            itemsToRender.addAll(matching);
            for (ItemEntity item : matching) {
                notifyIfNew(item);
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!showBeam.get() || itemsToRender.isEmpty()) return;

        double halfWidth = beamWidth.get() / 2.0;
        SettingColor c = beamColor.get();

        // Calculate the top of the world (Build limit)
        double topOfWorld = mc.world.getHeight();

        for (ItemEntity item : itemsToRender) {
            Vec3d pos = item.getPos();

            // Draw beam from item pos to top of world
            Box beam = new Box(
                pos.x - halfWidth, pos.y, pos.z - halfWidth,
                pos.x + halfWidth, topOfWorld, pos.z + halfWidth
            );

            event.renderer.box(beam, c, c, ShapeMode.Both, 0);
        }
    }

    private void notifyIfNew(ItemEntity item) {
        int id = item.getId();
        if (notifiedItemEntities.contains(id)) return;

        notifiedItemEntities.add(id);

        if (notification.get()) {
            String name = item.getStack().getName().getString();
            info("Found: %s", name);
            mc.player.playSound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.0f);
        }
    }
}