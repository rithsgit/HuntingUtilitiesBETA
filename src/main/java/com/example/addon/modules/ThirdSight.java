package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.option.Perspective;

public class ThirdSight extends Module {

    public enum CameraView { Back, Front }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<CameraView> cameraView = sgGeneral.add(new EnumSetting.Builder<CameraView>()
        .name("camera-view")
        .description("Which third-person perspective to use.")
        .defaultValue(CameraView.Back)
        .build()
    );

    public final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("Third-person camera distance from the player.")
        .defaultValue(4.0)
        .min(1.0)
        .max(30.0)
        .sliderRange(1.0, 30.0)
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

    // Independent camera yaw/pitch for free look.
    // Updated by ThirdSightMouseMixin via mouse delta.
    public float cameraYaw   = 0f;
    public float cameraPitch = 0f;

    private Perspective previousPerspective = null;

    public ThirdSight() {
        super(HuntingUtilities.CATEGORY, "third-sight",
            "Third-person camera with configurable distance, no block clipping, and free look.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.options == null) return;
        cameraYaw   = mc.player.getYaw();
        cameraPitch = mc.player.getPitch();
        previousPerspective = mc.options.getPerspective();
        mc.options.setPerspective(
            cameraView.get() == CameraView.Front
                ? Perspective.THIRD_PERSON_FRONT
                : Perspective.THIRD_PERSON_BACK
        );
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null && previousPerspective != null) {
            mc.options.setPerspective(previousPerspective);
        }
        previousPerspective = null;
    }
}