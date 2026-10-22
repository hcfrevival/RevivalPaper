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

    private final ChannelHandler handler = new ChannelInboundHandlerAdapter() {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            if (msg instanceof ServerboundClientTickEndPacket) {
                ArrivalStamps.this.stamp(System.nanoTime());
            }
            ctx.fireChannelRead(msg);
        }
    };

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

    /** Puts the handler in front of the connection's own; false when the channel has no game pipeline. */
    public boolean install(final Channel channel) {
        final ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HandlerNames.PACKET_HANDLER) == null) {
            return false;
        }
        this.uninstall();
        synchronized (this) {
            this.count = 0;
        }
        pipeline.addBefore(HandlerNames.PACKET_HANDLER, HANDLER, this.handler);
        this.installedOn = channel;
        return true;
    }

    public void uninstall() {
        final Channel channel = this.installedOn;
        if (channel == null) {
            return;
        }
        this.installedOn = null;
        final ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HANDLER) != null) {
            pipeline.remove(HANDLER);
        }
    }
}
