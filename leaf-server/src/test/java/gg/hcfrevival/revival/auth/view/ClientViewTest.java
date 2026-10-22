package gg.hcfrevival.revival.auth.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.combat.LegacySwordBlocking;
import java.lang.reflect.Field;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/** The client view's fixed facts and its use prediction (report 02 §2.4, §3.12). */
@RevivalTest
@RevivalRegistries
class ClientViewTest {

    private static int id(final Class<?> owner, final String field) throws ReflectiveOperationException {
        final Field accessor = owner.getDeclaredField(field);
        accessor.setAccessible(true);
        return ((EntityDataAccessor<?>) accessor.get(null)).id();
    }

    @Test
    void entityDataIdsMatchTheAccessors() throws ReflectiveOperationException {
        assertEquals(id(Entity.class, "DATA_SHARED_FLAGS_ID"), ClientView.DATA_SHARED_FLAGS);
        assertEquals(id(Entity.class, "DATA_NO_GRAVITY"), ClientView.DATA_NO_GRAVITY);
        assertEquals(id(Entity.class, "DATA_POSE"), ClientView.DATA_POSE);
        assertEquals(id(LivingEntity.class, "DATA_LIVING_ENTITY_FLAGS"), ClientView.DATA_LIVING_ENTITY_FLAGS);
    }

    @Test
    void theEdibleSwordIsUsedFoodOnlyWhenHungry() {
        final ClientView view = new ClientView();
        final ItemStack sword = ClientItems.toClient(new ItemStack(Items.DIAMOND_SWORD));
        assertTrue(sword.has(DataComponents.CONSUMABLE), "the client copy carries the decoration (" + LegacySwordBlocking.class.getSimpleName() + ")");
        assertEquals(Boolean.TRUE, view.predictsUse(sword));
        assertEquals(Boolean.FALSE, view.predictsUse(new ItemStack(Items.COOKED_BEEF)), "food 20: can't eat");
        assertEquals(Boolean.TRUE, view.predictsUse(new ItemStack(Items.GOLDEN_APPLE)), "always edible");
        assertEquals(Boolean.TRUE, view.predictsUse(new ItemStack(Items.SHIELD)));
        assertEquals(Boolean.FALSE, view.predictsUse(new ItemStack(Items.STICK)));
        assertEquals(null, view.predictsUse(new ItemStack(Items.BOW)), "BowItem overrides use: the server's result stands in");
    }

    @Test
    void cooldownsBlockUseUntilTheyRunOut() {
        final ClientView view = new ClientView();
        final ItemStack pearl = new ItemStack(Items.SHIELD);
        view.cooldown(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.SHIELD), 2);
        assertTrue(view.onCooldown(pearl));
        view.tick();
        assertTrue(view.onCooldown(pearl));
        view.tick();
        assertFalse(view.onCooldown(pearl));
    }

    @Test
    void inventoryMenuSlotsMapToInventoryIndices() {
        assertEquals(39, ClientItems.menuToInventory(5), "head");
        assertEquals(36, ClientItems.menuToInventory(8), "feet");
        assertEquals(0, ClientItems.menuToInventory(36), "first hotbar slot");
        assertEquals(ClientItems.OFFHAND, ClientItems.menuToInventory(45));
        assertEquals(-1, ClientItems.menuToInventory(0), "crafting result");
    }
}
