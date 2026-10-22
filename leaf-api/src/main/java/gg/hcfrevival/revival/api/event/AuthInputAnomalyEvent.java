package gg.hcfrevival.revival.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Called when a player under authoritative movement passes one of the input statistics thresholds in
 * {@code revival.yml} ({@code authoritative.stats.*}), once each time the value crosses it. Click timing and aim can't
 * be made authoritative, so they are only described: this is for staff tools and anticheats, nothing is done to the
 * player.
 * <ul>
 *   <li>{@code double-clicks}: share of attack ticks with two or more attacks;</li>
 *   <li>{@code aim-gcd}: share of turns whose yaw change shares no mouse step with the others (synthetic aim);</li>
 *   <li>{@code input-grammar}: actions a vanilla client can't send in one tick (attacking while using an item,
 *       clicking a container while walking, ...), counted since the player joined.</li>
 * </ul>
 */
@NullMarked
public class AuthInputAnomalyEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String kind;
    private final double value;
    private final double threshold;

    @ApiStatus.Internal
    public AuthInputAnomalyEvent(final Player player, final String kind, final double value, final double threshold) {
        super(player);
        this.kind = kind;
        this.value = value;
        this.threshold = threshold;
    }

    /** {@code double-clicks}, {@code aim-gcd} or {@code input-grammar}. */
    public String getKind() {
        return this.kind;
    }

    /** The statistic now. */
    public double getValue() {
        return this.value;
    }

    /** The configured threshold it passed. */
    public double getThreshold() {
        return this.threshold;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
