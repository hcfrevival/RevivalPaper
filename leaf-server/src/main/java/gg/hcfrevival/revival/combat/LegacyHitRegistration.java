package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.config.LegacyCombatSettings;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jspecify.annotations.Nullable;

/**
 * Hit registration for legacy weapons (patch 1006). Paper's attack handling stays as it is: reach, invalid-target
 * checks, packet order and every event. On top of it, while a player holds a legacy weapon a transient
 * {@code ATTACK_SPEED} modifier maxes out their attack speed, so the client shows no cooldown bar and predicts crits
 * and sprint slowdown like a 1.7 client, and an optional cap limits legacy attacks per tick.
 * <p>
 * Owned by the player's tick thread.
 */
public final class LegacyHitRegistration {

    public static final Identifier NO_COOLDOWN = Identifier.fromNamespaceAndPath("hcfrevival", "legacy_no_cooldown");
    static final AttributeModifier NO_COOLDOWN_MODIFIER = new AttributeModifier(NO_COOLDOWN, 1024.0, AttributeModifier.Operation.ADD_VALUE);

    private int attackTick = -1;
    private int attacks;

    /** Each tick: keep the no-cooldown modifier in step with the held item, the world and revival.yml. */
    public void tick(final ServerPlayer player) {
        refresh(player, LegacyAttack.settings(player, player.getWeaponItem()));
    }

    /**
     * Before an attack. Refreshes the modifier first, so a weapon swapped in since the last tick gets its own
     * cooldown, then applies the per-tick cap. Returns false when the attack should be dropped.
     */
    public boolean beforeAttack(final ServerPlayer player) {
        final LegacyCombatSettings settings = LegacyAttack.settings(player, player.getWeaponItem());
        refresh(player, settings);
        return settings == null || this.allow(player.tickCount, settings.maxAttacksPerTick());
    }

    /** Counts an attack in this tick; false once there have been {@code cap} already. A cap of 0 is off. */
    boolean allow(final int tick, final int cap) {
        if (tick != this.attackTick) {
            this.attackTick = tick;
            this.attacks = 0;
        }
        return cap <= 0 || ++this.attacks <= cap;
    }

    private static void refresh(final ServerPlayer player, final @Nullable LegacyCombatSettings settings) {
        final AttributeInstance speed = player.getAttribute(Attributes.ATTACK_SPEED);
        if (speed != null) {
            refresh(speed, settings != null && settings.hideCooldownIndicator());
        }
    }

    static void refresh(final AttributeInstance speed, final boolean wanted) {
        if (wanted != speed.hasModifier(NO_COOLDOWN)) {
            if (wanted) {
                speed.addTransientModifier(NO_COOLDOWN_MODIFIER);
            } else {
                speed.removeModifier(NO_COOLDOWN);
            }
        }
    }
}
