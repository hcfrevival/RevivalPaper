package gg.hcfrevival.revival.obfuscator;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.PlayerObfuscatorSettings;
import it.unimi.dsi.fastutil.ints.IntIterator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

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
        if (!sameLevel || !candidate(player)) {
            state.clear();
            return;
        }
        final ChunkMap chunkMap = level.getChunkSource().chunkMap;
        // The tracker's own candidates for this chunk, and the flat range it never pairs beyond
        final @Nullable ReferenceList<ServerPlayer> viewers = level.moonrise$getNearbyPlayers().getPlayers(player.chunkPosition(), NearbyPlayers.NearbyMapType.VIEW_DISTANCE);
        final double range = Math.min(level.spigotConfig.playerTrackingRange, chunkMap.serverViewDistance * 16);
        final double rangeSq = range * range;
        final int budget = settings.blockChecksPerTick();
        final LineOfSight.LevelOccluder occluder = state.occluder.reset(level);
        final boolean crouching = player.isCrouching();
        final int n = viewers == null ? 0 : viewers.size();
        final int start = n == 0 ? 0 : tick % n; // rotate, so a budget cut never starves the same pairs
        for (int k = 0; k < n; k++) {
            final ServerPlayer viewer = viewers.getUnchecked((start + k) % n);
            if (viewer == player) {
                continue;
            }
            final int viewerId = viewer.getId();
            final double dx = viewer.getX() - player.getX();
            final double dz = viewer.getZ() - player.getZ();
            final double distanceSq = dx * dx + dz * dz;
            // Vanilla for this pair: out of range, a viewer who sees everyone, or an invisible teammate the client draws anyway
            if (distanceSq > rangeSq || seesEveryone(viewer.gameMode(), viewer.revivalObfuscator.bypass) || !(crouching || player.isInvisibleTo(viewer))) {
                state.record(viewerId, false, tick);
                continue;
            }
            if (!state.retestAll && (tick + viewerId) % interval(distanceSq, state.occluded(viewerId)) != 0) {
                continue;
            }
            if (budget > 0 && occluder.lookups >= budget) {
                continue; // out of budget: last tick's answer stands until next tick
            }
            state.record(viewerId, !visible(state, occluder, player, viewer, settings, tick), tick);
        }
        state.retestAll = false;
        // A viewer the tracker can no longer pair is not in the list above, so nothing else would forget it
        for (final IntIterator it = state.occludedSince.keySet().intIterator(); it.hasNext(); ) {
            if (!pairable(level.getEntity(it.nextInt()), player, chunkMap, rangeSq)) {
                it.remove();
            }
        }
        state.publish(tick, settings.hideDelayTicks());
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

    /** Whether the tracker could still pair the viewer with the target: in its level, within range, with its chunk sent. */
    private static boolean pairable(final @Nullable Entity viewer, final ServerPlayer target, final ChunkMap chunkMap, final double rangeSq) {
        if (!(viewer instanceof ServerPlayer p)) {
            return false;
        }
        final double dx = p.getX() - target.getX();
        final double dz = p.getZ() - target.getZ();
        return dx * dx + dz * dz <= rangeSq && chunkMap.isChunkTracked(p, target.chunkPosition().x(), target.chunkPosition().z());
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

    private static boolean visible(final ObfuscatorState state, final LineOfSight.LevelOccluder occluder, final ServerPlayer target, final ServerPlayer viewer, final PlayerObfuscatorSettings settings, final int tick) {
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
        if (viewerState.cameraTick != tick) {
            // Where the viewer's third-person camera could sit: clipped once per tick by the first target that needs it,
            // on that target's budget, so viewers nobody is hiding from cost nothing
            viewerState.cameraTick = tick;
            viewerState.cameraCount = settings.cameraDistance() > 0.0
                ? LineOfSight.addCameraEnds(occluder, eyeX, eyeY, eyeZ, viewer.getYRot(), viewer.getXRot(), settings.cameraDistance(), viewerState.camera, 0)
                : 0;
        }
        for (int i = 0, m = viewerState.cameraCount * 3; i < m; i++) {
            ends[count * 3 + i] = viewerState.camera[i];
        }
        count += viewerState.cameraCount;
        // Corner rays go to the eye only; the predicted eye and the cameras get the centre ray
        return LineOfSight.visible(occluder, minX, minY, minZ, maxX, maxY, maxZ, ends, count, 1);
    }
}
