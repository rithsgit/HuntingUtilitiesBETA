package com.example.addon.modules;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
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
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class PortalMaker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks to wait between placement actions.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Show remaining portal frame positions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the preview boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(80, 160, 255, 35))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(100, 180, 255, 230))
        .build()
    );

    private final Setting<Boolean> autoEnter = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-enter")
        .description("Automatically enter the portal after it is created.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> finishDelay = sgGeneral.add(new IntSetting.Builder()
        .name("finish-delay")
        .description("Ticks to wait after lighting the portal before turning off.")
        .defaultValue(20)
        .min(0)
        .sliderMax(200)
        .build()
    );

    public final List<BlockPos> portalFramePositions = new ArrayList<>();
    private int placementIndex = 0;
    private int tickTimer = 0;
    private int finishTimer = 0;

    public PortalMaker() {
        super(HuntingUtilities.CATEGORY, "portal-maker", "Builds and lights a minimal Nether portal (10 obsidian).");
    }

    @Override
    public void onActivate() {
        portalFramePositions.clear();
        placementIndex = 0;
        tickTimer = 0;
        finishTimer = 0;

        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        // Quick inventory check
        if (!hasItemInHotbar(Items.OBSIDIAN)) {
            int total = countItem(Items.OBSIDIAN);
            if (total > 0) warning("Obsidian is in inventory but not hotbar!");
        }
        int obsidianCount = countItem(Items.OBSIDIAN); // Keep total count check
        if (obsidianCount < 10) {
            error("Need at least 10 obsidian (found " + obsidianCount + ")");
            toggle();
            return;
        }

        if (!hasItem(Items.FLINT_AND_STEEL)) {
            warning("No flint & steel found — light manually.");
        }

        Direction facing = mc.player.getHorizontalFacing();
        Direction right = facing.rotateYClockwise();

        BlockPos feet = mc.player.getBlockPos();
        boolean adjusted = false;

        // Minor adjustment if standing in/above non-solid block
        if (!mc.world.getBlockState(feet.down()).isFullCube(mc.world, feet.down())) {
            feet = feet.up();
            adjusted = true;
        }

        BlockPos origin = feet.offset(facing, 2).offset(right, -1);

        // Minimal 10-block portal frame (inside 2×3 opening)
        // Order: bottom → left side → right side → top
        portalFramePositions.add(origin.offset(right, 1));           // bottom 1
        portalFramePositions.add(origin.offset(right, 2));           // bottom 2
        portalFramePositions.add(origin.up(1));                      // left 1
        portalFramePositions.add(origin.up(2));                      // left 2
        portalFramePositions.add(origin.up(3));                      // left 3
        portalFramePositions.add(origin.offset(right, 3).up(1));     // right 1
        portalFramePositions.add(origin.offset(right, 3).up(2));     // right 2
        portalFramePositions.add(origin.offset(right, 3).up(3));     // right 3
        portalFramePositions.add(origin.offset(right, 1).up(4));     // top 1
        portalFramePositions.add(origin.offset(right, 2).up(4));     // top 2

        if (adjusted) {
            BlockPos stepPos = feet.offset(facing, 1);
            if (mc.world.getBlockState(stepPos).isReplaceable()) portalFramePositions.add(stepPos);
        }

        // Check for obstructions
        boolean blocked = portalFramePositions.stream()
            .anyMatch(p -> !mc.world.getBlockState(p).isReplaceable());

        if (blocked) {
            error("Portal area is obstructed. Move slightly and try again.");
            toggle();
            return;
        }

        // Check if already mostly built
        long existing = portalFramePositions.stream()
            .filter(p -> mc.world.getBlockState(p).getBlock() == Blocks.OBSIDIAN)
            .count();

        if (existing >= 9) {
            info("Portal frame looks complete → attempting to light it.");
            placementIndex = portalFramePositions.size(); // skip building
        }

        // Try to select obsidian
        selectHotbarItem(Items.OBSIDIAN);

        info("Building minimal Nether portal...");
    }

    @Override
    public void onDeactivate() {
        portalFramePositions.clear();
        placementIndex = 0;
        tickTimer = 0;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (mc.world.getBlockState(mc.player.getBlockPos()).getBlock() == Blocks.NETHER_PORTAL) {
            toggle();
            return;
        }

        // Check for any missing blocks and fix them before proceeding
        placementIndex = portalFramePositions.size();
        for (int i = 0; i < portalFramePositions.size(); i++) {
            if (mc.world.getBlockState(portalFramePositions.get(i)).getBlock() != Blocks.OBSIDIAN) {
                placementIndex = i;
                break;
            }
        }

        // Must hold obsidian to build
        if (placementIndex < portalFramePositions.size()) {
            if (!mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
                FindItemResult obsidian = InvUtils.find(Items.OBSIDIAN);
                if (!obsidian.found()) {
                    error("No obsidian found → disabled.");
                    toggle();
                    return;
                }

                if (obsidian.isHotbar()) {
                    mc.player.getInventory().selectedSlot = obsidian.slot();
                } else {
                    InvUtils.move().from(obsidian.slot()).toHotbar(mc.player.getInventory().selectedSlot);
                }
            }

            tickTimer++;
            if (tickTimer < placeDelay.get()) return;
            tickTimer = 0;

            // Place one block per valid tick, respecting the delay.
            BlockPos target = portalFramePositions.get(placementIndex);

            if (mc.world.getBlockState(target).getBlock() == Blocks.OBSIDIAN) {
                placementIndex++;
                return; // Skip to next tick to check next block
            }

            if (!mc.world.getBlockState(target).isReplaceable()) {
                // Basic break attempt if obstructed
                mc.interactionManager.attackBlock(target, mc.player.getHorizontalFacing().getOpposite());
                mc.player.swingHand(Hand.MAIN_HAND);
                return; // Wait for it to break
            }

            // Face the block for better placement compatibility
            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), () -> {
                BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(target),
                    Direction.UP,  // Most reliable for placing obsidian frames
                    target,
                    false
                );
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
            });

            placementIndex++;
        }

        // All blocks placed → light
        if (placementIndex >= portalFramePositions.size()) {
            if (isPortalLit()) {
                if (autoEnter.get()) moveToPortal();

                if (finishTimer++ >= finishDelay.get()) {
                    if (autoEnter.get()) error("Failed to enter portal.");
                    else info("PortalMaker finished.");
                    toggle();
                }
            } else {
                finishTimer = 0;
                if (tickTimer++ >= 10) {
                    lightPortal();
                    tickTimer = 0;
                }
            }
        }
    }


    private void lightPortal() {
        if (portalFramePositions.isEmpty()) return;

        if (!selectHotbarItem(Items.FLINT_AND_STEEL)) {
            warning("Cannot find flint & steel in hotbar.");
            return;
        }

        // Try lighting the two bottom obsidian blocks
        BlockPos bottom1 = portalFramePositions.get(0);
        BlockPos bottom2 = portalFramePositions.get(1);

        for (BlockPos pos : new BlockPos[]{bottom1, bottom2}) {
            if (mc.world.getBlockState(pos.up()).isAir()) {
                Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                    BlockHitResult hit = new BlockHitResult(
                        Vec3d.ofCenter(pos).add(0, 0.5, 0), // Top face of obsidian
                        Direction.UP,
                        pos,
                        false
                    );
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
        return mc.world.getBlockState(p1).getBlock() == Blocks.NETHER_PORTAL ||
            mc.world.getBlockState(p2).getBlock() == Blocks.NETHER_PORTAL;
    }

    private void moveToPortal() {
        if (portalFramePositions.size() < 2 || mc.player == null || mc.world == null) return;

        // 1. Define Target
        BlockPos p1 = portalFramePositions.get(0).up();
        BlockPos p2 = portalFramePositions.get(1).up();
        Vec3d portalCenter = new Vec3d(
            (p1.getX() + p2.getX()) / 2.0 + 0.5,
            p1.getY() + 0.5, // Target middle of the portal block vertically
            (p1.getZ() + p2.getZ()) / 2.0 + 0.5
        );

        // 2. Stop Conditions
        if (mc.world.getBlockState(mc.player.getBlockPos()).isOf(Blocks.NETHER_PORTAL)) {
            // We are in, stop all movement.
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            return;
        }
        // The portal floor is at p1.getY(). Don't fall more than 2 blocks below it.
        if (mc.player.getY() < p1.getY() - 2.0) {
            error("Fell too far below the portal. Stopping.");
            toggle();
            return;
        }

        // 3. Rotation
        // Always face the center of the portal for alignment.
        Rotations.rotate(Rotations.getYaw(portalCenter), Rotations.getPitch(portalCenter));

        // 3.5. Scaffolding to prevent falling
        if (mc.player.isOnGround()) {
            Direction facing = mc.player.getHorizontalFacing();
            BlockPos blockInFront = mc.player.getBlockPos().offset(facing);
            BlockPos supportBlock = blockInFront.down();

            // If the block under our next step is air, we need to bridge the gap.
            // Only do this if we are generally facing the portal.
            double yawDiff = MathHelper.wrapDegrees(Rotations.getYaw(portalCenter) - mc.player.getYaw());
            if (Math.abs(yawDiff) < 60 && mc.world.getBlockState(supportBlock).isReplaceable()) {
                if (placeBlock(supportBlock)) {
                    // Wait for the block to be placed.
                    tickTimer = placeDelay.get();
                    return; // Return to wait for delay and block update
                } else {
                    error("Cannot bridge gap to portal (no obsidian?). Stopping.");
                    toggle();
                    return;
                }
            }
        }

        // 4. Movement Control
        // Reset other movement keys
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);

        // Sprinting logic
        double distSq = mc.player.getPos().squaredDistanceTo(portalCenter.x, mc.player.getY(), portalCenter.z);
        // Sprint if we are not too close to avoid overshooting and precise alignment issues.
        mc.options.sprintKey.setPressed(distSq > 3.0 * 3.0);

        // Forward movement logic
        // Only move if we are roughly facing the target to prevent walking sideways into walls.
        double yawDiff = MathHelper.wrapDegrees(Rotations.getYaw(portalCenter) - mc.player.getYaw());
        if (Math.abs(yawDiff) < 45) { // More lenient angle
            mc.options.forwardKey.setPressed(true);
        } else {
            mc.options.forwardKey.setPressed(false);
        }

        // 5. Jumping Control
        // Re-evaluate scaffolding and jumping conditions right before potentially jumping

        Direction facing = mc.player.getHorizontalFacing();        
        BlockPos blockInFront = mc.player.getBlockPos().offset(facing);
        BlockPos supportBlock = blockInFront.down();

        // If the block under our next step is air, we need to bridge the gap.
        double yawDiff2 = MathHelper.wrapDegrees(Rotations.getYaw(portalCenter) - mc.player.getYaw());
        if (mc.player.isOnGround() && Math.abs(yawDiff) < 60 && mc.world.getBlockState(supportBlock).isReplaceable()) {
            if (placeBlock(supportBlock)) {

                // Wait for the block to be placed.
                tickTimer = placeDelay.get();
                return; // Return to wait for delay and block update
            } else {
                error("Cannot bridge gap to portal (no obsidian?). Stopping.");
                toggle();
                return;
            }
         }



        // Jump or walk into portal
        if (mc.player.isOnGround()) {
           // Jump if we are physically colliding with something.
            if (mc.player.horizontalCollision) {

                mc.player.jump();
                return; // Let the jump happen before next evaluation
            }

            // Proactive jump: check for a 1-block step-up.            
            if (!mc.world.getBlockState(blockInFront).isReplaceable() && mc.world.getBlockState(blockInFront.up()).isReplaceable()) {
                mc.player.jump();
                return;
            }

            // Jump if we are close and below the portal entrance to hop in.
            if (distSq < 2.5 * 2.5 && mc.player.getY() < p1.getY()) {
                mc.player.jump();
            }
        }
    }




    private boolean placeBlock(BlockPos pos) {
        // Find neighbor to place against
        BlockPos neighbor = null;
        Direction placeSide = null;
        for (Direction side : Direction.values()) {
            BlockPos check = pos.offset(side);
            if (!mc.world.getBlockState(check).isReplaceable()) {
                neighbor = check;
                placeSide = side.getOpposite();
                break;
            }
        }
        if (neighbor == null) return false;

        final BlockPos finalNeighbor = neighbor;
        final Direction finalPlaceSide = placeSide;

        // Ensure obsidian is in hand
        if (!mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
            FindItemResult obsidian = InvUtils.find(Items.OBSIDIAN);
            if (!obsidian.found()) return false;
            if (obsidian.isHotbar()) {
                mc.player.getInventory().selectedSlot = obsidian.slot();
            } else {
                InvUtils.move().from(obsidian.slot()).toHotbar(mc.player.getInventory().selectedSlot);
            }
        }

        // Place the block
        Rotations.rotate(Rotations.getYaw(finalNeighbor), Rotations.getPitch(finalNeighbor), () -> {
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(finalNeighbor), finalPlaceSide, finalNeighbor, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        });

        return true;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || portalFramePositions.isEmpty()) return;

        for (int i = placementIndex; i < portalFramePositions.size(); i++) {
            BlockPos pos = portalFramePositions.get(i);
            if (mc.world.getBlockState(pos).isReplaceable()) {
                event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }






    // ── Helper methods ────────────────────────────────────────

    private boolean selectHotbarItem(Item targetItem) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == targetItem) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }

    private boolean hasItemInHotbar(Item targetItem) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == targetItem) {
                return true;
            }
        }
        return false;
    }

    private int countItem(Item targetItem) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == targetItem) {
                count += mc.player.getInventory().getStack(i).getCount();
            }
        }
        return count;
    }

    private boolean hasItem(Item targetItem) {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == targetItem) {
                return true;
            }
        }
        return false;
    }
}