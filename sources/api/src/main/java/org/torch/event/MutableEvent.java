package org.torch.event;

import static org.bukkit.Bukkit.isPrimaryThread;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

public interface MutableEvent {
    static void init(Event evt) {
        if (!isPrimaryThread()) throw new IllegalStateException("Async request mutable event!");
        
        if (evt != null && evt instanceof Cancellable) {
            ((Cancellable) evt).setCancelled(false);
        }
    }
}
