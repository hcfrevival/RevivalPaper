package gg.hcfrevival.revival.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * A combat rule set (docs/revival/neue-combat.md §2): {@link #CLASSIC} is plain 1.7 and changes nothing,
 * {@link #CLASSIC_CAPPED} is plain 1.7 with Neue's click cap and click buffer only, {@link #NEUE} is the rule set of
 * Combo, the HCF team's own 1.7-PvP game ({@code Combo.Core/Combat/Rules.cs}): a click cap, a click buffer, DI, the
 * tipper and the parry. Rule sets work like knockback profiles: the presets are built in, a global
 * {@code combat-rules} section overrides their keys or adds sets, and each world picks one with
 * {@code legacy-combat.rules}.
 * <p>
 * Durations are written in seconds, as Combo writes them, and held here in server ticks:
 * {@code ticks = seconds <= 0 ? 0 : max(1, round(seconds × 20))} ({@code TickRate.FromSeconds} at 20 Hz).
 *
 * @param maxCps attacks a second the netcode's click bucket admits ({@code Rules.MaxCps}); 0 is off
 * @param clickBurst the bucket's cap, in clicks ({@code PlayerSim.Mouse}: 2, so an even clicker at the cap loses nothing)
 * @param clickBufferTicks how long a click turned away by the victim's damage window waits to land ({@code Rules.ClickBufferSeconds})
 * @param clickBufferLineOfSight a held click lands only when no block is between the attacker's eye and the target
 * @param influenceDegrees the most the victim's held direction bends a knock (DI, {@code Rules.InfluenceDegrees})
 * @param influenceProjectiles DI also bends the knock of projectiles that share the melee window (Combo's rod)
 * @param tipperFrom a hit from this far (eye to box, blocks) is a tipper ({@code Combat.TipperFrom})
 * @param tipperKnock horizontal knock scale of a tipper ({@code Rules.TipperKnock})
 * @param sourUnder a hit from closer than this is sour, 0 included ({@code Combat.SourUnder})
 * @param sourKnock horizontal knock scale of a sour hit ({@code Rules.SourKnock})
 * @param parry a fresh sword block parries the first hit in its window ({@code Rules.Parry}); needs {@code sword-blocking.enabled}
 * @param parryWindowTicks a fully rested block's parry window ({@code PlayerSim.ParryWindowSeconds})
 * @param parryBlockHitTicks a block raised this soon after a swing is a block-hit and never parries ({@code PlayerSim.BlockHitSeconds})
 * @param parryRearmTicks a block raised this soon after the last came down gets no window ({@code PlayerSim.ParryRearmSeconds})
 * @param parryRestTicks one raised this long after the last came down gets the whole window ({@code PlayerSim.ParryRestSeconds})
 * @param parryProjectiles projectiles that share the melee window are parried too, without a stagger
 * @param parryJitterTicks slack on the server-time floor of a block's age (§8.2): a stalled blocker gains at most this
 * @param riposteTicks after a parry the parrier's next hit within this long is a crit ({@code Combat.RiposteSeconds})
 * @param staggerTicks how long a parried attacker can't attack or block ({@code PlayerSim.StaggerSeconds})
 * @param staggerJitterTicks slack on the stagger's real-time floor: under authority the lockout lasts its windows after the
 *                           recoil's ack and at least {@code stagger − this} ticks of real time from the earliest an
 *                           honest ack could have come (the recoil's send time plus the round trip expected then: the
 *                           baseline, or the last ack's when the ping just rose), so a client that runs its ticks fast
 *                           gains at most this much plus its recoil ack's round trip above that; an ack window that came
 *                           in late doesn't move the floor, so it costs no honest click
 * @param staggerRecoilHorizontal the parried attacker is thrown back this fast ({@code Combat.RecoilHorizontal})
 * @param staggerRecoilVertical and up this fast ({@code Combat.RecoilVertical})
 * @param staggerMoveMultiplier movement speed while staggered ({@code PlayerSim.StaggerSlow}); 1 sends no slow at all
 * @param staggerSwordCooldown a staggered attacker's sword shows the item cooldown sweep, which also stops a block going up
 * @param staggerLowersBlock a staggered attacker who is blocking drops the block
 * @param feedback the sounds and particles of each Neue event
 */
public record CombatRules(
    double maxCps,
    double clickBurst,
    int clickBufferTicks,
    boolean clickBufferLineOfSight,
    double influenceDegrees,
    boolean influenceProjectiles,
    double tipperFrom,
    double tipperKnock,
    double sourUnder,
    double sourKnock,
    boolean parry,
    int parryWindowTicks,
    int parryBlockHitTicks,
    int parryRearmTicks,
    int parryRestTicks,
    boolean parryProjectiles,
    int parryJitterTicks,
    int riposteTicks,
    int staggerTicks,
    int staggerJitterTicks,
    double staggerRecoilHorizontal,
    double staggerRecoilVertical,
    double staggerMoveMultiplier,
    boolean staggerSwordCooldown,
    boolean staggerLowersBlock,
    Feedback feedback
) {

    /**
     * One event's cue. A null sound or particle is off (an empty string in revival.yml).
     *
     * @param sound a sound event id; ids not in the registry play as resource-pack sounds
     * @param particle a particle type id; only particles without options (spark, crit, sweep...) are sent
     * @param count particles per event
     */
    public record Cue(@Nullable Identifier sound, float volume, float pitch, @Nullable Identifier particle, int count) {

        static final Cue OFF = new Cue(null, 1.0F, 1.0F, null, 0);
    }

    /**
     * The hit feel (§10): plain vanilla sounds and particles, one sound per event, nothing on the HUD.
     *
     * @param tipper the sound goes to the attacker only; the particle to everyone, at the ray's entry point
     * @param sour silence is the cue, unless an owner sets one
     * @param parry everyone in range, at the parrier; the particle at chest height toward the attacker
     * @param parrySpark a second particle at the same spot
     * @param stagger the staggered player only
     */
    public record Feedback(Cue tipper, Cue sour, Cue parry, Cue parrySpark, Cue stagger) {

        public static final Feedback DEFAULT = new Feedback(
            new Cue(id("entity.arrow.hit_player"), 0.35F, 1.6F, id("electric_spark"), 4),
            Cue.OFF,
            new Cue(id("item.shield.block"), 1.0F, 1.25F, id("sweep_attack"), 1),
            new Cue(null, 1.0F, 1.0F, id("crit"), 6),
            new Cue(id("item.shield.break"), 0.7F, 1.3F, null, 0)
        );

        private static Identifier id(final String path) {
            return Identifier.withDefaultNamespace(path);
        }
    }

    public static final String DEFAULT = "classic";

    /** Plain 1.7: every Neue key is a no-op. */
    public static final CombatRules CLASSIC = new CombatRules(0.0, 2.0, 0, true, 0.0, false, 2.6, 1.0, 1.4, 1.0,
        false, 3, 6, 7, 20, true, 2, 12, 10, 1, 0.45, 0.22, 1.0, true, true, Feedback.DEFAULT);

    /**
     * Classic with Neue's click cap (12 a second, a burst of 2) and click buffer (0.15 s) and nothing else: no tipper, sour,
     * DI, parry, stagger or riposte. It equals a {@code combat-rules.classic-capped} section of {@code max-cps: 12.0} and
     * {@code click-buffer-seconds: 0.15}, which once started from classic, so a file that defines that set keeps working.
     */
    public static final CombatRules CLASSIC_CAPPED = new CombatRules(12.0, 2.0, 3, true, 0.0, false, 2.6, 1.0, 1.4, 1.0,
        false, 3, 6, 7, 20, true, 2, 12, 10, 1, 0.45, 0.22, 1.0, true, true, Feedback.DEFAULT);

    /** Combo's {@code Rules.Neue}, without its moves and body hitboxes (§9: a vanilla client can't use them). */
    public static final CombatRules NEUE = new CombatRules(12.0, 2.0, 3, true, 15.0, true, 2.6, 1.1, 1.4, 0.9,
        true, 3, 6, 7, 20, true, 2, 12, 10, 1, 0.45, 0.22, 0.35, true, true, Feedback.DEFAULT);

    public static final Map<String, CombatRules> PRESETS;

    static {
        final Map<String, CombatRules> presets = new LinkedHashMap<>();
        presets.put(DEFAULT, CLASSIC);
        presets.put("classic-capped", CLASSIC_CAPPED);
        presets.put("neue", NEUE);
        PRESETS = java.util.Collections.unmodifiableMap(presets);
    }

    static final String SECTION = "combat-rules";

    /** Every key a rule set has, in the order revival.yml's header lists them; feedback keys sit under {@code feedback}. */
    public static final List<String> KEYS = List.of(
        "max-cps", "click-burst", "click-buffer-seconds", "click-buffer-line-of-sight", "influence-degrees",
        "influence-projectiles", "tipper-from", "tipper-knock", "sour-under", "sour-knock", "parry", "parry-window-seconds",
        "parry-block-hit-seconds", "parry-rearm-seconds", "parry-rest-seconds", "parry-projectiles", "parry-jitter-ticks",
        "riposte-seconds", "stagger-seconds", "stagger-jitter-ticks", "stagger-recoil-horizontal", "stagger-recoil-vertical",
        "stagger-move-multiplier", "stagger-sword-cooldown", "stagger-lowers-block", "feedback"
    );

    /** The server tick rate the seconds keys convert at. */
    static final int TICKS_PER_SECOND = 20;

    /** Whether a fresh legacy knock is shaped at all (tipper, sour or DI); classic's never is, so its knock stays the engine's own object. */
    public boolean shapesKnock() {
        return this.tipperKnock != 1.0 || this.sourKnock != 1.0 || this.influenceDegrees > 0.0;
    }

    /** Whether a click turned away by a shut damage window is held for the window to open. */
    public boolean buffersClicks() {
        return this.clickBufferTicks > 0;
    }

    /** Whether the click cap limits anything: on, and under 20 a second (at 20 only the per-tick envelope limits). */
    public boolean capsClicks() {
        return this.maxCps > 0.0 && this.maxCps < TICKS_PER_SECOND;
    }

    /** Click tokens one tick refills: {@code max-cps / 20}, 0.6 for Neue. */
    public double clickRefill() {
        return this.maxCps / TICKS_PER_SECOND;
    }

    /** {@code TickRate.FromSeconds} at 20 Hz: 0 for none, otherwise at least one tick. */
    public static int ticks(final double seconds) {
        return seconds <= 0.0 ? 0 : (int) Math.max(1L, Math.round(seconds * TICKS_PER_SECOND));
    }

    /** Reads every rule set under {@code combat-rules}: each starts from the preset of the same name, or classic, and overrides the keys it sets. */
    static Map<String, CombatRules> readAll(final ConfigReader reader) {
        final Map<String, CombatRules> rules = new LinkedHashMap<>(PRESETS);
        final ConfigurationSection section = reader.config().getConfigurationSection(SECTION);
        if (section == null) {
            reader.config().createSection(SECTION);
            return java.util.Collections.unmodifiableMap(rules);
        }
        for (final String name : section.getKeys(false)) {
            rules.put(name, read(reader, SECTION + "." + name + ".", PRESETS.getOrDefault(name, CLASSIC)));
        }
        return java.util.Collections.unmodifiableMap(rules);
    }

    // Reads only the keys the owner wrote, so built-in values stay in code and a preset fix reaches every server
    private static CombatRules read(final ConfigReader reader, final String path, final CombatRules base) {
        final double tipperFrom = number(reader, path + "tipper-from", base.tipperFrom(), 0.0, 3.0);
        // sour-under <= tipper-from <= 3 (the client's reach): a band can't sit inside the other
        final double sourUnder = number(reader, path + "sour-under", base.sourUnder(), 0.0, tipperFrom);
        return new CombatRules(
            number(reader, path + "max-cps", base.maxCps(), 0.0, 20.0),
            number(reader, path + "click-burst", base.clickBurst(), 1.0, 4.0),
            seconds(reader, path + "click-buffer-seconds", base.clickBufferTicks()),
            reader.readBoolean(path + "click-buffer-line-of-sight", base.clickBufferLineOfSight()),
            number(reader, path + "influence-degrees", base.influenceDegrees(), 0.0, 45.0),
            reader.readBoolean(path + "influence-projectiles", base.influenceProjectiles()),
            tipperFrom,
            number(reader, path + "tipper-knock", base.tipperKnock(), 0.0, 2.0),
            sourUnder,
            number(reader, path + "sour-knock", base.sourKnock(), 0.0, 2.0),
            reader.readBoolean(path + "parry", base.parry()),
            seconds(reader, path + "parry-window-seconds", base.parryWindowTicks()),
            seconds(reader, path + "parry-block-hit-seconds", base.parryBlockHitTicks()),
            seconds(reader, path + "parry-rearm-seconds", base.parryRearmTicks()),
            seconds(reader, path + "parry-rest-seconds", base.parryRestTicks()),
            reader.readBoolean(path + "parry-projectiles", base.parryProjectiles()),
            (int) number(reader, path + "parry-jitter-ticks", base.parryJitterTicks(), 0.0, 20.0),
            seconds(reader, path + "riposte-seconds", base.riposteTicks()),
            seconds(reader, path + "stagger-seconds", base.staggerTicks()),
            (int) number(reader, path + "stagger-jitter-ticks", base.staggerJitterTicks(), 0.0, 20.0),
            number(reader, path + "stagger-recoil-horizontal", base.staggerRecoilHorizontal(), 0.0, 3.9),
            number(reader, path + "stagger-recoil-vertical", base.staggerRecoilVertical(), 0.0, 3.9),
            number(reader, path + "stagger-move-multiplier", base.staggerMoveMultiplier(), 0.0, 1.0),
            reader.readBoolean(path + "stagger-sword-cooldown", base.staggerSwordCooldown()),
            reader.readBoolean(path + "stagger-lowers-block", base.staggerLowersBlock()),
            feedback(reader, path + "feedback.", base.feedback())
        );
    }

    private static Feedback feedback(final ConfigReader reader, final String path, final Feedback base) {
        return new Feedback(
            cue(reader, path + "tipper", base.tipper()),
            cue(reader, path + "sour", base.sour()),
            cue(reader, path + "parry", base.parry()),
            cue(reader, path + "parry-spark", base.parrySpark()),
            cue(reader, path + "stagger", base.stagger())
        );
    }

    // <event>-sound, -volume, -pitch, -particle, -particle-count; an empty id turns that half of the cue off
    private static Cue cue(final ConfigReader reader, final String event, final Cue base) {
        return new Cue(
            identifier(reader, event + "-sound", base.sound()),
            (float) number(reader, event + "-volume", base.volume(), 0.0, 10.0),
            (float) number(reader, event + "-pitch", base.pitch(), 0.5, 2.0),
            identifier(reader, event + "-particle", base.particle()),
            (int) number(reader, event + "-particle-count", base.count(), 0.0, 64.0)
        );
    }

    private static @Nullable Identifier identifier(final ConfigReader reader, final String path, final @Nullable Identifier base) {
        final String value = reader.readString(path, base == null ? "" : base.toString()).trim();
        if (value.isEmpty()) {
            return null;
        }
        final Identifier id = Identifier.tryParse(value);
        if (id == null) {
            reader.problem(path, value, "an id such as minecraft:item.shield.block, or '' for none", base == null ? "none" : base);
            return base;
        }
        return id;
    }

    // A number clamped to its range; a value outside it is reported and used clamped
    private static double number(final ConfigReader reader, final String path, final double base, final double min, final double max) {
        final double value = reader.readDouble(path, base);
        if (!(value >= min && value <= max)) {
            final double clamped = Double.isNaN(value) ? base : Math.clamp(value, min, max);
            reader.problem(path, value, "a number from " + min + " to " + max, clamped);
            return clamped;
        }
        return value;
    }

    // Seconds to ticks; a value that isn't a whole number of ticks is reported once and used rounded
    private static int seconds(final ConfigReader reader, final String path, final int baseTicks) {
        final double fallback = (double) baseTicks / TICKS_PER_SECOND;
        final double value = reader.readDouble(path, fallback);
        if (!(value >= 0.0) || value > 60.0) {
            reader.problem(path, value, "seconds from 0 to 60", fallback);
            return baseTicks;
        }
        final int ticks = ticks(value);
        if (Math.abs(value * TICKS_PER_SECOND - Math.rint(value * TICKS_PER_SECOND)) > 1.0E-9) {
            reader.problem(path, value, "a multiple of 0.05 (one server tick)", (double) ticks / TICKS_PER_SECOND);
        }
        return ticks;
    }
}
