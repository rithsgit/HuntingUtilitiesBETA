package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;

public class ServerHealthcareSystem extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // Auto Totem
    private final Setting<Boolean> autoTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-totem")
        .description("Automatically equips a totem of undying in your offhand.")
        .defaultValue(true)
        .build()
    );

    // Auto Armor
    private final Setting<Boolean> autoArmor = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-armor")
        .description("Automatically equips the best armor in your inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ChestplatePreference> chestplatePreference = sgGeneral.add(new EnumSetting.Builder<ChestplatePreference>()
        .name("chestplate-preference")
        .description("Which item to prefer for the chest slot.")
        .defaultValue(ChestplatePreference.Chestplate)
        .visible(autoArmor::get)
        .build()
    );

    // Chestplate Swap
    private final Setting<Boolean> chestplateOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("chestplate-on-ground")
        .description("Wears a chestplate while on the ground and an elytra while in the air.")
        .defaultValue(false)
        .visible(autoArmor::get)
        .build()
    );

    private final Setting<Integer> swapDelay = sgGeneral.add(new IntSetting.Builder()
        .name("swap-delay")
        .description("Ticks to wait after performing a swap.")
        .defaultValue(10)
        .min(0)
        .visible(() -> autoArmor.get() && chestplateOnGround.get())
        .build()
    );

    // Auto Eat
    private final Setting<Boolean> autoEat = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Automatically eats Golden Apples when low on health.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> healthThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("health-threshold")
        .description("Health threshold to trigger auto-eat (in health points, 20 = full).")
        .defaultValue(10)
        .min(1)
        .max(19)
        .sliderRange(1, 19)
        .visible(autoEat::get)
        .build()
    );

    // Disconnect on Player
    private final Setting<Boolean> disconnectOnPlayer = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-player")
        .description("Disconnects if another player is detected nearby.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> playerDetectionRange = sgSafety.add(new IntSetting.Builder()
        .name("player-detection-range")
        .description("Distance to detect other players.")
        .defaultValue(32)
        .min(1)
        .sliderMax(128)
        .visible(disconnectOnPlayer::get)
        .build()
    );

    // Disconnect on Totem Pop
    private final Setting<Boolean> disconnectOnTotemPop = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-totem-pop")
        .description("Disconnects if a totem of undying is used.")
        .defaultValue(false)
        .build()
    );

    private int totemPops = 0;
    private boolean isEating = false;
    private int swapTimer = 0;

    public ServerHealthcareSystem() {
        super(HuntingUtilities.CATEGORY, "server-healthcare-system", "SHS - Manages health and safety features like auto-totem and auto-eat.");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) {
            totemPops = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
        }
        isEating = false;
        swapTimer = 0;
    }

    @Override
    public void onDeactivate() {
        if (isEating) {
            mc.options.useKey.setPressed(false);
            isEating = false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (swapTimer > 0) swapTimer--;

        // Disconnect on Totem Pop
        if (disconnectOnTotemPop.get()) {
            int currentPops = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
            if (currentPops > totemPops) {
                int remainingTotems = countTotems();
                if (mc.player.networkHandler != null) {
                    mc.player.networkHandler.getConnection().disconnect(Text.literal("[SHS] Disconnected on totem pop. " + remainingTotems + " totems remaining."));
                }
                this.toggle();
                return; // Stop further processing
            }
        }

        // Disconnect on Player
        if (disconnectOnPlayer.get()) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player || player.isCreative() || player.isSpectator()) continue;
                if (mc.player.distanceTo(player) <= playerDetectionRange.get()) {
                    if (mc.player.networkHandler != null) {
                        mc.player.networkHandler.getConnection().disconnect(Text.literal("[SHS] Player detected: " + player.getName().getString()));
                    }
                    this.toggle();
                    return; // Stop further processing
                }
            }
        }

        // Auto Totem
        if (autoTotem.get()) {
            if (!mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                FindItemResult totemResult = InvUtils.find(Items.TOTEM_OF_UNDYING);
                if (totemResult.found()) {
                    InvUtils.move().from(totemResult.slot()).toOffhand();
                }
            }
        }

        // Auto Armor
        if (autoArmor.get() && swapTimer == 0) {
            if (chestplateOnGround.get()) {
                handleChestplateElytraSwitch();
            }

            EquipmentSlot[] armorSlots = { EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD };
            for (int i = 0; i < 4; i++) {
                EquipmentSlot slot = armorSlots[i];

                // If chestplate-on-ground is active, it handles the chest slot.
                if (slot == EquipmentSlot.CHEST && chestplateOnGround.get()) continue;

                ItemStack current = mc.player.getEquippedStack(slot);
                int bestValue = getArmorValue(current);

                if (slot == EquipmentSlot.CHEST && chestplatePreference.get() == ChestplatePreference.Elytra) {
                    if (current.isOf(Items.ELYTRA)) bestValue = 1000000;
                }

                int bestSlot = -1;

                for (int j = 0; j < 36; j++) {
                    ItemStack stack = mc.player.getInventory().getStack(j);
                    if (stack.isEmpty()) continue;
                    var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
                    if (equippable == null || equippable.slot() != slot) continue;

                    int value = getArmorValue(stack);

                    if (slot == EquipmentSlot.CHEST && chestplatePreference.get() == ChestplatePreference.Elytra) {
                        if (stack.isOf(Items.ELYTRA)) value = 1000000;
                    }

                    if (value > bestValue) {
                        bestValue = value;
                        bestSlot = j;
                    }
                }

                if (bestSlot != -1) {
                    InvUtils.move().from(bestSlot).toArmor(i);
                }
            }
        }

        // Auto Eat
        if (autoEat.get()) {
            if (mc.player.getHealth() <= healthThreshold.get()) {
                if (!isEating && !mc.player.isUsingItem()) {
                    int gappleSlot = findGapple();
                    if (gappleSlot != -1) {
                        mc.player.getInventory().selectedSlot = gappleSlot;
                        mc.options.useKey.setPressed(true);
                        isEating = true;
                    }
                }
            } else if (isEating) {
                mc.options.useKey.setPressed(false);
                isEating = false;
            }
        }
    }

    private int findGapple() {
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                return i;
            }
        }
        return -1;
    }

    private int countTotems() {
        if (mc.player == null) return 0;
        int count = 0;
        for (ItemStack stack : mc.player.getInventory().main) {
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) {
                count += stack.getCount();
            }
        }
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            count += mc.player.getOffHandStack().getCount();
        }
        return count;
    }

    private int getArmorValue(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        var equippable = stack.getOrDefault(DataComponentTypes.EQUIPPABLE, null);
        if (equippable == null) return -1;
        AttributeModifiersComponent attrs = stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, null);
        if (attrs == null) return 0;
        int armor = 0, toughness = 0;
        for (var entry : attrs.modifiers()) {
            if (entry == null || entry.attribute() == null) continue;
            var keyOpt = entry.attribute().getKey();
            if (keyOpt == null || keyOpt.isEmpty()) continue;
            String id = keyOpt.get().getValue().toString();
            if (entry.modifier() == null) continue;
            int val = (int) entry.modifier().value();
            if (id.equals("minecraft:generic.armor")) armor += val;
            else if (id.equals("minecraft:generic.armor_toughness")) toughness += val;
        }
        return armor * 100 + toughness * 10;
    }

    public enum ChestplatePreference {
        Chestplate,
        Elytra
    }

    private void handleChestplateElytraSwitch() {
        // Prevent swapping to chestplate if RocketPilot is active (it needs Elytra)
        if (Modules.get().get(RocketPilot.class).isActive()) return;

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);

        if (mc.player.isOnGround()) {
            // On ground, we want a chestplate, unless the user has a hard preference for Elytra.
            if (chestplatePreference.get() != ChestplatePreference.Elytra && chest.isOf(Items.ELYTRA)) {
                // Currently wearing elytra, find a chestplate.
                FindItemResult cp = findBestChestplate();
                if (cp.found()) {
                    InvUtils.move().from(cp.slot()).toArmor(2);
                    swapTimer = swapDelay.get();
                }
            }
        } else { // In air
            // In air, we want an elytra.
            if (!chest.isOf(Items.ELYTRA)) {
                // Not wearing elytra, find one.
                FindItemResult elytra = InvUtils.find(Items.ELYTRA);
                if (elytra.found()) {
                    InvUtils.move().from(elytra.slot()).toArmor(2);
                    swapTimer = swapDelay.get();
                }
            }
        }
    }

    private FindItemResult findBestChestplate() {
        int bestValue = -1;
        int bestSlot = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (equippable == null || equippable.slot() != EquipmentSlot.CHEST) continue;
            if (stack.isOf(Items.ELYTRA)) continue;

            int value = getArmorValue(stack);
            if (value > bestValue) {
                bestValue = value;
                bestSlot = i;
            }
        }

        if (bestSlot != -1) {
            return new FindItemResult(bestSlot, mc.player.getInventory().getStack(bestSlot).getCount());
        }
        return new FindItemResult(-1, 0);
    }
}