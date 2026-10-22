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
 *       so only server-owned bits (fire, invisible, glowing) change. A field the server changed on purpose (§15.4: a
 *       plugin, a command, a shield disabled by an axe) is "meant" and keeps the server's value, so the change reaches
 *       the client in this transaction and the simulation takes it at the ack tick;</li>
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

    private SelfEcho() {
    }

    /**
     * The echo to send instead, the same packet when nothing changes, or null to send nothing.
     *
     * @param sprinting the client's sprint state now (the simulation's)
     * @param swimming the client's swim flag now
     * @param gliding the client's fall-flying flag now
     * @param meant client-owned fields the server changed on purpose ({@link #SPRINT}...): those keep the server's value
     */
    public static @Nullable ClientboundSetEntityDataPacket entityData(final ClientboundSetEntityDataPacket packet, final boolean sprinting, final boolean swimming,
                                                                      final boolean gliding, final int meant) {
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
                    int merged = server;
                    merged = bit(merged, 3, (meant & SPRINT) != 0 ? (server & 1 << 3) != 0 : sprinting);
                    merged = bit(merged, 4, (meant & SWIM) != 0 ? (server & 1 << 4) != 0 : swimming);
                    merged = bit(merged, 7, (meant & GLIDE) != 0 ? (server & 1 << 7) != 0 : gliding);
                    replacement = (byte) merged == server ? value : new SynchedEntityData.DataValue<>(value.id(), EntityDataSerializers.BYTE, (byte) merged);
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
