package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.view.ClientAttributes;
import java.util.List;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.junit.jupiter.api.Test;

/** §15.4: the truth has the sprint modifier only while it sprints, whatever a late movement-speed sync says. */
@RevivalTest
@RevivalRegistries
class PortSimulationSprintTest {

    @Test
    void aStaleSprintModifierIsDroppedAndAMissingOneAdded() {
        final List<AttributeModifier> stale = List.of(ClientAttributes.SPEED_MODIFIER_SPRINTING);
        assertTrue(PortSimulation.sprintFromState(stale, false).isEmpty(), "a sync sent while sprinting, acked after the sprint ended");
        final List<AttributeModifier> without = PortSimulation.sprintFromState(List.of(), true);
        assertEquals(1, without.size());
        assertEquals(ClientAttributes.SPRINTING_MODIFIER_ID, without.getFirst().id());
        assertSame(stale, PortSimulation.sprintFromState(stale, true), "nothing to change");
    }
}
