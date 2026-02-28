package com.example.addon.modules;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.addon.HuntingUtilities;
import org.lwjgl.glfw.GLFW;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
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

public class Inventory101 extends Module {
    private final SettingGroup sgRegearing = settings.createGroup("Regearing 101");
    private final SettingGroup sgCleaner   = settings.createGroup("Inventory Cleaner");
    private final SettingGroup sgOrganizer = settings.createGroup("Shulker Organizer");

    // ── Regearing ──
    private final Setting<Integer> regearDelay = sgRegearing.add(new IntSetting.Builder()
        .name("regear-movement-delay")
        .description("Delay in ticks between moving items from shulker to inventory.")
        .defaultValue(4).min(1).sliderMax(20)
        .build()
    );
    private final Setting<String> preset1Data = sgRegearing.add(new StringSetting.Builder()
        .name("preset-1-data").description("Saved data for inventory preset 1.")
        .defaultValue("").visible(() -> false)
        .build()
    );
    private final Setting<String> preset2Data = sgRegearing.add(new StringSetting.Builder()
        .name("preset-2-data").description("Saved data for inventory preset 2.")
        .defaultValue("").visible(() -> false)
        .build()
    );

    // ── Cleaner ──
    private final Setting<Boolean> autoDrop = sgCleaner.add(new BoolSetting.Builder()
        .name("inventory-cleaner")
        .description("Automatically drops whitelisted items from your inventory.")
        .defaultValue(false).build()
    );
    private final Setting<List<Item>> itemsToDrop = sgCleaner.add(new ItemListSetting.Builder()
        .name("items-to-drop").description("Items to automatically drop.")
        .defaultValue(new ArrayList<>()).visible(autoDrop::get)
        .build()
    );
    private final Setting<Integer> dropDelay = sgCleaner.add(new IntSetting.Builder()
        .name("drop-delay").description("Delay in ticks between drops.")
        .defaultValue(2).min(1).visible(autoDrop::get)
        .build()
    );
    private final Setting<Boolean> autoTrash = sgCleaner.add(new BoolSetting.Builder()
        .name("auto-trash")
        .description("Automatically throws away junk items when opening a container.")
        .defaultValue(false).build()
    );
    private final Setting<List<Item>> trashItems = sgCleaner.add(new ItemListSetting.Builder()
        .name("trash-items").description("Items to throw away.")
        .defaultValue(new ArrayList<>()).visible(autoTrash::get)
        .build()
    );
    private final Setting<Integer> trashDelay = sgCleaner.add(new IntSetting.Builder()
        .name("trash-delay").description("Delay in ticks between throwing items.")
        .defaultValue(2).min(1).visible(autoTrash::get)
        .build()
    );

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

    // ── State ──
    private boolean isRegearing = false;
    private int     regearTimer = 0;
    private boolean isSorting   = false;
    private int     sortTimer   = 0;
    private int     cleanerTimer = 0;
    private boolean isTrashing  = false;
    private int     trashTimer  = 0;
    private boolean trashedForCurrentScreen = false;
    private boolean wasClicking = false;
    private int     targetPresetIndex = 0;
    private boolean saveMode = false;
    private double lastMouseX = -1, lastMouseY = -1;
    private final Set<Integer> processedInDrag = new java.util.HashSet<>();

    public Inventory101() {
        super(HuntingUtilities.CATEGORY, "inventory-101", "Manages inventory layouts with shulker boxes.");
    }

    @Override
    public void onDeactivate() {
        isRegearing = false;
        saveMode    = false;
        isSorting   = false;
        isTrashing  = false;
        wasClicking = false;
        lastMouseX = -1;
        lastMouseY = -1;
        processedInDrag.clear();
    }

    // ─────────────────────── Public API for HandledScreenMixin ───────────────────────

    /** @return true when the Sort button should be shown in generic container screens. */
    public boolean isSortButtonEnabled() {
        return showSortButton.get();
    }

    /** Called by the Sort button click handler. */
    public void startSorting() {
        if (isBusy()) return;
        isSorting = true;
        sortTimer = 0;
    }

    /** Called by the S (save-mode toggle) button. */
    public void toggleSaveMode() {
        if (isBusy()) return;
        saveMode = !saveMode;
        info(saveMode ? "Select a preset slot (1 or 2) to SAVE." : "Save mode cancelled.");
    }

    /** @return whether save-mode is currently active. */
    public boolean isSaveMode() {
        return saveMode;
    }

    /** @return true if the given preset slot (1 or 2) has no saved data yet. */
    public boolean isPresetEmpty(int index) {
        String data = (index == 1) ? preset1Data.get() : preset2Data.get();
        return data == null || data.isEmpty();
    }

    /** Called by the preset buttons (1 / 2). */
    public void handlePreset(int index) {
        if (isBusy() && !saveMode) return;
        if (saveMode) {
            saveInventory(index);
            saveMode = false;
            info("Saved inventory to preset " + index + ".");
        } else {
            List<ItemStack> target = getPreset(index);
            if (target.stream().allMatch(ItemStack::isEmpty)) {
                warning("Preset " + index + " is empty.");
                return;
            }
            targetPresetIndex = index;
            isRegearing = true;
            regearTimer = 0;
            info("Loading preset " + index + "...");
        }
    }

    /** Called by the C (clear) button. */
    public void clearPresets() {
        preset1Data.set("");
        preset2Data.set("");
        saveMode = false;
        info("Presets cleared.");
    }

    // ─────────────────────── Tick Handler ───────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // High-priority blocking tasks
        if (isRegearing) {
            if (!(mc.currentScreen instanceof ShulkerBoxScreen)) { isRegearing = false; return; }
            if (regearTimer > 0) { regearTimer--; return; }
            if (performRegearStep()) {
                regearTimer = regearDelay.get();
            } else {
                mc.player.closeHandledScreen();
                info("Regearing complete.");
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                isRegearing = false;
            }
            return;
        }

        if (isSorting) {
            if (!(mc.currentScreen instanceof GenericContainerScreen)) { isSorting = false; return; }
            if (sortTimer > 0) { sortTimer--; return; }
            if (performSortStep()) { sortTimer = sortDelay.get(); } else { isSorting = false; info("Sorting complete."); }
            return;
        }

        // Mouse Drag Item Move (user interaction, high priority)
        if (mc.currentScreen instanceof HandledScreen) {
            HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
            boolean isClicking = Input.isButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            boolean isShift = Input.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT) || Input.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT);
    
            if (isClicking && isShift) {
                double mouseX = mc.mouse.getX();
                double mouseY = mc.mouse.getY();
    
                if (!wasClicking) { // Drag start
                    processedInDrag.clear();
                } else if (lastMouseX != -1) { // Drag continue - interpolate
                    double deltaX = mouseX - lastMouseX;
                    double deltaY = mouseY - lastMouseY;
                    double dist = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
    
                    if (dist > 1) {
                        int steps = (int) Math.ceil(dist / 2.0); // Check every 2 pixels
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
    
                // Always process current slot in case interpolation missed it or it's the first click
                Slot focused = getFocusedSlot(screen);
                if (focused != null && focused.hasStack() && !processedInDrag.contains(focused.id)) {
                    mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, focused.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                    processedInDrag.add(focused.id);
                }
    
                lastMouseX = mouseX;
                lastMouseY = mouseY;
                wasClicking = true;
                return; // Done for this tick
            } else {
                if (wasClicking) { // Drag ended
                    processedInDrag.clear();
                    lastMouseX = -1;
                }
                wasClicking = false;
            }
        } else {
            if (wasClicking) { // Screen closed during drag
                processedInDrag.clear();
                lastMouseX = -1;
            }
            wasClicking = false;
        }

        // Auto-trash
        if (autoTrash.get()) {
            if (mc.currentScreen instanceof GenericContainerScreen || mc.currentScreen instanceof ShulkerBoxScreen) {
                if (!trashedForCurrentScreen) { isTrashing = true; trashedForCurrentScreen = true; }
            } else {
                trashedForCurrentScreen = false;
                isTrashing = false;
            }
            if (isTrashing) {
                if (trashTimer > 0) {
                    trashTimer--;
                } else if (performTrashStep()) {
                    trashTimer = trashDelay.get();
                    return; // Action performed, end tick
                } else {
                    isTrashing = false;
                }
            }
        }

        // Auto-drop
        if (autoDrop.get()) {
            if (cleanerTimer > 0) { cleanerTimer--; }
            else {
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (!stack.isEmpty() && itemsToDrop.get().contains(stack.getItem())) {
                        InvUtils.drop().slot(i);
                        cleanerTimer = dropDelay.get();
                        return; // Action performed, end tick
                    }
                }
            }
        }
    }

    // ─────────────────────── Internal Logic ───────────────────────

    private void saveInventory(int index) {
        NbtCompound nbt  = new NbtCompound();
        NbtList     list = new NbtList();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                NbtCompound itemTag = new NbtCompound();
                itemTag.putByte("Slot", (byte) i);
                NbtElement encodedItem = ItemStack.CODEC
                    .encodeStart(RegistryOps.of(NbtOps.INSTANCE, mc.world.getRegistryManager()), stack)
                    .getOrThrow();
                itemTag.put("item", encodedItem);
                list.add(itemTag);
            }
        }
        nbt.put("Items", list);
        if (index == 1) preset1Data.set(nbt.toString());
        else            preset2Data.set(nbt.toString());
    }

    private List<ItemStack> getPreset(int index) {
        String nbtString = (index == 1) ? preset1Data.get() : preset2Data.get();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 36; i++) items.add(ItemStack.EMPTY);
        if (nbtString == null || nbtString.isEmpty()) return items;
        try {
            NbtCompound nbt = StringNbtReader.parse(nbtString);
            if (nbt.contains("Items", NbtElement.LIST_TYPE)) {
                NbtList list = nbt.getList("Items", NbtElement.COMPOUND_TYPE);
                for (int i = 0; i < list.size(); i++) {
                    NbtCompound itemTag = list.getCompound(i);
                    int slot = itemTag.getByte("Slot") & 255;
                    NbtElement itemNbt = itemTag.get("item");
                    if (slot < 36 && itemNbt != null) {
                        // Must decode with the same CODEC used during save (1.21 component format)
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

    private boolean performRegearStep() {
        List<ItemStack> preset = getPreset(targetPresetIndex);
        if (preset.stream().allMatch(ItemStack::isEmpty)) return false;
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)) return false;

        // Only pull items FROM the shulker INTO the player inventory.
        // We never dump player items back into the shulker — the user just wants
        // their saved items loaded into the correct inventory slots.
        for (int i = 0; i < 36; i++) {
            ItemStack desired = preset.get(i);

            int slotId = mapInventoryToSlotId(i);
            if (slotId < 0) continue;

            ItemStack current = handler.getSlot(slotId).getStack();
            if (!isItemEqual(current, desired)) {
                // 1. If desired is empty (Air), dump current item to Shulker
                if (desired.isEmpty()) {
                    if (!current.isEmpty()) {
                        int dumpSlot = findEmptyShulkerSlot(handler);
                        if (dumpSlot != -1) {
                            move(slotId, dumpSlot);
                            return true;
                        }
                    }
                    continue;
                }

                // 2. Try to find desired item in Shulker
                int sourceSlot = findItemInShulker(handler, desired);
                
                // 3. If not in Shulker, try to find in Player Inventory (but not the current slot)
                if (sourceSlot == -1) {
                    sourceSlot = findItemInPlayerInv(handler, desired, slotId);
                }

                if (sourceSlot != -1) {
                    move(sourceSlot, slotId);
                    return true; // one action per tick
                }

                // 4. If we can't find the desired item, but the slot is occupied by something else, dump it to shulker
                if (!current.isEmpty()) {
                    int dumpSlot = findEmptyShulkerSlot(handler);
                    if (dumpSlot != -1) {
                        move(slotId, dumpSlot);
                        return true;
                    }
                }
            }
        }
        return false; // nothing left to move → regearing complete
    }

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
                        move(j, i);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void move(int from, int to) {
        if (mc.interactionManager == null || mc.player == null) return;
        int syncId = mc.player.currentScreenHandler.syncId;
        // Perform a standard 3-click swap: Pickup A -> Pickup B (swaps) -> Pickup A (puts back if B was occupied)
        mc.interactionManager.clickSlot(syncId, from, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, to, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, from, 0, SlotActionType.PICKUP, mc.player);
    }

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

    private Slot getSlotAt(HandledScreen<?> screen, double mouseX, double mouseY) {
        double scaledMouseX = mouseX * mc.getWindow().getScaledWidth() / (double)mc.getWindow().getWidth();
        double scaledMouseY = mouseY * mc.getWindow().getScaledHeight() / (double)mc.getWindow().getHeight();

        int guiLeft = 0, guiTop = 0;
        try {
            Field fX = HandledScreen.class.getDeclaredField("x"); fX.setAccessible(true);
            Field fY = HandledScreen.class.getDeclaredField("y"); fY.setAccessible(true);
            guiLeft = fX.getInt(screen); guiTop = fY.getInt(screen);
        } catch (Exception e) {
            try {
                Field fX = HandledScreen.class.getDeclaredField("field_2776"); fX.setAccessible(true);
                Field fY = HandledScreen.class.getDeclaredField("field_2777"); fY.setAccessible(true);
                guiLeft = fX.getInt(screen); guiTop = fY.getInt(screen);
            } catch (Exception e2) {
                // Fallback for screens that don't have x/y fields
                guiLeft = (screen.width - 176) / 2;
                guiTop = (screen.height - 166) / 2;
            }
        }
        for (Slot slot : screen.getScreenHandler().slots) {
            int x = guiLeft + slot.x; int y = guiTop + slot.y;
            if (scaledMouseX >= x && scaledMouseX < x + 16 && scaledMouseY >= y && scaledMouseY < y + 16) return slot;
        }
        return null;
    }

    private Slot getFocusedSlot(HandledScreen<?> screen) {
        try {
            Field f = HandledScreen.class.getDeclaredField("focusedSlot");
            f.setAccessible(true);
            return (Slot) f.get(screen);
        } catch (Exception e) {
            try {
                Field f = HandledScreen.class.getDeclaredField("field_2787"); // Intermediary fallback
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
        if (invIndex >= 0 && invIndex < 9)  return 54 + invIndex;       // hotbar
        if (invIndex >= 9 && invIndex < 36) return 27 + (invIndex - 9); // main storage
        return -1;
    }

    private int findItemInPlayerInv(ShulkerBoxScreenHandler handler, ItemStack target, int excludeSlotId) {
        // Player inventory slots in ShulkerBoxScreenHandler are 27 to 62 (27 + 36 = 63 slots total, indices 0-62)
        for (int i = 27; i < 63; i++) {
            if (i == excludeSlotId) continue;
            if (isItemEqual(handler.getSlot(i).getStack(), target)) return i;
        }
        return -1;
    }

    private int findEmptyShulkerSlot(ShulkerBoxScreenHandler handler) {
        for (int i = 0; i < 27; i++) if (handler.getSlot(i).getStack().isEmpty()) return i;
        return -1;
    }

    private int findItemInShulker(ShulkerBoxScreenHandler handler, ItemStack target) {
        for (int i = 0; i < 27; i++) if (isItemEqual(handler.getSlot(i).getStack(), target)) return i;
        return -1;
    }

    private boolean isItemEqual(ItemStack a, ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) return true;
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.getItem() == b.getItem();
    }

    private boolean isBusy() {
        return isRegearing || isSorting || isTrashing;
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
