package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

public class DuraPanelHUD extends HudElement {

    public static final HudElementInfo<DuraPanelHUD> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "dura-panel",
        "Displays durability of equipped armour and held tools with warning/critical colour thresholds.",
        DuraPanelHUD::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // ── Setting Groups ────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgArmour   = settings.createGroup("Armour Slots");
    private final SettingGroup sgTools    = settings.createGroup("Tool Slots");
    private final SettingGroup sgWarnings = settings.createGroup("Warnings");

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum DisplayMode { Vertical, Flat }

    public enum Alignment { Left, Center, Right }

    public enum LabelMode { Text, Icon, Both }

    public enum IconPosition { Left, Right, Above, Below }

    public enum DurabilityFormat { Both, Numbers, Percentage }

    // ═══════════════════════════════════════════════════════════════════════════
    // General Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<DisplayMode> displayMode = sgGeneral.add(new EnumSetting.Builder<DisplayMode>()
        .name("display-mode")
        .description("Vertical lists each slot as a row. Flat shows all icons in a horizontal strip with durability above each.")
        .defaultValue(DisplayMode.Vertical)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0).min(0.25).sliderRange(0.25, 4.0)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align content to the left, center, or right within the element.")
        .defaultValue(Alignment.Left)
        .build()
    );

    // ── Vertical-mode label/icon settings ─────────────────────────────────────

    private final Setting<LabelMode> labelMode = sgGeneral.add(new EnumSetting.Builder<LabelMode>()
        .name("label-mode")
        .description("Show item labels as text, icon, or both. (Vertical mode only)")
        .defaultValue(LabelMode.Both)
        .visible(() -> displayMode.get() == DisplayMode.Vertical)
        .build()
    );

    private final Setting<IconPosition> iconPosition = sgGeneral.add(new EnumSetting.Builder<IconPosition>()
        .name("icon-position")
        .description("Where the item icon appears relative to the text on each row. (Vertical mode only)")
        .defaultValue(IconPosition.Left)
        .visible(() -> displayMode.get() == DisplayMode.Vertical && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("Scale of the item icons.")
        .defaultValue(1.5).min(0.5).sliderRange(0.5, 4.0)
        .visible(() -> displayMode.get() == DisplayMode.Flat
               || (displayMode.get() == DisplayMode.Vertical && labelMode.get() != LabelMode.Text))
        .build()
    );

    private final Setting<Double> iconGapSetting = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between the icon and adjacent text. (Vertical mode only)")
        .defaultValue(4.0).min(0).sliderRange(0, 16)
        .visible(() -> displayMode.get() == DisplayMode.Vertical && labelMode.get() != LabelMode.Text)
        .build()
    );

    // ── Flat-mode specific settings ───────────────────────────────────────────

    private final Setting<Double> flatSlotGap = sgGeneral.add(new DoubleSetting.Builder()
        .name("flat-slot-gap")
        .description("Horizontal gap in pixels between each slot column in Flat mode.")
        .defaultValue(6.0).min(0).sliderRange(0, 32)
        .visible(() -> displayMode.get() == DisplayMode.Flat)
        .build()
    );

    private final Setting<Boolean> flatShowIcon = sgGeneral.add(new BoolSetting.Builder()
        .name("flat-show-icon")
        .description("Show the item icon in each slot column in Flat mode.")
        .defaultValue(true)
        .visible(() -> displayMode.get() == DisplayMode.Flat)
        .build()
    );

    // ── Shared format / colour settings ──────────────────────────────────────

    private final Setting<DurabilityFormat> durabilityFormat = sgGeneral.add(new EnumSetting.Builder<DurabilityFormat>()
        .name("durability-format")
        .description("How to display durability values.")
        .defaultValue(DurabilityFormat.Numbers)
        .build()
    );

    private final Setting<Boolean> hideUnbreakable = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-unbreakable")
        .description("Hide slots whose item has no durability (unbreakable or non-damageable).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideEmpty = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-empty")
        .description("Hide slots that are currently empty.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color for item name labels.")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Color for durability values when healthy.")
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Armour Slot Toggles
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> showHelmet = sgArmour.add(new BoolSetting.Builder()
        .name("show-helmet")
        .description("Track the helmet slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showChestplate = sgArmour.add(new BoolSetting.Builder()
        .name("show-chestplate")
        .description("Track the chestplate slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showLeggings = sgArmour.add(new BoolSetting.Builder()
        .name("show-leggings")
        .description("Track the leggings slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBoots = sgArmour.add(new BoolSetting.Builder()
        .name("show-boots")
        .description("Track the boots slot.")
        .defaultValue(true)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool Slot Toggles
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> showMainhand = sgTools.add(new BoolSetting.Builder()
        .name("show-mainhand")
        .description("Track the mainhand slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showOffhand = sgTools.add(new BoolSetting.Builder()
        .name("show-offhand")
        .description("Track the offhand slot.")
        .defaultValue(true)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Warning / Critical Thresholds
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> warningThreshold = sgWarnings.add(new DoubleSetting.Builder()
        .name("warning-threshold")
        .description("Durability % at which the value turns the warning color.")
        .defaultValue(25.0).min(0).sliderRange(0, 100)
        .build()
    );

    private final Setting<SettingColor> warningColor = sgWarnings.add(new ColorSetting.Builder()
        .name("warning-color")
        .description("Color shown when durability is at or below the warning threshold.")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

    private final Setting<Double> criticalThreshold = sgWarnings.add(new DoubleSetting.Builder()
        .name("critical-threshold")
        .description("Durability % at which the value turns the critical color.")
        .defaultValue(10.0).min(0).sliderRange(0, 100)
        .build()
    );

    private final Setting<SettingColor> criticalColor = sgWarnings.add(new ColorSetting.Builder()
        .name("critical-color")
        .description("Color shown when durability is at or below the critical threshold.")
        .defaultValue(new SettingColor(255, 40, 40, 255))
        .build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public DuraPanelHUD() {
        super(INFO);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal slot descriptor
    // ─────────────────────────────────────────────────────────────────────────

    private record SlotRow(
        ItemStack    stack,
        String       label,
        String       value,
        SettingColor valueCol
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Render entry point
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null) { setSize(0, 0); return; }

        boolean showText = displayMode.get() == DisplayMode.Vertical
            && labelMode.get() != LabelMode.Icon;

        List<SlotRow> rows = new ArrayList<>();
        if (showHelmet.get())     addSlot(rows, mc.player.getEquippedStack(EquipmentSlot.HEAD),  showText);
        if (showChestplate.get()) addSlot(rows, mc.player.getEquippedStack(EquipmentSlot.CHEST), showText);
        if (showLeggings.get())   addSlot(rows, mc.player.getEquippedStack(EquipmentSlot.LEGS),  showText);
        if (showBoots.get())      addSlot(rows, mc.player.getEquippedStack(EquipmentSlot.FEET),  showText);
        if (showMainhand.get())   addSlot(rows, mc.player.getMainHandStack(),                     showText);
        if (showOffhand.get())    addSlot(rows, mc.player.getOffHandStack(),                      showText);

        if (rows.isEmpty()) { setSize(0, 0); return; }

        if (displayMode.get() == DisplayMode.Flat) {
            renderFlat(renderer, rows);
        } else {
            renderVertical(renderer, rows);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FLAT render
    // ─────────────────────────────────────────────────────────────────────────

    private void renderFlat(HudRenderer renderer, List<SlotRow> rows) {
        double s          = scale.get();
        double padH       = 4 * s;
        double padV       = 2 * s;
        double colGap     = flatSlotGap.get() * s;
        double lineHeight = renderer.textHeight(false, s);
        double textIconGap = 2 * s;

        boolean drawIcon = flatShowIcon.get();
        double  iconSz   = drawIcon ? 16.0 * iconScale.get() : 0;

        double[] colW    = new double[rows.size()];
        double[] valW    = new double[rows.size()];

        for (int i = 0; i < rows.size(); i++) {
            double vw = renderer.textWidth(rows.get(i).value(), false, s);
            valW[i]  = vw;
            colW[i]  = drawIcon ? Math.max(vw, iconSz) : vw;
        }

        double totalW = padH * 2;
        for (int i = 0; i < rows.size(); i++) {
            totalW += colW[i];
            if (i < rows.size() - 1) totalW += colGap;
        }

        double totalH = padV * 2 + lineHeight;
        if (drawIcon) totalH += textIconGap + iconSz;

        if (showBackground.get())
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());

        double stripX = x + padH;

        for (int i = 0; i < rows.size(); i++) {
            SlotRow row = rows.get(i);
            double cw   = colW[i];

            double textX = stripX + (cw - valW[i]) / 2.0;
            double textY = y + padV;
            renderer.text(row.value(), textX, textY, row.valueCol(), false, s);

            if (drawIcon && !row.stack().isEmpty()) {
                double iconX = stripX + (cw - iconSz) / 2.0;
                double iconY = textY + lineHeight + textIconGap;
                renderer.item(row.stack(), (int) iconX, (int) iconY, iconScale.get().floatValue(), false);
            }

            stripX += cw + colGap;
        }

        setSize(totalW, totalH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VERTICAL render
    // ─────────────────────────────────────────────────────────────────────────

    private void renderVertical(HudRenderer renderer, List<SlotRow> rows) {
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
        boolean      iconVert = showIcon && (iconPos == IconPosition.Above || iconPos == IconPosition.Below);
        double       effectiveIconGap = (showIcon && showText) ? iconGap : 0;

        double statRowH;
        if (!showIcon) {
            statRowH = lineHeight;
        } else if (iconVert) {
            statRowH = iconSz + effectiveIconGap + lineHeight;
        } else {
            statRowH = Math.max(lineHeight, iconSz);
        }

        // ── Measure ───────────────────────────────────────────────────────────

        double[] rowWidths  = new double[rows.size()];
        double[] textWidths = new double[rows.size()];
        double   maxW       = 0;

        for (int i = 0; i < rows.size(); i++) {
            SlotRow row = rows.get(i);
            double tw   = 0;
            if (showText) {
                tw = renderer.textWidth(row.label(), false, s)
                   + renderer.textWidth(row.value(), false, s);
            }
            textWidths[i] = tw;

            double rw;
            if (!showIcon || iconVert) {
                rw = showIcon && !row.stack().isEmpty() ? Math.max(iconSz, tw) : tw;
            } else {
                double iconContrib = !row.stack().isEmpty() ? iconSz + effectiveIconGap : 0;
                rw = iconContrib + tw;
            }
            rowWidths[i] = rw;
            maxW = Math.max(maxW, rw);
        }

        if (showIcon && !showText) maxW = Math.max(maxW, iconSz);

        double totalW = maxW + padH * 2;
        double totalH = padV * 2
            + rows.size() * statRowH
            + Math.max(0, rows.size() - 1) * rowGap;

        // ── Draw ──────────────────────────────────────────────────────────────

        Alignment align      = alignment.get();
        boolean   rightAlign = align == Alignment.Right;
        boolean   centAlign  = align == Alignment.Center;
        double    curY       = y + padV;

        for (int i = 0; i < rows.size(); i++) {
            SlotRow row = rows.get(i);
            drawVerticalStatRow(renderer, s,
                x, curY, totalW, padH, statRowH, lineHeight,
                rightAlign, centAlign,
                rowWidths[i], textWidths[i],
                showIcon ? row.stack() : ItemStack.EMPTY,
                iconSz, effectiveIconGap, iconPos,
                row.label(), row.value(),
                labelColor.get(), row.valueCol());
            curY += statRowH + rowGap;
        }

        setSize(totalW, totalH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build a SlotRow from an ItemStack, respecting hide settings.
    // ─────────────────────────────────────────────────────────────────────────

    private void addSlot(List<SlotRow> rows, ItemStack stack, boolean showText) {
        if (stack.isEmpty()) {
            if (hideEmpty.get()) return;
            rows.add(new SlotRow(ItemStack.EMPTY, showText ? "Empty: " : "", "—", labelColor.get()));
            return;
        }

        if (!stack.isDamageable()) {
            if (hideUnbreakable.get()) return;
            String label = showText ? stack.getName().getString() + ": " : "";
            rows.add(new SlotRow(stack, label, "∞", valueColor.get()));
            return;
        }

        int    remaining = stack.getMaxDamage() - stack.getDamage();
        int    max       = stack.getMaxDamage();
        double pct       = 100.0 * remaining / max;

        String value = switch (durabilityFormat.get()) {
            case Numbers    -> String.format("%d/%d", remaining, max);
            case Percentage -> String.format("%.0f%%", pct);
            default         -> String.format("%d/%d (%.0f%%)", remaining, max, pct);
        };

        SettingColor col;
        if      (pct <= criticalThreshold.get()) col = criticalColor.get();
        else if (pct <= warningThreshold.get())  col = warningColor.get();
        else                                      col = valueColor.get();

        String label = showText ? stack.getName().getString() + ": " : "";
        rows.add(new SlotRow(stack, label, value, col));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Draw a single vertical stat row
    // ─────────────────────────────────────────────────────────────────────────

    private void drawVerticalStatRow(HudRenderer renderer, double s,
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