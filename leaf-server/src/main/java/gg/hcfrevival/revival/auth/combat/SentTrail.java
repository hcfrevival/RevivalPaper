package gg.hcfrevival.revival.auth.combat;

/**
 * Where the tracker last sent a living non-player entity, for the non-player block clip (design §7.1, 2026-10-24): the
 * positions every client that tracks it interpolates between. A 26.3 client draws a mob along a stepped path through the
 * positions it was sent ({@code SteppedInterpolationHandler}, one step per send, {@code updateInterval} ticks long), so
 * what it shows at any moment lies on the segment between two consecutive sends. Mobs are not mirrored per viewer
 * (auth-combat.md §7), so this is shared by every viewer and carries no ack timing: the caller sizes the span it reads.
 * <p>
 * A ring of the last {@value #SIZE} sends, each stamped with the server tick it went out in; allocation-free after
 * construction. Main thread (the tracker's {@code sendChanges} and the attack judge).
 */
public final class SentTrail {

    /** Sends kept: a mob sent every 3 ticks needs 5 for the 600 ms rewind bound, an entity sent every tick 13. */
    static final int SIZE = 16;
    /**
     * A tracked entity is sent its position every 60 ticks even when it stands still ({@code ServerEntity.sendChanges}).
     * A longer gap means no client tracked it in between (or the tracker skipped it): whoever tracks it now got it from a
     * pairing at its current position, so the sends before the gap are no client's view.
     */
    static final int GAP_TICKS = 64;

    private final double[] positions = new double[SIZE * 3];
    private final int[] ticks = new int[SIZE];
    private int newest = -1;
    private int count;

    /** A position the tracker sent at server tick {@code tick} (the entity's position when the packet was built). */
    public void record(final double x, final double y, final double z, final int tick) {
        if (this.count > 0 && tick - this.ticks[this.newest] > GAP_TICKS) {
            this.count = 0; // a gap: the views from before it are gone
        }
        final int slot = (this.newest + 1) & (SIZE - 1);
        this.positions[slot * 3] = x;
        this.positions[slot * 3 + 1] = y;
        this.positions[slot * 3 + 2] = z;
        this.ticks[slot] = tick;
        this.newest = slot;
        this.count = Math.min(this.count + 1, SIZE);
    }

    /**
     * Grows {@code region} by the box {@code (minX..maxZ)} moved to every position a client may be drawing at server tick
     * {@code nowTick} if its view lags at most {@code spanTicks}: each send at or after {@code nowTick - spanTicks}, and the
     * newest one before that (a client may still be on its way from there to the next). With no send in the span the trail
     * adds nothing: the entity hasn't moved since its newest send, so every client shows it where it stands. The box is
     * the entity's own at {@code (x, y, z)} (its size now).
     *
     * @return how many sends were added
     */
    public int sweep(final int nowTick, final int spanTicks, final double x, final double y, final double z, final double minX, final double minY,
                     final double minZ, final double maxX, final double maxY, final double maxZ, final HitValidator.Region region) {
        if (this.count == 0 || nowTick - this.ticks[this.newest] > spanTicks) {
            return 0;
        }
        final int since = nowTick - spanTicks;
        int added = 0;
        for (int i = 0; i < this.count; i++) {
            final int slot = (this.newest - i) & (SIZE - 1);
            final double dx = this.positions[slot * 3] - x;
            final double dy = this.positions[slot * 3 + 1] - y;
            final double dz = this.positions[slot * 3 + 2] - z;
            region.include(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
            added++;
            if (this.ticks[slot] < since) {
                break; // the newest send before the span: where the client's oldest possible segment starts
            }
        }
        return added;
    }

    /** Sends held (at most {@value #SIZE}). */
    public int size() {
        return this.count;
    }
}
