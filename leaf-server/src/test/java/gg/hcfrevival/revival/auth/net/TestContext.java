package gg.hcfrevival.revival.auth.net;

import gg.hcfrevival.revival.auth.trace.TraceRecorder;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A {@link Transactions.Context} with plain fields. */
final class TestContext implements Transactions.Context {

    int self = 7;
    UUID uuid = new UUID(1L, 2L);
    int window = 1;
    int paced = Integer.MIN_VALUE; // MIN_VALUE: the same as window
    int serverTick;
    boolean coalesce;
    long stamp;
    boolean main = true;
    boolean ticking = true;

    @Override
    public boolean ticking() {
        return this.ticking;
    }

    @Override
    public boolean mainThread() {
        return this.main;
    }

    @Override
    public long stampMillis() {
        return this.stamp;
    }

    @Override
    public int selfId() {
        return this.self;
    }

    @Override
    public UUID selfUuid() {
        return this.uuid;
    }

    @Override
    public int windowTick() {
        return this.window;
    }

    @Override
    public int pacedTick() {
        return this.paced == Integer.MIN_VALUE ? this.window : this.paced;
    }

    @Override
    public int serverTick() {
        return this.serverTick;
    }

    @Override
    public boolean coalesce() {
        return this.coalesce;
    }

    @Override
    public @Nullable TraceRecorder trace() {
        return null;
    }
}
