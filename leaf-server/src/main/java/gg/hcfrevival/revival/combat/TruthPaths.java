package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.auth.AuthPlayer;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.auth.PathWiring;
import gg.hcfrevival.revival.auth.combat.TruthHistory;
import gg.hcfrevival.revival.auth.replicate.PathFeed;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The live {@link TruthHistory}: a player under authority's replication path ({@link PathFeed}, the steps every viewer
 * is fed, stamped when they were simulated). A path that stopped (no {@code TickEnd}, refused or frozen windows) still
 * answers with its newest step while the target is still there (review X2-01, R2C-03: a lag switch or a freeze made the
 * target unhittable once its last step aged past the attacker's cap). A player outside authority has no path: it answers
 * only while it stands where its tracker last sent it. Main thread, like the judge that asks.
 */
public final class TruthPaths implements TruthHistory {

    public static final TruthPaths INSTANCE = new TruthPaths();

    /** {@code ServerEntity.TOLERANCE_LEVEL_POSITION} (private there): a move this small is never sent. */
    static final double SEND_THRESHOLD_SQ = 7.62939453125E-6;

    private TruthPaths() {
    }

    @Override
    public long positionSince(final int targetId, final long sinceMillis, final double[] out) {
        final AuthPlayer target = Authority.active(targetId);
        if (target == null) {
            return outsideAuthority(targetId, sinceMillis, out);
        }
        final PathFeed feed = PathWiring.feed(target);
        if (feed == null) {
            return Long.MIN_VALUE;
        }
        final long found = feed.positionSince(sinceMillis, out);
        if (found != Long.MIN_VALUE) {
            return found;
        }
        // No step that new: no window ran since the newest. Its position is still the truth unless the target moved
        // without one (a server teleport pending or forced, a resync): then nothing is known that new
        if (feed.newest(out) != Long.MIN_VALUE && target.stillAt(out[0], out[1], out[2])) {
            return sinceMillis;
        }
        return Long.MIN_VALUE;
    }

    // Review 3 #3 (review 1's stale-stamp-stationary-targets): a player-type NPC, or a ViaVersion or Geyser player, standing
    // still is sent nothing but a resync every 60 ticks, so every view of it aged past the cap and enforce dropped the hit.
    // A passenger's tracker base follows it without a send, so a rider stays REWIND
    private static long outsideAuthority(final int targetId, final long sinceMillis, final double[] out) {
        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            if (level.getEntity(targetId) instanceof final ServerPlayer player) {
                final net.minecraft.server.level.ChunkMap.TrackedEntity tracked = player.moonrise$getTrackedEntity();
                if (tracked == null || player.isPassenger()) {
                    return Long.MIN_VALUE;
                }
                return standing(tracked.serverEntity.getPositionBase(), player.getX(), player.getY(), player.getZ(), sinceMillis, out);
            }
        }
        return Long.MIN_VALUE;
    }

    /**
     * The truth of a player outside authority at {@code (x, y, z)} whose tracker last sent {@code base}: within the
     * tracker's send threshold of it, the player hasn't moved since that send, so where it stands is its truth at any age
     * ({@link AuthPlayer#stillAt} for a path); further, a send is due and nothing is known that new.
     */
    static long standing(final Vec3 base, final double x, final double y, final double z, final long sinceMillis, final double[] out) {
        final double dx = x - base.x;
        final double dy = y - base.y;
        final double dz = z - base.z;
        if (!(dx * dx + dy * dy + dz * dz < SEND_THRESHOLD_SQ)) {
            return Long.MIN_VALUE;
        }
        out[0] = x;
        out[1] = y;
        out[2] = z;
        return sinceMillis;
    }
}
