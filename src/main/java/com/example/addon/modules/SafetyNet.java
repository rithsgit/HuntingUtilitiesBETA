package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.world.dimension.DimensionTypes;

import com.example.addon.Tim;

public class SafetyNet extends Module {

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------
    public enum DimensionMode {
        Overworld, End, Both
    }

    public enum WarnSound {
        Pling, Bell, Anvil, Basedrum, Chime, Hat;

        public SoundEvent getSoundEvent() {
            return switch (this) {
                case Pling    -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value();
                case Bell     -> SoundEvents.BLOCK_NOTE_BLOCK_BELL.value();
                case Anvil    -> SoundEvents.BLOCK_ANVIL_LAND;
                case Basedrum -> SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value();
                case Chime    -> SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value();
                case Hat      -> SoundEvents.BLOCK_NOTE_BLOCK_HAT.value();
            };
        }
    }

    // -------------------------------------------------------------------------
    // Setting Groups
    // -------------------------------------------------------------------------
    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgWarning    = settings.createGroup("Warning Ping");
    private final SettingGroup sgDisconnect = settings.createGroup("Auto Disconnect");
    private final SettingGroup sgOverworld  = settings.createGroup("Overworld Thresholds");
    private final SettingGroup sgEnd        = settings.createGroup("End Thresholds");
    private final SettingGroup sgGrace      = settings.createGroup("Grace Period");
    private final SettingGroup sgChorus     = settings.createGroup("Chorus Escape");

    // -------------------------------------------------------------------------
    // General Settings
    // -------------------------------------------------------------------------
    private final Setting<DimensionMode> dimension = sgGeneral.add(new EnumSetting.Builder<DimensionMode>()
        .name("dimension")
        .description("Which dimension(s) to protect you in.")
        .defaultValue(DimensionMode.End)
        .build()
    );

    private final Setting<Boolean> perDimensionThresholds = sgGeneral.add(new BoolSetting.Builder()
        .name("per-dimension-thresholds")
        .description("Use separate warn and disconnect Y levels for the Overworld and End instead of shared values.")
        .defaultValue(false)
        .visible(() -> dimension.get() == DimensionMode.Both)
        .build()
    );

    // -------------------------------------------------------------------------
    // Warning Ping Settings
    // -------------------------------------------------------------------------
    private final Setting<Boolean> warnEnabled = sgWarning.add(new BoolSetting.Builder()
        .name("warn-enabled")
        .description("Play a sound and show a title warning when below the warning Y level.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> warnY = sgWarning.add(new IntSetting.Builder()
        .name("warn-y-level")
        .description("Y level at which the warning ping triggers. Used when sharing thresholds for both dimensions.")
        .defaultValue(0)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .visible(() -> dimension.get() == DimensionMode.Both && !perDimensionThresholds.get())
        .build()
    );

    private final Setting<Integer> warnInterval = sgWarning.add(new IntSetting.Builder()
        .name("warn-interval")
        .description("How often the warning repeats while below the Y level, in ticks (20 = 1 second). Set to 0 to warn only once.")
        .defaultValue(40)
        .range(0, 200)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<WarnSound> warnSound = sgWarning.add(new EnumSetting.Builder<WarnSound>()
        .name("warn-sound")
        .description("The sound played when the warning triggers.")
        .defaultValue(WarnSound.Pling)
        .build()
    );

    private final Setting<Double> warnVolume = sgWarning.add(new DoubleSetting.Builder()
        .name("warn-volume")
        .description("Volume of the warning ping sound (0.0 = silent, 1.0 = full volume).")
        .defaultValue(1.0)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    // -------------------------------------------------------------------------
    // Auto Disconnect Settings
    // -------------------------------------------------------------------------
    private final Setting<Boolean> disconnectEnabled = sgDisconnect.add(new BoolSetting.Builder()
        .name("disconnect-enabled")
        .description("Automatically disconnect when below the disconnect Y level.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> disconnectY = sgDisconnect.add(new IntSetting.Builder()
        .name("disconnect-y-level")
        .description("Y level at which auto-disconnect triggers. Used when sharing thresholds for both dimensions.")
        .defaultValue(-10)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .visible(() -> dimension.get() == DimensionMode.Both && !perDimensionThresholds.get())
        .build()
    );

    // -------------------------------------------------------------------------
    // Overworld Thresholds Settings
    // -------------------------------------------------------------------------
    private final Setting<Integer> overworldWarnY = sgOverworld.add(new IntSetting.Builder()
        .name("warn-y-level")
        .description("Overworld Y level at which the warning triggers.")
        .defaultValue(-60)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .visible(() -> dimension.get() == DimensionMode.Overworld || (dimension.get() == DimensionMode.Both && perDimensionThresholds.get()))
        .build()
    );

    private final Setting<Integer> overworldDisconnectY = sgOverworld.add(new IntSetting.Builder()
        .name("disconnect-y-level")
        .description("Overworld Y level at which auto-disconnect triggers.")
        .defaultValue(-70)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .visible(() -> dimension.get() == DimensionMode.Overworld || (dimension.get() == DimensionMode.Both && perDimensionThresholds.get()))
        .build()
    );

    // -------------------------------------------------------------------------
    // End Thresholds Settings
    // -------------------------------------------------------------------------
    private final Setting<Integer> endWarnY = sgEnd.add(new IntSetting.Builder()
        .name("warn-y-level")
        .description("End Y level at which the warning triggers.")
        .defaultValue(0)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .visible(() -> dimension.get() == DimensionMode.End || (dimension.get() == DimensionMode.Both && perDimensionThresholds.get()))
        .build()
    );

    private final Setting<Integer> endDisconnectY = sgEnd.add(new IntSetting.Builder()
        .name("disconnect-y-level")
        .description("End Y level at which auto-disconnect triggers.")
        .defaultValue(-10)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .visible(() -> dimension.get() == DimensionMode.End || (dimension.get() == DimensionMode.Both && perDimensionThresholds.get()))
        .build()
    );

    // -------------------------------------------------------------------------
    // Grace Period Settings
    // -------------------------------------------------------------------------
    private final Setting<Boolean> graceEnabled = sgGrace.add(new BoolSetting.Builder()
        .name("grace-enabled")
        .description("Wait a set number of ticks below the threshold before firing warnings or disconnect. Prevents false triggers from lag spikes or brief knockback.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> graceTicks = sgGrace.add(new IntSetting.Builder()
        .name("grace-ticks")
        .description("How many consecutive ticks the player must be below the threshold before actions fire (20 = 1 second).")
        .defaultValue(10)
        .range(1, 100)
        .sliderRange(1, 60)
        .visible(graceEnabled::get)
        .build()
    );

    // -------------------------------------------------------------------------
    // Chorus Escape Settings
    // -------------------------------------------------------------------------
    private final Setting<Keybind> chorusEscapeKey = sgChorus.add(new KeybindSetting.Builder()
        .name("chorus-escape-key")
        .description("Eats a chorus fruit to escape the void. Ignores warnings/disconnects while active and disables module on landing.")
        .defaultValue(Keybind.none())
        .build()
    );

    // -------------------------------------------------------------------------
    // Internal State
    // -------------------------------------------------------------------------
    private boolean hasDisconnected;
    private int     warnTickCounter;
    private int     graceTickCounter;
    
    private boolean chorusEscapeActive;
    private boolean hasTriggeredEat;
    private boolean wasChorusPressed;

    public SafetyNet() {
        super(Tim.CATEGORY, "Safety Net", "Protects you from the void by warning, disconnecting, or chorus teleporting at low Y levels.");
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------
    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        // Safety: release right click if the module is manually toggled off mid-eat
        if (mc.options != null) {
            mc.options.useKey.setPressed(false);
        }
        resetState();
    }

    private void resetState() {
        hasDisconnected    = false;
        warnTickCounter    = 0;
        graceTickCounter   = 0;
        chorusEscapeActive = false;
        hasTriggeredEat    = false;
        wasChorusPressed   = false;
    }

    private void resetCounters() {
        warnTickCounter  = 0;
        graceTickCounter = 0;
    }

    // -------------------------------------------------------------------------
    // Main Tick Event
    // -------------------------------------------------------------------------
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        handleChorusEscapeKeybind();
        if (chorusEscapeActive) return; // Bypass normal logic while eating/teleporting

        if (!isInValidDimension()) {
            resetCounters();
            return;
        }

        int effectiveWarnY = getEffectiveWarnY();
        int effectiveDisconnectY = getEffectiveDisconnectY();

        validateThresholds(effectiveWarnY, effectiveDisconnectY);

        double playerY = mc.player.getY();
        boolean belowDisconnect = disconnectEnabled.get() && playerY < effectiveDisconnectY;
        boolean belowWarn = warnEnabled.get() && playerY < effectiveWarnY;
        boolean inDanger = belowDisconnect || belowWarn;

        if (!handleGracePeriod(inDanger)) return;

        // Auto Disconnect (highest priority)
        if (belowDisconnect) {
            if (!hasDisconnected) {
                hasDisconnected = true;
                executeDisconnect(playerY, effectiveDisconnectY);
            }
            return;
        } else {
            hasDisconnected = false;
        }

        // Warning Ping
        if (belowWarn) {
            warnTickCounter++;
            int interval = warnInterval.get();
            boolean shouldWarn = (interval == 0) ? (warnTickCounter == 1) : (warnTickCounter % interval == 1);

            if (shouldWarn) {
                executeWarning(playerY, effectiveWarnY);
            }
        } else {
            warnTickCounter = 0;
        }
    }

    // -------------------------------------------------------------------------
    // Logic Helpers
    // -------------------------------------------------------------------------

    private void handleChorusEscapeKeybind() {
        boolean chorusPressed = chorusEscapeKey.get().isPressed();
        if (chorusPressed && !wasChorusPressed && !chorusEscapeActive) {
            chorusEscapeActive = true;
            hasTriggeredEat = false;
        }
        wasChorusPressed = chorusPressed;

        if (!chorusEscapeActive) return;

        if (!hasTriggeredEat) {
            if (!selectHotbarItem(Items.CHORUS_FRUIT)) {
                warning("No Chorus Fruit found in hotbar! Falling back to normal Safety Net logic.");
                chorusEscapeActive = false;
            } else {
                mc.options.useKey.setPressed(true);
                hasTriggeredEat = true;
            }
        } else {
            if (!mc.player.isUsingItem()) {
                mc.options.useKey.setPressed(false);
                info("Chorus escape successful. Disabling Safety Net.");
                toggle();
            }
        }
    }

    private boolean isInValidDimension() {
        boolean inEnd = mc.world.getDimensionEntry().matchesKey(DimensionTypes.THE_END);
        boolean inOverworld = mc.world.getDimensionEntry().matchesKey(DimensionTypes.OVERWORLD);
        DimensionMode mode = dimension.get();

        if (mode == DimensionMode.Overworld && !inOverworld) return false;
        if (mode == DimensionMode.End && !inEnd) return false;
        if (mode == DimensionMode.Both && !inEnd && !inOverworld) return false; // Ignores Nether
        
        return true;
    }

    private int getEffectiveWarnY() {
        DimensionMode mode = dimension.get();
        boolean inEnd = mc.world.getDimensionEntry().matchesKey(DimensionTypes.THE_END);

        if (mode == DimensionMode.Overworld) return overworldWarnY.get();
        if (mode == DimensionMode.End) return endWarnY.get();

        // Both
        if (perDimensionThresholds.get()) {
            return inEnd ? endWarnY.get() : overworldWarnY.get();
        }
        return warnY.get();
    }

    private int getEffectiveDisconnectY() {
        DimensionMode mode = dimension.get();
        boolean inEnd = mc.world.getDimensionEntry().matchesKey(DimensionTypes.THE_END);

        if (mode == DimensionMode.Overworld) return overworldDisconnectY.get();
        if (mode == DimensionMode.End) return endDisconnectY.get();

        // Both
        if (perDimensionThresholds.get()) {
            return inEnd ? endDisconnectY.get() : overworldDisconnectY.get();
        }
        return disconnectY.get();
    }

    private void validateThresholds(int warnY, int disconnectY) {
        if (warnEnabled.get() && disconnectEnabled.get() && warnY <= disconnectY) {
            warning("⚠ Safety Net config issue: warn-y-level (" + warnY
                + ") is not above disconnect-y-level (" + disconnectY
                + "). You may not receive warnings before being disconnected!");
        }
    }

    private boolean handleGracePeriod(boolean inDanger) {
        if (inDanger && graceEnabled.get()) {
            graceTickCounter++;
            if (graceTickCounter < graceTicks.get()) {
                // Still within grace window — don't act yet, but don't reset warn
                // counter either so interval timing stays accurate once grace expires.
                return false;
            }
        } else if (!inDanger) {
            graceTickCounter = 0;
        }
        return true;
    }

    private void executeDisconnect(double playerY, int targetDisconnectY) {
        mc.inGameHud.setTitle(Text.literal("§c§lSAFETY NET DISCONNECT"));
        mc.inGameHud.setSubtitle(Text.literal(
            "§eY: " + String.format("%.1f", playerY) + " §7is below §c" + targetDisconnectY
        ));

        info("§cDisconnected — Y §e" + String.format("%.1f", playerY)
            + " §cis below safe threshold §e(" + targetDisconnectY + ")§c.");

        mc.world.disconnect();
        mc.disconnect();
    }

    private void executeWarning(double playerY, int targetWarnY) {
        mc.inGameHud.setTitle(Text.literal("§e§l⚠ VOID WARNING"));
        mc.inGameHud.setSubtitle(Text.literal(
            "§fY: §c" + String.format("%.1f", playerY) + "  §f| Safe above: §a" + targetWarnY
        ));

        warning("⚠ Safety Net: Below Y §c" + targetWarnY
            + "§r! Current Y: §c" + String.format("%.1f", playerY));

        mc.player.playSound(
            warnSound.get().getSoundEvent(),
            warnVolume.get().floatValue(),
            2.0f
        );
    }

    // -------------------------------------------------------------------------
    // Utility Helpers
    // -------------------------------------------------------------------------

    private boolean selectHotbarItem(Item item) {
        if (mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }
}