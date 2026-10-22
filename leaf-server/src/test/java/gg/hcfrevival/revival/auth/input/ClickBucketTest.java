package gg.hcfrevival.revival.auth.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** neue-combat §4.3: 12 CPS sustained, burst 2, per client tick. */
@RevivalTest
class ClickBucketTest {

    private static final double REFILL = 0.6;
    private static final double BURST = 2.0;

    // An even clicker at cps over 2000 ticks: clicks land on the tick a click falls in
    private static int[] evenClicker(final double cps) {
        final ClickBucket bucket = new ClickBucket();
        int clicks = 0;
        int landed = 0;
        double next = 0.0;
        for (int tick = 0; tick < 2000; tick++) {
            bucket.refill(REFILL, BURST);
            if (tick >= next) {
                next += 20.0 / cps;
                clicks++;
                if (bucket.take(BURST)) {
                    landed++;
                }
            }
        }
        return new int[] {clicks, landed};
    }

    @Test
    void evenClickersUpToTheCapLoseNothing() {
        for (final double cps : new double[] {9.0, 12.0}) {
            final int[] result = evenClicker(cps);
            assertEquals(result[0], result[1], cps + " CPS: " + result[1] + " of " + result[0]);
        }
    }

    @Test
    void twentyASecondLandsTwelve() {
        final ClickBucket bucket = new ClickBucket();
        int landed = 0;
        for (int tick = 0; tick < 2000; tick++) {
            bucket.refill(REFILL, BURST);
            if (bucket.take(BURST)) {
                landed++;
            }
        }
        final double perSecond = landed / 100.0;
        assertTrue(Math.abs(perSecond - 12.0) <= 0.2, perSecond + " a second");
    }

    @Test
    void afterIdlingThreeTicksInARowThenTheFourthIsRefused() {
        final ClickBucket bucket = new ClickBucket();
        for (int tick = 0; tick < 40; tick++) {
            bucket.refill(REFILL, BURST); // 2 s idle: full
        }
        bucket.refill(REFILL, BURST);
        assertTrue(bucket.take(BURST));
        bucket.refill(REFILL, BURST);
        assertTrue(bucket.take(BURST));
        bucket.refill(REFILL, BURST);
        assertTrue(bucket.take(BURST));
        bucket.refill(REFILL, BURST);
        assertFalse(bucket.take(BURST), "2 + 0.6 * 3 = 3.8 tokens over four ticks");
    }
}
