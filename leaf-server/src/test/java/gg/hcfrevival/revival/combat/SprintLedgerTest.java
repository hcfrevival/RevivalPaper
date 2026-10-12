package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.KnockbackProfile;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

@RevivalTest
class SprintLedgerTest {

    private static final KnockbackProfile LEGACY = KnockbackProfile.LEGACY_1_7;
    private static final KnockbackProfile HCFR = KnockbackProfile.PRESETS.get("hcfr");

    @Test
    void oneSprintKnockPerSprintStart() {
        final SprintLedger ledger = new SprintLedger();
        assertEquals(0.0, ledger.level(true, LEGACY)); // sprinting, but no START seen yet

        ledger.start(true);
        assertEquals(1.0, ledger.level(true, LEGACY));
        ledger.consume();
        assertEquals(0.0, ledger.level(true, LEGACY)); // still holding sprint: 1.7.10 needed a w-tap

        ledger.start(true); // a repeated START without a STOP doesn't re-arm
        assertEquals(0.0, ledger.level(true, LEGACY));

        ledger.stop();
        ledger.start(true); // w-tap
        assertEquals(1.0, ledger.level(true, LEGACY));
    }

    @Test
    void notSprintingMeansNoSprintLevel() {
        final SprintLedger ledger = new SprintLedger();
        ledger.start(true);
        assertEquals(0.0, ledger.level(false, LEGACY));
        assertEquals(0.0, ledger.level(false, HCFR));
    }

    @Test
    void stopDisarms() {
        final SprintLedger ledger = new SprintLedger();
        ledger.start(true);
        ledger.stop();
        assertEquals(0.0, ledger.level(true, LEGACY));
    }

    /** Core's SwordProfile.sprintLevel with mechanics.yml: 0.8 while sprinting, plus 0.5 after a sprint start on the ground. */
    @Test
    void hcfrSprintLevelsMatchCore() {
        final SprintLedger ledger = new SprintLedger();
        ledger.start(true);
        assertEquals(1.3, ledger.level(true, HCFR));
        ledger.consume();
        assertEquals(0.8, ledger.level(true, HCFR));

        ledger.stop();
        ledger.start(false); // core's ground-check: a sprint started in the air doesn't arm
        assertEquals(0.8, ledger.level(true, HCFR));
        assertEquals(1.0, ledger.level(true, LEGACY)); // 1.7.10 had no ground check
    }

    @Test
    void doubleHitWithoutWTapLosesTheSprintKnock() {
        final SprintLedger ledger = new SprintLedger();
        ledger.start(true);
        final Vec3 standing = new Vec3(0.0, -0.0784, 0.0);
        final Vec3 first = KnockbackEngine.knockback(standing, 0.0, -1.0, 0.0F, ledger.level(true, LEGACY), LEGACY, 0.0, RandomSource.create(0L));
        ledger.consume();
        final Vec3 second = KnockbackEngine.knockback(standing, 0.0, -1.0, 0.0F, ledger.level(true, LEGACY), LEGACY, 0.0, RandomSource.create(0L));

        assertEquals(0.9, first.z, 1.0E-4);
        assertEquals(0.4, second.z, 1.0E-4);
    }
}
