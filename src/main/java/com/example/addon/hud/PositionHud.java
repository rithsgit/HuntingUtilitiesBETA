package com.example.addon.hud;


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
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class PositionHud extends HudElement {

    public static final HudElementInfo<PositionHud> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "position",
        "Displays your current coordinates, dimension, biome, and Nether/Overworld equivalents.",
        PositionHud::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ── Visual settings ───────────────────────────────────────────────────────────

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0)
        .min(0.25)
        .sliderRange(0.25, 4.0)
        .build()
    );

    public enum Alignment { Left, Center, Right }

    public enum CoordVisibility {
        Visible,
        Censored,
        Hidden
    }

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align text to the left, center, or right.")
        .defaultValue(Alignment.Left)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("separator-color")
        .description("Color of the | separators.")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .build()
    );

    private final Setting<SettingColor> netherLabelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("nether-label-color")
        .description("Color of the Nether/Overworld coordinate labels.")
        .defaultValue(new SettingColor(200, 80, 80, 255))
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a per-line background highlight.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    // ── Feature toggles ───────────────────────────────────────────────────────────

    private final Setting<CoordVisibility> coordVisibility = sgGeneral.add(new EnumSetting.Builder<CoordVisibility>()
        .name("coord-visibility")
        .description("Controls how coordinates are displayed.")
        .defaultValue(CoordVisibility.Visible)
        .build()
    );

    private final Setting<Boolean> showDimension = sgGeneral.add(new BoolSetting.Builder()
        .name("show-dimension")
        .description("Show the current dimension (Overworld, Nether, End).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBiome = sgGeneral.add(new BoolSetting.Builder()
        .name("show-biome")
        .description("Show the biome you are currently standing in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showNether = sgGeneral.add(new BoolSetting.Builder()
        .name("show-nether-coords")
        .description("Show the Nether/Overworld equivalent coordinates on a second line.")
        .defaultValue(true)
        .build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────────

    public PositionHud() {
        super(INFO);
    }

    // ── Render ────────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null) { setSize(0, 0); return; }

        double s          = scale.get();
        double padH       = 4 * s;
        double padV       = 2 * s;
        double rowGap     = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double sepW       = renderer.textWidth(" | ", false, s);
        Alignment align = alignment.get();
        boolean rightAlign  = align == Alignment.Right;
        boolean centerAlign = align == Alignment.Center;

        BlockPos pos = mc.player.getBlockPos();
        int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();

        // ── Dimension ─────────────────────────────────────────────────────────────
        boolean inNether = mc.world != null && mc.world.getRegistryKey() == World.NETHER;
        boolean inEnd    = mc.world != null && mc.world.getRegistryKey() == World.END;

        String dimLabel = null, dimValue = null;
        if (showDimension.get() && mc.world != null) {
            dimLabel = "Dim: ";
            dimValue = inNether ? "Nether" : inEnd ? "End" : "Overworld";
        }

        // ── Biome ─────────────────────────────────────────────────────────────────
        String biomeLabel = null, biomeValue = null;
        if (showBiome.get() && mc.world != null) {
            biomeLabel = "Biome: ";
            biomeValue = mc.world.getBiome(pos).getKey()
                .map(RegistryKey::getValue)
                .map(id -> {
                    // Convert "minecraft:dark_forest" → "Dark Forest"
                    String[] parts = id.getPath().split("_");
                    StringBuilder sb = new StringBuilder();
                    for (String p : parts) {
                        if (!sb.isEmpty()) sb.append(' ');
                        sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
                    }
                    return sb.toString();
                })
                .orElse("Unknown");
        }

        // ── Coords ────────────────────────────────────────────────────────────────
        boolean coordsVisible = coordVisibility.get() != CoordVisibility.Hidden;
        boolean censored = coordVisibility.get() == CoordVisibility.Censored;
        String xLabel = "X: ", xVal = censored ? "XXXX" : String.valueOf(bx);
        String yLabel = "Y: ", yVal = censored ? "XXXX" : String.valueOf(by);
        String zLabel = "Z: ", zVal = censored ? "XXXX" : String.valueOf(bz);

        // ── Nether / OW equivalent ────────────────────────────────────────────────
        String netherLineLabel = null;
        String nxVal = null, nzVal = null;
        boolean hasLineNether = showNether.get() && !inEnd;

        if (hasLineNether) {
            if (inNether) {
                // Nether → Overworld: multiply by 8
                netherLineLabel = "OW: ";
                nxVal = String.valueOf(bx * 8);
                nzVal = censored ? "XXXX" : String.valueOf(bz * 8);
            } else {
                // Overworld → Nether: floor-divide to handle negatives correctly
                netherLineLabel = "Nether: ";
                nxVal = censored ? "XXXX" : String.valueOf((int) Math.floor(bx / 8.0));
                nzVal = censored ? "XXXX" : String.valueOf((int) Math.floor(bz / 8.0));
            }
        }

        // ── In the End: show a note instead of hiding the line silently ───────────
        String endNote = null;
        if (showNether.get() && inEnd) {
            endNote = "No portal conversion in The End";
        }

        // ── Measure widths ────────────────────────────────────────────────────────
        String nxLabel = "X: ", nzLabel = "Z: ";

        double dimW = dimLabel != null
            ? renderer.textWidth(dimLabel, false, s) + renderer.textWidth(dimValue, false, s) : 0;

        double biomeW = biomeLabel != null
            ? renderer.textWidth(biomeLabel, false, s) + renderer.textWidth(biomeValue, false, s) : 0;

        double coordW = renderer.textWidth(xLabel, false, s) + renderer.textWidth(xVal, false, s)
                      + sepW
                      + renderer.textWidth(yLabel, false, s) + renderer.textWidth(yVal, false, s)
                      + sepW
                      + renderer.textWidth(zLabel, false, s) + renderer.textWidth(zVal, false, s);

        double netherW = hasLineNether
            ? renderer.textWidth(netherLineLabel, false, s)
              + renderer.textWidth(nxLabel, false, s) + renderer.textWidth(nxVal, false, s)
              + sepW
              + renderer.textWidth(nzLabel, false, s) + renderer.textWidth(nzVal, false, s)
            : 0;

        double endNoteW = endNote != null
            ? renderer.textWidth(endNote, false, s) : 0;

        boolean hasCoords   = coordsVisible;
        boolean hasDim      = dimLabel    != null;
        boolean hasBiome    = biomeLabel  != null;
        boolean hasEndNote  = endNote     != null;

        double maxW   = Math.max(coordW, Math.max(dimW, Math.max(biomeW, Math.max(netherW, endNoteW))));
        double totalW = maxW + padH * 2;

        int lineCount = (hasCoords ? 1 : 0)
            + (hasDim     ? 1 : 0)
            + (hasBiome   ? 1 : 0)
            + (hasLineNether ? 1 : 0)
            + (hasEndNote ? 1 : 0);

        double totalH = lineCount * lineHeight + (lineCount - 1) * rowGap + padV * 2;

        int lineIdx = 0;

        // ── Draw: Dimension ───────────────────────────────────────────────────────
        if (hasDim) {
            lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
                rightAlign, centerAlign, totalW, dimW, lineIdx,
                dimLabel, dimValue, labelColor.get(), valueColor.get());
        }

        // ── Draw: Biome ───────────────────────────────────────────────────────────
        if (hasBiome) {
            lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
                rightAlign, centerAlign, totalW, biomeW, lineIdx,
                biomeLabel, biomeValue, labelColor.get(), valueColor.get());
        }

        // ── Draw: Coords (X | Y | Z) ──────────────────────────────────────────────
        if (hasCoords) {
            double rowY     = y + padV + lineIdx * (lineHeight + rowGap);
            double lineBoxW = coordW + padH * 2;
            if (showBackground.get()) {
                double bgX = rightAlign ? x + totalW - lineBoxW : centerAlign ? x + (totalW - lineBoxW) / 2.0 : x;
                renderer.quad(bgX, rowY - 1, lineBoxW, lineHeight + 2, backgroundColor.get());
            }
            if (rightAlign) {
                double cx = x + totalW - padH;
                double zw = renderer.textWidth(zVal,   false, s); cx -= zw; renderer.text(zVal,   cx, rowY, valueColor.get(),     false, s);
                double zlw= renderer.textWidth(zLabel, false, s); cx -= zlw;renderer.text(zLabel, cx, rowY, labelColor.get(),     false, s);
                cx -= sepW; renderer.text(" | ", cx, rowY, separatorColor.get(), false, s);
                double yw = renderer.textWidth(yVal,   false, s); cx -= yw; renderer.text(yVal,   cx, rowY, valueColor.get(),     false, s);
                double ylw= renderer.textWidth(yLabel, false, s); cx -= ylw;renderer.text(yLabel, cx, rowY, labelColor.get(),     false, s);
                cx -= sepW; renderer.text(" | ", cx, rowY, separatorColor.get(), false, s);
                double xw = renderer.textWidth(xVal,   false, s); cx -= xw; renderer.text(xVal,   cx, rowY, valueColor.get(),     false, s);
                double xlw= renderer.textWidth(xLabel, false, s); cx -= xlw;renderer.text(xLabel, cx, rowY, labelColor.get(),     false, s);
            } else {
                double cx = centerAlign ? x + (totalW - coordW) / 2.0 : x + padH;
                renderer.text(xLabel, cx, rowY, labelColor.get(),     false, s); cx += renderer.textWidth(xLabel, false, s);
                renderer.text(xVal,   cx, rowY, valueColor.get(),     false, s); cx += renderer.textWidth(xVal,   false, s);
                renderer.text(" | ",  cx, rowY, separatorColor.get(), false, s); cx += sepW;
                renderer.text(yLabel, cx, rowY, labelColor.get(),     false, s); cx += renderer.textWidth(yLabel, false, s);
                renderer.text(yVal,   cx, rowY, valueColor.get(),     false, s); cx += renderer.textWidth(yVal,   false, s);
                renderer.text(" | ",  cx, rowY, separatorColor.get(), false, s); cx += sepW;
                renderer.text(zLabel, cx, rowY, labelColor.get(),     false, s); cx += renderer.textWidth(zLabel, false, s);
                renderer.text(zVal,   cx, rowY, valueColor.get(),     false, s);
            }
            lineIdx++;
        }

        // ── Draw: Nether / OW equivalent ─────────────────────────────────────────
        if (hasLineNether) {
            double rowY     = y + padV + lineIdx * (lineHeight + rowGap);
            double lineBoxW = netherW + padH * 2;
            if (showBackground.get()) {
                double bgX = rightAlign ? x + totalW - lineBoxW : centerAlign ? x + (totalW - lineBoxW) / 2.0 : x;
                renderer.quad(bgX, rowY - 1, lineBoxW, lineHeight + 2, backgroundColor.get());
            }
            if (rightAlign) {
                double cx = x + totalW - padH;
                double nzw = renderer.textWidth(nzVal,   false, s); cx -= nzw; renderer.text(nzVal,         cx, rowY, valueColor.get(),      false, s);
                double nzlw= renderer.textWidth(nzLabel, false, s); cx -= nzlw;renderer.text(nzLabel,       cx, rowY, labelColor.get(),      false, s);
                cx -= sepW; renderer.text(" | ", cx, rowY, separatorColor.get(), false, s);
                double nxw = renderer.textWidth(nxVal,   false, s); cx -= nxw; renderer.text(nxVal,         cx, rowY, valueColor.get(),      false, s);
                double nxlw= renderer.textWidth(nxLabel, false, s); cx -= nxlw;renderer.text(nxLabel,       cx, rowY, labelColor.get(),      false, s);
                double nlw = renderer.textWidth(netherLineLabel, false, s); cx -= nlw; renderer.text(netherLineLabel, cx, rowY, netherLabelColor.get(), false, s);
            } else {
                double cx = centerAlign ? x + (totalW - netherW) / 2.0 : x + padH;
                renderer.text(netherLineLabel, cx, rowY, netherLabelColor.get(), false, s); cx += renderer.textWidth(netherLineLabel, false, s);
                renderer.text(nxLabel, cx, rowY, labelColor.get(),     false, s); cx += renderer.textWidth(nxLabel, false, s);
                renderer.text(nxVal,   cx, rowY, valueColor.get(),     false, s); cx += renderer.textWidth(nxVal,   false, s);
                renderer.text(" | ",   cx, rowY, separatorColor.get(), false, s); cx += sepW;
                renderer.text(nzLabel, cx, rowY, labelColor.get(),     false, s); cx += renderer.textWidth(nzLabel, false, s);
                renderer.text(nzVal,   cx, rowY, valueColor.get(),     false, s);
            }
            lineIdx++;
        }

        // ── Draw: End note ────────────────────────────────────────────────────────
        if (hasEndNote) {
            lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
                rightAlign, centerAlign, totalW, endNoteW, lineIdx,
                endNote, "", labelColor.get(), valueColor.get());
        }

        setSize(totalW, totalH);
    }

    // ── Draw helper ───────────────────────────────────────────────────────────────

    private int drawLabelValue(HudRenderer renderer, double s,
                               double padH, double padV, double rowGap, double lineHeight,
                               boolean rightAlign, boolean centerAlign,
                               double totalW, double lineW, int lineIdx,
                               String label, String value,
                               SettingColor lColor, SettingColor vColor) {
        double rowY     = y + padV + lineIdx * (lineHeight + rowGap);
        double lineBoxW = lineW + padH * 2;
        if (showBackground.get()) {
            double bgX = rightAlign  ? x + totalW - lineBoxW
                       : centerAlign ? x + (totalW - lineBoxW) / 2.0
                       : x;
            renderer.quad(bgX, rowY - 1, lineBoxW, lineHeight + 2, backgroundColor.get());
        }
        if (rightAlign) {
            double vw = renderer.textWidth(value, false, s);
            double lw = renderer.textWidth(label, false, s);
            double vx = x + totalW - padH - vw;
            renderer.text(label, vx - lw, rowY, lColor, false, s);
            renderer.text(value, vx,       rowY, vColor, false, s);
        } else {
            double cx = centerAlign ? x + (totalW - lineW) / 2.0 : x + padH;
            renderer.text(label, cx, rowY, lColor, false, s);
            cx += renderer.textWidth(label, false, s);
            renderer.text(value, cx, rowY, vColor, false, s);
        }
        return lineIdx + 1;
    }
}