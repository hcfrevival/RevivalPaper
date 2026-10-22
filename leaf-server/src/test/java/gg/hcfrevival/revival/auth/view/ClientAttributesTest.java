package gg.hcfrevival.revival.auth.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.sim.SimAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.jupiter.api.Test;

/** Report 01 §7.1, report 02 §3.6: the client's attribute semantics, including removeModifiers + re-add. */
@RevivalTest
@RevivalRegistries
class ClientAttributesTest {

    private static AttributeModifier total(final String id, final double amount) {
        return new AttributeModifier(Identifier.withDefaultNamespace(id), amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    private static List<ClientboundUpdateAttributesPacket.AttributeSnapshot> speed(final double base, final AttributeModifier... modifiers) {
        return List.of(new ClientboundUpdateAttributesPacket.AttributeSnapshot(Attributes.MOVEMENT_SPEED, base, List.of(modifiers)));
    }

    @Test
    void startsFromThePlayerDefaults() {
        final ClientAttributes attributes = new ClientAttributes();
        assertEquals(0.10000000149011612, attributes.value(Attributes.MOVEMENT_SPEED));
        assertEquals(3.0, attributes.value(Attributes.ENTITY_INTERACTION_RANGE));
        assertEquals(4.5, attributes.value(Attributes.BLOCK_INTERACTION_RANGE));
    }

    @Test
    void anUpdateWipesTheClientsOwnSprintModifier() {
        final ClientAttributes attributes = new ClientAttributes();
        attributes.setSprinting(true);
        assertEquals(0.10000000149011612 * (1.0 + 0.30000001192092896), attributes.value(Attributes.MOVEMENT_SPEED));
        attributes.apply(speed(0.10000000149011612));
        assertFalse(attributes.instance(Attributes.MOVEMENT_SPEED).hasModifier(ClientAttributes.SPRINTING_MODIFIER_ID), "H4: removeModifiers");
        assertEquals(0.10000000149011612, attributes.value(Attributes.MOVEMENT_SPEED));
        attributes.apply(speed(0.10000000149011612, ClientAttributes.SPEED_MODIFIER_SPRINTING));
        assertTrue(attributes.instance(Attributes.MOVEMENT_SPEED).hasModifier(ClientAttributes.SPRINTING_MODIFIER_ID));
    }

    @Test
    void respawnKeepsModifiersOnlyWhenAsked() {
        final ClientAttributes attributes = new ClientAttributes();
        attributes.apply(speed(0.2, total("speed", 0.4)));
        attributes.respawn(true);
        assertEquals(0.2 * 1.4, attributes.value(Attributes.MOVEMENT_SPEED));
        attributes.respawn(false);
        assertEquals(0.2, attributes.value(Attributes.MOVEMENT_SPEED), "assignBaseValues: the base stays, modifiers go");
    }

    /**
     * Two independent ports of the client's attribute maps (this view's and the simulation's) must agree bit for bit on
     * any history: several multiply-total modifiers make the product order, and so the last bits, depend on it.
     */
    @Test
    void agreesWithTheSimulationsCopyOnRandomHistories() {
        final Random random = new Random(263);
        for (int run = 0; run < 2000; run++) {
            final ClientAttributes view = new ClientAttributes();
            final SimAttributes sim = new SimAttributes();
            for (int step = 0; step < 12; step++) {
                if (random.nextInt(3) == 0) {
                    final boolean sprint = random.nextBoolean();
                    view.setSprinting(sprint);
                    sim.movementSpeed.removeModifier(SimAttributes.SPRINTING.id());
                    if (sprint) {
                        sim.movementSpeed.addTransientModifier(SimAttributes.SPRINTING);
                    }
                } else {
                    final List<AttributeModifier> modifiers = new ArrayList<>();
                    final int count = random.nextInt(5);
                    for (int i = 0; i < count; i++) {
                        modifiers.add(total("m" + random.nextInt(8) + "_" + i, random.nextDouble() * 2 - 0.9));
                    }
                    if (random.nextBoolean()) {
                        modifiers.add(ClientAttributes.SPEED_MODIFIER_SPRINTING);
                    }
                    final double base = 0.05 + random.nextDouble() * 0.2;
                    view.apply(speed(base, modifiers.toArray(AttributeModifier[]::new)));
                    sim.applyUpdate(Attributes.MOVEMENT_SPEED, base, modifiers);
                }
                assertEquals(Double.doubleToRawLongBits(sim.movementSpeed.value()), Double.doubleToRawLongBits(view.value(Attributes.MOVEMENT_SPEED)),
                    "run " + run + " step " + step);
            }
        }
    }
}
