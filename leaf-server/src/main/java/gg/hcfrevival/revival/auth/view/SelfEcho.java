package gg.hcfrevival.revival.auth.view;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jspecify.annotations.Nullable;

/**
 * The self-echo policy in enforce mode (design §3.3, report 02 H4/H5). The server echoes the player's own entity data
 * and attributes back to them; each echo would change the client's movement state at drain time. For players under
 * authority the echo is made to say what the client already has:
 * <ul>
 *   <li>{@code SetEntityData}: the pose and the living-entity using-item flags are client-owned and dropped; in the
 *       shared flags byte the client-owned bits (sprint 3, swim 4, fall-flying 7) are set to the client's current values
 *       so only server-owned bits (fire, invisible, glowing) change, and a byte whose server-owned bits are the ones
 *       last sent isn't sent at all. A field the server changed on purpose (§15.4: a plugin, a command, a shield
 *       disabled by an axe) is "meant" and keeps the server's value, so the change reaches the client in this
 *       transaction and the simulation takes it at the ack tick;</li>
 *   <li>{@code UpdateAttributes}: the {@code sprinting} modifier on {@code movement_speed} is present iff the client is
 *       sprinting, so {@code removeModifiers} and the re-add leave the client's speed as it was.</li>
 * </ul>
 * Whatever goes out is then logged and modelled at its ack tick like any other packet.
 */
public final class SelfEcho {

    // Client-owned fields the server may mean to change (AuthPlayer marks them; the echo then carries the server's value)
    public static final int SPRINT = 1;
    public static final int SWIM = 2;
    public static final int GLIDE = 4;
    public static final int POSE = 8;
    public static final int USE = 16;

    /**
     * The shared-flags bits the server owns: on fire (0), the unused 2, invisible (5), glowing (6). The rest are the
     * client's: sprint (3), swim (4), fall-flying (7), and the shift key (1), which LocalPlayer reads from its own input.
     */
    public static final int SERVER_FLAGS = 1 | 1 << 2 | 1 << 5 | 1 << 6;

    private SelfEcho() {
    }

    /**
     * The echo to send instead, the same packet when nothing changes, or null to send nothing.
     *
     * @param sprinting the client's sprint state now (the simulation's)
     * @param swimming the client's swim flag now
     * @param gliding the client's fall-flying flag now
     * @param meant client-owned fields the server changed on purpose ({@link #SPRINT}...): those keep the server's value
     * @param sentServerFlags the {@link #SERVER_FLAGS} bits of the last shared-flags byte sent to this client, -1 when
     *                        unknown: a byte with the same ones and no meant sprint, swim or glide is left out
     */
    public static @Nullable ClientboundSetEntityDataPacket entityData(final ClientboundSetEntityDataPacket packet, final boolean sprinting, final boolean swimming,
                                                                      final boolean gliding, final int meant, final int sentServerFlags) {
        List<SynchedEntityData.DataValue<?>> kept = null;
        final List<SynchedEntityData.DataValue<?>> values = packet.packedItems();
        for (int i = 0; i < values.size(); i++) {
            final SynchedEntityData.DataValue<?> value = values.get(i);
            final SynchedEntityData.DataValue<?> replacement;
            switch (value.id()) {
                case ClientView.DATA_POSE -> replacement = (meant & POSE) != 0 ? value : null;
                case ClientView.DATA_LIVING_ENTITY_FLAGS -> replacement = (meant & USE) != 0 ? value : null;
                case ClientView.DATA_SHARED_FLAGS -> {
                    final byte server = (Byte) value.value();
                    if ((meant & (SPRINT | SWIM | GLIDE)) == 0 && (server & SERVER_FLAGS) == sentServerFlags) {
                        // Only client-owned bits changed, and the client made those changes itself: a copy that lands
                        // after its next one undoes it (real client at 300 ms: a re-sprint undone in the tick of the next
                        // block-hit, so the hit went out unarmed, the client copying any sync like MC-69459)
                        replacement = null;
                    } else {
                        int merged = server;
                        merged = bit(merged, 3, (meant & SPRINT) != 0 ? (server & 1 << 3) != 0 : sprinting);
                        merged = bit(merged, 4, (meant & SWIM) != 0 ? (server & 1 << 4) != 0 : swimming);
                        merged = bit(merged, 7, (meant & GLIDE) != 0 ? (server & 1 << 7) != 0 : gliding);
                        replacement = (byte) merged == server ? value : new SynchedEntityData.DataValue<>(value.id(), EntityDataSerializers.BYTE, (byte) merged);
                    }
                }
                default -> replacement = value;
            }
            if (replacement != value && kept == null) {
                kept = new ArrayList<>(values.size());
                for (int j = 0; j < i; j++) {
                    kept.add(values.get(j));
                }
            }
            if (kept != null && replacement != null) {
                kept.add(replacement);
            }
        }
        if (kept == null) {
            return packet;
        }
        return kept.isEmpty() ? null : new ClientboundSetEntityDataPacket(packet.id(), kept);
    }

    private static int bit(final int flags, final int bit, final boolean on) {
        return on ? flags | 1 << bit : flags & ~(1 << bit);
    }

    /** The shared-flags byte a self entity-data packet carries (0-255), or -1 when it carries none. */
    public static int sharedFlags(final ClientboundSetEntityDataPacket packet) {
        final List<SynchedEntityData.DataValue<?>> values = packet.packedItems();
        for (int i = 0; i < values.size(); i++) {
            final SynchedEntityData.DataValue<?> value = values.get(i);
            if (value.id() == ClientView.DATA_SHARED_FLAGS && value.value() instanceof final Byte flags) {
                return flags & 0xFF;
            }
        }
        return -1;
    }

    /**
     * Whether an attribute sync carries {@code movement_speed}: -1 when it doesn't, else 1 with the sprinting modifier and
     * 0 without.
     */
    public static int speedSync(final ClientboundUpdateAttributesPacket packet) {
        for (final ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : packet.getValues()) {
            if (snapshot.attribute().equals(Attributes.MOVEMENT_SPEED)) {
                for (final AttributeModifier modifier : snapshot.modifiers()) {
                    if (modifier.id().equals(ClientAttributes.SPRINTING_MODIFIER_ID)) {
                        return 1;
                    }
                }
                return 0;
            }
        }
        return -1;
    }

    /**
     * The attribute echo without a movement-speed snapshot that differs from what the client holds ({@code client}) only by
     * the sprint modifier: the server's own copy toggled it after the client did (the truth writer follows the simulation),
     * so the sync says nothing new, and the client copies whatever a sync says (MC-69459). At 250 ms it landed after the
     * next block-hit had stopped the sprint again and left the client blocking at sprint speed while the truth walked
     * (real client 2026-10-22: a 0.00588 gap a cycle, then a correction chain). Null when nothing is left to send.
     */
    public static @org.jspecify.annotations.Nullable ClientboundUpdateAttributesPacket withoutSprintOnly(final ClientboundUpdateAttributesPacket packet,
                                                                                                        final ClientAttributes client) {
        final List<ClientboundUpdateAttributesPacket.AttributeSnapshot> snapshots = packet.getValues();
        for (int i = 0; i < snapshots.size(); i++) {
            final ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot = snapshots.get(i);
            if (!snapshot.attribute().equals(Attributes.MOVEMENT_SPEED)) {
                continue;
            }
            final ClientAttributes.Instance held = client.instance(Attributes.MOVEMENT_SPEED);
            if (held == null || Double.doubleToLongBits(held.getBaseValue()) != Double.doubleToLongBits(snapshot.base())
                || !sameButSprint(held.getModifiers(), snapshot.modifiers())) {
                return packet;
            }
            if (snapshots.size() == 1) {
                return null;
            }
            final List<ClientboundUpdateAttributesPacket.AttributeSnapshot> rest = new ArrayList<>(snapshots);
            rest.remove(i);
            return new ClientboundUpdateAttributesPacket(packet.getEntityId(), rest);
        }
        return packet;
    }

    // The two modifier sets are equal once the sprint modifier is left out of both
    private static boolean sameButSprint(final java.util.Set<AttributeModifier> held, final java.util.Collection<AttributeModifier> sent) {
        int heldOthers = 0;
        for (final AttributeModifier modifier : held) {
            if (!modifier.id().equals(ClientAttributes.SPRINTING_MODIFIER_ID)) {
                heldOthers++;
            }
        }
        int sentOthers = 0;
        for (final AttributeModifier modifier : sent) {
            if (modifier.id().equals(ClientAttributes.SPRINTING_MODIFIER_ID)) {
                continue;
            }
            sentOthers++;
            if (!held.contains(modifier)) {
                return false;
            }
        }
        return heldOthers == sentOthers;
    }

    /** The attribute echo with the client's own sprint modifier state. */
    public static ClientboundUpdateAttributesPacket attributes(final ClientboundUpdateAttributesPacket packet, final boolean sprinting) {
        final List<ClientboundUpdateAttributesPacket.AttributeSnapshot> snapshots = packet.getValues();
        for (int i = 0; i < snapshots.size(); i++) {
            final ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot = snapshots.get(i);
            if (!snapshot.attribute().equals(Attributes.MOVEMENT_SPEED)) {
                continue;
            }
            boolean has = false;
            for (final AttributeModifier modifier : snapshot.modifiers()) {
                has |= modifier.id().equals(ClientAttributes.SPRINTING_MODIFIER_ID);
            }
            if (has == sprinting) {
                return packet;
            }
            final List<AttributeModifier> modifiers = new ArrayList<>(snapshot.modifiers().size() + 1);
            for (final AttributeModifier modifier : snapshot.modifiers()) {
                if (!modifier.id().equals(ClientAttributes.SPRINTING_MODIFIER_ID)) {
                    modifiers.add(modifier);
                }
            }
            if (sprinting) {
                modifiers.add(ClientAttributes.SPEED_MODIFIER_SPRINTING);
            }
            final List<ClientboundUpdateAttributesPacket.AttributeSnapshot> rewritten = new ArrayList<>(snapshots);
            rewritten.set(i, new ClientboundUpdateAttributesPacket.AttributeSnapshot(snapshot.attribute(), snapshot.base(), modifiers));
            return new ClientboundUpdateAttributesPacket(packet.getEntityId(), rewritten);
        }
        return packet;
    }
}
