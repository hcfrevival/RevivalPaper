package gg.hcfrevival.revival.livetest;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.api.event.AuthInputAnomalyEvent;
import gg.hcfrevival.revival.api.event.RevivalParryEvent;
import gg.hcfrevival.revival.auth.AuthMode;
import gg.hcfrevival.revival.auth.AuthPlayer;
import gg.hcfrevival.revival.auth.AuthStats;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.auth.CombatWiring;
import gg.hcfrevival.revival.auth.PortSimulation;
import gg.hcfrevival.revival.auth.Simulation;
import gg.hcfrevival.revival.auth.combat.RemoteMirror;
import gg.hcfrevival.revival.auth.net.ClientStream;
import gg.hcfrevival.revival.auth.net.Wire;
import gg.hcfrevival.revival.auth.reconcile.Reconciler;
import gg.hcfrevival.revival.auth.view.ClientProfile;
import gg.hcfrevival.revival.auth.view.ClientView;
import gg.hcfrevival.revival.auth.view.WorldView;
import gg.hcfrevival.revival.combat.NeueCombat;
import gg.hcfrevival.revival.combat.NeueState;
import gg.hcfrevival.revival.config.AuthoritativeSettings;
import gg.hcfrevival.revival.config.CombatRules;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
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
import java.util.function.IntUnaryOperator;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * The cheat suite, round 2 (docs/revival/auth-cheat-suite-2.md): modified clients against the newest mechanics, under
 * {@code authoritative.mode: enforce} on a {@code NEVER} collision team. Neue (click buffer, click cap, parry, stagger,
 * riposte, DI), K per viewer, the post-stall timer bank, selective pongs, solid entities, the teleport hold and pearls,
 * the shooter's view for rods and snowballs, and a spoofed Lunar profile.
 * <p>
 * Same method as {@link AuthCheatScenarios}: every client here is a {@link Client} that reads what the server wrote and
 * composes every serverbound packet itself, its movement the real port fed what it applied; the driver ticks every
 * client every server tick, in creation order. Each scenario asserts that the cheat gains nothing (the truth is the
 * honest one, the action is refused, corrected or counted) next to an honest control, collects its row and fails the
 * run at the end if anything leaked, so every scenario always runs. Rows print as {@code REVIVAL_CHEAT2}.
 */
final class AuthCheatScenarios2 {

    private static final Input NONE = Input.EMPTY;
    private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
    private static final Input BACKWARD = new Input(false, true, false, false, false, false, false);
    private static final Input LEFT = new Input(false, false, true, false, false, false, false);
    private static final int WARM = 15;
    private static final String TEAM = "authcheat2";
    private static final double EPS = 1.0E-9;
    /** The duel lane's gap: eye to box 2.1, a plain hit, the victim's back on its wall (NeueLiveScenarios' lane). */
    private static final double GAP = 2.4;
    private static final Identifier BRAND = Identifier.withDefaultNamespace("brand");
    private static final Identifier REGISTER = Identifier.withDefaultNamespace("register");

    private final RevivalLiveTests tests;
    private final MinecraftServer server;
    private final ServerLevel level;
    private final Listener listener = new Listener() {
    };
    private final List<Client> clients = new ArrayList<>();
    private final List<Client> cast = new ArrayList<>();
    private final List<String> table = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private @Nullable BukkitTask driver;
    private int serial;
    private int ox;
    private int gy;
    private int oz;
    private @Nullable Difficulty difficulty;
    private boolean regeneration;

    // The scenario being run
    private String scenario = "";
    private @Nullable Client c;
    private @Nullable Client h;
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
    private final List<Entity> spawned = new ArrayList<>();

    // What the server did, from its events
    private final Map<UUID, Integer> attacks = new HashMap<>();
    private final Map<UUID, Integer> parriesBy = new HashMap<>();
    private final Map<UUID, String> kicks = new HashMap<>();
    private final Map<String, Integer> anomalies = new HashMap<>();
    private final Set<UUID> cancelDamageTo = new HashSet<>();
    private final List<long[]> attackLog = new ArrayList<>(); // {attacker entity id, server tick, attacker window}
    private final List<int[]> projectileHits = new ArrayList<>(); // {shooter entity id, hit entity id}
    private long lastParryTick = Long.MIN_VALUE;
    // The last held click that landed: the distance the landing pass measured (eye to the box the attacker's client shows)
    // and the same eye to the target's truth box at that moment
    private double landingView = Double.NaN;
    private double landingTruth = Double.NaN;
    private int lastParrier;
    private int lastParryAttacker;
    private final double[] knockFrom = new double[2];
    private final double[] knockTo = new double[2];
    private boolean knockSeen;
    private int knockVictim;
    // The stagger lockout of an honest attacker, measured by the control: server ticks from the parry to its first
    // delivered attack (the timer variants are compared with it)
    private final Map<Integer, Long> lockoutHonestTicks = new HashMap<>();

    AuthCheatScenarios2(final RevivalLiveTests tests, final MinecraftServer server, final ServerLevel level) {
        this.tests = tests;
        this.server = server;
        this.level = level;
    }

    void register() {
        if (Authority.refusal() != null) {
            return; // the async-tracker run: authority refuses (AuthLiveScenarios checks that), nothing to attack
        }
        this.step(1, "cheat suite 2: setup", this::setup);
        // The rule set the netcode is proven on (LiveRules: classic-capped on the NAS, plain classic the control)
        this.step(1, "cheat suite 2: rules " + LiveRules.MAIN, () -> this.rules(LiveRules.MAIN));

        // Honest: the rotation authority starts with
        this.only("rotationAtActivation", () -> this.rotationAtActivation());
        this.only("swapAndBlockSameTick", () -> this.swapAndBlockSameTick());

        // Neue (rules neue for the block, the main rule set again after it)
        this.step(1, "cheat suite 2: rules neue", () -> this.rules("neue"));
        this.clickRows("neue");
        this.only("blockSpam", () -> this.blockSpam());
        this.only("parryWithoutPunch", () -> this.parryWithoutPunch());
        this.only("cadenceWithoutPunch", () -> this.cadenceWithoutPunch("neue"));
        this.only("recoilIgnored", () -> this.recoilIgnored());
        this.only("lockoutPongsHeld", () -> this.lockoutPongsHeld());
        this.only("lockoutAxe", () -> this.lockoutAxe());
        this.only("heldClickAcrossLockout", () -> this.heldClickAcrossLockout());
        this.only("lockoutTimer", () -> this.lockoutTimer(0, 0));
        this.only("lockoutTimer", () -> this.lockoutTimer(1, 0));
        this.only("lockoutTimer", () -> this.lockoutTimer(2, 0));
        this.only("lockoutTimer", () -> this.lockoutTimer(0, 4));
        this.only("lockoutTimer", () -> this.lockoutTimer(1, 4));
        this.only("swapSwordAndParry", () -> this.swapSwordAndParry());
        this.only("mobOnFreshBlock", () -> this.mobOnFreshBlock());
        this.only("diKeysNotMoved", () -> this.diKeysNotMoved());
        this.step(1, "cheat suite 2: rules " + LiveRules.MAIN, () -> this.rules(LiveRules.MAIN));

        // The click cap and buffer are rule-set keys (classic-capped has both); L2 is the netcode's (the cadence and the
        // input grammar), so every rule set gets it
        if (!LiveRules.MAIN.equals("neue") && LiveRules.capsAndBuffers(LiveRules.MAIN)) {
            this.clickRows(LiveRules.MAIN);
        }
        this.only("cadenceWithoutPunch", () -> this.cadenceWithoutPunch(LiveRules.MAIN));

        // Replication: K per viewer
        this.only("replication", () -> this.replication());

        // Timer bank and selective pongs
        this.only("fakeStalls", () -> this.fakeStalls());
        this.only("frozenThenBurst", () -> this.frozenThenBurst());
        this.only("skipKnockPing", () -> this.skipKnockPing());
        this.only("knockAwareLagSwitch", () -> this.knockAwareLagSwitch());

        // Solid entities
        this.only("boatMovedAway", () -> this.boatMovedAway());
        this.only("boatMovedIntoLane", () -> this.boatMovedIntoLane());
        this.only("shulkerSpawnedPongsHeld", () -> this.shulkerSpawnedPongsHeld());
        this.only("shulkerDropped", () -> this.shulkerDropped());

        // Pearls and the teleport hold
        this.only("pearl", () -> this.pearl(false));
        this.only("pearl", () -> this.pearl(true));
        this.only("teleportHoldStalled", () -> this.teleportHoldStalled());
        this.only("pearlIntoUnsentChunk", () -> this.pearlIntoUnsentChunk());

        // Projectiles: the shooter's view
        this.only("projectile", () -> this.projectile(Items.SNOWBALL, false));
        this.only("projectile", () -> this.projectile(Items.SNOWBALL, true));
        this.only("projectile", () -> this.projectile(Items.FISHING_ROD, false));
        this.only("projectile", () -> this.projectile(Items.FISHING_ROD, true));

        // Client profile
        this.only("lunarSpoof", () -> this.lunarSpoof());

        this.step(1, "cheat suite 2: summary", this::summary);
        this.step(1, "cheat suite 2: rules classic again", () -> this.rules(CombatRules.DEFAULT));
    }

    // The click-buffer and click-cap rows, on a rule set that has both
    private void clickRows(final String rules) {
        this.only("bufferControl", () -> this.bufferControl(rules));
        this.only("bufferFromOutOfReach", () -> this.bufferFromOutOfReach(rules));
        this.only("bufferTargetLeaves", () -> this.bufferTargetLeaves(rules));
        this.only("bufferTwoTargets", () -> this.bufferTwoTargets(rules));
        this.only("capControl", () -> this.capControl(rules));
        this.only("capTimer", () -> this.capTimer(rules));
        this.only("capTwoPerWindow", () -> this.capTwoPerWindow(rules));
    }

    // Iteration aids for this file only: -Drevival.cheat2.only=<method>,<method> runs those scenarios alone (the rules
    // steps always run), -Drevival.cheat2.trace=true logs every client's state each server tick. Unset in real runs
    private static final String ONLY = System.getProperty("revival.cheat2.only", "");
    private static final boolean TRACE = Boolean.getBoolean("revival.cheat2.trace");

    private void only(final String name, final Runnable scenario) {
        if (ONLY.isEmpty() || java.util.Arrays.asList(ONLY.split(",")).contains(name)) {
            scenario.run();
        }
    }

    private void trace() {
        if (!TRACE || this.cast.isEmpty()) {
            return;
        }
        final StringBuilder line = new StringBuilder("REVIVAL_CHEAT2_TRACE t").append(MinecraftServer.currentTick).append(' ');
        for (final Client x : this.cast) {
            final NeueState state = NeueCombat.state(x.player);
            line.append(x.name).append("[w").append(x.auth.tick()).append(" hp").append(x.player.getHealth()).append(" dct").append(x.player.damageCooldownTime)
                .append(" use").append(x.player.isUsingItem() ? 1 : 0).append(" blk").append(state.blocking() ? 1 : 0).append(" W").append(state.parryWindow())
                .append(" stg").append(state.staggered() ? 1 : 0).append(" pend").append(state.pending() ? 1 : 0).append(" q").append(x.auth.stream().queued())
                .append(" fz").append(x.auth.frozen() ? 1 : 0).append(" z").append(f(x.auth.simulation().z() - this.oz)).append(" v").append(x.auth.lastHitVerdict())
                .append(" rot").append(f(x.auth.tickYRot())).append('/').append(f(x.auth.tickXRot())).append(" cy").append(f(x.yaw)).append('/').append(f(x.pitch))
                .append(" hand ").append(x.player.getMainHandItem().getItem()).append(" cd").append(x.player.getCooldowns().isOnCooldown(x.player.getMainHandItem()) ? 1 : 0)
                .append(" suse").append(x.auth.simulation().usingItem() ? 1 : 0).append(" muse").append(x.model.usingItem() ? 1 : 0).append(" gd")
                .append(x.auth.stats().grammarDropped()).append(" wp").append(x.withheld.size());
            for (final Client other : this.cast) {
                final RemoteMirror m = other == x ? null : CombatWiring.book(x.auth).mirror(other.player.getId());
                if (m != null) {
                    line.append(" sees ").append(other.name).append(" z").append(f(m.minZ() - this.oz)).append("..").append(f(m.maxZ() - this.oz)).append(" y")
                        .append(f(m.minY() - this.gy)).append(m.present() ? "" : " absent").append(m.exact() ? "" : " inexact");
                }
            }
            line.append("] ");
        }
        this.tests.getLogger().info(line.toString());
    }

    // =================================================================================================================
    // Honest: the rotation authority starts with
    // =================================================================================================================

    // A vanilla client sends its rotation only when it changes. A player facing yaw 180 since its join teleport (whose
    // accept carried that rotation and came in before authority started at the next TickEnd) and who hasn't moved the
    // mouse since: its attack straight ahead, its strafe, then the same after one mouse movement
    private void rotationAtActivation() {
        final String id = "honest: a client facing yaw 180 that hasn't turned since authority started attacks and strafes";
        this.step(1, id + ": arena and join", () -> {
            this.open(id);
            this.probe = () -> {
            };
            this.clearArena();
            this.walls(true, true, GAP);
            this.c = this.client("Target", this.ox + 0.5, this.gy, this.oz + 0.3, 0.0F);
            this.h = this.client("Honest", this.ox + 0.5, this.gy, this.oz + 0.3 + GAP, 180.0F);
        });
        this.step(WARM + 5, id + ": under enforce, swords", () -> {
            this.ready(this.c);
            this.ready(this.h, false);
            this.give(this.c, 0, new ItemStack(Items.DIAMOND_SWORD));
            this.give(this.h, 0, new ItemStack(Items.DIAMOND_SWORD));
        });
        final int[] mark = {0};
        this.step(5, id + ": attack straight ahead", () -> {
            this.label = "HONEST";
            mark[0] = this.attackLog.size();
            this.h.click(this.c, true);
        });
        this.step(3, id + ": strafe", () -> {
            this.honestExpect(this.attacksBy(this.h, mark[0]) == 1, "the attack straight ahead landed: " + this.attacksBy(this.h, mark[0]) + " (client yaw "
                + f(this.h.yaw) + ", the server's tick rotation " + f(this.h.auth.tickYRot()) + ", verdict " + this.h.auth.lastHitVerdict() + ")");
            this.base = this.snap(this.h);
            this.h.keys = LEFT;
        });
        this.step(10, id + ": stop", () -> this.h.keys = NONE);
        this.step(10, id + ": one mouse movement, then the same attack", () -> {
            final Snap d = this.delta(this.h);
            this.honestExpect(d.corrections() == 0, "the strafe needed no correction: " + d.corrections() + " (exact " + d.exact() + "/" + d.compared() + ")");
            this.aim(this.h, this.c); // it turns to the target: the rotation goes out with this tick's move
            mark[0] = this.attackLog.size();
        });
        this.step(2, id + ": attack after turning", () -> this.h.click(this.c, true));
        this.step(3, id + ": judge", () -> this.note("after one mouse movement: landed " + this.attacksBy(this.h, mark[0]) + " (tick rotation "
            + f(this.h.auth.tickYRot()) + ")"));
        this.finish(id);
    }

    // A vanilla client handles hotbar keys before use clicks in one tick (Minecraft.handleKeybinds), and
    // LivingEntity.startUsingItem reads the hand as it is then: a hotbar key and a right click in the same tick block
    // with the new sword. Control: the same a tick apart
    private void swapAndBlockSameTick() {
        final String id = "honest: take the sword and block in the same tick";
        this.begin(id, () -> {
        });
        final boolean[] blocked = new boolean[4];
        this.step(1, id + ": a stick in slot 0, a sword in slot 1", () -> {
            this.label = "HONEST";
            this.give(this.c, 0, new ItemStack(Items.STICK));
            this.give(this.c, 1, new ItemStack(Items.DIAMOND_SWORD));
        });
        this.step(10, id + ": control: hotbar key, then right click a tick later", () -> this.c.select(1));
        this.step(1, id + ": control: block", () -> this.c.use());
        this.step(2, id + ": control: judged", () -> {
            blocked[0] = gg.hcfrevival.revival.combat.LegacySwordBlocking.isSwordBlocking(this.c.player);
            blocked[1] = this.c.auth.simulation().usingItem();
            this.honestExpect(blocked[0] && blocked[1], "control, a tick apart: the server blocks " + blocked[0] + ", the truth uses the sword " + blocked[1]);
            this.c.release();
        });
        this.step(2, id + ": back to the stick", () -> this.c.select(0));
        this.step(10, id + ": hotbar key and right click in one tick", () -> {
            this.c.select(1);
            this.c.use();
        });
        this.step(2, id + ": judged", () -> {
            blocked[2] = gg.hcfrevival.revival.combat.LegacySwordBlocking.isSwordBlocking(this.c.player);
            blocked[3] = this.c.auth.simulation().usingItem();
            this.honestExpect(blocked[2] && blocked[3], "in one tick: the server blocks " + blocked[2] + ", the truth uses the sword " + blocked[3]
                + " (a vanilla client does both)");
            this.c.release();
        });
        this.finish(id);
    }

    // =================================================================================================================
    // Neue: click buffer
    // =================================================================================================================

    // neue-combat §5: an honest click into the shut window is held and lands on the tick it opens (the control)
    private void bufferControl(final String rules) {
        final String id = rules + " click-buffer: an honest click into the window lands on the opening tick (control)";
        this.pair(id, GAP, true, true);
        final long[] landed = {0};
        this.step(1, id + ": fresh hit", () -> {
            this.label = "CONTROL";
            reset(this.c.player);
            reset(this.h.player);
            landed[0] = NeueCombat.state(this.c.player).bufferedLanded();
            this.c.click(this.h, true);
        });
        this.step(8, id + ": click into the shut window", () -> this.c.click(this.h, true));
        this.step(16, id + ": judge", () -> {
            final long buffered = NeueCombat.state(this.c.player).bufferedLanded() - landed[0];
            this.honestExpect(this.h.player.getHealth() == 4.0F && buffered == 1, "two fresh hits, the second held and landed (health "
                + this.h.player.getHealth() + ", buffered landings " + buffered + ")");
        });
        this.finish(id);
    }

    // A click from out of reach is judged INVALID and never held: pre-loading clicks from 3.6 and walking into reach for
    // the opening tick lands nothing
    private void bufferFromOutOfReach(final String rules) {
        final String id = rules + " click-buffer: pre-load clicks from out of reach (3.6), walk into reach for the opening tick";
        this.pair(id, 3.6, true, false);
        final long[] before = new long[3];
        this.step(1, id + ": the target's window shut", () -> {
            reset(this.c.player);
            reset(this.h.player);
            this.shutWindow(this.h.player);
            this.base = this.snap(this.c);
            final NeueState state = NeueCombat.state(this.c.player);
            before[0] = state.bufferedLanded();
            before[1] = state.bufferOutOfReach();
            this.c.forcePosition = true;
            final int[] k = {0};
            this.c.before = x -> {
                final int t = k[0]++;
                if (t < 6) {
                    x.click(this.h, true); // every click from 3.6 (eye to box 3.3)
                } else if (t < 11) {
                    x.keys = FORWARD; // then into reach, no more clicks
                } else {
                    x.keys = NONE;
                }
            };
        });
        this.step(4, id + ": mid-way", () -> before[2] = NeueCombat.state(this.c.player).pending() ? 1 : 0);
        this.judge(20, id, () -> {
            final Snap d = this.delta(this.c);
            final NeueState state = NeueCombat.state(this.c.player);
            this.c.before = null;
            this.expect(d.attacksAccepted() == 0 && d.attacksRejected() + d.overCps() >= 6, "every pre-loaded click refused: judged out of reach "
                + d.attacksRejected() + ", over the click cap (not judged) " + d.overCps() + ", judged valid " + d.attacksAccepted());
            this.expect(before[2] == 0, "nothing held while out of reach");
            this.expect(state.bufferedLanded() == before[0] && this.h.player.getHealth() == 20.0F, "nothing landed when the window opened (health "
                + this.h.player.getHealth() + ", buffered landings " + (state.bufferedLanded() - before[0]) + ")");
            final Simulation s = this.c.auth.simulation();
            this.note("the attacker ended " + f(this.h.player.getZ() - 0.3 - s.z()) + " from the target's face (in reach at the opening tick)");
        });
        this.finish(id);
    }

    // §5.2 step 5: reach is checked again at landing, on the attacker's mirror
    private void bufferTargetLeaves(final String rules) {
        final String id = rules + " click-buffer: click from the edge of reach, the target walks out before the window opens";
        this.pair(id, 2.8, true, false);
        final long[] before = new long[3];
        final boolean[] clicked = {false};
        this.step(1, id + ": the target's window shut", () -> {
            reset(this.c.player);
            reset(this.h.player);
            this.shutWindow(this.h.player);
            this.base = this.snap(this.c);
            final NeueState state = NeueCombat.state(this.c.player);
            before[0] = state.bufferedLanded();
            before[1] = state.bufferOutOfReach();
            this.landingView = Double.NaN;
            this.landingTruth = Double.NaN;
            // The target walks backward from 7 ticks before the opening; the click goes 3 ticks before it
            this.h.before = x -> x.keys = this.h.player.damageCooldownTime <= 17 && this.h.player.damageCooldownTime > 0 ? BACKWARD : NONE;
            this.c.before = x -> {
                if (!clicked[0] && this.h.player.damageCooldownTime == 13) {
                    clicked[0] = true;
                    x.click(this.h, true);
                }
            };
        });
        this.step(4, id + ": held?", () -> before[2] = NeueCombat.state(this.c.player).pending() ? 1 : 0);
        this.judge(18, id, () -> {
            final Snap d = this.delta(this.c);
            final NeueState state = NeueCombat.state(this.c.player);
            this.c.before = null;
            this.h.before = null;
            this.h.keys = NONE;
            this.note("the click " + (clicked[0] ? "was made" : "was NOT made") + ", judged valid " + d.attacksAccepted() + ", held " + (before[2] == 1));
            final long landed = state.bufferedLanded() - before[0];
            if (landed == 0) {
                this.note("no landing (dropped out of reach " + (state.bufferOutOfReach() - before[1]) + ", health " + this.h.player.getHealth() + ")");
            } else {
                // §5.2 step 5: the landing is judged on the box the attacker's client shows at its latest tick (interpolation
                // and its own ping behind the target's truth), never past the client's range
                this.expect(this.landingView < 3.0, "it landed on the box the attacker's client showed, " + f(this.landingView) + " from its eye (range 3.0)");
                if (this.landingTruth >= 3.0) {
                    this.label = "BOUNDED (the attacker's view, within its rewind cap)";
                }
                this.note("the target's truth was " + f(this.landingTruth) + " from that eye (health " + this.h.player.getHealth() + ", rewind cap "
                    + this.c.auth.lastRewindCap() + " ms)");
            }
            this.note("the target walked to " + f(this.h.auth.simulation().z() - (this.oz + 0.3)) + " from the attacker");
        });
        this.finish(id);
    }

    // One held click per attacker: clicks spread over two shut targets land at most once
    private void bufferTwoTargets(final String rules) {
        final String id = rules + " click-buffer: spam held clicks across two targets";
        final Client[] other = {null};
        this.step(1, id + ": arena and join", () -> {
            this.open(id);
            this.clearArena();
            this.c = this.client("Cheat", this.ox + 0.5, this.gy, this.oz + 0.3, 0.0F);
            this.h = this.client("Target", this.ox - 0.1, this.gy, this.oz + 0.3 + GAP, 180.0F);
            other[0] = this.client("Target", this.ox + 1.1, this.gy, this.oz + 0.3 + GAP, 180.0F);
        });
        this.step(WARM + 5, id + ": under enforce", () -> {
            for (final Client x : this.cast) {
                this.ready(x);
                this.give(x, 0, new ItemStack(Items.DIAMOND_SWORD));
            }
        });
        final long[] before = new long[1];
        this.step(5, id + ": both windows shut, alternate clicks", () -> {
            reset(this.c.player);
            reset(this.h.player);
            reset(other[0].player);
            this.shutWindow(this.h.player);
            this.shutWindow(other[0].player);
            before[0] = NeueCombat.state(this.c.player).bufferedLanded();
            this.base = this.snap(this.c);
            final int[] k = {0};
            this.c.before = x -> {
                final int w = this.h.player.damageCooldownTime;
                if (w <= 14 && w >= 11 && k[0] < 4) {
                    final Client target = (k[0]++ & 1) == 0 ? this.h : other[0];
                    this.aim(x, target);
                    x.click(target, true);
                }
            };
        });
        this.judge(22, id, () -> {
            final Snap d = this.delta(this.c);
            this.c.before = null;
            final long buffered = NeueCombat.state(this.c.player).bufferedLanded() - before[0];
            final float lost = (20.0F - this.h.player.getHealth()) + (20.0F - other[0].player.getHealth());
            this.expect(buffered <= 1 && lost <= 8.0F, "at most one held click landed (buffered landings " + buffered + ", health " + this.h.player.getHealth()
                + " and " + other[0].player.getHealth() + ")");
            this.note("clicks judged valid " + d.attacksAccepted() + ", invalid " + d.attacksRejected());
        });
        this.finish(id);
    }

    // =================================================================================================================
    // Neue: click cap
    // =================================================================================================================

    // §4.3: an even 12 CPS clicker loses nothing
    private void capControl(final String rules) {
        final String id = rules + " click-cap: an even 12 CPS clicker (control)";
        this.pair(id, GAP, true, true);
        final int[] mark = {0};
        this.step(1, id + ": click 2, 2, 1 ticks apart for 2 s", () -> {
            this.label = "CONTROL";
            this.cancelDamageTo.add(this.h.player.getUUID());
            this.base = this.snap(this.c);
            mark[0] = this.attackLog.size();
            final int[] k = {0};
            this.c.before = x -> {
                final int t = k[0]++;
                if (t < 40 && (t % 5 == 0 || t % 5 == 2 || t % 5 == 4)) {
                    x.click(this.h, true);
                }
            };
        });
        this.step(45, id + ": judge", () -> {
            final Snap d = this.delta(this.c);
            this.c.before = null;
            this.cancelDamageTo.clear();
            final int delivered = this.attacksBy(this.c, mark[0]);
            this.honestExpect(delivered == 24 && d.overCps() == 0, "every click delivered: " + delivered + " of 24 (over the cap " + d.overCps() + ")");
        });
        this.finish(id);
    }

    // §4.3: the bucket refills per window that runs, and the tick clock bounds windows to real time
    private void capTimer(final String rules) {
        final String id = rules + " click-cap: a 30 tps timer, an attack in every window for 4 s";
        this.pair(id, GAP, true, true);
        final long[] start = {0, 0};
        final int[] mark = {0};
        this.step(1, id + ": go", () -> {
            this.cancelDamageTo.add(this.h.player.getUUID());
            this.base = this.snap(this.c);
            mark[0] = this.attackLog.size();
            start[0] = System.nanoTime();
            this.c.rate = 30.0;
            this.c.before = x -> x.click(this.h, true);
        });
        this.step(80, id + ": stop", () -> {
            this.c.before = null;
            this.c.rate = 20.0;
        });
        this.judge(30, id, () -> {
            final Snap d = this.delta(this.c);
            this.cancelDamageTo.clear();
            final CombatRules set = NeueCombat.rules(this.c.player);
            final AuthoritativeSettings settings = Revival.settings().authoritative();
            // Real time, as the tick clock counts it: the server's ticks run late on a loaded machine
            final double ticks = (System.nanoTime() - start[0]) / 50_000_000.0;
            final int delivered = this.attacksBy(this.c, mark[0]);
            final long bound = (long) Math.floor(set.clickBurst() + set.clickRefill() * (ticks + settings.maxBankedTicks() + 1)) + 1;
            this.expect(delivered <= bound, "delivered " + delivered + " in " + f(ticks * 50.0) + " ms (real-time bound " + bound + ", "
                + f(delivered * 20.0 / ticks) + " a second)");
            this.expect(d.overCps() > 0, "clicks past the cap counted: " + d.overCps());
            this.note("windows run " + d.windows() + " of " + d.closed() + " sent, refused " + d.refused());
        });
        this.honestAfter(id, NONE, 30);
        this.finish(id);
    }

    private void capTwoPerWindow(final String rules) {
        final String id = rules + " click-cap: two attacks in every window for 2 s";
        this.pair(id, GAP, true, true);
        final int[] mark = {0};
        this.step(1, id + ": go", () -> {
            this.cancelDamageTo.add(this.h.player.getUUID());
            this.base = this.snap(this.c);
            mark[0] = this.attackLog.size();
            this.c.before = x -> {
                x.click(this.h, true);
                x.click(this.h, true);
            };
        });
        this.step(40, id + ": stop", () -> this.c.before = null);
        this.judge(5, id, () -> {
            final Snap d = this.delta(this.c);
            this.cancelDamageTo.clear();
            final long bound = (long) Math.floor(2.0 + 0.6 * d.windows()) + 1;
            final int delivered = this.attacksBy(this.c, mark[0]);
            this.expect(delivered <= d.windows() && delivered <= bound, "delivered " + delivered + " in " + d.windows()
                + " windows (one a window at most, bucket bound " + bound + "; judged valid " + d.attacksAccepted() + ")");
            this.expect(d.overCap() >= 30, "second attacks dropped by the envelope: " + d.overCap() + ", over the cap " + d.overCps());
        });
        this.finish(id);
    }

    // =================================================================================================================
    // Neue: parry
    // =================================================================================================================

    // §8.2: a block toggled every tick has no rest, so no parry window
    private void blockSpam() {
        final String id = "neue parry: block-spam to fish for parries";
        this.pair(id, GAP, true, true);
        final int[] parries = new int[2];
        this.step(25, id + ": a rested fresh block (control)", () -> {
            parries[0] = this.parries(this.c);
            this.c.use();
        });
        this.step(1, id + ": the honest attacker hits it", () -> this.h.click(this.c, true));
        this.step(3, id + ": control parried", () -> {
            this.honestExpect(this.parries(this.c) - parries[0] == 1, "control: a rested fresh block parries (" + (this.parries(this.c) - parries[0]) + ")");
            this.c.release();
        });
        // No rest after the control: every block of the spam comes down and goes up again a tick later
        this.step(1, id + ": spam the block, the attacker clicks every tick", () -> {
            parries[1] = this.parries(this.c);
            this.probe = () -> this.c.player.setHealth(20.0F);
            this.c.before = x -> {
                if (x.model.usingItem()) {
                    x.release();
                } else {
                    x.use();
                }
            };
            this.h.before = x -> {
                this.aim(x, this.c);
                x.click(this.c, true);
            };
        });
        this.judge(40, id, () -> {
            this.c.before = null;
            this.h.before = null;
            this.probe = () -> {
            };
            this.expect(this.parries(this.c) == parries[1], "parries while spamming: " + (this.parries(this.c) - parries[1]) + " (window now "
                + NeueCombat.state(this.c.player).parryWindow() + ")");
        });
        this.finish(id);
    }

    // §8.2: a block raised soon after a swing (a block-hit) never parries. The swing is the Punch packet: an attack sent
    // without one leaves the server thinking the attacker hasn't swung
    private void parryWithoutPunch() {
        final String id = "neue parry: attack without the Punch packet, then block (a block-hit that parries)";
        this.pair(id, GAP, true, true);
        final int[] parries = new int[2];
        final int[] window = new int[2];
        this.step(25, id + ": control: attack with the Punch, then block", () -> {
            this.cancelDamageTo.add(this.h.player.getUUID());
            parries[0] = this.parries(this.c);
            this.c.click(this.h, true);
        });
        this.step(1, id + ": control: block", () -> this.c.use());
        this.step(1, id + ": control: the honest attacker hits the block", () -> {
            window[0] = NeueCombat.state(this.c.player).parryWindow();
            this.h.click(this.c, true);
        });
        this.step(3, id + ": control: judged", () -> {
            this.honestExpect(this.parries(this.c) == parries[0], "control: a block-hit doesn't parry (window " + window[0] + ", parries "
                + (this.parries(this.c) - parries[0]) + ")");
            this.c.release();
            reset(this.c.player);
        });
        this.step(30, id + ": cheat: attack without the Punch", () -> {
            parries[1] = this.parries(this.c);
            this.c.click(this.h, false);
        });
        this.step(1, id + ": cheat: block", () -> this.c.use());
        this.step(1, id + ": cheat: the honest attacker hits the block", () -> {
            window[1] = NeueCombat.state(this.c.player).parryWindow();
            this.h.click(this.c, true);
        });
        this.judge(3, id, () -> {
            this.cancelDamageTo.clear();
            final int fished = this.parries(this.c) - parries[1];
            this.expect(fished == 0, "the block raised one tick after the attack parried " + fished + " (its window " + window[1] + " ticks; with the Punch: "
                + window[0] + ")");
            this.c.release();
        });
        this.finish(id);
    }

    // §12.2 detector A counts Punch packets: an autoclicker that leaves them out is invisible to it (and to viewers)
    private void cadenceWithoutPunch(final String rules) {
        final String id = rules + " cadence: a 20 CPS autoclicker that leaves out the Punch packet";
        this.pair(id, GAP, true, true);
        final int[] longest = new int[2];
        final int[] fired = new int[2];
        final int[] swings = new int[4];
        this.step(5, id + ": control: 20 CPS with the Punch for 6 s", () -> {
            this.cancelDamageTo.add(this.h.player.getUUID());
            fired[0] = this.anomalies.getOrDefault(this.c.player.getScoreboardName() + " click-streak", 0);
            swings[0] = this.swingsSeen(this.h, this.c);
            this.c.before = x -> x.click(this.h, true);
        });
        this.step(120, id + ": control: judged", () -> {
            this.c.before = null;
            longest[0] = this.c.auth.cadence().longest();
            fired[0] = this.anomalies.getOrDefault(this.c.player.getScoreboardName() + " click-streak", 0) - fired[0];
            swings[1] = this.swingsSeen(this.h, this.c) - swings[0];
            this.honestExpect(longest[0] >= 100 && fired[0] >= 1, "control: detected (longest one-click run " + longest[0] + " ticks, click-streak alerts "
                + fired[0] + "; the target saw " + swings[1] + " swings)");
        });
        this.step(10, id + ": cheat: 20 CPS without the Punch for 6 s", () -> {
            fired[1] = this.anomalies.getOrDefault(this.c.player.getScoreboardName() + " click-streak", 0);
            swings[2] = this.swingsSeen(this.h, this.c);
            this.base = this.snap(this.c);
            this.c.before = x -> x.click(this.h, false);
            this.probe = () -> longest[1] = Math.max(longest[1], this.c.auth.cadence().streak());
        });
        this.judge(120, id, () -> {
            final Snap d = this.delta(this.c);
            this.c.before = null;
            this.probe = () -> {
            };
            this.cancelDamageTo.clear();
            final int alerts = this.anomalies.getOrDefault(this.c.player.getScoreboardName() + " click-streak", 0) - fired[1];
            swings[3] = this.swingsSeen(this.h, this.c) - swings[2];
            this.note("delivered " + d.attacksAccepted() + ", over the cap " + d.overCps());
            this.expect(longest[1] >= 100 || alerts >= 1, "the same clicker without Punch packets: longest one-click run " + longest[1] + " ticks, click-streak alerts "
                + alerts);
            this.note("the target saw " + swings[3] + " swings from it (" + swings[1] + " with the Punch)");
        });
        this.finish(id);
    }

    // =================================================================================================================
    // Neue: stagger and riposte
    // =================================================================================================================

    // §8.4: the recoil is a logged knock; ignoring it is corrected
    private void recoilIgnored() {
        final String id = "neue stagger: ignore the recoil (anti-knockback on the parry)";
        this.pair(id, GAP, false, true);
        final double[] start = new double[1];
        this.step(25, id + ": the honest player blocks", () -> this.h.use());
        this.step(1, id + ": attack the fresh block, ignore the recoil", () -> {
            this.base = this.snap(this.c);
            this.track = new Track();
            this.probe = () -> this.track.sample(this.c);
            start[0] = this.c.auth.simulation().z();
            this.c.ignoreMotion = true;
            this.c.forcePosition = true;
            this.c.click(this.h, true);
        });
        this.judge(20, id, () -> {
            final Snap d = this.delta(this.c);
            final NeueState state = NeueCombat.state(this.c.player);
            this.expect(state.staggers() >= 1 && this.lastParryAttacker == this.c.player.getId(), "parried and staggered (" + state.staggers() + ")");
            this.expect(start[0] - this.track.truthMinZ >= 1.0, "the truth took the recoil: thrown " + f(start[0] - this.track.truthMinZ) + " back");
            this.expect(start[0] - this.track.bodyMinZ >= 1.0, "the ServerPlayer too: " + f(start[0] - this.track.bodyMinZ));
            this.expect(d.corrections() > 0, "the client was corrected: " + d.corrections() + " (claims stayed " + f(start[0] - this.track.claimMinZ) + " back)");
            this.expect(state.staggerAck() != NeueState.NEVER, "the recoil's ack started the lockout (window " + state.staggerAck() + ")");
            this.h.release();
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // §8.4 step 4: the lockout holds until the recoil is acked (or forced at its deadline), then 10 windows
    private void lockoutPongsHeld() {
        final String id = "neue stagger: keep clicking through the lockout with the recoil's pong withheld";
        this.pair(id, GAP, true, true);
        this.lockoutCase(id, x -> {
            x.withholdPongs = true; // it reads the parry and the recoil at once, and keeps their pongs back
        }, 20, x -> x.releaseHeld());
    }

    // The lockout refuses every attack (LegacyHitRegistration.beforeAttack, AuthPlayer.runAttack), whatever is held
    private void lockoutAxe() {
        final String id = "neue stagger: swap to an axe and keep clicking through the lockout";
        this.pair(id, GAP, true, true);
        this.step(1, id + ": an axe in slot 2", () -> this.give(this.c, 2, new ItemStack(Items.DIAMOND_AXE)));
        this.lockoutCase(id, x -> {
        }, 2, x -> x.select(2));
    }

    // §8.4 / §5.2: the stagger drops the attacker's held click. A click held on another target (its window shut) just
    // before the parry must not land when that window opens, inside the lockout
    private void heldClickAcrossLockout() {
        final String id = "neue stagger: a click held on another target lands during the lockout?";
        this.pair(id, GAP, true, true);
        final Client[] other = {null};
        final long[] before = new long[1];
        this.step(1, id + ": a second target beside the blocker", () -> {
            other[0] = this.client("Target", this.ox + 1.3, this.gy, this.oz + 0.3 + GAP, 180.0F);
        });
        this.step(WARM + 5, id + ": the blocker blocks, the second target's window shut", () -> {
            this.ready(other[0]);
            reset(other[0].player);
            this.shutWindow(other[0].player);
            before[0] = NeueCombat.state(this.c.player).bufferedLanded();
            // The block goes up a tick before the held click, so the click into it a tick after that meets a fresh block
            this.h.before = x -> {
                if (other[0].player.damageCooldownTime == 14) {
                    x.before = null;
                    x.use();
                }
            };
            final int[] k = {0};
            this.c.before = x -> {
                final int w = other[0].player.damageCooldownTime;
                if (k[0] == 0 && w == 13) {
                    k[0] = 1;
                    this.aim(x, other[0]);
                    x.click(other[0], true); // held: the window is shut
                } else if (k[0] == 1) {
                    k[0] = 2;
                    this.aim(x, this.h);
                    x.click(this.h, true); // into the fresh block: parried
                }
            };
        });
        this.judge(20, id, () -> {
            this.c.before = null;
            this.h.before = null;
            final NeueState state = NeueCombat.state(this.c.player);
            this.expect(this.lastParryAttacker == this.c.player.getId(), "parried (" + state.staggers() + " staggers)");
            this.expect(state.bufferedLanded() == before[0] && other[0].player.getHealth() == 20.0F, "the held click never landed (buffered landings "
                + (state.bufferedLanded() - before[0]) + ", the second target's health " + other[0].player.getHealth() + ")");
            this.h.release();
        });
        this.finish(id);
    }

    // Parried at server tick P; the attacker clicks every tick; the first attack delivered after P is judged
    private void lockoutCase(final String id, final Consumer<Client> atAttack, final int laterTicks, final Consumer<Client> later) {
        final int[] attackMark = {0};
        this.step(25, id + ": the honest player blocks", () -> this.h.use());
        this.step(1, id + ": attack the fresh block, then click every tick", () -> {
            this.base = this.snap(this.c);
            attackMark[0] = this.attackLog.size();
            atAttack.accept(this.c);
            this.c.before = x -> {
                this.aim(x, this.h);
                x.click(this.h, true);
            };
        });
        this.step(2, id + ": the parrier lowers its block", () -> this.h.release());
        this.step(laterTicks, id + ": later", () -> later.accept(this.c));
        this.judge(30, id, () -> {
            final Snap d = this.delta(this.c);
            this.c.before = null;
            final NeueState state = NeueCombat.state(this.c.player);
            final long[] first = this.firstAttackAfter(this.c, attackMark[0], this.lastParryTick);
            this.expect(this.lastParryAttacker == this.c.player.getId() && state.staggerAck() != NeueState.NEVER, "parried, the lockout's start known (window "
                + state.staggerAck() + ", forced entries " + d.forced() + ")");
            this.expect(first != null && first[2] >= state.staggerAck() + 10, "first attack delivered in window " + (first == null ? "none" : first[2])
                + ", the recoil acked in " + state.staggerAck() + " (expected at least +10); " + (first == null ? "" : (first[1] - this.lastParryTick)
                + " server ticks after the parry"));
            this.c.releaseHeld();
        });
        this.finish(id);
    }

    // §8.4: the lockout is counted in the attacker's client ticks (windows). Variant 0: an honest attacker (the control,
    // in server ticks from the parry to its first delivered attack); 1: the attacker runs its client 3x fast once it sees
    // the recoil (the timer's standing allowance); 2: it banked 8 ticks first (a fake stall with its pongs held), then 3x
    private void lockoutTimer(final int variant, final int latency) {
        final String at = latency == 0 ? "" : " at " + latency * 50 + " ms";
        final String id = switch (variant) {
            case 0 -> "neue stagger: an honest attacker's lockout after a parry" + at + " (control)";
            case 1 -> "neue stagger: run the lockout's windows at 3x speed (timer)" + at;
            default -> "neue stagger: bank 8 ticks (a fake stall, pongs held), then run the lockout at 3x speed" + at;
        };
        this.pair(id, GAP, true, true);
        this.step(1, id + ": latency", () -> this.c.latencyTicks = latency);
        final int[] motions = {0};
        final int[] attackMark = {0};
        if (variant == 2) {
            this.step(24, id + ": bank (the client stops, its pongs held)", () -> {
                this.c.withholdPongs = true;
                this.c.rate = 0.0;
                final long until = MinecraftServer.currentTick + 26; // let go before max-unacked-ms freezes it
                this.probe = () -> {
                    if (MinecraftServer.currentTick >= until && this.c.withholdPongs) {
                        this.c.releaseHeld();
                    }
                };
            });
        }
        this.step(variant == 2 ? 8 : 25, id + ": the honest player blocks", () -> {
            this.c.rate = 20.0;
            this.h.use();
        });
        this.step(1, id + ": attack the fresh block, then click every tick", () -> {
            this.base = this.snap(this.c);
            attackMark[0] = this.attackLog.size();
            motions[0] = this.c.selfMotions;
            this.c.before = x -> {
                this.aim(x, this.h);
                x.click(this.h, true);
                if (variant > 0 && x.selfMotions > motions[0]) {
                    x.rate = 60.0; // it saw the recoil: run the lockout's windows as fast as the server lets them run
                }
            };
            final Runnable safety = this.probe;
            this.probe = () -> {
                safety.run();
                if (this.lastParryAttacker == this.c.player.getId() && this.firstAttackAfter(this.c, attackMark[0], this.lastParryTick) != null) {
                    this.c.before = null;
                    this.c.rate = 20.0;
                    this.c.releaseHeld();
                }
            };
        });
        this.step(2, id + ": the parrier lowers its block", () -> this.h.release());
        this.judge(30, id, () -> {
            final Snap d = this.delta(this.c);
            this.c.before = null;
            this.c.rate = 20.0;
            this.c.releaseHeld();
            this.probe = () -> {
            };
            final NeueState state = NeueCombat.state(this.c.player);
            final long[] first = this.firstAttackAfter(this.c, attackMark[0], this.lastParryTick);
            final boolean parried = this.lastParryAttacker == this.c.player.getId();
            final long ticks = first == null ? -1 : first[1] - this.lastParryTick;
            final String detail = "first attack " + ticks + " server ticks after the parry (window " + (first == null ? "none" : first[2]) + ", recoil acked in "
                + state.staggerAck() + "; windows run " + d.windows() + ", refused " + d.refused() + ", forced " + d.forced() + ")";
            if (variant == 0) {
                this.label = "CONTROL";
                this.honestExpect(parried && first != null && first[2] == state.staggerAck() + 10, "honest: " + detail);
                this.lockoutHonestTicks.put(latency, ticks);
            } else {
                final long honest = this.lockoutHonestTicks.getOrDefault(latency, -1L);
                this.expect(parried && first != null, "parried, then an attack delivered");
                this.expect(ticks >= honest - 1, detail + "; an honest attacker's came " + honest + " ticks after");
                this.note("lockout in windows: first attack " + (first == null ? "none" : first[2] - state.staggerAck()) + " windows after the ack");
            }
        });
        this.finish(id);
    }

    // §8.4: the stagger refuses blocks through the sword's cooldown (its type only). Another sword type raises a block
    // (and the parry window it starts) during the lockout
    private void swapSwordAndParry() {
        final String id = "neue stagger: swap to another sword type and block during the lockout (parry the riposte)";
        this.pair(id, GAP, true, true);
        final boolean[] blocking = {false};
        final int[] mark = new int[3];
        this.step(1, id + ": an iron sword in slot 1", () -> this.give(this.c, 1, new ItemStack(Items.IRON_SWORD)));
        this.step(25, id + ": the honest player blocks", () -> this.h.use());
        this.step(1, id + ": attack the fresh block", () -> {
            mark[0] = this.c.selfMotions;
            mark[1] = this.parries(this.c);
            mark[2] = this.parries(this.h);
            this.c.click(this.h, true);
            final int[] phase = {0};
            this.c.before = x -> {
                if (phase[0] == 0 && x.selfMotions > mark[0]) {
                    // It saw the recoil and the sweep on its diamond sword: the iron one, then a block the next tick
                    phase[0] = 1;
                    x.select(1);
                } else if (phase[0] == 1) {
                    x.before = null;
                    x.use();
                }
            };
            this.probe = () -> blocking[0] |= NeueCombat.lockedOut(this.c.player) && gg.hcfrevival.revival.combat.LegacySwordBlocking.isSwordBlocking(this.c.player);
        });
        // Parried in the attack's window (server tick P); the cheater sees the recoil, takes the iron sword in its window of
        // P + 1 and blocks in P + 2; the parrier lowers its block in P + 1 and ripostes in P + 3, inside the 3-tick window
        this.step(1, id + ": the parrier lowers its block", () -> this.h.release());
        this.step(2, id + ": riposte", () -> {
            this.aim(this.h, this.c);
            this.h.click(this.c, true);
        });
        this.judge(4, id, () -> {
            this.probe = () -> {
            };
            final int parriedRiposte = this.parries(this.c) - mark[1];
            this.expect(this.parries(this.h) - mark[2] == 1, "the honest player parried the first attack");
            this.expect(!blocking[0], "a block went up during the lockout: " + blocking[0] + " (with " + this.c.player.getMainHandItem().getItem() + ")");
            this.expect(parriedRiposte == 0, "the riposte was parried by the staggered player: " + parriedRiposte + " (riposter staggered "
                + NeueCombat.state(this.h.player).staggered() + ", its health " + this.h.player.getHealth() + ", the cheater's " + this.c.player.getHealth() + ")");
            this.c.release();
        });
        this.finish(id);
    }

    // §8.3: only a player's melee (or a player's melee-window projectile) parries; a mob's hit on a fresh block gives no riposte
    private void mobOnFreshBlock() {
        final String id = "neue riposte: a mob's hit on a fresh block (riposte farming on mobs)";
        this.begin(id, () -> {
        });
        final int[] parries = {0};
        this.step(1, id + ": sword", () -> this.give(this.c, 0, new ItemStack(Items.DIAMOND_SWORD)));
        this.step(25, id + ": a rested fresh block", () -> {
            parries[0] = this.parries(this.c);
            this.c.use();
        });
        this.step(1, id + ": a zombie hits it", () -> {
            // Easy for this step: peaceful refuses a zombie and zeroes a mob's damage before the parry hook
            final Difficulty was = this.level.getDifficulty();
            this.server.setDifficulty(this.level, Difficulty.EASY, null, true);
            final Entity zombie = EntityTypes.ZOMBIE.create(this.level, EntitySpawnReason.COMMAND);
            zombie.snapTo(this.c.player.getX(), this.gy, this.c.player.getZ() + 1.2, 180.0F, 0.0F);
            ((net.minecraft.world.entity.Mob) zombie).setNoAi(true);
            this.level.addFreshEntity(zombie);
            final float health = this.c.player.getHealth();
            final boolean hurt = this.c.player.hurtServer(this.level, this.level.damageSources().mobAttack((net.minecraft.world.entity.LivingEntity) zombie), 4.0F);
            this.server.setDifficulty(this.level, was, null, true);
            zombie.discard();
            this.expect(this.parries(this.c) == parries[0] && !NeueCombat.riposteReady(this.c.player), "no parry, no riposte (parries "
                + (this.parries(this.c) - parries[0]) + ", riposte ready " + NeueCombat.riposteReady(this.c.player) + ")");
            this.note("the zombie's hit " + (hurt ? "landed" : "did nothing") + " on the block (health " + health + " -> " + this.c.player.getHealth() + ")");
            this.c.release();
        });
        this.finish(id);
    }

    // =================================================================================================================
    // Neue: DI
    // =================================================================================================================

    // §7.2: DI reads the victim's simulated keys, the keys its truth moves with: strafe keys sent without strafing
    // still strafe the truth (the claims are corrected), so the bend costs the movement it stands for
    private void diKeysNotMoved() {
        final String id = "neue DI: strafe keys sent, claims that don't strafe";
        // The honest attacker first (south, wall behind), the cheater the victim (north, no wall: the knock carries it)
        this.step(1, id + ": arena and join", () -> {
            this.open(id);
            this.clearArena();
            this.walls(true, false, GAP);
            this.h = this.client("Honest", this.ox + 0.5, this.gy, this.oz + 0.3, 0.0F);
            this.c = this.client("Cheat", this.ox + 0.5, this.gy, this.oz + 0.3 + GAP, 180.0F);
        });
        this.step(WARM + 5, id + ": under enforce", () -> {
            this.ready(this.h);
            this.ready(this.c);
            this.give(this.h, 0, new ItemStack(Items.DIAMOND_SWORD));
            this.give(this.c, 0, new ItemStack(Items.DIAMOND_SWORD));
        });
        final double[] x0 = new double[1];
        final int[] mark = new int[1];
        this.step(10, id + ": hold A, claim no strafe", () -> {
            reset(this.c.player);
            reset(this.h.player);
            this.base = this.snap(this.c);
            x0[0] = this.c.auth.simulation().x();
            this.c.keys = LEFT;
            this.c.modelKeys = NONE;
            this.c.forcePosition = true;
        });
        this.step(3, id + ": hit", () -> {
            mark[0] = this.c.fake.received.size();
            this.knockSeen = false;
            this.knockVictim = this.c.player.getId();
            this.aim(this.h, this.c);
            this.h.click(this.c, true);
        });
        this.judge(4, id, () -> {
            final Snap d = this.delta(this.c);
            this.c.keys = NONE;
            this.c.modelKeys = null;
            Vec3 knock = null;
            for (final net.minecraft.network.protocol.Packet<?> packet : this.c.fake.receivedOfType(ClientboundSetEntityMotionPacket.class, mark[0])) {
                final ClientboundSetEntityMotionPacket motion = (ClientboundSetEntityMotionPacket) packet;
                if (motion.id() == this.c.player.getId()) {
                    knock = motion.movement();
                    break;
                }
            }
            this.expect(knock != null && this.knockSeen, "knocked (" + knock + ")");
            if (knock != null) {
                final double away = Math.toDegrees(Math.atan2(this.knockTo[0] - this.knockFrom[0], this.knockTo[1] - this.knockFrom[1]));
                final double bend = Math.toDegrees(Math.atan2(knock.x, knock.z)) - away;
                this.note("DI bent the knock " + f(bend) + " degrees");
            }
            final double strafed = this.c.auth.simulation().x() - x0[0];
            this.expect(Math.abs(this.knockTo[0] - x0[0]) >= 0.15, "the truth strafed with the keys before the hit: " + f(this.knockTo[0] - x0[0])
                + " (the claims: " + f(this.c.claimX - x0[0]) + " now, truth " + f(strafed) + ")");
            this.expect(d.corrections() > 0, "the claims that didn't strafe were corrected: " + d.corrections());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // =================================================================================================================
    // Replication: K per viewer
    // =================================================================================================================

    // Torture K1: a viewer whose own ack jitter is low gets K1. A cheat's pongs are perfectly regular (zero jitter) and
    // then turn erratic to flap K: what K1 buys, whether a backtrack works under it, and how often K can switch
    private void replication() {
        final String id = "replication";
        this.combatJoin(id, 2.8);
        final long[] k1At = {-1};
        final long[] start = {0};
        this.step(1, id + ": perfectly regular pongs until K1", () -> {
            start[0] = MinecraftServer.currentTick;
            this.probe = () -> {
                if (k1At[0] < 0 && this.c.auth.pathStepTicks(Revival.settings().authoritative()) == 1) {
                    k1At[0] = MinecraftServer.currentTick;
                }
            };
        });
        this.step(440, id + ": K1?", () -> this.tests.getLogger().info("REVIVAL_CHEAT2_NOTE K1 after " + (k1At[0] < 0 ? "never" : (k1At[0] - start[0]) + " ticks")
            + ", acks " + this.c.auth.transactions().ackedCount()));
        this.attackCase("replication: an honest hit at 2.5 under K1 (control)", 0.0, 2.8, null, 1, () -> this.note("K " + this.kOf(this.c)
            + " (K1 after " + (k1At[0] < 0 ? "never" : (k1At[0] - start[0]) / 20.0 + " s") + ")"));
        // Backtrack under K1: hold pongs 500 ms while the victim steps 3 aside, attack where the stale view shows it
        final String bt = "replication: perfectly regular pongs to get K1, then a 500 ms backtrack";
        this.step(1, bt + ": hold pongs, the victim steps aside", () -> {
            this.open(bt);
            this.base = this.snap(this.c);
            this.c.holdPongs = true;
            this.place(3.0, 2.8);
        });
        final int[] mark = {0};
        this.step(10, bt + ": attack where the client still shows it", () -> {
            mark[0] = this.attackLog.size();
            this.c.yaw = 0.0F;
            this.c.pitch = 0.0F;
            this.c.click(this.v(), true);
        });
        this.step(2, bt + ": release", () -> this.c.releaseHeld());
        this.step(3, bt + ": judge", () -> {
            final Snap d = this.delta(this.c);
            this.expect(this.attacksBy(this.c, mark[0]) == 0, "refused: landed " + this.attacksBy(this.c, mark[0]) + " (judged valid " + d.attacksAccepted()
                + ", invalid " + d.attacksRejected() + ", K " + this.kOf(this.c) + ", verdict " + this.c.auth.lastHitVerdict() + ")");
            this.record();
            this.place(0.0, 2.8);
        });
        // Flap: 3 s of erratic pongs (0-3 ticks late, in order), 3 s regular, twice; then hits right after
        final String flap = "replication: flap K (erratic and regular pongs, 3 s each, twice)";
        final long[] switches = {0};
        final long[] flapStart = {0};
        for (int round = 0; round < 2; round++) {
            this.step(20, flap + ": erratic", () -> {
                if (switches[0] == 0 && flapStart[0] == 0) {
                    this.open(flap);
                    switches[0] = this.c.auth.stats().pathStepSwitches();
                    flapStart[0] = MinecraftServer.currentTick;
                }
                this.c.pingDelay = pingId -> Math.floorMod(pingId * 7, 4);
            });
            this.step(60, flap + ": regular", () -> this.c.pingDelay = null);
            this.step(40, flap + ": K now " + round, () -> this.note("K " + this.kOf(this.c) + " at " + (MinecraftServer.currentTick - flapStart[0]) / 20.0 + " s"));
        }
        this.step(1, flap + ": judge", () -> {
            final long n = this.c.auth.stats().pathStepSwitches() - switches[0];
            final double seconds = (MinecraftServer.currentTick - flapStart[0]) / 20.0;
            final int switchSeconds = Revival.settings().authoritative().stepSwitchSeconds();
            this.expect(n <= Math.ceil(seconds / switchSeconds), "K switched " + n + " times in " + f(seconds) + " s (at most one per " + switchSeconds
                + " s of steady readings; every switch re-pairs the players fed to the viewer)");
            this.record();
        });
        this.attackCase("replication: reach 3.5 right after the flapping", 0.0, 3.8, null, 0, () -> this.note("K " + this.kOf(this.c)));
        this.attackCase("replication: an honest hit at 2.5 right after the flapping (control)", 0.0, 2.8, null, 1, () -> this.note("K " + this.kOf(this.c)));
        this.step(1, id + ": done", this::leaveAll);
    }

    private int kOf(final Client x) {
        return x.auth.pathStepTicks(Revival.settings().authoritative());
    }

    // =================================================================================================================
    // Timer bank and selective pongs
    // =================================================================================================================

    // Torture F4: an honest stall's burst runs the bank and the bank's worth it forfeited. A cheater manufacturing stalls
    // back to back (nothing out, pongs held, its client ticking on) gets no more windows than real time
    private void fakeStalls() {
        final String id = "timer: three fake 1.2 s stalls back to back, walking";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        final long[] t0 = {0};
        final long[] maxBurst = {0};
        final double[] stalledMaxZ = {Double.NEGATIVE_INFINITY};
        final double[] stallStartZ = {0.0};
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            t0[0] = MinecraftServer.currentTick;
            this.c.keys = FORWARD;
        });
        for (int round = 0; round < 3; round++) {
            this.step(round == 0 ? 1 : 6, id + ": stall " + round, () -> {
                stallStartZ[0] = Double.NaN;
                this.c.blink = true;
                this.c.holdPongs = true;
                final Runnable sample = this.probe;
                this.probe = () -> {
                    sample.run();
                    // Windows sent before the stall still drain from the queue; once it is empty nothing may move the player
                    if (this.c.blink && this.c.auth.stream().queued() == 0) {
                        if (Double.isNaN(stallStartZ[0])) {
                            stallStartZ[0] = this.c.player.getZ();
                        }
                        stalledMaxZ[0] = Math.max(stalledMaxZ[0], this.c.player.getZ() - stallStartZ[0]);
                    }
                };
            });
            this.step(24, id + ": burst " + round, () -> {
                this.c.releaseHeld();
                final long before = this.c.auth.stats().windows();
                this.c.flush();
                maxBurst[0] = Math.max(maxBurst[0], this.c.auth.stats().windows() - before);
            });
        }
        this.judge(30, id, () -> {
            final Snap d = this.delta(this.c);
            final int bank = Revival.settings().authoritative().maxBankedTicks();
            final long elapsed = MinecraftServer.currentTick - t0[0];
            final long moving = d.windows() - d.exemptRefused();
            this.expect(maxBurst[0] <= 2L * bank + 1, "each burst ran at most " + maxBurst[0] + " windows at once (bank " + bank + ", owed at most " + bank + ")");
            this.expect(moving <= elapsed + bank + 1, "windows that moved " + moving + " in " + elapsed + " server ticks (real-time budget " + (elapsed + bank + 1)
                + ")");
            this.expect(stalledMaxZ[0] <= EPS, "the ServerPlayer stayed put during each stall once the windows sent before it ran (+" + f(stalledMaxZ[0]) + ")");
            final Vec3 ref = this.reference(this.c, start, FORWARD, (int) moving);
            this.expect(this.c.auth.simulation().z() <= ref.z + EPS, "the truth walked " + f(this.c.auth.simulation().z() - start[2]) + ", no further than "
                + moving + " ticks of walking (" + f(ref.z - start[2]) + ")");
            this.c.keys = NONE;
        });
        this.honestAfter(id, NONE, 20);
        this.finish(id);
    }

    // A client that freezes (no ticks, no packets, pongs held) for 1.2 s, then runs its 24 missed ticks at once
    private void frozenThenBurst() {
        final String id = "timer: freeze 1.2 s, then run the 24 missed ticks at once";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        final long[] t0 = {0};
        final long[] burst = {0};
        this.arm(id, () -> {
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            t0[0] = MinecraftServer.currentTick;
            this.c.rate = 0.0;
            this.c.holdPongs = true;
            this.c.blink = true;
        });
        this.step(24, id + ": 24 ticks at once", () -> {
            this.c.releaseHeld();
            this.c.keys = FORWARD;
            for (int i = 0; i < 24; i++) {
                this.c.clientTick();
            }
            final long before = this.c.auth.stats().windows();
            this.c.flush();
            burst[0] = this.c.auth.stats().windows() - before;
            this.c.rate = 20.0;
            this.c.keys = NONE;
        });
        this.judge(30, id, () -> {
            final Snap d = this.delta(this.c);
            final int bank = Revival.settings().authoritative().maxBankedTicks();
            final long elapsed = MinecraftServer.currentTick - t0[0];
            final long moving = d.windows() - d.exemptRefused();
            this.expect(burst[0] <= 2L * bank + 1, "the burst ran " + burst[0] + " of 24 at once (bank " + bank + ", owed at most " + bank + ")");
            this.expect(moving <= elapsed + bank + 1, "windows that moved " + moving + " in " + elapsed + " server ticks (budget " + (elapsed + bank + 1) + ")");
            this.note("refused " + d.refused() + ", queued " + this.c.auth.stream().queued() + ", truth walked " + f(this.c.auth.simulation().z() - start[2]));
        });
        this.honestAfter(id, NONE, 20);
        this.finish(id);
    }

    // Selective pongs: skip only the ping of the bundle that carries a knock, answer every later one
    private void skipKnockPing() {
        final String id = "pongs: skip only the knock's ping, answer the rest";
        this.begin(id, () -> {
        });
        final double[] start = new double[6];
        final double[] knockVz = {0.0};
        this.arm(id, () -> {
            this.probe = () -> {
                this.track.sample(this.c);
                knockVz[0] = Math.max(knockVz[0], this.c.auth.simulation().vz());
            };
            System.arraycopy(this.state(this.c), 0, start, 0, 6);
            this.c.forcePosition = true;
            this.c.ignoreMotion = true;
            this.c.skipKnockPing = true;
            this.knock(this.c, 0.0, 0.42, 0.6);
        });
        this.judge(10, id, () -> {
            final Snap d = this.delta(this.c);
            final String reason = this.kicks.getOrDefault(this.c.player.getUUID(), "");
            this.expect(this.c.skipped > 0, "the knock's ping was skipped (" + this.c.skipped + ")");
            this.expect(this.c.kicked && reason.contains("transaction"), "kicked: '" + reason + "' (skipped " + this.c.auth.transactions().skippedCount()
                + ", violations " + d.txViolations() + ")");
            // Instant kick at the skipped id (design §15.8): the cheater is gone before it could use the knock it dodged
            this.note("the truth's velocity before the kick: vz " + f(knockVz[0]) + ", z +" + f(this.track.truthMaxZ - start[2]));
        });
        this.finish(id);
    }

    // A knock-aware lag switch: pongs at once, except a knock's bundle, held 8 ticks (and everything after it, in order)
    private void knockAwareLagSwitch() {
        final String id = "pongs: hold only a knock's pong 8 ticks (knock-aware lag switch), three knocks";
        this.begin(id, () -> {
        });
        final List<String> lands = new ArrayList<>();
        final int[] sent = {0};
        final int[] landed = {-1};
        final boolean[] late = {false};
        this.arm(id, () -> {
            this.c.forcePosition = true;
            this.c.knockHold = 8;
        });
        for (int i = 0; i < 3; i++) {
            final int n = i;
            this.step(n == 0 ? 2 : 30, id + ": knock " + n, () -> {
                sent[0] = this.c.auth.stream().currentTick();
                landed[0] = -1;
                this.knock(this.c, 0.0, 0.42, 0.6);
                this.probe = () -> {
                    this.track.sample(this.c);
                    if (landed[0] < 0 && this.c.auth.simulation().vz() > 0.2) {
                        landed[0] = this.c.auth.tick();
                    }
                };
            });
            this.step(25, id + ": knock " + n + " landed", () -> {
                final int deadline = this.deadline(this.c);
                final int after = landed[0] - sent[0];
                lands.add("+" + after + " (deadline " + deadline + ")");
                late[0] |= landed[0] < 0 || after > deadline + 1;
            });
        }
        this.judge(1, id, () -> {
            final Snap d = this.delta(this.c);
            this.expect(!late[0], "each knock landed by its deadline, not the client's +8: " + String.join(", ", lands));
            this.expect(d.forced() >= 3, "forced entries " + d.forced());
            this.note("rtt base " + this.c.auth.transactions().rttBaseNanos() / 1_000_000L + " ms, corrections " + d.corrections());
        });
        this.honestAfterReset(id);
        this.finish(id);
    }

    // =================================================================================================================
    // Solid entities
    // =================================================================================================================

    // The client walks up onto a boat, then the server moves the boat away and the client keeps it in its own world
    private void boatMovedAway() {
        final String id = "solids: stand on a boat the server moved away (the client keeps its copy)";
        this.begin(id, () -> {
        });
        final Entity[] boat = {null};
        final int[] high = {0};
        this.step(1, id + ": a boat ahead", () -> boat[0] = this.spawn(EntityTypes.OAK_BOAT, this.ox + 0.5, this.gy, this.oz + 2.6));
        this.step(5, id + ": walk up onto it", () -> this.c.keys = FORWARD);
        this.step(8, id + ": stand", () -> this.c.keys = NONE);
        this.step(15, id + ": the server moves it 4 blocks aside, the client ignores that", () -> {
            final double top = boat[0].getBoundingBox().maxY;
            this.honestExpect(Math.abs(this.c.auth.simulation().y() - top) < 1.0E-6 && Math.abs(this.c.model.y() - top) < 1.0E-6,
                "control: client and truth stand on the boat (truth y +" + f(this.c.auth.simulation().y() - this.gy) + ", client +" + f(this.c.model.y() - this.gy)
                    + ", corrections " + this.c.auth.stats().corrections() + ")");
            this.base = this.snap(this.c);
            this.track = new Track();
            this.probe = () -> this.track.sample(this.c);
            this.c.ignoreSolids = true;
            this.c.forcePosition = true;
            boat[0].snapTo(this.ox + 4.5, this.gy, this.oz + 2.6, 0.0F, 0.0F);
            this.probe = () -> {
                this.track.sample(this.c);
                if (this.c.claimY >= this.gy + 0.5 && this.c.auth.simulation().y() < this.gy + 0.5) {
                    high[0]++;
                }
            };
        });
        this.judge(20, id, () -> {
            final Snap d = this.delta(this.c);
            final Simulation s = this.c.auth.simulation();
            this.expect(high[0] >= 1, "the client claimed the boat's old top for " + high[0] + " ticks after the move, then took the corrections");
            this.expect(Math.abs(s.y() - this.gy) <= EPS && s.onGround(), "the truth fell to the floor (+" + f(s.y() - this.gy) + ")");
            this.expect(Math.abs(this.c.player.getY() - this.gy) <= EPS, "the ServerPlayer too (+" + f(this.c.player.getY() - this.gy) + ")");
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfterReset(id);
        this.finish(id);
    }

    // A ceiling keeps the walker off the boat's top: a boat the server moved into the lane blocks it
    private void boatMovedIntoLane() {
        final String id = "solids: walk through a boat the server moved into the lane (the client ignores the move)";
        this.begin(id, () -> this.blocks(this.ox - 2, this.ox + 2, this.gy + 2, this.gy + 2, this.oz - 1, this.oz + 8, Blocks.STONE.defaultBlockState()));
        final Entity[] boat = {null};
        this.step(1, id + ": a boat aside", () -> boat[0] = this.spawn(EntityTypes.OAK_BOAT, this.ox + 4.5, this.gy, this.oz + 3.0));
        final double[] face = {0.0};
        this.step(10, id + ": it moves into the lane, the client ignores it and walks", () -> {
            this.base = this.snap(this.c);
            this.track = new Track();
            this.probe = () -> this.track.sample(this.c);
            this.c.ignoreSolids = true;
            this.c.forcePosition = true;
            boat[0].snapTo(this.ox + 0.5, this.gy, this.oz + 3.0, 0.0F, 0.0F);
            face[0] = boat[0].getBoundingBox().minZ;
        });
        this.step(3, id + ": walk", () -> this.c.keys = FORWARD);
        this.judge(25, id, () -> {
            final Snap d = this.delta(this.c);
            this.c.keys = NONE;
            this.expect(this.track.claimMaxZ >= face[0] + 1.0, "claimed " + f(this.track.claimMaxZ - face[0]) + " past the boat's face");
            this.expect(this.track.truthMaxZ <= face[0] - 0.3 + 1.0E-7, "the truth stopped at the boat (" + f(this.track.truthMaxZ - (face[0] - 0.3)) + " past touching)");
            this.expect(this.track.bodyMaxZ <= face[0] - 0.3 + 1.0E-7, "the ServerPlayer too (" + f(this.track.bodyMaxZ - (face[0] - 0.3)) + ")");
            this.expect(d.corrections() > 0, "corrections " + d.corrections());
        });
        this.honestAfterReset(id);
        this.finish(id);
    }

    // A shulker spawned ahead while the client holds its pongs: the spawn reaches the truth at its deadline, before the
    // walker gets there; the client's own world never takes it
    private void shulkerSpawnedPongsHeld() {
        final String id = "solids: walk through a shulker spawned ahead, pongs held (the spawn's window)";
        this.begin(id, () -> {
        });
        final double face = 3.0; // from oz: the arena is placed at setup
        this.arm(id, () -> {
            this.c.withholdPongs = true; // it sees the spawn and keeps its pong back
            this.c.ignoreSolids = true;
            this.c.forcePosition = true;
            this.c.keys = FORWARD;
            this.spawn(EntityTypes.SHULKER, this.ox + 0.5, this.gy, this.oz + 3.5);
        });
        this.step(14, id + ": pongs released", () -> this.c.releaseHeld());
        this.judge(16, id, () -> {
            final Snap d = this.delta(this.c);
            this.c.keys = NONE;
            final double at = this.oz + face;
            this.expect(this.track.claimMaxZ >= at - 0.3 + 0.5, "claimed " + f(this.track.claimMaxZ - (at - 0.3)) + " into the shulker's box");
            this.expect(this.track.truthMaxZ <= at - 0.3 + 1.0E-7, "the truth stopped at the shulker (" + f(this.track.truthMaxZ - (at - 0.3)) + " past touching)");
            this.expect(this.track.bodyMaxZ <= at - 0.3 + 1.0E-7, "the ServerPlayer too (" + f(this.track.bodyMaxZ - (at - 0.3)) + ")");
            this.note("forced " + d.forced() + ", corrections " + d.corrections() + ", mover exemptions " + d.movers());
        });
        this.honestAfterReset(id);
        this.finish(id);
    }

    private void shulkerDropped() {
        final String id = "solids: phase through a shulker the client dropped from its world";
        this.begin(id, () -> {
        });
        final Entity[] shulker = {null};
        this.step(1, id + ": a shulker ahead", () -> shulker[0] = this.spawn(EntityTypes.SHULKER, this.ox + 0.5, this.gy, this.oz + 3.5));
        final double face = 3.0; // from oz: the arena is placed at setup
        this.step(10, id + ": drop it, walk", () -> {
            this.base = this.snap(this.c);
            this.track = new Track();
            this.probe = () -> this.track.sample(this.c);
            this.c.world.solids.apply(new ClientboundRemoveEntitiesPacket(shulker[0].getId()));
            this.c.ignoreSolids = true;
            this.c.forcePosition = true;
            this.c.keys = FORWARD;
        });
        this.judge(25, id, () -> {
            final Snap d = this.delta(this.c);
            this.c.keys = NONE;
            final double at = this.oz + face;
            this.expect(this.track.claimMaxZ >= at - 0.3 + 0.5, "claimed " + f(this.track.claimMaxZ - (at - 0.3)) + " into the shulker's box");
            this.expect(this.track.truthMaxZ <= at - 0.3 + 1.0E-7, "the truth stopped at the shulker (" + f(this.track.truthMaxZ - (at - 0.3)) + " past touching)");
            this.expect(d.corrections() > 0 && d.movers() == 0, "corrections " + d.corrections() + ", mover exemptions " + d.movers());
        });
        this.honestAfter(id, NONE, 0);
        this.finish(id);
    }

    // =================================================================================================================
    // Pearls and the teleport hold
    // =================================================================================================================

    // projectiles §6: a pearl also stops at the thrower's client blocks (what it was sent, fake walls included)
    private void pearl(final boolean fakeWall) {
        final String id = fakeWall ? "pearls: through a fake wall the client dropped from its world" : "pearls: a throw down the lane (control)";
        this.begin(id, () -> {
        });
        final double wall = 5.0; // from oz: the arena is placed at setup
        this.step(1, id + ": pearls", () -> this.give(this.c, 0, new ItemStack(Items.ENDER_PEARL, 4)));
        if (fakeWall) {
            this.step(2, id + ": a fake wall the client ignores", () -> {
                this.c.ignoreBlockUpdates = true;
                for (int x = this.ox - 3; x <= this.ox + 3; x++) {
                    for (int y = 0; y < 4; y++) {
                        this.c.player.connection.send(new ClientboundBlockUpdatePacket(new BlockPos(x, this.gy + y, this.oz + (int) wall), Blocks.STONE.defaultBlockState()));
                    }
                }
            });
        }
        final double[] start = new double[1];
        this.step(4, id + ": throw", () -> {
            this.base = this.snap(this.c);
            this.track = new Track();
            this.probe = () -> this.track.sample(this.c);
            start[0] = this.c.auth.simulation().z();
            this.c.yaw = 0.0F;
            this.c.pitch = 0.0F;
            this.c.use();
        });
        this.judge(40, id, () -> {
            final Simulation s = this.c.auth.simulation();
            final double at = this.oz + wall;
            if (fakeWall) {
                this.expect(this.track.truthMaxZ <= at - 0.3 + 1.0E-7, "the truth landed short of the fake wall (z max " + f(this.track.truthMaxZ - (at - 0.3))
                    + " past touching; now +" + f(s.z() - start[0]) + " from the throw)");
                this.expect(this.track.bodyMaxZ <= at - 0.3 + 1.0E-7, "the ServerPlayer too (" + f(this.track.bodyMaxZ - (at - 0.3)) + ")");
            } else {
                this.label = "CONTROL";
                this.honestExpect(s.z() >= at + 1.0 && Math.abs(this.c.model.z() - s.z()) < 1.0E-6, "the pearl carried the thrower past where the wall would be (+"
                    + f(s.z() - start[0]) + ", client and truth together)");
            }
            this.c.ignoreBlockUpdates = false;
        });
        if (fakeWall) {
            this.step(1, id + ": the client takes the wall back", () -> this.c.world.clear());
            this.honestAfter(id, NONE, 0);
        }
        this.finish(id);
    }

    // pvp-issues PR-8: a teleport into a chunk the client lacks waits for that chunk; the client never acks a chunk batch,
    // so the chunk sender stalls for it: the hold mustn't depend on the client
    private void teleportHoldStalled() {
        final String id = "teleport hold: never ack a chunk batch, then a 400-block teleport";
        this.begin(id, () -> {
        });
        final long[] at = {0};
        final int[] mark = {0};
        final long[] held = {0, 0};
        final long[] arrived = {-1};
        this.arm(id, () -> {
            this.c.noChunkAcks = true;
            this.c.forcePosition = true;
        });
        this.step(5, id + ": teleport", () -> {
            at[0] = MinecraftServer.currentTick;
            mark[0] = this.c.positions.size();
            held[0] = this.c.auth.stats().teleportsHeld();
            held[1] = this.c.auth.stats().teleportChunksSent();
            this.c.player.connection.teleport(this.ox + 400.5, this.gy, this.oz + 0.5, 0.0F, 0.0F);
            this.probe = () -> {
                if (arrived[0] < 0) {
                    for (int i = mark[0]; i < this.c.positions.size(); i++) {
                        if (!Reconciler.ours(this.c.positions.get(i).id())) {
                            arrived[0] = MinecraftServer.currentTick;
                        }
                    }
                }
            };
        });
        this.judge(110, id, () -> {
            final Simulation s = this.c.auth.simulation();
            final AuthoritativeSettings settings = Revival.settings().authoritative();
            final long wait = arrived[0] < 0 ? -1 : arrived[0] - at[0];
            this.note("held " + (this.c.auth.stats().teleportsHeld() - held[0]) + ", chunks sent by the netcode " + (this.c.auth.stats().teleportChunksSent() - held[1]));
            this.expect(arrived[0] >= 0 && wait <= settings.teleportHoldMaxTicks() + 2, "the teleport went out " + wait + " server ticks later (hold cap "
                + settings.teleportHoldMaxTicks() + ", chunk send after " + settings.teleportChunkSendTicks() + ")");
            this.expect(Math.abs(s.x() - (this.ox + 400.5)) < 1.0E-6 && Math.abs(s.y() - this.gy) < 1.0E-6, "the truth stands at the destination (x +"
                + f(s.x() - this.ox) + ", y +" + f(s.y() - this.gy) + ")");
            this.expect(!this.c.kicked, "not kicked (" + this.kicks.getOrDefault(this.c.player.getUUID(), "") + ")");
            this.c.noChunkAcks = false;
        });
        this.finish(id);
    }

    // pvp-issues PR-8 with a pearl: a long throw lands past the chunks the client has been sent (view distance 2 here),
    // and the client never acks a chunk batch, so the chunk sender stalls for it
    private void pearlIntoUnsentChunk() {
        final String id = "pearls: a long throw into a chunk the client lacks, chunk batches never acked";
        this.begin(id, () -> {
        });
        final double[] start = new double[1];
        final long[] held = new long[2];
        this.step(1, id + ": pearls", () -> this.give(this.c, 0, new ItemStack(Items.ENDER_PEARL, 4)));
        this.step(4, id + ": throw 45 degrees up", () -> {
            this.base = this.snap(this.c);
            this.c.noChunkAcks = true;
            start[0] = this.c.auth.simulation().z();
            held[0] = this.c.auth.stats().teleportsHeld();
            held[1] = this.c.auth.stats().teleportChunksSent();
            this.c.yaw = 0.0F;
            this.c.pitch = -45.0F;
            this.c.use();
        });
        this.judge(80, id, () -> {
            final Snap d = this.delta(this.c);
            final Simulation s = this.c.auth.simulation();
            final double ground = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, net.minecraft.util.Mth.floor(s.x()), net.minecraft.util.Mth.floor(s.z()));
            this.note("landed " + f(s.z() - start[0]) + " out; teleports held " + (this.c.auth.stats().teleportsHeld() - held[0]) + ", chunks the netcode sent "
                + (this.c.auth.stats().teleportChunksSent() - held[1]) + " (0: the landing chunk had been sent before the throw)");
            this.expect(s.z() - start[0] > 16.0, "the pearl carried the thrower " + f(s.z() - start[0]) + " out");
            this.expect(Math.abs(s.y() - ground) < 1.0E-6 && s.onGround(), "the truth stands on the floor there (y " + f(s.y() - ground) + " from it)");
            this.expect(!this.c.kicked, "not kicked (" + this.kicks.getOrDefault(this.c.player.getUUID(), "") + "), corrections " + d.corrections());
            this.c.noChunkAcks = false;
        });
        this.finish(id);
    }

    // =================================================================================================================
    // Projectiles: the shooter's view (projectiles §2)
    // =================================================================================================================

    // A rod or snowball's player hits use the shooter's mirror after its newest tick, within its rewind cap; past the cap
    // the target's server box. Backtrack: pongs held 500 ms while the target steps 3 aside, thrown where it was
    private void projectile(final net.minecraft.world.item.Item item, final boolean backtrack) {
        final String what = item == Items.SNOWBALL ? "snowball" : "rod";
        final String id = backtrack ? "projectiles: a " + what + " at a target backtracked 500 ms (pongs held)" : "projectiles: an honest " + what + " at 6 blocks (control)";
        this.combatJoin(id, 6.3);
        final int[] mark = {0};
        this.step(1, id + ": " + what, () -> this.give(this.c, 0, new ItemStack(item, item == Items.SNOWBALL ? 16 : 1)));
        if (backtrack) {
            this.step(5, id + ": hold pongs, the target steps 3 aside", () -> {
                this.c.holdPongs = true;
                this.place(3.0, 6.3);
            });
        }
        this.step(backtrack ? 10 : 5, id + ": throw where the client shows it", () -> {
            this.base = this.snap(this.c);
            mark[0] = this.projectileHits.size();
            this.c.yaw = 0.0F;
            this.c.pitch = -2.0F;
            this.c.use();
        });
        this.step(10, id + ": release", () -> this.c.releaseHeld());
        this.judge(5, id, () -> {
            int hits = 0;
            for (int i = mark[0]; i < this.projectileHits.size(); i++) {
                if (this.projectileHits.get(i)[0] == this.c.player.getId() && this.projectileHits.get(i)[1] == this.v().player.getId()) {
                    hits++;
                }
            }
            if (backtrack) {
                this.expect(hits == 0, "missed: " + hits + " hits (the target is at x +" + f(this.v().player.getX() - this.ox - 0.5) + ")");
            } else {
                this.label = "CONTROL";
                this.honestExpect(hits == 1, "the " + what + " hit: " + hits);
            }
        });
        this.finish(id);
    }

    // =================================================================================================================
    // Client profile
    // =================================================================================================================

    // design §15.8: profiles are informational; a client claiming Lunar gets nothing looser
    private void lunarSpoof() {
        final String id = "client profile: claims Lunar (lunarclient brand, lunar:apollo registered)";
        this.combatJoin(id, 2.8);
        final long[] before = new long[3];
        this.step(1, id + ": brand and channel", () -> {
            final FriendlyByteBuf brand = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            brand.writeUtf("lunarclient:v2.18.3-2511");
            final byte[] bytes = new byte[brand.readableBytes()];
            brand.readBytes(bytes);
            this.c.actions.add(new ServerboundCustomPayloadPacket(new DiscardedPayload(BRAND, bytes)));
            this.c.actions.add(new ServerboundCustomPayloadPacket(new DiscardedPayload(REGISTER, "lunar:apollo".getBytes(StandardCharsets.US_ASCII))));
            this.give(this.c, 0, new ItemStack(Items.DIAMOND_SWORD));
        });
        this.step(30, id + ": reach 4.5", () -> {
            this.open(id);
            final ClientProfile profile = this.c.auth.profile();
            this.expect(profile.brand() == ClientProfile.Brand.LUNAR && profile.apollo(), "the server reads it as Lunar (brand " + profile.brand() + " '"
                + profile.rawBrand() + "', apollo " + profile.apollo() + ")");
            this.base = this.snap(this.c);
            before[0] = this.attackLog.size();
            this.place(0.0, 4.8);
        });
        this.step(12, id + ": attack at 4.5", () -> {
            this.c.yaw = 0.0F;
            this.c.pitch = 0.0F;
            this.c.click(this.v(), true);
        });
        this.step(3, id + ": block, then attack while blocking", () -> {
            this.expect(this.attacksBy(this.c, (int) before[0]) == 0, "reach 4.5 refused (landed " + this.attacksBy(this.c, (int) before[0]) + ")");
            this.place(0.0, 2.8);
        });
        this.step(12, id + ": block", () -> this.c.use());
        this.step(3, id + ": attack while blocking, two inputs in one tick", () -> {
            before[1] = this.attackLog.size();
            before[2] = this.c.auth.stream().violations();
            this.c.click(this.v(), true);
            // A jump pressed and let go inside one tick: two PlayerInput packets, the last one the tick's (no keys)
            this.c.actions.add(new ServerboundPlayerInputPacket(new Input(false, false, false, false, true, false, false)));
            this.c.actions.add(new ServerboundPlayerInputPacket(NONE));
        });
        this.judge(4, id, () -> {
            this.expect(this.attacksBy(this.c, (int) before[1]) == 0, "autoblock refused (landed " + this.attacksBy(this.c, (int) before[1]) + ", blocking "
                + this.c.player.isUsingItem() + ")");
            this.expect(this.c.auth.stream().violations() > before[2], "the second input counted as for any client (" + (this.c.auth.stream().violations()
                - before[2]) + ")");
            this.c.release();
        });
        this.step(1, id + ": verdict", () -> {
            this.record();
            this.leaveAll();
        });
    }

    // =================================================================================================================
    // Combat plumbing (attacker c, victim h, as AuthCheatScenarios' combatJoin)
    // =================================================================================================================

    private Client v() {
        return this.h;
    }

    private void combatJoin(final String id, final double dz) {
        this.begin(id, () -> {
        });
        this.step(1, id + ": victim joins", () -> this.h = this.client("Victim", this.ox + 0.5, this.gy, this.oz + 0.5 + dz, 180.0F));
        this.step(WARM + 5, id + ": both under enforce", () -> {
            this.ready(this.h);
            this.tests.check(CombatWiring.book(this.c.auth).knows(this.h.player.getId()), this.scenario + ": the attacker's client has the victim");
        });
    }

    // Victim at (dx, dz) from the attacker's lane start; the attacker looks along +z
    private void place(final double dx, final double dz) {
        this.h.player.connection.teleport(this.ox + 0.5 + dx, this.gy, this.oz + 0.5 + dz, 180.0F, 0.0F);
    }

    private void attackCase(final String name, final double dx, final double dz, final @Nullable Runnable setup, final int expectLanded,
                            final @Nullable Runnable teardown) {
        final int[] mark = {0};
        this.step(1, name + ": place", () -> {
            this.open(name);
            this.place(dx, dz);
            if (setup != null) {
                setup.run();
            }
        });
        this.step(12, name + ": attack", () -> {
            this.base = this.snap(this.c);
            mark[0] = this.attackLog.size();
            this.c.yaw = 0.0F;
            this.c.pitch = 0.0F;
            this.c.click(this.h, true);
        });
        this.step(3, name + ": judge", () -> {
            final Snap d = this.delta(this.c);
            final int landed = this.attacksBy(this.c, mark[0]);
            final String detail = "landed " + landed + " (judged valid " + d.attacksAccepted() + ", invalid " + d.attacksRejected() + ", verdict "
                + this.c.auth.lastHitVerdict() + ")";
            if (expectLanded > 0) {
                this.label = "CONTROL";
                this.honestExpect(landed == 1, "an honest hit lands: " + detail);
            } else {
                this.expect(landed == 0, "refused: " + detail);
            }
            if (teardown != null) {
                teardown.run();
            }
            this.record();
        });
    }

    // =================================================================================================================
    // Neue plumbing
    // =================================================================================================================

    // A cheater (south, facing +z) and an honest partner (north, facing -z) `gap` apart, each with a diamond sword in
    // slot 0; walls behind either; the cheater ticks first in every server tick
    private void pair(final String id, final double gap, final boolean southWall, final boolean northWall) {
        this.step(1, id + ": arena and join", () -> {
            this.open(id);
            this.probe = () -> {
            };
            this.track = new Track();
            this.clearArena();
            this.walls(southWall, northWall, gap);
            this.c = this.client("Cheat", this.ox + 0.5, this.gy, this.oz + 0.3, 0.0F);
            this.h = this.client("Honest", this.ox + 0.5, this.gy, this.oz + 0.3 + gap, 180.0F);
        });
        this.step(WARM + 5, id + ": under enforce, swords", () -> {
            this.ready(this.c);
            this.ready(this.h);
            this.give(this.c, 0, new ItemStack(Items.DIAMOND_SWORD));
            this.give(this.h, 0, new ItemStack(Items.DIAMOND_SWORD));
            reset(this.c.player);
            reset(this.h.player);
        });
    }

    private void walls(final boolean south, final boolean north, final double gap) {
        if (south) {
            this.blocks(this.ox - 1, this.ox + 1, this.gy, this.gy + 2, this.oz - 1, this.oz - 1, Blocks.STONE.defaultBlockState());
        }
        if (north) {
            final int z = net.minecraft.util.Mth.floor(this.oz + 0.3 + gap + 0.3 + 1.0E-6);
            this.blocks(this.ox - 1, this.ox + 1, this.gy, this.gy + 2, z, z, Blocks.STONE.defaultBlockState());
        }
    }

    // A fresh hit's window, without the hit: shut for 10 more ticks to a hit no harder than 8 (the diamond sword's)
    private void shutWindow(final ServerPlayer victim) {
        victim.damageCooldownTime = 20;
        victim.lastHurt = 8.0F;
    }

    // Full health, the damage window open, no 1.7 shadow, no regeneration (NeueLiveScenarios.reset)
    private static void reset(final ServerPlayer player) {
        player.setHealth(20.0F);
        player.invulnerableDuration = 20;
        player.damageCooldownTime = 0;
        player.hurtTime = 0;
        player.lastHurt = 0.0F;
        player.revivalDamageWindows = null;
        player.revivalMotionShadow.set(new Vec3(0.0, -0.0784000015258789, 0.0));
        player.getFoodData().setFoodLevel(17);
        player.getFoodData().setSaturation(0.0F);
    }

    // Turns `x` to the middle of `target`'s box as x's client shows it, from the eye its next pick casts from
    private void aim(final Client x, final Client target) {
        final RemoteMirror shown = CombatWiring.book(x.auth).mirror(target.player.getId());
        final double tx;
        final double ty;
        final double tz;
        if (shown != null && shown.present()) {
            tx = (shown.minX() + shown.maxX()) * 0.5;
            ty = (shown.minY() + shown.maxY()) * 0.5;
            tz = (shown.minZ() + shown.maxZ()) * 0.5;
        } else {
            tx = target.player.getX();
            ty = target.player.getY() + 0.9;
            tz = target.player.getZ();
        }
        final double dx = tx - x.model.x();
        final double dy = ty - (x.model.y() + x.model.eyeHeight());
        final double dz = tz - x.model.z();
        x.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        x.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    private void rules(final String name) {
        final CombatRules rules = LiveRules.apply(this.level, name);
        this.tests.check(LiveRules.is(rules, name) && Revival.settings().swordBlocking().enabled(),
            "cheat suite 2: legacy-combat.rules is " + name + " after /revival reload (sword blocking " + Revival.settings().swordBlocking().enabled() + ")");
    }

    private int parries(final Client parrier) {
        return this.parriesBy.getOrDefault(parrier.player.getUUID(), 0);
    }

    // Swing animations `viewer` was sent for `swinger`
    private int swingsSeen(final Client viewer, final Client swinger) {
        int n = 0;
        for (final FakeClient.Received received : viewer.fake.received) {
            if (received.packet() instanceof final ClientboundSwingAnimationPacket swing && swing.entityId() == swinger.player.getId()) {
                n++;
            }
        }
        return n;
    }

    // The first attack `x` delivered after server tick `after`, from log index `from`: {entity id, server tick, window}
    private long @Nullable [] firstAttackAfter(final Client x, final int from, final long after) {
        if (after == Long.MIN_VALUE) {
            return null;
        }
        for (int i = from; i < this.attackLog.size(); i++) {
            final long[] entry = this.attackLog.get(i);
            if (entry[0] == x.player.getId() && entry[1] > after) {
                return entry;
            }
        }
        return null;
    }

    private int attacksBy(final Client x, final int from) {
        int n = 0;
        for (int i = from; i < this.attackLog.size(); i++) {
            if (this.attackLog.get(i)[0] == x.player.getId()) {
                n++;
            }
        }
        return n;
    }

    // =================================================================================================================
    // Scenario plumbing
    // =================================================================================================================

    private void setup() {
        final org.bukkit.Location spawn = this.level.getWorld().getSpawnLocation();
        this.ox = spawn.getBlockX() - 24;
        this.oz = spawn.getBlockZ() - 8;
        this.gy = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, this.ox, this.oz);
        // Health is a measure here: no natural regeneration, and peaceful (the flat world spawns slimes otherwise)
        this.difficulty = this.level.getDifficulty();
        this.regeneration = this.level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
        this.server.setDifficulty(this.level, Difficulty.PEACEFUL, null, true);
        this.level.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, false, this.level);
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
                final ServerPlayer player = ((org.bukkit.craftbukkit.entity.CraftPlayer) attack.getPlayer()).getHandle();
                this.attackLog.add(new long[] {player.getId(), MinecraftServer.currentTick, player.revivalAuth.tick()});
                final NeueState neue = NeueCombat.state(player);
                if (neue.active() && neue.buffered() && attack.getAttacked() instanceof final org.bukkit.craftbukkit.entity.CraftEntity target) {
                    final gg.hcfrevival.revival.auth.combat.AttackerSnapshot eye = CombatWiring.snapshot(player.revivalAuth);
                    final AABB box = target.getHandle().getBoundingBox();
                    final double ex = Math.max(Math.max(box.minX - eye.eyeX(), 0.0), eye.eyeX() - box.maxX);
                    final double ey = Math.max(Math.max(box.minY - eye.eyeY(), 0.0), eye.eyeY() - box.maxY);
                    final double ez = Math.max(Math.max(box.minZ - eye.eyeZ(), 0.0), eye.eyeZ() - box.maxZ);
                    this.landingView = neue.distance();
                    this.landingTruth = Math.sqrt(ex * ex + ey * ey + ez * ez);
                }
                if (TRACE) {
                    this.tests.getLogger().info("REVIVAL_CHEAT2_TRACE attack t" + MinecraftServer.currentTick + " " + attack.getPlayer().getName() + " -> "
                        + attack.getAttacked().getName() + " window " + player.revivalAuth.tick() + " buffered " + NeueCombat.state(player).buffered());
                }
            }
        }, this.tests);
        plugins.registerEvent(RevivalParryEvent.class, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (event instanceof final RevivalParryEvent parry && !parry.isCancelled()) {
                this.parriesBy.merge(parry.getPlayer().getUniqueId(), 1, Integer::sum);
                this.lastParryTick = MinecraftServer.currentTick;
                this.lastParrier = ((org.bukkit.craftbukkit.entity.CraftPlayer) parry.getPlayer()).getHandle().getId();
                this.lastParryAttacker = ((org.bukkit.craftbukkit.entity.CraftPlayer) parry.getAttacker()).getHandle().getId();
                if (TRACE) {
                    this.tests.getLogger().info("REVIVAL_CHEAT2_TRACE parry t" + MinecraftServer.currentTick + " " + parry.getPlayer().getName() + " parried "
                        + parry.getAttacker().getName());
                }
            }
        }, this.tests);
        plugins.registerEvent(EntityDamageByEntityEvent.class, this.listener, EventPriority.LOWEST, (ignored, event) -> {
            if (event instanceof final EntityDamageByEntityEvent damage && this.cancelDamageTo.contains(damage.getEntity().getUniqueId())) {
                damage.setCancelled(true);
            }
        }, this.tests);
        plugins.registerEvent(EntityKnockbackEvent.class, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (event instanceof final EntityKnockbackEvent knock && knock.getEntity() instanceof final org.bukkit.craftbukkit.entity.CraftPlayer victim
                && victim.getHandle().getId() == this.knockVictim && this.h != null) {
                this.knockSeen = true;
                this.knockFrom[0] = this.h.player.getX();
                this.knockFrom[1] = this.h.player.getZ();
                this.knockTo[0] = victim.getHandle().getX();
                this.knockTo[1] = victim.getHandle().getZ();
            }
        }, this.tests);
        plugins.registerEvent(ProjectileHitEvent.class, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (event instanceof final ProjectileHitEvent hit && hit.getHitEntity() != null && hit.getEntity().getShooter() instanceof final org.bukkit.entity.Entity shooter) {
                this.projectileHits.add(new int[] {((org.bukkit.craftbukkit.entity.CraftEntity) shooter).getHandle().getId(),
                    ((org.bukkit.craftbukkit.entity.CraftEntity) hit.getHitEntity()).getHandle().getId()});
            }
        }, this.tests);
        plugins.registerEvent(AuthInputAnomalyEvent.class, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (event instanceof final AuthInputAnomalyEvent anomaly) {
                this.anomalies.merge(anomaly.getPlayer().getName() + " " + anomaly.getKind(), 1, Integer::sum);
            }
        }, this.tests);
        plugins.registerEvent(PlayerKickEvent.class, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (event instanceof final PlayerKickEvent kick) {
                final UUID uuid = kick.getPlayer().getUniqueId();
                this.kicks.put(uuid, PlainTextComponentSerializer.plainText().serialize(kick.reason()));
                for (final Client x : this.clients) {
                    if (x.player.getUUID().equals(uuid)) {
                        x.kicked = true;
                    }
                }
            }
        }, this.tests);
        this.driver = this.tests.getServer().getScheduler().runTaskTimer(this.tests, this::drive, 1L, 1L);
        this.tests.check(this.gy > this.level.getMinY(), "cheat suite 2: arena at " + this.ox + " " + this.gy + " " + this.oz);
    }

    private void drive() {
        for (final Client x : List.copyOf(this.clients)) {
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
        this.trace();
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
        this.level.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, this.regeneration, this.level);
        if (this.difficulty != null) {
            this.server.setDifficulty(this.level, this.difficulty, null, true);
        }
        this.tests.getLogger().info("REVIVAL_CHEAT2 ===== cheat suite 2: " + this.table.size() + " scenarios, " + this.failures.size() + " leaked =====");
        for (final String line : this.table) {
            this.tests.getLogger().info("REVIVAL_CHEAT2 " + line);
        }
        final List<String> names = new ArrayList<>();
        for (final String line : this.failures) {
            names.add(line.substring(0, line.indexOf(" | ")));
        }
        this.tests.check(this.failures.isEmpty(), "cheat suite 2: " + (this.table.size() - this.failures.size()) + " of " + this.table.size()
            + " blocked; leaked: " + String.join("; ", names));
    }

    private String next(final String prefix) {
        return "C2" + prefix + (++this.serial);
    }

    private Client client(final String prefix, final double x, final double y, final double z, final float yaw) {
        final Client client = new Client(this.next(prefix), x, y, z, yaw);
        this.cast.add(client);
        return client;
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
            this.clearArena();
            arena.run();
            this.c = this.client("Cheat", this.ox + 0.5, this.gy, this.oz + 0.5, 0.0F);
        });
        this.step(WARM, id + ": under enforce", () -> this.ready(this.c));
    }

    private void ready(final Client x) {
        this.ready(x, true);
    }

    // `turn`: the client sends its rotation once more, as any mouse movement does. Authority starts at a TickEnd after
    // the join teleport's accept, and nothing seeds the stream's rotation from the player (see rotationAtActivation)
    private void ready(final Client x, final boolean turn) {
        if (turn) {
            x.lastYaw = Float.NaN;
            x.lastPitch = Float.NaN;
        }
        this.tests.check(x.auth.mode() == AuthMode.ENFORCE && x.auth.simulation().seeded() && !x.kicked && x.auth.stats().simulated() > 0,
            this.scenario + ": " + x.name + " runs under enforce (mode " + x.auth.mode() + ", seeded " + x.auth.simulation().seeded() + ", simulated "
                + x.auth.stats().simulated() + ")");
    }

    private void arm(final String id, final Runnable arm) {
        this.step(1, id + ": cheat", () -> {
            this.base = this.snap(this.c);
            this.track = new Track();
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
                this.c.rate = 0.0;
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

    // An honest client again after a cheat on its own world of solid entities: its copies are dropped, and the server
    // puts it back at the lane's start, clear of what it no longer holds (an honest client would have taken every update)
    private void honestAfterReset(final String id) {
        this.step(1, id + ": back to the start", () -> {
            if (!this.c.kicked) {
                this.c.honest(NONE);
                this.c.player.connection.teleport(this.ox + 0.5, this.gy, this.oz + 0.5, 0.0F, 0.0F);
            }
        });
        this.honestAfter(id, NONE, 0);
    }

    private void finish(final String id) {
        this.step(1, id + ": verdict", () -> {
            this.record();
            this.leaveAll();
        });
    }

    private void open(final String name) {
        this.scenario = name;
        this.broken.clear();
        this.honestBroken.clear();
        this.evidence.setLength(0);
        this.label = "";
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
            this.tests.check(true, "cheat2 " + line);
        } else {
            this.failures.add(line);
            this.tests.getLogger().warning("REVIVAL_CHEAT2_LEAK " + line);
        }
        this.open(this.scenario);
    }

    private void leaveAll() {
        this.probe = () -> {
        };
        for (final Client x : List.copyOf(this.cast)) {
            x.leave();
        }
        this.cast.clear();
        for (final Entity entity : this.spawned) {
            entity.discard();
        }
        this.spawned.clear();
        this.c = null;
        this.h = null;
        this.cancelDamageTo.clear();
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

    private Snap snap(final Client x) {
        final AuthStats s = x.auth.stats();
        return new Snap(s.windows(), x.auth.stream().closedTicks(), s.compared(), s.exact(), s.corrections(), s.violations(), x.auth.stream().violations(),
            x.auth.transactions().violationCount(), s.forcedEntries(), x.auth.clock().refused(), s.exempt(AuthStats.EXEMPT_REFUSED), s.attacksAccepted(),
            s.attacksRejected(), s.attacksOverCap(), s.attacksOverCps(), s.externalMovers(), System.nanoTime());
    }

    private Snap delta(final Client x) {
        return this.snap(x).minus(this.base);
    }

    private double[] state(final Client x) {
        final Simulation s = x.auth.simulation();
        return new double[] {s.x(), s.y(), s.z(), s.vx(), s.vy(), s.vz()};
    }

    // AuthPlayer's: ceil(min(rttBase, rtt-ceiling-ms) / 50) + ack-jitter-ticks
    private int deadline(final Client x) {
        final long rtt = Math.min(x.auth.transactions().rttBaseNanos(), Revival.settings().authoritative().rttCeilingMs() * 1_000_000L);
        return (int) Math.ceil(rtt / 50_000_000.0) + Revival.settings().authoritative().ackJitterTicks();
    }

    /** An honest client's path from {@code start}: the port with a fresh client view, {@code n} ticks of {@code keys}, yaw 0. */
    private Vec3 reference(final Client x, final double[] start, final Input keys, final int n) {
        final PortSimulation ref = new PortSimulation(x.player);
        final ClientView view = new ClientView();
        view.seed(x.player);
        final WorldView world = new WorldView();
        world.setLevel(this.level, false);
        ref.seed(start[0], start[1], start[2], start[3], start[4], start[5], 0.0F, 0.0F, true, view, world);
        Input previous = NONE;
        for (int i = 0; i < n; i++) {
            ref.tick(keys, previous, 0.0F, 0.0F, ClientStream.SPRINT_NONE, -1, false, view, world);
            previous = keys;
        }
        return new Vec3(ref.x(), ref.y(), ref.z());
    }

    private void knock(final Client x, final double vx, final double vy, final double vz) {
        x.player.connection.send(new ClientboundSetEntityMotionPacket(x.player.getId(), new Vec3(vx, vy, vz)));
    }

    private void give(final Client x, final int slot, final ItemStack stack) {
        x.player.getInventory().setItem(slot, stack);
        x.player.inventoryMenu.broadcastChanges();
    }

    private Entity spawn(final EntityType<?> type, final double x, final double y, final double z) {
        final Entity entity = type.create(this.level, EntitySpawnReason.COMMAND);
        entity.snapTo(x, y, z, 0.0F, 0.0F);
        if (entity instanceof final net.minecraft.world.entity.Mob mob) {
            mob.setNoAi(true); // a shulker that never peeks, shoots or teleports
            mob.setPersistenceRequired();
        }
        this.level.addFreshEntity(entity);
        this.spawned.add(entity);
        return entity;
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

    // Arena ------------------------------------------------------------------------------------------------------------

    private void clearArena() {
        for (final Entity entity : this.spawned) {
            entity.discard();
        }
        this.spawned.clear();
        for (int x = this.ox - 6; x <= this.ox + 6; x++) {
            for (int z = this.oz - 6; z <= this.oz + 32; z++) {
                for (int y = this.gy - 3; y <= this.gy + 5; y++) {
                    this.set(x, y, z, y < this.gy ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private void blocks(final int x0, final int x1, final int y0, final int y1, final int z0, final int z1, final BlockState state) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    this.set(x, y, z, state);
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

    private static String f(final double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        if (value != 0.0 && Math.abs(value) < 1.0E-3) {
            return String.format(Locale.ROOT, "%.3e", value);
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** Counters of one client at one moment; {@link #minus} gives what a phase added. */
    private record Snap(long windows, long closed, long compared, long exact, long corrections, long violations, long streamViolations, long txViolations,
                        long forced, long refused, long exemptRefused, long attacksAccepted, long attacksRejected, long overCap, long overCps, long movers,
                        long nanos) {

        static final Snap ZERO = new Snap(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        Snap minus(final Snap o) {
            return new Snap(this.windows - o.windows, this.closed - o.closed, this.compared - o.compared, this.exact - o.exact, this.corrections - o.corrections,
                this.violations - o.violations, this.streamViolations - o.streamViolations, this.txViolations - o.txViolations, this.forced - o.forced,
                this.refused - o.refused, this.exemptRefused - o.exemptRefused, this.attacksAccepted - o.attacksAccepted, this.attacksRejected - o.attacksRejected,
                this.overCap - o.overCap, this.overCps - o.overCps, this.movers - o.movers, this.nanos - o.nanos);
        }
    }

    /** Extremes of truth, ServerPlayer and claim over a phase, sampled once per server tick after the clients ticked. */
    private static final class Track {
        double truthMaxY = Double.NEGATIVE_INFINITY;
        double truthMaxZ = Double.NEGATIVE_INFINITY;
        double truthMinZ = Double.POSITIVE_INFINITY;
        double bodyMaxZ = Double.NEGATIVE_INFINITY;
        double bodyMinZ = Double.POSITIVE_INFINITY;
        double claimMinY = Double.POSITIVE_INFINITY;
        double claimMaxZ = Double.NEGATIVE_INFINITY;
        double claimMinZ = Double.POSITIVE_INFINITY;

        void sample(final Client x) {
            final Simulation s = x.auth.simulation();
            this.truthMaxY = Math.max(this.truthMaxY, s.y());
            this.truthMaxZ = Math.max(this.truthMaxZ, s.z());
            this.truthMinZ = Math.min(this.truthMinZ, s.z());
            this.bodyMaxZ = Math.max(this.bodyMaxZ, x.player.getZ());
            this.bodyMinZ = Math.min(this.bodyMinZ, x.player.getZ());
            this.claimMinY = Math.min(this.claimMinY, x.claimY);
            this.claimMaxZ = Math.max(this.claimMaxZ, x.claimZ);
            this.claimMinZ = Math.min(this.claimMinZ, x.claimZ);
        }
    }

    // A solid entity's packets, as the client applies them to its copies (SolidMirrors)
    private static boolean solidPacket(final Packet<?> packet) {
        return packet instanceof ClientboundAddEntityPacket || packet instanceof ClientboundRemoveEntitiesPacket || packet instanceof ClientboundMoveEntityPacket
            || packet instanceof ClientboundEntityPositionSyncPacket || packet instanceof ClientboundTeleportEntityPacket
            || packet instanceof ClientboundSetEntityDataPacket || packet instanceof ClientboundUpdateAttributesPacket
            || packet instanceof ClientboundSetPassengersPacket;
    }

    // =================================================================================================================
    // The modified client (AuthCheatScenarios.Cheater, plus a Punch per attack, cooldowns, solid entities, chunk-batch
    // acks and per-ping reply policies)
    // =================================================================================================================

    /**
     * A modified 26.3 client on a {@link FakeClient} connection. Each client tick: what the server wrote is read in order
     * and handled as a client would at frame start (pings are answered when they are released, which a policy can delay,
     * hold or skip), the keybind actions are sent with the client's own predictions applied to its movement model, the
     * model ticks, then the solid entities it holds, a cheat may edit its state, and {@code sendChanges} goes out as
     * {@code LocalPlayer} would send it.
     */
    private final class Client {
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
        private int sequence;

        // Reply policies
        int pongDelay;
        /** Every clientbound packet reaches this client this many ticks late (a long route, not a cheat). */
        int latencyTicks;
        boolean holdPongs;
        /** Extra ticks before answering a given ping (in order: a later ping never overtakes an earlier one). */
        @Nullable IntUnaryOperator pingDelay;
        /** A bundle carrying a knock for this client: its ping is held this many ticks (the knock-aware lag switch). */
        int knockHold;
        /** A bundle carrying a knock for this client: its ping is never answered, later ones are. */
        boolean skipKnockPing;
        int skipped;
        private final Set<Integer> skipIds = new HashSet<>();
        /**
         * A cheat that reads every packet at once but withholds its pong replies (in order) while set or until
         * {@link #pongHoldUntil}: it sees a knock or a parry and chooses when the server learns it did. {@link #holdPongs}
         * instead models a link that delays the pings themselves, and everything behind them.
         */
        boolean withholdPongs;
        int pongHoldUntil;
        final List<ServerboundPongPacket> withheld = new ArrayList<>();
        boolean noChunkAcks;
        boolean ignoreMotion;
        boolean ignoreBlockUpdates;
        boolean ignoreSolids;
        int selfMotions;

        // The next ticks
        Input keys = NONE;
        @Nullable Input modelKeys;
        private Input previousModelKeys = NONE;
        float yaw;
        float pitch;
        final List<Packet<? super ServerGamePacketListener>> actions = new ArrayList<>();
        @Nullable Consumer<Client> before;
        boolean forcePosition;
        boolean blink;
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

        int ticks;
        boolean kicked;
        boolean gone;
        double claimX;
        double claimY;
        double claimZ;

        private final ClientView.EchoSink echo = new ClientView.EchoSink() {
            @Override
            public void sharedFlags(final byte flags) {
                Client.this.model.echoSharedFlags(flags);
            }

            @Override
            public void pose(final Pose pose) {
                Client.this.model.echoPose(pose);
            }

            @Override
            public void usingItem(final boolean using, final InteractionHand hand) {
                Client.this.model.echoUsingItem(using, hand);
            }
        };

        Client(final String name, final double x, final double y, final double z, final float yaw) {
            this.name = name;
            this.fake = new FakeClient(AuthCheatScenarios2.this.server, AuthCheatScenarios2.this.level, name);
            this.player = this.fake.player;
            this.auth = this.player.revivalAuth;
            final net.minecraft.server.ServerScoreboard scoreboard = AuthCheatScenarios2.this.server.getScoreboard();
            scoreboard.addPlayerToTeam(this.player.getScoreboardName(), scoreboard.getPlayerTeam(TEAM));
            this.auth.setOverride(AuthMode.ENFORCE);
            this.player.connection.teleport(x, y, z, yaw, 0.0F);
            this.view.seed(this.player);
            this.world.setLevel(AuthCheatScenarios2.this.level, false);
            this.world.solids.setSelf(this.player.getId());
            this.model = new PortSimulation(this.player);
            this.yaw = yaw;
            AuthCheatScenarios2.this.clients.add(this);
        }

        /** A click on an entity: {@code Attack}, then the {@code Punch} a vanilla client always sends after it. */
        void click(final Client target, final boolean punch) {
            this.actions.add(new ServerboundAttackPacket(target.player.getId()));
            if (punch) {
                this.actions.add(new ServerboundPunchPacket());
            }
        }

        void use() {
            this.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, ++this.sequence, this.yaw, this.pitch));
        }

        void release() {
            this.actions.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
        }

        void select(final int slot) {
            this.actions.add(new ServerboundSetCarriedItemPacket(slot));
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

        // A frame without a tick: packets are handled and pings answered
        void frame() {
            this.ingest();
            this.releaseInbox();
            final List<Packet<? super ServerGamePacketListener>> out = new ArrayList<>(this.drained);
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
            this.releaseInbox();
            if (this.before != null) {
                this.before.accept(this);
            }
            if (!this.seeded) {
                final Vec3 at = this.joined.position();
                this.model.seed(at.x, at.y, at.z, 0.0, 0.0, 0.0, this.yaw, this.pitch, true, this.view, this.world);
                this.seeded = true;
            }
            final List<Packet<? super ServerGamePacketListener>> out = new ArrayList<>(this.drained);
            this.drained.clear();
            if (!this.loaded && this.fake.chunks.contains(net.minecraft.world.level.ChunkPos.pack(net.minecraft.util.Mth.floor(this.model.x()) >> 4,
                net.minecraft.util.Mth.floor(this.model.z()) >> 4))) {
                out.add(new ServerboundPlayerLoadedPacket());
                this.loaded = true;
            }
            for (final Packet<? super ServerGamePacketListener> action : this.actions) {
                this.predict(action);
                out.add(action);
            }
            this.actions.clear();

            final Input moving = this.modelKeys != null ? this.modelKeys : this.keys;
            if (this.loaded) {
                this.model.tick(moving, this.previousModelKeys, this.yaw, this.pitch, ClientStream.SPRINT_NONE, -1, false, this.view, this.world);
            }
            this.previousModelKeys = moving;
            // ClientLevel.tickEntities: the solid entities that joined after the local player tick after it
            final AABB box = this.model.box();
            this.world.solids.tick(false, 1.0F, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            this.view.tick();

            if (!this.keys.equals(this.lastInput)) {
                out.add(new ServerboundPlayerInputPacket(this.keys));
                this.lastInput = this.keys;
            }
            final boolean sprinting = this.model.sprinting();
            if (sprinting != this.wasSprinting) {
                out.add(new ServerboundPlayerCommandPacket(this.player, sprinting
                    ? ServerboundPlayerCommandPacket.Action.START_SPRINTING : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
                this.wasSprinting = sprinting;
            }
            this.claimX = this.model.x();
            this.claimY = this.model.y();
            this.claimZ = this.model.z();
            final Packet<? super ServerGamePacketListener> move = this.sendPosition(new Vec3(this.claimX, this.claimY, this.claimZ), this.model.onGround(),
                this.model.horizontalCollision());
            if (move != null) {
                out.add(move);
            }
            out.add(ServerboundClientTickEndPacket.INSTANCE);
            this.ticks++;
            if (this.blink) {
                this.held.addAll(out);
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
                case final ServerboundAttackPacket ignored -> this.model.clientAttack(true, this.view);
                case final ServerboundUseItemPacket use -> {
                    if (Boolean.TRUE.equals(this.view.predictsUse(this.view.items.hand(use.hand())))) {
                        this.model.startUsingItem(use.hand(), this.view);
                    }
                }
                case final ServerboundPlayerActionPacket playerAction when playerAction.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM ->
                    this.model.releaseUsingItem();
                case final ServerboundSetCarriedItemPacket carried -> this.view.items.select(carried.getSlot());
                default -> {
                }
            }
        }

        private void ingest() {
            final List<Packet<?>> raw = this.fake.raw;
            final int size = raw.size();
            for (; this.rawSeen < size; this.rawSeen++) {
                final Packet<?> packet = raw.get(this.rawSeen);
                if (packet instanceof final BundlePacket<?> bundle) {
                    boolean knock = false;
                    for (final Packet<?> sub : bundle.subPackets()) {
                        knock |= sub instanceof final ClientboundSetEntityMotionPacket motion && motion.id() == this.player.getId();
                    }
                    for (final Packet<?> sub : bundle.subPackets()) {
                        this.enqueue(sub, knock);
                    }
                } else {
                    this.enqueue(packet, packet instanceof final ClientboundSetEntityMotionPacket motion && motion.id() == this.player.getId());
                }
            }
        }

        private void enqueue(final Packet<?> packet, final boolean knockBundle) {
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
            int release = this.ticks + this.latencyTicks;
            if (packet instanceof final ClientboundPingPacket ping) {
                release = this.holdPongs ? Integer.MAX_VALUE : this.ticks + this.latencyTicks + this.pongDelay;
                if (this.pingDelay != null && !this.holdPongs) {
                    release += Math.max(0, this.pingDelay.applyAsInt(ping.getId()));
                }
                if (knockBundle && this.knockHold > 0) {
                    this.pongHoldUntil = Math.max(this.pongHoldUntil, this.ticks + this.knockHold);
                }
                if (knockBundle && this.skipKnockPing) {
                    this.skipIds.add(ping.getId());
                }
            }
            release = Math.max(release, this.lastRelease);
            this.lastRelease = release;
            this.inbox.addLast(new Object[] {packet, release});
        }

        private void releaseInbox() {
            if (!this.withheld.isEmpty() && !this.withholdPongs && this.ticks >= this.pongHoldUntil) {
                this.drained.addAll(this.withheld);
                this.withheld.clear();
            }
            while (!this.inbox.isEmpty() && (int) this.inbox.peekFirst()[1] <= this.ticks) {
                this.handle((Packet<?>) this.inbox.pollFirst()[0]);
            }
        }

        /** Everything held is handled at the next drain, at once. */
        void releaseHeld() {
            this.holdPongs = false;
            this.withholdPongs = false;
            this.pongHoldUntil = 0;
            for (final Object[] entry : this.inbox) {
                if ((int) entry[1] > this.ticks) {
                    entry[1] = this.ticks;
                }
            }
            this.lastRelease = this.ticks;
        }

        void flush() {
            this.blink = false;
            final List<Packet<? super ServerGamePacketListener>> out = new ArrayList<>(this.held);
            this.held.clear();
            this.send(out);
        }

        private void send(final List<Packet<? super ServerGamePacketListener>> packets) {
            for (final Packet<? super ServerGamePacketListener> packet : packets) {
                if (this.kicked || this.gone) {
                    return;
                }
                try {
                    this.fake.deliver(packet);
                } catch (final RuntimeException failure) {
                    AuthCheatScenarios2.this.tests.getLogger().info("REVIVAL_CHEAT2_NOTE " + this.name + " packet error: " + failure);
                }
            }
        }

        // ClientPacketListener, for what moves or equips this client
        private void handle(final Packet<?> packet) {
            final int self = this.player.getId();
            switch (packet) {
                case final ClientboundPingPacket ping -> {
                    if (this.skipIds.remove(ping.getId())) {
                        this.skipped++;
                    } else if (this.withholdPongs || this.ticks < this.pongHoldUntil || !this.withheld.isEmpty()) {
                        this.withheld.add(new ServerboundPongPacket(ping.getId()));
                    } else {
                        this.drained.add(new ServerboundPongPacket(ping.getId()));
                    }
                }
                case final ClientboundPlayerPositionPacket position -> this.teleport(position);
                case final ClientboundSetEntityMotionPacket motion when motion.id() == self -> {
                    this.selfMotions++;
                    if (!this.ignoreMotion && this.seeded) {
                        final double[] decoded = new double[3];
                        Wire.lpVec3(motion.movement().x, motion.movement().y, motion.movement().z, decoded);
                        this.model.setVelocity(decoded[0], decoded[1], decoded[2]);
                    }
                }
                case final ClientboundExplodePacket explode -> {
                    if (this.seeded && !this.ignoreMotion) {
                        explode.playerKnockback().ifPresent(k -> this.model.addVelocity(k.x, k.y, k.z));
                    }
                }
                case final ClientboundUpdateAttributesPacket attributes when attributes.getEntityId() == self -> {
                    this.view.attributes.apply(attributes.getValues());
                    this.model.attributes(attributes.getValues());
                }
                case final ClientboundUpdateMobEffectPacket effect when effect.getEntityId() == self -> this.view.addEffect(effect);
                case final ClientboundRemoveMobEffectPacket effect when effect.entityId() == self -> this.view.removeEffect(effect.effect());
                case final ClientboundPlayerAbilitiesPacket abilities -> this.view.abilities(abilities);
                case final ClientboundSetEntityDataPacket data when data.id() == self -> this.view.entityData(data.packedItems(), this.echo);
                case final ClientboundEntityEventPacket event -> {
                    if (event.getEventId() == EntityEvent.USE_ITEM_COMPLETE && event.getEntity(this.player.level()) == this.player) {
                        this.model.completeUsingItem();
                    }
                }
                case final ClientboundSetHealthPacket health -> this.view.health(health);
                case final ClientboundCooldownPacket cooldown -> this.view.cooldown(cooldown.cooldownGroup(), cooldown.duration());
                case final ClientboundSetPlayerInventoryPacket slot -> this.view.items.setInventory(slot.slot(), slot.contents());
                case final ClientboundContainerSetSlotPacket slot when slot.getContainerId() == 0 -> this.view.items.setMenuSlot(slot.getSlot(), slot.getItem());
                case final ClientboundContainerSetContentPacket content when content.containerId() == 0 -> this.view.items.setMenuContent(content.items());
                case final ClientboundSetHeldSlotPacket slot -> this.view.items.select(slot.slot());
                case final ClientboundBlockUpdatePacket update -> {
                    if (!this.ignoreBlockUpdates) {
                        this.world.onBlockUpdate(update.getPos(), update.getBlockState());
                    }
                }
                case final ClientboundSectionBlocksUpdatePacket section -> {
                    if (!this.ignoreBlockUpdates) {
                        section.runUpdates(this.world::onBlockUpdate);
                    }
                }
                case final ClientboundBlockChangedAckPacket ack -> this.ackedSequence = Math.max(this.ackedSequence, ack.sequence());
                default -> {
                }
            }
            if (solidPacket(packet) && !this.ignoreSolids) {
                // The boats, shulkers and happy ghasts it holds (its own player collides with them)
                this.world.solids.apply(packet);
            }
            if (packet instanceof final ClientboundSetPassengersPacket passengers) {
                this.view.passengers(passengers.getVehicle(), passengers.getPassengers(), self);
            }
        }

        // handleMovePlayer: apply, then accept with the resulting position
        private void teleport(final ClientboundPlayerPositionPacket packet) {
            this.positions.add(packet);
            if (!this.seeded) {
                this.joined = PositionMoveRotation.calculateAbsolute(this.joined, packet.change(), packet.relatives());
                final Vec3 at = this.joined.position();
                this.drained.add(new ServerboundAcceptTeleportationPacket(packet.id(), at.x, at.y, at.z, this.joined.yRot(), this.joined.xRot()));
                return;
            }
            this.model.position(packet.change(), packet.relatives());
            if (!Reconciler.ours(packet.id())) {
                this.yaw = this.model.yRot();
                this.pitch = this.model.xRot();
            }
            this.drained.add(new ServerboundAcceptTeleportationPacket(packet.id(), this.model.x(), this.model.y(), this.model.z(), this.model.yRot(), this.model.xRot()));
        }

        /** Back to an honest client (the cheat's leftovers are corrected by the server). */
        void honest(final Input keys) {
            this.before = null;
            this.modelKeys = null;
            this.keys = keys;
            this.forcePosition = true;
            this.ignoreMotion = false;
            this.ignoreBlockUpdates = false;
            if (this.ignoreSolids) {
                this.ignoreSolids = false;
                this.world.solids.clear(); // its copies are stale: nothing near it from here on
            }
            this.noChunkAcks = false;
            this.pingDelay = null;
            this.knockHold = 0;
            this.pongHoldUntil = 0;
            this.withholdPongs = false;
            this.skipKnockPing = false;
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
            AuthCheatScenarios2.this.clients.remove(this);
            if (this.kicked || this.player.hasDisconnected()) {
                // The fake connection doesn't run vanilla's disconnection: remove it as the disconnection would
                AuthCheatScenarios2.this.server.getConnection().getConnections().remove(this.player.connection.connection);
                if (!this.player.isRemoved()) {
                    AuthCheatScenarios2.this.server.getPlayerList().remove(this.player);
                }
            } else {
                this.fake.disconnect();
            }
        }
    }
}
