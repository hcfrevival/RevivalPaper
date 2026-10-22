package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Report 04 §7 and design §15.6: which packets change the client's physics. */
@RevivalTest
@RevivalRegistries
class OutboundClassifierTest {

    private static final UUID SELF = new UUID(3L, 4L);

    @Test
    void selfAndOthers() {
        final IntOpenHashSet players = new IntOpenHashSet();
        assertEquals(OutboundClassifier.SELF_MOTION, OutboundClassifier.classify(new ClientboundSetEntityMotionPacket(7, Vec3.ZERO), 7, SELF, players));
        assertEquals(OutboundClassifier.NONE, OutboundClassifier.classify(new ClientboundSetEntityMotionPacket(8, Vec3.ZERO), 7, SELF, players));
        final ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(8, new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F),
            java.util.Set.of(), false);
        assertEquals(OutboundClassifier.NONE, OutboundClassifier.classify(teleport, 7, SELF, players), "not a player this client was sent");
        players.add(8);
        assertEquals(OutboundClassifier.REMOTE_PLAYER, OutboundClassifier.classify(teleport, 7, SELF, players));
    }

    @Test
    void onlyGameModeGameEventsMatter() {
        final IntOpenHashSet players = new IntOpenHashSet();
        assertEquals(OutboundClassifier.GAME_MODE, OutboundClassifier.classify(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, 1.0F), 7, SELF, players));
        assertEquals(OutboundClassifier.NONE, OutboundClassifier.classify(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0F), 7, SELF, players));
    }
}
