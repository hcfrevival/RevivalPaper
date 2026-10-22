package gg.hcfrevival.revival.auth.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The port against vanilla 26.3's own movement code, bit for bit: each {@link GoldenScenes} scene (walking, sprint
 * jumps, stairs, sneaking at an edge, ice, water, lava, ladders, slime, soul sand and honey, cobwebs, knockback, item
 * use, a cardinal yaw along a wall, a bubble column, attack slowdown, auto-jump over steps, an elytra glide boosted by
 * firework rockets, no gravity; and solid entities: stepping onto boats, landing on a placed boat, moving boats, sneaking
 * at a boat's edge, auto-jumping onto shulkers, a happy ghast only solid from above, a shulker lid the client's section
 * lookup misses) replayed on the port must give the positions and flags vanilla's Player code produced for it on the
 * auth-rig oracle ({@link GoldenData}), where the entities were real vanilla ones.
 */
@RevivalTest
@RevivalRegistries
class MovementSimGoldenTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "walk_sprint_jump", "stairs_step_up", "sneak_edge", "ice_slide", "water_swim", "lava_wade", "ladder_climb", "slime_bounce",
        "soul_sand_honey", "cobweb_fall", "knockback_air", "item_use_slow", "cardinal_wall", "bubble_column", "attack_slowdown",
        "auto_jump_steps", "rocket_glide", "no_gravity", "boat_step_up", "boat_clutch", "boat_moving", "boat_sneak_edge", "shulker_auto_jump",
        "ghast_from_above", "shulker_section_ceiling"
    })
    void sceneMatchesVanilla(final String name) {
        int index = -1;
        for (int i = 0; i < GoldenScenes.NAMES.length; i++) {
            if (GoldenScenes.NAMES[i].equals(name)) {
                index = i;
            }
        }
        final String hex = GoldenData.DATA[index];
        final SimTestWorld world = new SimTestWorld();
        GoldenScenes.build(name, world::set);
        final SimPlayer p = new SimPlayer();
        final MovementSim sim = new MovementSim();
        final SimTickInput in = new SimTickInput();
        final GoldenScenes.Start start = GoldenScenes.start(name);
        p.setPos(start.x(), start.y(), start.z());
        p.yRot = start.yaw();
        p.xRot = start.pitch();
        p.mainHand = new ItemStack(Items.BOW);
        p.autoJumpOption = GoldenScenes.autoJump(name);
        p.gliderAvailable = GoldenScenes.elytra(name);
        final List<int[]> rockets = new ArrayList<>();
        for (int t = 0; t < GoldenScenes.TICKS; t++) {
            final GoldenScenes.Step step = GoldenScenes.step(name, t);
            // the solid entities as the client holds them for this tick, in its order
            world.solids.clear();
            for (final GoldenScenes.Solid solid : GoldenScenes.solids(name, t)) {
                if (solid != null) {
                    world.solid(solid.box(), solid.x(), solid.y(), solid.z(), solid.rule());
                }
            }
            if (step.action() != null) {
                final String[] a = step.action().split(" ");
                switch (a[0]) {
                    case "motion" -> sim.setVelocity(p, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
                    case "explode" -> sim.addVelocity(p, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
                    case "use" -> sim.startUsingItem(p, InteractionHand.MAIN_HAND, 72000);
                    case "release" -> sim.releaseUsingItem(p);
                    case "attack" -> sim.clientAttack(p, true);
                    case "rocket" -> rockets.add(new int[] {Integer.parseInt(a[1])});
                    case "nogravity" -> p.noGravity = a[1].equals("on");
                    default -> throw new IllegalArgumentException(step.action());
                }
            }
            sim.tick(p, in.set(FuzzScript.sent(step.keys(), p), step.yaw(), step.pitch()), world);
            // attached rockets tick after the player, then leave after their life
            for (final int[] rocket : rockets) {
                sim.fireworkBoost(p);
                rocket[0]--;
            }
            rockets.removeIf(rocket -> rocket[0] <= 0);
            final String at = name + " tick " + t;
            assertEquals(bits(hex, t * 4), Double.doubleToRawLongBits(p.x), at + " x " + p.x);
            assertEquals(bits(hex, t * 4 + 1), Double.doubleToRawLongBits(p.y), at + " y " + p.y);
            assertEquals(bits(hex, t * 4 + 2), Double.doubleToRawLongBits(p.z), at + " z " + p.z);
            final long flags = (p.onGround ? 1 : 0) | (p.horizontalCollision ? 2 : 0) | (p.sprinting ? 4 : 0);
            assertEquals(bits(hex, t * 4 + 3), flags, at + " flags");
        }
        final int end = GoldenScenes.TICKS * 4;
        assertEquals(bits(hex, end), Double.doubleToRawLongBits(p.vx), name + " final vx");
        assertEquals(bits(hex, end + 1), Double.doubleToRawLongBits(p.vy), name + " final vy");
        assertEquals(bits(hex, end + 2), Double.doubleToRawLongBits(p.vz), name + " final vz");
    }

    private static long bits(final String hex, final int index) {
        return Long.parseUnsignedLong(hex.substring(index * 16, index * 16 + 16), 16);
    }
}
