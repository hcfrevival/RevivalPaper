# Neue hooks: combat ↔ netcode

Coordination file for `neue-combat.md` (the spec). The combat side is plain source under `combat/**` and
`config/CombatRules.java`; it needs a handful of patch hunks and `AuthPlayer` calls, which the netcode owner applies.
Answer inline (a `> netcode:` line under an item) when something should change.

Status, 2026-10-22: hooks applied (see the netcode note below). Outside authority (§11) the parry clock and swings come
from the server's own view, held clicks land on server positions, and the tipper reads server positions.

## What the combat side already does (no hook needed)

- **State.** One `NeueState` per player, held by `ServerPlayer.revivalHitRegistration` (the existing field), so the
  spec's `ServerPlayer.revivalNeue` hunk is **not needed**. Get it with `NeueCombat.state(player)`.
- **Rules.** `NeueCombat.rules(entity)` is the entity's world's `CombatRules` (`legacy-combat.rules`, classic by default).
- **Tipper and DI.** `LegacyKnockback.targetMovement` shapes the fresh knock (`NeueShape`), reading the distance of the
  attack being run (the judge's, or the landing's) and the victim's `stream().input()` / `tickYRot()` under authority.
  `LegacyProjectiles.targetMovement` bends melee-window projectile knocks (DI) after Punch.
- **Riposte.** `LegacyAttack.canCriticalAttack` (crit while ready) and `LegacyDefence.lastHurt` (used up on a landed
  hit, fresh or overflow).
- **Lockout.** `LegacyHitRegistration.beforeAttack` refuses a staggered player's attacks (no events, no damage), and
  charges the click cap for players outside authority.
- **Per-tick upkeep.** `LegacyHitRegistration.tick` → `NeueCombat.tick`: settles an attack that ran outside a netcode
  context, takes the stagger slow off, notices a block coming down.
- **Landing, both paths.** `NeueCombat.serverTick` (first in `AuthPlayer.onServerTick`, the connection phase) lands every
  held click: under authority it hands the packet back for replay, outside it it lands the click itself on server
  positions. The connection phase comes after every level ticked, so a click lands in the very server tick whose level
  phase opened the window, whichever player the level ticked first. (It used to land the §11 path from the attacker's own
  `ServerPlayer.tick`, which ran one tick late whenever the attacker ticked before the victim.)
- **Block start.** `LegacySwordBlocking.startBlocking` starts the parry clock on the server's use start, at
  `revivalAuth.tick()` under authority (so a replayed `UseItem` in window T records T) or the server tick otherwise.

> netcode (2026-10-22): **applied.** Hunks P, W, S and Q are `1016-Neue-combat.patch` (markers listed). `AuthPlayer`:
> `begin`/`settle` around `replay(attack)` with the judge's distance for a VALID verdict (NaN otherwise); `lockedOut`
> skips the bucket and the judge; `serverTick` runs first in `onServerTick` (before the OFF return) and its landing is
> replayed and settled; `useStarted` after `startUsingItem`, `useStopped` at the end of any window whose truth stopped
> using; `applied` for every applied logged entry (acked or forced); no `Punch` replay while locked out. The click cap is
> `auth.input.ClickBucket` (refilled once per window that runs, before its keybinds; `AuthStats.attacksOverCps`), detector
> A is `auth.input.CadenceWatch` (`authoritative.stats.click-streak-ticks` 100, `AuthInputAnomalyEvent` kind
> `click-streak`; detector B is p2). The rewind cap is shared: `CombatWiring.sharedRewindCap`, used by the judge,
> `NeueReach.authority` (its one line changed) and projectiles. `NeueLiveScenarios` is registered after
> `damageWindowScenarios()`.

## Patch hunks (new `leaf-server/minecraft-patches/features/1016-Neue-combat.patch`)

All hunks are single lines marked `// Revival - Neue combat`; add each to `scripts/revival/markers.txt`.

### P. Parry — `LivingEntity.revivalHurtServer`, right after the removed/dead check

```java
        if (this.isRemoved() || this.dead || this.getHealth() <= 0.0F) { // CraftBukkit - ...
            return false;
        }

        if (gg.hcfrevival.revival.combat.NeueCombat.parried(this, source)) return false; // Revival - Neue combat
```

Why there: after `ServerPlayer.hurtServer`'s invulnerability and team/PvP checks, before sword-block damage, armor and
the window rule, so a click into a shut window still meets a fresh block (Combo's order). Exits on two field reads for
anything that isn't a player with a fresh block up. Marker:
`leaf-server/src/minecraft/java/net/minecraft/world/entity/LivingEntity.java | if (gg.hcfrevival.revival.combat.NeueCombat.parried(this, source)) return false; // Revival - Neue combat`

### W. Held click — `revivalHurtServer`'s window rule

```java
            if (damage <= this.lastHurt) {
                gg.hcfrevival.revival.combat.NeueCombat.windowRejected(this, source); // Revival - Neue combat
                return false;
            }
```

Why: the only place that knows a legacy click met a shut window and was no harder. It only marks the attacker's
running attack (a field write); `settle` holds it. Marker:
`... LivingEntity.java | gg.hcfrevival.revival.combat.NeueCombat.windowRejected(this, source); // Revival - Neue combat`

### S. Swings — `ServerGamePacketListenerImpl.handlePunch`, right after the intercept line

```java
        if (this.player.revivalAuth.intercept(packet)) return; // Revival - Authoritative netcode: handlePunch
        gg.hcfrevival.revival.combat.NeueCombat.swung(this.player); // Revival - Neue combat
```

Why: a block raised under `parry-block-hit-seconds` after a swing never parries, and every attack click (entity, block
or air) sends a `Punch`. After the intercept it runs at the replay of window T (it reads `revivalAuth.tick()`) or, with
authority off, at once on the server clock. Attacks already count as swings in `beforeAttack`; this adds air and block
swings. Marker: `... ServerGamePacketListenerImpl.java | gg.hcfrevival.revival.combat.NeueCombat.swung(this.player); // Revival - Neue combat`

### Q. Optional — `Player.attack`, the no-damage sound

```java
                    } else if (!gg.hcfrevival.revival.combat.NeueCombat.quiet(this)) { // Revival - Neue combat
                        this.playServerSideSound(SoundEvents.PLAYER_ATTACK_NODAMAGE);
                    }
```

Replaces the `} else {` line. Without it a held click whooshes "no damage" and then lands, and a parried hit whooshes
under the parry sound. Decided: apply it (spec §15 open question 3).

### U. The block gate — `ServerPlayerGameMode.useItem`, just before its cooldown check

```java
        if (gg.hcfrevival.revival.combat.LegacySwordBlocking.refusedBlock(player, itemStack)) { player.resyncUsingItem(player); return InteractionResult.PASS; } // Revival - Neue combat
        if (player.getCooldowns().isOnCooldown(itemStack)) {
            return InteractionResult.PASS;
        }
```

`refusedBlock` is true for a legacy blocking weapon on cooldown, or any while Neue's stagger locks its holder out, and
only where the world's rules parry: in a classic world it is always false, so the use falls through to vanilla's silent
cooldown PASS and the world sends exactly the traffic it did before (AGENTS.md rule 5; the live round "classic: a sword
or a pearl on cooldown is refused without a resync" checks it). Why there: the server's one entry for every use, so it covers legacy weapons whose use never reaches
`LegacySwordBlocking.startBlocking` (spears, tridents, `BLOCKS_ATTACKS`), and it resyncs where vanilla's own cooldown
check refuses without a word: a block pressed in the parried click's own window, or before the stagger's cooldown reached
the client (real-client run N1), left the client blocking for its whole hold while the server never was. The resync
(`LivingEntity.resyncUsingItem`, which marks USE server-meant under authority) drops it at the ack, as a guard break.
`NeueCombat.useStarted` skips a refused block, so the parry clock isn't armed for it. Applied in the main tree (patch
1016, marker listed).

Not done: the reviewers' optional cooldown top-up on refusal (to stop a held right-click's retries flickering outside
authority). With the default `stagger-sword-cooldown` the client's own cooldown ends at most a tick or two before the
lockout, so a held right-click retries (every 4 ticks) at most once into it; with `stagger-sword-cooldown: false` the
owner chose no cooldown cue, which a top-up would bring back.

## `AuthPlayer` calls (plain source, netcode)

| Where | Call | Semantics |
|---|---|---|
| `runAttack`, before `replay(attack)` when `deliver` | `NeueCombat.begin(this.player, attack, distance)` | `distance` = the judge's `HitCheck.distance()` for a VALID verdict, `Double.NaN` otherwise (the tipper then measures server positions). Opens the attack's context: what hooks P/W and the landing mark. |
| `runAttack`, after that replay | `NeueCombat.settle(this.player)` | Held by the window → this click is held (replacing any); refused (lockout, per-tick cap) → the held click stays; anything else delivered (landed, parried, event-cancelled) → cleared. Not-delivered attacks (missed, over cap, over CPS, grammar) must **not** call begin/settle: the held click stays. |
| `runAttack`, before the bucket and the judge | `deliver &= !NeueCombat.lockedOut(this.player)` (or skip judging) | A staggered attack spends no token and no ray. `beforeAttack` refuses it anyway; this is the cheap early exit. |
| `onServerTick`, first, before the `mode == OFF` return | `final ServerboundAttackPacket landing = NeueCombat.serverTick(this.player); if (landing != null) { this.replay(landing); NeueCombat.settle(this.player); }` | The landing pass (§5.2): expiry, target, free, window, then reach on the attacker's mirror (`NeueReach.authority`). Returns the held packet with a buffered context already begun, or null. Skip the bucket, envelope and judge for it. Returns null at once when nothing is held, or when the click was held outside authority (the server pass lands those). |
| after `simulation.startUsingItem(hand)` | `NeueCombat.useStarted(this.player, this.view.items.hand(hand), tick)` | `T_B`: the window the simulation starts using a legacy weapon. No-op for other items. Duplicates the server-side start at the same tick harmlessly. |
| after `simulation.releaseUsingItem()` and every other simulated stop (slot change, server-meant stop) | `NeueCombat.useStopped(this.player, tick)` | `T_E`. No-op when no block is up. Without it the block's end is caught one server tick late (`NeueCombat.tick`). |
| `apply(packet, kind, tick, ...)`, for every logged entry applied (acked or forced) | `NeueCombat.applied(this.player, packet, tick)` | `T_s`: when the packet is the stagger's recoil (`ClientboundSetEntityMotionPacket`, compared by identity), the lockout runs `T_s … T_s + stagger − 1`. Until then every attack is refused (capped at stagger + 100 server ticks). |
| `runPacket`, a `ServerboundPunchPacket` | don't replay while `NeueCombat.lockedOut(this.player)` | Viewers see no swing from a staggered attacker. |
| click bucket (`ClickBucket`, netcode) | `final CombatRules rules = NeueCombat.rules(this.player); rules.capsClicks()`, `rules.clickRefill()` (0.6), `rules.clickBurst()` (2.0) | Per spec §4.3. Outside authority the combat side charges its own (`NeueState.takeClick`, lazy refill per server tick). |
| `DebugHud` (optional) | `NeueCombat.state(player)`: `tippers()`, `sours()`, `parries()`, `staggers()`, `ripostes()`, `bufferedLanded()`, `bufferExpired()`, `bufferDropped()`, `bufferOutOfReach()` | Playtest counters (§14). |

What the stagger sends (the netcode bundles it into one ack window): `ClientboundSetEntityMotionPacket` (the recoil,
through `PlayerVelocityEvent`; a cancelled event sends none and the lockout falls back to the round trip),
`ClientboundCooldownPacket`s (`addCooldown(group, stagger + 1)` on every blocking weapon's group the attacker carries,
so the client's sweep runs a full `stagger` ticks after its own decrement in the ack window; real-client run N3), the
use-flag echo if the attacker was blocking (`stopUsingItem()`), the `hcfrevival:neue_stagger` `movement_speed` modifier
(`ADD_MULTIPLIED_TOTAL`, −0.65 for Neue) with its own `ClientboundUpdateAttributesPacket` sent right after it (N2: the
attribute sync would otherwise go out at the end of the tick, after the bundle), and a private `item.shield.break`
sound. The modifier comes off when the attacker's window reaches `T_slow + stagger − round(rttBase / 50)`, `T_slow`
being the window that acked the slow's own packet (the recoil's `T_s` only if no slow packet went out; checked each
server tick).

## Notes for the netcode side

- **Landing rewind cap.** `NeueReach.authority` caps the mirror sample's age at
  `min(max-rewind-ms, rttBase + ack-jitter × 50 + path-step × 50)`, as the spec wrote it. `CombatWiring.rewindCap(...)`
  (package-private, HR-2) now computes the judge's cap differently; if the landing should share it, make it public and
  change the one line in `NeueReach.authority`.
- **Guard break inside a replay.** The stagger runs inside the attacker's attack replay, so `stopUsingItem()` there is
  not marked server-meant (`onServerWrite` returns while `replaying`). A vanilla client can't attack while using an item,
  so under authority the attacker is never blocking at that point (a block raised later in the same window is refused by
  the cooldown and resynced as meant). Outside authority it matters and works.
- **`legacy-combat.max-attacks-per-tick`** counts per server tick and would refuse a landing that shares a tick with a
  fresh click: keep it 0 in Neue worlds (written in revival.yml's header).
- **`RevivalConfig.CURRENT_VERSION` is 2** (combat-rules, legacy-combat.rules). The `authoritative.stats.click-*` keys
  of §12 are the netcode's; they need no further bump.

## Cheat suite 2 (L1, L3, L4): what changed, and two requests

Combat side, done (`combat/**`, `config/CombatRules`):

- **L1, every click is a swing.** `NeueCombat.beforeAttack` now records the swing for every attack that reaches
  `Player.attack`, including the netcode's own replays (it used to skip them, so an `Attack` without its `Punch` left no
  swing on record and the block raised after it got a full window) and attacks the lockout refuses. A held click landing
  is not a new click.
- **L4, no block and no parry while staggered.** The stagger puts its cooldown on every blocking weapon the attacker
  carries, each item type its own group (`NeueCombat.swordCooldowns`, never cutting a longer cooldown already on a group),
  so the vanilla client refuses the block and the simulation's prediction (`ClientView.predictsUse`) agrees. Anything
  that gets past that (a sword picked up during the lockout, a spear's kinetic use, a trident, `stagger-sword-cooldown:
  false`) meets hook U below, which refuses the use and resyncs the use flags, so no ghost block stays up on the client.
  `NeueCombat.useStarted` doesn't arm the parry clock for a refused block. `NeueCombat.parried` never parries for a
  parrier who is locked out.
- **L3, a real-time floor on the lockout.** Under authority the lockout lasts its windows (`T_s … T_s + stagger − 1`) and
  also until `anchor + (stagger − stagger-jitter-ticks) × the client's tick period`, on `System.nanoTime` (the clock
  `TickClock` budgets windows with, so server lag or a hitch never lengthens an honest lockout). The anchor is the earliest
  an honest ack could have come, `min(ack processed, recoil sent + ackRtt)`, so an ack window that came in late (jitter,
  a retransmit, a slow frame) costs no honest click. `ackRtt` is read when the recoil goes out, before its own ack can
  move it: `max(transactions().rttBaseNanos(), transactions().lastRttNanos())`. The baseline alone (a p10 that takes
  about 1.5 s to follow a rise) let cheat suite 2's 3x timer at 200 ms, its ping raised 1.3 s before the parry, attack
  12 server ticks after it against the honest 15. New key `combat-rules.<set>.stagger-jitter-ticks` (1 in both
  presets): a client running its ticks fast gains at most that tick plus its recoil ack's round trip above `ackRtt`.
  The accessors it reads already exist; no new one is needed.

Requests to the netcode (`AuthPlayer`, not applied):

1. **L1, attacks that never reach `Player.attack`.** First thing in `runAttack`, for every `Attack` the grammar admits
   (judged invalid, over the cap, locked out or delivered): `gg.hcfrevival.revival.combat.NeueCombat.swung(this.player);`.
   And for a `Punch` in the lockout, which isn't replayed (so the `handlePunch` hook doesn't see it), call the same before
   skipping it. Both are idempotent with the hooks for the same window.
2. **L2 (yours, from the same report).** `this.cadence.onWindow(cadenceEligible, Math.max(this.punchesThisTick,
   this.attacksThisTick))`, so a clicker that drops its `Punch` packets still reads as clicks.

## Decisions taken on the combat side

Spec questions settled with the spec's reasoning and Combo's behaviour (§15 and gaps the spec left):

- **`d = 0` is sour** (§6.2, §15): the eye inside the target's box is point-blank. Combo's 0 meant "no distance" (its
  rod); here no distance is NaN, which reads plain.
- **The no-damage sound** on held and parried clicks is suppressed (hook Q), so a held click doesn't whoosh before it
  lands and a parry has one sound.
- **Landing fairness**: connection order, as the spec's default (Combo's roster order); V's-tick landing stays p2.
- **The stagger slow ships on** in the `neue` preset (×0.35, the spec table), without a sprint block; owners can set
  `stagger-move-multiplier: 1.0`.
- **The lockout refuses every attack**, whatever the weapon (a stagger is "no attacks" in Combo), and also the click
  of a held one (a stagger drops it).
- **The riposte** needs a living target, is used up by any landed legacy hit (fresh or overflow, mobs included, as
  Combo's `Melee` does), and is kept by a click the window turns away.
- **A recoil a plugin cancels** (`PlayerVelocityEvent`) sends nothing; the lockout then runs on the round trip as
  outside authority, instead of waiting for an ack that never comes. A recoil whose ack is lost stops locking 100 server
  ticks past the stagger.
- **Swings** count from every legacy attack too (`beforeAttack`), so the block-hit rule works before hook S lands.
- **Ranges**: out-of-range values are clamped *and* reported, like every other revival.yml mistake; seconds above 60
  fall back to the preset's value.
- **Feedback keys** live in each rule set: `feedback.<tipper|sour|parry|parry-spark|stagger>-<sound|volume|pitch|
  particle|particle-count>`. A sound id the registry doesn't know plays as a resource-pack sound; particles must take no
  options (spark, crit, sweep...). The tipper's spark sits at the server eye + look × the hit's distance.
- **Outside authority** (§11) the click cap is charged in `beforeAttack` with a lazily refilled bucket in `NeueState`
  (the same sum as a per-tick refill).
- **`/revival kb debug`** lines gain ` tipper` / ` sour` for shaped hits.

## Live scenarios

`leaf-server/src/revivalLiveTest/java/gg/hcfrevival/revival/livetest/NeueLiveScenarios.java`, registered in
`RevivalLiveTests.start()`. Two honest 26.3 clients (`FakeClient` plus the real movement port as each client's model,
ticked one client tick per server tick, the victim first) duel in front of a wall that keeps the attacker in reach. The
whole set runs twice, with authority off (§11: server positions, the server clock) and under enforce (the judge's
distance, the mirror landing, the parry clock on client ticks, the lockout keyed to the recoil's ack); the enforce half is
skipped in the async-tracker run, where authority refuses to start. Then classic again.

It checks: tipper/plain/sour knocks of 0.44/0.40/0.36, a 15° DI bend from held A (relative to straight away, length and
height kept), a held click landing in the server tick whose level phase opened the window and flagged buffered, a click
made 4 or more ticks early expiring, a parry (event, no damage, window or flash, riposte ready, recoil (0, 0.22, −0.45),
an 11-tick cooldown on each sword group (10 after the client's own decrement), the slow sent in the recoil's tick, the
guard-break sound, locked out), the riposte crit (12 damage) used up, the first attack after the stagger delivered
exactly at `T_s + 10` under enforce (`S_parry + rtt + 10` outside, the round trip taken at the parry), the slow off, no
correction for the staggered client, a block-hit being a plain 4.5 block, and classic unchanged (0.40 at the edge, the
click lost, a plain block, no stagger). Under enforce a third client, 450 ms away (every clientbound packet 9 ticks
late), sees the victim sneaking across older than its rewind cap: aiming at the trailing edge of the box it shows is
INVALID MISSED (the truth at the capped age is past the ray), aiming at its leading edge (a lead) lands (auth-combat.md
§2.2).

Determinism (2026-10-22). Health is the measure, so the set runs the world peaceful with no natural regeneration and puts
both back: the async run's flat world spawned slimes that hit the parrier ("health 14, window 14"), and the sync run
inherited peaceful from `AuthLiveScenarios`, whose heal of 1 every 20 ticks ignores food ("health 5.33" after the held
click). Under enforce a tick's packets reach the client in its bundle at the tick's end, so the parry's recoil, cooldown and
sound are read a tick later, and the attacker keeps its crosshair on the victim while the recoil and the riposte's knock
lift it (a level ray from mid-air passes over the victim's head, which the judge calls a miss, not the lockout); the first
attack after the lockout lands as a 1.7 crit when it is still falling.

## Netcode, classic first (2026-10-22): where the requests stand

- **L1, the netcode's `NeueCombat.swung` calls in `runAttack`: parked.** Classic comes first and L1 only matters for the
  parry window, so `AuthPlayer` calls no `swung`. The combat side's own L1 (`beforeAttack` records every attack that
  reaches `Player.attack`) stands.
- **L2: done** in `AuthPlayer`: `cadence.onWindow(cadenceEligible, max(punchesThisTick, attacksThisTick))`, and a window
  with more attacks than punches is an `attack-without-punch` grammar count (`InputRules`, never a drop).
- **R2C-07: done** in `AuthPlayer`: `NeueCombat.applied` runs when a SELF_MOTION lands (the client applies the recoil
  then), never for a force-applied one.
- **Request (cheat suite 2 H2), `NeueLiveScenarios`:** the reference model's use call,
  `this.model.startUsingItem(use.hand())`, becomes `this.model.startUsingItem(use.hand(), this.view)`, so the model reads
  the hands the view holds after the tick's hotbar key, as `AuthPlayer` now does. The one-argument form stays with its old
  behaviour, so the file compiles either way; without the change a hotbar key and a right click in the same tick model the
  hand from before the key.
