package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.EndermiteEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

public class DungeonAssistant extends Module {

    // ─────────────────────────── State ───────────────────────────
    private final Map<BlockPos, TargetType> targets = new HashMap<>();
    private final Map<BlockPos, Integer> stackedMinecartCounts = new HashMap<>();
    private final Map<BlockPos, Long> brokenSpawners = new HashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<BlockPos> checkedContainers = new HashSet<>();
    private final List<EndermiteEntity> endermiteTargets = new ArrayList<>();
    private final Set<Integer> notifiedEndermites = new HashSet<>();
    private final Set<Integer> checkedEntityIds = new HashSet<>();
    private final Set<BlockPos> spawnerTorches = new HashSet<>();

    // ─────────────────────────── Setting Groups ───────────────────────────
    private final SettingGroup sgGeneral       = settings.getDefaultGroup();
    private final SettingGroup sgAutoOpen      = settings.createGroup("Auto Open");
    private final SettingGroup sgSpawners      = settings.createGroup("Spawners");
    private final SettingGroup sgChests        = settings.createGroup("Chests");
    private final SettingGroup sgClutterBlocks = settings.createGroup("Clutter Blocks");
    private final SettingGroup sgEndermites    = settings.createGroup("Endermites");
    private final SettingGroup sgContainerUI   = settings.createGroup("Container UI");
    private final SettingGroup sgSafety        = settings.createGroup("Safety");

    // ── General ──
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Detection range in chunks (1 chunk = 16 blocks). High values impact performance.")
        .defaultValue(16)
        .min(1)
        .max(128)
        .sliderMin(1)
        .sliderMax(64)
        .build()
    );

    private final Setting<Integer> minYSetting = sgGeneral.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y-level to scan.")
        .defaultValue(-64)
        .min(-64)
        .max(320)
        .sliderMin(-64)
        .sliderMax(320)
        .build()
    );

    private final Setting<Integer> maxYSetting = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y-level to scan.")
        .defaultValue(320)
        .min(-64)
        .max(320)
        .sliderMin(-64)
        .sliderMax(320)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Render style.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    // ── Auto Open ──
    private final Setting<Boolean> autoOpen = sgAutoOpen.add(new BoolSetting.Builder()
        .name("auto-open")
        .description("Automatically open, check, and close containers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> silentMode = sgAutoOpen.add(new BoolSetting.Builder()
        .name("silent-mode")
        .description("Open containers invisibly — GUI is suppressed, inventory is read server-side, then the break-delay fires if no whitelisted items are found.")
        .defaultValue(true)
        .visible(autoOpen::get)
        .build()
    );

    private final Setting<Boolean> autoBreak = sgAutoOpen.add(new BoolSetting.Builder()
        .name("auto-break")
        .description("Break empty containers after checking.")
        .defaultValue(true)
        .visible(autoOpen::get)
        .build()
    );

    private final Setting<Integer> breakDelay = sgAutoOpen.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Ticks to wait before breaking an empty container.")
        .defaultValue(5)
        .min(0)
        .max(40)
        .sliderMin(0)
        .sliderMax(20)
        .visible(() -> autoOpen.get() && autoBreak.get())
        .build()
    );

    private final Setting<Boolean> silentSwitch = sgAutoOpen.add(new BoolSetting.Builder()
        .name("silent-switch")
        .description("Switch to axe (chest) or sword (chest minecart) for breaking, then restore the previous hotbar slot.")
        .defaultValue(true)
        .visible(() -> autoOpen.get() && autoBreak.get())
        .build()
    );

    private final Setting<List<Item>> whitelistedItems = sgAutoOpen.add(new ItemListSetting.Builder()
        .name("whitelisted-items")
        .description("Items to look for — if found the container is left open and a sound plays.")
        .defaultValue(List.of(
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.ENDER_CHEST,
            Items.SHULKER_BOX,
            Items.WHITE_SHULKER_BOX,   Items.ORANGE_SHULKER_BOX,
            Items.MAGENTA_SHULKER_BOX, Items.LIGHT_BLUE_SHULKER_BOX,
            Items.YELLOW_SHULKER_BOX,  Items.LIME_SHULKER_BOX,
            Items.PINK_SHULKER_BOX,    Items.GRAY_SHULKER_BOX,
            Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX,
            Items.PURPLE_SHULKER_BOX,  Items.BLUE_SHULKER_BOX,
            Items.BROWN_SHULKER_BOX,   Items.GREEN_SHULKER_BOX,
            Items.RED_SHULKER_BOX,     Items.BLACK_SHULKER_BOX
        ))
        .visible(autoOpen::get)
        .build()
    );

    // ── Spawners ──
    private final Setting<Boolean> trackSpawners = sgSpawners.add(new BoolSetting.Builder()
        .name("track-spawners")
        .description("Highlight monster spawners.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> spawnerColor = sgSpawners.add(new ColorSetting.Builder()
        .name("spawner-color")
        .description("Monster spawner highlight color.")
        .defaultValue(new SettingColor(255, 0, 0, 100))
        .visible(trackSpawners::get)
        .build()
    );

    private final Setting<Boolean> notifySpawnerBreak = sgSpawners.add(new BoolSetting.Builder()
        .name("notify-break")
        .description("Notifies you when a spawner is broken.")
        .defaultValue(false)
        .visible(trackSpawners::get)
        .build()
    );

    private final Setting<Boolean> showBrokenSpawnerBeam = sgSpawners.add(new BoolSetting.Builder()
        .name("broken-spawner-beam")
        .description("Renders a beam at the Y-level where a spawner was broken, extending both up and down.")
        .defaultValue(true)
        .visible(trackSpawners::get)
        .build()
    );

    private final Setting<SettingColor> brokenSpawnerColor = sgSpawners.add(new ColorSetting.Builder()
        .name("broken-spawner-color")
        .description("Color of the broken spawner beam.")
        .defaultValue(new SettingColor(255, 0, 0, 150))
        .visible(() -> trackSpawners.get() && showBrokenSpawnerBeam.get())
        .build()
    );

    private final Setting<Integer> brokenSpawnerDuration = sgSpawners.add(new IntSetting.Builder()
        .name("broken-beam-duration")
        .description("How long the beam remains (in seconds).")
        .defaultValue(10)
        .min(1)
        .sliderMax(60)
        .visible(() -> trackSpawners.get() && showBrokenSpawnerBeam.get())
        .build()
    );

    private final Setting<Boolean> autoBreakSpawners = sgSpawners.add(new BoolSetting.Builder()
        .name("auto-break")
        .description("Automatically break spawners in range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> spawnerBreakRange = sgSpawners.add(new IntSetting.Builder()
        .name("break-range")
        .description("Range in blocks to break spawners.")
        .defaultValue(5)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .visible(autoBreakSpawners::get)
        .build()
    );

    private final Setting<Integer> spawnerBreakDelay = sgSpawners.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Ticks to wait before breaking a spawner.")
        .defaultValue(5)
        .min(0)
        .max(20)
        .visible(autoBreakSpawners::get)
        .build()
    );

    // FIX #3/#9: Moved prioritizeSpawners into sgAutoOpen so it's discoverable
    // alongside the other ordering/priority settings; kept visible only when
    // both autoBreakSpawners and autoOpen are enabled.
    private final Setting<Boolean> prioritizeSpawners = sgAutoOpen.add(new BoolSetting.Builder()
        .name("prioritize-spawners")
        .description("Break spawners before opening chests when both auto-break and auto-open are active.")
        .defaultValue(true)
        .visible(() -> autoOpen.get() && autoBreakSpawners.get())
        .build()
    );

    // ── Chests ──
    private final Setting<Boolean> trackChests = sgChests.add(new BoolSetting.Builder()
        .name("track-chests")
        .description("Highlight chests.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> chestColor = sgChests.add(new ColorSetting.Builder()
        .name("chest-color")
        .description("Chest highlight color.")
        .defaultValue(new SettingColor(255, 215, 0, 80))
        .visible(trackChests::get)
        .build()
    );

    private final Setting<Boolean> trackChestMinecarts = sgChests.add(new BoolSetting.Builder()
        .name("track-chest-minecarts")
        .description("Highlight chest minecarts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> chestMinecartColor = sgChests.add(new ColorSetting.Builder()
        .name("chest-minecart-color")
        .description("Chest minecart highlight color.")
        .defaultValue(new SettingColor(255, 180, 0, 80))
        .visible(trackChestMinecarts::get)
        .build()
    );

    private final Setting<Boolean> highlightStacked = sgChests.add(new BoolSetting.Builder()
        .name("highlight-stacked-minecarts")
        .description("Use different color for stacked chest minecarts (2+).")
        .defaultValue(true)
        .visible(trackChestMinecarts::get)
        .build()
    );

    private final Setting<SettingColor> stackedMinecartColor = sgChests.add(new ColorSetting.Builder()
        .name("stacked-minecart-color")
        .description("Highlight color for stacked chest minecarts.")
        .defaultValue(new SettingColor(255, 0, 255, 120))
        .visible(() -> trackChestMinecarts.get() && highlightStacked.get())
        .build()
    );

    private final Setting<Boolean> brokenChestCounter = sgChests.add(new BoolSetting.Builder()
        .name("broken-chest-counter")
        .description("Counts and displays how many chests have been broken.")
        .defaultValue(true)
        .build()
    );

    // ── Clutter Blocks ──
    private final Setting<Boolean> scanCustomBlocks = sgClutterBlocks.add(new BoolSetting.Builder()
        .name("scan-blocks")
        .description("Highlight selected blocks in the surrounding area.")
        .defaultValue(true)
        .onChanged(v -> { targets.entrySet().removeIf(e -> e.getValue() == TargetType.CUSTOM_BLOCK); scannedChunks.clear(); })
        .build()
    );

    private final Setting<List<Block>> filterBlocks = sgClutterBlocks.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to search for and highlight in the world.")
        .defaultValue(List.of(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK))
        .onChanged(v -> { targets.entrySet().removeIf(e -> e.getValue() == TargetType.CUSTOM_BLOCK); scannedChunks.clear(); })
        .visible(scanCustomBlocks::get)
        .build()
    );

    private final Setting<SettingColor> customBlockColor = sgClutterBlocks.add(new ColorSetting.Builder()
        .name("block-color")
        .description("Highlight color for the selected blocks.")
        .defaultValue(new SettingColor(128, 200, 128, 60))
        .visible(scanCustomBlocks::get)
        .build()
    );

    private final Setting<Boolean> trackMisrotatedDeepslate = sgClutterBlocks.add(new BoolSetting.Builder()
        .name("misrotated-deepslate")
        .description("Highlights Deepslate blocks facing the wrong direction (axis ≠ Y). Indicates player-placed or tampered blocks.")
        .defaultValue(false)
        .onChanged(v -> { targets.entrySet().removeIf(e -> e.getValue() == TargetType.MISROTATED_DEEPSLATE); scannedChunks.clear(); })
        .build()
    );

    private final Setting<SettingColor> misrotatedDeepslateColor = sgClutterBlocks.add(new ColorSetting.Builder()
        .name("misrotated-deepslate-color")
        .description("Highlight color for misrotated Deepslate blocks.")
        .defaultValue(new SettingColor(0, 180, 255, 100))
        .visible(trackMisrotatedDeepslate::get)
        .build()
    );

    private final Setting<Boolean> highlightSpawnerTorches = sgClutterBlocks.add(new BoolSetting.Builder()
        .name("highlight-spawner-torches")
        .description("Highlights torches within 5 blocks of a spawner.")
        .defaultValue(true)
        .visible(trackSpawners::get)
        .build()
    );

    private final Setting<SettingColor> spawnerTorchColor = sgClutterBlocks.add(new ColorSetting.Builder()
        .name("spawner-torch-color")
        .description("Color for torches near spawners.")
        .defaultValue(new SettingColor(255, 255, 0, 150))
        .visible(() -> trackSpawners.get() && highlightSpawnerTorches.get())
        .build()
    );

    private final Setting<Keybind> toggleBlocksKey = sgClutterBlocks.add(new KeybindSetting.Builder()
        .name("toggle-key")
        .description("Key to toggle custom block scanning on/off.")
        .defaultValue(Keybind.none())
        .action(() -> {
            boolean newValue = !scanCustomBlocks.get();
            scanCustomBlocks.set(newValue);
            if (mc.player != null) info("Custom Blocks Highlight toggled %s.", newValue ? "§aON" : "§cOFF");
        })
        .build()
    );

    // ── Endermites ──
    private final Setting<Boolean> trackEndermites = sgEndermites.add(new BoolSetting.Builder()
        .name("track-endermites")
        .description("Highlights Endermites in the Overworld.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> endermiteColor = sgEndermites.add(new ColorSetting.Builder()
        .name("endermite-color")
        .description("The highlight color for Endermites.")
        .defaultValue(new SettingColor(138, 43, 226, 150))
        .visible(trackEndermites::get)
        .build()
    );

    private final Setting<Boolean> endermiteBeam = sgEndermites.add(new BoolSetting.Builder()
        .name("show-beam")
        .description("Shows a beam above detected Endermites.")
        .defaultValue(true)
        .visible(trackEndermites::get)
        .build()
    );

    private final Setting<Integer> endermiteBeamWidth = sgEndermites.add(new IntSetting.Builder()
        .name("beam-width")
        .description("The width of the beam.")
        .defaultValue(15)
        .min(5)
        .max(50)
        .visible(() -> trackEndermites.get() && endermiteBeam.get())
        .build()
    );

    // FIX #7: showContainerButtons and dumpHotbar now properly added to sgContainerUI
    public final Setting<Boolean> showContainerButtons = sgContainerUI.add(new BoolSetting.Builder()
        .name("show-container-buttons")
        .description("Shows 'Steal' and 'Dump' buttons on the right side of container GUIs.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> dumpHotbar = sgContainerUI.add(new BoolSetting.Builder()
        .name("dump-hotbar")
        .description("Whether the 'Dump' button also dumps your hotbar.")
        .defaultValue(false)
        .build()
    );

    // ── Safety ──
    private final Setting<Boolean> autoDisableOnLowHealth = sgSafety.add(new BoolSetting.Builder()
        .name("auto-disable-on-low-health")
        .description("Automatically disables the module if health is critically low with a totem equipped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lowHealthThreshold = sgSafety.add(new IntSetting.Builder()
        .name("low-health-threshold")
        .description("Health level (in hearts) to trigger auto-disable.")
        .defaultValue(3)
        .min(1)
        .max(10)
        .sliderRange(1, 5)
        .visible(autoDisableOnLowHealth::get)
        .build()
    );

    // ─────────────────────────── Runtime State ───────────────────────────
    private boolean hasPlayedSoundForCurrentScreen = false;
    private BlockPos lastOpenedContainer = null;
    private int breakDelayTimer = 0;
    private Entity lastOpenedEntity = null;
    private boolean isBreaking = false;
    private boolean isBreakingEntity = false;
    private BlockPos blockToBreak = null;
    private boolean isBreakingChest = false;
    private int brokenChestsCount = 0;
    private Entity entityToBreak = null;
    private String lastDimension = "";
    // FIX #(misc): dimensionChangeCooldown now starts at a meaningful value on
    // dimension change so we don't immediately re-scan in an inconsistent state.
    private int dimensionChangeCooldown = 0;
    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;

    /** True only when the container was opened by the auto-open logic, not by the player manually. */
    private boolean wasAutoOpened = false;

    // ── Silent open/close state ──
    private boolean silentOpenPending = false;
    private boolean silentFoundWhitelisted = false;
    private boolean pendingBreakCheck = false;
    // FIX #10: retry counter for late InventoryS2CPacket arrival on laggy servers
    private int silentSlotReadRetryTimer = 0;
    private static final int SILENT_SLOT_READ_MAX_RETRIES = 5;
    private int previousSlot = -1;

    private int interactTimeoutTimer = 0;
    private static final int INTERACT_TIMEOUT_TICKS = 20;

    public DungeonAssistant() {
        super(HuntingUtilities.CATEGORY, "dungeon-assistant", "Highlights dungeon elements: spawners, chests, and dungeon blocks.");
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    @Override
    public void onActivate() {
        targets.clear();
        stackedMinecartCounts.clear();
        brokenSpawners.clear();
        scannedChunks.clear();
        checkedContainers.clear();
        endermiteTargets.clear();
        notifiedEndermites.clear();
        checkedEntityIds.clear();
        spawnerTorches.clear();
        brokenChestsCount = 0;
        isBreakingChest = false;
        hasPlayedSoundForCurrentScreen = false;

        if (mc.player != null && mc.world != null) {
            info("§6Dungeon Assistant activated");
            if (mc.world.getRegistryKey() != null) {
                lastDimension = mc.world.getRegistryKey().getValue().toString();
            }
        }
    }

    @Override
    public void onDeactivate() {
        targets.clear();
        stackedMinecartCounts.clear();
        brokenSpawners.clear();
        checkedContainers.clear();
        endermiteTargets.clear();
        notifiedEndermites.clear();
        checkedEntityIds.clear();
        spawnerTorches.clear();
        // Restore slot if silent-switch was interrupted mid-break
        if (previousSlot >= 0 && mc.player != null) {
            mc.player.getInventory().selectedSlot = previousSlot;
        }
        resetAutoOpenState();
    }

    // ─────────────────────────── Event Handlers ───────────────────────────

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null || mc.world == null) return;

        if (wasAutoOpened) {
            interactTimeoutTimer = 0;

            if (autoOpen.get() && silentMode.get()
                    && event.screen instanceof HandledScreen<?>
                    && !(event.screen instanceof InventoryScreen)) {
                // FIX #10: Reset retry counter; we'll poll for non-empty slots in updateContainerLogic
                silentOpenPending = true;
                silentSlotReadRetryTimer = 0;
            }
            return;
        }

        // Manual open — track the target for Steal/Dump buttons
        HitResult hit = mc.crosshairTarget;
        if (hit != null) {
            if (hit.getType() == HitResult.Type.BLOCK) {
                lastOpenedContainer = ((BlockHitResult) hit).getBlockPos();
                lastOpenedEntity = null;
            } else if (hit.getType() == HitResult.Type.ENTITY) {
                EntityHitResult entityHit = (EntityHitResult) hit;
                if (entityHit.getEntity() instanceof ChestMinecartEntity) {
                    lastOpenedEntity = entityHit.getEntity();
                    lastOpenedContainer = null;
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (performSafetyChecks()) return;

        // FIX #4: Run breaking logic FIRST so isBreaking is armed before
        // updateContainerLogic reads it in the same tick.
        updateBreakingLogic();
        updateContainerLogic();
        updateScanningLogic();
    }

    // ─────────────────────────── Logic Methods ───────────────────────────

    private boolean performSafetyChecks() {
        if (autoDisableOnLowHealth.get()) {
            boolean hasTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
            if (hasTotem && mc.player.getHealth() <= lowHealthThreshold.get() * 2) {
                error("Health is critical (%.1f), disabling to prevent totem pop.", mc.player.getHealth());
                toggle();
                return true;
            }
        }
        return false;
    }

    private void updateBreakingLogic() {
        // Break-delay countdown
        if (breakDelayTimer > 0) {
            breakDelayTimer--;
            if (breakDelayTimer == 0) {
                if (blockToBreak != null) {
                    Block targetBlock = mc.world.getBlockState(blockToBreak).getBlock();
                    if (targetBlock == Blocks.CHEST || targetBlock == Blocks.TRAPPED_CHEST || targetBlock == Blocks.SPAWNER) {
                        isBreaking = true;
                        isBreakingChest = (targetBlock == Blocks.CHEST || targetBlock == Blocks.TRAPPED_CHEST);
                        if (silentSwitch.get() && previousSlot < 0) {
                            previousSlot = mc.player.getInventory().selectedSlot;
                        }
                    } else {
                        blockToBreak = null;
                    }
                } else if (entityToBreak != null) {
                    if (entityToBreak instanceof ChestMinecartEntity) {
                        isBreakingEntity = true;
                        if (silentSwitch.get() && previousSlot < 0) {
                            previousSlot = mc.player.getInventory().selectedSlot;
                        }
                    } else {
                        entityToBreak = null;
                    }
                }
            }
        }

        // ── Breaking a block (chest / trapped chest / spawner) ──
        if (isBreaking && blockToBreak != null && !mc.player.isTouchingWater()) {
            Block currentBreakTarget = mc.world.getBlockState(blockToBreak).getBlock();
            boolean done = mc.world.getBlockState(blockToBreak).isAir()
                || (currentBreakTarget != Blocks.CHEST && currentBreakTarget != Blocks.TRAPPED_CHEST && currentBreakTarget != Blocks.SPAWNER)
                || Math.sqrt(mc.player.squaredDistanceTo(blockToBreak.toCenterPos())) > 6;

            if (done) {
                if (isBreakingChest && mc.world.getBlockState(blockToBreak).isAir()) {
                    if (brokenChestCounter.get()) {
                        brokenChestsCount++;
                        info("Chests broken: " + brokenChestsCount);
                    }
                }
                isBreaking = false;
                blockToBreak = null;
                isBreakingChest = false;
                mc.interactionManager.cancelBlockBreaking();
                restoreSlot();
            } else {
                if (currentBreakTarget == Blocks.SPAWNER) {
                    int slot = findPickaxe();
                    if (slot != -1) mc.player.getInventory().selectedSlot = slot;
                } else {
                    int slot = findAxe();
                    if (slot != -1) mc.player.getInventory().selectedSlot = slot;
                }
                Rotations.rotate(Rotations.getYaw(blockToBreak), Rotations.getPitch(blockToBreak), () -> {
                    mc.interactionManager.updateBlockBreakingProgress(blockToBreak, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
            }
        }

        // ── Breaking a chest minecart ──
        if (isBreakingEntity && entityToBreak != null && !mc.player.isTouchingWater()) {
            boolean gone = !(entityToBreak instanceof ChestMinecartEntity)
                || !entityToBreak.isAlive()
                || mc.player.distanceTo(entityToBreak) > 6;

            if (gone) {
                isBreakingEntity = false;
                entityToBreak = null;
                restoreSlot();
            } else {
                int swordSlot = findSword();
                if (swordSlot != -1) mc.player.getInventory().selectedSlot = swordSlot;
                if (mc.player.getAttackCooldownProgress(0f) >= 1.0f) {
                    Rotations.rotate(Rotations.getYaw(entityToBreak), Rotations.getPitch(entityToBreak), () -> {
                        mc.interactionManager.attackEntity(mc.player, entityToBreak);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    });
                }
            }
        }
    }

    private void updateContainerLogic() {

        // ── Interact timeout ──
        if (interactTimeoutTimer > 0) {
            interactTimeoutTimer--;
            if (interactTimeoutTimer == 0 && wasAutoOpened && mc.currentScreen == null) {
                // Server never responded — remove from checked sets so it can be retried
                if (lastOpenedContainer != null) checkedContainers.remove(lastOpenedContainer);
                if (lastOpenedEntity != null)    checkedEntityIds.remove(lastOpenedEntity.getId());
                resetAutoOpenState();
            }
        }

        // ── SILENT MODE: wait for the container handler to be populated ──
        // FIX #10: Retry up to SILENT_SLOT_READ_MAX_RETRIES ticks so that the
        // InventoryS2CPacket has time to arrive on laggy servers. We only commit
        // once at least one slot is non-empty (or we exhaust retries).
        if (silentOpenPending && mc.currentScreen instanceof HandledScreen
                && !(mc.currentScreen instanceof InventoryScreen)) {

            HandledScreen<?> silentScreen = (HandledScreen<?>) mc.currentScreen;
            int numSlots     = silentScreen.getScreenHandler().slots.size();
            int containerSlots = numSlots - 36;

            if (containerSlots > 0) {
                // Check whether the server has populated at least one slot
                boolean anyNonEmpty = false;
                for (int i = 0; i < containerSlots; i++) {
                    if (!silentScreen.getScreenHandler().slots.get(i).getStack().isEmpty()) {
                        anyNonEmpty = true;
                        break;
                    }
                }

                boolean retriesExhausted = silentSlotReadRetryTimer >= SILENT_SLOT_READ_MAX_RETRIES;

                if (anyNonEmpty || retriesExhausted) {
                    // Slots are ready (or we've waited long enough — commit what we have)
                    silentFoundWhitelisted = false;
                    for (int i = 0; i < containerSlots; i++) {
                        Item item = silentScreen.getScreenHandler().slots.get(i).getStack().getItem();
                        if (whitelistedItems.get().contains(item)) {
                            silentFoundWhitelisted = true;
                            break;
                        }
                    }
                    pendingBreakCheck = true;
                    mc.player.closeHandledScreen();
                    silentOpenPending = false;
                    silentSlotReadRetryTimer = 0;
                    return;
                } else {
                    // Still waiting for the packet — try again next tick
                    silentSlotReadRetryTimer++;
                    return;
                }
            }
        }

        // ── After a silent close: decide break or alert ──
        if (pendingBreakCheck && mc.currentScreen == null && !silentOpenPending) {
            pendingBreakCheck = false;
            wasAutoOpened = false;
            hasPlayedSoundForCurrentScreen = false;

            if (!silentFoundWhitelisted) {
                if (autoBreak.get()) {
                    if (lastOpenedContainer != null) {
                        blockToBreak = lastOpenedContainer;
                        removeNeighborFromChecked(lastOpenedContainer);
                        breakDelayTimer = getRandomizedDelay(breakDelay.get());
                    } else if (lastOpenedEntity != null) {
                        entityToBreak = lastOpenedEntity;
                        breakDelayTimer = getRandomizedDelay(breakDelay.get());
                    }
                }
            } else {
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
            return;
        }

        // ── NON-SILENT MODE: screen is open ──
        if (mc.currentScreen instanceof HandledScreen && !(mc.currentScreen instanceof InventoryScreen)) {
            if (!wasAutoOpened) return;
            if (lastOpenedContainer == null && lastOpenedEntity == null) return;
            if (lastOpenedEntity != null && !(lastOpenedEntity instanceof ChestMinecartEntity)) return;

            HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
            int numSlots      = screen.getScreenHandler().slots.size();
            int containerSlots = numSlots - 36;

            if (containerSlots > 0) {
                boolean found = false;
                for (int i = 0; i < containerSlots; i++) {
                    if (whitelistedItems.get().contains(screen.getScreenHandler().slots.get(i).getStack().getItem())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    mc.player.closeHandledScreen();
                    wasAutoOpened = false;
                    if (autoBreak.get()) {
                        if (lastOpenedContainer != null) {
                            blockToBreak = lastOpenedContainer;
                            removeNeighborFromChecked(lastOpenedContainer);
                            breakDelayTimer = getRandomizedDelay(breakDelay.get());
                        } else if (lastOpenedEntity != null) {
                            entityToBreak = lastOpenedEntity;
                            breakDelayTimer = getRandomizedDelay(breakDelay.get());
                        }
                    }
                } else {
                    wasAutoOpened = false;
                    if (!hasPlayedSoundForCurrentScreen) {
                        boolean isChestOrMinecart = lastOpenedEntity != null
                            || (lastOpenedContainer != null
                                && (mc.world.getBlockState(lastOpenedContainer).getBlock() == Blocks.CHEST
                                || mc.world.getBlockState(lastOpenedContainer).getBlock() == Blocks.TRAPPED_CHEST));
                        if (isChestOrMinecart) {
                            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                            hasPlayedSoundForCurrentScreen = true;
                        }
                    }
                }
            }

        // ── No screen open: run auto-open cycle ──
        } else if (mc.currentScreen == null && !isBreaking && !isBreakingEntity
                && breakDelayTimer == 0 && !pendingBreakCheck && !silentOpenPending
                && !wasAutoOpened) {

            hasPlayedSoundForCurrentScreen = false;

            if (autoOpen.get()) {
                // FIX #3: Only prioritize spawners when one is actually within break range,
                // so distant spawners don't block chest-checking indefinitely.
                if (prioritizeSpawners.get() && autoBreakSpawners.get()) {
                    if (isSpawnerInBreakRange()) {
                        if (runSpawnerCheck()) return;
                    }
                    if (runMinecartCheck()) return;
                    if (runChestCheck()) return;
                    // Fall through to spawner break if no containers found
                    if (runSpawnerCheck()) return;
                } else {
                    if (runMinecartCheck()) return;
                    if (runChestCheck()) return;
                    if (runSpawnerCheck()) return;
                }
            }
        }
    }

    private void updateScanningLogic() {
        try {
            if (mc.world.getRegistryKey() == null) return;
        } catch (Exception e) { return; }

        if (dimensionChangeCooldown > 0) {
            dimensionChangeCooldown--;
            return;
        }

        try {
            String currDim = mc.world.getRegistryKey().getValue().toString();
            if (!currDim.equals(lastDimension)) {
                // FIX #(misc): Use a real cooldown value so we don't immediately re-scan
                // in an inconsistent state right after a dimension change.
                dimensionChangeCooldown = DIMENSION_CHANGE_COOLDOWN_TICKS;
                lastDimension = currDim;
                resetScanningState();
                return;
            }
        } catch (Exception ignored) { return; }

        BlockPos playerPos = mc.player.getBlockPos();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        cleanupDistantTargets(playerPos);
        scanBlockEntities(centerChunkX, centerChunkZ);
        scanChestMinecarts(centerChunkX, centerChunkZ);
        scanNewChunks(centerChunkX, centerChunkZ);
        scanEndermites();
        scanSpawnerTorches();

        // FIX #8: Prune stale checkedEntityIds for entities that are no longer alive/loaded
        pruneCheckedEntityIds();
    }

    // ─────────────────────────── Auto-Open Helpers ───────────────────────────

    /** FIX #3: Returns true if at least one spawner is within spawnerBreakRange. */
    private boolean isSpawnerInBreakRange() {
        double rangeSq = Math.pow(spawnerBreakRange.get(), 2);
        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            if (entry.getValue() == TargetType.SPAWNER) {
                if (entry.getKey().getSquaredDistance(mc.player.getPos()) <= rangeSq) return true;
            }
        }
        return false;
    }

    private boolean runSpawnerCheck() {
        if (!autoBreakSpawners.get()) return false;

        if (areMobsNearby()) return false;

        BlockPos bestPos = null;
        double minDistanceSq = Double.MAX_VALUE;
        double rangeSq = Math.pow(spawnerBreakRange.get(), 2);

        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            if (entry.getValue() == TargetType.SPAWNER) {
                BlockPos pos = entry.getKey();
                double distSq = pos.getSquaredDistance(mc.player.getPos());
                if (distSq <= rangeSq && distSq < minDistanceSq) {
                    minDistanceSq = distSq;
                    bestPos = pos;
                }
            }
        }

        if (bestPos != null) {
            blockToBreak = bestPos;
            breakDelayTimer = getRandomizedDelay(spawnerBreakDelay.get());
            return true;
        }
        return false;
    }

    private boolean areMobsNearby() {
        if (mc.player == null || mc.world == null) return false;
        // FIX #11: Use spawnerBreakRange instead of a hardcoded 4-block radius
        double radius = spawnerBreakRange.get();
        return !mc.world.getEntitiesByClass(HostileEntity.class,
            new Box(mc.player.getBlockPos()).expand(radius),
            Entity::isAlive).isEmpty();
    }

    private boolean runMinecartCheck() {
        if (!trackChestMinecarts.get()) return false;

        List<ChestMinecartEntity> minecarts = mc.world.getEntitiesByClass(
            ChestMinecartEntity.class,
            new Box(mc.player.getBlockPos()).expand(4.5),
            e -> !checkedEntityIds.contains(e.getId())
        );
        if (minecarts.isEmpty()) return false;

        minecarts.sort(Comparator.comparingDouble(e -> mc.player.squaredDistanceTo(e)));
        ChestMinecartEntity cart = minecarts.get(0);
        if (mc.player.distanceTo(cart) > 4.5) return false;

        lastOpenedEntity = cart;
        lastOpenedContainer = null;
        checkedEntityIds.add(cart.getId());
        wasAutoOpened = true;
        interactTimeoutTimer = INTERACT_TIMEOUT_TICKS;

        Rotations.rotate(Rotations.getYaw(cart), Rotations.getPitch(cart), () -> {
            mc.interactionManager.interactEntity(mc.player, cart, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
        });
        return true;
    }

    private boolean runChestCheck() {
        if (!trackChests.get()) return false;

        List<BlockPos> nearbyChests = targets.entrySet().stream()
            .filter(e -> e.getValue() == TargetType.CHEST)
            .map(Map.Entry::getKey)
            .filter(pos -> !checkedContainers.contains(pos))
            .filter(pos -> Math.sqrt(pos.getSquaredDistance(mc.player.getPos())) <= 4.5)
            .sorted(Comparator.comparingDouble(pos -> pos.getSquaredDistance(mc.player.getPos())))
            .toList();

        if (nearbyChests.isEmpty()) return false;

        BlockPos pos = nearbyChests.get(0);
        Block block = mc.world.getBlockState(pos).getBlock();

        checkedContainers.add(pos);

        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos neighbor = pos.offset(dir);
                if (mc.world.getBlockState(neighbor).getBlock() == block) {
                    checkedContainers.add(neighbor);
                    break;
                }
            }
        }

        lastOpenedContainer = pos;
        lastOpenedEntity = null;
        wasAutoOpened = true;
        interactTimeoutTimer = INTERACT_TIMEOUT_TICKS;

        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        });
        return true;
    }

    // ─────────────────────────── Scanning ───────────────────────────

    private void resetScanningState() {
        targets.clear();
        stackedMinecartCounts.clear();
        brokenSpawners.clear();
        scannedChunks.clear();
        checkedContainers.clear();
        checkedEntityIds.clear();
    }

    private void scanEndermites() {
        endermiteTargets.clear();

        if (!trackEndermites.get() || mc.world == null || mc.player == null) {
            notifiedEndermites.clear();
            return;
        }

        if (!mc.world.getRegistryKey().getValue().toString().equals("minecraft:overworld")) {
            notifiedEndermites.clear();
            return;
        }

        int blockRange = range.get() * 16;
        Box searchBox = new Box(mc.player.getBlockPos()).expand(blockRange);

        Set<Integer> currentIds = new HashSet<>();
        for (EndermiteEntity endermite : mc.world.getEntitiesByClass(EndermiteEntity.class, searchBox, e -> true)) {
            endermiteTargets.add(endermite);
            currentIds.add(endermite.getId());

            if (notifiedEndermites.add(endermite.getId())) {
                info("Endermite Detected, Beam created");
                mc.player.playSound(SoundEvents.ENTITY_ENDERMITE_AMBIENT, 1.0f, 1.0f);
            }
        }

        notifiedEndermites.retainAll(currentIds);
    }

    private void scanSpawnerTorches() {
        spawnerTorches.clear();
        if (!trackSpawners.get() || !highlightSpawnerTorches.get()) return;

        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            if (entry.getValue() == TargetType.SPAWNER) {
                BlockPos spawnerPos = entry.getKey();
                if (!mc.world.getChunkManager().isChunkLoaded(spawnerPos.getX() >> 4, spawnerPos.getZ() >> 4)) continue;

                for (int x = -5; x <= 5; x++) {
                    for (int y = -5; y <= 5; y++) {
                        for (int z = -5; z <= 5; z++) {
                            BlockPos pos = spawnerPos.add(x, y, z);
                            BlockState state = mc.world.getBlockState(pos);
                            Block b = state.getBlock();
                            if (b == Blocks.TORCH || b == Blocks.WALL_TORCH
                                    || b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH) {
                                spawnerTorches.add(pos);
                            }
                        }
                    }
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
        int dx = cx - centerChunkX;
        int dz = cz - centerChunkZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp)) return false;

        if (mc.world.getChunkManager().isChunkLoaded(cx, cz)) {
            scanChunk(mc.world.getChunk(cx, cz));
            scannedChunks.add(cp);
            return true;
        }
        return false;
    }

    private void scanChunk(WorldChunk chunk) {
        if (mc.world == null) return;
        boolean doCustomBlocks = scanCustomBlocks.get() && !filterBlocks.get().isEmpty()
            && mc.world.getRegistryKey().getValue().toString().equals("minecraft:overworld");
        boolean doMisrotated = trackMisrotatedDeepslate.get();
        if (!doCustomBlocks && !doMisrotated) return;

        int minY = minYSetting.get();
        int maxY = maxYSetting.get();
        List<Block> filter = doCustomBlocks ? filterBlocks.get() : List.of();

        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionY    = chunk.getBottomSectionCoord() + i;
            int sectionMinY = sectionY * 16;
            int sectionMaxY = sectionMinY + 16;

            if (sectionMaxY < minY || sectionMinY > maxY) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int worldY = sectionMinY + y;
                        if (worldY < minY || worldY > maxY) continue;

                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();
                        BlockPos blockPos = new BlockPos((chunk.getPos().x << 4) + x, worldY, (chunk.getPos().z << 4) + z);

                        if (doCustomBlocks && filter.contains(block)) {
                            targets.put(blockPos, TargetType.CUSTOM_BLOCK);
                        }

                        if (doMisrotated && block == Blocks.DEEPSLATE
                                && state.contains(Properties.AXIS)
                                && state.get(Properties.AXIS) != Axis.Y) {
                            targets.put(blockPos, TargetType.MISROTATED_DEEPSLATE);
                        }
                    }
                }
            }
        }
    }

    /**
     * FIX #1: scanBlockEntities now uses a consistent chunk-radius loop that
     * matches the circular scan shape used elsewhere. The isWithinRange check is
     * kept for a final block-level clamp.
     */
    private void scanBlockEntities(int centerChunkX, int centerChunkZ) {
        int r = range.get();
        int rSq = r * r;

        for (int cx = centerChunkX - r; cx <= centerChunkX + r; cx++) {
            for (int cz = centerChunkZ - r; cz <= centerChunkZ + r; cz++) {
                int dx = cx - centerChunkX;
                int dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > rSq) continue;

                WorldChunk chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getPos();
                    if (!isWithinRange(pos)) continue;

                    if ((trackSpawners.get() || autoBreakSpawners.get()) && be instanceof MobSpawnerBlockEntity) {
                        targets.put(pos, TargetType.SPAWNER);
                    } else if (trackChests.get()) {
                        Block block = mc.world.getBlockState(pos).getBlock();
                        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
                            targets.put(pos, TargetType.CHEST);
                        }
                    }
                }
            }
        }
    }

    private boolean isWithinRange(BlockPos pos) {
        int rangeBlocks = range.get() * 16;
        BlockPos playerPos = mc.player.getBlockPos();
        int dx = Math.abs(pos.getX() - playerPos.getX());
        int dz = Math.abs(pos.getZ() - playerPos.getZ());
        return dx <= rangeBlocks && dz <= rangeBlocks;
    }

    private void removeNeighborFromChecked(BlockPos pos) {
        if (pos == null || mc.world == null) return;
        Block block = mc.world.getBlockState(pos).getBlock();
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos neighbor = pos.offset(dir);
                if (mc.world.getBlockState(neighbor).getBlock() == block) {
                    checkedContainers.remove(neighbor);
                    break;
                }
            }
        }
    }

    private void scanChestMinecarts(int centerChunkX, int centerChunkZ) {
        if (!trackChestMinecarts.get()) return;

        int blockRange = range.get() * 16;
        Box searchBox = new Box(
            (centerChunkX << 4) - blockRange, mc.player.getY() - 64, (centerChunkZ << 4) - blockRange,
            (centerChunkX << 4) + 16 + blockRange, mc.player.getY() + 64, (centerChunkZ << 4) + 16 + blockRange
        );

        Map<BlockPos, Integer> minecartCountMap = new HashMap<>();
        for (ChestMinecartEntity minecart : mc.world.getEntitiesByClass(ChestMinecartEntity.class, searchBox, entity -> true)) {
            BlockPos pos = minecart.getBlockPos();
            minecartCountMap.put(pos, minecartCountMap.getOrDefault(pos, 0) + 1);
            targets.put(pos, TargetType.CHEST_MINECART);
        }

        stackedMinecartCounts.clear();
        stackedMinecartCounts.putAll(minecartCountMap);

        targets.entrySet().removeIf(entry -> {
            if (entry.getValue() == TargetType.CHEST_MINECART) {
                BlockPos pos = entry.getKey();
                if (!minecartCountMap.containsKey(pos)) {
                    stackedMinecartCounts.remove(pos);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * FIX #8: Remove stale checkedEntityIds for minecarts that are no longer
     * present in the loaded world, preventing ID-reuse skips.
     */
    private void pruneCheckedEntityIds() {
        if (checkedEntityIds.isEmpty()) return;

        int blockRange = range.get() * 16;
        Box searchBox = new Box(mc.player.getBlockPos()).expand(blockRange);

        Set<Integer> liveIds = new HashSet<>();
        for (ChestMinecartEntity e : mc.world.getEntitiesByClass(ChestMinecartEntity.class, searchBox, Entity::isAlive)) {
            liveIds.add(e.getId());
        }
        checkedEntityIds.retainAll(liveIds);
    }

    private void cleanupDistantTargets(BlockPos playerPos) {
        if (mc.player == null) return;

        int blockRange   = range.get() * 16;
        int cleanupRange = blockRange + 32;
        int cleanupRangeSq = cleanupRange * cleanupRange;

        targets.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            double dx = pos.getX() - playerPos.getX();
            double dz = pos.getZ() - playerPos.getZ();
            boolean tooFar = (dx * dx + dz * dz) > cleanupRangeSq;

            if (!tooFar) return false;

            // FIX #2: Only keep spawners that are in an unloaded chunk.
            // If the chunk is loaded and the block is gone, we should remove it.
            if (entry.getValue() == TargetType.SPAWNER) {
                boolean chunkLoaded = mc.world.getChunkManager()
                    .isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunkLoaded) {
                    // Chunk is loaded — if the spawner is gone, let it be removed
                    return mc.world.getBlockState(pos).getBlock() != Blocks.SPAWNER;
                }
                // Chunk unloaded — keep the spawner entry so we can detect breaks on return
                return false;
            }

            if (entry.getValue() == TargetType.CHEST_MINECART) {
                stackedMinecartCounts.remove(pos);
            }
            return true;
        });
    }

    // ─────────────────────────── Rendering ───────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        ShapeMode mode = shapeMode.get();
        Set<BlockPos> toRemove = new HashSet<>();

        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos  = entry.getKey();
            TargetType type = entry.getValue();

            Box renderBox;
            SettingColor color;

            if (type == TargetType.CHEST_MINECART) {
                List<ChestMinecartEntity> minecarts = mc.world.getEntitiesByClass(
                    ChestMinecartEntity.class, new Box(pos), entity -> true);

                if (minecarts.isEmpty()) {
                    toRemove.add(pos);
                    continue;
                }

                renderBox = minecarts.get(0).getBoundingBox();
                boolean isStacked = highlightStacked.get() && stackedMinecartCounts.getOrDefault(pos, 0) >= 2;
                color = isStacked ? stackedMinecartColor.get() : chestMinecartColor.get();

            } else {
                if (!mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

                if (mc.world.getBlockState(pos).isAir()) {
                    toRemove.add(pos);
                    continue;
                }

                Block currentBlock = mc.world.getBlockState(pos).getBlock();
                if (type == TargetType.SPAWNER || type == TargetType.CHEST || type == TargetType.MISROTATED_DEEPSLATE) {
                    if (!validateBlockType(currentBlock, type)) {
                        toRemove.add(pos);
                        continue;
                    }
                }

                renderBox = createPaddedBox(pos);
                color = getColor(type);
            }

            if (color != null) {
                event.renderer.box(renderBox, color, color, mode, 0);
            }
        }

        if (!toRemove.isEmpty()) {
            for (BlockPos pos : toRemove) {
                if (targets.get(pos) == TargetType.SPAWNER) {
                    if (notifySpawnerBreak.get()) {
                        info("Spawner broken at %d, %d, %d", pos.getX(), pos.getY(), pos.getZ());
                    }
                    if (showBrokenSpawnerBeam.get()) {
                        brokenSpawners.put(pos, System.currentTimeMillis() + (brokenSpawnerDuration.get() * 1000L));
                    }
                }
                targets.remove(pos);
            }
        }

        // Render broken spawner beams
        if (!brokenSpawners.isEmpty()) {
            long now = System.currentTimeMillis();
            brokenSpawners.entrySet().removeIf(entry -> now > entry.getValue());

            SettingColor color = brokenSpawnerColor.get();
            int worldBottom = mc.world.getBottomY();
            int worldTop    = worldBottom + mc.world.getHeight();

            for (BlockPos pos : brokenSpawners.keySet()) {
                // FIX #6: Beam extends both downward (to world bottom) and upward (to world top)
                // so it remains visible regardless of whether the spawner was above or below the player.
                Box beam = new Box(
                    pos.getX() + 0.4, worldBottom, pos.getZ() + 0.4,
                    pos.getX() + 0.6, worldTop,    pos.getZ() + 0.6
                );
                event.renderer.box(beam, color, color, ShapeMode.Both, 0);
            }
        }

        // Render Endermites
        if (trackEndermites.get() && !endermiteTargets.isEmpty()) {
            SettingColor color = endermiteColor.get();

            for (EndermiteEntity endermite : endermiteTargets) {
                if (!endermite.isAlive()) continue;

                event.renderer.box(endermite.getBoundingBox(), color, color, mode, 0);

                if (endermiteBeam.get()) {
                    double beamSize = endermiteBeamWidth.get() / 100.0;
                    Vec3d epos = endermite.getPos();
                    Box beamBox = new Box(
                        epos.x - beamSize, epos.y, epos.z - beamSize,
                        epos.x + beamSize, mc.world.getHeight(), epos.z + beamSize
                    );
                    event.renderer.box(beamBox, color, color, ShapeMode.Both, 0);
                }
            }
        }

        if (!spawnerTorches.isEmpty()) {
            SettingColor color = spawnerTorchColor.get();
            for (BlockPos pos : spawnerTorches) {
                event.renderer.box(pos, color, color, mode, 0);
            }
        }
    }

    // ─────────────────────────── Utilities ───────────────────────────

    /** FIX: Centralised helper — reset all auto-open flags in one place. */
    private void resetAutoOpenState() {
        wasAutoOpened = false;
        silentOpenPending = false;
        silentFoundWhitelisted = false;
        pendingBreakCheck = false;
        silentSlotReadRetryTimer = 0;
        interactTimeoutTimer = 0;
        lastOpenedContainer = null;
        lastOpenedEntity = null;
        hasPlayedSoundForCurrentScreen = false;
        isBreaking = false;
        isBreakingEntity = false;
        blockToBreak = null;
        isBreakingChest = false;
        entityToBreak = null;
        breakDelayTimer = 0;
        previousSlot = -1;
    }

    private void restoreSlot() {
        if (silentSwitch.get() && previousSlot >= 0) {
            mc.player.getInventory().selectedSlot = previousSlot;
            previousSlot = -1;
        }
    }

    private Box createPaddedBox(BlockPos pos) {
        return new Box(pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
    }

    private boolean validateBlockType(Block block, TargetType type) {
        return switch (type) {
            case SPAWNER             -> block == Blocks.SPAWNER;
            case CHEST               -> block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
            case CHEST_MINECART      -> true;
            case CUSTOM_BLOCK        -> filterBlocks.get().contains(block);
            case MISROTATED_DEEPSLATE -> block == Blocks.DEEPSLATE;
        };
    }

    private SettingColor getColor(TargetType type) {
        return switch (type) {
            case SPAWNER             -> trackSpawners.get() ? spawnerColor.get() : null;
            case CHEST               -> chestColor.get();
            case CHEST_MINECART      -> chestMinecartColor.get();
            case CUSTOM_BLOCK        -> customBlockColor.get();
            case MISROTATED_DEEPSLATE -> misrotatedDeepslateColor.get();
        };
    }

    public int getTotalTargets()                { return targets.size(); }
    public int getBrokenChestsCount()           { return brokenChestsCount; }

    public Map<TargetType, Integer> getTargetCounts() {
        Map<TargetType, Integer> counts = new HashMap<>();
        for (TargetType type : TargetType.values()) counts.put(type, 0);
        for (TargetType type : targets.values())    counts.put(type, counts.get(type) + 1);
        return counts;
    }

    private int findAxe() {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() instanceof AxeItem) return i;
        return -1;
    }

    private int findPickaxe() {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() instanceof PickaxeItem) return i;
        return -1;
    }

    private int findSword() {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() instanceof SwordItem) return i;
        return -1;
    }

    /**
     * FIX #5: Returns exactly 1 when baseDelay is 0 so the caller's break-delay
     * path fires on the very next tick, giving effectively-instant behaviour while
     * still going through the normal countdown code path.
     * The ±40 % jitter only applies when baseDelay > 0.
     */
    private int getRandomizedDelay(int baseDelay) {
        if (baseDelay <= 0) return 1;
        return (int) Math.max(1, Math.round(baseDelay * (1.0 + (Math.random() - 0.5) * 0.8)));
    }

    // ─────────────────────────── Target Types ───────────────────────────

    public enum TargetType {
        SPAWNER,
        CHEST,
        CHEST_MINECART,
        CUSTOM_BLOCK,
        // NOTE: ENDERMITE is intentionally absent — endermites are tracked as
        // live entities in endermiteTargets, not as block positions in targets.
        MISROTATED_DEEPSLATE
    }
}