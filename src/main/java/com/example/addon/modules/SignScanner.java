package com.example.addon.modules;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3d;

import com.example.addon.Tim;
import com.mojang.blaze3d.systems.RenderSystem;

import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HangingSignBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.item.DyeItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class SignScanner extends Module {
    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgFilters   = settings.createGroup("Filters");
    private final SettingGroup sgAutoSign  = settings.createGroup("Auto Sign");
    private final SettingGroup sgRender    = settings.createGroup("Render");
    private final SettingGroup sgHighlight = settings.createGroup("Highlight");

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum HighlightStyle {
        GLOW("Glow"),
        SPECTRAL("Spectral"),
        PULSE("Pulse");

        private final String displayName;
        HighlightStyle(String name) { this.displayName = name; }

        @Override
        public String toString() { return displayName; }
    }

    public enum DateFormat {
        Readable("Readable"),
        Short("Short");

        private final String displayName;
        DateFormat(String name) { this.displayName = name; }

        @Override
        public String toString() { return displayName; }
    }

    public enum DateLine {
        Line_1("Line 1"),
        Line_2("Line 2"),
        Line_3("Line 3"),
        Line_4("Line 4");

        private final String displayName;
        DateLine(String name) { this.displayName = name; }

        @Override
        public String toString() { return displayName; }
    }

    public enum MessageProfile {
        MESSAGE_1("Message 1"),
        MESSAGE_2("Message 2");

        private final String displayName;
        MessageProfile(String name) { this.displayName = name; }

        @Override
        public String toString() { return displayName; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Integer> chunks = sgGeneral.add(new IntSetting.Builder()
        .name("chunks")
        .description("Radius in chunks to scan for signs.")
        .defaultValue(16).min(1).sliderMax(64).build());

    private final Setting<Boolean> chatMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-messages")
        .description("Notify in chat when a sign is found.")
        .defaultValue(true).build());

    private final Setting<Boolean> cacheSignText = sgGeneral.add(new BoolSetting.Builder()
        .name("cache-sign-text")
        .description("Cache sign text to improve performance.")
        .defaultValue(true).build());

    private final Setting<Integer> updateInterval = sgGeneral.add(new IntSetting.Builder()
        .name("update-interval")
        .description("How often to scan for signs (in ticks).")
        .defaultValue(20).min(1).sliderMax(100)
        .visible(cacheSignText::get).build());

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Filters
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> censorship = sgFilters.add(new BoolSetting.Builder()
        .name("censorship")
        .description("Censors bad words on signs.")
        .defaultValue(true).build());

    private final Setting<List<String>> badWords = sgFilters.add(new StringListSetting.Builder()
        .name("banned-words")
        .description("List of words to censor.")
        .defaultValue(List.of("badword1", "badword2"))
        .visible(censorship::get).build());

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Auto Sign
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> autoSign = sgAutoSign.add(new BoolSetting.Builder()
        .name("auto-sign")
        .description("Writes configured text on signs automatically when the edit screen opens.")
        .defaultValue(false).build());

    private final Setting<MessageProfile> activeProfile = sgAutoSign.add(new EnumSetting.Builder<MessageProfile>()
        .name("active-profile")
        .description("Which message profile to use for Auto Sign.")
        .defaultValue(MessageProfile.MESSAGE_1)
        .visible(autoSign::get)
        .build());

    private final Setting<Keybind> switchProfileKey = sgAutoSign.add(new KeybindSetting.Builder()
        .name("switch-profile-key")
        .description("Press this key to swap between Message 1 and Message 2.")
        .defaultValue(Keybind.none())
        .visible(autoSign::get)
        .action(() -> {
            MessageProfile newProfile = activeProfile.get() == MessageProfile.MESSAGE_1 
                ? MessageProfile.MESSAGE_2 
                : MessageProfile.MESSAGE_1;
            activeProfile.set(newProfile);
            info("Switched Auto Sign profile to: " + newProfile.toString());
        })
        .build());

    private final Setting<List<String>> message1Lines = sgAutoSign.add(new StringListSetting.Builder()
        .name("message-1-lines")
        .description("The text to put on the sign for Message 1 (up to 4 lines).")
        .defaultValue(List.of("hello there,", "just a wander,", "- riths blahblah"))
        .visible(() -> autoSign.get() && activeProfile.get() == MessageProfile.MESSAGE_1)
        .build());

    private final Setting<List<String>> message2Lines = sgAutoSign.add(new StringListSetting.Builder()
        .name("message-2-lines")
        .description("The text to put on the sign for Message 2 (up to 4 lines).")
        .defaultValue(List.of("completely", "different", "message"))
        .visible(() -> autoSign.get() && activeProfile.get() == MessageProfile.MESSAGE_2)
        .build());

    private final Setting<Integer> editorDelay = sgAutoSign.add(new IntSetting.Builder()
        .name("editor-delay")
        .description("Ticks to wait before submitting the sign.")
        .defaultValue(8).min(1)
        .visible(autoSign::get).build());

    private final Setting<Boolean> autoGlowDye = sgAutoSign.add(new BoolSetting.Builder()
        .name("auto-glow-dye")
        .description("Automatically applies a glow ink sac and selected dye to the sign after editing.")
        .defaultValue(false)
        .visible(autoSign::get)
        .build());

    private final Setting<DyeColor> dyeColor = sgAutoSign.add(new EnumSetting.Builder<DyeColor>()
        .name("dye-color")
        .description("Which color dye to apply to the sign.")
        .defaultValue(DyeColor.WHITE)
        .visible(() -> autoSign.get() && autoGlowDye.get())
        .build());

    private final Setting<Boolean> autoDate = sgAutoSign.add(new BoolSetting.Builder()
        .name("auto-date")
        .description("Automatically stamps the current date onto the sign.")
        .defaultValue(false)
        .visible(autoSign::get)
        .build());

    private final Setting<DateFormat> dateFormat = sgAutoSign.add(new EnumSetting.Builder<DateFormat>()
        .name("date-format")
        .description("How the date is formatted.")
        .defaultValue(DateFormat.Readable)
        .visible(() -> autoSign.get() && autoDate.get())
        .build());

    private final Setting<DateLine> dateLine = sgAutoSign.add(new EnumSetting.Builder<DateLine>()
        .name("date-line")
        .description("Which line to place the date on.")
        .defaultValue(DateLine.Line_4)
        .visible(() -> autoSign.get() && autoDate.get())
        .build());

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Render
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> scale = sgRender.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the rendered text.")
        .defaultValue(1.5).min(0.1).sliderMax(5.0).build());

    private final Setting<SettingColor> textColor = sgRender.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Text color.")
        .defaultValue(new SettingColor(255, 255, 255, 255)).build());

    private final Setting<Boolean> useSignColor = sgRender.add(new BoolSetting.Builder()
        .name("use-sign-color")
        .description("Use the sign's dye color for text.")
        .defaultValue(false).build());

    private final Setting<Boolean> background = sgRender.add(new BoolSetting.Builder()
        .name("background")
        .description("Render a background behind the text.")
        .defaultValue(true).build());

    private final Setting<SettingColor> backgroundColor = sgRender.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color.")
        .defaultValue(new SettingColor(30, 30, 30, 160))
        .visible(background::get).build());

    private final Setting<HighlightStyle> highlightStyle = sgRender.add(new EnumSetting.Builder<HighlightStyle>()
        .name("highlight-style")
        .description("GLOW renders layered bloom around the panel. SPECTRAL renders a crisp outline like the spectral arrow effect. PULSE renders a fading bloom.")
        .defaultValue(HighlightStyle.GLOW)
        .visible(background::get).build());

    private final Setting<Boolean> merge = sgRender.add(new BoolSetting.Builder()
        .name("merge")
        .description("Merge signs that are close together.")
        .defaultValue(true).build());

    private final Setting<Double> mergeDistance = sgRender.add(new DoubleSetting.Builder()
        .name("merge-distance")
        .description("Distance in pixels to merge signs.")
        .defaultValue(20.0).min(0).sliderMax(100)
        .visible(merge::get).build());

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Highlight
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Integer> glowLayers = sgHighlight.add(new IntSetting.Builder()
        .name("glow-layers")
        .description("Number of bloom layers rendered around the background panel.")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> background.get() && (highlightStyle.get() == HighlightStyle.GLOW || highlightStyle.get() == HighlightStyle.PULSE)).build());

    private final Setting<Double> glowSpread = sgHighlight.add(new DoubleSetting.Builder()
        .name("glow-spread")
        .description("How far each bloom layer expands outward (in pixels).")
        .defaultValue(3.0).min(0.5).sliderMax(12.0)
        .visible(() -> background.get() && (highlightStyle.get() == HighlightStyle.GLOW || highlightStyle.get() == HighlightStyle.PULSE)).build());

    private final Setting<Integer> glowBaseAlpha = sgHighlight.add(new IntSetting.Builder()
        .name("glow-base-alpha")
        .description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(60).min(4).sliderMax(150)
        .visible(() -> background.get() && highlightStyle.get() == HighlightStyle.GLOW).build());

    private final Setting<SettingColor> glowColor = sgHighlight.add(new ColorSetting.Builder()
        .name("glow-color")
        .description("Color of the bloom glow.")
        .defaultValue(new SettingColor(100, 180, 255, 255))
        .visible(() -> background.get() && (highlightStyle.get() == HighlightStyle.GLOW || highlightStyle.get() == HighlightStyle.PULSE)).build());

    private final Setting<Double> pulseSpeed = sgHighlight.add(new DoubleSetting.Builder()
        .name("pulse-speed")
        .description("Pulse cycle speed. 1.0 = one full fade in/out per second.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMax(3.0)
        .visible(() -> background.get() && highlightStyle.get() == HighlightStyle.PULSE).build()
    );

    private final Setting<Integer> pulseMinAlpha = sgHighlight.add(new IntSetting.Builder()
        .name("pulse-min-alpha")
        .description("Lowest alpha reached during the pulse (0 = invisible).")
        .defaultValue(15).min(0).max(255).sliderMax(100)
        .visible(() -> background.get() && highlightStyle.get() == HighlightStyle.PULSE).build()
    );

    private final Setting<Integer> pulseMaxAlpha = sgHighlight.add(new IntSetting.Builder()
        .name("pulse-max-alpha")
        .description("Peak alpha reached during the pulse.")
        .defaultValue(220).min(15).max(255).sliderMax(255)
        .visible(() -> background.get() && highlightStyle.get() == HighlightStyle.PULSE).build()
    );

    private final Setting<SettingColor> spectralColor = sgHighlight.add(new ColorSetting.Builder()
        .name("spectral-color")
        .description("Color of the spectral outline border.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> background.get() && highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Double> spectralThickness = sgHighlight.add(new DoubleSetting.Builder()
        .name("thickness")
        .description("Thickness of the spectral border lines (in pixels).")
        .defaultValue(1.5).min(0.5).sliderMax(6.0)
        .visible(() -> background.get() && highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Double> spectralExpand = sgHighlight.add(new DoubleSetting.Builder()
        .name("expand")
        .description("How far the border sits beyond the panel edge (in pixels).")
        .defaultValue(2.0).min(0.0).sliderMax(10.0)
        .visible(() -> background.get() && highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Boolean> spectralPulse = sgHighlight.add(new BoolSetting.Builder()
        .name("pulse")
        .description("Pulsate the spectral border alpha over time, like the vanilla glowing effect.")
        .defaultValue(true)
        .visible(() -> background.get() && highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Integer> spectralFillAlpha = sgHighlight.add(new IntSetting.Builder()
        .name("fill-alpha")
        .description("Alpha of a faint tinted fill drawn inside the border (0 = border only).")
        .defaultValue(20).min(0).sliderMax(80)
        .visible(() -> background.get() && highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private final Map<BlockPos, List<Text>> signs    = new ConcurrentHashMap<>();
    private final Set<BlockPos>             notified = new HashSet<>();
    private int timer = 0;

    // AutoSign state
    private int         editTimer   = 0;
    private BlockEntity pendingSign = null;   

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public SignScanner() {
        super(Tim.CATEGORY, "sign-scanner", "Scans and displays sign text.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        signs.clear();
        notified.clear();
        timer         = 0;
        editTimer     = 0;
        pendingSign = null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick — AutoSign
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!autoSign.get() || pendingSign == null) return;

        editTimer++;
        if (editTimer >= editorDelay.get()) {
            finishEditing();
        }
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!autoSign.get()) return;

        if (event.screen instanceof AbstractSignEditScreen screen) {
            BlockEntity sign = extractSignEntity(screen);
            if (sign != null) {
                pendingSign = sign;
                editTimer   = 0;
            }
        } else {
            pendingSign = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick — Sign Scanning
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        double dist    = chunks.get() * 16.0;
        double rangeSq = dist * dist;
        signs.keySet().removeIf(pos -> pos.getSquaredDistance(mc.player.getPos()) > rangeSq);

        if (cacheSignText.get()) {
            if (timer > 0) { timer--; return; }
            timer = updateInterval.get();
        }

        try {
            Set<BlockPos> currentSigns = new HashSet<>();

            for (BlockEntity be : Utils.blockEntities()) {
                SignText[] texts = switch (be) {
                    case HangingSignBlockEntity h -> new SignText[]{ h.getFrontText(), h.getBackText() };
                    case SignBlockEntity s        -> new SignText[]{ s.getFrontText(), s.getBackText() };
                    default -> null;
                };
                if (texts == null) continue;
                if (be.getPos().getSquaredDistance(mc.player.getPos()) > rangeSq) continue;

                List<Text> lineList = new ArrayList<>();
                SignText front = texts[0], back = texts[1];
                if (censorship.get()) { front = censorSignText(front); back = censorSignText(back); }

                readSignText(front, lineList);
                readSignText(back,  lineList);

                // Hardcoded ignore empty
                if (lineList.stream().allMatch(t -> t.getString().isBlank())) continue;

                signs.put(be.getPos(), lineList);
                currentSigns.add(be.getPos());

                if (chatMessages.get() && !notified.contains(be.getPos())) {
                    List<String> ss = lineList.stream().map(Text::getString).filter(s -> !s.isBlank()).toList();
                    if (!ss.isEmpty()) info("Sign found: " + String.join(" | ", ss));
                    notified.add(be.getPos());
                }
            }

            signs.keySet().retainAll(currentSigns);
            notified.retainAll(currentSigns);

        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AutoSign — Finish Editing
    // ═══════════════════════════════════════════════════════════════════════════

    private void finishEditing() {
        if (pendingSign == null) return;

        BlockPos pos = pendingSign.getPos();

        List<String> configured = switch (activeProfile.get()) {
            case MESSAGE_1 -> message1Lines.get();
            case MESSAGE_2 -> message2Lines.get();
        };
        
        String[] rows = new String[4];
        for (int i = 0; i < 4; i++) {
            rows[i] = (i < configured.size()) ? configured.get(i) : "";
        }

        // Inject Date if enabled
        if (autoDate.get()) {
            int lineIndex = dateLine.get().ordinal(); // 0 to 3
            rows[lineIndex] = getCurrentDate();
        }

        mc.player.networkHandler.sendPacket(
            new UpdateSignC2SPacket(pos, true, rows[0], rows[1], rows[2], rows[3])
        );

        if (autoGlowDye.get()) {
            FindItemResult glowSac = InvUtils.findInHotbar(Items.GLOW_INK_SAC);
            if (glowSac.found()) {
                InvUtils.swap(glowSac.slot(), false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
                mc.player.swingHand(Hand.MAIN_HAND);
                InvUtils.swapBack();
            } else {
                error("Glow Ink Sac not found in hotbar. Disabling auto-glow-dye.");
                autoGlowDye.set(false);
            }

            Item dyeItem = DyeItem.byColor(dyeColor.get());
            FindItemResult dyeResult = InvUtils.findInHotbar(dyeItem);
            if (dyeResult.found()) {
                InvUtils.swap(dyeResult.slot(), false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
                mc.player.swingHand(Hand.MAIN_HAND);
                InvUtils.swapBack();
            } else {
                error("Selected dye (%s) not found in hotbar. Disabling auto-glow-dye.", dyeColor.get().getName());
                autoGlowDye.set(false);
            }
        }

        mc.player.closeHandledScreen();
        pendingSign = null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Auto Date Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private String getCurrentDate() {
        LocalDate date = LocalDate.now();
        if (dateFormat.get() == DateFormat.Short) {
            return date.format(DateTimeFormatter.ofPattern("dd/MM/yy"));
        } else {
            String daySuffix = getDaySuffix(date.getDayOfMonth());
            return date.format(DateTimeFormatter.ofPattern("d'" + daySuffix + "' MMMM yyyy"));
        }
    }

    private String getDaySuffix(int day) {
        if (day >= 11 && day <= 13) return "th";
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Reflection helper — 1.21+ Safe Entity Extraction
    // ═══════════════════════════════════════════════════════════════════════════

    private static BlockEntity extractSignEntity(AbstractSignEditScreen screen) {
        Class<?> cls = screen.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (SignBlockEntity.class.isAssignableFrom(f.getType()) || HangingSignBlockEntity.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return (BlockEntity) f.get(screen);
                    } catch (Exception ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Sign Text Helpers — Fixed for 1.21+ Text Color API
    // ═══════════════════════════════════════════════════════════════════════════

    private void readSignText(SignText signText, List<Text> output) {
        for (Text t : signText.getMessages(false)) {
            Text cleaned = cleanSignText(t);
            if (!cleaned.getString().trim().isEmpty()) output.add(cleaned);
        }
    }

    public boolean shouldCensor() { return isActive() && censorship.get(); }

    public SignText censorSignText(SignText signText) {
        SignText newText = signText;
        for (int i = 0; i < 4; i++) {
            newText = newText.withMessage(i, censorText(signText.getMessage(i, false)));
        }
        return newText;
    }

    private Text censorText(Text text) {
        TextContent content = text.getContent();
        if (content instanceof PlainTextContent ptc) content = PlainTextContent.of(censor(ptc.string()));
        MutableText result = MutableText.of(content).setStyle(text.getStyle());
        for (Text sibling : text.getSiblings()) result.append(censorText(sibling));
        return result;
    }

    public String censor(String input) {
        String working = input;
        for (String bad : badWords.get()) {
            try { if (working.matches("(?i).*" + bad + ".*")) return "****"; }
            catch (Exception ignored) {}
        }
        // Hardcoded redact coordinates
        working = working.replaceAll("-?\\d+[kKmM]?([\\s,]+-?\\d+[kKmM]?){1,2}", "XXXX");
        return working;
    }

    private Text cleanSignText(Text text) {
        return Text.literal(text.getString().replaceAll("§.", "")).setStyle(text.getStyle());
    }

    private String getTextContent(Text text) { return text.getString(); }

    // ═══════════════════════════════════════════════════════════════════════════
    // Render 2D
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null) return;
        TextRenderer tr = TextRenderer.get();
        List<SignEntry> entries = new ArrayList<>();

        try {
            for (Map.Entry<BlockPos, List<Text>> entry : signs.entrySet()) {
                BlockPos   pos      = entry.getKey();
                List<Text> lineList = entry.getValue();
                if (lineList.isEmpty()) continue;

                BlockEntity be = mc.world.getBlockEntity(pos);
                Vec3d vec = (be instanceof HangingSignBlockEntity)
                    ? Vec3d.ofCenter(pos).add(0, -0.2, 0)
                    : Vec3d.ofCenter(pos).add(0,  0.5, 0);

                Vector3d pos3d = new Vector3d(vec.x, vec.y, vec.z);
                if (!NametagUtils.to2D(pos3d, scale.get())) continue;
                entries.add(new SignEntry(pos, lineList, pos3d));
            }
        } catch (Exception ignored) {}

        entries.sort(Comparator.comparingDouble(e -> mc.player.squaredDistanceTo(e.pos.toCenterPos())));

        List<SignEntry> grouped    = new ArrayList<>();
        double          mergeDistSq = mergeDistance.get() * mergeDistance.get();

        for (SignEntry entry : entries) {
            if (!merge.get()) { grouped.add(entry); continue; }
            boolean merged = false;
            for (SignEntry group : grouped) {
                double dx = entry.pos3d.x - group.pos3d.x;
                double dy = entry.pos3d.y - group.pos3d.y;
                if (dx * dx + dy * dy <= mergeDistSq) { group.count++; merged = true; break; }
            }
            if (!merged) grouped.add(entry);
        }

        grouped.sort(Comparator.comparingDouble(e -> -mc.player.squaredDistanceTo(e.pos.toCenterPos())));

        try {
            for (SignEntry entry : grouped) renderSign(entry, event, tr);
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Sign Rendering
    // ═══════════════════════════════════════════════════════════════════════════

    private void renderSign(SignEntry entry, Render2DEvent event, TextRenderer tr) {
        NametagUtils.begin(entry.pos3d, event.drawContext);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        List<Text> linesToRender = new ArrayList<>(entry.lines);
        if (entry.count > 1) linesToRender.add(Text.literal(entry.count + " signs").formatted(Formatting.YELLOW));

        double lh       = tr.getHeight();
        double maxWidth = 0;
        for (Text t : linesToRender) maxWidth = Math.max(maxWidth, tr.getWidth(getTextContent(t)));
        double totalH = linesToRender.size() * lh;

        double pad = 4.0;
        double bw  = maxWidth + pad * 2;
        double bh  = totalH  + pad * 2;
        double bx  = -bw / 2.0;
        double by  = -totalH / 2.0 - pad;

        if (background.get()) {
            if (highlightStyle.get() == HighlightStyle.GLOW) {
                renderGlowHighlight(bx, by, bw, bh);
            } else if (highlightStyle.get() == HighlightStyle.PULSE) {
                renderPulseHighlight(bx, by, bw, bh);
            } else {
                renderSpectralHighlight(bx, by, bw, bh);
            }
            Renderer2D.COLOR.begin();
            Renderer2D.COLOR.quad(bx, by, bw, bh, backgroundColor.get());
            Renderer2D.COLOR.render(null);
        }

        tr.begin(1.0, false, true);
        double y = -(linesToRender.size() * lh) / 2.0;
        int i = 0;
        for (Text lineText : linesToRender) {
            boolean isMergedCountLine = entry.count > 1 && i == linesToRender.size() - 1;
            String line = getTextContent(lineText);
            double x    = -tr.getWidth(line) / 2.0;

            SettingColor color;
            if (isMergedCountLine) {
                color = new SettingColor(255, 255, 0, 255);
            } else {
                color = textColor.get();
                if (useSignColor.get() && lineText.getStyle().getColor() != null) {
                    int rgb = lineText.getStyle().getColor().getRgb();
                    if (rgb != 0) color = new SettingColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Glow Highlight
    // ═══════════════════════════════════════════════════════════════════════════

    private void renderGlowHighlight(double bx, double by, double bw, double bh) {
        int          layers    = glowLayers.get();
        double       spread    = glowSpread.get();
        int          baseAlpha = glowBaseAlpha.get();
        SettingColor gc        = glowColor.get();

        Renderer2D.COLOR.begin();
        for (int i = layers; i >= 1; i--) {
            double expansion  = spread * i;
            double t          = (double)(i - 1) / layers;
            int    layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - t * t)));
            Renderer2D.COLOR.quad(
                bx - expansion, by - expansion,
                bw + expansion * 2, bh + expansion * 2,
                withAlpha(gc, layerAlpha)
            );
        }
        Renderer2D.COLOR.render(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pulse Highlight
    // ═══════════════════════════════════════════════════════════════════════════

    private float getPulseFactor() {
        double speed = pulseSpeed.get();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float)((Math.sin(phase) + 1.0) * 0.5);
    }

    private int applyPulse(int baseAlpha) {
        float f = getPulseFactor();
        int min = pulseMinAlpha.get();
        int max = pulseMaxAlpha.get();
        return Math.min(255, Math.max(0, (int)(min + (max - min) * f)));
    }

    private void renderPulseHighlight(double bx, double by, double bw, double bh) {
        int layers = glowLayers.get();
        double spread = glowSpread.get();
        SettingColor gc = glowColor.get();

        int pa = applyPulse(gc.a);
        SettingColor pColor = withAlpha(gc, pa);

        Renderer2D.COLOR.begin();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double)(i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int)(pa * taper));
            Renderer2D.COLOR.quad(
                bx - expansion, by - expansion,
                bw + expansion * 2, bh + expansion * 2,
                withAlpha(pColor, layerAlpha)
            );
        }
        Renderer2D.COLOR.render(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Spectral Highlight
    // ═══════════════════════════════════════════════════════════════════════════

    private void renderSpectralHighlight(double bx, double by, double bw, double bh) {
        double       expand    = spectralExpand.get();
        double       thickness = spectralThickness.get();
        SettingColor sc        = spectralColor.get();

        double ox = bx - expand;
        double oy = by - expand;
        double ow = bw + expand * 2;
        double oh = bh + expand * 2;

        int lineAlpha = sc.a;
        int fillAlpha = spectralFillAlpha.get();
        if (spectralPulse.get()) {
            double pulse = 0.6 + 0.4 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 750.0 * Math.PI));
            lineAlpha = (int)(lineAlpha * pulse);
            fillAlpha = (int)(fillAlpha * pulse);
        }

        Renderer2D.COLOR.begin();

        if (fillAlpha > 0) {
            Renderer2D.COLOR.quad(ox, oy, ow, oh, withAlpha(sc, fillAlpha));
        }

        SettingColor lc = withAlpha(sc, lineAlpha);
        Renderer2D.COLOR.quad(ox,                oy,                 ow,        thickness, lc); 
        Renderer2D.COLOR.quad(ox,                oy + oh - thickness, ow,        thickness, lc); 
        Renderer2D.COLOR.quad(ox,                oy + thickness,      thickness, oh - thickness * 2, lc); 
        Renderer2D.COLOR.quad(ox + ow - thickness, oy + thickness,    thickness, oh - thickness * 2, lc); 

        Renderer2D.COLOR.render(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Color Helper
    // ═══════════════════════════════════════════════════════════════════════════

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SignEntry
    // ═══════════════════════════════════════════════════════════════════════════

    private static class SignEntry {
        BlockPos   pos;
        List<Text> lines;
        Vector3d   pos3d;
        int        count = 1;

        SignEntry(BlockPos pos, List<Text> lines, Vector3d pos3d) {
            this.pos   = pos;
            this.lines = lines;
            this.pos3d = pos3d;
        }
    }
}