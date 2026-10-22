package gg.hcfrevival.revival.auth;

/**
 * Receives each simulated client tick of a player, for replication to viewers (design §8, report 03 §6 R1: one step
 * per authoritative tick). The replicate package ({@code auth.replicate.PathFeed}) installs itself with
 * {@link Authority#setPathSink}. Main thread.
 */
@FunctionalInterface
public interface PathSink {

    PathSink NONE = (target, tick, x, y, z, yRot, xRot, onGround) -> {
    };

    void onTick(AuthPlayer target, int tick, double x, double y, double z, float yRot, float xRot, boolean onGround);
}
