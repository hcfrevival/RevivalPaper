package gg.hcfrevival.revival.auth.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import org.jspecify.annotations.Nullable;

/**
 * When each {@code TickEnd} came off the wire (torture F4). The main thread handles packets between and at the start
 * of server ticks, so a server hitch delays every window that arrived during it; the tick clock must credit the client
 * for that time from the window's real arrival, not from when the main thread got to it. A Netty handler in front of the
 * connection's own stamps each one, in order; the main thread takes the stamps in the same order ({@link #take}). The
 * packet is a singleton, so order is all that pairs them. A {@code TickEnd} decoded before the handler went in, or one
 * whose stamp a full ring dropped, takes a later one's stamp: a later arrival, which credits the client less, never
 * more; the pairing is right again as soon as the main thread empties the ring. With no handler (a test connection) the
 * main thread's own time stands in.
 */
public final class ArrivalStamps {

    static final String HANDLER = "revival_arrival";
    static final int RING = 256;

    // Written on the event loop, read on the main thread, under this object's lock
    private final long[] stamps = new long[RING];
    private int head;
    private int count;
    private @Nullable Channel installedOn;

    private final ChannelHandler handler = new Stamper(this);

    // Sharable (review PT2-01, R2C-01): Netty refuses a second add of a non-sharable handler that was ever added and
    // removed, so authority off and on again threw in activate() on every TickEnd. Its only state is the stamps, locked
    @ChannelHandler.Sharable
    private static final class Stamper extends ChannelInboundHandlerAdapter {
        private final ArrivalStamps stamps;

        Stamper(final ArrivalStamps stamps) {
            this.stamps = stamps;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            if (msg instanceof ServerboundClientTickEndPacket) {
                this.stamps.stamp(System.nanoTime());
            }
            ctx.fireChannelRead(msg);
        }
    }

    synchronized void stamp(final long nanos) {
        if (this.count == RING) {
            this.head = (this.head + 1) % RING; // the main thread is far behind: the oldest stamp goes
            this.count--;
        }
        this.stamps[(this.head + this.count) % RING] = nanos;
        this.count++;
    }

    /** The arrival of the oldest {@code TickEnd} not taken yet, or {@code fallback} when none was stamped. Main thread. */
    public synchronized long take(final long fallback) {
        if (this.count == 0) {
            return fallback;
        }
        final long nanos = this.stamps[this.head];
        this.head = (this.head + 1) % RING;
        this.count--;
        return Math.min(nanos, fallback);
    }

    /**
     * The most windows a flush may be stamped past the last one handled: {@code maxBanked} (review X2-02: what the client
     * can catch up at once, so a stall never voids the ack deadline) plus every {@code TickEnd} that came off the wire and
     * waits for the main thread. Those the client did send; a server hitch left them queued, and the hitched tick's own
     * flush stamped short of them was due as soon as they ran (torture r80j10, a 725 ms hitch: one forced entry, six
     * corrections). Any thread.
     */
    public synchronized int creditBound(final int maxBanked) {
        return maxBanked + this.count;
    }

    /**
     * Puts the handler in front of the connection's own; false when the channel has no game pipeline, or the pipeline
     * refused it (a channel closing under us): the main thread's own time stands in, authority still starts.
     */
    public boolean install(final Channel channel) {
        final ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HandlerNames.PACKET_HANDLER) == null) {
            return false;
        }
        this.uninstall();
        synchronized (this) {
            this.count = 0;
        }
        try {
            pipeline.addBefore(HandlerNames.PACKET_HANDLER, HANDLER, this.handler);
        } catch (final RuntimeException e) {
            return false; // ChannelPipelineException, NoSuchElementException: the pipeline changed on the event loop
        }
        this.installedOn = channel;
        return true;
    }

    public void uninstall() {
        final Channel channel = this.installedOn;
        if (channel == null) {
            return;
        }
        this.installedOn = null;
        try {
            channel.pipeline().remove(this.handler); // by instance: no get-then-remove race with a closing channel
        } catch (final java.util.NoSuchElementException e) {
            // already gone with the channel
        }
    }
}
