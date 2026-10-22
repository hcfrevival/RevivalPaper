package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.auth.AuthPlayer;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.auth.PathWiring;
import gg.hcfrevival.revival.auth.combat.TruthHistory;
import gg.hcfrevival.revival.auth.replicate.PathFeed;
import java.util.List;

/**
 * The live {@link TruthHistory}: a player under authority's replication path ({@link PathFeed}, the steps every viewer
 * is fed, stamped when they were simulated). A player outside authority has none, so a view of it past the rewind cap
 * stays REWIND. Main thread, like the judge that asks.
 */
public final class TruthPaths implements TruthHistory {

    public static final TruthPaths INSTANCE = new TruthPaths();

    private TruthPaths() {
    }

    @Override
    public long positionSince(final int targetId, final long sinceMillis, final double[] out) {
        // shortcut: a scan of the players under authority, only for attacks past the cap (a few a second at most)
        final List<AuthPlayer> active = Authority.activePlayers();
        for (int i = 0, size = active.size(); i < size; i++) {
            final AuthPlayer target = active.get(i);
            if (target.player().getId() == targetId) {
                final PathFeed feed = PathWiring.feed(target);
                return feed == null ? Long.MIN_VALUE : feed.positionSince(sinceMillis, out);
            }
        }
        return Long.MIN_VALUE;
    }
}
