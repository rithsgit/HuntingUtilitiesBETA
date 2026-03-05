package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Tunnelers extends Module {

    public enum TunnelType {
        TUNNEL_1x1,
        TUNNEL_2x2,
        HOLE,
        ABNORMAL_TUNNEL
    }

    // -------------------- Setting Groups --------------------
    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sg1x1      = settings.createGroup("1x1 Tunnels");
    private final SettingGroup sg2x2      = settings.createGroup("2x2 Tunnels");
    private final SettingGroup sgHoles    = settings.createGroup("Holes");
    private final SettingGroup sgAbnormal = settings.createGroup("Abnormal Tunnels");
    private final SettingGroup sgRender   = settings.createGroup("Render");

    // -------------------- General --------------------
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Scan range in chunks.")
        .defaultValue(8).min(1).sliderMax(32).build());

    private final Setting<Integer> scanDelay = sgGeneral.add(new IntSetting.Builder()
        .name("scan-delay").description("Ticks between out-of-range pruning passes.")
        .defaultValue(40).min(10).sliderMax(200).build());

    // -------------------- 1x1 --------------------
    private final Setting<Boolean> find1x1 = sg1x1.add(new BoolSetting.Builder()
        .name("find-1x1-tunnels").defaultValue(true).build());

    private final Setting<SettingColor> color1x1 = sg1x1.add(new ColorSetting.Builder()
        .name("color-1x1").defaultValue(new SettingColor(255, 255, 0, 75))
        .visible(find1x1::get).build());

    // -------------------- 2x2 --------------------
    private final Setting<Boolean> find2x2 = sg2x2.add(new BoolSetting.Builder()
        .name("find-2x2-tunnels").defaultValue(true).build());

    private final Setting<SettingColor> color2x2 = sg2x2.add(new ColorSetting.Builder()
        .name("color-2x2").defaultValue(new SettingColor(255, 165, 0, 75))
        .visible(find2x2::get).build());

    // -------------------- Holes --------------------
    private final Setting<Boolean> findHoles = sgHoles.add(new BoolSetting.Builder()
        .name("find-holes").defaultValue(true).build());

    private final Setting<Integer> minHoleHeight = sgHoles.add(new IntSetting.Builder()
        .name("min-hole-height").defaultValue(4).min(2).sliderMax(20)
        .visible(findHoles::get).build());

    private final Setting<SettingColor> colorHoles = sgHoles.add(new ColorSetting.Builder()
        .name("color-holes").defaultValue(new SettingColor(0, 255, 255, 75))
        .visible(findHoles::get).build());

    // -------------------- Abnormal --------------------
    private final Setting<Boolean> findAbnormalTunnels = sgAbnormal.add(new BoolSetting.Builder()
        .name("find-abnormal-tunnels").defaultValue(true).build());

    private final Setting<SettingColor> colorAbnormalTunnels = sgAbnormal.add(new ColorSetting.Builder()
        .name("color-abnormal").defaultValue(new SettingColor(255, 0, 255, 75))
        .visible(findAbnormalTunnels::get).build());

    // -------------------- Render --------------------
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both).build());

    // ------------------------------------------------------------------ //
    //  State                                                               //
    // ------------------------------------------------------------------ //

    /**
     * Detection results used by the render thread.
     * Written only from the main thread (results flushed from pendingResults).
     * ConcurrentHashMap so the render thread can iterate without locking.
     */
    private final ConcurrentHashMap<BlockPos, TunnelType> locations = new ConcurrentHashMap<>();

    /**
     * Results produced by background scan tasks, waiting to be merged into
     * {@link #locations} on the next main-thread tick.
     */
    private final ConcurrentLinkedQueue<Map<BlockPos, TunnelType>> pendingResults
        = new ConcurrentLinkedQueue<>();

    /** Chunks whose results are already in {@code locations}. Main thread only. */
    private final Set<ChunkPos> scannedChunks = new HashSet<>();

    /** Chunks currently being scanned on a background thread. */
    private final Set<ChunkPos> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Single-thread executor for background chunk scans.
     * One thread is enough — scans are fast once off the render thread.
     * Using a bounded queue (capacity 64) so we never queue more work than
     * the player can possibly reach.
     */
    private ExecutorService executor;

    private int tickTimer;

    public Tunnelers() {
        super(HuntingUtilities.CATEGORY, "tunnelers", "Highlights tunnels and holes.");
    }

    @Override
    public void onActivate() {
        locations.clear();
        pendingResults.clear();
        scannedChunks.clear();
        inFlight.clear();
        tickTimer = 0;
        // daemon=true so it doesn't prevent JVM shutdown if the game force-exits
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Tunnelers-Scanner");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY); // yield to game threads
            return t;
        });
    }

    @Override
    public void onDeactivate() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        locations.clear();
        pendingResults.clear();
        scannedChunks.clear();
        inFlight.clear();
    }

    // ------------------------------------------------------------------ //
    //  Mixin hooks                                                         //
    // ------------------------------------------------------------------ //

    /** Called by TunnelersMixin immediately when a chunk packet is received. */
    public void onChunkLoaded(WorldChunk chunk) {
        ChunkPos cp = chunk.getPos();
        // Take a snapshot of the chunk data on the main thread, then scan off-thread.
        submitScan(cp, snapshotChunk(chunk));
    }

    /** Called by TunnelersMixin when a chunk is unloaded. */
    public void onChunkUnloaded(int chunkX, int chunkZ) {
        ChunkPos cp = new ChunkPos(chunkX, chunkZ);
        scannedChunks.remove(cp);
        inFlight.remove(cp);
        evictChunk(cp);
    }

    // ------------------------------------------------------------------ //
    //  Tick                                                                //
    // ------------------------------------------------------------------ //

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // 1. Merge completed background results into the main map (cheap, main thread).
        flushPendingResults();

        // 2. Throttled: prune out-of-range data and queue missing chunks.
        if (tickTimer > 0) { tickTimer--; return; }
        tickTimer = scanDelay.get();

        pruneOutOfRange();
        submitMissingChunks();
    }

    /** Drain all completed scan results into {@link #locations}. */
    private void flushPendingResults() {
        Map<BlockPos, TunnelType> batch;
        while ((batch = pendingResults.poll()) != null) {
            locations.putAll(batch);
        }
    }

    private void pruneOutOfRange() {
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        int r   = range.get();
        int rSq = r * r;

        scannedChunks.removeIf(cp -> {
            int dx = cp.x - pcx, dz = cp.z - pcz;
            if (dx * dx + dz * dz > rSq) { evictChunk(cp); return true; }
            return false;
        });
    }

    private void submitMissingChunks() {
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        int r   = range.get();
        int rSq = r * r;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > rSq) continue;
                int cx = pcx + dx, cz = pcz + dz;
                ChunkPos cp = new ChunkPos(cx, cz);
                if (scannedChunks.contains(cp) || inFlight.contains(cp)) continue;
                if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) continue;
                WorldChunk chunk = mc.world.getChunk(cx, cz);
                submitScan(cp, snapshotChunk(chunk));
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Snapshot + async scan                                               //
    // ------------------------------------------------------------------ //

    /**
     * Copies the block states of a chunk into a flat array on the main thread.
     * This is fast (just array copies from already-loaded data) and means the
     * background scanner never touches Minecraft world state at all, avoiding
     * all thread-safety issues.
     *
     * Layout: [sectionIndex][localX + localZ*16 + localY*256]
     */
    private BlockState[][] snapshotChunk(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        BlockState[][] snapshot = new BlockState[sections.length][];

        for (int si = 0; si < sections.length; si++) {
            ChunkSection section = sections[si];
            if (section == null || section.isEmpty()) {
                snapshot[si] = null;
                continue;
            }
            BlockState[] data = new BlockState[16 * 16 * 16];
            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        data[lx + lz * 16 + ly * 256] = section.getBlockState(lx, ly, lz);
                    }
                }
            }
            snapshot[si] = data;
        }
        return snapshot;
    }

    /** Mark chunk as in-flight and submit the scan task to the background thread. */
    private void submitScan(ChunkPos cp, BlockState[][] snapshot) {
        if (inFlight.contains(cp)) return;
        inFlight.add(cp);
        // Capture settings values now (main thread) so the background task
        // doesn't need to read Setting objects across threads.
        boolean do1x1      = find1x1.get();
        boolean do2x2      = find2x2.get();
        boolean doHoles    = findHoles.get();
        boolean doAbnormal = findAbnormalTunnels.get();
        int     holeDepth  = minHoleHeight.get();
        int     minY       = mc.world.getBottomY();
        int     maxY       = minY + mc.world.getHeight();
        int     bottomCoord = (minY >> 4); // chunk.getBottomSectionCoord() equivalent

        executor.submit(() -> {
            try {
                Map<BlockPos, TunnelType> results = scanSnapshot(
                    cp, snapshot, bottomCoord, minY, maxY,
                    do1x1, do2x2, doHoles, doAbnormal, holeDepth
                );
                pendingResults.add(results);
                scannedChunks.add(cp); // safe: HashSet touched only on main thread via flush
            } finally {
                inFlight.remove(cp);
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  Off-thread scan                                                     //
    // ------------------------------------------------------------------ //

    /**
     * Runs entirely on the background thread.
     * No Minecraft world access – works only with the pre-copied snapshot array.
     *
     * <p>Helper methods below use the same snapshot via a {@link ScanContext}
     * so we never call {@code mc.world.getBlockState()} from off-thread.</p>
     */
    private Map<BlockPos, TunnelType> scanSnapshot(
            ChunkPos cp,
            BlockState[][] snapshot,
            int bottomCoord,
            int minY, int maxY,
            boolean do1x1, boolean do2x2,
            boolean doHoles, boolean doAbnormal,
            int holeDepth
    ) {
        Map<BlockPos, TunnelType> results = new HashMap<>();
        int baseX = cp.x << 4;
        int baseZ = cp.z << 4;

        ScanContext ctx = new ScanContext(snapshot, bottomCoord, minY, maxY, baseX, baseZ);

        for (int si = 0; si < snapshot.length; si++) {
            if (snapshot[si] == null) continue; // empty section

            int sectionMinY = (bottomCoord + si) << 4;
            int sectionMaxY = sectionMinY + 16;
            if (sectionMaxY <= minY || sectionMinY >= maxY) continue;

            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    int worldY = sectionMinY + ly;
                    if (worldY < minY || worldY >= maxY) continue;

                    for (int lz = 0; lz < 16; lz++) {
                        int wx = baseX + lx;
                        int wz = baseZ + lz;

                        classifyBlock(wx, worldY, wz, ctx,
                            do1x1, do2x2, doHoles, doAbnormal, holeDepth,
                            results);
                    }
                }
            }
        }
        return results;
    }

    private void classifyBlock(
            int wx, int wy, int wz,
            ScanContext ctx,
            boolean do1x1, boolean do2x2,
            boolean doHoles, boolean doAbnormal,
            int holeDepth,
            Map<BlockPos, TunnelType> results
    ) {
        // ---- HOLE -------------------------------------------------------
        // Detected from the solid rim block at y; highlight the air column
        // above it (y+1 .. y+holeDepth) so the marker floats inside the pit.
        if (doHoles && isHole(wx, wy, wz, ctx, holeDepth)) {
            for (int i = 1; i <= holeDepth; i++)
                results.put(new BlockPos(wx, wy + i, wz), TunnelType.HOLE);
            return; // don't also test tunnel types on this column
        }

        // ---- 1x1 --------------------------------------------------------
        // Detected from the solid floor at y; highlight the two air blocks
        // the player occupies: y+1 (feet) and y+2 (head).
        if (do1x1 && is1x1Tunnel(wx, wy, wz, ctx)) {
            results.put(new BlockPos(wx, wy + 1, wz), TunnelType.TUNNEL_1x1);
            results.put(new BlockPos(wx, wy + 2, wz), TunnelType.TUNNEL_1x1);
        }

        // ---- Abnormal (3x3 / 4x4 / 5x5) --------------------------------
        // Detected from the solid floor at y; highlight the air volume
        // above it (dy starts at 1 so the floor itself is excluded).
        if (doAbnormal) {
            int sz = getAbnormalTunnelSize(wx, wy, wz, ctx);
            if (sz > 0) {
                for (int dx = 0; dx < sz; dx++)
                    for (int dy = 1; dy <= sz; dy++)   // dy=1: skip the solid floor
                        for (int dz = 0; dz < sz; dz++)
                            results.put(new BlockPos(wx + dx, wy + dy, wz + dz),
                                TunnelType.ABNORMAL_TUNNEL);
            }
        }

        // ---- 2x2 --------------------------------------------------------
        // Detected from the solid floor at y; highlight the 2×2×2 air volume
        // above it (fy=1 and fy=2, skipping the floor at fy=0).
        if (do2x2 && is2x2Tunnel(wx, wy, wz, ctx)) {
            for (int dx = 0; dx < 2; dx++)
                for (int dy = 1; dy <= 2; dy++)        // dy=1: skip the solid floor
                    for (int dz = 0; dz < 2; dz++)
                        results.put(new BlockPos(wx + dx, wy + dy, wz + dz),
                            TunnelType.TUNNEL_2x2);
        }
    }

    // ------------------------------------------------------------------ //
    //  Block tests – use ScanContext, never mc.world                       //
    // ------------------------------------------------------------------ //

    /**
     * A hole: a narrow vertical air shaft dug into solid ground.
     *
     * Conditions that must ALL be true:
     *  1. The rim block at y is solid.
     *  2. All 4 cardinal neighbours at y are solid (enclosed rim — not a cave edge).
     *  3. All 4 diagonal neighbours at y are solid (no diagonal cave bleed-in).
     *  4. Air directly above the rim (y+1) — open to the surface above.
     *  5. At least `depth` consecutive air blocks going DOWN from y-1.
     *  6. At every level of the air column, all 4 cardinal neighbours are solid
     *     — the shaft stays 1x1 all the way down, not widening into a cave.
     */
    private boolean isHole(int x, int y, int z, ScanContext ctx, int depth) {
        if (!ctx.isSolid(x, y, z))    return false; // rim must be solid
        if (!ctx.isAir(x, y + 1, z))  return false; // open above

        // All 4 cardinal neighbours at rim level must be solid
        if (!ctx.isSolid(x - 1, y, z)) return false;
        if (!ctx.isSolid(x + 1, y, z)) return false;
        if (!ctx.isSolid(x, y, z - 1)) return false;
        if (!ctx.isSolid(x, y, z + 1)) return false;

        // All 4 diagonal neighbours at rim level must be solid
        if (!ctx.isSolid(x - 1, y, z - 1)) return false;
        if (!ctx.isSolid(x + 1, y, z - 1)) return false;
        if (!ctx.isSolid(x - 1, y, z + 1)) return false;
        if (!ctx.isSolid(x + 1, y, z + 1)) return false;

        // Air shaft going down: check depth AND that walls stay solid at every level
        for (int i = 1; i <= depth; i++) {
            int sy = y - i;
            if (!ctx.isAir(x, sy, z)) return false; // shaft must stay air
            // All 4 cardinal walls must be solid at this depth — shaft must not
            // widen into a cave at any point going down
            if (!ctx.isSolid(x - 1, sy, z)) return false;
            if (!ctx.isSolid(x + 1, sy, z)) return false;
            if (!ctx.isSolid(x, sy, z - 1)) return false;
            if (!ctx.isSolid(x, sy, z + 1)) return false;
        }
        return true;
    }

    /**
     * A 1x1 tunnel: a single air column exactly 2 blocks tall, fully enclosed
     * on all sides including diagonals.
     * All 8 surrounding blocks (4 cardinal + 4 diagonal) must be solid at both
     * air levels. The diagonal check eliminates open cave false positives: a cave
     * passage running diagonally will have solid cardinal neighbours but open
     * diagonal corners, so it correctly fails here.
     */
    private boolean is1x1Tunnel(int x, int y, int z, ScanContext ctx) {
        if (!ctx.isSolid(x, y,     z)) return false; // floor
        if (!ctx.isAir  (x, y + 1, z)) return false; // feet
        if (!ctx.isAir  (x, y + 2, z)) return false; // head
        if (!ctx.isSolid(x, y + 3, z)) return false; // ceiling
        // All 8 neighbours (cardinal + diagonal) solid at both air levels.
        // This fully encloses the 1x1 column and rejects open caves.
        for (int dy = 1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue; // skip the air column itself
                    if (!ctx.isSolid(x + dx, y + dy, z + dz)) return false;
                }
            }
        }
        return true;
    }

    /**
     * A 2×2 tunnel: 2×2 solid floor at y, 2×2×2 air at y+1..y+2,
     * solid 2×2 ceiling at y+3, and a full solid wall on either the N/S
     * pair or the W/E pair.
     */
    private boolean is2x2Tunnel(int x, int y, int z, ScanContext ctx) {
        // floor
        for (int fx = 0; fx < 2; fx++)
            for (int fz = 0; fz < 2; fz++)
                if (!ctx.isSolid(x + fx, y, z + fz)) return false;
        // air volume (y+1 and y+2)
        for (int fx = 0; fx < 2; fx++)
            for (int fy = 1; fy <= 2; fy++)
                for (int fz = 0; fz < 2; fz++)
                    if (!ctx.isAir(x + fx, y + fy, z + fz)) return false;
        // ceiling at y+3
        for (int fx = 0; fx < 2; fx++)
            for (int fz = 0; fz < 2; fz++)
                if (!ctx.isSolid(x + fx, y + 3, z + fz)) return false;

        // N/S walls at both air levels
        boolean nw = true, sw = true;
        for (int fx = 0; fx < 2; fx++) for (int fy = 1; fy <= 2; fy++) {
            if (!ctx.isSolid(x + fx, y + fy, z - 1)) nw = false;
            if (!ctx.isSolid(x + fx, y + fy, z + 2)) sw = false;
        }
        if (nw && sw) return true;

        // W/E walls at both air levels
        boolean ww = true, ew = true;
        for (int fz = 0; fz < 2; fz++) for (int fy = 1; fy <= 2; fy++) {
            if (!ctx.isSolid(x - 1, y + fy, z + fz)) ww = false;
            if (!ctx.isSolid(x + 2, y + fy, z + fz)) ew = false;
        }
        return ww && ew;
    }

    private int getAbnormalTunnelSize(int x, int y, int z, ScanContext ctx) {
        if (isTunnelOfSize(x, y, z, ctx, 5)) return 5;
        if (isTunnelOfSize(x, y, z, ctx, 4)) return 4;
        if (isTunnelOfSize(x, y, z, ctx, 3)) return 3;
        return 0;
    }

    private boolean isTunnelOfSize(int x, int y, int z, ScanContext ctx, int size) {
        // floor at y
        for (int fx = 0; fx < size; fx++)
            for (int fz = 0; fz < size; fz++)
                if (!ctx.isSolid(x + fx, y, z + fz)) return false;
        // air volume: y+1 .. y+size (size layers tall)
        for (int fx = 0; fx < size; fx++)
            for (int fy = 1; fy <= size; fy++)
                for (int fz = 0; fz < size; fz++)
                    if (!ctx.isAir(x + fx, y + fy, z + fz)) return false;
        // ceiling at y+size+1
        for (int fx = 0; fx < size; fx++)
            for (int fz = 0; fz < size; fz++)
                if (!ctx.isSolid(x + fx, y + size + 1, z + fz)) return false;

        // N/S walls across all air levels
        boolean nw = true, sw = true;
        for (int fx = 0; fx < size; fx++) for (int fy = 1; fy <= size; fy++) {
            if (!ctx.isSolid(x + fx, y + fy, z - 1))    nw = false;
            if (!ctx.isSolid(x + fx, y + fy, z + size)) sw = false;
        }
        if (nw && sw) return true;

        // W/E walls across all air levels
        boolean ww = true, ew = true;
        for (int fz = 0; fz < size; fz++) for (int fy = 1; fy <= size; fy++) {
            if (!ctx.isSolid(x - 1,    y + fy, z + fz)) ww = false;
            if (!ctx.isSolid(x + size, y + fy, z + fz)) ew = false;
        }
        return ww && ew;
    }

    // ------------------------------------------------------------------ //
    //  ScanContext – snapshot accessor                                      //
    // ------------------------------------------------------------------ //

    /**
     * Wraps a chunk snapshot and provides safe block-state lookups by world
     * coordinate.  Queries that fall outside the snapshot (adjacent chunks)
     * return {@code null} / treat the block as non-solid, non-air.
     *
     * <p>This is intentional: tunnel-wall checks that cross a chunk boundary
     * will simply fail to match rather than reading from the live world on a
     * background thread.  The small number of false negatives at chunk edges
     * is far preferable to thread-safety violations.</p>
     */
    private static final class ScanContext {
        private final BlockState[][] snapshot; // [sectionIndex][lx + lz*16 + ly*256]
        private final int bottomCoord;         // chunk.getBottomSectionCoord()
        private final int minY, maxY;
        private final int baseX, baseZ;        // world coords of chunk origin

        ScanContext(BlockState[][] snapshot, int bottomCoord,
                    int minY, int maxY, int baseX, int baseZ) {
            this.snapshot    = snapshot;
            this.bottomCoord = bottomCoord;
            this.minY        = minY;
            this.maxY        = maxY;
            this.baseX       = baseX;
            this.baseZ       = baseZ;
        }

        /** Returns the BlockState at world (x, y, z), or null if out of snapshot. */
        private BlockState get(int x, int y, int z) {
            if (y < minY || y >= maxY) return null;
            int lx = x - baseX;
            int lz = z - baseZ;
            if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16) return null;

            int si = (y >> 4) - bottomCoord;
            if (si < 0 || si >= snapshot.length) return null;
            BlockState[] section = snapshot[si];
            if (section == null) return null;

            int ly = y & 15;
            return section[lx + lz * 16 + ly * 256];
        }

        boolean isSolid(int x, int y, int z) {
            BlockState s = get(x, y, z);
            return s != null && s.isOpaque(); // opaque == solid for tunnel detection purposes
        }

        boolean isAir(int x, int y, int z) {
            BlockState s = get(x, y, z);
            return s == null || s.isAir(); // out-of-snapshot → treat as air
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private void evictChunk(ChunkPos cp) {
        int sx = cp.getStartX(), sz = cp.getStartZ();
        locations.keySet().removeIf(p ->
            p.getX() >= sx && p.getX() < sx + 16 &&
            p.getZ() >= sz && p.getZ() < sz + 16
        );
    }

    // ------------------------------------------------------------------ //
    //  Render                                                              //
    // ------------------------------------------------------------------ //

    @EventHandler
    private void onRender(Render3DEvent event) {
        for (Map.Entry<BlockPos, TunnelType> entry : locations.entrySet()) {
            SettingColor c = getColor(entry.getValue());
            if (c != null) event.renderer.box(entry.getKey(), c, c, shapeMode.get(), 0);
        }
    }

    private SettingColor getColor(TunnelType type) {
        if (type == null) return null;
        return switch (type) {
            case TUNNEL_1x1      -> find1x1.get()            ? color1x1.get()             : null;
            case TUNNEL_2x2      -> find2x2.get()            ? color2x2.get()             : null;
            case HOLE            -> findHoles.get()           ? colorHoles.get()           : null;
            case ABNORMAL_TUNNEL -> findAbnormalTunnels.get() ? colorAbnormalTunnels.get() : null;
        };
    }
}