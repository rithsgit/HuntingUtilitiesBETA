package com.example.addon.modules;

import java.util.Set;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnchantmentListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;

public class ServerHealthcareSystem extends Module {

    // ── Setting Groups ────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgAutoArmor  = settings.createGroup("Auto Armor");
    private final SettingGroup sgAutoEat    = settings.createGroup("Auto Eat");
    private final SettingGroup sgSafety     = settings.createGroup("Safety");

    // ── General ───────────────────────────────────────────────────────────────

    private final Setting<Boolean> autoRespawn = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-respawn")
        .description("Automatically respawns after death.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-totem")
        .description("Automatically equips a totem of undying in your offhand.")
        .defaultValue(true)
        .build()
    );

    // ── Auto Armor ────────────────────────────────────────────────────────────

    private final Setting<Boolean> autoArmor = sgAutoArmor.add(new BoolSetting.Builder()
        .name("auto-armor")
        .description("Automatically equips the best armor in your inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ChestplatePreference> chestplatePreference = sgAutoArmor.add(new EnumSetting.Builder<ChestplatePreference>()
        .name("chestplate-preference")
        .description("Which item to prefer for the chest slot.")
        .defaultValue(ChestplatePreference.Elytra)
        .visible(autoArmor::get)
        .build()
    );

    private final Setting<Keybind> switchPreference = sgAutoArmor.add(new KeybindSetting.Builder()
        .name("switch-preference-key")
        .description("Switches between preferring Chestplate or Elytra.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (chestplatePreference.get() == ChestplatePreference.Chestplate) {
                chestplatePreference.set(ChestplatePreference.Elytra);
            } else {
                chestplatePreference.set(ChestplatePreference.Chestplate);
            }
            info("Chestplate preference set to: %s", chestplatePreference.get().name());
        })
        .visible(autoArmor::get)
        .build()
    );

    private final Setting<Boolean> chestplateOnGround = sgAutoArmor.add(new BoolSetting.Builder()
        .name("chestplate-on-ground")
        .description("Wears a chestplate on the ground and an elytra in the air.")
        .defaultValue(false)
        .visible(autoArmor::get)
        .build()
    );

    private final Setting<Integer> swapDelay = sgAutoArmor.add(new IntSetting.Builder()
        .name("swap-delay")
        .description("Ticks to wait after performing a chest/elytra swap.")
        .defaultValue(10)
        .min(0)
        .visible(() -> autoArmor.get() && chestplateOnGround.get())
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> ignoredEnchantments = sgAutoArmor.add(new EnchantmentListSetting.Builder()
        .name("ignored-enchantments")
        .description("Armor with these enchantments will be ignored by Auto Armor.")
        .defaultValue(Enchantments.BINDING_CURSE)
        .visible(autoArmor::get)
        .build()
    );

    // ── Auto Eat ──────────────────────────────────────────────────────────────

    private final Setting<Boolean> autoEat = sgAutoEat.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Automatically eats Golden Apples when low on health or on fire.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> healthThreshold = sgAutoEat.add(new IntSetting.Builder()
        .name("health-threshold")
        .description("Health at which auto-eat triggers (out of 20).")
        .defaultValue(10)
        .min(1)
        .max(19)
        .sliderRange(1, 19)
        .visible(autoEat::get)
        .build()
    );

    // ── Safety ────────────────────────────────────────────────────────────────

    private final Setting<Boolean> disconnectOnTotemPop = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-totem-pop")
        .description("Disconnects when a totem of undying is consumed.")
        .defaultValue(false)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    // Auto Eat
    private boolean isEating              = false;
    private boolean ateForFire            = false;
    private boolean tookDamageWhileOnFire = false;
    private int     eatHotbarSlot         = -1;
    private Item    eatTargetItem         = null; // the item we committed to eating this session
    private int     eatStartupTicks       = 0;   // grace ticks after starting eat before we check active item
    private float   lastHealth            = -1;

    // Auto Armor
    private int swapTimer = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ServerHealthcareSystem() {
        super(HuntingUtilities.CATEGORY, "server-healthcare-system",
            "SHS — Manages health, safety, tracking, and server monitoring.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        if (mc.player != null) {
            lastHealth = mc.player.getHealth();
        }
        resetState();
    }

    @Override
    public void onDeactivate() {
        stopEating();
        lastHealth = -1;
        resetState();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (mc.player != null) {
            lastHealth = mc.player.getHealth();
        }
        resetState();
        if (autoTotem.get()) {
            tickAutoTotem();
        }
    }

    /** Clears all transient session state. */
    private void resetState() {
        isEating              = false;
        ateForFire            = false;
        tookDamageWhileOnFire = false;
        eatHotbarSlot         = -1;
        eatTargetItem         = null;
        eatStartupTicks       = 0;
        swapTimer             = 0;
    }

    private void stopEating() {
        mc.options.useKey.setPressed(false);
        isEating        = false;
        eatHotbarSlot   = -1;
        eatTargetItem   = null;
        eatStartupTicks = 0;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (swapTimer > 0) swapTimer--;

        tickHealthTracking();
        tickAutoRespawn();
        tickAutoTotem();
        tickAutoArmor();
        tickAutoEat();
    }

    private void tickHealthTracking() {
        if (lastHealth == -1) lastHealth = mc.player.getHealth();
        float health = mc.player.getHealth();

        if (mc.player.isOnFire()) {
            if (health < lastHealth) tookDamageWhileOnFire = true;
        } else {
            ateForFire            = false;
            tookDamageWhileOnFire = false;
        }
        lastHealth = health;
    }

    private void tickAutoRespawn() {
        if (autoRespawn.get() && mc.currentScreen instanceof DeathScreen) {
            mc.player.requestRespawn();
            mc.setScreen(null);
        }
    }

    private void tickAutoTotem() {
        if (!autoTotem.get()) return;
        if (!mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            FindItemResult totem = InvUtils.find(Items.TOTEM_OF_UNDYING);
            if (totem.found()) InvUtils.move().from(totem.slot()).toOffhand();
        }
    }

    private void tickAutoArmor() {
        if (!autoArmor.get() || swapTimer > 0) return;

        if (chestplateOnGround.get()) handleChestplateElytraSwitch();

        EquipmentSlot[] slots = { EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD };
        for (int i = 0; i < 4; i++) {
            EquipmentSlot slot = slots[i];
            if (slot == EquipmentSlot.CHEST && chestplateOnGround.get()) continue;

            ItemStack current   = mc.player.getEquippedStack(slot);
            int       bestValue = getArmorValue(current);

            if (slot == EquipmentSlot.CHEST
                    && chestplatePreference.get() == ChestplatePreference.Elytra
                    && current.isOf(Items.ELYTRA)) {
                bestValue = 1_000_000;
            }

            int bestSlot = -1;
            for (int j = 0; j < 36; j++) {
                ItemStack stack = mc.player.getInventory().getStack(j);
                if (stack.isEmpty()) continue;
                if (hasIgnoredEnchantment(stack)) continue;
                var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
                if (equippable == null || equippable.slot() != slot) continue;

                int value = getArmorValue(stack);
                if (slot == EquipmentSlot.CHEST
                        && chestplatePreference.get() == ChestplatePreference.Elytra
                        && stack.isOf(Items.ELYTRA)) {
                    value = 1_000_000;
                }

                if (value > bestValue) {
                    bestValue = value;
                    bestSlot  = j;
                }
            }

            if (bestSlot != -1) InvUtils.move().from(bestSlot).toArmor(i);
        }
    }

    private void tickAutoEat() {
        if (!autoEat.get()) return;

        if (!isEating) {
            // ── Decide whether to start eating ───────────────────────────────
            boolean needsHealth = mc.player.getHealth() <= healthThreshold.get();
            boolean needsFireEat = mc.player.isOnFire() && tookDamageWhileOnFire && !ateForFire;

            if (!needsHealth && !needsFireEat) return;

            int gappleSlot = findBestGapple();
            if (gappleSlot == -1) return;

            // Remember what item type we are eating so we can validate it later
            eatTargetItem = mc.player.getInventory().getStack(gappleSlot).getItem();

            // Move to hotbar if needed
            if (gappleSlot < 9) {
                eatHotbarSlot = gappleSlot;
            } else {
                eatHotbarSlot = mc.player.getInventory().selectedSlot;
                InvUtils.move().from(gappleSlot).toHotbar(eatHotbarSlot);
            }
            mc.player.getInventory().selectedSlot = eatHotbarSlot;

            // Give the server/client 3 ticks to reflect the item move before
            // we check whether the active item is correct. Without this grace
            // period the stop-condition fires on the very first tick.
            eatStartupTicks = 3;

            mc.options.useKey.setPressed(true);
            isEating = true;

            if (needsFireEat) {
                ateForFire            = true;
                tookDamageWhileOnFire = false;
            }

        } else {
            // ── Already eating — keep holding or stop ─────────────────────────

            // During startup grace period just hold the key and wait
            if (eatStartupTicks > 0) {
                eatStartupTicks--;
                // Make sure we are still on the right hotbar slot
                mc.player.getInventory().selectedSlot = eatHotbarSlot;
                mc.options.useKey.setPressed(true);
                return;
            }

            // After grace period, check that we are actually eating the right item
            ItemStack hotbarStack = mc.player.getInventory().getStack(eatHotbarSlot);
            boolean hotbarHasGapple = eatTargetItem != null && hotbarStack.isOf(eatTargetItem);

            // Also accept if the player is actively using a gapple (covers edge cases
            // where the item animates but selectedSlot temporarily differs)
            ItemStack activeItem = mc.player.getActiveItem();
            boolean activeIsGapple = activeItem.isOf(Items.GOLDEN_APPLE)
                    || activeItem.isOf(Items.ENCHANTED_GOLDEN_APPLE);

            if (!hotbarHasGapple && !activeIsGapple) {
                // Gapple is gone (eaten or moved) — stop
                stopEating();
                return;
            }

            // Keep hotbar slot and use key locked
            mc.player.getInventory().selectedSlot = eatHotbarSlot;
            mc.options.useKey.setPressed(true);

            // Stop once the player finishes using the item naturally
            if (!mc.player.isUsingItem()) {
                stopEating();
            }
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null || !disconnectOnTotemPop.get()) return;

        if (event.packet instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35 && packet.getEntity(mc.world) != null && packet.getEntity(mc.world).getId() == mc.player.getId()) {
                disconnect("[SHS] Disconnected on totem pop. " + countTotems() + " totems remaining.");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void disconnect(String reason) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getConnection().disconnect(Text.literal(reason));
        }
        this.toggle();
    }

    private void handleChestplateElytraSwitch() {
        if (Modules.get().get(RocketPilot.class).isActive()) return;

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (mc.player.isOnGround()) {
            if (chestplatePreference.get() != ChestplatePreference.Elytra && chest.isOf(Items.ELYTRA)) {
                FindItemResult cp = findBestChestplate();
                if (cp.found()) { InvUtils.move().from(cp.slot()).toArmor(2); swapTimer = swapDelay.get(); }
            }
        } else {
            if (!chest.isOf(Items.ELYTRA)) {
                FindItemResult elytra = InvUtils.find(Items.ELYTRA);
                if (elytra.found()) { InvUtils.move().from(elytra.slot()).toArmor(2); swapTimer = swapDelay.get(); }
            }
        }
    }

    private FindItemResult findBestChestplate() {
        int bestValue = -1, bestSlot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (equippable == null || equippable.slot() != EquipmentSlot.CHEST) continue;
            if (stack.isOf(Items.ELYTRA)) continue;
            int value = getArmorValue(stack);
            if (value > bestValue) { bestValue = value; bestSlot = i; }
        }
        return bestSlot != -1
            ? new FindItemResult(bestSlot, mc.player.getInventory().getStack(bestSlot).getCount())
            : new FindItemResult(-1, 0);
    }

    private boolean hasIgnoredEnchantment(ItemStack stack) {
        if (ignoredEnchantments.get().isEmpty()) return false;
        ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants == null) return false;
        for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
            if (entry.getKey().isPresent() && ignoredEnchantments.get().contains(entry.getKey().get())) return true;
        }
        return false;
    }

    private int getArmorValue(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        if (stack.getOrDefault(DataComponentTypes.EQUIPPABLE, null) == null) return -1;

        AttributeModifiersComponent attrs = stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, null);
        double armor = 0, toughness = 0;

        if (attrs != null) {
            for (var entry : attrs.modifiers()) {
                if (entry == null || entry.attribute() == null || entry.modifier() == null) continue;
                var keyOpt = entry.attribute().getKey();
                if (keyOpt == null || keyOpt.isEmpty()) continue;
                String id = keyOpt.get().getValue().toString();
                double v  = entry.modifier().value();
                if      (id.equals("minecraft:generic.armor"))           armor     += v;
                else if (id.equals("minecraft:generic.armor_toughness")) toughness += v;
            }
        }

        double enchBonus =
              getEnchantmentLevel(stack, "minecraft:protection")            * 3.0
            + getEnchantmentLevel(stack, "minecraft:fire_protection")       * 1.0
            + getEnchantmentLevel(stack, "minecraft:projectile_protection") * 1.0;

        return (int) (armor * 100 + toughness * 10 + enchBonus);
    }

    private int countTotems() {
        if (mc.player == null) return 0;
        int count = 0;
        for (ItemStack stack : mc.player.getInventory().main) {
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) count += mc.player.getOffHandStack().getCount();
        return count;
    }

    /**
     * Finds the best gapple to eat based on a fixed priority:
     * 1. Enchanted Gapple (Hotbar)
     * 2. Gapple (Hotbar)
     * 3. Enchanted Gapple (Inventory)
     * 4. Gapple (Inventory)
     */
    private int findBestGapple() {
        int hotbarGapple      = -1;
        int inventoryEgapple  = -1;
        int inventoryGapple   = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            if (stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)) {
                if (i < 9) return i;
                if (inventoryEgapple == -1) inventoryEgapple = i;
            } else if (stack.isOf(Items.GOLDEN_APPLE)) {
                if (i < 9) { if (hotbarGapple == -1) hotbarGapple = i; }
                else        { if (inventoryGapple == -1) inventoryGapple = i; }
            }
        }

        if (hotbarGapple     != -1) return hotbarGapple;
        if (inventoryEgapple != -1) return inventoryEgapple;
        return inventoryGapple;
    }

    private int getEnchantmentLevel(ItemStack stack, String id) {
        ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants == null) return 0;
        for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
            if (entry.getKey().isPresent() && entry.getKey().get().getValue().toString().equals(id))
                return enchants.getLevel(entry);
        }
        return 0;
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum ChestplatePreference { Chestplate, Elytra }
}