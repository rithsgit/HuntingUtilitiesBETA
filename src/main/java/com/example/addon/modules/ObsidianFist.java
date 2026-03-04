package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * ObsidianFist — Place-break Ender Chests for XP.
 *
 * Speed design:
 *   Every cycle sends PLACE + START_DESTROY + STOP_DESTROY in a single tick.
 *   Rotation is sent as a raw PlayerMoveC2SPacket — no Rotations.rotate()
 *   callbacks that defer execution to the next tick.
 *   We do not wait for a place-confirm packet before mining; we mine
 *   optimistically. If the server rejects the place the air-confirm never
 *   arrives and the timeout retries.
 *
 * Theoretical throughput:
 *   One tick to send place+mine, one tick minimum for server to confirm break
 *   → 10 cycles/s on a LAN. At 50ms RTT → ~8 cycles/s. Server-side
 *   anti-cheat or tick-lag are the remaining limits.
 */
public class ObsidianFist extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRepair  = settings.createGroup("Auto Repair");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    // ── Settings ──────────────────────────────────────────────────────────────

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Sends a rotation packet each cycle (no callback delay).")
        .defaultValue(true).build());

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Restore the previous hotbar slot when stopping.")
        .defaultValue(true).build());

    private final Setting<Integer> cycleDelay = sgGeneral.add(new IntSetting.Builder()
        .name("cycle-delay")
        .description("Extra ticks to wait after a confirmed break before the next cycle. 0 = as fast as possible.")
        .defaultValue(0).min(0).sliderMax(5).build());

    private final Setting<Integer> breakTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("break-timeout")
        .description("Ticks to wait for server break-confirm before retrying. Lower = faster recovery from ghost blocks.")
        .defaultValue(8).min(2).sliderMax(20).build());

    private final Setting<Integer> burstCount = sgGeneral.add(new IntSetting.Builder()
        .name("burst-count")
        .description("Cycles before stopping. Ignored when Loop is on.")
        .defaultValue(16).min(1).sliderMax(128).build());

    private final Setting<Boolean> loopBurst = sgGeneral.add(new BoolSetting.Builder()
        .name("loop")
        .description("Run indefinitely. Disable to use Burst Count.")
        .defaultValue(true).build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").defaultValue(5.0).min(0).sliderMax(6).build());

    // Auto Repair
    private final Setting<Boolean> autoRepair = sgRepair.add(new BoolSetting.Builder()
        .name("auto-repair").defaultValue(false).build());
    private final Setting<Integer> repairThreshold = sgRepair.add(new IntSetting.Builder()
        .name("durability-threshold").defaultValue(50).min(1).sliderMax(500)
        .visible(autoRepair::get).build());
    private final Setting<Integer> repairPacketsPerBurst = sgRepair.add(new IntSetting.Builder()
        .name("packets-per-burst").defaultValue(3).min(1).sliderMax(10)
        .visible(autoRepair::get).build());
    private final Setting<Integer> repairBurstDelay = sgRepair.add(new IntSetting.Builder()
        .name("burst-delay").defaultValue(4).min(0).sliderMax(20)
        .visible(autoRepair::get).build());

    // Render
    private final Setting<Boolean>      render    = sgRender.add(new BoolSetting.Builder().name("render").defaultValue(true).build());
    private final Setting<ShapeMode>    shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder().name("side-color").defaultValue(new SettingColor(255, 0, 0, 75)).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(255, 0, 0, 255)).build());

    // ── State ─────────────────────────────────────────────────────────────────

    /**
     * IDLE       — waiting for the player to look at a block.
     * FIRST_MINE — holding START_DESTROY on an existing chest; waiting for server air confirm.
     * WAIT_BREAK — place+mine sent this tick; waiting for server air confirm.
     * DELAY      — confirmed, counting down cycleDelay before next cycle.
     */
    private enum State { IDLE, FIRST_MINE, WAIT_BREAK, DELAY }

    private State    state          = State.IDLE;
    private BlockPos currentPos;      // ender chest position
    private Direction currentDir;     // face we mine
    private BlockPos placeTarget;     // block we place against
    private Direction placeSide;      // face of placeTarget we place on
    private int      prevSlot        = -1;
    private int      cyclesDone      = 0;
    private int      timer           = 0;
    private int      retries         = 0;

    // Written by packet thread, read+cleared by tick thread
    private volatile boolean breakConfirmed    = false;
    private volatile boolean restorationPending = false;

    // Repair
    private boolean isRepairing = false;
    private int     repairTimer = 0;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public ObsidianFist() {
        super(HuntingUtilities.CATEGORY, "obsidian-fist", "Place-break Ender Chests for XP.");
    }

    @Override public void onActivate()   { fullReset(); }
    @Override public void onDeactivate() { fullReset(); }

    private void fullReset() {
        if (swapBack.get() && prevSlot != -1) InvUtils.swap(prevSlot, false);
        state        = State.IDLE;
        currentPos   = null;
        currentDir   = null;
        placeTarget  = null;
        placeSide    = null;
        prevSlot     = -1;
        cyclesDone   = 0;
        timer        = 0;
        retries      = 0;
        breakConfirmed    = false;
        restorationPending = false;
        isRepairing  = false;
        repairTimer  = 0;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // ── Drain break confirmation immediately, before anything else ────────
        if (breakConfirmed) {
            breakConfirmed = false;
            restorationPending = false;
            onBreakConfirmed();
            if (state == State.IDLE) return;
        }

        // ── Drain restoration — retry cycle immediately ───────────────────────
        if (restorationPending) {
            restorationPending = false;
            timer = 0;
            syncGhostBlock(currentPos);
            syncGhostBlock(placeTarget);
            retries++;
            if (retries > 8) { error("Too many restorations, aborting."); fullReset(); return; }
            tickCycle();
            return;
        }

        // ── Auto repair ───────────────────────────────────────────────────────
        if (autoRepair.get()) {
            FindItemResult pk = findPickaxe();
            if (pk.found()) {
                int dur = mc.player.getInventory().getStack(pk.slot()).getMaxDamage()
                        - mc.player.getInventory().getStack(pk.slot()).getDamage();
                if (dur <= repairThreshold.get() && !isRepairing) isRepairing = true;
                if (dur > repairThreshold.get() && isRepairing) { isRepairing = false; info("Pickaxe repaired."); }
            }
            if (isRepairing) { handleRepair(pk); return; }
        }

        // ── Timer tick ────────────────────────────────────────────────────────
        if (timer > 0) { timer--; return; }

        // ── State dispatch ────────────────────────────────────────────────────
        switch (state) {
            case IDLE       -> tickIdle();
            case FIRST_MINE -> tickFirstMine();
            case WAIT_BREAK -> tickWaitBreak(); // timeout only — normally packet fires first
            case DELAY      -> tickCycle();     // delay elapsed, fire next cycle
        }
    }

    // ── IDLE ──────────────────────────────────────────────────────────────────

    private void tickIdle() {
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        if (mc.player.squaredDistanceTo(pos.toCenterPos()) > range.get() * range.get()) return;
        if (prevSlot == -1) prevSlot = mc.player.getInventory().selectedSlot;

        if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
            // Existing chest — speed-mine it first, then loop
            if (!findPickaxe().found()) return;
            currentPos  = pos;
            currentDir  = hit.getSide();
            placeTarget = pos.offset(hit.getSide());
            placeSide   = hit.getSide().getOpposite();
            cyclesDone  = 0;
            retries     = 0;
            doFirstMine();
        } else {
            // Empty surface — place then mine
            if (!findPickaxe().found() || !InvUtils.find(Items.ENDER_CHEST).found()) return;
            BlockPos placePos = pos.offset(hit.getSide());
            if (!canPlace(placePos)) return;
            currentPos  = placePos;
            currentDir  = getBreakDirection(placePos);
            placeTarget = pos;
            placeSide   = hit.getSide();
            cyclesDone  = 0;
            retries     = 0;
            tickCycle(); // start immediately
        }
    }

    // ── FIRST MINE ────────────────────────────────────────────────────────────

    /**
     * Sends START_DESTROY to begin mining the existing chest.
     * Does NOT send STOP_DESTROY — we hold and wait for the server to confirm
     * the break (requires instant-mine conditions: Eff5 pick + Haste II).
     */
    private void doFirstMine() {
        FindItemResult pk = findPickaxe();
        if (!pk.found()) { fullReset(); return; }

        InvUtils.swap(pk.slot(), false);
        sendRotation(currentPos);
        mc.interactionManager.attackBlock(currentPos, currentDir);
        mc.player.swingHand(Hand.MAIN_HAND);

        state = State.FIRST_MINE;
        timer = breakTimeout.get(); // safety timeout
    }

    private void tickFirstMine() {
        // Timer expired without a break confirm — resend START_DESTROY
        syncGhostBlock(currentPos);
        retries++;
        if (retries > 5) { error("First mine timed out."); fullReset(); return; }
        doFirstMine();
    }

    // ── CYCLE — place + mine in ONE tick ─────────────────────────────────────

    /**
     * The hot path. Everything in this method executes within a single game tick:
     *
     *   1. syncGhostBlock  — ABORT_DESTROY_BLOCK clears client ghost state
     *   2. Swap to echest, send rotation packet, send interactBlock
     *   3. Swap to pickaxe, send rotation packet, send attackBlock
     *   4. Send STOP_DESTROY_BLOCK immediately (instant-mine)
     *   5. Enter WAIT_BREAK with a short timeout
     *
     * The server processes these in order on the same connection flush.
     * When it confirms the break, breakConfirmed is set by the packet thread
     * and drained at the TOP of the very next onTick — zero extra latency.
     */
    private void tickCycle() {
        FindItemResult echest  = InvUtils.find(Items.ENDER_CHEST);
        FindItemResult pickaxe = findPickaxe();
        if (!echest.found() || !pickaxe.found()) { fullReset(); return; }

        // 1. Clear ghost blocks on BOTH positions — placement surface and chest pos
        syncGhostBlock(currentPos);
        syncGhostBlock(placeTarget);

        // 2. Place ender chest
        InvUtils.swap(echest.slot(), false);
        sendRotation(placeTarget);

        boolean sneaking = !mc.player.isSneaking()
            && isClickable(mc.world.getBlockState(placeTarget).getBlock());
        if (sneaking) mc.player.networkHandler.sendPacket(
            new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(
            new Vec3d(
                placeTarget.getX() + 0.5 + placeSide.getOffsetX() * 0.5,
                placeTarget.getY() + 0.5 + placeSide.getOffsetY() * 0.5,
                placeTarget.getZ() + 0.5 + placeSide.getOffsetZ() * 0.5),
            placeSide, placeTarget, false));
        mc.player.swingHand(Hand.MAIN_HAND);

        if (sneaking) mc.player.networkHandler.sendPacket(
            new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));

        // 3+4. Mine immediately — same tick
        InvUtils.swap(pickaxe.slot(), false);
        sendRotation(currentPos);

        mc.interactionManager.attackBlock(currentPos, currentDir);
        mc.player.swingHand(Hand.MAIN_HAND);
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, currentPos, currentDir));

        // 5. Wait for server air confirm
        state = State.WAIT_BREAK;
        timer = breakTimeout.get();
        retries = 0;
    }

    // ── WAIT_BREAK timeout ────────────────────────────────────────────────────

    private void tickWaitBreak() {
        // Timeout: server didn't confirm — ghost block likely stuck. Sync and retry.
        syncGhostBlock(currentPos);
        retries++;
        if (retries > 8) {
            error("Break timed out too many times, aborting.");
            fullReset();
            return;
        }
        // Retry the full cycle immediately
        state = State.DELAY;
        timer = 0;
        tickCycle();
    }

    // ── Break confirmed ───────────────────────────────────────────────────────

    private void onBreakConfirmed() {
        mc.interactionManager.cancelBlockBreaking();
        mc.player.swingHand(Hand.MAIN_HAND);
        cyclesDone++;
        retries = 0;

        if (!loopBurst.get() && cyclesDone >= burstCount.get()) {
            fullReset();
            return;
        }

        if (cycleDelay.get() > 0) {
            state = State.DELAY;
            timer = cycleDelay.get();
        } else {
            // Fire immediately — still in the same tick's onTick execution
            tickCycle();
        }
    }

    // ── Packets ───────────────────────────────────────────────────────────────

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (currentPos == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket p) {
            if (p.getPos().equals(currentPos)) handleBlockUpdate(p.getState().isAir());
        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket p) {
            p.visitUpdates((pos, st) -> {
                if (pos.equals(currentPos)) handleBlockUpdate(st.isAir());
            });
        }
    }

    private void handleBlockUpdate(boolean isAir) {
        if (isAir) {
            switch (state) {
                case FIRST_MINE, WAIT_BREAK -> {
                    breakConfirmed = true;
                    timer = 0; // cancel timeout immediately
                }
                default -> {}
            }
        } else {
            // Non-air while waiting for break = server pushed block back (restoration)
            switch (state) {
                case WAIT_BREAK, FIRST_MINE -> {
                    restorationPending = true;
                    timer = 0;
                }
                default -> {}
            }
        }
    }

    // ── Auto repair ───────────────────────────────────────────────────────────

    private void handleRepair(FindItemResult pickaxe) {
        if (repairTimer > 0) { repairTimer--; return; }
        if (!pickaxe.isMainHand()) InvUtils.swap(pickaxe.slot(), false);
        if (!mc.player.getOffHandStack().isOf(Items.EXPERIENCE_BOTTLE)) {
            FindItemResult xp = InvUtils.find(Items.EXPERIENCE_BOTTLE);
            if (!xp.found()) { error("No XP bottles."); autoRepair.set(false); isRepairing = false; return; }
            InvUtils.move().from(xp.slot()).toOffhand();
            repairTimer = 2;
            return;
        }
        // Look straight down to throw XP bottle
        mc.player.networkHandler.sendPacket(
            new PlayerMoveC2SPacket.LookAndOnGround(mc.player.getYaw(), 90f, mc.player.isOnGround(), mc.player.horizontalCollision));
        for (int i = 0; i < repairPacketsPerBurst.get(); i++)
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        repairTimer = repairBurstDelay.get();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Sends a rotation packet inline — no callback, no deferred execution.
     * This is the critical change vs Rotations.rotate(): the server receives
     * the look direction in the same packet flush as the place/mine packets
     * that follow it, so they are processed in the correct rotation context.
     */
    private void sendRotation(BlockPos target) {
        if (!rotate.get() || target == null || mc.player == null) return;
        Vec3d eyes = mc.player.getEyePos();
        Vec3d d    = target.toCenterPos().subtract(eyes);
        float yaw  = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) MathHelper.wrapDegrees(
            -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        mc.player.networkHandler.sendPacket(
            new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    /**
     * ABORT_DESTROY_BLOCK forces the server to resend the authoritative block
     * state, clearing ghost blocks that silently prevent placement.
     */
    private void syncGhostBlock(BlockPos pos) {
        if (pos == null || mc.player == null) return;
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, Direction.UP));
    }

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