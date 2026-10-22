package gg.hcfrevival.revival.combat;

import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * What a player's client shows, for projectile rules that need authority: the boxes it draws other players at (lag
 * compensated projectile hits, docs/revival/projectiles.md §2) and the blocks it has (pearls against fake walls, §6).
 * The netcode implements it ({@code auth.CombatWiring}) and installs it once at startup; until then, and for every
 * player not under authority, every query answers "unknown" and the projectile rules fall back to the server's world.
 * <p>
 * Called on the projectile's tick thread, which owns the shooter's authority state (authority refuses Leaf's parallel
 * world ticking and async tracker).
 */
public interface ProjectileViews {

    /** Answers "unknown" to everything: no authority. */
    ProjectileViews NONE = new ProjectileViews() {
        @Override
        public boolean compensates(final ServerPlayer shooter) {
            return false;
        }

        @Override
        public boolean viewBox(final ServerPlayer shooter, final int targetId, final double[] out) {
            return false;
        }

        @Override
        public @Nullable ProjectileBlocks clientBlocks(final ServerPlayer player) {
            return null;
        }
    };

    /**
     * Whether {@code shooter}'s projectile hits on players can be judged on its client's boxes at all: under authority in
     * {@code enforce}, simulation seeded. Cheap; when false, {@link #viewBox} is never asked for that shooter's
     * projectiles this tick.
     */
    boolean compensates(ServerPlayer shooter);

    /**
     * The box {@code targetId} has on {@code shooter}'s client now: its mirror after the shooter's newest client tick
     * ({@link ProjectileRewind#viewBox}). Writes minX, minY, minZ, maxX, maxY, maxZ to {@code out[0..5]} and returns true
     * only when the shooter is under authority in {@code enforce} and the box is exact, pickable and no older than the
     * shooter's rewind cap (the melee cap, design §15.7). False means "use the target's server box".
     */
    boolean viewBox(ServerPlayer shooter, int targetId, double[] out);

    /** The blocks {@code player}'s client has (its {@code WorldView}), or null when the player is not under authority. */
    @Nullable ProjectileBlocks clientBlocks(ServerPlayer player);

    /** The installed views, {@link #NONE} until the netcode installs its own. */
    static ProjectileViews current() {
        return Installed.current;
    }

    /** Installs the netcode's views (null puts back {@link #NONE}). */
    static void install(final @Nullable ProjectileViews views) {
        Installed.current = views == null ? NONE : views;
    }

    /** Holder for the installed views. */
    final class Installed {
        private static volatile ProjectileViews current = NONE;

        private Installed() {
        }
    }
}
