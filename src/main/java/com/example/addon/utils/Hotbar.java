package com.example.addon.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Handles common hotbar searches and slot changes.
 */
public final class Hotbar {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private Hotbar() {}

    /**
     * Finds an item in the hotbar.
     *
     * @param item item to find
     * @return matching zero-based hotbar slot, or -1 when unavailable
     */
    public static int find(Item item) {
        return find(stack -> stack.isOf(item));
    }

    /**
     * Finds the first matching stack in the hotbar.
     *
     * @param predicate stack condition
     * @return matching zero-based hotbar slot, or -1 when unavailable
     */
    public static int find(Predicate<ItemStack> predicate) {
        return find(0, 8, predicate);
    }

    /**
     * Finds the first matching stack inside a hotbar range.
     *
     * @param first first zero-based hotbar slot
     * @param last last zero-based hotbar slot
     * @param predicate stack condition
     * @return matching zero-based hotbar slot, or -1 when unavailable
     */
    public static int find(
        int first, int last,
        Predicate<ItemStack> predicate) {

        int start = Math.min(first, last);
        int end = Math.max(first, last);

        for (int slot = start; slot <= end; slot++) {
            if (predicate.test(stack(slot))) return slot;
        }

        return -1;
    }

    /**
     * Finds a matching stack while checking the selected slot first.
     *
     * @param predicate stack condition
     * @return matching zero-based hotbar slot, or -1 when unavailable
     */
    public static int first(Predicate<ItemStack> predicate) {
        int selected = selected();
        if (predicate.test(stack(selected))) {
            return selected;
        }

        for (int slot = 0; slot < 9; slot++) {
            if (slot != selected && predicate.test(stack(slot))) {
                return slot;
            }
        }

        return -1;
    }

    /**
     * Finds the highest scoring stack in the hotbar.
     *
     * @param predicate stack condition
     * @param score stack score
     * @return matching zero-based hotbar slot, or -1 when unavailable
     */
    public static int best(
        Predicate<ItemStack> predicate,
        ToIntFunction<ItemStack> score) {

        int slot = -1;
        int best = Integer.MIN_VALUE;

        for (int idx = 0; idx < 9; idx++) {
            ItemStack stack = stack(idx);
            if (!predicate.test(stack)) continue;

            int current = score.applyAsInt(stack);
            if (current <= best) continue;

            best = current;
            slot = idx;
        }

        return slot;
    }

    /**
     * Counts an item across the hotbar.
     *
     * @param item item to count
     * @return total item count
     */
    public static int count(Item item) {
        int count = 0;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = stack(slot);
            if (stack.isOf(item)) count += stack.getCount();
        }

        return count;
    }

    /**
     * Returns the currently selected hotbar slot.
     *
     * @return zero-based selected slot
     */
    public static int selected() {
        return client.player.getInventory().selectedSlot;
    }

    /**
     * Returns a hotbar stack.
     *
     * @param slot zero-based hotbar slot
     * @return item stack stored in the slot
     */
    public static ItemStack stack(int slot) {
        return client.player.getInventory().getStack(slot);
    }

    /**
     * Changes the selected hotbar slot on the client.
     *
     * @param slot zero-based hotbar slot
     */
    public static void set(int slot) {
        client.player.getInventory().setSelectedSlot(slot);
    }

    /**
     * Sends the selected hotbar slot to the server.
     *
     * @param slot zero-based hotbar slot
     */
    public static void sync(int slot) {
        client.getNetworkHandler().sendPacket(
            new UpdateSelectedSlotC2SPacket(slot)
        );
    }

    /**
     * Changes the selected hotbar slot on the client and server.
     *
     * @param slot zero-based hotbar slot
     */
    public static void select(int slot) {
        set(slot);
        sync(slot);
    }

    /**
     * Temporarily swaps an inventory item into the selected hotbar slot.
     *
     * @param slot hotbar or inventory slot to use
     * @return true when the swap was prepared
     */
    public static boolean swap(int slot) {
        return InvUtils.swap(slot, true);
    }

    /**
     * Restores the hotbar state saved by the last temporary swap.
     */
    public static void restore() {
        InvUtils.swapBack();
    }
}