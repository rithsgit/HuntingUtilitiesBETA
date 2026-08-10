package com.example.addon.modules;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

public class Mendbot extends Module {
    public enum MendTarget { Elytra, Tools, Armour, All }
    public enum MendSource { Bottles, Mining, Leveling }

    public enum MiningPreset {
        All_Materials,
        Overworld_Set,
        Nether_Set,
        Ancient_Debris,
        Nether_Quartz,
        Iron,
        Gold,
        Diamond,
        Copper,
        Coal,
        Lapis,
        Redstone,
        Emerald;

        @Override
        public String toString() {
            return name().replace("_", " ");
        }
    }

    private enum MiningState {
        SEARCHING,
        EQUIPPING,
        REPAIRING,
        PAUSED,
        FINISHED
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgOres = settings.createGroup("Smart Ores");
    private final SettingGroup sgLeveling = settings.createGroup("Leveling");

    private static final List<String> OVERWORLD_ORES = List.of("iron_ore", "gold_ore", "copper_ore", "coal_ore", "diamond_ore", "lapis_ore", "redstone_ore", "emerald_ore");
    private static final List<String> NETHER_ORES = List.of("nether_quartz_ore", "ancient_debris", "nether_gold_ore");

    // --- Core Settings ---
    private final Setting<MendSource> mendSource = sgGeneral.add(new EnumSetting.Builder<MendSource>()
        .name("mend-source")
        .description("How to get XP (Bottles, Mining, or Leveling).")
        .defaultValue(MendSource.Bottles)
        .build()
    );

    private final Setting<MendTarget> mendTarget = sgGeneral.add(new EnumSetting.Builder<MendTarget>()
        .name("mend-target")
        .description("What to repair.")
        .defaultValue(MendTarget.Elytra)
        .visible(() -> mendSource.get() != MendSource.Leveling)
        .build()
    );

    // --- Leveling Settings ---
    private final Setting<Integer> targetLevel = sgLeveling.add(new IntSetting.Builder()
        .name("target-level")
        .description("The XP level to reach.")
        .defaultValue(30)
        .min(1)
        .max(21863)
        .sliderMax(100)
        .visible(() -> mendSource.get() == MendSource.Leveling)
        .build()
    );

    private final Setting<Integer> minBottleSlots = sgLeveling.add(new IntSetting.Builder()
        .name("min-bottle-slots")
        .description("Minimum hotbar slots with XP bottles before resuming. 0 = only pause when completely out.")
        .defaultValue(0)
        .min(0)
        .max(9)
        .sliderMax(9)
        .visible(() -> mendSource.get() == MendSource.Leveling)
        .build()
    );

    // --- Smart Mining Settings ---
    private final Setting<Boolean> useSmartMining = sgGeneral.add(new BoolSetting.Builder()
        .name("use-smart-mining")
        .description("Automatically selects ores based on dimension (Nether/Overworld).")
        .defaultValue(true)
        .visible(() -> mendSource.get() == MendSource.Mining)
        .build()
    );

    private final Setting<MiningPreset> miningPreset = sgOres.add(new EnumSetting.Builder<MiningPreset>()
        .name("mining-preset")
        .description("Select the mining target.")
        .defaultValue(MiningPreset.All_Materials)
        .visible(() -> mendSource.get() == MendSource.Mining && useSmartMining.get())
        .build()
    );

    // --- Baritone Settings ---
    private final Setting<String> baritoneStartCommand = sgGeneral.add(new StringSetting.Builder()
        .name("baritone-start")
        .description("Manual command to run (Only used if Smart Mining is off).")
        .defaultValue("#mine nether_quartz_ore")
        .visible(() -> mendSource.get() == MendSource.Mining && !useSmartMining.get())
        .build()
    );

    private final Setting<String> baritonePauseCommand = sgGeneral.add(new StringSetting.Builder()
        .name("baritone-pause")
        .description("Command to pause Baritone before swapping items.")
        .defaultValue("#pause")
        .visible(() -> mendSource.get() == MendSource.Mining)
        .build()
    );

    private final Setting<String> baritoneResumeCommand = sgGeneral.add(new StringSetting.Builder()
        .name("baritone-resume")
        .description("Command to resume Baritone after swapping items.")
        .defaultValue("#resume")
        .visible(() -> mendSource.get() == MendSource.Mining)
        .build()
    );

    private final Setting<String> baritoneStopCommand = sgGeneral.add(new StringSetting.Builder()
        .name("baritone-stop")
        .description("Command to run when stopping Mining Mode.")
        .defaultValue("#stop")
        .visible(() -> mendSource.get() == MendSource.Mining)
        .build()
    );

    private final Setting<Integer> swapDelay = sgGeneral.add(new IntSetting.Builder()
        .name("swap-delay")
        .description("Ticks to wait after pausing before swapping items.")
        .defaultValue(10)
        .min(0)
        .sliderMax(40)
        .visible(() -> mendSource.get() == MendSource.Mining)
        .build()
    );
    
    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks to wait after resuming and swapping (Fixes kicking).")
        .defaultValue(5)
        .min(0)
        .sliderMax(20)
        .visible(() -> mendSource.get() == MendSource.Mining)
        .build()
    );

    // --- Safety Settings ---
    private final Setting<Boolean> lowHealthDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("low-health-disable")
        .description("Automatically disable the module if your health drops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> healthThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("health-threshold")
        .description("The health percentage to disable at.")
        .defaultValue(6)
        .min(1)
        .max(20)
        .sliderMax(20)
        .visible(lowHealthDisable::get)
        .build()
    );

    private final Setting<Boolean> goldenHelmet = sgGeneral.add(new BoolSetting.Builder()
        .name("golden-helmet")
        .description("Equips a golden helmet for safety (e.g. piglin bartering).")
        .defaultValue(false)
        .visible(() -> mendSource.get() == MendSource.Mining)
        .build()
    );

    // --- Bottle Settings ---
    private final Setting<Integer> packetsPerBurst = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-burst")
        .description("How many XP bottles to throw per burst.")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .visible(() -> mendSource.get() == MendSource.Bottles || mendSource.get() == MendSource.Leveling)
        .build()
    );

    private final Setting<Integer> burstDelay = sgGeneral.add(new IntSetting.Builder()
        .name("burst-delay")
        .description("Ticks to wait between bursts.")
        .defaultValue(4)
        .min(0)
        .sliderMax(20)
        .visible(() -> mendSource.get() == MendSource.Bottles || mendSource.get() == MendSource.Leveling)
        .build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Disable module when finished or out of XP.")
        .defaultValue(true)
        .build()
    );

    // Fields
    private int mendTimer = 0;
    private ItemStack savedHelmet = ItemStack.EMPTY;
    private boolean isPaused = false;
    
    private MiningState miningState = MiningState.SEARCHING;
    private int currentRepairSlot = -1;
    private EquipmentSlot targetEquipSlot = null;
    private boolean targetIsOffhand = false;
    private int swapTimer = 0;
    private boolean startCommandSent = false;

    public Mendbot() {
        super(Tim.CATEGORY, "mendbot", "Automatically mends items using XP bottles or Mining.");
    }

    @Override
    public void onActivate() {
        mendTimer = 0;
        startCommandSent = false;
        isPaused = false;
        
        if (mendSource.get() == MendSource.Mining) {
            miningState = MiningState.SEARCHING;
            currentRepairSlot = -1;
            targetEquipSlot = null;
            targetIsOffhand = false;
            swapTimer = 0;
        }

        if (mc.player != null) {
            savedHelmet = mc.player.getEquippedStack(EquipmentSlot.HEAD).copy();
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null) {
            if (mc.player.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.GOLDEN_HELMET)) {
                restoreHelmet(savedHelmet);
            }
            if (mendSource.get() == MendSource.Mining && !baritoneStopCommand.get().isEmpty()) {
                mc.player.networkHandler.sendChatMessage(baritoneStopCommand.get());
            }
        }
    }

    private int findItemSlot(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }
    
    private boolean isHotbar(int slot) { return slot >= 0 && slot < 9; }
    
    private int countHotbarBottles() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.EXPERIENCE_BOTTLE)) {
                count++;
            }
        }
        return count;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (goldenHelmet.get() && mendSource.get() == MendSource.Mining && mc.player.age % 10 == 0) {
            if (!mc.player.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.GOLDEN_HELMET)) {
                int goldHelmSlot = findItemSlot(Items.GOLDEN_HELMET);
                if (goldHelmSlot != -1) InvUtils.move().from(goldHelmSlot).toArmor(3);
            }
        }

        if (!startCommandSent && mendSource.get() == MendSource.Mining) {
            String cmd = useSmartMining.get() ? getSmartOreCommand() : baritoneStartCommand.get();
            if (cmd != null && !cmd.isEmpty()) {
                info("Starting Baritone: " + cmd);
                mc.player.networkHandler.sendChatMessage(cmd);
                startCommandSent = true;
            }
        }

        if (lowHealthDisable.get() && mc.player.getHealth() <= healthThreshold.get()) {
            error("Low health detected! Disabling...");
            toggle();
            return;
        }

        if (mendSource.get() == MendSource.Leveling) {
            handleLeveling();
            return;
        }

        if (mendSource.get() == MendSource.Mining) {
            handleMiningMendingStateMachine();
            return;
        }

        if (mendTimer > 0) { mendTimer--; return; }

        int xpSlot = findItemSlot(Items.EXPERIENCE_BOTTLE);
        if (xpSlot == -1) {
            info("No more XP bottles — stopping.");
            if (autoDisable.get()) toggle();
            return;
        }

        boolean finished = false;
        switch (mendTarget.get()) {
            case Elytra -> finished = !handleElytraMending();
            case Tools -> finished = !handleToolMending();
            case Armour -> finished = !handleArmourMending();
            case All -> finished = !handleElytraMending() && !handleToolMending() && !handleArmourMending();
        }

        if (finished) {
            info("Mending complete.");
            if (autoDisable.get()) toggle();
        }
    }

    // --- Leveling Logic ---
    private void handleLeveling() {
        // Check if target level reached
        if (mc.player.experienceLevel >= targetLevel.get()) {
            info("Target level " + targetLevel.get() + " reached!");
            if (autoDisable.get()) toggle();
            return;
        }

        // Count bottles in hotbar
        int hotbarBottles = countHotbarBottles();
        int anyBottleSlot = findItemSlot(Items.EXPERIENCE_BOTTLE);

        // Determine if we should pause
        boolean shouldPause = false;

        if (anyBottleSlot == -1) {
            // No bottles at all
            shouldPause = true;
        } else if (minBottleSlots.get() > 0 && hotbarBottles < minBottleSlots.get()) {
            // Not enough bottles in hotbar
            shouldPause = true;
        }

        if (shouldPause) {
            if (!isPaused) {
                if (anyBottleSlot == -1) {
                    warning("No XP bottles detected — pausing...");
                } else {
                    warning("Waiting for XP bottles... (" + hotbarBottles + "/" + minBottleSlots.get() + " hotbar slots)");
                }
                isPaused = true;
            }
            return;
        }

        // We have bottles and meet the minimum requirement
        if (isPaused) {
            info("XP bottles available — resuming.");
            isPaused = false;
        }

        if (mendTimer > 0) { mendTimer--; return; }

        // Throw bottles
        throwXpBottles();
    }

    // --- Smart Mining Logic ---
    private String getSmartOreCommand() {
        StringBuilder sb = new StringBuilder("#mine ");
        boolean first = true;

        MiningPreset preset = miningPreset.get();
        List<String> targetOres = null;

        switch (preset) {
            case All_Materials -> {
                targetOres = new ArrayList<>(OVERWORLD_ORES);
                targetOres.addAll(NETHER_ORES);
                break;
            }
            case Overworld_Set -> targetOres = OVERWORLD_ORES;
            case Nether_Set -> targetOres = NETHER_ORES;
            default -> {
                String oreName = getOreName(preset);
                if (oreName != null) {
                    sb.append(oreName);
                    return sb.toString();
                } else {
                    return "#mine"; 
                }
            }
        }

        for (String ore : targetOres) {
            if (!first) sb.append(",");
            sb.append(ore);
            first = false;
        }

        return sb.toString();
    }

    private String getOreName(MiningPreset preset) {
        return switch (preset) {
            case Iron -> "iron_ore";
            case Gold -> "gold_ore";
            case Copper -> "copper_ore";
            case Coal -> "coal_ore";
            case Diamond -> "diamond_ore";
            case Lapis -> "lapis_ore";
            case Redstone -> "redstone_ore";
            case Emerald -> "emerald_ore";
            case Ancient_Debris -> "ancient_debris";
            case Nether_Quartz -> "nether_quartz_ore";
            default -> null;
        };
    }

    // --- State Machine ---
    private void handleMiningMendingStateMachine() {
        switch (miningState) {
            case SEARCHING -> {
                int foundSlot = findNextDamagedItem();
                if (foundSlot == -1) {
                    if (baritoneStopCommand.get() != null && !baritoneStopCommand.get().isEmpty()) {
                        info("All items repaired. Stopping Baritone.");
                        mc.player.networkHandler.sendChatMessage(baritoneStopCommand.get());
                    }
                    miningState = MiningState.FINISHED;
                    if (autoDisable.get()) toggle();
                    return;
                }
                
                ItemStack stack = mc.player.getInventory().getStack(foundSlot);
                if (stack.isEmpty()) {
                    return;
                }
                
                targetEquipSlot = getTargetEquipmentSlot(stack);
                targetIsOffhand = (targetEquipSlot == null && isTool(stack));
                
                if (targetEquipSlot == EquipmentSlot.HEAD && goldenHelmet.get()) {
                    return; 
                }
                
                equipItem(foundSlot, targetEquipSlot, targetIsOffhand);
                currentRepairSlot = foundSlot;
                miningState = MiningState.EQUIPPING;
                swapTimer = 4; 
            }
            case EQUIPPING -> {
                if (swapTimer > 0) {
                    swapTimer--;
                    return;
                }
                
                ItemStack equipped = targetIsOffhand ? 
                    mc.player.getOffHandStack() : 
                    (targetEquipSlot != null ? mc.player.getEquippedStack(targetEquipSlot) : ItemStack.EMPTY);
                    
                if (!equipped.isEmpty() && equipped.isDamaged()) {
                    miningState = MiningState.REPAIRING;
                    info("Repairing: " + equipped.getName().getString());
                } else if (equipped.isEmpty()) {
                    miningState = MiningState.SEARCHING;
                } else {
                    miningState = MiningState.REPAIRING;
                }
            }
            case REPAIRING -> {
                ItemStack equipped = targetIsOffhand ? 
                    mc.player.getOffHandStack() : 
                    (targetEquipSlot != null ? mc.player.getEquippedStack(targetEquipSlot) : ItemStack.EMPTY);
                
                if (equipped.isEmpty() || !equipped.isDamaged()) {
                    info("Item repaired. Pausing to swap.");
                    if (!baritonePauseCommand.get().isEmpty()) {
                        mc.player.networkHandler.sendChatMessage(baritonePauseCommand.get());
                    }
                    swapTimer = swapDelay.get();
                    miningState = MiningState.PAUSED;
                }
            }
            case PAUSED -> {
                if (swapTimer > 0) {
                    swapTimer--;
                    return;
                }
                
                ItemStack equipped = targetIsOffhand ? 
                    mc.player.getOffHandStack() : 
                    (targetEquipSlot != null ? mc.player.getEquippedStack(targetEquipSlot) : ItemStack.EMPTY);
                    
                if (!equipped.isEmpty()) {
                    int emptySlot = mc.player.getInventory().getEmptySlot();
                    if (targetIsOffhand) {
                        if (emptySlot != -1) InvUtils.move().fromOffhand().to(emptySlot);
                        else InvUtils.move().fromOffhand().toHotbar(0);
                    } else if (targetEquipSlot != null) {
                        int armorIdx = armorSlotIndex(targetEquipSlot);
                        if (emptySlot != -1) InvUtils.move().fromArmor(armorIdx).to(emptySlot);
                        else InvUtils.move().fromArmor(armorIdx).toHotbar(0);
                    }
                }

                currentRepairSlot = -1;
                targetEquipSlot = null;
                targetIsOffhand = false;
                
                if (!baritoneResumeCommand.get().isEmpty()) {
                    mc.player.networkHandler.sendChatMessage(baritoneResumeCommand.get());
                }
                miningState = MiningState.SEARCHING;
                swapTimer = actionDelay.get();
            }
            case FINISHED -> {
                // Do nothing
            }
        }
    }

    private int findNextDamagedItem() {
        boolean doElytra = (mendTarget.get() == MendTarget.All || mendTarget.get() == MendTarget.Elytra);
        boolean doTools = (mendTarget.get() == MendTarget.All || mendTarget.get() == MendTarget.Tools);
        boolean doArmour = (mendTarget.get() == MendTarget.All || mendTarget.get() == MendTarget.Armour);

        if (mc.player == null) return -1;

        if (doElytra) {
            int slot = findDamagedItem(stack -> stack.isOf(Items.ELYTRA));
            if (slot != -1) return slot;
        }

        if (doTools) {
            int slot = findDamagedItem(this::isTool);
            if (slot != -1) return slot;
        }

        if (doArmour) {
            int slot = findDamagedItem(stack -> {
                if (stack.getItem() instanceof ArmorItem && !stack.isOf(Items.ELYTRA)) {
                    if (goldenHelmet.get()) {
                        var eq = stack.get(DataComponentTypes.EQUIPPABLE);
                        return eq != null && eq.slot() != EquipmentSlot.HEAD;
                    }
                    return true;
                }
                return false;
            });
            if (slot != -1) return slot;
        }

        return -1;
    }

    private int findDamagedItem(java.util.function.Predicate<ItemStack> predicate) {
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (predicate.test(stack) && stack.isDamaged()) {
                return i;
            }
        }
        return -1;
    }

    private EquipmentSlot getTargetEquipmentSlot(ItemStack stack) {
        if (stack.isOf(Items.ELYTRA)) {
            return EquipmentSlot.CHEST;
        }
        if (stack.getItem() instanceof ArmorItem) {
            var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (equippable != null) {
                return equippable.slot();
            }
        }
        return null;
    }

    private int armorSlotIndex(EquipmentSlot slot) {
        return switch (slot) {
            case FEET -> 0;
            case LEGS -> 1;
            case CHEST -> 2;
            case HEAD -> 3;
            default -> -1;
        };
    }

    private void equipItem(int fromSlot, EquipmentSlot slot, boolean offhand) {
        if (offhand) {
            ItemStack offHand = mc.player.getOffHandStack();
            if (!offHand.isEmpty()) {
                int emptySlot = mc.player.getInventory().getEmptySlot();
                if (emptySlot != -1) InvUtils.move().fromOffhand().to(emptySlot);
                else InvUtils.move().fromOffhand().toHotbar(0);
            }
            InvUtils.move().from(fromSlot).toOffhand();
        } else if (slot != null) {
            int armorIdx = armorSlotIndex(slot);
            ItemStack currentArmor = mc.player.getEquippedStack(slot);
            if (!currentArmor.isEmpty()) {
                int emptySlot = mc.player.getInventory().getEmptySlot();
                if (emptySlot != -1) InvUtils.move().fromArmor(armorIdx).to(emptySlot);
                else InvUtils.move().fromArmor(armorIdx).toHotbar(0);
            }
            InvUtils.move().from(fromSlot).toArmor(armorIdx);
        }
    }

    // --- Safety ---
    private void restoreHelmet(ItemStack original) {
        ItemStack current = mc.player.getEquippedStack(EquipmentSlot.HEAD);
        if (ItemStack.areItemsAndComponentsEqual(current, original)) return;
        if (!current.isEmpty()) {
            int empty = mc.player.getInventory().getEmptySlot();
            if (empty != -1) InvUtils.move().fromArmor(3).to(empty);
            else {
                int same = findItemSlot(current.getItem());
                if (same != -1 && !isHotbar(same)) InvUtils.move().fromArmor(3).to(same);
                else InvUtils.move().fromArmor(3).toHotbar(0);
            }
        }
        if (!original.isEmpty()) {
            int saved = -1;
            for (int i = 0; i < mc.player.getInventory().size(); i++) {
                if (ItemStack.areItemsAndComponentsEqual(mc.player.getInventory().getStack(i), original)) { saved = i; break; }
            }
            if (saved != -1) InvUtils.move().from(saved).toArmor(3);
        }
    }

    // --- Bottle Logic ---
    private boolean handleElytraMending() {
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.isOf(Items.ELYTRA) || !chest.isDamaged()) {
            int elytra = findDamagedItem(stack -> stack.isOf(Items.ELYTRA));
            if (elytra != -1) { InvUtils.move().from(elytra).toArmor(2); return true; } 
            else return false;
        }
        throwXpBottles();
        return true;
    }

    private boolean handleToolMending() {
        ItemStack offHand = mc.player.getOffHandStack();
        if (isTool(offHand)) {
            if (offHand.isDamaged()) { throwXpBottles(); return true; } 
            else { int slot = mc.player.getInventory().getEmptySlot(); if (slot != -1) { InvUtils.move().fromOffhand().to(slot); return true; } }
        }
        int damaged = findDamagedItem(this::isTool);
        if (damaged != -1) { InvUtils.move().from(damaged).toOffhand(); return true; }
        return false;
    }

    private boolean handleArmourMending() {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = mc.player.getEquippedStack(slot);
            if (stack.getItem() instanceof ArmorItem && !stack.isOf(Items.ELYTRA) && stack.isDamaged()) { throwXpBottles(); return true; }
        }
        int damaged = findDamagedItem(stack -> stack.getItem() instanceof ArmorItem && !stack.isOf(Items.ELYTRA));
        if (damaged != -1) {
            ItemStack stack = mc.player.getInventory().getStack(damaged);
            var eq = stack.get(DataComponentTypes.EQUIPPABLE);
            if (eq != null) {
                EquipmentSlot s = eq.slot();
                ItemStack eqd = mc.player.getEquippedStack(s);
                if (eqd.isEmpty() || !eqd.isDamaged()) { InvUtils.move().from(damaged).toArmor(armorSlotIndex(s)); return true; }
            }
        }
        return false;
    }

    private boolean isTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item i = stack.getItem();
        return i instanceof net.minecraft.item.PickaxeItem || i instanceof net.minecraft.item.SwordItem || i instanceof net.minecraft.item.AxeItem || i instanceof net.minecraft.item.ShovelItem || i == Items.BOW || i == Items.FLINT_AND_STEEL || i == Items.SHIELD || i == Items.TRIDENT || i == Items.FISHING_ROD;
    }

    private void throwXpBottles() {
        float yaw = mc.player.getYaw() + (float) (Math.random() * 0.2 - 0.1);
        float pitch = 90 + (float) (Math.random() * 0.2 - 0.1);
        Rotations.rotate(yaw, pitch, () -> {
            int xp = findItemSlot(Items.EXPERIENCE_BOTTLE);
            if (xp == -1) return;
            if (isHotbar(xp)) {
                InvUtils.swap(xp, true);
                for (int i = 0; i < packetsPerBurst.get(); i++) mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                InvUtils.swapBack();
            } else {
                int empty = mc.player.getInventory().getEmptySlot();
                if (empty != -1) {
                    InvUtils.move().from(xp).toHotbar(empty);
                    InvUtils.swap(empty, true);
                    for (int i = 0; i < packetsPerBurst.get(); i++) mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    InvUtils.swapBack();
                    InvUtils.move().from(empty).to(xp);
                } else {
                    int prev = mc.player.getInventory().selectedSlot;
                    InvUtils.move().from(xp).toHotbar(prev);
                    for (int i = 0; i < packetsPerBurst.get(); i++) mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    InvUtils.move().from(prev).to(xp);
                }
            }
        });
        mendTimer = burstDelay.get();
    }
}