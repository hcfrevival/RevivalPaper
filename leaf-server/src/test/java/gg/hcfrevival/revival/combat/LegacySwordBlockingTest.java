package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.RevivalSettings;
import gg.hcfrevival.revival.config.SwordBlockingSettings;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.UseEffects;
import org.junit.jupiter.api.Test;

/** 1.7.10 EntityPlayer.damageEntity: a blocked hit takes {@code (1 + damage) × 0.5}, before armor. */
@RevivalTest
@RevivalRegistries
class LegacySwordBlockingTest {

    private static final SwordBlockingSettings DEFAULTS = RevivalSettings.DEFAULTS.swordBlocking();

    @Test
    void blockedHitTakesHalfOfDamagePlusOne() {
        assertEquals(8.0F - 4.5F, LegacySwordBlocking.blockedAmount(8.0F, DEFAULTS)); // a blocked 8 takes 4.5
        assertEquals(7.0F - 4.0F, LegacySwordBlocking.blockedAmount(7.0F, DEFAULTS));
        assertEquals(10.0F - 5.5F, LegacySwordBlocking.blockedAmount(10.0F, DEFAULTS));
    }

    @Test
    void defaultsMatchOneSevenTen() {
        assertTrue(DEFAULTS.enabled());
        assertTrue(DEFAULTS.clientDecoration());
        assertEquals(1.0, DEFAULTS.damageOffset());
        assertEquals(0.5, DEFAULTS.damageFactor());
        assertTrue(DEFAULTS.ignoreArmorBypassingDamage());
        assertEquals(0.2, DEFAULTS.useSpeedMultiplier());
    }

    @Test
    void swordsBlockAndOtherItemsDont() {
        assertTrue(LegacySwordBlocking.isBlockingWeapon(new ItemStack(Items.DIAMOND_SWORD)));
        assertTrue(LegacySwordBlocking.isBlockingWeapon(new ItemStack(Items.WOODEN_SWORD)));
        assertFalse(LegacySwordBlocking.isBlockingWeapon(new ItemStack(Items.DIAMOND_AXE)));
        assertFalse(LegacySwordBlocking.isBlockingWeapon(ItemStack.EMPTY));
    }

    @Test
    void clientsGetTheDecorationAndItComesOffAgain() {
        final ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
        final DataComponentPatch server = sword.getComponentsPatch();

        final DataComponentPatch client = LegacySwordBlocking.toClient(sword, server);

        assertEquals(LegacySwordBlocking.DECORATION, client.get(DataComponentMap.EMPTY, DataComponents.BLOCKS_ATTACKS));
        assertEquals(Component.literal("Excalibur"), client.get(DataComponentMap.EMPTY, DataComponents.CUSTOM_NAME));
        assertEquals(null, client.get(DataComponentMap.EMPTY, DataComponents.USE_EFFECTS)); // 0.2 is already the default
        assertEquals(server, LegacySwordBlocking.fromClient(client));
        assertFalse(sword.has(DataComponents.BLOCKS_ATTACKS)); // the server's own copy never blocks like a shield
    }

    @Test
    void otherItemsAndRealShieldsAreLeftAlone() {
        final ItemStack axe = new ItemStack(Items.DIAMOND_AXE);
        assertSame(axe.getComponentsPatch(), LegacySwordBlocking.toClient(axe, axe.getComponentsPatch()));
        final ItemStack shield = new ItemStack(Items.SHIELD);
        assertSame(shield.getComponentsPatch(), LegacySwordBlocking.toClient(shield, shield.getComponentsPatch()));
        final DataComponentPatch notOurs = DataComponentPatch.builder().set(DataComponents.USE_EFFECTS, new UseEffects(true, true, 1.0F)).build();
        assertSame(notOurs, LegacySwordBlocking.fromClient(notOurs));
    }
}
