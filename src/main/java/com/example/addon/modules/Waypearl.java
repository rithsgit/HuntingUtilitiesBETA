package com.example.addon.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.example.addon.Tim;
import com.example.addon.utils.GlowingRegistry;

import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

public class Waypearl extends Module {

    // ═══════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════

    public enum PingSound {
        BeaconActivate   ("Beacon Activate",    SoundEvents.BLOCK_BEACON_ACTIVATE),
        BeaconDeactivate ("Beacon Deactivate",  SoundEvents.BLOCK_BEACON_DEACTIVATE),
        BellUse          ("Bell",               SoundEvents.BLOCK_BELL_USE),
        ExperienceOrb    ("Experience Orb",     SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP),
        PlayerLevelUp    ("Level Up",           SoundEvents.ENTITY_PLAYER_LEVELUP),
        NoteBlockBell    ("Note Bell",          reg("block.note_block.bell")),
        NoteBlockChime   ("Note Chime",         reg("block.note_block.chime")),
        NoteBlockPling   ("Note Pling",         reg("block.note_block.pling")),
        EnderEye         ("Ender Eye",          SoundEvents.ENTITY_ENDER_EYE_LAUNCH),
        EndermanTeleport ("Enderman Teleport",  SoundEvents.ENTITY_ENDERMAN_TELEPORT);

        public final String label;
        public final SoundEvent sound;
        PingSound(String label, SoundEvent sound) { this.label = label; this.sound = sound; }
        @Override public String toString() { return label; }
        private static SoundEvent reg(String id) {
            return Registries.SOUND_EVENT.get(Identifier.of(id));
        }
    }

    public enum ModuleMode { Assistant, Requester }
    public enum PullOrder { DISCOVERY, NEAREST }
    public enum RenderMode { Default, PearlsOnly }

    private enum WalkState { IDLE, WALKING_TO, PULLING, WALKING_BACK, ABORTED }

    // ═══════════════════════════════════════════════════════════════
    // Constants & Helpers
    // ═══════════════════════════════════════════════════════════════

    private static final Identifier BEAM_TEXTURE = Identifier.of("minecraft", "textures/entity/beacon_beam.png");
    
    private static final Map<Integer, Vec3d> BEAM_POS_CACHE = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgColumns    = settings.createGroup("Bubble Columns");
    private final SettingGroup sgCap        = settings.createGroup("Cap Box");
    private final SettingGroup sgSound      = settings.createGroup("Sound");
    private final SettingGroup sgBot        = settings.createGroup("Bot Assistant");
    private final SettingGroup sgWalker     = settings.createGroup("Walker");
    private final SettingGroup sgPearlsOnly = settings.createGroup("Pearls Only");
    private final SettingGroup sgDisconnect = settings.createGroup("Auto Disconnect");

    // ═════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═════════════════════════════════════════════════════════════════════

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Detection radius in blocks.")
        .defaultValue(64).min(16).sliderMax(128).build());

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval").description("Ticks between bubble column background scans.")
        .defaultValue(40).min(10).sliderMax(200).build());

    private final Setting<RenderMode> renderMode = sgGeneral.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode").description("Default = bubble column beams. Pearls Only = entity outlines & soul sand beams.")
        .defaultValue(RenderMode.Default).build());

    private final Setting<Boolean> highlightOwnPearl = sgGeneral.add(new BoolSetting.Builder()
        .name("highlight-own-pearl").description("Use a distinct color for your own pearls.")
        .defaultValue(true).build());

    private final Setting<SettingColor> ownPearlColor = sgGeneral.add(new ColorSetting.Builder()
        .name("own-pearl-color").description("Color for your own pearl's outline in Default mode.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .visible(() -> renderMode.get() == RenderMode.Default && highlightOwnPearl.get()).build());

    // ═══════════════════════════════════════════════════════════════
    // Settings — Bubble Columns (Hidden if PearlsOnly)
    // ═══════════════════════════════════════════════════════════════

    private final Setting<Boolean> columnsEnabled = sgColumns.add(new BoolSetting.Builder()
        .name("highlight-columns").defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Default).build());

    private final Setting<SettingColor> coreColor = sgColumns.add(new ColorSetting.Builder()
        .name("core-color").defaultValue(new SettingColor(180, 230, 255, 255))
        .visible(() -> renderMode.get() == RenderMode.Default && columnsEnabled.get()).build());

    private final Setting<SettingColor> glowColor = sgColumns.add(new ColorSetting.Builder()
        .name("glow-color").defaultValue(new SettingColor(0, 180, 255, 50))
        .visible(() -> renderMode.get() == RenderMode.Default && columnsEnabled.get()).build());

    private final Setting<Double> coreWidth = sgColumns.add(new DoubleSetting.Builder()
        .name("core-width").defaultValue(0.03).min(0.005).sliderMax(0.25)
        .visible(() -> renderMode.get() == RenderMode.Default && columnsEnabled.get()).build());

    private final Setting<Double> glowSpread = sgColumns.add(new DoubleSetting.Builder()
        .name("glow-spread").defaultValue(0.08).min(0.01).sliderMax(0.5)
        .visible(() -> renderMode.get() == RenderMode.Default && columnsEnabled.get()).build());

    private final Setting<Integer> glowLayers = sgColumns.add(new IntSetting.Builder()
        .name("glow-layers").defaultValue(4).min(1).sliderMax(8)
        .visible(() -> renderMode.get() == RenderMode.Default && columnsEnabled.get()).build());

    private final Setting<Integer> glowBaseAlpha = sgColumns.add(new IntSetting.Builder()
        .name("glow-base-alpha").defaultValue(50).min(4).sliderMax(150)
        .visible(() -> renderMode.get() == RenderMode.Default && columnsEnabled.get()).build());

    // ═══════════════════════════════════════════════════════════════
    // Settings — Cap Box (Hidden if PearlsOnly)
    // ═══════════════════════════════════════════════════════════════

    public enum CapPosition { NONE, BOTTOM, TOP, BOTH }

    private final Setting<CapPosition> capPosition = sgCap.add(new EnumSetting.Builder<CapPosition>()
        .name("cap-position").defaultValue(CapPosition.BOTTOM)
        .visible(() -> renderMode.get() == RenderMode.Default).build());

    private final Setting<SettingColor> capColor = sgCap.add(new ColorSetting.Builder()
        .name("cap-color").defaultValue(new SettingColor(0, 200, 255, 160))
        .visible(() -> renderMode.get() == RenderMode.Default && capPosition.get() != CapPosition.NONE).build());

    private final Setting<Double> capSize = sgCap.add(new DoubleSetting.Builder()
        .name("cap-size").defaultValue(0.4).min(0.05).sliderMax(2.0)
        .visible(() -> renderMode.get() == RenderMode.Default && capPosition.get() != CapPosition.NONE).build());

    private final Setting<Double> capThickness = sgCap.add(new DoubleSetting.Builder()
        .name("cap-thickness").defaultValue(0.04).min(0.01).sliderMax(0.5)
        .visible(() -> renderMode.get() == RenderMode.Default && capPosition.get() != CapPosition.NONE).build());

    private final Setting<ShapeMode> capShapeMode = sgCap.add(new EnumSetting.Builder<ShapeMode>()
        .name("cap-shape-mode").defaultValue(ShapeMode.Both)
        .visible(() -> renderMode.get() == RenderMode.Default && capPosition.get() != CapPosition.NONE).build());

    private final Setting<Boolean> capGlow = sgCap.add(new BoolSetting.Builder()
        .name("cap-glow").defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Default && capPosition.get() != CapPosition.NONE).build());

    // ═══════════════════════════════════════════════════════════════
    // Settings — Sound
    // ═══════════════════════════════════════════════════════════════

    private final Setting<Boolean> soundEnabled = sgSound.add(new BoolSetting.Builder()
        .name("sound-ping").defaultValue(true).build());

    private final Setting<PingSound> pingSound = sgSound.add(new EnumSetting.Builder<PingSound>()
        .name("sound").defaultValue(PingSound.BeaconActivate).visible(soundEnabled::get).build());

    private final Setting<Double> soundVolume = sgSound.add(new DoubleSetting.Builder()
        .name("volume").defaultValue(1.0).min(0.1).sliderMax(2.0).visible(soundEnabled::get).build());

    private final Setting<Double> soundPitch = sgSound.add(new DoubleSetting.Builder()
        .name("pitch").defaultValue(1.8).min(0.5).sliderMax(2.0).visible(soundEnabled::get).build());

    // ═══════════════════════════════════════════════════════════════
    // Settings — Bot Assistant
    // ═══════════════════════════════════════════════════════════════

    private final Setting<ModuleMode> moduleMode = sgBot.add(new EnumSetting.Builder<ModuleMode>()
        .name("mode").defaultValue(ModuleMode.Requester).build());

    private final Setting<String> botUsername = sgBot.add(new StringSetting.Builder()
        .name("bot-username").defaultValue("").build());

    private final Setting<String> triggerPhrase = sgBot.add(new StringSetting.Builder()
        .name("trigger-phrase").defaultValue("tp,pull,pearl").build());

    private final Setting<List<String>> whitelist = sgBot.add(new StringListSetting.Builder()
        .name("whitelist").defaultValue(Collections.emptyList())
        .visible(() -> moduleMode.get() == ModuleMode.Assistant).build());

    private final Setting<Keybind> selfTriggerKey = sgBot.add(new KeybindSetting.Builder()
        .name("self-trigger-key").defaultValue(Keybind.none())
        .visible(() -> moduleMode.get() == ModuleMode.Requester).build());

    private final Setting<Integer> pullCooldown = sgBot.add(new IntSetting.Builder()
        .name("cooldown").defaultValue(5).min(1).sliderMax(30).build());

    private final Setting<PullOrder> pullOrder = sgBot.add(new EnumSetting.Builder<PullOrder>()
        .name("pull-order").defaultValue(PullOrder.DISCOVERY)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant).build());

    private final Setting<Boolean> notifyEnabled = sgBot.add(new BoolSetting.Builder()
        .name("notify-requester").defaultValue(true)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant).build());

    private final Setting<String> notifyMessage = sgBot.add(new StringSetting.Builder()
        .name("notify-message").defaultValue("Pulling your pearl now, {player}.")
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && notifyEnabled.get()).build());

    // ═══════════════════════════════════════════════════════════════
    // Settings — Walker
    // ═══════════════════════════════════════════════════════════════

    private final Setting<Boolean> walkerEnabled = sgWalker.add(new BoolSetting.Builder()
        .name("walker-enabled").defaultValue(true)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant).build());

    private final Setting<Double> interactReach = sgWalker.add(new DoubleSetting.Builder()
        .name("interact-reach").defaultValue(3.5).min(1.0).sliderMax(5.0)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Double> slowZoneRadius = sgWalker.add(new DoubleSetting.Builder()
        .name("slow-zone-radius").defaultValue(6.0).min(2.0).sliderMax(16.0)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Double> waypointSpacing = sgWalker.add(new DoubleSetting.Builder()
        .name("waypoint-spacing").defaultValue(6.0).min(2.0).sliderMax(20.0)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Integer> walkTimeoutTicks = sgWalker.add(new IntSetting.Builder()
        .name("walk-timeout").defaultValue(200).min(40).sliderMax(600)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Integer> returnTimeoutTicks = sgWalker.add(new IntSetting.Builder()
        .name("return-timeout").defaultValue(200).min(40).sliderMax(600)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Double> returnTolerance = sgWalker.add(new DoubleSetting.Builder()
        .name("return-tolerance").defaultValue(1.0).min(0.3).sliderMax(5.0)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Integer> stuckThresholdTicks = sgWalker.add(new IntSetting.Builder()
        .name("stuck-threshold").defaultValue(30).min(10).sliderMax(100)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Double> stuckMovementMin = sgWalker.add(new DoubleSetting.Builder()
        .name("stuck-min-movement").defaultValue(0.02).min(0.005).sliderMax(0.2)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Double> voidAbortY = sgWalker.add(new DoubleSetting.Builder()
        .name("void-abort-y").defaultValue(0.0).min(-64.0).sliderMax(64.0)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Boolean> returnAfterPull = sgWalker.add(new BoolSetting.Builder()
        .name("return-after-pull").defaultValue(true)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Boolean> walkerSprint = sgWalker.add(new BoolSetting.Builder()
        .name("sprint").defaultValue(true)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Boolean> walkerJump = sgWalker.add(new BoolSetting.Builder()
        .name("auto-jump").defaultValue(true)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Integer> jumpAttemptTicks = sgWalker.add(new IntSetting.Builder()
        .name("jump-attempt-ticks").defaultValue(8).min(2).sliderMax(30)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get() && walkerJump.get()).build());

    private final Setting<Integer> jumpCooldownTicks = sgWalker.add(new IntSetting.Builder()
        .name("jump-cooldown").defaultValue(12).min(4).sliderMax(40)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get() && walkerJump.get()).build());

    private final Setting<Boolean> sneakOnInteract = sgWalker.add(new BoolSetting.Builder()
        .name("sneak-on-interact").defaultValue(true)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    private final Setting<Boolean> abortOnLowHealth = sgWalker.add(new BoolSetting.Builder()
        .name("abort-on-low-health").defaultValue(true)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant).build());

    private final Setting<Integer> lowHealthThreshold = sgWalker.add(new IntSetting.Builder()
        .name("low-health-threshold").defaultValue(4).min(1).sliderMax(10)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get() && abortOnLowHealth.get()).build());

    private final Setting<Boolean> abortOnFire = sgWalker.add(new BoolSetting.Builder()
        .name("abort-on-fire").defaultValue(false)
        .visible(() -> moduleMode.get() == ModuleMode.Assistant && walkerEnabled.get()).build());

    // ═══════════════════════════════════════════════════════════════
    // Settings — Pearls Only (Hidden if Default)
    // ═══════════════════════════════════════════════════════════════

    private final Setting<Boolean> poOutlineEnabled = sgPearlsOnly.add(new BoolSetting.Builder()
        .name("outline").description("Renders an outline around the pearl entity.")
        .defaultValue(true).visible(() -> renderMode.get() == RenderMode.PearlsOnly).build());

    private final Setting<SettingColor> poOutlineColor = sgPearlsOnly.add(new ColorSetting.Builder()
        .name("outline-color").defaultValue(new SettingColor(0, 200, 255, 255))
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poOutlineEnabled.get()).build());

    private final Setting<Integer> poGlowStrength = sgPearlsOnly.add(new IntSetting.Builder()
        .name("glow-strength").description("Brightens the outline color channels (1-8).")
        .defaultValue(3).min(1).max(8).sliderMax(8)
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poOutlineEnabled.get()).build());

    private final Setting<Boolean> poOwnOutlineEnabled = sgPearlsOnly.add(new BoolSetting.Builder()
        .name("own-outline").description("Renders an outline around your own pearl.")
        .defaultValue(true).visible(() -> renderMode.get() == RenderMode.PearlsOnly && highlightOwnPearl.get()).build());

    private final Setting<SettingColor> poOwnOutlineColor = sgPearlsOnly.add(new ColorSetting.Builder()
        .name("own-outline-color").defaultValue(new SettingColor(255, 255, 0, 255))
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poOwnOutlineEnabled.get()).build());

    private final Setting<Integer> poOwnGlowStrength = sgPearlsOnly.add(new IntSetting.Builder()
        .name("own-glow-strength").defaultValue(3).min(1).max(8).sliderMax(8)
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poOwnOutlineEnabled.get()).build());

    private final Setting<Boolean> poBeamEnabled = sgPearlsOnly.add(new BoolSetting.Builder()
        .name("beam").description("Renders a beacon beam down to the soul sand.")
        .defaultValue(true).visible(() -> renderMode.get() == RenderMode.PearlsOnly).build());

    private final Setting<SettingColor> poBeamColor = sgPearlsOnly.add(new ColorSetting.Builder()
        .name("beam-color").defaultValue(new SettingColor(255, 140, 0, 200))
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poBeamEnabled.get()).build());

    private final Setting<Double> poBeamInnerRadius = sgPearlsOnly.add(new DoubleSetting.Builder()
        .name("beam-inner-radius").defaultValue(0.1).min(0.01).sliderMax(1.0)
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poBeamEnabled.get()).build());

    private final Setting<Double> poBeamOuterRadius = sgPearlsOnly.add(new DoubleSetting.Builder()
        .name("beam-glow-radius").defaultValue(0.175).min(0.01).sliderMax(2.0)
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poBeamEnabled.get()).build());

    private final Setting<Integer> poBeamHeight = sgPearlsOnly.add(new IntSetting.Builder()
        .name("beam-height").description("How high up the beacon beam shoots.")
        .defaultValue(64).min(1).max(319).sliderMax(319)
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poBeamEnabled.get()).build());

    private final Setting<Boolean> poOwnBeamEnabled = sgPearlsOnly.add(new BoolSetting.Builder()
        .name("own-beam").description("Renders a beacon beam for your own pearl.")
        .defaultValue(true).visible(() -> renderMode.get() == RenderMode.PearlsOnly && highlightOwnPearl.get()).build());

    private final Setting<SettingColor> poOwnBeamColor = sgPearlsOnly.add(new ColorSetting.Builder()
        .name("own-beam-color").defaultValue(new SettingColor(0, 255, 0, 200))
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poOwnBeamEnabled.get()).build());

    private final Setting<Double> poOwnBeamInnerRadius = sgPearlsOnly.add(new DoubleSetting.Builder()
        .name("own-beam-inner-radius").defaultValue(0.1).min(0.01).sliderMax(1.0)
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poOwnBeamEnabled.get()).build());

    private final Setting<Double> poOwnBeamOuterRadius = sgPearlsOnly.add(new DoubleSetting.Builder()
        .name("own-beam-glow-radius").defaultValue(0.175).min(0.01).sliderMax(2.0)
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poOwnBeamEnabled.get()).build());

    private final Setting<Integer> poOwnBeamHeight = sgPearlsOnly.add(new IntSetting.Builder()
        .name("own-beam-height").defaultValue(64).min(1).max(319).sliderMax(319)
        .visible(() -> renderMode.get() == RenderMode.PearlsOnly && poOwnBeamEnabled.get()).build());

    // ═══════════════════════════════════════════════════════════════
    // Settings — Auto Disconnect
    // ═══════════════════════════════════════════════════════════════

    private final Setting<Boolean> disconnectEnabled = sgDisconnect.add(new BoolSetting.Builder()
        .name("disconnect-enabled")
        .description("Disconnects when another player enters render distance.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> ignoreFriendsOnDisconnect = sgDisconnect.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Doesn't disconnect if the entering player is a friend (checked via NeighbourhoodWatch).")
        .defaultValue(true)
        .visible(disconnectEnabled::get)
        .build());

    private final Setting<Boolean> ignoreBotUser = sgDisconnect.add(new BoolSetting.Builder()
        .name("ignore-bot-user")
        .description("Doesn't trigger if the entering player is the configured bot username.")
        .defaultValue(true)
        .visible(disconnectEnabled::get)
        .build());

    private final Setting<String> disconnectUser = sgDisconnect.add(new StringSetting.Builder()
        .name("disconnect-notify-user")
        .description("Username to notify before disconnecting. If offline, the bot leaves immediately. "
                   + "Only active in Bot Assistant mode.")
        .defaultValue("")
        .visible(() -> disconnectEnabled.get() && moduleMode.get() == ModuleMode.Assistant)
        .build());

    private final Setting<String> disconnectMessage = sgDisconnect.add(new StringSetting.Builder()
        .name("disconnect-message")
        .description("Message sent to the notify user. Use {player} for the triggering player's name. "
                   + "Only active in Bot Assistant mode.")
        .defaultValue("Disconnecting — {player} entered render distance.")
        .visible(() -> disconnectEnabled.get() && moduleMode.get() == ModuleMode.Assistant)
        .build());

    private final Setting<Integer> disconnectAntiSpam = sgDisconnect.add(new IntSetting.Builder()
        .name("anti-spam-interval")
        .description("Minimum seconds between outgoing disconnect notifications to prevent chat spam.")
        .defaultValue(5)
        .min(1)
        .sliderMax(60)
        .visible(() -> disconnectEnabled.get() && moduleMode.get() == ModuleMode.Assistant)
        .build());

    // ═══════════════════════════════════════════════════════════════
    // Pearl memory record
    // ═══════════════════════════════════════════════════════════════

    private record PearlRecord(int entityId, String owner, BlockPos trapdoor, long discoveredMs) {}

    // ═══════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════

    private final AtomicReference<Map<String, Vec3d[]>> columnLines = new AtomicReference<>(Collections.emptyMap());
    private final LinkedHashMap<Integer, PearlRecord> pearlMemory = new LinkedHashMap<>();
    private final Set<Integer> seenPearlIds = Collections.synchronizedSet(new HashSet<>());

    private final AtomicBoolean scanPending   = new AtomicBoolean(false);
    private final AtomicBoolean pingQueued    = new AtomicBoolean(false);
    private final AtomicBoolean pullQueued    = new AtomicBoolean(false);
    private final AtomicLong    lastTriggerMs = new AtomicLong(0L);

    private volatile String pendingNotifyTarget = null;
    private boolean wasSelfTriggerPressed = false;
    private int tickCounter = 0;

    private WalkState          walkState    = WalkState.IDLE;
    private BlockPos           walkTarget   = null;
    private Vec3d              idlePosition = null;
    private final Deque<Vec3d> waypoints    = new ArrayDeque<>();
    private int                walkTicks    = 0;
    private Vec3d              lastPos      = null;
    private int                stuckTicks   = 0;
    private int                jumpCooldown = 0;
    
    private final Set<Integer> trackedGlowIds = Collections.synchronizedSet(new HashSet<>());

    // Auto-disconnect anti-spam state
    private volatile long lastDisconnectMsgMs = 0L;
    private volatile boolean disconnectArmed  = true;

    // ═══════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════

    public Waypearl() {
        super(Tim.CATEGORY, "Waypearl", "Detects nearby stasis pearls sitting above bubble columns.");
    }

    // ═══════════════════════════════════════════════════════════════
    // Public accessors
    // ═══════════════════════════════════════════════════════════════

    public int getRange() { return range.get(); }
    public Set<Integer> getSeenPearlIds() { return seenPearlIds; }

    // ═════════════════════════════════════════════════════════════════════
    // Lifecycle

    @Override
    public void onActivate() {
        columnLines.set(Collections.emptyMap());
        synchronized (pearlMemory) { pearlMemory.clear(); }
        seenPearlIds.clear();
        pingQueued.set(false);
        pullQueued.set(false);
        scanPending.set(false);
        lastTriggerMs.set(0L);
        pendingNotifyTarget = null;
        tickCounter = 0;
        resetWalker();
        wasSelfTriggerPressed = false;

        lastDisconnectMsgMs = 0L;
        disconnectArmed = true;

        for (int id : trackedGlowIds) GlowingRegistry.remove(id);
        trackedGlowIds.clear();
        BEAM_POS_CACHE.clear();
    }

    @Override
    public void onDeactivate() {
        columnLines.set(Collections.emptyMap());
        synchronized (pearlMemory) { pearlMemory.clear(); }
        seenPearlIds.clear();
        pingQueued.set(false);
        pullQueued.set(false);
        scanPending.set(false);
        stopMovement();
        resetWalker();

        lastDisconnectMsgMs = 0L;
        disconnectArmed = true;

        for (int id : trackedGlowIds) GlowingRegistry.remove(id);
        trackedGlowIds.clear();
        BEAM_POS_CACHE.clear();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        // Re-arm the disconnect detector after a fresh join/relog
        disconnectArmed = true;
        lastDisconnectMsgMs = 0L;
    }

    // ═══════════════════════════════════════════════════════════════
    // Chat listener
    // ═══════════════════════════════════════════════════════════════

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (moduleMode.get() != ModuleMode.Assistant) return;
        String raw = event.getMessage().getString().trim();

        if (raw.startsWith("<")) {
            int closeAngle = raw.indexOf('>');
            if (closeAngle >= 2) {
                evaluateTrigger(raw.substring(1, closeAngle), raw.substring(closeAngle + 1).trim(), false);
                return;
            }
        }

        if (raw.startsWith("[") && raw.contains("->") && raw.contains("]:")) {
            int arrowIdx   = raw.indexOf("->");
            int bracketEnd = raw.indexOf("]:");
            if (arrowIdx > 1 && bracketEnd > arrowIdx) {
                evaluateTrigger(raw.substring(1, arrowIdx).trim(),
                                raw.substring(bracketEnd + 2).trim(), true);
                return;
            }
        }

        String tag3 = " whispers to you: ";
        int idx3 = raw.indexOf(tag3);
        if (idx3 > 0) {
            evaluateTrigger(raw.substring(0, idx3).trim(),
                            raw.substring(idx3 + tag3.length()).trim(), true);
            return;
        }

        String tag4 = " whispers: ";
        int idx4 = raw.indexOf(tag4);
        if (idx4 > 0) {
            evaluateTrigger(raw.substring(0, idx4).trim(),
                            raw.substring(idx4 + tag4.length()).trim(), true);
        }
    }

    private void evaluateTrigger(String senderName, String content, boolean isWhisper) {
        if (mc.player == null) return;
        String bot = botUsername.get().trim();
        if (bot.isEmpty()) return;

        boolean whitelisted = false;
        for (String name : whitelist.get()) {
            if (name.equals(senderName)) {
                whitelisted = true;
                break;
            }
        }
        if (!whitelisted) return;

        String phraseRaw = triggerPhrase.get().trim();
        if (phraseRaw.isEmpty()) return;
        boolean keywordMatched = false;
        for (String kw : phraseRaw.split(",")) {
            String k = kw.trim();
            if (!k.isEmpty() && content.contains(k)) {
                keywordMatched = true;
                break;
            }
        }
        if (!keywordMatched) return;

        if (!isWhisper && !content.contains(bot)) return;

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastTriggerMs.get() < pullCooldown.get() * 1000L) {
            mc.player.sendMessage(Text.literal("[Waypearl] Cooldown active — ignoring."), false);
            return;
        }

        if (walkState != WalkState.IDLE && walkState != WalkState.ABORTED) {
            mc.player.sendMessage(Text.literal("[Waypearl] Walker active — ignoring."), false);
            return;
        }

        lastTriggerMs.set(nowMs);
        pendingNotifyTarget = senderName;
        pullQueued.set(true);
        mc.player.sendMessage(Text.literal("[Waypearl] Pull triggered by " + senderName + "."), false);
    }

    // ═══════════════════════════════════════════════════════════════
    // Player proximity → Disconnect
    // ═══════════════════════════════════════════════════════════════

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!disconnectEnabled.get()) return;
        if (mc.world == null || mc.player == null) return;
        if (mc.player.networkHandler == null) return;

        if (!(event.entity instanceof PlayerEntity enteringPlayer)) return;
        if (enteringPlayer == mc.player) return;
        if (enteringPlayer.getId() == mc.player.getId()) return;

        String enteringName = enteringPlayer.getName().getString();

        // Ignore the configured bot username
        String botName = botUsername.get().trim();
        if (ignoreBotUser.get() && !botName.isEmpty() && enteringName.equalsIgnoreCase(botName)) return;

        // ── NeighbourhoodWatch integration ──
        // Check if the entering player is on the friends list.
        if (ignoreFriendsOnDisconnect.get()) {
            NeighbourhoodWatch nw = getNeighbourhoodWatch();
            if (nw != null && nw.isActive()) {
                if (nw.isFriend(enteringName)) {
                    return; // Friend detected — do NOT disconnect
                }
            }
        }

        // Anti-spam #2 (armed flag): guarantees only ONE disconnect per encounter,
        // even if multiple players walk in simultaneously.
        if (!disconnectArmed) return;
        disconnectArmed = false;

        long now = System.currentTimeMillis();
        boolean isAssistant = moduleMode.get() == ModuleMode.Assistant;

        if (isAssistant) {
            // ── Bot Assistant mode: send message to custom user before leaving ──
            String targetUser = disconnectUser.get().trim();

            if (targetUser.isEmpty()) {
                info("No target user configured — leaving immediately.");
            } else if (isPlayerOnline(targetUser)) {
                // Anti-spam #1 (time-based): minimum interval between messages
                long minMs = disconnectAntiSpam.get() * 1000L;
                if (now - lastDisconnectMsgMs < minMs) {
                    info("Anti-spam active — skipping message, disconnecting.");
                } else {
                    lastDisconnectMsgMs = now;
                    try {
                        String msg = disconnectMessage.get().replace("{player}", enteringName);
                        mc.player.networkHandler.sendChatMessage("/msg " + targetUser + " " + msg);
                        info("Sent disconnect notice to §b" + targetUser + "§r — "
                            + enteringName + " entered render distance.");
                    } catch (Exception ignored) {
                        // Fall through to disconnect even if the message send fails
                    }
                }
            } else {
                info("Target user §b" + targetUser + "§r is offline — leaving immediately.");
            }
        } else {
            // ── Requester mode: message feature disabled — just disconnect ──
            info("Requester mode — disconnecting without message (" + enteringName + " entered render distance).");
        }

        // Delayed disconnect so the /msg packet flushes before we tear the world down.
        Thread.ofVirtual().name("Waypearl-disconnect").start(() -> {
            try {
                Thread.sleep(150L);
                if (mc.world != null && mc.player != null) {
                    mc.world.disconnect();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private NeighbourhoodWatch getNeighbourhoodWatch() {
        return Modules.get().get(NeighbourhoodWatch.class);
    }

    private boolean isPlayerOnline(String username) {
        if (username == null || username.isEmpty()) return false;
        if (mc.player == null || mc.player.networkHandler == null) return false;

        PlayerListEntry entry = mc.player.networkHandler.getPlayerListEntry(username);
        if (entry != null) return true;

        for (PlayerListEntry ple : mc.player.networkHandler.getPlayerList()) {
            if (ple != null && ple.getProfile() != null
                && username.equalsIgnoreCase(ple.getProfile().getName())) {
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // Tick
    // ═══════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.world.getRegistryKey() == World.NETHER) return;

        boolean selfPressed = selfTriggerKey.get().isPressed();
        if (selfPressed && !wasSelfTriggerPressed && moduleMode.get() == ModuleMode.Requester) {
            if (mc.currentScreen == null && (walkState == WalkState.IDLE || walkState == WalkState.ABORTED)) {
                long now = System.currentTimeMillis();
                if (now - lastTriggerMs.get() >= pullCooldown.get() * 1000L) {
                    sendSelfTrigger();
                }
            }
        }
        wasSelfTriggerPressed = selfPressed;

        if (pingQueued.compareAndSet(true, false) && soundEnabled.get()) {
            mc.getSoundManager().play(PositionedSoundInstance.master(
                pingSound.get().sound,
                soundPitch.get().floatValue(),
                soundVolume.get().floatValue()));
        }

        if (pullQueued.compareAndSet(true, false)) {
            String requester = pendingNotifyTarget;
            sendNotifyMessage(requester);
            executePull(requester);
            pendingNotifyTarget = null;
        }

        tickWalker();
        updatePearlMemory();

        tickCounter++;
        if (tickCounter >= scanInterval.get()) {
            tickCounter = 0;
            triggerColumnScan();
        }
    }

    private void sendSelfTrigger() {
        String bot = botUsername.get().trim();
        if (bot.isEmpty()) return;

        String[] phrases = triggerPhrase.get().split(",");
        List<String> validPhrases = new ArrayList<>();
        for (String p : phrases) {
            String t = p.trim();
            if (!t.isEmpty()) validPhrases.add(t);
        }

        String phrase = validPhrases.isEmpty() ? "tp"
            : validPhrases.get((int) (Math.random() * validPhrases.size()));

        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(chars.charAt((int) (Math.random() * chars.length())));
        }

        mc.player.networkHandler.sendChatCommand("msg " + bot + " " + phrase + " " + sb.toString());
        info("Sent pull request to §6" + bot + " §r(phrase: " + phrase + ").");
    }

    // ═══════════════════════════════════════════════════════════════
    // Pearl memory update
    // ═══════════════════════════════════════════════════════════════

    private void updatePearlMemory() {
        Map<String, Vec3d[]> lines = columnLines.get();
        for (Entity e : mc.world.getEntities()) {
            if (e.getType() != EntityType.ENDER_PEARL) continue;
            if (mc.player.distanceTo(e) > range.get()) continue;

            int    px  = (int) Math.floor(e.getX());
            int    pz  = (int) Math.floor(e.getZ());
            String key = px + "," + pz;
            if (!lines.containsKey(key)) continue;
            if (!seenPearlIds.add(e.getId())) continue;

            Vec3d[] line     = lines.get(key);
            int     topY     = (int) Math.floor(line[1].y);
            BlockPos trapdoor = findTrapdoor(px, topY, pz);

            String ownerName = "unknown";
            if (e instanceof EnderPearlEntity pearl) {
                Entity owner = pearl.getOwner();
                if (owner != null) ownerName = owner.getName().getString();
            }

            synchronized (pearlMemory) {
                pearlMemory.put(e.getId(), new PearlRecord(e.getId(), ownerName, trapdoor,
                    System.currentTimeMillis()));
            }

            pingQueued.set(true);

            String msg = "[Waypearl] New stasis pearl — owner: " + ownerName;
            mc.player.sendMessage(Text.literal(msg), false);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Notify & Pull execution
    // ═══════════════════════════════════════════════════════════════

    private void sendNotifyMessage(String target) {
        if (!notifyEnabled.get()) return;
        if (target == null || target.isEmpty() || mc.player == null) return;
        String msg = notifyMessage.get().replace("{player}", target);
        mc.player.networkHandler.sendChatMessage("/msg " + target + " " + msg);
    }

    private void executePull(String requester) {
        if (mc.world == null || mc.player == null) return;

        String botName = botUsername.get().trim();

        List<PearlRecord> candidates = new ArrayList<>();
        synchronized (pearlMemory) {
            for (PearlRecord rec : pearlMemory.values()) {
                if (rec.trapdoor() == null) continue;
                if (!pearlStillExists(rec.entityId())) continue;
                if (!isAnyTrapdoor(mc.world.getBlockState(rec.trapdoor()).getBlock())) continue;

                if (!botName.isEmpty() && rec.owner().equalsIgnoreCase(botName)) continue;

                candidates.add(rec);
            }
        }

        if (candidates.isEmpty()) candidates = scanLiveTargets();

        if (requester != null) {
            candidates.removeIf(rec ->
                !rec.owner().equalsIgnoreCase(requester) && !rec.owner().equalsIgnoreCase("unknown")
            );
        }

        if (candidates.isEmpty()) {
            mc.player.sendMessage(
                Text.literal("[Waypearl] No valid stasis pearls found for " + (requester != null ? requester : "anyone") + "."), false);
            return;
        }

        Vec3d playerPos = mc.player.getPos();

        candidates.sort((a, b) -> {
            if (requester != null) {
                boolean aMine = a.owner().equalsIgnoreCase(requester);
                boolean bMine = b.owner().equalsIgnoreCase(requester);
                if (aMine && !bMine) return -1;
                if (!aMine && bMine) return 1;
            }

            if (pullOrder.get() == PullOrder.NEAREST) {
                double da = playerPos.squaredDistanceTo(Vec3d.ofCenter(a.trapdoor()));
                double db = playerPos.squaredDistanceTo(Vec3d.ofCenter(b.trapdoor()));
                return Double.compare(da, db);
            }

            return 0;
        });

        dispatchTarget(candidates.get(0).trapdoor());
    }

    private boolean pearlStillExists(int entityId) {
        for (Entity e : mc.world.getEntities()) {
            if (e.getId() == entityId && e.getType() == EntityType.ENDER_PEARL) return true;
        }
        return false;
    }

    private List<PearlRecord> scanLiveTargets() {
        List<PearlRecord> found = new ArrayList<>();
        Map<String, Vec3d[]> lines = columnLines.get();
        String botName = botUsername.get().trim();

        for (Entity e : mc.world.getEntities()) {
            if (e.getType() != EntityType.ENDER_PEARL) continue;
            if (mc.player.distanceTo(e) > range.get()) continue;

            String ownerName = "unknown";
            if (e instanceof EnderPearlEntity pearl) {
                Entity owner = pearl.getOwner();
                if (owner != null) ownerName = owner.getName().getString();
            }

            if (!botName.isEmpty() && ownerName.equalsIgnoreCase(botName)) continue;

            int    px  = (int) Math.floor(e.getX());
            int    pz  = (int) Math.floor(e.getZ());
            String key = px + "," + pz;
            Vec3d[] line = lines.get(key);
            if (line == null) continue;

            int topY = (int) Math.floor(line[1].y);
            BlockPos trapdoor = findTrapdoor(px, topY, pz);
            if (trapdoor == null) continue;

            found.add(new PearlRecord(e.getId(), ownerName, trapdoor, 0L));
        }
        return found;
    }

    private void dispatchTarget(BlockPos trapdoor) {
        double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(trapdoor));
        if (dist <= interactReach.get()) {
            interactTrapdoor(trapdoor);
        } else if (walkerEnabled.get()) {
            beginWalk(trapdoor);
        } else {
            mc.player.sendMessage(
                Text.literal("[Waypearl] Trapdoor out of reach and walker is disabled."), false);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Walker — state machine
    // ═══════════════════════════════════════════════════════════════

    private void beginWalk(BlockPos target) {
        walkTarget   = target;
        idlePosition = mc.player.getPos();
        walkState    = WalkState.WALKING_TO;
        walkTicks    = 0;
        stuckTicks   = 0;
        jumpCooldown = 0;
        lastPos      = idlePosition;
        waypoints.clear();

        List<Vec3d> path = findPath(mc.player.getBlockPos(), target);
        if (path != null) {
            waypoints.addAll(path);
        } else {
            abortWalk("No clear path to trapdoor found.");
            return;
        }

        String msg = "[Waypearl] Path found (" + waypoints.size() + " waypoints).";
        mc.player.sendMessage(Text.literal(msg), false);
    }

    private void tickWalker() {
        if (walkState == WalkState.IDLE || walkState == WalkState.ABORTED) return;
        if (mc.player == null || mc.world == null) {
            abortWalk("Player/world null.");
            return;
        }

        Vec3d pos = mc.player.getPos();

        if (pos.y < voidAbortY.get()) {
            abortWalk("Fell below void-abort Y (" + voidAbortY.get() + ").");
            return;
        }

        if (abortOnLowHealth.get() && mc.player.getHealth() <= lowHealthThreshold.get() * 2f) {
            abortWalk("Health low (" + String.format("%.1f", mc.player.getHealth()) + ").");
            return;
        }

        if (mc.player.isOnFire()) {
            if (abortOnFire.get()) {
                abortWalk("Player on fire.");
                return;
            }
            mc.player.sendMessage(Text.literal("[Waypearl] Warning: on fire."), false);
        }

        if (jumpCooldown > 0) jumpCooldown--;

        switch (walkState) {
            case WALKING_TO -> {
                walkTicks++;

                if (walkTicks > walkTimeoutTicks.get()) {
                    abortWalk("Timed out after " + walkTicks + " ticks.");
                    return;
                }

                if (lastPos != null) {
                    double moved = xzDist(pos, lastPos);
                    if (moved < stuckMovementMin.get()) {
                        stuckTicks++;
                        if (walkerJump.get()
                                && stuckTicks >= jumpAttemptTicks.get()
                                && jumpCooldown == 0
                                && mc.player.isOnGround()) {
                            mc.player.jump();
                            jumpCooldown = jumpCooldownTicks.get();
                            stuckTicks = 0;
                        }
                        if (stuckTicks >= stuckThresholdTicks.get()) {
                            abortWalk("Stuck for " + stuckTicks + " ticks.");
                            return;
                        }
                    } else {
                        stuckTicks = 0;
                    }
                }
                lastPos = pos;

                if (walkTarget != null
                        && !isAnyTrapdoor(mc.world.getBlockState(walkTarget).getBlock())) {
                    abortWalk("Trapdoor vanished.");
                    return;
                }

                Vec3d currentWaypoint = waypoints.peek();
                if (currentWaypoint == null) {
                    stopMovement();
                    walkState = WalkState.PULLING;
                    walkTicks = 0;
                    return;
                }

                double distToWaypoint = pos.distanceTo(currentWaypoint);
                boolean isFinalWaypoint = waypoints.size() == 1;

                if (isFinalWaypoint && distToWaypoint <= interactReach.get()) {
                    stopMovement();
                    walkState = WalkState.PULLING;
                    walkTicks = 0;
                    return;
                }

                double advanceThreshold = isFinalWaypoint ? interactReach.get() : waypointSpacing.get() * 0.5;
                if (distToWaypoint <= advanceThreshold) {
                    waypoints.poll();
                    return;
                }

                double distToFinal = walkTarget != null
                    ? pos.distanceTo(Vec3d.ofCenter(walkTarget)) : distToWaypoint;
                boolean inSlowZone = distToFinal <= slowZoneRadius.get();
                faceAndWalkToward(pos, currentWaypoint, !inSlowZone);
            }

            case PULLING -> {
                stopMovement();
                if (sneakOnInteract.get()) {
                    mc.options.sneakKey.setPressed(true);
                    mc.player.setSneaking(true);
                }
                interactTrapdoor(walkTarget);
                if (sneakOnInteract.get()) {
                    mc.options.sneakKey.setPressed(false);
                    mc.player.setSneaking(false);
                }

                if (returnAfterPull.get() && idlePosition != null) {
                    walkState  = WalkState.WALKING_BACK;
                    walkTicks  = 0;
                    stuckTicks = 0;
                    lastPos    = mc.player.getPos();
                    buildReturnWaypoints();
                    mc.player.sendMessage(Text.literal("[Waypearl] Returning to idle."), false);
                } else {
                    walkState = WalkState.IDLE;
                    resetWalkerFields();
                }
            }

            case WALKING_BACK -> {
                walkTicks++;

                if (walkTicks > returnTimeoutTicks.get()) {
                    mc.player.sendMessage(
                        Text.literal("[Waypearl] Return timed out; stopping here."), false);
                    stopMovement();
                    walkState = WalkState.IDLE;
                    resetWalkerFields();
                    return;
                }

                if (lastPos != null) {
                    double moved = xzDist(pos, lastPos);
                    if (moved < stuckMovementMin.get()) {
                        stuckTicks++;
                        if (walkerJump.get()
                                && stuckTicks >= jumpAttemptTicks.get()
                                && jumpCooldown == 0
                                && mc.player.isOnGround()) {
                            mc.player.jump();
                            jumpCooldown = jumpCooldownTicks.get();
                            stuckTicks = 0;
                        }
                        if (stuckTicks >= stuckThresholdTicks.get()) {
                            mc.player.sendMessage(
                                Text.literal("[Waypearl] Stuck returning; stopping here."), false);
                            stopMovement();
                            walkState = WalkState.IDLE;
                            resetWalkerFields();
                            return;
                        }
                    } else {
                        stuckTicks = 0;
                    }
                }
                lastPos = pos;

                Vec3d currentWaypoint = waypoints.peek();
                if (currentWaypoint == null) {
                    snapToIdle();
                    return;
                }

                double distToWaypoint = pos.distanceTo(currentWaypoint);
                boolean isFinal = waypoints.size() == 1;

                if (isFinal && distToWaypoint <= returnTolerance.get()) {
                    snapToIdle();
                    return;
                }

                double advanceThreshold = isFinal ? returnTolerance.get() : waypointSpacing.get() * 0.5;
                if (distToWaypoint <= advanceThreshold) {
                    waypoints.poll();
                    return;
                }

                double distToHome = pos.distanceTo(idlePosition);
                boolean inSlowZone = distToHome <= slowZoneRadius.get();
                faceAndWalkToward(pos, currentWaypoint, !inSlowZone);
            }

            default -> {}
        }
    }

    private void snapToIdle() {
        stopMovement();
        mc.player.setPosition(idlePosition.x, idlePosition.y, idlePosition.z);
        mc.player.sendMessage(Text.literal("[Waypearl] Returned to idle position."), false);
        walkState = WalkState.IDLE;
        resetWalkerFields();
    }

    private void buildReturnWaypoints() {
        waypoints.clear();
        if (idlePosition == null) return;

        List<Vec3d> path = findPath(mc.player.getBlockPos(), BlockPos.ofFloored(idlePosition));
        if (path != null) waypoints.addAll(path);
    }

    private List<Vec3d> findPath(BlockPos start, BlockPos end) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();

        queue.add(start);
        visited.add(start);

        BlockPos currentEnd = null;
        int iterations = 0;

        while (!queue.isEmpty() && iterations < 1500) {
            iterations++;
            BlockPos curr = queue.poll();

            if (curr.getSquaredDistance(end) <= interactReach.get() * interactReach.get()) {
                currentEnd = curr;
                break;
            }

            for (Direction dir : Direction.values()) {
                if (dir.getAxis().isHorizontal()) {
                    BlockPos next = curr.offset(dir);
                    if (isWalkable(next) && !visited.contains(next)) {
                        visited.add(next);
                        parents.put(next, curr);
                        queue.add(next);
                    }
                    else if (isPassable(curr.up(2)) && isWalkable(next.up()) && !visited.contains(next.up())) {
                        BlockPos up = next.up();
                        visited.add(up);
                        parents.put(up, curr);
                        queue.add(up);
                    }
                    else if (isPassable(next) && isPassable(next.up())) {
                        BlockPos drop = next.down();
                        if (isWalkable(drop) && !visited.contains(drop)) {
                            visited.add(drop);
                            parents.put(drop, curr);
                            queue.add(drop);
                        }
                    }
                }
            }
        }

        if (currentEnd == null) return null;

        List<Vec3d> path = new ArrayList<>();
        BlockPos p = currentEnd;
        while (p != null) {
            path.add(Vec3d.ofCenter(p));
            p = parents.get(p);
        }
        Collections.reverse(path);
        return path;
    }

    private boolean isPassable(BlockPos pos) {
        return mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
    }

    private boolean isWalkable(BlockPos pos) {
        return isPassable(pos) && isPassable(pos.up()) && !isPassable(pos.down());
    }

    private void faceAndWalkToward(Vec3d from, Vec3d to, boolean sprint) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;

        float pYaw = MathHelper.wrapDegrees(mc.player.getYaw());
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float diff = MathHelper.wrapDegrees(targetYaw - pYaw);

        mc.options.forwardKey.setPressed(diff > -67.5 && diff <= 67.5);
        mc.options.backKey.setPressed(diff > 112.5 || diff <= -112.5);
        mc.options.leftKey.setPressed(diff > -157.5 && diff <= -22.5);
        mc.options.rightKey.setPressed(diff > 22.5 && diff <= 157.5);

        boolean doSprint = walkerSprint.get() && sprint;
        mc.options.sprintKey.setPressed(doSprint);
        mc.player.setSprinting(doSprint);
    }

    private void stopMovement() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        if (mc.player != null) {
            mc.player.setSprinting(false);
            mc.player.setSneaking(false);
        }
    }

    private void abortWalk(String reason) {
        stopMovement();
        walkState = WalkState.ABORTED;
        resetWalkerFields();
        if (mc.player != null)
            mc.player.sendMessage(Text.literal("[Waypearl] Walker aborted: " + reason), false);
    }

    private void resetWalkerFields() {
        walkTarget   = null;
        idlePosition = null;
        walkTicks    = 0;
        stuckTicks   = 0;
        jumpCooldown = 0;
        lastPos      = null;
        waypoints.clear();
    }

    private void resetWalker() {
        walkState = WalkState.IDLE;
        resetWalkerFields();
    }

    // ═══════════════════════════════════════════════════════════════
    // Trapdoor helpers
    // ═════════════════════════════════════════════════════════════════

    private BlockPos findTrapdoor(int x, int topY, int z) {
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos pos = new BlockPos(x, topY + dy, z);
            if (isAnyTrapdoor(mc.world.getBlockState(pos).getBlock())) return pos;
        }
        return null;
    }

    private boolean isAnyTrapdoor(Block block) {
        return block instanceof TrapdoorBlock
            || block == Blocks.OAK_TRAPDOOR      || block == Blocks.SPRUCE_TRAPDOOR
            || block == Blocks.BIRCH_TRAPDOOR    || block == Blocks.JUNGLE_TRAPDOOR
            || block == Blocks.ACACIA_TRAPDOOR   || block == Blocks.DARK_OAK_TRAPDOOR
            || block == Blocks.MANGROVE_TRAPDOOR || block == Blocks.CHERRY_TRAPDOOR
            || block == Blocks.BAMBOO_TRAPDOOR || block == Blocks.CRIMSON_TRAPDOOR
            || block == Blocks.WARPED_TRAPDOOR   || block == Blocks.IRON_TRAPDOOR;
    }

    private void interactTrapdoor(BlockPos pos) {
        if (pos == null) return;
        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
            Vec3d          hitVec = Vec3d.ofCenter(pos);
            BlockHitResult hit    = new BlockHitResult(hitVec, Direction.UP, pos, false);
            ActionResult   result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);

            String base = result.isAccepted()
                ? "[Waypearl] Trapdoor activated."
                : "[Waypearl] Interaction failed.";
            mc.player.sendMessage(Text.literal(base), false);
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // Background column scan
    // ═══════════════════════════════════════════════════════════════

    private void triggerColumnScan() {
        if (!scanPending.compareAndSet(false, true)) return;

        final BlockPos origin   = mc.player.getBlockPos();
        final int      r        = range.get();
        final int      chunkR   = (r >> 4) + 1;
        final int      originCX = origin.getX() >> 4;
        final int      originCZ = origin.getZ() >> 4;

        final Map<Long, WorldChunk> snapshot = new HashMap<>();
        for (int cx = originCX - chunkR; cx <= originCX + chunkR; cx++) {
            for (int cz = originCZ - chunkR; cz <= originCZ + chunkR; cz++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk != null) snapshot.put(ChunkPos.toLong(cx, cz), chunk);
            }
        }

        Thread.ofVirtual().name("Waypearl-scan").start(() -> {
            try { runColumnScan(origin, r, snapshot); }
            finally { scanPending.set(false); }
        });
    }

    private void runColumnScan(BlockPos origin, int r, Map<Long, WorldChunk> chunks) {
        Map<String, int[]> yExtents = new HashMap<>();
        Map<String, Vec3d[]> newLines = new LinkedHashMap<>();

        int minX = origin.getX() - r, maxX = origin.getX() + r,
            minY = Math.max(origin.getY() - r, -64),
            maxY = Math.min(origin.getY() + r, 320),
            minZ = origin.getZ() - r, maxZ = origin.getZ() + r;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                WorldChunk chunk = chunks.get(ChunkPos.toLong(x >> 4, z >> 4));
                if (chunk == null) continue;

                int lowestY = Integer.MAX_VALUE, highestY = Integer.MIN_VALUE;
                for (int y = minY; y <= maxY; y++) {
                    if (chunk.getBlockState(new BlockPos(x, y, z)).getBlock()
                            != Blocks.BUBBLE_COLUMN) continue;
                    if (y < lowestY)  lowestY  = y;
                    if (y > highestY) highestY = y;
                }
                if (lowestY == Integer.MAX_VALUE) continue;

                int srcY = lowestY - 1, srcFloor = Math.max(srcY - 384, -64);
                while (srcY >= srcFloor &&
                    chunk.getBlockState(new BlockPos(x, srcY, z)).getBlock() == Blocks.BUBBLE_COLUMN)
                    srcY--;

                if (chunk.getBlockState(new BlockPos(x, srcY, z)).getBlock() != Blocks.SOUL_SAND)
                    continue;

                String key = x + "," + z;
                int fx = x, fz = z, flo = lowestY, fhi = highestY;
                yExtents.compute(key, (k, e) -> {
                    if (e == null) return new int[]{ fx, flo, fhi, fz };
                    e[1] = Math.min(e[1], flo);
                    e[2] = Math.max(e[2], fhi);
                    return e;
                });
            }
        }

        for (Map.Entry<String, int[]> entry : yExtents.entrySet()) {
            int[] e = entry.getValue();
            newLines.put(entry.getKey(), new Vec3d[]{
                new Vec3d(e[0] + 0.5, e[1],     e[3] + 0.5),
                new Vec3d(e[0] + 0.5, e[2] + 1, e[3] + 0.5)
            });
        }
        columnLines.set(newLines);
    }

    // ═══════════════════════════════════════════════════════════════
    // Render
    // ═══════════════════════════════════════════════════════════════
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        if (renderMode.get() == RenderMode.Default) {
            renderDefaultMode(event);
        } else {
            renderPearlsOnlyMode(event);
        }
    }

    private void renderDefaultMode(Render3DEvent event) {
        boolean     doBeam = columnsEnabled.get();
        CapPosition cap    = capPosition.get();
        boolean     doCap  = cap != CapPosition.NONE;
        if (!doBeam && !doCap) return;

        int r = range.get();
        Map<String, Vec3d[]> lines = columnLines.get();
        
        Map<String, Vec3d> activePearlPos = new HashMap<>();
        Set<Integer> activePearlIds = new HashSet<>();

        for (Entity e : mc.world.getEntities()) {
            if (e.getType() != EntityType.ENDER_PEARL) continue;
            if (mc.player.distanceTo(e) > r) continue;

            Vec3d pos = e.getLerpedPos(mc.getRenderTickCounter().getTickDelta(true));
            int    px  = (int) Math.floor(e.getX());
            int    pz  = (int) Math.floor(e.getZ());
            String key = px + "," + pz;
            Vec3d[] line = lines.get(key);

            if (line != null && pos.y >= line[0].y) {
                activePearlPos.put(key, pos);
                activePearlIds.add(e.getId());
            }
        }

        SettingColor core      = coreColor.get();
        SettingColor glow      = glowColor.get();
        double       halfCore  = coreWidth.get();
        double       spread    = glowSpread.get();
        int          layers    = glowLayers.get();
        int          baseAlpha = glowBaseAlpha.get();

        SettingColor capCol   = capColor.get();
        double       capHalf  = capSize.get();
        double       capThick = capThickness.get();
        ShapeMode    capMode  = capShapeMode.get();
        boolean      capBloom = capGlow.get();

        for (Map.Entry<String, Vec3d[]> entry : lines.entrySet()) {
            Vec3d pearlPos = activePearlPos.get(entry.getKey());
            if (pearlPos == null) continue;

            Vec3d[] line = entry.getValue();
            
            double cx = pearlPos.x;
            double cz = pearlPos.z;
            double botY = line[0].y;
            double topY = pearlPos.y;

            if (doBeam) {
                drawGlowBeam(event, cx, botY, cz, topY, core, glow,
                    halfCore, spread, layers, baseAlpha);
            }

            if (doCap) {
                boolean db = (cap == CapPosition.BOTTOM || cap == CapPosition.BOTH);
                boolean dt = (cap == CapPosition.TOP    || cap == CapPosition.BOTH);
                if (db) {
                    drawCapBox(event, cx, botY, cz, capHalf, capThick, capCol,
                            capMode, capBloom, spread, layers, baseAlpha);
                }
                if (dt) {
                    drawCapBox(event, cx, topY, cz, capHalf, capThick, capCol,
                            capMode, capBloom, spread, layers, baseAlpha);
                }
            }
        }

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof EnderPearlEntity pearl)) continue;
            if (mc.player.distanceTo(e) > r) continue;
            
            if (!activePearlIds.contains(pearl.getId())) continue;

            boolean isOwn = pearl.getOwner() != null && pearl.getOwner().equals(mc.player);

            if (isOwn && highlightOwnPearl.get()) {
                GlowingRegistry.add(pearl.getId(), buildGlowArgb(ownPearlColor.get(), 3));
                trackedGlowIds.add(pearl.getId());
            } else if (!isOwn) {
                GlowingRegistry.add(pearl.getId(), buildGlowArgb(coreColor.get(), 3));
                trackedGlowIds.add(pearl.getId());
            }
        }

        for (Iterator<Integer> it = trackedGlowIds.iterator(); it.hasNext(); ) {
            int id = it.next();
            if (!activePearlIds.contains(id) || mc.world.getEntityById(id) == null) {
                GlowingRegistry.remove(id);
                it.remove();
            }
        }
    }

    private void renderPearlsOnlyMode(Render3DEvent event) {
        int r = range.get();
        Set<Integer> activePearlIds = new HashSet<>();

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof EnderPearlEntity pearl)) continue;
            if (mc.player.distanceTo(e) > r) continue;

            int pearlId = pearl.getId();
            activePearlIds.add(pearlId);
            boolean isOwn = pearl.getOwner() != null && pearl.getOwner().equals(mc.player);

            if (isOwn && highlightOwnPearl.get()) {
                if (poOwnOutlineEnabled.get()) {
                    GlowingRegistry.add(pearlId, buildGlowArgb(poOwnOutlineColor.get(), poOwnGlowStrength.get()));
                    trackedGlowIds.add(pearlId);
                } else {
                    GlowingRegistry.remove(pearlId);
                    trackedGlowIds.remove(pearlId);
                }
            } else {
                if (poOutlineEnabled.get()) {
                    GlowingRegistry.add(pearlId, buildGlowArgb(poOutlineColor.get(), poGlowStrength.get()));
                    trackedGlowIds.add(pearlId);
                } else {
                    GlowingRegistry.remove(pearlId);
                    trackedGlowIds.remove(pearlId);
                }
            }

            if (isInBubbleColumn(pearl)) {
                if (isOwn && highlightOwnPearl.get()) {
                    if (poOwnBeamEnabled.get()) {
                        renderPearlsOnlyBeam(event, pearl, poOwnBeamColor.get(),
                                poOwnBeamInnerRadius.get().floatValue(), poOwnBeamOuterRadius.get().floatValue(),
                                poOwnBeamHeight.get());
                    }
                } else {
                    if (poBeamEnabled.get()) {
                        renderPearlsOnlyBeam(event, pearl, poBeamColor.get(),
                                poBeamInnerRadius.get().floatValue(), poBeamOuterRadius.get().floatValue(),
                                poBeamHeight.get());
                    }
                }
            }
        }

        for (Iterator<Integer> it = trackedGlowIds.iterator(); it.hasNext(); ) {
            int id = it.next();
            if (!activePearlIds.contains(id) || mc.world.getEntityById(id) == null) {
                GlowingRegistry.remove(id);
                it.remove();
            }
        }

        BEAM_POS_CACHE.keySet().removeIf(id -> mc.world.getEntityById(id) == null);
    }

    // ═══════════════════════════════════════════════════════════════
    // Render Helpers
    // ═══════════════════════════════════════════════════════════════

    private int buildGlowArgb(SettingColor c, int strength) {
        float factor = 1f + (strength - 1) * 0.15f;
        int r = Math.min(255, (int)(c.r * factor));
        int g = Math.min(255, (int)(c.g * factor));
        int b = Math.min(255, (int)(c.b * factor));
        return (255 << 24) | (r << 16) | (g << 8) | b;
    }

    private boolean isInBubbleColumn(EnderPearlEntity pearl) {
        if (mc.world == null) return false;
        BlockPos pos = BlockPos.ofFloored(pearl.getPos());
        return mc.world.getBlockState(pos).isOf(Blocks.BUBBLE_COLUMN)
            || mc.world.getBlockState(pos.down()).isOf(Blocks.BUBBLE_COLUMN);
    }

    private Vec3d getOrCreateBeamPos(EnderPearlEntity pearl) {
        return BEAM_POS_CACHE.computeIfAbsent(pearl.getId(), id -> {
            double x = pearl.getX();
            double y = pearl.getY();
            double z = pearl.getZ();
            BlockPos.Mutable pos = new BlockPos.Mutable();

            for (int i = 1; i < 256; i++) {
                pos.set((int) Math.floor(x), (int) (y - i), (int) Math.floor(z));
                BlockState state = mc.world.getBlockState(pos);

                if (!state.isOf(Blocks.WATER) && !state.isOf(Blocks.BUBBLE_COLUMN)) {
                    return new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                }
            }
            return new Vec3d(Math.floor(x) + 0.5, y - 256, Math.floor(z) + 0.5);
        });
    }

    private void renderPearlsOnlyBeam(Render3DEvent event, EnderPearlEntity pearl,
                                      SettingColor color, float innerRadius, float outerRadius, int height) {
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d camPos = camera.getPos();
        Vec3d base = getOrCreateBeamPos(pearl);

        MatrixStack matrices = event.matrices;
        matrices.push();

        matrices.translate(
            base.x - camPos.x - 0.5,
            base.y - camPos.y,
            base.z - camPos.z - 0.5
        );

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();

        BeaconBlockEntityRenderer.renderBeam(
            matrices, immediate, BEAM_TEXTURE, event.tickDelta,
            1.0f, mc.world.getTime(), 0, height,
            color.getPacked(), innerRadius, outerRadius
        );

        immediate.draw();
        matrices.pop();
    }

    private void drawGlowBeam(Render3DEvent event, double cx, double botY, double cz, double topY,
            SettingColor core, SettingColor glow, double halfCore,
            double spread, int layers, int baseAlpha) {
        for (int i = layers; i >= 1; i--) {
            double exp   = spread * i;
            int    alpha = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i - 1) / layers)));
            event.renderer.box(new Box(cx - halfCore - exp, botY, cz - halfCore - exp,
                                       cx + halfCore + exp, topY, cz + halfCore + exp),
                withAlpha(glow, alpha), withAlpha(glow, 0), ShapeMode.Sides, 0);
        }
        event.renderer.box(new Box(cx - halfCore, botY, cz - halfCore,
                                   cx + halfCore, topY, cz + halfCore),
            withAlpha(core, 180), core, ShapeMode.Both, 0);
    }

    private void drawCapBox(Render3DEvent event, double cx, double y, double cz,
            double halfXZ, double halfY, SettingColor color, ShapeMode mode,
            boolean bloom, double spread, int layers, int baseAlpha) {
        double minY = y - halfY, maxY = y + halfY;
        if (bloom) {
            for (int i = layers; i >= 1; i--) {
                double exp   = spread * i;
                int    alpha = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i - 1) / layers)));
                event.renderer.box(new Box(cx - halfXZ - exp, minY, cz - halfXZ - exp,
                                           cx + halfXZ + exp, maxY, cz + halfXZ + exp),
                    withAlpha(color, alpha), withAlpha(color, 0), ShapeMode.Sides, 0);
            }
        }
        event.renderer.box(new Box(cx - halfXZ, minY, cz - halfXZ,
                                   cx + halfXZ, maxY, cz + halfXZ),
            withAlpha(color, color.a), color, mode, 0);
    }

    // ═══════════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════════

    private double xzDist(Vec3d a, Vec3d b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private SettingColor withAlpha(SettingColor c, int alpha) {
        return new SettingColor(c.r, c.g, c.b, Math.min(255, Math.max(0, alpha)));
    }
}