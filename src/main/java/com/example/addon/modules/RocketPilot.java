package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.InvUtils;
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
import meteordevelopment.orbit.EventHandler;


public class RocketPilot extends Module {

    private long lastOscillationMsg = 0;
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgPitch40 = settings.createGroup("Pitch40");
    private final SettingGroup sgOscillation = settings.createGroup("Oscillation");
    private final SettingGroup sgDrunk = settings.createGroup("DrunkPilot");
    private final SettingGroup sgFlightSafety = settings.createGroup("Flight Safety");
    private final SettingGroup sgPlayerSafety = settings.createGroup("Player Safety");

    // ─────────────────────────────────────── Flight ───────────────────────────────────────
    public final Setting<Boolean> useTargetY = sgFlight.add(new BoolSetting.Builder()
        .name("use-target-y")
        .description("Whether to maintain a specific Y level.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> targetY = sgFlight.add(new DoubleSetting.Builder()
        .name("target-y")
        .description("The upper Y level limit (ceiling).")
        .defaultValue(120.0)
        .min(-64)
        .max(320)
        .sliderRange(0, 256)
        .visible(useTargetY::get)
        .build()
    );

    public final Setting<Double> flightTolerance = sgFlight.add(new DoubleSetting.Builder()
        .name("flight-tolerance")
        .description("The drop range allowed below target Y.")
        .defaultValue(2.0)
        .min(0.5)
        .max(10.0)
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

    // ─────────────────────────────────────── Pitch40 Mode ───────────────────────────────────────
    public final Setting<Boolean> pitch40Mode = sgPitch40.add(new BoolSetting.Builder()
        .name("pitch40-mode")
        .description("A flight mode that maintains a negative pitch to stay within a Y-level range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> pitch40UpperY = sgPitch40.add(new DoubleSetting.Builder()
        .name("upper-y")
        .description("The upper Y-level to stay below.")
        .defaultValue(120.0)
        .min(-64)
        .max(320)
        .sliderRange(0, 256)
        .visible(pitch40Mode::get)
        .build()
    );

    private final Setting<Double> pitch40LowerY = sgPitch40.add(new DoubleSetting.Builder()
        .name("lower-y")
        .description("The lower Y-level to stay above. Rockets will be used below this level.")
        .defaultValue(110.0)
        .min(-64)
        .max(320)
        .sliderRange(0, 256)
        .visible(pitch40Mode::get)
        .build()
    );

    private final Setting<Double> pitch40Smoothing = sgPitch40.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("How smoothly to adjust pitch in Pitch40 mode.")
        .defaultValue(0.05)
        .min(0.01)
        .max(1.0)
        .visible(pitch40Mode::get)
        .build()
    );

    private final Setting<Integer> pitch40BelowMinDelay = sgPitch40.add(new IntSetting.Builder()
        .name("below-min-delay")
        .description("Time in ms to wait below min Y before using rockets.")
        .defaultValue(8000)
        .min(1000)
        .sliderRange(1000, 10000)
        .visible(pitch40Mode::get)
        .build()
    );

    // ─────────────────────────────────────── Oscillation Mode ───────────────────────────────────────
    // MOVED UP: This must be defined before 'pitchSmoothing' because 'pitchSmoothing' references it.
    public final Setting<Boolean> oscillationMode = sgOscillation.add(new BoolSetting.Builder()
        .name("oscillation-mode")
        .description("Auto-oscillates pitch between ±40° for efficient high-speed travel.")
        .defaultValue(false)
        .onChanged(v -> {
            if (v && isActive() && mc.world != null && System.currentTimeMillis() - lastOscillationMsg > 1000) {
                info("Oscillation mode enabled.");
                lastOscillationMsg = System.currentTimeMillis();
            } else if (!v && isActive() && mc.world != null) {
                info("Oscillation disabled.");
            }
        })
        .build()
    );

    public final Setting<Double> pitchSmoothing = sgFlight.add(new DoubleSetting.Builder()
        .name("pitch-smoothing")
        .description("How smoothly the pitch changes in normal mode (0.0 - 1.0).")
        .defaultValue(0.15)
        .min(0.01)
        .max(1.0)
        .sliderRange(0.05, 0.5)
        .visible(() -> !oscillationMode.get()) // Now this reference is valid
        .build()
    );

    public final Setting<Double> oscillationSpeed = sgOscillation.add(new DoubleSetting.Builder()
        .name("oscillation-speed")
        .description("Speed of the pitch wave cycle (higher = faster transitions).")
        .defaultValue(0.08)
        .min(0.01)
        .max(0.5)
        .sliderRange(0.02, 0.2)
        .visible(oscillationMode::get)
        .build()
    );

    private final Setting<Integer> oscillationRocketDelay = sgOscillation.add(new IntSetting.Builder()
        .name("oscillation-rocket-delay")
        .description("The minimum delay between firing rockets in oscillation mode.")
        .defaultValue(350)
        .min(0)
        .sliderRange(0, 1000)
        .visible(oscillationMode::get)
        .build()
    );

    private final Setting<Boolean> oscillationRockets = sgOscillation.add(new BoolSetting.Builder()
        .name("oscillation-rockets")
        .description("Automatically fire rockets at the peak of the upward pitch.")
        .defaultValue(true)
        .visible(oscillationMode::get)
        .build()
    );

    // ─────────────────────────────────────── Drunk Pilot ───────────────────────────────────────
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
        .description("Maximum yaw change per update.")
        .defaultValue(120.0)
        .min(1.0)
        .max(180.0)
        .sliderRange(50.0, 180.0)
        .visible(drunkMode::get)
        .build()
    );
    
    private final Setting<Boolean> positiveCoordsOnly = sgDrunk.add(new BoolSetting.Builder()
        .name("positive-coords-only")
        .description("Biases drunk mode toward whichever directions increase X and Z based on the player's current quadrant.")
        .defaultValue(false)
        .visible(drunkMode::get)
        .build()
    );

    private final Setting<Double> drunkSmoothing = sgDrunk.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("How smoothly to rotate to the new direction (lower = smoother).")
        .defaultValue(0.05)
        .min(0.01)
        .max(1.0)
        .visible(drunkMode::get)
        .build()
    );

    // ─────────────────────────────────────── Flight Safety ───────────────────────────────────────
    private final Setting<Boolean> collisionAvoidance = sgFlightSafety.add(new BoolSetting.Builder()
        .name("collision-avoidance")
        .description("Attempts to avoid flying straight into walls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> avoidanceDistance = sgFlightSafety.add(new IntSetting.Builder()
        .name("avoidance-distance")
        .description("How far to look ahead for obstacles.")
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
        .description("Height above ground to trigger safe landing.")
        .defaultValue(20)
        .min(5)
        .sliderRange(10, 50)
        .visible(safeLanding::get)
        .build()
    );

    private final Setting<Boolean> limitRotationSpeed = sgFlightSafety.add(new BoolSetting.Builder()
        .name("limit-rotation-speed")
        .description("Limits how fast the module can rotate the player to bypass anti-cheat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxRotationPerTick = sgFlightSafety.add(new DoubleSetting.Builder()
        .name("max-rotation-per-tick")
        .description("Maximum degrees to rotate per tick.")
        .defaultValue(20.0)
        .min(1.0)
        .max(90.0)
        .sliderRange(5.0, 45.0)
        .visible(limitRotationSpeed::get)
        .build()
    );

    public final Setting<Integer> minRocketsWarning = sgFlightSafety.add(new IntSetting.Builder()
        .name("min-rockets-warning")
        .description("Warn when you have this many or fewer rockets left.")
        .defaultValue(16)
        .min(0)
        .sliderRange(5, 64)
        .build()
    );

    private final Setting<Boolean> autoLandOnLowRockets = sgFlightSafety.add(new BoolSetting.Builder()
        .name("auto-land-on-low-rockets")
        .description("Initiates a safe landing when rockets are critically low.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> autoLandThreshold = sgFlightSafety.add(new IntSetting.Builder()
        .name("auto-land-threshold")
        .description("Rocket count to trigger auto-landing.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .visible(autoLandOnLowRockets::get)
        .build()
    );

    private final Setting<Boolean> autoPark = sgFlightSafety.add(new BoolSetting.Builder()
        .name("auto-park")
        .description("Automatically lands and disables the module when a suitable location is reached.")
        .defaultValue(false)
        .build()
    );

    // ─────────────────────────────────────── Player Safety ───────────────────────────────────────
    private final Setting<Boolean> autoDisableOnLowHealth = sgPlayerSafety.add(new BoolSetting.Builder()
        .name("auto-disable-on-low-health")
        .description("Automatically disables the module if health is critically low with a totem equipped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lowHealthThreshold = sgPlayerSafety.add(new IntSetting.Builder()
        .name("low-health-threshold")
        .description("Health level (in hearts) to trigger auto-disable.")
        .defaultValue(3)
        .min(1)
        .max(10)
        .sliderRange(1, 5)
        .visible(autoDisableOnLowHealth::get)
        .build()
    );

    private final Setting<Boolean> disconnectOnTotemPop = sgPlayerSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-totem-pop")
        .description("Automatically disconnects from the server if a totem of undying is used while this module is active.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> disconnectAfterEmergencyLanding = sgPlayerSafety.add(new BoolSetting.Builder()
        .name("disconnect-after-emergency-landing")
        .description("If elytra is critically low and no replacement is available, performs a safe landing then disconnects.")
        .defaultValue(true)
        .build()
    );

    // ─────────────────────────────────────── Internal state ───────────────────────────────────────
    public long lastRocketTime = 0;
    private boolean needsTakeoffRocket = false;
    private boolean ascentMode = false;
    private boolean pitch40Climbing = false;
    private boolean pitch40Rocketing = false;
    private long pitch40BelowMinStartTime = 0;

    private float targetPitch = 0;
    private int waveTicks = 0;
    private int drunkTimer = 0;
    private float targetDrunkYaw = 0;
    private int currentDrunkDuration = 0;
    private boolean rocketsWarningSent = false;
    private int totemPops = 0;
    private boolean emergencyLanding = false;
    private int takeoffTimer = 0;

    public RocketPilot() {
        super(HuntingUtilities.CATEGORY, "rocket-pilot", "Automatic elytra + rocket flight with height maintenance and auto-takeoff.");
    }

    @Override
    public void onActivate() {
        lastRocketTime = 0;
        needsTakeoffRocket = false;
        waveTicks = 0;
        drunkTimer = 0;
        currentDrunkDuration = 0;
        ascentMode = false;
        pitch40Climbing = false;
        pitch40Rocketing = false;
        pitch40BelowMinStartTime = 0;
        rocketsWarningSent = false;
        emergencyLanding = false;
        takeoffTimer = 0;

        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        totemPops = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);

        if (useTargetY.get()) {
            info("Targeting Y level of %.1f.", targetY.get());
        } else {
            info("Y Level Target ignored.");
        }

        targetPitch = mc.player.getPitch();
        targetDrunkYaw = mc.player.getYaw();

        if (mc.player.isGliding()) return;
        if (!autoTakeoff.get()) return;

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
        info("Flying up!");
    }

    @Override
    public void onDeactivate() {
        needsTakeoffRocket = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (disconnectOnTotemPop.get()) {
            int currentPops = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
            if (currentPops > totemPops) {
                error("Totem popped! Disconnecting...");
                if (mc.player.networkHandler != null) {
                    mc.player.networkHandler.getConnection().disconnect(Text.literal("[RocketPilot] Disconnected on totem pop."));
                }
                toggle();
                return;
            }
        }

        if (autoDisableOnLowHealth.get()) {
            boolean hasTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
            if (hasTotem && mc.player.getHealth() <= lowHealthThreshold.get() * 2) {
                error("Health is critical (%.1f), disabling to prevent totem pop.", mc.player.getHealth());
                toggle();
                return;
            }
        }

        // Check elytra
        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) {
            error("Elytra missing — disabling.");
            toggle();
            return;
        }

        if (takeoffTimer > 0) takeoffTimer--;

        // Check disable on land
        if (disableOnLand.get() && mc.player.isOnGround() && !needsTakeoffRocket && takeoffTimer == 0) {
            info("Landed — disabling.");
            toggle();
            return;
        }

        replenishRockets();

        // If on ground and below target Y, try to take off again.
        if (isNearGround() && !mc.player.isGliding() && (!useTargetY.get() || mc.player.getY() < targetY.get())) {
            if (autoTakeoff.get() && countFireworks() > 0 && !needsTakeoffRocket) {
                targetPitch = -28.0f;
                mc.player.setPitch(targetPitch);
                if (mc.player.isOnGround()) mc.player.jump();
                needsTakeoffRocket = true;
                info("Flying up!");
            }
        }

        // ─────────────────────────────── Auto-takeoff logic ───────────────────────────────
        if (needsTakeoffRocket) {
            if (mc.player.isOnGround()) {
                mc.player.jump();
            } else if (mc.player.isGliding()) {
                if (shouldFireRocket() && countFireworks() > 0) {
                    fireRocket();
                    lastRocketTime = System.currentTimeMillis();
                }
                needsTakeoffRocket = false;
                takeoffTimer = 40;
            } else if (mc.player.getVelocity().y < -0.04) {
                // Not gliding, but falling -> try to deploy
                if (mc.player.networkHandler != null) {
                    mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }
            }
            return;
        }

        // ─────────────────────────────── Main flight logic ───────────────────────────────
        if (!mc.player.isGliding()) return;

        Float desiredPitch = null;
        
        // ─────────────────────────────── Safety Checks ───────────────────────────────
        boolean assistantHandling = false;
        ElytraAssistant assistant = Modules.get().get(ElytraAssistant.class);
        if (assistant != null && assistant.isAutoSwapEnabled()) {
            assistantHandling = true;
        }

        if (!assistantHandling) {
            double percent = getDurabilityPercent();
            if (percent <= 5.0) { // 5% durability threshold
                Integer newDura = swapToFreshElytra();
                if (newDura != null) {
                    info("Auto-swapped elytra, durability was low.");
                    emergencyLanding = false;
                } else if (!emergencyLanding && disconnectAfterEmergencyLanding.get()) {
                    emergencyLanding = true;
                    warning("No replacement elytra found! Initiating emergency landing...");
                }
            }
        }
        
        int rockets = countFireworks();
        if (rockets > 0 && rockets <= minRocketsWarning.get()) {
            if (!rocketsWarningSent) {
                warning("Low fireworks: only %d remaining!", rockets);
                rocketsWarningSent = true;
            }
        } else if (rockets > minRocketsWarning.get()) {
            rocketsWarningSent = false;
        }

        // 1. Critical Landing / Safety Overrides
        if (autoLandOnLowRockets.get() && rockets <= autoLandThreshold.get() && mc.player.isGliding()) {
            if (getDistanceToGround() <= landingHeight.get()) {
                desiredPitch = MathHelper.lerp(0.1f, mc.player.getPitch(), -10.0f);
                if (mc.player.isOnGround()) {
                    info("Safe landing complete (Low Rockets).");
                    toggle();
                }
            } else {
                desiredPitch = MathHelper.lerp(0.05f, mc.player.getPitch(), 20.0f);
            }
        }

        // ─────────────────────────────── Emergency Landing (Low Elytra, No Swap) ───────────────────────────────
        if (desiredPitch == null && emergencyLanding) {
            float current = mc.player.getPitch();
            int distToGround = getDistanceToGround();

            if (mc.player.isOnGround()) {
                info("Emergency landing complete.");
                if (disconnectAfterEmergencyLanding.get() && mc.player.networkHandler != null) {
                    mc.player.networkHandler.getConnection().disconnect(
                        Text.literal("[RocketPilot] Emergency landing complete — elytra critically low, no replacement found.")
                    );
                }
                toggle();
                return;
            }

            if (distToGround <= 8) {
                // Very close to ground — flare up sharply to bleed speed and land softly
                desiredPitch = MathHelper.lerp(0.15f, current, -20.0f);
            } else {
                // Descend gradually at a shallow angle
                desiredPitch = MathHelper.lerp(0.05f, current, 25.0f);
            }
        }

        // ─────────────────────────────── 2. Collision Avoidance ───────────────────────────────
        if (desiredPitch == null && collisionAvoidance.get() && mc.player.isGliding()) {
            // Don't avoid if looking down too much, safe landing should handle it.
            if (mc.player.getPitch() < 30) {
                Vec3d cameraPos = mc.player.getCameraPosVec(1.0F);
                Vec3d velocity = mc.player.getVelocity();
                Vec3d forward = velocity.normalize();
                
                // Cast 3 rays: Center, Left, Right
                Vec3d[] rays = new Vec3d[] {
                    forward,
                    forward.rotateY(0.5f), // ~30 degrees left
                    forward.rotateY(-0.5f) // ~30 degrees right
                };

                boolean obstacleDetected = false;
                for (Vec3d dir : rays) {
                    Vec3d endPos = cameraPos.add(dir.multiply(avoidanceDistance.get()));
                    BlockHitResult result = mc.world.raycast(new RaycastContext(cameraPos, endPos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
                    if (result.getType() == HitResult.Type.BLOCK) {
                        obstacleDetected = true;
                        break;
                    }
                }

                if (obstacleDetected) {
                    // Obstacle detected. Pull up.
                    float currentPitch = mc.player.getPitch();
                    // Pull up more sharply if we are going faster
                    double speed = mc.player.getVelocity().horizontalLength();
                    float pullUpStrength = (float) MathHelper.clamp(speed * 20, 20, 60);

                    desiredPitch = MathHelper.lerp(0.3f, currentPitch, -pullUpStrength);

                    // Fire a rocket to gain altitude quickly
                    if (shouldFireRocket() && countFireworks() > 0 && mc.player.getVelocity().y < 0.2) {
                        long now = System.currentTimeMillis();
                        if (now - lastRocketTime >= 200) {
                            fireRocket();
                            lastRocketTime = now;
                        }
                    }
                }
            }
        }

        // ─────────────────────────────── 3. Safe Landing ───────────────────────────────
        if (desiredPitch == null && safeLanding.get() && getDistanceToGround() <= landingHeight.get()) {
            // Flare up slightly to slow down and avoid crash
            desiredPitch = MathHelper.lerp(0.1f, mc.player.getPitch(), -10.0f);
        }

        // ─────────────────────────────── 4. Normal Flight Modes ───────────────────────────────
        if (desiredPitch == null) {
            if (pitch40Mode.get()) {
                desiredPitch = handlePitch40Mode();
            } else if (oscillationMode.get()) {
                desiredPitch = handleOscillationMode();
            } else {
                handleFlightControl();
                if (useTargetY.get()) {
                    float currentPitch = mc.player.getPitch();
                    float smooth = pitchSmoothing.get().floatValue();
                    desiredPitch = currentPitch + (targetPitch - currentPitch) * smooth;
                }
            }

            // Drunk mode handles Yaw, so it can run alongside pitch modes
            if (drunkMode.get()) {
                double yDiff = Math.abs(mc.player.getY() - targetY.get());
                if (yDiff <= flightTolerance.get()) {
                    handleDrunkMode();
                } else {
                    targetDrunkYaw = mc.player.getYaw();
                    drunkTimer = 0;
                }
            }
        }

        // 3. Apply Pitch
        if (desiredPitch != null) {
            if (limitRotationSpeed.get()) {
                float currentPitch = mc.player.getPitch();
                float diff = desiredPitch - currentPitch;
                float max = maxRotationPerTick.get().floatValue();
                diff = MathHelper.clamp(diff, -max, max);
                mc.player.setPitch(currentPitch + diff);
            } else {
                mc.player.setPitch(desiredPitch);
            }
        }
    }

    private Float handleOscillationMode() {
        waveTicks++;
        // Calculate sine wave: Range -40 to +40
        double cycle = waveTicks * oscillationSpeed.get();
        float calculatedPitch = (float) (40.0 * Math.sin(cycle));

        if (oscillationRockets.get() && countFireworks() > 0) {
            // Check if near the peak of the upward swing (pitch approx -40)
            if (calculatedPitch < -38.0f && shouldFireRocket()) {
                long now = System.currentTimeMillis();
                if (now - lastRocketTime >= oscillationRocketDelay.get()) {
                    fireRocket();
                    lastRocketTime = now;
                }
            }
        }
        return calculatedPitch;
    }

    private Float handlePitch40Mode() {
        if (mc.player == null) return null;

        double currentY = mc.player.getY();
        double upperY = pitch40UpperY.get();
        double lowerY = pitch40LowerY.get();
        float smooth = pitch40Smoothing.get().floatValue();

        // Hysteresis logic: Climb if below lower limit, stop climbing if above upper limit
        if (currentY <= lowerY) {
            pitch40Climbing = true;
        } else if (currentY >= upperY) {
            pitch40Climbing = false;
            pitch40Rocketing = false;
        }

        // Momentum check: if below lowerY for too long, engage rockets
        if (currentY < lowerY) {
            if (pitch40BelowMinStartTime == 0) pitch40BelowMinStartTime = System.currentTimeMillis();
            if (System.currentTimeMillis() - pitch40BelowMinStartTime > pitch40BelowMinDelay.get()) pitch40Rocketing = true;
        } else {
            pitch40BelowMinStartTime = 0;
        }

        Float pitch;
        if (pitch40Climbing) {
            pitch = MathHelper.lerp(smooth, mc.player.getPitch(), -40f); // Pitch up to -40 degrees
        } else {
            // Pitch down to 40 degrees to descend and gain speed
            pitch = MathHelper.lerp(smooth, mc.player.getPitch(), 40f);
        }

        if (pitch40Rocketing) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.get()) {
                if (shouldFireRocket() && countFireworks() > 0) {
                    fireRocket();
                    lastRocketTime = now;
                }
            }
        }
        return pitch;
    }

    private void handleDrunkMode() {
        if (drunkTimer++ >= currentDrunkDuration) {
            float intensity = drunkIntensity.get().floatValue();
            
            if (positiveCoordsOnly.get()) {
                // ── Quadrant-aware "drift away from origin" heading ──
                // Minecraft yaw convention:
                //   0°    = South (+Z)
                //  -90°   = East  (+X)
                //   90°   = West  (-X)
                //  ±180°  = North (-Z)
                //
                // Goal: fly AWAY from 0,0 — deeper into the current quadrant.
                //
                //  Q1 (X≥0, Z≥0): South-East  [-90°,   0°]   (+X and +Z)
                //  Q2 (X<0, Z≥0): South-West  [  0°,  90°]   (-X and +Z)
                //  Q3 (X<0, Z<0): North-West  [ 90°, 180°]   (-X and -Z)
                //  Q4 (X≥0, Z<0): North-East  [-180°, -90°]  (+X and -Z)
                double x = mc.player.getX();
                double z = mc.player.getZ();
                boolean xNeg = x < 0;
                boolean zNeg = z < 0;

                float minYaw, maxYaw;
                if (!xNeg && !zNeg) {
                    // Q1: South-East
                    minYaw = -90.0f; maxYaw = 0.0f;
                } else if (xNeg && !zNeg) {
                    // Q2: South-West
                    minYaw = 0.0f; maxYaw = 90.0f;
                } else if (xNeg) {
                    // Q3: North-West
                    minYaw = 90.0f; maxYaw = 180.0f;
                } else {
                    // Q4: North-East
                    minYaw = -180.0f; maxYaw = -90.0f;
                }

                // Pick a truly uniform random heading anywhere in the full 90° quadrant window.
                // This avoids clustering near the midpoint diagonal.
                targetDrunkYaw = minYaw + (float) (Math.random() * (maxYaw - minYaw));
            } else {
                float changeYaw = (float) ((Math.random() - 0.5) * 2 * intensity);
                targetDrunkYaw = mc.player.getYaw() + changeYaw;
            }

            drunkTimer = 0;
            currentDrunkDuration = drunkInterval.get() + (int)(Math.random() * 10);
        }

        float currentYaw = mc.player.getYaw();
        float diffYaw = MathHelper.wrapDegrees(targetDrunkYaw - currentYaw);
        float change = diffYaw * drunkSmoothing.get().floatValue();

        if (limitRotationSpeed.get()) {
            float max = maxRotationPerTick.get().floatValue();
            change = MathHelper.clamp(change, -max, max);
        }

        mc.player.setYaw(currentYaw + change);
    }

    private void handleFlightControl() {
        double diff = 0;

        if (useTargetY.get()) {
            double currentY = mc.player.getY();
            double target = targetY.get();
            double tolerance = flightTolerance.get();
            diff = target - currentY;

            if (diff > tolerance) {
                ascentMode = true;
            } else if (diff <= 0) {
                ascentMode = false;
            }
        } else {
            ascentMode = true;
        }

        // ─── ROCKET FIRING LOGIC ───
        if (ascentMode) {
            long now = System.currentTimeMillis();
            long delay = rocketDelay.get();

            if (now - lastRocketTime >= delay && mc.player.getVelocity().y < 0.5) {
                if (shouldFireRocket() && countFireworks() > 0) {
                    fireRocket();
                    lastRocketTime = now;
                }
            }
        }

        // ─── IMPROVED PITCH CALCULATION ───
        if (!useTargetY.get()) return;

        float maxPitchUp = -45.0f;
        float maxPitchDown = 40.0f;

        if (Math.abs(diff) < 0.5) {
            targetPitch = 0.0f;
        } else {
            double scale = 10.0;
            float calculatedPitch = (float) (- (diff / scale) * 45.0f);
            targetPitch = MathHelper.clamp(calculatedPitch, maxPitchUp, maxPitchDown);
        }
    }

    public boolean shouldFireRocket() {
        if (mc.player == null) return false;
        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) return false;

        // Don't fire rockets when looking straight up or down.
        if (Math.abs(mc.player.getPitch()) > 70) return false;

        // Don't fire if moving too slowly (wasteful)
        if (!needsTakeoffRocket && mc.player.getVelocity().horizontalLength() < 0.3) return false;

        return elytra.getDamage() < elytra.getMaxDamage() - 1;
    }

    public double getDurabilityPercent() {
        if (mc.player == null) return 100.0;
        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) return 100.0;
        return 100.0 * (elytra.getMaxDamage() - elytra.getDamage()) / elytra.getMaxDamage();
    }

    private void replenishRockets() {
        // Check hotbar for rockets
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) return;
        }

        // Find rockets in main inventory
        int invSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) {
                invSlot = i;
                break;
            }
        }

        if (invSlot != -1) {
            int hotbarSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) {
                    hotbarSlot = i;
                    break;
                }
            }
            if (hotbarSlot == -1) {
                hotbarSlot = mc.player.getInventory().selectedSlot;
            }
            InvUtils.move().from(invSlot).toHotbar(hotbarSlot);
        }
    }

    private int countFireworks() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack != null && stack.isOf(Items.FIREWORK_ROCKET)) {
                count += stack.getCount();
            }
        }
        if (mc.player.getOffHandStack() != null && mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            count += mc.player.getOffHandStack().getCount();
        }
        return count;
    }

    private Integer swapToFreshElytra() {
        int bestSlot = -1;
        int bestDurability = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.ELYTRA)) {
                int durability = stack.getMaxDamage() - stack.getDamage();
                if (durability > bestDurability && durability > 50) {
                    bestSlot = i;
                    bestDurability = durability;
                }
            }
        }

        if (bestSlot != -1) {
            int slotId;
            if (bestSlot < 9) {
                slotId = 36 + bestSlot;
            } else {
                slotId = bestSlot;
            }
            InvUtils.move().from(slotId).toArmor(2);
            return bestDurability;
        }

        return null;
    }

    private boolean isNearGround() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.player.isOnGround()) return true;

        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int i = 1; i <= 3; i++) {
            pos.set(mc.player.getX(), mc.player.getY() - i, mc.player.getZ());
            if (mc.world.getBlockState(pos).isSolidBlock(mc.world, pos)) {
                return true;
            }
        }
        return false;
    }

    private int getDistanceToGround() {
        if (mc.player == null || mc.world == null) return 999;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int max = landingHeight.get();
        for (int i = 0; i <= max; i++) {
            pos.set(mc.player.getX(), mc.player.getY() - i, mc.player.getZ());
            if (!mc.world.getBlockState(pos).isAir()) {
                return i;
            }
        }
        return 999;
    }

    private void fireRocket() {
        if (mc.player == null || mc.interactionManager == null) return;

        int rocketSlot = -1;
        int selected = mc.player.getInventory().selectedSlot;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) {
                rocketSlot = i;
                break;
            }
        }

        if (rocketSlot == -1 && mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            rocketSlot = 40;
        }

        if (rocketSlot == -1) return;

        if (rocketSlot < 9) {
            mc.player.getInventory().selectedSlot = rocketSlot;
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.getInventory().selectedSlot = selected;
        } else {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        }
    }

    public boolean isEmergencyLanding() {
        return emergencyLanding;
    }
}