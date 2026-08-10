package com.example.addon.hud;

import com.example.addon.Tim;
import com.example.addon.modules.LootLens;

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
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class LootLensHud extends HudElement {

    public static final HudElementInfo<LootLensHud> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "loot-lens",
        "Shows nearby double chests, shulker boxes, and ender chests from Loot Lens.",
        LootLensHud::new
    );

    private final SettingGroup sgGeneral     = settings.getDefaultGroup();
    private final SettingGroup sgChestWarn   = settings.createGroup("Chest Warnings");
    private final SettingGroup sgShulkerWarn = settings.createGroup("Shulker Warnings");
    private final SettingGroup sgEnderWarn   = settings.createGroup("Ender Chest Warnings");

    // ── Layout ────────────────────────────────────────────────────────────────

    public enum Layout { Inline, Stacked, StackedIcons }

    private final Setting<Layout> layout = sgGeneral.add(new EnumSetting.Builder<Layout>()
        .name("layout")
        .description("Inline: single line with separators. Stacked: one row per type. StackedIcons: stacked with item icons.")
        .defaultValue(Layout.Inline)
        .build()
    );

    // ── Feature toggles ───────────────────────────────────────────────────────

    private final Setting<Boolean> showDoubleChests = sgGeneral.add(new BoolSetting.Builder()
        .name("show-chests")
        .description("Show the double chest count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-shulkers")
        .description("Show the shulker box count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showEnderChests = sgGeneral.add(new BoolSetting.Builder()
        .name("show-ender-chests")
        .description("Show the ender chest count.")
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
        .visible(() -> layout.get() == Layout.StackedIcons)
        .build()
    );

    public enum IconPosition { Left, Right, Above, Below }

    private final Setting<IconPosition> iconPosition = sgGeneral.add(new EnumSetting.Builder<IconPosition>()
        .name("icon-position")
        .description("Where the icon appears relative to the text.")
        .defaultValue(IconPosition.Left)
        .visible(() -> layout.get() == Layout.StackedIcons && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("Scale of the item icons.")
        .defaultValue(1.5)
        .min(0.5)
        .sliderRange(0.5, 4.0)
        .visible(() -> layout.get() == Layout.StackedIcons && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconGapSetting = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between icon and text.")
        .defaultValue(4.0)
        .min(0)
        .sliderRange(0, 16)
        .visible(() -> layout.get() == Layout.StackedIcons && labelMode.get() != LabelMode.Text)
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
        .description("Color for values at zero count.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("separator-color")
        .description("Color of the | separators.")
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

    // ── Chest warnings ────────────────────────────────────────────────────────

    private final Setting<Integer> chestWarningThreshold = sgChestWarn.add(new IntSetting.Builder()
        .name("warning-threshold")
        .description("Chest count to trigger warning color.")
        .defaultValue(3).min(0).sliderRange(0, 64).build()
    );

    private final Setting<SettingColor> chestWarningColor = sgChestWarn.add(new ColorSetting.Builder()
        .name("warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255)).build()
    );

    private final Setting<Integer> chestCriticalThreshold = sgChestWarn.add(new IntSetting.Builder()
        .name("critical-threshold")
        .description("Chest count to trigger critical color.")
        .defaultValue(6).min(0).sliderRange(0, 64).build()
    );

    private final Setting<SettingColor> chestCriticalColor = sgChestWarn.add(new ColorSetting.Builder()
        .name("critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255)).build()
    );

    // ── Shulker warnings ──────────────────────────────────────────────────────

    private final Setting<Integer> shulkerWarningThreshold = sgShulkerWarn.add(new IntSetting.Builder()
        .name("warning-threshold")
        .description("Shulker count to trigger warning color.")
        .defaultValue(2).min(0).sliderRange(0, 64).build()
    );

    private final Setting<SettingColor> shulkerWarningColor = sgShulkerWarn.add(new ColorSetting.Builder()
        .name("warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255)).build()
    );

    private final Setting<Integer> shulkerCriticalThreshold = sgShulkerWarn.add(new IntSetting.Builder()
        .name("critical-threshold")
        .description("Shulker count to trigger critical color.")
        .defaultValue(4).min(0).sliderRange(0, 64).build()
    );

    private final Setting<SettingColor> shulkerCriticalColor = sgShulkerWarn.add(new ColorSetting.Builder()
        .name("critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255)).build()
    );

    // ── Ender chest warnings ──────────────────────────────────────────────────

    private final Setting<Integer> enderWarningThreshold = sgEnderWarn.add(new IntSetting.Builder()
        .name("warning-threshold")
        .description("Ender chest count to trigger warning color.")
        .defaultValue(2).min(0).sliderRange(0, 64).build()
    );

    private final Setting<SettingColor> enderWarningColor = sgEnderWarn.add(new ColorSetting.Builder()
        .name("warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255)).build()
    );

    private final Setting<Integer> enderCriticalThreshold = sgEnderWarn.add(new IntSetting.Builder()
        .name("critical-threshold")
        .description("Ender chest count to trigger critical color.")
        .defaultValue(4).min(0).sliderRange(0, 64).build()
    );

    private final Setting<SettingColor> enderCriticalColor = sgEnderWarn.add(new ColorSetting.Builder()
        .name("critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255)).build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public LootLensHud() { super(INFO); }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        LootLens module = Modules.get().get(LootLens.class);
        if (module == null || !module.isActive()) { setSize(0, 0); return; }

        switch (layout.get()) {
            case Inline       -> renderInline(renderer, module);
            case Stacked      -> renderStacked(renderer, module, false);
            case StackedIcons -> renderStacked(renderer, module, true);
        }
    }

    // ── Inline layout ─────────────────────────────────────────────────────────

    private void renderInline(HudRenderer renderer, LootLens module) {
        double s = scale.get();

        java.util.List<String[]> segments = new java.util.ArrayList<>();
        if (showDoubleChests.get()) segments.add(new String[]{"Chests: ",       String.valueOf(module.getDoubleChestCount()), "chest"});
        if (showShulkers.get())     segments.add(new String[]{"Shulkers: ",     String.valueOf(module.getShulkerBoxCount()),  "shulker"});
        if (showEnderChests.get())  segments.add(new String[]{"Ender Chests: ", String.valueOf(module.getEnderChestCount()),  "ender"});
        if (segments.isEmpty()) { setSize(0, 0); return; }

        double padH       = 4 * s;
        double padV       = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double sepW       = renderer.textWidth(" | ", false, s);

        double totalTextW = 0;
        for (int i = 0; i < segments.size(); i++) {
            totalTextW += renderer.textWidth(segments.get(i)[0], false, s);
            totalTextW += renderer.textWidth(segments.get(i)[1], false, s);
            if (i < segments.size() - 1) totalTextW += sepW;
        }

        double totalW = totalTextW + padH * 2;
        double totalH = lineHeight + padV * 2;

        if (showBackground.get())
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());

        double drawX = x + padH;
        double drawY = y + padV;

        for (int i = 0; i < segments.size(); i++) {
            String label = segments.get(i)[0];
            String value = segments.get(i)[1];
            String type  = segments.get(i)[2];

            renderer.text(label, drawX, drawY, labelColor.get(), false, s);
            drawX += renderer.textWidth(label, false, s);
            renderer.text(value, drawX, drawY, resolveColor(type, Integer.parseInt(value)), false, s);
            drawX += renderer.textWidth(value, false, s);

            if (i < segments.size() - 1) {
                renderer.text(" | ", drawX, drawY, separatorColor.get(), false, s);
                drawX += sepW;
            }
        }

        setSize(totalW, totalH);
    }

    // ── Stacked layout (with optional icons) ─────────────────────────────────

    private void renderStacked(HudRenderer renderer, LootLens module, boolean withIcons) {
        double s          = scale.get();
        double padH       = 4 * s;
        double padV       = 2 * s;
        double rowGap     = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double iconSz     = withIcons ? 16.0 * iconScale.get() : 0;
        double iconGap    = withIcons ? iconGapSetting.get() * s : 0;

        LabelMode    mode         = withIcons ? labelMode.get() : LabelMode.Text;
        IconPosition iconPos      = withIcons ? iconPosition.get() : IconPosition.Left;
        boolean      showIcon     = withIcons && mode != LabelMode.Text;
        boolean      showText     = mode != LabelMode.Icon;
        boolean      iconVertical = showIcon && (iconPos == IconPosition.Above || iconPos == IconPosition.Below);
        double       effIconGap   = (showIcon && showText) ? iconGap : 0;

        double statRowH;
        if (!showIcon) {
            statRowH = lineHeight;
        } else if (iconVertical) {
            statRowH = iconSz + iconGap + lineHeight;
        } else {
            statRowH = Math.max(lineHeight, iconSz);
        }

        // ── Gather data ───────────────────────────────────────────────────────

        String       chestLabel = null, chestValue = null;
        SettingColor chestColor = valueColor.get();
        ItemStack    chestIcon  = ItemStack.EMPTY;
        if (showDoubleChests.get()) {
            int count  = module.getDoubleChestCount();
            chestLabel = showText ? "Chests: " : "";
            chestValue = String.valueOf(count);
            chestIcon  = showIcon ? new ItemStack(Items.CHEST) : ItemStack.EMPTY;
            chestColor = resolveColor("chest", count);
        }

        String       shulkerLabel = null, shulkerValue = null;
        SettingColor shulkerColor = valueColor.get();
        ItemStack    shulkerIcon  = ItemStack.EMPTY;
        if (showShulkers.get()) {
            int count    = module.getShulkerBoxCount();
            shulkerLabel = showText ? "Shulkers: " : "";
            shulkerValue = String.valueOf(count);
            shulkerIcon  = showIcon ? new ItemStack(Items.SHULKER_BOX) : ItemStack.EMPTY;
            shulkerColor = resolveColor("shulker", count);
        }

        String       enderLabel = null, enderValue = null;
        SettingColor enderColor = valueColor.get();
        ItemStack    enderIcon  = ItemStack.EMPTY;
        if (showEnderChests.get()) {
            int count  = module.getEnderChestCount();
            enderLabel = showText ? "Ender Chests: " : "";
            enderValue = String.valueOf(count);
            enderIcon  = showIcon ? new ItemStack(Items.ENDER_CHEST) : ItemStack.EMPTY;
            enderColor = resolveColor("ender", count);
        }

        boolean hasChest   = chestLabel   != null;
        boolean hasShulker = shulkerLabel != null;
        boolean hasEnder   = enderLabel   != null;
        if (!hasChest && !hasShulker && !hasEnder) { setSize(0, 0); return; }

        // ── Measure widths ────────────────────────────────────────────────────

        double chestTextW   = hasChest   ? renderer.textWidth(chestLabel,   false, s) + renderer.textWidth(chestValue,   false, s) : 0;
        double shulkerTextW = hasShulker ? renderer.textWidth(shulkerLabel, false, s) + renderer.textWidth(shulkerValue, false, s) : 0;
        double enderTextW   = hasEnder   ? renderer.textWidth(enderLabel,   false, s) + renderer.textWidth(enderValue,   false, s) : 0;

        double chestW, shulkerW, enderW;
        if (!showIcon || iconVertical) {
            chestW   = hasChest   ? (showIcon && !chestIcon.isEmpty()   ? Math.max(iconSz, chestTextW)   : chestTextW)   : 0;
            shulkerW = hasShulker ? (showIcon && !shulkerIcon.isEmpty() ? Math.max(iconSz, shulkerTextW) : shulkerTextW) : 0;
            enderW   = hasEnder   ? (showIcon && !enderIcon.isEmpty()   ? Math.max(iconSz, enderTextW)   : enderTextW)   : 0;
        } else {
            double chestIconW   = (showIcon && !chestIcon.isEmpty())   ? iconSz + effIconGap : 0;
            double shulkerIconW = (showIcon && !shulkerIcon.isEmpty()) ? iconSz + effIconGap : 0;
            double enderIconW   = (showIcon && !enderIcon.isEmpty())   ? iconSz + effIconGap : 0;
            chestW   = hasChest   ? chestIconW   + chestTextW   : 0;
            shulkerW = hasShulker ? shulkerIconW + shulkerTextW : 0;
            enderW   = hasEnder   ? enderIconW   + enderTextW   : 0;
        }

        double contentW = Math.max(chestW, Math.max(shulkerW, enderW));
        if (showIcon && !showText) contentW = Math.max(contentW, iconSz);
        double totalW = contentW + padH * 2;

        double totalH = padV;
        if (hasChest)   totalH += statRowH + rowGap;
        if (hasShulker) totalH += statRowH + rowGap;
        if (hasEnder)   totalH += statRowH + rowGap;
        totalH -= rowGap;
        totalH += padV;

        // ── Draw ──────────────────────────────────────────────────────────────

        Alignment align       = alignment.get();
        boolean   rightAlign  = align == Alignment.Right;
        boolean   centerAlign = align == Alignment.Center;
        double    curY        = y + padV;

        if (hasChest) {
            drawStatRow(renderer, s, x, curY, totalW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, chestW, chestTextW,
                chestIcon, iconSz, effIconGap, iconPos,
                chestLabel, chestValue, labelColor.get(), chestColor);
            curY += statRowH + rowGap;
        }
        if (hasShulker) {
            drawStatRow(renderer, s, x, curY, totalW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, shulkerW, shulkerTextW,
                shulkerIcon, iconSz, effIconGap, iconPos,
                shulkerLabel, shulkerValue, labelColor.get(), shulkerColor);
            curY += statRowH + rowGap;
        }
        if (hasEnder) {
            drawStatRow(renderer, s, x, curY, totalW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, enderW, enderTextW,
                enderIcon, iconSz, effIconGap, iconPos,
                enderLabel, enderValue, labelColor.get(), enderColor);
        }

        setSize(totalW, totalH);
    }

    // ── Resolve color by rising count ─────────────────────────────────────────

    private SettingColor resolveColor(String type, int count) {
        return switch (type) {
            case "chest" -> {
                if (count >= chestCriticalThreshold.get())  yield chestCriticalColor.get();
                if (count >= chestWarningThreshold.get())   yield chestWarningColor.get();
                yield valueColor.get();
            }
            case "shulker" -> {
                if (count >= shulkerCriticalThreshold.get()) yield shulkerCriticalColor.get();
                if (count >= shulkerWarningThreshold.get())  yield shulkerWarningColor.get();
                yield valueColor.get();
            }
            case "ender" -> {
                if (count >= enderCriticalThreshold.get())  yield enderCriticalColor.get();
                if (count >= enderWarningThreshold.get())   yield enderWarningColor.get();
                yield valueColor.get();
            }
            default -> valueColor.get();
        };
    }

    // ── Draw a stat row with configurable icon position ───────────────────────

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