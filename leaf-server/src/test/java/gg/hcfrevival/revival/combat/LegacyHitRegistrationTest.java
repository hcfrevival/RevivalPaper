package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.jupiter.api.Test;

@RevivalTest
@RevivalRegistries
class LegacyHitRegistrationTest {

    @Test
    void capCountsAttacksPerTick() {
        final LegacyHitRegistration registration = new LegacyHitRegistration();
        assertTrue(registration.allow(10, 2));
        assertTrue(registration.allow(10, 2));
        assertFalse(registration.allow(10, 2));
        assertTrue(registration.allow(11, 2)); // a new tick starts over
    }

    @Test
    void zeroCapIsUnlimited() {
        final LegacyHitRegistration registration = new LegacyHitRegistration();
        for (int i = 0; i < 100; i++) {
            assertTrue(registration.allow(10, 0));
        }
    }

    @Test
    void modifierRemovesTheSwordCooldownAndComesOffAgain() {
        final AttributeInstance speed = new AttributeInstance(Attributes.ATTACK_SPEED, instance -> { });
        speed.setBaseValue(4.0);
        speed.addTransientModifier(new AttributeModifier(Identifier.withDefaultNamespace("base_attack_speed"), -2.4, AttributeModifier.Operation.ADD_VALUE));
        assertEquals(1.6, speed.getValue(), 1.0E-9); // a sword: 12.5 ticks to full strength

        LegacyHitRegistration.refresh(speed, true);
        LegacyHitRegistration.refresh(speed, true); // idempotent
        assertEquals(1024.0, speed.getValue()); // capped; under 0.02 ticks to full strength, so the bar never shows
        assertTrue(20.0 / speed.getValue() < 0.5);

        LegacyHitRegistration.refresh(speed, false);
        assertEquals(1.6, speed.getValue(), 1.0E-9);
    }
}
