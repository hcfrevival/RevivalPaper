package gg.hcfrevival.revival.auth;

import net.minecraft.network.protocol.Packet;

/**
 * Feeds each viewer's lag-compensation mirrors (design §7.1, report 03 §7): the combat package's {@code MirrorBook}
 * installs itself with {@link Authority#setMirrors}. Per-viewer state can live in {@link AuthPlayer#combatState}.
 * Main thread only, in the viewer's client order.
 */
public interface ViewerMirrors {

    ViewerMirrors NONE = new ViewerMirrors() {
        @Override
        public void onApplied(final AuthPlayer viewer, final Packet<?> packet, final int arrivalTick, final boolean exact, final long sentMillis) {
        }

        @Override
        public void onTickEnd(final AuthPlayer viewer, final int tick) {
        }

        @Override
        public void onReset(final AuthPlayer viewer) {
        }
    };

    /**
     * A packet about another player ({@code AddEntity}, {@code RemoveEntities}, {@code MoveEntity},
     * {@code EntityPositionSync}, {@code TeleportEntity}, their {@code SetEntityData}/{@code UpdateAttributes}/
     * {@code SetPassengers}) was applied by the viewer's client before its tick {@code arrivalTick}.
     *
     * @param exact false when it was sent unbundled (applied at or before {@code arrivalTick})
     * @param sentMillis server time the packet was sent at (the rewind cap measures from it)
     */
    void onApplied(AuthPlayer viewer, Packet<?> packet, int arrivalTick, boolean exact, long sentMillis);

    /**
     * The viewer's client starts tick {@code tick}: every packet it applied before the tick has been handed to
     * {@link #onApplied}. Called before the window's first attack is judged (the attack ray of tick {@code tick} sees the
     * boxes after tick {@code tick - 1}'s interpolation step), and in any case once per window.
     */
    void onTickEnd(AuthPlayer viewer, int tick);

    /** The viewer's client dropped every entity (login, respawn, resync). */
    void onReset(AuthPlayer viewer);

    /** The viewer's client applied a {@code TickingState}: its remote entities' interpolation speed. */
    default void onTickRate(final AuthPlayer viewer, final float rate, final boolean frozen) {
    }
}
