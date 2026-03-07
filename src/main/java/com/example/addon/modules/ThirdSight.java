package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.Perspective;

public class ThirdSight extends Module {

    public enum CameraMode { ThirdPerson, BirdsEye }
    public enum ShoulderSide { Right, Left }

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgShoulder = settings.createGroup("Shoulder");
    private final SettingGroup sgZoom     = settings.createGroup("Zoom");

    // ── General ──────────────────────────────────────────────────────────────

    public final Setting<CameraMode> cameraMode = sgGeneral.add(new EnumSetting.Builder<CameraMode>()
        .name("camera-mode")
        .description("ThirdPerson: standard over-the-shoulder. BirdsEye: camera below looking up at the player's back.")
        .defaultValue(CameraMode.ThirdPerson)
        .build()
    );

    public final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("Camera distance from the player. In BirdsEye mode this controls how far below.")
        .defaultValue(4.0)
        .min(1.0)
        .max(30.0)
        .sliderRange(1.0, 30.0)
        .build()
    );

    public final Setting<Boolean> freeLook = sgGeneral.add(new BoolSetting.Builder()
        .name("free-look")
        .description("Orbit the camera around the player without affecting movement direction. Disabled in BirdsEye mode.")
        .defaultValue(true)
        .visible(() -> cameraMode.get() == CameraMode.ThirdPerson)
        .build()
    );

    public final Setting<Double> sensitivity = sgGeneral.add(new DoubleSetting.Builder()
        .name("sensitivity")
        .description("Free-look mouse sensitivity.")
        .defaultValue(1.0)
        .min(1.0)
        .max(20.0)
        .sliderRange(1.0, 20.0)
        .visible(() -> cameraMode.get() == CameraMode.ThirdPerson && freeLook.get())
        .build()
    );

    // ── Shoulder ─────────────────────────────────────────────────────────────

    public final Setting<Boolean> shoulderEnabled = sgShoulder.add(new BoolSetting.Builder()
        .name("over-the-shoulder")
        .description("Offset the camera left or right for an over-the-shoulder look.")
        .defaultValue(false)
        .build()
    );

    public final Setting<ShoulderSide> shoulderSide = sgShoulder.add(new EnumSetting.Builder<ShoulderSide>()
        .name("side")
        .description("Which shoulder the camera sits behind.")
        .defaultValue(ShoulderSide.Right)
        .visible(shoulderEnabled::get)
        .build()
    );

    public final Setting<Double> shoulderOffset = sgShoulder.add(new DoubleSetting.Builder()
        .name("offset")
        .description("How far left or right the camera is shifted, in blocks.")
        .defaultValue(0.75)
        .min(0.1)
        .max(3.0)
        .sliderRange(0.1, 2.0)
        .visible(shoulderEnabled::get)
        .build()
    );

    public final Setting<Keybind> shoulderToggleKey = sgShoulder.add(new KeybindSetting.Builder()
        .name("toggle-key")
        .description("Press to flip between left and right shoulder while the module is active.")
        .defaultValue(Keybind.none())
        .visible(shoulderEnabled::get)
        .build()
    );

    public final Setting<Boolean> smoothTransitions = sgShoulder.add(new BoolSetting.Builder()
        .name("smooth-transitions")
        .description("Smoothly interpolate between camera positions.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> transitionSpeed = sgShoulder.add(new DoubleSetting.Builder()
        .name("transition-speed")
        .description("Speed of the smoothing.")
        .defaultValue(0.15)
        .min(0.01)
        .max(1.0)
        .sliderRange(0.05, 0.5)
        .visible(smoothTransitions::get)
        .build()
    );

    // ── Zoom ─────────────────────────────────────────────────────────────────

    public final Setting<Double> zoomDistance = sgZoom.add(new DoubleSetting.Builder()
        .name("zoom-distance")
        .description("Camera distance when zoomed in.")
        .defaultValue(2.0)
        .min(0.5)
        .max(30.0)
        .sliderRange(0.5, 10.0)
        .build()
    );

    public final Setting<Keybind> zoomKey = sgZoom.add(new KeybindSetting.Builder()
        .name("zoom-key")
        .description("Key to activate zoom.")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Boolean> zoomToggle = sgZoom.add(new BoolSetting.Builder()
        .name("toggle-mode")
        .description("If true, press to toggle zoom. If false, hold to zoom.")
        .defaultValue(false)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    // Independent camera yaw/pitch for free look.
    // Updated by ThirdSightMouseMixin via mouse delta.
    // In BirdsEye mode cameraYaw tracks player yaw and cameraPitch is locked to 90.
    public float cameraYaw   = 0f;
    public float cameraPitch = 0f;

    // Lateral offset read by ThirdSightCameraMixin.
    // Positive = right, negative = left.
    public float lateralOffset = 0f;
    private float targetLateralOffset = 0f;
    private double currentDistance = 4.0;
    private boolean isZooming = false;
    private boolean wasZoomKeyPressed = false;

    private Perspective previousPerspective = null;
    private boolean     wasKeyPressed       = false;

    public ThirdSight() {
        super(HuntingUtilities.CATEGORY, "third-sight",
            "Third-person camera with configurable distance, no block clipping, free look, BirdsEye mode, and shoulder offset.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        if (mc.player == null || mc.options == null) return;
        cameraYaw   = mc.player.getYaw();
        cameraPitch = mc.player.getPitch();
        previousPerspective = mc.options.getPerspective();
        mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        updateLateralOffset();
        if (smoothTransitions.get()) lateralOffset = 0f;

        currentDistance = distance.get();
        isZooming = false;
        wasZoomKeyPressed = false;
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null && previousPerspective != null) {
            mc.options.setPerspective(previousPerspective);
        }
        previousPerspective = null;
        lateralOffset = 0f;
        shoulderEnabled.set(false);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.options == null) return;

        if (cameraMode.get() == CameraMode.BirdsEye) {
            // Lock pitch downward — camera sits below and behind the player,
            // looking up at the back of their head toward the sky.
            cameraYaw   = mc.player.getYaw();
            cameraPitch = 90f;
        }

        // Re-enforce perspective in case setting changed or F5 was pressed.
        if (mc.options.getPerspective() != Perspective.THIRD_PERSON_BACK) {
            mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        }

        // Handle shoulder toggle keybind — flip side on press, not hold.
        if (shoulderEnabled.get()) {
            boolean isPressed = shoulderToggleKey.get().isPressed();
            if (isPressed && !wasKeyPressed) {
                shoulderSide.set(
                    shoulderSide.get() == ShoulderSide.Right
                        ? ShoulderSide.Left
                        : ShoulderSide.Right
                );
            }
            wasKeyPressed = isPressed;
        }

        // Handle zoom keybind
        boolean zoomPressed = zoomKey.get().isPressed();
        if (zoomToggle.get()) {
            if (zoomPressed && !wasZoomKeyPressed) {
                isZooming = !isZooming;
            }
        } else {
            isZooming = zoomPressed;
        }
        wasZoomKeyPressed = zoomPressed;

        updateLateralOffset();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        double targetDist = isZooming ? zoomDistance.get() : distance.get();

        if (!smoothTransitions.get()) {
            lateralOffset = targetLateralOffset;
            currentDistance = targetDist;
            return;
        }
        float speed = transitionSpeed.get().floatValue();
        lateralOffset += (targetLateralOffset - lateralOffset) * speed;
        if (Math.abs(targetLateralOffset - lateralOffset) < 0.001f) lateralOffset = targetLateralOffset;

        currentDistance += (targetDist - currentDistance) * speed;
        if (Math.abs(targetDist - currentDistance) < 0.01) currentDistance = targetDist;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateLateralOffset() {
        if (!shoulderEnabled.get()) {
            targetLateralOffset = 0f;
        } else {
            float offset = (float) shoulderOffset.get().doubleValue();
            targetLateralOffset = shoulderSide.get() == ShoulderSide.Right ? offset : -offset;
        }
        if (!smoothTransitions.get()) lateralOffset = targetLateralOffset;
    }

    public double getDistance() {
        return currentDistance;
    }

    /**
     * Called by ThirdSightMouseMixin — mouse delta should only apply in
     * ThirdPerson mode with free-look on. BirdsEye drives cameraYaw itself.
     */
    public boolean isFreeLookActive() {
        return isActive() && (
            (cameraMode.get() == CameraMode.ThirdPerson && freeLook.get()) ||
            cameraMode.get() == CameraMode.BirdsEye
        );
    }
}