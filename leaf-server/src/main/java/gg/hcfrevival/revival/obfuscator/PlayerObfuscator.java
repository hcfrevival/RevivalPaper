package gg.hcfrevival.revival.obfuscator;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.PlayerObfuscatorSettings;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

/**
 * Decides, per (viewer, target) pair of players, whether the target is sent to the viewer at all. A sneaking or
 * invisible target with no line of sight to a viewer for {@code hide-delay-ticks} is withheld from that viewer's
 * entity tracker until a line clears; the tab list is untouched. Every other player keeps vanilla tracking, and
 * viewers in creative or spectator mode, or with {@link #BYPASS_PERMISSION}, always see everyone.
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
        final ServerLevel level = player.level();
        // Movement this tick from our own snapshot: xo/yo/zo already equal the current position inside tick()
        double vx = player.getX() - state.lastX;
        double vy = player.getY() - state.lastY;
        double vz = player.getZ() - state.lastZ;
        if (Math.abs(vx) > MAX_SPEED || Math.abs(vy) > MAX_SPEED || Math.abs(vz) > MAX_SPEED) {
            vx = vy = vz = 0.0;
            state.retestAll = true; // a pearl or respawn: last tick's answers are for another place
        }
        state.lastX = player.getX();
        state.lastY = player.getY();
        state.lastZ = player.getZ();
        state.velocityX = vx;
        state.velocityY = vy;
        state.velocityZ = vz;
        final boolean sameLevel = level == state.lastLevel;
        state.lastLevel = level;
        if (!settings.enabled()) {
            state.clear();
            return;
        }
        if (tick - state.bypassTick >= BYPASS_REFRESH_TICKS) {
            state.bypass = player.getBukkitEntity().hasPermission(BYPASS_PERMISSION);
            state.bypassTick = tick;
        }
        // As a viewer: where this player's third-person camera could sit, clipped once here instead of by every target
        state.cameraCount = settings.cameraDistance() > 0.0
            ? LineOfSight.addCameraEnds(state.occluder.reset(level), player.getX(), player.getEyeY(), player.getZ(), player.getYRot(), player.getXRot(), settings.cameraDistance(), state.camera, 0)
            : 0;
        if (!sameLevel || !candidate(player)) {
            state.clear();
            return;
        }
        final List<ServerPlayer> players = level.players();
        final int n = players.size();
        // The tracker itself never pairs beyond this
        final double range = Math.min(level.spigotConfig.playerTrackingRange, level.getChunkSource().chunkMap.serverViewDistance * 16);
        final double rangeSq = range * range;
        final int budget = settings.blockChecksPerTick();
        final LineOfSight.LevelOccluder occluder = state.occluder.reset(level);
        final int start = n == 0 ? 0 : tick % n; // rotate, so a budget cut never starves the same pairs
        for (int k = 0; k < n; k++) {
            final ServerPlayer viewer = players.get((start + k) % n);
            if (viewer == player) {
                continue;
            }
            final int viewerId = viewer.getId();
            final double dx = viewer.getX() - player.getX();
            final double dy = viewer.getY() - player.getY();
            final double dz = viewer.getZ() - player.getZ();
            final double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > rangeSq || seesEveryone(viewer.gameMode(), viewer.revivalObfuscator.bypass)) {
                state.record(viewerId, false, tick);
                continue;
            }
            if (!state.retestAll && (tick + viewerId) % interval(distanceSq, state.occluded(viewerId)) != 0) {
                continue;
            }
            if (budget > 0 && occluder.lookups >= budget) {
                continue; // out of budget: last tick's answer stands until next tick
            }
            state.record(viewerId, !visible(state, occluder, player, viewer, settings), tick);
        }
        state.retestAll = false;
        state.publish(tick, settings.hideDelayTicks(), level);
    }

    /**
     * Sneaking or invisible, in a mode other players can see, not glowing (vanilla outlines glowing players through
     * walls) and not riding (a withheld rider would leave a visible riderless mount).
     */
    static boolean candidate(final Player player) {
        return (player.isCrouching() || player.isInvisible())
            && !player.isSpectator() && !player.isCurrentlyGlowing() && !player.isPassenger() && !player.isVehicle();
    }

    /** Staff and anyone not playing survival or adventure see everyone. */
    static boolean seesEveryone(final GameType mode, final boolean bypass) {
        return bypass || !mode.isSurvival();
    }

    /**
     * Ticks between tests of a pair: more often when close, and at most every {@link #CLEAR_RETEST_TICKS} while clear.
     * Every near value stays within the default {@code lead-ticks}, so the stretched box covers the movement between
     * tests and a reveal is never late.
     */
    static int interval(final double distanceSq, final boolean occluded) {
        final int byDistance = distanceSq <= 16.0 * 16.0 ? 2 : distanceSq <= 32.0 * 32.0 ? 3 : distanceSq <= 64.0 * 64.0 ? 4 : 8;
        return occluded ? byDistance : Math.max(byDistance, CLEAR_RETEST_TICKS);
    }

    private static boolean visible(final ObfuscatorState state, final LineOfSight.LevelOccluder occluder, final ServerPlayer target, final ServerPlayer viewer, final PlayerObfuscatorSettings settings) {
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
        for (int i = 0, m = viewerState.cameraCount * 3; i < m; i++) {
            ends[count * 3 + i] = viewerState.camera[i];
        }
        count += viewerState.cameraCount;
        // Corner rays go to the eye only; the predicted eye and the cameras get the centre ray
        return LineOfSight.visible(occluder, minX, minY, minZ, maxX, maxY, maxZ, ends, count, 1);
    }
}
