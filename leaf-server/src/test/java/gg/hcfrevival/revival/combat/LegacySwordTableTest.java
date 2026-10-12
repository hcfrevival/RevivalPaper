package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.LegacyCombatSettings;
import gg.hcfrevival.revival.config.RevivalSettings;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/** Fails when a Minecraft update adds a sword without a 1.7.10 damage entry. */
@RevivalTest
@RevivalRegistries
class LegacySwordTableTest {

    private static final LegacyCombatSettings DEFAULTS = RevivalSettings.DEFAULTS.legacyCombat();

    @Test
    void everySwordIsALegacyWeaponWithDamage() {
        final List<Item> swords = BuiltInRegistries.ITEM.stream().filter(item -> item.getDefaultInstance().is(ItemTags.SWORDS)).toList();

        assertFalse(swords.isEmpty());
        for (final Item sword : swords) {
            final Identifier id = BuiltInRegistries.ITEM.getKey(sword);
            assertTrue(DEFAULTS.weaponDamage().containsKey(id), id + " has no legacy-combat.weapon-damage entry");
        }
    }

    @Test
    void swordTable() {
        final Map<Item, Float> expected = Map.of(
            Items.WOODEN_SWORD, 5.0F,
            Items.GOLDEN_SWORD, 5.0F,
            Items.STONE_SWORD, 6.0F,
            Items.COPPER_SWORD, 6.0F,
            Items.IRON_SWORD, 7.0F,
            Items.DIAMOND_SWORD, 8.0F,
            Items.NETHERITE_SWORD, 9.0F
        );
        for (final Map.Entry<Item, Float> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), DEFAULTS.weaponDamage().get(BuiltInRegistries.ITEM.getKey(entry.getKey())), entry.getKey().toString());
        }
        assertEquals(expected.size(), DEFAULTS.weaponDamage().size());
    }
}
