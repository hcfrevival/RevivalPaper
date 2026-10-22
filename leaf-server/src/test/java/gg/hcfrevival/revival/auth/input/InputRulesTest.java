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

    @Test
    void anAttackWithoutItsPunchIsCountedNotDropped() {
        // Cheat suite 2 L2: startAttack swings after the attack, so a vanilla client sends a Punch with every Attack
        final InputRules rules = new InputRules();
        rules.startWindow(false);
        rules.keybinds(false);
        assertEquals(OK, rules.check(ATTACK, false));
        assertEquals(OK, rules.check(InputRules.PUNCH, false));
        assertEquals(OK, rules.check(TICK_END, false), "attack and swing");
        rules.startWindow(false);
        rules.keybinds(false);
        assertEquals(OK, rules.check(ATTACK, false));
        assertEquals(OK, rules.check(ATTACK, false));
        assertEquals(OK, rules.check(InputRules.PUNCH, false));
        assertEquals(InputRules.ATTACK_WITHOUT_PUNCH, rules.check(TICK_END, false), "two attacks, one swing");
        assertFalse(InputRules.drops(InputRules.ATTACK_WITHOUT_PUNCH), "counted only: the tick still runs");
        rules.startWindow(false);
        rules.keybinds(false);
        assertEquals(OK, rules.check(InputRules.PUNCH, false));
        assertEquals(OK, rules.check(TICK_END, false), "a swing at the air is no attack");
        assertEquals(1, rules.count(InputRules.ATTACK_WITHOUT_PUNCH));
    }

    @Test
    void aReleaseOnlyComesFromAWindowThatStartedUsing() {
        // Classic audit #5: Minecraft.handleKeybinds sends RELEASE_USE_ITEM only from its isUsingItem branch, so a use
        // and its release in one window (a block for no tick, a free sprint re-arm) is impossible
        final InputRules rules = new InputRules();
        rules.startWindow(false);
        rules.keybinds(false);
        assertEquals(OK, rules.check(USE, false));
        assertEquals(InputRules.RELEASE_WITHOUT_USE, rules.check(RELEASE, false));
        assertTrue(InputRules.drops(InputRules.RELEASE_WITHOUT_USE));
        rules.startWindow(false);
        rules.keybinds(false);
        assertEquals(InputRules.RELEASE_WITHOUT_USE, rules.check(RELEASE, false), "a release with no use at all");
        rules.startWindow(false);
        rules.keybinds(true);
        assertEquals(OK, rules.check(RELEASE, false), "the honest release tick");
        assertEquals(2, rules.count(InputRules.RELEASE_WITHOUT_USE));
    }

    @Test
    void noOffHandUseBehindAMainHandItemThatStartsUsing() {
        // Classic audit #3: startUseItem tries the main hand first and a sword's block consumes the click
        final InputRules rules = new InputRules();
        rules.startWindow(false);
        rules.keybinds(false);
        assertEquals(OK, rules.check(USE, false));
        assertEquals(InputRules.OFFHAND_AFTER_MAIN, rules.hand(true, true), "an off-hand pot behind a sword");
        assertEquals(OK, rules.hand(true, false), "an empty or non-using main hand lets the off hand go");
        assertEquals(OK, rules.hand(false, true), "the main hand itself");
        assertTrue(InputRules.drops(InputRules.OFFHAND_AFTER_MAIN));
        assertEquals(1, rules.count(InputRules.OFFHAND_AFTER_MAIN));
    }

    @Test
    void noHotbarChangeAfterTheTicksFirstAttackOrUse() {
        // Classic audit #3 (pvp-issues IN-5): hotbar keys are read first, and every action sends its slot before it
        final InputRules rules = new InputRules();
        rules.startWindow(false);
        rules.keybinds(false);
        assertEquals(OK, rules.carried(true), "a hotbar key, then...");
        assertEquals(OK, rules.check(USE, false), "...a use (cheat suite 2 H2)");
        assertEquals(InputRules.SLOT_AFTER_ACTION, rules.carried(true), "the pot thrown, the sword back the same tick");
        assertEquals(OK, rules.carried(false), "the slot it already holds");
        rules.startWindow(false);
        rules.keybinds(false);
        assertEquals(OK, rules.check(ATTACK, false));
        assertEquals(InputRules.SLOT_AFTER_ACTION, rules.carried(true));
        rules.startWindow(false);
        rules.keybinds(false);
        assertEquals(OK, rules.carried(true), "next tick: free");
        assertTrue(InputRules.drops(InputRules.SLOT_AFTER_ACTION));
        assertEquals(2, rules.count(InputRules.SLOT_AFTER_ACTION));
    }

    @Test
    void aUseRotationOffTheTicksIsCountedNeverDropped() {
        final InputRules rules = new InputRules();
        rules.useRotation();
        assertEquals(1, rules.count(InputRules.USE_ROTATION));
        assertFalse(InputRules.drops(InputRules.USE_ROTATION), "rewritten to the tick's rotation, the use still runs");
        assertEquals("use-rotation", InputRules.name(InputRules.USE_ROTATION));
        assertEquals(0, rules.total(), "no grammar alert: modded clients send keybind packets outside the tick");
    }
}
