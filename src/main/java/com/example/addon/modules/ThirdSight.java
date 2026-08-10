package com.example.addon.modules;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.Perspective;

public class ThirdSight extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgZoom    = settings.createGroup("Zoom");

    // ── General ──────────────────────────────────────────────────────────────

    private final Setting<Keybind> noDistanceKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("no-distance-key")
        .description("Toggles a mode that disables camera distance modifications, allowing vanilla third person unless zooming.")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("Camera distance from the player.")
        .defaultValue(4.0)
        .min(1.0)
        .max(30.0)
        .sliderRange(1.0, 30.0)
        .build()
    );

    public final Setting<Double> transitionSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("transition-speed")
        .description("How smoothly the camera transitions between distances and FOV. 1.0 = instant.")
        .defaultValue(0.15)
        .min(0.01)
        .max(1.0)
        .sliderRange(0.05, 0.5)
        .build()
    );

    public final Setting<Boolean> customFov = sgGeneral.add(new BoolSetting.Builder()
        .name("custom-first-person-fov")
        .description("Overrides your FOV while in First Person, allowing you to push past the vanilla limit of 115.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> targetFov = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-fov")
        .description("The FOV to use while in First Person. Can be pushed past vanilla limits.")
        .defaultValue(110.0)
        .min(1.0)
        .max(200.0)
        .sliderRange(30.0, 200.0)
        .visible(customFov::get)
        .build()
    );

    public final Setting<Boolean> freeLook = sgGeneral.add(new BoolSetting.Builder()
        .name("free-look")
        .description("Orbit the camera around the player without affecting movement direction.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> sensitivity = sgGeneral.add(new DoubleSetting.Builder()
        .name("sensitivity")
        .description("Free-look mouse sensitivity.")
        .defaultValue(1.0)
        .min(1.0)
        .max(20.0)
        .sliderRange(1.0, 20.0)
        .visible(freeLook::get)
        .build()
    );

    public final Setting<Double> followSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("follow-speed")
        .description("How quickly the camera yaw catches up to the direction you're looking when free-look is off. 1.0 = instant.")
        .defaultValue(0.12)
        .min(0.01)
        .max(1.0)
        .sliderRange(0.02, 0.5)
        .visible(() -> !freeLook.get())
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

    public final Setting<Double> zoomFov = sgZoom.add(new DoubleSetting.Builder()
        .name("zoom-fov")
        .description("Field of View when zooming in First Person.")
        .defaultValue(30.0)
        .min(1.0)
        .max(110.0)
        .sliderRange(10.0, 110.0)
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

    // Free-look / BirdsEye camera angles
    public float cameraYaw   = 0f;
    public float cameraPitch = 0f;

    private double  currentDistance         = 4.0;
    private boolean isZooming               = false;
    private boolean wasZoomKeyPressed       = false;
    private boolean noDistanceActive        = false;
    private boolean wasNoDistanceKeyPressed = false;
    private double  originalFov             = -1;
    private double  currentFov              = 0;

    private Perspective previousPerspective = null;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ThirdSight() {
        super(Tim.CATEGORY, "third-sight",
            "Third-person camera with configurable distance, no block clipping, and free look.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        if (mc.player == null || mc.options == null) return;

        cameraYaw   = mc.player.getYaw();
        cameraPitch = Math.max(-89.9f, Math.min(89.9f, mc.player.getPitch()));

        previousPerspective = mc.options.getPerspective();
        
        // Start at current vanilla distance to allow smooth transition in
        currentDistance = (previousPerspective == Perspective.FIRST_PERSON) ? 0.0 : 4.0;

        if (previousPerspective == Perspective.FIRST_PERSON)
            mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);

        isZooming               = false;
        wasZoomKeyPressed       = false;
        noDistanceActive        = false;
        wasNoDistanceKeyPressed = false;
        
        originalFov = -1;
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null) {
            if (previousPerspective != null)
                mc.options.setPerspective(previousPerspective);
            if (originalFov != -1)
                mc.options.getFov().setValue((int) originalFov);
        }

        previousPerspective = null;
        originalFov = -1;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.options == null) return;

        if (mc.currentScreen == null) {
            // No Distance toggle
            boolean noDistPressed = noDistanceKey.get().isPressed();
            if (noDistPressed && !wasNoDistanceKeyPressed) {
                noDistanceActive = !noDistanceActive;
                info("No Distance mode %s.", noDistanceActive ? "§aenabled" : "§cdisabled");
            }
            wasNoDistanceKeyPressed = noDistPressed;

            // ── Zoom keybind ─────────────────────────────────────────────────
            boolean zoomPressed = zoomKey.get().isPressed();
            if (zoomToggle.get()) {
                if (zoomPressed && !wasZoomKeyPressed) isZooming = !isZooming;
            } else {
                isZooming = zoomPressed;
            }
            wasZoomKeyPressed = zoomPressed;

        } else {
            wasNoDistanceKeyPressed = false;
            wasZoomKeyPressed       = false;
            if (!zoomToggle.get()) isZooming = false;
        }

        // ── Normal camera tick ────────────────────────────────────────────────
        if (noDistanceActive) {
            if (previousPerspective != null) {
                mc.options.setPerspective(previousPerspective);
                previousPerspective = null;
            }
        } else {
            if (previousPerspective == null)
                previousPerspective = mc.options.getPerspective();
            if (mc.options.getPerspective() != Perspective.THIRD_PERSON_BACK)
                mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        double speed = transitionSpeed.get();
        double targetDist;

        if (noDistanceActive) targetDist = 4.0; // Vanilla third person distance
        else targetDist = isZooming ? zoomDistance.get() : distance.get();

        // When free-look is off, smoothly chase the player's look direction.
        boolean shouldFollow = !freeLook.get() && mc.player != null;
        if (shouldFollow) {
            float playerYaw = mc.player.getYaw();
            float yawDiff = playerYaw - cameraYaw;
            if (yawDiff >  180f) yawDiff -= 360f;
            if (yawDiff < -180f) yawDiff += 360f;
            float fs = (float) followSpeed.get().doubleValue();
            cameraYaw += yawDiff * fs;
        }

        currentDistance += (targetDist - currentDistance) * speed;
        if (Math.abs(targetDist - currentDistance) < 0.01) currentDistance = targetDist;

        // FOV smoothing (unified for first-person custom FOV and zoom)
        double targetFovValue = originalFov;

        // Only apply custom/zoom FOV when in First Person
        if (mc.options.getPerspective().isFirstPerson() && customFov.get()) {
            if (isZooming) {
                targetFovValue = zoomFov.get();
            } else {
                targetFovValue = targetFov.get();
            }
        }

        if (targetFovValue != originalFov) {
            if (originalFov == -1) {
                originalFov = mc.options.getFov().getValue();
                currentFov = originalFov;
            }
            currentFov += (targetFovValue - currentFov) * speed;
            if (Math.abs(targetFovValue - currentFov) < 0.1) currentFov = targetFovValue;
            mc.options.getFov().setValue((int) currentFov);
        } else if (originalFov != -1) {
            currentFov += (originalFov - currentFov) * speed;
            if (Math.abs(originalFov - currentFov) < 0.1) {
                currentFov = originalFov;
                mc.options.getFov().setValue((int) originalFov);
                originalFov = -1;
            } else {
                mc.options.getFov().setValue((int) currentFov);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public double getDistance() { return currentDistance; }

    public boolean isZooming() { return isZooming; }

    public void setZooming(boolean z) { this.isZooming = z; }

    public boolean isNoDistanceActive() { return noDistanceActive; }

    /**
     * Called by AbstractClientPlayerEntityMixin to counteract the vanilla FOV multiplier 
     * (which causes the "beacon effect" FOV scaling from speed/jump boost).
     */
    public boolean isBeaconEffectCountered() {
        return isActive();
    }

    /**
     * Called by ThirdSightMouseMixin — free look is active when enabled and not zooming in first person.
     */
    public boolean isFreeLookActive() {
        if (!isActive()) return false;
        if (mc.options.getPerspective().isFirstPerson()) return false;
        if (noDistanceActive && !isZooming()) return false;
        return freeLook.get();
    }
}