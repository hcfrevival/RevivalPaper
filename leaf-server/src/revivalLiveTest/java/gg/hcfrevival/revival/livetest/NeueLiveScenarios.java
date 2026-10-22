package gg.hcfrevival.revival.livetest;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.api.event.RevivalParryEvent;
import gg.hcfrevival.revival.auth.AuthMode;
import gg.hcfrevival.revival.auth.AuthPlayer;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.auth.PortSimulation;
import gg.hcfrevival.revival.auth.net.ClientStream;
import gg.hcfrevival.revival.auth.net.Wire;
import gg.hcfrevival.revival.auth.view.ClientView;
import gg.hcfrevival.revival.auth.view.WorldView;
import gg.hcfrevival.revival.combat.NeueCombat;
import gg.hcfrevival.revival.combat.NeueState;
import gg.hcfrevival.revival.config.CombatRules;
import gg.hcfrevival.revival.config.RevivalConfig;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

/**
 * Live scenarios for Neue combat (docs/revival/neue-combat.md): two honest 26.3 clients ({@link FakeClient}, each with the
 * real movement port as its client model) fight in a world switched to {@code legacy-combat.rules: neue} by editing
 * revival.yml and reloading, as an owner would. The whole set runs twice: with authority off (Neue's server-side path,
 * §11: server positions, the server clock) and under enforce (the judge's distance, the mirror landing, the parry clock
 * on client ticks, the lockout keyed to the recoil's ack). Then classic again, which must be 1.7 exactly.
 * <p>
 * The attacker stands with its back to a wall, so a recoil or a riposte can't throw it out of reach: every distance is
 * set by placement and the numbers are exact. The clients are ticked by a driver, one client tick per server tick, the
 * victim first, so what the victim does in a tick (a block, held keys) is processed before the attacker's click.
 */
final class NeueLiveScenarios implements Listener {

    private static final double EPSILON = 1.0E-3;
    private static final Input NONE = Input.EMPTY;
    private static final Input HOLD_LEFT = new Input(false, false, true, false, false, false, false);
    private static final String TEAM = "neuelive";
    /** The duel lane's x offset from the open lane. */
    private static final int DUEL = 4;
    private static final double DUEL_GAP = 2.4;

    private final RevivalLiveTests tests;
    private final MinecraftServer server;
    private final ServerLevel level;

    private Duelist attacker;
    private Duelist victim;
    private BukkitTask driver;
    private int bx;
    private int gy;
    private int bz;

    // What the server did, from its events
    private int parryEvents;
    private boolean recordFirstAttack;
    private long firstAttackTick = Long.MIN_VALUE;
    private boolean knockSeen;
    private double knockFromX;
    private double knockFromZ;
    private double knockToX;
    private double knockToZ;

    // The attacker clicks every client tick while set (the lockout's end, measured to the window)
    private boolean spam;

    // The held-click probe: the victim's damage window at each driver tick, and its value the tick before a hit landed
    private boolean probing;
    private float probeHealth;
    private int lastWindow;
    private int landedAfterWindow;

    // The parry round
    private long parryServerTick;
    private long correctionsBefore;
    private int parryRttTicks;
    private int victimMark;
    private int attackerMark;

    NeueLiveScenarios(final RevivalLiveTests tests, final MinecraftServer server, final ServerLevel level) {
        this.tests = tests;
        this.server = server;
        this.level = level;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onParry(final RevivalParryEvent event) {
        this.parryEvents++;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(final PrePlayerAttackEntityEvent event) {
        if (this.recordFirstAttack && this.attacker != null && event.getPlayer().getUniqueId().equals(this.attacker.player.getUUID())) {
            this.recordFirstAttack = false;
            this.spam = false;
            this.firstAttackTick = this.attackerTick();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKnock(final EntityKnockbackEvent event) {
        if (this.victim != null && event.getEntity().getUniqueId().equals(this.victim.player.getUUID())) {
            this.knockSeen = true;
            this.knockFromX = this.attacker.player.getX();
            this.knockFromZ = this.attacker.player.getZ();
            this.knockToX = this.victim.player.getX();
            this.knockToZ = this.victim.player.getZ();
        }
    }

    void register() {
        this.tests.after(5, "neue: setup", this::setup);
        this.block("off");
        if (Authority.refusal() == null) {
            this.tests.after(1, "neue: enforce", () -> {
                this.attacker.auth.setOverride(AuthMode.ENFORCE);
                this.victim.auth.setOverride(AuthMode.ENFORCE);
            });
            this.tests.after(40, "neue: enforce (active)", () -> {
                this.tests.check(this.attacker.auth.mode() == AuthMode.ENFORCE && this.victim.auth.mode() == AuthMode.ENFORCE,
                    "neue enforce: both clients under enforce (" + this.attacker.auth.mode() + ", " + this.victim.auth.mode() + ")");
                this.tests.check(this.attacker.auth.ownsClientState() && this.victim.auth.ownsClientState(), "neue enforce: the simulations own both players");
            });
            this.block("enforce");
        }
        this.tests.after(1, "neue: teardown", this::teardown);
    }

    // The whole set under one authority mode
    private void block(final String mode) {
        final String m = "neue " + mode + ": ";
        this.tests.after(1, m + "rules neue", () -> this.rules("neue"));

        // Tipper and sour (§6): eye to box 2.65, 2.2 and 1.2
        this.knock(m + "a hit from the edge of reach is a tipper", 2.95, 0.44, 1, 0);
        this.knock(m + "a hit from the middle is plain", 2.5, 0.4, 0, 0);
        this.knock(m + "a point-blank hit is sour", 1.5, 0.36, 0, 1);

        // DI (§7)
        this.influence(m + "DI bends the knock toward the victim's held keys");

        // Click buffer (§5)
        this.buffer(m + "a click into the window lands on the tick it opens", 8, true);
        this.buffer(m + "a click four or more ticks before the window opens expires", 2, false);

        // Parry, stagger, riposte (§8)
        this.parry(m + "a fresh block parries");
        this.blockHit(m + "a block-hit never parries");

        // Classic again: 1.7 exactly
        this.tests.after(30, m + "rules classic", () -> this.rules("classic"));
        this.knock(m + "classic: an edge-of-reach hit is 1.7's", 2.95, 0.4, 0, 0);
        this.buffer(m + "classic: a click into the window is lost", 8, false);
        this.classicBlock(m + "classic: a fresh block is a plain block");
    }

    private void setup() {
        final org.bukkit.Location spawn = this.level.getWorld().getSpawnLocation();
        this.bx = spawn.getBlockX() - 40;
        this.bz = spawn.getBlockZ() + 40;
        this.gy = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, this.bx, this.bz);
        // Two lanes. Both have a wall behind the attacker. The duel lane also has one behind the victim, 2.4 away, so a fresh
        // hit can't knock it out of reach of the next click; the open lane leaves the victim free for any distance
        for (int x = this.bx - 1; x <= this.bx + 1; x++) {
            for (int y = this.gy; y < this.gy + 3; y++) {
                this.level.setBlockAndUpdate(new BlockPos(x, y, this.bz - 1), Blocks.STONE.defaultBlockState());
                this.level.setBlockAndUpdate(new BlockPos(x + DUEL, y, this.bz - 1), Blocks.STONE.defaultBlockState());
                this.level.setBlockAndUpdate(new BlockPos(x + DUEL, y, this.bz + 3), Blocks.STONE.defaultBlockState());
            }
        }
        final net.minecraft.server.ServerScoreboard scoreboard = this.server.getScoreboard();
        net.minecraft.world.scores.PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM);
        }
        team.setCollisionRule(net.minecraft.world.scores.Team.CollisionRule.NEVER);
        this.attacker = new Duelist("NeueAttacker", this.attackerSpot(0), 0.0F);
        this.victim = new Duelist("NeueVictim", this.victimSpot(0, 2.5), 180.0F);
        this.server.server.getPluginManager().registerEvents(this, this.tests);
        this.driver = this.server.server.getScheduler().runTaskTimer(this.tests, this::drive, 1L, 1L);
        this.tests.check(this.attacker.auth.mode() == AuthMode.OFF, "neue: the duel starts outside authority");
    }

    private void teardown() {
        this.driver.cancel();
        this.rules("classic");
        this.attacker.fake.disconnect();
        this.victim.fake.disconnect();
        HandlerList.unregisterAll(this);
    }

    // One server tick: the probe, then the victim's client tick, then the attacker's
    private void drive() {
        if (this.probing) {
            if (this.victim.player.getHealth() < this.probeHealth) {
                this.probing = false;
                this.landedAfterWindow = this.lastWindow;
            } else {
                this.lastWindow = this.victim.player.damageCooldownTime;
            }
        }
        this.victim.tick();
        if (this.spam) {
            this.attacker.attack(this.victim);
        }
        this.attacker.tick();
    }

    private Vec3 attackerSpot(final int lane) {
        return new Vec3(this.bx + lane + 0.5, this.gy, this.bz + 0.3); // back of the box on the wall's face
    }

    private Vec3 victimSpot(final int lane, final double gap) {
        return new Vec3(this.bx + lane + 0.5, this.gy, this.bz + 0.3 + gap);
    }

    // In the open lane, `gap` apart
    private void place(final double gap) {
        this.place(0, gap);
    }

    // In the duel lane: the victim's back on its wall too, 2.4 apart (eye to box 2.1, plain)
    private void placeDuel() {
        this.place(DUEL, DUEL_GAP);
    }

    // Both back on their marks, standing, fresh windows and health; the victim's 1.7 shadow at rest
    private void place(final int lane, final double gap) {
        this.attacker.teleport(this.attackerSpot(lane), 0.0F);
        this.victim.teleport(this.victimSpot(lane, gap), 180.0F);
        this.victim.keys = NONE;
        for (final Duelist duelist : new Duelist[] {this.attacker, this.victim}) {
            final ServerPlayer player = duelist.player;
            player.setHealth(20.0F);
            player.invulnerableDuration = 20;
            player.damageCooldownTime = 0;
            player.hurtTime = 0;
            player.lastHurt = 0.0F;
            player.revivalDamageWindows = null;
            player.revivalMotionShadow.set(new Vec3(0.0, -0.0784000015258789, 0.0));
            // No natural regeneration (it needs food 18), so health counts damage alone
            player.getFoodData().setFoodLevel(17);
            player.getFoodData().setSaturation(0.0F);
        }
    }

    private long attackerTick() {
        return this.attacker.auth.ownsClientState() ? this.attacker.auth.tick() : MinecraftServer.currentTick;
    }

    // Tipper and sour -------------------------------------------------------------------------------------------------

    private void knock(final String name, final double gap, final double expectedZ, final int tippers, final int sours) {
        final long[] before = new long[2];
        this.tests.after(30, name + " (place)", () -> this.place(gap));
        this.tests.after(15, name + " (hit)", () -> {
            before[0] = this.state().tippers();
            before[1] = this.state().sours();
            this.victimMark = this.victim.fake.received.size();
            this.attacker.attack(this.victim);
        });
        this.tests.after(3, name, () -> {
            final List<Vec3> motions = this.motions(this.victim, this.victimMark);
            this.tests.check(motions.size() == 1 && near(motions.getFirst(), 0.0, 0.3608, expectedZ), name + ": the knock is (0, 0.3608, " + expectedZ + "): " + motions);
            this.tests.check(this.state().tippers() - before[0] == tippers && this.state().sours() - before[1] == sours,
                name + ": counted " + (this.state().tippers() - before[0]) + " tippers, " + (this.state().sours() - before[1]) + " sours");
        });
    }

    // DI ---------------------------------------------------------------------------------------------------------------

    private void influence(final String name) {
        this.tests.after(30, name + " (place)", () -> this.place(2.5));
        // The victim faces -Z (yaw 180) and holds A, its left, -X: from the tick before the click, so the click's window
        // reads it whichever of the two windows runs first
        this.tests.after(15, name + " (hold A)", () -> this.victim.keys = HOLD_LEFT);
        this.tests.after(1, name + " (hit)", () -> {
            this.victimMark = this.victim.fake.received.size();
            this.knockSeen = false;
            this.attacker.attack(this.victim);
        });
        this.tests.after(1, name + " (release)", () -> this.victim.keys = NONE);
        this.tests.after(3, name, () -> {
            final List<Vec3> motions = this.motions(this.victim, this.victimMark);
            this.tests.check(motions.size() == 1 && this.knockSeen, name + ": one knock (" + motions + ")");
            final Vec3 knock = motions.getFirst();
            // Bent relative to straight away from the attacker, where the two stood when the knock was decided
            final double away = Math.toDegrees(Math.atan2(this.knockToX - this.knockFromX, this.knockToZ - this.knockFromZ));
            final double bend = Math.toDegrees(Math.atan2(knock.x, knock.z)) - away;
            this.tests.check(Math.abs(bend + 15.0) < 0.1, name + ": bent " + bend + " degrees toward the held direction (expected -15): " + knock);
            this.tests.check(Math.abs(Math.hypot(knock.x, knock.z) - 0.4) < EPSILON && Math.abs(knock.y - 0.3608) < EPSILON, name + ": length and height kept: " + knock);
        });
    }

    // Click buffer -----------------------------------------------------------------------------------------------------

    // A fresh hit, then a second click `after` ticks later into the shut window. Under neue with `lands`, exactly one more
    // fresh hit follows, in the server tick whose level phase opened the window: the window read 11 at the driver tick
    // before (one decrement from open). Otherwise nothing lands
    private void buffer(final String name, final int after, final boolean lands) {
        final long[] before = new long[2];
        this.tests.after(30, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (fresh hit)", () -> this.attacker.attack(this.victim));
        this.tests.after(after, name + " (click into the window)", () -> {
            this.tests.check(this.victim.player.getHealth() == 12.0F, name + ": the first hit dealt 8 (health " + this.victim.player.getHealth() + ")");
            before[0] = this.state().bufferedLanded();
            before[1] = this.state().bufferExpired();
            this.attacker.attack(this.victim);
            this.probing = true;
            this.probeHealth = this.victim.player.getHealth();
            this.lastWindow = this.victim.player.damageCooldownTime;
            this.landedAfterWindow = Integer.MIN_VALUE;
        });
        this.tests.after(16, name, () -> {
            this.probing = false;
            final CombatRules rules = Revival.settings().legacyCombat(this.level.getWorld().getName()).rules();
            if (lands && rules.buffersClicks()) {
                this.tests.check(this.victim.player.getHealth() == 4.0F, name + ": exactly one more fresh hit landed (health " + this.victim.player.getHealth() + ")");
                this.tests.check(this.landedAfterWindow == this.victim.player.invulnerableDuration / 2 + 1,
                    name + ": it landed in the tick the window opened (the window read " + this.landedAfterWindow + " the tick before; open at "
                        + this.victim.player.invulnerableDuration / 2 + ")");
                this.tests.check(this.state().bufferedLanded() == before[0] + 1, name + ": flagged buffered (" + this.state().bufferedLanded() + ")");
            } else {
                this.tests.check(this.victim.player.getHealth() == 12.0F, name + ": nothing landed (health " + this.victim.player.getHealth() + ")");
                this.tests.check(this.state().bufferedLanded() == before[0], name + ": no buffered landing");
                if (rules.buffersClicks()) {
                    this.tests.check(this.state().bufferExpired() == before[1] + 1, name + ": the held click expired (" + this.state().bufferExpired() + ")");
                }
            }
        });
    }

    // Parry, stagger, riposte ------------------------------------------------------------------------------------------

    private void parry(final String name) {
        this.tests.after(40, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (block)", () -> this.victim.use());
        this.tests.after(1, name + " (click)", () -> {
            // The click meets the block one tick old, inside its 3-tick window
            this.parryEvents = 0;
            this.victimMark = this.victim.fake.received.size();
            this.attackerMark = this.attacker.fake.received.size();
            this.correctionsBefore = this.attacker.auth.stats().corrections();
            this.attacker.attack(this.victim);
            this.parryServerTick = MinecraftServer.currentTick + 1;
            this.parryRttTicks = (Math.max(0, this.attacker.player.connection.latency()) + 49) / 50;
        });
        this.tests.after(1, name, () -> {
            this.tests.check(this.parryEvents == 1, name + ": RevivalParryEvent fired (" + this.parryEvents + ")");
            final ServerPlayer v = this.victim.player;
            this.tests.check(v.getHealth() == 20.0F && v.damageCooldownTime == 0 && v.hurtTime == 0,
                name + ": the parrier takes nothing, opens no window, doesn't flash (health " + v.getHealth() + ", window " + v.damageCooldownTime + ")");
            this.tests.check(this.motions(this.victim, this.victimMark).isEmpty(), name + ": the parrier isn't knocked");
            this.tests.check(NeueCombat.state(v).parryUsed(), name + ": once per block");
            this.tests.check(NeueCombat.riposteReady(v), name + ": the parrier's riposte is ready");
            final List<Vec3> recoil = this.motions(this.attacker, this.attackerMark);
            this.tests.check(recoil.size() == 1 && near(recoil.getFirst(), 0.0, 0.22, -0.45), name + ": stagger: thrown back (0, 0.22, -0.45): " + recoil);
            final List<ClientboundCooldownPacket> cooldowns = this.attacker.fake.receivedOfType(ClientboundCooldownPacket.class, this.attackerMark);
            this.tests.check(cooldowns.size() == 1 && cooldowns.getFirst().duration() == 10 && cooldowns.getFirst().cooldownGroup().getPath().equals("diamond_sword"),
                name + ": stagger: the sword's cooldown sweep for 10 ticks: " + cooldowns);
            this.tests.check(this.attacker.player.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(NeueCombat.STAGGER_SLOW), name + ": stagger: slowed");
            this.tests.check(this.attacker.fake.receivedOfType(ClientboundSoundPacket.class, this.attackerMark).stream()
                .anyMatch(sound -> sound.getSound().value().location().getPath().equals("item.shield.break")), name + ": stagger: the attacker hears the guard break");
            this.tests.check(NeueCombat.lockedOut(this.attacker.player), name + ": stagger: locked out");
            // The victim drops the block (a click while it's up would be discarded), the attacker clicks every tick
            this.victim.release();
            this.firstAttackTick = Long.MIN_VALUE;
            this.recordFirstAttack = true;
            this.spam = true;
        });
        this.tests.after(1, name + " (riposte)", () -> {
            this.attacker.player.setHealth(20.0F);
            this.attacker.player.damageCooldownTime = 0;
            this.attacker.player.lastHurt = 0.0F;
            this.attackerMark = this.attacker.fake.received.size();
            this.victimMark = this.victim.fake.received.size();
            this.victim.attack(this.attacker);
        });
        this.tests.after(2, name + ": riposte", () -> {
            this.tests.check(this.attacker.player.getHealth() == 8.0F, name + ": riposte: a guaranteed crit, 8 x 1.5 = 12 (health " + this.attacker.player.getHealth() + ")");
            this.tests.check(this.attacker.fake.receivedOfType(ClientboundAnimatePacket.class, this.attackerMark).stream()
                    .anyMatch(animate -> animate.getAction() == ClientboundAnimatePacket.CRITICAL_HIT)
                    || this.victim.fake.receivedOfType(ClientboundAnimatePacket.class, this.victimMark).stream()
                    .anyMatch(animate -> animate.getAction() == ClientboundAnimatePacket.CRITICAL_HIT),
                name + ": riposte: the vanilla crit cue");
            this.tests.check(!NeueCombat.riposteReady(this.victim.player), name + ": riposte: used up");
            this.tests.check(this.victim.player.getHealth() == 20.0F, name + ": the attacker's clicks are still refused (victim health " + this.victim.player.getHealth() + ")");
        });
        this.tests.after(25, name + ": the stagger ends after its length", () -> {
            this.spam = false;
            this.recordFirstAttack = false;
            final NeueState state = this.state();
            if (this.attacker.auth.ownsClientState()) {
                // The lockout runs from the window that acked the recoil: windows T_s ... T_s + 9 refused, T_s + 10 lands
                this.tests.check(state.staggerAck() != NeueState.NEVER, name + ": the recoil's ack tick is known (" + state.staggerAck() + ")");
                this.tests.check(this.firstAttackTick == state.staggerAck() + 10,
                    name + ": the first attack delivered was in window " + this.firstAttackTick + ", the recoil acked in " + state.staggerAck() + " (expected +10)");
            } else {
                // Outside authority the round trip stands in for the ack
                this.tests.check(this.firstAttackTick == this.parryServerTick + this.parryRttTicks + 10,
                    name + ": the first attack delivered was at server tick " + this.firstAttackTick + ", parried at " + this.parryServerTick
                        + " with a round trip of " + this.parryRttTicks + " ticks (expected +" + (this.parryRttTicks + 10) + ")");
            }
            this.tests.check(this.victim.player.getHealth() == 12.0F, name + ": that attack landed (victim health " + this.victim.player.getHealth() + ")");
            this.tests.check(!NeueCombat.lockedOut(this.attacker.player), name + ": stagger: over");
            this.tests.check(!this.attacker.player.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(NeueCombat.STAGGER_SLOW), name + ": stagger: the slow is off");
            this.tests.check(this.attacker.auth.stats().corrections() == this.correctionsBefore,
                name + ": the staggered client needed no correction (" + (this.attacker.auth.stats().corrections() - this.correctionsBefore) + ")");
        });
    }

    private void blockHit(final String name) {
        this.tests.after(40, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (swing)", () -> this.victim.attack(this.attacker)); // the swing of a block-hit
        this.tests.after(2, name + " (block)", () -> this.victim.use()); // two ticks after the swing: a block-hit
        this.tests.after(1, name + " (click)", () -> {
            this.parryEvents = 0;
            this.attacker.attack(this.victim);
        });
        this.tests.after(2, name, () -> {
            this.tests.check(this.victim.player.isBlocking() && !NeueCombat.state(this.victim.player).mayParry(),
                name + ": the block is up with no window (" + NeueCombat.state(this.victim.player).parryWindow() + ")");
            this.tests.check(this.parryEvents == 0 && this.victim.player.getHealth() == 15.5F,
                name + ": a plain 1.7 block, (8 + 1) x 0.5 = 4.5 (health " + this.victim.player.getHealth() + ")");
            this.victim.release();
        });
    }

    private void classicBlock(final String name) {
        this.tests.after(40, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (block)", () -> this.victim.use());
        this.tests.after(1, name + " (click)", () -> {
            this.parryEvents = 0;
            this.attackerMark = this.attacker.fake.received.size();
            this.attacker.attack(this.victim);
        });
        this.tests.after(2, name, () -> {
            this.tests.check(this.parryEvents == 0 && this.victim.player.getHealth() == 15.5F, name + ": a plain 1.7 block (health " + this.victim.player.getHealth() + ")");
            this.tests.check(this.motions(this.attacker, this.attackerMark).isEmpty() && !NeueCombat.state(this.attacker.player).staggered(), name + ": no stagger");
            this.victim.release();
        });
    }

    // Helpers ----------------------------------------------------------------------------------------------------------

    private void rules(final String name) {
        try {
            final File file = new File("revival.yml");
            final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            yaml.set("world-settings.default.legacy-combat.rules", name);
            yaml.save(file);
        } catch (final java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        RevivalConfig.reload();
        final CombatRules rules = Revival.settings().legacyCombat(this.level.getWorld().getName()).rules();
        this.tests.check(rules == (name.equals("neue") ? CombatRules.NEUE : CombatRules.CLASSIC), "legacy-combat.rules is " + name + " after /revival reload");
    }

    private NeueState state() {
        return NeueCombat.state(this.attacker.player);
    }

    private List<Vec3> motions(final Duelist duelist, final int from) {
        final List<Vec3> out = new ArrayList<>();
        for (final ClientboundSetEntityMotionPacket motion : duelist.fake.receivedOfType(ClientboundSetEntityMotionPacket.class, from)) {
            if (motion.id() == duelist.player.getId()) {
                out.add(motion.movement());
            }
        }
        return out;
    }

    private static boolean near(final Vec3 motion, final double x, final double y, final double z) {
        return Math.abs(motion.x - x) < EPSILON && Math.abs(motion.y - y) < EPSILON && Math.abs(motion.z - z) < EPSILON;
    }

    /**
     * An honest 26.3 client: what the server writes is applied as the client would (teleports, its own velocity and
     * attributes, entity data, inventory, cooldowns, chunks), its keybind actions carry the client's own predictions, and
     * the real movement port moves it. One client tick per {@link #tick}.
     */
    private final class Duelist {
        final FakeClient fake;
        final ServerPlayer player;
        final AuthPlayer auth;
        final ClientView view = new ClientView();
        final WorldView world = new WorldView();
        final PortSimulation model;
        final List<Packet<? super ServerGamePacketListener>> actions = new ArrayList<>();
        Input keys = NONE;
        private Input previousKeys = NONE;
        float yaw;
        private int seen;
        private int sequence;
        private final ClientView.EchoSink echo = new ClientView.EchoSink() {
            @Override
            public void sharedFlags(final byte flags) {
                Duelist.this.model.echoSharedFlags(flags);
            }

            @Override
            public void pose(final Pose pose) {
                Duelist.this.model.echoPose(pose);
            }

            @Override
            public void usingItem(final boolean using, final InteractionHand hand) {
                Duelist.this.model.echoUsingItem(using, hand);
            }
        };

        Duelist(final String name, final Vec3 at, final float yaw) {
            this.fake = new FakeClient(NeueLiveScenarios.this.server, NeueLiveScenarios.this.level, name);
            this.player = this.fake.player;
            this.auth = this.player.revivalAuth;
            final net.minecraft.server.ServerScoreboard scoreboard = NeueLiveScenarios.this.server.getScoreboard();
            scoreboard.addPlayerToTeam(this.player.getScoreboardName(), scoreboard.getPlayerTeam(TEAM));
            this.auth.setOverride(AuthMode.OFF);
            this.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            this.model = new PortSimulation(this.player);
            this.yaw = yaw;
            this.teleport(at, yaw);
        }

        void teleport(final Vec3 at, final float yaw) {
            this.yaw = yaw;
            this.player.connection.teleport(at.x, at.y, at.z, yaw, 0.0F);
        }

        void attack(final Duelist target) {
            this.actions.add(new ServerboundAttackPacket(target.player.getId()));
            this.actions.add(new ServerboundPunchPacket());
        }

        void use() {
            this.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, ++this.sequence, this.yaw, 0.0F));
        }

        void release() {
            this.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
        }

        void tick() {
            if (this.world.level() == null) {
                this.view.seed(this.player);
                this.world.setLevel(NeueLiveScenarios.this.level, true);
                this.world.seedChunks(this.player.moonrise$getChunkLoader().getSentChunksRaw());
                final Vec3 at = this.fake.clientPosition();
                this.model.seed(at.x, at.y, at.z, 0.0, 0.0, 0.0, this.yaw, 0.0F, this.player.onGround(), this.view, this.world);
            }
            this.ingest();
            // The keybinds and their predictions, before the tick moves (Minecraft.handleKeybinds)
            final List<Packet<? super ServerGamePacketListener>> sent = List.copyOf(this.actions);
            this.actions.clear();
            for (final Packet<? super ServerGamePacketListener> action : sent) {
                switch (action) {
                    case final ServerboundAttackPacket ignored -> this.model.clientAttack(true, this.view);
                    case final ServerboundUseItemPacket use -> {
                        if (Boolean.TRUE.equals(this.view.predictsUse(this.view.items.hand(use.hand())))) {
                            this.model.startUsingItem(use.hand());
                        }
                    }
                    case final ServerboundPlayerActionPacket release when release.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM ->
                        this.model.releaseUsingItem();
                    default -> {
                    }
                }
            }
            if (this.fake.loaded()) {
                this.model.tick(this.keys, this.previousKeys, this.yaw, 0.0F, ClientStream.SPRINT_NONE, -1, false, this.view, this.world);
            }
            this.previousKeys = this.keys;
            this.view.tick();
            this.fake.clientTick(sent, this.keys, this.model.sprinting(), new Vec3(this.model.x(), this.model.y(), this.model.z()), this.yaw, 0.0F,
                this.model.onGround(), this.model.horizontalCollision());
        }

        // ClientPacketListener for what moves or equips this client, in arrival order
        private void ingest() {
            final int self = this.player.getId();
            for (; this.seen < this.fake.received.size(); this.seen++) {
                switch (this.fake.received.get(this.seen).packet()) {
                    case final ClientboundPlayerPositionPacket position -> {
                        this.model.position(position.change(), position.relatives());
                        this.yaw = this.model.yRot();
                    }
                    case final ClientboundSetEntityMotionPacket motion when motion.id() == self -> {
                        final double[] decoded = new double[3];
                        Wire.lpVec3(motion.movement().x, motion.movement().y, motion.movement().z, decoded);
                        this.model.setVelocity(decoded[0], decoded[1], decoded[2]);
                    }
                    case final ClientboundUpdateAttributesPacket attributes when attributes.getEntityId() == self -> {
                        this.view.attributes.apply(attributes.getValues());
                        this.model.attributes(attributes.getValues());
                    }
                    case final ClientboundSetEntityDataPacket data when data.id() == self -> this.view.entityData(data.packedItems(), this.echo);
                    case final ClientboundPlayerAbilitiesPacket abilities -> this.view.abilities(abilities);
                    case final ClientboundSetHealthPacket health -> this.view.health(health);
                    case final ClientboundCooldownPacket cooldown -> this.view.cooldown(cooldown.cooldownGroup(), cooldown.duration());
                    case final ClientboundSetPlayerInventoryPacket slot -> this.view.items.setInventory(slot.slot(), slot.contents());
                    case final ClientboundContainerSetSlotPacket slot when slot.getContainerId() == 0 -> this.view.items.setMenuSlot(slot.getSlot(), slot.getItem());
                    case final ClientboundContainerSetContentPacket content when content.containerId() == 0 -> this.view.items.setMenuContent(content.items());
                    case final ClientboundSetHeldSlotPacket held -> this.view.items.select(held.slot());
                    case final ClientboundLevelChunkWithLightPacket chunk -> this.world.onChunk(chunk.x(), chunk.z());
                    case final ClientboundForgetLevelChunkPacket forget -> this.world.onForgetChunk(forget.pos().x(), forget.pos().z());
                    default -> {
                    }
                }
            }
        }
    }
}
