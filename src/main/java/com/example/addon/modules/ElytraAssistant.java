package com.example.addon.modules;

import org.lwjgl.glfw.GLFW;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;

public class ElytraAssistant extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum MiddleClickAction {
        None,
        Rocket,
        Pearl
    }

    public enum WarningSound {
        Anvil,
        WitherSpawn,
        CreeperPrimed,
        ExperienceOrb,
        Bell,
        NoteBassDrum;

        public SoundEvent toSoundEvent() {
            return switch (this) {
                case Anvil         -> SoundEvents.BLOCK_ANVIL_LAND;
                case WitherSpawn   -> SoundEvents.ENTITY_WITHER_SPAWN;
                case CreeperPrimed -> SoundEvents.ENTITY_CREEPER_PRIMED;
                case ExperienceOrb -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
                case Bell          -> SoundEvents.BLOCK_BELL_USE;
                case NoteBassDrum  -> SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value();
            };
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgAutoReplace = settings.createGroup("Auto Replace");
    private final SettingGroup sgMiddleClick = settings.createGroup("Middle Click");
    private final SettingGroup sgMisc        = settings.createGroup("Miscellaneous");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Auto Replace
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> autoReplace = sgAutoReplace.add(new BoolSetting.Builder()
        .name("auto-replace")
        .description("Automatically replace elytra when durability is low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> durabilityThreshold = sgAutoReplace.add(new IntSetting.Builder()
        .name("durability-threshold")
        .description("Minimum durability before replacing.")
        .defaultValue(10)
        .min(1)
        .sliderMax(100)
        .visible(autoReplace::get)
        .build()
    );

    private final Setting<WarningSound> warningSoundType = sgAutoReplace.add(new EnumSetting.Builder<WarningSound>()
        .name("warning-sound")
        .description("Sound played when no replacement elytra is available.")
        .defaultValue(WarningSound.Anvil)
        .visible(autoReplace::get)
        .build()
    );

    private final Setting<Double> warningSoundVolume = sgAutoReplace.add(new DoubleSetting.Builder()
        .name("warning-volume")
        .description("Volume of the warning sound.")
        .defaultValue(1.0)
        .min(0.1)
        .sliderMax(2.0)
        .visible(autoReplace::get)
        .build()
    );

    private final Setting<Keybind> toggleKey = sgAutoReplace.add(new KeybindSetting.Builder()
        .name("toggle-key")
        .description("Key to toggle auto replace.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (mc.currentScreen != null) return;
            boolean enabled = !autoReplace.get();
            autoReplace.set(enabled);
            info("Auto Replace " + (enabled ? "enabled" : "disabled") + ".");
        })
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Middle Click
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<MiddleClickAction> middleClickAction = sgMiddleClick.add(new EnumSetting.Builder<MiddleClickAction>()
        .name("action")
        .description("Item to use when middle clicking.")
        .defaultValue(MiddleClickAction.None)
        .build()
    );

    public final Setting<Boolean> silentRocket = sgMiddleClick.add(new BoolSetting.Builder()
        .name("silent-rocket")
        .description("Prevents hand swing animation when using rockets.")
        .defaultValue(true)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Miscellaneous
    // ═══════════════════════════════════════════════════════════════════════════

    public final Setting<Boolean> antiAfk = sgMisc.add(new BoolSetting.Builder()
        .name("anti-afk")
        .description("Prevents AFK kick by swinging hand periodically.")
        .defaultValue(false)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════════════════

    private static final int AFK_INTERVAL_TICKS = 300; // 15 seconds
    private static final int AFK_RANDOMNESS_TICKS = 120; // ±6 seconds
    private static final int MIDDLE_CLICK_COOLDOWN = 5;

    // ═══════════════════════════════════════════════════════════════════════════
    // State — Auto Replace
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean noReplacementWarned = false;

    // ═══════════════════════════════════════════════════════════════════════════
    // State — Middle Click
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean wasMiddlePressed = false;
    private int middleClickCooldown = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // State — Anti-AFK
    // ═══════════════════════════════════════════════════════════════════════════

    private int antiAfkTimer = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public ElytraAssistant() {
        super(Tim.CATEGORY, "elytra-assistant", "Smart elytra and rocket management.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        resetAutoReplaceState();
        resetMiddleClickState();
        resetAntiAfkState();
    }

    private void resetAutoReplaceState() {
        noReplacementWarned = false;
    }

    private void resetMiddleClickState() {
        wasMiddlePressed = false;
        middleClickCooldown = 0;
    }

    private void resetAntiAfkState() {
        antiAfkTimer = 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Event Handlers
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        handleMiddleClick();
        handleAntiAfk();
        handleAutoReplace();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Auto Replace Feature
    // ═══════════════════════════════════════════════════════════════════════════

    private void handleAutoReplace() {
        if (!autoReplace.get()) return;
        if (Modules.get().get(Mendbot.class).isActive()) return;

        ItemStack chestplate = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chestplate.isOf(Items.ELYTRA)) return;

        int remainingDurability = getRemainingDurability(chestplate);
        if (remainingDurability > durabilityThreshold.get()) {
            noReplacementWarned = false;
            return;
        }

        FindItemResult replacement = findBestReplacementElytra();
        if (replacement.found()) {
            equipElytraSilently(replacement.slot());
            warning("Elytra durability low! Replaced with fresh elytra.");
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            noReplacementWarned = false;
        } else if (!noReplacementWarned) {
            warning("No replacement elytra available!");
            playWarningSound();
            noReplacementWarned = true;
        }
    }

    private FindItemResult findBestReplacementElytra() {
        int bestSlot = -1;
        int bestDurability = -1;

        for (int i = 0; i < mc.player.getInventory().main.size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isUsableElytra(stack)) continue;

            int durability = getRemainingDurability(stack);
            if (durability > bestDurability) {
                bestSlot = i;
                bestDurability = durability;
            }
        }

        return bestSlot != -1
            ? new FindItemResult(bestSlot, mc.player.getInventory().getStack(bestSlot).getCount())
            : new FindItemResult(-1, 0);
    }

    private boolean isUsableElytra(ItemStack stack) {
        return !stack.isEmpty()
            && stack.isOf(Items.ELYTRA)
            && getRemainingDurability(stack) > durabilityThreshold.get();
    }

    private int getRemainingDurability(ItemStack elytra) {
        return elytra.getMaxDamage() - elytra.getDamage();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Middle Click Feature
    // ═══════════════════════════════════════════════════════════════════════════

    private void handleMiddleClick() {
        if (middleClickCooldown > 0) middleClickCooldown--;

        if (middleClickAction.get() == MiddleClickAction.None) return;
        if (mc.currentScreen != null) return;

        boolean isPressed = Input.isButtonPressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

        if (isPressed && !wasMiddlePressed && middleClickCooldown == 0) {
            executeMiddleClickAction();
            wasMiddlePressed = true;
            middleClickCooldown = MIDDLE_CLICK_COOLDOWN;
        } else if (!isPressed) {
            wasMiddlePressed = false;
        }
    }

    private void executeMiddleClickAction() {
        MiddleClickAction action = middleClickAction.get();

        // Ground usage is always prevented while the module is enabled.
        if (mc.player.isOnGround()) return;

        ItemUsage target = switch (action) {
            case Rocket -> new ItemUsage(Items.FIREWORK_ROCKET);
            case Pearl  -> new ItemUsage(Items.ENDER_PEARL);
            default     -> null;
        };

        if (target == null) return;
        useItemFromInventory(target.item());
    }

    private void useItemFromInventory(net.minecraft.item.Item item) {
        FindItemResult result = InvUtils.find(item);
        if (!result.found()) return;

        int slot = result.slot();
        int previousSlot = mc.player.getInventory().selectedSlot;

        if (isHotbarSlot(slot)) {
            InvUtils.swap(slot, silentRocket.get());
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
        } else {
            InvUtils.move().from(slot).toHotbar(previousSlot);
            InvUtils.swap(previousSlot, silentRocket.get());
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
            InvUtils.move().from(previousSlot).to(slot);
        }
    }

    private boolean isHotbarSlot(int slot) {
        return slot >= 0 && slot < 9;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Anti-AFK Feature
    // ═══════════════════════════════════════════════════════════════════════════

    private void handleAntiAfk() {
        if (!antiAfk.get()) return;

        if (antiAfkTimer <= 0) {
            mc.player.swingHand(Hand.MAIN_HAND);
            antiAfkTimer = calculateNextSwingDelay();
        } else {
            antiAfkTimer--;
        }
    }

    private int calculateNextSwingDelay() {
        int base = AFK_INTERVAL_TICKS;
        int variance = (int) (Math.random() * AFK_RANDOMNESS_TICKS * 2) - AFK_RANDOMNESS_TICKS;
        return Math.max(1, base + variance);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Inventory Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private void equipElytraSilently(int slot) {
        InvUtils.move().from(convertToInventorySlot(slot)).toArmor(2);
    }

    private int convertToInventorySlot(int hotbarSlot) {
        return isHotbarSlot(hotbarSlot) ? 36 + hotbarSlot : hotbarSlot;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Sound Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private void playWarningSound() {
        mc.player.playSound(
            warningSoundType.get().toSoundEvent(),
            warningSoundVolume.get().floatValue(),
            1.0f
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════════

    public boolean shouldPreventRocketUse() {
        return isActive() && mc.player.isOnGround();
    }

    public boolean shouldSilentRocket() {
        return isActive() && silentRocket.get();
    }

    public boolean isAutoReplaceEnabled() {
        return isActive() && autoReplace.get();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Inner Classes
    // ═══════════════════════════════════════════════════════════════════════════

    private record ItemUsage(net.minecraft.item.Item item) {}
} 