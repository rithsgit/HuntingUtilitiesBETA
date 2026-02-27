package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3d;

import com.example.addon.HuntingUtilities;
import com.mojang.blaze3d.systems.RenderSystem;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HangingSignBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.MutableText;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class SignScanner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgOptimization = settings.createGroup("Optimization");

    private final Setting<Integer> chunks = sgGeneral.add(new IntSetting.Builder()
        .name("chunks")
        .description("Radius in chunks to scan for signs.")
        .defaultValue(16)
        .min(1)
        .sliderMax(64)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Notify in chat when a sign is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreEmpty = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-empty")
        .description("Ignore signs with no text.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> preserveLines = sgGeneral.add(new BoolSetting.Builder()
        .name("preserve-lines")
        .description("Preserves the 4-line layout of signs, including empty lines.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgRender.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the rendered text.")
        .defaultValue(1.5)
        .min(0.1)
        .sliderMax(5.0)
        .build()
    );

    private final Setting<Boolean> useLegacyFormatting = sgRender.add(new BoolSetting.Builder()
        .name("legacy-formatting")
        .description("Preserves Minecraft color/style codes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> textColor = sgRender.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Text color.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<Boolean> useSignColor = sgRender.add(new BoolSetting.Builder()
        .name("use-sign-color")
        .description("Use the sign's dye color for text.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> background = sgRender.add(new BoolSetting.Builder()
        .name("background")
        .description("Render a background behind the text.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgRender.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color.")
        .defaultValue(new SettingColor(30, 30, 30, 160))
        .visible(background::get)
        .build()
    );
    
    private final Setting<Boolean> merge = sgRender.add(new BoolSetting.Builder()
        .name("merge")
        .description("Merge signs that are close together.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> mergeDistance = sgRender.add(new DoubleSetting.Builder()
        .name("merge-distance")
        .description("Distance in pixels to merge signs.")
        .defaultValue(20.0)
        .min(0)
        .sliderMax(100)
        .visible(merge::get)
        .build()
    );

    private final Setting<Boolean> censorship = sgFilter.add(new BoolSetting.Builder()
        .name("censorship")
        .description("Censors bad words on signs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cacheSignText = sgOptimization.add(new BoolSetting.Builder()
        .name("cache-sign-text")
        .description("Cache sign text to improve performance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> updateInterval = sgOptimization.add(new IntSetting.Builder()
        .name("update-interval")
        .description("How often to scan for signs (in ticks).")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .visible(cacheSignText::get)
        .build()
    );

    private final Setting<List<String>> badWords = sgFilter.add(new StringListSetting.Builder()
        .name("Banned Words")
        .description("List of words to censor.")
        .defaultValue(List.of("badword1", "badword2"))
        .visible(censorship::get)
        .build()
    );

    private final Map<BlockPos, List<Text>> signs = new ConcurrentHashMap<>();
    private final Set<BlockPos> notified = new HashSet<>();
    private int timer = 0;

    public SignScanner() {
        super(HuntingUtilities.CATEGORY, "sign-scanner", "Scans and displays sign text.");
    }

    @Override
    public void onActivate() {
        signs.clear();
        notified.clear();
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        double dist = chunks.get() * 16.0;
        double rangeSq = dist * dist;
        signs.keySet().removeIf(pos ->
            pos.getSquaredDistance(mc.player.getPos()) > rangeSq
        );

        if (cacheSignText.get()) {
            if (timer > 0) {
                timer--;
                return;
            }
            timer = updateInterval.get();
        }

        try {
            Set<BlockPos> currentSigns = new HashSet<>();

            for (BlockEntity be : Utils.blockEntities()) {
                // Check both sign types simultaneously via switch expression
                SignText[] texts = switch (be) {
                    case HangingSignBlockEntity h -> new SignText[]{ h.getFrontText(), h.getBackText() };
                    case SignBlockEntity s        -> new SignText[]{ s.getFrontText(), s.getBackText() };
                    default -> null;
                };
                if (texts == null) continue;
                SignText front = texts[0], back = texts[1];
                if (be.getPos().getSquaredDistance(mc.player.getPos()) > rangeSq) continue;

                List<Text> lines = new ArrayList<>();

                if (censorship.get()) {
                    front = censorSignText(front);
                    back = censorSignText(back);
                }

                if (preserveLines.get()) {
                    int frontColor = front.getColor().getSignColor();
                    for (Text t : front.getMessages(false)) lines.add(applyColor(cleanSignText(t), frontColor));
                    
                    boolean backHasText = false;
                    for (Text t : back.getMessages(false)) {
                        if (!t.getString().isBlank()) backHasText = true;
                    }
                    if (backHasText) {
                        int backColor = back.getColor().getSignColor();
                        for (Text t : back.getMessages(false)) lines.add(applyColor(cleanSignText(t), backColor));
                    }
                } else {
                    readSignText(front, lines);
                    readSignText(back, lines);
                }

                if (lines.stream().allMatch(t -> t.getString().isBlank()) && ignoreEmpty.get()) continue;

                signs.put(be.getPos(), lines);
                currentSigns.add(be.getPos());

                if (chatFeedback.get() && !notified.contains(be.getPos())) {
                    List<String> lineStrings = lines.stream().map(Text::getString).filter(s -> !s.isBlank()).toList();
                    if (!lineStrings.isEmpty()) {
                        info("Sign found: " + String.join(" | ", lineStrings));
                    }
                    notified.add(be.getPos());
                }
            }

            signs.keySet().retainAll(currentSigns);
            notified.retainAll(currentSigns);

        } catch (Exception ignored) {
        }
    }

    private void readSignText(SignText signText, List<Text> output) {
        int color = signText.getColor().getSignColor();
        for (Text t : signText.getMessages(false)) {
            Text cleaned = cleanSignText(t);
            if (!cleaned.getString().trim().isEmpty()) {
                output.add(applyColor(cleaned, color));
            }
        }
    }

    public boolean shouldCensor() {
        return isActive() && censorship.get();
    }

    public SignText censorSignText(SignText signText) {
        SignText newText = signText;
        for (int i = 0; i < 4; i++) {
            Text original = signText.getMessage(i, false);
            newText = newText.withMessage(i, censorText(original));
        }
        return newText;
    }

    private Text censorText(Text text) {
        TextContent content = text.getContent();
        if (content instanceof PlainTextContent ptc) {
            content = PlainTextContent.of(censor(ptc.string()));
        }

        MutableText result = MutableText.of(content).setStyle(text.getStyle());
        for (Text sibling : text.getSiblings()) {
            result.append(censorText(sibling));
        }
        return result;
    }

    public String censor(String input) {
        for (String bad : badWords.get()) {
            try {
                if (input.matches("(?i).*" + bad + ".*")) {
                    return "****";
                }
            } catch (Exception ignored) {
            }
        }
        return input;
    }

    private Text cleanSignText(Text text) {
        // Removes formatting codes and artifacts by converting to plain string then back to literal
        String s = text.getString().replaceAll("§.", "");
        return Text.literal(s).setStyle(text.getStyle());
    }

    private Text applyColor(Text text, int color) {
        if (text instanceof MutableText mt) {
            return mt.setStyle(text.getStyle().withColor(color));
        }
        return text;
    }

    private String getTextContent(Text text) {
        if (!useLegacyFormatting.get()) {
            return text.getString();
        }

        StringBuilder sb = new StringBuilder();
        text.visit((style, asString) -> {
            if (style.getColor() != null) {
                Formatting f = Formatting.byName(style.getColor().getName());
                if (f != null) sb.append(f);
            }
            if (style.isObfuscated()) sb.append(Formatting.OBFUSCATED);
            if (style.isBold()) sb.append(Formatting.BOLD);
            if (style.isStrikethrough()) sb.append(Formatting.STRIKETHROUGH);
            if (style.isUnderlined()) sb.append(Formatting.UNDERLINE);
            if (style.isItalic()) sb.append(Formatting.ITALIC);
            sb.append(asString);
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null) return;
        TextRenderer tr = TextRenderer.get();
        List<SignEntry> entries = new ArrayList<>();

        try {
            for (Map.Entry<BlockPos, List<Text>> entry : signs.entrySet()) {
                BlockPos pos = entry.getKey();
                List<Text> lines = entry.getValue();
                if (lines.isEmpty()) continue;

                Vec3d vec = Vec3d.ofCenter(pos).add(0, 0.5, 0);
                Vector3d pos3d = new Vector3d(vec.x, vec.y, vec.z);
                
                if (!NametagUtils.to2D(pos3d, scale.get())) continue;

                entries.add(new SignEntry(pos, lines, pos3d));
            }
        } catch (Exception ignored) {}

        // Sort closest to furthest for grouping
        entries.sort(Comparator.comparingDouble(e -> mc.player.squaredDistanceTo(e.pos.toCenterPos())));

        List<SignEntry> grouped = new ArrayList<>();
        double mergeDistSq = mergeDistance.get() * mergeDistance.get();

        for (SignEntry entry : entries) {
            if (!merge.get()) {
                grouped.add(entry);
                continue;
            }

            boolean merged = false;
            for (SignEntry group : grouped) {
                double dx = entry.pos3d.x - group.pos3d.x;
                double dy = entry.pos3d.y - group.pos3d.y;
                if (dx * dx + dy * dy <= mergeDistSq) {
                    group.count++;
                    merged = true;
                    break;
                }
            }

            if (!merged) grouped.add(entry);
        }

        // Sort furthest to closest for rendering (Painter's algorithm)
        grouped.sort(Comparator.comparingDouble(e -> -mc.player.squaredDistanceTo(e.pos.toCenterPos())));

        try {
            for (SignEntry entry : grouped) {
                renderSign(entry, event, tr);
            }
        } catch (Exception ignored) {}
    }

    private void renderSign(SignEntry entry, Render2DEvent event, TextRenderer tr) {
        NametagUtils.begin(entry.pos3d, event.drawContext);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        List<Text> linesToRender = new ArrayList<>(entry.lines);
        if (entry.count > 1) {
            linesToRender.add(Text.literal(entry.count + " signs").formatted(Formatting.YELLOW));
        }

        double lh = tr.getHeight();

        // Background
        if (background.get()) {
            Renderer2D.COLOR.begin();
            double maxWidth = 0;
            for (Text t : linesToRender) maxWidth = Math.max(maxWidth, tr.getWidth(getTextContent(t)));
            double totalH = linesToRender.size() * lh;

            double pad = 4.0;
            double bw = maxWidth + pad * 2;
            double bh = totalH + pad * 2;
            double bx = -bw / 2.0;
            double by = -totalH / 2.0 - pad;

            Renderer2D.COLOR.quad(bx, by, bw, bh, backgroundColor.get());
            Renderer2D.COLOR.render(null);
        }

        // Text
        tr.begin(1.0, false, true);
        double y = -(linesToRender.size() * lh) / 2.0;
        for (Text lineText : linesToRender) {
            String line = getTextContent(lineText);
            double x = -tr.getWidth(line) / 2.0;

            SettingColor color = textColor.get();
            if (useSignColor.get() && lineText.getStyle().getColor() != null) {
                int rgb = lineText.getStyle().getColor().getRgb();
                color = new SettingColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
            }

            tr.render(line, x, y, color, true);
            y += lh;
        }
        tr.end();

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        NametagUtils.end(event.drawContext);
    }

    private static class SignEntry {
        BlockPos pos;
        List<Text> lines;
        Vector3d pos3d;
        int count = 1;

        public SignEntry(BlockPos pos, List<Text> lines, Vector3d pos3d) {
            this.pos = pos; this.lines = lines; this.pos3d = pos3d;
        }
    }
}