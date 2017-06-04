package org.torch.api;

import javax.annotation.Nullable;

import net.minecraft.server.BlockPosition;
import net.minecraft.server.Entity;
import net.minecraft.server.EntityHuman;
import net.minecraft.server.IBlockData;
import net.minecraft.server.SoundCategory;
import net.minecraft.server.SoundEffect;
import net.minecraft.server.World;

public interface IWorldAccess {
    /**
     * Called on all IWorldAccess when an entity is created or loaded.
     * On server-side worlds, adds the entity to the entity tracker.
     */
    abstract void onEntityAdded(Entity entity);
    
    /**
     * Called on all IWorldAccesses when an entity is unloaded or destroyed.
     * On server-side worlds, removes the entity from the entity tracker.
     */
    abstract void onEntityRemoved(Entity entity);
    
    abstract void notifyBlockUpdate(World world, BlockPosition position, IBlockData oldData, IBlockData newData);
    
    @Async abstract void playSoundNearbyExpect(@Nullable EntityHuman expect, SoundEffect effect, SoundCategory category, double x, double y, double z, float volume, float pitch);
    
    @Async abstract void playWorldEventNearbyExpect(@Nullable EntityHuman expect, int type, BlockPosition position, int data);
    
    @Async abstract void playWorldEvent(int type, BlockPosition position, int data);
    
    @Async abstract void sendBlockBreakProgress(int breakerEntityId, BlockPosition position, int progress);
}
