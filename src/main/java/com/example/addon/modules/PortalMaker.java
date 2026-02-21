package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

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

        // Minor adjustment if standing in/above non-solid block
        if (!mc.world.getBlockState(feet.down()).isFullCube(mc.world, feet.down())) {
            feet = feet.up();
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
            if (!(mc.player.getMainHandStack().getItem() instanceof net.minecraft.item.BlockItem bi) ||
                bi.getBlock().asItem() != Items.OBSIDIAN) {
                if (!selectHotbarItem(Items.OBSIDIAN)) {
                    error("No obsidian in hotbar → disabled.");
                    toggle();
                    return;
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
            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), null);

            BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(target),
                Direction.UP,  // Most reliable for placing obsidian frames
                target,
                false
            );

            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);

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
                BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(pos).add(0, 0.5, 0), // Top face of obsidian
                    Direction.UP,
                    pos,
                    false
                );
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
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
        if (portalFramePositions.size() < 2) return;
        BlockPos p1 = portalFramePositions.get(0).up();
        BlockPos p2 = portalFramePositions.get(1).up();

        // Center of the 2x1 portal opening
        double cx = (p1.getX() + p2.getX()) / 2.0 + 0.5;
        double cz = (p1.getZ() + p2.getZ()) / 2.0 + 0.5;
        double targetY = p1.getY();
        Vec3d center = new Vec3d(cx, targetY, cz);

        // Check surroundings / failure
        if (mc.player.getY() < targetY - 2.0) {
            error("I missed, Stopping..");
            toggle();
            return;
        }

        double yDiff = targetY - mc.player.getY();
        double dist = Math.sqrt(mc.player.squaredDistanceTo(cx, mc.player.getY(), cz));

        // Check if we need to place a block to make the jump (pillar up)
        if (yDiff > 1.1 && dist < 4.0) {
            if (selectHotbarItem(Items.OBSIDIAN)) {
                Rotations.rotate(mc.player.getYaw(), 90); // Look down
                if (mc.player.isOnGround()) {
                    mc.player.jump();
                } else if (mc.player.getY() % 1 > 0.4) {
                    BlockPos feet = mc.player.getBlockPos();
                    if (mc.world.getBlockState(feet).isReplaceable()) {
                        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(feet), Direction.DOWN, feet, false);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
                return; // Focus on placing, don't move yet
            }
        }

        // --- Pathfinding Movement Logic ---

        // 1. Stop other movement keys to avoid conflicts
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);

        // 2. Rotate to face the portal's center
        Rotations.rotate(Rotations.getYaw(center), 0);

        // 3. Smart jumping based on obstacles
        // This logic decides if a jump is necessary to overcome an obstacle.
        if (mc.player.isOnGround()) {
            Direction facing = mc.player.getHorizontalFacing();
            BlockPos blockInFront = mc.player.getBlockPos().offset(facing);

            // Check if there's a wall in front that isn't just a 1-block step-up.
            boolean isWall = !mc.world.getBlockState(blockInFront).isReplaceable() && !mc.world.getBlockState(blockInFront.up()).isReplaceable();

            if (mc.player.horizontalCollision || isWall) {
                // Jump if we are physically colliding with something, or if we see a wall.
                mc.player.jump();
            } else if (dist < 2.5 && yDiff > 0) {
                // Hop up if we are right below the portal entrance.
                mc.player.jump();
            }
        }

        // 4. Always press forward and sprint to move towards the portal
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
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

    private void replenishObby() {
        // Check hotbar for obby
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.OBSIDIAN)) return;
        }

        // Find obby in main inventory
        int invSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.OBSIDIAN)) {
                invSlot = i;
                break;
            }
        }

        if (invSlot != -1) {
            int hotbarSlot = mc.player.getInventory().selectedSlot;
            InvUtils.move().from(invSlot).toHotbar(hotbarSlot);
        }
    }



}