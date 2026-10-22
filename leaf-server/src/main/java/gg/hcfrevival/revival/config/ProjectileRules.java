package gg.hcfrevival.revival.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How legacy projectiles are thrown, fly and hit, plus splash potions, ender pearls and lag-compensated projectile hits:
 * the part of {@code legacy-combat.projectiles} that docs/revival/projectiles.md specifies. Defaults reproduce 1.7.10,
 * except {@link Pearls#safeLanding} (pearl glitching was a 1.7 bug every HCF server patched) and lag compensation, which
 * only acts for shooters under authority.
 *
 * @param legacyThrow {@code throw: legacy-1.7}: a player's throw starts at 1.7's origin with 1.7's heading and spread, and
 *     never inherits the thrower's motion (26.x's {@code shootFromRotation} adds it)
 * @param legacyFlight {@code flight: legacy-1.7}: thrown items and the rod hook move first and then take drag and gravity,
 *     as 1.7's EntityThrowable and EntityFishHook did (26.x applies gravity and drag before the move)
 * @param legacyHits {@code hit-detection: legacy-1.7}: the {@link #hitMargin} from the first tick, 1.7's intercept (a segment
 *     that starts inside a box still hits it) and the owner immune for {@link #ownerImmuneTicks}
 * @param hitMargin how far entity boxes grow for a legacy projectile's entity test (1.7: 0.3)
 * @param ownerImmuneTicks a legacy projectile can't hit its owner before this many ticks in the air (1.7: 5)
 * @param throwEyeHeight a legacy throw starts this far above the thrower's feet before {@link #throwDrop} (1.7's server: 1.62)
 * @param throwDrop how far below the eye a legacy throw starts (1.7: 0.1)
 * @param throwSide how far to the side a legacy throw starts (1.7: 0.16)
 * @param throwSpread the Gaussian spread per axis of a legacy throw's unit heading, times the item's inaccuracy (1.7: 0.0075)
 * @param lagCompensated projectile entity ids whose hits on players are judged against the boxes the shooter's client drew
 * @param pearls ender pearl rules
 * @param splash splash potion rules
 */
public record ProjectileRules(
    boolean legacyThrow,
    boolean legacyFlight,
    boolean legacyHits,
    double hitMargin,
    int ownerImmuneTicks,
    double throwEyeHeight,
    double throwDrop,
    double throwSide,
    double throwSpread,
    Set<Identifier> lagCompensated,
    Pearls pearls,
    Splash splash
) {

    static final List<String> DEFAULT_LAG_COMPENSATED = List.of("minecraft:fishing_bobber", "minecraft:snowball", "minecraft:egg");

    /**
     * {@code legacy-combat.projectiles.ender-pearl}.
     *
     * @param enabled pearls are legacy projectiles (1.7 throw, flight, hits, and a 0-damage hit that lands on players)
     * @param clientWalls a pearl also collides with the blocks its thrower's client has, fake walls included (needs authority)
     * @param safeLanding the thrower lands only where a standing box fits; otherwise the landing is moved back along the
     *     pearl's last flight, at most {@link #maxBacktrack}
     * @param maxBacktrack how far back along the flight a landing may move
     * @param backtrackStep the step of that search
     * @param passThroughBlocks blocks a pearl flies through and a landing box may overlap (HCF's "pearl through gates")
     * @param passThroughTags block tags, the same
     * @param refund when no landing fits: give the pearl back and clear its cooldown ({@code refund}), or just drop the
     *     teleport ({@code cancel})
     */
    public record Pearls(
        boolean enabled,
        boolean clientWalls,
        boolean safeLanding,
        double maxBacktrack,
        double backtrackStep,
        Set<Identifier> passThroughBlocks,
        List<TagKey<Block>> passThroughTags,
        boolean refund
    ) {

        /** Whether a pearl ignores this block in flight and in the landing test. */
        public boolean passesThrough(final BlockState state) {
            if (this.passThroughBlocks.isEmpty() && this.passThroughTags.isEmpty()) {
                return false;
            }
            for (int i = 0, size = this.passThroughTags.size(); i < size; i++) {
                if (state.is(this.passThroughTags.get(i))) {
                    return true;
                }
            }
            return !this.passThroughBlocks.isEmpty() && this.passThroughBlocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }

        /** Whether any block passes through. */
        public boolean hasPassThrough() {
            return !this.passThroughBlocks.isEmpty() || !this.passThroughTags.isEmpty();
        }
    }

    /**
     * {@code legacy-combat.projectiles.splash-potion}.
     *
     * @param enabled splash potions are legacy projectiles (1.7 throw, flight and hits)
     * @param legacyFalloff {@code falloff: legacy-1.7}: strength {@code 1 - d/4} with {@code d} from the potion's position at
     *     the start of its impact tick to the entity's feet, and full strength on the entity it hit (1.7's EntityPotion);
     *     {@code vanilla} is 26.x's box-to-box distance
     */
    public record Splash(boolean enabled, boolean legacyFalloff) {
    }

    private static final String PATH = LegacyProjectileSettings.PATH;

    static ProjectileRules read(final ConfigReader reader, final String world) {
        final Set<Identifier> compensated = new HashSet<>();
        for (final String type : reader.getWorldStringList(world, PATH + "lag-compensation.projectiles", DEFAULT_LAG_COMPENSATED)) {
            final Identifier id = Identifier.tryParse(type);
            if (id == null) {
                reader.problem(ConfigReader.worldPath(world, PATH + "lag-compensation.projectiles"), type, "an entity id such as minecraft:snowball", "skipping it");
            } else {
                compensated.add(id);
            }
        }
        final Set<Identifier> passBlocks = new HashSet<>();
        final List<TagKey<Block>> passTags = new ArrayList<>();
        for (final String entry : reader.getWorldStringList(world, PATH + "ender-pearl.pass-through", List.of())) {
            final boolean tag = entry.startsWith("#");
            final Identifier id = Identifier.tryParse(tag ? entry.substring(1) : entry);
            if (id == null) {
                reader.problem(ConfigReader.worldPath(world, PATH + "ender-pearl.pass-through"), entry, "a block id or #tag such as minecraft:oak_fence_gate", "skipping it");
            } else if (tag) {
                passTags.add(TagKey.create(Registries.BLOCK, id));
            } else {
                passBlocks.add(id);
            }
        }
        return new ProjectileRules(
            choice(reader, world, "throw", "legacy-1.7", "vanilla"),
            choice(reader, world, "flight", "legacy-1.7", "vanilla"),
            choice(reader, world, "hit-detection", "legacy-1.7", "vanilla"),
            reader.getWorldDouble(world, PATH + "hit-margin", 0.3),
            reader.getWorldInt(world, PATH + "owner-immune-ticks", 5),
            reader.getWorldDouble(world, PATH + "throw-eye-height", 1.62),
            reader.getWorldDouble(world, PATH + "throw-drop", 0.1),
            reader.getWorldDouble(world, PATH + "throw-side", 0.16),
            reader.getWorldDouble(world, PATH + "throw-spread", 0.0075),
            Set.copyOf(compensated),
            new Pearls(
                reader.getWorldBoolean(world, PATH + "ender-pearl.enabled", true),
                reader.getWorldBoolean(world, PATH + "ender-pearl.client-walls", true),
                reader.getWorldBoolean(world, PATH + "ender-pearl.safe-landing", true),
                Math.max(0.0, reader.getWorldDouble(world, PATH + "ender-pearl.max-backtrack", 1.5)),
                step(reader, world),
                Set.copyOf(passBlocks),
                List.copyOf(passTags),
                choice(reader, world, "ender-pearl.no-landing", "refund", "cancel")
            ),
            new Splash(
                reader.getWorldBoolean(world, PATH + "splash-potion.enabled", true),
                choice(reader, world, "splash-potion.falloff", "legacy-1.7", "vanilla")
            )
        );
    }

    private static double step(final ConfigReader reader, final String world) {
        final double step = reader.getWorldDouble(world, PATH + "ender-pearl.backtrack-step", 0.0625);
        if (!(step >= 0.001)) {
            reader.problem(ConfigReader.worldPath(world, PATH + "ender-pearl.backtrack-step"), Double.toString(step), "at least 0.001", "0.0625");
            return 0.0625;
        }
        return step;
    }

    // A two-way option: true for the first (default) value, false for the second
    private static boolean choice(final ConfigReader reader, final String world, final String key, final String first, final String second) {
        final String value = reader.getWorldString(world, PATH + key, first).toLowerCase(Locale.ROOT);
        if (value.equals(first) || value.equals(second)) {
            return value.equals(first);
        }
        reader.problem(ConfigReader.worldPath(world, PATH + key), value, first + " or " + second, first);
        return true;
    }
}
