package com.example.addon.hud;
import com.example.addon.Tim;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.ArrayList;
import java.util.List;

public class StatisticsInformation extends HudElement {

    public static final HudElementInfo<StatisticsInformation> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP,
        "statistics-information",
        "Provides a comprehensive display of real-time performance metrics, navigational data, and session-specific statistics.",
        StatisticsInformation::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // ── Settings ──────────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // Performance
    private final Setting<Boolean> showSpeed = sgGeneral.add(new BoolSetting.Builder()
        .name("show-speed")
        .description("Show player speed.")
        .defaultValue(true)
        .build()
    );

    public enum SpeedUnit { Both, BPS, KMH }

    private final Setting<SpeedUnit> speedUnit = sgGeneral.add(new EnumSetting.Builder<SpeedUnit>()
        .name("speed-unit")
        .description("Which speed unit(s) to display.")
        .defaultValue(SpeedUnit.Both)
        .visible(showSpeed::get)
        .build()
    );

    private final Setting<Boolean> showFps = sgGeneral.add(new BoolSetting.Builder()
        .name("show-fps")
        .description("Show current FPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTps = sgGeneral.add(new BoolSetting.Builder()
        .name("show-tps")
        .description("Show server TPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showPing = sgGeneral.add(new BoolSetting.Builder()
        .name("show-ping")
        .description("Show your ping to the server.")
        .defaultValue(true)
        .build()
    );

    // Location
    public enum CoordinateDisplay { Show, Hidden }

    private final Setting<CoordinateDisplay> coordinateDisplay = sgGeneral.add(new EnumSetting.Builder<CoordinateDisplay>()
        .name("coordinates")
        .description("Whether to show your current coordinates.")
        .defaultValue(CoordinateDisplay.Hidden)
        .build()
    );

    private final Setting<Boolean> showDirection = sgGeneral.add(new BoolSetting.Builder()
        .name("show-direction")
        .description("Show the direction you are facing (cardinal + yaw).")
        .defaultValue(true)
        .build()
    );

    public enum DirectionFormat { Cardinal, Yaw, Both }

    private final Setting<DirectionFormat> directionFormat = sgGeneral.add(new EnumSetting.Builder<DirectionFormat>()
        .name("direction-format")
        .description("How to display facing direction.")
        .defaultValue(DirectionFormat.Both)
        .visible(showDirection::get)
        .build()
    );

    // System & World
    private final Setting<Boolean> showMemory = sgGeneral.add(new BoolSetting.Builder()
        .name("show-memory")
        .description("Show JVM memory usage (used / max MB).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> memoryColorCode = sgGeneral.add(new BoolSetting.Builder()
        .name("memory-color-code")
        .description("Color the memory value yellow above 75% and red above 90% usage.")
        .defaultValue(true)
        .visible(showMemory::get)
        .build()
    );

    private final Setting<Boolean> showChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("show-chunks")
        .description("Show number of loaded chunks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showPlayerCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-player-count")
        .description("Show number of players on the server.")
        .defaultValue(true)
        .build()
    );

    // Session
    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("show-distance")
        .description("Show total distance traveled since login.")
        .defaultValue(true)
        .build()
    );

    public enum DistanceUnit { Blocks, Km, Both }

    private final Setting<DistanceUnit> distanceUnit = sgGeneral.add(new EnumSetting.Builder<DistanceUnit>()
        .name("distance-unit")
        .description("Which unit(s) to display for distance traveled.")
        .defaultValue(DistanceUnit.Both)
        .visible(showDistance::get)
        .build()
    );

    private final Setting<Boolean> distanceIncludeY = sgGeneral.add(new BoolSetting.Builder()
        .name("distance-include-y")
        .description("Include vertical movement in the distance calculation.")
        .defaultValue(false)
        .visible(showDistance::get)
        .build()
    );

    private final Setting<Boolean> showTimeOnline = sgGeneral.add(new BoolSetting.Builder()
        .name("show-time-online")
        .description("Show time spent online since joining the server.")
        .defaultValue(true)
        .build()
    );

    public enum TimeFormat { HMS, HM, Seconds }

    private final Setting<TimeFormat> timeFormat = sgGeneral.add(new EnumSetting.Builder<TimeFormat>()
        .name("time-format")
        .description("How to display the time online. HMS: 1h 23m 45s  HM: 1h 23m  Seconds: 5025s")
        .defaultValue(TimeFormat.HMS)
        .visible(showTimeOnline::get)
        .build()
    );

    // Safety & Stability
    private final Setting<Boolean> showStability = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stability")
        .description("Show connection stability based on time since last server tick.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> tpsGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("tps-guard")
        .description("Show a warning if TPS is too low for safe movement through unloaded chunks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> tpsGuardThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("tps-guard-threshold")
        .description("TPS threshold for the safety warning.")
        .defaultValue(15.0).min(1.0).max(20.0)
        .visible(tpsGuard::get)
        .build()
    );

    private final Setting<Boolean> tpsGuardHideStatic = sgGeneral.add(new BoolSetting.Builder()
        .name("tps-guard-hide-stationary")
        .description("Hide the TPS guard warning when the player is not moving.")
        .defaultValue(false)
        .visible(tpsGuard::get)
        .build()
    );

    // Visuals
    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0)
        .min(0.25)
        .sliderRange(0.25, 4.0)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("separator-color")
        .description("Color of the | separator between combined stats.")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background highlight behind the element.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    public enum Alignment { Left, Center, Right }

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align text to the left, center, or right.")
        .defaultValue(Alignment.Left)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────────

    private double distanceTraveled = 0.0;
    private double prevX = Double.MAX_VALUE;
    private double prevY = Double.MAX_VALUE;
    private double prevZ = Double.MAX_VALUE;
    private long sessionStartMs = -1;

    public StatisticsInformation() {
        super(INFO);
    }

    // ── Render Data Structures ────────────────────────────────────────────────────

    private record Segment(String text, Color color) {}
    private record Line(List<Segment> segments) {}

    // ── Tick ──────────────────────────────────────────────────────────────────────

    @Override
    public void tick(HudRenderer renderer) {
        if (mc.player == null) {
            prevX = Double.MAX_VALUE;
            prevY = Double.MAX_VALUE;
            prevZ = Double.MAX_VALUE;
            distanceTraveled = 0.0;
            sessionStartMs = -1;
            return;
        }

        if (sessionStartMs < 0) sessionStartMs = System.currentTimeMillis();

        double cx = mc.player.getX();
        double cy = mc.player.getY();
        double cz = mc.player.getZ();

        if (prevX != Double.MAX_VALUE) {
            double dx = cx - prevX;
            double dz = cz - prevZ;
            double dy = distanceIncludeY.get() ? (cy - prevY) : 0.0;
            distanceTraveled += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        prevX = cx;
        prevY = cy;
        prevZ = cz;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        double s = scale.get();
        double padH = 4 * s;
        double padV = 2 * s;
        double rowGap = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double sepW = renderer.textWidth(" | ", false, s);

        List<Line> lines = new ArrayList<>();

        // Build all lines dynamically
        addSpeedLine(lines);
        addPerformanceLine(lines, sepW);
        addDirectionLine(lines);
        addCoordsLine(lines);
        addMemoryLine(lines);
        addWorldLine(lines, sepW);
        addDistanceLine(lines);
        addTimeLine(lines);
        addStabilityLine(lines);
        addTpsGuardLine(lines);

        if (lines.isEmpty()) {
            setSize(0, 0);
            return;
        }

        // Measure max width
        double maxW = 0;
        for (Line line : lines) {
            double w = 0;
            for (Segment seg : line.segments) {
                w += renderer.textWidth(seg.text, false, s);
            }
            if (w > maxW) maxW = w;
        }

        double totalW = maxW + padH * 2;
        double totalH = lines.size() * lineHeight + (lines.size() - 1) * rowGap + padV * 2;

        // Draw unified background
        if (showBackground.get()) {
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());
        }

        // Draw lines
        Alignment align = alignment.get();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            
            double lineW = 0;
            for (Segment seg : line.segments) {
                lineW += renderer.textWidth(seg.text, false, s);
            }

            double startX;
            if (align == Alignment.Center) {
                startX = x + (totalW - lineW) / 2.0;
            } else if (align == Alignment.Right) {
                startX = x + totalW - padH - lineW;
            } else {
                startX = x + padH;
            }

            double cy = y + padV + i * (lineHeight + rowGap);

            double cx = startX;
            for (Segment seg : line.segments) {
                renderer.text(seg.text, cx, cy, seg.color, false, s);
                cx += renderer.textWidth(seg.text, false, s);
            }
        }

        setSize(totalW, totalH);
    }

    // ── Line Builders ─────────────────────────────────────────────────────────────

    private void addSpeedLine(List<Line> lines) {
        if (!showSpeed.get() || mc.player == null) return;
        double bps = getSpeedBps();
        double kmh = bps * 3.6;
        String value = switch (speedUnit.get()) {
            case BPS  -> String.format("%.1f bps", bps);
            case KMH  -> String.format("%.1f km/h", kmh);
            case Both -> String.format("%.1f bps / %.1f km/h", bps, kmh);
        };
        lines.add(new Line(List.of(
            new Segment("Speed: ", labelColor.get()),
            new Segment(value, valueColor.get())
        )));
    }

    private void addPerformanceLine(List<Line> lines, double sepW) {
        List<Segment> segs = new ArrayList<>();
        if (showFps.get()) {
            segs.add(new Segment("FPS: ", labelColor.get()));
            segs.add(new Segment(String.valueOf(mc.getCurrentFps()), valueColor.get()));
        }
        
        if (showTps.get()) {
            if (!segs.isEmpty()) segs.add(new Segment(" | ", separatorColor.get()));
            float tps = TickRate.INSTANCE.getTickRate();
            Color tpsColor = tps < 10f ? new SettingColor(255, 60, 60, 255) : 
                             tps < 15f ? new SettingColor(255, 200, 0, 255) : 
                             valueColor.get();
            segs.add(new Segment("TPS: ", labelColor.get()));
            segs.add(new Segment(String.format("%.1f", tps), tpsColor));
        }

        if (showPing.get() && mc.player != null && mc.getNetworkHandler() != null) {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (entry != null) {
                if (!segs.isEmpty()) segs.add(new Segment(" | ", separatorColor.get()));
                segs.add(new Segment("Ping: ", labelColor.get()));
                segs.add(new Segment(entry.getLatency() + "ms", valueColor.get()));
            }
        }
        if (!segs.isEmpty()) lines.add(new Line(segs));
    }

    private void addDirectionLine(List<Line> lines) {
        if (!showDirection.get() || mc.player == null) return;
        float yaw = mc.player.getYaw() % 360f;
        if (yaw < 0) yaw += 360f;
        String cardinal = getCardinal(yaw);
        String value = switch (directionFormat.get()) {
            case Cardinal -> cardinal;
            case Yaw      -> String.format("%.1f°", yaw);
            case Both     -> String.format("%s  %.1f°", cardinal, yaw);
        };
        lines.add(new Line(List.of(
            new Segment("Facing: ", labelColor.get()),
            new Segment(value, valueColor.get())
        )));
    }

    private void addCoordsLine(List<Line> lines) {
        if (coordinateDisplay.get() != CoordinateDisplay.Show || mc.player == null) return;
        String value = String.format("%d, %d, %d", (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ());
        lines.add(new Line(List.of(
            new Segment("Pos: ", labelColor.get()),
            new Segment(value, valueColor.get())
        )));
    }

    private void addMemoryLine(List<Line> lines) {
        if (!showMemory.get()) return;
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB  = rt.maxMemory() / (1024 * 1024);
        double pct  = (double) usedMB / maxMB;
        
        Color memColor = valueColor.get();
        if (memoryColorCode.get()) {
            if      (pct >= 0.90) memColor = new SettingColor(255, 60,  60,  255);
            else if (pct >= 0.75) memColor = new SettingColor(255, 200, 0,   255);
        }
        lines.add(new Line(List.of(
            new Segment("Mem: ", labelColor.get()),
            new Segment(usedMB + " / " + maxMB + " MB", memColor)
        )));
    }

    private void addWorldLine(List<Line> lines, double sepW) {
        List<Segment> segs = new ArrayList<>();
        if (showChunks.get() && mc.worldRenderer != null) {
            segs.add(new Segment("Chunks: ", labelColor.get()));
            segs.add(new Segment(String.valueOf(mc.worldRenderer.getCompletedChunkCount()), valueColor.get()));
        }
        if (showPlayerCount.get() && mc.getNetworkHandler() != null) {
            if (!segs.isEmpty()) segs.add(new Segment(" | ", separatorColor.get()));
            segs.add(new Segment("Players: ", labelColor.get()));
            segs.add(new Segment(String.valueOf(mc.getNetworkHandler().getPlayerList().size()), valueColor.get()));
        }
        if (!segs.isEmpty()) lines.add(new Line(segs));
    }

    private void addDistanceLine(List<Line> lines) {
        if (!showDistance.get()) return;
        String value = switch (distanceUnit.get()) {
            case Blocks -> String.format("%.0f m", distanceTraveled);
            case Km     -> String.format("%.3f km", distanceTraveled / 1000.0);
            case Both   -> String.format("%.0f m  /  %.3f km", distanceTraveled, distanceTraveled / 1000.0);
        };
        lines.add(new Line(List.of(
            new Segment("Traveled: ", labelColor.get()),
            new Segment(value, valueColor.get())
        )));
    }

    private void addTimeLine(List<Line> lines) {
        if (!showTimeOnline.get() || sessionStartMs < 0) return;
        long totalSecs = (System.currentTimeMillis() - sessionStartMs) / 1000L;
        long hours     = totalSecs / 3600;
        long minutes   = (totalSecs % 3600) / 60;
        long seconds   = totalSecs % 60;
        String value = switch (timeFormat.get()) {
            case Seconds -> String.format("%ds", totalSecs);
            case HM      -> hours > 0 ? String.format("%dh %02dm", hours, minutes) : String.format("%dm", minutes);
            case HMS     -> hours > 0 ? String.format("%dh %02dm %02ds", hours, minutes, seconds) : 
                            minutes > 0 ? String.format("%dm %02ds", minutes, seconds) : 
                            String.format("%ds", seconds);
        };
        lines.add(new Line(List.of(
            new Segment("Online: ", labelColor.get()),
            new Segment(value, valueColor.get())
        )));
    }

    private void addStabilityLine(List<Line> lines) {
        if (!showStability.get()) return;
        long lastTick = (long) TickRate.INSTANCE.getTimeSinceLastTick();
        Color stabColor = valueColor.get();
        String value;
        if (lastTick > 1000) {
            value = "DESYNC";
            stabColor = new SettingColor(255, 60, 60, 255);
        } else {
            value = lastTick + "ms";
            if (lastTick > 250) stabColor = new SettingColor(255, 200, 0, 255);
        }
        lines.add(new Line(List.of(
            new Segment("Stability: ", labelColor.get()),
            new Segment(value, stabColor)
        )));
    }

    private void addTpsGuardLine(List<Line> lines) {
        if (!tpsGuard.get()) return;
        float tps = TickRate.INSTANCE.getTickRate();
        if (tps < tpsGuardThreshold.get() && !(tpsGuardHideStatic.get() && getSpeedBps() < 0.1)) {
            lines.add(new Line(List.of(
                new Segment("! TPS Guard: ", labelColor.get()),
                new Segment("DANGER", new SettingColor(255, 60, 60, 255))
            )));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private double getSpeedBps() {
        if (mc.player == null) return 0.0;
        double dx = mc.player.getX() - mc.player.prevX;
        double dz = mc.player.getZ() - mc.player.prevZ;
        return Math.sqrt(dx * dx + dz * dz) * 20.0;
    }

    private String getCardinal(float yaw) {
        if (yaw < 22.5f  || yaw >= 337.5f) return "S";
        if (yaw < 67.5f)                   return "SW";
        if (yaw < 112.5f)                  return "W";
        if (yaw < 157.5f)                  return "NW";
        if (yaw < 202.5f)                  return "N";
        if (yaw < 247.5f)                  return "NE";
        if (yaw < 292.5f)                  return "E";
        return                                    "SE";
    }
}