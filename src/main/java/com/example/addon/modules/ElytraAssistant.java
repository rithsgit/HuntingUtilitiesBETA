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
import net.minecraft.item.Item;
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

    public enum ReplenishMode {
        Bind,
        Automatic
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgAutoReplace     = settings.createGroup("Auto Replace");
    private final SettingGroup sgMiddleClick     = settings.createGroup("Middle Click");
    private final SettingGroup sgRocketReplenish = settings.createGroup("Rocket Replenish");

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
    // Settings — Rocket Replenish
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> rocketReplenishEnabled = sgRocketReplenish.add(new BoolSetting.Builder()
        .name("rocket-replenish")
        .description("Enables the rocket replenish system.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ReplenishMode> replenishMode = sgRocketReplenish.add(new EnumSetting.Builder<ReplenishMode>()
        .name("replenish-mode")
        .description("Toggle between using a keybind or doing it automatically.")
        .defaultValue(ReplenishMode.Bind)
        .visible(rocketReplenishEnabled::get)
        .build()
    );

    private final Setting<Integer> autoThreshold = sgRocketReplenish.add(new IntSetting.Builder()
        .name("auto-threshold")
        .description("Rocket count at which the slot automatically refills.")
        .defaultValue(5)
        .min(1)
        .sliderMax(63)
        .visible(() -> rocketReplenishEnabled.get() && replenishMode.get() == ReplenishMode.Automatic)
        .build()
    );

    private final Setting<Boolean> useSelectedSlot = sgRocketReplenish.add(new BoolSetting.Builder()
        .name("use-selected-slot")
        .description("Replenishes the currently selected hotbar slot instead of a specific one.")
        .defaultValue(false)
        .visible(rocketReplenishEnabled::get)
        .build()
    );

    private final Setting<Integer> targetSlot = sgRocketReplenish.add(new IntSetting.Builder()
        .name("target-slot")
        .description("The specific hotbar slot to replenish (1-9).")
        .defaultValue(8)
        .min(1)
        .max(9)
        .visible(() -> rocketReplenishEnabled.get() && !useSelectedSlot.get())
        .build()
    );

    private final Setting<Keybind> rocketReplenishKey = sgRocketReplenish.add(new KeybindSetting.Builder()
        .name("replenish-key")
        .description("Replenishes the target hotbar slot's item to its max stack size from the main inventory.")
        .defaultValue(Keybind.none())
        .visible(() -> rocketReplenishEnabled.get() && replenishMode.get() == ReplenishMode.Bind)
        .action(() -> {
            if (mc.currentScreen != null) return;
            if (mc.player == null || mc.world == null) return;
            if (!rocketReplenishEnabled.get()) return;
            handleRocketReplenish(false);
        })
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════════════════

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
    }

    private void resetAutoReplaceState() {
        noReplacementWarned = false;
    }

    private void resetMiddleClickState() {
        wasMiddlePressed = false;
        middleClickCooldown = 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Event Handlers
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        handleMiddleClick();
        handleAutoReplace();
        handleAutoReplenish();
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

        if (mc.player.isOnGround()) return;

        ItemUsage target = switch (action) {
            case Rocket -> new ItemUsage(Items.FIREWORK_ROCKET);
            case Pearl  -> new ItemUsage(Items.ENDER_PEARL);
            default     -> null;
        };

        if (target == null) return;
        useItemFromInventory(target.item());
    }

    private void useItemFromInventory(Item item) {
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
    // Rocket Replenish Feature
    // ═══════════════════════════════════════════════════════════════════════════

    private void handleAutoReplenish() {
        if (!rocketReplenishEnabled.get()) return;
        if (replenishMode.get() != ReplenishMode.Automatic) return;
        if (mc.currentScreen != null) return;

        int selectedSlot = useSelectedSlot.get()
            ? mc.player.getInventory().selectedSlot
            : targetSlot.get() - 1;

        ItemStack targetStack = mc.player.getInventory().getStack(selectedSlot);
        
        // If the slot is occupied by something else, don't touch it.
        if (!targetStack.isEmpty() && !targetStack.isOf(Items.FIREWORK_ROCKET)) return;

        int currentCount = targetStack.getCount();
        if (currentCount <= autoThreshold.get()) {
            handleRocketReplenish(true);
        }
    }

    private void handleRocketReplenish(boolean silent) {
        int selectedSlot = useSelectedSlot.get()
            ? mc.player.getInventory().selectedSlot
            : targetSlot.get() - 1;

        ItemStack targetStack = mc.player.getInventory().getStack(selectedSlot);
        Item targetItem = Items.FIREWORK_ROCKET;

        if (!targetStack.isEmpty() && targetStack.getItem() != targetItem) {
            if (!silent) info("Target slot has a different item — cannot replenish.");
            return;
        }

        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            if (!silent) info("Cursor has an item — cannot replenish right now.");
            return;
        }

        int maxCount = targetItem.getMaxCount();
        int currentCount = targetStack.getCount();
        int needed = maxCount - currentCount;

        if (needed <= 0) {
            if (!silent) info("Stack is already full (" + maxCount + ").");
            return;
        }

        for (int i = 9; i < 36 && needed > 0; i++) {
            ItemStack sourceStack = mc.player.getInventory().getStack(i);
            if (sourceStack.isEmpty()) continue;
            if (sourceStack.getItem() != targetItem) continue;

            int available = sourceStack.getCount();
            InvUtils.move().from(i).toHotbar(selectedSlot);
            needed -= Math.min(needed, available);
        }

        int finalCount = maxCount - needed;

        if (needed > 0) {
            if (!silent) info("Replenished " + targetItem.getName().getString()
                + " to " + finalCount + " (not enough items in inventory).");
        } else {
            if (!silent) info("Replenished " + targetItem.getName().getString()
                + " to " + maxCount + ".");
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
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

    private record ItemUsage(Item item) {}
}