package gg.hcfrevival.revival.auth.sim;

import net.minecraft.world.entity.player.Input;

/**
 * What one client tick's window says about the player's controls: the key state the client sent for the tick
 * ({@code ServerboundPlayerInputPacket}, sticky: the last one sent if the window has none; sent after the tick, so it
 * carries an auto-jump's jump) and the rotation the tick used (the move packet's raw floats, or the last ones). Pre-physics actions (attacks, item use) are separate
 * {@link MovementSim} calls made before {@link MovementSim#tick}. Mutable so one instance can be reused.
 */
public final class SimTickInput {

    public Input keys = Input.EMPTY;
    /** Raw, unwrapped client yaw: never {@code Mth.wrapDegrees} it, the sin table index depends on the exact float. */
    public float yRot;
    public float xRot;

    public SimTickInput set(final Input keys, final float yRot, final float xRot) {
        this.keys = keys;
        this.yRot = yRot;
        this.xRot = xRot;
        return this;
    }
}
