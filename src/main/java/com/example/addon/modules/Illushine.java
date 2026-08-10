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
import meteordevelopment.meteorclient.utils.render.WireframeEntityRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;

public class Illushine extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    private enum MobCategory { PASSIVE, NEUTRAL, HOSTILE }

    /**
     * Wireframe — custom geometry outline drawn by WireframeEntityRenderer.
     * Spectral  — vanilla glowing outline (spectral arrow effect) driven by
     *             GlowingRegistry → existing EntityGlowingMixin pipeline.
     */
    public enum HighlightMode {
        Wireframe("Wireframe"),
        Spectral("Spectral");

        private final String title;
        HighlightMode(String title) { this.title = title; }

        @Override public String toString() { return title; }
    }

    public enum CrosshairMode {
        None("None"),
        WhiteDot("White Dot"),
        Normal("Normal");

        private final String title;
        CrosshairMode(String title) { this.title = title; }

        @Override public String toString() { return title; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Category override table
    // ═══════════════════════════════════════════════════════════════════════════

    private static final Map<EntityType<?>, MobCategory> CATEGORY_OVERRIDES = new HashMap<>(Map.ofEntries(
        // ── Passive ──────────────────────────────────────────────────────────
        Map.entry(EntityType.FOX,              MobCategory.PASSIVE),

        // ── Neutral ──────────────────────────────────────────────────────────
        Map.entry(EntityType.PIGLIN,           MobCategory.HOSTILE),
        Map.entry(EntityType.ZOMBIFIED_PIGLIN, MobCategory.NEUTRAL),
        Map.entry(EntityType.ENDERMAN,         MobCategory.NEUTRAL),
        Map.entry(EntityType.GOAT,             MobCategory.NEUTRAL),

        // ── Hostile ──────────────────────────────────────────────────────────
        Map.entry(EntityType.PIGLIN_BRUTE,     MobCategory.HOSTILE),
        Map.entry(EntityType.GHAST,            MobCategory.HOSTILE),
        Map.entry(EntityType.SHULKER,          MobCategory.HOSTILE),
        Map.entry(EntityType.PHANTOM,          MobCategory.HOSTILE),
        Map.entry(EntityType.SLIME,            MobCategory.HOSTILE),
        Map.entry(EntityType.MAGMA_CUBE,       MobCategory.HOSTILE),
        Map.entry(EntityType.HOGLIN,           MobCategory.HOSTILE)
    ));

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgCrosshair = settings.createGroup("Crosshair");
    private final SettingGroup sgPassive   = settings.createGroup("Passive");
    private final SettingGroup sgNeutral   = settings.createGroup("Neutral");
    private final SettingGroup sgHostile   = settings.createGroup("Hostile");
    private final SettingGroup sgPlayer    = settings.createGroup("Player Scale");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<HighlightMode> highlightMode = sgGeneral.add(new EnumSetting.Builder<HighlightMode>()
        .name("highlight-mode")
        .description("How mobs are outlined. Wireframe draws custom geometry; Spectral uses the vanilla glow pipeline.")
        .defaultValue(HighlightMode.Wireframe)
        .build()
    );

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Range to highlight mobs.")
        .defaultValue(64).min(1).sliderMax(256)
        .build()
    );

    private final Setting<Set<EntityType<?>>> ignoredEntities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("ignored-entities")
        .description("Entities to ignore.")
        .defaultValue(new HashSet<>())
        .build()
    );

    private final Setting<Double> outlineScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("outline-scale")
        .description("Scale of the wireframe outline (Wireframe mode only).")
        .defaultValue(1.0).min(0.1).sliderMax(2.0)
        .visible(() -> highlightMode.get() == HighlightMode.Wireframe)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Crosshair
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<CrosshairMode> crosshairMode = sgCrosshair.add(new EnumSetting.Builder<CrosshairMode>()
        .name("crosshair-mode")
        .description("The crosshair style to display while Illushine is active.")
        .defaultValue(CrosshairMode.Normal)
        .build()
    );

    private final Setting<SettingColor> crosshairColor = sgCrosshair.add(new ColorSetting.Builder()
        .name("crosshair-color")
        .description("Color of the Normal crosshair lines.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> crosshairMode.get() == CrosshairMode.Normal)
        .build()
    );

    private final Setting<Integer> crosshairSize = sgCrosshair.add(new IntSetting.Builder()
        .name("crosshair-size").description("Half-length of each crosshair arm in pixels.")
        .defaultValue(5).min(1).sliderMax(20)
        .visible(() -> crosshairMode.get() == CrosshairMode.Normal)
        .build()
    );

    private final Setting<Integer> crosshairGap = sgCrosshair.add(new IntSetting.Builder()
        .name("crosshair-gap").description("Gap (in pixels) between center and each arm.")
        .defaultValue(2).min(0).sliderMax(10)
        .visible(() -> crosshairMode.get() == CrosshairMode.Normal)
        .build()
    );

    private final Setting<Integer> crosshairThickness = sgCrosshair.add(new IntSetting.Builder()
        .name("crosshair-thickness").description("Thickness of the crosshair lines in pixels.")
        .defaultValue(1).min(1).sliderMax(5)
        .visible(() -> crosshairMode.get() == CrosshairMode.Normal)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Passive / Neutral / Hostile
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> highlightPassive = sgPassive.add(new BoolSetting.Builder()
        .name("highlight-passive").description("Highlight passive mobs.").defaultValue(true).build());

    private final Setting<SettingColor> passiveColor = sgPassive.add(new ColorSetting.Builder()
        .name("passive-color").description("Outline color for passive mobs.")
        .defaultValue(new SettingColor(0, 255, 100, 255)).visible(highlightPassive::get).build());

    private final Setting<Double> passiveScale = sgPassive.add(new DoubleSetting.Builder()
        .name("passive-scale").description("Visual scale for passive mobs.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0).visible(highlightPassive::get).build());

    private final Setting<Boolean> highlightNeutral = sgNeutral.add(new BoolSetting.Builder()
        .name("highlight-neutral").description("Highlight neutral mobs.").defaultValue(true).build());

    private final Setting<SettingColor> neutralColor = sgNeutral.add(new ColorSetting.Builder()
        .name("neutral-color").description("Outline color for neutral mobs.")
        .defaultValue(new SettingColor(255, 200, 0, 255)).visible(highlightNeutral::get).build());

    private final Setting<Double> neutralScale = sgNeutral.add(new DoubleSetting.Builder()
        .name("neutral-scale").description("Visual scale for neutral mobs.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0).visible(highlightNeutral::get).build());

    private final Setting<Boolean> highlightHostile = sgHostile.add(new BoolSetting.Builder()
        .name("highlight-hostile").description("Highlight hostile mobs.").defaultValue(true).build());

    private final Setting<SettingColor> hostileColor = sgHostile.add(new ColorSetting.Builder()
        .name("hostile-color").description("Outline color for hostile mobs.")
        .defaultValue(new SettingColor(255, 50, 50, 255)).visible(highlightHostile::get).build());

    private final Setting<Double> hostileScale = sgHostile.add(new DoubleSetting.Builder()
        .name("hostile-scale").description("Visual scale for hostile mobs.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0).visible(highlightHostile::get).build());

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Player Scale
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> playerScale = sgPlayer.add(new DoubleSetting.Builder()
        .name("player-scale")
        .description("Visually scales your own player model and camera height.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0)
        .build()
    );

    private final Setting<Boolean> scaleOtherPlayers = sgPlayer.add(new BoolSetting.Builder()
        .name("scale-other-players")
        .description("Apply a visual scale to other players.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> otherPlayerScale = sgPlayer.add(new DoubleSetting.Builder()
        .name("other-player-scale")
        .description("Visual scale applied to other players.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0)
        .visible(scaleOtherPlayers::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private final Map<Integer, MobCategory> activelyOutlined = new HashMap<>();

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public Illushine() {
        super(Tim.CATEGORY, "illushine",
            "Highlights mobs with a wireframe or spectral outline by hostility type.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════════

    public CrosshairMode getCrosshairMode() { return crosshairMode.get(); }
    public double getPlayerScale() { return playerScale.get(); }
    public boolean getScaleOtherPlayers() { return scaleOtherPlayers.get(); }
    public double getOtherPlayerScale() { return otherPlayerScale.get(); }

    public double getMobScale(MobEntity mob) {
        MobCategory cat = categorise(mob);
        return switch (cat) {
            case PASSIVE -> passiveScale.get();
            case NEUTRAL -> neutralScale.get();
            case HOSTILE -> hostileScale.get();
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        activelyOutlined.clear();
    }

    @Override
    public void onDeactivate() {
        for (Integer id : activelyOutlined.keySet()) {
            GlowingRegistry.remove(id);
        }
        activelyOutlined.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        boolean spectral = highlightMode.get() == HighlightMode.Spectral;

        Map<Integer, MobCategory> newOutlined = new HashMap<>();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof MobEntity mob)) continue;
            if (ignoredEntities.get().contains(mob.getType())) continue;
            if (mc.player.distanceTo(mob) > range.get()) continue;

            MobCategory category = categorise(mob);
            boolean shouldHighlight = switch (category) {
                case PASSIVE -> highlightPassive.get();
                case NEUTRAL -> highlightNeutral.get();
                case HOSTILE -> highlightHostile.get();
            };
            if (!shouldHighlight) continue;

            newOutlined.put(mob.getId(), category);
        }

        if (spectral) {
            for (Integer id : activelyOutlined.keySet()) {
                if (!newOutlined.containsKey(id)) GlowingRegistry.remove(id);
            }
            for (Map.Entry<Integer, MobCategory> entry : newOutlined.entrySet()) {
                SettingColor c = colorForCategory(entry.getValue());
                GlowingRegistry.add(entry.getKey(), (255 << 24) | (c.r << 16) | (c.g << 8) | c.b);
            }
        } else {
            for (Integer id : activelyOutlined.keySet()) {
                GlowingRegistry.remove(id);
            }
        }

        activelyOutlined.clear();
        activelyOutlined.putAll(newOutlined);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Render
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null || activelyOutlined.isEmpty()) return;

        boolean wireframe = highlightMode.get() == HighlightMode.Wireframe;

        for (Map.Entry<Integer, MobCategory> entry : activelyOutlined.entrySet()) {
            Entity entity = mc.world.getEntityById(entry.getKey());
            if (!(entity instanceof MobEntity mob)) continue;

            SettingColor color = colorForCategory(entry.getValue());

            if (wireframe) {
                WireframeEntityRenderer.render(
                    event, mob, outlineScale.get(),
                    withAlpha(color, 25), color, ShapeMode.Both
                );
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Crosshair
    // ═══════════════════════════════════════════════════════════════════════════

    public void drawCrosshair(DrawContext context) {
        if (mc.getWindow() == null) return;
        if (!mc.options.getPerspective().isFirstPerson()) return;
        if (mc.currentScreen != null) return;

        int cx = mc.getWindow().getScaledWidth()  / 2;
        int cy = mc.getWindow().getScaledHeight() / 2;

        switch (crosshairMode.get()) {
            case WhiteDot -> context.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
            case Normal   -> drawNormalCrosshair(context, cx, cy);
            default       -> {}
        }
    }

    private void drawNormalCrosshair(DrawContext context, int cx, int cy) {
        int arm = crosshairSize.get();
        int gap = crosshairGap.get();
        int th  = crosshairThickness.get();
        int col = toARGB(crosshairColor.get());

        int halfU = th / 2;
        int halfD = th - halfU;

        context.fill(cx - arm - gap, cy - halfU, cx - gap,       cy + halfD, col);
        context.fill(cx + gap,       cy - halfU, cx + arm + gap, cy + halfD, col);
        context.fill(cx - halfU,     cy - arm - gap, cx + halfD, cy - gap,   col);
        context.fill(cx - halfU,     cy + gap,       cx + halfD, cy + arm + gap, col);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Categorisation
    // ═══════════════════════════════════════════════════════════════════════════

    private MobCategory categorise(MobEntity mob) {
        if (mob.getType() == EntityType.PIGLIN && mob.isBaby()) return MobCategory.PASSIVE;

        if (mob.getType() == EntityType.SPIDER || mob.getType() == EntityType.CAVE_SPIDER) {
            long time = mc.world.getTimeOfDay() % 24000;
            boolean isDay = time < 13000;
            boolean canSeeSky = mc.world.isSkyVisible(mob.getBlockPos());
            return (isDay && canSeeSky) ? MobCategory.NEUTRAL : MobCategory.HOSTILE;
        }

        MobCategory override = CATEGORY_OVERRIDES.get(mob.getType());
        if (override != null) return override;

        if (mob instanceof HostileEntity) return MobCategory.HOSTILE;
        if (mob instanceof Angerable)     return MobCategory.NEUTRAL;
        if (mob instanceof PassiveEntity) return MobCategory.PASSIVE;
        return MobCategory.NEUTRAL;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private SettingColor colorForCategory(MobCategory cat) {
        return switch (cat) {
            case PASSIVE -> passiveColor.get();
            case NEUTRAL -> neutralColor.get();
            case HOSTILE -> hostileColor.get();
        };
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    private static int toARGB(SettingColor c) {
        return (c.a << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}