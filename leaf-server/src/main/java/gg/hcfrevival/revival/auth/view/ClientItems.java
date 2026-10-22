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
        this.setInventoryClient(index, toClient(server));
    }

    /** As {@link #setInventory}, with the client's copy already made (a §15.6 snapshot). */
    public void setInventoryClient(final int index, final ItemStack client) {
        if (index >= 0 && index < SIZE) {
            this.stacks[index] = client;
            this.changes++;
        }
    }

    /** {@code ContainerSetSlot} for the inventory menu (container 0): a menu slot. */
    public void setMenuSlot(final int menuSlot, final ItemStack server) {
        this.setMenuSlotClient(menuSlot, toClient(server));
    }

    public void setMenuSlotClient(final int menuSlot, final ItemStack client) {
        final int index = menuToInventory(menuSlot);
        if (index >= 0) {
            this.setInventoryClient(index, client);
        }
    }

    /** {@code ContainerSetContent} for the inventory menu. */
    public void setMenuContent(final List<ItemStack> items) {
        final ItemStack[] client = new ItemStack[items.size()];
        for (int slot = 0; slot < client.length; slot++) {
            client[slot] = toClient(items.get(slot));
        }
        this.setMenuContentClient(client);
    }

    public void setMenuContentClient(final ItemStack[] client) {
        for (int slot = 0; slot < client.length; slot++) {
            final int index = menuToInventory(slot);
            if (index >= 0) {
                this.stacks[index] = client[slot];
            }
        }
        this.changes++;
    }

    /** {@code SetEquipment} for the player itself. */
    public void setEquipment(final EquipmentSlot slot, final ItemStack server) {
        this.setEquipmentClient(slot, toClient(server));
    }

    public void setEquipmentClient(final EquipmentSlot slot, final ItemStack client) {
        switch (slot) {
            case MAINHAND -> this.setInventoryClient(this.selected, client);
            case OFFHAND -> this.setInventoryClient(OFFHAND, client);
            default -> this.setInventoryClient(slot.getIndex(Inventory.INVENTORY_SIZE), client);
        }
    }

    /**
     * The client's copies of an inventory packet's stacks, made when the packet is logged (§15.6), so the ack applies
     * what was encoded even if the decoration settings changed in between; null for packets that carry none.
     */
    public static ItemStack @org.jspecify.annotations.Nullable [] snapshot(final net.minecraft.network.protocol.Packet<?> packet) {
        return switch (packet) {
            case final net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket slot -> new ItemStack[]{toClient(slot.contents())};
            case final net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket slot -> new ItemStack[]{toClient(slot.getItem())};
            case final net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket content -> {
                final ItemStack[] client = new ItemStack[content.items().size()];
                for (int i = 0; i < client.length; i++) {
                    client[i] = toClient(content.items().get(i));
                }
                yield client;
            }
            case final net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket equipment -> {
                final ItemStack[] client = new ItemStack[equipment.getSlots().size()];
                for (int i = 0; i < client.length; i++) {
                    client[i] = toClient(equipment.getSlots().get(i).getSecond());
                }
                yield client;
            }
            default -> null;
        };
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
