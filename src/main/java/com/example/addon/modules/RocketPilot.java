package com.example.addon.modules;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.example.addon.Tim;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class RocketPilot extends Module {

    // ─── Enums ───────────────────────────────────────────────────────────────────
    public enum FlightMode { None, Normal, Pitch40, AltitudeBounce, Ebounce }

    public enum FlightPattern {
        Manual,
        Drunk,
        Grid,
        Circle,
        Hexagon,
        Triangle,
        ZigZag,
        FigureEight,
        Sweep
    }

    public enum DrunkBias { None, North, South, East, West, PositiveOnly, NegativeOnly, NegPos, PosNeg }

    public enum DrunkSpiralMode { None, Grid, Circle, Hexagon, Triangle }

    // ─── Constants ───────────────────────────────────────────────────────────────
    private static final int   TAKEOFF_GRACE_TICKS       = 40;
    private static final float ELYTRA_LOW_PERCENT        = 5.0f;
    private static final int   ELYTRA_MIN_SWAP_DUR       = 50;
    private static final long  COLLISION_ROCKET_COOLDOWN = 200L;

    // Ebounce Constants
    private static final double EBOUNCE_STOP = 0.2;
    private static final int EBOUNCE_GRID = 10;
    private static final int EBOUNCE_REACH = 5;
    private static final int EBOUNCE_LAUNCH = 3;
    private static final int EBOUNCE_WAIT = 20;
    private static final int EBOUNCE_WARMUP = 20;

    private final int[] dxs = {0, -1, -1, -1, 0, 1, 1, 1};
    private final int[] dzs = {1, 1, 0, -1, -1, -1, 0, 1};

    // Ebounce state
    private int ebouncePx, ebouncePz, ebounceDx, ebounceDz;
    private int ebounceSlow, ebounceWarm, ebounceJump;
    private boolean ebouncePass, ebounceStarted;

    // ─── Setting Groups ───────────────────────────────────────────────────────────
    private final SettingGroup sgFlight       = settings.createGroup("Flight");
    private final SettingGroup sgPitch40      = settings.createGroup("Pitch40");
    private final SettingGroup sgBounce       = settings.createGroup("Altitude Bounce");
    private final SettingGroup sgEbounce      = settings.createGroup("Ebounce");
    private final SettingGroup sgSweep        = settings.createGroup("Sweep Pattern");
    private final SettingGroup sgPatterns     = settings.createGroup("Patterns");
    private final SettingGroup sgDrunk        = settings.createGroup("DrunkPilot");
    private final SettingGroup sgFlightSafety = settings.createGroup("Flight Safety");
    private final SettingGroup sgPlayerSafety = settings.createGroup("Player Safety");

    // ─── Flight Settings ─────────────────────────────────────────────────────────
    public final Setting<FlightMode> flightMode = sgFlight.add(new EnumSetting.Builder<FlightMode>()
        .name("flight-mode")
        .description("The primary flight mode for pitch control.")
        .defaultValue(FlightMode.Normal)
        .onChanged(v -> {
            if (!isActive() || mc.world == null) return;
            resetPatternState();
            
            if (v != FlightMode.Ebounce) {
                mc.options.forwardKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                if (ebouncePass) {
                    BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
                    ebouncePass = false;
                }
            }

            switch (v) {
                case Pitch40        -> info("Pitch40 mode enabled.");
                case AltitudeBounce -> info("Altitude Bounce mode enabled.");
                case Ebounce        -> info("Ebounce mode enabled.");
                case None           -> info("Flight pitch control disabled.");
                default             -> info("Normal flight mode enabled.");
            }
        })
        .build()
    );

    public final Setting<Boolean> useTargetY = sgFlight.add(new BoolSetting.Builder()
        .name("use-target-y")
        .description("Whether to maintain a specific Y level.")
        .defaultValue(true)
        .visible(() -> flightMode.get() != FlightMode.Ebounce)
        .build()
    );

    public final Setting<Double> targetY = sgFlight.add(new DoubleSetting.Builder()
        .name("target-y")
        .description("The Y level to maintain.")
        .defaultValue(120.0)
        .min(-64).max(10000)
        .sliderRange(0, 10000)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && useTargetY.get())
        .build()
    );

    public final Setting<Double> flightTolerance = sgFlight.add(new DoubleSetting.Builder()
        .name("flight-tolerance")
        .description("Allowable drop below target Y before climbing.")
        .defaultValue(2.0)
        .min(0.5).max(10.0)
        .sliderRange(1.0, 5.0)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && useTargetY.get())
        .build()
    );

    public final Setting<Boolean> useFreeLookY = sgFlight.add(new BoolSetting.Builder()
        .name("use-freelook-y")
        .description("Render the camera at a specific Y level while flying.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> freeLookY = sgFlight.add(new DoubleSetting.Builder()
        .name("freelook-y")
        .description("The Y level to render the camera at.")
        .defaultValue(120.0)
        .min(-64).max(320)
        .sliderRange(0, 256)
        .visible(useFreeLookY::get)
        .build()
    );

    private final Setting<Keybind> toggleFreeLookY = sgFlight.add(new KeybindSetting.Builder()
        .name("toggle-freelook-y")
        .description("Key to toggle the freelook Y feature.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (mc.currentScreen != null) return;
            boolean newVal = !useFreeLookY.get();
            useFreeLookY.set(newVal);
            info("Freelook Y " + (newVal ? "enabled" : "disabled") + ".");
        })
        .build()
    );

    private final Setting<Boolean> autoTakeoff = sgFlight.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Automatically jump and fire a rocket to start elytra flight.")
        .defaultValue(true)
        .visible(() -> flightMode.get() != FlightMode.Ebounce)
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
        .visible(() -> flightMode.get() != FlightMode.Ebounce)
        .build()
    );

    public final Setting<Boolean> silentRockets = sgFlight.add(new BoolSetting.Builder()
        .name("silent-rockets")
        .description("Suppresses the hand swing animation when firing rockets.")
        .defaultValue(true)
        .visible(() -> flightMode.get() != FlightMode.Ebounce)
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

    // ─── Pattern Settings ─────────────────────────────────────────────────────────
    public final Setting<FlightPattern> flightPattern = sgPatterns.add(new EnumSetting.Builder<FlightPattern>()
        .name("flight-pattern")
        .description("The flight pattern to follow. Manual allows free mouse look.")
        .defaultValue(FlightPattern.Manual)
        .visible(() -> flightMode.get() != FlightMode.Ebounce)
        .onChanged(v -> { 
            resetPatternState();
            resetDrunkSpiralState();
        })
        .build()
    );

    // ─── Sweep Pattern Settings ──────────────────────────────────────────────────
    private final Setting<Integer> sweepWidth = sgSweep.add(new IntSetting.Builder()
        .name("sweep-width")
        .description("Total side-to-side distance in chunks.")
        .defaultValue(10).min(1).sliderRange(1, 50)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Sweep)
        .build()
    );

    private final Setting<Integer> sweepAdvance = sgSweep.add(new IntSetting.Builder()
        .name("sweep-advance")
        .description("Forward distance moved per sweep in chunks.")
        .defaultValue(2).min(1).sliderRange(1, 20)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Sweep)
        .build()
    );

    private final Setting<Double> sweepExpansionRate = sgSweep.add(new DoubleSetting.Builder()
        .name("sweep-expansion-rate")
        .description("Percentage increase in sweep width/advance per full cycle (e.g., 0.1 for 10% increase).")
        .defaultValue(0.0)
        .min(0.0).max(0.5)
        .sliderRange(0.0, 0.2)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Sweep)
        .build()
    );

    private final Setting<Double> sweepMaxFactor = sgSweep.add(new DoubleSetting.Builder()
        .name("sweep-max-factor")
        .description("Maximum multiplier for sweep width/advance (e.g., 2.0 for double the initial size).")
        .defaultValue(1.0)
        .min(1.0).max(5.0)
        .sliderRange(1.0, 3.0)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Sweep && sweepExpansionRate.get() > 0.0)
        .build()
    );

    private final Setting<Boolean> sweepAutoUpdate = sgSweep.add(new BoolSetting.Builder()
        .name("auto-update-origin")
        .description("Relocates the sweep pattern origin to your position if you manually fly too far from the current target.")
        .defaultValue(true)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Sweep)
        .build()
    );

    // ─── Altitude Bounce Settings ─────────────────────────────────────────────────
    private final Setting<Double> bounceClimbPitch = sgBounce.add(new DoubleSetting.Builder()
        .name("climb-pitch")
        .description("Pitch angle while climbing aggressively (negative = nose up).")
        .defaultValue(-35.0)
        .min(-60.0).max(-5.0)
        .sliderRange(-50.0, -10.0)
        .visible(() -> flightMode.get() == FlightMode.AltitudeBounce)
        .build()
    );

    private final Setting<Double> bounceGlidePitch = sgBounce.add(new DoubleSetting.Builder()
        .name("glide-pitch")
        .description("Pitch angle during the glide descent phase (positive = nose down).")
        .defaultValue(20.0)
        .min(5.0).max(60.0)
        .sliderRange(5.0, 45.0)
        .visible(() -> flightMode.get() == FlightMode.AltitudeBounce)
        .build()
    );

    private final Setting<Double> bouncePeakY = sgBounce.add(new DoubleSetting.Builder()
        .name("peak-y")
        .description("Y level to reach before cutting rockets and beginning the glide.")
        .defaultValue(130.0)
        .min(-64.0).max(10000.0)
        .sliderRange(64.0, 256.0)
        .visible(() -> flightMode.get() == FlightMode.AltitudeBounce)
        .build()
    );

    private final Setting<Double> bounceFloorY = sgBounce.add(new DoubleSetting.Builder()
        .name("floor-y")
        .description("Y level at which the glide ends and the climb begins again.")
        .defaultValue(100.0)
        .min(-64.0).max(320.0)
        .sliderRange(64.0, 256.0)
        .visible(() -> flightMode.get() == FlightMode.AltitudeBounce)
        .build()
    );

    private final Setting<Double> bouncePitchSmoothing = sgBounce.add(new DoubleSetting.Builder()
        .name("pitch-smoothing")
        .description("How smoothly to transition between climb and glide pitches.")
        .defaultValue(0.08)
        .min(0.01).max(1.0)
        .sliderRange(0.02, 0.3)
        .visible(() -> flightMode.get() == FlightMode.AltitudeBounce)
        .build()
    );

    // ─── Ebounce Settings ─────────────────────────────────────────────────────────
    private final Setting<Double> ebouncePitch = sgEbounce.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("The pitch used while elytra bouncing.")
        .defaultValue(72.4)
        .min(-90.0).max(90.0)
        .sliderRange(-90.0, 90.0)
        .visible(() -> flightMode.get() == FlightMode.Ebounce)
        .build()
    );

    private final Setting<Boolean> ebounceObstacle = sgEbounce.add(new BoolSetting.Builder()
        .name("obstacle-passer")
        .description("Uses Baritone to pass obstacles when movement stops.")
        .defaultValue(true)
        .visible(() -> flightMode.get() == FlightMode.Ebounce)
        .build()
    );

    private final Setting<Boolean> ebounceAvoid = sgEbounce.add(new BoolSetting.Builder()
        .name("avoid-collisions")
        .description("Uses raycasts to detect obstacles and avoid collisions.")
        .defaultValue(true)
        .visible(() -> flightMode.get() == FlightMode.Ebounce && ebounceObstacle.get())
        .build()
    );

    private final Setting<Integer> ebounceTicks = sgEbounce.add(new IntSetting.Builder()
        .name("collision-ticks")
        .description("How many movement ticks ahead to scan for obstacles.")
        .defaultValue(8)
        .min(5).sliderMax(10)
        .visible(() -> flightMode.get() == FlightMode.Ebounce && ebounceObstacle.get() && ebounceAvoid.get())
        .build()
    );

    // ─── Pattern Settings Continued ──────────────────────────────────────────────
    private final Setting<Keybind> pauseKey = sgPatterns.add(new KeybindSetting.Builder()
        .name("pause-key")
        .description("Pauses/resumes the current flight pattern or drunk spiral.")
        .defaultValue(Keybind.none())
        .action(this::togglePause)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && isPatternMode())
        .build()
    );

    private final Setting<Double> patternTurnSpeed = sgPatterns.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("How quickly to yaw toward pattern waypoints.")
        .defaultValue(0.1)
        .min(0.01).max(1.0)
        .sliderRange(0.05, 0.5)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() != FlightPattern.Manual && flightPattern.get() != FlightPattern.Drunk)
        .build()
    );

    private final Setting<Integer> waypointReachRadius = sgPatterns.add(new IntSetting.Builder()
        .name("waypoint-reach-radius")
        .description("Horizontal distance (blocks) to a waypoint before advancing.")
        .defaultValue(30)
        .min(5)
        .sliderRange(10, 100)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() != FlightPattern.Manual && flightPattern.get() != FlightPattern.Drunk)
        .build()
    );

    private final Setting<Integer> gridSpacing = sgPatterns.add(new IntSetting.Builder()
        .name("grid-spacing")
        .description("Distance between grid lines in chunks.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 32)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Grid)
        .build()
    );

    private final Setting<Integer> circleSegments = sgPatterns.add(new IntSetting.Builder()
        .name("circle-segments")
        .description("Number of waypoints per full spiral rotation.")
        .defaultValue(32)
        .min(4)
        .sliderRange(8, 128)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Circle)
        .build()
    );

    private final Setting<Integer> circleExpansion = sgPatterns.add(new IntSetting.Builder()
        .name("circle-expansion")
        .description("How many chunks the radius increases per rotation.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 16)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Circle)
        .build()
    );

    private final Setting<Integer> hexagonSideLength = sgPatterns.add(new IntSetting.Builder()
        .name("hexagon-side-length")
        .description("Side length of the hexagon in chunks.")
        .defaultValue(4).min(1).sliderRange(1, 32)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Hexagon)
        .build()
    );

    private final Setting<Integer> hexagonExpansion = sgPatterns.add(new IntSetting.Builder()
        .name("hexagon-expansion")
        .description("Chunks the hexagon side length grows per full rotation.")
        .defaultValue(2).min(1).sliderRange(1, 16)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Hexagon)
        .build()
    );

    private final Setting<Integer> triangleSideLength = sgPatterns.add(new IntSetting.Builder()
        .name("triangle-side-length")
        .description("Side length of the triangle in chunks.")
        .defaultValue(6).min(1).sliderRange(1, 32)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Triangle)
        .build()
    );

    private final Setting<Integer> triangleExpansion = sgPatterns.add(new IntSetting.Builder()
        .name("triangle-expansion")
        .description("Chunks the triangle side length grows per full rotation.")
        .defaultValue(3).min(1).sliderRange(1, 16)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Triangle)
        .build()
    );

    private final Setting<Integer> zigzagLegLength = sgPatterns.add(new IntSetting.Builder()
        .name("zigzag-leg-length")
        .description("Length of each zigzag leg in chunks.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 50)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.ZigZag)
        .build()
    );

    private final Setting<Double> zigzagAngle = sgPatterns.add(new DoubleSetting.Builder()
        .name("zigzag-angle")
        .description("Turn angle at each ZigZag corner (degrees from forward).")
        .defaultValue(45.0)
        .min(10.0).max(80.0)
        .sliderRange(10.0, 80.0)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.ZigZag)
        .build()
    );

    private final Setting<Integer> figureEightRadius = sgPatterns.add(new IntSetting.Builder()
        .name("figure-eight-radius")
        .description("Radius of the loops in chunks.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.FigureEight)
        .build()
    );

    // ─── DrunkPilot Settings ──────────────────────────────────────────────────────
    private final Setting<DrunkSpiralMode> drunkSpiralMode = sgDrunk.add(new EnumSetting.Builder<DrunkSpiralMode>()
        .name("spiral-mode")
        .description("Constrains drunk wandering to follow an expanding grid or circular spiral outward.")
        .defaultValue(DrunkSpiralMode.None)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk)
        .onChanged(v -> { resetDrunkSpiralState(); })
        .build()
    );

    private final Setting<Integer> drunkInterval = sgDrunk.add(new IntSetting.Builder()
        .name("change-interval")
        .description("Ticks between direction changes.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk)
        .build()
    );

    private final Setting<Double> drunkIntensity = sgDrunk.add(new DoubleSetting.Builder()
        .name("intensity")
        .description("Maximum yaw change per update (degrees). Applied when coordinate-bias is None.")
        .defaultValue(120.0)
        .min(1.0).max(180.0)
        .sliderRange(50.0, 180.0)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk && drunkSpiralMode.get() == DrunkSpiralMode.None)
        .build()
    );

    public final Setting<DrunkBias> drunkBias = sgDrunk.add(new EnumSetting.Builder<DrunkBias>()
        .name("coordinate-bias")
        .description("Constrains drunk-pilot heading. None = fully random.")
        .defaultValue(DrunkBias.None)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk && drunkSpiralMode.get() == DrunkSpiralMode.None)
        .build()
    );

    private final Setting<Boolean> drunkAvoidVisited = sgDrunk.add(new BoolSetting.Builder()
        .name("avoid-visited")
        .description("Attempts to steer the Drunk Pilot away from chunks it has already flown over.")
        .defaultValue(true)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk && drunkSpiralMode.get() == DrunkSpiralMode.None)
        .build()
    );

    private final Setting<Double> drunkSmoothing = sgDrunk.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("How smoothly to rotate to the new heading (lower = smoother).")
        .defaultValue(0.05)
        .min(0.01).max(1.0)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk)
        .build()
    );

    private final Setting<Integer> drunkGridSpacing = sgDrunk.add(new IntSetting.Builder()
        .name("drunk-grid-spacing")
        .description("Distance (chunks) between grid legs when spiral-mode is Grid.")
        .defaultValue(4).min(1).sliderRange(1, 16)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk
                    && drunkSpiralMode.get() == DrunkSpiralMode.Grid)
        .build()
    );

    private final Setting<Integer> drunkCircleSegments = sgDrunk.add(new IntSetting.Builder()
        .name("drunk-circle-segments")
        .description("Waypoints per full rotation when spiral-mode is Circle.")
        .defaultValue(24).min(4).sliderRange(8, 64)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk
                    && drunkSpiralMode.get() == DrunkSpiralMode.Circle)
        .build()
    );

    private final Setting<Integer> drunkCircleExpansion = sgDrunk.add(new IntSetting.Builder()
        .name("drunk-circle-expansion")
        .description("Chunks the circle radius grows per full rotation.")
        .defaultValue(2).min(1).sliderRange(1, 8)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk
                    && drunkSpiralMode.get() == DrunkSpiralMode.Circle)
        .build()
    );

    private final Setting<Integer> drunkHexagonSideLength = sgDrunk.add(new IntSetting.Builder()
        .name("drunk-hexagon-side-length")
        .description("Side length (chunks) of each hexagon edge when spiral-mode is Hexagon.")
        .defaultValue(4).min(1).sliderRange(1, 32)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk
                    && drunkSpiralMode.get() == DrunkSpiralMode.Hexagon)
        .build()
    );

    private final Setting<Integer> drunkHexagonExpansion = sgDrunk.add(new IntSetting.Builder()
        .name("drunk-hexagon-expansion")
        .description("Chunks the hexagon side length grows per full rotation.")
        .defaultValue(2).min(1).sliderRange(1, 16)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk
                    && drunkSpiralMode.get() == DrunkSpiralMode.Hexagon)
        .build()
    );

    private final Setting<Integer> drunkTriangleSideLength = sgDrunk.add(new IntSetting.Builder()
        .name("drunk-triangle-side-length")
        .description("Side length (chunks) of each triangle edge when spiral-mode is Triangle.")
        .defaultValue(6).min(1).sliderRange(1, 32)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk
                    && drunkSpiralMode.get() == DrunkSpiralMode.Triangle)
        .build()
    );

    private final Setting<Integer> drunkTriangleExpansion = sgDrunk.add(new IntSetting.Builder()
        .name("drunk-triangle-expansion")
        .description("Chunks the triangle side length grows per full rotation.")
        .defaultValue(3).min(1).sliderRange(1, 16)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk
                    && drunkSpiralMode.get() == DrunkSpiralMode.Triangle)
        .build()
    );

    private final Setting<Double> drunkSpiralNoise = sgDrunk.add(new DoubleSetting.Builder()
        .name("spiral-noise")
        .description("Random yaw offset (degrees) added to the spiral heading for the drunk feel.")
        .defaultValue(30.0).min(0.0).max(180.0).sliderRange(0.0, 90.0)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk
                    && drunkSpiralMode.get() != DrunkSpiralMode.None)
        .build()
    );

    private final Setting<Integer> drunkSpiralReach = sgDrunk.add(new IntSetting.Builder()
        .name("spiral-waypoint-reach")
        .description("Horizontal distance (blocks) to a spiral waypoint before advancing.")
        .defaultValue(20).min(5).sliderRange(5, 80)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && flightPattern.get() == FlightPattern.Drunk
                    && drunkSpiralMode.get() != DrunkSpiralMode.None)
        .build()
    );

    // ─── Flight Safety Settings ───────────────────────────────────────────────────
    private final Setting<Boolean> collisionAvoidance = sgFlightSafety.add(new BoolSetting.Builder()
        .name("collision-avoidance")
        .description("Attempts to avoid flying straight into walls.")
        .defaultValue(true)
        .visible(() -> flightMode.get() != FlightMode.Ebounce)
        .build()
    );

    private final Setting<Integer> avoidanceDistance = sgFlightSafety.add(new IntSetting.Builder()
        .name("avoidance-distance")
        .description("How far ahead to look for obstacles (blocks).")
        .defaultValue(10)
        .min(3)
        .sliderRange(5, 20)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && collisionAvoidance.get())
        .build()
    );

    private final Setting<Boolean> limitRotationSpeed = sgFlightSafety.add(new BoolSetting.Builder()
        .name("limit-rotation-speed")
        .description("Caps rotation speed per tick to reduce anti-cheat flags.")
        .defaultValue(true)
        .visible(() -> flightMode.get() != FlightMode.Ebounce)
        .build()
    );

    private final Setting<Double> maxRotationPerTick = sgFlightSafety.add(new DoubleSetting.Builder()
        .name("max-rotation-per-tick")
        .description("Maximum degrees to rotate per tick.")
        .defaultValue(20.0)
        .min(1.0).max(90.0)
        .sliderRange(5.0, 45.0)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && limitRotationSpeed.get())
        .build()
    );

    private final Setting<Keybind> panicKey = sgFlightSafety.add(new KeybindSetting.Builder()
        .name("panic-key")
        .description("Immediately disconnects from the server and disables the module.")
        .defaultValue(Keybind.none())
        .action(this::panicDisconnect)
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

    private final Setting<Boolean> disconnectOnLowRockets = sgPlayerSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-low-rockets")
        .description("Disconnect from the server when your firework rocket count drops below the minimum.")
        .defaultValue(false)
        .visible(() -> flightMode.get() != FlightMode.Ebounce)
        .build()
    );

    private final Setting<Integer> minRockets = sgPlayerSafety.add(new IntSetting.Builder()
        .name("min-rockets")
        .description("Minimum number of firework rockets to keep before disconnecting.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 64)
        .visible(() -> flightMode.get() != FlightMode.Ebounce && disconnectOnLowRockets.get())
        .build()
    );

    // ─── Internal State ───────────────────────────────────────────────────────────
    public  long    lastRocketTime           = 0;
    private boolean needsTakeoffRocket       = false;
    private boolean ascentMode               = false;
    private final Set<Long> drunkVisitedChunks = new LinkedHashSet<>();
    private boolean pitch40Climbing          = false;
    private boolean pitch40Rocketing         = false;
    private long    pitch40BelowMinStartTime = -1;
    private long    lastLagbackTime          = 0;
    private boolean bounceClimbing           = true;

    private float   targetPitch              = 0;
    private int     drunkTimer               = 0;
    private float   targetDrunkYaw           = 0;
    private int     currentDrunkDuration     = 0;
    private int     totemPops                = 0;
    private int     takeoffTimer             = 0;
    private int     takeoffWaitTicks         = 0;

    // Pattern flight state
    private boolean paused              = false;
    private Vec3d   origin              = null;
    private Vec3d   currentTarget       = null;
    private int     gridStep            = 1;
    private int     gridStepsInLeg      = 0;
    private int     gridDirection       = 0;
    private float   zigzagCurrentYaw    = 0;
    private boolean zigzagTurnRight     = true;
    private boolean zigzagFirstLeg      = true;
    private double  circleAngle         = 0;
    private int     sweepStep           = 0;
    private double  currentSweepFactor  = 1.0;
    private float   sweepInitialYaw     = 0;
    private int     figureEightWaypoint = 0;
    private int     polygonSide         = 0;
    private int     polygonRotation     = 0;

    // Drunk Spiral state
    private Vec3d  drunkSpiralOrigin    = null;
    private Vec3d  drunkSpiralTarget    = null;
    private int    drunkGridStep        = 1;
    private int    drunkGridStepsInLeg  = 0;
    private int    drunkGridDirection   = 0;
    private double drunkCircleAngle     = 0;
    private int    drunkPolygonSide     = 0;
    private int    drunkPolygonRotation = 0;

    // ─── Constructor ─────────────────────────────────────────────────────────────
    public RocketPilot() {
        super(Tim.CATEGORY, "rocket-pilot",
            "Automatic elytra + rocket flight with height maintenance, auto-takeoff, and pattern flight.");
    }

    // ─── Utilities ───────────────────────────────────────────────────────────────
    private boolean isPatternMode() {
        if (flightPattern.get() == FlightPattern.Manual) return false;
        if (flightPattern.get() == FlightPattern.Drunk && drunkSpiralMode.get() == DrunkSpiralMode.None) return false;
        return true;
    }

    private void togglePause() {
        if (mc.currentScreen != null) return;
        if (!isPatternMode()) return;
        paused = !paused;
        info("Pattern flight %s.", paused ? "paused" : "resumed");
    }

    private void panicDisconnect() {
        if (mc.currentScreen != null) return;
        if (mc.player == null) return;
        info("Panic disconnect triggered!");
        disconnect("[RocketPilot] Panic disconnect.");
    }

    private void resetPatternState() {
        paused              = false;
        origin              = null;
        currentTarget       = null;
        gridStep            = 1;
        gridStepsInLeg      = 0;
        gridDirection       = 0;
        zigzagCurrentYaw    = 0;
        zigzagTurnRight     = true;
        zigzagFirstLeg      = true;
        circleAngle         = 0;
        sweepStep           = 0;
        currentSweepFactor  = 1.0;
        sweepInitialYaw     = 0;
        drunkVisitedChunks.clear();
        figureEightWaypoint = 0;
        polygonSide         = 0;
        polygonRotation     = 0;
    }

    private void resetDrunkSpiralState() {
        drunkSpiralOrigin    = null;
        drunkSpiralTarget    = null;
        drunkGridStep        = 1;
        drunkGridStepsInLeg  = 0;
        drunkGridDirection   = 0;
        drunkCircleAngle     = 0;
        drunkPolygonSide     = 0;
        drunkPolygonRotation = 0;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────────
    @Override
    public void onActivate() {
        lastRocketTime           = 0;
        needsTakeoffRocket       = false;
        drunkTimer               = 0;
        currentDrunkDuration     = 0;
        ascentMode               = false;
        pitch40Climbing          = false;
        pitch40Rocketing         = false;
        pitch40BelowMinStartTime = -1;
        bounceClimbing           = true;
        lastLagbackTime          = 0;
        takeoffTimer             = 0;
        takeoffWaitTicks         = 0;

        ebounceSlow = 0; ebounceWarm = 0; ebounceJump = 0;
        ebouncePass = false; ebounceStarted = false;

        if (mc.player == null || mc.world == null) { toggle(); return; }

        if (flightMode.get() == FlightMode.Ebounce) {
            ebounceFace();
            ebounceCenter();
            // If already gliding, we've technically "started"
            ebounceStarted = mc.player.isGliding();
            return; // Skip standard rocket takeoff logic
        }

        // Reset standard pattern state if player has moved too far from the origin since last time
        if (origin != null && mc.player.getPos().distanceTo(origin) > 100) {
            resetPatternState();
        }

        // Reset drunk spiral state if player has moved too far from the origin since last time
        if (drunkSpiralOrigin != null && mc.player.getPos().distanceTo(drunkSpiralOrigin) > 100) {
            resetDrunkSpiralState();
        }

        totemPops      = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
        targetPitch    = mc.player.getPitch();
        targetDrunkYaw = mc.player.getYaw();

        if (mc.player.isGliding()) return;
        if (!autoTakeoff.get())    return;

        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) {
            error("No elytra equipped.");
            toggle();
            return;
        }
        if (countFireworks() == 0 && flightMode.get() != FlightMode.Ebounce) {
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
        takeoffWaitTicks   = 0;
        paused             = false; // Unpause on disable
        drunkVisitedChunks.clear(); // Free memory

        // Clear Ebounce inputs
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        if (ebouncePass) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
        }

        // Deliberately NOT resetting origin/currentTarget here so it can resume after restocking
    }

    // ─── Main Tick ────────────────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (System.currentTimeMillis() - lastLagbackTime < 500) return;
        if (mc.player == null || mc.world == null) return;

        if (disconnectOnTotemPop.get()) {
            int currentPops = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
            if (currentPops > totemPops) {
                error("Totem popped! Disconnecting...");
                disconnect("[RocketPilot] Disconnected on totem pop.");
                return;
            }
        }

        if (autoDisableOnLowHealth.get()) {
            boolean hasTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                            || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
            if (hasTotem && mc.player.getHealth() <= lowHealthThreshold.get() * 2f) {
                error("Health critical (%.1f hp), disabling.", mc.player.getHealth());
                toggle();
                return;
            }
        }

        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) {
            error("Elytra missing — disabling.");
            toggle();
            return;
        }

        // Cleanly separate Ebounce logic from Rocket logic
        if (flightMode.get() == FlightMode.Ebounce) {
            handleEbounceTick();
            return;
        }

        replenishRockets();

        if (disconnectOnLowRockets.get() && countFireworks() < minRockets.get()) {
            error("Low on rockets (%d < %d)! Disconnecting...", countFireworks(), minRockets.get());
            disconnect("[RocketPilot] Disconnected due to low rocket count.");
            return;
        }

        if (takeoffTimer > 0) takeoffTimer--;

        if (disableOnLand.get() && mc.player.isOnGround() && !needsTakeoffRocket && takeoffTimer == 0) {
            info("Landed — disabling.");
            toggle();
            return;
        }

        boolean wantsToFly = !useTargetY.get() || mc.player.getY() < targetY.get();
        if (flightMode.get() == FlightMode.Pitch40 || flightMode.get() == FlightMode.AltitudeBounce) {
            wantsToFly = true;
        }

        if (isNearGround() && !mc.player.isGliding() && wantsToFly && autoTakeoff.get() && countFireworks() > 0 && !needsTakeoffRocket) {
            targetPitch = -28.0f;
            mc.player.setPitch(targetPitch);
            if (mc.player.isOnGround()) mc.player.jump();
            needsTakeoffRocket = true;
            takeoffWaitTicks   = 0;
            info("Re-launching!");
        }

        if (needsTakeoffRocket) {
            handleTakeoff();
            return;
        }

        if (!mc.player.isGliding()) return;

        handleElytraHealth();

        Float desiredPitch  = null;
        boolean safetyOverride = false;

        // Priority 1: Collision avoidance
        if (desiredPitch == null && collisionAvoidance.get()) {
            desiredPitch = handleCollisionAvoidance();
            if (desiredPitch != null) safetyOverride = true;
        }

        // Priority 2: Normal flight modes
        if (desiredPitch == null) {
            desiredPitch = switch (flightMode.get()) {
                case Pitch40        -> handlePitch40Mode();
                case AltitudeBounce -> handleAltitudeBounceMode();
                case None           -> useTargetY.get() ? handleNormalMode() : null;
                default             -> handleNormalMode();
            };
        }

        if (!safetyOverride) {
            FlightPattern currentPattern = flightPattern.get();
            if (currentPattern == FlightPattern.Drunk) {
                if (drunkVisitedChunks.size() > 2000) {
                    Iterator<Long> it = drunkVisitedChunks.iterator();
                    for (int i = 0; i < 1000 && it.hasNext(); i++) {
                        it.next();
                        it.remove();
                    }
                }
                drunkVisitedChunks.add(mc.player.getChunkPos().toLong());
                handleDrunkMode();
            } else if (currentPattern != FlightPattern.Manual) {
                handlePatternYaw();
            }
        }

        applyPitch(desiredPitch);
    }

    @EventHandler
    private void onPacketReceive(meteordevelopment.meteorclient.events.packets.PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            lastLagbackTime = System.currentTimeMillis();
            mc.options.forwardKey.setPressed(false);
        }
    }

    // ─── Ebounce Mode Logic ──────────────────────────────────────────────────────
    private void handleEbounceTick() {
        if (mc.player == null || mc.world == null) return;
        handleElytraHealth();

        IBaritone baritone = ebounceObstacle.get() ? BaritoneAPI.getProvider().getPrimaryBaritone() : null;

        if (ebounceObstacle.get()) {
            if (ebouncePass && (baritone.getCustomGoalProcess().isActive() || baritone.getPathingBehavior().isPathing())) {
                mc.options.forwardKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                mc.player.setSprinting(false);
                ebounceSlow = 0; ebounceWarm = 0; ebounceJump = 0;
                return;
            }
            if (ebouncePass) {
                ebounceRotate();
                ebounceSlow = 0; ebounceWarm = 0; ebounceJump = 0;
                ebouncePass = false;
            }
        } else if (ebouncePass) {
            baritone.getPathingBehavior().cancelEverything();
            ebouncePass = false;
        }

        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) {
            error("Elytra missing — disabling.");
            toggle();
            return;
        }

        if (!mc.player.isGliding() && !ebounceStarted) {
            ebounceSlow = 0; ebounceWarm = 0;
            mc.player.setSprinting(false);

            if (mc.player.isOnGround()) {
                ebounceJump = 0;
                mc.options.forwardKey.setPressed(true);
                mc.options.jumpKey.setPressed(true);
                mc.player.jump();
                return;
            }

            ebounceJump++;
            if (ebounceJump < EBOUNCE_LAUNCH || mc.player.getVelocity().y >= 0.0) {
                return;
            }

            mc.player.startGliding();
            if (mc.player.networkHandler != null) {
                mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
            mc.player.setSprinting(true);
            ebounceJump = 0;
            ebounceStarted = true;
        } else {
            mc.player.setSprinting(true);
            if (!mc.player.isGliding() && !mc.player.isOnGround()) {
                mc.player.startGliding();
                if (mc.player.networkHandler != null) {
                    mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }
            }

            if (!mc.player.isGliding()) {
                ebounceStarted = false;
                return;
            }

            mc.player.setPitch(ebouncePitch.get().floatValue());
            mc.options.forwardKey.setPressed(true);
            mc.options.jumpKey.setPressed(true);

            ebounceWarm++;
            if (ebounceObstacle.get() && ebounceAvoid.get()) {
                Vec3d hit = ebounceCollision();
                if (hit != null) {
                    ebouncePath(baritone, hit);
                    return;
                }
            }
            if (ebounceObstacle.get() && ebounceWarm >= EBOUNCE_WARMUP) {
                Vec3d vel = mc.player.getVelocity();
                double speed = Math.hypot(vel.x, vel.z);
                if (speed < EBOUNCE_STOP) ebounceSlow++;
                else ebounceSlow = 0;
                if (ebounceSlow > EBOUNCE_WAIT) {
                    ebouncePath(baritone);
                    return;
                }
            } else {
                ebounceSlow = 0;
            }
        }
    }

    private Vec3d ebounceCollision() {
        Vec3d front = new Vec3d(ebounceDx, 0, ebounceDz).normalize();
        Vec3d side = new Vec3d(-front.z, 0, front.x);
        Vec3d vel = mc.player.getVelocity();

        double scan = Math.hypot(vel.x, vel.z) * ebounceTicks.get();
        double width = mc.player.getWidth() / 2.0;

        Vec3d closest = null;
        double distance = Double.MAX_VALUE;

        for (int idx = -1; idx <= 1; idx += 2) {
            for (double y = 0.5; y <= 1.5; y++) {
                Vec3d start = new Vec3d(mc.player.getX(), mc.player.getY() + y, mc.player.getZ());
                start = start.add(side.multiply(width * idx));
                Vec3d end = start.add(front.multiply(scan));
                BlockHitResult hit = mc.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));

                if (hit.getType() != HitResult.Type.BLOCK) continue;
                double current = start.squaredDistanceTo(hit.getPos());
                if (current < distance) {
                    distance = current;
                    closest = hit.getPos();
                }
            }
        }
        return closest;
    }

    private void ebouncePath(IBaritone baritone) {
        ebouncePath(baritone, mc.player.getPos());
    }

    private void ebouncePath(IBaritone baritone, Vec3d point) {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        ebounceSlow = 0; ebounceWarm = 0; ebounceJump = 0;
        ebouncePass = true; ebounceStarted = false;
        mc.player.setSprinting(false);
        GoalBlock goal = new GoalBlock(ebounceGoal(point));
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
    }

    private BlockPos ebounceGoal(Vec3d point) {
        Vec3d dir = new Vec3d(ebounceDx, 0, ebounceDz).normalize();
        double ox = point.x - ebouncePx;
        double oz = point.z - ebouncePz;
        double along = ox * dir.x + oz * dir.z;
        double px = ebouncePx + dir.x * (along + EBOUNCE_REACH);
        double pz = ebouncePz + dir.z * (along + EBOUNCE_REACH);
        return new BlockPos((int) Math.round(px), mc.player.getBlockY(), (int) Math.round(pz));
    }

    private void ebounceFace() {
        float yaw = mc.player.getYaw();
        int face = MathHelper.floor((yaw + 22.5F) / 45.0F) & 7;
        ebounceDx = dxs[face];
        ebounceDz = dzs[face];
    }

    private void ebounceCenter() {
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        if (ebounceDx == 0) {
            ebouncePx = ebounceSnap(px);
            ebouncePz = 0;
        } else if (ebounceDz == 0) {
            ebouncePx = 0;
            ebouncePz = ebounceSnap(pz);
        } else {
            Boolean eq = ebounceDx == ebounceDz;
            ebouncePx = ebounceSnap(eq ? px - pz : px + pz);
            ebouncePz = 0;
        }
    }

    private void ebounceRotate() {
        float yaw = (float) Math.toDegrees(Math.atan2(-ebounceDx, ebounceDz));
        mc.player.setYaw(yaw);
        mc.player.setHeadYaw(yaw);
        mc.player.setBodyYaw(yaw);
    }

    private int ebounceSnap(double value) {
        return (int) Math.round(value / EBOUNCE_GRID) * EBOUNCE_GRID;
    }

    // ─── Takeoff ─────────────────────────────────────────────────────────────────
    private void handleTakeoff() {
        if (mc.player.isOnGround()) {
            mc.player.jump();
            return;
        }
        if (!mc.player.isGliding()) {
            if (mc.player.networkHandler != null) {
                mc.player.networkHandler.sendPacket(
                    new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING)
                );
            }
            return;
        }
        boolean rocketInHotbar = hotbarHasRocket();
        if (!rocketInHotbar) {
            takeoffWaitTicks++;
            if (takeoffWaitTicks < 10) return;
        }
        if (shouldFireRocket() && countFireworks() > 0) {
            fireRocket();
            lastRocketTime = System.currentTimeMillis();
        }
        needsTakeoffRocket = false;
        takeoffWaitTicks   = 0;
        takeoffTimer       = TAKEOFF_GRACE_TICKS;
    }

    private boolean hotbarHasRocket() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) return true;
        }
        return false;
    }

    // ─── Elytra Health ───────────────────────────────────────────────────────────
    private void handleElytraHealth() {
        if (getDurabilityPercent() <= ELYTRA_LOW_PERCENT) {
            Integer newDura = swapToFreshElytra();
            if (newDura != null) {
                info("Auto-swapped elytra (durability was low).");
            } else {
                warning("No replacement elytra found!");
            }
        }
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

        if (isPatternMode()) {
            currentTarget = null;
        }

        Vec3d leftDir  = fwd.rotateY(1.5f);
        Vec3d rightDir = fwd.rotateY(-1.5f);
        double checkDist = avoidanceDistance.get() * 1.5;

        boolean leftClear = mc.world.raycast(new RaycastContext(
            camPos, camPos.add(leftDir.multiply(checkDist)),
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player
        )).getType() == HitResult.Type.MISS;

        boolean rightClear = mc.world.raycast(new RaycastContext(
            camPos, camPos.add(rightDir.multiply(checkDist)),
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player
        )).getType() == HitResult.Type.MISS;

        float yawSpeed = 5.0f;
        if (limitRotationSpeed.get()) yawSpeed = Math.min(yawSpeed, maxRotationPerTick.get().floatValue());

        if (leftClear && !rightClear) {
            mc.player.setYaw(mc.player.getYaw() + yawSpeed);
        } else if (rightClear && !leftClear) {
            mc.player.setYaw(mc.player.getYaw() - yawSpeed);
        } else if (leftClear) {
            if (mc.player.age % 2 == 0) mc.player.setYaw(mc.player.getYaw() + yawSpeed);
            else mc.player.setYaw(mc.player.getYaw() - yawSpeed);
        }

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

        float calculatedPitch;
        if (Math.abs(diff) < 0.5) {
            calculatedPitch = 0.0f;
        } else {
            calculatedPitch = (float) (-Math.tanh(diff / 10.0) * 60.0);
            calculatedPitch = MathHelper.clamp(calculatedPitch, -60.0f, 45.0f);
        }

        targetPitch = calculatedPitch;
        float smooth = pitchSmoothing.get().floatValue();
        return mc.player.getPitch() + (targetPitch - mc.player.getPitch()) * smooth;
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

    // ─── Altitude Bounce Mode ─────────────────────────────────────────────────────
    private Float handleAltitudeBounceMode() {
        double currentY = mc.player.getY();
        double peakY    = bouncePeakY.get();
        double floorY   = bounceFloorY.get();
        float  smooth   = bouncePitchSmoothing.get().floatValue();

        if (bounceClimbing && currentY >= peakY)  bounceClimbing = false;
        if (!bounceClimbing && currentY <= floorY) bounceClimbing = true;

        if (bounceClimbing) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.get()
                    && mc.player.getVelocity().y < 0.5
                    && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
            return MathHelper.lerp(smooth, mc.player.getPitch(), bounceClimbPitch.get().floatValue());
        } else {
            return MathHelper.lerp(smooth, mc.player.getPitch(), bounceGlidePitch.get().floatValue());
        }
    }

    // ─── Pattern Flight ───────────────────────────────────────────────────────────
    private void handlePatternYaw() {
        if (paused) return;

        if (flightPattern.get() != FlightPattern.Manual && flightPattern.get() != FlightPattern.Drunk) {
            if (origin == null) origin = mc.player.getPos();

            if (currentTarget == null) {
                calculateNextTarget();
            } else {
                double dx = currentTarget.x - mc.player.getX();
                double dz = currentTarget.z - mc.player.getZ();

                if (sweepAutoUpdate.get() && flightPattern.get() == FlightPattern.Sweep && (dx * dx + dz * dz) > 4096.0) {
                    resetPatternState();
                    return;
                }

                int    radius = waypointReachRadius.get();
                if (dx * dx + dz * dz < (double)(radius * radius)) calculateNextTarget();
            }

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

    private void calculateNextTarget() {
        if (origin == null) origin = mc.player.getPos();

        double targetYValue  = useTargetY.get() ? targetY.get() : mc.player.getY();
        double nextX, nextZ;
        FlightPattern currentPattern = flightPattern.get();

        if (currentPattern == FlightPattern.Manual || currentPattern == FlightPattern.Drunk) { currentTarget = null; return; }

        if (currentPattern == FlightPattern.Grid) {
            int spacing = gridSpacing.get() * 16;
            if (currentTarget == null) {
                gridDirection  = 3;
                gridStepsInLeg = 0;
                Vec3d offset = getGridDirectionOffset(gridDirection, spacing);
                nextX = origin.x + offset.x;
                nextZ = origin.z + offset.z;
                gridStepsInLeg = 1;
            } else {
                if (gridStepsInLeg >= gridStep) {
                    gridDirection  = (gridDirection + 1) % 4;
                    gridStepsInLeg = 0;
                    if (gridDirection == 0 || gridDirection == 2) gridStep++;
                }
                Vec3d offset = getGridDirectionOffset(gridDirection, spacing);
                nextX = currentTarget.x + offset.x;
                nextZ = currentTarget.z + offset.z;
                gridStepsInLeg++;
            }
        } else if (currentPattern == FlightPattern.ZigZag) {
            double legLength = zigzagLegLength.get() * 16.0;
            if (currentTarget == null) {
                zigzagCurrentYaw = mc.player.getYaw();
                zigzagTurnRight  = true;
                zigzagFirstLeg   = true;
            }
            if (zigzagFirstLeg) {
                zigzagFirstLeg = false;
            } else {
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
        } else if (currentPattern == FlightPattern.FigureEight) {
            double r = figureEightRadius.get() * 16.0;
            double x_off, z_off;
            switch (figureEightWaypoint) {
                case 0: x_off =  r; z_off =  r;    break;
                case 1: x_off =  0; z_off =  2*r;  break;
                case 2: x_off = -r; z_off =  r;    break;
                case 3: x_off =  0; z_off =  0;    break;
                case 4: x_off = -r; z_off = -r;    break;
                case 5: x_off =  0; z_off = -2*r;  break;
                case 6: x_off =  r; z_off = -r;    break;
                default: x_off = 0; z_off =  0;    break;
            }
            nextX = origin.x + x_off;
            nextZ = origin.z + z_off;
            figureEightWaypoint = (figureEightWaypoint + 1) % 8;
        } else if (currentPattern == FlightPattern.Circle) {
            double angleStep       = 2.0 * Math.PI / circleSegments.get();
            double expansionBlocks = circleExpansion.get() * 16.0;
            double b               = expansionBlocks / (2.0 * Math.PI);
            double radius          = b * circleAngle;
            nextX = origin.x + radius * Math.cos(circleAngle);
            nextZ = origin.z + radius * Math.sin(circleAngle);
            circleAngle += angleStep;
        } else if (currentPattern == FlightPattern.Hexagon || currentPattern == FlightPattern.Triangle) {
            int    sides      = currentPattern == FlightPattern.Hexagon ? 6 : 3;
            double extAngle   = 2.0 * Math.PI / sides; // 60° for hex, 120° for tri
            int    baseSide   = currentPattern == FlightPattern.Hexagon
                                ? hexagonSideLength.get() : triangleSideLength.get();
            int    expansion  = currentPattern == FlightPattern.Hexagon
                                ? hexagonExpansion.get() : triangleExpansion.get();

            int    totalSteps  = polygonRotation * sides + polygonSide;
            double growPerSide = (expansion * 16.0) / sides;
            double sideLen     = (baseSide * 16.0) + totalSteps * growPerSide;

            double heading = polygonSide * extAngle;
            Vec3d start    = (currentTarget != null) ? currentTarget : origin;
            nextX = start.x + Math.cos(heading) * sideLen;
            nextZ = start.z + Math.sin(heading) * sideLen;

            polygonSide++;
            if (polygonSide >= sides) {
                polygonSide = 0;
                polygonRotation++;
            }
        } else if (currentPattern == FlightPattern.Sweep) {
            if (currentTarget == null) {
                sweepInitialYaw = mc.player.getYaw();
                sweepStep = 0;
                currentSweepFactor = 1.0;
            }

            if (sweepExpansionRate.get() > 0.0 && sweepStep > 0 && sweepStep % 4 == 0) {
                currentSweepFactor = Math.min(sweepMaxFactor.get(), currentSweepFactor * (1.0 + sweepExpansionRate.get()));
            }

            double width   = sweepWidth.get() * 16.0 * currentSweepFactor;
            double advance = sweepAdvance.get() * 16.0 * currentSweepFactor;

            float rad = (float) Math.toRadians(sweepInitialYaw);
            Vec3d fwd  = new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
            Vec3d side = new Vec3d(-Math.cos(rad), 0, -Math.sin(rad));
            Vec3d base = (currentTarget != null) ? currentTarget : origin;

            Vec3d move;
            switch (sweepStep % 4) {
                case 0:  move = side.multiply(sweepStep == 0 ? -width : -width * 2.0); break;
                case 1:  move = fwd.multiply(advance); break;
                case 2:  move = side.multiply(width * 2.0);  break;
                default: move = fwd.multiply(advance); break;
            }

            nextX = base.x + move.x;
            nextZ = base.z + move.z;

            sweepStep++;
        } else {
            return;
        }

        currentTarget = new Vec3d(nextX, targetYValue, nextZ);
    }

    private Vec3d getGridDirectionOffset(int dir, int dist) {
        return switch (dir) {
            case 0 -> new Vec3d( dist, 0,    0);
            case 1 -> new Vec3d(   0, 0, -dist);
            case 2 -> new Vec3d(-dist, 0,    0);
            case 3 -> new Vec3d(   0, 0,  dist);
            default -> Vec3d.ZERO;
        };
    }

    // ─── Drunk Mode ──────────────────────────────────────────────────────────────
    private void handleDrunkMode() {
        if (drunkSpiralMode.get() != DrunkSpiralMode.None) {
            if (paused) return;
            handleDrunkSpiralMode();
            return;
        }

        if (drunkTimer++ >= currentDrunkDuration) {
            float intensity = drunkIntensity.get().floatValue();
            DrunkBias bias  = drunkBias.get();

            if (bias == DrunkBias.None) {
                if (drunkAvoidVisited.get()) {
                    float bestCandidate = mc.player.getYaw();

                    for (int i = 0; i < 10; i++) {
                        float candidate = mc.player.getYaw() + (float)((Math.random() - 0.5) * 2.0 * intensity);
                        double rad = Math.toRadians(candidate);
                        boolean pathVisited = false;

                        for (int dist : new int[]{16, 32, 48}) {
                            int cx = (int) Math.floor((mc.player.getX() - Math.sin(rad) * dist) / 16.0);
                            int cz = (int) Math.floor((mc.player.getZ() + Math.cos(rad) * dist) / 16.0);
                            if (drunkVisitedChunks.contains(ChunkPos.toLong(cx, cz))) {
                                pathVisited = true;
                                break;
                            }
                        }
                        if (!pathVisited) {
                            bestCandidate = candidate;
                            break;
                        }
                        if (i == 0) bestCandidate = candidate;
                    }
                    targetDrunkYaw = bestCandidate;
                } else {
                    targetDrunkYaw = mc.player.getYaw() + (float)((Math.random() - 0.5) * 2.0 * intensity);
                }
            } else {
                float minYaw, maxYaw;
                boolean isNorth = false;
                switch (bias) {
                    case North        -> { isNorth = true; minYaw = 0; maxYaw = 0; }
                    case South        -> { minYaw = -22.5f; maxYaw =  22.5f; }
                    case East         -> { minYaw = -112.5f; maxYaw = -67.5f; }
                    case West         -> { minYaw =  67.5f; maxYaw = 112.5f; }
                    case PositiveOnly -> { minYaw = -90f;  maxYaw =   0f; }
                    case NegativeOnly -> { minYaw =  90f;  maxYaw = 180f; }
                    case NegPos       -> { minYaw =   0f;  maxYaw =  90f; }
                    case PosNeg       -> { minYaw = -180f; maxYaw = -90f; }
                    default           -> { minYaw = -180f; maxYaw = 180f; }
                }

                if (isNorth) {
                    targetDrunkYaw = 180f + ((float)Math.random() * 45f - 22.5f);
                } else {
                    targetDrunkYaw = minYaw + (float)(Math.random() * (maxYaw - minYaw));
                }
            }

            drunkTimer           = 0;
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

    // ─── Drunk Spiral Mode ───────────────────────────────────────────────────────
    private void handleDrunkSpiralMode() {
        if (mc.player == null) return;
        if (drunkSpiralOrigin == null) drunkSpiralOrigin = mc.player.getPos();

        if (drunkSpiralTarget == null) {
            calculateDrunkSpiralTarget();
        } else {
            double dx = drunkSpiralTarget.x - mc.player.getX();
            double dz = drunkSpiralTarget.z - mc.player.getZ();
            int    r  = drunkSpiralReach.get();
            if (dx * dx + dz * dz < (double)(r * r)) calculateDrunkSpiralTarget();
        }
        if (drunkSpiralTarget == null) return;

        if (drunkTimer++ >= currentDrunkDuration) {
            double dx = drunkSpiralTarget.x - mc.player.getX();
            double dz = drunkSpiralTarget.z - mc.player.getZ();
            float baseYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float noise   = (float)((Math.random() - 0.5) * 2.0 * drunkSpiralNoise.get());
            targetDrunkYaw = MathHelper.wrapDegrees(baseYaw + noise);

            drunkTimer           = 0;
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

    private void calculateDrunkSpiralTarget() {
        if (drunkSpiralOrigin == null) drunkSpiralOrigin = mc.player.getPos();

        double targetYValue = useTargetY.get() ? targetY.get() : mc.player.getY();
        double nextX, nextZ;

        if (drunkSpiralMode.get() == DrunkSpiralMode.Grid) {
            int spacing = drunkGridSpacing.get() * 16;
            if (drunkSpiralTarget == null) {
                drunkGridDirection  = 3;
                drunkGridStepsInLeg = 0;
                Vec3d off = getGridDirectionOffset(drunkGridDirection, spacing);
                nextX = drunkSpiralOrigin.x + off.x;
                nextZ = drunkSpiralOrigin.z + off.z;
                drunkGridStepsInLeg = 1;
            } else {
                if (drunkGridStepsInLeg >= drunkGridStep) {
                    drunkGridDirection  = (drunkGridDirection + 1) % 4;
                    drunkGridStepsInLeg = 0;
                    if (drunkGridDirection == 0 || drunkGridDirection == 2) drunkGridStep++;
                }
                Vec3d off = getGridDirectionOffset(drunkGridDirection, spacing);
                nextX = drunkSpiralTarget.x + off.x;
                nextZ = drunkSpiralTarget.z + off.z;
                drunkGridStepsInLeg++;
            }
        } else if (drunkSpiralMode.get() == DrunkSpiralMode.Hexagon || drunkSpiralMode.get() == DrunkSpiralMode.Triangle) {
            int    sides      = drunkSpiralMode.get() == DrunkSpiralMode.Hexagon ? 6 : 3;
            double extAngle   = 2.0 * Math.PI / sides;
            int    baseSide   = drunkSpiralMode.get() == DrunkSpiralMode.Hexagon
                                ? drunkHexagonSideLength.get() : drunkTriangleSideLength.get();
            int    expansion  = drunkSpiralMode.get() == DrunkSpiralMode.Hexagon
                                ? drunkHexagonExpansion.get() : drunkTriangleExpansion.get();

            int    totalSteps  = drunkPolygonRotation * sides + drunkPolygonSide;
            double growPerSide = (expansion * 16.0) / sides;
            double sideLen     = (baseSide * 16.0) + totalSteps * growPerSide;

            double heading = drunkPolygonSide * extAngle;
            Vec3d start    = (drunkSpiralTarget != null) ? drunkSpiralTarget : drunkSpiralOrigin;
            nextX = start.x + Math.cos(heading) * sideLen;
            nextZ = start.z + Math.sin(heading) * sideLen;

            drunkPolygonSide++;
            if (drunkPolygonSide >= sides) {
                drunkPolygonSide = 0;
                drunkPolygonRotation++;
            }
        } else {
            double angleStep       = 2.0 * Math.PI / drunkCircleSegments.get();
            double expansionBlocks = drunkCircleExpansion.get() * 16.0;
            double b               = expansionBlocks / (2.0 * Math.PI);
            double radius          = b * drunkCircleAngle;
            nextX = drunkSpiralOrigin.x + radius * Math.cos(drunkCircleAngle);
            nextZ = drunkSpiralOrigin.z + radius * Math.sin(drunkCircleAngle);
            drunkCircleAngle += angleStep;
        }

        drunkSpiralTarget = new Vec3d(nextX, targetYValue, nextZ);
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
        if (hotbarSlot == -1) return;
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

    private Integer swapToFreshElytra() {
        int bestSlot = -1, bestDurability = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.ELYTRA)) {
                int dur = stack.getMaxDamage() - stack.getDamage();
                if (dur > bestDurability && dur > ELYTRA_MIN_SWAP_DUR) {
                    bestSlot = i; bestDurability = dur;
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

    private void fireRocket() {
        if (mc.player == null || mc.interactionManager == null) return;

        int rocketSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) { rocketSlot = i; break; }
        }

        if (rocketSlot == -1) {
            if (mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
                mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
                if (!silentRockets.get()) mc.player.swingHand(Hand.OFF_HAND);
            }
            return;
        }

        InvUtils.swap(rocketSlot, true);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (!silentRockets.get()) mc.player.swingHand(Hand.MAIN_HAND);
        InvUtils.swapBack();
    }

    private void disconnect(String reason) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getConnection().disconnect(Text.literal(reason));
        }
        toggle();
    }
}