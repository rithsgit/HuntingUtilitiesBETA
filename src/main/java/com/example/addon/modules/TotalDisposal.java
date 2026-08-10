package com.example.addon.modules;

import org.lwjgl.glfw.GLFW;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;

public class TotalDisposal extends Module {
    public enum ModifierKey {
        Control,
        Shift
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ── Settings ───────────────────────────────────────────────────
    private final Setting<Keybind> dropKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("drop-key")
        .description("Key to drop the entire inventory.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> killKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("kill-key")
        .description("Key to send the /kill command.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<ModifierKey> modifier = sgGeneral.add(new EnumSetting.Builder<ModifierKey>()
        .name("modifier")
        .description("The required modifier key that must be held.")
        .defaultValue(ModifierKey.Control)
        .build()
    );

    private final Setting<Boolean> dropEverything = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-everything")
        .description("Drops all items including armor and offhand.")
        .defaultValue(true)
        .build()
    );

    private boolean wasDropPressed = false;
    private boolean wasKillPressed = false;

    public TotalDisposal() {
        super(Tim.CATEGORY, "total-disposal", "Drop items and /kill via dedicated key combinations.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.currentScreen != null) return; // Prevent triggering while in chat or menus

        boolean dropPressed = dropKey.get().isPressed();
        boolean killPressed = killKey.get().isPressed();

        if (checkModifiers()) {
            if (dropPressed && !wasDropPressed) {
                executeDrop();
            }
            if (killPressed && !wasKillPressed) {
                executeKill();
            }
        }

        wasDropPressed = dropPressed;
        wasKillPressed = killPressed;
    }

    private boolean checkModifiers() {
        return switch (modifier.get()) {
            case Control -> Input.isKeyPressed(GLFW.GLFW_KEY_LEFT_CONTROL) || Input.isKeyPressed(GLFW.GLFW_KEY_RIGHT_CONTROL);
            case Shift   -> Input.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT) || Input.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT);
        };
    }

    private void executeDrop() {
        if (dropEverything.get()) {
            // Drop all slots: 0-8 (Hotbar), 9-35 (Main), 36-39 (Armor), 40 (Offhand)
            for (int i = 0; i <= 40; i++) {
                InvUtils.drop().slot(i);
            }
            info("Inventory dropped.");
        }
    }

    private void executeKill() {
        if (mc.player != null) {
            mc.player.networkHandler.sendChatCommand("kill");
            info("Sent /kill command.");
        }
    }
}