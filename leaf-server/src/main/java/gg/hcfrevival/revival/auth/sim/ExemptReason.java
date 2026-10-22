package gg.hcfrevival.revival.auth.sim;

/**
 * Why {@link MovementSim#tick} did not simulate a tick: states the port does not cover, where the caller keeps vanilla
 * handling of the client's claim and re-seeds the simulation when the state ends (authoritative.md §5).
 */
public enum ExemptReason {
    /** Simulated. */
    NONE,
    /** Riding: LocalPlayer.rideTick and the vehicle's physics are out of scope for v1. */
    PASSENGER,
    /** Sleeping: immobile, position set by the bed. */
    SLEEPING,
    /** Dead or dying. */
    DEAD,
    /** The camera is another entity (spectator view): applyInput decays instead of reading keys. */
    SPECTATOR_CAMERA,
    /** Riptide spin ({@code autoSpinAttackTicks}): entity pushes and the spin attack are not ported yet. */
    RIPTIDE,
    /**
     * The caller flagged the tick ({@link SimPlayer#externalMover}): something outside the player's own tick may move it
     * in the client's level, which the port does not model: a moving piston or an opening shulker box next to it, a
     * shulker's lid, a potent sulfur geyser, a mob or player pushing it (report 01 §6.6-6.7).
     */
    EXTERNAL_MOVER
}
