package gg.hcfrevival.revival.livetest;

import com.mojang.authlib.GameProfile;
import gg.hcfrevival.revival.api.event.RevivalKnockbackEvent;
import gg.hcfrevival.revival.combat.LegacyHitRegistration;
import io.netty.buffer.Unpooled;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Live-server tests for RevivalPaper's combat layer. Two fake players on recording connections fight through the
 * server's real packet handlers (attack, sprint, use item), on real ticks, with the real event pipeline.
 * <p>
 * Nonce-gated: it only runs when {@code scripts/revival/live-tests.py} starts the server with
 * {@code -Drevival.livetest.nonce=<nonce>} in offline mode, and every result line carries that nonce, so a stray copy
 * on a real server does nothing and an old log can't pass a new run.
 */
public final class RevivalLiveTests extends JavaPlugin implements Listener {

    static final String NONCE_PROPERTY = "revival.livetest.nonce";
    private static final double EPSILON = 1.0E-3;

    private final Deque<Step> steps = new ArrayDeque<>();
    private String nonce = "";
    private MinecraftServer server;
    private ServerLevel level;
    private Location spawn;
    private ServerPlayer attacker;
    private ServerPlayer victim;
    private RecordingConnection victimConnection;
    private int checks;

    private int knockbacks;
    private int velocityEvents;
    private int revivalKnocks;
    private boolean cancelNextDamage;
    private float healthBefore;
    private int ticksUntilFresh;

    private record Step(int delay, String name, Runnable action) {
    }

    @Override
    public void onEnable() {
        final String property = System.getProperty(NONCE_PROPERTY);
        if (property == null || property.isBlank() || this.getServer().getOnlineMode()) {
            this.getLogger().warning("RevivalLiveTests only runs when started by scripts/revival/live-tests.py; doing nothing");
            return;
        }
        this.nonce = property;
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getScheduler().runTask(this, this::start);
    }

    // Events -------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (this.cancelNextDamage && event.getEntity().getUniqueId().equals(this.victim.getUUID())) {
            this.cancelNextDamage = false;
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(final EntityKnockbackEvent event) {
        if (this.victim != null && event.getEntity().getUniqueId().equals(this.victim.getUUID())) {
            this.knockbacks++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(final PlayerVelocityEvent event) {
        if (this.victim != null && event.getPlayer().getUniqueId().equals(this.victim.getUUID())) {
            this.velocityEvents++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRevivalKnock(final RevivalKnockbackEvent event) {
        this.revivalKnocks++;
    }

    // Scenarios ----------------------------------------------------------------------------------------------------

    private void start() {
        try {
            this.server = ((CraftServer) this.getServer()).getServer();
            this.level = ((CraftWorld) this.getServer().getWorlds().getFirst()).getHandle();
            this.spawn = this.level.getWorld().getSpawnLocation();
            final RecordingConnection attackerConnection = new RecordingConnection();
            this.victimConnection = new RecordingConnection();
            this.attacker = this.fakePlayer("RevivalAttacker", attackerConnection);
            this.victim = this.fakePlayer("RevivalVictim", this.victimConnection);
            // Players tick their physics through their connection; these have none, so tick them here
            this.getServer().getScheduler().runTaskTimer(this, () -> {
                this.attacker.doTick();
                this.victim.doTick();
            }, 1L, 1L);
        } catch (final Throwable failure) {
            this.fail("setup", failure);
            return;
        }

        // Spawn protection and the join grace period have to run out first
        this.after(80, "one knock and one velocity packet per legacy hit", this::oneKnockPerHit);
        this.after(1, "non-legacy hits stay 26.x", this::axeStaysModern);
        this.after(30, "non-legacy hits stay 26.x (hit)", this::axeHit);
        this.after(1, "a cancelled hit has no side effects", this::cancelledHit);
        this.after(25, "sprint knock once per sprint start", this::sprintFirstHit);
        this.after(25, "held sprint gets no second sprint knock", this::sprintHeldHit);
        this.after(1, "w-tap re-arms the sprint knock", this::sprintWTap);
        this.after(25, "w-tap re-arms the sprint knock (hit)", this::sprintAfterWTap);
        this.windowScenarios();
        this.after(25, "overflow hit deals the excess with no knock", this::overflowFirst);
        this.after(3, "overflow hit deals the excess with no knock (stronger hit)", this::overflowSecond);
        this.after(25, "rod then sword in the window: full damage, no second knock", this::rodHit);
        this.after(3, "rod then sword in the window: full damage, no second knock (sword)", this::swordAfterRod);
        this.after(25, "snowball knocks away from the thrower", this::snowballHit);
        this.after(2, "snowball knocks away from the thrower (packet)", this::snowballPacket);
        this.after(25, "sword-block items, packets and damage", this::swordBlock);
        this.next();
    }

    private void oneKnockPerHit() {
        this.reset(Items.DIAMOND_SWORD, 20);
        this.hit();
        this.check(this.knockbacks == 1, "exactly one EntityKnockbackEvent (got " + this.knockbacks + ")");
        this.check(this.revivalKnocks == 1, "exactly one RevivalKnockbackEvent (got " + this.revivalKnocks + ")");
        this.check(this.velocityEvents == 1, "exactly one PlayerVelocityEvent (got " + this.velocityEvents + ")");
        final List<Vec3> motions = this.motionPackets();
        this.check(motions.size() == 1, "exactly one velocity packet to the victim (got " + motions.size() + ")");
        this.check(near(motions.getFirst(), 0.0, 0.3608, 0.4), "standing knock is (0, 0.3608, 0.4): " + motions.getFirst());
        this.check(this.victim.getHealth() == 12.0F, "diamond sword deals 8 (health " + this.victim.getHealth() + ")");
        this.check(this.attacker.getAttribute(Attributes.ATTACK_SPEED).hasModifier(LegacyHitRegistration.NO_COOLDOWN), "no-cooldown modifier while a sword is held");
    }

    private void axeStaysModern() {
        this.attacker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
    }

    private void axeHit() {
        this.reset(Items.IRON_AXE, 20);
        this.hit();
        this.check(this.revivalKnocks == 0, "an axe hit isn't a legacy hit");
        this.check(this.victim.getHealth() == 11.0F, "a charged iron axe deals 26.x's 9 (health " + this.victim.getHealth() + ")");
        this.check(!this.attacker.getAttribute(Attributes.ATTACK_SPEED).hasModifier(LegacyHitRegistration.NO_COOLDOWN), "no no-cooldown modifier with an axe");
    }

    private void cancelledHit() {
        this.reset(Items.DIAMOND_SWORD, 20);
        this.sprint(false);
        this.sprint(true);
        this.cancelNextDamage = true;
        this.hit();
        this.check(this.knockbacks == 0 && this.motionPackets().isEmpty() && this.velocityEvents == 0, "no knock, no velocity packet");
        this.check(this.victim.getHealth() == 20.0F, "no damage");
        this.check(this.attacker.revivalSprintLedger.armed(false), "the sprint knock is still armed");
        this.check(this.victim.invulnerableTime == 0, "no damage window opened");
    }

    private void sprintFirstHit() {
        this.reset(Items.DIAMOND_SWORD, 20);
        this.hit();
        final List<Vec3> motions = this.motionPackets();
        this.check(motions.size() == 1 && near(motions.getFirst(), 0.0, 0.4608, 0.9), "armed sprint hit knocks (0, 0.4608, 0.9): " + motions);
        this.check(this.attacker.isSprinting(), "the attacker is still sprinting (sprint never cleared)");
    }

    private void sprintHeldHit() {
        this.reset(Items.DIAMOND_SWORD, 20);
        this.hit();
        final List<Vec3> motions = this.motionPackets();
        this.check(motions.size() == 1 && near(motions.getFirst(), 0.0, 0.3608, 0.4), "held sprint hit knocks like a standing hit: " + motions);
    }

    private void sprintWTap() {
        this.sprint(false);
        this.sprint(true);
    }

    private void sprintAfterWTap() {
        this.reset(Items.DIAMOND_SWORD, 20);
        this.hit();
        final List<Vec3> motions = this.motionPackets();
        this.check(motions.size() == 1 && near(motions.getFirst(), 0.0, 0.4608, 0.9), "re-armed sprint hit knocks (0, 0.4608, 0.9): " + motions);
        this.sprint(false);
    }

    // Retries a same-damage hit every tick until one lands fresh, so the window is measured on real ticks
    private void windowScenarios() {
        for (final int window : new int[] {20, 18, 0}) {
            this.after(25, "damage window follows the entity's " + window + " ticks", () -> {
                this.reset(Items.DIAMOND_SWORD, window);
                this.hit();
                this.healthBefore = this.victim.getHealth();
                this.ticksUntilFresh = 0;
            });
            for (int tick = 1; tick <= 15; tick++) {
                final int elapsed = tick;
                this.after(1, "damage window follows the entity's " + window + " ticks (tick " + tick + ")", () -> {
                    if (this.ticksUntilFresh == 0) {
                        this.hit();
                        if (this.victim.getHealth() < this.healthBefore) {
                            this.ticksUntilFresh = elapsed;
                        }
                    }
                });
            }
            this.after(1, "damage window follows the entity's " + window + " ticks (result)", () -> {
                // The first fresh hit lands once the window has fallen to half its length or below
                final int expected = window / 2 + (window == 0 ? 1 : 0);
                this.check(Math.abs(this.ticksUntilFresh - expected) <= 1, "window " + window + ": second hit landed after " + this.ticksUntilFresh + " ticks, expected about " + expected);
                this.windowFresh[window == 20 ? 0 : window == 18 ? 1 : 2] = this.ticksUntilFresh;
                if (window == 18) {
                    this.check(this.windowFresh[0] - this.windowFresh[1] == 1, "an 18-tick window reopens one tick before a 20-tick one");
                }
            });
        }
    }

    private final int[] windowFresh = new int[3];

    private void overflowFirst() {
        this.reset(Items.WOODEN_SWORD, 20);
        this.hit();
        this.check(this.victim.getHealth() == 15.0F, "wooden sword deals 5 (health " + this.victim.getHealth() + ")");
    }

    private void overflowSecond() {
        this.attacker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
        this.hit();
        this.check(this.victim.getHealth() == 12.0F, "diamond sword inside the window deals only the excess 3 (health " + this.victim.getHealth() + ")");
        this.check(this.knockbacks == 1, "the overflow hit doesn't knock (knock events " + this.knockbacks + ")");
    }

    private void rodHit() {
        this.reset(Items.DIAMOND_SWORD, 20);
        final FishingHook hook = new FishingHook(this.attacker, this.level, 0, 0);
        final boolean landed = this.victim.hurtServer(this.level, this.level.damageSources().thrown(hook, this.attacker), 0.0F);
        this.check(landed, "a 0-damage rod hit lands on a player");
        this.check(this.knockbacks == 1, "the rod knocks the victim");
        this.check(this.victim.getHealth() == 20.0F && this.victim.lastHurt == 0.0F, "the rod deals 0 and opens the window with lastHurt 0");
        this.check(this.victim.invulnerableTime == 20, "the rod opened the damage window");
    }

    private void swordAfterRod() {
        this.hit();
        this.check(this.victim.getHealth() == 12.0F, "the sword inside the rod's window deals its full 8 (health " + this.victim.getHealth() + ")");
        this.check(this.knockbacks == 1, "the sword inside the rod's window doesn't knock again (knock events " + this.knockbacks + ")");
    }

    private void snowballHit() {
        this.reset(Items.DIAMOND_SWORD, 20);
        final Snowball snowball = new Snowball(this.level, this.attacker, new ItemStack(Items.SNOWBALL));
        snowball.setDeltaMovement(1.5, 0.0, 0.0); // flying sideways; 1.7 knocks away from the thrower anyway
        final boolean landed = this.victim.hurtServer(this.level, this.level.damageSources().thrown(snowball, this.attacker), 0.0F);
        this.check(landed && this.knockbacks == 1, "a 0-damage snowball lands and knocks");
        this.check(near(this.victim.getDeltaMovement(), 0.0, 0.3608, 0.4), "the snowball knocks away from the thrower, not along its flight: " + this.victim.getDeltaMovement());
    }

    private void snowballPacket() {
        final List<Vec3> motions = this.motionPackets();
        // The tracker sends it at the end of the tick, after the victim's own physics tick, so check the direction only
        this.check(motions.size() == 1, "one velocity packet for the snowball knock, from the tracker (got " + motions.size() + ")");
        this.check(!motions.isEmpty() && motions.getFirst().z > 0.2 && Math.abs(motions.getFirst().x) < 0.05, "the velocity packet pushes away from the thrower: " + motions);
    }

    private void swordBlock() {
        this.reset(Items.DIAMOND_SWORD, 20);
        final ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        final DataComponentPatch clientPatch = this.encodedPatch(sword);
        final var edible = clientPatch.get(DataComponentMap.EMPTY, DataComponents.CONSUMABLE);
        this.check(edible != null && edible.animation() == net.minecraft.world.item.ItemUseAnimation.BLOCK, "clients get an edible sword with the block animation");
        this.check(!sword.has(DataComponents.CONSUMABLE) && !sword.has(DataComponents.BLOCKS_ATTACKS), "the server's sword is neither edible nor a shield");
        this.check(!this.roundTrip(sword).has(DataComponents.CONSUMABLE), "a sword a client sends back loses the decoration");

        this.victim.setItemInHand(InteractionHand.MAIN_HAND, sword);
        this.victim.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 180.0F, 0.0F));
        this.check(this.victim.isBlocking() && this.victim.getBukkitEntity().isBlocking(), "right click with a sword blocks; Bukkit isBlocking() is true");
        this.hit();
        this.check(this.victim.getHealth() == 15.5F, "a blocked 8 takes (8 + 1) x 0.5 = 4.5 (health " + this.victim.getHealth() + ")");
        final List<Vec3> motions = this.motionPackets();
        this.check(this.knockbacks == 1 && motions.size() == 1 && near(motions.getFirst(), 0.0, 0.3608, 0.4), "a blocked hit still knocks fully: " + motions);
        this.victim.stopUsingItem();
    }

    // Helpers ------------------------------------------------------------------------------------------------------

    private ServerPlayer fakePlayer(final String name, final RecordingConnection connection) {
        final GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("revival-live-" + name).getBytes()), name);
        final ServerPlayer player = new ServerPlayer(this.server, this.level, profile, ClientInformation.createDefault());
        this.server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        return player;
    }

    // Attacker at spawn facing +z (yaw 0), victim 2.5 blocks ahead, both standing, fresh counters
    private void reset(final Item weapon, final int window) {
        this.attacker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(weapon));
        this.victim.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        this.attacker.snapTo(this.spawn.getX(), this.spawn.getY(), this.spawn.getZ(), 0.0F, 0.0F);
        this.victim.snapTo(this.spawn.getX(), this.spawn.getY(), this.spawn.getZ() + 2.5, 180.0F, 0.0F);
        this.victim.setDeltaMovement(Vec3.ZERO);
        this.victim.setHealth(20.0F);
        this.victim.invulnerableDuration = window;
        this.victim.invulnerableTime = 0;
        this.victim.lastHurt = 0.0F;
        this.knockbacks = 0;
        this.velocityEvents = 0;
        this.revivalKnocks = 0;
        this.victimConnection.sent.clear();
    }

    private void hit() {
        this.attacker.connection.handleAttack(new ServerboundAttackPacket(this.victim.getId()));
    }

    private void sprint(final boolean start) {
        this.attacker.connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(this.attacker,
            start ? ServerboundPlayerCommandPacket.Action.START_SPRINTING : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
    }

    private List<Vec3> motionPackets() {
        return this.victimConnection.sent.stream()
            .filter(packet -> packet instanceof final ClientboundSetEntityMotionPacket motion && motion.id() == this.victim.getId())
            .map(packet -> ((ClientboundSetEntityMotionPacket) packet).movement())
            .toList();
    }

    private DataComponentPatch encodedPatch(final ItemStack stack) {
        final RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), this.level.registryAccess());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
        buffer.readVarInt();
        Item.STREAM_CODEC.decode(buffer);
        return DataComponentPatch.STREAM_CODEC.decode(buffer);
    }

    private ItemStack roundTrip(final ItemStack stack) {
        final RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), this.level.registryAccess());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
        return ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
    }

    private static boolean near(final Vec3 motion, final double x, final double y, final double z) {
        return Math.abs(motion.x - x) < EPSILON && Math.abs(motion.y - y) < EPSILON && Math.abs(motion.z - z) < EPSILON;
    }

    private void after(final int ticks, final String name, final Runnable action) {
        this.steps.add(new Step(ticks, name, action));
    }

    private void next() {
        final Step step = this.steps.poll();
        if (step == null) {
            this.getLogger().info("REVIVAL_LIVE_DONE " + this.nonce + " checks=" + this.checks);
            this.getServer().shutdown();
            return;
        }
        this.getServer().getScheduler().runTaskLater(this, () -> {
            try {
                step.action().run();
            } catch (final Throwable failure) {
                this.fail(step.name(), failure);
                return;
            }
            this.next();
        }, Math.max(1, step.delay()));
    }

    private void check(final boolean passed, final String detail) {
        if (!passed) {
            throw new AssertionError(detail);
        }
        this.checks++;
        this.getLogger().info("REVIVAL_LIVE_PASS " + this.nonce + " " + detail);
    }

    private void fail(final String step, final Throwable failure) {
        this.getLogger().log(Level.SEVERE, "REVIVAL_LIVE_FAIL " + this.nonce + " " + step + ": " + failure.getMessage(), failure);
        this.getServer().shutdown();
    }
}
