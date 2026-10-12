package gg.hcfrevival.revival.api.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Called after a legacy hit knocked its victim, once per hit, with the motion the victim was given. Paper's
 * {@code EntityKnockbackEvent} fires first and can still change or cancel the knock; this reports the result.
 */
@NullMarked
public class RevivalKnockbackEvent extends EntityEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player attacker;
    private final String profile;
    private final Vector velocity;
    private final boolean extraOnly;

    @ApiStatus.Internal
    public RevivalKnockbackEvent(final LivingEntity victim, final Player attacker, final String profile, final Vector velocity, final boolean extraOnly) {
        super(victim);
        this.attacker = attacker;
        this.profile = profile;
        this.velocity = velocity;
        this.extraOnly = extraOnly;
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) this.entity;
    }

    public Player getAttacker() {
        return this.attacker;
    }

    /** The knockback profile the hit used. */
    public String getProfile() {
        return this.profile;
    }

    /** The victim's motion after the knock. */
    public Vector getVelocity() {
        return this.velocity.clone();
    }

    /**
     * Whether the hit landed inside the victim's damage window, so the victim only got the extra knock from sprint
     * or the Knockback enchant, as in 1.7.10.
     */
    public boolean isExtraOnly() {
        return this.extraOnly;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
