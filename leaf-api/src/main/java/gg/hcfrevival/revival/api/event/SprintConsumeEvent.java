package gg.hcfrevival.revival.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Called when a legacy hit used up the player's sprint knock; they need to stop and start sprinting (w-tap) to arm the next one.
 */
@NullMarked
public class SprintConsumeEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @ApiStatus.Internal
    public SprintConsumeEvent(final Player player) {
        super(player);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
