package com.example.addon.modules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.Tim;
import com.example.addon.hud.LastSeenPlayerHud;
import com.example.addon.utils.GlowingRegistry;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.utils.render.WireframeEntityRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

public class NeighbourhoodWatch extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum PlayerStatus { Friend, Enemy, Proxy, Other }

    public enum TabEvent   { Join, Leave, Both }
    public enum TabFilter  { Friends, Enemies, Proxies, Others, All }
    public enum FilterMode { Censor, AutoIgnore }

    public enum HighlightMode {
        Wireframe("Wireframe"),
        Spectral("Spectral");

        private final String title;
        HighlightMode(String title) { this.title = title; }

        @Override public String toString() { return title; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgSafety      = settings.createGroup("Safety");
    private final SettingGroup sgMsgControl  = settings.createGroup("Message Control");
    private final SettingGroup sgTracking    = settings.createGroup("Player Tracking");
    private final SettingGroup sgFriends     = settings.createGroup("Friends & Enemies");
    private final SettingGroup sgTabList     = settings.createGroup("Tab List Monitoring");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Safety
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> disconnectOnPlayer = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-player")
        .description("Disconnects when another player is detected nearby.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> playerDetectionRange = sgSafety.add(new IntSetting.Builder()
        .name("player-detection-range")
        .description("Distance within which a player triggers a disconnect.")
        .defaultValue(32).min(1).sliderMax(128)
        .visible(disconnectOnPlayer::get)
        .build()
    );

    private final Setting<Boolean> ignoreFriendsOnDisconnect = sgSafety.add(new BoolSetting.Builder()
        .name("ignore-friends-on-disconnect")
        .description("Does not disconnect if the nearby player is a friend.")
        .defaultValue(true)
        .visible(disconnectOnPlayer::get)
        .build()
    );

    private final Setting<Boolean> ignoreProxiesOnDisconnect = sgSafety.add(new BoolSetting.Builder()
        .name("ignore-proxies-on-disconnect")
        .description("Does not disconnect if the nearby player is a proxy.")
        .defaultValue(true)
        .visible(disconnectOnPlayer::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Message Control
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<FilterMode> filterMode = sgMsgControl.add(new EnumSetting.Builder<FilterMode>()
        .name("mode")
        .description("Censor: replaces matched keywords with XXXX. AutoIgnore: runs /ignorehard on the sender.")
        .defaultValue(FilterMode.Censor)
        .build()
    );

    private final Setting<List<String>> ignoreKeywords = sgMsgControl.add(new StringListSetting.Builder()
        .name("keywords")
        .description("Words to act on. Censor mode redacts them; AutoIgnore mode silences the sender. Case-insensitive.")
        .defaultValue(List.of())
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Player Tracking
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> trackPlayers = sgTracking.add(new BoolSetting.Builder()
        .name("track-players")
        .description("Highlights and notifies when players enter visual range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> trackRange = sgTracking.add(new IntSetting.Builder()
        .name("track-range")
        .description("Distance within which players are tracked.")
        .defaultValue(128).min(1).sliderMax(256)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<TabFilter> trackFilter = sgTracking.add(new EnumSetting.Builder<TabFilter>()
        .name("track-filter")
        .description("Which player category to highlight and notify for.")
        .defaultValue(TabFilter.Enemies)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<Boolean> notifyChat = sgTracking.add(new BoolSetting.Builder()
        .name("notify-chat").description("Send a chat message when a player enters range.")
        .defaultValue(true).visible(trackPlayers::get)
        .build()
    );

    private final Setting<String> customMessage = sgTracking.add(new StringSetting.Builder()
        .name("custom-message")
        .description("Notification message. Use {player} for name and {status} for relation.")
        .defaultValue("Warning: {status} {player} is in visual range!")
        .visible(() -> trackPlayers.get() && notifyChat.get())
        .build()
    );

    private final Setting<Boolean> playSound = sgTracking.add(new BoolSetting.Builder()
        .name("play-sound").description("Play a sound when a player enters range.")
        .defaultValue(false).visible(trackPlayers::get)
        .build()
    );

    // ── Highlight rendering ───────────────────────────────────────────────────

    private final Setting<HighlightMode> highlightMode = sgTracking.add(new EnumSetting.Builder<HighlightMode>()
        .name("highlight-mode")
        .description("Wireframe draws custom geometry. Spectral uses the vanilla glow pipeline.")
        .defaultValue(HighlightMode.Wireframe)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<Double> outlineScale = sgTracking.add(new DoubleSetting.Builder()
        .name("outline-scale")
        .description("Scale of the wireframe outline (Wireframe mode only). 1.0 = exact model size.")
        .defaultValue(1.02).min(1.0).sliderMax(2.0)
        .visible(() -> trackPlayers.get() && highlightMode.get() == HighlightMode.Wireframe)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Friends & Enemies
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<List<String>> friends = sgFriends.add(new StringListSetting.Builder()
        .name("friends").description("Players treated as friends. Case-insensitive.")
        .defaultValue(List.of()).onChanged(l -> updateFriendEnemySets())
        .visible(this::isFriendCategoryVisible)
        .build()
    );

    private final Setting<SettingColor> friendColor = sgFriends.add(new ColorSetting.Builder()
        .name("friend-color").description("Highlight color for friends.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .visible(() -> trackPlayers.get() && isFriendCategoryVisible())
        .build()
    );

    private final Setting<List<String>> enemies = sgFriends.add(new StringListSetting.Builder()
        .name("enemies").description("Players treated as enemies. Case-insensitive.")
        .defaultValue(List.of()).onChanged(l -> updateFriendEnemySets())
        .visible(this::isEnemyCategoryVisible)
        .build()
    );

    private final Setting<SettingColor> enemyColor = sgFriends.add(new ColorSetting.Builder()
        .name("enemy-color").description("Highlight color for enemies.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(() -> trackPlayers.get() && isEnemyCategoryVisible())
        .build()
    );

    private final Setting<List<String>> proxies = sgFriends.add(new StringListSetting.Builder()
        .name("proxies").description("Players treated as proxies. Case-insensitive.")
        .defaultValue(List.of()).onChanged(l -> updateFriendEnemySets())
        .visible(this::isProxyCategoryVisible)
        .build()
    );

    private final Setting<SettingColor> proxyColor = sgFriends.add(new ColorSetting.Builder()
        .name("proxy-color").description("Highlight color for proxies.")
        .defaultValue(new SettingColor(255, 140, 0, 255))
        .visible(() -> trackPlayers.get() && isProxyCategoryVisible())
        .build()
    );

    private final Setting<SettingColor> otherColor = sgFriends.add(new ColorSetting.Builder()
        .name("other-color").description("Highlight color for unknown players.")
        .defaultValue(new SettingColor(139, 0, 0, 255))
        .visible(() -> trackPlayers.get() && isOtherCategoryVisible())
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Tab List Monitoring
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<TabEvent> tabEvent = sgTabList.add(new EnumSetting.Builder<TabEvent>()
        .name("event")
        .description("Which tab-list event to notify on.")
        .defaultValue(TabEvent.Both)
        .build()
    );

    private final Setting<TabFilter> tabFilter = sgTabList.add(new EnumSetting.Builder<TabFilter>()
        .name("notify-for")
        .description("Which player category triggers a notification.")
        .defaultValue(TabFilter.All)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private final Set<Integer> notifiedPlayers    = new HashSet<>();
    private final Set<Integer> activelyOutlined   = new HashSet<>();
    private final Set<String>  ignoredThisSession = new HashSet<>();
    private final Set<String>  playersInTab       = new HashSet<>();
    private final Set<String>  friendSet          = new HashSet<>();
    private final Set<String>  enemySet           = new HashSet<>();
    private final Set<String>  proxySet           = new HashSet<>();

    private boolean anyPlayerNearby = false;

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public NeighbourhoodWatch() {
        super(Tim.CATEGORY, "neighbourhood-watch",
            "Manages player tracking, safety, server monitoring, and keyword alerts.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        resetState();
        updateFriendEnemySets();
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getPlayerList().forEach(entry -> {
                String name = entry.getProfile().getName();
                if (name != null && !name.isEmpty()) playersInTab.add(name);
            });
        }
    }

    @Override
    public void onDeactivate() {
        clearAllOutlines();
        resetState();
        anyPlayerNearby = false;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        clearAllOutlines();
        resetState();
        anyPlayerNearby = false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (tickDisconnectOnPlayer()) return;
        tickPlayerTracking();
        tickOutlineShader();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Outline management (Spectral / GlowingRegistry)
    // ═══════════════════════════════════════════════════════════════════════════

    private void tickOutlineShader() {
        if (!trackPlayers.get()) {
            clearAllOutlines();
            return;
        }

        boolean spectral = highlightMode.get() == HighlightMode.Spectral;
        Set<Integer> newlyActive = new HashSet<>();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (mc.player.distanceTo(player) > trackRange.get()) continue;

            String       name   = player.getName().getString();
            PlayerStatus status = getPlayerStatusPublic(name);

            boolean shouldHighlight = trackFilter.get() == TabFilter.All || switch (status) {
                case Friend -> trackFilter.get() == TabFilter.Friends;
                case Enemy  -> trackFilter.get() == TabFilter.Enemies;
                case Proxy  -> trackFilter.get() == TabFilter.Proxies;
                case Other  -> trackFilter.get() == TabFilter.Others;
            };
            if (!shouldHighlight) continue;

            if (spectral) {
                SettingColor color = switch (status) {
                    case Friend -> friendColor.get();
                    case Enemy  -> enemyColor.get();
                    case Proxy  -> proxyColor.get();
                    case Other  -> otherColor.get();
                };
                GlowingRegistry.add(player.getId(), (255 << 24) | (color.r << 16) | (color.g << 8) | color.b);
            }
            
            newlyActive.add(player.getId());
        }

        for (int id : activelyOutlined) {
            if (!newlyActive.contains(id) || !spectral) {
                GlowingRegistry.remove(id);
            }
        }

        activelyOutlined.clear();
        activelyOutlined.addAll(newlyActive);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Render 3D — wireframe outline
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        if (trackPlayers.get() && highlightMode.get() == HighlightMode.Wireframe) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (!activelyOutlined.contains(player.getId())) continue;

                String       name   = player.getName().getString();
                PlayerStatus status = getPlayerStatusPublic(name);
                SettingColor color  = switch (status) {
                    case Friend -> friendColor.get();
                    case Enemy  -> enemyColor.get();
                    case Proxy  -> proxyColor.get();
                    case Other  -> otherColor.get();
                };

                WireframeEntityRenderer.render(
                    event, player, outlineScale.get(),
                    withAlpha(color, 0), color, ShapeMode.Lines
                );
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Packet Handler — Tab list
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerListS2CPacket packet)) return;

        for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
            if (entry.profile() == null) continue;
            String name = entry.profile().getName();
            if (name == null || name.isEmpty()) continue;

            if (packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
                if (playersInTab.add(name)) {
                    handleTabListChange(name, "joined");
                }
            } else if (packet.getActions().contains(PlayerListS2CPacket.Action.UPDATE_LISTED) && !entry.listed()) {
                if (playersInTab.remove(name)) {
                    handleTabListChange(name, "left");
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chat message listener — Message Control
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onReceiveMessage(meteordevelopment.meteorclient.events.game.ReceiveMessageEvent event) {
        if (mc.player == null || mc.player.networkHandler == null) return;
        if (ignoreKeywords.get().isEmpty()) return;

        if (filterMode.get() == FilterMode.AutoIgnore) {
            parseMessageForAutoIgnore(event.getMessage().getString());
        } else {
            String censored = censorMessage(event.getMessage().getString());
            if (censored != null) event.setMessage(Text.literal(censored));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean tickDisconnectOnPlayer() {
        if (!disconnectOnPlayer.get()) return false;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isCreative() || player.isSpectator()) continue;
            if (ignoreFriendsOnDisconnect.get()  && isFriend(player.getName().getString())) continue;
            if (ignoreProxiesOnDisconnect.get()  && isProxy(player.getName().getString()))  continue;
            if (mc.player.distanceTo(player) <= playerDetectionRange.get()) {
                disconnect("[NeighbourhoodWatch] Player detected: " + player.getName().getString());
                return true;
            }
        }
        return false;
    }

    private void tickPlayerTracking() {
        if (!trackPlayers.get()) {
            anyPlayerNearby = false;
            return;
        }

        anyPlayerNearby = false;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (mc.player.distanceTo(player) > trackRange.get()) continue;

            anyPlayerNearby = true;

            String       name   = player.getName().getString();
            PlayerStatus status = getPlayerStatusPublic(name);

            boolean isNewlySpotted = notifiedPlayers.add(player.getId());

            if (isNewlySpotted) {
                // ── Update Last Seen HUD directly ──
                Hud hudSystem = Systems.get(Hud.class);
                if (hudSystem != null) {
                    for (HudElement element : hudSystem) {
                        if (element instanceof LastSeenPlayerHud lastSeenHud) {
                            lastSeenHud.updateLastSeen(player);
                            break;
                        }
                    }
                }
            }

            boolean shouldNotify = trackFilter.get() == TabFilter.All || switch (status) {
                case Friend -> trackFilter.get() == TabFilter.Friends;
                case Enemy  -> trackFilter.get() == TabFilter.Enemies;
                case Proxy  -> trackFilter.get() == TabFilter.Proxies;
                case Other  -> trackFilter.get() == TabFilter.Others;
            };
            if (!shouldNotify) continue;

            if (isNewlySpotted) {
                if (notifyChat.get()) {
                    String statusStr = status.name().toLowerCase();
                    String msg = customMessage.get()
                        .replace("{player}", name)
                        .replace("{status}", statusStr);
                    info(msg);
                }
                if (playSound.get()) {
                    mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
                }
            }
        }
        notifiedPlayers.removeIf(id -> mc.world.getEntityById(id) == null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tab List
    // ═══════════════════════════════════════════════════════════════════════════

    private void handleTabListChange(String playerName, String action) {
        PlayerStatus status = getPlayerStatusPublic(playerName);

        if (tabEvent.get() != TabEvent.Both) {
            TabEvent eventType = action.equals("joined") ? TabEvent.Join : TabEvent.Leave;
            if (tabEvent.get() != eventType) return;
        }

        boolean shouldNotify = tabFilter.get() == TabFilter.All || switch (status) {
            case Friend -> tabFilter.get() == TabFilter.Friends;
            case Enemy  -> tabFilter.get() == TabFilter.Enemies;
            case Proxy  -> tabFilter.get() == TabFilter.Proxies;
            case Other  -> tabFilter.get() == TabFilter.Others;
        };
        if (!shouldNotify) return;

        String label = switch (status) {
            case Friend -> "§aFriend";
            case Enemy  -> "§cEnemy";
            case Proxy  -> "§6Proxy";
            case Other  -> "Player";
        };
        info("%s %s has %s the server.", label, playerName, action);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chat Parsing — Message Control
    // ═══════════════════════════════════════════════════════════════════════════

    private String[] parseSenderAndBody(String rawMessage) {
        if (rawMessage.startsWith("<")) {
            int close = rawMessage.indexOf('>');
            if (close < 1) return null;
            return new String[]{ rawMessage.substring(1, close).trim(),
                                 rawMessage.substring(close + 1).trim() };
        }
        int colon = rawMessage.indexOf(':');
        if (colon < 1 || colon >= 20) return null;
        String name = rawMessage.substring(0, colon);
        if (name.contains(" ")) return null;
        return new String[]{ name.trim(), rawMessage.substring(colon + 1).trim() };
    }

    private String findKeyword(String body) {
        String search = body.toLowerCase();
        for (String kw : ignoreKeywords.get()) {
            if (kw.isBlank()) continue;
            if (search.contains(kw.toLowerCase())) return kw;
        }
        return null;
    }

    private String censorMessage(String rawMessage) {
        String  working = rawMessage;
        boolean changed = false;
        for (String kw : ignoreKeywords.get()) {
            if (kw.isBlank()) continue;
            String replacement = "X".repeat(kw.length());
            String replaced = working.replaceAll("(?i)" + java.util.regex.Pattern.quote(kw), replacement);
            if (!replaced.equals(working)) { working = replaced; changed = true; }
        }
        return changed ? working : null;
    }

    private void parseMessageForAutoIgnore(String rawMessage) {
        String[] parts = parseSenderAndBody(rawMessage);
        if (parts == null) return;
        String sender = parts[0], messageBody = parts[1];

        if (sender.equalsIgnoreCase(mc.player.getName().getString())) return;
        if (isFriend(sender) || isProxy(sender)) return;
        if (ignoredThisSession.contains(sender.toLowerCase())) return;
        if (findKeyword(messageBody) == null) return;

        mc.player.networkHandler.sendChatCommand("ignorehard " + sender);
        ignoredThisSession.add(sender.toLowerCase());
        info("Auto-ignored %s (keyword match).", sender);
    }

    private void clearAllOutlines() {
        for (int id : activelyOutlined) {
            GlowingRegistry.remove(id);
        }
        activelyOutlined.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // General Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private void resetState() {
        notifiedPlayers.clear();
        ignoredThisSession.clear();
        playersInTab.clear();
    }

    private void updateFriendEnemySets() {
        friendSet.clear();
        for (String name : friends.get()) friendSet.add(name.toLowerCase());
        enemySet.clear();
        for (String name : enemies.get()) enemySet.add(name.toLowerCase());
        proxySet.clear();
        for (String name : proxies.get()) proxySet.add(name.toLowerCase());
    }

    private void disconnect(String reason) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getConnection().disconnect(Text.literal(reason));
        }
        this.toggle();
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Category Visibility Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isFriendCategoryVisible() {
        return trackFilter.get() == TabFilter.Friends || trackFilter.get() == TabFilter.All
            || tabFilter.get()   == TabFilter.Friends || tabFilter.get()   == TabFilter.All;
    }

    private boolean isEnemyCategoryVisible() {
        return trackFilter.get() == TabFilter.Enemies || trackFilter.get() == TabFilter.All
            || tabFilter.get()   == TabFilter.Enemies || tabFilter.get()   == TabFilter.All;
    }

    private boolean isProxyCategoryVisible() {
        return trackFilter.get() == TabFilter.Proxies || trackFilter.get() == TabFilter.All
            || tabFilter.get()   == TabFilter.Proxies || tabFilter.get()   == TabFilter.All;
    }

    private boolean isOtherCategoryVisible() {
        return trackFilter.get() == TabFilter.Others || trackFilter.get() == TabFilter.All
            || tabFilter.get()   == TabFilter.Others  || tabFilter.get()   == TabFilter.All;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════════

    public boolean isFriend(String name) { return name != null && friendSet.contains(name.toLowerCase()); }
    public boolean isEnemy(String name)  { return name != null && enemySet.contains(name.toLowerCase()); }
    public boolean isProxy(String name)  { return name != null && proxySet.contains(name.toLowerCase()); }

    public PlayerStatus getPlayerStatusPublic(String name) {
        if (isFriend(name)) return PlayerStatus.Friend;
        if (isEnemy(name))  return PlayerStatus.Enemy;
        if (isProxy(name))  return PlayerStatus.Proxy;
        return PlayerStatus.Other;
    }

    public boolean isDisconnectOnPlayerArmed() {
        return disconnectOnPlayer.get();
    }

    /** Exposes whether a player is actively inside tracking range. Used by the HUD. */
    public boolean isAnyPlayerNearby() {
        return anyPlayerNearby;
    }
}