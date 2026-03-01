package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.MathHelper;

public class Timethrottle extends Module {

    private final SettingGroup sgTps = settings.createGroup("TPS");
    private final SettingGroup sgChunkLoading = settings.createGroup("Chunk Loading");

    // TPS Settings
    private final Setting<Double> targetTps = sgTps.add(new DoubleSetting.Builder()
        .name("target-tps")
        .description("TPS above which the game runs at normal speed.")
        .defaultValue(19.0)
        .min(1)
        .max(20)
        .sliderMax(20)
        .build()
    );

    private final Setting<Double> minTps = sgTps.add(new DoubleSetting.Builder()
        .name("min-tps")
        .description("TPS at which the game runs at its slowest speed.")
        .defaultValue(10.0)
        .min(1)
        .max(20)
        .sliderMax(20)
        .build()
    );

    private final Setting<Double> minSpeed = sgTps.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("The minimum speed multiplier to use when TPS is at or below 'min-tps'.")
        .defaultValue(0.5)
        .min(0.1)
        .max(1.0)
        .sliderMax(1.0)
        .build()
    );

    // Chunk Loading Settings
    private final Setting<Boolean> chunkThrottle = sgChunkLoading.add(new BoolSetting.Builder()
        .name("chunk-throttle")
        .description("Also slow down the game when many chunks are loading.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> chunkLoadThreshold = sgChunkLoading.add(new IntSetting.Builder()
        .name("chunk-load-threshold")
        .description("Number of unloaded chunks in view distance to trigger throttling.")
        .defaultValue(50)
        .min(1)
        .sliderMax(200)
        .visible(chunkThrottle::get)
        .build()
    );

    private final Setting<Double> chunkLoadSlowdown = sgChunkLoading.add(new DoubleSetting.Builder()
        .name("chunk-load-slowdown")
        .description("The speed multiplier to apply when chunk loading is heavy.")
        .defaultValue(0.7)
        .min(0.1)
        .max(1.0)
        .sliderMax(1.0)
        .visible(chunkThrottle::get)
        .build()
    );

    // General Settings
    private final Setting<Double> smoothing = settings.getDefaultGroup().add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("How quickly the speed adjusts. 0 is instant, 1 is no change.")
        .defaultValue(0.1)
        .min(0.0)
        .max(0.99)
        .sliderMax(0.5)
        .build()
    );

    private double currentSpeed = 1.0;

    public Timethrottle() {
        super(HuntingUtilities.CATEGORY, "time-throttle", "Automatically adjusts game speed based on server performance (TPS, chunk loading).");
    }

    @Override
    public void onActivate() {
        currentSpeed = 1.0;
    }

    @Override
    public void onDeactivate() {
        Modules.get().get(Timer.class).setOverride(1.0);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) {
            return;
        }

        // Calculate TPS-based speed
        double tps = TickRate.INSTANCE.getTickRate();
        double tpsSpeed = 1.0;

        if (tps <= targetTps.get()) {
            if (tps <= minTps.get()) {
                tpsSpeed = minSpeed.get();
            } else {
                // Linear interpolation between minSpeed and 1.0
                tpsSpeed = MathHelper.map(tps, minTps.get(), targetTps.get(), minSpeed.get(), 1.0);
            }
        }

        // Calculate chunk-loading-based speed
        double chunkSpeed = 1.0;
        if (chunkThrottle.get()) {
            int unloadedCount = countUnloadedChunks();
            if (unloadedCount > chunkLoadThreshold.get()) {
                chunkSpeed = chunkLoadSlowdown.get();
            }
        }

        // The final desired speed is the minimum of the two, to be most responsive to lag
        double desiredSpeed = Math.min(tpsSpeed, chunkSpeed);

        // Apply smoothing
        currentSpeed = MathHelper.lerp(1.0 - smoothing.get(), currentSpeed, desiredSpeed);

        // Apply to the game timer via Meteor's Timer module
        Modules.get().get(Timer.class).setOverride(currentSpeed);
    }

    private int countUnloadedChunks() {
        if (mc.world == null || mc.player == null) return 0;

        int unloaded = 0;
        int viewDistance = mc.options.getClampedViewDistance();
        int playerChunkX = mc.player.getChunkPos().x;
        int playerChunkZ = mc.player.getChunkPos().z;

        for (int x = -viewDistance; x <= viewDistance; x++) {
            for (int z = -viewDistance; z <= viewDistance; z++) {
                if (!mc.world.getChunkManager().isChunkLoaded(playerChunkX + x, playerChunkZ + z)) {
                    unloaded++;
                }
            }
        }
        return unloaded;
    }
}
