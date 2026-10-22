package gg.hcfrevival.revival.auth.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The whole tick on the deploy jar: 300 random {@link FuzzScript} runs (random worlds of 53 movement-relevant blocks in
 * every state, water, lava, random keys and rotations, knockback, explosions, effects, attribute packets, abilities, item
 * use, attacks, teleports, food, no-gravity, elytra with attached rockets, auto-jump on or off) replayed on the port inside
 * RevivalPaper, each run's per-tick state hash compared with
 * the one vanilla 26.3's own Player code produced on the auth-rig oracle ({@link FuzzData}).
 */
@RevivalTest
@RevivalRegistries
class MovementSimFuzzReplayTest {

    @Test
    void everyRunMatchesVanilla() {
        int differ = 0;
        final StringBuilder first = new StringBuilder();
        for (int run = 0; run < FuzzData.HASHES.length; run++) {
            final long hash = play(FuzzData.SEED, run);
            if (hash != FuzzData.HASHES[run]) {
                if (differ++ == 0) {
                    first.append("run ").append(run);
                }
            }
        }
        assertEquals(0, differ, "runs differing from vanilla (first: " + first + ")");
    }

    static long play(final long seed, final int run) {
        final SimTestWorld world = new SimTestWorld();
        final SimPlayer p = new SimPlayer();
        final MovementSim sim = new MovementSim();
        final SimTickInput in = new SimTickInput();
        final FuzzScript script = new FuzzScript(seed, run);
        final FuzzScript.Target target = new FuzzScript.Target() {
            @Override
            public void set(final int x, final int y, final int z, final BlockState state) {
                world.set(x, y, z, state);
            }

            @Override
            public void motion(final Vec3 v) {
                sim.setVelocity(p, v.x, v.y, v.z);
            }

            @Override
            public void explode(final Vec3 v) {
                sim.addVelocity(p, v.x, v.y, v.z);
            }

            @Override
            public void effect(final Holder<MobEffect> effect, final int amplifier) {
                if (amplifier < 0) {
                    sim.removeEffect(p, effect);
                } else {
                    sim.addEffect(p, effect, amplifier);
                }
            }

            @Override
            public void attribute(final Holder<Attribute> attribute, final double base, final List<AttributeModifier> modifiers) {
                sim.applyAttributeUpdate(p, attribute, base, modifiers);
            }

            @Override
            public void abilities(final boolean flying, final boolean mayfly, final float flyingSpeed) {
                sim.setAbilities(p, flying, mayfly, false, flyingSpeed);
            }

            @Override
            public void use(final InteractionHand hand) {
                sim.startUsingItem(p, hand, 72000);
            }

            @Override
            public void release() {
                sim.releaseUsingItem(p);
            }

            @Override
            public void attack() {
                sim.clientAttack(p, true);
            }

            @Override
            public void teleport(final PositionMoveRotation change, final Set<Relative> relatives) {
                sim.teleport(p, change, relatives);
            }

            @Override
            public void food(final int level) {
                p.foodLevel = level;
            }

            @Override
            public void noGravity(final boolean noGravity) {
                p.noGravity = noGravity;
            }

            @Override
            public void addRocket() {
                // FuzzScript counts the rockets; each boosts after the tick
            }

            @Override
            public void removeRocket(final int index) {
            }
        };
        script.build(target);
        p.setPos(script.startX, script.startY, script.startZ);
        p.mainHand = new ItemStack(Items.BOW);
        p.offHand = new ItemStack(Items.SHIELD);
        p.gliderAvailable = script.elytra;
        p.autoJumpOption = script.autoJump;
        long hash = 0;
        for (int t = 0; t < FuzzScript.TICKS; t++) {
            final Input keys = FuzzScript.sent(script.next(p, target), p);
            sim.tick(p, in.set(keys, script.yaw(), script.pitch()), world);
            for (int i = 0; i < script.rockets(); i++) {
                sim.fireworkBoost(p);
            }
            hash = FuzzScript.mix(hash, p.x, p.y, p.z, p.vx, p.vy, p.vz, p.fallDistance,
                FuzzScript.flags(p.onGround, p.horizontalCollision, p.sprinting, p.isSwimming(), p.fallFlying, p.crouching, p.flying, p.usingItem));
        }
        return hash;
    }
}
