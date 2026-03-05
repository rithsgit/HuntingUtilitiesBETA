package com.example.addon.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.decoration.GlowItemFrameEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

public class LootLens extends Module {

    // ─────────────────────────── State ───────────────────────────

    private final Map<BlockPos, StorageType> containers                = new HashMap<>();
    private final Set<BlockPos>              inventoryCheckedContainers = new HashSet<>();
    private final Set<BlockPos>              scannedByScanner           = new HashSet<>();
    private final Set<BlockPos>              shulkerContainers          = new HashSet<>();
    private final Map<BlockPos, Integer>     shulkerCounts              = new HashMap<>();
    private final Map<BlockPos, Integer>     stackedMinecartCounts      = new HashMap<>();
    private final Map<Vec3d, ItemFrameEntity>     itemFrameEntities     = new HashMap<>();
    private final Map<Vec3d, GlowItemFrameEntity> glowItemFrameEntities = new HashMap<>();
    private final Set<Vec3d>                 notifiedItemFrames         = new HashSet<>();

    private BlockPos lastOpenedContainer    = null;
    private boolean  screenInventoryChecked = false;

    private String lastDimension = "";
    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;
    private int dimensionChangeCooldown = 0;

    /**
     * FIX: cleanupDistantContainers was running every tick. Now throttled to
     * every CLEANUP_INTERVAL ticks to reduce per-tick overhead.
     */
    private static final int CLEANUP_INTERVAL = 40;
    private int cleanupTimer = 0;

    // ─────────────────────────── Setting Groups ───────────────────────────

    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgStorage    = settings.createGroup("Storage");
    private final SettingGroup sgUtility    = settings.createGroup("Utility");
    private final SettingGroup sgDecorative = settings.createGroup("Decorative");

    // ── General ──
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

    private final Setting<Boolean> notification = sgGeneral.add(new BoolSetting.Builder()
        .name("notification")
        .description("Send chat messages and play sound when shulkers are found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> customItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("custom-items")
        .description("Additional items to highlight in containers.")
        .defaultValue(List.of(Items.ENCHANTED_GOLDEN_APPLE, Items.ELYTRA))
        .build()
    );

    // ── Storage ──
    private final Setting<Boolean> scanChests = sgStorage.add(new BoolSetting.Builder()
        .name("chests")
        .description("Detect chests.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.CHEST); })
        .build()
    );
    private final Setting<SettingColor> chestColor = sgStorage.add(new ColorSetting.Builder()
        .name("chest-color")
        .defaultValue(new SettingColor(255, 215, 0, 38))
        .visible(scanChests::get)
        .build()
    );

    private final Setting<Boolean> scanTrappedChests = sgStorage.add(new BoolSetting.Builder()
        .name("trapped-chests")
        .description("Detect trapped chests.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.TRAPPED_CHEST); })
        .build()
    );
    private final Setting<SettingColor> trappedChestColor = sgStorage.add(new ColorSetting.Builder()
        .name("trapped-chest-color")
        .defaultValue(new SettingColor(255, 69, 0, 38))
        .visible(scanTrappedChests::get)
        .build()
    );

    private final Setting<Boolean> scanBarrels = sgStorage.add(new BoolSetting.Builder()
        .name("barrels")
        .description("Detect barrels.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.BARREL); })
        .build()
    );
    private final Setting<SettingColor> barrelColor = sgStorage.add(new ColorSetting.Builder()
        .name("barrel-color")
        .defaultValue(new SettingColor(139, 69, 19, 38))
        .visible(scanBarrels::get)
        .build()
    );

    // FIX: ShulkerBoxBlock placed in the world is now a tracked StorageType.
    private final Setting<Boolean> scanShulkerBoxes = sgStorage.add(new BoolSetting.Builder()
        .name("shulker-boxes")
        .description("Detect shulker boxes placed in the world.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.SHULKER_BOX); })
        .build()
    );
    private final Setting<SettingColor> shulkerBoxColor = sgStorage.add(new ColorSetting.Builder()
        .name("shulker-box-color")
        .defaultValue(new SettingColor(160, 32, 240, 80))
        .visible(scanShulkerBoxes::get)
        .build()
    );

    private final Setting<Boolean> scanChestMinecarts = sgStorage.add(new BoolSetting.Builder()
        .name("chest-minecarts")
        .description("Detect chest minecarts.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.CHEST_MINECART); })
        .build()
    );
    private final Setting<SettingColor> chestMinecartColor = sgStorage.add(new ColorSetting.Builder()
        .name("chest-minecart-color")
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
        .defaultValue(new SettingColor(255, 0, 255, 180))
        .visible(() -> scanChestMinecarts.get() && highlightStacked.get())
        .build()
    );

    // Moved from sgGeneral to fix forward reference
    private final Setting<Integer> beamWidth = sgGeneral.add(new IntSetting.Builder()
        .name("beam-width")
        .description("Beam width (in hundredths of a block).")
        .defaultValue(15)
        .min(5)
        .max(50)
        .sliderMin(5)
        .sliderMax(50)
        .visible(() -> showBeam.get() || (scanChestMinecarts.get() && highlightStacked.get()))
        .build()
    );

    // ── Utility ──
    private final Setting<Boolean> scanFurnaces = sgUtility.add(new BoolSetting.Builder()
        .name("furnaces")
        .description("Detect furnaces, blast furnaces, and smokers.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.FURNACE); })
        .build()
    );
    private final Setting<SettingColor> furnaceColor = sgUtility.add(new ColorSetting.Builder()
        .name("furnace-color")
        .defaultValue(new SettingColor(192, 192, 192, 38))
        .visible(scanFurnaces::get)
        .build()
    );

    private final Setting<Boolean> scanHoppers = sgUtility.add(new BoolSetting.Builder()
        .name("hoppers")
        .description("Detect hoppers.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.HOPPER); })
        .build()
    );
    private final Setting<SettingColor> hopperColor = sgUtility.add(new ColorSetting.Builder()
        .name("hopper-color")
        .defaultValue(new SettingColor(64, 64, 64, 38))
        .visible(scanHoppers::get)
        .build()
    );

    private final Setting<Boolean> scanDispensers = sgUtility.add(new BoolSetting.Builder()
        .name("dispensers")
        .description("Detect dispensers.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.DISPENSER); })
        .build()
    );
    private final Setting<SettingColor> dispenserColor = sgUtility.add(new ColorSetting.Builder()
        .name("dispenser-color")
        .defaultValue(new SettingColor(169, 169, 169, 38))
        .visible(scanDispensers::get)
        .build()
    );

    private final Setting<Boolean> scanDroppers = sgUtility.add(new BoolSetting.Builder()
        .name("droppers")
        .description("Detect droppers.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.DROPPER); })
        .build()
    );
    private final Setting<SettingColor> dropperColor = sgUtility.add(new ColorSetting.Builder()
        .name("dropper-color")
        .defaultValue(new SettingColor(128, 128, 128, 38))
        .visible(scanDroppers::get)
        .build()
    );

    // ── Decorative ──
    private final Setting<Boolean> scanBrewingStands = sgDecorative.add(new BoolSetting.Builder()
        .name("brewing-stands")
        .description("Detect brewing stands.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.BREWING_STAND); })
        .build()
    );
    private final Setting<SettingColor> brewingStandColor = sgDecorative.add(new ColorSetting.Builder()
        .name("brewing-stand-color")
        .defaultValue(new SettingColor(138, 43, 226, 38))
        .visible(scanBrewingStands::get)
        .build()
    );

    private final Setting<Boolean> scanCrafters = sgDecorative.add(new BoolSetting.Builder()
        .name("crafters")
        .description("Detect crafters.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.CRAFTER); })
        .build()
    );
    private final Setting<SettingColor> crafterColor = sgDecorative.add(new ColorSetting.Builder()
        .name("crafter-color")
        .defaultValue(new SettingColor(160, 82, 45, 38))
        .visible(scanCrafters::get)
        .build()
    );

    private final Setting<Boolean> scanDecoratedPots = sgDecorative.add(new BoolSetting.Builder()
        .name("decorated-pots")
        .description("Detect decorated pots.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.DECORATED_POT); })
        .build()
    );
    private final Setting<SettingColor> decoratedPotColor = sgDecorative.add(new ColorSetting.Builder()
        .name("decorated-pot-color")
        .defaultValue(new SettingColor(205, 133, 63, 38))
        .visible(scanDecoratedPots::get)
        .build()
    );

    private final Setting<Boolean> scanItemFrames = sgDecorative.add(new BoolSetting.Builder()
        .name("item-frames")
        .description("Detect item frames and glow item frames holding shulker boxes or custom items.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> itemFrameColor = sgDecorative.add(new ColorSetting.Builder()
        .name("item-frame-color")
        .defaultValue(new SettingColor(255, 100, 255, 150))
        .visible(scanItemFrames::get)
        .build()
    );

    // ─────────────────────────── Constructor ───────────────────────────

    public LootLens() {
        super(HuntingUtilities.CATEGORY, "loot-lens", "Highlights storage containers that hold shulkers.");
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    @Override
    public void onActivate() {
        clearAllState();
        if (mc.player != null && mc.world != null) {
            info("§bLoot Lens activated");
            if (mc.world.getRegistryKey() != null) {
                lastDimension = mc.world.getRegistryKey().getValue().toString();
            }
        }
    }

    @Override
    public void onDeactivate() {
        clearAllState();
    }

    private void clearAllState() {
        containers.clear();
        inventoryCheckedContainers.clear();
        scannedByScanner.clear();
        shulkerContainers.clear();
        shulkerCounts.clear();
        stackedMinecartCounts.clear();
        itemFrameEntities.clear();
        glowItemFrameEntities.clear();
        notifiedItemFrames.clear();
        lastOpenedContainer    = null;
        screenInventoryChecked = false;
        cleanupTimer           = 0;
    }

    // ─────────────────────────── Tick Logic ───────────────────────────

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
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
                dimensionChangeCooldown = DIMENSION_CHANGE_COOLDOWN_TICKS;
                lastDimension = currDim;
                clearAllState();
                return;
            }
        } catch (Exception ignored) { return; }

        // FIX: Cleanup now throttled — only runs every CLEANUP_INTERVAL ticks.
        if (++cleanupTimer >= CLEANUP_INTERVAL) {
            cleanupTimer = 0;
            cleanupDistantContainers();
        }

        scanChestMinecarts();
        scanItemFrames();

        BlockPos currentPos = mc.player.getBlockPos();
        scanBlockEntities(currentPos.getX() >> 4, currentPos.getZ() >> 4);
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (mc.currentScreen instanceof HandledScreen<?>
                && !(mc.currentScreen instanceof InventoryScreen)
                && lastOpenedContainer != null
                && !screenInventoryChecked) {

            HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
            if (containers.containsKey(lastOpenedContainer) || shulkerContainers.contains(lastOpenedContainer)) {
                checkScreenInventoryForShulkers(screen);
                screenInventoryChecked = true;
            }
        }

        if (mc.currentScreen == null && lastOpenedContainer != null) {
            lastOpenedContainer    = null;
            screenInventoryChecked = false;
        }
    }

    // ─────────────────────────── Screen Handler ───────────────────────────

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null || mc.world == null) return;

        screenInventoryChecked = false;

        if (event.screen instanceof InventoryScreen) return;

        HitResult hitResult = mc.crosshairTarget;
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
            lastOpenedContainer = ((BlockHitResult) hitResult).getBlockPos();
        }
    }

    // ─────────────────────────── Mixin-facing API ───────────────────────────

    /**
     * Called by LootLensInteractMixin — records the block the player clicked
     * before the server responds with an OpenScreen packet.
     */
    public void setLastInteractedPos(BlockPos pos) {
        lastOpenedContainer    = pos;
        screenInventoryChecked = false;
    }

    /**
     * Called by LootLensMixin on OpenScreenS2CPacket arrival — confirms that
     * a screen open followed the interaction and resets the per-screen flag.
     */
    public void onOpenScreenPacket() {
        screenInventoryChecked = false;
    }

    // ─────────────────────────── Container Logic ───────────────────────────

    private void checkScreenInventoryForShulkers(HandledScreen<?> screen) {
        if (lastOpenedContainer == null) return;

        ScreenHandler handler         = screen.getScreenHandler();
        int playerInventoryStart      = handler.slots.size() - 36;
        int shulkerCount              = 0;
        boolean previouslyHadShulker  = shulkerContainers.contains(lastOpenedContainer);

        for (int i = 0; i < playerInventoryStart; i++) {
            Slot      slot  = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            boolean isShulker = stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
            if (isShulker || customItems.get().contains(stack.getItem())) {
                shulkerCount++;
            }
        }

        BlockPos adjacentChest = findAdjacentChest(lastOpenedContainer, false);

        inventoryCheckedContainers.add(lastOpenedContainer);
        if (adjacentChest != null) inventoryCheckedContainers.add(adjacentChest);

        if (shulkerCount > 0) {
            shulkerContainers.add(lastOpenedContainer);
            shulkerCounts.put(lastOpenedContainer, shulkerCount);
            if (adjacentChest != null) {
                shulkerContainers.add(adjacentChest);
                shulkerCounts.put(adjacentChest, shulkerCount);
            }

            if (!previouslyHadShulker && notification.get()) {
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                info("§a%d %s found!", shulkerCount, shulkerCount == 1 ? "item" : "items");
            }
        } else {
            containers.remove(lastOpenedContainer);
            shulkerContainers.remove(lastOpenedContainer);
            shulkerCounts.remove(lastOpenedContainer);
            if (adjacentChest != null) {
                containers.remove(adjacentChest);
                shulkerContainers.remove(adjacentChest);
                shulkerCounts.remove(adjacentChest);
            }

            if (previouslyHadShulker && notification.get()) {
                info("§70 items found, removing highlight.");
            }
        }
    }

    // ─────────────────────────── Scanning ───────────────────────────

    private void scanBlockEntities(int centerChunkX, int centerChunkZ) {
        int rangeBlocks  = range.get();
        int chunkRange   = (rangeBlocks >> 4) + 1;
        int chunkRangeSq = chunkRange * chunkRange;
        int maxDistSq    = rangeBlocks * rangeBlocks;
        BlockPos playerPos = mc.player.getBlockPos();

        for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
            for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                int dx = cx - centerChunkX;
                int dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > chunkRangeSq) continue;

                WorldChunk chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getPos();
                    if (pos.getSquaredDistance(playerPos) > maxDistSq) continue;
                    if (scannedByScanner.contains(pos) && !shulkerContainers.contains(pos)) continue;

                    Block block = mc.world.getBlockState(pos).getBlock();
                    StorageType type = classifyBlock(block);
                    if (type != null) {
                        containers.put(pos, type);
                        scannedByScanner.add(pos);
                    }
                }
            }
        }
    }

    /**
     * FIX: ShulkerBoxBlock placed in the world is now classified as SHULKER_BOX.
     */
    private StorageType classifyBlock(Block block) {
        if (block == Blocks.CHEST             && scanChests.get())        return StorageType.CHEST;
        if (block == Blocks.TRAPPED_CHEST     && scanTrappedChests.get()) return StorageType.TRAPPED_CHEST;
        if (block == Blocks.BARREL            && scanBarrels.get())       return StorageType.BARREL;
        if (block == Blocks.DISPENSER         && scanDispensers.get())    return StorageType.DISPENSER;
        if (block == Blocks.DROPPER           && scanDroppers.get())      return StorageType.DROPPER;
        if (block == Blocks.HOPPER            && scanHoppers.get())       return StorageType.HOPPER;
        if (block == Blocks.BREWING_STAND     && scanBrewingStands.get()) return StorageType.BREWING_STAND;
        if (block == Blocks.CRAFTER           && scanCrafters.get())      return StorageType.CRAFTER;
        if (block == Blocks.DECORATED_POT     && scanDecoratedPots.get()) return StorageType.DECORATED_POT;
        if (block instanceof ShulkerBoxBlock  && scanShulkerBoxes.get())  return StorageType.SHULKER_BOX;
        if (scanFurnaces.get() && (block == Blocks.FURNACE
                || block == Blocks.BLAST_FURNACE
                || block == Blocks.SMOKER))                                return StorageType.FURNACE;
        return null;
    }

    private void scanChestMinecarts() {
        if (!scanChestMinecarts.get()) return;

        BlockPos playerPos = mc.player.getBlockPos();
        int scanRange = range.get();
        Box searchBox = new Box(
            playerPos.getX() - scanRange, playerPos.getY() - scanRange, playerPos.getZ() - scanRange,
            playerPos.getX() + scanRange, playerPos.getY() + scanRange, playerPos.getZ() + scanRange
        );

        Map<BlockPos, Integer> minecartCountMap = new HashMap<>();
        for (ChestMinecartEntity minecart : mc.world.getEntitiesByClass(
                ChestMinecartEntity.class, searchBox, entity -> true)) {
            BlockPos pos = minecart.getBlockPos();
            minecartCountMap.put(pos, minecartCountMap.getOrDefault(pos, 0) + 1);
            containers.putIfAbsent(pos, StorageType.CHEST_MINECART);
        }

        stackedMinecartCounts.clear();
        stackedMinecartCounts.putAll(minecartCountMap);

        // FIX: Ghost minecart positions removed when no longer present.
        containers.entrySet().removeIf(entry -> {
            if (entry.getValue() != StorageType.CHEST_MINECART) return false;
            BlockPos pos = entry.getKey();
            if (minecartCountMap.containsKey(pos)) return false;
            inventoryCheckedContainers.remove(pos);
            scannedByScanner.remove(pos);
            shulkerContainers.remove(pos);
            shulkerCounts.remove(pos);
            stackedMinecartCounts.remove(pos);
            return true;
        });
    }

    /**
     * FIX: Item frame maps are now diffed rather than cleared and rebuilt from
     * scratch every tick. Only entries that are no longer in the world are removed.
     */
    private void scanItemFrames() {
        if (!scanItemFrames.get()) return;

        BlockPos playerPos = mc.player.getBlockPos();
        int scanRange = range.get();
        Box searchBox = new Box(
            playerPos.getX() - scanRange, playerPos.getY() - scanRange, playerPos.getZ() - scanRange,
            playerPos.getX() + scanRange, playerPos.getY() + scanRange, playerPos.getZ() + scanRange
        );

        Set<Vec3d> currentFramePositions = new HashSet<>();

        for (ItemFrameEntity frame : mc.world.getEntitiesByClass(
                ItemFrameEntity.class, searchBox, entity -> true)) {
            ItemStack heldStack = frame.getHeldItemStack();
            if (heldStack.isEmpty()) continue;

            boolean isShulker = heldStack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof ShulkerBoxBlock;
            boolean isCustom  = customItems.get().contains(heldStack.getItem());
            if (!isShulker && !isCustom) continue;

            Vec3d pos = frame.getPos();
            currentFramePositions.add(pos);

            if (frame instanceof GlowItemFrameEntity glow) {
                glowItemFrameEntities.put(pos, glow);
            } else {
                itemFrameEntities.put(pos, frame);
            }

            if (notifiedItemFrames.add(pos) && notification.get()) {
                if (isShulker) info("Shulker found in item frame, beam lit.");
                else           info("§aTracked item found in item frame!");
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
        }

        // Remove stale entries that are no longer in range or no longer valid.
        itemFrameEntities.entrySet().removeIf(e -> !currentFramePositions.contains(e.getKey()));
        glowItemFrameEntities.entrySet().removeIf(e -> !currentFramePositions.contains(e.getKey()));
        notifiedItemFrames.removeIf(pos ->
            !itemFrameEntities.containsKey(pos) && !glowItemFrameEntities.containsKey(pos));
    }

    // ─────────────────────────── Double Chest ───────────────────────────

    private BlockPos findAdjacentChest(BlockPos pos, boolean checkContainers) {
        if (mc.world == null) return null;

        BlockState state = mc.world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return null;

        try {
            ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
            if (chestType == ChestType.SINGLE) return null;

            Direction facing      = state.get(ChestBlock.FACING);
            Direction neighborDir = chestType == ChestType.LEFT
                ? facing.rotateYClockwise()
                : facing.rotateYCounterclockwise();

            BlockPos   neighborPos   = pos.offset(neighborDir);
            BlockState neighborState = mc.world.getBlockState(neighborPos);

            if (!(neighborState.getBlock() instanceof ChestBlock)) return null;

            ChestType neighborType   = neighborState.get(ChestBlock.CHEST_TYPE);
            Direction neighborFacing = neighborState.get(ChestBlock.FACING);

            if (neighborFacing != facing)          return null;
            if (neighborType == ChestType.SINGLE)  return null;
            if (neighborType == chestType)         return null;

            if (checkContainers && !containers.containsKey(neighborPos)) return null;

            return neighborPos;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ─────────────────────────── Cleanup ───────────────────────────

    private void removeContainersOfType(StorageType type) {
        containers.entrySet().removeIf(entry -> {
            if (entry.getValue() != type) return false;
            BlockPos pos = entry.getKey();
            inventoryCheckedContainers.remove(pos);
            scannedByScanner.remove(pos);
            shulkerContainers.remove(pos);
            shulkerCounts.remove(pos);
            if (type == StorageType.CHEST_MINECART) stackedMinecartCounts.remove(pos);
            return true;
        });
    }

    private void cleanupDistantContainers() {
        if (mc.player == null) return;

        BlockPos playerPos     = mc.player.getBlockPos();
        int cleanupRange       = (int) (range.get() * 1.5);
        int cleanupRangeSq     = cleanupRange * cleanupRange;

        containers.entrySet().removeIf(entry -> {
            if (entry.getKey().getSquaredDistance(playerPos) <= cleanupRangeSq) return false;
            BlockPos pos = entry.getKey();
            inventoryCheckedContainers.remove(pos);
            scannedByScanner.remove(pos);
            shulkerContainers.remove(pos);
            shulkerCounts.remove(pos);
            return true;
        });
    }

    // ─────────────────────────── Rendering ───────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        ShapeMode    mode               = shapeMode.get();
        Set<BlockPos> toRemove          = new HashSet<>();
        Set<BlockPos> renderedDoubleChests = new HashSet<>();

        renderItemFrames(event);
        if (containers.isEmpty()) return;

        for (Map.Entry<BlockPos, StorageType> entry : containers.entrySet()) {
            BlockPos    pos  = entry.getKey();
            StorageType type = entry.getValue();

            if (renderedDoubleChests.contains(pos)) continue;

            Box renderBox;

            if (type == StorageType.CHEST_MINECART) {
                List<ChestMinecartEntity> minecarts = mc.world.getEntitiesByClass(
                    ChestMinecartEntity.class, new Box(pos), entity -> true);
                if (minecarts.isEmpty()) {
                    toRemove.add(pos);
                    continue;
                }
                renderBox = getMinecartChestBox(minecarts.get(0));
            } else {
                Block currentBlock = mc.world.getBlockState(pos).getBlock();
                if (!validateBlockType(currentBlock, type)) {
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
                if (showBeam.get()) renderBeam(event, renderBox, color);
            } else {
                boolean isStacked = type == StorageType.CHEST_MINECART
                    && highlightStacked.get()
                    && stackedMinecartCounts.getOrDefault(pos, 0) >= 2;

                color = isStacked ? stackedMinecartColor.get() : getColor(type);
                if (color != null) {
                    event.renderer.box(renderBox, color, color, mode, 0);
                    // Automatically render a beam for stacked minecarts to improve visibility
                    if (isStacked) renderBeam(event, renderBox, color);
                }
            }
        }

        if (!toRemove.isEmpty()) {
            for (BlockPos removePos : toRemove) {
                containers.remove(removePos);
                inventoryCheckedContainers.remove(removePos);
                scannedByScanner.remove(removePos);
                shulkerContainers.remove(removePos);
                shulkerCounts.remove(removePos);
            }
        }
    }

    private void renderBeam(Render3DEvent event, Box anchorBox, SettingColor color) {
        double beamSize  = beamWidth.get() / 100.0;
        double centerX   = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double centerZ   = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int    worldBot  = mc.world.getBottomY();
        int    worldTop  = worldBot + mc.world.getHeight();

        Box beamBox = new Box(
            centerX - beamSize, worldBot, centerZ - beamSize,
            centerX + beamSize, worldTop, centerZ + beamSize
        );
        event.renderer.box(beamBox, color, color, ShapeMode.Both, 0);
    }

    private void renderItemFrames(Render3DEvent event) {
        SettingColor color    = itemFrameColor.get();
        double       beamSize = beamWidth.get() / 100.0;
        int worldBot = mc.world.getBottomY();
        int worldTop = worldBot + mc.world.getHeight();

        for (ItemFrameEntity frame : itemFrameEntities.values()) {
            if (frame == null || frame.isRemoved()) continue;
            event.renderer.box(frame.getBoundingBox(), color, color, ShapeMode.Both, 0);
            if (showBeam.get()) {
                Vec3d pos = frame.getPos();
                event.renderer.box(
                    new Box(pos.x - beamSize, worldBot, pos.z - beamSize,
                            pos.x + beamSize, worldTop, pos.z + beamSize),
                    color, color, ShapeMode.Both, 0);
            }
        }

        for (GlowItemFrameEntity frame : glowItemFrameEntities.values()) {
            if (frame == null || frame.isRemoved()) continue;
            event.renderer.box(frame.getBoundingBox(), color, color, ShapeMode.Both, 0);
            if (showBeam.get()) {
                Vec3d pos = frame.getPos();
                event.renderer.box(
                    new Box(pos.x - beamSize, worldBot, pos.z - beamSize,
                            pos.x + beamSize, worldTop, pos.z + beamSize),
                    color, color, ShapeMode.Both, 0);
            }
        }
    }

    // ─────────────────────────── Utilities ───────────────────────────

    private Box getMinecartChestBox(ChestMinecartEntity minecart) {
        Box entityBox = minecart.getBoundingBox();
        // A standard chest is 14/16 blocks wide/deep.
        double chestSize = 14.0 / 16.0; // 0.875
        // A minecart is 0.98 blocks wide. Center the chest box.
        double xPadding = (entityBox.getLengthX() - chestSize) / 2.0;
        double zPadding = (entityBox.getLengthZ() - chestSize) / 2.0;

        // The chest model is roughly 10/16 blocks high.
        double chestHeight = 10.0 / 16.0; // 0.625

        // The chest sits in the top portion of the minecart's bounding box.
        double minY = entityBox.maxY - chestHeight;
        double maxY = entityBox.maxY;

        return new Box(
            entityBox.minX + xPadding,
            minY,
            entityBox.minZ + zPadding,
            entityBox.maxX - xPadding,
            maxY,
            entityBox.maxZ - zPadding
        );
    }

    private Box createPaddedBox(BlockPos pos) {
        double p = 0.0625;
        return new Box(pos.getX() + p, pos.getY() + p, pos.getZ() + p,
                       pos.getX() + 1 - p, pos.getY() + 1 - p, pos.getZ() + 1 - p);
    }

    private Box createPaddedDoubleChestBox(BlockPos pos1, BlockPos pos2) {
        double p    = 0.0625;
        double minX = Math.min(pos1.getX(), pos2.getX());
        double minY = Math.min(pos1.getY(), pos2.getY());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        double maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        double maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;
        return new Box(minX + p, minY + p, minZ + p, maxX - p, maxY - p, maxZ - p);
    }

    private boolean validateBlockType(Block block, StorageType type) {
        return switch (type) {
            case CHEST          -> block == Blocks.CHEST;
            case TRAPPED_CHEST  -> block == Blocks.TRAPPED_CHEST;
            case BARREL         -> block == Blocks.BARREL;
            case SHULKER_BOX    -> block instanceof ShulkerBoxBlock;
            case FURNACE        -> block == Blocks.FURNACE
                                || block == Blocks.BLAST_FURNACE
                                || block == Blocks.SMOKER;
            case DISPENSER      -> block == Blocks.DISPENSER;
            case DROPPER        -> block == Blocks.DROPPER;
            case HOPPER         -> block == Blocks.HOPPER;
            case BREWING_STAND  -> block == Blocks.BREWING_STAND;
            case CRAFTER        -> block == Blocks.CRAFTER;
            case DECORATED_POT  -> block == Blocks.DECORATED_POT;
            case CHEST_MINECART -> true;
        };
    }

    private SettingColor getColor(StorageType type) {
        return switch (type) {
            case CHEST          -> chestColor.get();
            case TRAPPED_CHEST  -> trappedChestColor.get();
            case BARREL         -> barrelColor.get();
            case SHULKER_BOX    -> shulkerBoxColor.get();
            case CHEST_MINECART -> chestMinecartColor.get();
            case FURNACE        -> furnaceColor.get();
            case DISPENSER      -> dispenserColor.get();
            case DROPPER        -> dropperColor.get();
            case HOPPER         -> hopperColor.get();
            case BREWING_STAND  -> brewingStandColor.get();
            case CRAFTER        -> crafterColor.get();
            case DECORATED_POT  -> decoratedPotColor.get();
        };
    }

    public int getTotalContainers() { return containers.size(); }

    // ─────────────────────────── Storage Types ───────────────────────────

    private enum StorageType {
        CHEST, TRAPPED_CHEST, BARREL, SHULKER_BOX, CHEST_MINECART,
        FURNACE, DISPENSER, DROPPER, HOPPER,
        BREWING_STAND, CRAFTER, DECORATED_POT
    }
}