package com.example.addon.modules;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
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
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class PortalMaker extends Module {

    // ── Enums ──────────────────────────────────────────────────────
    public enum EntryMode    { None, Walk, Pearl }
    private enum RecycleState { IDLE, STEPPING_OUT, WAITING, RE_ENTERING }
    
    public enum RenderMode {
        GLOW,
        SPECTRAL,
        PULSE
    }

    // ── Setting Groups ─────────────────────────────────────────────
    private final SettingGroup sgGeneral      = settings.getDefaultGroup();
    private final SettingGroup sgMovement     = settings.createGroup("Movement & Entry");
    private final SettingGroup sgRecycle      = settings.createGroup("Recycle");
    private final SettingGroup sgRender       = settings.createGroup("Render");
    private final SettingGroup sgGlow         = settings.createGroup("Glow");

    // ── Settings — Building ────────────────────────────────────────
    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks to wait between placement actions.")
        .defaultValue(2).min(1).sliderRange(1, 12)
        .build()
    );

    private final Setting<Integer> finishDelay = sgGeneral.add(new IntSetting.Builder()
        .name("finish-delay")
        .description("Ticks to wait after lighting the portal before turning off.")
        .defaultValue(20).min(0).sliderMax(200)
        .build()
    );

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Lets you place obsidian in mid-air without needing a solid block to click against.")
        .defaultValue(true)
        .build()
    );

    // ── Settings — Movement ────────────────────────────────────────
    private final Setting<Boolean> useBaritone = sgMovement.add(new BoolSetting.Builder()
        .name("use-baritone")
        .description("Uses Baritone to automatically path into the portal.")
        .defaultValue(true)
        .build()
    );

    private final Setting<EntryMode> entryMode = sgMovement.add(new EnumSetting.Builder<EntryMode>()
        .name("entry-mode")
        .description("How to enter the portal after it is created.")
        .defaultValue(EntryMode.Walk)
        .visible(useBaritone::get)
        .build()
    );

    // ── Settings — Recycle ─────────────────────────────────────────
    private final Setting<Boolean> autoRecycle = sgRecycle.add(new BoolSetting.Builder()
        .name("auto-recycle")
        .description("After changing dimension, automatically step out, wait, and go back in.")
        .defaultValue(false)
        .visible(useBaritone::get)
        .build()
    );

    private final Setting<Boolean> cancelOnMovement = sgRecycle.add(new BoolSetting.Builder()
        .name("cancel-on-movement")
        .description("Cancels the recycle process if you manually press a movement key.")
        .defaultValue(true)
        .visible(() -> useBaritone.get() && autoRecycle.get())
        .build()
    );

    private final Setting<Integer> recycleDelaySeconds = sgRecycle.add(new IntSetting.Builder()
        .name("recycle-wait-time")
        .description("How many seconds to wait before going back into the portal.")
        .defaultValue(5).min(1).sliderMax(60)
        .visible(() -> useBaritone.get() && autoRecycle.get())
        .build()
    );

    private final Setting<Keybind> recycleKey = sgRecycle.add(new KeybindSetting.Builder()
        .name("recycle-key")
        .description("Manual keybind to trigger the recycle cycle (step out -> wait -> in).")
        .defaultValue(Keybind.none())
        .visible(useBaritone::get)
        .build()
    );

    private final Setting<Integer> dimensionSwitchCooldownTicks = sgRecycle.add(new IntSetting.Builder()
        .name("dimension-switch-cooldown")
        .description("Ticks to wait after a dimension change before resuming operations (e.g., recycling).")
        .defaultValue(40)
        .min(0).sliderMax(200)
        .visible(useBaritone::get)
        .build()
    );

    // ── Settings — Render ──────────────────────────────────────────
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Show remaining portal frame positions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the preview boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(80, 160, 255, 35))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(100, 180, 255, 255))
        .build()
    );

    // ── Settings — Glow ────────────────────────────────────────────
    private final Setting<RenderMode> renderMode = sgGlow.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("GLOW = layered bloom boxes. SPECTRAL = subtle fill. PULSE = fading in/out highlight.")
        .defaultValue(RenderMode.GLOW)
        .build()
    );

    private final Setting<Integer> glowLayers = sgGlow.add(new IntSetting.Builder()
        .name("glow-layers")
        .description("Number of bloom layers rendered around each preview block.")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> renderMode.get() == RenderMode.GLOW || renderMode.get() == RenderMode.PULSE)
        .build()
    );

    private final Setting<Double> glowSpread = sgGlow.add(new DoubleSetting.Builder()
        .name("glow-spread")
        .description("How far each bloom layer expands outward (in blocks).")
        .defaultValue(0.05).min(0.01).sliderMax(0.2)
        .visible(() -> renderMode.get() == RenderMode.GLOW || renderMode.get() == RenderMode.PULSE)
        .build()
    );

    private final Setting<Integer> glowBaseAlpha = sgGlow.add(new IntSetting.Builder()
        .name("glow-base-alpha")
        .description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(60).min(4).sliderMax(150)
        .visible(() -> renderMode.get() == RenderMode.GLOW)
        .build()
    );

    private final Setting<Integer> spectralFillAlpha = sgGlow.add(new IntSetting.Builder()
        .name("spectral-fill-alpha")
        .description("Fill alpha for preview blocks in SPECTRAL mode.")
        .defaultValue(40).min(0).max(200).sliderMax(120)
        .visible(() -> renderMode.get() == RenderMode.SPECTRAL)
        .build()
    );

    private final Setting<Double> pulseSpeed = sgGlow.add(new DoubleSetting.Builder()
        .name("pulse-speed")
        .description("Pulse cycle speed. 1.0 = one full fade in/out per second.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMax(3.0)
        .visible(() -> renderMode.get() == RenderMode.PULSE)
        .build()
    );

    private final Setting<Integer> pulseMinAlpha = sgGlow.add(new IntSetting.Builder()
        .name("pulse-min-alpha")
        .description("Lowest alpha reached during the pulse (0 = invisible).")
        .defaultValue(15).min(0).max(255).sliderMax(100)
        .visible(() -> renderMode.get() == RenderMode.PULSE)
        .build()
    );

    private final Setting<Integer> pulseMaxAlpha = sgGlow.add(new IntSetting.Builder()
        .name("pulse-max-alpha")
        .description("Peak alpha reached during the pulse.")
        .defaultValue(220).min(50).max(255).sliderMax(255)
        .visible(() -> renderMode.get() == RenderMode.PULSE)
        .build()
    );

    // ── State ──────────────────────────────────────────────────────
    public final List<BlockPos> portalFramePositions = new ArrayList<>();
    private int     placementIndex   = 0;
    private int     tickTimer        = 0;
    private int     finishTimer      = 0;
    private boolean pearlThrown      = false;
    private String  lastDimension    = "";
    private String  builtDimension   = "";
    private boolean portalLitDetected = false;
    private int     dimensionChangeCooldown = 0;
    private RecycleState recycleState = RecycleState.IDLE;
    private Vec3d   recycleTarget    = null;
    private Vec3d   stepOutTarget    = null;
    private int     recycleWaitTimer = 0;
    private boolean wasRecyclePressed = false;

    private boolean originalEnterPortal = true;
    private boolean originalAllowPlace = true;
    private boolean originalAllowBreak = true;
    private boolean originalAllowParkour = true;
    private boolean originalAllowParkourPlace = true;

    public PortalMaker() {
        super(Tim.CATEGORY, "portal-maker", "Builds and lights a minimal Nether portal (10 obsidian).");
    }

    // ── Safe Block State Helper ────────────────────────────────────
    private BlockState getSafeBlockState(BlockPos pos) {
        if (mc.world == null) return Blocks.AIR.getDefaultState();
        try {
            if (!mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                return Blocks.AIR.getDefaultState();
            }
            return mc.world.getBlockState(pos);
        } catch (Exception e) {
            return Blocks.AIR.getDefaultState();
        }
    }

    private boolean isChunkSafe(BlockPos pos) {
        if (mc.world == null || mc.player == null) return false;
        try {
            return mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────
    @Override
    public void onActivate() {
        portalFramePositions.clear();
        placementIndex   = 0;
        tickTimer        = 0;
        finishTimer      = 0;
        pearlThrown      = false;
        recycleState     = RecycleState.IDLE;
        lastDimension    = "";
        wasRecyclePressed = false;
        builtDimension   = "";
        portalLitDetected = false;
        dimensionChangeCooldown = 0;

        // Force Baritone to allow entering portals and placing blocks to reach it
        if (useBaritone.get()) {
            try {
                originalEnterPortal = BaritoneAPI.getSettings().enterPortal.value;
                BaritoneAPI.getSettings().enterPortal.value = true;
                
                originalAllowPlace = BaritoneAPI.getSettings().allowPlace.value;
                BaritoneAPI.getSettings().allowPlace.value = true;

                originalAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
                BaritoneAPI.getSettings().allowBreak.value = true;

                originalAllowParkour = BaritoneAPI.getSettings().allowParkour.value;
                BaritoneAPI.getSettings().allowParkour.value = true;

                originalAllowParkourPlace = BaritoneAPI.getSettings().allowParkourPlace.value;
                BaritoneAPI.getSettings().allowParkourPlace.value = true;
            } catch (Exception ignored) {}
        }

        if (mc.player == null || mc.world == null) { toggle(); return; }

        if (!hasItemInHotbar(Items.OBSIDIAN)) {
            int total = countItem(Items.OBSIDIAN);
            if (total > 0) warning("Obsidian is in inventory but not hotbar!");
        }
        int obsidianCount = getObsidianCount();
        if (obsidianCount < 10) {
            error("Need at least 10 obsidian (found " + obsidianCount + ")");
            toggle();
            return;
        }

        if (!hasItem(Items.FLINT_AND_STEEL)) warning("No flint & steel found — light manually.");

        if (useBaritone.get() && !hasThrowawayBlocks()) {
            warning("No throwaway blocks (dirt, cobblestone, etc.) found! Baritone may fail to bridge to the portal.");
        }

        Direction facing = mc.player.getHorizontalFacing();
        Direction right  = facing.rotateYClockwise();

        BlockPos feet     = mc.player.getBlockPos();
        boolean  adjusted = false;

        if (!mc.world.getBlockState(feet.down()).isFullCube(mc.world, feet.down())) {
            feet     = feet.up();
            adjusted = true;
        }

        BlockPos origin = feet.offset(facing, 2).offset(right, -1);

        portalFramePositions.add(origin.offset(right, 1));
        portalFramePositions.add(origin.offset(right, 2));
        portalFramePositions.add(origin.up(1));
        portalFramePositions.add(origin.up(2));
        portalFramePositions.add(origin.up(3));
        portalFramePositions.add(origin.offset(right, 3).up(1));
        portalFramePositions.add(origin.offset(right, 3).up(2));
        portalFramePositions.add(origin.offset(right, 3).up(3));
        portalFramePositions.add(origin.offset(right, 1).up(4));
        portalFramePositions.add(origin.offset(right, 2).up(4));

        if (adjusted) {
            BlockPos stepPos = feet.offset(facing, 1);
            if (mc.world.getBlockState(stepPos).isReplaceable()) portalFramePositions.add(stepPos);
        }

        boolean blocked = portalFramePositions.stream()
            .anyMatch(p -> !mc.world.getBlockState(p).isReplaceable());
        if (blocked) { error("Portal area is obstructed. Move slightly and try again."); toggle(); return; }

        long existing = portalFramePositions.stream()
            .filter(p -> mc.world.getBlockState(p).getBlock() == Blocks.OBSIDIAN)
            .count();
        if (existing >= 9) {
            info("Portal frame looks complete → attempting to light it.");
            placementIndex = portalFramePositions.size();
        }

        lastDimension = mc.world.getRegistryKey().getValue().toString();
        builtDimension = lastDimension;

        selectHotbarItem(Items.OBSIDIAN);
        info("Building minimal Nether portal...");
    }

    @Override
    public void onDeactivate() {
        portalFramePositions.clear();
        placementIndex   = 0;
        tickTimer        = 0;
        stopMovement();
        
        // Restore Baritone's settings to what they were before
        if (useBaritone.get()) {
            try {
                BaritoneAPI.getSettings().enterPortal.value = originalEnterPortal;
                BaritoneAPI.getSettings().allowPlace.value = originalAllowPlace;
                BaritoneAPI.getSettings().allowBreak.value = originalAllowBreak;
                BaritoneAPI.getSettings().allowParkour.value = originalAllowParkour;
                BaritoneAPI.getSettings().allowParkourPlace.value = originalAllowParkourPlace;
            } catch (Exception ignored) {}
        }
    }

    // ── Event Handlers ─────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (mc.player.isDead() || !mc.player.isAlive()) {
            stopMovement();
            toggle();
            return;
        }

        if (useBaritone.get() && autoRecycle.get() && cancelOnMovement.get() && isMovingManually()) {
            if (recycleState != RecycleState.IDLE || dimensionChangeCooldown > 0) {
                info("Recycle cancelled by manual movement.");
            }
            toggle();
            return;
        }

        try { 
            if (mc.world.getRegistryKey() == null) return; 
        } catch (Exception ignored) { 
            return; 
        }
        
        String currentDim;
        try {
            currentDim = mc.world.getRegistryKey().getValue().toString();
        } catch (Exception e) { 
            return; 
        }

        if (builtDimension.isEmpty()) builtDimension = currentDim;

        if (!currentDim.equals(lastDimension)) {
            lastDimension = currentDim;
            portalFramePositions.clear();
            if (useBaritone.get() && autoRecycle.get()) {
                dimensionChangeCooldown = dimensionSwitchCooldownTicks.get();
            }
            return;
        }

        if (dimensionChangeCooldown > 0) {
            dimensionChangeCooldown--;
            if (dimensionChangeCooldown == 0) {
                startRecycle();
            }
            return;
        }

        boolean recyclePressed = recycleKey.get().isPressed();
        if (useBaritone.get() && recyclePressed && !wasRecyclePressed) {
            if (recycleState == RecycleState.IDLE) {
                if (dimensionChangeCooldown > 0) info("Cannot recycle yet, waiting for dimension change cooldown.");
                else startRecycle();
            } else {
                recycleState = RecycleState.IDLE;
                stopMovement();
                info("Recycle cancelled.");
            }
        }
        wasRecyclePressed = recyclePressed;

        if (recycleState != RecycleState.IDLE) {
            handleRecycle();
            return;
        }

        if (isPlayerInPortal()) {
            stopMovement();
            if (useBaritone.get() && !autoRecycle.get() && !recycleKey.get().isSet() && recycleState == RecycleState.IDLE) {
                toggle();
            }
            return;
        }

        if (isPortalLit()) portalLitDetected = true;

        if (portalLitDetected || !currentDim.equals(builtDimension)) {
            if (currentDim.equals(builtDimension)) handlePhase2();
            return;
        }

        placementIndex = portalFramePositions.size();
        for (int i = 0; i < portalFramePositions.size(); i++) {
            BlockPos bp = portalFramePositions.get(i);
            if (!isChunkSafe(bp)) {
                placementIndex = portalFramePositions.size();
                break;
            }
            if (mc.world.getBlockState(bp).getBlock() != Blocks.OBSIDIAN) {
                placementIndex = i;
                break;
            }
        }

        if (placementIndex < portalFramePositions.size()) {
            if (mc.player.getInventory().main.isEmpty()) return;

            if (!mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
                FindItemResult obsidian = InvUtils.find(Items.OBSIDIAN);
                if (!obsidian.found()) { error("No obsidian found -> disabled."); toggle(); return; }
                if (obsidian.isHotbar()) mc.player.getInventory().selectedSlot = obsidian.slot();
                else InvUtils.move().from(obsidian.slot()).toHotbar(mc.player.getInventory().selectedSlot);
            }

            tickTimer++;
            if (tickTimer < placeDelay.get()) return;
            tickTimer = 0;

            BlockPos target = portalFramePositions.get(placementIndex);
            if (!isChunkSafe(target)) return;
            
            if (mc.world.getBlockState(target).getBlock() == Blocks.OBSIDIAN) { placementIndex++; return; }

            if (!mc.world.getBlockState(target).isReplaceable()) {
                mc.interactionManager.attackBlock(target, mc.player.getHorizontalFacing().getOpposite());
                mc.player.swingHand(Hand.MAIN_HAND);
                return;
            }

            // Pre-calculate the hit result and rotation target
            BlockHitResult hit = null;
            BlockPos lookTarget = target;

            if (airPlace.get()) {
                // Air place trick: target the air block directly from the top
                hit = new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false);
            } else {
                // Vanilla placement: find an adjacent solid block to click against
                Direction placeDir = null;
                BlockPos neighborPos = null;
                
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = target.offset(dir);
                    BlockState neighborState = getSafeBlockState(neighbor);
                    // Look for a solid, full block to click against
                    if (!neighborState.isReplaceable() && neighborState.isFullCube(mc.world, neighbor)) {
                        placeDir = dir.getOpposite();
                        neighborPos = neighbor;
                        break;
                    }
                }
                
                if (neighborPos != null) {
                    // Click the exact face of the neighboring solid block
                    Vec3d hitVec = Vec3d.ofCenter(neighborPos).add(Vec3d.of(placeDir.getVector()).multiply(0.5));
                    hit = new BlockHitResult(hitVec, placeDir, neighborPos, false);
                    lookTarget = neighborPos; // Rotate to look directly at the solid block
                } else {
                    // No valid neighbor found, and air-place is disabled. Abort.
                    error("Cannot place block without air-place (no solid neighbors found).");
                    toggle();
                    return;
                }
            }

            final BlockHitResult finalHit = hit;
            Rotations.rotate(Rotations.getYaw(lookTarget), Rotations.getPitch(lookTarget), () -> {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, finalHit);
                mc.player.swingHand(Hand.MAIN_HAND);
            });
            placementIndex++;
            return;
        }

        handlePhase2();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;
        
        if (render.get() && !portalFramePositions.isEmpty()) {
            for (int i = placementIndex; i < portalFramePositions.size(); i++) {
                BlockPos pos = portalFramePositions.get(i);
                if (!isChunkSafe(pos)) continue;
                if (!mc.world.getBlockState(pos).isReplaceable()) continue;

                Box box = new Box(pos);
                
                if (renderMode.get() == RenderMode.PULSE) {
                    renderPulseBox(event, box, lineColor.get());
                } else if (renderMode.get() == RenderMode.SPECTRAL) {
                    event.renderer.box(box, withAlpha(lineColor.get(), spectralFillAlpha.get()), lineColor.get(), ShapeMode.Both, 0);
                } else {
                    renderGlowLayers(event, box, lineColor.get());
                    event.renderer.box(box, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                }
            }
        }
    }

    // ── Building Logic ─────────────────────────────────────────────
    private void handlePhase2() {
        if (!portalLitDetected) {
            finishTimer = 0;
            if (tickTimer++ >= 10) { lightPortal(); tickTimer = 0; }
            return;
        }

        if (useBaritone.get() && entryMode.get() != EntryMode.None) {
            moveToPortal();
        } else {
            if (finishTimer++ >= finishDelay.get()) {
                info("PortalMaker finished.");
                toggle();
            }
        }
    }

    // ── Recycle Logic ──────────────────────────────────────────────
    private void handleRecycle() {
        if (!useBaritone.get()) {
            recycleState = RecycleState.IDLE;
            return;
        }

        if (mc.player == null || mc.world == null) {
            recycleState = RecycleState.IDLE;
            stopMovement();
            return;
        }

        if (mc.player.isDead() || !mc.player.isAlive()) {
            recycleState = RecycleState.IDLE;
            stopMovement();
            toggle();
            return;
        }

        switch (recycleState) {
            case STEPPING_OUT -> {
                if (stepOutTarget == null) { 
                    recycleState = RecycleState.WAITING; 
                    return; 
                }

                // Wait for Baritone to finish pathing
                if (isBaritoneIdle()) {
                    // If we are safely out of the portal, start the wait timer
                    if (!isPlayerInPortal()) {
                        stopMovement();
                        recycleState = RecycleState.WAITING;
                        return;
                    } else {
                        // Still somehow in the portal, try pathing out again
                        moveTo(stepOutTarget);
                    }
                }
                // If Baritone is not idle, it is still walking. Do nothing and let it finish.
            }
            case WAITING -> {
                stopMovement(); 
                if (recycleWaitTimer-- <= 0) {
                    recycleState = RecycleState.RE_ENTERING;
                    info("Wait complete. Re-entering portal...");
                }
            }
            case RE_ENTERING -> {
                // If we entered the portal, we are done
                if (isPlayerInPortal()) {
                    stopMovement();
                    toggle();
                    return;
                }
                
                // Wait for Baritone to finish pathing
                if (isBaritoneIdle()) {
                    // If we aren't in the portal yet, issue the path command again
                    moveTo(recycleTarget);
                }
            }
            default -> {}
        }
    }

    private void startRecycle() {
        if (!useBaritone.get()) return;
        
        if (mc.player == null || mc.world == null) {
            recycleState = RecycleState.IDLE;
            return;
        }
        
        BlockPos playerPos = mc.player.getBlockPos();
        if (!isChunkSafe(playerPos)) {
            dimensionChangeCooldown = 10; 
            return;
        }
        
        setupRecycleTarget();
        recycleState = RecycleState.STEPPING_OUT;
        recycleWaitTimer = recycleDelaySeconds.get() * 20;
        info("Initiating portal recycle...");
    }

    private void setupRecycleTarget() {
        if (mc.player == null || mc.world == null) {
            recycleTarget = null;
            stepOutTarget = null;
            return;
        }

        BlockPos pos = mc.player.getBlockPos();
        
        if (!getSafeBlockState(pos).isOf(Blocks.NETHER_PORTAL)) {
            for (BlockPos p : BlockPos.iterate(pos.add(-5, -5, -5), pos.add(5, 5, 5))) {
                if (!isChunkSafe(p)) continue; 
                if (getSafeBlockState(p).isOf(Blocks.NETHER_PORTAL)) {
                    pos = p;
                    break;
                }
            }
        }

        if (getSafeBlockState(pos).isOf(Blocks.NETHER_PORTAL)) {
            BlockState state = getSafeBlockState(pos);
            Direction.Axis axis = state.contains(net.minecraft.state.property.Properties.HORIZONTAL_AXIS) 
                ? state.get(net.minecraft.state.property.Properties.HORIZONTAL_AXIS) 
                : Direction.Axis.X;

            int minC = axis == Direction.Axis.X ? pos.getX() : pos.getZ();
            int maxC = minC;

            int maxIterations = 20;
            while (maxIterations-- > 0) {
                BlockPos checkPos = axis == Direction.Axis.X 
                    ? new BlockPos(minC - 1, pos.getY(), pos.getZ()) 
                    : new BlockPos(pos.getX(), pos.getY(), minC - 1);
                if (!isChunkSafe(checkPos)) break;
                if (!getSafeBlockState(checkPos).isOf(Blocks.NETHER_PORTAL)) break;
                minC--;
            }

            maxIterations = 20;
            while (maxIterations-- > 0) {
                BlockPos checkPos = axis == Direction.Axis.X 
                    ? new BlockPos(maxC + 1, pos.getY(), pos.getZ()) 
                    : new BlockPos(pos.getX(), pos.getY(), maxC + 1);
                if (!isChunkSafe(checkPos)) break;
                if (!getSafeBlockState(checkPos).isOf(Blocks.NETHER_PORTAL)) break;
                maxC++;
            }

            double mid = (minC + maxC + 1) / 2.0;
            if (axis == Direction.Axis.X) {
                recycleTarget = new Vec3d(mid, pos.getY(), pos.getZ() + 0.5);
                Vec3d o1 = recycleTarget.add(0, 0, 2.0);
                Vec3d o2 = recycleTarget.add(0, 0, -2.0);
                if (isAreaClear(o1)) stepOutTarget = o1;
                else if (isAreaClear(o2)) stepOutTarget = o2;
                else stepOutTarget = o1;
            } else {
                recycleTarget = new Vec3d(pos.getX() + 0.5, pos.getY(), mid);
                Vec3d o1 = recycleTarget.add(2.0, 0, 0);
                Vec3d o2 = recycleTarget.add(-2.0, 0, 0);
                if (isAreaClear(o1)) stepOutTarget = o1;
                else if (isAreaClear(o2)) stepOutTarget = o2;
                else stepOutTarget = o1;
            }
        } else {
            recycleTarget = mc.player.getPos();
            stepOutTarget = mc.player.getPos().add(mc.player.getRotationVector().multiply(-2.0));
        }
    }

    private boolean isAreaClear(Vec3d pos) {
        if (mc.world == null) return false;
        BlockPos bp = BlockPos.ofFloored(pos);
        if (!isChunkSafe(bp)) return false;
        return getSafeBlockState(bp).isReplaceable() && getSafeBlockState(bp.up()).isReplaceable();
    }

    // ── Portal Helpers ─────────────────────────────────────────────
    private boolean isPlayerInPortal() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos feet = mc.player.getBlockPos();
        if (!isChunkSafe(feet)) return false;
        return getSafeBlockState(feet).isOf(Blocks.NETHER_PORTAL) ||
               getSafeBlockState(feet.up()).isOf(Blocks.NETHER_PORTAL);
    }

    private void lightPortal() {
        if (portalFramePositions.isEmpty()) return;
        if (!selectHotbarItem(Items.FLINT_AND_STEEL)) { warning("Cannot find flint & steel in hotbar."); return; }

        BlockPos bottom1 = portalFramePositions.get(0);
        BlockPos bottom2 = portalFramePositions.get(1);

        for (BlockPos pos : new BlockPos[]{bottom1, bottom2}) {
            if (!isChunkSafe(pos)) continue;
            if (getSafeBlockState(pos.up()).isAir()) {
                Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos).add(0, 0.5, 0), Direction.UP, pos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
                break;
            }
        }
    }

    private boolean isPortalLit() {
        if (portalFramePositions.size() < 2) return false;
        BlockPos p1 = portalFramePositions.get(0).up();
        BlockPos p2 = portalFramePositions.get(1).up();

        if (!isChunkSafe(p1) || !isChunkSafe(p2)) {
            return portalLitDetected;
        }

        return mc.world.getBlockState(p1).getBlock() == Blocks.NETHER_PORTAL ||
               mc.world.getBlockState(p2).getBlock() == Blocks.NETHER_PORTAL;
    }

    // ── Baritone Movement Engine ───────────────────────────────────
    private void moveToPortal() {
        if (portalFramePositions.size() < 2) return;
        moveTo(getPortalOpeningCenter());
    }

    private void moveTo(Vec3d target) {
        if (mc.player == null || mc.world == null || target == null) return;
        if (mc.player.isDead() || !mc.player.isAlive()) {
            stopMovement();
            toggle();
            return;
        }

        // Use BlockPos.ofFloored to accurately get the block coordinates from the Vec3d.
        // This prevents rounding errors that could target the block adjacent to the portal.
        BlockPos targetPos = BlockPos.ofFloored(target.x, target.y, target.z);
        
        // Use the Baritone API directly to avoid command manager chat spam (prevents coordinate leaks)
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        
        // Only issue the path command if Baritone isn't already trying to path there
        // GoalBlock is used because it forces the player's feet into the exact portal block,
        // ensuring the teleport triggers reliably.
        if (isBaritoneIdle()) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(targetPos));
        }
    }

    private Vec3d getPortalOpeningCenter() {
        BlockPos p1 = portalFramePositions.get(0).up();
        BlockPos p2 = portalFramePositions.get(1).up();
        return new Vec3d(
            (p1.getX() + p2.getX()) / 2.0 + 0.5,
             p1.getY(),
            (p1.getZ() + p2.getZ()) / 2.0 + 0.5
        );
    }

    // ── Placement Helpers ──────────────────────────────────────────
    private void stopMovement() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        
        // Stop Baritone pathing silently
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            IPathingBehavior pathing = baritone.getPathingBehavior();
            if (pathing != null && (pathing.isPathing() || pathing.hasPath() || pathing.getInProgress().isPresent())) {
                pathing.cancelEverything();
            }
        } catch (Exception ignored) {}
    }

    // ── Render Helpers ─────────────────────────────────────────────
    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int    layers    = glowLayers.get();
        double spread    = glowSpread.get();
        int    baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            double expansion  = spread * i;
            int    layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double) (i - 1) / layers)));
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

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    // ── Utility Helpers ────────────────────────────────────────────
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

    private boolean selectHotbarItem(Item targetItem) {
        if (mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == targetItem) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }

    private boolean hasItemInHotbar(Item targetItem) {
        if (mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == targetItem) return true;
        }
        return false;
    }

    public int getObsidianCount() {
        return countItem(Items.OBSIDIAN);
    }

    private int countItem(Item targetItem) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(targetItem)) count += stack.getCount();
        }
        ItemStack offhand = mc.player.getOffHandStack();
        if (offhand.isOf(targetItem)) count += offhand.getCount();
        return count;
    }

    private boolean hasItem(Item targetItem) {
        return countItem(targetItem) > 0;
    }

    private boolean hasThrowawayBlocks() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            Item item = stack.getItem();
            if (item == Items.DIRT || item == Items.COBBLESTONE || item == Items.NETHERRACK || 
                item == Items.STONE || item == Items.GRASS_BLOCK || item == Items.DEEPSLATE ||
                item == Items.COBBLED_DEEPSLATE || item == Items.SAND || item == Items.GRAVEL ||
                item == Items.GLASS || item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS) {
                return true;
            }
        }
        return false;
    }

    private boolean isMovingManually() {
        if (mc.currentScreen != null) return false;
        return Input.isKeyPressed(GLFW.GLFW_KEY_W) || 
               Input.isKeyPressed(GLFW.GLFW_KEY_A) ||
               Input.isKeyPressed(GLFW.GLFW_KEY_S) || 
               Input.isKeyPressed(GLFW.GLFW_KEY_D) ||
               Input.isKeyPressed(GLFW.GLFW_KEY_SPACE) || 
               Input.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT) ||
               Input.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }
}