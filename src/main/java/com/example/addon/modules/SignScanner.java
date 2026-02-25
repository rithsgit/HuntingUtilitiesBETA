package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HangingSignBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.MutableText;
import net.minecraft.text.TextContent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Vector3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SignScanner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgFilter = settings.createGroup("Filter");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Scanning range in blocks.")
        .defaultValue(64)
        .min(0)
        .sliderMax(128)
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
        .sliderMax(3.0)
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

    private final Setting<Boolean> filterBadWords = sgFilter.add(new BoolSetting.Builder()
        .name("filter-bad-words")
        .description("Censors bad words on signs.")
        .defaultValue(true)
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

        if (timer > 0) {
            timer--;
            return;
        }
        timer = 20;

        double rangeSq = range.get() * range.get();
        signs.keySet().removeIf(pos ->
            pos.getSquaredDistance(mc.player.getPos()) > rangeSq
        );

        int rangeBlocks = range.get();
        int chunkRange = (rangeBlocks >> 4) + 1;
        int centerChunkX = mc.player.getBlockPos().getX() >> 4;
        int centerChunkZ = mc.player.getBlockPos().getZ() >> 4;

        for (int x = -chunkRange; x <= chunkRange; x++) {
            for (int z = -chunkRange; z <= chunkRange; z++) {
                WorldChunk chunk = mc.world.getChunkManager().getChunk(centerChunkX + x, centerChunkZ + z, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    // Accept both regular signs and hanging signs regardless of class hierarchy
                    SignText front, back;
                    if (be instanceof HangingSignBlockEntity hsign) {
                        front = hsign.getFrontText();
                        back = hsign.getBackText();
                    } else if (be instanceof SignBlockEntity sign) {
                        front = sign.getFrontText();
                        back = sign.getBackText();
                    } else {
                        continue;
                    }
                    if (be.getPos().getSquaredDistance(mc.player.getPos()) > rangeSq) continue;

                    List<Text> lines = new ArrayList<>();

                    if (filterBadWords.get()) {
                        front = censorSignText(front);
                        back = censorSignText(back);
                    }

                    if (preserveLines.get()) {
                        for (Text t : front.getMessages(false)) lines.add(t);

                        boolean backHasText = false;
                        for (Text t : back.getMessages(false)) {
                            if (!t.getString().isBlank()) backHasText = true;
                        }
                        if (backHasText) {
                            for (Text t : back.getMessages(false)) lines.add(t);
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
            }
        }
    }

    private void readSignText(SignText signText, List<Text> output) {
        for (Text t : signText.getMessages(false)) {
            if (!t.getString().trim().isEmpty()) output.add(t);
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
        String lower = input.toLowerCase();
        for (String bad : badWords.get()) {
            if (lower.contains(bad.toLowerCase())) {
                return "*CENSORED*";
            }
        }
        return input;
    }

    private String textToLegacy(Text text) {
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
    private void onRender(Render3DEvent event) {
        for (Map.Entry<BlockPos, List<Text>> entry : signs.entrySet()) {
            BlockPos pos = entry.getKey();
            List<Text> lines = entry.getValue();

            if (lines.isEmpty()) continue;

            Vec3d vec = Vec3d.ofCenter(pos).add(0, 0.5, 0);
            Vector3d pos3d = new Vector3d(vec.x, vec.y, vec.z);

            if (NametagUtils.to2D(pos3d, scale.get())) {
                NametagUtils.begin(pos3d);

                RenderSystem.disableDepthTest(); // Disable depth for the entire overlay

                double maxWidth = 0;
                for (Text lineText : lines) {
                    maxWidth = Math.max(maxWidth, TextRenderer.get().getWidth(textToLegacy(lineText)));
                }
                double lineHeight = TextRenderer.get().getHeight();
                double totalHeight = lines.size() * lineHeight;

                // Background
                if (background.get()) {
                    double padding = 2.0;
                    double bw = maxWidth + padding * 2;
                    double bh = totalHeight + padding * 2;
                    double bx = -bw / 2.0;
                    double by = -totalHeight / 2.0 - padding;

                    Renderer2D.COLOR.begin();
                    Renderer2D.COLOR.quad(bx, by, bw, bh, backgroundColor.get());
                    Renderer2D.COLOR.render(event.matrices);
                }

                // Text rendering
                TextRenderer.get().begin(1.0, false, false);

                double y = -totalHeight / 2.0;
                for (Text lineText : lines) {
                    String line = textToLegacy(lineText);
                    double x = -TextRenderer.get().getWidth(line) / 2.0;

                    if (outline.get()) {
                        SettingColor oc = outlineColor.get();
                        TextRenderer.get().render(line, x - 1, y, oc, false);
                        TextRenderer.get().render(line, x + 1, y, oc, false);
                        TextRenderer.get().render(line, x, y - 1, oc, false);
                        TextRenderer.get().render(line, x, y + 1, oc, false);
                        TextRenderer.get().render(line, x, y, textColor.get(), false);
                    } else {
                        TextRenderer.get().render(line, x, y, textColor.get(), shadow.get());
                    }

                    y += lineHeight;
                }

                TextRenderer.get().end();

                RenderSystem.enableDepthTest(); // Re-enable depth after drawing

                NametagUtils.end();
            }
        }
    }
}