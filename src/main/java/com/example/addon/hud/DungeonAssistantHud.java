package com.example.addon.hud;

import com.example.addon.Tim;
import com.example.addon.modules.DungeonAssistant;
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
import java.util.Map;

public class DungeonAssistantHud extends HudElement {
    public static final HudElementInfo<DungeonAssistantHud> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "dungeon-assistant",
        "Displays dungeon element counts.",
        DungeonAssistantHud::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors  = settings.createGroup("Colors");

    // ── Layout ────────────────────────────────────────────────────────────────

    public enum Layout { Inline, Stacked, StackedIcons }

    private final Setting<Layout> layout = sgGeneral.add(new EnumSetting.Builder<Layout>()
        .name("layout")
        .description("How the data is presented.")
        .defaultValue(Layout.StackedIcons)
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
        .defaultValue(Alignment.Left)
        .visible(() -> layout.get() != Layout.Inline)
        .build()
    );

    public enum LabelMode { Text, Icon, Both }

    private final Setting<LabelMode> labelMode = sgGeneral.add(new EnumSetting.Builder<LabelMode>()
        .name("label-mode")
        .defaultValue(LabelMode.Both)
        .visible(() -> layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline)
        .build()
    );

    public enum IconPosition { Left, Right, Above, Below }

    private final Setting<IconPosition> iconPosition = sgGeneral.add(new EnumSetting.Builder<IconPosition>()
        .name("icon-position")
        .defaultValue(IconPosition.Left)
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .defaultValue(1.5).min(0.5).sliderRange(0.5, 4.0)
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
        .build()
    );
    
    private final Setting<Double> iconGapSetting = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between icon and text.")
        .defaultValue(4.0).min(0).sliderRange(0, 16)
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
        .build()
    );

    // ── Feature Toggles ───────────────────────────────────────────────────────

    private final Setting<Boolean> showSpawners = sgGeneral.add(new BoolSetting.Builder().name("show-spawners").defaultValue(true).build());
    private final Setting<Boolean> showChests = sgGeneral.add(new BoolSetting.Builder().name("show-chests").defaultValue(true).build());
    private final Setting<Boolean> showMinecarts = sgGeneral.add(new BoolSetting.Builder().name("show-minecarts").defaultValue(true).build());

    // ── Visuals ───────────────────────────────────────────────────────────────

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgColors.add(new ColorSetting.Builder()
        .name("label-color")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgColors.add(new ColorSetting.Builder()
        .name("value-color")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> spawnerColor = sgColors.add(new ColorSetting.Builder()
        .name("spawner-color")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .build()
    );

    private final Setting<SettingColor> chestColor = sgColors.add(new ColorSetting.Builder()
        .name("chest-color")
        .defaultValue(new SettingColor(255, 200, 0, 255))
        .build()
    );

    private final Setting<SettingColor> minecartColor = sgColors.add(new ColorSetting.Builder()
        .name("minecart-color")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgColors.add(new ColorSetting.Builder()
        .name("separator-color")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .visible(() -> layout.get() == Layout.Inline)
        .build()
    );

    public DungeonAssistantHud() { super(INFO); }

    private record Stat(String label, String value, SettingColor color, ItemStack icon) {}

    @Override
    public void render(HudRenderer renderer) {
        DungeonAssistant module = Modules.get().get(DungeonAssistant.class);
        if (module == null || !module.isActive()) {
            if (isInEditor()) {
                renderDummy(renderer);
            } else {
                setSize(0, 0);
            }
            return;
        }

        Map<DungeonAssistant.TargetType, Integer> counts = module.getTargetCounts();
        List<Stat> stats = new ArrayList<>();

        int spawnerCount = counts.getOrDefault(DungeonAssistant.TargetType.SPAWNER, 0);
        if (showSpawners.get() && spawnerCount > 0) stats.add(new Stat("Spawners: ", String.valueOf(spawnerCount), spawnerColor.get(), new ItemStack(Items.SPAWNER)));
        
        int chestCount = counts.getOrDefault(DungeonAssistant.TargetType.CHEST, 0);
        if (showChests.get() && chestCount > 0) stats.add(new Stat("Chests: ", String.valueOf(chestCount), chestColor.get(), new ItemStack(Items.CHEST)));
        
        int minecartCount = counts.getOrDefault(DungeonAssistant.TargetType.CHEST_MINECART, 0);
        if (showMinecarts.get() && minecartCount > 0) stats.add(new Stat("Minecarts: ", String.valueOf(minecartCount), minecartColor.get(), new ItemStack(Items.CHEST_MINECART)));

        if (stats.isEmpty()) { setSize(0, 0); return; }

        if (layout.get() == Layout.Inline) renderInline(renderer, stats);
        else renderStacked(renderer, stats, layout.get() == Layout.StackedIcons);
    }

    private void renderDummy(HudRenderer renderer) {
        double s = scale.get();
        String text = "Dungeon Assistant: Idle";
        double w = renderer.textWidth(text, false, s) + 8 * s;
        double h = renderer.textHeight(false, s) + 4 * s;
        setSize(w, h);
        if (showBackground.get()) renderer.quad(x, y, w, h, backgroundColor.get());
        renderer.text(text, x + 4 * s, y + 2 * s, labelColor.get(), false, s);
    }

    private void renderInline(HudRenderer renderer, List<Stat> stats) {
        double s = scale.get(), padH = 4 * s, padV = 2 * s, lh = renderer.textHeight(false, s), sepW = renderer.textWidth(" | ", false, s);
        double iconSz = 16.0 * iconScale.get(), iconGap = iconGapSetting.get() * s;
        LabelMode mode = labelMode.get(); boolean showIcon = mode != LabelMode.Text, showLabel = mode != LabelMode.Icon;
        IconPosition iconPos = iconPosition.get(); double effIconGap = showIcon ? iconGap : 0;

        double totalW = 0, rowH = showIcon ? Math.max(lh, iconSz) : lh;
        for (int i = 0; i < stats.size(); i++) {
            Stat st = stats.get(i);
            double segW = 0;
            if (showLabel) segW += renderer.textWidth(st.label(), false, s);
            segW += renderer.textWidth(st.value(), false, s);
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
                renderer.item(st.icon(), (int) cx, (int) (y + padV + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz + effIconGap;
            }
            if (showLabel) {
                renderer.text(st.label(), cx, y + padV + (rowH - lh) / 2.0, labelColor.get(), false, s);
                cx += renderer.textWidth(st.label(), false, s);
            }
            renderer.text(st.value(), cx, y + padV + (rowH - lh) / 2.0, st.color(), false, s);
            cx += renderer.textWidth(st.value(), false, s);

            if (showIcon && iconPos != IconPosition.Left) {
                cx += effIconGap;
                renderer.item(st.icon(), (int) cx, (int) (y + padV + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz;
            }
            if (i < stats.size() - 1) {
                renderer.text(" | ", cx, y + padV + (rowH - lh) / 2.0, separatorColor.get(), false, s);
                cx += sepW;
            }
        }
    }

    private void renderStacked(HudRenderer renderer, List<Stat> stats, boolean withIcons) {
        double s = scale.get();
        double padH = 4 * s, padV = 2 * s, rowGap = 2 * s;
        double lh = renderer.textHeight(false, s);
        double iconSz = withIcons ? 16.0 * iconScale.get() : 0;
        double iconGap = withIcons ? iconGapSetting.get() * s : 0;

        LabelMode mode = withIcons ? labelMode.get() : LabelMode.Text;
        IconPosition iconPos = withIcons ? iconPosition.get() : IconPosition.Left;
        boolean showIcon = withIcons && mode != LabelMode.Text;
        boolean showText = mode != LabelMode.Icon;
        boolean iconVert = showIcon && (iconPos == IconPosition.Above || iconPos == IconPosition.Below);

        double rowH = !showIcon ? lh : iconVert ? iconSz + iconGap + lh : Math.max(lh, iconSz);

        double maxW = 0;
        double[] textW = new double[stats.size()];
        for (int i = 0; i < stats.size(); i++) {
            Stat st = stats.get(i);
            textW[i] = (showText ? renderer.textWidth(st.label(), false, s) : 0) + renderer.textWidth(st.value(), false, s);
            double fullW;
            if (!showIcon || iconVert) fullW = Math.max(iconSz, textW[i]);
            else fullW = iconSz + iconGap + textW[i];
            maxW = Math.max(maxW, fullW);
        }

        double totalW = maxW + padH * 2;
        double totalH = stats.size() * rowH + (stats.size() - 1) * rowGap + padV * 2;

        setSize(totalW, totalH);
        if (showBackground.get()) renderer.quad(x, y, totalW, totalH, backgroundColor.get());

        Alignment align = alignment.get();
        double curY = y + padV;
        for (int i = 0; i < stats.size(); i++) {
            Stat st = stats.get(i);
            drawStatRow(renderer, s, x, curY, totalW, padH, rowH, lh, align, textW[i], st, showIcon, showText, iconSz, iconGap, iconPos);
            curY += rowH + rowGap;
        }
    }

    private void drawStatRow(HudRenderer renderer, double s, double rx, double ry, double totalW, double padH,
                             double rowH, double lh, Alignment align, double textW, Stat st,
                             boolean showIcon, boolean showText, double iconSz, double gap, IconPosition pos) {
        boolean iconVert = showIcon && (pos == IconPosition.Above || pos == IconPosition.Below);
        double rowW = !showIcon || iconVert ? Math.max(iconSz, textW) : iconSz + gap + textW;

        double startX = switch (align) {
            case Right -> rx + totalW - padH - rowW;
            case Center -> rx + (totalW - rowW) / 2.0;
            default -> rx + padH;
        };

        if (!showIcon || !iconVert) {
            double ty = ry + (rowH - lh) / 2.0;
            double iy = ry + (rowH - iconSz) / 2.0;
            double cx = startX;

            if (showIcon && pos == IconPosition.Left) {
                renderer.item(st.icon(), (int) cx, (int) iy, iconScale.get().floatValue(), false);
                cx += iconSz + gap;
            }

            if (showText) {
                renderer.text(st.label(), cx, ty, labelColor.get(), false, s);
                cx += renderer.textWidth(st.label(), false, s);
            }
            renderer.text(st.value(), cx, ty, st.color(), false, s);

            if (showIcon && pos == IconPosition.Right) {
                renderer.item(st.icon(), (int) (startX + textW + gap), (int) iy, iconScale.get().floatValue(), false);
            }
        } else {
            double ty, iy;
            if (pos == IconPosition.Above) {
                iy = ry;
                ty = ry + iconSz + gap;
            } else {
                ty = ry;
                iy = ry + lh + gap;
            }

            double ix = startX + (textW > iconSz ? (textW - iconSz) / 2.0 : 0);
            double tx = startX + (iconSz > textW ? (iconSz - textW) / 2.0 : 0);

            if (showIcon) renderer.item(st.icon(), (int) ix, (int) iy, iconScale.get().floatValue(), false);
            if (showText) {
                renderer.text(st.label(), tx, ty, labelColor.get(), false, s);
                tx += renderer.textWidth(st.label(), false, s);
            }
            renderer.text(st.value(), tx, ty, st.color(), false, s);
        }
    }
}
