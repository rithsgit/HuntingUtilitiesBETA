package com.example.addon.modules;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryOps;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.DyeColor;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/** Replenish mode for each item — controls how many to keep in inventory. */
enum ReplenishMode {
    Single, // Keep exactly 1 item
    Fill,   // Fill inventory with as many as possible
    Custom  // Keep a specific number of items
}

public class Inventory101 extends Module {
    private final SettingGroup sgPresets   = settings.createGroup("Presets");
    private final SettingGroup sgRegear    = settings.createGroup("Regear");
    private final SettingGroup sgReplenish = settings.createGroup("Replenish");
    private final SettingGroup sgOrganizer = settings.createGroup("Organizer");
    private final SettingGroup sgCleaner   = settings.createGroup("Cleaner");
    private final SettingGroup sgAutoTool  = settings.createGroup("Auto-Tool");

    // ── Presets ──
    private final Setting<String> preset1Name = sgPresets.add(new StringSetting.Builder()
        .name("preset-1-name").description("Custom name for Preset 1.")
        .defaultValue("1")
        .build()
    );

    private final Setting<String> preset2Name = sgPresets.add(new StringSetting.Builder()
        .name("preset-2-name").description("Custom name for Preset 2.")
        .defaultValue("2")
        .build()
    );

    private final Setting<String> preset1Data = sgPresets.add(new StringSetting.Builder()
        .name("preset-1-data").description("Saved data for inventory preset 1.")
        .defaultValue("").visible(() -> false)
        .build()
    );

    private final Setting<String> preset2Data = sgPresets.add(new StringSetting.Builder()
        .name("preset-2-data").description("Saved data for inventory preset 2.")
        .defaultValue("").visible(() -> false)
        .build()
    );

    // ── Regear ──
    private final Setting<Boolean> showRegearButton = sgRegear.add(new BoolSetting.Builder()
        .name("show-button")
        .description("Shows the G (Gear) button in shulker boxes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> regearDelay = sgRegear.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between armor movement actions.")
        .defaultValue(2).min(1).sliderMax(20)
        .build()
    );

    // ── Replenish ──
    private final Setting<Boolean> showReplenishButton = sgReplenish.add(new BoolSetting.Builder()
        .name("show-button")
        .description("Shows the R (Replenish) button in shulker boxes.")
        .defaultValue(true)
        .build()
    );

    // ── Ender Chest ──
    private final Setting<Boolean> replenishEnderChest = sgReplenish.add(new BoolSetting.Builder()
        .name("ender-chest")
        .description("Replenish Ender Chests from shulker boxes.")
        .defaultValue(true)
        .build()
    );
    private final Setting<ReplenishMode> enderChestMode = sgReplenish.add(new EnumSetting.Builder<ReplenishMode>()
        .name("ender-chest-mode")
        .description("How many Ender Chests to keep in inventory.")
        .defaultValue(ReplenishMode.Fill)
        .visible(replenishEnderChest::get)
        .build()
    );
    private final Setting<Integer> enderChestCount = sgReplenish.add(new IntSetting.Builder()
        .name("ender-chest-count")
        .description("Exact number of Ender Chests to maintain.")
        .defaultValue(1).min(1).sliderMax(512)
        .visible(() -> replenishEnderChest.get() && enderChestMode.get() == ReplenishMode.Custom)
        .build()
    );

    // ── Obsidian ──
    private final Setting<Boolean> replenishObsidian = sgReplenish.add(new BoolSetting.Builder()
        .name("obsidian")
        .description("Replenish Obsidian from shulker boxes.")
        .defaultValue(true)
        .build()
    );
    private final Setting<ReplenishMode> obsidianMode = sgReplenish.add(new EnumSetting.Builder<ReplenishMode>()
        .name("obsidian-mode")
        .description("How many Obsidian to keep in inventory.")
        .defaultValue(ReplenishMode.Fill)
        .visible(replenishObsidian::get)
        .build()
    );
    private final Setting<Integer> obsidianCount = sgReplenish.add(new IntSetting.Builder()
        .name("obsidian-count")
        .description("Exact number of Obsidian to maintain.")
        .defaultValue(64).min(1).sliderMax(512)
        .visible(() -> replenishObsidian.get() && obsidianMode.get() == ReplenishMode.Custom)
        .build()
    );

    // ── Firework Rockets ──
    private final Setting<Boolean> replenishFireworkRocket = sgReplenish.add(new BoolSetting.Builder()
        .name("firework-rocket")
        .description("Replenish Firework Rockets from shulker boxes.")
        .defaultValue(true)
        .build()
    );
    private final Setting<ReplenishMode> fireworkRocketMode = sgReplenish.add(new EnumSetting.Builder<ReplenishMode>()
        .name("firework-rocket-mode")
        .description("How many Firework Rockets to keep in inventory.")
        .defaultValue(ReplenishMode.Fill)
        .visible(replenishFireworkRocket::get)
        .build()
    );
    private final Setting<Integer> fireworkRocketCount = sgReplenish.add(new IntSetting.Builder()
        .name("firework-rocket-count")
        .description("Exact number of Firework Rockets to maintain.")
        .defaultValue(64).min(1).sliderMax(512)
        .visible(() -> replenishFireworkRocket.get() && fireworkRocketMode.get() == ReplenishMode.Custom)
        .build()
    );

    // ── Enchanted Golden Apples ──
    private final Setting<Boolean> replenishEnchantedGoldenApple = sgReplenish.add(new BoolSetting.Builder()
        .name("enchanted-golden-apple")
        .description("Replenish Enchanted Golden Apples from shulker boxes.")
        .defaultValue(true)
        .build()
    );
    private final Setting<ReplenishMode> enchantedGoldenAppleMode = sgReplenish.add(new EnumSetting.Builder<ReplenishMode>()
        .name("enchanted-golden-apple-mode")
        .description("How many Enchanted Golden Apples to keep in inventory.")
        .defaultValue(ReplenishMode.Single)
        .visible(replenishEnchantedGoldenApple::get)
        .build()
    );
    private final Setting<Integer> enchantedGoldenAppleCount = sgReplenish.add(new IntSetting.Builder()
        .name("enchanted-golden-apple-count")
        .description("Exact number of Enchanted Golden Apples to maintain.")
        .defaultValue(1).min(1).sliderMax(512)
        .visible(() -> replenishEnchantedGoldenApple.get() && enchantedGoldenAppleMode.get() == ReplenishMode.Custom)
        .build()
    );

    // ── Totem of Undying ──
    private final Setting<Boolean> replenishTotem = sgReplenish.add(new BoolSetting.Builder()
        .name("totem-of-undying")
        .description("Replenish Totems of Undying from shulker boxes.")
        .defaultValue(true)
        .build()
    );
    private final Setting<ReplenishMode> totemMode = sgReplenish.add(new EnumSetting.Builder<ReplenishMode>()
        .name("totem-mode")
        .description("How many Totems of Undying to keep in inventory.")
        .defaultValue(ReplenishMode.Single)
        .visible(replenishTotem::get)
        .build()
    );
    private final Setting<Integer> totemCount = sgReplenish.add(new IntSetting.Builder()
        .name("totem-count")
        .description("Exact number of Totems to maintain.")
        .defaultValue(1).min(1).sliderMax(36)
        .visible(() -> replenishTotem.get() && totemMode.get() == ReplenishMode.Custom)
        .build()
    );

    // ── Elytra ──
    private final Setting<Boolean> replenishElytra = sgReplenish.add(new BoolSetting.Builder()
        .name("elytra")
        .description("Replenish Elytras from shulker boxes (durability-aware swapping).")
        .defaultValue(true)
        .build()
    );
    private final Setting<ReplenishMode> elytraMode = sgReplenish.add(new EnumSetting.Builder<ReplenishMode>()
        .name("elytra-mode")
        .description("How many Elytras to keep in inventory (not counting armor slot).")
        .defaultValue(ReplenishMode.Single)
        .visible(replenishElytra::get)
        .build()
    );
    private final Setting<Integer> elytraCount = sgReplenish.add(new IntSetting.Builder()
        .name("elytra-count")
        .description("Exact number of Elytras to maintain (inventory only, not armor slot).")
        .defaultValue(1).min(1).sliderMax(36)
        .visible(() -> replenishElytra.get() && elytraMode.get() == ReplenishMode.Custom)
        .build()
    );
    private final Setting<Integer> elytraThreshold = sgReplenish.add(new IntSetting.Builder()
        .name("elytra-threshold")
        .description("Durability threshold to consider an elytra as needing replacement.")
        .defaultValue(15).min(1).sliderMax(100)
        .visible(replenishElytra::get)
        .build()
    );

    // ── End Crystal ──
    private final Setting<Boolean> replenishEndCrystal = sgReplenish.add(new BoolSetting.Builder()
        .name("end-crystal")
        .description("Replenish End Crystals from shulker boxes.")
        .defaultValue(true)
        .build()
    );
    private final Setting<ReplenishMode> endCrystalMode = sgReplenish.add(new EnumSetting.Builder<ReplenishMode>()
        .name("end-crystal-mode")
        .description("How many End Crystals to keep in inventory.")
        .defaultValue(ReplenishMode.Fill)
        .visible(replenishEndCrystal::get)
        .build()
    );
    private final Setting<Integer> endCrystalCount = sgReplenish.add(new IntSetting.Builder()
        .name("end-crystal-count")
        .description("Exact number of End Crystals to maintain.")
        .defaultValue(16).min(1).sliderMax(512)
        .visible(() -> replenishEndCrystal.get() && endCrystalMode.get() == ReplenishMode.Custom)
        .build()
    );

    // ── Custom Items ──
    private final Setting<Boolean> replenishCustom = sgReplenish.add(new BoolSetting.Builder()
        .name("custom-items-enabled")
        .description("Enable replenishing custom items from shulker boxes.")
        .defaultValue(false)
        .build()
    );
    private final Setting<List<Item>> customReplenishItems = sgReplenish.add(new ItemListSetting.Builder()
        .name("custom-items")
        .description("Additional items to replenish. Order determines priority — items at the top are restocked first.")
        .defaultValue(new ArrayList<>())
        .visible(replenishCustom::get)
        .build()
    );
    private final Setting<ReplenishMode> customMode = sgReplenish.add(new EnumSetting.Builder<ReplenishMode>()
        .name("custom-mode")
        .description("Replenish mode for all custom items.")
        .defaultValue(ReplenishMode.Fill)
        .visible(() -> replenishCustom.get() && !customReplenishItems.get().isEmpty())
        .build()
    );
    private final Setting<Integer> customCount = sgReplenish.add(new IntSetting.Builder()
        .name("custom-count")
        .description("Exact number to maintain for each custom item.")
        .defaultValue(64).min(1).sliderMax(512)
        .visible(() -> replenishCustom.get() && !customReplenishItems.get().isEmpty() && customMode.get() == ReplenishMode.Custom)
        .build()
    );

    private final Setting<Integer> replenishDelay = sgReplenish.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between movement actions.")
        .defaultValue(2).min(1).sliderMax(20)
        .build()
    );

    /**
     * Builds the combined replenish whitelist from presets + custom items.
     * Items appear in priority order — presets first (top-to-bottom), then custom
     * items (in list order). Only items whose toggle is enabled are included.
     */
    private List<Item> getReplenishWhitelist() {
        List<Item> whitelist = new ArrayList<>();
        if (replenishEnderChest.get())                whitelist.add(Items.ENDER_CHEST);
        if (replenishObsidian.get())                  whitelist.add(Items.OBSIDIAN);
        if (replenishFireworkRocket.get())            whitelist.add(Items.FIREWORK_ROCKET);
        if (replenishEnchantedGoldenApple.get())      whitelist.add(Items.ENCHANTED_GOLDEN_APPLE);
        if (replenishTotem.get())                     whitelist.add(Items.TOTEM_OF_UNDYING);
        if (replenishElytra.get())                    whitelist.add(Items.ELYTRA);
        if (replenishEndCrystal.get())                whitelist.add(Items.END_CRYSTAL);
        if (replenishCustom.get()) {
            for (Item item : customReplenishItems.get()) {
                if (!whitelist.contains(item)) whitelist.add(item);
            }
        }
        return whitelist;
    }

    /**
     * Returns how many items to pull from shulker in one replenish session:
     *   Single → item.getMaxCount() (1 stack — 1 totem, 1 elytra, or 64 obsidian)
     *   Fill   → Integer.MAX_VALUE (fill inventory, keep pulling until full or shulker empty)
     *   Custom → user-specified count (pull exactly N items)
     * Only called for items whose toggle is enabled.
     */
    private int getPullLimit(Item item) {
        if (item == Items.ENDER_CHEST) {
            return switch (enderChestMode.get()) {
                case Single -> item.getMaxCount();
                case Fill -> Integer.MAX_VALUE;
                case Custom -> enderChestCount.get();
            };
        }
        if (item == Items.OBSIDIAN) {
            return switch (obsidianMode.get()) {
                case Single -> item.getMaxCount();
                case Fill -> Integer.MAX_VALUE;
                case Custom -> obsidianCount.get();
            };
        }
        if (item == Items.FIREWORK_ROCKET) {
            return switch (fireworkRocketMode.get()) {
                case Single -> item.getMaxCount();
                case Fill -> Integer.MAX_VALUE;
                case Custom -> fireworkRocketCount.get();
            };
        }
        if (item == Items.ENCHANTED_GOLDEN_APPLE) {
            return switch (enchantedGoldenAppleMode.get()) {
                case Single -> item.getMaxCount();
                case Fill -> Integer.MAX_VALUE;
                case Custom -> enchantedGoldenAppleCount.get();
            };
        }
        if (item == Items.TOTEM_OF_UNDYING) {
            return switch (totemMode.get()) {
                case Single -> item.getMaxCount();
                case Fill -> Integer.MAX_VALUE;
                case Custom -> totemCount.get();
            };
        }
        if (item == Items.ELYTRA) {
            return switch (elytraMode.get()) {
                case Single -> item.getMaxCount();
                case Fill -> Integer.MAX_VALUE;
                case Custom -> elytraCount.get();
            };
        }
        if (item == Items.END_CRYSTAL) {
            return switch (endCrystalMode.get()) {
                case Single -> item.getMaxCount();
                case Fill -> Integer.MAX_VALUE;
                case Custom -> endCrystalCount.get();
            };
        }
        // Custom items — use shared mode/count
        return switch (customMode.get()) {
            case Single -> item.getMaxCount();
            case Fill -> Integer.MAX_VALUE;
            case Custom -> customCount.get();
        };
    }

    // ── Organizer ──
    private final Setting<Boolean> showSortButton = sgOrganizer.add(new BoolSetting.Builder()
        .name("show-sort-button").description("Show a sort button in chests.")
        .defaultValue(true).build()
    );

    private final Setting<Integer> sortDelay = sgOrganizer.add(new IntSetting.Builder()
        .name("sort-delay").description("Delay in ticks between sort actions.")
        .defaultValue(2).min(1).visible(showSortButton::get)
        .build()
    );

    private final Setting<Boolean> shiftClickAll = sgOrganizer.add(new BoolSetting.Builder()
        .name("shift-click-all")
        .description("When shift-clicking an item, moves all items of the same type from that inventory.")
        .defaultValue(true)
        .build()
    );

    // ── Cleaner ──
    private final Setting<Boolean> autoTrash = sgCleaner.add(new BoolSetting.Builder()
        .name("auto-trash")
        .description("Automatically discards whitelisted items (drops in world, throws in containers).")
        .defaultValue(false).build()
    );

    private final Setting<List<Item>> trashItems = sgCleaner.add(new ItemListSetting.Builder()
        .name("trash-items").description("Items to automatically discard.")
        .defaultValue(new ArrayList<>()).visible(autoTrash::get)
        .build()
    );

    private final Setting<Integer> trashDelay = sgCleaner.add(new IntSetting.Builder()
        .name("trash-delay").description("Delay in ticks between discard actions.")
        .defaultValue(2).min(1).visible(autoTrash::get)
        .build()
    );

    // ── Auto Tool ──
    private final Setting<Boolean> autoTool = sgAutoTool.add(new BoolSetting.Builder()
        .name("auto-tool")
        .description("Automatically swaps to the best tool when breaking blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> silentAutoTool = sgAutoTool.add(new BoolSetting.Builder()
        .name("silent-swap")
        .description("Swaps to the tool silently.")
        .defaultValue(true)
        .visible(autoTool::get)
        .build()
    );

    // ── State ──
    private boolean saveMode      = false;
    private boolean isRegearing    = false;
    private int     regearTimer    = 0;
    private int     regearPresetIndex = 0;
    private boolean isReplenishing = false;
    private int     replenishTimer = 0;
    private boolean replenishedForCurrentScreen = false;
    private Map<Item, Integer> pulledThisSession = new LinkedHashMap<>();
    private boolean isSorting    = false;
    private int     sortTimer    = 0;
    private boolean isInvSorting  = false;
    private int     invSortTimer  = 0;
    private int     invSortPreset = 0;
    private boolean isTrashing    = false;
    private int     trashTimer    = 0;
    private boolean trashedForCurrentScreen = false;

    // Interaction state
    private boolean wasClicking   = false;
    private double  lastMouseX    = -1;
    private double  lastMouseY    = -1;
    private final Set<Integer> processedInDrag = new HashSet<>();

    // Auto-tool state
    private boolean moveAllActionTaken  = false;
    private boolean wasBreaking         = false;
    private int     prevSlotAutoTool    = -1;

    public Inventory101() {
        super(Tim.CATEGORY, "inventory-101", "Manages inventory layouts with shulker boxes.");
    }

    @Override
    public void onDeactivate() {
        isRegearing    = false;
        regearTimer    = 0;
        regearPresetIndex = 0;
        isReplenishing = false;
        replenishTimer = 0;
        isSorting    = false;
        isInvSorting = false;
        isTrashing   = false;
        wasClicking  = false;
        lastMouseX   = -1;
        lastMouseY   = -1;
        saveMode     = false;
        processedInDrag.clear();
        moveAllActionTaken   = false;
        wasBreaking          = false;
        prevSlotAutoTool     = -1;
        trashTimer           = 0;
        trashedForCurrentScreen = false;
        replenishedForCurrentScreen = false;
        pulledThisSession.clear();
    }

    // ─────────────────────── Lifecycle Helpers ───────────────────────

    public String getPresetName(int index) {
        return (index == 1) ? preset1Name.get() : preset2Name.get();
    }

    // ─────────────────────── Public API for HandledScreenMixin ───────────────────────

    public boolean isRegearButtonEnabled() {
        return showRegearButton.get();
    }

    public void startRegearing() {
        if (isBusy()) return;
        isRegearing = true;
        regearTimer = 0;
        regearPresetIndex = 0;
        info("Regearing Essentials...");
    }

    public void toggleSaveMode() {
        if (isBusy()) return;
        saveMode = !saveMode;
        info(saveMode ? "§eSelect a preset slot (1 or 2) to SAVE current layout." : "§7Save mode §ccancelled§7.");
    }

    public boolean isSaveMode() { return saveMode; }

    public boolean isPresetEmpty(int index) {
        String data = (index == 1) ? preset1Data.get() : preset2Data.get();
        return data == null || data.isEmpty();
    }

    public void handlePreset(int index) {
        if (isBusy() && !saveMode) return;
        if (saveMode) {
            saveInventory(index);
            saveMode = false;
            info("Inventory layout saved to Preset §a" + getPresetName(index) + "§7.");
        } else {
            isRegearing = true;
            regearTimer = 0;
            regearPresetIndex = index;
            info("Loading Preset §6" + getPresetName(index) + "§7...");
        }
    }

    public void clearPresets() {
        preset1Data.set("");
        preset2Data.set("");
        saveMode = false;
        info("Presets cleared.");
    }

    public void startInvSort(int presetIndex) {
        if (isBusy()) return;
        if (isPresetEmpty(presetIndex)) {
            warning("Preset " + getPresetName(presetIndex) + " is empty.");
            return;
        }
        invSortPreset = presetIndex;
        isInvSorting  = true;
        invSortTimer  = 0;
        info("Sorting inventory to Preset §6" + getPresetName(presetIndex) + "§7...");
    }

    public boolean isSortButtonEnabled() { return showSortButton.get(); }

    public void startSorting() {
        if (isBusy()) return;
        isSorting = true;
        sortTimer = 0;
    }

    public boolean isReplenishButtonEnabled() {
        if (getReplenishWhitelist().isEmpty()) return false;
        return showReplenishButton.get();
    }

    public void startReplenishing() {
        if (isBusy()) return;
        if (getReplenishWhitelist().isEmpty()) {
            warning("No items to replenish (whitelist is empty).");
            return;
        }
        pulledThisSession.clear();
        isReplenishing = true;
        replenishTimer = 0;
        info("Restocking whitelisted items...");
    }

    // ─────────────────────── Tick Handler ───────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        tickAutoTool();

        if (isRegearing) {
            if (!(mc.currentScreen instanceof ShulkerBoxScreen)) { isRegearing = false; return; }
            if (regearTimer > 0) { regearTimer--; return; }
            if (performRegearStep()) {
                regearTimer = regearDelay.get();
            } else {
                boolean wasPresetRegear = regearPresetIndex != 0;
                isRegearing = false;
                info("Regear §acomplete§7.");
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                if (!wasPresetRegear) startReplenishing();
            }
            return;
        }

        if (isReplenishing) {
            if (!(mc.currentScreen instanceof ShulkerBoxScreen)) { isReplenishing = false; return; }
            if (replenishTimer > 0) { replenishTimer--; return; }
            if (performReplenishStep()) {
                replenishTimer = replenishDelay.get();
            } else {
                info("Restock §acomplete§7.");
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                isReplenishing = false;
            }
            return;
        }

        if (isInvSorting) {
            if (!(mc.currentScreen instanceof InventoryScreen)) { isInvSorting = false; return; }
            if (invSortTimer > 0) { invSortTimer--; return; }
            if (performInvSortStep()) {
                invSortTimer = sortDelay.get();
            } else {
                isInvSorting = false;
                info(getPresetName(invSortPreset) + " sort §acomplete§7.");
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
            return;
        }

        if (isSorting) {
            if (!(mc.currentScreen instanceof GenericContainerScreen)) { isSorting = false; return; }
            if (sortTimer > 0) { sortTimer--; return; }
            if (performSortStep()) {
                sortTimer = sortDelay.get();
            } else {
                isSorting = false;
                info("Sorting §acomplete§7.");
            }
            return;
        }

        tickMouseInteractions();
        tickAutoTrash();
        tickAutoDrop();
    }

    // ─────────────────────── Auto Tool ───────────────────────

    private void tickAutoTool() {
        if (!autoTool.get()) return;
        if (mc.interactionManager.isBreakingBlock()) {
            HitResult hit = mc.crosshairTarget;
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                BlockState state = mc.world.getBlockState(bhr.getBlockPos());
                if (!state.isAir()) {
                    int bestSlot = findBestTool(state);
                    if (bestSlot != -1 && bestSlot != mc.player.getInventory().selectedSlot) {
                        if (!wasBreaking) {
                            prevSlotAutoTool = mc.player.getInventory().selectedSlot;
                            wasBreaking = true;
                        }
                        InvUtils.swap(bestSlot, silentAutoTool.get());
                    }
                }
            }
        } else if (wasBreaking) {
            if (silentAutoTool.get() && prevSlotAutoTool != -1) {
                InvUtils.swap(prevSlotAutoTool, true);
            }
            wasBreaking = false;
            prevSlotAutoTool = -1;
        }
    }

    // ─────────────────────── Mouse Interactions ───────────────────────

    private void tickMouseInteractions() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            if (wasClicking) {
                processedInDrag.clear();
                lastMouseX = -1;
                moveAllActionTaken = false;
            }
            wasClicking = false;
            return;
        }

        boolean isClicking = Input.isButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean isShift    = Input.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT)
                          || Input.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT);

        if (isClicking && isShift) {
            if (!wasClicking) {
                if (shiftClickAll.get()) {
                    Slot focused = getFocusedSlot(screen);
                    if (focused != null && focused.hasStack()) {
                        moveAllActionTaken = true;
                        Item targetItem = focused.getStack().getItem();
                        boolean clickedInPlayerInventory = focused.inventory == mc.player.getInventory();
                        for (Slot slot : screen.getScreenHandler().slots) {
                            boolean slotInPlayerInventory = slot.inventory == mc.player.getInventory();
                            if (slot.hasStack() && slot.getStack().getItem() == targetItem) {
                                if (clickedInPlayerInventory == slotInPlayerInventory) {
                                    mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                                }
                            }
                        }
                    }
                }
                if (!moveAllActionTaken) {
                    processedInDrag.clear();
                    lastMouseX = mc.mouse.getX();
                    lastMouseY = mc.mouse.getY();
                    Slot focused = getFocusedSlot(screen);
                    if (focused != null && focused.hasStack() && !processedInDrag.contains(focused.id)) {
                        mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, focused.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                        processedInDrag.add(focused.id);
                    }
                }
            } else if (!moveAllActionTaken) {
                double mouseX = mc.mouse.getX();
                double mouseY = mc.mouse.getY();
                
                if (lastMouseX != -1) {
                    double deltaX = mouseX - lastMouseX;
                    double deltaY = mouseY - lastMouseY;
                    double dist   = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                    
                    // Only interpolate if moving roughly horizontally (staying in the same row).
                    // This catches fast horizontal drags without clipping into rows above/below.
                    if (dist > 1 && Math.abs(deltaY) < 14) {
                        int steps = (int) Math.ceil(dist / 2.0);
                        for (int i = 0; i <= steps; i++) {
                            double currentX = lastMouseX + (deltaX * i / steps);
                            double currentY = lastMouseY + (deltaY * i / steps);
                            Slot slot = getSlotAt(screen, currentX, currentY);
                            if (slot != null && slot.hasStack() && !processedInDrag.contains(slot.id)) {
                                mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                                processedInDrag.add(slot.id);
                            }
                        }
                    }
                }
                
                // Always ensure the currently focused slot is processed.
                // This catches fast vertical/diagonal movements safely without missing the target.
                Slot focused = getFocusedSlot(screen);
                if (focused != null && focused.hasStack() && !processedInDrag.contains(focused.id)) {
                    mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, focused.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                    processedInDrag.add(focused.id);
                }
                
                lastMouseX = mouseX;
                lastMouseY = mouseY;
            }
            wasClicking = true;
        } else {
            if (wasClicking) {
                processedInDrag.clear();
                lastMouseX = -1;
                moveAllActionTaken = false;
            }
            wasClicking = false;
        }
    }

    // ─────────────────────── Regear Logic ───────────────────────

    private boolean performRegearStep() {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)) return false;
        if (regearPresetIndex == 0) return performGenericRegearStep(handler);

        List<ItemStack> preset = getPreset(regearPresetIndex);
        int[] slotOrder = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            36,
            37, 38, 39, 40
        };

        for (int presetSlot : slotOrder) {
            ItemStack desired = preset.get(presetSlot);
            if (desired.isEmpty()) continue;
            if (isPresetSlotMatch(presetSlot, desired)) continue;

            if (presetSlot >= 36) {
                for (int j = 0; j < 27; j++) {
                    ItemStack shulkerStack = handler.getSlot(j).getStack();
                    if (shulkerStack.isEmpty() || !isSameItemType(shulkerStack, desired)) continue;
                    if (shulkerStack.isOf(Items.ELYTRA) && isLowDurabilityElytra(shulkerStack)) continue;

                    if (presetSlot == 36) {
                        // Offhand slot is not in ShulkerBoxScreenHandler's slot list,
                        // so InvUtils.move().toOffhand() silently fails. Use quickMove
                        // (shift-click) instead — the server auto-places totems in offhand.
                        quickMove(j);
                    } else {
                        int armorIndex = switch (presetSlot) {
                            case 37 -> 0; case 38 -> 1; case 39 -> 2; case 40 -> 3;
                            default -> -1;
                        };
                        if (armorIndex == -1) continue;
                        // Armor slots are not in ShulkerBoxScreenHandler's slot list,
                        // so InvUtils.move().toArmor() silently fails. Use quickMove
                        // (shift-click) instead — the server auto-equips armor items.
                        quickMove(j);
                    }
                    return true;
                }
            } else {
                int targetSlotId = mapInventoryToSlotId(presetSlot);
                if (targetSlotId == -1) continue;

                for (int i = 27; i < 63; i++) {
                    if (i == targetSlotId) continue;
                    ItemStack invStack = handler.getSlot(i).getStack();
                    if (invStack.isEmpty() || !isSameItemType(invStack, desired)) continue;

                    int sourceInvIndex = screenHandlerSlotToInvIndex(i);
                    if (sourceInvIndex >= 0 && sourceInvIndex < 36) {
                        ItemStack sourceDesired = preset.get(sourceInvIndex);
                        if (!sourceDesired.isEmpty() && isSameItemType(invStack, sourceDesired)) continue;
                    }
                    smartMove(i, targetSlotId);
                    return true;
                }

                for (int j = 0; j < 27; j++) {
                    ItemStack shulkerStack = handler.getSlot(j).getStack();
                    if (shulkerStack.isEmpty() || !isSameItemType(shulkerStack, desired)) continue;
                    if (shulkerStack.isOf(Items.ELYTRA) && isLowDurabilityElytra(shulkerStack)) continue;
                    smartMove(j, targetSlotId);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPresetSlotMatch(int presetSlot, ItemStack desired) {
        ItemStack current;
        if (presetSlot < 36) current = mc.player.getInventory().getStack(presetSlot);
        else if (presetSlot == 36) current = mc.player.getOffHandStack();
        else {
            EquipmentSlot eqSlot = presetIndexToArmorSlot(presetSlot);
            if (eqSlot == null) return false;
            current = mc.player.getEquippedStack(eqSlot);
        }
        if (!isSameItemType(current, desired)) return false;
        if (current.isOf(Items.ELYTRA) && isLowDurabilityElytra(current)) return false;
        return true;
    }

    private int screenHandlerSlotToInvIndex(int slotId) {
        if (slotId >= 27 && slotId <= 53) return (slotId - 27) + 9;
        if (slotId >= 54 && slotId <= 62) return slotId - 54;
        return -1;
    }

    private boolean performGenericRegearStep(ShulkerBoxScreenHandler handler) {
        EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : armorSlots) {
            ItemStack current = mc.player.getEquippedStack(slot);
            boolean needsEquip = current.isEmpty();
            if (slot == EquipmentSlot.CHEST && !current.isEmpty() && current.isOf(Items.ELYTRA) && isLowDurabilityElytra(current)) {
                needsEquip = true;
            }
            if (needsEquip) {
                int armorIndex = switch (slot) {
                    case FEET -> 0; case LEGS -> 1; case CHEST -> 2; case HEAD -> 3;
                    default -> -1;
                };
                if (armorIndex == -1) continue;
                for (int j = 0; j < 27; j++) {
                    ItemStack shulkerStack = handler.getSlot(j).getStack();
                    if (shulkerStack.isEmpty()) continue;
                    var equippable = shulkerStack.get(DataComponentTypes.EQUIPPABLE);
                    if (equippable != null && equippable.slot() == slot) {
                        // Armor slots are not in ShulkerBoxScreenHandler's slot list,
                        // so InvUtils.move().toArmor() silently fails. Use quickMove
                        // (shift-click) instead — the server auto-equips armor items.
                        quickMove(j);
                        return true;
                    }
                    if (slot == EquipmentSlot.CHEST && shulkerStack.isOf(Items.ELYTRA) && !isLowDurabilityElytra(shulkerStack)) {
                        quickMove(j);
                        return true;
                    }
                }
            }
        }

        ItemStack offhand = mc.player.getOffHandStack();
        if (!offhand.isOf(Items.TOTEM_OF_UNDYING)) {
            for (int j = 0; j < 27; j++) {
                if (handler.getSlot(j).getStack().isOf(Items.TOTEM_OF_UNDYING)) {
                    // Offhand slot is not in ShulkerBoxScreenHandler's slot list,
                    // so InvUtils.move().toOffhand() silently fails. Use quickMove
                    // (shift-click) instead — the server auto-places totems in offhand.
                    quickMove(j);
                    return true;
                }
            }
        }
        return false;
    }

    private EquipmentSlot presetIndexToArmorSlot(int i) {
        return switch (i) {
            case 37 -> EquipmentSlot.FEET;
            case 38 -> EquipmentSlot.LEGS;
            case 39 -> EquipmentSlot.CHEST;
            case 40 -> EquipmentSlot.HEAD;
            default -> null;
        };
    }

    // ─────────────────────── Replenish (Restock) Logic ───────────────────────

    /** Snapshot of an item's state in the player's inventory. */
    private static class InvItemState {
        int totalCount = 0;         // total item count across all inventory stacks
        int partialSlotId = -1;     // handler slot ID of first partial stack, or -1
        int partialCount = 0;       // item count in the partial stack
        int maxCount = 64;          // max stack size for this item
        int badElytraSlotId = -1;   // handler slot ID of first bad elytra, or -1
    }

    /**
     * Performs one step of the restock process.
     *
     * Flow:
     *   1. Scan the shulker box — identify which whitelisted items are available and where
     *   2. Scan the player's inventory — determine current stack counts, partials, and bad elytras
     *   3. Execute the highest-priority move:
     *      a. Elytra durability swaps (replace damaged elytra with good ones from shulker)
     *      b. Top up partial stacks (fill incomplete inventory stacks from shulker)
     *      c. Pull new stacks (bring items up to the target stack count)
     *
     * Returns true if an action was taken (caller should wait for the delay),
     * false if restock is complete.
     */
    private boolean performReplenishStep() {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)) return false;

        List<Item> whitelist = getReplenishWhitelist();
        if (whitelist.isEmpty()) return false;

        // ── 1. Scan shulker: find which whitelisted items are available and where ──
        Map<Item, List<Integer>> shulkerSlots = new LinkedHashMap<>();
        for (int j = 0; j < 27; j++) {
            ItemStack stack = handler.getSlot(j).getStack();
            if (stack.isEmpty()) continue;
            if (whitelist.contains(stack.getItem())) {
                shulkerSlots.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(j);
            }
        }

        // ── 2. Scan inventory: count full stacks, locate partials, and find bad elytras ──
        Map<Item, InvItemState> invState = new LinkedHashMap<>();
        boolean hasEmptyInvSlot = false;
        for (int i = 27; i < 63; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                hasEmptyInvSlot = true;
                continue;
            }
            Item item = stack.getItem();
            InvItemState state = invState.computeIfAbsent(item, k -> new InvItemState());
            state.maxCount = stack.getMaxCount();

            if (isBadElytra(stack)) {
                // Bad elytras are NOT counted — they need replacing
                state.badElytraSlotId = i;
                continue;
            }

            state.totalCount += stack.getCount();
            if (stack.getCount() < stack.getMaxCount() && state.partialSlotId == -1) {
                state.partialSlotId = i;
                state.partialCount = stack.getCount();
            }
        }

        // ── 3a. Elytra handling (durability swaps + pulling to meet target) ──
        if (whitelist.contains(Items.ELYTRA)) {
            if (handleElytraSwaps(handler, shulkerSlots.getOrDefault(Items.ELYTRA, List.of()))) return true;
        }

        // ── 3b. Top up partial stacks from shulker ──
        //    If the shulker stack fits entirely into the remaining space, use quickMove (1 click).
        //    Otherwise use smartMove to split: pick up from shulker, merge onto partial,
        //    put remainder back. This always works even with a full inventory.
        //    Respects the per-item pull limit for this session.
        for (Item item : whitelist) {
            if (item == Items.ELYTRA) continue; // elytra is max stack 1, no top-up needed
            List<Integer> slots = shulkerSlots.get(item);
            if (slots == null) continue;
            InvItemState state = invState.get(item);
            if (state == null || state.partialSlotId == -1) continue;

            // Check pull limit for this session
            int pullLimit = getPullLimit(item);
            int alreadyPulled = pulledThisSession.getOrDefault(item, 0);
            if (alreadyPulled >= pullLimit) continue;

            int space = state.maxCount - state.partialCount;
            if (space <= 0) continue;

            for (int shulkerSlot : slots) {
                ItemStack shulkerStack = handler.getSlot(shulkerSlot).getStack();
                if (shulkerStack.isEmpty() || shulkerStack.getItem() != item) continue;

                int itemsMoved = Math.min(shulkerStack.getCount(), space);
                if (shulkerStack.getCount() <= space) {
                    quickMove(shulkerSlot);
                } else {
                    smartMove(shulkerSlot, state.partialSlotId);
                }
                pulledThisSession.merge(item, itemsMoved, Integer::sum);
                return true;
            }
        }

        // ── 3c. Pull new stacks from shulker ──
        //    Pulls items until the per-session pull limit is reached:
        //      Single → 1 stack (1 totem, 64 obsidian, etc.)
        //      Fill   → fill inventory (no limit)
        //      Custom → N items
        //    Elytra is skipped here — all elytra logic is in handleElytraSwaps (step 3a).
        for (Item item : whitelist) {
            if (item == Items.ELYTRA) continue;
            List<Integer> slots = shulkerSlots.get(item);
            if (slots == null) continue;

            // Check pull limit for this session
            int pullLimit = getPullLimit(item);
            int alreadyPulled = pulledThisSession.getOrDefault(item, 0);
            if (alreadyPulled >= pullLimit) continue;

            boolean hasRoom = hasEmptyInvSlot
                || (invState.get(item) != null && invState.get(item).partialSlotId != -1 && invState.get(item).partialCount < invState.get(item).maxCount);
            if (!hasRoom) continue;

            for (int shulkerSlot : slots) {
                ItemStack shulkerStack = handler.getSlot(shulkerSlot).getStack();
                if (shulkerStack.isEmpty() || shulkerStack.getItem() != item) continue;

                int itemsMoved = shulkerStack.getCount();
                quickMove(shulkerSlot);
                pulledThisSession.merge(item, itemsMoved, Integer::sum);
                return true;
            }
        }

        return false;
    }

    /**
     * Handles ALL elytra replenish logic:
     *   1. Swap inventory elytras for better ones from shulker (worst goes back to shulker)
     *   2. Pull elytras from shulker to meet the target-stacks count
     *   3. Deposit low-durability inventory elytras into empty shulker slots (cleanup)
     *
     * IMPORTANT: This method completely IGNORES the armor slot. The equipped elytra
     * is counted toward the target but is NEVER moved, swapped, or touched. The
     * reason is that quickMove (shift-click) on an elytra auto-equips it instead
     * of placing it in the inventory, and shift-clicking an elytra from inventory
     * auto-equips it instead of moving it to the shulker. This causes elytras to
     * bounce between the armor slot and inventory/shulker, blocking all replenish.
     *
     * Only smartMove is used for elytras — it explicitly targets a specific slot,
     * bypassing the auto-equip behavior entirely.
     *
     * Returns true if an action was taken.
     */
    private boolean handleElytraSwaps(ShulkerBoxScreenHandler handler, List<Integer> elytraSlots) {
        // ── Find the best (highest remaining durability) elytra in the shulker ──
        int bestShulkerSlot = -1;
        int bestShulkerDurability = -1;
        for (int slot : elytraSlots) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isOf(Items.ELYTRA)) continue;
            int remaining = stack.getMaxDamage() - stack.getDamage();
            if (remaining > bestShulkerDurability) {
                bestShulkerDurability = remaining;
                bestShulkerSlot = slot;
            }
        }

        // ── 1. Swap inventory elytras for better ones from shulker ──
        //    Find the worst elytra in the player's inventory (NOT armor slot).
        //    If the shulker's best is better, smartMove-swap it. The 3-click
        //    swap naturally sends the worse elytra back to the shulker slot.
        //    The swap counts as 1 pull toward the session limit — it extracts
        //    a good elytra from the shulker even though a bad one goes back.
        //    This prevents step 2 from pulling a SECOND elytra on the next
        //    tick (which would give the player double the intended amount).
        //    smartMove is used instead of quickMove because quickMove on an
        //    elytra auto-equips it to the armor slot instead of placing it in
        //    the inventory.
        if (bestShulkerSlot != -1) {
            int worstInvSlot = -1;
            int worstInvDurability = Integer.MAX_VALUE;
            for (int i = 27; i < 63; i++) {
                ItemStack invStack = handler.getSlot(i).getStack();
                if (!invStack.isOf(Items.ELYTRA)) continue;
                int remaining = invStack.getMaxDamage() - invStack.getDamage();
                if (remaining < worstInvDurability) {
                    worstInvDurability = remaining;
                    worstInvSlot = i;
                }
            }
            if (worstInvSlot != -1 && bestShulkerDurability > worstInvDurability) {
                smartMove(bestShulkerSlot, worstInvSlot);
                pulledThisSession.merge(Items.ELYTRA, 1, Integer::sum);
                return true;
            }
        }

        // ── 2. Pull elytras up to session pull limit ──
        //    Mode caps: Single = 1 elytra, Fill = fill inventory, Custom = N elytras.
        //    Tracks how many have been pulled this replenish session.
        //    Pull from shulker into an empty inventory slot using smartMove
        //    (not quickMove, which would auto-equip).
        //    If no empty inventory slot, we can't pull — quickMove would
        //    trigger auto-equip and bounce the elytra around.
        int elytraPullLimit = getPullLimit(Items.ELYTRA);
        int elytrasPulled = pulledThisSession.getOrDefault(Items.ELYTRA, 0);
        if (elytrasPulled < elytraPullLimit) {
            // Prefer a good (above threshold) elytra from shulker
            int goodSlot = -1;
            for (int slot : elytraSlots) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (stack.isOf(Items.ELYTRA) && !isLowDurabilityElytra(stack)) {
                    goodSlot = slot;
                    break;
                }
            }

            // Check if player has any elytras at all (for fallback)
            boolean hasAnyElytra = false;
            if (mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) hasAnyElytra = true;
            for (int i = 27; i < 63; i++) {
                if (handler.getSlot(i).getStack().isOf(Items.ELYTRA)) { hasAnyElytra = true; break; }
            }

            // If no good elytra available but player has NONE at all, pull the best
            // bad one — a damaged elytra is still better than no elytra.
            int pullSlot = goodSlot != -1 ? goodSlot : (!hasAnyElytra ? bestShulkerSlot : -1);

            if (pullSlot != -1) {
                for (int i = 27; i < 63; i++) {
                    if (handler.getSlot(i).getStack().isEmpty()) {
                        smartMove(pullSlot, i);
                        pulledThisSession.merge(Items.ELYTRA, 1, Integer::sum);
                        return true;
                    }
                }
            }
        }

        // ── 3. Deposit low-durability inventory elytras into empty shulker slots ──
        //    After all swaps and pulls are done, clean up by moving any remaining
        //    bad elytras from inventory into empty shulker slots. Use smartMove
        //    (not quickMove) because quickMove on an elytra from inventory
        //    auto-equips it to the armor slot instead of moving to the shulker.
        for (int i = 27; i < 63; i++) {
            ItemStack invStack = handler.getSlot(i).getStack();
            if (!invStack.isOf(Items.ELYTRA) || !isLowDurabilityElytra(invStack)) continue;
            for (int j = 0; j < 27; j++) {
                if (handler.getSlot(j).getStack().isEmpty()) {
                    smartMove(i, j);
                    return true;
                }
            }
            break; // Shulker full — can't deposit more
        }

        return false;
    }

    /** Whether this stack is a low-durability elytra (should be replaced, not counted). */
    private boolean isBadElytra(ItemStack stack) {
        return stack.isOf(Items.ELYTRA) && isLowDurabilityElytra(stack);
    }

    // ─────────────────────── Inv Sort ───────────────────────

    private boolean performInvSortStep() {
        List<ItemStack> preset = getPreset(invSortPreset);
        boolean[] satisfied = new boolean[36];
        boolean[] inventoryClaimed = new boolean[36];

        for (int i = 0; i < 36; i++) {
            ItemStack desired = preset.get(i);
            if (desired.isEmpty()) {
                satisfied[i] = mc.player.getInventory().getStack(i).isEmpty();
                continue;
            }
            if (!inventoryClaimed[i] && isSameItemType(mc.player.getInventory().getStack(i), desired)) {
                satisfied[i] = true;
                inventoryClaimed[i] = true;
            } else {
                for (int j = 0; j < 36; j++) {
                    if (!inventoryClaimed[j] && isSameItemType(mc.player.getInventory().getStack(j), desired)) {
                        inventoryClaimed[j] = true;
                        break;
                    }
                }
            }
        }

        for (int i = 0; i < 36; i++) {
            if (satisfied[i]) continue;
            ItemStack desired = preset.get(i);
            if (desired.isEmpty() || !mc.player.getInventory().getStack(i).isEmpty()) continue;
            for (int j = 0; j < 36; j++) {
                if (j == i || !isSameItemType(mc.player.getInventory().getStack(j), desired) || satisfied[j]) continue;
                InvUtils.move().from(j).to(i);
                return true;
            }
        }

        for (int i = 0; i < 36; i++) {
            if (satisfied[i] || mc.player.getInventory().getStack(i).isEmpty()) continue;
            for (int j = 0; j < 36; j++) {
                if (j == i || !mc.player.getInventory().getStack(j).isEmpty()) continue;
                InvUtils.move().from(i).to(j);
                return true;
            }
        }
        return false;
    }

    // ─────────────────────── Container Sort ───────────────────────

    private boolean performSortStep() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return false;
        int invSize = handler.getRows() * 9;
        List<ItemStack> current = new ArrayList<>();
        for (int i = 0; i < invSize; i++) current.add(handler.getSlot(i).getStack());
        List<ItemStack> sorted = new ArrayList<>(current);
        sorted.sort(new ShulkerColorComparator());
        for (int i = 0; i < invSize; i++) {
            if (!ItemStack.areEqual(current.get(i), sorted.get(i))) {
                for (int j = i + 1; j < invSize; j++) {
                    if (ItemStack.areEqual(current.get(j), sorted.get(i))) {
                        smartMove(j, i);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ─────────────────────── Trash ───────────────────────

    private boolean performTrashStep() {
        if (mc.player.currentScreenHandler == null) return false;
        ScreenHandler handler = mc.player.currentScreenHandler;
        int playerStart = handler.slots.size() - 36;
        for (int i = playerStart; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && trashItems.get().contains(stack.getItem())) {
                mc.interactionManager.clickSlot(handler.syncId, i, 1, SlotActionType.THROW, mc.player);
                return true;
            }
        }
        return false;
    }

    // ─────────────────────── Utility Helpers ───────────────────────

    /**
     * Smart move — moves an item from one slot to another using the fewest clicks possible.
     *
     * Three strategies (picked automatically):
     *   1. Target is empty            → 2 clicks (pickup + place) — saves 1 click vs old move()
     *   2. Target has a different item → 3 clicks (pickup + place + put-back) — full swap
     *   3. Cursor already holding     → abort (prevents item loss)
     */
    private void smartMove(int from, int to) {
        if (mc.interactionManager == null || mc.player == null) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!handler.getCursorStack().isEmpty()) return;
        int syncId = handler.syncId;

        ItemStack targetStack = handler.getSlot(to).getStack();
        if (targetStack.isEmpty()) {
            // Target is empty — just pick up and place (2 clicks)
            mc.interactionManager.clickSlot(syncId, from, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, to,   0, SlotActionType.PICKUP, mc.player);
        } else {
            // Target has an item — full 3-click swap
            mc.interactionManager.clickSlot(syncId, from, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, to,   0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, from, 0, SlotActionType.PICKUP, mc.player);
        }
    }

    /**
     * Quick move (shift-click) — 1 click, server decides the destination.
     * Best for moving items between inventories (shulker ↔ player)
     * when we don't care about the exact target slot.
     * The server automatically merges partial stacks first, then uses empty slots.
     */
    private void quickMove(int slot) {
        if (mc.interactionManager == null || mc.player == null) return;
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            slot, 0, SlotActionType.QUICK_MOVE, mc.player
        );
    }

    private void tickAutoTrash() {
        if (autoTrash.get() && !isBusy()) {
            if (isTrashing) {
                if (trashTimer > 0) trashTimer--;
                else if (performTrashStep()) trashTimer = trashDelay.get();
                else isTrashing = false;
            }
        }
    }

    private void tickAutoDrop() {
        if (autoTrash.get() && mc.currentScreen == null) {
            if (trashTimer > 0) { trashTimer--; }
            else {
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (!stack.isEmpty() && trashItems.get().contains(stack.getItem())) {
                        InvUtils.drop().slot(i);
                        trashTimer = trashDelay.get();
                        break;
                    }
                }
            }
        }
    }

    private boolean isBusy() {
        return isSorting || isTrashing || isReplenishing || isRegearing || isInvSorting;
    }

    // ─────────────────────── Preset Save / Load ───────────────────────

    private void saveInventory(int index) {
        NbtCompound nbt  = new NbtCompound();
        NbtList     list = new NbtList();
        for (int i = 0; i < 36; i++) encodeSlot(list, mc.player.getInventory().getStack(i), i, mc);
        encodeSlot(list, mc.player.getOffHandStack(), 36, mc);
        encodeSlot(list, mc.player.getEquippedStack(EquipmentSlot.FEET),  37, mc);
        encodeSlot(list, mc.player.getEquippedStack(EquipmentSlot.LEGS),  38, mc);
        encodeSlot(list, mc.player.getEquippedStack(EquipmentSlot.CHEST), 39, mc);
        encodeSlot(list, mc.player.getEquippedStack(EquipmentSlot.HEAD), 40, mc);
        nbt.put("Items", list);
        if (index == 1) preset1Data.set(nbt.toString());
        else            preset2Data.set(nbt.toString());
    }

    private void encodeSlot(NbtList list, ItemStack stack, int slot, net.minecraft.client.MinecraftClient mc) {
        if (stack.isEmpty()) return;
        NbtCompound itemTag = new NbtCompound();
        itemTag.putInt("Slot", slot);
        NbtElement encodedItem = ItemStack.CODEC
            .encodeStart(RegistryOps.of(NbtOps.INSTANCE, mc.world.getRegistryManager()), stack)
            .getOrThrow();
        itemTag.put("item", encodedItem);
        list.add(itemTag);
    }

    private static final int PRESET_SIZE = 41;

    private List<ItemStack> getPreset(int index) {
        String nbtString = (index == 1) ? preset1Data.get() : preset2Data.get();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < PRESET_SIZE; i++) items.add(ItemStack.EMPTY);
        if (nbtString == null || nbtString.isEmpty()) return items;
        try {
            NbtCompound nbt = StringNbtReader.parse(nbtString);
            if (nbt.contains("Items", NbtElement.LIST_TYPE)) {
                NbtList list = nbt.getList("Items", NbtElement.COMPOUND_TYPE);
                for (int i = 0; i < list.size(); i++) {
                    NbtCompound itemTag = list.getCompound(i);
                    int slot = itemTag.getInt("Slot");
                    NbtElement itemNbt = itemTag.get("item");
                    if (slot < PRESET_SIZE && itemNbt != null) {
                        ItemStack.CODEC
                            .parse(RegistryOps.of(NbtOps.INSTANCE, mc.world.getRegistryManager()), itemNbt)
                            .result()
                            .ifPresent(s -> items.set(slot, s));
                    }
                }
            }
        } catch (Exception e) {
            error("Failed to parse inventory preset: " + e.getMessage());
        }
        return items;
    }

    // ─────────────────────── Slot / Item Helpers ───────────────────────

    private Slot getSlotAt(HandledScreen<?> screen, double mouseX, double mouseY) {
        double scaledMouseX = mouseX * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth();
        double scaledMouseY = mouseY * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
        int[] pos = getGuiPos(screen);
        if (pos == null) return null;
        for (Slot slot : screen.getScreenHandler().slots) {
            int x = pos[0] + slot.x, y = pos[1] + slot.y;
            if (scaledMouseX >= x && scaledMouseX < x + 16 && scaledMouseY >= y && scaledMouseY < y + 16) return slot;
        }
        return null;
    }

    private int[] getGuiPos(HandledScreen<?> screen) {
        try {
            Field fX = HandledScreen.class.getDeclaredField("x"); fX.setAccessible(true);
            Field fY = HandledScreen.class.getDeclaredField("y"); fY.setAccessible(true);
            return new int[]{ fX.getInt(screen), fY.getInt(screen) };
        } catch (Exception ignored) {}
        try {
            Field fX = HandledScreen.class.getDeclaredField("field_2776"); fX.setAccessible(true);
            Field fY = HandledScreen.class.getDeclaredField("field_2777"); fY.setAccessible(true);
            return new int[]{ fX.getInt(screen), fY.getInt(screen) };
        } catch (Exception ignored) {}
        try {
            Field fW = HandledScreen.class.getDeclaredField("backgroundWidth");  fW.setAccessible(true);
            Field fH = HandledScreen.class.getDeclaredField("backgroundHeight"); fH.setAccessible(true);
            int bgW = fW.getInt(screen);
            int bgH = fH.getInt(screen);
            return new int[]{ (screen.width - bgW) / 2, (screen.height - bgH) / 2 };
        } catch (Exception ignored) {}
        return new int[]{ (screen.width - 176) / 2, (screen.height - 166) / 2 };
    }

    private Slot getFocusedSlot(HandledScreen<?> screen) {
        try {
            Field f = HandledScreen.class.getDeclaredField("focusedSlot");
            f.setAccessible(true);
            return (Slot) f.get(screen);
        } catch (Exception e) {
            try {
                Field f = HandledScreen.class.getDeclaredField("field_2787");
                f.setAccessible(true);
                return (Slot) f.get(screen);
            } catch (Exception e2) {
                return getSlotUnderMouse(screen);
            }
        }
    }

    private Slot getSlotUnderMouse(HandledScreen<?> screen) {
        return getSlotAt(screen, mc.mouse.getX(), mc.mouse.getY());
    }

    private int mapInventoryToSlotId(int invIndex) {
        if (invIndex >= 0 && invIndex < 9)  return 54 + invIndex;
        if (invIndex >= 9 && invIndex < 36) return 27 + (invIndex - 9);
        return -1;
    }

    private int findBestTool(BlockState state) {
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i; }
        }
        return bestSlot;
    }

    private boolean isSameItemType(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.getItem() == b.getItem();
    }

    private boolean isLowDurabilityElytra(ItemStack stack) {
        return stack.isOf(Items.ELYTRA) && (stack.getMaxDamage() - stack.getDamage() < elytraThreshold.get());
    }

    private static class ShulkerColorComparator implements Comparator<ItemStack> {
        @Override
        public int compare(ItemStack o1, ItemStack o2) {
            boolean s1 = isShulker(o1), s2 = isShulker(o2);
            if (s1 && !s2) return -1;
            if (!s1 && s2) return 1;
            if (!s1)       return 0;
            return Integer.compare(getColorId(o1), getColorId(o2));
        }
        private boolean isShulker(ItemStack stack) {
            return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
        }
        private int getColorId(ItemStack stack) {
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock sb) {
                DyeColor c = sb.getColor();
                return c == null ? 16 : c.getId();
            }
            return 17;
        }
    }
}