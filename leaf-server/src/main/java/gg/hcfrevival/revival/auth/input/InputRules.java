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
 *       attack in the tick a container closed means a click no screen saw. Not for a player whose miss penalty the
 *       server turned off through Lunar's Apollo ({@code ClientProfile.missPenaltyDisabled}, server truth): Lunar may
 *       land that click, and the server chose to let it (client-compat L1);</li>
 *   <li>{@link #CLICK_WHILE_MOVING}: opening a screen releases every key ({@code KeyMapping.releaseAll}) and keys pressed
 *       over a screen don't reach the game, so a container click in a tick whose sent input still walks or jumps is an
 *       inventory move. The exception is a container that closed after the click (closing re-reads the held keys, and
 *       toggled sneak and sprint come back, so those two never count), and only when some tick since the last close
 *       sent no walking keys: the release of the screen's opening. A click and a close every tick while the keys stay
 *       down never opened a screen (review E8);</li>
 *   <li>{@link #AFTER_MOVE}: nothing but {@code TickEnd} follows the move claim;</li>
 *   <li>{@link #ATTACK_WITHOUT_PUNCH}: every attack click swings ({@code startAttack} -> {@code swing}, a {@code Punch}
 *       after the {@code Attack}), so a window with more attacks than punches hid its swings (cheat suite 2 L2:
 *       NoSwing, the click cadence and viewers' swing animations both blind). Counted at the {@code TickEnd}, never
 *       dropped.</li>
 *   <li>{@link #RELEASE_WITHOUT_USE}: {@code releaseUsingItem} is only sent from the {@code isUsingItem} branch, so a
 *       window that didn't start using sends none. A {@code UseItem} and its release in one window block for no tick at
 *       all: a zero-cost block-hit that re-armed the sprint knock (classic audit #5).</li>
 *   <li>{@link #USE_ROTATION}: {@code handleKeybinds} sends the rotation the tick runs with (the mouse turns between
 *       ticks), and the server aims a throw along the {@code UseItem}'s own: a different one aims a pot, a rod or a pearl
 *       where the claims never looked. Rewritten to the tick's, counted, never dropped: some clients send keybind
 *       packets outside the tick (client-compat L10) (classic audit #3).</li>
 *   <li>{@link #OFFHAND_AFTER_MAIN}: {@code startUseItem} tries the main hand first and stops at a use that consumes the
 *       click, so with a main-hand item that starts using (a sword's block, food, a bow) no off-hand use, use-on or
 *       interact follows: an off-hand pot thrown while the sword stays in hand (classic audit #3).</li>
 *   <li>{@link #SLOT_AFTER_ACTION}: hotbar keys are read at the top of {@code handleKeybinds} and every attack and use
 *       sends its slot first ({@code ensureHasSentCarriedItem}), so no {@code SetCarriedItem} to another slot follows the
 *       tick's first attack or use: a pot thrown and the sword back in hand in one tick (pvp-issues IN-5, classic audit
 *       #3). The client is sent its slot back.</li>
 *   <li>{@link #UNSEEN_TARGET}: {@code useItemOn} and {@code startDestroyBlock} act on the block {@code Minecraft.hitResult}
 *       is on, the first block along the client's crosshair, so another block's outline never stands between its eye and
 *       the point a use names, nor hides every point of the face a dig names: a door, a chest or a block worked through a
 *       wall (ghost hand, pvp-issues WD-4). Judged on the client's own world, so a block it broke and still lacks is no
 *       wall. The block on the crosshair passes; failing that, the line of sight does, so a click off the tick's rotation
 *       still passes.</li>
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
    public static final int ATTACK_WITHOUT_PUNCH = 7;
    public static final int RELEASE_WITHOUT_USE = 8;
    public static final int USE_ROTATION = 9;
    public static final int OFFHAND_AFTER_MAIN = 10;
    public static final int SLOT_AFTER_ACTION = 11;
    public static final int UNSEEN_TARGET = 12;
    public static final int RULES = 13;

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
    public static final int PUNCH = 8;

    private static final String[] NAMES = {"ok", "attack-while-using", "use-while-using", "attack-after-use", "attack-after-close",
        "click-while-moving", "after-move", "attack-without-punch", "release-without-use",
        "use-rotation", "offhand-after-main", "slot-after-action", "unseen-target"};

    private final long[] counts = new long[RULES];
    private boolean usingAtKeybinds;
    // ATTACK_AFTER_CLOSE holds: the client's screens eat the next tick's first click, as vanilla's missTime does
    private boolean screenEatsClick = true;
    private boolean walking;
    private boolean used;
    private boolean closed;
    private boolean moved;
    // An attack or a use key action came in this window: no hotbar change follows it
    private boolean acted;
    private int attacks;
    private int punches;
    // Some tick since the last container close ran with no walking keys: a screen may have opened (releaseAll)
    private boolean keysReleasedSinceClose = true;

    /** A window starts. {@code walking}: the input the window ends with has a movement or jump key down. */
    public void startWindow(final boolean walking) {
        this.usingAtKeybinds = false;
        this.walking = walking;
        this.used = false;
        this.closed = false;
        this.moved = false;
        this.acted = false;
        this.attacks = 0;
        this.punches = 0;
        if (!walking) {
            this.keysReleasedSinceClose = true;
        }
    }

    /** Whether an action breaking {@code rule} is not handled in enforce. Every rule drops for now; client profiles
     * (design §15.8) may relax one where a client provably behaves differently. */
    public static boolean drops(final int rule) {
        return rule != OK && rule != ATTACK_WITHOUT_PUNCH && rule != USE_ROTATION;
    }

    /**
     * Whether this player's client eats the first click after a screen ({@code missTime = 10000}): false while the
     * server has turned its miss penalty off through Apollo, so {@link #ATTACK_AFTER_CLOSE} doesn't hold for it.
     */
    public void screenEatsClick(final boolean eats) {
        this.screenEatsClick = eats;
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
            // A spear's attack: Minecraft.startAttack sends it in place of the Attack, under the same keybinds (pvp-issues HR-7)
            case final net.minecraft.network.protocol.game.ServerboundPlayerActionPacket action
                when action.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STAB -> ATTACK;
            case final net.minecraft.network.protocol.game.ServerboundUseItemPacket ignored -> USE;
            case final net.minecraft.network.protocol.game.ServerboundUseItemOnPacket ignored -> USE;
            case final net.minecraft.network.protocol.game.ServerboundInteractPacket ignored -> USE;
            case final net.minecraft.network.protocol.game.ServerboundPlayerActionPacket action
                when action.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM -> RELEASE;
            case final net.minecraft.network.protocol.game.ServerboundContainerClosePacket ignored -> CONTAINER_CLOSE;
            case final net.minecraft.network.protocol.game.ServerboundContainerClickPacket ignored -> CONTAINER_CLICK;
            case final net.minecraft.network.protocol.game.ServerboundMovePlayerPacket ignored -> MOVE;
            case final net.minecraft.network.protocol.game.ServerboundClientTickEndPacket ignored -> TICK_END;
            case final net.minecraft.network.protocol.game.ServerboundPunchPacket ignored -> PUNCH;
            default -> OTHER;
        };
    }

    /**
     * The window's next packet ({@link #ATTACK}, {@link #USE}...). {@code closesLater}: a container close follows it in the
     * window. Returns {@link #OK} or the rule it breaks (counted).
     */
    public int check(final int action, final boolean closesLater) {
        final int rule = this.rule(action, closesLater);
        if (action == ATTACK || action == USE) {
            this.acted = true;
        }
        switch (action) {
            case USE -> this.used = true;
            case CONTAINER_CLOSE -> {
                this.closed = true;
                this.keysReleasedSinceClose = false;
            }
            case MOVE -> this.moved = true;
            case ATTACK -> {
                if (rule == OK) {
                    this.attacks++; // one another rule dropped is counted there already
                }
            }
            case PUNCH -> this.punches++;
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
            case ATTACK -> this.usingAtKeybinds ? ATTACK_WHILE_USING : this.used ? ATTACK_AFTER_USE
                : this.closed && this.screenEatsClick ? ATTACK_AFTER_CLOSE : OK;
            case USE -> this.usingAtKeybinds ? USE_WHILE_USING : OK;
            case RELEASE -> this.usingAtKeybinds ? OK : RELEASE_WITHOUT_USE;
            case CONTAINER_CLICK -> this.walking && !(closesLater && this.keysReleasedSinceClose) ? CLICK_WHILE_MOVING : OK;
            case TICK_END -> this.attacks > this.punches ? ATTACK_WITHOUT_PUNCH : OK;
            default -> OK;
        };
    }

    /**
     * A use key action that passed {@link #check}: {@code offHand} its hand, {@code mainStartsUsing} the main hand holds an
     * item the client starts using (its use consumed the click). Returns {@link #OK} or {@link #OFFHAND_AFTER_MAIN} (counted).
     */
    public int hand(final boolean offHand, final boolean mainStartsUsing) {
        if (offHand && mainStartsUsing) {
            this.counts[OFFHAND_AFTER_MAIN]++;
            return OFFHAND_AFTER_MAIN;
        }
        return OK;
    }

    /** A {@code SetCarriedItem}: {@code changesSlot}, to a slot other than the one held. Returns {@link #OK} or
     * {@link #SLOT_AFTER_ACTION} (counted). */
    public int carried(final boolean changesSlot) {
        if (changesSlot && this.acted && !this.moved) {
            this.counts[SLOT_AFTER_ACTION]++;
            return SLOT_AFTER_ACTION;
        }
        return OK;
    }

    /** A block use or dig on a block another block hides from the client's eye: {@link #UNSEEN_TARGET} (counted). */
    public int unseen() {
        this.counts[UNSEEN_TARGET]++;
        return UNSEEN_TARGET;
    }

    /** A {@code UseItem} whose rotation isn't the tick's (rewritten to it, never dropped). */
    public void useRotation() {
        this.counts[USE_ROTATION]++;
    }

    public long count(final int rule) {
        return this.counts[rule];
    }

    /** Every rule broken, for the grammar alert: not {@link #USE_ROTATION}, which modded clients that send keybind packets
     * outside the tick break honestly (client-compat L10); the rewrite already takes the aim away. */
    public long total() {
        long total = 0;
        for (int rule = 1; rule < RULES; rule++) {
            if (rule != USE_ROTATION) {
                total += this.counts[rule];
            }
        }
        return total;
    }

    public static String name(final int rule) {
        return rule >= 0 && rule < RULES ? NAMES[rule] : "unknown-" + rule;
    }
}
