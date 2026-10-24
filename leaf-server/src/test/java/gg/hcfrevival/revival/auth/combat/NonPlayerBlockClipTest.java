package gg.hcfrevival.revival.auth.combat;

import static gg.hcfrevival.revival.auth.combat.CombatScenes.attacker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.view.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * The non-player block clip (design §7.1, 2026-10-24; pvp-issues WD-5): a villager is never hit through a block the
 * server has, wherever it may have been drawn, and never refused on reach or aim. The attacker stands at (0.5, 64, 0.5)
 * looking south (+z) with 1.7 ranges, its eye at y 65.62; the villager (0.6 x 1.95) stands 2.8 blocks ahead.
 */
@RevivalTest
@RevivalRegistries
class NonPlayerBlockClipTest {

    private static final AttackerSnapshot SOUTH = attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.5, 3.0);
    /** A killaura's swing: the crosshair off to the west, nowhere near the target. */
    private static final AttackerSnapshot WEST = attacker(0.5, 64.0, 0.5, 90.0F, 0.0F, 4.5, 3.0);
    private static final int VILLAGER = 86;
    /** CombatWiring.NON_PLAYER_MARGIN. */
    private static final double MARGIN = 0.1;
    private static final BlockState GLASS = Blocks.GLASS.defaultBlockState();

    /** {@code EntityDimensions.makeBoundingBox} of a villager (0.6F x 1.95F) with its feet at (x, y, z). */
    private static AABB villager(final double x, final double y, final double z) {
        final float w = 0.6F / 2.0F;
        return new AABB(x - w, y, z - w, x + w, y + 1.95F, z + w);
    }

    private static HitValidator.Region region(final AABB... boxes) {
        final HitValidator.Region region = new HitValidator.Region();
        for (final AABB box : boxes) {
            region.include(box);
        }
        return region.inflate(MARGIN);
    }

    private static HitCheck clip(final AttackerSnapshot attacker, final PickWorld world, final AABB... boxes) {
        return new HitValidator(TruthHistory.NONE).blockClip(attacker, VILLAGER, region(boxes), world);
    }

    /** The NAS case: a one-block glass cell, glass on every side and on top, the villager inside at (0.5, 64, 3.5). */
    private static CombatScenes.Blocks2 glassCell() {
        final CombatScenes.Blocks2 world = new CombatScenes.Blocks2();
        for (int x = -1; x <= 1; x++) {
            for (int z = 2; z <= 4; z++) {
                world.set(x, 63, z, Blocks.STONE.defaultBlockState());
                world.set(x, 66, z, GLASS);
                for (int y = 64; y <= 65; y++) {
                    if (x != 0 || z != 3) {
                        world.set(x, y, z, GLASS);
                    }
                }
            }
        }
        return world;
    }

    // A glass wall across z = 2, x -2..2, `height` blocks from y 64
    private static CombatScenes.Blocks2 glassWall(final int height) {
        final CombatScenes.Blocks2 world = new CombatScenes.Blocks2();
        for (int x = -2; x <= 2; x++) {
            for (int y = 64; y < 64 + height; y++) {
                world.set(x, y, 2, GLASS);
            }
        }
        return world;
    }

    @Test
    void aVillagerInAGlassCellIsBlocked() {
        final HitCheck check = clip(SOUTH, glassCell(), villager(0.5, 64.0, 3.5));
        assertEquals(HitCheck.Verdict.INVALID, check.verdict());
        assertEquals(HitCheck.Reason.BLOCKED, check.reason());
        assertEquals(HitCheck.Reason.BLOCKED, clip(WEST, glassCell(), villager(0.5, 64.0, 3.5)).reason(), "wherever the crosshair was");
    }

    @Test
    void aVillagerInTheOpenIsNotVetoed() {
        final HitCheck check = clip(SOUTH, new CombatScenes.Blocks2(), villager(0.5, 64.0, 3.3));
        assertEquals(HitCheck.Verdict.UNKNOWN, check.verdict(), "vanilla's checks decide");
        assertEquals(HitCheck.Reason.IN_SIGHT, check.reason());
        assertEquals(2.4, check.distance(), 1.0E-6); // to the region's near face (3.0 less the margin; the box's half width is a float)
    }

    @Test
    void neitherAimNorReachIsJudged() {
        // A killaura's swing at a villager in the open: the crosshair is far off, but nothing stands between
        assertEquals(HitCheck.Reason.IN_SIGHT, clip(WEST, new CombatScenes.Blocks2(), villager(0.5, 64.0, 3.3)).reason());
        // 5 blocks ahead, past the 3-block entity range: vanilla's reach check refuses it, not this
        assertEquals(HitCheck.Verdict.UNKNOWN, clip(SOUTH, new CombatScenes.Blocks2(), villager(0.5, 64.0, 5.8)).verdict());
        assertEquals(HitCheck.Reason.OUT_OF_REACH, clip(SOUTH, new CombatScenes.Blocks2(), villager(0.5, 64.0, 30.0)).reason(),
            "past the clip's own reach: not looked at");
    }

    @Test
    void aVillagerBehindAOneHighWallIsInSightOverIt() {
        assertEquals(HitCheck.Reason.IN_SIGHT, clip(SOUTH, glassWall(1), villager(0.5, 64.0, 3.3)).reason());
        assertEquals(HitCheck.Reason.BLOCKED, clip(SOUTH, glassWall(3), villager(0.5, 64.0, 3.3)).reason(), "and not through a 3-high one");
    }

    @Test
    void aPreviousTickBoxInSightIsEnough() {
        // Stone at z = 2 for x -2..0, 3 high: the villager now (x 0.5) is behind it, a tick ago (x 1.6) it was past its end.
        // The crosshair meets the wall; a line from the eye still reaches the box of a tick ago
        final CombatScenes.Blocks2 world = new CombatScenes.Blocks2();
        for (int x = -2; x <= 0; x++) {
            for (int y = 64; y <= 66; y++) {
                world.set(x, y, 2, Blocks.STONE.defaultBlockState());
            }
        }
        final AABB now = villager(0.5, 64.0, 3.3);
        final AABB tickAgo = villager(1.6, 64.0, 3.3);
        assertEquals(HitCheck.Reason.BLOCKED, clip(SOUTH, world, now).reason(), "now alone: behind the wall");
        assertEquals(HitCheck.Reason.IN_SIGHT, clip(SOUTH, world, now, tickAgo).reason(), "rejected only when every candidate is blocked");
        assertEquals(HitCheck.Reason.IN_SIGHT, clip(WEST, world, now, tickAgo).reason());
    }

    @Test
    void aDeniedBreakHoleIsStillTheBlock() {
        // The NAS glass box, 2026-10-24: the client broke the glass in front of its eye (protected: the server denied it),
        // so its world has a hole there until the ack restores it; the truth never had the hole
        final CombatScenes.Blocks2 blocks = glassWall(3);
        final WorldView view = worldView(blocks);
        final BlockPos glass = new BlockPos(0, 65, 2);
        view.predict(glass, 1, 1, 0.5, 64.0, 0.5, true, Blocks.AIR.defaultBlockState(), 0.5, 64.0, 0.5);
        assertEquals(1, view.holes(), "a hole on the client only");
        final AABB box = villager(0.5, 64.0, 3.3);
        view.readAsClient(true);
        try {
            assertEquals(HitCheck.Reason.IN_SIGHT, clip(SOUTH, view, box).reason(), "the client saw the villager through its hole");
        } finally {
            view.readAsClient(false);
        }
        final HitCheck truth = clip(SOUTH, view, box); // the view as the judge reads it (CombatWiring.judgeNonPlayer)
        assertEquals(HitCheck.Verdict.INVALID, truth.verdict());
        assertEquals(HitCheck.Reason.BLOCKED, truth.reason());
    }

    // A WorldView over a mocked level whose blocks are `blocks`; the client holds chunks -1..0, 0
    private static WorldView worldView(final CombatScenes.Blocks2 blocks) {
        final ServerLevel level = mock(ServerLevel.class);
        when(level.getMinY()).thenReturn(-64);
        when(level.getHeight()).thenReturn(384);
        when(level.getChunkSource()).thenReturn(mock(ServerChunkCache.class));
        when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            final BlockPos pos = call.getArgument(0);
            return blocks.blockState(pos.getX(), pos.getY(), pos.getZ());
        });
        final WorldView view = new WorldView();
        view.setLevel(level, true);
        view.onChunk(-1, 0);
        view.onChunk(0, 0);
        return view;
    }
}
