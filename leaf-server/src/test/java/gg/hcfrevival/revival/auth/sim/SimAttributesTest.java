package gg.hcfrevival.revival.auth.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.jupiter.api.Test;

/**
 * Client attribute semantics (AttributeInstance.java:20-214): base, then ADD_VALUE, ADD_MULTIPLIED_BASE on the summed
 * base, ADD_MULTIPLIED_TOTAL as a product, clamped to the vanilla range; the attribute packet replaces every modifier,
 * the client's own sprint modifier included.
 */
@RevivalTest
@RevivalRegistries
class SimAttributesTest {

    private static AttributeModifier mod(final String id, final double amount, final AttributeModifier.Operation op) {
        return new AttributeModifier(Identifier.withDefaultNamespace(id), amount, op);
    }

    @Test
    void operationsApplyInVanillaOrder() {
        final SimAttributes a = new SimAttributes();
        a.applyUpdate(Attributes.MOVEMENT_SPEED, 0.1, List.of(
            mod("speed_effect", 0.4, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
            mod("boots", 0.05, AttributeModifier.Operation.ADD_VALUE),
            mod("base_bonus", 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)));
        final double base = 0.1 + 0.05;
        assertEquals((base + base * 0.5) * (1.0 + 0.4), a.movementSpeed.value());
    }

    @Test
    void sprintModifierComesAndGoes() {
        final SimPlayer p = new SimPlayer();
        MovementSim.setSprinting(p, true);
        assertEquals(0.10000000149011612 * (1.0 + 0.30000001192092896), p.attributes.movementSpeed.value());
        MovementSim.setSprinting(p, true); // removed and re-added: no duplicate
        MovementSim.setSprinting(p, false);
        assertEquals(0.10000000149011612, p.attributes.movementSpeed.value());
        assertNull(p.attributes.movementSpeed.modifier(SimAttributes.SPRINTING.id()));
    }

    @Test
    void attributePacketReplacesTheClientsSprintModifier() {
        final SimPlayer p = new SimPlayer();
        MovementSim.setSprinting(p, true);
        // ClientPacketListener.handleUpdateAttributes: setBaseValue, removeModifiers, add the packet's modifiers
        p.attributes.applyUpdate(Attributes.MOVEMENT_SPEED, 0.10000000149011612, List.of());
        assertEquals(0.10000000149011612, p.attributes.movementSpeed.value());
        p.attributes.applyUpdate(Attributes.MOVEMENT_SPEED, 0.10000000149011612, List.of(SimAttributes.SPRINTING));
        assertEquals(0.10000000149011612 * (1.0 + 0.30000001192092896), p.attributes.movementSpeed.value());
    }

    @Test
    void valuesAreClampedToTheClientsRange() {
        final SimAttributes a = new SimAttributes();
        a.applyUpdate(Attributes.SCALE, 100.0, List.of());
        assertEquals(16.0, a.scale.value());
        a.applyUpdate(Attributes.GRAVITY, Double.NaN, List.of());
        assertEquals(-1.0, a.gravity.value()); // NaN → min (RangedAttribute.sanitizeValue)
    }

    @Test
    void duplicateModifiersThrowLikeTheClient() {
        final SimAttributes a = new SimAttributes();
        assertThrows(IllegalArgumentException.class, () -> a.applyUpdate(Attributes.MOVEMENT_SPEED, 0.1,
            List.of(mod("x", 0.1, AttributeModifier.Operation.ADD_VALUE), mod("x", 0.2, AttributeModifier.Operation.ADD_VALUE))));
    }
    @Test
    void aCopyKeepsTheValueAndTheModifierOrder() {
        // several ADD_MULTIPLIED_TOTAL modifiers multiply in the per-operation hash map's iteration order; a fork of the
        // state (SimPlayer.copyFrom, every tick for the reconciler) must keep that order, and must keep it as both change
        final SimAttributes a = new SimAttributes();
        final SimAttributes b = new SimAttributes();
        a.applyUpdate(Attributes.MOVEMENT_SPEED, 0.1, List.of(
            mod("slowness", -0.15, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
            mod("speed", 0.4, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
            mod("armor", 0.0731, AttributeModifier.Operation.ADD_VALUE)));
        final SimPlayer p = new SimPlayer();
        p.attributes.copyFrom(a);
        MovementSim.setSprinting(p, true);
        b.copyFrom(p.attributes);
        assertEquals(Double.doubleToRawLongBits(p.attributes.movementSpeed.value()), Double.doubleToRawLongBits(b.movementSpeed.value()));
        for (final SimAttributes x : List.of(p.attributes, b)) {
            x.movementSpeed.addTransientModifier(mod("swiftness", 0.2, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            x.movementSpeed.removeModifier(Identifier.withDefaultNamespace("slowness"));
        }
        assertEquals(Double.doubleToRawLongBits(p.attributes.movementSpeed.value()), Double.doubleToRawLongBits(b.movementSpeed.value()));
        // copying an empty map over a used one, and back
        b.copyFrom(new SimAttributes());
        assertEquals(0.10000000149011612, b.movementSpeed.value());
        b.copyFrom(p.attributes);
        assertEquals(Double.doubleToRawLongBits(p.attributes.movementSpeed.value()), Double.doubleToRawLongBits(b.movementSpeed.value()));
        b.movementSpeed.removeModifiers();
        assertEquals(0.1, b.movementSpeed.value());
    }
}
