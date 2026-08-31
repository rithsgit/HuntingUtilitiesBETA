package com.example.addon.modules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.mixin.InteractionAccessor;
import com.example.addon.utils.Hotbar;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Core packet mining, queuing, and bursting logic provided by Arkie.
 * Refactored for readability and maintainability.
 */
public class Datamine extends Module {
    // --- Constants (Synced from MiningTweaks) ---
    private static final double BREAK_THRESHOLD = 0.7;
    private static final double REACH = 6.0;
    private static final long RESTART_DELAY = 300;
    private static final long BURST_PAUSE = 275;
    private static final int BURST_COUNT = 22;
    private static final int FAKE_BLOCK_HEIGHT = 1024;

    // --- Enums ---
    public enum MiningMode { Packet, Normal }
    public enum SwapMode { Normal, Silent }
    public enum HighlightStyle { GLOW, SPECTRAL, PULSE }
    public enum NukerMode { Disabled, Tunnel, Hole, Excavator }
    public enum TunnelShape { x1_2, x2_2, x3_3, x4_4, x5_5 }
    public enum ExcavatorShape { Box, Sphere, Flat }
    public enum ListMode { Whitelist, Blacklist }

    // --- Setting Groups ---
    private final SettingGroup sgMining = this.settings.getDefaultGroup();
    private final SettingGroup sgNuker = this.settings.createGroup("Nuker");
    private final SettingGroup sgTools = this.settings.createGroup("Tools");
    private final SettingGroup sgVanilla = this.settings.createGroup("Vanilla Bypass");
    private final SettingGroup sgCollect = this.settings.createGroup("Auto-Collect");
    private final SettingGroup sgVisuals = this.settings.createGroup("Visuals");

    // --- Mining Settings ---
    private final Setting<MiningMode> miningMode = sgMining.add(new EnumSetting.Builder<MiningMode>()
        .name("mining-mode")
        .description("Packet uses exploit bursts for instant breaking. Normal uses standard vanilla breaking.")
        .defaultValue(MiningMode.Packet)
        .build()
    );

    private final Setting<Boolean> instantRemine = sgMining.add(new BoolSetting.Builder()
        .name("instant-remine")
        .description("Automatically mines the last broken block when it is replaced.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> validationTicks = sgMining.add(new IntSetting.Builder()
        .name("validation-wait")
        .description("Checks whether the block was mined after this many ticks.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> maxRetries = sgMining.add(new IntSetting.Builder()
        .name("maximum-retries")
        .description("Maximum mining retries after a block fails to break.")
        .defaultValue(1)
        .min(0)
        .sliderMax(2)
        .build()
    );

    private final Setting<Integer> retryCooldown = sgMining.add(new IntSetting.Builder()
        .name("retry-cooldown")
        .description("Delay in ticks before starting another mining attempt.")
        .defaultValue(6)
        .min(1)
        .sliderMax(12)
        .build()
    );

    // --- Nuker Settings ---
    private final Setting<NukerMode> nukerMode = sgNuker.add(new EnumSetting.Builder<NukerMode>()
        .name("nuker-mode")
        .description("Whether to use the built-in nuker to automatically queue blocks.")
        .defaultValue(NukerMode.Disabled)
        .build()
    );

    private final Setting<TunnelShape> tunnelShape = sgNuker.add(new EnumSetting.Builder<TunnelShape>()
        .name("tunnel-shape")
        .description("The shape of the tunnel to mine.")
        .defaultValue(TunnelShape.x3_3)
        .visible(() -> nukerMode.get() == NukerMode.Tunnel)
        .build()
    );

    private final Setting<ExcavatorShape> excavatorShape = sgNuker.add(new EnumSetting.Builder<ExcavatorShape>()
        .name("excavator-shape")
        .description("The shape of the area to scan and excavate.")
        .defaultValue(ExcavatorShape.Box)
        .visible(() -> nukerMode.get() == NukerMode.Excavator)
        .build()
    );

    private final Setting<Integer> excavatorRange = sgNuker.add(new IntSetting.Builder()
        .name("excavator-range")
        .description("Radius in blocks to search for whitelisted blocks to excavate.")
        .defaultValue(16)
        .min(4)
        .sliderMax(64)
        .visible(() -> nukerMode.get() == NukerMode.Excavator)
        .build()
    );

    private final Setting<Boolean> excavatorIgnoreFloor = sgNuker.add(new BoolSetting.Builder()
        .name("ignore-floor")
        .description("Prevents the excavator from mining any blocks directly below your feet level.")
        .defaultValue(true)
        .visible(() -> nukerMode.get() == NukerMode.Excavator)
        .build()
    );

    private final Setting<ListMode> listMode = sgNuker.add(new EnumSetting.Builder<ListMode>()
        .name("list-mode")
        .description("Whether to treat the block list as a whitelist or blacklist.")
        .defaultValue(ListMode.Whitelist)
        .visible(() -> nukerMode.get() == NukerMode.Tunnel || nukerMode.get() == NukerMode.Hole)
        .build()
    );

    private final Setting<List<Block>> blockList = sgNuker.add(new BlockListSetting.Builder()
        .name("block-list")
        .description("Which blocks to target or ignore. Excavator strictly uses this as a whitelist.")
        .visible(() -> nukerMode.get() != NukerMode.Disabled)
        .build()
    );

    // --- Tool Settings ---
    private final Setting<SwapMode> swapMode = sgTools.add(new EnumSetting.Builder<SwapMode>()
        .name("auto-swap")
        .description("How to switch tools. Silent switches server-side without changing your visible hotbar.")
        .defaultValue(SwapMode.Normal)
        .build()
    );

    private final Setting<Boolean> silentSwing = sgTools.add(new BoolSetting.Builder()
        .name("silent-swing")
        .description("Hides the client-side hand swing animation. (Server still receives the packet).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> toolSyncDelay = sgTools.add(new IntSetting.Builder()
        .name("tool-sync-delay")
        .description("Delay in ticks after switching tools before mining starts.")
        .defaultValue(3)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> durabilityProtection = sgTools.add(new BoolSetting.Builder()
        .name("durability-protection")
        .description("Prevents the auto-tool feature from selecting tools that are about to break.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> durabilityThreshold = sgTools.add(new IntSetting.Builder()
        .name("durability-threshold")
        .description("The minimum durability remaining for a tool to be used.")
        .defaultValue(5)
        .min(1)
        .sliderMax(50)
        .visible(durabilityProtection::get)
        .build()
    );

    // --- Vanilla Bypass Settings ---
    private final Setting<Integer> vanilla = sgVanilla.add(new IntSetting.Builder()
        .name("vanilla-cutoff")
        .description("Uses vanilla mining for breaks within this limit in ticks. 0 = disabled.")
        .defaultValue(1)
        .min(0)
        .sliderMax(5)
        .build()
    );

    // --- Auto-Collect Settings ---
    private final Setting<Boolean> autoCollect = sgCollect.add(new BoolSetting.Builder()
        .name("auto-collect")
        .description("Uses Baritone to pathfind and collect dropped items when the queue is empty.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> collectRange = sgCollect.add(new IntSetting.Builder()
        .name("collect-range")
        .description("Maximum distance to search for dropped items.")
        .defaultValue(16)
        .min(4)
        .sliderMax(64)
        .visible(autoCollect::get)
        .build()
    );

    private final Setting<List<Item>> collectWhitelist = sgCollect.add(new ItemListSetting.Builder()
        .name("collect-whitelist")
        .description("Only collects the specified items. Leave empty to collect all items.")
        .visible(autoCollect::get)
        .build()
    );

    private final Setting<Integer> collectDelay = sgCollect.add(new IntSetting.Builder()
        .name("collect-delay")
        .description("Delay in ticks after an item drops before auto-collect activates.")
        .defaultValue(10)
        .min(0)
        .sliderMax(40)
        .visible(autoCollect::get)
        .build()
    );

    private final Setting<Integer> gracePeriod = sgCollect.add(new IntSetting.Builder()
        .name("collect-grace-period")
        .description("Seconds after a whitelisted item drops to actively search for it.")
        .defaultValue(5)
        .min(1)
        .sliderMax(15)
        .visible(autoCollect::get)
        .build()
    );

    // --- Visual Settings ---
    private final Setting<Boolean> render = sgVisuals.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders packet-mining progress and queued blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<HighlightStyle> highlightStyle = sgVisuals.add(new EnumSetting.Builder<HighlightStyle>()
        .name("highlight-style")
        .description("The style to highlight blocks with.")
        .defaultValue(HighlightStyle.GLOW)
        .visible(render::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgVisuals.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How mining progress and queued blocks are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(() -> render.get() && highlightStyle.get() == HighlightStyle.GLOW)
        .build()
    );

    private final Setting<Integer> glowLayers = sgVisuals.add(new IntSetting.Builder()
        .name("glow-layers")
        .defaultValue(4)
        .min(1)
        .sliderMax(8)
        .visible(() -> render.get() && (highlightStyle.get() == HighlightStyle.GLOW || highlightStyle.get() == HighlightStyle.PULSE))
        .build()
    );

    private final Setting<Double> glowSpread = sgVisuals.add(new DoubleSetting.Builder()
        .name("glow-spread")
        .defaultValue(0.05)
        .min(0.01)
        .sliderMax(0.2)
        .visible(() -> render.get() && (highlightStyle.get() == HighlightStyle.GLOW || highlightStyle.get() == HighlightStyle.PULSE))
        .build()
    );

    private final Setting<Integer> glowBaseAlpha = sgVisuals.add(new IntSetting.Builder()
        .name("glow-base-alpha")
        .defaultValue(50)
        .min(4)
        .sliderMax(150)
        .visible(() -> render.get() && highlightStyle.get() == HighlightStyle.GLOW)
        .build()
    );

    private final Setting<Double> pulseSpeed = sgVisuals.add(new DoubleSetting.Builder()
        .name("pulse-speed")
        .description("Pulse cycle speed. 1.0 = one full fade in/out per second.")
        .defaultValue(1.0)
        .min(0.1)
        .max(5.0)
        .sliderMax(3.0)
        .visible(() -> render.get() && highlightStyle.get() == HighlightStyle.PULSE)
        .build()
    );

    private final Setting<Integer> pulseMinAlpha = sgVisuals.add(new IntSetting.Builder()
        .name("pulse-min-alpha")
        .description("Lowest alpha reached during the pulse (0 = invisible).")
        .defaultValue(15)
        .min(0)
        .max(255)
        .sliderMax(100)
        .visible(() -> render.get() && highlightStyle.get() == HighlightStyle.PULSE)
        .build()
    );

    private final Setting<Integer> pulseMaxAlpha = sgVisuals.add(new IntSetting.Builder()
        .name("pulse-max-alpha")
        .description("Peak alpha reached during the pulse.")
        .defaultValue(220)
        .min(15)
        .max(255)
        .sliderMax(255)
        .visible(() -> render.get() && highlightStyle.get() == HighlightStyle.PULSE)
        .build()
    );

    private final Setting<Integer> spectralLineAlpha = sgVisuals.add(new IntSetting.Builder()
        .name("spectral-line-alpha")
        .defaultValue(255)
        .min(0)
        .sliderMax(255)
        .visible(() -> render.get() && highlightStyle.get() == HighlightStyle.SPECTRAL)
        .build()
    );

    private final Setting<Integer> spectralFillAlpha = sgVisuals.add(new IntSetting.Builder()
        .name("spectral-fill-alpha")
        .defaultValue(15)
        .min(0)
        .sliderMax(255)
        .visible(() -> render.get() && highlightStyle.get() == HighlightStyle.SPECTRAL)
        .build()
    );

    private final Setting<Double> spectralExpand = sgVisuals.add(new DoubleSetting.Builder()
        .name("spectral-expand")
        .defaultValue(0.05)
        .min(0)
        .sliderMax(0.5)
        .visible(() -> render.get() && highlightStyle.get() == HighlightStyle.SPECTRAL)
        .build()
    );

    private final Setting<SettingColor> queueColor = sgVisuals.add(new ColorSetting.Builder()
        .name("queue-color")
        .description("The color for queued blocks.")
        .defaultValue(new SettingColor(0, 200, 255, 200))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> primaryColor = sgVisuals.add(new ColorSetting.Builder()
        .name("primary-color")
        .description("The color for the primary target.")
        .defaultValue(new SettingColor(0, 255, 100, 255))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> secondaryColor = sgVisuals.add(new ColorSetting.Builder()
        .name("secondary-color")
        .description("The color for the secondary target.")
        .defaultValue(new SettingColor(180, 0, 255, 200))
        .visible(render::get)
        .build()
    );

    // --- Variables & State ---
    private final Deque<Request> queue = new ArrayDeque<>();
    private final Deque<Retry> waiting = new ArrayDeque<>();

    private Target primary;
    private Target secondary;
    private Request last;

    private int tick = 0;
    private long ready = 0;
    private long stopped = 0;
    private boolean fast = false;

    private long lastMineTime = 0;
    private final Set<Integer> seenItems = new HashSet<>();
    private boolean sendingCustomPacket = false;
    private boolean swapped = false;

    private BlockPos excavatorTarget = null;

    public Datamine() {
        super(Tim.CATEGORY, "datamine", "Queues blocks for fast packet mining with double break.");
    }

    // --- Lifecycle ---
    @Override
    public void onActivate() {
        this.reset();
    }

    @Override
    public void onDeactivate() {
        if (this.primary != null && !this.primary.finished) {
            this.action(this.primary, PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, this.primary.pos, this.primary.side);
        }
        if (this.secondary != null && !this.secondary.finished) {
            this.action(this.secondary, PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, this.secondary.pos, this.secondary.side);
        }

        if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
        }

        this.reset();
    }

    // --- Event Handlers ---
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (this.miningMode.get() == MiningMode.Normal || sendingCustomPacket) return;

        if (event.packet instanceof PlayerActionC2SPacket packet) {
            PlayerActionC2SPacket.Action action = packet.getAction();
            if (action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK ||
                action == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK ||
                action == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK) {

                if (this.isTracked(packet.getPos())) {
                    event.cancel();
                }

                if (this.instantRemine.get() && this.last != null && action == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK && packet.getPos().equals(this.last.pos)) {
                    event.cancel();
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null || this.mc.interactionManager == null) return;

        this.tick++;

        if (this.nukerMode.get() != NukerMode.Disabled) {
            this.doNuking();
        }

        this.promote();
        this.clean();

        this.update(this.secondary);
        this.update(this.primary);

        this.fill();
        this.remine();
        
        this.checkForNewItems();
        this.doAutoCollect();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.render.get()) return;

        for (Request request : this.queue) {
            this.renderBox(event, new Box(request.pos), this.queueColor.get());
        }

        if (this.secondary != null) {
            this.renderTarget(event, this.secondary, this.secondaryColor.get());
        }
        if (this.primary != null) {
            this.renderTarget(event, this.primary, this.primaryColor.get());
        }
    }

    // --- Nuker Logic ---
    private void doNuking() {
        if (this.mc.player == null || this.mc.world == null) return;

        BlockPos basePos = this.mc.player.getBlockPos();
        Direction facing = this.mc.player.getHorizontalFacing();

        if (this.nukerMode.get() == NukerMode.Tunnel) {
            int width = 1, height = 2;
            TunnelShape shape = this.tunnelShape.get();
            if (shape == TunnelShape.x2_2) { width = 2; height = 2; }
            else if (shape == TunnelShape.x3_3) { width = 3; height = 3; }
            else if (shape == TunnelShape.x4_4) { width = 4; height = 4; }
            else if (shape == TunnelShape.x5_5) { width = 5; height = 5; }

            int halfW = width / 2;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offsetX = x - halfW;
                    int offsetY = y; // Start at feet level and go up
                    BlockPos pos = this.getTunnelPos(basePos, facing, offsetX, offsetY);
                    this.tryMine(pos);
                }
            }
        } else if (this.nukerMode.get() == NukerMode.Hole) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = basePos.add(x, -1, z);
                    this.tryMine(pos);
                }
            }
        } else if (this.nukerMode.get() == NukerMode.Excavator) {
            this.doExcavating();
        }
    }

    private BlockPos getTunnelPos(BlockPos base, Direction facing, int offsetX, int offsetY) {
        if (facing == Direction.NORTH) return base.add(offsetX, offsetY, -1);
        if (facing == Direction.SOUTH) return base.add(offsetX, offsetY, 1);
        if (facing == Direction.WEST) return base.add(-1, offsetY, offsetX);
        if (facing == Direction.EAST) return base.add(1, offsetY, offsetX);
        return base;
    }

    private void tryMine(BlockPos pos) {
        if (pos == null) return;
        if (this.isTracked(pos)) return;

        BlockState state = this.mc.world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof FluidBlock) return;

        if (!this.isBlockAllowed(state.getBlock())) return;

        if (!this.breakable(pos, state)) return;

        Direction side = this.face(pos, Direction.UP);
        this.mine(pos, side);
    }

    private boolean isBlockAllowed(Block block) {
        if (blockList.get().isEmpty()) return listMode.get() == ListMode.Blacklist;
        if (listMode.get() == ListMode.Whitelist) {
            return blockList.get().contains(block);
        } else {
            return !blockList.get().contains(block);
        }
    }

    // --- Excavator Logic ---
    private void doExcavating() {
        // Pause Baritone if we are actively mining something
        if (this.primary != null || this.secondary != null || !this.queue.isEmpty()) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        // Find a new target if we don't have one or the current one is invalid
        if (this.excavatorTarget == null || !this.isExcavatable(this.excavatorTarget)) {
            this.excavatorTarget = this.findExcavatorTarget();
        }

        if (this.excavatorTarget == null) {
            // No targets found, stop Baritone
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        // If we are close enough to mine it, cancel Baritone and queue the block
        if (this.reachable(this.excavatorTarget)) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            this.tryMine(this.excavatorTarget);
            this.excavatorTarget = null; // Look for next target next tick
        } else {
            // Path to the target if we aren't already
            if (!BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                GoalNear goal = new GoalNear(this.excavatorTarget, 1);
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(goal);
            }
        }
    }

    private boolean isExcavatable(BlockPos pos) {
        if (pos == null) return false;
        
        // Prevent mining any floor blocks if the player is on the ground
        if (this.excavatorIgnoreFloor.get() && this.mc.player.isOnGround()) {
            if (pos.getY() < this.mc.player.getBlockPos().getY()) {
                return false;
            }
        }

        BlockState state = this.mc.world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof FluidBlock) return false;
        if (state.getHardness(this.mc.world, pos) < 0.0F) return false; // Unbreakable
        if (this.blockList.get().isEmpty()) return false; // Strictly whitelist only
        return this.blockList.get().contains(state.getBlock());
    }

    private BlockPos findExcavatorTarget() {
        BlockPos playerPos = this.mc.player.getBlockPos();
        int range = this.excavatorRange.get();
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        int rangeSq = range * range;

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    if (this.excavatorShape.get() == ExcavatorShape.Sphere) {
                        if (x*x + y*y + z*z > rangeSq) continue;
                    } else if (this.excavatorShape.get() == ExcavatorShape.Flat) {
                        if (y != 0) continue;
                    }

                    BlockPos pos = playerPos.add(x, y, z);
                    if (this.isTracked(pos)) continue;
                    if (!this.isExcavatable(pos)) continue;
                    
                    double dist = this.mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (dist < closestDist) {
                        closestDist = dist;
                        closest = pos.toImmutable();
                    }
                }
            }
        }
        return closest;
    }

    // --- Queue & Target Management ---
    public void mine(BlockPos pos, Direction side) {
        if (this.mc.player == null || this.mc.world == null || this.mc.interactionManager == null || pos == null || side == null) return;

        pos = pos.toImmutable();
        if (this.isTracked(pos)) return;

        BlockState state = this.mc.world.getBlockState(pos);
        if (!this.breakable(pos, state)) return;

        this.queue.addLast(new Request(pos, side, 0));
        this.fill();
    }

    private void reset() {
        this.queue.clear();
        this.waiting.clear();
        this.primary = null;
        this.secondary = null;
        this.last = null;
        this.tick = 0;
        this.ready = 0;
        this.stopped = 0;
        this.fast = false;
        this.lastMineTime = 0;
        this.seenItems.clear();
        this.sendingCustomPacket = false;
        this.swapped = false;
        this.excavatorTarget = null;
    }

    private void promote() {
        if (this.waiting.isEmpty()) return;

        long now = System.currentTimeMillis();
        Iterator<Retry> iterator = this.waiting.iterator();

        while (iterator.hasNext()) {
            Retry retry = iterator.next();
            if (now < retry.ready) continue;

            BlockState state = this.mc.world.getBlockState(retry.request.pos);
            iterator.remove();

            if (!this.breakable(retry.request.pos, state)) continue;
            this.queue.addFirst(retry.request);
        }
    }

    private void clean() {
        this.queue.removeIf(request -> !this.breakable(request.pos, this.mc.world.getBlockState(request.pos)));
        this.waiting.removeIf(retry -> !this.breakable(retry.request.pos, this.mc.world.getBlockState(retry.request.pos)));
    }

    private void fill() {
        if (this.queue.isEmpty()) return;

        if (this.primary == null) {
            if (!this.startable()) return;

            Target target = this.next();
            if (target != null) this.begin(target);
        }

        if (this.primary == null || this.secondary != null || this.queue.isEmpty() || !this.parkable()) return;

        Target target = this.next();
        if (target == null) return;

        this.park();
        this.begin(target);
    }

    private Target next() {
        while (!this.queue.isEmpty()) {
            Request request = this.queue.removeFirst();

            BlockState state = this.mc.world.getBlockState(request.pos);
            if (!this.breakable(request.pos, state)) continue;

            Direction side = this.face(request.pos, request.side);
            return new Target(request, state, side);
        }
        return null;
    }

    private boolean startable() {
        return this.fast || System.currentTimeMillis() - this.stopped > RESTART_DELAY;
    }

    private boolean parkable() {
        return this.secondary == null
            && System.currentTimeMillis() >= this.ready
            && this.primary != null && !this.primary.arming
            && !this.primary.finished && !this.primary.instant
            && this.progress(this.primary) < 1.0;
    }

    private void park() {
        Target target = this.primary;
        this.action(target, PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, target.pos, target.side);

        Target parked = new Target(
            new Request(target.pos, target.side, target.retry),
            target.state, target.side
        );

        long now = System.currentTimeMillis();

        parked.started = now;
        parked.updated = now;

        parked.slot = target.slot;
        parked.delta = this.delta(parked);
        parked.work = Math.max(0.0, parked.delta);

        parked.instant = parked.delta >= 1.0F;
        parked.burst = target.burst;

        this.secondary = parked;
        this.primary = null;
    }

    private void remove(Target target, boolean confirmed) {
        if (target == this.primary) {
            this.primary = null;
        }

        if (target == this.secondary) {
            this.secondary = null;
            this.ready = System.currentTimeMillis();
            this.ready += (confirmed ? 50L : BURST_PAUSE);
        }

        // Revert slot if the target was removed while arming
        if (target.arming && this.swapped) {
            this.revertSlot();
        }
    }

    public boolean isTracked(BlockPos pos) {
        if (this.primary != null && this.primary.pos.equals(pos)) return true;
        if (this.secondary != null && this.secondary.pos.equals(pos)) return true;
        for (Request request : this.queue) {
            if (request.pos.equals(pos)) return true;
        }
        for (Retry retry : this.waiting) {
            if (retry.request.pos.equals(pos)) return true;
        }
        return false;
    }

    // --- Server Block Update Hook ---
    public void onServerBlockUpdate(BlockPos pos, BlockState state) {
        if (pos == null || state == null) return;
        BlockPos immutablePos = pos.toImmutable();

        if (this.primary != null && this.primary.pos.equals(immutablePos)) {
            if (state.isAir()) {
                this.confirm(this.primary);
            } else if (!this.breakable(immutablePos, state)) {
                this.remove(this.primary, false);
            }
        }

        if (this.secondary != null && this.secondary.pos.equals(immutablePos)) {
            if (state.isAir()) {
                this.confirm(this.secondary);
            } else if (!this.breakable(immutablePos, state)) {
                this.remove(this.secondary, false);
            }
        }

        this.queue.removeIf(request -> request.pos.equals(immutablePos) && !this.breakable(immutablePos, state));
        this.waiting.removeIf(retry -> retry.request.pos.equals(immutablePos) && !this.breakable(immutablePos, state));

        if (this.instantRemine.get()
                && this.last != null
                && this.last.pos.equals(immutablePos)
                && this.breakable(immutablePos, state)) {
            this.remine();
        }
    }

    // --- Vanilla Bypass ---
    public boolean bypass(BlockPos pos) {
        if (this.mc.player == null ||
            this.mc.world == null || pos == null ||
            this.vanilla.get() <= 0 || this.isTracked(pos)) {
            return false;
        }

        BlockState state = this.mc.world.getBlockState(pos);
        if (!this.breakable(pos, state)) return false;

        float delta = state.calcBlockBreakingDelta(
            this.mc.player, this.mc.world, pos
        );

        return delta >= 1.0F / this.vanilla.get();
    }

    // --- Mining Logic ---
    private void begin(Target target) {
        target.side = this.face(target.pos, target.side);
        target.slot = this.best(target.state, target.pos);

        this.primary = target;

        this.select(target.slot);

        if (this.swapped) {
            target.arming = true;
            target.arm = this.tick + this.toolSyncDelay.get();
            // Do NOT revert slot here. We want the server to keep the tool equipped during arming.
            return;
        }

        this.start(target);
    }

    private void start(Target target) {
        long now = System.currentTimeMillis();

        target.arming = false;
        target.started = now;
        target.updated = now;

        target.delta = this.delta(target);
        target.work = Math.max(0.0, target.delta);

        target.instant = target.delta >= 1.0F;

        this.packet(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, target.pos, target.side);

        if (this.miningMode.get() == MiningMode.Packet && !target.instant) {
            this.packet(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, this.fake(target.pos), target.side);
        }

        if (this.silentSwing.get()) {
            this.mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        } else {
            this.mc.player.swingHand(Hand.MAIN_HAND);
        }

        if (target.instant) this.finish(target);
        this.revertSlot();
    }

    private void update(Target target) {
        if (target == null) return;

        BlockState state = this.mc.world.getBlockState(target.pos);

        if (state.isAir()) {
            this.confirm(target);
            return;
        }

        if (!this.reachable(target.pos) || !state.equals(target.state)) {
            this.fail(target);
            return;
        }

        if (target.arming) {
            int slot = this.best(target.state, target.pos);

            if (slot != target.slot) {
                target.slot = slot;
                this.select(slot);
                if (!this.swapped) {
                    this.start(target);
                    return;
                }
                target.arm = this.tick + this.toolSyncDelay.get();
                return;
            }

            if (this.tick < target.arm) {
                return;
            }

            this.start(target);
            return;
        }

        if (target.finished) {
            int delay = target == this.primary ? this.validationTicks.get() : this.validationTicks.get() * 2;
            if (this.tick - target.finish >= delay) this.verify(target);
            return;
        }

        int slot = this.best(target.state, target.pos);
        if (slot != target.slot) target.slot = slot;

        this.advance(target);

        double progress = this.progress(target);
        long elapsed = System.currentTimeMillis() - target.started;

        if (this.miningMode.get() == MiningMode.Packet) {
            if (!target.burst && elapsed >= BURST_PAUSE && this.expected(target) >= BURST_PAUSE && progress < 1.0) {
                this.burst(target);
            }
        }

        if (progress >= 1.0) this.finish(target);
    }

    private void advance(Target target) {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0, now - target.updated);

        target.delta = this.delta(target);

        if (elapsed > 0 && target.delta > 0.0F) {
            target.work += target.delta * elapsed / 50.0;
        }

        target.updated = now;
    }

    private void burst(Target target) {
        this.advance(target);

        target.side = this.face(target.pos, target.side);
        target.slot = this.best(target.state, target.pos);

        this.select(target.slot);

        BlockPos pos = this.fake(target.pos);

        for (int idx = 0; idx < BURST_COUNT; idx++) {
            this.packet(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, target.side);
        }

        target.burst = true;
        this.revertSlot();
    }

    private void finish(Target target) {
        if (target.finished) return;

        this.advance(target);

        target.finished = true;
        target.finish = this.tick;

        this.fast = target.burst;

        if (target == this.primary && !target.instant) {
            this.action(target, PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, target.pos, target.side);
        }

        this.stopped = System.currentTimeMillis();
    }

    private void verify(Target target) {
        BlockState state = this.mc.world.getBlockState(target.pos);

        if (state.isAir()) {
            this.confirm(target);
            return;
        }

        this.fail(target);
    }

    private void fail(Target target) {
        BlockState state = this.mc.world.getBlockState(target.pos);

        boolean reachable = this.reachable(target.pos);
        boolean identical = state.equals(target.state);

        Direction side = this.face(target.pos, target.side);
        target.side = side;

        this.action(target, PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, target.pos, target.side);
        this.remove(target, false);

        if (!reachable || !identical || target.retry >= this.maxRetries.get()) {
            return;
        }

        long ready = System.currentTimeMillis();
        ready += this.retryCooldown.get() * 50L;

        this.waiting.addLast(new Retry(new Request(
            target.pos, side, target.retry + 1), ready)
        );
    }

    private void confirm(Target target) {
        this.last = new Request(target.pos,
            this.face(target.pos, target.side), 0
        );

        this.remove(target, true);
    }

    private boolean remine() {
        if (!this.instantRemine.get() || this.last == null ||
            this.primary != null || this.secondary != null) {
            return false;
        }

        BlockState state = this.mc.world.getBlockState(this.last.pos);
        if (!this.breakable(this.last.pos, state)) return false;

        Direction side = this.face(this.last.pos, this.last.side);
        int slot = this.best(state, this.last.pos);

        this.select(slot);
        this.packet(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, this.last.pos, side);
        this.revertSlot();

        this.stopped = System.currentTimeMillis();
        return true;
    }

    // --- Math & Calculations ---
    private double progress(Target target) {
        if (target.finished) return 1.0;

        double limit = this.limit(target);
        if (limit <= 0.0) return 1.0;

        return Math.min(1.0, target.work / limit);
    }

    private long expected(Target target) {
        if (target.delta <= 0.0F) return Long.MAX_VALUE;

        double limit = this.limit(target);
        double ratio = limit / target.delta - 1.0;

        return (long) Math.max(0.0, ratio * 50.0);
    }

    private double limit(Target target) {
        return target == this.primary ? BREAK_THRESHOLD : 1.0;
    }

    private float delta(Target target) {
        int selected = Hotbar.selected();
        Hotbar.set(target.slot);

        try {
            return target.state.calcBlockBreakingDelta(this.mc.player, this.mc.world, target.pos);
        } finally {
            Hotbar.set(selected);
        }
    }

    private double visual(Target target) {
        if (target.finished) return 1.0;

        double limit = this.limit(target);
        if (limit <= 0.0) return 1.0;

        double work = target.work;

        if (!target.arming && target.updated > 0 && target.delta > 0.0F) {
            long elapsed = System.currentTimeMillis() - target.updated;
            work += target.delta * Math.max(0, elapsed) / 50.0;
        }

        return Math.min(1.0, work / limit);
    }

    // --- Tool & Packet Logic ---
    private int best(BlockState state, BlockPos pos) {
        int selected = Hotbar.selected();
        int best = selected;
        float speed = -1.0F;

        boolean suitable = false;
        boolean required = state.isToolRequired();

        try {
            for (int idx = 0; idx < 9; idx++) {
                ItemStack stack = Hotbar.stack(idx);

                if (this.durabilityProtection.get() && stack.isDamageable()) {
                    int remaining = stack.getMaxDamage() - stack.getDamage();
                    if (remaining <= this.durabilityThreshold.get()) continue;
                }

                boolean good = stack.isSuitableFor(state);
                Hotbar.set(idx);

                float value = state.calcBlockBreakingDelta(this.mc.player, this.mc.world, pos);

                if (required && good != suitable) {
                    if (!good) continue;
                    best = idx;
                    speed = value;
                    suitable = true;
                    continue;
                }

                if (value <= speed) continue;

                best = idx;
                speed = value;
                suitable = good;
            }
        } finally {
            Hotbar.set(selected);
        }

        return best;
    }

    private void action(Target target, PlayerActionC2SPacket.Action action, BlockPos pos, Direction side) {
        target.side = this.face(target.pos, target.side);
        target.slot = this.best(target.state, target.pos);

        this.select(target.slot);
        this.packet(action, pos, target.side);
        this.revertSlot();
    }

    private void select(int slot) {
        if (Hotbar.selected() == slot) {
            this.swapped = false;
            return;
        }

        this.swapped = true;

        // Normal mode updates the client slot and sends the packet
        // Silent mode only sends the packet to the server
        if (this.swapMode.get() == SwapMode.Normal) {
            Hotbar.set(slot);
        }
        
        Hotbar.sync(slot);
    }

    private void revertSlot() {
        if (!this.swapped || this.swapMode.get() != SwapMode.Silent) return;
        
        // Re-syncs the actual client slot to the server
        Hotbar.sync(Hotbar.selected());
        this.swapped = false;
    }

    private void packet(PlayerActionC2SPacket.Action action, BlockPos pos, Direction side) {
        if (this.mc.world == null || this.mc.interactionManager == null) return;

        sendingCustomPacket = true;
        try {
            ((InteractionAccessor) this.mc.interactionManager).Tim$sendSequencedPacket(
                this.mc.world,
                sequence -> new PlayerActionC2SPacket(action, pos, side, sequence)
            );
        } finally {
            sendingCustomPacket = false;
        }
    }

    private BlockPos fake(BlockPos pos) {
        return new BlockPos(pos.getX(), FAKE_BLOCK_HEIGHT, pos.getZ());
    }

    // --- Block Targeting & Validation ---
    private Direction face(BlockPos pos, Direction fallback) {
        Vec3d eye = this.mc.player.getEyePos();

        Direction best = fallback == null ? Direction.UP : fallback;
        double distance = Double.POSITIVE_INFINITY;

        for (Direction side : Direction.values()) {
            Vec3d point = this.point(pos, side);

            BlockHitResult hit = this.mc.world.raycast(
                new RaycastContext(eye, point,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    this.mc.player
                )
            );

            if (hit.getType() != HitResult.Type.BLOCK ||
                !hit.getBlockPos().equals(pos)) {
                continue;
            }

            double value = eye.squaredDistanceTo(point);
            if (value >= distance) continue;

            distance = value;
            best = hit.getSide();
        }

        if (distance < Double.POSITIVE_INFINITY) {
            return best;
        }

        for (Direction side : Direction.values()) {
            Vec3d point = this.point(pos, side);

            double value = eye.squaredDistanceTo(point);
            if (value >= distance) continue;

            distance = value;
            best = side;
        }

        return best;
    }

    private Vec3d point(BlockPos pos, Direction side) {
        return new Vec3d(
            pos.getX() + 0.5 + side.getOffsetX() * 0.49,
            pos.getY() + 0.5 + side.getOffsetY() * 0.49,
            pos.getZ() + 0.5 + side.getOffsetZ() * 0.49
        );
    }

    private boolean breakable(BlockPos pos, BlockState state) {
        return this.reachable(pos) && !state.isAir()
            && !(state.getBlock() instanceof FluidBlock)
            && state.getHardness(this.mc.world, pos) >= 0.0F;
    }

    private boolean reachable(BlockPos pos) {
        Vec3d eye = this.mc.player.getEyePos();

        double px = Math.max(pos.getX(), Math.min(eye.x, pos.getX() + 1.0));
        double py = Math.max(pos.getY(), Math.min(eye.y, pos.getY() + 1.0));
        double pz = Math.max(pos.getZ(), Math.min(eye.z, pos.getZ() + 1.0));

        double dx = px - eye.x;
        double dy = py - eye.y;
        double dz = pz - eye.z;

        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    // --- Auto-Collect Logic ---
    private void checkForNewItems() {
        if (!this.autoCollect.get() || this.mc.player == null || this.mc.world == null) return;

        boolean foundNew = false;
        List<ItemEntity> items = this.mc.world.getEntitiesByClass(ItemEntity.class,
            this.mc.player.getBoundingBox().expand(this.collectRange.get()), e -> {
                if (this.collectWhitelist.get().isEmpty()) return true;
                return this.collectWhitelist.get().contains(e.getStack().getItem());
            });

        for (ItemEntity item : items) {
            if (this.seenItems.add(item.getId())) {
                foundNew = true;
            }
        }

        if (foundNew) {
            this.lastMineTime = System.currentTimeMillis();
        }

        this.seenItems.removeIf(id -> this.mc.world.getEntityById(id) == null);
    }

    private void doAutoCollect() {
        if (!this.autoCollect.get() || this.mc.player == null || this.mc.world == null) return;
        if (Modules.get().isActive(PortalMaker.class)) return;

        long currentTime = System.currentTimeMillis();
        long elapsedMs = currentTime - this.lastMineTime;
        long gracePeriodMs = this.gracePeriod.get() * 1000L;
        long collectDelayMs = this.collectDelay.get() * 50L; // Convert ticks to ms

        if (this.primary != null || this.secondary != null || !this.queue.isEmpty()) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        if (elapsedMs < collectDelayMs) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        if (elapsedMs > gracePeriodMs) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) return;

        ItemEntity closestItem = null;
        double closestDist = this.collectRange.get() * this.collectRange.get();

        List<ItemEntity> items = this.mc.world.getEntitiesByClass(ItemEntity.class,
            this.mc.player.getBoundingBox().expand(this.collectRange.get()), e -> {
                if (this.collectWhitelist.get().isEmpty()) return true;
                return this.collectWhitelist.get().contains(e.getStack().getItem());
            });

        for (ItemEntity item : items) {
            double dist = item.squaredDistanceTo(this.mc.player);
            if (dist < closestDist) {
                closestDist = dist;
                closestItem = item;
            }
        }

        if (closestItem != null) {
            BlockPos itemPos = closestItem.getBlockPos();
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(itemPos));
        }
    }

    // --- Rendering Logic ---
    private void renderTarget(Render3DEvent event, Target target, SettingColor color) {
        double offset = (1.0 - this.visual(target)) / 2.0;

        Box box = new Box(
            target.pos.getX() + offset,
            target.pos.getY() + offset,
            target.pos.getZ() + offset,
            target.pos.getX() + 1.0 - offset,
            target.pos.getY() + 1.0 - offset,
            target.pos.getZ() + 1.0 - offset
        );

        this.renderBox(event, box, color);
    }

    private void renderBox(Render3DEvent event, Box box, SettingColor color) {
        if (this.highlightStyle.get() == HighlightStyle.SPECTRAL) {
            double expand = this.spectralExpand.get();
            Box renderBox = box.expand(expand);
            SettingColor sideColor = this.withAlpha(color, Math.max(4, color.a / 4));
            event.renderer.box(renderBox, this.withAlpha(sideColor, this.spectralFillAlpha.get()), this.withAlpha(color, this.spectralLineAlpha.get()), ShapeMode.Both, 0);
        } else if (this.highlightStyle.get() == HighlightStyle.GLOW) {
            SettingColor sideColor = this.withAlpha(color, Math.max(4, color.a / 4));
            this.renderGlowLayers(event, box, color);
            event.renderer.box(box, this.withAlpha(sideColor, 0), color, this.shapeMode.get(), 0);
        } else if (this.highlightStyle.get() == HighlightStyle.PULSE) {
            this.renderPulseBox(event, box, color);
        }
    }

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int layers = this.glowLayers.get();
        double spread = this.glowSpread.get();
        int baseAlpha = this.glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            int layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i-1) / layers)));
            event.renderer.box(box.expand(spread * i), this.withAlpha(color, layerAlpha), this.withAlpha(color, 0), ShapeMode.Sides, 0);
        }
    }

    private float getPulseFactor() {
        double speed = this.pulseSpeed.get();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float)((Math.sin(phase) + 1.0) * 0.5);
    }

    private int applyPulse(int baseAlpha) {
        float f = getPulseFactor();
        int min = this.pulseMinAlpha.get();
        int max = this.pulseMaxAlpha.get();
        return Math.min(255, Math.max(0, (int)(min + (max - min) * f)));
    }

    private void renderPulseBox(Render3DEvent event, Box box, SettingColor color) {
        int pa = applyPulse(color.a);
        SettingColor pColor = this.withAlpha(color, pa);
        int layers = this.glowLayers.get();
        double spread = this.glowSpread.get();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double)(i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int)(pa * taper));
            event.renderer.box(box.expand(expansion), this.withAlpha(pColor, layerAlpha), this.withAlpha(pColor, 0), ShapeMode.Sides, 0);
        }

        event.renderer.box(box, this.withAlpha(pColor, pa / 3), pColor, ShapeMode.Both, 0);
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    // --- Data Structures ---
    private record Request(BlockPos pos, Direction side, int retry) {
        private Request {
            pos = pos.toImmutable();
        }
    }

    private record Retry(Request request, long ready) {}

    private static class Target {
        private final BlockPos pos;
        private final BlockState state;
        private final int retry;

        private Direction side;

        private long started;
        private long updated;

        private float delta;
        private double work;

        private int slot;
        private int arm;
        private int finish;

        private boolean arming;
        private boolean burst;
        private boolean instant;
        private boolean finished;

        private Target(Request request, BlockState state, Direction side) {
            this.pos = request.pos;
            this.state = state;
            this.side = side;
            this.retry = request.retry;
        }
    }
}