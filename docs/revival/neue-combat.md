# Neue combat (`legacy-combat.rules: neue`)

Status: design, 2026-10-21. Combat side implemented (`config/CombatRules`, `combat/Neue*`); the hooks and netcode calls
it needs, and the questions it settled, are in `neue-hooks.md`. Branch `revival/authoritative`; `authoritative.md` §15 is
binding and this spec assumes `authoritative.mode: enforce` (§11 covers players outside authority).

Neue is the rule set of Combo, the HCF team's standalone 1.7-PvP game (`~/Developer/GitHub/combo`,
`src/Combo.Core/Combat/Rules.cs`). Classic is plain 1.7. Neue adds five things to the fight:

- a click buffer that takes away the CPS race;
- a click cap of 12 CPS;
- DI, where the victim's held direction bends the knock up to 15°;
- the tipper: a hit from the edge of reach knocks 10% further, and a point-blank hit knocks 10% less;
- a parry, with stagger and riposte.

This document turns each one into exact semantics for a **vanilla 26.3 client** on the authoritative server: formulas,
constants, timing tick by tick, hook points, tests and risks.

Out, as the user asked: Combo's moves. That means the slide, the roll and its i-frames (`HitKind.Dodged`), the
grapple, wall runs, wall jumps and wall climbs, the mantle, the jump buffer and coyote time. Also out: Neue's body
hitboxes (§9: a vanilla client cannot use them).

Sources read:

- Combo:
  - `Combat/{Rules,Combat,Knockback,Fighter,Hitbox,Inventory,Projectile}.cs`
  - `Sim/PlayerSim.cs` (`Mouse`, `SpendClick`, `ParryWindow`, `Parries`, `Stagger`)
  - `Combo.Server/GameServer.cs` (`Step`, `Claim`, `Buffered`, `Strike`, `HookPlayers`)
  - tests `RulesTests`, `CombatTests` (`ParryAndDodgeTests`), `MoveTests` (parry), `CollisionTests` (the even clicker)
  - `docs/DESIGN.md`
  - commits `f6ac0a9` (the two rule sets), `e94f163` (parry, stagger), `71d02f0`, `73c1d37`
  - the memory note: the parry rules were decided 2026-10-19, after block-hits had parried 13% of hits by accident.
- RevivalPaper:
  - `combat/*`
  - `config/{LegacyCombatSettings,KnockbackProfile,SwordBlockingSettings,DamageWindowSettings,AuthoritativeSettings}`
  - `auth/{AuthPlayer,CombatWiring,AttackJudge}`, `auth/combat/{HitValidator,HitCheck}`, `auth/input/*`
  - the applied `Player.attack`, `LivingEntity.revivalHurtServer` and `ServerPlayer.tick`/`hurtServer`
  - `auth-combat.md`, `auth-netcode.md`, `auth-cheat-suite.md`
  - report 02 §2.2-2.4 (client clicks)
  - the 26.3 client source in `~/Developer/tools/mc-src/src-26.3`.

## 0. Summary

| Mechanic | Combo Neue | RevivalPaper neue (vanilla 26.3 client) | Priority |
|---|---|---|---|
| Click cap | 12 CPS token bucket, burst 2, per 64 Hz tick, every click charged | 0.6 tokens per **client tick** (window), burst 2.0, **attack packets** charged, one attack per client tick (envelope) | p0 |
| Click buffer | a click rejected by the damage window waits ≤ 0.15 s, lands on the first open tick if in reach and free | the same, 3 server ticks; landing pass in the attacker's `onServerTick`; reach re-checked against the attacker's mirror | p0 |
| Tipper / sour | ×1.1 horizontal from ≥ 2.6 (eye to hitbox), ×0.9 under 1.4 | the same constants, distance = `HitCheck.distance()` (exact, lag-compensated); in `LegacyKnockback.targetMovement` | p0 |
| DI | ≤ 15° × sine of the angle between the knock and the held direction, horizontal only | the same math, keys + yaw of the victim's latest simulated tick | p0 |
| Parry | fresh block, 0.15 s window, block-hits never parry, rest scaling, once per block | the same in ticks (3 / 6 / 7 / 20), on the edible-sword block, hook at the top of `revivalHurtServer` | p1 |
| Stagger | recoil, 0.5 s no attacks or blocks, move ×0.35 | recoil velocity + sword item cooldown (vanilla sweep, blocks the block client-side) + server attack refusal + guard break; the slow is optional | p1 |
| Riposte | next hit in 0.6 s is a crit | the same (12 ticks), through `LegacyAttack.canCriticalAttack` | p1 |
| Body hitboxes | model parts | **not feasible**: the client picks the 0.6 × 1.8 box (§9) | no |
| Feedback | spark, sound and call-out per tipper; ring per parry | vanilla sounds and particles only (§10) | p1 |
| Autoclickers | cap + buffer | the same + a locked-cadence detector (§12) | p1 / p2 |

## 1. Translating Combo

- **Ticks.** Combo runs at 64 TPS and writes its rules in seconds or 1.7 units. RevivalPaper runs at 20 TPS, and
  every Neue duration is an exact multiple of 50 ms: 0.15 s = 3 ticks, 0.3 = 6, 0.35 = 7, 0.5 = 10, 0.6 = 12,
  1.0 = 20. Motion in 1.7 units (blocks per 1.7 tick) is the 26.3 velocity unit, so the recoil numbers carry over
  as they are.
- **Who predicts what.** A Combo server owns the client code, so its client predicts the stagger and the parry. A
  vanilla client predicts only vanilla things. A lockout on a vanilla client is therefore a server refusal, plus a
  vanilla cue the client already draws and enforces itself, the item cooldown (§8.4).
- **Lag compensation.** Combo rewinds recorded poses. RevivalPaper replays the attacker's client exactly
  (`MirrorBook`, `PickRay`). Every distance here is taken from that replay, never from server positions, except
  in §11.
- **1.7 reach in 26.3.** 1.7 picked a box grown by 0.1 (`getCollisionBorderSize`). 26.3 picks the bare 0.6 × 1.8
  box (`getPickRadius() = 0` for players) with entity range 3.0, using a strict `<`. Combo's numbers (2.6 and 1.4
  of a 3.0 reach) keep their meaning when measured to the 26.3 box.

## 2. revival.yml: rule sets

Rule sets work like knockback profiles. There are named built-in presets, `classic` and `neue`. A global
`combat-rules` section can override a preset's keys or add new sets, and each world picks one.

```yaml
combat-rules:                 # global; each set starts from the preset of its name, or from classic
  neue: {}                    # e.g. { max-cps: 12.0, tipper-knock: 1.1 }
world-settings:
  default:
    legacy-combat:
      rules: classic          # classic | neue | a set under combat-rules
```

| Key | Combo constant | classic | neue | In ticks (×20) |
|---|---|---|---|---|
| `max-cps` | `Rules.MaxCps` | 0 (off) | 12.0 | 0.6 tokens per client tick |
| `click-burst` | `PlayerBody.ClickBudget` cap (`PlayerSim.Mouse`) | 2.0 | 2.0 | n/a |
| `click-buffer-seconds` | `Rules.ClickBufferSeconds` | 0 | 0.15 | 3 server ticks |
| `click-buffer-line-of-sight` | (not in Combo) | true | true | n/a |
| `influence-degrees` | `Rules.InfluenceDegrees` | 0 | 15.0 | n/a |
| `influence-projectiles` | DI in `Combat.Rod` | false | true | n/a |
| `tipper-from` | `Combat.TipperFrom` | 2.6 | 2.6 | blocks, eye to box |
| `tipper-knock` | `Rules.TipperKnock` | 1.0 | 1.1 | n/a |
| `sour-under` | `Combat.SourUnder` | 1.4 | 1.4 | blocks |
| `sour-knock` | `Rules.SourKnock` | 1.0 | 0.9 | n/a |
| `parry` | `Rules.Parry` | false | true | n/a |
| `parry-window-seconds` | `PlayerSim.ParryWindowSeconds` | 0.15 | 0.15 | 3 |
| `parry-block-hit-seconds` | `PlayerSim.BlockHitSeconds` | 0.3 | 0.3 | 6 |
| `parry-rearm-seconds` | `PlayerSim.ParryRearmSeconds` | 0.35 | 0.35 | 7 |
| `parry-rest-seconds` | `PlayerSim.ParryRestSeconds` | 1.0 | 1.0 | 20 |
| `parry-projectiles` | the parry in `Combat.Rod` | true | true | n/a |
| `parry-jitter-ticks` | (not in Combo; §8.2) | 2 | 2 | 2 |
| `riposte-seconds` | `Combat.RiposteSeconds` | 0.6 | 0.6 | 12 |
| `stagger-seconds` | `PlayerSim.StaggerSeconds` | 0.5 | 0.5 | 10 |
| `stagger-recoil-horizontal` | `Combat.RecoilHorizontal` | 0.45 | 0.45 | blocks/tick |
| `stagger-recoil-vertical` | `Combat.RecoilVertical` | 0.22 | 0.22 | blocks/tick |
| `stagger-move-multiplier` | `PlayerSim.StaggerSlow` | 1.0 | 0.35 | n/a (p2, §8.4) |
| `stagger-sword-cooldown` | (vanilla cue) | true | true | n/a |
| `stagger-lowers-block` | `PlayerSim.Stagger` → `LowerBlock` | true | true | n/a |
| `feedback.*` | the hit feel | §10 | §10 | n/a |

How the loader reads these keys:

- **Seconds become ticks.** `ticks = seconds <= 0 ? 0 : max(1, round(seconds × 20))`. This is
  `TickRate.FromSeconds` at 20 Hz. A value that is not a multiple of 0.05 is reported once, then used rounded.
- **Ranges are clamped:**
  - `max-cps` 0..20, where 0 means off; at 20 or more only the per-tick envelope limits.
  - `click-burst` 1..4.
  - degrees 0..45.
  - knock factors 0..2.
  - `sour-under` ≤ `tipper-from` ≤ 3.0.
- **Parry needs the global `sword-blocking.enabled`.** That section is global, so the parry stays inert in a neue
  world when sword blocking is off.
- **Keep `legacy-combat.max-attacks-per-tick` at 0 in neue worlds.** It counts per **server** tick, so it would
  drop a buffered landing that shares a server tick with a fresh click, or banked windows. The client-tick
  envelope and the bucket replace it.
- **No migration.** The keys are new and default to classic. Bump `RevivalConfig.CURRENT_VERSION`, so
  `/revival reload` writes them back as missing keys.
- **Plain source.** `config/CombatRules.java` holds a record with every key, `PRESETS` and `readAll`, modelled on
  `KnockbackProfile`. `LegacyCombatSettings` gains `rulesName` and `CombatRules rules`, and `RevivalSettings`
  gains `combatRules`.

## 3. Timing model

Three clocks matter: the server tick, each player's client tick (window `W_T`), and the victim's damage window.

**One server tick `N`** (`MinecraftServer.tickServer`, Paper's improved tick loop):

1. **Packet phase.** `run_all_packets`, and the same between ticks while the loop waits. When a `TickEnd` arrives
   its window runs (`AuthPlayer.runWindow`): `Attack` packets are judged and replayed into `Player.attack` here.
2. **Level phase.** Entities tick. `ServerPlayer.tick` does `damageCooldownTime--` (`ServerPlayer.java:758`). **A
   victim's melee window opens in this phase**, and in no other.
3. **Connection phase.** `tickConnection` → `SGPLI.tick` → `AuthPlayer.onServerTick` → queued windows
   (`runReady`).

**Victim `V`'s window** is open when `!(V.damageCooldownTime > V.invulnerableDuration / 2f)`, which is 1.7's
`hurtResistantTime > maxHurtResistantTime / 2` with the default 20. A fresh hit sets 20. Ten decrements later the
window is open again. A hit processed in the packet phase of `S` opens in the level phase of `S+9`; one processed in
the connection phase opens in `S+10`.

- With split damage windows on (`DamageWindows`), the fields hold the melee window everywhere outside `hurtServer`.
- `revivalHurtServer` rejects a hit in a shut window when `damage <= lastHurt`, where `lastHurt` is the raw damage
  of 1.7, as `LegacyDefence.lastHurt` keeps it. A harder hit lands as an overflow for the difference.

**"The first tick `V` can be hit"** is the first server tick whose level phase leaves the window open. The buffer
lands its click in that tick's connection phase (§5).

## 4. Click cap

### 4.1 Combo

`PlayerSim.Mouse` runs every 64 Hz tick and does `budget = min(2.0, budget + MaxCps / Hz)`. A tick with the attack
button spends 1.0 if `budget ≥ 1 − 1e-9`. Otherwise that click does nothing: no swing, no claim, and its air clicks
are charged too.

The cap is 2.0 rather than 1.0 for a reason given in the code: "capped at one, each refill's overshoot was lost and
the 12 a second Neue allows came to about 8". The test `AnEvenClickerUpToTheCapSwingsEveryClick` checks that an even
clicker at 0.75 × cap and at the cap swings `cps ± 0.2` per second.

### 4.2 How a 26.3 client batches clicks (report 02 §2.2-2.4, `Minecraft.startAttack`)

- Clicks are consumed per client tick in `handleKeybinds`: `while (keyAttack.consumeClick()) startAttack()`. Every
  click in one tick uses the same `hitResult`, picked once at the start of the tick.
- A click on an entity sends `Attack(id)` and then `Punch`. A click on air or a block sends `Punch` alone. Two
  clicks in one tick send two `Attack`+`Punch` pairs.
- While an item is in use (an edible-sword block, eating) every click of the tick is discarded, release tick
  included. While a screen is open, clicks are discarded, and the first click after it closes is dropped
  (`missTime = 10000`).
- A whiff sets `missTime = 10`, but `continueAttack(false)` clears it at the end of any tick in which the button is
  up. It only blocks while the button stays held.
- Holding attack on an entity never attacks again. Holding it on a block sends a `Punch` **every tick** while
  mining. That is why the bucket never charges `Punch` (4.3).
- The server cannot stop a vanilla client from swinging. It only decides what lands.

### 4.3 RevivalPaper semantics

Each player has a `ClickBucket` with `tokens` (double), starting at `click-burst`.

1. **Refill once per window that runs** (not one that is refused or exempt), before its keybinds:
   `tokens = min(burst, tokens + max-cps / 20)`.

   The refill is per client tick rather than per server tick because the tick clock already bounds windows to real
   time. A burst of banked windows (up to `max-banked-ticks`) refills at most to the cap, so nobody can bank clicks.
2. **Charge each `Attack` packet** that passed the input grammar (`refused` false) and the envelope
   (`max-attacks-per-client-tick`, default 1). If `tokens ≥ 1 − 1e-9`: `tokens −= 1`, then judge as today.
   Otherwise the attack is not delivered and not judged (this saves the ray), is counted as `attacksOverCps`, and
   leaves the buffer (§5) untouched.
3. **Clicks that send only a `Punch`** (air, blocks) are not charged. Combo charges them, but on 26.3 a held mining
   button sends a `Punch` every tick and would drain the bucket. Under the cap nobody's hits differ.

**What the cap allows:**

- 12 attacks a second, sustained.
- At most one per client tick, because of the envelope.
- At most `min(k, floor(2 + 0.6 (k − 1)))` in any `k` consecutive ticks: 2 in 2, 3 in 3, 3 in 4, 7 in 10 and 13 in
  20.
- An even 12 CPS clicker lands every click (the intervals run 2, 2, 1, …). A 20 CPS clicker lands 12.

**Why `max-attacks-per-client-tick` stays 1:**

- A second attack in the same tick picks the same `hitResult` and meets a window the first one has just shut.
- Under neue it would only re-arm the buffer.
- The envelope drops it **before** it is charged.

**Hooks** (netcode, `AuthPlayer`):

- `runWindow`: refill next to `attacksThisTick = 0`.
- `runAttack`: after `envelope.admitAttack(...)` and before `Authority.attackJudge().judge(...)`, add
  `paced = !refused && admitted && bucket.take()`. Then `deliver &= paced`.
- `AuthStats` gains `attacksOverCps`.

**Tests:**

- `ClickBucketTest` (pure). Even clickers at 9 and 12 CPS over 2,000 ticks lose 0 clicks. Twenty a second gives
  12.0 ± 0.2 delivered a second (the Combo test, ported to 20 Hz). After 2 s idle, 3 consecutive ticks are all
  admitted and the 4th is refused. Refused windows do not refill. A refused or over-envelope attack spends nothing.
  `max-cps: 0` admits everything.
- Live (`AuthLiveScenarios`): a `FakeClient` sends one `Attack` per tick for 40 ticks at a target with a damage
  window of 0 (`invulnerableDuration` 0). `PrePlayerAttackEntityEvent` fires 24 or 25 times.

**Risks:**

- An honest jitter clicker above 12 CPS loses clicks past 12 a second. Those clicks mostly fall in damage windows
  where they would be rejected anyway, and the buffer covers the window opening for any rate of 7 CPS or more (§5).
- The client still swings and plays its own swing for refused clicks, with no hit sound. That is the same as a
  click into a damage window today.

## 5. Click buffer

### 5.1 Combo

`GameServer.Claim`: a VALID click that the rules turn away (`HitKind.Rejected`: the window is shut and the hit is no
harder) sets `PendingUntil = Tick + 0.15 s`, `PendingTarget` and `PendingLag`, the attacker's view rewind.

Every later tick runs `Buffered`:

- Drop the click if it has expired, the target is gone, or the attacker is not free (blocking, rolling, staggered,
  climbing).
- Wait while the window is shut.
- On the first open tick, clear the pending click: one attempt only.
- Land it if `Combat.Distance(eye, pose at Tick − PendingLag) ≤ Reach`. Aim is not re-checked.
- A newer claim replaces the pending click, a landed one clears it, and a missed click leaves it.
- Tests: `NeuesBufferLandsAClickOnTheFirstTickTheWindowOpens`, `ClassicLosesAClickIntoTheWindow`.

### 5.2 RevivalPaper semantics

**When a click is held.** The `Attack` in window `T` was delivered: a VALID verdict, past the grammar, the envelope,
the bucket and the stagger. The target is a `ServerPlayer`, the attacker holds a legacy weapon,
`click-buffer-ticks > 0`, and the replayed `Player.attack` hit **the window rule**: hook W fired on
`damage <= lastHurt` inside a shut window.

An overflow (a falling crit, a riposte crit) lands as 1.7's overflow and is never held.

**The pending record** (one per attacker, in `NeueState`) holds `targetId`, the `ServerboundAttackPacket` itself,
`untilServerTick = serverTick + bufferTicks`, `clickTick = T`, the selected slot and the weapon (`ItemStack` copy
from `ClientView`).

What changes it:

| After a delivered attack on any target... | Pending |
|---|---|
| held again (window rule) | replaced by this click |
| landed (fresh or overflow), parried, or turned away for any other reason (event cancelled...) | cleared |

| An attack that was not delivered (missed, over cap, over CPS, grammar, staggered) | Pending |
|---|---|
| any | unchanged |

**The landing pass** is `NeueCombat.serverTick(attacker, now)`, called first in `AuthPlayer.onServerTick`, before the
`mode == OFF` return and before `runReady()`. It runs in the connection phase, so the window that the level phase
opened is already visible. It runs once per server tick:

1. No pending click: return. `serverTick > untilServerTick`: clear it (stat `bufferExpired`).
2. Target removed, dead, in another level, or a spectator: clear.
3. **Attacker not free:** clear (stat `bufferNotFree`). The attacker is free when all of these hold:
   - alive;
   - `lastExempt == EXEMPT_NONE`, the simulation is seeded, `!frozen()` and `!teleportPending()`;
   - `!simulation.usingItem()`, because the client discards clicks while it uses an item;
   - `containerMenu == inventoryMenu`;
   - not staggered (§8);
   - the same selected slot, with `ItemStack.isSameItem(view.items.mainHand(), pendingWeapon)`, still a legacy
     weapon.
4. The target's window is still shut: return and wait.
5. **The window is open.** Clear the pending click, as Combo does: one attempt only. Then check **reach** against
   what the attacker's client shows now:
   - `eye`: the eye from `CombatWiring.snapshot(attacker)`. That is the origin of the client's next pick: the truth
     after its latest tick, plus the eye offset while a correction is in flight (§15.3).
   - `box`: `book(attacker).sample(targetId, attacker.tick(), s)`. It must be `pickable`, and `exact` while
     `nowMillis − s.stamp` is within the judge's cap (`CombatWiring.sharedRewindCap`). Past the cap the judge's
     Overwatch rule applies (auth-combat.md §2.2): the box is the target's truth at the capped age, in the sample's
     size (`TruthPaths`), and with no truth that new the click can't land.
   - `d² = Σ max(min − e, 0, e − max)²` over the axes: the closest point, and 0 inside the box. It must be
     `< entityRange²`, the client's `ENTITY_INTERACTION_RANGE` from `ClientView`, 3.0 by default.
   - With `click-buffer-line-of-sight`, a block clip (OUTLINE, no fluids, the `PickRay` block pass on the attacker's
     `WorldView`) from `eye` to the closest point must find nothing nearer.
   - On failure: stat `bufferOutOfReach` and return.
6. **Land.** Set the context `{buffered = true, distance = √d²}`, then `replay(pendingAttack)` through
   `handleAttack`. Paper's checks run, the events fire, and `Player.attack` runs. The click's token is already spent,
   so the bucket and the envelope are skipped. The judge is skipped too: the click was VALID when it was made, and
   reach has just been re-checked.

**What is decided at landing time** (as in Combo, which reads `SprintKnownBefore` on the landing tick): the sprint
knock (`combatSprinting`, the sprint ledger), crit (`fallDistance`, `onGround`), the riposte and the knockback
positions.

**What is not re-checked:** aim. The click was aimed and judged when it was made, within 150 ms of landing. The
mirror box is the natural form of Combo's `PendingLag` rewind: it is exactly what the attacker's client draws at its
latest tick.

**Why here.** A click held in the packet phase of `N` can land in the connection phase of `N`, if the level phase of
`N` opened the window. That is 0 server ticks after the window opens, and before any window queued in the same
phase. The attacker's own later clicks are judged after it.

**Around a server stall** (pvp-issues SV-2, `damage-windows.melee-wall-clock`), step 4 reads the counter the landing's
replay will meet, which follows the clicks' clock while the server is or was behind its schedule (`auth-combat.md` §6).
Right after a stall the pass also waits a tick while windows the attacker sent through the stall still wait for the
main thread: their clicks reached the server before the landing, and the one at the window's opening lands with its
own time.

When two attackers have clicks held on the same victim, connection order decides who lands fresh. The other click
is turned away and is not held again. Combo's roster order behaves the same way. A fairer option (p2): land the
oldest held click in `V`'s `ServerPlayer.tick` right after `damageCooldownTime--`. That needs one more hook line.

### 5.3 Numbers

`buffer_sim.py` (§12.3) simulates a 1v1 combo with the victim always in reach, using human clicking (CV 0.15) or a
fixed-rate autoclicker:

| Clicker | Aim | Classic: delay after open, hits/s | Neue: delay, hits/s |
|---|---|---|---|
| human 6 CPS | perfect | 1.10 ticks, 1.80 | 0.01, 2.00 |
| human 8 | perfect | 0.75, 1.86 | 0.00, 2.00 |
| human 12 | perfect | 0.42, 1.92 | 0.00, 2.00 |
| human 6 | 60% of clicks on target | 3.42, 1.49 | 1.58, 1.73 |
| human 8 | 60% | 2.41, 1.61 | 0.79, 1.85 |
| human 12 | 60% | 1.49, 1.74 | 0.31, 1.94 |
| autoclicker 20 | 60% | 0.75, 1.86 | 0.19, 1.96 |
| autoclicker 30 | 60% | 0.64, 1.88 | 0.22, 1.96 |

With aim, every rate of 6 CPS or more lands every combo hit on the opening tick. The maximum is 2.00 fresh hits/s
(one per 10 ticks).

### 5.4 Hooks, tests, risks

**Hooks:**

- W, in patch 1016 (combat): the window rejection in `LivingEntity.revivalHurtServer`.

  ```java
  if (damage <= this.lastHurt) {
      gg.hcfrevival.revival.combat.NeueCombat.windowRejected(this, source); // Revival - Neue combat
      return false;
  }
  ```

  `windowRejected` marks the attacker's context only for a legacy melee hit (`LegacyAttack.settings(source) != null`).
- Context and settlement, in `AuthPlayer.runAttack` (netcode): set `NeueCombat.begin(player, targetId, distance,
  buffered = false)` before `replay(attack)`, and `NeueCombat.settle(player)` after it. `CombatWiring.judge` already
  computes the distance (`recordHit`). Expose it, or return the `HitCheck`.
- The landing pass, in `AuthPlayer.onServerTick` (netcode).
- Optional (p1), in patch 1016: skip `PLAYER_ATTACK_NODAMAGE` in `Player.attack` when the click is held:
  `else if (!NeueCombat.holding(this)) this.playServerSideSound(...)`. Without it the attacker hears a "no damage"
  whoosh and then the hit.

**Tests:**

- `ClickBufferTest`: a pure state machine with an injected server tick and predicates.
  - Clicked at `open − 3`: lands at `open`. At `open − 4`: expires.
  - Out of reach at `open`: dropped, and does not land later even when back in reach.
  - Block raised, slot changed, container opened, or staggered before `open`: dropped.
  - A newer held click replaces the old one. A missed click, or one over the cap, keeps it. A landed click clears
    it.
  - Parried at `open`: parried, not hit.
  - Two attackers: exactly one fresh hit and one turned away, not held again.
- Live (port of Combo's pair of tests): two `FakeClient`s. A lands a fresh hit and clicks again 8 server ticks
  later. Exactly one fresh hit follows, on the server tick where `V.damageCooldownTime` first reaches 10, flagged
  buffered. Under `rules: classic`, no hit follows.
- Live: the victim walks out of reach between the click and `open`, so no hit lands.

**Risks:**

- `PrePlayerAttackEntityEvent` fires twice for one held click, once at the click and once at landing. Plugins that
  count attacks see two. Damage events fire once.
- A mirror that is inexact at landing (snap, late packet) drops the click. That is rare, and an honest player loses
  a hit.
- Landing happens without aim, which gives up to 150 ms of "sticky" damage, bounded by the reach check.

## 6. Tipper and sour

### 6.1 Combo

`Combat.Shape` classifies the hit as a tipper when `distance ≥ TipperFrom`, as sour when
`0 < distance < SourUnder`, and as plain otherwise. Its scale is `TipperKnock`, `SourKnock` or 1, applied to x and z
**of the whole knock result** (after `KnockbackEngine.Knock`: friction base, push, extra, clamp). y is never touched,
so combos keep 1.7's arcs. When the scale is 1 the spot reads plain.

Direct hits use the pick distance, eye to where the ray entered. Buffered hits use the closest-point distance at
landing. The rod has no tipper ("the tipper is the sword's").

### 6.2 RevivalPaper semantics

**Distance `d`, in this order:**

1. A direct hit uses `HitCheck.distance()` from the judge: eye (`xo + 1.0 (x − xo)` + eye height) to where the
   attacker's own pick ray entered the target's 0.6 × 1.8 box in the attacker's mirror at tick `T`. It is 0 when the
   eye is inside, and strictly `< 3.0` for a VALID pick. It is bit-exact and lag-compensated.
2. A buffered hit uses the landing distance from §5.2 step 5.
3. Anything else (no context: §11, or an UNKNOWN verdict for a target that is not under authority) falls back to the
   closest point from the attacker's server eye to `V`'s server box. That is not lag-compensated, and is documented
   as such.

**Classification:**

- tipper iff `d ≥ tipper-from`;
- sour iff `0 ≤ d < sour-under`;
- plain otherwise, and for `NaN`.

One deliberate difference from Combo: `d = 0` (the eye inside the box, players overlapping) is **sour**. In Combo,
0 meant "no distance" (the rod).

**Shape** (the pure `combat/NeueShape`):

```
s      = spot == TIPPER ? tipperKnock : spot == SOUR ? sourKnock : 1
(x, z) = (v.x · s, v.z · s)
(x, z) = influence(x, z, steer)                      // §7
v'     = clamp((x, v.y, z), ±profile.velocityClamp)  // re-clamp: s > 1 could pass 3.9
```

**Where it goes:** in `LegacyKnockback.targetMovement`, fresh branch (`!comesFromEffect`), on the value returned by
`KnockbackEngine.knockback(...)`, before the return. Reading the context costs nothing when the world's rules are
classic: that is the early exit.

**How it composes with the rest of the knock:**

- The scale multiplies the whole horizontal result: the motion model's base
  (`SPORTBUKKIT`/`VANILLA_1_7` shadow, `NONE` live, `AUTHORITATIVE` simulated velocity) over friction, plus the
  base push, plus the extra knock (sprint ledger level + Knockback enchant).
- Knockback resistance is already inside the result, and a per-player profile override is resolved first.
- `VANILLA_1_7` stores the shaped knock in the shadow (`sendVelocity` takes the event's velocity), so compounding
  combos compound the shaped knock, exactly as Combo's `Shadow.Set(pushed)` after `Shape` does.
- The overflow extra-knock path (`comesFromEffect`) is never shaped, as with Combo's `ExtraKnock`, and is never sent
  to players.

**Golden values** (`legacy-1.7`, standing victim with shadow `y = −0.0784`, attacker at −Z with yaw 0; `hcfr`
preset where marked):

| Hit | Plain | Tipper (d ≥ 2.6) | Sour (d < 1.4) |
|---|---|---|---|
| no sprint | (0, 0.3608, 0.4) | (0, 0.3608, 0.44) | (0, 0.3608, 0.36) |
| sprint armed (level 1) | (0, 0.4608, 0.9) | (0, 0.4608, 0.99) | (0, 0.4608, 0.81) |
| `hcfr`, armed ground start (1.3) | (0, 0.3408, 0.97) | 1.067 | 0.873 |
| `hcfr`, sprint held (0.8) | (0, 0.3408, 0.72) | 0.792 | 0.648 |

### 6.3 Tests and risks

**Tests:**

- `NeueShapeTest`:
  - the table above;
  - boundaries: 2.6 is a tipper, 1.4 is plain, 0 is sour, `NaN` is plain;
  - classic is the identity;
  - y is never changed;
  - the re-clamp.
- `LegacyKnockbackTest`: the context distance reaches the shape. The `RevivalKnockbackEvent` motion is the shaped
  one.
- Live: `FakeClient` hits with eye-to-box distances of 2.8, 2.0 and 1.0. The `SetEntityMotion` z decodes to 0.44,
  0.40 and 0.36 (LpVec3 tolerance 1e-4).
- Debug: `/revival kb debug` lines gain `tipper`/`sour`. `RevivalKnockbackEvent#getSpot()` is p2 API.

**Risks:**

- The tipper band is 0.4 of a 3.0 reach, and 26.3's reach is 0.1 shorter per side than 1.7's. Spacing at max range
  pays a little more than it did on Combo's grown box.
- The fallback distance (§11) is lag-dependent.

## 7. DI

### 7.1 The math (exactly `Combat.Shape` + `Combat.Heading`)

**Held direction `h`.** With `forward = W − S` and `left = A − D` (each 0 or 1), and `φ` the victim's yaw in
degrees (MC convention, 0 faces +Z):

```
h = (left · cos φ − forward · sin φ,  forward · cos φ + left · sin φ)
```

normalized, and zero with no keys held. This is the direction of 26.3's `Entity.getInputVector`
(`Entity.java:1812-1821`) for the client's `Input`.

**Bend.** With the horizontal knock `k = (x, z)` after the tipper:

```
across = (x · h.z − z · h.x) / (|k| · |h|)          // the sine of the angle from k to h
t      = influenceDegrees · π / 180 · across
(x, z) = (x cos t − z sin t,  x sin t + z cos t)    // k turned toward h by up to 15°
```

**What it does:**

- Held at 90° to the knock: the full 15°.
- Held along it, or straight against it: no bend.
- Length is preserved and y is untouched.
- Across a knock that travels about 2.7 blocks, 15° is about 0.7 blocks of drift.

### 7.2 RevivalPaper semantics

**Input source:**

- Victim under authority: `AuthPlayer.stream().input()` (the `Input` of the victim's latest simulated client tick,
  through `ClientStream.advanceInput`) and `tickYRot()` (that tick's yaw).
- Otherwise: `ServerPlayer.getLastClientInput()` and `getYRot()`.

**"At the tick the knock applies" cannot be honoured on a vanilla client.** The knock's vector goes out in the
velocity packet at hit time, and the client applies those numbers as they are at its drain. Re-aiming it at the
apply tick would take a second impulse or a correction.

So DI reads what the victim held at their latest processed tick when the hit is processed. That is about
`RTT_victim / 2 + jitter` before the knock lands on their screen, which is exactly Combo's semantics: the input of
the server tick the hit is processed in.

**Where it goes:**

- `NeueShape`, right after the tipper scale, in `LegacyKnockback.targetMovement`.
- With `influence-projectiles`: also in `LegacyProjectiles.targetMovement` (after Punch) for projectiles in
  `damage-windows.melee-projectiles` (rod, snowball, egg; Combo's `Rod` DI). Arrows keep 1.7's knock.

**Golden values** (`k = (0, ·, 0.4)`, plain spot; `h` is (x, z) in world terms):

| Held direction | Result (x, z) | Bend |
|---|---|---|
| +X (the victim faces +Z and holds A) | (0.103527618041008, 0.386370330515627) | 15.000° |
| +X+Z diagonal (W+A at yaw 0) | (0.073625841953992, 0.393165658974135) | 10.607° |
| +Z along the knock, or −Z against it | (0, 0.4) unchanged | 0° |
| armed sprint tipper (0, 0.4608, 0.99), +X | (0.256230854651496, 0.956266568026178) | 15° |

### 7.3 Tests and risks

**Tests:**

- `NeueShapeTest` goldens. `|k|` is preserved within 1e-12.
- `HeadingTest`: 10,000 random `(Input, yaw)` pairs against `Entity.getInputVector(new Vec3(left, 0, forward), 1,
  yaw)`, same direction within 1e-6 (both use the `Mth` tables). No keys gives zero. Diagonals are normalized.
- Live: the victim's `FakeClient` sends `PlayerInput(left)` and its tick, then takes a hit. The velocity's x has the
  sign of `h.x` and the bend is 15° ± 0.05° after LpVec3.

**Risks:**

- DI is decided from input up to one victim window old. Under jitter a strafe direction change in the last 50 to
  100 ms is missed. Combo has the same behaviour.
- DI also bends a victim's own w-tap combo escapes the way they hold. That is intended.

## 8. Parry, stagger, riposte

### 8.1 Combo's rules

- **The block.** A **fresh** block parries any hit within its first 0.15 s, if the sword was resting. The parry
  window is computed when the block goes up (`PlayerSim.ParryWindow`):
  - 0 if the blocker swung less than 0.3 s ago: a block-hit never parries, so trading cannot fish one.
  - Otherwise `round(0.15 s × clamp((sinceBlock − 0.35) / (1.0 − 0.35), 0, 1))`, where `sinceBlock` is the time
    since the last block came down. Spamming the block gets nothing; resting for a second gets the whole window.
- **One parry per block.** The next hit on the same block is a plain 1.7 block (half damage).
- **Parried hit** (`Combat.Parried`):
  - no damage, no knock, no damage window;
  - the parrier gets a riposte: their next hit within 0.6 s is a guaranteed crit, consumed once it lands fresh or as
    an overflow;
  - the parrier's sprint knock is re-armed;
  - the attacker is staggered (`PlayerSim.Stagger`). That means a recoil of (0.45 away, 0.22 up), the block lowered,
    no clicks, blocks, throws or eating for 0.5 s, movement ×0.35 and sprint off. It applies on the attacker's acked
    tick, like a knock.
- **The rod** is parried too: riposte, and no knock or window for the parrier. There is no stagger for a thrower
  "who may be across the map".
- **Measured:** six pro bots parry about 2% of hits.

### 8.2 Block, swing and parry window in RevivalPaper ticks

**Block start.** The window `T_B` in which the simulation starts using a legacy weapon: the replayed `UseItem`,
followed by `Simulation.startUsingItem` with `LegacySwordBlocking.isBlockingWeapon(stack)`. At that point record
`T_B`, the server tick `S_B` and the parry window:

```
W = (T_B − T_sw < blockHitTicks)  ? 0
  : round(windowTicks · clamp((T_B − T_E − rearmTicks) / (restTicks − rearmTicks), 0, 1))
```

with `windowTicks` 3, `blockHitTicks` 6, `rearmTicks` 7, `restTicks` 20, using `Math.round`. `parryUsed = false`.

**Block end** `T_E`: the window in which the simulation stops using it (`RELEASE_USE_ITEM`, a slot change, or a
server-meant stop). Initially `T_E` and `T_sw` are far in the past, so the first block gets the full window.

**Swing** `T_sw`: the last window with a `Punch`. Every attack click sends one (entity, block or air); clicks while
using an item send none.

**`W` by the time since the last block came down** (`T_B − T_E`, with the sword rested):

| Ticks since last block | ≤ 9 | 10-13 | 14-17 | ≥ 18 |
|---|---|---|---|---|
| `W` | 0 | 1 | 2 | 3 (150 ms) |

**At a hit** processed at server tick `S_now`, with the victim's latest window `T_now`:

```
blockTicks = max(T_now − T_B,  S_now − S_B − parryJitterTicks)
parry iff V is sword-blocking  &&  blockTicks < W  &&  !parryUsed
```

The server-time floor stops a blink. A blocker who sends the block-start window and then stalls their windows would
otherwise sit at `blockTicks = 0` until the next hit. With the floor (`parry-jitter-ticks` 2) they get at most 2
ticks of extra window.

A block that goes up in the same window as a click (an attack, then a use: the input grammar allows that order) has
`T_B − T_sw = 0`: a block-hit, so no parry.

### 8.3 The parry hook and what it does

Hook P, in patch 1016, at the top of `LivingEntity.revivalHurtServer` after the removed/dead checks:

```java
if (gg.hcfrevival.revival.combat.NeueCombat.parried(this, source)) return false; // Revival - Neue combat
```

**Order.** It runs after `ServerPlayer.hurtServer`'s invulnerability and team/PvP checks (`canHarmPlayer`), so team
rules hold. It runs before sword-block damage, armor and the window rule. A click into the parrier's shut window
against a fresh block therefore still parries and staggers, which is Combo's order.

**What qualifies.** The victim is a `ServerPlayer` in a world with `parry: true`. The source is a legacy melee hit,
or (with `parry-projectiles`) a melee-window projectile with a player owner.

**`NeueCombat.parried`:**

1. Fire `RevivalParryEvent(parrier, attacker, projectile)`, which is new API and cancellable. If cancelled, the hit
   goes on as a plain blocked hit.
2. Set `parryUsed`.
3. Parrier: `riposteUntil = S_now + riposteTicks`, then `revivalSprintLedger.rearm(onGround)`. That re-arm is a
   no-op when `rearm-sprint-on-block` already re-armed at block start.
4. Melee: stagger the attacker (8.4). A projectile: no stagger.
5. Feedback (§10).
6. Return true.

**Effects of returning false.** `Player.attack` sees `wasHurt == false`:

- no knock, no extra knock;
- the attacker's sprint arm is **not** consumed;
- no crit or magic-hit visuals;
- `entity.player.attack.nodamage` plays to the attacker and to players nearby (`Player.sendSoundEffect`). Keep it
  under the parry sound or suppress it, with the same optional hunk as §5.4.

The parrier's health, `lastHurt`, `damageCooldownTime`, `hurtTime` and the damage event are all untouched: no red
flash and no window, as in Combo.

**A parried rod.** `LegacyProjectiles.rodHits` gets false. With `rod-hook-only-if-hit` the hook does not catch: "the
hook turned aside".

**Riposte.**

- `LegacyAttack.canCriticalAttack(attacker, target)` becomes `... || NeueCombat.riposteReady(attacker)`, where ready
  means `serverTick < riposteUntil`. This is plain source; no patch hunk is needed.
- The riposte is consumed in `LegacyDefence.lastHurt`, which is called on both landing branches (fresh and
  overflow), when the source's attacker had one ready. A riposte click turned away by the window keeps it, and if
  it is held (§5) it can land as the crit at landing.
- Damage is `base × critical-multiplier (1.5) + enchantments`, which is Combo's order.
- The crit sound and `Player.crit` (an animate packet to trackers **and the attacker**) are the vanilla riposte
  cue, at no cost.

### 8.4 The stagger on a vanilla 26.3 client

`NeueCombat.stagger(attacker, parrier)` sends these together. During a server tick the netcode gathers them into one
per-tick bundle with one ping, so they share one ack window, `T_s`.

1. **Recoil.** `ClientboundSetEntityMotionPacket(A, r)`, where `r = (0.45 a.x, 0.22, 0.45 a.z)` and `a` is the unit
   horizontal vector from the parrier to the attacker in truth positions (if zero, the parrier's facing
   `(−sin yaw, cos yaw)`).

   Send it the way `LegacyKnockback.sendVelocity` does: fire `PlayerVelocityEvent`, then set the `MotionShadow` per
   the model. As a logged entry the simulation takes it at its ack.

   On 26.3 physics with no input this throws the attacker about 2.4 blocks over 7 airborne ticks, about 2.7 blocks
   in all. A standing 1.7 knock (0.4, 0.3608) travels the same 2.7.
2. **Sword cooldown** (`stagger-sword-cooldown`). `A.getCooldowns().addCooldown(weapon, staggerTicks)` sends
   `ClientboundCooldownPacket`.
   - The vanilla hotbar draws the cooldown sweep over that sword for exactly 10 client ticks from `T_s`. The client
     counts it down itself.
   - While it lasts the client's `MultiPlayerGameMode.useItem` returns PASS (26.3: `if
     (player.getCooldowns().isOnCooldown(itemStack))`). No block can be raised or predicted, and the server's
     `ServerPlayerGameMode.useItem` refuses too.
   - `ClientView` already models it (`cooldown`, `onCooldown`, `predictsUse` → PASS), so blocking stays
     correction-free.
   - The cooldown group is the item: every sword of that type.
3. **Guard break** (`stagger-lowers-block`). If A is already sword-blocking (a block-hit went up), call
   `A.stopUsingItem()` on the server.
   - Its self entity-data echo is server-meant (`AuthPlayer.onServerWrite`, `setLivingEntityFlag` outside the
     replay).
   - On the client, `LocalPlayer.onSyncedDataUpdated` does `!serverUsingItem && startedUsingItem → stopUsingItem()`,
     so the block drops at `T_s` on both ends.
4. **Attack lockout.** Every attack by A processed after the parry, up to and including window
   `T_s + staggerTicks − 1`, is refused in `LegacyHitRegistration.beforeAttack` (plain source; it already runs first
   in `Player.attack`). The refused attack gets no events and no damage. The same call clears A's held click.
   - `T_s` is the window in which the recoil entry is acked. `AuthPlayer.apply` compares the packet by identity with
     the one `NeueState` stored. If no ack comes, the deadline's force-apply window counts as `T_s`. Until `T_s` is
     known the lockout holds: clicks the attacker made before they could see the parry are part of the recovery.
     This is stricter than Combo, where clicks still in flight could land on the parrier's now-plain block.
   - `Punch` packets in the lockout are not replayed, so viewers see no swing. The attacker's own client still
     animates its swing. A vanilla client cannot be stopped from that, and the simulation already applies the
     client's attack prediction for every `Attack`.
5. **Slow** (`stagger-move-multiplier` < 1, p2). Add a transient `movement_speed` modifier
   `hcfrevival:neue_stagger`, `ADD_MULTIPLIED_TOTAL`, `m − 1 = −0.65`, in the same bundle.
   - Send its removal when A's window reaches `T_s + staggerTicks − round(rttBase / 50)`, so it lands near
     `T_s + staggerTicks`. The simulation takes both at their acks (`ClientAttributes`). The vanilla FOV narrows
     with it, which is a cue in itself.
   - Combo also stops sprinting during a stagger. Vanilla has no clean server-side sprint block (only blindness or
     food ≤ 6), so that is not copied.
6. **Sound to A only:** `item.shield.break` (§10).

**What the attacker sees:**

- they are thrown back about 2.7 blocks;
- the sword slot sweeps grey for 0.5 s, and right-click does nothing;
- swings do no damage;
- optionally, their view narrows.

Meanwhile the parrier has 12 ticks to drop the block and riposte. With Speed II a sprint closes about 2 blocks in 5
to 6 ticks.

**Rejected alternatives for the lockout cue:**

- **An attack-speed dip** (to show the crosshair attack indicator). The indicator fills from the attacker's last
  swing, not from the stagger. It changes the client's own attack prediction: the ×0.6 slowdown needs
  strength > 0.9, so the simulation would branch on it. It also fights `LegacyHitRegistration`'s per-tick
  `NO_COOLDOWN` refresh.
- **`MINIMUM_ATTACK_CHARGE`** (26.3's `cannotAttackWithItem` stops the client swinging at all). It would have to be
  on the client copy of every legacy sword, because `toClient` is global. With +1024 attack speed it also refuses
  the first click after every hotbar switch (the ticker resets on an item change), a 50 ms penalty on
  potion-to-sword plays. Not without the user's OK.

### 8.5 Tests and risks

**Tests:**

- `ParryWindowTest`: the `W` table, and the block-hit edge (swing at `T`, block at `T+5` gives 0, `T+6` the full
  window).
- `NeueStateTest`:
  - once per block, reset by a fresh block;
  - the stall floor: `T_now` frozen, `S_now − S_B = 5` → no parry;
  - riposte: a crit at tick +11, expired at +12, kept when the window turns the click away, consumed on an overflow;
  - a parried hit leaves `V`'s health, `lastHurt`, `damageCooldownTime` and `hurtTime` unchanged and A's ledger
    armed, and a hit straight after lands fresh;
  - a parried rod: no hook, no stagger, riposte set.
- Live (`AuthLiveScenarios`, two `FakeClient`s):
  - B rests 1 s before each block, and A hits each block once: at `blockTicks` 0 it is parried, on the next block at
    2 parried, on the next at 3 a plain blocked hit. A second hit on a block that already parried is a plain
    blocked hit.
  - A receives one bundle `[Ping, SetEntityMotion, Cooldown, (SetEntityData)]`.
  - A's attacks in windows `T_s … T_s+9` are not delivered, and at `T_s+10` one is.
  - A's `UseItem` during the lockout starts no use.
  - The `RevivalParryEvent` cancel path gives a plain blocked hit.
- Rig (a real 26.3 client, `auth-rig`):
  - the stagger bundle causes zero corrections;
  - the cooldown overlay shows, and a block cannot be raised;
  - a block-hitting attacker's block drops at `T_s`;
  - with the slow, zero corrections across both attribute acks.

**Risks:**

- Ping asymmetry. The parry is judged on the server's latest view of the blocker, so a high-ping blocker's block
  arrives later. There is no defensive rewind, as in Combo.
- 20 Hz granularity: 3-tick windows and 1-tick steps in the rest scale.
- The attacker sees their own swings during the lockout.
- Factions, PvP timers and spawn protection usually cancel `EntityDamageByEntityEvent`, which fires **after** hook P.
  HCFR core must cancel `RevivalParryEvent` in the same cases; this is outside the repo.
- The cooldown also hits the attacker's other swords of the same type.

## 9. Body hitboxes: not feasible on a vanilla client

Combo's Neue picks the model's own parts (`Hitbox.Parts`: head, torso, arms and legs at 0.9375 scale, posed by
stance and body yaw). A vanilla 26.3 client picks the 0.6 × 1.8 box (`ProjectileUtil.getEntityHitResult`, pick
radius 0) and sends an `Attack` only when that box wins.

The server can therefore only **remove** hits:

- **Arm hits outside the box are impossible.** At 0.9375 scale the arms reach ±0.47 blocks against the box's ±0.30.
  The client never picks them, so they never arrive.
- **Box hits outside the parts would be refused.** From the side the body is about 0.23 deep against the box's 0.6.
  The client swings and predicts its own slowdown, and nothing happens: a "ghost hit". To this crowd that reads as
  broken registration.
- **Exact refusals would need a lot more mirroring.** The server would have to mirror each viewer's client-side
  body yaw (`yBodyRot`, worked out on the viewer from interpolated movement and lerped head rotation), pose and
  sneak. That is a substantial addition to `RemoteMirror`.

Recommendation: no `body-hitboxes` key. Keep the box, which in 26.3 is already 0.1 tighter per side than 1.7's.

If the question comes back, a p2 option: an observe-only statistic of the share of accepted hits whose ray misses
Combo's parts, computed from the mirror plus a ported body-yaw rule. That measures the question without changing a
single hit.

## 10. Feedback (vanilla sounds and particles, no resource pack)

Plain, readable cues for a tryhard crowd: one sound per event, nothing on the HUD.

| Event | Who hears / sees | Sound (`feedback.<event>-sound`, volume, pitch) | Particle (`feedback.<event>-particle`, count) |
|---|---|---|---|
| tipper | sound to the attacker only; particle to everyone in range | `entity.arrow.hit_player` (the PvP "ding") 0.35, 1.6 | `electric_spark` ×4 at the ray's entry point (eye + look × d) |
| sour | nobody: silence is the cue | none | none |
| parry | everyone in range | `item.shield.block` 1.0, 1.25 at the parrier (sword blocks are otherwise silent, so it is unique) | `sweep_attack` ×1 at chest height 0.6 toward the attacker (legacy combat has no sweeps, so it is unique), plus `crit` ×6 |
| stagger | the staggered player only | `item.shield.break` 0.7, 1.3 | none: the hotbar sweep and the recoil carry it |
| riposte | everyone | vanilla crit sound and particles (server-sent for any crit) | (vanilla) |
| buffered hit | n/a | none: it is just the hit | n/a |

- Private sounds go out as `ClientboundSoundPacket` to one connection. Public ones use `level.playSound(null, ...)`,
  which respects visibility hiding (`visibility.effect-reach`).
- None of these are physics packets, so they are neither bundled nor logged.
- An empty string turns a cue off.

## 11. Players outside authority (degraded mode)

Neue's exact semantics need `enforce`. These players get approximations:

- authority `off` or `observe`;
- not eligible (Geyser, other protocols through Via);
- exempt ticks.

| Mechanic | Fallback |
|---|---|
| Click cap | refill per server tick in `LegacyHitRegistration.tick`, charge in `beforeAttack` (buffered landings skip it) |
| Click buffer | arm in hook W as above; land in `LegacyHitRegistration.tick` with the server eye and the target's server box (not lag-compensated) |
| Tipper | closest-point distance on server positions (§6.2 case 3) |
| DI | `getLastClientInput()` and `getYRot()` |
| Parry | `T_B`, `T_E` and `T_sw` from server ticks (use start and stop seen on the `ServerPlayer`, swing from `handlePunch`); `parry-jitter-ticks` 0 |
| Stagger lockout | until `S_parry + ceil(rtt / 50) + staggerTicks` server ticks (estimated `T_s`), the round trip (the keep-alive latency) taken once at the parry, so a keep-alive answered mid-stagger doesn't move its end; the rest unchanged |

## 12. Autoclickers

### 12.1 What still matters after the buffer and the cap

- **Combo timing no longer depends on CPS.** At 7 CPS or more there is always a click in the 3-tick buffer span, so
  every combo hit lands on the opening tick (§5.3).
- **Aim sampling (spray) remains.** Each click is a sample of the crosshair. With shaky tracking (60% on target),
  more clicks still mean a better chance that a valid click is held when the window opens, up to the cap:
  - hits/s for 6 / 8 / 12 CPS humans and an autoclicker: 1.73 / 1.85 / 1.94 / 1.96 under neue, against
    1.49 / 1.61 / 1.74 / 1.86-1.88 under classic;
  - the autoclicker's edge over a 12 CPS human falls from 7-8% to 1%;
  - over an 8 CPS human it falls from 15-17% to 6%.
- **Endurance.** A machine holds 12 CPS for a whole fight; humans drop to 8 or 10.
- **Overflow attempts.** Falling crits inside a shut window are bounded by the cap.
- **Not touched by the buffer or the cap:** macro timing (block-hit, w-tap and rod macros), and aim assist. Aim is
  measured by `aim-gcd`.

### 12.2 The locked-cadence detector

It is evidence for staff, not authority.

**Observable.** Per window, `clicks` is the number of `Punch` packets, before the bucket (an attack click sends
exactly one). `InputStats` gains `clicksThisTick`.

**Eligibility.** A window counts only if all of these hold. Any other window resets both chains, which can only
lower detection, never raise false positives.

- it ran, and was neither refused nor exempt;
- the player is alive;
- the client was not using an item at its keybinds (simulation);
- no container is open (`containerMenu == inventoryMenu`);
- no `PlayerAction` `START/STOP/ABORT_DESTROY_BLOCK` in the window, and `!player.gameMode.isDestroyingBlock`.
  Mining sends a `Punch` every tick, and is the one source of false 1-per-tick streaks.

**Detector A, `click-streak`** (a 20 CPS lock, or a 1-per-tick macro). The run of consecutive eligible windows with
exactly one click. It fires when the run reaches `click-streak-ticks` (100: 5 s at exactly 20 CPS). The event's
`value` is the run.

**Detector B, `click-lock`** (any fixed rate; built 2026-10-23 in `auth.input.CadenceWatch`). Keep a ring of the last `N + 1` click ticks (`click-lock-clicks`, 150).
`interval = t_i − t_{i−1}`, where 0 means two clicks in one tick. On each click, with the ring full:

1. `spread = max − min` of the N intervals, from a per-value count over 0..20, kept O(1). Require `spread ≤ 1`, so
   every interval is `k` or `k+1`.
2. Require the mean interval `≤ 20 / click-lock-min-cps` (8.0).
3. If both hold, fit the least-squares line `t_i ≈ a + b·i`. It fires if `max |residual| ≤ click-lock-residual`
   (0.75 ticks).

   A fixed-rate clicker sampled on a fixed tick grid gives a balanced (Sturmian) sequence, which is never more than
   about one tick off its line. The O(N) fit runs only while (1) and (2) hold: about 3,000 multiply-adds per click
   at most, and nothing for most players.

The event's `value` is the number of locked clicks (≥ N).

**Events.** Both raise `AuthInputAnomalyEvent` with kind `click-streak` or `click-lock`. `AnomalyWatch` gains the two
kinds: one event per crossing, re-armed when the value drops. Both also show in `/revival auth inspect`. As built, the
`click-lock` event waits for the lock to hold N more clicks (value ≥ 2N). A code review's simulation (2026-10-23:
Gaussian intervals, no tempo drift, the tick grid) had the first lock flag steady humans in 15-40 of 40 three-minute
sessions at 12-14 CPS with a CV of 0.05-0.07; 0-2 held the lock that long, while fixed-rate clickers did in 28-40.

**Keys** (`authoritative.stats.*`):

```yaml
click-streak-ticks: 100
click-lock-clicks: 150
click-lock-residual: 0.75
click-lock-min-cps: 8.0
```

**Cost.** O(1) per window. Preallocated rings, no allocation.

### 12.3 Thresholds: how they were chosen

The study scripts are `cadence_sim*.py` and `buffer_sim.py` in `~/Developer/tools/auth-rig/neue/`. The
model:

- **Client timing.** 60, 144 or 240 fps with ±7.5% frame jitter. GLFW polls clicks at the end of a frame. Tick `k`
  runs at the first frame start at or after `50k + φ`. A click is consumed by the first tick that runs at or after
  its poll.
- **Humans.** Renewal clicking with gamma intervals. CV per player, tempo drift AR(0.9) of about 3%. Butterfly
  clicking as alternating 0.35 / 1.65 gaps.
- **Autoclickers.** A fixed period with Gaussian timer jitter of σ 0.5-5 ms, or uniform ±10% or ±25%
  randomization.

**False positives per hour of unbroken clicking** (200 h per population, 40 s segments):

| Human population | A (L = 100) | B (N = 150, R = 0.75) |
|---|---|---|
| steady 6-8 CPS, CV .05-.08 | 0 | 0 |
| steady 9-12 CPS, CV .06-.10 | 0 | 0.010 (2 in 200 h) |
| jitter 10-14 CPS, CV .10-.25 | 0 | 0 |
| fast 15-19 CPS, CV .12-.25 | 0 | 0.005 (1 in 200 h) |
| butterfly 16-26 CPS, CV .20-.35 | 0 | 0 |
| stress: steady 19-21 CPS, CV .10-.15 (not a real population) | 1.25 | 1.4 (0 at N = 300) |

Shorter windows are useless at 50 ms resolution. At N = 40-80 with R = 1.0, B fired 20-170 times an hour on
steady humans: human jitter (σ ≈ 10 ms) is far below a tick. A at L = 20-40 fired 2-41 times an hour on 15-19 CPS
humans.

**Detection within a 60 s session** (share of sessions, median time to fire):

| Autoclicker | A | B |
|---|---|---|
| fixed 20 CPS, σ 0.5-5 ms | 67-83%, 5 s | 52-82%, 7.5 s |
| 20 CPS ±10% | 97%, 16 s | 72%, 24 s |
| fixed 8, 12, 14, 16, 25 CPS | 0 | 99-100%, 6-19 s |
| fixed 10 CPS | 0 | 51-78%, 15 s |
| ±10% at 12 / 14 / 16 / 25 CPS | 0 | 41 / 71 / 95 / 100% |
| ±10% at 8-10 CPS | 0 | 0 |
| ±25% at 8 / 12 / 16 / 20 / 25 CPS | 0 / 0 / 0 / 1 / 0% | 0 / 0 / 0 / 0 / 10% |

**Caveats:**

- The human model is synthetic and on the steady (pessimistic) side.
- Before trusting the thresholds, record real playtest windows (`InputStats` intervals, RVTR traces) and re-run the
  study.
- Well-randomized autoclickers cannot be told from humans at tick granularity. The cap is the defense; the detector
  is only evidence.

**Tests** (`CadenceWatchTest`):

- A: 99 one-click ticks do not fire, 100 do; a 0- or 2-click tick resets the run; an ineligible window resets it.
- B: (2, 2, 1)×50 (12 CPS) fires at the 150th interval; 2×150 and 1×150 fire, and so does 25 CPS (1, 1, 1, 2
  clicks a tick); a 3 after 150 2s breaks the lock at its click (it sits about a tick off the line; one hiccup among
  many is still a lock once it is inside the ring); 1s and 3s, or 3s, never lock; an ineligible window or a new N
  starts the chain over; a seeded human-like sequence (CV 0.10, 12 CPS, 10,000 clicks) never fires.
- Mining punches never count.

## 13. Hooks and owners

New patch `leaf-server/minecraft-patches/features/1016-Neue-combat.patch`, one mechanic, all hunks marked
`// Revival - Neue combat` and listed in `scripts/revival/markers.txt`:

| Applied file | Hunk |
|---|---|
| `ServerPlayer.java` | `public final gg.hcfrevival.revival.combat.NeueState revivalNeue = new gg.hcfrevival.revival.combat.NeueState();` |
| `LivingEntity.java` (`revivalHurtServer`, top) | `if (gg.hcfrevival.revival.combat.NeueCombat.parried(this, source)) return false;` |
| `LivingEntity.java` (`revivalHurtServer`, window rejection) | `NeueCombat.windowRejected(this, source);` before `return false` |
| `Player.java` (`attack`, `else` of `wasHurt`), optional | skip `PLAYER_ATTACK_NODAMAGE` while `NeueCombat.holding(this)` or after a parry |

Plain source (no patch):

| Owner | File | Change |
|---|---|---|
| config | `config/CombatRules.java` (new), `LegacyCombatSettings`, `RevivalSettings`, `RevivalConfig` | §2 keys, presets, `combat-rules`, version bump |
| config | `AuthoritativeSettings` | the four `stats.click-*` keys |
| combat | `combat/NeueCombat.java` (new) | static entry points: `parried`, `windowRejected`, `begin`/`settle`, `serverTick` (landing), `stagger`, `riposteReady` |
| combat | `combat/NeueState.java` (new) | per player: `ClickBucket`, pending click, `T_B`/`S_B`/`T_E`/`T_sw`/`W`/`parryUsed`, `riposteUntil`, stagger (`T_s`, packet ref, end), context |
| combat | `combat/NeueShape.java` (new, pure) | tipper/sour spot and scale, DI, heading |
| combat | `LegacyKnockback.targetMovement`, `LegacyProjectiles.targetMovement` | shape the fresh knock; DI on melee-window projectiles |
| combat | `LegacyAttack.canCriticalAttack`, `LegacyDefence.lastHurt` | riposte crit and its consumption |
| combat | `LegacyHitRegistration.beforeAttack`/`tick` | stagger lockout; the fallbacks of §11 |
| combat | `leaf-api/.../RevivalParryEvent.java` (new) | cancellable parry event |
| netcode | `AuthPlayer.runWindow`/`runAttack` | bucket refill and charge; context before and after `replay(attack)`; `Punch` → `T_sw` and `clicksThisTick`; no `Punch` replay during the lockout |
| netcode | `AuthPlayer` use transitions | `T_B`/`T_E` from `startUsingItem`/`releaseUsingItem`/server-meant stops |
| netcode | `AuthPlayer.onServerTick` | `NeueCombat.serverTick(this)` first |
| netcode | `AuthPlayer.apply` | `T_s` = ack window of the stagger's motion packet (by identity) |
| netcode | `auth/input/CadenceWatch.java` (new), `InputStats`, `AnomalyWatch`, `DebugHud` | §12.2 |
| sim | `ClientView`/`ClientAttributes` | verify: cooldown PASS on the sword, the stagger modifier on `movement_speed` (p2), server-meant use stop |

## 14. Rollout and proof

1. **Unit.** `ClickBucketTest`, `ClickBufferTest`, `NeueShapeTest`, `HeadingTest`, `ParryWindowTest`,
   `NeueStateTest`, `CadenceWatchTest`, `CombatRulesTest` (load, presets, seconds to ticks, ranges). All goldens are
   in this file, with the Combo constant cited per test.
2. **Live** (`revivalLiveTest`, sync tracker; authority refuses the async one). Scenarios: buffer, tipper, DI,
   parry, stagger, lockout, cap. Existing cheat-suite rows 44-51 must stay green with `rules: neue`.
3. **Rig** (a real 26.3 client). Each scenario gives zero corrections: stagger, block refusal, guard break, DI keys.
   Cooldown sweep screenshots are taken once (visual), and the rest are judged by numbers.
4. **Playtest** with `debug.hud`. Per player:
   - tippers %, sours %, buffered landed / expired / dropped, parries, staggers, ripostes, `attacksOverCps`;
   - Combo's bot reference: tippers about 10-12% of hits, sours 20-40% (bots fight close), buffered 1-10%, parries
     about 2%.

## 15. Open questions

- **Landing fairness** with several attackers on one victim. Connection order, or V's tick (§5.2, p2)?
- **The stagger slow without a sprint block.** Does ×0.35 alone feel right, or drop it?
- **The nodamage sound** on held and parried clicks. Suppress it (one hunk) or keep it?
- **`RevivalParryEvent` in HCFR core.** Who wires it, and does core need parries off in some modes (KOTH, events)?
- **Combo's `d = 0` (plain) against sour here.** This spec picks sour; confirm.
