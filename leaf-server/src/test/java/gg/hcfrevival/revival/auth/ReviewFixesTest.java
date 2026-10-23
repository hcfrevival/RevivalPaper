package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.view.ClientProfile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure pieces of the review fixes (docs/revival/auth-review.md) that need no server. */
@RevivalTest
class ReviewFixesTest {

    @Test
    void aSeedNeverSweepsAClaimedMoveLongerThanTheBound() {
        // Review E1: a claim pair 1e8 apart would make the port sweep every block between them
        assertTrue(AuthPlayer.withinMove(0.3, -0.08, 9.99, 10.0));
        assertFalse(AuthPlayer.withinMove(0.0, 0.0, 1.0E8, 10.0));
        assertFalse(AuthPlayer.withinMove(-10.5, 0.0, 0.0, 10.0));
        assertFalse(AuthPlayer.withinMove(Double.NaN, 0.0, 0.0, 10.0), "NaN is never within");
    }

    @Test
    void vanillaTeleportIdsAreOrderedAcrossTheWrap() {
        // Review teleport-accept-bookkeeping-kicks: an accept older than the first teleport sent under authority
        assertTrue(AuthPlayer.teleportBefore(5, 9));
        assertFalse(AuthPlayer.teleportBefore(9, 5));
        assertFalse(AuthPlayer.teleportBefore(7, 7));
        assertTrue(AuthPlayer.teleportBefore(Integer.MAX_VALUE - 1, 2), "ids wrap from MAX_VALUE to 0");
    }

    @Test
    void aWarmUpClaimNeverReachesVanillaUnderEnforce() {
        // Review 3 #1: a client holding its first pongs flew on vanilla's 10-blocks-a-tick check until the freeze
        assertFalse(AuthPlayer.exemptClaimRuns(AuthMode.ENFORCE, AuthStats.EXEMPT_WARMUP));
        assertTrue(AuthPlayer.exemptClaimRuns(AuthMode.OBSERVE, AuthStats.EXEMPT_WARMUP), "observe trusts every claim");
        assertFalse(AuthPlayer.exemptClaimRuns(AuthMode.ENFORCE, AuthStats.EXEMPT_NOT_LOADED));
        assertFalse(AuthPlayer.exemptClaimRuns(AuthMode.OBSERVE, AuthStats.EXEMPT_REFUSED));
        assertTrue(AuthPlayer.exemptClaimRuns(AuthMode.ENFORCE, AuthStats.EXEMPT_RIDING), "riding stays vanilla's");
    }

    @Test
    void enforceNeverRidesWithVehiclesDenied() {
        // Review 3 #2: the mount check and the dismount when enforce starts (a login re-mount) share the rule
        assertTrue(Authority.deniesVehicles(AuthMode.ENFORCE, "deny"));
        assertFalse(Authority.deniesVehicles(AuthMode.OBSERVE, "deny"));
        assertFalse(Authority.deniesVehicles(AuthMode.ENFORCE, "vanilla"));
    }

    @Test
    void brandsAreClassed() {
        // Design §15.8 client profiles
        assertEquals(ClientProfile.Brand.VANILLA, ClientProfile.classify("vanilla"));
        assertEquals(ClientProfile.Brand.LUNAR, ClientProfile.classify("lunarclient:1.21.4-a1b2c3"));
        assertEquals(ClientProfile.Brand.FABRIC, ClientProfile.classify("fabric"));
        assertEquals(ClientProfile.Brand.FORGE, ClientProfile.classify("forge"));
        assertEquals(ClientProfile.Brand.OTHER, ClientProfile.classify("feather"));
        assertEquals(ClientProfile.Brand.UNKNOWN, ClientProfile.classify(""));
    }
}
