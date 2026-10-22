package gg.hcfrevival.revival.api.event;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Called when a fresh sword block parries a hit under the Neue combat rules ({@code legacy-combat.rules: neue}): the
 * hit deals no damage and no knock, the parrier gets a riposte (their next hit within 0.6 s is a crit) and a melee
 * attacker is staggered. {@link #getPlayer()} is the parrier.
 * <p>
 * It fires before {@code EntityDamageByEntityEvent}, which a parried hit never reaches. Plugins that cancel that event to
 * stop combat (teams, PvP timers, safe zones) should cancel this one in the same cases; a cancelled parry goes on as a
 * plain blocked hit.
 */
@NullMarked
public class RevivalParryEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player attacker;
    private final @Nullable Projectile projectile;
    private boolean cancelled;

    @ApiStatus.Internal
    public RevivalParryEvent(final Player parrier, final Player attacker, final @Nullable Projectile projectile) {
        super(parrier);
        this.attacker = attacker;
        this.projectile = projectile;
    }

    /** Who swung, or who threw the projectile. */
    public Player getAttacker() {
        return this.attacker;
    }

    /** The projectile that was parried (a rod's hook, a snowball, an egg), or null for a melee hit, which staggers its attacker. */
    public @Nullable Projectile getProjectile() {
        return this.projectile;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(final boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
