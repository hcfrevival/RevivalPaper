package gg.hcfrevival.revival.auth.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** §15.2 prediction sequences: per ClientLevel, and no jump far enough ahead to poison every later one. */
@RevivalTest
class WorldViewSequenceTest {

    @Test
    void aNewLevelStartsTheSequenceAgain() {
        // Review prediction-sequence-not-reset-on-level: a dimension change makes a new ClientLevel, counting from 1
        final WorldView view = new WorldView();
        view.setLevel(null, true);
        assertTrue(view.predict(BlockPos.ZERO, 40, 1, 0.0, 0.0, 0.0, false));
        assertFalse(view.predict(BlockPos.ZERO, 12, 2, 0.0, 0.0, 0.0, false), "backwards in one level");
        view.setLevel(null, true);
        assertTrue(view.predict(BlockPos.ZERO, 1, 3, 0.0, 0.0, 0.0, false), "the new level's first prediction");
    }

    @Test
    void aSequenceFarAheadIsRefusedAndDoesNotPoisonTheNextOnes() {
        // Review E10: one prediction at 2^31-1 would make every later honest one look backwards
        final WorldView view = new WorldView();
        view.setLevel(null, true);
        assertTrue(view.predict(BlockPos.ZERO, 5, 1, 0.0, 0.0, 0.0, false));
        assertFalse(view.predict(BlockPos.ZERO, Integer.MAX_VALUE, 2, 0.0, 0.0, 0.0, false));
        assertTrue(view.predict(BlockPos.ZERO, 6, 3, 0.0, 0.0, 0.0, false), "the honest next one still counts");
    }
}
