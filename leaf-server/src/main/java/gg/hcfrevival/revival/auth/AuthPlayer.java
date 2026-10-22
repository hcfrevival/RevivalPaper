package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.auth.input.InputEnvelope;
import gg.hcfrevival.revival.auth.input.AnomalyWatch;
import gg.hcfrevival.revival.auth.input.InputRules;
import gg.hcfrevival.revival.auth.input.InputStats;
import gg.hcfrevival.revival.auth.net.BypassAuditor;
import gg.hcfrevival.revival.auth.net.ClientStream;
import gg.hcfrevival.revival.auth.net.OutboundClassifier;
import gg.hcfrevival.revival.auth.net.TickClock;
import gg.hcfrevival.revival.auth.net.Transactions;
import gg.hcfrevival.revival.auth.net.Wire;
import gg.hcfrevival.revival.auth.reconcile.RebaseGuard;
import gg.hcfrevival.revival.auth.reconcile.Reconciler;
import gg.hcfrevival.revival.auth.trace.TraceRecorder;
import gg.hcfrevival.revival.auth.view.ClientItems;
import gg.hcfrevival.revival.auth.view.ClientView;
import gg.hcfrevival.revival.auth.view.PredictionRules;
import gg.hcfrevival.revival.auth.view.SelfEcho;
import gg.hcfrevival.revival.auth.view.WorldView;
import gg.hcfrevival.revival.config.AuthoritativeSettings;
import io.netty.channel.ChannelFutureListener;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
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
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * One player's authoritative netcode (design §2, §3, §5, §15), held as {@code ServerPlayer.revivalAuth}. The hooks call
 * it with one line each; everything here runs on the main thread except {@link #send} (any thread, under the
 * transaction lock) and {@link #netActive} (read by Netty).
 * <p>
 * <b>Windows.</b> While a player is under authority, their gameplay packets are not handled on arrival: they are kept
 * in the current window ({@link ClientStream}) and, at {@code TickEnd(T)}, replayed through their original handlers in
 * the order they arrived, interleaved with the simulation at the client's points (§15.4): drain (acked server packets,
 * teleport accepts) → keybind and GUI actions (each {@code Attack} judged at its place) → aiStep packets → the
 * simulated tick → {@code PlayerInput}, the sprint command and the move claim → {@code TickEnd}. A window over the time
 * budget waits for budget ({@link TickClock}); past {@code timer-queue-cap-ms} it is refused.
 * <p>
 * <b>Modes.</b> In {@code observe} the replayed handlers do what they always did and the claim stays the truth; the
 * simulation is measured against it and then moved onto it. In {@code enforce} the claim is not handled: the truth is
 * written to the {@code ServerPlayer} through the vanilla accepted-move path ({@code revivalApplyTruth}), the client is
 * corrected when it leaves the true path, invalid attacks are dropped, and self echoes of client-owned state are
 * rewritten (§3.3).
 */
public final class AuthPlayer implements Transactions.Context, ClientView.EchoSink, WorldView.RevertListener {

    private static final Logger LOGGER = LogManager.getLogger("RevivalPaper");
    private static final Set<Relative> CORRECTION = EnumSet.of(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT, Relative.X_ROT);
    /** Position and velocity absolute, camera untouched: for a client stuck off the truth. */
    private static final Set<Relative> CORRECTION_ABSOLUTE = EnumSet.of(Relative.Y_ROT, Relative.X_ROT);
    private static final Set<Relative> CORRECTION_RELATIVE = EnumSet.of(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT, Relative.X_ROT,
        Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z);

    private final ServerPlayer player;
    private @Nullable AuthMode override;
    private AuthMode mode = AuthMode.OFF;
    private volatile boolean netActive;
    private boolean eligibilityChecked;
    private @Nullable String ineligible;

    private final Transactions transactions = new Transactions();
    private final ClientStream stream = new ClientStream();
    private final ClientView view = new ClientView();
    private final WorldView world = new WorldView();
    private final TickClock clock = new TickClock();
    private final Reconciler reconciler = new Reconciler();
    private final AuthStats stats = new AuthStats();
    private final RollingStats recent = new RollingStats();
    private double lastCorrectionSize;
    private @Nullable String lastHitVerdict;
    private long lastHitRewindMillis = -1L;
    private double lastHitDistance = Double.NaN;
    private final InputStats inputStats = new InputStats();
    private final InputRules rules = new InputRules();
    private final AnomalyWatch anomalies = new AnomalyWatch();
    private final InputEnvelope envelope = new InputEnvelope();
    private final Transactions.Acked forced = new Transactions.Acked();
    private final double[] decoded = new double[3];
    private Simulation simulation = new EchoSimulation();
    private volatile @Nullable TraceRecorder trace;

    /** Per-viewer state of the combat package's mirrors. */
    public @Nullable Object combatState;
    /** This player's replication state as a target (its path feed and fed viewers, {@link PathWiring}). */
    @Nullable Object replicationState;
    private long sendStamp;

    private boolean coalescing;
    // Client-owned fields the server changed on purpose (SelfEcho bits), and when; cleared when an echo carrying them is acked
    private int meant;
    private final long[] meantMillis = new long[5];
    private boolean writingTruth;
    // A correction decided at tick T-1, sent at the start of window T unless the hotbar branch explains the claim (§15.4)
    private boolean deferredCorrection;
    private double deferredX;
    private double deferredY;
    private double deferredZ;
    private volatile int windowTick = 1;
    private boolean replaying;
    private boolean warm;
    private int teleportsPending;
    private boolean reseedFromServer = true;
    private boolean frozen;
    private boolean kicked;
    private int runningTick;
    private float tickYRot;
    private float tickXRot;
    private int attacksThisTick;
    private double lastClaimX;
    private double lastClaimY;
    private double lastClaimZ;
    private boolean hasLastClaim;
    private int lastClaimTick;
    private double prevClaimX;
    private double prevClaimY;
    private double prevClaimZ;
    private int prevClaimTick;
    private boolean hasPrevClaim;
    /** The next seed is a new local player's (login, respawn): standing still where the server put it. */
    private boolean seedStill;

    public AuthPlayer(final ServerPlayer player) {
        this.player = player;
    }

    // Accessors for the simulation, combat and reconcile parts ------------------------------------------------------

    public ServerPlayer player() {
        return this.player;
    }

    /** The mode this player runs in now. */
    public AuthMode mode() {
        return this.mode;
    }

    /** Per-player override (API); null follows the server-wide mode. */
    public @Nullable AuthMode override() {
        return this.override;
    }

    public void setOverride(final @Nullable AuthMode mode) {
        this.override = mode;
    }

    public boolean netActive() {
        return this.netActive;
    }

    public ClientView view() {
        return this.view;
    }

    public WorldView world() {
        return this.world;
    }

    public Simulation simulation() {
        return this.simulation;
    }

    public ClientStream stream() {
        return this.stream;
    }

    public Transactions transactions() {
        return this.transactions;
    }

    public AuthStats stats() {
        return this.stats;
    }

    /** The last 10 seconds (playtest HUD). */
    public RollingStats recent() {
        return this.recent;
    }

    /** |Δpos| of the last correction sent, in blocks. */
    public double lastCorrectionSize() {
        return this.lastCorrectionSize;
    }

    /** The last attack's verdict as the HUD shows it ({@code VALID}, {@code INVALID MISSED}...), or null. */
    public @Nullable String lastHitVerdict() {
        return this.lastHitVerdict;
    }

    /** How old the target position behind the last judged attack was, in ms, or -1. */
    public long lastHitRewindMillis() {
        return this.lastHitRewindMillis;
    }

    /** What the judge found for the last attack (the combat wiring calls this). */
    public void recordHit(final String verdict, final long rewindMillis, final double distance) {
        this.lastHitVerdict = verdict;
        this.lastHitRewindMillis = rewindMillis;
        this.lastHitDistance = distance;
    }

    public InputRules inputRules() {
        return this.rules;
    }

    public InputStats inputStats() {
        return this.inputStats;
    }

    public TickClock clock() {
        return this.clock;
    }

    public Reconciler reconciler() {
        return this.reconciler;
    }

    /** The client tick being run ({@code T}): attacks and mirrors are keyed by it. */
    public int tick() {
        return this.runningTick;
    }

    /** The rotation of tick {@code T}: the attack ray's direction (report 02 §2.2). */
    public float tickYRot() {
        return this.tickYRot;
    }

    public float tickXRot() {
        return this.tickXRot;
    }

    /** Server teleports sent but not yet applied by the client: in-flight windows are not written or judged (§15.3). */
    public boolean teleportPending() {
        return this.teleportsPending > 0;
    }

    public @Nullable String ineligible() {
        return this.ineligible;
    }

    public boolean frozen() {
        return this.frozen;
    }

    public @Nullable TraceRecorder trace() {
        return this.trace;
    }

    // Transactions.Context ----------------------------------------------------------------------------------------

    @Override
    public int selfId() {
        return this.player.getId();
    }

    @Override
    public UUID selfUuid() {
        return this.player.getUUID();
    }

    @Override
    public int windowTick() {
        return this.windowTick;
    }

    @Override
    public int serverTick() {
        return MinecraftServer.currentTick;
    }

    @Override
    public long stampMillis() {
        return this.sendStamp;
    }

    @Override
    public boolean coalesce() {
        return this.coalescing && MinecraftServer.getServer().isSameThread();
    }

    // Lifecycle ---------------------------------------------------------------------------------------------------

    private AuthoritativeSettings settings() {
        return Revival.settings().authoritative();
    }

    // Eligibility per player (§15.3): a real 26.3 connection, not a bot and not Bedrock
    private @Nullable String checkEligibility() {
        if (!this.player.isRealPlayer || this.player.connection == null) {
            return "not a real connection";
        }
        final String via = Eligibility.viaVersionMismatch(this.player.getUUID());
        if (via != null) {
            return via;
        }
        if (Eligibility.isBedrock(this.player.getUUID())) {
            return "a Bedrock (Geyser/Floodgate) player";
        }
        return null;
    }

    private void switchMode(AuthMode wanted) {
        if (wanted == this.mode) {
            return;
        }
        if (wanted == AuthMode.OFF) {
            this.deactivate();
            return;
        }
        if (!this.eligibilityChecked) {
            this.eligibilityChecked = true;
            this.ineligible = this.checkEligibility();
        }
        if (this.ineligible != null) {
            return;
        }
        if (this.mode == AuthMode.OFF) {
            this.activate();
            if (wanted == AuthMode.ENFORCE && !this.view.selfCollisionNever()) {
                wanted = AuthMode.OBSERVE;
            }
        }
        this.mode = wanted;
        this.reconciler.reset();
        this.simulation.trackBelief(wanted == AuthMode.ENFORCE);
    }

    // §11 visibility ---------------------------------------------------------------------------------------------------

    private final gg.hcfrevival.revival.auth.visibility.Visibility visibility = new gg.hcfrevival.revival.auth.visibility.Visibility();

    public gg.hcfrevival.revival.auth.visibility.Visibility visibility() {
        return this.visibility;
    }

    /**
     * Whether {@code target} may be sent to this client: closer than {@code always-visible-distance}, or in line of sight
     * through this client's blocks from its eye region (grown by what it can move over its round trip) to the target's
     * box (grown by what the target moves before the client shows it). Main thread.
     */
    boolean sees(final ServerPlayer target, final AuthoritativeSettings settings) {
        if (this.mode == AuthMode.OFF || !this.netActive || !this.simulation.seeded() || this.world.level() != target.level()) {
            return true;
        }
        final int id = target.getId();
        final int tick = MinecraftServer.currentTick;
        if (!this.visibility.due(id, tick, settings.visibilityCheckIntervalTicks())) {
            return this.visibility.sent(id);
        }
        final Simulation sim = this.simulation;
        final double eyeX = sim.x();
        final double eyeY = sim.y() + sim.eyeHeight();
        final double eyeZ = sim.z();
        final net.minecraft.world.phys.AABB box = target.getBoundingBox();
        final double cx = (box.minX + box.maxX) * 0.5 - eyeX;
        final double cy = (box.minY + box.maxY) * 0.5 - eyeY;
        final double cz = (box.minZ + box.maxZ) * 0.5 - eyeZ;
        final double always = settings.alwaysVisibleDistance();
        boolean seen = cx * cx + cy * cy + cz * cz <= always * always;
        if (!seen) {
            final double speed = settings.visibilityMaxSpeed();
            final double rttTicks = this.transactions.rttBaseNanos() / 50_000_000.0;
            final double grow = speed * (rttTicks + settings.visibilityCheckIntervalTicks());
            final double targetGrow = speed * (settings.visibilityCheckIntervalTicks() + settings.pathStepTicks());
            seen = gg.hcfrevival.revival.auth.visibility.Occlusion.boxesSeeEachOther(this.world, eyeX - grow, eyeY - grow, eyeZ - grow,
                eyeX + grow, eyeY + grow, eyeZ + grow, box.minX - targetGrow, box.minY - targetGrow, box.minZ - targetGrow,
                box.maxX + targetGrow, box.maxY + targetGrow, box.maxZ + targetGrow);
        }
        final boolean sent = this.visibility.decide(id, tick, seen, settings.visibilityHideAfterChecks());
        if (!sent) {
            this.stats.hiddenChecks++;
        }
        return sent;
    }

    // §11: sounds and particles that would give a hidden player away
    private boolean givesAwayHidden(final Packet<?> packet) {
        final it.unimi.dsi.fastutil.ints.IntOpenHashSet hidden = this.visibility.hidden();
        if (hidden.isEmpty()) {
            return false;
        }
        double x;
        double y;
        double z;
        switch (packet) {
            case final net.minecraft.network.protocol.game.ClientboundSoundEntityPacket sound -> {
                return hidden.contains(sound.getId());
            }
            case final net.minecraft.network.protocol.game.ClientboundSoundPacket sound -> {
                x = sound.getX();
                y = sound.getY();
                z = sound.getZ();
            }
            case final net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket particles -> {
                x = particles.x();
                y = particles.y();
                z = particles.z();
            }
            default -> {
                return false;
            }
        }
        for (final it.unimi.dsi.fastutil.ints.IntIterator it = hidden.iterator(); it.hasNext(); ) {
            final net.minecraft.world.entity.Entity target = this.player.level().getEntity(it.nextInt());
            if (target != null && target.getBoundingBox().inflate(this.settings().visibilityEffectReach()).contains(x, y, z)) {
                this.stats.culledEffects++;
                return true;
            }
        }
        return false;
    }

    // §15.1 bypass audit: physics packets that reached the client around our choke point, logged at the next ping
    private @Nullable BypassAuditor auditor;
    private boolean bypassWarned;

    private void installAuditor() {
        if (!this.settings().bypassAudit()) {
            return;
        }
        final BypassAuditor audit = new BypassAuditor(this.player.getId(),
            this.transactions.auditQueue(), this.player.level().registryAccess());
        final io.netty.channel.Channel channel = this.player.connection.connection.channel;
        if (channel != null && audit.install(channel)) {
            this.auditor = audit;
            this.transactions.setAuditing(true);
        }
    }

    private void uninstallAuditor() {
        this.transactions.setAuditing(false);
        final BypassAuditor audit = this.auditor;
        this.auditor = null;
        if (audit != null) {
            audit.uninstall();
        }
    }

    /** The bypass auditor, when the channel has one (tests, inspect). */
    public @Nullable BypassAuditor auditor() {
        return this.auditor;
    }

    private void drainBypasses() {
        final BypassAuditor audit = this.auditor;
        if (audit == null) {
            return;
        }
        Packet<?> packet;
        while ((packet = audit.pollBypassed()) != null) {
            this.onBypass(packet);
        }
    }

    /** A physics packet the client got without our ping around it: logged now, applied at the next ack (upper bound). */
    public void onBypass(final Packet<?> packet) {
        final int kind = OutboundClassifier.classify(packet, this.player.getId(), this.player.getUUID(), it.unimi.dsi.fastutil.ints.IntSets.EMPTY_SET);
        if (kind == OutboundClassifier.NONE) {
            return;
        }
        this.stats.bypasses++;
        if (kind != OutboundClassifier.BLOCK_UPDATE && kind != OutboundClassifier.SECTION_UPDATE) {
            this.stats.bypassMovers++;
        }
        this.transactions.logBypass(this.player.connection.connection, packet, kind, this);
        if (!this.bypassWarned) {
            this.bypassWarned = true;
            LOGGER.warn("{} was sent a {} that did not go through RevivalPaper's netcode (a plugin writing packets directly: PacketEvents, "
                + "ProtocolLib, GrimAC). It is applied late; turn GrimAC's movement, velocity and setback handling off for players under "
                + "authoritative movement", this.player.getScoreboardName(), packet.type().id());
        }
    }

    private void activate() {
        this.installAuditor();
        this.simulation = Authority.newSimulation(this);
        this.view.seed(this.player);
        this.simulation.attributes(this.player.getAttributes().getSyncableAttributesPacket());
        this.world.setLevel(this.player.level(), false);
        this.stream.forgetClient();
        this.transactions.reset();
        this.clock.start(System.nanoTime(), this.view.tickPeriodNanos(), this.settings().maxBankedTicks());
        this.warm = false;
        this.teleportsPending = 0;
        this.reseedFromServer = true;
        this.frozen = false;
        this.netActive = true;
        Authority.activated(this);
        Authority.mirrors().onReset(this);
    }

    private void deactivate() {
        this.uninstallAuditor();
        this.netActive = false;
        this.mode = AuthMode.OFF;
        Authority.deactivated(this);
        // Nothing the client sent is lost: buffered windows run through the handlers now, as vanilla would have
        this.stream.drainTo(window -> {
            for (int i = 0; i < window.items(); i++) {
                if (window.kind(i) == ClientStream.PACKET || window.kind(i) == ClientStream.ROTATION_REPLY) {
                    this.replay((Packet<?>) window.ref(i));
                }
            }
            this.stream.recycle(window);
        });
        this.transactions.reset();
        Authority.mirrors().onReset(this);
    }

    // Outbound (hook 9) ----------------------------------------------------------------------------------------------

    /**
     * Any thread. Physics packets are logged and pinged; while the simulation owns the client's state (enforce, not
     * exempt), self echoes of client-owned state are rewritten. Exempt players get vanilla echoes, as the server's own
     * recomputation runs for them.
     */
    void send(final Connection connection, final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) {
        Packet<?> out = packet;
        if (!this.visibility.hidden().isEmpty() && MinecraftServer.getServer().isSameThread() && this.givesAwayHidden(packet)) {
            return;
        }
        if (this.ownsClientState() && MinecraftServer.getServer().isSameThread()) {
            final Simulation sim = this.simulation;
            if (packet instanceof final ClientboundSetEntityDataPacket data && data.id() == this.player.getId()) {
                out = SelfEcho.entityData(data, sim.sprinting(), sim.swimming(), sim.fallFlying(), this.meant);
                if (out == null) {
                    return;
                }
            } else if (packet instanceof final ClientboundUpdateAttributesPacket attributes && attributes.getEntityId() == this.player.getId()) {
                final ClientboundUpdateAttributesPacket echo = (this.meant & SelfEcho.SPRINT) == 0 ? SelfEcho.attributes(attributes, sim.sprinting()) : attributes;
                out = echo;
                final int speed = SelfEcho.speedSync(echo);
                if (speed >= 0) {
                    // A movement-speed sync on its way: if the sprint changes before its ack, the client would keep the
                    // wrong modifier (MC-69459), so another one follows then
                    this.speedSyncsPending++;
                    this.speedSyncSprint = speed == 1;
                }
            }
        }
        this.transactions.send(connection, out, listener, flush, this);
    }

    /**
     * Sends a replicated path step to this player (main thread), logged with the step's own server time so lag
     * compensation measures rewind from when the target was there (§15.7).
     */
    void sendStamped(final Packet<?> packet, final long stamp) {
        this.sendStamp = stamp;
        try {
            this.player.connection.send(packet);
        } finally {
            this.sendStamp = 0L;
        }
    }

    /** {@code suspendFlushing}: the server tick starts; classified packets gather into one bundle until it ends. */
    public void onSuspendFlushing() {
        this.coalescing = this.netActive;
    }

    /** {@code resumeFlushing}: the tick's bundle (or a heartbeat ping) goes out before the flush. */
    public void onResumeFlushing(final Connection connection) {
        this.coalescing = false;
        if (this.netActive) {
            this.transactions.endOfTick(connection, this, this.settings().heartbeatPing());
        }
    }

    // Inbound hooks --------------------------------------------------------------------------------------------------

    /**
     * Top of every gameplay handler (hook 1011): returns true when the handler must not run now. Under authority the
     * packet joins the current window and runs when the window does; {@code TickEnd} closes the window.
     */
    public boolean intercept(final Packet<?> packet) {
        if (this.replaying) {
            return false;
        }
        if (packet instanceof ServerboundClientTickEndPacket) {
            return this.onTickEnd(packet);
        }
        if (this.mode == AuthMode.OFF) {
            return false;
        }
        if (packet instanceof final ServerboundMovePlayerPacket move && !finite(move)) {
            if (Double.isNaN(move.getX(0.0)) || Double.isNaN(move.getY(0.0)) || Double.isNaN(move.getZ(0.0))
                || !Float.isFinite(move.getYRot(0.0F)) || !Float.isFinite(move.getXRot(0.0F))) {
                this.violation("NaN move");
                return false; // vanilla disconnects for these, as it always has
            }
            // Infinite coordinates (vanilla would clamp them): never a claim, never a correction off them
            this.protocolViolation("non-finite move");
            return true;
        }
        switch (this.stream.recordChecked(packet)) {
            case ClientStream.SECOND_CLAIM -> this.protocolViolation("a second move claim, input or sprint command in one client tick");
            case ClientStream.OVERFLOW -> {
                this.protocolViolation("a client tick past " + ClientStream.MAX_ITEMS + " packets (no TickEnd)");
                return true; // dropped
            }
            default -> {
            }
        }
        this.traceInbound(packet);
        return true;
    }

    private static boolean finite(final ServerboundMovePlayerPacket move) {
        return Double.isFinite(move.getX(0.0)) && Double.isFinite(move.getY(0.0)) && Double.isFinite(move.getZ(0.0))
            && Float.isFinite(move.getYRot(0.0F)) && Float.isFinite(move.getXRot(0.0F));
    }

    private boolean onTickEnd(final Packet<?> packet) {
        AuthMode wanted = Authority.modeFor(this);
        if (wanted == AuthMode.ENFORCE && this.mode != AuthMode.OFF && !this.view.selfCollisionNever()) {
            // Precondition (§15.3): other players may push this client, which the simulation can't see; measure only
            wanted = AuthMode.OBSERVE;
        }
        if (wanted != this.mode) {
            this.switchMode(wanted);
        }
        if (this.mode == AuthMode.OFF) {
            return false;
        }
        final TraceRecorder recorder = this.trace;
        if (recorder != null) {
            recorder.tickEnd();
        }
        this.stream.close(packet, System.nanoTime());
        this.windowTick = this.stream.currentTick();
        this.runReady();
        return true;
    }

    /** {@code handlePong} via {@link Authority#onPong}, on the main thread in stream order. */
    void onPong(final int id) {
        if (this.mode == AuthMode.OFF) {
            return;
        }
        final Transactions.Acked acked = this.stream.currentAcked();
        final int from = acked.size();
        final int status = this.transactions.ack(id, MinecraftServer.currentTick, acked);
        final TraceRecorder recorder = this.trace;
        if (recorder != null) {
            recorder.pong(id);
        }
        switch (status) {
            case Transactions.ACKED, Transactions.SKIPPED -> {
                this.warm = true;
                boolean rotation = false;
                int correction = 0;
                for (int i = from; i < acked.size(); i++) {
                    final Packet<?> entry = acked.packet(i);
                    if (entry instanceof ClientboundPlayerRotationPacket) {
                        rotation = true;
                    } else if (entry instanceof final ClientboundPlayerPositionPacket position && Reconciler.ours(position.id())) {
                        correction = position.id();
                    }
                }
                if (!this.stream.recordAck(from, acked.size(), rotation, correction)) {
                    // Pongs belong to the drain, before the tick's keybinds: a late one would choose when a packet lands
                    this.protocolViolation("a pong after the tick's keybinds");
                }
                if (status == Transactions.SKIPPED) {
                    this.protocolViolation("skipped pings");
                }
            }
            case Transactions.UNKNOWN -> this.protocolViolation("pong for a ping never sent");
            default -> {
            }
        }
    }

    /**
     * {@code handleAcceptTeleportPacket}, after the thread check. Returns true when vanilla must skip it (one of our
     * corrections). The accept is also recorded in the window's drain, where it is checked as a claim.
     */
    public boolean onTeleportAccept(final ServerboundAcceptTeleportationPacket packet) {
        if (this.replaying) {
            return false;
        }
        if (this.mode == AuthMode.OFF) {
            // A teleport enforce completed at its pong before authority went off: vanilla no longer waits for it
            final int index = this.awaitingAccepts.indexOf(packet.id());
            if (index >= 0) {
                this.awaitingAccepts.removeElements(0, index + 1);
                this.awaitingSince.removeElements(0, index + 1);
                return true;
            }
            return false;
        }
        final TraceRecorder recorder = this.trace;
        if (recorder != null) {
            recorder.teleportAck(packet.id(), packet.x(), packet.y(), packet.z(), packet.yRot(), packet.xRot());
        }
        if (this.mode == AuthMode.ENFORCE && !(Double.isFinite(packet.x()) && Double.isFinite(packet.y()) && Double.isFinite(packet.z())
            && Float.isFinite(packet.yRot()) && Float.isFinite(packet.xRot()))) {
            this.protocolViolation("a non-finite teleport accept"); // vanilla kicks these too; under enforce it never sees them
            return true;
        }
        this.stream.recordAccept(packet);
        if (this.mode == AuthMode.ENFORCE && !Reconciler.ours(packet.id())) {
            // A teleport sent before authority started has no pong of ours: vanilla's bookkeeping happens here (its
            // destination, never the accept's coordinates); a no-op when its pong already completed it
            if (this.player.connection.revivalCompleteServerTeleport(packet.id())) {
                this.acceptedBeforeAuthority.add(packet.id());
            }
        }
        // Enforce: every accept is ours, judged as a claim in its window; vanilla never takes its coordinates (§15.3)
        return this.mode == AuthMode.ENFORCE || Reconciler.ours(packet.id());
    }

    // Server teleports applied at their pong window whose accept hasn't come yet: ids and the window they were applied in
    private final it.unimi.dsi.fastutil.ints.IntArrayList awaitingAccepts = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private final it.unimi.dsi.fastutil.ints.IntArrayList awaitingSince = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private final it.unimi.dsi.fastutil.ints.IntArrayList earlyAccepts = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private final it.unimi.dsi.fastutil.ints.IntArrayList acceptedBeforeAuthority = new it.unimi.dsi.fastutil.ints.IntArrayList();

    // A server teleport's accept must follow its pong in the same drain; past the ack deadline it never will
    private void checkAwaitingAccepts(final int tick, final int deadline) {
        if (!this.awaitingAccepts.isEmpty() && tick - this.awaitingSince.getInt(0) > deadline) {
            this.awaitingAccepts.clear();
            this.awaitingSince.clear();
            this.protocolViolation("a server teleport was never accepted");
        }
    }

    /** {@code internalTeleport}, right before the packet is sent: a server teleport is a transaction (§15.3). */
    public void onTeleportSent(final int id, final PositionMoveRotation destination, final Set<Relative> relatives) {
        if (!this.netActive) {
            return;
        }
        this.teleportsPending++;
        this.stats.serverTeleports++;
        this.reconciler.reset();
    }

    /** {@code SGPLI.tick()}: windows the clock now allows run; frozen and kick checks for unanswered pings. */
    public void onServerTick() {
        if (this.mode == AuthMode.OFF) {
            return; // activation happens at a TickEnd, at a window boundary
        }
        this.drainBypasses();
        final long now = System.nanoTime();
        // Unanswered pings, or a client tick that never ends while packets keep coming: frozen, then kicked
        final long unanswered = Math.max(this.transactions.oldestOutstandingNanos(now), this.stream.openWindowAgeNanos(now));
        final long limit = this.settings().maxUnackedMs() * 1_000_000L;
        this.frozen = unanswered > limit;
        if (unanswered > 3 * limit && this.mode == AuthMode.ENFORCE && !this.kicked) {
            this.kicked = true;
            this.player.connection.disconnect(Component.literal("Timed out (no transaction replies)"));
            return;
        }
        this.runReady();
        DebugHud.tick(this, this.settings());
    }

    /**
     * Whether the simulation is the only writer of this player's pose, swimming, gliding and item-use state on the
     * {@code ServerPlayer} (§15.4): the server's own per-tick recomputation of them is skipped.
     */
    public boolean ownsClientState() {
        return this.mode == AuthMode.ENFORCE && this.lastExempt == AuthStats.EXEMPT_NONE && this.simulation.seeded();
    }

    /**
     * A client-owned field changed on the {@code ServerPlayer} ({@code setSharedFlag}, {@code setPose},
     * {@code setLivingEntityFlag}). Outside the truth writer and the replay of the client's own packets, that change is
     * the server's (use completion, an elytra breaking, a plugin setter): its echo goes to the client as is, and the
     * truth writer leaves the field alone until the client has acked it.
     */
    public void onServerWrite(final int field) {
        if (this.writingTruth || this.replaying || !this.ownsClientState()) {
            return;
        }
        this.markMeant(field);
    }

    /**
     * {@code LivingEntity.resyncUsingItem}: Paper resends the use flags when it refuses a use (a cancelled interact), during
     * the replay of the client's own packet. That resync is the server's, so it goes out as meant.
     */
    public void onServerResync() {
        if (this.ownsClientState()) {
            this.markMeant(SelfEcho.USE);
        }
    }

    private void markMeant(final int field) {
        this.meant |= field;
        final long now = System.currentTimeMillis();
        for (int i = 0; i < this.meantMillis.length; i++) {
            if ((field & 1 << i) != 0) {
                this.meantMillis[i] = now;
            }
        }
    }

    /** Fields a self entity-data packet sent at {@code sentMillis} carried, now acked: the client has the server's value. */
    private void meantAcked(final java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> values, final long sentMillis) {
        if (this.meant == 0) {
            return;
        }
        int carried = 0;
        for (final net.minecraft.network.syncher.SynchedEntityData.DataValue<?> value : values) {
            switch (value.id()) {
                case ClientView.DATA_SHARED_FLAGS -> carried |= SelfEcho.SPRINT | SelfEcho.SWIM | SelfEcho.GLIDE;
                case ClientView.DATA_POSE -> carried |= SelfEcho.POSE;
                case ClientView.DATA_LIVING_ENTITY_FLAGS -> carried |= SelfEcho.USE;
                default -> {
                }
            }
        }
        for (int i = 0; i < this.meantMillis.length; i++) {
            if ((carried & this.meant & 1 << i) != 0 && sentMillis >= this.meantMillis[i]) {
                this.meant &= ~(1 << i);
            }
        }
    }

    // A meant field whose echo never came back (no packet, or lost) stops being meant after the ack deadline
    private void expireMeant(final long nowMillis, final long deadlineMillis) {
        for (int i = 0; i < this.meantMillis.length; i++) {
            if ((this.meant & 1 << i) != 0 && nowMillis - this.meantMillis[i] > deadlineMillis) {
                this.meant &= ~(1 << i);
            }
        }
    }

    /** Client-owned fields the server means to change, not yet acked (SelfEcho bits). */
    public int meant() {
        return this.meant;
    }

    /** {@code LivingEntity.aiStep} (hook 15): the zero-input server travel is skipped while the simulation owns movement. */
    public boolean skipsTravel() {
        return this.mode == AuthMode.ENFORCE && this.simulation.seeded() && this.exemptReason() == AuthStats.EXEMPT_NONE;
    }

    // Running windows ------------------------------------------------------------------------------------------------

    private void runReady() {
        final AuthoritativeSettings settings = this.settings();
        final long period = this.view.tickPeriodNanos();
        final long drift = settings.timerDriftMs() * 1_000_000L;
        final long cap = settings.timerQueueCapMs() * 1_000_000L;
        ClientStream.Window window;
        while ((window = this.stream.peek()) != null && this.mode != AuthMode.OFF) {
            final long now = System.nanoTime();
            final boolean refused;
            if (this.frozen) {
                refused = true;
                this.clock.refuse();
            } else if (this.clock.tryRun(now, this.transactions.lastAckedSentNanos(), period, drift, settings.maxBankedTicks())) {
                refused = false;
            } else if (now - window.closedNanos() > cap) {
                refused = true;
                this.clock.refuse();
            } else {
                return; // waits for budget
            }
            this.stream.poll();
            try {
                this.runWindow(window, refused, settings);
            } finally {
                this.stream.recycle(window);
            }
        }
    }

    private void runWindow(final ClientStream.Window window, final boolean refused, final AuthoritativeSettings settings) {
        final int tick = window.tick();
        this.runningTick = tick;
        this.stats.windows++;
        if (this.transactions.takeLostSync()) {
            this.resync();
            this.stats.lostSync++;
        }
        if (this.world.level() != this.player.level() && this.teleportsPending == 0) {
            // A level change without a respawn the client acked (Leaf smooth teleport): resync from the server
            this.resync();
        }

        // Deadlines: entries still unacked past ceil(rttBase / 50) + jitter windows are applied now (§15.1)
        final int deadline = (int) Math.ceil(this.transactions.rttBaseNanos() / 50_000_000.0) + settings.ackJitterTicks();
        this.forced.clear();
        if (this.transactions.forceDue(tick, deadline, this.forced) > 0) {
            for (int i = 0; i < this.forced.size(); i++) {
                this.apply(this.forced.packet(i), this.forced.kind(i), tick, false, this.forced.sentMillis(i));
                this.stats.forcedEntries++;
            }
            this.forced.clear();
        }
        this.world.expire(tick - deadline, this);
        this.checkAwaitingAccepts(tick, deadline);
        this.expireMeant(System.currentTimeMillis(), deadline * 50L + 1000L);
        if (this.deferredCorrection) {
            this.resolveCorrection(window);
        }

        // The window's sendChanges packets, read ahead: the simulated tick needs them before they replay
        Input sentInput = null;
        int sprint = ClientStream.SPRINT_NONE;
        ServerboundMovePlayerPacket claim = null;
        int flying = -1;
        boolean fallFlying = false;
        int lastClose = -1;
        for (int i = 0; i < window.items(); i++) {
            if (window.kind(i) != ClientStream.PACKET) {
                continue;
            }
            switch (window.ref(i)) {
                case final net.minecraft.network.protocol.game.ServerboundContainerClosePacket ignored -> lastClose = i;
                case final ServerboundPlayerInputPacket input -> sentInput = input.input();
                case final ServerboundMovePlayerPacket move -> claim = move;
                case final ServerboundPlayerAbilitiesPacket abilities -> flying = abilities.isFlying() ? 1 : 0;
                case final ServerboundPlayerCommandPacket command -> {
                    switch (command.getAction()) {
                        case START_SPRINTING -> sprint = ClientStream.SPRINT_START;
                        case STOP_SPRINTING -> sprint = ClientStream.SPRINT_STOP;
                        case START_FALL_FLYING -> fallFlying = true;
                        default -> {
                        }
                    }
                }
                case null, default -> {
                }
            }
        }
        this.attacksThisTick = 0;
        // The input grammar (§15.4) judges each action against the input the tick ends with
        this.rules.startWindow(InputRules.walking(sentInput != null ? sentInput : this.stream.input()));

        boolean simulated = false;
        boolean rotationResolved = false;
        Packet<?> tickEnd = null;
        for (int i = 0; i < window.items(); i++) {
            switch (window.kind(i)) {
                case ClientStream.ACK -> {
                    for (int e = window.a(i); e < window.b(i); e++) {
                        this.apply(window.acked.packet(e), window.acked.kind(e), tick, !window.acked.upperBound(e), window.acked.sentMillis(e));
                    }
                }
                case ClientStream.ACCEPT -> this.checkAccept((ServerboundAcceptTeleportationPacket) window.ref(i), settings);
                case ClientStream.ROTATION_REPLY -> {
                    final ServerboundMovePlayerPacket reply = (ServerboundMovePlayerPacket) window.ref(i);
                    this.stream.setRotation(reply.getYRot(this.stream.yRot()), reply.getXRot(this.stream.xRot()));
                    if (this.mode == AuthMode.OBSERVE && !refused) {
                        this.replay(reply);
                    }
                }
                case ClientStream.PACKET -> {
                    final Packet<?> packet = (Packet<?>) window.ref(i);
                    final int category = window.a(i);
                    if (!rotationResolved && category != ClientStream.GUI) {
                        // The tick's rotation, after the drain applied any teleport or rotation packet (report 02 §2.2)
                        this.resolveRotation(claim);
                        rotationResolved = true;
                        this.rules.keybinds(this.simulation.seeded() && this.simulation.usingItem());
                    }
                    final int rule = this.rules.check(InputRules.action(packet), i < lastClose);
                    // Enforce: an action no vanilla client sends is not handled; the client's own prediction still runs
                    final boolean dropped = rule != InputRules.OK && this.mode == AuthMode.ENFORCE;
                    if (rule != InputRules.OK && settings.debugLog()) {
                        DebugHud.grammar(this, tick, InputRules.name(rule), packet, dropped);
                    }
                    if (category == ClientStream.TICK_END) {
                        tickEnd = packet;
                        continue;
                    }
                    if (!simulated && category >= ClientStream.INPUT) {
                        this.simulate(sentInput, sprint, flying, fallFlying, claim, refused, settings);
                        simulated = true;
                    }
                    this.runPacket(packet, category, tick, refused || dropped, settings);
                    if (dropped) {
                        this.stats.grammarDropped++;
                        if (packet instanceof ServerboundAttackPacket) {
                            this.recordHit("INVALID grammar " + InputRules.name(rule), -1L, Double.NaN);
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (!rotationResolved) {
            this.resolveRotation(claim);
        }
        if (!simulated) {
            this.simulate(sentInput, sprint, flying, fallFlying, claim, refused, settings);
        }
        if (tickEnd != null) {
            this.replay(tickEnd); // vanilla's per-tick resets (known movement, the two-positions guard) at the right point
        }

        this.reconciler.afterWindow();
        this.mirrorTick(tick);
        this.inputStats.onTick(tick, this.attacksThisTick, claim != null && claim.hasRotation(), this.tickYRot);
        if (settings.anomalyAlerts()) {
            this.checkAnomalies(settings);
        }
        this.envelope.endTick();
        this.view.tick();
    }

    // AuthInputAnomalyEvent once per crossing of a stats threshold (design §10)
    private void checkAnomalies(final AuthoritativeSettings settings) {
        final InputStats input = this.inputStats;
        this.anomaly(AnomalyWatch.DOUBLE_CLICKS, input.doubleClickRatio(), settings.anomalyDoubleClickRatio(),
            input.attackTicks() >= settings.anomalyMinAttackTicks());
        this.anomaly(AnomalyWatch.AIM_GCD, input.turns() == 0 ? 0.0 : (double) input.gcdMisses() / input.turns(), settings.anomalyAimGcdMissRatio(),
            input.turns() >= settings.anomalyMinTurns());
        this.anomaly(AnomalyWatch.INPUT_GRAMMAR, this.rules.total(), settings.anomalyGrammarViolations(), true);
    }

    private void anomaly(final int kind, final double value, final double threshold, final boolean enough) {
        if (this.anomalies.crossed(kind, value, threshold, enough)) {
            LOGGER.info("[auth] input anomaly {}: {} {} (threshold {})", this.player.getScoreboardName(), AnomalyWatch.name(kind), value, threshold);
            new gg.hcfrevival.revival.api.event.AuthInputAnomalyEvent(this.player.getBukkitEntity(), AnomalyWatch.name(kind), value, threshold).callEvent();
        }
    }

    // One buffered gameplay packet, at its place in the window
    private void runPacket(final Packet<?> packet, final int category, final int tick, final boolean refused, final AuthoritativeSettings settings) {
        switch (packet) {
            case final ServerboundSetCarriedItemPacket carried -> {
                this.view.items.select(carried.getSlot());
                this.replayUnless(refused, packet);
            }
            case final ServerboundPlayerActionPacket action -> {
                if (action.getSequence() > 0) {
                    this.player.connection.ackBlockChangesUpTo(action.getSequence()); // acked even when the handler drops it (§15.2)
                }
                switch (action.getAction()) {
                    case DROP_ITEM -> this.view.items.dropSelected(false);
                    case DROP_ALL_ITEMS -> this.view.items.dropSelected(true);
                    case RELEASE_USE_ITEM -> this.simulation.releaseUsingItem();
                    default -> {
                    }
                }
                final boolean breaking = action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                    || action.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK;
                // §15.2: the client breaks (and predicts) only the block on its crosshair; judged before the handler runs
                final boolean admitted = breaking && this.onCrosshair(action.getPos());
                this.replayUnless(refused, packet);
                if (breaking) {
                    this.predict(action.getPos(), action.getSequence(), tick, admitted);
                }
            }
            case final ServerboundUseItemPacket use -> {
                this.player.connection.ackBlockChangesUpTo(use.sequence()); // acked even when Paper's spam limit drops it (§15.2)
                this.replayUnless(refused, packet);
                final InteractionHand hand = use.hand();
                Boolean predicted = this.view.predictsUse(this.view.items.hand(hand));
                if (predicted == null) {
                    // An item whose class overrides use: the server's own result stands in for the client's prediction
                    predicted = !refused && this.player.isUsingItem() && this.player.getUsedItemHand() == hand;
                }
                if (predicted) {
                    this.simulation.startUsingItem(hand); // a refusal's resync (onServerResync) reaches the client as meant
                }
            }
            case final ServerboundUseItemOnPacket useOn -> {
                this.player.connection.ackBlockChangesUpTo(useOn.sequence()); // acked even when Paper's spam limit drops it (§15.2)
                final BlockHitResult hit = useOn.hitResult();
                // §15.2: a vanilla client places only a block item it holds, on the block on its crosshair
                final boolean admitted = PredictionRules.canPlace(this.view.items.hand(useOn.hand())) && this.onCrosshair(hit.getBlockPos());
                this.replayUnless(refused, packet);
                this.predict(hit.getBlockPos(), useOn.sequence(), tick, admitted);
                this.predict(hit.getBlockPos().relative(hit.getDirection()), useOn.sequence(), tick, admitted);
            }
            case final ServerboundAttackPacket attack -> this.runAttack(attack, tick, refused, settings);
            case final ServerboundPlayerLoadedPacket ignored -> {
                this.view.setLoaded(true);
                this.replay(packet);
            }
            case final ServerboundMovePlayerPacket move -> {
                if (!refused && (this.mode == AuthMode.OBSERVE || this.lastExempt != AuthStats.EXEMPT_NONE)) {
                    this.replay(move);
                }
            }
            default -> {
                this.replayUnless(refused, packet);
                if (category == ClientStream.GUI) {
                    if (refused && packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket) {
                        // Not handled: the client predicted the click, so it gets the container back as it is
                        this.player.containerMenu.sendAllDataToRemote();
                    }
                    // Container clicks carry hashed stacks: the client changed its inventory as the server just did
                    this.view.items.seed(this.player.getInventory());
                }
            }
        }
    }

    // The rotation tick T ran with: its claim's (rotation is sent whenever it changed), or the one the client holds
    private void resolveRotation(final @Nullable ServerboundMovePlayerPacket claim) {
        if (claim != null && claim.hasRotation()) {
            this.stream.setRotation(claim.getYRot(0.0F), claim.getXRot(0.0F));
        }
        this.tickYRot = this.stream.yRot();
        this.tickXRot = this.stream.xRot();
    }

    private void predict(final BlockPos pos, final int sequence, final int tick, final boolean admitted) {
        if (!admitted) {
            this.stats.predictionsRefused++;
        }
        if (!this.world.predict(pos, sequence, tick, this.simulation.x(), this.simulation.y(), this.simulation.z(), admitted)) {
            this.violation("block prediction sequence went backwards");
        }
    }

    private final gg.hcfrevival.revival.auth.combat.PickRay pickRay = new gg.hcfrevival.revival.auth.combat.PickRay();

    // The client's crosshair at its keybinds (Minecraft.pick(1.0F), no entity in the way modelled) is on this block
    private boolean onCrosshair(final BlockPos pos) {
        if (!this.simulation.seeded()) {
            return false;
        }
        final gg.hcfrevival.revival.auth.combat.PickRay ray = this.pickRay;
        ray.clear();
        ray.cast(CombatWiring.snapshot(this), this.world);
        return PredictionRules.onCrosshair(ray.resolveInOrder() == gg.hcfrevival.revival.auth.combat.PickRay.Kind.BLOCK,
            ray.blockPosX(), ray.blockPosY(), ray.blockPosZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    // An attack at its place in the window: the client's own prediction, the input cap, then the lag-compensated check
    private void runAttack(final ServerboundAttackPacket attack, final int tick, final boolean refused, final AuthoritativeSettings settings) {
        this.attacksThisTick++;
        this.mirrorTick(tick); // the ray of tick T sees the boxes after tick T-1's interpolation
        final Entity target = this.player.level().getEntityOrPart(attack.entityId());
        // The sprint state the client attacked with, before its own attack stops it (sprint knock, §15.5)
        this.combatSprinting = this.simulation.sprinting();
        // The client already slowed itself (report 02 §2.4.1); it did, whatever the server decides
        this.simulation.clientAttack(target instanceof Player, this.view);
        this.stats.attacksQueued++;
        final boolean admitted = this.envelope.admitAttack(settings.maxAttacksPerClientTick());
        if (!admitted) {
            this.stats.attacksOverCap++;
        }
        int verdict = AttackJudge.UNKNOWN;
        if (target != null && !refused) {
            // Also while a server teleport is pending: the windows in flight are simulated from the pre-teleport state,
            // which is where the client really is when it attacks
            verdict = Authority.attackJudge().judge(this, target, tick);
        }
        if (verdict == AttackJudge.VALID) {
            this.stats.attacksAccepted++;
        } else if (verdict == AttackJudge.INVALID) {
            this.stats.attacksRejected++;
            if (settings.debugLog()) {
                DebugHud.rejectedHit(this, tick, target.getScoreboardName(), this.lastHitVerdict == null ? "INVALID" : this.lastHitVerdict,
                    this.lastHitRewindMillis, this.lastHitDistance);
            }
        } else if (target == null || refused) {
            this.recordHit(target == null ? "UNKNOWN no-target" : "UNKNOWN refused", -1L, Double.NaN);
        }
        final boolean deliver;
        if (this.mode == AuthMode.ENFORCE) {
            // A player is hit only when the lag-compensated check says so: an attack it could not judge (a server
            // teleport pending, no seed, the mirror out of reach of the rewind) is not delivered. Other targets keep
            // Paper's own validation.
            final boolean judged = target instanceof Player ? verdict == AttackJudge.VALID : verdict != AttackJudge.INVALID;
            deliver = !refused && admitted && judged && this.lastExempt == AuthStats.EXEMPT_NONE;
        } else {
            deliver = !refused;
        }
        if (deliver) {
            this.replay(attack);
        }
    }

    private int speedSyncsPending;
    private boolean speedSyncSprint;
    private int lastExempt = AuthStats.EXEMPT_NONE;
    private int mirroredTick;
    private double eyeOffsetX;
    private double eyeOffsetY;
    private double eyeOffsetZ;

    /**
     * Where the client's eye is relative to the truth's for attack judging (§15.3): its last claim's offset, each axis
     * bounded by the in-flight correction's Δ, while one is in flight; 0 otherwise.
     */
    public double eyeOffset(final int axis) {
        return axis == 0 ? this.eyeOffsetX : axis == 1 ? this.eyeOffsetY : this.eyeOffsetZ;
    }
    private boolean combatSprinting;

    /**
     * The client's sprint state for combat while the simulation owns it (§15.5): during an attack's replay, the state the
     * client attacked with; otherwise the state after its last tick.
     */
    public boolean combatSprinting() {
        return this.combatSprinting;
    }

    // Once per window: the viewer's mirrors take the packets acked in its drain and step to this tick
    private void mirrorTick(final int tick) {
        if (this.mirroredTick != tick) {
            this.mirroredTick = tick;
            Authority.mirrors().onTickEnd(this, tick);
        }
    }

    // The client's tick T, from the entity tick on (report 01 §11.4)
    private void simulate(final @Nullable Input sentInput, final int sprint, final int flying, final boolean fallFlying,
                          final @Nullable ServerboundMovePlayerPacket claim, final boolean refused, final AuthoritativeSettings settings) {
        final Simulation sim = this.simulation;
        final Input keys = this.stream.advanceInput(sentInput);
        final boolean hasPos = claim != null && claim.hasPosition();
        final double cx = hasPos ? claim.getX(0.0) : 0.0;
        final double cy = hasPos ? claim.getY(0.0) : 0.0;
        final double cz = hasPos ? claim.getZ(0.0) : 0.0;

        int exempt = refused ? AuthStats.EXEMPT_REFUSED : this.exemptReason();
        if (exempt == AuthStats.EXEMPT_NONE && (this.reseedFromServer || !sim.seeded())) {
            this.seedFromServer();
        }
        if (exempt == AuthStats.EXEMPT_NONE) {
            // §15.6: a piston, shulker box, solid entity, geyser or rod pull may move the client this tick
            sim.externalMover(sim.seeded() && this.movers.near(this.player, this.runningTick, sim.x(), sim.y(), sim.z(), sim.box(), this.world, settings));
            sim.attachedRockets(this.rocketsOnSelf.size());
            exempt = sim.unsupported(this.view);
        }
        final boolean wasSprinting = sim.sprinting();
        final boolean wasOnGround = sim.onGround();
        if (exempt == AuthStats.EXEMPT_NONE) {
            try {
                sim.tick(keys, this.stream.previousInput(), this.tickYRot, this.tickXRot, sprint, flying, fallFlying, this.view, this.world);
            } catch (final RuntimeException e) {
                // A bug in the port must not take the player down: this tick falls back to the claim, and the next re-seeds
                this.stats.simulationErrors++;
                if (this.stats.simulationErrors == 1) {
                    LOGGER.error("Movement simulation failed for {}; the tick falls back to the client's claim", this.player.getScoreboardName(), e);
                }
                exempt = AuthStats.EXEMPT_SIMULATION_ERROR;
            }
        }
        this.lastExempt = exempt;
        if (exempt == AuthStats.EXEMPT_NONE) {
            this.stats.simulated++;
            this.combatSprinting = sim.sprinting();
            if (this.mode == AuthMode.ENFORCE && wasSprinting != sim.sprinting()) {
                // The sprint knock arms from the client's real sprint, not its sprint packets (§7.2)
                gg.hcfrevival.revival.combat.LegacyKnockback.simulatedSprint(this.player, sim.sprinting(), wasOnGround);
                if (this.speedSyncsPending > 0 && this.speedSyncSprint != sim.sprinting()) {
                    // The sync on its way says the opposite: send the sprint state as it is now behind it
                    this.player.connection.send(new ClientboundUpdateAttributesPacket(this.player.getId(),
                        java.util.List.of(this.player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED))));
                    this.stats.speedResyncs++;
                }
            }
            this.compare(claim, hasPos, cx, cy, cz, settings);
            if (this.mode == AuthMode.ENFORCE) {
                if (this.teleportsPending == 0) {
                    this.reconcile(claim, hasPos, cx, cy, cz, settings);
                    this.applyTruth();
                }
            } else if (hasPos) {
                sim.rebase(cx, cy, cz); // observe: the claim stays the truth
            }
            if (this.teleportsPending == 0) {
                Authority.pathSink().onTick(this, this.runningTick, sim.x(), sim.y(), sim.z(), this.tickYRot, this.tickXRot, sim.onGround());
            }
        } else {
            this.stats.countExempt(exempt);
            switch (exempt) {
                case AuthStats.EXEMPT_NOT_LOADED, AuthStats.EXEMPT_REFUSED -> {
                    // Frozen: no physics, claims ignored
                }
                case AuthStats.EXEMPT_NO_SIMULATION, AuthStats.EXEMPT_WARMUP -> {
                    // No port yet (or the first acks still outstanding): the claim is all there is
                    if (hasPos) {
                        final double vx = this.hasLastClaim ? cx - this.lastClaimX : 0.0;
                        final double vy = this.hasLastClaim ? cy - this.lastClaimY : 0.0;
                        final double vz = this.hasLastClaim ? cz - this.lastClaimZ : 0.0;
                        sim.seed(cx, cy, cz, vx, vy, vz, this.tickYRot, this.tickXRot, claim.isOnGround(), this.view, this.world);
                    }
                    if (exempt == AuthStats.EXEMPT_WARMUP) {
                        this.reseedFromServer = true;
                    }
                }
                default -> {
                    // Riding, dead, spectating, unsupported states, port errors: vanilla handles the claim; on leaving,
                    // re-seed from what the server owns (§15.3)
                    this.reseedFromServer = true;
                }
            }
            if (exempt != AuthStats.EXEMPT_NOT_LOADED && exempt != AuthStats.EXEMPT_REFUSED && hasPos && this.teleportsPending == 0) {
                Authority.pathSink().onTick(this, this.runningTick, cx, cy, cz, this.tickYRot, this.tickXRot, claim.isOnGround());
            }
        }
        if (hasPos) {
            this.prevClaimX = this.lastClaimX;
            this.prevClaimY = this.lastClaimY;
            this.prevClaimZ = this.lastClaimZ;
            this.prevClaimTick = this.lastClaimTick;
            this.hasPrevClaim = this.hasLastClaim;
            this.lastClaimX = cx;
            this.lastClaimY = cy;
            this.lastClaimZ = cz;
            this.lastClaimTick = this.runningTick;
            this.hasLastClaim = true;
        }
        // §15.3: while a correction is in flight, the next attack is judged from where the client is, within the Δ
        if (this.reconciler.inFlight() && hasPos && exempt == AuthStats.EXEMPT_NONE) {
            this.eyeOffsetX = Reconciler.bounded(cx - sim.x(), this.reconciler.inFlightDelta(0));
            this.eyeOffsetY = Reconciler.bounded(cy - sim.y(), this.reconciler.inFlightDelta(1));
            this.eyeOffsetZ = Reconciler.bounded(cz - sim.z(), this.reconciler.inFlightDelta(2));
        } else {
            this.eyeOffsetX = 0.0;
            this.eyeOffsetY = 0.0;
            this.eyeOffsetZ = 0.0;
        }
        this.stream.advancePosition(hasPos, cx, cy, cz);
        final TraceRecorder recorder = this.trace;
        if (recorder != null) {
            recorder.sim(sim.x(), sim.y(), sim.z(), sim.vx(), sim.vy(), sim.vz(), (sim.onGround() ? 1 : 0) | (sim.horizontalCollision() ? 2 : 0) | (exempt != 0 ? 4 : 0));
        }
    }

    /**
     * Seeds from server-owned state (§15.3): the position the server holds. The velocity: zero for a new local player
     * (login, respawn); the client's own after its last claimed move when the last two claims were consecutive ticks
     * ending where the server is (the port works it out, auth-sim-requests §1); else the server's known movement.
     */
    private void seedFromServer() {
        this.rebaseGuard.reset();
        final ServerPlayer p = this.player;
        final Simulation sim = this.simulation;
        if (this.seedStill) {
            sim.seed(p.getX(), p.getY(), p.getZ(), 0.0, 0.0, 0.0, this.tickYRot, this.tickXRot, p.onGround(), this.view, this.world);
        } else if (this.hasPrevClaim && this.lastClaimTick == this.prevClaimTick + 1
            && p.getX() == this.lastClaimX && p.getY() == this.lastClaimY && p.getZ() == this.lastClaimZ) {
            if (!sim.seedFromMove(this.prevClaimX, this.prevClaimY, this.prevClaimZ, this.lastClaimX, this.lastClaimY, this.lastClaimZ,
                this.tickYRot, this.tickXRot, p.onGround(), this.view, this.world)) {
                this.stats.inexactSeeds++;
            }
        } else {
            final Vec3 known = p.getKnownMovement();
            sim.seed(p.getX(), p.getY(), p.getZ(), known.x, known.y, known.z, this.tickYRot, this.tickXRot, p.onGround(), this.view, this.world);
            this.stats.inexactSeeds++;
        }
        this.seedStill = false;
        this.reseedFromServer = false;
    }

    /** Why the tick can't be simulated, from what the client has applied (§15.3), or 0. */
    int exemptReason() {
        if (!this.view.loaded()) {
            return AuthStats.EXEMPT_NOT_LOADED;
        }
        if (this.view.passenger()) {
            return AuthStats.EXEMPT_RIDING;
        }
        if (this.view.dead()) {
            return AuthStats.EXEMPT_DEAD;
        }
        if (this.view.cameraOther()) {
            return AuthStats.EXEMPT_SPECTATOR_CAMERA;
        }
        if (this.view.spectator()) {
            return AuthStats.EXEMPT_SPECTATOR;
        }
        if (this.view.pose() == Pose.SLEEPING) {
            return AuthStats.EXEMPT_SLEEPING;
        }
        if (!this.warm) {
            return AuthStats.EXEMPT_WARMUP;
        }
        return AuthStats.EXEMPT_NONE;
    }

    // The claim against the truth, and the client's own position rule when it sent none (report 02 §2.6)
    private void compare(final @Nullable ServerboundMovePlayerPacket claim, final boolean hasPos, final double cx, final double cy, final double cz,
                         final AuthoritativeSettings settings) {
        final Simulation sim = this.simulation;
        if (this.reconciler.inFlight()) {
            return; // the client is off by the correction until it applies it
        }
        if (hasPos) {
            final double dx = cx - sim.x();
            final double dy = cy - sim.y();
            final double dz = cz - sim.z();
            final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            this.stats.divergence(distance, settings.rebaseEpsilon(), settings.correctionThreshold());
            this.recent.compared(distance <= settings.rebaseEpsilon(), System.currentTimeMillis());
            if (distance > settings.rebaseEpsilon() && settings.debugLog()) {
                DebugHud.diverged(this, this.runningTick, cx, cy, cz, sim, claim.isOnGround());
            }
            if (claim.isOnGround() != sim.onGround() || claim.horizontalCollision() != sim.horizontalCollision()) {
                this.stats.flagMismatches++;
            }
        } else if (this.stream.lastSentKnown()) {
            if (this.stream.reminderDue()) {
                this.stats.missedReminders++;
            } else {
                final double dx = sim.x() - this.stream.lastSentX();
                final double dy = sim.y() - this.stream.lastSentY();
                final double dz = sim.z() - this.stream.lastSentZ();
                if (dx * dx + dy * dy + dz * dz > 4.0E-8) {
                    this.stats.missingPositionViolations++;
                }
            }
        }
    }

    // Enforce: the reconciler decides; a correction is relative in position, absolute in velocity (v1, see the doc)
    private void reconcile(final @Nullable ServerboundMovePlayerPacket claim, final boolean hasPos, final double cx, final double cy, final double cz,
                           final AuthoritativeSettings settings) {
        if (!hasPos) {
            return;
        }
        final Simulation sim = this.simulation;
        final double dx = sim.x() - cx;
        final double dy = sim.y() - cy;
        final double dz = sim.z() - cz;
        final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int decision = this.reconciler.judge(distance, settings.rebaseEpsilon(), settings.correctionThreshold(), settings.correctionBudget(),
            settings.correctionBudgetDecay(), settings.maxCorrectionsPerSecond(), System.nanoTime());
        if (decision == Reconciler.EXACT && distance > 0.0) {
            // rebase-epsilon on: a claim that moves the box into or against a shape, or creeps, stays a claim (§15.5)
            if (sim.clearPath(-dx, -dy, -dz, this.world)
                && this.rebaseGuard.admit(this.runningTick, -dx, -dy, -dz, settings.rebaseDriftCap(), settings.rebaseDriftWindowTicks())) {
                sim.rebase(cx, cy, cz);
            } else {
                this.stats.rebasesRefused++;
                decision = Reconciler.WITHIN;
            }
        }
        // A claim off by less than the threshold for correction-persist-ticks in a row is corrected anyway: every client converges
        if (decision == Reconciler.WITHIN && distance > 0.0) {
            if (++this.withinStreak >= settings.correctionPersistTicks()) {
                decision = Reconciler.CORRECT;
            }
        }
        if (decision != Reconciler.WITHIN) {
            this.withinStreak = 0;
        }
        final boolean exact = decision == Reconciler.EXACT;
        if (decision != Reconciler.IN_FLIGHT) {
            sim.belief(exact, cx, cy, cz);
        }
        if (decision == Reconciler.CORRECT) {
            // Decided at the start of the next window, once it shows whether a hotbar change explains the claim (§15.4)
            this.deferredCorrection = true;
            this.deferredX = cx;
            this.deferredY = cy;
            this.deferredZ = cz;
        }
    }

    // The deferred correction of the previous tick: the hotbar branch, then the attack-strength branch, else the correction
    private void resolveCorrection(final ClientStream.Window window) {
        this.deferredCorrection = false;
        if (this.mode != AuthMode.ENFORCE || this.teleportsPending > 0 || this.reconciler.inFlight()) {
            return;
        }
        final Simulation sim = this.simulation;
        final int slot = firstCarriedSlot(window);
        if (slot >= 0 && sim.retryWithSlot(slot, this.view, this.world, this.deferredX, this.deferredY, this.deferredZ)) {
            this.stats.hotbarBranches++;
            sim.belief(true, this.deferredX, this.deferredY, this.deferredZ);
            this.applyTruth();
            return;
        }
        if (sim.retryAttackStrength(this.view, this.world, this.deferredX, this.deferredY, this.deferredZ)) {
            this.stats.attackStrengthBranches++;
            sim.belief(true, this.deferredX, this.deferredY, this.deferredZ);
            this.applyTruth();
            return;
        }
        if (sim.correction(this.correctionAhead(), this.stream.input(), this.tickYRot, this.tickXRot, this.view, this.world, this.delta)) {
            this.sendForecast();
        } else {
            this.sendFallback(sim.x() - this.deferredX, sim.y() - this.deferredY, sim.z() - this.deferredZ);
        }
    }

    /**
     * Client ticks between the truth's last tick and the drain that applies a correction sent now (§15.5): the window
     * being processed already ended its tick, so at least one, else {@code n̂ = rtt / 50}.
     */
    private int correctionAhead() {
        return Math.max(1, (int) Math.round(this.transactions.rttBaseNanos() / 50_000_000.0));
    }

    // The forecast in delta: relative, or absolute when the client would land inside a block or the same correction
    // keeps coming back (a client stuck off the truth, where relative corrections chase it one tick behind forever)
    private void sendForecast() {
        final double[] d = this.delta;
        if (d[6] != 0.0 || this.repeating(d[0], d[1], d[2])) {
            this.sendAbsolute(d[7], d[8], d[9], d[10], d[11], d[12]);
        } else {
            this.sendCorrection(d[0], d[1], d[2], d[3], d[4], d[5], true);
        }
    }

    // No belief to forecast with: relative position and absolute velocity, absolute when stuck
    private void sendFallback(final double dx, final double dy, final double dz) {
        final Simulation sim = this.simulation;
        if (this.repeating(dx, dy, dz)) {
            this.sendAbsolute(sim.x(), sim.y(), sim.z(), sim.vx(), sim.vy(), sim.vz());
        } else {
            this.sendCorrection(dx, dy, dz, sim.vx(), sim.vy(), sim.vz(), false);
        }
    }

    // Whether this correction is the one sent last, correction-repeat-limit times in a row
    private boolean repeating(final double dx, final double dy, final double dz) {
        final boolean same = Math.abs(dx - this.lastSentDx) < 1.0E-9 && Math.abs(dy - this.lastSentDy) < 1.0E-9 && Math.abs(dz - this.lastSentDz) < 1.0E-9;
        if (same && this.correctionRepeats + 1 >= this.settings().correctionRepeatLimit()) {
            this.correctionRepeats = 0;
            return true;
        }
        return false;
    }

    // A SetCarriedItem that opens the window's tick group (gameMode.tick's ensureHasSentCarriedItem), or -1
    private static int firstCarriedSlot(final ClientStream.Window window) {
        for (int i = 0; i < window.items(); i++) {
            if (window.kind(i) != ClientStream.PACKET || window.a(i) == ClientStream.GUI) {
                continue;
            }
            return window.ref(i) instanceof final ServerboundSetCarriedItemPacket carried ? carried.getSlot() : -1;
        }
        return -1;
    }

    private final double[] delta = new double[13];
    // The last relative correction's Δ and how many times in a row it went out; the last velocity sent
    private double lastSentDx = Double.NaN;
    private double lastSentDy = Double.NaN;
    private double lastSentDz = Double.NaN;
    private int correctionRepeats;
    private double lastSentVx;
    private double lastSentVy;
    private double lastSentVz;
    private int withinStreak;
    // Accepts in a row whose residual needed another correction (reset by an accept that lands)
    private int backstopStreak;
    private final RebaseGuard rebaseGuard = new RebaseGuard();
    private final ExternalMovers movers = new ExternalMovers();

    /**
     * One correction (design §6.2, report 06 §7.3): position always relative (the client keeps its frame interpolation),
     * camera untouched (relative zero rotation); velocity relative when it is a Δ against the belief, else absolute.
     */
    private void sendCorrection(final double dx, final double dy, final double dz, final double vx, final double vy, final double vz, final boolean relativeVelocity) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz) || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)) {
            this.violation("a correction off a non-finite state"); // never sent: the client would disconnect on it
            return;
        }
        final boolean same = Math.abs(dx - this.lastSentDx) < 1.0E-9 && Math.abs(dy - this.lastSentDy) < 1.0E-9 && Math.abs(dz - this.lastSentDz) < 1.0E-9;
        this.correctionRepeats = same ? this.correctionRepeats + 1 : 0;
        this.lastSentDx = dx;
        this.lastSentDy = dy;
        this.lastSentDz = dz;
        this.lastSentVx = vx;
        this.lastSentVy = vy;
        this.lastSentVz = vz;
        final int id = this.reconciler.send(dx, dy, dz);
        this.stats.corrections++;
        this.recent.correction(System.currentTimeMillis());
        this.lastCorrectionSize = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (this.settings().debugLog()) {
            DebugHud.correction(this, this.runningTick, dx, dy, dz, vx, vy, vz, relativeVelocity);
        }
        final TraceRecorder recorder = this.trace;
        if (recorder != null) {
            recorder.correction(relativeVelocity ? 2 : 1, dx, dy, dz);
        }
        this.player.connection.send(new ClientboundPlayerPositionPacket(id,
            new PositionMoveRotation(new Vec3(dx, dy, dz), new Vec3(vx, vy, vz), 0.0F, 0.0F), relativeVelocity ? CORRECTION_RELATIVE : CORRECTION));
    }

    /** An absolute correction: the client is put on {@code (x, y, z)} moving at {@code (vx, vy, vz)}, its camera untouched. */
    private void sendAbsolute(final double x, final double y, final double z, final double vx, final double vy, final double vz) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)) {
            this.violation("a correction off a non-finite state");
            return;
        }
        final Simulation sim = this.simulation;
        this.lastSentDx = Double.NaN;
        this.lastSentDy = Double.NaN;
        this.lastSentDz = Double.NaN;
        this.correctionRepeats = 0;
        this.lastSentVx = vx;
        this.lastSentVy = vy;
        this.lastSentVz = vz;
        final int id = this.reconciler.send(x - sim.x(), y - sim.y(), z - sim.z());
        this.stats.corrections++;
        this.stats.absoluteCorrections++;
        this.recent.correction(System.currentTimeMillis());
        this.lastCorrectionSize = Math.sqrt((x - sim.x()) * (x - sim.x()) + (y - sim.y()) * (y - sim.y()) + (z - sim.z()) * (z - sim.z()));
        if (this.settings().debugLog()) {
            DebugHud.absoluteCorrection(this, this.runningTick, x, y, z, vx, vy, vz);
        }
        final TraceRecorder recorder = this.trace;
        if (recorder != null) {
            recorder.correction(3, x, y, z);
        }
        this.player.connection.send(new ClientboundPlayerPositionPacket(id,
            new PositionMoveRotation(new Vec3(x, y, z), new Vec3(vx, vy, vz), 0.0F, 0.0F), CORRECTION_ABSOLUTE));
    }

    // Enforce: the truth goes through the vanilla accepted-move path, so its side effects and events stay (§6.3)
    private void applyTruth() {
        final Simulation sim = this.simulation;
        this.writingTruth = true;
        try {
            this.player.connection.revivalApplyTruth(sim.x(), sim.y(), sim.z(), this.tickYRot, this.tickXRot, sim.onGround(), sim.horizontalCollision());
            this.writeClientState();
            // §15.5: the server's motion is the client's (what getVelocity, knockback and the move checks read), except
            // while a server velocity waits for the tracker to send it
            if (!this.player.syncVelocity) {
                this.player.setDeltaMovement(sim.vx(), sim.vy(), sim.vz());
            }
        } finally {
            this.writingTruth = false;
        }
    }

    /**
     * §15.4: the client's pose, swimming, gliding and item use come from the simulation; the server's own recomputation
     * of them is skipped. Starting a glide or an item use stays with the replayed handlers (plugins can refuse them), so
     * the writer only ends what the client ended. Sprint stays with the replayed commands, so a cancelled
     * {@code PlayerToggleSprintEvent} keeps its server-side meaning (§15.5). Fields the server changed on purpose are
     * left alone until the client has acked them.
     */
    private void writeClientState() {
        final Simulation sim = this.simulation;
        final ServerPlayer p = this.player;
        if ((this.meant & SelfEcho.POSE) == 0 && p.getPose() != sim.pose()) {
            p.setPose(sim.pose());
        }
        if ((this.meant & SelfEcho.SWIM) == 0 && p.isSwimming() != sim.swimming()) {
            p.setSwimming(sim.swimming());
        }
        if ((this.meant & SelfEcho.GLIDE) == 0 && p.isFallFlying() && !sim.fallFlying()) {
            p.stopFallFlying();
        }
        if ((this.meant & SelfEcho.USE) == 0 && p.isUsingItem() && !sim.usingItem()) {
            p.stopUsingItem();
        }
    }

    // A teleport accept, in the drain: checked as a claim, never a seed (§15.3)
    private void checkAccept(final ServerboundAcceptTeleportationPacket accept, final AuthoritativeSettings settings) {
        if (!Double.isFinite(accept.x()) || !Double.isFinite(accept.y()) || !Double.isFinite(accept.z())) {
            this.violation("non-finite teleport accept");
            return;
        }
        this.stream.setRotation(accept.yRot(), accept.xRot());
        if (Reconciler.ours(accept.id())) {
            if (this.reconciler.accepted(accept.id())) {
                this.stats.correctionsAccepted++;
                final Simulation sim = this.simulation;
                final double dx = sim.x() - accept.x();
                final double dy = sim.y() - accept.y();
                final double dz = sim.z() - accept.z();
                final double residual = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (residual > settings.correctionThreshold() && this.mode == AuthMode.ENFORCE && this.teleportsPending == 0) {
                    // The backstop: the client is where it says now; forecast both to the tick it applies the next one,
                    // so a client stuck off the truth (inside a block, falling) is not chased one tick behind forever
                    if (sim.beliefValid()) {
                        sim.belief(false, accept.x(), accept.y(), accept.z()); // its velocity already took the correction
                    } else {
                        sim.rebaseBelief(accept.x(), accept.y(), accept.z(), this.lastSentVx, this.lastSentVy, this.lastSentVz);
                    }
                    final boolean forecast = sim.correction(this.correctionAhead(), this.stream.input(), this.tickYRot, this.tickXRot, this.view,
                        this.world, this.delta);
                    if (++this.backstopStreak >= settings.correctionRepeatLimit()) {
                        // Corrections keep missing: the client's own state isn't what the belief says (its velocity, its
                        // ground), so land it absolutely, position and velocity, where the truth will be
                        this.backstopStreak = 0;
                        if (forecast) {
                            this.sendAbsolute(this.delta[7], this.delta[8], this.delta[9], this.delta[10], this.delta[11], this.delta[12]);
                        } else {
                            this.sendAbsolute(sim.x(), sim.y(), sim.z(), sim.vx(), sim.vy(), sim.vz());
                        }
                    } else if (forecast) {
                        this.sendForecast();
                    } else {
                        this.sendFallback(dx, dy, dz);
                    }
                } else {
                    this.backstopStreak = 0;
                    sim.belief(residual <= settings.rebaseEpsilon(), accept.x(), accept.y(), accept.z());
                }
            } else if (this.reconciler.sentLately(accept.id())) {
                this.stats.staleAccepts++; // a correction a server teleport or reset dropped while it was on its way
            } else {
                // Our correction ids are ours alone: an unknown one is forged, like an unknown pong (§15.1)
                this.protocolViolation("accept for a correction never sent");
            }
            return;
        }
        if (this.mode == AuthMode.ENFORCE) {
            final int index = this.awaitingAccepts.indexOf(accept.id());
            if (index < 0) {
                if (this.acceptedBeforeAuthority.rem(accept.id())) {
                    return; // sent before authority started: vanilla's bookkeeping ran at its arrival
                }
                if (this.teleportsPending > 0) {
                    // A teleport sent ahead of its ping (a full bundle) is accepted before its pong: matched when it lands
                    this.earlyAccepts.add(accept.id());
                } else {
                    this.violation("accept for a server teleport never sent or already accepted");
                }
                return;
            }
            // Accepted: this one and any earlier ones (the client acks only the newest it applied)
            this.awaitingAccepts.removeElements(0, index + 1);
            this.awaitingSince.removeElements(0, index + 1);
        }
        final Simulation sim = this.simulation;
        if (sim.seeded() && (accept.x() != sim.x() || accept.y() != sim.y() || accept.z() != sim.z())) {
            this.stats.teleportAcceptMismatches++;
        }
    }

    // Applying acked entries (the client's drain) ----------------------------------------------------------------

    private void apply(final Packet<?> packet, final int kind, final int tick, final boolean exact, final long sentMillis) {
        final Simulation sim = this.simulation;
        switch (kind) {
            case OutboundClassifier.SELF_MOTION -> {
                final Vec3 movement = ((ClientboundSetEntityMotionPacket) packet).movement();
                Wire.lpVec3(movement.x, movement.y, movement.z, this.decoded);
                sim.setVelocity(this.decoded[0], this.decoded[1], this.decoded[2]);
            }
            case OutboundClassifier.SELF_POSITION -> {
                final ClientboundPlayerPositionPacket position = (ClientboundPlayerPositionPacket) packet;
                this.world.onTeleport();
                if (Reconciler.ours(position.id())) {
                    this.reconciler.applied(position.id()); // changes the client, not the truth
                    sim.beliefCorrected(position.change(), position.relatives()); // the client's own state takes it, as the client does
                } else {
                    if (!this.view.passenger()) {
                        sim.position(position.change(), position.relatives());
                        this.stream.setRotation(sim.yRot(), sim.xRot());
                        this.reseedFromServer = !sim.seeded(); // the teleport is the server-owned state to start from
                    }
                    if (this.mode == AuthMode.ENFORCE) {
                        // Complete at its pong (§15.3): the server player writes the truth again; the accept is only a claim
                        this.player.connection.revivalCompleteServerTeleport(position.id());
                        if (!this.earlyAccepts.rem(position.id())) {
                            this.awaitingAccepts.add(position.id());
                            this.awaitingSince.add(tick);
                        }
                    }
                    this.teleportsPending = Math.max(0, this.teleportsPending - 1);
                    if (this.teleportsPending == 0) {
                        this.earlyAccepts.clear();
                    }
                    this.reconciler.reset();
                }
            }
            case OutboundClassifier.SELF_ROTATION -> {
                final ClientboundPlayerRotationPacket rotation = (ClientboundPlayerRotationPacket) packet;
                sim.rotation(rotation.yRot(), rotation.relativeY(), rotation.xRot(), rotation.relativeX());
                this.stream.setRotation(sim.yRot(), sim.xRot());
            }
            case OutboundClassifier.EXPLODE -> {
                final Vec3 knockback = ((ClientboundExplodePacket) packet).playerKnockback().orElse(Vec3.ZERO);
                if (Double.isFinite(knockback.x) && Double.isFinite(knockback.y) && Double.isFinite(knockback.z)) {
                    sim.addVelocity(knockback.x, knockback.y, knockback.z);
                }
            }
            case OutboundClassifier.ABILITIES -> this.view.abilities((ClientboundPlayerAbilitiesPacket) packet);
            case OutboundClassifier.SELF_ATTRIBUTES -> {
                final java.util.List<ClientboundUpdateAttributesPacket.AttributeSnapshot> values = ((ClientboundUpdateAttributesPacket) packet).getValues();
                this.view.attributes.apply(values);
                sim.attributes(values);
                if (SelfEcho.speedSync((ClientboundUpdateAttributesPacket) packet) >= 0 && this.speedSyncsPending > 0) {
                    this.speedSyncsPending--;
                }
            }
            case OutboundClassifier.SELF_EFFECT_ADD -> this.view.addEffect((ClientboundUpdateMobEffectPacket) packet);
            case OutboundClassifier.SELF_EFFECT_REMOVE -> this.view.removeEffect(((ClientboundRemoveMobEffectPacket) packet).effect());
            case OutboundClassifier.SELF_ENTITY_DATA -> {
                final ClientboundSetEntityDataPacket data = (ClientboundSetEntityDataPacket) packet;
                this.view.entityData(data.packedItems(), this);
                this.meantAcked(data.packedItems(), sentMillis);
            }
            case OutboundClassifier.BLOCK_UPDATE -> {
                final ClientboundBlockUpdatePacket update = (ClientboundBlockUpdatePacket) packet;
                this.world.onBlockUpdate(update.getPos(), update.getBlockState());
            }
            case OutboundClassifier.SECTION_UPDATE -> ((ClientboundSectionBlocksUpdatePacket) packet).runUpdates(this.world::onBlockUpdate);
            case OutboundClassifier.BLOCK_ACK -> this.world.onPredictionAck(((ClientboundBlockChangedAckPacket) packet).sequence(), this);
            case OutboundClassifier.CHUNK -> {
                final ClientboundLevelChunkWithLightPacket chunk = (ClientboundLevelChunkWithLightPacket) packet;
                this.world.onChunk(chunk.x(), chunk.z());
            }
            case OutboundClassifier.FORGET_CHUNK -> {
                final ClientboundForgetLevelChunkPacket forget = (ClientboundForgetLevelChunkPacket) packet;
                this.world.onForgetChunk(forget.pos().x(), forget.pos().z());
            }
            case OutboundClassifier.GAME_MODE -> this.localGameMode(GameType.byId((int) ((ClientboundGameEventPacket) packet).getParam()));
            case OutboundClassifier.SELF_INFO -> {
                for (final ClientboundPlayerInfoUpdatePacket.Entry entry : ((ClientboundPlayerInfoUpdatePacket) packet).entries()) {
                    if (this.player.getUUID().equals(entry.profileId())) {
                        sim.tabListGameMode(entry.gameMode()); // first: it compares with the mode the view still holds
                        this.view.tabGameMode(entry.gameMode());
                    }
                }
            }
            case OutboundClassifier.RESPAWN -> {
                final ClientboundRespawnPacket respawn = (ClientboundRespawnPacket) packet;
                this.respawn(respawn.dataToKeep());
                this.localGameMode(respawn.commonPlayerSpawnInfo().gameType()); // handleRespawn → setLocalMode
            }
            case OutboundClassifier.LOGIN -> {
                this.respawn((byte) 0);
                this.localGameMode(((net.minecraft.network.protocol.game.ClientboundLoginPacket) packet).commonPlayerSpawnInfo().gameType());
            }
            case OutboundClassifier.ROCKET -> this.applyRocket(packet);
            case OutboundClassifier.SELF_PASSENGERS -> {
                final ClientboundSetPassengersPacket passengers = (ClientboundSetPassengersPacket) packet;
                this.view.passengers(passengers.getVehicle(), passengers.getPassengers(), this.player.getId());
            }
            case OutboundClassifier.INVENTORY -> this.applyInventory(packet);
            case OutboundClassifier.HELD_SLOT -> this.view.items.select(((ClientboundSetHeldSlotPacket) packet).slot());
            case OutboundClassifier.HEALTH -> this.view.health((ClientboundSetHealthPacket) packet);
            case OutboundClassifier.TICKING -> {
                if (packet instanceof final ClientboundTickingStatePacket state) {
                    this.view.tickingState(state.tickRate(), state.isFrozen());
                    Authority.mirrors().onTickRate(this, state.tickRate(), state.isFrozen());
                }
            }
            case OutboundClassifier.SELF_ENTITY_EVENT -> {
                if (((ClientboundEntityEventPacket) packet).getEventId() == net.minecraft.world.entity.EntityEvent.USE_ITEM_COMPLETE) {
                    sim.completeUsingItem();
                }
            }
            case OutboundClassifier.COOLDOWN -> {
                final ClientboundCooldownPacket cooldown = (ClientboundCooldownPacket) packet;
                this.view.cooldown(cooldown.cooldownGroup(), cooldown.duration());
            }
            case OutboundClassifier.TEAM -> this.view.team((ClientboundSetPlayerTeamPacket) packet);
            case OutboundClassifier.CAMERA -> this.view.camera(((ClientboundSetCameraPacket) packet).getEntity(this.player.level()) != this.player);
            case OutboundClassifier.ROD_PULL -> {
                if (((ClientboundEntityEventPacket) packet).getEntity(this.player.level()) instanceof final FishingHook hook && hook.getHookedIn() == this.player) {
                    this.stats.externalMovers++; // the client pulls itself toward the rod owner this tick: not modelled, exempt (§15.6)
                    this.movers.rodPull(tick);
                }
            }
            case OutboundClassifier.PISTON -> {
                this.stats.externalMovers++;
                this.movers.blockEvent(tick, ((net.minecraft.network.protocol.game.ClientboundBlockEventPacket) packet).getPos());
            }
            case OutboundClassifier.REMOTE_PLAYER -> {
                Authority.mirrors().onApplied(this, packet, tick, exact, sentMillis);
                if (packet instanceof final net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket remove) {
                    this.removeRockets(remove);
                }
            }
            default -> {
            }
        }
    }

    private void applyInventory(final Packet<?> packet) {
        // §15.6: the client's stacks as encoded when the entry was logged; converted now only if no snapshot was taken
        final Object taken = this.transactions.takeSnapshot(packet);
        final net.minecraft.world.item.ItemStack[] snapshot = taken instanceof final net.minecraft.world.item.ItemStack[] stacks ? stacks : ClientItems.snapshot(packet);
        if (snapshot == null) {
            return;
        }
        switch (packet) {
            case final ClientboundSetPlayerInventoryPacket slot -> this.view.items.setInventoryClient(slot.slot(), snapshot[0]);
            case final ClientboundContainerSetSlotPacket slot -> {
                if (slot.getContainerId() == 0) {
                    this.view.items.setMenuSlotClient(slot.getSlot(), snapshot[0]);
                } else {
                    this.view.items.seed(this.player.getInventory());
                }
            }
            case final ClientboundContainerSetContentPacket content -> {
                if (content.containerId() == 0) {
                    this.view.items.setMenuContentClient(snapshot);
                } else {
                    this.view.items.seed(this.player.getInventory());
                }
            }
            case final ClientboundSetEquipmentPacket equipment -> {
                for (int i = 0; i < snapshot.length; i++) {
                    this.view.items.setEquipmentClient(equipment.getSlots().get(i).getFirst(), snapshot[i]);
                }
            }
            default -> {
            }
        }
    }

    @Override
    public @Nullable Object snapshot(final Packet<?> packet) {
        return ClientItems.snapshot(packet);
    }

    // A new local player (respawn, dimension change, login): KEEP flags per report 02 §3.14
    // handleLogin / handleRespawn / GameEvent CHANGE_GAME_MODE → MultiPlayerGameMode.setLocalMode
    private void localGameMode(final GameType mode) {
        this.view.gameMode(mode);
        this.simulation.localGameMode(mode);
    }

    // Firework rockets the client holds, in the order it added them, and which of them are attached to it (auth-sim §3.2)
    private final it.unimi.dsi.fastutil.ints.IntArrayList rockets = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet rocketsOnSelf = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    private void applyRocket(final Packet<?> packet) {
        switch (packet) {
            case final net.minecraft.network.protocol.game.ClientboundAddEntityPacket add -> this.rockets.add(add.getId());
            case final ClientboundSetEntityDataPacket data -> {
                for (final net.minecraft.network.syncher.SynchedEntityData.DataValue<?> value : data.packedItems()) {
                    // The rocket resolves its attachment in its own tick and keeps it (FireworkRocketEntity.tick)
                    if (value.id() == net.minecraft.world.entity.projectile.FireworkRocketEntity.DATA_ATTACHED_TO_TARGET.id()
                        && value.value() instanceof final java.util.OptionalInt target && target.isPresent() && target.getAsInt() == this.player.getId()) {
                        this.rocketsOnSelf.add(data.id());
                    }
                }
            }
            case final net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket remove -> this.removeRockets(remove);
            default -> {
            }
        }
    }

    private void removeRockets(final net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket remove) {
        if (this.rockets.isEmpty()) {
            return;
        }
        for (int i = 0, n = remove.entityIds().size(); i < n; i++) {
            final int id = remove.entityIds().getInt(i);
            this.rockets.rem(id);
            this.rocketsOnSelf.remove(id);
        }
    }

    private void respawn(final byte keep) {
        this.visibility.clear();
        this.seedStill = true;
        this.hasLastClaim = false;
        this.hasPrevClaim = false;
        this.rockets.clear();
        this.rocketsOnSelf.clear();
        this.movers.reset();
        final boolean keepAttributes = (keep & ClientboundRespawnPacket.KEEP_ATTRIBUTE_MODIFIERS) != 0;
        final boolean keepData = (keep & ClientboundRespawnPacket.KEEP_ENTITY_DATA) != 0;
        this.view.respawn(keepAttributes, keepData);
        this.simulation.respawn(keepData, keepAttributes);
        this.stream.resetClient(keepData);
        if (!keepData) {
            this.stream.setRotation(-180.0F, 0.0F);
        }
        this.world.setLevel(this.player.level(), true);
        this.reconciler.reset();
        this.reseedFromServer = true;
        Authority.mirrors().onReset(this);
    }

    private void resync() {
        this.view.seed(this.player);
        this.simulation.attributes(this.player.getAttributes().getSyncableAttributesPacket());
        this.world.setLevel(this.player.level(), false);
        this.world.clearUnsent();
        this.reseedFromServer = true;
        this.reconciler.reset();
        Authority.mirrors().onReset(this);
    }

    // ClientView.EchoSink --------------------------------------------------------------------------------------------

    @Override
    public void sharedFlags(final byte flags) {
        this.simulation.echoSharedFlags(flags);
    }

    @Override
    public void pose(final Pose pose) {
        this.simulation.echoPose(pose);
    }

    @Override
    public void usingItem(final boolean using, final InteractionHand hand) {
        this.simulation.echoUsingItem(using, hand);
    }

    // WorldView.RevertListener: after a revert, the truth snaps back if it now intersects the block (§15.2)

    @Override
    public void onRevert(final BlockPos pos, final BlockState state, final double playerX, final double playerY, final double playerZ) {
        this.stats.reverts++;
        final Simulation sim = this.simulation;
        if (!sim.seeded()) {
            return;
        }
        final VoxelShape shape = state.getCollisionShape(this.player.level(), pos, CollisionContext.empty());
        if (shape.isEmpty()) {
            return;
        }
        final double half = this.player.getBbWidth() / 2.0;
        final AABB box = new AABB(sim.x() - half, sim.y(), sim.z() - half, sim.x() + half, sim.y() + this.player.getBbHeight(), sim.z() + half);
        if (shape.move(pos).toAabbs().stream().anyMatch(box::intersects)) {
            sim.snapTo(playerX, playerY, playerZ);
        }
    }

    // Replay ---------------------------------------------------------------------------------------------------------

    private void replayUnless(final boolean refused, final Packet<?> packet) {
        if (!refused) {
            this.replay(packet);
        }
    }

    /** Runs a buffered packet through its original handler, as the packet processor would. */
    @SuppressWarnings("unchecked")
    private void replay(final Packet<?> packet) {
        final net.minecraft.server.network.ServerGamePacketListenerImpl listener = this.player.connection;
        if (listener == null || !listener.shouldHandleMessage(packet)) {
            return;
        }
        final boolean outer = this.replaying;
        this.replaying = true;
        try {
            ((Packet<ServerGamePacketListener>) packet).handle(listener);
        } catch (final Exception e) {
            listener.onPacketError(packet, e);
        } finally {
            this.replaying = outer;
        }
    }

    @SuppressWarnings("unchecked")
    private void traceInbound(final Packet<?> packet) {
        final TraceRecorder recorder = this.trace;
        if (recorder == null) {
            return;
        }
        switch (packet) {
            case final ServerboundPlayerInputPacket input -> recorder.input(input.input());
            case final ServerboundMovePlayerPacket move -> recorder.move(move.hasPosition(), move.hasRotation(), move.getX(0.0), move.getY(0.0), move.getZ(0.0),
                move.getYRot(0.0F), move.getXRot(0.0F), move.isOnGround(), move.horizontalCollision());
            case final ServerboundPlayerCommandPacket command -> recorder.command(command.getAction().ordinal(), command.getData());
            default -> recorder.action((Packet<? super ServerGamePacketListener>) packet);
        }
    }

    // Pong order is the clock everything else stands on: in enforce a client that breaks it is disconnected (§15.1)
    private void protocolViolation(final String what) {
        this.violation(what);
        if (this.mode == AuthMode.ENFORCE && !this.kicked) {
            this.kicked = true;
            this.player.connection.disconnect(Component.literal("Invalid transaction replies"));
        }
    }

    private void violation(final String what) {
        this.stats.violations++;
        if (this.mode == AuthMode.ENFORCE) {
            LOGGER.warn("{} broke the client protocol under authority: {}", this.player.getScoreboardName(), what);
        } else {
            LOGGER.debug("{} broke the client protocol (observe): {}", this.player.getScoreboardName(), what);
        }
    }

    // Tracing ---------------------------------------------------------------------------------------------------------

    /** {@code /revival auth trace start}. */
    public synchronized void startTrace(final java.nio.file.Path directory) throws java.io.IOException {
        this.stopTrace();
        this.trace = TraceRecorder.start(directory, this.player, Revival.settings().toString());
    }

    /** {@code /revival auth trace stop}; returns the file, or null if none was running. */
    public synchronized java.nio.file.@Nullable Path stopTrace() {
        final TraceRecorder recorder = this.trace;
        this.trace = null;
        if (recorder == null) {
            return null;
        }
        recorder.close();
        return recorder.file();
    }
}
