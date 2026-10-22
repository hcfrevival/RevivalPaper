package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.LegacyProjectileSettings;
import gg.hcfrevival.revival.config.ProjectileRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What a projectile hits this tick, for legacy projectiles and lag-compensated shooters (docs/revival/projectiles.md §2,
 * §5, §6). It replaces {@code ProjectileUtil.getHitResultOnMoveVector} for thrown items and the rod hook and
 * {@code AbstractArrow.findHitEntities} for arrows, and returns null whenever vanilla's answer stands.
 * <p>
 * <b>1.7 hit detection</b> ({@code hit-detection: legacy-1.7}). 1.7.10's EntityThrowable, EntityArrow and EntityFishHook
 * grew every candidate's box by 0.3 from the first tick, tested it with {@code AxisAlignedBB.calculateIntercept} (any face
 * the segment crosses, so a segment that starts inside the box still hits it at its exit face), kept the candidate
 * nearest the segment's start ({@code d < best || best == 0}) and skipped the thrower only for its first 5 ticks in the
 * air. 26.x grows the box by {@code (tickCount - 2) / 20}, 0 for the first two ticks and 0.3 only from the eighth
 * ({@code ProjectileUtil.computeMargin}), clips only entry faces, and skips the owner until the projectile has left its
 * box. At rod range (2 to 4 blocks, one to three ticks of flight) that halves the box a rod has to touch.
 * <p>
 * <b>Lag compensation</b> ({@code lag-compensation.projectiles}). For a player's projectile under authority, each other
 * player is tested at the box the shooter's client draws it at ({@link ProjectileViews#viewBox}) instead of its server
 * box, when that box is known exactly and within the rewind cap; otherwise at its server box. Non-player entities and
 * blocks are always the server's.
 * <p>
 * <b>Pearls</b>: a pearl with {@code client-walls} also stops at the first block its thrower's client has in the way
 * (fake walls included), and {@code pass-through} blocks don't stop it ({@link PearlLanding}).
 */
public final class ProjectileHits {

    /** Everything a projectile may hit except players, which the lag-compensated search visits separately. */
    private static final Predicate<Entity> NOT_A_PLAYER = entity -> !(entity instanceof Player) && !entity.isSpectator();

    private ProjectileHits() {
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * {@code ThrowableProjectile.tick} and {@code FishingHook.checkCollision}, in place of
     * {@code ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity)}: the block or entity this tick's move
     * hits (a miss result when nothing), or null to let vanilla decide.
     */
    public static @Nullable HitResult onMove(final Projectile projectile, final Predicate<Entity> filter) {
        if (!(projectile.level() instanceof final ServerLevel level)) {
            return null;
        }
        final LegacyProjectileSettings settings = Revival.settings().legacyCombat(level.getWorld().getName()).projectiles();
        if (!settings.enabled()) {
            return null;
        }
        final ProjectileRules rules = settings.rules();
        final Entity owner = projectile.getOwner();
        final boolean legacyType = LegacyThrow.thrownLegacy(projectile, settings) && (!settings.playerShootersOnly() || owner instanceof Player);
        final boolean legacy = rules.legacyHits() && legacyType;
        final ProjectileViews views = ProjectileViews.current();
        final ServerPlayer shooter = compensatedShooter(projectile, owner, rules, views);
        ProjectileRules.Pearls pearls = null;
        ProjectileBlocks walls = null;
        if (projectile instanceof ThrownEnderpearl && legacyType) {
            pearls = rules.pearls();
            if (pearls.clientWalls() && owner instanceof final ServerPlayer thrower) {
                walls = views.clientBlocks(thrower);
            }
        }
        final boolean passThrough = pearls != null && pearls.hasPassThrough();
        // The legacy rod hook tests the move it is about to make (1.7's segment), not 26.x's motion before gravity
        final boolean rodPath = projectile instanceof FishingHook && legacyType && rules.legacyFlight() && owner instanceof Player;
        if (!legacy && shooter == null && walls == null && !passThrough && !rodPath) {
            return null;
        }

        Vec3 delta = projectile.getDeltaMovement();
        if (rodPath && !projectile.onGround()) {
            delta = delta.add(0.0, -projectile.getGravity(), 0.0);
        }
        final Vec3 from = projectile.position();
        final Vec3 to = from.add(delta);
        BlockHitResult block = passThrough
            ? PearlLanding.clipServer(level, projectile, from, to, pearls)
            : level.clipIncludingBorder(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile));
        if (walls != null) {
            final Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
            final BlockHitResult wall = PearlLanding.clip(walls, from, end, pearls);
            if (wall != null && (block.getType() == HitResult.Type.MISS || from.distanceToSqr(wall.getLocation()) < from.distanceToSqr(block.getLocation()))) {
                block = new PearlLanding.ClientWallHit(wall.getLocation(), wall.getDirection(), wall.getBlockPos(), wall.isInside());
            }
        }
        final Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        final Search search = new Search(projectile, owner, filter, legacy, legacy ? rules.hitMargin() : ProjectileUtil.computeMargin(projectile),
            rules.ownerImmuneTicks(), from, end);
        search.run(level, projectile.getBoundingBox().expandTowards(delta).inflate(1.0), shooter, views);
        final EntityHitResult entity = search.result();
        return entity != null ? entity : block;
    }

    /**
     * {@code AbstractArrow.findHitEntities(from, to)}, before vanilla's search: the entities this step hits, or null to let
     * vanilla decide. A legacy arrow hits the one nearest entity (1.7 had no piercing); a lag-compensated vanilla arrow
     * keeps 26.x's rules (exact box first, then the margin box with a clear line to the box's centre), on the shooter's
     * boxes.
     */
    public static @Nullable List<EntityHitResult> arrowHits(final AbstractArrow arrow, final Vec3 from, final Vec3 to, final Predicate<Entity> filter) {
        if (!(arrow.level() instanceof final ServerLevel level)) {
            return null;
        }
        final LegacyProjectileSettings settings = Revival.settings().legacyCombat(level.getWorld().getName()).projectiles();
        if (!settings.enabled()) {
            return null;
        }
        final ProjectileRules rules = settings.rules();
        final Entity owner = arrow.getOwner();
        final boolean legacy = rules.legacyHits() && LegacyProjectiles.legacyType(arrow, settings) && (!settings.playerShootersOnly() || owner instanceof Player);
        final ProjectileViews views = ProjectileViews.current();
        final ServerPlayer shooter = compensatedShooter(arrow, owner, rules, views);
        if (!legacy && shooter == null) {
            return null;
        }
        final AABB area = arrow.getBoundingBox().expandTowards(arrow.getDeltaMovement()).inflate(1.0);
        if (legacy) {
            final Search search = new Search(arrow, owner, filter, true, rules.hitMargin(), rules.ownerImmuneTicks(), from, to);
            search.run(level, area, shooter, views);
            final EntityHitResult hit = search.result();
            return hit == null ? List.of() : List.of(hit);
        }
        return manyVanilla(level, arrow, from, to, area, filter, shooter, views);
    }

    // The shooter when this projectile type is lag-compensated and the shooter's client view can be asked, else null
    private static @Nullable ServerPlayer compensatedShooter(final Projectile projectile, final @Nullable Entity owner, final ProjectileRules rules,
                                                             final ProjectileViews views) {
        if (!(owner instanceof final ServerPlayer shooter) || rules.lagCompensated().isEmpty() || views == ProjectileViews.NONE) {
            return null;
        }
        if (!rules.lagCompensated().contains(BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()))) {
            return null;
        }
        return views.compensates(shooter) ? shooter : null;
    }

    // ProjectileUtil.getManyEntityHitResult(level, arrow, from, to, area, filter, false, false), with the shooter's boxes
    private static List<EntityHitResult> manyVanilla(final ServerLevel level, final AbstractArrow arrow, final Vec3 from, final Vec3 to, final AABB area,
                                                     final Predicate<Entity> filter, final ServerPlayer shooter, final ProjectileViews views) {
        final float margin = ProjectileUtil.computeMargin(arrow);
        final List<EntityHitResult> hits = new ArrayList<>();
        for (final Entity entity : level.getEntities(arrow, area, NOT_A_PLAYER)) {
            if (filter.test(entity)) {
                manyVanillaOne(level, arrow, entity, entity.getBoundingBox(), from, to, margin, hits);
            }
        }
        final double[] box = new double[6];
        for (final ServerPlayer player : level.players()) {
            if (player.isSpectator() || !filter.test(player)) {
                continue;
            }
            if (views.viewBox(shooter, player.getId(), box)) {
                manyVanillaOne(level, arrow, player, new AABB(box[0], box[1], box[2], box[3], box[4], box[5]), from, to, margin, hits);
            } else if (player.getBoundingBox().intersects(area)) {
                manyVanillaOne(level, arrow, player, player.getBoundingBox(), from, to, margin, hits);
            }
        }
        return hits;
    }

    private static void manyVanillaOne(final ServerLevel level, final AbstractArrow arrow, final Entity entity, final AABB box, final Vec3 from, final Vec3 to,
                                       final float margin, final List<EntityHitResult> hits) {
        final Optional<Vec3> exact = box.clip(from, to);
        if (exact.isPresent()) {
            hits.add(new EntityHitResult(entity, exact.get()));
            return;
        }
        if (margin > 0.0F) {
            final Optional<Vec3> outside = box.inflate(margin).clip(from, to);
            if (outside.isPresent()) {
                Vec3 towards = box.getCenter();
                final BlockHitResult sight = level.clipIncludingBorder(new ClipContext(outside.get(), towards, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, arrow));
                if (sight.getType() != HitResult.Type.MISS) {
                    towards = sight.getLocation();
                }
                if (box.clip(outside.get(), towards).isPresent()) {
                    hits.add(new EntityHitResult(entity, outside.get()));
                }
            }
        }
    }

    /** One tick's entity search: the candidates, each at its server box or the shooter's view of it, and the pick. */
    private static final class Search {
        private final Projectile projectile;
        private final @Nullable Entity owner;
        private final Predicate<Entity> filter;
        private final boolean legacy;
        private final double margin;
        private final int ownerImmuneTicks;
        private final double fromX;
        private final double fromY;
        private final double fromZ;
        private final double toX;
        private final double toY;
        private final double toZ;
        private final double[] point = new double[3];
        private @Nullable Entity best;
        private double bestDistance;
        private double bestX;
        private double bestY;
        private double bestZ;

        Search(final Projectile projectile, final @Nullable Entity owner, final Predicate<Entity> filter, final boolean legacy, final double margin,
               final int ownerImmuneTicks, final Vec3 from, final Vec3 to) {
            this.projectile = projectile;
            this.owner = owner;
            this.filter = filter;
            this.legacy = legacy;
            this.margin = margin;
            this.ownerImmuneTicks = ownerImmuneTicks;
            this.fromX = from.x;
            this.fromY = from.y;
            this.fromZ = from.z;
            this.toX = to.x;
            this.toY = to.y;
            this.toZ = to.z;
            this.bestDistance = legacy ? 0.0 : Double.MAX_VALUE;
        }

        void run(final ServerLevel level, final AABB area, final @Nullable ServerPlayer shooter, final ProjectileViews views) {
            if (shooter == null) {
                for (final Entity entity : level.getEntities(this.projectile, area, EntitySelector.NO_SPECTATORS)) {
                    if (this.hittable(entity)) {
                        this.consider(entity, entity.getBoundingBox());
                    }
                }
                return;
            }
            // Players are visited on their own: the box the shooter draws can be far from the server's
            for (final Entity entity : level.getEntities(this.projectile, area, NOT_A_PLAYER)) {
                if (this.hittable(entity)) {
                    this.consider(entity, entity.getBoundingBox());
                }
            }
            final double[] box = new double[6];
            for (final ServerPlayer player : level.players()) {
                if (player.isSpectator() || !this.hittable(player)) {
                    continue;
                }
                if (views.viewBox(shooter, player.getId(), box)) {
                    this.consider(player, box[0], box[1], box[2], box[3], box[4], box[5]);
                } else if (player.getBoundingBox().intersects(area)) {
                    this.consider(player, player.getBoundingBox());
                }
            }
        }

        // 1.7: the owner (and whatever rides with it) only after its first ticks in the air; 26.x: the projectile's own filter
        private boolean hittable(final Entity entity) {
            final Entity owner = this.owner;
            if (this.legacy && owner != null && (entity == owner || owner.isPassengerOfSameVehicle(entity))) {
                return entity.canBeHitByProjectile() && this.projectile.tickCount >= this.ownerImmuneTicks;
            }
            return this.filter.test(entity);
        }

        private void consider(final Entity entity, final AABB box) {
            this.consider(entity, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }

        private void consider(final Entity entity, final double minX, final double minY, final double minZ, final double maxX, final double maxY,
                              final double maxZ) {
            final double m = this.margin;
            if (this.legacy) {
                final double distance = intercept(minX - m, minY - m, minZ - m, maxX + m, maxY + m, maxZ + m, this.fromX, this.fromY, this.fromZ, this.toX,
                    this.toY, this.toZ, this.point);
                if (distance >= 0.0 && nearer17(distance, this.bestDistance)) {
                    this.take(entity, distance);
                }
                return;
            }
            final Optional<Vec3> location = new AABB(minX - m, minY - m, minZ - m, maxX + m, maxY + m, maxZ + m)
                .clip(new Vec3(this.fromX, this.fromY, this.fromZ), new Vec3(this.toX, this.toY, this.toZ));
            if (location.isPresent()) {
                final Vec3 at = location.get();
                final double dx = at.x - this.fromX;
                final double dy = at.y - this.fromY;
                final double dz = at.z - this.fromZ;
                final double distance = dx * dx + dy * dy + dz * dz;
                if (distance < this.bestDistance) {
                    this.point[0] = at.x;
                    this.point[1] = at.y;
                    this.point[2] = at.z;
                    this.take(entity, distance);
                }
            }
        }

        private void take(final Entity entity, final double distance) {
            this.best = entity;
            this.bestDistance = distance;
            this.bestX = this.point[0];
            this.bestY = this.point[1];
            this.bestZ = this.point[2];
        }

        @Nullable EntityHitResult result() {
            return this.best == null ? null : new EntityHitResult(this.best, new Vec3(this.bestX, this.bestY, this.bestZ));
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // 1.7.10 math
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * 1.7.10's pick among candidates ({@code if (d1 < d0 || d0 == 0.0D)}, d0 starting at 0): the first hit is always taken,
     * a nearer one replaces it, and a hit at distance exactly 0 is replaced by any later one.
     */
    static boolean nearer17(final double distance, final double best) {
        return distance < best || best == 0.0;
    }

    /**
     * 1.7.10 {@code AxisAlignedBB.calculateIntercept(from, to)}: of the six face planes, each crossed by the segment at
     * {@code t ∈ [0, 1]} ({@code Vec3.getIntermediateWith?Value}, which skips an axis whose delta squared is under
     * {@code 1.0000000116860974E-7}) at a point inside that face (bounds inclusive), the one nearest {@code from}; ties go
     * to the earlier face in the order minX, maxX, minY, maxY, minZ, maxZ. Writes the point to {@code point[0..2]} and
     * returns its squared distance from {@code from}, or -1 when the segment crosses no face. Unlike 26.x's
     * {@code AABB.clip}, exit faces count, so a segment starting inside the box hits it.
     */
    static double intercept(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ,
                            final double fromX, final double fromY, final double fromZ, final double toX, final double toY, final double toZ,
                            final double[] point) {
        final double dx = toX - fromX;
        final double dy = toY - fromY;
        final double dz = toZ - fromZ;
        double best = -1.0;
        double bx = 0.0;
        double by = 0.0;
        double bz = 0.0;
        for (int face = 0; face < 6; face++) {
            final int axis = face >> 1;
            final double delta = axis == 0 ? dx : axis == 1 ? dy : dz;
            if (delta * delta < 1.0000000116860974E-7) {
                continue;
            }
            final double plane = switch (face) {
                case 0 -> minX;
                case 1 -> maxX;
                case 2 -> minY;
                case 3 -> maxY;
                case 4 -> minZ;
                default -> maxZ;
            };
            final double start = axis == 0 ? fromX : axis == 1 ? fromY : fromZ;
            final double t = (plane - start) / delta;
            if (!(t >= 0.0 && t <= 1.0)) {
                continue;
            }
            final double x = fromX + dx * t;
            final double y = fromY + dy * t;
            final double z = fromZ + dz * t;
            final boolean inside = switch (axis) {
                case 0 -> y >= minY && y <= maxY && z >= minZ && z <= maxZ;
                case 1 -> x >= minX && x <= maxX && z >= minZ && z <= maxZ;
                default -> x >= minX && x <= maxX && y >= minY && y <= maxY;
            };
            if (!inside) {
                continue;
            }
            final double ex = x - fromX;
            final double ey = y - fromY;
            final double ez = z - fromZ;
            final double distance = ex * ex + ey * ey + ez * ez;
            if (best < 0.0 || distance < best) {
                best = distance;
                bx = x;
                by = y;
                bz = z;
            }
        }
        if (best >= 0.0) {
            point[0] = bx;
            point[1] = by;
            point[2] = bz;
        }
        return best;
    }
}
