package com.example.addon.modules;

import com.example.addon.Tim;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

public class Timethrottle extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════════════════

    private static final double NORMAL_SPEED = 1.0;
    private static final int    GRACE_PERIOD = 100;
    private static final int    TICKS_PER_SECOND = 20;

    // ═══════════════════════════════════════════════════════════════════════════
    // ThrottleSource Interface
    // ═══════════════════════════════════════════════════════════════════════════

    private interface ThrottleSource {
        String name();
        double evaluate();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SafetyReason Enum
    // ═══════════════════════════════════════════════════════════════════════════

    public enum SafetyReason {
        NONE("None"),
        HURT("Took Damage"),
        HOSTILE_NEARBY("Hostile Nearby"),
        PLAYER_NEARBY("Player Nearby"),
        ATTACKING("Attacking");

        private final String title;
        SafetyReason(String title) { this.title = title; }
        public String getTitle() { return title; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral      = settings.getDefaultGroup();
    private final SettingGroup sgTps          = settings.createGroup("TPS");
    private final SettingGroup sgChunkLoading = settings.createGroup("Chunk Loading");
    private final SettingGroup sgPing         = settings.createGroup("Ping");
    private final SettingGroup sgSafety       = settings.createGroup("Safety");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> slowDownSmoothing = sgGeneral.add(new DoubleSetting.Builder()
        .name("slow-down-smoothing").description("How quickly speed drops when throttling. 0 = instant, higher = more gradual.")
        .defaultValue(0.1).min(0.0).max(0.99).sliderMax(0.5).build()
    );

    private final Setting<Double> speedUpSmoothing = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed-up-smoothing").description("How quickly speed recovers after throttling. 0 = instant, higher = more gradual. Higher values give chunks more time to catch up.")
        .defaultValue(0.4).min(0.0).max(0.99).sliderMax(0.5).build()
    );

    private final Setting<Double> absoluteMinSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("absolute-min-speed").description("The hard floor for game speed.")
        .defaultValue(0.15).min(0.05).max(0.5).sliderMax(0.5).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — TPS
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> targetTps = sgTps.add(new DoubleSetting.Builder()
        .name("target-tps").description("TPS above which no throttling is applied.")
        .defaultValue(19.0).min(1).max(20).sliderMax(20).build()
    );

    private final Setting<Double> minTps = sgTps.add(new DoubleSetting.Builder()
        .name("min-tps").description("TPS at which the slowest speed is applied.")
        .defaultValue(10.0).min(1).max(20).sliderMax(20).build()
    );

    private final Setting<Double> tpsMinSpeed = sgTps.add(new DoubleSetting.Builder()
        .name("min-speed").description("Speed multiplier applied when TPS is at or below min-tps.")
        .defaultValue(0.5).min(0.1).max(1.0).sliderMax(1.0).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Chunk Loading
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> chunkThrottle = sgChunkLoading.add(new BoolSetting.Builder()
        .name("chunk-throttle").description("Slow down when chunks are missing to force them to load.")
        .defaultValue(true).build()
    );

    private final Setting<Double> chunkLoadSlowdown = sgChunkLoading.add(new DoubleSetting.Builder()
        .name("chunk-min-speed").description("Speed to lock to when max-throttle is reached. (0.7 = 70%)")
        .defaultValue(0.7).min(0.1).max(1.0).sliderMax(1.0).visible(chunkThrottle::get).build()
    );

    // --- Stall Detection (Moved UP to fix forward reference) ---
    private final Setting<Boolean> stallDetection = sgChunkLoading.add(new BoolSetting.Builder()
        .name("stall-detection").description("Give up early if chunks aren't actually loading (stalled).")
        .defaultValue(true).visible(chunkThrottle::get).build()
    );

    private final Setting<Integer> stallTimeout = sgChunkLoading.add(new IntSetting.Builder()
        .name("stall-timeout").description("Seconds without chunk-loading progress before giving up.")
        .defaultValue(8).min(1).sliderMax(60)
        .visible(() -> chunkThrottle.get() && stallDetection.get()).build()
    );

    // --- Give-up / Timeout Escape ---
    private final Setting<Integer> maxThrottleTime = sgChunkLoading.add(new IntSetting.Builder()
        .name("max-throttle-time").description("Max seconds of continuous chunk-throttling before giving up and running at normal speed. 0 = disabled.")
        .defaultValue(15).min(0).sliderMax(120).visible(chunkThrottle::get).build()
    );

    private final Setting<Integer> giveUpCooldown = sgChunkLoading.add(new IntSetting.Builder()
        .name("give-up-cooldown").description("Seconds at normal speed after giving up before re-evaluating chunks.")
        .defaultValue(3).min(0).sliderMax(30)
        .visible(() -> chunkThrottle.get() && (maxThrottleTime.get() > 0 || stallDetection.get())).build()
    );

    // --- Chunk Count EMA Smoothing ---
    private final Setting<Double> chunkEmaFactor = sgChunkLoading.add(new DoubleSetting.Builder()
        .name("chunk-smoothing").description("Smooths the unloaded-chunk count to prevent jittery speed changes. 0 = no smoothing, higher = more smoothing.")
        .defaultValue(0.5).min(0.0).max(0.95).sliderMax(0.8).visible(chunkThrottle::get).build()
    );

    // --- Dimension Override ---
    private final Setting<Boolean> dimensionOverride = sgChunkLoading.add(new BoolSetting.Builder()
        .name("dimension-override").description("Use different chunk thresholds for Overworld, Nether, and End.")
        .defaultValue(true).visible(chunkThrottle::get).build()
    );

    // --- Dimension Specific ---
    private final Setting<Integer> owStart = sgChunkLoading.add(new IntSetting.Builder()
        .name("overworld-start").description("Missing chunks to start slowing down in the Overworld.")
        .defaultValue(10).min(1).sliderMax(100)
        .visible(() -> chunkThrottle.get() && dimensionOverride.get()).build()
    );

    private final Setting<Integer> owMax = sgChunkLoading.add(new IntSetting.Builder()
        .name("overworld-max").description("Missing chunks for max slowdown in the Overworld.")
        .defaultValue(80).min(10).sliderMax(500)
        .visible(() -> chunkThrottle.get() && dimensionOverride.get()).build()
    );

    private final Setting<Integer> netherStart = sgChunkLoading.add(new IntSetting.Builder()
        .name("nether-start").description("Missing chunks to start slowing down in the Nether.")
        .defaultValue(50).min(1).sliderMax(200)
        .visible(() -> chunkThrottle.get() && dimensionOverride.get()).build()
    );

    private final Setting<Integer> netherMax = sgChunkLoading.add(new IntSetting.Builder()
        .name("nether-max").description("Missing chunks for max slowdown in the Nether.")
        .defaultValue(200).min(20).sliderMax(1000)
        .visible(() -> chunkThrottle.get() && dimensionOverride.get()).build()
    );

    private final Setting<Integer> endStart = sgChunkLoading.add(new IntSetting.Builder()
        .name("end-start").description("Missing chunks to start slowing down in the End.")
        .defaultValue(5).min(1).sliderMax(100)
        .visible(() -> chunkThrottle.get() && dimensionOverride.get()).build()
    );

    private final Setting<Integer> endMax = sgChunkLoading.add(new IntSetting.Builder()
        .name("end-max").description("Missing chunks for max slowdown in the End.")
        .defaultValue(50).min(10).sliderMax(500)
        .visible(() -> chunkThrottle.get() && dimensionOverride.get()).build()
    );

    // --- Universal Thresholds ---
    private final Setting<Integer> chunkLoadThreshold = sgChunkLoading.add(new IntSetting.Builder()
        .name("start-throttle").description("Missing chunks to start slowing down.")
        .defaultValue(10).min(1).sliderMax(100)
        .visible(() -> chunkThrottle.get() && !dimensionOverride.get()).build()
    );

    private final Setting<Integer> maxChunkThreshold = sgChunkLoading.add(new IntSetting.Builder()
        .name("max-throttle").description("Missing chunks to hit the maximum slowdown.")
        .defaultValue(80).min(10).sliderMax(500)
        .visible(() -> chunkThrottle.get() && !dimensionOverride.get()).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Ping
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> pingThrottle = sgPing.add(new BoolSetting.Builder()
        .name("ping-throttle").description("Slow down when server ping is high.")
        .defaultValue(true).build()
    );

    private final Setting<Integer> pingThreshold = sgPing.add(new IntSetting.Builder()
        .name("ping-threshold").description("Ping (ms) above which throttling begins.")
        .defaultValue(150).min(20).sliderMin(20).sliderMax(500).visible(pingThrottle::get).build()
    );

    private final Setting<Integer> maxPing = sgPing.add(new IntSetting.Builder()
        .name("max-ping").description("Ping (ms) at which the slowest speed is applied.")
        .defaultValue(400).min(50).sliderMin(50).sliderMax(1000).visible(pingThrottle::get).build()
    );

    private final Setting<Double> pingMinSpeed = sgPing.add(new DoubleSetting.Builder()
        .name("ping-min-speed").description("Speed multiplier applied when ping is at or above max-ping.")
        .defaultValue(0.6).min(0.1).max(1.0).sliderMax(1.0).visible(pingThrottle::get).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Safety
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> combatSafety = sgSafety.add(new BoolSetting.Builder()
        .name("combat-safety").description("Disables throttling when in combat or near enemies.")
        .defaultValue(true).build()
    );

    private final Setting<Boolean> detectSwing = sgSafety.add(new BoolSetting.Builder()
        .name("detect-attacking").description("Resume normal speed when you swing your weapon.")
        .defaultValue(true).visible(combatSafety::get).build()
    );

    private final Setting<Integer> safetyRange = sgSafety.add(new IntSetting.Builder()
        .name("safety-range").description("Radius to check for hostile entities or players.")
        .defaultValue(15).min(0).sliderMax(32).visible(combatSafety::get).build()
    );

    private final Setting<Integer> safetyDuration = sgSafety.add(new IntSetting.Builder()
        .name("safety-duration").description("Ticks to keep throttling disabled after a safety trigger.")
        .defaultValue(60).min(0).sliderMax(200).visible(combatSafety::get).build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private double       currentSpeed     = NORMAL_SPEED;
    private int          safetyTicks      = 0;
    private int          graceTicks       = 0;
    private SafetyReason lastSafetyReason = SafetyReason.NONE;

    // Chunk tracking state
    private int     chunkThrottleTicks     = 0;  // How long chunk source has been throttling
    private int     chunkGiveUpTicks      = 0;  // Remaining give-up cooldown (normal speed period)
    private int     stallTicks            = 0;  // Ticks since chunks last made loading progress
    private int     lastRawUnloaded       = -1; // Previous raw unloaded count (for stall detection)
    private double  smoothedUnloaded      = -1; // EMA of unloaded chunk count
    private int     cachedUnloaded        = 0;  // Cached smoothed count for this tick
    private boolean cachedPlayerAreaLoaded = true;
    private boolean chunkDataValid        = false;

    // ═══════════════════════════════════════════════════════════════════════════
    // ThrottleSource instances
    // ═══════════════════════════════════════════════════════════════════════════

    private final ThrottleSource tpsSource = new ThrottleSource() {
        @Override public String name() { return "TPS"; }
        @Override public double evaluate() {
            double tps = TickRate.INSTANCE.getTickRate();
            if (tps >= targetTps.get()) return NORMAL_SPEED;
            if (tps <= minTps.get())    return tpsMinSpeed.get();
            return MathHelper.map(tps, minTps.get(), targetTps.get(), tpsMinSpeed.get(), NORMAL_SPEED);
        }
    };

    private final ThrottleSource chunkSource = new ThrottleSource() {
        @Override public String name() { return "Chunks"; }
        @Override public double evaluate() {
            if (!chunkThrottle.get()) return NORMAL_SPEED;
            // Give-up active: run at normal speed to give player a break
            if (chunkGiveUpTicks > 0) return NORMAL_SPEED;
            // Data not ready yet (first tick, world just loaded)
            if (!chunkDataValid) return NORMAL_SPEED;
            // Player area not fully loaded: don't throttle
            if (!cachedPlayerAreaLoaded) return NORMAL_SPEED;

            int startThr;
            int maxThr;

            if (dimensionOverride.get()) {
                if (mc.world.getRegistryKey() == World.NETHER) {
                    startThr = netherStart.get();
                    maxThr = netherMax.get();
                } else if (mc.world.getRegistryKey() == World.END) {
                    startThr = endStart.get();
                    maxThr = endMax.get();
                } else {
                    startThr = owStart.get();
                    maxThr = owMax.get();
                }
            } else {
                startThr = chunkLoadThreshold.get();
                maxThr = maxChunkThreshold.get();
            }

            if (cachedUnloaded <= startThr) return NORMAL_SPEED;
            if (cachedUnloaded >= maxThr) return chunkLoadSlowdown.get();
            return MathHelper.map(cachedUnloaded, startThr, maxThr, NORMAL_SPEED, chunkLoadSlowdown.get());
        }
    };

    private final ThrottleSource pingSource = new ThrottleSource() {
        @Override public String name() { return "Ping"; }
        @Override public double evaluate() {
            if (!pingThrottle.get()) return NORMAL_SPEED;
            int ping = getPlayerPing();
            if (ping <= pingThreshold.get()) return NORMAL_SPEED;
            if (ping >= maxPing.get())       return pingMinSpeed.get();
            return MathHelper.map(ping, pingThreshold.get(), maxPing.get(), NORMAL_SPEED, pingMinSpeed.get());
        }
    };

    private final ThrottleSource[] sources = { tpsSource, chunkSource, pingSource };

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor & Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    public Timethrottle() {
        super(Tim.CATEGORY, "time-throttle",
            "Automatically adjusts game speed based on server TPS, chunk loading, and ping.");
    }

    @Override
    public void onActivate() {
        currentSpeed          = NORMAL_SPEED;
        safetyTicks           = 0;
        graceTicks            = GRACE_PERIOD;
        lastSafetyReason      = SafetyReason.NONE;
        chunkThrottleTicks    = 0;
        chunkGiveUpTicks      = 0;
        stallTicks            = 0;
        lastRawUnloaded       = -1;
        smoothedUnloaded      = -1;
        chunkDataValid        = false;
        Modules.get().get(Timer.class).setOverride(NORMAL_SPEED);
    }

    @Override
    public void onDeactivate() { applySpeed(NORMAL_SPEED); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        currentSpeed          = NORMAL_SPEED;
        safetyTicks           = 0;
        graceTicks            = 0;
        lastSafetyReason      = SafetyReason.NONE;
        chunkThrottleTicks    = 0;
        chunkGiveUpTicks      = 0;
        stallTicks            = 0;
        lastRawUnloaded       = -1;
        smoothedUnloaded      = -1;
        chunkDataValid        = false;
        Modules.get().get(Timer.class).setOverride(NORMAL_SPEED);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        // Player's own chunk not loaded — don't interfere
        if (!mc.world.getChunkManager().isChunkLoaded(mc.player.getChunkPos().x, mc.player.getChunkPos().z)) {
            applySpeed(NORMAL_SPEED);
            return;
        }

        // Grace period after module activation / world join
        if (graceTicks > 0) {
            graceTicks--;
            applySpeed(NORMAL_SPEED);
            return;
        }

        // Update chunk tracking data (cached for this entire tick)
        updateChunkTracking();

        // Safety overrides (instant, no smoothing — combat needs immediate response)
        updateSafety();
        if (safetyTicks > 0) {
            safetyTicks--;
            applySpeed(NORMAL_SPEED);
            return;
        }
        lastSafetyReason = SafetyReason.NONE;

        // Compute desired speed from all sources
        double desired = computeDesiredSpeed();

        // Track how long chunk throttling has been active, and check for give-up
        double chunkSpeed = chunkSource.evaluate();
        if (chunkSpeed < NORMAL_SPEED - 0.01) {
            chunkThrottleTicks++;
            checkGiveUp();
        } else {
            chunkThrottleTicks = 0;
        }

        // Decrement give-up cooldown
        if (chunkGiveUpTicks > 0) chunkGiveUpTicks--;

        // Apply with asymmetric smoothing
        smoothAndApply(desired);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chunk Tracking — runs once per tick, caches results for all evaluations
    // ═══════════════════════════════════════════════════════════════════════════

    private void updateChunkTracking() {
        if (mc.world == null || mc.player == null) {
            chunkDataValid = false;
            return;
        }

        // Check 3×3 chunks around player (cheap, 9 lookups)
        int px = mc.player.getChunkPos().x;
        int pz = mc.player.getChunkPos().z;
        cachedPlayerAreaLoaded = true;
        outer:
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!mc.world.getChunkManager().isChunkLoaded(px + dx, pz + dz)) {
                    cachedPlayerAreaLoaded = false;
                    break outer;
                }
            }
        }

        // Skip expensive full count if player area isn't even loaded
        if (!cachedPlayerAreaLoaded) {
            chunkDataValid = true; // Valid but will return NORMAL_SPEED
            return;
        }

        // Count unloaded chunks in view distance
        int raw = countUnloadedChunks();

        // EMA smoothing — prevents jittery speed changes as player moves
        if (smoothedUnloaded < 0) {
            smoothedUnloaded = raw;
        } else {
            double factor = chunkEmaFactor.get();
            smoothedUnloaded = smoothedUnloaded * factor + raw * (1.0 - factor);
        }
        cachedUnloaded = (int) Math.round(smoothedUnloaded);

        // Stall detection — if chunk count decreased, progress was made
        if (lastRawUnloaded < 0 || raw < lastRawUnloaded) {
            stallTicks = 0; // Progress! Reset stall timer
        } else {
            stallTicks++;
        }
        lastRawUnloaded = raw;

        chunkDataValid = true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Give-Up Logic — escape from hopeless throttling
    // ═══════════════════════════════════════════════════════════════════════════

    private void checkGiveUp() {
        boolean shouldGiveUp = false;

        // Condition 1: Max throttle time reached — we've been slow for too long
        if (maxThrottleTime.get() > 0 && chunkThrottleTicks >= maxThrottleTime.get() * TICKS_PER_SECOND) {
            shouldGiveUp = true;
        }

        // Condition 2: Stall detected — chunks aren't loading at all, being slow won't help
        if (stallDetection.get() && stallTicks >= stallTimeout.get() * TICKS_PER_SECOND) {
            shouldGiveUp = true;
        }

        if (shouldGiveUp) {
            // Enter recovery: run at normal speed for the cooldown period
            chunkGiveUpTicks   = Math.max(giveUpCooldown.get(), 1) * TICKS_PER_SECOND;
            chunkThrottleTicks = 0;
            stallTicks         = 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Safety Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private void updateSafety() {
        if (!combatSafety.get()) return;
        SafetyReason reason = detectSafetyReason();
        if (reason != SafetyReason.NONE) {
            lastSafetyReason = reason;
            safetyTicks      = safetyDuration.get();
        }
    }

    private SafetyReason detectSafetyReason() {
        if (mc.player.hurtTime > 0) return SafetyReason.HURT;
        if (detectSwing.get() && mc.player.handSwingTicks > 0) return SafetyReason.ATTACKING;

        int range = safetyRange.get();
        if (range <= 0) return SafetyReason.NONE;

        Box box = mc.player.getBoundingBox().expand(range);

        if (!mc.world.getEntitiesByClass(HostileEntity.class, box, Entity::isAlive).isEmpty())
            return SafetyReason.HOSTILE_NEARBY;

        if (!mc.world.getEntitiesByClass(PlayerEntity.class, box, p -> p != mc.player && p.isAlive()).isEmpty())
            return SafetyReason.PLAYER_NEARBY;

        return SafetyReason.NONE;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Evaluation & Application
    // ═══════════════════════════════════════════════════════════════════════════

    private double computeDesiredSpeed() {
        double desired = NORMAL_SPEED;
        for (ThrottleSource source : sources) desired = Math.min(desired, source.evaluate());
        return Math.max(desired, absoluteMinSpeed.get());
    }

    private void smoothAndApply(double desired) {
        // Asymmetric smoothing:
        // - Slowing down: fast response (low smoothing) — get to throttle speed quickly
        // - Speeding up: gradual recovery (higher smoothing) — gives chunks time to catch up
        double smoothing = (desired < currentSpeed)
            ? slowDownSmoothing.get()
            : speedUpSmoothing.get();
        currentSpeed = MathHelper.lerp(1.0 - smoothing, currentSpeed, desired);
        applySpeed(currentSpeed);
    }

    private void applySpeed(double speed) {
        if (Double.isNaN(speed) || Double.isInfinite(speed) || speed <= 0.0) speed = NORMAL_SPEED;
        currentSpeed = speed;
        Modules.get().get(Timer.class).setOverride(speed);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public Accessors (For HUD)
    // ═══════════════════════════════════════════════════════════════════════════

    public double       getCurrentSpeed()     { return currentSpeed; }
    public boolean      isSafetyActive()      { return safetyTicks > 0; }
    public boolean      isChunkGiveUpActive() { return chunkGiveUpTicks > 0; }
    public SafetyReason getLastSafetyReason() { return lastSafetyReason; }
    public int          sourceCount()         { return sources.length; }
    public String       sourceName(int i)     { return (i >= 0 && i < sources.length) ? sources[i].name() : "?"; }
    public double       evaluateSource(int i) { return (i >= 0 && i < sources.length) ? sources[i].evaluate() : NORMAL_SPEED; }

    @Override
    public String getInfoString() {
        if (isSafetyActive()) return "SAFETY";
        if (chunkGiveUpTicks > 0) return "RECOVERY";
        return String.format("%.0f%%", getCurrentSpeed() * 100);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private int getPlayerPing() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry != null ? entry.getLatency() : 0;
    }

    private int countUnloadedChunks() {
        if (mc.world == null || mc.player == null) return 0;
        int unloaded     = 0;
        int viewDistance = mc.options.getClampedViewDistance();
        int cx           = mc.player.getChunkPos().x;
        int cz           = mc.player.getChunkPos().z;
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                if (!mc.world.getChunkManager().isChunkLoaded(cx + dx, cz + dz)) {
                    unloaded++;
                }
            }
        }
        return unloaded;
    }
}