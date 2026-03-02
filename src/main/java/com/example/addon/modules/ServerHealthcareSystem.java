package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ServerHealthcareSystem extends Module {

    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgSafety     = settings.createGroup("Safety");
    private final SettingGroup sgAutoIgnore = settings.createGroup("Auto Ignore");

    // ── General ──────────────────────────────────────────────────────────────

    private final Setting<Boolean> autoTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-totem")
        .description("Automatically equips a totem of undying in your offhand.")
        .defaultValue(true)
        .build()
    );

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

    // ── Safety ────────────────────────────────────────────────────────────────

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

    private final Setting<Boolean> disconnectOnTotemPop = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-totem-pop")
        .description("Disconnects if a totem of undying is used.")
        .defaultValue(false)
        .build()
    );

    // ── Auto Ignore ───────────────────────────────────────────────────────────

    private final Setting<Boolean> autoIgnore = sgAutoIgnore.add(new BoolSetting.Builder()
        .name("auto-ignore")
        .description("Silently runs /ignorehard on players who say certain keywords in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> ignoreKeywords = sgAutoIgnore.add(new StringListSetting.Builder()
        .name("keywords")
        .description("If any of these words appear in a player's message body, they will be /ignorehard'd.")
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

    // ── State ─────────────────────────────────────────────────────────────────

    /** Players ignored this session. Cleared on toggle or server join. */
    private final Set<String> ignoredThisSession = new HashSet<>();

    private int totemPops = 0;
    private boolean isEating = false;
    /**
     * The hotbar slot (0-8) we committed to eating from.
     * Always a valid hotbar index while isEating == true. -1 otherwise.
     */
    private int eatHotbarSlot = -1;
    private int swapTimer = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ServerHealthcareSystem() {
        super(HuntingUtilities.CATEGORY, "server-healthcare-system",
            "SHS - Manages health and safety features like auto-totem and auto-eat.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        if (mc.player != null) {
            totemPops = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
        }
        isEating = false;
        eatHotbarSlot = -1;
        swapTimer = 0;
        ignoredThisSession.clear();
    }

    @Override
    public void onDeactivate() {
        if (isEating) {
            mc.options.useKey.setPressed(false);
            isEating = false;
            eatHotbarSlot = -1;
        }
        ignoredThisSession.clear();
    }

    /** Clear the ignored-set when joining a new server so old names don't carry over. */
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        ignoredThisSession.clear();
        if (mc.player != null) {
            totemPops = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
        }
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

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
                    mc.player.networkHandler.getConnection().disconnect(
                        Text.literal("[SHS] Disconnected on totem pop. " + remainingTotems + " totems remaining.")
                    );
                }
                this.toggle();
                return;
            }
            totemPops = currentPops;
        }

        // Disconnect on Player
        if (disconnectOnPlayer.get()) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player || player.isCreative() || player.isSpectator()) continue;
                if (mc.player.distanceTo(player) <= playerDetectionRange.get()) {
                    if (mc.player.networkHandler != null) {
                        mc.player.networkHandler.getConnection().disconnect(
                            Text.literal("[SHS] Player detected: " + player.getName().getString())
                        );
                    }
                    this.toggle();
                    return;
                }
            }
        }

        // Auto Totem
        if (autoTotem.get()) {
            if (!mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                FindItemResult totem = InvUtils.find(Items.TOTEM_OF_UNDYING);
                if (totem.found()) InvUtils.move().from(totem.slot()).toOffhand();
            }
        }

        // Auto Armor
        if (autoArmor.get() && swapTimer == 0) {
            if (chestplateOnGround.get()) handleChestplateElytraSwitch();

            EquipmentSlot[] armorSlots = { EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD };
            for (int i = 0; i < 4; i++) {
                EquipmentSlot slot = armorSlots[i];
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

        // Auto Eat
        if (autoEat.get()) {
            float health = mc.player.getHealth();

            if (health <= healthThreshold.get()) {
                if (!isEating && !mc.player.isUsingItem()) {
                    int found = findGapple();
                    if (found != -1) {
                        if (found < 9) {
                            // Already in hotbar — select directly
                            eatHotbarSlot = found;
                        } else {
                            // In main inventory — move to current hotbar slot, eat from there
                            eatHotbarSlot = mc.player.getInventory().selectedSlot;
                            InvUtils.move().from(found).toHotbar(eatHotbarSlot);
                        }
                        mc.player.getInventory().selectedSlot = eatHotbarSlot;
                        mc.options.useKey.setPressed(true);
                        isEating = true;
                    }
                } else if (isEating && eatHotbarSlot != -1) {
                    // Pin the hotbar slot for the duration of the eat animation
                    if (mc.player.getInventory().selectedSlot != eatHotbarSlot) {
                        mc.player.getInventory().selectedSlot = eatHotbarSlot;
                    }
                }
            } else if (isEating) {
                mc.options.useKey.setPressed(false);
                isEating = false;
                eatHotbarSlot = -1;
            }
        }
    }

    // ── Auto Ignore ───────────────────────────────────────────────────────────

    /**
     * Listens to every incoming chat message. If auto-ignore is enabled and the
     * message body contains a flagged keyword, silently runs /ignorehard on the sender.
     *
     * Supported chat formats:
     *   <PlayerName> message text     (standard vanilla)
     *   PlayerName: message text      (some plugin formats)
     *
     * Sender and body are parsed separately so keywords can never accidentally
     * match the sender's name and trigger a false positive.
     */
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!autoIgnore.get()) return;
        if (mc.player == null || mc.player.networkHandler == null) return;

        List<String> keywords = ignoreKeywords.get();
        if (keywords.isEmpty()) return;

        String raw = event.getMessage().getString();

        String sender;
        String messageBody;

        if (raw.startsWith("<")) {
            // Format: <PlayerName> message
            int close = raw.indexOf('>');
            if (close < 1) return;
            sender      = raw.substring(1, close).trim();
            messageBody = raw.substring(close + 1).trim();
        } else {
            // Format: PlayerName: message
            int colon = raw.indexOf(':');
            if (colon < 1 || colon >= 20) return;
            String possibleName = raw.substring(0, colon);
            if (possibleName.contains(" ")) return; // reject prefixed/decorated names
            sender      = possibleName.trim();
            messageBody = raw.substring(colon + 1).trim();
        }

        if (sender.equalsIgnoreCase(mc.player.getName().getString())) return;
        if (ignoredThisSession.contains(sender.toLowerCase())) return;

        boolean matched = false;
        for (String keyword : keywords) {
            if (keyword.isBlank()) continue;
            if (ignoreCaseSensitive.get()) {
                if (messageBody.contains(keyword)) { matched = true; break; }
            } else {
                if (messageBody.toLowerCase().contains(keyword.toLowerCase())) { matched = true; break; }
            }
        }

        if (!matched) return;

        mc.player.networkHandler.sendChatCommand("ignorehard " + sender);
        ignoredThisSession.add(sender.toLowerCase());

        if (ignoreNotify.get()) {
            mc.player.sendMessage(
                Text.literal("[SHS] Auto-ignored " + sender + " (keyword match)."), false
            );
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Searches hotbar first (no item move needed), then main inventory.
     * Enchanted golden apples are preferred over regular ones in both zones.
     */
    private int findGapple() {
        int regularFallback = -1;
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.ENCHANTED_GOLDEN_APPLE) return i;
            if (item == Items.GOLDEN_APPLE && regularFallback == -1) regularFallback = i;
        }
        if (regularFallback != -1) return regularFallback;
        for (int i = 9; i < 36; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.ENCHANTED_GOLDEN_APPLE) return i;
            if (item == Items.GOLDEN_APPLE && regularFallback == -1) regularFallback = i;
        }
        return regularFallback;
    }

    private int countTotems() {
        if (mc.player == null) return 0;
        int count = 0;
        for (ItemStack stack : mc.player.getInventory().main) {
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            count += mc.player.getOffHandStack().getCount();
        }
        return count;
    }

    /**
     * Scores an armor piece by armor, toughness, and protection enchantments.
     * Uses double precision to avoid truncation of fractional attribute values.
     */
    private int getArmorValue(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        var equippable = stack.getOrDefault(DataComponentTypes.EQUIPPABLE, null);
        if (equippable == null) return -1;

        AttributeModifiersComponent attrs = stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, null);
        double armor = 0, toughness = 0;

        if (attrs != null) {
            for (var entry : attrs.modifiers()) {
                if (entry == null || entry.attribute() == null || entry.modifier() == null) continue;
                var keyOpt = entry.attribute().getKey();
                if (keyOpt == null || keyOpt.isEmpty()) continue;
                String id  = keyOpt.get().getValue().toString();
                double val = entry.modifier().value();
                if (id.equals("minecraft:generic.armor"))                armor    += val;
                else if (id.equals("minecraft:generic.armor_toughness")) toughness += val;
            }
        }

        double enchantBonus =
              getEnchantmentLevel(stack, "minecraft:protection")            * 3.0
            + getEnchantmentLevel(stack, "minecraft:fire_protection")       * 1.0
            + getEnchantmentLevel(stack, "minecraft:projectile_protection") * 1.0;

        return (int) (armor * 100 + toughness * 10 + enchantBonus);
    }

    /** Returns the level of a named enchantment on a stack, or 0 if absent. */
    private int getEnchantmentLevel(ItemStack stack, String enchantmentId) {
        var enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants == null) return 0;
        for (var entry : enchants.getEnchantments()) {
            var key = entry.getKey();
            if (key.isPresent() && key.get().getValue().toString().equals(enchantmentId)) {
                return enchants.getLevel(entry);
            }
        }
        return 0;
    }

    private void handleChestplateElytraSwitch() {
        if (Modules.get().get(RocketPilot.class).isActive()) return;

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);

        if (mc.player.isOnGround()) {
            if (chestplatePreference.get() != ChestplatePreference.Elytra && chest.isOf(Items.ELYTRA)) {
                FindItemResult cp = findBestChestplate();
                if (cp.found()) {
                    InvUtils.move().from(cp.slot()).toArmor(2);
                    swapTimer = swapDelay.get();
                }
            }
        } else {
            if (!chest.isOf(Items.ELYTRA)) {
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
        int bestSlot  = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (equippable == null || equippable.slot() != EquipmentSlot.CHEST) continue;
            if (stack.isOf(Items.ELYTRA)) continue;

            int value = getArmorValue(stack);
            if (value > bestValue) {
                bestValue = value;
                bestSlot  = i;
            }
        }

        if (bestSlot != -1) {
            return new FindItemResult(bestSlot, mc.player.getInventory().getStack(bestSlot).getCount());
        }
        return new FindItemResult(-1, 0);
    }

    // ── Enum ──────────────────────────────────────────────────────────────────

    public enum ChestplatePreference {
        Chestplate,
        Elytra
    }
}