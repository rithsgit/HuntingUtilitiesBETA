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
import net.minecraft.block.BlockState;
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

    // ─────────────────────────── State ───────────────────────────

    private final Map<BlockPos, PortalType> portals         = new ConcurrentHashMap<>();
    private final Set<BlockPos>             createdPortals  = ConcurrentHashMap.newKeySet();
    private final List<PortalStructure>     portalStructures = new CopyOnWriteArrayList<>();

    private final Set<String>          notifiedStructures = ConcurrentHashMap.newKeySet();
    private final Map<String, Long>    messageCooldowns   = new ConcurrentHashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 2000;

    private final Set<ChunkPos>  scannedChunks = new HashSet<>();
    // FIX: dirtyChunks is only ever touched from the game thread; plain HashSet is fine.
    private final Set<ChunkPos>  dirtyChunks   = new HashSet<>();

    /**
     * FIX: Dirty flag — set whenever portals changes. groupPortals() only
     * rebuilds when this is true, eliminating the per-tick full BFS.
     */
    private volatile boolean portalsDirty = false;

    private int  totalCreated             = 0;
    private String lastDimension          = "";
    private int  dimensionChangeCooldown  = 0;
    private int  exclusionTimer           = 0;
    private static final int COOLDOWN_TICKS = 200;
    private BlockPos entryPortalPos       = null;
    private static final int    ENTRY_PORTAL_EXCLUSION_RADIUS    = 5;
    private static final double ENTRY_PORTAL_EXCLUSION_RADIUS_SQ =
        ENTRY_PORTAL_EXCLUSION_RADIUS * ENTRY_PORTAL_EXCLUSION_RADIUS;

    private boolean manuallyActivated  = false;
    private long    sessionStartTime   = 0;
    private int     minSessionDuration = 0;

    /** FIX: Throttle timers — cleanup and structure grouping run every N ticks. */
    private static final int CLEANUP_INTERVAL    = 60;
    private static final int STRUCTURE_INTERVAL  = 5;
    private int cleanupTimer   = 0;
    private int structureTimer = 0;

    // ─────────────────────────── Setting Groups ───────────────────────────

    private final SettingGroup sgGeneral      = settings.getDefaultGroup();
    private final SettingGroup sgNetherPortals = settings.createGroup("Nether Portals");
    private final SettingGroup sgEndDimension  = settings.createGroup("End Dimension");
    private final SettingGroup sgRender        = settings.createGroup("Render");

    // ── General ──
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Portal detection range in chunks.")
        .defaultValue(32)
        .min(16)
        .max(64)
        .sliderMin(16)
        .sliderMax(64)
        .build()
    );

    private final Setting<Boolean> dynamicColors = sgGeneral.add(new BoolSetting.Builder()
        .name("dynamic-colors")
        .description("Animated per-type hue-shifted colors for portals.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> autoMarkRange = sgGeneral.add(new IntSetting.Builder()
        .name("auto-mark-range")
        .description("Auto-mark portals within this range (blocks) as created by you.")
        .defaultValue(10)
        .min(0)
        .max(50)
        .sliderMin(0)
        .sliderMax(50)
        .build()
    );

    private final Setting<Boolean> showCreatedCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-created-count")
        .description("Show how many portals you've created in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyShowCreated = sgGeneral.add(new BoolSetting.Builder()
        .name("only-show-created")
        .description("Only highlight portals you've created.")
        .defaultValue(false)
        .build()
    );

    // FIX: portalGui now triggers the actual mixin via a public getter rather
    // than being dead code. The mixin reads isPortalGuiEnabled().
    public final Setting<Boolean> portalGui = sgGeneral.add(new BoolSetting.Builder()
        .name("portal-gui")
        .description("Allows opening screens while inside a portal.")
        .defaultValue(true)
        .build()
    );

    // FIX: trackOverworld — previously processNewDiscovery silently ignored the
    // Overworld entirely with no setting to override it.
    private final Setting<Boolean> trackOverworld = sgGeneral.add(new BoolSetting.Builder()
        .name("track-overworld")
        .description("Also auto-mark portals found in the Overworld.")
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
        .min(5)
        .max(50)
        .sliderMin(5)
        .sliderMax(50)
        .visible(showBeam::get)
        .build()
    );

    private Setting<Boolean> resetButton;

    {
        resetButton = sgGeneral.add(new BoolSetting.Builder()
            .name("reset")
            .description("Resets the current session and clears created portals.")
            .defaultValue(false)
            .onChanged(v -> {
                if (v) {
                    int oldCount = totalCreated;
                    totalCreated = 0;
                    createdPortals.clear();
                    notifiedStructures.clear();
                    sessionStartTime = System.currentTimeMillis();
                    portalsDirty = true;
                    info("Session cleared. Reset " + oldCount + " created portal"
                        + (oldCount == 1 ? "" : "s") + ".");
                    resetButton.set(false);
                }
            })
            .build()
        );
    }

    // ── Nether Portals ──
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

    // ── End Dimension ──
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

    // ── Render ──
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Render style for portal highlights.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    // ─────────────────────────── Constructor ───────────────────────────

    public PortalTracker() {
        super(HuntingUtilities.CATEGORY, "portal-tracker", "Automatically tracks and highlights portals.");
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private void sendMessage(String message) {
        long now = System.currentTimeMillis();
        Long last = messageCooldowns.get(message);
        if (last == null || now - last > MESSAGE_COOLDOWN_MS) {
            super.info(message);
            messageCooldowns.put(message, now);
        }
    }

    /** Public accessor for the PortalGuiMixin. */
    public boolean isPortalGuiEnabled() {
        return portalGui.get();
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    @Override
    public void onActivate() {
        clearAllState();
        manuallyActivated = false;
        sessionStartTime  = System.currentTimeMillis();

        if (mc.player != null && mc.world != null && mc.world.getRegistryKey() != null) {
            sendMessage("§bPortal Tracker activated");
            lastDimension = mc.world.getRegistryKey().getValue().toString();
        }
    }

    @Override
    public void onDeactivate() {
        if (manuallyActivated && mc.player != null && mc.world != null) {
            long duration = System.currentTimeMillis() - sessionStartTime;
            if (duration >= minSessionDuration) {
                sendMessage("§7Session: §f" + portalStructures.size()
                    + " §7Portals discovered §8| §a" + totalCreated + " §7Created");
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
        portalsDirty   = false;
        manuallyActivated = false;
        sessionStartTime  = 0;
    }

    // ─────────────────────────── Tick ───────────────────────────

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

        try {
            String currDim = mc.world.getRegistryKey().getValue().toString();
            if (!currDim.equals(lastDimension)) {
                dimensionChangeCooldown = 40; // brief settle period
                exclusionTimer          = COOLDOWN_TICKS;
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
                    (currDim.equals("minecraft:the_nether")  && scanNetherPortals.get()) ||
                    (currDim.equals("minecraft:overworld")    && scanNetherPortals.get()) ||
                    (currDim.equals("minecraft:the_end")      && (scanEndPortals.get() || scanEndGateways.get()));
                if (notify) sendMessage("§7Entered " + getDimensionName(currDim) + " — scanning started");
                return;
            }
        } catch (Exception ignored) { return; }

        // FIX: Flush dirty chunks first so re-scans happen this tick.
        if (!dirtyChunks.isEmpty()) {
            scannedChunks.removeAll(dirtyChunks);
            dirtyChunks.clear();
        }

        BlockPos playerPos   = mc.player.getBlockPos();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        scanBlockEntities(centerChunkX, centerChunkZ);
        scanNewChunks(centerChunkX, centerChunkZ);

        // FIX: groupPortals only rebuilds when portal data actually changed.
        if (portalsDirty && ++structureTimer >= STRUCTURE_INTERVAL) {
            structureTimer = 0;
            portalsDirty   = false;
            groupPortals();
        }

        // FIX: Cleanup on its own throttled timer, not tied to groupPortals.
        if (++cleanupTimer >= CLEANUP_INTERVAL) {
            cleanupTimer = 0;
            cleanupDistantPortals();
            cleanupTrackedData();
            cleanupDistantChunks(centerChunkX, centerChunkZ);
        }

        if (!manuallyActivated) manuallyActivated = true;
    }

    // ─────────────────────────── Scanning ───────────────────────────

    /**
     * FIX: scanBlockEntities previously used range (chunks) as a raw block
     * distance, scanning up to range² chunks every tick with no guard.
     * Now uses a proper block-distance maxDistSq derived from range * 16.
     * Block entities are only checked once per chunk load via the scannedChunks
     * guard — same pattern as scanNewChunks.
     */
    private void scanBlockEntities(int centerChunkX, int centerChunkZ) {
        int chunkRange = range.get();
        int chunkRangeSq = chunkRange * chunkRange;
        int maxBlockDist = chunkRange * 16;
        int maxDistSq    = maxBlockDist * maxBlockDist;
        String dimId = mc.world.getRegistryKey().getValue().toString();
        BlockPos playerPos = mc.player.getBlockPos();

        for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
            for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                int dx = cx - centerChunkX;
                int dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > chunkRangeSq) continue;

                // Only scan block entities for chunks not already fully scanned.
                ChunkPos cp = new ChunkPos(cx, cz);
                if (scannedChunks.contains(cp)) continue;

                WorldChunk chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getPos();
                    if (pos.getSquaredDistance(playerPos) > maxDistSq) continue;

                    PortalType type = null;
                    if (scanEndGateways.get() && be instanceof EndGatewayBlockEntity) {
                        type = PortalType.END_GATEWAY;
                    } else if (scanEndPortals.get() && be instanceof EndPortalBlockEntity) {
                        type = PortalType.END_PORTAL;
                    }

                    if (type != null && !portals.containsKey(pos)) {
                        portals.put(pos, type);
                        portalsDirty = true;
                        processNewDiscovery(pos, type, dimId);
                    }
                }
            }
        }
    }

    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r    = range.get();
        int rSq  = r * r;
        int limit = 10; // chunks per tick cap

        int chunksScanned = 0;

        outer:
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                for (int side = 0; side < 2; side++) {
                    int z = (side == 0) ? -d : d;
                    if (processChunk(centerChunkX + x, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) {
                        if (++chunksScanned >= limit) break outer;
                    }
                }
            }
            for (int z = -d + 1; z < d; z++) {
                for (int side = 0; side < 2; side++) {
                    int x = (side == 0) ? -d : d;
                    if (processChunk(centerChunkX + x, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) {
                        if (++chunksScanned >= limit) break outer;
                    }
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

        if (mc.world.getChunkManager().isChunkLoaded(cx, cz)) {
            scanChunk(mc.world.getChunk(cx, cz));
            scannedChunks.add(cp);
            return true;
        }
        return false;
    }

    /**
     * FIX: scanChunk now only tracks NETHER_PORTAL via the block-state scanner.
     * END_PORTAL and END_GATEWAY are block entities and are handled exclusively
     * in scanBlockEntities — removing the duplicate-entry source.
     */
    private void scanChunk(WorldChunk chunk) {
        String dimId = mc.world.getRegistryKey().getValue().toString();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionMinY = (chunk.getBottomSectionCoord() + i) * 16;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        PortalType found = getPortalTypeBlockOnly(state.getBlock());
                        if (found == null) continue;

                        BlockPos pos = new BlockPos(
                            (chunk.getPos().x << 4) + x,
                            sectionMinY + y,
                            (chunk.getPos().z << 4) + z
                        );
                        if (!portals.containsKey(pos)) {
                            portals.put(pos, found);
                            portalsDirty = true;
                            processNewDiscovery(pos, found, dimId);
                        }
                    }
                }
            }
        }
    }

    /**
     * FIX: Only returns NETHER for block-state scanning.
     * END_PORTAL / END_GATEWAY are exclusively handled as block entities.
     */
    private PortalType getPortalTypeBlockOnly(Block block) {
        if (scanNetherPortals.get() && block == Blocks.NETHER_PORTAL) return PortalType.NETHER;
        return null;
    }

    private void processNewDiscovery(BlockPos pos, PortalType type, String dimensionId) {
        if (autoMarkRange.get() <= 0 || mc.player == null) return;
        if (type != PortalType.NETHER) return;

        // FIX: trackOverworld setting now controls whether Overworld portals are auto-marked.
        boolean isOverworld = dimensionId.equals("minecraft:overworld");
        if (isOverworld && !trackOverworld.get()) return;

        int autoRange = autoMarkRange.get();
        if (pos.getSquaredDistance(mc.player.getPos()) > (double) autoRange * autoRange) return;

        if (exclusionTimer > 0 && entryPortalPos != null
                && pos.getSquaredDistance(entryPortalPos) <= ENTRY_PORTAL_EXCLUSION_RADIUS_SQ) return;

        if (createdPortals.add(pos)) {
            portalsDirty = true;
        }
    }

    // ─────────────────────────── Grouping ───────────────────────────

    /**
     * FIX: Only called when portalsDirty is true (gated in onTick).
     * Full BFS over all portal blocks — same logic as before but no longer
     * runs unconditionally every tick.
     */
    private void groupPortals() {
        List<PortalStructure> newStructures = new ArrayList<>();
        Set<BlockPos>         visited       = new HashSet<>();

        for (BlockPos startPos : portals.keySet()) {
            if (visited.contains(startPos)) continue;

            PortalType type = portals.get(startPos);
            if (type == null) continue;

            Set<BlockPos> component   = new HashSet<>();
            Queue<BlockPos> queue     = new LinkedList<>();
            Box structureBox          = new Box(startPos);
            boolean isCreated         = false;

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

            if (!component.isEmpty()) {
                Box renderBox = structureBox.expand(0.02);
                newStructures.add(new PortalStructure(renderBox, isCreated, type));

                if (isCreated && showCreatedCount.get()) {
                    String structureId = String.format("%s_%.1f_%.1f_%.1f",
                        type.name(), structureBox.minX, structureBox.minY, structureBox.minZ);
                    if (notifiedStructures.add(structureId)) {
                        totalCreated++;
                        sendMessage("§aCreated Portal #" + totalCreated
                            + " §7(" + type.getDisplayName() + ")");
                    }
                }
            }
        }

        portalStructures.clear();
        portalStructures.addAll(newStructures);
    }

    // ─────────────────────────── Cleanup ───────────────────────────

    private void cleanupTrackedData() {
        if (mc.player == null) return;
        BlockPos playerPos    = mc.player.getBlockPos();
        int cleanupDist       = range.get() * 16 + 32;
        double cleanupDistSq  = (double) cleanupDist * cleanupDist;
        createdPortals.removeIf(pos -> pos.getSquaredDistance(playerPos) > cleanupDistSq);
    }

    private void cleanupDistantPortals() {
        if (mc.player == null) return;
        BlockPos playerPos   = mc.player.getBlockPos();
        int renderDist       = range.get() * 16;
        double renderDistSq  = (double) (renderDist + 64) * (renderDist + 64);

        boolean removed = portals.entrySet().removeIf(
            entry -> playerPos.getSquaredDistance(entry.getKey()) > renderDistSq
        );
        if (removed) portalsDirty = true;
    }

    /** FIX: Separate method to prune chunks that have drifted out of range. */
    private void cleanupDistantChunks(int centerChunkX, int centerChunkZ) {
        int r   = range.get();
        int rSq = r * r;
        scannedChunks.removeIf(cp -> {
            int dx = cp.x - centerChunkX;
            int dz = cp.z - centerChunkZ;
            return dx * dx + dz * dz > rSq;
        });
    }

    // ─────────────────────────── Block Updates ───────────────────────────

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || mc.world == null) return;

        BlockPos pos = event.pos;
        double threshold = range.get() * 16.0 + 32;
        if (pos.getSquaredDistance(mc.player.getPos()) > threshold * threshold) return;

        boolean wasPortal = isTrackedPortalBlock(event.oldState.getBlock());
        boolean isPortal  = isTrackedPortalBlock(event.newState.getBlock());

        if (wasPortal || isPortal) {
            ChunkPos cp = new ChunkPos(pos);
            dirtyChunks.add(cp);
            scannedChunks.remove(cp);
            if (!isPortal) {
                portals.remove(pos);
                portalsDirty = true;
            }
        }
    }

    private boolean isTrackedPortalBlock(Block block) {
        return block == Blocks.NETHER_PORTAL
            || block == Blocks.END_PORTAL
            || block == Blocks.END_GATEWAY;
    }

    // ─────────────────────────── Rendering ───────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        for (PortalStructure structure : portalStructures) {
            if (onlyShowCreated.get() && !structure.isCreated) continue;

            Color color = getColor(structure.type);
            if (color == null) continue;

            event.renderer.box(structure.boundingBox, color, color, shapeMode.get(), 0);

            if (showBeam.get()) {
                renderBeam(event, structure.boundingBox, color);
            }
        }
    }

    /**
     * FIX: Beam uses world height bounds, not a hardcoded offset.
     */
    private void renderBeam(Render3DEvent event, Box anchorBox, Color color) {
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

    // ─────────────────────────── Color ───────────────────────────

    /**
     * FIX: dynamicColors now uses per-type hue offsets so portal types remain
     * visually distinct even while animating (Nether 0°, End 120°, Gateway 240°).
     */
    private Color getColor(PortalType type) {
        if (dynamicColors.get()) {
            long  time    = System.currentTimeMillis();
            float baseHue = type == PortalType.NETHER      ? 0f
                          : type == PortalType.END_PORTAL   ? 0.333f
                          : /* END_GATEWAY */                 0.667f;
            float hue = (baseHue + (time % 3000) / 3000f) % 1f;
            int   rgb = java.awt.Color.HSBtoRGB(hue, 0.8f, 1.0f);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 100);
        }
        return switch (type) {
            case NETHER      -> netherColor.get();
            case END_PORTAL  -> endPortalColor.get();
            case END_GATEWAY -> endGatewayColor.get();
        };
    }

    // ─────────────────────────── Public API ───────────────────────────

    public int getTotalPortals()  { return portalStructures.size(); }
    public int getTotalCreated()  { return totalCreated; }

    public void markChunkDirty(ChunkPos chunkPos) {
        if (chunkPos == null) return;
        dirtyChunks.add(chunkPos);
        scannedChunks.remove(chunkPos);
    }

    // ─────────────────────────── Utilities ───────────────────────────

    private String getDimensionName(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:overworld"  -> "Overworld";
            case "minecraft:the_end"    -> "End";
            default -> dimensionId;
        };
    }

    // ─────────────────────────── Types ───────────────────────────

    private enum PortalType {
        NETHER("Nether Portal"),
        END_PORTAL("End Portal"),
        END_GATEWAY("End Gateway");

        private final String name;
        PortalType(String name) { this.name = name; }
        public String getDisplayName() { return name; }
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