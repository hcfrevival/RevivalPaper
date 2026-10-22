package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.auth.combat.MirrorBook;
import gg.hcfrevival.revival.auth.combat.RemoteMirror;

/**
 * The shooter's view for lag-compensated projectile hits (docs/revival/projectiles.md §2), as a pure function of the
 * shooter's {@link MirrorBook}.
 * <p>
 * A projectile a player shot in client tick {@code T} is spawned when the server replays that tick's window, and its
 * k-th flight tick runs about k server ticks later, when the shooter's newest replayed tick is about {@code T + k - 1}.
 * Had the shooter's client simulated the projectile itself, its k-th move would end in client tick {@code T + k - 1},
 * with every remote player drawn at its box after that tick's interpolation step: the mirror's live state. So each
 * flight tick is judged against the targets as the shooter's client draws them after its newest tick. No shot tick is
 * stored: when the shooter's windows run late the box is older (a longer rewind, bounded by the cap), when they come in
 * a burst it is newer.
 */
public final class ProjectileRewind {

    private ProjectileRewind() {
    }

    /**
     * Writes the box {@code targetId} has on the shooter's client after its newest tick to {@code out[0..5]} (minX, minY,
     * minZ, maxX, maxY, maxZ). True only when the book has run a tick, has the target present and pickable (not a
     * spectator), its replay is exact, and {@code nowMillis} minus the server time of the step on screen is at most
     * {@code capMillis} (inclusive, as {@code HitValidator}'s cap).
     */
    public static boolean viewBox(final MirrorBook book, final int targetId, final long nowMillis, final long capMillis, final double[] out) {
        if (book.lastTick() == Integer.MIN_VALUE) {
            return false;
        }
        final RemoteMirror mirror = book.mirror(targetId);
        if (mirror == null || !mirror.present() || !mirror.pickable() || !mirror.exact()) {
            return false;
        }
        if (nowMillis - mirror.displayStamp() > capMillis) {
            return false;
        }
        out[0] = mirror.minX();
        out[1] = mirror.minY();
        out[2] = mirror.minZ();
        out[3] = mirror.maxX();
        out[4] = mirror.maxY();
        out[5] = mirror.maxZ();
        return true;
    }
}
