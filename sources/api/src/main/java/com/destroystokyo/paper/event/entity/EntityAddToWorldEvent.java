package com.destroystokyo.paper.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityEvent;
import org.torch.event.MutableEvent;

/**
 * Fired any time an entity is being added to the world for any reason.
 *
 * Not to be confused with {@link org.bukkit.event.entity.CreatureSpawnEvent}
 * This will fire anytime a chunk is reloaded too.
 */
public class EntityAddToWorldEvent extends EntityEvent {
    // Torch start
    private static EntityAddToWorldEvent instance;
    
    public static EntityAddToWorldEvent of(Entity entity) {
        MutableEvent.init(instance);
        
        if (instance == null) {
            instance = new EntityAddToWorldEvent(entity);
            return instance;
        }
        
        instance.entity = entity;
        
        return instance;
    }
    // Torch end

    public EntityAddToWorldEvent(Entity entity) {
        super(entity);
    }

    private static final HandlerList handlers = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
