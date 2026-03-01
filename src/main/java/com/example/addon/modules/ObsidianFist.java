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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
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
    private final SettingGroup sgRepair = settings.createGroup("Auto Repair");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the block when mining/placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoRepair = sgRepair.add(new BoolSetting.Builder()
        .name("auto-repair")
        .description("Automatically repairs your pickaxe with XP bottles when its durability is low.")
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
        .description("How many XP bottles to throw in one burst.")
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

    private final Setting<Integer> burstCount = sgGeneral.add(new IntSetting.Builder()
        .name("burst-count")
        .description("Number of ender chest place-break cycles to perform in one burst.")
        .defaultValue(8)
        .min(1)
        .sliderMax(16)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between place-break cycles (0 = instant chain).")
        .defaultValue(0)
        .min(0)
        .sliderMax(5)
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
    private int burstCyclesDone = 0;
    // Auto Repair state
    private boolean isRepairing = false;
    private int repairTimer = 0;

    public ObsidianFist() {
        super(HuntingUtilities.CATEGORY, "obsidian-fist", "Place-break Ender Chests. Fully self-contained, no other modules required.");
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
        mode = State.Idle;
        currentPos = null;
        currentDir = null;
        timer = 0;
        attempts = 0;
        restorationCount = 0;
        placeTarget = null;
        placeSide = null;
        prevSlot = -1;
        burstCyclesDone = 0;
        isRepairing = false;
        repairTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Auto-repair logic
        if (autoRepair.get()) {
            FindItemResult pickaxe = findPickaxe();
            ItemStack stack = pickaxe.found() ? mc.player.getInventory().getStack(pickaxe.slot()) : ItemStack.EMPTY;
            boolean needsRepair = pickaxe.found() && stack.isDamaged() && (stack.getMaxDamage() - stack.getDamage() <= repairThreshold.get());

            if (needsRepair) {
                isRepairing = true;
            } else if (isRepairing) {
                isRepairing = false;
                info("Pickaxe repaired.");
            }

            if (isRepairing) {
                handleRepair(pickaxe);
                // Halt main obsidian fist logic while repairing
                return;
            }
        }

        if (timer > 0) {
            timer--;
            if (timer > 0) return; // If timer is still running, wait.

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
            return;
        }
        BlockPos pos = hit.getBlockPos();

        if (mc.player.squaredDistanceTo(pos.toCenterPos()) > range.get() * range.get()) {
            return;
        }

        // 1. Existing Ender Chest - mine it then instantly replace and continue burst
        if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
            currentPos = pos;
            currentDir = hit.getSide();
            placeTarget = pos.offset(hit.getSide().getOpposite()); // Block to place against when replacing
            placeSide = hit.getSide();
            mode = State.MiningStart;
            attempts = 0;
            restorationCount = 0;
            burstCyclesDone = 0;
            return;
        }

        // 2. Place and Mine
        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) {
            return;
        }

        BlockPos placePos = pos.offset(hit.getSide());
        if (!canPlace(placePos)) {
            return;
        }
        if (!mc.world.getFluidState(placePos).isEmpty()) {
            return;
        }
        if (mc.world.isOutOfHeightLimit(placePos)) {
            return;
        }

        currentPos = placePos;
        currentDir = getBreakDirection(placePos); // Direction to mine from
        mode = State.Placing;
        attempts = 0;
        restorationCount = 0;
        burstCyclesDone = 0;

        // Store interaction data for placement
        placeTarget = pos;
        placeSide = hit.getSide();
    }

    private void handlePlacing() {
        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) {
            reset();
            return;
        }

        Runnable placeLogic = () -> {
            int prevSlot = mc.player.getInventory().selectedSlot;
            if (!InvUtils.swap(echest.slot(), false)) return;

            boolean sneaking = !mc.player.isSneaking() && isClickable(mc.world.getBlockState(placeTarget).getBlock());
            if (sneaking) mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));

            BlockHitResult placeHit = new BlockHitResult(
                new Vec3d(placeTarget.getX() + 0.5 + placeSide.getOffsetX() * 0.5, placeTarget.getY() + 0.5 + placeSide.getOffsetY() * 0.5, placeTarget.getZ() + 0.5 + placeSide.getOffsetZ() * 0.5),
                placeSide,
                placeTarget,
                false
            );

            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
            mc.player.swingHand(Hand.MAIN_HAND);

            if (sneaking) mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));

            if (swapBack.get()) InvUtils.swap(prevSlot, false);

            mode = State.MiningStart;
            // Always wait 1 tick after placing before attempting to mine.
            // This gives the server time to process the placement and prevents desync.
            timer = 1;
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(placeTarget), Rotations.getPitch(placeTarget), placeLogic);
        } else {
            placeLogic.run();
        }
    }

    private void handleMiningStart() {
        FindItemResult pickaxe = findPickaxe();
        if (!pickaxe.found()) {
            reset();
            return;
        }

        Runnable mineLogic = () -> {
            if (prevSlot == -1) prevSlot = mc.player.getInventory().selectedSlot;
            InvUtils.swap(pickaxe.slot(), false);

            // Use interaction manager directly - self-contained, no BlockUtils/SpeedMine
            if (mc.interactionManager.isBreakingBlock()) {
                mc.interactionManager.updateBlockBreakingProgress(currentPos, currentDir);
            } else {
                mc.interactionManager.attackBlock(currentPos, currentDir);
            }
            mc.player.swingHand(Hand.MAIN_HAND);

            if (breakMode.get() == BreakMode.Instant) {
                doFinishMiningCycle();
            }
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(currentPos), Rotations.getPitch(currentPos), mineLogic);
        } else {
            mineLogic.run();
        }
    }

    private void handleMiningHold() {
        // Timer handled in onTick
        finishMining();
    }

    /** Called when break is confirmed (from packet) or after MiningHold (instant mode). */
    private void doFinishMiningCycle() {
        if (swapBack.get() && prevSlot != -1) {
            InvUtils.swap(prevSlot, false);
            prevSlot = -1;
        }
        mc.player.swingHand(Hand.MAIN_HAND);

        if (breakMode.get() == BreakMode.Instant) {
            if (placeTarget != null) {
                if (burstCyclesDone == 0) {
                    // First break done, cycle to replace -> auto break
                    burstCyclesDone++;
                    mode = State.Placing;
                    attempts = 0;
                    restorationCount = 0;
                    timer = delay.get();
                } else {
                    burstCyclesDone++;
                    if (burstCyclesDone >= burstCount.get()) {
                        reset();
                    } else {
                        mode = State.Placing;
                        attempts = 0;
                        restorationCount = 0;
                        timer = delay.get();
                    }
                }
            } else {
                reset();
            }
        } else {
            mode = State.WaitingBreak;
            timer = 40;
        }
    }

    private void finishMining() {
        doFinishMiningCycle();
    }

    private Direction getBreakDirection(BlockPos pos) {
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d blockCenter = pos.toCenterPos();
        Vec3d diff = eyePos.subtract(blockCenter);
        return Direction.getFacing(diff.x, diff.y, diff.z);
    }

    private boolean canPlace(BlockPos pos) {
        if (!World.isValid(pos)) return false;
        if (!mc.world.getBlockState(pos).isReplaceable()) return false;
        if (!mc.world.getFluidState(pos).isEmpty()) return false;
        return mc.world.canPlace(Blocks.ENDER_CHEST.getDefaultState(), pos, net.minecraft.block.ShapeContext.absent());
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

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (currentPos == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket p) {
            if (p.getPos().equals(currentPos)) {
                handleBlockUpdate(p.getPos(), p.getState().isAir());
            }
        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket p) {
            p.visitUpdates((pos, state) -> {
                if (pos.equals(currentPos)) {
                    handleBlockUpdate(pos, state.isAir());
                }
            });
        }
    }

    private void handleBlockUpdate(BlockPos pos, boolean isAir) {
        if (currentPos == null || !pos.equals(currentPos)) return;

        // Air in MiningStart = block broke (we call attackBlock/updateBlockBreakingProgress each tick until server confirms)
        if (isAir && mode == State.MiningStart) {
            doFinishMiningCycle();
            return;
        }

        // Air in MiningHold = late break confirmation from previous cycle, ignore (we've already placed next)
        if (isAir && mode == State.MiningHold) {
            return;
        }

        if (mode == State.WaitingBreak) {
            if (isAir) {
                // Break confirmed
                if (placeTarget != null) {
                    burstCyclesDone++;
                    if (burstCyclesDone >= burstCount.get()) {
                        reset();
                    } else {
                        mode = State.Placing;
                        attempts = 0;
                        restorationCount = 0;
                        timer = delay.get();
                    }
                } else {
                    reset();
                }
            } else {
                // Block restored (Desync or failure)
                handleRestoration();
            }
        } else if (breakMode.get() == BreakMode.Instant && !isAir && mode == State.Placing) {
            // Block still there while we want to place: our previous break failed, retry break
            handleRestoration();
        }
        // MiningStart + block present = placement confirmed, ignore (block being there is correct)
    }

    private void handleRestoration() {
        restorationCount++;
        if (restorationCount > 3) { // restorationLimit
            error("Block restored too many times, aborting.");
            reset(); // Give up
        } else {
            mode = State.MiningStart; // Retry break
            timer = delay.get();
        }
    }

    private void handleRepair(FindItemResult pickaxe) {
        if (mode != State.Idle) reset();

        if (repairTimer > 0) {
            repairTimer--;
            return;
        }

        // 1. Ensure pickaxe is in main hand to receive XP
        if (!pickaxe.isMainHand()) {
            InvUtils.swap(pickaxe.slot(), false);
        }

        // 2. Find and position XP bottles in offhand
        if (!mc.player.getOffHandStack().isOf(Items.EXPERIENCE_BOTTLE)) {
            FindItemResult xp = InvUtils.find(Items.EXPERIENCE_BOTTLE);
            if (!xp.found()) {
                error("No XP bottles found. Disabling auto-repair.");
                autoRepair.set(false);
                isRepairing = false;
                return;
            }
            InvUtils.move().from(xp.slot()).toOffhand();
            // Wait a couple ticks for item move to register
            repairTimer = 2;
            return;
        }

        // 3. Throw XP from offhand while looking down
        Rotations.rotate(mc.player.getYaw(), 90, () -> {
            for (int i = 0; i < repairPacketsPerBurst.get(); i++) {
                mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            }
        });

        repairTimer = repairBurstDelay.get();
    }

    // ── Pickaxe selection ─────────────────────────────────────────────────────

    private FindItemResult findPickaxe() {
        // Prioritize non-silk touch for obsidian drops
        FindItemResult r = InvUtils.find(s -> s.getItem() == Items.NETHERITE_PICKAXE && !hasSilkTouch(s));
        if (r.found()) {
            return r;
        }

        r = InvUtils.find(s -> s.getItem() == Items.DIAMOND_PICKAXE && !hasSilkTouch(s));
        if (r.found()) {
            return r;
        }

        // Fallback to any pickaxe if no non-silk touch is available
        r = InvUtils.find(Items.NETHERITE_PICKAXE);
        if (r.found()) {
            return r;
        }

        r = InvUtils.find(Items.DIAMOND_PICKAXE);
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
