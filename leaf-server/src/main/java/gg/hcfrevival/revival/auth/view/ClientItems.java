package gg.hcfrevival.revival.auth.view;

import gg.hcfrevival.revival.combat.LegacySwordBlocking;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The stacks the player's client holds in its own inventory, as the client decoded them (report 01 §7.2). Movement reads
 * the main hand (use effects, scaffolding), the offhand, the feet (powder snow) and any glider slot.
 * <p>
 * Stacks are kept as the client sees them: RevivalPaper's item codec sends legacy weapons with the edible-sword
 * decoration ({@link LegacySwordBlocking#toClient}), so a sword here carries the {@code CONSUMABLE} component the
 * server's own stack lacks, and the client's use prediction reads it.
 * <p>
 * Indices are {@link Inventory} indices: 0-8 hotbar, 9-35 main, 36-39 feet/legs/chest/head, 40 offhand, 41 body,
 * 42 saddle.
 */
public final class ClientItems {

    public static final int SIZE = 43;
    public static final int OFFHAND = 40;

    private final ItemStack[] stacks = new ItemStack[SIZE];
    private int selected;
    private int changes;

    public ClientItems() {
        java.util.Arrays.fill(this.stacks, ItemStack.EMPTY);
    }

    /** The client copy of a server stack: the same item, count and components, with the client decoration applied. */
    public static ItemStack toClient(final ItemStack server) {
        if (server.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(server.typeHolder(), server.getCount(), LegacySwordBlocking.toClient(server, server.getComponentsPatch()));
    }

    public int selected() {
        return this.selected;
    }

    public int changes() {
        return this.changes;
    }

    public ItemStack get(final int index) {
        return index >= 0 && index < SIZE ? this.stacks[index] : ItemStack.EMPTY;
    }

    public ItemStack mainHand() {
        return this.stacks[this.selected];
    }

    public ItemStack hand(final InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? this.mainHand() : this.stacks[OFFHAND];
    }

    public ItemStack equipment(final EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> this.mainHand();
            case OFFHAND -> this.stacks[OFFHAND];
            default -> this.get(slot.getIndex(Inventory.INVENTORY_SIZE));
        };
    }

    /** {@code SetHeldSlot}, or the client's own hotbar change ({@code SetCarriedItem}). */
    public void select(final int slot) {
        if (slot >= 0 && slot < Inventory.getSelectionSize() && slot != this.selected) {
            this.selected = slot;
            this.changes++;
        }
    }

    /** {@code SetPlayerInventory(slot, stack)}: an inventory index. */
    public void setInventory(final int index, final ItemStack server) {
        if (index >= 0 && index < SIZE) {
            this.stacks[index] = toClient(server);
            this.changes++;
        }
    }

    /** {@code ContainerSetSlot} for the inventory menu (container 0): a menu slot. */
    public void setMenuSlot(final int menuSlot, final ItemStack server) {
        final int index = menuToInventory(menuSlot);
        if (index >= 0) {
            this.setInventory(index, server);
        }
    }

    /** {@code ContainerSetContent} for the inventory menu. */
    public void setMenuContent(final List<ItemStack> items) {
        for (int slot = 0; slot < items.size(); slot++) {
            final int index = menuToInventory(slot);
            if (index >= 0) {
                this.stacks[index] = toClient(items.get(slot));
            }
        }
        this.changes++;
    }

    /** {@code SetEquipment} for the player itself. */
    public void setEquipment(final EquipmentSlot slot, final ItemStack server) {
        switch (slot) {
            case MAINHAND -> this.setInventory(this.selected, server);
            case OFFHAND -> this.setInventory(OFFHAND, server);
            default -> this.setInventory(slot.getIndex(Inventory.INVENTORY_SIZE), server);
        }
    }

    /** The client's drop prediction ({@code MultiPlayerGameMode.dropItem} → {@code removeFromSelected}). */
    public void dropSelected(final boolean all) {
        final ItemStack stack = this.mainHand();
        if (stack.isEmpty()) {
            return;
        }
        if (all || stack.getCount() <= 1) {
            this.stacks[this.selected] = ItemStack.EMPTY;
        } else {
            final ItemStack less = stack.copy();
            less.shrink(1);
            this.stacks[this.selected] = less;
        }
        this.changes++;
    }

    /**
     * Copies the server's inventory: used where the client changed its own inventory in a way the server confirms without
     * sending the slots back (container clicks carry hashed stacks), and when authority starts mid-session.
     */
    public void seed(final Inventory server) {
        for (int i = 0; i < SIZE && i < server.getContainerSize(); i++) {
            this.stacks[i] = toClient(server.getItem(i));
        }
        this.selected = server.getSelectedSlot();
        this.changes++;
    }

    // InventoryMenu: 5-8 head..feet, 9-35 main, 36-44 hotbar, 45 offhand
    static int menuToInventory(final int menuSlot) {
        if (menuSlot >= 5 && menuSlot <= 8) {
            return 39 - (menuSlot - 5);
        }
        if (menuSlot >= 9 && menuSlot <= 35) {
            return menuSlot;
        }
        if (menuSlot >= 36 && menuSlot <= 44) {
            return menuSlot - 36;
        }
        return menuSlot == 45 ? OFFHAND : -1;
    }
}
