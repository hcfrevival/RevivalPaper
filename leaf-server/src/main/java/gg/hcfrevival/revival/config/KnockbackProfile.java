package gg.hcfrevival.revival.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Knockback constants for legacy hits. {@link #LEGACY_1_7} reproduces EntityLivingBase.knockBack and the extra
 * knockback in EntityPlayer.attackTargetEntityWithCurrentItem from 1.7.10.
 *
 * @param friction the victim's horizontal motion is divided by this before the knock
 * @param frictionVertical the victim's vertical motion is divided by this before the knock
 * @param horizontal horizontal push away from the attacker
 * @param vertical upward push
 * @param verticalCap upward motion after the base knock is capped at this
 * @param extraHorizontal extra push along the attacker's facing, per level (Knockback enchant level + 1 when sprinting)
 * @param extraVertical extra upward push when the level is above zero
 * @param attackerSlowdown the attacker's horizontal motion is multiplied by this when the level is above zero
 * @param velocityClamp each axis of the result is clamped to ±this, the most a 1.7 velocity packet could carry
 * @param resistanceMode how the knockback-resistance attribute applies
 * @param motionModel which motion a player victim is knocked from
 * @param groundPredictionDistance treat an airborne player victim within this many blocks of the ground as landed when
 *     their ping says they already are on their screen; 0 turns it off. Ported from core's ClientGround
 * @param groundPredictionPingOffset milliseconds taken off the player's ping before that comparison
 * @param sprintArmedLevel the sprint part of the extra-knockback level on the first sprint hit after a sprint start
 * @param sprintHeldLevel the sprint part on later hits while the sprint is held; 0 in 1.7.10, which needed a w-tap
 * @param sprintArmRequiresGround a sprint start only arms the next sprint hit when the attacker was on the ground
 */
public record KnockbackProfile(
    double friction,
    double frictionVertical,
    double horizontal,
    double vertical,
    double verticalCap,
    double extraHorizontal,
    double extraVertical,
    double attackerSlowdown,
    double velocityClamp,
    ResistanceMode resistanceMode,
    MotionModel motionModel,
    double groundPredictionDistance,
    double groundPredictionPingOffset,
    double sprintArmedLevel,
    double sprintHeldLevel,
    boolean sprintArmRequiresGround
) {

    public enum ResistanceMode {
        /** Ignore knockback resistance; 1.7 had no gear with it. */
        NONE,
        /** 1.7.10: the knock is skipped when a random roll is below the resistance. */
        ROLL,
        /** 26.x: the knock is scaled by {@code 1 - resistance}. */
        SCALE
    }

    public enum MotionModel {
        /** SportBukkit 1.7.10: knock from the player's 1.7 server motion, which the knock never changes, so every hit lands the same. */
        SPORTBUKKIT,
        /** 1.7.10: knock from the player's 1.7 server motion, which keeps the knock, so combo hits compound. */
        VANILLA_1_7,
        /** Knock from 26.x's live server motion. */
        NONE,
        /**
         * Knock from the client's own motion as the authoritative simulation has it (docs/revival/authoritative.md §7.2),
         * so a running victim is knocked from their real run; players not under authority fall back to sportbukkit.
         */
        AUTHORITATIVE
    }

    public static final String DEFAULT = "legacy-1.7";

    public static final KnockbackProfile LEGACY_1_7 = new KnockbackProfile(2.0, 2.0, 0.4, 0.4, 0.4000000059604645, 0.5, 0.1, 0.6, 3.9, ResistanceMode.NONE, MotionModel.SPORTBUKKIT, 0.0, 25.0, 1.0, 0.0, false);

    /**
     * Built-in presets. Except legacy-1.7, legacy-1.8 and hcfr, the numbers are archived server configs from
     * github.com/sprytex/Knockback-Values (MIT) and github.com/threeamnewacc/Server-Archive, as curated by Mental
     * (github.com/owengregson/Mental, MIT, (c) 2024 Owen Gregson). The archives' authenticity can't be verified.
     * Mental gives kohi, minehq and badlion compounding combos because of their 1.7 roots.
     */
    public static final Map<String, KnockbackProfile> PRESETS;

    static {
        final Map<String, KnockbackProfile> presets = new LinkedHashMap<>();
        presets.put(DEFAULT, LEGACY_1_7);
        presets.put("legacy-1.8", new KnockbackProfile(2.0, 2.0, 0.4, 0.4, 0.4, 0.5, 0.1, 0.6, 3.9, ResistanceMode.ROLL, MotionModel.SPORTBUKKIT, 0.0, 25.0, 1.0, 0.0, false));
        presets.put("kohi", preset(2.0, 2.0, 0.35, 0.35, 0.4, 0.425, 0.085));
        presets.put("minehq", preset(2.0, 2.0, 0.36, 0.36, 0.4, 0.45, 0.09));
        presets.put("badlion", preset(2.0, 2.0, 0.34, 0.34, 0.4, 0.48, 0.085));
        presets.put("mmc", preset(1.8, 1.8, 0.32, 0.32, 0.4, 0.5, 0.1));
        presets.put("lunar", preset(1.46, 1.31, 0.54, 0.44, 0.361735, 0.38, 0.0));
        presets.put("velt", preset(10.0, 10.0, 0.325, 0.36, 0.36, 0.5, 0.0));
        // Today's prod numbers from core's environments/factions/plugins/Factions/mechanics.yml (combat.knockback):
        // sprint 0.8 while held, plus sprint-reset 0.5 after a sprint start on the ground (ground-check)
        presets.put("hcfr", new KnockbackProfile(2.0, 2.0, 0.32, 0.38, 0.36, 0.5, 0.0, 0.6, 3.9, ResistanceMode.NONE, MotionModel.SPORTBUKKIT, 0.0, 25.0, 1.3, 0.8, true));
        // Prod's arrow knock (projectile-horizontal 0.2, projectile-vertical 0.25), for legacy-combat.projectiles.knockback-profile
        presets.put("hcfr-projectile", preset(2.0, 2.0, 0.2, 0.25, 0.36, 0.5, 0.0));
        PRESETS = java.util.Collections.unmodifiableMap(presets);
    }

    private static KnockbackProfile preset(final double friction, final double frictionVertical, final double horizontal, final double vertical, final double verticalCap, final double extraHorizontal, final double extraVertical) {
        return new KnockbackProfile(friction, frictionVertical, horizontal, vertical, verticalCap, extraHorizontal, extraVertical, 0.6, 3.9, ResistanceMode.NONE, MotionModel.SPORTBUKKIT, 0.0, 25.0, 1.0, 0.0, false);
    }

    /** Reads every profile under {@code knockback-profiles}: each starts from the preset of the same name, or legacy-1.7, and overrides the keys it sets. */
    static Map<String, KnockbackProfile> readAll(final ConfigReader reader) {
        final Map<String, KnockbackProfile> profiles = new LinkedHashMap<>(PRESETS);
        final ConfigurationSection section = reader.config().getConfigurationSection(PROFILES);
        if (section == null) {
            reader.config().createSection(PROFILES);
            return java.util.Collections.unmodifiableMap(profiles);
        }
        for (final String name : section.getKeys(false)) {
            profiles.put(name, read(reader, PROFILES + "." + name + ".", PRESETS.getOrDefault(name, LEGACY_1_7)));
        }
        return java.util.Collections.unmodifiableMap(profiles);
    }

    static final String PROFILES = "knockback-profiles";

    /** Every key a profile has, in the order revival.yml and {@code /revival kb show} list them. */
    public static final List<String> KEYS = List.of(
        "friction", "friction-vertical", "horizontal", "vertical", "vertical-cap", "extra-horizontal", "extra-vertical",
        "attacker-slowdown", "velocity-clamp", "resistance-mode", "motion-model", "ground-prediction-distance",
        "ground-prediction-ping-offset", "sprint-armed-level", "sprint-held-level", "sprint-arm-requires-ground"
    );

    /** Every key's value, in {@link #KEYS} order: numbers, booleans, and options as their revival.yml names. */
    public Map<String, Object> values() {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("friction", this.friction);
        values.put("friction-vertical", this.frictionVertical);
        values.put("horizontal", this.horizontal);
        values.put("vertical", this.vertical);
        values.put("vertical-cap", this.verticalCap);
        values.put("extra-horizontal", this.extraHorizontal);
        values.put("extra-vertical", this.extraVertical);
        values.put("attacker-slowdown", this.attackerSlowdown);
        values.put("velocity-clamp", this.velocityClamp);
        values.put("resistance-mode", name(this.resistanceMode));
        values.put("motion-model", name(this.motionModel));
        values.put("ground-prediction-distance", this.groundPredictionDistance);
        values.put("ground-prediction-ping-offset", this.groundPredictionPingOffset);
        values.put("sprint-armed-level", this.sprintArmedLevel);
        values.put("sprint-held-level", this.sprintHeldLevel);
        values.put("sprint-arm-requires-ground", this.sprintArmRequiresGround);
        return values;
    }

    /**
     * A copy with one key set from text, as typed in {@code /revival kb set}.
     *
     * @throws IllegalArgumentException for an unknown key or a value of the wrong kind
     */
    public KnockbackProfile with(final String key, final String text) {
        final Map<String, Object> values = this.values();
        final Object current = values.get(key);
        if (current == null) {
            throw new IllegalArgumentException("unknown key " + key + "; keys are " + String.join(", ", KEYS));
        }
        values.put(key, switch (current) {
            case final Double ignored -> {
                try {
                    final double number = Double.parseDouble(text);
                    if (!Double.isFinite(number)) {
                        throw new NumberFormatException();
                    }
                    yield number;
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(key + " needs a number, not " + text);
                }
            }
            case final Boolean ignored -> {
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException(key + " needs true or false, not " + text);
                }
                yield Boolean.parseBoolean(text);
            }
            default -> {
                final Class<? extends Enum<?>> type = key.equals("resistance-mode") ? ResistanceMode.class : MotionModel.class;
                for (final Enum<?> constant : type.getEnumConstants()) {
                    if (name(constant).equalsIgnoreCase(text)) {
                        yield name(constant);
                    }
                }
                throw new IllegalArgumentException(key + " needs one of " + optionNames(type) + ", not " + text);
            }
        });
        return fromValues(values);
    }

    private static KnockbackProfile fromValues(final Map<String, Object> values) {
        return new KnockbackProfile(
            (Double) values.get("friction"),
            (Double) values.get("friction-vertical"),
            (Double) values.get("horizontal"),
            (Double) values.get("vertical"),
            (Double) values.get("vertical-cap"),
            (Double) values.get("extra-horizontal"),
            (Double) values.get("extra-vertical"),
            (Double) values.get("attacker-slowdown"),
            (Double) values.get("velocity-clamp"),
            option(ResistanceMode.class, (String) values.get("resistance-mode")),
            option(MotionModel.class, (String) values.get("motion-model")),
            (Double) values.get("ground-prediction-distance"),
            (Double) values.get("ground-prediction-ping-offset"),
            (Double) values.get("sprint-armed-level"),
            (Double) values.get("sprint-held-level"),
            (Boolean) values.get("sprint-arm-requires-ground")
        );
    }

    private static <E extends Enum<E>> E option(final Class<E> type, final String name) {
        for (final E constant : type.getEnumConstants()) {
            if (name(constant).equals(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(name);
    }

    // Reads only the keys the owner wrote, so built-in values stay in code and a preset fix reaches every server
    private static KnockbackProfile read(final ConfigReader reader, final String path, final KnockbackProfile base) {
        final double friction = reader.readDouble(path + "friction", base.friction());
        return new KnockbackProfile(
            friction,
            reader.readDouble(path + "friction-vertical", reader.config().contains(path + "friction", true) ? friction : base.frictionVertical()),
            reader.readDouble(path + "horizontal", base.horizontal()),
            reader.readDouble(path + "vertical", base.vertical()),
            reader.readDouble(path + "vertical-cap", base.verticalCap()),
            reader.readDouble(path + "extra-horizontal", base.extraHorizontal()),
            reader.readDouble(path + "extra-vertical", base.extraVertical()),
            reader.readDouble(path + "attacker-slowdown", base.attackerSlowdown()),
            reader.readDouble(path + "velocity-clamp", base.velocityClamp()),
            option(reader, path + "resistance-mode", ResistanceMode.class, base.resistanceMode()),
            option(reader, path + "motion-model", MotionModel.class, base.motionModel()),
            reader.readDouble(path + "ground-prediction-distance", base.groundPredictionDistance()),
            reader.readDouble(path + "ground-prediction-ping-offset", base.groundPredictionPingOffset()),
            reader.readDouble(path + "sprint-armed-level", base.sprintArmedLevel()),
            reader.readDouble(path + "sprint-held-level", base.sprintHeldLevel()),
            reader.readBoolean(path + "sprint-arm-requires-ground", base.sprintArmRequiresGround())
        );
    }

    // Options are written lower-case with dashes, e.g. vanilla-1.7 for VANILLA_1_7
    private static <E extends Enum<E>> E option(final ConfigReader reader, final String path, final Class<E> type, final E fallback) {
        final String value = reader.readString(path, name(fallback));
        for (final E constant : type.getEnumConstants()) {
            if (name(constant).equals(value.toLowerCase(Locale.ROOT))) {
                return constant;
            }
        }
        reader.problem(path, value, "one of " + optionNames(type), name(fallback));
        return fallback;
    }

    private static String optionNames(final Class<? extends Enum<?>> type) {
        final StringBuilder names = new StringBuilder();
        for (final Enum<?> constant : type.getEnumConstants()) {
            names.append(names.isEmpty() ? "" : ", ").append(name(constant));
        }
        return names.toString();
    }

    static String name(final Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '-').replace("1-7", "1.7");
    }
}
