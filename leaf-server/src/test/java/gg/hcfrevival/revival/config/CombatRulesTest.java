package gg.hcfrevival.revival.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.lang.reflect.RecordComponent;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Combat rule sets (docs/revival/neue-combat.md §2). Neue's numbers are Combo's ({@code Combo.Core/Combat/Rules.cs}
 * {@code Rules.Neue}, {@code Combat.TipperFrom/SourUnder/RiposteSeconds/Recoil*}, {@code PlayerSim.ParryWindowSeconds,
 * BlockHitSeconds, ParryRearmSeconds, ParryRestSeconds, StaggerSeconds, StaggerSlow}), in 20 Hz ticks.
 */
@RevivalTest
class CombatRulesTest {

    private static ConfigReader reader(final String yaml) throws InvalidConfigurationException {
        final YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return new ConfigReader(config);
    }

    @Test
    void everyWorldIsClassicByDefaultAndPresetsStayInCode() throws InvalidConfigurationException {
        final ConfigReader reader = reader("");
        final RevivalSettings settings = RevivalConfig.read(reader);

        assertSame(CombatRules.CLASSIC, settings.legacyCombat().rules());
        assertEquals("classic", settings.legacyCombat().rulesName());
        assertEquals(List.of("classic", "classic-capped", "neue"), List.copyOf(settings.combatRules().keySet()));
        reader.config().options().copyDefaults(true);
        final YamlConfiguration saved = new YamlConfiguration();
        saved.loadFromString(reader.config().saveToString());
        assertEquals("classic", saved.getString("world-settings.default.legacy-combat.rules"));
        assertTrue(saved.getConfigurationSection("combat-rules").getKeys(false).isEmpty());
        assertTrue(reader.problems().isEmpty());
    }

    @Test
    void classicChangesNothing() {
        final CombatRules classic = CombatRules.CLASSIC;
        assertFalse(classic.shapesKnock());
        assertFalse(classic.buffersClicks());
        assertFalse(classic.capsClicks());
        assertFalse(classic.parry());
        assertEquals(1.0, classic.staggerMoveMultiplier());
    }

    @Test
    void classicCappedIsClassicWithTheCapAndTheBufferOnly() throws ReflectiveOperationException {
        final CombatRules capped = CombatRules.PRESETS.get("classic-capped");
        assertSame(CombatRules.CLASSIC_CAPPED, capped);
        assertEquals(12.0, capped.maxCps());
        assertEquals(0.6, capped.clickRefill(), 1.0E-12);
        assertEquals(2.0, capped.clickBurst());
        assertEquals(3, capped.clickBufferTicks()); // 0.15 s
        assertTrue(capped.clickBufferLineOfSight());
        assertTrue(capped.capsClicks());
        assertTrue(capped.buffersClicks());
        assertEquals(1.0, capped.tipperKnock());
        assertEquals(1.0, capped.sourKnock());
        assertEquals(0.0, capped.influenceDegrees());
        assertFalse(capped.shapesKnock());
        assertFalse(capped.parry()); // so no stagger and no riposte either
        assertEquals(1.0, capped.staggerMoveMultiplier());
        // Every key but the two is classic's
        for (final RecordComponent component : CombatRules.class.getRecordComponents()) {
            final String name = component.getName();
            if (!name.equals("maxCps") && !name.equals("clickBufferTicks")) {
                assertEquals(component.getAccessor().invoke(CombatRules.CLASSIC), component.getAccessor().invoke(capped), name);
            }
        }
    }

    @Test
    void theNasCustomClassicCappedSetResolvesToThePreset() throws InvalidConfigurationException {
        // The NAS ran classic + cap + buffer as a custom set before the preset existed; its file must keep working unchanged,
        // and the same two keys over classic under any other name (how that set resolved then) are the same rules
        final ConfigReader reader = reader("""
            combat-rules:
              classic-capped:
                max-cps: 12.0
                click-buffer-seconds: 0.15
              capped-over-classic:
                max-cps: 12.0
                click-buffer-seconds: 0.15
            world-settings:
              default:
                legacy-combat:
                  rules: classic-capped
            """);
        final RevivalSettings settings = RevivalConfig.read(reader);

        assertEquals("classic-capped", settings.legacyCombat().rulesName());
        assertEquals(CombatRules.CLASSIC_CAPPED, settings.legacyCombat().rules());
        assertEquals(CombatRules.CLASSIC_CAPPED, settings.combatRules("capped-over-classic"));
        assertTrue(reader.problems().isEmpty(), reader.problems().toString());
    }

    @Test
    void aWorldPicksClassicCappedWithoutASection() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            world-settings:
              default:
                legacy-combat:
                  rules: classic-capped
            """);
        final RevivalSettings settings = RevivalConfig.read(reader);

        assertSame(CombatRules.CLASSIC_CAPPED, settings.legacyCombat().rules());
        assertTrue(reader.problems().isEmpty(), reader.problems().toString());
    }

    @Test
    void neueIsCombosRulesInTicks() {
        final CombatRules neue = CombatRules.PRESETS.get("neue");
        assertSame(CombatRules.NEUE, neue);
        assertEquals(12.0, neue.maxCps()); // Rules.MaxCps
        assertEquals(0.6, neue.clickRefill(), 1.0E-12); // 0.6 tokens per client tick
        assertEquals(2.0, neue.clickBurst());
        assertEquals(3, neue.clickBufferTicks()); // 0.15 s
        assertEquals(15.0, neue.influenceDegrees());
        assertEquals(2.6, neue.tipperFrom());
        assertEquals(1.1, neue.tipperKnock());
        assertEquals(1.4, neue.sourUnder());
        assertEquals(0.9, neue.sourKnock());
        assertTrue(neue.parry());
        assertEquals(3, neue.parryWindowTicks()); // 0.15 s
        assertEquals(6, neue.parryBlockHitTicks()); // 0.3 s
        assertEquals(7, neue.parryRearmTicks()); // 0.35 s
        assertEquals(20, neue.parryRestTicks()); // 1.0 s
        assertEquals(12, neue.riposteTicks()); // 0.6 s
        assertEquals(10, neue.staggerTicks()); // 0.5 s
        assertEquals(1, neue.staggerJitterTicks()); // the lockout's real-time floor allows one tick (cheat suite 2, L3)
        assertEquals(0.45, neue.staggerRecoilHorizontal());
        assertEquals(0.22, neue.staggerRecoilVertical());
        assertEquals(0.35, neue.staggerMoveMultiplier());
        assertTrue(neue.shapesKnock());
        assertTrue(neue.buffersClicks());
        assertTrue(neue.capsClicks());
    }

    @Test
    void secondsBecomeTicks() {
        assertEquals(0, CombatRules.ticks(0.0));
        assertEquals(0, CombatRules.ticks(-1.0));
        assertEquals(1, CombatRules.ticks(0.01)); // never rounds a duration away
        assertEquals(3, CombatRules.ticks(0.15)); // 0.15 × 20 is 3.0000000000000004
        assertEquals(7, CombatRules.ticks(0.35));
        assertEquals(12, CombatRules.ticks(0.6));
        assertEquals(20, CombatRules.ticks(1.0));
        assertEquals(4, CombatRules.ticks(0.175)); // half a tick rounds up
    }

    @Test
    void worldsPickASetAndOverridesKeepTheRest() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            combat-rules:
              neue:
                max-cps: 14.0
                tipper-knock: 1.15
                feedback:
                  tipper-sound: ''
                  parry-pitch: 1.4
              practice:
                parry: true
                parry-window-seconds: 0.25
            world-settings:
              default:
                legacy-combat:
                  rules: neue
              hub:
                legacy-combat:
                  rules: classic
              duels:
                legacy-combat:
                  rules: practice
            """);
        final RevivalSettings settings = RevivalConfig.read(reader);

        final CombatRules neue = settings.legacyCombat().rules();
        assertEquals("neue", settings.legacyCombat().rulesName());
        assertEquals(14.0, neue.maxCps());
        assertEquals(1.15, neue.tipperKnock());
        assertEquals(0.9, neue.sourKnock()); // not set: the preset's
        assertNull(neue.feedback().tipper().sound()); // '' turns it off
        assertEquals(Identifier.withDefaultNamespace("electric_spark"), neue.feedback().tipper().particle());
        assertEquals(1.4F, neue.feedback().parry().pitch());
        assertEquals(Identifier.withDefaultNamespace("item.shield.block"), neue.feedback().parry().sound());

        assertSame(CombatRules.CLASSIC, settings.legacyCombat("hub").rules());

        final CombatRules practice = settings.legacyCombat("duels").rules();
        assertTrue(practice.parry()); // a new name starts from classic
        assertEquals(5, practice.parryWindowTicks());
        assertEquals(0, practice.clickBufferTicks());
        assertFalse(practice.shapesKnock());
        assertSame(settings.legacyCombat(), settings.legacyCombat("world_nether"));
        assertTrue(reader.problems().isEmpty());
    }

    @Test
    void unknownSetFallsBackToClassic() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            world-settings:
              arena:
                legacy-combat:
                  rules: nouveau
            """);
        final RevivalSettings settings = RevivalConfig.read(reader);

        assertEquals(1, reader.problems().size());
        assertEquals("classic", settings.legacyCombat("arena").rulesName());
        assertSame(CombatRules.CLASSIC, settings.legacyCombat("arena").rules());
    }

    @Test
    void rangesAreClampedAndReported() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            combat-rules:
              wild:
                max-cps: 30.0
                click-burst: 0.5
                influence-degrees: 90.0
                tipper-knock: 3.0
                tipper-from: 2.0
                sour-under: 2.5
                parry-window-seconds: 0.17
                riposte-seconds: -1.0
                stagger-move-multiplier: 2.0
                feedback:
                  parry-sound: 'Not A Sound'
            """);
        final CombatRules wild = RevivalConfig.read(reader).combatRules("wild");

        assertEquals(20.0, wild.maxCps());
        assertFalse(wild.capsClicks()); // 20 a second: only the per-tick envelope limits
        assertEquals(1.0, wild.clickBurst());
        assertEquals(45.0, wild.influenceDegrees());
        assertEquals(2.0, wild.tipperKnock());
        assertEquals(2.0, wild.tipperFrom());
        assertEquals(2.0, wild.sourUnder()); // sour-under <= tipper-from
        assertEquals(3, wild.parryWindowTicks()); // 0.17 s is 3.4 ticks: reported, used rounded
        assertEquals(12, wild.riposteTicks()); // the base's
        assertEquals(1.0, wild.staggerMoveMultiplier());
        assertEquals(Identifier.withDefaultNamespace("item.shield.block"), wild.feedback().parry().sound());
        assertEquals(9, reader.problems().size());
    }

    @Test
    void wrongTypesFallBackAndAreReported() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            combat-rules:
              neue:
                parry: sometimes
                tipper-from: far
            """);
        final CombatRules neue = RevivalConfig.read(reader).combatRules("neue");

        assertTrue(neue.parry());
        assertEquals(2.6, neue.tipperFrom());
        assertEquals(2, reader.problems().size());
    }

    @Test
    void knockbackEditsKeepTheRules() throws InvalidConfigurationException {
        final RevivalSettings settings = RevivalConfig.read(reader("""
            world-settings:
              default:
                legacy-combat:
                  rules: neue
            """));

        final RevivalSettings edited = settings.withWorldProfile("default", "kohi").withProfile("kohi", KnockbackProfile.LEGACY_1_7);
        assertSame(CombatRules.NEUE, edited.legacyCombat().rules());
        assertEquals("neue", edited.legacyCombat().rulesName());
        assertSame(settings.combatRules(), edited.combatRules());
    }
}
