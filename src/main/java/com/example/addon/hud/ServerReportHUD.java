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
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;

public class ServerReportHUD extends HudElement {

    public static final HudElementInfo<ServerReportHUD> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "server-report",
        "Displays current weather, biome, and active potion effects.",
        ServerReportHUD::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum Alignment { Left, Center, Right }

    // ── Setting Groups ────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgWeather  = settings.createGroup("Weather");
    private final SettingGroup sgBiome    = settings.createGroup("Biome");
    private final SettingGroup sgEffects  = settings.createGroup("Potion Effects");

    // ═══════════════════════════════════════════════════════════════════════════
    // General Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0).min(0.25).sliderRange(0.25, 4.0)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align text to the left, center, or right.")
        .defaultValue(Alignment.Left)
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background behind each line.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color for section labels (e.g. 'Weather:', 'Biome:').")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Color for label values (weather state, biome name).")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Weather Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> showWeather = sgWeather.add(new BoolSetting.Builder()
        .name("show-weather")
        .description("Show the current world weather.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> clearColor = sgWeather.add(new ColorSetting.Builder()
        .name("clear-color")
        .description("Color for clear weather.")
        .defaultValue(new SettingColor(120, 220, 255, 255))
        .visible(showWeather::get)
        .build()
    );

    private final Setting<SettingColor> rainColor = sgWeather.add(new ColorSetting.Builder()
        .name("rain-color")
        .description("Color for rainy weather.")
        .defaultValue(new SettingColor(100, 160, 230, 255))
        .visible(showWeather::get)
        .build()
    );

    private final Setting<SettingColor> thunderColor = sgWeather.add(new ColorSetting.Builder()
        .name("thunder-color")
        .description("Color for thunderstorm weather.")
        .defaultValue(new SettingColor(180, 130, 255, 255))
        .visible(showWeather::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Biome Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> showBiome = sgBiome.add(new BoolSetting.Builder()
        .name("show-biome")
        .description("Show the biome the player is currently standing in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> biomeColor = sgBiome.add(new ColorSetting.Builder()
        .name("biome-color")
        .description("Color for the biome name.")
        .defaultValue(new SettingColor(100, 220, 140, 255))
        .visible(showBiome::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Potion Effect Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> showEffects = sgEffects.add(new BoolSetting.Builder()
        .name("show-effects")
        .description("Show active potion effects. Hides this section entirely when no effects are active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showEffectDuration = sgEffects.add(new BoolSetting.Builder()
        .name("show-duration")
        .description("Show the remaining duration of each potion effect.")
        .defaultValue(true)
        .visible(showEffects::get)
        .build()
    );

    private final Setting<Boolean> showEffectAmplifier = sgEffects.add(new BoolSetting.Builder()
        .name("show-amplifier")
        .description("Show the amplifier level (II, III…) of each potion effect.")
        .defaultValue(true)
        .visible(showEffects::get)
        .build()
    );

    private final Setting<SettingColor> beneficialColor = sgEffects.add(new ColorSetting.Builder()
        .name("beneficial-color")
        .description("Color for beneficial potion effects.")
        .defaultValue(new SettingColor(80, 220, 100, 255))
        .visible(showEffects::get)
        .build()
    );

    private final Setting<SettingColor> harmfulColor = sgEffects.add(new ColorSetting.Builder()
        .name("harmful-color")
        .description("Color for harmful potion effects.")
        .defaultValue(new SettingColor(255, 80, 80, 255))
        .visible(showEffects::get)
        .build()
    );

    private final Setting<SettingColor> neutralColor = sgEffects.add(new ColorSetting.Builder()
        .name("neutral-color")
        .description("Color for neutral potion effects.")
        .defaultValue(new SettingColor(200, 200, 200, 255))
        .visible(showEffects::get)
        .build()
    );

    private final Setting<SettingColor> effectMetaColor = sgEffects.add(new ColorSetting.Builder()
        .name("effect-meta-color")
        .description("Color for effect level and duration values.")
        .defaultValue(new SettingColor(180, 180, 180, 255))
        .visible(showEffects::get)
        .build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public ServerReportHUD() {
        super(INFO);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null || mc.world == null) {
            if (isInEditor()) {
                setSize(120, 20);
                renderer.text("Server Report", x, y, labelColor.get(), false, scale.get());
            } else {
                setSize(0, 0);
            }
            return;
        }

        double s          = scale.get();
        double padH       = 4 * s;
        double padV       = 2 * s;
        double rowGap     = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        Alignment align   = alignment.get();

        // ── Gather weather ────────────────────────────────────────────────────

        String weatherLabel = "";
        SettingColor weatherValueColor = valueColor.get();

        if (showWeather.get()) {
            if (mc.world.isThundering()) {
                weatherLabel = "Thunderstorm";
                weatherValueColor = thunderColor.get();
            } else if (mc.world.isRaining()) {
                weatherLabel = "Rain";
                weatherValueColor = rainColor.get();
            } else {
                weatherLabel = "Clear";
                weatherValueColor = clearColor.get();
            }
        }

        // ── Gather biome ──────────────────────────────────────────────────────

        String biomeName = "";

        if (showBiome.get()) {
            RegistryEntry<Biome> biomeEntry = mc.world.getBiome(mc.player.getBlockPos());
            biomeName = biomeEntry.getKey()
                .map(key -> {
                    String path = key.getValue().getPath();
                    // Convert snake_case to Title Case
                    String[] words = path.split("_");
                    StringBuilder sb = new StringBuilder();
                    for (String word : words) {
                        if (!word.isEmpty()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(Character.toUpperCase(word.charAt(0)));
                            sb.append(word.substring(1));
                        }
                    }
                    return sb.toString();
                })
                .orElse("Unknown");
        }

        // ── Gather potion effects ─────────────────────────────────────────────

        record EffectEntry(String name, int amplifier, int durationTicks, StatusEffectCategory category) {}
        List<EffectEntry> effects = new ArrayList<>();

        if (showEffects.get()) {
            for (StatusEffectInstance instance : mc.player.getStatusEffects()) {
                RegistryEntry<StatusEffect> effectEntry = instance.getEffectType();
                String name = effectEntry.value().getName().getString();
                // Clean up translation key fallback if needed
                if (name.startsWith("effect.")) {
                    String[] parts = name.split("\\.");
                    name = parts.length > 0 ? capitalize(parts[parts.length - 1]) : name;
                }
                effects.add(new EffectEntry(
                    name,
                    instance.getAmplifier(),
                    instance.getDuration(),
                    effectEntry.value().getCategory()
                ));
            }
            // Sort: beneficial first, then neutral, then harmful
            effects.sort((a, b) -> {
                int rankA = effectRank(a.category());
                int rankB = effectRank(b.category());
                return Integer.compare(rankA, rankB);
            });
        }

        // ── Determine visible sections ────────────────────────────────────────

        boolean hasWeather = showWeather.get();
        boolean hasBiome   = showBiome.get();
        boolean hasEffects = showEffects.get() && !effects.isEmpty();

        if (!hasWeather && !hasBiome && !hasEffects) {
            setSize(0, 0);
            return;
        }

        // ── Pre-measure widths ────────────────────────────────────────────────

        double maxW = 0;

        double weatherW = 0;
        if (hasWeather) {
            weatherW = renderer.textWidth("Weather: ", false, s)
                     + renderer.textWidth(weatherLabel, false, s);
            maxW = Math.max(maxW, weatherW);
        }

        double biomeW = 0;
        if (hasBiome) {
            biomeW = renderer.textWidth("Biome: ", false, s)
                   + renderer.textWidth(biomeName, false, s);
            maxW = Math.max(maxW, biomeW);
        }

        double[] effectWidths = new double[effects.size()];
        if (hasEffects) {
            for (int i = 0; i < effects.size(); i++) {
                EffectEntry e = effects.get(i);
                String levelStr = showEffectAmplifier.get() && e.amplifier() > 0
                    ? " " + toRoman(e.amplifier() + 1) : "";
                String durStr = showEffectDuration.get()
                    ? " (" + formatDuration(e.durationTicks()) + ")" : "";
                double w = renderer.textWidth(e.name(), false, s)
                         + renderer.textWidth(levelStr, false, s)
                         + renderer.textWidth(durStr, false, s);
                effectWidths[i] = w;
                maxW = Math.max(maxW, w);
            }
        }

        // ── Count rows ────────────────────────────────────────────────────────

        int lineCount = 0;
        if (hasWeather) lineCount++;
        if (hasBiome)   lineCount++;
        lineCount += effects.size();

        if (lineCount == 0) { setSize(0, 0); return; }

        double totalW = maxW + padH * 2;
        double totalH = lineCount * lineHeight + (lineCount - 1) * rowGap + padV * 2;
        int lineIdx = 0;

        // ── Draw: Weather ─────────────────────────────────────────────────────

        if (hasWeather) {
            double rowY = y + padV + lineIdx * (lineHeight + rowGap);

            if (showBackground.get()) {
                double bgW = weatherW + padH * 2;
                renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
            }

            drawLabelValue(renderer, s, align, totalW, padH, rowY,
                "Weather: ", weatherLabel, weatherW,
                labelColor.get(), weatherValueColor);
            lineIdx++;
        }

        // ── Draw: Biome ───────────────────────────────────────────────────────

        if (hasBiome) {
            double rowY = y + padV + lineIdx * (lineHeight + rowGap);

            if (showBackground.get()) {
                double bgW = biomeW + padH * 2;
                renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
            }

            drawLabelValue(renderer, s, align, totalW, padH, rowY,
                "Biome: ", biomeName, biomeW,
                labelColor.get(), biomeColor.get());
            lineIdx++;
        }

        // ── Draw: Potion Effects ──────────────────────────────────────────────

        if (hasEffects) {
            for (int i = 0; i < effects.size(); i++) {
                EffectEntry e   = effects.get(i);
                double rowY     = y + padV + lineIdx * (lineHeight + rowGap);
                double rowW     = effectWidths[i];

                String levelStr = showEffectAmplifier.get() && e.amplifier() > 0
                    ? " " + toRoman(e.amplifier() + 1) : "";
                String durStr   = showEffectDuration.get()
                    ? " (" + formatDuration(e.durationTicks()) + ")" : "";

                SettingColor nameCol = switch (e.category()) {
                    case BENEFICIAL -> beneficialColor.get();
                    case HARMFUL    -> harmfulColor.get();
                    default         -> neutralColor.get();
                };

                if (showBackground.get()) {
                    double bgW = rowW + padH * 2;
                    renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
                }

                // Draw: name (colored) + level + duration (meta color)
                if (align == Alignment.Right) {
                    double cx = x + totalW - padH;
                    double dw = renderer.textWidth(durStr,   false, s);
                    double lw = renderer.textWidth(levelStr, false, s);
                    double nw = renderer.textWidth(e.name(), false, s);
                    cx -= dw; renderer.text(durStr,   cx, rowY, effectMetaColor.get(), false, s);
                    cx -= lw; renderer.text(levelStr, cx, rowY, effectMetaColor.get(), false, s);
                    cx -= nw; renderer.text(e.name(), cx, rowY, nameCol,               false, s);
                } else if (align == Alignment.Center) {
                    double cx = x + (totalW - rowW) / 2.0;
                    renderer.text(e.name(), cx, rowY, nameCol, false, s);
                    cx += renderer.textWidth(e.name(), false, s);
                    renderer.text(levelStr, cx, rowY, effectMetaColor.get(), false, s);
                    cx += renderer.textWidth(levelStr, false, s);
                    renderer.text(durStr,   cx, rowY, effectMetaColor.get(), false, s);
                } else {
                    double cx = x + padH;
                    renderer.text(e.name(), cx, rowY, nameCol, false, s);
                    cx += renderer.textWidth(e.name(), false, s);
                    renderer.text(levelStr, cx, rowY, effectMetaColor.get(), false, s);
                    cx += renderer.textWidth(levelStr, false, s);
                    renderer.text(durStr,   cx, rowY, effectMetaColor.get(), false, s);
                }

                lineIdx++;
            }
        }

        setSize(totalW, totalH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Draw helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Draws a two-part "Label: Value" row with separate colors.
     */
    private void drawLabelValue(HudRenderer renderer, double s,
                                Alignment align, double totalW, double padH, double rowY,
                                String label, String value, double rowW,
                                SettingColor lColor, SettingColor vColor) {
        if (align == Alignment.Right) {
            double cx = x + totalW - padH;
            double vw = renderer.textWidth(value, false, s);
            double lw = renderer.textWidth(label, false, s);
            cx -= vw; renderer.text(value, cx, rowY, vColor, false, s);
            cx -= lw; renderer.text(label, cx, rowY, lColor, false, s);
        } else if (align == Alignment.Center) {
            double cx = x + (totalW - rowW) / 2.0;
            renderer.text(label, cx, rowY, lColor, false, s);
            cx += renderer.textWidth(label, false, s);
            renderer.text(value, cx, rowY, vColor, false, s);
        } else {
            double cx = x + padH;
            renderer.text(label, cx, rowY, lColor, false, s);
            cx += renderer.textWidth(label, false, s);
            renderer.text(value, cx, rowY, vColor, false, s);
        }
    }

    private double bgStartX(Alignment align, double totalW, double bgW) {
        return switch (align) {
            case Right  -> x + totalW - bgW;
            case Center -> x + (totalW - bgW) / 2.0;
            case Left   -> x;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Sort order: beneficial=0, neutral=1, harmful=2 */
    private int effectRank(StatusEffectCategory category) {
        return switch (category) {
            case BENEFICIAL -> 0;
            case NEUTRAL    -> 1;
            case HARMFUL    -> 2;
        };
    }

    /** Converts tick count to a human-readable duration string. */
    private String formatDuration(int ticks) {
        if (ticks >= 32767 * 20) return "∞"; // effectively infinite
        int totalSeconds = ticks / 20;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + ":" + String.format("%02d", seconds);
        }
        return seconds + "s";
    }

    /** Converts an integer to a Roman numeral string (handles 1–20). */
    private String toRoman(int n) {
        String[] thousands = {"", "M", "MM", "MMM"};
        String[] hundreds  = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
        String[] tens      = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        String[] ones      = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        if (n <= 0 || n >= 4000) return String.valueOf(n);
        return thousands[n / 1000] + hundreds[(n % 1000) / 100]
             + tens[(n % 100) / 10] + ones[n % 10];
    }

    /** Capitalises the first letter of a string. */
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}