package gg.hcfrevival.revival.obfuscator;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.Arrays;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * One player's obfuscator state. As a target, which viewers it is withheld from: the mutable side is owned by the
 * player's tick thread, and the published sorted {@link #hidden} array is the only thing the entity tracker reads, on
 * any thread. As a viewer, its movement this tick and its bypass flag, read by other targets on the same tick thread.
 */
public final class ObfuscatorState {

    private static final int[] NONE = new int[0];

    private volatile int[] hidden = NONE;
    private final Int2IntOpenHashMap occludedSince = new Int2IntOpenHashMap();
    private int[] scratch = NONE;

    final double[] ends = new double[12];
    final LineOfSight.LevelOccluder occluder = new LineOfSight.LevelOccluder();
    @Nullable Level lastLevel;
    double lastX;
    double lastY;
    double lastZ;
    double velocityX;
    double velocityY;
    double velocityZ;
    boolean bypass;
    int bypassTick = Integer.MIN_VALUE / 2; // far enough back to refresh at once, without tick - bypassTick overflowing

    /** Whether this player is currently withheld from the viewer. Any thread. */
    public boolean hides(final int viewerId) {
        final int[] hidden = this.hidden;
        return hidden.length != 0 && Arrays.binarySearch(hidden, viewerId) >= 0;
    }

    int[] hidden() {
        return this.hidden;
    }

    /** Whether the viewer had no line of sight at its last test. */
    boolean occluded(final int viewerId) {
        return this.occludedSince.containsKey(viewerId);
    }

    /** Records a test: an occluded viewer keeps the tick it was first occluded at, a clear one is forgotten. */
    void record(final int viewerId, final boolean occluded, final int tick) {
        if (occluded) {
            this.occludedSince.putIfAbsent(viewerId, tick);
        } else {
            this.occludedSince.remove(viewerId);
        }
    }

    void forget(final int viewerId) {
        this.occludedSince.remove(viewerId);
    }

    /** Publishes the viewers occluded for at least {@code hideDelay} ticks; the array is replaced only when it changes. */
    void publish(final int tick, final int hideDelay) {
        final int[] current = this.hidden;
        if (this.occludedSince.isEmpty() && current.length == 0) {
            return;
        }
        if (this.scratch.length < this.occludedSince.size()) {
            this.scratch = new int[Math.max(16, this.occludedSince.size() * 2)];
        }
        int n = 0;
        for (final ObjectIterator<Int2IntMap.Entry> it = Int2IntMaps.fastIterator(this.occludedSince); it.hasNext(); ) {
            final Int2IntMap.Entry entry = it.next();
            if (tick - entry.getIntValue() >= hideDelay) {
                this.scratch[n++] = entry.getIntKey();
            }
        }
        Arrays.sort(this.scratch, 0, n);
        if (n != current.length || !Arrays.equals(current, 0, n, this.scratch, 0, n)) {
            this.hidden = n == 0 ? NONE : Arrays.copyOf(this.scratch, n);
        }
    }

    /** Withholds from nobody; a no-op when already so. */
    void clear() {
        if (!this.occludedSince.isEmpty()) {
            this.occludedSince.clear();
        }
        if (this.hidden.length != 0) {
            this.hidden = NONE;
        }
    }
}
