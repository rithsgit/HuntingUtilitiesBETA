package com.example.addon.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.addon.Tim;
import com.mojang.blaze3d.systems.RenderSystem;

import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
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
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
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
import net.minecraft.util.DyeColor;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

public class LootLens extends Module {

    // ─────────────────────────── Enums ───────────────────────────

    public enum RenderMode { GLOW, SPECTRAL, PULSE }
    public enum BeamStyle  { BOX, GUARDIAN }

    // ─────────────────────────── State ───────────────────────────

    private final Map<BlockPos, StorageType>      containers                 = new HashMap<>();
    private final Set<BlockPos>                   inventoryCheckedContainers = new HashSet<>();
    private final Set<BlockPos>                   scannedByScanner           = new HashSet<>();
    private final Set<BlockPos>                   shulkerContainers          = new HashSet<>();
    private final Map<BlockPos, Integer>          shulkerCounts              = new HashMap<>();
    private final Map<Vec3d, ItemFrameEntity>     itemFrameEntities          = new HashMap<>();
    private final Map<Vec3d, GlowItemFrameEntity> glowItemFrameEntities      = new HashMap<>();
    private final Set<Vec3d>                      notifiedItemFrames         = new HashSet<>();

    private final Set<BlockPos>                   minecartInventoryChecked   = new HashSet<>();

    // Stacked minecart tracking — tracked by Cluster UUID to survive movement without spam.
    private final Map<UUID, StackedState>         knownStackedMinecarts      = new HashMap<>();

    private final Map<BlockPos, DyeColor>         bedPositions               = new HashMap<>();

    private BlockPos lastOpenedContainer    = null;
    private boolean  screenInventoryChecked = false;

    private String lastDimension = "";
    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;
    private int dimensionChangeCooldown = 0;

    private static final int CLEANUP_INTERVAL = 40;
    private int cleanupTimer = 0;

    // ─────────────────────────── Setting Groups ───────────────────────────

    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgStorage    = settings.createGroup("Storage");
    private final SettingGroup sgUtility    = settings.createGroup("Utility");
    private final SettingGroup sgDecorative = settings.createGroup("Decorative");
    private final SettingGroup sgBeam       = settings.createGroup("Beam");

    // ── General ──

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Container detection range in blocks.")
        .defaultValue(128).min(16).max(512).sliderMin(32).sliderMax(256).build()
    );

    private final Setting<Boolean> notification = sgGeneral.add(new BoolSetting.Builder()
        .name("notification").description("Send chat messages and play sound when shulkers are found.")
        .defaultValue(true).build()
    );

    private final Setting<List<Item>> customItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("custom-items").description("Additional items to highlight in containers.")
        .defaultValue(List.of(Items.ENCHANTED_GOLDEN_APPLE, Items.ELYTRA)).build()
    );

    private final Setting<Boolean> stealDumpButtons = sgGeneral.add(new BoolSetting.Builder()
        .name("steal-dump-buttons")
        .description("Show steal and dump buttons on container screens.")
        .defaultValue(true)
        .build()
    );

    private final Setting<RenderMode> renderMode = sgGeneral.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("GLOW = layered bloom boxes. SPECTRAL = subtle fill. PULSE = fading in/out highlight.")
        .defaultValue(RenderMode.GLOW).build()
    );

    private final Setting<Integer> glowLayers = sgGeneral.add(new IntSetting.Builder()
        .name("glow-layers").description("Number of bloom layers rendered around each container.")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> renderMode.get() == RenderMode.GLOW || renderMode.get() == RenderMode.PULSE).build()
    );

    private final Setting<Double> glowSpread = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow-spread").description("How far each bloom layer expands outward (in blocks).")
        .defaultValue(0.04).min(0.01).sliderMax(0.15)
        .visible(() -> renderMode.get() == RenderMode.GLOW || renderMode.get() == RenderMode.PULSE).build()
    );

    private final Setting<Integer> glowBaseAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("glow-base-alpha").description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(60).min(10).sliderMax(150)
        .visible(() -> renderMode.get() == RenderMode.GLOW).build()
    );

    private final Setting<Integer> spectralFillAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("spectral-fill-alpha")
        .description("Fill alpha for block containers in SPECTRAL mode (0 = invisible, 40 = subtle).")
        .defaultValue(40).min(0).max(200).sliderMax(120)
        .visible(() -> renderMode.get() == RenderMode.SPECTRAL).build()
    );

    private final Setting<Boolean> spectralOutline = sgGeneral.add(new BoolSetting.Builder()
        .name("spectral-outline").description("Draw a crisp outline around block containers in SPECTRAL mode.")
        .defaultValue(true).visible(() -> renderMode.get() == RenderMode.SPECTRAL).build()
    );

    private final Setting<Double> pulseSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("pulse-speed")
        .description("Pulse cycle speed. 1.0 = one full fade in/out per second.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMax(3.0)
        .visible(() -> renderMode.get() == RenderMode.PULSE).build()
    );

    private final Setting<Integer> pulseMinAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("pulse-min-alpha")
        .description("Lowest alpha reached during the pulse (0 = invisible).")
        .defaultValue(15).min(0).max(255).sliderMax(100)
        .visible(() -> renderMode.get() == RenderMode.PULSE).build()
    );

    private final Setting<Integer> pulseMaxAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("pulse-max-alpha")
        .description("Peak alpha reached during the pulse.")
        .defaultValue(220).min(0).max(255).sliderMax(255)
        .visible(() -> renderMode.get() == RenderMode.PULSE).build()
    );

    private final Setting<Boolean> pulseBeams = sgGeneral.add(new BoolSetting.Builder()
        .name("pulse-beams")
        .description("Also pulse the beam opacity in sync with the highlights.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.PULSE).build()
    );

    // ── Beam ──

    private final Setting<BeamStyle> beamStyle = sgBeam.add(new EnumSetting.Builder<BeamStyle>()
        .name("beam-style")
        .description("BOX = simple axis-aligned box beam. GUARDIAN = spinning guardian-style beam.")
        .defaultValue(BeamStyle.GUARDIAN).build()
    );

    private final Setting<Integer> beamWidth = sgBeam.add(new IntSetting.Builder()
        .name("beam-width").description("Box beam width (in hundredths of a block).")
        .defaultValue(15).min(5).max(50).sliderMin(5).sliderMax(50)
        .visible(() -> beamStyle.get() == BeamStyle.BOX).build()
    );

    private final Setting<Boolean> mergeBeams = sgBeam.add(new BoolSetting.Builder()
        .name("merge-beams").description("Merge beams for nearby shulker containers to reduce clutter.")
        .defaultValue(true).build()
    );

    private final Setting<Double> mergeDistance = sgBeam.add(new DoubleSetting.Builder()
        .name("merge-distance").description("Distance within which beams are merged.")
        .defaultValue(2.0).min(0).sliderMax(10).visible(mergeBeams::get).build()
    );

    private final Setting<Double> guardianBeamRadius = sgBeam.add(new DoubleSetting.Builder()
        .name("guardian-radius")
        .description("Radius of the guardian beam strands from centre (blocks).")
        .defaultValue(0.08).min(0.01).max(0.6).sliderMax(0.3)
        .visible(() -> beamStyle.get() == BeamStyle.GUARDIAN).build()
    );

    private final Setting<Integer> guardianStrands = sgBeam.add(new IntSetting.Builder()
        .name("guardian-strands")
        .description("Number of spinning flat quads that make up the beam (2-8).")
        .defaultValue(4).min(2).max(8).sliderMax(8)
        .visible(() -> beamStyle.get() == BeamStyle.GUARDIAN).build()
    );

    private final Setting<Double> guardianSpinSpeed = sgBeam.add(new DoubleSetting.Builder()
        .name("guardian-spin-speed")
        .description("How fast the beam rotates. 1.0 = one full revolution every ~6 seconds.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMax(3.0)
        .visible(() -> beamStyle.get() == BeamStyle.GUARDIAN).build()
    );

    private final Setting<Integer> guardianCoreAlpha = sgBeam.add(new IntSetting.Builder()
        .name("guardian-core-alpha")
        .description("Alpha of the solid centre core of the guardian beam (0 = no core).")
        .defaultValue(90).min(0).max(255).sliderMax(200)
        .visible(() -> beamStyle.get() == BeamStyle.GUARDIAN).build()
    );

    private final Setting<Integer> guardianStrandAlpha = sgBeam.add(new IntSetting.Builder()
        .name("guardian-strand-alpha")
        .description("Alpha of the outer spinning strands.")
        .defaultValue(160).min(10).max(255).sliderMax(255)
        .visible(() -> beamStyle.get() == BeamStyle.GUARDIAN).build()
    );

    private final Setting<Boolean> guardianGlow = sgBeam.add(new BoolSetting.Builder()
        .name("guardian-glow")
        .description("Add a soft bloom halo around the guardian beam.")
        .defaultValue(true)
        .visible(() -> beamStyle.get() == BeamStyle.GUARDIAN).build()
    );

    private final Setting<Double> guardianGlowRadius = sgBeam.add(new DoubleSetting.Builder()
        .name("guardian-glow-radius")
        .description("Radius of the bloom halo around the guardian beam.")
        .defaultValue(0.18).min(0.02).max(1.0).sliderMax(0.5)
        .visible(() -> beamStyle.get() == BeamStyle.GUARDIAN && guardianGlow.get()).build()
    );

    // ── Storage ──

    private final Setting<Boolean> scanChests = sgStorage.add(new BoolSetting.Builder()
        .name("chests").description("Detect chests and trapped chests.")
        .defaultValue(true)
        .onChanged(v -> {
            if (!v) {
                removeContainersOfType(StorageType.CHEST);
                removeContainersOfType(StorageType.TRAPPED_CHEST);
            }
        }).build()
    );
    private final Setting<SettingColor> chestColor = sgStorage.add(new ColorSetting.Builder()
        .name("chest-color").defaultValue(new SettingColor(255, 215, 0, 200))
        .visible(scanChests::get).build()
    );

    private final Setting<Boolean> scanBarrels = sgStorage.add(new BoolSetting.Builder()
        .name("barrels").description("Detect barrels.").defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.BARREL); }).build()
    );
    private final Setting<SettingColor> barrelColor = sgStorage.add(new ColorSetting.Builder()
        .name("barrel-color").defaultValue(new SettingColor(139, 69, 19, 200))
        .visible(scanBarrels::get).build()
    );

    private final Setting<Boolean> scanShulkerBoxes = sgStorage.add(new BoolSetting.Builder()
        .name("shulker-boxes").description("Detect shulker boxes placed in the world.").defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.SHULKER_BOX); }).build()
    );
    private final Setting<SettingColor> shulkerBoxColor = sgStorage.add(new ColorSetting.Builder()
        .name("shulker-box-color").defaultValue(new SettingColor(160, 32, 240, 200))
        .visible(scanShulkerBoxes::get).build()
    );

    private final Setting<Boolean> scanEnderChests = sgStorage.add(new BoolSetting.Builder()
        .name("ender-chests").description("Detect ender chests.").defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.ENDER_CHEST); }).build()
    );
    private final Setting<SettingColor> enderChestColor = sgStorage.add(new ColorSetting.Builder()
        .name("ender-chest-color").defaultValue(new SettingColor(75, 0, 130, 200))
        .visible(scanEnderChests::get).build()
    );

    private final Setting<Boolean> scanChestMinecarts = sgStorage.add(new BoolSetting.Builder()
        .name("chest-minecarts").description("Detect chest minecarts (highlighted immediately, beam shows if stacked or confirmed loot).")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.CHEST_MINECART); }).build()
    );
    private final Setting<SettingColor> chestMinecartColor = sgStorage.add(new ColorSetting.Builder()
        .name("chest-minecart-color").defaultValue(new SettingColor(255, 180, 0, 200))
        .visible(scanChestMinecarts::get).build()
    );

    private final Setting<Integer> stackedMinecartThreshold = sgStorage.add(new IntSetting.Builder()
        .name("stacked-threshold")
        .description("How many minecarts at the same block position count as 'stacked' and trigger an immediate beam + chat alert.")
        .defaultValue(2).min(2).max(10).sliderRange(2, 5)
        .visible(scanChestMinecarts::get).build()
    );

    private final Setting<SettingColor> stackedMinecartColor = sgStorage.add(new ColorSetting.Builder()
        .name("stacked-minecart-color").description("Highlight and beam color for stacked chest minecarts.")
        .defaultValue(new SettingColor(255, 0, 255, 255))
        .visible(scanChestMinecarts::get).build()
    );

    private final Setting<SettingColor> shulkerFoundColor = sgStorage.add(new ColorSetting.Builder()
        .name("shulker-found-color").description("Bright color for chests/barrels confirmed to hold shulkers or custom items.")
        .defaultValue(new SettingColor(0, 255, 80, 255)).build()
    );

    // ── Utility ──

    private final Setting<Boolean> scanUtility = sgUtility.add(new BoolSetting.Builder()
        .name("utility-blocks")
        .description("Detect utility containers: furnaces, blast furnaces, smokers, hoppers, dispensers, and droppers.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.UTILITY); }).build()
    );
    private final Setting<SettingColor> utilityColor = sgUtility.add(new ColorSetting.Builder()
        .name("utility-color")
        .defaultValue(new SettingColor(150, 150, 150, 200))
        .visible(scanUtility::get).build()
    );

    // ── Decorative ──

    private final Setting<Boolean> scanDecorative = sgDecorative.add(new BoolSetting.Builder()
        .name("decorative-blocks")
        .description("Detect decorative containers: brewing stands, crafters, chiseled bookshelves, and decorated pots.")
        .defaultValue(true)
        .onChanged(v -> { if (!v) removeContainersOfType(StorageType.DECORATIVE); }).build()
    );
    private final Setting<SettingColor> decorativeColor = sgDecorative.add(new ColorSetting.Builder()
        .name("decorative-color")
        .defaultValue(new SettingColor(180, 100, 220, 200))
        .visible(scanDecorative::get).build()
    );

    private final Setting<Boolean> scanItemFramesSetting = sgDecorative.add(new BoolSetting.Builder()
        .name("item-frames").description("Detect item frames holding shulker boxes or custom items.")
        .defaultValue(true).build()
    );
    private final Setting<SettingColor> itemFrameColor = sgDecorative.add(new ColorSetting.Builder()
        .name("item-frame-color").defaultValue(new SettingColor(255, 100, 255, 200))
        .visible(scanItemFramesSetting::get).build()
    );

    private final Setting<Boolean> scanBeds = sgDecorative.add(new BoolSetting.Builder()
        .name("beds").description("Highlight all coloured beds in the surrounding area using their matching dye colour.")
        .defaultValue(false).build()
    );

    private final Setting<Integer> bedFillAlpha = sgDecorative.add(new IntSetting.Builder()
        .name("bed-fill-alpha").description("Fill transparency for bed highlights (0 = outline only).")
        .defaultValue(50).min(0).max(200).sliderMax(150)
        .visible(scanBeds::get).build()
    );

    // ─────────────────────────── Constructor ───────────────────────────

    public LootLens() {
        super(Tim.CATEGORY, "loot-lens", "Highlights storage containers confirmed to hold shulkers or custom items.");
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    @Override
    public void onActivate() {
        clearAllState();
        if (mc.player != null && mc.world != null && mc.world.getRegistryKey() != null)
            lastDimension = mc.world.getRegistryKey().getValue().toString();
    }

    @Override
    public void onDeactivate() { clearAllState(); }

    private void clearAllState() {
        containers.clear(); inventoryCheckedContainers.clear(); scannedByScanner.clear();
        shulkerContainers.clear(); shulkerCounts.clear();
        itemFrameEntities.clear(); glowItemFrameEntities.clear(); notifiedItemFrames.clear();
        minecartInventoryChecked.clear();
        knownStackedMinecarts.clear();
        bedPositions.clear();
        lastOpenedContainer = null; screenInventoryChecked = false; cleanupTimer = 0;
    }

    // ─────────────────────────── Tick Logic ───────────────────────────

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        try { if (mc.world.getRegistryKey() == null) return; } catch (Exception e) { return; }
        if (dimensionChangeCooldown > 0) { dimensionChangeCooldown--; return; }
        try {
            String currDim = mc.world.getRegistryKey().getValue().toString();
            if (!currDim.equals(lastDimension)) {
                dimensionChangeCooldown = DIMENSION_CHANGE_COOLDOWN_TICKS;
                lastDimension = currDim; clearAllState(); return;
            }
        } catch (Exception ignored) { return; }
        if (++cleanupTimer >= CLEANUP_INTERVAL) { cleanupTimer = 0; cleanupDistantContainers(); }
        scanChestMinecarts(); scanItemFrames();
        if (scanBeds.get()) scanDecorativeWorldBlocks();
        BlockPos currentPos = mc.player.getBlockPos();
        scanBlockEntities(currentPos.getX() >> 4, currentPos.getZ() >> 4);
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen instanceof HandledScreen<?>
                && !(mc.currentScreen instanceof InventoryScreen)
                && lastOpenedContainer != null && !screenInventoryChecked) {
            HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
            if (containers.containsKey(lastOpenedContainer) || shulkerContainers.contains(lastOpenedContainer)) {
                checkScreenInventoryForShulkers(screen); screenInventoryChecked = true;
            }
        }
        if (mc.currentScreen == null && lastOpenedContainer != null) {
            lastOpenedContainer = null; screenInventoryChecked = false;
        }
    }

    // ─────────────────────────── Screen Handler ───────────────────────────

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null || mc.world == null) return;
        screenInventoryChecked = false;
        if (event.screen instanceof InventoryScreen) return;
        HitResult hitResult = mc.crosshairTarget;
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK)
            lastOpenedContainer = ((BlockHitResult) hitResult).getBlockPos();
    }

    // ─────────────────────────── Mixin-facing API ───────────────────────────

    public void setLastInteractedPos(BlockPos pos) { lastOpenedContainer = pos; screenInventoryChecked = false; }
    public void onOpenScreenPacket() { screenInventoryChecked = false; }

    // ─────────────────────────── Helpers ───────────────────────────

    private boolean isImmediateHighlight(StorageType type) {
        return switch (type) {
            case SHULKER_BOX, ENDER_CHEST, UTILITY, DECORATIVE -> true;
            case CHEST, TRAPPED_CHEST, BARREL, CHEST_MINECART -> false;
        };
    }

    private boolean bposEquals(BlockPos a, BlockPos b) {
        return a != null && a.equals(b);
    }

    // ─────────────────────────── Container Logic ───────────────────────────

    private void checkScreenInventoryForShulkers(HandledScreen<?> screen) {
        if (lastOpenedContainer == null) return;
        if (mc.world != null && mc.world.getBlockState(lastOpenedContainer).getBlock() == Blocks.ENDER_CHEST) return;
        ScreenHandler handler    = screen.getScreenHandler();
        int playerInventoryStart = handler.slots.size() - 36;
        int shulkerCount         = 0;
        boolean previouslyHad    = shulkerContainers.contains(lastOpenedContainer);
        for (int i = 0; i < playerInventoryStart; i++) {
            Slot slot = handler.slots.get(i); ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            boolean isShulker = stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
            if (isShulker || customItems.get().contains(stack.getItem())) shulkerCount++;
        }
        StorageType type = containers.get(lastOpenedContainer);
        if (type != null && isImmediateHighlight(type)) return;

        inventoryCheckedContainers.add(lastOpenedContainer);
        if (type == StorageType.CHEST_MINECART) minecartInventoryChecked.add(lastOpenedContainer);

        BlockPos adjacentChest = findAdjacentChest(lastOpenedContainer, false);
        if (adjacentChest != null) inventoryCheckedContainers.add(adjacentChest);

        if (shulkerCount > 0) {
            shulkerContainers.add(lastOpenedContainer);
            shulkerCounts.put(lastOpenedContainer, shulkerCount);
            if (adjacentChest != null) { shulkerContainers.add(adjacentChest); shulkerCounts.put(adjacentChest, shulkerCount); }
            if (!previouslyHad && notification.get()) {
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                info("%d %s found!", shulkerCount, shulkerCount == 1 ? "item" : "items");
            }
        } else {
            if (type == StorageType.CHEST_MINECART
                    && knownStackedMinecarts.values().stream().anyMatch(
                        st -> st.stacked && bposEquals(st.lastBlockPos, lastOpenedContainer))) {
                minecartInventoryChecked.add(lastOpenedContainer);
                return;
            }
            containers.remove(lastOpenedContainer);
            shulkerContainers.remove(lastOpenedContainer);
            shulkerCounts.remove(lastOpenedContainer);
            minecartInventoryChecked.remove(lastOpenedContainer);
            if (adjacentChest != null) { containers.remove(adjacentChest); shulkerContainers.remove(adjacentChest); shulkerCounts.remove(adjacentChest); }
            if (previouslyHad && notification.get()) info("0 items found, removing highlight.");
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
                int dx = cx - centerChunkX, dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > chunkRangeSq) continue;
                WorldChunk chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getPos();
                    if (pos.getSquaredDistance(playerPos) > maxDistSq) continue;
                    if (scannedByScanner.contains(pos)
                            && !shulkerContainers.contains(pos)
                            && !inventoryCheckedContainers.contains(pos)) continue;
                    Block block = mc.world.getBlockState(pos).getBlock();
                    StorageType type = classifyBlock(block);
                    if (type != null) { containers.put(pos, type); scannedByScanner.add(pos); }
                }
            }
        }
    }

    private StorageType classifyBlock(Block block) {
        if (block == Blocks.CHEST         && scanChests.get())        return StorageType.CHEST;
        if (block == Blocks.TRAPPED_CHEST && scanChests.get())        return StorageType.TRAPPED_CHEST;
        if (block == Blocks.BARREL        && scanBarrels.get())       return StorageType.BARREL;
        if (block == Blocks.ENDER_CHEST   && scanEnderChests.get())   return StorageType.ENDER_CHEST;
        if (block instanceof ShulkerBoxBlock && scanShulkerBoxes.get()) return StorageType.SHULKER_BOX;
        if (scanUtility.get() && (block == Blocks.FURNACE
                || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER
                || block == Blocks.HOPPER
                || block == Blocks.DISPENSER
                || block == Blocks.DROPPER))                          return StorageType.UTILITY;
        if (scanDecorative.get() && (block == Blocks.BREWING_STAND
                || block == Blocks.CRAFTER
                || block == Blocks.CHISELED_BOOKSHELF
                || block == Blocks.DECORATED_POT))                    return StorageType.DECORATIVE;
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

        List<ChestMinecartEntity> minecarts = mc.world.getEntitiesByClass(ChestMinecartEntity.class, searchBox, e -> true);
        
        // Group minecarts into clusters based on proximity
        List<Set<ChestMinecartEntity>> clusters = new ArrayList<>();
        Set<ChestMinecartEntity> assigned = new HashSet<>();
        
        for (ChestMinecartEntity m1 : minecarts) {
            if (assigned.contains(m1)) continue;
            Set<ChestMinecartEntity> cluster = new HashSet<>();
            cluster.add(m1);
            assigned.add(m1);
            
            for (ChestMinecartEntity m2 : minecarts) {
                if (assigned.contains(m2)) continue;
                if (m1.squaredDistanceTo(m2) < 0.5) {
                    cluster.add(m2);
                    assigned.add(m2);
                }
            }
            clusters.add(cluster);
        }

        Set<UUID> seenClusterIds = new HashSet<>();
        Set<BlockPos> currentMinecartPositions = new HashSet<>();

        for (Set<ChestMinecartEntity> cluster : clusters) {
            if (cluster.isEmpty()) continue;
            
            int count = cluster.size();
            Vec3d centroid = new Vec3d(0, 0, 0);
            UUID clusterId = null;
            
            for (ChestMinecartEntity m : cluster) {
                centroid = centroid.add(m.getPos());
                currentMinecartPositions.add(m.getBlockPos());
                containers.putIfAbsent(m.getBlockPos(), StorageType.CHEST_MINECART);
                if (clusterId == null || m.getUuid().compareTo(clusterId) < 0) {
                    clusterId = m.getUuid();
                }
            }
            centroid = centroid.multiply(1.0 / count);
            BlockPos bpos = BlockPos.ofFloored(centroid);
            
            seenClusterIds.add(clusterId);
            updateStackedMinecartState(clusterId, bpos, centroid, count);
        }

        // Expire states whose minecarts have vanished entirely
        Iterator<Map.Entry<UUID, StackedState>> it = knownStackedMinecarts.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            Map.Entry<UUID, StackedState> e = it.next();
            UUID id = e.getKey();
            StackedState s = e.getValue();
            if (seenClusterIds.contains(id)) continue;

            if (++s.missingTicks < 3) continue; // brief vanish — keep

            boolean wasStacked = s.stacked;
            if (wasStacked) {
                clearStackedHighlight(s, id, s.lastBlockPos);
            }
            it.remove();
            if (wasStacked) removed++;
        }
        if (removed > 0 && notification.get() && mc.player != null) {
            int remaining = (int) knownStackedMinecarts.values().stream().filter(st -> st.stacked).count();
            if (remaining > 0)
                info("§7%d stacked minecart group(s) cleared. §f%d §7group(s) remaining.", removed, remaining);
            else
                info("§7All stacked minecart groups cleared.");
        }

        // Remove minecart container entries that are no longer present
        containers.entrySet().removeIf(entry -> {
            if (entry.getValue() != StorageType.CHEST_MINECART) return false;
            BlockPos pos = entry.getKey();
            if (currentMinecartPositions.contains(pos)) return false;
            if (knownStackedMinecarts.values().stream().anyMatch(st -> st.stacked && bposEquals(st.lastBlockPos, pos))) return false;
            inventoryCheckedContainers.remove(pos); scannedByScanner.remove(pos);
            shulkerContainers.remove(pos); shulkerCounts.remove(pos);
            minecartInventoryChecked.remove(pos);
            return true;
        });
    }

    private void updateStackedMinecartState(UUID id, BlockPos bpos, Vec3d centroid, int count) {
        final int entryThreshold = stackedMinecartThreshold.get();
        final int exitThreshold  = Math.max(1, entryThreshold - 1);

        StackedState s = knownStackedMinecarts.get(id);
        
        // If it's not stacked and doesn't meet entry threshold, don't even track it.
        if (s == null && count < entryThreshold) {
            return; 
        }
        
        if (s == null) s = new StackedState();
        knownStackedMinecarts.put(id, s);
        
        BlockPos oldPos = s.lastBlockPos;
        s.observedCount = count;
        s.lastBlockPos  = bpos;
        s.lastCentroid  = centroid;
        s.missingTicks  = 0;

        boolean meetsEntry = count >= entryThreshold;
        boolean meetsExit  = count <= exitThreshold;

        if (!s.stacked) {
            if (meetsEntry) {
                if (++s.entryDebounce >= 3) {
                    enterStacked(s, id, bpos, count);
                }
            } else {
                s.entryDebounce = 0;
            }
        } else {
            if (meetsExit) {
                if (++s.exitDebounce >= 3) {
                    clearStackedHighlight(s, id, bpos);
                    knownStackedMinecarts.remove(id);
                    if (notification.get() && mc.player != null) {
                        info("§7Stacked minecart group resolved (below exit threshold).");
                    }
                }
            } else {
                s.exitDebounce = 0;
            }

            if (s.stacked && s.confirmedCount != count) {
                s.confirmedCount = count;
                if (notification.get() && mc.player != null) {
                    info("§eStack updated: §f%d§e minecarts at one position.", count);
                }
            }

            if (s.stacked && bpos != null && !bpos.equals(oldPos)) {
                if (oldPos != null) {
                    shulkerContainers.remove(oldPos);
                    shulkerCounts.remove(oldPos);
                }
                shulkerContainers.add(bpos);
                shulkerCounts.put(bpos, count);
            } else if (s.stacked && bpos != null) {
                shulkerCounts.put(bpos, count);
            }
        }
    }

    private void enterStacked(StackedState s, UUID id, BlockPos bpos, int count) {
        s.stacked = true;
        s.confirmedCount = count;
        s.entryDebounce = 0;
        s.exitDebounce  = 0;
        if (bpos != null) {
            shulkerContainers.add(bpos);
            shulkerCounts.put(bpos, count);
        }
        if (notification.get() && mc.player != null) {
            info("§dStacked minecarts detected! §f%d§d minecarts at one position.", count);
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f);
        }
    }

    private void clearStackedHighlight(StackedState s, UUID id, BlockPos bpos) {
        s.stacked = false;
        s.entryDebounce = 0;
        s.exitDebounce  = 0;
        if (bpos != null) {
            if (!minecartInventoryChecked.contains(bpos) || !shulkerCounts.containsKey(bpos)) {
                shulkerContainers.remove(bpos);
                shulkerCounts.remove(bpos);
            }
        }
    }

    private void scanItemFrames() {
        if (!scanItemFramesSetting.get()) return;
        BlockPos playerPos = mc.player.getBlockPos();
        int scanRange = range.get();
        Box searchBox = new Box(
            playerPos.getX() - scanRange, playerPos.getY() - scanRange, playerPos.getZ() - scanRange,
            playerPos.getX() + scanRange, playerPos.getY() + scanRange, playerPos.getZ() + scanRange
        );
        Set<Vec3d> currentFramePositions = new HashSet<>();
        for (ItemFrameEntity frame : mc.world.getEntitiesByClass(ItemFrameEntity.class, searchBox, entity -> true)) {
            ItemStack heldStack = frame.getHeldItemStack();
            if (heldStack.isEmpty()) continue;
            boolean isShulker = heldStack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
            boolean isCustom  = customItems.get().contains(heldStack.getItem());
            if (!isShulker && !isCustom) continue;
            Vec3d pos = frame.getPos(); currentFramePositions.add(pos);
            if (frame instanceof GlowItemFrameEntity glow) glowItemFrameEntities.put(pos, glow);
            else itemFrameEntities.put(pos, frame);
            if (notifiedItemFrames.add(pos) && notification.get()) {
                if (isShulker) info("Shulker found in item frame!");
                else           info("Tracked item found in item frame!");
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
        }
        itemFrameEntities.entrySet().removeIf(e -> !currentFramePositions.contains(e.getKey()));
        glowItemFrameEntities.entrySet().removeIf(e -> !currentFramePositions.contains(e.getKey()));
        notifiedItemFrames.removeIf(pos -> !itemFrameEntities.containsKey(pos) && !glowItemFrameEntities.containsKey(pos));
    }

    // ─────────────────────────── Bed Scanning ───────────────────────────

    private void scanDecorativeWorldBlocks() {
        if (mc.player == null || mc.world == null) return;
        if (!scanBeds.get()) return;

        BlockPos playerPos = mc.player.getBlockPos();
        int rangeBlocks    = range.get();
        int chunkRange     = (rangeBlocks >> 4) + 1;
        int centerChunkX   = playerPos.getX() >> 4;
        int centerChunkZ   = playerPos.getZ() >> 4;
        int chunkRangeSq   = chunkRange * chunkRange;
        int maxDistSq      = rangeBlocks * rangeBlocks;

        bedPositions.clear();

        for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
            for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                int dx = cx - centerChunkX, dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > chunkRangeSq) continue;
                WorldChunk chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                ChunkSection[] sections = chunk.getSectionArray();
                for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
                    ChunkSection section = sections[sectionIdx];
                    if (section == null || section.isEmpty()) continue;

                    if (!section.hasAny(state -> state.getBlock() instanceof BedBlock)) continue;

                    int baseY = chunk.sectionIndexToCoord(sectionIdx) << 4;
                    int baseX = cx << 4;
                    int baseZ = cz << 4;

                    for (int lx = 0; lx < 16; lx++) {
                        for (int ly = 0; ly < 16; ly++) {
                            for (int lz = 0; lz < 16; lz++) {
                                BlockState state = section.getBlockState(lx, ly, lz);
                                Block block = state.getBlock();
                                BlockPos pos = new BlockPos(baseX + lx, baseY + ly, baseZ + lz);
                                if (pos.getSquaredDistance(playerPos) > maxDistSq) continue;

                                if (block instanceof BedBlock) {
                                    try {
                                        if (state.get(BedBlock.PART) != BedPart.HEAD) continue;
                                    } catch (Exception ignored) { continue; }
                                    DyeColor color = ((BedBlock) block).getColor();
                                    bedPositions.put(pos.toImmutable(), color);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private SettingColor dyeToColor(DyeColor dye, int alpha) {
        return switch (dye) {
            case WHITE      -> new SettingColor(255, 255, 255, alpha);
            case ORANGE     -> new SettingColor(255, 140,   0, alpha);
            case MAGENTA    -> new SettingColor(255,   0, 255, alpha);
            case LIGHT_BLUE -> new SettingColor(100, 200, 255, alpha);
            case YELLOW     -> new SettingColor(255, 240,   0, alpha);
            case LIME       -> new SettingColor(100, 230,  50, alpha);
            case PINK       -> new SettingColor(255, 150, 180, alpha);
            case GRAY       -> new SettingColor(100, 100, 100, alpha);
            case LIGHT_GRAY -> new SettingColor(190, 190, 190, alpha);
            case CYAN       -> new SettingColor(  0, 200, 200, alpha);
            case PURPLE     -> new SettingColor(150,   0, 200, alpha);
            case BLUE       -> new SettingColor( 30,  80, 200, alpha);
            case BROWN      -> new SettingColor(130,  80,  30, alpha);
            case GREEN      -> new SettingColor( 50, 160,  50, alpha);
            case RED        -> new SettingColor(220,  30,  30, alpha);
            case BLACK      -> new SettingColor( 30,  30,  30, alpha);
        };
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
                ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
            BlockPos   neighborPos   = pos.offset(neighborDir);
            BlockState neighborState = mc.world.getBlockState(neighborPos);
            if (!(neighborState.getBlock() instanceof ChestBlock)) return null;
            ChestType  neighborType   = neighborState.get(ChestBlock.CHEST_TYPE);
            Direction  neighborFacing = neighborState.get(ChestBlock.FACING);
            if (neighborFacing != facing || neighborType == ChestType.SINGLE || neighborType == chestType) return null;
            if (checkContainers && !containers.containsKey(neighborPos)) return null;
            return neighborPos;
        } catch (Exception ignored) { return null; }
    }

    // ─────────────────────────── Cleanup ───────────────────────────

    private void removeContainersOfType(StorageType type) {
        containers.entrySet().removeIf(entry -> {
            if (entry.getValue() != type) return false;
            BlockPos pos = entry.getKey();
            inventoryCheckedContainers.remove(pos); scannedByScanner.remove(pos);
            shulkerContainers.remove(pos); shulkerCounts.remove(pos);
            if (type == StorageType.CHEST_MINECART) {
                minecartInventoryChecked.remove(pos);
                knownStackedMinecarts.entrySet().removeIf(e -> {
                    StackedState s = e.getValue();
                    if (bposEquals(s.lastBlockPos, pos)) {
                        if (s.stacked) {
                            shulkerContainers.remove(pos);
                            shulkerCounts.remove(pos);
                        }
                        return true;
                    }
                    return false;
                });
            }
            return true;
        });
    }

    private void cleanupDistantContainers() {
        if (mc.player == null) return;
        BlockPos playerPos = mc.player.getBlockPos();
        int cleanupRange   = range.get() + (range.get() >> 1);
        int cleanupRangeSq = cleanupRange * cleanupRange;

        knownStackedMinecarts.entrySet().removeIf(entry -> {
            StackedState s = entry.getValue();
            if (s.lastBlockPos == null) return false;
            if (s.lastBlockPos.getSquaredDistance(playerPos) > cleanupRangeSq) {
                if (s.stacked) {
                    shulkerContainers.remove(s.lastBlockPos);
                    shulkerCounts.remove(s.lastBlockPos);
                }
                return true;
            }
            return false;
        });

        containers.entrySet().removeIf(entry -> {
            if (entry.getKey().getSquaredDistance(playerPos) <= cleanupRangeSq) return false;
            BlockPos pos = entry.getKey();
            if (knownStackedMinecarts.values().stream().anyMatch(st -> st.stacked && bposEquals(st.lastBlockPos, pos))) return false;
            inventoryCheckedContainers.remove(pos); scannedByScanner.remove(pos);
            shulkerContainers.remove(pos); shulkerCounts.remove(pos);
            minecartInventoryChecked.remove(pos);
            return true;
        });
    }

    // ─────────────────────────── Rendering ───────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        boolean isSpectral = renderMode.get() == RenderMode.SPECTRAL;
        boolean isPulse    = renderMode.get() == RenderMode.PULSE;
        Set<BlockPos>  toRemove             = new HashSet<>();
        Set<BlockPos>  renderedDoubleChests = new HashSet<>();
        List<BeamData> beamsToRender        = new ArrayList<>();

        renderItemFrames(event, beamsToRender);
        if (scanBeds.get()) renderBeds(event);

        for (Map.Entry<BlockPos, StorageType> entry : containers.entrySet()) {
            BlockPos    pos  = entry.getKey();
            StorageType type = entry.getValue();

            boolean shouldRender;
            if (type == StorageType.CHEST_MINECART) {
                // Minecarts should ALWAYS highlight, even if not opened yet.
                shouldRender = true;
            } else if (isImmediateHighlight(type)) {
                shouldRender = true;
            } else {
                shouldRender = shulkerContainers.contains(pos);
            }
            if (!shouldRender) continue;

            if (renderedDoubleChests.contains(pos)) continue;

            Box renderBox;
            SettingColor baseColor;
            boolean isStackedMinecart = false;

            if (type == StorageType.CHEST_MINECART) {
                isStackedMinecart = knownStackedMinecarts.values().stream().anyMatch(st -> st.stacked && bposEquals(st.lastBlockPos, pos));
                List<ChestMinecartEntity> minecarts = mc.world.getEntitiesByClass(
                    ChestMinecartEntity.class, new Box(pos), entity -> true);
                if (minecarts.isEmpty()) {
                    if (isStackedMinecart) {
                        renderBox = createPaddedBox(pos);
                    } else {
                        toRemove.add(pos); continue;
                    }
                } else {
                    renderBox = getMinecartChestBox(minecarts.get(0));
                }
                // Determine color: green if shulkers are confirmed, otherwise use standard minecart colors.
                boolean hasShulkers = shulkerContainers.contains(pos);
                baseColor = isStackedMinecart
                    ? (hasShulkers ? shulkerFoundColor.get() : stackedMinecartColor.get())
                    : (hasShulkers ? shulkerFoundColor.get() : chestMinecartColor.get());
            } else {
                BlockState currentState = mc.world.getBlockState(pos);
                if (!validateBlockType(currentState.getBlock(), type)) { toRemove.add(pos); continue; }
                BlockPos adjacentPos = findAdjacentChest(pos, true);
                if (adjacentPos != null) {
                    renderBox = createPaddedDoubleChestBox(pos, adjacentPos);
                    renderedDoubleChests.add(adjacentPos);
                } else if (type == StorageType.SHULKER_BOX) {
                    renderBox = createShulkerBox(pos, currentState);
                } else {
                    renderBox = createPaddedBox(pos);
                }
                baseColor = isImmediateHighlight(type) ? getColor(type) : shulkerFoundColor.get();
            }

            if (isSpectral) {
                int fillAlpha = (type == StorageType.CHEST_MINECART) ? 0 : spectralFillAlpha.get();
                int lineAlpha = (type == StorageType.CHEST_MINECART || !spectralOutline.get()) ? 0 : baseColor.a;
                event.renderer.box(renderBox, withAlpha(baseColor, fillAlpha), withAlpha(baseColor, lineAlpha),
                    spectralOutline.get() ? ShapeMode.Both : ShapeMode.Sides, 0);
            } else if (isPulse) {
                renderPulseBox(event, renderBox, baseColor);
            } else {
                renderGlowLayers(event, renderBox, baseColor);
                event.renderer.box(renderBox, withAlpha(baseColor, 0), baseColor, ShapeMode.Lines, 0);
            }

            // Exclude common blocks like Utility, Decorative, and Ender Chests from beam spam.
            // Single chest minecarts should also not have beams (only stacked ones do).
            boolean shouldBeam = type != StorageType.UTILITY && type != StorageType.DECORATIVE && type != StorageType.ENDER_CHEST;
            if (type == StorageType.CHEST_MINECART && !isStackedMinecart) {
                shouldBeam = false;
            }

            if (shouldBeam) {
                SettingColor beamColor = (isPulse && pulseBeams.get()) ? pulseColor(baseColor) : baseColor;
                beamsToRender.add(new BeamData(renderBox, beamColor));
            }
        }

        renderBeams(event, beamsToRender);

        if (!toRemove.isEmpty()) {
            for (BlockPos removePos : toRemove) {
                containers.remove(removePos); inventoryCheckedContainers.remove(removePos);
                scannedByScanner.remove(removePos); shulkerContainers.remove(removePos);
                shulkerCounts.remove(removePos); minecartInventoryChecked.remove(removePos);
            }
        }
    }

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int layers = glowLayers.get(); double spread = glowSpread.get(); int baseAlpha = glowBaseAlpha.get();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i; double t = (double)(i - 1) / layers;
            int layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - t * t)));
            event.renderer.box(box.expand(expansion), withAlpha(color, layerAlpha),
                withAlpha(color, 0), ShapeMode.Sides, 0);
        }
    }

    // ─────────────────────────── Pulse Rendering Helper ───────────────────────────

    /** Returns a smooth 0..1 factor driven by a sine wave. */
    private float getPulseFactor() {
        double speed = pulseSpeed.get();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float)((Math.sin(phase) + 1.0) * 0.5);
    }

    /** Map a base alpha through the pulse min/max range. */
    private int applyPulse(int baseAlpha) {
        float f = getPulseFactor();
        int min = pulseMinAlpha.get();
        int max = pulseMaxAlpha.get();
        return Math.min(255, Math.max(0, (int)(min + (max - min) * f)));
    }

    /** Convenience: clone a colour with its alpha pulsed. */
    private SettingColor pulseColor(SettingColor base) {
        return withAlpha(base, applyPulse(base.a));
    }

    /** Renders a box with pulsing glow layers and outline. */
    private void renderPulseBox(Render3DEvent event, Box box, SettingColor base) {
        int pa = applyPulse(base.a);
        SettingColor pColor = withAlpha(base, pa);
        int layers = glowLayers.get();
        double spread = glowSpread.get();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double)(i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int)(pa * taper));
            event.renderer.box(box.expand(expansion),
                withAlpha(pColor, layerAlpha), withAlpha(pColor, 0), ShapeMode.Sides, 0);
        }
        // Crisp pulsing outline + subtle pulsing fill
        event.renderer.box(box, withAlpha(pColor, pa / 3), pColor, ShapeMode.Both, 0);
    }

    // ─────────────────────────── Bed Rendering ───────────────────────────

    private void renderBeds(Render3DEvent event) {
        if (mc.world == null) return;
        boolean isSpectral = renderMode.get() == RenderMode.SPECTRAL;
        boolean isPulse    = renderMode.get() == RenderMode.PULSE;
        int fill = bedFillAlpha.get();

        for (Map.Entry<BlockPos, DyeColor> entry : bedPositions.entrySet()) {
            BlockPos pos = entry.getKey();
            DyeColor dye = entry.getValue();

            BlockState state = mc.world.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock)) continue;

            Direction facing = state.get(BedBlock.FACING);
            BlockPos footPos = pos.offset(facing.getOpposite());
            BlockState footState = mc.world.getBlockState(footPos);
            boolean hasFootBlock = footState.getBlock() instanceof BedBlock;

            Box renderBox;
            if (hasFootBlock) {
                double minX = Math.min(pos.getX(), footPos.getX());
                double minZ = Math.min(pos.getZ(), footPos.getZ());
                double maxX = Math.max(pos.getX(), footPos.getX()) + 1.0;
                double maxZ = Math.max(pos.getZ(), footPos.getZ()) + 1.0;
                renderBox = new Box(minX + 0.0625, pos.getY(), minZ + 0.0625,
                                    maxX - 0.0625, pos.getY() + 0.5625, maxZ - 0.0625);
            } else {
                renderBox = new Box(pos.getX() + 0.0625, pos.getY(), pos.getZ() + 0.0625,
                                    pos.getX() + 0.9375, pos.getY() + 0.5625, pos.getZ() + 0.9375);
            }

            SettingColor color = dyeToColor(dye, 200);

            if (isSpectral) {
                event.renderer.box(renderBox,
                    withAlpha(color, fill),
                    withAlpha(color, spectralOutline.get() ? color.a : 0),
                    spectralOutline.get() ? ShapeMode.Both : ShapeMode.Sides, 0);
            } else if (isPulse) {
                renderPulseBox(event, renderBox, color);
            } else {
                renderGlowLayers(event, renderBox, color);
                event.renderer.box(renderBox,
                    withAlpha(color, fill),
                    color, ShapeMode.Both, 0);
            }
        }
    }

    // ─────────────────────────── Beam Dispatch ───────────────────────────

    private void renderBeams(Render3DEvent event, List<BeamData> beams) {
        if (beams.isEmpty()) return;
        if (mergeBeams.get()) {
            List<BeamData> merged = new ArrayList<>();
            double distSq = Math.pow(mergeDistance.get(), 2);
            for (BeamData beam : beams) {
                boolean skip = false;
                double bx = (beam.box.minX + beam.box.maxX) / 2.0;
                double bz = (beam.box.minZ + beam.box.maxZ) / 2.0;
                for (BeamData m : merged) {
                    double mx = (m.box.minX + m.box.maxX) / 2.0;
                    double mz = (m.box.minZ + m.box.maxZ) / 2.0;
                    if (Math.pow(bx - mx, 2) + Math.pow(bz - mz, 2) <= distSq) { skip = true; break; }
                }
                if (!skip) merged.add(beam);
            }
            beams = merged;
        }
        for (BeamData beam : beams) {
            if (beamStyle.get() == BeamStyle.GUARDIAN) renderGuardianBeam(event, beam.box, beam.color);
            else                                        renderBoxBeam(event, beam.box, beam.color);
        }
    }

    // ─────────────────────────── Box Beam ───────────────────────────

    private void renderBoxBeam(Render3DEvent event, Box anchorBox, SettingColor color) {
        double beamSize = beamWidth.get() / 100.0;
        double centerX  = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double centerZ  = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int    worldBot = mc.world.getBottomY();
        int    worldTop = worldBot + mc.world.getHeight();
        Box beamBox = new Box(
            centerX - beamSize, worldBot, centerZ - beamSize,
            centerX + beamSize, worldTop, centerZ + beamSize);
        event.renderer.box(beamBox, withAlpha(color, 80), color, ShapeMode.Both, 0);
        for (int i = 1; i <= 2; i++) {
            double exp   = beamSize * i * 1.5;
            int    alpha = Math.max(4, 30 / i);
            Box bloom = new Box(
                centerX - beamSize - exp, worldBot, centerZ - beamSize - exp,
                centerX + beamSize + exp, worldTop, centerZ + beamSize + exp);
            event.renderer.box(bloom, withAlpha(color, alpha), withAlpha(color, 0), ShapeMode.Sides, 0);
        }
    }

    // ─────────────────────────── Guardian Beam ───────────────────────────

    private void renderGuardianBeam(Render3DEvent event, Box anchorBox, SettingColor color) {
        if (mc.world == null) return;

        double cx = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double cz = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.world.getBottomY();
        int worldTop = worldBot + mc.world.getHeight();

        double radius  = guardianBeamRadius.get();
        int    strands = guardianStrands.get();
        double speed   = guardianSpinSpeed.get();

        double rotationRad = (System.currentTimeMillis() % (long)(6000.0 / speed))
                             / (6000.0 / speed) * Math.PI * 2.0;

        Vec3d camPos = mc.gameRenderer.getCamera().getPos();
        double camX  = camPos.x, camY = camPos.y, camZ = camPos.z;

        float r       = color.r / 255f;
        float g       = color.g / 255f;
        float b       = color.b / 255f;
        float strandA = guardianStrandAlpha.get() / 255f;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

        MatrixStack matrices = new MatrixStack();
        matrices.push();

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buf = tessellator.begin(
            VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        org.joml.Matrix4f matrix = matrices.peek().getPositionMatrix();

        double relCx  = cx      - camX;
        double relCz  = cz      - camZ;
        double relBot = worldBot - camY;
        double relTop = worldTop - camY;

        for (int i = 0; i < strands; i++) {
            double angle = rotationRad + (Math.PI * 2.0 / strands) * i;
            double cos   = Math.cos(angle);
            double sin   = Math.sin(angle);

            double lx = relCx + cos * radius, lz = relCz + sin * radius;
            double rx = relCx - cos * radius, rz = relCz - sin * radius;

            float lxf = (float) lx, lzf = (float) lz;
            float rxf = (float) rx, rzf = (float) rz;
            float botF = (float) relBot, topF = (float) relTop;

            buf.vertex(matrix, lxf, botF, lzf).color(r, g, b, strandA);
            buf.vertex(matrix, rxf, botF, rzf).color(r, g, b, strandA);
            buf.vertex(matrix, lxf, topF, lzf).color(r, g, b, strandA);

            buf.vertex(matrix, rxf, botF, rzf).color(r, g, b, strandA);
            buf.vertex(matrix, rxf, topF, rzf).color(r, g, b, strandA);
            buf.vertex(matrix, lxf, topF, lzf).color(r, g, b, strandA);
        }

        BufferRenderer.drawWithGlobalProgram(buf.end());
        matrices.pop();

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        int coreAlpha = guardianCoreAlpha.get();
        if (coreAlpha > 0) {
            double coreR = radius * 0.25;
            Box coreBox = new Box(
                cx - coreR, worldBot, cz - coreR,
                cx + coreR, worldTop, cz + coreR);
            event.renderer.box(coreBox,
                withAlpha(color, coreAlpha),
                withAlpha(color, Math.min(255, coreAlpha + 40)),
                ShapeMode.Both, 0);
        }

        if (guardianGlow.get()) {
            double glowR = guardianGlowRadius.get();
            for (int ring = 1; ring <= 2; ring++) {
                double expansion = glowR * ring;
                int    alpha     = Math.max(4, 22 / ring);
                Box bloomBox = new Box(
                    cx - radius - expansion, worldBot, cz - radius - expansion,
                    cx + radius + expansion, worldTop, cz + radius + expansion);
                event.renderer.box(bloomBox,
                    withAlpha(color, alpha),
                    withAlpha(color, 0),
                    ShapeMode.Sides, 0);
            }
        }
    }

    // ─────────────────────────── Item Frame Rendering ───────────────────────────

    private void renderItemFrames(Render3DEvent event, List<BeamData> beams) {
        if (!scanItemFramesSetting.get()) return;
        SettingColor color = itemFrameColor.get();
        boolean isSpectral = renderMode.get() == RenderMode.SPECTRAL;
        boolean isPulse    = renderMode.get() == RenderMode.PULSE;
        for (ItemFrameEntity frame : itemFrameEntities.values()) {
            if (frame == null || frame.isRemoved()) continue;
            if (isSpectral) event.renderer.box(frame.getBoundingBox(),
                withAlpha(color, spectralFillAlpha.get()),
                withAlpha(color, spectralOutline.get() ? color.a : 0),
                spectralOutline.get() ? ShapeMode.Both : ShapeMode.Sides, 0);
            else if (isPulse) {
                renderPulseBox(event, frame.getBoundingBox(), color);
            } else {
                renderGlowLayers(event, frame.getBoundingBox(), color);
                event.renderer.box(frame.getBoundingBox(), withAlpha(color, 0), color, ShapeMode.Lines, 0);
            }
            ItemStack held = frame.getHeldItemStack();
            if (held.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                beams.add(new BeamData(frame.getBoundingBox(), color));
            }
        }
        for (GlowItemFrameEntity frame : glowItemFrameEntities.values()) {
            if (frame == null || frame.isRemoved()) continue;
            if (isSpectral) event.renderer.box(frame.getBoundingBox(),
                withAlpha(color, spectralFillAlpha.get()),
                withAlpha(color, spectralOutline.get() ? color.a : 0),
                spectralOutline.get() ? ShapeMode.Both : ShapeMode.Sides, 0);
            else if (isPulse) {
                renderPulseBox(event, frame.getBoundingBox(), color);
            } else {
                renderGlowLayers(event, frame.getBoundingBox(), color);
                event.renderer.box(frame.getBoundingBox(), withAlpha(color, 0), color, ShapeMode.Lines, 0);
            }
            ItemStack held = frame.getHeldItemStack();
            if (held.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                beams.add(new BeamData(frame.getBoundingBox(), color));
            }
        }
    }

    // ─────────────────────────── Color / Box Helpers ───────────────────────────

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    private Box getMinecartChestBox(ChestMinecartEntity minecart) {
        Box entityBox = minecart.getBoundingBox(); double chestSz = 14.0 / 16.0;
        double xPad = (entityBox.getLengthX() - chestSz) / 2.0, zPad = (entityBox.getLengthZ() - chestSz) / 2.0;
        double minY = entityBox.maxY - (10.0 / 16.0);
        return new Box(entityBox.minX + xPad, minY, entityBox.minZ + zPad,
                       entityBox.maxX - xPad, entityBox.maxY, entityBox.maxZ - zPad);
    }

    private Box createPaddedBox(BlockPos pos) {
        double p = 0.0625;
        return new Box(pos.getX()+p, pos.getY()+p, pos.getZ()+p,
                       pos.getX()+1-p, pos.getY()+1-p, pos.getZ()+1-p);
    }

    private Box createShulkerBox(BlockPos pos, BlockState state) {
        try {
            Box shape = state.getOutlineShape(mc.world, pos).getBoundingBox(); double p = 0.5 / 16.0;
            return new Box(pos.getX()+shape.minX-p, pos.getY()+shape.minY-p, pos.getZ()+shape.minZ-p,
                           pos.getX()+shape.maxX+p, pos.getY()+shape.maxY+p, pos.getZ()+shape.maxZ+p);
        } catch (Exception ignored) { return createPaddedBox(pos); }
    }

    private Box createPaddedDoubleChestBox(BlockPos pos1, BlockPos pos2) {
        double p = 0.0625;
        double minX = Math.min(pos1.getX(), pos2.getX()), minY = Math.min(pos1.getY(), pos2.getY()),
               minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxX = Math.max(pos1.getX(), pos2.getX())+1, maxY = Math.max(pos1.getY(), pos2.getY())+1,
               maxZ = Math.max(pos1.getZ(), pos2.getZ())+1;
        return new Box(minX+p, minY+p, minZ+p, maxX-p, maxY-p, maxZ-p);
    }

    // ─────────────────────────── Validation & Color Lookup ───────────────────────────

    private boolean validateBlockType(Block block, StorageType type) {
        return switch (type) {
            case CHEST          -> block == Blocks.CHEST;
            case TRAPPED_CHEST  -> block == Blocks.TRAPPED_CHEST;
            case BARREL         -> block == Blocks.BARREL;
            case SHULKER_BOX    -> block instanceof ShulkerBoxBlock;
            case ENDER_CHEST    -> block == Blocks.ENDER_CHEST;
            case UTILITY        -> block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE
                                || block == Blocks.SMOKER  || block == Blocks.HOPPER
                                || block == Blocks.DISPENSER || block == Blocks.DROPPER;
            case DECORATIVE     -> block == Blocks.BREWING_STAND || block == Blocks.CRAFTER
                                || block == Blocks.DECORATED_POT || block == Blocks.CHISELED_BOOKSHELF;
            case CHEST_MINECART -> true;
        };
    }

    private SettingColor getColor(StorageType type) {
        return switch (type) {
            case CHEST, TRAPPED_CHEST -> chestColor.get();
            case BARREL         -> barrelColor.get();
            case SHULKER_BOX    -> shulkerBoxColor.get();
            case ENDER_CHEST    -> enderChestColor.get();
            case CHEST_MINECART -> chestMinecartColor.get();
            case UTILITY        -> utilityColor.get();
            case DECORATIVE     -> decorativeColor.get();
        };
    }

    // ─────────────────────────── Public API ───────────────────────────

    public int getTotalContainers() { return containers.size(); }

    public boolean shouldShowStealDumpButtons() {
        return isActive() && stealDumpButtons.get();
    }

    public int getDoubleChestCount() {
        if (mc.world == null) return 0;
        Set<BlockPos> counted = new HashSet<>();
        int count = 0;
        for (Map.Entry<BlockPos, StorageType> entry : containers.entrySet()) {
            BlockPos    pos  = entry.getKey();
            StorageType type = entry.getValue();
            if (type != StorageType.CHEST && type != StorageType.TRAPPED_CHEST) continue;
            if (counted.contains(pos)) continue;
            BlockState state = mc.world.getBlockState(pos);
            if (!(state.getBlock() instanceof ChestBlock)) continue;
            try {
                ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
                if (chestType == ChestType.SINGLE) continue;
                BlockPos adjacent = findAdjacentChest(pos, false);
                if (adjacent == null) continue;
                counted.add(pos); counted.add(adjacent);
                count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    public int getShulkerBoxCount() {
        int count = 0;
        for (StorageType type : containers.values())
            if (type == StorageType.SHULKER_BOX) count++;
        return count;
    }

    public int getEnderChestCount() {
        int count = 0;
        for (StorageType type : containers.values())
            if (type == StorageType.ENDER_CHEST) count++;
        return count;
    }

    // ─────────────────────────── Storage Types ───────────────────────────

    private enum StorageType {
        CHEST, TRAPPED_CHEST, BARREL, SHULKER_BOX, ENDER_CHEST, CHEST_MINECART,
        UTILITY,
        DECORATIVE
    }

    private record BeamData(Box box, SettingColor color) {}

    /** Mutable per-stack state. */
    private static final class StackedState {
        boolean stacked         = false;
        int     observedCount   = 0;
        int     confirmedCount  = 0;
        int     entryDebounce   = 0;
        int     exitDebounce    = 0;
        int     missingTicks    = 0;
        BlockPos lastBlockPos   = null;
        Vec3d   lastCentroid    = null;
    }
}