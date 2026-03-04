package com.example.addon.modules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

public class NeighbourhoodWatch extends Module {

    // ── Setting Groups ────────────────────────────────────────────────────────

    private final SettingGroup sgSafety     = settings.createGroup("Safety");
    private final SettingGroup sgAutoIgnore = settings.createGroup("Auto Ignore");
    private final SettingGroup sgTracking   = settings.createGroup("Player Tracking");
    private final SettingGroup sgFriends    = settings.createGroup("Friends & Enemies");
    private final SettingGroup sgTabList    = settings.createGroup("Tab List Monitoring");

    // ── Safety ────────────────────────────────────────────────────────────────

    private final Setting<Boolean> disconnectOnPlayer = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-player")
        .description("Disconnects when another player is detected nearby.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> playerDetectionRange = sgSafety.add(new IntSetting.Builder()
        .name("player-detection-range")
        .description("Distance within which a player triggers a disconnect.")
        .defaultValue(32)
        .min(1)
        .sliderMax(128)
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

    // ── Auto Ignore ───────────────────────────────────────────────────────────

    private final Setting<Boolean> autoIgnore = sgAutoIgnore.add(new BoolSetting.Builder()
        .name("auto-ignore")
        .description("Runs /ignorehard on players who say certain keywords in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> ignoreKeywords = sgAutoIgnore.add(new StringListSetting.Builder()
        .name("keywords")
        .description("Players who use any of these words in their message will be /ignorehard'd.")
        .defaultValue(List.of())
        .visible(autoIgnore::get)
        .build()
    );

    private final Setting<Boolean> ignoreCaseSensitive = sgAutoIgnore.add(new BoolSetting.Builder()
        .name("case-sensitive")
        .description("Match keywords with case sensitivity.")
        .defaultValue(false)
        .visible(autoIgnore::get)
        .build()
    );

    private final Setting<Boolean> ignoreNotify = sgAutoIgnore.add(new BoolSetting.Builder()
        .name("notify")
        .description("Print a local message when a player is auto-ignored.")
        .defaultValue(true)
        .visible(autoIgnore::get)
        .build()
    );

    // ── Player Tracking ───────────────────────────────────────────────────────

    private final Setting<Boolean> trackPlayers = sgTracking.add(new BoolSetting.Builder()
        .name("track-players")
        .description("Highlights and notifies when players enter visual range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> trackRange = sgTracking.add(new IntSetting.Builder()
        .name("track-range")
        .description("Distance within which players are tracked.")
        .defaultValue(128)
        .min(1)
        .sliderMax(256)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<Boolean> trackFriends = sgTracking.add(new BoolSetting.Builder()
        .name("track-friends")
        .description("Highlight friends in range.")
        .defaultValue(true)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<Boolean> trackEnemies = sgTracking.add(new BoolSetting.Builder()
        .name("track-enemies")
        .description("Highlight enemies in range.")
        .defaultValue(true)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<Boolean> trackProxies = sgTracking.add(new BoolSetting.Builder()
        .name("track-proxies")
        .description("Highlight proxy accounts in range.")
        .defaultValue(true)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<Boolean> trackOthers = sgTracking.add(new BoolSetting.Builder()
        .name("track-others")
        .description("Highlight unknown players in range.")
        .defaultValue(true)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<ShapeMode> trackingShapeMode = sgTracking.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How player highlight shapes are rendered.")
        .defaultValue(ShapeMode.Lines)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<Boolean> notifyChat = sgTracking.add(new BoolSetting.Builder()
        .name("notify-chat")
        .description("Send a chat message when a player enters range.")
        .defaultValue(true)
        .visible(trackPlayers::get)
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
        .name("play-sound")
        .description("Play a sound when a player enters range.")
        .defaultValue(false)
        .visible(trackPlayers::get)
        .build()
    );

    // ── Friends & Enemies ─────────────────────────────────────────────────────

    private final Setting<List<String>> friends = sgFriends.add(new StringListSetting.Builder()
        .name("friends")
        .description("Players treated as friends. Case-insensitive.")
        .defaultValue(List.of())
        .onChanged(l -> updateFriendEnemySets())
        .build()
    );

    private final Setting<List<String>> enemies = sgFriends.add(new StringListSetting.Builder()
        .name("enemies")
        .description("Players treated as enemies. Case-insensitive.")
        .defaultValue(List.of())
        .onChanged(l -> updateFriendEnemySets())
        .build()
    );

    private final Setting<List<String>> proxies = sgFriends.add(new StringListSetting.Builder()
        .name("proxies")
        .description("Players treated as proxies. Case-insensitive.")
        .defaultValue(List.of())
        .onChanged(l -> updateFriendEnemySets())
        .build()
    );

    private final Setting<SettingColor> friendColor = sgFriends.add(new ColorSetting.Builder()
        .name("friend-color")
        .description("Highlight color for friends.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<SettingColor> enemyColor = sgFriends.add(new ColorSetting.Builder()
        .name("enemy-color")
        .description("Highlight color for enemies.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<SettingColor> proxyColor = sgFriends.add(new ColorSetting.Builder()
        .name("proxy-color")
        .description("Highlight color for proxies.")
        .defaultValue(new SettingColor(255, 140, 0, 255))
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<SettingColor> otherColor = sgFriends.add(new ColorSetting.Builder()
        .name("other-color")
        .description("Highlight color for unknown players.")
        .defaultValue(new SettingColor(139, 0, 0, 255))
        .visible(trackPlayers::get)
        .build()
    );

    // ── Tab List Monitoring ───────────────────────────────────────────────────

    private final Setting<Boolean> monitorTabList = sgTabList.add(new BoolSetting.Builder()
        .name("monitor-tab-list")
        .description("Notifies when players join or leave the server via the tab list.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> notifyOnJoin = sgTabList.add(new BoolSetting.Builder()
        .name("notify-on-join")
        .description("Notify when a player joins the server.")
        .defaultValue(true)
        .visible(monitorTabList::get)
        .build()
    );

    private final Setting<Boolean> notifyOnLeave = sgTabList.add(new BoolSetting.Builder()
        .name("notify-on-leave")
        .description("Notify when a player leaves the server.")
        .defaultValue(true)
        .visible(monitorTabList::get)
        .build()
    );

    private final Setting<Boolean> tabNotifyFriends = sgTabList.add(new BoolSetting.Builder()
        .name("notify-friends")
        .description("Notify when a friend joins or leaves.")
        .defaultValue(true)
        .visible(monitorTabList::get)
        .build()
    );

    private final Setting<Boolean> tabNotifyEnemies = sgTabList.add(new BoolSetting.Builder()
        .name("notify-enemies")
        .description("Notify when an enemy joins or leaves.")
        .defaultValue(true)
        .visible(monitorTabList::get)
        .build()
    );

    private final Setting<Boolean> tabNotifyProxies = sgTabList.add(new BoolSetting.Builder()
        .name("notify-proxies")
        .description("Notify when a proxy joins or leaves.")
        .defaultValue(true)
        .visible(monitorTabList::get)
        .build()
    );

    private final Setting<Boolean> tabNotifyOthers = sgTabList.add(new BoolSetting.Builder()
        .name("notify-others")
        .description("Notify when an unknown player joins or leaves.")
        .defaultValue(false)
        .visible(monitorTabList::get)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    private final Set<Integer> notifiedPlayers    = new HashSet<>();
    private final Set<String>  ignoredThisSession = new HashSet<>();
    private final Set<String>  playersInTab       = new HashSet<>();
    private final Set<String>  friendSet          = new HashSet<>();
    private final Set<String>  enemySet           = new HashSet<>();
    private final Set<String>  proxySet           = new HashSet<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public NeighbourhoodWatch() {
        super(HuntingUtilities.CATEGORY, "neighbourhood-watch",
            "Manages player tracking, safety, and server monitoring.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        resetState();
        updateFriendEnemySets();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        resetState();
    }

    // ── Core Logic ────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (tickDisconnectOnPlayer()) return; // disconnected — stop processing this tick
        tickPlayerTracking();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!trackPlayers.get() || mc.world == null || mc.player == null) return;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (mc.player.distanceTo(player) > trackRange.get()) continue;

            SettingColor color = switch (getPlayerStatus(player.getName().getString())) {
                case Friend -> trackFriends.get() ? friendColor.get() : null;
                case Enemy  -> trackEnemies.get() ? enemyColor.get()  : null;
                case Proxy  -> trackProxies.get() ? proxyColor.get()  : null;
                case Other  -> trackOthers.get()  ? otherColor.get()  : null;
            };

            if (color != null) {
                event.renderer.box(player.getBoundingBox(), color, color, trackingShapeMode.get(), 0);
            }
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!monitorTabList.get() || !(event.packet instanceof PlayerListS2CPacket packet)) return;

        // Note: Some servers (e.g., Paper) use UPDATE_LISTED with listed=false
        // to signify a player leaving, instead of the REMOVE_PLAYER action.
        for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
            if (entry.profile() == null) continue;
            String name = entry.profile().getName();
            if (name == null || name.isEmpty()) continue;

            if (packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
                if (playersInTab.add(name)) handleTabListChange(name, "joined");
            } else if (packet.getActions().contains(PlayerListS2CPacket.Action.UPDATE_LISTED) && !entry.listed()) {
                if (playersInTab.remove(name)) handleTabListChange(name, "left");
            }
        }
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!autoIgnore.get() || mc.player == null || mc.player.networkHandler == null) return;

        List<String> keywords = ignoreKeywords.get();
        if (keywords.isEmpty()) return;

        String raw = event.getMessage().getString();
        parseChatMessage(raw);
    }

    // ── Tick Logic ────────────────────────────────────────────────────────────

    /**
     * @return true if a disconnect was triggered
     */
    /** @return true if a disconnect was triggered */
    private boolean tickDisconnectOnPlayer() {
        if (!disconnectOnPlayer.get()) return false;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isCreative() || player.isSpectator()) continue;
            if (ignoreFriendsOnDisconnect.get() && isFriend(player.getName().getString())) continue;
            if (ignoreProxiesOnDisconnect.get() && isProxy(player.getName().getString())) continue;
            if (mc.player.distanceTo(player) <= playerDetectionRange.get()) {
                disconnect("[NeighbourhoodWatch] Player detected: " + player.getName().getString());
                return true;
            }
        }
        return false;
    }

    private void tickPlayerTracking() {
        if (!trackPlayers.get()) return;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (mc.player.distanceTo(player) > trackRange.get()) continue;

            if (notifiedPlayers.add(player.getId())) {
                if (notifyChat.get()) {
                    String playerName = player.getName().getString();
                    String status = getPlayerStatus(playerName).name().toLowerCase();
                    String msg = customMessage.get()
                        .replace("{player}", playerName)
                        .replace("{status}", status);
                    info(msg);
                }
                if (playSound.get()) {
                    mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
                }
            }
        }
        notifiedPlayers.removeIf(id -> mc.world.getEntityById(id) == null);
    }

    private void parseChatMessage(String rawMessage) {
        String sender, messageBody;

        if (rawMessage.startsWith("<")) {
            int close = rawMessage.indexOf('>');
            if (close < 1) return;
            sender      = rawMessage.substring(1, close).trim();
            messageBody = rawMessage.substring(close + 1).trim();
        } else {
            int colon = rawMessage.indexOf(':');
            // Check for a colon, but not too far in, to avoid parsing non-chat messages.
            // Minecraft names are max 16 chars, so 20 is a safe upper bound.
            if (colon < 1 || colon >= 20) return;
            String possibleName = rawMessage.substring(0, colon);
            // Ensure there are no spaces in the potential sender name.
            if (possibleName.contains(" ")) return;
            sender      = possibleName.trim();
            messageBody = rawMessage.substring(colon + 1).trim();
        }

        if (sender.equalsIgnoreCase(mc.player.getName().getString())) return;
        if (isFriend(sender) || isProxy(sender)) return;
        if (ignoredThisSession.contains(sender.toLowerCase())) return;

        boolean matched = false;
        for (String keyword : ignoreKeywords.get()) {
            if (keyword.isBlank()) continue;
            String body = ignoreCaseSensitive.get() ? messageBody : messageBody.toLowerCase();
            String kw   = ignoreCaseSensitive.get() ? keyword     : keyword.toLowerCase();
            if (body.contains(kw)) {
                matched = true;
                break;
            }
        }
        if (!matched) return;

        mc.player.networkHandler.sendChatCommand("ignorehard " + sender);
        ignoredThisSession.add(sender.toLowerCase());
        if (ignoreNotify.get()) info("Auto-ignored %s (keyword match).", sender);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Clears all transient session state. */
    private void resetState() {
        notifiedPlayers.clear();
        ignoredThisSession.clear();
        playersInTab.clear();
    }

    private void disconnect(String reason) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getConnection().disconnect(Text.literal(reason));
        }
        this.toggle();
    }

    private void handleTabListChange(String playerName, String action) {
        PlayerStatus status = getPlayerStatus(playerName);

        boolean shouldNotify = switch (status) {
            case Friend -> tabNotifyFriends.get();
            case Enemy  -> tabNotifyEnemies.get();
            case Proxy  -> tabNotifyProxies.get();
            case Other  -> tabNotifyOthers.get();
        };
        if (!shouldNotify) return;
        if (action.equals("joined") && !notifyOnJoin.get()) return;
        if (action.equals("left")   && !notifyOnLeave.get()) return;

        String label = switch (status) {
            case Friend -> "§aFriend";
            case Enemy  -> "§cEnemy";
            case Proxy  -> "§6Proxy";
            case Other  -> "Player";
        };
        info("%s %s has %s the server.", label, playerName, action);
    }

    private void updateFriendEnemySets() {
        friendSet.clear();
        for (String name : friends.get()) {
            friendSet.add(name.toLowerCase());
        }
        enemySet.clear();
        for (String name : enemies.get()) {
            enemySet.add(name.toLowerCase());
        }
        proxySet.clear();
        for (String name : proxies.get()) {
            proxySet.add(name.toLowerCase());
        }
    }

    public boolean isFriend(String name) {
        if (name == null) return false;
        return friendSet.contains(name.toLowerCase());
    }

    public boolean isEnemy(String name) {
        if (name == null) return false;
        return enemySet.contains(name.toLowerCase());
    }

    public boolean isProxy(String name) {
        if (name == null) return false;
        return proxySet.contains(name.toLowerCase());
    }

    private PlayerStatus getPlayerStatus(String name) {
        if (isFriend(name)) return PlayerStatus.Friend;
        if (isEnemy(name))  return PlayerStatus.Enemy;
        if (isProxy(name))  return PlayerStatus.Proxy;
        return PlayerStatus.Other;
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum PlayerStatus { Friend, Enemy, Proxy, Other }
}