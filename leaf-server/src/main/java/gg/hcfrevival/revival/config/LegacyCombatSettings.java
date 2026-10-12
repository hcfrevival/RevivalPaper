package gg.hcfrevival.revival.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * The {@code legacy-combat} section of one world. A hit is a legacy hit when the attacker's weapon is in
 * {@link #weaponItems} or one of {@link #weaponTags} and has a {@link #weaponDamage} entry; every other hit
 * stays 26.x.
 *
 * @param weaponItems items listed in {@code weapons}
 * @param weaponTags item tags listed in {@code weapons} as {@code #namespace:path}
 * @param weaponDamage total 1.7.10 damage per weapon, the player's base 1 included
 * @param strengthPerLevel Strength multiplies damage by {@code 1 + this × level}
 * @param weaknessPerLevel Weakness subtracts {@code this × level} before Strength applies
 * @param sharpnessPerLevel extra damage per Sharpness level
 * @param criticalMultiplier damage multiplier for a falling hit
 * @param sweepAttack whether a legacy hit may sweep
 * @param knockbackProfileName the {@code knockback-profile} this world uses
 * @param knockback that profile
 * @param legacyArmor a legacy hit is reduced 4% per armor point, ignoring toughness, as in 1.7.10
 * @param legacyEnchantProtection a legacy hit is reduced by 1.7.10 Protection instead of 26.x's
 * @param randomizeProtection 1.7.10 rolled each hit's Protection between half and all of it; off uses all of it
 * @param hideCooldownIndicator while a legacy weapon is held, max out attack speed so the client shows no cooldown bar
 * @param maxAttacksPerTick legacy attacks a player may land per tick; 0 is unlimited, as in 1.7.10
 */
public record LegacyCombatSettings(
    Set<Identifier> weaponItems,
    List<TagKey<Item>> weaponTags,
    Map<Identifier, Float> weaponDamage,
    double strengthPerLevel,
    double weaknessPerLevel,
    double sharpnessPerLevel,
    double criticalMultiplier,
    boolean sweepAttack,
    String knockbackProfileName,
    KnockbackProfile knockback,
    boolean legacyArmor,
    boolean legacyEnchantProtection,
    boolean randomizeProtection,
    boolean hideCooldownIndicator,
    int maxAttacksPerTick
) {

    static final String PATH = "legacy-combat.";

    static final List<String> DEFAULT_WEAPONS = List.of("#minecraft:swords");

    /** 1.7.10 ItemSword: 4 + ToolMaterial damage (0/1/2/3/0), plus the player's base 1. Copper and netherite follow their tier. */
    static final Map<String, Double> DEFAULT_WEAPON_DAMAGE;

    static {
        final Map<String, Double> damage = new LinkedHashMap<>();
        damage.put("wooden_sword", 5.0);
        damage.put("golden_sword", 5.0);
        damage.put("stone_sword", 6.0);
        damage.put("copper_sword", 6.0);
        damage.put("iron_sword", 7.0);
        damage.put("diamond_sword", 8.0);
        damage.put("netherite_sword", 9.0);
        DEFAULT_WEAPON_DAMAGE = Collections.unmodifiableMap(damage); // keeps revival.yml in tier order
    }

    /** A copy using another knockback profile, for {@code /revival kb} edits. */
    LegacyCombatSettings withKnockback(final String name, final KnockbackProfile profile) {
        return new LegacyCombatSettings(this.weaponItems, this.weaponTags, this.weaponDamage, this.strengthPerLevel, this.weaknessPerLevel,
            this.sharpnessPerLevel, this.criticalMultiplier, this.sweepAttack, name, profile, this.legacyArmor, this.legacyEnchantProtection,
            this.randomizeProtection, this.hideCooldownIndicator, this.maxAttacksPerTick);
    }

    /** Whether any weapon is a legacy weapon in this world; {@code weapons: []} turns legacy combat off. */
    public boolean enabled() {
        return !this.weaponItems.isEmpty() || !this.weaponTags.isEmpty();
    }

    static LegacyCombatSettings read(final ConfigReader reader, final String world, final Map<String, KnockbackProfile> profiles) {
        final Set<Identifier> items = new HashSet<>();
        final List<TagKey<Item>> tags = new ArrayList<>();
        final String weaponsPath = ConfigReader.worldPath(world, PATH + "weapons");
        for (final String entry : reader.getWorldStringList(world, PATH + "weapons", DEFAULT_WEAPONS)) {
            final boolean tag = entry.startsWith("#");
            final Identifier id = identifier(reader, weaponsPath, tag ? entry.substring(1) : entry);
            if (id != null) {
                if (tag) {
                    tags.add(TagKey.create(Registries.ITEM, id));
                } else {
                    items.add(id);
                }
            }
        }

        final Map<Identifier, Float> damage = new HashMap<>();
        final String damagePath = ConfigReader.worldPath(world, PATH + "weapon-damage");
        for (final Map.Entry<String, Double> entry : reader.getWorldDoubleMap(world, PATH + "weapon-damage", DEFAULT_WEAPON_DAMAGE).entrySet()) {
            final Identifier id = identifier(reader, damagePath, entry.getKey());
            if (id != null) {
                damage.put(id, entry.getValue().floatValue());
            }
        }

        String profileName = reader.getWorldString(world, PATH + "knockback-profile", KnockbackProfile.DEFAULT);
        KnockbackProfile profile = profiles.get(profileName);
        if (profile == null) {
            reader.problem(ConfigReader.worldPath(world, PATH + "knockback-profile"), profileName, "a preset or a profile under " + KnockbackProfile.PROFILES, KnockbackProfile.DEFAULT);
            profileName = KnockbackProfile.DEFAULT;
            profile = KnockbackProfile.LEGACY_1_7;
        }

        return new LegacyCombatSettings(
            Set.copyOf(items),
            List.copyOf(tags),
            Map.copyOf(damage),
            reader.getWorldDouble(world, PATH + "strength-multiplier-per-level", 1.3),
            reader.getWorldDouble(world, PATH + "weakness-per-level", 0.5),
            reader.getWorldDouble(world, PATH + "sharpness-per-level", 1.25),
            reader.getWorldDouble(world, PATH + "critical-multiplier", 1.5),
            reader.getWorldBoolean(world, PATH + "sweep-attack", false),
            profileName,
            profile,
            reader.getWorldBoolean(world, PATH + "legacy-armor", true),
            reader.getWorldBoolean(world, PATH + "legacy-enchant-protection", true),
            reader.getWorldBoolean(world, PATH + "randomize-protection", true),
            reader.getWorldBoolean(world, PATH + "hide-cooldown-indicator", true),
            reader.getWorldInt(world, PATH + "max-attacks-per-tick", 0)
        );
    }

    private static @Nullable Identifier identifier(final ConfigReader reader, final String path, final String value) {
        final Identifier id = Identifier.tryParse(value);
        if (id == null) {
            reader.problem(path, value, "an item id such as minecraft:diamond_sword", "skipping it");
        }
        return id;
    }
}
