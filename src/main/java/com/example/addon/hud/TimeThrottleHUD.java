package com.example.addon.hud;

import com.example.addon.Tim;
import com.example.addon.modules.Timethrottle;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class TimeThrottleHUD extends HudElement {

    public static final HudElementInfo<TimeThrottleHUD> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "time-throttle",
        "Displays the system speed impact of the Timethrottle module.",
        TimeThrottleHUD::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Alignment { Left, Center, Right }

    // ── Settings ──────────────────────────────────────────────────────────────

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0).min(0.5).sliderMax(3.0)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align content to the left, center, or right.")
        .defaultValue(Alignment.Left)
        .build()
    );

    private final Setting<Boolean> showSource = sgGeneral.add(new BoolSetting.Builder()
        .name("show-source")
        .description("Show which source is currently causing the most slowdown on a second line.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBar = sgGeneral.add(new BoolSetting.Builder()
        .name("show-bar")
        .description("Shows a color-coded speed multiplier bar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> barHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("bar-height")
        .description("Height of the progress bar.")
        .defaultValue(4.0).min(2.0).sliderMax(10.0)
        .visible(showBar::get)
        .build()
    );

    // ── Colors ────────────────────────────────────────────────────────────────

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color for the text labels.")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> healthyColor = sgGeneral.add(new ColorSetting.Builder()
        .name("healthy-color")
        .description("Color used when speed is above 80%.")
        .defaultValue(new SettingColor(60, 255, 60, 255))
        .build()
    );

    private final Setting<SettingColor> warningColor = sgGeneral.add(new ColorSetting.Builder()
        .name("warning-color")
        .description("Color used when speed is between 40% and 80%.")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

    private final Setting<SettingColor> criticalColor = sgGeneral.add(new ColorSetting.Builder()
        .name("critical-color")
        .description("Color used when speed drops below 40%.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .build()
    );

    private final Setting<SettingColor> barBackgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("bar-background-color")
        .description("Color of the empty part of the progress bar.")
        .defaultValue(new SettingColor(40, 40, 40, 200))
        .visible(showBar::get)
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background highlight behind the HUD element.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    public TimeThrottleHUD() {
        super(INFO);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        Timethrottle module = Modules.get().get(Timethrottle.class);
        
        // Handle inactive state or editor preview
        if (module == null || !module.isActive()) {
            if (isInEditor()) {
                renderPlaceholder(renderer, "Speed: 100%", "Source: None", 1.0);
            } else {
                setSize(0, 0);
            }
            return;
        }

        double mult = module.getCurrentSpeed();

        // Find bottleneck source
        String sourceName = null;
        if (showSource.get()) {
            double minVal = 0.99; // Only show if actually throttling
            for (int i = 0; i < module.sourceCount(); i++) {
                double val = module.evaluateSource(i);
                if (val < minVal) {
                    minVal = val;
                    sourceName = module.sourceName(i);
                }
            }
        }

        String speedLabel = "Speed: ";
        String speedValue = String.format("%.0f%%", mult * 100);
        String sourceLabel = "Source: ";
        String sourceValue = sourceName != null ? sourceName : "None";

        renderLayout(renderer, speedLabel, speedValue, sourceLabel, sourceValue, mult);
    }

    private void renderPlaceholder(HudRenderer renderer, String speedVal, String sourceVal, double mult) {
        renderLayout(renderer, "Speed: ", speedVal, "Source: ", sourceVal, mult);
    }

    private void renderLayout(HudRenderer renderer, String speedLabel, String speedValue, String sourceLabel, String sourceValue, double mult) {
        double s = scale.get();
        double padH = 4 * s;
        double padV = 2 * s;
        double rowGap = 2 * s;
        double lineHeight = renderer.textHeight(false, s);

        // Determine dynamic color
        SettingColor dynamicCol;
        if (mult > 0.8) dynamicCol = healthyColor.get();
        else if (mult > 0.4) dynamicCol = warningColor.get();
        else dynamicCol = criticalColor.get();

        // Measure widths
        double speedLabelW = renderer.textWidth(speedLabel, false, s);
        double speedValueW = renderer.textWidth(speedValue, false, s);
        double speedLineW = speedLabelW + speedValueW;

        double sourceLineW = 0;
        boolean drawingSource = showSource.get();
        if (drawingSource) {
            double sourceLabelW = renderer.textWidth(sourceLabel, false, s);
            double sourceValueW = renderer.textWidth(sourceValue, false, s);
            sourceLineW = sourceLabelW + sourceValueW;
        }

        double maxTextW = Math.max(speedLineW, sourceLineW);
        double barW = 0;
        double barH = 0;
        double barGap = 0;

        if (showBar.get()) {
            barW = maxTextW; // Make bar match text width
            barH = barHeight.get() * s;
            barGap = rowGap;
        }

        double contentW = maxTextW;
        double totalW = contentW + padH * 2;
        
        int lineCount = 1 + (drawingSource ? 1 : 0);
        double textAndBarH = (lineCount * lineHeight) + ((lineCount - 1) * rowGap);
        double totalH = textAndBarH + padV * 2 + (showBar.get() ? barGap + barH : 0);

        setSize(totalW, totalH);

        // Draw Background
        if (showBackground.get()) {
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());
        }

        Alignment align = alignment.get();

        // Draw Line 1: Speed
        double l1X = getAlignedX(align, x, padH, totalW, speedLineW);
        double l1Y = y + padV;
        renderer.text(speedLabel, l1X, l1Y, labelColor.get(), false, s);
        renderer.text(speedValue, l1X + speedLabelW, l1Y, dynamicCol, false, s);

        // Draw Line 2: Source
        if (drawingSource) {
            double l2X = getAlignedX(align, x, padH, totalW, sourceLineW);
            double l2Y = l1Y + lineHeight + rowGap;
            renderer.text(sourceLabel, l2X, l2Y, labelColor.get(), false, s);
            renderer.text(sourceValue, l2X + renderer.textWidth(sourceLabel, false, s), l2Y, dynamicCol, false, s);
        }

        // Draw Bar
        if (showBar.get()) {
            double barX = getAlignedX(align, x, padH, totalW, barW);
            double barY = y + padV + textAndBarH + barGap;
            
            // Draw background track
            renderer.quad(barX, barY, barW, barH, barBackgroundColor.get());
            
            // Draw progress fill (clamped to 0-1 to prevent overflow)
            double progress = Math.max(0.0, Math.min(1.0, mult));
            if (progress > 0) {
                renderer.quad(barX, barY, barW * progress, barH, dynamicCol);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double getAlignedX(Alignment align, double baseX, double padH, double totalW, double contentW) {
        return switch (align) {
            case Left   -> baseX + padH;
            case Right  -> baseX + totalW - padH - contentW;
            case Center -> baseX + (totalW - contentW) / 2.0;
        };
    }
}