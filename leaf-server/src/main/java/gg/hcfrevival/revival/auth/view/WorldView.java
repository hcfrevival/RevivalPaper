package gg.hcfrevival.revival.auth.view;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * The world as the player's client holds it (design §4 and §15.2, report 02 §3.9, report 04 §6): what this client was
 * <em>sent</em>, not the live world.
 * <ul>
 *   <li>{@code sent}: per position, the state of the last acked {@code BlockUpdate}/{@code SectionBlocksUpdate}. It
 *       persists until a later packet for the position, or chunk data for its chunk, replaces it. Plugin fake blocks
 *       ({@code CraftPlayer.sendBlockChange}: HCF claim and combat walls) therefore stay exactly as long as the client
 *       has them.</li>
 *   <li>{@code pinned}: server changes the client has not been sent yet. {@code LevelChunk.setBlockState} reports every
 *       change (whatever its update flags) to the players holding that chunk; the pre-change state is pinned until a
 *       packet for the position is acked. A change the server never broadcasts stays pinned: the client never got it.</li>
 *   <li>{@code predictions}: blocks the client changed itself with sequence {@code s} ({@code BlockStatePredictionHandler}):
 *       a server update for a predicted position is only stored; at the {@code BlockChangedAck >= s} the block becomes
 *       the stored server state. Sequences must increase; predictions expire at the ack deadline.</li>
 *   <li>the chunks the client holds (acked chunk data and forgets).</li>
 * </ul>
 * Reads: prediction, then sent, then pinned, then the live world. Only {@code BlockState}/{@code FluidState} come out of
 * here: the simulation computes shapes itself and never calls Paper's patched collision helpers (report 07 §1.6).
 * <p>
 * v1 admits a prediction only where the server's own handling changed the block the same way (a placement the server
 * denied is not honored by the truth, §15.2 {@code honor-denied-predictions: false}); judging what a vanilla client would
 * have predicted on its own is not implemented yet. Main thread only.
 */
public final class WorldView implements gg.hcfrevival.revival.auth.sim.SimWorld, gg.hcfrevival.revival.auth.combat.PickWorld,
    gg.hcfrevival.revival.auth.visibility.OcclusionWorld {

    /** Called when a prediction ends with a different block than the client predicted (it may have to snap back). */
    public interface RevertListener {
        void onRevert(BlockPos pos, BlockState state, double playerX, double playerY, double playerZ);
    }

    private static final class Prediction {
        final int sequence;
        final int window;
        final BlockState predicted;
        BlockState server;
        final double playerX;
        final double playerY;
        final double playerZ;

        Prediction(final int sequence, final int window, final BlockState predicted, final BlockState server, final double playerX, final double playerY, final double playerZ) {
            this.sequence = sequence;
            this.window = window;
            this.predicted = predicted;
            this.server = server;
            this.playerX = playerX;
            this.playerY = playerY;
            this.playerZ = playerZ;
        }
    }

    private @Nullable ServerLevel level;
    private final Long2ObjectOpenHashMap<BlockState> sent = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<BlockState> pinned = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Prediction> predictions = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet chunks = new LongOpenHashSet();
    private boolean chunksKnown;
    private int lastTeleportSequence;
    private int lastSequence;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private long pins;
    private long reverts;
    private long sequenceViolations;
    private long expired;

    /**
     * The level the client is in. A new level (login, respawn, dimension change) clears everything; {@code chunksKnown}
     * says whether the chunk list is tracked from the start (false when authority starts mid-session).
     */
    public void setLevel(final ServerLevel level, final boolean chunksKnown) {
        if (this.level != level || chunksKnown) {
            this.sent.clear();
            this.pinned.clear();
            this.predictions.clear();
            this.chunks.clear();
        }
        this.level = level;
        this.chunksKnown = chunksKnown;
    }

    public @Nullable ServerLevel level() {
        return this.level;
    }

    // Reads --------------------------------------------------------------------------------------------------------

    /**
     * The block state the client has at a position: air for chunks it doesn't hold and outside the build height
     * (ClientLevel's empty chunk and {@code VOID_AIR}, the {@code SimWorld} contract).
     */
    @Override
    public BlockState blockState(final int x, final int y, final int z) {
        final ServerLevel live = this.liveLevel();
        if (y < live.getMinY() || y >= live.getMinY() + live.getHeight()) {
            return net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState();
        }
        if (!this.hasChunk(x >> 4, z >> 4)) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        final long key = BlockPos.asLong(x, y, z);
        if (!this.predictions.isEmpty()) {
            final Prediction prediction = this.predictions.get(key);
            if (prediction != null) {
                return prediction.predicted;
            }
        }
        if (!this.sent.isEmpty()) {
            final BlockState state = this.sent.get(key);
            if (state != null) {
                return state;
            }
        }
        if (!this.pinned.isEmpty()) {
            final BlockState state = this.pinned.get(key);
            if (state != null) {
                return state;
            }
        }
        return live.getBlockState(this.cursor.set(x, y, z));
    }

    public BlockState blockState(final BlockPos pos) {
        return this.blockState(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The fluid the client has: every fluid in vanilla is part of a block state. */
    @Override
    public FluidState fluidState(final int x, final int y, final int z) {
        return this.blockState(x, y, z).getFluidState();
    }

    /**
     * Whether the client holds the chunk ({@code Level.hasChunkAt} on the client: the {@code -0.1} gravity branch and
     * {@code hasFluidAndLoaded}). Before the chunk list is known, whatever the server has loaded.
     */
    public boolean hasChunk(final int chunkX, final int chunkZ) {
        if (this.chunksKnown) {
            return this.chunks.contains(ChunkPos.pack(chunkX, chunkZ));
        }
        final ServerLevel level = this.level;
        return level != null && level.getChunkSource().getChunkAtIfLoadedImmediately(chunkX, chunkZ) != null;
    }

    public boolean hasChunkAt(final int blockX, final int blockZ) {
        return this.hasChunk(blockX >> 4, blockZ >> 4);
    }

    /** The world border as the client holds it; v1: the live border (lerping borders are not mirrored yet). */
    public WorldBorder border() {
        return this.liveLevel().getWorldBorder();
    }

    @Override
    public int minY() {
        return this.liveLevel().getMinY();
    }

    @Override
    public int height() {
        return this.liveLevel().getHeight();
    }

    @Override
    public boolean isChunkLoaded(final int chunkX, final int chunkZ) {
        return this.hasChunk(chunkX, chunkZ);
    }

    /**
     * {@code LevelChunkSection.hasFluid()} of the client's section: the live section's, or true when the overlay holds a
     * fluid in that section (the client's section counts fluids from the states it has).
     */
    @Override
    public boolean sectionHasFluid(final int chunkX, final int sectionY, final int chunkZ) {
        final ServerLevel live = this.liveLevel();
        if (!this.hasChunk(chunkX, chunkZ)) {
            return false;
        }
        final net.minecraft.world.level.chunk.LevelChunk chunk = live.getChunkSource().getChunkAtIfLoadedImmediately(chunkX, chunkZ);
        final int index = sectionY - live.getMinSectionY();
        if (chunk == null || index < 0 || index >= chunk.getSectionsCount()) {
            return false;
        }
        return chunk.getSection(index).hasFluid() || this.overlayHasFluid(chunkX, sectionY, chunkZ);
    }

    private boolean overlayHasFluid(final int chunkX, final int sectionY, final int chunkZ) {
        return hasFluidIn(this.sent, chunkX, sectionY, chunkZ) || hasFluidIn(this.pinned, chunkX, sectionY, chunkZ);
    }

    private static boolean hasFluidIn(final Long2ObjectOpenHashMap<BlockState> map, final int chunkX, final int sectionY, final int chunkZ) {
        if (map.isEmpty()) {
            return false;
        }
        final ObjectIterator<Long2ObjectMap.Entry<BlockState>> iterator = map.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            final Long2ObjectMap.Entry<BlockState> entry = iterator.next();
            final long key = entry.getLongKey();
            if (BlockPos.getX(key) >> 4 == chunkX && BlockPos.getY(key) >> 4 == sectionY && BlockPos.getZ(key) >> 4 == chunkZ
                && !entry.getValue().getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double borderMinX() {
        return this.border().getMinX();
    }

    @Override
    public double borderMinZ() {
        return this.border().getMinZ();
    }

    @Override
    public double borderMaxX() {
        return this.border().getMaxX();
    }

    @Override
    public double borderMaxZ() {
        return this.border().getMaxZ();
    }

    /** v1: boats, shulkers and happy ghasts are not mirrored for the player's own collision; none are added. */
    @Override
    public void collectEntityColliders(final gg.hcfrevival.revival.auth.sim.SimBox area, final gg.hcfrevival.revival.auth.sim.SimBoxList out) {
    }

    @Override
    public boolean fastLava() {
        return this.liveLevel().environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA);
    }

    private ServerLevel liveLevel() {
        final ServerLevel level = this.level;
        if (level == null) {
            throw new IllegalStateException("WorldView has no level yet");
        }
        return level;
    }

    // World changes, as the server makes them ------------------------------------------------------------------

    /** Whether a change at this position reaches this client (it holds the chunk, so the broadcast will come). */
    public boolean watches(final int blockX, final int blockZ) {
        return this.hasChunk(blockX >> 4, blockZ >> 4);
    }

    /** The server changed a block in a chunk this client holds: pin what the client still has. */
    public void onServerChange(final BlockPos pos, final BlockState oldState) {
        final long key = pos.asLong();
        if (this.sent.containsKey(key) || this.pinned.containsKey(key) || this.predictions.containsKey(key)) {
            return;
        }
        this.pinned.put(key, oldState);
        this.pins++;
    }

    // Acked packets -------------------------------------------------------------------------------------------------

    /** An acked {@code BlockUpdate} (or one position of a section update). */
    public void onBlockUpdate(final BlockPos pos, final BlockState state) {
        final long key = pos.asLong();
        final Prediction prediction = this.predictions.get(key);
        if (prediction != null) {
            prediction.server = state; // ClientLevel.setServerVerifiedBlockState: stored, the predicted block stays
            return;
        }
        this.sent.put(key, state);
        this.pinned.remove(key);
    }

    /** Acked chunk data: the client replaced every block in the chunk. */
    public void onChunk(final int chunkX, final int chunkZ) {
        this.chunks.add(ChunkPos.pack(chunkX, chunkZ));
        this.clearChunk(chunkX, chunkZ);
    }

    /** An acked {@code ForgetLevelChunk}. */
    public void onForgetChunk(final int chunkX, final int chunkZ) {
        this.chunks.remove(ChunkPos.pack(chunkX, chunkZ));
        this.clearChunk(chunkX, chunkZ);
        if (!this.predictions.isEmpty()) {
            this.predictions.keySet().removeIf(key -> (BlockPos.getX(key) >> 4) == chunkX && (BlockPos.getZ(key) >> 4) == chunkZ);
        }
    }

    /**
     * A client prediction made in this tick's keybinds with sequence {@code s}: the client changed the block before this
     * tick's movement. {@code admitted}: a vanilla client would have made it (PredictionRules); otherwise only the sequence
     * is kept. The predicted state is the live state after the server handled the action; the state the client
     * returns to at the ack is what it had before. Returns false for a sequence that did not increase (a violation).
     */
    public boolean predict(final BlockPos pos, final int sequence, final int window, final double playerX, final double playerY, final double playerZ,
                           final boolean admitted) {
        if (sequence < this.lastSequence) {
            this.sequenceViolations++;
            return false;
        }
        this.lastSequence = sequence;
        if (!admitted) {
            return true; // not a change a vanilla client predicts (§15.2): the server's block update brings it, at its ack
        }
        final long key = pos.asLong();
        final BlockState live = this.liveLevel().getBlockState(pos);
        final Prediction existing = this.predictions.get(key);
        final BlockState before = existing != null ? existing.server : this.blockStateWithoutPrediction(key, pos);
        if (existing == null && before == live) {
            return true; // the server did not change it: nothing the truth would honor
        }
        // BlockStatePredictionHandler.retainKnownServerState keeps the first server state recorded for a position
        this.predictions.put(key, new Prediction(sequence, window, live, before, playerX, playerY, playerZ));
        return true;
    }

    private BlockState blockStateWithoutPrediction(final long key, final BlockPos pos) {
        final BlockState sentState = this.sent.get(key);
        if (sentState != null) {
            return sentState;
        }
        final BlockState pinnedState = this.pinned.get(key);
        return pinnedState != null ? pinnedState : this.liveLevel().getBlockState(pos);
    }

    /** An acked {@code BlockChangedAck(sequence)}: {@code endPredictionsUpTo}. */
    public void onPredictionAck(final int sequence, final RevertListener listener) {
        this.endPredictions(sequence, Integer.MAX_VALUE, listener);
    }

    /** Predictions made at or before window {@code window} whose ack never came are ended as if acked (§15.2). */
    public void expire(final int window, final RevertListener listener) {
        this.endPredictions(Integer.MAX_VALUE, window, listener);
    }

    private void endPredictions(final int sequence, final int window, final RevertListener listener) {
        if (this.predictions.isEmpty()) {
            return;
        }
        final ObjectIterator<Long2ObjectMap.Entry<Prediction>> iterator = this.predictions.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            final Long2ObjectMap.Entry<Prediction> entry = iterator.next();
            final Prediction prediction = entry.getValue();
            if (prediction.sequence > sequence || prediction.window > window) {
                continue;
            }
            iterator.remove();
            if (window != Integer.MAX_VALUE) {
                this.expired++;
            }
            final long key = entry.getLongKey();
            this.sent.put(key, prediction.server);
            this.pinned.remove(key);
            if (prediction.server != prediction.predicted) {
                this.reverts++;
                listener.onRevert(BlockPos.of(key), prediction.server, prediction.playerX, prediction.playerY, prediction.playerZ);
            }
        }
    }

    /** An acked {@code PlayerPosition}: {@code blockStatePredictionHandler.onTeleport()}. */
    public void onTeleport() {
        this.lastTeleportSequence = this.lastSequence;
    }

    /** {@code lastTeleportSequence}: the client skips the revert snap for predictions older than its last teleport. */
    public int lastTeleportSequence() {
        return this.lastTeleportSequence;
    }

    /** Forgets every overlay entry (resync from the server's world). */
    /** §11: a full opaque cube as this client was sent it hides what is behind it. */
    @Override
    public boolean occludes(final int x, final int y, final int z) {
        return this.level != null && this.blockState(x, y, z).isSolidRender();
    }

    public void clear() {
        this.sent.clear();
        this.pinned.clear();
        this.predictions.clear();
    }

    /**
     * A resync from the server: pinned states and predictions go, but never the sent states, which are what the client
     * was sent (plugin fake blocks included) and which a resync does not change (cheat suite #32).
     */
    public void clearUnsent() {
        this.pinned.clear();
        this.predictions.clear();
    }

    private void clearChunk(final int chunkX, final int chunkZ) {
        if (!this.sent.isEmpty()) {
            this.sent.keySet().removeIf(key -> (BlockPos.getX(key) >> 4) == chunkX && (BlockPos.getZ(key) >> 4) == chunkZ);
        }
        if (!this.pinned.isEmpty()) {
            this.pinned.keySet().removeIf(key -> (BlockPos.getX(key) >> 4) == chunkX && (BlockPos.getZ(key) >> 4) == chunkZ);
        }
    }

    public int sentCount() {
        return this.sent.size();
    }

    public int pinnedCount() {
        return this.pinned.size();
    }

    public int predictionCount() {
        return this.predictions.size();
    }

    public int chunkCount() {
        return this.chunks.size();
    }

    public boolean chunksKnown() {
        return this.chunksKnown;
    }

    public long pins() {
        return this.pins;
    }

    public long reverts() {
        return this.reverts;
    }

    public long sequenceViolations() {
        return this.sequenceViolations;
    }

    public long expired() {
        return this.expired;
    }
}
