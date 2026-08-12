package com.example.addon.modules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.mixin.InteractionAccessor;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
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
import net.minecraft.block.BlockState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * Core packet mining, queuing, and bursting logic provided by Arkie.
 * Refactored for readability and maintainability.
 */
public class Datamine extends Module {
    // --- Constants ---
    private static final double BREAK_THRESHOLD = 0.75;
    private static final long BURST_PAUSE = 305;
    private static final int BURST_COUNT = 22;
    private static final int FAKE_BLOCK_HEIGHT = 2048;

    // --- Enums ---
    public enum MiningMode { Packet, Normal }
    public enum SwapMode { Normal, Silent }
    public enum HighlightStyle { GLOW, SPECTRAL, PULSE }

    // --- Setting Groups ---
    private final SettingGroup sgMining = this.settings.getDefaultGroup();
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

    private final Setting<Integer> remineDelay = sgMining.add(new IntSetting.Builder()
        .name("remine-delay")
        .description("Delay in ticks before checking for a replaced block. 0 = instant.")
        .defaultValue(5)
        .min(0)
        .sliderMax(20)
        .visible(instantRemine::get)
        .build()
    );

    private final Setting<Integer> validationTicks = sgMining.add(new IntSetting.Builder()
        .name("validation-ticks")
        .description("Ticks to wait before validating whether a block was broken.")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> maxAttempts = sgMining.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("Maximum total mining attempts for each block.")
        .defaultValue(3)
        .min(1)
        .sliderMax(3)
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
        .name("vanilla-bypass")
        .description("Bypasses the mining queue for blocks that break within this many ticks. 0 = disabled.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
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
        .min(50)
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
    private Target primary;
    private Target secondary;
    private Request last;
    
    private int tick = 0;
    private int lastBreakTick = 0;
    private long lastMineTime = 0;
    private long stopped = 0;
    
    private final Set<Integer> seenItems = new HashSet<>();
    private boolean sendingCustomPacket = false;
    private boolean swapped = false;

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

        // Stop Baritone if it was pathing to items, UNLESS PortalMaker is currently using it
        if (this.autoCollect.get() && !Modules.get().isActive(PortalMaker.class) && BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
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
                
                // Prevent STOP_DESTROY_BLOCK from being sent for the last broken block while instant remine is active
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

    // --- Queue & Target Management ---
    public void mine(BlockPos pos, Direction side) {
        if (this.mc.player == null || this.mc.world == null || this.mc.interactionManager == null || pos == null || side == null) return;

        pos = pos.toImmutable();
        if (this.isTracked(pos)) return;

        BlockState state = this.mc.world.getBlockState(pos);
        if (!this.breakable(pos, state)) return;

        this.queue.addLast(new Request(pos, side, 1));
        this.fill();
    }

    private void reset() {
        this.queue.clear();
        this.primary = null;
        this.secondary = null;
        this.last = null;
        this.tick = 0;
        this.lastBreakTick = 0;
        this.lastMineTime = 0;
        this.stopped = 0;
        this.seenItems.clear();
        this.sendingCustomPacket = false;
        this.swapped = false;
    }

    private void clean() {
        this.queue.removeIf(request -> !this.breakable(request.pos, this.mc.world.getBlockState(request.pos)));
    }

    private void fill() {
        if (this.primary == null) {
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
            if (this.breakable(request.pos, state)) {
                return new Target(request, state);
            }
        }
        return null;
    }

    private boolean parkable() {
        return this.primary != null && !this.primary.finished && !this.primary.instant && this.primary.progress < 1;
    }

    private void park() {
        Target target = this.primary;
        this.action(target, PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, target.pos, target.side);
        target.primary = false;
        target.parked = true;
        this.secondary = target;
        this.primary = null;
    }

    private void remove(Target target) {
        if (target == this.primary) this.primary = null;
        if (target == this.secondary) this.secondary = null;
    }

    public boolean isTracked(BlockPos pos) {
        if (this.primary != null && this.primary.pos.equals(pos)) return true;
        if (this.secondary != null && this.secondary.pos.equals(pos)) return true;
        for (Request request : this.queue) {
            if (request.pos.equals(pos)) return true;
        }
        return false;
    }

    private boolean breakable(BlockPos pos, BlockState state) {
        return !state.isAir() && state.getHardness(this.mc.world, pos) >= 0;
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
        target.primary = true;
        target.started = System.currentTimeMillis();
        target.slot = this.best(target.state, target.pos);

        this.select(target.slot);

        target.delta = this.delta(target);
        target.instant = target.delta >= 1.0F;
        target.progress = target.instant ? 1 : 0;

        this.primary = target;

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

        if (target.finished) {
            if (this.tick - target.finish >= this.validationTicks.get()) {
                this.verify(target);
            }
            return;
        }

        target.slot = this.best(target.state, target.pos);
        target.delta = this.delta(target);
        target.progress = this.progress(target);

        long elapsed = System.currentTimeMillis() - target.started;

        if (this.miningMode.get() == MiningMode.Packet) {
            if (!target.burst && elapsed >= BURST_PAUSE && this.duration(target) > BURST_PAUSE && target.progress < 1) {
                this.burst(target);
            }
        }

        if (target.progress >= 1) this.finish(target);
    }

    private void finish(Target target) {
        if (target.finished) return;

        if (!target.instant && !target.parked) {
            this.action(target, PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, target.pos, target.side);
        }

        target.progress = 1;
        target.finished = true;
        target.finish = this.tick;
    }

    private void verify(Target target) {
        BlockState state = this.mc.world.getBlockState(target.pos);

        if (state.isAir()) {
            this.confirm(target);
            return;
        }

        this.remove(target);
        if (target.attempt >= this.maxAttempts.get()) return;

        this.queue.addFirst(new Request(target.pos, target.side, target.attempt + 1));
    }

    private void confirm(Target target) {
        // Send STOP_DESTROY_BLOCK packet to the server for the already mined block
        // This ensures the server is fully aware the breaking action has ceased, preventing desyncs.
        this.packet(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, target.pos, target.side);

        this.last = new Request(target.pos, target.side, 1);
        this.lastBreakTick = this.tick;
        this.remove(target);
    }

    private void burst(Target target) {
        target.slot = this.best(target.state, target.pos);
        this.select(target.slot);

        BlockPos pos = this.fake(target.pos);

        for (int idx = 0; idx < BURST_COUNT; idx++) {
            this.packet(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, target.side);
        }

        target.burst = true;
        this.revertSlot();
    }

    private boolean remine() {
        if (!this.instantRemine.get() || this.last == null ||
            this.primary != null || this.secondary != null) {
            return false;
        }

        BlockState state = this.mc.world.getBlockState(this.last.pos);
        if (!this.breakable(this.last.pos, state)) return false;

        Direction side = this.last.side;
        int slot = this.best(state, this.last.pos);

        this.select(slot);
        this.packet(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            this.last.pos, side
        );

        this.stopped = System.currentTimeMillis();
        return true;
    }

    // --- Math & Calculations ---
    private double progress(Target target) {
        if (target.finished) return 1;
        if (target.delta <= 0) return 0;

        double diff = System.currentTimeMillis() - target.started;
        double ticks = Math.max(1.0, diff / 50.0);
        double limit = (this.miningMode.get() == MiningMode.Packet && target.primary) ? BREAK_THRESHOLD : 1.0;

        return Math.min(1.0, target.delta * ticks / limit);
    }

    private long duration(Target target) {
        if (target.delta <= 0) return Long.MAX_VALUE;
        double limit = (this.miningMode.get() == MiningMode.Packet && target.primary) ? BREAK_THRESHOLD : 1.0;
        return (long) Math.max(0, (limit / target.delta - 1.0) * 50.0);
    }

    private float delta(Target target) {
        PlayerInventory inv = this.mc.player.getInventory();
        int selected = inv.selectedSlot;
        inv.setSelectedSlot(target.slot);

        try {
            return target.state.calcBlockBreakingDelta(this.mc.player, this.mc.world, target.pos);
        } finally {
            inv.setSelectedSlot(selected);
        }
    }

    // --- Tool & Packet Logic ---
    private int best(BlockState state, BlockPos pos) {
        PlayerInventory inv = this.mc.player.getInventory();
        int selected = inv.selectedSlot;
        int best = selected;

        float speed = -1;
        boolean suitable = false;
        boolean required = state.isToolRequired();

        try {
            for (int idx = 0; idx < 9; idx++) {
                ItemStack stack = inv.getStack(idx);
                
                if (this.durabilityProtection.get() && stack.isDamageable()) {
                    int remaining = stack.getMaxDamage() - stack.getDamage();
                    if (remaining <= this.durabilityThreshold.get()) continue;
                }

                boolean good = stack.isSuitableFor(state);
                inv.setSelectedSlot(idx);

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
            inv.setSelectedSlot(selected);
        }
        return best;
    }

    private void action(Target target, PlayerActionC2SPacket.Action action, BlockPos pos, Direction side) {
        target.slot = this.best(target.state, target.pos);
        this.select(target.slot);
        this.packet(action, pos, side);
        this.revertSlot();
    }

    private void select(int slot) {
        PlayerInventory inv = this.mc.player.getInventory();
        if (inv.selectedSlot == slot) {
            this.swapped = false;
            return;
        }

        this.swapped = true;

        if (this.swapMode.get() == SwapMode.Normal) {
            inv.setSelectedSlot(slot);
        }

        this.mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void revertSlot() {
        if (!this.swapped || this.swapMode.get() != SwapMode.Silent) return;

        PlayerInventory inv = this.mc.player.getInventory();
        this.mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(inv.selectedSlot));
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

        // If actively mining, halt Baritone pathing
        if (this.primary != null || this.secondary != null || !this.queue.isEmpty()) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        // Enforce the delay cooldown before starting collection
        if (elapsedMs < collectDelayMs) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        // If the grace period has expired, stop pathing
        if (elapsedMs > gracePeriodMs) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().forceCancel();
            }
            return;
        }

        // Don't interrupt existing pathing
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
        double offset = (1.0 - target.progress) / 2.0;

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
    private record Request(BlockPos pos, Direction side, int attempt) {
        private Request {
            pos = pos.toImmutable();
        }
    }

    private static class Target {
        private final BlockPos pos;
        private final BlockState state;
        private final Direction side;
        private final int attempt;

        private long started;
        private float delta;
        private double progress;
        private int slot;

        private boolean primary;
        private boolean parked;
        private boolean burst;
        private boolean instant;
        private boolean finished;
        private int finish;

        private Target(Request request, BlockState state) {
            this.pos = request.pos;
            this.state = state;
            this.side = request.side;
            this.attempt = request.attempt;
        }
    }
}