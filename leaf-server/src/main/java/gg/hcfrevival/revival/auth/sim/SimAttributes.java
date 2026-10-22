package gg.hcfrevival.revival.auth.sim;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jspecify.annotations.Nullable;

/**
 * The attributes movement reads, with the vanilla 26.3 CLIENT's semantics: base value plus modifiers keyed by
 * {@link Identifier}, recomputed exactly like {@code AttributeInstance} (AttributeInstance.java:20-214).
 * <p>
 * Exactness details that matter:
 * <ul>
 *   <li>per-operation maps are real {@code Object2ObjectOpenHashMap}s and the id map a real
 *       {@code Object2ObjectArrayMap}, fed the same add/remove history as the client (its own sprint modifier,
 *       {@code ClientboundUpdateAttributesPacket}'s {@code setBaseValue}, {@code removeModifiers}, re-add in packet
 *       order), so the iteration order, and with it the product order of several {@code ADD_MULTIPLIED_TOTAL}
 *       modifiers (sprint ×1.3, Speed, Slowness), is the client's;</li>
 *   <li>values are clamped to the vanilla ranges hard-coded here (Attributes.java), not the server registry's, which
 *       Spigot lets {@code spigot.yml} change;</li>
 *   <li>Leaf's insertion-ordered attribute maps (patch 0149) are never used.</li>
 * </ul>
 * Attributes not listed here are ignored: no movement code reads them.
 */
public final class SimAttributes {

    /** LivingEntity.SPEED_MODIFIER_SPRINTING (LivingEntity.java:158-161). */
    public static final AttributeModifier SPRINTING = new AttributeModifier(Identifier.withDefaultNamespace("sprinting"), 0.30000001192092896,
        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    // Player.createAttributes (Player.java:205-218) over LivingEntity.createLivingAttributes, vanilla ranges
    public final Instance movementSpeed = new Instance(Attributes.MOVEMENT_SPEED, 0.10000000149011612, 0.0, 1024.0);
    public final Instance sneakingSpeed = new Instance(Attributes.SNEAKING_SPEED, 0.3, 0.0, 1.0);
    public final Instance jumpStrength = new Instance(Attributes.JUMP_STRENGTH, 0.41999998688697815, 0.0, 32.0);
    public final Instance stepHeight = new Instance(Attributes.STEP_HEIGHT, 0.6, 0.0, 10.0);
    public final Instance gravity = new Instance(Attributes.GRAVITY, 0.08, -1.0, 1.0);
    public final Instance scale = new Instance(Attributes.SCALE, 1.0, 0.0625, 16.0);
    public final Instance movementEfficiency = new Instance(Attributes.MOVEMENT_EFFICIENCY, 0.0, 0.0, 1.0);
    public final Instance waterMovementEfficiency = new Instance(Attributes.WATER_MOVEMENT_EFFICIENCY, 0.0, 0.0, 1.0);
    public final Instance frictionModifier = new Instance(Attributes.FRICTION_MODIFIER, 1.0, 0.0, 2048.0);
    public final Instance airDragModifier = new Instance(Attributes.AIR_DRAG_MODIFIER, 1.0, 0.0, 2048.0);
    public final Instance bounciness = new Instance(Attributes.BOUNCINESS, 0.0, 0.0, 1.0);
    public final Instance attackSpeed = new Instance(Attributes.ATTACK_SPEED, 4.0, 0.0, 1024.0);
    /** Not syncable: the client keeps the base. */
    public final Instance attackKnockback = new Instance(Attributes.ATTACK_KNOCKBACK, 0.0, 0.0, 5.0);
    public final Instance entityInteractionRange = new Instance(Attributes.ENTITY_INTERACTION_RANGE, 3.0, 0.0, 64.0);
    public final Instance blockInteractionRange = new Instance(Attributes.BLOCK_INTERACTION_RANGE, 4.5, 0.0, 64.0);

    private final Instance[] all = {
        this.movementSpeed, this.sneakingSpeed, this.jumpStrength, this.stepHeight, this.gravity, this.scale, this.movementEfficiency,
        this.waterMovementEfficiency, this.frictionModifier, this.airDragModifier, this.bounciness, this.attackSpeed, this.attackKnockback,
        this.entityInteractionRange, this.blockInteractionRange
    };

    /** The instance for {@code attribute}, or null when movement does not read it. */
    public @Nullable Instance get(final Holder<Attribute> attribute) {
        for (final Instance instance : this.all) {
            if (instance.attribute == attribute) {
                return instance;
            }
        }
        return null;
    }

    /**
     * One {@code AttributeSnapshot} of {@code ClientboundUpdateAttributesPacket} as ClientPacketListener applies it
     * (ClientPacketListener.java:2377-2389): {@code setBaseValue}, {@code removeModifiers}, then
     * {@code addTransientModifier} for each modifier in packet order. Unknown attributes are ignored.
     */
    public void applyUpdate(final Holder<Attribute> attribute, final double base, final List<AttributeModifier> modifiers) {
        final Instance instance = this.get(attribute);
        if (instance == null) {
            return;
        }
        instance.setBaseValue(base);
        instance.removeModifiers();
        for (final AttributeModifier modifier : modifiers) {
            instance.addTransientModifier(modifier);
        }
    }

    /** Copies another map's history-equivalent state (for forks of a simulation, e.g. the reconciler's belief). */
    public void copyFrom(final SimAttributes other) {
        for (int i = 0; i < this.all.length; i++) {
            this.all[i].copyFrom(other.all[i]);
        }
    }

    /** One attribute, vanilla {@code AttributeInstance} semantics with the client's ranges. */
    public static final class Instance {

        final Holder<Attribute> attribute;
        private final double minValue;
        private final double maxValue;
        private double baseValue;
        private final IdMap modifierById = new IdMap();
        @SuppressWarnings({"unchecked", "rawtypes"})
        private final OperationMap<Identifier, AttributeModifier>[] byOperation = new OperationMap[3];
        private boolean dirty = true;
        private double cachedValue;

        Instance(final Holder<Attribute> attribute, final double baseValue, final double minValue, final double maxValue) {
            this.attribute = attribute;
            this.baseValue = baseValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        public Holder<Attribute> attribute() {
            return this.attribute;
        }

        public double baseValue() {
            return this.baseValue;
        }

        /** AttributeInstance.setBaseValue (AttributeInstance.java:42-47). */
        public void setBaseValue(final double baseValue) {
            if (baseValue != this.baseValue) {
                this.baseValue = baseValue;
                this.dirty = true;
            }
        }

        /** AttributeInstance.getModifiers(operation) (AttributeInstance.java:53-55): created on first use, kept afterwards. */
        private OperationMap<Identifier, AttributeModifier> modifiers(final AttributeModifier.Operation operation) {
            OperationMap<Identifier, AttributeModifier> map = this.byOperation[operation.ordinal()];
            if (map == null) {
                map = new OperationMap<>();
                this.byOperation[operation.ordinal()] = map;
            }
            return map;
        }

        public @Nullable AttributeModifier modifier(final Identifier id) {
            return this.modifierById.get(id);
        }

        /** AttributeInstance.addModifier (AttributeInstance.java:72-80): a duplicate id throws, as on the client. */
        public void addTransientModifier(final AttributeModifier modifier) {
            final AttributeModifier previous = this.modifierById.putIfAbsent(modifier.id(), modifier);
            if (previous != null) {
                throw new IllegalArgumentException("Modifier is already applied on this attribute!");
            }
            this.modifiers(modifier.operation()).put(modifier.id(), modifier);
            this.dirty = true;
        }

        /** AttributeInstance.removeModifier(Identifier) (AttributeInstance.java:122-131). */
        public boolean removeModifier(final Identifier id) {
            final AttributeModifier modifier = this.modifierById.remove(id);
            if (modifier == null) {
                return false;
            }
            this.modifiers(modifier.operation()).remove(id);
            this.dirty = true;
            return true;
        }

        /**
         * AttributeInstance.removeModifiers (AttributeInstance.java:134-138): over a copy of the id map's values, in
         * insertion order. Removing the first entry until none is left is the same order without the copy.
         */
        public void removeModifiers() {
            while (!this.modifierById.isEmpty()) {
                this.removeModifier(this.modifierById.firstKey());
            }
        }

        /** AttributeInstance.getValue / calculateValue (AttributeInstance.java:140-167) with RangedAttribute.sanitizeValue. */
        public double value() {
            if (this.dirty) {
                this.cachedValue = this.calculateValue();
                this.dirty = false;
            }
            return this.cachedValue;
        }

        private double calculateValue() {
            double base = this.baseValue;
            final OperationMap<Identifier, AttributeModifier> add = this.byOperation[AttributeModifier.Operation.ADD_VALUE.ordinal()];
            if (add != null) {
                base = add.addAmounts(base);
            }
            double result = base;
            final OperationMap<Identifier, AttributeModifier> multBase = this.byOperation[AttributeModifier.Operation.ADD_MULTIPLIED_BASE.ordinal()];
            if (multBase != null) {
                result = multBase.addMultipliedBase(result, base);
            }
            final OperationMap<Identifier, AttributeModifier> multTotal = this.byOperation[AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL.ordinal()];
            if (multTotal != null) {
                result = multTotal.multiplyTotal(result);
            }
            // RangedAttribute.sanitizeValue (RangedAttribute.java:31-33)
            return Double.isNaN(result) ? this.minValue : (result < this.minValue ? this.minValue : Math.min(result, this.maxValue));
        }

        /** Takes over another instance's state and history, without allocating once the tables have their size. */
        void copyFrom(final Instance other) {
            this.baseValue = other.baseValue;
            this.modifierById.copyFrom(other.modifierById);
            for (int i = 0; i < 3; i++) {
                final OperationMap<Identifier, AttributeModifier> from = other.byOperation[i];
                final OperationMap<Identifier, AttributeModifier> to = this.byOperation[i];
                if (from == null) {
                    if (to != null && !to.resetToNew()) {
                        this.byOperation[i] = null;
                    }
                } else if (to == null) {
                    this.byOperation[i] = from.clone();
                } else {
                    to.copyLayout(from);
                }
            }
            this.dirty = other.dirty;
            this.cachedValue = other.cachedValue;
        }
    }

    /**
     * The id map ({@code Object2ObjectArrayMap}): insertion order, removal shifts the tail down. Subclassed only to copy
     * it and read its first key without allocating.
     */
    static final class IdMap extends Object2ObjectArrayMap<Identifier, AttributeModifier> {

        Identifier firstKey() {
            return (Identifier) this.key[0];
        }

        void copyFrom(final IdMap other) {
            if (this.key.length < other.size) {
                this.key = new Object[other.key.length];
                this.value = new Object[other.key.length];
            }
            System.arraycopy(other.key, 0, this.key, 0, other.size);
            System.arraycopy(other.value, 0, this.value, 0, other.size);
            for (int i = other.size; i < this.size; i++) {
                this.key[i] = null;
                this.value[i] = null;
            }
            this.size = other.size;
        }
    }

    /**
     * A per-operation map ({@code Object2ObjectOpenHashMap}, fastutil 8.5.18 like the 26.3 client), subclassed to copy
     * its table exactly (what {@code clone()} gives, so a copy iterates in the same order) and to walk its values
     * without an iterator. The walk is fastutil's {@code MapIterator} order: the null-key slot {@code n} first, then the
     * table from {@code n - 1} down to 0. Generic (not bound to Identifier) on purpose: the inherited {@code K[]} and
     * {@code V[]} tables are Object arrays at run time, and a bound type would make javac cast them.
     */
    static final class OperationMap<K, V> extends Object2ObjectOpenHashMap<K, V> {

        @Override
        @SuppressWarnings("unchecked")
        public OperationMap<K, V> clone() {
            return (OperationMap<K, V>) super.clone();
        }

        /** Makes the table identical to {@code other}'s. */
        void copyLayout(final OperationMap<K, V> other) {
            if (this.key.length != other.key.length) {
                this.key = other.key.clone();
                this.value = other.value.clone();
            } else {
                System.arraycopy(other.key, 0, this.key, 0, other.key.length);
                System.arraycopy(other.value, 0, this.value, 0, other.value.length);
            }
            this.n = other.n;
            this.mask = other.mask;
            this.maxFill = other.maxFill;
            this.size = other.size;
            this.containsNullKey = other.containsNullKey;
        }

        /** Empties the map back to a new map's state when its table is still the initial size; false otherwise. */
        boolean resetToNew() {
            if (this.n != this.minN) {
                return false;
            }
            java.util.Arrays.fill(this.key, null);
            java.util.Arrays.fill(this.value, null);
            this.size = 0;
            this.containsNullKey = false;
            return true;
        }

        private double amount(final int pos) {
            return ((AttributeModifier) this.value[pos]).amount();
        }

        /** {@code for (m : values()) base += m.amount()}. */
        double addAmounts(double base) {
            int left = this.size;
            if (this.containsNullKey) {
                base += this.amount(this.n);
                left--;
            }
            for (int pos = this.n; left > 0 && pos-- != 0; ) {
                if (this.key[pos] != null) {
                    base += this.amount(pos);
                    left--;
                }
            }
            return base;
        }

        /** {@code for (m : values()) result += base * m.amount()}. */
        double addMultipliedBase(double result, final double base) {
            int left = this.size;
            if (this.containsNullKey) {
                result += base * this.amount(this.n);
                left--;
            }
            for (int pos = this.n; left > 0 && pos-- != 0; ) {
                if (this.key[pos] != null) {
                    result += base * this.amount(pos);
                    left--;
                }
            }
            return result;
        }

        /** {@code for (m : values()) result *= 1.0 + m.amount()}. */
        double multiplyTotal(double result) {
            int left = this.size;
            if (this.containsNullKey) {
                result *= 1.0 + this.amount(this.n);
                left--;
            }
            for (int pos = this.n; left > 0 && pos-- != 0; ) {
                if (this.key[pos] != null) {
                    result *= 1.0 + this.amount(pos);
                    left--;
                }
            }
            return result;
        }
    }
}
