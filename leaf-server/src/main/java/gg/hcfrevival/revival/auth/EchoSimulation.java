package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.auth.view.ClientView;
import gg.hcfrevival.revival.auth.view.WorldView;
import java.util.Set;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Stands in for the movement port until it lands: it keeps the state the netcode hands it (seeds, teleports, velocity
 * packets) so the plumbing runs end to end, does no physics, and reports every tick as
 * {@link AuthStats#EXEMPT_NO_SIMULATION}, so the netcode adopts the client's claim instead of comparing against it.
 */
public final class EchoSimulation implements Simulation {

    private boolean seeded;
    private double x;
    private double y;
    private double z;
    private double xo;
    private double yo;
    private double zo;
    private double vx;
    private double vy;
    private double vz;
    private float yRot;
    private float xRot;
    private boolean onGround;
    private boolean sprinting;
    private Pose pose = Pose.STANDING;

    @Override
    public int unsupported(final ClientView view) {
        return AuthStats.EXEMPT_NO_SIMULATION;
    }

    @Override
    public boolean seeded() {
        return this.seeded;
    }

    @Override
    public void seed(final double x, final double y, final double z, final double vx, final double vy, final double vz,
                     final float yRot, final float xRot, final boolean onGround, final ClientView view, final WorldView world) {
        this.x = this.xo = x;
        this.y = this.yo = y;
        this.z = this.zo = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.yRot = yRot;
        this.xRot = xRot;
        this.onGround = onGround;
        this.seeded = true;
    }

    @Override
    public void unseed() {
        this.seeded = false;
    }

    @Override
    public void rebase(final double x, final double y, final double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void setVelocity(final double x, final double y, final double z) {
        this.vx = x;
        this.vy = y;
        this.vz = z;
    }

    @Override
    public void addVelocity(final double x, final double y, final double z) {
        this.vx += x;
        this.vy += y;
        this.vz += z;
    }

    @Override
    public void position(final PositionMoveRotation change, final Set<Relative> relatives) {
        final PositionMoveRotation current = new PositionMoveRotation(new Vec3(this.x, this.y, this.z), new Vec3(this.vx, this.vy, this.vz), this.yRot, this.xRot);
        final PositionMoveRotation result = PositionMoveRotation.calculateAbsolute(current, change, relatives);
        final PositionMoveRotation old = PositionMoveRotation.calculateAbsolute(
            new PositionMoveRotation(new Vec3(this.xo, this.yo, this.zo), Vec3.ZERO, this.yRot, this.xRot), change, relatives);
        this.x = result.position().x;
        this.y = result.position().y;
        this.z = result.position().z;
        this.xo = old.position().x;
        this.yo = old.position().y;
        this.zo = old.position().z;
        this.vx = result.deltaMovement().x;
        this.vy = result.deltaMovement().y;
        this.vz = result.deltaMovement().z;
        this.yRot = result.yRot();
        this.xRot = result.xRot();
        this.seeded = true;
    }

    @Override
    public void rotation(final float yRot, final boolean relativeY, final float xRot, final boolean relativeX) {
        this.yRot = relativeY ? this.yRot + yRot : yRot;
        this.xRot = Math.clamp(relativeX ? this.xRot + xRot : xRot, -90.0F, 90.0F);
    }

    @Override
    public void snapTo(final double x, final double y, final double z) {
        this.x = this.xo = x;
        this.y = this.yo = y;
        this.z = this.zo = z;
    }

    @Override
    public void echoSharedFlags(final byte flags) {
        this.sprinting = (flags & 1 << 3) != 0;
    }

    @Override
    public void echoPose(final Pose pose) {
        this.pose = pose;
    }

    @Override
    public void echoUsingItem(final boolean using, final InteractionHand hand) {
    }

    @Override
    public void completeUsingItem() {
    }

    @Override
    public void attributes(final java.util.List<net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket.AttributeSnapshot> snapshots) {
    }

    @Override
    public void localGameMode(final net.minecraft.world.level.GameType mode) {
    }

    @Override
    public void respawn(final boolean keepEntityData, final boolean keepAttributeModifiers) {
        if (!keepEntityData) {
            this.vx = this.vy = this.vz = 0.0;
            this.sprinting = false;
        }
        this.seeded = false;
    }

    @Override
    public void clientAttack(final boolean targetIsPlayer, final ClientView view) {
    }

    @Override
    public void startUsingItem(final InteractionHand hand) {
    }

    @Override
    public void releaseUsingItem() {
    }

    @Override
    public void tick(final Input keys, final Input previousKeys, final float yRot, final float xRot, final int sprintCommand,
                     final int flyingCommand, final boolean fallFlyingCommand, final ClientView view, final WorldView world) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.yRot = yRot;
        this.xRot = xRot;
    }

    @Override
    public double x() {
        return this.x;
    }

    @Override
    public double y() {
        return this.y;
    }

    @Override
    public double z() {
        return this.z;
    }

    @Override
    public double xo() {
        return this.xo;
    }

    @Override
    public double yo() {
        return this.yo;
    }

    @Override
    public double zo() {
        return this.zo;
    }

    @Override
    public double vx() {
        return this.vx;
    }

    @Override
    public double vy() {
        return this.vy;
    }

    @Override
    public double vz() {
        return this.vz;
    }

    @Override
    public float yRot() {
        return this.yRot;
    }

    @Override
    public float xRot() {
        return this.xRot;
    }

    @Override
    public boolean onGround() {
        return this.onGround;
    }

    @Override
    public boolean horizontalCollision() {
        return false;
    }

    @Override
    public double fallDistance() {
        return 0.0;
    }

    @Override
    public boolean sprinting() {
        return this.sprinting;
    }

    @Override
    public float eyeHeight() {
        return this.pose == Pose.CROUCHING ? 1.27F : this.pose == Pose.SWIMMING || this.pose == Pose.FALL_FLYING ? 0.4F : 1.62F;
    }

    @Override
    public net.minecraft.world.phys.AABB box() {
        final double height = this.pose == Pose.CROUCHING ? 1.5 : this.pose == Pose.SWIMMING || this.pose == Pose.FALL_FLYING ? 0.6 : 1.8;
        return new net.minecraft.world.phys.AABB(this.x - 0.3, this.y, this.z - 0.3, this.x + 0.3, this.y + height, this.z + 0.3);
    }

    @Override
    public Pose pose() {
        return this.pose;
    }
}
