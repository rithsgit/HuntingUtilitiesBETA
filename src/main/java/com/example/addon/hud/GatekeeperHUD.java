package com.example.addon.hud;

import com.example.addon.Tim;
import com.example.addon.modules.Gatekeeper;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

public class GatekeeperHUD extends HudElement {

    public static final HudElementInfo<GatekeeperHUD> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "gatekeeper",
        "Displays end portal and gateway statistics for the area.",
        GatekeeperHUD::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ── Layout ────────────────────────────────────────────────────────────────

    public enum Layout { Inline, Stacked, StackedIcons }

    private final Setting<Layout> layout = sgGeneral.add(new EnumSetting.Builder<Layout>()
        .name("layout")
        .description("Inline: single line with separator. Stacked: one row per stat. StackedIcons: stacked with item icons.")
        .defaultValue(Layout.Inline)
        .build()
    );

    // ── Feature toggles ───────────────────────────────────────────────────────

    private final Setting<Boolean> showEndPortals = sgGeneral.add(new BoolSetting.Builder()
        .name("show-end-portals")
        .description("Show the count of End Portal frames in the area.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showEndGateways = sgGeneral.add(new BoolSetting.Builder()
        .name("show-end-gateways")
        .description("Show the total End Gateways in the area.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showAnomalousGateways = sgGeneral.add(new BoolSetting.Builder()
        .name("show-anomalous-gateways")
        .description("Show the count of anomalous gateways (broken or far-out).")
        .defaultValue(true)
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
        .description("Align text within the element. Has no effect in Inline layout.")
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

    private final Setting<IconPosition> iconPosition = sgGeneral.add(new EnumSetting.Builder<IconPosition>()
        .name("icon-position")
        .description("Where the icon appears relative to the text.")
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
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconGapSetting = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between icon and text.")
        .defaultValue(4.0)
        .min(0)
        .sliderRange(0, 16)
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
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
        .description("Color for values.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("separator-color")
        .description("Color of the | separator.")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .visible(() -> layout.get() == Layout.Inline)
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background behind the element.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public GatekeeperHUD() { super(INFO); }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        Gatekeeper tracker = Modules.get().get(Gatekeeper.class);
        if (tracker == null || !tracker.isActive()) { setSize(0, 0); return; }
        if (!showEndPortals.get() && !showEndGateways.get() && !showAnomalousGateways.get()) { setSize(0, 0); return; }

        switch (layout.get()) {
            case Inline       -> renderInline(renderer, tracker);
            case Stacked      -> renderStacked(renderer, tracker, false);
            case StackedIcons -> renderStacked(renderer, tracker, true);
        }
    }

    private void renderInline(HudRenderer renderer, Gatekeeper tracker) {
        double s = scale.get(), padH = 4 * s, padV = 2 * s, lh = renderer.textHeight(false, s), sepW = renderer.textWidth(" | ", false, s);
        double iconSz = 16.0 * iconScale.get(), iconGap = iconGapSetting.get() * s;
        LabelMode mode = labelMode.get(); boolean showIcon = mode != LabelMode.Text, showLabel = mode != LabelMode.Icon;
        IconPosition iconPos = iconPosition.get(); double effIconGap = showIcon ? iconGap : 0;

        record Stat(String label, String value, ItemStack icon) {}
        List<Stat> segments = new ArrayList<>();
        if (showEndPortals.get())         segments.add(new Stat("End Portals: ", String.valueOf(tracker.getTotalEndPortals()),    new ItemStack(Items.ENDER_EYE)));
        if (showEndGateways.get())        segments.add(new Stat("Gateways: ",    String.valueOf(tracker.getTotalGateways()),       new ItemStack(Items.CHORUS_FLOWER)));
        if (segments.isEmpty()) { setSize(0, 0); return; }

        double totalW = 0, rowH = showIcon ? Math.max(lh, iconSz) : lh;
        for (int i = 0; i < segments.size(); i++) {
            Stat st = segments.get(i);
            double segW = 0;
            if (showLabel) segW += renderer.textWidth(st.label(), false, s);
            segW += renderer.textWidth(st.value(), false, s);
            if (showIcon) segW += iconSz + effIconGap;
            totalW += segW;
            if (i < segments.size() - 1) totalW += sepW;
        }
        setSize(totalW + padH * 2, rowH + padV * 2);
        if (showBackground.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());
        double cx = x + padH, rowY = y + padV;
        for (int i = 0; i < segments.size(); i++) {
            Stat st = segments.get(i);
            if (showIcon && iconPos == IconPosition.Left) {
                renderer.item(st.icon(), (int) cx, (int) (rowY + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz + effIconGap;
            }
            if (showLabel) {
                renderer.text(st.label(), cx, rowY + (rowH - lh) / 2.0, labelColor.get(), false, s);
                cx += renderer.textWidth(st.label(), false, s);
            }
            renderer.text(st.value(), cx, rowY + (rowH - lh) / 2.0, valueColor.get(), false, s);
            cx += renderer.textWidth(st.value(), false, s);
            if (showIcon && iconPos != IconPosition.Left) {
                cx += effIconGap;
                renderer.item(st.icon(), (int) cx, (int) (rowY + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz;
            }
            if (i < segments.size() - 1) { renderer.text(" | ", cx, rowY + (rowH - lh) / 2.0, separatorColor.get(), false, s); cx += sepW; }
        }
    }

    private void renderStacked(HudRenderer renderer, Gatekeeper tracker, boolean withIcons) {
        double s = scale.get(), padH = 4 * s, padV = 2 * s, rowGap = 2 * s, lh = renderer.textHeight(false, s);
        double iconSz = withIcons ? 16.0 * iconScale.get() : 0, iconGap = withIcons ? iconGapSetting.get() * s : 0;
        LabelMode mode = withIcons ? labelMode.get() : LabelMode.Text;
        IconPosition iconPos = withIcons ? iconPosition.get() : IconPosition.Left;
        boolean showIcon = withIcons && mode != LabelMode.Text, showText = mode != LabelMode.Icon;
        boolean iconVertical = showIcon && (iconPos == IconPosition.Above || iconPos == IconPosition.Below);
        double effectiveIconGap = (showIcon && showText) ? iconGap : 0;
        double statRowH = !showIcon ? lh : iconVertical ? iconSz + iconGap + lh : Math.max(lh, iconSz);

        record Data(String label, String value, ItemStack icon) {}
        List<Data> stats = new ArrayList<>();
        if (showEndPortals.get())        stats.add(new Data(showText ? "End Portals: " : "", String.valueOf(tracker.getTotalEndPortals()),    showIcon ? new ItemStack(Items.ENDER_EYE) : ItemStack.EMPTY));
        if (showEndGateways.get())       stats.add(new Data(showText ? "Gateways: " : "",    String.valueOf(tracker.getTotalGateways()),       showIcon ? new ItemStack(Items.CHORUS_FLOWER) : ItemStack.EMPTY));
        if (stats.isEmpty()) { setSize(0, 0); return; }

        double maxRowW = 0;
        for (Data d : stats) {
            double tw = renderer.textWidth(d.label, false, s) + renderer.textWidth(d.value, false, s);
            double rw = (!showIcon || iconVertical) ? (showIcon && !d.icon.isEmpty() ? Math.max(iconSz, tw) : tw) : (showIcon && !d.icon.isEmpty() ? iconSz + effectiveIconGap : 0) + tw;
            maxRowW = Math.max(maxRowW, rw);
        }
        double totalW = maxRowW + padH * 2, totalH = stats.size() * statRowH + (stats.size() - 1) * rowGap + padV * 2;
        setSize(totalW, totalH);

        Alignment align = alignment.get(); boolean rightAlign = align == Alignment.Right, centerAlign = align == Alignment.Center;
        double curY = y + padV;
        for (Data d : stats) {
            double tw = renderer.textWidth(d.label, false, s) + renderer.textWidth(d.value, false, s);
            double rw = (!showIcon || iconVertical) ? (showIcon && !d.icon.isEmpty() ? Math.max(iconSz, tw) : tw) : (showIcon && !d.icon.isEmpty() ? iconSz + effectiveIconGap : 0) + tw;
            drawStatRow(renderer, s, x, curY, totalW, padH, statRowH, lh, rightAlign, centerAlign, rw, tw, d.icon, iconSz, effectiveIconGap, iconPos, d.label, d.value, labelColor.get(), valueColor.get());
            curY += statRowH + rowGap;
        }
    }

    private void drawStatRow(HudRenderer renderer, double s, double rx, double ry, double totalW, double padH, double rowH, double lineHeight, boolean rightAlign, boolean centerAlign, double lineW, double textW, ItemStack icon, double iconSz, double iconGap, IconPosition iconPos, String label, String value, SettingColor lColor, SettingColor vColor) {
        boolean hasIcon = !icon.isEmpty();
        if (showBackground.get()) renderer.quad(rx, ry - 1, totalW, rowH + 2, backgroundColor.get());
        if (!hasIcon || iconPos == IconPosition.Left || iconPos == IconPosition.Right) {
            double textY = ry + (rowH - lineHeight) / 2.0, iconY = ry + (rowH - iconSz) / 2.0;
            if (rightAlign) {
                double cx = rx + totalW - padH;
                if (iconPos == IconPosition.Right && hasIcon) { renderer.item(icon, (int)(cx - iconSz), (int) iconY, iconScale.get().floatValue(), false); cx -= iconSz + iconGap; }
                if (value != null && !value.isEmpty()) { cx -= renderer.textWidth(value, false, s); renderer.text(value, cx, textY, vColor, false, s); }
                if (label != null && !label.isEmpty()) { cx -= renderer.textWidth(label, false, s); renderer.text(label, cx, textY, lColor, false, s); }
                if (iconPos == IconPosition.Left && hasIcon) { cx -= iconGap + iconSz; renderer.item(icon, (int) cx, (int) iconY, iconScale.get().floatValue(), false); }
            } else {
                double cx = centerAlign ? rx + (totalW - lineW) / 2.0 : rx + padH;
                if (iconPos == IconPosition.Left && hasIcon) { renderer.item(icon, (int) cx, (int) iconY, iconScale.get().floatValue(), false); cx += iconSz + iconGap; }
                if (label != null && !label.isEmpty()) { renderer.text(label, cx, textY, lColor, false, s); cx += renderer.textWidth(label, false, s); }
                if (value != null && !value.isEmpty()) { renderer.text(value, cx, textY, vColor, false, s); }
                if (iconPos == IconPosition.Right && hasIcon) { cx += iconGap; renderer.item(icon, (int) cx, (int)(ry + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false); }
            }
        } else {
            double iconY, textY;
            if (iconPos == IconPosition.Above) { iconY = ry; textY = ry + iconSz + iconGap; }
            else { textY = ry; iconY = ry + lineHeight + iconGap; }
            double iconX = rightAlign ? rx + totalW - padH - iconSz : centerAlign ? rx + (totalW - iconSz) / 2.0 : rx + padH + (textW - iconSz) / 2.0;
            if (hasIcon) renderer.item(icon, (int) iconX, (int) iconY, iconScale.get().floatValue(), false);
            if (rightAlign) {
                double cx = rx + totalW - padH;
                if (value != null && !value.isEmpty()) { cx -= renderer.textWidth(value, false, s); renderer.text(value, cx, textY, vColor, false, s); }
                if (label != null && !label.isEmpty()) { cx -= renderer.textWidth(label, false, s); renderer.text(label, cx, textY, lColor, false, s); }
            } else {
                double cx = centerAlign ? rx + (totalW - textW) / 2.0 : rx + padH;
                if (label != null && !label.isEmpty()) { renderer.text(label, cx, textY, lColor, false, s); cx += renderer.textWidth(label, false, s); }
                if (value != null && !value.isEmpty()) { renderer.text(value, cx, textY, vColor, false, s); }
            }
        }
    }
}