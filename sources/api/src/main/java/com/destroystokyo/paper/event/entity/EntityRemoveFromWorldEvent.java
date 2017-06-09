package com.destroystokyo.paper.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityEvent;
import org.torch.event.MutableEvent;

/**
 * Fired any time an entity is being removed from a world for any reason
 */
public class EntityRemoveFromWorldEvent extends EntityEvent {
    // Torch start
    private static EntityRemoveFromWorldEvent instance;
    
    public static EntityRemoveFromWorldEvent of(Entity entity) {
        MutableEvent.init(instance);
        
        if (instance == null) {
            instance = new EntityRemoveFromWorldEvent(entity);
            return instance;
        }
        
        instance.entity = entity;
        
        return instance;
    }
    // Torch end

    public EntityRemoveFromWorldEvent(Entity entity) {
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
