package com.example.addon.modules;

import com.example.addon.Tim;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Penpal extends Module {
    public enum Modifier {
        NONE,
        SHIFT,
        CONTROL,
        ALT
    }

    public enum MessageType {
        CUSTOM,
        RANDOM
    }

    private static final String SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private final Random random = new Random();
    private String lastBotCmd = "";

    // Delay Queue
    private static class PendingMessage {
        String target;
        String msg;
        long executeAt;

        PendingMessage(String target, String msg, long executeAt) {
            this.target = target;
            this.msg = msg;
            this.executeAt = executeAt;
        }
    }
    private final List<PendingMessage> messageQueue = new ArrayList<>();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> randomPool = sgGeneral.add(new StringSetting.Builder()
        .name("random-pool")
        .description("Comma-separated list of commands to randomly pick from for RANDOM.")
        .defaultValue("tp, pearl, teleport")
        .build()
    );

    private final Setting<Boolean> humanDelay = sgGeneral.add(new BoolSetting.Builder()
        .name("human-like-delay")
        .description("Adds a randomized 50-150ms delay before sending to bypass anti-spam packet timing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> globalCooldown = sgGeneral.add(new IntSetting.Builder()
        .name("global-cooldown-ms")
        .description("Prevents triggering any slot again for this many milliseconds after a message is sent.")
        .defaultValue(500)
        .min(0)
        .sliderMax(2000)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Shows a message in the Meteor chat when a message is sent.")
        .defaultValue(true)
        .build()
    );

    private final SettingGroup sgMessage1 = settings.createGroup("Message 1");
    private final SettingGroup sgMessage2 = settings.createGroup("Message 2");
    private final SettingGroup sgMessage3 = settings.createGroup("Message 3");
    private final SettingGroup sgMessage4 = settings.createGroup("Message 4");

    // --- Message 1 ---
    private final Setting<String> target1 = sgMessage1.add(new StringSetting.Builder()
        .name("target-user").description("The username to send the message to.").defaultValue("").build()
    );
    private final Setting<MessageType> type1 = sgMessage1.add(new EnumSetting.Builder<MessageType>()
        .name("message-type").description("RANDOM randomly picks from the pool. CUSTOM uses your specific text.").defaultValue(MessageType.RANDOM).build()
    );
    private final Setting<String> message1 = sgMessage1.add(new StringSetting.Builder()
        .name("custom-message").description("The message to send. Only used if Message Type is set to CUSTOM.").defaultValue("").visible(() -> type1.get() == MessageType.CUSTOM).build()
    );
    private final Setting<Modifier> modifier1 = sgMessage1.add(new EnumSetting.Builder<Modifier>()
        .name("modifier").description("The modifier key to hold.").defaultValue(Modifier.NONE).build()
    );
    private final Setting<Keybind> key1 = sgMessage1.add(new KeybindSetting.Builder()
        .name("trigger-key").description("The key to press to send the message.").defaultValue(Keybind.none()).build()
    );

    // --- Message 2 ---
    private final Setting<String> target2 = sgMessage2.add(new StringSetting.Builder()
        .name("target-user").description("The username to send the message to.").defaultValue("").build()
    );
    private final Setting<MessageType> type2 = sgMessage2.add(new EnumSetting.Builder<MessageType>()
        .name("message-type").description("RANDOM randomly picks from the pool. CUSTOM uses your specific text.").defaultValue(MessageType.RANDOM).build()
    );
    private final Setting<String> message2 = sgMessage2.add(new StringSetting.Builder()
        .name("custom-message").description("The message to send. Only used if Message Type is set to CUSTOM.").defaultValue("").visible(() -> type2.get() == MessageType.CUSTOM).build()
    );
    private final Setting<Modifier> modifier2 = sgMessage2.add(new EnumSetting.Builder<Modifier>()
        .name("modifier").description("The modifier key to hold.").defaultValue(Modifier.NONE).build()
    );
    private final Setting<Keybind> key2 = sgMessage2.add(new KeybindSetting.Builder()
        .name("trigger-key").description("The key to press to send the message.").defaultValue(Keybind.none()).build()
    );

    // --- Message 3 ---
    private final Setting<String> target3 = sgMessage3.add(new StringSetting.Builder()
        .name("target-user").description("The username to send the message to.").defaultValue("").build()
    );
    private final Setting<MessageType> type3 = sgMessage3.add(new EnumSetting.Builder<MessageType>()
        .name("message-type").description("RANDOM randomly picks from the pool. CUSTOM uses your specific text.").defaultValue(MessageType.RANDOM).build()
    );
    private final Setting<String> message3 = sgMessage3.add(new StringSetting.Builder()
        .name("custom-message").description("The message to send. Only used if Message Type is set to CUSTOM.").defaultValue("").visible(() -> type3.get() == MessageType.CUSTOM).build()
    );
    private final Setting<Modifier> modifier3 = sgMessage3.add(new EnumSetting.Builder<Modifier>()
        .name("modifier").description("The modifier key to hold.").defaultValue(Modifier.NONE).build()
    );
    private final Setting<Keybind> key3 = sgMessage3.add(new KeybindSetting.Builder()
        .name("trigger-key").description("The key to press to send the message.").defaultValue(Keybind.none()).build()
    );

    // --- Message 4 ---
    private final Setting<String> target4 = sgMessage4.add(new StringSetting.Builder()
        .name("target-user").description("The username to send the message to.").defaultValue("").build()
    );
    private final Setting<MessageType> type4 = sgMessage4.add(new EnumSetting.Builder<MessageType>()
        .name("message-type").description("RANDOM randomly picks from the pool. CUSTOM uses your specific text.").defaultValue(MessageType.RANDOM).build()
    );
    private final Setting<String> message4 = sgMessage4.add(new StringSetting.Builder()
        .name("custom-message").description("The message to send. Only used if Message Type is set to CUSTOM.").defaultValue("").visible(() -> type4.get() == MessageType.CUSTOM).build()
    );
    private final Setting<Modifier> modifier4 = sgMessage4.add(new EnumSetting.Builder<Modifier>()
        .name("modifier").description("The modifier key to hold.").defaultValue(Modifier.NONE).build()
    );
    private final Setting<Keybind> key4 = sgMessage4.add(new KeybindSetting.Builder()
        .name("trigger-key").description("The key to press to send the message.").defaultValue(Keybind.none()).build()
    );

    private final boolean[] wasPressed = new boolean[5]; // Index 1 to 4
    private long lastSentTime = 0;

    public Penpal() {
        super(Tim.CATEGORY, "penpal", "Quickly message custom users or bots via /msg using custom modifier keys and binds.");
    }

    @Override
    public void onActivate() {
        for (int i = 0; i < wasPressed.length; i++) {
            wasPressed[i] = false;
        }
        lastBotCmd = "";
        lastSentTime = 0;
        messageQueue.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Process message queue for human-like delays
        if (!messageQueue.isEmpty()) {
            long currentTime = System.currentTimeMillis();
            messageQueue.removeIf(pending -> {
                if (currentTime >= pending.executeAt) {
                    executeSend(pending.target, pending.msg);
                    return true;
                }
                return false;
            });
        }

        checkSlot(1, target1, type1, message1, modifier1, key1);
        checkSlot(2, target2, type2, message2, modifier2, key2);
        checkSlot(3, target3, type3, message3, modifier3, key3);
        checkSlot(4, target4, type4, message4, modifier4, key4);
    }

    private void checkSlot(int id, Setting<String> targetSetting, Setting<MessageType> typeSetting, Setting<String> customMsgSetting, Setting<Modifier> modSetting, Setting<Keybind> keySetting) {
        if (targetSetting.get().isBlank()) {
            wasPressed[id] = false;
            return;
        }
        
        boolean pressed = isPressed(keySetting.get(), modSetting.get());
        if (pressed && !wasPressed[id]) {
            // Check global cooldown
            if (System.currentTimeMillis() - lastSentTime >= globalCooldown.get()) {
                // Resolve the actual message based on the Enum selection
                String finalMessage;
                if (typeSetting.get() == MessageType.CUSTOM) {
                    finalMessage = customMsgSetting.get();
                } else {
                    finalMessage = getRandomBotCommand();
                }
                
                // Append anti-spam suffix
                finalMessage += " " + generateAntiSpamSuffix();
                
                // Calculate delay
                long delay = humanDelay.get() ? 50 + random.nextInt(101) : 0; // 50-150ms
                long executeAt = System.currentTimeMillis() + delay;
                
                // Queue message
                messageQueue.add(new PendingMessage(targetSetting.get(), finalMessage, executeAt));
                lastSentTime = System.currentTimeMillis();
            }
        }
        wasPressed[id] = pressed;
    }

    private String getRandomBotCommand() {
        String[] pool = randomPool.get().split(",");
        List<String> valid = new ArrayList<>();
        
        for (String s : pool) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(lastBotCmd)) {
                valid.add(trimmed);
            }
        }
        
        if (valid.isEmpty()) {
            // Fallback if pool is empty or only contains the last command
            for (String s : pool) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    lastBotCmd = trimmed;
                    return trimmed;
                }
            }
            return "tp"; // Ultimate fallback
        }
        
        String cmd = valid.get(random.nextInt(valid.size()));
        lastBotCmd = cmd;
        return cmd;
    }

    private String generateAntiSpamSuffix() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(SUFFIX_CHARS.charAt(random.nextInt(SUFFIX_CHARS.length())));
        }
        return sb.toString();
    }

    private void executeSend(String target, String msg) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (target.isEmpty() || msg.isEmpty()) return;
        
        target = target.trim();
        mc.getNetworkHandler().sendChatCommand("msg " + target + " " + msg);
        if (chatFeedback.get()) {
            info("Sent message to " + target + ".");
        }
    }

    private boolean isPressed(Keybind key, Modifier modifier) {
        if (!key.isPressed()) return false;
        if (mc.currentScreen != null) return false; // Don't trigger when in menus/chat
        
        long handle = mc.getWindow().getHandle();
        boolean shiftDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean ctrlDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        boolean altDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;

        // Ensure ONLY the specified modifier is held (prevents overlap conflicts)
        return switch (modifier) {
            case NONE -> !shiftDown && !ctrlDown && !altDown;
            case SHIFT -> shiftDown && !ctrlDown && !altDown;
            case CONTROL -> ctrlDown && !shiftDown && !altDown;
            case ALT -> altDown && !shiftDown && !ctrlDown;
        };
    }
}