package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HangingSignBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.item.Items;
import net.minecraft.item.SignItem;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class SignScanner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAutoSign = settings.createGroup("Auto Sign");
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

    private final Setting<Boolean> chatMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-messages")
        .description("Notify in chat when a sign is found.")
        .defaultValue(true)
        .build()
    );

    // Auto Sign Settings
    private final Setting<Boolean> autoSign = sgAutoSign.add(new BoolSetting.Builder()
        .name("auto-sign")
        .description("Automatically places signs with custom text.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Keybind> autoSignKey = sgAutoSign.add(new KeybindSetting.Builder()
        .name("toggle-key")
        .description("Key to toggle auto sign.")
        .defaultValue(Keybind.none())
        .action(() -> {
            boolean newVal = !autoSign.get();
            autoSign.set(newVal);
            info("Auto Sign " + (newVal ? "enabled" : "disabled") + ".");
        })
        .build()
    );

    private final Setting<String> line1 = sgAutoSign.add(new StringSetting.Builder()
        .name("line-1")
        .description("Line 1 of the sign.")
        .defaultValue("Hello")
        .visible(autoSign::get)
        .build()
    );

    private final Setting<String> line2 = sgAutoSign.add(new StringSetting.Builder()
        .name("line-2")
        .description("Line 2 of the sign.")
        .defaultValue("World")
        .visible(autoSign::get)
        .build()
    );

    private final Setting<String> line3 = sgAutoSign.add(new StringSetting.Builder()
        .name("line-3")
        .description("Line 3 of the sign.")
        .defaultValue(":)")
        .visible(autoSign::get)
        .build()
    );

    private final Setting<String> line4 = sgAutoSign.add(new StringSetting.Builder()
        .name("line-4")
        .description("Line 4 of the sign.")
        .defaultValue("")
        .visible(autoSign::get)
        .build()
    );

    private final Setting<Double> placeDelay = sgAutoSign.add(new DoubleSetting.Builder()
        .name("place-delay")
        .description("The delay between placing signs automatically (in seconds).")
        .defaultValue(1.0)
        .min(0)
        .sliderMax(10)
        .visible(autoSign::get)
        .build()
    );

    private final Setting<Double> manualEditDelay = sgAutoSign.add(new DoubleSetting.Builder()
        .name("manual-edit-delay")
        .description("The delay after placing a sign with empty lines for manual editing (in seconds).")
        .defaultValue(5.0)
        .min(0)
        .sliderMax(20)
        .visible(autoSign::get)
        .build()
    );

    private final Setting<Boolean> autoGlow = sgAutoSign.add(new BoolSetting.Builder()
        .name("auto-glow")
        .description("Automatically applies a glow ink sac to the sign.")
        .defaultValue(false)
        .visible(autoSign::get)
        .build()
    );

    private final Setting<Boolean> rotate = sgAutoSign.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the block when placing.")
        .defaultValue(true)
        .visible(autoSign::get)
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

    private final Setting<Boolean> ignoreEmpty = sgFilter.add(new BoolSetting.Builder()
        .name("ignore-empty")
        .description("Ignore signs with no text.")
        .defaultValue(true)
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
    private int autoSignTimer = 0;

    public SignScanner() {
        super(HuntingUtilities.CATEGORY, "sign-scanner", "Scans and displays sign text.");
    }

    @Override
    public void onActivate() {
        signs.clear();
        notified.clear();
        timer = 0;
        autoSignTimer = 0;
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (!autoSign.get()) return;

        if (autoSignTimer > 0) {
            autoSignTimer--;
            return;
        }

        FindItemResult signResult = InvUtils.findInHotbar(item -> item.getItem() instanceof SignItem);
        if (!signResult.found()) return;

        BlockPos playerPos = mc.player.getBlockPos();
        int r = 4;

        for (int x = -r; x <= r; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (!mc.world.getBlockState(pos).isReplaceable()) continue;

                    for (Direction dir : Direction.values()) {
                        BlockPos neighbor = pos.offset(dir);
                        if (mc.world.getBlockState(neighbor).isSolidBlock(mc.world, neighbor)) {
                            
                            final BlockPos finalNeighbor = neighbor;
                            final Direction finalSide = dir.getOpposite();
                            final int slot = signResult.slot();

                            String l1 = line1.get();
                            String l2 = line2.get();
                            String l3 = line3.get();
                            String l4 = line4.get();
                            boolean hasEmptyLine = l1.isEmpty() || l2.isEmpty() || l3.isEmpty() || l4.isEmpty();

                            Runnable action = () -> {
                                int prevSlot = mc.player.getInventory().selectedSlot;
                                InvUtils.swap(slot, false);

                                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(finalNeighbor), finalSide, finalNeighbor, false);
                                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                                mc.player.swingHand(Hand.MAIN_HAND);

                                if (!hasEmptyLine) {
                                    mc.player.networkHandler.sendPacket(new UpdateSignC2SPacket(pos, true, l1, l2, l3, l4));

                                    if (autoGlow.get()) {
                                        FindItemResult glowResult = InvUtils.findInHotbar(Items.GLOW_INK_SAC);
                                        if (glowResult.found()) {
                                            InvUtils.swap(glowResult.slot(), false);
                                            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(pos), finalSide, pos, false));
                                            mc.player.swingHand(Hand.MAIN_HAND);
                                        }
                                    }
                                }

                                InvUtils.swap(prevSlot, false);
                            };

                            if (rotate.get()) {
                                Rotations.rotate(Rotations.getYaw(finalNeighbor), Rotations.getPitch(finalNeighbor), action);
                            } else {
                                action.run();
                            }

                            if (hasEmptyLine) {
                                autoSignTimer = (int) (manualEditDelay.get() * 20);
                            } else {
                                autoSignTimer = (int) (placeDelay.get() * 20);
                            }
                            return;
                        }
                    }
                }
            }
        }
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

                readSignText(front, lines);
                readSignText(back, lines);

                if (lines.stream().allMatch(t -> t.getString().isBlank()) && ignoreEmpty.get()) continue;

                signs.put(be.getPos(), lines);
                currentSigns.add(be.getPos());

                if (chatMessages.get() && !notified.contains(be.getPos())) {
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
        return text.getString();
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
        int i = 0;
        for (Text lineText : linesToRender) {
            boolean isMergedCountLine = entry.count > 1 && i == linesToRender.size() - 1;

            String line;
            // For the merged count line, always use the plain string to avoid legacy formatting issues.
            if (isMergedCountLine) {
                line = lineText.getString();
            } else {
                line = getTextContent(lineText);
            }

            double x = -tr.getWidth(line) / 2.0;

            SettingColor color;
            if (isMergedCountLine) {
                // The merged count line is always yellow.
                color = new SettingColor(255, 255, 0, 255);
            } else {
                color = textColor.get();
                if (useSignColor.get() && lineText.getStyle().getColor() != null) {
                    int rgb = lineText.getStyle().getColor().getRgb();
                    if (rgb != 0) {
                        color = new SettingColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
                    }
                }
            }

            tr.render(line, x, y, color, true);
            y += lh;
            i++;
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