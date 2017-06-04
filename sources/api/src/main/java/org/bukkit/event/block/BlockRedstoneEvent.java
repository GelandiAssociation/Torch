package org.bukkit.event.block;

import org.bukkit.block.Block;
import org.bukkit.event.HandlerList;
import org.torch.event.MutableEvent;

/**
 * Called when a redstone current changes
 */
public class BlockRedstoneEvent extends BlockEvent implements MutableEvent {
    private static final HandlerList handlers = new HandlerList();
    // Torch start
    protected int oldCurrent;
    protected int newCurrent;
    
    private static BlockRedstoneEvent instance;
    
    public static BlockRedstoneEvent of(final Block block, final int oldCurrent, final int newCurrent) {
        MutableEvent.init(instance);
        
        if (instance == null) {
            instance = new BlockRedstoneEvent(block, oldCurrent, newCurrent);
            return instance;
        }
        
        instance.block = block;
        instance.oldCurrent = oldCurrent;
        instance.newCurrent = newCurrent;
        
        return instance;
    }
    // Torch end

    public BlockRedstoneEvent(final Block block, final int oldCurrent, final int newCurrent) {
        super(block);
        this.oldCurrent = oldCurrent;
        this.newCurrent = newCurrent;
    }

    /**
     * Gets the old current of this block
     *
     * @return The previous current
     */
    public int getOldCurrent() {
        return oldCurrent;
    }

    /**
     * Gets the new current of this block
     *
     * @return The new current
     */
    public int getNewCurrent() {
        return newCurrent;
    }

    /**
     * Sets the new current of this block
     *
     * @param newCurrent The new current to set
     */
    public void setNewCurrent(int newCurrent) {
        this.newCurrent = newCurrent;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
