package gg.hcfrevival.revival.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Called when a player starts sprinting and their next legacy hit will carry a sprint knock. In 1.7.10 that took a fresh sprint start, in practice a w-tap.
 */
@NullMarked
public class SprintArmEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @ApiStatus.Internal
    public SprintArmEvent(final Player player) {
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
