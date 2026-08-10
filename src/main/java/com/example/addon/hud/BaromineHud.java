package com.example.addon.hud;

import com.example.addon.Tim;
import com.example.addon.modules.Baromine;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

public class BaromineHud extends HudElement {
    public static final HudElementInfo<BaromineHud> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP, "Baromine",
        "Baromine",
        "Displays Baromine mining progress, target item, and inventory stock.",
        BaromineHud::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgWarnings = settings.createGroup("Warnings");

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

    // Feature Toggles
    private final Setting<Boolean> showStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("show-status")
        .description("Shows current bot activity (Mining, Depositing, etc).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTargetProgress = sgGeneral.add(new BoolSetting.Builder()
        .name("show-target-progress")
        .description("Show current mined count vs target stack goal.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showToolDurability = sgGeneral.add(new BoolSetting.Builder()
        .name("show-tool-durability")
        .description("Show main hand tool durability percentage.")
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

    private final Setting<Boolean> showShulkerCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-shulker-count")
        .description("Show total shulker boxes in inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideShulkerIfZero = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-shulker-if-zero")
        .description("Hide shulker count if you have 0 in inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showRuntime = sgGeneral.add(new BoolSetting.Builder()
        .name("show-runtime")
        .description("Show elapsed session time.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBar = sgGeneral.add(new BoolSetting.Builder()
        .name("show-bar")
        .description("Show a color-coded progress bar for the mining goal.")
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

    // Warnings
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

    private final Setting<Integer> shulkerWarningThreshold = sgWarnings.add(new IntSetting.Builder()
        .name("shulker-warning-threshold")
        .description("Shulker box count at or below which the value turns warning color.")
        .defaultValue(1).min(0).sliderRange(0, 64)
        .build()
    );

    private final Setting<SettingColor> shulkerWarningColor = sgWarnings.add(new ColorSetting.Builder()
        .name("shulker-warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

    private final Setting<Integer> shulkerCriticalThreshold = sgWarnings.add(new IntSetting.Builder()
        .name("shulker-critical-threshold")
        .description("Shulker box count at or below which the value turns critical color.")
        .defaultValue(0).min(0).sliderRange(0, 64)
        .build()
    );

    private final Setting<SettingColor> shulkerCriticalColor = sgWarnings.add(new ColorSetting.Builder()
        .name("shulker-critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255))
        .build()
    );

    private record Stat(String label, String value, ItemStack icon, SettingColor valColor, double progress) {}

    public BaromineHud() { super(INFO); }

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

        Baromine baromine = Modules.get().get(Baromine.class);
        if (baromine != null && baromine.isActive()) {
            if (showStatus.get()) {
                String status = baromine.getCurrentStatus();
                segments.add(new Stat("Status: ", status, new ItemStack(Items.COMPASS), new SettingColor(0, 255, 255, 255), 0));
            }
            if (showTargetProgress.get()) {
                int currentCount = baromine.getCurrentTargetCount();
                int targetCount = baromine.targetStacks.get() * 64;
                double pct = targetCount > 0 ? Math.min(1.0, (double) currentCount / targetCount) : 0;
                SettingColor col = pct >= 1.0 ? new SettingColor(60, 255, 60, 255) : pct > 0.75 ? new SettingColor(255, 165, 0, 255) : valueColor.get();
                segments.add(new Stat("Mined: ", currentCount + "/" + targetCount, new ItemStack(baromine.getTargetBlock().asItem()), col, pct));
            }
            if (showToolDurability.get()) {
                double dur = baromine.getMainHandDurabilityPercent();
                SettingColor col = dur > 50 ? valueColor.get() : dur > 20 ? new SettingColor(255, 165, 0, 255) : new SettingColor(255, 40, 40, 255);
                segments.add(new Stat("Tool: ", String.format("%.0f%%", dur), new ItemStack(Items.DIAMOND_PICKAXE), col, 0));
            }
            if (showRuntime.get()) {
                long elapsed = System.currentTimeMillis() - baromine.getSessionStartTime();
                long secs = (elapsed / 1000) % 60;
                long mins = (elapsed / (1000 * 60)) % 60;
                long hours = (elapsed / (1000 * 60 * 60)) % 24;
                segments.add(new Stat("Runtime: ", String.format("%02dh %02dm %02ds", hours, mins, secs), new ItemStack(Items.CLOCK), valueColor.get(), 0));
            }
        }

        if (showEnderChestCount.get()) {
            int count = countItem(Items.ENDER_CHEST);
            if (!hideEnderIfZero.get() || count > 0 || isInEditor()) {
                SettingColor col = valueColor.get();
                if (count <= enderCriticalThreshold.get()) col = enderCriticalColor.get();
                else if (count <= enderWarningThreshold.get()) col = enderWarningColor.get();
                segments.add(new Stat("Ender Chests: ", String.valueOf(count), new ItemStack(Items.ENDER_CHEST), col, 0));
            }
        }

        if (showShulkerCount.get()) {
            int count = countShulkers();
            if (!hideShulkerIfZero.get() || count > 0 || isInEditor()) {
                SettingColor col = valueColor.get();
                if (count <= shulkerCriticalThreshold.get()) col = shulkerCriticalColor.get();
                else if (count <= shulkerWarningThreshold.get()) col = shulkerWarningColor.get();
                segments.add(new Stat("Shulkers: ", String.valueOf(count), new ItemStack(Items.SHULKER_BOX), col, 0));
            }
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

        List<Stat> stats = new ArrayList<>();
        Baromine baromine = Modules.get().get(Baromine.class);

        if (baromine != null && baromine.isActive()) {
            if (showStatus.get()) {
                stats.add(new Stat(showText ? "Status: " : "", baromine.getCurrentStatus(), new ItemStack(Items.COMPASS), new SettingColor(0, 255, 255, 255), 0));
            }
            if (showTargetProgress.get()) {
                int currentCount = baromine.getCurrentTargetCount();
                int targetCount = baromine.targetStacks.get() * 64;
                double pct = targetCount > 0 ? Math.min(1.0, (double) currentCount / targetCount) : 0;
                SettingColor col = pct >= 1.0 ? new SettingColor(60, 255, 60, 255) : pct > 0.75 ? new SettingColor(255, 165, 0, 255) : new SettingColor(255, 255, 255, 255);
                stats.add(new Stat(showText ? "Mined: " : "", currentCount + "/" + targetCount, new ItemStack(baromine.getTargetBlock().asItem()), col, pct));
            }
            if (showToolDurability.get()) {
                double dur = baromine.getMainHandDurabilityPercent();
                SettingColor col = dur > 50 ? valueColor.get() : dur > 20 ? new SettingColor(255, 165, 0, 255) : new SettingColor(255, 40, 40, 255);
                stats.add(new Stat(showText ? "Tool: " : "", String.format("%.0f%%", dur), new ItemStack(Items.DIAMOND_PICKAXE), col, 0));
            }
            if (showRuntime.get()) {
                long elapsed = System.currentTimeMillis() - baromine.getSessionStartTime();
                long secs = (elapsed / 1000) % 60;
                long mins = (elapsed / (1000 * 60)) % 60;
                long hours = (elapsed / (1000 * 60 * 60)) % 24;
                stats.add(new Stat(showText ? "Runtime: " : "", String.format("%02dh %02dm %02ds", hours, mins, secs), new ItemStack(Items.CLOCK), valueColor.get(), 0));
            }
        }

        if (showEnderChestCount.get()) {
            int count = countItem(Items.ENDER_CHEST);
            if (!hideEnderIfZero.get() || count > 0 || isInEditor()) {
                SettingColor col = valueColor.get();
                if (count <= enderCriticalThreshold.get()) col = enderCriticalColor.get();
                else if (count <= enderWarningThreshold.get()) col = enderWarningColor.get();
                stats.add(new Stat(showText ? "Ender Chests: " : "", String.valueOf(count), new ItemStack(Items.ENDER_CHEST), col, 0));
            }
        }

        if (showShulkerCount.get()) {
            int count = countShulkers();
            if (!hideShulkerIfZero.get() || count > 0 || isInEditor()) {
                SettingColor col = valueColor.get();
                if (count <= shulkerCriticalThreshold.get()) col = shulkerCriticalColor.get();
                else if (count <= shulkerWarningThreshold.get()) col = shulkerWarningColor.get();
                stats.add(new Stat(showText ? "Shulkers: " : "", String.valueOf(count), new ItemStack(Items.SHULKER_BOX), col, 0));
            }
        }

        if (stats.isEmpty()) {
            if (isInEditor()) {
                double lh = renderer.textHeight(false, s);
                setSize(renderer.textWidth("Mined: 0/64", false, s) + padH * 2, lh + padV * 2);
                renderer.text("Mined: 0/64", x + padH, y + padV, Color.GRAY, false, s);
            } else {
                setSize(0, 0);
            }
            return;
        }

        double maxTextW = 0;
        double maxSegW = 0;
        for (Stat st : stats) {
            double textW = renderer.textWidth(st.label, false, s) + renderer.textWidth(st.value, false, s);
            maxTextW = Math.max(maxTextW, textW);
            double segW = textW;
            if (showIcon && !iconVertical) segW += iconSz + iconGap;
            maxSegW = Math.max(maxSegW, segW);
        }

        double contentW = showIcon && iconVertical ? Math.max(iconSz, maxTextW) : maxSegW;
        if (showIcon && !showText) contentW = Math.max(contentW, iconSz);
        double totalW = contentW + padH * 2;

        double contentH = stats.size() * (statRowH + rowGap) - rowGap;

        BarPosition bp          = barPosition.get();
        boolean     barVertical = bp == BarPosition.Left || bp == BarPosition.Right;
        double      barSize     = 3 * s;
        double      barGap      = 3 * s;
        double      totalH      = contentH + padV * 2;

        if (showBar.get()) {
            if (barVertical) totalW += barSize + barGap;
            else             totalH += barSize + barGap;
        }

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

        for (Stat st : stats) {
            double textW = renderer.textWidth(st.label, false, s) + renderer.textWidth(st.value, false, s);
            double lineW = textW;
            if (showIcon && !iconVertical) lineW += iconSz + iconGap;
            
            drawStatRow(renderer, s, curX, curY, contentRowW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, lineW, textW,
                showIcon ? st.icon : ItemStack.EMPTY, iconSz, iconGap, iconPos,
                st.label, st.value, labelColor.get(), st.valColor);
            curY += statRowH + rowGap;
        }

        if (showBar.get()) {
            double barVal = 0;
            for (Stat st : stats) {
                if (st.progress > 0) {
                    barVal = st.progress;
                    break;
                }
            }

            SettingColor bCol = barVal >= 1.0
                ? new SettingColor(60, 255, 60, 255)
                : barVal > 0.75 ? new SettingColor(255, 165, 0, 255) : new SettingColor(255, 255, 255, 255);

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

    private int countItem(Item item) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(item)) count += s.getCount();
        }
        if (mc.player.getOffHandStack().isOf(item)) count += mc.player.getOffHandStack().getCount();
        return count;
    }

    private int countShulkers() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() instanceof net.minecraft.item.BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                count += s.getCount();
            }
        }
        if (mc.player.getOffHandStack().getItem() instanceof net.minecraft.item.BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
            count += mc.player.getOffHandStack().getCount();
        }
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