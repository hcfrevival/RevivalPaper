package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.Revival;
import io.netty.channel.ChannelFutureListener;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Static entry points for the authoritative-netcode hooks (patches 1011-1013) and the switches other parts plug into.
 * Per-player work happens in {@link AuthPlayer} ({@code ServerPlayer.revivalAuth}).
 * <p>
 * Authority refuses to run (stays {@link AuthMode#OFF}, says why once) while Leaf's async entity tracker or parallel
 * world ticking is on: both send or handle a player's packets off the main thread, so transaction order would no longer
 * be wire order (design §2.2, report 04 §11).
 */
public final class Authority {

    private static final Logger LOGGER = LogManager.getLogger("RevivalPaper");

    private static volatile @Nullable AuthMode override;
    private static volatile @Nullable String loggedRefusal;
    private static volatile AttackJudge judge = CombatWiring.INSTANCE;

    static {
        // Projectile hits and pearl walls judged on the shooter's client (projectile-hooks.md N1)
        gg.hcfrevival.revival.combat.ProjectileViews.install(CombatWiring.INSTANCE);
    }
    private static volatile ViewerMirrors mirrors = CombatWiring.INSTANCE;
    private static volatile PathSink paths = PathWiring.INSTANCE;
    private static volatile Function<AuthPlayer, Simulation> simulations = player -> new PortSimulation(player.player());

    // Players running in observe or enforce, for the world-change journal, and by entity id for the judges. Main thread
    // only. Review PT2-02: pruned of players who quit every server tick (no quit hook deactivates them), never by a read
    private static final ReferenceArrayList<AuthPlayer> ACTIVE = new ReferenceArrayList<>();
    private static final java.util.List<AuthPlayer> ACTIVE_VIEW = java.util.Collections.unmodifiableList(ACTIVE);
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<AuthPlayer> ACTIVE_BY_ID = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

    /** Before a player's chunk list is known (authority started mid-session), changes this close are pinned. */
    static final double JOURNAL_RANGE_SQR = 128.0 * 128.0;

    private Authority() {
    }

    // Mode ------------------------------------------------------------------------------------------------------------

    /** Why authority can't run on this server, or null. */
    public static @Nullable String refusal() {
        if (org.dreeam.leaf.config.modules.async.MultithreadedTracker.enabled) {
            return "Leaf async.async-entity-tracker is enabled (packets are sent from tracker threads)";
        }
        if (org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking.enabled) {
            return "Leaf async.parallel-world-ticking is enabled (packets are handled while other worlds tick)";
        }
        return null;
    }

    /** The server-wide mode: {@code /revival auth mode} if set, else revival.yml; OFF when refused. */
    public static AuthMode globalMode() {
        return allowed(override != null ? override : Revival.settings().authoritative().mode());
    }

    /** The mode a player runs in: their API override, else the server-wide mode; OFF when refused. */
    static AuthMode modeFor(final AuthPlayer player) {
        final AuthMode personal = player.override();
        return personal == null ? globalMode() : allowed(personal);
    }

    private static AuthMode allowed(final AuthMode mode) {
        if (mode == AuthMode.OFF) {
            return AuthMode.OFF;
        }
        final String refusal = refusal();
        if (refusal != null) {
            if (!refusal.equals(loggedRefusal)) {
                loggedRefusal = refusal;
                LOGGER.warn("Authoritative movement stays off: {}", refusal);
            }
            return AuthMode.OFF;
        }
        return mode;
    }

    /** {@code /revival auth mode}: overrides revival.yml until the next restart; null goes back to the file. */
    public static void setModeOverride(final @Nullable AuthMode mode) {
        override = mode;
    }

    public static @Nullable AuthMode modeOverride() {
        return override;
    }

    // Plug-ins --------------------------------------------------------------------------------------------------------

    public static void setAttackJudge(final AttackJudge attackJudge) {
        judge = attackJudge;
    }

    public static AttackJudge attackJudge() {
        return judge;
    }

    public static void setMirrors(final ViewerMirrors viewerMirrors) {
        mirrors = viewerMirrors;
    }

    public static ViewerMirrors mirrors() {
        return mirrors;
    }

    public static void setPathSink(final PathSink sink) {
        paths = sink;
    }

    public static PathSink pathSink() {
        return paths;
    }

    /** Installs the movement port; players that start authority afterwards get a fresh simulation from it. */
    public static void setSimulationFactory(final Function<AuthPlayer, Simulation> factory) {
        simulations = factory;
    }

    static Simulation newSimulation(final AuthPlayer player) {
        return simulations.apply(player);
    }

    // Hooks -------------------------------------------------------------------------------------------------------

    /**
     * {@code ServerCommonPacketListenerImpl.handlePong} (hook 8). Pongs arrive on Netty; ours are moved onto the main
     * thread's packet queue so they keep their place among the movement packets (report 04 §2.8). Ids we did not send
     * (positive: other ping users) are left alone.
     */
    public static void onPong(final ServerCommonPacketListenerImpl listener, final ServerboundPongPacket packet) {
        if (!(listener instanceof final ServerGamePacketListenerImpl game)
            || !gg.hcfrevival.revival.auth.net.Transactions.ours(packet.getId()) || !game.player.revivalAuth.netActive()) {
            return;
        }
        PacketUtils.ensureRunningOnSameThread(packet, game, MinecraftServer.getServer().packetProcessor());
        game.player.revivalAuth.onPong(packet.getId());
    }

    /**
     * {@code ServerCommonPacketListenerImpl.send} (hook 9), in place of {@code connection.send}: physics packets to a
     * player under authority are logged and wrapped with a ping, under the connection's lock.
     */
    public static void send(final ServerCommonPacketListenerImpl listener, final Connection connection, final Packet<?> packet,
                            final @Nullable ChannelFutureListener sendListener, final boolean flush) {
        if (listener instanceof final ServerGamePacketListenerImpl game) {
            final AuthPlayer auth = game.player.revivalAuth;
            if (auth.netActive()) {
                auth.send(connection, packet, sendListener, flush);
                return;
            }
        }
        connection.send(packet, sendListener, flush);
    }

    /**
     * {@code LevelChunk.setBlockState}: a block changed (whatever the update flags). Players under authority who hold that
     * chunk keep the old state in their world view until a packet for the position is acked (§15.2).
     */
    public static void onBlockChanged(final net.minecraft.world.level.Level level, final BlockPos pos, final BlockState oldState, final BlockState newState) {
        if (ACTIVE.isEmpty() || oldState == newState || !(level instanceof final ServerLevel serverLevel) || !MinecraftServer.getServer().isSameThread()) {
            return;
        }
        // Review PT-05: only the players the chunk was sent to, not every player under authority in every world. A player
        // whose forget is on its way is no longer listed but still holds the chunk for an RTT: it is leaving that chunk
        final net.minecraft.server.level.ChunkHolder holder = serverLevel.getChunkSource().chunkMap.getVisibleChunkIfPresent(
            net.minecraft.world.level.ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        if (holder == null) {
            return;
        }
        final java.util.List<ServerPlayer> holders = holder.moonrise$getPlayers(false);
        for (int i = 0, size = holders.size(); i < size; i++) {
            final AuthPlayer auth = holders.get(i).revivalAuth;
            if (!auth.netActive() || auth.world().level() != level) {
                continue;
            }
            if (auth.world().chunksKnown()) {
                if (!auth.world().watches(pos.getX(), pos.getZ())) {
                    continue;
                }
            } else {
                final ServerPlayer player = auth.player();
                final double dx = player.getX() - (pos.getX() + 0.5);
                final double dz = player.getZ() - (pos.getZ() + 0.5);
                if (dx * dx + dz * dz > JOURNAL_RANGE_SQR) {
                    continue;
                }
            }
            auth.world().onServerChange(pos, oldState);
        }
    }

    /** {@code ServerCommonPacketListenerImpl.suspendFlushing}: the server tick starts for this connection. */
    public static void onSuspendFlushing(final ServerCommonPacketListenerImpl listener) {
        if (MinecraftServer.currentTick != pruneTick) {
            // Once a server tick, whoever is online (review PT2-02: no quit hook deactivates a player under authority)
            pruneTick = MinecraftServer.currentTick;
            pruneDisconnected();
        }
        if (listener instanceof final ServerGamePacketListenerImpl game) {
            game.player.revivalAuth.onSuspendFlushing();
        }
    }

    /** {@code ServerCommonPacketListenerImpl.resumeFlushing}, before the flush: the tick's bundle goes out. */
    public static void onResumeFlushing(final ServerCommonPacketListenerImpl listener, final Connection connection) {
        if (listener instanceof final ServerGamePacketListenerImpl game) {
            game.player.revivalAuth.onResumeFlushing(connection);
        }
    }

    /** {@code ServerEntity.sendChanges}: the target's vanilla movement packet, withheld from viewers on its feed (R7). */
    public static void sendMovement(final net.minecraft.world.entity.Entity entity, final net.minecraft.server.level.ServerEntity.Synchronizer synchronizer,
                                    final Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> packet) {
        PathWiring.sendMovement(entity, synchronizer, packet);
    }

    /** {@code ServerEntity.sendChanges}, every server tick: the target's authoritative steps to each viewer in range. */
    public static void drainPaths(final net.minecraft.server.level.ServerEntity serverEntity,
                                  final java.util.Set<net.minecraft.server.network.ServerPlayerConnection> tracked) {
        if (serverEntity.entity instanceof final ServerPlayer player && (player.revivalAuth.replicationState != null || player.revivalAuth.netActive())) {
            PathWiring.drain(serverEntity, tracked);
        }
    }

    /** {@code ServerEntity.addPairing}, after the pairing bundle was sent. */
    public static void onPaired(final net.minecraft.world.entity.Entity entity, final ServerPlayer viewer,
                                final java.util.List<? extends Packet<?>> pairing) {
        if (!ACTIVE.isEmpty()) {
            PathWiring.onPaired(entity, viewer, pairing);
        }
    }

    /** {@code ServerEntity.removePairing}. */
    public static void onUnpaired(final net.minecraft.world.entity.Entity entity, final ServerPlayer viewer) {
        if (entity instanceof final ServerPlayer player && player.revivalAuth.replicationState != null) {
            PathWiring.onUnpaired(entity, viewer);
        }
    }

    /** {@code Player.tick}/{@code updateSwimming}/{@code updateFallFlying}/{@code updatingUsingItem}: skip the server's own recomputation. */
    public static boolean ownsClientState(final net.minecraft.world.entity.Entity entity) {
        return entity instanceof final ServerPlayer player && player.revivalAuth.ownsClientState();
    }

    /** {@code Entity.setSharedFlag}: sprint (3), swimming (4) and gliding (7) are client-owned. */
    public static void onServerFlag(final net.minecraft.world.entity.Entity entity, final int flag) {
        if (entity instanceof final ServerPlayer player && (flag == 3 || flag == 4 || flag == 7)) {
            player.revivalAuth.onServerWrite(flag == 3 ? gg.hcfrevival.revival.auth.view.SelfEcho.SPRINT
                : flag == 4 ? gg.hcfrevival.revival.auth.view.SelfEcho.SWIM : gg.hcfrevival.revival.auth.view.SelfEcho.GLIDE);
        }
    }

    /** {@code Entity.setPose}, when the pose changes. */
    public static void onServerPose(final net.minecraft.world.entity.Entity entity) {
        if (entity instanceof final ServerPlayer player) {
            player.revivalAuth.onServerWrite(gg.hcfrevival.revival.auth.view.SelfEcho.POSE);
        }
    }

    /**
     * {@code Entity.startRiding}: with {@code authoritative.vehicles: deny} a player under enforce never mounts (§15.3),
     * since a ridden vehicle's movement comes from the client.
     */
    public static boolean refusesMount(final net.minecraft.world.entity.Entity entity) {
        if (entity instanceof final ServerPlayer player && player.revivalAuth.mode() == AuthMode.ENFORCE
            && "deny".equals(gg.hcfrevival.revival.Revival.settings().authoritative().vehicles())) {
            player.revivalAuth.stats().mountsRefused++;
            return true;
        }
        return false;
    }

    /**
     * {@code ChunkMap.TrackedEntity.updatePlayer}, after the vanish check (patch 1015): whether player {@code target} is
     * sent to {@code viewer} (design §11, {@code visibility.enabled}). Only players under authority are judged, from the
     * blocks they were sent; everyone else sees what vanilla shows. Main thread.
     */
    public static boolean visibleTo(final net.minecraft.world.entity.Entity target, final ServerPlayer viewer) {
        if (!(target instanceof final ServerPlayer player) || player == viewer) {
            return true;
        }
        final gg.hcfrevival.revival.config.AuthoritativeSettings settings = gg.hcfrevival.revival.Revival.settings().authoritative();
        if (!settings.visibilityEnabled()) {
            return true;
        }
        return viewer.revivalAuth.sees(player, settings);
    }

    /** {@code LivingEntity.resyncUsingItem}, before Paper resends the use flags. */
    public static void onServerResync(final net.minecraft.world.entity.Entity entity) {
        if (entity instanceof final ServerPlayer player) {
            player.revivalAuth.onServerResync();
        }
    }

    /** {@code LivingEntity.setLivingEntityFlag}: the using-item flags. */
    public static void onServerUse(final net.minecraft.world.entity.Entity entity) {
        if (entity instanceof final ServerPlayer player) {
            player.revivalAuth.onServerWrite(gg.hcfrevival.revival.auth.view.SelfEcho.USE);
        }
    }

    private static long ineligibleJoins;
    private static int visibilityTick = Integer.MIN_VALUE;
    private static int visibilityChecks;

    // Review PT-08: one budget of visibility checks per server tick for the whole server (main thread)
    static boolean takeVisibilityCheck(final int tick, final int max) {
        if (tick != visibilityTick) {
            visibilityTick = tick;
            visibilityChecks = 0;
        }
        if (visibilityChecks >= max) {
            return false;
        }
        visibilityChecks++;
        return true;
    }
    private static boolean ineligibleWarned;

    // A player who can't be under authority joined (§15.3, review E4): counted, and one warning while enforce is on
    static void countIneligible() {
        ineligibleJoins++;
        if (!ineligibleWarned && Revival.settings().authoritative().mode() == AuthMode.ENFORCE
            && Revival.settings().authoritative().ineligible().equals("allow")) {
            ineligibleWarned = true;
            LOGGER.warn("[auth] players on another client version (ViaVersion) or Bedrock (Geyser) play outside authoritative movement, "
                + "under vanilla rules; set authoritative.ineligible: deny to refuse them while enforce is on");
        }
    }

    /** Players who joined but could not be under authority (another protocol, Bedrock), since startup. */
    public static long ineligibleJoins() {
        return ineligibleJoins;
    }

    // Torture F6: the lag-compensation clock, server time. Rewind ages are measured on wall time less how far the server
    // has fallen behind its own tick schedule, so a hitch is never the attacker's: an attack the hitch held is judged in
    // the first tick after it, a tick after the box it was aimed at. The ticks that catch up shrink the lag again, so
    // the clock never drifts from wall time on an ordinary server (per-tick jitter cancels); ticks Paper skips stay off it
    private static volatile long lagNanos;
    // When the next measurement is due on schedule: a tick still running past it (a hitch in progress, or the packets
    // handled right after one, before the next tick's measurement) is late by that much already
    private static volatile long nextDueNanos = Long.MAX_VALUE;
    private static int clockBaseTick = Integer.MIN_VALUE;
    private static long clockBaseNanos;
    private static long clockPeriodNanos;
    private static int clockLastTick = Integer.MIN_VALUE;

    /** Main thread, from every player's connection tick: where the server stands against its tick schedule. */
    static void noteServerTick(final int tick, final long nowNanos, final long periodNanos) {
        if (tick == clockLastTick) {
            return;
        }
        clockLastTick = tick;
        if (clockBaseTick == Integer.MIN_VALUE || periodNanos != clockPeriodNanos || tick < clockBaseTick) {
            // Start (or a new tick rate): the schedule starts here, keeping the lag measured so far
            clockBaseTick = tick;
            clockBaseNanos = nowNanos - lagNanos;
            clockPeriodNanos = periodNanos;
        } else {
            final long lag = nowNanos - (clockBaseNanos + (tick - clockBaseTick) * periodNanos);
            if (lag < 0L) {
                clockBaseNanos += lag; // ahead of the schedule (it was taken late): the schedule moves to now
                lagNanos = 0L;
            } else {
                lagNanos = lag;
            }
        }
        nextDueNanos = clockBaseNanos + (tick + 1 - clockBaseTick) * periodNanos;
    }

    /** {@code System.currentTimeMillis()} less how far the server is behind its tick schedule, now: any thread. */
    public static long clockMillis() {
        return System.currentTimeMillis() - lagMillisAt(System.nanoTime());
    }

    /** How far the server is behind its tick schedule at {@code nowNanos}, in milliseconds. */
    static long lagMillisAt(final long nowNanos) {
        return Math.max(lagNanos, nowNanos - nextDueNanos) / 1_000_000L;
    }

    static void activated(final AuthPlayer player) {
        pruneDisconnected();
        final AuthPlayer before = ACTIVE_BY_ID.put(player.player().getId(), player);
        if (before != player) {
            if (before != null) {
                ACTIVE.remove(before);
            }
            ACTIVE.add(player);
        }
    }

    static void deactivated(final AuthPlayer player) {
        if (ACTIVE_BY_ID.get(player.player().getId()) == player) {
            ACTIVE_BY_ID.remove(player.player().getId());
        }
        ACTIVE.remove(player);
    }

    private static int pruneTick = Integer.MIN_VALUE;

    // Players who quit under authority (they never ran deactivate): their ServerPlayer and auth state go with them
    static void pruneDisconnected() {
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            final AuthPlayer auth = ACTIVE.get(i);
            if (auth.player().hasDisconnected()) {
                ACTIVE.remove(i);
                if (ACTIVE_BY_ID.get(auth.player().getId()) == auth) {
                    ACTIVE_BY_ID.remove(auth.player().getId());
                }
            }
        }
    }

    /** Players currently under authority (observe or enforce). Main thread. */
    public static java.util.List<AuthPlayer> activePlayers() {
        return ACTIVE_VIEW;
    }

    /** The player under authority with this entity id, or null. Main thread. */
    public static @Nullable AuthPlayer active(final int entityId) {
        final AuthPlayer auth = ACTIVE_BY_ID.get(entityId);
        return auth == null || auth.player().hasDisconnected() ? null : auth;
    }
}
