package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HangingSignBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.MutableText;
import net.minecraft.text.TextContent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SignScanner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgOptimization = settings.createGroup("Optimization");
    private final SettingGroup sgClustering = settings.createGroup("Clustering");

    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("Maximum distance to scan for signs.")
        .defaultValue(64.0)
        .min(0)
        .sliderMax(256)
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

    private final Setting<Boolean> multilineDisplay = sgGeneral.add(new BoolSetting.Builder()
        .name("multiline-display")
        .description("Always render 4 lines per sign.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> scale = sgRender.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the rendered text.")
        .defaultValue(1.5)
        .min(0.1)
        .sliderMax(3.0)
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

    private final Setting<Boolean> outline = sgRender.add(new BoolSetting.Builder()
        .name("outline")
        .description("Render text with an outline.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> outlineColor = sgRender.add(new ColorSetting.Builder()
        .name("outline-color")
        .description("Outline color.")
        .defaultValue(new SettingColor(0, 0, 0, 255))
        .visible(outline::get)
        .build()
    );

    private final Setting<Boolean> shadow = sgRender.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Render text with shadow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> throughWalls = sgRender.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Renders sign text through walls and terrain.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> filterBadWords = sgFilter.add(new BoolSetting.Builder()
        .name("filter-bad-words")
        .description("Censors bad words on signs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cullOffScreen = sgOptimization.add(new BoolSetting.Builder()
        .name("cull-off-screen")
        .description("Don't render signs that are outside the screen bounds.")
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

    private final Setting<Boolean> enableClustering = sgClustering.add(new BoolSetting.Builder()
        .name("enable-clustering")
        .description("Groups overlapping signs together.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> clusterRadius = sgClustering.add(new DoubleSetting.Builder()
        .name("cluster-radius")
        .description("Radius in pixels to group signs.")
        .defaultValue(40.0)
        .min(0)
        .sliderMax(200)
        .visible(enableClustering::get)
        .build()
    );

    public enum ClusterMode { Stack, Cycle }

    private final Setting<ClusterMode> clusterMode = sgClustering.add(new EnumSetting.Builder<ClusterMode>()
        .name("cluster-mode")
        .description("How to display clustered signs.")
        .defaultValue(ClusterMode.Stack)
        .visible(enableClustering::get)
        .build()
    );

    private final Setting<Boolean> useRegex = sgFilter.add(new BoolSetting.Builder()
        .name("use-regex")
        .description("Use regular expressions for bad word filtering.")
        .defaultValue(false)
        .visible(filterBadWords::get)
        .build()
    );

    private final Setting<List<String>> badWords = sgFilter.add(new StringListSetting.Builder()
        .name("bad-words")
        .description("List of words to censor.")
        .defaultValue(List.of("badword1", "badword2"))
        .visible(filterBadWords::get)
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

        double dist = maxDistance.get();
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

                if (filterBadWords.get()) {
                    front = censorSignText(front);
                    back = censorSignText(back);
                }

                if (multilineDisplay.get() || preserveLines.get()) {
                    for (Text t : front.getMessages(false)) lines.add(cleanSignText(t));
                    
                    boolean backHasText = false;
                    for (Text t : back.getMessages(false)) {
                        if (!t.getString().isBlank()) backHasText = true;
                    }
                    if (backHasText) {
                        for (Text t : back.getMessages(false)) lines.add(cleanSignText(t));
                    }
                } else {
                    readSignText(front, lines);
                    readSignText(back, lines);
                }

                if (lines.stream().allMatch(t -> t.getString().isBlank()) && ignoreEmpty.get()) continue;

                signs.put(be.getPos(), lines);

                if (chatFeedback.get() && !notified.contains(be.getPos())) {
                    List<String> lineStrings = lines.stream().map(Text::getString).filter(s -> !s.isBlank()).toList();
                    if (!lineStrings.isEmpty()) {
                        info("Sign found: " + String.join(" | ", lineStrings));
                    }
                    notified.add(be.getPos());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void readSignText(SignText signText, List<Text> output) {
        for (Text t : signText.getMessages(false)) {
            Text cleaned = cleanSignText(t);
            if (!cleaned.getString().trim().isEmpty()) {
                output.add(cleaned);
            }
        }
    }

    public boolean shouldCensor() {
        return isActive() && filterBadWords.get();
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
        if (useRegex.get()) {
            for (String bad : badWords.get()) {
                try {
                    if (input.matches("(?i).*" + bad + ".*")) {
                        return "*CENSORED*";
                    }
                } catch (Exception ignored) {}
            }
            return input;
        }
        String lower = input.toLowerCase();
        for (String bad : badWords.get()) {
            if (lower.contains(bad.toLowerCase())) {
                return "*CENSORED*";
            }
        }
        return input;
    }

    private Text cleanSignText(Text text) {
        // Removes formatting codes and artifacts by converting to plain string then back to literal
        String s = text.getString().replaceAll("§.", "");
        return Text.literal(s).setStyle(text.getStyle());
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

                // After to2D(), pos3d contains screen-space coordinates
                if (cullOffScreen.get()) {
                    double margin = 100.0;
                    if (pos3d.x < -margin || pos3d.y < -margin || pos3d.x > mc.getWindow().getScaledWidth() + margin || pos3d.y > mc.getWindow().getScaledHeight() + margin) continue;
                }

                entries.add(new SignEntry(pos, lines, pos3d, new Vector3d(pos3d.x, pos3d.y, pos3d.z)));
            }
        } catch (Exception ignored) {}

        List<SignCluster> clusters = new ArrayList<>();
        if (enableClustering.get()) {
            for (SignEntry entry : entries) {
                boolean added = false;
                for (SignCluster cluster : clusters) {
                    double dist = Math.sqrt(Math.pow(cluster.pos2d.x - entry.pos2d.x, 2) + Math.pow(cluster.pos2d.y - entry.pos2d.y, 2));
                    if (dist <= clusterRadius.get()) {
                        cluster.add(entry);
                        added = true;
                        break;
                    }
                }
                if (!added) {
                    clusters.add(new SignCluster(entry));
                }
            }
        } else {
            for (SignEntry entry : entries) {
                clusters.add(new SignCluster(entry));
            }
        }

        try {
            for (SignCluster cluster : clusters) {
                renderCluster(cluster, event, tr);
            }
        } catch (Exception ignored) {}
    }

    private void renderCluster(SignCluster cluster, Render2DEvent event, TextRenderer tr) {
        NametagUtils.begin(cluster.anchor3d, event.drawContext);
        if (throughWalls.get()) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
        }

        List<List<Text>> signsToRender = new ArrayList<>();
        if (clusterMode.get() == ClusterMode.Cycle && enableClustering.get()) {
            int index = (int) ((System.currentTimeMillis() / 2000L) % cluster.entries.size());
            signsToRender.add(cluster.entries.get(index).lines);
        } else {
            for (SignEntry entry : cluster.entries) {
                signsToRender.add(entry.lines);
            }
        }

        double yOffset = 0;
        double lh = tr.getHeight();

        // First pass: Backgrounds
        if (background.get()) {
            Renderer2D.COLOR.begin();
            double currentY = 0;
            for (List<Text> lines : signsToRender) {
                double maxWidth = 0;
                for (Text t : lines) maxWidth = Math.max(maxWidth, tr.getWidth(getTextContent(t)));
                double totalH = lines.size() * lh;

                double pad = 2.0;
                double bw = maxWidth + pad * 2;
                double bh = totalH + pad * 2;
                double bx = -bw / 2.0;
                double by = currentY - totalH / 2.0 - pad;

                Renderer2D.COLOR.quad(bx, by, bw, bh, backgroundColor.get());
                
                // Add spacing between stacked signs
                currentY += totalH + 6.0; 
            }
            Renderer2D.COLOR.render(null);
        }

        // Second pass: Text
        double currentY = 0;
        for (List<Text> lines : signsToRender) {
            double totalH = lines.size() * lh;
            
            // Text — begin with shadow consistent with setting for smoother appearance
            tr.begin(1.0, false, shadow.get());
            double y = currentY - totalH / 2.0;
            for (Text lineText : lines) {
                String line = getTextContent(lineText);
                double x = -tr.getWidth(line) / 2.0;
                if (outline.get()) {
                    SettingColor oc = outlineColor.get();
                    tr.render(line, x - 1, y, oc, false);
                    tr.render(line, x + 1, y, oc, false);
                    tr.render(line, x, y - 1, oc, false);
                    tr.render(line, x, y + 1, oc, false);
                }
                tr.render(line, x, y, textColor.get(), shadow.get());
                y += lh;
            }
            tr.end();

            currentY += totalH + 6.0;
        }

        if (throughWalls.get()) {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }
        NametagUtils.end(event.drawContext);
    }

    private static class SignEntry {
        BlockPos pos;
        List<Text> lines;
        Vector3d pos3d;
        Vector3d pos2d;

        public SignEntry(BlockPos pos, List<Text> lines, Vector3d pos3d, Vector3d pos2d) {
            this.pos = pos; this.lines = lines; this.pos3d = pos3d; this.pos2d = pos2d;
        }
    }

    private static class SignCluster {
        Vector3d pos2d;
        Vector3d anchor3d;
        List<SignEntry> entries = new ArrayList<>();

        public SignCluster(SignEntry first) { this.pos2d = first.pos2d; this.anchor3d = first.pos3d; this.entries.add(first); }
        public void add(SignEntry entry) { this.entries.add(entry); }
    }
}