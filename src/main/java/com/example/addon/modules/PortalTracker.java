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

    private final Map<BlockPos, PortalType> portals = new ConcurrentHashMap<>();
    private final Set<BlockPos> createdPortals = ConcurrentHashMap.newKeySet();
    private final List<PortalStructure> portalStructures = new CopyOnWriteArrayList<>();

    private final Set<String> notifiedStructures = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> messageCooldowns = new ConcurrentHashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 2000;

    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<ChunkPos> dirtyChunks = ConcurrentHashMap.newKeySet();

    private int totalCreated = 0;
    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;
    private int exclusionTimer = 0;
    private static final int COOLDOWN_TICKS = 200;
    private BlockPos entryPortalPos = null;
    private static final int ENTRY_PORTAL_EXCLUSION_RADIUS = 5;
    private static final double ENTRY_PORTAL_EXCLUSION_RADIUS_SQ = ENTRY_PORTAL_EXCLUSION_RADIUS * ENTRY_PORTAL_EXCLUSION_RADIUS;
    private boolean manuallyActivated = false;
    private long sessionStartTime = 0;
    private int minSessionDuration = 0;
    private int structureUpdateTimer = 0;
    private boolean wasInPortal = false;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgNetherPortals = settings.createGroup("Nether Portals");
    private final SettingGroup sgEndDimension = settings.createGroup("End Dimension");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Portal detection range (chunks around player).")
        .defaultValue(32)
        .min(16)
        .max(64)
        .sliderMin(16)
        .sliderMax(64)
        .build()
    );

    private final Setting<Boolean> dynamicColors = sgGeneral.add(new BoolSetting.Builder()
        .name("dynamic-colors")
        .description("Animated rainbow colors for portals.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> autoMarkRange = sgGeneral.add(new IntSetting.Builder()
        .name("auto-mark-range")
        .description("Auto-mark portals within this range as created by you.")
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
                    info("Session cleared. Reset " + oldCount + " created portal" + (oldCount == 1 ? "" : "s") + ".");
                    resetButton.set(false);
                }
            })
            .build()
        );
    }

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

    private final Setting<Boolean> scanEndPortals = sgEndDimension.add(new BoolSetting.Builder()
        .name("end-portals")
        .description("Scan End portal frames.")
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

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Render style for portal highlights.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    public PortalTracker() {
        super(HuntingUtilities.CATEGORY, "portal-tracker", "Automatically tracks and highlights portals.");
    }

    private void sendMessage(String message) {
        long now = System.currentTimeMillis();
        Long last = messageCooldowns.get(message);
        if (last == null || now - last > MESSAGE_COOLDOWN_MS) {
            super.info(message);
            messageCooldowns.put(message, now);
        }
    }

    @Override
    public void onActivate() {
        portals.clear();
        createdPortals.clear();
        portalStructures.clear();
        notifiedStructures.clear();
        messageCooldowns.clear();
        scannedChunks.clear();
        dirtyChunks.clear();

        manuallyActivated = false;
        sessionStartTime = System.currentTimeMillis();

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
                sendMessage("§7Session: §f" + portalStructures.size() + " §7Portals discovered §8| §a" + totalCreated + " §7Created");
            }
        }
        portals.clear();
        createdPortals.clear();
        portalStructures.clear();
        notifiedStructures.clear();
        messageCooldowns.clear();
        manuallyActivated = false;
        sessionStartTime = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc == null || mc.player == null || mc.world == null) return;

        try {
            if (mc.world.getRegistryKey() == null) return;
        } catch (Exception e) {
            return;
        }

        if (dimensionChangeCooldown > 0) {
            dimensionChangeCooldown--;
            return;
        }

        if (exclusionTimer > 0) exclusionTimer--;

        try {
            String currDim = mc.world.getRegistryKey().getValue().toString();
            if (!currDim.equals(lastDimension)) {
                dimensionChangeCooldown = 0;
                exclusionTimer = COOLDOWN_TICKS;
                lastDimension = currDim;

                if (mc.player != null) entryPortalPos = mc.player.getBlockPos();

                portals.clear();
                createdPortals.clear();
                portalStructures.clear();
                notifiedStructures.clear();
                messageCooldowns.clear();
                scannedChunks.clear();
                dirtyChunks.clear();

                boolean notify = (currDim.equals("minecraft:the_nether") && scanNetherPortals.get()) ||
                                 (currDim.equals("minecraft:overworld") && scanNetherPortals.get()) ||
                                 (currDim.equals("minecraft:the_end") && (scanEndPortals.get() || scanEndGateways.get()));
                if (notify) sendMessage("§7Entered " + getDimensionName(currDim) + " - scanning started");
                return;
            }
        } catch (Exception ignored) {
            return;
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        // Handle dirty chunks from block updates
        if (!dirtyChunks.isEmpty()) {
            for (ChunkPos cp : dirtyChunks) {
                scannedChunks.remove(cp);
            }
            dirtyChunks.clear();
        }

        scanBlockEntities(centerChunkX, centerChunkZ);
        scanNewChunks(centerChunkX, centerChunkZ);

        if (!wasInPortal && structureUpdateTimer++ > 0) {
            structureUpdateTimer = 0;
            cleanupDistantPortals();
            cleanupTrackedData();
            groupPortals();
            if (!manuallyActivated) manuallyActivated = true;
        }
    }

    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r = range.get();
        int rSq = r * r;

        // Cleanup distant chunks
        scannedChunks.removeIf(cp -> {
            int dx = cp.x - centerChunkX;
            int dz = cp.z - centerChunkZ;
            return dx * dx + dz * dz > rSq;
        });

        int chunksScanned = 0;
        int limit = 10; // Limit chunks per tick

        // Scan concentric squares
        for (int d = 0; d <= r; d++) {
            if (chunksScanned >= limit) break;

            int minX = -d;
            int maxX = d;
            int minZ = -d;
            int maxZ = d;

            for (int x = minX; x <= maxX; x++) {
                if (processChunk(centerChunkX + x, centerChunkZ + minZ, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                if (chunksScanned >= limit) break;
                if (minZ != maxZ) {
                    if (processChunk(centerChunkX + x, centerChunkZ + maxZ, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                }
                if (chunksScanned >= limit) break;
            }
            if (chunksScanned >= limit) break;

            for (int z = minZ + 1; z < maxZ; z++) {
                if (processChunk(centerChunkX + minX, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                if (chunksScanned >= limit) break;
                if (minX != maxX) {
                    if (processChunk(centerChunkX + maxX, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                }
                if (chunksScanned >= limit) break;
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

    private void scanChunk(WorldChunk chunk) {
        String dimId = mc.world.getRegistryKey().getValue().toString();
        ChunkSection[] sections = chunk.getSectionArray();
        
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionY = chunk.getBottomSectionCoord() + i;
            int sectionMinY = sectionY * 16;
            int sectionMaxY = sectionMinY + 16;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int worldY = sectionMinY + y;
                        BlockState state = section.getBlockState(x, y, z);
                        PortalType found = getPortalType(state.getBlock());

                        if (found != null) {
                            BlockPos pos = new BlockPos((chunk.getPos().x << 4) + x, worldY, (chunk.getPos().z << 4) + z);
                            if (!portals.containsKey(pos)) {
                                portals.put(pos, found);
                                processNewDiscovery(pos, found, dimId);
                            }
                        }
                    }
                }
            }
        }
    }

    private void scanBlockEntities(int centerChunkX, int centerChunkZ) {
        int maxDistSq = range.get() * range.get();
        String dimId = mc.world.getRegistryKey().getValue().toString();

        for (int cx = centerChunkX - range.get(); cx <= centerChunkX + range.get(); cx++) {
            for (int cz = centerChunkZ - range.get(); cz <= centerChunkZ + range.get(); cz++) {
                int dx = cx - centerChunkX;
                int dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > maxDistSq) continue;

                WorldChunk chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    PortalType type = null;
                    if (scanEndGateways.get() && be instanceof EndGatewayBlockEntity) {
                        type = PortalType.END_GATEWAY;
                    } else if (scanEndPortals.get() && be instanceof EndPortalBlockEntity) {
                        type = PortalType.END_PORTAL;
                    }

                    if (type != null) {
                        BlockPos pos = be.getPos();
                        if (!portals.containsKey(pos)) {
                            portals.put(pos, type);
                            processNewDiscovery(pos, type, dimId);
                        }
                    }
                }
            }
        }
    }

    private boolean isPortalBlock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY;
    }

    private PortalType getPortalType(Block block) {
        if (scanNetherPortals.get() && block == Blocks.NETHER_PORTAL) return PortalType.NETHER;
        if (scanEndPortals.get() && block == Blocks.END_PORTAL_FRAME) return PortalType.END_PORTAL;
        if (scanEndGateways.get() && block == Blocks.END_GATEWAY) return PortalType.END_GATEWAY;
        return null;
    }

    private void processNewDiscovery(BlockPos pos, PortalType type, String dimensionId) {
        if (autoMarkRange.get() <= 0 || mc.player == null) return;
        if (dimensionId.equals("minecraft:overworld")) return;
        if (type != PortalType.NETHER) return;

        int autoRange = autoMarkRange.get();

        double distSq = pos.getSquaredDistance(mc.player.getPos());
        if (distSq <= autoRange * autoRange) {
            boolean shouldExclude = false;
            if (exclusionTimer > 0 && entryPortalPos != null) {
                if (pos.getSquaredDistance(entryPortalPos) <= ENTRY_PORTAL_EXCLUSION_RADIUS_SQ) shouldExclude = true;
            }
            if (!shouldExclude && !createdPortals.contains(pos)) {
                createdPortals.add(pos);
            }
        }
    }

    private void groupPortals() {
        List<PortalStructure> newStructures = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos startPos : portals.keySet()) {
            if (visited.contains(startPos)) continue;

            PortalType type = portals.get(startPos);
            if (type == null) continue;

            Set<BlockPos> component = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();
            queue.add(startPos);
            visited.add(startPos);

            Box structureBox = new Box(startPos);
            boolean isCreated = false;

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
                    String structureId = String.format("%s_%.1f_%.1f_%.1f", type.name(), structureBox.minX, structureBox.minY, structureBox.minZ);
                    if (notifiedStructures.add(structureId)) {
                        totalCreated++;
                        sendMessage("§aCreated Portal #" + totalCreated + " §7(" + type.getDisplayName() + ")");
                    }
                }
            }
        }

        portalStructures.clear();
        portalStructures.addAll(newStructures);
    }

    private void cleanupTrackedData() {
        if (mc.player == null) return;
        BlockPos playerPos = mc.player.getBlockPos();
        int cleanupDist = range.get() * 16 + 32;
        double cleanupDistSq = cleanupDist * cleanupDist;
        createdPortals.removeIf(pos -> pos.getSquaredDistance(playerPos) > cleanupDistSq);
    }

    private void cleanupDistantPortals() {
        if (mc.player == null) return;
        BlockPos playerPos = mc.player.getBlockPos();
        int renderDist = range.get() * 16;
        double renderDistSq = (renderDist + 64) * (renderDist + 64);
        portals.entrySet().removeIf(entry -> playerPos.getSquaredDistance(entry.getKey()) > renderDistSq);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        for (PortalStructure structure : portalStructures) {
            if (onlyShowCreated.get() && !structure.isCreated) continue;

            Color color = getColor(structure.type);
            if (color != null) {
                event.renderer.box(structure.boundingBox, color, color, shapeMode.get(), 0);
            }
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || mc.world == null) return;

        BlockPos pos = event.pos;
        if (pos.getSquaredDistance(mc.player.getPos()) > (range.get() * 16.0 + 32) * (range.get() * 16.0 + 32)) return;

        boolean wasPortal = getPortalType(event.oldState.getBlock()) != null;
        boolean isPortal = getPortalType(event.newState.getBlock()) != null;

        if (wasPortal || isPortal) {
            dirtyChunks.add(new ChunkPos(pos));
            if (event.newState.isAir()) portals.remove(pos);
        }
    }

    private Color getColor(PortalType type) {
        if (dynamicColors.get()) {
            long time = System.currentTimeMillis();
            float hue = (time % 3000) / 3000f;
            int rgb = java.awt.Color.HSBtoRGB(hue, 0.8f, 1.0f);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            return new Color(r, g, b, 100);
        }

        return switch (type) {
            case NETHER -> netherColor.get();
            case END_PORTAL -> endPortalColor.get();
            case END_GATEWAY -> endGatewayColor.get();
        };
    }

    public int getTotalPortals() {
        return portalStructures.size();
    }

    public int getTotalCreated() {
        return totalCreated;
    }

    /**
     * Public method to allow mixins to mark chunks as dirty for re-scanning.
     */
    public void markChunkDirty(ChunkPos chunkPos) {
        if (chunkPos != null) {
            dirtyChunks.add(chunkPos);
            scannedChunks.remove(chunkPos);
        }
    }

    private String getDimensionName(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_end" -> "End";
            default -> dimensionId;
        };
    }

    private enum PortalType {
        NETHER("Nether Portal"),
        END_PORTAL("End Portal"),
        END_GATEWAY("End Gateway");

        private final String name;
        PortalType(String name) { this.name = name; }
        public String getDisplayName() { return name; }
    }

    private static class PortalStructure {
        final Box boundingBox;
        final boolean isCreated;
        final PortalType type;

        PortalStructure(Box boundingBox, boolean isCreated, PortalType type) {
            this.boundingBox = boundingBox;
            this.isCreated = isCreated;
            this.type = type;
        }
    }
}