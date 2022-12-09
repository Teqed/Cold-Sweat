package dev.momostudios.coldsweat.mixin;

import dev.momostudios.coldsweat.ColdSweat;
import dev.momostudios.coldsweat.api.event.common.BlockChangedEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Called when a block state is changed.<br>
 * This event is not {@link net.minecraftforge.eventbus.api.Cancelable}.<br>
 * <br>
 * The event isn't called if the chunk does not have the {@link ChunkStatus#FULL} status to prevent chunkloading deadlocks.
 */
@Mixin(Level.class)
public class MixinBlockUpdate
{
    Level level = (Level) (Object) this;

    @Inject(method = "onBlockStateChange", at = @At("HEAD"), remap = ColdSweat.REMAP_MIXINS)
    private void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci)
    {
        if (oldState != newState)
        {
            ChunkAccess chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
            if (chunk != null && chunk.getStatus() == ChunkStatus.FULL)
            {
                MinecraftForge.EVENT_BUS.post(new BlockChangedEvent(pos, oldState, newState, level));
            }
        }
    }
}
