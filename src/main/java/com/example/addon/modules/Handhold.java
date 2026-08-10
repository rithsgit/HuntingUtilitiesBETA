package com.example.addon.modules;

import java.lang.reflect.Method;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class Handhold extends Module {
    
    // ─── Enums ────────────────────────────────────────────────────────────────────
    public enum Role { Leader, Follower }
    public enum OrbitSide { Left, Right }
    
    private enum FollowerState { 
        TRACKING,        
        PANIC_BOOST,     
        WAITING          
    }
    
    private enum LeaderState {
        NORMAL,
        SLOWING_DOWN
    }

    // ─── Setting Groups ───────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // ─── General Settings ─────────────────────────────────────────────────────────
    public final Setting<String> targetName = sgGeneral.add(new StringSetting.Builder()
        .name("target")
        .description("Leader's name if you are Follower, Follower's name if you are Leader.")
        .defaultValue("")
        .build()
    );

    private final Setting<Role> role = sgGeneral.add(new EnumSetting.Builder<Role>()
        .name("role")
        .description("Are you leading the flight, or following?")
        .defaultValue(Role.Follower)
        .onChanged(v -> {
            if (v == Role.Leader) info("Leader mode: Watching for disconnects.");
            else info("Follower mode: Tracking target.");
        })
        .build()
    );

    private final Setting<Boolean> disableWhenTargetLands = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-target-lands")
        .description("Disable Handhold and Rocket Pilot when the target stops flying")
        .defaultValue(true)
        .visible(() -> role.get() == Role.Follower)
        .build()
    );

    // ─── Leader Pace Control Settings ─────────────────────────────────────────────
    private final Setting<Boolean> enablePaceControl = sgGeneral.add(new BoolSetting.Builder()
        .name("pace-control")
        .description("Automatically slow down if you pull too far ahead of the follower.")
        .defaultValue(true)
        .visible(() -> role.get() == Role.Leader)
        .build()
    );

    private final Setting<Double> maxLeadDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-lead-distance")
        .description("Horizontal distance before starting to slow down.")
        .defaultValue(50.0)
        .min(10.0).max(128.0)
        .sliderRange(20.0, 100.0)
        .visible(() -> role.get() == Role.Leader && enablePaceControl.get())
        .build()
    );

    private final Setting<Double> resumeDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("resume-distance")
        .description("Horizontal distance to the follower before resuming normal speed.")
        .defaultValue(30.0)
        .min(5.0).max(100.0)
        .sliderRange(10.0, 50.0)
        .visible(() -> role.get() == Role.Leader && enablePaceControl.get())
        .build()
    );

    private final Setting<Double> slowdownPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("slowdown-pitch")
        .description("How aggressively to pitch up to bleed speed (negative = up).")
        .defaultValue(-35.0)
        .min(-60.0).max(-5.0)
        .sliderRange(-50.0, -10.0)
        .visible(() -> role.get() == Role.Leader && enablePaceControl.get())
        .build()
    );

    // ─── Proximity & Orbiting Settings (Follower) ─────────────────────────────────
    private final Setting<Double> minFollowDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-follow-distance")
        .description("If closer than this, orbit instead of aiming directly at them.")
        .defaultValue(5.0)
        .min(1.0).max(20.0)
        .sliderRange(1.0, 10.0)
        .visible(() -> role.get() == Role.Follower)
        .build()
    );

    private final Setting<Double> orbitOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("orbit-offset")
        .description("How many degrees to shift your yaw when orbiting too close.")
        .defaultValue(25.0)
        .min(5.0).max(90.0)
        .sliderRange(10.0, 45.0)
        .visible(() -> role.get() == Role.Follower)
        .build()
    );

    private final Setting<OrbitSide> orbitSide = sgGeneral.add(new EnumSetting.Builder<OrbitSide>()
        .name("orbit-side")
        .description("Which side to orbit on when you get too close.")
        .defaultValue(OrbitSide.Left)
        .visible(() -> role.get() == Role.Follower)
        .build()
    );

    // ─── Rotation Settings (Follower) ─────────────────────────────────────────────
    private final Setting<Boolean> lookAtTarget = sgRotation.add(new BoolSetting.Builder()
        .name("look-at-target")
        .description("Always keep your camera aimed at the target.")
        .defaultValue(true)
        .visible(() -> role.get() == Role.Follower)
        .build()
    );

    private final Setting<Double> rotationSpeed = sgRotation.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("How smoothly to turn towards the target (lower = smoother).")
        .defaultValue(0.1)
        .min(0.01).max(1.0)
        .sliderRange(0.02, 0.5)
        .visible(() -> role.get() == Role.Follower)
        .build()
    );

    private final Setting<Boolean> limitRotationSpeed = sgRotation.add(new BoolSetting.Builder()
        .name("limit-rotation-speed")
        .description("Caps rotation speed per tick to reduce anti-cheat flags.")
        .defaultValue(true)
        .visible(() -> role.get() == Role.Follower)
        .build()
    );

    private final Setting<Double> maxRotationPerTick = sgRotation.add(new DoubleSetting.Builder()
        .name("max-rotation-per-tick")
        .description("Maximum degrees to rotate per tick.")
        .defaultValue(20.0)
        .min(1.0).max(90.0)
        .sliderRange(5.0, 45.0)
        .visible(() -> limitRotationSpeed.get() && role.get() == Role.Follower)
        .build()
    );

    // ─── Safety Settings ──────────────────────────────────────────────────────────
    private final Setting<Boolean> safetyDisconnect = sgSafety.add(new BoolSetting.Builder()
        .name("safety-disconnect")
        .description("Triggers a panic rocket towards their last location, then DCs if they aren't found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> disconnectDelay = sgSafety.add(new DoubleSetting.Builder()
        .name("disconnect-delay")
        .description("Seconds to fly towards last location before giving up and disconnecting.")
        .defaultValue(4.0)
        .min(1.0).max(15.0)
        .sliderRange(1.0, 10.0)
        .visible(safetyDisconnect::get)
        .build()
    );

    private final Setting<Boolean> pauseOnObstacle = sgSafety.add(new BoolSetting.Builder()
        .name("pause-on-obstacle")
        .description("Stops looking at target if a wall is in the way.")
        .defaultValue(true)
        .visible(() -> role.get() == Role.Follower)
        .build()
    );

    private final Setting<Integer> obstaclePauseTicks = sgSafety.add(new IntSetting.Builder()
        .name("obstacle-pause-ticks")
        .description("How many ticks to pause tracking when an obstacle is hit.")
        .defaultValue(15)
        .min(5).max(40)
        .sliderRange(5, 40)
        .visible(() -> pauseOnObstacle.get() && role.get() == Role.Follower)
        .build()
    );

    // ─── Internal State ───────────────────────────────────────────────────────────
    private static Method getFlagMethod;

    static {
        try {
            getFlagMethod = Entity.class.getDeclaredMethod("getFlag", int.class);
            getFlagMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            Tim.LOG.error("Failed to find getFlag method", e);
        }
    }

    private boolean wasTargetFlying = false;
    private boolean forcedRocketPilot = false;
    private int obstaclePauseTimer = 0;
    private boolean hasWarnedNotFound = false;
    private boolean wasInWorld = false;

    // Follower State Machine
    private FollowerState followerState = FollowerState.TRACKING;
    private float lastKnownYaw = 0;
    private int panicTimer = 0;
    private int waitTimerTicks = 0;
    private boolean hasFiredPanicRocket = false;

    // Leader State Machine
    private LeaderState leaderState = LeaderState.NORMAL;
    private RocketPilot.FlightMode savedRpMode = RocketPilot.FlightMode.Normal;
    private boolean savedRpTargetY = true;

    public Handhold() {
        super(Tim.CATEGORY, "handhold", "Follow a player or lead with mutual safety disconnects.");
    }

    @Override
    public void onActivate() {
        wasTargetFlying = false;
        forcedRocketPilot = false;
        obstaclePauseTimer = 0;
        hasWarnedNotFound = false;
        wasInWorld = false;
        resetFollowerPanicState();
        resetLeaderSlowdownState();
        
        if (role.get() == Role.Leader) info("Leading %s. Watching for disconnects.", targetName.get());
        else info("Following %s.", targetName.get());
    }

    @Override
    public void onDeactivate() {
        if (forcedRocketPilot) {
            RocketPilot rp = Modules.get().get(RocketPilot.class);
            if (rp != null && rp.isActive()) rp.toggle();
            forcedRocketPilot = false;
        }
        resetFollowerPanicState();
        resetLeaderSlowdownState(); // Always restore RocketPilot settings when module turns off
    }

    private void resetFollowerPanicState() {
        followerState = FollowerState.TRACKING;
        lastKnownYaw = 0;
        panicTimer = 0;
        waitTimerTicks = 0;
        hasFiredPanicRocket = false;
    }

    private void resetLeaderSlowdownState() {
        if (leaderState == LeaderState.SLOWING_DOWN) {
            restoreRocketPilot();
        }
        leaderState = LeaderState.NORMAL;
    }

    private void overrideRocketPilot() {
        RocketPilot rp = Modules.get().get(RocketPilot.class);
        if (rp != null && rp.isActive()) {
            savedRpMode = rp.flightMode.get();
            savedRpTargetY = rp.useTargetY.get();
            
            // Force RocketPilot to stop controlling pitch and firing rockets
            rp.flightMode.set(RocketPilot.FlightMode.None);
            rp.useTargetY.set(false);
        }
    }

    private void restoreRocketPilot() {
        RocketPilot rp = Modules.get().get(RocketPilot.class);
        if (rp != null && rp.isActive()) {
            rp.flightMode.set(savedRpMode);
            rp.useTargetY.set(savedRpTargetY);
        }
    }

    private PlayerEntity getTarget() {
        if (targetName.get() == null || targetName.get().isEmpty()) return null;
        if (mc.world == null) return null;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player &&
                player.getName().getString().equalsIgnoreCase(targetName.get())) {
                return player;
            }
        }
        return null;
    }

    private boolean isFallFlying(Entity entity) {
        if (getFlagMethod == null) return false;
        try {
            return (boolean) getFlagMethod.invoke(entity, 7);
        } catch (Exception e) {
            return false;
        }
    }

    private void forceDisconnect(String reason) {
        if (mc.player != null && mc.player.networkHandler != null && mc.player.networkHandler.getConnection() != null) {
            mc.player.networkHandler.getConnection().disconnect(Text.of(reason));
        }
    }

    private void firePanicRocket() {
        if (hasFiredPanicRocket || !mc.player.isGliding()) return;

        if (mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            hasFiredPanicRocket = true;
            return;
        }

        FindItemResult rocketResult = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (rocketResult.found()) {
            int prevSlot = mc.player.getInventory().selectedSlot;
            InvUtils.swap(rocketResult.slot(), false);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swap(prevSlot, false);
            hasFiredPanicRocket = true;
        }
    }

    private void lookAtSmooth(Vec3d target) {
        Vec3d diff = target.subtract(mc.player.getEyePos());
        if (diff.lengthSquared() < 0.01) return;

        double targetYawExact = Math.toDegrees(Math.atan2(-diff.x, diff.z));
        float targetYaw = (float) targetYawExact;
        
        double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        
        if (horizontalDist < minFollowDistance.get()) {
            float offset = orbitOffset.get().floatValue();
            if (orbitSide.get() == OrbitSide.Right) offset = -offset;
            targetYaw += offset;
        }
        
        float currentYaw = mc.player.getYaw();
        float diffYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        
        float desiredChange = diffYaw * rotationSpeed.get().floatValue();
        
        if (limitRotationSpeed.get()) {
            desiredChange = MathHelper.clamp(desiredChange, 
                -maxRotationPerTick.get().floatValue(), 
                 maxRotationPerTick.get().floatValue());
        }
        
        if (Math.abs(desiredChange) < 0.1f) return;
        
        float newYaw = currentYaw + desiredChange;
        
        mc.player.setYaw(newYaw);
        mc.player.bodyYaw = newYaw;
        mc.player.headYaw = newYaw;
    }

    private boolean isObstacleInWay(Vec3d targetPos) {
        if (!pauseOnObstacle.get()) return false;
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            mc.player.getEyePos(), targetPos, RaycastContext.ShapeType.COLLIDER, 
            RaycastContext.FluidHandling.NONE, mc.player
        ));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        boolean targetExists = getTarget() != null;

        // ═══════════════════════════════════════════════════════════════════════
        // FOLLOWER LOGIC (Panic Boost State Machine)
        // ═══════════════════════════════════════════════════════════════════════
        if (role.get() == Role.Follower && safetyDisconnect.get() && !targetName.get().isEmpty()) {
            
            if (wasInWorld && !targetExists && followerState == FollowerState.TRACKING) {
                lastKnownYaw = mc.player.getYaw(); 
                followerState = FollowerState.PANIC_BOOST;
                panicTimer = 3; 
                waitTimerTicks = (int)(disconnectDelay.get() * 20.0); 
                hasFiredPanicRocket = false;
                warning("Target lost visual! Firing panic rocket...");
            }

            if (followerState == FollowerState.PANIC_BOOST) {
                mc.player.setYaw(lastKnownYaw);
                mc.player.bodyYaw = lastKnownYaw;
                mc.player.headYaw = lastKnownYaw;
                firePanicRocket();
                panicTimer--;
                if (panicTimer <= 0) followerState = FollowerState.WAITING;
                return; 
            }

            if (followerState == FollowerState.WAITING) {
                mc.player.setYaw(lastKnownYaw);
                mc.player.bodyYaw = lastKnownYaw;
                mc.player.headYaw = lastKnownYaw;

                waitTimerTicks--;
                
                if (targetExists) {
                    info("Target re-acquired! Resuming normal tracking.");
                    resetFollowerPanicState();
                } else if (waitTimerTicks <= 0) {
                    error("Time ran out. Forcing safety disconnect...");
                    forceDisconnect("[Handhold] Safety Disconnect: Lost " + targetName.get() + ".");
                    return;
                }
                return; 
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // SAFETY DISCONNECT (Applies to both, triggers if target just flat out logs out)
        // ═══════════════════════════════════════════════════════════════════════
        if (safetyDisconnect.get() && !targetName.get().isEmpty()) {
            if (wasInWorld && !targetExists && role.get() == Role.Leader) {
                error("%s disconnected! Forcing safety disconnect...", targetName.get());
                forceDisconnect("[Handhold] Safety Disconnect: " + targetName.get() + " left the server.");
                return;
            }
        }
        wasInWorld = targetExists;

        // Handle waiting for target to load in
        if (!targetExists) {
            if (!hasWarnedNotFound && !targetName.get().isEmpty()) {
                warning("Waiting for %s...", targetName.get());
                hasWarnedNotFound = true;
            }
            if (forcedRocketPilot) {
                RocketPilot rp = Modules.get().get(RocketPilot.class);
                if (rp != null && rp.isActive()) rp.toggle();
                forcedRocketPilot = false;
            }
            return;
        } else {
            if (hasWarnedNotFound) {
                info("Locked onto %s.", targetName.get());
                hasWarnedNotFound = false;
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // LEADER LOGIC (Dynamic Pace Control)
        // ═══════════════════════════════════════════════════════════════════════
        if (role.get() == Role.Leader) {
            if (!enablePaceControl.get() || !mc.player.isGliding()) {
                if (leaderState == LeaderState.SLOWING_DOWN) restoreRocketPilot();
                leaderState = LeaderState.NORMAL;
                return;
            }

            PlayerEntity follower = getTarget();
            // Calculate exact horizontal distance (ignore Y differences)
            double dx = mc.player.getX() - follower.getX();
            double dz = mc.player.getZ() - follower.getZ();
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            if (leaderState == LeaderState.NORMAL) {
                if (horizontalDist > maxLeadDistance.get()) {
                    leaderState = LeaderState.SLOWING_DOWN;
                    overrideRocketPilot();
                    info("Follower falling behind. Slowing down...");
                }
            } else if (leaderState == LeaderState.SLOWING_DOWN) {
                if (horizontalDist < resumeDistance.get()) {
                    leaderState = LeaderState.NORMAL;
                    restoreRocketPilot();
                    info("Follower caught up. Resuming normal flight.");
                } else {
                    // Force nose up to bleed horizontal speed safely
                    mc.player.setPitch(slowdownPitch.get().floatValue());
                    mc.player.bodyYaw = mc.player.getYaw();
                    mc.player.headYaw = mc.player.getYaw();
                }
            }
            return; 
        }

        // ═══════════════════════════════════════════════════════════════════════
        // STANDARD FOLLOWER TRACKING LOGIC
        // ═══════════════════════════════════════════════════════════════════════
        PlayerEntity target = getTarget(); 
        boolean targetFlying = isFallFlying(target);

        if (lookAtTarget.get()) {
            if (obstaclePauseTimer > 0) {
                obstaclePauseTimer--;
            } else {
                Vec3d lookPos = targetFlying ? 
                    target.getPos().add(target.getVelocity().multiply(5)) : 
                    target.getPos();
                    
                if (mc.player.isGliding() && isObstacleInWay(lookPos)) {
                    obstaclePauseTimer = obstaclePauseTicks.get();
                } else {
                    lookAtSmooth(lookPos);
                }
            }
        }

        if (targetFlying) {
            RocketPilot rp = Modules.get().get(RocketPilot.class);
            if (rp != null) {
                if (!rp.flightPattern.get().equals(RocketPilot.FlightPattern.Manual)) {
                    rp.flightPattern.set(RocketPilot.FlightPattern.Manual);
                    info("Rocket Pilot pattern set to Manual for Handhold.");
                }

                if (!rp.isActive()) {
                    rp.toggle();
                    forcedRocketPilot = true;
                    info("Target started flying, enabled Rocket Pilot.");
                }
            }
        } else {
            if (wasTargetFlying) {
                if (disableWhenTargetLands.get()) {
                    if (forcedRocketPilot) {
                        RocketPilot rp = Modules.get().get(RocketPilot.class);
                        if (rp != null && rp.isActive()) rp.toggle();
                        forcedRocketPilot = false;
                    }
                    info("Target landed, disabling Handhold.");
                    this.toggle();
                    return;
                } else {
                    if (forcedRocketPilot) {
                        RocketPilot rp = Modules.get().get(RocketPilot.class);
                        if (rp != null && rp.isActive()) rp.toggle();
                        forcedRocketPilot = false;
                        info("Target landed, disabled Rocket Pilot.");
                    }
                }
            }
        }

        wasTargetFlying = targetFlying;
    }

    @Override
    public String getInfoString() {
        if (role.get() == Role.Leader) {
            if (leaderState == LeaderState.SLOWING_DOWN) return "Slowing Down ⏳";
            return "Leading: " + (targetName.get().isEmpty() ? "None" : targetName.get());
        }
        
        if (followerState == FollowerState.PANIC_BOOST) return "Panic Boost!";
        if (followerState == FollowerState.WAITING) {
            float remainingSecs = waitTimerTicks / 20.0f;
            return String.format("DC in %.1fs", remainingSecs);
        }

        PlayerEntity target = getTarget();
        if (target == null) return "Searching...";
        return target.getName().getString() + (isFallFlying(target) ? " ✈" : " 👁");
    }
}