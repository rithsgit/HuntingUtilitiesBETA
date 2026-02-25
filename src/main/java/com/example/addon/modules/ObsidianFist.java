package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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
import net.minecraft.item.Item;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
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

    private final Setting<PickaxeMode> pickaxeMode = sgGeneral.add(new EnumSetting.Builder<PickaxeMode>()
        .name("pickaxe-mode")
        .description("Which pickaxe to use.")
        .defaultValue(PickaxeMode.NoSilkTouch)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the block when mining/placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Swaps back to the previous slot after mining.")
        .defaultValue(true)
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

    private final Setting<Integer> holdTicks = sgGeneral.add(new IntSetting.Builder()
        .name("hold-ticks")
        .description("Ticks to hold the start-break packet. (1-3 recommended for strict servers)")
        .defaultValue(1)
        .min(0)
        .max(10)
        .build()
    );

    private final Setting<Boolean> verifyPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("verify-place")
        .description("Wait for server to confirm placement before mining.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> retryDelay = sgGeneral.add(new IntSetting.Builder()
        .name("retry-delay")
        .description("Ticks to wait before retrying if the server rejects the break.")
        .defaultValue(5)
        .min(0)
        .build()
    );

    private final Setting<Integer> maxRetries = sgGeneral.add(new IntSetting.Builder()
        .name("max-retries")
        .description("Maximum retries for failed operations before aborting.")
        .defaultValue(3)
        .min(1)
        .build()
    );

    private final Setting<Integer> restorationLimit = sgGeneral.add(new IntSetting.Builder()
        .name("restoration-limit")
        .description("Max times to attempt breaking if server restores the block (Desync protection).")
        .defaultValue(2)
        .min(1)
        .build()
    );

    private final Setting<Boolean> strict = sgGeneral.add(new BoolSetting.Builder()
        .name("strict")
        .description("Sends extra packets (ABORT/STOP on different face) to bypass stricter anticheats.")
        .defaultValue(false)
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

    private enum Mode {
        Idle, Placing, WaitingPlace, MiningStart, MiningHold, WaitingBreak
    }

    private Mode mode = Mode.Idle;
    private BlockPos currentPos;
    private Direction currentDir;
    private int timer;
    private int attempts;
    private int restorationCount;

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
        mode = Mode.Idle;
        currentPos = null;
        currentDir = null;
        timer = 0;
        attempts = 0;
        restorationCount = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (timer > 0) {
            timer--;
            if (mode == Mode.MiningHold && timer == 0) {
                // Hold timer finished, proceed
            } else {
                return;
            }
        }

        // State machine loop to allow instant transitions if delays are 0
        int steps = 0;
        while (steps < 10) {
            Mode prevMode = mode;
            runStateMachine();
            if (mode == prevMode && mode != Mode.Idle) break; // Waiting for something
            if (mode == Mode.Idle && prevMode == Mode.Idle) break; // Nothing to do
            steps++;
        }
    }

    private void runStateMachine() {
        switch (mode) {
            case Idle -> handleIdle();
            case Placing -> handlePlacing();
            case WaitingPlace -> { /* Handled in onPacket or timeout */ }
            case MiningStart -> handleMiningStart();
            case MiningHold -> handleMiningHold();
            case WaitingBreak -> { /* Handled in onPacket */ }
        }
    }

    private void handleIdle() {
        FindItemResult pickaxe = findPickaxe();
        if (!pickaxe.found()) return;

        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();

        if (mc.player.squaredDistanceTo(pos.toCenterPos()) > range.get() * range.get()) return;

        // 1. Existing Ender Chest
        if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
            currentPos = pos;
            currentDir = hit.getSide();
            mode = Mode.MiningStart;
            attempts = 0;
            restorationCount = 0;
            return;
        }

        // 2. Place and Mine
        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) return;

        BlockPos placePos = pos.offset(hit.getSide());
        if (!BlockUtils.canPlace(placePos)) return;
        if (!mc.world.getFluidState(placePos).isEmpty()) return;
        if (mc.world.isOutOfHeightLimit(placePos)) return;

        currentPos = placePos;
        currentDir = getBreakDirection(placePos); // Direction to mine from
        mode = Mode.Placing;
        attempts = 0;
        restorationCount = 0;

        // Store interaction data for placement
        placeTarget = pos;
        placeSide = hit.getSide();
    }

    // Temp storage for placement
    private BlockPos placeTarget;
    private Direction placeSide;

    private void handlePlacing() {
        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) {
            reset();
            return;
        }

        int prevSlot = mc.player.getInventory().selectedSlot;
        InvUtils.swap(echest.slot(), false);

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

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
        mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));

        if (sneaking) mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));

        if (swapBack.get()) mc.player.getInventory().selectedSlot = prevSlot;

        if (verifyPlace.get()) {
            mode = Mode.WaitingPlace;
            timer = 10; // Timeout for placement
        } else {
            mode = Mode.MiningStart;
        }
    }

    private void handleMiningStart() {
        FindItemResult pickaxe = findPickaxe();
        if (!pickaxe.found()) {
            reset();
            return;
        }

        int prevSlot = mc.player.getInventory().selectedSlot;
        InvUtils.swap(pickaxe.slot(), false);

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(currentPos), Rotations.getPitch(currentPos));
        }

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, currentPos, currentDir));
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, currentPos, currentDir));

        if (holdTicks.get() > 0) {
            mode = Mode.MiningHold;
            timer = holdTicks.get();
        } else {
            finishMining();
        }

        if (swapBack.get()) mc.player.getInventory().selectedSlot = prevSlot;
    }

    private void handleMiningHold() {
        // Timer handled in onTick
        finishMining();
    }

    private void finishMining() {
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, currentPos, currentDir));

        if (strict.get()) {
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, currentPos, currentDir));
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, currentPos, currentDir.getOpposite()));
        }

        mc.player.swingHand(Hand.MAIN_HAND);
        mc.world.setBlockState(currentPos, Blocks.AIR.getDefaultState());

        mode = Mode.WaitingBreak;
        timer = 40; // Ghost block suppression timeout
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
            handleBlockUpdate(p.getPos(), p.getState().isAir());
        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket p) {
            p.visitUpdates((pos, state) -> {
                handleBlockUpdate(pos, state.isAir());
            });
        }
    }

    private void handleBlockUpdate(BlockPos pos, boolean isAir) {
        if (!pos.equals(currentPos)) return;

        if (mode == Mode.WaitingPlace && !isAir) {
            // Placement confirmed
            mode = Mode.MiningStart;
            timer = 0;
        } else if (mode == Mode.WaitingBreak) {
            if (isAir) {
                // Break confirmed
                reset();
            } else {
                // Block restored (Desync or failure)
                restorationCount++;
                if (restorationCount > restorationLimit.get()) {
                    reset(); // Give up
                } else {
                    mode = Mode.MiningStart; // Retry break
                    timer = retryDelay.get();
                }
            }
        }
    }

    // ── Pickaxe selection ─────────────────────────────────────────────────────

    private FindItemResult findPickaxe() {
        FindItemResult r = findPickaxeOfType(Items.NETHERITE_PICKAXE);
        if (r.found()) return r;
        r = findPickaxeOfType(Items.DIAMOND_PICKAXE);
        if (r.found()) return r;
        return InvUtils.find(s -> s.getItem() == Items.NETHERITE_PICKAXE || s.getItem() == Items.DIAMOND_PICKAXE);
    }

    private FindItemResult findPickaxeOfType(Item item) {
        return InvUtils.find(s -> {
            if (s.getItem() != item) return false;
            boolean silk = hasSilkTouch(s);
            return switch (pickaxeMode.get()) {
                case NoSilkTouch -> !silk;
                case SilkTouch   ->  silk;
                case Any         -> true;
            };
        });
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

    public enum PickaxeMode {
        NoSilkTouch, SilkTouch, Any
    }
}
