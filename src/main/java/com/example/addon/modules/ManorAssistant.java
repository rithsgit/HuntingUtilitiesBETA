package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.Tim;
import com.example.addon.utils.GlowingRegistry;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.EvokerEntity;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.screen.slot.Slot;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

public class ManorAssistant extends Module {

    // ═══════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum TargetType {
        CONTAINER,
        SECRET_CONTAINER
    }

    public enum RenderMode {
        GLOW,
        SPECTRAL,
        PULSE
    }

    public enum AlertSound {
        EVOKER_CAST("Evoker Cast"),
        TOTEM_USE("Totem Use"),
        LEVEL_UP("Level Up"),
        EXPERIENCE_ORB("Experience Orb"),
        BELL("Bell");

        private final String displayName;
        AlertSound(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private static final int DIMENSION_CHANGE_COOLDOWN_TICKS = 40;
    private static final int INTERACT_TIMEOUT_TICKS = 20;

    private final Map<BlockPos, TargetType> targets = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<BlockPos> checkedContainers = new HashSet<>();
    
    private final Set<Integer> notifiedEvokers = new HashSet<>();
    private final Set<Integer> notifiedVindicators = new HashSet<>();
    private final Set<Integer> notifiedTotems = new HashSet<>();
    private int totalTotemsFound = 0;

    private boolean wasAutoOpened = false;
    private BlockPos lastOpenedContainer = null;
    private int interactTimeoutTimer = 0;
    private boolean hasAlertedForCurrentScreen = false;

    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;

    // Entity tracking lists
    private final List<EvokerEntity> evokerTargets = new ArrayList<>();
    private final List<VindicatorEntity> vindicatorTargets = new ArrayList<>();
    private final List<ItemEntity> totemDrops = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBlocks = settings.createGroup("Targets - Manor");
    private final SettingGroup sgEntities = settings.createGroup("Targets - Mobs & Loot");
    private final SettingGroup sgAutomation = settings.createGroup("Automation");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Detection range in chunks.")
        .defaultValue(16).min(1).max(128).sliderMin(1).sliderMax(64)
        .build()
    );

    private final Setting<Integer> manorYLevel = sgGeneral.add(new IntSetting.Builder()
        .name("manor-y-level")
        .description("Minimum Y level to scan. Mansions generate above Y = 60.")
        .defaultValue(60).min(-64).max(320).sliderMin(-64).sliderMax(320)
        .onChanged(v -> {
            scannedChunks.clear();
            targets.entrySet().removeIf(entry -> entry.getKey().getY() < v);
        })
        .build()
    );

    private final Setting<RenderMode> renderMode = sgGeneral.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("GLOW = layered bloom boxes. SPECTRAL = outline shader. PULSE = fading highlight.")
        .defaultValue(RenderMode.GLOW)
        .build()
    );

    private final Setting<Integer> beamWidth = sgGeneral.add(new IntSetting.Builder()
        .name("beam-width")
        .description("Width of the beams for dropped Totems.")
        .defaultValue(15).min(5).max(50)
        .build()
    );

    private final Setting<Integer> glowLayers = sgGeneral.add(new IntSetting.Builder()
        .name("glow-layers").description("Number of bloom layers rendered around each target.")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> renderMode.get() == RenderMode.GLOW || renderMode.get() == RenderMode.PULSE)
        .build()
    );

    private final Setting<Double> glowSpread = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow-spread").description("How far each bloom layer expands outward (in blocks).")
        .defaultValue(0.04).min(0.01).sliderMax(0.15)
        .visible(() -> renderMode.get() == RenderMode.GLOW || renderMode.get() == RenderMode.PULSE)
        .build()
    );

    private final Setting<Integer> glowBaseAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("glow-base-alpha").description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(60).min(10).sliderMax(150)
        .visible(() -> renderMode.get() == RenderMode.GLOW)
        .build()
    );

    private final Setting<Integer> spectralBlockFillAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("spectral-block-fill-alpha")
        .description("Fill alpha for block targets in SPECTRAL mode (0 = invisible, 30 = subtle).")
        .defaultValue(30).min(0).max(120).sliderMax(80)
        .visible(() -> renderMode.get() == RenderMode.SPECTRAL)
        .build()
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
        .defaultValue(220).min(50).max(255).sliderMax(255)
        .visible(() -> renderMode.get() == RenderMode.PULSE).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Targets
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> trackContainers = sgBlocks.add(new BoolSetting.Builder()
        .name("track-chests").description("Highlight standard chests.").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> containerColor = sgBlocks.add(new ColorSetting.Builder()
        .name("chest-color").description("Color for standard chests.")
        .defaultValue(new SettingColor(0, 0, 255, 255))
        .visible(trackContainers::get).build()
    );

    private final Setting<Boolean> trackSecretContainers = sgBlocks.add(new BoolSetting.Builder()
        .name("track-secret-chests").description("Highlight chests hidden inside walls (secret rooms).").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> secretContainerColor = sgBlocks.add(new ColorSetting.Builder()
        .name("secret-chest-color").description("Color for hidden chests.")
        .defaultValue(new SettingColor(255, 0, 255, 255))
        .visible(trackSecretContainers::get).build()
    );

    private final Setting<List<Item>> containerWhitelist = sgBlocks.add(new ItemListSetting.Builder()
        .name("chest-whitelist")
        .description("Items to alert you about when opening Chests.")
        .defaultValue(List.of(
            Items.NETHERITE_BLOCK, Items.NETHERITE_INGOT, Items.DIAMOND, 
            Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE,
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
            Items.ENDER_CHEST, Items.ENCHANTED_GOLDEN_APPLE, Items.ELYTRA,
            Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE,
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
            Items.SHULKER_BOX, Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX,
            Items.LIGHT_BLUE_SHULKER_BOX, Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX,
            Items.GRAY_SHULKER_BOX, Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX,
            Items.BLUE_SHULKER_BOX, Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX,
            Items.BLACK_SHULKER_BOX,
            Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
            Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE,
            Items.TOTEM_OF_UNDYING, Items.WIND_CHARGE, Items.BOOK
        ))
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Entities
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> trackEvokers = sgEntities.add(new BoolSetting.Builder()
        .name("track-evokers").description("Highlights Evokers (Totem dropers).").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> evokerColor = sgEntities.add(new ColorSetting.Builder()
        .name("evoker-color").description("Color for Evokers.")
        .defaultValue(new SettingColor(150, 0, 255, 255))
        .visible(trackEvokers::get).build()
    );

    private final Setting<Boolean> trackVindicators = sgEntities.add(new BoolSetting.Builder()
        .name("track-vindicators").description("Highlights Vindicators (Axe mob).").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> vindicatorColor = sgEntities.add(new ColorSetting.Builder()
        .name("vindicator-color").description("Color for Vindicators.")
        .defaultValue(new SettingColor(255, 100, 0, 255))
        .visible(trackVindicators::get).build()
    );

    private final Setting<Boolean> trackDroppedTotems = sgEntities.add(new BoolSetting.Builder()
        .name("track-dropped-totems").description("Highlights dropped Totems of Undying with a massive beam.").defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> totemColor = sgEntities.add(new ColorSetting.Builder()
        .name("totem-color").description("Color for dropped Totems.")
        .defaultValue(new SettingColor(255, 215, 0, 255))
        .visible(trackDroppedTotems::get).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Automation & Safety
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> enableAlerts = sgAutomation.add(new BoolSetting.Builder()
        .name("enable-alerts")
        .description("Master toggle for audio cues and loot announcements.")
        .defaultValue(true)
        .build()
    );

    private final Setting<AlertSound> alertSound = sgAutomation.add(new EnumSetting.Builder<AlertSound>()
        .name("alert-sound")
        .description("Which sound to play for module alerts.")
        .defaultValue(AlertSound.EVOKER_CAST)
        .visible(enableAlerts::get)
        .build()
    );

    private final Setting<Double> alertVolume = sgAutomation.add(new DoubleSetting.Builder()
        .name("alert-volume")
        .description("Volume of the alert sound. Goes up to 5.0 for extra loud alerts.")
        .defaultValue(1.0).min(0).max(5.0).sliderMax(5.0)
        .visible(enableAlerts::get)
        .build()
    );

    private final Setting<Boolean> autoOpenChests = sgAutomation.add(new BoolSetting.Builder()
        .name("auto-open-chests")
        .description("Automatically opens nearby chests.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disconnectOnPlayer = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-player")
        .description("Instantly disconnects from the server if another player enters render distance.")
        .defaultValue(false).build()
    );

    private final Setting<Boolean> autoDisableOnLowHealth = sgSafety.add(new BoolSetting.Builder()
        .name("auto-disable-on-low-health")
        .description("Disables the module if health is critical.")
        .defaultValue(true).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor & Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    public ManorAssistant() {
        super(Tim.CATEGORY, "manor-assistant", "Highlights Woodland Manor elements: secret chests, evokers, vindicators, and dropped totems.");
    }

    @Override
    public void onActivate() {
        targets.clear();
        scannedChunks.clear();
        checkedContainers.clear();
        notifiedEvokers.clear();
        notifiedVindicators.clear();
        notifiedTotems.clear();
        totalTotemsFound = 0;
        hasAlertedForCurrentScreen = false;
        GlowingRegistry.clear();
    }

    @Override
    public void onDeactivate() {
        GlowingRegistry.clear();
        targets.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Event Handlers
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (performSafetyChecks()) return;
        checkForPlayers();
        updateContainerLogic();
        checkOpenedContainerLoot(); 
        updateScanningLogic();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        boolean isSpectral = renderMode.get() == RenderMode.SPECTRAL;
        boolean isPulse = renderMode.get() == RenderMode.PULSE;
        Set<BlockPos> toRemove = new HashSet<>();

        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            TargetType type = entry.getValue();

            if (!mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            if (mc.world.getBlockState(pos).isAir()) { toRemove.add(pos); continue; }

            Block currentBlock = mc.world.getBlockState(pos).getBlock();
            if (!validateBlockType(currentBlock, type)) { toRemove.add(pos); continue; }

            Box renderBox = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
            SettingColor color = getColor(type);
            if (color == null) continue;

            if (isSpectral) {
                event.renderer.box(renderBox, withAlpha(color, spectralBlockFillAlpha.get()), withAlpha(color, 0), ShapeMode.Sides, 0);
            } else if (isPulse) {
                renderPulseBox(event, renderBox, color);
            } else {
                renderGlowLayers(event, renderBox, color);
                event.renderer.box(renderBox, withAlpha(color, 0), color, ShapeMode.Lines, 0);
            }
        }

        for (BlockPos pos : toRemove) {
            targets.remove(pos);
        }

        // Render Entities
        renderEntity(event, isSpectral, isPulse, trackEvokers.get(), false, evokerTargets, evokerColor.get());
        renderEntity(event, isSpectral, isPulse, trackVindicators.get(), false, vindicatorTargets, vindicatorColor.get());
        renderEntity(event, isSpectral, isPulse, trackDroppedTotems.get(), true, totemDrops, totemColor.get());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Scanning Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private void updateScanningLogic() {
        if (mc.world.getRegistryKey() == null) return;
        if (dimensionChangeCooldown > 0) { dimensionChangeCooldown--; return; }

        String currDim = mc.world.getRegistryKey().getValue().toString();
        if (!currDim.equals(lastDimension)) {
            dimensionChangeCooldown = DIMENSION_CHANGE_COOLDOWN_TICKS;
            lastDimension = currDim;
            targets.clear();
            scannedChunks.clear();
            GlowingRegistry.clear();
            return;
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        cleanupDistantTargets(playerPos);
        scanEvokers();
        scanVindicators();
        scanDroppedTotems();
        pruneBlockTargets();
        scanNewChunks(centerChunkX, centerChunkZ);
    }

    private void scanEvokers() {
        evokerTargets.clear();
        if (!trackEvokers.get()) return;

        int blockRange = range.get() * 16;
        Box searchBox = new Box(mc.player.getBlockPos()).expand(blockRange);
        Set<Integer> currentIds = new HashSet<>();

        for (EvokerEntity evoker : mc.world.getEntitiesByClass(EvokerEntity.class, searchBox, e -> true)) {
            evokerTargets.add(evoker);
            currentIds.add(evoker.getId());

            if (renderMode.get() == RenderMode.SPECTRAL) {
                GlowingRegistry.add(evoker.getId(), toArgb(evokerColor.get()));
            } else {
                GlowingRegistry.remove(evoker.getId());
            }

            if (notifiedEvokers.add(evoker.getId())) {
                if (enableAlerts.get()) {
                    info("§dEvoker Detected! Watch for Vexes.");
                    playAlert();
                }
            }
        }
        notifiedEvokers.retainAll(currentIds);
    }

    private void scanVindicators() {
        vindicatorTargets.clear();
        if (!trackVindicators.get()) return;

        int blockRange = range.get() * 16;
        Box searchBox = new Box(mc.player.getBlockPos()).expand(blockRange);
        Set<Integer> currentIds = new HashSet<>();

        for (VindicatorEntity vindicator : mc.world.getEntitiesByClass(VindicatorEntity.class, searchBox, e -> true)) {
            vindicatorTargets.add(vindicator);
            currentIds.add(vindicator.getId());

            if (renderMode.get() == RenderMode.SPECTRAL) {
                GlowingRegistry.add(vindicator.getId(), toArgb(vindicatorColor.get()));
            } else {
                GlowingRegistry.remove(vindicator.getId());
            }

            if (notifiedVindicators.add(vindicator.getId())) {
                if (enableAlerts.get()) {
                    info("§6Vindicator Detected!");
                    playAlert();
                }
            }
        }
        notifiedVindicators.retainAll(currentIds);
    }

    private void scanDroppedTotems() {
        totemDrops.clear();
        if (!trackDroppedTotems.get()) return;

        int blockRange = range.get() * 16;
        Box searchBox = new Box(mc.player.getBlockPos()).expand(blockRange);
        Set<Integer> currentIds = new HashSet<>();

        for (ItemEntity item : mc.world.getEntitiesByClass(ItemEntity.class, searchBox, e -> true)) {
            if (item.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
                totemDrops.add(item);
                currentIds.add(item.getId());

                if (renderMode.get() == RenderMode.SPECTRAL) {
                    GlowingRegistry.add(item.getId(), toArgb(totemColor.get()));
                }

                if (notifiedTotems.add(item.getId())) {
                    totalTotemsFound++;
                    if (enableAlerts.get()) {
                        info("§6§lTOTEM OF UNDYING DROPPED! §ePick it up!");
                        playAlert();
                    }
                }
            }
        }
        notifiedTotems.retainAll(currentIds);
    }

    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r = range.get();
        int rSq = r * r;

        scannedChunks.removeIf(cp -> {
            int dx = cp.x - centerChunkX;
            int dz = cp.z - centerChunkZ;
            return dx * dx + dz * dz > rSq;
        });

        int chunksScanned = 0;
        int limit = 10;

        outer:
        for (int d = 0; d <= r; d++) {
            int minX = -d, maxX = d, minZ = -d, maxZ = d;

            for (int x = minX; x <= maxX; x++) {
                if (processChunk(centerChunkX + x, centerChunkZ + minZ, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                if (chunksScanned >= limit) break outer;
                if (minZ != maxZ) {
                    if (processChunk(centerChunkX + x, centerChunkZ + maxZ, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                    if (chunksScanned >= limit) break outer;
                }
            }

            for (int z = minZ + 1; z < maxZ; z++) {
                if (processChunk(centerChunkX + minX, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                if (chunksScanned >= limit) break outer;
                if (minX != maxX) {
                    if (processChunk(centerChunkX + maxX, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) chunksScanned++;
                    if (chunksScanned >= limit) break outer;
                }
            }
        }
    }

    private boolean processChunk(int cx, int cz, int rSq, int centerChunkX, int centerChunkZ) {
        int dx = cx - centerChunkX, dz = cz - centerChunkZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp)) return false;
        if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) return false;

        WorldChunk chunk = mc.world.getChunk(cx, cz);
        scanBlockEntitiesInChunk(chunk);
        scannedChunks.add(cp);
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Block Entity Scanning
    // ═══════════════════════════════════════════════════════════════════════════

    private void scanBlockEntitiesInChunk(WorldChunk chunk) {
        int minY = manorYLevel.get(); 

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getPos();
            if (pos.getY() < minY) continue;

            if (be instanceof ChestBlockEntity) {
                if (isSecretChest(pos)) {
                    targets.put(pos, TargetType.SECRET_CONTAINER);
                } else {
                    targets.put(pos, TargetType.CONTAINER);
                }
            }
        }
    }

    private boolean isSecretChest(BlockPos pos) {
        boolean enclosed = true;
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue; // Check up and all 4 sides
            if (!mc.world.getBlockState(pos.offset(dir)).isOpaque()) {
                enclosed = false;
                break;
            }
        }
        return enclosed;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Automation & Safety Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private void updateContainerLogic() {
        if (!autoOpenChests.get()) return;
        
        if (interactTimeoutTimer > 0) interactTimeoutTimer--;

        if (mc.currentScreen == null && !wasAutoOpened) {
            List<BlockPos> nearbyChests = targets.entrySet().stream()
                .filter(e -> e.getValue() == TargetType.CONTAINER || e.getValue() == TargetType.SECRET_CONTAINER)
                .map(Map.Entry::getKey)
                .filter(pos -> !checkedContainers.contains(pos))
                .filter(pos -> Math.sqrt(pos.getSquaredDistance(mc.player.getPos())) <= 4.5)
                .sorted(Comparator.comparingDouble(pos -> pos.getSquaredDistance(mc.player.getPos())))
                .toList();

            if (!nearbyChests.isEmpty()) {
                BlockPos pos = nearbyChests.get(0);
                checkedContainers.add(pos);
                wasAutoOpened = true;
                interactTimeoutTimer = INTERACT_TIMEOUT_TICKS;

                Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
            }
        } else if (mc.currentScreen == null && wasAutoOpened && interactTimeoutTimer == 0) {
            wasAutoOpened = false;
        }
    }

    private void checkOpenedContainerLoot() {
        if (mc.currentScreen instanceof HandledScreen<?> screen && !(mc.currentScreen instanceof InventoryScreen)) {
            // Ignore Ender Chest and Shulker Box contents to prevent false triggers
            if (mc.currentScreen instanceof ShulkerBoxScreen || screen.getTitle().getString().equals(Text.translatable("container.enderchest").getString())) {
                hasAlertedForCurrentScreen = true;
                return;
            }
            
            if (!hasAlertedForCurrentScreen) {
                for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
                    Slot slot = screen.getScreenHandler().slots.get(i);
                    // Ignore player's own inventory contents to prevent false triggers
                    if (slot.inventory instanceof PlayerInventory) continue;
                    
                    ItemStack stack = slot.getStack();
                    if (!stack.isEmpty() && containerWhitelist.get().contains(stack.getItem())) {
                        info("§cRare loot found in chest: §e" + stack.getName().getString() + "§c!");
                        playAlert();
                        hasAlertedForCurrentScreen = true;
                        break;
                    }
                }
            }
        } else {
            hasAlertedForCurrentScreen = false;
        }
    }

    private void checkForPlayers() {
        if (!disconnectOnPlayer.get()) return;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (player.distanceTo(mc.player) < 128) {
                info("§cPlayer detected in render distance! Disconnecting...");
                mc.getNetworkHandler().getConnection().disconnect(new DisconnectionInfo(Text.literal("Player detected in render distance")));
                return;
            }
        }
    }

    private void playAlert() {
        if (mc.player == null) return;
        SoundEvent sound = switch (alertSound.get()) {
            case LEVEL_UP -> SoundEvents.ENTITY_PLAYER_LEVELUP;
            case TOTEM_USE -> SoundEvents.ITEM_TOTEM_USE;
            case EXPERIENCE_ORB -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
            case BELL -> SoundEvents.BLOCK_BELL_USE;
            case EVOKER_CAST -> SoundEvents.ENTITY_EVOKER_CAST_SPELL;
        };
        mc.player.playSound(sound, alertVolume.get().floatValue(), 1.0f);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Bloom & Pulse Rendering
    // ═══════════════════════════════════════════════════════════════════════════

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int layers = glowLayers.get();
        double spread = glowSpread.get();
        int baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            int layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double)(i - 1) / layers)));
            event.renderer.box(
                box.expand(expansion),
                withAlpha(color, layerAlpha),
                withAlpha(color, 0),
                ShapeMode.Sides, 0
            );
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

    private void renderEntity(Render3DEvent event, boolean isSpectral, boolean isPulse, boolean isEnabled, boolean renderBeam, List<? extends net.minecraft.entity.Entity> entities, SettingColor color) {
        if (!isEnabled || entities.isEmpty()) return;

        double beamSize = beamWidth.get() / 100.0;
        for (net.minecraft.entity.Entity entity : entities) {
            if (!entity.isAlive()) continue;
            Box box = entity.getBoundingBox();
            Vec3d pos = entity.getPos();
            Box beamBox = renderBeam ? new Box(
                pos.x - beamSize, pos.y, pos.z - beamSize,
                pos.x + beamSize, mc.world.getHeight(), pos.z + beamSize
            ) : null;

            if (isSpectral) {
                event.renderer.box(box, withAlpha(color, 0), withAlpha(color, 200), ShapeMode.Lines, 0);
                if (renderBeam) event.renderer.box(beamBox, withAlpha(color, 20), withAlpha(color, 180), ShapeMode.Both, 0);
            } else if (isPulse) {
                renderPulseBox(event, box, color);
                if (renderBeam) renderPulseBox(event, beamBox, color);
            } else {
                renderGlowLayers(event, box, color);
                event.renderer.box(box, withAlpha(color, 0), color, ShapeMode.Lines, 0);
                if (renderBeam) {
                    renderGlowLayers(event, beamBox, color);
                    event.renderer.box(beamBox, withAlpha(color, 60), color, ShapeMode.Both, 0);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utility Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean validateBlockType(Block block, TargetType type) {
        return switch (type) {
            case CONTAINER, SECRET_CONTAINER -> block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL;
        };
    }

    private SettingColor getColor(TargetType type) {
        return switch (type) {
            case CONTAINER -> trackContainers.get() ? containerColor.get() : null;
            case SECRET_CONTAINER -> trackSecretContainers.get() ? secretContainerColor.get() : null;
        };
    }

    private void pruneBlockTargets() {
        if (mc.world == null || mc.player == null) return;
        Set<BlockPos> toRemove = new HashSet<>();
        for (Map.Entry<BlockPos, TargetType> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;

            if (mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                Block currentBlock = mc.world.getBlockState(pos).getBlock();
                if (mc.world.getBlockState(pos).isAir() || !validateBlockType(currentBlock, entry.getValue())) {
                    toRemove.add(pos);
                }
            } else {
                toRemove.add(pos);
                scannedChunks.remove(new ChunkPos(chunkX, chunkZ));
            }
        }
        for (BlockPos pos : toRemove) {
            targets.remove(pos);
        }
    }

    private void cleanupDistantTargets(BlockPos playerPos) {
        int r = range.get();
        int pChunkX = playerPos.getX() >> 4;
        int pChunkZ = playerPos.getZ() >> 4;

        targets.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            int dx = (pos.getX() >> 4) - pChunkX;
            int dz = (pos.getZ() >> 4) - pChunkZ;
            if (dx * dx + dz * dz > r * r) {
                scannedChunks.remove(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
                return true;
            }
            return false;
        });
    }

    private boolean performSafetyChecks() {
        if (!autoDisableOnLowHealth.get()) return false;
        boolean hasTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
            || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
        if (hasTotem && mc.player.getHealth() <= 6) { 
            error("Health is critical, disabling to prevent totem pop.");
            toggle();
            return true;
        }
        return false;
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    private int toArgb(SettingColor c) {
        return (c.a << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }

}