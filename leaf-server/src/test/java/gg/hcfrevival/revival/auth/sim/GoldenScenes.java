package gg.hcfrevival.revival.auth.sim;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Scripted movement scenes for {@link MovementSimGoldenTest}: a world, a start state and per-tick keys, rotation and
 * between-tick actions. The expected per-tick results ({@link GoldenData}) come from vanilla 26.3's own Player code
 * running each scene offline (the auth-rig oracle: ~/Developer/tools/auth-rig/harness, {@code ./sim.sh GoldenGen});
 * the test replays the same scenes on the port. Plain Java (no JUnit) so the rig compiles this file too.
 */
public final class GoldenScenes {

    /** Where a scene puts blocks. */
    public interface Blocks2 {
        void set(int x, int y, int z, BlockState state);
    }

    public static final String[] NAMES = {
        "walk_sprint_jump", "stairs_step_up", "sneak_edge", "ice_slide", "water_swim", "lava_wade", "ladder_climb", "slime_bounce",
        "soul_sand_honey", "cobweb_fall", "knockback_air", "item_use_slow", "cardinal_wall", "bubble_column", "attack_slowdown",
        "auto_jump_steps", "rocket_glide", "no_gravity", "boat_step_up", "boat_clutch", "boat_moving", "boat_sneak_edge", "shulker_auto_jump",
        "ghast_from_above", "shulker_section_ceiling"
    };

    public static final int TICKS = 40;

    private GoldenScenes() {
    }

    /** One tick's script: keys, rotation and the action applied before it (null for none). */
    public record Step(Input keys, float yaw, float pitch, String action) {
    }

    /** Start position and rotation. */
    public record Start(double x, double y, double z, float yaw, float pitch) {
    }

    private static Input keys(final String k) {
        return new Input(k.contains("W"), k.contains("S"), k.contains("A"), k.contains("D"), k.contains("J"), k.contains("C"), k.contains("R"));
    }

    private static void floor(final Blocks2 w, final BlockState s, final int y) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                w.set(x, y, z, s);
            }
        }
    }

    /** The client's auto-jump option in the scene (vanilla's default is off). */
    public static boolean autoJump(final String name) {
        return name.equals("auto_jump_steps") || name.equals("shulker_auto_jump");
    }

    /** Whether the player wears an elytra in the scene. */
    public static boolean elytra(final String name) {
        return name.equals("rocket_glide");
    }

    public static void build(final String name, final Blocks2 w) {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        switch (name) {
            case "stairs_step_up" -> {
                floor(w, stone, -1);
                for (int x = -2; x <= 2; x++) {
                    w.set(x, 0, 2, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
                    w.set(x, 0, 3, stone);
                    w.set(x, 1, 3, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                }
            }
            case "sneak_edge" -> {
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        w.set(x, 3, z, stone);
                    }
                }
                floor(w, stone, -1);
            }
            case "ice_slide" -> floor(w, Blocks.ICE.defaultBlockState(), -1);
            case "water_swim" -> {
                floor(w, stone, -4);
                for (int x = -8; x <= 8; x++) {
                    for (int z = 1; z <= 8; z++) {
                        for (int y = -3; y <= -1; y++) {
                            w.set(x, y, z, Blocks.WATER.defaultBlockState());
                        }
                    }
                    for (int z = -8; z <= 0; z++) {
                        w.set(x, -1, z, stone);
                    }
                }
                w.set(0, -1, 2, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
            }
            case "lava_wade" -> {
                floor(w, stone, -2);
                for (int x = -8; x <= 8; x++) {
                    for (int z = 1; z <= 8; z++) {
                        w.set(x, -1, z, Blocks.LAVA.defaultBlockState());
                    }
                    for (int z = -8; z <= 0; z++) {
                        w.set(x, -1, z, stone);
                    }
                }
            }
            case "ladder_climb" -> {
                floor(w, stone, -1);
                for (int y = 0; y < 8; y++) {
                    w.set(0, y, 2, stone);
                    w.set(0, y, 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
                }
            }
            case "slime_bounce" -> {
                floor(w, Blocks.SLIME_BLOCK.defaultBlockState(), -1);
            }
            case "soul_sand_honey" -> {
                floor(w, stone, -1);
                for (int x = -2; x <= 2; x++) {
                    for (int z = 1; z <= 3; z++) {
                        w.set(x, -1, z, Blocks.SOUL_SAND.defaultBlockState());
                    }
                    for (int z = 4; z <= 7; z++) {
                        w.set(x, -1, z, Blocks.HONEY_BLOCK.defaultBlockState());
                    }
                }
            }
            case "cobweb_fall" -> {
                floor(w, stone, -1);
                w.set(0, 2, 0, Blocks.COBWEB.defaultBlockState());
                w.set(0, 1, 0, Blocks.COBWEB.defaultBlockState());
            }
            case "cardinal_wall" -> {
                floor(w, stone, -1);
                for (int z = -8; z <= 8; z++) {
                    w.set(2, 0, z, stone);
                    w.set(2, 1, z, stone);
                }
            }
            case "bubble_column" -> {
                floor(w, stone, -1);
                w.set(0, -1, 0, Blocks.SOUL_SAND.defaultBlockState());
                for (int y = 0; y < 6; y++) {
                    w.set(0, y, 0, Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(net.minecraft.world.level.block.BubbleColumnBlock.DRAG_DOWN, false));
                    w.set(1, y, 0, stone);
                    w.set(-1, y, 0, stone);
                    w.set(0, y, 1, stone);
                    w.set(0, y, -1, stone);
                }
            }
            case "auto_jump_steps" -> {
                // a one-block step (auto-jump), a bottom slab (stepped, not jumped), a block under a slab (too high)
                floor(w, stone, -1);
                for (int x = -8; x <= 8; x++) {
                    w.set(x, 0, 3, stone);
                    w.set(x, 0, 7, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                    w.set(x, 0, 10, stone);
                    w.set(x, 1, 10, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                }
            }
            case "shulker_section_ceiling" -> {
                // a low room in the section below y = 16, the shulker hanging from the ceiling block at y = 17
                floor(w, stone, 12);
                w.set(0, 17, 0, stone);
            }
            default -> floor(w, stone, -1);
        }
    }

    // ------------------------------------------------------------------------------------------------ solid entities

    /**
     * A solid entity of a scene at one tick, as the client holds it when the player ticks: a boat, a shulker (attached to
     * {@code face}, its lid at {@code rawPeek} percent, not moving) or a happy ghast (on its still timeout or not).
     */
    public record Solid(String kind, double x, double y, double z, Direction face, int rawPeek, boolean still) {
        static Solid boat(final double x, final double y, final double z) {
            return new Solid("boat", x, y, z, Direction.DOWN, 0, false);
        }

        static Solid shulker(final double x, final double y, final double z, final Direction face, final int rawPeek) {
            return new Solid("shulker", x, y, z, face, rawPeek, false);
        }

        static Solid ghast(final double x, final double y, final double z, final boolean still) {
            return new Solid("ghast", x, y, z, Direction.DOWN, 0, still);
        }

        /** The client's peek: {@code Shulker.updatePeekAmount}'s target, reached and held. */
        public float peek() {
            return this.rawPeek * 0.01F;
        }

        /**
         * The client's bounding box: EntityDimensions.makeBoundingBox at the position (boat, adult ghast), or
         * Shulker.makeBoundingBox, {@code getProgressAabb} of the physical peek {@code 0.5 - sin((0.5 + peek) pi) / 2}
         * (Shulker.java:203-212).
         */
        public AABB box() {
            return switch (this.kind) {
                case "boat" -> EntityTypes.OAK_BOAT.getDimensions().makeBoundingBox(this.x, this.y, this.z);
                case "ghast" -> EntityTypes.HAPPY_GHAST.getDimensions().makeBoundingBox(this.x, this.y, this.z);
                default -> Shulker.getProgressAabb(1.0F, this.face.getOpposite(), 0.5F - Mth.sin((0.5F + this.peek()) * 3.1415927F) * 0.5F,
                    new Vec3(this.x, this.y, this.z));
            };
        }

        /** SimWorld.ENTITY_SOLID, or ENTITY_SOLID_FROM_ABOVE for a ghast off its still timeout. */
        public int rule() {
            return this.kind.equals("ghast") && !this.still ? SimWorld.ENTITY_SOLID_FROM_ABOVE : SimWorld.ENTITY_SOLID;
        }
    }

    private static final Solid[] NONE = new Solid[0];

    /**
     * The scene's solid entities at tick {@code t}, before the player's tick: index {@code i} is the same entity every tick
     * (null while it is not in the level). Each scene keeps its entities in one entity section, so the client's order is
     * the order they were added: the index.
     */
    public static Solid[] solids(final String name, final int t) {
        return switch (name) {
            case "boat_step_up" -> new Solid[] {Solid.boat(0.5, 0.0, 2.5), Solid.boat(1.9, 0.0, 2.6), Solid.boat(0.7, 0.0, 6.0)};
            // a boat placed under a falling player, gone again later
            case "boat_clutch" -> new Solid[] {t >= 6 && t < 32 ? Solid.boat(2.5, 0.0, 2.5) : null};
            // one boat crossing the player's path, one driving into the player
            case "boat_moving" -> new Solid[] {Solid.boat(6.5 - 0.15 * t, 0.0, 2.5), Solid.boat(2.5, 0.0, 9.0 - 0.12 * t)};
            case "boat_sneak_edge" -> new Solid[] {Solid.boat(0.5, 0.0, 0.5)};
            case "shulker_auto_jump" -> new Solid[] {Solid.shulker(0.5, 0.0, 3.5, Direction.DOWN, 0), Solid.shulker(1.5, 0.0, 3.5, Direction.DOWN, 0),
                Solid.shulker(0.5, 1.0, 3.5, Direction.DOWN, 0), Solid.shulker(2.5, 0.0, 7.5, Direction.DOWN, 100)};
            // the player stands on a ghast, walks off it and back into its side; then it stays still around the player
            case "ghast_from_above" -> new Solid[] {Solid.ghast(4.0, -2.0, 4.0, t >= 32)};
            case "shulker_section_ceiling" -> new Solid[] {Solid.shulker(0.5, 16.0, 0.5, Direction.UP, 100)};
            default -> NONE;
        };
    }

    public static Start start(final String name) {
        return switch (name) {
            case "sneak_edge" -> new Start(0.5, 4.0, 0.5, 0.0F, 10.0F);
            case "water_swim" -> new Start(0.5, 0.0, -0.5, 0.0F, 20.0F);
            case "lava_wade" -> new Start(0.5, 0.0, -0.5, 0.0F, 0.0F);
            case "ladder_climb" -> new Start(0.5, 0.0, 0.5, 0.0F, -10.0F);
            case "slime_bounce" -> new Start(0.25, 5.0, 0.75, 33.0F, 0.0F);
            case "cobweb_fall" -> new Start(0.5, 4.0, 0.5, 0.0F, 0.0F);
            case "cardinal_wall" -> new Start(0.5, 0.0, -6.5, 0.0F, 0.0F);
            case "bubble_column" -> new Start(0.5, 0.0, 0.5, 0.0F, 0.0F);
            case "attack_slowdown" -> new Start(6.5, 0.0, 0.5, 90.0F, 0.0F);
            case "rocket_glide" -> new Start(0.5, 30.0, 0.5, 20.0F, 10.0F);
            case "boat_clutch" -> new Start(2.5, 12.0, 2.5, 0.0F, 0.0F);
            case "boat_moving" -> new Start(2.5, 0.0, 0.5, 0.0F, 0.0F);
            case "boat_sneak_edge" -> new Start(0.5, 0.5625, 0.5, 0.0F, 30.0F);
            case "ghast_from_above" -> new Start(4.0, 2.0, 4.0, -90.0F, 0.0F);
            case "shulker_section_ceiling" -> new Start(0.5, 13.0, 0.5, 0.0F, -30.0F);
            default -> new Start(0.5, 0.0, 0.5, 0.0F, 0.0F);
        };
    }

    /** The script of tick {@code t} (0-based). */
    public static Step step(final String name, final int t) {
        return switch (name) {
            case "walk_sprint_jump" -> new Step(keys(t < 3 ? "" : t < 10 ? "W" : t < 25 ? "WR" : t < 32 ? "WRJ" : ""), 37.5F + (t > 20 ? 0.15F * t : 0.0F), 5.0F, null);
            case "stairs_step_up" -> new Step(keys(t < 2 ? "" : "WR"), 0.0F, 0.0F, null);
            case "sneak_edge" -> new Step(keys(t < 4 ? "" : t < 30 ? "WC" : "W"), t < 15 ? 0.0F : 120.0F, 10.0F, null);
            case "ice_slide" -> new Step(keys(t < 2 ? "" : t < 18 ? "WRJ" : ""), 15.0F, 0.0F, null);
            case "water_swim" -> new Step(keys(t < 2 ? "" : t < 14 ? "WR" : t < 22 ? "WC" : t < 30 ? "WRJ" : "J"), 0.0F, t < 14 ? 30.0F : -20.0F, null);
            case "lava_wade" -> new Step(keys(t < 2 ? "" : t < 30 ? "WJ" : "SJ"), 0.0F, 0.0F, null);
            case "ladder_climb" -> new Step(keys(t < 2 ? "" : t < 20 ? "W" : t < 28 ? "WC" : t < 34 ? "C" : ""), 0.0F, -10.0F, null);
            case "slime_bounce" -> new Step(keys(t < 20 ? "" : t < 30 ? "W" : "C"), 33.0F, 0.0F, null);
            case "soul_sand_honey" -> new Step(keys(t < 2 ? "" : t % 8 == 3 ? "WJ" : "W"), 0.0F, 0.0F, null);
            case "cobweb_fall" -> new Step(keys(t < 15 ? "" : "WJ"), 0.0F, 0.0F, null);
            case "knockback_air" -> new Step(keys(t < 2 ? "" : t < 6 ? "WRJ" : "WR"), 0.0F, 0.0F,
                t == 8 ? "motion -0.35 0.36 0.2" : t == 15 ? "explode 0.1 0.5 -0.3" : null);
            case "item_use_slow" -> new Step(keys(t < 2 ? "" : t < 30 ? "WR" : "WRJ"), 210.0F, 0.0F, t == 10 ? "use" : t == 24 ? "release" : null);
            case "cardinal_wall" -> new Step(keys(t < 2 ? "" : t < 30 ? "WD" : "WDJ"), 0.0F, 0.0F, null);
            case "bubble_column" -> new Step(keys(""), 0.0F, 0.0F, null);
            case "attack_slowdown" -> new Step(keys(t < 2 ? "" : "WR"), 90.0F, 0.0F, t == 20 || t == 22 || t == 34 ? "attack" : null);
            case "auto_jump_steps" -> new Step(keys(t < 2 ? "" : "WR"), t < 34 ? 0.0F : 60.0F, 0.0F, null);
            case "rocket_glide" -> new Step(keys(t < 2 ? "" : t < 4 ? "J" : "W"), 20.0F + 0.5F * t, t < 10 ? 10.0F : -25.0F,
                t == 5 ? "rocket 15" : t == 12 ? "rocket 12" : t == 30 ? "rocket 5" : null);
            case "no_gravity" -> new Step(keys(t < 2 ? "" : t < 30 ? "W" : "WJ"), 0.0F, 0.0F,
                t == 4 ? "motion 0.1 0.5 0.0" : t == 8 ? "nogravity on" : t == 20 ? "nogravity off" : null);
            case "boat_step_up" -> new Step(keys(t < 2 ? "" : t < 22 ? "WR" : t < 26 ? "WRJ" : "W"), t < 14 ? 0.0F : 12.0F, 0.0F, null);
            case "boat_clutch" -> new Step(keys(t < 20 ? "" : t < 24 ? "W" : ""), 0.0F, 0.0F, null);
            case "boat_moving" -> new Step(keys(t < 2 ? "" : t < 30 ? "W" : "WJ"), 0.0F, 0.0F, null);
            case "boat_sneak_edge" -> new Step(keys(t < 2 ? "" : t < 22 ? "WC" : t < 30 ? "C" : "W"), t < 12 ? 0.0F : 50.0F, 30.0F, null);
            case "shulker_auto_jump" -> new Step(keys(t < 2 ? "" : "WR"), t < 20 ? 0.0F : 15.0F, 0.0F, null);
            case "ghast_from_above" -> new Step(keys(t < 2 ? "" : t < 4 ? "J" : t < 19 ? "WR" : t < 23 ? "" : "S"), -90.0F, 0.0F, null);
            case "shulker_section_ceiling" -> new Step(keys(t == 3 || t == 20 ? "J" : t > 24 ? "W" : ""), 0.0F, -30.0F, null);
            default -> new Step(keys(""), 0.0F, 0.0F, null);
        };
    }
}
