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
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.state.property.Properties;
import net.minecraft.world.World;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LavaMarker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> chunkRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-radius")
        .description("The horizontal radius in chunks to scan for lava.")
        .defaultValue(4)
        .min(1)
        .sliderMax(16)
        .build()
    );

    private final Setting<Integer> verticalRadius = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-radius")
        .description("The vertical radius to scan for lava.")
        .defaultValue(64)
        .min(0)
        .sliderMax(64)
        .build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("The color of the highlight.")
        .defaultValue(new SettingColor(255, 100, 0, 50))
        .build()
    );

    private final Setting<Boolean> trackFlowingLava = sgGeneral.add(new BoolSetting.Builder()
        .name("track-flowing-lava")
        .description("Highlights flowing lava blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> flowingLavaColor = sgGeneral.add(new ColorSetting.Builder()
        .name("flowing-lava-color")
        .description("The color of the flowing lava highlight.")
        .defaultValue(new SettingColor(139, 0, 0, 50))
        .visible(trackFlowingLava::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Sides)
        .build()
    );

    private final Map<String, Set<BlockPos>> lavaFallPositions = new ConcurrentHashMap<>();
    private final Map<String, Set<BlockPos>> flowingLavaPositions = new ConcurrentHashMap<>();
    private final Map<String, Set<ChunkPos>> scannedChunksPerDimension = new ConcurrentHashMap<>();
    private final Set<ChunkPos> dirtyChunks = ConcurrentHashMap.newKeySet();

    private String lastDimension = "";

    public LavaMarker() {
        super(HuntingUtilities.CATEGORY, "lava-marker", "Highlights the bottom of fully flowed lava falls.");
    }

    @Override
    public void onDeactivate() {
        lavaFallPositions.clear();
        flowingLavaPositions.clear();
        scannedChunksPerDimension.clear();
        dirtyChunks.clear();
        lastDimension = "";
    }

    @Override
    public void onActivate() {
        lavaFallPositions.clear();
        flowingLavaPositions.clear();
        scannedChunksPerDimension.clear();
        dirtyChunks.clear();
        if (mc.player != null && mc.world != null) {
            lastDimension = mc.world.getRegistryKey().getValue().toString();
        } else {
            lastDimension = "";
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        
        String currentDimension = mc.world.getRegistryKey().getValue().toString();
        
        // Only run in the Nether
        if (!currentDimension.equals("minecraft:the_nether")) {
            return;
        }

        if (!currentDimension.equals(lastDimension)) {
            lastDimension = currentDimension;
        }

        Set<BlockPos> fallPositions = lavaFallPositions.computeIfAbsent(currentDimension, k -> ConcurrentHashMap.newKeySet());
        Set<BlockPos> flowPositions = flowingLavaPositions.computeIfAbsent(currentDimension, k -> ConcurrentHashMap.newKeySet());
        Set<ChunkPos> scannedChunks = scannedChunksPerDimension.computeIfAbsent(currentDimension, k -> new HashSet<>());

        if (!dirtyChunks.isEmpty()) {
            dirtyChunks.forEach(scannedChunks::remove);
            dirtyChunks.clear();
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int chunkRadius = this.chunkRadius.get();

        fallPositions.removeIf(pos -> pos.getSquaredDistance(playerPos) > (chunkRadius * 16 + 16) * (chunkRadius * 16 + 16));
        flowPositions.removeIf(pos -> pos.getSquaredDistance(playerPos) > (chunkRadius * 16 + 16) * (chunkRadius * 16 + 16));

        scannedChunks.removeIf(chunkPos -> {
            int dx = chunkPos.x - (playerPos.getX() >> 4);
            int dz = chunkPos.z - (playerPos.getZ() >> 4);
            return (dx * dx + dz * dz) > (chunkRadius * chunkRadius);
        });

        // Progressively scan a few new chunks each tick, prioritizing chunks closer to the player.
        int chunksToScanPerTick = 4;
        int scannedThisTick = 0;

        List<ChunkPos> potentialChunks = new ArrayList<>();
        for (int x = -chunkRadius; x <= chunkRadius; x++) {
            for (int z = -chunkRadius; z <= chunkRadius; z++) {
                potentialChunks.add(new ChunkPos((playerPos.getX() >> 4) + x, (playerPos.getZ() >> 4) + z));
            }
        }

        potentialChunks.sort(Comparator.comparingDouble(c -> c.getSquaredDistance(new ChunkPos(playerPos))));

        for (ChunkPos chunkPos : potentialChunks) {
            if (scannedThisTick >= chunksToScanPerTick) break;

            if (!scannedChunks.contains(chunkPos) && mc.world.getChunkManager().isChunkLoaded(chunkPos.x, chunkPos.z)) {
                scanChunk(mc.world.getChunk(chunkPos.x, chunkPos.z), fallPositions, flowPositions);
                scannedChunks.add(chunkPos);
                scannedThisTick++;
            }
        }
    }

    public void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
        if (mc.world == null || !mc.world.getRegistryKey().getValue().toString().equals("minecraft:the_nether")) return;
    
        if (oldState.isOf(Blocks.LAVA) || newState.isOf(Blocks.LAVA) || oldState.isAir() || newState.isAir()) {
            dirtyChunks.add(new ChunkPos(pos));
        }
    }

    private void scanChunk(Chunk chunk, Set<BlockPos> fallPositions, Set<BlockPos> flowPositions) {
        if (chunk == null || mc.player == null || mc.world == null) return;

        // First, remove all existing positions within this chunk's bounds to prepare for a fresh scan.
        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        int endX = chunkPos.getEndX();
        int endZ = chunkPos.getEndZ();
        fallPositions.removeIf(p ->
            p.getX() >= startX && p.getX() <= endX &&
            p.getZ() >= startZ && p.getZ() <= endZ);
        if (trackFlowingLava.get()) {
            flowPositions.removeIf(p -> p.getX() >= startX && p.getX() <= endX &&
                p.getZ() >= startZ && p.getZ() <= endZ);
        }

        int vRadius = verticalRadius.get();
        BlockPos.Mutable mpos = new BlockPos.Mutable();

        // Pass 1: find all fall tips (last block of a falling column) within this chunk.
        // BFS is then used to mark the entire connected flow from each tip.
        Set<BlockPos> fallTips = new HashSet<>();

        for (int sectionY = chunk.getBottomSectionCoord(); sectionY < chunk.getTopSectionCoord(); sectionY++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        mpos.set(chunk.getPos().getStartX() + x, sectionY * 16 + y, chunk.getPos().getStartZ() + z);
                        if (Math.abs(mpos.getY() - mc.player.getY()) > vRadius) continue;

                        FluidState fs = mc.world.getFluidState(mpos);
                        if (fs.isIn(FluidTags.LAVA) && !fs.isStill()
                                && fs.contains(Properties.FALLING) && fs.get(Properties.FALLING)
                                && !mc.world.getFluidState(mpos.down()).isIn(FluidTags.LAVA)) {
                            // This is the last block of a falling lava column
                            fallTips.add(mpos.toImmutable());
                        }

                        if (trackFlowingLava.get() && fs.isIn(FluidTags.LAVA) && !fs.isStill()) {
                            flowPositions.add(mpos.toImmutable());
                        }
                    }
                }
            }
        }

        // Pass 2: BFS from each fall tip to mark the full connected flowing lava spread.
        Set<BlockPos> globalVisited = new HashSet<>(fallPositions);
        for (BlockPos tip : fallTips) {
            bfsConnectedFlow(tip, mc.world, fallPositions, globalVisited);
        }
    }

    /**
     * BFS from a fall tip through all connected FLOWING lava (non-source).
     * Still/source lava (lakes) acts as a natural boundary — BFS stops there.
     */
    private void bfsConnectedFlow(BlockPos start, World world, Set<BlockPos> result, Set<BlockPos> visited) {
        if (visited.contains(start)) return;

        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        int maxBlocks = 1024; // cap to avoid runaway BFS on massive flows
        int count = 0;

        while (!queue.isEmpty() && count < maxBlocks) {
            BlockPos cur = queue.poll();
            result.add(cur);
            count++;

            for (BlockPos nb : new BlockPos[]{
                cur.north(), cur.south(), cur.east(), cur.west(),
                cur.up(), cur.down()
            }) {
                if (!visited.contains(nb)) {
                    FluidState ns = world.getFluidState(nb);
                    if (ns.isIn(FluidTags.LAVA) && !ns.isStill()) {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }
        }
    }

    private boolean isFlowingLava(BlockPos pos, World world) {
        FluidState state = world.getFluidState(pos);
        return state.isIn(FluidTags.LAVA) && !state.isStill();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;
        String currentDimension = mc.world.getRegistryKey().getValue().toString();

        Set<BlockPos> fallPositions = lavaFallPositions.get(currentDimension);
        if (fallPositions != null && !fallPositions.isEmpty()) {
            renderConnectedAABBs(event, fallPositions, color.get());
        }

        if (trackFlowingLava.get()) {
            Set<BlockPos> flowPositions = flowingLavaPositions.get(currentDimension);
            if (flowPositions != null && !flowPositions.isEmpty()) {
                // Exclude any blocks already highlighted as fall-flow to prevent overlap
                Set<BlockPos> netFlow = new HashSet<>(flowPositions);
                if (fallPositions != null) netFlow.removeAll(fallPositions);
                if (!netFlow.isEmpty()) renderConnectedAABBs(event, netFlow, flowingLavaColor.get());
            }
        }
    }

    /**
     * Renders each block with face culling: shared faces between adjacent blocks
     * in the same set are excluded so the entire flow appears as one seamless shape
     * rather than individual boxes or an oversized bounding-box cube.
     *
     * Exclude bitmask (matches Direction.ordinal()):
     *   1 = Down, 2 = Up, 4 = North, 8 = South, 16 = West, 32 = East
     */
    private void renderConnectedAABBs(Render3DEvent event, Set<BlockPos> positions, SettingColor c) {
        for (BlockPos pos : positions) {
            int exclude = 0;
            if (positions.contains(pos.down()))  exclude |= 1;
            if (positions.contains(pos.up()))    exclude |= 2;
            if (positions.contains(pos.north())) exclude |= 4;
            if (positions.contains(pos.south())) exclude |= 8;
            if (positions.contains(pos.west()))  exclude |= 16;
            if (positions.contains(pos.east()))  exclude |= 32;
            event.renderer.box(pos, c, c, shapeMode.get(), exclude);
        }
    }
}