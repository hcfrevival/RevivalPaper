package gg.hcfrevival.revival.livetest;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

/** A connection with no client: every packet the server sends is recorded instead of written. */
final class RecordingConnection extends Connection {

    final List<Packet<?>> sent = new CopyOnWriteArrayList<>();

    RecordingConnection() {
        super(PacketFlow.SERVERBOUND);
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

    @Override
    public <T extends PacketListener> void setupInboundProtocol(final ProtocolInfo<T> protocol, final T packetListener) {
    }

    @Override
    public void setupOutboundProtocol(final ProtocolInfo<?> protocol) {
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void handleDisconnection() {
    }
}
