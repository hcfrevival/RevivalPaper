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
 *       clicking a container while walking, ...), counted since the player joined;</li>
 *   <li>{@code click-streak}: client ticks in a row with exactly one click (a one-per-tick autoclicker);</li>
 *   <li>{@code jump-reset}: share of knocks taken on the ground with a jump in their own tick, against the player's own
 *       jump rate (a velocity macro);</li>
 *   <li>{@code click-lock}: clicks in a row at one fixed rate (an autoclicker at any rate);</li>
 *   <li>{@code w-tap-lock}, {@code block-hit-lock}: hits in a row followed by the same w-tap or block-hit to the tick
 *       (a macro bound to the click);</li>
 *   <li>{@code rewind-stretch}: the median rewind of the player's last valid hits, past what its own base round trip and
 *       display delay explain (backtrack inside the rewind cap).</li>
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

    /** One of the kinds above, such as {@code click-lock}. */
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
