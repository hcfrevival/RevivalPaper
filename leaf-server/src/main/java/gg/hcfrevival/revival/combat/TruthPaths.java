package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.auth.AuthPlayer;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.auth.PathWiring;
import gg.hcfrevival.revival.auth.combat.TruthHistory;
import gg.hcfrevival.revival.auth.replicate.PathFeed;

/**
 * The live {@link TruthHistory}: a player under authority's replication path ({@link PathFeed}, the steps every viewer
 * is fed, stamped when they were simulated). A player outside authority has none, so a view of it past the rewind cap
 * stays REWIND. A path that stopped (no {@code TickEnd}, refused or frozen windows) still answers with its newest step
 * while the target is still there (review X2-01, R2C-03: a lag switch or a freeze made the target unhittable once its
 * last step aged past the attacker's cap). Main thread, like the judge that asks.
 */
public final class TruthPaths implements TruthHistory {

    public static final TruthPaths INSTANCE = new TruthPaths();

    private TruthPaths() {
    }

    @Override
    public long positionSince(final int targetId, final long sinceMillis, final double[] out) {
        final AuthPlayer target = Authority.active(targetId);
        if (target == null) {
            return Long.MIN_VALUE;
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
}
