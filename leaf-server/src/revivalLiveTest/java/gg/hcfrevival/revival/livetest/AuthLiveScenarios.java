package gg.hcfrevival.revival.livetest;

import gg.hcfrevival.revival.auth.AuthMode;
import gg.hcfrevival.revival.auth.AuthPlayer;
import gg.hcfrevival.revival.auth.AuthStats;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.auth.PortSimulation;
import gg.hcfrevival.revival.auth.Simulation;
import gg.hcfrevival.revival.auth.net.Wire;
import gg.hcfrevival.revival.auth.view.ClientView;
import gg.hcfrevival.revival.auth.view.SelfEcho;
import gg.hcfrevival.revival.auth.view.WorldView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.scheduler.BukkitTask;

/**
 * Live scenarios for the authoritative netcode (docs/revival/auth-netcode.md): a {@link FakeClient} speaking the 26.3
 * client protocol, driven one client tick per server tick, with a client-side model (the same movement port, fed what a
 * client would apply) producing the positions it reports.
 * <p>
 * With Leaf's async tracker on, authority must refuse to start; that is all the async run checks.
 */
final class AuthLiveScenarios {

    private static final Input FORWARD = new Input(true, false, false, false, false, false, false);

    private final RevivalLiveTests tests;
    private final MinecraftServer server;
    private final ServerLevel level;

    private FakeClient client;
    private AuthPlayer auth;
    private BukkitTask driver;
    private IntConsumer script = tick -> {
    };
    private IntConsumer preTick = tick -> {
    };
    private Input keys = Input.EMPTY;
    private Input previousKeys = Input.EMPTY;
    private float yaw;
    private float pitch;

    // The client's own movement: the port, fed what the fake client applied
    private final ClientView clientView = new ClientView();
    private final WorldView clientWorld = new WorldView();
    private PortSimulation clientModel;
    private int seenPackets;
    // Keybind packets for the next client tick (sent before movement, as handleKeybinds and gameMode.tick send them)
    private final List<Packet<? super net.minecraft.network.protocol.game.ServerGamePacketListener>> actions = new ArrayList<>();
    private final ClientView.EchoSink clientEcho = new ClientView.EchoSink() {
        @Override
        public void sharedFlags(final byte flags) {
            AuthLiveScenarios.this.clientModel.echoSharedFlags(flags);
        }

        @Override
        public void pose(final Pose pose) {
            AuthLiveScenarios.this.clientModel.echoPose(pose);
        }

        @Override
        public void usingItem(final boolean using, final InteractionHand hand) {
            AuthLiveScenarios.this.clientModel.echoUsingItem(using, hand);
        }
    };

    // What the server's simulation did, by window
    private final List<int[]> velocityApplied = new ArrayList<>();
    private int motionWindow = -1;
    private int windowsAtStart;

    AuthLiveScenarios(final RevivalLiveTests tests, final MinecraftServer server, final ServerLevel level) {
        this.tests = tests;
        this.server = server;
        this.level = level;
    }

    void register() {
        if (Authority.refusal() != null) {
            this.tests.after(5, "authority refuses to start with Leaf's async tracker", this::refusalStart);
            this.tests.after(10, "authority refuses to start with Leaf's async tracker (check)", this::refusalCheck);
            return;
        }
        this.tests.after(5, "auth: a 26.3-speaking fake client joins under observe", this::join);
        this.tests.after(20, "auth: windows are counted one per client tick", this::windowsCounted);
        this.tests.after(1, "auth: a server teleport is a transaction applied at its ack window", this::teleport);
        this.tests.after(10, "auth: a teleport is applied exactly", this::teleportChecked);
        this.tests.after(1, "auth: velocity is acked at the client tick that saw it", this::sendVelocity);
        this.tests.after(30, "auth: velocity is acked at the client tick that saw it (check)", this::velocityChecked);
        this.tests.after(1, "auth: an honest straight walk", this::walk);
        this.tests.after(60, "auth: an honest straight walk matches the simulation bit for bit", this::walkChecked);
        this.tests.after(1, "auth: an unknown pong is a protocol violation", this::badPong);
        this.tests.after(1, "auth: enforce needs the client's own collision rule to be NEVER", this::enforceWithoutTeam);
        this.tests.after(5, "auth: enforce needs the client's own collision rule to be NEVER (check)", this::enforceCapped);
        this.tests.after(1, "auth: enforce once the client is on a NEVER team", this::enforceWithTeam);
        this.tests.after(10, "auth: an honest walk under enforce", this::enforceWalk);
        this.tests.after(50, "auth: an honest walk under enforce needs no correction and the server follows the truth", this::enforceWalkChecked);
        this.tests.after(1, "auth: a client that jumps ahead is corrected onto the true path", this::cheat);
        this.tests.after(20, "auth: a client that jumps ahead is corrected onto the true path (check)", this::cheatChecked);
        this.tests.after(1, "auth: under enforce the server's pose is the simulation's", this::sneak);
        this.tests.after(8, "auth: under enforce the server's pose is the simulation's (check)", this::sneakChecked);
        this.tests.after(5, "auth: a pose the server sets on purpose reaches the client", this::meantPose);
        this.tests.after(15, "auth: a pose the server sets on purpose reaches the client, then the simulation owns it again", this::meantPoseChecked);
        this.tests.after(1, "auth: a number-key slot switch while blocking", this::hotbarSetup);
        this.tests.after(5, "auth: a number-key slot switch while blocking (walk)", this::hotbarWalk);
        this.tests.after(30, "auth: a number-key slot switch while blocking takes the hotbar branch, not a correction", this::hotbarChecked);
        this.tests.after(1, "auth: a plugin's setVelocity from PlayerMoveEvent", this::moveVelocity);
        this.tests.after(40, "auth: a plugin's setVelocity from PlayerMoveEvent reaches the client and the truth at its ack tick", this::moveVelocityChecked);
        this.tests.after(1, "auth: a double jump (flight toggle cancelled, setVelocity)", this::doubleJumpSetup);
        this.tests.after(5, "auth: a double jump (jump twice)", this::doubleJump);
        this.tests.after(40, "auth: a double jump reaches the client and the truth at its ack tick", this::doubleJumpChecked);
        this.tests.after(1, "auth: vehicles: deny refuses mounting under enforce", this::mountRefused);
        this.tests.after(1, "auth: a second client watches the walker", this::viewerJoins);
        this.tests.after(30, "auth: the walker is on the viewer's path feed", this::viewerFed);
        this.tests.after(60, "auth: the viewer's mirror of a walking player never freezes and trails it by the K2 delay", this::mirrorChecked);
        this.hitRun(true);
        this.hitRun(false);
        this.tests.after(1, "auth: a double-tap sprint sword hit and an axe hit deal the same damage and knock with authority on and off", this::hitCompare);
        this.tests.after(1, "auth: input grammar: face to face again under enforce", () -> this.hitSetup(true));
        this.tests.after(15, "auth: input grammar: sword in hand", () -> this.hitWeapon(true, Items.DIAMOND_SWORD));
        this.tests.after(30, "auth: input grammar: attacks while blocking", this::attackWhileBlocking);
        this.tests.after(15, "auth: input grammar: attacks while blocking are dropped and reported", this::attackWhileBlockingChecked);
        this.tests.after(1, "auth: input grammar: a container click while walking", this::clickWhileWalking);
        this.tests.after(10, "auth: input grammar: a container click while walking is dropped and the container resent", this::clickWhileWalkingChecked);
        this.tests.after(1, "auth: snowball, rod and arrow on a running victim", this::projectilesRunning);
        this.tests.after(80, "auth: projectile knocks on a running victim reach client and truth at their ack tick", this::projectilesChecked);
        this.tests.after(1, "auth: an honest block placement", this::placeSetup);
        this.tests.after(8, "auth: an honest block placement (place)", this::place);
        this.tests.after(10, "auth: an honest block placement is placed, predicted and needs no correction", this::placeChecked);
        this.tests.after(5, "auth: killed mid-jump", this::killMidJump);
        this.tests.after(30, "auth: killed mid-jump: no tick of a dying client is judged", this::killMidJumpChecked);
        this.tests.after(1, "auth: respawn", this::respawn);
        this.tests.after(40, "auth: respawn: the new local player is simulated from a standstill, bit for bit", this::respawnChecked);
        this.tests.after(1, "auth: visibility: a wall between two players", this::visibilitySetup);
        this.tests.after(30, "auth: visibility: a player behind a wall is not sent", this::visibilityHidden);
        this.tests.after(20, "auth: visibility: shown again as soon as the wall is gone", this::visibilityShown);
        this.tests.after(5, "auth: a far teleport, into chunks the client hasn't got yet", this::farTeleport);
        this.tests.after(80, "auth: a far teleport needs no correction: the truth holds the client's chunks", this::farTeleportChecked);
        this.tests.after(2, "auth: cleanup", this::cleanup);
    }

    // Async run --------------------------------------------------------------------------------------------------------

    private void refusalStart() {
        this.client = new FakeClient(this.server, this.level, "AuthRefused");
        this.auth = this.client.player.revivalAuth;
        this.auth.setOverride(AuthMode.OBSERVE);
        this.driver = this.tests.getServer().getScheduler().runTaskTimer(this.pluginOf(), () -> this.client.clientTick(List.of(), Input.EMPTY, false,
            this.client.clientPosition(), 0.0F, 0.0F, true, false), 1L, 1L);
    }

    private void refusalCheck() {
        this.tests.check(this.auth.mode() == AuthMode.OFF, "the player stays off: " + Authority.refusal());
        this.tests.check(!this.auth.netActive(), "no packets are wrapped");
        this.cleanup();
    }

    private org.bukkit.plugin.Plugin pluginOf() {
        return this.tests;
    }

    // Sync run ---------------------------------------------------------------------------------------------------------

    private void join() {
        // The flat world spawns slimes (26.3 has no spawn-monsters property); a slime's hit is a real velocity the walk
        // tests would have to model. The combat scenarios before these never meet a mob, so peaceful from here on
        this.server.setDifficulty(this.level, net.minecraft.world.Difficulty.PEACEFUL, null, true);
        // Record when the server's simulation is handed velocity, by window, around the real port
        Authority.setSimulationFactory(owner -> new Recording(new PortSimulation(owner.player()), owner, this.velocityApplied));
        this.client = new FakeClient(this.server, this.level, "AuthWalker");
        this.auth = this.client.player.revivalAuth;
        this.auth.setOverride(AuthMode.OBSERVE);
        this.clientModel = new PortSimulation(this.client.player);
        this.tests.getServer().getPluginManager().registerEvents(this.plugins, this.tests);
        this.driver = this.tests.getServer().getScheduler().runTaskTimer(this.tests, this::drive, 1L, 1L);
    }

    // One client tick: the client applies what it was sent, moves, reports
    private void drive() {
        if (this.clientWorld.level() == null) {
            // The model starts where the client is after the join teleport, as the server's view was seeded
            this.clientView.seed(this.client.player);
            // The client's chunks: what Moonrise has sent it so far, then every chunk and forget packet it applies
            this.clientWorld.setLevel(this.level, true);
            this.clientWorld.seedChunks(this.client.player.moonrise$getChunkLoader().getSentChunksRaw());
            this.clientWorld.endProvisional(); // the client knows exactly what it holds: no server-loaded fallback for its model
            final Vec3 at = this.client.clientPosition();
            this.clientModel.seed(at.x, at.y, at.z, 0.0, 0.0, 0.0, this.yaw, 0.0F, this.client.player.onGround(), this.clientView, this.clientWorld);
        }
        // The drain: teleports and own velocity, in the order they arrived
        for (; this.seenPackets < this.client.received.size(); this.seenPackets++) {
            final Packet<?> packet = this.client.received.get(this.seenPackets).packet();
            if (packet instanceof final ClientboundPlayerPositionPacket position) {
                this.clientModel.position(position.change(), position.relatives());
            } else if (packet instanceof final ClientboundSetEntityMotionPacket motion && motion.id() == this.client.player.getId()) {
                final double[] decoded = new double[3];
                Wire.lpVec3(motion.movement().x, motion.movement().y, motion.movement().z, decoded);
                this.clientModel.setVelocity(decoded[0], decoded[1], decoded[2]);
            } else if (packet instanceof final net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket data && data.id() == this.client.player.getId()) {
                this.clientView.entityData(data.packedItems(), this.clientEcho);
            } else if (packet instanceof final net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket abilities) {
                this.clientView.abilities(abilities);
            } else if (packet instanceof final net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket chunk) {
                this.clientWorld.onChunk(chunk.x(), chunk.z());
            } else if (packet instanceof final net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket forget) {
                this.clientWorld.onForgetChunk(forget.pos().x(), forget.pos().z());
            } else if (packet instanceof final net.minecraft.network.protocol.game.ClientboundSetHealthPacket health) {
                this.clientView.health(health);
            } else if (packet instanceof final net.minecraft.network.protocol.game.ClientboundRespawnPacket respawn) {
                // A new LocalPlayer: standing still where the server puts it, PlayerLoaded again
                final boolean keepData = (respawn.dataToKeep() & net.minecraft.network.protocol.game.ClientboundRespawnPacket.KEEP_ENTITY_DATA) != 0;
                final boolean keepAttributes = (respawn.dataToKeep() & net.minecraft.network.protocol.game.ClientboundRespawnPacket.KEEP_ATTRIBUTE_MODIFIERS) != 0;
                this.clientView.respawn(keepAttributes, keepData);
                this.clientModel.respawn(keepData, keepAttributes);
                this.client.respawned();
            }
        }
        this.preTick.accept(this.client.ticks); // this tick's keybinds, before it moves
        final boolean wasFlying = this.clientView.flying();
        if (this.client.loaded()) {
            // LocalPlayer.tick does nothing until the client has loaded (its loading screen waits for its chunk)
            this.clientModel.tick(this.keys, this.previousKeys, this.yaw, this.pitch, 0, -1, false, this.clientView, this.clientWorld);
        }
        this.previousKeys = this.keys;
        if (this.clientView.flying() != wasFlying) {
            // LocalPlayer.aiStep toggled flight (double jump) and sent it at once, before the tick's input and move
            final net.minecraft.world.entity.player.Abilities abilities = new net.minecraft.world.entity.player.Abilities();
            abilities.flying = this.clientView.flying();
            this.actions.add(new net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket(abilities));
        }
        final Vec3 position = new Vec3(this.clientModel.x(), this.clientModel.y(), this.clientModel.z());
        final List<Packet<? super net.minecraft.network.protocol.game.ServerGamePacketListener>> sent = List.copyOf(this.actions);
        this.actions.clear();
        this.client.clientTick(sent, this.keys, this.clientModel.sprinting(), position, this.yaw, this.pitch, this.clientModel.onGround(),
            this.clientModel.horizontalCollision());
        if (this.viewer != null) {
            this.viewer.clientTick(List.of(), Input.EMPTY, false, this.viewer.clientPosition(), 0.0F, 0.0F, true, false);
            if (this.keys == FORWARD && this.viewer.player.revivalAuth.combatState instanceof final gg.hcfrevival.revival.auth.combat.MirrorBook book) {
                final gg.hcfrevival.revival.auth.combat.RemoteMirror mirror = book.mirror(this.client.player.getId());
                final gg.hcfrevival.revival.auth.combat.MirrorBook.Sample sample = new gg.hcfrevival.revival.auth.combat.MirrorBook.Sample();
                final boolean exact = book.sample(this.client.player.getId(), this.viewer.player.revivalAuth.tick(), sample) && sample.exact;
                if (mirror != null) {
                    this.mirrorTrace.add(new double[]{this.auth.simulation().z(), mirror.z(), exact ? 1.0 : 0.0});
                }
            }
        }
        this.script.accept(this.client.ticks);
    }

    private void windowsCounted() {
        this.tests.check(this.auth.mode() == AuthMode.OBSERVE, "the fake client runs under observe (mode " + this.auth.mode() + ")");
        this.tests.check(this.auth.netActive(), "its physics packets are wrapped");
        // Activation happened at its first TickEnd, which closed the first window; every client tick is one window
        this.tests.check(this.auth.stream().closedTicks() == this.client.ticks,
            "one window per client tick after activation (" + this.auth.stream().closedTicks() + " windows, " + this.client.ticks + " ticks)");
        this.tests.check(this.auth.transactions().ackedCount() > 0, "pings are answered (heartbeats at least): " + this.auth.transactions().ackedCount());
        this.tests.check(this.auth.stats().violations() == 0 && this.auth.stream().violations() == 0, "a vanilla-ordered client breaks no grammar");
        this.windowsAtStart = this.auth.stream().closedTicks();
    }

    private void teleport() {
        final int x = this.client.player.getBlockX();
        final int z = this.client.player.getBlockZ();
        final int ground = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        this.client.player.connection.teleport(x + 0.5, ground, z + 0.5, 0.0F, 0.0F);
        this.tests.check(this.auth.teleportPending(), "the teleport is pending until the client applies it");
    }

    private void teleportChecked() {
        this.tests.check(!this.auth.teleportPending(), "the teleport's pong came back");
        final Simulation sim = this.auth.simulation();
        final Vec3 client = this.client.clientPosition();
        this.tests.check(this.auth.stats().teleportAcceptMismatches() == 0, "the accept matched the simulated teleport exactly");
        this.tests.check(sim.seeded(), "the teleport seeded the simulation (server-owned state)");
        this.tests.check(Math.abs(client.x - this.client.player.getX()) < 1.0E-9 && Math.abs(client.z - this.client.player.getZ()) < 1.0E-9,
            "client and server agree on the destination: " + client);
    }

    private void sendVelocity() {
        this.velocityApplied.clear();
        this.script = tick -> {
            if (this.motionWindow < 0) {
                // Between client ticks: the client applies it at its next drain, so the pong lands in the next window
                this.motionWindow = this.auth.stream().currentTick();
                this.client.player.connection.send(new ClientboundSetEntityMotionPacket(this.client.player.getId(), new Vec3(0.0, 0.42, 0.0)));
                this.script = ignored -> {
                };
            }
        };
    }

    private void velocityChecked() {
        boolean bundled = false;
        for (final Packet<?> packet : this.client.raw) {
            if (packet instanceof final ClientboundBundlePacket bundle) {
                Packet<? super ClientGamePacketListener> first = null;
                boolean motion = false;
                for (final Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                    if (first == null) {
                        first = sub;
                    }
                    motion |= sub instanceof final ClientboundSetEntityMotionPacket m && m.id() == this.client.player.getId();
                }
                bundled |= motion && first instanceof ClientboundPingPacket;
            }
        }
        this.tests.check(bundled, "the velocity went out in a bundle behind our ping");
        this.tests.check(this.velocityApplied.size() == 1, "the simulation got the velocity once (" + this.velocityApplied.size() + ")");
        final int[] applied = this.velocityApplied.getFirst();
        this.tests.check(applied[0] == this.motionWindow, "applied before window " + applied[0] + ", the window its pong arrived in (" + this.motionWindow + ")");
        final double[] decoded = new double[3];
        Wire.lpVec3(0.0, 0.42, 0.0, decoded);
        this.tests.check(Double.longBitsToDouble(((long) applied[1] << 32) | (applied[2] & 0xFFFFFFFFL)) == decoded[1], "the client's quantized value: " + decoded[1]);
    }

    private long comparedBefore;
    private long exactBefore;

    private void walk() {
        this.comparedBefore = this.auth.stats().compared();
        this.exactBefore = this.auth.stats().exact();
        this.yaw = 0.0F;
        this.keys = FORWARD; // from the next client tick on
        this.script = tick -> {
            if (this.keys == FORWARD && ++this.walked >= 40) {
                this.keys = Input.EMPTY;
            }
        };
    }

    private int walked;

    private void walkChecked() {
        final AuthStats stats = this.auth.stats();
        final long compared = stats.compared() - this.comparedBefore;
        final long exact = stats.exact() - this.exactBefore;
        this.tests.check(this.walked >= 40, "the client walked forward for 40 ticks");
        this.tests.check(compared >= 40, "the claims were compared with the simulation (" + compared + ")");
        this.tests.check(exact == compared, "every claim matched the simulation bit for bit: " + exact + "/" + compared
            + ", max divergence " + stats.maxDivergence() + ", exempt no-sim " + stats.exempt(AuthStats.EXEMPT_NO_SIMULATION)
            + ", errors " + stats.simulationErrors());
        this.tests.check(stats.flagMismatches() == 0, "onGround and horizontalCollision matched (" + stats.flagMismatches() + ")");
        this.tests.check(this.client.player.getZ() > this.client.clientPosition().z - 1.0E-9 && this.client.player.getZ() > 2.0,
            "the walk moved the player on the server (observe: the claim is the truth): z " + this.client.player.getZ());
    }

    private void badPong() {
        final long before = this.auth.transactions().violationCount();
        this.client.deliver(new ServerboundPongPacket(-70_000));
        this.tests.check(this.auth.transactions().violationCount() == before + 1, "a pong for an id we never sent is a violation");
        this.client.deliver(new ServerboundPongPacket(42));
        this.tests.check(this.auth.transactions().violationCount() == before + 1, "a foreign pong passes through");
    }

    // Enforce ------------------------------------------------------------------------------------------------------

    private void enforceWithoutTeam() {
        this.auth.setOverride(AuthMode.ENFORCE);
    }

    private void enforceCapped() {
        this.tests.check(this.auth.mode() == AuthMode.OBSERVE, "without a NEVER team the player stays in observe (" + this.auth.mode() + ")");
    }

    private void enforceWithTeam() {
        final net.minecraft.server.ServerScoreboard scoreboard = this.server.getScoreboard();
        net.minecraft.world.scores.PlayerTeam team = scoreboard.getPlayerTeam("authnever");
        if (team == null) {
            team = scoreboard.addPlayerTeam("authnever");
        }
        team.setCollisionRule(net.minecraft.world.scores.Team.CollisionRule.NEVER);
        scoreboard.addPlayerToTeam(this.client.player.getScoreboardName(), team);
    }

    private long correctionsBefore;
    private long enforceCompared;
    private long enforceExact;

    private void enforceWalk() {
        this.tests.check(this.auth.view().selfCollisionNever(), "the team packets reached the client view");
        this.tests.check(this.auth.mode() == AuthMode.ENFORCE, "the player is now under enforce (" + this.auth.mode() + ")");
        this.correctionsBefore = this.auth.stats().corrections();
        this.enforceCompared = this.auth.stats().compared();
        this.enforceExact = this.auth.stats().exact();
        this.walked = 0;
        this.yaw = 90.0F; // west, -x
        this.keys = FORWARD;
        this.script = tick -> {
            if (this.keys == FORWARD && ++this.walked >= 30) {
                this.keys = Input.EMPTY;
            }
        };
    }

    private void enforceWalkChecked() {
        final AuthStats stats = this.auth.stats();
        final long compared = stats.compared() - this.enforceCompared;
        this.tests.check(compared >= 30, "the claims were compared (" + compared + ")");
        this.tests.check(stats.exact() - this.enforceExact == compared, "every claim was exact");
        this.tests.check(stats.corrections() == this.correctionsBefore, "no correction for an honest client (" + (stats.corrections() - this.correctionsBefore) + ")");
        final Simulation sim = this.auth.simulation();
        this.tests.check(this.client.player.getX() == sim.x() && this.client.player.getY() == sim.y() && this.client.player.getZ() == sim.z(),
            "the server player is where the simulation says: " + this.client.player.position() + " vs " + new Vec3(sim.x(), sim.y(), sim.z()));
        this.tests.check(this.auth.skipsTravel(), "the zero-input server travel is skipped while the simulation owns the player");
        final String hud = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(gg.hcfrevival.revival.auth.DebugHud.line(this.auth, System.currentTimeMillis()));
        this.tests.check(hud.startsWith("E  rtt ") && hud.contains(" exact ") && hud.contains("corr 0 (0)") && hud.contains(" bank "), "the playtest HUD line: " + hud);
    }

    private Vec3 truthBeforeCheat;

    private void cheat() {
        this.correctionsBefore = this.auth.stats().corrections();
        this.script = tick -> {
            if (this.truthBeforeCheat == null) {
                // The next client tick reports a position half a block ahead of where its inputs take it
                this.truthBeforeCheat = new Vec3(this.clientModel.x(), this.clientModel.y(), this.clientModel.z());
                this.clientModel.rebase(this.clientModel.x() - 0.5, this.clientModel.y(), this.clientModel.z());
                this.script = ignored -> {
                };
            }
        };
    }

    private void cheatChecked() {
        final AuthStats stats = this.auth.stats();
        this.tests.check(stats.corrections() - this.correctionsBefore == 1, "exactly one correction (" + (stats.corrections() - this.correctionsBefore) + ")");
        this.tests.check(stats.correctionsAccepted() >= 1, "the client accepted it");
        this.tests.check(this.auth.reconciler().violations() == 0, "the accept came right after its pong");
        this.tests.check(!this.auth.reconciler().inFlight(), "nothing is in flight any more");
        final Simulation sim = this.auth.simulation();
        this.tests.check(this.clientModel.x() == sim.x() && this.clientModel.y() == sim.y() && this.clientModel.z() == sim.z(),
            "the client is back on the true path, bit for bit: " + new Vec3(this.clientModel.x(), this.clientModel.y(), this.clientModel.z())
                + " vs " + new Vec3(sim.x(), sim.y(), sim.z()));
        this.tests.check(this.client.player.getX() == sim.x(), "the server player never took the cheated position");
    }

    // §15.4: the simulation is the only writer of the client's state ------------------------------------------------

    private static final Input SNEAK = new Input(false, false, false, false, false, true, false);

    private void sneak() {
        this.correctionsBefore = this.auth.stats().corrections();
        this.tests.check(this.auth.ownsClientState(), "the simulation owns the client's state under enforce");
        this.keys = SNEAK;
    }

    private void sneakChecked() {
        final Simulation sim = this.auth.simulation();
        this.tests.check(sim.pose() == Pose.CROUCHING && this.client.player.getPose() == Pose.CROUCHING,
            "the server pose follows the simulation (" + this.client.player.getPose() + ", simulation " + sim.pose() + ")");
        this.tests.check(this.auth.stats().corrections() == this.correctionsBefore, "no correction");
        this.keys = Input.EMPTY;
    }

    private int receivedMark;

    private void meantPose() {
        this.tests.check(this.client.player.getPose() == Pose.STANDING, "standing again (" + this.client.player.getPose() + ")");
        this.receivedMark = this.client.received.size();
        this.client.player.setPose(Pose.SWIMMING); // a plugin's write, outside the client's own packets
        this.tests.check((this.auth.meant() & SelfEcho.POSE) != 0, "the write is marked server-meant");
    }

    private void meantPoseChecked() {
        boolean sent = false;
        for (final ClientboundSetEntityDataPacket data : this.client.receivedOfType(ClientboundSetEntityDataPacket.class, this.receivedMark)) {
            if (data.id() == this.client.player.getId()) {
                for (final net.minecraft.network.syncher.SynchedEntityData.DataValue<?> value : data.packedItems()) {
                    sent |= value.id() == ClientView.DATA_POSE && value.value() == Pose.SWIMMING;
                }
            }
        }
        this.tests.check(sent, "the self echo carried the server's pose (not dropped as client-owned)");
        this.tests.check(this.auth.meant() == 0, "acked, the pose is no longer server-meant (" + this.auth.meant() + ")");
        final Simulation sim = this.auth.simulation();
        this.tests.check(sim.pose() == Pose.STANDING && this.client.player.getPose() == Pose.STANDING,
            "the client recomputed its pose and the server follows the simulation again (" + this.client.player.getPose() + ", simulation " + sim.pose() + ")");
        this.tests.check(this.clientModel.x() == sim.x() && this.clientModel.y() == sim.y() && this.clientModel.z() == sim.z(), "still on the true path");
        this.tests.check(this.auth.stats().corrections() == this.correctionsBefore, "no correction");
    }

    private long hotbarBranchesBefore;
    private int hotbarStart = -1;

    private void hotbarSetup() {
        final ItemStack shield = new ItemStack(Items.SHIELD);
        this.client.player.getInventory().setItem(0, shield);
        this.client.player.getInventory().setItem(1, ItemStack.EMPTY);
        this.client.player.getInventory().setSelectedSlot(0);
        // The client's own inventory gets the slot update; the model reads its view
        this.clientView.items.setInventory(0, shield);
        this.clientView.items.setInventory(1, ItemStack.EMPTY);
        this.clientView.items.select(0);
    }

    private void hotbarWalk() {
        this.tests.check(this.auth.view().items.mainHand().is(Items.SHIELD), "the server's view of the client holds the shield");
        this.correctionsBefore = this.auth.stats().corrections();
        this.hotbarBranchesBefore = this.auth.stats().hotbarBranches();
        this.yaw = 0.0F; // south
        this.keys = FORWARD;
        this.hotbarStart = -1;
        this.preTick = tick -> {
            if (this.hotbarStart < 0) {
                this.hotbarStart = tick;
            }
            final int k = tick - this.hotbarStart;
            if (k == 0) {
                // Right click: the client starts blocking before it moves (MultiPlayerGameMode.useItem)
                this.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, this.yaw, 0.0F));
                this.clientModel.startUsingItem(InteractionHand.MAIN_HAND);
            } else if (k == 8) {
                // Number key 2 in handleKeybinds: this tick already moves with the empty hand (the use stops) ...
                this.clientView.items.select(1);
            } else if (k == 9) {
                // ... and the slot is only sent by the next tick's gameMode.tick (ensureHasSentCarriedItem), report 02 H9
                this.actions.add(new ServerboundSetCarriedItemPacket(1));
            } else if (k == 20) {
                this.keys = Input.EMPTY;
                this.preTick = ignored -> {
                };
            }
        };
    }

    private void hotbarChecked() {
        final AuthStats stats = this.auth.stats();
        this.tests.check(stats.hotbarBranches() - this.hotbarBranchesBefore == 1, "one hotbar branch (" + (stats.hotbarBranches() - this.hotbarBranchesBefore) + ")");
        this.tests.check(stats.corrections() == this.correctionsBefore, "no correction (" + (stats.corrections() - this.correctionsBefore) + ")");
        final Simulation sim = this.auth.simulation();
        this.tests.check(this.clientModel.x() == sim.x() && this.clientModel.y() == sim.y() && this.clientModel.z() == sim.z(),
            "the simulation took the client's branch, bit for bit: " + new Vec3(this.clientModel.x(), this.clientModel.y(), this.clientModel.z())
                + " vs " + new Vec3(sim.x(), sim.y(), sim.z()));
        this.tests.check(!sim.usingItem() && !this.client.player.isUsingItem(), "nobody is blocking any more");
        this.tests.check(this.client.player.getInventory().getSelectedSlot() == 1, "the server took the slot");
    }

    // §15.5: server velocity writes ----------------------------------------------------------------------------------

    /** Plugins the scenarios stand in for: a velocity on a move, a double jump on a flight toggle. */
    private final class Plugins implements org.bukkit.event.Listener {
        boolean moveArmed;
        boolean moveFired;
        boolean flightArmed;
        boolean flightFired;
        final List<String> anomalies = new ArrayList<>();
        int failedMoves;

        // Paper's moved-too-quickly / moved-wrongly / clipped-into-block checks must never judge an authoritative truth
        @org.bukkit.event.EventHandler
        public void onFailMove(final io.papermc.paper.event.player.PlayerFailMoveEvent event) {
            final FakeClient walker = AuthLiveScenarios.this.client;
            if (walker != null && event.getPlayer().getUniqueId().equals(walker.player.getUUID()) && walker.player.revivalAuth.mode() == AuthMode.ENFORCE) {
                this.failedMoves++;
                AuthLiveScenarios.this.tests.getLogger().warning("Paper judged a truth: " + event.getFailReason());
            }
        }

        @org.bukkit.event.EventHandler
        public void onAnomaly(final gg.hcfrevival.revival.api.event.AuthInputAnomalyEvent event) {
            this.anomalies.add(event.getPlayer().getName() + " " + event.getKind() + " " + event.getValue());
        }
        double victimDamage = -1.0;

        // The damage dealt, taken at the hit: peaceful regenerates health between the hit and the check
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(final org.bukkit.event.entity.EntityDamageByEntityEvent event) {
            final FakeClient victim = AuthLiveScenarios.this.viewer;
            if (victim != null && event.getEntity().getUniqueId().equals(victim.player.getUUID())) {
                this.victimDamage = event.getFinalDamage();
            }
        }

        @org.bukkit.event.EventHandler
        public void onMove(final org.bukkit.event.player.PlayerMoveEvent event) {
            if (this.moveArmed && event.getPlayer().getUniqueId().equals(AuthLiveScenarios.this.client.player.getUUID())) {
                this.moveArmed = false;
                this.moveFired = true;
                event.getPlayer().setVelocity(new org.bukkit.util.Vector(0.0, 0.6, 0.0));
            }
        }

        @org.bukkit.event.EventHandler
        public void onFlight(final org.bukkit.event.player.PlayerToggleFlightEvent event) {
            if (this.flightArmed && event.getPlayer().getUniqueId().equals(AuthLiveScenarios.this.client.player.getUUID())) {
                this.flightArmed = false;
                this.flightFired = true;
                event.setCancelled(true);
                event.getPlayer().setVelocity(new org.bukkit.util.Vector(0.0, 0.8, 0.0));
            }
        }
    }

    private final Plugins plugins = new Plugins();
    private double startY;
    private double maxY;

    private List<Vec3> selfMotions(final int from) {
        final List<Vec3> out = new ArrayList<>();
        for (final ClientboundSetEntityMotionPacket motion : this.client.receivedOfType(ClientboundSetEntityMotionPacket.class, from)) {
            if (motion.id() == this.client.player.getId()) {
                final double[] decoded = new double[3];
                Wire.lpVec3(motion.movement().x, motion.movement().y, motion.movement().z, decoded);
                out.add(new Vec3(decoded[0], decoded[1], decoded[2]));
            }
        }
        return out;
    }

    private void moveVelocity() {
        this.correctionsBefore = this.auth.stats().corrections();
        this.receivedMark = this.client.received.size();
        this.startY = this.clientModel.y();
        this.maxY = this.startY;
        this.walked = 0;
        this.yaw = 180.0F; // north
        this.keys = FORWARD;
        this.plugins.moveArmed = true;
        this.script = tick -> {
            this.maxY = Math.max(this.maxY, this.clientModel.y());
            if (this.keys == FORWARD && ++this.walked >= 20) {
                this.keys = Input.EMPTY;
            }
        };
    }

    private void moveVelocityChecked() {
        this.tests.check(this.plugins.moveFired, "PlayerMoveEvent fired for the simulated move");
        final List<Vec3> motions = this.selfMotions(this.receivedMark);
        this.tests.check(motions.size() == 1 && motions.getFirst().x == 0.0 && Math.abs(motions.getFirst().y - 0.6) < 1.0E-3 && motions.getFirst().z == 0.0,
            "the plugin's velocity reached the client as set, not overwritten by the walk's motion: " + motions);
        this.tests.check(this.maxY - this.startY > 0.5, "the client went up (" + (this.maxY - this.startY) + ")");
        this.tests.check(this.auth.stats().corrections() == this.correctionsBefore, "no correction: the truth took it at the client's tick ("
            + (this.auth.stats().corrections() - this.correctionsBefore) + ")");
        final Simulation sim = this.auth.simulation();
        this.tests.check(this.clientModel.x() == sim.x() && this.clientModel.y() == sim.y() && this.clientModel.z() == sim.z(), "client and truth agree bit for bit");
        final Vec3 motion = this.client.player.getDeltaMovement();
        this.tests.check(motion.x == sim.vx() && motion.y == sim.vy() && motion.z == sim.vz(),
            "the server's motion is the simulation's again: " + motion + " vs " + new Vec3(sim.vx(), sim.vy(), sim.vz()));
    }

    private static final Input JUMP = new Input(false, false, false, false, true, false, false);

    private void doubleJumpSetup() {
        this.client.player.getAbilities().mayfly = true;
        this.client.player.onUpdateAbilities();
    }

    private void doubleJump() {
        this.tests.check(this.clientView.mayfly() && this.auth.view().mayfly(), "the client may fly, and the server's view of it knows");
        this.correctionsBefore = this.auth.stats().corrections();
        this.receivedMark = this.client.received.size();
        this.startY = this.clientModel.y();
        this.maxY = this.startY;
        this.plugins.flightArmed = true;
        this.hotbarStart = -1;
        this.preTick = tick -> {
            if (this.hotbarStart < 0) {
                this.hotbarStart = tick;
            }
            final int k = tick - this.hotbarStart;
            // Jump, release, jump again inside the 7-tick trigger window: LocalPlayer.aiStep toggles flight
            this.keys = k == 0 || k == 2 ? JUMP : Input.EMPTY;
            if (k > 2) {
                this.preTick = ignored -> {
                };
            }
        };
        this.script = tick -> this.maxY = Math.max(this.maxY, this.clientModel.y());
    }

    private void doubleJumpChecked() {
        this.tests.check(this.plugins.flightFired, "PlayerToggleFlightEvent fired");
        final List<Vec3> motions = this.selfMotions(this.receivedMark);
        this.tests.check(motions.size() == 1 && Math.abs(motions.getFirst().y - 0.8) < 1.0E-3, "the double jump's velocity reached the client: " + motions);
        this.tests.check(!this.client.receivedOfType(net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket.class, this.receivedMark).isEmpty(),
            "the refused flight went back to the client");
        this.tests.check(!this.clientView.flying() && !this.auth.view().flying() && !this.client.player.getAbilities().flying, "nobody is flying");
        this.tests.check(this.maxY - this.startY > 1.2, "the client was launched (" + (this.maxY - this.startY) + ")");
        this.tests.check(this.auth.stats().corrections() == this.correctionsBefore, "no correction ("
            + (this.auth.stats().corrections() - this.correctionsBefore) + ")");
        final Simulation sim = this.auth.simulation();
        this.tests.check(this.clientModel.x() == sim.x() && this.clientModel.y() == sim.y() && this.clientModel.z() == sim.z(), "client and truth agree bit for bit");
        this.client.player.getAbilities().mayfly = false;
        this.client.player.onUpdateAbilities();
        this.script = tick -> {
        };
    }

    private void mountRefused() {
        final long before = this.auth.stats().mountsRefused();
        final net.minecraft.world.entity.Entity boat = net.minecraft.world.entity.EntityTypes.OAK_BOAT.create(this.level,
            net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        boat.setPos(this.client.player.getX() + 1.5, this.client.player.getY(), this.client.player.getZ());
        this.level.addFreshEntity(boat);
        final boolean mounted = this.client.player.startRiding(boat, true, true);
        this.tests.check(!mounted && this.client.player.getVehicle() == null, "even a forced mount is refused");
        this.tests.check(this.auth.stats().mountsRefused() == before + 1, "and counted");
        boat.discard();
    }

    // Replication ----------------------------------------------------------------------------------------------------

    private FakeClient viewer;
    private final List<double[]> mirrorTrace = new ArrayList<>();

    private void viewerJoins() {
        this.viewer = new FakeClient(this.server, this.level, "AuthViewer");
        this.viewer.player.revivalAuth.setOverride(AuthMode.OBSERVE);
        // Six blocks beside the walker, well inside replication.exact-radius
        final Vec3 at = this.client.player.position();
        this.viewer.player.connection.teleport(at.x + 6.0, at.y, at.z, 0.0F, 0.0F);
    }

    private void viewerFed() {
        final AuthPlayer viewerAuth = this.viewer.player.revivalAuth;
        this.tests.check(viewerAuth.mode() == AuthMode.OBSERVE && viewerAuth.netActive(), "the viewer runs under authority too");
        this.tests.check(gg.hcfrevival.revival.auth.PathWiring.feeds(this.auth, this.viewer.player),
            "the walker's path goes to the viewer from the feed (distance " + this.client.player.distanceTo(this.viewer.player) + ")");
        this.walked = 0;
        this.yaw = 0.0F; // south, +z
        this.keys = FORWARD;
        this.mirrorTrace.clear();
        this.script = tick -> {
            if (this.keys == FORWARD && ++this.walked >= 50) {
                this.keys = Input.EMPTY;
            }
        };
    }

    private void mirrorChecked() {
        int moves = 0;
        int stepped = 0;
        for (final FakeClient.Received received : this.viewer.received) {
            if (received.packet() instanceof final net.minecraft.network.protocol.game.ClientboundMoveEntityPacket move
                && move.revivalEntityId() == this.client.player.getId() && move.hasPosition()) {
                moves++;
                if (move.getPositionDelta() instanceof final net.minecraft.network.protocol.game.VecDelta.Stepped path
                    && path.steps().size() == 1 && path.steps().getFirst().ticks() == 2) {
                    stepped++;
                }
            }
        }
        this.tests.check(moves > 40 && stepped == moves, "every move of the walker is a one-step 2-tick path (" + stepped + "/" + moves + ")");
        // Steady state: from the 12th walking tick to the 45th
        double minLag = Double.MAX_VALUE;
        double maxLag = -Double.MAX_VALUE;
        int frozen = 0;
        int inexact = 0;
        for (int i = 12; i < 45 && i < this.mirrorTrace.size(); i++) {
            final double[] now = this.mirrorTrace.get(i);
            final double[] before = this.mirrorTrace.get(i - 1);
            if (!(now[1] > before[1])) {
                frozen++;
            }
            final double speed = now[0] - before[0];
            final double lag = (now[0] - now[1]) / speed;
            minLag = Math.min(minLag, lag);
            maxLag = Math.max(maxLag, lag);
            if (now[2] == 0.0) {
                inexact++;
            }
        }
        final StringBuilder trace = new StringBuilder("AUTH_MIRROR_TRACE");
        for (final double[] row : this.mirrorTrace) {
            trace.append(String.format(java.util.Locale.ROOT, " [%.4f %.4f %s]", row[0], row[1], row[2] == 1.0 ? "x" : "-"));
        }
        this.tests.getLogger().info(trace.toString());
        this.tests.check(this.mirrorTrace.size() >= 45, "the walk was traced (" + this.mirrorTrace.size() + " ticks)");
        this.tests.check(frozen == 0, "the viewer's display moved every tick (" + frozen + " frozen ticks)");
        this.tests.check(minLag >= 1.0 && maxLag <= 3.5, "it trails the truth by a steady " + minLag + " to " + maxLag + " ticks of movement");
        this.tests.check(maxLag - minLag < 0.5, "the delay holds (spread " + (maxLag - minLag) + ")");
        this.tests.check(inexact == 0, "the mirror stayed exact (" + inexact + " inexact samples)");
    }

    // §15.5: hits with authority on and off ---------------------------------------------------------------------------

    /** One run's results: the victim's knock and damage, sword then axe. */
    private final Vec3[][] knocks = new Vec3[2][2];
    private final double[][] damages = new double[2][2];
    private boolean sprintAtSwing;
    private int hitMark;
    private long acceptedBefore;

    private void hitRun(final boolean on) {
        final String label = "auth: hits with authority " + (on ? "on" : "off");
        this.tests.after(1, label + ": face to face", () -> this.hitSetup(on));
        this.tests.after(15, label + ": sword in hand", () -> this.hitWeapon(on, Items.DIAMOND_SWORD));
        this.tests.after(30, label + ": double-tap sprint, then hit", () -> this.hitSwing(true));
        this.tests.after(15, label + ": the sword hit", () -> this.hitRecord(on, 0));
        this.tests.after(1, label + ": axe in hand", () -> this.hitWeapon(on, Items.IRON_AXE));
        this.tests.after(35, label + ": standing axe hit", () -> this.hitSwing(false));
        this.tests.after(15, label + ": the axe hit", () -> this.hitRecord(on, 1));
    }

    private void hitSetup(final boolean on) {
        this.preTick = tick -> {
        };
        this.script = tick -> {
        };
        this.keys = Input.EMPTY;
        this.auth.setOverride(on ? AuthMode.ENFORCE : AuthMode.OFF);
        this.viewer.player.revivalAuth.setOverride(on ? AuthMode.OBSERVE : AuthMode.OFF);
        // The attacker looks along +z at the victim 2.8 blocks ahead, both on the ground, the same spot both runs
        final int x = Mth.floor(this.client.player.getX());
        final int z = Mth.floor(this.client.player.getZ()) - 20;
        final int ground = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        this.yaw = 0.0F;
        this.client.player.connection.teleport(x + 0.5, ground, z + 0.5, 0.0F, 0.0F);
        this.viewer.player.connection.teleport(x + 0.5, ground, z + 3.3, 180.0F, 0.0F);
    }

    private void hitWeapon(final boolean on, final net.minecraft.world.item.Item weapon) {
        this.tests.check(this.auth.mode() == (on ? AuthMode.ENFORCE : AuthMode.OFF), "the attacker is " + (on ? "under enforce" : "off") + " (" + this.auth.mode() + ")");
        final ItemStack stack = new ItemStack(weapon);
        final int slot = this.client.player.getInventory().getSelectedSlot();
        this.client.player.getInventory().setItem(slot, stack);
        this.clientView.items.setInventory(slot, stack);
        this.clientView.items.select(slot);
        this.viewer.player.setHealth(20.0F);
    }

    private void hitSwing(final boolean sprintFirst) {
        this.hitMark = this.viewer.received.size();
        this.acceptedBefore = this.auth.stats().attacksAccepted();
        this.plugins.victimDamage = -1.0;
        this.hotbarStart = -1;
        final int attackAt = sprintFirst ? 3 : 0;
        this.preTick = tick -> {
            if (this.hotbarStart < 0) {
                this.hotbarStart = tick;
            }
            final int k = tick - this.hotbarStart;
            if (sprintFirst && k <= 2) {
                this.keys = k == 1 ? Input.EMPTY : FORWARD; // double-tap W: the client starts sprinting on the second press
            }
            if (k == attackAt) {
                this.sprintAtSwing = this.clientModel.sprinting();
                this.actions.add(new net.minecraft.network.protocol.game.ServerboundAttackPacket(this.viewer.player.getId()));
                this.clientModel.clientAttack(true, this.clientView);
            } else if (k > attackAt) {
                this.keys = Input.EMPTY;
                this.preTick = ignored -> {
                };
            }
        };
    }

    private void hitRecord(final boolean on, final int which) {
        final int run = on ? 1 : 0;
        Vec3 knock = null;
        for (final ClientboundSetEntityMotionPacket motion : this.viewer.receivedOfType(ClientboundSetEntityMotionPacket.class, this.hitMark)) {
            if (motion.id() == this.viewer.player.getId() && knock == null) {
                knock = motion.movement();
            }
        }
        this.knocks[run][which] = knock;
        this.damages[run][which] = this.plugins.victimDamage;
        if (which == 0) {
            this.tests.check(this.sprintAtSwing, "the attacker was sprinting when it hit");
        }
        this.tests.check(knock != null, "the victim was knocked (damage " + this.damages[run][which] + ")");
        if (on) {
            this.tests.check(this.auth.stats().attacksAccepted() - this.acceptedBefore == 1, "the hit was judged valid ("
                + (this.auth.stats().attacksAccepted() - this.acceptedBefore) + ", last " + this.auth.lastHitVerdict() + ")");
        }
    }

    private void hitCompare() {
        for (int which = 0; which < 2; which++) {
            final String weapon = which == 0 ? "sprint sword hit" : "axe hit";
            final Vec3 off = this.knocks[0][which];
            final Vec3 on = this.knocks[1][which];
            this.tests.check(off.equals(on), weapon + ": the same knock, " + on + " on vs " + off + " off");
            this.tests.check(this.damages[0][which] == this.damages[1][which], weapon + ": the same damage, " + this.damages[1][which] + " on vs "
                + this.damages[0][which] + " off");
        }
        final Vec3 sprintKnock = this.knocks[1][0];
        this.tests.check(Math.abs(sprintKnock.x) < 1.0E-6 && Math.abs(sprintKnock.y - 0.4608) < 1.0E-3 && Math.abs(sprintKnock.z - 0.9) < 1.0E-3,
            "the sword hit carried the armed sprint knock (0, 0.4608, 0.9): " + sprintKnock);
        this.tests.check(this.damages[1][0] == 8.0 && this.damages[1][1] == 9.0, "legacy diamond sword 8, charged iron axe 9 ("
            + this.damages[1][0] + ", " + this.damages[1][1] + ")");
    }

    // §15.4 input grammar ------------------------------------------------------------------------------------------

    private long grammarBefore;
    private long droppedBefore;

    private void attackWhileBlocking() {
        this.tests.check(this.auth.mode() == AuthMode.ENFORCE, "the attacker is under enforce again (" + this.auth.mode() + ")");
        this.grammarBefore = this.auth.inputRules().count(gg.hcfrevival.revival.auth.input.InputRules.ATTACK_WHILE_USING);
        this.droppedBefore = this.auth.stats().grammarDropped();
        this.correctionsBefore = this.auth.stats().corrections();
        this.plugins.victimDamage = -1.0;
        this.plugins.anomalies.clear();
        this.hotbarStart = -1;
        this.preTick = tick -> {
            if (this.hotbarStart < 0) {
                this.hotbarStart = tick;
            }
            final int k = tick - this.hotbarStart;
            if (k == 0) {
                // Right click: block with the sword
                this.actions.add(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 2, this.yaw, 0.0F));
                this.clientModel.startUsingItem(InteractionHand.MAIN_HAND);
            } else if (k >= 2 && k <= 4) {
                // A vanilla client throws attack clicks away while it uses an item; this one sends them
                this.actions.add(new net.minecraft.network.protocol.game.ServerboundAttackPacket(this.viewer.player.getId()));
                this.clientModel.clientAttack(true, this.clientView);
            } else if (k == 6) {
                this.actions.add(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, net.minecraft.core.BlockPos.ZERO,
                    net.minecraft.core.Direction.DOWN));
                this.clientModel.releaseUsingItem();
                this.preTick = ignored -> {
                };
            }
        };
    }

    private void attackWhileBlockingChecked() {
        final long broken = this.auth.inputRules().count(gg.hcfrevival.revival.auth.input.InputRules.ATTACK_WHILE_USING) - this.grammarBefore;
        this.tests.check(broken == 3, "three attacks while blocking broke the grammar (" + broken + ")");
        this.tests.check(this.auth.stats().grammarDropped() - this.droppedBefore == 3, "and were not handled (" + (this.auth.stats().grammarDropped() - this.droppedBefore) + ")");
        this.tests.check(this.plugins.victimDamage < 0.0, "the victim took no hit (" + this.plugins.victimDamage + ")");
        this.tests.check(String.valueOf(this.auth.lastHitVerdict()).startsWith("INVALID grammar attack-while-using"), "the HUD's verdict: " + this.auth.lastHitVerdict());
        this.tests.check(this.plugins.anomalies.stream().anyMatch(line -> line.startsWith("AuthWalker input-grammar")), "AuthInputAnomalyEvent fired: " + this.plugins.anomalies);
        this.tests.check(this.auth.stats().corrections() == this.correctionsBefore, "the client's own slowdown from those attacks stayed modelled: no correction");
        final Simulation sim = this.auth.simulation();
        this.tests.check(this.clientModel.x() == sim.x() && this.clientModel.y() == sim.y() && this.clientModel.z() == sim.z(), "client and truth agree bit for bit");
    }

    private void clickWhileWalking() {
        this.grammarBefore = this.auth.inputRules().count(gg.hcfrevival.revival.auth.input.InputRules.CLICK_WHILE_MOVING);
        this.droppedBefore = this.auth.stats().grammarDropped();
        this.receivedMark = this.client.received.size();
        this.yaw = 180.0F;
        this.keys = FORWARD;
        this.walked = 0;
        this.hotbarStart = -1;
        this.preTick = tick -> {
            if (this.hotbarStart < 0) {
                this.hotbarStart = tick;
            }
            if (tick - this.hotbarStart == 3) {
                // An inventory move: a click in the player's own inventory while W is held
                this.actions.add(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(0, this.client.player.inventoryMenu.getStateId(),
                    (short) 36, (byte) 0, net.minecraft.world.inventory.ContainerInput.PICKUP, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(),
                    net.minecraft.network.HashedStack.EMPTY));
                this.preTick = ignored -> {
                };
            }
        };
        this.script = tick -> {
            if (this.keys == FORWARD && ++this.walked >= 6) {
                this.keys = Input.EMPTY;
            }
        };
    }

    private void clickWhileWalkingChecked() {
        final long broken = this.auth.inputRules().count(gg.hcfrevival.revival.auth.input.InputRules.CLICK_WHILE_MOVING) - this.grammarBefore;
        this.tests.check(broken == 1, "the click while walking broke the grammar (" + broken + ")");
        this.tests.check(this.auth.stats().grammarDropped() - this.droppedBefore == 1, "and was not handled");
        this.tests.check(!this.client.receivedOfType(net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket.class, this.receivedMark).isEmpty(),
            "the client got its inventory back as the server has it");
        this.script = tick -> {
        };
    }

    // §15.5: projectile knocks on a running victim ---------------------------------------------------------------------

    private void projectilesRunning() {
        this.tests.check(this.auth.mode() == AuthMode.ENFORCE, "the victim runs under enforce (" + this.auth.mode() + ")");
        this.correctionsBefore = this.auth.stats().corrections();
        this.overBefore = this.auth.stats().overThreshold();
        this.receivedMark = this.client.received.size();
        this.hotbarStart = -1;
        this.yaw = 0.0F;
        this.keys = FORWARD;
        final ServerLevel level = this.level;
        final net.minecraft.server.level.ServerPlayer thrower = this.viewer.player;
        this.preTick = tick -> {
            if (this.hotbarStart < 0) {
                this.hotbarStart = tick;
            }
            final int k = tick - this.hotbarStart;
            final net.minecraft.server.level.ServerPlayer victim = this.client.player;
            // 25 ticks apart: past the 20-tick damage window, so each one knocks
            if (k == 5) {
                final net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball snowball =
                    new net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball(level, thrower, new ItemStack(Items.SNOWBALL));
                victim.hurtServer(level, level.damageSources().thrown(snowball, thrower), 0.0F);
            } else if (k == 30) {
                final net.minecraft.world.entity.projectile.FishingHook hook = new net.minecraft.world.entity.projectile.FishingHook(thrower, level, 0, 0);
                victim.hurtServer(level, level.damageSources().thrown(hook, thrower), 0.0F);
            } else if (k == 55) {
                final net.minecraft.world.entity.projectile.arrow.Arrow arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(level, thrower,
                    new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                arrow.setDeltaMovement(0.0, 0.0, 2.0);
                victim.hurtServer(level, level.damageSources().arrow(arrow, thrower), 1.0F);
            } else if (k == 70) {
                this.keys = Input.EMPTY;
                this.preTick = ignored -> {
                };
            }
        };
    }

    private void projectilesChecked() {
        final List<Vec3> knocks = this.selfMotions(this.receivedMark);
        this.tests.check(knocks.size() == 3, "three knocks reached the running client: " + knocks);
        this.tests.check(this.auth.stats().corrections() == this.correctionsBefore, "no correction: each knock reached the truth at the client's tick ("
            + (this.auth.stats().corrections() - this.correctionsBefore) + ")");
        this.tests.check(this.auth.stats().overThreshold() == this.overBefore, "no diverged tick (" + (this.auth.stats().overThreshold() - this.overBefore) + ")");
        final Simulation sim = this.auth.simulation();
        this.tests.check(this.clientModel.x() == sim.x() && this.clientModel.y() == sim.y() && this.clientModel.z() == sim.z(), "client and truth agree bit for bit");
        this.client.player.setHealth(20.0F);
        this.tests.check(this.plugins.failedMoves == 0, "Paper's movement checks never judged a truth under enforce (" + this.plugins.failedMoves + ")");
    }

    // §15.2: the client's chunks after a far teleport ------------------------------------------------------------------

    private void farTeleport() {
        this.correctionsBefore = this.auth.stats().corrections();
        this.overBefore = this.auth.stats().overThreshold();
        this.keys = Input.EMPTY;
        this.preTick = tick -> {
        };
        final int x = Mth.floor(this.client.player.getX()) + 400;
        final int z = Mth.floor(this.client.player.getZ());
        this.client.player.connection.teleport(x + 0.5, this.client.player.getY(), z + 0.5, this.yaw, 0.0F);
        this.tests.check(this.auth.world().chunksKnown(), "the server knows which chunks the client holds");
    }

    private void farTeleportChecked() {
        final Simulation sim = this.auth.simulation();
        this.tests.check(this.auth.world().hasChunkAt(Mth.floor(sim.x()), Mth.floor(sim.z())), "the destination chunk reached the client");
        this.tests.check(this.auth.stats().corrections() == this.correctionsBefore, "no correction (" + (this.auth.stats().corrections() - this.correctionsBefore)
            + "; exempt chunk-unknown " + this.auth.stats().exempt(gg.hcfrevival.revival.auth.AuthStats.EXEMPT_CHUNK_UNKNOWN) + ", inexact seeds "
            + this.auth.stats().inexactSeeds() + ", missing positions " + this.auth.stats().missingPositionViolations() + ")");
        this.tests.check(this.clientModel.x() == sim.x() && this.clientModel.y() == sim.y() && this.clientModel.z() == sim.z(),
            "client and truth agree bit for bit: " + new Vec3(this.clientModel.x(), this.clientModel.y(), this.clientModel.z()) + " vs " + new Vec3(sim.x(), sim.y(), sim.z()));
    }

    // §15.2: an honest placement --------------------------------------------------------------------------------------

    private net.minecraft.core.BlockPos placed;
    private long refusedBefore;

    private void placeSetup() {
        this.preTick = tick -> {
        };
        this.script = tick -> {
        };
        this.keys = Input.EMPTY;
        final ItemStack stone = new ItemStack(Items.STONE, 16);
        final int slot = this.client.player.getInventory().getSelectedSlot();
        this.client.player.getInventory().setItem(slot, stone);
        this.clientView.items.setInventory(slot, stone);
        this.yaw = 0.0F;
        this.pitch = 60.0F; // looking down at the ground ahead
    }

    private void place() {
        this.tests.check(this.auth.mode() == AuthMode.ENFORCE, "under enforce (" + this.auth.mode() + ")");
        this.correctionsBefore = this.auth.stats().corrections();
        this.refusedBefore = this.auth.stats().predictionsRefused();
        // What the client's crosshair is on: the server player stands on the truth with the tick's rotation
        final net.minecraft.world.phys.HitResult hit = this.client.player.pick(this.client.player.blockInteractionRange(), 1.0F, false);
        this.tests.check(hit instanceof net.minecraft.world.phys.BlockHitResult, "the crosshair is on a block (" + hit + ")");
        final net.minecraft.world.phys.BlockHitResult block = (net.minecraft.world.phys.BlockHitResult) hit;
        this.placed = block.getBlockPos().relative(block.getDirection());
        this.actions.add(new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, block, 7));
    }

    private void placeChecked() {
        this.tests.check(this.level.getBlockState(this.placed).is(net.minecraft.world.level.block.Blocks.STONE), "the server placed it at " + this.placed);
        this.tests.check(this.auth.stats().predictionsRefused() == this.refusedBefore, "a vanilla client predicts it: admitted ("
            + (this.auth.stats().predictionsRefused() - this.refusedBefore) + " refused)");
        this.tests.check(this.auth.stats().corrections() == this.correctionsBefore, "no correction (" + (this.auth.stats().corrections() - this.correctionsBefore) + ")");
        this.level.setBlockAndUpdate(this.placed, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        this.pitch = 0.0F;
    }

    // §15.3: death is an exempt state from the client stream ----------------------------------------------------------

    private long overBefore;
    private long withinBefore;
    private long deadBefore;

    private void killMidJump() {
        final AuthStats stats = this.auth.stats();
        this.correctionsBefore = stats.corrections();
        this.overBefore = stats.overThreshold();
        this.withinBefore = stats.withinThreshold();
        this.deadBefore = stats.exempt(AuthStats.EXEMPT_DEAD);
        this.hotbarStart = -1;
        this.preTick = tick -> {
            if (this.hotbarStart < 0) {
                this.hotbarStart = tick;
            }
            final int k = tick - this.hotbarStart;
            this.keys = k == 0 ? JUMP : Input.EMPTY;
            if (k == 4) {
                // A console /kill near the top of the jump, between client ticks
                this.client.player.kill(this.level);
                this.preTick = ignored -> {
                };
            }
        };
    }

    private void killMidJumpChecked() {
        final AuthStats stats = this.auth.stats();
        this.tests.check(this.client.player.isDeadOrDying() && this.auth.view().dead(), "the client saw its death");
        this.tests.check(stats.exempt(AuthStats.EXEMPT_DEAD) > this.deadBefore, "dead ticks are exempt (" + (stats.exempt(AuthStats.EXEMPT_DEAD) - this.deadBefore) + ")");
        this.tests.check(stats.overThreshold() == this.overBefore && stats.withinThreshold() == this.withinBefore,
            "no diverged tick around the death (over " + (stats.overThreshold() - this.overBefore) + ", within " + (stats.withinThreshold() - this.withinBefore) + ")");
        this.tests.check(stats.corrections() == this.correctionsBefore, "no correction (" + (stats.corrections() - this.correctionsBefore) + ")");
    }

    private void respawn() {
        this.overBefore = this.auth.stats().overThreshold();
        this.withinBefore = this.auth.stats().withinThreshold();
        this.correctionsBefore = this.auth.stats().corrections();
        this.enforceCompared = this.auth.stats().compared();
        this.actions.add(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
            net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    private void respawnChecked() {
        final AuthStats stats = this.auth.stats();
        this.tests.check(!this.client.player.isDeadOrDying() && !this.auth.view().dead(), "alive again");
        this.tests.check(stats.compared() > this.enforceCompared, "its claims are compared again (" + (stats.compared() - this.enforceCompared) + ")");
        this.tests.check(stats.overThreshold() == this.overBefore && stats.withinThreshold() == this.withinBefore,
            "no diverged tick after the respawn (over " + (stats.overThreshold() - this.overBefore) + ", within " + (stats.withinThreshold() - this.withinBefore) + ")");
        this.tests.check(stats.corrections() == this.correctionsBefore, "no correction (" + (stats.corrections() - this.correctionsBefore) + ")");
        final Simulation sim = this.auth.simulation();
        this.tests.check(this.clientModel.x() == sim.x() && this.clientModel.y() == sim.y() && this.clientModel.z() == sim.z(),
            "client and truth agree bit for bit: " + new Vec3(this.clientModel.x(), this.clientModel.y(), this.clientModel.z()) + " vs " + new Vec3(sim.x(), sim.y(), sim.z()));
    }

    // §11 visibility ---------------------------------------------------------------------------------------------------

    private int wallX;
    private int wallY;
    private int wallZ;

    private static void visibility(final boolean enabled) {
        try {
            final java.io.File file = new java.io.File("revival.yml");
            final org.bukkit.configuration.file.YamlConfiguration yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            yaml.set("authoritative.visibility.enabled", enabled);
            yaml.save(file);
        } catch (final java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        gg.hcfrevival.revival.config.RevivalConfig.reload();
    }

    private void wall(final net.minecraft.world.level.block.state.BlockState state) {
        for (int x = this.wallX - 8; x <= this.wallX + 8; x++) {
            for (int y = this.wallY; y < this.wallY + 8; y++) {
                this.level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, y, this.wallZ), state);
            }
        }
    }

    private void visibilitySetup() {
        this.preTick = tick -> {
        };
        this.script = tick -> {
        };
        this.keys = Input.EMPTY;
        this.tests.check(this.viewer.player.revivalAuth.mode() != AuthMode.OFF, "the viewer is under authority (" + this.viewer.player.revivalAuth.mode() + ")");
        final int x = Mth.floor(this.client.player.getX());
        final int z = Mth.floor(this.client.player.getZ()) + 30;
        final int ground = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        this.wallX = x;
        this.wallY = ground;
        this.wallZ = z + 10;
        this.wall(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        this.yaw = 0.0F;
        this.client.player.connection.teleport(x + 0.5, ground, z + 0.5, 0.0F, 0.0F);
        this.viewer.player.connection.teleport(x + 0.5, ground, z + 20.5, 180.0F, 0.0F);
        visibility(true);
        this.tests.check(gg.hcfrevival.revival.Revival.settings().authoritative().visibilityEnabled(), "visibility.enabled after /revival reload");
        this.receivedMark = this.viewer.received.size();
    }

    private void visibilityHidden() {
        this.tests.check(this.viewer.player.revivalAuth.visibility().hidden().contains(this.client.player.getId()),
            "the walker is hidden from the viewer (hides " + this.viewer.player.revivalAuth.visibility().hides() + ")");
        boolean removed = false;
        for (final net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket remove
            : this.viewer.receivedOfType(net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket.class, this.receivedMark)) {
            removed |= remove.entityIds().contains(this.client.player.getId());
        }
        this.tests.check(removed, "the viewer's client was told to remove the walker");
        this.wall(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        this.receivedMark = this.viewer.received.size();
    }

    private void visibilityShown() {
        this.tests.check(!this.viewer.player.revivalAuth.visibility().hidden().contains(this.client.player.getId()), "the walker is visible again");
        boolean added = false;
        for (final net.minecraft.network.protocol.game.ClientboundAddEntityPacket add
            : this.viewer.receivedOfType(net.minecraft.network.protocol.game.ClientboundAddEntityPacket.class, this.receivedMark)) {
            added |= add.getId() == this.client.player.getId();
        }
        this.tests.check(added, "the walker was spawned again on the viewer's client");
        visibility(false);
    }

    private void cleanup() {
        if (this.driver != null) {
            this.driver.cancel();
        }
        Authority.setSimulationFactory(owner -> new PortSimulation(owner.player()));
        final net.minecraft.world.scores.PlayerTeam team = this.server.getScoreboard().getPlayerTeam("authnever");
        if (team != null) {
            this.server.getScoreboard().removePlayerTeam(team);
        }
        if (this.client != null) {
            this.client.disconnect();
        }
        if (this.viewer != null) {
            this.viewer.disconnect();
        }
    }

    /** The real port, with every velocity the netcode hands it recorded with the window it was applied in. */
    private record Recording(Simulation delegate, AuthPlayer owner, List<int[]> velocities) implements Simulation {

        // Every default of Simulation goes to the port too: a default left here would run the server's truth on a no-op

        @Override
        public boolean beliefValid() {
            return this.delegate.beliefValid();
        }

        @Override
        public void rebaseBelief(final double x, final double y, final double z, final double vx, final double vy, final double vz) {
            this.delegate.rebaseBelief(x, y, z, vx, vy, vz);
        }

        @Override
        public boolean beliefInsideShape(final gg.hcfrevival.revival.auth.view.WorldView world) {
            return this.delegate.beliefInsideShape(world);
        }

        @Override
        public void beliefCorrected(final net.minecraft.world.entity.PositionMoveRotation change, final java.util.Set<net.minecraft.world.entity.Relative> relatives) {
            this.delegate.beliefCorrected(change, relatives);
        }

        @Override
        public void resetAttackStrength() {
            this.delegate.resetAttackStrength();
        }

        @Override
        public boolean seedFromMove(final double fromX, final double fromY, final double fromZ, final double toX, final double toY, final double toZ,
                                    final @org.jspecify.annotations.Nullable Input keys, final float yRot, final float xRot, final boolean onGround,
                                    final ClientView view, final gg.hcfrevival.revival.auth.view.WorldView world) {
            return this.delegate.seedFromMove(fromX, fromY, fromZ, toX, toY, toZ, keys, yRot, xRot, onGround, view, world);
        }

        @Override
        public void setVelocity(final double x, final double y, final double z) {
            final long bits = Double.doubleToRawLongBits(y);
            this.velocities.add(new int[]{this.owner.tick(), (int) (bits >>> 32), (int) bits});
            this.delegate.setVelocity(x, y, z);
        }

        @Override
        public int unsupported(final ClientView view) {
            return this.delegate.unsupported(view);
        }

        @Override
        public boolean seeded() {
            return this.delegate.seeded();
        }

        @Override
        public void seed(final double x, final double y, final double z, final double vx, final double vy, final double vz, final float yRot, final float xRot,
                         final boolean onGround, final ClientView view, final WorldView world) {
            this.delegate.seed(x, y, z, vx, vy, vz, yRot, xRot, onGround, view, world);
        }

        @Override
        public void unseed() {
            this.delegate.unseed();
        }

        @Override
        public void rebase(final double x, final double y, final double z) {
            this.delegate.rebase(x, y, z);
        }

        @Override
        public void addVelocity(final double x, final double y, final double z) {
            this.delegate.addVelocity(x, y, z);
        }

        @Override
        public void position(final PositionMoveRotation change, final Set<Relative> relatives) {
            this.delegate.position(change, relatives);
        }

        @Override
        public void rotation(final float yRot, final boolean relativeY, final float xRot, final boolean relativeX) {
            this.delegate.rotation(yRot, relativeY, xRot, relativeX);
        }

        @Override
        public void snapTo(final double x, final double y, final double z) {
            this.delegate.snapTo(x, y, z);
        }

        @Override
        public void echoSharedFlags(final byte flags) {
            this.delegate.echoSharedFlags(flags);
        }

        @Override
        public void echoPose(final Pose pose) {
            this.delegate.echoPose(pose);
        }

        @Override
        public void echoUsingItem(final boolean using, final InteractionHand hand) {
            this.delegate.echoUsingItem(using, hand);
        }

        @Override
        public void completeUsingItem() {
            this.delegate.completeUsingItem();
        }

        @Override
        public void attributes(final List<net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket.AttributeSnapshot> snapshots) {
            this.delegate.attributes(snapshots);
        }

        @Override
        public void localGameMode(final net.minecraft.world.level.GameType mode) {
            this.delegate.localGameMode(mode);
        }

        @Override
        public void respawn(final boolean keepEntityData, final boolean keepAttributeModifiers) {
            this.delegate.respawn(keepEntityData, keepAttributeModifiers);
        }

        @Override
        public void clientAttack(final boolean targetIsPlayer, final ClientView view) {
            this.delegate.clientAttack(targetIsPlayer, view);
        }

        @Override
        public void startUsingItem(final InteractionHand hand) {
            this.delegate.startUsingItem(hand);
        }

        @Override
        public void releaseUsingItem() {
            this.delegate.releaseUsingItem();
        }

        @Override
        public void tick(final Input keys, final Input previousKeys, final float yRot, final float xRot, final int sprintCommand, final int flyingCommand,
                         final boolean fallFlyingCommand, final ClientView view, final WorldView world) {
            this.delegate.tick(keys, previousKeys, yRot, xRot, sprintCommand, flyingCommand, fallFlyingCommand, view, world);
        }

        @Override
        public double x() {
            return this.delegate.x();
        }

        @Override
        public double y() {
            return this.delegate.y();
        }

        @Override
        public double z() {
            return this.delegate.z();
        }

        @Override
        public double xo() {
            return this.delegate.xo();
        }

        @Override
        public double yo() {
            return this.delegate.yo();
        }

        @Override
        public double zo() {
            return this.delegate.zo();
        }

        @Override
        public double vx() {
            return this.delegate.vx();
        }

        @Override
        public double vy() {
            return this.delegate.vy();
        }

        @Override
        public double vz() {
            return this.delegate.vz();
        }

        @Override
        public float yRot() {
            return this.delegate.yRot();
        }

        @Override
        public float xRot() {
            return this.delegate.xRot();
        }

        @Override
        public boolean onGround() {
            return this.delegate.onGround();
        }

        @Override
        public boolean horizontalCollision() {
            return this.delegate.horizontalCollision();
        }

        @Override
        public double fallDistance() {
            return this.delegate.fallDistance();
        }

        @Override
        public boolean sprinting() {
            return this.delegate.sprinting();
        }

        @Override
        public float eyeHeight() {
            return this.delegate.eyeHeight();
        }

        @Override
        public AABB box() {
            return this.delegate.box();
        }

        @Override
        public Pose pose() {
            return this.delegate.pose();
        }

        @Override
        public void trackBelief(final boolean on) {
            this.delegate.trackBelief(on);
        }

        @Override
        public void belief(final boolean exact, final double claimX, final double claimY, final double claimZ) {
            this.delegate.belief(exact, claimX, claimY, claimZ);
        }

        @Override
        public boolean correction(final int ticksAhead, final Input keys, final float yRot, final float xRot, final ClientView view, final WorldView world,
                                  final double[] out) {
            return this.delegate.correction(ticksAhead, keys, yRot, xRot, view, world, out);
        }

        @Override
        public boolean swimming() {
            return this.delegate.swimming();
        }

        @Override
        public boolean fallFlying() {
            return this.delegate.fallFlying();
        }

        @Override
        public boolean usingItem() {
            return this.delegate.usingItem();
        }

        @Override
        public boolean retryWithSlot(final int slot, final ClientView view, final WorldView world, final double claimX, final double claimY, final double claimZ) {
            return this.delegate.retryWithSlot(slot, view, world, claimX, claimY, claimZ);
        }

        @Override
        public void tabListGameMode(final net.minecraft.world.level.GameType mode) {
            this.delegate.tabListGameMode(mode);
        }

        @Override
        public void externalMover(final boolean moving) {
            this.delegate.externalMover(moving);
        }

        @Override
        public void attachedRockets(final int count) {
            this.delegate.attachedRockets(count);
        }

        @Override
        public boolean clearPath(final double dx, final double dy, final double dz, final WorldView world) {
            return this.delegate.clearPath(dx, dy, dz, world);
        }

        @Override
        public boolean retryAttackStrength(final ClientView view, final WorldView world, final double claimX, final double claimY, final double claimZ) {
            return this.delegate.retryAttackStrength(view, world, claimX, claimY, claimZ);
        }
    }
}
