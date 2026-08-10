package com.example.addon.hud;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.Tim;
import com.example.addon.modules.NeighbourhoodWatch;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class LastSeenPlayerHud extends HudElement {

    public static final HudElementInfo<LastSeenPlayerHud> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "last-seen-player",
        "Displays the name of the last player you saw and how long ago it was.",
        LastSeenPlayerHud::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static final Map<UUID, ItemStack> CACHED_HEADS = new ConcurrentHashMap<>();

    public static ItemStack getPlayerHead(com.mojang.authlib.GameProfile profile) {
        if (profile == null || profile.getId() == null) return ItemStack.EMPTY;
        return CACHED_HEADS.computeIfAbsent(profile.getId(), id -> {
            ItemStack headStack = new ItemStack(Items.PLAYER_HEAD);
            headStack.set(DataComponentTypes.PROFILE, new ProfileComponent(profile));
            return headStack;
        });
    }

    public enum Alignment { Left, Center, Right }
    public enum TimeFormat { Smart, Seconds, None }

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> censorshipMode = sgGeneral.add(new BoolSetting.Builder()
        .name("censorship-mode")
        .description("Hides the player's name with X's, matching the name's length.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0).min(0.25).sliderRange(0.25, 4.0)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align content to the left, center, or right within the element.")
        .defaultValue(Alignment.Left)
        .build()
    );

    private final Setting<String> prefix = sgGeneral.add(new StringSetting.Builder()
        .name("prefix")
        .description("Text to show before the player's name.")
        .defaultValue("Last Seen:")
        .build()
    );

    private final Setting<TimeFormat> timeFormat = sgGeneral.add(new EnumSetting.Builder<TimeFormat>()
        .name("time-format")
        .description("How to format the time since the player was seen.")
        .defaultValue(TimeFormat.Smart)
        .build()
    );

    private final Setting<Boolean> showIcon = sgGeneral.add(new BoolSetting.Builder()
        .name("show-icon")
        .description("Shows the player's face next to their name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> iconScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("Scale of the player icon.")
        .defaultValue(1.0).min(0.5).sliderRange(0.5, 4.0)
        .visible(showIcon::get)
        .build()
    );

    private final Setting<Double> iconGap = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between the icon and the text.")
        .defaultValue(4.0).min(0).sliderRange(0, 16)
        .visible(showIcon::get)
        .build()
    );

    private final Setting<SettingColor> prefixColor = sgGeneral.add(new ColorSetting.Builder()
        .name("prefix-color")
        .description("Color for the prefix text.")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> nameColor = sgGeneral.add(new ColorSetting.Builder()
        .name("name-color")
        .description("Color for the player's name.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> timeColor = sgGeneral.add(new ColorSetting.Builder()
        .name("time-color")
        .description("Color for the time text.")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

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
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private String lastPlayerName = "None";
    private String displayName = "None";
    private boolean wasCensorshipEnabled = false;
    private long lastSeenTime = 0;
    private ItemStack headStack = ItemStack.EMPTY;

    private String cachedTimeText = "";
    private long lastTimeUpdateSecond = -1;

    public LastSeenPlayerHud() {
        super(INFO);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Data Input (Called by NeighbourhoodWatch)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Called directly by NeighbourhoodWatch when a player enters tracking range.
     */
    public void updateLastSeen(PlayerEntity player) {
        String name = player.getName().getString();
        this.lastPlayerName = name;
        this.lastSeenTime = System.currentTimeMillis();
        this.lastTimeUpdateSecond = -1;
        
        boolean currentCensor = censorshipMode.get();
        this.displayName = currentCensor && !name.equals("None")
            ? "X".repeat(name.length())
            : name;

        this.headStack = new ItemStack(Items.PLAYER_HEAD);
        this.headStack.set(DataComponentTypes.PROFILE, new ProfileComponent(player.getGameProfile()));
        
        getPlayerHead(player.getGameProfile());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Events
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        lastPlayerName = "None";
        displayName = "None";
        lastSeenTime = 0;
        headStack = ItemStack.EMPTY;
        cachedTimeText = "";
        lastTimeUpdateSecond = -1;
        CACHED_HEADS.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Render
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void render(HudRenderer renderer) {
        boolean inEditor = isInEditor();

        // Rely completely on NeighbourhoodWatch's scan
        NeighbourhoodWatch nw = Modules.get().get(NeighbourhoodWatch.class);
        boolean anyPlayerNearby = nw != null && nw.isActive() && nw.isAnyPlayerNearby();

        if (!inEditor && (mc.player == null || mc.world == null || !anyPlayerNearby)) {
            setSize(0, 0);
            return;
        }

        String renderName;
        if (inEditor && !anyPlayerNearby && lastPlayerName.equals("None")) {
            renderName = "PlayerName";
        } else {
            renderName = displayName;
        }

        boolean currentCensor = censorshipMode.get();
        if (currentCensor != wasCensorshipEnabled) {
            wasCensorshipEnabled = currentCensor;
            displayName = currentCensor && !lastPlayerName.equals("None")
                ? "X".repeat(lastPlayerName.length())
                : lastPlayerName;
            renderName = displayName;
        }

        if (timeFormat.get() != TimeFormat.None && !lastPlayerName.equals("None")) {
            if (lastSeenTime > 0) {
                long diff = System.currentTimeMillis() - lastSeenTime;
                long currentSecond = diff / 1000;
                if (currentSecond != lastTimeUpdateSecond) {
                    lastTimeUpdateSecond = currentSecond;
                    cachedTimeText = " (" + formatTime(diff) + " ago)";
                }
            } else if (!cachedTimeText.equals(" (Unknown)")) {
                cachedTimeText = " (Unknown)";
            }
        } else if (inEditor && !anyPlayerNearby) {
            cachedTimeText = " (5s ago)";
        } else {
            cachedTimeText = "";
        }

        double s = scale.get();
        double padH = 4 * s;
        double padV = 2 * s;
        double lineHeight = renderer.textHeight(false, s);

        boolean drawIcon = showIcon.get() && (!headStack.isEmpty() || inEditor) && !lastPlayerName.equals("None");
        ItemStack iconStack = (inEditor && headStack.isEmpty()) ? new ItemStack(Items.PLAYER_HEAD) : headStack;

        double iconSize = drawIcon ? 16.0 * iconScale.get() : 0;
        double gap = drawIcon ? iconGap.get() * s : 0;

        String prefixText = prefix.get().isEmpty() ? "" : prefix.get() + " ";

        double prefixW = renderer.textWidth(prefixText, false, s);
        double nameW   = renderer.textWidth(renderName, false, s);
        double timeW   = renderer.textWidth(cachedTimeText, false, s);

        double totalTextW = prefixW + nameW + timeW;
        double contentW = totalTextW + (drawIcon ? iconSize + gap : 0);

        double totalW = contentW + padH * 2;
        double totalH = Math.max(lineHeight, drawIcon ? iconSize : 0) + padV * 2;

        if (showBackground.get() || inEditor) {
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());
        }

        Alignment align = alignment.get();
        double contentX;

        if (align == Alignment.Left) {
            contentX = x + padH;
        } else if (align == Alignment.Center) {
            contentX = x + (totalW - contentW) / 2.0;
        } else {
            contentX = x + totalW - padH - contentW;
        }

        double currentX = contentX;
        double textY = y + padV + (totalH - padV * 2 - lineHeight) / 2.0;
        double iconY = y + padV + (totalH - padV * 2 - iconSize) / 2.0;

        if (drawIcon) {
            renderer.item(iconStack, (int) currentX, (int) iconY, iconScale.get().floatValue(), false);
            currentX += iconSize + gap;
        }

        if (!prefixText.isEmpty()) {
            renderer.text(prefixText, currentX, textY, prefixColor.get(), false, s);
            currentX += prefixW;
        }

        renderer.text(renderName, currentX, textY, nameColor.get(), false, s);
        currentX += nameW;

        if (!cachedTimeText.isEmpty()) {
            renderer.text(cachedTimeText, currentX, textY, timeColor.get(), false, s);
        }

        setSize(totalW, totalH);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private String formatTime(long millis) {
        long seconds = millis / 1000;

        if (timeFormat.get() == TimeFormat.Seconds) {
            return seconds + "s";
        }

        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return minutes + "m " + secs + "s";
        } else {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return hours + "h " + minutes + "m";
        }
    }
}