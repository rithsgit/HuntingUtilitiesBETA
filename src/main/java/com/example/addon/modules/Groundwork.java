package com.example.addon.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class Groundwork extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScaffold = settings.createGroup("Scaffold");
    private final SettingGroup sgSurround = settings.createGroup("Surround");
    private final SettingGroup sgRender = settings.createGroup("Render");

    public enum MainMode {
        Bridge,
        DoubleBridge,
        Scaffold,
        Surround
    }

    public enum ListMode {
        Whitelist,
        Blacklist
    }

    public enum ScaffoldMode {
        Disabled,
        Single,
        Hold,
        Toggle
    }

    public enum SurroundTarget {
        Self,
        Others
    }

    // --- General Settings ---
    private final Setting<MainMode> mainMode = sgGeneral.add(new EnumSetting.Builder<MainMode>()
        .name("mode")
        .description("Bridge = standard 1-wide groundwork. DoubleBridge = 2-wide groundwork. Scaffold = mid-air elytra placement. Surround = box in targets.")
        .defaultValue(MainMode.Bridge)
        .build()
    );

    private final Setting<List<Block>> blockList = sgGeneral.add(new BlockListSetting.Builder()
        .name("block-list")
        .description("Which blocks are allowed to be used for groundwork.")
        .build()
    );

    private final Setting<ListMode> listMode = sgGeneral.add(new EnumSetting.Builder<ListMode>()
        .name("list-mode")
        .description("Whether to treat the block list as a whitelist or blacklist.")
        .defaultValue(ListMode.Whitelist)
        .build()
    );

    private final Setting<Boolean> randomizeBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("randomize-blocks")
        .description("Randomly picks a block from the available valid blocks in your hotbar when placing.")
        .defaultValue(false)
        .visible(() -> blockList.get() != null && blockList.get().size() > 1)
        .build()
    );

    private final Setting<Boolean> autoReplenish = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-replenish")
        .description("Automatically moves whitelisted blocks from your inventory to your hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> replenishThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("replenish-threshold")
        .description("The stack count at which to trigger auto-replenish.")
        .defaultValue(16)
        .min(1)
        .sliderMax(63)
        .visible(autoReplenish::get)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Maximum horizontal distance from the player to place blocks.")
        .defaultValue(2.0)
        .min(1.0)
        .sliderMax(5.0)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Tick delay between block placements.")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .visible(this::isBridgeMode)
        .build()
    );

    private final Setting<Boolean> fastMode = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-mode")
        .description("Drops delay to 1 tick when nearing an edge to prevent falling.")
        .defaultValue(true)
        .visible(this::isBridgeMode)
        .build()
    );

    private final Setting<Boolean> pauseOnShift = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-shift")
        .description("Pauses block placement while holding shift (sneak).")
        .defaultValue(true)
        .visible(this::isBridgeMode)
        .build()
    );

    // --- Scaffold Settings ---
    private final Setting<ScaffoldMode> scaffoldMode = sgScaffold.add(new EnumSetting.Builder<ScaffoldMode>()
        .name("scaffold-mode")
        .description("Single = one block per key press. Hold = continuous while key is held. Toggle = continuous until toggled off.")
        .defaultValue(ScaffoldMode.Hold)
        .visible(() -> mainMode.get() == MainMode.Scaffold)
        .build()
    );

    private final Setting<Keybind> scaffoldKey = sgScaffold.add(new KeybindSetting.Builder()
        .name("scaffold-key")
        .description("Key to activate scaffold placement.")
        .defaultValue(Keybind.none())
        .visible(() -> mainMode.get() == MainMode.Scaffold && scaffoldMode.get() != ScaffoldMode.Disabled)
        .action(() -> {
            if (mc.currentScreen != null) return;
            if (mc.player == null || mc.world == null) return;

            if (scaffoldMode.get() == ScaffoldMode.Single) {
                queuedSinglePlace = true;
            } else if (scaffoldMode.get() == ScaffoldMode.Toggle) {
                queuedToggle = true;
            }
        })
        .build()
    );

    private final Setting<Boolean> requireFlying = sgScaffold.add(new BoolSetting.Builder()
        .name("require-flying")
        .description("Only place blocks while gliding with elytra.")
        .defaultValue(true)
        .visible(() -> mainMode.get() == MainMode.Scaffold && scaffoldMode.get() != ScaffoldMode.Disabled)
        .build()
    );

    private final Setting<Boolean> airPlace = sgScaffold.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Lets you place blocks in mid-air without needing a solid block to click against.")
        .defaultValue(true)
        .visible(() -> mainMode.get() == MainMode.Scaffold && scaffoldMode.get() != ScaffoldMode.Disabled)
        .build()
    );

    private final Setting<Boolean> buildPlatform = sgScaffold.add(new BoolSetting.Builder()
        .name("build-platform")
        .description("Builds a 5x5 platform around the mid-air placed block after a short delay. Ignored if in a hole.")
        .defaultValue(false)
        .visible(() -> mainMode.get() == MainMode.Scaffold && scaffoldMode.get() != ScaffoldMode.Disabled && airPlace.get())
        .build()
    );

    private final Setting<Integer> platformWaitTime = sgScaffold.add(new IntSetting.Builder()
        .name("platform-wait-time")
        .description("Seconds to wait before automatically building the 5x5 platform.")
        .defaultValue(3)
        .min(0)
        .sliderMax(10)
        .visible(() -> mainMode.get() == MainMode.Scaffold && scaffoldMode.get() != ScaffoldMode.Disabled && airPlace.get() && buildPlatform.get())
        .build()
    );

    private final Setting<Boolean> scaffoldSilent = sgScaffold.add(new BoolSetting.Builder()
        .name("silent-swap")
        .description("Silently swap to the block without changing your visible hotbar slot.")
        .defaultValue(true)
        .visible(() -> mainMode.get() == MainMode.Scaffold && scaffoldMode.get() != ScaffoldMode.Disabled)
        .build()
    );

    private final Setting<Boolean> scaffoldSwing = sgScaffold.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing hand when placing a block.")
        .defaultValue(false)
        .visible(() -> mainMode.get() == MainMode.Scaffold && scaffoldMode.get() != ScaffoldMode.Disabled)
        .build()
    );

    private final Setting<Integer> scaffoldDelay = sgScaffold.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between placements in Hold / Toggle mode.")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .visible(() -> mainMode.get() == MainMode.Scaffold && (scaffoldMode.get() == ScaffoldMode.Hold || scaffoldMode.get() == ScaffoldMode.Toggle))
        .build()
    );

    private final Setting<Integer> scaffoldLookAhead = sgScaffold.add(new IntSetting.Builder()
        .name("look-ahead")
        .description("How many blocks ahead of your position to place (based on horizontal facing). 0 = directly below.")
        .defaultValue(0)
        .min(0)
        .sliderMax(5)
        .visible(() -> mainMode.get() == MainMode.Scaffold && scaffoldMode.get() != ScaffoldMode.Disabled)
        .build()
    );

    private final Setting<Double> scaffoldYOffset = sgScaffold.add(new DoubleSetting.Builder()
        .name("y-offset")
        .description("Vertical offset (in blocks) below the player to place at. 1 = directly under feet.")
        .defaultValue(1.0)
        .min(0.0)
        .sliderMax(5.0)
        .visible(() -> mainMode.get() == MainMode.Scaffold && scaffoldMode.get() != ScaffoldMode.Disabled)
        .build()
    );

    // --- Surround Settings ---
    private final Setting<SurroundTarget> surroundTarget = sgSurround.add(new EnumSetting.Builder<SurroundTarget>()
        .name("target")
        .description("Who to surround.")
        .defaultValue(SurroundTarget.Self)
        .visible(() -> mainMode.get() == MainMode.Surround)
        .build()
    );

    private final Setting<Boolean> openTop = sgSurround.add(new BoolSetting.Builder()
        .name("open-top")
        .description("Leaves the block above the target open. Useful for placing an ender chest.")
        .defaultValue(true)
        .visible(() -> mainMode.get() == MainMode.Surround)
        .build()
    );

    private final Setting<Boolean> placeBottom = sgSurround.add(new BoolSetting.Builder()
        .name("place-bottom")
        .description("Also places a block under the target.")
        .defaultValue(false)
        .visible(() -> mainMode.get() == MainMode.Surround)
        .build()
    );

    private final Setting<Integer> surroundDelay = sgSurround.add(new IntSetting.Builder()
        .name("delay")
        .description("Tick delay between placements when surrounding.")
        .defaultValue(2)
        .min(0)
        .sliderMax(10)
        .visible(() -> mainMode.get() == MainMode.Surround)
        .build()
    );

    // --- Render Settings ---
    private final Setting<Boolean> renderPlacements = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights blocks that are queued or recently placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the core target box is drawn.")
        .defaultValue(ShapeMode.Both)
        .visible(renderPlacements::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The fill color for rendered blocks.")
        .defaultValue(new SettingColor(255, 255, 255, 32))
        .visible(renderPlacements::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The outline color for rendered blocks.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(renderPlacements::get)
        .build()
    );

    private final Setting<Integer> glowLayers = sgRender.add(new IntSetting.Builder()
        .name("glow-layers")
        .description("Number of bloom layers rendered around each placement.")
        .defaultValue(4)
        .min(1)
        .sliderMax(8)
        .visible(renderPlacements::get)
        .build()
    );

    private final Setting<Double> glowSpread = sgRender.add(new DoubleSetting.Builder()
        .name("glow-spread")
        .description("How far each bloom layer expands outward (in blocks).")
        .defaultValue(0.04)
        .min(0.01)
        .sliderMax(0.15)
        .visible(renderPlacements::get)
        .build()
    );

    private final Setting<Integer> glowBaseAlpha = sgRender.add(new IntSetting.Builder()
        .name("glow-base-alpha")
        .description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(60)
        .min(10)
        .sliderMax(150)
        .visible(renderPlacements::get)
        .build()
    );

    // Constants
    private static final double EDGE_THRESHOLD = 0.85;
    private static final double STEP_DISTANCE = 0.25;
    private static final double EXTEND_DISTANCE = 1.0;
    private static final int MARK_LIFETIME = 10;

    // Runtime State - Groundwork
    private final Deque<BlockPos> placementQueue = new ArrayDeque<>();
    private final Map<BlockPos, Integer> recentPlacements = new HashMap<>();
    private final BlockPos.Mutable checkingPos = new BlockPos.Mutable();

    private int tickCounter = 0;
    private int currentTimer = 0;
    private int groundworkLayer = 0;

    // Runtime State - Scaffold
    private boolean scaffoldToggled = false;
    private boolean queuedSinglePlace = false;
    private boolean queuedToggle = false;
    private int scaffoldCooldown = 0;

    // Runtime State - Platform Builder
    private boolean isPlatformBuilding = false;
    private int platformWaitTimer = 0;
    private int platformPlaceDelay = 0;
    private final List<BlockPos> platformQueue = new ArrayList<>();

    // Runtime State - Surround
    private int surroundCooldown = 0;

    // Runtime State - Replenish
    private boolean notifiedOutOfBlocks = false;
    private Block lastPlacedBlock = null;
    private int lastPlacedHotbarSlot = -1;

    public Groundwork() {
        super(Tim.CATEGORY, "groundwork", "Dynamically places blocks under your feet to bridge gaps, scaffold, and surround.");
    }

    private boolean isBridgeMode() {
        return mainMode.get() == MainMode.Bridge || mainMode.get() == MainMode.DoubleBridge;
    }

    @Override
    public void onActivate() {
        resetState();
        resetScaffoldState();
        currentTimer = placeDelay.get();
        groundworkLayer = (mc.player == null) ? 0 : getPlayerLayerY();
    }

    @Override
    public void onDeactivate() {
        resetState();
        resetScaffoldState();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return; // Pause logic if a screen/inventory is open

        // 1. Always handle auto-replenish first to ensure hotbar is ready
        handleAutoReplenish();

        // 2. Handle Surround Logic
        if (mainMode.get() == MainMode.Surround) {
            handleSurround();
            return;
        }

        // 3. Handle Scaffold Logic (Includes platform builder if enabled)
        if (mainMode.get() == MainMode.Scaffold) {
            handlePlatformBuilding();
            if (isPlatformBuilding) {
                placementQueue.clear();
                return;
            }

            boolean scaffoldPlaced = handleScaffold();
            if (scaffoldPlaced || isScaffoldActive()) {
                placementQueue.clear();
                return;
            }
            return;
        }

        // 4. Handle Bridge / DoubleBridge Logic
        if (pauseOnShift.get() && mc.player.isSneaking()) {
            placementQueue.clear();
            return;
        }

        tickCounter++;

        // Update layer if player moves up/down
        int playerLayer = getPlayerLayerY();
        if (groundworkLayer != playerLayer) {
            groundworkLayer = playerLayer;
            placementQueue.clear();
        }

        // Clean up old marks and invalid queue entries
        cleanState();

        // Add new blocks to queue based on movement
        collectBlocks();

        // Wait for timer
        if (++currentTimer < getDynamicDelay()) return;

        BlockPos target = getNextValidBlock();
        if (target == null) return;

        int slot = findValidSlot(target);
        if (slot == -1) {
            // Re-queue if no block found yet
            placementQueue.addFirst(target);
            return;
        }

        if (placeBlock(target, slot)) {
            currentTimer = 0;
            recentPlacements.put(target, tickCounter);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderPlacements.get() || mc.world == null) return;

        for (BlockPos pos : placementQueue) {
            renderGlowBox(event, pos);
        }

        for (BlockPos pos : recentPlacements.keySet()) {
            if (!placementQueue.contains(pos)) {
                renderGlowBox(event, pos);
            }
        }
    }

    // --- Surround Logic Methods ---

    private void handleSurround() {
        if (surroundCooldown > 0) {
            surroundCooldown--;
            return;
        }

        Entity target = mc.player;
        if (surroundTarget.get() == SurroundTarget.Others) {
            target = mc.world.getPlayers().stream()
                .filter(p -> p != mc.player && p.isAlive() && p.distanceTo(mc.player) <= 5.0)
                .min(Comparator.comparingDouble(p -> p.distanceTo(mc.player)))
                .orElse(null);

            if (target == null) return;
        }

        BlockPos basePos = target.getBlockPos();
        List<BlockPos> positions = new ArrayList<>();

        positions.add(basePos.north());
        positions.add(basePos.south());
        positions.add(basePos.east());
        positions.add(basePos.west());

        if (!openTop.get()) {
            positions.add(basePos.up());
        }
        if (placeBottom.get()) {
            positions.add(basePos.down());
        }

        for (BlockPos pos : positions) {
            if (mc.world.getBlockState(pos).isReplaceable()) {
                int slot = findValidSlot(pos);
                if (slot != -1) {
                    if (placeBlock(pos, slot)) {
                        recentPlacements.put(pos.toImmutable(), tickCounter);
                        surroundCooldown = surroundDelay.get();
                    }
                    return; // Place one block per cycle
                }
            }
        }
    }

    // --- Groundwork Logic Methods ---

    private void resetState() {
        placementQueue.clear();
        recentPlacements.clear();
        currentTimer = 0;
        tickCounter = 0;
        notifiedOutOfBlocks = false;
        lastPlacedBlock = null;
        lastPlacedHotbarSlot = -1;
    }

    private void collectBlocks() {
        if (mainMode.get() == MainMode.DoubleBridge) {
            // Determine perpendicular axis based on facing
            Direction facing = mc.player.getHorizontalFacing();
            Vec3d perp = (facing == Direction.NORTH || facing == Direction.SOUTH) ? new Vec3d(1, 0, 0) : new Vec3d(0, 0, 1);

            // Directly below player (both sides)
            addBlockToQueue(BlockPos.ofFloored(mc.player.getX() + perp.x * 0.5, groundworkLayer, mc.player.getZ() + perp.z * 0.5), true);
            addBlockToQueue(BlockPos.ofFloored(mc.player.getX() - perp.x * 0.5, groundworkLayer, mc.player.getZ() - perp.z * 0.5), true);

            Vec3d movement = getMovementVector();
            if (movement.lengthSquared() == 0) return;

            // Ahead of player based on movement (both sides)
            for (double i = STEP_DISTANCE; i <= EXTEND_DISTANCE + 0.001; i += STEP_DISTANCE) {
                double targetX = mc.player.getX() + movement.x * i;
                double targetZ = mc.player.getZ() + movement.z * i;
                addBlockToQueue(BlockPos.ofFloored(targetX + perp.x * 0.5, groundworkLayer, targetZ + perp.z * 0.5), false);
                addBlockToQueue(BlockPos.ofFloored(targetX - perp.x * 0.5, groundworkLayer, targetZ - perp.z * 0.5), false);
            }
        } else {
            // Standard 1-wide Bridge
            addBlockToQueue(BlockPos.ofFloored(mc.player.getX(), groundworkLayer, mc.player.getZ()), true);

            Vec3d movement = getMovementVector();
            if (movement.lengthSquared() == 0) return;

            for (double i = STEP_DISTANCE; i <= EXTEND_DISTANCE + 0.001; i += STEP_DISTANCE) {
                double targetX = mc.player.getX() + movement.x * i;
                double targetZ = mc.player.getZ() + movement.z * i;
                addBlockToQueue(BlockPos.ofFloored(targetX, groundworkLayer, targetZ), false);
            }
        }
    }

    private void addBlockToQueue(BlockPos pos, boolean priority) {
        BlockPos immutable = pos.toImmutable();

        if (!isBlockOpen(immutable) || placementQueue.contains(immutable) || recentPlacements.containsKey(immutable)) {
            return;
        }

        if (priority) {
            placementQueue.addFirst(immutable);
        } else {
            placementQueue.addLast(immutable);
        }
    }

    private BlockPos getNextValidBlock() {
        while (!placementQueue.isEmpty()) {
            BlockPos pos = placementQueue.pollFirst();
            if (isBlockValid(pos)) return pos;
        }
        return null;
    }

    private void cleanState() {
        placementQueue.removeIf(pos -> !isBlockValid(pos));

        recentPlacements.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            return !isBlockOpen(pos) || isOutOfReach(pos) || (tickCounter - entry.getValue() > MARK_LIFETIME);
        });
    }

    private boolean placeBlock(BlockPos pos, int slot) {
        ItemStack stack = mc.player.getInventory().getStack(slot);
        if (!(stack.getItem() instanceof BlockItem item)) return false;

        // Track before placing to prevent replenish losing context when stack hits 0
        lastPlacedBlock = item.getBlock();
        lastPlacedHotbarSlot = slot;

        if (!InvUtils.swap(slot, true)) return false;

        try {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, getHitResult(pos));
            mc.player.swingHand(Hand.MAIN_HAND);
            playBlockSound(item, pos);
            return true;
        } finally {
            InvUtils.swapBack();
        }
    }

    private BlockHitResult getHitResult(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            BlockState state = mc.world.getBlockState(neighbor);

            if (state.isReplaceable() || !state.getFluidState().isEmpty()) continue;

            Direction face = dir.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(neighbor).add(
                face.getOffsetX() * 0.5,
                face.getOffsetY() * 0.5,
                face.getOffsetZ() * 0.5
            );

            return new BlockHitResult(hitVec, face, neighbor, false);
        }

        // Fallback (though usually invalid for legit placement)
        return new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
    }

    /**
     * Collects every hotbar slot that holds an allowed, placeable, full-cube block.
     * If {@code randomizeBlocks} is enabled and more than one candidate exists,
     * a random slot is returned; otherwise the first match is returned.
     */
    private int findValidSlot(BlockPos pos) {
        List<Integer> validSlots = new ArrayList<>();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof BlockItem item)) continue;

            Block block = item.getBlock();
            if (!isBlockAllowed(block) || block instanceof FallingBlock) continue;

            if (Block.isShapeFullCube(block.getDefaultState().getCollisionShape(mc.world, pos))) {
                validSlots.add(i);
            }
        }

        return pickSlot(validSlots);
    }

    // --- Scaffold Logic Methods ---

    private void resetScaffoldState() {
        scaffoldToggled = false;
        queuedSinglePlace = false;
        queuedToggle = false;
        scaffoldCooldown = 0;

        isPlatformBuilding = false;
        platformWaitTimer = 0;
        platformPlaceDelay = 0;
        platformQueue.clear();
    }

    private boolean isScaffoldActive() {
        if (mainMode.get() != MainMode.Scaffold || scaffoldMode.get() == ScaffoldMode.Disabled) return false;
        if (scaffoldMode.get() == ScaffoldMode.Toggle) return scaffoldToggled;
        if (scaffoldMode.get() == ScaffoldMode.Hold) {
            Keybind key = scaffoldKey.get();
            return key != null && key.isPressed();
        }
        return false;
    }

    private boolean handleScaffold() {
        if (mainMode.get() != MainMode.Scaffold || scaffoldMode.get() == ScaffoldMode.Disabled) return false;
        if (mc.currentScreen != null) return false;

        if (scaffoldCooldown > 0) scaffoldCooldown--;

        if (queuedToggle) {
            scaffoldToggled = !scaffoldToggled;
            info("Scaffold " + (scaffoldToggled ? "enabled" : "disabled") + ".");
            queuedToggle = false;
        }

        if (queuedSinglePlace) {
            boolean placed = attemptScaffoldPlace();
            queuedSinglePlace = false;
            return placed;
        }

        Keybind key = scaffoldKey.get();
        boolean isPressed = key != null && key.isPressed();

        if (scaffoldMode.get() == ScaffoldMode.Hold) {
            if (isPressed && scaffoldCooldown == 0) {
                if (attemptScaffoldPlace()) {
                    scaffoldCooldown = scaffoldDelay.get();
                    return true;
                }
            }
        } else if (scaffoldMode.get() == ScaffoldMode.Toggle) {
            if (scaffoldToggled && scaffoldCooldown == 0) {
                if (attemptScaffoldPlace()) {
                    scaffoldCooldown = scaffoldDelay.get();
                    return true;
                }
            }
        }
        return false;
    }

    private boolean attemptScaffoldPlace() {
        if (mc.player == null || mc.world == null) return false;
        if (requireFlying.get() && !mc.player.isGliding()) {
            info("Cannot place: You are not gliding.");
            return false;
        }

        int blockSlot = findScaffoldBlockSlot();
        if (blockSlot == -1) {
            info("Cannot place: No blocks found in hotbar.");
            return false;
        }

        BlockPos basePos = mc.player.getBlockPos();
        int yOffsetBlocks = (int) Math.floor(scaffoldYOffset.get());
        BlockPos targetPos = basePos.down(yOffsetBlocks > 0 ? yOffsetBlocks : 1);

        int lookAhead = scaffoldLookAhead.get();
        if (lookAhead > 0) {
            Direction facing = mc.player.getHorizontalFacing();
            targetPos = targetPos.offset(facing, lookAhead);
        }

        BlockState state = mc.world.getBlockState(targetPos);
        if (!state.isReplaceable()) {
            info("Cannot place: Target space is already occupied.");
            return false;
        }

        boolean usedAirPlace = airPlace.get();
        boolean placedSuccessfully = placeScaffoldBlock(targetPos, usedAirPlace);

        if (placedSuccessfully && usedAirPlace && buildPlatform.get() && isInOpenAir()) {
            platformQueue.clear();
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos p = targetPos.add(x, 0, z);
                    if (mc.world.getBlockState(p).isReplaceable()) {
                        platformQueue.add(p);
                    }
                }
            }

            platformWaitTimer = platformWaitTime.get() * 20;
            isPlatformBuilding = false;
        }

        return placedSuccessfully;
    }

    private boolean isInOpenAir() {
        BlockPos pos = mc.player.getBlockPos();
        return mc.world.getBlockState(pos.north()).isAir() &&
               mc.world.getBlockState(pos.south()).isAir() &&
               mc.world.getBlockState(pos.east()).isAir() &&
               mc.world.getBlockState(pos.west()).isAir() &&
               mc.world.getBlockState(pos.up().north()).isAir() &&
               mc.world.getBlockState(pos.up().south()).isAir() &&
               mc.world.getBlockState(pos.up().east()).isAir() &&
               mc.world.getBlockState(pos.up().west()).isAir();
    }

    private void handlePlatformBuilding() {
        if (platformWaitTimer > 0) {
            platformWaitTimer--;
            if (platformWaitTimer == 0) {
                isPlatformBuilding = true;
                platformPlaceDelay = 0;
                info("Building 5x5 platform...");
            }
            return;
        }

        if (isPlatformBuilding) {
            if (platformPlaceDelay > 0) {
                platformPlaceDelay--;
                return;
            }

            if (platformQueue.isEmpty()) {
                isPlatformBuilding = false;
                return;
            }

            BlockPos nextPos = platformQueue.remove(0);
            placeScaffoldBlock(nextPos, true);

            platformPlaceDelay = 1;
        }
    }

    private boolean placeScaffoldBlock(BlockPos targetPos, boolean allowAirPlace) {
        int blockSlot = findScaffoldBlockSlot();
        if (blockSlot == -1) return false;

        BlockState state = mc.world.getBlockState(targetPos);
        if (!state.isReplaceable()) return false;

        ItemStack originalStack = mc.player.getInventory().getStack(blockSlot);
        if (originalStack.getItem() instanceof BlockItem bi) {
            // Track before placing to prevent replenish losing context when stack hits 0
            lastPlacedBlock = bi.getBlock();
            lastPlacedHotbarSlot = blockSlot;
        } else {
            return false;
        }

        Direction placeDir;
        BlockPos placeAgainst;
        Vec3d hitVec;

        if (allowAirPlace) {
            placeDir = Direction.UP;
            placeAgainst = targetPos;
            hitVec = Vec3d.ofCenter(targetPos);
        } else {
            placeDir = Direction.UP;
            placeAgainst = targetPos.up();

            if (!mc.world.getBlockState(placeAgainst).isSideSolidFullSquare(mc.world, placeAgainst, Direction.DOWN)) {
                Direction found = null;
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = targetPos.offset(dir);
                    if (mc.world.getBlockState(neighbor).isSideSolidFullSquare(mc.world, neighbor, dir.getOpposite())) {
                        found = dir;
                        placeAgainst = neighbor;
                        break;
                    }
                }
                if (found == null) {
                    return false;
                }
                placeDir = found.getOpposite();
            }
            hitVec = Vec3d.ofCenter(targetPos)
                .add(Vec3d.of(placeDir.getOpposite().getVector()).multiply(0.5));
        }

        InvUtils.swap(blockSlot, scaffoldSilent.get());

        BlockHitResult hitResult = new BlockHitResult(hitVec, placeDir, placeAgainst, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);

        if (scaffoldSwing.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        InvUtils.swapBack();
        return true;
    }

    /**
     * Collects every hotbar slot that holds an allowed, placeable block (scaffold
     * doesn't require full-cube, since e.g. slabs/stairs are fine in the air).
     * If {@code randomizeBlocks} is enabled and more than one candidate exists,
     * a random slot is returned; otherwise the first match is returned.
     */
    private int findScaffoldBlockSlot() {
        List<Integer> validSlots = new ArrayList<>();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof BlockItem item)) continue;

            Block block = item.getBlock();
            if (!isBlockAllowed(block) || block instanceof FallingBlock) continue;

            validSlots.add(i);
        }

        return pickSlot(validSlots);
    }

    /**
     * Picks a slot from the list of valid slots. Returns -1 if the list is empty.
     * If {@code randomizeBlocks} is enabled and there is more than one option,
     * picks a uniformly random slot; otherwise returns the first one.
     */
    private int pickSlot(List<Integer> validSlots) {
        if (validSlots.isEmpty()) {
            checkAndNotifyEmpty();
            return -1;
        }
        
        notifiedOutOfBlocks = false; // Reset notification flag if we have a valid block

        if (validSlots.size() == 1) return validSlots.get(0);
        if (!randomizeBlocks.get()) return validSlots.get(0);

        return validSlots.get(ThreadLocalRandom.current().nextInt(validSlots.size()));
    }

    // --- Replenish Logic Methods ---

    private void handleAutoReplenish() {
        if (!autoReplenish.get()) return;
        if (lastPlacedHotbarSlot == -1 || lastPlacedBlock == null) return;

        ItemStack hotbarStack = mc.player.getInventory().getStack(lastPlacedHotbarSlot);
        boolean needsReplenish = false;

        if (hotbarStack.isEmpty()) {
            needsReplenish = true;
        } else if (hotbarStack.getItem() instanceof BlockItem bi && bi.getBlock() == lastPlacedBlock) {
            if (hotbarStack.getCount() <= replenishThreshold.get()) {
                needsReplenish = true;
            }
        } else {
            // Player manually changed the slot, reset tracking
            lastPlacedBlock = null;
            lastPlacedHotbarSlot = -1;
            return;
        }

        if (needsReplenish) {
            for (int i = 9; i < 36; i++) {
                ItemStack invStack = mc.player.getInventory().getStack(i);
                if (invStack.getItem() instanceof BlockItem bi && bi.getBlock() == lastPlacedBlock) {
                    InvUtils.move().from(i).to(lastPlacedHotbarSlot);
                    return; // Move one stack per tick to avoid packet spam
                }
            }
        }
    }

    private void checkAndNotifyEmpty() {
        if (notifiedOutOfBlocks) return;

        boolean hasAny = false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof BlockItem item) {
                if (isBlockAllowed(item.getBlock())) {
                    hasAny = true;
                    break;
                }
            }
        }

        if (!hasAny) {
            info("Out of " + (listMode.get() == ListMode.Whitelist ? "whitelisted" : "usable") + " blocks!");
            notifiedOutOfBlocks = true;
        }
    }

    // --- Utility Methods ---

    private boolean isBlockAllowed(Block block) {
        if (listMode.get() == ListMode.Blacklist) {
            return !blockList.get().contains(block);
        }
        return blockList.get().contains(block);
    }

    private int getDynamicDelay() {
        return fastMode.get() && isNearEdge() ? 1 : placeDelay.get();
    }

    private boolean isNearEdge() {
        int pX = (int) Math.round(mc.player.getX());
        int pZ = (int) Math.round(mc.player.getZ());

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                checkingPos.set(pX + x, groundworkLayer, pZ + z);
                if (!mc.world.getBlockState(checkingPos).isAir()) continue;

                double dx = checkingPos.getX() + 0.5 - mc.player.getX();
                double dz = checkingPos.getZ() + 0.5 - mc.player.getZ();

                if (dx * dx + dz * dz < EDGE_THRESHOLD * EDGE_THRESHOLD) return true;
            }
        }
        return false;
    }

    private Vec3d getMovementVector() {
        Vec3d move = Vec3d.ZERO;
        float yaw = mc.player.getYaw();

        if (mc.options.forwardKey.isPressed()) move = move.add(Vec3d.fromPolar(0, yaw));
        if (mc.options.backKey.isPressed()) move = move.add(Vec3d.fromPolar(0, yaw + 180));
        if (mc.options.leftKey.isPressed()) move = move.add(Vec3d.fromPolar(0, yaw - 90));
        if (mc.options.rightKey.isPressed()) move = move.add(Vec3d.fromPolar(0, yaw + 90));

        return move.lengthSquared() == 0 ? Vec3d.ZERO : move.normalize();
    }

    private int getPlayerLayerY() {
        return BlockPos.ofFloored(mc.player.getX(), mc.player.getY(), mc.player.getZ()).down().getY();
    }

    private boolean isBlockOpen(BlockPos pos) {
        return pos.getY() == groundworkLayer && mc.world.getBlockState(pos).isReplaceable();
    }

    private boolean isBlockValid(BlockPos pos) {
        return isBlockOpen(pos) && !isOutOfReach(pos);
    }

    private boolean isOutOfReach(BlockPos pos) {
        // Use horizontal distance for reach. 
        // This fixes the issue where a reach of 2.0 fails to reach the block directly under the player 
        // (due to eye height making the 3D distance ~2.12).
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        return dx * dx + dz * dz > reach.get() * reach.get();
    }

    // --- Render/Sound Helpers ---

    private void renderGlowBox(Render3DEvent event, BlockPos pos) {
        Box box = new Box(pos);
        renderGlowLayers(event, box, sideColor.get());
        event.renderer.box(box, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int layers = glowLayers.get();
        double spread = glowSpread.get();
        int baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double t = (double)(i - 1) / layers;
            int layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - t * t)));

            event.renderer.box(
                box.expand(expansion),
                withAlpha(color, layerAlpha),
                withAlpha(color, 0),
                ShapeMode.Sides, 0
            );
        }
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    private void playBlockSound(BlockItem item, BlockPos pos) {
        BlockSoundGroup sound = item.getBlock().getDefaultState().getSoundGroup();
        mc.world.playSound(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            sound.getPlaceSound(), SoundCategory.BLOCKS,
            (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false
        );
    }
}