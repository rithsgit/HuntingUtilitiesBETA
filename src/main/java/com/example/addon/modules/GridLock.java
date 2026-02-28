package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class GridLock extends Module {

    // ─────────────────────────── Enums ───────────────────────────
    public enum Pattern { SquareSpiral, ArchimedeanSpiral, DiagonalGrid, Lawnmower, Circle, Random }
    public enum Direction { South, East, North, West }
    public enum ShiftDirection { Right, Left }

    // ─────────────────────────── Setting Groups ───────────────────────────
    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgAltitude = settings.createGroup("Altitude Control");
    private final SettingGroup sgLimits   = settings.createGroup("Limits");
    private final SettingGroup sgSafety   = settings.createGroup("Safety");

    // ─────────────────────────── General ───────────────────────────
    public final Setting<Pattern> pattern = sgGeneral.add(new EnumSetting.Builder<Pattern>()
        .name("pattern")
        .description("The flight pattern to follow.")
        .defaultValue(Pattern.SquareSpiral)
        .build()
    );

    public final Setting<Integer> chunkSpacing = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-spacing")
        .description("Distance between grid lines or spiral loops in chunks.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 32)
        .build()
    );

    public final Setting<Double> turnSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("How fast to turn towards the next target (degrees per tick).")
        .defaultValue(4.0)
        .min(1.0)
        .max(180.0)
        .build()
    );

    private final Setting<Integer> lawnmowerLength = sgGeneral.add(new IntSetting.Builder()
        .name("lawnmower-length")
        .description("Length of the long strips in the Lawnmower pattern (in steps).")
        .defaultValue(10)
        .min(2)
        .sliderRange(5, 50)
        .visible(() -> pattern.get() == Pattern.Lawnmower)
        .build()
    );

    private final Setting<Direction> lawnmowerDirection = sgGeneral.add(new EnumSetting.Builder<Direction>()
        .name("lawnmower-direction")
        .description("The initial direction for the Lawnmower pattern.")
        .defaultValue(Direction.South)
        .visible(() -> pattern.get() == Pattern.Lawnmower)
        .build()
    );

    private final Setting<ShiftDirection> lawnmowerShift = sgGeneral.add(new EnumSetting.Builder<ShiftDirection>()
        .name("lawnmower-shift")
        .description("Which way to shift after each leg.")
        .defaultValue(ShiftDirection.Right)
        .visible(() -> pattern.get() == Pattern.Lawnmower)
        .build()
    );

    private final Setting<Keybind> pauseBind = sgGeneral.add(new KeybindSetting.Builder()
        .name("pause-bind")
        .description("Keybind to pause/resume the flight.")
        .defaultValue(Keybind.none())
        .action(this::togglePause)
        .build()
    );

    // Circle Pattern
    private final Setting<Integer> circleRadius = sgGeneral.add(new IntSetting.Builder()
        .name("circle-radius")
        .description("Radius of the circle in chunks.")
        .defaultValue(10)
        .min(1)
        .sliderRange(5, 50)
        .visible(() -> pattern.get() == Pattern.Circle)
        .build()
    );

    private final Setting<Integer> circleSegments = sgGeneral.add(new IntSetting.Builder()
        .name("circle-segments")
        .description("Number of waypoints on the circle's circumference.")
        .defaultValue(32)
        .min(4)
        .sliderRange(8, 128)
        .visible(() -> pattern.get() == Pattern.Circle)
        .build()
    );

    // Random Pattern
    private final Setting<Integer> randomMaxRadius = sgGeneral.add(new IntSetting.Builder()
        .name("random-radius")
        .description("Maximum radius from origin for random waypoints, in chunks.")
        .defaultValue(20)
        .min(1)
        .sliderRange(10, 100)
        .visible(() -> pattern.get() == Pattern.Random)
        .build()
    );

    private final Setting<Boolean> renderPath = sgGeneral.add(new BoolSetting.Builder()
        .name("render-path")
        .description("Renders a line to the next target waypoint.")
        .defaultValue(true)
        .build()
    );

    // ─────────────────────────── Altitude Control ───────────────────────────
    private final Setting<Boolean> controlAltitude = sgAltitude.add(new BoolSetting.Builder()
        .name("control-altitude")
        .description("Automatically adjusts flight altitude to follow terrain, including ceiling detection for the Nether.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> flightAltitude = sgAltitude.add(new IntSetting.Builder()
        .name("floor-clearance")
        .description("Desired height above the ground/floor.")
        .defaultValue(20)
        .min(5)
        .sliderRange(5, 80)
        .visible(controlAltitude::get)
        .build()
    );

    private final Setting<Integer> ceilingClearance = sgAltitude.add(new IntSetting.Builder()
        .name("ceiling-clearance")
        .description("Minimum gap to keep below ceilings (critical in the Nether bedrock roof).")
        .defaultValue(10)
        .min(3)
        .sliderRange(3, 30)
        .visible(controlAltitude::get)
        .build()
    );

    private final Setting<Integer> lookaheadBlocks = sgAltitude.add(new IntSetting.Builder()
        .name("lookahead-blocks")
        .description("How many blocks ahead to sample terrain to anticipate altitude changes.")
        .defaultValue(20)
        .min(5)
        .sliderRange(5, 60)
        .visible(controlAltitude::get)
        .build()
    );

    private final Setting<Double> maxClimbRate = sgAltitude.add(new DoubleSetting.Builder()
        .name("max-climb-rate")
        .description("Maximum vertical speed when climbing (blocks/tick).")
        .defaultValue(1.5)
        .min(0.1)
        .sliderRange(0.5, 3.0)
        .visible(controlAltitude::get)
        .build()
    );

    private final Setting<Double> maxSinkRate = sgAltitude.add(new DoubleSetting.Builder()
        .name("max-sink-rate")
        .description("Maximum vertical speed when descending (blocks/tick).")
        .defaultValue(1.0)
        .min(0.1)
        .sliderRange(0.5, 2.0)
        .visible(controlAltitude::get)
        .build()
    );

    // ─────────────────────────── Limits ───────────────────────────
    private final Setting<Boolean> useMaxDistance = sgLimits.add(new BoolSetting.Builder()
        .name("use-max-distance")
        .description("Automatically disable the module after reaching a certain distance from the start.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> maxDistance = sgLimits.add(new IntSetting.Builder()
        .name("max-distance")
        .description("The maximum distance in blocks from the start point before disabling.")
        .defaultValue(1000)
        .min(100)
        .sliderRange(100, 5000)
        .visible(useMaxDistance::get)
        .build()
    );

    // ─────────────────────────── Safety ───────────────────────────
    private final Setting<Boolean> pauseOnDamage = sgSafety.add(new BoolSetting.Builder()
        .name("pause-on-damage")
        .description("Pauses the module if you take damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnLowHealth = sgSafety.add(new BoolSetting.Builder()
        .name("pause-on-low-health")
        .description("Pauses the module if your health is low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lowHealthThreshold = sgSafety.add(new IntSetting.Builder()
        .name("low-health-threshold")
        .description("The health level (in hearts) to trigger the pause.")
        .defaultValue(5)
        .min(1)
        .max(19)
        .sliderRange(1, 10)
        .visible(pauseOnLowHealth::get)
        .build()
    );

    // ─────────────────────────── State ───────────────────────────
    private Vec3d origin;
    private Vec3d currentTarget;
    private boolean paused = false;
    private float lastHealth = 0;
    private boolean pausedBySafety = false;

    // Square spiral / DiagonalGrid state
    private int stepSize = 1;
    private int stepsInLeg = 0;
    private int direction = 0; // 0=+Z, 1=+X, 2=-Z, 3=-X
    private int baseDir = 0;

    // Lawnmower state
    private boolean lawnmowerReturning = false;
    private boolean lawnmowerForward = true;

    // Archimedean spiral state
    private double spiralTheta = 0;

    // Circle state
    private int circleCurrentSegment = 0;

    private static final Color PATH_COLOR = new Color(85, 255, 85, 200);

    // ─────────────────────────── Constructor ───────────────────────────
    public GridLock() {
        super(HuntingUtilities.CATEGORY, "grid-lock",
            "Flies a configurable grid/spiral pattern with real-time terrain following (floor + ceiling).");
    }

    // ─────────────────────────── Lifecycle ───────────────────────────
    @Override
    public void onActivate() {
        if (mc.player == null) { toggle(); return; }

        RocketPilot rocketPilot = Modules.get().get(RocketPilot.class);
        if (rocketPilot != null && rocketPilot.isActive()) {
            warning("RocketPilot is active. Disabling it to use GridLock.");
            rocketPilot.toggle();
        }

        origin = mc.player.getPos();
        paused = false;
        lastHealth = mc.player.getHealth();
        pausedBySafety = false;

        stepSize = 1;
        stepsInLeg = 0;
        baseDir = getInitialDirection();
        direction = baseDir;
        spiralTheta = Math.PI / 4;
        lawnmowerReturning = false;
        lawnmowerForward = true;
        circleCurrentSegment = 0;

        currentTarget = null;
        calculateNextTarget();
    }

    // ─────────────────────────── Tick ───────────────────────────
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || currentTarget == null) return;

        // ── Safety ──
        if (pauseOnDamage.get() && mc.player.getHealth() < lastHealth && !paused) {
            warning("Paused due to taking damage.");
            pausedBySafety = true;
            paused = true;
        }

        float health = mc.player.getHealth();
        float threshold = lowHealthThreshold.get() * 2f;

        if (pauseOnLowHealth.get() && health <= threshold && !paused) {
            warning("Paused due to low health.");
            pausedBySafety = true;
            paused = true;
        }

        if (paused && pausedBySafety && health > threshold && health >= lastHealth) {
            info("Health restored. Resuming.");
            paused = false;
            pausedBySafety = false;
        }

        if (paused) {
            mc.inGameHud.setOverlayMessage(net.minecraft.text.Text.literal("§cGridLock Paused"), false);
            lastHealth = mc.player.getHealth();
            return;
        }

        // ── Max distance ──
        if (useMaxDistance.get() && mc.player.getPos().distanceTo(origin) > maxDistance.get()) {
            info("Reached max distance of %d blocks. Disabling.", maxDistance.get());
            toggle();
            return;
        }

        // ── Advance waypoint when close ──
        Vec3d playerFlat = mc.player.getPos().multiply(1, 0, 1);
        Vec3d targetFlat = currentTarget.multiply(1, 0, 1);
        if (playerFlat.squaredDistanceTo(targetFlat) < 30 * 30) {
            calculateNextTarget();
        }

        // ── Yaw toward target ──
        double dx = currentTarget.x - mc.player.getX();
        double dz = currentTarget.z - mc.player.getZ();
        if (dx * dx + dz * dz > 0.1) {
            double targetYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90;
            float currentYaw = mc.player.getYaw();
            float diff = MathHelper.wrapDegrees((float) targetYaw - currentYaw);
            float change = MathHelper.clamp(diff, -turnSpeed.get().floatValue(), turnSpeed.get().floatValue());
            mc.player.setYaw(currentYaw + change);
        }

        // ── Terrain-reading altitude (floor + ceiling + lookahead) ──
        if (controlAltitude.get()) {
            double floorY = getGroundY(mc.player.getX(), mc.player.getZ());
            double ceilY  = getCeilingY(mc.player.getX(), mc.player.getZ());

            // Lookahead: sample terrain ahead in the current direction of travel
            double horizLen = Math.sqrt(dx * dx + dz * dz);
            double la = lookaheadBlocks.get();
            double fwdX = horizLen > 0.1 ? mc.player.getX() + (dx / horizLen) * la : mc.player.getX();
            double fwdZ = horizLen > 0.1 ? mc.player.getZ() + (dz / horizLen) * la : mc.player.getZ();
            double aheadFloor = getGroundY(fwdX, fwdZ);
            double aheadCeil  = getCeilingY(fwdX, fwdZ);

            // Worst-case floor (highest) and best-case ceiling (lowest)
            double effectiveFloor = Math.max(
                floorY  != Double.MIN_VALUE ? floorY  : -64,
                aheadFloor != Double.MIN_VALUE ? aheadFloor : -64
            );
            double effectiveCeil = Math.min(
                ceilY   != Double.MIN_VALUE ? ceilY   : 320,
                aheadCeil  != Double.MIN_VALUE ? aheadCeil  : 320
            );

            double idealY    = effectiveFloor + flightAltitude.get();
            double ceilLimit = effectiveCeil  - ceilingClearance.get();

            if (idealY > ceilLimit) {
                // Gap too tight — split the difference
                idealY = (effectiveFloor + effectiveCeil) * 0.5;
            }

            double prevY = currentTarget.y;
            double newY  = MathHelper.clamp(idealY, prevY - maxSinkRate.get(), prevY + maxClimbRate.get());
            currentTarget = new Vec3d(currentTarget.x, newY, currentTarget.z);

            // Pitch toward height target
            double dist      = Math.sqrt(dx * dx + dz * dz);
            double heightDiff = currentTarget.y - mc.player.getY();
            double targetPitch = -Math.toDegrees(Math.atan2(heightDiff, Math.max(dist, 1.0)));
            targetPitch = MathHelper.clamp(targetPitch, -45, 45);
            mc.player.setPitch(MathHelper.lerp(0.1f, mc.player.getPitch(), (float) targetPitch));
        }

        // Anti-stall: pitch down if horizontal speed is too low
        if (mc.player.getVelocity().horizontalLength() < 1.0) {
            mc.player.setPitch(MathHelper.lerp(0.2f, mc.player.getPitch(), 40.0f));
        }

        lastHealth = mc.player.getHealth();
    }


    // ─────────────────────────── Pattern logic ───────────────────────────
    private void calculateNextTarget() {
        int space = chunkSpacing.get() * 16;

        if (currentTarget == null) {
            // Set initial target based on pattern
            if (pattern.get() == Pattern.Circle) {
                double radius = circleRadius.get() * 16;
                currentTarget = new Vec3d(origin.x + radius, origin.y, origin.z);
                circleCurrentSegment = 1;
            } else { // Default for spiral/grid/lawnmower/random
                currentTarget = new Vec3d(origin.x, origin.y, origin.z).add(getDirectionOffset(direction, space));
                stepsInLeg = 1;
            }
            return;
        }

        Pattern p = pattern.get();

        if (p == Pattern.SquareSpiral || p == Pattern.DiagonalGrid) {
            stepsInLeg++;
            if (stepsInLeg >= stepSize) {
                direction = (direction + 1) % 4;
                stepsInLeg = 0;
                if (direction % 2 == 0) stepSize++;
            }
            Vec3d offset = (p == Pattern.SquareSpiral)
                ? getDirectionOffset(direction, space)
                : getDiagonalDirectionOffset(direction, space);
            currentTarget = new Vec3d(currentTarget.x + offset.x, mc.player.getY(), currentTarget.z + offset.z);

        } else if (p == Pattern.Lawnmower) {
            int shiftStep = (lawnmowerShift.get() == ShiftDirection.Right) ? 1 : 3;
            int shiftDir  = (baseDir + shiftStep) % 4;
            Vec3d offset;
            if (lawnmowerReturning) {
                offset = getDirectionOffset(shiftDir, space);
                lawnmowerReturning = false;
                stepsInLeg = 0;
                lawnmowerForward = !lawnmowerForward;
                direction = lawnmowerForward ? baseDir : (baseDir + 2) % 4;
            } else {
                offset = getDirectionOffset(direction, space);
                stepsInLeg++;
                if (stepsInLeg >= lawnmowerLength.get()) lawnmowerReturning = true;
            }
            currentTarget = new Vec3d(currentTarget.x + offset.x, mc.player.getY(), currentTarget.z + offset.z);

        } else if (p == Pattern.ArchimedeanSpiral) {
            double b = space / (2.0 * Math.PI);
            double currentRadius = b * spiralTheta;
            spiralTheta += space / (currentRadius + space);
            double r = b * spiralTheta;
            currentTarget = new Vec3d(
                origin.x + r * Math.cos(spiralTheta),
                mc.player.getY(),
                origin.z + r * Math.sin(spiralTheta)
            );
        } else if (p == Pattern.Circle) {
            circleCurrentSegment++;
            double radius = circleRadius.get() * 16;
            int segments = circleSegments.get();
            double angle = (2.0 * Math.PI / segments) * circleCurrentSegment;

            currentTarget = new Vec3d(
                origin.x + radius * Math.cos(angle),
                mc.player.getY(),
                origin.z + radius * Math.sin(angle)
            );
        } else if (p == Pattern.Random) {
            double radius = randomMaxRadius.get() * 16;
            double angle = Math.random() * 2 * Math.PI;
            double r = radius * Math.sqrt(Math.random()); // Distribute points evenly across the circle area

            currentTarget = new Vec3d(
                origin.x + r * Math.cos(angle),
                mc.player.getY(),
                origin.z + r * Math.sin(angle)
            );
        }
    }

    // ─────────────────────────── Helpers ───────────────────────────
    private int getInitialDirection() {
        return switch (lawnmowerDirection.get()) {
            case South -> 0; case East -> 1; case North -> 2; case West -> 3;
        };
    }

    private Vec3d getDirectionOffset(int dir, double dist) {
        return switch (dir) {
            case 0 -> new Vec3d(0, 0, dist);
            case 1 -> new Vec3d(dist, 0, 0);
            case 2 -> new Vec3d(0, 0, -dist);
            case 3 -> new Vec3d(-dist, 0, 0);
            default -> Vec3d.ZERO;
        };
    }

    private Vec3d getDiagonalDirectionOffset(int dir, double dist) {
        return switch (dir) {
            case 0 -> new Vec3d(dist, 0, dist);
            case 1 -> new Vec3d(dist, 0, -dist);
            case 2 -> new Vec3d(-dist, 0, -dist);
            case 3 -> new Vec3d(-dist, 0, dist);
            default -> Vec3d.ZERO;
        };
    }

    /** Fires a downward raycast and returns the Y of the first solid block below. */
    private double getGroundY(double x, double z) {
        if (mc.world == null || mc.player == null) return Double.MIN_VALUE;
        double startY = mc.player.getY();
        var result = mc.world.raycast(new RaycastContext(
            new Vec3d(x, startY, z),
            new Vec3d(x, startY - 256, z),
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.ANY,
            mc.player
        ));
        return result != null ? result.getPos().y : Double.MIN_VALUE;
    }

    /** Fires an upward raycast and returns the Y of the first solid block above (ceiling). */
    private double getCeilingY(double x, double z) {
        if (mc.world == null || mc.player == null) return Double.MIN_VALUE;
        double startY = mc.player.getY();
        var result = mc.world.raycast(new RaycastContext(
            new Vec3d(x, startY, z),
            new Vec3d(x, startY + 256, z),
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.ANY,
            mc.player
        ));
        return result != null ? result.getPos().y : Double.MIN_VALUE;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    private void togglePause() {
        paused = !paused;
        if (!paused) pausedBySafety = false;
        info(paused ? "Paused." : "Resumed.");
    }
}
