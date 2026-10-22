package gg.hcfrevival.revival.auth.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/** §15.2: a block change is admitted as the client's prediction only if a vanilla client would have made it. */
@RevivalTest
@RevivalRegistries
class PredictionRulesTest {

    @Test
    void onlyABlockItemPlacesItself() {
        assertTrue(PredictionRules.canPlace(new ItemStack(Items.STONE)));
        assertFalse(PredictionRules.canPlace(ItemStack.EMPTY), "an empty hand places nothing (HCF ghost blocks)");
        assertFalse(PredictionRules.canPlace(new ItemStack(Items.DIAMOND_SWORD)));
    }

    @Test
    void theCrosshairMustBeOnTheBlock() {
        assertTrue(PredictionRules.onCrosshair(true, 1, 2, 3, 1, 2, 3));
        assertFalse(PredictionRules.onCrosshair(true, 1, 2, 4, 1, 2, 3));
        assertFalse(PredictionRules.onCrosshair(false, 1, 2, 3, 1, 2, 3), "out of reach or a miss");
    }
}
