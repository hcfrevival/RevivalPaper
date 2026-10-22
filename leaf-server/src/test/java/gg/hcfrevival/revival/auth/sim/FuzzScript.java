package gg.hcfrevival.revival.auth.sim;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A deterministic random movement script for {@link MovementSimFuzzReplayTest}: a world, a start and per-tick keys,
 * rotation and between-tick changes (knockback, explosions, effects, attribute packets, abilities, item use, attacks,
 * teleports, food, no-gravity echoes, attached firework rockets), the client's auto-jump option and whether it wears an
 * elytra, all drawn from (seed, run). The auth-rig harness plays it on vanilla's own Player
 * code ({@code ./sim.sh FuzzRecord}) and records a hash of every tick's state per run ({@link FuzzData}); the test
 * replays it on the port inside RevivalPaper, so the whole tick is checked on the deploy jar, not only on vanilla's.
 * Decisions read only the port's state, which equals vanilla's while they agree. Plain Java, no JUnit.
 */
public final class FuzzScript {

    public static final int TICKS = 200;

    /** What a tick's changes are applied to: the port, and in the rig vanilla's player too. */
    public interface Target {
        void set(int x, int y, int z, BlockState state);

        void motion(Vec3 v);

        void explode(Vec3 v);

        void effect(Holder<MobEffect> effect, int amplifier);

        void attribute(Holder<Attribute> attribute, double base, List<AttributeModifier> modifiers);

        void abilities(boolean flying, boolean mayfly, float flyingSpeed);

        void use(InteractionHand hand);

        void release();

        void attack();

        void teleport(PositionMoveRotation change, Set<Relative> relatives);

        void food(int level);

        /** A self {@code DATA_NO_GRAVITY} echo. */
        void noGravity(boolean noGravity);

        /** A firework rocket attached to the player enters the client's level (it ticks after the player from now on). */
        void addRocket();

        /** The rocket at {@code index} (in the order they were added) leaves the client's level. */
        void removeRocket(int index);
    }

    private static final Block[] PALETTE = {
        Blocks.STONE, Blocks.STONE_SLAB, Blocks.OAK_STAIRS, Blocks.OAK_FENCE, Blocks.COBBLESTONE_WALL, Blocks.GLASS_PANE, Blocks.LADDER, Blocks.VINE,
        Blocks.SCAFFOLDING, Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.HONEY_BLOCK, Blocks.SLIME_BLOCK, Blocks.SOUL_SAND,
        Blocks.ICE, Blocks.BLUE_ICE, Blocks.BUBBLE_COLUMN, Blocks.STRAW_BED, Blocks.OAK_TRAPDOOR, Blocks.SNOW, Blocks.FARMLAND, Blocks.DIRT_PATH,
        Blocks.BAMBOO, Blocks.POINTED_DRIPSTONE, Blocks.BIG_DRIPLEAF, Blocks.CAULDRON, Blocks.WATER_CAULDRON, Blocks.LANTERN, Blocks.IRON_CHAIN,
        Blocks.OAK_FENCE_GATE, Blocks.KELP, Blocks.SEAGRASS, Blocks.CACTUS, Blocks.HOPPER, Blocks.ANVIL, Blocks.END_PORTAL_FRAME, Blocks.SOUL_SOIL,
        Blocks.MUD, Blocks.SHELF_MUSHROOM, Blocks.TWISTING_VINES, Blocks.CAVE_VINES, Blocks.MOSS_CARPET, Blocks.DAYLIGHT_DETECTOR, Blocks.LECTERN,
        Blocks.STONECUTTER, Blocks.GRINDSTONE, Blocks.BELL, Blocks.COMPOSTER, Blocks.CANDLE, Blocks.SEA_PICKLE, Blocks.AZALEA, Blocks.DECORATED_POT
    };
    private static final List<BlockState> STATES = new ArrayList<>();

    private final Random r;
    private float yaw;
    private float pitch;
    private Input keys = Input.EMPTY;
    private final List<int[]> rockets = new ArrayList<>();
    /** The client's auto-jump option in this run ({@code Options.autoJump}). */
    public final boolean autoJump;
    /** Whether the player wears a glider (elytra) in this run, so it can glide and rockets boost it. */
    public final boolean elytra;
    public final double startX;
    public final double startY;
    public final double startZ;

    public FuzzScript(final long seed, final int run) {
        this.r = new Random(new SplittableRandom(seed * 1_000_003L + run).nextLong());
        this.startX = this.r.nextInt(5) - 2 + (this.r.nextInt(3) == 0 ? this.r.nextDouble() : 0.5);
        this.startY = this.r.nextInt(3);
        this.startZ = this.r.nextInt(5) - 2 + (this.r.nextInt(3) == 0 ? this.r.nextDouble() : 0.5);
        this.yaw = this.r.nextInt(4) == 0 ? 90.0F * this.r.nextInt(4) : this.r.nextFloat() * 720.0F - 360.0F;
        this.pitch = this.r.nextFloat() * 120.0F - 60.0F;
        this.autoJump = this.r.nextInt(3) == 0;
        this.elytra = this.r.nextInt(4) == 0;
    }

    private static synchronized List<BlockState> states() {
        if (STATES.isEmpty()) {
            for (final Block b : PALETTE) {
                STATES.addAll(b.getStateDefinition().getPossibleStates());
            }
            for (int level = 0; level <= 15; level++) {
                STATES.add(Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level));
            }
            STATES.add(Blocks.LAVA.defaultBlockState());
        }
        return STATES;
    }

    public float startYaw() {
        return this.yaw;
    }

    public float startPitch() {
        return this.pitch;
    }

    /** The world: a floor with holes, walls, scattered palette blocks, a pool. Call once, first. */
    public void build(final Target t) {
        final List<BlockState> states = states();
        final BlockState floor = this.r.nextInt(3) == 0 ? states.get(this.r.nextInt(states.size())) : Blocks.STONE.defaultBlockState();
        for (int x = -9; x <= 9; x++) {
            for (int z = -9; z <= 9; z++) {
                if (this.r.nextInt(40) != 0) {
                    t.set(x, -1, z, floor);
                }
                t.set(x, -5, z, Blocks.STONE.defaultBlockState());
                if (Math.abs(x) == 9 || Math.abs(z) == 9) {
                    for (int y = 0; y < 4; y++) {
                        t.set(x, y, z, Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        if (this.r.nextInt(3) == 0) {
            for (int x = 2; x <= 6; x++) {
                for (int z = -6; z <= -2; z++) {
                    for (int y = -4; y <= -1; y++) {
                        t.set(x, y, z, Blocks.WATER.defaultBlockState());
                    }
                }
            }
        }
        final int count = 30 + this.r.nextInt(60);
        for (int i = 0; i < count; i++) {
            t.set(this.r.nextInt(17) - 8, this.r.nextInt(4), this.r.nextInt(17) - 8, states.get(this.r.nextInt(states.size())));
        }
    }

    /** The between-tick change of the next tick, applied to {@code t}; {@code p} is the port's state. Returns the keys. */
    public Input next(final SimPlayer p, final Target t) {
        for (int i = this.rockets.size() - 1; i >= 0; i--) {
            if (--this.rockets.get(i)[0] <= 0) {
                this.rockets.remove(i);
                t.removeRocket(i);
            }
        }
        final int roll = this.r.nextInt(200);
        if (roll < 4) {
            t.motion(new Vec3(this.r.nextGaussian() * 0.4, this.r.nextGaussian() * 0.3 + 0.2, this.r.nextGaussian() * 0.4));
        } else if (roll < 6) {
            t.explode(new Vec3(this.r.nextGaussian() * 0.5, this.r.nextGaussian() * 0.5, this.r.nextGaussian() * 0.5));
        } else if (roll < 9) {
            final Holder<MobEffect>[] effects = effects();
            t.effect(effects[this.r.nextInt(effects.length)], this.r.nextInt(3) == 0 ? -1 : this.r.nextInt(3));
        } else if (roll < 12) {
            final Holder<Attribute>[] attributes = attributes();
            final Holder<Attribute> attribute = attributes[this.r.nextInt(attributes.length)];
            final SimAttributes.Instance instance = p.attributes.get(attribute);
            final double base = this.r.nextInt(3) == 0 ? instance.baseValue() * (0.5 + this.r.nextDouble()) : instance.baseValue();
            final List<AttributeModifier> modifiers = new ArrayList<>();
            for (int i = this.r.nextInt(3); i > 0; i--) {
                final Identifier id = this.r.nextInt(4) == 0 ? SimAttributes.SPRINTING.id() : Identifier.withDefaultNamespace("fuzz_" + this.r.nextInt(8));
                final AttributeModifier.Operation op = AttributeModifier.Operation.values()[this.r.nextInt(3)];
                final double amount = this.r.nextGaussian() * 0.3;
                if (modifiers.stream().noneMatch(m -> m.id().equals(id))) {
                    modifiers.add(new AttributeModifier(id, amount, op));
                }
            }
            t.attribute(attribute, base, modifiers);
        } else if (roll < 13) {
            final boolean mayfly = this.r.nextBoolean();
            t.abilities(mayfly && this.r.nextBoolean(), mayfly, 0.05F);
        } else if (roll < 18) {
            t.use(this.r.nextInt(3) == 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        } else if (roll < 22 && p.usingItem) {
            t.release();
        } else if (roll < 26) {
            t.attack();
        } else if (roll < 27) {
            final Set<Relative> relatives = EnumSet.noneOf(Relative.class);
            for (final Relative rel : Relative.values()) {
                if (this.r.nextBoolean()) {
                    relatives.add(rel);
                }
            }
            t.teleport(new PositionMoveRotation(new Vec3(this.r.nextGaussian(), this.r.nextGaussian(), this.r.nextGaussian()),
                new Vec3(this.r.nextGaussian() * 0.2, this.r.nextGaussian() * 0.2, this.r.nextGaussian() * 0.2),
                this.r.nextFloat() * 90.0F, this.r.nextFloat() * 30.0F), relatives);
        } else if (roll < 28) {
            t.food(this.r.nextInt(21));
        } else if (roll < 29) {
            t.noGravity(this.r.nextInt(3) == 0);
        } else if (roll < 33 && this.elytra) {
            this.rockets.add(new int[] {5 + this.r.nextInt(40)});
            t.addRocket();
        }
        final Input k = this.keys;
        this.keys = new Input(toggle(k.forward(), 0.3, 0.05), toggle(k.backward(), 0.03, 0.3), toggle(k.left(), 0.07, 0.15),
            toggle(k.right(), 0.07, 0.15), toggle(k.jump(), 0.12, 0.25), toggle(k.shift(), 0.02, 0.2), toggle(k.sprint(), 0.15, 0.05));
        if (this.r.nextInt(8) == 0) {
            this.yaw += (float) this.r.nextGaussian() * 25.0F;
        }
        if (this.r.nextInt(50) == 0) {
            this.yaw = 90.0F * (this.r.nextInt(9) - 4);
        }
        if (this.r.nextInt(10) == 0) {
            this.pitch = Math.max(-90.0F, Math.min(90.0F, this.pitch + (float) this.r.nextGaussian() * 20.0F));
        }
        return this.keys;
    }

    /** The attached rockets the client holds now: call {@link MovementSim#fireworkBoost} this many times after the tick. */
    public int rockets() {
        return this.rockets.size();
    }

    /**
     * What the client sends for a tick whose keyboard keys are {@code keys}: with the jump an auto-jump the last move
     * predicted makes (LocalPlayer.aiStep, makeJump). Exact while the port's auto-jump option is the client's.
     */
    public static Input sent(final Input keys, final SimPlayer p) {
        if (p.autoJumpTime > 0 && !keys.jump()) {
            return new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint());
        }
        return keys;
    }

    public float yaw() {
        return this.yaw;
    }

    public float pitch() {
        return this.pitch;
    }

    private boolean toggle(final boolean down, final double on, final double off) {
        return down ? this.r.nextDouble() >= off : this.r.nextDouble() < on;
    }

    @SuppressWarnings("unchecked")
    private static Holder<MobEffect>[] effects() {
        return new Holder[] {MobEffects.JUMP_BOOST, MobEffects.SLOW_FALLING, MobEffects.LEVITATION, MobEffects.DOLPHINS_GRACE, MobEffects.BLINDNESS,
            MobEffects.WEAVING};
    }

    @SuppressWarnings("unchecked")
    private static Holder<Attribute>[] attributes() {
        return new Holder[] {Attributes.MOVEMENT_SPEED, Attributes.SNEAKING_SPEED, Attributes.JUMP_STRENGTH, Attributes.STEP_HEIGHT, Attributes.GRAVITY,
            Attributes.SCALE, Attributes.MOVEMENT_EFFICIENCY, Attributes.WATER_MOVEMENT_EFFICIENCY, Attributes.FRICTION_MODIFIER,
            Attributes.AIR_DRAG_MODIFIER, Attributes.BOUNCINESS, Attributes.ATTACK_SPEED};
    }

    /** Folds one tick's state into the run hash: position and velocity bits, fall distance, flags. */
    public static long mix(final long hash, final double x, final double y, final double z, final double vx, final double vy, final double vz,
                           final double fallDistance, final int flags) {
        long h = hash;
        h = step(h, Double.doubleToRawLongBits(x));
        h = step(h, Double.doubleToRawLongBits(y));
        h = step(h, Double.doubleToRawLongBits(z));
        h = step(h, Double.doubleToRawLongBits(vx));
        h = step(h, Double.doubleToRawLongBits(vy));
        h = step(h, Double.doubleToRawLongBits(vz));
        h = step(h, Double.doubleToRawLongBits(fallDistance));
        return step(h, flags);
    }

    private static long step(final long h, final long v) {
        long x = (h ^ v) * 0x9E3779B97F4A7C15L;
        x ^= x >>> 29;
        return x;
    }

    /** The flags folded per tick: onGround, horizontalCollision, sprinting, swimming, fallFlying, crouching, flying, using. */
    public static int flags(final boolean onGround, final boolean horizontalCollision, final boolean sprinting, final boolean swimming,
                            final boolean fallFlying, final boolean crouching, final boolean flying, final boolean using) {
        return (onGround ? 1 : 0) | (horizontalCollision ? 2 : 0) | (sprinting ? 4 : 0) | (swimming ? 8 : 0) | (fallFlying ? 16 : 0)
            | (crouching ? 32 : 0) | (flying ? 64 : 0) | (using ? 128 : 0);
    }
}
