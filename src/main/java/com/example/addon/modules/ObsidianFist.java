package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class ObsidianFist extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRepair  = settings.createGroup("Auto Repair");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    // ── General ───────────────────────────────────────────────────────────────

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the block when mining/placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<BreakMode> breakMode = sgGeneral.add(new EnumSetting.Builder<BreakMode>()
        .name("break-mode")
        .description("Safe waits for server confirmation, Instant is fastest, Custom allows a specific delay.")
        .defaultValue(BreakMode.Instant)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Swaps back to the previous slot after the burst finishes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> customBreakDelay = sgGeneral.add(new IntSetting.Builder()
        .name("custom-break-delay")
        .description("Ticks to wait before sending the stop-break packet in Custom mode.")
        .defaultValue(1)
        .min(0)
        .sliderMax(20)
        .visible(() -> breakMode.get() == BreakMode.Custom)
        .build()
    );

    private final Setting<Integer> placeActionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-action-delay")
        .description("Ticks to wait after placing before mining again.")
        .defaultValue(1)
        .min(0)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> burstCount = sgGeneral.add(new IntSetting.Builder()
        .name("burst-count")
        .description("Number of ender chest place-break cycles to perform per burst.")
        .defaultValue(8)
        .min(1)
        .sliderMax(32)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between place-break cycles (0 = instant chain).")
        .defaultValue(0)
        .min(0)
        .sliderMax(5)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to mine/place.")
        .defaultValue(5.0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    // ── Auto Repair ───────────────────────────────────────────────────────────

    private final Setting<Boolean> autoRepair = sgRepair.add(new BoolSetting.Builder()
        .name("auto-repair")
        .description("Automatically repairs your pickaxe with XP bottles when durability is low.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> repairThreshold = sgRepair.add(new IntSetting.Builder()
        .name("durability-threshold")
        .description("Remaining durability below which to start repairing.")
        .defaultValue(50)
        .min(1)
        .sliderMax(500)
        .visible(autoRepair::get)
        .build()
    );

    private final Setting<Integer> repairPacketsPerBurst = sgRepair.add(new IntSetting.Builder()
        .name("packets-per-burst")
        .description("XP bottles to throw per repair burst.")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .visible(autoRepair::get)
        .build()
    );

    private final Setting<Integer> repairBurstDelay = sgRepair.add(new IntSetting.Builder()
        .name("burst-delay")
        .description("Ticks to wait between repair bursts.")
        .defaultValue(4)
        .min(0)
        .sliderMax(20)
        .visible(autoRepair::get)
        .build()
    );

    // ── Render ────────────────────────────────────────────────────────────────

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render the target block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 0, 0, 75))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    // ── Enums ─────────────────────────────────────────────────────────────────

    private enum BreakMode { Safe, Instant, Custom }

    private enum State { Idle, Placing, MiningStart, MiningHold, WaitingBreak, SpeedMining }

    // ── State ─────────────────────────────────────────────────────────────────

    private State mode       = State.Idle;
    private BlockPos currentPos;
    private Direction currentDir;
    private int timer;
    private int restorationCount;
    private BlockPos placeTarget;
    private Direction placeSide;
    // FIX: prevSlot captured once in handleIdle, never overwritten mid-burst
    private int prevSlot     = -1;
    // FIX: unified counter – incremented exactly once per completed cycle
    private int burstCyclesDone = 0;

    // Auto-repair
    private boolean isRepairing = false;
    private int repairTimer     = 0;

    public ObsidianFist() {
        super(HuntingUtilities.CATEGORY, "obsidian-fist",
              "Place-break Ender Chests for XP. Fully self-contained.");
    }

    @Override public void onActivate()   { reset(); }
    @Override public void onDeactivate() { reset(); }

    // ── Reset ─────────────────────────────────────────────────────────────────

    /**
     * Full reset. swapBack is only applied here – never mid-burst – so the
     * pickaxe slot reference survives across all cycles within a burst.
     */
    private void reset() {
        if (swapBack.get() && prevSlot != -1) {
            InvUtils.swap(prevSlot, false);
        }
        mode             = State.Idle;
        currentPos       = null;
        currentDir       = null;
        timer            = 0;
        restorationCount = 0;
        placeTarget      = null;
        placeSide        = null;
        prevSlot         = -1;
        burstCyclesDone  = 0;
        isRepairing      = false;
        repairTimer      = 0;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // ── Auto-repair gate ──────────────────────────────────────────────────
        if (autoRepair.get()) {
            FindItemResult pickaxe = findPickaxe();
            boolean needsRepair = pickaxe.found()
                && mc.player.getInventory().getStack(pickaxe.slot()).isDamaged()
                && (mc.player.getInventory().getStack(pickaxe.slot()).getMaxDamage()
                    - mc.player.getInventory().getStack(pickaxe.slot()).getDamage()
                    <= repairThreshold.get());

            if (needsRepair && !isRepairing) {
                isRepairing = true;
            } else if (!needsRepair && isRepairing) {
                isRepairing = false;
                info("Pickaxe repaired.");
            }

            if (isRepairing) {
                // FIX: pause without resetting burst state
                handleRepair(pickaxe);
                return;
            }
        }

        // ── Tick-down ─────────────────────────────────────────────────────────
        if (timer > 0) {
            timer--;
            if (timer > 0) return;

            // Timer just expired while waiting for Safe-mode confirmation
            if (mode == State.WaitingBreak) {
                // Timed out – treat as a restoration failure and retry
                handleRestoration();
                if (timer > 0) return;
            }
        }

        // ── State-machine loop ────────────────────────────────────────────────
        // Allows instant chaining when all delays are 0.
        // Cap at 10 steps to prevent an infinite loop on logic bugs.
        for (int steps = 0; steps < 10; steps++) {
            if (timer > 0) break;
            State before = mode;
            runStateMachine();
            // If nothing changed and we're not Idle, we're waiting for a packet
            if (mode == before && mode != State.Idle) break;
            if (mode == State.Idle && before == State.Idle) break;
        }
    }

    private void runStateMachine() {
        switch (mode) {
            case Idle        -> handleIdle();
            case Placing     -> handlePlacing();
            case MiningStart -> handleMiningStart();
            // FIX: MiningHold does nothing – it waits for onPacket to fire
            case MiningHold  -> { /* waiting for BlockUpdateS2CPacket */ }
            case WaitingBreak-> { /* waiting for packet or tick timeout  */ }
            case SpeedMining -> handleSpeedMining();
        }
    }

    // ── State handlers ────────────────────────────────────────────────────────

    private void handleIdle() {
        FindItemResult pickaxe = findPickaxe();
        if (!pickaxe.found()) return;

        if (!(mc.crosshairTarget instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        if (mc.player.squaredDistanceTo(pos.toCenterPos()) > range.get() * range.get()) return;

        // FIX: capture prevSlot exactly once, before any swaps occur
        if (prevSlot == -1) prevSlot = mc.player.getInventory().selectedSlot;

        // Case 1: existing ender chest – mine it directly, then burst
        if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
            currentPos      = pos;
            currentDir      = hit.getSide();
            placeTarget     = pos.offset(hit.getSide().getOpposite());
            placeSide       = hit.getSide();
            mode            = State.MiningStart;
            restorationCount= 0;
            burstCyclesDone = 0;
            return;
        }

        // Case 2: place then mine
        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) return;

        BlockPos placePos = pos.offset(hit.getSide());
        if (!canPlace(placePos)) return;

        currentPos      = placePos;
        currentDir      = getBreakDirection(placePos);
        mode            = State.Placing;
        restorationCount= 0;
        burstCyclesDone = 0;
        placeTarget     = pos;
        placeSide       = hit.getSide();
    }

    private void handlePlacing() {
        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) { reset(); return; }

        Runnable placeLogic = () -> {
            if (placeTarget == null || placeSide == null) return;

            InvUtils.swap(echest.slot(), false);

            boolean sneaking = !mc.player.isSneaking()
                && isClickable(mc.world.getBlockState(placeTarget).getBlock());
            if (sneaking) mc.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));

            BlockHitResult placeHit = new BlockHitResult(
                new Vec3d(
                    placeTarget.getX() + 0.5 + placeSide.getOffsetX() * 0.5,
                    placeTarget.getY() + 0.5 + placeSide.getOffsetY() * 0.5,
                    placeTarget.getZ() + 0.5 + placeSide.getOffsetZ() * 0.5),
                placeSide, placeTarget, false);

            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
            mc.player.swingHand(Hand.MAIN_HAND);

            if (sneaking) mc.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));

            mode  = State.MiningStart;
            timer = placeActionDelay.get();
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(placeTarget), Rotations.getPitch(placeTarget), placeLogic);
        else              placeLogic.run();
    }

    private void handleMiningStart() {
        FindItemResult pickaxe = findPickaxe();
        if (!pickaxe.found()) { reset(); return; }

        Runnable mineLogic = () -> {
            if (currentPos == null || currentDir == null) return;

            InvUtils.swap(pickaxe.slot(), false);

            if (mc.interactionManager.isBreakingBlock()) {
                mc.interactionManager.updateBlockBreakingProgress(currentPos, currentDir);
            } else {
                mc.interactionManager.attackBlock(currentPos, currentDir);
            }
            mc.player.swingHand(Hand.MAIN_HAND);

            switch (breakMode.get()) {
                case Instant -> {
                    mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, currentPos, currentDir));
                    advanceBurst();
                }
                case Custom -> {
                    mode  = State.SpeedMining;
                    timer = customBreakDelay.get();
                }
                // FIX: Safe mode waits for packet confirmation via MiningHold
                case Safe -> {
                    mode  = State.MiningHold;
                    timer = 40; // Safety timeout – handleBlockUpdate fires first on success
                }
            }
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(currentPos), Rotations.getPitch(currentPos), mineLogic);
        else              mineLogic.run();
    }

    private void handleSpeedMining() {
        // Timer expired – send stop-break and advance
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, currentPos, currentDir));
        advanceBurst();
    }

    // ── Core burst logic ──────────────────────────────────────────────────────

    /**
     * Called exactly once per completed place-break cycle.
     * FIX: single increment, unified check – no dual-branch off-by-one.
     * FIX: swapBack only happens in reset(), not here, so prevSlot survives.
     */
    private void advanceBurst() {
        mc.player.swingHand(Hand.MAIN_HAND);
        mc.interactionManager.cancelBlockBreaking();

        burstCyclesDone++;

        if (burstCyclesDone >= burstCount.get()) {
            reset(); // burst complete – swapBack happens here
        } else {
            // Continue burst
            restorationCount = 0;
            mode  = State.Placing;
            timer = delay.get();
        }
    }

    // ── Packet handler ────────────────────────────────────────────────────────

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (currentPos == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket p) {
            if (p.getPos().equals(currentPos))
                handleBlockUpdate(p.getPos(), p.getState().isAir());

        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket p) {
            p.visitUpdates((pos, state) -> {
                if (pos.equals(currentPos))
                    handleBlockUpdate(pos, state.isAir());
            });
        }
    }

    private void handleBlockUpdate(BlockPos pos, boolean isAir) {
        if (currentPos == null || !pos.equals(currentPos)) return;

        if (isAir) {
            // Block broke – confirmed regardless of which mode we're in
            switch (mode) {
                case MiningStart, MiningHold, WaitingBreak -> advanceBurst();
                // Ignore stale air packets in other states
                default -> {}
            }
        } else {
            // Block restored (server rejected break or desync)
            switch (mode) {
                case WaitingBreak, MiningHold -> handleRestoration();
                // In Instant/Custom: if block is still present while we're about
                // to place, our previous break failed – retry
                case Placing -> {
                    if (breakMode.get() == BreakMode.Instant || breakMode.get() == BreakMode.Custom)
                        handleRestoration();
                }
                default -> {}
            }
        }
    }

    private void handleRestoration() {
        restorationCount++;
        if (restorationCount > 3) {
            error("Block restored too many times, aborting.");
            reset();
        } else {
            mode  = State.MiningStart;
            timer = delay.get();
        }
    }

    // ── Auto repair ───────────────────────────────────────────────────────────

    /**
     * FIX: no longer calls reset() – we just pause the burst without wiping state.
     */
    private void handleRepair(FindItemResult pickaxe) {
        if (repairTimer > 0) { repairTimer--; return; }

        // Ensure pickaxe in main hand
        if (!pickaxe.isMainHand()) {
            InvUtils.swap(pickaxe.slot(), false);
        }

        // Move XP bottles to offhand if needed
        if (!mc.player.getOffHandStack().isOf(Items.EXPERIENCE_BOTTLE)) {
            FindItemResult xp = InvUtils.find(Items.EXPERIENCE_BOTTLE);
            if (!xp.found()) {
                error("No XP bottles found. Disabling auto-repair.");
                autoRepair.set(false);
                isRepairing = false;
                return;
            }
            InvUtils.move().from(xp.slot()).toOffhand();
            repairTimer = 2;
            return;
        }

        // Throw XP bottles while looking down
        Rotations.rotate(mc.player.getYaw(), 90, () -> {
            for (int i = 0; i < repairPacketsPerBurst.get(); i++) {
                mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            }
        });
        repairTimer = repairBurstDelay.get();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FindItemResult findPickaxe() {
        FindItemResult r = InvUtils.find(Items.NETHERITE_PICKAXE);
        return r.found() ? r : InvUtils.find(Items.DIAMOND_PICKAXE);
    }

    private Direction getBreakDirection(BlockPos pos) {
        Vec3d diff = mc.player.getEyePos().subtract(pos.toCenterPos());
        return Direction.getFacing(diff.x, diff.y, diff.z);
    }

    private boolean canPlace(BlockPos pos) {
        if (!World.isValid(pos)) return false;
        if (!mc.world.getBlockState(pos).isReplaceable()) return false;
        if (!mc.world.getFluidState(pos).isEmpty()) return false;
        if (mc.world.isOutOfHeightLimit(pos)) return false;
        return mc.world.canPlace(Blocks.ENDER_CHEST.getDefaultState(), pos,
            net.minecraft.block.ShapeContext.absent());
    }

    private boolean isClickable(Block block) {
        return block instanceof net.minecraft.block.CraftingTableBlock
            || block instanceof net.minecraft.block.AnvilBlock
            || block instanceof net.minecraft.block.BlockWithEntity
            || block instanceof net.minecraft.block.BedBlock
            || block instanceof net.minecraft.block.FenceGateBlock
            || block instanceof net.minecraft.block.DoorBlock
            || block instanceof net.minecraft.block.TrapdoorBlock
            || block == Blocks.CHEST
            || block == Blocks.ENDER_CHEST;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || currentPos == null) return;
        event.renderer.box(currentPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }
}