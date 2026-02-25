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
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
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

    private final Map<BlockPos, List<String>> signs = new ConcurrentHashMap<>();
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

        signs.keySet().removeIf(pos -> 
            Math.sqrt(pos.getSquaredDistance(mc.player.getPos())) > range.get()
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
                    if (!(be instanceof SignBlockEntity sign)) continue;
                    if (Math.sqrt(be.getPos().getSquaredDistance(mc.player.getPos())) > rangeBlocks) continue;

                    List<String> lines = new ArrayList<>();
                    SignText front = sign.getFrontText();
                    SignText back = sign.getBackText();

                    if (filterBadWords.get()) {
                        front = censorSignText(front);
                        back = censorSignText(back);
                    }

                    readSignText(front, lines);
                    readSignText(back, lines);

                    if (lines.isEmpty() && ignoreEmpty.get()) continue;

                    signs.put(be.getPos(), lines);

                    if (chatFeedback.get() && !notified.contains(be.getPos())) {
                        if (!lines.isEmpty()) {
                            info("Sign found: " + String.join(", ", lines));
                        }
                        notified.add(be.getPos());
                    }
                }
            }
        }
    }

    private void readSignText(SignText signText, List<String> output) {
        for (Text t : signText.getMessages(false)) {
            String s = t.getString().trim();
            if (!s.isEmpty()) output.add(s);
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

    @EventHandler
    private void onRender(Render3DEvent event) {
        for (Map.Entry<BlockPos, List<String>> entry : signs.entrySet()) {
            BlockPos pos = entry.getKey();
            List<String> lines = entry.getValue();
            
            Vec3d vec = Vec3d.ofCenter(pos).add(0, 0.5, 0);
            Vector3d pos3d = new Vector3d(vec.x, vec.y, vec.z);

            if (NametagUtils.to2D(pos3d, scale.get())) {
                NametagUtils.begin(pos3d);
                TextRenderer.get().begin(1.0, false, true);

                double y = -(TextRenderer.get().getHeight() * lines.size()) / 2.0;
                for (String line : lines) {
                    double x = -TextRenderer.get().getWidth(line) / 2.0;
                    if (outline.get()) {
                        SettingColor oc = outlineColor.get();
                        TextRenderer.get().render(line, x - 1, y, oc, false);
                        TextRenderer.get().render(line, x + 1, y, oc, false);
                        TextRenderer.get().render(line, x, y - 1, oc, false);
                        TextRenderer.get().render(line, x, y + 1, oc, false);
                        TextRenderer.get().render(line, x, y, textColor.get(), false);
                    } else {
                        TextRenderer.get().render(line, x, y, textColor.get(), true);
                    }
                    y += TextRenderer.get().getHeight();
                }

                TextRenderer.get().end();
                NametagUtils.end();
            }
        }
    }
}
