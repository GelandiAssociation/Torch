package org.torch.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

public interface MutableEvent {
    static void init(Event evt) {
        // Reset cancel
        if (evt != null && evt instanceof Cancellable) {
            ((Cancellable) evt).setCancelled(false);
        }
    }
}
