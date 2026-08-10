package com.example.addon.modules;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;

public class Handmold extends Module {

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgMainHand = settings.createGroup("Main Hand");
    private final SettingGroup sgOffHand  = settings.createGroup("Off Hand");

    // ── General ───────────────────────────────────────────────────────────────

    private final Setting<Boolean> noHandBob = sgGeneral.add(new BoolSetting.Builder()
        .name("no-hand-bob")
        .description("Disables hand bobbing while walking.")
        .defaultValue(false).build()
    );

    private final Setting<Boolean> hideEmptyMainhand = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-empty-mainhand")
        .description("Hides the main hand when not holding any item.")
        .defaultValue(false).build()
    );

    private final Setting<Boolean> hideOffhandCompletely = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-offhand-completely")
        .description("Hides the offhand completely regardless of what it holds.")
        .defaultValue(false).build()
    );

    public enum EatPosition { StayInPlace, Custom }

    private final Setting<EatPosition> eatPosition = sgGeneral.add(new EnumSetting.Builder<EatPosition>()
        .name("eat-position")
        .description("Where the hand moves to while eating or drinking. Custom defaults to 0 (center) unless changed.")
        .defaultValue(EatPosition.Custom)
        .build()
    );

    private final Setting<Double> eatTargetX = sgGeneral.add(new DoubleSetting.Builder()
        .name("eat-target-x")
        .description("The X position the hand slides to while eating. 0 = center, negative = left, positive = right.")
        .defaultValue(0.0).min(-2.0).max(2.0).sliderRange(-1.0, 1.0)
        .visible(() -> eatPosition.get() == EatPosition.Custom)
        .build()
    );

    // ── Main Hand ─────────────────────────────────────────────────────────────

    private final Setting<Double> mainX = sgMainHand.add(new DoubleSetting.Builder()
        .name("x").description("Horizontal offset.")
        .defaultValue(0.0).min(-2.0).max(2.0).sliderRange(-1.0, 1.0).build()
    );
    private final Setting<Double> mainY = sgMainHand.add(new DoubleSetting.Builder()
        .name("y").description("Vertical offset.")
        .defaultValue(0.0).min(-2.0).max(2.0).sliderRange(-1.0, 1.0).build()
    );
    private final Setting<Double> mainZ = sgMainHand.add(new DoubleSetting.Builder()
        .name("z").description("Depth offset.")
        .defaultValue(0.0).min(-2.0).max(2.0).sliderRange(-1.0, 1.0).build()
    );
    private final Setting<Double> mainScale = sgMainHand.add(new DoubleSetting.Builder()
        .name("scale").description("Scale multiplier.")
        .defaultValue(1.0).min(0.1).max(3.0).sliderRange(0.1, 2.0).build()
    );
    private final Setting<Double> mainRotX = sgMainHand.add(new DoubleSetting.Builder()
        .name("rot-x").description("Rotation around X axis (degrees).")
        .defaultValue(0.0).min(-180.0).max(180.0).sliderRange(-180.0, 180.0).build()
    );
    private final Setting<Double> mainRotY = sgMainHand.add(new DoubleSetting.Builder()
        .name("rot-y").description("Rotation around Y axis (degrees).")
        .defaultValue(0.0).min(-180.0).max(180.0).sliderRange(-180.0, 180.0).build()
    );
    private final Setting<Double> mainRotZ = sgMainHand.add(new DoubleSetting.Builder()
        .name("rot-z").description("Rotation around Z axis (degrees).")
        .defaultValue(0.0).min(-180.0).max(180.0).sliderRange(-180.0, 180.0).build()
    );

    // ── Off Hand ──────────────────────────────────────────────────────────────

    private final Setting<Double> offX = sgOffHand.add(new DoubleSetting.Builder()
        .name("x").description("Horizontal offset.")
        .defaultValue(0.0).min(-2.0).max(2.0).sliderRange(-1.0, 1.0).build()
    );
    private final Setting<Double> offY = sgOffHand.add(new DoubleSetting.Builder()
        .name("y").description("Vertical offset.")
        .defaultValue(0.0).min(-2.0).max(2.0).sliderRange(-1.0, 1.0).build()
    );
    private final Setting<Double> offZ = sgOffHand.add(new DoubleSetting.Builder()
        .name("z").description("Depth offset.")
        .defaultValue(0.0).min(-2.0).max(2.0).sliderRange(-1.0, 1.0).build()
    );
    private final Setting<Double> offScale = sgOffHand.add(new DoubleSetting.Builder()
        .name("scale").description("Scale multiplier.")
        .defaultValue(1.0).min(0.1).max(3.0).sliderRange(0.1, 2.0).build()
    );
    private final Setting<Double> offRotX = sgOffHand.add(new DoubleSetting.Builder()
        .name("rot-x").description("Rotation around X axis (degrees).")
        .defaultValue(0.0).min(-180.0).max(180.0).sliderRange(-180.0, 180.0).build()
    );
    private final Setting<Double> offRotY = sgOffHand.add(new DoubleSetting.Builder()
        .name("rot-y").description("Rotation around Y axis (degrees).")
        .defaultValue(0.0).min(-180.0).max(180.0).sliderRange(-180.0, 180.0).build()
    );
    private final Setting<Double> offRotZ = sgOffHand.add(new DoubleSetting.Builder()
        .name("rot-z").description("Rotation around Z axis (degrees).")
        .defaultValue(0.0).min(-180.0).max(180.0).sliderRange(-180.0, 180.0).build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public Handmold() {
        super(Tim.CATEGORY, "handmold",
            "Adjusts the position, scale, and rotation of each hand independently.");
    }

    // ── Public API — read by mixins ───────────────────────────────────────────

    public double getMainX()     { return mainX.get(); }
    public double getMainY()     { return mainY.get(); }
    public double getMainZ()     { return mainZ.get(); }
    public double getMainScale() { return mainScale.get(); }
    public double getMainRotX()  { return mainRotX.get(); }
    public double getMainRotY()  { return mainRotY.get(); }
    public double getMainRotZ()  { return mainRotZ.get(); }

    public double getOffX()      { return offX.get(); }
    public double getOffY()      { return offY.get(); }
    public double getOffZ()      { return offZ.get(); }
    public double getOffScale()  { return offScale.get(); }
    public double getOffRotX()   { return offRotX.get(); }
    public double getOffRotY()   { return offRotY.get(); }
    public double getOffRotZ()   { return offRotZ.get(); }

    public boolean shouldDisableHandBob()        { return isActive() && noHandBob.get(); }
    public boolean shouldHideEmptyMainhand()     { return isActive() && hideEmptyMainhand.get(); }
    public boolean shouldHideOffhandCompletely() { return isActive() && hideOffhandCompletely.get(); }
    public EatPosition getEatPosition()          { return eatPosition.get(); }
    public double      getEatTargetX()           { return eatTargetX.get(); }
}