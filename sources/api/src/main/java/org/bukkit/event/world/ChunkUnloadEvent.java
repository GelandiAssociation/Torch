package org.bukkit.event.world;

import org.bukkit.Chunk;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.torch.event.MutableEvent;

/**
 * Called when a chunk is unloaded
 */
public class ChunkUnloadEvent extends ChunkEvent implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    // Torch start
    private boolean cancel = false;
    private boolean saveChunk;
    
    private static ChunkUnloadEvent instance;
    
    public static ChunkUnloadEvent of(final Chunk chunk) {
        return of(chunk, true);
    }
    
    public static ChunkUnloadEvent of(Chunk chunk, boolean save) {
        MutableEvent.init(instance);
        
        if (instance == null) {
            instance = new ChunkUnloadEvent(chunk, save);
            return instance;
        }
        
        instance.chunk = chunk;
        instance.saveChunk = save;
        
        return instance;
    }
    // Torch end

    public ChunkUnloadEvent(final Chunk chunk) {
        this(chunk, true);
    }

    public ChunkUnloadEvent(Chunk chunk, boolean save) {
        super(chunk);
        this.saveChunk = save;
    }

    /**
     * Return whether this chunk will be saved to disk.
     *
     * @return chunk save status
     */
    public boolean isSaveChunk() {
        return saveChunk;
    }

    /**
     * Set whether this chunk will be saved to disk.
     *
     * @param saveChunk chunk save status
     */
    public void setSaveChunk(boolean saveChunk) {
        this.saveChunk = saveChunk;
    }

    @Override
    public boolean isCancelled() {
        return cancel;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancel = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
