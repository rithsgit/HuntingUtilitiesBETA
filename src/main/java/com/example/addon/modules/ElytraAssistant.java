package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

public class ElytraAssistant extends Module {

    public enum MiddleClickAction {
        None,
        Rocket,
        Pearl
    }

    private final SettingGroup sgDurability = settings.createGroup("Durability");
    private final SettingGroup sgChestplate = settings.createGroup("Chestplate Swap");
    private final SettingGroup sgUtilities  = settings.createGroup("Utilities");
    private final SettingGroup sgMending  = settings.createGroup("Auto Mending");
    
    // ─── Durability ────────────────────────────────────────
    private final Setting<Boolean> autoSwap = sgDurability.add(new BoolSetting.Builder()
        .name("auto-swap")
        .description("Swaps to a fresh elytra when current one is low on durability.")
        .defaultValue(true)
        .onChanged(this::onAutoSwapChanged)
        .build()
    );

    private final Setting<Integer> durabilityThreshold = sgDurability.add(new IntSetting.Builder()
        .name("durability-threshold")
        .description("Remaining durability below which swap occurs.")
        .defaultValue(10)
        .min(1)
        .sliderMax(100)
        .visible(autoSwap::get)
        .build()
    );

    private final Setting<Keybind> autoSwapKey = sgDurability.add(new KeybindSetting.Builder()
        .name("toggle-key")
        .description("Key to toggle auto swap.")
        .defaultValue(Keybind.none())
        .action(() -> {
            boolean newVal = !autoSwap.get();
            autoSwap.set(newVal);
            info("Auto Swap " + (newVal ? "enabled" : "disabled") + ".");
        })
        .build()
    );

    // ─── Chestplate Swap ───────────────────────────────────
    private final Setting<Boolean> chestplateOnGround = sgChestplate.add(new BoolSetting.Builder()
        .name("chestplate-on-ground")
        .description("Wears chestplate on ground, elytra while flying.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Keybind> chestplateToggleKey = sgChestplate.add(new KeybindSetting.Builder()
        .name("toggle-key")
        .description("Key to toggle chestplate swapping.")
        .defaultValue(Keybind.none())
        .action(() -> {
            boolean newVal = !chestplateOnGround.get();
            chestplateOnGround.set(newVal);
            info("Chestplate swap " + (newVal ? "enabled" : "disabled") + ".");
        })
        .build()
    );

    private final Setting<Integer> swapDelay = sgDurability.add(new IntSetting.Builder()
        .name("swap-delay")
        .description("Ticks to wait after performing a swap.")
        .defaultValue(10)
        .min(0)
        .visible(() -> autoSwap.get() || chestplateOnGround.get())
        .build()
    );

    // ─── Utilities ────────────────────────────────────────
    private final Setting<MiddleClickAction> middleClickAction = sgUtilities.add(new EnumSetting.Builder<MiddleClickAction>()
        .name("middle-click-action")
        .description("Item to use when middle clicking.")
        .defaultValue(MiddleClickAction.None)
        .build()
    );

    public final Setting<Boolean> silentRocket = sgUtilities.add(new BoolSetting.Builder()
        .name("silent-rocket")
        .description("Prevents hand swing animation when using rockets.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> preventGroundUsage = sgUtilities.add(new BoolSetting.Builder()
        .name("prevent-ground-usage")
        .description("Blocks rocket usage while standing on ground.")
        .defaultValue(true)
        .build()
    );

    // ─── Auto Mending ───────────────────────────────────
    private final Setting<Boolean> autoMend = sgMending.add(new BoolSetting.Builder()
        .name("auto-mend")
        .description("Tries to mend damaged elytra with XP bottles.")
        .defaultValue(false)
        .onChanged(this::onAutoMendChanged)
        .build()
    );

    private final Setting<Keybind> autoMendToggleKey = sgMending.add(new KeybindSetting.Builder()
        .name("toggle-key")
        .description("Key to toggle auto mending.")
        .defaultValue(Keybind.none())
        .action(() -> {
            boolean newVal = !autoMend.get();
            autoMend.set(newVal);
            info("Auto Mend " + (newVal ? "enabled" : "disabled") + ".");
        })
        .build()
    );

    private final Setting<Integer> packetsPerBurst = sgMending.add(new IntSetting.Builder()
        .name("packets-per-burst")
        .description("How many XP bottles to throw per burst.")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .visible(autoMend::get)
        .build()
    );

    private final Setting<Integer> burstDelay = sgMending.add(new IntSetting.Builder()
        .name("burst-delay")
        .description("Ticks to wait between bursts.")
        .defaultValue(3)
        .min(0)
        .sliderMax(20)
        .visible(autoMend::get)
        .build()
    );

    // Internal state
    private int swapTimer = 0;
    private boolean noReplacementWarned = false;
    private boolean noUsableElytraWarned = false;
    private boolean wasMiddlePressed = false;
    private int mendTimer = 0;
    private int middleClickTimer = 0;

    public ElytraAssistant() {
        super(HuntingUtilities.CATEGORY, "elytra-assistant", "Smart elytra & rocket management.");
    }

    @Override
    public void onActivate() {
        swapTimer = 0;
        noReplacementWarned = false;
        noUsableElytraWarned = false;
        wasMiddlePressed = false;
        mendTimer = 0;
        middleClickTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (middleClickTimer > 0) middleClickTimer--;

        // Middle Click Logic
        if (middleClickAction.get() != MiddleClickAction.None) {
            if (Input.isButtonPressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) {
                if (!wasMiddlePressed && middleClickTimer == 0) {
                    runMiddleClickAction();
                    wasMiddlePressed = true;
                    middleClickTimer = 5;
                }
            } else {
                wasMiddlePressed = false;
            }
        }

        if (swapTimer > 0) {
            swapTimer--;
            return;
        }

        // Priority: auto mend (can override normal behavior)
        if (autoMend.get()) {
            handleAutoMend();
            return;
        }

        // Chestplate ↔ Elytra swap based on ground state
        if (chestplateOnGround.get()) {
            handleChestplateElytraSwitch();
        }

        // Normal durability-based auto-swap
        if (autoSwap.get()) {
            handleDurabilityAutoSwap();
        }
    }

    private void handleChestplateElytraSwitch() {
        // Prevent swapping to chestplate if RocketPilot is active (it needs Elytra)
        if (Modules.get().get(RocketPilot.class).isActive()) return;

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);

        if (mc.player.isOnGround()) {
            noUsableElytraWarned = false;
            if (chest.isOf(Items.ELYTRA)) {
                FindItemResult cp = InvUtils.find(stack ->
                    stack.isOf(Items.NETHERITE_CHESTPLATE) || stack.isOf(Items.DIAMOND_CHESTPLATE));
                if (cp.found()) {
                    InvUtils.move().from(cp.slot()).toArmor(2);
                    swapTimer = swapDelay.get();
                }
            }
        } else {
            // In air → want elytra with enough durability
            if (!chest.isOf(Items.ELYTRA) || (autoSwap.get() && chest.getMaxDamage() - chest.getDamage() <= durabilityThreshold.get())) {
                FindItemResult elytra = findUsableElytra();
                if (elytra.found()) {
                    InvUtils.move().from(elytra.slot()).toArmor(2);
                    swapTimer = swapDelay.get();
                    info("Equipped usable elytra.");
                } else if (!noUsableElytraWarned) {
                    warning("No usable elytra found in inventory!");
                    noUsableElytraWarned = true;
                }
            }
        }
    }

    private void handleDurabilityAutoSwap() {
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.isOf(Items.ELYTRA)) return;

        int remaining = chest.getMaxDamage() - chest.getDamage();
        if (remaining > durabilityThreshold.get()) {
            noReplacementWarned = false;
            return;
        }

        FindItemResult replacement = findUsableElytra();
        if (replacement.found()) {
            InvUtils.move().from(replacement.slot()).toArmor(2);
            info("Auto-swapped low-durability elytra.");
            swapTimer = swapDelay.get();
            noReplacementWarned = false;
        } else if (!noReplacementWarned) {
            warning("No replacement elytra available!");
            noReplacementWarned = true;
        }
    }

    private FindItemResult findUsableElytra() {
        return InvUtils.find(stack ->
            stack.isOf(Items.ELYTRA) &&
            (stack.getMaxDamage() - stack.getDamage() > durabilityThreshold.get())
        );
    }

    private void handleAutoMend() {
        if (mendTimer > 0) {
            mendTimer--;
            return;
        }

        FindItemResult xp = InvUtils.find(Items.EXPERIENCE_BOTTLE);
        if (!xp.found()) {
            info("No more XP bottles — disabling auto-mend.");
            autoMend.set(false);
            return;
        }

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.isOf(Items.ELYTRA) || !chest.isDamaged()) {
            // Try to equip damaged one if possible
            FindItemResult damaged = InvUtils.find(stack ->
                stack.isOf(Items.ELYTRA) && stack.isDamaged()
            );
            if (damaged.found()) {
                InvUtils.move().from(damaged.slot()).toArmor(2);
                swapTimer = Math.max(swapDelay.get(), 10);
            } else {
                info("All Elytras mended!");
                autoMend.set(false);
            }
            return;
        }

        Rotations.rotate(mc.player.getYaw(), 90, () -> {
            if (xp.isHotbar()) {
                InvUtils.swap(xp.slot(), true);
                for (int i = 0; i < packetsPerBurst.get(); i++) {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }
                InvUtils.swapBack();
            } else {
                int prevSlot = mc.player.getInventory().selectedSlot;
                InvUtils.move().from(xp.slot()).toHotbar(prevSlot);
                for (int i = 0; i < packetsPerBurst.get(); i++) {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }
                InvUtils.move().from(prevSlot).to(xp.slot());
            }
        });
        mendTimer = burstDelay.get();
    }

    private void runMiddleClickAction() {
        if (mc.currentScreen != null) return;

        MiddleClickAction action = middleClickAction.get();
        FindItemResult itemResult = null;

        if (action == MiddleClickAction.Rocket) {
            if (preventGroundUsage.get() && mc.player.isOnGround()) return;
            itemResult = InvUtils.find(Items.FIREWORK_ROCKET);
        } else if (action == MiddleClickAction.Pearl) {
            itemResult = InvUtils.find(Items.ENDER_PEARL);
        }

        if (itemResult == null || !itemResult.found()) return;

        int slot = itemResult.slot();
        int prevSlot = mc.player.getInventory().selectedSlot;

        if (slot < 9) {
            InvUtils.swap(slot, true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
        } else {
            InvUtils.move().from(slot).toHotbar(prevSlot);
            InvUtils.swap(prevSlot, true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
            InvUtils.move().from(prevSlot).to(slot); // Corrected to use .to(slot)
        }

    }

    private void onAutoSwapChanged(boolean v) {
        if (v && autoMend != null && autoMend.get()) {
            autoMend.set(false);
            info("Auto Mend has been disabled as it conflicts with Auto Swap.");
        }
    }

    private void onAutoMendChanged(boolean v) {
        if (v && autoSwap != null && autoSwap.get()) {
            autoSwap.set(false);
            info("Auto Swap has been disabled as it conflicts with Auto Mend.");
        }
    }

    // Helper for addons to check if rocket should be blocked
    public boolean shouldPreventRocketUse() {
        return isActive() && preventGroundUsage.get() && mc.player.isOnGround();
    }

    // Helper for addons to check silent mode
    public boolean shouldSilentRocket() {
        return isActive() && silentRocket.get();
    }

    public boolean isAutoSwapEnabled() {
        return isActive() && autoSwap.get();
    }
}