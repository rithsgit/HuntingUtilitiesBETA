package com.example.addon.hud;
import com.example.addon.Tim;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;

public class SecondLifeHUD extends HudElement {

    public static final HudElementInfo<SecondLifeHUD> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "second-life",
        "Displays the total number of Totems of Undying across your entire inventory, with warning and critical colour thresholds.",
        SecondLifeHUD::new
    );

    // ── Enums ─────────────────────────────────────────────────────────────────

    /** Primary layout: count beside the icon (Left/Right) or stacked above it. */
    public enum CountSide { Left, Right, Above, Below }

    /** When CountSide is Above, which horizontal side the text aligns to. */
    public enum TextAlign { Left, Center, Right }

    public enum BarPosition { Above, Below, Left, Right }

    public enum LabelMode { Both, Icon, Text }

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final ItemStack TOTEM_STACK = new ItemStack(Items.TOTEM_OF_UNDYING);

    // ── Setting Groups ────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgLayout   = settings.createGroup("Layout");
    private final SettingGroup sgEfficiency = settings.createGroup("Efficiency");
    private final SettingGroup sgWarnings = settings.createGroup("Warnings");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> scale = sgLayout.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Overall scale of the HUD element.")
        .defaultValue(1.0).min(0.25).sliderRange(0.25, 4.0)
        .build()
    );

    private final Setting<LabelMode> labelMode = sgLayout.add(new EnumSetting.Builder<LabelMode>()
        .name("label-mode")
        .description("Show the icon, the count text, or both.")
        .defaultValue(LabelMode.Both)
        .build()
    );

    private final Setting<Double> iconScale = sgLayout.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("Scale of the totem icon independently from the text.")
        .defaultValue(1.5).min(0.5).sliderRange(0.5, 4.0)
        .visible(() -> labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> textScale = sgLayout.add(new DoubleSetting.Builder()
        .name("text-scale")
        .description("Scale of the count text independently from the icon.")
        .defaultValue(1.0).min(0.25).sliderRange(0.25, 4.0)
        .visible(() -> labelMode.get() != LabelMode.Icon)
        .build()
    );

    private final Setting<CountSide> countSide = sgLayout.add(new EnumSetting.Builder<CountSide>()
        .name("count-side")
        .description("Where the count appears relative to the icon.")
        .defaultValue(CountSide.Right)
        .visible(() -> labelMode.get() == LabelMode.Both)
        .build()
    );

    private final Setting<TextAlign> textAlign = sgLayout.add(new EnumSetting.Builder<TextAlign>()
        .name("text-align")
        .description("Horizontal alignment of the count text when stacked above or below the icon.")
        .defaultValue(TextAlign.Center)
        .visible(() -> labelMode.get() == LabelMode.Both && (countSide.get() == CountSide.Above || countSide.get() == CountSide.Below))
        .build()
    );

    private final Setting<Double> iconGapSetting = sgLayout.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between the icon and the count text.")
        .defaultValue(4.0).min(0).sliderRange(0, 16)
        .visible(() -> labelMode.get() == LabelMode.Both)
        .build()
    );

    private final Setting<Double> paddingH = sgLayout.add(new DoubleSetting.Builder()
        .name("padding-horizontal")
        .description("Left and right padding inside the element.")
        .defaultValue(4.0).min(0).sliderRange(0, 16)
        .build()
    );

    private final Setting<Double> paddingV = sgLayout.add(new DoubleSetting.Builder()
        .name("padding-vertical")
        .description("Top and bottom padding inside the element.")
        .defaultValue(2.0).min(0).sliderRange(0, 16)
        .build()
    );

    // ── Efficiency Settings ───────────────────────────────────────────────────

    private final Setting<Boolean> showPops = sgEfficiency.add(new BoolSetting.Builder()
        .name("show-pops")
        .description("Show the number of totems popped this session.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showEfficiency = sgEfficiency.add(new BoolSetting.Builder()
        .name("show-efficiency")
        .description("Show survival efficiency percentage (Remaining / (Remaining + Popped)).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showBar = sgEfficiency.add(new BoolSetting.Builder()
        .name("show-bar")
        .description("Show a color-coded efficiency bar.")
        .defaultValue(false)
        .build()
    );

    private final Setting<BarPosition> barPosition = sgEfficiency.add(new EnumSetting.Builder<BarPosition>()
        .name("bar-position")
        .description("Where the efficiency bar appears relative to the totem/text.")
        .defaultValue(BarPosition.Below)
        .visible(showBar::get)
        .build()
    );

    // ── Visibility ────────────────────────────────────────────────────────────

    private final Setting<Boolean> hideWhenZero = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-when-zero")
        .description("Hide the element entirely when you have no totems.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showCountPrefix = sgGeneral.add(new BoolSetting.Builder()
        .name("count-prefix")
        .description("Adds an 'x' before the totem count.")
        .defaultValue(true)
        .visible(() -> labelMode.get() != LabelMode.Icon)
        .build()
    );

    private final Setting<Boolean> textShadow = sgGeneral.add(new BoolSetting.Builder()
        .name("text-shadow")
        .description("Draw a shadow behind the count text.")
        .defaultValue(false)
        .visible(() -> labelMode.get() != LabelMode.Icon)
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Color for the count when above all warning thresholds.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    // ── Background ────────────────────────────────────────────────────────────

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

    // ═══════════════════════════════════════════════════════════════════════════
    // Warnings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Integer> warningCount = sgWarnings.add(new IntSetting.Builder()
        .name("warning-count")
        .description("Totem count at or below which the value turns the warning color.")
        .defaultValue(3).min(0).sliderRange(0, 20)
        .build()
    );

    private final Setting<SettingColor> warningColor = sgWarnings.add(new ColorSetting.Builder()
        .name("warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

    private final Setting<Integer> criticalCount = sgWarnings.add(new IntSetting.Builder()
        .name("critical-count")
        .description("Totem count at or below which the value turns the critical color.")
        .defaultValue(1).min(0).sliderRange(0, 10)
        .build()
    );

    private final Setting<SettingColor> criticalColor = sgWarnings.add(new ColorSetting.Builder()
        .name("critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255))
        .build()
    );

    private final Setting<Boolean> playSound = sgWarnings.add(new BoolSetting.Builder()
        .name("play-sound")
        .description("Play a warning sound when a totem is popped or count is low.")
        .defaultValue(true)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    private int sessionPops = 0;

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        sessionPops = 0;
        lastCount = -1;
    }

    private int lastCount = -1;

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null) return;
        if (event.packet instanceof EntityStatusS2CPacket packet && packet.getStatus() == 35) {
            if (packet.getEntity(mc.world) != null && packet.getEntity(mc.world).getId() == mc.player.getId()) {
                sessionPops++;
                if (playSound.get()) mc.player.playSound(net.minecraft.sound.SoundEvents.ITEM_TOTEM_USE, 0.5f, 0.8f);
            }
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public SecondLifeHUD() {
        super(INFO);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null) { setSize(0, 0); return; }

        int count = countTotems();

        // Play alert sound if count drops into warning/critical range
        if (lastCount != -1 && count < lastCount && count <= warningCount.get() && playSound.get()) {
            mc.player.playSound(net.minecraft.sound.SoundEvents.ENTITY_ITEM_BREAK, 1f, 0.5f);
        }
        lastCount = count;

        // ── Hide when zero ────────────────────────────────────────────────────
        if (count == 0 && hideWhenZero.get()) { setSize(0, 0); return; }

        double s          = scale.get();
        double padH       = paddingH.get() * s;
        double padV       = paddingV.get() * s;

        LabelMode mode = labelMode.get();
        boolean showIcon = mode != LabelMode.Text;
        boolean showText = mode != LabelMode.Icon;

        double iconSz  = showIcon ? 16.0 * iconScale.get() : 0;
        double ts      = textScale.get() * s;
        double iconGap = (showIcon && showText) ? iconGapSetting.get() * s : 0;

        String value   = (showCountPrefix.get() ? "x" : "") + count;
        double efficiency = 1.0;

        if (sessionPops > 0) {
            if (showPops.get()) value += " (" + sessionPops + ")";

            if (showEfficiency.get()) {
                double total = count + sessionPops;
                efficiency = (total == 0) ? 1.0 : ((double) count / total);
                value += String.format(" [%.0f%%]", efficiency * 100);
            }
        }

        double textW   = showText ? renderer.textWidth(value, textShadow.get(), ts) : 0;
        double textH   = showText ? renderer.textHeight(textShadow.get(), ts) : 0;

        CountSide side = countSide.get();
        boolean vertical = side == CountSide.Above || side == CountSide.Below;

        SettingColor col = valueColor.get();

        if (sessionPops >= 10) col = criticalColor.get();
        else if (sessionPops > 0) col = warningColor.get();
        else if (count <= criticalCount.get()) col = criticalColor.get();
        else if (count <= warningCount.get())  col = warningColor.get();

        // ── Measure ──
        double innerW, innerH;

        if (!showIcon || !showText) {
            innerW = Math.max(iconSz, textW);
            innerH = Math.max(iconSz, textH);
        } else if (vertical) { // text Above/Below icon
            innerW = Math.max(iconSz, textW);
            innerH = iconSz + iconGap + textH;
        } else {
            innerW = iconSz + iconGap + textW;
            innerH = Math.max(iconSz, textH);
        }

        double barSize = 3 * s;
        double barGap  = 3 * s;
        BarPosition bp = barPosition.get();
        boolean barVertical = bp == BarPosition.Left || bp == BarPosition.Right;

        double totalW = innerW + padH * 2;
        double totalH = innerH + padV * 2;

        if (showBar.get()) {
            if (barVertical) totalW += barSize + barGap;
            else             totalH += barSize + barGap;
        }

        setSize(totalW, totalH);

        // ── Render ──
        if (showBackground.get())
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());

        double contentX = x + padH;
        double contentY = y + padV;

        if (showBar.get()) {
            if (bp == BarPosition.Left) contentX += barSize + barGap;
            if (bp == BarPosition.Above) contentY += barSize + barGap;
        }

        if (showIcon) {
            double ix, iy;
            if (!showText) {
                ix = contentX + (innerW - iconSz) / 2.0;
                iy = contentY + (innerH - iconSz) / 2.0;
            } else if (vertical) {
                ix = contentX + (innerW - iconSz) / 2.0;
                iy = (side == CountSide.Above) ? contentY + textH + iconGap : contentY;
            } else {
                ix = (side == CountSide.Right) ? contentX : contentX + innerW - iconSz;
                iy = contentY + (innerH - iconSz) / 2.0;
            }
            renderer.item(TOTEM_STACK, (int) ix, (int) iy, iconScale.get().floatValue(), false);
        }

        if (showText) {
            double tx, ty;
            if (!showIcon) {
                tx = contentX + (innerW - textW) / 2.0;
                ty = contentY + (innerH - textH) / 2.0;
            } else if (vertical) {
                switch (textAlign.get()) {
                    case Right  -> tx = contentX + innerW - textW;
                    case Center -> tx = contentX + (innerW - textW) / 2.0;
                    default     -> tx = contentX;
                }
                ty = (side == CountSide.Above) ? contentY : contentY + iconSz + iconGap;
            } else {
                tx = (side == CountSide.Right) ? contentX + iconSz + iconGap : contentX;
                ty = contentY + (innerH - textH) / 2.0;
            }
            renderer.text(value, tx, ty, col, textShadow.get(), ts);
        }

        if (showBar.get()) {
            double bx, by, bw, bh;
            if (!barVertical) {
                bw = innerW;
                bh = barSize;
                bx = contentX;
                by = (bp == BarPosition.Above) ? contentY - barGap - barSize : contentY + innerH + barGap;

                renderer.quad(bx, by, bw, bh, new Color(0, 0, 0, 100));
                renderer.quad(bx, by, bw * efficiency, bh, col);
            } else {
                bw = barSize;
                bh = innerH;
                bx = (bp == BarPosition.Left) ? contentX - barGap - barSize : contentX + innerW + barGap;
                by = contentY;

                renderer.quad(bx, by, bw, bh, new Color(0, 0, 0, 100));
                double progressH = bh * efficiency;
                renderer.quad(bx, by + (bh - progressH), bw, progressH, col);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Count every Totem of Undying in the player's full inventory,
    // including the offhand and any item currently held on the cursor.
    // ─────────────────────────────────────────────────────────────────────────

    private int countTotems() {
        var inv = mc.player.getInventory();
        int total = 0;

        for (var list : java.util.List.of(inv.main, inv.offHand, inv.armor)) {
            for (ItemStack s : list) {
                if (s.isOf(Items.TOTEM_OF_UNDYING)) total += s.getCount();
            }
        }

        if (mc.player.currentScreenHandler != null) {
            if (mc.player.currentScreenHandler.getCursorStack().isOf(Items.TOTEM_OF_UNDYING)) total += mc.player.currentScreenHandler.getCursorStack().getCount();
        }

        return total;
    }
}