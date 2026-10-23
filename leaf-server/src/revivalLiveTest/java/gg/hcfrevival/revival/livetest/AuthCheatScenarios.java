package gg.hcfrevival.revival.livetest;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.auth.AuthMode;
import gg.hcfrevival.revival.auth.AuthPlayer;
import gg.hcfrevival.revival.auth.AuthStats;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.auth.CombatWiring;
import gg.hcfrevival.revival.auth.PortSimulation;
import gg.hcfrevival.revival.auth.Simulation;
import gg.hcfrevival.revival.auth.combat.RemoteMirror;
import gg.hcfrevival.revival.auth.input.InputRules;
import gg.hcfrevival.revival.auth.net.ClientStream;
import gg.hcfrevival.revival.auth.net.Transactions;
import gg.hcfrevival.revival.auth.net.Wire;
import gg.hcfrevival.revival.auth.reconcile.Reconciler;
import gg.hcfrevival.revival.auth.view.ClientView;
import gg.hcfrevival.revival.auth.view.WorldView;
import gg.hcfrevival.revival.config.AuthoritativeSettings;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * The cheat suite (docs/revival/auth-cheat-suite.md, design §1 threat model and §14): a modified client, played by a
 * {@link Cheater} that speaks the 26.3 protocol and can send any packet in any order, attacks a server running
 * {@code authoritative.mode: enforce} with its client on a {@code NEVER} collision team (the §15.3 precondition).
 * <p>
 * The {@link FakeClient} carries the connection; the cheater reads what the server wrote ({@link FakeClient#raw}) and
 * composes every serverbound packet itself, so it controls when each ping is answered, which teleports it accepts and
 * with what, and what each window holds. Its own movement is the real port ({@link PortSimulation}) fed what it applied,
 * so an honest cheater is bit-exact and a cheat is a precise edit of an honest client.
 * <p>
 * Each scenario asserts its expected outcome: the truth (the simulation) and the {@code ServerPlayer} never take the
 * cheated state, the client is corrected or the action refused, the violation is counted, and an honest client right
 * after is exact again. Outcomes are collected (blocked / leaked, with the numbers) and the suite fails at the end if
 * any cheat leaked, so every scenario always runs.
 */
final class AuthCheatScenarios {

    private static final Input NONE = Input.EMPTY;
    private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
    private static final Input FORWARD_SHIFT = new Input(true, false, false, false, false, true, false);
    private static final Input FORWARD_SPRINT = new Input(true, false, false, false, false, false, true);
    private static final Input JUMP = new Input(false, false, false, false, true, false, false);
    private static final int WARM = 15;
    private static final String TEAM = "authcheat";
    private static final double EPS = 1.0E-9;
    private static final net.minecraft.resources.Identifier PROBE_MODIFIER = net.minecraft.resources.Identifier.fromNamespaceAndPath("revival", "cheat_probe");

    private final RevivalLiveTests tests;
    private final MinecraftServer server;
    private final ServerLevel level;
    private final Listener listener = new Listener() {
    };
    private final List<Cheater> clients = new ArrayList<>();
    private final List<String> table = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private @Nullable BukkitTask driver;
    private int serial;
    private int ox;
    private int gy;
    private int oz;

    // The scenario being run
    private String scenario = "";
    private @Nullable Cheater c;
    private @Nullable Cheater v;
    private final List<String> broken = new ArrayList<>();
    private final List<String> honestBroken = new ArrayList<>();
    private final StringBuilder evidence = new StringBuilder();
    private String label = "";
    private Runnable probe = () -> {
    };
    private Snap base = Snap.ZERO;
    private @Nullable Snap honestBase;
    private Track track = new Track();
    private int armTicks;
    private int attacksBase;
    private int clicksBase;

    // What the server did, from its events
    private final Map<UUID, Integer> attacks = new HashMap<>();
    private final Map<UUID, Integer> clicks = new HashMap<>();
    private final Map<UUID, String> kicks = new HashMap<>();
    private final Map<UUID, java.util.Set<String>> anomalies = new HashMap<>();
    private final Set<Long> protectedBlocks = new HashSet<>();
    // Placements a plugin cancels (BlockPlaceEvent), by position: HCF claims and combat walls
    private final Set<Long> deniedPlacements = new HashSet<>();
    // What the honest control of a cheat row measured, for the cheat to be judged against (null or NaN until it ran)
    private @Nullable Vec3 keepSprintTruth;
    private @Nullable Vec3 keepSprintKnock;
    private float honestHitDamage = Float.NaN;
    private double moveMaxY;
    private double moveMinY;
    private double moveMaxZ;

    AuthCheatScenarios(final RevivalLiveTests tests, final MinecraftServer server, final ServerLevel level) {
        this.tests = tests;
        this.server = server;
        this.level = level;
    }

    void register() {
        if (Authority.refusal() != null) {
            return; // the async-tracker run: authority refuses (AuthLiveScenarios checks that), nothing to attack
        }
        this.step(1, "cheat suite: setup", this::setup);
        // The rule set the netcode is proven on (LiveRules: classic-capped on the NAS, plain classic the control)
        this.step(1, "cheat suite: rules " + LiveRules.MAIN, () -> this.rules(LiveRules.MAIN));

        // Joins (load-test regression 2026-10-22: truths fell through the floor of a crowd joining together)
        this.crowdJoin(5, 0.0, "5 players join 2 ticks apart on a floor, then walk");
        this.crowdJoin(1, 160.0, "a player joins into chunks still being sent, then walks");
        this.crowdJoin(3, 320.0, "3 bots that report loaded once their chunk is sent, before it arrives, join, then walk (the load-test regression)", true);

        // Movement
        this.fly();
        this.hover();
        this.speed();
        this.step();
        this.phase("phase: through a wall", false);
        this.phase("phase: through a closed door", true);
        this.vclip();
        this.spider();
        this.jesus();
        this.noFall();
        this.noSlowSneak();
        this.noSlowItem();

        // Knockback and transactions
        this.ignoreKnock();
        this.withholdPongs();
        this.stall(90, true);
        this.stall(240, false);
        this.stallTeleport(20);
        this.delayedPongs(3, true);
        this.delayedPongs(9, false);
        this.batchedKnocks();
        this.pongGame("pongs: answered out of order", x -> x.outOfOrder = true, true);
        this.pongGame("pongs: unknown id in our band", x -> x.preDrain.add(new ServerboundPongPacket(Transactions.PING_MIN + 77)), true);
        this.pongGame("pongs: future id", x -> x.futurePong = true, true);
        this.pongGame("pongs: duplicate of an acked id", x -> x.duplicatePong = true, false);
        this.pongGame("pongs: foreign ids", x -> {
            x.preDrain.add(new ServerboundPongPacket(4242));
            x.preDrain.add(new ServerboundPongPacket(-1234));
        }, false);
        this.pongAfterKeybind();
        this.twoInputs();

        // Timer
        this.timer(22);
        this.timer(30);
        this.blink();
        this.slowThenBurst();
        this.noTickEnd();
        this.twoMoves();

        // Teleports
        this.spoofedAccept();
        this.neverAccept();
        this.bogusCorrectionAccept();
        this.bogusVanillaAccept();
        this.nonFinite("non-finite: NaN position claim", x -> x.moveOverride = new ServerboundMovePlayerPacket.Pos(
            new Vec3(Double.NaN, x.model.y(), x.model.z()), true, false));
        this.nonFinite("non-finite: infinite position claim", x -> x.moveOverride = new ServerboundMovePlayerPacket.Pos(
            new Vec3(x.model.x(), Double.POSITIVE_INFINITY, x.model.z()), true, false));
        this.nonFinite("non-finite: NaN rotation claim", x -> x.moveOverride = new ServerboundMovePlayerPacket.PosRot(
            new Vec3(x.model.x(), x.model.y(), x.model.z()), Float.NaN, Float.NaN, true, false));
        this.nanServerAccept();
        this.nanCorrectionAccept();

        // Creep and overlap
        this.creep("creep: 1e-7 per tick into a closed door (rebase-epsilon 0)", true, 1.0E-7, 100);
        this.creep("creep: 1e-7 per tick into a closed fence gate (rebase-epsilon 0)", false, 1.0E-7, 60);
        this.epsilonStep(1.0E-10);
        this.creep("creep: 0.9e-10 per tick into a closed door (rebase-epsilon 1e-10, its max)", true, 0.9E-10, 1300);
        this.floorCreep();
        this.epsilonStep(0.0);
        this.overlap();

        // Combat
        this.combatReach();
        this.claimReach(); // classic audit #1: an eye offset the server didn't cause
        this.combatTeleportPending();
        this.combatBacktrack();
        this.combatForcedPoison();
        this.combatLatePongPoison();
        this.hitStoppedTarget(); // review X2-01: a target that stopped sending stays hittable where it stopped
        this.heldTeleportPong(); // review X2-03: a server teleport whose pong misses the deadline
        this.heldKnockPong(0); // torture r80down300w: a knock whose pong comes just past its deadline
        this.heldKnockPong(1);
        this.heldKnockPong(2);
        this.combatLatency(1);
        this.combatLatency(3);
        this.combatLatency(5);
        this.combatLatencyMoving(6);
        this.combatChaseAway(6); // classic audit #7: Paper's range check after a VALID hit
        this.combatFakeLag();
        this.combatAutoblock();
        this.combatContainerClose();
        // Classic audit #4: a piston explains only what it moves, along its push, as far as it travels
        this.pistonCase("piston: pushed along its lane, the claims adopted (control)", 0);
        this.pistonCase("piston: a piston 10 blocks off toggled, the claims rise 0.55 a tick (a claim-wall climb)", 1);
        this.pistonCase("piston: in the lane, the claims rise 0.55 a tick instead of moving with the push", 2);
        // Classic audit #3: use packets carry no rotation or hand of their own
        this.silentPot();
        this.offhandPot(true);
        this.offhandPot(false);
        // Classic audit #8: the jump-reset alert, a macro against an honest tap-hopper
        this.jumpReset("jump-reset: a macro that jumps in the window of every knock it takes on the ground", true);
        this.jumpReset("honest: a player who jumps on 3% of its ground ticks at random, knocked as often (control)", false);
        // pvp-issues SP-4: tick-locked w-tap and block-hit macros against human timing
        this.tapLock("w-tap: a macro that lets go of forward 1 tick after every hit, for 2 ticks", TAP_W, true);
        this.tapLock("block-hit: a macro that blocks 1 tick after every hit, for 2 ticks", TAP_BLOCK, true);
        this.tapLock("honest: a w-tap and a block-hit after every hit, timed as a human (control)", TAP_W | TAP_BLOCK, false);
        // pvp-issues HR-3: backtrack inside the rewind cap against an honest fighter
        this.rewindStretch("rewind-stretch: a client that holds its packets 150 ms before each swing", true);
        this.rewindStretch("honest: hits at the client's own pace (control)", false);
        // Classic audit #13: a pot refill through the inventory screen mid-fight, as an honest client does it
        this.potRefill();
        // pvp-issues SP-3, MV-3: cheats authority closes by design, each against its honest control
        this.keepSprint("keep-sprint: an honest sprint hit (control)", false);
        this.keepSprint("keep-sprint: the client skips its own x0.6 and sprint stop after a sprint hit", true);
        this.keepSprintAxe("keep-sprint: an honest sprint hit with an axe, one cooldown after the last (control)", false);
        this.keepSprintAxe("keep-sprint: the same axe hit, the client skipping its own x0.6 and sprint stop", true);
        // pvp-issues HR-7: a vanilla spear stabs (PlayerAction STAB); the server hits along its own line
        this.spearStab("spear: an honest stab at 3.9 blocks, past a sword's reach (control)", 3.9, true);
        this.spearStab("spear: a stab at 5.5 blocks, past the spear's reach", 5.5, false);
        this.packetCrit("crits: an honest hit standing on the ground (control)", false);
        this.packetCrit("crits: claims a 0.0625 hop off the ground before the hit (packet criticals)", true);
        // Classic audit #9: eating and drinking under enforce, as an honest client does them
        this.consume("honest: a golden apple eaten under enforce (control)", false);
        this.consume("honest: a Speed potion drunk under enforce, then a sprint (control)", true);
        this.inventoryMove();
        this.mountDenied();
        this.vehiclesStep("vanilla");
        this.combatRiding();
        this.vehiclesStep("deny");

        // World
        this.phantomPlace();
        this.predictionSpam();
        this.protectedBreak();
        // NAS glitch 2026-10-22: an honest client breaking protected blocks it stands on or walks into
        this.honeyUnderfoot();
        this.protectedWallHonest(false);
        this.protectedWallHonest(true);
        // pvp-issues WD-1: HCF's block glitch, a cancelled placement climbed over a 2-high wall, against the same block allowed
        this.blockGlitch("world: a block placed in front of a 2-high wall, climbed and over (control)", false);
        this.blockGlitch("world: a block a plugin cancels, its ghost climbed over a 2-high wall (block glitch)", true);
        this.unsentChunkWalk();
        this.neverLoaded();
        // pvp-issues WD-4: ghost hand, a chest opened and a block dug through a wall, each against its in-sight control
        this.ghostChest("honest: opens a chest in sight (control)", false);
        this.ghostChest("ghost hand: opens a chest behind a wall", true);
        this.ghostDig("honest: starts digging a block in sight (control)", false);
        this.ghostDig("ghost hand: starts digging a block behind a wall", true);
        this.lecternTop("honest: right-clicks a lectern's top, above its cube (control)");

        // Self echo
        this.sprintEcho("self-echo: STOP_SPRINTING before acking the sprint attribute sync", false);
        this.sprintEcho("self-echo: sprint dropped without STOP_SPRINTING, attribute sync acked late", true);

        // Sprint knock (classic audit #2, #5): the ledger follows the simulated sprint; packets and a zero-tick block move nothing
        this.sprintLedger("sprint knock: an honest w-tap re-arms it (control)", 0);
        this.sprintLedger("sprint knock: a spoofed STOP_SPRINTING in a click's window, sprint key held", 1);
        this.sprintLedger("sprint knock: STOP_SPRINTING, then START_SPRINTING with a click (SuperKB)", 2);
        this.sprintLedger("sprint knock: an honest block-hit, the release a tick later, re-arms it (control)", 3);
        this.sprintLedger("sprint knock: a use and its release in one window (a block for no tick)", 4);

        this.step(1, "cheat suite: summary", this::summary);
        this.step(3, "cheat suite: players who quit are released", () -> {
            // Review PT2-02: every cheater joined and quit under authority, and no quit hook deactivates them
            long leaked = 0;
            for (final AuthPlayer active : gg.hcfrevival.revival.auth.Authority.activePlayers()) {
                if (active.player().hasDisconnected()) {
                    leaked++;
                }
            }
            this.tests.check(leaked == 0, "cheat suite: players who quit under authority are no longer held (" + leaked + " held, "
                + gg.hcfrevival.revival.auth.Authority.activePlayers().size() + " active)");
        });
        this.step(1, "cheat suite: rules classic again", () -> this.rules(gg.hcfrevival.revival.config.CombatRules.DEFAULT));
    }

    private void rules(final String name) {
        final gg.hcfrevival.revival.config.CombatRules rules = LiveRules.apply(this.level, name);
        this.tests.check(LiveRules.is(rules, name), "cheat suite: legacy-combat.rules is " + name + " after /revival reload (max-cps "
            + rules.maxCps() + ", click buffer " + rules.clickBufferTicks() + " ticks, parry " + rules.parry() + ")");
    }

    // =================================================================================================================
    // Movement
    // =================================================================================================================

    private void fly() {
        final String id = "fly: claims rising 0.25/tick";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            final double y0 = this.c.model.y();
            final int[] k = {0};
            this.c.forcePosition = true;
            this.c.claimOnGround = false;
            this.c.cheat = x -> {
                k[0]++;
                x.model.setVelocity(x.model.vx(), 0.0, x.model.vz());
                x.model.rebase(x.model.x(), y0 + 0.25 * k[0], x.model.z());
            };
        });
        this.judge(30, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(this.track.claimMaxY >= this.gy + 5.0, "claimed up to +" + f(this.track.claimMaxY - this.gy));
            this.expect(this.track.truthMaxY <= this.gy + EPS, "truth y max +" + f(this.track.truthMaxY - this.gy));
            this.expect(this.track.bodyMaxY <= this.gy + EPS && this.moveMaxY <= this.gy + EPS,
                "ServerPlayer y max +" + f(Math.max(this.track.bodyMaxY, this.moveMaxY) - this.gy));
            this.expect(d.corrections() > 0, "corrections " + d.corrections() + " (accepted " + d.accepted() + ")");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void hover() {
        final String id = "fly: jump then hover";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            final double[] hold = {Double.NaN};
            final int[] k = {0};
            this.c.forcePosition = true;
            this.c.before = x -> x.keys = k[0]++ == 0 ? JUMP : NONE;
            this.c.cheat = x -> {
                if (Double.isNaN(hold[0]) && x.model.y() >= this.gy + 1.0) {
                    hold[0] = x.model.y();
                }
                if (!Double.isNaN(hold[0])) {
                    x.model.setVelocity(x.model.vx(), 0.0, x.model.vz());
                    x.model.rebase(x.model.x(), hold[0], x.model.z());
                    x.claimOnGround = false;
                }
            };
        });
        this.judge(40, id, () -> {
            final Snap d = this.delta(this.c);
            final Simulation s = this.c.auth.simulation();
            this.expect(this.track.highClaims >= 25, "claims held at +1.0 or more for " + this.track.highClaims + " ticks");
            this.expect(this.track.truthMaxY <= this.gy + 1.2523, "truth y max +" + f(this.track.truthMaxY - this.gy) + " (a jump)");
            this.expect(Math.abs(s.y() - this.gy) <= EPS && s.onGround(), "truth landed (y +" + f(s.y() - this.gy) + ")");
            this.expect(Math.max(this.track.bodyMaxY, this.moveMaxY) <= this.gy + 1.2523, "ServerPlayer y max +" + f(Math.max(this.track.bodyMaxY, this.moveMaxY) - this.gy));
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void speed() {
        final String id = "speed: claims 1.5x the walk";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            final double[] zb = {0.0};
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
            this.c.before = x -> zb[0] = x.model.z();
            this.c.cheat = x -> x.model.rebase(x.model.x(), x.model.y(), zb[0] + 1.5 * (x.model.z() - zb[0]));
        });
        this.judge(25, id, () -> {
            final Snap d = this.delta(this.c);
            final int n = this.c.ticks - this.armTicks;
            final Vec3 ref = this.reference(this.c, start, FORWARD, n, false);
            this.expectTruthIs(ref, start, n);
            this.expect(this.track.claimLead >= 0.05, "claims ran up to " + f(this.track.claimLead) + " ahead of the truth");
            this.expect(Math.max(this.track.bodyMaxZ, this.moveMaxZ) <= ref.z + EPS, "ServerPlayer z max +" + f(Math.max(this.track.bodyMaxZ, this.moveMaxZ) - start[2]));
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void step() {
        final String id = "step: 1.0 block step-up";
        this.begin(id, () -> this.wall(3, 1, Blocks.STONE.defaultBlockState()));
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            final double face = this.face(3);
            final boolean[] up = {false};
            final double[] zc = {0.0};
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
            this.c.cheat = x -> {
                if (!up[0] && x.model.z() >= face - 0.3 - 1.0E-6) {
                    up[0] = true;
                    zc[0] = x.model.z();
                }
                if (up[0]) {
                    zc[0] += 0.2;
                    x.model.setVelocity(x.model.vx(), 0.0, x.model.vz());
                    x.model.rebase(x.model.x(), this.gy + 1.0, zc[0]);
                    x.claimOnGround = true;
                }
            };
        });
        this.judge(30, id, () -> {
            final Snap d = this.delta(this.c);
            final double face = this.face(3);
            final int n = this.c.ticks - this.armTicks;
            this.expectTruthIs(this.reference(this.c, start, FORWARD, n, false), start, n);
            this.expect(this.track.claimMaxY >= this.gy + 1.0, "claimed standing on the block (+" + f(this.track.claimMaxY - this.gy) + ")");
            this.expect(this.track.truthMaxY <= this.gy + EPS && this.track.truthMaxZ <= face - 0.3 + EPS,
                "truth max y +" + f(this.track.truthMaxY - this.gy) + ", max z " + f(this.track.truthMaxZ - (face - 0.3)) + " past the face");
            this.expect(Math.max(this.track.bodyMaxY, this.moveMaxY) <= this.gy + EPS, "ServerPlayer y max +" + f(Math.max(this.track.bodyMaxY, this.moveMaxY) - this.gy));
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void phase(final String id, final boolean door) {
        this.begin(id, () -> {
            this.wall(3, 2, Blocks.STONE.defaultBlockState());
            if (door) {
                this.door(3);
            }
        });
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            final double z0 = this.c.model.z();
            final int[] k = {0};
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
            this.c.cheat = x -> {
                k[0]++;
                x.model.rebase(x.model.x(), x.model.y(), z0 + 0.25 * k[0]);
            };
        });
        this.judge(30, id, () -> {
            final Snap d = this.delta(this.c);
            final double face = this.face(3);
            final int n = this.c.ticks - this.armTicks;
            this.expectTruthIs(this.reference(this.c, start, FORWARD, n, false), start, n);
            this.expect(this.track.claimMaxZ >= face + 1.3, "claimed " + f(this.track.claimMaxZ - face) + " past the face");
            this.expect(this.track.truthMaxZ <= face - 0.3 + EPS, "truth z max " + f(this.track.truthMaxZ - (face - 0.3)) + " past touching");
            this.expect(Math.max(this.track.bodyMaxZ, this.moveMaxZ) <= face - 0.3 + EPS, "ServerPlayer z max " + f(Math.max(this.track.bodyMaxZ, this.moveMaxZ) - (face - 0.3)) + " past touching");
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void vclip() {
        final String id = "vclip: claims 6 blocks down through the floor";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            this.c.forcePosition = true;
            this.c.claimOnGround = true;
            this.c.cheat = x -> {
                x.model.setVelocity(0.0, 0.0, 0.0);
                x.model.rebase(x.model.x(), this.gy - 6.0, x.model.z());
            };
        });
        this.judge(15, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(this.track.claimMinY <= this.gy - 5.9, "claimed y " + f(this.track.claimMinY - this.gy));
            this.expect(this.track.truthMinY >= this.gy - EPS, "truth y min " + f(this.track.truthMinY - this.gy));
            this.expect(this.track.bodyMinY >= this.gy - EPS && this.moveMinY >= this.gy - EPS, "ServerPlayer y min " + f(Math.min(this.track.bodyMinY, this.moveMinY) - this.gy));
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void spider() {
        final String id = "spider: claims climbing a plain wall";
        this.begin(id, () -> this.wall(1, 4, Blocks.STONE.defaultBlockState()));
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            final int[] k = {0};
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
            this.c.claimOnGround = false;
            this.c.cheat = x -> {
                k[0]++;
                x.model.setVelocity(x.model.vx(), 0.0, x.model.vz());
                x.model.rebase(x.model.x(), this.gy + 0.2 * k[0], x.model.z());
            };
        });
        this.judge(25, id, () -> {
            final Snap d = this.delta(this.c);
            final int n = this.c.ticks - this.armTicks;
            this.expectTruthIs(this.reference(this.c, start, FORWARD, n, false), start, n);
            this.expect(this.track.claimMaxY >= this.gy + 3.0, "claimed +" + f(this.track.claimMaxY - this.gy));
            this.expect(this.track.truthMaxY <= this.gy + EPS, "truth y max +" + f(this.track.truthMaxY - this.gy));
            this.expect(Math.max(this.track.bodyMaxY, this.moveMaxY) <= this.gy + EPS, "ServerPlayer y max +" + f(Math.max(this.track.bodyMaxY, this.moveMaxY) - this.gy));
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void jesus() {
        final String id = "jesus: claims walking on water";
        this.begin(id, () -> {
            for (int x = this.ox - 2; x <= this.ox + 2; x++) {
                for (int z = this.oz + 3; z <= this.oz + 14; z++) {
                    for (int y = this.gy - 3; y <= this.gy - 1; y++) {
                        this.set(x, y, z, Blocks.WATER.defaultBlockState());
                    }
                }
            }
        });
        final int[] onWater = {0};
        final double[] previousZ = {Double.NaN};
        this.arm(id, () -> {
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
            this.c.claimOnGround = true;
            this.c.cheat = x -> {
                x.model.setVelocity(x.model.vx(), 0.0, x.model.vz());
                x.model.rebase(x.model.x(), this.gy, x.model.z());
            };
            final Runnable sample = this.probe;
            this.probe = () -> {
                sample.run();
                final Simulation s = this.c.auth.simulation();
                if (previousZ[0] - 0.3 >= this.oz + 3.05 && s.z() + 0.3 <= this.oz + 15 && s.y() >= this.gy - EPS && s.onGround()) {
                    onWater[0]++;
                }
                previousZ[0] = s.z();
            };
        });
        this.judge(40, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(this.track.claimMaxZ >= this.oz + 6.0, "claims crossed the water to z +" + f(this.track.claimMaxZ - this.oz));
            this.expect(onWater[0] == 0, "truth stood on the water surface for " + onWater[0] + " ticks");
            this.expect(this.track.truthMinY <= this.gy - 0.5 && this.track.bodyMinY <= this.gy - 0.5,
                "truth sank to " + f(this.track.truthMinY - this.gy) + ", ServerPlayer to " + f(this.track.bodyMinY - this.gy));
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void noFall() {
        final String id = "no-fall: claims onGround through a 12-block fall";
        this.begin(id, () -> {
        });
        final float[] honest = {0.0F};
        final float[] lowest = {20.0F, 20.0F};
        final double[] fallen = {0.0, 0.0};
        this.step(1, id + ": honest fall (control)", () -> {
            this.c.player.setHealth(20.0F);
            this.c.forcePosition = true;
            this.probe = () -> {
                fallen[0] = Math.max(fallen[0], this.c.player.fallDistance);
                lowest[0] = Math.min(lowest[0], this.c.player.getHealth());
            };
            this.c.player.connection.teleport(this.c.player.getX(), this.gy + 12.0, this.c.player.getZ(), 0.0F, 0.0F);
        });
        this.step(45, id + ": honest landed", () -> honest[0] = 20.0F - lowest[0]);
        this.step(25, id + ": cheat", () -> {
            this.c.player.setHealth(20.0F);
            this.base = this.snap(this.c);
            this.track = new Track();
            this.resetMoves();
            this.probe = () -> {
                this.track.sample(this.c);
                fallen[1] = Math.max(fallen[1], this.c.player.fallDistance);
                lowest[1] = Math.min(lowest[1], this.c.player.getHealth());
            };
            this.c.claimOnGround = true;
            this.c.player.connection.teleport(this.c.player.getX(), this.gy + 12.0, this.c.player.getZ(), 0.0F, 0.0F);
        });
        this.judge(45, id, () -> {
            final Snap d = this.delta(this.c);
            final Simulation s = this.c.auth.simulation();
            final float damage = 20.0F - lowest[1];
            this.expect(honest[0] >= 1.0F && damage == honest[0], "fall damage " + damage + ", an honest fall took " + honest[0]
                + " (server fall distance " + f(fallen[1]) + " vs " + f(fallen[0]) + ")");
            this.expect(d.flagMismatches() > 0, "onGround lies counted: " + d.flagMismatches());
            this.expect(Math.abs(s.y() - this.gy) <= EPS && s.onGround(), "truth landed (y +" + f(s.y() - this.gy) + ")");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void noSlowSneak() {
        final String id = "no-slow: sneaking at walking speed";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            this.c.keys = FORWARD_SHIFT;
            this.c.modelKeys = FORWARD;
            this.c.forcePosition = true;
        });
        this.judge(25, id, () -> {
            final Snap d = this.delta(this.c);
            final int n = this.c.ticks - this.armTicks;
            final Vec3 ref = this.reference(this.c, start, FORWARD_SHIFT, n, false);
            this.expectTruthIs(ref, start, n);
            this.expect(this.track.claimLead >= 0.05, "claims ran up to " + f(this.track.claimLead) + " ahead of the truth");
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void noSlowItem() {
        final String id = "no-slow: blocking with a sword at walking speed";
        this.begin(id, () -> {
        });
        this.step(1, id + ": sword", () -> this.give(this.c, new ItemStack(Items.DIAMOND_SWORD)));
        final double[] start = new double[6];
        this.step(4, id + ": cheat", () -> {
            this.base = this.snap(this.c);
            this.track = new Track();
            this.resetMoves();
            this.probe = () -> this.track.sample(this.c);
            this.armTicks = this.c.ticks;
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
            this.c.noUsePrediction = true;
            this.c.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, this.c.yaw, this.c.pitch));
        });
        this.judge(25, id, () -> {
            final Snap d = this.delta(this.c);
            final int n = this.c.ticks - this.armTicks;
            final Vec3 ref = this.reference(this.c, start, FORWARD, n, true);
            this.expectTruthIs(ref, start, n);
            this.expect(this.c.player.isUsingItem(), "the server player is blocking");
            this.expect(this.track.claimLead >= 0.05, "claims ran up to " + f(this.track.claimLead) + " ahead of the truth");
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
            this.c.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // =================================================================================================================
    // Knockback and transactions
    // =================================================================================================================

    private void ignoreKnock() {
        final String id = "anti-knockback: pongs normally, claims ignore the knock";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            this.c.forcePosition = true;
            this.c.ignoreMotion = true;
            this.knock(this.c, 0.0, 0.42, 0.6);
        });
        this.judge(20, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(this.track.truthMaxZ - start[2] >= 1.0 && this.track.truthMaxY >= this.gy + 0.3,
                "truth knocked " + f(this.track.truthMaxZ - start[2]) + " back, +" + f(this.track.truthMaxY - this.gy) + " up");
            this.expect(this.track.bodyMaxZ - start[2] >= 1.0, "ServerPlayer knocked " + f(this.track.bodyMaxZ - start[2]));
            this.expect(d.corrections() > 0 && d.forced() == 0, "the client was corrected: " + d.corrections() + " corrections, " + d.accepted()
                + " accepted, forced " + d.forced());
            this.note("claim now " + f(this.c.claimZ - this.c.auth.simulation().z()) + " from the truth");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void withholdPongs() {
        final String id = "anti-knockback: withhold every pong";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            this.c.forcePosition = true;
            this.c.ignoreMotion = true;
            this.c.holdPongs = true;
            this.knock(this.c, 0.0, 0.42, 0.6);
        });
        this.step(12, id + ": deadline", () -> {
            final Snap d = this.delta(this.c);
            this.expect(d.forced() >= 1, "unacked entries force-applied: " + d.forced() + " (deadline " + this.deadline(this.c) + " windows)");
            this.expect(this.track.truthMaxZ - start[2] >= 0.5, "the knock reached the truth without an ack (z +" + f(this.track.truthMaxZ - start[2]) + ")");
        });
        this.step(23, id + ": frozen", () -> {
            final Snap d = this.delta(this.c);
            this.expect(this.c.auth.frozen(), "frozen after " + d.nanos() / 1_000_000L + " ms without a pong");
            this.expect(d.exemptRefused() > 0, "windows refused while frozen: " + d.exemptRefused());
        });
        this.judge(65, id, () -> {
            final Snap d = this.delta(this.c);
            // pvp-issues NW-2: a stall freezes, never kicks (in HCF a kick is a combat-logger death); vanilla's keep-alive
            // decides when a connection is gone. Frozen, the cheater gains nothing: it doesn't move and its knocks landed
            this.expect(!this.c.kicked && this.c.auth.frozen(), "still frozen after " + d.nanos() / 1_000_000L + " ms, not kicked ("
                + this.kicks.getOrDefault(this.c.player.getUUID(), "") + ")");
            this.expect(this.track.truthMaxZ - start[2] >= 0.5, "the knock stayed on the truth (z +" + f(this.track.truthMaxZ - start[2]) + ")");
        });
        this.finish(id);
    }

    // Honest joins: every claim exact, no correction, from the first tick on (the chunk the player stands in is held from
    // its join on, whatever order its chunks came in)
    private void crowdJoin(final int players, final double offset, final String what) {
        this.crowdJoin(players, offset, what, false);
    }

    private void crowdJoin(final int players, final double offset, final String what, final boolean loadEarly) {
        final String id = "honest: " + what;
        final List<Cheater> crowd = new ArrayList<>();
        this.step(1, id + ": arena", () -> {
            this.open(id);
            this.label = "HONEST";
            this.clearArena();
            if (offset > 0.0) {
                // The server has the floor loaded; what the joiners hold depends on what has been sent to them
                final int cx = net.minecraft.util.Mth.floor(this.ox + offset) >> 4;
                final int cz = net.minecraft.util.Mth.floor(this.oz) >> 4;
                for (int dx = -1; dx <= players / 8 + 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        this.level.getChunk(cx + dx, cz + dz);
                    }
                }
            }
        });
        for (int i = 0; i < players; i++) {
            final int n = i;
            this.step(2, id + ": join " + n, () -> {
                final Cheater x = new Cheater(this.next("Crowd"), this.ox + 0.5 + offset + 2.0 * n, this.gy, this.oz + 3.5, 0.0F);
                x.loadEarly = loadEarly;
                crowd.add(x);
            });
        }
        this.step(WARM + 10, id + ": walk", () -> {
            for (final Cheater x : crowd) {
                this.ready(x);
                x.keys = FORWARD;
            }
        });
        this.step(20, id + ": stop", () -> {
            for (final Cheater x : crowd) {
                x.keys = NONE;
            }
        });
        this.step(20, id + ": judge", () -> {
            for (final Cheater x : crowd) {
                final AuthStats stats = x.auth.stats();
                this.honestExpect(!x.kicked, x.name + " not kicked");
                this.honestExpect(stats.corrections() == 0, x.name + " corrections " + stats.corrections());
                this.honestExpect(stats.compared() > 20 && stats.exact() == stats.compared(), x.name + " exact " + stats.exact() + "/" + stats.compared());
                this.honestExpect(x.model.z() - (this.oz + 3.5) > 2.0, x.name + " walked (z +" + f(x.model.z() - (this.oz + 3.5)) + ")");
                this.honestExpect(Math.abs(x.auth.simulation().y() - this.gy) < 1.0E-6, x.name + " stands on the floor (y " + f(x.auth.simulation().y()) + ")");
            }
        });
        this.step(1, id + ": verdict", () -> {
            this.record();
            for (final Cheater x : crowd) {
                x.leave();
            }
        });
    }

    // pvp-issues NW-2: an honest network stall freezes the player and it recovers
    private void stall(final int ticks, final boolean walking) {
        final String id = "stall: " + (ticks / 20.0) + " s with nothing in or out" + (walking ? ", walking" : ", standing");
        this.begin(id, () -> {
        });
        final boolean[] frozen = {false};
        this.arm(id, () -> {
            this.label = "HONEST";
            this.c.keys = walking ? FORWARD : NONE;
            this.c.stall = true;
            this.c.blink = true;
            final Runnable sample = this.probe;
            this.probe = () -> {
                sample.run();
                frozen[0] |= this.c.auth.frozen();
            };
        });
        this.step(ticks, id + ": the network comes back", () -> {
            this.c.keys = NONE;
            this.c.releaseHeld();
            this.c.flush();
        });
        this.judge(60, id, () -> {
            final Snap d = this.delta(this.c);
            this.honestExpect(!this.c.kicked, "not kicked (" + this.kicks.getOrDefault(this.c.player.getUUID(), "") + ")");
            this.honestExpect(frozen[0], "frozen during the stall");
            this.honestExpect(!this.c.auth.frozen(), "running again");
            this.honestExpect(d.corrections() <= 1, "recovered with " + d.corrections() + " correction(s)");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // Torture F2/F3/F4: a server teleport sent into a 1 s stall (nothing in or out, under max-unacked-ms). The burst of
    // windows the client sends after it isn't counted against the teleport's deadline, the teleport's late accept is
    // never a violation, the client lands where the truth does, and the burst drains at once
    private void stallTeleport(final int ticks) {
        final String id = "stall: " + (ticks / 20.0) + " s with a server teleport sent into it";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            this.label = "HONEST";
            this.c.keys = NONE;
            this.c.stall = true;
            this.c.blink = true;
        });
        this.step(ticks / 2, id + ": the server teleports it", () -> this.c.player.connection.teleport(this.ox + 0.5, this.gy, this.oz + 1.5, 0.0F, 0.0F));
        this.step(ticks - ticks / 2, id + ": the network comes back", () -> {
            this.c.releaseHeld();
            this.c.flush();
        });
        this.judge(40, id, () -> {
            final Snap d = this.delta(this.c);
            this.honestExpect(!this.c.kicked, "not kicked (" + this.kicks.getOrDefault(this.c.player.getUUID(), "") + ")");
            this.honestExpect(d.violations() == 0 && d.txViolations() == 0, "no violation (" + d.violations() + ", pongs " + d.txViolations() + ")");
            this.honestExpect(d.corrections() <= 1, "recovered with " + d.corrections() + " correction(s), forced " + d.forced());
            this.honestExpect(this.c.auth.stream().queued() <= 1, "the burst drained (" + this.c.auth.stream().queued() + " windows queued)");
            final double z = this.c.auth.simulation().z();
            this.honestExpect(Math.abs(z - (this.oz + 1.5)) < 1.0E-6 && Math.abs(this.c.model.z() - z) < 1.0E-9,
                "at the teleport's destination, client and truth (truth z " + f(z) + ", client z " + f(this.c.model.z()) + ")");
            this.honestExpect(!this.c.auth.teleportPending(), "the teleport is done");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void delayedPongs(final int delay, final boolean withinDeadline) {
        final String id = "anti-knockback: delay pongs " + delay + " ticks to choose when the knock lands";
        this.begin(id, () -> {
        });
        final int[] sent = {0};
        final int[] landed = {-1};
        this.arm(id, () -> {
            this.c.forcePosition = true;
            this.c.pongDelay = delay;
            sent[0] = this.c.auth.stream().currentTick();
            this.knock(this.c, 0.0, 0.42, 0.6);
            final Runnable sample = this.probe;
            this.probe = () -> {
                sample.run();
                if (landed[0] < 0 && this.c.auth.simulation().vz() > 0.2) {
                    landed[0] = this.c.auth.tick();
                }
            };
        });
        this.judge(22, id, () -> {
            final Snap d = this.delta(this.c);
            final int deadline = this.deadline(this.c);
            final int after = landed[0] - sent[0];
            if (withinDeadline) {
                this.label = "BOUNDED (lag within the deadline)";
                this.expect(landed[0] > 0 && after == delay && d.forced() == 0, "the knock landed at window +" + after + ", the client's choice +" + delay
                    + ", within the deadline " + deadline + " (forced " + d.forced() + ")");
            } else {
                this.expect(landed[0] > 0 && after <= deadline + 1 && after < delay, "the knock landed at window +" + after + ", not the client's +" + delay
                    + " (deadline " + deadline + ", forced " + d.forced() + ")");
                this.expect(d.forced() >= 1, "forced entries " + d.forced());
            }
            this.note("corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void batchedKnocks() {
        final String id = "anti-knockback: batch two knocks into one drain past the deadline";
        this.begin(id, () -> {
        });
        final int[] first = {-1};
        final int[] second = {-1};
        this.arm(id, () -> {
            this.c.forcePosition = true;
            this.c.holdPongs = true;
            this.knock(this.c, 0.0, 0.42, 0.6);
            final Runnable sample = this.probe;
            this.probe = () -> {
                sample.run();
                final Simulation s = this.c.auth.simulation();
                if (first[0] < 0 && s.vz() > 0.2) {
                    first[0] = this.c.auth.tick();
                }
                if (second[0] < 0 && s.vx() < -0.2) {
                    second[0] = this.c.auth.tick();
                }
            };
        });
        this.step(2, id + ": second knock", () -> this.knock(this.c, -0.6, 0.42, 0.0));
        this.step(6, id + ": both pongs at once", () -> this.c.releaseHeld());
        this.judge(14, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(first[0] > 0, "the first knock reached the truth on its own (window " + first[0] + ")");
            this.expect(second[0] > first[0], "the second one after it (window " + second[0] + ")");
            this.expect(d.forced() >= 1, "forced entries " + d.forced());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void pongGame(final String id, final Consumer<Cheater> play, final boolean kick) {
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            this.c.forcePosition = true;
            play.accept(this.c);
        });
        this.judge(8, id, () -> {
            final Snap d = this.delta(this.c);
            final String reason = this.kicks.getOrDefault(this.c.player.getUUID(), "");
            if (kick) {
                this.expect(this.c.kicked && reason.contains("transaction"), "kicked: '" + reason + "'");
                this.expect(d.txViolations() >= 1, "transaction violations " + d.txViolations() + ", stats " + d.violations());
            } else {
                this.expect(!this.c.kicked, "not kicked " + reason);
                this.expect(d.txViolations() == 0 && d.violations() == 0, "no violation (transactions " + d.txViolations() + ", stats " + d.violations()
                    + ", duplicates " + d.duplicates() + ")");
            }
        });
        if (!kick) {
            this.honestAfter(id, NONE, 0);
        }
        this.finish(id);
    }

    private void pongAfterKeybind() {
        final String id = "pongs: pong placed after a keybind packet";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            this.c.forcePosition = true;
            this.knock(this.c, 0.0, 0.42, 0.6);
            this.c.before = x -> {
                x.before = null;
                x.drainAfterActions = true;
                x.actions.add(new ServerboundSetCarriedItemPacket(1));
            };
        });
        this.judge(12, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(d.streamViolations() >= 1, "grammar violation counted: " + d.streamViolations());
            // §15.8: an ordering-only violation is counted, not kicked (a Lunar frame-time packet can make one). The pong keeps
            // its arrival place, so the knock it releases lands in that tick all the same: no choice of when it lands
            final String reason = this.kicks.getOrDefault(this.c.player.getUUID(), "");
            this.expect(!this.c.kicked, "counted, not kicked " + reason);
            this.expect(this.c.auth.simulation().z() - start[2] >= 0.3, "the knock reached the truth (z +" + f(this.c.auth.simulation().z() - start[2]) + ")");
            this.expect(d.forced() == 0, "applied at its pong, not forced (" + d.forced() + ")");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // §15.8: a second PlayerInput in one tick (a modded client's frame-time key press) is counted; the last one counts
    private void twoInputs() {
        final String id = "grammar: two PlayerInput packets in one tick";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            this.c.keys = FORWARD;
            this.c.before = x -> {
                x.before = null;
                x.actions.add(new ServerboundPlayerInputPacket(JUMP));
            };
        });
        this.judge(12, id, () -> {
            final Snap d = this.delta(this.c);
            final String reason = this.kicks.getOrDefault(this.c.player.getUUID(), "");
            this.expect(!this.c.kicked, "counted, not kicked " + reason);
            this.expect(d.streamViolations() >= 1, "grammar violation counted: " + d.streamViolations());
            this.expect(d.corrections() == 0, "the last input before the move is the tick's: no jump, no correction (" + d.corrections() + ")");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // =================================================================================================================
    // Timer
    // =================================================================================================================

    private void timer(final int tps) {
        final String id = "timer: " + tps + " tps for 4 s";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            this.c.keys = FORWARD;
            this.c.rate = tps;
        });
        this.judge(80, id, () -> {
            final Snap d = this.delta(this.c);
            final AuthoritativeSettings settings = Revival.settings().authoritative();
            final long ms = d.nanos() / 1_000_000L;
            final long budget = ms / 50L + settings.maxBankedTicks() + 1;
            // Refused windows are run (their actions update the client model) but move nothing: only the moving ones count
            final long moving = d.windows() - d.exemptRefused();
            this.expect(moving <= budget, "windows that moved " + moving + " of " + d.closed() + " sent in " + ms + " ms (real-time budget " + budget
                + ", refused " + d.exemptRefused() + ")");
            this.expect(d.closed() > d.windows(), "the extra ticks waited or were refused: queued " + this.c.auth.stream().queued() + ", refused " + d.refused());
            if (tps >= 30) {
                this.expect(d.refused() > 0, "past timer-queue-cap-ms windows are refused: " + d.refused());
            }
            final int moved = (int) (d.windows() - d.exemptRefused());
            final Vec3 ref = this.reference(this.c, start, FORWARD, moved, false);
            this.expect(this.c.auth.simulation().z() <= ref.z + EPS, "the truth walked " + f(this.c.auth.simulation().z() - start[2])
                + ", no further than " + moved + " ticks of walking (" + f(ref.z - start[2]) + ")");
        });
        this.honestAfter(id, NONE, 40);
        this.finish(id);
    }

    private void blink() {
        final String id = "timer: blink (1.2 s without packets, then the burst)";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        final long[] burst = {0};
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            this.c.keys = FORWARD;
            this.c.blink = true;
        });
        this.step(24, id + ": burst", () -> {
            this.expect(Math.max(this.track.bodyMaxZ, this.moveMaxZ) <= start[2] + EPS, "during the blink the ServerPlayer stayed (z +"
                + f(Math.max(this.track.bodyMaxZ, this.moveMaxZ) - start[2]) + ")");
            final long before = this.c.auth.stats().windows();
            this.c.flush();
            burst[0] = this.c.auth.stats().windows() - before;
            this.track.maxPerTick = 0;
            this.track.lastWindows = this.c.auth.stats().windows();
            final int bank = Revival.settings().authoritative().maxBankedTicks();
            // Torture F4: the bank, then the bank's worth of time it forfeited, owed to the catch-up ticks right behind it
            // (an honest stall's burst must drain); never more: a longer blink is refused past timer-queue-cap-ms
            this.expect(burst[0] <= 2 * bank + 1, "the burst ran " + burst[0] + " of 24 windows at once (bank " + bank + ", owed at most " + bank + ")");
        });
        this.judge(20, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(this.track.maxPerTick <= 2, "then at most " + this.track.maxPerTick + " per server tick");
            final int moved = (int) (d.windows() - d.exemptRefused());
            final Vec3 ref = this.reference(this.c, start, FORWARD, moved, false);
            this.expect(this.c.auth.simulation().z() <= ref.z + EPS, "the truth walked " + f(this.c.auth.simulation().z() - start[2]) + " for " + moved + " windows");
            this.note("refused " + d.refused() + ", queued " + this.c.auth.stream().queued());
        });
        this.honestAfter(id, NONE, 20);
        this.finish(id);
    }

    private void slowThenBurst() {
        final String id = "timer: 15 tps for 3 s, then a 10-tick burst";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            this.c.keys = FORWARD;
            this.c.rate = 15.0;
        });
        this.judge(60, id, () -> {
            final AuthoritativeSettings settings = Revival.settings().authoritative();
            final long before = this.c.auth.stats().windows();
            for (int i = 0; i < 10; i++) {
                this.c.clientTick();
            }
            final long ran = this.c.auth.stats().windows() - before;
            final long cap = (long) Math.ceil((settings.timerDriftMs() + 100) / 50.0) + 1;
            this.expect(ran <= cap, "the ack clock let " + ran + " of 10 run at once (cap " + cap + ", bank " + settings.maxBankedTicks() + ")");
            this.c.rate = 20.0;
        });
        this.honestAfter(id, NONE, 20);
        this.finish(id);
    }

    private void noTickEnd() {
        final String id = "timer: TickEnd never sent";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            this.c.keys = FORWARD;
            this.c.noTickEnd = true;
            this.c.before = x -> {
                for (int i = 0; i < 4; i++) {
                    x.extraMoves.add(new ServerboundMovePlayerPacket.Pos(new Vec3(x.model.x(), x.model.y(), x.model.z()), true, false));
                }
            };
        });
        this.judge(110, id, () -> {
            final Snap d = this.delta(this.c);
            final int items = openWindowItems(this.c.auth.stream());
            this.expect(d.windows() == 0 && Math.abs(this.c.auth.simulation().z() - start[2]) <= EPS && Math.max(this.track.bodyMaxZ, this.moveMaxZ) <= start[2] + EPS,
                "nothing moved: windows run " + d.windows() + ", truth z +" + f(this.c.auth.simulation().z() - start[2]));
            this.expect(d.streamViolations() > 0, "grammar violations " + d.streamViolations());
            this.expect(items >= 0 && items <= ClientStream.MAX_ITEMS + 8, "the open window holds " + items + " packets (cap " + ClientStream.MAX_ITEMS + ")");
            this.note((this.c.kicked ? "kicked" : "not kicked") + ", frozen " + this.c.auth.frozen());
        });
        this.finish(id);
    }

    private void twoMoves() {
        final String id = "timer: two move claims in one window";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            this.c.forcePosition = true;
            this.c.before = x -> {
                x.before = null;
                x.extraMoves.add(new ServerboundMovePlayerPacket.Pos(new Vec3(x.model.x(), x.model.y(), x.model.z() + 2.0), true, false));
            };
        });
        this.judge(8, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(d.streamViolations() >= 1, "grammar violation counted: " + d.streamViolations());
            this.expect(this.track.truthMaxZ <= start[2] + EPS && Math.max(this.track.bodyMaxZ, this.moveMaxZ) <= start[2] + EPS,
                "neither claim was taken (truth z +" + f(this.track.truthMaxZ - start[2]) + ")");
            this.note(this.c.kicked ? "kicked" : "not kicked, the window ran (vanilla kicks a second position claim)");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // =================================================================================================================
    // Teleports
    // =================================================================================================================

    private void spoofedAccept() {
        final String id = "teleport: accept a server teleport with spoofed coordinates";
        this.begin(id, () -> {
        });
        final double[] target = new double[3];
        final int[] high = {0};
        this.arm(id, () -> {
            target[0] = this.c.player.getX();
            target[1] = this.gy;
            target[2] = this.c.player.getZ() + 6.0;
            this.c.forcePosition = true;
            this.c.acceptOffset = new Vec3(0.0, 6.0, 0.0);
            this.c.holdAfterDrain = true;
            this.c.blinkTicks = 10;
            this.c.player.connection.teleport(target[0], target[1], target[2], 0.0F, 0.0F);
            final Runnable sample = this.probe;
            this.probe = () -> {
                sample.run();
                if (this.c.player.getY() >= target[1] + 5.0) {
                    high[0]++;
                }
            };
        });
        this.judge(16, id, () -> {
            final Snap d = this.delta(this.c);
            final Simulation s = this.c.auth.simulation();
            final double top = Math.max(this.track.bodyMaxY, this.moveMaxY);
            this.expect(top <= target[1] + 1.0E-6, "ServerPlayer y max +" + f(top - target[1]) + " (" + high[0] + " ticks at the spoofed height)");
            this.expect(d.teleportMismatches() >= 1, "the accept was flagged: mismatches " + d.teleportMismatches());
            this.expect(Math.abs(s.z() - target[2]) <= 1.0E-6 && Math.abs(s.y() - target[1]) <= 1.0E-6, "the truth took the teleport (z " + f(s.z() - target[2])
                + ", y " + f(s.y() - target[1]) + ")");
            this.expect(this.c.player.position().distanceTo(new Vec3(s.x(), s.y(), s.z())) <= 1.0E-6, "the ServerPlayer is back on the truth");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void neverAccept() {
        final String id = "teleport: never accept a server teleport";
        this.begin(id, () -> {
        });
        final double[] target = new double[3];
        this.arm(id, () -> {
            target[0] = this.c.player.getX();
            target[1] = this.gy;
            target[2] = this.c.player.getZ() + 4.0;
            this.c.forcePosition = true;
            this.c.acceptServerTeleports = false;
            this.c.player.connection.teleport(target[0], target[1], target[2], 0.0F, 0.0F);
        });
        this.step(4, id + ": walk", () -> this.c.keys = FORWARD);
        this.judge(20, id, () -> {
            final Simulation s = this.c.auth.simulation();
            final double gap = this.c.player.position().distanceTo(new Vec3(s.x(), s.y(), s.z()));
            // §15.3: the teleport completes at its pong (the ServerPlayer follows the truth from there); an accept that never
            // follows gains nothing, so it is counted after the ack deadline, not kicked (§15.8)
            this.expect(s.z() >= target[2], "the truth took the teleport and walked on (z +" + f(s.z() - target[2]) + " past it)");
            this.expect(gap <= 1.0E-6, "the ServerPlayer follows the truth (it is " + f(gap) + " away, at z +" + f(this.c.player.getZ() - target[2]) + ")");
            final String reason = this.kicks.getOrDefault(this.c.player.getUUID(), "");
            this.expect(!this.c.kicked, "counted, not kicked " + reason);
            this.expect(this.c.auth.violationReasons().getInt("a server teleport was never accepted") >= 1,
                "counted after the ack deadline: " + this.c.auth.violationReasons());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void bogusCorrectionAccept() {
        final String id = "teleport: accept an id never sent (correction band) to drop fake walls";
        this.begin(id, () -> {
        });
        this.step(1, id + ": fake wall", () -> {
            for (int x = this.ox - 2; x <= this.ox + 2; x++) {
                for (int y = 0; y < 2; y++) {
                    this.c.player.connection.send(new ClientboundBlockUpdatePacket(new BlockPos(x, this.gy + y, this.oz + 3), Blocks.STONE.defaultBlockState()));
                }
            }
        });
        final int[] sent = {0};
        this.step(3, id + ": control", () -> {
            this.base = this.snap(this.c);
            this.track = new Track();
            this.resetMoves();
            this.probe = () -> this.track.sample(this.c);
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
        });
        this.step(20, id + ": cheat", () -> {
            final Snap d = this.delta(this.c);
            this.expect(this.track.truthMaxZ <= this.oz + 3 - 0.3 + EPS && d.corrections() == 0,
                "control: the fake wall stops truth and client alike (z " + f(this.track.truthMaxZ - (this.oz + 2.7)) + ", corrections " + d.corrections() + ")");
            sent[0] = this.c.auth.world().sentCount();
            this.base = this.snap(this.c);
            this.track = new Track();
            this.c.preDrain.add(new ServerboundAcceptTeleportationPacket(Integer.MIN_VALUE + 4242, this.c.model.x(), this.c.model.y(), this.c.model.z(), 0.0F, 0.0F));
            this.c.world.clear(); // the cheater's own client lets go of the fake blocks
        });
        this.judge(25, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(this.track.truthMaxZ <= this.oz + 3 - 0.3 + EPS, "the truth still stops at the fake wall (z max " + f(this.track.truthMaxZ - (this.oz + 2.7))
                + " past it; sent states " + sent[0] + " -> " + this.c.auth.world().sentCount() + ")");
            this.expect(d.violations() >= 1, "the accept counted: violations " + d.violations());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void bogusVanillaAccept() {
        final String id = "teleport: accept a vanilla id never sent";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            this.c.forcePosition = true;
            this.c.preDrain.add(new ServerboundAcceptTeleportationPacket(777, this.c.model.x(), this.c.model.y() + 5.0, this.c.model.z(), 0.0F, 0.0F));
        });
        this.judge(6, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(Math.max(this.track.bodyMaxY, this.moveMaxY) <= this.gy + EPS && this.track.truthMaxY <= this.gy + EPS, "nothing moved");
            this.expect(d.violations() + d.teleportMismatches() >= 1, "flagged: violations " + d.violations() + ", accept mismatches " + d.teleportMismatches());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void nonFinite(final String id, final Consumer<Cheater> claim) {
        this.begin(id, () -> {
        });
        final int[] before = {0};
        final int[] errors = {0};
        this.arm(id, () -> {
            this.c.forcePosition = true;
            before[0] = this.c.positions.size();
            errors[0] = this.c.serverErrors;
            this.c.before = x -> {
                x.before = null;
                claim.accept(x);
            };
        });
        this.judge(8, id, () -> {
            final Snap d = this.delta(this.c);
            final Simulation s = this.c.auth.simulation();
            int bad = 0;
            for (int i = before[0]; i < this.c.positions.size(); i++) {
                bad += finite(this.c.positions.get(i).change()) ? 0 : 1;
            }
            this.expect(Double.isFinite(s.x()) && Double.isFinite(s.y()) && Double.isFinite(s.z()) && finite(this.c.player.position())
                && Float.isFinite(this.c.player.getYRot()) && Float.isFinite(this.c.player.getXRot()), "truth and ServerPlayer stay finite");
            this.expect(bad == 0, "non-finite position packets sent to the client: " + bad);
            this.expect(this.c.serverErrors == errors[0], "windows handled without an exception (" + (this.c.serverErrors - errors[0]) + " thrown"
                + (this.c.serverErrors > errors[0] ? ", last " + this.c.lastServerError : "") + ")");
            this.expect(this.c.kicked || d.violations() + d.streamViolations() + d.txViolations() >= 1,
                "rejected: " + (this.c.kicked ? "kicked '" + this.kicks.get(this.c.player.getUUID()) + "'" : "violations " + d.violations()));
            this.note("corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void nanServerAccept() {
        final String id = "non-finite: NaN accept for a server teleport";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            this.c.forcePosition = true;
            this.c.acceptNaN = true;
            this.c.player.connection.teleport(this.c.player.getX(), this.gy, this.c.player.getZ() + 2.0, 0.0F, 0.0F);
        });
        this.judge(6, id, () -> {
            this.expect(finite(this.c.player.position()), "ServerPlayer finite");
            this.expect(this.c.kicked, "kicked: '" + this.kicks.getOrDefault(this.c.player.getUUID(), "") + "'");
        });
        this.finish(id);
    }

    private void nanCorrectionAccept() {
        final String id = "non-finite: NaN accept for our correction";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            this.c.forcePosition = true;
            this.c.acceptCorrectionNaN = true;
            this.c.before = x -> {
                x.before = null;
                x.cheat = y -> {
                    y.cheat = null;
                    y.model.rebase(y.model.x(), y.model.y(), y.model.z() + 0.5);
                };
            };
        });
        this.judge(10, id, () -> {
            final Snap d = this.delta(this.c);
            final Simulation s = this.c.auth.simulation();
            this.expect(Double.isFinite(s.z()) && finite(this.c.player.position()), "truth and ServerPlayer finite");
            this.expect(d.violations() >= 1, "the NaN accept counted: violations " + d.violations());
            // A non-finite accept is a protocol violation under enforce (vanilla kicks one for its own teleports); short of
            // a kick, the correction must not be left in flight
            this.expect(this.c.kicked || !this.c.auth.reconciler().inFlight(), "kicked or the correction is not left in flight (corrections "
                + d.corrections() + ", reconciler violations " + this.c.auth.reconciler().violations() + ", kicked " + this.c.kicked + ")");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // =================================================================================================================
    // Creep and overlap
    // =================================================================================================================

    private void creep(final String id, final boolean door, final double perTick, final int ticks) {
        this.begin(id, () -> {
            if (door) {
                this.wall(1, 2, Blocks.STONE.defaultBlockState());
                this.door(1);
            } else {
                this.gate(1);
            }
        });
        final double[] touch = {0.0};
        final double[] depth = {0.0};
        final long[] creepCorrections = {0};
        this.arm(id, () -> {
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
        });
        this.step(15, id + ": creep", () -> {
            final double face = this.face(1);
            touch[0] = this.c.auth.simulation().z();
            this.expect(Math.abs(touch[0] - (face - 0.3)) <= 1.0E-7, "touching the face (" + f(touch[0] - (face - 0.3)) + ")");
            this.base = this.snap(this.c);
            this.track = new Track();
            final int[] k = {0};
            this.c.keys = NONE;
            this.c.cheat = x -> {
                k[0]++;
                x.model.setVelocity(0.0, x.model.vy(), 0.0);
                x.model.rebase(x.model.x(), x.model.y(), touch[0] + k[0] * perTick);
            };
        });
        this.step(ticks, id + ": walk on", () -> {
            depth[0] = this.c.auth.simulation().z() - touch[0];
            creepCorrections[0] = this.delta(this.c).corrections();
            this.c.cheat = null;
            this.c.keys = FORWARD;
        });
        this.judge(25, id, () -> {
            final Snap d = this.delta(this.c);
            final double face = this.face(1);
            this.expect(this.track.truthMaxZ <= face - 0.3 + 1.0E-12, "after " + ticks + " creeping claims the truth sat " + f(depth[0])
                + " inside, then reached " + f(this.track.truthMaxZ - (face - 0.3)) + " past the face");
            this.note("claims exact " + d.exact() + "/" + d.compared() + ", corrections while creeping " + creepCorrections[0] + ", in all " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void floorCreep() {
        final String id = "creep: 0.9e-10 per tick down into the floor (rebase-epsilon 1e-10)";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            final int[] k = {0};
            this.c.forcePosition = true;
            this.c.cheat = x -> {
                k[0]++;
                x.model.rebase(x.model.x(), this.gy - k[0] * 0.9E-10, x.model.z());
            };
        });
        this.judge(100, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(this.track.truthMinY >= this.gy - 1.0E-10 - 1.0E-13, "gravity sets the truth back on the floor every tick (min " + f(this.track.truthMinY - this.gy) + ")");
            this.note("exact " + d.exact() + "/" + d.compared() + ", corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void overlap() {
        final String id = "overlap: claims 0.05 inside a wall";
        this.begin(id, () -> this.wall(1, 2, Blocks.STONE.defaultBlockState()));
        this.arm(id, () -> {
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
        });
        this.step(12, id + ": overlap", () -> {
            final double face = this.face(1);
            this.c.keys = NONE;
            this.c.cheat = x -> x.model.rebase(x.model.x(), x.model.y(), face - 0.3 + 0.05);
            this.base = this.snap(this.c);
            this.track = new Track();
        });
        this.judge(12, id, () -> {
            final Snap d = this.delta(this.c);
            final double face = this.face(1);
            this.expect(this.track.claimMaxZ >= face - 0.3 + 0.049, "claimed " + f(this.track.claimMaxZ - (face - 0.3)) + " inside");
            this.expect(this.track.truthMaxZ <= face - 0.3 + EPS, "truth z max " + f(this.track.truthMaxZ - (face - 0.3)) + " past the face");
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void epsilonStep(final double value) {
        this.step(1, "cheat suite: rebase-epsilon " + value, () -> {
            this.configure("authoritative.rebase-epsilon", value);
            this.tests.check(Revival.settings().authoritative().rebaseEpsilon() == value, "authoritative.rebase-epsilon is " + value + " after /revival reload");
        });
    }

    private void vehiclesStep(final String value) {
        this.step(1, "cheat suite: vehicles " + value, () -> {
            this.configure("authoritative.vehicles", value);
            this.tests.check(value.equals(Revival.settings().authoritative().vehicles()), "authoritative.vehicles is " + value + " after /revival reload");
        });
    }

    // Edits revival.yml and reloads it, as an owner would
    private void configure(final String key, final Object value) {
        try {
            final File file = new File("revival.yml");
            final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            yaml.set(key, value);
            yaml.save(file);
        } catch (final java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        gg.hcfrevival.revival.config.RevivalConfig.reload();
    }

    // =================================================================================================================
    // Combat
    // =================================================================================================================

    private void combatJoin(final String id) {
        this.begin(id, () -> {
        });
        this.step(1, id + ": victim joins", () -> this.v = new Cheater(this.next("Victim"), this.ox + 0.5, this.gy, this.oz + 0.5 + 2.8, 180.0F));
        this.step(WARM + 5, id + ": both under enforce", () -> {
            this.ready(this.v);
            this.tests.check(CombatWiring.book(this.c.auth).knows(this.v.player.getId()), this.scenario + ": the attacker's client has the victim");
        });
    }

    // Victim at (dx, dz) from the attacker's lane start; the attacker looks along +z
    private void place(final double dx, final double dz) {
        this.v.player.connection.teleport(this.ox + 0.5 + dx, this.gy, this.oz + 0.5 + dz, 180.0F, 0.0F);
    }

    private void attack(final Cheater x, final int count) {
        for (int i = 0; i < count; i++) {
            x.actions.add(new ServerboundAttackPacket(this.v.player.getId()));
            x.actions.add(new net.minecraft.network.protocol.game.ServerboundPunchPacket()); // startAttack swings after it
        }
    }

    private int attacksOf(final Cheater x) {
        return this.attacks.getOrDefault(x.player.getUUID(), 0);
    }

    private void open(final String name) {
        this.scenario = name;
        this.broken.clear();
        this.honestBroken.clear();
        this.evidence.setLength(0);
        this.label = "";
    }

    private void attackCase(final String name, final double dx, final double dz, final @Nullable Runnable setup, final int count, final int expectLanded,
                            final @Nullable Runnable teardown) {
        this.step(1, name + ": place", () -> {
            this.open(name);
            this.place(dx, dz);
            if (setup != null) {
                setup.run();
            }
        });
        this.step(12, name + ": attack", () -> {
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            this.c.yaw = 0.0F;
            this.c.pitch = 0.0F;
            this.attack(this.c, count);
        });
        this.step(3, name + ": judge", () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            final String detail = "landed " + landed + " of " + count + " (judged valid " + d.attacksAccepted() + ", invalid " + d.attacksRejected()
                + ", over cap " + d.overCap() + ")";
            if (expectLanded > 0) {
                if (count == 1) {
                    this.label = "CONTROL";
                    this.honestExpect(landed == 1 && d.attacksAccepted() == 1, "an honest hit lands: " + detail);
                } else {
                    this.expect(landed == expectLanded && d.overCap() == count - expectLanded, detail);
                }
            } else {
                this.expect(landed == 0, "refused: " + detail);
            }
            if (teardown != null) {
                teardown.run();
            }
            this.record();
        });
    }

    private void combatReach() {
        final String id = "combat";
        this.combatJoin(id);
        this.attackCase("combat: honest hit at 2.5 (control)", 0.0, 2.8, null, 1, 1, null);
        this.attackCase("combat: reach 3.5", 0.0, 3.8, null, 1, 0, null);
        this.attackCase("combat: reach 4.5", 0.0, 4.8, null, 1, 0, null);
        this.attackCase("combat: reach 6.0", 0.0, 6.3, null, 1, 0, null);
        this.attackCase("combat: through a wall at 2.5", 0.0, 2.8, () -> this.wallAt(1, 3), 1, 0, () -> this.wallAt(1, 0));
        this.attackCase("combat: killaura without aim (target 2 blocks to the side)", 2.0, 0.0, null, 1, 0, null);
        this.attackCase("combat: 5 attacks in one window (autoclicker)", 0.0, 2.8, null, 5, 1, null);
        this.attackCase("combat: honest hit at 2.5 after the cheats (control)", 0.0, 2.8, null, 1, 1, null);
        this.step(1, id + ": done", this::leaveAll);
    }

    // Classic audit #1: the attack's eye follows a claim off the truth only for a gap the server caused (a forced knock, a
    // refused window, a denied break). A claim that leads the truth toward the target on its own, at once or creeping
    // inside the belief's forecast, casts from the truth; so does one that puts the eye past a pane
    private void claimReach() {
        this.claimLead("combat: claims 0.49 toward a target 3.3 from the true eye before the swing", false);
        this.claimLead("combat: creeps 0.025 a tick toward a target 3.3 from the true eye, then swings", true);
        this.claimThroughPane();
    }

    private void claimLead(final String id, final boolean creep) {
        this.combatJoin(id);
        final double[] z0 = new double[1];
        final long[] refused = new long[1];
        this.step(1, id + ": place", () -> {
            this.open(id);
            this.place(0.0, 3.6); // its near face 3.3 from the true eye, 2.81 from a claim 0.49 ahead
            z0[0] = this.c.auth.simulation().z();
            final int[] k = {0};
            this.c.forcePosition = true;
            this.c.cheat = x -> {
                k[0]++;
                x.model.setVelocity(0.0, x.model.vy(), 0.0);
                x.model.rebase(x.model.x(), x.model.y(), z0[0] + (creep ? Math.min(0.49, 0.025 * k[0]) : 0.49));
            };
        });
        this.step(creep ? 24 : 12, id + ": attack", () -> {
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            refused[0] = this.c.auth.stats().eyeOffsetsRefused();
            this.c.yaw = 0.0F;
            this.c.pitch = 0.0F;
            this.attack(this.c, 1);
        });
        this.judge(3, id, () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            this.expect(landed == 0, "refused: landed " + landed + " (judged valid " + d.attacksAccepted() + ", invalid " + d.attacksRejected()
                + ") with the claim " + f(this.c.claimZ - z0[0]) + " ahead, verdict " + this.c.auth.lastHitVerdict());
            this.note("eye offsets refused " + (this.c.auth.stats().eyeOffsetsRefused() - refused[0]) + ", corrections " + d.corrections());
            this.c.cheat = null;
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void claimThroughPane() {
        final String id = "combat: claims 0.49 through a glass pane it touches, target 1.5 behind the pane";
        this.combatJoin(id);
        final double[] z0 = new double[1];
        this.step(1, id + ": the pane; walk up to it", () -> {
            this.open(id);
            final BlockState pane = Blocks.GLASS_PANE.defaultBlockState().setValue(net.minecraft.world.level.block.CrossCollisionBlock.EAST, true)
                .setValue(net.minecraft.world.level.block.CrossCollisionBlock.WEST, true);
            this.wall(2, 3, pane); // its faces at oz + 2.4375 and 2.5625
            this.place(0.0, 2.0625 + 1.5 + 0.3); // 1.5 behind the far face
            this.c.forcePosition = true;
            this.c.keys = FORWARD;
        });
        this.step(20, id + ": against the pane", () -> {
            this.c.keys = NONE;
            z0[0] = this.c.auth.simulation().z();
            this.tests.check(Math.abs(z0[0] - (this.oz + 2.4375 - 0.3)) <= 1.0E-6, this.scenario + ": the attacker touches the pane (z "
                + f(z0[0] - this.oz) + ")");
            this.c.cheat = x -> {
                x.model.setVelocity(0.0, x.model.vy(), 0.0);
                x.model.rebase(x.model.x(), x.model.y(), z0[0] + 0.49); // the eye 0.065 past the far face
            };
        });
        this.step(12, id + ": attack", () -> {
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            this.c.yaw = 0.0F;
            this.c.pitch = 0.0F;
            this.attack(this.c, 1);
        });
        this.judge(3, id, () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            this.expect(landed == 0, "no hit through the pane: landed " + landed + " (judged valid " + d.attacksAccepted() + ", invalid "
                + d.attacksRejected() + "), verdict " + this.c.auth.lastHitVerdict());
            this.c.cheat = null;
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // pvp-issues HR-2: honest hits at a long round trip land; a stale view with prompt pongs doesn't
    // Review X2-01 / R2C-03: the victim's link stops (nothing in or out) for 2 s, past max-unacked-ms; its last path step
    // ages past the attacker's rewind cap, and the judge asks the truth at the capped age: still the newest step
    private void hitStoppedTarget() {
        final String id = "combat: hit a target whose link stopped 2 s ago, where it stopped";
        this.combatJoin(id);
        this.step(1, id + ": its link stops", () -> {
            this.v.stall = true;
            this.v.blink = true;
        });
        this.step(40, id + ": attack", () -> {
            this.open(id);
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            this.c.yaw = 0.0F;
            this.c.pitch = 0.0F;
            this.attack(this.c, 1);
        });
        this.step(3, id + ": judge", () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            this.label = "CONTROL";
            this.honestExpect(landed == 1 && d.attacksAccepted() == 1, "hittable where it stopped: landed " + landed + ", verdict "
                + this.c.auth.lastHitVerdict() + ", rewind " + this.c.auth.lastHitRewindMillis() + " ms past the cap " + this.c.auth.lastRewindCap() + " ms");
            this.v.releaseHeld();
            this.v.flush();
            this.record();
        });
        this.step(1, id + ": done", this::leaveAll);
    }

    // Review X2-03 / X2-05: a server teleport reaches a client that holds its pongs past the deadline while walking. The
    // truth takes it at the force and walks on from there (written, replicated); the late landing re-applies nothing
    private void heldTeleportPong() {
        final String id = "teleport: a server teleport that reaches the client 1.2 s late, walking";
        this.begin(id, () -> {
        });
        final double[] forced = new double[3];
        final double[] before = new double[1];
        this.arm(id, () -> {
            this.label = "HONEST";
            this.c.latencyTicks = 24; // the teleport, its ping and everything after it arrive 24 ticks late
            this.c.yaw = 0.0F;
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
            this.c.player.connection.teleport(this.ox + 0.5, this.gy, this.oz + 0.5, 0.0F, 0.0F); // back to the start
            before[0] = this.c.auth.stats().forcedLanded();
        });
        this.step(1, id + ": the link is quick again (what was sent still arrives in order)", () -> this.c.latencyTicks = 0);
        this.step(11, id + ": forced", () -> {
            forced[0] = this.c.player.getX();
            forced[1] = this.c.player.getY();
            forced[2] = this.c.player.getZ();
        });
        this.step(12, id + ": still held", () -> {
            this.honestExpect(this.c.player.getZ() > forced[2] + 0.5, "the ServerPlayer walks on from the forced teleport before the client has it (z +"
                + f(this.c.player.getZ() - forced[2]) + ")");
            forced[2] = this.c.player.getZ();
        });
        this.step(10, id + ": landed", () -> {
            this.honestExpect(this.c.auth.stats().forcedLanded() > before[0], "the teleport landed late (" + (this.c.auth.stats().forcedLanded() - before[0]) + ")");
            this.honestExpect(this.c.player.getZ() >= forced[2] - 1.0E-9, "the landing rolled nothing back (z " + f(this.c.player.getZ() - forced[2]) + " since the release)");
            this.c.keys = NONE;
        });
        this.judge(20, id, () -> {
            final Snap d = this.delta(this.c);
            this.honestExpect(!this.c.kicked, "not kicked");
            this.honestExpect(d.violations() == 0, "no violation (accept matched): " + d.violations());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // Torture r80down300w (2026-10-22): a knock forced at its deadline whose real pong came in the next window. The pong
    // took it off the forced and in-flight sets before that window's drain landed it, so the correction resolved first
    // forecast the client without it; the client applied both, and five corrections chased the double. The delay is the
    // deadline plus 0, 1 or 2 ticks, so one of them lands the pong in the window right after the forcing
    private void heldKnockPong(final int extra) {
        final String id = "knock: a knock that reaches the client " + extra + " ticks past its deadline, walking";
        this.begin(id, () -> {
        });
        final long[] before = new long[2];
        final int[] delay = new int[1];
        this.arm(id, () -> {
            this.label = "HONEST";
            this.c.yaw = 0.0F;
            this.c.keys = FORWARD;
            before[0] = this.c.auth.stats().forcedLanded();
            before[1] = this.c.auth.stats().forcedEntries();
            delay[0] = this.deadline(this.c) + extra;
            this.c.latencyTicks = delay[0]; // the knock, its ping and what follows arrive that late
            this.knock(this.c, 0.0, 0.42, 0.6);
        });
        this.step(1, id + ": the link is quick again (what was sent still arrives in order)", () -> this.c.latencyTicks = 0);
        this.step(30, id + ": landed", () -> { // the deadline is a few windows on a local link
            this.note("delay " + delay[0] + " ticks; forced " + (this.c.auth.stats().forcedEntries() - before[1]) + ", landed "
                + (this.c.auth.stats().forcedLanded() - before[0]));
            this.c.keys = NONE;
        });
        this.judge(20, id, () -> {
            final Snap d = this.delta(this.c);
            this.honestExpect(!this.c.kicked, "not kicked");
            this.honestExpect(d.corrections() <= 1, "at most one correction for the late knock: " + d.corrections() + " (" + d.exact() + "/" + d.compared()
                + " claims exact)");
            this.honestExpect(d.violations() == 0, "no violation: " + d.violations());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void combatLatency(final int ticks) {
        final String id = "combat: an honest hit at " + ticks * 50 + " ms of latency";
        this.combatJoin(id);
        this.step(1, id + ": latency", () -> this.c.latencyTicks = ticks);
        this.step(40, id + ": the round trip settles", () -> this.tests.getLogger().info("REVIVAL_CHEAT_NOTE " + id + ": rtt base "
            + this.c.auth.transactions().rttBaseNanos() / 1_000_000L + " ms"));
        this.attackCase(id + " (control)", 0.0, 2.8, null, 1, 1, () -> this.tests.getLogger().info("REVIVAL_CHEAT_NOTE " + id + ": rewind "
            + this.c.auth.lastHitRewindMillis() + " ms, cap " + this.c.auth.lastRewindCap() + " ms (display " + this.c.auth.lastDisplayDelay() + " ms), verdict "
            + this.c.auth.lastHitVerdict()));
        this.step(1, id + ": done", this::leaveAll);
    }

    // The rewind-cap policy (2026-10-22): ping made good up to rtt-ceiling-ms (250) plus the client's own display delay,
    // jitter and tick waits. Expected at 300 ms on a target sprinting across the line of sight: the hit lands (the ceiling
    // plus the 150 ms tick-wait slack still covers a 300 ms round trip and its waits); past roughly 350-400 ms of steady
    // ping a hit is judged at the target's truth at the capped age, so the shooter leads moving players (RewindCapTest)
    private void combatLatencyMoving(final int ticks) {
        final String id = "combat: an honest hit at " + ticks * 50 + " ms of latency on a target sprinting across";
        this.combatJoin(id);
        this.step(1, id + ": latency", () -> this.c.latencyTicks = ticks);
        // The ping just went up: rttBase follows once 29 of the last 32 acks are above it (RttBaseline, about 1.5 s plus
        // the round trip), and from then on the deadline is sized from the new ping, so nothing is forced
        final long[] forcedAtSettle = {0L};
        this.step(50, id + ": the round trip settles", () -> {
            forcedAtSettle[0] = this.c.auth.stats().forcedEntries();
            this.tests.getLogger().info("REVIVAL_CHEAT_NOTE " + id + ": rtt base " + this.c.auth.transactions().rttBaseNanos() / 1_000_000L
                + " ms, deadline " + this.deadline(this.c) + " windows, forced so far " + forcedAtSettle[0]);
        });
        this.step(1, id + ": the target takes its place", () -> {
            this.open(id);
            this.label = "CONTROL";
            this.place(-1.8, 2.2); // it crosses in front, inside reach (3.0) when the attack is made
        });
        // A teleport leaves the attacker's mirror inexact until the feed re-spawns it (replication.repair-interval-ticks);
        // the yaw is set after the teleport (which turns the target to face the attacker) reached its client
        this.step(30, id + ": the target sprints across", () -> {
            this.v.yaw = -90.0F; // facing +x: across the attacker's line of sight
            this.v.keys = FORWARD_SPRINT;
        });
        this.step(8, id + ": aim where the attacker's client shows it, attack", () -> {
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            final gg.hcfrevival.revival.auth.combat.RemoteMirror shown = CombatWiring.book(this.c.auth).mirror(this.v.player.getId());
            final Simulation s = this.c.auth.simulation();
            if (shown != null) {
                final double dx = shown.x() - s.x();
                final double dy = shown.y() + shown.height() * 0.5 - (s.y() + s.eyeHeight());
                final double dz = shown.z() - s.z();
                this.c.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                this.c.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                this.tests.getLogger().info("REVIVAL_CHEAT_NOTE " + id + ": mirror exact " + shown.exact() + ", present " + shown.present()
                    + ", target speed " + f(this.v.auth.simulation().vx()));
            }
            this.attack(this.c, 1);
        });
        this.judge(4, id, () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            this.honestExpect(landed == 1 && d.attacksAccepted() == 1, "an honest hit lands: landed " + landed + " (judged valid " + d.attacksAccepted()
                + ", invalid " + d.attacksRejected() + "; rewind " + this.c.auth.lastHitRewindMillis() + " ms, cap " + this.c.auth.lastRewindCap()
                + " ms, display " + this.c.auth.lastDisplayDelay() + " ms, rtt base " + this.c.auth.transactions().rttBaseNanos() / 1_000_000L
                + " ms, verdict " + this.c.auth.lastHitVerdict() + ")");
            final long forcedSince = this.c.auth.stats().forcedEntries() - forcedAtSettle[0];
            this.honestExpect(forcedSince == 0, "nothing forced once the round trip settled (" + forcedSince + " entries)");
        });
        this.step(1, id + ": verdict", () -> {
            this.v.keys = NONE;
            this.record();
            this.leaveAll();
        });
    }

    // Classic audit #7: a VALID hit on a target running straight away. Paper's range check after the judge (handleAttack)
    // measured the target's newest server box from the attacker's eye now; at 300 ms with Speed IV the target is past its
    // 3 + 3 blocks by then, and the hit vanished after its VALID
    private void combatChaseAway(final int ticks) {
        final String id = "combat: an honest hit at " + ticks * 50 + " ms of latency on a target running straight away with Speed IV";
        this.combatJoin(id);
        this.step(1, id + ": latency", () -> this.c.latencyTicks = ticks);
        this.step(50, id + ": the round trip settles", () -> {
        });
        this.step(1, id + ": the target takes its place", () -> {
            this.open(id);
            this.label = "CONTROL";
            this.place(0.0, 2.9); // its near face 2.6 from the attacker's eye
            this.v.player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SPEED, 400, 3));
        });
        this.step(30, id + ": the target runs straight away", () -> {
            this.v.yaw = 0.0F; // facing +z, away from the attacker
            this.v.keys = FORWARD_SPRINT;
        });
        final double[] gap = new double[1];
        this.step(8, id + ": aim at what the attacker's client shows, attack", () -> {
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            final gg.hcfrevival.revival.auth.combat.RemoteMirror shown = CombatWiring.book(this.c.auth).mirror(this.v.player.getId());
            final Simulation s = this.c.auth.simulation();
            if (shown != null) {
                final double dx = shown.x() - s.x();
                final double dy = shown.y() + shown.height() * 0.5 - (s.y() + s.eyeHeight());
                final double dz = shown.z() - s.z();
                this.c.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                this.c.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            }
            this.attack(this.c, 1);
        });
        this.judge(4, id, () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            gap[0] = this.v.player.getZ() - 0.3 - this.c.player.getZ();
            this.honestExpect(landed == 1 && d.attacksAccepted() == 1, "an honest hit lands: landed " + landed + " (judged valid " + d.attacksAccepted()
                + ", invalid " + d.attacksRejected() + "; rewind " + this.c.auth.lastHitRewindMillis() + " ms, cap " + this.c.auth.lastRewindCap()
                + " ms, verdict " + this.c.auth.lastHitVerdict() + "); the target's server box was " + f(gap[0]) + " away by the judge");
        });
        this.step(1, id + ": verdict", () -> {
            this.v.keys = NONE;
            this.v.player.removeEffect(net.minecraft.world.effect.MobEffects.SPEED);
            this.record();
            this.leaveAll();
        });
    }

    private void combatFakeLag() {
        final String id = "combat: fake lag (pongs on time, the victim's movement shown 10 ticks late)";
        this.combatJoin(id);
        this.step(1, id + ": strafe", () -> {
            this.open(id);
            this.c.staleEntityTicks = 10;
            this.v.keys = new Input(false, false, true, false, false, false, false); // strafing out of the attacker's line
        });
        this.step(10, id + ": attack where the stale view still shows it", () -> {
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            this.c.yaw = 0.0F;
            this.c.pitch = 0.0F;
            this.attack(this.c, 1);
        });
        this.judge(3, id, () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            this.expect(landed == 0, "refused: landed " + landed + " (judged valid " + d.attacksAccepted() + ", invalid " + d.attacksRejected() + ", verdict "
                + this.c.auth.lastHitVerdict() + ")");
        });
        this.finish(id);
    }

    private void combatTeleportPending() {
        final String id = "combat: while a server teleport is pending";
        this.combatJoin(id);
        this.pendingCase("combat: reach 4.5 while a server teleport is pending", 4.8, false);
        this.pendingCase("combat: honest hit at 2.5 while a server teleport is pending (control)", 2.8, true);
        this.step(1, id + ": done", this::leaveAll);
    }

    // The attacker's own server teleport (a pearl landing) is in flight: its pong is held over the attack's window
    private void pendingCase(final String name, final double dz, final boolean honest) {
        final boolean[] pending = {false};
        this.step(1, name + ": place", () -> {
            this.open(name);
            this.place(0.0, dz);
        });
        this.step(12, name + ": teleport in place, hold its pong, attack", () -> {
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            this.c.holdPongs = true;
            this.c.player.connection.teleport(this.c.player.getX(), this.c.player.getY(), this.c.player.getZ(), 0.0F, 0.0F);
            this.c.before = x -> {
                x.before = null;
                pending[0] = x.auth.teleportPending();
                this.attack(x, 1);
            };
        });
        this.step(3, name + ": release", () -> this.c.releaseHeld());
        this.step(3, name + ": judge", () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            final String detail = "landed " + landed + " (teleport pending " + pending[0] + ", judged valid " + d.attacksAccepted() + ", invalid "
                + d.attacksRejected() + ")";
            if (honest) {
                this.label = "CONTROL";
                this.honestExpect(landed == 1, "an honest hit lands: " + detail);
            } else {
                this.expect(landed == 0, "refused: " + detail);
            }
            this.record();
        });
    }

    private void combatBacktrack() {
        final String id = "combat: backtrack past the rewind cap (pongs held 500 ms)";
        this.combatJoin(id);
        this.step(1, id + ": hold pongs, victim steps 3 aside", () -> {
            this.open(id);
            this.base = this.snap(this.c);
            this.c.holdPongs = true;
            this.place(3.0, 2.8);
        });
        this.step(10, id + ": attack where the client still shows it", () -> {
            this.attacksBase = this.attacksOf(this.c);
            this.attack(this.c, 1);
        });
        this.step(2, id + ": release", () -> this.c.releaseHeld());
        this.judge(3, id, () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            this.expect(landed == 0, "refused: landed " + landed + " (forced " + d.forced() + ", judged valid " + d.attacksAccepted() + ", invalid " + d.attacksRejected() + ")");
        });
        this.finish(id);
    }

    private void poisoned(final String id) {
        this.attackCase(id + " -> then killaura without aim", 2.0, 0.0, null, 1, 0, null);
        this.attackCase(id + " -> then reach 4.5", 0.0, 4.8, null, 1, 0, null);
        this.attackCase(id + " -> then an honest hit at 2.5 (control)", 0.0, 2.8, null, 1, 1, null);
        this.step(1, id + ": done", this::leaveAll);
    }

    private void combatForcedPoison() {
        final String id = "combat: one lag spike past the ack deadline";
        this.combatJoin(id);
        this.step(1, id + ": hold pongs", () -> this.c.holdPongs = true);
        this.step(9, id + ": release", () -> this.c.releaseHeld());
        this.step(10, id + ": mirror", () -> {
            final RemoteMirror mirror = CombatWiring.book(this.c.auth).mirror(this.v.player.getId());
            this.tests.getLogger().info("REVIVAL_CHEAT_NOTE " + id + ": forced " + this.c.auth.stats().forcedEntries() + ", victim mirror exact "
                + (mirror != null && mirror.exact()));
        });
        this.poisoned(id);
    }

    private void combatLatePongPoison() {
        final String id = "combat: a pong placed after an attack in its window";
        this.combatJoin(id);
        this.step(1, id + ": attack, then the pong", () -> {
            this.open(id);
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            this.c.before = x -> {
                x.before = null;
                x.drainAfterActions = true;
                this.attack(x, 1);
            };
        });
        // §15.8: a pong after the tick's keybinds is counted, not kicked; it keeps its arrival place, so the attack before it
        // is judged on the mirror before the pong's entries (no backtrack), and the cheats after it still get nothing
        this.judge(4, id, () -> {
            final String reason = this.kicks.getOrDefault(this.c.player.getUUID(), "");
            this.expect(!this.c.kicked, "counted, not kicked " + reason);
            this.expect(this.c.auth.violationReasons().getInt("a pong after the tick's keybinds") >= 1, "counted: " + this.c.auth.violationReasons());
            this.note("the attack before the pong landed " + (this.attacksOf(this.c) - this.attacksBase) + " (judged at its place)");
        });
        this.step(1, id + ": verdict", this::record);
        this.poisoned(id);
    }

    private void combatAutoblock() {
        final String id = "combat: attack while blocking (autoblock)";
        this.combatJoin(id);
        this.step(1, id + ": sword", () -> this.give(this.c, new ItemStack(Items.DIAMOND_SWORD)));
        this.step(4, id + ": block", () -> this.c.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, 0.0F, 0.0F)));
        final boolean[] using = {false};
        this.step(3, id + ": attack while blocking", () -> {
            this.open(id);
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            using[0] = this.c.player.isUsingItem();
            this.attack(this.c, 1);
        });
        this.judge(3, id, () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            this.expect(landed == 0, "refused: landed " + landed + " while blocking " + using[0] + " (judged valid " + d.attacksAccepted() + ")");
        });
        this.finish(id);
    }

    private void combatContainerClose() {
        final String id = "combat: attack in the window that closes a screen";
        this.combatJoin(id);
        this.step(1, id + ": close and attack", () -> {
            this.open(id);
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            this.c.actions.add(new ServerboundContainerClosePacket(0));
            this.attack(this.c, 1);
        });
        this.judge(3, id, () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            this.expect(landed == 0, "refused: landed " + landed + " (judged valid " + d.attacksAccepted() + ", grammar violations " + d.streamViolations() + ")");
        });
        this.finish(id);
    }

    private void inventoryMove() {
        final String id = "inventory-move: container click while walking";
        this.begin(id, () -> {
        });
        this.step(1, id + ": stick", () -> this.give(this.c, new ItemStack(Items.STICK)));
        this.step(4, id + ": walk", () -> {
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
        });
        this.step(5, id + ": click", () -> {
            this.base = this.snap(this.c);
            this.clicksBase = this.clicks.getOrDefault(this.c.player.getUUID(), 0);
            this.c.actions.add(new ServerboundContainerClickPacket(0, this.c.player.inventoryMenu.getStateId(), (short) 36, (byte) 0, ContainerInput.PICKUP,
                new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
        });
        this.judge(3, id, () -> {
            final Snap d = this.delta(this.c);
            final int handled = this.clicks.getOrDefault(this.c.player.getUUID(), 0) - this.clicksBase;
            this.expect(handled == 0, "refused: clicks handled " + handled + " with the tick's keys holding forward (grammar violations " + d.streamViolations() + ")");
        });
        this.finish(id);
    }

    private void mountDenied() {
        final String id = "vehicles: mount under enforce (authoritative.vehicles: deny)";
        this.begin(id, () -> {
        });
        final Entity[] cart = {null};
        this.arm(id, () -> {
            final Entity minecart = EntityTypes.MINECART.create(this.level, EntitySpawnReason.COMMAND);
            if (minecart != null) {
                minecart.snapTo(this.c.player.getX(), this.c.player.getY(), this.c.player.getZ(), 0.0F, 0.0F);
                this.level.addFreshEntity(minecart);
                this.c.player.startRiding(minecart, true, true);
                cart[0] = minecart;
            }
        });
        this.judge(5, id, () -> {
            this.expect(cart[0] != null && !this.c.player.isPassenger() && !this.c.auth.view().passenger(), "a forced mount is refused (passenger "
                + this.c.player.isPassenger() + ")");
            if (cart[0] != null) {
                cart[0].discard();
            }
        });
        this.finish(id);
    }

    private void combatRiding() {
        final String id = "combat: attack from an exempt state (riding)";
        this.combatJoin(id);
        final Entity[] cart = {null};
        final boolean[] riding = {false};
        this.step(1, id + ": mount", () -> {
            final Entity minecart = EntityTypes.MINECART.create(this.level, EntitySpawnReason.COMMAND);
            if (minecart != null) {
                minecart.snapTo(this.c.player.getX(), this.c.player.getY(), this.c.player.getZ(), 0.0F, 0.0F);
                this.level.addFreshEntity(minecart);
                this.c.player.startRiding(minecart, true, true);
                cart[0] = minecart;
            }
        });
        this.step(8, id + ": attack", () -> {
            this.open(id);
            riding[0] = this.c.player.isPassenger();
            this.base = this.snap(this.c);
            this.attacksBase = this.attacksOf(this.c);
            this.c.pitch = 25.0F;
            this.attack(this.c, 1);
        });
        this.judge(3, id, () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksOf(this.c) - this.attacksBase;
            this.expect(riding[0] && this.c.auth.stats().exempt(AuthStats.EXEMPT_RIDING) > 0, "riding (authoritative.vehicles: vanilla) is exempt ("
                + this.c.auth.stats().exempt(AuthStats.EXEMPT_RIDING) + " ticks)");
            this.expect(landed == 0, "refused: landed " + landed + " (judged valid " + d.attacksAccepted() + ")");
            this.c.player.stopRiding();
            if (cart[0] != null) {
                cart[0].discard();
            }
        });
        this.finish(id);
    }

    // =================================================================================================================
    // World
    // =================================================================================================================

    private void phantomPlace() {
        final String id = "world: place a block without holding one, stand on it";
        this.begin(id, () -> {
        });
        this.arm(id, () -> {
            final BlockPos at = new BlockPos(this.ox, this.gy, this.oz + 2);
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
            this.c.actions.add(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(new Vec3(this.ox + 0.5, this.gy, this.oz + 2.5), Direction.UP, at.below(), false), 1));
            this.c.world.onBlockUpdate(at, Blocks.STONE.defaultBlockState()); // the cheater's client thinks it placed one
            this.c.ownBlocks = true;
            final boolean[] on = {false};
            this.c.cheat = x -> {
                if (!on[0] && x.model.z() >= this.oz + 1.7 - 1.0E-6) {
                    on[0] = true;
                }
                if (on[0]) {
                    x.model.setVelocity(x.model.vx(), 0.0, x.model.vz());
                    x.model.rebase(x.model.x(), this.gy + 1.0, Math.min(x.claimZ + 0.2, this.oz + 2.5));
                    x.claimOnGround = true;
                }
            };
        });
        final int[] predictions = {-1};
        this.step(2, id + ": prediction", () -> predictions[0] = this.c.auth.world().predictionCount());
        this.judge(20, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(predictions[0] == 0 && this.level.getBlockState(new BlockPos(this.ox, this.gy, this.oz + 2)).isAir(),
                "no prediction admitted (" + predictions[0] + ") and no block placed");
            this.expect(this.track.truthMaxY <= this.gy + EPS, "the truth never stood on it (y max +" + f(this.track.truthMaxY - this.gy) + ")");
            this.expect(this.track.truthMaxZ >= this.oz + 2.0, "the truth walked through the spot (z +" + f(this.track.truthMaxZ - this.oz) + ")");
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void predictionSpam() {
        final String id = "world: spam placements with block acks held";
        this.begin(id, () -> {
        });
        this.step(1, id + ": stone", () -> {
            this.give(this.c, new ItemStack(Items.STONE, 64));
            // §15.2: a vanilla client places on the block on its crosshair: look down at the floor block two ahead
            // (eye 1.62 above it, 2.0 away)
            this.c.pitch = (float) Math.toDegrees(Math.atan2(1.62, 2.0));
        });
        final int[] seq = {1};
        final int[] admitted = {0};
        this.step(4, id + ": place", () -> {
            this.base = this.snap(this.c);
            this.c.forcePosition = true;
            this.c.actions.add(this.place(0, seq[0]++));
            this.probe = () -> admitted[0] = Math.max(admitted[0], this.c.auth.world().predictionCount());
        });
        this.step(2, id + ": spam with acks held", () -> {
            this.note("first placement " + (this.level.getBlockState(new BlockPos(this.ox, this.gy, this.oz + 2)).isAir() ? "refused by the server" : "made"));
            this.note("diagnostics: hand " + this.c.player.getMainHandItem() + ", windows " + this.c.auth.stats().windows() + ", refused " + this.c.auth.clock().refused()
                + ", queued " + this.c.auth.stream().queued() + ", grammar " + this.c.auth.inputRules().total() + ", predictions refused "
                + this.c.auth.stats().predictionsRefused() + ", loaded " + this.c.player.connection.hasClientLoaded() + ", in range "
                + this.c.player.isWithinBlockInteractionRange(new BlockPos(this.ox, this.gy - 1, this.oz + 2), 1.0) + ", pos " + this.c.player.position()
                + ", unobstructed " + this.level.isUnobstructed(Blocks.STONE.defaultBlockState(), new BlockPos(this.ox, this.gy, this.oz + 2),
                net.minecraft.world.phys.shapes.CollisionContext.of(this.c.player)) + ", entities there "
                + this.level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(new BlockPos(this.ox, this.gy, this.oz + 2)))
                + ", awaiting teleport " + awaiting(this.c.player));
            this.c.holdPongs = true;
            final int[] left = {3};
            this.c.before = x -> {
                if (left[0]-- <= 0) {
                    x.before = null;
                    return;
                }
                for (int i = 0; i < 5; i++) {
                    x.actions.add(this.place(i % 2 == 0 ? -(1 + i / 2) : 1 + i / 2, seq[0]++));
                }
            };
        });
        final int[] left = {0};
        final long[] expired = {0};
        this.step(12, id + ": deadline", () -> {
            left[0] = this.c.auth.world().predictionCount();
            expired[0] = this.c.auth.world().expired();
            this.c.releaseHeld();
        });
        this.judge(4, id, () -> {
            this.note("the first block is " + (this.level.getBlockState(new BlockPos(this.ox, this.gy, this.oz + 2)).isAir() ? "still not placed" : "placed")
                + "; predictions refused as not a vanilla client's " + this.c.auth.stats().predictionsRefused());
            this.expect(admitted[0] >= 1, "a placement the server made is a prediction (" + admitted[0] + ")");
            this.expect(left[0] == 0, "with every ack held, no prediction outlived its deadline (" + left[0] + " left, " + expired[0] + " expired)");
            this.expect(this.c.ackedSequence >= seq[0] - 1, "every sequence was acked (" + this.c.ackedSequence + " of " + (seq[0] - 1) + ")");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private static Object awaiting(final net.minecraft.server.level.ServerPlayer player) {
        try {
            final java.lang.reflect.Field field = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
            field.setAccessible(true);
            return String.valueOf(field.get(player.connection));
        } catch (final ReflectiveOperationException e) {
            return e.toString();
        }
    }

    private ServerboundUseItemOnPacket place(final int dx, final int sequence) {
        final BlockPos floor = new BlockPos(this.ox + dx, this.gy - 1, this.oz + 2);
        return new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(new Vec3(floor.getX() + 0.5, this.gy, floor.getZ() + 0.5), Direction.UP, floor, false), sequence);
    }

    private void protectedBreak() {
        final String id = "world: break a protected block, walk through before the revert";
        this.begin(id, () -> this.wall(1, 2, Blocks.TNT.defaultBlockState()));
        final int[] predictions = {-1};
        this.arm(id, () -> {
            final BlockPos low = new BlockPos(this.ox, this.gy, this.oz + 1);
            this.protectedBlocks.add(low.asLong());
            this.protectedBlocks.add(low.above().asLong());
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
            this.c.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, low, Direction.NORTH, 1));
            this.c.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, low.above(), Direction.NORTH, 2));
            this.c.world.onBlockUpdate(low, Blocks.AIR.defaultBlockState()); // instant break: the client predicted air
            this.c.world.onBlockUpdate(low.above(), Blocks.AIR.defaultBlockState());
            this.c.ignoreBlockUpdates = true; // and ignores the server putting them back
            this.c.ownBlocks = true;
        });
        this.step(2, id + ": prediction", () -> predictions[0] = this.c.auth.world().predictionCount());
        this.judge(23, id, () -> {
            final Snap d = this.delta(this.c);
            final double face = this.oz + 1;
            this.expect(predictions[0] == 0 && this.level.getBlockState(new BlockPos(this.ox, this.gy, this.oz + 1)).is(Blocks.TNT),
                "the refused break is no prediction (" + predictions[0] + "), the block stands");
            this.expect(this.track.truthMaxZ <= face - 0.3 + EPS, "the truth stops at it (z " + f(this.track.truthMaxZ - (face - 0.3)) + ")");
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
            this.c.ignoreBlockUpdates = false;
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // NAS glitch 2026-10-22 (Lunar, RTT 31 ms): holding attack on a protected honey block underfoot, the client predicts it
    // gone every tick it is back, sinks into it, and vanilla's restore puts it back up. Corrected instead, it lost that
    // snap (onTeleport), stayed inside the restored block and fell through; the corrections never converged
    private void honeyUnderfoot() {
        final String id = "world: hold attack on a protected honey block underfoot, then walk off (honest)";
        final BlockPos[] under = {null};
        final Snap[] held = {null};
        final int[] counters = new int[4];
        this.begin(id, () -> {
            under[0] = new BlockPos(this.ox, this.gy - 1, this.oz);
            this.set(under[0].getX(), under[0].getY(), under[0].getZ(), Blocks.HONEY_BLOCK.defaultBlockState());
            this.set(under[0].getX(), under[0].getY() - 1, under[0].getZ(), Blocks.AIR.defaultBlockState()); // a pit to fall through
        });
        this.arm(id, () -> {
            this.protectedBlocks.add(under[0].asLong());
            counters[0] = (int) this.c.auth.stats().holeClaims();
            counters[1] = (int) this.c.auth.stats().holeSnaps();
            this.c.predictBreaks = true;
            this.c.pitch = 90.0F; // looking straight down at it
            this.c.keys = NONE;
            this.c.forcePosition = true;
            this.c.before = x -> {
                // Minecraft.continueAttack -> startDestroyBlock: an instant break every tick the block is there again
                if (!x.world.blockState(under[0]).isAir()) {
                    x.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, under[0], Direction.UP, ++x.sequence));
                }
            };
        });
        this.step(40, id + ": attack held 2 s", () -> {
            held[0] = this.delta(this.c);
            counters[2] = this.c.predictedBreaks;
            counters[3] = this.c.restoreSnaps;
            this.c.before = null; // attack released
            this.c.pitch = 0.0F;
            this.c.keys = FORWARD; // and off the block
        });
        this.judge(30, id, () -> {
            final Snap d = this.delta(this.c);
            final AuthStats stats = this.c.auth.stats();
            final double top = this.gy - 1 + 15.0 / 16.0;
            this.expect(this.level.getBlockState(under[0]).is(Blocks.HONEY_BLOCK), "the block stands");
            this.expect(this.track.truthMinY >= top - EPS, "the truth never left its top (y min " + f(this.track.truthMinY - top) + ")");
            this.honestExpect(counters[2] >= 10 && counters[3] >= 5, "the client broke it " + counters[2] + " times and was put back on it "
                + counters[3] + " times by its own restore");
            this.honestExpect(held[0].corrections() == 0, "no correction while it bobbed in its own hole: " + held[0].corrections()
                + " (claims in the hole " + (stats.holeClaims() - counters[0]) + ", restores modelled " + (stats.holeSnaps() - counters[1]) + ")");
            this.honestExpect(d.corrections() <= 1, "walking off after it: " + (d.corrections() - held[0].corrections()) + " corrections more");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // The same for a wall (the NAS glass panes): broken on the client, walked into, restored around the player. dig: a
    // glass pane dug for its STOP_DESTROY_BLOCK; else TNT, broken by START_DESTROY_BLOCK at once
    private void protectedWallHonest(final boolean dig) {
        final String id = dig ? "world: dig a protected glass pane and walk into the gap (honest)"
            : "world: break a protected wall block and walk into the gap (honest)";
        final BlockState block = dig ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.TNT.defaultBlockState();
        final int[] digTicks = {0};
        this.begin(id, () -> this.wall(1, 1, block)); // one high: the gap is a way through
        this.arm(id, () -> {
            final BlockPos low = new BlockPos(this.ox, this.gy, this.oz + 1);
            this.protectedBlocks.add(low.asLong());
            this.protectedBlocks.add(low.above().asLong());
            this.c.predictBreaks = true;
            this.c.pitch = dig ? 45.0F : 60.0F; // the crosshair on it (a pane's face is 7/16 into its block)
            this.c.keys = NONE;
            this.c.forcePosition = true;
            this.c.before = x -> {
                final int t = digTicks[0]++;
                if (!dig && t == 0) {
                    x.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, low, Direction.NORTH, ++x.sequence));
                }
                if (dig && t == 0) {
                    x.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, low, Direction.NORTH, ++x.sequence));
                }
                if (dig && t == 10) {
                    x.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, low, Direction.NORTH, ++x.sequence));
                }
                if (t == (dig ? 10 : 0)) {
                    x.keys = FORWARD; // into the gap it sees
                }
            };
        });
        this.judge(40, id, () -> {
            final Snap d = this.delta(this.c);
            final double face = this.oz + 1 + (dig ? 7.0 / 16.0 : 0.0); // a pane's post
            this.expect(this.level.getBlockState(new BlockPos(this.ox, this.gy, this.oz + 1)).is(block.getBlock()), "the block stands");
            this.expect(this.track.truthMaxZ <= face - 0.3 + EPS, "the truth stops at it (z " + f(this.track.truthMaxZ - (face - 0.3)) + ")");
            this.honestExpect(this.c.predictedBreaks >= 1, "the client broke it on its side (" + this.c.predictedBreaks + "), restores that put it back "
                + this.c.restoreSnaps);
            this.honestExpect(d.corrections() <= 1, "corrections " + d.corrections() + " (at most one for the gap it walked into)");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // =================================================================================================================
    // Self echo
    // =================================================================================================================

    // kind 0: a piston behind the player (facing +z) extends, and the client moves with it as vanilla does (0.51 a tick,
    // two ticks: the band adopts it, no correction); 1: a piston 10 blocks off extends while the client claims 0.55 up a
    // tick; 2: the piston behind it extends while it claims 0.55 up instead of along the push
    private void pistonCase(final String id, final int kind) {
        final Direction facing = kind == 1 ? Direction.EAST : Direction.SOUTH;
        // Placed when the arena is (the suite's origin is only known then), the power beside it off the lane: a server
        // piston refuses an extend event it has no signal for (triggerEvent)
        final BlockPos[] at = new BlockPos[2];
        this.begin(id, () -> {
            at[0] = kind == 1 ? new BlockPos(this.ox + 3, this.gy, this.oz + 10) : new BlockPos(this.ox, this.gy, this.oz - 1);
            at[1] = at[0].relative(kind == 1 ? Direction.NORTH : Direction.WEST);
            this.set(at[0].getX(), at[0].getY(), at[0].getZ(),
                Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, facing));
            this.set(at[1].getX(), at[1].getY(), at[1].getZ(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        });
        final double[] start = new double[3];
        this.arm(id, () -> {
            this.c.forcePosition = true;
            start[0] = this.c.auth.simulation().x();
            start[1] = this.c.auth.simulation().y();
            start[2] = this.c.auth.simulation().z();
            final int seen = this.c.blockEvents;
            final int[] k = {-1};
            this.c.cheat = x -> {
                if (k[0] < 0 && x.blockEvents > seen) {
                    k[0] = 0; // the event reached the client this tick: it moves from the next
                    return;
                }
                if (k[0] < 0 || k[0] >= 3) {
                    return;
                }
                final int t = k[0]++;
                x.model.setVelocity(0.0, 0.0, 0.0);
                if (kind == 0) {
                    if (t < 2) {
                        x.model.rebase(x.model.x(), x.model.y(), x.model.z() + 0.51);
                    }
                } else {
                    x.model.rebase(x.model.x(), x.model.y() + 0.55, x.model.z());
                }
            };
            this.level.blockEvent(at[0], Blocks.PISTON, 0, facing.get3DDataValue());
        });
        this.judge(20, id, () -> {
            final Snap d = this.delta(this.c);
            final Simulation s = this.c.auth.simulation();
            this.note("the client handled " + this.c.blockEvents + " block events");
            if (kind == 0) {
                this.label = "CONTROL";
                this.honestExpect(d.corrections() == 0 && Math.abs(s.z() - start[2] - 1.02) <= 1.0E-6, "pushed 1.02 with the piston, adopted: z +"
                    + f(s.z() - start[2]) + ", corrections " + d.corrections() + ", pushes adopted " + this.c.auth.stats().pushesAdopted());
            } else {
                this.expect(this.track.truthMaxY <= start[1] + 1.0E-9, "the truth never rose (max y +" + f(this.track.truthMaxY - start[1]) + ")");
                this.expect(d.corrections() > 0, "corrections " + d.corrections());
            }
            this.c.cheat = null;
        });
        this.honestAfter(id, NONE, 0);
        this.step(1, id + ": no piston", () -> {
            this.set(at[1].getX(), at[1].getY(), at[1].getZ(), Blocks.AIR.defaultBlockState());
            this.set(at[0].getX(), at[0].getY(), at[0].getZ(), Blocks.AIR.defaultBlockState());
            final BlockPos head = at[0].relative(facing);
            this.set(head.getX(), head.getY(), head.getZ(), Blocks.AIR.defaultBlockState());
        });
        this.finish(id);
    }

    // Knocked every 20 ticks (alternating +x/-x), 16 times; the macro presses jump in the very tick it takes each knock,
    // the honest player presses it on 5% of its ground ticks at random (seeded), so most of its knocks land on the ground
    // and the alert judges it (10 grounded knocks at least). The alert (min knocks lowered to 10 for the row) flags the macro and not the
    // jumpy player, whose share of jumps on knocks is its own share anyway
    private void jumpReset(final String id, final boolean macro) {
        this.begin(id, () -> {
        });
        this.step(1, id + ": min knocks 10", () -> this.configure("authoritative.stats.jump-reset-min-knocks", 10));
        this.arm(id, () -> {
            this.c.forcePosition = true;
            final int[] seen = {this.c.knocksSeen};
            final boolean[] wasJump = {false};
            final java.util.Random dice = new java.util.Random(4242L);
            this.c.before = x -> {
                final boolean knocked = x.knocksSeen > seen[0];
                seen[0] = x.knocksSeen;
                final boolean jump = macro ? knocked : x.model.onGround() && !wasJump[0] && dice.nextDouble() < 0.03;
                wasJump[0] = jump;
                x.keys = jump ? JUMP : NONE;
            };
        });
        // 24 knocks: a 3% hopper is in the air for about 3 in 10 of them, and the control only means something with at least
        // min-knocks (10) grounded ones (a 5% hopper over 16 knocks had 8)
        for (int i = 0; i < 24; i++) {
            final double vx = i % 2 == 0 ? 0.3 : -0.3;
            this.step(20, id + ": knock " + (i + 1), () -> this.knock(this.c, vx, 0.3, 0.0));
        }
        this.judge(20, id, () -> {
            final AuthStats s = this.c.auth.stats();
            final boolean flagged = this.anomalies.getOrDefault(this.c.player.getUUID(), java.util.Set.of()).contains("jump-reset");
            final String detail = "grounded knocks " + s.groundedKnocks() + ", jumped in their window " + s.knockJumps() + ", grounded ticks "
                + s.groundedTicks() + ", jumps on them " + s.groundJumps();
            if (macro) {
                this.expect(flagged, "the jump-reset alert fired: " + flagged + " (" + detail + ")");
            } else {
                this.label = "CONTROL";
                this.honestExpect(!flagged && s.groundedKnocks() >= 10, "no jump-reset alert over " + s.groundedKnocks() + " grounded knocks: " + flagged
                    + " (" + detail + ")");
            }
            this.c.before = null;
            this.configure("authoritative.stats.jump-reset-min-knocks", 40);
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private static final int TAP_W = 1;
    private static final int TAP_BLOCK = 2;

    // pvp-issues SP-4: 30 hits 10 ticks apart, each followed by its taps. The macro's tap is the same to the tick every time
    // (1 tick after the hit, 2 long); the control's lead and length are drawn around the same with a 20 ms spread (0.4
    // ticks, seeded), tighter than the tap study's humans. The row lowers tap-lock-samples to 20. Forward walks into a wall
    // (the attacks behind it are counted, valid or not), so the lane stays put
    private void tapLock(final String id, final int taps, final boolean macro) {
        this.combatJoin(id);
        final int[] wall = new int[3];
        this.step(1, id + ": a sword, a wall ahead, the victim behind it, 20 taps lock", () -> {
            this.open(id);
            this.configure("authoritative.stats.tap-lock-samples", 20);
            this.give(this.c, new ItemStack(Items.DIAMOND_SWORD));
            this.place(0.0, 2.8);
            wall[0] = net.minecraft.util.Mth.floor(this.c.player.getX());
            wall[1] = net.minecraft.util.Mth.floor(this.c.player.getY());
            wall[2] = net.minecraft.util.Mth.floor(this.c.player.getZ()) + 1;
            this.set(wall[0], wall[1], wall[2], Blocks.STONE.defaultBlockState());
            this.set(wall[0], wall[1] + 1, wall[2], Blocks.STONE.defaultBlockState());
            this.c.forcePosition = true;
            this.c.keys = FORWARD;
        });
        final long[] before = new long[2];
        this.arm(id, () -> {
            before[0] = this.c.auth.wTaps().samples();
            before[1] = this.c.auth.blockHits().samples();
            final java.util.Random dice = new java.util.Random(5150L);
            final int[] k = {-1};
            final int[] gaps = {1, 2, 1, 2};
            this.c.before = x -> {
                final int t = ++k[0] % 10;
                if (t == 0) {
                    for (int g = 0; g < 4; g++) {
                        gaps[g] = macro ? (g % 2 == 0 ? 1 : 2) : Math.max(g % 2, (int) Math.round((g % 2 == 0 ? 1.0 : 2.0) + 0.4 * dice.nextGaussian()));
                    }
                    this.attack(x, 1);
                }
                x.keys = (taps & TAP_W) != 0 && t >= gaps[0] && t < gaps[0] + gaps[1] ? NONE : FORWARD;
                if ((taps & TAP_BLOCK) != 0 && t == gaps[2]) {
                    x.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, x.yaw, x.pitch));
                }
                if ((taps & TAP_BLOCK) != 0 && t == gaps[2] + gaps[3]) {
                    x.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
                }
            };
        });
        this.step(300, id + ": 30 hits", () -> {
        });
        this.judge(10, id, () -> {
            final java.util.Set<String> raised = this.anomalies.getOrDefault(this.c.player.getUUID(), java.util.Set.of());
            final gg.hcfrevival.revival.auth.input.TapWatch w = this.c.auth.wTaps();
            final gg.hcfrevival.revival.auth.input.TapWatch b = this.c.auth.blockHits();
            final long wSamples = w.samples() - before[0];
            final long bSamples = b.samples() - before[1];
            this.note("w-taps " + wSamples + " (longest run " + w.longest() + "), block-hits " + bSamples + " (longest run " + b.longest() + "), alerts "
                + raised);
            if (macro) {
                final boolean block = (taps & TAP_BLOCK) != 0;
                this.expect((block ? bSamples : wSamples) >= 25, "the taps were measured (" + (block ? bSamples : wSamples) + ")");
                this.expect(raised.contains(block ? "block-hit-lock" : "w-tap-lock"), "the " + (block ? "block-hit-lock" : "w-tap-lock") + " alert fired");
            } else {
                this.label = "CONTROL";
                this.honestExpect(wSamples >= 25 && bSamples >= 25, "both taps were measured (" + wSamples + ", " + bSamples + ")");
                this.honestExpect(!raised.contains("w-tap-lock") && !raised.contains("block-hit-lock"), "no tap alert: " + raised);
            }
            this.c.before = null;
            this.c.keys = NONE;
            this.configure("authoritative.stats.tap-lock-samples", gg.hcfrevival.revival.auth.input.TapWatch.LOCK_SAMPLES);
            this.set(wall[0], wall[1], wall[2], Blocks.AIR.defaultBlockState());
            this.set(wall[0], wall[1] + 1, wall[2], Blocks.AIR.defaultBlockState());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // pvp-issues HR-3: 16 hits 20 ticks apart on a victim re-placed 2.8 ahead (and healed) 14 ticks before each. The
    // cheat holds everything from its pings on for the 3 ticks before each swing and lets it go the tick after, so its
    // acked view is 150 ms old when it hits while its base round trip stays the link's: fake lag timed to its swings. The
    // row widens rewind-slack-ms to 300 so every held hit stays inside the cap and is judged valid (the held pongs widen
    // the cap's jitter term too, but only once a tenth of the 256-ack window holds them), and lowers rewind-stretch-hits
    // to 10
    private void rewindStretch(final String id, final boolean cheat) {
        this.combatJoin(id);
        this.step(1, id + ": a sword, the victim in reach, 10 hits judged, slack 300", () -> {
            this.open(id);
            this.configure("authoritative.stats.rewind-stretch-hits", 10);
            this.configure("authoritative.rewind-slack-ms", 300);
            this.give(this.c, new ItemStack(Items.DIAMOND_SWORD));
            this.place(0.0, 2.8);
            this.c.forcePosition = true;
            this.c.keys = NONE;
        });
        final long[] before = new long[1];
        this.arm(id, () -> {
            before[0] = this.c.auth.rewinds().hits();
            final int[] k = {-1};
            this.c.before = x -> {
                final int t = ++k[0] % 20;
                if (t == 5) {
                    this.place(0.0, 2.8);
                    this.v.player.setHealth(20.0F); // sixteen sword hits would kill it
                }
                if (cheat && t == 16) {
                    x.holdPongs = true;
                }
                if (t == 19) {
                    this.attack(x, 1);
                }
                if (cheat && t == 0 && k[0] > 0) {
                    x.releaseHeld();
                }
            };
        });
        this.step(320, id + ": 16 hits", () -> {
        });
        this.judge(10, id, () -> {
            final boolean raised = this.anomalies.getOrDefault(this.c.player.getUUID(), java.util.Set.of()).contains("rewind-stretch");
            final gg.hcfrevival.revival.auth.input.RewindWatch r = this.c.auth.rewinds();
            final long valid = r.hits() - before[0];
            this.note("valid hits " + valid + ", median excess " + r.median() + " ms (highest " + r.highest() + "), alert " + raised);
            if (cheat) {
                this.expect(valid >= 10, "its hits were judged valid (" + valid + ")");
                this.expect(raised, "the rewind-stretch alert fired (median " + r.median() + " ms)");
            } else {
                this.label = "CONTROL";
                this.honestExpect(valid >= 10, "its hits were judged valid (" + valid + ")");
                this.honestExpect(!raised, "no rewind-stretch alert (median " + r.median() + " ms)");
            }
            this.c.before = null;
            this.c.releaseHeld();
            this.configure("authoritative.stats.rewind-stretch-hits", gg.hcfrevival.revival.auth.input.RewindWatch.HITS);
            this.configure("authoritative.rewind-slack-ms", 150);
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // pvp-issues SP-3: KeepSprint skips the client's own x0.6 and sprint stop after its sprint hit (Player.attack). The truth
    // makes them anyway (Simulation.clientAttack): the claims run ahead of it and are corrected, and the knock is the one an
    // honest hit gives. The control runs first and leaves its truth and knock as the reference.
    private void keepSprint(final String id, final boolean cheat) {
        this.combatJoin(id);
        this.step(1, id + ": a sword, the victim ahead, sprint", () -> {
            this.open(id);
            this.give(this.c, new ItemStack(Items.DIAMOND_SWORD));
            this.place(0.0, 3.4);
            this.c.forcePosition = true;
            this.c.keys = FORWARD_SPRINT;
        });
        this.step(5, id + ": the hit", () -> {
            this.base = this.snap(this.c);
            this.c.noAttackPrediction = cheat;
            this.v.lastKnock = null;
            this.attack(this.c, 1);
        });
        this.step(4, id + ": let go", () -> this.c.keys = NONE);
        // Late enough for a correction that waits behind queued windows (as the axe rows)
        this.judge(10, id, () -> {
            final Snap d = this.delta(this.c);
            final Simulation s = this.c.auth.simulation();
            final Vec3 truth = new Vec3(s.x(), s.y(), s.z());
            final Vec3 knock = this.v.lastKnock;
            this.note("knock " + (knock == null ? "none" : f(knock.horizontalDistance()) + " across, " + f(knock.y) + " up") + ", corrections "
                + d.corrections());
            if (!cheat) {
                this.label = "CONTROL";
                this.honestExpect(knock != null && knock.horizontalDistance() > 0.85, "the armed sprint knock landed");
                this.honestExpect(d.corrections() == 0, "no correction (" + d.corrections() + ")");
                this.keepSprintTruth = truth;
                this.keepSprintKnock = knock;
                return;
            }
            this.expect(d.corrections() >= 1, "the client that skipped its slowdown was corrected (" + d.corrections() + ")");
            if (this.keepSprintTruth != null && this.keepSprintKnock != null && knock != null) {
                final double gap = truth.distanceTo(this.keepSprintTruth);
                this.expect(gap <= EPS, "the truth is the honest hit's, x0.6 and all (gap " + f(gap) + ")");
                this.expect(knock.distanceTo(this.keepSprintKnock) <= 1.0E-6, "the knock is the honest hit's (" + f(knock.horizontalDistance()) + " across)");
            } else {
                this.expect(false, "the control ran first (truth " + this.keepSprintTruth + ", knock " + this.keepSprintKnock + ", this knock " + knock + ")");
            }
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // Fable review #7b: a 26.x weapon has a real cooldown, and the retry's "the hit was weak" explained a KeepSprint client's
    // claims on any full-strength axe hit. Counted from the last hit the ticker is exact, so a sprint hit one cooldown later
    // keeps the client's x0.6 and sprint stop. The first hit, standing, is honest in both rows: it resets the ticker at a
    // known tick (an iron axe's cooldown is 22.2 ticks; the second hit comes 30 after the first)
    private void keepSprintAxe(final String id, final boolean cheat) {
        this.combatJoin(id);
        this.step(1, id + ": an axe, the victim in reach", () -> {
            this.open(id);
            this.give(this.c, new ItemStack(Items.IRON_AXE));
            this.place(0.0, 2.8);
            this.c.forcePosition = true;
            this.c.keys = NONE;
        });
        this.step(25, id + ": the first hit, standing, at full strength", () -> this.attack(this.c, 1));
        this.step(25, id + ": the victim ahead again, sprint", () -> {
            this.place(0.0, 3.4);
            this.c.keys = FORWARD_SPRINT;
        });
        final long[] branches = new long[4];
        this.step(5, id + ": the second hit", () -> {
            this.base = this.snap(this.c);
            branches[0] = this.c.auth.stats().attackStrengthBranches();
            branches[1] = this.c.auth.stats().correctionsHeld();
            branches[2] = this.c.auth.stats().hotbarBranches();
            branches[3] = this.c.auth.stats().correctionsWaited();
            this.c.noAttackPrediction = cheat;
            this.attack(this.c, 1);
        });
        this.step(4, id + ": let go", () -> this.c.keys = NONE);
        // Late enough for a correction that waits behind queued windows (a loaded run once judged before it landed)
        this.judge(10, id, () -> {
            final Snap d = this.delta(this.c);
            this.note("corrections " + d.corrections() + ", victim health " + this.v.player.getHealth() + "; weak-hit branches "
                + (this.c.auth.stats().attackStrengthBranches() - branches[0]) + ", held " + (this.c.auth.stats().correctionsHeld() - branches[1])
                + ", waited " + (this.c.auth.stats().correctionsWaited() - branches[3]) + ", hotbar branches "
                + (this.c.auth.stats().hotbarBranches() - branches[2]));
            if (!cheat) {
                this.label = "CONTROL";
                this.honestExpect(d.corrections() == 0, "no correction (" + d.corrections() + ")");
            } else {
                this.expect(d.corrections() >= 1, "the client that skipped its slowdown was corrected (" + d.corrections() + ")");
            }
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // pvp-issues HR-7: a vanilla spear's attack is a stab (Minecraft.startAttack: PIERCING_WEAPON sends PlayerAction STAB,
    // never an Attack), which the server runs along its own line (PiercingWeapon.attack: getHitEntitiesAlong from the
    // attacker's truth, reach 2.0 to 4.5). Under enforce it lands within the spear's reach and not past it
    private void spearStab(final String id, final double dz, final boolean inReach) {
        this.combatJoin(id);
        this.step(1, id + ": a spear, the victim ahead", () -> {
            this.open(id);
            this.give(this.c, new ItemStack(Items.IRON_SPEAR));
            this.place(0.0, dz);
            this.c.forcePosition = true;
            this.c.keys = NONE;
        });
        final float[] health = new float[1];
        this.step(30, id + ": the stab", () -> {
            health[0] = this.v.player.getHealth();
            this.c.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STAB, BlockPos.ZERO, Direction.DOWN));
        });
        this.judge(5, id, () -> {
            final float dealt = health[0] - this.v.player.getHealth();
            this.note("dealt " + dealt);
            if (inReach) {
                this.label = "CONTROL";
                this.honestExpect(dealt > 0.0F, "the stab landed");
            } else {
                this.expect(dealt == 0.0F, "nothing past the spear's reach");
            }
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // pvp-issues MV-3: packet criticals claim a small hop (y +0.0625, then back down, not on the ground) in the ticks before
    // the hit, so a server that trusts claims has the attacker falling when the attack (sent ahead of its tick's move) lands:
    // a critical. The truth never left the ground: no crit, the damage is the honest hit's, the hop is corrected.
    private void packetCrit(final String id, final boolean cheat) {
        this.combatJoin(id);
        this.step(1, id + ": a sword, the victim in reach", () -> {
            this.open(id);
            this.give(this.c, new ItemStack(Items.DIAMOND_SWORD));
            this.place(0.0, 2.8);
            this.c.forcePosition = true;
            this.c.keys = NONE;
        });
        final float[] health = new float[1];
        this.step(5, id + ": the hop and the hit", () -> {
            this.base = this.snap(this.c);
            this.track = new Track();
            this.probe = () -> this.track.sample(this.c);
            health[0] = this.v.player.getHealth();
            final int[] k = {0};
            this.c.before = x -> {
                final int t = k[0]++;
                if (cheat && t < 2) {
                    x.moveOverride = new ServerboundMovePlayerPacket.Pos(new Vec3(x.model.x(), x.model.y() + (t == 0 ? 0.0625 : 0.0), x.model.z()), false, false);
                }
                if (t == 2) {
                    this.attack(x, 1);
                    x.before = null;
                }
            };
        });
        this.judge(10, id, () -> {
            final Snap d = this.delta(this.c);
            final float dealt = health[0] - this.v.player.getHealth();
            this.note("dealt " + f(dealt) + ", truth up to y +" + f(this.track.truthMaxY - this.gy) + ", corrections " + d.corrections());
            if (!cheat) {
                this.label = "CONTROL";
                this.honestExpect(dealt > 0.0F, "the hit landed");
                this.honestExpect(d.corrections() == 0, "no correction (" + d.corrections() + ")");
                this.honestHitDamage = dealt;
                return;
            }
            this.expect(this.track.truthMaxY <= this.gy + EPS, "the truth never left the ground (y +" + f(this.track.truthMaxY - this.gy) + ")");
            this.expect(!Float.isNaN(this.honestHitDamage) && Math.abs(dealt - this.honestHitDamage) < 1.0E-4F,
                "no critical: the damage is the honest hit's (" + f(dealt) + " against " + f(this.honestHitDamage) + ")");
            this.expect(d.corrections() >= 1, "the hop was corrected (" + d.corrections() + ")");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // pvp-issues WD-1, HCF's block glitch: place a block where a plugin cancels the placement (a claim, a combat wall) and climb
    // the client's ghost of it over a 2-high wall. The truth never stands on a placement the server denied
    // (honor-denied-predictions false), so it stays behind the wall; the control places the same block for real and climbs
    // over. The cheat keeps its ghost past the server's ack, the way a slow ack or a modified client would.
    private void blockGlitch(final String id, final boolean denied) {
        this.begin(id, () -> this.wall(2, 2, Blocks.STONE.defaultBlockState()));
        final BlockPos[] spot = new BlockPos[1];
        this.step(1, id + ": stone", () -> {
            this.give(this.c, new ItemStack(Items.STONE, 16));
            // the crosshair on the top face of the floor block one ahead (eye 1.62 above it, 1.0 away)
            this.c.pitch = (float) Math.toDegrees(Math.atan2(1.62, 1.0));
        });
        this.arm(id, () -> {
            spot[0] = new BlockPos(this.ox, this.gy, this.oz + 1);
            if (denied) {
                this.deniedPlacements.add(spot[0].asLong());
            }
            this.c.forcePosition = true;
            this.c.keepGhostBlocks = denied;
            final BlockPos floor = spot[0].below();
            final int[] k = {0};
            this.c.before = x -> {
                final int t = k[0]++;
                if (t == 0) {
                    final int sequence = ++x.sequence;
                    x.actions.add(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                        new BlockHitResult(new Vec3(floor.getX() + 0.5, this.gy, floor.getZ() + 0.5), Direction.UP, floor, false), sequence));
                    x.predictPlace(spot[0], Blocks.STONE.defaultBlockState(), sequence);
                } else if (t == 2) {
                    x.pitch = 0.0F;
                    x.keys = new Input(true, false, false, false, true, false, false); // forward and jump: onto it, then over the wall
                }
            };
        });
        this.judge(40, id, () -> {
            final Snap d = this.delta(this.c);
            final boolean placed = !this.level.getBlockState(spot[0]).isAir();
            this.note("the block " + (placed ? "placed" : "not placed") + "; truth up to y +" + f(this.track.truthMaxY - this.gy) + ", z +"
                + f(this.track.truthMaxZ - this.oz) + "; claims up to y +" + f(this.track.claimMaxY - this.gy) + "; corrections " + d.corrections());
            if (!denied) {
                this.label = "CONTROL";
                this.honestExpect(placed, "the block was placed");
                this.honestExpect(this.track.truthMaxY >= this.gy + 2.0 - EPS, "the truth climbed onto the wall (y +" + f(this.track.truthMaxY - this.gy) + ")");
                this.honestExpect(d.corrections() == 0, "no correction (" + d.corrections() + ")");
                return;
            }
            this.expect(!placed, "the plugin cancelled the placement");
            this.expect(this.track.claimMaxY >= this.gy + 1.0 - EPS, "the client stood on its ghost (claims up to y +" + f(this.track.claimMaxY - this.gy) + ")");
            this.expect(this.track.truthMaxY <= this.gy + 1.2523 + 1.0E-3, "the truth only jumped from the floor (y +" + f(this.track.truthMaxY - this.gy) + ")");
            this.expect(this.track.truthMaxZ < this.oz + 2.0 - 0.3 + EPS, "the truth stayed behind the wall (z +" + f(this.track.truthMaxZ - this.oz) + ")");
            this.expect(d.corrections() >= 1, "the client was corrected (" + d.corrections() + ")");
            this.expect(!this.c.kicked, "not kicked");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // A client that never acks a chunk batch stops getting chunks while the server keeps loading them around it. Teleported
    // next to a chunk border (the hold sends the chunk under it, nothing else), it walks into the loaded chunk it was never
    // sent while claiming 3 blocks a tick on the ground. The chunk-unknown exemption is for our record missing a chunk the
    // client holds: it mustn't hand ticks over a chunk Moonrise never sent to vanilla's checks
    private void unsentChunkWalk() {
        final String id = "chunks: never acks a chunk batch, walks into a loaded chunk it was never sent, claiming 3 blocks a tick";
        this.begin(id, () -> {
        });
        final double[] start = new double[1];
        final double[] far = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        final long[] exempt = new long[1];
        this.arm(id, () -> {
            this.c.noChunkAcks = true;
            this.c.forcePosition = true;
        });
        this.step(5, id + ": teleport 1.5 short of a chunk border", () ->
            this.c.player.connection.teleport(this.ox + 400.5, this.gy, Math.floorDiv(this.oz, 16) * 16 + 14.5, 0.0F, 0.0F));
        this.step(40, id + ": walk across, the claims racing ahead", () -> {
            final Simulation s = this.c.auth.simulation();
            start[0] = s.z();
            exempt[0] = this.c.auth.stats().exempt(AuthStats.EXEMPT_CHUNK_UNKNOWN);
            this.c.keys = FORWARD;
            final double[] claim = {s.z()};
            this.c.cheat = x -> {
                claim[0] += 3.0;
                x.moveOverride = new ServerboundMovePlayerPacket.Pos(new Vec3(x.model.x(), this.gy, claim[0]), true, false);
            };
            this.probe = () -> {
                far[0] = Math.max(far[0], this.c.auth.simulation().z());
                far[1] = Math.max(far[1], this.c.player.getZ());
            };
        });
        this.judge(12, id, () -> {
            final long chunkUnknown = this.c.auth.stats().exempt(AuthStats.EXEMPT_CHUNK_UNKNOWN) - exempt[0];
            this.note("the truth up to z +" + f(far[0] - start[0]) + ", the server's position up to z +" + f(far[1] - start[0])
                + ", chunk-unknown ticks " + chunkUnknown);
            this.expect(far[1] - start[0] < 4.0, "the server never took the claims (z +" + f(far[1] - start[0]) + " in 12 ticks)");
            this.expect(chunkUnknown == 0, "no chunk-unknown tick over a chunk Moonrise never sent (" + chunkUnknown + ")");
            this.c.cheat = null;
            this.c.noChunkAcks = false;
        });
        this.finish(id);
    }

    // A client that never reports PlayerLoaded: the server's client-load timeout (ServerPlayer.tick → tickClientLoadTimeout)
    // takes it as loaded anyway, and vanilla handles its moves from then on. Authority must judge them too, not leave the
    // client exempt ("not loaded") for good with every move on vanilla's checks. It claims a flight of 0.5 a tick
    private void neverLoaded() {
        final String id = "load: never sends PlayerLoaded, then claims a flight of 0.5 a tick past the server's load timeout";
        this.step(1, id + ": arena and join", () -> {
            this.open(id);
            this.probe = () -> {
            };
            this.track = new Track();
            this.resetMoves();
            this.clearArena();
            this.c = new Cheater(this.next("Cheat"), this.ox + 0.5, this.gy, this.oz + 0.5, 0.0F);
            this.c.neverLoads = true;
        });
        final double[] y0 = new double[1];
        final double[] top = {Double.NEGATIVE_INFINITY};
        this.step(WARM + 80, id + ": past the timeout, fly", () -> {
            this.base = this.snap(this.c);
            y0[0] = this.c.player.getY();
            final double[] claim = {y0[0]};
            this.c.cheat = x -> {
                claim[0] += 0.5;
                x.moveOverride = new ServerboundMovePlayerPacket.Pos(new Vec3(this.ox + 0.5, claim[0], this.oz + 0.5), false, false);
            };
            this.probe = () -> top[0] = Math.max(top[0], this.c.player.getY());
        });
        this.judge(20, id, () -> {
            this.note("the server takes the client as loaded: " + this.c.player.connection.hasClientLoaded() + "; not-loaded ticks "
                + this.c.auth.stats().exempt(AuthStats.EXEMPT_NOT_LOADED) + "; the server's position up to y +" + f(top[0] - y0[0]));
            this.expect(top[0] - y0[0] < 1.0, "the server never took the flight (y +" + f(top[0] - y0[0]) + " in 20 ticks)");
            this.c.cheat = null;
        });
        this.finish(id);
    }

    // pvp-issues WD-4, ghost hand: a chest 3 blocks ahead, behind a 2-high wall or not; the client names a point on the
    // chest's front face (vanilla's handler checks the distance and that the point is near the block, never the sight)
    private void ghostChest(final String id, final boolean walled) {
        final BlockPos[] chest = new BlockPos[1];
        this.begin(id, () -> {
            chest[0] = new BlockPos(this.ox, this.gy, this.oz + 3);
            this.set(chest[0].getX(), chest[0].getY(), chest[0].getZ(), Blocks.CHEST.defaultBlockState());
            if (walled) {
                this.wall(2, 2, Blocks.STONE.defaultBlockState());
            }
        });
        final long[] unseen = new long[1];
        this.arm(id, () -> {
            unseen[0] = this.c.auth.inputRules().count(InputRules.UNSEEN_TARGET);
            this.c.forcePosition = true;
            final Vec3 front = new Vec3(chest[0].getX() + 0.5, chest[0].getY() + 0.4, chest[0].getZ() + 0.0625);
            this.c.actions.add(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(front, Direction.NORTH, chest[0], false),
                ++this.c.sequence));
        });
        this.judge(5, id, () -> {
            final boolean opened = this.c.player.containerMenu != this.c.player.inventoryMenu;
            final long counted = this.c.auth.inputRules().count(InputRules.UNSEEN_TARGET) - unseen[0];
            this.note("chest " + (opened ? "opened" : "not opened") + ", unseen-target " + counted);
            if (!walled) {
                this.label = "CONTROL";
                this.honestExpect(opened, "the chest opened");
                this.honestExpect(counted == 0, "no unseen-target (" + counted + ")");
            } else {
                this.expect(!opened, "the chest behind the wall stayed shut");
                this.expect(counted == 1, "unseen-target counted (" + counted + ")");
            }
            this.c.player.closeContainer();
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // pvp-issues WD-4, ghost hand: a dig started on a block 3 ahead, behind a 2-high wall or not
    private void ghostDig(final String id, final boolean walled) {
        final BlockPos[] dirt = new BlockPos[1];
        this.begin(id, () -> {
            dirt[0] = new BlockPos(this.ox, this.gy, this.oz + 3);
            this.set(dirt[0].getX(), dirt[0].getY(), dirt[0].getZ(), Blocks.DIRT.defaultBlockState());
            if (walled) {
                this.wall(2, 2, Blocks.STONE.defaultBlockState());
            }
        });
        final long[] unseen = new long[1];
        this.arm(id, () -> {
            unseen[0] = this.c.auth.inputRules().count(InputRules.UNSEEN_TARGET);
            this.c.forcePosition = true;
            // the crosshair on it: eye 1.62 above the floor, its centre 3.0 ahead and 0.5 up
            this.c.pitch = (float) Math.toDegrees(Math.atan2(1.12, 3.0));
            this.c.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, dirt[0], Direction.NORTH,
                ++this.c.sequence));
        });
        this.judge(5, id, () -> {
            final long counted = this.c.auth.inputRules().count(InputRules.UNSEEN_TARGET) - unseen[0];
            this.note("unseen-target " + counted);
            if (!walled) {
                this.label = "CONTROL";
                this.honestExpect(counted == 0, "no unseen-target (" + counted + ")");
            } else {
                this.expect(counted == 1, "the dig behind the wall is unseen-target (" + counted + ")");
            }
            this.c.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, dirt[0], Direction.NORTH,
                ++this.c.sequence));
            this.c.pitch = 0.0F;
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // pvp-issues WD-4: a lectern's book rest rises 2 pixels above its cube, and a click there is on its outline (the crosshair
    // passes over it: the point's sight decides)
    private void lecternTop(final String id) {
        final BlockPos[] lectern = new BlockPos[1];
        this.begin(id, () -> {
            lectern[0] = new BlockPos(this.ox, this.gy, this.oz + 3);
            this.set(lectern[0].getX(), lectern[0].getY(), lectern[0].getZ(), Blocks.LECTERN.defaultBlockState());
        });
        final long[] unseen = new long[1];
        this.arm(id, () -> {
            unseen[0] = this.c.auth.inputRules().count(InputRules.UNSEEN_TARGET);
            this.c.forcePosition = true;
            // Facing north, the rest's top is 18 pixels up and 9.7 to 14 back from the lectern's north side
            final Vec3 top = new Vec3(lectern[0].getX() + 0.5, lectern[0].getY() + 1.125, lectern[0].getZ() + 0.75);
            this.c.actions.add(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(top, Direction.UP, lectern[0], false),
                ++this.c.sequence));
        });
        this.judge(5, id, () -> {
            final long counted = this.c.auth.inputRules().count(InputRules.UNSEEN_TARGET) - unseen[0];
            this.note("unseen-target " + counted);
            this.label = "CONTROL";
            this.honestExpect(counted == 0, "no unseen-target (" + counted + ")");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // Walking with the sword, the player opens its inventory (no packet; KeyMapping.releaseAll, so the next input is
    // empty), shift-clicks a pot from the main inventory into the hotbar, closes the screen, walks again, takes the pot
    // with its number key and throws it: every packet is a vanilla client's, so no grammar drop and no correction
    private void potRefill() {
        final String id = "honest: a pot refill through the inventory screen mid-fight, then the throw (control)";
        this.begin(id, () -> {
        });
        this.step(1, id + ": a sword, an empty slot 2, pots in the inventory", () -> {
            this.c.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            this.c.player.getInventory().setItem(1, ItemStack.EMPTY);
            final ItemStack pots = splashHealing();
            pots.setCount(1);
            this.c.player.getInventory().setItem(9, pots);
            this.c.player.getInventory().setSelectedSlot(0);
            this.c.player.inventoryMenu.broadcastChanges();
        });
        final long[] dropped = new long[1];
        this.step(5, id + ": walk", () -> {
            this.open(id);
            this.label = "CONTROL";
            this.base = this.snap(this.c);
            dropped[0] = this.c.auth.stats().grammarDropped();
            this.c.forcePosition = true;
            this.c.keys = FORWARD;
        });
        this.step(10, id + ": open the inventory (the keys release)", () -> this.c.keys = NONE);
        this.step(2, id + ": shift-click the pot into the hotbar", () -> this.c.actions.add(new ServerboundContainerClickPacket(0,
            this.c.player.inventoryMenu.getStateId(), (short) 9, (byte) 0, ContainerInput.QUICK_MOVE, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY)));
        this.step(3, id + ": close it, walk on", () -> {
            this.c.actions.add(new ServerboundContainerClosePacket(0));
            this.c.keys = FORWARD;
        });
        this.step(4, id + ": number key 2, throw the pot", () -> {
            this.honestExpect(this.c.player.getInventory().getItem(1).is(Items.SPLASH_POTION), "the pot moved into hotbar slot 2 ("
                + this.c.player.getInventory().getItem(1) + ")");
            this.c.actions.add(new ServerboundSetCarriedItemPacket(1));
            this.c.noUsePrediction = true;
            this.c.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, this.c.yaw, this.c.pitch));
        });
        this.judge(10, id, () -> {
            final Snap d = this.delta(this.c);
            final var thrown = this.potions(this.c);
            this.honestExpect(thrown.size() == 1, "the pot was thrown (" + thrown.size() + ")");
            this.honestExpect(this.c.auth.stats().grammarDropped() == dropped[0], "no grammar drop (" + (this.c.auth.stats().grammarDropped() - dropped[0]) + ")");
            this.honestExpect(d.corrections() == 0 && d.exact() == d.compared(), "no correction: " + d.corrections() + " corrections, " + d.exact() + "/"
                + d.compared() + " claims exact");
            thrown.forEach(net.minecraft.world.entity.Entity::discard);
            this.c.noUsePrediction = false;
            this.c.keys = NONE;
        });
        this.finish(id);
    }

    // The client eats or drinks it to the end (the server's completion reaches it as USE_ITEM_COMPLETE), then walks or
    // sprints: no correction while it eats, slowed, or after, sped up by the potion
    private void consume(final String id, final boolean potion) {
        this.begin(id, () -> {
        });
        this.step(1, id + ": in hand", () -> {
            this.c.player.getInventory().setItem(0, potion
                ? net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.SWIFTNESS)
                : new ItemStack(Items.GOLDEN_APPLE));
            this.c.player.getInventory().setSelectedSlot(0);
            this.c.player.inventoryMenu.broadcastChanges();
        });
        this.step(5, id + ": use it, walking", () -> {
            this.open(id);
            this.label = "CONTROL";
            this.base = this.snap(this.c);
            this.c.forcePosition = true;
            this.c.keys = FORWARD;
            this.c.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, this.c.yaw, this.c.pitch));
        });
        this.step(45, id + ": done, sprint", () -> {
            this.honestExpect(!this.c.player.isUsingItem() && !this.c.auth.simulation().usingItem(), "the use ended on both (server "
                + this.c.player.isUsingItem() + ", truth " + this.c.auth.simulation().usingItem() + ")");
            this.honestExpect(potion ? this.c.player.hasEffect(net.minecraft.world.effect.MobEffects.SPEED)
                : this.c.player.hasEffect(net.minecraft.world.effect.MobEffects.ABSORPTION), "the effect is on");
            this.c.keys = FORWARD_SPRINT;
        });
        this.judge(20, id, () -> {
            final Snap d = this.delta(this.c);
            this.honestExpect(d.corrections() == 0 && d.exact() == d.compared() && d.compared() >= 40, "no correction: " + d.corrections()
                + " corrections, " + d.exact() + "/" + d.compared() + " claims exact");
            this.c.keys = NONE;
            this.c.player.removeAllEffects();
        });
        this.finish(id);
    }

    private static ItemStack splashHealing() {
        return net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.SPLASH_POTION, net.minecraft.world.item.alchemy.Potions.HEALING);
    }

    private java.util.List<net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion> potions(final Cheater x) {
        return this.level.getEntitiesOfClass(net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion.class,
            x.player.getBoundingBox().inflate(6.0));
    }

    // Silent auto-pot: in one window the pot's slot, a throw at the feet (pitch 90) under level claims, the sword's slot
    // again. The throw flies along the tick's rotation (the one the claims, the judge and every viewer saw), and the
    // sword can't come back in the tick the pot was thrown
    private void silentPot() {
        final String id = "use: a pot thrown at the feet under level claims, the sword back in the same tick (silent auto-pot)";
        this.begin(id, () -> {
        });
        this.step(1, id + ": a sword in slot 1, a pot in slot 2", () -> {
            this.c.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            this.c.player.getInventory().setItem(1, splashHealing());
            this.c.player.inventoryMenu.broadcastChanges();
        });
        final long[] before = new long[2];
        this.step(5, id + ": the cheat", () -> {
            this.base = this.snap(this.c);
            before[0] = this.c.auth.inputRules().count(InputRules.USE_ROTATION);
            before[1] = this.c.auth.inputRules().count(InputRules.SLOT_AFTER_ACTION);
            this.c.yaw = 0.0F;
            this.c.pitch = 0.0F;
            this.c.forcePosition = true;
            this.c.noUsePrediction = true; // a splash pot is thrown, not used
            this.c.actions.add(new ServerboundSetCarriedItemPacket(1));
            this.c.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, 0.0F, 90.0F));
            this.c.actions.add(new ServerboundSetCarriedItemPacket(0));
        });
        this.judge(2, id, () -> {
            final var thrown = this.potions(this.c);
            final double vy = thrown.isEmpty() ? Double.NaN : thrown.getFirst().getDeltaMovement().y;
            this.expect(!thrown.isEmpty() && vy > 0.0, "the pot flew along the tick's level aim, not at the feet (" + thrown.size() + " thrown, vy "
                + f(vy) + ")");
            final long rotation = this.c.auth.inputRules().count(InputRules.USE_ROTATION) - before[0];
            final long slot = this.c.auth.inputRules().count(InputRules.SLOT_AFTER_ACTION) - before[1];
            this.expect(rotation == 1, "use-rotation counted " + rotation);
            this.expect(slot == 1 && this.c.player.getInventory().getSelectedSlot() == 1, "the sword's slot was refused in the throw's tick (counted "
                + slot + ", server slot " + (this.c.player.getInventory().getSelectedSlot() + 1) + ")");
            thrown.forEach(net.minecraft.world.entity.Entity::discard);
            this.c.noUsePrediction = false;
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // An off-hand pot while the main hand holds a sword, whose block consumes the click first: no throw. With the main hand
    // empty the off hand's use is honest (control)
    private void offhandPot(final boolean sword) {
        final String id = sword ? "use: an off-hand pot thrown while a sword is in the main hand"
            : "use: an off-hand pot thrown with an empty main hand (control)";
        this.begin(id, () -> {
        });
        this.step(1, id + ": the hands", () -> {
            this.c.player.getInventory().setItem(0, sword ? new ItemStack(Items.DIAMOND_SWORD) : ItemStack.EMPTY);
            this.c.player.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, splashHealing());
            this.c.player.inventoryMenu.broadcastChanges();
        });
        final long[] before = new long[1];
        this.step(5, id + ": throw it", () -> {
            this.base = this.snap(this.c);
            before[0] = this.c.auth.inputRules().count(InputRules.OFFHAND_AFTER_MAIN);
            this.c.pitch = 0.0F;
            this.c.forcePosition = true;
            this.c.noUsePrediction = true;
            this.c.actions.add(new ServerboundUseItemPacket(InteractionHand.OFF_HAND, 1, this.c.yaw, 0.0F));
        });
        this.judge(2, id, () -> {
            final var thrown = this.potions(this.c);
            final long counted = this.c.auth.inputRules().count(InputRules.OFFHAND_AFTER_MAIN) - before[0];
            if (sword) {
                this.expect(thrown.isEmpty() && counted == 1, "no throw (" + thrown.size() + " thrown), offhand-after-main counted " + counted);
            } else {
                this.label = "CONTROL";
                this.honestExpect(thrown.size() == 1 && counted == 0, "thrown " + thrown.size() + ", offhand-after-main counted " + counted);
            }
            thrown.forEach(net.minecraft.world.entity.Entity::discard);
            this.c.noUsePrediction = false;
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void sprintLedger(final String id, final int kind) {
        this.combatJoin(id);
        this.step(1, id + ": a sword, the victim aside, sprint", () -> {
            this.open(id);
            this.give(this.c, new ItemStack(Items.DIAMOND_SWORD));
            this.place(3.0, 1.0);
            this.c.forcePosition = true;
            this.c.keys = FORWARD_SPRINT;
        });
        final long[] released = new long[1];
        this.step(8, id + ": a hit used the arm", () -> {
            this.tests.check(this.c.auth.simulation().sprinting(), this.scenario + ": the attacker sprints");
            this.tests.check(this.c.player.revivalSprintLedger.armed(false), this.scenario + ": its sprint start armed the knock");
            this.c.player.revivalSprintLedger.consume(); // as a landed sprint hit does
            released[0] = this.c.auth.inputRules().count(InputRules.RELEASE_WITHOUT_USE);
            final ServerboundUseItemPacket use = new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, 0.0F, 0.0F);
            final ServerboundPlayerActionPacket release = new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                BlockPos.ZERO, Direction.DOWN);
            final int[] k = {0};
            this.c.before = x -> {
                final int t = k[0]++;
                switch (kind) {
                    case 0 -> x.keys = t < 2 ? NONE : FORWARD_SPRINT; // let go of W for two ticks, sprint key held
                    case 1 -> {
                        if (t == 0) {
                            this.attack(x, 1); // stops the simulated sprint; the held key re-sprints it this tick
                            x.injectSprint = ServerboundPlayerCommandPacket.Action.STOP_SPRINTING;
                        }
                    }
                    case 2 -> {
                        if (t == 0) {
                            x.injectSprint = ServerboundPlayerCommandPacket.Action.STOP_SPRINTING;
                        } else if (t == 1) {
                            x.injectSprint = ServerboundPlayerCommandPacket.Action.START_SPRINTING;
                            this.attack(x, 1);
                        }
                    }
                    case 3 -> {
                        if (t == 0) {
                            x.actions.add(use);
                        } else if (t == 2) {
                            x.actions.add(release);
                        }
                    }
                    default -> {
                        if (t == 0) {
                            x.actions.add(use);
                            x.actions.add(release);
                        }
                    }
                }
                if (t >= 3) {
                    x.before = null;
                }
            };
        });
        this.judge(8, id, () -> {
            final boolean armed = this.c.player.revivalSprintLedger.armed(false);
            if (kind == 0 || kind == 3) {
                this.label = "CONTROL";
                this.honestExpect(armed, "re-armed: " + armed);
            } else {
                this.expect(!armed, "the held level, not re-armed (armed " + armed + ")");
            }
            if (kind == 4) {
                final long counted = this.c.auth.inputRules().count(InputRules.RELEASE_WITHOUT_USE) - released[0];
                this.expect(counted >= 1, "release-without-use counted " + counted);
            }
            this.note("sprinting " + this.c.auth.simulation().sprinting() + ", corrections " + this.delta(this.c).corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    private void sprintEcho(final String id, final boolean noStop) {
        this.begin(id, () -> {
        });
        final double[] walk = {0.0};
        final int[] stale = {0};
        this.arm(id, () -> {
            this.c.keys = FORWARD;
            this.c.forcePosition = true;
        });
        this.step(20, id + ": sprint, drop it, ack late", () -> {
            walk[0] = this.track.lastDz;
            this.base = this.snap(this.c);
            this.track = new Track();
            final int[] k = {0};
            this.c.suppressSprintStop = noStop;
            this.c.before = x -> {
                // Sprint three ticks with every pong 3 ticks late, so the sprint attribute sync (sent while the
                // simulation sprints) is still unacked when the client drops sprint; then stand (no double tap), then walk
                final int t = k[0]++;
                if (t == 0) {
                    x.pongDelay = 3;
                }
                if (t == 1) {
                    // Any movement-speed sync while sprinting (a potion, armour): here a no-op modifier
                    x.player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).addOrUpdateTransientModifier(
                        new net.minecraft.world.entity.ai.attributes.AttributeModifier(PROBE_MODIFIER, 0.0,
                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
                }
                if (t < 3) {
                    x.keys = FORWARD_SPRINT;
                } else if (t < 13) {
                    x.keys = NONE;
                } else {
                    x.keys = FORWARD;
                }
            };
            final Runnable sample = this.probe;
            this.probe = () -> {
                sample.run();
                if (this.c.auth.simulation() instanceof final PortSimulation port && !port.sprinting()
                    && port.state().attributes.movementSpeed.value() > 0.1 + 1.0E-6) {
                    stale[0]++;
                }
            };
        });
        this.judge(36, id, () -> {
            final Simulation s = this.c.auth.simulation();
            final double attribute = s instanceof final PortSimulation port ? port.state().attributes.movementSpeed.value() : Double.NaN;
            this.expect(stale[0] == 0, "ticks the truth kept the sprint modifier while not sprinting: " + stale[0] + " (speed attribute now " + f(attribute) + ")");
            // The baseline was taken 20 ticks into a walk, 0.546^20 (about 5e-6 of the speed) short of the steady walk: the
            // tolerance covers that, and is far below the sprint speed a stale modifier gives (0.2806 against 0.2159)
            this.expect(this.track.lastDz <= walk[0] + 1.0E-5, "walking " + f(this.track.lastDz) + "/tick, honest walk " + f(walk[0]));
            this.expect(this.c.auth.combatSprinting() == s.sprinting(), "combat reads the simulated sprint (" + this.c.auth.combatSprinting() + ", truth "
                + s.sprinting() + ")");
            this.note("self attribute syncs carrying the sprint modifier: " + this.c.sprintSyncs);
            this.c.player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).removeModifier(PROBE_MODIFIER);
            this.note("ServerPlayer.isSprinting " + this.c.player.isSprinting() + " (sprint stays with the replayed commands)");
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // =================================================================================================================
    // Scenario plumbing
    // =================================================================================================================

    private void setup() {
        final org.bukkit.Location spawn = this.level.getWorld().getSpawnLocation();
        this.ox = spawn.getBlockX() + 24;
        this.oz = spawn.getBlockZ() - 8;
        this.level.getChunk(this.ox >> 4, this.oz >> 4); // loaded (a filtered dev run has no earlier scenario that loaded it)
        this.gy = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, this.ox, this.oz);
        final net.minecraft.server.ServerScoreboard scoreboard = this.server.getScoreboard();
        net.minecraft.world.scores.PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM);
        }
        team.setCollisionRule(net.minecraft.world.scores.Team.CollisionRule.NEVER);

        final PluginManager plugins = this.tests.getServer().getPluginManager();
        plugins.registerEvent(PrePlayerAttackEntityEvent.class, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (event instanceof final PrePlayerAttackEntityEvent attack) {
                this.attacks.merge(attack.getPlayer().getUniqueId(), 1, Integer::sum);
            }
        }, this.tests);
        plugins.registerEvent(gg.hcfrevival.revival.api.event.AuthInputAnomalyEvent.class, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (event instanceof final gg.hcfrevival.revival.api.event.AuthInputAnomalyEvent anomaly) {
                this.anomalies.computeIfAbsent(anomaly.getPlayer().getUniqueId(), k -> new java.util.HashSet<>()).add(anomaly.getKind());
            }
        }, this.tests);
        plugins.registerEvent(InventoryClickEvent.class, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (event instanceof final InventoryClickEvent click) {
                this.clicks.merge(click.getWhoClicked().getUniqueId(), 1, Integer::sum);
            }
        }, this.tests);
        plugins.registerEvent(PlayerKickEvent.class, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (event instanceof final PlayerKickEvent kick) {
                final UUID uuid = kick.getPlayer().getUniqueId();
                this.kicks.put(uuid, PlainTextComponentSerializer.plainText().serialize(kick.reason()));
                for (final Cheater x : this.clients) {
                    if (x.player.getUUID().equals(uuid)) {
                        x.kicked = true;
                    }
                }
            }
        }, this.tests);
        plugins.registerEvent(PlayerMoveEvent.class, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (event instanceof final PlayerMoveEvent move && this.c != null && move.getPlayer().getUniqueId().equals(this.c.player.getUUID())) {
                this.moveMaxY = Math.max(this.moveMaxY, move.getTo().getY());
                this.moveMinY = Math.min(this.moveMinY, move.getTo().getY());
                this.moveMaxZ = Math.max(this.moveMaxZ, move.getTo().getZ());
            }
        }, this.tests);
        plugins.registerEvent(BlockPlaceEvent.class, this.listener, EventPriority.LOWEST, (ignored, event) -> {
            if (event instanceof final BlockPlaceEvent placed && this.deniedPlacements.contains(BlockPos.asLong(placed.getBlock().getX(),
                placed.getBlock().getY(), placed.getBlock().getZ()))) {
                placed.setCancelled(true);
            }
        }, this.tests);
        plugins.registerEvent(BlockBreakEvent.class, this.listener, EventPriority.LOWEST, (ignored, event) -> {
            if (event instanceof final BlockBreakEvent broken && this.protectedBlocks.contains(BlockPos.asLong(broken.getBlock().getX(), broken.getBlock().getY(),
                broken.getBlock().getZ()))) {
                broken.setCancelled(true);
            }
        }, this.tests);

        this.driver = this.tests.getServer().getScheduler().runTaskTimer(this.tests, this::drive, 1L, 1L);
        this.tests.check(this.gy > this.level.getMinY(), "cheat suite: arena at " + this.ox + " " + this.gy + " " + this.oz);
    }

    private void drive() {
        for (final Cheater x : List.copyOf(this.clients)) {
            try {
                x.step();
            } catch (final RuntimeException failure) {
                this.tests.getLogger().log(Level.WARNING, "cheat client " + x.name + " failed", failure);
                x.gone = true;
            }
        }
        try {
            this.probe.run();
        } catch (final RuntimeException failure) {
            this.tests.getLogger().log(Level.WARNING, "cheat probe failed", failure);
        }
    }

    private void summary() {
        if (this.driver != null) {
            this.driver.cancel();
        }
        this.leaveAll();
        HandlerList.unregisterAll(this.listener);
        final net.minecraft.world.scores.PlayerTeam team = this.server.getScoreboard().getPlayerTeam(TEAM);
        if (team != null) {
            this.server.getScoreboard().removePlayerTeam(team);
        }
        this.tests.getLogger().info("REVIVAL_CHEAT ===== cheat suite: " + this.table.size() + " scenarios, " + this.failures.size() + " leaked =====");
        for (final String line : this.table) {
            this.tests.getLogger().info("REVIVAL_CHEAT " + line);
        }
        final List<String> names = new ArrayList<>();
        for (final String line : this.failures) {
            names.add(line.substring(0, line.indexOf(" | ")));
        }
        this.tests.check(this.failures.isEmpty(), "cheat suite: " + (this.table.size() - this.failures.size()) + " of " + this.table.size()
            + " cheats blocked; leaked: " + String.join("; ", names));
    }

    private String next(final String prefix) {
        return prefix + (++this.serial);
    }

    private void step(final int delay, final String name, final Runnable action) {
        this.tests.after(delay, name, action);
    }

    private void begin(final String id, final Runnable arena) {
        this.step(1, id + ": arena and join", () -> {
            this.open(id);
            this.probe = () -> {
            };
            this.track = new Track();
            this.resetMoves();
            this.clearArena();
            arena.run();
            this.c = new Cheater(this.next("Cheat"), this.ox + 0.5, this.gy, this.oz + 0.5, 0.0F);
        });
        this.step(WARM, id + ": under enforce", () -> this.ready(this.c));
    }

    private void ready(final Cheater x) {
        this.tests.check(x.auth.mode() == AuthMode.ENFORCE && x.auth.simulation().seeded() && !x.kicked && x.auth.stats().simulated() > 0,
            this.scenario + ": " + x.name + " runs under enforce (mode " + x.auth.mode() + ", seeded " + x.auth.simulation().seeded() + ", simulated "
                + x.auth.stats().simulated() + ", exact " + x.auth.stats().exact() + "/" + x.auth.stats().compared() + ")");
    }

    private void arm(final String id, final Runnable arm) {
        this.step(1, id + ": cheat", () -> {
            this.base = this.snap(this.c);
            this.track = new Track();
            this.resetMoves();
            this.probe = () -> this.track.sample(this.c);
            this.armTicks = this.c.ticks;
            arm.run();
        });
    }

    private void judge(final int delay, final String id, final Runnable judge) {
        this.step(delay, id + ": judge", judge);
    }

    private void honestAfter(final String id, final Input keys, final int drainTicks) {
        this.step(1, id + ": honest again", () -> {
            this.probe = () -> {
            };
            this.honestBase = null;
            if (this.c.kicked) {
                this.note("kicked, no honest phase");
                return;
            }
            this.c.honest(keys);
            if (drainTicks > 0) {
                this.c.rate = 0.0; // a client that stops ticking until its backlog ran
            }
        });
        if (drainTicks > 0) {
            this.step(drainTicks, id + ": backlog ran", () -> this.c.rate = 20.0);
        }
        this.step(15, id + ": honest settles", () -> this.honestBase = this.c.kicked ? null : this.snap(this.c));
        this.step(12, id + ": honest unaffected", () -> {
            if (this.honestBase == null || this.c.kicked) {
                return;
            }
            final Snap d = this.snap(this.c).minus(this.honestBase);
            this.honestExpect(d.corrections() == 0 && d.compared() >= 8,
                "honest again: " + d.corrections() + " corrections, " + d.exact() + "/" + d.compared() + " claims exact");
        });
    }

    private void finish(final String id) {
        this.step(1, id + ": verdict", () -> {
            this.record();
            this.leaveAll();
        });
    }

    private void record() {
        final boolean blocked = this.broken.isEmpty();
        String outcome = blocked ? (this.label.isEmpty() ? "BLOCKED" : this.label) : "LEAKED";
        if (!this.honestBroken.isEmpty()) {
            outcome += " (honest client affected)";
        }
        final String line = this.scenario + " | " + outcome + " | " + this.evidence;
        this.table.add(line);
        if (blocked && this.honestBroken.isEmpty()) {
            this.tests.check(true, "cheat " + line);
        } else {
            this.failures.add(line);
            this.tests.getLogger().warning("REVIVAL_CHEAT_LEAK " + line);
        }
        this.open(this.scenario);
    }

    private void leaveAll() {
        this.probe = () -> {
        };
        if (this.c != null) {
            this.c.leave();
        }
        if (this.v != null) {
            this.v.leave();
        }
        this.c = null;
        this.v = null;
        this.protectedBlocks.clear();
        this.deniedPlacements.clear();
    }

    private void expect(final boolean ok, final String what) {
        this.evidence.append(ok ? "" : "FAILED: ").append(what).append("; ");
        if (!ok) {
            this.broken.add(what);
        }
    }

    private void honestExpect(final boolean ok, final String what) {
        this.evidence.append(ok ? "" : "FAILED: ").append(what).append("; ");
        if (!ok) {
            this.honestBroken.add(what);
        }
    }

    private void note(final String what) {
        this.evidence.append(what).append("; ");
    }

    private void expectTruthIs(final Vec3 ref, final double[] start, final int n) {
        final Simulation s = this.c.auth.simulation();
        final double gap = Math.sqrt((s.x() - ref.x) * (s.x() - ref.x) + (s.y() - ref.y) * (s.y() - ref.y) + (s.z() - ref.z) * (s.z() - ref.z));
        this.expect(gap <= EPS, "truth is the honest path after " + n + " ticks (moved " + f(s.z() - start[2]) + ", reference " + f(ref.z - start[2])
            + ", gap " + f(gap) + ")");
    }

    private void resetMoves() {
        this.moveMaxY = Double.NEGATIVE_INFINITY;
        this.moveMinY = Double.POSITIVE_INFINITY;
        this.moveMaxZ = Double.NEGATIVE_INFINITY;
    }

    private Snap snap(final Cheater x) {
        final AuthStats s = x.auth.stats();
        return new Snap(s.windows(), x.auth.stream().closedTicks(), s.compared(), s.exact(), s.corrections(), s.correctionsAccepted(), s.violations(),
            x.auth.stream().violations(), x.auth.transactions().violationCount(), x.auth.transactions().duplicateCount(), s.forcedEntries(),
            s.flagMismatches(), s.teleportAcceptMismatches(), x.auth.clock().refused(), s.exempt(AuthStats.EXEMPT_REFUSED), s.attacksAccepted(),
            s.attacksRejected(), s.attacksOverCap(), System.nanoTime());
    }

    private Snap delta(final Cheater x) {
        return this.snap(x).minus(this.base);
    }

    private double[] state(final Cheater x) {
        final Simulation s = x.auth.simulation();
        return new double[] {s.x(), s.y(), s.z(), s.vx(), s.vy(), s.vz()};
    }

    // AuthPlayer's: ceil(min(rttBase, rtt-ceiling-ms) / 50) + ack-jitter-ticks
    private int deadline(final Cheater x) {
        final long rtt = Math.min(x.auth.transactions().rttBaseNanos(), Revival.settings().authoritative().rttCeilingMs() * 1_000_000L);
        return (int) Math.ceil(rtt / 50_000_000.0) + Revival.settings().authoritative().ackJitterTicks();
    }

    /** An honest client's path from {@code start}: the port with a fresh client view, {@code n} ticks of {@code keys}, yaw 0. */
    private Vec3 reference(final Cheater x, final double[] start, final Input keys, final int n, final boolean use) {
        final PortSimulation ref = new PortSimulation(x.player);
        final ClientView view = new ClientView();
        view.seed(x.player);
        final WorldView world = new WorldView();
        world.setLevel(this.level, false);
        ref.seed(start[0], start[1], start[2], start[3], start[4], start[5], 0.0F, 0.0F, true, view, world);
        if (use) {
            ref.startUsingItem(InteractionHand.MAIN_HAND, view); // the hand as the view holds it (cheat suite 2 H2)
        }
        Input previous = NONE;
        for (int i = 0; i < n; i++) {
            ref.tick(keys, previous, 0.0F, 0.0F, ClientStream.SPRINT_NONE, -1, false, view, world);
            previous = keys;
        }
        return new Vec3(ref.x(), ref.y(), ref.z());
    }

    private void knock(final Cheater x, final double vx, final double vy, final double vz) {
        x.player.connection.send(new ClientboundSetEntityMotionPacket(x.player.getId(), new Vec3(vx, vy, vz)));
    }

    private void give(final Cheater x, final ItemStack stack) {
        x.player.getInventory().setItem(x.player.getInventory().getSelectedSlot(), stack);
        x.player.inventoryMenu.broadcastChanges();
    }

    // Arena ------------------------------------------------------------------------------------------------------------

    private void clearArena() {
        for (int x = this.ox - 4; x <= this.ox + 4; x++) {
            for (int z = this.oz - 4; z <= this.oz + 24; z++) {
                for (int y = this.gy - 3; y <= this.gy + 6; y++) {
                    this.set(x, y, z, y < this.gy ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private void set(final int x, final int y, final int z, final BlockState state) {
        final BlockPos pos = new BlockPos(x, y, z);
        if (this.level.getBlockState(pos) != state) {
            this.level.setBlock(pos, state, 2 | 16);
        }
    }

    private void wall(final int dz, final int height, final BlockState state) {
        for (int x = this.ox - 2; x <= this.ox + 2; x++) {
            for (int y = 0; y < height; y++) {
                this.set(x, this.gy + y, this.oz + dz, state);
            }
        }
    }

    // A wall right in front of the attacker (three wide), 0 high removes it
    private void wallAt(final int dz, final int height) {
        for (int x = this.ox - 1; x <= this.ox + 1; x++) {
            for (int y = 0; y < 3; y++) {
                this.set(x, this.gy + y, this.oz + dz, y < height ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }

    private void door(final int dz) {
        final BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH).setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        this.set(this.ox, this.gy, this.oz + dz, lower);
        this.set(this.ox, this.gy + 1, this.oz + dz, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private void gate(final int dz) {
        final BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH).setValue(FenceGateBlock.OPEN, false);
        for (int x = this.ox - 2; x <= this.ox + 2; x++) {
            this.set(x, this.gy, this.oz + dz, x == this.ox ? gate : Blocks.STONE.defaultBlockState());
            this.set(x, this.gy + 1, this.oz + dz, x == this.ox ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
        }
    }

    /** The z of the near face of the collision shape at (lane, ground, oz + dz). */
    private double face(final int dz) {
        final BlockPos pos = new BlockPos(this.ox, this.gy, this.oz + dz);
        return pos.getZ() + this.level.getBlockState(pos).getCollisionShape(this.level, pos).min(Direction.Axis.Z);
    }

    // Helpers ----------------------------------------------------------------------------------------------------------

    private static boolean finite(final Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static boolean finite(final PositionMoveRotation change) {
        return finite(change.position()) && finite(change.deltaMovement()) && Float.isFinite(change.yRot()) && Float.isFinite(change.xRot());
    }

    private static String f(final double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        if (value != 0.0 && Math.abs(value) < 1.0E-3) {
            return String.format(Locale.ROOT, "%.3e", value);
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }

    // ClientStream's open window (private): how many packets a client that never ends a tick has buffered
    private static int openWindowItems(final ClientStream stream) {
        try {
            final java.lang.reflect.Field current = ClientStream.class.getDeclaredField("current");
            current.setAccessible(true);
            final Object window = current.get(stream);
            final java.lang.reflect.Field items = window.getClass().getDeclaredField("items");
            items.setAccessible(true);
            return items.getInt(window);
        } catch (final ReflectiveOperationException failure) {
            return -1;
        }
    }

    /** Counters of one cheater at one moment; {@link #minus} gives what a phase added. */
    private record Snap(long windows, long closed, long compared, long exact, long corrections, long accepted, long violations, long streamViolations,
                        long txViolations, long duplicates, long forced, long flagMismatches, long teleportMismatches, long refused, long exemptRefused,
                        long attacksAccepted, long attacksRejected, long overCap, long nanos) {

        static final Snap ZERO = new Snap(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        Snap minus(final Snap o) {
            return new Snap(this.windows - o.windows, this.closed - o.closed, this.compared - o.compared, this.exact - o.exact,
                this.corrections - o.corrections, this.accepted - o.accepted, this.violations - o.violations, this.streamViolations - o.streamViolations,
                this.txViolations - o.txViolations, this.duplicates - o.duplicates, this.forced - o.forced, this.flagMismatches - o.flagMismatches,
                this.teleportMismatches - o.teleportMismatches, this.refused - o.refused, this.exemptRefused - o.exemptRefused,
                this.attacksAccepted - o.attacksAccepted, this.attacksRejected - o.attacksRejected, this.overCap - o.overCap, this.nanos - o.nanos);
        }
    }

    /** Extremes of truth, ServerPlayer and claim over a phase, sampled once per server tick after the client ticked. */
    private final class Track {
        double truthMaxY = Double.NEGATIVE_INFINITY;
        double truthMinY = Double.POSITIVE_INFINITY;
        double truthMaxZ = Double.NEGATIVE_INFINITY;
        double bodyMaxY = Double.NEGATIVE_INFINITY;
        double bodyMinY = Double.POSITIVE_INFINITY;
        double bodyMaxZ = Double.NEGATIVE_INFINITY;
        double claimMaxY = Double.NEGATIVE_INFINITY;
        double claimMinY = Double.POSITIVE_INFINITY;
        double claimMaxZ = Double.NEGATIVE_INFINITY;
        int highClaims;
        double claimLead = Double.NEGATIVE_INFINITY;
        double prevZ = Double.NaN;
        double lastDz;
        long lastWindows = -1;
        long maxPerTick;

        void sample(final Cheater x) {
            final Simulation s = x.auth.simulation();
            this.truthMaxY = Math.max(this.truthMaxY, s.y());
            this.truthMinY = Math.min(this.truthMinY, s.y());
            this.truthMaxZ = Math.max(this.truthMaxZ, s.z());
            this.bodyMaxY = Math.max(this.bodyMaxY, x.player.getY());
            this.bodyMinY = Math.min(this.bodyMinY, x.player.getY());
            this.bodyMaxZ = Math.max(this.bodyMaxZ, x.player.getZ());
            this.claimMaxY = Math.max(this.claimMaxY, x.claimY);
            this.claimMinY = Math.min(this.claimMinY, x.claimY);
            this.claimMaxZ = Math.max(this.claimMaxZ, x.claimZ);
            this.claimLead = Math.max(this.claimLead, x.claimZ - s.z());
            if (x.claimY >= AuthCheatScenarios.this.gy + 1.0) {
                this.highClaims++;
            }
            if (!Double.isNaN(this.prevZ)) {
                this.lastDz = s.z() - this.prevZ;
            }
            this.prevZ = s.z();
            final long windows = x.auth.stats().windows();
            if (this.lastWindows >= 0) {
                this.maxPerTick = Math.max(this.maxPerTick, windows - this.lastWindows);
            }
            this.lastWindows = windows;
        }
    }

    // =================================================================================================================
    // The modified client
    // =================================================================================================================

    /**
     * A modified 26.3 client on a {@link FakeClient} connection. Each client tick: what the server wrote is read in order
     * and handled as a client would at frame start (pings are answered when they are released, which a lag policy can
     * delay or hold), the keybind actions are sent with the client's own predictions applied to its movement model, the
     * model ticks, a cheat may edit its state, and {@code sendChanges} goes out as {@code LocalPlayer} would send it,
     * unless the scenario rearranges the window.
     */
    private final class Cheater {
        final String name;
        final FakeClient fake;
        final ServerPlayer player;
        final AuthPlayer auth;
        final ClientView view = new ClientView();
        final WorldView world = new WorldView();
        final PortSimulation model;
        private boolean seeded;
        private PositionMoveRotation joined = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F);
        private int rawSeen;
        private final ArrayDeque<Object[]> inbox = new ArrayDeque<>();
        private int lastRelease;
        final List<Packet<? super ServerGamePacketListener>> drained = new ArrayList<>();
        final List<ClientboundPlayerPositionPacket> positions = new ArrayList<>();
        int ackedSequence;
        int lastPingId;
        int lastPongId;

        // Lag and reply policies
        int pongDelay;
        /** Every clientbound packet reaches this client this many ticks late (a long route, not a cheat). */
        int latencyTicks;
        /** Other players' movement reaches this client's view this many ticks late, its pongs on time (fake lag). */
        int staleEntityTicks;
        /** A network stall: nothing comes in until it ends (then everything at once); with {@code blink}, nothing goes out. */
        boolean stall;
        boolean holdPongs;
        boolean outOfOrder;
        boolean futurePong;
        boolean duplicatePong;
        private final ArrayDeque<Integer> pings = new ArrayDeque<>();
        boolean ignoreMotion;
        boolean ignoreBlockUpdates;
        boolean ownBlocks; // the cheat edited its own world: an honest client starts from the server's again
        // An honest client's own breaks (MultiPlayerGameMode.destroyBlock under BlockStatePredictionHandler): the block goes
        // at once, server updates for it are stored, and the BlockChangedAck puts the stored state back, snapping the
        // player to where it broke it if it is inside and no teleport came since (ClientLevel.syncBlockState)
        boolean predictBreaks;
        int sequence;
        private int teleportSequence = -1;
        private final Map<Long, Object[]> retained = new HashMap<>();
        int predictedBreaks;
        int restoreSnaps;
        boolean acceptServerTeleports = true;
        @Nullable Vec3 acceptOffset;
        boolean acceptNaN;
        boolean acceptCorrectionNaN;

        // The next ticks
        Input keys = NONE;
        @Nullable Input modelKeys;
        private Input previousModelKeys = NONE;
        float yaw;
        float pitch;
        final List<Packet<? super ServerGamePacketListener>> preDrain = new ArrayList<>();
        final List<Packet<? super ServerGamePacketListener>> actions = new ArrayList<>();
        boolean drainAfterActions;
        @Nullable Consumer<Cheater> before;
        @Nullable Consumer<Cheater> cheat;
        @Nullable Boolean claimOnGround;
        boolean forcePosition;
        @Nullable Packet<? super ServerGamePacketListener> moveOverride;
        final List<Packet<? super ServerGamePacketListener>> extraMoves = new ArrayList<>();
        boolean noTickEnd;
        boolean noUsePrediction;
        /** KeepSprint: the client skips its own x0.6 and sprint stop after a sprint hit (Player.attack). */
        boolean noAttackPrediction;
        /** The block glitch: the client keeps the blocks it predicted placing when the server's ack says otherwise. */
        boolean keepGhostBlocks;
        /** Never acks a chunk batch ({@code ChunkBatchReceived}), so the server stops sending it chunks. */
        boolean noChunkAcks;
        /** Never reports {@code PlayerLoaded}: the server's client-load timeout decides it loaded instead. */
        boolean neverLoads;
        /** The last knock ({@code SetEntityMotion} for itself), decoded. */
        @Nullable Vec3 lastKnock;
        boolean suppressSprintStop;
        /** {@code BlockEvent}s handled (a piston moving, a shulker box opening). */
        int blockEvents;
        /** Knocks ({@code SetEntityMotion} for itself) handled. */
        int knocksSeen;
        /** A sprint command sent this tick in the sprint slot whatever the model does (a spoofed one), then cleared. */
        ServerboundPlayerCommandPacket.@Nullable Action injectSprint;
        boolean blink;
        int blinkTicks;
        boolean holdAfterDrain;
        private final List<Packet<? super ServerGamePacketListener>> held = new ArrayList<>();
        double rate = 20.0;
        private double credit;

        // LocalPlayer.sendChanges bookkeeping
        private Input lastInput = NONE;
        private boolean wasSprinting;
        private Vec3 lastSentPos = Vec3.ZERO;
        private float lastYaw = Float.NaN;
        private float lastPitch = Float.NaN;
        private boolean lastOnGround;
        private boolean lastCollision;
        private int reminder;
        private boolean loaded;
        /**
         * Reports itself loaded once the server has sent its chunk, before it arrives, and stands on the server's floor (the
         * load-test bot's timing). Before the chunk is sent a client can't have it, and a vanilla one stays on its loading
         * screen: no physics, no moves the server takes.
         */
        boolean loadEarly;

        int ticks;
        boolean kicked;
        boolean gone;
        int serverErrors;
        String lastServerError = "";
        int sprintSyncs;
        double claimX;
        double claimY;
        double claimZ;

        private final ClientView.EchoSink echo = new ClientView.EchoSink() {
            @Override
            public void sharedFlags(final byte flags) {
                Cheater.this.model.echoSharedFlags(flags);
            }

            @Override
            public void pose(final Pose pose) {
                Cheater.this.model.echoPose(pose);
            }

            @Override
            public void usingItem(final boolean using, final InteractionHand hand) {
                Cheater.this.model.echoUsingItem(using, hand);
            }
        };

        Cheater(final String name, final double x, final double y, final double z, final float yaw) {
            this.name = name;
            this.fake = new FakeClient(AuthCheatScenarios.this.server, AuthCheatScenarios.this.level, name);
            this.player = this.fake.player;
            this.auth = this.player.revivalAuth;
            final net.minecraft.server.ServerScoreboard scoreboard = AuthCheatScenarios.this.server.getScoreboard();
            scoreboard.addPlayerToTeam(this.player.getScoreboardName(), scoreboard.getPlayerTeam(TEAM));
            this.auth.setOverride(AuthMode.ENFORCE);
            this.player.connection.teleport(x, y, z, yaw, 0.0F);
            this.view.seed(this.player);
            this.world.setLevel(AuthCheatScenarios.this.level, false);
            this.model = new PortSimulation(this.player);
            this.yaw = yaw;
            AuthCheatScenarios.this.clients.add(this);
        }

        /** One server tick: as many client ticks as the tick rate gives, else a frame that only handles packets. */
        void step() {
            if (this.gone || this.kicked) {
                return;
            }
            this.credit += this.rate / 20.0;
            final int n = (int) this.credit;
            this.credit -= n;
            if (n == 0) {
                this.frame();
                return;
            }
            for (int i = 0; i < n && !this.kicked; i++) {
                this.clientTick();
            }
        }

        // A frame without a tick: packets are handled and pings answered at once
        void frame() {
            this.ingest();
            this.release();
            final List<Packet<? super ServerGamePacketListener>> out = new ArrayList<>(this.preDrain);
            out.addAll(this.drained);
            this.preDrain.clear();
            this.drained.clear();
            if (this.blink) {
                this.held.addAll(out);
            } else {
                this.send(out);
            }
        }

        void clientTick() {
            if (this.gone || this.kicked) {
                return;
            }
            this.ingest();
            this.release();
            if (this.before != null) {
                this.before.accept(this);
            }
            this.pongGames();
            if (!this.seeded) {
                final Vec3 at = this.joined.position();
                this.model.seed(at.x, at.y, at.z, 0.0, 0.0, 0.0, this.yaw, this.pitch, true, this.view, this.world);
                this.seeded = true;
            }
            final List<Packet<? super ServerGamePacketListener>> out = new ArrayList<>(this.preDrain);
            this.preDrain.clear();
            if (!this.drainAfterActions) {
                out.addAll(this.drained);
                this.drained.clear();
            }
            final int drainEnd = out.size();
            if (!this.loaded && !this.neverLoads && (this.loadEarly && this.chunkSent() || this.fake.chunks.contains(net.minecraft.world.level.ChunkPos.pack(net.minecraft.util.Mth.floor(this.model.x()) >> 4,
                net.minecraft.util.Mth.floor(this.model.z()) >> 4)))) {
                // A vanilla client reports PlayerLoaded once its loading screen has the chunk it stands in
                out.add(new ServerboundPlayerLoadedPacket());
                this.loaded = true;
            }
            for (final Packet<? super ServerGamePacketListener> action : this.actions) {
                this.predict(action);
                out.add(action);
            }
            this.actions.clear();
            if (this.drainAfterActions) {
                out.addAll(this.drained);
                this.drained.clear();
                this.drainAfterActions = false;
            }

            final Input moving = this.modelKeys != null ? this.modelKeys : this.keys;
            if (this.loaded) {
                // LocalPlayer.tick does nothing until the client has loaded
                this.model.tick(moving, this.previousModelKeys, this.yaw, this.pitch, ClientStream.SPRINT_NONE, -1, false, this.view, this.world);
            }
            this.previousModelKeys = moving;
            this.view.tick();
            if (this.cheat != null) {
                this.cheat.accept(this);
            }

            if (!this.keys.equals(this.lastInput)) {
                out.add(new ServerboundPlayerInputPacket(this.keys));
                this.lastInput = this.keys;
            }
            final boolean sprinting = this.model.sprinting();
            if (sprinting != this.wasSprinting) {
                if (sprinting || !this.suppressSprintStop) {
                    out.add(new ServerboundPlayerCommandPacket(this.player, sprinting
                        ? ServerboundPlayerCommandPacket.Action.START_SPRINTING : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
                }
                this.wasSprinting = sprinting;
            }
            if (this.injectSprint != null) {
                out.add(new ServerboundPlayerCommandPacket(this.player, this.injectSprint));
                this.injectSprint = null;
            }
            this.claimX = this.model.x();
            this.claimY = this.model.y();
            this.claimZ = this.model.z();
            final boolean onGround = this.claimOnGround != null ? this.claimOnGround : this.model.onGround();
            final boolean collision = this.model.horizontalCollision();
            final Packet<? super ServerGamePacketListener> move;
            if (this.moveOverride != null) {
                move = this.moveOverride;
                this.moveOverride = null;
            } else {
                move = this.sendPosition(new Vec3(this.claimX, this.claimY, this.claimZ), onGround, collision);
            }
            if (move != null) {
                out.add(move);
            }
            out.addAll(this.extraMoves);
            this.extraMoves.clear();
            if (!this.noTickEnd) {
                out.add(ServerboundClientTickEndPacket.INSTANCE);
            }
            this.ticks++;

            if (this.holdAfterDrain) {
                // The drain goes out now; everything after it waits (a client that stalls right after replying)
                this.holdAfterDrain = false;
                this.send(new ArrayList<>(out.subList(0, drainEnd)));
                this.held.addAll(out.subList(drainEnd, out.size()));
                this.blink = true;
                return;
            }
            if (this.blink) {
                this.held.addAll(out);
                if (this.blinkTicks > 0 && --this.blinkTicks == 0) {
                    this.flush();
                }
                return;
            }
            this.send(out);
        }

        // LocalPlayer.sendPosition (26.3)
        private @Nullable Packet<? super ServerGamePacketListener> sendPosition(final Vec3 position, final boolean onGround, final boolean collision) {
            this.reminder++;
            final boolean move = this.forcePosition || position.distanceToSqr(this.lastSentPos) > 4.0E-8 || this.reminder >= 20;
            final boolean rot = this.yaw != this.lastYaw || this.pitch != this.lastPitch;
            Packet<? super ServerGamePacketListener> packet = null;
            if (move && rot) {
                packet = new ServerboundMovePlayerPacket.PosRot(position, this.yaw, this.pitch, onGround, collision);
            } else if (move) {
                packet = new ServerboundMovePlayerPacket.Pos(position, onGround, collision);
            } else if (rot) {
                packet = new ServerboundMovePlayerPacket.Rot(this.yaw, this.pitch, onGround, collision);
            } else if (onGround != this.lastOnGround || collision != this.lastCollision) {
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
            this.lastCollision = collision;
            return packet;
        }

        // The client's own effects of its keybind actions
        private void predict(final Packet<? super ServerGamePacketListener> action) {
            switch (action) {
                case final ServerboundAttackPacket ignored -> {
                    if (!this.noAttackPrediction) {
                        this.model.clientAttack(true, this.view);
                    }
                }
                case final ServerboundUseItemPacket use -> {
                    if (!this.noUsePrediction && Boolean.TRUE.equals(this.view.predictsUse(this.view.items.hand(use.hand())))) {
                        this.model.startUsingItem(use.hand(), this.view);
                    }
                }
                case final ServerboundPlayerActionPacket playerAction when playerAction.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM ->
                    this.model.releaseUsingItem();
                case final ServerboundPlayerActionPacket destroy when this.predictBreaks
                    && (destroy.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && destroy.getSequence() > 0
                    || destroy.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) -> this.predictBreak(destroy.getPos(), destroy.getSequence());
                case final ServerboundSetCarriedItemPacket carried -> this.view.items.select(carried.getSlot());
                default -> {
                }
            }
        }

        // destroyBlock inside startPredicting: retainKnownServerState (the first state and player position stay), then the
        // block's fluid, or air
        // Whether the server has sent the chunk the model stands in (Moonrise's per-player set; main thread, as the suite runs)
        private boolean chunkSent() {
            final ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
                this.player.moonrise$getChunkLoader();
            return loader != null && loader.getSentChunksRaw().contains(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(
                net.minecraft.util.Mth.floor(this.model.x()) >> 4, net.minecraft.util.Mth.floor(this.model.z()) >> 4));
        }

        // MultiPlayerGameMode.useItemOn under BlockStatePredictionHandler: the client places at once and keeps the server's
        // updates for that position until the ack puts them back
        void predictPlace(final BlockPos pos, final BlockState state, final int sequence) {
            final Object[] kept = this.retained.get(pos.asLong());
            if (kept != null) {
                kept[0] = sequence;
            } else {
                this.retained.put(pos.asLong(), new Object[] {sequence, this.world.blockState(pos), new Vec3(this.model.x(), this.model.y(), this.model.z())});
            }
            this.world.onBlockUpdate(pos, state);
        }

        private void predictBreak(final BlockPos pos, final int sequence) {
            final BlockState state = this.world.blockState(pos);
            if (state.isAir()) {
                return;
            }
            final Object[] kept = this.retained.get(pos.asLong());
            if (kept != null) {
                kept[0] = sequence;
            } else {
                this.retained.put(pos.asLong(), new Object[] {sequence, state, new Vec3(this.model.x(), this.model.y(), this.model.z())});
            }
            this.world.onBlockUpdate(pos, state.getFluidState().createLegacyBlock());
            this.predictedBreaks++;
        }

        // ClientLevel.setServerVerifiedBlockState: a position this client predicted keeps its update for the ack
        private void serverBlock(final BlockPos pos, final BlockState state) {
            final Object[] kept = this.retained.get(pos.asLong());
            if (kept != null) {
                kept[1] = state;
            } else if (!this.ignoreBlockUpdates) {
                this.world.onBlockUpdate(pos, state);
            }
        }

        // BlockStatePredictionHandler.endPredictionsUpTo -> ClientLevel.syncBlockState
        private void endPredictions(final int ackSequence) {
            final java.util.Iterator<Map.Entry<Long, Object[]>> iterator = this.retained.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<Long, Object[]> entry = iterator.next();
                if ((int) entry.getValue()[0] > ackSequence) {
                    continue;
                }
                iterator.remove();
                final BlockPos pos = BlockPos.of(entry.getKey());
                final BlockState state = (BlockState) entry.getValue()[1];
                if (this.world.blockState(pos) == state || this.keepGhostBlocks) {
                    continue;
                }
                this.world.onBlockUpdate(pos, state);
                final Vec3 at = (Vec3) entry.getValue()[2];
                final boolean colliding = this.seeded && this.colliding(pos, state);
                if (this.teleportSequence < ackSequence && colliding) {
                    this.model.snapTo(at.x, at.y, at.z);
                    this.restoreSnaps++;
                }
            }
        }

        // Entity.isColliding: the block's collision shape overlaps the player's box
        private boolean colliding(final BlockPos pos, final BlockState state) {
            final net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(this.player.level(), pos,
                net.minecraft.world.phys.shapes.CollisionContext.empty());
            if (shape.isEmpty()) {
                return false;
            }
            final net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(this.model.x() - 0.3, this.model.y(), this.model.z() - 0.3,
                this.model.x() + 0.3, this.model.y() + 1.8, this.model.z() + 0.3);
            for (final net.minecraft.world.phys.AABB part : shape.move(pos).toAabbs()) {
                if (part.intersects(box)) {
                    return true;
                }
            }
            return false;
        }

        // Pong tricks that need the ids the server sent
        private void pongGames() {
            if (this.outOfOrder && this.pings.size() >= 2) {
                this.outOfOrder = false;
                final int first = this.pings.poll();
                final int second = this.pings.poll();
                this.preDrain.add(new ServerboundPongPacket(second));
                this.preDrain.add(new ServerboundPongPacket(first));
                while (!this.pings.isEmpty()) {
                    this.preDrain.add(new ServerboundPongPacket(this.pings.poll()));
                }
            }
            if (this.futurePong && this.lastPingId != 0) {
                this.futurePong = false;
                this.preDrain.add(new ServerboundPongPacket(this.lastPingId - 5 >= Transactions.PING_MIN ? this.lastPingId - 5 : Transactions.PING_MAX));
            }
            if (this.duplicatePong && this.lastPongId != 0) {
                this.duplicatePong = false;
                this.preDrain.add(new ServerboundPongPacket(this.lastPongId));
            }
        }

        private void ingest() {
            final List<Packet<?>> raw = this.fake.raw;
            final int size = raw.size();
            for (; this.rawSeen < size; this.rawSeen++) {
                final Packet<?> packet = raw.get(this.rawSeen);
                if (packet instanceof final BundlePacket<?> bundle) {
                    for (final Packet<?> sub : bundle.subPackets()) {
                        this.enqueue(sub);
                    }
                } else {
                    this.enqueue(packet);
                }
            }
        }

        private void enqueue(final Packet<?> packet) {
            // Netty-thread replies on a real client: never lagged by the game thread
            if (packet instanceof final ClientboundKeepAlivePacket keepAlive) {
                if (!this.kicked) {
                    this.fake.deliver(new ServerboundKeepAlivePacket(keepAlive.getId()));
                }
                return;
            }
            if (packet instanceof ClientboundChunkBatchFinishedPacket) {
                if (!this.kicked && !this.noChunkAcks) {
                    this.fake.deliver(new ServerboundChunkBatchReceivedPacket(20.0F));
                }
                return;
            }
            if (this.staleEntityTicks > 0 && (packet instanceof net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket)) {
                this.staleInbox.addLast(new Object[] {packet, this.ticks + this.staleEntityTicks});
                return;
            }
            int release = this.ticks + this.latencyTicks;
            if (packet instanceof ClientboundPingPacket) {
                release = this.holdPongs ? Integer.MAX_VALUE : this.ticks + this.latencyTicks + this.pongDelay;
            }
            if (this.stall) {
                release = Integer.MAX_VALUE;
            }
            release = Math.max(release, this.lastRelease);
            this.lastRelease = release;
            this.inbox.addLast(new Object[] {packet, release});
        }

        private final ArrayDeque<Object[]> staleInbox = new ArrayDeque<>();

        private void release() {
            while (!this.inbox.isEmpty() && (int) this.inbox.peekFirst()[1] <= this.ticks) {
                this.handle((Packet<?>) this.inbox.pollFirst()[0]);
            }
            while (!this.staleInbox.isEmpty() && (int) this.staleInbox.peekFirst()[1] <= this.ticks) {
                this.handle((Packet<?>) this.staleInbox.pollFirst()[0]);
            }
        }

        /** Everything held is handled at the next drain, at once. */
        void releaseHeld() {
            this.holdPongs = false;
            this.stall = false;
            for (final Object[] entry : this.inbox) {
                if ((int) entry[1] > this.ticks) {
                    entry[1] = this.ticks;
                }
            }
            this.lastRelease = this.ticks;
        }

        void flush() {
            this.blink = false;
            this.blinkTicks = 0;
            final List<Packet<? super ServerGamePacketListener>> out = new ArrayList<>(this.held);
            this.held.clear();
            this.send(out);
        }

        private void send(final List<Packet<? super ServerGamePacketListener>> packets) {
            for (final Packet<? super ServerGamePacketListener> packet : packets) {
                if (this.kicked || this.gone) {
                    return;
                }
                if (packet instanceof final ServerboundPongPacket pong) {
                    this.lastPongId = pong.getId();
                }
                try {
                    this.fake.deliver(packet);
                } catch (final RuntimeException failure) {
                    // On a real connection this surfaces as a packet-handling error; the cheater keeps going
                    this.serverErrors++;
                    this.lastServerError = failure.toString();
                }
            }
        }

        // ClientPacketListener, for what moves this client
        private void handle(final Packet<?> packet) {
            final int self = this.player.getId();
            switch (packet) {
                case final ClientboundPingPacket ping -> {
                    this.lastPingId = ping.getId();
                    if (this.outOfOrder) {
                        this.pings.add(ping.getId());
                    } else {
                        this.drained.add(new ServerboundPongPacket(ping.getId()));
                    }
                }
                case final ClientboundPlayerPositionPacket position -> this.teleport(position);
                case final ClientboundSetEntityMotionPacket motion when motion.id() == self -> {
                    this.knocksSeen++;
                    final double[] decoded = new double[3];
                    Wire.lpVec3(motion.movement().x, motion.movement().y, motion.movement().z, decoded);
                    this.lastKnock = new Vec3(decoded[0], decoded[1], decoded[2]);
                    if (!this.ignoreMotion && this.seeded) {
                        this.model.setVelocity(decoded[0], decoded[1], decoded[2]);
                    }
                }
                case final ClientboundExplodePacket explode -> {
                    if (this.seeded && !this.ignoreMotion) {
                        explode.playerKnockback().ifPresent(k -> this.model.addVelocity(k.x, k.y, k.z));
                    }
                }
                case final ClientboundUpdateAttributesPacket attributes when attributes.getEntityId() == self -> {
                    for (final ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : attributes.getValues()) {
                        for (final net.minecraft.world.entity.ai.attributes.AttributeModifier modifier : snapshot.modifiers()) {
                            if (modifier.id().equals(gg.hcfrevival.revival.auth.view.ClientAttributes.SPRINTING_MODIFIER_ID)) {
                                this.sprintSyncs++;
                            }
                        }
                    }
                    this.view.attributes.apply(attributes.getValues());
                    this.model.attributes(attributes.getValues());
                }
                case final ClientboundUpdateMobEffectPacket effect when effect.getEntityId() == self -> this.view.addEffect(effect);
                case final net.minecraft.network.protocol.game.ClientboundBlockEventPacket ignored -> this.blockEvents++;
                case final ClientboundRemoveMobEffectPacket effect when effect.entityId() == self -> this.view.removeEffect(effect.effect());
                case final ClientboundPlayerAbilitiesPacket abilities -> this.view.abilities(abilities);
                case final ClientboundSetEntityDataPacket data when data.id() == self -> this.view.entityData(data.packedItems(), this.echo);
                case final ClientboundEntityEventPacket event -> {
                    if (event.getEventId() == EntityEvent.USE_ITEM_COMPLETE && event.getEntity(this.player.level()) == this.player) {
                        this.model.completeUsingItem();
                    }
                }
                case final ClientboundSetPlayerInventoryPacket slot -> this.view.items.setInventory(slot.slot(), slot.contents());
                case final ClientboundContainerSetSlotPacket slot when slot.getContainerId() == 0 -> this.view.items.setMenuSlot(slot.getSlot(), slot.getItem());
                case final ClientboundContainerSetContentPacket content when content.containerId() == 0 -> this.view.items.setMenuContent(content.items());
                case final ClientboundSetHeldSlotPacket held -> this.view.items.select(held.slot());
                case final ClientboundBlockUpdatePacket update -> this.serverBlock(update.getPos(), update.getBlockState());
                case final ClientboundSectionBlocksUpdatePacket section -> section.runUpdates(this::serverBlock);
                case final ClientboundBlockChangedAckPacket ack -> {
                    this.ackedSequence = Math.max(this.ackedSequence, ack.sequence());
                    this.endPredictions(ack.sequence());
                }
                case final ClientboundSetPassengersPacket passengers -> this.view.passengers(passengers.getVehicle(), passengers.getPassengers(), self);
                default -> {
                }
            }
        }

        // handleMovePlayer: apply, then accept with the resulting position (unless a cheat says otherwise)
        private void teleport(final ClientboundPlayerPositionPacket packet) {
            this.positions.add(packet);
            this.teleportSequence = this.sequence; // blockStatePredictionHandler.onTeleport
            final boolean finite = AuthCheatScenarios.finite(packet.change());
            if (!this.seeded) {
                this.joined = PositionMoveRotation.calculateAbsolute(this.joined, packet.change(), packet.relatives());
                final Vec3 at = this.joined.position();
                this.drained.add(new ServerboundAcceptTeleportationPacket(packet.id(), at.x, at.y, at.z, this.joined.yRot(), this.joined.xRot()));
                return;
            }
            if (Reconciler.ours(packet.id())) {
                if (finite) {
                    this.model.position(packet.change(), packet.relatives());
                }
                if (this.acceptCorrectionNaN) {
                    this.acceptCorrectionNaN = false;
                    this.drained.add(new ServerboundAcceptTeleportationPacket(packet.id(), Double.NaN, Double.NaN, Double.NaN, this.yaw, this.pitch));
                    return;
                }
                this.drained.add(new ServerboundAcceptTeleportationPacket(packet.id(), this.model.x(), this.model.y(), this.model.z(), this.model.yRot(), this.model.xRot()));
                return;
            }
            if (finite) {
                this.model.position(packet.change(), packet.relatives());
                this.yaw = this.model.yRot();
                this.pitch = this.model.xRot();
            }
            if (!this.acceptServerTeleports) {
                return;
            }
            if (this.acceptNaN) {
                this.drained.add(new ServerboundAcceptTeleportationPacket(packet.id(), Double.NaN, Double.NaN, Double.NaN, this.yaw, this.pitch));
                return;
            }
            final Vec3 offset = this.acceptOffset != null ? this.acceptOffset : Vec3.ZERO;
            this.drained.add(new ServerboundAcceptTeleportationPacket(packet.id(), this.model.x() + offset.x, this.model.y() + offset.y, this.model.z() + offset.z,
                this.model.yRot(), this.model.xRot()));
        }

        /** Back to an honest client (the cheat's leftovers are corrected by the server). */
        void honest(final Input keys) {
            this.before = null;
            this.cheat = null;
            this.modelKeys = null;
            this.keys = keys;
            this.claimOnGround = null;
            this.forcePosition = true;
            this.moveOverride = null;
            this.extraMoves.clear();
            this.ignoreMotion = false;
            this.ignoreBlockUpdates = false;
            if (this.ownBlocks) {
                this.ownBlocks = false;
                this.world.clear();
            }
            this.acceptServerTeleports = true;
            this.acceptOffset = null;
            this.acceptNaN = false;
            this.acceptCorrectionNaN = false;
            this.noUsePrediction = false;
            this.noAttackPrediction = false;
            this.noChunkAcks = false;
            if (this.keepGhostBlocks) {
                this.keepGhostBlocks = false;
                this.world.clear(); // its ghosts go: an honest client starts from the server's world again
            }
            this.suppressSprintStop = false;
            this.injectSprint = null;
            this.noTickEnd = false;
            this.outOfOrder = false;
            this.futurePong = false;
            this.duplicatePong = false;
            while (!this.pings.isEmpty()) {
                this.drained.add(new ServerboundPongPacket(this.pings.poll()));
            }
            this.rate = 20.0;
            this.credit = 0.0;
            this.pongDelay = 0;
            if (this.holdPongs) {
                this.releaseHeld();
            }
            if (this.blink) {
                this.flush();
            }
        }

        void leave() {
            if (this.gone) {
                return;
            }
            this.gone = true;
            AuthCheatScenarios.this.clients.remove(this);
            if (this.kicked || this.player.hasDisconnected()) {
                // The fake connection doesn't run vanilla's disconnection, so a kicked cheater would stay in the world
                // (and obstruct later scenarios' placements): remove it as the disconnection would
                AuthCheatScenarios.this.server.getConnection().getConnections().remove(this.player.connection.connection);
                if (!this.player.isRemoved()) {
                    AuthCheatScenarios.this.server.getPlayerList().remove(this.player);
                }
            } else {
                this.fake.disconnect();
            }
        }
    }
}
