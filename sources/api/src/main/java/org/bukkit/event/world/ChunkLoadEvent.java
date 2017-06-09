package org.bukkit.event.world;

import org.bukkit.Chunk;
import org.bukkit.event.HandlerList;
import org.torch.event.MutableEvent;

/**
 * Called when a chunk is loaded
 */
public class ChunkLoadEvent extends ChunkEvent {
    private static final HandlerList handlers = new HandlerList();
    // Torch start
    private boolean newChunk;
    
    private static ChunkLoadEvent instance;
    
    public static ChunkLoadEvent of(final Chunk chunk, final boolean newChunk) {
        MutableEvent.init(instance);
        
        if (instance == null) {
            instance = new ChunkLoadEvent(chunk, newChunk);
            return instance;
        }
        
        instance.chunk = chunk;
        instance.newChunk = newChunk;
        
        return instance;
    }
    // Torch end

    public ChunkLoadEvent(final Chunk chunk, final boolean newChunk) {
        super(chunk);
        this.newChunk = newChunk;
    }

    /**
     * Gets if this chunk was newly created or not.
     * <p>
     * Note that if this chunk is new, it will not be populated at this time.
     *
     * @return true if the chunk is new, otherwise false
     */
    public boolean isNewChunk() {
        return newChunk;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
