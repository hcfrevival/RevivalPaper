package gg.hcfrevival.revival.auth.input;

import static gg.hcfrevival.revival.auth.input.InputRules.ATTACK;
import static gg.hcfrevival.revival.auth.input.InputRules.CONTAINER_CLICK;
import static gg.hcfrevival.revival.auth.input.InputRules.CONTAINER_CLOSE;
import static gg.hcfrevival.revival.auth.input.InputRules.MOVE;
import static gg.hcfrevival.revival.auth.input.InputRules.OK;
import static gg.hcfrevival.revival.auth.input.InputRules.OTHER;
import static gg.hcfrevival.revival.auth.input.InputRules.RELEASE;
import static gg.hcfrevival.revival.auth.input.InputRules.TICK_END;
import static gg.hcfrevival.revival.auth.input.InputRules.USE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** §15.4 input grammar: one rule per test, each from Minecraft.handleKeybinds or the screens. */
@RevivalTest
class InputRulesTest {

    @Test
    void anAttackOrUseWhileUsingIsDroppedIncludingTheReleaseTick() {
        final InputRules rules = new InputRules();
        rules.startWindow(false);
        rules.keybinds(true);
        assertEquals(InputRules.ATTACK_WHILE_USING, rules.check(ATTACK, false));
        assertEquals(InputRules.USE_WHILE_USING, rules.check(USE, false));
        // The release tick: the isUsingItem branch released and threw the clicks away
        rules.startWindow(false);
        rules.keybinds(true);
        assertEquals(OK, rules.check(RELEASE, false));
        assertEquals(InputRules.ATTACK_WHILE_USING, rules.check(ATTACK, false));
        // The tick after: free
        rules.startWindow(false);
        rules.keybinds(false);
        assertEquals(OK, rules.check(ATTACK, false));
        assertEquals(2, rules.count(InputRules.ATTACK_WHILE_USING));
    }

    @Test
    void attacksComeBeforeUses() {
        final InputRules rules = new InputRules();
        rules.startWindow(false);
        assertEquals(OK, rules.check(ATTACK, false), "a block-hit: attack, then block, in one tick");
        assertEquals(OK, rules.check(USE, false));
        assertEquals(InputRules.ATTACK_AFTER_USE, rules.check(ATTACK, false));
    }

    @Test
    void noAttackInTheTickAContainerClosed() {
        // missTime = 10000 while the screen was open drops the next tick's clicks (report 02 §2.3)
        final InputRules rules = new InputRules();
        rules.startWindow(false);
        assertEquals(OK, rules.check(CONTAINER_CLOSE, false));
        assertEquals(InputRules.ATTACK_AFTER_CLOSE, rules.check(ATTACK, false));
        assertTrue(InputRules.drops(InputRules.ATTACK_AFTER_CLOSE));
        assertFalse(InputRules.drops(OK));
    }

    @Test
    void clickAndCloseEveryTickWhileWalkingNeverOpenedAScreen() {
        // Review E8: [click, close] in every window with the keys held down is an inventory move
        final InputRules rules = new InputRules();
        rules.startWindow(false); // the screen opened: releaseAll, the tick sent no walking keys
        assertEquals(OK, rules.check(CONTAINER_CLICK, false));
        rules.startWindow(true);
        assertEquals(OK, rules.check(CONTAINER_CLICK, true), "the last click before the close that re-read the keys");
        assertEquals(OK, rules.check(CONTAINER_CLOSE, false));
        rules.startWindow(true);
        assertEquals(InputRules.CLICK_WHILE_MOVING, rules.check(CONTAINER_CLICK, true), "no tick since the close released the keys");
        assertEquals(OK, rules.check(CONTAINER_CLOSE, false));
        rules.startWindow(true);
        assertEquals(InputRules.CLICK_WHILE_MOVING, rules.check(CONTAINER_CLICK, true));
        assertEquals(2, rules.count(InputRules.CLICK_WHILE_MOVING));
    }

    @Test
    void containerClicksWhileWalkingAreAnInventoryMove() {
        final InputRules rules = new InputRules();
        rules.startWindow(true);
        assertEquals(InputRules.CLICK_WHILE_MOVING, rules.check(CONTAINER_CLICK, false));
        // Click, then close: closing re-reads the keys still held, so the tick's input may walk (after the opening's release)
        rules.startWindow(false);
        rules.startWindow(true);
        assertEquals(OK, rules.check(CONTAINER_CLICK, true));
        assertEquals(OK, rules.check(CONTAINER_CLOSE, false));
        // Standing still (or only toggled sneak/sprint down): fine
        rules.startWindow(false);
        assertEquals(OK, rules.check(CONTAINER_CLICK, false));
    }

    @Test
    void nothingButTickEndAfterTheMove() {
        final InputRules rules = new InputRules();
        rules.startWindow(false);
        assertEquals(OK, rules.check(MOVE, false));
        assertEquals(InputRules.AFTER_MOVE, rules.check(ATTACK, false));
        assertEquals(InputRules.AFTER_MOVE, rules.check(OTHER, false));
        assertEquals(OK, rules.check(TICK_END, false));
        assertEquals(2, rules.total());
    }

    @Test
    void walkingIsMovementOrJumpNotTheToggles() {
        final net.minecraft.world.entity.player.Input sneakSprint = new net.minecraft.world.entity.player.Input(false, false, false, false, false, true, true);
        assertEquals(false, InputRules.walking(sneakSprint));
        assertEquals(true, InputRules.walking(new net.minecraft.world.entity.player.Input(false, false, true, false, false, false, false)));
        assertEquals(true, InputRules.walking(new net.minecraft.world.entity.player.Input(false, false, false, false, true, false, false)));
    }
}
