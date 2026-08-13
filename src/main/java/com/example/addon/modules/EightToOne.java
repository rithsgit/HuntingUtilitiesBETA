package com.example.addon.modules;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public class EightToOne extends Module {

    private static final int    DIMENSION_SETTLE_TICKS           = 40;
    private static final int    ENTRY_EXCLUSION_COOLDOWN_TICKS   = 200;
    private static final int    ENTRY_EXCLUSION_RADIUS           = 5;
    private static final double ENTRY_EXCLUSION_RADIUS_SQ        = (double) ENTRY_EXCLUSION_RADIUS * ENTRY_EXCLUSION_RADIUS;
    private static final int    CHUNK_SCAN_LIMIT_PER_TICK        = 64;
    private static final int    CLEANUP_INTERVAL_TICKS           = 60;
    private static final long   MESSAGE_COOLDOWN_MS              = 2000;

    public enum HighlightStyle { GLOW, SPECTRAL, PULSE }
    public enum CoordVisibility { Visible, Censored, Hidden }
    public enum BeamStyle { BOX, GUARDIAN }
    public enum ReplenishItem { Obsidian, EnderChest }

    private final SettingGroup sgGeneral       = settings.getDefaultGroup();
    private final SettingGroup sgNetherPortals = settings.createGroup("Nether Portals");
    private final SettingGroup sgAnchors       = settings.createGroup("Respawn Anchors");
    private final SettingGroup sgRender        = settings.createGroup("Render");
    private final SettingGroup sgBeam          = settings.createGroup("Beam");
    private final SettingGroup sgReplenish     = settings.createGroup("Replenish");

    // ── Toggles ──
    private final Setting<Boolean> scanNetherPortals = sgNetherPortals.add(new BoolSetting.Builder()
        .name("scan-nether").description("Scan lit Nether portals.").defaultValue(true).build());

    private final Setting<Boolean> scanAnchors = sgAnchors.add(new BoolSetting.Builder()
        .name("scan-anchors").description("Scan Respawn Anchors.").defaultValue(true).build());

    // ── General ──
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Portal detection range in chunks.").defaultValue(32).min(16).max(64).build());

    private final Setting<Integer> autoMarkRange = sgGeneral.add(new IntSetting.Builder()
        .name("auto-mark-range").description("Auto-mark Nether portals within this many blocks of the player as created by you.")
        .defaultValue(10).min(0).max(50).visible(scanNetherPortals::get).build());

    private final Setting<Boolean> showCreatedCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-created-count").description("Show a chat message each time a new portal you created is discovered.")
        .defaultValue(true).build());

    private final Setting<CoordVisibility> coordVisibility = sgGeneral.add(new EnumSetting.Builder<CoordVisibility>()
        .name("coord-visibility").description("Controls how coordinates are displayed in chat.").defaultValue(CoordVisibility.Visible).build());

    // ── Nether Portals ──
    private final Setting<Boolean> differentiatePortalSizes = sgNetherPortals.add(new BoolSetting.Builder()
        .name("differentiate-sizes").description("Give exit portals and custom/built portals different colors.")
        .defaultValue(true).visible(scanNetherPortals::get).build());

    private final Setting<SettingColor> netherColorFull = sgNetherPortals.add(new ColorSetting.Builder()
        .name("color-exit-portal").defaultValue(new SettingColor(180, 60, 255, 255)).visible(scanNetherPortals::get).build());

    private final Setting<SettingColor> netherColorCustom = sgNetherPortals.add(new ColorSetting.Builder()
        .name("color-custom-built").defaultValue(new SettingColor(255, 140, 0, 255))
        .visible(() -> scanNetherPortals.get() && differentiatePortalSizes.get()).build());

    // ── Respawn Anchors ──
    private final Setting<SettingColor> anchorChargedColor = sgAnchors.add(new ColorSetting.Builder()
        .name("color-charged").defaultValue(new SettingColor(255, 200, 0, 255)).visible(scanAnchors::get).build());

    private final Setting<SettingColor> anchorUnchargedColor = sgAnchors.add(new ColorSetting.Builder()
        .name("color-uncharged").defaultValue(new SettingColor(100, 100, 120, 255)).visible(scanAnchors::get).build());

    private final Setting<Boolean> onlyShowChargedAnchors = sgAnchors.add(new BoolSetting.Builder()
        .name("only-charged").description("Only highlight anchors that have at least 1 charge.").defaultValue(false).visible(scanAnchors::get).build());

    // ── Render ──
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both).build());

    private final Setting<HighlightStyle> highlightStyle = sgRender.add(new EnumSetting.Builder<HighlightStyle>()
        .name("highlight-style").defaultValue(HighlightStyle.GLOW).build());

    private final Setting<Boolean> highlightFrame = sgRender.add(new BoolSetting.Builder()
        .name("highlight-frame").description("Highlights the obsidian frame of Nether portals.").defaultValue(true).visible(scanNetherPortals::get).build());

    private final Setting<Boolean> dynamicColors = sgRender.add(new BoolSetting.Builder()
        .name("dynamic-colors").defaultValue(false).build());

    private final Setting<Integer> glowLayers = sgRender.add(new IntSetting.Builder()
        .name("glow-layers").defaultValue(4).min(1).sliderMax(8)
        .visible(() -> highlightStyle.get() == HighlightStyle.GLOW || highlightStyle.get() == HighlightStyle.PULSE).build());

    private final Setting<Double> glowSpread = sgRender.add(new DoubleSetting.Builder()
        .name("glow-spread").defaultValue(0.05).min(0.01).sliderMax(0.2)
        .visible(() -> highlightStyle.get() == HighlightStyle.GLOW || highlightStyle.get() == HighlightStyle.PULSE).build());

    private final Setting<Integer> glowBaseAlpha = sgRender.add(new IntSetting.Builder()
        .name("glow-base-alpha").defaultValue(50).min(4).sliderMax(150).visible(() -> highlightStyle.get() == HighlightStyle.GLOW).build());

    private final Setting<Integer> spectralLineAlpha = sgRender.add(new IntSetting.Builder()
        .name("line-alpha").defaultValue(255).visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Integer> spectralFillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha").defaultValue(15).visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Double> spectralExpand = sgRender.add(new DoubleSetting.Builder()
        .name("expand").defaultValue(0.05).visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Double> pulseSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("pulse-speed")
        .description("Pulse cycle speed. 1.0 = one full fade in/out per second.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMax(3.0)
        .visible(() -> highlightStyle.get() == HighlightStyle.PULSE).build()
    );

    private final Setting<Integer> pulseMinAlpha = sgRender.add(new IntSetting.Builder()
        .name("pulse-min-alpha")
        .description("Lowest alpha reached during the pulse (0 = invisible).")
        .defaultValue(15).min(0).max(255).sliderMax(100)
        .visible(() -> highlightStyle.get() == HighlightStyle.PULSE).build()
    );

    private final Setting<Integer> pulseMaxAlpha = sgRender.add(new IntSetting.Builder()
        .name("pulse-max-alpha")
        .description("Peak alpha reached during the pulse.")
        .defaultValue(220).min(50).max(255).sliderMax(255)
        .visible(() -> highlightStyle.get() == HighlightStyle.PULSE).build()
    );

    // ── Beam ──
    private final Setting<Boolean> showBeam = sgBeam.add(new BoolSetting.Builder()
        .name("show-beam").defaultValue(true).build());

    private final Setting<Integer> beamRange = sgBeam.add(new IntSetting.Builder()
        .name("beam-range")
        .description("Maximum horizontal distance (in chunks) to render the vertical beam.")
        .defaultValue(16)
        .min(1)
        .sliderMax(64)
        .visible(showBeam::get)
        .build());

    private final Setting<Boolean> onlyNearestBeam = sgBeam.add(new BoolSetting.Builder()
        .name("only-nearest-beam")
        .description("Only render the beam for the portal closest to the player.")
        .defaultValue(false)
        .visible(showBeam::get)
        .build());

    private final Setting<BeamStyle> beamStyle = sgBeam.add(new EnumSetting.Builder<BeamStyle>()
        .name("beam-style").defaultValue(BeamStyle.GUARDIAN).visible(showBeam::get).build());

    private final Setting<Integer> beamWidth = sgBeam.add(new IntSetting.Builder()
        .name("beam-width").defaultValue(15).min(1).sliderMax(100)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.BOX).build());

    private final Setting<Double> guardianRadius = sgBeam.add(new DoubleSetting.Builder()
        .name("guardian-radius").defaultValue(0.08).min(0.01).sliderMax(1.0)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Integer> guardianStrands = sgBeam.add(new IntSetting.Builder()
        .name("guardian-strands").defaultValue(4).min(1).sliderMax(16)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Double> guardianSpinSpeed = sgBeam.add(new DoubleSetting.Builder()
        .name("guardian-spin-speed").defaultValue(1.0).min(0.1).sliderMax(5.0)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Integer> guardianCoreAlpha = sgBeam.add(new IntSetting.Builder()
        .name("guardian-core-alpha").defaultValue(90).min(4).sliderMax(255)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Integer> guardianStrandAlpha = sgBeam.add(new IntSetting.Builder()
        .name("guardian-strand-alpha").defaultValue(160).min(4).sliderMax(255)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    // ── Replenish ──
    private final Setting<Boolean> replenishMode = sgReplenish.add(new BoolSetting.Builder()
        .name("replenish-mode")
        .description("Toggles the replenish keybind.")
        .defaultValue(false)
        .build());

    private final Setting<ReplenishItem> replenishItem = sgReplenish.add(new EnumSetting.Builder<ReplenishItem>()
        .name("replenish-item")
        .description("The item to replenish.")
        .defaultValue(ReplenishItem.Obsidian)
        .visible(replenishMode::get)
        .build());

    private final Setting<Boolean> useSelectedSlot = sgReplenish.add(new BoolSetting.Builder()
        .name("use-selected-slot")
        .description("Replenishes the currently selected hotbar slot instead of a specific one.")
        .defaultValue(false)
        .visible(replenishMode::get)
        .build());

    private final Setting<Integer> targetSlot = sgReplenish.add(new IntSetting.Builder()
        .name("target-slot")
        .description("The specific hotbar slot to replenish (1-9).")
        .defaultValue(1)
        .min(1)
        .max(9)
        .visible(() -> replenishMode.get() && !useSelectedSlot.get())
        .build());

    private final Setting<Keybind> replenishKey = sgReplenish.add(new KeybindSetting.Builder()
        .name("replenish-key")
        .description("Replenishes the target hotbar slot's item to its max stack size from the main inventory.")
        .defaultValue(Keybind.none())
        .visible(replenishMode::get)
        .action(() -> {
            if (mc.currentScreen != null) return;
            if (mc.player == null || mc.world == null) return;
            if (!replenishMode.get()) return;
            handleReplenish();
        })
        .build());

    // ── State ──
    private final Map<BlockPos, PortalType> portals = new ConcurrentHashMap<>();
    private final Set<BlockPos> createdPortals = ConcurrentHashMap.newKeySet();
    private final Map<BlockPos, PortalStructure> portalStructureMap = new ConcurrentHashMap<>();
    private final Map<BlockPos, Boolean> anchorChargeMap = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<ChunkPos> dirtyChunks = new HashSet<>();
    private final Map<String, Long> messageCooldowns = new ConcurrentHashMap<>();

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;
    private int totalCreated = 0;
    private boolean portalsDirty = false;
    private boolean framesDirty = false;
    private BlockPos entryPortalPos = null;
    private int exclusionTimer = 0;
    private int cleanupTimer = 0;
    private boolean isDisconnecting = false; // Tracks if we are leaving a server

    private final Map<String, Boolean> crossDimensionSizeCache = new ConcurrentHashMap<>();

    public EightToOne() {
        super(Tim.CATEGORY, "eight-to-one", "Tracks Nether portals and Respawn Anchors with 8:1 conversion awareness.");
    }

    @Override
    public void onActivate() {
        clearAllState();
        if (mc.player != null && mc.world != null) lastDimension = mc.world.getRegistryKey().getValue().toString();
    }

    @Override
    public void onDeactivate() {
        clearAllState();
        // Only reset totalCreated on manual disable, NOT on disconnect
        if (!isDisconnecting) {
            totalCreated = 0;
        }
        isDisconnecting = false;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        // Mark that we're disconnecting so onDeactivate doesn't wipe the counter
        isDisconnecting = true;
    }

    private void clearAllState() {
        portals.clear(); createdPortals.clear(); portalStructureMap.clear();
        anchorChargeMap.clear(); scannedChunks.clear(); dirtyChunks.clear();
        crossDimensionSizeCache.clear();
        portalsDirty = false; framesDirty = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (isDisconnecting) isDisconnecting = false; // We're back in-game, clear disconnect flag
        if (dimensionChangeCooldown > 0) { dimensionChangeCooldown--; return; }
        if (exclusionTimer > 0) exclusionTimer--;

        handleDimensionChange();

        if (!dirtyChunks.isEmpty()) { 
            scannedChunks.removeAll(dirtyChunks); 
            dirtyChunks.clear(); 
        }

        BlockPos playerPos = mc.player.getBlockPos();
        scanNewChunks(playerPos.getX() >> 4, playerPos.getZ() >> 4);

        if (portalsDirty) {
            portalsDirty = false;
            groupPortals();
        }

        if (framesDirty) {
            framesDirty = false;
            precomputeFrameBoxes();
        }

        if (++cleanupTimer >= CLEANUP_INTERVAL_TICKS) {
            cleanupTimer = 0;
            cleanupDistantPortals();
        }
    }

    private void handleDimensionChange() {
        String currDim = mc.world.getRegistryKey().getValue().toString();
        if (currDim.equals(lastDimension)) return;

        dimensionChangeCooldown = DIMENSION_SETTLE_TICKS;
        exclusionTimer = ENTRY_EXCLUSION_COOLDOWN_TICKS;
        lastDimension = currDim;
        entryPortalPos = mc.player.getBlockPos();

        portals.clear(); createdPortals.clear(); portalStructureMap.clear(); scannedChunks.clear();
        dirtyChunks.clear(); crossDimensionSizeCache.clear(); anchorChargeMap.clear();
        portalsDirty = false; framesDirty = false;

        if (currDim.equals("minecraft:the_nether") || currDim.equals("minecraft:overworld")) {
            sendMessage("§7Entered " + (currDim.contains("nether") ? "Nether" : "Overworld") + " — 八対一 scanning started");
        }
    }

    private void precomputeFrameBoxes() {
        for (PortalStructure structure : portalStructureMap.values()) {
            if (structure.type != PortalType.NETHER) continue;
            
            Box frameBox = null;
            try {
                for (BlockPos p : structure.portalBlocks) {
                    for (Direction d : Direction.values()) {
                        BlockPos n = p.offset(d);
                        if (!structure.portalBlocks.contains(n)) {
                            if (isChunkLoaded(n) && mc.world.getBlockState(n).isOf(Blocks.OBSIDIAN)) {
                                Box nb = new Box(n);
                                frameBox = (frameBox == null) ? nb : frameBox.union(nb);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                frameBox = null;
            }
            
            structure.cachedFrameBox = (frameBox != null) ? frameBox.expand(0.02) : null;
        }
    }

    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r = range.get(), rSq = r * r, scanned = 0;
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                if (tryScanChunk(centerChunkX + x, centerChunkZ - d, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
                if (d > 0 && tryScanChunk(centerChunkX + x, centerChunkZ + d, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
            }
            for (int z = -d + 1; z < d; z++) {
                if (tryScanChunk(centerChunkX - d, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
                if (tryScanChunk(centerChunkX + d, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
            }
        }
    }

    private boolean tryScanChunk(int cx, int cz, int rSq, int centerCX, int centerCZ) {
        int dx = cx - centerCX, dz = cz - centerCZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp)) return false;

        WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz);
        if (chunk != null) {
            scanChunk(chunk);
            scannedChunks.add(cp);
            return true;
        }
        return false;
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int chunkX = chunk.getPos().x << 4;
        int chunkZ = chunk.getPos().z << 4;

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            boolean hasNether = false;
            boolean hasAnchor = false;
            
            if (scanNetherPortals.get()) {
                try {
                    hasNether = section.hasAny(state -> state.isOf(Blocks.NETHER_PORTAL));
                } catch (Exception e) {
                    hasNether = false;
                }
            }
            if (scanAnchors.get()) {
                try {
                    hasAnchor = section.hasAny(state -> state.isOf(Blocks.RESPAWN_ANCHOR));
                } catch (Exception e) {
                    hasAnchor = false;
                }
            }
            
            if (!hasNether && !hasAnchor) continue;

            int sectionMinY = (chunk.getBottomSectionCoord() + i) * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        var state = section.getBlockState(x, y, z);
                        if (hasAnchor && state.isOf(Blocks.RESPAWN_ANCHOR)) {
                            BlockPos pos = new BlockPos(chunkX + x, sectionMinY + y, chunkZ + z);
                            anchorChargeMap.put(pos, state.get(RespawnAnchorBlock.CHARGES) > 0);
                            portals.put(pos, PortalType.RESPAWN_ANCHOR);
                            portalsDirty = true;
                        } else if (hasNether && state.isOf(Blocks.NETHER_PORTAL)) {
                            BlockPos pos = new BlockPos(chunkX + x, sectionMinY + y, chunkZ + z);
                            portals.put(pos, PortalType.NETHER);
                            portalsDirty = true;
                            processNewDiscovery(pos);
                        }
                    }
                }
            }
        }
    }

    private PortalType classifyBlock(Block block) {
        if (scanNetherPortals.get() && block == Blocks.NETHER_PORTAL) return PortalType.NETHER;
        if (scanAnchors.get() && block == Blocks.RESPAWN_ANCHOR) return PortalType.RESPAWN_ANCHOR;
        return null;
    }

    private void processNewDiscovery(BlockPos pos) {
        if (autoMarkRange.get() <= 0 || mc.player == null) return;
        if (pos.getSquaredDistance(mc.player.getPos()) > (double) autoMarkRange.get() * autoMarkRange.get()) return;
        if (exclusionTimer > 0 && entryPortalPos != null && pos.getSquaredDistance(entryPortalPos) <= ENTRY_EXCLUSION_RADIUS_SQ) return;
        if (createdPortals.add(pos)) portalsDirty = true;
    }

    private void groupPortals() {
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> active = new HashSet<>();

        List<BlockPos> portalKeys = List.copyOf(portals.keySet());
        
        for (BlockPos startPos : portalKeys) {
            if (visited.contains(startPos)) continue;
            PortalType type = portals.get(startPos);
            if (type == null) continue;
            
            if (type == PortalType.RESPAWN_ANCHOR) {
                visited.add(startPos);
                if (onlyShowChargedAnchors.get() && !anchorChargeMap.getOrDefault(startPos, false)) continue;
                active.add(startPos);
                portalStructureMap.put(startPos, new PortalStructure(new Box(startPos).expand(0.02), Set.of(startPos), false, SizeState.EXIT, type));
                continue;
            }
            
            Set<BlockPos> component = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();
            Box structureBox = new Box(startPos);
            boolean isCreated = false;
            queue.add(startPos); visited.add(startPos);
            
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                component.add(current);
                if (createdPortals.contains(current)) isCreated = true;
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.offset(dir);
                    PortalType neighborType = portals.get(neighbor);
                    if (neighborType == type && visited.add(neighbor)) {
                        queue.add(neighbor);
                        structureBox = structureBox.union(new Box(neighbor));
                    }
                }
            }
            
            BlockPos anchor = componentAnchor(component);
            active.add(anchor);
            
            SizeState sizeState = SizeState.PENDING;
            String crossKey = lastDimension + ":" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
            Boolean crossCached = crossDimensionSizeCache.get(crossKey);
            if (crossCached != null) {
                sizeState = crossCached ? SizeState.EXIT : SizeState.CUSTOM;
            } else if (dimensionChangeCooldown <= 0) {
                boolean allCorners = hasObsidianOnAllCorners(component);
                sizeState = allCorners ? SizeState.EXIT : SizeState.CUSTOM;
                crossDimensionSizeCache.put(crossKey, allCorners);
            }
            
            if (isCreated && showCreatedCount.get() && !portalStructureMap.containsKey(anchor)) {
                totalCreated++;
                sendMessage("§aCreated Portal #" + totalCreated + (sizeState == SizeState.EXIT ? " §8[Exit]" : " §8[Custom]"));
            }
            
            PortalStructure structure = new PortalStructure(structureBox.expand(0.02), component, isCreated, sizeState, type);
            portalStructureMap.put(anchor, structure);
        }

        portalStructureMap.keySet().retainAll(active);
        framesDirty = true;
    }

    private boolean hasObsidianOnAllCorners(Set<BlockPos> component) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        
        for (BlockPos pos : component) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }
        
        BlockPos[] corners = (minX == maxX) 
            ? new BlockPos[]{
                new BlockPos(minX, minY-1, minZ-1), new BlockPos(minX, minY-1, maxZ+1),
                new BlockPos(minX, maxY+1, minZ-1), new BlockPos(minX, maxY+1, maxZ+1)
              }
            : new BlockPos[]{
                new BlockPos(minX-1, minY-1, minZ), new BlockPos(maxX+1, minY-1, minZ),
                new BlockPos(minX-1, maxY+1, minZ), new BlockPos(maxX+1, maxY+1, minZ)
              };
        
        for (BlockPos c : corners) {
            try {
                if (!isChunkLoaded(c) || !mc.world.getBlockState(c).isOf(Blocks.OBSIDIAN)) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private boolean isChunkLoaded(BlockPos pos) {
        return mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private BlockPos componentAnchor(Set<BlockPos> comp) {
        BlockPos anchor = null;
        for (BlockPos p : comp) {
            if (anchor == null || p.getY() < anchor.getY() || (p.getY() == anchor.getY() && p.getX() < anchor.getX())) {
                anchor = p;
            }
        }
        return anchor;
    }

    private void cleanupDistantPortals() {
        if (mc.player == null) return;
        double distSq = Math.pow(range.get() * 16 + 64, 2);
        boolean removed = false;
        
        if (portals.entrySet().removeIf(e -> e.getKey().getSquaredDistance(mc.player.getPos()) > distSq)) {
            portalsDirty = true;
            removed = true;
        }

        if (removed) {
            portalStructureMap.entrySet().removeIf(e -> 
                e.getValue().boundingBox.getCenter().squaredDistanceTo(mc.player.getPos()) > distSq);
            framesDirty = true;
        }

        int px = mc.player.getBlockPos().getX() >> 4, pz = mc.player.getBlockPos().getZ() >> 4;
        int rSq = range.get() * range.get();
        scannedChunks.removeIf(cp -> (cp.x - px) * (cp.x - px) + (cp.z - pz) * (cp.z - pz) > rSq);
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.world == null || mc.player == null) return;
        
        PortalType type = classifyBlock(event.newState.getBlock());
        if (type != null) { 
            portals.put(event.pos, type); 
            portalsDirty = true; 
        } else if (portals.remove(event.pos) != null) { 
            portalsDirty = true; 
        }
        
        if (event.newState.isOf(Blocks.OBSIDIAN) || event.oldState.isOf(Blocks.OBSIDIAN)) {
            crossDimensionSizeCache.clear(); 
            portalsDirty = true;
            framesDirty = true;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        double beamDistSq = Math.pow(beamRange.get() * 16.0, 2);

        PortalStructure nearest = null;
        if (showBeam.get() && onlyNearestBeam.get()) {
            double minSq = Double.MAX_VALUE;
            for (PortalStructure structure : portalStructureMap.values()) {
                double sq = mc.player.getPos().squaredDistanceTo(structure.boundingBox.getCenter());
                if (sq < minSq) { minSq = sq; nearest = structure; }
            }
        }

        List<PortalStructure> structuresToRender = List.copyOf(portalStructureMap.values());
        
        for (PortalStructure structure : structuresToRender) {
            BlockPos center = BlockPos.ofFloored(structure.boundingBox.getCenter());
            if (!isChunkLoaded(center)) continue;

            SettingColor color = getStructureColor(structure);
            if (color == null) continue;
            
            if (highlightStyle.get() == HighlightStyle.SPECTRAL) {
                renderSpectral(event, structure, color);
            } else if (highlightStyle.get() == HighlightStyle.PULSE) {
                if (highlightFrame.get() && structure.type == PortalType.NETHER && structure.cachedFrameBox != null) {
                    renderPulseBox(event, structure.cachedFrameBox, color);
                }
                renderPulseBox(event, structure.boundingBox, color);
            } else {
                if (highlightFrame.get() && structure.type == PortalType.NETHER && structure.cachedFrameBox != null) {
                    renderGlowLayers(event, structure.cachedFrameBox, color);
                    event.renderer.box(structure.cachedFrameBox, withAlpha(color, 0), color, shapeMode.get(), 0);
                }
                renderGlowLayers(event, structure.boundingBox, color);
                event.renderer.box(structure.boundingBox, withAlpha(color, 0), color, shapeMode.get(), 0);
            }
            
            if (showBeam.get() && (nearest == null || structure == nearest) 
                && mc.player.getPos().squaredDistanceTo(structure.boundingBox.getCenter()) <= beamDistSq) {
                SettingColor beamColor = (highlightStyle.get() == HighlightStyle.PULSE) ? pulseColor(color) : color;
                renderBeams(event, List.of(new BeamData(structure.boundingBox, beamColor)));
            }
        }
    }

    private void renderSpectral(Render3DEvent event, PortalStructure structure, SettingColor color) {
        double expand = spectralExpand.get();
        Box renderBox = structure.boundingBox.expand(expand);
        int lineAlpha = spectralLineAlpha.get();
        event.renderer.box(renderBox, withAlpha(color, spectralFillAlpha.get()), withAlpha(color, lineAlpha), ShapeMode.Both, 0);
    }

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int layers = glowLayers.get(); 
        double spread = glowSpread.get(); 
        int baseAlpha = glowBaseAlpha.get();
        for (int i = layers; i >= 1; i--) {
            int layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i-1) / layers)));
            event.renderer.box(box.expand(spread * i), withAlpha(color, layerAlpha), withAlpha(color, 0), ShapeMode.Sides, 0);
        }
    }

    private float getPulseFactor() {
        double speed = pulseSpeed.get();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float)((Math.sin(phase) + 1.0) * 0.5);
    }

    private int applyPulse(int baseAlpha) {
        float f = getPulseFactor();
        int min = pulseMinAlpha.get();
        int max = pulseMaxAlpha.get();
        return Math.min(255, Math.max(0, (int)(min + (max - min) * f)));
    }

    private SettingColor pulseColor(SettingColor base) {
        return withAlpha(base, applyPulse(base.a));
    }

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
        event.renderer.box(box, withAlpha(pColor, pa / 3), pColor, ShapeMode.Both, 0);
    }

    private void renderBeams(Render3DEvent event, List<BeamData> beams) {
        for (BeamData beam : beams) {
            if (beamStyle.get() == BeamStyle.GUARDIAN) renderGuardianBeam(event, beam.box, beam.color);
            else renderBoxBeam(event, beam.box, beam.color);
        }
    }

    private void renderBoxBeam(Render3DEvent event, Box anchorBox, SettingColor color) {
        double beamSize = Math.max(0.01, beamWidth.get() / 100.0);
        double centerX = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double centerZ = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.world.getBottomY(), worldTop = worldBot + mc.world.getHeight();
        Box beamBox = new Box(centerX - beamSize, worldBot, centerZ - beamSize, centerX + beamSize, worldTop, centerZ + beamSize);
        renderGlowLayers(event, beamBox, color);
        event.renderer.box(beamBox, withAlpha(color, 60), color, ShapeMode.Both, 0);
    }

    private void renderGuardianBeam(Render3DEvent event, Box anchorBox, SettingColor color) {
        double cx = (anchorBox.minX + anchorBox.maxX) / 2.0, cz = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.world.getBottomY(), worldTop = worldBot + mc.world.getHeight();
        double radius = Math.max(0.01, guardianRadius.get());
        double rotationRad = (System.currentTimeMillis() % 6000L) / 6000.0 * Math.PI * 2.0 * guardianSpinSpeed.get();
        for (int i = 0; i < guardianStrands.get(); i++) {
            double angle = rotationRad + (Math.PI * 2.0 / guardianStrands.get()) * i;
            Box strandBox = new Box(
                cx + Math.cos(angle) * radius - 0.01, worldBot, cz + Math.sin(angle) * radius - 0.01,
                cx + Math.cos(angle) * radius + 0.01, worldTop, cz + Math.sin(angle) * radius + 0.01
            );
            event.renderer.box(strandBox, withAlpha(color, guardianStrandAlpha.get() / 2), withAlpha(color, guardianStrandAlpha.get()), ShapeMode.Both, 0);
        }
    }

    private SettingColor getStructureColor(PortalStructure structure) {
        if (structure.type == PortalType.RESPAWN_ANCHOR) {
            boolean charged = false;
            for (BlockPos p : structure.portalBlocks) {
                charged = anchorChargeMap.getOrDefault(p, false);
                break;
            }
            if (dynamicColors.get()) {
                float hue = ((charged ? 0.13f : 0.65f) + (System.currentTimeMillis() % 3000) / 3000f) % 1f;
                int rgb = java.awt.Color.HSBtoRGB(hue, 0.8f, 1.0f);
                return new SettingColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
            }
            return charged ? anchorChargedColor.get() : anchorUnchargedColor.get();
        }
        if (dynamicColors.get()) {
            float hue = ((structure.isFullSize() ? 0.78f : 0.08f) + (System.currentTimeMillis() % 3000) / 3000f) % 1f;
            int rgb = java.awt.Color.HSBtoRGB(hue, 0.8f, 1.0f);
            return new SettingColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
        }
        return (differentiatePortalSizes.get() && !structure.isFullSize()) ? netherColorCustom.get() : netherColorFull.get();
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    private void sendMessage(String message) {
        long now = System.currentTimeMillis();
        if (now - messageCooldowns.getOrDefault(message, 0L) > MESSAGE_COOLDOWN_MS) {
            info(message); 
            messageCooldowns.put(message, now);
        }
    }

    // ── Replenish Feature ──
    private void handleReplenish() {
        int selectedSlot = useSelectedSlot.get() 
            ? mc.player.getInventory().selectedSlot 
            : targetSlot.get() - 1; // Convert 1-9 to 0-8

        ItemStack targetStack = mc.player.getInventory().getStack(selectedSlot);
        Item targetItem = replenishItem.get() == ReplenishItem.Obsidian 
            ? Items.OBSIDIAN 
            : Items.ENDER_CHEST;

        // Abort if the slot is already occupied by a different item
        if (!targetStack.isEmpty() && targetStack.getItem() != targetItem) {
            info("Target slot has a different item — cannot replenish.");
            return;
        }

        int maxCount = targetItem.getMaxCount();
        int currentCount = targetStack.getCount();
        int needed = maxCount - currentCount;

        if (needed <= 0) {
            info("Stack is already full (" + maxCount + ").");
            return;
        }

        // Search main inventory (slots 9–35) for matching items and move them
        // into the target hotbar slot until it reaches max stack size.
        for (int i = 9; i < 36 && needed > 0; i++) {
            ItemStack sourceStack = mc.player.getInventory().getStack(i);
            if (sourceStack.isEmpty()) continue;
            if (sourceStack.getItem() != targetItem) continue;

            int available = sourceStack.getCount();

            InvUtils.move().from(i).toHotbar(selectedSlot);

            needed -= Math.min(needed, available);
        }

        int finalCount = maxCount - needed;

        if (needed > 0) {
            info("Replenished " + targetItem.getName().getString()
                + " to " + finalCount + " (not enough items in inventory).");
        } else {
            info("Replenished " + targetItem.getName().getString()
                + " to " + maxCount + ".");
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }

    public boolean isPortalGuiEnabled() { return isActive(); }
    public int getTotalPortals() { return (int) portalStructureMap.values().stream().filter(s -> s.type == PortalType.NETHER).count(); }
    public int getTotalAnchors() { return (int) portalStructureMap.values().stream().filter(s -> s.type == PortalType.RESPAWN_ANCHOR).count(); }
    public int getTotalCreated() { return totalCreated; }
    public void markChunkDirty(ChunkPos cp) { scannedChunks.remove(cp); dirtyChunks.add(cp); portalsDirty = true; framesDirty = true; }

    private enum PortalType { NETHER, RESPAWN_ANCHOR }
    private enum SizeState { PENDING, EXIT, CUSTOM }
    
    private static class PortalStructure {
        final Box boundingBox; 
        final Set<BlockPos> portalBlocks; 
        final boolean isCreated; 
        final SizeState sizeState; 
        final PortalType type;
        Box cachedFrameBox;
        
        PortalStructure(Box bb, Set<BlockPos> pb, boolean ic, SizeState ss, PortalType t) {
            this.boundingBox = bb; 
            this.portalBlocks = pb; 
            this.isCreated = ic; 
            this.sizeState = ss; 
            this.type = t;
            this.cachedFrameBox = null;
        }
        
        boolean isFullSize() { return sizeState == SizeState.EXIT; }
    }
    
    private record BeamData(Box box, SettingColor color) {}
}