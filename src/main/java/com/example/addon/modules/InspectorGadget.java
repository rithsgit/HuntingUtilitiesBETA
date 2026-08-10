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
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IBaritoneProcess;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class InspectorGadget extends Module {

    public enum ScanState { SETUP, MOVING_TO_TILE, MOVING_TO_TARGET, OPENING_TARGET, WAITING, COOLDOWN, COMPLETE }

    public enum HighlightMode {
        GLOW,
        SPECTRAL,
        PULSE
    }

    public enum StorageTarget {
        Chests("Chests", Blocks.CHEST, Blocks.TRAPPED_CHEST),
        Barrels("Barrels", Blocks.BARREL),
        Chests_And_Barrels("Chests & Barrels", Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL),
        Dispensers_And_Droppers("Dispensers & Droppers", Blocks.DISPENSER, Blocks.DROPPER),
        Hoppers("Hoppers", Blocks.HOPPER),
        Furnaces("Furnaces", Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER),
        All_Standard("All Standard Storage",
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL,
            Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER,
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
            Blocks.BREWING_STAND);

        public final String title;
        public final Block[] blocks;

        StorageTarget(String title, Block... blocks) {
            this.title = title;
            this.blocks = blocks;
        }

        @Override
        public String toString() { return title; }

        public boolean contains(Block block) {
            for (Block b : blocks) {
                if (b == block) return true;
            }
            return false;
        }
    }

    public enum CompletionSound {
        None("None", null),
        LevelUp("Level Up", "minecraft:entity.player.levelup"),
        XpPickup("XP Pickup", "minecraft:entity.experience_orb.pickup"),
        TotemPop("Totem Pop", "minecraft:item.totem.use"),
        VillagerYes("Villager Yes", "minecraft:entity.villager.yes"),
        Pling("Pling", "minecraft:block.note_block.pling"),
        Bell("Bell", "minecraft:block.bell.use");

        public final String title;
        public final String id;

        CompletionSound(String title, String id) {
            this.title = title;
            this.id = id;
        }

        @Override
        public String toString() { return title; }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgVisuals = settings.createGroup("Visuals");

    private final Setting<StorageTarget> targetStorage = sgGeneral.add(new EnumSetting.Builder<StorageTarget>()
        .name("target-storage")
        .description("Which storage blocks to scan and open.")
        .defaultValue(StorageTarget.Chests_And_Barrels)
        .build()
    );

    private final Setting<Keybind> addTileKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("add-tile-key")
        .description("Key to add a tile while looking at a block.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> startKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("start-key")
        .description("Key to start the automated pathing sequence.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> clearKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("clear-key")
        .description("Key to clear all created tiles.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> pauseKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("pause-key")
        .description("Pauses the pathing so you can chat or move. Press again to resume.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> tileScanRange = sgGeneral.add(new IntSetting.Builder()
        .name("tile-scan-range")
        .description("Radius to scan for storage blocks around each tile.")
        .defaultValue(5).min(2).max(10).sliderMax(10)
        .build()
    );

    private final Setting<Integer> openDelay = sgGeneral.add(new IntSetting.Builder()
        .name("open-delay")
        .description("How long to wait between opening and closing containers to prevent anti-cheat kicks.")
        .defaultValue(15).min(2).max(60).sliderMax(40)
        .build()
    );

    private final Setting<CompletionSound> completionSound = sgGeneral.add(new EnumSetting.Builder<CompletionSound>()
        .name("completion-sound")
        .description("Sound played when all tiles have been scanned.")
        .defaultValue(CompletionSound.LevelUp)
        .build()
    );

    // ── Visual Settings ──

    private final Setting<HighlightMode> highlightMode = sgVisuals.add(new EnumSetting.Builder<HighlightMode>()
        .name("highlight-mode")
        .description("GLOW = layered bloom boxes. SPECTRAL = subtle fill and outline. PULSE = fading in/out bloom.")
        .defaultValue(HighlightMode.GLOW)
        .build()
    );

    private final Setting<Integer> glowLayers = sgVisuals.add(new IntSetting.Builder()
        .name("glow-layers")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> highlightMode.get() == HighlightMode.GLOW || highlightMode.get() == HighlightMode.PULSE)
        .build()
    );

    private final Setting<Double> glowSpread = sgVisuals.add(new DoubleSetting.Builder()
        .name("glow-spread")
        .defaultValue(0.05).min(0.01).sliderMax(0.2)
        .visible(() -> highlightMode.get() == HighlightMode.GLOW || highlightMode.get() == HighlightMode.PULSE)
        .build()
    );

    private final Setting<Integer> glowBaseAlpha = sgVisuals.add(new IntSetting.Builder()
        .name("glow-base-alpha")
        .defaultValue(60).min(4).sliderMax(150)
        .visible(() -> highlightMode.get() == HighlightMode.GLOW)
        .build()
    );

    private final Setting<Integer> spectralLineAlpha = sgVisuals.add(new IntSetting.Builder()
        .name("spectral-line-alpha")
        .defaultValue(255).min(0).sliderMax(255)
        .visible(() -> highlightMode.get() == HighlightMode.SPECTRAL)
        .build()
    );

    private final Setting<Integer> spectralFillAlpha = sgVisuals.add(new IntSetting.Builder()
        .name("spectral-fill-alpha")
        .defaultValue(30).min(0).sliderMax(255)
        .visible(() -> highlightMode.get() == HighlightMode.SPECTRAL)
        .build()
    );

    private final Setting<Double> pulseSpeed = sgVisuals.add(new DoubleSetting.Builder()
        .name("pulse-speed")
        .description("Pulse cycle speed. 1.0 = one full fade in/out per second.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMax(3.0)
        .visible(() -> highlightMode.get() == HighlightMode.PULSE)
        .build()
    );

    private final Setting<Integer> pulseMinAlpha = sgVisuals.add(new IntSetting.Builder()
        .name("pulse-min-alpha")
        .description("Lowest alpha reached during the pulse (0 = invisible).")
        .defaultValue(15).min(0).max(255).sliderMax(100)
        .visible(() -> highlightMode.get() == HighlightMode.PULSE)
        .build()
    );

    private final Setting<Integer> pulseMaxAlpha = sgVisuals.add(new IntSetting.Builder()
        .name("pulse-max-alpha")
        .description("Peak alpha reached during the pulse.")
        .defaultValue(220).min(50).max(255).sliderMax(255)
        .visible(() -> highlightMode.get() == HighlightMode.PULSE)
        .build()
    );

    private final Setting<SettingColor> highlightColor = sgVisuals.add(new ColorSetting.Builder()
        .name("storage-color")
        .description("Color of the storage blocks found during scan.")
        .defaultValue(new SettingColor(255, 215, 0, 200)).build()
    );

    private final Setting<SettingColor> pathColor = sgVisuals.add(new ColorSetting.Builder()
        .name("tile-color")
        .description("Color of the pathing tiles and sequence pillars.")
        .defaultValue(new SettingColor(0, 255, 100, 200)).build()
    );

    // ── State ──

    private ScanState currentState = ScanState.SETUP;
    private final List<BlockPos> pathTiles = new ArrayList<>();
    private final List<BlockPos> localTargets = new ArrayList<>();
    private final Set<BlockPos> visitedTargets = new HashSet<>();

    private int tileIndex = 0;
    private int targetIndex = 0;
    private int waitTimer = 0;
    private int pathTimeout = 0;
    private boolean issuedMoveCommand = false;
    
    private boolean wasAddPressed = false;
    private boolean wasStartPressed = false;
    private boolean wasClearPressed = false;
    private boolean wasPausePressed = false;
    private boolean isPaused = false;

    private BlockPos currentInteractTile = null;
    private BlockPos currentPathTarget = null;

    private int openedCount = 0;
    private int shulkerCount = 0;

    public InspectorGadget() {
        super(Tim.CATEGORY, "inspector-gadget", "Walks a custom path of tiles to scan nearby storage blocks using Baritone.");
    }

    @Override
    public void onActivate() {
        currentState = ScanState.SETUP;
        pathTiles.clear();
        localTargets.clear();
        visitedTargets.clear();
        tileIndex = 0;
        targetIndex = 0;
        waitTimer = 0;
        pathTimeout = 0;
        issuedMoveCommand = false;

        wasAddPressed = false;
        wasStartPressed = false;
        wasClearPressed = false;
        wasPausePressed = false;
        isPaused = false;

        currentInteractTile = null;
        currentPathTarget = null;

        resetStats();
    }

    @Override
    public void onDeactivate() {
        stopMovement();
        closeScreen();
        resetTargets();
    }

    private void closeScreen() {
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) {
            mc.player.closeHandledScreen();
        }
    }

    private void resetTargets() {
        pathTiles.clear();
        localTargets.clear();
        visitedTargets.clear();
        currentInteractTile = null;
        currentPathTarget = null;
    }

    public int getOpenedCount() { return openedCount; }
    public int getNearbyCount() { return localTargets.size(); }
    public int getShulkerCount() { return shulkerCount; }

    public void resetStats() {
        openedCount = 0;
        shulkerCount = 0;
    }

    // ─────────────────────────── Logic ───────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (currentState != ScanState.SETUP) {
            boolean pausePressed = pauseKey.get().isPressed();
            if (pausePressed && !wasPausePressed) {
                isPaused = !isPaused;
                if (isPaused) {
                    stopMovement();
                    closeScreen();
                    info("Pathing Paused.");
                } else {
                    pathTimeout = 0;
                    issuedMoveCommand = false;
                    info("Pathing Resumed.");
                }
            }
            wasPausePressed = pausePressed;

            if (isPaused) return;
        }

        if (currentState == ScanState.SETUP) {
            boolean addPressed = addTileKey.get().isPressed();
            boolean startPressed = startKey.get().isPressed();
            boolean clearPressed = clearKey.get().isPressed();

            if (addPressed && !wasAddPressed) addTile();

            if (startPressed && !wasStartPressed) {
                if (pathTiles.isEmpty()) {
                    error("No tiles created. Look at blocks and use the Add Tile key.");
                } else {
                    currentState = ScanState.MOVING_TO_TILE;
                    tileIndex = 0;
                    visitedTargets.clear();
                    pathTimeout = 0;
                    issuedMoveCommand = false;
                    info("Starting pathing sequence for %d tiles.", pathTiles.size());
                }
            }

            if (clearPressed && !wasClearPressed) {
                pathTiles.clear();
                info("Cleared all tiles.");
            }

            wasAddPressed = addPressed;
            wasStartPressed = startPressed;
            wasClearPressed = clearPressed;
            return;
        }

        switch (currentState) {
            case MOVING_TO_TILE -> handleMoveToTile();
            case MOVING_TO_TARGET -> handleMoveToTarget();
            case OPENING_TARGET -> handleOpeningTarget();
            case WAITING -> handleWaiting();
            case COOLDOWN -> handleCooldown();
            case COMPLETE -> handleCompletion();
        }
    }

    private void addTile() {
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            BlockPos target = ((BlockHitResult) mc.crosshairTarget).getBlockPos();
            pathTiles.add(target);
            info("Added tile %d", pathTiles.size());
        } else {
            warning("You must look at a block to add a tile.");
        }
    }

    private void handleMoveToTile() {
        if (tileIndex >= pathTiles.size()) {
            currentState = ScanState.COMPLETE;
            return;
        }

        currentPathTarget = pathTiles.get(tileIndex);
        BlockPos standPos = currentPathTarget.up(); // Stand on top of the tile
        Vec3d targetPos = Vec3d.ofCenter(standPos);
        double distance = mc.player.getPos().distanceTo(targetPos);

        pathTimeout++;
        if (pathTimeout > 1000) { // 50 seconds timeout
            warning("Timeout while pathing to tile. Skipping.");
            tileIndex++;
            currentPathTarget = null;
            pathTimeout = 0;
            issuedMoveCommand = false;
            stopMovement();
            return;
        }

        if (distance <= 1.5 && isBaritoneIdle()) {
            stopMovement();
            issuedMoveCommand = false;
            pathTimeout = 0;
            populateLocalTargets();

            if (localTargets.isEmpty()) {
                info("Tile %d: No new targets found nearby. Moving to next tile.", tileIndex + 1);
                tileIndex++;
                currentPathTarget = null;
            } else {
                info("Tile %d: Found %d targets. Scanning...", tileIndex + 1, localTargets.size());
                targetIndex = 0;
                currentState = ScanState.MOVING_TO_TARGET;
            }
        } else {
            if (!issuedMoveCommand) {
                pathToBlock(standPos);
                issuedMoveCommand = true;
            }
        }
    }

    private void populateLocalTargets() {
        localTargets.clear();
        BlockPos center = pathTiles.get(tileIndex);
        int range = tileScanRange.get();
        StorageTarget storageFilter = targetStorage.get();

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    if (x*x + y*y + z*z > range*range) continue;
                    BlockPos checkPos = center.add(x, y, z);
                    Block b = mc.world.getBlockState(checkPos).getBlock();

                    if (b instanceof ShulkerBoxBlock) {
                        shulkerCount++;
                    }

                    if (storageFilter.contains(b) && !visitedTargets.contains(checkPos)) {
                        localTargets.add(checkPos);
                    }
                }
            }
        }

        // Greedy nearest-neighbour sort from the player's current position to avoid zigzag.
        List<BlockPos> ordered = new ArrayList<>();
        Set<BlockPos> remaining = new HashSet<>(localTargets);
        Vec3d cursor = mc.player.getPos();
        while (!remaining.isEmpty()) {
            BlockPos nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (BlockPos p : remaining) {
                double d = p.getSquaredDistance(cursor);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = p;
                }
            }
            ordered.add(nearest);
            remaining.remove(nearest);
            cursor = Vec3d.ofCenter(nearest);
        }
        localTargets.clear();
        localTargets.addAll(ordered);
    }

    private void handleMoveToTarget() {
        if (targetIndex >= localTargets.size()) {
            tileIndex++;
            localTargets.clear();
            currentInteractTile = null;
            currentPathTarget = null;
            currentState = ScanState.MOVING_TO_TILE;
            return;
        }

        BlockPos blockTarget = localTargets.get(targetIndex);
        Block block = mc.world.getBlockState(blockTarget).getBlock();
        StorageTarget storageFilter = targetStorage.get();

        if (!storageFilter.contains(block) || visitedTargets.contains(blockTarget)) {
            targetIndex++;
            return;
        }

        double distanceToChest = mc.player.getPos().distanceTo(Vec3d.ofCenter(blockTarget));

        if (currentInteractTile == null || !isStandable(currentInteractTile)) {
            currentInteractTile = null;
            List<BlockPos> validTiles = getValidInteractTiles(blockTarget);
            
            if (validTiles.isEmpty()) {
                // Fallback for elevated chests: Path to the block directly beneath the chest
                BlockPos fallbackTile = blockTarget.down();
                while (fallbackTile.getY() > mc.world.getBottomY() && mc.world.getBlockState(fallbackTile).getCollisionShape(mc.world, fallbackTile).isEmpty()) {
                    fallbackTile = fallbackTile.down();
                }
                
                // If the block beneath the chest is standable, use it
                if (isStandable(fallbackTile.up())) {
                    currentInteractTile = fallbackTile.up();
                } else {
                    // If we can't find a tile to stand on, but we are already close enough, just open it!
                    if (distanceToChest <= 4.2) {
                        stopMovement();
                        issuedMoveCommand = false;
                        currentState = ScanState.OPENING_TARGET;
                        return;
                    }
                    warning("Cannot path to block. Skipping.");
                    markVisited(blockTarget);
                    targetIndex++;
                    return;
                }
            } else {
                validTiles.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(mc.player.getBlockPos())));
                currentInteractTile = validTiles.get(0);
            }
            pathTimeout = 0;
            issuedMoveCommand = false;
        }

        Vec3d targetPos = Vec3d.ofCenter(currentInteractTile);
        double distanceToTile = mc.player.getPos().distanceTo(targetPos);

        pathTimeout++;
        if (pathTimeout > 1000) { // 50 seconds timeout
            warning("Timeout while pathing to target. Skipping.");
            markVisited(blockTarget);
            targetIndex++;
            currentInteractTile = null;
            pathTimeout = 0;
            issuedMoveCommand = false;
            stopMovement();
            return;
        }

        // If we are close enough to the tile OR we are within reach of the chest itself, open it.
        if (distanceToTile <= 1.5 || distanceToChest <= 4.2) {
            stopMovement();
            issuedMoveCommand = false;
            pathTimeout = 0;
            currentState = ScanState.OPENING_TARGET;
        } else {
            if (!issuedMoveCommand) {
                pathToBlock(currentInteractTile);
                issuedMoveCommand = true;
            }
        }
    }

    private void handleOpeningTarget() {
        BlockPos blockTarget = localTargets.get(targetIndex);

        Vec3d eye = mc.player.getEyePos();
        Vec3d blockCenter = Vec3d.ofCenter(blockTarget);

        Vec3d diff = eye.subtract(blockCenter);

        Direction side;
        double ax = Math.abs(diff.x), ay = Math.abs(diff.y), az = Math.abs(diff.z);
        if (ax >= ay && ax >= az) {
            side = diff.x > 0 ? Direction.EAST : Direction.WEST;
        } else if (ay >= ax && ay >= az) {
            side = diff.y > 0 ? Direction.UP : Direction.DOWN;
        } else {
            side = diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        Vec3d hitVec = Vec3d.ofCenter(blockTarget)
            .add(Vec3d.of(side.getOpposite().getVector()).multiply(0.5));

        // Use strictly horizontal difference for Yaw to prevent wild swinging when the chest is directly above/below
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-(blockCenter.x - eye.x), blockCenter.z - eye.z)));
        mc.player.setHeadYaw(mc.player.getYaw());
        
        // Use 3D difference for Pitch, safely clamped
        double dist3d = blockCenter.distanceTo(eye);
        if (dist3d > 0) {
            mc.player.setPitch((float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, (blockCenter.y - eye.y) / dist3d)))));
        }

        BlockHitResult hitResult = new BlockHitResult(hitVec, side, blockTarget, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        mc.player.swingHand(Hand.MAIN_HAND);

        currentState = ScanState.WAITING;
        waitTimer = 0;
    }

    private void handleWaiting() {
        waitTimer++;

        if (mc.currentScreen instanceof HandledScreen<?> && !(mc.currentScreen instanceof InventoryScreen)) {
            if (waitTimer >= openDelay.get()) {
                mc.player.closeHandledScreen();
                openedCount++;
                markVisited(localTargets.get(targetIndex));
                targetIndex++;
                currentInteractTile = null;
                waitTimer = 0;
                currentState = ScanState.COOLDOWN;
            }
        } else if (waitTimer > openDelay.get() + 20) {
            warning("Failed to open block. Skipping...");
            markVisited(localTargets.get(targetIndex));
            targetIndex++;
            currentInteractTile = null;
            waitTimer = 0;
            currentState = ScanState.COOLDOWN;
        }
    }

    private void handleCooldown() {
        waitTimer++;
        if (waitTimer >= openDelay.get()) {
            waitTimer = 0;
            currentState = ScanState.MOVING_TO_TARGET;
        }
    }

    private void handleCompletion() {
        stopMovement();
        resetTargets();

        CompletionSound soundSetting = completionSound.get();
        if (soundSetting != CompletionSound.None && soundSetting.id != null) {
            try {
                Identifier soundId = Identifier.tryParse(soundSetting.id);
                if (soundId != null) {
                    SoundEvent sound = Registries.SOUND_EVENT.get(soundId);
                    if (sound != null) mc.player.playSound(sound, 1.0f, 1.0f);
                }
            } catch (Exception ignored) {}
        }

        info("Inspector Gadget: Path complete!");
        this.toggle();
    }

    // ─────────────────────────── Baritone Movement Engine ───────────────────────────

    private void pathToBlock(BlockPos standPos) {
        if (standPos == null) return;
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (isBaritoneIdle()) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(standPos));
        }
    }

    private void stopMovement() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            IPathingBehavior pathing = baritone.getPathingBehavior();
            if (pathing != null && (pathing.isPathing() || pathing.hasPath() || pathing.getInProgress().isPresent())) {
                pathing.cancelEverything();
            }
        } catch (Exception ignored) {}
    }

    private boolean isBaritoneIdle() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            IPathingBehavior pathing = baritone.getPathingBehavior();
            if (pathing == null) return true;

            boolean activeProcess = baritone.getPathingControlManager()
                    .mostRecentInControl()
                    .map(IBaritoneProcess::isActive)
                    .orElse(false);

            return !activeProcess && !pathing.isPathing() && !pathing.hasPath() && pathing.getInProgress().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    // ─────────────────────────── Validation Helpers ───────────────────────────

    private boolean isStandable(BlockPos pos) {
        BlockState feet = mc.world.getBlockState(pos);
        BlockState head = mc.world.getBlockState(pos.up());
        BlockState floor = mc.world.getBlockState(pos.down());

        // Feet & head must be passable (no collision).
        if (!feet.getCollisionShape(mc.world, pos).isEmpty()) return false;
        if (!head.getCollisionShape(mc.world, pos.up()).isEmpty()) return false;
        // Floor must have a collision shape (any solid-enough surface counts).
        if (floor.getCollisionShape(mc.world, pos.down()).isEmpty()) return false;
        
        // Do not choose a spot on top of storage blocks
        Block floorBlock = floor.getBlock();
        if (targetStorage.get().contains(floorBlock) || floorBlock instanceof ShulkerBoxBlock || floorBlock == Blocks.ENDER_CHEST) {
            return false;
        }
        
        return true;
    }

    private List<BlockPos> getValidInteractTiles(BlockPos blockPos) {
        List<BlockPos> tiles = new ArrayList<>();
        // Search a 5x5 footprint around the chest, up to 5 blocks high or low to handle elevation changes.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (dx * dx + dz * dz > 5) continue; // Roughly circular radius of 2
                for (int dy = -5; dy <= 5; dy++) {
                    BlockPos tilePos = blockPos.add(dx, dy, dz);
                    if (isStandable(tilePos)) {
                        tiles.add(tilePos);
                    }
                }
            }
        }
        return tiles;
    }

    // Marks a chest as visited, including its double chest half if it has one.
    private void markVisited(BlockPos pos) {
        visitedTargets.add(pos);
        BlockState state = mc.world.getBlockState(pos);
        if (state.contains(Properties.CHEST_TYPE)) {
            ChestType type = state.get(Properties.CHEST_TYPE);
            if (type != ChestType.SINGLE) {
                Direction facing = state.get(Properties.HORIZONTAL_FACING);
                Direction connectedDir = type == ChestType.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
                BlockPos otherHalf = pos.offset(connectedDir);
                visitedTargets.add(otherHalf);
            }
        }
    }

    // ─────────────────────────── Rendering ───────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (pathTiles.isEmpty() && localTargets.isEmpty()) return;

        SettingColor pColor = pathColor.get();
        SettingColor cColor = highlightColor.get();
        HighlightMode mode = highlightMode.get();

        for (int i = 0; i < pathTiles.size(); i++) {
            BlockPos pos = pathTiles.get(i);

            Box flatTileBox = new Box(
                pos.getX(), pos.getY() + 1.0, pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.02, pos.getZ() + 1.0
            );
            
            double height = Math.min((i + 1) * 0.25, 3.0);
            Box pillarBox = new Box(
                pos.getX() + 0.4, pos.getY() + 1.0, pos.getZ() + 0.4,
                pos.getX() + 0.6, pos.getY() + 1.0 + height, pos.getZ() + 0.6
            );

            if (mode == HighlightMode.GLOW) {
                renderGlowLayers(event, flatTileBox, pColor);
                renderGlowLayers(event, pillarBox, pColor);
                event.renderer.box(flatTileBox, withAlpha(pColor, 60), pColor, ShapeMode.Sides, 0);
                event.renderer.box(pillarBox, withAlpha(pColor, 100), pColor, ShapeMode.Both, 0);
            } else if (mode == HighlightMode.PULSE) {
                renderPulseBox(event, flatTileBox, pColor);
                renderPulseBox(event, pillarBox, pColor);
            } else { // SPECTRAL
                SettingColor lineC = withAlpha(pColor, spectralLineAlpha.get());
                SettingColor fillC = withAlpha(pColor, spectralFillAlpha.get());
                event.renderer.box(flatTileBox, fillC, lineC, ShapeMode.Both, 0);
                event.renderer.box(pillarBox, fillC, lineC, ShapeMode.Both, 0);
            }
        }

        if (currentState != ScanState.SETUP && !localTargets.isEmpty()) {
            for (BlockPos pos : localTargets) {
                if (visitedTargets.contains(pos)) continue;
                Box box = new Box(pos);
                
                if (mode == HighlightMode.GLOW) {
                    renderGlowLayers(event, box, cColor);
                    event.renderer.box(box, withAlpha(cColor, 0), cColor, ShapeMode.Lines, 0);
                } else if (mode == HighlightMode.PULSE) {
                    renderPulseBox(event, box, cColor);
                } else { // SPECTRAL
                    SettingColor lineC = withAlpha(cColor, spectralLineAlpha.get());
                    SettingColor fillC = withAlpha(cColor, spectralFillAlpha.get());
                    event.renderer.box(box, fillC, lineC, ShapeMode.Both, 0);
                }
            }
        }
    }

    // ── Render Helpers ──

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int layers = glowLayers.get();
        double spread = glowSpread.get();
        int baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double t = (double)(i - 1) / layers;
            int layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - t * t)));
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
            event.renderer.box(box.expand(expansion), withAlpha(pColor, layerAlpha), withAlpha(pColor, 0), ShapeMode.Sides, 0);
        }
        event.renderer.box(box, withAlpha(pColor, pa / 3), pColor, ShapeMode.Both, 0);
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }
}