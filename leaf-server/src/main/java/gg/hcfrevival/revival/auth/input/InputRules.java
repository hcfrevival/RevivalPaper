package gg.hcfrevival.revival.auth.input;

/**
 * What a vanilla 26.3 client can do inside one client tick, beyond the packet order {@code ClientStream} checks (design
 * §10, §15.4). Each rule is a fact of {@code Minecraft.handleKeybinds} or of screens:
 * <ul>
 *   <li>{@link #ATTACK_WHILE_USING}, {@link #USE_WHILE_USING}: while the player uses an item, handleKeybinds takes the
 *       {@code isUsingItem} branch, which only releases and throws every attack and use click away, the release tick
 *       included;</li>
 *   <li>{@link #ATTACK_AFTER_USE}: attack clicks are handled before use clicks, so no attack follows a use in one
 *       tick;</li>
 *   <li>{@link #ATTACK_AFTER_CLOSE}: a screen eats the clicks made while it is open, and closing it adds none: every
 *       tick the screen was open set {@code missTime = 10000}, so the next tick's {@code startAttack} drops every
 *       click until {@code continueAttack} clears it (report 02 §2.3, {@code Minecraft.java:1816-1826, 1658}). An
 *       attack in the tick a container closed means a click no screen saw;</li>
 *   <li>{@link #CLICK_WHILE_MOVING}: opening a screen releases every key ({@code KeyMapping.releaseAll}) and keys pressed
 *       over a screen don't reach the game, so a container click in a tick whose sent input still walks or jumps is an
 *       inventory move. The exception is a container that closed after the click (closing re-reads the held keys, and
 *       toggled sneak and sprint come back, so those two never count), and only when some tick since the last close
 *       sent no walking keys: the release of the screen's opening. A click and a close every tick while the keys stay
 *       down never opened a screen (review E8);</li>
 *   <li>{@link #AFTER_MOVE}: nothing but {@code TickEnd} follows the move claim.</li>
 * </ul>
 * In enforce, an action that breaks a rule is not handled; the client's own prediction of it still updates the model.
 * One instance per player, main thread.
 */
public final class InputRules {

    public static final int OK = 0;
    public static final int ATTACK_WHILE_USING = 1;
    public static final int USE_WHILE_USING = 2;
    public static final int ATTACK_AFTER_USE = 3;
    public static final int ATTACK_AFTER_CLOSE = 4;
    public static final int CLICK_WHILE_MOVING = 5;
    public static final int AFTER_MOVE = 6;
    public static final int RULES = 7;

    // What a packet does, as far as the rules care
    public static final int OTHER = 0;
    public static final int ATTACK = 1;
    /** {@code UseItem}, {@code UseItemOn}, {@code Interact}: the use key. */
    public static final int USE = 2;
    public static final int RELEASE = 3;
    public static final int CONTAINER_CLOSE = 4;
    public static final int CONTAINER_CLICK = 5;
    public static final int MOVE = 6;
    public static final int TICK_END = 7;

    private static final String[] NAMES = {"ok", "attack-while-using", "use-while-using", "attack-after-use", "attack-after-close",
        "click-while-moving", "after-move"};

    private final long[] counts = new long[RULES];
    private boolean usingAtKeybinds;
    private boolean walking;
    private boolean used;
    private boolean closed;
    private boolean moved;
    // Some tick since the last container close ran with no walking keys: a screen may have opened (releaseAll)
    private boolean keysReleasedSinceClose = true;

    /** A window starts. {@code walking}: the input the window ends with has a movement or jump key down. */
    public void startWindow(final boolean walking) {
        this.usingAtKeybinds = false;
        this.walking = walking;
        this.used = false;
        this.closed = false;
        this.moved = false;
        if (!walking) {
            this.keysReleasedSinceClose = true;
        }
    }

    /** Whether an action breaking {@code rule} is not handled in enforce. Every rule drops for now; client profiles
     * (design §15.8) may relax one where a client provably behaves differently. */
    public static boolean drops(final int rule) {
        return rule != OK;
    }

    /** The window's keybinds start (after its drain and screens): {@code using}, the client uses an item now. */
    public void keybinds(final boolean using) {
        this.usingAtKeybinds = using;
    }

    /** Whether a sent input walks or jumps (sneak and sprint can be toggles, which a closing screen restores). */
    public static boolean walking(final net.minecraft.world.entity.player.Input input) {
        return input.forward() || input.backward() || input.left() || input.right() || input.jump();
    }

    /** What {@code packet} does, as far as the rules care. */
    public static int action(final net.minecraft.network.protocol.Packet<?> packet) {
        return switch (packet) {
            case final net.minecraft.network.protocol.game.ServerboundAttackPacket ignored -> ATTACK;
            case final net.minecraft.network.protocol.game.ServerboundUseItemPacket ignored -> USE;
            case final net.minecraft.network.protocol.game.ServerboundUseItemOnPacket ignored -> USE;
            case final net.minecraft.network.protocol.game.ServerboundInteractPacket ignored -> USE;
            case final net.minecraft.network.protocol.game.ServerboundPlayerActionPacket action
                when action.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM -> RELEASE;
            case final net.minecraft.network.protocol.game.ServerboundContainerClosePacket ignored -> CONTAINER_CLOSE;
            case final net.minecraft.network.protocol.game.ServerboundContainerClickPacket ignored -> CONTAINER_CLICK;
            case final net.minecraft.network.protocol.game.ServerboundMovePlayerPacket ignored -> MOVE;
            case final net.minecraft.network.protocol.game.ServerboundClientTickEndPacket ignored -> TICK_END;
            default -> OTHER;
        };
    }

    /**
     * The window's next packet ({@link #ATTACK}, {@link #USE}...). {@code closesLater}: a container close follows it in the
     * window. Returns {@link #OK} or the rule it breaks (counted).
     */
    public int check(final int action, final boolean closesLater) {
        final int rule = this.rule(action, closesLater);
        switch (action) {
            case USE -> this.used = true;
            case CONTAINER_CLOSE -> {
                this.closed = true;
                this.keysReleasedSinceClose = false;
            }
            case MOVE -> this.moved = true;
            default -> {
            }
        }
        if (rule != OK) {
            this.counts[rule]++;
        }
        return rule;
    }

    private int rule(final int action, final boolean closesLater) {
        if (this.moved && action != TICK_END && action != MOVE) {
            return AFTER_MOVE;
        }
        return switch (action) {
            case ATTACK -> this.usingAtKeybinds ? ATTACK_WHILE_USING : this.used ? ATTACK_AFTER_USE : this.closed ? ATTACK_AFTER_CLOSE : OK;
            case USE -> this.usingAtKeybinds ? USE_WHILE_USING : OK;
            case CONTAINER_CLICK -> this.walking && !(closesLater && this.keysReleasedSinceClose) ? CLICK_WHILE_MOVING : OK;
            default -> OK;
        };
    }

    public long count(final int rule) {
        return this.counts[rule];
    }

    public long total() {
        long total = 0;
        for (int rule = 1; rule < RULES; rule++) {
            total += this.counts[rule];
        }
        return total;
    }

    public static String name(final int rule) {
        return rule >= 0 && rule < RULES ? NAMES[rule] : "unknown-" + rule;
    }
}
