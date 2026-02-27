package com.example.addon.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

/**
 * LavaMarker — highlights fully-flowed lava in the Nether.
 *
 * Highlights the entire lava fall: column + floor pool (all flowing lava).
 * Excludes: source blocks (lakes).
 *
 * Detection:
 *   1. Find fall tips (bottom of falling columns).
 *   2. BFS through connected flowing lava (column + floor spread).
 *   3. All non-still lava in the fall is highlighted.
 */
public class LavaMarker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> chunkRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-radius")
        .description("Horizontal scan radius in chunks.")
        .defaultValue(4).min(1).sliderMax(128).build()
    );

    private final Setting<Integer> verticalRadius = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-radius")
        .description("Vertical scan radius in blocks.")
        .defaultValue(64).min(0).sliderMax(128).build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("flowing-lava")
        .description("Color for fully-flowed lava falls.")
        .defaultValue(new SettingColor(255, 100, 0, 50)).build()
    );

    private final Setting<Integer> minFallHeight = sgGeneral.add(new IntSetting.Builder()
        .name("min-fall-height")
        .description("Lava falls shorter than this will be ignored.")
        .defaultValue(5)
        .min(0)
        .sliderMax(32)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Sides).build()
    );

    // Per-chunk result sets — replaced atomically to prevent flicker
    private final Map<ChunkPos, Set<BlockPos>> fallsByChunk = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> dirtyChunks   = ConcurrentHashMap.newKeySet();

    private String lastDimension = "";

    public LavaMarker() {
        super(HuntingUtilities.CATEGORY, "lava-marker", "Highlights fully-flowed lava falls in the Nether.");
    }

    @Override
    public void onActivate() {
        clearData();
        if (mc.world != null) lastDimension = mc.world.getRegistryKey().getValue().toString();
    }

    @Override
    public void onDeactivate() {
        clearData();
    }

    private void clearData() {
        fallsByChunk.clear();
        scannedChunks.clear();
        dirtyChunks.clear();
    }

    // -------------------------------------------------------------------------
    // Tick: progressive chunk scanning
    // -------------------------------------------------------------------------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        String dim = mc.world.getRegistryKey().getValue().toString();
        if (!dim.equals("minecraft:the_nether")) {
            if (!fallsByChunk.isEmpty()) clearData();
            return;
        }
        if (!dim.equals(lastDimension)) {
            lastDimension = dim;
            clearData();
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int radius = chunkRadius.get();
        int pX = playerPos.getX() >> 4;
        int pZ = playerPos.getZ() >> 4;

        // Prune out-of-range data
        scannedChunks.removeIf(cp -> isOutOfRange(cp, pX, pZ, radius));
        fallsByChunk.keySet().removeIf(cp -> isOutOfRange(cp, pX, pZ, radius));
        dirtyChunks.removeIf(cp -> isOutOfRange(cp, pX, pZ, radius));

        // Queue unscanned in-range loaded chunks, sorted closest-first
        List<ChunkPos> todo = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                ChunkPos cp = new ChunkPos(pX + x, pZ + z);
                if (!scannedChunks.contains(cp) && mc.world.getChunkManager().isChunkLoaded(cp.x, cp.z)) {
                    todo.add(cp);
                }
            }
        }
        todo.sort(Comparator.comparingDouble(cp -> {
            double dx = ((ChunkPos)cp).x - pX, dz = ((ChunkPos)cp).z - pZ;
            return dx * dx + dz * dz;
        }));

        // Process dirty queue first (block updates), then new chunks
        int processed = 0;
        while (!dirtyChunks.isEmpty() && processed < 4) {
            ChunkPos cp = dirtyChunks.iterator().next();
            dirtyChunks.remove(cp);
            scannedChunks.remove(cp);
            if (mc.world.getChunkManager().isChunkLoaded(cp.x, cp.z)) {
                scanChunk(mc.world.getChunk(cp.x, cp.z));
                scannedChunks.add(cp);
                processed++;
            }
        }
        for (ChunkPos cp : todo) {
            if (processed >= 4) break;
            scanChunk(mc.world.getChunk(cp.x, cp.z));
            scannedChunks.add(cp);
            processed++;
        }
    }

    private boolean isOutOfRange(ChunkPos cp, int pX, int pZ, int radius) {
        return Math.abs(cp.x - pX) > radius || Math.abs(cp.z - pZ) > radius;
    }

    // -------------------------------------------------------------------------
    // Block update
    // -------------------------------------------------------------------------

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.world == null) return;
        if (!mc.world.getRegistryKey().getValue().toString().equals("minecraft:the_nether")) return;
        BlockState ns = event.newState;
        if (ns.isOf(Blocks.LAVA) || ns.isAir()) {
            ChunkPos cp = new ChunkPos(event.pos);
            scannedChunks.remove(cp);
            dirtyChunks.add(cp);
            // Adjacent chunks may be affected by cross-border flows
            dirtyChunks.add(new ChunkPos(cp.x - 1, cp.z));
            dirtyChunks.add(new ChunkPos(cp.x + 1, cp.z));
            dirtyChunks.add(new ChunkPos(cp.x, cp.z - 1));
            dirtyChunks.add(new ChunkPos(cp.x, cp.z + 1));
        }
    }

    // -------------------------------------------------------------------------
    // Chunk scan — builds local set, then atomically swaps it in
    // -------------------------------------------------------------------------

    private void scanChunk(Chunk chunk) {
        if (chunk == null || mc.player == null || mc.world == null) return;

        ChunkPos cp = chunk.getPos();
        int vRadius = verticalRadius.get();
        int playerY = (int) mc.player.getY();
        int minY = Math.max(mc.world.getBottomY(), playerY - vRadius);
        int maxY = Math.min(mc.world.getBottomY() + mc.world.getHeight(), playerY + vRadius);

        // Pass 1: find fall tips in this chunk
        Set<BlockPos> fallTips = new HashSet<>();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionY    = chunk.getBottomSectionCoord() + i;
            int sectionMinY = sectionY << 4;
            int sectionMaxY = sectionMinY + 15;
            if (sectionMaxY < minY || sectionMinY > maxY) continue;
            if (!section.hasAny(s -> s.getFluidState().isIn(FluidTags.LAVA))) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int worldY = sectionMinY + y;
                        if (worldY < minY || worldY > maxY) continue;

                        FluidState fs = section.getBlockState(x, y, z).getFluidState();
                        if (!fs.isIn(FluidTags.LAVA)) continue;

                        boolean falling = fs.contains(Properties.FALLING) && fs.get(Properties.FALLING);
                        if (!falling) continue;

                        BlockPos pos = new BlockPos(cp.getStartX() + x, worldY, cp.getStartZ() + z);
                        // Fall tip: last block of a falling column (block below is NOT falling)
                        if (!isFalling(pos.down())) {
                            fallTips.add(pos);
                        }
                    }
                }
            }
        }

        // Pass 2: BFS from each tip, collect floor spread, filter to fully-flowed only
        Set<BlockPos> allValidFallBlocks = new HashSet<>();
        Set<BlockPos> visitedInScan      = new HashSet<>();
        for (BlockPos tip : fallTips) {
            if (visitedInScan.contains(tip)) continue;

            Set<BlockPos> currentFall = new HashSet<>();
            bfs(tip, currentFall, visitedInScan);

            if (currentFall.isEmpty()) continue;

            // Check fall height from original BFS (includes falling column)
            int fallMinY = Integer.MAX_VALUE;
            int fallMaxY = Integer.MIN_VALUE;
            for (BlockPos pos : currentFall) {
                fallMinY = Math.min(fallMinY, pos.getY());
                fallMaxY = Math.max(fallMaxY, pos.getY());
            }
            if (fallMaxY - fallMinY + 1 < minFallHeight.get()) continue;

            // Include entire fall: column + floor pool (all flowing lava, including falling)
            for (BlockPos pos : currentFall) {
                FluidState fs = mc.world.getFluidState(pos);
                if (fs.isIn(FluidTags.LAVA) && !fs.isStill()) {
                    allValidFallBlocks.add(pos);
                }
            }
        }

        // Atomic swap — renderer always sees a complete set, never a partial clear
        if (!allValidFallBlocks.isEmpty()) fallsByChunk.put(cp, allValidFallBlocks);
        else fallsByChunk.remove(cp);
    }

    private boolean isFalling(BlockPos pos) {
        FluidState fs = mc.world.getFluidState(pos);
        return fs.isIn(FluidTags.LAVA) && fs.contains(Properties.FALLING) && fs.get(Properties.FALLING);
    }

    // -------------------------------------------------------------------------
    // BFS — traces all connected FLOWING lava from a fall tip.
    // Still/source lava (lakes) stops the BFS.
    // Going UP is restricted to FALLING blocks only (no lake bleed).
    // -------------------------------------------------------------------------

    private void bfs(BlockPos start, Set<BlockPos> result, Set<BlockPos> visited) {
        if (visited.contains(start)) return;

        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            result.add(cur); // every reachable flowing block = fully flowed

            // Horizontal + down: follow all connected flowing lava
            for (BlockPos nb : new BlockPos[]{
                cur.north(), cur.south(), cur.east(), cur.west(), cur.down()
            }) {
                if (!visited.contains(nb)
                        && mc.world.getChunkManager().isChunkLoaded(nb.getX() >> 4, nb.getZ() >> 4)) {
                    FluidState ns = mc.world.getFluidState(nb);
                    if (ns.isIn(FluidTags.LAVA) && !ns.isStill()) {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }

            // Up: only follow FALLING blocks (traces the column, prevents lake bleed)
            BlockPos up = cur.up();
            if (!visited.contains(up)
                    && mc.world.getChunkManager().isChunkLoaded(up.getX() >> 4, up.getZ() >> 4)) {
                if (isFalling(up)) {
                    visited.add(up);
                    queue.add(up);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;
        for (Set<BlockPos> set : fallsByChunk.values()) {
            for (BlockPos pos : set) {
                FluidState fs = mc.world.getFluidState(pos);
                if (!fs.isIn(FluidTags.LAVA)) continue;
                if (fs.isStill()) continue;

                boolean isBottomBlock = !mc.world.getFluidState(pos.down()).isIn(FluidTags.LAVA);
                if (isBottomBlock && mc.world.getBlockState(pos.down()).isAir()) continue;

                event.renderer.box(pos, color.get(), color.get(), shapeMode.get(), 0);
            }
        }
    }
}
