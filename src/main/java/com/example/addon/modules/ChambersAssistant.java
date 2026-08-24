package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.Tim;
import com.example.addon.hud.ChambersAssistantHud;
import com.example.addon.utils.GlowingRegistry;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DecoratedPotBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.block.entity.VaultBlockEntity;
import net.minecraft.block.enums.TrialSpawnerState;
import net.minecraft.block.enums.VaultState;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.BreezeEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.screen.slot.Slot;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

public class ChambersAssistant extends Module {

    // ═══════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum TargetType {
        TRIAL_SPAWNER,
        ACTIVE_TRIAL_SPAWNER,
        EJECTING_TRIAL_SPAWNER,
        OMINOUS_SPAWNER,
        ACTIVE_OMINOUS_SPAWNER,
        EJECTING_OMINOUS_SPAWNER,
        VAULT,
        EJECTING_VAULT,
        OMINOUS_VAULT,
        LOOT_POT,
        CONTAINER
    }

    public enum RenderMode {
        GLOW,
        SPECTRAL,
        PULSE
    }

    public enum AlertSound {
        DRAGON_GROWL("Dragon Growl"),
        LEVEL_UP("Level Up"),
        RAVAGER_ROAR("Ravager Roar"),
        EXPERIENCE_ORB("Experience Orb"),
        BELL("Bell");

        private final String displayName;
        AlertSound(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;
    private static final int INTERACT_TIMEOUT_TICKS = 20;

    private final Map<BlockPos, TargetType> targets = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<BlockPos> checkedContainers = new HashSet<>();
    private final Set<BlockPos> notifiedPots = new HashSet<>();
    private final Set<BlockPos> notifiedActiveOminousSpawners = new HashSet<>();
    
    private final List<BreezeEntity> breezeTargets = new ArrayList<>();
    private final List<WindChargeEntity> windChargeTargets = new ArrayList<>();
    private final List<ItemFrameEntity> itemFrameTargets = new ArrayList<>();
    private final List<ItemEntity> trialItemTargets = new ArrayList<>();
    
    private final Set<Integer> notifiedBreezes = new HashSet<>();
    private final Set<Integer> notifiedDroppedRewards = new HashSet<>();
    private int omenWarnTimer = 0;

    private boolean wasAutoOpened = false;
    private BlockPos lastOpenedContainer = null;
    private int interactTimeoutTimer = 0;

    private int drinkTimer = 0;
    private int previousDrinkSlot = -1;
    private boolean hasAlertedForCurrentScreen = false;

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBlocks = settings.createGroup("Targets - Chambers");
    private final SettingGroup sgEntities = settings.createGroup("Targets - Entities");
    private final SettingGroup sgAutomation = settings.createGroup("Automation");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Detection range in chunks.")
        .defaultValue(16).min(1).max(128).sliderMin(1).sliderMax(64)
        .build()
    );

    private final Setting<Integer> chamberYLevel = sgGeneral.add(new IntSetting.Builder()
        .name("chamber-y-level")
        .description("Maximum Y level to scan. Trial Chambers can generate up to around Y = 40.")
        .defaultValue(40).min(-64).max(320).sliderMin(-64).sliderMax(100)
        .onChanged(v -> {
            scannedChunks.clear();
            targets.entrySet().removeIf(entry -> entry.getKey().getY() > v);
        })
        .build()
    );

    private final Setting<RenderMode> renderMode = sgGeneral.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("GLOW = layered bloom boxes. SPECTRAL = outline shader. PULSE = fading highlight.")
        .defaultValue(RenderMode.GLOW)
        .build()
    );

    private final Setting<Integer> beamWidth = sgGeneral.add(new IntSetting.Builder()
        .name("beam-width")
        .description("Width of the beams for entities and anomalies.")
        .defaultValue(15).min(5).max(50)
        .build()
    );

    private final Setting<Integer> glowLayers = sgGeneral.add(new IntSetting.Builder()
        .name("glow-layers").description("Number of bloom layers rendered around each target.")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> renderMode.get() == RenderMode.GLOW || renderMode.get() == RenderMode.PULSE)
        .build()
    );

    private final Setting<Double> glowSpread = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow-spread").description("How far each bloom layer expands outward (in blocks).")
        .defaultValue(0.04).min(0.01).sliderMax(0.15)
        .visible(() -> renderMode.get() == RenderMode.GLOW || renderMode.get() == RenderMode.PULSE)
        .build()
    );

    private final Setting<Integer> glowBaseAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("glow-base-alpha").description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(60).min(10).sliderMax(150)
        .visible(() -> renderMode.get() == RenderMode.GLOW)
        .build()
    );

    private final Setting<Integer> spectralBlockFillAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("spectral-block-fill-alpha")
        .description("Fill alpha for block targets in SPECTRAL mode (0 = invisible, 30 = subtle).")
        .defaultValue(30).min(0).max(120).sliderMax(80)
        .visible(() -> renderMode.get() == RenderMode.SPECTRAL)
        .build()
    );

    private final Setting<Double> pulseSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("pulse-speed")
        .description("Pulse cycle speed. 1.0 = one full fade in/out per second.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMax(3.0)
        .visible(() -> renderMode.get() == RenderMode.PULSE).build()
    );

    private final Setting<Integer> pulseMinAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("pulse-min-alpha")
        .description("Lowest alpha reached during the pulse (0 = invisible).")
        .defaultValue(15).min(0).max(255).sliderMax(100)
        .visible(() -> renderMode.get() == RenderMode.PULSE).build()
    );

    private final Setting<Integer> pulseMaxAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("pulse-max-alpha")
        .description("Peak alpha reached during the pulse.")
        .defaultValue(220).min(15).max(255).sliderMax(255)
        .visible(() -> renderMode.get() == RenderMode.PULSE).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Targets
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> trackSpawners = sgBlocks.add(new BoolSetting.Builder()
        .name("track-spawners").description("Highlight Trial Spawners (normal and ominous).").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> spawnerColor = sgBlocks.add(new ColorSetting.Builder()
        .name("spawner-color").description("Color for idle Trial Spawners.").defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(trackSpawners::get).build()
    );

    private final Setting<SettingColor> activeSpawnerColor = sgBlocks.add(new ColorSetting.Builder()
        .name("active-spawner-color").description("Color for Trial Spawners that are currently active.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(trackSpawners::get).build()
    );

    private final Setting<SettingColor> ejectingSpawnerColor = sgBlocks.add(new ColorSetting.Builder()
        .name("ejecting-spawner-color").description("Color for Trial Spawners that are ejecting rewards.")
        .defaultValue(new SettingColor(0, 255, 0, 255)) // Green
        .visible(trackSpawners::get).build()
    );

    private final Setting<SettingColor> ominousSpawnerColor = sgBlocks.add(new ColorSetting.Builder()
        .name("ominous-spawner-color").description("Color for idle Ominous Spawners.").defaultValue(new SettingColor(0, 180, 255, 255))
        .visible(trackSpawners::get).build()
    );

    private final Setting<SettingColor> activeOminousSpawnerColor = sgBlocks.add(new ColorSetting.Builder()
        .name("active-ominous-spawner-color").description("Color for Ominous Spawners that are currently active.")
        .defaultValue(new SettingColor(180, 0, 0, 255))
        .visible(trackSpawners::get).build()
    );

    private final Setting<Boolean> trackVaults = sgBlocks.add(new BoolSetting.Builder()
        .name("track-vaults").description("Highlight Vaults (normal and ominous).").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> vaultColor = sgBlocks.add(new ColorSetting.Builder()
        .name("vault-color").description("Color for active/unlooted vaults.").defaultValue(new SettingColor(255, 215, 0, 255))
        .visible(trackVaults::get).build()
    );

    private final Setting<SettingColor> ejectingVaultColor = sgBlocks.add(new ColorSetting.Builder()
        .name("ejecting-vault-color").description("Color for vaults that are currently ejecting loot.")
        .defaultValue(new SettingColor(0, 255, 0, 255)) // Green
        .visible(trackVaults::get).build()
    );

    private final Setting<SettingColor> ominousVaultColor = sgBlocks.add(new ColorSetting.Builder()
        .name("ominous-vault-color").description("Color for Ominous Vaults.").defaultValue(new SettingColor(180, 0, 255, 255))
        .visible(trackVaults::get).build()
    );

    private final Setting<Boolean> trackContainers = sgBlocks.add(new BoolSetting.Builder()
        .name("track-containers").description("Highlight standard chests, barrels, and dispensers.").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> containerColor = sgBlocks.add(new ColorSetting.Builder()
        .name("container-color").description("Color for standard chests, barrels, and dispensers.")
        .defaultValue(new SettingColor(0, 0, 255, 255)) // Blue
        .visible(trackContainers::get).build()
    );

    private final Setting<List<Item>> containerWhitelist = sgBlocks.add(new ItemListSetting.Builder()
        .name("container-whitelist")
        .description("Items to alert you about when opening Chests/Barrels/Dispensers.")
        .defaultValue(List.of(
            Items.NETHERITE_BLOCK, Items.NETHERITE_INGOT, Items.DIAMOND, 
            Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE,
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
            Items.ENDER_CHEST, Items.ENCHANTED_GOLDEN_APPLE, Items.ELYTRA, Items.MACE, Items.OMINOUS_BOTTLE,
            Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE,
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
            Items.SHULKER_BOX, Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX,
            Items.LIGHT_BLUE_SHULKER_BOX, Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX,
            Items.GRAY_SHULKER_BOX, Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX,
            Items.BLUE_SHULKER_BOX, Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX,
            Items.BLACK_SHULKER_BOX,
            Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
            Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE, Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE, Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE,
            Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE, Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE, Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE,
            Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE, Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE, Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE,
            Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE, Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE,
            Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE, Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE, Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE,
            Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE, Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE, Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE
        ))
        .build()
    );

    private final Setting<List<Item>> potWhitelist = sgBlocks.add(new ItemListSetting.Builder()
        .name("pot-whitelist")
        .description("Items to search for inside Decorated Pots.")
        .defaultValue(List.of(
            Items.DIAMOND, Items.EMERALD, Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE,
            Items.ENDER_PEARL, Items.TRIAL_KEY, Items.OMINOUS_TRIAL_KEY, Items.EXPERIENCE_BOTTLE, Items.OMINOUS_BOTTLE,
            Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE,
            Items.MUSIC_DISC_5, Items.MUSIC_DISC_RELIC,
            Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE, Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE,
            Items.ENDER_CHEST
        ))
        .build()
    );

    private final Setting<SettingColor> lootPotColor = sgBlocks.add(new ColorSetting.Builder()
        .name("loot-pot-color")
        .description("Color for pots containing whitelisted items.")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Entities
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> trackBreezes = sgEntities.add(new BoolSetting.Builder()
        .name("track-breezes").description("Highlights Breezes and Wind Charge projectiles.").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> breezeColor = sgEntities.add(new ColorSetting.Builder()
        .name("breeze-color").description("Color for Breezes and Wind Charges.").defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(trackBreezes::get).build()
    );

    private final Setting<Boolean> trackOminousItemFrames = sgEntities.add(new BoolSetting.Builder()
        .name("track-item-frames").description("Highlights invisible Ominous Item Frames holding items.").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> itemFrameColor = sgEntities.add(new ColorSetting.Builder()
        .name("item-frame-color").description("Color for invisible Item Frames.").defaultValue(new SettingColor(255, 0, 255, 255))
        .visible(trackOminousItemFrames::get).build()
    );

    private final Setting<Boolean> trackTrialItems = sgEntities.add(new BoolSetting.Builder()
        .name("track-keys-and-bottles").description("Highlights dropped Trial Keys and Ominous Bottles.").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> trialItemColor = sgEntities.add(new ColorSetting.Builder()
        .name("trial-item-color").description("Color for dropped Trial Keys and Ominous Bottles.")
        .defaultValue(new SettingColor(255, 255, 0, 255))
        .visible(trackTrialItems::get).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Automation & Safety
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> autoOpenVaults = sgAutomation.add(new BoolSetting.Builder()
        .name("auto-open-vaults")
        .description("Automatically opens Vaults when you have a Trial Key.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoDrinkOminous = sgAutomation.add(new BoolSetting.Builder()
        .name("auto-drink-ominous")
        .description("Automatically drinks an Ominous Bottle when near a Trial Spawner to trigger Ominous state.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> enableAlerts = sgAutomation.add(new BoolSetting.Builder()
        .name("alerts")
        .description("Master toggle for audio cues, reward announcements, and omen effect warnings.")
        .defaultValue(true)
        .build()
    );

    private final Setting<AlertSound> alertSound = sgAutomation.add(new EnumSetting.Builder<AlertSound>()
        .name("alert-sound")
        .description("Which sound to play for module alerts.")
        .defaultValue(AlertSound.DRAGON_GROWL)
        .visible(enableAlerts::get)
        .build()
    );

    private final Setting<Double> alertVolume = sgAutomation.add(new DoubleSetting.Builder()
        .name("alert-volume")
        .description("Volume of the alert sound. Goes up to 5.0 for extra loud alerts.")
        .defaultValue(1.0).min(0).max(5.0).sliderMax(5.0)
        .visible(enableAlerts::get)
        .build()
    );

    private final Setting<Boolean> alertOnLootPot = sgAutomation.add(new BoolSetting.Builder()
        .name("alert-on-loot-pot")
        .description("Plays a sound and warns you when a pot containing whitelisted loot is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disconnectOnPlayer = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-player")
        .description("Instantly disconnects from the server if another player enters render distance.")
        .defaultValue(false).build()
    );

    private final Setting<Boolean> autoDisableOnLowHealth = sgSafety.add(new BoolSetting.Builder()
        .name("auto-disable-on-low-health")
        .description("Disables the module if health is critical.")
        .defaultValue(true).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor & Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    public ChambersAssistant() {
        super(Tim.CATEGORY, "chambers-assistant", "Highlights Trial Chambers elements: spawners, vaults, pots, and breezes.");
    }

    @Override
    public void onActivate() {
        targets.clear();
        scannedChunks.clear();
        checkedContainers.clear();
        notifiedPots.clear();
        notifiedActiveOminousSpawners.clear();
        notifiedDroppedRewards.clear();
        breezeTargets.clear();
        windChargeTargets.clear();
        itemFrameTargets.clear();
        trialItemTargets.clear();
        notifiedBreezes.clear();
        omenWarnTimer = 0;
        drinkTimer = 0;
        previousDrinkSlot = -1;
        hasAlertedForCurrentScreen = false;
        GlowingRegistry.clear();
    }

    @Override
    public void onDeactivate() {
        if (drinkTimer > 0) {
            mc.options.useKey.setPressed(false);
            if (previousDrinkSlot != -1 && mc.player != null) {
                mc.player.getInventory().selectedSlot = previousDrinkSlot;
            }
        }
        GlowingRegistry.clear();
        targets.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Event Handlers
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (performSafetyChecks()) return;
        checkForPlayers();
        checkOmenEffects();
        updateContainerLogic();
        checkOpenedContainerLoot(); 
        updateOminousDrink();
        updateDynamicStates();
        updateScanningLogic();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        boolean isSpectral = renderMode.get() == RenderMode.SPECTRAL;
        boolean isPulse = renderMode.get() == RenderMode.PULSE;
        Set<BlockPos> toRemove = new HashSet<>();

        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            TargetType type = entry.getValue();

            if (!mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            if (mc.world.getBlockState(pos).isAir()) { toRemove.add(pos); continue; }

            Block currentBlock = mc.world.getBlockState(pos).getBlock();
            if (!validateBlockType(currentBlock, type)) { toRemove.add(pos); continue; }

            Box renderBox = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
            SettingColor color = getColor(type);
            if (color == null) continue;

            if (isSpectral) {
                event.renderer.box(renderBox, withAlpha(color, spectralBlockFillAlpha.get()), withAlpha(color, 0), ShapeMode.Sides, 0);
            } else if (isPulse) {
                renderPulseBox(event, renderBox, color);
            } else {
                renderGlowLayers(event, renderBox, color);
                event.renderer.box(renderBox, withAlpha(color, 0), color, ShapeMode.Lines, 0);
            }
        }

        for (BlockPos pos : toRemove) {
            targets.remove(pos);
            notifiedPots.remove(pos);
            notifiedActiveOminousSpawners.remove(pos);
        }

        // Breezes do not get beams, all other entities do
        renderEntity(event, isSpectral, isPulse, trackBreezes.get(), false, breezeTargets, breezeColor.get());
        renderEntity(event, isSpectral, isPulse, trackBreezes.get(), true, windChargeTargets, breezeColor.get());
        renderEntity(event, isSpectral, isPulse, trackOminousItemFrames.get(), true, itemFrameTargets, itemFrameColor.get());
        renderEntity(event, isSpectral, isPulse, trackTrialItems.get(), true, trialItemTargets, trialItemColor.get());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Dynamic State Updater
    // ═══════════════════════════════════════════════════════════════════════════

    private void updateDynamicStates() {
        if (mc.world == null || mc.player == null) return;

        for (BlockPos pos : new HashSet<>(targets.keySet())) {
            if (!mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

            BlockState state = mc.world.getBlockState(pos);
            Block block = state.getBlock();

            if (block == Blocks.TRIAL_SPAWNER) {
                boolean isOminous = state.get(Properties.OMINOUS);
                TrialSpawnerState spawnerState = state.get(Properties.TRIAL_SPAWNER_STATE);
                TargetType currentType = targets.get(pos);
                TargetType newType;

                if (spawnerState == TrialSpawnerState.EJECTING_REWARD) {
                    newType = isOminous ? TargetType.EJECTING_OMINOUS_SPAWNER : TargetType.EJECTING_TRIAL_SPAWNER;
                } else if (spawnerState == TrialSpawnerState.ACTIVE) {
                    newType = isOminous ? TargetType.ACTIVE_OMINOUS_SPAWNER : TargetType.ACTIVE_TRIAL_SPAWNER;
                } else {
                    newType = isOminous ? TargetType.OMINOUS_SPAWNER : TargetType.TRIAL_SPAWNER;
                }

                if (currentType != newType) {
                    targets.put(pos, newType);
                    
                    if (newType == TargetType.ACTIVE_OMINOUS_SPAWNER && enableAlerts.get() && notifiedActiveOminousSpawners.add(pos)) {
                        info("§cOminous Spawner Activated!");
                        playAlert();
                    } else if (newType == TargetType.EJECTING_TRIAL_SPAWNER || newType == TargetType.EJECTING_OMINOUS_SPAWNER) {
                        if (enableAlerts.get()) {
                            info("§eTrial Spawner is ejecting rewards!");
                            playAlert();
                        }
                    }
                }
            } else if (block == Blocks.VAULT) {
                boolean isOminous = state.get(Properties.OMINOUS);
                VaultState vState = state.get(Properties.VAULT_STATE);
                TargetType currentType = targets.get(pos);
                TargetType newType;

                if (vState == VaultState.EJECTING) {
                    newType = TargetType.EJECTING_VAULT;
                } else {
                    newType = isOminous ? TargetType.OMINOUS_VAULT : TargetType.VAULT;
                }

                if (currentType != newType) {
                    targets.put(pos, newType);
                    if (newType == TargetType.EJECTING_VAULT && enableAlerts.get()) {
                        info("§aVault is ejecting loot!");
                        playAlert();
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Scanning Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private void updateScanningLogic() {
        if (mc.world.getRegistryKey() == null) return;
        if (dimensionChangeCooldown > 0) { dimensionChangeCooldown--; return; }

        String currDim = mc.world.getRegistryKey().getValue().toString();
        if (!currDim.equals(lastDimension)) {
            dimensionChangeCooldown = DIMENSION_CHANGE_COOLDOWN_TICKS;
            lastDimension = currDim;
            targets.clear();
            scannedChunks.clear();
            GlowingRegistry.clear();
            return;
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        cleanupDistantTargets(playerPos);
        scanBreezes();
        scanWindCharges();
        scanOminousItemFrames();
        scanTrialItems();
        scanDroppedRewards(); 
        pruneBlockTargets();
        scanNewChunks(centerChunkX, centerChunkZ);
    }

    private void scanDroppedRewards() {
        if (!enableAlerts.get()) return;
        int blockRange = range.get() * 16;
        Box searchBox = new Box(mc.player.getBlockPos()).expand(blockRange);
        Set<Integer> currentIds = new HashSet<>();

        for (ItemEntity item : mc.world.getEntitiesByClass(ItemEntity.class, searchBox, e -> true)) {
            currentIds.add(item.getId());
            if (notifiedDroppedRewards.add(item.getId())) {
                for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
                    TargetType type = entry.getValue();
                    if (type == TargetType.EJECTING_TRIAL_SPAWNER || type == TargetType.EJECTING_OMINOUS_SPAWNER || type == TargetType.EJECTING_VAULT) {
                        if (entry.getKey().isWithinDistance(item.getPos(), 2.0)) {
                            info("§bReward Ejected: §e" + item.getStack().getName().getString() + "§b!");
                            playAlert();
                            break;
                        }
                    }
                }
            }
        }
        notifiedDroppedRewards.retainAll(currentIds);
    }

    private void scanBreezes() {
        breezeTargets.clear();
        if (!trackBreezes.get()) return;

        int blockRange = range.get() * 16;
        Box searchBox = new Box(mc.player.getBlockPos()).expand(blockRange);
        Set<Integer> currentIds = new HashSet<>();

        for (BreezeEntity breeze : mc.world.getEntitiesByClass(BreezeEntity.class, searchBox, e -> true)) {
            breezeTargets.add(breeze);
            currentIds.add(breeze.getId());

            if (renderMode.get() == RenderMode.SPECTRAL) {
                GlowingRegistry.add(breeze.getId(), toArgb(breezeColor.get()));
            } else {
                GlowingRegistry.remove(breeze.getId());
            }

            if (notifiedBreezes.add(breeze.getId())) {
                info("Breeze Detected!");
                playAlert();
            }
        }
        notifiedBreezes.retainAll(currentIds);
    }

    private void scanWindCharges() {
        windChargeTargets.clear();
        if (!trackBreezes.get()) return;

        int blockRange = range.get() * 16;
        Box searchBox = new Box(mc.player.getBlockPos()).expand(blockRange);

        for (WindChargeEntity charge : mc.world.getEntitiesByClass(WindChargeEntity.class, searchBox, e -> true)) {
            windChargeTargets.add(charge);
            if (renderMode.get() == RenderMode.SPECTRAL) {
                GlowingRegistry.add(charge.getId(), toArgb(breezeColor.get()));
            }
        }
    }

    private void scanOminousItemFrames() {
        itemFrameTargets.clear();
        if (!trackOminousItemFrames.get()) return;

        int blockRange = range.get() * 16;
        Box searchBox = new Box(mc.player.getBlockPos()).expand(blockRange);

        for (ItemFrameEntity frame : mc.world.getEntitiesByClass(ItemFrameEntity.class, searchBox, e -> true)) {
            if (frame.isInvisible() && !frame.getHeldItemStack().isEmpty()) {
                itemFrameTargets.add(frame);
                if (renderMode.get() == RenderMode.SPECTRAL) {
                    GlowingRegistry.add(frame.getId(), toArgb(itemFrameColor.get()));
                }
            }
        }
    }

    private void scanTrialItems() {
        trialItemTargets.clear();
        if (!trackTrialItems.get()) return;

        int blockRange = range.get() * 16;
        Box searchBox = new Box(mc.player.getBlockPos()).expand(blockRange);

        for (ItemEntity item : mc.world.getEntitiesByClass(ItemEntity.class, searchBox, e -> true)) {
            Item stackItem = item.getStack().getItem();
            if (stackItem == Items.TRIAL_KEY || stackItem == Items.OMINOUS_TRIAL_KEY || stackItem == Items.OMINOUS_BOTTLE) {
                trialItemTargets.add(item);
                if (renderMode.get() == RenderMode.SPECTRAL) {
                    GlowingRegistry.add(item.getId(), toArgb(trialItemColor.get()));
                }
            }
        }
    }

    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r = range.get();
        int rSq = r * r;

        scannedChunks.removeIf(cp -> {
            int dx = cp.x - centerChunkX;
            int dz = cp.z - centerChunkZ;
            return dx * dx + dz * dz > rSq;
        });

        int chunksScanned = 0;
        int limit = 10;

        outer:
        for (int d = 0; d <= r; d++) {
            int minX = -d, maxX = d, minZ = -d, maxZ = d;

            for (int x = minX; x <= maxX; x++) {
                if (processChunk(centerChunkX + x, centerChunkZ + minZ, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                if (chunksScanned >= limit) break outer;
                if (minZ != maxZ) {
                    if (processChunk(centerChunkX + x, centerChunkZ + maxZ, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                    if (chunksScanned >= limit) break outer;
                }
            }

            for (int z = minZ + 1; z < maxZ; z++) {
                if (processChunk(centerChunkX + minX, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                if (chunksScanned >= limit) break outer;
                if (minX != maxX) {
                    if (processChunk(centerChunkX + maxX, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                    if (chunksScanned >= limit) break outer;
                }
            }
        }
    }

    private boolean processChunk(int cx, int cz, int rSq, int centerChunkX, int centerChunkZ) {
        int dx = cx - centerChunkX, dz = cz - centerChunkZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp)) return false;
        if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) return false;

        WorldChunk chunk = mc.world.getChunk(cx, cz);
        scanBlockEntitiesInChunk(chunk);
        scannedChunks.add(cp);
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Block Entity Scanning
    // ═══════════════════════════════════════════════════════════════════════════

    private void scanBlockEntitiesInChunk(WorldChunk chunk) {
        int maxY = chamberYLevel.get(); 

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getPos();
            if (pos.getY() > maxY) continue;

            BlockState state = mc.world.getBlockState(pos);
            
            if (be instanceof TrialSpawnerBlockEntity) {
                boolean isOminous = state.get(Properties.OMINOUS); 
                TrialSpawnerState spawnerState = state.get(Properties.TRIAL_SPAWNER_STATE);

                if (spawnerState == TrialSpawnerState.EJECTING_REWARD) {
                    targets.put(pos, isOminous ? TargetType.EJECTING_OMINOUS_SPAWNER : TargetType.EJECTING_TRIAL_SPAWNER);
                } else if (spawnerState == TrialSpawnerState.ACTIVE) {
                    targets.put(pos, isOminous ? TargetType.ACTIVE_OMINOUS_SPAWNER : TargetType.ACTIVE_TRIAL_SPAWNER);
                } else {
                    targets.put(pos, isOminous ? TargetType.OMINOUS_SPAWNER : TargetType.TRIAL_SPAWNER);
                }
            } 
            else if (be instanceof VaultBlockEntity) {
                boolean isOminous = state.get(Properties.OMINOUS);
                VaultState vState = state.get(Properties.VAULT_STATE);
                
                if (vState == VaultState.EJECTING) {
                    targets.put(pos, TargetType.EJECTING_VAULT);
                } else {
                    targets.put(pos, isOminous ? TargetType.OMINOUS_VAULT : TargetType.VAULT);
                }
            }
            else if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof DispenserBlockEntity) {
                targets.put(pos, TargetType.CONTAINER);
            }
            else if (be instanceof DecoratedPotBlockEntity pot) {
                if (!potWhitelist.get().isEmpty()) {
                    ItemStack potItem = pot.getStack(); 
                    if (!potItem.isEmpty() && potWhitelist.get().contains(potItem.getItem())) {
                        targets.put(pos, TargetType.LOOT_POT);
                        
                        if (notifiedPots.add(pos)) {
                            if (alertOnLootPot.get()) {
                                info("§bLoot Pot detected containing: §e" + potItem.getName().getString() + "§b!");
                                playAlert();
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Automation & Safety Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private void updateContainerLogic() {
        if (interactTimeoutTimer > 0) interactTimeoutTimer--;

        if (mc.currentScreen == null && !wasAutoOpened && autoOpenVaults.get()) {
            List<BlockPos> nearbyVaults = targets.entrySet().stream()
                .filter(e -> e.getValue() == TargetType.VAULT || e.getValue() == TargetType.OMINOUS_VAULT)
                .map(Map.Entry::getKey)
                .filter(pos -> !checkedContainers.contains(pos))
                .filter(pos -> Math.sqrt(pos.getSquaredDistance(mc.player.getPos())) <= 4.5)
                .sorted(Comparator.comparingDouble(pos -> pos.getSquaredDistance(mc.player.getPos())))
                .toList();

            if (!nearbyVaults.isEmpty()) {
                BlockPos pos = nearbyVaults.get(0);
                checkedContainers.add(pos);
                wasAutoOpened = true;
                interactTimeoutTimer = INTERACT_TIMEOUT_TICKS;

                Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
            }
        } else if (mc.currentScreen == null && wasAutoOpened && interactTimeoutTimer == 0) {
            wasAutoOpened = false;
        }
    }

    private void checkOpenedContainerLoot() {
        if (mc.currentScreen instanceof HandledScreen<?> screen && !(mc.currentScreen instanceof InventoryScreen)) {
            // Ignore Ender Chest and Shulker Box contents to prevent false triggers
            if (mc.currentScreen instanceof ShulkerBoxScreen || screen.getTitle().getString().equals(Text.translatable("container.enderchest").getString())) {
                hasAlertedForCurrentScreen = true;
                return;
            }
            
            if (!hasAlertedForCurrentScreen) {
                for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
                    Slot slot = screen.getScreenHandler().slots.get(i);
                    // Ignore player's own inventory contents to prevent false triggers
                    if (slot.inventory instanceof PlayerInventory) continue;
                    
                    ItemStack stack = slot.getStack();
                    if (!stack.isEmpty() && containerWhitelist.get().contains(stack.getItem())) {
                        info("§cRare loot found in container: §e" + stack.getName().getString() + "§c!");
                        playAlert();
                        hasAlertedForCurrentScreen = true;
                        break;
                    }
                }
            }
        } else {
            hasAlertedForCurrentScreen = false;
        }
    }

    private void updateOminousDrink() {
        if (!autoDrinkOminous.get()) {
            if (drinkTimer > 0) {
                mc.options.useKey.setPressed(false);
                if (previousDrinkSlot != -1 && mc.player != null) {
                    mc.player.getInventory().selectedSlot = previousDrinkSlot;
                    previousDrinkSlot = -1;
                }
                drinkTimer = 0;
            }
            return;
        }

        boolean hasOmen = mc.player.hasStatusEffect(StatusEffects.BAD_OMEN) || mc.player.hasStatusEffect(StatusEffects.TRIAL_OMEN);
        
        boolean hasNearbySpawner = targets.entrySet().stream()
            .anyMatch(e -> e.getValue() == TargetType.TRIAL_SPAWNER && e.getKey().isWithinDistance(mc.player.getPos(), 8.0));

        if (drinkTimer == 0 && !hasOmen && hasNearbySpawner && mc.currentScreen == null) {
            int bottleSlot = findOminousBottle();
            if (bottleSlot != -1) {
                previousDrinkSlot = mc.player.getInventory().selectedSlot;
                mc.player.getInventory().selectedSlot = bottleSlot;
                mc.options.useKey.setPressed(true);
                drinkTimer = 40;
            }
        } else if (drinkTimer > 0) {
            drinkTimer--;
            if (hasOmen || drinkTimer == 0 || mc.player.getInventory().getStack(mc.player.getInventory().selectedSlot).getItem() != Items.OMINOUS_BOTTLE) {
                mc.options.useKey.setPressed(false);
                if (previousDrinkSlot != -1) {
                    mc.player.getInventory().selectedSlot = previousDrinkSlot;
                    previousDrinkSlot = -1;
                }
                drinkTimer = 0;
            }
        }
    }

    private int findOminousBottle() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.OMINOUS_BOTTLE)) return i;
        }
        return -1;
    }

    private void checkOmenEffects() {
        if (!enableAlerts.get()) return;

        if (omenWarnTimer > 0) {
            omenWarnTimer--;
            return;
        }

        boolean hasBadOmen = mc.player.hasStatusEffect(StatusEffects.BAD_OMEN);
        boolean hasTrialOmen = mc.player.hasStatusEffect(StatusEffects.TRIAL_OMEN);

        if (hasTrialOmen) {
            warning("You have the Trial Omen effect! Ominous Spawners are active.");
            playAlert();
            omenWarnTimer = 200;
        } else if (hasBadOmen) {
            info("You have Bad Omen. Approaching a Trial Spawner will trigger an Ominous state.");
            playAlert();
            omenWarnTimer = 200;
        }
    }

    private void checkForPlayers() {
        if (!disconnectOnPlayer.get()) return;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (player.distanceTo(mc.player) < 128) {
                info("§cPlayer detected in render distance! Disconnecting...");
                mc.getNetworkHandler().getConnection().disconnect(new DisconnectionInfo(Text.literal("Player detected in render distance")));
                return;
            }
        }
    }

    private void playAlert() {
        if (mc.player == null) return;
        SoundEvent sound = switch (alertSound.get()) {
            case LEVEL_UP -> SoundEvents.ENTITY_PLAYER_LEVELUP;
            case RAVAGER_ROAR -> SoundEvents.ENTITY_RAVAGER_ROAR;
            case EXPERIENCE_ORB -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
            case BELL -> SoundEvents.BLOCK_BELL_USE;
            default -> SoundEvents.ENTITY_ENDER_DRAGON_GROWL;
        };
        mc.player.playSound(sound, alertVolume.get().floatValue(), 1.0f);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Bloom & Pulse Rendering
    // ═══════════════════════════════════════════════════════════════════════════

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int layers = glowLayers.get();
        double spread = glowSpread.get();
        int baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            int layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double)(i - 1) / layers)));
            event.renderer.box(
                box.expand(expansion),
                withAlpha(color, layerAlpha),
                withAlpha(color, 0),
                ShapeMode.Sides, 0
            );
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

    private void renderEntity(Render3DEvent event, boolean isSpectral, boolean isPulse, boolean isEnabled, boolean renderBeam, List<? extends net.minecraft.entity.Entity> entities, SettingColor color) {
        if (!isEnabled || entities.isEmpty()) return;

        double beamSize = beamWidth.get() / 100.0;
        for (net.minecraft.entity.Entity entity : entities) {
            if (!entity.isAlive()) continue;
            Box box = entity.getBoundingBox();
            Vec3d pos = entity.getPos();
            Box beamBox = renderBeam ? new Box(
                pos.x - beamSize, pos.y, pos.z - beamSize,
                pos.x + beamSize, mc.world.getHeight(), pos.z + beamSize
            ) : null;

            if (isSpectral) {
                event.renderer.box(box, withAlpha(color, 0), withAlpha(color, 200), ShapeMode.Lines, 0);
                if (renderBeam) event.renderer.box(beamBox, withAlpha(color, 20), withAlpha(color, 180), ShapeMode.Both, 0);
            } else if (isPulse) {
                renderPulseBox(event, box, color);
                if (renderBeam) renderPulseBox(event, beamBox, color);
            } else {
                renderGlowLayers(event, box, color);
                event.renderer.box(box, withAlpha(color, 0), color, ShapeMode.Lines, 0);
                if (renderBeam) {
                    renderGlowLayers(event, beamBox, color);
                    event.renderer.box(beamBox, withAlpha(color, 60), color, ShapeMode.Both, 0);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utility Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean validateBlockType(Block block, TargetType type) {
        return switch (type) {
            case TRIAL_SPAWNER, ACTIVE_TRIAL_SPAWNER, EJECTING_TRIAL_SPAWNER, OMINOUS_SPAWNER, ACTIVE_OMINOUS_SPAWNER, EJECTING_OMINOUS_SPAWNER -> block == Blocks.TRIAL_SPAWNER;
            case VAULT, EJECTING_VAULT, OMINOUS_VAULT -> block == Blocks.VAULT;
            case LOOT_POT -> block == Blocks.DECORATED_POT;
            case CONTAINER -> block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL || block == Blocks.DISPENSER || block == Blocks.DROPPER;
        };
    }

    private SettingColor getColor(TargetType type) {
        return switch (type) {
            case TRIAL_SPAWNER -> trackSpawners.get() ? spawnerColor.get() : null;
            case ACTIVE_TRIAL_SPAWNER -> trackSpawners.get() ? activeSpawnerColor.get() : null;
            case EJECTING_TRIAL_SPAWNER -> trackSpawners.get() ? ejectingSpawnerColor.get() : null;
            case OMINOUS_SPAWNER -> trackSpawners.get() ? ominousSpawnerColor.get() : null;
            case ACTIVE_OMINOUS_SPAWNER -> trackSpawners.get() ? activeOminousSpawnerColor.get() : null;
            case EJECTING_OMINOUS_SPAWNER -> trackSpawners.get() ? ejectingSpawnerColor.get() : null;
            case VAULT -> trackVaults.get() ? vaultColor.get() : null;
            case EJECTING_VAULT -> trackVaults.get() ? ejectingVaultColor.get() : null;
            case OMINOUS_VAULT -> trackVaults.get() ? ominousVaultColor.get() : null;
            case LOOT_POT -> lootPotColor.get();
            case CONTAINER -> trackContainers.get() ? containerColor.get() : null;
        };
    }

    private void pruneBlockTargets() {
        if (mc.world == null || mc.player == null) return;
        Set<BlockPos> toRemove = new HashSet<>();
        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;

            if (mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                Block currentBlock = mc.world.getBlockState(pos).getBlock();
                if (mc.world.getBlockState(pos).isAir() || !validateBlockType(currentBlock, entry.getValue())) {
                    toRemove.add(pos);
                }
            } else {
                // Chunk is unloaded! Remove the target so the HUD accurately reflects current render distance
                toRemove.add(pos);
                scannedChunks.remove(new ChunkPos(chunkX, chunkZ)); // Ensure it gets rescanned if reloaded
            }
        }
        for (BlockPos pos : toRemove) {
            targets.remove(pos);
            notifiedPots.remove(pos);
            notifiedActiveOminousSpawners.remove(pos);
        }
    }

    private void cleanupDistantTargets(BlockPos playerPos) {
        int r = range.get();
        int pChunkX = playerPos.getX() >> 4;
        int pChunkZ = playerPos.getZ() >> 4;

        targets.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            int dx = (pos.getX() >> 4) - pChunkX;
            int dz = (pos.getZ() >> 4) - pChunkZ;
            if (dx * dx + dz * dz > r * r) {
                scannedChunks.remove(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
                notifiedPots.remove(pos);
                notifiedActiveOminousSpawners.remove(pos);
                return true;
            }
            return false;
        });
    }

    private boolean performSafetyChecks() {
        if (!autoDisableOnLowHealth.get()) return false;
        boolean hasTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
            || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
        if (hasTotem && mc.player.getHealth() <= 6) { 
            error("Health is critical, disabling to prevent totem pop.");
            toggle();
            return true;
        }
        return false;
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    private int toArgb(SettingColor c) {
        return (c.a << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HUD API
    // ═══════════════════════════════════════════════════════════════════════════

    public record ChamberStat(String name, int count, ItemStack icon, ChambersAssistantHud.StatSeverity severity) {}

    public List<ChambersAssistantHud.ChamberStat> getStats() {
        List<ChambersAssistantHud.ChamberStat> stats = new ArrayList<>();
        int normalSpawners = 0, ominousSpawners = 0, normalVaults = 0, ominousVaults = 0, lootPots = 0, containers = 0;
        
        for (TargetType type : targets.values()) {
            switch (type) {
                case TRIAL_SPAWNER, ACTIVE_TRIAL_SPAWNER, EJECTING_TRIAL_SPAWNER -> normalSpawners++;
                case ACTIVE_OMINOUS_SPAWNER, EJECTING_OMINOUS_SPAWNER, OMINOUS_SPAWNER -> ominousSpawners++;
                case VAULT, EJECTING_VAULT -> normalVaults++;
                case OMINOUS_VAULT -> ominousVaults++;
                case LOOT_POT -> lootPots++;
                case CONTAINER -> containers++;
            }
        }
        
        stats.add(new ChambersAssistantHud.ChamberStat("Spawners", normalSpawners, new ItemStack(Items.TRIAL_SPAWNER), ChambersAssistantHud.StatSeverity.Normal));
        stats.add(new ChambersAssistantHud.ChamberStat("Ominous", ominousSpawners, new ItemStack(Items.TRIAL_SPAWNER), ominousSpawners > 0 ? ChambersAssistantHud.StatSeverity.Warning : ChambersAssistantHud.StatSeverity.Normal));
        stats.add(new ChambersAssistantHud.ChamberStat("Vaults", normalVaults, new ItemStack(Items.VAULT), ChambersAssistantHud.StatSeverity.Normal));
        stats.add(new ChambersAssistantHud.ChamberStat("Ominous V", ominousVaults, new ItemStack(Items.VAULT), ChambersAssistantHud.StatSeverity.Normal));
        stats.add(new ChambersAssistantHud.ChamberStat("Pots", lootPots, new ItemStack(Items.DECORATED_POT), ChambersAssistantHud.StatSeverity.Normal));
        stats.add(new ChambersAssistantHud.ChamberStat("Chests", containers, new ItemStack(Items.CHEST), ChambersAssistantHud.StatSeverity.Normal));
        stats.add(new ChambersAssistantHud.ChamberStat("Breezes", breezeTargets.size(), new ItemStack(Items.WIND_CHARGE), breezeTargets.size() > 0 ? ChambersAssistantHud.StatSeverity.Warning : ChambersAssistantHud.StatSeverity.Normal));
        stats.add(new ChambersAssistantHud.ChamberStat("Keys", trialItemTargets.size(), new ItemStack(Items.TRIAL_KEY), ChambersAssistantHud.StatSeverity.Normal));
        
        return stats;
    }
}