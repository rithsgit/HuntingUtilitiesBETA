package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Ringmaster – versatile shape builder with blueprint preview, rotation, and auto‑placement.
 * Supports 26 distinct 2D shapes, extruded into layers.
 */
public class Ringmaster extends Module {

    // ========================================
    // CONSTANTS
    // ========================================
    private static final double EPSILON = 1e-4;                 // tighter tolerance for integer coords
    private static final double CROSS_ARM_WIDTH = 0.3;
    private static final double HEART_SCALE = 1.2;
    private static final double HEART_OFFSET_Y = 0.2;
    private static final double CRESCENT_INNER_RATIO = 0.7;
    private static final double CRESCENT_OFFSET_X_FRAC = -0.25;
    private static final double CRESCENT_OFFSET_Y_FRAC = -0.15;
    private static final double TRAPEZOID_TOP_RATIO = 0.6;
    private static final double PARALLELOGRAM_SHEAR = 0.4;
    private static final double ARROW_HEAD_START = 0.4;
    private static final double ARROW_SHAFT_HALF = 0.25;
    private static final double ARROW_HEAD_BASE_HALF = 0.4;
    private static final double CROWN_BASE_HEIGHT = 0.4;
    private static final double CROWN_PEAK_HEIGHT = 0.6;
    private static final double LIGHTNING_WIDTH = 0.2;
    private static final double CLUB_CIRCLE_RADIUS = 0.3;
    private static final int SAFE_BOUND_MARGIN = 5;

    // ========================================
    // SETTINGS GROUPS
    // ========================================
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgRender = settings.createGroup("Blueprint Render");

    // ========================================
    // GENERAL SETTINGS – Shape & Layout
    // ========================================
    private final Setting<ShapeType> shapeType = sgGeneral.add(new EnumSetting.Builder<ShapeType>()
            .name("shape-type")
            .description("The base 2D shape to build.")
            .defaultValue(ShapeType.Circle)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    private final Setting<Integer> xRadius = sgGeneral.add(new IntSetting.Builder()
            .name("x-radius")
            .description("Radius of the shape along the X‑axis (blocks).")
            .defaultValue(10)
            .min(1)
            .sliderMax(50)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    private final Setting<Integer> zRadius = sgGeneral.add(new IntSetting.Builder()
            .name("z-radius")
            .description("Radius along the Z‑axis (or Y when vertical).")
            .defaultValue(10)
            .min(1)
            .sliderMax(50)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    private final Setting<Integer> thickness = sgGeneral.add(new IntSetting.Builder()
            .name("thickness")
            .description("Outline thickness (in blocks). Best for convex shapes.")
            .defaultValue(1)
            .min(1)
            .sliderMax(10)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    private final Setting<FillMode> fillMode = sgGeneral.add(new EnumSetting.Builder<FillMode>()
            .name("fill-mode")
            .description("Whether the shape is solid (Filled) or just an Outline.")
            .defaultValue(FillMode.Outline)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    private final Setting<Integer> layers = sgGeneral.add(new IntSetting.Builder()
            .name("layers")
            .description("Number of layers (height when horizontal, thickness when vertical).")
            .defaultValue(1)
            .min(1)
            .sliderMax(50)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    private final Setting<Integer> viewLayer = sgGeneral.add(new IntSetting.Builder()
            .name("view-layer")
            .description("Which layer to currently view and build.")
            .defaultValue(1)
            .min(1)
            .sliderMax(50)
            .onChanged(v -> {
                currentLayer = v;
                activeTarget = null;
            })
            .build()
    );

    private final Setting<Orientation> orientation = sgGeneral.add(new EnumSetting.Builder<Orientation>()
            .name("orientation")
            .description("Build plane: Horizontal (X‑Z) or Vertical (X‑Y).")
            .defaultValue(Orientation.Horizontal)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    // ========================================
    // SHAPE‑SPECIFIC PARAMETERS
    // ========================================
    private final Setting<Double> starInnerRadius = sgGeneral.add(new DoubleSetting.Builder()
            .name("star-inner-radius")
            .description("Fraction of outer radius for the star's inner valley.")
            .defaultValue(0.382)
            .min(0.1)
            .max(0.9)
            .visible(() -> shapeType.get() == ShapeType.Star)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    private final Setting<Double> ringInnerRadius = sgGeneral.add(new DoubleSetting.Builder()
            .name("ring-inner-radius")
            .description("Fraction of outer radius for the ring's hollow centre.")
            .defaultValue(0.4)
            .min(0.1)
            .max(0.9)
            .visible(() -> shapeType.get() == ShapeType.Ring)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    private final Setting<Integer> gearTeeth = sgGeneral.add(new IntSetting.Builder()
            .name("gear-teeth")
            .description("Number of teeth on the gear.")
            .defaultValue(8)
            .min(4)
            .max(24)
            .visible(() -> shapeType.get() == ShapeType.Gear)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    private final Setting<Double> gearToothDepth = sgGeneral.add(new DoubleSetting.Builder()
            .name("gear-tooth-depth")
            .description("Depth of each tooth as a fraction of the outer radius.")
            .defaultValue(0.2)
            .min(0.05)
            .max(0.5)
            .visible(() -> shapeType.get() == ShapeType.Gear)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    // ========================================
    // ROTATION & LAYER NAVIGATION
    // ========================================
    private final Setting<RotationPreset> rotationPreset = sgGeneral.add(new EnumSetting.Builder<RotationPreset>()
            .name("rotation")
            .description("Rotates the shape in the build plane.")
            .defaultValue(RotationPreset.Deg0)
            .onChanged(v -> regenerateBlueprint())
            .build()
    );

    private final Setting<LayerDisplay> layerDisplay = sgGeneral.add(new EnumSetting.Builder<LayerDisplay>()
            .name("layer-display")
            .description("Show only the active layer or all layers simultaneously.")
            .defaultValue(LayerDisplay.Single)
            .build()
    );

    private final Setting<Keybind> layerUpKey = sgGeneral.add(new KeybindSetting.Builder()
            .name("layer-up")
            .description("Keybind to advance to the next layer.")
            .defaultValue(Keybind.none())
            .visible(() -> layerDisplay.get() == LayerDisplay.Single)
            .build()
    );

    private final Setting<Keybind> layerDownKey = sgGeneral.add(new KeybindSetting.Builder()
            .name("layer-down")
            .description("Keybind to go back to the previous layer.")
            .defaultValue(Keybind.none())
            .visible(() -> layerDisplay.get() == LayerDisplay.Single)
            .build()
    );

    // ========================================
    // PLACEMENT SETTINGS
    // ========================================
    private final Setting<Boolean> pauseOnShift = sgPlacement.add(new BoolSetting.Builder()
            .name("pause-on-shift")
            .description("Temporarily pause placement while sneaking.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> autoPlace = sgPlacement.add(new BoolSetting.Builder()
            .name("auto-place")
            .description("Automatically place blocks as you approach them.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> placeDelay = sgPlacement.add(new IntSetting.Builder()
            .name("place-delay")
            .description("Ticks between each placement action.")
            .defaultValue(2)
            .min(0)
            .sliderMax(10)
            .visible(autoPlace::get)
            .build()
    );

    private final Setting<Boolean> airPlace = sgPlacement.add(new BoolSetting.Builder()
            .name("air-place")
            .description("Use off‑hand exploit to place blocks in mid‑air.")
            .defaultValue(true)
            .visible(autoPlace::get)
            .build()
    );

    private final Setting<Boolean> alignDirectional = sgPlacement.add(new BoolSetting.Builder()
            .name("align-directional")
            .description("Rotate player towards centre when placing stairs/slabs/logs.")
            .defaultValue(true)
            .visible(autoPlace::get)
            .build()
    );

    private final Setting<Integer> replenishThreshold = sgPlacement.add(new IntSetting.Builder()
            .name("replenish-threshold")
            .description("Auto‑refill hotbar slot when stack drops below this count.")
            .defaultValue(5)
            .min(1)
            .sliderMax(63)
            .build()
    );

    private final Setting<List<Block>> blockPalette = sgPlacement.add(new BlockListSetting.Builder()
            .name("block-palette")
            .description("Blocks to use (in order or randomly). Empty = use held item.")
            .build()
    );

    private final Setting<Boolean> randomizeBlocks = sgPlacement.add(new BoolSetting.Builder()
            .name("randomize-blocks")
            .description("Pick blocks randomly from the palette instead of sequentially.")
            .defaultValue(false)
            .visible(() -> blockPalette.get().size() >= 2)
            .build()
    );

    // ========================================
    // RENDER SETTINGS (Blueprint Render)
    // ========================================
    private final Setting<Boolean> blueprintEnabled = sgRender.add(new BoolSetting.Builder()
            .name("blueprint")
            .description("Render the blueprint preview.")
            .defaultValue(true)
            .build()
    );

    private final Setting<SettingColor> pendingColor = sgRender.add(new ColorSetting.Builder()
            .name("pending-color")
            .description("Colour for blocks awaiting placement.")
            .defaultValue(new SettingColor(0, 255, 255, 100))
            .build()
    );

    private final Setting<SettingColor> activeColor = sgRender.add(new ColorSetting.Builder()
            .name("active-color")
            .description("Colour for the block currently targeted.")
            .defaultValue(new SettingColor(255, 255, 0, 150))
            .build()
    );

    private final Setting<SettingColor> completedColor = sgRender.add(new ColorSetting.Builder()
            .name("completed-color")
            .description("Colour for already placed blocks.")
            .defaultValue(new SettingColor(0, 255, 0, 40))
            .build()
    );

    private final Setting<Boolean> showCompleted = sgRender.add(new BoolSetting.Builder()
            .name("show-completed")
            .description("Keep placed blocks visible in the blueprint render.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
            .name("render-distance")
            .description("Maximum distance (blocks) to render blueprint blocks.")
            .defaultValue(64)
            .min(16)
            .sliderMax(256)
            .build()
    );

    // ========================================
    // INTERNAL STATE
    // ========================================
    private final List<BlockPos> blueprint = new ArrayList<>();
    private final Set<BlockPos> placedPositions = new HashSet<>();
    private BlockPos centerPos;
    private BlockPos activeTarget = null;
    private int tickTimer = 0;
    private int outOfBlocksCooldown = 0;
    private int currentLayer = 1;
    private int totalLayers = 1;
    private boolean wasLayerUpPressed = false;
    private boolean wasLayerDownPressed = false;

    // AirPlace packet filters
    private boolean lock = false;
    private boolean own = false;

    // ========================================
    // ENUMS
    // ========================================
    private enum FillMode { Outline, Filled }
    private enum Orientation { Horizontal, Vertical }
    private enum LayerDisplay { Single, All }

    private enum ShapeType {
        // Existing (16)
        Circle, Square, Diamond, Hexagon, Star, Pentagon, Octagon, Cross,
        Triangle, Heart, Arrow, Crescent, Trapezoid, Parallelogram,
        Ring, Gear,
        // New (10)
        Heptagon, Nonagon, Decagon, Hexagram, Shield,
        Crown, Lightning, Spade, Club, Kite
    }

    private enum RotationPreset {
        Deg0(0), Deg45(45), Deg90(90), Deg135(135),
        Deg180(180), Deg225(225), Deg270(270), Deg315(315);

        final int degrees;
        RotationPreset(int deg) { this.degrees = deg; }
        double radians() { return Math.toRadians(this.degrees); }

        @Override
        public String toString() {
            return Integer.toString(degrees);
        }
    }

    // ========================================
    // CONSTRUCTOR
    // ========================================
    public Ringmaster() {
        super(Tim.CATEGORY, "ringmaster",
                "Extruded shape builder with blueprint preview, rotation and auto‑placement.");
    }

    // ========================================
    // MODULE LIFECYCLE
    // ========================================
    @Override
    public void onActivate() {
        centerPos = mc.player.getBlockPos();
        currentLayer = viewLayer.get();
        placedPositions.clear();
        generateBlueprint();
        sortBlueprint();
        verifyBlueprint();
        outOfBlocksCooldown = 0;
        lock = false;
        own = false;
    }

    @Override
    public void onDeactivate() {
        blueprint.clear();
        placedPositions.clear();
        activeTarget = null;
        lock = false;
        own = false;
    }

    private void regenerateBlueprint() {
        if (mc.player == null || mc.world == null || centerPos == null) return;
        placedPositions.clear();
        generateBlueprint();
        sortBlueprint();
        verifyBlueprint();
        activeTarget = null;
        tickTimer = 0;
    }

    // ========================================
    // TICK HANDLER
    // ========================================
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (pauseOnShift.get() && mc.player.isSneaking()) {
            activeTarget = null;
            tickTimer = 0;
            return;
        }

        if (layerDisplay.get() == LayerDisplay.Single) {
            handleLayerKeys();
        } else {
            if (currentLayer != viewLayer.get()) {
                currentLayer = viewLayer.get();
                activeTarget = null;
            }
        }

        if (outOfBlocksCooldown > 0) outOfBlocksCooldown--;

        if (blueprint.isEmpty()) {
            info("Blueprint complete! Disabling Ringmaster.");
            toggle();
            return;
        }
        verifyBlueprint();
        if (placedPositions.size() >= blueprint.size()) {
            info("Blueprint complete! Disabling Ringmaster.");
            toggle();
            return;
        }

        handleManualMode();
    }

    private void handleLayerKeys() {
        boolean up = layerUpKey.get().isPressed();
        if (up && !wasLayerUpPressed) {
            if (currentLayer < totalLayers) {
                currentLayer++;
                viewLayer.set(currentLayer);
                activeTarget = null;
                info("Layer " + currentLayer + " / " + totalLayers);
            }
        }
        wasLayerUpPressed = up;

        boolean down = layerDownKey.get().isPressed();
        if (down && !wasLayerDownPressed) {
            if (currentLayer > 1) {
                currentLayer--;
                viewLayer.set(currentLayer);
                activeTarget = null;
                info("Layer " + currentLayer + " / " + totalLayers);
            }
        }
        wasLayerDownPressed = down;
    }

    // ========================================
    // AUTO‑PLACEMENT LOGIC
    // ========================================
    private void handleManualMode() {
        int targetCoord = getCurrentLayerCoord();
        boolean isVertical = orientation.get() == Orientation.Vertical;

        if (!autoPlace.get()) {
            activeTarget = null;
            tickTimer = 0;
            return;
        }

        BlockPos nearest = findNearestTargetOnLayer(targetCoord, isVertical);

        if (nearest != null) {
            double dist = mc.player.getBlockPos().getSquaredDistance(nearest);
            double maxDist = airPlace.get() ? 25.0 : 6.0;
            if (dist <= maxDist) {
                activeTarget = nearest;
                tickTimer++;
                if (tickTimer >= placeDelay.get()) {
                    tickTimer = 0;
                    attemptPlace(nearest);
                }
                return;
            }
        } else {
            boolean layerHasBlocks = false;
            for (BlockPos pos : blueprint) {
                if (placedPositions.contains(pos)) continue;
                int coord = isVertical ? pos.getZ() : pos.getY();
                if (coord == targetCoord) {
                    layerHasBlocks = true;
                    break;
                }
            }
            if (!layerHasBlocks) {
                if (currentLayer < totalLayers) {
                    info("Layer " + currentLayer + " complete! Press Layer Up to continue.");
                } else {
                    info("Final layer complete! Disabling Ringmaster.");
                    toggle();
                }
            }
        }

        activeTarget = null;
        tickTimer = 0;
    }

    private int getCurrentLayerCoord() {
        boolean isVertical = orientation.get() == Orientation.Vertical;
        return isVertical ? centerPos.getZ() + (currentLayer - 1)
                          : centerPos.getY() + (currentLayer - 1);
    }

    private BlockPos findNearestTargetOnLayer(int targetCoord, boolean isVertical) {
        BlockPos nearest = null;
        double closestDist = Double.MAX_VALUE;
        for (BlockPos pos : blueprint) {
            if (placedPositions.contains(pos)) continue;
            int coord = isVertical ? pos.getZ() : pos.getY();
            if (coord != targetCoord) continue;
            double dist = mc.player.getBlockPos().getSquaredDistance(pos);
            if (dist < closestDist) {
                closestDist = dist;
                nearest = pos;
            }
        }
        return nearest;
    }

    // ========================================
    // BLOCK PLACEMENT
    // ========================================
    private boolean attemptPlace(BlockPos target) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (!selectBlockFromPalette()) return false;

        final BlockHitResult finalHit;
        final BlockPos lookTarget;

        if (airPlace.get()) {
            finalHit = new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false);
            lookTarget = target;
        } else {
            Direction placeDir = null;
            BlockPos neighborPos = null;
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = target.offset(dir);
                BlockState neighborState = mc.world.getBlockState(neighbor);
                if (!neighborState.isReplaceable() && neighborState.isFullCube(mc.world, neighbor)) {
                    placeDir = dir.getOpposite();
                    neighborPos = neighbor;
                    break;
                }
            }
            if (neighborPos != null) {
                Vec3d hitVec = Vec3d.ofCenter(neighborPos)
                        .add(Vec3d.of(placeDir.getVector()).multiply(0.5));
                finalHit = new BlockHitResult(hitVec, placeDir, neighborPos, false);
                lookTarget = neighborPos;
            } else {
                return false;
            }
        }

        float targetYaw, targetPitch;
        if (alignDirectional.get() && isDirectionalBlock(mc.player.getMainHandStack())) {
            targetYaw = (float) Rotations.getYaw(centerPos);
            targetPitch = (float) Rotations.getPitch(centerPos);
        } else {
            targetYaw = (float) Rotations.getYaw(lookTarget);
            targetPitch = (float) Rotations.getPitch(lookTarget);
        }

        lock = true;
        Rotations.rotate(targetYaw, targetPitch, () -> {
            if (airPlace.get()) {
                PlayerActionC2SPacket swap = new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                        BlockPos.ORIGIN, Direction.DOWN);
                own = true;
                try {
                    mc.getNetworkHandler().sendPacket(swap);
                    mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(
                            Hand.OFF_HAND, finalHit,
                            mc.player.currentScreenHandler.getRevision() + 2));
                    mc.getNetworkHandler().sendPacket(swap);
                } finally {
                    own = false;
                    lock = false;
                }
            } else {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, finalHit);
                lock = false;
            }
            mc.player.swingHand(Hand.MAIN_HAND);
        });
        return true;
    }

    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (lock && !own && event.packet instanceof PlayerInteractBlockC2SPacket) {
            event.cancel();
        }
    }

    // ========================================
    // INVENTORY MANAGEMENT
    // ========================================
    private boolean isValidBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem)) return false;
        if (blockPalette.get().isEmpty()) return true;
        return blockPalette.get().contains(((BlockItem) stack.getItem()).getBlock());
    }

    private boolean selectBlockFromPalette() {
        if (mc.player == null) return false;

        ItemStack mainHand = mc.player.getMainHandStack();
        if (isValidBlock(mainHand)) {
            tryReplenish();
            return true;
        }

        List<Integer> validSlots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (isValidBlock(mc.player.getInventory().getStack(i))) {
                validSlots.add(i);
            }
        }

        if (!validSlots.isEmpty()) {
            if (randomizeBlocks.get() && validSlots.size() > 1) {
                int idx = mc.world.getRandom().nextInt(validSlots.size());
                mc.player.getInventory().selectedSlot = validSlots.get(idx);
            } else {
                mc.player.getInventory().selectedSlot = validSlots.get(0);
            }
            tryReplenish();
            return true;
        }

        if (!mc.player.getAbilities().creativeMode) {
            for (int i = 9; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (isValidBlock(stack)) {
                    int hotbarSlot = -1;
                    for (int j = 0; j < 9; j++) {
                        if (mc.player.getInventory().getStack(j).isEmpty()) {
                            hotbarSlot = j;
                            break;
                        }
                    }
                    if (hotbarSlot == -1) hotbarSlot = mc.player.getInventory().selectedSlot;
                    InvUtils.move().from(i).toHotbar(hotbarSlot);
                    mc.player.getInventory().selectedSlot = hotbarSlot;
                    return false;
                }
            }
        }

        if (outOfBlocksCooldown == 0) {
            warning("Out of blocks! Restock to resume.");
            outOfBlocksCooldown = 100;
        }
        activeTarget = null;
        tickTimer = 0;
        return false;
    }

    private void tryReplenish() {
        if (mc.player == null) return;
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.getItem() instanceof BlockItem
                && mainHand.getCount() <= replenishThreshold.get()
                && mainHand.getCount() < mainHand.getMaxCount()) {
            int targetSlot = mc.player.getInventory().selectedSlot;
            for (int i = 9; i < 36; i++) {
                ItemStack invStack = mc.player.getInventory().getStack(i);
                if (!invStack.isEmpty() && ItemStack.areItemsEqual(mainHand, invStack)) {
                    InvUtils.move().from(i).toHotbar(targetSlot);
                    return;
                }
            }
            if (outOfBlocksCooldown == 0) {
                warning("Low on blocks – restock soon.");
                outOfBlocksCooldown = 100;
            }
        }
    }

    private boolean isDirectionalBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem)) return false;
        Block block = ((BlockItem) stack.getItem()).getBlock();
        return block instanceof net.minecraft.block.StairsBlock ||
                block instanceof net.minecraft.block.SlabBlock ||
                block instanceof net.minecraft.block.PillarBlock;
    }

    // ========================================
    // BLUEPRINT RENDERER
    // ========================================
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!blueprintEnabled.get()) return;

        BlockPos playerPos = mc.player.getBlockPos();
        int renderDistSq = renderDistance.get() * renderDistance.get();
        int targetCoord = getCurrentLayerCoord();
        boolean isVertical = orientation.get() == Orientation.Vertical;
        boolean showAll = layerDisplay.get() == LayerDisplay.All;

        for (BlockPos pos : blueprint) {
            if (!showAll) {
                int coord = isVertical ? pos.getZ() : pos.getY();
                if (coord != targetCoord) continue;
            }
            if (pos.getSquaredDistance(playerPos) > renderDistSq) continue;

            Color color;
            if (placedPositions.contains(pos)) {
                if (!showCompleted.get()) continue;
                color = completedColor.get();
            } else if (pos.equals(activeTarget)) {
                color = activeColor.get();
            } else {
                color = pendingColor.get();
            }
            event.renderer.box(pos, color, color, ShapeMode.Both, 0);
        }
    }

    // ========================================
    // BLUEPRINT GENERATION ENGINE
    // ========================================
    private void generateBlueprint() {
        blueprint.clear();
        placedPositions.clear();

        double xr = xRadius.get();
        double zr = zRadius.get();
        int thick = thickness.get();

        double rotRad = rotationPreset.get().radians();
        double cos = Math.cos(rotRad);
        double sin = Math.sin(rotRad);

        // Safe bound: 2 * max(xr,zr) + margin + extra for rounding
        int maxRadius = (int) Math.max(xr, zr);
        int bound = maxRadius * 2 + SAFE_BOUND_MARGIN + 2;

        totalLayers = layers.get();
        currentLayer = Math.min(currentLayer, totalLayers);
        if (currentLayer < 1) currentLayer = 1;
        if (viewLayer.get() != currentLayer) viewLayer.set(currentLayer);

        if (orientation.get() == Orientation.Horizontal) {
            // Shape in X‑Z plane, layers along Y
            for (int x = -bound; x <= bound; x++) {
                for (int z = -bound; z <= bound; z++) {
                    int rx = (int) Math.round(x * cos - z * sin);
                    int rz = (int) Math.round(x * sin + z * cos);
                    if (!isInShape2D(shapeType.get(), rx, rz, xr, zr, thick)) continue;
                    for (int y = 0; y < totalLayers; y++) {
                        blueprint.add(new BlockPos(
                                centerPos.getX() + x,
                                centerPos.getY() + y,
                                centerPos.getZ() + z
                        ));
                    }
                }
            }
        } else {
            // Shape in X‑Y plane, layers along Z
            int yOffset = maxRadius; // centre vertically
            for (int x = -bound; x <= bound; x++) {
                for (int y = -yOffset; y <= yOffset; y++) {
                    int rx = (int) Math.round(x * cos - y * sin);
                    int ry = (int) Math.round(x * sin + y * cos);
                    if (!isInShape2D(shapeType.get(), rx, ry, xr, zr, thick)) continue;
                    for (int z = 0; z < totalLayers; z++) {
                        blueprint.add(new BlockPos(
                                centerPos.getX() + x,
                                centerPos.getY() + y + yOffset,
                                centerPos.getZ() + z
                        ));
                    }
                }
            }
        }
    }

    // ========================================
    // 2D SHAPE EVALUATION
    // ========================================
    private boolean isInShape2D(ShapeType type, int x, int z, double xr, double zr, int thickness) {
        boolean inside = isInsideFilled(type, x, z, xr, zr);
        if (!inside) return false;
        if (fillMode.get() == FillMode.Filled) return true;

        // Convex shapes use radius‑shrink outline; non‑convex use neighbour check
        if (isConvex(type)) {
            double innerXr = xr - thickness;
            double innerZr = zr - thickness;
            if (innerXr <= 0 || innerZr <= 0) return true;
            return !isInsideFilled(type, x, z, innerXr, innerZr);
        } else {
            int[][] dirs = {{1,0}, {-1,0}, {0,1}, {0,-1}};
            for (int[] d : dirs) {
                if (!isInsideFilled(type, x + d[0], z + d[1], xr, zr)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean isConvex(ShapeType type) {
        return switch (type) {
            case Circle, Square, Diamond, Pentagon, Hexagon, Octagon,
                 Triangle, Trapezoid, Parallelogram,
                 Heptagon, Nonagon, Decagon, Kite -> true;
            default -> false;
        };
    }

    private boolean isInsideFilled(ShapeType type, int x, int z, double xr, double zr) {
        return switch (type) {
            // Existing
            case Circle        -> isInEllipse(x, z, xr, zr);
            case Square        -> isInSquare(x, z, xr, zr);
            case Diamond       -> isInDiamond(x, z, xr, zr);
            case Star          -> isInStar(x, z, xr, zr);
            case Cross         -> isInCross(x, z, xr, zr);
            case Pentagon      -> isInPolygon(x, z, xr, zr, 5);
            case Hexagon       -> isInPolygon(x, z, xr, zr, 6);
            case Octagon       -> isInPolygon(x, z, xr, zr, 8);
            case Triangle      -> isInTriangle(x, z, xr, zr);
            case Heart         -> isInHeart(x, z, xr, zr);
            case Arrow         -> isInArrow(x, z, xr, zr);
            case Crescent      -> isInCrescent(x, z, xr, zr);
            case Trapezoid     -> isInTrapezoid(x, z, xr, zr);
            case Parallelogram -> isInParallelogram(x, z, xr, zr);
            case Ring          -> isInRing(x, z, xr, zr);
            case Gear          -> isInGear(x, z, xr, zr);
            // New
            case Heptagon      -> isInPolygon(x, z, xr, zr, 7);
            case Nonagon       -> isInPolygon(x, z, xr, zr, 9);
            case Decagon       -> isInPolygon(x, z, xr, zr, 10);
            case Hexagram      -> isInHexagram(x, z, xr, zr);
            case Shield        -> isInShield(x, z, xr, zr);
            case Crown         -> isInCrown(x, z, xr, zr);
            case Lightning     -> isInLightning(x, z, xr, zr);
            case Spade         -> isInSpade(x, z, xr, zr);
            case Club          -> isInClub(x, z, xr, zr);
            case Kite          -> isInKite(x, z, xr, zr);
            default -> false;
        };
    }

    // ---------- Core shape tests (improved) ----------
    private boolean isInEllipse(int x, int z, double xr, double zr) {
        double dx = x / xr, dz = z / zr;
        return dx*dx + dz*dz <= 1.0 + EPSILON;
    }

    private boolean isInSquare(int x, int z, double xr, double zr) {
        return Math.abs(x) <= xr + EPSILON && Math.abs(z) <= zr + EPSILON;
    }

    private boolean isInDiamond(int x, int z, double xr, double zr) {
        return Math.abs(x)/xr + Math.abs(z)/zr <= 1.0 + EPSILON;
    }

    private boolean isInCross(int x, int z, double xr, double zr) {
        double nx = Math.abs(x / xr), nz = Math.abs(z / zr);
        return (nx <= 1.0 + EPSILON && nz <= CROSS_ARM_WIDTH + EPSILON) ||
               (nz <= 1.0 + EPSILON && nx <= CROSS_ARM_WIDTH + EPSILON);
    }

    // Regular polygon (convex) – works for Heptagon, Nonagon, etc.
    private boolean isInPolygon(int x, int z, double xr, double zr, int sides) {
        double nx = x / xr, nz = z / zr;
        double dist = Math.sqrt(nx*nx + nz*nz);
        if (dist > 1.0 + EPSILON) return false;
        if (dist < EPSILON) return true; // centre point
        double angle = Math.atan2(nz, nx);
        if (angle < 0) angle += 2 * Math.PI;
        double seg = angle % (2 * Math.PI / sides);
        double t = Math.abs(seg - Math.PI / sides);
        double apothem = Math.cos(Math.PI / sides);
        return dist * Math.cos(t) <= apothem + EPSILON;
    }

    private boolean isInStar(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        double dist = Math.sqrt(nx*nx + nz*nz);
        if (dist > 1.0 + EPSILON) return false;
        double angle = Math.atan2(nz, nx);
        if (angle < 0) angle += 2 * Math.PI;
        int points = 5;
        double seg = angle % (2 * Math.PI / points);
        double t = Math.abs(seg - Math.PI / points) / (Math.PI / points);
        double inner = starInnerRadius.get();
        double radius = inner + (1.0 - inner) * (1.0 - t);
        return dist <= radius + EPSILON;
    }

    // ---------- Improved shapes ----------
    // Triangle: isosceles, base at bottom, apex at top (centred)
    private boolean isInTriangle(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        if (nz < -1.0 - EPSILON || nz > 1.0 + EPSILON) return false;
        double halfWidth = 0.5 * (1.0 - nz); // base width = 1 at bottom, 0 at top
        return Math.abs(nx) <= halfWidth + EPSILON;
    }

    // Crescent: outer ellipse minus offset inner ellipse (now uses +EPSILON for exclusion)
    private boolean isInCrescent(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        double outerDist = Math.sqrt(nx*nx + nz*nz);
        if (outerDist > 1.0 + EPSILON) return false;
        // Inner ellipse parameters
        double innerX = xr * CRESCENT_INNER_RATIO;
        double innerZ = zr * CRESCENT_INNER_RATIO;
        double offX = CRESCENT_OFFSET_X_FRAC * xr;
        double offY = CRESCENT_OFFSET_Y_FRAC * zr;
        double dx = x - offX;
        double dy = z - offY;
        double innerDist = Math.sqrt((dx*dx)/(innerX*innerX) + (dy*dy)/(innerZ*innerZ));
        // Must be outside inner ellipse (strictly > 1)
        return innerDist > 1.0 + EPSILON;
    }

    // Trapezoid: bottom full width, top narrower (TRAPEZOID_TOP_RATIO)
    private boolean isInTrapezoid(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        if (nz < -1.0 - EPSILON || nz > 1.0 + EPSILON) return false;
        // t = 0 at bottom, 1 at top
        double t = (nz + 1.0) / 2.0;
        double halfWidth = 1.0 - (1.0 - TRAPEZOID_TOP_RATIO) * t;
        return Math.abs(nx) <= halfWidth + EPSILON;
    }

    private boolean isInParallelogram(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        double xp = nx - PARALLELOGRAM_SHEAR * nz;
        return Math.abs(xp) <= 1.0 + EPSILON && Math.abs(nz) <= 1.0 + EPSILON;
    }

    private boolean isInRing(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        double outer = Math.sqrt(nx*nx + nz*nz);
        if (outer > 1.0 + EPSILON) return false;
        double inner = ringInnerRadius.get();
        double innerDist = Math.sqrt((nx/inner)*(nx/inner) + (nz/inner)*(nz/inner));
        return innerDist > 1.0 + EPSILON;
    }

    private boolean isInGear(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        double dist = Math.sqrt(nx*nx + nz*nz);
        if (dist > 1.0 + EPSILON) return false;
        int teeth = gearTeeth.get();
        double depth = gearToothDepth.get();
        double angle = Math.atan2(nz, nx);
        if (angle < 0) angle += 2 * Math.PI;
        double seg = angle / (2 * Math.PI / teeth);
        double frac = seg - Math.floor(seg);
        double base = 1.0 - depth;
        double tooth = (frac >= 0.2 && frac <= 0.8) ? 1.0 : 0.0;
        return dist <= base + depth * tooth + EPSILON;
    }

    // ---------- Other shapes (unchanged but with EPSILON) ----------
    private boolean isInHeart(int x, int z, double xr, double zr) {
        double nx = x / xr * HEART_SCALE;
        double nz = z / zr * HEART_SCALE;
        double a = nx*nx + (nz - HEART_OFFSET_Y)*(nz - HEART_OFFSET_Y) - 1.0;
        return a*a*a - nx*nx * (nz - HEART_OFFSET_Y)*(nz - HEART_OFFSET_Y)*(nz - HEART_OFFSET_Y) <= EPSILON;
    }

    private boolean isInArrow(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        if (nz >= -1.0 - EPSILON && nz <= ARROW_HEAD_START + EPSILON && Math.abs(nx) <= ARROW_SHAFT_HALF + EPSILON)
            return true;
        if (nz > ARROW_HEAD_START - EPSILON && nz <= 1.0 + EPSILON) {
            double t = (nz - ARROW_HEAD_START) / (1.0 - ARROW_HEAD_START);
            double half = ARROW_HEAD_BASE_HALF * (1.0 - t);
            return Math.abs(nx) <= half + EPSILON;
        }
        return false;
    }

    private boolean isInHexagram(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        return isInTriangleUp(nx, nz) || isInTriangleDown(nx, nz);
    }

    private boolean isInTriangleUp(double x, double y) {
        if (y < -1 - EPSILON || y > 1 + EPSILON) return false;
        double half = (1.0 - y) / 2.0;
        return Math.abs(x) <= half + EPSILON;
    }

    private boolean isInTriangleDown(double x, double y) {
        if (y < -1 - EPSILON || y > 1 + EPSILON) return false;
        double half = (1.0 + y) / 2.0;
        return Math.abs(x) <= half + EPSILON;
    }

    private boolean isInShield(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        if (nz >= -EPSILON) {
            return (nx*nx + nz*nz <= 1.0 + EPSILON);
        }
        double halfWidth = 1.0 + nz;
        return Math.abs(nx) <= halfWidth + EPSILON;
    }

    private boolean isInCrown(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        double baseTop = -1.0 + 2.0 * CROWN_BASE_HEIGHT;
        if (nz >= -1.0 - EPSILON && nz <= baseTop + EPSILON && Math.abs(nx) <= 1.0 + EPSILON)
            return true;
        if (nz > baseTop - EPSILON && nz <= 1.0 + EPSILON) {
            double t = (nz - baseTop) / (1.0 - baseTop);
            double centerHalf = 0.3 * (1.0 - t);
            double sideHalf = 0.2 * (1.0 - t);
            boolean left  = Math.abs(nx + 0.6) <= sideHalf + EPSILON;
            boolean center = Math.abs(nx) <= centerHalf + EPSILON;
            boolean right = Math.abs(nx - 0.6) <= sideHalf + EPSILON;
            return left || center || right;
        }
        return false;
    }

    private boolean isInLightning(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        double[][] pts = {
            {-0.3, -1.0},
            { 0.3, -0.6},
            {-0.3,  0.0},
            { 0.3,  0.6},
            {-0.3,  1.0}
        };
        double w = LIGHTNING_WIDTH;
        for (int i = 0; i < pts.length - 1; i++) {
            double x1 = pts[i][0], y1 = pts[i][1];
            double x2 = pts[i+1][0], y2 = pts[i+1][1];
            double dx = x2 - x1, dy = y2 - y1;
            double len = Math.sqrt(dx*dx + dy*dy);
            if (len == 0) continue;
            double t = ((nx - x1)*dx + (nz - y1)*dy) / (len*len);
            t = Math.max(0, Math.min(1, t));
            double px = x1 + t*dx, py = y1 + t*dy;
            double dist = Math.sqrt((nx - px)*(nx - px) + (nz - py)*(nz - py));
            if (dist <= w + EPSILON) return true;
        }
        return false;
    }

    private boolean isInSpade(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        if (nz >= -EPSILON) {
            double halfWidth = 1.0 - nz;
            if (Math.abs(nx) <= halfWidth + EPSILON) return true;
        }
        double cx1 = -0.5, cy1 = -0.5, cx2 = 0.5, cy2 = -0.5;
        double r = 0.5;
        double d1 = Math.sqrt((nx - cx1)*(nx - cx1) + (nz - cy1)*(nz - cy1));
        double d2 = Math.sqrt((nx - cx2)*(nx - cx2) + (nz - cy2)*(nz - cy2));
        return d1 <= r + EPSILON || d2 <= r + EPSILON;
    }

    private boolean isInClub(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        double r = CLUB_CIRCLE_RADIUS;
        double[][] centers = {{0,0.5}, {-0.4,0}, {0.4,0}};
        for (double[] c : centers) {
            double dx = nx - c[0], dy = nz - c[1];
            if (dx*dx + dy*dy <= r*r + EPSILON) return true;
        }
        if (Math.abs(nx) <= 0.1 + EPSILON && nz >= -1.0 - EPSILON && nz <= -0.2 + EPSILON) return true;
        return false;
    }

    // Kite: convex quadrilateral with vertices counter‑clockwise
    private boolean isInKite(int x, int z, double xr, double zr) {
        double nx = x / xr, nz = z / zr;
        // Vertices ordered counter‑clockwise: (-1,0) -> (0,-0.8) -> (1,0) -> (0,1)
        double[][] verts = { {-1,0}, {0,-0.8}, {1,0}, {0,1} };
        for (int i = 0; i < verts.length; i++) {
            double[] p1 = verts[i];
            double[] p2 = verts[(i+1)%verts.length];
            double dx = p2[0] - p1[0];
            double dy = p2[1] - p1[1];
            double cross = dx * (nz - p1[1]) - dy * (nx - p1[0]);
            if (cross < -EPSILON) return false;
        }
        return true;
    }

    // ========================================
    // UTILITY
    // ========================================
    private void sortBlueprint() {
        blueprint.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(centerPos)));
    }

    private void verifyBlueprint() {
        placedPositions.clear();
        for (BlockPos pos : blueprint) {
            BlockState state = mc.world.getBlockState(pos);
            if (!state.isReplaceable()) {
                placedPositions.add(pos);
            }
        }
    }
}