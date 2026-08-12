package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.addon.Tim;

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
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public class Tunnelers extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════════════════

    private static final int[][] HORIZONTAL_DIRS = {
        { 1, 0, 0}, {-1, 0, 0},
        { 0, 0, 1}, { 0, 0,-1}
    };

    private static final int  MAX_QUEUE_PER_TICK    = 32;
    private static final int  MAX_BATCHES_PER_FLUSH = 4;
    private static final int  MAX_IN_FLIGHT         = 6;
    private static final int  DRAIN_PER_TICK        = 4;
    private static final long TIME_BUDGET_NS        = 500_000L;

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum HighlightStyle {
        GLOW("Glow"),
        SPECTRAL("Spectral"),
        PULSE("Pulse");

        private final String displayName;
        HighlightStyle(String name) { this.displayName = name; }
        @Override public String toString() { return displayName; }
    }

    public enum TunnelType {
        TUNNEL_1x1,
        OTHER_TUNNEL,
        HOLE,
        LADDER_SHAFT
    }

    public enum ShaftMode {
        Holes,
        LadderShafts,
        Both
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgSpectral = settings.createGroup("Spectral");
    private final SettingGroup sgTunnels  = settings.createGroup("Tunnels");
    private final SettingGroup sgShafts   = settings.createGroup("Shafts");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Scan range in chunks.")
        .defaultValue(8).min(1).sliderMax(32)
        .build());

    private final Setting<Integer> scanDelay = sgGeneral.add(new IntSetting.Builder()
        .name("scan-delay")
        .description("Ticks between out-of-range pruning passes.")
        .defaultValue(40).min(10).sliderMax(200)
        .build());

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<HighlightStyle> highlightStyle = sgGeneral.add(new EnumSetting.Builder<HighlightStyle>()
        .name("highlight-style")
        .description("GLOW renders layered bloom around each box. SPECTRAL renders a crisp outline only. PULSE renders a fading bloom effect.")
        .defaultValue(HighlightStyle.GLOW)
        .build());

    private final Setting<Boolean> fadeWithDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("fade-with-distance")
        .description("Reduces opacity of highlights that are further away.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxRenderBoxes = sgGeneral.add(new IntSetting.Builder()
        .name("max-render-boxes")
        .description("Maximum merged boxes rendered per frame. Lower = better FPS in dense areas.")
        .defaultValue(2000).min(100).sliderMax(8000)
        .build());

    // ── Glow ───────────────────────────────────────────────────────────────────

    private final Setting<Integer> glowLayers = sgGeneral.add(new IntSetting.Builder()
        .name("glow-layers")
        .description("Number of bloom layers rendered around each box.")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> highlightStyle.get() == HighlightStyle.GLOW || highlightStyle.get() == HighlightStyle.PULSE)
        .build());

    private final Setting<Double> glowSpread = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow-spread")
        .description("How far each bloom layer expands outward (in blocks).")
        .defaultValue(0.05).min(0.01).sliderMax(0.2)
        .visible(() -> highlightStyle.get() == HighlightStyle.GLOW || highlightStyle.get() == HighlightStyle.PULSE)
        .build());

    private final Setting<Integer> glowBaseAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("glow-base-alpha")
        .description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(60).min(4).sliderMax(150)
        .visible(() -> highlightStyle.get() == HighlightStyle.GLOW)
        .build());

    // ── Pulse ──────────────────────────────────────────────────────────────────

    private final Setting<Double> pulseSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("pulse-speed")
        .description("Pulse cycle speed. 1.0 = one full fade in/out per second.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMax(3.0)
        .visible(() -> highlightStyle.get() == HighlightStyle.PULSE)
        .build());

    private final Setting<Integer> pulseMinAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("pulse-min-alpha")
        .description("Lowest alpha reached during the pulse (0 = invisible).")
        .defaultValue(15).min(0).max(255).sliderMax(100)
        .visible(() -> highlightStyle.get() == HighlightStyle.PULSE)
        .build());

    private final Setting<Integer> pulseMaxAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("pulse-max-alpha")
        .description("Peak alpha reached during the pulse.")
        .defaultValue(220).min(50).max(255).sliderMax(255)
        .visible(() -> highlightStyle.get() == HighlightStyle.PULSE)
        .build());

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Spectral
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> spectralExpand = sgSpectral.add(new DoubleSetting.Builder()
        .name("expand")
        .description("How much to expand the outline box beyond each tunnel box surface (in blocks).")
        .defaultValue(0.05).min(0.0).sliderMax(0.3)
        .visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL)
        .build());

    private final Setting<Integer> spectralLineAlpha = sgSpectral.add(new IntSetting.Builder()
        .name("line-alpha")
        .description("Opacity of the spectral outline (0-255). Affected by fade-with-distance.")
        .defaultValue(255).min(30).sliderMax(255)
        .visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL)
        .build());

    private final Setting<Integer> spectralFillAlpha = sgSpectral.add(new IntSetting.Builder()
        .name("fill-alpha")
        .description("Alpha of a faint tinted fill drawn inside the outline (0 = lines only).")
        .defaultValue(15).min(0).sliderMax(80)
        .visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL)
        .build());

    private final Setting<Boolean> spectralPulse = sgSpectral.add(new BoolSetting.Builder()
        .name("pulse")
        .description("Pulsate the spectral outline alpha over time, like the vanilla glowing effect.")
        .defaultValue(true)
        .visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL)
        .build());

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Tunnels
    // ═══════════════════════════════════════════════════════════════════════════

    // ── 1x1 Tunnels ────────────────────────────────────────────────────────────

    private final Setting<Boolean> find1x1 = sgTunnels.add(new BoolSetting.Builder()
        .name("1x1")
        .description("Detect 1x1 tunnels.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> min1x1Length = sgTunnels.add(new IntSetting.Builder()
        .name("min-1x1-length")
        .description("Minimum length of a 1x1 tunnel to be rendered.")
        .defaultValue(8).min(1).sliderMax(64)
        .visible(find1x1::get)
        .build());

    private final Setting<SettingColor> color1x1 = sgTunnels.add(new ColorSetting.Builder()
        .name("color-1x1")
        .description("Color for 1x1 tunnels.")
        .defaultValue(new SettingColor(255, 255, 0, 75))
        .visible(find1x1::get)
        .build());

    // ── Other Tunnels (1x2, 2x2, 3x3, 4x4, 5x5) ─────────────────────────────────

    private final Setting<Boolean> findOtherTunnels = sgTunnels.add(new BoolSetting.Builder()
        .name("Other Tunnels")
        .description("Detect 1x2, 2x2, 3x3, 4x4, and 5x5 tunnels.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> minOtherLength = sgTunnels.add(new IntSetting.Builder()
        .name("min-other-length")
        .description("Minimum length for other tunnels to be rendered.")
        .defaultValue(2).min(1).sliderMax(64)
        .visible(findOtherTunnels::get)
        .build());

    private final Setting<SettingColor> colorOtherTunnels = sgTunnels.add(new ColorSetting.Builder()
        .name("color-other")
        .description("Color for 1x2, 2x2, 3x3, 4x4, and 5x5 tunnels.")
        .defaultValue(new SettingColor(255, 200, 0, 75))
        .visible(findOtherTunnels::get)
        .build());

    // ── Fluids in Tunnels ──────────────────────────────────────────────────────

    private final Setting<Boolean> includeWater = sgTunnels.add(new BoolSetting.Builder()
        .name("include-water")
        .description("Detect tunnels even if they contain water instead of air.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includeLava = sgTunnels.add(new BoolSetting.Builder()
        .name("include-lava")
        .description("Detect tunnels even if they contain lava instead of air.")
        .defaultValue(true)
        .build());

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Shafts
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<ShaftMode> shaftMode = sgShafts.add(new EnumSetting.Builder<ShaftMode>()
        .name("shaft-mode")
        .description("Which vertical shaft types to detect: Holes, LadderShafts, or Both.")
        .defaultValue(ShaftMode.Both)
        .build());

    private final Setting<Integer> minHoleHeight = sgShafts.add(new IntSetting.Builder()
        .name("min-hole-height")
        .description("Minimum shaft depth to be detected as a hole.")
        .defaultValue(4).min(2).sliderMax(20)
        .visible(() -> shaftMode.get() == ShaftMode.Holes || shaftMode.get() == ShaftMode.Both)
        .build());

    private final Setting<SettingColor> colorHoles = sgShafts.add(new ColorSetting.Builder()
        .name("color-holes")
        .defaultValue(new SettingColor(0, 255, 255, 75))
        .visible(() -> shaftMode.get() == ShaftMode.Holes || shaftMode.get() == ShaftMode.Both)
        .build());

    private final Setting<Integer> minLadderHeight = sgShafts.add(new IntSetting.Builder()
        .name("min-ladder-height")
        .description("Minimum consecutive ladder blocks to count as a shaft.")
        .defaultValue(4).min(2).sliderMax(20)
        .visible(() -> shaftMode.get() == ShaftMode.LadderShafts || shaftMode.get() == ShaftMode.Both)
        .build());

    private final Setting<SettingColor> colorLadderShafts = sgShafts.add(new ColorSetting.Builder()
        .name("color-ladder-shafts")
        .defaultValue(new SettingColor(0, 255, 0, 75))
        .visible(() -> shaftMode.get() == ShaftMode.LadderShafts || shaftMode.get() == ShaftMode.Both)
        .build());

    // ═══════════════════════════════════════════════════════════════════════════
    // State & Threading
    // ═══════════════════════════════════════════════════════════════════════════

    private final ConcurrentHashMap<BlockPos, TunnelType> locations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Set<BlockPos>> chunkIndex = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ScanResult> pendingResults = new ConcurrentLinkedQueue<>();

    private volatile List<MergedBox> renderSnapshot = Collections.emptyList();
    private final AtomicBoolean mergeScheduled = new AtomicBoolean(false);
    private volatile int snapPX, snapPY, snapPZ;

    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final LinkedHashSet<ChunkPos> snapshotQueue = new LinkedHashSet<>();
    private final Set<ChunkPos> inFlight = ConcurrentHashMap.newKeySet();

    private ExecutorService executor;

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;
    private int pruneTimer = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    public Tunnelers() {
        super(Tim.CATEGORY, "tunnelers", "Highlights player-made tunnels and shafts.");
    }

    @Override
    public void onActivate() {
        clearState();
        if (mc.world != null) lastDimension = mc.world.getRegistryKey().getValue().toString();

        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Tunnelers-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    @Override
    public void onDeactivate() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        clearState();
    }

    private void clearState() {
        locations.clear();
        chunkIndex.clear();
        pendingResults.clear();
        scannedChunks.clear();
        snapshotQueue.clear();
        inFlight.clear();
        renderSnapshot = Collections.emptyList();
        mergeScheduled.set(false);
        pruneTimer = 0;
        dimensionChangeCooldown = 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick & Queue Management
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (dimensionChangeCooldown > 0) {
            dimensionChangeCooldown--;
            return;
        }

        String currDim = mc.world.getRegistryKey().getValue().toString();
        if (!currDim.equals(lastDimension)) {
            lastDimension = currDim;
            dimensionChangeCooldown = 40;
            clearState();
            return;
        }

        if (flushPendingResults()) scheduleMerge();

        if (++pruneTimer >= scanDelay.get()) {
            pruneTimer = 0;
            if (pruneOutOfRange()) scheduleMerge();
        }

        int playerCX = mc.player.getBlockPos().getX() >> 4;
        int playerCZ = mc.player.getBlockPos().getZ() >> 4;
        enqueueNewChunks(playerCX, playerCZ);
        drainSnapshotQueue();
    }

    private void enqueueNewChunks(int centerCX, int centerCZ) {
        int r = range.get();
        int rSq = r * r;
        int added = 0;
        long startTime = System.nanoTime();

        outer:
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                if (tryEnqueue(centerCX + x, centerCZ - d, rSq, centerCX, centerCZ)) added++;
                if (added >= MAX_QUEUE_PER_TICK || System.nanoTime() - startTime > TIME_BUDGET_NS) break outer;

                if (d != 0) {
                    if (tryEnqueue(centerCX + x, centerCZ + d, rSq, centerCX, centerCZ)) added++;
                    if (added >= MAX_QUEUE_PER_TICK || System.nanoTime() - startTime > TIME_BUDGET_NS) break outer;
                }
            }
            for (int z = -d + 1; z < d; z++) {
                if (tryEnqueue(centerCX - d, centerCZ + z, rSq, centerCX, centerCZ)) added++;
                if (added >= MAX_QUEUE_PER_TICK || System.nanoTime() - startTime > TIME_BUDGET_NS) break outer;

                if (d != 0) {
                    if (tryEnqueue(centerCX + d, centerCZ + z, rSq, centerCX, centerCZ)) added++;
                    if (added >= MAX_QUEUE_PER_TICK || System.nanoTime() - startTime > TIME_BUDGET_NS) break outer;
                }
            }
        }
    }

    private boolean tryEnqueue(int cx, int cz, int rSq, int centerCX, int centerCZ) {
        int dx = cx - centerCX, dz = cz - centerCZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp) || inFlight.contains(cp) || snapshotQueue.contains(cp)) return false;
        if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) return false;

        return snapshotQueue.add(cp);
    }

    private void drainSnapshotQueue() {
        for (int i = 0; i < DRAIN_PER_TICK; i++) {
            if (inFlight.size() >= MAX_IN_FLIGHT || snapshotQueue.isEmpty()) break;

            Iterator<ChunkPos> it = snapshotQueue.iterator();
            ChunkPos cp = it.next();
            it.remove();

            if (!mc.world.getChunkManager().isChunkLoaded(cp.x, cp.z)) continue;
            WorldChunk chunk = mc.world.getChunk(cp.x, cp.z);
            if (chunk == null) continue;

            inFlight.add(cp);

            final ScanConfig config = createScanConfig();
            final int bottomCoord = config.minY >> 4;

            executor.submit(() -> {
                try {
                    BlockState[][] snapshot = snapshotChunk(chunk);
                    Map<BlockPos, TunnelType> results = scanSnapshot(cp, snapshot, bottomCoord, config);
                    pendingResults.add(new ScanResult(cp, results));
                } finally {
                    inFlight.remove(cp);
                }
            });
        }
    }

    private boolean flushPendingResults() {
        ScanResult batch;
        int n = 0;

        while (n < MAX_BATCHES_PER_FLUSH && (batch = pendingResults.poll()) != null) {
            scannedChunks.add(batch.chunkPos);
            Set<BlockPos> index = chunkIndex.computeIfAbsent(batch.chunkPos, k -> ConcurrentHashMap.newKeySet());

            for (Map.Entry<BlockPos, TunnelType> e : batch.results.entrySet()) {
                locations.put(e.getKey(), e.getValue());
                index.add(e.getKey());
            }
            n++;
        }
        return n > 0;
    }

    private boolean pruneOutOfRange() {
        if (mc.player == null) return false;

        int centerCX = mc.player.getBlockPos().getX() >> 4;
        int centerCZ = mc.player.getBlockPos().getZ() >> 4;
        int rSq = range.get() * range.get();
        boolean evicted = false;

        Iterator<ChunkPos> it = scannedChunks.iterator();
        while (it.hasNext()) {
            ChunkPos cp = it.next();
            int dx = cp.x - centerCX, dz = cp.z - centerCZ;
            if (dx * dx + dz * dz > rSq) {
                evictChunk(cp);
                it.remove();
                evicted = true;
            }
        }
        return evicted;
    }

    private void evictChunk(ChunkPos cp) {
        Set<BlockPos> idx = chunkIndex.remove(cp);
        if (idx != null) idx.forEach(locations::remove);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Merge Scheduling & Greedy Meshing
    // ═══════════════════════════════════════════════════════════════════════════

    private void scheduleMerge() {
        if (!mergeScheduled.compareAndSet(false, true)) return;

        snapPX = mc.player.getBlockPos().getX();
        snapPY = mc.player.getBlockPos().getY();
        snapPZ = mc.player.getBlockPos().getZ();

        final Map<BlockPos, TunnelType> locSnapshot = new HashMap<>(locations);
        final int px = snapPX, py = snapPY, pz = snapPZ;
        final double maxDistSq = (double)(range.get() * 16) * (range.get() * 16);

        final int min1 = min1x1Length.get();
        final int minOther = minOtherLength.get();

        executor.submit(() -> {
            try {
                renderSnapshot = buildMergedBoxes(locSnapshot, px, py, pz, maxDistSq, min1, minOther);
            } finally {
                mergeScheduled.set(false);
            }
        });
    }

    private static List<MergedBox> buildMergedBoxes(
            Map<BlockPos, TunnelType> locs, int px, int py, int pz, double maxDistSq,
            int min1, int minOther) {

        if (locs.isEmpty()) return Collections.emptyList();

        EnumMap<TunnelType, Set<Long>> remaining = new EnumMap<>(TunnelType.class);
        EnumMap<TunnelType, List<int[]>> coordsByType = new EnumMap<>(TunnelType.class);

        for (TunnelType t : TunnelType.values()) {
            remaining.put(t, new HashSet<>());
            coordsByType.put(t, new ArrayList<>());
        }

        for (Map.Entry<BlockPos, TunnelType> e : locs.entrySet()) {
            BlockPos p = e.getKey();
            TunnelType t = e.getValue();
            remaining.get(t).add(pack(p.getX(), p.getY(), p.getZ()));
            coordsByType.get(t).add(new int[]{ p.getX(), p.getY(), p.getZ() });
        }

        // Filter lengths
        filterTunnelTypeByLength(TunnelType.TUNNEL_1x1, min1, coordsByType, remaining);
        filterTunnelTypeByLength(TunnelType.OTHER_TUNNEL, minOther, coordsByType, remaining);

        List<MergedBox> boxes = new ArrayList<>();

        boxes.addAll(mergeBlockSet(remaining.get(TunnelType.TUNNEL_1x1), coordsByType.get(TunnelType.TUNNEL_1x1), px, py, pz, maxDistSq, TunnelType.TUNNEL_1x1));
        boxes.addAll(mergeBlockSet(remaining.get(TunnelType.OTHER_TUNNEL), coordsByType.get(TunnelType.OTHER_TUNNEL), px, py, pz, maxDistSq, TunnelType.OTHER_TUNNEL));
        boxes.addAll(mergeBlockSet(remaining.get(TunnelType.HOLE), coordsByType.get(TunnelType.HOLE), px, py, pz, maxDistSq, TunnelType.HOLE));
        boxes.addAll(mergeBlockSet(remaining.get(TunnelType.LADDER_SHAFT), coordsByType.get(TunnelType.LADDER_SHAFT), px, py, pz, maxDistSq, TunnelType.LADDER_SHAFT));

        boxes.sort(Comparator.comparingDouble(b -> b.distSq));
        return boxes;
    }

    private static List<MergedBox> mergeBlockSet(
            Set<Long> blocksToMerge, List<int[]> coordList,
            int px, int py, int pz, double maxDistSq, TunnelType type) {

        List<MergedBox> boxes = new ArrayList<>();
        if (blocksToMerge.isEmpty() || coordList.isEmpty()) return boxes;

        Set<Long> rem = new HashSet<>(blocksToMerge);

        for (int[] origin : coordList) {
            int ox = origin[0], oy = origin[1], oz = origin[2];
            if (!rem.contains(pack(ox, oy, oz))) continue;

            int x2 = ox;
            while (rem.contains(pack(x2 + 1, oy, oz))) x2++;

            int z2 = oz;
            while (canExtendZ(rem, ox, x2, oy, z2 + 1)) z2++;

            int y2 = oy;
            while (canExtendY(rem, ox, x2, y2 + 1, oz, z2)) y2++;

            for (int x = ox; x <= x2; x++)
                for (int y = oy; y <= y2; y++)
                    for (int z = oz; z <= z2; z++)
                        rem.remove(pack(x, y, z));

            double nearestX = Math.max(ox, Math.min(px, x2));
            double nearestY = Math.max(oy, Math.min(py, y2));
            double nearestZ = Math.max(oz, Math.min(pz, z2));

            double ddx = nearestX - px, ddy = nearestY - py, ddz = nearestZ - pz;
            double distSq = Math.min(ddx * ddx + ddy * ddy + ddz * ddz, maxDistSq);

            boxes.add(new MergedBox(ox, oy, oz, x2 + 1, y2 + 1, z2 + 1, type, distSq));
        }

        return boxes;
    }

    private static void filterTunnelTypeByLength(
            TunnelType type, int minLength,
            EnumMap<TunnelType, List<int[]>> coordsByType,
            EnumMap<TunnelType, Set<Long>> remaining) {

        if (minLength <= 1) return;

        List<int[]> coords = coordsByType.get(type);
        if (coords == null || coords.isEmpty()) return;

        Set<Long> allBlocks = new HashSet<>(coords.size());
        for (int[] c : coords) allBlocks.add(pack(c[0], c[1], c[2]));

        Set<Long> blocksToKeep = new HashSet<>();
        Set<Long> visited = new HashSet<>();

        for (int[] startCoord : coords) {
            long startKey = pack(startCoord[0], startCoord[1], startCoord[2]);
            if (visited.contains(startKey)) continue;

            List<long[]> component = new ArrayList<>();
            Queue<long[]> queue = new LinkedList<>();
            queue.add(new long[]{ startCoord[0], startCoord[1], startCoord[2], startKey });
            visited.add(startKey);

            while (!queue.isEmpty()) {
                long[] cur = queue.poll();
                component.add(cur);
                int cx = (int) cur[0], cy = (int) cur[1], cz = (int) cur[2];

                for (int[] d : HORIZONTAL_DIRS) {
                    int nx = cx + d[0], ny = cy + d[1], nz = cz + d[2];
                    long nk = pack(nx, ny, nz);
                    if (allBlocks.contains(nk) && visited.add(nk)) {
                        queue.add(new long[]{ nx, ny, nz, nk });
                    }
                }
            }

            if (component.size() >= minLength) {
                for (long[] entry : component) blocksToKeep.add(entry[3]);
            }
        }

        coords.removeIf(c -> !blocksToKeep.contains(pack(c[0], c[1], c[2])));
        remaining.put(type, blocksToKeep);
    }

    private static boolean canExtendZ(Set<Long> rem, int ox, int x2, int y, int z) {
        for (int x = ox; x <= x2; x++)
            if (!rem.contains(pack(x, y, z))) return false;
        return true;
    }

    private static boolean canExtendY(Set<Long> rem, int ox, int x2, int y, int oz, int z2) {
        for (int x = ox; x <= x2; x++)
            for (int z = oz; z <= z2; z++)
                if (!rem.contains(pack(x, y, z))) return false;
        return true;
    }

    private static long pack(int x, int y, int z) {
        return ((long)(x + 33_554_432) << 38) | ((long)(y + 2_048) << 26) | (z + 33_554_432);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Scanning Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private ScanConfig createScanConfig() {
        boolean doHoles = shaftMode.get() == ShaftMode.Holes || shaftMode.get() == ShaftMode.Both;
        boolean doLadders = shaftMode.get() == ShaftMode.LadderShafts || shaftMode.get() == ShaftMode.Both;
        boolean ft = findOtherTunnels.get();

        return new ScanConfig(
            find1x1.get(),
            ft, ft, ft,
            doHoles, doLadders,
            minHoleHeight.get(), minLadderHeight.get(),
            mc.world.getBottomY(), mc.world.getBottomY() + mc.world.getHeight(),
            includeWater.get(), includeLava.get()
        );
    }

    private BlockState[][] snapshotChunk(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        BlockState[][] out = new BlockState[sections.length][];

        for (int si = 0; si < sections.length; si++) {
            ChunkSection sec = sections[si];
            if (sec == null || sec.isEmpty()) continue;

            BlockState[] data = new BlockState[16 * 16 * 16];
            for (int lx = 0; lx < 16; lx++)
                for (int ly = 0; ly < 16; ly++)
                    for (int lz = 0; lz < 16; lz++)
                        data[lx + lz * 16 + ly * 256] = sec.getBlockState(lx, ly, lz);
            out[si] = data;
        }
        return out;
    }

    private Map<BlockPos, TunnelType> scanSnapshot(ChunkPos cp, BlockState[][] snapshot, int bottomCoord, ScanConfig config) {
        Map<BlockPos, TunnelType> results = new HashMap<>();
        int baseX = cp.x << 4, baseZ = cp.z << 4;
        ScanContext ctx = new ScanContext(snapshot, bottomCoord, config.minY, config.maxY,
            baseX, baseZ, config.includeWater, config.includeLava);

        for (int si = 0; si < snapshot.length; si++) {
            if (snapshot[si] == null) continue;
            int sMinY = (bottomCoord + si) << 4, sMaxY = sMinY + 16;
            if (sMaxY <= config.minY || sMinY >= config.maxY) continue;

            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    int wy = sMinY + ly;
                    if (wy < config.minY || wy >= config.maxY) continue;
                    for (int lz = 0; lz < 16; lz++) {
                        classifyBlock(baseX + lx, wy, baseZ + lz, ctx, config, results);
                    }
                }
            }
        }
        return results;
    }

    private void classifyBlock(int wx, int wy, int wz, ScanContext ctx, ScanConfig config, Map<BlockPos, TunnelType> results) {
        // Holes
        if (config.doHoles && isHole(wx, wy, wz, ctx, config.holeDepth)) {
            for (int i = 0; i < config.holeDepth; i++) results.put(new BlockPos(wx, wy - i, wz), TunnelType.HOLE);
            return;
        }

        // Ladder shafts
        if (config.doLadder && isLadderShaft(wx, wy, wz, ctx, config.ladderMin)) {
            for (int i = 0; i < config.ladderMin; i++) results.put(new BlockPos(wx, wy + i, wz), TunnelType.LADDER_SHAFT);
        }

        // 1x1 Tunnels
        if (config.do1x1 && is1x1Tunnel(wx, wy, wz, ctx)) {
            results.put(new BlockPos(wx, wy + 1, wz), TunnelType.TUNNEL_1x1);
        }

        // 1x1 All Four Walls
        if (config.do1x1 && is1x1AllFourWalls(wx, wy, wz, ctx)) {
            results.put(new BlockPos(wx, wy + 1, wz), TunnelType.TUNNEL_1x1);
        }

        // 1x2 Tunnels
        if (config.do1x2 && is1x2Tunnel(wx, wy, wz, ctx)) {
            results.put(new BlockPos(wx, wy + 1, wz), TunnelType.OTHER_TUNNEL);
            results.put(new BlockPos(wx, wy + 2, wz), TunnelType.OTHER_TUNNEL);
        }

        // 2x2 Tunnels
        if (config.do2x2 && is2x2Tunnel(wx, wy, wz, ctx)) {
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 1; dy <= 2; dy++) {
                    for (int dz = 0; dz < 2; dz++) {
                        results.put(new BlockPos(wx + dx, wy + dy, wz + dz), TunnelType.OTHER_TUNNEL);
                    }
                }
            }
        }

        // Abnormal Tunnels (3x3, 4x4, 5x5)
        if (config.doAbnormal) {
            int sz = getAbnormalTunnelSize(wx, wy, wz, ctx);
            if (sz > 0) {
                for (int dx = 0; dx < sz; dx++) {
                    for (int dy = 1; dy <= sz; dy++) {
                        for (int dz = 0; dz < sz; dz++) {
                            results.put(new BlockPos(wx + dx, wy + dy, wz + dz), TunnelType.OTHER_TUNNEL);
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Block Tests
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isHole(int x, int y, int z, ScanContext ctx, int depth) {
        if (!ctx.isAir(x, y, z)) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && !ctx.isSolid(x + dx, y, z + dz)) return false;
            }
        }
        for (int i = 1; i < depth; i++) {
            int sy = y - i;
            if (!ctx.isAir(x,sy,z) || !ctx.isSolid(x-1,sy,z) || !ctx.isSolid(x+1,sy,z)
                    || !ctx.isSolid(x,sy,z-1) || !ctx.isSolid(x,sy,z+1)) return false;
        }
        return true;
    }

    private boolean is1x1Tunnel(int x, int y, int z, ScanContext ctx) {
        if (!ctx.isSolid(x, y, z) || !ctx.isTunnelInterior(x, y + 1, z) || !ctx.isSolid(x, y + 2, z)) return false;

        boolean northSolid = ctx.isSolid(x, y + 1, z - 1);
        boolean southSolid = ctx.isSolid(x, y + 1, z + 1);
        boolean eastSolid  = ctx.isSolid(x + 1, y + 1, z);
        boolean westSolid  = ctx.isSolid(x - 1, y + 1, z);

        boolean neSolid = ctx.isSolid(x + 1, y + 1, z - 1);
        boolean nwSolid = ctx.isSolid(x - 1, y + 1, z - 1);
        boolean seSolid = ctx.isSolid(x + 1, y + 1, z + 1);
        boolean swSolid = ctx.isSolid(x - 1, y + 1, z + 1);

        int solidCardinal = (northSolid ? 1 : 0) + (southSolid ? 1 : 0) + (eastSolid ? 1 : 0) + (westSolid ? 1 : 0);

        if (solidCardinal == 3) return neSolid && nwSolid && seSolid && swSolid;
        if (solidCardinal == 2) {
            boolean straight = (northSolid && southSolid) || (eastSolid && westSolid);
            return straight && neSolid && nwSolid && seSolid && swSolid;
        }
        return false;
    }

    private boolean is1x1AllFourWalls(int x, int y, int z, ScanContext ctx) {
        if (!ctx.isSolid(x, y, z) || !ctx.isTunnelInterior(x, y + 1, z) || !ctx.isSolid(x, y + 2, z)) return false;
        if (!ctx.isSolid(x, y + 1, z - 1)) return false;
        if (!ctx.isSolid(x, y + 1, z + 1)) return false;
        if (!ctx.isSolid(x + 1, y + 1, z)) return false;
        if (!ctx.isSolid(x - 1, y + 1, z)) return false;
        return true;
    }

    private boolean is1x2Tunnel(int x, int y, int z, ScanContext ctx) {
        if (!is1x2Slice(x, y, z, ctx)) return false;
        if (isMineshaftBlock(ctx.get(x, y, z)) || isMineshaftBlock(ctx.get(x, y + 3, z))) return false;

        boolean northSolid = ctx.isSolid(x, y + 1, z - 1) && ctx.isSolid(x, y + 2, z - 1);
        boolean southSolid = ctx.isSolid(x, y + 1, z + 1) && ctx.isSolid(x, y + 2, z + 1);
        boolean eastSolid  = ctx.isSolid(x + 1, y + 1, z) && ctx.isSolid(x + 1, y + 2, z);
        boolean westSolid  = ctx.isSolid(x - 1, y + 1, z) && ctx.isSolid(x - 1, y + 2, z);

        int solidWalls = (northSolid ? 1 : 0) + (southSolid ? 1 : 0) + (eastSolid ? 1 : 0) + (westSolid ? 1 : 0);

        if (solidWalls == 3) return true;
        if (solidWalls == 2) return (northSolid && southSolid) || (eastSolid && westSolid);
        return false;
    }

    private boolean is1x2Slice(int x, int y, int z, ScanContext ctx) {
        return ctx.isSolid(x, y, z)
            && ctx.isTunnelInterior(x, y + 1, z)
            && ctx.isTunnelInterior(x, y + 2, z)
            && ctx.isSolid(x, y + 3, z);
    }

    private boolean is2x2Tunnel(int x, int y, int z, ScanContext ctx) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                if (!ctx.isSolid(x + dx, y, z + dz) || !ctx.isSolid(x + dx, y + 3, z + dz)) return false;
                if (!ctx.isTunnelInterior(x + dx, y + 1, z + dz) || !ctx.isTunnelInterior(x + dx, y + 2, z + dz)) return false;
            }
        }

        boolean northSolid = true, southSolid = true, eastSolid = true, westSolid = true;

        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 1; dy <= 2; dy++) {
                if (!ctx.isSolid(x + dx, y + dy, z - 1)) northSolid = false;
                if (!ctx.isSolid(x + dx, y + dy, z + 2)) southSolid = false;
            }
        }
        for (int dz = 0; dz < 2; dz++) {
            for (int dy = 1; dy <= 2; dy++) {
                if (!ctx.isSolid(x + 2, y + dy, z + dz)) eastSolid = false;
                if (!ctx.isSolid(x - 1, y + dy, z + dz)) westSolid = false;
            }
        }

        int solidWalls = (northSolid ? 1 : 0) + (southSolid ? 1 : 0) + (eastSolid ? 1 : 0) + (westSolid ? 1 : 0);
        if (solidWalls == 3) return true;
        if (solidWalls == 2) return (northSolid && southSolid) || (eastSolid && westSolid);
        return false;
    }

    private int getAbnormalTunnelSize(int x, int y, int z, ScanContext ctx) {
        if (isTunnelOfSize(x, y, z, ctx, 5)) return 5;
        if (isTunnelOfSize(x, y, z, ctx, 4)) return 4;
        if (isTunnelOfSize(x, y, z, ctx, 3)) return 3;
        return 0;
    }

    private boolean isTunnelOfSize(int x, int y, int z, ScanContext ctx, int s) {
        for (int fx = 0; fx < s; fx++) {
            for (int fz = 0; fz < s; fz++) {
                if (!ctx.isSolid(x+fx, y, z+fz) || !ctx.isSolid(x+fx, y+s+1, z+fz)) return false;
            }
        }
        for (int fx = 0; fx < s; fx++) {
            for (int fy = 1; fy <= s; fy++) {
                for (int fz = 0; fz < s; fz++) {
                    if (!ctx.isTunnelInterior(x+fx, y+fy, z+fz)) return false;
                }
            }
        }
        for (int fx = 0; fx < s; fx++) {
            for (int fy = 1; fy <= s; fy++) {
                if (!ctx.isSolid(x+fx, y+fy, z-1) || !ctx.isSolid(x+fx, y+fy, z+s)) return false;
            }
        }
        for (int fz = 0; fz < s; fz++) {
            for (int fy = 1; fy <= s; fy++) {
                if (!ctx.isSolid(x-1, y+fy, z+fz) || !ctx.isSolid(x+s, y+fy, z+fz)) return false;
            }
        }
        return true;
    }

    private boolean isMineshaftBlock(BlockState s) {
        if (s == null) return false;
        Block b = s.getBlock();
        return b == Blocks.OAK_PLANKS || b == Blocks.DARK_OAK_PLANKS;
    }

    private boolean isLadderShaft(int x, int y, int z, ScanContext ctx, int minH) {
        if (!ctx.isSolid(x, y - 1, z)) return false;
        for (int i = 0; i < minH; i++) {
            int cy = y + i;
            if (!ctx.isAir(x, cy, z)) return false;
            if (!ctx.isLadder(x-1, cy, z) && !ctx.isLadder(x+1, cy, z)
                    && !ctx.isLadder(x, cy, z-1) && !ctx.isLadder(x, cy, z+1)) return false;

            int walls = 0;
            if (ctx.isSolid(x-1, cy, z)) walls++;
            if (ctx.isSolid(x+1, cy, z)) walls++;
            if (ctx.isSolid(x, cy, z-1)) walls++;
            if (ctx.isSolid(x, cy, z+1)) walls++;
            if (walls < 3) return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Rendering
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        List<MergedBox> snapshot = renderSnapshot;
        if (snapshot.isEmpty()) return;

        boolean doFade = fadeWithDistance.get();
        double maxDistSq = (double)(range.get() * 16) * (range.get() * 16);
        int limit = maxRenderBoxes.get();
        ShapeMode sm = shapeMode.get();
        HighlightStyle style = highlightStyle.get();

        double spectralPulseMult = 1.0;
        if (style == HighlightStyle.SPECTRAL && spectralPulse.get()) {
            spectralPulseMult = 0.6 + 0.4 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 750.0 * Math.PI));
        }

        float pulseFactor = (style == HighlightStyle.PULSE) ? getPulseFactor() : 0f;

        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        int drawn = 0;
        for (MergedBox box : snapshot) {
            if (drawn >= limit) break;

            SettingColor base = getColor(box.type);
            if (base == null) continue;

            float fadeFrac = 1.0f;
            if (doFade) {
                double dx = px < box.x1 ? box.x1 - px : (px > box.x2 ? px - box.x2 : 0);
                double dy = py < box.y1 ? box.y1 - py : (py > box.y2 ? py - box.y2 : 0);
                double dz = pz < box.z1 ? box.z1 - pz : (pz > box.z2 ? pz - box.z2 : 0);
                double currentDistSq = dx*dx + dy*dy + dz*dz;

                fadeFrac = (float) Math.max(0.0, 1.0 - currentDistSq / maxDistSq);
                if (fadeFrac <= 0) continue;
            }

            int fadedA = Math.max(8, (int)(base.a * fadeFrac));
            SettingColor fadedColor = new SettingColor(base.r, base.g, base.b, fadedA);

            switch (style) {
                case GLOW     -> renderGlowBox(event, box, fadedColor, fadeFrac, sm);
                case PULSE    -> renderPulseBox(event, box, fadedColor, fadeFrac, pulseFactor, sm);
                case SPECTRAL -> renderSpectralBox(event, box, fadedColor, fadeFrac, spectralPulseMult, sm);
            }
            drawn++;
        }
    }

    private void renderGlowBox(Render3DEvent event, MergedBox box, SettingColor faded, float fadeFrac, ShapeMode sm) {
        int layers = glowLayers.get();
        double spread = glowSpread.get();
        int baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double t = (double)(i - 1) / layers;
            int layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - t * t)));
            layerAlpha = Math.max(4, (int)(layerAlpha * fadeFrac));

            event.renderer.box(
                box.x1 - expansion, box.y1 - expansion, box.z1 - expansion,
                box.x2 + expansion, box.y2 + expansion, box.z2 + expansion,
                withAlpha(faded, layerAlpha), withAlpha(faded, 0),
                ShapeMode.Sides, 0
            );
        }
        event.renderer.box(box.x1, box.y1, box.z1, box.x2, box.y2, box.z2, faded, faded, sm, 0);
    }

    private void renderPulseBox(Render3DEvent event, MergedBox box, SettingColor faded, float fadeFrac, float pulseFactor, ShapeMode sm) {
        int maxA = (int)(pulseMaxAlpha.get() * fadeFrac);
        int minA = (int)(pulseMinAlpha.get() * fadeFrac);
        int pa = Math.min(255, Math.max(0, (int)(minA + (maxA - minA) * pulseFactor)));

        SettingColor pColor = withAlpha(faded, pa);
        int layers = glowLayers.get();
        double spread = glowSpread.get();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double)(i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int)(pa * taper));
            event.renderer.box(
                box.x1 - expansion, box.y1 - expansion, box.z1 - expansion,
                box.x2 + expansion, box.y2 + expansion, box.z2 + expansion,
                withAlpha(pColor, layerAlpha), withAlpha(pColor, 0),
                ShapeMode.Sides, 0
            );
        }
        event.renderer.box(box.x1, box.y1, box.z1, box.x2, box.y2, box.z2, withAlpha(pColor, pa / 3), pColor, sm, 0);
    }

    private void renderSpectralBox(Render3DEvent event, MergedBox box, SettingColor faded, float fadeFrac, double pulseMult, ShapeMode sm) {
        double expand = spectralExpand.get();
        double ex1 = box.x1 - expand, ey1 = box.y1 - expand, ez1 = box.z1 - expand;
        double ex2 = box.x2 + expand, ey2 = box.y2 + expand, ez2 = box.z2 + expand;

        int lineAlpha = Math.max(4, (int)(spectralLineAlpha.get() * fadeFrac * pulseMult));
        int fillAlpha = Math.max(0, (int)(spectralFillAlpha.get() * fadeFrac * pulseMult));

        if (fillAlpha > 0) {
            event.renderer.box(ex1, ey1, ez1, ex2, ey2, ez2, withAlpha(faded, fillAlpha), withAlpha(faded, 0), ShapeMode.Sides, 0);
        }
        event.renderer.box(ex1, ey1, ez1, ex2, ey2, ez2, withAlpha(faded, 0), withAlpha(faded, lineAlpha), ShapeMode.Lines, 0);
    }

    private float getPulseFactor() {
        double speed = pulseSpeed.get();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float)((Math.sin(phase) + 1.0) * 0.5);
    }

    private SettingColor getColor(TunnelType type) {
        if (type == null) return null;
        ShaftMode sm = shaftMode.get();
        return switch (type) {
            case TUNNEL_1x1   -> find1x1.get() ? color1x1.get() : null;
            case OTHER_TUNNEL -> findOtherTunnels.get() ? colorOtherTunnels.get() : null;
            case HOLE         -> (sm == ShaftMode.Holes || sm == ShaftMode.Both) ? colorHoles.get() : null;
            case LADDER_SHAFT -> (sm == ShaftMode.LadderShafts || sm == ShaftMode.Both) ? colorLadderShafts.get() : null;
        };
    }

    private static SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper Classes
    // ═══════════════════════════════════════════════════════════════════════════

    private static final class ScanContext {
        private final BlockState[][] snapshot;
        private final int bottomCoord, minY, maxY, baseX, baseZ;
        private final boolean includeWater, includeLava;

        ScanContext(BlockState[][] s, int bc, int minY, int maxY, int bx, int bz,
                    boolean includeWater, boolean includeLava) {
            this.snapshot = s;
            this.bottomCoord = bc;
            this.minY = minY;
            this.maxY = maxY;
            this.baseX = bx;
            this.baseZ = bz;
            this.includeWater = includeWater;
            this.includeLava = includeLava;
        }

        BlockState get(int x, int y, int z) {
            if (y < minY || y >= maxY) return null;
            int lx = x - baseX, lz = z - baseZ;
            if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16) return null;

            int si = (y >> 4) - bottomCoord;
            if (si < 0 || si >= snapshot.length) return null;

            BlockState[] sec = snapshot[si];
            return sec == null ? null : sec[lx + lz * 16 + (y & 15) * 256];
        }

        boolean isSolid(int x, int y, int z) {
            BlockState s = get(x, y, z);
            return s != null && s.isOpaque();
        }

        boolean isAir(int x, int y, int z) {
            BlockState s = get(x, y, z);
            return s == null || s.isAir();
        }

        boolean isLadder(int x, int y, int z) {
            BlockState s = get(x, y, z);
            return s != null && s.isOf(Blocks.LADDER);
        }

        boolean isTunnelInterior(int x, int y, int z) {
            BlockState s = get(x, y, z);
            if (s == null || s.isAir()) return true;
            if (includeWater && s.isOf(Blocks.WATER)) return true;
            if (includeLava && s.isOf(Blocks.LAVA)) return true;
            return false;
        }
    }

    private static final class ScanConfig {
        final boolean do1x1, do1x2, do2x2, doAbnormal, doHoles, doLadder;
        final int holeDepth, ladderMin, minY, maxY;
        final boolean includeWater, includeLava;

        ScanConfig(boolean do1x1, boolean do1x2, boolean do2x2,
                   boolean doAbnormal, boolean doHoles, boolean doLadder, int holeDepth,
                   int ladderMin, int minY, int maxY, boolean includeWater, boolean includeLava) {
            this.do1x1 = do1x1;
            this.do1x2 = do1x2;
            this.do2x2 = do2x2;
            this.doAbnormal = doAbnormal;
            this.doHoles = doHoles;
            this.doLadder = doLadder;
            this.holeDepth = holeDepth;
            this.ladderMin = ladderMin;
            this.minY = minY;
            this.maxY = maxY;
            this.includeWater = includeWater;
            this.includeLava = includeLava;
        }
    }

    private static final class MergedBox {
        final double x1, y1, z1, x2, y2, z2;
        final TunnelType type;
        final double distSq;

        MergedBox(double x1, double y1, double z1, double x2, double y2, double z2, TunnelType type, double distSq) {
            this.x1 = x1; this.y1 = y1; this.z1 = z1;
            this.x2 = x2; this.y2 = y2; this.z2 = z2;
            this.type = type; this.distSq = distSq;
        }
    }

    private static final class ScanResult {
        final ChunkPos chunkPos;
        final Map<BlockPos, TunnelType> results;

        ScanResult(ChunkPos chunkPos, Map<BlockPos, TunnelType> results) {
            this.chunkPos = chunkPos;
            this.results = results;
        }
    }
}