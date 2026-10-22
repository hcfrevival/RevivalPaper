package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.api.event.RevivalParryEvent;
import gg.hcfrevival.revival.config.CombatRules;
import gg.hcfrevival.revival.config.KnockbackProfile;
import gg.hcfrevival.revival.config.LegacyCombatSettings;
import gg.hcfrevival.revival.config.RevivalSettings;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.util.CraftVector;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.jspecify.annotations.Nullable;

/**
 * Neue combat on the server (docs/revival/neue-combat.md): Combo's click buffer, tipper, DI, parry, stagger and riposte
 * for vanilla 26.3 clients. The state is {@link NeueState}, one per player; the knock math is {@link NeueShape}.
 * <p>
 * Entry points, by caller:
 * <ul>
 *   <li>patch hooks: {@link #parried} (top of {@code revivalHurtServer}), {@link #windowRejected} (its window rule),
 *       {@link #quiet} ({@code Player.attack}'s no-damage sound), {@link #swung} ({@code handlePunch});</li>
 *   <li>the netcode ({@code AuthPlayer}): {@link #begin}/{@link #settle} around an attack's replay, {@link #serverTick}
 *       (the landing pass), {@link #useStarted}/{@link #useStopped}, {@link #applied} (the stagger's ack),
 *       {@link #lockedOut};</li>
 *   <li>the legacy combat classes: knock shaping, riposte, the lockout in hit registration, the fallbacks for players
 *       outside authority (§11).</li>
 * </ul>
 * docs/revival/neue-hooks.md lists each hook. Every entry point exits on a field read or two when the rules are classic.
 * <p>
 * Owned by the tick thread of the players involved (attacker and victim share a level, so a thread).
 */
public final class NeueCombat {

    /** The stagger's transient {@code movement_speed} modifier (§8.4 step 5). */
    public static final Identifier STAGGER_SLOW = Identifier.fromNamespaceAndPath("hcfrevival", "neue_stagger");

    private NeueCombat() {
    }

    public static NeueState state(final ServerPlayer player) {
        return player.revivalHitRegistration.neue();
    }

    /** The combat rules of the entity's world. */
    public static CombatRules rules(final Entity entity) {
        return Revival.settings().legacyCombat(entity.level().getWorld().getName()).rules();
    }

    static long now() {
        return MinecraftServer.currentTick;
    }

    // Whether the player's client ticks are the clock: the simulation owns their state (enforce, not exempt)
    private static boolean authority(final ServerPlayer player) {
        return player.revivalAuth.ownsClientState();
    }

    // The player's latest client tick under authority, the server tick otherwise
    private static long tick(final ServerPlayer player, final boolean authority) {
        return authority ? player.revivalAuth.tick() : now();
    }

    // Round trip in whole ticks, rounded up: the lockout's stand-in for an ack outside authority (§11)
    private static int rttTicks(final ServerPlayer player, final boolean authority) {
        final long millis = authority ? player.revivalAuth.transactions().rttBaseNanos() / 1_000_000L : Math.max(0, player.connection.latency());
        return (int) ((millis + 49L) / 50L);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The netcode
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * {@code AuthPlayer.runAttack}, before replaying an attack it delivers: {@code distance} is the judge's eye-to-box
     * distance ({@code HitCheck.distance()}, exact and lag-compensated; NaN when it has none), which the tipper reads.
     */
    public static void begin(final ServerPlayer attacker, final ServerboundAttackPacket packet, final double distance) {
        final NeueState state = state(attacker);
        settle(attacker, state); // an attack left open ends first
        state.begin(packet.entityId(), packet, distance, false);
    }

    /** After that replay: a click the window turned away is held, anything else that was delivered clears the held one. */
    public static void settle(final ServerPlayer attacker) {
        settle(attacker, state(attacker));
    }

    private static void settle(final ServerPlayer attacker, final NeueState state) {
        if (!state.active()) {
            return;
        }
        // The weapon the click was made with, as the attacker's client holds it: the landing pass compares against it
        final boolean authority = authority(attacker);
        final int slot = authority ? attacker.revivalAuth.view().items.selected() : attacker.getInventory().getSelectedSlot();
        final ItemStack weapon = authority ? attacker.revivalAuth.view().items.mainHand() : attacker.getMainHandItem();
        state.settle(now(), rules(attacker).clickBufferTicks(), slot, weapon.getItem());
    }

    /**
     * The landing pass (§5.2), first in {@code AuthPlayer.onServerTick}: the connection phase, after every level ticked,
     * so the tick whose level phase opened the target's window is the tick the click lands, whichever of the two players
     * the level ticked first. Under authority it returns the held click to replay through {@code handleAttack}, its
     * context begun (buffered, with the landing distance): replay it, then {@link #settle}. The token, envelope and judge
     * are skipped: the click paid and was judged when it was made, and reach was just re-checked on the attacker's mirror.
     * Outside authority (§11) it lands the click itself, on server positions, and returns null.
     */
    public static @Nullable ServerboundAttackPacket serverTick(final ServerPlayer attacker) {
        final NeueState state = state(attacker);
        if (!state.pending()) {
            return null;
        }
        if (state.active()) {
            settle(attacker, state); // an attack this tick that nothing settled yet
        }
        final boolean replay = state.pendingPacket() instanceof ServerboundAttackPacket && authority(attacker);
        final ServerPlayer victim = target(attacker, state.pendingTarget());
        final boolean free = victim != null && (replay ? freeUnderAuthority(attacker, state) : freeOnServer(attacker, state));
        final Object packet = state.pendingPacket(); // still readable once due
        if (state.landingStep(now(), victim != null, free, victim != null && windowOpen(victim)) != NeueState.DUE) {
            return null;
        }
        final boolean lineOfSight = rules(attacker).clickBufferLineOfSight();
        if (!replay) {
            landOnServer(attacker, victim, state, lineOfSight);
            return null;
        }
        final double distance = NeueReach.authority(attacker, victim, lineOfSight);
        if (Double.isNaN(distance)) {
            state.outOfReach();
            return null;
        }
        state.begin(victim.getId(), packet, distance, true);
        return (ServerboundAttackPacket) packet;
    }

    /** The simulation started using an item at client tick {@code tick}: a sword block's parry clock starts. */
    public static void useStarted(final ServerPlayer player, final ItemStack stack, final int tick) {
        if (LegacySwordBlocking.isBlockingWeapon(stack)) {
            state(player).blockStarted(tick, now(), true, rules(player));
        }
    }

    /** The simulation stopped using an item at client tick {@code tick} (release, slot change, a server-meant stop). */
    public static void useStopped(final ServerPlayer player, final int tick) {
        state(player).blockEnded(tick, true);
    }

    /** A {@code Punch} (every attack click sends one) at the player's tick: blocks raised soon after are block-hits. */
    public static void swung(final ServerPlayer player) {
        final boolean authority = authority(player);
        state(player).swung(tick(player, authority), authority);
    }

    /** A logged packet was applied to the player's truth at client tick {@code tick} (acked, or forced at its deadline). */
    public static void applied(final ServerPlayer player, final Packet<?> packet, final int tick) {
        if (packet instanceof ClientboundSetEntityMotionPacket) {
            state(player).motionApplied(packet, tick);
        }
    }

    /** Whether the player is staggered: their attacks aren't delivered and their {@code Punch}es aren't replayed. */
    public static boolean lockedOut(final ServerPlayer player) {
        final NeueState state = state(player);
        if (!state.staggered()) {
            return false;
        }
        final boolean authority = authority(player);
        return state.lockedOut(authority, tick(player, authority), now(), rttTicks(player, authority));
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Patch hooks
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Hook P, top of {@code LivingEntity.revivalHurtServer} after the removed/dead checks (ServerPlayer.hurtServer's
     * invulnerability and team checks already ran): whether a fresh sword block parried this hit (§8.3). A parried hit
     * deals nothing (no damage, knock, window or flash); the parrier gets a riposte and a re-armed sprint knock; a melee
     * attacker is staggered.
     */
    public static boolean parried(final LivingEntity victim, final DamageSource source) {
        if (!(victim instanceof final ServerPlayer parrier)) {
            return false;
        }
        final NeueState state = state(parrier);
        if (!state.mayParry() || !(source.getEntity() instanceof final ServerPlayer attacker) || attacker == parrier) {
            return false;
        }
        final RevivalSettings live = Revival.settings();
        final LegacyCombatSettings settings = live.legacyCombat(parrier.level().getWorld().getName());
        final CombatRules rules = settings.rules();
        if (!rules.parry() || !live.swordBlocking().enabled()) {
            return false;
        }
        final boolean melee = LegacyAttack.settings(source) != null;
        final Projectile projectile = !melee && rules.parryProjectiles() && source.getDirectEntity() instanceof final Projectile thrown
            && settings.damageWindows().meleeProjectiles().contains(BuiltInRegistries.ENTITY_TYPE.getKey(thrown.getType())) ? thrown : null;
        if (!melee && projectile == null || !LegacySwordBlocking.isSwordBlocking(parrier)) {
            return false;
        }
        final boolean authority = authority(parrier);
        final long now = now();
        if (!state.parries(tick(parrier, authority), now, authority ? rules.parryJitterTicks() : 0)) {
            return false;
        }
        if (RevivalParryEvent.getHandlerList().getRegisteredListeners().length > 0
            && !new RevivalParryEvent(parrier.getBukkitEntity(), attacker.getBukkitEntity(),
                projectile == null ? null : (org.bukkit.entity.Projectile) projectile.getBukkitEntity()).callEvent()) {
            return false; // a plain blocked hit
        }
        state.parried(now, rules.riposteTicks());
        parrier.revivalSprintLedger.rearm(parrier.onGround());
        if (melee) {
            final NeueState attackerState = state(attacker);
            if (attackerState.on(parrier.getId())) {
                attackerState.mark(NeueState.PARRIED);
            }
            stagger(attacker, parrier, settings, live);
        }
        parryCue(parrier, attacker, rules.feedback());
        return true;
    }

    /**
     * Hook W, {@code revivalHurtServer}'s window rule ({@code damage <= lastHurt} inside a shut window), before it returns
     * false: a legacy melee click on a player the window turned away is held (§5.2).
     */
    public static void windowRejected(final LivingEntity victim, final DamageSource source) {
        if (victim instanceof ServerPlayer && source.getEntity() instanceof final ServerPlayer attacker && LegacyAttack.settings(source) != null) {
            final NeueState state = state(attacker);
            if (state.on(victim.getId())) {
                state.mark(NeueState.HELD);
            }
        }
    }

    /**
     * Optional, {@code Player.attack}'s {@code PLAYER_ATTACK_NODAMAGE}: skipped for a click that is held (it lands a few
     * ticks later, with its own sound) or parried (the parry has its own), so neither whooshes first.
     */
    public static boolean quiet(final Player attacker) {
        if (!(attacker instanceof final ServerPlayer player)) {
            return false;
        }
        final NeueState state = state(player);
        return state.active() && (state.outcome() == NeueState.HELD || state.outcome() == NeueState.PARRIED);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The legacy combat classes
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * From {@link LegacyHitRegistration#beforeAttack}, first in {@code Player.attack}: false refuses the attack (a stagger
     * lockout, or the click cap outside authority). An attack the netcode didn't begin (outside authority, or a plugin's
     * {@code Player.attack}) gets its own context here, settled when the next one starts or on the next tick.
     */
    static boolean beforeAttack(final ServerPlayer player, final @Nullable LegacyCombatSettings settings) {
        final NeueState state = state(player);
        final boolean entered = state.enter();
        if (state.staggered() && lockedOut(player)) {
            if (entered) {
                state.mark(NeueState.REFUSED);
            }
            return false;
        }
        if (entered || settings == null) {
            return true;
        }
        final CombatRules rules = settings.rules();
        final boolean authority = authority(player);
        state.swung(tick(player, authority), authority); // an attack is a swing, also where no Punch hook reports one
        if (!rules.buffersClicks() && !rules.shapesKnock() && !rules.capsClicks()) {
            return true; // classic: nothing to track
        }
        settle(player, state);
        if (!authority && rules.capsClicks() && !state.takeClick(now(), rules.clickRefill(), rules.clickBurst())) {
            return false;
        }
        state.begin(NeueState.UNKNOWN_TARGET, null, Double.NaN, false);
        state.enter();
        return true;
    }

    /** {@link LegacyHitRegistration#beforeAttack}'s own per-tick cap refused the attack: never delivered. */
    static void refused(final ServerPlayer player) {
        final NeueState state = state(player);
        if (state.active()) {
            state.mark(NeueState.REFUSED);
        }
    }

    /**
     * Every server tick, from {@link LegacyHitRegistration#tick} (the level phase): settles an attack run outside the
     * netcode, takes the stagger's slow off and notices a block coming down. Held clicks land in {@link #serverTick}.
     */
    static void tick(final ServerPlayer player) {
        final NeueState state = state(player);
        if (state.active()) {
            settle(player, state);
        }
        if (state.slowed()) {
            final boolean authority = authority(player);
            final int rtt = authority ? (int) Math.round(player.revivalAuth.transactions().rttBaseNanos() / 50_000_000.0) : 0;
            if (state.slowEnds(authority, tick(player, authority), now(), rtt)) {
                final AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.removeModifier(STAGGER_SLOW);
                }
            }
        }
        if (state.blocking() && !LegacySwordBlocking.isSwordBlocking(player)) {
            // The netcode reports a block coming down at its exact client tick; this catches the rest
            final boolean authority = authority(player);
            state.blockEnded(tick(player, authority), authority);
        }
    }

    /** A sword block went up on the server ({@link LegacySwordBlocking#startBlocking}). */
    static void blockStarted(final ServerPlayer player) {
        final boolean authority = authority(player);
        state(player).blockStarted(tick(player, authority), now(), authority, rules(player));
    }

    /** {@link LegacyAttack#canCriticalAttack}: the parrier's next hit within the riposte is a crit. */
    public static boolean riposteReady(final Player attacker) {
        return attacker instanceof final ServerPlayer player && state(player).riposteReady(now());
    }

    /** {@link LegacyDefence#lastHurt}: a legacy melee hit landed, fresh or as an overflow. Uses up a riposte. */
    static void landed(final DamageSource source) {
        if (source.getEntity() instanceof final ServerPlayer attacker) {
            final NeueState state = state(attacker);
            state.consumeRiposte(now());
            state.mark(NeueState.LANDED); // the attack being run: one legacy attack hurts one victim (no sweeps)
        }
    }

    /**
     * {@link LegacyKnockback#targetMovement}, a fresh legacy knock under rules that shape it: the tipper by the hit's
     * distance (the judge's, the landing's, else server positions), then DI from the victim's held keys (§6-7).
     */
    static Vec3 shapeMelee(final Vec3 knock, final LivingEntity victim, final ServerPlayer attacker, final CombatRules rules, final KnockbackProfile profile) {
        final NeueState state = state(attacker);
        double distance = state.on(victim.getId()) ? state.distance() : Double.NaN;
        if (Double.isNaN(distance)) {
            distance = NeueReach.serverDistance(attacker, victim);
        }
        int spot = NeueShape.spot(distance, rules);
        if (NeueShape.scale(spot, rules) == 1.0) {
            spot = NeueShape.PLAIN; // Combo reads a spot that changes nothing as plain
        }
        state.spot(spot);
        final Vec3 shaped = victim instanceof final ServerPlayer target && rules.influenceDegrees() > 0.0
            ? steer(knock, NeueShape.scale(spot, rules), rules.influenceDegrees(), target, profile)
            : NeueShape.shape(knock, NeueShape.scale(spot, rules), 0.0, 0, 0, 0.0F, profile.velocityClamp());
        if (spot != NeueShape.PLAIN) {
            final Vec3 eye = attacker.getEyePosition();
            final Vec3 look = attacker.getLookAngle();
            final double reach = Math.min(distance, 3.0); // the ray's entry point, near enough for a spark
            final CombatRules.Cue cue = spot == NeueShape.TIPPER ? rules.feedback().tipper() : rules.feedback().sour();
            cue(attacker, attacker, cue, eye.x + look.x * reach, eye.y + look.y * reach, eye.z + look.z * reach, 0.1);
        }
        return shaped;
    }

    /**
     * {@link LegacyProjectiles#targetMovement}, after Punch: DI on the knock of a projectile that shares the melee window
     * (rod, snowball, egg; Combo's {@code Combat.Rod}), with {@code influence-projectiles}. Arrows keep 1.7's knock.
     */
    static Vec3 influenceProjectile(final Vec3 knocked, final LivingEntity victim, final Projectile projectile, final KnockbackProfile profile) {
        if (!(victim instanceof final ServerPlayer target)) {
            return knocked;
        }
        final LegacyCombatSettings settings = Revival.settings().legacyCombat(victim.level().getWorld().getName());
        final CombatRules rules = settings.rules();
        if (!rules.influenceProjectiles() || rules.influenceDegrees() <= 0.0
            || !settings.damageWindows().meleeProjectiles().contains(BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()))) {
            return knocked;
        }
        return steer(knocked, 1.0, rules.influenceDegrees(), target, profile);
    }

    // The victim's held keys and yaw: their latest simulated client tick under authority, their last input packet otherwise
    private static Vec3 steer(final Vec3 knock, final double scale, final double degrees, final ServerPlayer victim, final KnockbackProfile profile) {
        final Input input;
        final float yaw;
        if (victim.revivalAuth.ownsClientState()) {
            input = victim.revivalAuth.stream().input();
            yaw = victim.revivalAuth.tickYRot();
        } else {
            input = victim.getLastClientInput();
            yaw = victim.getYRot();
        }
        final int forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        final int left = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        return NeueShape.shape(knock, scale, degrees, forward, left, yaw, profile.velocityClamp());
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The stagger
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * A parried attacker (§8.4): thrown back, the sword's cooldown sweep (which also stops a block going up on the
     * client), the block dropped, attacks refused until the recoil's ack plus the stagger, optionally slowed, and a
     * sound for them alone. The netcode bundles what this sends into one ack window.
     */
    static void stagger(final ServerPlayer attacker, final ServerPlayer parrier, final LegacyCombatSettings settings, final RevivalSettings live) {
        final CombatRules rules = settings.rules();
        // Away from the parrier on truth positions; overlapping, along the parrier's facing
        double ax = attacker.getX() - parrier.getX();
        double az = attacker.getZ() - parrier.getZ();
        double length = Math.sqrt(ax * ax + az * az);
        if (length < 1.0E-9) {
            ax = -Mth.sin(parrier.getYRot() * Mth.DEG_TO_RAD);
            az = Mth.cos(parrier.getYRot() * Mth.DEG_TO_RAD);
            length = Math.sqrt(ax * ax + az * az);
        }
        final Vec3 recoil = new Vec3(ax / length * rules.staggerRecoilHorizontal(), rules.staggerRecoilVertical(), az / length * rules.staggerRecoilHorizontal());
        ClientboundSetEntityMotionPacket packet = null;
        final PlayerVelocityEvent event = new PlayerVelocityEvent(attacker.getBukkitEntity(), CraftVector.toBukkit(recoil));
        if (event.callEvent()) {
            final Vec3 sent = CraftVector.toVec3(event.getVelocity());
            packet = new ClientboundSetEntityMotionPacket(attacker.getId(), sent);
            attacker.connection.send(packet);
            final KnockbackProfile own = attacker.revivalKnockbackProfile != null ? live.knockbackProfile(attacker.revivalKnockbackProfile) : null;
            if ((own != null ? own : settings.knockback()).motionModel() == KnockbackProfile.MotionModel.VANILLA_1_7) {
                attacker.revivalMotionShadow.set(sent);
            }
        }
        final int ticks = rules.staggerTicks();
        final ItemStack weapon = attacker.getMainHandItem();
        if (rules.staggerSwordCooldown() && ticks > 0 && LegacyAttack.isLegacyWeapon(weapon, settings)) {
            attacker.getCooldowns().addCooldown(weapon, ticks);
        }
        if (rules.staggerLowersBlock() && LegacySwordBlocking.isSwordBlocking(attacker)) {
            attacker.stopUsingItem();
        }
        final boolean slow = rules.staggerMoveMultiplier() < 1.0 && ticks > 0;
        state(attacker).stagger(now(), packet, ticks, slow);
        if (slow) {
            final AttributeInstance speed = attacker.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                speed.addOrUpdateTransientModifier(new AttributeModifier(STAGGER_SLOW, rules.staggerMoveMultiplier() - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
        cue(attacker, attacker, rules.feedback().stagger(), attacker.getX(), attacker.getEyeY(), attacker.getZ(), 0.0);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Outside authority (§11)
    // ------------------------------------------------------------------------------------------------------------------

    // A click held outside authority lands here, once due: server eye, server box, not lag-compensated
    private static void landOnServer(final ServerPlayer attacker, final ServerPlayer victim, final NeueState state, final boolean lineOfSight) {
        final double distance = NeueReach.server(attacker, victim, lineOfSight);
        if (Double.isNaN(distance) || !attacker.level().getWorldBorder().isWithinBounds(victim.blockPosition())) {
            state.outOfReach();
            return;
        }
        state.begin(victim.getId(), null, distance, true);
        state.enter();
        attacker.attack(victim);
        settle(attacker, state);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------------

    // The held click's target, if it can still be hit: a player, alive, here, not a spectator
    private static @Nullable ServerPlayer target(final ServerPlayer attacker, final int id) {
        return attacker.level().getEntity(id) instanceof final ServerPlayer victim && victim != attacker && victim.isAlive()
            && !victim.isRemoved() && !victim.isSpectator() && victim.level() == attacker.level() ? victim : null;
    }

    /** 1.7's {@code hurtResistantTime > maxHurtResistantTime / 2}, inverted: the melee window is open to a fresh hit. */
    static boolean windowOpen(final LivingEntity victim) {
        return !(victim.damageCooldownTime > victim.invulnerableDuration / 2.0F);
    }

    // §5.2 step 3: the attacker's client would swing now with the same weapon in the same slot
    private static boolean freeUnderAuthority(final ServerPlayer attacker, final NeueState state) {
        final gg.hcfrevival.revival.auth.AuthPlayer auth = attacker.revivalAuth;
        if (!attacker.isAlive() || !auth.ownsClientState() || auth.frozen() || auth.teleportPending() || auth.simulation().usingItem()
            || attacker.containerMenu != attacker.inventoryMenu || lockedOut(attacker)) {
            return false;
        }
        final ItemStack held = auth.view().items.mainHand();
        return auth.view().items.selected() == state.pendingSlot() && held.getItem() == state.pendingWeapon() && LegacyAttack.settings(attacker, held) != null;
    }

    private static boolean freeOnServer(final ServerPlayer attacker, final NeueState state) {
        if (!attacker.isAlive() || attacker.isUsingItem() || attacker.containerMenu != attacker.inventoryMenu || lockedOut(attacker)) {
            return false;
        }
        final ItemStack held = attacker.getMainHandItem();
        return attacker.getInventory().getSelectedSlot() == state.pendingSlot() && held.getItem() == state.pendingWeapon() && LegacyAttack.settings(attacker, held) != null;
    }

    // The parry's cue: a sound everyone near hears at the parrier, and a sweep with sparks at chest height toward the attacker
    private static void parryCue(final ServerPlayer parrier, final ServerPlayer attacker, final CombatRules.Feedback feedback) {
        double ax = attacker.getX() - parrier.getX();
        double az = attacker.getZ() - parrier.getZ();
        final double length = Math.sqrt(ax * ax + az * az);
        if (length > 1.0E-9) {
            ax /= length;
            az /= length;
        }
        final double x = parrier.getX() + ax * 0.6;
        final double y = parrier.getY() + parrier.getBbHeight() * 0.65;
        final double z = parrier.getZ() + az * 0.6;
        cue(parrier, null, feedback.parry(), x, y, z, 0.0);
        cue(parrier, null, feedback.parrySpark(), x, y, z, 0.2);
    }

    /**
     * One cue at (x, y, z): its sound to {@code only} when given (a private cue), otherwise to everyone near; its
     * particles to everyone near. Server-side sounds and particles respect visibility hiding.
     */
    private static void cue(final ServerPlayer source, final @Nullable ServerPlayer only, final CombatRules.Cue cue, final double x, final double y, final double z, final double spread) {
        if (cue.sound() != null) {
            final Holder<SoundEvent> sound = sound(cue.sound());
            if (only != null) {
                only.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS, x, y, z, cue.volume(), cue.pitch(), only.getRandom().nextLong()));
            } else {
                source.level().playSound(null, x, y, z, sound, SoundSource.PLAYERS, cue.volume(), cue.pitch());
            }
        }
        if (cue.particle() != null && cue.count() > 0) {
            final ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(cue.particle());
            if (type instanceof final SimpleParticleType simple) {
                source.level().sendParticles(simple, x, y, z, cue.count(), spread, spread, spread, 0.0);
            }
        }
    }

    // A registered sound, or a resource-pack one by id
    private static Holder<SoundEvent> sound(final Identifier id) {
        final Optional<Holder.Reference<SoundEvent>> known = BuiltInRegistries.SOUND_EVENT.get(id);
        return known.isPresent() ? known.get() : Holder.direct(SoundEvent.createVariableRangeEvent(id));
    }
}
