# Projectiles, splash potions and ender pearls

Status: spec and combat side written and unit-tested, 2026-10-22, branch `revival/authoritative`. The hooks the combat
side needs are listed in `projectile-hooks.md` and are pending until the netcode owner applies them (§9 says what is
live without them). Covers pvp-issues PR-1, PR-3, PR-4, PR-5, PR-6, PR-7, PR-8 and the pearl part of WD-1.

Read with: `pvp-issues.md` §6, `auth-combat.md` (MirrorBook, the rewind cap), `authoritative.md` §15.2 (WorldView),
`README.md` (`legacy-combat.projectiles`), `neue-combat.md` (DI on melee-window projectiles; untouched here).

Sources for 1.7.10 are MCP 1.7.10 (`EntityThrowable`, `EntityArrow`, `EntityFishHook`, `EntityPotion`,
`EntityEnderPearl`, `AxisAlignedBB.calculateIntercept`, `Vec3.getIntermediateWith?Value`, `MathHelper`), CraftBukkit
1.7.10 for the pearl's teleport event and 0-damage hits, and Intave's port of `calculateIntercept`
(`~/Developer/tools/prior-art/intave/.../share/BoundingBox.java`) as a cross-check. 26.3 is the decompile in
`~/Developer/tools/mc-src/src-26.3` and the applied sources in `leaf-server/src/minecraft/java`.

## 0. Decisions

| Item | Decision | Default |
|---|---|---|
| PR-1 rod, snowball and egg hits | Judged on the boxes the shooter's client drew, from its newest tick, capped like melee | on for `fishing_bobber`, `snowball`, `egg` |
| PR-1 arrows | Same mechanism, off by default: long flights and dodgeable arrows (§2.5) | off |
| PR-3 throw | 1.7's origin, heading and spread, no thrower motion | `throw: legacy-1.7` |
| PR-3 flight | 1.7's path (move, then drag and gravity) inside 26.x's tick | `flight: legacy-1.7` |
| Hit detection (new, §5) | 1.7's 0.3 margin from the first tick, its intercept, owner immune 5 ticks | `hit-detection: legacy-1.7` |
| PR-4 splash | 1.7's point distance from the pre-move position, direct hit = 1 | `splash-potion.falloff: legacy-1.7` |
| PR-7 pearls | Collide with the thrower's client blocks too; land only where a standing box fits; refund otherwise | on |
| PR-5 heal timing | No server fix for the delay; the spawn is already exact | n/a |
| PR-6 damage windows | Recommend `enabled: true` for HCFR combat worlds after one playtest; keep `false` as the 1.7 default | `false` |
| PR-8 | Not handled by the netcode yet; irrelevant for pearls at view distance ≥ 4 | n/a |

Two defaults depart from "defaults reproduce 1.7.10" on purpose: the pearl safe landing (1.7's behaviour there is the
glitch every HCF server patched) and lag compensation (only active for shooters under authority).

## 1. What 1.7.10 and 26.3 actually do

| | 1.7.10 | 26.3 |
|---|---|---|
| Throw origin (players) | feet + 1.62 (EntityPlayerMP's constant eye; `posY + 1.62D` for the rod) − 0.1, and 0.16 to the side (`posX -= cos(yaw)·0.16F`, `posZ -= sin(yaw)·0.16F`) | eye of the current pose − 0.1, centred (crouching: 1.27 − 0.1); the rod 0.3 ahead at the eye |
| Heading | look × 0.4 (EntityThrowable, rod) or × 1 (arrow), normalised with a float sqrt, + Gaussian · 0.0075F · inaccuracy per axis, × speed | normalised, + triangle(0, 0.0172275 · inaccuracy), × speed |
| Thrower's motion | never added | `shootFromRotation` adds `getKnownMovement()` (y only while airborne), unless Paper's `misc.disable-relative-projectile-velocity` |
| Speeds, gravity, pitch offset | snowball/egg/pearl 1.5, g 0.03; potion 0.5, g 0.05, −20°; exp bottle 0.7, 0.07, −20°; bow 3 × power, g 0.05; rod 1.5, g 0.04 | the same (rod: own cast, g 0.03) |
| Thrown-item tick | test the move, move by motion, then `motion *= 0.99` (0.8 in water), `motionY -= g` | gravity, then drag, then test and move (`ThrowableProjectile.tick`) |
| Rod tick | test, move, `motionY -= 0.04`, `× 0.92` | test the motion *before* gravity, gravity, move, × 0.92 |
| Arrow tick | test, move, drag, gravity | the same (water drag before the move) |
| Entity test | box + 0.3 from the first tick; `calculateIntercept` (entry or exit face); nearest, `d < best ∥ best == 0`; owner skipped while `ticksInAir < 5` | box + `max(0, min(0.3, (tickCount − 2)/20))`; `AABB.clip` (entry faces, `0 < s < 1`); owner skipped until the projectile has left its box; arrows: margin hits need a clear line to the box centre |
| Splash centre | the potion's position at the start of its impact tick (`onImpact` runs before the move) | the potion's box at the hit point |
| Splash distance | point to point: potion to the entity's feet; `1 − √d/4` under 4; 1 for the entity hit | box to box, entity box + margin |
| Pearl teleport | to the pearl's start-of-tick position (`this.posX` before the move); no fit check | `oldPosition()`: the same point; no fit check |
| Pearl hitting a player | `attackEntityFrom(thrown, 0)`: lands on CraftBukkit (knock, flash, window) | `hurt(thrown, 0)`: Paper drops 0-damage player hits |
| Client | n/a | does not predict thrown items, rods or arrows: the projectile appears when its `AddEntity` arrives (one RTT after the click) and is then simulated by the client with 26.x physics, corrected every 10 ticks (5 for the rod, 20 for arrows) |

### 1.1 Numbers that matter

Pearl landing distance, thrown from 1.52 above flat ground (`LegacyThrowTest.pearlsFly17sDistanceAnd26xFallsShort`):

| Pitch | 1.7 | 26.x | Short by |
|---|---|---|---|
| 0° | 15.31 | 13.89 | 1.43 |
| −10° | 29.91 | 27.83 | 2.08 |
| −20° | 43.09 | 41.05 | 2.04 |
| −30° | 50.91 | 49.10 | 1.81 |
| −45° | 51.67 | 50.29 | 1.39 |

26.x applies one tick of drag and gravity before every move, so each displacement is one tick behind 1.7's.

Rod hook height after each tick of a level cast (`LegacyThrowTest.legacyRodMoves17sHookPath`): 1.7 `0, −0.037, −0.108,
−0.209, −0.340, −0.497`; the rod before this change (0.04 in 26.x's order) `−0.040, −0.117, −0.228, −0.369, −0.540,
−0.737`, 0.04 lower per tick, 0.12 lower at 4 blocks.

Entity margin by tick in 26.3: ticks 1-2: 0, 3: 0.05, 4: 0.1, 5: 0.15, 6: 0.2, 7: 0.25, 8+: 0.3. A cast at 1.5 is 1.5,
2.88, 4.15, 5.32, 6.39 blocks out after ticks 1-5. So at rod range (2-4 blocks) 26.3 needs the hook within 0.30-0.35 of
a player's centre line, 1.7 within 0.6: half the target width (`ProjectileHitsTest.rodRangeHitsNeed17sMarginFromTheFirstTick`).

Self-pot, Instant Health II, thrown straight down by a player at (0, 64, 0) (`LegacyThrowTest.aStandingSelfPotBurstsHalfABlockUpOn17`, `LegacySplashTest`):

| | 1.7 | 26.3 |
|---|---|---|
| Standing still | bursts at y 64.475, 0.501 from the feet: strength 0.875, heals **7** | hits the ground under the feet, boxes touch: 1.0, heals **8** |
| Sprinting (0.28 b/t) | the thrower is 0.84 on at impact: 0.723, heals **6** | the potion inherits the sprint and lands at x 0.72: heals **8** |

Heal by distance, 1.7 (to the feet): 0 → 8, 0.5 → 7, 1 → 6, 1.5 → 5, 2 → 4, 2.5 → 3, 3 → 2, 3.5 → 1, 4 → 0.
26.3 (horizontal offset of a ground burst from a standing player, margin 0.05): 0 → 8, 0.5 → 8, 1 → 7, 1.5 → 6, 2 → 5,
2.5 → 4, 3 → 3, 3.5 → 2, 4 → 1. 26.3 is a full block more forgiving at every distance.

These derive from the 1.7 source as transcribed; a one-off check against a running 1.7.10 server (a stationary
straight-down pot heals 7) is in the proof backlog (§11).

## 2. PR-1: lag-compensated projectile hits

### 2.1 What the shooter sees

A player clicks in client tick T. The `UseItem` (or the bow release) reaches the server RTT/2 later and is replayed in
window T, so the projectile spawns at the truth's eye of that tick (exact, PR-5). Its k-th flight tick runs about k
server ticks later. The shooter's client shows nothing until the `AddEntity` arrives, one RTT after the click, and then
draws the projectile against the other players as its K2 interpolation shows them (RTT + 75-110 ms behind the server,
the melee rewind).

Four definitions of a fair hit were considered:

| | Judged against | Lead the shooter needs | Rewind on the victim | Verdict |
|---|---|---|---|---|
| A. Server time (today, and 1.7 servers) | the victims' truth when the projectile gets there | own RTT + interpolation, about 0.5 block on a strafing target at 150 ms | none | rod combos depend on ping: the PR-1 complaint |
| B. The shooter's screen at impact | the truth about 2-3 ticks old (interpolation only) | still own RTT, because the projectile left RTT/2 late | 75-110 ms | fixes the look of the impact, not the aim |
| C. The shooter's own timeline | each player as the shooter's client drew it in the tick after the projectile's | none: hits what was aimed at, as if the client had predicted the projectile | RTT + 75-110 ms, capped | **chosen for rods, snowballs, eggs** |
| D. C or B decided at the ack | the same, exactly | none | the same | rejected: every hit lands one more RTT late and the projectile has flown on |

"Replace, not union": the victim's server box is not tested as well. A union doubles the target's width along its
motion (0.2 b/t × 2.5 ticks ≈ 0.5 block) and would make every projectile easier rather than fairer.

### 2.2 Semantics

For a projectile P whose owner O is a `ServerPlayer` under authority in `enforce` with a seeded simulation, of a type
listed in `lag-compensation.projectiles`, every server tick:

1. P's move this tick is tested against blocks on the server world, as vanilla (plus §6 for pearls). The segment ends
   at the block hit.
2. Every other player Q that P's hit filter accepts (not a spectator, PvP and team rules, the owner rule of §5) is
   tested at **V(Q)**: O's mirror of Q after O's newest replayed client tick (`ProjectileRewind.viewBox`), when that
   mirror is present, pickable, exact (`RemoteMirror.exact()`: not riding, no late or unbundled packet) and
   `now − displayStamp ≤ cap(O)`, cap(O) being O's melee rewind cap (`min(max-rewind-ms, rttBase + jitter + K·50)`,
   design §15.7). Otherwise Q is tested at its server box.
3. Non-player entities are always tested at their server boxes.
4. Boxes grow by the hit detection's margin (§5) and the nearest hit wins by that rule. The hit then runs the normal
   path: events, damage window, knock from O's server position (`knockback-origin: shooter`).

Why O's newest tick and not a stored shot tick: in steady state the newest replayed tick when flight tick k runs is
T + k − 1, and the mirror after it is exactly what O's client would have drawn the projectile's k-th move against had it
simulated it (C). When O's windows run late the box is older (a longer rewind, bounded by the cap); a lag switch past
the cap falls back to server boxes. Nothing per projectile is stored.

### 2.3 Fallbacks and bounds

- O not under authority, observe mode, unseeded, or a non-player owner: vanilla server boxes.
- Q not mirrored by O (vanished, never sent), not exact, or past the cap: Q's server box.
- The cap is the melee cap, so a projectile never rewinds further than a sword hit from the same player could.
- Blocks are the server's: a projectile can't pass a real block because the shooter's view was old. Fake walls don't
  stop rods or snowballs (as on vanilla HCF servers); pearls see them (§6).

### 2.4 Integration and cost

`ProjectileHits.onMove` replaces `ProjectileUtil.getHitResultOnMoveVector` in `ThrowableProjectile.tick` and
`FishingHook.checkCollision`; `ProjectileHits.arrowHits` runs first in `AbstractArrow.findHitEntities`. Both return null
when vanilla stands. The netcode supplies `ProjectileViews` (`compensates`, `viewBox`, `clientBlocks`), implemented over
`CombatWiring.book(viewer)` and the melee cap.

Per compensated projectile per tick: one entity query on vanilla's area (non-players) plus one pass over the level's
players (filter, one hash lookup into O's `MirrorBook`, 6 doubles). With 100 players in the level that is a few
microseconds; only rods, snowballs and eggs in flight pay it.

### 2.5 Why arrows are off by default

At 20-50 blocks an arrow flies 10-25 ticks and dodging it is a skill. With C, a 200 ms archer would hit a target that
dodged on its own screen up to the cap (300 ms) earlier, and the arrow would visibly pass where the target is. A server
that wants ping-free bows adds `minecraft:arrow` to the list. Pearls and potions gain nothing from it (their direct
hits are incidental).

## 3. PR-3: throws and flight

`throw: legacy-1.7` (`LegacyThrow.shoot`, at the top of `Projectile.shootFromRotation`): for a player's throw of a
legacy type (snowball, egg, pearl, splash potion, an arrow from a legacy launcher) the projectile starts at 1.7's
origin, with 1.7's heading and Gaussian spread, and nothing of the thrower's motion. A pot thrown down while sprinting
no longer drifts forward; a pearl thrown while sprinting no longer flies further. Under authority the thrower's position
and rotation at the replay are the client's own, so the throw is exactly what 1.7 would have made of that client's tick.

`flight: legacy-1.7`: 1.7 moved first and then applied drag and gravity; 26.x applies them first. Without touching
26.x's tick, the legacy launch velocity is `(v0 + g ŷ) / d` and the gravity per tick `g / d` (`d` = 0.99 in air, 0.8 in
water, read when 26.x reads it). Then 26.x's displacement `(w − g/d)·d` equals 1.7's `v` every tick
(`LegacyThrowTest.legacyFlightMoves17sPathInThe26xTickOrder`, 200 ticks, six pitches, 1e-9). The client runs 26.x
physics on the velocity it is sent and draws the same path to within 3e-4 blocks per tick between corrections.

The rod (`LegacyProjectiles.cast`, already hooked): 1.7's origin under `throw`, and under `flight` launch
`v0 + 0.92·g ŷ` with gravity `0.92·g` in the hook's own order, which moves 1.7's hook path exactly; its entity test then
uses the move it is about to make (1.7's segment) rather than 26.x's pre-gravity motion.

Paper's `misc.disable-relative-projectile-velocity` (per world, every projectile type) remains the blunt alternative;
`throw: legacy-1.7` is per legacy type and also restores 1.7's origin and spread.

## 4. PR-4: splash falloff

`splash-potion.falloff: legacy-1.7` (`LegacySplash`): the effect box is the potion's box at its start-of-tick position
(`oldPosition()`, which under `flight: legacy-1.7` is exactly 1.7's pre-move position) grown by (4, 2, 4), and each
entity's strength is `1 − √d/4` with `d` from that position to the entity's feet, or 1 for the entity the potion hit.
The instant and duration formulas (`(int)(s·(4 << amp) + 0.5)`, `(int)(s·duration + 0.5)` if over 20) are identical in
both versions and stay vanilla's. `PotionSplashEvent` still fires with these intensities.

Core: hcfr-core's precision handling (#13, #53, #115) must be off in worlds with the legacy falloff, or the two stack.
Agree with core which side owns it; this side gives 1.7's numbers.

## 5. 1.7 hit detection (a finding beyond the audit)

`hit-detection: legacy-1.7` (`ProjectileHits`): every legacy projectile tests entity boxes grown by 0.3 from its first
tick with 1.7's `calculateIntercept` (any face crossed at t ∈ [0, 1], inclusive face bounds, axes with Δ² < 1e-7
skipped, so a segment that starts inside the grown box still hits at its exit face), keeps the nearest by
`d < best ∥ best == 0`, and can hit its owner from tick 5. 26.3 gives a rod half 1.7's target width at rod range (§1.1),
which is the measurable part of "rod hitbox" complaints. `ProjectileHitsTest` pins the intercept against hand
geometry and against `AABB.clip` on 100,000 outside-to-outside segments.

## 6. PR-7: ender pearls

`ender-pearl.enabled` makes pearls a legacy type: 1.7 throw, flight and hits, and a pearl that hits a player deals 1.7
CraftBukkit's landing 0-damage hit (knock away from the thrower).

**Flight against fake walls** (`client-walls`). Each tick the pearl's segment is also clipped against the thrower's
client blocks (`WorldView`, which keeps every block sent to the client, fake combat and claim walls included,
design §15.2), with the server's own collision walk (`PearlLanding.clip`, a copy of `BlockGetter.traverseBlocks` and
`VoxelShape.clip`). The nearer of the two block hits wins. A hit on a block only the client has is a
`PearlLanding.ClientWallHit`, which `Projectile.hitTargetOrDeflectSelf` lets through its "server block is air" early
return so the pearl lands.

**Pass-through** (`pass-through`, block ids or `#tags`): blocks the pearl flies through and the landing box may
overlap, for HCF servers that allow pearling through gates on purpose. Empty by default (open gates, string and other
collision-free blocks pass anyway).

**Safe landing** (`safe-landing`). The landing is 1.7's (and 26.3's) start-of-tick position if a standing box of the
thrower (`getDimensions(STANDING)`, 0.6 × 1.8 at scale 1) fits there in both the server world and the client blocks,
shrunk by 1e-7 as `Entity.noCollision` tests (resting on a floor or against a wall is fine). Otherwise the feet move
back along the pearl's last flight direction in `backtrack-step` (1/16) steps, up to `max-backtrack` (1.5, one tick of
pearl flight), to the first spot that fits. Every spot is on the pearl's own path, so a landing never ends past what the
pearl hit. If none fits, the teleport is dropped and, with `no-landing: refund`, the pearl goes back to the inventory
(dropped if full) with its item cooldown cleared.

Glitch spots covered by `PearlLandingTest` (1.7's landing overlaps the block; the resolved landing fits and is the first
fitting step): a one-block wall, a closed fence gate, a closed door, a bottom slab and a stair step, a connected glass
pane, a ceiling hit from below (lands 1.3125 lower), a fake glass wall only the client has (the pearl stops at it and
the landing moves 4 steps back), a 1-high crawl space (no landing: refund), and a pass-through gate.

Not covered: entity colliders (boats, shulkers) in the fit test; the world border (vanilla's clip still stops the pearl
at it); scaffolding and powder snow read with an empty collision context in the client view.

**PR-8 (checked).** The netcode does not hold a teleport into a chunk the client lacks yet (`AuthPlayer` has no such
path; the correction per far teleport stands). For pearls it doesn't arise in practice: a pearl lands at most about 52
blocks out (§1.1), inside a view distance of 4 or more, so the destination chunk is already on the client. The
landing check reads a missing client chunk as air, so it never refuses a landing for it.

## 7. PR-5: heal timing on high ping

What a server can do, and what it already does under authority:

- The potion spawns at the client's own eye and rotation for the tick it was thrown (the window replay), so the throw is
  exact. With `throw` and `flight` legacy it is also 1.7's throw and path.
- The heal reaches the thrower's screen RTT + flight (2-3 ticks for a self-pot) after the click. That delay is the
  same on any server and can't be removed on a vanilla client.

What would be wrong: fast-forwarding the potion by the window's age (half an RTT) would apply the splash before the
thrower's own later windows arrive, so the distance would use where the server thought the thrower was, not where they
were. Accuracy matters more than 50-100 ms for pots. Rejected. An optional refinement, not built: with jitter the
potion's impact tick can pair with the thrower's truth one window early or late; a time-aligned self-splash would use
the thrower's truth at their client tick T_throw + flight. It changes at most one tick of movement (0.28 block when
sprinting), worth a playtest look before building.

## 8. PR-6: split damage windows

Recommendation for HCFR combat worlds: `damage-windows.enabled: true` with the defaults (`melee-projectiles`: bobber,
snowball, egg; every channel the entity's own length), after one A/B playtest of combo length with fire aspect and
bows.

- It ends "a fire tick ate my knockback": in one window, a fire tick (1) landing just after a window ends resets it
  with `lastHurt` 1, and the next sword hit lands as a 6-damage overflow with no knock.
- Rod, snowball and egg stay in the melee window, so 1.7 rod combos (a rod opening the window, the sword landing as an
  overflow) are unchanged.
- Arrows get their own window: a bow hit no longer blocks a sword hit, and archers can't land more than one arrow per
  half-window.
- Pearl hits are in the projectile window, pearl fall damage and splash harming in "other".
- Cost: fire and poison now deal their full damage during sword combos (about 0.5 HP/s with fire aspect), which 1.7
  swallowed. If that's too much, raise `other-ticks`.

Keep `false` as the default: 1.7 had one window.

## 9. What is live, and what waits for hooks

Live with the existing hooks (patch 1008): the rod's 1.7 origin and flight (`LegacyProjectiles.cast`), pearl hits on
players landing and knocking (`zeroDamageHit`, `targetMovement` see pearls as a legacy type).

Waiting for `projectile-hooks.md`: the legacy throw for thrown items and arrows (H1), thrown-item flight (H2), hit
detection and lag compensation (H3-H5), pearl fake walls (H3, H6, N1), the safe landing (H7) and the splash falloff
(H8, H9).

## 10. revival.yml

Under `world-settings.<world>.legacy-combat.projectiles`, read by `config/ProjectileRules.java` (wired as one
component of `LegacyProjectileSettings`):

| Key | Default | What it does |
|---|---|---|
| `throw` | `legacy-1.7` | 1.7's origin, heading and spread for a player's throw, no thrower motion; `vanilla` is 26.x's |
| `flight` | `legacy-1.7` | Thrown items and the rod hook move 1.7's path; `vanilla` is 26.x's |
| `hit-detection` | `legacy-1.7` | 1.7's margin from the first tick, intercept and owner rule; `vanilla` is 26.x's |
| `hit-margin` | `0.3` | How far entity boxes grow for a legacy projectile |
| `owner-immune-ticks` | `5` | A legacy projectile can't hit its owner before this many ticks |
| `throw-eye-height`, `throw-drop`, `throw-side`, `throw-spread` | `1.62`, `0.1`, `0.16`, `0.0075` | 1.7's throw origin and spread |
| `lag-compensation.projectiles` | `[minecraft:fishing_bobber, minecraft:snowball, minecraft:egg]` | Projectiles whose player hits use the shooter's view (authority only) |
| `ender-pearl.enabled` | `true` | Pearls are a legacy type |
| `ender-pearl.client-walls` | `true` | Pearls also stop at the thrower's client blocks (fake walls) |
| `ender-pearl.safe-landing` | `true` | Land only where a standing box fits |
| `ender-pearl.max-backtrack`, `ender-pearl.backtrack-step` | `1.5`, `0.0625` | How far, and in what steps, a landing may move back |
| `ender-pearl.pass-through` | `[]` | Block ids or `#tags` pearls fly through and may land in |
| `ender-pearl.no-landing` | `refund` | `refund` gives the pearl back and clears its cooldown; `cancel` just drops the teleport |
| `splash-potion.enabled` | `true` | Splash potions are a legacy type (throw, flight, hits) |
| `splash-potion.falloff` | `legacy-1.7` | 1.7's splash strength; `vanilla` is 26.x's |

New keys only, so no config-version bump. `enabled` and `shooters: players` gate all of them.

## 11. Code and tests

| File | Role |
|---|---|
| `combat/LegacyThrow.java` | the throw and flight hooks, 1.7's table sine, heading, origin and launch math |
| `combat/ProjectileHits.java` | the per-tick block and entity test for legacy and compensated projectiles; 1.7's intercept |
| `combat/LegacySplash.java` | the splash box and strength hooks and formulas |
| `combat/PearlLanding.java` | the client-wall hit, the collision walk, the fit test, the landing search, the refund |
| `combat/ProjectileViews.java` | what the netcode supplies (installed at startup; `NONE` until then) |
| `combat/ProjectileBlocks.java` | a block source: the server world or a client's `WorldView` (`worldView::blockState`) |
| `combat/ProjectileRewind.java` | the shooter's view box from its `MirrorBook` |
| `combat/LegacyProjectiles.java` | pearls as a legacy type; the rod cast's origin and flight |
| `config/ProjectileRules.java` | the keys above |

Unit tests (`./gradlew :leaf-server:runRevivalTests`): `LegacyThrowTest` (7: table sine, headings, origin, the flight
equivalence over 200 ticks, the pearl table, the rod path, the self-pot), `LegacySplashTest` (3: both falloff tables,
harming), `ProjectileHitsTest` (8: intercept faces, exit faces, inclusive ends, the per-axis skip, nearest face, the
pick rule, rod-range margins by tick, 100,000 random segments against `AABB.clip`), `PearlLandingTest` (11: the glitch
spots above, the clip against the block's own shape clip), `ProjectileRewindTest` (3), `ProjectileRulesTest` (2),
plus the updated `LegacyProjectilesTest`.

Still to add (other harnesses):

- Live: rod and bow at 0/100/200 ms RTT against a strafing `FakeClient` (hit rate within 5 points of 0 ms; no hit with a
  rewind past the cap); the pearl suite of PR-7 on a running server (30 spots, fake glass wall, refund); a potion's spawn
  position equals the client's eye at its tick (PR-5).
- One check against a running 1.7.10 server: a stationary straight-down Health II pot heals 7, sprinting 6; a level
  pearl from standing lands 15.3 blocks out.
- Playtest: damage-window A/B (PR-6); pot accuracy before and after the legacy falloff (RedLine's `potionAccuracy`).

## 12. Open questions

- Core and precision: who owns pot precision once the legacy falloff is on (hcfr-core #13, #53, #115)?
- Arrows: does HCFR want ping-free bows (add `minecraft:arrow`)? Default off for the reason in §2.5.
- Pearl refund: core's own pearl timer is not told; core should listen for a new `RevivalPearlRefundEvent` (requested in
  `projectile-hooks.md` A1) or reset its timer when no `ENDER_PEARL` teleport follows a throw.
- Crouching throws: 1.7's server had no crouch eye; `throw-eye-height` keeps 1.62 for every pose. Confirm HCFR wants that
  rather than the client's camera height.
