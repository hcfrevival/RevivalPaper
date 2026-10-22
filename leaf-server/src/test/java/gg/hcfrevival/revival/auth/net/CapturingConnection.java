package gg.hcfrevival.revival.auth.net;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

/** A connection that records what is sent on it, in call order (the order Paper writes it). */
final class CapturingConnection extends Connection {

    final List<Packet<?>> sent = new ArrayList<>();

    CapturingConnection() {
        super(PacketFlow.CLIENTBOUND);
        this.channel = new EmbeddedChannel();
    }

    @Override
    public void send(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) {
        this.sent.add(packet);
    }

    @Override
    public boolean isConnected() {
        return true;
    }
}
