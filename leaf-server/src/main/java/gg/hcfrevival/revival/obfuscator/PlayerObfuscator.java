package gg.hcfrevival.revival.obfuscator;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.PlayerObfuscatorSettings;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Decides, per (viewer, target) pair of players, whether the target is sent to the viewer at all. A sneaking or
 * invisible target with no line of sight to a viewer for {@code hide-delay-ticks} is withheld from that viewer's
 * entity tracker until a line clears; the tab list is untouched. Every other player keeps vanilla tracking.
 * <p>
 * {@link #tick} runs in the target's own tick on its world thread and publishes the result; {@link #canSee} is the
 * tracker's read of it, safe on Leaf's async tracker threads.
 */
public final class PlayerObfuscator {

    public static final String BYPASS_PERMISSION = "revival.obfuscator.bypass";

    /** Clear pairs re-test this often at most: their ray runs the whole distance, and hiding late is never a pop-in. */
    private static final int CLEAR_RETEST_TICKS = 4;
    /** Any faster is a teleport, not a prediction. */
    private static final double MAX_SPEED = 1.0;
    private static final int BYPASS_REFRESH_TICKS = 20;

    private PlayerObfuscator() {
    }

    /** Whether the tracker may pair the viewer with this entity. Any thread: one volatile read. */
    public static boolean canSee(final ServerPlayer viewer, final Entity tracked) {
        return !(tracked instanceof ServerPlayer target) || !target.revivalObfuscator.hides(viewer.getId());
    }

    /** Once per tick, on the player's world thread, from {@code ServerPlayer.tick()}. */
    public static void tick(final ServerPlayer player) {
        final ObfuscatorState state = player.revivalObfuscator;
        final int tick = MinecraftServer.currentTick;
        final PlayerObfuscatorSettings settings = Revival.settings().playerObfuscator();
        // Movement this tick from our own snapshot: xo/yo/zo already equal the current position inside tick()
        double vx = player.getX() - state.lastX;
        double vy = player.getY() - state.lastY;
        double vz = player.getZ() - state.lastZ;
        if (Math.abs(vx) > MAX_SPEED || Math.abs(vy) > MAX_SPEED || Math.abs(vz) > MAX_SPEED) {
            vx = vy = vz = 0.0;
        }
        state.lastX = player.getX();
        state.lastY = player.getY();
        state.lastZ = player.getZ();
        state.velocityX = vx;
        state.velocityY = vy;
        state.velocityZ = vz;
        if (settings.enabled() && tick - state.bypassTick >= BYPASS_REFRESH_TICKS) {
            state.bypass = player.getBukkitEntity().hasPermission(BYPASS_PERMISSION);
            state.bypassTick = tick;
        }
        final ServerLevel level = player.level();
        final boolean sameLevel = level == state.lastLevel;
        state.lastLevel = level;
        if (!settings.enabled() || !sameLevel || !candidate(player)) {
            state.clear();
            return;
        }
        final List<ServerPlayer> players = level.players();
        final double range = level.spigotConfig.playerTrackingRange;
        final double rangeSq = range * range;
        state.occluder.reset(level);
        for (int i = 0, n = players.size(); i < n; i++) {
            final ServerPlayer viewer = players.get(i);
            if (viewer == player) {
                continue;
            }
            final int viewerId = viewer.getId();
            final double dx = viewer.getX() - player.getX();
            final double dy = viewer.getY() - player.getY();
            final double dz = viewer.getZ() - player.getZ();
            final double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > rangeSq || viewer.isSpectator() || viewer.revivalObfuscator.bypass) {
                state.forget(viewerId);
                continue;
            }
            int interval = distanceSq <= 16.0 * 16.0 ? 1 : distanceSq <= 32.0 * 32.0 ? 2 : distanceSq <= 64.0 * 64.0 ? 4 : 8;
            if (!state.occluded(viewerId)) {
                interval = Math.max(interval, CLEAR_RETEST_TICKS);
            }
            if ((tick + viewerId) % interval != 0) {
                continue;
            }
            state.record(viewerId, !visible(state, player, viewer, settings), tick);
        }
        state.publish(tick, settings.hideDelayTicks());
    }

    /** Sneaking or invisible, and not riding: a withheld rider would leave a visible riderless mount. */
    static boolean candidate(final ServerPlayer player) {
        return (player.isCrouching() || player.isInvisible()) && !player.isPassenger() && !player.isVehicle();
    }

    private static boolean visible(final ObfuscatorState state, final ServerPlayer target, final ServerPlayer viewer, final PlayerObfuscatorSettings settings) {
        final int lead = settings.leadTicks();
        final var box = target.getBoundingBox();
        double minX = box.minX;
        double minY = box.minY;
        double minZ = box.minZ;
        double maxX = box.maxX;
        double maxY = box.maxY;
        double maxZ = box.maxZ;
        // Stretch the box toward where the target will be in `lead` ticks
        final double lx = state.velocityX * lead;
        final double ly = state.velocityY * lead;
        final double lz = state.velocityZ * lead;
        if (lx < 0.0) minX += lx; else maxX += lx;
        if (ly < 0.0) minY += ly; else maxY += ly;
        if (lz < 0.0) minZ += lz; else maxZ += lz;

        final double[] ends = state.ends;
        final double eyeX = viewer.getX();
        final double eyeY = viewer.getEyeY();
        final double eyeZ = viewer.getZ();
        ends[0] = eyeX;
        ends[1] = eyeY;
        ends[2] = eyeZ;
        int count = 1;
        final ObfuscatorState viewerState = viewer.revivalObfuscator;
        if (viewerState.velocityX != 0.0 || viewerState.velocityY != 0.0 || viewerState.velocityZ != 0.0) {
            ends[3] = eyeX + viewerState.velocityX * lead;
            ends[4] = eyeY + viewerState.velocityY * lead;
            ends[5] = eyeZ + viewerState.velocityZ * lead;
            count = 2;
        }
        final int eyeCount = count;
        if (settings.cameraDistance() > 0.0) {
            count = LineOfSight.addCameraEnds(state.occluder, eyeX, eyeY, eyeZ, viewer.getYRot(), viewer.getXRot(), settings.cameraDistance(), ends, count);
        }
        return LineOfSight.visible(state.occluder, minX, minY, minZ, maxX, maxY, maxZ, ends, count, eyeCount);
    }
}
