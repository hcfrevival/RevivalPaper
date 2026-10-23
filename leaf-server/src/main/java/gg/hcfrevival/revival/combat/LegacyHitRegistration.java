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
 * and sprint slowdown like a 1.7 client, and an optional cap limits legacy attacks per tick. It also holds the player's
 * {@link NeueState}, so Neue combat needs no field of its own on {@code ServerPlayer}.
 * <p>
 * Owned by the player's tick thread.
 */
public final class LegacyHitRegistration {

    public static final Identifier NO_COOLDOWN = Identifier.fromNamespaceAndPath("hcfrevival", "legacy_no_cooldown");
    static final AttributeModifier NO_COOLDOWN_MODIFIER = new AttributeModifier(NO_COOLDOWN, 1024.0, AttributeModifier.Operation.ADD_VALUE);

    private int attackTick = -1;
    private int attacks;
    private final NeueState neue = new NeueState();

    /** The player's Neue combat state (held click, parry clock, stagger, riposte); see {@link NeueCombat}. */
    public NeueState neue() {
        return this.neue;
    }

    /**
     * Each tick (top of {@code ServerPlayer.tick}): keep the no-cooldown modifier in step with the held item, the world and
     * revival.yml; Neue's per-tick upkeep; the melee window's level tick (SV-2).
     */
    public void tick(final ServerPlayer player) {
        refresh(player, LegacyAttack.settings(player, player.getWeaponItem()));
        NeueCombat.tick(player);
        DamageWindows.levelTick(player);
    }

    // hide-cooldown-on-items: an item that brings no attack speed of its own (a rod, a pot, a pearl) keeps the modifier, so
    // the client already has it when a swap brings the sword (classic audit #11). Never the empty hand: fists stay 26.x
    private static boolean keepsNoCooldown(final ServerPlayer player, final net.minecraft.world.item.ItemStack held) {
        if (held.isEmpty()) {
            return false;
        }
        final gg.hcfrevival.revival.config.LegacyCombatSettings world = gg.hcfrevival.revival.Revival.settings().legacyCombat(player.level().getWorld().getName());
        if (!world.hideCooldownOnItems() || !world.hideCooldownIndicator() || !world.enabled()) {
            return false;
        }
        final net.minecraft.world.item.component.ItemAttributeModifiers modifiers = held.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
            net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
        final java.util.List<net.minecraft.world.item.component.ItemAttributeModifiers.Entry> entries = modifiers.modifiers();
        for (int i = 0, n = entries.size(); i < n; i++) { // per tick: no iterator
            if (entries.get(i).attribute().is(Attributes.ATTACK_SPEED)) {
                return false; // a 26.x weapon (an axe, a mace, a trident): its cooldown stays
            }
        }
        return true;
    }

    /**
     * Before an attack. Refreshes the modifier first, so a weapon swapped in since the last tick gets its own
     * cooldown, then applies Neue's stagger lockout and click cap ({@link NeueCombat}) and the per-tick cap. Returns
     * false when the attack should be dropped.
     */
    public boolean beforeAttack(final ServerPlayer player) {
        player.revivalBaseKnockTarget = null; // a base knock from an earlier hit must not hide this hit's extra knock
        final LegacyCombatSettings settings = LegacyAttack.settings(player, player.getWeaponItem());
        refresh(player, settings);
        if (!NeueCombat.beforeAttack(player, settings)) {
            return false;
        }
        if (settings != null && !this.allow(player.tickCount, settings.maxAttacksPerTick())) {
            NeueCombat.refused(player);
            return false;
        }
        return true;
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
            refresh(speed, settings != null ? settings.hideCooldownIndicator() : keepsNoCooldown(player, player.getWeaponItem()));
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
