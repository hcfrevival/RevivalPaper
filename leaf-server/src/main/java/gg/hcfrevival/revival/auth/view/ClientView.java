package gg.hcfrevival.revival.auth.view;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * Everything movement reads that the client owns or holds its own copy of (design §4, report 01 §7, report 02 §3).
 * It changes only through acked packets ({@code AuthPlayer} applies them at the ack tick) and the client's own actions in
 * its windows, never by reading the server's live state (except to seed it when authority starts mid-session).
 */
public final class ClientView {

    /** Entity data ids (stable: {@code defineId} order). Checked against the real accessors by ClientViewTest. */
    public static final int DATA_SHARED_FLAGS = 0;
    public static final int DATA_NO_GRAVITY = 5;
    public static final int DATA_POSE = 6;
    public static final int DATA_LIVING_ENTITY_FLAGS = 8;

    /** One effect as the client holds it: presence and amplifier act on movement; the client never expires it. */
    public record Effect(int amplifier, int duration, boolean ambient, boolean visible) {
    }

    public final ClientAttributes attributes = new ClientAttributes();
    public final ClientItems items = new ClientItems();
    private final Reference2ObjectOpenHashMap<Holder<MobEffect>, Effect> effects = new Reference2ObjectOpenHashMap<>();
    private final Object2IntOpenHashMap<Identifier> cooldownEnds = new Object2IntOpenHashMap<>();

    // Abilities (ClientboundPlayerAbilitiesPacket, game mode switches)
    private boolean flying;
    private boolean mayfly;
    private boolean instabuild;
    private boolean invulnerable;
    private float flyingSpeed = 0.05F;
    private float walkingSpeed = 0.1F;
    private GameType gameMode = GameType.SURVIVAL;

    private int foodLevel = 20;
    private float saturation = 5.0F;
    private float health = 20.0F;

    // Self entity-data echoes as the client last assigned them
    private byte sharedFlags;
    private Pose pose = Pose.STANDING;
    private boolean noGravity;

    private float tickRate = 20.0F;
    private boolean frozen;

    private boolean loaded;
    private boolean passenger;
    private int vehicle = -1;
    private boolean dead;
    private boolean cameraOther;
    private @Nullable GameType tabGameMode;
    private int clientTicks;

    // Teams as the client holds them: its own collision rule decides whether other players push it
    private final java.util.HashMap<String, net.minecraft.world.scores.Team.CollisionRule> teamRules = new java.util.HashMap<>();
    private final java.util.HashMap<String, String> playerTeams = new java.util.HashMap<>();
    private String selfName = "";

    /** Starts from the server's state, as if the client had just been sent everything. */
    public void seed(final ServerPlayer player) {
        this.attributes.seed(player.getAttributes());
        this.items.seed(player.getInventory());
        this.effects.clear();
        for (final MobEffectInstance effect : player.getActiveEffects()) {
            this.effects.put(effect.getEffect(), new Effect(effect.getAmplifier(), effect.getDuration(), effect.isAmbient(), effect.isVisible()));
        }
        final Abilities abilities = player.getAbilities();
        this.flying = abilities.flying;
        this.mayfly = abilities.mayfly;
        this.instabuild = abilities.instabuild;
        this.invulnerable = abilities.invulnerable;
        this.flyingSpeed = abilities.getFlyingSpeed();
        this.walkingSpeed = abilities.getWalkingSpeed();
        this.gameMode = player.gameMode();
        this.foodLevel = player.getFoodData().getFoodLevel();
        this.saturation = player.getFoodData().getSaturationLevel();
        this.health = player.getHealth();
        this.pose = player.getPose();
        this.noGravity = player.isNoGravity();
        this.sharedFlags = 0;
        if (player.isSprinting()) {
            this.sharedFlags |= 1 << 3;
        }
        if (player.isSwimming()) {
            this.sharedFlags |= 1 << 4;
        }
        if (player.isFallFlying()) {
            this.sharedFlags |= (byte) (1 << 7);
        }
        this.tickRate = player.level().tickRateManager().tickrate();
        this.frozen = player.level().tickRateManager().isFrozen();
        this.loaded = player.connection == null || player.connection.hasClientLoaded();
        this.passenger = player.isPassenger();
        this.vehicle = player.getVehicle() != null ? player.getVehicle().getId() : -1;
        this.dead = player.isDeadOrDying();
        this.cameraOther = player.getCamera() != player;
        this.tabGameMode = null;
        this.cooldownEnds.clear();
        this.selfName = player.getScoreboardName();
        this.teamRules.clear();
        this.playerTeams.clear();
        final net.minecraft.world.scores.PlayerTeam team = player.getTeam();
        if (team != null) {
            final boolean collisions = io.papermc.paper.configuration.GlobalConfiguration.get().collisions.enablePlayerCollisions;
            this.teamRules.put(team.getName(), collisions ? team.getCollisionRule() : net.minecraft.world.scores.Team.CollisionRule.NEVER);
            this.playerTeams.put(this.selfName, team.getName());
        }
    }

    // Acked packets --------------------------------------------------------------------------------------------------

    /** {@code handlePlayerAbilities}. */
    public void abilities(final ClientboundPlayerAbilitiesPacket packet) {
        this.flying = packet.isFlying();
        this.instabuild = packet.canInstabuild();
        this.invulnerable = packet.isInvulnerable();
        this.mayfly = packet.canFly();
        this.flyingSpeed = packet.getFlyingSpeed();
        this.walkingSpeed = packet.getWalkingSpeed();
    }

    /** {@code GameEvent CHANGE_GAME_MODE}: {@code setLocalMode} → {@code GameType.updatePlayerAbilities}. */
    public void gameMode(final GameType type) {
        this.gameMode = type;
        final Abilities abilities = new Abilities();
        abilities.flying = this.flying;
        type.updatePlayerAbilities(abilities);
        this.flying = abilities.flying;
        this.mayfly = abilities.mayfly;
        this.instabuild = abilities.instabuild;
        this.invulnerable = abilities.invulnerable;
    }

    /** {@code handleSetHealth}: food gates sprinting ({@code foodLevel > 6 || mayfly}); health 0 opens the death screen. */
    public void health(final ClientboundSetHealthPacket packet) {
        this.health = packet.getHealth();
        this.dead = packet.getHealth() <= 0.0F;
        this.foodLevel = packet.getFood();
        this.saturation = packet.getSaturation();
    }

    /** {@code handleUpdateMobEffect}: {@code forceAddEffect}. Speed/slowness reach movement through attributes only. */
    public void addEffect(final ClientboundUpdateMobEffectPacket packet) {
        this.effects.put(packet.getEffect(), new Effect(packet.getEffectAmplifier(), packet.getEffectDurationTicks(), packet.isEffectAmbient(), packet.isEffectVisible()));
    }

    /** {@code handleRemoveMobEffect}: the only way an effect ends on the client (report 02 §3.7, H16). */
    public void removeEffect(final Holder<MobEffect> effect) {
        this.effects.remove(effect);
    }

    /** {@code handleTickingState}: the client's tick period from the next frame. */
    public void tickingState(final float rate, final boolean frozen) {
        this.tickRate = rate;
        this.frozen = frozen;
    }

    /** {@code handleItemCooldown}: starts now, counted down once per client tick. */
    public void cooldown(final Identifier group, final int duration) {
        if (duration <= 0) {
            this.cooldownEnds.removeInt(group);
        } else {
            this.cooldownEnds.put(group, this.clientTicks + duration);
        }
    }

    /**
     * Self entity data ({@code assignValues}): returns the fields that matter to movement through the callbacks the
     * caller gives (the simulation's echo methods). Values for other fields are ignored.
     */
    public void entityData(final List<SynchedEntityData.DataValue<?>> values, final EchoSink sink) {
        for (final SynchedEntityData.DataValue<?> value : values) {
            switch (value.id()) {
                case DATA_SHARED_FLAGS -> {
                    if (value.value() instanceof final Byte flags) {
                        this.sharedFlags = flags;
                        sink.sharedFlags(flags);
                    }
                }
                case DATA_POSE -> {
                    if (value.value() instanceof final Pose newPose) {
                        this.pose = newPose;
                        sink.pose(newPose);
                    }
                }
                case DATA_NO_GRAVITY -> {
                    if (value.value() instanceof final Boolean flag) {
                        this.noGravity = flag;
                    }
                }
                case DATA_LIVING_ENTITY_FLAGS -> {
                    if (value.value() instanceof final Byte flags) {
                        sink.usingItem((flags & 1) != 0, (flags & 2) != 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
                    }
                }
                default -> {
                }
            }
        }
    }

    /** Where {@link #entityData} sends the echoes that change the client's movement state. */
    public interface EchoSink {
        void sharedFlags(byte flags);

        void pose(Pose pose);

        void usingItem(boolean using, InteractionHand hand);
    }

    /** Respawn: a new local player. Attributes keep modifiers or only bases; effects are gone. */
    public void respawn(final boolean keepAttributeModifiers, final boolean keepEntityData) {
        this.attributes.respawn(keepAttributeModifiers);
        this.effects.clear();
        if (!keepEntityData) {
            this.sharedFlags = 0;
            this.pose = Pose.STANDING;
            this.noGravity = false;
        }
        this.loaded = false;
        this.dead = false;
        this.passenger = false;
        this.vehicle = -1;
        this.cameraOther = false;
    }

    /** {@code SetPassengers}: the client rides {@code vehicle} when it is among the passengers. */
    public void passengers(final int vehicle, final int[] passengers, final int self) {
        boolean riding = false;
        for (final int passenger : passengers) {
            riding |= passenger == self;
        }
        if (riding) {
            this.passenger = true;
            this.vehicle = vehicle;
        } else if (vehicle == this.vehicle) {
            this.passenger = false;
            this.vehicle = -1;
        }
    }

    /** {@code SetCamera}: spectating another entity (exempt). */
    public void camera(final boolean other) {
        this.cameraOther = other;
    }

    /** Self {@code PlayerInfoUpdate} game mode: {@code isSpectator()} on the client reads the tab list. */
    public void tabGameMode(final GameType mode) {
        this.tabGameMode = mode;
    }

    /** {@code SetPlayerTeam}, as {@code ClientPacketListener.handleSetPlayerTeamPacket} applies it. */
    public void team(final net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket packet) {
        final String name = packet.getName();
        final var teamAction = packet.getTeamAction();
        if (teamAction == net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            this.teamRules.remove(name);
            this.playerTeams.values().removeIf(name::equals);
            return;
        }
        packet.getParameters().ifPresent(parameters -> this.teamRules.put(name, parameters.collisionRule()));
        final var playerAction = packet.getPlayerAction();
        if (playerAction == net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket.Action.ADD) {
            for (final String player : packet.getPlayers()) {
                this.playerTeams.put(player, name);
            }
        } else if (playerAction == net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            for (final String player : packet.getPlayers()) {
                this.playerTeams.remove(player, name);
            }
        }
    }

    /**
     * The authority precondition (§15.3): the client sees itself on a team with collision rule NEVER, so no other
     * player pushes it ({@code EntitySelector.pushableBy}).
     */
    public boolean selfCollisionNever() {
        final String team = this.playerTeams.get(this.selfName);
        return team != null && this.teamRules.get(team) == net.minecraft.world.scores.Team.CollisionRule.NEVER;
    }

    // Client actions ------------------------------------------------------------------------------------------------

    /** {@code PlayerLoaded}: the client moves from this tick on. */
    public void setLoaded(final boolean loaded) {
        this.loaded = loaded;
    }

    /** One client tick ran: cooldowns count down. */
    public void tick() {
        this.clientTicks++;
    }

    /**
     * Whether the client predicts item use for {@code stack} ({@code MultiPlayerGameMode.useItem} → {@code Item.use}):
     * cooldowns give PASS; a consumable starts when it can be consumed and takes time (the edible sword: always); a
     * blocking or kinetic item starts. Returns null when the item's class overrides {@code use} (bows, tridents,
     * spyglasses...), whose prediction isn't mirrored yet: the caller falls back to the server's own result.
     */
    public @Nullable Boolean predictsUse(final ItemStack stack) {
        if (stack.isEmpty()) {
            return Boolean.FALSE;
        }
        if (this.onCooldown(stack)) {
            return Boolean.FALSE;
        }
        final Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable != null) {
            final FoodProperties food = stack.get(DataComponents.FOOD);
            final boolean canConsume = food == null || this.invulnerable || food.canAlwaysEat() || this.foodLevel < 20;
            return canConsume && consumable.consumeTicks() > 0;
        }
        if (OVERRIDES_USE.get(stack.getItem().getClass())) {
            return null;
        }
        if (stack.has(DataComponents.EQUIPPABLE) && stack.get(DataComponents.EQUIPPABLE).swappable()) {
            return Boolean.FALSE;
        }
        return stack.has(DataComponents.BLOCKS_ATTACKS) || stack.has(DataComponents.KINETIC_WEAPON);
    }

    // Whether an item class has its own Item.use (bows, tridents, spyglasses...), whose prediction isn't mirrored
    private static final ClassValue<Boolean> OVERRIDES_USE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            try {
                return type.getMethod("use", net.minecraft.world.level.Level.class, net.minecraft.world.entity.player.Player.class, InteractionHand.class)
                    .getDeclaringClass() != Item.class;
            } catch (final NoSuchMethodException e) {
                return Boolean.TRUE;
            }
        }
    };

    /** {@code ItemCooldowns.isOnCooldown} with the client's cooldown group. */
    public boolean onCooldown(final ItemStack stack) {
        if (this.cooldownEnds.isEmpty()) {
            return false;
        }
        final UseCooldown useCooldown = stack.get(DataComponents.USE_COOLDOWN);
        final Identifier group = useCooldown != null && useCooldown.cooldownGroup().isPresent()
            ? useCooldown.cooldownGroup().get()
            : BuiltInRegistries.ITEM.getKey(stack.getItem());
        final int end = this.cooldownEnds.getOrDefault(group, Integer.MIN_VALUE);
        return end > this.clientTicks;
    }

    // Reads ----------------------------------------------------------------------------------------------------------

    public boolean hasEffect(final Holder<MobEffect> effect) {
        return this.effects.containsKey(effect);
    }

    public @Nullable Effect effect(final Holder<MobEffect> effect) {
        return this.effects.get(effect);
    }

    public int effectCount() {
        return this.effects.size();
    }

    public boolean flying() {
        return this.flying;
    }

    /** The client toggled flight itself (aiStep) and reported it with {@code PlayerAbilities}. */
    public void setFlying(final boolean flying) {
        this.flying = flying;
    }

    public boolean mayfly() {
        return this.mayfly;
    }

    public boolean instabuild() {
        return this.instabuild;
    }

    public boolean invulnerable() {
        return this.invulnerable;
    }

    public float flyingSpeed() {
        return this.flyingSpeed;
    }

    public float walkingSpeed() {
        return this.walkingSpeed;
    }

    public GameType gameMode() {
        return this.gameMode;
    }

    /** {@code LocalPlayer.isSpectator()}: the tab-list mode once the client has one, else the local mode. */
    public boolean spectator() {
        return (this.tabGameMode != null ? this.tabGameMode : this.gameMode) == GameType.SPECTATOR;
    }

    public boolean dead() {
        return this.dead;
    }

    public boolean cameraOther() {
        return this.cameraOther;
    }

    public int foodLevel() {
        return this.foodLevel;
    }

    public float saturation() {
        return this.saturation;
    }

    public float health() {
        return this.health;
    }

    public byte sharedFlags() {
        return this.sharedFlags;
    }

    public Pose pose() {
        return this.pose;
    }

    public boolean noGravity() {
        return this.noGravity;
    }

    public boolean loaded() {
        return this.loaded;
    }

    public boolean passenger() {
        return this.passenger;
    }

    public int vehicle() {
        return this.vehicle;
    }

    public int clientTicks() {
        return this.clientTicks;
    }

    public float tickRate() {
        return this.tickRate;
    }

    public boolean frozen() {
        return this.frozen;
    }

    /** The client's tick period: {@code max(50 ms, 1000 / rate)} while running normally, 50 ms when frozen (H18). */
    public long tickPeriodNanos() {
        if (this.frozen || this.tickRate <= 0.0F) {
            return 50_000_000L;
        }
        return Math.max(50_000_000L, (long) (1.0E9 / this.tickRate));
    }
}
