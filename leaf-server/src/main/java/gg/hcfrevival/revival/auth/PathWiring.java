package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.auth.combat.MirrorBook;
import gg.hcfrevival.revival.auth.combat.RemoteMirror;
import gg.hcfrevival.revival.auth.replicate.PathFeed;
import gg.hcfrevival.revival.config.AuthoritativeSettings;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Replication of players under authority (design §8, report 03 §6, patch 1014): every simulated client tick of a player
 * is one step of its {@link PathFeed}; each viewer within {@code replication.exact-radius} gets the steps as K = 2
 * stepped moves, drained every server tick into that viewer's per-tick bundle (which carries our ping, R5), and the
 * vanilla tracker's movement packets for that player are withheld from it (R7). Viewers farther away keep the vanilla
 * cadence (still logged for their mirrors).
 * <p>
 * Handovers keep each client's {@code VecDeltaCodec} base right: a viewer coming into range starts from the vanilla base
 * it holds; a viewer leaving range gets an {@code EntityPositionSync} at the vanilla base before vanilla deltas resume.
 * A jump the client would snap (R6) re-pairs the player on that viewer. Main thread.
 */
public final class PathWiring implements PathSink {

    public static final PathWiring INSTANCE = new PathWiring();

    /** Leaving range takes this much more distance than entering it, so a viewer on the edge doesn't flap. */
    static final double HYSTERESIS = 4.0;

    /** One replicated player: its feed and the viewers it feeds. */
    static final class Target {
        final PathFeed feed;
        final Reference2ObjectOpenHashMap<ServerPlayer, Fed> viewers = new Reference2ObjectOpenHashMap<>();
        /** Viewers that still take the vanilla tracker's movement packets. */
        final Predicate<ServerPlayer> vanilla = viewer -> !this.viewers.containsKey(viewer);
        /** The server tick each viewer was last re-paired for an inexact mirror. */
        final it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<ServerPlayer> repaired = new it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<>();

        boolean mayRepair(final ServerPlayer viewer, final int now, final AuthoritativeSettings settings) {
            if (this.repaired.containsKey(viewer) && now - this.repaired.getInt(viewer) < settings.repairIntervalTicks()) {
                return false;
            }
            this.repaired.put(viewer, now);
            return true;
        }

        Target(final int entityId, final AuthoritativeSettings settings) {
            this.feed = new PathFeed(entityId, settings.pathBuffer(),
                new PathFeed.Settings(settings.pathStepTicks(), settings.keepAlive(), settings.preRoll(), settings.respawnDistance()));
        }
    }

    /** One (target, viewer) pair on the feed. */
    static final class Fed implements PathFeed.Sink {
        final PathFeed.Viewer viewer;
        final ServerPlayer player;

        Fed(final PathFeed.Viewer viewer, final ServerPlayer player) {
            this.viewer = viewer;
            this.player = player;
        }

        @Override
        public void send(final Packet<ClientGamePacketListener> packet, final long stamp) {
            this.player.revivalAuth.sendStamped(packet, stamp);
        }
    }

    private PathWiring() {
    }

    static @Nullable Target target(final AuthPlayer auth) {
        return auth.replicationState instanceof final Target target ? target : null;
    }

    private static Target targetOrNew(final AuthPlayer auth, final AuthoritativeSettings settings) {
        if (auth.replicationState instanceof final Target target) {
            return target;
        }
        final Target target = new Target(auth.player().getId(), settings);
        auth.replicationState = target;
        return target;
    }

    // PathSink (R1): every simulated client tick of the target is one step

    @Override
    public void onTick(final AuthPlayer target, final int tick, final double x, final double y, final double z, final float yRot, final float xRot,
                       final boolean onGround) {
        targetOrNew(target, Revival.settings().authoritative()).feed.append(x, y, z, yRot, xRot, onGround, System.currentTimeMillis());
    }

    // Tracker hooks --------------------------------------------------------------------------------------------------

    /** Whether the target's vanilla movement packets must skip some viewers. */
    static void sendMovement(final Entity entity, final ServerEntity.Synchronizer synchronizer, final Packet<? super ClientGamePacketListener> packet) {
        if (entity instanceof final ServerPlayer player) {
            final Target target = target(player.revivalAuth);
            if (target != null && !target.viewers.isEmpty()) {
                synchronizer.sendToTrackingPlayersFiltered(packet, target.vanilla);
                return;
            }
        }
        synchronizer.sendToTrackingPlayers(packet);
    }

    /** {@code ServerEntity.addPairing}, after the pairing bundle went out: a viewer in range starts on the feed. */
    static void onPaired(final Entity entity, final ServerPlayer viewer, final List<? extends Packet<?>> pairing) {
        if (!(entity instanceof final ServerPlayer player) || !replicated(player.revivalAuth)) {
            return;
        }
        final AuthoritativeSettings settings = Revival.settings().authoritative();
        final Target target = targetOrNew(player.revivalAuth, settings);
        final Fed old = target.viewers.remove(viewer);
        if (old != null) {
            old.viewer.despawned();
        }
        if (distanceSq(player, viewer) > settings.exactRadius() * settings.exactRadius()) {
            return;
        }
        for (final Packet<?> packet : pairing) {
            if (packet instanceof final ClientboundAddEntityPacket add && add.getId() == player.getId()) {
                final Fed fed = new Fed(target.feed.viewer(), viewer);
                fed.viewer.spawned(add.getX(), add.getY(), add.getZ(), add.getYRot(), add.getXRot(), player.onGround(), System.currentTimeMillis());
                target.viewers.put(viewer, fed);
                return;
            }
        }
    }

    /** {@code ServerEntity.removePairing}. */
    static void onUnpaired(final Entity entity, final ServerPlayer viewer) {
        if (entity instanceof final ServerPlayer player) {
            final Target target = target(player.revivalAuth);
            if (target != null) {
                final Fed fed = target.viewers.remove(viewer);
                if (fed != null) {
                    fed.viewer.despawned();
                }
                if (viewer.isRemoved()) {
                    target.repaired.removeInt(viewer);
                }
            }
        }
    }

    /**
     * {@code ServerEntity.sendChanges}, every server tick: viewers move on or off the feed by distance, and each viewer on
     * it gets the steps it hasn't had (R5: into its per-tick bundle).
     */
    static void drain(final ServerEntity serverEntity, final Set<ServerPlayerConnection> tracked) {
        if (!(serverEntity.entity instanceof final ServerPlayer player)) {
            return;
        }
        final AuthPlayer auth = player.revivalAuth;
        final Target existing = target(auth);
        if (!replicated(auth)) {
            if (existing != null && !existing.viewers.isEmpty()) {
                // Authority went off for the target: every viewer goes back to the vanilla tracker
                for (final Fed fed : existing.viewers.values()) {
                    toVanilla(serverEntity, fed);
                }
                existing.viewers.clear();
            }
            return;
        }
        if (tracked.isEmpty()) {
            return;
        }
        final AuthoritativeSettings settings = Revival.settings().authoritative();
        final Target target = existing != null ? existing : targetOrNew(auth, settings);
        final double enter = settings.exactRadius() * settings.exactRadius();
        final double leave = (settings.exactRadius() + HYSTERESIS) * (settings.exactRadius() + HYSTERESIS);
        for (final ServerPlayerConnection connection : tracked) {
            final ServerPlayer viewer = connection.getPlayer();
            Fed fed = target.viewers.get(viewer);
            final double distance = distanceSq(player, viewer);
            if (fed == null) {
                if (distance > enter) {
                    continue;
                }
                // Into range: the client's codec base is the vanilla tracker's
                fed = new Fed(target.feed.viewer(), viewer);
                final Vec3 base = serverEntity.getPositionBase();
                fed.viewer.spawned(base.x, base.y, base.z, serverEntity.getLastSentYRot(), serverEntity.getLastSentXRot(), player.onGround(),
                    System.currentTimeMillis());
                target.viewers.put(viewer, fed);
            } else if (distance > leave) {
                target.viewers.remove(viewer);
                toVanilla(serverEntity, fed);
                continue;
            }
            PathFeed.Result result = drainOne(fed, player.getId());
            if (result == PathFeed.Result.RESPAWN || inexact(fed, player.getId()) && target.mayRepair(viewer, MinecraftServer.currentTick, settings)) {
                // R6: a jump the client would snap; or the viewer's mirror of the target lost exactness (a lag spike past the
                // ack deadline, a late pong), which only a spawn restores and without which no hit on it can be judged.
                // Re-pair (onPaired puts a fresh viewer on the feed), then send again
                serverEntity.removePairing(viewer);
                serverEntity.addPairing(viewer);
                final Fed fresh = target.viewers.get(viewer);
                if (fresh != null) {
                    drainOne(fresh, player.getId());
                }
            }
        }
    }

    // The viewer's mirror of the target is present but no longer the client's exactly, for a reason a spawn fixes
    private static boolean inexact(final Fed fed, final int targetId) {
        if (!(fed.player.revivalAuth.combatState instanceof final MirrorBook book)) {
            return false;
        }
        final RemoteMirror mirror = book.mirror(targetId);
        return mirror != null && mirror.present() && !mirror.exact() && !mirror.passenger() && mirror.pose() != net.minecraft.world.entity.Pose.SLEEPING;
    }

    private static PathFeed.Result drainOne(final Fed fed, final int targetId) {
        double x = Double.NaN;
        double y = Double.NaN;
        double z = Double.NaN;
        if (fed.player.revivalAuth.combatState instanceof final MirrorBook book) {
            final RemoteMirror mirror = book.mirror(targetId);
            if (mirror != null) {
                x = mirror.x();
                y = mirror.y();
                z = mirror.z();
            }
        }
        return fed.viewer.drain(fed, x, y, z);
    }

    // Back to vanilla deltas: an absolute sync at the vanilla codec base, so the next vanilla delta decodes right
    private static void toVanilla(final ServerEntity serverEntity, final Fed fed) {
        fed.viewer.despawned();
        final Vec3 base = serverEntity.getPositionBase();
        final PositionPath path = PositionPath.stepped(List.of(new PositionStep(base, Revival.settings().authoritative().pathStepTicks())));
        fed.player.connection.send(new ClientboundEntityPositionSyncPacket(serverEntity.entity.getId(), path, serverEntity.getLastSentYRot(),
            serverEntity.getLastSentXRot(), serverEntity.entity.onGround()));
    }

    /** Whether the player's movement comes from the simulation, so its path is authoritative. */
    static boolean replicated(final AuthPlayer auth) {
        return auth.mode() != AuthMode.OFF && auth.netActive();
    }

    private static double distanceSq(final ServerPlayer a, final ServerPlayer b) {
        final double dx = a.getX() - b.getX();
        final double dy = a.getY() - b.getY();
        final double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Viewers currently on the target's feed (inspection, tests). */
    public static int fedViewers(final AuthPlayer target) {
        final Target state = target(target);
        return state == null ? 0 : state.viewers.size();
    }

    /** Whether {@code viewer} gets {@code target}'s path from the feed (tests). */
    public static boolean feeds(final AuthPlayer target, final ServerPlayer viewer) {
        final Target state = target(target);
        return state != null && state.viewers.containsKey(viewer);
    }

    /** The target's feed, or null before its first step. */
    public static @Nullable PathFeed feed(final AuthPlayer target) {
        final Target state = target(target);
        return state == null ? null : state.feed;
    }
}
