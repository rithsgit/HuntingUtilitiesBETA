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
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
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

public class ObsidianFist extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Logs detailed information to the console for debugging.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the block when mining/placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<BreakMode> breakMode = sgGeneral.add(new EnumSetting.Builder<BreakMode>()
        .name("break-mode")
        .description("Safe waits for server confirmation, Instant is faster but may be less reliable.")
        .defaultValue(BreakMode.Instant)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Swaps back to the previous slot after mining.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between actions (cycling or rebreaking).")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The maximum distance to mine/place.")
        .defaultValue(5.0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render the target block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color.")
        .defaultValue(new SettingColor(255, 0, 0, 75))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    private enum BreakMode {
        Safe, Instant
    }

    private enum State {
        Idle, Placing, MiningStart, MiningHold, WaitingBreak
    }

    private State mode = State.Idle;
    private BlockPos currentPos;
    private Direction currentDir;
    private int timer;
    private int attempts;
    private int restorationCount;
    private BlockPos placeTarget;
    private Direction placeSide;
    private int prevSlot = -1;

    public ObsidianFist() {
        super(HuntingUtilities.CATEGORY, "obsidian-fist", "WIP: Insta-mines Ender Chests. Optimized for 2b2t.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        if (debug.get()) info("Resetting state.");
        mode = State.Idle;
        currentPos = null;
        currentDir = null;
        timer = 0;
        attempts = 0;
        restorationCount = 0;
        placeTarget = null;
        placeSide = null;
        prevSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (timer > 0) {
            timer--;
            if (timer > 0) return; // If timer is still running, wait.

            if (debug.get()) info("Timer finished. Current mode: %s", mode);
            // Timer just finished. Handle timeouts for waiting states.
            if (mode == State.WaitingBreak) {
                // Break confirmation timed out, assume it failed.
                handleBlockUpdate(currentPos, false);
                // handleBlockUpdate might set a new timer, so we wait.
                if (timer > 0) return;
            }
        }

        // State machine loop to allow instant transitions if delays are 0
        int steps = 0;
        while (steps < 5) { // Limit iterations to prevent infinite loops
            if (timer > 0) break;
            State prevMode = mode;
            runStateMachine();
            if (debug.get() && mode != prevMode) info("State transition: %s -> %s", prevMode, mode);
            if (mode == prevMode && mode != State.Idle) break; // Waiting for something
            if (mode == State.Idle && prevMode == State.Idle) break; // Nothing to do
            steps++;
        }
    }

    private void runStateMachine() {
        switch (mode) {
            case Idle -> handleIdle();
            case Placing -> handlePlacing();
            case MiningStart -> handleMiningStart();
            case MiningHold -> handleMiningHold(); // This calls finishMining()
            case WaitingBreak -> { /* Handled in onPacket */ }
        }
    }

    private void handleIdle() {
        FindItemResult pickaxe = findPickaxe();
        if (!pickaxe.found()) {
            return;
        }

        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            if (debug.get()) info("Idle: Not looking at a block.");
            return;
        }
        BlockPos pos = hit.getBlockPos();

        if (mc.player.squaredDistanceTo(pos.toCenterPos()) > range.get() * range.get()) {
            if (debug.get()) info("Idle: Target out of range.");
            return;
        }

        // 1. Existing Ender Chest
        if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
            if (debug.get()) info("Idle: Found existing Ender Chest at %s. Starting to mine.", pos);
            currentPos = pos;
            currentDir = hit.getSide();
            mode = State.MiningStart;
            attempts = 0;
            restorationCount = 0;
            return;
        }

        // 2. Place and Mine
        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) {
            if (debug.get()) info("Idle: No Ender Chest found for placement.");
            return;
        }

        BlockPos placePos = pos.offset(hit.getSide());
        if (!BlockUtils.canPlace(placePos)) {
            if (debug.get()) info("Idle: Cannot place at %s.", placePos);
            return;
        }
        if (!mc.world.getFluidState(placePos).isEmpty()) {
            if (debug.get()) info("Idle: Cannot place in fluid at %s.", placePos);
            return;
        }
        if (mc.world.isOutOfHeightLimit(placePos)) {
            if (debug.get()) info("Idle: Cannot place out of height limit at %s.", placePos);
            return;
        }

        if (debug.get()) info("Idle: Found placeable spot at %s. Starting placement.", placePos);
        currentPos = placePos;
        currentDir = getBreakDirection(placePos); // Direction to mine from
        mode = State.Placing;
        attempts = 0;
        restorationCount = 0;

        // Store interaction data for placement
        placeTarget = pos;
        placeSide = hit.getSide();
    }

    private void handlePlacing() {
        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) {
            if (debug.get()) error("Placing: Lost Ender Chests. Resetting.");
            reset();
            return;
        }

        int prevSlot = mc.player.getInventory().selectedSlot;
        if (!InvUtils.swap(echest.slot(), false)) return;

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(placeTarget), Rotations.getPitch(placeTarget));
        }

        boolean sneaking = !mc.player.isSneaking() && BlockUtils.isClickable(mc.world.getBlockState(placeTarget).getBlock());
        if (sneaking) mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));

        BlockHitResult placeHit = new BlockHitResult(
            new Vec3d(placeTarget.getX() + 0.5 + placeSide.getOffsetX() * 0.5, placeTarget.getY() + 0.5 + placeSide.getOffsetY() * 0.5, placeTarget.getZ() + 0.5 + placeSide.getOffsetZ() * 0.5),
            placeSide,
            placeTarget,
            false
        );

        if (debug.get()) info("Placing: Interacting with block at %s to place at %s.", placeTarget, currentPos);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
        mc.player.swingHand(Hand.MAIN_HAND);

        // Client-side placement to prevent the client from trying to mine air.
        mc.world.setBlockState(currentPos, Blocks.ENDER_CHEST.getDefaultState());

        if (sneaking) mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));

        if (swapBack.get()) InvUtils.swap(prevSlot, false);

        mode = State.MiningStart;
        timer = delay.get();
    }

    private void handleMiningStart() {
        FindItemResult pickaxe = findPickaxe();
        if (!pickaxe.found()) {
            if (debug.get()) error("MiningStart: Lost pickaxe. Resetting.");
            reset();
            return;
        }

        prevSlot = mc.player.getInventory().selectedSlot;
        InvUtils.swap(pickaxe.slot(), false);

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(currentPos), Rotations.getPitch(currentPos));
        }

        if (debug.get()) info("MiningStart: Sending START_DESTROY_BLOCK for %s.", currentPos);
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, currentPos, currentDir));

        mode = State.MiningHold;
        timer = 0; // The hold is effectively instant, just a state transition
    }

    private void handleMiningHold() {
        // Timer handled in onTick
        finishMining();
    }

    private void finishMining() {
        if (debug.get()) info("FinishMining: Sending STOP_DESTROY_BLOCK for %s.", currentPos);
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, currentPos, currentDir.getOpposite()));

        if (swapBack.get() && prevSlot != -1) {
            InvUtils.swap(prevSlot, false);
            prevSlot = -1;
        }

        mc.player.swingHand(Hand.MAIN_HAND);
        if (debug.get()) info("FinishMining: Set block to AIR client-side.");
        mc.world.setBlockState(currentPos, Blocks.AIR.getDefaultState());

        if (breakMode.get() == BreakMode.Instant) {
            if (placeTarget != null) {
                if (debug.get()) info("FinishMining: Instant mode, cycling back to Placing.");
                mode = State.Placing;
                attempts = 0;
                restorationCount = 0;
                timer = delay.get();
            } else {
                if (debug.get()) info("FinishMining: Instant mode, no place target, resetting.");
                reset();
            }
        } else {
            if (debug.get()) info("FinishMining: Safe mode, waiting for server confirmation.");
            mode = State.WaitingBreak;
            timer = 40; // Ghost block suppression timeout
        }
    }

    private Direction getBreakDirection(BlockPos pos) {
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d blockCenter = pos.toCenterPos();
        Vec3d diff = eyePos.subtract(blockCenter);
        return Direction.getFacing(diff.x, diff.y, diff.z);
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (currentPos == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket p) {
            if (p.getPos().equals(currentPos)) {
                if (debug.get()) info("OnPacket: Received BlockUpdateS2CPacket for target pos. New state is air: %b", p.getState().isAir());
            }
            handleBlockUpdate(p.getPos(), p.getState().isAir());
        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket p) {
            p.visitUpdates((pos, state) -> {
                if (pos.equals(currentPos)) {
                    if (debug.get()) info("OnPacket: Received ChunkDeltaUpdateS2CPacket for target pos. New state is air: %b", state.isAir());
                }
                handleBlockUpdate(pos, state.isAir());
            });
        }
    }

    private void handleBlockUpdate(BlockPos pos, boolean isAir) {
        if (currentPos == null || !pos.equals(currentPos)) return;

        // This handles the case where we do a client-side place, but the server rejects it.
        // If we are trying to mine, but the server says the block is air, our placement failed.
        if (isAir && (mode == State.MiningStart || mode == State.MiningHold)) {
            if (debug.get()) error("Server rejected placement at %s. Retrying placement.", pos);
            mode = State.Placing;
            timer = delay.get();
            return;
        }

        if (mode == State.WaitingBreak) {
            if (isAir) {
                // Break confirmed
                if (debug.get()) info("HandleBlockUpdate: Break confirmed by server.");
                if (placeTarget != null) {
                    // Loop back to placing
                    mode = State.Placing;
                    attempts = 0;
                    restorationCount = 0;
                    timer = delay.get();
                } else {
                    reset();
                }
            } else {
                // Block restored (Desync or failure)
                if (debug.get()) info("HandleBlockUpdate: Block restored by server.");
                handleRestoration();
            }
        } else if (breakMode.get() == BreakMode.Instant && !isAir) {
            // Block restored in instant mode, interrupt and retry
            if (debug.get()) info("HandleBlockUpdate: Block restored in Instant mode. Interrupting and retrying.");
            handleRestoration();
        }
    }

    private void handleRestoration() {
        restorationCount++;
        if (restorationCount > 3) { // restorationLimit
            if (debug.get()) error("handleRestoration: Restoration limit exceeded.");
            error("Block restored too many times, aborting.");
            reset(); // Give up
        } else {
            if (debug.get()) info("Block restored by server, retrying break... (%d/%d)", restorationCount, 3);
            if (debug.get()) info("handleRestoration: Retrying break. Count: %d", restorationCount);
            mode = State.MiningStart; // Retry break
            timer = delay.get();
        }
    }

    // ── Pickaxe selection ─────────────────────────────────────────────────────

    private FindItemResult findPickaxe() {
        // Prioritize non-silk touch for obsidian drops
        FindItemResult r = InvUtils.find(s -> s.getItem() == Items.NETHERITE_PICKAXE && !hasSilkTouch(s));
        if (r.found()) {
            if (debug.get()) info("Found non-silk Netherite Pickaxe in slot %d.", r.slot());
            return r;
        }

        r = InvUtils.find(s -> s.getItem() == Items.DIAMOND_PICKAXE && !hasSilkTouch(s));
        if (r.found()) {
            if (debug.get()) info("Found non-silk Diamond Pickaxe in slot %d.", r.slot());
            return r;
        }

        // Fallback to any pickaxe if no non-silk touch is available
        r = InvUtils.find(Items.NETHERITE_PICKAXE);
        if (r.found()) {
            if (debug.get()) info("Found fallback Netherite Pickaxe in slot %d.", r.slot());
            return r;
        }

        r = InvUtils.find(Items.DIAMOND_PICKAXE);
        if (r.found()) {
            if (debug.get()) info("Found fallback Diamond Pickaxe in slot %d.", r.slot());
        } else {
            if (debug.get()) info("No suitable pickaxe found.");
        }
        return r;
    }

    private boolean hasSilkTouch(ItemStack stack) {
        ItemEnchantmentsComponent enchants = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        return enchants.getEnchantments().stream().anyMatch(e -> e.matchesKey(Enchantments.SILK_TOUCH));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (currentPos == null) return;

        if (render.get()) {
            event.renderer.box(currentPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
