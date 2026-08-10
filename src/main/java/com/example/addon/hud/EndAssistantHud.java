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

import java.util.ArrayList;
import java.util.List;

public class EndAssistantHud extends HudElement {

    public static final HudElementInfo<EndAssistantHud> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "end-assistant",
        "Displays End Assistant stats like nearby elytras, shulkers, and chests.",
        EndAssistantHud::new
    );

    // ── Data Models required by Gatekeeper ─────────────────────────────────────
    public record EndStat(String label, int value, ItemStack icon, StatSeverity severity) {}
    public enum StatSeverity { Normal, Warning, Critical }

    // ── Settings ──────────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilters = settings.createGroup("Filters");

    public enum Layout { Inline, Stacked, StackedIcons }

    private final Setting<Layout> layout = sgGeneral.add(new EnumSetting.Builder<Layout>()
        .name("layout")
        .description("Inline: single line with separator. Stacked: one row per stat. StackedIcons: stacked with item icons.")
        .defaultValue(Layout.Inline)
        .build()
    );

    private final Setting<Boolean> hideEmptyStats = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-empty")
        .description("Hides stats that have a value of 0. Disabled while in the HUD Editor.")
        .defaultValue(true)
        .build()
    );

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

    private final Setting<SettingColor> normalColor = sgGeneral.add(new ColorSetting.Builder()
        .name("normal-color")
        .description("Color for normal severity values.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> warningColor = sgGeneral.add(new ColorSetting.Builder()
        .name("warning-color")
        .description("Color for warning severity values.")
        .defaultValue(new SettingColor(255, 255, 0, 255))
        .build()
    );

    private final Setting<SettingColor> criticalColor = sgGeneral.add(new ColorSetting.Builder()
        .name("critical-color")
        .description("Color for critical severity values.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
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

    // ── Filter Settings ───────────────────────────────────────────────────────

    private final Setting<Boolean> showElytrasFound = sgFilters.add(new BoolSetting.Builder()
        .name("elytras-found")
        .description("Show the total elytras found this session.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showElytrasNearby = sgFilters.add(new BoolSetting.Builder()
        .name("elytras-nearby")
        .description("Show the elytras currently nearby.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showChestsNearby = sgFilters.add(new BoolSetting.Builder()
        .name("chests-nearby")
        .description("Show the chests currently nearby.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showShulkers = sgFilters.add(new BoolSetting.Builder()
        .name("shulkers")
        .description("Show the shulkers currently nearby.")
        .defaultValue(true)
        .build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public EndAssistantHud() { super(INFO); }

    private SettingColor getColorForSeverity(StatSeverity severity) {
        return switch (severity) {
            case Normal -> normalColor.get();
            case Warning -> warningColor.get();
            case Critical -> criticalColor.get();
        };
    }

    private boolean shouldShowStat(String label) {
        return switch (label) {
            case "Elytras Found" -> showElytrasFound.get();
            case "Elytras Nearby" -> showElytrasNearby.get();
            case "Chests Nearby" -> showChestsNearby.get();
            case "Shulkers" -> showShulkers.get();
            default -> true;
        };
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        boolean inEditor = isInEditor();
        Gatekeeper tracker = Modules.get().get(Gatekeeper.class);
        
        if (tracker == null || !tracker.isActive()) {
            if (!inEditor) { 
                setSize(0, 0); 
                return; 
            }
        }

        List<EndStat> rawStats = tracker != null ? tracker.getEndAssistantStats() : new ArrayList<>();
        List<EndStat> stats = new ArrayList<>();

        // Filter stats based on user toggles
        for (EndStat es : rawStats) {
            if (shouldShowStat(es.label())) {
                stats.add(es);
            }
        }
        
        // Provide dummy stats in the editor so the HUD can be moved around
        if (inEditor && stats.isEmpty()) {
            if (showElytrasFound.get()) stats.add(new EndStat("Elytras Found", 0, new ItemStack(net.minecraft.item.Items.ELYTRA), StatSeverity.Normal));
            if (showElytrasNearby.get()) stats.add(new EndStat("Elytras Nearby", 0, new ItemStack(net.minecraft.item.Items.ELYTRA), StatSeverity.Normal));
            if (showChestsNearby.get()) stats.add(new EndStat("Chests Nearby", 0, new ItemStack(net.minecraft.item.Items.CHEST), StatSeverity.Normal));
            if (showShulkers.get()) stats.add(new EndStat("Shulkers", 0, new ItemStack(net.minecraft.item.Items.SHULKER_SHELL), StatSeverity.Normal));
        }

        switch (layout.get()) {
            case Inline       -> renderInline(renderer, stats, inEditor);
            case Stacked      -> renderStacked(renderer, stats, false, inEditor);
            case StackedIcons -> renderStacked(renderer, stats, true, inEditor);
        }
    }

    // ── Inline layout ─────────────────────────────────────────────────────────

    private void renderInline(HudRenderer renderer, List<EndStat> stats, boolean inEditor) {
        double s          = scale.get();
        double padH       = 4 * s;
        double padV       = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double sepW       = renderer.textWidth(" | ", false, s);
        double iconSz     = 16.0 * iconScale.get();
        double iconGap    = iconGapSetting.get() * s;

        LabelMode mode = labelMode.get();
        boolean showIcon = mode != LabelMode.Text;
        boolean showLabel = mode != LabelMode.Icon;
        IconPosition iconPos = iconPosition.get();
        double effIconGap = showIcon ? iconGap : 0;

        record Stat(String label, String value, ItemStack icon, StatSeverity severity) {}
        java.util.List<Stat> segments = new java.util.ArrayList<>();
        for (EndStat es : stats) {
            // Bypass hide-empty while in the HUD editor
            if (!inEditor && hideEmptyStats.get() && es.value() == 0) continue;
            segments.add(new Stat(es.label() + ": ", String.valueOf(es.value()), es.icon(), es.severity()));
        }
        if (segments.isEmpty()) { setSize(0, 0); return; }

        double totalW = 0;
        double rowH = showIcon ? Math.max(lineHeight, iconSz) : lineHeight;

        for (int i = 0; i < segments.size(); i++) {
            Stat st = segments.get(i);
            double segmentW = 0;
            if (showLabel) segmentW += renderer.textWidth(st.label(), false, s);
            segmentW += renderer.textWidth(st.value(), false, s);
            if (showIcon) segmentW += iconSz + effIconGap;
            totalW += segmentW;
            if (i < segments.size() - 1) totalW += sepW;
        }

        setSize(totalW + padH * 2, rowH + padV * 2);

        if (showBackground.get())
            renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        double cx   = x + padH;
        double rowY = y + padV;

        for (int i = 0; i < segments.size(); i++) {
            Stat st = segments.get(i);
            SettingColor valColor = getColorForSeverity(st.severity());

            if (showIcon && iconPos == IconPosition.Left) {
                renderer.item(st.icon(), (int) cx, (int) (rowY + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz + effIconGap;
            }
            if (showLabel) {
                renderer.text(st.label(), cx, rowY + (rowH - lineHeight) / 2.0, labelColor.get(), false, s);
                cx += renderer.textWidth(st.label(), false, s);
            }
            renderer.text(st.value(), cx, rowY + (rowH - lineHeight) / 2.0, valColor, false, s);
            cx += renderer.textWidth(st.value(), false, s);

            if (showIcon && iconPos != IconPosition.Left) {
                cx += effIconGap;
                renderer.item(st.icon(), (int) cx, (int) (rowY + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz;
            }
            if (i < segments.size() - 1) {
                renderer.text(" | ", cx, rowY + (rowH - lineHeight) / 2.0, separatorColor.get(), false, s);
                cx += sepW;
            }
        }
    }

    // ── Stacked layout (with optional icons) ─────────────────────────────────

    private void renderStacked(HudRenderer renderer, List<EndStat> stats, boolean withIcons, boolean inEditor) {
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
        double       effectiveIconGap = (showIcon && showText) ? iconGap : 0;

        double statRowH;
        if (!showIcon) {
            statRowH = lineHeight;
        } else if (iconVertical) {
            statRowH = iconSz + iconGap + lineHeight;
        } else {
            statRowH = Math.max(lineHeight, iconSz);
        }

        record Stat(String label, String value, ItemStack icon, StatSeverity severity) {}
        java.util.List<Stat> segments = new java.util.ArrayList<>();
        for (EndStat es : stats) {
            // Bypass hide-empty while in the HUD editor
            if (!inEditor && hideEmptyStats.get() && es.value() == 0) continue;
            segments.add(new Stat(showText ? es.label() + ": " : "", String.valueOf(es.value()), showIcon ? es.icon() : ItemStack.EMPTY, es.severity()));
        }
        if (segments.isEmpty()) { setSize(0, 0); return; }

        double maxTextW = 0;
        double maxIconW = 0;

        for (Stat st : segments) {
            double textW = renderer.textWidth(st.label(), false, s) + renderer.textWidth(st.value(), false, s);
            maxTextW = Math.max(maxTextW, textW);
            if (showIcon && !st.icon().isEmpty()) {
                maxIconW = Math.max(maxIconW, iconSz + effectiveIconGap);
            }
        }

        double contentW;
        if (!showIcon || iconVertical) {
            contentW = Math.max(maxTextW, showIcon ? iconSz : 0);
        } else {
            contentW = maxTextW + maxIconW;
        }
        if (showIcon && !showText) contentW = Math.max(contentW, iconSz);
        double totalW = contentW + padH * 2;

        double totalH = padV;
        for (int i = 0; i < segments.size(); i++) {
            totalH += statRowH;
            if (i < segments.size() - 1) totalH += rowGap;
        }
        totalH += padV;

        Alignment align       = alignment.get();
        boolean   rightAlign  = align == Alignment.Right;
        boolean   centerAlign = align == Alignment.Center;
        double    curY        = y + padV;

        for (Stat st : segments) {
            SettingColor valColor = getColorForSeverity(st.severity());
            double textW = renderer.textWidth(st.label(), false, s) + renderer.textWidth(st.value(), false, s);
            double lineW = textW;
            if (showIcon && !iconVertical) {
                lineW += iconSz + effectiveIconGap;
            } else if (showIcon && iconVertical) {
                lineW = Math.max(textW, iconSz);
            }

            drawStatRow(renderer, s, x, curY, totalW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, lineW, textW,
                st.icon(), iconSz, effectiveIconGap, iconPos,
                st.label(), st.value(), labelColor.get(), valColor);
            curY += statRowH + rowGap;
        }

        setSize(totalW, totalH);
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