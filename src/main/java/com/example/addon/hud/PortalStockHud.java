package com.example.addon.hud;


import com.example.addon.Tim;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class PortalStockHud extends HudElement {
    public static final HudElementInfo<PortalStockHud> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP, "Portal Stock",
        "Portal Stock",
        "Displays obsidian count in inventory and portal frame progress.",
        PortalStockHud::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgWarnings = settings.createGroup("Warnings");

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
        .defaultValue(1.0).min(0.25).sliderRange(0.25, 4.0)
        .build()
    );

    public enum Alignment { Left, Center, Right }

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align text to the left, center, or right.")
        .defaultValue(Alignment.Left)
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
        .description("Where the item icon appears relative to the text.")
        .defaultValue(IconPosition.Left)
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("Scale of the item icons.")
        .defaultValue(1.5).min(0.5).sliderRange(0.5, 4.0)
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconGapSetting = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between the icon and the text.")
        .defaultValue(4.0).min(0).sliderRange(0, 16)
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
        .description("Color for values when healthy.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
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

    // ── Feature toggles ───────────────────────────────────────────────────────

    private final Setting<Boolean> showObsidianCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-obsidian-count")
        .description("Show total obsidian in inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideObsidianIfZero = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-obsidian-if-zero")
        .description("Hide obsidian count if you have 0 in inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showEnderChestCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-ender-chest-count")
        .description("Show total ender chests in inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideEnderIfZero = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-ender-if-zero")
        .description("Hide ender chest count if you have 0 in inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showPortalProgress = sgGeneral.add(new BoolSetting.Builder()
        .name("show-portal-progress")
        .description("Show portal frame placement progress (requires Portal Maker module).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBar = sgGeneral.add(new BoolSetting.Builder()
        .name("show-bar")
        .description("Show a color-coded progress bar.")
        .defaultValue(true)
        .build()
    );

    public enum BarPosition { Above, Below, Left, Right }

    private final Setting<BarPosition> barPosition = sgGeneral.add(new EnumSetting.Builder<BarPosition>()
        .name("bar-position")
        .description("Where the bar appears relative to content.")
        .defaultValue(BarPosition.Below)
        .visible(showBar::get)
        .build()
    );

    // ── Warning settings ──────────────────────────────────────────────────────

    private final Setting<Integer> warningThreshold = sgWarnings.add(new IntSetting.Builder()
        .name("warning-threshold")
        .description("Obsidian count at or below which the value turns warning color.")
        .defaultValue(64).min(0).sliderRange(0, 256)
        .build()
    );

    private final Setting<SettingColor> warningColor = sgWarnings.add(new ColorSetting.Builder()
        .name("warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

    private final Setting<Integer> criticalThreshold = sgWarnings.add(new IntSetting.Builder()
        .name("critical-threshold")
        .description("Obsidian count at or below which the value turns critical color.")
        .defaultValue(16).min(0).sliderRange(0, 128)
        .build()
    );

    private final Setting<SettingColor> criticalColor = sgWarnings.add(new ColorSetting.Builder()
        .name("critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255))
        .build()
    );

    private final Setting<Integer> enderWarningThreshold = sgWarnings.add(new IntSetting.Builder()
        .name("ender-warning-threshold")
        .description("Ender chest count at or below which the value turns warning color.")
        .defaultValue(1).min(0).sliderRange(0, 64)
        .build()
    );

    private final Setting<SettingColor> enderWarningColor = sgWarnings.add(new ColorSetting.Builder()
        .name("ender-warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

    private final Setting<Integer> enderCriticalThreshold = sgWarnings.add(new IntSetting.Builder()
        .name("ender-critical-threshold")
        .description("Ender chest count at or below which the value turns critical color.")
        .defaultValue(0).min(0).sliderRange(0, 64)
        .build()
    );

    private final Setting<SettingColor> enderCriticalColor = sgWarnings.add(new ColorSetting.Builder()
        .name("ender-critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255))
        .build()
    );

    // ── Portal frame positions (set externally by PortalMaker module) ─────────

    public List<BlockPos> portalFramePositions = new ArrayList<>();

    private record Stat(String label, String value, ItemStack icon, SettingColor valColor) {}

    public PortalStockHud() { super(INFO); }

    // ── Render ────────────────────────────────────────────────────────────────

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

        List<Stat> segments = new ArrayList<>();

        if (showEnderChestCount.get()) {
            int count = countItem(Items.ENDER_CHEST);
            if (!hideEnderIfZero.get() || count > 0 || isInEditor()) {
                SettingColor col = valueColor.get();
                if (count <= enderCriticalThreshold.get()) col = enderCriticalColor.get();
                else if (count <= enderWarningThreshold.get()) col = enderWarningColor.get();
                segments.add(new Stat("Ender Chests: ", String.valueOf(count), new ItemStack(Items.ENDER_CHEST), col));
            }
        }

        if (showObsidianCount.get()) {
            int count = countItem(Items.OBSIDIAN);
            if (!hideObsidianIfZero.get() || count > 0 || isInEditor()) {
                SettingColor col = valueColor.get();
                if (count <= criticalThreshold.get()) col = criticalColor.get();
                else if (count <= warningThreshold.get()) col = warningColor.get();
                segments.add(new Stat("Obsidian: ", String.valueOf(count), new ItemStack(Items.OBSIDIAN), col));
            }
        }

        if (showPortalProgress.get() && !portalFramePositions.isEmpty() && mc.world != null) {
            int total = portalFramePositions.size(), placed = 0;
            for (BlockPos pos : portalFramePositions) if (mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) placed++;
            double pct = (double) placed / total;
            SettingColor col = pct >= 1.0 ? new SettingColor(60, 255, 60, 255) : pct > 0.4 ? new SettingColor(255, 165, 0, 255) : new SettingColor(255, 60, 60, 255);
            segments.add(new Stat("Portal: ", placed + "/" + total, new ItemStack(Items.OBSIDIAN), col));
        }

        if (segments.isEmpty()) { setSize(0, 0); return; }

        double totalW = 0, rowH = showIcon ? Math.max(lh, iconSz) : lh;
        for (int i = 0; i < segments.size(); i++) {
            Stat st = segments.get(i);
            double segW = 0;
            if (showLabel) segW += renderer.textWidth(st.label, false, s);
            segW += renderer.textWidth(st.value, false, s);
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
                renderer.item(st.icon, (int) cx, (int) (rowY + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz + effIconGap;
            }
            if (showLabel) {
                renderer.text(st.label, cx, rowY + (rowH - lh) / 2.0, labelColor.get(), false, s);
                cx += renderer.textWidth(st.label, false, s);
            }
            renderer.text(st.value, cx, rowY + (rowH - lh) / 2.0, st.valColor, false, s);
            cx += renderer.textWidth(st.value, false, s);

            if (showIcon && iconPos != IconPosition.Left) {
                cx += effIconGap;
                renderer.item(st.icon, (int) cx, (int) (rowY + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz;
            }
            if (i < segments.size() - 1) {
                renderer.text(" | ", cx, rowY + (rowH - lh) / 2.0, separatorColor.get(), false, s);
                cx += sepW;
            }
        }
    }

    private void renderStacked(HudRenderer renderer, boolean withIcons) {
        double s           = scale.get();
        double padH        = 4 * s;
        double padV        = 2 * s;
        double rowGap      = 2 * s;
        double lineHeight  = renderer.textHeight(false, s);
        double iconSz      = 16.0 * iconScale.get();
        double iconGap     = iconGapSetting.get() * s;

        LabelMode    mode         = withIcons ? labelMode.get() : LabelMode.Text;
        IconPosition iconPos      = withIcons ? iconPosition.get() : IconPosition.Left;
        boolean      showIcon     = withIcons && mode != LabelMode.Text;
        boolean      showText     = mode != LabelMode.Icon;
        boolean      iconVertical = showIcon && (iconPos == IconPosition.Above || iconPos == IconPosition.Below);

        double statRowH = !showIcon ? lineHeight
            : iconVertical ? iconSz + iconGap + lineHeight
            : Math.max(lineHeight, iconSz);

        // ── Gather data ───────────────────────────────────────────────────────

        String       enderLabel = null, enderValue = null;
        SettingColor enderColor = valueColor.get();
        int          enderCount = 0;
        ItemStack    enderStack = new ItemStack(Items.ENDER_CHEST);

        if (showEnderChestCount.get()) {
            enderCount = countItem(Items.ENDER_CHEST);
            if (!hideEnderIfZero.get() || enderCount > 0 || isInEditor()) {
                enderLabel = showText ? "Ender Chests: " : "";
                enderValue = String.valueOf(enderCount);
                if      (enderCount <= enderCriticalThreshold.get()) enderColor = enderCriticalColor.get();
                else if (enderCount <= enderWarningThreshold.get())  enderColor = enderWarningColor.get();
            }
        }

        String       obsLabel = null, obsValue = null;
        SettingColor obsColor = valueColor.get();
        int          obsCount = 0;
        ItemStack    obsStack = new ItemStack(Items.OBSIDIAN);

        if (showObsidianCount.get()) {
            obsCount = countItem(Items.OBSIDIAN);
            if (!hideObsidianIfZero.get() || obsCount > 0 || isInEditor()) {
                obsLabel = showText ? "Obsidian: " : "";
                obsValue = String.valueOf(obsCount);
                if      (obsCount <= criticalThreshold.get()) obsColor = criticalColor.get();
                else if (obsCount <= warningThreshold.get())  obsColor = warningColor.get();
            }
        }

        String       portalLabel = null, portalValue = null;
        SettingColor portalColor = valueColor.get();
        double       portalPct   = 0;
        int          placed = 0, total = 0;

        if (showPortalProgress.get() && !portalFramePositions.isEmpty() && mc.world != null) {
            total = portalFramePositions.size();
            for (BlockPos pos : portalFramePositions) {
                if (mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) placed++;
            }
            portalPct   = (double) placed / total;
            portalLabel = showText ? "Portal: " : "";
            portalValue = placed + "/" + total;
            portalColor = portalPct >= 1.0
                ? new SettingColor(60, 255, 60, 255)
                : portalPct > 0.4
                    ? new SettingColor(255, 165, 0, 255)
                    : new SettingColor(255, 60, 60, 255);
        }

        boolean hasObs    = obsLabel    != null;
        boolean hasEnder  = enderLabel  != null;
        boolean hasPortal = portalLabel != null;

        if (!hasObs && !hasEnder && !hasPortal) {
            if (isInEditor()) {
                double lh = renderer.textHeight(false, s);
                setSize(renderer.textWidth("Obsidian: 0", false, s) + padH * 2, lh + padV * 2);
                renderer.text("Obsidian: 0", x + padH, y + padV, Color.GRAY, false, s);
            } else {
                setSize(0, 0);
            }
            return;
        }

        // ── Measure widths ────────────────────────────────────────────────────

        double enderTextW  = hasEnder  ? renderer.textWidth(enderLabel,  false, s) + renderer.textWidth(enderValue,  false, s) : 0;
        double obsTextW    = hasObs    ? renderer.textWidth(obsLabel,    false, s) + renderer.textWidth(obsValue,    false, s) : 0;
        double portalTextW = hasPortal ? renderer.textWidth(portalLabel, false, s) + renderer.textWidth(portalValue, false, s) : 0;

        double effectiveIconGap = (showIcon && showText) ? iconGap : 0;

        double enderW, obsW, portalW;
        if (!showIcon || iconVertical) {
            enderW  = hasEnder  ? (showIcon ? Math.max(iconSz, enderTextW)  : enderTextW)  : 0;
            obsW    = hasObs    ? (showIcon ? Math.max(iconSz, obsTextW)    : obsTextW)    : 0;
            portalW = hasPortal ? (showIcon ? Math.max(iconSz, portalTextW) : portalTextW) : 0;
        } else {
            double iconColW = iconSz + effectiveIconGap;
            enderW  = hasEnder  ? iconColW + enderTextW  : 0;
            obsW    = hasObs    ? iconColW + obsTextW    : 0;
            portalW = hasPortal ? iconColW + portalTextW : 0;
        }

        // ── Dimensions ────────────────────────────────────────────────────────

        double contentW = Math.max(obsW, Math.max(enderW, portalW));
        if (showIcon && !showText) contentW = Math.max(contentW, iconSz);
        double totalW = contentW + padH * 2;

        double contentH = (hasObs    ? statRowH + rowGap : 0)
                        + (hasEnder  ? statRowH + rowGap : 0)
                        + (hasPortal ? statRowH + rowGap : 0)
                        - rowGap;

        BarPosition bp          = barPosition.get();
        boolean     barVertical = bp == BarPosition.Left || bp == BarPosition.Right;
        double      barSize     = 3 * s;
        double      barGap      = 3 * s;
        double      totalH      = contentH + padV * 2;

        if (showBar.get()) {
            if (barVertical) totalW += barSize + barGap;
            else             totalH += barSize + barGap;
        }

        // ── Draw ──────────────────────────────────────────────────────────────

        boolean rightAlign  = alignment.get() == Alignment.Right;
        boolean centerAlign = alignment.get() == Alignment.Center;

        if (showBackground.get())
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());

        double contentX = x + padH;
        double contentY = y + padV;

        if (showBar.get()) {
            if (bp == BarPosition.Left)  contentX += barSize + barGap;
            if (bp == BarPosition.Above) contentY += barSize + barGap;
        }

        double curX        = contentX - padH;
        double contentRowW = contentW + padH * 2;
        double curY        = contentY;

        if (hasEnder) {
            drawStatRow(renderer, s, curX, curY, contentRowW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, enderW, enderTextW,
                showIcon ? enderStack : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                enderLabel, enderValue, labelColor.get(), enderColor);
            curY += statRowH + rowGap;
        }

        if (hasObs) {
            drawStatRow(renderer, s, curX, curY, contentRowW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, obsW, obsTextW,
                showIcon ? obsStack : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                obsLabel, obsValue, labelColor.get(), obsColor);
            curY += statRowH + rowGap;
        }

        if (hasPortal) {
            drawStatRow(renderer, s, curX, curY, contentRowW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, portalW, portalTextW,
                showIcon ? obsStack : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                portalLabel, portalValue, labelColor.get(), portalColor);
        }

        if (showBar.get()) {
            double barVal = hasPortal ? portalPct
                : (obsCount > 0 ? Math.min(1.0, (double) obsCount / Math.max(warningThreshold.get(), 1)) : 0);
            SettingColor bCol = barVal >= 1.0
                ? new SettingColor(60, 255, 60, 255)
                : barVal > 0.4 ? warningColor.get() : criticalColor.get();

            double bx, by, bw, bh;
            if (!barVertical) {
                bw = contentW; bh = barSize;
                bx = contentX;
                by = (bp == BarPosition.Above) ? contentY - barGap - bh : contentY + contentH + barGap;
                renderer.quad(bx, by, bw, bh, new Color(0, 0, 0, 100));
                renderer.quad(bx, by, bw * barVal, bh, bCol);
            } else {
                bw = barSize; bh = contentH;
                bx = (bp == BarPosition.Left) ? contentX - barGap - bw : contentX + contentW + barGap;
                by = contentY;
                renderer.quad(bx, by, bw, bh, new Color(0, 0, 0, 100));
                double progressH = bh * barVal;
                renderer.quad(bx, by + (bh - progressH), bw, progressH, bCol);
            }
        }

        setSize(totalW, totalH);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int countItem(net.minecraft.item.Item item) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(item)) count += s.getCount();
        }
        if (mc.player.getOffHandStack().isOf(item)) count += mc.player.getOffHandStack().getCount();
        return count;
    }

    private void drawStatRow(HudRenderer renderer, double s,
                             double rx, double ry, double totalW, double padH,
                             double rowH, double lineHeight,
                             boolean rightAlign, boolean centerAlign,
                             double lineW, double textW,
                             ItemStack icon, double iconSz, double iconGap,
                             IconPosition iconPos,
                             String label, String value,
                             SettingColor lColor, SettingColor vColor) {

        boolean hasIcon      = !icon.isEmpty();
        boolean iconVertical = hasIcon && (iconPos == IconPosition.Above || iconPos == IconPosition.Below);

        if (showBackground.get())
            renderer.quad(rx, ry - 1, totalW, rowH + 2, backgroundColor.get());

        if (!hasIcon || !iconVertical) {
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
            double iconY, textY;
            if (iconPos == IconPosition.Above) {
                iconY = ry;
                textY = ry + iconSz + iconGap;
            } else {
                textY = ry;
                iconY = ry + lineHeight + iconGap;
            }

            double iconX;
            if (rightAlign)       iconX = rx + totalW - padH - iconSz;
            else if (centerAlign) iconX = rx + (totalW - iconSz) / 2.0;
            else {
                iconX = rx + padH + (textW - iconSz) / 2.0;
                if (iconX < rx + padH) iconX = rx + padH;
            }
            if (hasIcon)
                renderer.item(icon, (int) iconX, (int) iconY, iconScale.get().floatValue(), false);

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
}