package com.example.addon.modules;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.meteor.MouseScrollEvent;
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

    // ── Scroll-Wheel Adjustment ───────────────────────────────────────────────

    public final Setting<Boolean> scrollWheelAdjust = sgGeneral.add(new BoolSetting.Builder()
        .name("scroll-wheel-adjust")
        .description("Use the mouse scroll wheel to adjust the third-person camera distance on the fly. "
                   + "Only active while in third person and not zooming. Disabling reverts to the slider value.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Keybind> scrollWheelKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("Scroll Wheel")
        .description("Keybind to toggle the scroll wheel camera distance adjustment on or off.")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Double> scrollSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("scroll-speed")
        .description("Distance added/removed per scroll click. Positive scroll = zoom in, negative = zoom out.")
        .defaultValue(1.0)
        .min(0.1)
        .max(5.0)
        .sliderRange(0.1, 5.0)
        .visible(scrollWheelAdjust::get)
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

    // Scroll-wheel adjustment state
    private double scrollTargetDistance     = 4.0;
    private double lastKnownSliderDistance   = 4.0;
    private boolean wasScrollKeyPressed     = false;

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
        wasScrollKeyPressed     = false;
        
        originalFov = -1;

        // Sync scroll state on enable
        scrollTargetDistance   = distance.get();
        lastKnownSliderDistance = distance.get();
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

            // ── Scroll Wheel toggle keybind ──────────────────────────────────
            boolean scrollPressed = scrollWheelKey.get().isPressed();
            if (scrollPressed && !wasScrollKeyPressed) {
                scrollWheelAdjust.set(!scrollWheelAdjust.get());
                info("Scroll Wheel Adjust %s.", scrollWheelAdjust.get() ? "§aenabled" : "§cdisabled");
            }
            wasScrollKeyPressed = scrollPressed;

        } else {
            wasNoDistanceKeyPressed = false;
            wasZoomKeyPressed       = false;
            wasScrollKeyPressed     = false;
            if (!zoomToggle.get()) isZooming = false;
        }

        // ── Slider / Scroll Sync ──────────────────────────────────────────────
        // If the user changes the slider in the GUI, snap the scroll target to it
        double currentSliderValue = distance.get();
        if (currentSliderValue != lastKnownSliderDistance) {
            scrollTargetDistance = currentSliderValue;
            lastKnownSliderDistance = currentSliderValue;
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

    // ── Mouse Scroll ─────────────────────────────────────────────────────────

    @EventHandler
    private void onMouseScroll(MouseScrollEvent event) {
        if (!scrollWheelAdjust.get()) return;
        if (mc.player == null || mc.options == null) return;
        if (mc.currentScreen != null) return;                // don't hijack GUI scrolls
        if (noDistanceActive) return;                         // vanilla distance in effect
        if (isZooming) return;                                // zoom key has its own distance
        if (mc.options.getPerspective() != Perspective.THIRD_PERSON_BACK) return;

        // Cancel the vanilla hotbar scroll so the wheel only controls the camera
        // We cancel before clamping so hitting the distance limit doesn't scroll hotbar
        event.cancel();

        double delta = event.value;
        if (delta == 0.0) return;

        // Scroll up (positive) => zoom in (decrease distance)
        // Scroll down (negative) => zoom out (increase distance)
        double next = scrollTargetDistance - delta * scrollSpeed.get();
        next = Math.max(1.0, Math.min(30.0, next)); // Clamp to slider bounds

        if (next == scrollTargetDistance) return;
        
        scrollTargetDistance = next;
        lastKnownSliderDistance = distance.get(); // Prevent onTick from snapping it back to the slider
    }

    // ── Render ─────────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        double speed = transitionSpeed.get();
        double targetDist;

        if (noDistanceActive)             targetDist = 4.0; // Vanilla third person distance
        else if (isZooming)               targetDist = zoomDistance.get();
        else if (scrollWheelAdjust.get()) targetDist = scrollTargetDistance;
        else                              targetDist = distance.get();

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
     * Returns the current scroll-driven distance target.
     */
    public double getScrollTargetDistance() { return scrollTargetDistance; }

    /**
     * Allows external callers to reset the scroll target back to the slider value.
     */
    public void resetScrollDistance() {
        scrollTargetDistance = distance.get();
        lastKnownSliderDistance = distance.get();
    }

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