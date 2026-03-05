package com.example.addon.modules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.EndGatewayBlockEntity;
import net.minecraft.block.entity.EndPortalBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

public class PortalTracker extends Module {

    // ───────────────────────────────────────────────────────────────
    // Constants
    // ───────────────────────────────────────────────────────────────

    private static final int    DIMENSION_SETTLE_TICKS           = 40;
    private static final int    ENTRY_EXCLUSION_COOLDOWN_TICKS   = 200;
    private static final int    ENTRY_EXCLUSION_RADIUS           = 5;
    private static final double ENTRY_EXCLUSION_RADIUS_SQ        = ENTRY_EXCLUSION_RADIUS * ENTRY_EXCLUSION_RADIUS;
    private static final int    CHUNK_SCAN_LIMIT_PER_TICK        = 10;
    private static final int    STRUCTURE_REBUILD_INTERVAL_TICKS = 5;
    private static final int    CLEANUP_INTERVAL_TICKS           = 60;
    private static final long   MESSAGE_COOLDOWN_MS              = 2000;

    // ───────────────────────────────────────────────────────────────
    // Settings
    // ───────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral       = settings.getDefaultGroup();
    private final SettingGroup sgNetherPortals = settings.createGroup("Nether Portals");
    private final SettingGroup sgEndDimension  = settings.createGroup("End Dimension");
    private final SettingGroup sgRender        = settings.createGroup("Render");

    // -- General --

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Portal detection range in chunks.")
        .defaultValue(32)
        .min(16).max(64)
        .sliderMin(16).sliderMax(64)
        .build()
    );

    private final Setting<Integer> autoMarkRange = sgGeneral.add(new IntSetting.Builder()
        .name("auto-mark-range")
        .description("Auto-mark Nether portals within this many blocks of the player as created by you.")
        .defaultValue(10)
        .min(0).max(50)
        .sliderMin(0).sliderMax(50)
        .build()
    );

    private final Setting<Boolean> trackOverworld = sgGeneral.add(new BoolSetting.Builder()
        .name("track-overworld")
        .description("Also auto-mark portals found in the Overworld as created by you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showCreatedCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-created-count")
        .description("Show a chat message each time a new portal you created is discovered.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyShowCreated = sgGeneral.add(new BoolSetting.Builder()
        .name("only-show-created")
        .description("Only highlight portals you've created.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showBeam = sgGeneral.add(new BoolSetting.Builder()
        .name("show-beam")
        .description("Show a vertical beam above each tracked portal.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> beamWidth = sgGeneral.add(new IntSetting.Builder()
        .name("beam-width")
        .description("Beam width in hundredths of a block.")
        .defaultValue(15)
        .min(5).max(50)
        .sliderMin(5).sliderMax(50)
        .visible(showBeam::get)
        .build()
    );

    private final Setting<Boolean> dynamicColors = sgGeneral.add(new BoolSetting.Builder()
        .name("dynamic-colors")
        .description("Animate portal colors. Each type uses a distinct hue offset so types stay visually distinguishable.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> resetButton = sgGeneral.add(new BoolSetting.Builder()
        .name("reset")
        .description("Reset the current session and clear all created-portal records.")
        .defaultValue(false)
        .onChanged(this::handleReset)
        .build()
    );

    // -- Nether Portals --

    private final Setting<Boolean> scanNetherPortals = sgNetherPortals.add(new BoolSetting.Builder()
        .name("scan-nether")
        .description("Scan lit Nether portals.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> netherColor = sgNetherPortals.add(new ColorSetting.Builder()
        .name("nether-color")
        .defaultValue(new SettingColor(180, 60, 255, 120))
        .visible(scanNetherPortals::get)
        .build()
    );

    // -- End Dimension --

    private final Setting<Boolean> scanEndPortals = sgEndDimension.add(new BoolSetting.Builder()
        .name("end-portals")
        .description("Scan End portal blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> endPortalColor = sgEndDimension.add(new ColorSetting.Builder()
        .name("end-portal-color")
        .defaultValue(new SettingColor(0, 255, 128, 100))
        .visible(scanEndPortals::get)
        .build()
    );

    private final Setting<Boolean> scanEndGateways = sgEndDimension.add(new BoolSetting.Builder()
        .name("end-gateways")
        .description("Scan End gateways.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> endGatewayColor = sgEndDimension.add(new ColorSetting.Builder()
        .name("end-gateway-color")
        .defaultValue(new SettingColor(255, 0, 255, 100))
        .visible(scanEndGateways::get)
        .build()
    );

    // -- Render --

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Render style for portal highlights.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    // ───────────────────────────────────────────────────────────────
    // State
    // ───────────────────────────────────────────────────────────────

    // Portal data
    private final Map<BlockPos, PortalType> portals          = new ConcurrentHashMap<>();
    private final Set<BlockPos>             createdPortals   = ConcurrentHashMap.newKeySet();
    private final List<PortalStructure>     portalStructures = new CopyOnWriteArrayList<>();
    private volatile boolean                portalsDirty     = false;

    // Chunk scanning
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<ChunkPos> dirtyChunks   = new HashSet<>();

    // Notification dedup
    private final Set<String>       notifiedStructures = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> messageCooldowns   = new ConcurrentHashMap<>();

    // Dimension tracking
    private String lastDimension           = "";
    private int    dimensionChangeCooldown = 0;

    // Entry-portal exclusion (avoids auto-marking the portal you arrived through)
    private BlockPos entryPortalPos = null;
    private int      exclusionTimer = 0;

    // Session
    private boolean manuallyActivated = false;
    private long    sessionStartTime  = 0;
    private int     totalCreated      = 0;

    // Throttle timers
    private int structureTimer = 0;
    private int cleanupTimer   = 0;

    // ───────────────────────────────────────────────────────────────
    // Constructor
    // ───────────────────────────────────────────────────────────────

    public PortalTracker() {
        super(HuntingUtilities.CATEGORY, "portal-tracker", "Automatically tracks and highlights portals.");
    }

    // ───────────────────────────────────────────────────────────────
    // Lifecycle
    // ───────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        clearAllState();
        sessionStartTime = System.currentTimeMillis();

        if (mc.player != null && mc.world != null && mc.world.getRegistryKey() != null) {
            lastDimension = mc.world.getRegistryKey().getValue().toString();
        }
    }

    @Override
    public void onDeactivate() {
        if (manuallyActivated && mc.player != null) {
            long elapsed = System.currentTimeMillis() - sessionStartTime;
            if (elapsed > 0) {
                sendMessage("§7Session ended — §f" + portalStructures.size()
                    + " §7portals discovered §8| §a" + totalCreated + " §7created");
            }
        }
        clearAllState();
    }

    private void clearAllState() {
        portals.clear();
        createdPortals.clear();
        portalStructures.clear();
        notifiedStructures.clear();
        messageCooldowns.clear();
        scannedChunks.clear();
        dirtyChunks.clear();
        portalsDirty      = false;
        manuallyActivated = false;
        sessionStartTime  = 0;
        structureTimer    = 0;
        cleanupTimer      = 0;
    }

    // ───────────────────────────────────────────────────────────────
    // Tick
    // ───────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        try {
            if (mc.world.getRegistryKey() == null) return;
        } catch (Exception e) { return; }

        if (dimensionChangeCooldown > 0) {
            dimensionChangeCooldown--;
            return;
        }

        if (exclusionTimer > 0) exclusionTimer--;

        if (handleDimensionChange()) return;

        // Flush dirty chunks before scanning so re-scans happen this tick.
        if (!dirtyChunks.isEmpty()) {
            scannedChunks.removeAll(dirtyChunks);
            dirtyChunks.clear();
        }

        BlockPos playerPos    = mc.player.getBlockPos();
        int      centerChunkX = playerPos.getX() >> 4;
        int      centerChunkZ = playerPos.getZ() >> 4;

        scanBlockEntities(centerChunkX, centerChunkZ);
        scanNewChunks(centerChunkX, centerChunkZ);

        if (portalsDirty && ++structureTimer >= STRUCTURE_REBUILD_INTERVAL_TICKS) {
            structureTimer = 0;
            portalsDirty   = false;
            groupPortals();
        }

        if (++cleanupTimer >= CLEANUP_INTERVAL_TICKS) {
            cleanupTimer = 0;
            cleanupDistantPortals();
            cleanupTrackedData();
            cleanupDistantChunks(centerChunkX, centerChunkZ);
        }

        if (!manuallyActivated) manuallyActivated = true;
    }

    /**
     * Detects a dimension change and resets all portal state for the new dimension.
     * Returns true if a change was detected so the caller can return early.
     */
    private boolean handleDimensionChange() {
        try {
            String currDim = mc.world.getRegistryKey().getValue().toString();
            if (currDim.equals(lastDimension)) return false;

            dimensionChangeCooldown = DIMENSION_SETTLE_TICKS;
            exclusionTimer          = ENTRY_EXCLUSION_COOLDOWN_TICKS;
            lastDimension           = currDim;
            entryPortalPos          = mc.player.getBlockPos();

            portals.clear();
            createdPortals.clear();
            portalStructures.clear();
            notifiedStructures.clear();
            scannedChunks.clear();
            dirtyChunks.clear();
            portalsDirty = false;

            boolean notify =
                (currDim.equals("minecraft:the_nether") && scanNetherPortals.get()) ||
                (currDim.equals("minecraft:overworld")   && scanNetherPortals.get()) ||
                (currDim.equals("minecraft:the_end")     && (scanEndPortals.get() || scanEndGateways.get()));
            if (notify) sendMessage("§7Entered " + getDimensionName(currDim) + " — scanning started");
            return true;
        } catch (Exception ignored) {
            return true;
        }
    }

    // ───────────────────────────────────────────────────────────────
    // Scanning
    // ───────────────────────────────────────────────────────────────

    /**
     * Scans block entities (EndPortal, EndGateway) in chunks within range.
     * Skips chunks already in scannedChunks.
     */
    private void scanBlockEntities(int centerChunkX, int centerChunkZ) {
        int chunkRange   = range.get();
        int chunkRangeSq = chunkRange * chunkRange;
        int maxDistSq    = (chunkRange * 16) * (chunkRange * 16);
        String   dimId     = mc.world.getRegistryKey().getValue().toString();
        BlockPos playerPos = mc.player.getBlockPos();

        for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
            for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                int dx = cx - centerChunkX;
                int dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > chunkRangeSq) continue;

                ChunkPos cp = new ChunkPos(cx, cz);
                if (scannedChunks.contains(cp)) continue;

                WorldChunk chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getPos();
                    if (pos.getSquaredDistance(playerPos) > maxDistSq) continue;

                    PortalType type = classifyBlockEntity(be);
                    if (type != null && !portals.containsKey(pos)) {
                        portals.put(pos, type);
                        portalsDirty = true;
                        processNewDiscovery(pos, type, dimId);
                    }
                }
            }
        }
    }

    /**
     * Scans unvisited chunks concentrically outward from the player,
     * capped at CHUNK_SCAN_LIMIT_PER_TICK per tick.
     * Only NETHER_PORTAL blocks are detected here; End types are block entities.
     */
    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r       = range.get();
        int rSq     = r * r;
        int scanned = 0;

        outer:
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                for (int side = 0; side < 2; side++) {
                    int z = (side == 0) ? -d : d;
                    if (processChunk(centerChunkX + x, centerChunkZ + z, rSq, centerChunkX, centerChunkZ))
                        if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) break outer;
                }
            }
            for (int z = -d + 1; z < d; z++) {
                for (int side = 0; side < 2; side++) {
                    int x = (side == 0) ? -d : d;
                    if (processChunk(centerChunkX + x, centerChunkZ + z, rSq, centerChunkX, centerChunkZ))
                        if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) break outer;
                }
            }
        }
    }

    private boolean processChunk(int cx, int cz, int rSq, int centerChunkX, int centerChunkZ) {
        int dx = cx - centerChunkX;
        int dz = cz - centerChunkZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp)) return false;
        if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) return false;

        scanChunk(mc.world.getChunk(cx, cz));
        scannedChunks.add(cp);
        return true;
    }

    private void scanChunk(WorldChunk chunk) {
        String         dimId    = mc.world.getRegistryKey().getValue().toString();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionMinY = (chunk.getBottomSectionCoord() + i) * 16;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        PortalType type = classifyBlock(section.getBlockState(x, y, z).getBlock());
                        if (type == null) continue;

                        BlockPos pos = new BlockPos(
                            (chunk.getPos().x << 4) + x,
                            sectionMinY + y,
                            (chunk.getPos().z << 4) + z
                        );
                        if (!portals.containsKey(pos)) {
                            portals.put(pos, type);
                            portalsDirty = true;
                            processNewDiscovery(pos, type, dimId);
                        }
                    }
                }
            }
        }
    }

    // ───────────────────────────────────────────────────────────────
    // Classification
    // ───────────────────────────────────────────────────────────────

    /** Block-state scan — Nether portals only. End types are block entities. */
    private PortalType classifyBlock(Block block) {
        if (scanNetherPortals.get() && block == Blocks.NETHER_PORTAL) return PortalType.NETHER;
        return null;
    }

    /** Block-entity scan — End portal and gateway only. */
    private PortalType classifyBlockEntity(BlockEntity be) {
        if (scanEndGateways.get() && be instanceof EndGatewayBlockEntity) return PortalType.END_GATEWAY;
        if (scanEndPortals.get()  && be instanceof EndPortalBlockEntity)  return PortalType.END_PORTAL;
        return null;
    }

    private boolean isTrackedPortalBlock(Block block) {
        return block == Blocks.NETHER_PORTAL
            || block == Blocks.END_PORTAL
            || block == Blocks.END_GATEWAY;
    }

    // ───────────────────────────────────────────────────────────────
    // Discovery
    // ───────────────────────────────────────────────────────────────

    private void processNewDiscovery(BlockPos pos, PortalType type, String dimensionId) {
        if (autoMarkRange.get() <= 0 || mc.player == null) return;
        if (type != PortalType.NETHER) return;
        if (dimensionId.equals("minecraft:overworld") && !trackOverworld.get()) return;
        if (pos.getSquaredDistance(mc.player.getPos()) > (double) autoMarkRange.get() * autoMarkRange.get()) return;
        if (exclusionTimer > 0 && entryPortalPos != null
                && pos.getSquaredDistance(entryPortalPos) <= ENTRY_EXCLUSION_RADIUS_SQ) return;

        if (createdPortals.add(pos)) portalsDirty = true;
    }

    // ───────────────────────────────────────────────────────────────
    // Grouping
    // ───────────────────────────────────────────────────────────────

    /**
     * BFS over all tracked portal blocks to group adjacent same-type blocks
     * into PortalStructure bounding boxes. Only runs when portalsDirty is true.
     */
    private void groupPortals() {
        List<PortalStructure> newStructures = new ArrayList<>();
        Set<BlockPos>         visited       = new HashSet<>();

        for (BlockPos startPos : portals.keySet()) {
            if (visited.contains(startPos)) continue;

            PortalType type = portals.get(startPos);
            if (type == null) continue;

            Set<BlockPos>   component    = new HashSet<>();
            Queue<BlockPos> queue        = new LinkedList<>();
            Box             structureBox = new Box(startPos);
            boolean         isCreated    = false;

            queue.add(startPos);
            visited.add(startPos);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                component.add(current);
                if (createdPortals.contains(current)) isCreated = true;

                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.offset(dir);
                    if (portals.get(neighbor) == type && visited.add(neighbor)) {
                        queue.add(neighbor);
                        structureBox = structureBox.union(new Box(neighbor));
                    }
                }
            }

            if (component.isEmpty()) continue;

            newStructures.add(new PortalStructure(structureBox.expand(0.02), isCreated, type));

            if (isCreated && showCreatedCount.get()) {
                String id = String.format("%s_%.1f_%.1f_%.1f",
                    type.name(), structureBox.minX, structureBox.minY, structureBox.minZ);
                if (notifiedStructures.add(id)) {
                    totalCreated++;
                    sendMessage("§aCreated Portal #" + totalCreated + " §7(" + type.getDisplayName() + ")");
                }
            }
        }

        portalStructures.clear();
        portalStructures.addAll(newStructures);
    }

    // ───────────────────────────────────────────────────────────────
    // Cleanup
    // ───────────────────────────────────────────────────────────────

    private void cleanupDistantPortals() {
        if (mc.player == null) return;
        BlockPos playerPos  = mc.player.getBlockPos();
        int      renderDist = range.get() * 16;
        double   distSq     = (double) (renderDist + 64) * (renderDist + 64);

        boolean removed = portals.entrySet().removeIf(e -> playerPos.getSquaredDistance(e.getKey()) > distSq);
        if (removed) portalsDirty = true;
    }

    private void cleanupTrackedData() {
        if (mc.player == null) return;
        BlockPos playerPos = mc.player.getBlockPos();
        int      dist      = range.get() * 16 + 32;
        double   distSq    = (double) dist * dist;
        createdPortals.removeIf(pos -> pos.getSquaredDistance(playerPos) > distSq);
    }

    private void cleanupDistantChunks(int centerChunkX, int centerChunkZ) {
        int r   = range.get();
        int rSq = r * r;
        scannedChunks.removeIf(cp -> {
            int dx = cp.x - centerChunkX;
            int dz = cp.z - centerChunkZ;
            return dx * dx + dz * dz > rSq;
        });
    }

    // ───────────────────────────────────────────────────────────────
    // Block Update Event
    // ───────────────────────────────────────────────────────────────

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || mc.world == null) return;

        double threshold = range.get() * 16.0 + 32;
        if (event.pos.getSquaredDistance(mc.player.getPos()) > threshold * threshold) return;

        boolean wasPortal = isTrackedPortalBlock(event.oldState.getBlock());
        boolean isPortal  = isTrackedPortalBlock(event.newState.getBlock());
        if (!wasPortal && !isPortal) return;

        ChunkPos cp = new ChunkPos(event.pos);
        dirtyChunks.add(cp);
        scannedChunks.remove(cp);

        if (!isPortal) {
            portals.remove(event.pos);
            portalsDirty = true;
        }
    }

    // ───────────────────────────────────────────────────────────────
    // Rendering
    // ───────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        for (PortalStructure structure : portalStructures) {
            if (onlyShowCreated.get() && !structure.isCreated) continue;

            Color color = getColor(structure.type);
            if (color == null) continue;

            event.renderer.box(structure.boundingBox, color, color, shapeMode.get(), 0);
            if (showBeam.get()) renderBeam(event, structure.boundingBox, color);
        }
    }

    private void renderBeam(Render3DEvent event, Box anchorBox, Color color) {
        double beamSize = beamWidth.get() / 100.0;
        double centerX  = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double centerZ  = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int    worldBot = mc.world.getBottomY();
        int    worldTop = worldBot + mc.world.getHeight();

        event.renderer.box(
            new Box(centerX - beamSize, worldBot, centerZ - beamSize,
                    centerX + beamSize, worldTop, centerZ + beamSize),
            color, color, ShapeMode.Both, 0
        );
    }

    // ───────────────────────────────────────────────────────────────
    // Color
    // ───────────────────────────────────────────────────────────────

    /**
     * When dynamic colors are on, each type uses a distinct hue offset
     * (Nether 0°, End 120°, Gateway 240°) so types stay visually distinguishable.
     */
    private Color getColor(PortalType type) {
        if (dynamicColors.get()) {
            float baseHue = switch (type) {
                case NETHER      -> 0f;
                case END_PORTAL  -> 0.333f;
                case END_GATEWAY -> 0.667f;
            };
            float hue = (baseHue + (System.currentTimeMillis() % 3000) / 3000f) % 1f;
            int   rgb = java.awt.Color.HSBtoRGB(hue, 0.8f, 1.0f);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 100);
        }
        return switch (type) {
            case NETHER      -> netherColor.get();
            case END_PORTAL  -> endPortalColor.get();
            case END_GATEWAY -> endGatewayColor.get();
        };
    }

    // ───────────────────────────────────────────────────────────────
    // Setting Handlers
    // ───────────────────────────────────────────────────────────────

    private void handleReset(boolean value) {
        if (!value) return;
        int old = totalCreated;
        totalCreated = 0;
        createdPortals.clear();
        notifiedStructures.clear();
        sessionStartTime = System.currentTimeMillis();
        portalsDirty     = true;
        info("Session cleared. Reset " + old + " created portal" + (old == 1 ? "" : "s") + ".");
        resetButton.set(false);
    }

    // ───────────────────────────────────────────────────────────────
    // Public API
    // ───────────────────────────────────────────────────────────────

    /** Used by PortalGuiMixin to check whether screen-opening in portals is allowed. */
    public boolean isPortalGuiEnabled() { return isActive(); }

    public int getTotalPortals()         { return portalStructures.size(); }
    public int getTotalCreated()         { return totalCreated; }

    /** Called by chunk-load mixins to force a re-scan of a specific chunk. */
    public void markChunkDirty(ChunkPos chunkPos) {
        if (chunkPos == null) return;
        dirtyChunks.add(chunkPos);
        scannedChunks.remove(chunkPos);
    }

    // ───────────────────────────────────────────────────────────────
    // Utilities
    // ───────────────────────────────────────────────────────────────

    private void sendMessage(String message) {
        long now  = System.currentTimeMillis();
        Long last = messageCooldowns.get(message);
        if (last == null || now - last > MESSAGE_COOLDOWN_MS) {
            super.info(message);
            messageCooldowns.put(message, now);
        }
    }

    private String getDimensionName(String id) {
        return switch (id) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:overworld"  -> "Overworld";
            case "minecraft:the_end"    -> "End";
            default -> id;
        };
    }

    // ───────────────────────────────────────────────────────────────
    // Inner Types
    // ───────────────────────────────────────────────────────────────

    private enum PortalType {
        NETHER("Nether Portal"),
        END_PORTAL("End Portal"),
        END_GATEWAY("End Gateway");

        private final String displayName;
        PortalType(String displayName) { this.displayName = displayName; }
        public String getDisplayName()  { return displayName; }
    }

    private static class PortalStructure {
        final Box        boundingBox;
        final boolean    isCreated;
        final PortalType type;

        PortalStructure(Box boundingBox, boolean isCreated, PortalType type) {
            this.boundingBox = boundingBox;
            this.isCreated   = isCreated;
            this.type        = type;
        }
    }
}