package com.example.addon.modules;

import com.example.addon.Tim;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules; // Added import
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.process.IBaritoneProcess;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.AxeItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.SwordItem;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

public class Baromine extends Module {
    private final SettingGroup sgTarget = settings.createGroup("Target");
    private final SettingGroup sgSafety = settings.createGroup("Safety & Limits");
    private final SettingGroup sgDeposit = settings.createGroup("Auto Deposit");
    private final SettingGroup sgMend = settings.createGroup("Auto Mend");
    private final SettingGroup sgSession = settings.createGroup("Session");
    private final SettingGroup sgNotifications = settings.createGroup("Notifications");

    // --- TARGET ---
    public enum TargetMode {
        Ores,
        Blocks
    }

    private final Setting<TargetMode> targetMode = sgTarget.add(new EnumSetting.Builder<TargetMode>()
        .name("target-mode")
        .description("What type of block to mine.")
        .defaultValue(TargetMode.Ores)
        .onChanged(mode -> updateBaritoneGoal())
        .build()
    );

    private final Setting<Block> targetOre = sgTarget.add(new BlockSetting.Builder()
        .name("target-ore")
        .description("The specific ore to mine.")
        .visible(() -> targetMode.get() == TargetMode.Ores)
        .defaultValue(Blocks.DIAMOND_ORE)
        .filter(this::isOre)
        .onChanged(block -> updateBaritoneGoal())
        .build()
    );

    private final Setting<Block> targetBlock = sgTarget.add(new BlockSetting.Builder()
        .name("target-block")
        .description("The specific block to mine.")
        .visible(() -> targetMode.get() == TargetMode.Blocks)
        .defaultValue(Blocks.STONE)
        .filter(block -> !isOre(block) && block != Blocks.AIR)
        .onChanged(block -> updateBaritoneGoal())
        .build()
    );

    private final Setting<Boolean> includeDeepslate = sgTarget.add(new BoolSetting.Builder()
        .name("include-deepslate")
        .description("Also mines the deepslate variant of the selected ore.")
        .visible(() -> targetMode.get() == TargetMode.Ores)
        .defaultValue(true)
        .onChanged(b -> updateBaritoneGoal())
        .build()
    );

    public final Setting<Integer> targetStacks = sgTarget.add(new IntSetting.Builder()
        .name("target-stacks")
        .description("Total amount of stacks to mine before stopping the module entirely.")
        .defaultValue(1)
        .sliderRange(1, 64)
        .build()
    );

    public enum CraftMode {
        Disabled,
        Ores,
        Blocks
    }

    private final Setting<CraftMode> autoCraft = sgTarget.add(new EnumSetting.Builder<CraftMode>()
        .name("auto-craft")
        .description("Automatically crafts items using a crafting table.")
        .defaultValue(CraftMode.Disabled)
        .build()
    );

    private final Setting<List<Block>> craftOreList = sgTarget.add(new BlockListSetting.Builder()
        .name("craft-ore-list")
        .description("Ore blocks to craft from raw materials (e.g. Iron Block, Diamond Block).")
        .visible(() -> autoCraft.get() == CraftMode.Ores)
        .filter(block -> getOreRecipe(block) != null)
        .build()
    );

    private final Setting<List<Block>> craftBlockList = sgTarget.add(new BlockListSetting.Builder()
        .name("craft-block-list")
        .description("Blocks to craft from mined materials (e.g. Stone Bricks, Slabs, Stairs).")
        .visible(() -> autoCraft.get() == CraftMode.Blocks)
        .filter(block -> getBlockRecipe(block) != null)
        .build()
    );

    // --- SAFETY & LIMITS ---
    private final Setting<Double> warningHealth = sgSafety.add(new DoubleSetting.Builder()
        .name("warning-health")
        .description("Health level to trigger a warning sound and chat message.")
        .defaultValue(12.0)
        .sliderRange(1.0, 20.0)
        .build()
    );

    private final Setting<Double> criticalHealth = sgSafety.add(new DoubleSetting.Builder()
        .name("critical-health")
        .description("Health level to trigger an instant disconnect from the server.")
        .defaultValue(6.0)
        .sliderRange(1.0, 20.0)
        .build()
    );

    private final Setting<Boolean> disconnectOnPlayer = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-player")
        .description("Instantly disconnects if another player enters render distance.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseInCombat = sgSafety.add(new BoolSetting.Builder()
        .name("pause-in-combat")
        .description("Pauses Baromine if a hostile mob is within 5 blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> goldenHelmet = sgSafety.add(new BoolSetting.Builder()
        .name("golden-helmet")
        .description("Stops mining if you are not wearing a Golden Helmet (prevents Piglin aggression).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> lavaAvoidance = sgSafety.add(new BoolSetting.Builder()
        .name("lava-avoidance")
        .description("Activates safety protocols only if lava is directly touching you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> waterAvoidance = sgSafety.add(new BoolSetting.Builder()
        .name("water-avoidance")
        .description("Activates safety protocols if water is directly touching you.")
        .defaultValue(false)
        .build()
    );

    public enum ExcessDropMode {
        Disabled,
        Cobblestone,
        Netherrack,
        CobbledDeepslate
    }

    private final Setting<ExcessDropMode> dropExcessMode = sgSafety.add(new EnumSetting.Builder<ExcessDropMode>()
        .name("drop-excess")
        .description("Automatically drops excess blocks, keeping only a single stack of 64.")
        .defaultValue(ExcessDropMode.Disabled)
        .build()
    );

    private final Setting<Boolean> avoidDeepDark = sgSafety.add(new BoolSetting.Builder()
        .name("avoid-deep-dark")
        .description("Uses a tactical #goto retreat if you enter the Deep Dark biome.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> safeLogout = sgSafety.add(new BoolSetting.Builder()
        .name("safe-logout")
        .description("Hides inside a wall before turning off the module when the target is reached.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minYLevel = sgSafety.add(new IntSetting.Builder()
        .name("min-y-level")
        .description("Stops mining if the player goes below this Y-level.")
        .defaultValue(-64)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Integer> maxYLevel = sgSafety.add(new IntSetting.Builder()
        .name("max-y-level")
        .description("Stops mining if the player goes above this Y-level.")
        .defaultValue(320)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Boolean> radiusLimit = sgSafety.add(new BoolSetting.Builder()
        .name("radius-limit")
        .description("Stops mining if the player wanders too far from the starting position.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> radiusBlocks = sgSafety.add(new IntSetting.Builder()
        .name("radius-blocks")
        .description("The maximum block radius from the starting position.")
        .visible(radiusLimit::get)
        .defaultValue(500)
        .sliderRange(50, 5000)
        .build()
    );

    // --- AUTO DEPOSIT ---
    public enum DepositMode {
        Disabled,
        Inventory,
        EnderChest
    }

    public enum ToolEnchant {
        SilkTouch,
        Fortune
    }

    private final Setting<DepositMode> depositMode = sgDeposit.add(new EnumSetting.Builder<DepositMode>()
        .name("deposit-mode")
        .description("Where to stash items. Inventory uses Shulkers directly, EnderChest uses an Ender Chest.")
        .defaultValue(DepositMode.EnderChest)
        .build()
    );

    private final Setting<Integer> depositStacks = sgDeposit.add(new IntSetting.Builder()
        .name("deposit-stacks")
        .description("How many stacks of items in your inventory trigger the deposit process.")
        .visible(() -> depositMode.get() != DepositMode.Disabled)
        .defaultValue(1)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Integer> swapSlot = sgDeposit.add(new IntSetting.Builder()
        .name("swap-slot")
        .description("The hotbar slot used to swap Shulker Boxes and Ender Chests into.")
        .defaultValue(1)
        .sliderRange(0, 8)
        .visible(() -> depositMode.get() != DepositMode.Disabled)
        .build()
    );

    private final Setting<ToolEnchant> toolEnchant = sgDeposit.add(new EnumSetting.Builder<ToolEnchant>()
        .name("tool-enchant")
        .description("Which enchantment to require when breaking blocks and replacing tools.")
        .visible(() -> depositMode.get() != DepositMode.Disabled)
        .defaultValue(ToolEnchant.SilkTouch)
        .build()
    );

    private final Setting<Item> foodItem = sgDeposit.add(new ItemSetting.Builder()
        .name("food-item")
        .description("Food item to automatically pull from the Ender Chest when low. Raw foods are excluded.")
        .visible(() -> depositMode.get() == DepositMode.EnderChest)
        .filter(item -> {
            if (!new ItemStack(item).contains(DataComponentTypes.FOOD)) return false;
            String id = Registries.ITEM.getId(item).getPath();
            return !id.contains("raw") 
                && !id.contains("rotten") 
                && !id.equals("cod") 
                && !id.equals("salmon") 
                && !id.equals("tropical_fish") 
                && !id.equals("pufferfish")
                && !id.equals("potato") 
                && !id.equals("poisonous_potato")
                && !id.equals("spider_eye");
        })
        .defaultValue(Items.ENCHANTED_GOLDEN_APPLE)
        .build()
    );

    private final Setting<Integer> minFoodCount = sgDeposit.add(new IntSetting.Builder()
        .name("min-food-count")
        .description("Minimum amount of selected food to keep in your inventory. Triggers regear if below.")
        .visible(() -> depositMode.get() == DepositMode.EnderChest && foodItem.get() != Items.AIR)
        .defaultValue(10)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Keybind> highlightContainerKey = sgDeposit.add(new KeybindSetting.Builder()
        .name("highlight-craft-container")
        .description("Look at a Shulker Box or Chest and press this to set it as the material source for crafting.")
        .visible(() -> autoCraft.get() == CraftMode.Ores || autoCraft.get() == CraftMode.Blocks)
        .defaultValue(Keybind.none())
        .build()
    );

    // --- AUTO MEND ---
    private final Setting<Boolean> autoMend = sgMend.add(new BoolSetting.Builder()
        .name("auto-mend")
        .description("Mines XP ores to repair tools. Pauses mining, repairs, and resumes automatically.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Block> mendOreBlock = sgMend.add(new BlockSetting.Builder()
        .name("mend-ore-block")
        .description("The ore to mine for XP when auto-mending (e.g., Nether Quartz, Coal).")
        .visible(autoMend::get)
        .defaultValue(Blocks.NETHER_QUARTZ_ORE)
        .filter(this::isOre)
        .build()
    );

    private final Setting<Double> maxMendDurability = sgMend.add(new DoubleSetting.Builder()
        .name("max-mend-durability")
        .description("The durability percentage to reach before stopping Auto-Mend and resuming mining.")
        .visible(autoMend::get)
        .defaultValue(70.0)
        .sliderRange(10.0, 100.0)
        .build()
    );

    private final Setting<Double> minToolDurability = sgMend.add(new DoubleSetting.Builder()
        .name("min-tool-durability")
        .description("The durability percentage to trigger replacing tools or activating Auto-Mend.")
        .visible(() -> depositMode.get() != DepositMode.Disabled || autoMend.get())
        .defaultValue(10.0)
        .sliderRange(1.0, 50.0)
        .build()
    );

    // --- SESSION ---
    private final Setting<Boolean> antiAfk = sgSession.add(new BoolSetting.Builder()
        .name("anti-afk")
        .description("Periodically jumps and swings hand to prevent being kicked for AFK when stuck.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enableMaxRuntime = sgSession.add(new BoolSetting.Builder()
        .name("enable-max-runtime")
        .description("Enables a maximum time limit before the module automatically turns off.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> maxRuntimeHours = sgSession.add(new DoubleSetting.Builder()
        .name("max-runtime-hours")
        .description("Maximum hours to run before automatically turning off.")
        .visible(enableMaxRuntime::get)
        .defaultValue(8.0)
        .sliderRange(0.5, 24.0)
        .build()
    );

    private final Setting<Boolean> autoReconnect = sgSession.add(new BoolSetting.Builder()
        .name("auto-reconnect")
        .description("Keeps the module enabled and reconnects after X hours if disconnected by safety or timeout.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> reconnectHours = sgSession.add(new IntSetting.Builder()
        .name("delay-hours")
        .description("How many hours to wait before attempting to reconnect.")
        .visible(autoReconnect::get)
        .defaultValue(2)
        .sliderRange(1, 24)
        .build()
    );

    // --- NOTIFICATIONS ---
    public enum PingMode {
        Chat,
        Sound,
        Both
    }

    public enum WarningSound {
        Pling,
        Bass,
        Harp,
        Bell,
        Anvil,
        LevelUp,
        OrbPickup,
        Beacon,
        GhastWarn,
        DragonGrowl,
        WitherSpawn,
        ChallengeComplete
    }

    private final Setting<PingMode> pingMode = sgNotifications.add(new EnumSetting.Builder<PingMode>()
        .name("ping-mode")
        .description("How you want to be notified of module events.")
        .defaultValue(PingMode.Both)
        .build()
    );

    private final Setting<WarningSound> warningSound = sgNotifications.add(new EnumSetting.Builder<WarningSound>()
        .name("warning-sound")
        .description("Which sound to play for notifications.")
        .visible(() -> pingMode.get() == PingMode.Sound || pingMode.get() == PingMode.Both)
        .defaultValue(WarningSound.Pling)
        .build()
    );

    private final Setting<Double> soundVolume = sgNotifications.add(new DoubleSetting.Builder()
        .name("volume")
        .description("The volume of the warning sound.")
        .visible(() -> pingMode.get() == PingMode.Sound || pingMode.get() == PingMode.Both)
        .defaultValue(1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    private static final Predicate<ItemStack> SHULKER_PREDICATE = stack ->
        stack.getItem() instanceof net.minecraft.item.BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;

    // --- VARIABLES ---
    private boolean wasPausedForCombat = false;
    private boolean hasHealthWarned = false;
    private boolean playerLogoutPending = false;
    private boolean isFinalDeposit = false;
    private boolean autoMendFailed = false; 

    // Auto-Mend Variables
    private boolean isAutoMending = false;
    private Block savedTargetBlock;
    private int mendToolSwapDelay = 0;

    private ServerInfo lastServer = null;
    private boolean isWaitingToReconnect = false;
    private long reconnectTime = 0;

    private long startTime = 0;
    private double startX = 0;
    private double startZ = 0;
    private int antiAfkTickCounter = 0;
    private int jumpTicks = 0;

    private boolean shulkerRecoveryAttempted = false;
    private int pickupTimeout = 0;

    private boolean isHandlingLava = false;
    private int lavaSafetyDelay = 0;
    private int lavaSafetyCounter = 0;
    private int lavaMoveTicks = 0;

    private boolean isHandlingWater = false;
    private int waterSafetyDelay = 0;
    private int waterSafetyCounter = 0;
    private int waterMoveTicks = 0;

    private boolean wasPausedForPortalMaker = false;

    // Safe Logout Variables
    private enum HideoutState {
        IDLE,
        DIGGING,
        ENTERING,
        SEALING,
        DONE
    }
    private HideoutState hideoutState = HideoutState.IDLE;
    private int hideoutDelay = 0;
    private Direction hideoutDir = null;
    private BlockPos hideoutPos = null;

    // Deep Dark Retreat Variables
    private enum DeepDarkState {
        IDLE,
        RETREATING,
        ASCENDING,
        RUNNING_AWAY
    }
    private DeepDarkState deepDarkState = DeepDarkState.IDLE;
    private final Deque<BlockPos> safePosQueue = new ArrayDeque<>();
    private static final int MAX_QUEUE_SIZE = 10; 
    private int safePosTimer = 0;
    private int retreatDelay = 0;
    private static final Random RANDOM = new Random();

    // Craft Variables
    private enum CraftState {
        IDLE,
        GATHERING_WOOD,
        FINDING_TABLE,
        PLACING_TABLE,
        OPENING_TABLE,
        PULLING_MATERIALS,
        CRAFTING,
        CLOSING_TABLE,
        BREAKING_TABLE,
        PICKING_UP_TABLE,
        RESUMING
    }
    private CraftState craftState = CraftState.IDLE;
    private int craftDelay = 0;
    private int craftStep = 0;
    private BlockPos craftTablePos = null;
    private BlockPos craftContainerPos1 = null;
    private BlockPos craftContainerPos2 = null;

    private enum DepositState {
        IDLE,
        PAUSING_BARITONE,
        CLEARING_SPACE,
        PLACING_ECHEST,
        OPENING_ECHEST,
        EXTRACTING_SHULKER,
        CLOSING_ECHEST,
        PLACING_SHULKER,
        OPENING_SHULKER,
        TRANSFERRING_ITEMS,
        CLOSING_SHULKER,
        BREAKING_SHULKER,
        PICKING_UP_SHULKER,
        REOPENING_ECHEST,
        DEPOSITING_SHULKER,
        REPLACING_TOOLS,
        REGEAR_FOOD,
        CLOSING_ECHEST_AGAIN,
        BREAKING_ECHEST,
        PICKING_UP_ECHEST,
        MINING_SURROUNDINGS_SHULKER,
        RESUMING
    }

    private DepositState depositState = DepositState.IDLE;
    private int depositDelay = 0;
    private BlockPos echestPos = null;
    private BlockPos shulkerPos = null;
    private boolean spaceClearingStarted = false;
    private int spaceClearAttempts = 0;
    private int spaceClearMinWait = 0;

    public Baromine() {
        super(Tim.CATEGORY, "baromine", "Automated Baritone miner for targeted ores or blocks with heavy safety protocols.");
    }

    @Override
    public void onActivate() {
        wasPausedForCombat = false;
        hasHealthWarned = false;
        playerLogoutPending = false;
        isFinalDeposit = false;
        isAutoMending = false;
        autoMendFailed = false;
        hideoutState = HideoutState.IDLE;
        deepDarkState = DeepDarkState.IDLE;
        depositState = DepositState.IDLE;
        craftState = CraftState.IDLE;
        
        isWaitingToReconnect = false;
        lastServer = mc.getCurrentServerEntry();
        
        startTime = System.currentTimeMillis();
        antiAfkTickCounter = 0;

        safePosQueue.clear();
        wasPausedForPortalMaker = false;
        
        if (mc.player != null) {
            startX = mc.player.getX();
            startZ = mc.player.getZ();
        }
        
        updateBaritoneGoal();
        
        int targetItems = targetStacks.get() * 64;
        sendPing("Baromine activated. Targeting " + getTargetBlock().getName().getString() + " x" + targetItems + " (" + targetStacks.get() + " stacks).");
    }

    @Override
    public void onDeactivate() {
        isWaitingToReconnect = false;
        antiAfkTickCounter = 0;
        jumpTicks = 0;
        isHandlingLava = false;
        lavaSafetyDelay = 0;
        lavaMoveTicks = 0;
        isHandlingWater = false;
        waterSafetyDelay = 0;
        waterMoveTicks = 0;
        playerLogoutPending = false;
        isFinalDeposit = false;
        isAutoMending = false;
        hideoutState = HideoutState.IDLE;
        deepDarkState = DeepDarkState.IDLE;
        craftState = CraftState.IDLE;
        safePosQueue.clear();
        wasPausedForPortalMaker = false;
        if (mc.options != null) {
            mc.options.jumpKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
        }
        if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
            if (!Modules.get().isActive(PortalMaker.class)) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
            }
        }
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("set okIfWater false");
        sendPing("Baromine deactivated.");
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoReconnect.get() && lastServer != null) {
            isWaitingToReconnect = true;
            reconnectTime = System.currentTimeMillis() + (reconnectHours.get() * 3600000L);
            ChatUtils.sendMsg("Baromine", Text.literal("Disconnected. Waiting " + reconnectHours.get() + " hours before attempting to reconnect."));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (enableMaxRuntime.get() && System.currentTimeMillis() - startTime >= maxRuntimeHours.get() * 3600000L) {
            sendPing("Maximum runtime of " + maxRuntimeHours.get() + " hours reached. Stopping module.");
            toggle();
            return;
        }

        if (isWaitingToReconnect) {
            if (System.currentTimeMillis() >= reconnectTime) {
                isWaitingToReconnect = false;
                ChatUtils.sendMsg("Baromine", Text.literal("Reconnect delay finished. Attempting to reconnect..."));
                ConnectScreen.connect(new TitleScreen(), mc, ServerAddress.parse(lastServer.address), lastServer, false, null);
            }
            return; 
        }

        if (mc.player == null || mc.world == null) return;

        // Yield Baritone control to PortalMaker if it is active
        if (Modules.get().isActive(PortalMaker.class)) {
            if (!wasPausedForPortalMaker) {
                wasPausedForPortalMaker = true;
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
            }
            return;
        }

        if (wasPausedForPortalMaker) {
            wasPausedForPortalMaker = false;
            if (depositState == DepositState.IDLE && craftState == CraftState.IDLE && hideoutState == HideoutState.IDLE && deepDarkState == DeepDarkState.IDLE && !isAutoMending) {
                sendPing("PortalMaker disabled. Resuming Baromine operations.");
                updateBaritoneGoal();
            }
        }

        if (highlightContainerKey.get().isPressed() && craftState == CraftState.IDLE && depositState == DepositState.IDLE) {
            if (mc.crosshairTarget instanceof BlockHitResult hit) {
                BlockState state = mc.world.getBlockState(hit.getBlockPos());
                if (state.getBlock() instanceof ShulkerBoxBlock) {
                    craftContainerPos1 = hit.getBlockPos();
                    craftContainerPos2 = null;
                    sendPing("Highlighted Shulker Box for material pulling.");
                } else if (state.getBlock() == Blocks.CHEST) {
                    craftContainerPos1 = hit.getBlockPos();
                    craftContainerPos2 = null;
                    if (state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                        craftContainerPos2 = hit.getBlockPos().offset(ChestBlock.getFacing(state));
                    }
                    sendPing("Highlighted Chest for material pulling.");
                }
            }
        }

        if (hideoutState != HideoutState.IDLE) {
            handleHideoutState();
            return;
        }

        if (deepDarkState != DeepDarkState.IDLE) {
            handleDeepDarkRetreat();
            return;
        }

        float health = mc.player.getHealth();
        if (health <= criticalHealth.get()) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
            disconnectSafely("CRITICAL HEALTH");
            return;
        }

        if (isAutoMending) {
            handleAutoMend();
            return;
        }

        if (autoMend.get() && !autoMendFailed && depositState == DepositState.IDLE && craftState == CraftState.IDLE && hasToolsBelowDurability(minToolDurability.get())) {
            startAutoMend();
            return;
        }

        if (disconnectOnPlayer.get()) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player != mc.player) {
                    playerLogoutPending = true;
                    break;
                }
            }
        }

        if (playerLogoutPending) {
            if (depositState == DepositState.IDLE && craftState == CraftState.IDLE) {
                disconnectSafely("Player detected in render distance!");
                playerLogoutPending = false;
                return;
            }
        }

        if (avoidDeepDark.get() && depositState == DepositState.IDLE && craftState == CraftState.IDLE) {
            safePosTimer++;
            if (safePosTimer >= 40) { 
                safePosTimer = 0;
                Optional<RegistryKey<Biome>> biomeKey = mc.world.getBiome(mc.player.getBlockPos()).getKey();
                boolean inDeepDark = biomeKey.isPresent() && biomeKey.get().equals(BiomeKeys.DEEP_DARK);

                if (inDeepDark) {
                    triggerDeepDarkRetreat();
                    return;
                } else {
                    safePosQueue.addLast(mc.player.getBlockPos());
                    while (safePosQueue.size() > MAX_QUEUE_SIZE) {
                        safePosQueue.removeFirst();
                    }
                }
            }
        }

        if (craftState != CraftState.IDLE) {
            handleCraftState();
            return; 
        }

        if (autoCraft.get() == CraftMode.Ores && depositState == DepositState.IDLE && hasCraftableOre()) {
            craftState = CraftState.FINDING_TABLE;
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
            return;
        }

        if (autoCraft.get() == CraftMode.Blocks && depositState == DepositState.IDLE && hasCraftableBlock()) {
            craftState = CraftState.FINDING_TABLE;
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
            return;
        }

        if (depositState != DepositState.IDLE) {
            handleDepositState();
            return; 
        }

        if (depositMode.get() == DepositMode.Disabled && mc.player.getInventory().getEmptySlot() == -1) {
            sendPing("Inventory is full and Auto Deposit is disabled. Stopping module to prevent wasted mining.");
            stopBaritoneSafely("Inventory full");
            toggle();
            return;
        }

        int requiredItems = targetStacks.get() * 64;
        if (getTotalAvailableTargetItems() >= requiredItems) {
            if (depositMode.get() != DepositMode.Disabled && getCurrentTargetCount() > 0) {
                isFinalDeposit = true;
                sendPing("Target stacks reached! Initiating final deposit...");
                depositState = DepositState.PAUSING_BARITONE;
                depositDelay = 5;
                return;
            } else {
                sendPing("Target stacks reached! Total items: " + getTotalAvailableTargetItems());
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
                if (safeLogout.get() && isInNetherOrOverworld()) {
                    startHideout();
                } else {
                    toggle();
                }
                return;
            }
        }

        if (depositMode.get() != DepositMode.Disabled) {
            int requiredDepositItems = depositStacks.get() * 64;
            if (getCurrentTargetCount() >= requiredDepositItems) {
                depositState = DepositState.PAUSING_BARITONE;
                depositDelay = 5;
                return;
            }
        }

        int playerY = mc.player.getBlockPos().getY();
        if (playerY < minYLevel.get()) {
            stopBaritoneSafely("Went below minimum Y-level (" + minYLevel.get() + ")!");
            toggle();
            return;
        }
        if (playerY > maxYLevel.get()) {
            stopBaritoneSafely("Went above maximum Y-level (" + maxYLevel.get() + ")!");
            toggle();
            return;
        }

        if (radiusLimit.get()) {
            double dist = Math.sqrt(Math.pow(mc.player.getX() - startX, 2) + Math.pow(mc.player.getZ() - startZ, 2));
            if (dist > radiusBlocks.get()) {
                stopBaritoneSafely("Went outside allowed radius limit (" + radiusBlocks.get() + " blocks)!");
                toggle();
                return;
            }
        }

        if (health <= warningHealth.get()) {
            if (!hasHealthWarned) {
                sendPing("Warning: Health is low!");
                hasHealthWarned = true;
            }
        } else {
            hasHealthWarned = false; 
        }

        if (lavaAvoidance.get()) {
            if (isHandlingLava) {
                if (lavaMoveTicks > 0) {
                    lavaMoveTicks--;
                    mc.options.forwardKey.setPressed(true);
                    return;
                }
                mc.options.forwardKey.setPressed(false);

                if (lavaSafetyDelay > 0) {
                    lavaSafetyDelay--;
                    return;
                }
                
                if (isImmediateLavaDanger()) {
                    placeSafetyBlock(false);
                    lavaSafetyDelay = 5;
                    lavaSafetyCounter++;
                    
                    if (lavaSafetyCounter > 40) {
                        disconnectSafely("Lava safety failed!");
                        return;
                    }
                    return;
                } else {
                    isHandlingLava = false;
                    mc.options.jumpKey.setPressed(false);
                    mc.options.forwardKey.setPressed(false);
                    sendPing("Lava avoided. Resuming Baromine.");
                    updateBaritoneGoal();
                }
                return;
            } else if (isImmediateLavaDanger()) {
                isHandlingLava = true;
                lavaSafetyCounter = 0;
                stopBaritoneSafely("Lava detected! Activating safety protocols.");
                lavaSafetyDelay = 5;
                return;
            }
        }

        if (waterAvoidance.get()) {
            if (isHandlingWater) {
                if (waterMoveTicks > 0) {
                    waterMoveTicks--;
                    mc.options.forwardKey.setPressed(true);
                    return;
                }
                mc.options.forwardKey.setPressed(false);

                if (waterSafetyDelay > 0) {
                    waterSafetyDelay--;
                    return;
                }
                
                if (isImmediateWaterDanger()) {
                    placeSafetyBlock(true);
                    waterSafetyDelay = 5;
                    waterSafetyCounter++;
                    
                    if (waterSafetyCounter > 40) {
                        stopBaritoneSafely("Water safety failed!");
                        return;
                    }
                    return;
                } else {
                    isHandlingWater = false;
                    mc.options.jumpKey.setPressed(false);
                    mc.options.forwardKey.setPressed(false);
                    sendPing("Water avoided. Resuming Baromine.");
                    updateBaritoneGoal();
                }
                return;
            } else if (isImmediateWaterDanger()) {
                isHandlingWater = true;
                waterSafetyCounter = 0;
                stopBaritoneSafely("Water detected! Activating safety protocols.");
                waterSafetyDelay = 5;
                return;
            }
        }

        if (goldenHelmet.get() && mc.player.getInventory().getArmorStack(3).getItem() != Items.GOLDEN_HELMET) {
            stopBaritoneSafely("Golden Helmet is not equipped!");
            return;
        }

        if (pauseInCombat.get()) {
            boolean inDanger = false;
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof MobEntity mob && mob.isAttacking() && mc.player.distanceTo(mob) < 6.0) {
                    inDanger = true;
                    break;
                }
            }

            if (inDanger && !wasPausedForCombat) {
                stopBaritoneSafely("Hostile entity detected! Pausing.");
                equipSword(); 
                wasPausedForCombat = true;
                return;
            } else if (!inDanger && wasPausedForCombat) {
                sendPing("Combat clear. Resuming Baromine.");
                wasPausedForCombat = false;
                updateBaritoneGoal();
            }
        }

        if (antiAfk.get()) {
            antiAfkTickCounter++;
            if (antiAfkTickCounter >= 600) {
                antiAfkTickCounter = 0;
                if (isBaritoneIdle()) { 
                    jumpTicks = 10;
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            }

            if (jumpTicks > 0) {
                mc.options.jumpKey.setPressed(true);
                jumpTicks--;
            } else {
                mc.options.jumpKey.setPressed(false);
            }
        }

        handleExcessDrop();
    }

    private void handleExcessDrop() {
        if (dropExcessMode.get() == ExcessDropMode.Disabled) return;

        Item targetItem = null;
        if (dropExcessMode.get() == ExcessDropMode.Cobblestone) targetItem = Items.COBBLESTONE;
        else if (dropExcessMode.get() == ExcessDropMode.Netherrack) targetItem = Items.NETHERRACK;
        else if (dropExcessMode.get() == ExcessDropMode.CobbledDeepslate) targetItem = Items.COBBLED_DEEPSLATE;
        
        if (targetItem == null) return;
        
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == targetItem) {
                total += stack.getCount();
            }
        }
        
        if (total <= 64) return;
        
        // Drop from main inventory first (slots 9-35), then hotbar (0-8)
        int[] order = new int[36];
        for (int i = 9; i < 36; i++) order[i - 9] = i;
        for (int i = 0; i < 9; i++) order[27 + i] = i;
        
        for (int i = 0; i < 36; i++) {
            int slot = order[i];
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.getItem() == targetItem) {
                int count = stack.getCount();
                if (total - count >= 64) {
                    int containerSlot = slot < 9 ? slot + 36 : slot;
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerSlot, 1, SlotActionType.THROW, mc.player);
                    return; // Do one drop per tick to avoid packet spam
                }
            }
        }
    }

    // --- DEEP DARK RETREAT LOGIC ---
    private void triggerDeepDarkRetreat() {
        sendPing("Entered Deep Dark biome! Initiating tactical retreat.");
        deepDarkState = DeepDarkState.RETREATING;
        retreatDelay = 10; 
        
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
        equipSword(); 
        
        if (!safePosQueue.isEmpty()) {
            BlockPos target = safePosQueue.peekFirst();
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("goto " + target.getX() + " " + target.getY() + " " + target.getZ());
        } else {
            deepDarkState = DeepDarkState.ASCENDING;
            int x = mc.player.getBlockPos().getX();
            int z = mc.player.getBlockPos().getZ();
            sendPing("Retreated to safe spot. Ascending to Y=0.");
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("goto " + x + " 0 " + z);
        }
    }

    private void handleDeepDarkRetreat() {
        if (retreatDelay > 0) {
            retreatDelay--;
            return;
        }

        if (deepDarkState == DeepDarkState.RETREATING) {
            if (isBaritoneIdle()) { 
                safePosQueue.clear(); 
                deepDarkState = DeepDarkState.ASCENDING;
                retreatDelay = 10; 
                
                int x = mc.player.getBlockPos().getX();
                int z = mc.player.getBlockPos().getZ();
                sendPing("Retreated to safe spot. Ascending to Y=0.");
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("goto " + x + " 0 " + z);
            }
        } else if (deepDarkState == DeepDarkState.ASCENDING) {
            if (isBaritoneIdle()) { 
                deepDarkState = DeepDarkState.RUNNING_AWAY;
                retreatDelay = 10;
                sendPing("Reached Y=0. Relocating 200 blocks away.");
                startRunningAway();
            }
        } else if (deepDarkState == DeepDarkState.RUNNING_AWAY) {
            if (isBaritoneIdle()) { 
                sendPing("Evasion complete. Resuming mining operations.");
                deepDarkState = DeepDarkState.IDLE;
                safePosQueue.clear();
                updateBaritoneGoal();
            }
        }
    }

    private void startRunningAway() {
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        Direction dir = dirs[RANDOM.nextInt(dirs.length)];
        
        int dist = 200;
        int targetX = mc.player.getBlockPos().getX() + (dir.getOffsetX() * dist);
        int targetZ = mc.player.getBlockPos().getZ() + (dir.getOffsetZ() * dist);
        int targetY = 64; 
        
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("goto " + targetX + " " + targetY + " " + targetZ);
        
        deepDarkState = DeepDarkState.RUNNING_AWAY;
    }

    // --- AUTO CRAFT LOGIC ---
    private void handleCraftState() {
        if (craftDelay > 0) {
            craftDelay--;
            return;
        }

        switch (craftState) {
            case GATHERING_WOOD:
                if (hasLogs()) {
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
                    craftState = CraftState.FINDING_TABLE;
                    craftDelay = 5;
                } else {
                    if (isBaritoneIdle()) {
                        sendPing("Searching for wood to craft a table...");
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("mine minecraft:oak_log minecraft:spruce_log minecraft:birch_log minecraft:jungle_log minecraft:acacia_log minecraft:dark_oak_log minecraft:mangrove_log minecraft:cherry_log minecraft:crimson_stem minecraft:warped_stem");
                        craftDelay = 20;
                    }
                }
                break;

            case FINDING_TABLE:
                if (InvUtils.find(Items.CRAFTING_TABLE).found()) {
                    craftState = CraftState.PLACING_TABLE;
                    craftDelay = 5;
                    return;
                }

                int plankCount = 0;
                for (ItemStack stack : mc.player.getInventory().main) {
                    if (isPlank(stack.getItem())) plankCount += stack.getCount();
                }

                if (plankCount >= 4) {
                    if (craftStep == 0) {
                        Item plank = getAnyPlank();
                        if (plank == null) { abortCraft("No planks found!"); return; }
                        FindItemResult find = InvUtils.find(plank);
                        int invSlot = find.slot() < 9 ? find.slot() + 36 : find.slot();
                        mc.interactionManager.clickSlot(0, invSlot, 0, SlotActionType.PICKUP, mc.player);
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep <= 4) {
                        mc.interactionManager.clickSlot(0, craftStep, 1, SlotActionType.PICKUP, mc.player);
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep == 5) {
                        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                            int emptySlot = mc.player.getInventory().getEmptySlot();
                            if (emptySlot != -1) {
                                int slotId = emptySlot < 9 ? emptySlot + 36 : emptySlot;
                                mc.interactionManager.clickSlot(0, slotId, 0, SlotActionType.PICKUP, mc.player);
                            }
                        }
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep == 6) {
                        mc.interactionManager.clickSlot(0, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                        craftStep = 0;
                        craftDelay = 5;
                    }
                } else if (hasLogs()) {
                    Item log = getAnyLog();
                    if (craftStep == 0) {
                        FindItemResult find = InvUtils.find(log);
                        int invSlot = find.slot() < 9 ? find.slot() + 36 : find.slot();
                        mc.interactionManager.clickSlot(0, invSlot, 0, SlotActionType.PICKUP, mc.player);
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep == 1) {
                        mc.interactionManager.clickSlot(0, 1, 1, SlotActionType.PICKUP, mc.player);
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep == 2) {
                        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                            int emptySlot = mc.player.getInventory().getEmptySlot();
                            if (emptySlot != -1) {
                                int slotId = emptySlot < 9 ? emptySlot + 36 : emptySlot;
                                mc.interactionManager.clickSlot(0, slotId, 0, SlotActionType.PICKUP, mc.player);
                            }
                        }
                        craftStep++;
                        craftDelay = 2;
                    } else if (craftStep == 3) {
                        mc.interactionManager.clickSlot(0, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                        craftStep = 0;
                        craftDelay = 5;
                    }
                } else {
                    craftState = CraftState.GATHERING_WOOD;
                }
                break;

            case PLACING_TABLE:
                if (isWaterNearby(mc.player.getBlockPos(), 2) || isImmediateLavaDanger()) {
                    if (!isBaritoneIdle()) { return; } 
                    int dx = RANDOM.nextInt(16) + 10; 
                    int dz = RANDOM.nextInt(16) + 10;
                    if (RANDOM.nextBoolean()) dx *= -1;
                    if (RANDOM.nextBoolean()) dz *= -1;
                    int targetX = mc.player.getBlockPos().getX() + dx;
                    int targetZ = mc.player.getBlockPos().getZ() + dz;
                    int targetY = mc.player.getBlockPos().getY();
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("goto " + targetX + " " + targetY + " " + targetZ);
                    craftDelay = 10;
                    return;
                }

                FindItemResult tableItem = InvUtils.findInHotbar(Items.CRAFTING_TABLE);
                if (!tableItem.found()) { abortCraft("Lost Crafting Table!"); return; }
                
                craftTablePos = findAndPlace(tableItem);
                if (craftTablePos == null) {
                    abortCraft("Failed to place Crafting Table!");
                    return;
                }
                craftState = CraftState.OPENING_TABLE;
                craftDelay = 5;
                break;

            case OPENING_TABLE:
                if (mc.world.getBlockState(craftTablePos).getBlock() != Blocks.CRAFTING_TABLE) {
                    abortCraft("Failed to place Crafting Table.");
                    return;
                }
                BlockHitResult craftHit = new BlockHitResult(Vec3d.ofCenter(craftTablePos), Direction.UP, craftTablePos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, craftHit);
                mc.player.swingHand(Hand.MAIN_HAND);
                craftState = CraftState.CRAFTING;
                craftDelay = 5;
                break;

            case PULLING_MATERIALS:
                if (craftContainerPos1 == null) {
                    abortCraft("No container highlighted for materials!");
                    return;
                }

                if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) && !(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler)) {
                    BlockHitResult containerHit = new BlockHitResult(Vec3d.ofCenter(craftContainerPos1), Direction.UP, craftContainerPos1, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, containerHit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    craftDelay = 5;
                    return;
                }

                GenericContainerScreenHandler containerHandler = (GenericContainerScreenHandler) mc.player.currentScreenHandler;
                boolean needMaterials = false;
                Item rawItemNeeded = null;

                if (autoCraft.get() == CraftMode.Ores) {
                    CraftRecipe recipe = getCraftableOreRecipe();
                    if (recipe != null && countItem(recipe.raw) < recipe.count) {
                        needMaterials = true;
                        rawItemNeeded = recipe.raw;
                    }
                } else if (autoCraft.get() == CraftMode.Blocks) {
                    CraftRecipe recipe = getCraftableBlockRecipe();
                    if (recipe != null && countItem(recipe.raw) < recipe.count) {
                        needMaterials = true;
                        rawItemNeeded = recipe.raw;
                    }
                }

                if (!needMaterials) {
                    mc.player.closeHandledScreen();
                    craftState = CraftState.OPENING_TABLE;
                    craftDelay = 5;
                    return;
                }

                boolean pulled = false;
                for (int i = 0; i < 27; i++) {
                    ItemStack stack = containerHandler.getSlot(i).getStack();
                    if (stack.getItem() == rawItemNeeded) {
                        mc.interactionManager.clickSlot(containerHandler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                        pulled = true;
                        break;
                    }
                }

                if (!pulled) {
                    abortCraft("Ran out of " + rawItemNeeded.getName().getString() + " in the container!");
                    return;
                }
                
                craftDelay = 2;
                break;

            case CRAFTING:
                if (!(mc.player.currentScreenHandler instanceof CraftingScreenHandler)) {
                    abortCraft("Failed to open Crafting Table.");
                    return;
                }
                CraftingScreenHandler craftHandler = (CraftingScreenHandler) mc.player.currentScreenHandler;
                
                Item rawItem = null;
                int requiredAmount = 0;
                int[] gridSlots = null;

                if (autoCraft.get() == CraftMode.Ores) {
                    CraftRecipe recipe = getCraftableOreRecipe();
                    if (recipe == null) {
                        mc.player.closeHandledScreen();
                        craftState = CraftState.CLOSING_TABLE;
                        craftDelay = 5;
                        return;
                    }
                    rawItem = recipe.raw;
                    requiredAmount = recipe.count;
                    gridSlots = recipe.gridSlots;
                } else if (autoCraft.get() == CraftMode.Blocks) {
                    CraftRecipe recipe = getCraftableBlockRecipe();
                    if (recipe == null) {
                        mc.player.closeHandledScreen();
                        craftState = CraftState.CLOSING_TABLE;
                        craftDelay = 5;
                        return;
                    }
                    rawItem = recipe.raw;
                    requiredAmount = recipe.count;
                    gridSlots = recipe.gridSlots;
                }

                if (rawItem == null) {
                    mc.player.closeHandledScreen();
                    craftState = CraftState.CLOSING_TABLE;
                    craftDelay = 5;
                    return;
                }

                if (craftStep == 0) {
                    FindItemResult find = InvUtils.find(rawItem);
                    int slotId = find.slot() < 9 ? find.slot() + 37 : find.slot() + 10;
                    mc.interactionManager.clickSlot(craftHandler.syncId, slotId, 0, SlotActionType.PICKUP, mc.player);
                    craftStep++;
                    craftDelay = 2;
                } else if (craftStep <= requiredAmount) {
                    mc.interactionManager.clickSlot(craftHandler.syncId, gridSlots[craftStep - 1], 1, SlotActionType.PICKUP, mc.player);
                    craftStep++;
                    craftDelay = 2;
                } else if (craftStep == requiredAmount + 1) {
                    if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                        FindItemResult find = InvUtils.find(rawItem);
                        if (find.found()) {
                            int slotId = find.slot() < 9 ? find.slot() + 37 : find.slot() + 10;
                            mc.interactionManager.clickSlot(craftHandler.syncId, slotId, 0, SlotActionType.PICKUP, mc.player);
                        } else {
                            int emptySlot = mc.player.getInventory().getEmptySlot();
                            if (emptySlot != -1) {
                                int slotId = emptySlot < 9 ? emptySlot + 37 : emptySlot + 10;
                                mc.interactionManager.clickSlot(craftHandler.syncId, slotId, 0, SlotActionType.PICKUP, mc.player);
                            }
                        }
                    }
                    craftStep++;
                    craftDelay = 2;
                } else if (craftStep == requiredAmount + 2) {
                    mc.interactionManager.clickSlot(craftHandler.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                    craftStep = 0;
                    craftDelay = 5;
                }
                break;

            case CLOSING_TABLE:
                mc.player.closeHandledScreen();
                craftState = CraftState.BREAKING_TABLE;
                craftDelay = 5;
                break;

            case BREAKING_TABLE:
                if (!equipEnchantedPickaxe()) { 
                    abortCraft("No " + toolEnchant.get() + " Pickaxe found!"); 
                    return; 
                }
                if (mc.world.getBlockState(craftTablePos).getBlock() == Blocks.CRAFTING_TABLE) {
                    breakBlock(craftTablePos);
                    craftDelay = 1;
                    return;
                }
                pickupTimeout = 0;
                craftState = CraftState.PICKING_UP_TABLE;
                craftDelay = 5; 
                break;

            case PICKING_UP_TABLE:
                if (InvUtils.find(Items.CRAFTING_TABLE).found()) {
                    mc.options.forwardKey.setPressed(false);
                    craftState = CraftState.RESUMING;
                    craftDelay = 5;
                    return;
                }

                ItemEntity targetTable = null;
                double closestTableDist = 6.0;
                for (Entity entity : mc.world.getEntities()) {
                    if (entity instanceof ItemEntity itemEntity && itemEntity.getStack().getItem() == Items.CRAFTING_TABLE) {
                        double dist = mc.player.distanceTo(entity);
                        if (dist < closestTableDist) {
                            closestTableDist = dist;
                            targetTable = itemEntity;
                        }
                    }
                }

                if (targetTable != null) {
                    Vec3d itemPos = targetTable.getPos();
                    double diffX = itemPos.x - mc.player.getX();
                    double diffZ = itemPos.z - mc.player.getZ();
                    float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90;
                    mc.player.setYaw(yaw);
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, mc.player.getPitch(), mc.player.isOnGround(), false));
                    mc.options.forwardKey.setPressed(true);
                    craftDelay = 1;
                } else {
                    pickupTimeout++;
                    if (pickupTimeout > 60) {
                        mc.options.forwardKey.setPressed(false);
                        abortCraft("Lost Crafting Table after mining it!");
                    } else {
                        craftDelay = 1;
                    }
                }
                break;

            case RESUMING:
                ensureToolsInHotbar();
                craftState = CraftState.IDLE;
                craftStep = 0;
                sendPing("Successfully crafted items. Resuming Baromine.");
                updateBaritoneGoal();
                break;
        }
    }

    private boolean isPlank(Item item) {
        return item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS || item == Items.BIRCH_PLANKS || 
               item == Items.JUNGLE_PLANKS || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS || 
               item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS || 
               item == Items.CRIMSON_PLANKS || item == Items.WARPED_PLANKS;
    }

    private Item getAnyPlank() {
        for (ItemStack stack : mc.player.getInventory().main) {
            if (isPlank(stack.getItem())) return stack.getItem();
        }
        return null;
    }

    private boolean isLog(Item item) {
        return item == Items.OAK_LOG || item == Items.SPRUCE_LOG || item == Items.BIRCH_LOG || 
               item == Items.JUNGLE_LOG || item == Items.ACACIA_LOG || item == Items.DARK_OAK_LOG || 
               item == Items.MANGROVE_LOG || item == Items.CHERRY_LOG || 
               item == Items.CRIMSON_STEM || item == Items.WARPED_STEM;
    }

    private Item getAnyLog() {
        for (ItemStack stack : mc.player.getInventory().main) {
            if (isLog(stack.getItem())) return stack.getItem();
        }
        return null;
    }

    private boolean hasLogs() {
        for (ItemStack stack : mc.player.getInventory().main) {
            if (isLog(stack.getItem())) return true;
        }
        return false;
    }

    private int countItem(Item item) {
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private record CraftRecipe(Item raw, int count, int[] gridSlots) {}

    private CraftRecipe getOreRecipe(Block block) {
        int[] grid9 = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[] grid4 = new int[]{1, 2, 4, 5};
        if (block == Blocks.IRON_BLOCK) return new CraftRecipe(Items.RAW_IRON, 9, grid9);
        if (block == Blocks.GOLD_BLOCK) return new CraftRecipe(Items.RAW_GOLD, 9, grid9);
        if (block == Blocks.COPPER_BLOCK) return new CraftRecipe(Items.RAW_COPPER, 9, grid9);
        if (block == Blocks.DIAMOND_BLOCK) return new CraftRecipe(Items.DIAMOND, 9, grid9);
        if (block == Blocks.EMERALD_BLOCK) return new CraftRecipe(Items.EMERALD, 9, grid9);
        if (block == Blocks.COAL_BLOCK) return new CraftRecipe(Items.COAL, 9, grid9);
        if (block == Blocks.REDSTONE_BLOCK) return new CraftRecipe(Items.REDSTONE, 9, grid9);
        if (block == Blocks.LAPIS_BLOCK) return new CraftRecipe(Items.LAPIS_LAZULI, 9, grid9);
        if (block == Blocks.QUARTZ_BLOCK) return new CraftRecipe(Items.QUARTZ, 4, grid4);
        return null;
    }

    private CraftRecipe getBlockRecipe(Block block) {
        int[] grid9 = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[] grid4 = new int[]{1, 2, 4, 5};
        int[] grid3Bottom = new int[]{7, 8, 9};
        int[] grid6Top = new int[]{1, 2, 3, 4, 5, 6}; // For walls, iron bars, glass panes
        int[] grid6Stairs = new int[]{1, 4, 5, 7, 8, 9}; // For stairs

        // Stone & Deepslate Variants
        if (block == Blocks.STONE_BRICKS) return new CraftRecipe(Items.STONE, 4, grid4);
        if (block == Blocks.POLISHED_GRANITE) return new CraftRecipe(Items.GRANITE, 4, grid4);
        if (block == Blocks.POLISHED_DIORITE) return new CraftRecipe(Items.DIORITE, 4, grid4);
        if (block == Blocks.POLISHED_ANDESITE) return new CraftRecipe(Items.ANDESITE, 4, grid4);
        if (block == Blocks.POLISHED_DEEPSLATE) return new CraftRecipe(Items.DEEPSLATE, 4, grid4);
        if (block == Blocks.DEEPSLATE_BRICKS) return new CraftRecipe(Items.POLISHED_DEEPSLATE, 4, grid4);
        if (block == Blocks.DEEPSLATE_TILES) return new CraftRecipe(Items.DEEPSLATE_BRICKS, 4, grid4);
        if (block == Blocks.END_STONE_BRICKS) return new CraftRecipe(Items.END_STONE, 4, grid4);
        if (block == Blocks.MUD_BRICKS) return new CraftRecipe(Items.PACKED_MUD, 4, grid4);

        // Nether Variants
        if (block == Blocks.NETHER_BRICKS) return new CraftRecipe(Items.NETHER_BRICK, 4, grid4);
        if (block == Blocks.QUARTZ_BRICKS) return new CraftRecipe(Items.QUARTZ_BLOCK, 4, grid4);
        if (block == Blocks.POLISHED_BASALT) return new CraftRecipe(Items.BASALT, 4, grid4);
        if (block == Blocks.POLISHED_BLACKSTONE) return new CraftRecipe(Items.BLACKSTONE, 4, grid4);
        if (block == Blocks.POLISHED_BLACKSTONE_BRICKS) return new CraftRecipe(Items.BLACKSTONE, 4, grid4);

        // Sands & Clays
        if (block == Blocks.SANDSTONE) return new CraftRecipe(Items.SAND, 4, grid4);
        if (block == Blocks.RED_SANDSTONE) return new CraftRecipe(Items.RED_SAND, 4, grid4);
        if (block == Blocks.CUT_SANDSTONE) return new CraftRecipe(Items.SANDSTONE, 4, grid4);
        if (block == Blocks.CUT_RED_SANDSTONE) return new CraftRecipe(Items.RED_SANDSTONE, 4, grid4);
        if (block == Blocks.BRICKS) return new CraftRecipe(Items.CLAY_BALL, 4, grid4);
        
        // Nature & Misc
        if (block == Blocks.SNOW_BLOCK) return new CraftRecipe(Items.SNOWBALL, 4, grid4);
        if (block == Blocks.GLOWSTONE) return new CraftRecipe(Items.GLOWSTONE_DUST, 4, grid4);
        if (block == Blocks.MELON) return new CraftRecipe(Items.MELON_SLICE, 9, grid9);
        if (block == Blocks.DRIED_KELP_BLOCK) return new CraftRecipe(Items.DRIED_KELP, 9, grid9);
        if (block == Blocks.BAMBOO_BLOCK) return new CraftRecipe(Items.BAMBOO, 9, grid9);
        if (block == Blocks.HAY_BLOCK) return new CraftRecipe(Items.WHEAT, 9, grid9);
        if (block == Blocks.BONE_BLOCK) return new CraftRecipe(Items.BONE_MEAL, 9, grid9);
        if (block == Blocks.PACKED_ICE) return new CraftRecipe(Items.ICE, 9, grid9);
        if (block == Blocks.BLUE_ICE) return new CraftRecipe(Items.PACKED_ICE, 9, grid9);

        // Bars & Panes
        if (block == Blocks.IRON_BARS) return new CraftRecipe(Items.IRON_INGOT, 6, grid6Top);
        if (block == Blocks.GLASS_PANE) return new CraftRecipe(Items.GLASS, 6, grid6Top);

        // Slabs
        if (block == Blocks.STONE_SLAB) return new CraftRecipe(Items.STONE, 3, grid3Bottom);
        if (block == Blocks.COBBLESTONE_SLAB) return new CraftRecipe(Items.COBBLESTONE, 3, grid3Bottom);
        if (block == Blocks.STONE_BRICK_SLAB) return new CraftRecipe(Items.STONE_BRICKS, 3, grid3Bottom);
        if (block == Blocks.SANDSTONE_SLAB) return new CraftRecipe(Items.SANDSTONE, 3, grid3Bottom);
        if (block == Blocks.RED_SANDSTONE_SLAB) return new CraftRecipe(Items.RED_SANDSTONE, 3, grid3Bottom);
        if (block == Blocks.NETHER_BRICK_SLAB) return new CraftRecipe(Items.NETHER_BRICKS, 3, grid3Bottom);
        if (block == Blocks.QUARTZ_SLAB) return new CraftRecipe(Items.QUARTZ_BLOCK, 3, grid3Bottom);
        if (block == Blocks.END_STONE_BRICK_SLAB) return new CraftRecipe(Items.END_STONE_BRICKS, 3, grid3Bottom);
        if (block == Blocks.POLISHED_GRANITE_SLAB) return new CraftRecipe(Items.POLISHED_GRANITE, 3, grid3Bottom);
        if (block == Blocks.POLISHED_DIORITE_SLAB) return new CraftRecipe(Items.POLISHED_DIORITE, 3, grid3Bottom);
        if (block == Blocks.POLISHED_ANDESITE_SLAB) return new CraftRecipe(Items.POLISHED_ANDESITE, 3, grid3Bottom);
        if (block == Blocks.POLISHED_DEEPSLATE_SLAB) return new CraftRecipe(Items.POLISHED_DEEPSLATE, 3, grid3Bottom);
        if (block == Blocks.DEEPSLATE_BRICK_SLAB) return new CraftRecipe(Items.DEEPSLATE_BRICKS, 3, grid3Bottom);
        if (block == Blocks.DEEPSLATE_TILE_SLAB) return new CraftRecipe(Items.DEEPSLATE_TILES, 3, grid3Bottom);
        if (block == Blocks.COBBLED_DEEPSLATE_SLAB) return new CraftRecipe(Items.COBBLED_DEEPSLATE, 3, grid3Bottom);
        if (block == Blocks.BLACKSTONE_SLAB) return new CraftRecipe(Items.BLACKSTONE, 3, grid3Bottom);
        if (block == Blocks.POLISHED_BLACKSTONE_SLAB) return new CraftRecipe(Items.POLISHED_BLACKSTONE, 3, grid3Bottom);
        if (block == Blocks.POLISHED_BLACKSTONE_BRICK_SLAB) return new CraftRecipe(Items.POLISHED_BLACKSTONE_BRICKS, 3, grid3Bottom);
        if (block == Blocks.MUD_BRICK_SLAB) return new CraftRecipe(Items.MUD_BRICKS, 3, grid3Bottom);
        if (block == Blocks.OAK_SLAB) return new CraftRecipe(Items.OAK_PLANKS, 3, grid3Bottom);
        if (block == Blocks.SPRUCE_SLAB) return new CraftRecipe(Items.SPRUCE_PLANKS, 3, grid3Bottom);
        if (block == Blocks.BIRCH_SLAB) return new CraftRecipe(Items.BIRCH_PLANKS, 3, grid3Bottom);
        if (block == Blocks.JUNGLE_SLAB) return new CraftRecipe(Items.JUNGLE_PLANKS, 3, grid3Bottom);
        if (block == Blocks.ACACIA_SLAB) return new CraftRecipe(Items.ACACIA_PLANKS, 3, grid3Bottom);
        if (block == Blocks.DARK_OAK_SLAB) return new CraftRecipe(Items.DARK_OAK_PLANKS, 3, grid3Bottom);
        if (block == Blocks.MANGROVE_SLAB) return new CraftRecipe(Items.MANGROVE_PLANKS, 3, grid3Bottom);
        if (block == Blocks.CHERRY_SLAB) return new CraftRecipe(Items.CHERRY_PLANKS, 3, grid3Bottom);

        // Stairs
        if (block == Blocks.STONE_STAIRS) return new CraftRecipe(Items.STONE, 6, grid6Stairs);
        if (block == Blocks.COBBLESTONE_STAIRS) return new CraftRecipe(Items.COBBLESTONE, 6, grid6Stairs);
        if (block == Blocks.STONE_BRICK_STAIRS) return new CraftRecipe(Items.STONE_BRICKS, 6, grid6Stairs);
        if (block == Blocks.SANDSTONE_STAIRS) return new CraftRecipe(Items.SANDSTONE, 6, grid6Stairs);
        if (block == Blocks.RED_SANDSTONE_STAIRS) return new CraftRecipe(Items.RED_SANDSTONE, 6, grid6Stairs);
        if (block == Blocks.NETHER_BRICK_STAIRS) return new CraftRecipe(Items.NETHER_BRICKS, 6, grid6Stairs);
        if (block == Blocks.QUARTZ_STAIRS) return new CraftRecipe(Items.QUARTZ_BLOCK, 6, grid6Stairs);
        if (block == Blocks.END_STONE_BRICK_STAIRS) return new CraftRecipe(Items.END_STONE_BRICKS, 6, grid6Stairs);
        if (block == Blocks.GRANITE_STAIRS) return new CraftRecipe(Items.GRANITE, 6, grid6Stairs);
        if (block == Blocks.DIORITE_STAIRS) return new CraftRecipe(Items.DIORITE, 6, grid6Stairs);
        if (block == Blocks.ANDESITE_STAIRS) return new CraftRecipe(Items.ANDESITE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_GRANITE_STAIRS) return new CraftRecipe(Items.POLISHED_GRANITE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_DIORITE_STAIRS) return new CraftRecipe(Items.POLISHED_DIORITE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_ANDESITE_STAIRS) return new CraftRecipe(Items.POLISHED_ANDESITE, 6, grid6Stairs);
        if (block == Blocks.DEEPSLATE_BRICK_STAIRS) return new CraftRecipe(Items.DEEPSLATE_BRICKS, 6, grid6Stairs);
        if (block == Blocks.DEEPSLATE_TILE_STAIRS) return new CraftRecipe(Items.DEEPSLATE_TILES, 6, grid6Stairs);
        if (block == Blocks.COBBLED_DEEPSLATE_STAIRS) return new CraftRecipe(Items.COBBLED_DEEPSLATE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_DEEPSLATE_STAIRS) return new CraftRecipe(Items.POLISHED_DEEPSLATE, 6, grid6Stairs);
        if (block == Blocks.BLACKSTONE_STAIRS) return new CraftRecipe(Items.BLACKSTONE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_BLACKSTONE_STAIRS) return new CraftRecipe(Items.POLISHED_BLACKSTONE, 6, grid6Stairs);
        if (block == Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS) return new CraftRecipe(Items.POLISHED_BLACKSTONE_BRICKS, 6, grid6Stairs);
        if (block == Blocks.MUD_BRICK_STAIRS) return new CraftRecipe(Items.MUD_BRICKS, 6, grid6Stairs);
        if (block == Blocks.OAK_STAIRS) return new CraftRecipe(Items.OAK_PLANKS, 6, grid6Stairs);
        if (block == Blocks.SPRUCE_STAIRS) return new CraftRecipe(Items.SPRUCE_PLANKS, 6, grid6Stairs);
        if (block == Blocks.BIRCH_STAIRS) return new CraftRecipe(Items.BIRCH_PLANKS, 6, grid6Stairs);
        if (block == Blocks.JUNGLE_STAIRS) return new CraftRecipe(Items.JUNGLE_PLANKS, 6, grid6Stairs);
        if (block == Blocks.ACACIA_STAIRS) return new CraftRecipe(Items.ACACIA_PLANKS, 6, grid6Stairs);
        if (block == Blocks.DARK_OAK_STAIRS) return new CraftRecipe(Items.DARK_OAK_PLANKS, 6, grid6Stairs);
        if (block == Blocks.MANGROVE_STAIRS) return new CraftRecipe(Items.MANGROVE_PLANKS, 6, grid6Stairs);
        if (block == Blocks.CHERRY_STAIRS) return new CraftRecipe(Items.CHERRY_PLANKS, 6, grid6Stairs);

        // Walls
        if (block == Blocks.COBBLESTONE_WALL) return new CraftRecipe(Items.COBBLESTONE, 6, grid6Top);
        if (block == Blocks.STONE_BRICK_WALL) return new CraftRecipe(Items.STONE_BRICKS, 6, grid6Top);
        if (block == Blocks.SANDSTONE_WALL) return new CraftRecipe(Items.SANDSTONE, 6, grid6Top);
        if (block == Blocks.RED_SANDSTONE_WALL) return new CraftRecipe(Items.RED_SANDSTONE, 6, grid6Top);
        if (block == Blocks.NETHER_BRICK_WALL) return new CraftRecipe(Items.NETHER_BRICKS, 6, grid6Top);
        if (block == Blocks.END_STONE_BRICK_WALL) return new CraftRecipe(Items.END_STONE_BRICKS, 6, grid6Top);
        if (block == Blocks.GRANITE_WALL) return new CraftRecipe(Items.GRANITE, 6, grid6Top);
        if (block == Blocks.DIORITE_WALL) return new CraftRecipe(Items.DIORITE, 6, grid6Top);
        if (block == Blocks.ANDESITE_WALL) return new CraftRecipe(Items.ANDESITE, 6, grid6Top);
        if (block == Blocks.COBBLED_DEEPSLATE_WALL) return new CraftRecipe(Items.COBBLED_DEEPSLATE, 6, grid6Top);
        if (block == Blocks.POLISHED_DEEPSLATE_WALL) return new CraftRecipe(Items.POLISHED_DEEPSLATE, 6, grid6Top);
        if (block == Blocks.DEEPSLATE_BRICK_WALL) return new CraftRecipe(Items.DEEPSLATE_BRICKS, 6, grid6Top);
        if (block == Blocks.DEEPSLATE_TILE_WALL) return new CraftRecipe(Items.DEEPSLATE_TILES, 6, grid6Top);
        if (block == Blocks.BLACKSTONE_WALL) return new CraftRecipe(Items.BLACKSTONE, 6, grid6Top);
        if (block == Blocks.POLISHED_BLACKSTONE_WALL) return new CraftRecipe(Items.POLISHED_BLACKSTONE, 6, grid6Top);
        if (block == Blocks.POLISHED_BLACKSTONE_BRICK_WALL) return new CraftRecipe(Items.POLISHED_BLACKSTONE_BRICKS, 6, grid6Top);
        if (block == Blocks.MUD_BRICK_WALL) return new CraftRecipe(Items.MUD_BRICKS, 6, grid6Top);

        return null;
    }

    private boolean hasCraftableOre() {
        return getCraftableOreRecipe() != null;
    }

    private CraftRecipe getCraftableOreRecipe() {
        for (Block block : craftOreList.get()) {
            CraftRecipe recipe = getOreRecipe(block);
            if (recipe != null && countItem(recipe.raw) >= recipe.count) {
                return recipe;
            }
        }
        return null;
    }

    private boolean hasCraftableBlock() {
        return getCraftableBlockRecipe() != null;
    }

    private CraftRecipe getCraftableBlockRecipe() {
        for (Block block : craftBlockList.get()) {
            CraftRecipe recipe = getBlockRecipe(block);
            if (recipe != null && countItem(recipe.raw) >= recipe.count) {
                return recipe;
            }
        }
        return null;
    }

    private void abortCraft(String reason) {
        sendPing("CRAFT ABORTED: " + reason);
        if (mc.player.currentScreenHandler != null) {
            mc.player.closeHandledScreen();
        }
        craftState = CraftState.IDLE;
        craftStep = 0;
        craftDelay = 0;
        updateBaritoneGoal();
    }

    // --- SAFE HIDEOUT LOGIC ---
    private boolean isInNetherOrOverworld() {
        if (mc.world == null) return false;
        return mc.world.getRegistryKey().equals(World.OVERWORLD) || mc.world.getRegistryKey().equals(World.NETHER);
    }

    private void startHideout() {
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
        hideoutState = HideoutState.DIGGING;
        hideoutDir = null;
        hideoutPos = null;
        hideoutDelay = 0;
        sendPing("Target reached! Finding a safe wall to hide in before logging out.");
    }

    private void handleHideoutState() {
        if (hideoutDelay > 0) {
            hideoutDelay--;
            return;
        }

        switch (hideoutState) {
            case DIGGING:
                if (hideoutDir == null) {
                    Direction[] dirs = {mc.player.getHorizontalFacing(), Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
                    for (Direction d : dirs) {
                        BlockPos feet = mc.player.getBlockPos().offset(d);
                        BlockPos head = feet.up();
                        if (!mc.world.isAir(feet) && !mc.world.isAir(head) && mc.world.getBlockState(feet).getBlock().getHardness() > 0 && mc.world.getBlockState(head).getBlock().getHardness() > 0) {
                            hideoutDir = d;
                            hideoutPos = feet;
                            break;
                        }
                    }
                    if (hideoutDir == null) {
                        sendPing("Could not find a valid wall to hide in. Logging out anyway.");
                        hideoutState = HideoutState.DONE;
                        hideoutDelay = 10;
                        return;
                    }
                }

                boolean feetBroken = mc.world.isAir(hideoutPos);
                boolean headBroken = mc.world.isAir(hideoutPos.up());

                if (!feetBroken) {
                    breakBlock(hideoutPos);
                    hideoutDelay = 2;
                } else if (!headBroken) {
                    breakBlock(hideoutPos.up());
                    hideoutDelay = 2;
                } else {
                    hideoutState = HideoutState.ENTERING;
                    hideoutDelay = 2;
                }
                break;

            case ENTERING:
                if (mc.player.getBlockPos().equals(hideoutPos)) {
                    mc.options.forwardKey.setPressed(false);
                    hideoutState = HideoutState.SEALING;
                    hideoutDelay = 2;
                } else {
                    mc.options.forwardKey.setPressed(true);
                    hideoutDelay = 1; 
                }
                break;

            case SEALING:
                mc.options.forwardKey.setPressed(false);
                
                BlockPos sealPos = mc.player.getBlockPos().offset(hideoutDir.getOpposite());
                FindItemResult blockItem = InvUtils.findInHotbar(item -> 
                    item.getItem() instanceof net.minecraft.item.BlockItem && 
                    !SHULKER_PREDICATE.test(item) && 
                    item.getItem() != Items.ENDER_CHEST
                );
                
                if (blockItem.found() && mc.world.isAir(sealPos)) {
                    lookAtBlock(sealPos);
                    BlockUtils.place(sealPos, blockItem, 0);
                    sendPing("Sealed inside wall. Safe logout complete.");
                } else {
                    sendPing("Warning: No blocks to seal the wall. Logging out in open air.");
                }

                hideoutState = HideoutState.DONE;
                hideoutDelay = 10;
                break;

            case DONE:
                toggle();
                hideoutState = HideoutState.IDLE;
                break;
        }
    }

    // --- NATIVE AUTO-MEND LOGIC ---
    private void startAutoMend() {
        isAutoMending = true;
        savedTargetBlock = getTargetBlock();
        
        sendPing("Tool durability low! Pausing mining to auto-mend tools.");
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
        updateMendingTools();

        if (!isAutoMending) return;
        
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("set minYLevelWhileMining 0");
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("set maxYLevelWhileMining 320");
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("mine " + Registries.BLOCK.getId(mendOreBlock.get()).toString());
    }

    private void handleAutoMend() {
        if (mendToolSwapDelay > 0) {
            mendToolSwapDelay--;
        } else {
            updateMendingTools();
            mendToolSwapDelay = 20; 
        }

        if (!hasToolsBelowDurability(maxMendDurability.get())) {
            stopAutoMend();
        }
    }

    private void stopAutoMend() {
        isAutoMending = false;
        sendPing("Tools repaired. Resuming mining operations.");
        
        if (getToolType(mc.player.getOffHandStack()) != null) {
            int emptySlot = mc.player.getInventory().getEmptySlot();
            if (emptySlot != -1) InvUtils.move().fromOffhand().to(emptySlot);
        }

        updateBaritoneGoal(savedTargetBlock);
    }

    private void updateMendingTools() {
        int mainHandSlot = mc.player.getInventory().selectedSlot;
        ItemStack mainHand = mc.player.getMainHandStack();
        
        boolean needsSwap = false;
        if (getToolType(mainHand) == null) {
            needsSwap = true;
        } else if (hasSilkTouch(mainHand)) {
            needsSwap = true; 
        } else if (getDurabilityPercent(mainHand) >= maxMendDurability.get()) {
            if (findMostDamagedNonSilkTool(mainHandSlot) != -1) {
                needsSwap = true;
            }
        }

        if (needsSwap) {
            int bestSlot = findMostDamagedNonSilkTool(mainHandSlot);
            if (bestSlot == -1) {
                sendPing("Auto-Mend failed: No non-Silk Touch tool found to mine XP!");
                autoMendFailed = true;
                isAutoMending = false;
                updateBaritoneGoal(savedTargetBlock);
                return;
            }
            
            if (bestSlot < 9) {
                mc.player.getInventory().selectedSlot = bestSlot;
            } else {
                InvUtils.move().from(bestSlot).toHotbar(mc.player.getInventory().selectedSlot);
            }
            return;
        }

        ItemStack offHand = mc.player.getOffHandStack();
        boolean needsOffhandSwap = false;
        if (getToolType(offHand) == null) {
            needsOffhandSwap = true;
        } else if (hasSilkTouch(offHand)) {
            needsOffhandSwap = true;
        } else if (getDurabilityPercent(offHand) >= maxMendDurability.get()) {
            if (findMostDamagedNonSilkTool(mainHandSlot) != -1) {
                needsOffhandSwap = true;
            }
        }

        if (needsOffhandSwap) {
            int bestSlot = findMostDamagedNonSilkTool(mainHandSlot);
            if (bestSlot != -1) {
                if (!offHand.isEmpty()) {
                    int emptySlot = mc.player.getInventory().getEmptySlot();
                    if (emptySlot != -1) InvUtils.move().fromOffhand().to(emptySlot);
                }
                InvUtils.move().from(bestSlot).toOffhand();
            }
        }
    }

    private int findMostDamagedNonSilkTool(int... excludeSlots) {
        Set<Integer> excluded = new HashSet<>();
        for (int s : excludeSlots) excluded.add(s);
        
        int worstSlot = -1;
        double worstDurability = 101.0;
        
        for (int i = 0; i < 36; i++) {
            if (excluded.contains(i)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (getToolType(stack) != null && !hasSilkTouch(stack)) {
                double dur = getDurabilityPercent(stack);
                if (dur < maxMendDurability.get() && dur < worstDurability) {
                    worstDurability = dur;
                    worstSlot = i;
                }
            }
        }
        
        if (worstSlot == -1) {
            for (int i = 0; i < 36; i++) {
                if (excluded.contains(i)) continue;
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (getToolType(stack) != null && !hasSilkTouch(stack)) {
                    return i;
                }
            }
        }
        
        return worstSlot;
    }

    private boolean hasSilkTouch(ItemStack stack) {
        if (mc.world == null || stack.isEmpty()) return false;
        RegistryEntry<Enchantment> silkTouch = mc.world.getRegistryManager()
            .getOrThrow(RegistryKeys.ENCHANTMENT)
            .getOrThrow(Enchantments.SILK_TOUCH);
        return EnchantmentHelper.getLevel(silkTouch, stack) > 0;
    }

    private double getDurabilityPercent(ItemStack stack) {
        if (stack.isEmpty() || stack.getMaxDamage() == 0) return 100.0;
        return (double)(stack.getMaxDamage() - stack.getDamage()) / stack.getMaxDamage() * 100.0;
    }

    // --- DEPOSIT STATE MACHINE LOGIC ---
    private void handleDepositState() {
        if (depositDelay > 0) {
            depositDelay--;
            return;
        }

        Set<Item> validItems = getValidTargetItems();

        switch (depositState) {
            case PAUSING_BARITONE:
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
                if (!isBaritoneIdle()) { 
                    depositDelay = 5; 
                    return;
                }
                
                sendPing("Inventory threshold reached. Pausing Baritone to deposit items.");
                spaceClearingStarted = false;
                spaceClearAttempts = 0;
                
                depositState = DepositState.CLEARING_SPACE;
                depositDelay = 5;
                break;

            case CLEARING_SPACE:
                if (!spaceClearingStarted) {
                    spaceClearingStarted = true;
                    sendPing("Clearing 2x2 area with Baritone...");
                    spaceClearAttempts = 0;
                    spaceClearMinWait = 40; // 2 seconds minimum wait

                    BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("sel pos1 ~-1 ~ ~-1");
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("sel pos2 ~1 ~1 ~1");
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("sel cleararea");
                }

                spaceClearMinWait--;

                if (spaceClearMinWait <= 0 && isBaritoneIdle()) {
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("sel clear");
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
                    spaceClearingStarted = false;
                    if (depositMode.get() == DepositMode.EnderChest) {
                        depositState = DepositState.PLACING_ECHEST;
                    } else {
                        depositState = DepositState.PLACING_SHULKER;
                    }
                    depositDelay = 5;
                }
                break;

            case PLACING_ECHEST:
                FindItemResult echestItemFind = InvUtils.find(Items.ENDER_CHEST);
                if (!echestItemFind.found()) { 
                    sendPing("No Ender Chest in inventory! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return; 
                }
                
                if (echestItemFind.slot() >= 9) {
                    int targetSlot = swapSlot.get();
                    InvUtils.move().from(echestItemFind.slot()).toHotbar(targetSlot);
                    mc.player.getInventory().selectedSlot = targetSlot;
                } else {
                    mc.player.getInventory().selectedSlot = echestItemFind.slot();
                }
                
                echestPos = findAndPlace(InvUtils.findInHotbar(Items.ENDER_CHEST));
                if (echestPos == null) {
                    spaceClearAttempts++;
                    if (spaceClearAttempts >= 3) {
                        sendPing("Failed to place Ender Chest after multiple attempts! Resuming mining.");
                        spaceClearAttempts = 0;
                        depositState = DepositState.RESUMING;
                        return;
                    }
                    sendPing("Failed to place Ender Chest! Retrying...");
                    depositState = DepositState.CLEARING_SPACE;
                    depositDelay = 10;
                    return;
                }
                
                depositState = DepositState.OPENING_ECHEST;
                depositDelay = 5;
                break;

            case OPENING_ECHEST:
                if (mc.world.getBlockState(echestPos).getBlock() != Blocks.ENDER_CHEST) {
                    sendPing("Failed to place/find Ender Chest. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                BlockHitResult echestHit = new BlockHitResult(Vec3d.ofCenter(echestPos), Direction.UP, echestPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, echestHit);
                mc.player.swingHand(Hand.MAIN_HAND);
                depositState = DepositState.EXTRACTING_SHULKER;
                depositDelay = 5;
                break;

            case EXTRACTING_SHULKER:
                if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler)) {
                    sendPing("Failed to open Ender Chest. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                GenericContainerScreenHandler echestHandler = (GenericContainerScreenHandler) mc.player.currentScreenHandler;
                
                boolean hasShulkerInInv = false;
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (SHULKER_PREDICATE.test(stack) && isValidShulkerForDeposit(stack, validItems)) {
                        hasShulkerInInv = true;
                        break;
                    }
                }
                
                if (hasShulkerInInv) {
                    depositState = DepositState.CLOSING_ECHEST;
                    depositDelay = 2;
                    return;
                }
                
                int shulkerSlot = -1;
                for (int i = 0; i < 27; i++) {
                    ItemStack stack = echestHandler.getSlot(i).getStack();
                    if (SHULKER_PREDICATE.test(stack) && isValidShulkerForDeposit(stack, validItems)) {
                        shulkerSlot = i;
                        break;
                    }
                }

                if (shulkerSlot == -1) {
                    mc.player.closeHandledScreen();
                    sendPing("All Shulker Boxes are completely full! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }

                mc.interactionManager.clickSlot(echestHandler.syncId, shulkerSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
                depositState = DepositState.CLOSING_ECHEST;
                depositDelay = 2;
                break;

            case CLOSING_ECHEST:
                mc.player.closeHandledScreen();
                depositState = DepositState.PLACING_SHULKER;
                depositDelay = 5;
                break;

            case PLACING_SHULKER:
                shulkerRecoveryAttempted = false;
                
                int shulkerInvSlot = -1;
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (SHULKER_PREDICATE.test(stack) && isValidShulkerForDeposit(stack, validItems)) {
                        shulkerInvSlot = i;
                        break;
                    }
                }

                if (shulkerInvSlot == -1) {
                    sendPing("No valid Shulker Boxes found! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }

                if (shulkerInvSlot >= 9) {
                    int targetSlot = swapSlot.get();
                    InvUtils.move().from(shulkerInvSlot).toHotbar(targetSlot);
                    mc.player.getInventory().selectedSlot = targetSlot;
                } else {
                    mc.player.getInventory().selectedSlot = shulkerInvSlot;
                }

                BlockPos placeExclude = (depositMode.get() == DepositMode.EnderChest) ? echestPos : null;
                BlockPos placeExcludeUp = (depositMode.get() == DepositMode.EnderChest) ? echestPos.up() : null;
                shulkerPos = findAndPlace(InvUtils.findInHotbar(SHULKER_PREDICATE), placeExclude, placeExcludeUp);

                if (shulkerPos == null) {
                    spaceClearAttempts++;
                    if (spaceClearAttempts >= 3) {
                        sendPing("Failed to place Shulker Box after 3 attempts! Resuming mining.");
                        spaceClearAttempts = 0;
                        depositState = DepositState.RESUMING;
                        return;
                    }
                    depositState = DepositState.CLEARING_SPACE;
                    depositDelay = 10;
                    return;
                }
                
                spaceClearAttempts = 0;
                depositState = DepositState.OPENING_SHULKER;
                depositDelay = 5;
                break;

            case OPENING_SHULKER:
                if (!(mc.world.getBlockState(shulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
                    sendPing("Failed to place Shulker Box. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                BlockHitResult shulkerHit = new BlockHitResult(Vec3d.ofCenter(shulkerPos), Direction.UP, shulkerPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, shulkerHit);
                mc.player.swingHand(Hand.MAIN_HAND);
                depositState = DepositState.TRANSFERRING_ITEMS;
                depositDelay = 5;
                break;

            case TRANSFERRING_ITEMS:
                if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler)) {
                    if (!shulkerRecoveryAttempted) {
                        sendPing("Warning: Unable to open Shulker Box. Checking for obstructions...");
                        shulkerRecoveryAttempted = true;
                        depositState = DepositState.MINING_SURROUNDINGS_SHULKER;
                        depositDelay = 5;
                    } else {
                        sendPing("Failed to open Shulker Box even after clearing space. Resuming mining.");
                        depositState = DepositState.RESUMING;
                    }
                    return;
                }
                
                ShulkerBoxScreenHandler shulkerHandler = (ShulkerBoxScreenHandler) mc.player.currentScreenHandler;

                boolean moved = false;
                for (int i = 27; i < shulkerHandler.slots.size(); i++) {
                    ItemStack stack = shulkerHandler.getSlot(i).getStack();
                    if (validItems.contains(stack.getItem())) {
                        mc.interactionManager.clickSlot(shulkerHandler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                        moved = true;
                        break; 
                    }
                }
                
                if (moved) {
                    depositDelay = 2;
                } else {
                    depositState = DepositState.CLOSING_SHULKER;
                    depositDelay = 2;
                }
                break;

            case MINING_SURROUNDINGS_SHULKER:
                if (!mc.world.isAir(shulkerPos.up())) {
                    breakBlock(shulkerPos.up());
                    depositDelay = 10;
                    return;
                }
                
                sendPing("Surroundings cleared. Attempting to open Shulker Box again.");
                BlockHitResult shulkerReopenHit = new BlockHitResult(Vec3d.ofCenter(shulkerPos), Direction.UP, shulkerPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, shulkerReopenHit);
                mc.player.swingHand(Hand.MAIN_HAND);
                depositState = DepositState.TRANSFERRING_ITEMS;
                depositDelay = 5;
                break;

            case CLOSING_SHULKER:
                mc.player.closeHandledScreen();
                depositState = DepositState.BREAKING_SHULKER;
                depositDelay = 5;
                break;

            case BREAKING_SHULKER:
                if (!equipEnchantedPickaxe()) { 
                    sendPing("No " + toolEnchant.get() + " Pickaxe found! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return; 
                }
                
                if (mc.world.getBlockState(shulkerPos).getBlock() instanceof ShulkerBoxBlock) {
                    breakBlock(shulkerPos);
                    depositDelay = 1;
                    return;
                }
                
                pickupTimeout = 0;
                depositState = DepositState.PICKING_UP_SHULKER;
                depositDelay = 5; 
                break;

            case PICKING_UP_SHULKER:
                if (InvUtils.find(SHULKER_PREDICATE).found()) {
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    mc.options.leftKey.setPressed(false);
                    mc.options.rightKey.setPressed(false);
                    
                    if (playerLogoutPending) {
                        sendPing("Shulker Box secured. Skipping Ender Chest cleanup to log out safely!");
                        depositState = DepositState.IDLE;
                        return;
                    }
                    
                    if (depositMode.get() == DepositMode.EnderChest) {
                        depositState = DepositState.REOPENING_ECHEST;
                    } else {
                        depositState = DepositState.RESUMING;
                    }
                    depositDelay = 5;
                    return;
                }

                ItemEntity targetShulker = null;
                double closestShulkerDist = 8.0; 
                boolean shulkerExists = false;
                for (Entity entity : mc.world.getEntities()) {
                    if (entity instanceof ItemEntity itemEntity && SHULKER_PREDICATE.test(itemEntity.getStack())) {
                        double dist = mc.player.distanceTo(entity);
                        if (dist < 32.0) shulkerExists = true;
                        if (dist < closestShulkerDist) {
                            closestShulkerDist = dist;
                            targetShulker = itemEntity;
                        }
                    }
                }

                if (targetShulker != null) {
                    Vec3d itemPos = targetShulker.getPos();
                    double diffX = itemPos.x - mc.player.getX();
                    double diffZ = itemPos.z - mc.player.getZ();
                    float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90;
                    mc.player.setYaw(yaw);
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, mc.player.getPitch(), mc.player.isOnGround(), false));
                    
                    mc.options.forwardKey.setPressed(true);
                    if (Math.abs(itemPos.y - mc.player.getY()) > 0.5 || mc.player.horizontalCollision) {
                        mc.options.jumpKey.setPressed(true);
                    } else {
                        mc.options.jumpKey.setPressed(false);
                    }
                    
                    depositDelay = 1;
                } else {
                    pickupTimeout++;
                    if (pickupTimeout > 100) { 
                        mc.options.forwardKey.setPressed(false);
                        mc.options.jumpKey.setPressed(false);
                        
                        if (playerLogoutPending) {
                            sendPing("Failed to pick up Shulker, but logging out due to player!");
                            depositState = DepositState.IDLE;
                            return;
                        }

                        if ((isFinalDeposit || safeLogout.get()) && shulkerExists) {
                            sendPing("Waiting for Shulker Box to drop or come into range...");
                            pickupTimeout = 0; 
                            depositDelay = 10;
                        } else {
                            sendPing("Lost Shulker Box after mining it! Resuming mining.");
                            depositState = DepositState.RESUMING;
                        }
                    } else {
                        depositDelay = 1;
                    }
                }
                break;

            case REOPENING_ECHEST:
                if (mc.world.getBlockState(echestPos).getBlock() != Blocks.ENDER_CHEST) {
                    sendPing("Ender Chest disappeared before reopening! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                BlockHitResult echestHit2 = new BlockHitResult(Vec3d.ofCenter(echestPos), Direction.UP, echestPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, echestHit2);
                mc.player.swingHand(Hand.MAIN_HAND);
                depositState = DepositState.DEPOSITING_SHULKER;
                depositDelay = 5;
                break;

            case DEPOSITING_SHULKER:
                if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler)) {
                    sendPing("Failed to reopen Ender Chest. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                GenericContainerScreenHandler echestHandler2 = (GenericContainerScreenHandler) mc.player.currentScreenHandler;
                int shulkerReturnSlot = -1;
                
                for (int i = 27; i < echestHandler2.slots.size(); i++) {
                    if (SHULKER_PREDICATE.test(echestHandler2.getSlot(i).getStack())) {
                        shulkerReturnSlot = i;
                        break;
                    }
                }

                if (shulkerReturnSlot == -1) {
                    mc.player.closeHandledScreen();
                    sendPing("Lost Shulker Box after mining it! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }

                boolean placed = false;
                for (int i = 0; i < 27; i++) {
                    if (echestHandler2.getSlot(i).getStack().isEmpty()) {
                        mc.interactionManager.clickSlot(echestHandler2.syncId, shulkerReturnSlot, 0, SlotActionType.PICKUP, mc.player);
                        mc.interactionManager.clickSlot(echestHandler2.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                            mc.interactionManager.clickSlot(echestHandler2.syncId, shulkerReturnSlot, 0, SlotActionType.PICKUP, mc.player);
                        }
                        placed = true;
                        break;
                    }
                }
                
                if (!placed) {
                    mc.player.closeHandledScreen();
                    sendPing("Ender Chest is completely full! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }

                depositState = DepositState.REPLACING_TOOLS;
                depositDelay = 2;
                break;

            case REPLACING_TOOLS:
                if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler)) {
                    sendPing("Failed to reopen Ender Chest for tool check. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                GenericContainerScreenHandler echestHandler3 = (GenericContainerScreenHandler) mc.player.currentScreenHandler;

                RegistryKey<Enchantment> enchantKey = toolEnchant.get() == ToolEnchant.SilkTouch ? Enchantments.SILK_TOUCH : Enchantments.FORTUNE;
                RegistryEntry<Enchantment> enchantment = mc.world.getRegistryManager()
                    .getOrThrow(RegistryKeys.ENCHANTMENT)
                    .getOrThrow(enchantKey);

                for (int i = 27; i < echestHandler3.slots.size(); i++) {
                    ItemStack invStack = echestHandler3.getSlot(i).getStack();
                    String type = getToolType(invStack);
                    if (type != null) {
                        double durability = getDurabilityPercent(invStack);
                        if (durability <= minToolDurability.get()) {
                            int newToolSlot = -1;
                            
                            for (int j = 0; j < 27; j++) {
                                ItemStack echestStack = echestHandler3.getSlot(j).getStack();
                                if (getToolType(echestStack) != null && getToolType(echestStack).equals(type)) {
                                    if (EnchantmentHelper.getLevel(enchantment, echestStack) > 0) {
                                        newToolSlot = j;
                                        break;
                                    }
                                }
                            }

                            if (newToolSlot == -1) {
                                for (int j = 0; j < 27; j++) {
                                    ItemStack echestStack = echestHandler3.getSlot(j).getStack();
                                    if (getToolType(echestStack) != null && getToolType(echestStack).equals(type)) {
                                        newToolSlot = j;
                                        break;
                                    }
                                }
                            }

                            if (newToolSlot != -1) {
                                mc.interactionManager.clickSlot(echestHandler3.syncId, newToolSlot, 0, SlotActionType.PICKUP, mc.player);
                                mc.interactionManager.clickSlot(echestHandler3.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                                if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                                    mc.interactionManager.clickSlot(echestHandler3.syncId, newToolSlot, 0, SlotActionType.PICKUP, mc.player);
                                }
                                sendPing("Replaced low durability " + type + ".");
                            } else {
                                sendPing("Warning: " + type + " durability low, but no replacement found in Ender Chest.");
                            }
                        }
                    }
                }
                
                depositState = DepositState.REGEAR_FOOD;
                depositDelay = 2;
                break;

            case REGEAR_FOOD:
                if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler)) {
                    sendPing("Failed to reopen Ender Chest for food check. Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return;
                }
                GenericContainerScreenHandler echestHandler4 = (GenericContainerScreenHandler) mc.player.currentScreenHandler;
                
                Item targetFood = foodItem.get();
                if (targetFood != Items.AIR) {
                    int currentFoodCount = 0;
                    for (int i = 27; i < echestHandler4.slots.size(); i++) {
                        ItemStack invStack = echestHandler4.getSlot(i).getStack();
                        if (invStack.getItem() == targetFood) {
                            currentFoodCount += invStack.getCount();
                        }
                    }
                    
                    if (currentFoodCount < minFoodCount.get()) {
                        boolean movedFood = false;
                        for (int i = 0; i < 27; i++) {
                            ItemStack echestStack = echestHandler4.getSlot(i).getStack();
                            if (echestStack.getItem() == targetFood) {
                                mc.interactionManager.clickSlot(echestHandler4.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                                movedFood = true;
                                break;
                            }
                        }
                        if (movedFood) {
                            sendPing("Low food detected. Regearing food from Ender Chest...");
                            depositDelay = 2;
                            return;
                        }
                    }
                }
                
                depositState = DepositState.CLOSING_ECHEST_AGAIN;
                depositDelay = 2;
                break;

            case CLOSING_ECHEST_AGAIN:
                mc.player.closeHandledScreen();
                depositState = DepositState.BREAKING_ECHEST;
                depositDelay = 5;
                break;

            case BREAKING_ECHEST:
                if (!equipEnchantedPickaxe()) { 
                    sendPing("No " + toolEnchant.get() + " Pickaxe found! Resuming mining.");
                    depositState = DepositState.RESUMING;
                    return; 
                }
                
                if (mc.world.getBlockState(echestPos).getBlock() == Blocks.ENDER_CHEST) {
                    breakBlock(echestPos);
                    depositDelay = 1;
                    return;
                }
                
                pickupTimeout = 0;

                if (toolEnchant.get() == ToolEnchant.Fortune) {
                    sendPing("Fortune mode active: Leaving Ender Chest drops behind.");
                    depositState = DepositState.RESUMING;
                    depositDelay = 5;
                } else {
                    depositState = DepositState.PICKING_UP_ECHEST;
                    depositDelay = 5; 
                }
                break;

            case PICKING_UP_ECHEST:
                if (InvUtils.find(Items.ENDER_CHEST).found()) {
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    depositState = DepositState.RESUMING;
                    depositDelay = 5;
                    return;
                }

                ItemEntity targetEchest = null;
                double closestEchestDist = 8.0;
                boolean echestExists = false;
                for (Entity entity : mc.world.getEntities()) {
                    if (entity instanceof ItemEntity itemEntity && itemEntity.getStack().getItem() == Items.ENDER_CHEST) {
                        double dist = mc.player.distanceTo(entity);
                        if (dist < 32.0) echestExists = true;
                        if (dist < closestEchestDist) {
                            closestEchestDist = dist;
                            targetEchest = itemEntity;
                        }
                    }
                }

                if (targetEchest != null) {
                    Vec3d itemPos = targetEchest.getPos();
                    double diffX = itemPos.x - mc.player.getX();
                    double diffZ = itemPos.z - mc.player.getZ();
                    float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90;
                    mc.player.setYaw(yaw);
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, mc.player.getPitch(), mc.player.isOnGround(), false));
                    mc.options.forwardKey.setPressed(true);
                    if (Math.abs(itemPos.y - mc.player.getY()) > 0.5 || mc.player.horizontalCollision) {
                        mc.options.jumpKey.setPressed(true);
                    } else {
                        mc.options.jumpKey.setPressed(false);
                    }
                    depositDelay = 1;
                } else {
                    pickupTimeout++;
                    if (pickupTimeout > 100) { 
                        mc.options.forwardKey.setPressed(false);
                        mc.options.jumpKey.setPressed(false);

                        if ((isFinalDeposit || safeLogout.get()) && echestExists) {
                            sendPing("Waiting for Ender Chest to drop or come into range...");
                            pickupTimeout = 0; 
                            depositDelay = 10;
                        } else {
                            sendPing("Lost Ender Chest after mining it! Resuming mining.");
                            depositState = DepositState.RESUMING;
                        }
                    } else {
                        depositDelay = 1;
                    }
                }
                break;

            case RESUMING:
                ensureToolsInHotbar();
                depositState = DepositState.IDLE;
                
                if (isFinalDeposit) {
                    isFinalDeposit = false;
                    sendPing("Final deposit complete. Target stacks reached!");
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
                    if (safeLogout.get() && isInNetherOrOverworld()) {
                        startHideout();
                    } else {
                        toggle();
                    }
                } else if (playerLogoutPending) {
                    // Handled by onTick logout logic
                } else {
                    sendPing("Successfully deposited items. Resuming Baromine.");
                    updateBaritoneGoal();
                }
                break;
        }
    }

    // --- HELPER METHODS: BARITONE & TARGETING ---

    private void updateBaritoneGoal() {
        updateBaritoneGoal(getTargetBlock());
    }

    private void updateBaritoneGoal(Block target) {
        if (!isActive()) return; 
        if (mc.player == null) return;
        if (Modules.get().isActive(PortalMaker.class)) return; // Yield control to PortalMaker

        String blockId = Registries.BLOCK.getId(target).toString();
        StringBuilder mineCommand = new StringBuilder("mine ").append(blockId);
        
        if (targetMode.get() == TargetMode.Ores && includeDeepslate.get()) {
            Block deepslateVariant = getDeepslateVariant(target);
            if (deepslateVariant != null) {
                String deepslateId = Registries.BLOCK.getId(deepslateVariant).toString();
                mineCommand.append(" ").append(deepslateId);
            }
        }
        
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("set minYLevelWhileMining " + (minYLevel.get() + 64));
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("set maxYLevelWhileMining " + (maxYLevel.get() + 64));
        
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(mineCommand.toString());
    }

    public Block getTargetBlock() {
        return targetMode.get() == TargetMode.Ores ? targetOre.get() : targetBlock.get();
    }

    public int getCurrentTargetCount() {
        Set<Item> validItems = getValidTargetItems();
        
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (validItems.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public int getTotalAvailableTargetItems() {
        Set<Item> validItems = getValidTargetItems();
        int count = 0;
        
        // 1. Player Inventory
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (validItems.contains(stack.getItem())) {
                count += stack.getCount();
            } else if (SHULKER_PREDICATE.test(stack)) {
                count += countItemsInShulker(stack, validItems);
            }
        }
        
        // 2. Ender Chest
        if (mc.player.getEnderChestInventory() != null) {
            for (int i = 0; i < mc.player.getEnderChestInventory().size(); i++) {
                ItemStack stack = mc.player.getEnderChestInventory().getStack(i);
                if (validItems.contains(stack.getItem())) {
                    count += stack.getCount();
                } else if (SHULKER_PREDICATE.test(stack)) {
                    count += countItemsInShulker(stack, validItems);
                }
            }
        }
        
        return count;
    }

    private int countItemsInShulker(ItemStack shulkerStack, Set<Item> validItems) {
        ContainerComponent container = shulkerStack.get(DataComponentTypes.CONTAINER);
        if (container == null) return 0;
        return (int) container.stream()
            .filter(stack -> !stack.isEmpty() && validItems.contains(stack.getItem()))
            .mapToInt(ItemStack::getCount)
            .sum();
    }

    private Set<Item> getValidTargetItems() {
        Set<Item> items = new HashSet<>();
        Block target = getTargetBlock();
        items.add(target.asItem());
        
        Item drop = getOreDrop(target);
        if (drop != null) items.add(drop);
        
        Block deepslate = getDeepslateVariant(target);
        if (deepslate != null) items.add(deepslate.asItem());
        
        return items;
    }

    private Block getDeepslateVariant(Block block) {
        if (block == Blocks.DIAMOND_ORE) return Blocks.DEEPSLATE_DIAMOND_ORE;
        if (block == Blocks.IRON_ORE) return Blocks.DEEPSLATE_IRON_ORE;
        if (block == Blocks.GOLD_ORE) return Blocks.DEEPSLATE_GOLD_ORE;
        if (block == Blocks.COPPER_ORE) return Blocks.DEEPSLATE_COPPER_ORE;
        if (block == Blocks.COAL_ORE) return Blocks.DEEPSLATE_COAL_ORE;
        if (block == Blocks.LAPIS_ORE) return Blocks.DEEPSLATE_LAPIS_ORE;
        if (block == Blocks.REDSTONE_ORE) return Blocks.DEEPSLATE_REDSTONE_ORE;
        if (block == Blocks.EMERALD_ORE) return Blocks.DEEPSLATE_EMERALD_ORE;
        return null;
    }

    private Item getOreDrop(Block block) {
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) return Items.DIAMOND;
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) return Items.RAW_IRON;
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) return Items.RAW_GOLD;
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) return Items.RAW_COPPER;
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) return Items.COAL;
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) return Items.LAPIS_LAZULI;
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) return Items.REDSTONE;
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) return Items.EMERALD;
        if (block == Blocks.NETHER_GOLD_ORE) return Items.GOLD_NUGGET;
        if (block == Blocks.NETHER_QUARTZ_ORE) return Items.QUARTZ;
        return null; 
    }

    private void stopBaritoneSafely(String reason) {
        if (Modules.get().isActive(PortalMaker.class)) return; // Don't stop if PortalMaker is using it
        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
        sendPing("SAFETY STOP: " + reason);
    }

    private boolean isOre(Block block) {
        if (block == Blocks.ANCIENT_DEBRIS) return true;
        if (block == Blocks.SPORE_BLOSSOM || block == Blocks.HEAVY_CORE) return false;
        return block.getName().getString().toLowerCase().contains("ore");
    }

    private boolean hasToolsBelowDurability(double threshold) {
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            String type = getToolType(stack);
            if (type != null) {
                double durability = getDurabilityPercent(stack);
                if (durability < threshold) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- HELPER METHODS: DEPOSIT & INVENTORY ---

    private void disconnectSafely(String reason) {
        sendPing(reason);
        if (mc.player != null) mc.player.playSound(getSoundEvent(), soundVolume.get().floatValue(), 1.0f);
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(new DisconnectionInfo(Text.literal(reason + " Baromine disconnect.")));
        }
        depositState = DepositState.IDLE;
        if (!autoReconnect.get()) toggle();
    }

    private BlockPos findAndPlace(FindItemResult item, BlockPos... exclude) {
        if (!item.found()) return null;
        Set<BlockPos> excluded = new HashSet<>(Arrays.asList(exclude));
        
        BlockPos playerPos = mc.player.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>();
        
        Direction[] horizontal = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        
        for (Direction dir : horizontal) candidates.add(playerPos.offset(dir));
        for (Direction dir : horizontal) candidates.add(playerPos.up().offset(dir));
        candidates.add(playerPos.up(2));
        candidates.add(playerPos.down());
        
        for (BlockPos pos : candidates) {
            if (excluded.contains(pos)) continue;
            if (mc.world.isAir(pos) || mc.world.getBlockState(pos).isReplaceable()) {
                if (mc.world.isAir(pos.up()) || mc.world.getBlockState(pos.up()).isReplaceable()) {
                    if (BlockUtils.place(pos, item, 0)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private boolean isValidShulkerForDeposit(ItemStack shulkerStack, Set<Item> validItems) {
        ContainerComponent container = shulkerStack.get(DataComponentTypes.CONTAINER);
        if (container == null) return true; 
        
        long filledSlots = container.stream().filter(stack -> !stack.isEmpty()).count();
        if (filledSlots >= 27) return false; 
        
        return container.stream()
            .filter(stack -> !stack.isEmpty())
            .allMatch(stack -> validItems.contains(stack.getItem()));
    }

    private String getToolType(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Item item = stack.getItem();
        if (item instanceof PickaxeItem) return "pickaxe";
        if (item instanceof AxeItem) return "axe";
        if (item instanceof ShovelItem) return "shovel";
        if (item instanceof HoeItem) return "hoe";
        return null;
    }

    private boolean equipEnchantedPickaxe() {
        if (mc.world == null) return false;
        RegistryKey<Enchantment> enchantKey = toolEnchant.get() == ToolEnchant.SilkTouch ? Enchantments.SILK_TOUCH : Enchantments.FORTUNE;
        RegistryEntry<Enchantment> enchantment = mc.world.getRegistryManager()
            .getOrThrow(RegistryKeys.ENCHANTMENT)
            .getOrThrow(enchantKey);
        
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof PickaxeItem) {
                if (EnchantmentHelper.getLevel(enchantment, stack) > 0) {
                    if (i < 9) {
                        mc.player.getInventory().selectedSlot = i;
                    } else {
                        int targetSlot = -1;
                        for (int j = 0; j < 9; j++) {
                            if (mc.player.getInventory().getStack(j).isEmpty()) {
                                targetSlot = j;
                                break;
                            }
                        }
                        if (targetSlot == -1) {
                            targetSlot = mc.player.getInventory().selectedSlot;
                        }
                        
                        InvUtils.move().from(i).toHotbar(targetSlot);
                        mc.player.getInventory().selectedSlot = targetSlot;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureToolsInHotbar() {
        if (mc.player == null) return;
        
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (getToolType(stack) != null) {
                int targetSlot = -1;
                for (int j = 0; j < 9; j++) {
                    if (mc.player.getInventory().getStack(j).isEmpty()) {
                        targetSlot = j;
                        break;
                    }
                }
                
                if (targetSlot != -1) {
                    InvUtils.move().from(i).toHotbar(targetSlot);
                } else {
                    InvUtils.move().from(i).toHotbar(mc.player.getInventory().selectedSlot);
                }
            }
        }
    }

    private void equipSword() {
        if (mc.player == null) return;
        int bestSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof SwordItem) {
                bestSlot = i;
                break;
            }
        }
        if (bestSlot == -1) {
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getStack(i).getItem() instanceof SwordItem) {
                    int targetSlot = -1;
                    for (int j = 0; j < 9; j++) {
                        if (mc.player.getInventory().getStack(j).isEmpty()) {
                            targetSlot = j;
                            break;
                        }
                    }
                    if (targetSlot == -1) targetSlot = mc.player.getInventory().selectedSlot;
                    InvUtils.move().from(i).toHotbar(targetSlot);
                    bestSlot = targetSlot;
                    break;
                }
            }
        }
        if (bestSlot != -1) {
            mc.player.getInventory().selectedSlot = bestSlot;
        }
    }

    // --- HELPER METHODS: LAVA/WATER SAFETY & WORLD INTERACTION ---

    private boolean isWaterNearby(BlockPos center, int radius) {
        if (mc.world == null) return false;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -1; y <= 2; y++) { 
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = center.add(x, y, z);
                    var fluidState = mc.world.getBlockState(checkPos).getFluidState();
                    if (fluidState.isOf(Fluids.WATER) || fluidState.isOf(Fluids.FLOWING_WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isImmediateLavaDanger() {
        BlockPos playerPos = mc.player.getBlockPos();
        if (mc.world.getBlockState(playerPos).getBlock() == Blocks.LAVA) return true;
        if (mc.world.getBlockState(playerPos.up()).getBlock() == Blocks.LAVA) return true;
        
        for (Direction dir : Direction.values()) {
            if (dir.getAxis().isHorizontal()) {
                if (mc.world.getBlockState(playerPos.offset(dir)).getBlock() == Blocks.LAVA) return true;
            }
        }
        return false;
    }

    private boolean isImmediateWaterDanger() {
        BlockPos playerPos = mc.player.getBlockPos();
        if (mc.world.getBlockState(playerPos).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)) return true;
        if (mc.world.getBlockState(playerPos.up()).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)) return true;
        
        for (Direction dir : Direction.values()) {
            if (dir.getAxis().isHorizontal()) {
                if (mc.world.getBlockState(playerPos.offset(dir)).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    private void placeSafetyBlock(boolean isWater) {
        Block fluidBlock = isWater ? Blocks.WATER : Blocks.LAVA;
        
        FindItemResult blockItem = InvUtils.findInHotbar(itemStack -> 
            itemStack.getItem() instanceof net.minecraft.item.BlockItem && 
            !SHULKER_PREDICATE.test(itemStack) && 
            itemStack.getItem() != Items.ENDER_CHEST
        );
        
        if (!blockItem.found()) {
            if (isWater) stopBaritoneSafely("No blocks for water safety!");
            else disconnectSafely("No blocks for lava safety!");
            return;
        }
        
        BlockPos playerPos = mc.player.getBlockPos();
        Direction[] horizontal = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        
        for (Direction dir : horizontal) {
            BlockPos sidePos = playerPos.offset(dir);
            if (mc.world.getBlockState(sidePos).getBlock() == fluidBlock) {
                lookAtBlock(sidePos);
                if (BlockUtils.place(sidePos, blockItem, 0)) {
                    return;
                }
            }
        }
        
        for (Direction dir : horizontal) {
            BlockPos sidePos = playerPos.offset(dir);
            BlockPos sideUpPos = sidePos.up();
            BlockPos sideDownPos = sidePos.down();
            
            boolean sideClear = mc.world.isAir(sidePos) || mc.world.getBlockState(sidePos).getBlock() == fluidBlock;
            boolean sideUpClear = mc.world.isAir(sideUpPos) || mc.world.getBlockState(sideUpPos).getBlock() == fluidBlock;
            
            if (sideClear && sideUpClear) {
                if (mc.world.getBlockState(sidePos).getBlock() == fluidBlock) {
                    lookAtBlock(sidePos);
                    BlockUtils.place(sidePos, blockItem, 0);
                }
                if (mc.world.isAir(sideDownPos) || mc.world.getBlockState(sideDownPos).getBlock() == fluidBlock) {
                    lookAtBlock(sideDownPos);
                    BlockUtils.place(sideDownPos, blockItem, 0);
                }
                
                float yaw = switch (dir) {
                    case NORTH -> 180f;
                    case SOUTH -> 0f;
                    case WEST -> 90f;
                    case EAST -> -90f;
                    default -> 0f;
                };
                
                mc.player.setYaw(yaw);
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, mc.player.getPitch(), mc.player.isOnGround(), false));
                mc.options.forwardKey.setPressed(true);
                if (isWater) waterMoveTicks = 10;
                else lavaMoveTicks = 10;
                return;
            }
        }
        
        BlockPos headPos = playerPos.up(2);
        boolean headClear = mc.world.isAir(headPos) || mc.world.getBlockState(headPos).getBlock() == fluidBlock;
        
        if (headClear) {
            BlockPos downPos = playerPos.down();
            if (mc.world.isAir(downPos) || mc.world.getBlockState(downPos).getBlock() == fluidBlock) {
                lookAtBlock(downPos);
                BlockUtils.place(downPos, blockItem, 0);
            }
            mc.options.jumpKey.setPressed(true);
            jumpTicks = 5;
        }
    }

    private void lookAtBlock(BlockPos pos) {
        Vec3d posVec = Vec3d.ofCenter(pos);
        double diffX = posVec.x - mc.player.getX();
        double diffY = posVec.y - (mc.player.getY() + mc.player.getStandingEyeHeight());
        double diffZ = posVec.z - mc.player.getZ();
        
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(diffY, dist));
        
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), false));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    private void breakBlock(BlockPos pos) {
        if (mc.world.getBlockState(pos).isAir()) return;
        lookAtBlock(pos);
        mc.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    // --- HELPER METHODS: NOTIFICATIONS ---

    private void sendPing(String message) {
        if (pingMode.get() == PingMode.Chat || pingMode.get() == PingMode.Both) {
            ChatUtils.sendMsg("Baromine", Text.literal(message));
        }
        if (pingMode.get() == PingMode.Sound || pingMode.get() == PingMode.Both) {
            if (mc.player != null) {
                mc.player.playSound(getSoundEvent(), soundVolume.get().floatValue(), 1.0f);
            }
        }
    }

    private SoundEvent getSoundEvent() {
        return switch (warningSound.get()) {
            case Bass -> SoundEvents.BLOCK_NOTE_BLOCK_BASS.value();
            case Harp -> SoundEvents.BLOCK_NOTE_BLOCK_HARP.value();
            case Bell -> SoundEvents.BLOCK_BELL_USE;
            case Anvil -> SoundEvents.BLOCK_ANVIL_LAND;
            case LevelUp -> SoundEvents.ENTITY_PLAYER_LEVELUP;
            case OrbPickup -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
            case Beacon -> SoundEvents.BLOCK_BEACON_POWER_SELECT;
            case GhastWarn -> SoundEvents.ENTITY_GHAST_WARN;
            case DragonGrowl -> SoundEvents.ENTITY_ENDER_DRAGON_GROWL;
            case WitherSpawn -> SoundEvents.ENTITY_WITHER_SPAWN;
            case ChallengeComplete -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
            default -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value();
        };
    }

    // --- BARITONE STATE HELPER ---
    public static boolean isBaritoneIdle() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        IPathingBehavior pathing = baritone.getPathingBehavior();

        boolean activeProcess = baritone.getPathingControlManager()
                .mostRecentInControl()
                .map(IBaritoneProcess::isActive)
                .orElse(false);

        boolean executingPath = pathing.isPathing();
        boolean holdingPath = pathing.hasPath();
        boolean calculatingPath = pathing.getInProgress().isPresent();

        return !activeProcess
                && !executingPath
                && !holdingPath
                && !calculatingPath;
    }

    // --- HUD HELPERS ---
    public String getCurrentStatus() {
        if (!isActive()) return "Inactive";
        if (isAutoMending) return "Auto-Mending";
        if (hideoutState != HideoutState.IDLE) return "Hiding for Logout";
        if (deepDarkState == DeepDarkState.RETREATING) return "Retreating (Deep Dark)";
        if (deepDarkState == DeepDarkState.ASCENDING) return "Ascending (Deep Dark)";
        if (deepDarkState == DeepDarkState.RUNNING_AWAY) return "Running Away";
        if (craftState != CraftState.IDLE) return "Crafting";
        if (depositState != DepositState.IDLE) return "Depositing: " + depositState.name().replace("_", " ");
        if (isHandlingLava) return "Avoiding Lava";
        if (isHandlingWater) return "Avoiding Water";
        if (wasPausedForCombat) return "Paused (Combat)";
        if (isWaitingToReconnect) return "Waiting to Reconnect";
        return "Mining";
    }

    public double getMainHandDurabilityPercent() {
        if (mc.player == null) return 100.0;
        return getDurabilityPercent(mc.player.getMainHandStack());
    }

    public long getSessionStartTime() {
        return startTime;
    }
}