package com.example.addon.mixin;

import com.example.addon.modules.Tunnelers;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * Mixin for {@link Tunnelers}.
 *
 * <p>Hooks into {@link ClientChunkManager#loadChunkFromPacket} so that the
 * Tunnelers module is notified the moment a chunk arrives from the server,
 * rather than discovering it during the next periodic scan tick.  This removes
 * the scan-delay lag and prevents chunks that unload before the timer fires
 * from being silently missed.</p>
 *
 * <p>A symmetric inject into {@link ClientChunkManager#unload} removes stale
 * entries from the locations map as soon as a chunk leaves the view distance,
 * keeping memory usage tight even with a large scan range.</p>
 *
 * <h3>Registration</h3>
 * Add the following entry to your {@code mixins.<modid>.json}:
 * <pre>{@code
 * "client": [
 *   "TunnelersMixin"
 * ]
 * }</pre>
 */
@Mixin(ClientChunkManager.class)
public abstract class TunnelersMixin {

    // ------------------------------------------------------------------ //
    //  Chunk loaded                                                        //
    // ------------------------------------------------------------------ //

    /**
     * Called by the game when a chunk packet is received from the server and
     * the chunk has been fully populated into the client world.  The return
     * value is the finished {@link WorldChunk}; we read it from the
     * {@link CallbackInfoReturnable} so we never touch a partially-built chunk.
     *
     * <p>If Tunnelers is active we hand the chunk straight to
     * {@link Tunnelers#onChunkLoaded(WorldChunk)} which runs the same
     * detection logic that the tick-based scanner uses, but immediately and
     * only for this one chunk.</p>
     */
    @Inject(
        method = "loadChunkFromPacket",
        at = @At("RETURN")
    )
    private void tunnelers$onChunkLoaded(
            int x,
            int z,
            PacketByteBuf buf,
            NbtCompound nbt,
            Consumer<ChunkData.BlockEntityVisitor> chunkDataConsumer,
            CallbackInfoReturnable<WorldChunk> cir
    ) {
        WorldChunk chunk = cir.getReturnValue();
        if (chunk == null) return;

        Tunnelers module = Modules.get().get(Tunnelers.class);
        if (module != null && module.isActive()) {
            module.onChunkLoaded(chunk);
        }
    }

    // ------------------------------------------------------------------ //
    //  Chunk unloaded                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Called by the game when a chunk is removed from the client world (e.g.
     * the player moves out of range or the server sends an unload packet).
     *
     * <p>We notify Tunnelers so it can evict every {@link net.minecraft.util.math.BlockPos}
     * that belonged to this chunk from its {@code locations} map, preventing
     * memory from growing unboundedly on long sessions.</p>
     */
    @Inject(
        method = "unload",
        at = @At("HEAD")
    )
    private void tunnelers$onChunkUnloaded(ChunkPos pos, CallbackInfo ci) {
        Tunnelers module = Modules.get().get(Tunnelers.class);
        if (module != null && module.isActive()) {
            module.onChunkUnloaded(pos.x, pos.z);
        }
    }
}