package gg.hcfrevival.revival.auth.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.replicate.PathFeed;
import java.util.Random;
import net.minecraft.world.entity.EntityTypes;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RemoteMirror} to the real 26.3 client. The verification rig (~/Developer/tools/auth-rig/combat, MirrorVerify)
 * replays {@link MirrorStreams} on the client's own {@code SteppedInterpolationHandler}, {@code Entity} and
 * {@code ClientPacketListener} handler bodies and on the mirror, bit for bit: 2,000,000 ticks, 0 differences
 * (docs/revival/auth-combat.md). It prints these fingerprints of the mirror's state over fixed streams; any change to the
 * port that alters a single bit of a box, position, rotation or interpolator field changes them. Re-run the rig before
 * updating a value here.
 */
@RevivalTest
@RevivalRegistries
class MirrorFingerprintTest {

    /** {@code MirrorVerify}: "fingerprint adversarial seed=7 trials=200 ticks=200". */
    static final long ADVERSARIAL = 0xEB1BDE5FF2784AC9L;
    /** {@code MirrorVerify}: "fingerprint fed seed=11 trials=100 ticks=400". */
    static final long FED = 0x7C4B2C64ECE587A2L;

    @Test
    void playersInterpolateOverTwoTicks() {
        // RemoteMirror is built with N = 2; the rig checks the client's own value too
        assertEquals(2, EntityTypes.PLAYER.updateInterval());
        assertEquals(2, MirrorBook.playerInterpolationSteps());
    }

    @Test
    void adversarialStreamsMatchTheRig() {
        final Random random = new Random(7L);
        long hash = 0L;
        for (int i = 0; i < 200; i++) {
            hash = MirrorStreams.run(MirrorStreams.adversarial(random, 200), new RemoteMirror(2), hash);
        }
        assertEquals(ADVERSARIAL, hash, String.format("got 0x%016XL", hash));
    }

    @Test
    void pathFeedStreamsMatchTheRig() {
        final Random random = new Random(11L);
        long hash = 0L;
        for (int i = 0; i < 100; i++) {
            hash = MirrorStreams.run(MirrorStreams.fed(random, 400, i % 4, PathFeed.Settings.DEFAULT), new RemoteMirror(2), hash);
        }
        assertEquals(FED, hash, String.format("got 0x%016XL", hash));
    }
}
