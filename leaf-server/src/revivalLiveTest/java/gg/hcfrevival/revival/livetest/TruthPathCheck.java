package gg.hcfrevival.revival.livetest;

import com.mojang.authlib.GameProfile;
import gg.hcfrevival.revival.auth.AuthMode;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.auth.PortSimulation;
import gg.hcfrevival.revival.auth.net.ClientStream;
import gg.hcfrevival.revival.auth.sim.SimPlayer;
import gg.hcfrevival.revival.auth.view.ClientView;
import gg.hcfrevival.revival.auth.view.WorldView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;

/**
 * The truth path (patch 1012, {@code revivalApplyTruth}) against the accepted-move branch as a claim takes it (the same
 * method with {@code resolved == false}: a second collision pass, every interim position with its waypoint updates).
 * <p>
 * A course (pressure plate, soul sand, cobweb, slab, a staircase to a 5-block drop, a pool, a ladder up a wall and a
 * 3-block drop, a pit to sneak to, then turns on the spot) is walked by the movement port, honestly, from the course
 * start: those simulated ticks are the recorded moves. Two fresh players then take the same moves, one per server tick,
 * one through the reference branch and one through the truth path, each followed by the block effects the player's own
 * tick applies. After every move both must show the same state (position, rotation, old position, box, ground and
 * collision flags, fall distance, motion, supporting block, steps, stuck multiplier, movement records, known movement,
 * health, exhaustion, statistics, the handler's lastGood/lastPos/floating bookkeeping), the same events (move, jump,
 * game events, damage, statistics, exhaustion, block insides, plate presses, redstone) and the same packets to
 * themselves and to a player watching, sounds field by field. Only waypoint packets may differ: the truth path writes
 * one position, so the interim ones are no longer sent.
 */
final class TruthPathCheck {

    private static final int MOVES = 420;
    private static final int LEGACY = 0;
    private static final int RESOLVED = 1;

    private final RevivalLiveTests tests;
    private final MinecraftServer server;
    private final ServerLevel level;
    private final Listener listener = new Listener() {
    };

    private int bx;
    private int by;
    private int bz;
    // x, y, z, yRot, xRot, onGround, horizontalCollision, vx, vy, vz per recorded tick
    private final double[][] moves = new double[MOVES][];
    private final Run[] runs = {new Run("vanilla branch"), new Run("truth path")};
    private ServerPlayer observer;
    private RecordingConnection observerConnection;
    private Run current;
    private int currentMove = -1;

    private static final class Run {
        final String name;
        ServerPlayer player;
        RecordingConnection connection;
        final List<String> states = new ArrayList<>();
        final List<List<String>> events = new ArrayList<>();
        final List<List<String>> selfPackets = new ArrayList<>();
        final List<List<String>> observed = new ArrayList<>();
        int selfMark;
        int observerMark;
        int waypointPackets;
        int trackerPackets;
        int offTruth;
        int moveEvents;
        int jumpEvents;
        int fallDamage;
        int steps;
        int swims;
        int insides;
        int presses;
        int sounds;

        Run(final String name) {
            this.name = name;
            for (int i = 0; i < MOVES; i++) {
                this.events.add(new ArrayList<>());
                this.selfPackets.add(new ArrayList<>());
                this.observed.add(new ArrayList<>());
            }
        }
    }

    TruthPathCheck(final RevivalLiveTests tests, final MinecraftServer server, final ServerLevel level) {
        this.tests = tests;
        this.server = server;
        this.level = level;
    }

    void register() {
        if (Authority.refusal() != null) {
            return; // the async-tracker run: the sync run carries the authority scenarios
        }
        this.tests.after(1, "truth path: the course", this::course);
        this.tests.after(20, "truth path: the moves the port records on the course", this::record);
        for (final int run : new int[] {LEGACY, RESOLVED}) {
            final String name = this.runs[run].name;
            this.tests.after(1, "truth path: a fresh player for the " + name, () -> this.join(run));
            this.tests.after(10, "truth path: the " + name + " player at the course start", () -> this.start(run));
            for (int i = 0; i < MOVES; i++) {
                final int move = i;
                this.tests.after(1, "truth path: the " + name + " applies move " + i, () -> this.apply(run, move));
            }
            this.tests.after(1, "truth path: the " + name + " done", () -> this.finish(run));
            this.tests.after(40, "truth path: the course settles (plates release)", () -> {
            });
        }
        this.tests.after(1, "truth path: same state, events and packets as the vanilla branch on every move", this::compare);
    }

    // Course --------------------------------------------------------------------------------------------------------

    private void course() {
        final org.bukkit.Location spawn = this.level.getWorld().getSpawnLocation();
        // Out of tracking range of every other test player (their own packets would interleave with these)
        this.bx = ((spawn.getBlockX() - 320) >> 4) << 4;
        this.bz = spawn.getBlockZ() + 24;
        // Kept loaded for the whole check, and loaded now: the port reads the server's loaded chunks
        for (int cx = (this.bx >> 4) - 1; cx <= ((this.bx + 40) >> 4) + 1; cx++) {
            for (int cz = (this.bz >> 4) - 1; cz <= (this.bz >> 4) + 1; cz++) {
                this.level.getWorld().addPluginChunkTicket(cx, cz, this.tests);
                this.level.getChunk(cx, cz);
            }
        }
        this.by = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, this.bx, this.bz);
        for (int x = this.bx - 2; x <= this.bx + 40; x++) {
            for (int z = this.bz - 3; z <= this.bz + 6; z++) {
                for (int y = this.by - 3; y <= this.by + 7; y++) {
                    this.set(x, y, z, y < this.by ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
        this.set(this.bx + 3, this.by, this.bz, Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
        this.set(this.bx + 5, this.by - 1, this.bz, Blocks.SOUL_SAND.defaultBlockState());
        this.set(this.bx + 6, this.by - 1, this.bz, Blocks.SOUL_SAND.defaultBlockState());
        this.set(this.bx + 8, this.by, this.bz, Blocks.COBWEB.defaultBlockState());
        this.set(this.bx + 10, this.by, this.bz, Blocks.STONE_SLAB.defaultBlockState());
        // A staircase of full blocks to a platform 5 up, then a drop with fall damage
        for (int step = 0; step < 7; step++) {
            final int height = Math.min(step + 1, 5);
            for (int y = 0; y < height; y++) {
                this.set(this.bx + 13 + step, this.by + y, this.bz, Blocks.STONE.defaultBlockState());
            }
        }
        // A pool two deep to swim through and climb out of
        for (int x = this.bx + 24; x <= this.bx + 26; x++) {
            this.set(x, this.by - 2, this.bz, Blocks.WATER.defaultBlockState());
            this.set(x, this.by - 1, this.bz, Blocks.WATER.defaultBlockState());
        }
        // A ladder up a wall 3 high, then a 3-block drop
        for (int y = 0; y < 3; y++) {
            this.set(this.bx + 28, this.by + y, this.bz, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
            this.set(this.bx + 29, this.by + y, this.bz, Blocks.STONE.defaultBlockState());
            this.set(this.bx + 30, this.by + y, this.bz, Blocks.STONE.defaultBlockState());
        }
        // A pit to sneak to the edge of
        for (int x = this.bx + 34; x <= this.bx + 35; x++) {
            this.set(x, this.by - 1, this.bz, Blocks.AIR.defaultBlockState());
            this.set(x, this.by - 2, this.bz, Blocks.AIR.defaultBlockState());
        }
        // The watcher: off the corridor, near its middle (sounds carry 16 blocks)
        this.observerConnection = new RecordingConnection();
        this.observer = this.place("TruthWatcher", this.observerConnection, this.bx + 18.5, this.bz + 5.5, 90.0F);
        final PluginManager plugins = this.tests.getServer().getPluginManager();
        this.listen(plugins, org.bukkit.event.player.PlayerMoveEvent.class, event -> {
            final org.bukkit.event.player.PlayerMoveEvent move = (org.bukkit.event.player.PlayerMoveEvent) event;
            if (this.mine(move.getPlayer())) {
                this.current.moveEvents++;
                this.event("move from " + loc(move.getFrom()) + " to " + loc(move.getTo()) + " at " + loc(move.getPlayer().getLocation())
                    + " onGround " + move.getPlayer().isOnGround());
            }
        });
        this.listen(plugins, com.destroystokyo.paper.event.player.PlayerJumpEvent.class, event -> {
            final com.destroystokyo.paper.event.player.PlayerJumpEvent jump = (com.destroystokyo.paper.event.player.PlayerJumpEvent) event;
            if (this.mine(jump.getPlayer())) {
                this.current.jumpEvents++;
                this.event("jump from " + loc(jump.getFrom()) + " to " + loc(jump.getTo()) + " at " + loc(jump.getPlayer().getLocation()));
            }
        });
        this.listen(plugins, org.bukkit.event.world.GenericGameEvent.class, event -> {
            final org.bukkit.event.world.GenericGameEvent game = (org.bukkit.event.world.GenericGameEvent) event;
            if (this.mine(game.getEntity())) {
                final String key = game.getEvent().getKey().toString();
                if (key.endsWith("step")) {
                    this.current.steps++;
                } else if (key.endsWith("swim")) {
                    this.current.swims++;
                }
                this.event("game event " + key + " at " + loc(game.getLocation()) + " radius " + game.getRadius());
            }
        });
        this.listen(plugins, org.bukkit.event.entity.EntityDamageEvent.class, event -> {
            final org.bukkit.event.entity.EntityDamageEvent damage = (org.bukkit.event.entity.EntityDamageEvent) event;
            if (this.mine(damage.getEntity())) {
                if (damage.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
                    this.current.fallDamage++;
                }
                this.event("damage " + damage.getCause() + " " + damage.getDamage() + " final " + damage.getFinalDamage() + " cancelled " + damage.isCancelled());
            }
        });
        this.listen(plugins, org.bukkit.event.player.PlayerStatisticIncrementEvent.class, event -> {
            final org.bukkit.event.player.PlayerStatisticIncrementEvent stat = (org.bukkit.event.player.PlayerStatisticIncrementEvent) event;
            if (this.mine(stat.getPlayer())) {
                this.event("statistic " + stat.getStatistic() + " " + stat.getMaterial() + " " + stat.getEntityType() + " " + stat.getPreviousValue() + " -> " + stat.getNewValue());
            }
        });
        this.listen(plugins, org.bukkit.event.entity.EntityExhaustionEvent.class, event -> {
            final org.bukkit.event.entity.EntityExhaustionEvent exhaustion = (org.bukkit.event.entity.EntityExhaustionEvent) event;
            if (this.mine(exhaustion.getEntity())) {
                this.event("exhaustion " + exhaustion.getExhaustionReason() + " " + exhaustion.getExhaustion());
            }
        });
        this.listen(plugins, io.papermc.paper.event.entity.EntityInsideBlockEvent.class, event -> {
            final io.papermc.paper.event.entity.EntityInsideBlockEvent inside = (io.papermc.paper.event.entity.EntityInsideBlockEvent) event;
            if (this.mine(inside.getEntity())) {
                this.current.insides++;
                this.event("inside " + inside.getBlock().getType() + " " + block(inside.getBlock()));
            }
        });
        this.listen(plugins, org.bukkit.event.player.PlayerInteractEvent.class, event -> {
            final org.bukkit.event.player.PlayerInteractEvent interact = (org.bukkit.event.player.PlayerInteractEvent) event;
            if (this.mine(interact.getPlayer())) {
                if (interact.getAction() == org.bukkit.event.block.Action.PHYSICAL) {
                    this.current.presses++;
                }
                this.event("interact " + interact.getAction() + " " + (interact.getClickedBlock() == null ? "-" : block(interact.getClickedBlock())));
            }
        });
        this.listen(plugins, org.bukkit.event.block.BlockRedstoneEvent.class, event -> {
            final org.bukkit.event.block.BlockRedstoneEvent redstone = (org.bukkit.event.block.BlockRedstoneEvent) event;
            if (this.current != null && this.currentMove >= 0 && this.near(redstone.getBlock())) {
                this.event("redstone " + block(redstone.getBlock()) + " " + redstone.getOldCurrent() + " -> " + redstone.getNewCurrent());
            }
        });
    }

    private void set(final int x, final int y, final int z, final BlockState state) {
        final BlockPos pos = new BlockPos(x, y, z);
        if (this.level.getBlockState(pos) != state) {
            this.level.setBlock(pos, state, 2 | 16);
        }
    }

    // Recording -----------------------------------------------------------------------------------------------------

    private void record() {
        // The port, seeded at the course start as a fresh player stands there, fed scripted keys; the world is the server's
        final ServerPlayer seedFrom = this.observer;
        final ClientView view = new ClientView();
        view.seed(seedFrom);
        final WorldView world = new WorldView();
        world.setLevel(this.level, false);
        final PortSimulation sim = new PortSimulation(seedFrom);
        final double sx = this.bx + 0.5;
        final double sz = this.bz + 0.5;
        sim.seed(sx, this.by, sz, 0.0, 0.0, 0.0, -90.0F, 0.0F, true, view, world);
        final SimPlayer state = sim.state();
        Input previous = Input.EMPTY;
        int phase = 0; // 0 walk the course, 1 sneak to the pit, 2 turn on the spot
        int phaseStart = 0;
        float yaw = -90.0F;
        float pitch = 0.0F;
        for (int t = 0; t < MOVES; t++) {
            final double along = sim.x() - this.bx;
            Input keys;
            if (phase == 0 && along >= 31.6 && sim.onGround() && Math.abs(sim.y() - this.by) < 1.0E-6) {
                phase = 1;
                phaseStart = t;
            } else if (phase == 1 && t - phaseStart >= 40) {
                phase = 2;
                phaseStart = t;
            }
            if (phase == 0) {
                final boolean jump = sim.horizontalCollision() && sim.onGround() || state.wasTouchingWater;
                final boolean sprint = t >= 12 && t < 22;
                keys = t < 6 ? Input.EMPTY : new Input(true, false, false, false, jump, false, sprint);
            } else if (phase == 1) {
                keys = new Input(true, false, false, false, false, true, false);
            } else {
                keys = Input.EMPTY;
                final int k = t - phaseStart;
                if (k < 10) {
                    yaw += 2.0F; // under Paper's 10-degree event threshold until they add up
                } else if (k < 15) {
                    yaw += 15.0F;
                } else if (k < 20) {
                    pitch += 12.0F;
                }
            }
            sim.tick(keys, previous, yaw, pitch, ClientStream.SPRINT_NONE, -1, false, view, world);
            previous = keys;
            this.moves[t] = new double[] {sim.x(), sim.y(), sim.z(), yaw, pitch, sim.onGround() ? 1.0 : 0.0, sim.horizontalCollision() ? 1.0 : 0.0,
                sim.vx(), sim.vy(), sim.vz()};
        }
        final double[] last = this.moves[MOVES - 1];
        this.tests.check(phase == 2 && last[0] > this.bx + 32.0, "the port walked the whole course in " + MOVES + " ticks (ends at "
            + new Vec3(last[0] - this.bx, last[1] - this.by, last[2] - this.bz) + " from its start, phase " + phase + ")");
    }

    // Runs ----------------------------------------------------------------------------------------------------------

    private void join(final int run) {
        final Run r = this.runs[run];
        r.connection = new RecordingConnection();
        r.player = this.place(run == LEGACY ? "TruthVanilla" : "TruthResolved", r.connection, this.bx + 0.5, this.bz + 0.5, -90.0F);
    }

    // A player on a recording connection (never ticked by a client), loaded, teleported to (x, by, z) and accepted
    private ServerPlayer place(final String name, final RecordingConnection connection, final double x, final double z, final float yaw) {
        final GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("revival-truth-" + name).getBytes()), name);
        final ServerPlayer player = new ServerPlayer(this.server, this.level, profile, ClientInformation.createDefault());
        this.server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        player.revivalAuth.setOverride(AuthMode.OFF);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        player.connection.teleport(x, this.by, z, yaw, 0.0F);
        ClientboundPlayerPositionPacket teleport = null;
        for (final Packet<?> packet : connection.sent) {
            if (packet instanceof final ClientboundPlayerPositionPacket position) {
                teleport = position;
            }
        }
        this.tests.check(teleport != null, name + " was sent its teleport");
        // SGPLI.tick does this every tick on a connected client; this one's connection is never ticked
        player.connection.resetPosition();
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.id(), x, this.by, z, yaw, 0.0F));
        return player;
    }

    // Both players start from the same state: what a fresh join leaves differs only in things the moves never read
    private void start(final int run) {
        final Run r = this.runs[run];
        final ServerPlayer p = r.player;
        this.tests.check(p.position().equals(new Vec3(this.bx + 0.5, this.by, this.bz + 0.5)), r.name + " player stands at the course start: " + p.position());
        p.setHealth(20.0F);
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0F);
        p.getFoodData().exhaustionLevel = 0.0F;
        p.resetFallDistance();
        p.setDeltaMovement(Vec3.ZERO);
        p.setOnGround(true);
        set(Entity.class, p, "invulnerableTime", 0);
        p.moveDist = 0.0F;
        p.flyDist = 0.0F;
        set(Entity.class, p, "nextStep", 1.0F);
        set(Entity.class, p, "firstTick", false); // the waypoint updates run, as for a player whose tick has run
        set(ServerGamePacketListenerImpl.class, p.connection, "tickCount", 1000); // support-block resends may happen once
        set(ServerGamePacketListenerImpl.class, p.connection, "lastSupportedBlocksSend", 0);
        this.tests.check(p.connection.hasClientLoaded() && get(ServerGamePacketListenerImpl.class, p.connection, "awaitingPositionFromClient") == null,
            r.name + " player is loaded with no teleport pending (loaded " + p.connection.hasClientLoaded() + ", awaiting "
                + get(ServerGamePacketListenerImpl.class, p.connection, "awaitingPositionFromClient") + ", waiting for respawn "
                + get(ServerGamePacketListenerImpl.class, p.connection, "waitingForRespawn") + ", load timer "
                + get(ServerGamePacketListenerImpl.class, p.connection, "clientLoadedTimeoutTimer") + ")");
        r.connection.sent.clear();
        this.observerConnection.sent.clear();
        r.selfMark = 0;
        r.observerMark = 0;
        this.current = r;
    }

    private void apply(final int run, final int move) {
        final Run r = this.runs[run];
        this.collect(r, move - 1);
        this.currentMove = move;
        final double[] m = this.moves[move];
        final ServerPlayer p = r.player;
        // The server tick before the window (SGPLI.tickPlayer: resetPosition, old position, then the parts of doTick that
        // read what the last move left: the fluid state and the block effects of its movement records); travel is skipped
        // under authority, so the player is not moved
        p.connection.resetPosition();
        p.xo = p.getX();
        p.yo = p.getY();
        p.zo = p.getZ();
        invoke(Entity.class, p, "updateFluidInteraction");
        invoke(Entity.class, p, "applyEffectsFromBlocks");
        p.connection.revivalApplyTruth(m[0], m[1], m[2], (float) m[3], (float) m[4], m[5] != 0.0, m[6] != 0.0, run == RESOLVED);
        if (p.getX() != m[0] || p.getY() != m[1] || p.getZ() != m[2]) {
            // CraftBukkit's justTeleported: the first move event after a teleport whose from isn't where the player stands
            // returns early, the player left at the start of the move (both branches; the next move writes the truth)
            r.offTruth++;
        }
        // AuthPlayer.applyTruth's tail
        if (!p.syncVelocity) {
            p.setDeltaMovement(m[7], m[8], m[9]);
        }
        r.states.add(this.state(p));
    }

    private void finish(final int run) {
        final Run r = this.runs[run];
        this.collect(r, MOVES - 1);
        this.currentMove = -1;
        this.current = null;
        this.server.getPlayerList().remove(r.player);
    }

    // Packets the player and the watcher got since the last move, attributed to it
    private void collect(final Run r, final int move) {
        final List<Packet<?>> self = r.connection.sent;
        final List<Packet<?>> seen = this.observerConnection.sent;
        if (move >= 0) {
            for (int i = r.selfMark; i < self.size(); i++) {
                final String line = this.describe(self.get(i), r);
                if (line != null) {
                    r.selfPackets.get(move).add(line);
                }
            }
            for (int i = r.observerMark; i < seen.size(); i++) {
                final String line = this.describe(seen.get(i), r);
                if (line != null) {
                    r.observed.get(move).add(line);
                }
            }
        }
        r.selfMark = self.size();
        r.observerMark = seen.size();
    }

    // Packets whose timing is the chunk loader's or the server tick's (chunks, light, time, latency, keep-alives), not the
    // move's: the two runs start at different server ticks
    private static final java.util.Set<Class<?>> UNTIMED = java.util.Set.of(
        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket.class,
        net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket.class,
        net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket.class,
        net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket.class,
        net.minecraft.network.protocol.game.ClientboundLightUpdatePacket.class,
        net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket.class,
        net.minecraft.network.protocol.game.ClientboundSetTimePacket.class,
        net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.class,
        net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket.class,
        net.minecraft.network.protocol.common.ClientboundKeepAlivePacket.class,
        net.minecraft.network.protocol.common.ClientboundPingPacket.class);

    private String describe(final Packet<?> packet, final Run r) {
        if (UNTIMED.contains(packet.getClass())) {
            return null;
        }
        // The tracker's own cadence (a still entity's position every 60 of its ticks, counted from its pairing): the
        // positions it sends are the player's, which the state comparison already holds equal on every move
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket) {
            r.trackerPackets++;
            return null;
        }
        return switch (packet) {
            case final ClientboundTrackedWaypointPacket ignored -> {
                r.waypointPackets++;
                yield null;
            }
            // Pitch and seed left out: splash and swim pitches come from the entities' shared random (Paper), which every
            // entity in the world draws from
            case final ClientboundSoundPacket sound -> {
                r.sounds++;
                yield "sound " + sound.getSound().unwrapKey().map(Object::toString).orElse("direct") + " " + sound.getSource() + " "
                    + sound.getX() + " " + sound.getY() + " " + sound.getZ() + " volume " + sound.getVolume();
            }
            case final ClientboundSoundEntityPacket sound -> "entity sound " + sound.getSound().unwrapKey().map(Object::toString).orElse("direct") + " "
                + sound.getSource() + " volume " + sound.getVolume();
            case final ClientboundBlockUpdatePacket update -> "block update " + update.getPos() + " " + update.getBlockState();
            default -> packet.getClass().getSimpleName();
        };
    }

    private String state(final ServerPlayer p) {
        final StringBuilder s = new StringBuilder(512);
        final AABB box = p.getBoundingBox();
        s.append("pos ").append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ())
            .append(" old ").append(p.xo).append(' ').append(p.yo).append(' ').append(p.zo)
            .append(" rot ").append(p.getYRot()).append(' ').append(p.getXRot()).append(' ').append(p.yRotO).append(' ').append(p.xRotO).append(' ').append(p.getYHeadRot())
            .append(" box ").append(box.minX).append(' ').append(box.minY).append(' ').append(box.minZ).append(' ').append(box.maxX).append(' ').append(box.maxY).append(' ').append(box.maxZ)
            .append(" block ").append(p.blockPosition()).append(" chunk ").append(p.chunkPosition())
            .append(" ground ").append(p.onGround()).append(" h ").append(p.horizontalCollision).append(" v ").append(p.verticalCollision)
            .append(" below ").append(p.verticalCollisionBelow).append(" minor ").append(p.minorHorizontalCollision)
            .append(" support ").append(p.mainSupportingBlockPos).append(' ').append(get(Entity.class, p, "onGroundNoBlocks"))
            .append(" fall ").append(p.fallDistance).append(" motion ").append(p.getDeltaMovement())
            .append(" steps ").append(p.moveDist).append(' ').append(p.flyDist).append(' ').append(get(Entity.class, p, "nextStep"))
            .append(" stuck ").append(get(Entity.class, p, "stuckSpeedMultiplier"))
            .append(" records ").append(get(Entity.class, p, "movementThisTick")).append(' ').append(get(Entity.class, p, "finalMovementsThisTick"))
            .append(" known ").append(p.getKnownMovement())
            .append(" water ").append(p.isInWater()).append(" pose ").append(p.getPose()).append(" sprint ").append(p.isSprinting())
            .append(" health ").append(p.getHealth()).append(" food ").append(p.getFoodData().getFoodLevel()).append(' ')
            .append(p.getFoodData().getSaturationLevel()).append(' ').append(p.getFoodData().exhaustionLevel)
            .append(" impulse ").append(p.currentImpulseImpactPos).append(' ').append(get(net.minecraft.world.entity.LivingEntity.class, p, "currentImpulseContextResetGraceTime"));
        final ServerGamePacketListenerImpl c = p.connection;
        for (final String field : new String[] {"lastGoodX", "lastGoodY", "lastGoodZ", "firstGoodX", "firstGoodY", "firstGoodZ", "lastPosX", "lastPosY", "lastPosZ",
            "lastYRot", "lastXRot", "hasMoved", "clientIsFloating", "receivedMovePacketCount", "knownMovePacketCount", "lastSupportedBlocksSend",
            "justTeleported", "awaitingPositionFromClient"}) {
            s.append(' ').append(field).append(' ').append(get(ServerGamePacketListenerImpl.class, c, field));
        }
        @SuppressWarnings("unchecked") final Map<Stat<?>, Integer> stats = (Map<Stat<?>, Integer>) get(StatsCounter.class, p.getStats(), "stats");
        final TreeMap<String, Integer> sorted = new TreeMap<>();
        synchronized (stats) {
            for (final Map.Entry<Stat<?>, Integer> entry : stats.entrySet()) {
                sorted.put(entry.getKey().getName(), entry.getValue());
            }
        }
        s.append(" stats ").append(sorted);
        return s.toString();
    }

    // Comparing -----------------------------------------------------------------------------------------------------

    private void compare() {
        final Run a = this.runs[LEGACY];
        final Run b = this.runs[RESOLVED];
        this.server.getPlayerList().remove(this.observer);
        this.level.getWorld().removePluginChunkTickets(this.tests);
        this.tests.check(a.states.size() == MOVES && b.states.size() == MOVES, "both took all " + MOVES + " moves");
        int events = 0;
        int packets = 0;
        for (int i = 0; i < MOVES; i++) {
            this.same(i, "state", List.of(a.states.get(i)), List.of(b.states.get(i)));
            this.same(i, "events", a.events.get(i), b.events.get(i));
            this.same(i, "packets to the player", a.selfPackets.get(i), b.selfPackets.get(i));
            this.same(i, "packets to the watcher", a.observed.get(i), b.observed.get(i));
            events += a.events.get(i).size();
            packets += a.selfPackets.get(i).size() + a.observed.get(i).size();
        }
        this.tests.check(true, String.format(Locale.ROOT, "the truth path matches the vanilla branch on all %d moves: %d events (%d moves, %d jumps, %d steps, "
                + "%d swims, %d fall damage, %d block insides, %d plate presses), %d packets (%d sounds); tracker moves %d / %d; moves left off the truth "
                + "(CraftBukkit's justTeleported) %d / %d; waypoint packets %d -> %d",
            MOVES, events, a.moveEvents, a.jumpEvents, a.steps, a.swims, a.fallDamage, a.insides, a.presses, packets, a.sounds, a.trackerPackets, b.trackerPackets,
            a.offTruth, b.offTruth, a.waypointPackets, b.waypointPackets));
        // The course has to reach what it is there for, or the comparison proves less than it says
        this.tests.check(a.moveEvents > 100 && a.jumpEvents >= 5 && a.steps > 10 && a.fallDamage >= 1 && a.insides > 0 && a.presses > 0,
            "the course exercised moves, jumps, steps, fall damage, block insides and a plate");
        this.tests.check(b.waypointPackets <= a.waypointPackets, "the truth path sends no more waypoint packets than the vanilla branch");
    }

    private void same(final int move, final String what, final List<String> a, final List<String> b) {
        if (!a.equals(b)) {
            final double[] m = this.moves[move];
            throw new AssertionError("move " + move + " (" + new Vec3(m[0] - this.bx, m[1] - this.by, m[2] - this.bz) + " from the start, onGround "
                + (m[5] != 0.0) + "): " + what + " differ\n  vanilla branch: " + a + "\n  truth path:     " + b);
        }
    }

    // Helpers -------------------------------------------------------------------------------------------------------

    private boolean mine(final org.bukkit.entity.Entity entity) {
        return entity != null && this.current != null && this.currentMove >= 0 && entity.getUniqueId().equals(this.current.player.getUUID());
    }

    private boolean near(final org.bukkit.block.Block block) {
        return Math.abs(block.getX() - (this.bx + 18)) <= 24 && Math.abs(block.getZ() - this.bz) <= 6;
    }

    private void event(final String line) {
        this.current.events.get(this.currentMove).add(line);
    }

    private void listen(final PluginManager plugins, final Class<? extends Event> type, final java.util.function.Consumer<Event> handler) {
        plugins.registerEvent(type, this.listener, EventPriority.MONITOR, (ignored, event) -> {
            if (type.isInstance(event)) {
                handler.accept(event);
            }
        }, this.tests);
    }

    private String loc(final org.bukkit.Location l) {
        return (l.getX() - this.bx) + "," + (l.getY() - this.by) + "," + (l.getZ() - this.bz) + "," + l.getYaw() + "," + l.getPitch();
    }

    private String block(final org.bukkit.block.Block block) {
        return (block.getX() - this.bx) + "," + (block.getY() - this.by) + "," + (block.getZ() - this.bz);
    }

    private static Object get(final Class<?> owner, final Object target, final String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + name, e);
        }
    }

    private static void set(final Class<?> owner, final Object target, final String name, final Object value) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + name, e);
        }
    }

    private static void invoke(final Class<?> owner, final Object target, final String name) {
        try {
            final Method method = owner.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + name + "()", e);
        }
    }
}
