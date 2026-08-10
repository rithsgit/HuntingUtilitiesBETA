package com.example.addon.hud;

import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import com.example.addon.Tim;
import com.example.addon.modules.RocketPilot;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class RocketPilotHud extends HudElement {
    public static final HudElementInfo<RocketPilotHud> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "rocket-pilot",
        "Displays RocketPilot status, elytra durability, and rocket count.",
        RocketPilotHud::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgElytra  = settings.createGroup("Elytra Warnings");
    private final SettingGroup sgRockets = settings.createGroup("Rocket Warnings");

    // ── Layout ────────────────────────────────────────────────────────────────

    public enum Layout { Inline, Stacked, StackedIcons }

    private final Setting<Layout> layout = sgGeneral.add(new EnumSetting.Builder<Layout>()
        .name("layout")
        .description("How the data is presented.")
        .defaultValue(Layout.StackedIcons)
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("separator-color")
        .description("Color of the | separators.")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .visible(() -> layout.get() == Layout.Inline)
        .build()
    );

    // ── Visual settings ───────────────────────────────────────────────────────

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0)
        .min(0.25)
        .sliderRange(0.25, 4.0)
        .build()
    );

    public enum Alignment { Left, Center, Right }

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align text to the left, center, or right within the element.")
        .defaultValue(Alignment.Left)
        .visible(() -> layout.get() != Layout.Inline)
        .build()
    );

    public enum LabelMode { Text, Icon, Both }

    private final Setting<LabelMode> labelMode = sgGeneral.add(new EnumSetting.Builder<LabelMode>()
        .name("label-mode")
        .description("Show the item label as text, icon, or both.")
        .defaultValue(LabelMode.Both)
        .visible(() -> layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline)
        .build()
    );

    public enum IconPosition { Left, Right, Above, Below }

    public enum BarPosition { Above, Below, Left, Right }

    private final Setting<IconPosition> iconPosition = sgGeneral.add(new EnumSetting.Builder<IconPosition>()
        .name("icon-position")
        .description("Where the item icon appears relative to the text on each stat row.")
        .defaultValue(IconPosition.Left)
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("Scale of the item icons.")
        .defaultValue(1.5)
        .min(0.5)
        .sliderRange(0.5, 4.0)
        .visible(() -> labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconGapSetting = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between the icon and the text.")
        .defaultValue(4.0)
        .min(0)
        .sliderRange(0, 16)
        .visible(() -> labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color for labels.")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Color for values when healthy.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background behind each row.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    // ── Feature toggles ───────────────────────────────────────────────────────

    private final Setting<Boolean> showStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("show-status")
        .description("Show the RocketPilot status line (only visible while module is active).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBar = sgGeneral.add(new BoolSetting.Builder()
        .name("show-bar")
        .description("Show a color-coded efficiency bar based on rocket consumption.")
        .defaultValue(false)
        .build()
    );

    private final Setting<BarPosition> barPosition = sgGeneral.add(new EnumSetting.Builder<BarPosition>()
        .name("bar-position")
        .description("Where the efficiency bar appears relative to the text/icons.")
        .defaultValue(BarPosition.Below)
        .visible(showBar::get)
        .build()
    );

    private final Setting<Boolean> showDurability = sgGeneral.add(new BoolSetting.Builder()
        .name("show-durability")
        .description("Show the count of usable elytras in inventory.")
        .defaultValue(true)
        .build()
    );

    public enum DistanceUnit { Blocks, Kilometers }

    private final Setting<Boolean> showFlightTime = sgGeneral.add(new BoolSetting.Builder()
        .name("show-flight-time")
        .description("Show estimated flight time remaining based on rocket consumption.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideTimeWhenStatic = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-time-when-static")
        .description("Hide the flight time estimate when not moving.")
        .defaultValue(false)
        .visible(showFlightTime::get)
        .build()
    );

    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("show-distance")
        .description("Show estimated distance remaining based on rocket consumption.")
        .defaultValue(true)
        .build()
    );

    private final Setting<DistanceUnit> distanceUnit = sgGeneral.add(new EnumSetting.Builder<DistanceUnit>()
        .name("distance-unit")
        .description("The unit used to display the estimated remaining distance.")
        .defaultValue(DistanceUnit.Blocks)
        .visible(showDistance::get)
        .build()
    );

    private final Setting<Boolean> showRocketCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-rocket-count")
        .description("Show total firework rockets across the inventory.")
        .defaultValue(true)
        .build()
    );

    // ── Elytra warnings ───────────────────────────────────────────────────────

    private final Setting<Double> elytraDurabilityThreshold = sgElytra.add(new DoubleSetting.Builder()
        .name("durability-threshold")
        .description("Durability % below which an elytra is considered spent and not counted.")
        .defaultValue(10.0).min(0).sliderRange(0, 100).build()
    );

    private final Setting<Integer> elytraWarningCount = sgElytra.add(new IntSetting.Builder()
        .name("warning-count")
        .description("Elytra count at or below which the value turns the warning color.")
        .defaultValue(2).min(0).sliderRange(0, 20).build()
    );

    private final Setting<SettingColor> elytraWarningColor = sgElytra.add(new ColorSetting.Builder()
        .name("warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255)).build()
    );

    private final Setting<Integer> elytraCriticalCount = sgElytra.add(new IntSetting.Builder()
        .name("critical-count")
        .description("Elytra count at or below which the value turns the critical color.")
        .defaultValue(1).min(0).sliderRange(0, 10).build()
    );

    private final Setting<SettingColor> elytraCriticalColor = sgElytra.add(new ColorSetting.Builder()
        .name("critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255)).build()
    );

    // ── Rocket warnings ───────────────────────────────────────────────────────

    private final Setting<Integer> rocketWarningThreshold = sgRockets.add(new IntSetting.Builder()
        .name("warning-threshold")
        .description("Rocket count to trigger warning color.")
        .defaultValue(16).min(0).sliderRange(0, 128).build()
    );

    private final Setting<SettingColor> rocketWarningColor = sgRockets.add(new ColorSetting.Builder()
        .name("warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255)).build()
    );

    private final Setting<Integer> rocketCriticalThreshold = sgRockets.add(new IntSetting.Builder()
        .name("critical-threshold")
        .description("Rocket count to trigger critical color.")
        .defaultValue(8).min(0).sliderRange(0, 128).build()
    );

    private final Setting<SettingColor> rocketCriticalColor = sgRockets.add(new ColorSetting.Builder()
        .name("critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255)).build()
    );

    private final Setting<Integer> lowFuelThreshold = sgRockets.add(new IntSetting.Builder()
        .name("low-fuel-threshold")
        .description("Estimated minutes of flight remaining to trigger critical color.")
        .defaultValue(10).min(1).sliderMax(60)
        .visible(showFlightTime::get)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    private long flightStartTime = -1;
    private int flightStartRockets = -1;

    // ── Constructor ───────────────────────────────────────────────────────────

    public RocketPilotHud() { super(INFO); }

    // ── Render ────────────────────────────────────────────────────────────────

    private record Stat(String label, String value, ItemStack icon, SettingColor valColor) {}

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null) { setSize(0, 0); return; }

        if (layout.get() == Layout.Inline) renderInline(renderer);
        else renderStacked(renderer, layout.get() == Layout.StackedIcons);
    }

    private void renderInline(HudRenderer renderer) {
        double s = scale.get(), padH = 4 * s, padV = 2 * s, lh = renderer.textHeight(false, s), sepW = renderer.textWidth(" | ", false, s);
        double iconSz = 16.0 * iconScale.get(), iconGap = iconGapSetting.get() * s;
        LabelMode mode = labelMode.get(); boolean showIcon = mode != LabelMode.Text, showLabel = mode != LabelMode.Icon;
        IconPosition iconPos = iconPosition.get(); double effIconGap = showIcon ? iconGap : 0;

        List<Stat> stats = new ArrayList<>();

        int elytraCount = countElytras();
        if (showDurability.get() && (elytraCount > 0 || isInEditor())) {
            SettingColor col = valueColor.get();
            if (elytraCount <= elytraCriticalCount.get()) col = elytraCriticalColor.get();
            else if (elytraCount <= elytraWarningCount.get()) col = elytraWarningColor.get();
            stats.add(new Stat("Elytras: ", String.valueOf(elytraCount), new ItemStack(Items.ELYTRA), col));
        }

        int currentRockets = countRockets();
        if (showRocketCount.get() && (currentRockets > 0 || isInEditor())) {
            SettingColor col = valueColor.get();
            if (currentRockets <= rocketCriticalThreshold.get()) col = rocketCriticalColor.get();
            else if (currentRockets <= rocketWarningThreshold.get()) col = rocketWarningColor.get();
            stats.add(new Stat("Rockets: ", String.valueOf(currentRockets), new ItemStack(Items.FIREWORK_ROCKET), col));
        }

        if (stats.isEmpty()) { setSize(0, 0); return; }

        double totalW = 0, rowH = showIcon ? Math.max(lh, iconSz) : lh;
        for (int i = 0; i < stats.size(); i++) {
            Stat st = stats.get(i);
            double segW = 0;
            if (showLabel) segW += renderer.textWidth(st.label, false, s);
            segW += renderer.textWidth(st.value, false, s);
            if (showIcon) segW += iconSz + effIconGap;
            totalW += segW;
            if (i < stats.size() - 1) totalW += sepW;
        }
        setSize(totalW + padH * 2, rowH + padV * 2);
        if (showBackground.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        double cx = x + padH;
        for (int i = 0; i < stats.size(); i++) {
            Stat st = stats.get(i);
            if (showIcon && iconPos == IconPosition.Left) {
                renderer.item(st.icon, (int) cx, (int) (y + padV + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz + effIconGap;
            }
            if (showLabel) {
                renderer.text(st.label, cx, y + padV + (rowH - lh) / 2.0, labelColor.get(), false, s);
                cx += renderer.textWidth(st.label, false, s);
            }
            renderer.text(st.value, cx, y + padV + (rowH - lh) / 2.0, st.valColor, false, s);
            cx += renderer.textWidth(st.value, false, s);

            if (showIcon && iconPos != IconPosition.Left) {
                cx += effIconGap;
                renderer.item(st.icon, (int) cx, (int) (y + padV + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz;
            }
            if (i < stats.size() - 1) {
                renderer.text(" | ", cx, y + padV + (rowH - lh) / 2.0, separatorColor.get(), false, s);
                cx += sepW;
            }
        }
    }

    private void renderStacked(HudRenderer renderer, boolean withIcons) {
        RocketPilot rp = Modules.get().get(RocketPilot.class);

        double s          = scale.get();
        double padH       = 4 * s;
        double padV       = 2 * s;
        double rowGap     = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double iconSz     = 16.0 * iconScale.get();
        double iconGap    = iconGapSetting.get() * s;

        LabelMode    mode     = labelMode.get();
        IconPosition iconPos  = iconPosition.get();
        boolean      showIcon = mode != LabelMode.Text;
        boolean      showText = mode != LabelMode.Icon;

        // For Above/Below icon layouts each stat row is TWO visual rows tall
        BarPosition bp = barPosition.get();
        boolean iconVertical = showIcon && (iconPos == IconPosition.Above || iconPos == IconPosition.Below);

        // Height of a single stat row
        double statRowH;
        if (!showIcon) {
            statRowH = lineHeight;
        } else if (iconVertical) {
            statRowH = iconSz + iconGap + lineHeight;
        } else {
            statRowH = Math.max(lineHeight, iconSz);
        }

        // Status line is always one text line tall
        double statusRowH = lineHeight;

        // ── Gather data ───────────────────────────────────────────────────────

        String statusValue = null;
        if (showStatus.get() && rp.isActive()) {
            RocketPilot.FlightPattern pat = rp.flightPattern.get();
            statusValue = (pat == RocketPilot.FlightPattern.Manual)
                ? rp.flightMode.get().toString() : pat.toString();
        }
        String statusLabel = "RocketPilot: ";
        double statusW = statusValue != null
            ? renderer.textWidth(statusLabel, false, s) + renderer.textWidth(statusValue, false, s)
            : 0;

        // Elytra
        int elytraCount = countElytras();
        ItemStack    elytraStack = ItemStack.EMPTY;
        String       durLabel = null, durValue = null;
        SettingColor durColor = valueColor.get();
        if (showDurability.get() && (elytraCount > 0 || isInEditor())) {
            durLabel = showText ? "Elytras: " : "";
            durValue = String.valueOf(elytraCount);

            // Find a valid elytra to show as icon
            double threshold = elytraDurabilityThreshold.get();
            for (ItemStack stack : mc.player.getInventory().main) {
                if (isValidElytra(stack, threshold)) { elytraStack = stack; break; }
            }
            if (elytraStack.isEmpty()) {
                ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
                if (isValidElytra(chest, threshold)) elytraStack = chest;
            }
            if (elytraStack.isEmpty()) elytraStack = new ItemStack(Items.ELYTRA);

            if      (elytraCount <= elytraCriticalCount.get()) durColor = elytraCriticalColor.get();
            else if (elytraCount <= elytraWarningCount.get())  durColor = elytraWarningColor.get();
        }

        // Rockets
        int currentRockets = countRockets();
        ItemStack    rocketStack = ItemStack.EMPTY;
        String       rocketLabel = null, rocketValue = null;
        SettingColor rocketColor = valueColor.get();

        if ((showRocketCount.get() || showFlightTime.get()) && (currentRockets > 0 || isInEditor())) {
            String name = "Firework Rocket";
            for (int i = 0; i < 36; i++) {
                ItemStack s2 = mc.player.getInventory().getStack(i);
                if (s2.isOf(Items.FIREWORK_ROCKET)) { name = s2.getName().getString(); rocketStack = s2; break; }
            }
            ItemStack offhand = mc.player.getOffHandStack();
            if (offhand.isOf(Items.FIREWORK_ROCKET)) { name = offhand.getName().getString(); rocketStack = offhand; }

            if (showRocketCount.get()) {
                rocketLabel = showText ? name + ": " : "";
                rocketValue = String.valueOf(currentRockets);
                if      (currentRockets <= rocketCriticalThreshold.get()) rocketColor = rocketCriticalColor.get();
                else if (currentRockets <= rocketWarningThreshold.get())  rocketColor = rocketWarningColor.get();
            }
        }

        // Flight Time Prediction
        String timeLabel = null, timeValue = null;
        SettingColor timeColor = valueColor.get();
        String distLabel = null, distValue = null;
        double efficiency = 1.0;
        SettingColor distColor = valueColor.get();

        if ((showFlightTime.get() || showDistance.get()) && rp.isActive() && (currentRockets > 0 || isInEditor())) {
            boolean moving = mc.player.getVelocity().lengthSquared() > 0.0001;

            if (!hideTimeWhenStatic.get() || moving) {
                if (flightStartTime == -1 || currentRockets > flightStartRockets) {
                    flightStartTime = System.currentTimeMillis();
                    flightStartRockets = currentRockets;
                }

                int used = flightStartRockets - currentRockets;
                long elapsed = System.currentTimeMillis() - flightStartTime;

                if (used > 0 && elapsed > 1000) {
                    double msPerRocket = (double) elapsed / used;
                    long msRemaining = (long) (currentRockets * msPerRocket);
                    
                    efficiency = (double) currentRockets / flightStartRockets;

                    if (showFlightTime.get()) {
                        long hrs = TimeUnit.MILLISECONDS.toHours(msRemaining);
                        long mins = TimeUnit.MILLISECONDS.toMinutes(msRemaining) % 60;
                        long secs = TimeUnit.MILLISECONDS.toSeconds(msRemaining) % 60;
                        timeLabel = showText ? "Est. Flight: " : "";
                        timeValue = hrs > 0 ? String.format("%02d:%02d:%02d", hrs, mins, secs) : String.format("%02d:%02d", mins, secs);
                        if (TimeUnit.MILLISECONDS.toMinutes(msRemaining) < lowFuelThreshold.get()) timeColor = rocketCriticalColor.get();
                    }

                    if (showDistance.get()) {
                        double speed = mc.player.getVelocity().length() * 20.0;
                        double estDist = (msRemaining / 1000.0) * speed;
                        distLabel = showText ? "Est. Distance: " : "";
                        if (distanceUnit.get() == DistanceUnit.Kilometers) {
                            distValue = String.format("%.2f km", estDist / 1000.0);
                        } else {
                            distValue = estDist >= 1000 ? String.format("%.1fk blocks", estDist / 1000.0) : String.format("%.0f blocks", estDist);
                        }
                        distColor = timeColor;
                    }
                } else {
                    if (showFlightTime.get()) { timeLabel = showText ? "Est. Flight: " : ""; timeValue = "Calculating..."; }
                    if (showDistance.get())   { distLabel = showText ? "Est. Distance: " : ""; distValue = "Calculating..."; }
                }
            }
        } else {
            flightStartTime = -1;
        }

        boolean hasStatus = statusValue != null;
        boolean hasDur    = durLabel    != null;
        boolean hasRocket = rocketLabel != null;
        boolean hasTime   = timeValue   != null;
        boolean hasDist   = distValue   != null;

        if (!hasStatus && !hasDur && !hasRocket && !hasTime && !hasDist) { setSize(0, 0); return; }

        // ── Measure text widths ───────────────────────────────────────────────

        // Text block width for each stat row (label + value, no icon)
        double durTextW    = durLabel    != null ? renderer.textWidth(durLabel,    false, s) + renderer.textWidth(durValue,    false, s) : 0;
        double rocketTextW = rocketLabel != null ? renderer.textWidth(rocketLabel, false, s) + renderer.textWidth(rocketValue, false, s) : 0;
        double timeTextW   = timeLabel   != null ? renderer.textWidth(timeLabel,   false, s) + renderer.textWidth(timeValue,   false, s) : 0;
        double distTextW   = distLabel   != null ? renderer.textWidth(distLabel,   false, s) + renderer.textWidth(distValue,   false, s) : 0;

        // Gap between icon and text — only non-zero when BOTH icon and text are shown
        double effectiveIconGap = (showIcon && showText) ? iconGap : 0;

        // Full row width depends on icon position
        double durW, rocketW, timeW, distW;
        if (!showIcon || iconVertical) {
            // Vertical icon: icon sits above/below text, row width = max(iconSz, textW)
            durW    = durLabel    != null ? (showIcon && !elytraStack.isEmpty() ? Math.max(iconSz, durTextW)    : durTextW)    : 0;
            rocketW = rocketLabel != null ? (showIcon && !rocketStack.isEmpty() ? Math.max(iconSz, rocketTextW) : rocketTextW) : 0;
            timeW   = timeLabel   != null ? (showIcon ? Math.max(iconSz, timeTextW) : timeTextW) : 0;
            distW   = distLabel   != null ? (showIcon ? Math.max(iconSz, distTextW) : distTextW) : 0;
        } else {
            // Horizontal icon (Left/Right): icon + gap (only if text present) + text
            double durIconW    = (showIcon && !elytraStack.isEmpty())  ? iconSz + effectiveIconGap : 0;
            double rocketIconW = (showIcon && !rocketStack.isEmpty())  ? iconSz + effectiveIconGap : 0;
            double timeIconW   = showIcon ? iconSz + effectiveIconGap : 0;
            double distIconW   = showIcon ? iconSz + effectiveIconGap : 0;
            durW    = durLabel    != null ? durIconW    + durTextW    : 0;
            rocketW = rocketLabel != null ? rocketIconW + rocketTextW : 0;
            timeW   = timeLabel   != null ? timeIconW   + timeTextW   : 0;
            distW   = distLabel   != null ? distIconW   + distTextW   : 0;
        }

        // ── Element dimensions ────────────────────────────────────────────────

        double contentW = Math.max(statusW, Math.max(durW, Math.max(rocketW, Math.max(timeW, distW))));
        // Ensure a minimum width when only icons are shown so the element is visible
        if (showIcon && !showText) contentW = Math.max(contentW, iconSz);
        double totalW   = contentW + padH * 2;
        
        double contentH = (hasStatus ? statusRowH + rowGap : 0)
                        + (hasDur ? statRowH + rowGap : 0)
                        + (hasRocket ? statRowH + rowGap : 0)
                        + (hasTime ? statRowH + rowGap : 0)
                        + (hasDist ? statRowH + rowGap : 0)
                        - rowGap;

        double barSize = 3 * s;
        double barGap  = 3 * s;
        boolean barVertical = bp == BarPosition.Left || bp == BarPosition.Right;

        double totalH = contentH + padV * 2;

        if (showBar.get()) {
            if (barVertical) totalW += barSize + barGap;
            else             totalH += barSize + barGap;
        }

        // ── Draw ──────────────────────────────────────────────────────────────

        Alignment align       = alignment.get();
        boolean   rightAlign  = align == Alignment.Right;
        boolean   centerAlign = align == Alignment.Center;

        if (showBackground.get())
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());

        double contentX = x + padH;
        double contentY = y + padV;

        if (showBar.get()) {
            if (bp == BarPosition.Left) contentX += barSize + barGap;
            if (bp == BarPosition.Above) contentY += barSize + barGap;
        }

        double curX = contentX - padH; // Back out padH because draw methods add it back
        double contentRowW = contentW + padH * 2;
        double curY = contentY;

        if (hasStatus) {
            drawTextRow(renderer, s, curX, curY, contentRowW, padH, statusRowH, lineHeight,
                rightAlign, centerAlign, statusW,
                statusLabel, statusValue, labelColor.get(), valueColor.get());
            curY += statusRowH + rowGap;
        }

        if (hasDur) {
            drawStatRow(renderer, s, curX, curY, contentRowW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, durW, durTextW,
                showIcon ? elytraStack : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                durLabel, durValue, labelColor.get(), durColor);
            curY += statRowH + rowGap;
        }

        if (hasRocket) {
            drawStatRow(renderer, s, curX, curY, contentRowW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, rocketW, rocketTextW,
                showIcon ? rocketStack : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                rocketLabel, rocketValue, labelColor.get(), rocketColor);
            curY += statRowH + rowGap;
        }

        if (hasTime) {
            drawStatRow(renderer, s, curX, curY, contentRowW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, timeW, timeTextW,
                showIcon ? new ItemStack(Items.CLOCK) : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                timeLabel, timeValue, labelColor.get(), timeColor);
            curY += statRowH + rowGap;
        }

        if (hasDist) {
            drawStatRow(renderer, s, curX, curY, contentRowW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, distW, distTextW,
                showIcon ? new ItemStack(Items.COMPASS) : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                distLabel, distValue, labelColor.get(), distColor);
        }

        if (showBar.get()) {
            double bx, by, bw, bh;
            SettingColor bCol = efficiency > 0.5 ? valueColor.get() : efficiency > 0.2 ? rocketWarningColor.get() : rocketCriticalColor.get();
            
            if (!barVertical) {
                bw = contentW;
                bh = barSize;
                bx = contentX;
                by = (bp == BarPosition.Above) ? contentY - barGap - bh : contentY + contentH + barGap;

                renderer.quad(bx, by, bw, bh, new Color(0, 0, 0, 100));
                renderer.quad(bx, by, bw * efficiency, bh, bCol);
            } else {
                bw = barSize;
                bh = contentH;
                bx = (bp == BarPosition.Left) ? contentX - barGap - bw : contentX + contentW + barGap;
                by = contentY;

                renderer.quad(bx, by, bw, bh, new Color(0, 0, 0, 100));
                double progressH = bh * efficiency;
                renderer.quad(bx, by + (bh - progressH), bw, progressH, bCol);
            }
        }

        setSize(totalW, totalH);
    }

    // ── Draw a plain text row (used for status line) ──────────────────────────

    private void drawTextRow(HudRenderer renderer, double s,
                             double rx, double ry, double totalW, double padH,
                             double rowH, double lineHeight,
                             boolean rightAlign, boolean centerAlign,
                             double lineW,
                             String label, String value,
                             SettingColor lColor, SettingColor vColor) {

        double textY = ry + (rowH - lineHeight) / 2.0;

        if (showBackground.get())
            renderer.quad(rx, ry - 1, totalW, rowH + 2, backgroundColor.get());

        if (rightAlign) {
            double cx = rx + totalW - padH;
            cx -= renderer.textWidth(value, false, s);
            renderer.text(value, cx, textY, vColor, false, s);
            cx -= renderer.textWidth(label, false, s);
            renderer.text(label, cx, textY, lColor, false, s);
        } else {
            double cx = centerAlign ? rx + (totalW - lineW) / 2.0 : rx + padH;
            renderer.text(label, cx, textY, lColor, false, s);
            renderer.text(value, cx + renderer.textWidth(label, false, s), textY, vColor, false, s);
        }
    }

    // ── Draw a stat row (elytra or rocket) with configurable icon position ────
    //
    // iconPos controls where the icon sits relative to the text block:
    //   Left  – [icon] [label value]
    //   Right – [label value] [icon]
    //   Above – [icon centred]
    //             [label value]
    //   Below – [label value]
    //           [icon centred]

    private void drawStatRow(HudRenderer renderer, double s,
                             double rx, double ry, double totalW, double padH,
                             double rowH, double lineHeight,
                             boolean rightAlign, boolean centerAlign,
                             double lineW, double textW,
                             ItemStack icon, double iconSz, double iconGap,
                             IconPosition iconPos,
                             String label, String value,
                             SettingColor lColor, SettingColor vColor) {

        boolean hasIcon = !icon.isEmpty();

        if (showBackground.get())
            renderer.quad(rx, ry - 1, totalW, rowH + 2, backgroundColor.get());

        if (!hasIcon || iconPos == IconPosition.Left || iconPos == IconPosition.Right) {
            // ── Horizontal arrangement ────────────────────────────────────────
            double textY = ry + (rowH - lineHeight) / 2.0;
            double iconY = ry + (rowH - iconSz)     / 2.0;

            if (rightAlign) {
                double cx = rx + totalW - padH;
                if (iconPos == IconPosition.Right && hasIcon) {
                    renderer.item(icon, (int)(cx - iconSz), (int) iconY, iconScale.get().floatValue(), false);
                    cx -= iconSz + iconGap;
                }
                if (value != null && !value.isEmpty()) {
                    cx -= renderer.textWidth(value, false, s);
                    renderer.text(value, cx, textY, vColor, false, s);
                }
                if (label != null && !label.isEmpty()) {
                    cx -= renderer.textWidth(label, false, s);
                    renderer.text(label, cx, textY, lColor, false, s);
                }
                if (iconPos == IconPosition.Left && hasIcon) {
                    cx -= iconGap + iconSz;
                    renderer.item(icon, (int) cx, (int) iconY, iconScale.get().floatValue(), false);
                }
            } else {
                double cx = centerAlign ? rx + (totalW - lineW) / 2.0 : rx + padH;
                if (iconPos == IconPosition.Left && hasIcon) {
                    renderer.item(icon, (int) cx, (int) iconY, iconScale.get().floatValue(), false);
                    cx += iconSz + iconGap;
                }
                if (label != null && !label.isEmpty()) {
                    renderer.text(label, cx, textY, lColor, false, s);
                    cx += renderer.textWidth(label, false, s);
                }
                if (value != null && !value.isEmpty()) {
                    renderer.text(value, cx, textY, vColor, false, s);
                    cx += renderer.textWidth(value, false, s);
                }
                if (iconPos == IconPosition.Right && hasIcon) {
                    cx += iconGap;
                    renderer.item(icon, (int) cx, (int)(ry + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                }
            }

        } else {
            // ── Vertical arrangement (Above / Below) ──────────────────────────
            // rowH = iconSz + iconGap + lineHeight
            double iconY, textY;
            if (iconPos == IconPosition.Above) {
                iconY = ry;
                textY = ry + iconSz + iconGap;
            } else { // Below
                textY = ry;
                iconY = ry + lineHeight + iconGap;
            }

            // Icon centred horizontally within totalW (or within lineW for center/right)
            double iconX;
            if (rightAlign) {
                iconX = rx + totalW - padH - iconSz;
            } else if (centerAlign) {
                iconX = rx + (totalW - iconSz) / 2.0;
            } else {
                iconX = rx + padH + (textW - iconSz) / 2.0;
                if (iconX < rx + padH) iconX = rx + padH;
            }
            if (hasIcon)
                renderer.item(icon, (int) iconX, (int) iconY, iconScale.get().floatValue(), false);

            // Text drawn on its row
            if (rightAlign) {
                double cx = rx + totalW - padH;
                if (value != null && !value.isEmpty()) {
                    cx -= renderer.textWidth(value, false, s);
                    renderer.text(value, cx, textY, vColor, false, s);
                }
                if (label != null && !label.isEmpty()) {
                    cx -= renderer.textWidth(label, false, s);
                    renderer.text(label, cx, textY, lColor, false, s);
                }
            } else {
                double cx = centerAlign ? rx + (totalW - textW) / 2.0 : rx + padH;
                if (label != null && !label.isEmpty()) {
                    renderer.text(label, cx, textY, lColor, false, s);
                    cx += renderer.textWidth(label, false, s);
                }
                if (value != null && !value.isEmpty()) {
                    renderer.text(value, cx, textY, vColor, false, s);
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int countRockets() {
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

    private int countElytras() {
        if (mc.player == null) return 0;
        int count = 0;
        double threshold = elytraDurabilityThreshold.get();

        for (ItemStack stack : mc.player.getInventory().main) {
            if (isValidElytra(stack, threshold)) count++;
        }
        if (isValidElytra(mc.player.getEquippedStack(EquipmentSlot.CHEST), threshold)) count++;
        if (isValidElytra(mc.player.getOffHandStack(), threshold)) count++;

        return count;
    }

    private boolean isValidElytra(ItemStack stack, double threshold) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.ELYTRA)) return false;
        if (!stack.isDamageable()) return true;
        double pct = 100.0 * (stack.getMaxDamage() - stack.getDamage()) / stack.getMaxDamage();
        return pct > threshold;
    }
}