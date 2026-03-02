package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class RocketPilot extends Module {

    // ─── Enum ────────────────────────────────────────────────────────────────────
    public enum FlightMode { Normal, Oscillation, Pitch40 }
    public enum FlightPattern { None, Grid, Circle, ZigZag, }
    // ─── Constants ───────────────────────────────────────────────────────────────
    private static final int   TAKEOFF_GRACE_TICKS        = 40;
    private static final float ELYTRA_LOW_PERCENT         = 5.0f;
    private static final int   ELYTRA_MIN_SWAP_DUR        = 50;
    private static final long  COLLISION_ROCKET_COOLDOWN  = 200L;

    // ─── Setting Groups ───────────────────────────────────────────────────────────
    private final SettingGroup sgFlight       = settings.createGroup("Flight");
    private final SettingGroup sgPitch40      = settings.createGroup("Pitch40");
    private final SettingGroup sgOscillation  = settings.createGroup("Oscillation");
    private final SettingGroup sgPatterns     = settings.createGroup("Patterns");
    private final SettingGroup sgDrunk        = settings.createGroup("DrunkPilot");
    private final SettingGroup sgFlightSafety = settings.createGroup("Flight Safety");
    private final SettingGroup sgPlayerSafety = settings.createGroup("Player Safety");

    // ─── Flight Settings ─────────────────────────────────────────────────────────
    public final Setting<Boolean> useTargetY = sgFlight.add(new BoolSetting.Builder()
        .name("use-target-y")
        .description("Whether to maintain a specific Y level.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> targetY = sgFlight.add(new DoubleSetting.Builder()
        .name("target-y")
        .description("The Y level to maintain.")
        .defaultValue(120.0)
        .min(-64).max(320)
        .sliderRange(0, 256)
        .visible(useTargetY::get)
        .build()
    );

    public final Setting<Double> flightTolerance = sgFlight.add(new DoubleSetting.Builder()
        .name("flight-tolerance")
        .description("Allowable drop below target Y before climbing.")
        .defaultValue(2.0)
        .min(0.5).max(10.0)
        .sliderRange(1.0, 5.0)
        .build()
    );

    private final Setting<Boolean> autoTakeoff = sgFlight.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Automatically jump and fire a rocket to start elytra flight.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableOnLand = sgFlight.add(new BoolSetting.Builder()
        .name("disable-on-land")
        .description("Automatically disable the module when you land.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> rocketDelay = sgFlight.add(new IntSetting.Builder()
        .name("rocket-delay")
        .description("Delay in milliseconds between rockets.")
        .defaultValue(2000)
        .min(100)
        .sliderRange(500, 5000)
        .build()
    );

    public final Setting<Boolean> silentRockets = sgFlight.add(new BoolSetting.Builder()
        .name("silent-rockets")
        .description("Suppresses the hand swing animation when firing rockets.")
        .defaultValue(true)
        .build()
    );

    public final Setting<FlightMode> flightMode = sgFlight.add(new EnumSetting.Builder<FlightMode>()
        .name("flight-mode")
        .description("The primary flight mode for pitch control.")
        .defaultValue(FlightMode.Normal)
        .onChanged(v -> {
            if (!isActive() || mc.world == null) return;
            // Reset pattern state when switching modes so origin is re-anchored cleanly
            resetPatternState();
            switch (v) {
                case Oscillation -> info("Oscillation mode enabled.");
                case Pitch40     -> info("Pitch40 mode enabled.");
                default          -> info("Normal flight mode enabled.");
            }
        })
        .build()
    );

    public final Setting<Double> pitchSmoothing = sgFlight.add(new DoubleSetting.Builder()
        .name("pitch-smoothing")
        .description("How smoothly pitch changes in Normal and Pattern modes (lower = smoother).")
        .defaultValue(0.15)
        .min(0.01).max(1.0)
        .sliderRange(0.05, 0.5)
        .visible(() -> flightMode.get() == FlightMode.Normal)
        .build()
    );

    // ─── Pitch40 Settings ────────────────────────────────────────────────────────
    private final Setting<Double> pitch40UpperY = sgPitch40.add(new DoubleSetting.Builder()
        .name("upper-y")
        .description("Upper Y-level ceiling; stop climbing above this.")
        .defaultValue(120.0)
        .min(-64).max(320)
        .sliderRange(0, 256)
        .visible(() -> flightMode.get() == FlightMode.Pitch40)
        .build()
    );

    private final Setting<Double> pitch40LowerY = sgPitch40.add(new DoubleSetting.Builder()
        .name("lower-y")
        .description("Lower Y-level floor; start climbing below this.")
        .defaultValue(110.0)
        .min(-64).max(320)
        .sliderRange(0, 256)
        .visible(() -> flightMode.get() == FlightMode.Pitch40)
        .build()
    );

    private final Setting<Double> pitch40Smoothing = sgPitch40.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("How smoothly to adjust pitch in Pitch40 mode.")
        .defaultValue(0.05)
        .min(0.01).max(1.0)
        .visible(() -> flightMode.get() == FlightMode.Pitch40)
        .build()
    );

    private final Setting<Integer> pitch40BelowMinDelay = sgPitch40.add(new IntSetting.Builder()
        .name("below-min-delay")
        .description("Time in ms to remain below lower-y before firing rockets.")
        .defaultValue(8000)
        .min(1000)
        .sliderRange(1000, 10000)
        .visible(() -> flightMode.get() == FlightMode.Pitch40)
        .build()
    );

    // ─── Oscillation Settings ────────────────────────────────────────────────────
    public final Setting<Double> oscillationSpeed = sgOscillation.add(new DoubleSetting.Builder()
        .name("oscillation-speed")
        .description("Speed of the pitch wave cycle (higher = faster).")
        .defaultValue(0.08)
        .min(0.01).max(0.5)
        .sliderRange(0.02, 0.2)
        .visible(() -> flightMode.get() == FlightMode.Oscillation)
        .build()
    );

    private final Setting<Integer> oscillationRocketDelay = sgOscillation.add(new IntSetting.Builder()
        .name("oscillation-rocket-delay")
        .description("Minimum delay between rockets in oscillation mode (ms).")
        .defaultValue(350)
        .min(0)
        .visible(() -> flightMode.get() == FlightMode.Oscillation)
        .build()
    );

    private final Setting<Boolean> oscillationRockets = sgOscillation.add(new BoolSetting.Builder()
        .name("oscillation-rockets")
        .description("Fire rockets at the peak of the upward pitch cycle.")
        .defaultValue(true)
        .visible(() -> flightMode.get() == FlightMode.Oscillation)
        .build()
    );

    // ─── Pattern Settings ────────────────────────────────────────────────────────
    public final Setting<FlightPattern> flightPattern = sgPatterns.add(new EnumSetting.Builder<FlightPattern>()
        .name("flight-pattern")
        .description("The pattern to fly in. Overrides yaw control.")
        .defaultValue(FlightPattern.None)
        .visible(() -> true)
        .build()
    );

    private final Setting<Keybind> pauseKey = sgPatterns.add(new KeybindSetting.Builder()
        .name("pause-key")
        .description("Pauses/resumes the current flight pattern.")
        .defaultValue(Keybind.none())
        .action(this::togglePause)
        .visible(() -> isPatternMode())
        .build()
    );

    private final Setting<Double> patternTurnSpeed = sgPatterns.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("How quickly to yaw toward pattern waypoints.")
        .defaultValue(0.1)
        .min(0.01).max(1.0)
        .sliderRange(0.05, 0.5)
        .visible(() -> isPatternMode())
        .build()
    );

    private final Setting<Integer> waypointReachRadius = sgPatterns.add(new IntSetting.Builder()
        .name("waypoint-reach-radius")
        .description("Horizontal distance (blocks) to a waypoint before advancing.")
        .defaultValue(30)
        .min(5)
        .sliderRange(10, 100)
        .visible(() -> isPatternMode())
        .build()
    );

    private final Setting<Integer> gridSpacing = sgPatterns.add(new IntSetting.Builder()
        .name("grid-spacing")
        .description("Distance between grid lines in chunks.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 32)
        .visible(() -> flightPattern.get() == FlightPattern.Grid)
        .build()
    );

    private final Setting<Integer> circleSegments = sgPatterns.add(new IntSetting.Builder()
        .name("circle-segments")
        .description("Number of waypoints per full spiral rotation.")
        .defaultValue(32)
        .min(4)
        .sliderRange(8, 128)
        .visible(() -> flightPattern.get() == FlightPattern.Circle)
        .build()
    );

    private final Setting<Integer> circleExpansion = sgPatterns.add(new IntSetting.Builder()
        .name("circle-expansion")
        .description("How many chunks the radius increases per rotation.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 16)
        .visible(() -> flightPattern.get() == FlightPattern.Circle)
        .build()
    );

    private final Setting<Integer> zigzagLegLength = sgPatterns.add(new IntSetting.Builder()
        .name("zigzag-leg-length")
        .description("Length of each zigzag leg in chunks.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 50)
        .visible(() -> flightPattern.get() == FlightPattern.ZigZag)
        .build()
    );

    private final Setting<Double> zigzagAngle = sgPatterns.add(new DoubleSetting.Builder()
        .name("zigzag-angle")
        .description("Turn angle at each ZigZag corner (degrees from forward).")
        .defaultValue(45.0)
        .min(10.0).max(80.0)
        .sliderRange(10.0, 80.0)
        .visible(() -> flightPattern.get() == FlightPattern.ZigZag)
        .build()
    );

    // ─── Drunk Pilot Settings ────────────────────────────────────────────────────
    public final Setting<Boolean> drunkMode = sgDrunk.add(new BoolSetting.Builder()
        .name("drunk-mode")
        .description("Randomly changes facing direction.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> drunkInterval = sgDrunk.add(new IntSetting.Builder()
        .name("change-interval")
        .description("Ticks between direction changes.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .visible(drunkMode::get)
        .build()
    );

    private final Setting<Double> drunkIntensity = sgDrunk.add(new DoubleSetting.Builder()
        .name("intensity")
        .description("Maximum yaw change per update (degrees).")
        .defaultValue(120.0)
        .min(1.0).max(180.0)
        .sliderRange(50.0, 180.0)
        .visible(drunkMode::get)
        .build()
    );

    private final Setting<Boolean> positiveCoordsOnly = sgDrunk.add(new BoolSetting.Builder()
        .name("positive-coords-only")
        .description("Biases heading away from origin — deeper into the player's current world quadrant.")
        .defaultValue(false)
        .visible(drunkMode::get)
        .build()
    );

    private final Setting<Double> drunkSmoothing = sgDrunk.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("How smoothly to rotate to the new heading (lower = smoother).")
        .defaultValue(0.05)
        .min(0.01).max(1.0)
        .visible(drunkMode::get)
        .build()
    );

    // ─── Flight Safety Settings ───────────────────────────────────────────────────
    private final Setting<Boolean> collisionAvoidance = sgFlightSafety.add(new BoolSetting.Builder()
        .name("collision-avoidance")
        .description("Attempts to avoid flying straight into walls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> avoidanceDistance = sgFlightSafety.add(new IntSetting.Builder()
        .name("avoidance-distance")
        .description("How far ahead to look for obstacles (blocks).")
        .defaultValue(10)
        .min(3)
        .sliderRange(5, 20)
        .visible(collisionAvoidance::get)
        .build()
    );

    private final Setting<Boolean> safeLanding = sgFlightSafety.add(new BoolSetting.Builder()
        .name("safe-landing")
        .description("Automatically levels out when near the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> landingHeight = sgFlightSafety.add(new IntSetting.Builder()
        .name("landing-height")
        .description("Height above ground to trigger safe landing (blocks).")
        .defaultValue(20)
        .min(5)
        .sliderRange(10, 50)
        .visible(safeLanding::get)
        .build()
    );

    private final Setting<Boolean> limitRotationSpeed = sgFlightSafety.add(new BoolSetting.Builder()
        .name("limit-rotation-speed")
        .description("Caps rotation speed per tick to reduce anti-cheat flags.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxRotationPerTick = sgFlightSafety.add(new DoubleSetting.Builder()
        .name("max-rotation-per-tick")
        .description("Maximum degrees to rotate per tick.")
        .defaultValue(20.0)
        .min(1.0).max(90.0)
        .sliderRange(5.0, 45.0)
        .visible(limitRotationSpeed::get)
        .build()
    );

    public final Setting<Integer> minRocketsWarning = sgFlightSafety.add(new IntSetting.Builder()
        .name("min-rockets-warning")
        .description("Warn when rocket count drops to this level or below.")
        .defaultValue(16)
        .min(0)
        .sliderRange(5, 64)
        .build()
    );

    private final Setting<Boolean> autoLandOnLowRockets = sgFlightSafety.add(new BoolSetting.Builder()
        .name("auto-land-on-low-rockets")
        .description("Initiate a safe landing when rockets are critically low.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> autoLandThreshold = sgFlightSafety.add(new IntSetting.Builder()
        .name("auto-land-threshold")
        .description("Rocket count that triggers auto-landing.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .visible(autoLandOnLowRockets::get)
        .build()
    );

    // ─── Player Safety Settings ───────────────────────────────────────────────────
    private final Setting<Boolean> autoDisableOnLowHealth = sgPlayerSafety.add(new BoolSetting.Builder()
        .name("auto-disable-on-low-health")
        .description("Disables the module if health is critically low while a totem is equipped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lowHealthThreshold = sgPlayerSafety.add(new IntSetting.Builder()
        .name("low-health-threshold")
        .description("Health level (hearts) to trigger auto-disable.")
        .defaultValue(3)
        .min(1).max(10)
        .sliderRange(1, 5)
        .visible(autoDisableOnLowHealth::get)
        .build()
    );

    private final Setting<Boolean> disconnectOnTotemPop = sgPlayerSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-totem-pop")
        .description("Disconnect from the server if a totem of undying is consumed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> disconnectAfterEmergencyLanding = sgPlayerSafety.add(new BoolSetting.Builder()
        .name("disconnect-after-emergency-landing")
        .description("If elytra is critically low and no replacement is found, land then disconnect.")
        .defaultValue(true)
        .build()
    );

    // ─── Internal State ───────────────────────────────────────────────────────────
    public  long    lastRocketTime           = 0;
    private boolean needsTakeoffRocket       = false;
    private boolean ascentMode               = false;
    private boolean pitch40Climbing          = false;
    private boolean pitch40Rocketing         = false;
    private long    pitch40BelowMinStartTime = -1;   // -1 = not tracking

    private float   targetPitch              = 0;
    private int     waveTicks                = 0;
    private int     drunkTimer               = 0;
    private float   targetDrunkYaw           = 0;
    private int     currentDrunkDuration     = 0;
    private boolean rocketsWarningSent       = false;
    private int     totemPops                = 0;
    private boolean emergencyLanding         = false;
    private int     takeoffTimer             = 0;

    // Pattern flight state
    private boolean paused         = false;
    private Vec3d   origin         = null;
    private Vec3d   currentTarget  = null;

    // Grid state
    private int gridStep       = 1;
    private int gridStepsInLeg = 0;
    private int gridDirection  = 0;  // 0:E, 1:N, 2:W, 3:S

    // ZigZag state
    private float   zigzagCurrentYaw = 0;
    private boolean zigzagTurnRight  = true;
    private boolean zigzagFirstLeg   = true;

    // Circle state
    private double circleAngle = 0;

    // ─── Constructor ─────────────────────────────────────────────────────────────
    public RocketPilot() {
        super(HuntingUtilities.CATEGORY, "rocket-pilot",
            "Automatic elytra + rocket flight with height maintenance, auto-takeoff, and pattern flight.");
    }

    // ─── Utilities ───────────────────────────────────────────────────────────────
    private boolean isPatternMode() {
        return flightPattern.get() != FlightPattern.None;
    }

    private void togglePause() {
        if (!isPatternMode()) return;
        paused = !paused;
        info("Pattern flight %s.", paused ? "paused" : "resumed");
    }

    private void resetPatternState() {
        paused         = false;
        origin         = null;
        currentTarget  = null;
        gridStep       = 1;
        gridStepsInLeg = 0;
        gridDirection  = 0;
        zigzagCurrentYaw = 0;
        zigzagTurnRight  = true;
        zigzagFirstLeg   = true;
        circleAngle      = 0;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────────
    @Override
    public void onActivate() {
        lastRocketTime           = 0;
        needsTakeoffRocket       = false;
        waveTicks                = 0;
        drunkTimer               = 0;
        currentDrunkDuration     = 0;
        ascentMode               = false;
        pitch40Climbing          = false;
        pitch40Rocketing         = false;
        pitch40BelowMinStartTime = -1;
        rocketsWarningSent       = false;
        emergencyLanding         = false;
        takeoffTimer             = 0;

        resetPatternState();

        if (mc.player == null || mc.world == null) { toggle(); return; }

        totemPops      = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
        targetPitch    = mc.player.getPitch();
        targetDrunkYaw = mc.player.getYaw();

        if (useTargetY.get()) info("Targeting Y level %.1f.", targetY.get());
        else                  info("Y-level targeting disabled.");

        if (mc.player.isGliding()) return;
        if (!autoTakeoff.get())    return;

        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) {
            error("No elytra equipped.");
            toggle();
            return;
        }
        if (countFireworks() == 0) {
            error("No fireworks in inventory.");
            toggle();
            return;
        }
        if (!isNearGround()) {
            warning("Not on ground — auto-takeoff skipped.");
            return;
        }

        targetPitch = -28.0f;
        mc.player.setPitch(targetPitch);
        mc.player.jump();
        needsTakeoffRocket = true;
        info("Taking off!");
    }

    @Override
    public void onDeactivate() {
        needsTakeoffRocket = false;
        resetPatternState();
    }

    // ─── Main Tick ────────────────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // ── Safety: totem pop disconnect ──
        if (disconnectOnTotemPop.get()) {
            int currentPops = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
            if (currentPops > totemPops) {
                error("Totem popped! Disconnecting...");
                disconnect("[RocketPilot] Disconnected on totem pop.");
                return;
            }
        }

        // ── Safety: low health with totem ──
        if (autoDisableOnLowHealth.get()) {
            boolean hasTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                            || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
            if (hasTotem && mc.player.getHealth() <= lowHealthThreshold.get() * 2f) {
                error("Health critical (%.1f hp), disabling.", mc.player.getHealth());
                toggle();
                return;
            }
        }

        // ── Elytra presence check ──
        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) {
            error("Elytra missing — disabling.");
            toggle();
            return;
        }

        if (takeoffTimer > 0) takeoffTimer--;

        // ── Land detection ──
        if (disableOnLand.get() && mc.player.isOnGround() && !needsTakeoffRocket && takeoffTimer == 0) {
            info("Landed — disabling.");
            toggle();
            return;
        }

        replenishRockets();

        // ── Re-takeoff if on ground and below target ──
        if (isNearGround() && !mc.player.isGliding()
                && (!useTargetY.get() || mc.player.getY() < targetY.get())
                && autoTakeoff.get() && countFireworks() > 0 && !needsTakeoffRocket) {
            targetPitch = -28.0f;
            mc.player.setPitch(targetPitch);
            if (mc.player.isOnGround()) mc.player.jump();
            needsTakeoffRocket = true;
            info("Re-launching!");
        }

        // ── Takeoff sequence ──
        if (needsTakeoffRocket) {
            handleTakeoff();
            return;
        }

        if (!mc.player.isGliding()) return;

        // ── Elytra durability / swap ──
        handleElytraHealth();

        // ── Rocket warning ──
        int rockets = countFireworks();
        if (rockets > 0 && rockets <= minRocketsWarning.get()) {
            if (!rocketsWarningSent) {
                warning("Low fireworks: only %d remaining!", rockets);
                rocketsWarningSent = true;
            }
        } else if (rockets > minRocketsWarning.get()) {
            rocketsWarningSent = false;
        }

        // ── Determine desired pitch (priority order) ──
        Float desiredPitch = null;

        // Priority 1: auto-land on low rockets
        if (autoLandOnLowRockets.get() && rockets <= autoLandThreshold.get()) {
            desiredPitch = handleLowRocketLanding();
        }

        // Priority 2: emergency landing (elytra critical, no swap)
        if (desiredPitch == null && emergencyLanding) {
            desiredPitch = handleEmergencyLanding();
            if (desiredPitch == null) return; // module was toggled inside
        }

        // Priority 3: collision avoidance
        if (desiredPitch == null && collisionAvoidance.get()) {
            desiredPitch = handleCollisionAvoidance();
        }

        // Priority 4: safe landing near ground
        if (desiredPitch == null && safeLanding.get() && getDistanceToGround() <= landingHeight.get()) {
            desiredPitch = MathHelper.lerp(0.1f, mc.player.getPitch(), -10.0f);
        }

        // Priority 5: normal flight modes
        if (desiredPitch == null) {
            if (isPatternMode()) {
                handlePatternYaw();
            }

            desiredPitch = switch (flightMode.get()) {
                case Pitch40             -> handlePitch40Mode();
                case Oscillation         -> handleOscillationMode();
                default                  -> handleNormalMode();
            };

            // Drunk mode adjusts yaw independently; runs alongside any pitch mode
            if (drunkMode.get() && !isPatternMode()) {
                double yDiff = Math.abs(mc.player.getY() - targetY.get());
                if (yDiff <= flightTolerance.get()) handleDrunkMode();
                else { targetDrunkYaw = mc.player.getYaw(); drunkTimer = 0; }
            }
        }

        applyPitch(desiredPitch);
    }

    // ─── Takeoff ─────────────────────────────────────────────────────────────────
    private void handleTakeoff() {
        if (mc.player.isOnGround()) {
            mc.player.jump();
        } else if (mc.player.isGliding()) {
            if (shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = System.currentTimeMillis();
            }
            needsTakeoffRocket = false;
            takeoffTimer = TAKEOFF_GRACE_TICKS;
        } else if (mc.player.getVelocity().y < -0.04 && mc.player.networkHandler != null) {
            mc.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING)
            );
        }
    }

    // ─── Elytra Health ───────────────────────────────────────────────────────────
    private void handleElytraHealth() {
        boolean assistantHandling = false;
        ElytraAssistant assistant = Modules.get().get(ElytraAssistant.class);
        if (assistant != null && assistant.isAutoSwapEnabled()) assistantHandling = true;

        if (!assistantHandling && getDurabilityPercent() <= ELYTRA_LOW_PERCENT) {
            Integer newDura = swapToFreshElytra();
            if (newDura != null) {
                info("Auto-swapped elytra (durability was low).");
                emergencyLanding = false;
            } else if (!emergencyLanding && disconnectAfterEmergencyLanding.get()) {
                emergencyLanding = true;
                warning("No replacement elytra found! Initiating emergency landing...");
            }
        }
    }

    // ─── Low Rocket Landing ───────────────────────────────────────────────────────
    private Float handleLowRocketLanding() {
        if (getDistanceToGround() <= landingHeight.get()) {
            float pitch = MathHelper.lerp(0.1f, mc.player.getPitch(), -10.0f);
            if (mc.player.isOnGround()) {
                info("Safe landing complete (low rockets).");
                toggle();
            }
            return pitch;
        }
        return MathHelper.lerp(0.05f, mc.player.getPitch(), 20.0f);
    }

    // ─── Emergency Landing ────────────────────────────────────────────────────────
    /** @return desired pitch, or null if the module was toggled inside */
    private Float handleEmergencyLanding() {
        if (mc.player.isOnGround()) {
            info("Emergency landing complete.");
            if (disconnectAfterEmergencyLanding.get()) {
                disconnect("[RocketPilot] Emergency landing complete — elytra critically low, no replacement found.");
            } else {
                toggle();
            }
            return null;
        }
        float current = mc.player.getPitch();
        return (getDistanceToGround() <= 8)
            ? MathHelper.lerp(0.15f, current, -20.0f)
            : MathHelper.lerp(0.05f, current,  25.0f);
    }

    // ─── Collision Avoidance ──────────────────────────────────────────────────────
    private Float handleCollisionAvoidance() {
        if (!mc.player.isGliding() || mc.player.getPitch() >= 30) return null;

        Vec3d camPos   = mc.player.getCameraPosVec(1.0f);
        Vec3d velocity = mc.player.getVelocity();
        if (velocity.lengthSquared() < 0.01) return null;

        Vec3d fwd    = velocity.normalize();
        Vec3d[] rays = { fwd, fwd.rotateY(0.5f), fwd.rotateY(-0.5f) };

        boolean obstacleDetected = false;
        for (Vec3d dir : rays) {
            BlockHitResult hit = mc.world.raycast(new RaycastContext(
                camPos, camPos.add(dir.multiply(avoidanceDistance.get())),
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player
            ));
            if (hit.getType() == HitResult.Type.BLOCK) { obstacleDetected = true; break; }
        }

        if (!obstacleDetected) return null;

        float currentPitch = mc.player.getPitch();
        double speed       = mc.player.getVelocity().horizontalLength();
        float pullUpStr    = (float) MathHelper.clamp(speed * 20, 20, 60);

        if (shouldFireRocket() && countFireworks() > 0 && mc.player.getVelocity().y < 0.2) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= COLLISION_ROCKET_COOLDOWN) {
                fireRocket();
                lastRocketTime = now;
            }
        }
        return MathHelper.lerp(0.3f, currentPitch, -pullUpStr);
    }

    // ─── Normal Mode ─────────────────────────────────────────────────────────────
    private Float handleNormalMode() {
        if (!useTargetY.get()) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.get()
                    && mc.player.getVelocity().y < 0.5
                    && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
            return null;
        }

        double currentY  = mc.player.getY();
        double target    = targetY.get();
        double tolerance = flightTolerance.get();
        double diff      = target - currentY;

        if      (diff > tolerance) ascentMode = true;
        else if (diff <= 0)        ascentMode = false;

        if (ascentMode) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.get()
                    && mc.player.getVelocity().y < 0.5
                    && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
        }

        // tanh S-curve: gentle angles for small diffs, approaches ±45° for large diffs
        float calculatedPitch;
        if (Math.abs(diff) < 0.5) {
            calculatedPitch = 0.0f;
        } else {
            calculatedPitch = (float) (-Math.tanh(diff / 10.0) * 45.0);
            calculatedPitch = MathHelper.clamp(calculatedPitch, -45.0f, 40.0f);
        }

        targetPitch = calculatedPitch;
        float smooth = pitchSmoothing.get().floatValue();
        return mc.player.getPitch() + (targetPitch - mc.player.getPitch()) * smooth;
    }

    // ─── Oscillation Mode ────────────────────────────────────────────────────────
    private Float handleOscillationMode() {
        waveTicks++;
        float calculatedPitch = (float) (40.0 * Math.sin(waveTicks * oscillationSpeed.get()));

        if (oscillationRockets.get() && countFireworks() > 0 && calculatedPitch < -38.0f) {
            long now = System.currentTimeMillis();
            if (shouldFireRocket() && now - lastRocketTime >= oscillationRocketDelay.get()) {
                fireRocket();
                lastRocketTime = now;
            }
        }
        return calculatedPitch;
    }

    // ─── Pitch40 Mode ────────────────────────────────────────────────────────────
    private Float handlePitch40Mode() {
        double currentY = mc.player.getY();
        double upperY   = pitch40UpperY.get();
        double lowerY   = pitch40LowerY.get();
        float  smooth   = pitch40Smoothing.get().floatValue();

        if      (currentY <= lowerY) { pitch40Climbing = true; }
        else if (currentY >= upperY) { pitch40Climbing = false; pitch40Rocketing = false; }

        if (currentY < lowerY) {
            if (pitch40BelowMinStartTime < 0) pitch40BelowMinStartTime = System.currentTimeMillis();
            if (System.currentTimeMillis() - pitch40BelowMinStartTime > pitch40BelowMinDelay.get()) {
                pitch40Rocketing = true;
            }
        } else {
            pitch40BelowMinStartTime = -1;
        }

        float pitch = pitch40Climbing
            ? MathHelper.lerp(smooth, mc.player.getPitch(), -40f)
            : MathHelper.lerp(smooth, mc.player.getPitch(),  40f);

        if (pitch40Rocketing) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.get() && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
        }
        return pitch;
    }

    // ─── Pattern Flight ───────────────────────────────────────────────────────────
    /**
     * Handles Grid, ZigZag, and Circle modes.
     * Returns a desired pitch like all other mode handlers so limitRotationSpeed
     * and applyPitch() are respected consistently.
     * Height maintenance uses the same tanh/target-Y logic as Normal mode.
     */
    private void handlePatternYaw() {
        if (paused) return;

        if (flightPattern.get() != FlightPattern.None) {
            // Anchor origin on first run
            if (origin == null) origin = mc.player.getPos();

            // Initialise or advance waypoint
            if (currentTarget == null) {
                calculateNextTarget();
            } else {
                double dx = currentTarget.x - mc.player.getX();
                double dz = currentTarget.z - mc.player.getZ();
                int radius = waypointReachRadius.get();
                if (dx * dx + dz * dz < (double)(radius * radius)) {
                    calculateNextTarget();
                }
            }

            // ── Yaw toward waypoint ──
            if (currentTarget != null) {
                double dx = currentTarget.x - mc.player.getX();
                double dz = currentTarget.z - mc.player.getZ();

                float targetYaw  = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float currentYaw = mc.player.getYaw();
                float diffYaw    = MathHelper.wrapDegrees(targetYaw - currentYaw);
                float yawChange  = diffYaw * patternTurnSpeed.get().floatValue();

                if (limitRotationSpeed.get()) {
                    yawChange = MathHelper.clamp(yawChange,
                        -maxRotationPerTick.get().floatValue(),
                         maxRotationPerTick.get().floatValue());
                }
                mc.player.setYaw(currentYaw + yawChange);
            }
        } else {
            currentTarget = null;
        }
    }

    /**
     * Calculates the next waypoint.
     *
     * FIX Grid:   gridStepsInLeg incremented AFTER placing waypoint, not before.
     *             First leg no longer immediately skips.
     *             gridStep increments after directions 0 and 2 (not 1 and 3) so the
     *             spiral expands correctly every two legs.
     *
     * FIX ZigZag: First leg flies straight (no turn). Subsequent legs alternate
     *             2× angle left/right so coverage stays symmetric.
     *
     * FIX Circle: First waypoint is placed at circleAngle=0 (straight ahead), then
     *             angle is advanced for the next call — no off-axis turn on start.
     */
    private void calculateNextTarget() {
        if (origin == null) origin = mc.player.getPos();

        double targetYValue = useTargetY.get() ? targetY.get() : mc.player.getY();
        double nextX, nextZ;
        FlightPattern pattern = flightPattern.get();

        if (pattern == FlightPattern.None) {
            currentTarget = null;
            return;
        }

        if (pattern == FlightPattern.Grid) {
            int spacing = gridSpacing.get() * 16;

            if (currentTarget == null) {
                // First leg: head South from origin
                gridDirection  = 3;
                gridStepsInLeg = 0;
                Vec3d offset = getGridDirectionOffset(gridDirection, spacing);
                nextX = origin.x + offset.x;
                nextZ = origin.z + offset.z;
                // Count this leg step AFTER placing the waypoint
                gridStepsInLeg = 1;
            } else {
                // Check if this leg is done
                if (gridStepsInLeg >= gridStep) {
                    gridDirection  = (gridDirection + 1) % 4;
                    gridStepsInLeg = 0;
                    // Expand the spiral after completing directions 0 (E) and 2 (W)
                    if (gridDirection == 0 || gridDirection == 2) gridStep++;
                }
                Vec3d offset = getGridDirectionOffset(gridDirection, spacing);
                nextX = currentTarget.x + offset.x;
                nextZ = currentTarget.z + offset.z;
                gridStepsInLeg++;
            }

        } else if (pattern == FlightPattern.ZigZag) {
            double legLength = zigzagLegLength.get() * 16.0;

            if (currentTarget == null) {
                // Initialise: capture player's current heading, first leg is straight
                zigzagCurrentYaw = mc.player.getYaw();
                zigzagTurnRight  = true;
                zigzagFirstLeg   = true;
            }

            if (zigzagFirstLeg) {
                // No turn on the very first leg — fly straight ahead
                zigzagFirstLeg = false;
            } else {
                // Alternate left/right with the full 2× angle
                double turnAmount = zigzagAngle.get() * 2.0;
                zigzagCurrentYaw = MathHelper.wrapDegrees(
                    zigzagCurrentYaw + (float)(zigzagTurnRight ? turnAmount : -turnAmount)
                );
                zigzagTurnRight = !zigzagTurnRight;
            }

            double radYaw    = Math.toRadians(zigzagCurrentYaw);
            Vec3d startPoint = (currentTarget != null) ? currentTarget : origin;
            nextX = startPoint.x + (-Math.sin(radYaw) * legLength);
            nextZ = startPoint.z + ( Math.cos(radYaw) * legLength);

        } else if (pattern == FlightPattern.Circle) {
            double angleStep       = 2.0 * Math.PI / circleSegments.get();
            double expansionBlocks = circleExpansion.get() * 16.0;
            double b               = expansionBlocks / (2.0 * Math.PI);
            double radius          = b * circleAngle;

            // Place waypoint at current angle, then advance for the next call
            nextX = origin.x + radius * Math.cos(circleAngle);
            nextZ = origin.z + radius * Math.sin(circleAngle);
            circleAngle += angleStep;

        } else {
            return;
        }

        currentTarget = new Vec3d(nextX, targetYValue, nextZ);
    }

    private Vec3d getGridDirectionOffset(int dir, int dist) {
        return switch (dir) {
            case 0 -> new Vec3d( dist, 0,    0);  // East  (+X)
            case 1 -> new Vec3d(   0, 0, -dist);  // North (-Z)
            case 2 -> new Vec3d(-dist, 0,    0);  // West  (-X)
            case 3 -> new Vec3d(   0, 0,  dist);  // South (+Z)
            default -> Vec3d.ZERO;
        };
    }

    // ─── Drunk Mode ──────────────────────────────────────────────────────────────
    private void handleDrunkMode() {
        if (drunkTimer++ >= currentDrunkDuration) {
            float intensity = drunkIntensity.get().floatValue();

            if (positiveCoordsOnly.get()) {
                // Minecraft yaw: 0°=South(+Z), -90°=East(+X), 90°=West(-X), ±180°=North(-Z)
                double px = mc.player.getX();
                double pz = mc.player.getZ();
                float minYaw, maxYaw;
                if      (px >= 0 && pz >= 0) { minYaw = -90f; maxYaw =   0f; } // SE (+X, +Z)
                else if (px <  0 && pz >= 0) { minYaw =   0f; maxYaw =  90f; } // SW (-X, +Z)
                else if (px <  0)            { minYaw =  90f; maxYaw = 180f; } // NW (-X, -Z)
                else                         { minYaw = -180f; maxYaw = -90f;} // NE (+X, -Z)
                targetDrunkYaw = minYaw + (float)(Math.random() * (maxYaw - minYaw));
            } else {
                targetDrunkYaw = mc.player.getYaw()
                    + (float)((Math.random() - 0.5) * 2.0 * intensity);
            }

            drunkTimer = 0;
            currentDrunkDuration = drunkInterval.get() + (int)(Math.random() * 10);
        }

        float currentYaw = mc.player.getYaw();
        float diffYaw    = MathHelper.wrapDegrees(targetDrunkYaw - currentYaw);
        float change     = diffYaw * drunkSmoothing.get().floatValue();

        if (limitRotationSpeed.get()) {
            float max = maxRotationPerTick.get().floatValue();
            change = MathHelper.clamp(change, -max, max);
        }
        mc.player.setYaw(currentYaw + change);
    }

    // ─── Apply Pitch ─────────────────────────────────────────────────────────────
    private void applyPitch(Float desiredPitch) {
        if (desiredPitch == null) return;
        float current = mc.player.getPitch();
        if (limitRotationSpeed.get()) {
            float max  = maxRotationPerTick.get().floatValue();
            float diff = MathHelper.clamp(desiredPitch - current, -max, max);
            mc.player.setPitch(current + diff);
        } else {
            mc.player.setPitch(desiredPitch);
        }
    }

    // ─── Public Accessors ────────────────────────────────────────────────────────
    public boolean shouldFireRocket() {
        if (mc.player == null) return false;
        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) return false;
        if (Math.abs(mc.player.getPitch()) > 70) return false;
        if (!needsTakeoffRocket && mc.player.getVelocity().horizontalLength() < 0.3) return false;
        return elytra.getDamage() < elytra.getMaxDamage() - 1;
    }

    public double getDurabilityPercent() {
        if (mc.player == null) return 100.0;
        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) return 100.0;
        return 100.0 * (elytra.getMaxDamage() - elytra.getDamage()) / (double) elytra.getMaxDamage();
    }

    public boolean isEmergencyLanding() { return emergencyLanding; }

    // ─── Private Helpers ─────────────────────────────────────────────────────────
    private void replenishRockets() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) return;
        }
        int invSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) { invSlot = i; break; }
        }
        if (invSlot == -1) return;

        int hotbarSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) { hotbarSlot = i; break; }
        }
        if (hotbarSlot == -1) hotbarSlot = mc.player.getInventory().selectedSlot;
        InvUtils.move().from(invSlot).toHotbar(hotbarSlot);
    }

    private int countFireworks() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(Items.FIREWORK_ROCKET)) count += s.getCount();
        }
        ItemStack offhand = mc.player.getOffHandStack();
        if (offhand.isOf(Items.FIREWORK_ROCKET)) count += offhand.getCount();
        return count;
    }

    /**
     * Swaps to the highest-durability elytra in inventory.
     * Passes raw inventory index (0–35) to InvUtils.move().from() — no slot remapping.
     */
    private Integer swapToFreshElytra() {
        int bestSlot = -1, bestDurability = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.ELYTRA)) {
                int dur = stack.getMaxDamage() - stack.getDamage();
                if (dur > bestDurability && dur > ELYTRA_MIN_SWAP_DUR) {
                    bestSlot = i;
                    bestDurability = dur;
                }
            }
        }
        if (bestSlot == -1) return null;
        InvUtils.move().from(bestSlot).toArmor(2);
        return bestDurability;
    }

    private boolean isNearGround() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.player.isOnGround()) return true;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int i = 1; i <= 3; i++) {
            pos.set(mc.player.getX(), mc.player.getY() - i, mc.player.getZ());
            if (mc.world.getBlockState(pos).isSolidBlock(mc.world, pos)) return true;
        }
        return false;
    }

    /** Starts at i=1 (one block below feet) to avoid a spurious return of 0. */
    private int getDistanceToGround() {
        if (mc.player == null || mc.world == null) return 999;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int max = landingHeight.get();
        for (int i = 1; i <= max; i++) {
            pos.set(mc.player.getX(), mc.player.getY() - i, mc.player.getZ());
            if (!mc.world.getBlockState(pos).isAir()) return i;
        }
        return 999;
    }

    private void fireRocket() {
        if (mc.player == null || mc.interactionManager == null) return;

        int selected   = mc.player.getInventory().selectedSlot;
        int rocketSlot = -1;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) { rocketSlot = i; break; }
        }
        if (rocketSlot == -1 && mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) rocketSlot = 40;
        if (rocketSlot == -1) return;

        if (rocketSlot < 9) {
            mc.player.getInventory().selectedSlot = rocketSlot;
            if (silentRockets.get()) {
                mc.player.networkHandler.sendPacket(
                    new net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket(
                        Hand.MAIN_HAND, 0,
                        mc.player.getYaw(), mc.player.getPitch()
                    )
                );
            } else {
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            }
            mc.player.getInventory().selectedSlot = selected;
        } else {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        }
    }

    /** Disconnects from the server with the given reason and toggles the module. */
    private void disconnect(String reason) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getConnection().disconnect(Text.literal(reason));
        }
        toggle();
    }
}