package gg.hcfrevival.revival.livetest;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.api.event.RevivalParryEvent;
import gg.hcfrevival.revival.auth.AuthMode;
import gg.hcfrevival.revival.auth.AuthPlayer;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.auth.CombatWiring;
import gg.hcfrevival.revival.auth.PortSimulation;
import gg.hcfrevival.revival.auth.Simulation;
import gg.hcfrevival.revival.auth.combat.RemoteMirror;
import gg.hcfrevival.revival.auth.net.ClientStream;
import gg.hcfrevival.revival.auth.net.Wire;
import gg.hcfrevival.revival.auth.reconcile.Reconciler;
import gg.hcfrevival.revival.auth.view.ClientView;
import gg.hcfrevival.revival.auth.view.WorldView;
import gg.hcfrevival.revival.combat.LegacyAttack;
import gg.hcfrevival.revival.combat.NeueCombat;
import gg.hcfrevival.revival.combat.NeueState;
import gg.hcfrevival.revival.config.CombatRules;
import gg.hcfrevival.revival.config.RevivalConfig;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
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
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Live scenarios for Neue combat (docs/revival/neue-combat.md): two honest 26.3 clients ({@link FakeClient}, each with the
 * real movement port as its client model) fight in a world switched to {@code legacy-combat.rules: neue} by editing
 * revival.yml and reloading, as an owner would. The whole set runs twice: with authority off (Neue's server-side path,
 * §11: server positions, the server clock) and under enforce (the judge's distance, the mirror landing, the parry clock
 * on client ticks, the lockout keyed to the recoil's ack). Then classic again, which must be 1.7 exactly, and
 * {@code classic-capped}: 1.7 exactly but for Neue's click cap and click buffer.
 * <p>
 * The attacker stands with its back to a wall, so a recoil or a riposte can't throw it out of reach: every distance is
 * set by placement and the numbers are exact. The clients are ticked by a driver, one client tick per server tick, the
 * victim first, so what the victim does in a tick (a block, held keys) is processed before the attacker's click. The
 * driver is a scheduler task created before every step, so in a tick where both run it runs first (CraftScheduler orders
 * by creation): a step's packets go out at the next tick's driver, and a step reads what the driver just did.
 * <p>
 * Health is the measure, so the world runs peaceful with no natural regeneration while the set runs: outside peaceful
 * the flat world spawns slimes that hit the duelists, and peaceful heals 1 every 20 ticks whatever the food level (the
 * sync run inherits peaceful from {@code AuthLiveScenarios}). Both are put back at the end.
 * <p>
 * Under enforce a third client, 450 ms away, checks the rewind cap's Overwatch rule: a view older than the cap is judged
 * against the target's truth at the capped age, so leading a crossing target lands and aiming where it shows misses.
 */
final class NeueLiveScenarios implements Listener {

    private static final double EPSILON = 1.0E-3;
    private static final Input NONE = Input.EMPTY;
    private static final Input HOLD_LEFT = new Input(false, false, true, false, false, false, false);
    private static final Input SNEAK_FORWARD = new Input(true, false, false, false, false, true, false);
    private static final String TEAM = "neuelive";
    /** The duel lane's x offset from the open lane. */
    private static final int DUEL = 4;
    private static final double DUEL_GAP = 2.4;
    /** The crossing for the rewind-cap control: this far +z of the lanes, clear of their walls. */
    private static final int CROSSING = 10;
    /** The shooter's latency: every clientbound packet reaches it 9 ticks late (450 ms, as the cheat suite counts). */
    private static final int SHOOTER_LATENCY_TICKS = 9;

    private final RevivalLiveTests tests;
    private final MinecraftServer server;
    private final ServerLevel level;

    private Duelist attacker;
    private Duelist victim;
    private Shooter shooter;
    private BukkitTask driver;
    private Difficulty difficulty;
    private boolean regeneration;
    private int bx;
    private int gy;
    private int bz;

    // What the server did, from its events
    private int parryEvents;
    private boolean recordFirstAttack;
    private long firstAttackTick = Long.MIN_VALUE;
    private long firstAttackServerTick = Long.MIN_VALUE;
    // The honest control's lockout, parry to first delivered attack in server ticks, for the 3x-speed cheat (L3)
    private long honestLockoutTicks = Long.MIN_VALUE;
    private boolean firstAttackCrit;
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

    // The click-cap rounds: the attacker clicks in the client ticks this admits (counted from its start); with
    // cancelVictimDamage the victim's damage is cancelled, so no damage window turns a click away and every attack past the
    // cap reaches the damage event
    private java.util.function.IntPredicate clicking;
    private int clickCount;
    private boolean cancelVictimDamage;
    private int damageEvents;

    // The early-click probe: the server tick the click into the window was made in, and the tick whose level phase opened
    // the window (the driver read 11 at its start)
    private boolean clickArmed;
    private long clickServerTick = Long.MIN_VALUE;
    private long openServerTick = Long.MIN_VALUE;

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
        // The round trip the stagger takes, read when it does: a keep-alive answered in the click's own tick moves it
        this.parryRttTicks = (Math.max(0, this.attacker.player.connection.latency()) + 49) / 50;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(final PrePlayerAttackEntityEvent event) {
        if (this.clickArmed && this.attacker != null && event.getPlayer().getUniqueId().equals(this.attacker.player.getUUID())) {
            this.clickArmed = false;
            this.clickServerTick = MinecraftServer.currentTick;
        }
        if (this.recordFirstAttack && this.attacker != null && event.getPlayer().getUniqueId().equals(this.attacker.player.getUUID())) {
            this.recordFirstAttack = false;
            this.spam = false;
            this.firstAttackTick = this.attackerTick();
            this.firstAttackServerTick = MinecraftServer.currentTick;
            this.attacker.rate = Math.min(this.attacker.rate, 1); // a timer cheat stops running ahead once it has its hit
            // Still falling from the riposte's knock, it is a 1.7 crit (under enforce the lockout ends mid-air)
            this.firstAttackCrit = LegacyAttack.canCriticalAttack(this.attacker.player, this.victim.player);
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

    // Every attack of the attacker's that reached the victim's damage event, cancelled or not
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (this.victim != null && event.getEntity().getUniqueId().equals(this.victim.player.getUUID())
            && event.getDamager().getUniqueId().equals(this.attacker.player.getUUID())) {
            this.damageEvents++;
            if (this.cancelVictimDamage) {
                event.setCancelled(true);
            }
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
        this.influence(m + "DI bends the knock toward the victim's held keys", -15.0);

        // Click buffer (§5)
        this.buffer(m + "a click into the window lands on the tick it opens", 8, true);
        this.buffer(m + "a click four or more ticks before the window opens expires", 2, false);

        // Parry, stagger, riposte (§8)
        this.parry(m + "a fresh block parries");
        this.blockInTheParriedWindow(m + "a block pressed in the parried click's own window leaves no ghost block", false);
        this.blockInTheParriedWindow(m + "a block pressed before the cooldown reached the client leaves no ghost block", true);
        this.staggerSwapBlock(m + "staggered, another sword type can't block, so the riposte lands");
        this.staggerPickedUpSword(m + "staggered, a sword picked up during the lockout can't block either");
        if (mode.equals("off")) {
            // A block up while staggered needs an attack sent while blocking, which the input grammar drops under enforce
            this.staggerKeepsBlock(m + "staggered with the block still up (stagger-lowers-block false), it doesn't parry the riposte");
        }
        this.blockHit(m + "a block-hit never parries");
        this.blockHitNoPunch(m + "a block-hit whose click sent no Punch never parries");

        // Classic again: 1.7 exactly
        this.tests.after(30, m + "rules classic", () -> this.rules("classic"));
        this.knock(m + "classic: an edge-of-reach hit is 1.7's", 2.95, 0.4, 0, 0);
        this.buffer(m + "classic: a click into the window is lost", 8, false);
        this.classicBlock(m + "classic: a fresh block is a plain block");
        this.classicCooldownRefusal(m + "classic: a sword or a pearl on cooldown is refused without a resync");

        // Classic capped, the ruleset for now: 1.7 exactly but for the click cap and the click buffer
        this.tests.after(30, m + "rules classic-capped", () -> this.rules("classic-capped"));
        this.clicker(m + "classic-capped: an even 12 CPS clicker keeps every click", false);
        this.clicker(m + "classic-capped: a locked 20 CPS clicker delivers at most 12 a second plus the burst", true);
        this.bufferEarly(m + "classic-capped: a click 1 tick before the window opens lands on the opening tick", 1, true);
        this.bufferEarly(m + "classic-capped: a click 2 ticks before the window opens lands on the opening tick", 2, true);
        this.bufferEarly(m + "classic-capped: a click 3 ticks before the window opens lands on the opening tick", 3, true);
        this.bufferEarly(m + "classic-capped: a click 4 ticks before the window opens expires", 4, false);
        this.knock(m + "classic-capped: an edge-of-reach hit is 1.7's, no tipper", 2.95, 0.4, 0, 0);
        this.knock(m + "classic-capped: a point-blank hit is 1.7's, no sour", 1.5, 0.4, 0, 0);
        this.influence(m + "classic-capped: held keys don't bend the knock, no DI", 0.0);
        this.classicBlock(m + "classic-capped: a hit on a fresh, rested block is a plain block, no parry or stagger");
        this.classicCooldownRefusal(m + "classic-capped: a sword or a pearl on cooldown is refused without a resync");

        // Last of the duel: a client that ran ahead leaves its windows waiting on the tick clock for a while (under
        // enforce), which the rounds above don't want
        this.tests.after(30, m + "rules neue (the timer cheat)", () -> this.rules("neue"));
        this.staggerFast(m + "a client running the lockout 3x fast doesn't end it early");
        this.tests.after(1, m + "rules classic (after the timer cheat)", () -> this.rules("classic"));

        if (mode.equals("enforce")) {
            this.pastTheCap(m + "past the rewind cap (450 ms)");
        }
    }

    private void setup() {
        this.difficulty = this.level.getDifficulty();
        this.regeneration = this.level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
        this.server.setDifficulty(this.level, Difficulty.PEACEFUL, null, true); // the slimes already here despawn on their next tick
        this.level.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, false, this.level);
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
        this.attacker.player.getInventory().setItem(1, new ItemStack(Items.IRON_SWORD)); // another sword type, for L4
        this.victim = new Duelist("NeueVictim", this.victimSpot(0, 2.5), 180.0F);
        this.server.server.getPluginManager().registerEvents(this, this.tests);
        this.driver = this.server.server.getScheduler().runTaskTimer(this.tests, this::drive, 1L, 1L);
        this.tests.check(this.attacker.auth.mode() == AuthMode.OFF, "neue: the duel starts outside authority");
        this.tests.check(this.level.getDifficulty() == Difficulty.PEACEFUL && !this.level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION),
            "neue: peaceful with no natural regeneration, so health counts hits alone");
    }

    private void teardown() {
        this.driver.cancel();
        this.rules("classic");
        if (this.shooter != null) {
            this.shooter.fake.disconnect();
            this.shooter = null;
        }
        this.attacker.fake.disconnect();
        this.victim.fake.disconnect();
        HandlerList.unregisterAll(this);
        this.level.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, this.regeneration, this.level);
        this.server.setDifficulty(this.level, this.difficulty, null, true);
    }

    // One server tick: the probe, then the victim's client tick, then the attacker's, then the shooter's
    private void drive() {
        if (this.probing) {
            if (this.victim.player.getHealth() < this.probeHealth) {
                this.probing = false;
                this.landedAfterWindow = this.lastWindow;
            } else {
                this.lastWindow = this.victim.player.damageCooldownTime;
                if (this.lastWindow == this.victim.player.invulnerableDuration / 2 + 1 && this.openServerTick == Long.MIN_VALUE) {
                    this.openServerTick = MinecraftServer.currentTick; // this tick's level phase opens it
                }
            }
        }
        this.victim.tick();
        for (int i = 0; i < this.attacker.rate; i++) {
            if (this.spam) {
                // The crosshair kept on the victim: the recoil and the riposte's knock lift the attacker, and under enforce
                // a level ray from up there passes over the victim's head (the judge would call it a miss, not the lockout)
                this.attacker.aimAt(this.victim);
                this.attacker.attack(this.victim);
            } else if (this.clicking != null && this.clicking.test(this.clickCount++)) {
                this.attacker.attack(this.victim);
            }
            this.attacker.tick();
        }
        if (this.shooter != null) {
            this.shooter.tick();
        }
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
        this.attacker.pitch = 0.0F;
        reset(this.attacker.player);
        reset(this.victim.player);
    }

    private static void reset(final ServerPlayer player) {
        player.setHealth(20.0F);
        player.invulnerableDuration = 20;
        player.damageCooldownTime = 0;
        player.hurtTime = 0;
        player.lastHurt = 0.0F;
        player.revivalDamageWindows = null;
        player.revivalMotionShadow.set(new Vec3(0.0, -0.0784000015258789, 0.0));
        // Natural regeneration is off for the set (setup); below 18 food it wouldn't heal either way
        player.getFoodData().setFoodLevel(17);
        player.getFoodData().setSaturation(0.0F);
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

    private void influence(final String name, final double expectedBend) {
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
            this.tests.check(Math.abs(bend - expectedBend) < 0.1, name + ": bent " + bend + " degrees toward the held direction (expected " + expectedBend + "): " + knock);
            this.tests.check(Math.abs(Math.hypot(knock.x, knock.z) - 0.4) < EPSILON && Math.abs(knock.y - 0.3608) < EPSILON, name + ": length and height kept: " + knock);
        });
    }

    // Click buffer -----------------------------------------------------------------------------------------------------

    // A fresh hit, then a second click `after` ticks later into the shut window. Under neue with `lands`, exactly one more
    // fresh hit follows, in the server tick whose level phase opened the window: the window read 11 at the driver tick
    // before (one decrement from open). Otherwise nothing lands
    private void buffer(final String name, final int after, final boolean lands) {
        this.buffer(name, after, lands, -1);
    }

    // The same with the click made `early` ticks before the window opens: `after` is 9 − early (the fresh hit's window opens
    // in the tenth server tick after its step), and the round checks the tick it measured
    private void bufferEarly(final String name, final int early, final boolean lands) {
        this.buffer(name, 9 - early, lands, early);
    }

    private void buffer(final String name, final int after, final boolean lands, final int early) {
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
            this.clickArmed = true;
            this.clickServerTick = Long.MIN_VALUE;
            this.openServerTick = Long.MIN_VALUE;
        });
        this.tests.after(16, name, () -> {
            this.probing = false;
            this.clickArmed = false;
            if (early >= 0) {
                this.tests.check(this.openServerTick - this.clickServerTick == early, name + ": the click was made " + early
                    + " ticks before the window opened (made in server tick " + this.clickServerTick + ", opened in " + this.openServerTick + ")");
            }
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
            this.parryServerTick = MinecraftServer.currentTick + 1; // the driver sends it next tick; onParry reads the round trip
            this.parryRttTicks = Integer.MIN_VALUE;
        });
        this.tests.after(1, name, () -> {
            this.tests.check(this.parryEvents == 1, name + ": RevivalParryEvent fired (" + this.parryEvents + ")");
            final ServerPlayer v = this.victim.player;
            this.tests.check(v.getHealth() == 20.0F && v.damageCooldownTime == 0 && v.hurtTime == 0,
                name + ": the parrier takes nothing, opens no window, doesn't flash (health " + v.getHealth() + ", window " + v.damageCooldownTime + ")");
            this.tests.check(NeueCombat.state(v).parryUsed(), name + ": once per block");
            this.tests.check(NeueCombat.riposteReady(v), name + ": the parrier's riposte is ready");
            this.tests.check(this.attacker.player.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(NeueCombat.STAGGER_SLOW), name + ": stagger: slowed");
            this.tests.check(NeueCombat.lockedOut(this.attacker.player), name + ": stagger: locked out");
            // The victim drops the block (a click while it's up would be discarded), the attacker clicks every tick
            this.victim.release();
            this.firstAttackTick = Long.MIN_VALUE;
            this.recordFirstAttack = true;
            this.spam = true;
        });
        this.tests.after(1, name + " (riposte)", () -> {
            // What the parry sent, read a tick on: under authority a tick's packets go out in its bundle at the tick's end
            this.tests.check(this.motions(this.victim, this.victimMark).isEmpty(), name + ": the parrier isn't knocked");
            final List<Vec3> recoil = this.motions(this.attacker, this.attackerMark);
            this.tests.check(recoil.size() == 1 && near(recoil.getFirst(), 0.0, 0.22, -0.45), name + ": stagger: thrown back (0, 0.22, -0.45): " + recoil);
            final List<ClientboundCooldownPacket> cooldowns = this.attacker.fake.receivedOfType(ClientboundCooldownPacket.class, this.attackerMark);
            // One tick past the stagger: the server's cooldowns tick once in the parry's own tick, so the client sees 10 (N3)
            this.tests.check(cooldowns.size() == 2 && cooldowns.stream().allMatch(c -> c.duration() == 11)
                    && cooldowns.stream().anyMatch(c -> c.cooldownGroup().getPath().equals("diamond_sword"))
                    && cooldowns.stream().anyMatch(c -> c.cooldownGroup().getPath().equals("iron_sword")),
                name + ": stagger: the cooldown sweep on every sword it carries (diamond, iron), stagger + 1 ticks: " + cooldowns);
            // N2: the slow rides the recoil's tick, not the dirty-attribute flush 0-3 ticks later
            long recoilTick = Long.MIN_VALUE;
            long slowTick = Long.MIN_VALUE;
            for (int i = this.attackerMark; i < this.attacker.fake.received.size(); i++) {
                final FakeClient.Received r = this.attacker.fake.received.get(i);
                if (recoilTick == Long.MIN_VALUE && r.packet() instanceof final ClientboundSetEntityMotionPacket m && m.id() == this.attacker.player.getId()) {
                    recoilTick = r.serverTick();
                }
                if (slowTick == Long.MIN_VALUE && r.packet() instanceof final ClientboundUpdateAttributesPacket a && a.getEntityId() == this.attacker.player.getId()
                    && a.getValues().stream().anyMatch(v -> v.modifiers().stream().anyMatch(mod -> mod.id().equals(NeueCombat.STAGGER_SLOW)))) {
                    slowTick = r.serverTick();
                }
            }
            this.tests.check(slowTick != Long.MIN_VALUE && slowTick == recoilTick,
                name + ": stagger: the slow went out in the recoil's tick (recoil " + recoilTick + ", slow " + slowTick + ")");
            this.tests.check(this.attacker.fake.receivedOfType(ClientboundSoundPacket.class, this.attackerMark).stream()
                .anyMatch(sound -> sound.getSound().value().location().getPath().equals("item.shield.break")), name + ": stagger: the attacker hears the guard break");
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
            this.attacker.pitch = 0.0F;
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
            this.honestLockoutTicks = this.firstAttackServerTick - this.parryServerTick;
            final float landed = this.firstAttackCrit ? 8.0F : 12.0F;
            this.tests.check(this.victim.player.getHealth() == landed, name + ": that attack landed, " + (this.firstAttackCrit ? "a crit as it fell: 8 x 1.5 = 12" : "8")
                + " (victim health " + this.victim.player.getHealth() + ")");
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
            // The swing hit the attacker and its knock lifted it: under enforce a level ray from there passes over the victim
            this.attacker.aimAt(this.victim);
            this.attacker.attack(this.victim);
        });
        this.tests.after(2, name, () -> {
            this.tests.check(this.victim.player.isBlocking() && !NeueCombat.state(this.victim.player).mayParry(),
                name + ": the block is up with no window (" + NeueCombat.state(this.victim.player).parryWindow() + ")");
            this.tests.check(this.parryEvents == 0 && this.victim.player.getHealth() == 15.5F,
                name + ": a plain 1.7 block, (8 + 1) x 0.5 = 4.5 (health " + this.victim.player.getHealth() + ")");
            this.victim.release();
            this.attacker.pitch = 0.0F;
        });
    }

    // Cheat suite 2: L4, L3, L1 next to the honest controls above -------------------------------------------------------

    /**
     * L4: parried, the attacker takes an iron sword (another cooldown group) and blocks inside the lockout. The stagger put
     * the cooldown on every sword it carries, so no block goes up (the server refuses the use, and under enforce the
     * simulation never starts it), and the parrier's riposte lands as a crit instead of being parried.
     */
    private void staggerSwapBlock(final String name) {
        this.tests.after(40, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (block)", () -> this.victim.use());
        this.tests.after(1, name + " (click)", () -> {
            this.parryEvents = 0;
            this.attackerMark = this.attacker.fake.received.size();
            this.attacker.attack(this.victim);
        });
        this.tests.after(1, name + " (take the iron sword)", () -> {
            this.tests.check(this.parryEvents == 1 && NeueCombat.lockedOut(this.attacker.player), name + ": parried and staggered (" + this.parryEvents + ")");
            this.victim.release();
            this.attacker.select(1);
        });
        this.tests.after(1, name + " (block with it)", () -> this.attacker.use()); // sent whatever the client's cooldown says
        this.tests.after(1, name + " (riposte)", () -> {
            this.tests.check(this.attacker.player.getMainHandItem().is(Items.IRON_SWORD), name + ": the iron sword is in hand");
            this.tests.check(!this.attacker.player.isBlocking() && !this.attacker.player.isUsingItem(),
                name + ": no block went up during the lockout (blocking " + this.attacker.player.isBlocking() + ")");
            this.attacker.player.setHealth(20.0F);
            this.attacker.player.damageCooldownTime = 0;
            this.attacker.player.lastHurt = 0.0F;
            this.victim.attack(this.attacker);
        });
        this.tests.after(2, name, () -> {
            this.tests.check(this.parryEvents == 1 && !NeueCombat.state(this.victim.player).staggered(),
                name + ": the riposte wasn't parried (parries " + this.parryEvents + ", parrier staggered " + NeueCombat.state(this.victim.player).staggered() + ")");
            this.tests.check(this.attacker.player.getHealth() == 8.0F, name + ": the riposte landed as a crit, 8 x 1.5 = 12 (health " + this.attacker.player.getHealth() + ")");
            this.attacker.select(0);
            this.attacker.release();
        });
    }

    /**
     * L3: parried, the attacker runs its client 3 ticks per server tick and clicks in every one. The lockout lasts its
     * windows after the recoil's ack and also its real time (less a tick of jitter), so its first attack is delivered at
     * most a tick before the honest control's above.
     */
    private void staggerFast(final String name) {
        this.tests.after(40, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (block)", () -> this.victim.use());
        this.tests.after(1, name + " (click)", () -> {
            this.parryEvents = 0;
            this.attacker.attack(this.victim);
            this.parryServerTick = MinecraftServer.currentTick + 1;
        });
        this.tests.after(1, name + " (run fast)", () -> {
            this.tests.check(this.parryEvents == 1, name + ": parried (" + this.parryEvents + ")");
            this.victim.release();
            this.firstAttackServerTick = Long.MIN_VALUE;
            this.recordFirstAttack = true;
            this.spam = true;
            this.attacker.rate = 3;
        });
        this.tests.after(25, name, () -> {
            this.spam = false;
            this.recordFirstAttack = false;
            this.attacker.rate = 1;
            this.attacker.pitch = 0.0F;
            final long ticks = this.firstAttackServerTick - this.parryServerTick;
            final int jitter = CombatRules.NEUE.staggerJitterTicks();
            this.tests.check(this.firstAttackServerTick != Long.MIN_VALUE && ticks >= this.honestLockoutTicks - jitter,
                name + ": the first attack delivered " + ticks + " server ticks after the parry; the honest control's came after "
                    + this.honestLockoutTicks + " (at most " + jitter + " sooner)");
            this.tests.check(this.victim.player.getHealth() < 20.0F, name + ": and it landed (victim health " + this.victim.player.getHealth() + ")");
        });
    }

    /**
     * L1: the block-hit control above, with the swing's click sending its {@code Attack} and no {@code Punch}. Every click
     * is a swing, so the block raised two ticks later still has no parry window.
     */
    private void blockHitNoPunch(final String name) {
        this.tests.after(40, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (swing without the Punch)", () -> this.victim.attackWithoutPunch(this.attacker));
        this.tests.after(2, name + " (block)", () -> this.victim.use());
        this.tests.after(1, name + " (click)", () -> {
            this.parryEvents = 0;
            this.attacker.aimAt(this.victim);
            this.attacker.attack(this.victim);
        });
        this.tests.after(2, name, () -> {
            this.tests.check(this.victim.player.isBlocking() && !NeueCombat.state(this.victim.player).mayParry(),
                name + ": the block is up with no window (" + NeueCombat.state(this.victim.player).parryWindow() + ")");
            this.tests.check(this.parryEvents == 0 && this.victim.player.getHealth() == 15.5F,
                name + ": a plain 1.7 block, (8 + 1) x 0.5 = 4.5 (health " + this.victim.player.getHealth() + ")");
            this.victim.release();
            this.attacker.pitch = 0.0F;
        });
    }

    /**
     * Real-client run N1: a block the client raised before it could know the parry's cooldown (in the parried click's own
     * window, attack then use, or one tick after it with the cooldown still on its way) is refused by the server's cooldown
     * check. The block gate resyncs it, so within a round trip the client drops the block it predicted and the server (and,
     * under enforce, the truth) agree there is none. Without the resync the client stayed blocking for its whole hold.
     */
    private void blockInTheParriedWindow(final String name, final boolean nextTick) {
        this.tests.after(40, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (block)", () -> this.victim.use());
        this.tests.after(1, name + " (click" + (nextTick ? ")" : ", then block in the same tick)"), () -> {
            this.parryEvents = 0;
            this.attacker.attack(this.victim);
            if (!nextTick) {
                this.attacker.use(); // the client predicts it: the cooldown can't have reached it yet
            }
        });
        if (nextTick) {
            this.tests.after(1, name + " (block the tick after)", () -> this.attacker.useBeforeTheCooldownArrives());
        }
        this.tests.after(1, name + " (the client blocks)", () -> {
            this.tests.check(this.parryEvents == 1, name + ": parried (" + this.parryEvents + ")");
            this.tests.check(!this.attacker.player.isUsingItem(), name + ": the server refused the block");
        });
        // One round trip on this link (a tick): the resync has reached the client, which drops its block
        this.tests.after(2, name, () -> {
            this.tests.check(!this.attacker.player.isUsingItem() && !this.attacker.model.usingItem(),
                name + ": the server and the client agree: no block (server " + this.attacker.player.isUsingItem() + ", client " + this.attacker.model.usingItem() + ")");
            if (this.attacker.auth.ownsClientState()) {
                this.tests.check(!this.attacker.auth.simulation().usingItem(), name + ": and the truth took the resync: no block");
            }
            this.tests.check(!NeueCombat.state(this.attacker.player).blocking(), name + ": no parry clock armed for the refused block");
        });
        this.tests.after(15, name + " (done)", () -> this.attacker.release());
    }

    /**
     * Review 6, the use gate: a sword type the attacker didn't carry when it was parried missed the stagger's cooldowns,
     * so the client predicts its block. {@code ServerPlayerGameMode.useItem}'s gate ({@code refusedBlock}) refuses it
     * on the server and resyncs the use flags, so the client drops the block at once. Fails with the gate reverted: the
     * server would block with it.
     */
    private void staggerPickedUpSword(final String name) {
        this.tests.after(40, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (block)", () -> this.victim.use());
        this.tests.after(1, name + " (click)", () -> {
            this.parryEvents = 0;
            this.attacker.attack(this.victim);
        });
        this.tests.after(1, name + " (pick up a golden sword)", () -> {
            this.tests.check(this.parryEvents == 1 && NeueCombat.lockedOut(this.attacker.player), name + ": parried and staggered (" + this.parryEvents + ")");
            this.tests.check(this.attacker.player.getCooldowns().getRemainingCooldown(net.minecraft.resources.Identifier.withDefaultNamespace("golden_sword")) == 0,
                name + ": the golden sword has no cooldown (it wasn't carried)");
            this.victim.release();
            this.attacker.player.getInventory().setItem(2, new ItemStack(Items.GOLDEN_SWORD));
        });
        this.tests.after(1, name + " (take it)", () -> this.attacker.select(2));
        this.tests.after(1, name + " (block with it)", () -> {
            this.attackerMark = this.attacker.fake.received.size();
            this.attacker.use();
        });
        this.tests.after(1, name, () -> {
            this.tests.check(NeueCombat.lockedOut(this.attacker.player), name + ": still inside the lockout");
            this.tests.check(this.attacker.player.getMainHandItem().is(Items.GOLDEN_SWORD), name + ": the golden sword is in hand");
            this.tests.check(!this.attacker.player.isUsingItem() && !this.attacker.player.isBlocking(),
                name + ": the server refused the block (using " + this.attacker.player.isUsingItem() + ")");
            this.tests.check(!NeueCombat.state(this.attacker.player).blocking(), name + ": no parry clock armed for the refused block");
        });
        // What the refusal sent, read a tick on: under authority a tick's packets go out in its bundle at the tick's end
        this.tests.after(1, name + ": the resync", () -> {
            this.tests.check(this.useFlagsSent(this.attacker, this.attackerMark), name + ": the use flags were resynced, so the client drops the block it predicted");
            this.tests.check(!this.attacker.player.isUsingItem(), name + ": and the server still isn't blocking");
        });
        this.tests.after(14, name + " (done)", () -> {
            this.attacker.select(0);
            this.attacker.release();
            this.attacker.player.getInventory().setItem(2, ItemStack.EMPTY);
        });
    }

    /**
     * Review 6, the parry guard: with {@code stagger-lowers-block: false} a parried attacker keeps a block it raised just
     * before its click (sent while blocking, which only the grammar refuses under enforce), a fresh block with its whole
     * window. The parrier's riposte meets that block inside its window: a staggered player never parries, so the riposte
     * lands as a crit on a plain block. Fails with the guard in {@code NeueCombat.parried} reverted (a second parry).
     */
    private void staggerKeepsBlock(final String name) {
        this.tests.after(40, name + " (rules)", () -> this.lowersBlock(false));
        this.tests.after(1, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (block)", () -> this.victim.use());
        this.tests.after(1, name + " (block, then click)", () -> {
            this.parryEvents = 0;
            this.attacker.use(); // rested: a full window
            this.attacker.attack(this.victim);
        });
        this.tests.after(1, name + " (riposte)", () -> {
            this.tests.check(this.parryEvents == 1 && NeueCombat.lockedOut(this.attacker.player), name + ": parried and staggered (" + this.parryEvents + ")");
            this.tests.check(this.attacker.player.isBlocking() && NeueCombat.state(this.attacker.player).mayParry(),
                name + ": the staggered attacker's block is still up, with its window (" + NeueCombat.state(this.attacker.player).parryWindow() + ")");
            this.victim.release();
        });
        this.tests.after(1, name + " (riposte click)", () -> {
            this.attacker.player.setHealth(20.0F);
            this.attacker.player.damageCooldownTime = 0;
            this.attacker.player.lastHurt = 0.0F;
            this.victim.attack(this.attacker);
        });
        this.tests.after(2, name, () -> {
            this.tests.check(this.parryEvents == 1 && !NeueCombat.state(this.victim.player).staggered(),
                name + ": the riposte wasn't parried (parries " + this.parryEvents + ", parrier staggered " + NeueCombat.state(this.victim.player).staggered() + ")");
            this.tests.check(this.attacker.player.getHealth() == 13.5F,
                name + ": the riposte crit landed on a plain block, (12 + 1) x 0.5 = 6.5 (health " + this.attacker.player.getHealth() + ")");
            this.attacker.release();
        });
        this.tests.after(1, name + " (rules back)", () -> this.lowersBlock(true));
    }

    // combat-rules.neue.stagger-lowers-block, written and reloaded as an owner would; true removes the override
    private void lowersBlock(final boolean lowers) {
        try {
            final File file = new File("revival.yml");
            final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            // Back to the bare preset: no combat-rules.neue section at all, so the world's rules are CombatRules.NEUE itself
            yaml.set(lowers ? "combat-rules.neue" : "combat-rules.neue.stagger-lowers-block", lowers ? null : false);
            yaml.save(file);
        } catch (final java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        RevivalConfig.reload();
        final CombatRules rules = Revival.settings().legacyCombat(this.level.getWorld().getName()).rules();
        this.tests.check(rules.parry() && rules.staggerLowersBlock() == lowers && (!lowers || rules.equals(CombatRules.NEUE)),
            "neue with stagger-lowers-block " + lowers + " after /revival reload");
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

    /**
     * The click cap ({@code classic-capped}): 2 seconds of clicks at the victim, whose damage is cancelled so no damage
     * window turns one away. An even 12 CPS clicker (2, 2 and 1 ticks apart) keeps all 24 clicks; a locked 20 CPS clicker
     * (every tick) is held to the cap's refill plus its burst, and gets no fewer than the refill gives.
     */
    private void clicker(final String name, final boolean locked) {
        final int ticks = 40;
        final int[] start = {0};
        this.tests.after(40, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (click for 2 s)", () -> {
            this.cancelVictimDamage = true;
            start[0] = this.damageEvents;
            this.clickCount = 0;
            this.clicking = locked ? t -> t < ticks : t -> t < ticks && (t % 5 == 0 || t % 5 == 2 || t % 5 == 4);
        });
        this.tests.after(ticks + 5, name, () -> {
            this.clicking = null;
            this.cancelVictimDamage = false;
            final CombatRules rules = NeueCombat.rules(this.attacker.player);
            final int delivered = this.damageEvents - start[0];
            if (locked) {
                final long most = (long) Math.floor(rules.clickBurst() + rules.clickRefill() * ticks) + 1;
                final long least = (long) Math.floor(rules.clickRefill() * ticks) - 1;
                this.tests.check(delivered >= least && delivered <= most, name + ": delivered " + delivered + " of " + ticks
                    + " clicks (from " + least + " to " + most + ": 12 a second over 2 s, plus the burst)");
            } else {
                this.tests.check(delivered == 24, name + ": every click delivered (" + delivered + " of 24)");
            }
            this.tests.check(this.victim.player.getHealth() == 20.0F, name + ": the victim took nothing (health " + this.victim.player.getHealth() + ")");
        });
    }

    /**
     * Classic is vanilla's traffic: the use gate ({@code LegacySwordBlocking.refusedBlock}) acts only where the rules parry,
     * so in a classic world a sword or an ender pearl on cooldown meets vanilla's own silent PASS. The client knows both
     * cooldowns; an honest client still sends the use and predicts nothing. Fails with the gate's {@code parry()} check
     * removed: the sword's refusal resyncs the use flags.
     */
    private void classicCooldownRefusal(final String name) {
        this.tests.after(40, name + " (place)", this::placeDuel);
        this.tests.after(15, name + " (cooldowns)", () -> {
            final ServerPlayer player = this.attacker.player;
            player.getInventory().setItem(3, new ItemStack(Items.ENDER_PEARL));
            player.getCooldowns().addCooldown(player.getMainHandItem(), 40);
            player.getCooldowns().addCooldown(new ItemStack(Items.ENDER_PEARL), 40);
        });
        this.tests.after(2, name + " (use the sword)", () -> {
            this.tests.check(Boolean.FALSE.equals(this.attacker.view.predictsUse(this.attacker.view.items.hand(InteractionHand.MAIN_HAND))),
                name + ": the client knows the sword's cooldown");
            this.attackerMark = this.attacker.fake.received.size();
            this.attacker.use();
        });
        // A tick for the use, a tick for the tick-end bundle under authority
        this.tests.after(2, name + ": the sword", () -> {
            this.tests.check(!this.attacker.player.isUsingItem(), name + ": the server refused the sword (using " + this.attacker.player.isUsingItem() + ")");
            this.tests.check(!this.useFlagsSent(this.attacker, this.attackerMark), name + ": no resync for the sword");
            this.attacker.select(3);
        });
        this.tests.after(2, name + " (use the pearl)", () -> {
            this.attackerMark = this.attacker.fake.received.size();
            this.attacker.use();
        });
        this.tests.after(2, name + ": the pearl", () -> {
            final ServerPlayer player = this.attacker.player;
            this.tests.check(player.getInventory().getItem(3).is(Items.ENDER_PEARL) && player.getInventory().getItem(3).getCount() == 1,
                name + ": the server refused the pearl (" + player.getInventory().getItem(3) + ")");
            this.tests.check(!this.useFlagsSent(this.attacker, this.attackerMark), name + ": no resync for the pearl");
            this.attacker.select(0);
            player.getInventory().setItem(3, ItemStack.EMPTY);
            player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(player.getMainHandItem()));
            player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(new ItemStack(Items.ENDER_PEARL)));
        });
    }

    // Whether the server sent the duelist its own use flags (a resync) since the mark
    private boolean useFlagsSent(final Duelist duelist, final int mark) {
        return duelist.fake.receivedOfType(ClientboundSetEntityDataPacket.class, mark).stream()
            .anyMatch(data -> data.id() == duelist.player.getId()
                && data.packedItems().stream().anyMatch(value -> value.id() == gg.hcfrevival.revival.auth.view.ClientView.DATA_LIVING_ENTITY_FLAGS));
    }

    // The rewind cap's Overwatch rule (pvp-issues HR-2) -----------------------------------------------------------------

    /**
     * The shooter's packets reach it 450 ms late, so it shows the victim, sneaking across its line of sight, older than its
     * rewind cap, and the judge tests its ray against the victim's truth at the capped age instead ({@code HitValidator}).
     * Sneaking (0.065 a tick) keeps that truth a fraction of a box ahead of the box the shooter shows (2 to 4 ticks of
     * motion), so both aims are picks an honest client makes, on the box it shows: its trailing edge (where it shows the
     * victim) misses the truth, its leading edge (a lead) lands.
     */
    private void pastTheCap(final String name) {
        final double sx = this.bx + 0.5;
        final double sz = this.bz + CROSSING + 0.5;
        this.tests.after(30, name + " (join)", () -> {
            this.shooter = new Shooter("NeueShooter", new Vec3(sx, this.gy, sz), SHOOTER_LATENCY_TICKS);
            this.victim.teleport(new Vec3(sx - 1.6, this.gy, sz + 2.6), -90.0F); // facing +x, across the shooter's view
            this.victim.keys = NONE;
            reset(this.victim.player);
        });
        // Long enough for its join, the victim's teleport and the first re-pair to have reached it; every round trip it has
        // measured is a long one, so rttBase (their p10) has nothing short to forget
        this.tests.after(120, name + " (the victim sneaks across)", () -> {
            final long rtt = this.shooter.auth.transactions().rttBaseNanos() / 1_000_000L;
            this.tests.check(this.shooter.auth.mode() == AuthMode.ENFORCE && this.shooter.auth.ownsClientState() && rtt >= 450L,
                name + ": the shooter is under enforce, 450 ms away (" + this.shooter.auth.mode() + ", rtt base " + rtt + " ms)");
            this.victim.keys = SNEAK_FORWARD;
        });
        this.tests.after(30, name + " (aim where it shows the victim)", () -> this.shooter.aim = Shooter.TRAILING);
        this.tests.after(6, name + ": aiming where it shows the victim misses", () -> {
            this.shotChecks(name, "aiming where it shows the victim");
            this.tests.check("INVALID MISSED".equals(this.shooter.auth.lastHitVerdict()) && this.victim.player.getHealth() == 20.0F,
                name + ": the truth at the capped age is past the ray: " + this.shooter.auth.lastHitVerdict() + " (victim health "
                    + this.victim.player.getHealth() + ")");
            this.tests.check(this.shooter.aimX <= this.shooter.eyeX,
                name + ": the ray leaves the box it shows away from the victim's motion (aim x " + this.shooter.aimX + ", eye x " + this.shooter.eyeX + ")");
        });
        this.tests.after(1, name + " (lead the victim)", () -> this.shooter.aim = Shooter.LEADING);
        this.tests.after(6, name + ": leading the victim lands", () -> {
            this.shotChecks(name, "leading the victim");
            this.tests.check("VALID".equals(this.shooter.auth.lastHitVerdict()) && this.victim.player.getHealth() < 20.0F,
                name + ": the ray meets the truth at the capped age: " + this.shooter.auth.lastHitVerdict() + " (victim health "
                    + this.victim.player.getHealth() + ")");
        });
        this.tests.after(1, name + " (done)", () -> {
            this.victim.keys = NONE;
            this.shooter.fake.disconnect();
            this.shooter = null;
        });
    }

    // What every shot of the control must show: it was sent (an honest pick of the box shown) and judged past the cap
    private void shotChecks(final String name, final String shot) {
        final Shooter s = this.shooter;
        this.tests.check(s.shots == 1, name + ": " + shot + ": one attack sent on the box the shooter shows (" + s.shots + ")");
        s.shots = 0;
        final long rewind = s.auth.lastHitRewindMillis();
        final long cap = s.auth.lastRewindCap();
        // CombatWiring.sharedRewindCap is the cap the judge used; it recorded it, with the age of the view it judged
        this.tests.check(rewind > cap, name + ": " + shot + ": the view was older than the cap (rewind " + rewind + " ms, cap " + cap
            + " ms, display " + s.auth.lastDisplayDelay() + " ms; shown x " + s.shownMinX + ".." + s.shownMaxX + ", aim x " + s.aimX + ")");
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
        // Equal, not the same: a run on classic-capped writes its two keys under combat-rules, which reads as an equal copy
        this.tests.check(rules.equals(CombatRules.PRESETS.get(name)), "legacy-combat.rules is " + name + " after /revival reload");
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
     * An honest 26.3 client a long route away: every clientbound packet reaches it {@code latency} ticks late (keep-alives
     * and chunk batches are answered at once, as the netty thread would), so its pongs, and the view they ack, run that far
     * behind. It stands still. When {@link #aim} is set, its next tick picks a point on the near face of the victim's box
     * as its mirror shows it (the box its attack ray tests this tick), turns to it, and attacks: the trailing edge of
     * the box (against the victim's motion, +x) or the leading edge. Reads {@link FakeClient#raw} itself, so the fake's
     * own prompt replies are never sent.
     */
    private final class Shooter {
        static final int TRAILING = 1;
        static final int LEADING = 2;

        final FakeClient fake;
        final ServerPlayer player;
        final AuthPlayer auth;
        final ClientView view = new ClientView();
        final WorldView world = new WorldView();
        final PortSimulation model;
        private final int latency;
        private final ArrayDeque<Packet<?>> inbox = new ArrayDeque<>();
        private final ArrayDeque<Integer> releases = new ArrayDeque<>();
        private final List<Packet<? super ServerGamePacketListener>> drained = new ArrayList<>();
        private PositionMoveRotation joined = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F);
        private int rawSeen;
        private int ticks;
        private boolean positioned;
        private boolean seeded;
        private boolean loaded;
        private float yaw;
        private float pitch;
        // LocalPlayer.sendPosition bookkeeping
        private Vec3 lastSentPos = Vec3.ZERO;
        private float lastYaw = Float.NaN;
        private float lastPitch = Float.NaN;
        private boolean lastOnGround;
        private int reminder;

        int aim;
        int shots;
        double aimX;
        double eyeX;
        double shownMinX;
        double shownMaxX;

        private final ClientView.EchoSink echo = new ClientView.EchoSink() {
            @Override
            public void sharedFlags(final byte flags) {
                Shooter.this.model.echoSharedFlags(flags);
            }

            @Override
            public void pose(final Pose pose) {
                Shooter.this.model.echoPose(pose);
            }

            @Override
            public void usingItem(final boolean using, final InteractionHand hand) {
                Shooter.this.model.echoUsingItem(using, hand);
            }
        };

        Shooter(final String name, final Vec3 at, final int latency) {
            this.fake = new FakeClient(NeueLiveScenarios.this.server, NeueLiveScenarios.this.level, name);
            this.player = this.fake.player;
            this.auth = this.player.revivalAuth;
            this.latency = latency;
            final net.minecraft.server.ServerScoreboard scoreboard = NeueLiveScenarios.this.server.getScoreboard();
            scoreboard.addPlayerToTeam(this.player.getScoreboardName(), scoreboard.getPlayerTeam(TEAM));
            this.auth.setOverride(AuthMode.ENFORCE);
            this.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            this.player.connection.teleport(at.x, at.y, at.z, 0.0F, 0.0F); // facing +z
            this.view.seed(this.player);
            this.world.setLevel(NeueLiveScenarios.this.level, false);
            this.model = new PortSimulation(this.player);
        }

        void tick() {
            // What came in, each due `latency` ticks from now
            final List<Packet<?>> raw = this.fake.raw;
            for (final int size = raw.size(); this.rawSeen < size; this.rawSeen++) {
                if (raw.get(this.rawSeen) instanceof final BundlePacket<?> bundle) {
                    for (final Packet<?> sub : bundle.subPackets()) {
                        this.enqueue(sub);
                    }
                } else {
                    this.enqueue(raw.get(this.rawSeen));
                }
            }
            while (!this.releases.isEmpty() && this.releases.peekFirst() <= this.ticks) {
                this.releases.pollFirst();
                this.handle(this.inbox.pollFirst());
            }
            // The client's player exists once its login position came in (its packets are 450 ms on their way)
            if (!this.seeded && this.positioned) {
                final Vec3 at = this.joined.position();
                this.model.seed(at.x, at.y, at.z, 0.0, 0.0, 0.0, this.yaw, this.pitch, true, this.view, this.world);
                this.seeded = true;
            }
            final List<Packet<? super ServerGamePacketListener>> out = new ArrayList<>(this.drained);
            this.drained.clear();
            if (this.seeded) {
                if (!this.loaded && this.fake.chunks.contains(net.minecraft.world.level.ChunkPos.pack(net.minecraft.util.Mth.floor(this.model.x()) >> 4,
                    net.minecraft.util.Mth.floor(this.model.z()) >> 4))) {
                    out.add(new ServerboundPlayerLoadedPacket());
                    this.loaded = true;
                }
                if (this.aim != 0) {
                    this.aimAndAttack(out);
                }
                if (this.loaded) {
                    this.model.tick(NONE, NONE, this.yaw, this.pitch, ClientStream.SPRINT_NONE, -1, false, this.view, this.world);
                }
                this.view.tick();
                final Packet<? super ServerGamePacketListener> move = this.sendPosition(new Vec3(this.model.x(), this.model.y(), this.model.z()),
                    this.model.onGround(), this.model.horizontalCollision());
                if (move != null) {
                    out.add(move);
                }
            }
            out.add(ServerboundClientTickEndPacket.INSTANCE);
            this.ticks++;
            for (final Packet<? super ServerGamePacketListener> packet : out) {
                this.fake.deliver(packet);
            }
        }

        // The pick: a point on the near face of the box shown (the T sample: the live mirror after the last tick), at mid
        // height, a hair inside its trailing or leading x edge; the tick's rotation is the one that ray was cast with
        private void aimAndAttack(final List<Packet<? super ServerGamePacketListener>> out) {
            final int mode = this.aim;
            this.aim = 0;
            final RemoteMirror shown = CombatWiring.book(this.auth).mirror(NeueLiveScenarios.this.victim.player.getId());
            if (shown == null || !shown.present()) {
                return;
            }
            final Simulation sim = this.auth.simulation();
            final double ex = sim.x();
            final double ey = sim.y() + sim.eyeHeight();
            final double ez = sim.z();
            final double px = mode == LEADING ? shown.maxX() - 0.05 : shown.minX() + 0.03;
            final double py = (shown.minY() + shown.maxY()) * 0.5;
            final double pz = shown.minZ() + 0.01;
            final double dx = px - ex;
            final double dy = py - ey;
            final double dz = pz - ez;
            this.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            this.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            this.aimX = px;
            this.eyeX = ex;
            this.shownMinX = shown.minX();
            this.shownMaxX = shown.maxX();
            this.shots++;
            this.model.clientAttack(true, this.view);
            out.add(new ServerboundAttackPacket(NeueLiveScenarios.this.victim.player.getId()));
            out.add(new ServerboundPunchPacket());
        }

        private void enqueue(final Packet<?> packet) {
            switch (packet) {
                case final ClientboundKeepAlivePacket keepAlive -> this.fake.deliver(new ServerboundKeepAlivePacket(keepAlive.getId()));
                case final ClientboundChunkBatchFinishedPacket ignored -> this.fake.deliver(new ServerboundChunkBatchReceivedPacket(20.0F));
                default -> {
                    this.inbox.addLast(packet);
                    this.releases.addLast(this.ticks + this.latency);
                }
            }
        }

        // ClientPacketListener, for what moves or equips this client
        private void handle(final Packet<?> packet) {
            final int self = this.player.getId();
            switch (packet) {
                case final ClientboundPingPacket ping -> this.drained.add(new ServerboundPongPacket(ping.getId()));
                case final ClientboundPlayerPositionPacket position -> {
                    if (!this.seeded) {
                        this.positioned = true;
                        this.joined = PositionMoveRotation.calculateAbsolute(this.joined, position.change(), position.relatives());
                        this.yaw = this.joined.yRot();
                        this.pitch = this.joined.xRot();
                        final Vec3 at = this.joined.position();
                        this.drained.add(new ServerboundAcceptTeleportationPacket(position.id(), at.x, at.y, at.z, this.joined.yRot(), this.joined.xRot()));
                        return;
                    }
                    this.model.position(position.change(), position.relatives());
                    if (!Reconciler.ours(position.id())) {
                        this.yaw = this.model.yRot();
                        this.pitch = this.model.xRot();
                    }
                    this.drained.add(new ServerboundAcceptTeleportationPacket(position.id(), this.model.x(), this.model.y(), this.model.z(), this.model.yRot(),
                        this.model.xRot()));
                }
                case final ClientboundSetEntityMotionPacket motion when motion.id() == self -> {
                    if (this.seeded) {
                        final double[] decoded = new double[3];
                        Wire.lpVec3(motion.movement().x, motion.movement().y, motion.movement().z, decoded);
                        this.model.setVelocity(decoded[0], decoded[1], decoded[2]);
                    }
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
                default -> {
                }
            }
        }

        // LocalPlayer.sendPosition (26.3)
        private Packet<? super ServerGamePacketListener> sendPosition(final Vec3 position, final boolean onGround, final boolean collision) {
            this.reminder++;
            final boolean move = position.distanceToSqr(this.lastSentPos) > 4.0E-8 || this.reminder >= 20;
            final boolean rot = this.yaw != this.lastYaw || this.pitch != this.lastPitch;
            Packet<? super ServerGamePacketListener> packet = null;
            if (move && rot) {
                packet = new ServerboundMovePlayerPacket.PosRot(position, this.yaw, this.pitch, onGround, collision);
            } else if (move) {
                packet = new ServerboundMovePlayerPacket.Pos(position, onGround, collision);
            } else if (rot) {
                packet = new ServerboundMovePlayerPacket.Rot(this.yaw, this.pitch, onGround, collision);
            } else if (onGround != this.lastOnGround) {
                packet = new ServerboundMovePlayerPacket.StatusOnly(onGround, collision);
            }
            if (move) {
                this.lastSentPos = position;
                this.reminder = 0;
            }
            if (rot) {
                this.lastYaw = this.yaw;
                this.lastPitch = this.pitch;
            }
            this.lastOnGround = onGround;
            return packet;
        }
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
        float pitch;
        /** Client ticks per server tick: 1 is honest, more is a timer cheat. */
        int rate = 1;
        private boolean cooldownInFlight;
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

        // A modified client: the Attack alone, no Punch after it
        void attackWithoutPunch(final Duelist target) {
            this.actions.add(new ServerboundAttackPacket(target.player.getId()));
        }

        // A hotbar key: the client selects at once and tells the server
        void select(final int slot) {
            this.view.items.select(slot);
            this.actions.add(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
        }

        // Pitch to the middle of the target's box from the eye the next pick casts from (after the last tick); the yaw stays
        void aimAt(final Duelist target) {
            final double dx = target.player.getX() - this.model.x();
            final double dy = target.player.getY() + target.player.getBbHeight() * 0.5 - (this.model.y() + this.model.eyeHeight());
            final double dz = target.player.getZ() - this.model.z();
            this.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }

        void use() {
            this.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, ++this.sequence, this.yaw, 0.0F));
        }

        // A block pressed before the stagger's cooldown reached this client (one RTT on a real link): it predicts the block
        void useBeforeTheCooldownArrives() {
            this.cooldownInFlight = true;
            this.use();
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
                        if (this.cooldownInFlight || Boolean.TRUE.equals(this.view.predictsUse(this.view.items.hand(use.hand())))) {
                            this.model.startUsingItem(use.hand(), this.view);
                        }
                        this.cooldownInFlight = false;
                    }
                    case final ServerboundPlayerActionPacket release when release.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM ->
                        this.model.releaseUsingItem();
                    default -> {
                    }
                }
            }
            if (this.fake.loaded()) {
                this.model.tick(this.keys, this.previousKeys, this.yaw, this.pitch, ClientStream.SPRINT_NONE, -1, false, this.view, this.world);
            }
            this.previousKeys = this.keys;
            this.view.tick();
            this.fake.clientTick(sent, this.keys, this.model.sprinting(), new Vec3(this.model.x(), this.model.y(), this.model.z()), this.yaw, this.pitch,
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
