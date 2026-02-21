package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.game.GameLeftEvent; // Import for disconnect event
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.GlowItemFrameEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LootLens extends Module {
    private final Map<BlockPos, StorageType> containers = new HashMap<>();
    private final Set<BlockPos> checkedContainers = new HashSet<>();
    private final Set<BlockPos> shulkerContainers = new HashSet<>();
    private final Map<BlockPos, Integer> shulkerCounts = new HashMap<>();
    private final Map<BlockPos, Integer> stackedMinecartCounts = new HashMap<>();
    private final Map<Vec3d, ItemFrameEntity> itemFrameEntities = new HashMap<>();
    private final Map<Vec3d, GlowItemFrameEntity> glowItemFrameEntities = new HashMap<>();
    private final Set<Vec3d> notifiedItemFrames = new HashSet<>();

    private BlockPos lastOpenedContainer = null;

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStorage = settings.createGroup("Storage");
    private final SettingGroup sgUtility = settings.createGroup("Utility");
    private final SettingGroup sgContainerButtons = settings.createGroup("Container Buttons");
    private final SettingGroup sgDecorative = settings.createGroup("Decorative");

    // General Settings
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Container detection range in blocks.")
        .defaultValue(128)
        .min(16)
        .max(512)
        .sliderMin(32)
        .sliderMax(256)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Render style.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> shulkerFoundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("shulker-found-color")
        .description("Color for containers with shulkers found.")
        .defaultValue(new SettingColor(0, 255, 0, 150))
        .build()
    );

    private final Setting<Boolean> showBeam = sgGeneral.add(new BoolSetting.Builder()
        .name("show-beam")
        .description("Show beam above containers with shulkers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> beamWidth = sgGeneral.add(new IntSetting.Builder()
        .name("beam-width")
        .description("Default beam width (in hundredths).")
        .defaultValue(15)
        .min(5)
        .max(50)
        .sliderMin(5)
        .sliderMax(50)
        .visible(showBeam::get)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Send chat messages when shulkers are found or removed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> playSound = sgGeneral.add(new BoolSetting.Builder()
        .name("play-sound")
        .description("Play a sound when a shulker is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> customItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("custom-items")
        .description("Additional items to highlight in containers.")
        .defaultValue(List.of(Items.ENCHANTED_GOLDEN_APPLE, Items.ELYTRA))
        .build()
    );

    // Container Buttons
    public final Setting<Boolean> showContainerButtons = sgContainerButtons.add(new BoolSetting.Builder()
        .name("show-container-buttons")
        .description("Shows 'Steal' and 'Dump' buttons on the right side of container GUIs.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> dumpHotbar = sgContainerButtons.add(new BoolSetting.Builder()
        .name("dump-hotbar")
        .description("Whether the 'Dump' button also dumps your hotbar.")
        .defaultValue(false)
        .visible(showContainerButtons::get)
        .build()
    );


    // --- Storage Settings ---
    private final Setting<Boolean> scanChests = sgStorage.add(new BoolSetting.Builder()
        .name("chests")
        .description("Detect chests.")
        .defaultValue(true)
        .onChanged(v -> {
            if (!v) removeContainersOfType(StorageType.CHEST);
        })
        .build()
    );
    private final Setting<SettingColor> chestColor = sgStorage.add(new ColorSetting.Builder()
        .name("chest-color")
        .description("Chest highlight color.")
        .defaultValue(new SettingColor(255, 215, 0, 38))
        .visible(scanChests::get)
        .build()
    );

    private final Setting<Boolean> scanTrappedChests = sgStorage.add(new BoolSetting.Builder()
        .name("trapped-chests")
        .description("Detect trapped chests.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> trappedChestColor = sgStorage.add(new ColorSetting.Builder()
        .name("trapped-chest-color")
        .description("Trapped chest highlight color.")
        .defaultValue(new SettingColor(255, 69, 0, 38))
        .visible(scanTrappedChests::get)
        .build()
    );

    private final Setting<Boolean> scanBarrels = sgStorage.add(new BoolSetting.Builder()
        .name("barrels")
        .description("Detect barrels.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> barrelColor = sgStorage.add(new ColorSetting.Builder()
        .name("barrel-color")
        .description("Barrel highlight color.")
        .defaultValue(new SettingColor(139, 69, 19, 38))
        .visible(scanBarrels::get)
        .build()
    );
    private final Setting<Boolean> scanChestMinecarts = sgStorage.add(new BoolSetting.Builder()
        .name("chest-minecarts")
        .description("Detect chest minecarts.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> chestMinecartColor = sgStorage.add(new ColorSetting.Builder()
        .name("chest-minecart-color")
        .description("Chest minecart highlight color.")
        .defaultValue(new SettingColor(255, 180, 0, 38))
        .visible(scanChestMinecarts::get)
        .build()
    );
    
    private final Setting<Boolean> highlightStacked = sgStorage.add(new BoolSetting.Builder()
        .name("highlight-stacked-minecarts")
        .description("Use different color for stacked chest minecarts (2+).")
        .defaultValue(true)
        .visible(scanChestMinecarts::get)
        .build()
    );
    
    private final Setting<SettingColor> stackedMinecartColor = sgStorage.add(new ColorSetting.Builder()
        .name("stacked-minecart-color")
        .description("Highlight color for stacked chest minecarts.")
        .defaultValue(new SettingColor(255, 0, 255, 100))
        .visible(() -> scanChestMinecarts.get() && highlightStacked.get())
        .build()
    );

    // --- Utility Settings ---
    private final Setting<Boolean> scanFurnaces = sgUtility.add(new BoolSetting.Builder()
        .name("furnaces")
        .description("Detect furnaces, blast furnaces, and smokers.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> furnaceColor = sgUtility.add(new ColorSetting.Builder()
        .name("furnace-color")
        .description("Furnace highlight color.")
        .defaultValue(new SettingColor(192, 192, 192, 38))
        .visible(scanFurnaces::get)
        .build()
    );

    private final Setting<Boolean> scanHoppers = sgUtility.add(new BoolSetting.Builder()
        .name("hoppers")
        .description("Detect hoppers.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> hopperColor = sgUtility.add(new ColorSetting.Builder()
        .name("hopper-color")
        .description("Hopper highlight color.")
        .defaultValue(new SettingColor(64, 64, 64, 38))
        .visible(scanHoppers::get)
        .build()
    );

    private final Setting<Boolean> scanDispensers = sgUtility.add(new BoolSetting.Builder()
        .name("dispensers")
        .description("Detect dispensers.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> dispenserColor = sgUtility.add(new ColorSetting.Builder()
        .name("dispenser-color")
        .description("Dispenser highlight color.")
        .defaultValue(new SettingColor(169, 169, 169, 38))
        .visible(scanDispensers::get)
        .build()
    );

    private final Setting<Boolean> scanDroppers = sgUtility.add(new BoolSetting.Builder()
        .name("droppers")
        .description("Detect droppers.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> dropperColor = sgUtility.add(new ColorSetting.Builder()
        .name("dropper-color")
        .description("Dropper highlight color.")
        .defaultValue(new SettingColor(128, 128, 128, 38))
        .visible(scanDroppers::get)
        .build()
    );

    // --- Decorative Settings ---

    private final Setting<Boolean> scanBrewingStands = sgDecorative.add(new BoolSetting.Builder()
        .name("brewing-stands")
        .description("Detect brewing stands.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> brewingStandColor = sgDecorative.add(new ColorSetting.Builder()
        .name("brewing-stand-color")
        .description("Brewing stand highlight color.")
        .defaultValue(new SettingColor(138, 43, 226, 38))
        .visible(scanBrewingStands::get)
        .build()
    );

    private final Setting<Boolean> scanCrafters = sgDecorative.add(new BoolSetting.Builder()
        .name("crafters")
        .description("Detect crafters.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> crafterColor = sgDecorative.add(new ColorSetting.Builder()
        .name("crafter-color")
        .description("Crafter highlight color.")
        .defaultValue(new SettingColor(160, 82, 45, 38))
        .visible(scanCrafters::get)
        .build()
    );

    private final Setting<Boolean> scanDecoratedPots = sgDecorative.add(new BoolSetting.Builder()
        .name("decorated-pots")
        .description("Detect decorated pots.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> decoratedPotColor = sgDecorative.add(new ColorSetting.Builder()
        .name("decorated-pot-color")
        .description("Decorated pot highlight color.")
        .defaultValue(new SettingColor(205, 133, 63, 38))
        .visible(scanDecoratedPots::get)
        .build()
    );

    private final Setting<Boolean> scanItemFrames = sgDecorative.add(new BoolSetting.Builder()
        .name("item-frames")
        .description("Detect item frames with shulker boxes.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> itemFrameColor = sgDecorative.add(new ColorSetting.Builder()
        .name("item-frame-color")
        .description("Item frame highlight color (only shown when shulker inside).")
        .defaultValue(new SettingColor(255, 100, 255, 150))
        .visible(scanItemFrames::get)
        .build()
    );

    private final Setting<Boolean> scanGlowItemFrames = sgDecorative.add(new BoolSetting.Builder()
        .name("glow-item-frames")
        .description("Detect glow item frames with shulker boxes.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> glowItemFrameColor = sgDecorative.add(new ColorSetting.Builder()
        .name("glow-item-frame-color")
        .description("Glow item frame highlight color (only shown when shulker inside).")
        .defaultValue(new SettingColor(100, 255, 255, 150))
        .visible(scanGlowItemFrames::get)
        .build()
    );



    public LootLens() {
        super(HuntingUtilities.CATEGORY, "loot-lens", "Highlights storage containers that hold shulkers.");
    }

    @Override
    public void onActivate() {
        containers.clear();
        checkedContainers.clear();
        shulkerContainers.clear();
        shulkerCounts.clear();
        stackedMinecartCounts.clear();
        itemFrameEntities.clear();
        glowItemFrameEntities.clear();
        notifiedItemFrames.clear();
        lastOpenedContainer = null;
        
        if (mc.player != null && mc.world != null) {
            info("§bLoot Lens activated");
            if (mc.world.getRegistryKey() != null) {
                lastDimension = mc.world.getRegistryKey().getValue().toString();
            }
        }
    }

    @Override
    public void onDeactivate() {
        containers.clear();
        checkedContainers.clear();
        shulkerContainers.clear();
        shulkerCounts.clear();
        stackedMinecartCounts.clear();
        itemFrameEntities.clear();
        glowItemFrameEntities.clear();
        notifiedItemFrames.clear();
        lastOpenedContainer = null;
    }

    // Auto-disable on disconnect
    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (this.isActive()) toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        try {
            if (mc.world.getRegistryKey() == null) return;
        } catch (Exception e) { return; }

        if (dimensionChangeCooldown > 0) {
            dimensionChangeCooldown--;
            return;
        }

        try {
            String currDim = mc.world.getRegistryKey().getValue().toString();
            if (!currDim.equals(lastDimension)) {
                dimensionChangeCooldown = 0;
                lastDimension = currDim;

                containers.clear();
                checkedContainers.clear();
                shulkerContainers.clear();
                shulkerCounts.clear();
                stackedMinecartCounts.clear();
                lastOpenedContainer = null;
                return;
            }
        } catch (Exception ignored) { return; }

        BlockPos currentPos = mc.player.getBlockPos();
        cleanupDistantContainers();
        scanChestMinecarts();
        scanItemFrames();
        
        int centerChunkX = currentPos.getX() >> 4;
        int centerChunkZ = currentPos.getZ() >> 4;
        scanBlockEntities(centerChunkX, centerChunkZ);
    }
    
    private void scanBlockEntities(int centerChunkX, int centerChunkZ) {
        int rangeBlocks = range.get();
        int chunkRange = (rangeBlocks >> 4) + 1;
        int maxDistSq = rangeBlocks * rangeBlocks;
        BlockPos playerPos = mc.player.getBlockPos();

        for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
            for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                int dx = cx - centerChunkX;
                int dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > (chunkRange + 1) * (chunkRange + 1)) continue;

                WorldChunk chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getPos();
                    if (pos.getSquaredDistance(playerPos) > maxDistSq) continue;
                    
                    if (containers.containsKey(pos)) continue;
                    if (checkedContainers.contains(pos) && !shulkerContainers.contains(pos)) continue;

                    Block block = mc.world.getBlockState(pos).getBlock();
                    StorageType type = null;

                    if (block == Blocks.CHEST && scanChests.get()) type = StorageType.CHEST;
                    else if (block == Blocks.TRAPPED_CHEST && scanTrappedChests.get()) type = StorageType.TRAPPED_CHEST;
                    else if (block == Blocks.BARREL && scanBarrels.get()) type = StorageType.BARREL;
                    else if (scanFurnaces.get() && (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER)) type = StorageType.FURNACE;
                    else if (block == Blocks.DISPENSER && scanDispensers.get()) type = StorageType.DISPENSER;
                    else if (block == Blocks.DROPPER && scanDroppers.get()) type = StorageType.DROPPER;
                    else if (block == Blocks.HOPPER && scanHoppers.get()) type = StorageType.HOPPER;
                    else if (block == Blocks.BREWING_STAND && scanBrewingStands.get()) type = StorageType.BREWING_STAND;
                    else if (block == Blocks.CRAFTER && scanCrafters.get()) type = StorageType.CRAFTER;
                    else if (block == Blocks.DECORATED_POT && scanDecoratedPots.get()) type = StorageType.DECORATED_POT;

                    if (type != null) {
                        containers.put(pos, type);
                    }
                }
            }
        }
    }
    
    private void scanChestMinecarts() {
        if (mc.player == null || mc.world == null || !scanChestMinecarts.get()) return;

        BlockPos playerPos = mc.player.getBlockPos();
        int scanRange = range.get();
        Box searchBox = new Box(
            playerPos.getX() - scanRange, playerPos.getY() - scanRange, playerPos.getZ() - scanRange,
            playerPos.getX() + scanRange, playerPos.getY() + scanRange, playerPos.getZ() + scanRange
        );

        Map<BlockPos, Integer> minecartCountMap = new HashMap<>();
        
        for (ChestMinecartEntity minecart : mc.world.getEntitiesByClass(ChestMinecartEntity.class, searchBox, entity -> true)) {
            BlockPos pos = minecart.getBlockPos();
            minecartCountMap.put(pos, minecartCountMap.getOrDefault(pos, 0) + 1);
            
            if (!containers.containsKey(pos) || containers.get(pos) != StorageType.CHEST_MINECART) {
                containers.put(pos, StorageType.CHEST_MINECART);
            }
        }
        
        stackedMinecartCounts.clear();
        stackedMinecartCounts.putAll(minecartCountMap);
        
        containers.entrySet().removeIf(entry -> {
            if (entry.getValue() == StorageType.CHEST_MINECART) {
                BlockPos pos = entry.getKey();
                if (!minecartCountMap.containsKey(pos)) {
                    checkedContainers.remove(pos);
                    shulkerContainers.remove(pos);
                    shulkerCounts.remove(pos);
                    stackedMinecartCounts.remove(pos);
                    return true;
                }
            }
            return false;
        });
    }

    private void scanItemFrames() {
        if (mc.player == null || mc.world == null) return;
        if (!scanItemFrames.get() && !scanGlowItemFrames.get()) return;

        BlockPos playerPos = mc.player.getBlockPos();
        int scanRange = range.get();
        Box searchBox = new Box(
            playerPos.getX() - scanRange, playerPos.getY() - scanRange, playerPos.getZ() - scanRange,
            playerPos.getX() + scanRange, playerPos.getY() + scanRange, playerPos.getZ() + scanRange
        );

        // Clear old frame entities
        itemFrameEntities.clear();
        glowItemFrameEntities.clear();

        // Scan for item frames (includes glow item frames)
        for (ItemFrameEntity frame : mc.world.getEntitiesByClass(ItemFrameEntity.class, searchBox, entity -> true)) {
            boolean isGlow = frame instanceof GlowItemFrameEntity;

            if (isGlow && !scanGlowItemFrames.get()) continue;
            if (!isGlow && !scanItemFrames.get()) continue;

            ItemStack heldStack = frame.getHeldItemStack();
            if (!heldStack.isEmpty()) {
                boolean isShulker = heldStack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
                boolean isCustom = customItems.get().contains(heldStack.getItem());

                if (isShulker || isCustom) {
                    Vec3d pos = frame.getPos();

                    if (isGlow) {
                        glowItemFrameEntities.put(pos, (GlowItemFrameEntity) frame);
                    } else {
                        itemFrameEntities.put(pos, frame);
                    }

                    // Notify if this is a new discovery
                    if (!notifiedItemFrames.contains(pos)) {
                        notifiedItemFrames.add(pos);
                        if (notify.get()) {
                            if (isShulker) {
                                info("Shulker found, Beam has been lit.");
                            } else {
                                info("§aItem found, Look for the beam!");
                            }
                        }
                        if (playSound.get()) {
                            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                        }
                    }
                }
            }
        }
        
        // Clean up notifications for frames that are no longer present
        notifiedItemFrames.removeIf(pos -> 
            !itemFrameEntities.containsKey(pos) && !glowItemFrameEntities.containsKey(pos)
        );
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        HitResult hitResult = mc.crosshairTarget;
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hitResult;
            lastOpenedContainer = blockHit.getBlockPos();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.currentScreen instanceof HandledScreen<?> screen && lastOpenedContainer != null) {
            if (containers.containsKey(lastOpenedContainer) || checkedContainers.contains(lastOpenedContainer)) {
                checkScreenInventoryForShulkers(screen);
                checkedContainers.add(lastOpenedContainer);
            }
        }
    }

    private void checkScreenInventoryForShulkers(HandledScreen<?> screen) {
        if (lastOpenedContainer == null) return;
        
        ScreenHandler handler = screen.getScreenHandler();
        int shulkerCount = 0;
        boolean previouslyHadShulker = shulkerContainers.contains(lastOpenedContainer);
        
        // Player inventory is typically the last 36 slots (hotbar + main)
        int playerInventoryStart = handler.slots.size() - 36;
        
        for (int i = 0; i < handler.slots.size(); i++) {
            if (i >= playerInventoryStart) break;
            Slot slot = handler.slots.get(i);

            ItemStack stack = slot.getStack();
            if (!stack.isEmpty()) {
                boolean isShulker = stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
                if (isShulker || customItems.get().contains(stack.getItem())) {
                    shulkerCount++;
                }
            }
        }
        
        boolean hasShulker = shulkerCount > 0;
        BlockPos adjacentChest = findAdjacentChest(lastOpenedContainer, false);
        
        if (hasShulker) {
            if (!previouslyHadShulker) {
                shulkerContainers.add(lastOpenedContainer);
                shulkerCounts.put(lastOpenedContainer, shulkerCount);
                if (adjacentChest != null) {
                    shulkerContainers.add(adjacentChest);
                    shulkerCounts.put(adjacentChest, shulkerCount);
                    checkedContainers.add(adjacentChest);
                }

                if (playSound.get()) {
                    mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                }

                if (notify.get()) info("§a" + shulkerCount + (shulkerCount == 1 ? " Item" : " Items") + " found!");
            } else {
                shulkerCounts.put(lastOpenedContainer, shulkerCount);
                if (adjacentChest != null) {
                    shulkerCounts.put(adjacentChest, shulkerCount);
                }
            }
        } else {
            checkedContainers.add(lastOpenedContainer);
            containers.remove(lastOpenedContainer);
            shulkerContainers.remove(lastOpenedContainer);
            shulkerCounts.remove(lastOpenedContainer);

            if (adjacentChest != null) {
                checkedContainers.add(adjacentChest);
                containers.remove(adjacentChest);
                shulkerContainers.remove(adjacentChest);
                shulkerCounts.remove(adjacentChest);
            }

            // Only notify if the container previously had shulkers
            if (notify.get() && previouslyHadShulker) {
                info("§70 items found, removing highlight.");
            }
        }
    }
    
    private BlockPos findAdjacentChest(BlockPos pos, boolean checkContainers) {
        if (mc.world == null) return null;
        
        BlockState state = mc.world.getBlockState(pos);
        Block block = state.getBlock();
        
        if (!(block instanceof ChestBlock)) return null;
        
        try {
            ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
            if (chestType == ChestType.SINGLE) return null; 
            
            Direction facing = state.get(ChestBlock.FACING);
            
            Direction neighborDirection;
            if (chestType == ChestType.LEFT) {
                neighborDirection = facing.rotateYClockwise();
            } else {
                neighborDirection = facing.rotateYCounterclockwise();
            }
            
            BlockPos neighborPos = pos.offset(neighborDirection);
            
            BlockState neighborState = mc.world.getBlockState(neighborPos);
            if (neighborState.getBlock() == block) {
                try {
                    ChestType neighborChestType = neighborState.get(ChestBlock.CHEST_TYPE);
                    Direction neighborFacing = neighborState.get(ChestBlock.FACING);
                    
                    if (neighborFacing == facing && neighborChestType != ChestType.SINGLE && neighborChestType != chestType) {
                        if (!checkContainers || containers.containsKey(neighborPos)) {
                            return neighborPos;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        
        return null;
    }

    private void removeContainersOfType(StorageType type) {
        containers.entrySet().removeIf(entry -> {
            if (entry.getValue() == type) {
                BlockPos pos = entry.getKey();
                checkedContainers.remove(pos);
                shulkerContainers.remove(pos);
                shulkerCounts.remove(pos);
                if (type == StorageType.CHEST_MINECART) {
                    stackedMinecartCounts.remove(pos);
                }
                return true;
            }
            return false;
        });
    }

    private void cleanupDistantContainers() {
        if (mc.player == null) return;
        
        BlockPos playerPos = mc.player.getBlockPos();
        int cleanupRange = (int)(range.get() * 1.5);
        int cleanupRangeSquared = cleanupRange * cleanupRange;
        
        containers.entrySet().removeIf(entry -> {
            boolean tooFar = entry.getKey().getSquaredDistance(playerPos) > cleanupRangeSquared;
            if (tooFar) {
                checkedContainers.remove(entry.getKey());
                shulkerContainers.remove(entry.getKey());
                shulkerCounts.remove(entry.getKey());
            }
            return tooFar;
        });
    }

    private void renderItemFrames(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        // Use the beamWidth setting (in hundredths) for adjustable beam thickness
        double beamSize = beamWidth.get() / 100.0;

        // Render regular item frames
        for (Map.Entry<Vec3d, ItemFrameEntity> entry : itemFrameEntities.entrySet()) {
            ItemFrameEntity frame = entry.getValue();
            if (frame == null || frame.isRemoved()) continue;

            Box box = frame.getBoundingBox();
            SettingColor color = itemFrameColor.get();
            
            // Highlight the item frame
            event.renderer.box(box, color, color, ShapeMode.Both, 0);
            
            // Draw beam if enabled - using a box for better visibility
            if (showBeam.get()) {
                Vec3d pos = frame.getPos();
                Box beamBox = new Box(
                    pos.x - beamSize, pos.y, pos.z - beamSize,
                    pos.x + beamSize, pos.y + 256, pos.z + beamSize
                );
                event.renderer.box(beamBox, color, color, ShapeMode.Both, 0);
            }
        }

        // Render glow item frames
        for (Map.Entry<Vec3d, GlowItemFrameEntity> entry : glowItemFrameEntities.entrySet()) {
            GlowItemFrameEntity frame = entry.getValue();
            if (frame == null || frame.isRemoved()) continue;

            Box box = frame.getBoundingBox();
            SettingColor color = glowItemFrameColor.get();
            
            // Highlight the item frame
            event.renderer.box(box, color, color, ShapeMode.Both, 0);
            
            // Draw beam if enabled - using a box for better visibility
            if (showBeam.get()) {
                Vec3d pos = frame.getPos();
                Box beamBox = new Box(
                    pos.x - beamSize, pos.y, pos.z - beamSize,
                    pos.x + beamSize, pos.y + 256, pos.z + beamSize
                );
                event.renderer.box(beamBox, color, color, ShapeMode.Both, 0);
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        ShapeMode mode = shapeMode.get();
        Set<BlockPos> toRemove = new HashSet<>();
        Set<BlockPos> renderedDoubleChests = new HashSet<>();
        
        // Render item frames with shulker boxes
        renderItemFrames(event);
        
        if (containers.isEmpty()) return;
        
        for (Map.Entry<BlockPos, StorageType> entry : containers.entrySet()) {
            BlockPos pos = entry.getKey();
            StorageType type = entry.getValue();
            
            if (renderedDoubleChests.contains(pos)) continue;
            
            Box renderBox;
            
            if (type == StorageType.CHEST_MINECART) {
                List<ChestMinecartEntity> minecarts = mc.world.getEntitiesByClass(ChestMinecartEntity.class, new Box(pos), entity -> true);
                
                if (!minecarts.isEmpty()) {
                    renderBox = minecarts.get(0).getBoundingBox();
                } else {
                    toRemove.add(pos);
                    continue;
                }
            }
            else {
                Block currentBlock = mc.world.getBlockState(pos).getBlock();
                boolean blockStillExists = validateBlockType(currentBlock, type);
                
                if (!blockStillExists) {
                    toRemove.add(pos);
                    continue;
                }
                
                BlockPos adjacentPos = findAdjacentChest(pos, true);
                if (adjacentPos != null) {
                    renderBox = createPaddedDoubleChestBox(pos, adjacentPos);
                    renderedDoubleChests.add(adjacentPos);
                } else {
                    renderBox = createPaddedBox(pos);
                }
            }

            SettingColor color;
            
            if (shulkerContainers.contains(pos)) {
                color = shulkerFoundColor.get();
                event.renderer.box(renderBox, color, color, ShapeMode.Both, 0);
            } else {
                boolean isStacked = type == StorageType.CHEST_MINECART && highlightStacked.get() && 
                                   stackedMinecartCounts.getOrDefault(pos, 0) >= 2;
                
                if (isStacked) {
                    color = stackedMinecartColor.get();
                    event.renderer.box(renderBox, color, color, ShapeMode.Both, 0);
                } else {
                    color = getColor(entry.getValue());
                    if (color != null) {
                        event.renderer.box(renderBox, color, color, mode, 0);
                    }
                }
            }

        }

        if (!toRemove.isEmpty()) {
            for (BlockPos removePos : toRemove) {
                containers.remove(removePos);
                checkedContainers.remove(removePos);
                shulkerContainers.remove(removePos);
                shulkerCounts.remove(removePos);
            }
        }
    }
    
    private Box createPaddedBox(BlockPos pos) {
        double padding = 0.0625; 
        
        return new Box(
            pos.getX() + padding,
            pos.getY() + padding,
            pos.getZ() + padding,
            pos.getX() + 1.0 - padding,
            pos.getY() + 1.0 - padding,
            pos.getZ() + 1.0 - padding
        );
    }
    
    private Box createPaddedDoubleChestBox(BlockPos pos1, BlockPos pos2) {
        double minX = Math.min(pos1.getX(), pos2.getX());
        double minY = Math.min(pos1.getY(), pos2.getY());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        double maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        double maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;
        
        double padding = 0.0625; 
        
        return new Box(
            minX + padding,
            minY + padding,
            minZ + padding,
            maxX - padding,
            maxY - padding,
            maxZ - padding
        );
    }
    
    private boolean validateBlockType(Block block, StorageType type) {
        return switch (type) {
            case CHEST -> block == Blocks.CHEST;
            case TRAPPED_CHEST -> block == Blocks.TRAPPED_CHEST;
            case BARREL -> block == Blocks.BARREL;
            case FURNACE -> block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER;
            case DISPENSER -> block == Blocks.DISPENSER;
            case DROPPER -> block == Blocks.DROPPER;
            case HOPPER -> block == Blocks.HOPPER;
            case BREWING_STAND -> block == Blocks.BREWING_STAND;
            case CRAFTER -> block == Blocks.CRAFTER;
            case DECORATED_POT -> block == Blocks.DECORATED_POT;
            case CHEST_MINECART -> true;
        };
    }

    private SettingColor getColor(StorageType type) {
        return switch (type) {
            case CHEST -> chestColor.get();
            case TRAPPED_CHEST -> trappedChestColor.get();
            case BARREL -> barrelColor.get();
            case CHEST_MINECART -> chestMinecartColor.get();
            case FURNACE -> furnaceColor.get();
            case DISPENSER -> dispenserColor.get();
            case DROPPER -> dropperColor.get();
            case HOPPER -> hopperColor.get();
            case BREWING_STAND -> brewingStandColor.get();
            case CRAFTER -> crafterColor.get();
            case DECORATED_POT -> decoratedPotColor.get();
        };
    }

    public int getTotalContainers() {
        return containers.size();
    }

    private String getDimensionName(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_end" -> "End";
            default -> dimensionId;
        };
    }

    private enum StorageType {
        CHEST,
        TRAPPED_CHEST,
        BARREL,
        CHEST_MINECART,
        FURNACE,
        DISPENSER,
        DROPPER,
        HOPPER,
        BREWING_STAND,
        CRAFTER,
        DECORATED_POT,
    }
}