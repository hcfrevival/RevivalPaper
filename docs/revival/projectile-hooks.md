# Projectile hooks: combat ↔ netcode

Coordination file for `projectiles.md` (the spec). The combat side is plain source under `combat/**` and
`config/ProjectileRules.java`; it needs the patch hunks and the netcode API below, which the netcode owner applies.
Answer inline (a `> netcode:` line under an item) when something should change.

Status, 2026-10-22: combat side written, compiles, unit-tested. Every hook below is **pending**.

> netcode (2026-10-22): **applied.** H1-H9 are a new patch at the tip, `1017-Authoritative-projectiles.patch` (not 1008,
> so nothing rebases under other work), every line marked `// Revival - Projectiles` and listed in markers.txt. N1:
> `CombatWiring` implements `ProjectileViews`, installed from `Authority`'s static init; `viewBox` uses
> `CombatWiring.sharedRewindCap` (the judge's HR-2 cap, also Neue's landing); `clientBlocks` is the player's `WorldView`
> itself (it implements `ProjectileBlocks`). N3: under enforce the thrower's position at the `UseItem` replay is the
> truth after the previous client tick (the truth writer runs at each simulated tick, before the next window's
> packets), and `getKnownMovement()` is the truth's last move. N2 (teleport hold) not done.

## What needs no hook

- **Rod cast** (existing `LegacyProjectiles.cast` call in `FishingHook`'s constructor, patch 1008): now also sets 1.7's
  origin and the legacy flight's launch velocity and gravity. Live as soon as this source ships.
- **Pearl hits on players**: `LegacyProjectiles.legacyType` now includes `ThrownEnderpearl`, so the existing
  `zeroDamageHit` and `targetMovement` hooks land and knock them (1.7 CraftBukkit). Live as soon as this source ships.
- **Settings**: `ProjectileRules` is one new component of `LegacyProjectileSettings` (read by one line in its `read`).
  `LegacyCombatSettings`, `RevivalSettings` and `RevivalConfig` are untouched. No config-version bump (new keys only).
- **Neue**: `LegacyProjectiles.targetMovement` and `rodHits` (Neue's DI and parry) are untouched; this side changed
  `legacyType` (package-visible, the pearl case) and `cast`.

## Patch hunks

Extend `1008-Projectiles.patch` (same mechanic) with the hunks below, every one marked `// Revival - Projectiles`, and
add each marker line to `scripts/revival/markers.txt`. Text is against the applied sources in
`leaf-server/src/minecraft/java` as of today. All calls exit on a settings read for anything that isn't a legacy or
compensated projectile.

### H1. Throw — `Projectile.shootFromRotation`, first line

```java
    public void shootFromRotation(final Entity source, final float xRot, final float yRot, final float yOffset, final float pow, final float uncertainty) {
        if (gg.hcfrevival.revival.combat.LegacyThrow.shoot(this, source, xRot, yRot, yOffset, pow, uncertainty)) return; // Revival - Projectiles
        float xd = -Mth.sin(yRot * Mth.DEG_TO_RAD) * Mth.cos(xRot * Mth.DEG_TO_RAD);
```

Covers snowballs, eggs, pearls, splash potions (`spawnProjectileFromRotation`), bow arrows (`BowItem.shootProjectile`)
and Bukkit's `launchProjectile`. True means it set position, motion and rotation (1.7's throw, or 26.x's plus the
flight's launch velocity, with Paper's `disable-relative-projectile-velocity` honoured).
Marker: `leaf-server/src/minecraft/java/net/minecraft/world/entity/projectile/Projectile.java | if (gg.hcfrevival.revival.combat.LegacyThrow.shoot(this, source, xRot, yRot, yOffset, pow, uncertainty)) return; // Revival - Projectiles`

### H2. Flight — `ThrowableProjectile.tick`, the gravity line

```java
        this.handleFirstTickBubbleColumn();
        if (!gg.hcfrevival.revival.combat.LegacyThrow.gravity(this)) this.applyGravity(); // Revival - Projectiles
        this.applyInertia();
```

Marker: `... ThrowableProjectile.java | if (!gg.hcfrevival.revival.combat.LegacyThrow.gravity(this)) this.applyGravity(); // Revival - Projectiles`

### H3. Hits — `ThrowableProjectile.tick`, the hit result

```java
        HitResult result = gg.hcfrevival.revival.combat.ProjectileHits.onMove(this, this::canHitEntity); // Revival - Projectiles
        if (result == null) result = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity); // Revival - Projectiles
```

Replaces `HitResult result = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);`. Leaf's
weak-loaded-chunk branch below it stays as it is. Markers: both lines, in `ThrowableProjectile.java`.

### H4. Hits — `FishingHook.checkCollision`

```java
    private void checkCollision() {
        HitResult hitResult = gg.hcfrevival.revival.combat.ProjectileHits.onMove(this, this::canHitEntity); // Revival - Projectiles
        if (hitResult == null) hitResult = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity); // Revival - Projectiles
        this.preHitTargetOrDeflectSelf(hitResult);
    }
```

Markers: both lines, in `FishingHook.java`.

### H5. Hits — `AbstractArrow.findHitEntities`, before vanilla's search

```java
    protected Collection<EntityHitResult> findHitEntities(final Vec3 from, final Vec3 to) {
        final java.util.List<EntityHitResult> revivalHits = gg.hcfrevival.revival.combat.ProjectileHits.arrowHits(this, from, to, this::canHitEntity); // Revival - Projectiles
        if (revivalHits != null) return revivalHits; // Revival - Projectiles
        return ProjectileUtil.getManyEntityHitResult(
```

Markers: both lines, in `arrow/AbstractArrow.java`.

### H6. Fake-wall landings — `Projectile.hitTargetOrDeflectSelf`, the air check

```java
                BlockState collidedWith = this.level().getBlockState(blockHitResult.getBlockPos());
                if (collidedWith.isAir() && !gg.hcfrevival.revival.combat.PearlLanding.clientWall(blockHitResult)) { // Revival - Projectiles: a pearl stopped by a fake wall
                    return ProjectileDeflection.NONE;
                }
```

Without it a pearl stopped by a block only its thrower's client has would slide along the wall instead of landing.
`ClientWallHit` is only ever produced for pearls with `client-walls`. Marker: the `if` line, in `Projectile.java`.

### H7. Safe landing — `ThrownEnderpearl.onHit`

```java
            if (owner != null && isAllowedToTeleportOwner(owner, level)) {
                Vec3 teleportPos = gg.hcfrevival.revival.combat.PearlLanding.destination(this, owner, this.oldPosition()); // Revival - Projectiles
                if (teleportPos == null) { this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); return; } // Revival - Projectiles: no safe landing (refunded)
```

Replaces `Vec3 teleportPos = this.oldPosition();`. The adjusted destination is what `PlayerTeleportEvent` (cause
`ENDER_PEARL`) sees, so core's region rules judge the real landing. Markers: both lines, in
`throwableitemprojectile/ThrownEnderpearl.java`.

### H8, H9. Splash — `ThrownSplashPotion.onHitAsPotion`

```java
        AABB potionAabb = gg.hcfrevival.revival.combat.LegacySplash.box(this, hitResult, hitResult == null ? this.getBoundingBox() : this.getBoundingBox().move(hitResult.getLocation().subtract(this.position()))); // Paper - More projectile API // Revival - Projectiles
        ...
                    double dist = potionAabb.distanceToSqr(entity.getBoundingBox().inflate(margin));
                    final double scale = gg.hcfrevival.revival.combat.LegacySplash.scale(this, entity, hitResult, dist); // Revival - Projectiles: 1.7's falloff or 26.x's, 0 out of range
                    if (scale > 0.0) { // Revival - Projectiles
                        // CraftBukkit start
                        affected.put(entity.getBukkitEntity(), scale);
```

H9 replaces `if (dist < 16.0) {` and `double scale = 1.0 - Math.sqrt(dist) / 4.0; // Paper - diff on change, ...`.
26.x's scale is positive exactly when `dist < 16`, so vanilla behaviour is unchanged when the falloff is off. Markers:
the three lines, in `throwableitemprojectile/ThrownSplashPotion.java`.

## Netcode API

### N1. Install `ProjectileViews` (required for lag compensation and fake walls)

`CombatWiring` implements `gg.hcfrevival.revival.combat.ProjectileViews`, installed once where authority starts
(`ProjectileViews.install(CombatWiring.INSTANCE)`, and `install(null)` if authority is torn down):

```java
@Override
public boolean compensates(final ServerPlayer shooter) {
    final AuthPlayer auth = shooter.revivalAuth;
    return auth != null && auth.mode() == AuthMode.ENFORCE && auth.simulation().seeded();
}

@Override
public boolean viewBox(final ServerPlayer shooter, final int targetId, final double[] out) {
    final AuthPlayer auth = shooter.revivalAuth;
    return ProjectileRewind.viewBox(book(auth), targetId, System.currentTimeMillis(), rewindCap(auth), out);
}

@Override
public @Nullable ProjectileBlocks clientBlocks(final ServerPlayer player) {
    final AuthPlayer auth = player.revivalAuth;
    return auth != null && auth.mode() == AuthMode.ENFORCE ? auth.world()::blockState : null; // or cache one adapter per AuthPlayer
}
```

- `rewindCap(auth)`: please factor the cap `judge` uses (`min(max-rewind-ms, rttBase + jitter + K·50)`, or HR-2's new
  one) into one method so projectiles and melee always share it.
- `ProjectileRewind.viewBox` reads `book.mirror(targetId)` (the live state after the viewer's newest tick) and
  `book.lastTick()`; no new `MirrorBook` API is needed.
- Thread: called from the projectile's entity tick, the same thread that runs the shooter's windows (authority refuses
  parallel world ticking).
- Accessor names (`mode()`, `simulation()`, `world()`) are whatever `AuthPlayer` exposes; the WorldView must be the one
  the simulation reads (sent states, pins, predictions).

### N2. Optional (PR-8): teleports into unsent chunks

Hold a server teleport whose destination chunk `WorldView.hasChunkAt` says the client lacks until the chunk is sent (or
ping around chunk packets), as pvp-issues PR-8 proposes. Pearls almost never need it (§6 of the spec); `/tp` does.

### N3. Check: the thrower at the replay

`LegacyThrow.shoot` reads `source.getX()/getY()/getZ()` and the rotation passed in (the `UseItem` packet's). Under
authority that must be the truth after the previous client tick (PR-5 says it is). With `throw: vanilla` it also reads
`getKnownMovement()`, which must be the truth's last movement.

## API (leaf-api owner)

### A1. Optional: `RevivalPearlRefundEvent`

`PearlLanding.refund` gives the pearl back and clears the vanilla item cooldown, but HCF core runs its own pearl timer.
A plain event `RevivalPearlRefundEvent(Player player, ItemStack pearl)` fired from `refund` would let core reset it.
Not added here (outside `combat/**`); until it exists core can treat "a pearl thrown, no `ENDER_PEARL` teleport within
its flight" as a refund.
