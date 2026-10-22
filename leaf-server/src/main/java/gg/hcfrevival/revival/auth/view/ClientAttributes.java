package gg.hcfrevival.revival.auth.view;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import org.jspecify.annotations.Nullable;

/**
 * The local player's attributes exactly as a vanilla 26.3 client holds them (report 01 §7.1, report 02 §3.6).
 * <p>
 * The server's own {@code AttributeInstance} is not a stand-in: Leaf's {@code 0149-optimize-attribute} changes its maps to
 * insertion-ordered array maps, while the client keeps one {@code Object2ObjectOpenHashMap} per operation. With several
 * {@code ADD_MULTIPLIED_TOTAL} modifiers (sprint ×1.3, Speed, Slowness) the product order is the hash map's iteration
 * order, which depends on the whole add/remove history. {@link Instance} is vanilla 26.3's {@code AttributeInstance}
 * (client-reachable parts) with the same map types and the same operations, so it iterates in the same order.
 * <p>
 * The client's history is: {@code handleUpdateAttributes} → {@code setBaseValue}, {@code removeModifiers()},
 * {@code addTransientModifier} per modifier in packet order; and its own {@code LivingEntity.setSprinting}, which removes
 * and re-adds the {@code sprinting} modifier on {@code movement_speed}.
 */
public final class ClientAttributes {

    /** {@code LivingEntity.SPRINTING_MODIFIER_ID}. */
    public static final Identifier SPRINTING_MODIFIER_ID = Identifier.withDefaultNamespace("sprinting");
    /** {@code LivingEntity.SPEED_MODIFIER_SPRINTING}: 0.3F widened, multiply total. */
    public static final AttributeModifier SPEED_MODIFIER_SPRINTING =
        new AttributeModifier(SPRINTING_MODIFIER_ID, 0.30000001192092896, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    private final Reference2ObjectOpenHashMap<Holder<Attribute>, Instance> instances = new Reference2ObjectOpenHashMap<>();
    private final AttributeSupplier supplier;
    private int changes;

    public ClientAttributes() {
        this(DefaultAttributes.getSupplier(net.minecraft.world.entity.EntityTypes.PLAYER));
    }

    ClientAttributes(final AttributeSupplier supplier) {
        this.supplier = supplier;
    }

    /** The client's value, or the attribute's default when the client has no instance for it. */
    public double value(final Holder<Attribute> attribute) {
        final Instance instance = this.instance(attribute);
        return instance != null ? instance.getValue() : attribute.value().getDefaultValue();
    }

    /** Counts every change, so a reader can cache derived values until the next one. */
    public int changes() {
        return this.changes;
    }

    /**
     * {@code AttributeMap.getInstance} on the client: created on first use from the player's default supplier, or null
     * when the player type has no such attribute.
     */
    public @Nullable Instance instance(final Holder<Attribute> attribute) {
        Instance instance = this.instances.get(attribute);
        if (instance == null && this.supplier.hasAttribute(attribute)) {
            instance = new Instance(attribute, this);
            final AttributeInstance template = this.supplier.createInstance(ignored -> {
            }, attribute);
            if (template != null) {
                instance.replaceFrom(template);
            }
            this.instances.put(attribute, instance);
        }
        return instance;
    }

    /** {@code ClientPacketListener.handleUpdateAttributes} for the local player. */
    public void apply(final List<ClientboundUpdateAttributesPacket.AttributeSnapshot> snapshots) {
        for (final ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : snapshots) {
            final Instance instance = this.instance(snapshot.attribute());
            if (instance == null) {
                continue; // the client logs "does not have attribute" and skips it
            }
            instance.setBaseValue(snapshot.base());
            instance.removeModifiers();
            for (final AttributeModifier modifier : snapshot.modifiers()) {
                instance.addTransientModifier(modifier);
            }
        }
    }

    /** {@code LivingEntity.setSprinting} on the client: the local sprint modifier. */
    public void setSprinting(final boolean sprinting) {
        final Instance speed = this.instance(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(SPRINTING_MODIFIER_ID);
        if (sprinting) {
            speed.addTransientModifier(SPEED_MODIFIER_SPRINTING);
        }
    }

    /**
     * Starts from the server's current attributes, as if the client had just been sent all of them (authority turned on
     * mid-session, or a resync after the transaction log was lost). Same order as an update packet would carry.
     */
    public void seed(final AttributeMap server) {
        this.instances.clear();
        for (final AttributeInstance attribute : server.getSyncableAttributes()) {
            final Instance instance = this.instance(attribute.getAttribute());
            if (instance == null) {
                continue;
            }
            instance.setBaseValue(attribute.getBaseValue());
            instance.removeModifiers();
            for (final AttributeModifier modifier : attribute.getModifiers()) {
                instance.addTransientModifier(modifier);
            }
        }
        this.changes++;
    }

    /**
     * {@code handleRespawn}: the new local player gets a fresh attribute map, with every modifier copied when the
     * respawn keeps attribute modifiers ({@code AttributeMap.assignAllValues}) and only base values otherwise
     * ({@code assignBaseValues}).
     */
    public void respawn(final boolean keepModifiers) {
        final java.util.ArrayList<Instance> old = new java.util.ArrayList<>(this.instances.values());
        this.instances.clear();
        for (final Instance previous : old) {
            final Instance fresh = this.instance(previous.getAttribute());
            if (fresh == null) {
                continue;
            }
            if (keepModifiers) {
                fresh.replaceFrom(previous);
            } else {
                fresh.setBaseValue(previous.getBaseValue());
            }
        }
        this.changes++;
    }

    /** Vanilla 26.3 {@code AttributeInstance}, the parts the client reaches, with the same maps and the same operations. */
    public static final class Instance {
        private final Holder<Attribute> attribute;
        private final Map<AttributeModifier.Operation, Map<Identifier, AttributeModifier>> modifiersByOperation = Maps.newEnumMap(AttributeModifier.Operation.class);
        private final Map<Identifier, AttributeModifier> modifierById = new Object2ObjectArrayMap<>();
        private final Map<Identifier, AttributeModifier> permanentModifiers = new Object2ObjectArrayMap<>();
        private final ClientAttributes owner;
        private double baseValue;
        private boolean dirty = true;
        private double cachedValue;

        Instance(final Holder<Attribute> attribute, final ClientAttributes owner) {
            this.attribute = attribute;
            this.owner = owner;
            this.baseValue = attribute.value().getDefaultValue();
        }

        public Holder<Attribute> getAttribute() {
            return this.attribute;
        }

        public double getBaseValue() {
            return this.baseValue;
        }

        public void setBaseValue(final double baseValue) {
            if (baseValue != this.baseValue) {
                this.baseValue = baseValue;
                this.setDirty();
            }
        }

        Map<Identifier, AttributeModifier> getModifiers(final AttributeModifier.Operation operation) {
            return this.modifiersByOperation.computeIfAbsent(operation, key -> new Object2ObjectOpenHashMap<>());
        }

        public java.util.Set<AttributeModifier> getModifiers() {
            return ImmutableSet.copyOf(this.modifierById.values());
        }

        public @Nullable AttributeModifier getModifier(final Identifier id) {
            return this.modifierById.get(id);
        }

        public boolean hasModifier(final Identifier id) {
            return this.modifierById.get(id) != null;
        }

        private void addModifier(final AttributeModifier modifier) {
            final AttributeModifier previous = this.modifierById.putIfAbsent(modifier.id(), modifier);
            if (previous != null) {
                throw new IllegalArgumentException("Modifier is already applied on this attribute!");
            }
            this.getModifiers(modifier.operation()).put(modifier.id(), modifier);
            this.setDirty();
        }

        public void addTransientModifier(final AttributeModifier modifier) {
            this.addModifier(modifier);
        }

        private void setDirty() {
            this.dirty = true;
            this.owner.changes++;
        }

        public void removeModifier(final AttributeModifier modifier) {
            this.removeModifier(modifier.id());
        }

        public boolean removeModifier(final Identifier id) {
            final AttributeModifier modifier = this.modifierById.remove(id);
            if (modifier == null) {
                return false;
            }
            this.getModifiers(modifier.operation()).remove(id);
            this.permanentModifiers.remove(id);
            this.setDirty();
            return true;
        }

        public void removeModifiers() {
            for (final AttributeModifier modifier : this.getModifiers()) {
                this.removeModifier(modifier);
            }
        }

        public double getValue() {
            if (this.dirty) {
                this.cachedValue = this.calculateValue();
                this.dirty = false;
            }
            return this.cachedValue;
        }

        private double calculateValue() {
            double base = this.getBaseValue();
            for (final AttributeModifier modifier : this.getModifiersOrEmpty(AttributeModifier.Operation.ADD_VALUE)) {
                base += modifier.amount();
            }
            double result = base;
            for (final AttributeModifier modifier : this.getModifiersOrEmpty(AttributeModifier.Operation.ADD_MULTIPLIED_BASE)) {
                result += base * modifier.amount();
            }
            for (final AttributeModifier modifier : this.getModifiersOrEmpty(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)) {
                result *= 1.0 + modifier.amount();
            }
            return this.attribute.value().sanitizeValue(result);
        }

        private Collection<AttributeModifier> getModifiersOrEmpty(final AttributeModifier.Operation operation) {
            return this.modifiersByOperation.getOrDefault(operation, Map.of()).values();
        }

        // AttributeInstance.replaceFrom between two client instances (respawn keeping modifiers)
        void replaceFrom(final Instance other) {
            this.baseValue = other.baseValue;
            this.modifierById.clear();
            this.modifierById.putAll(other.modifierById);
            this.permanentModifiers.clear();
            this.permanentModifiers.putAll(other.permanentModifiers);
            this.modifiersByOperation.clear();
            other.modifiersByOperation.forEach((operation, modifiers) -> this.getModifiers(operation).putAll(modifiers));
            this.setDirty();
        }

        // AttributeSupplier.createInstance copies its template this way; the client's first instance starts here
        void replaceFrom(final AttributeInstance template) {
            this.baseValue = template.getBaseValue();
            this.modifierById.clear();
            this.permanentModifiers.clear();
            this.modifiersByOperation.clear();
            for (final AttributeModifier modifier : template.getModifiers()) {
                this.modifierById.put(modifier.id(), modifier);
                this.getModifiers(modifier.operation()).put(modifier.id(), modifier);
            }
            for (final AttributeModifier modifier : template.getPermanentModifiers()) {
                this.permanentModifiers.put(modifier.id(), modifier);
            }
            this.setDirty();
        }
    }
}
