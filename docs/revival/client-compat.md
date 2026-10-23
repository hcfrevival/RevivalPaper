# Client compatibility: Lunar, PvP clients and mods under authority

Status: research and spec, 2026-10-21, branch `revival/authoritative`. No code changed by this document. It answers
`pvp-issues.md` CL-1 and covers CL-2 briefly. Read with `authoritative.md` §10 and §15, `auth-netcode.md`, and
`auth-research/02-client-netcode.md` ("report 02": what a vanilla 26.3 client sends, in which order).

How claims are tagged:

- **[src]** read in source or bytecode: the decompiled vanilla 26.3 client (`~/Developer/tools/mc-src/src-26.3`), the
  Apollo API 1.2.9 jar hcfr-core builds against (`~/.m2/repository/com/lunarclient/apollo-api/1.2.9`, read with
  `javap`), Apollo, Lithium, EntityCulling and GrimAC source on GitHub, hcfr-core, this repository.
- **[doc]** official docs and release notes (lunarclient.dev, Lunar's news posts, Lunar's release notes as mirrored by
  releasebot.io).
- **[inf]** inferred from the above: the most likely behaviour, not observed.
- **[?]** unknown: the test in §9 decides.

Lunar Client is closed source. Everything this document says about what Lunar's own code does on 26.3 is [inf] or [?]
until the §9 session has run.

## 1. Summary

The authority judges every packet against what a vanilla 26.3 client can send. Lunar on 26.3 is the vanilla 26.3
client plus Sodium, plus Lunar's mod framework, plus three server-controlled combat options that HCFR turns on. Nothing
found so far changes Lunar's movement physics or its interpolation of other players. What can break is narrower, and
most of it sits in the strict parts of the netcode: the kicks and the input grammar.

Top risks, ranked by severity times likelihood:

1. **Kicks for packets sent outside the vanilla tick (L10).** In enforce, "a pong after the tick's keybinds" and "a
   second input, sprint command or move claim in one tick" disconnect the player ("Invalid transaction replies"). In
   HCF a kick is a combat-log death. Any client feature that writes a keybind-type packet (drop, swap, slot change, use,
   attack) from a key callback, the render loop or a Fabric end-of-tick event will now and then be followed by a pong
   in the same window. Lunar: [?]. Fabric mods: certain for some.
2. **`disable-miss-penalty` against `ATTACK_AFTER_CLOSE` (L1).** HCFR turns the option on in Arena and Factions. If
   Lunar's version also removes the dropped first click after closing a screen, every pot refill followed by a hit in
   the same tick is dropped and counted. Three counts raise `AuthInputAnomalyEvent`, so staff get false alerts on the
   most common HCF action. Lunar's implementation: [?]. Since 2026-10-22 the rule doesn't hold for a player whose
   miss penalty the server turned off (N5), so neither the drop nor the alert can happen.
3. **Lunar input features the simulation may not see (L5, L6, L8).** Toggle sprint that sets sprint without the
   `Input` sprint bit means speed divergence and corrections. Fly boost means corrections whenever a player flies
   (certain if it is on). Smooth Teleport, if it moves the player rather than the camera, means every pearl, teleport
   and correction misses. All three: [?] (fly boost [inf]).
4. **Non-vanilla trig (F1, F4).** OptiFine-style fast math changes every strafing tick, which means constant
   rubber-banding for those players. Lithium's sine table is bit-exact but indexes with an `int` cast, so it differs
   only past about 11.8 million degrees of yaw: low.
5. **Interpolation of other players on the attacker's client (L11).** A change there means hits wrongly rejected at
   box edges. No evidence that Lunar changed it on 26.3. EntityCulling's tick culling was checked: it still advances
   interpolation on 26.3 [src].
6. **`allow-dig-and-use` (L2): low.** The authority is already permissive here: use prediction has no
   "is destroying" gate and dig predictions have no "is using" gate [src]. A future grammar rule on dig and use must be
   gated by the client profile.
7. **Statistics, not authority (L7).** Lunar's zoom (smooth camera, its own sensitivity) breaks the yaw-GCD fit and
   raises `aim-gcd` anomalies (IN-4).
8. **CL-2.** ViaVersion and Geyser players are off authority (client-trusted). hcfr-core deploys neither today [src],
   so every current player is a native 26.3 client.

The fix has one core: a per-player **client profile** (§8). It is built from the brand, the `lunar:apollo` channel and
the Apollo options the server itself sent. The grammar, the kick policy and the simulation's few variants read it.
Section 7 lists the changes per owner.

## 2. What HCFR pushes to Lunar today [src]

- hcfr-core pins Apollo-Bukkit 1.2.9 (`deploy/lib/artifacts.sh`, `scripts/common.sh`) and builds against
  `apollo-api` 1.2.9 (`pom.xml`).
- Arena (`arena/src/main/java/dev/hcfr/arena/lunar/ApolloCombatBridge.java`, `LunarConfig.java`, file `lunar.yml`) and
  Factions (`factions/src/main/java/dev/hcfr/factions/lunar/combat/LunarCombatFeature.java`,
  `factions/.../lunar/bridge/ApolloBridge.java`) set these globally (one value for every Lunar player):

  | Apollo option | Config node | HCFR value |
  |---|---|---|
  | `CombatModule.ALLOW_DIG_AND_USE` | `allow-dig-and-use` | **true** |
  | `CombatModule.DISABLE_MISS_PENALTY` | `disable-miss-penalty` | **true** |
  | `CombatModule.DISABLE_BLOCK_MISS_PENALTY` | `disable-block-miss-penalty` | false |
  | `ModOneSevenVisuals.ENABLED` (mod settings, per player) | `one-seven-visuals.enabled` | forced **true** at Apollo registration |

- Arena turns off Apollo's waypoint module in its config (`deploy/lib/render.sh`). The combat module keeps its default
  (enabled).
- hcfr-core #34 ("Users can not swing and pot or hitreg feels off", closed) is why the options were turned on.
- No ViaVersion, ViaRewind, Geyser or Floodgate anywhere in hcfr-core's `deploy/`, `scripts/` or `environments/`.

(Read on hcfr-core branch `feat/arena-redline`, commit `32465f39`. Factions and Arena have the same defaults.)

## 3. Lunar on 26.3

### 3.1 Version, stack and detection

- Full 26.3 support since game v2.22.41 (23 Sep 2026) [doc]. Later 26.3 fixes in the notes: FPS work, a crash with
  "1.7 Visuals Glint Rendering", "Fixed Smooth Teleport making the world slide too far on 26.3", "Fixed swing
  animation not being recorded on 26.3" (the Rewind replay mod) [doc]. Nothing about combat, hit registration,
  interpolation or movement physics.
- Modern Lunar ships Sodium: its performance guide lists Sodium's Entity Culling and Entity Distance settings for
  modern versions [doc]. Sodium is render-only [inf].
- Brand: `lunarclient:<build>/version/<mc>` (community-documented, for example `lunarclient:8cccced/version/1.21.6`).
  Paper stores it in `ServerCommonPacketListenerImpl.clientBrand` and exposes `Player#getClientBrandName()` [src]. It
  is self-reported.
- Apollo registers a player when the client registers the `lunar:apollo` plugin channel (`ApolloPlayerListener`,
  `PlayerRegisterChannelEvent`) [src]. The client then sends a `PlayerHandshakeMessage`, and Apollo fires
  `ApolloPlayerHandshakeEvent`, which carries:
  - the Minecraft version, as an enum that stops at `V1_20_4` in 1.2.9 (so `UNKNOWN` for 26.x);
  - the Lunar version (git branch, commit, semver);
  - the installed mods (id, name, version, type `FABRIC_INTERNAL`, `FABRIC_EXTERNAL`, `FORGE_INTERNAL` or
    `FORGE_EXTERNAL`).

  `ApolloPlayer.getInstalledMods()` returns the same list [src].
- `/lunarclient <player>` prints whether Apollo sees that player on Lunar [src].
- Every combat option is built with `notifyClient()`: Apollo sends it to the client on registration and on change, as
  a plugin message on `lunar:apollo`. `Options.get(ApolloPlayer, option)` returns the value one player has [src].

### 3.2 The Apollo combat module

Option facts are [src] (Apollo API 1.2.9 bytecode) and [doc] (lunarclient.dev). The 26.3 code paths are [src] for the
vanilla code, [inf] for what Lunar changes in it.

| Option (node, default) | Apollo's own text | Vanilla 26.3 code it most likely changes | Effect on the packets |
|---|---|---|---|
| `disable-miss-penalty`, false | "remove the miss penalty on all versions 1.8 and above"; "may cause compatibility issues with anti-cheats" | `Minecraft.startAttack` sets `missTime = 10` on a null hit result (`Minecraft.java:1663`) and on a miss (`:1710`). `continueAttack(false)` clears it in any tick the button is up (`:1631-1634`). Separately, an open screen sets `missTime = 10000` every tick (`:1817`), which drops the first attack click after the screen closes (report 02 §2.3). | After a whiff with the button still down, clicks a vanilla client eats now send `Attack`+`Punch`. **[?] whether the screen drop goes too.** |
| `disable-block-miss-penalty`, false | "remove the miss penalty when targeting a block" | Not identified. 26.3 only sets `missTime` on a miss, on a block hit that is air (the `BLOCK` case falls through to `MISS`, `:1698-1711`), and on a null hit result. | [?] |
| `allow-dig-and-use`, false | "allow digging and using an item at the same time" (1.7 behaviour) | `startUseItem` does nothing while `gameMode.isDestroying()` (`:1724-1725`), so right-click is dead while the attack key mines a block: the "can not swing and pot" of hcfr-core #34. `continueAttack` mines only `!player.isUsingItem()` (`:1636`). 1.7.10 had neither gate. | `UseItem`/`UseItemOn` while mining (pots thrown while swinging at the ground). Probably also mining packets (`START/STOP/ABORT_DESTROY_BLOCK`) and a `Punch` every tick while blocking or eating with attack held on a block. Attack clicks while using stay discarded: 1.7 discarded them too (`isUsingItem` branch, `Minecraft.java:1965-1977`). |

### 3.3 Lunar mods that touch input, movement or the camera

Option names come from the Apollo API's mod-setting classes [src]. Behaviour is [inf] unless tagged.

- **1.7 Visuals** (`one-seven-visuals`). HCFR forces `enabled`. Two sub-options stay the player's (API defaults
  true):
  - `use-item-while-digging`: "1.7: Right-clicking while breaking blocks will stop the block break". That reads like a
    behaviour change, not a visual: use while mining, with the break aborted.
  - `always-swing`: "Provides better visual feedback when attacking while keeping vanilla behavior". Lunar's March
    2025 recap: the sword swings "even while your hits are being delayed", "nearly identical to the way 1.7
    Animations work on Badlion" [doc].

  A 26.3 client swing sends no packet (`player.swing(hand, anim, false)`; `Punch` is written only by
  `startAttack`/`continueAttack`, `Minecraft.java:1646-1647, 1716-1717`) [src], so a visual swing adds nothing to the
  wire [inf].
- **Toggle Sneak** (`toggle-sneak`): `toggle-sprint`, `sprint-keybind-override`, `toggle-sneak`,
  `sneak-keybind-override`, `toggle-sneak-container`, `double-tap`, `fly-boost`, `fly-boost-amount`. Game v2.22.40
  added "an option for servers to restrict fly boost outside creative mode" [doc]; it is not in API 1.2.9.
- **Zoom** (`smooth-camera`, `camera-sensitivity`, ...), **Freelook**, **Snaplook**. Zoom changes how the player's own
  rotation moves. Freelook and Snaplook move the camera, not the player's rotation, so attacks and move packets keep
  the player rotation [inf].
- **Smooth Teleport** (a misc option in the release notes; not in API 1.2.9) [doc]. "Making the world slide" suggests
  a camera effect; whether it also moves the player: [?].
- **Hitbox** ("hittable" colour), **Hit Color**, **CPS**, **Keystrokes**, **Momentum**, **Knockback Trainer**: HUD and
  render [inf].

### 3.4 Apollo modules that add traffic or change rules

- **Packet enrichment** (off by default; enabled in Apollo's `config.yml`; hcfr-core does not enable it). The client
  sends extra `lunar:apollo` messages on attack, item use, bucket use, inventory open and close, and chat open and
  close. The attack message carries attacker and target `PlayerInfo` (UUID, world, x/y/z as doubles, yaw, pitch,
  forward and strafe input), the distance and a client timestamp [src]. It is useful to verify the mirror (§9.4).
  Client-reported data is never evidence for a verdict.
- **Server rules** (`competitive-game`, `disable-shaders`, `crystal-optimizer`, `disable-chunk-reloading`, ...): none
  touch PvP movement or input [src].
- **Staff mods** (BHop and Staff Nametags added in Oct 2026) are active only when the server enables them [doc]. HCFR
  doesn't.

## 4. Other clients and mods

| Client or mod | 26.3 status | What matters |
|---|---|---|
| Badlion | Officially up to 1.21; no 26.x notes [doc] | Can't join natively; needs Via (not deployed). Its "Badlion Mod API" has a server-controlled "No Hit Delay" [doc]. |
| Feather | Acquired by InPvP, users moved to Dawn (dawn.gg) [doc]; versions not published | Fabric family; Forge and Fabric mods can be added [doc]. No server-controlled combat option known. |
| LabyMod 4 | [?] | Fabric-style mod platform; treat like Fabric. |
| OptiFine | Newest known build 26.1.2; 26.2 and 26.3 not seen [doc] | Fast Math replaces sin/cos with a less precise table. GrimAC supports it for every 1.8+ client by trying both tables and switching after a buffer of disagreeing samples (`TrigHandler`) [src]. |
| Sodium, Iris, ImmediatelyFast, FerriteCore | yes | Rendering and memory [inf]. |
| Lithium | yes [inf] | `CompactSineLUT` is "bit-for-bit identical" to `Mth`'s table and checks every entry at load [src]. But it indexes with `(int)(d * 10430.378350470453) & 0xFFFF`, where 26.3 uses `(long)` (`Mth.java` `sin`/`cos`) [src]. Same result unless the angle times 10430.378 reaches 2^31: past about 11.8 million degrees of yaw (32 768 turns). Its collision rewrites aim at vanilla behaviour [inf]. |
| EntityCulling (tick culling) | yes | On 26.3 a culled entity's reduced tick runs `setOldPosAndRot`, `aiStep`, then `getInterpolation().interpolate()` [src]. `RemotePlayer.aiStep` never moves the entity [src], so remote positions follow vanilla. The skipped `tick()` only skips local bookkeeping such as pose recompute [inf]. |
| Click Between Ticks | yes | Per its description it does not move attacks or digging between ticks; it exposes an input event other mods may use [doc]. |
| Mouse Tweaks, Item Scroller, Inventory Profiles Next | yes | Container clicks inside screens: fine. Auto-refill while walking is `CLICK_WHILE_MOVING` (intended, IN-5). Packets from tick events: L10. |
| Debugify-style bug-fix mods | yes | Client-only fixes of vanilla bugs, some of them in movement, diverge from the port [inf]. |
| Tweakeroo-style utilities | yes | Placement tweaks (not on the crosshair) are refused as predictions; movement tweaks are corrected. That is by design. |

## 5. Where the authority is strict

These are the surfaces a non-vanilla client can trip. All are [src] in `auth/`.

- **Kick** in enforce (`AuthPlayer.protocolViolation`, message "Invalid transaction replies"):
  - a second `PlayerInput`, sprint `PlayerCommand` or move claim in one window (`ClientStream.SECOND_CLAIM`);
  - a pong after the window's first non-GUI packet (`ClientStream.recordAck`, "a pong after the tick's keybinds");
  - skipped pings, and a pong for a ping never sent;
  - more than 512 packets in one window;
  - non-finite moves, forged correction accepts, and server teleports never accepted.
- **Dropped and counted** in enforce (`InputRules`):
  - `ATTACK_WHILE_USING`, `USE_WHILE_USING` (an attack or use packet while the client is using an item);
  - `ATTACK_AFTER_USE`;
  - `ATTACK_AFTER_CLOSE`;
  - `CLICK_WHILE_MOVING`;
  - `AFTER_MOVE`.

  The client's own prediction still runs, so a dropped packet causes no correction. `stats.grammar-violations` (3)
  raises `AuthInputAnomalyEvent`. Only `ServerboundAttackPacket` counts as an attack: `Punch` and `PlayerAction`
  (digging) are "other".
- **Counted only** (`ClientStream` `OUT_OF_ORDER`): a category out of order, a GUI packet after the tick group started,
  and a use after a release in one window.
- **Model**, where a wrong guess shows up as corrections:
  - the movement port: vanilla trig, collision, and sprint from the `Input` sprint bit or a double tap with
    `sprintWindow` 7. The sprint command is only cross-checked (`PortSimulation.sprintMismatches`, not shown by
    `inspect`).
  - use prediction from the item alone (`ClientView.predictsUse`; no `isDestroying` gate);
  - dig and place predictions admitted on the crosshair and the held item (`AuthPlayer.runPacket`, `onCrosshair`; no
    `isUsingItem` gate).
- **Hits**: `RemoteMirror` is vanilla's `SteppedInterpolationHandler`; `PickRay` is vanilla's pick from the truth eye.

Side finding (vanilla, not Lunar): a vanilla client standing in a portal with a container open sends `ContainerClose`
from `aiStep` (report 02 §2.5, window part (d)). `ClientStream` classes every `ContainerClose` as GUI, so this one is
`OUT_OF_ORDER` (counted). It needs classifying by position (N8).

## 6. The matrix

Owners as in `pvp-issues.md`: **netcode** (`auth`, `auth.net`, `auth.input`, `auth.view`), **sim** (`auth.sim`),
**combat** (`auth.combat`, `combat`), **config**, **core** (hcfr-core). Tests refer to §9: R = automated, S = manual
Lunar session.

| # | Client / feature | What changes on the wire or in physics | Effect on the authority | Required change | Owner | Test |
|---|---|---|---|---|---|---|
| L1 | Lunar, Apollo `disable-miss-penalty` (**on**) | [inf] No `missTime = 10` on a miss: after a whiff with the button down, clicks vanilla eats are sent. [?] whether the dropped first click after a screen goes too. | Miss part: none (no rule models it; the server can't see the button). Screen part, if it goes: refill-then-hit in one tick is `ATTACK_AFTER_CLOSE`, dropped (as vanilla would) and counted; 3 counts make a staff alert. No correction (the client's attack prediction still runs). | Profile (N1-N3). Done 2026-10-22: `ATTACK_AFTER_CLOSE` off while the server has `missPenaltyDisabled` on for the player (N5), so the click is neither dropped nor counted; S4 still tells whether Lunar lands it. Fairness decision (K3). | netcode, core | S3, S4, R1 |
| L2 | Lunar, Apollo `allow-dig-and-use` (**on**) | [inf] `UseItem`/`UseItemOn` while mining. Mining packets and `Punch` every tick while using with attack held on a block. Attack clicks while using still discarded. | None found: no gate on use while destroying, dig predictions admitted while using, `InputRules` ignores `PlayerAction`/`Punch` [src]. | None now. Any future dig/use rule (for example "use while destroying") must be off for this profile (N5). | netcode | S5, S6, R1 |
| L3 | Lunar, Apollo `disable-block-miss-penalty` (off) | [?] | As L1 if turned on. | Keep off, or run S3 with it on first. | core | S3 |
| L4 | Lunar 1.7 Visuals (**forced on**) | `always-swing`: visual, no packet [inf]. `use-item-while-digging`: [?] behaviour like L2, chosen by the player. 1.7 sneak animation: [?] camera only, or the entity's eye height. | None if visual. A player-chosen L2 behaviour is fine today (L2). An entity eye change would wrongly reject crouched attacks. | Core pins `use-item-while-digging` to `allow-dig-and-use`, so the server, not the player, decides (K1). PickRay eye variant only if S7 proves an eye change (C2). | core, combat | S6, S7 |
| L5 | Lunar Toggle Sprint / Toggle Sneak | [?] whether toggles set the key state (the `Input` bit, as vanilla's toggles do, report 02 H14) or sprint directly. `toggle-sneak-container` keeps shift in screens. [?] whether the mod ever sends its own `PlayerInput`/sprint command. | (a) Sprint without the sprint bit and without a double tap: the simulation doesn't sprint, so corrections. (b) An extra input or sprint command in a window: kick. (c) Shift or sprint kept in screens: fine, `InputRules.walking` ignores both. | (a) Sim takes a legal client `START_SPRINTING` as a bounded branch (S2 below). It also covers vanilla players with `sprintWindow` 8-10. (b) Rate-limited kick (N4). `inspect` shows sprint and flight mismatches (N6). | sim, netcode | S2 |
| L6 | Lunar fly boost | [inf] Flying speed multiplied while sprint-flying. | Constant corrections for any flying player (staff `/fly`, hub, creative). | Core forces `toggle-sneak.fly-boost` off through mod settings, or Lunar's newer server option once Apollo exposes it (K1). No sim support. | core | S9 |
| L7 | Lunar Zoom (smooth camera, zoom sensitivity), Freelook, Snaplook | Zoom changes how the player's rotation moves; Freelook and Snaplook move the camera [inf]. | Authority: none (rotation is input). `InputStats` yaw-GCD misses rise: false `aim-gcd` alerts (IN-4). | `InputStats` tolerates a second GCD and skips smoothed stretches (N7); optionally core forces zoom `smooth-camera` off (K1). | netcode, core | S10 |
| L8 | Lunar Smooth Teleport | [?] Camera only, or the player's position too. | If position: every pearl and teleport misses ("accepts off"), and every correction converges slowly, then goes absolute. | If S8 shows it moves the player: core forces it off once Apollo exposes it; until then, document it as unsupported. | core | S8 |
| L9 | Lunar extra serverbound traffic | `minecraft:brand`, `minecraft:register`, `lunar:apollo` handshake, packet enrichment if enabled [src]. Lunar's ping HUD may use `PingRequest` [?]. | None: custom payloads skip the window [src]; `PingRequest` is not our pong. | None. | — | S0 |
| L10 | Keybind-type packets written outside the tick (Lunar features [?]; Fabric mods that use tick or input events) | A `PlayerAction` (drop, swap), `SetCarriedItem`, `UseItem` or `Attack` written from a key callback, the render loop, or Fabric `END_CLIENT_TICK` (which runs after `TickEnd`). It lands at the start of the next window, before that frame's drain. | The window's tick group starts early, and the next pong in it is "a pong after the tick's keybinds": **kick** in enforce. Chance per press is roughly the share of frames that drain a pong (20 per second over the frame rate) [inf]. Attacks sent there are judged with tick T's rotation and eye, so they may be refused. | N4: an isolated late pong is counted, not kicked; it keeps its window, with entries applied at its arrival position, as today. Kick only past a sustained rate (pong manipulation happens every tick). | netcode | S11, S2, live |
| L11 | Lunar interpolation of other players | [?] Release notes since 26.3 show no change; Turbo Entity Mode is a renderer [doc]. If Lunar patched the 26.3 stutter (MC-311974/976), the K2 feed keeps vanilla's queue full, so restart paths rarely run (report 03 §5-6): differences would show only at pairings and after lag spikes. | Hits wrongly rejected or accepted at box edges. | None unless S7 shows rejections; then a `RemoteMirror` variant chosen by profile (C1). Verify with packet enrichment (§9.4). | combat | S7, S14 |
| L12 | Sodium (bundled with Lunar) | Render only [inf] | None | None | — | R2 |
| F1 | Lithium sine table | `int` vs `long` index cast [src] | Past about 1.18e7 degrees of yaw, every moving tick diverges. Rare: an absolute-rotation teleport or a respawn resets yaw. | A `SimMath` Lithium variant picked by bounded branching, or a server yaw re-wrap (S1). | sim | R2, unit |
| F2 | Lithium collisions, other optimisers | Aim at vanilla behaviour [inf] | Any difference shows as corrections. | Golden traces on a Fabric rig with these mods (R2). | sim | R2 |
| F3 | EntityCulling tick culling | Interpolation still advances on 26.3 [src] | Low | None | — | R2, S7 |
| F4 | OptiFine Fast Math, fast-math mods | Different sin/cos table [src: Grim] | Corrections on most moving ticks: rubber-banding. | Policy: support (S1, a second table with Grim-style selection) or unsupported (documented; such players get corrections). | sim, config | R3 |
| F5 | Bug-fix mods (Debugify-style) | Client-side movement fixes [inf] | Corrections | Unsupported by design; player FAQ. | config | — |
| F6 | Inventory mods | Container clicks in screens; auto-refill while walking | Fine; auto-refill while walking refused (IN-5, intended). | None | — | — |
| O1 | Badlion | No 26.x | Can't join without Via | — | — | — |
| O2 | Feather/Dawn, LabyMod | Fabric family [?] | As the F rows, plus their own toggle sprint and zoom (L5, L7 equivalents) [?]. | Profile by brand (N1); same tests. | netcode | S-series on that client |
| V1 | ViaVersion/ViaRewind (Lunar 1.8.9 and others) | Another protocol | Off authority, client-trusted (CL-2, §10). | §10 | config, netcode | §10 |
| G1 | Geyser/Floodgate | Bedrock | Off authority (CL-2, §10). | §10 | config | §10 |

## 7. Required changes, per owner

### netcode

- **N1. `ClientProfile` on `AuthPlayer`.** Fields:
  - `brand`: vanilla, lunar, fabric, forge, other, from `ServerCommonPacketListenerImpl.clientBrand`;
  - `apollo`: the player registered `lunar:apollo`;
  - `rules`, the server-declared rule set: `missPenaltyDisabled`, `blockMissPenaltyDisabled`, `digAndUse`,
    `useItemWhileDigging`, each with the window it became active in;
  - `mods`: Lunar's installed-mods list (from the Apollo handshake);
  - `trig`: `VANILLA` (default), `LITHIUM`, `FAST_MATH`, detected or declared.

  Refresh it at the first window, on `PlayerRegisterChannelEvent` for `lunar:apollo`, and every 20 ticks while Apollo
  is present.
- **N2. Apollo reader in `auth.view`, by reflection, as `Eligibility` reaches ViaVersion and Floodgate.**
  - The rules: `Apollo.getModuleManager().getModule(CombatModule.class).getOptions().get(apolloPlayer, option)`.
  - The mods: `ApolloPlayer.getInstalledMods()`.

  No compile dependency and no core change for the rules. Apollo's values are what the server sent, so they are
  server truth. Only "this client is Lunar" is client-claimed.
- **N3. Rule transitions: relax early, tighten late.**
  - A relaxed rule (for example no `ATTACK_AFTER_CLOSE`) applies from the window in which the reader first sees it.
  - A tightened rule applies only from the window after the first pong that follows the change, plus the ack deadline.

  An honest client is then never judged by a rule it hasn't received. The cost is at most an RTT of looser rules for
  that one player. (Exact timing would need the outbound `lunar:apollo` payload classified as a transaction; it's not
  worth it for a rule set that changes at login and on `/reload`.)
- **N4. Late pongs and extra claims.**
  - "A pong after the tick's keybinds" stops being an instant kick. It is counted (`violations ... pongs N`), and the
    pong keeps its window and its arrival position. `runWindow` already applies acks in arrival order, so earlier
    packets are judged before the pong's entries.
  - Kick only past `authoritative.protocol-kick-rate` (for example 20 in 60 s). A client that withholds pongs to steer
    when packets land does it every tick; a frame-time key press does it a few times a minute.
  - Treat a second `PlayerInput`/sprint command the same way: count it, keep the last one before the move claim, and
    kick past the rate.
  - Keep the instant kick for pongs after the move claim (impossible for any client that writes from the main
    thread), skipped or unknown pongs, overflow and forged accepts.
  - This changes design §15.1 and needs the maintainer's sign-off.
- **NAS test setup (2026-10-23):** Apollo-Bukkit 1.3.0 on the NAS test server (`/volume2/docker/authoritative`), set as
  HCFR runs it: `disable-miss-penalty: true`, `disable-block-miss-penalty: false`, `allow-dig-and-use: true` (MCStats
  uploads off; the generated file kept as `config.yml.default`), and a NoAI husk "Dummy" at the arena centre (0, 65,
  -1000) to hit, for the user's recorded Lunar session (S3-S6).
- **N5. `InputRules` reads the profile.**
  - `ATTACK_AFTER_CLOSE` is off when `missPenaltyDisabled` (built 2026-10-22, `InputRules.screenEatsClick`, read each
    window from the profile; unit `InputRulesTest.anAttackAfterACloseIsFineWhenTheServerTurnedTheMissPenaltyOff`).
    It didn't wait for S4: if Lunar keeps vanilla's screen drop, the rule never fires for those players anyway, and if
    Lunar lands the click, the server that pushed the option meant it to land. A client faking Lunar gains one click a
    tick earlier after a screen. S4 still tells which of the two Lunar does.
  - `ATTACK_WHILE_USING`, `USE_WHILE_USING`, `ATTACK_AFTER_USE`, `CLICK_WHILE_MOVING` and `AFTER_MOVE` stay for every
    profile. They hold under 1.7 semantics and under every Apollo option as documented. Relax one only on S6 evidence.
  - Any new dig/use rule is off for `digAndUse` or `useItemWhileDigging`.
  - Violations of a rule that the profile relaxed for a recent transition count separately and never feed
    `AnomalyWatch.INPUT_GRAMMAR`.
- **N6. `inspect` and HUD.** `inspect` gets:
  - a profile line, for example "client lunarclient:...; apollo combat: miss-penalty off, dig-and-use on (since window
    N); mods: ...; trig vanilla";
  - the sprint and flight mismatch counters (they exist in `PortSimulation` but aren't printed);
  - the stream-violation reasons by name;
  - optionally `inspect <player> reset`, for per-scenario deltas.
- **N7. `InputStats`.** The yaw-GCD fit accepts up to two GCDs per session (normal and zoom sensitivity), and skips
  turns whose second difference is under the GCD (smoothed camera). This is detection only, and the thresholds still
  need real data (IN-4).
- **N8. Portal `ContainerClose`.** A `ContainerClose` after the window's keybinds and before `PlayerInput`/move is the
  `aiStep` close (report 02 §2.5): class it `AISTEP`, not GUI. This is a vanilla fix that costs nothing.
- **N9 (CL-2).** `PathWiring.onPaired` puts every viewer in `exact-radius` on the K2 feed without checking the
  viewer's own eligibility [src]. A ViaVersion or Geyser viewer then gets 20 stepped moves per second through its
  translator. That is probably harmless, but untested. Either feed K2 only to viewers whose `AuthPlayer` is eligible,
  or add a Via-viewer case to the CL-2 proof.

### sim

- **S1. Trig variants.**
  - `SimMath.sin/cos` gets `VANILLA` (`long` cast) and `LITHIUM` (`int` cast). Both share the table, so `LITHIUM`
    matters only past 2^31 index units. It is selected from the profile's mod list (`lithium` in Lunar's handshake),
    or by a bounded branch when the claim matches only the other variant.
  - The cheaper alternative: the server re-wraps yaw. When |yaw| > 1e6 degrees, send a relative
    `ClientboundPlayerRotationPacket` of −n·360. It is exact in float (Sterbenz), modelled like any rotation packet,
    and keeps every client in the range where both casts agree.
  - If the F4 policy is "support": add OptiFine's fast table as `FAST_MATH`, with Grim's selection rule (try both on a
    divergence; switch after k agreeing ticks; bounded branching as in §15.4).
- **S2. Sprint by command** (only if S2 in §9 shows L5(a)). In a window with `START_SPRINTING` where
  `canStartSprinting` held but the port did not start sprinting, run the tick also with sprint started. Keep the
  branch that reproduces the claim, the non-sprinting one when there is no claim (the §15.4 bounded-branching rule).
  This replaces `sprintMismatches` as a silent counter for that case.
- **S3. A Fabric variant of the rig client** (R2): launch the 26.3 client through Fabric Loader with Lithium, Sodium,
  EntityCulling, FerriteCore, ImmediatelyFast and ModernFix. Run `gym/check-traces.py`, `harness/sim-replay.sh` and
  the enforce scenarios.

### combat

- **C1. Mirror check against Lunar.** On a staging server only, turn on packet enrichment's attack packet. A probe
  plugin logs, per attack, Lunar's attacker and target `PlayerInfo` next to `AttackerSnapshot` and the `MirrorBook`
  box at that tick. Positions must match (bit for bit if Lunar reports tick positions, otherwise within one partial
  tick). Only on a mismatch: port Lunar's interpolation as a `RemoteMirror` variant chosen by the profile.
- **C2. Eye height.** Only if S7 shows crouch-only rejections for Lunar and not for vanilla: find out whether Lunar
  moved the entity's eye (`getEyeHeight`) or only the camera. Port the eye into `PickRay` as a profile variant only in
  the first case.
- **C3. Fairness (HR-9).** With `disable-miss-penalty`, Lunar players land clicks vanilla players lose after a whiff.
  neue's click buffer (HR-6, IN-1) shrinks that gap for everyone. The decision is K3.

### core (hcfr-core)

- **K1. Pin the input-affecting mod settings through `ModSettingModule`** (as `ModOneSevenVisuals.ENABLED` is today):
  - `ModToggleSneak.FLY_BOOST = false`, everywhere authority runs;
  - `ModOneSevenVisuals.USE_ITEM_WHILE_DIGGING = allow-dig-and-use`, so the server decides;
  - optionally `ModZoom.SMOOTH_CAMERA = false` (aim stats).

  Use the newer fly-boost and Smooth Teleport server options once an Apollo update exposes them, and bump the pin in
  `deploy/lib/artifacts.sh`, `scripts/common.sh` and `pom.xml` together.
- **K2. Log the Apollo handshake per player** (Lunar version, installed external mods) to the console or a staff
  command. It is the record staff need when one player's numbers look odd.
- **K3. Decide the fairness policy on authoritative servers.**
  - (a) Keep `disable-miss-penalty` and `allow-dig-and-use` on (today's Arena and Factions default), with the
    authority following the profile. Vanilla-launcher players keep the 26.3 miss penalty and can't dig-and-use.
    Publish that.
  - (b) Turn `disable-miss-penalty` off: everyone plays vanilla 26.3 click rules, and the "1.8 hit delay" complaint
    returns (hcfr-core #34).
  - Recommendation: (a), plus neue's click buffer when it ships. Keep `allow-dig-and-use` on: it is what fixes "swing
    and pot", and the authority already allows it.
- **K4.** Keep ViaVersion and Geyser off servers that run authority, or use the CL-2 policy (§10).
- **K5.** Packet enrichment stays off in production. It is turned on only on a staging server for C1.

### config (`revival.yml`)

```yaml
authoritative:
  client-profiles:
    apollo-rules: follow        # follow (read Apollo's per-player options) | ignore (vanilla rules for everyone)
    unknown-mods: enforce       # enforce | observe: players whose brand is not vanilla or lunar
  protocol-kick-rate: 20        # late pongs or extra claims per 60 s before a kick (N4); 0 = kick at once (today)
  trig-variants: [vanilla, lithium]   # add fast-math only if the F4 policy is "support"
  ineligible: allow             # CL-2: allow | deny | separate
```

## 8. The client profile, in detail

- **Sources, in trust order.**
  1. What the server sent: Apollo's per-player option values. These are server truth.
  2. What the client claims: the brand, the `lunar:apollo` channel, the Apollo handshake (version, mods).
  3. What the simulation observes: trig variant fits and sprint branches.
- **What a cheat gains by faking Lunar.** Registering `lunar:apollo` and sending a handshake gets exactly the rules
  real Lunar players get from this server, no more. That is parity, not an exploit, provided every relaxed rule is
  bounded to what the option really allows (N5).
- **What never depends on the profile:**
  - movement truth;
  - the hit check (except a proven C1 or C2 variant);
  - the attack cap;
  - the timer;
  - transaction order (except the rate-limited N4 kick).
- **When there is no Apollo** (no plugin, or the reflection fails), the profile is vanilla rules for everyone, which is
  today's behaviour.
- **Timing (N3).** Relax at first sight, tighten one pong plus the ack deadline after the change. Options change at
  Apollo registration (the first second of a session) and on config reload.

## 9. Test plan

### 9.1 Automated (no Lunar needed)

- **R1, an Apollo stand-in in the rig agent.** The rig agent already hooks `Minecraft.tick()` and `handleKeybinds()`
  (`~/Developer/tools/auth-rig/README-enforce.md`). Add switchable hooks that copy the inferred options:
  - `miss-a`: skip `missTime = 10` (`Minecraft.java:1663, 1710`);
  - `miss-b`: `startAttack` ignores `missTime`, the screen drop included;
  - `dig-use`: no `isDestroying()` gate in `startUseItem` (`:1725`) and no `isUsingItem()` gate in `continueAttack`
    (`:1636`).

  New scenarios: `whiff_then_click`, `refill_then_click` (close the inventory and click in the same tick),
  `swing_and_pot`, `block_and_dig`, `blockhit_100`.

  Expect: 0 corrections in every variant, and 0 grammar violations except `attack-after-close` under `miss-b`. That one
  proves the rule fires, and that N5 then silences it for the profile. This is the CL-1 "stand-in" proof, done in the
  agent instead of a separate Fabric mod.
- **R2, the Fabric rig (S3):** 80 gym traces replay bit for bit with Lithium, Sodium, EntityCulling, FerriteCore,
  ImmediatelyFast and ModernFix loaded; the enforce scenarios give 0 corrections.
- **R3, fast math:** the same with a 26.3 OptiFine build or a fast-math mod when one exists. Expect corrections. This
  documents the F4 policy.
- **Unit tests:**
  - `InputRules` per profile, one test per rule and option;
  - relax-early and tighten-late transitions;
  - `SimMath` variants at yaw 1.2e7 degrees;
  - the N4 rate (no kick below it, kick above it, entries applied in arrival order);
  - the Apollo reader with Apollo absent;
  - the N8 portal close.
- **Live (`AuthLiveScenarios`):** a `FakeClient` that writes an `Attack` before the window's pong (the frame-time
  pattern). It is not kicked below the rate and is kicked above it.

### 9.2 Manual Lunar session

Lunar can't be scripted here, so this is a person at the keyboard. The user plays on Lunar (26.3 profile, as
Chayne); a staff member reads the server.

**Server setup (staff):**

1. A RevivalPaper build from `revival/authoritative` (`./gradlew createPaperclipJar`). Use one of:
   - **Quick:** the rig server `~/Developer/hcfr-servers/auth-gym`, with the pinned `apollo-bukkit-1.2.9.jar` (from
     hcfr-core's `deploy/lib/artifacts.sh`) in `plugins/`. Set the combat module's `disable-miss-penalty: true`,
     `allow-dig-and-use: true` and `disable-block-miss-penalty: false` in `plugins/Apollo-Bukkit/config.yml` (the
     option nodes are the config keys [inf]).
   - **Faithful:** hcfr-core Arena on RevivalPaper; its `lunar.yml` defaults push the same options and force 1.7
     Visuals.
2. `config/paper-global.yml`: `collisions.enable-player-collisions: false` (otherwise authority stays at observe).
   Leaf `async-entity-tracker` and `parallel-world-ticking` off. Play at a ping under 150 ms (local or LAN), so the
   rewind cap is not what you measure.
3. `revival.yml`:
   ```yaml
   authoritative:
     mode: observe
     debug:
       hud: true
       hud-interval-ticks: 5
       log: true
     stats:
       anomaly-alerts: true
   ```
   Then `/revival reload`.
4. A target: a second account on the official vanilla 26.3 launcher (anyone can drive it), or the rig client in a long
   walk or `aim` scenario. During fights it stays within 24 blocks (`exact-radius`).
5. Note:
   - the Lunar version;
   - the Lunar settings for 1.7 Visuals (both sub-options), Toggle Sneak, Zoom, Freelook and Smooth Teleport;
   - any Fabric mods added in Lunar.

   The user screenshots the Lunar mods menu and the Fabric mods tab.

**For every scenario:**

- Staff runs `/revival auth inspect Chayne` before and after it. The counters add up from join, so compare the two
  snapshots.
- Staff runs `/revival auth trace start Chayne` and `/revival auth trace stop Chayne` around it.
- The HUD (action bar) reads `O  rtt Nms  exact X%  corr N (n) last D  bank B  hit VERDICT`.
- With `debug.log` on, the console shows `[auth] input grammar ...`, `[auth] hit rejected ...`, `[auth] diverged ...`
  and `[auth] correction ...`.
- Protocol violations under observe are logged only at debug level. Read them from the `violations N (stream S,
  pongs P)` part of `inspect`.

| # | The user does | Staff watch (delta between snapshots) | Pass | If it fails |
|---|---|---|---|---|
| S0 | Join on Lunar. | `/lunarclient Chayne` says "using". `inspect` line 1: `observe`, no "(not eligible ...)", no "players can push it". `transactions: ... skipped 0, duplicates 0`. | All as listed. | Not eligible: protocol or Via detection (§10). |
| S1 | Toggle Sneak mod **off**. 3 minutes of walking, sprinting, sprint-jumping, strafing in circles, stopping and starting, w-tapping, crouch-walking, ladders, water, cobwebs, soul sand, stairs and slabs. | HUD `exact`; `inspect`: `over`, `max`, `flags off`, `violations`, `input grammar`. | `exact` ≥ 99.9 %, `over 0`, `flags off 0`, `violations 0`, `0 broken`. | Divergence with no mod involved: a Lunar physics change (new row). Send the trace. |
| S2 | Toggle Sprint and Toggle Sneak **on** (and `toggle-sneak-container` on). Repeat S1 for 2 minutes. Then 20 times, while moving with both toggled, open the inventory, then a chest, close it, keep moving. | Same as S1, and `violations (stream, pongs)`. | Same as S1; `violations 0`. | `exact` drops only while sprinting: L5(a), so S2 in §7. `violations` > 0: L5(b) or L10 (a kick under enforce). |
| S3 | 5 blocks from the target (out of reach), butterfly or jitter click at the air for 10 s; then step in and keep clicking the target for 10 s. Lunar's CPS mod is visible. | `input grammar`, `attacks ... rejected`. Afterwards, from the trace: `Punch` per second vs Lunar's CPS. | `0 broken`, `rejected 0`. | Grammar counts here: L1 does more than documented. |
| S4 | **Refill then hit.** Stand next to the target (2.5 blocks or closer) with splash pots in the inventory. 30 times: press E, shift-click a pot into the hotbar, close (E or Esc) and left-click the target at the same instant, as tight as you can. Then 30 times with a chest. | `input grammar: ... attack-after-close N`; console `[auth] input grammar Chayne ... attack-after-close`. | `attack-after-close 0` means Lunar keeps vanilla's screen drop: no change needed. | N > 0: Lunar lands the first click after a screen (L1, variant B), so N5 gates `ATTACK_AFTER_CLOSE` by the profile. Control: the vanilla account must show 0. |
| S5 | **Swing and pot.** Look straight down at a solid block, hold left click (mining), and right-click splash pots 20 times; also hold right for 5 s. | `input grammar`, `world view ... predicted`, HUD `exact`. | Pots are thrown while mining (on vanilla they are not: control). `0 broken`, `exact` unchanged. | No pots thrown: Apollo's option didn't reach the client (check the combat module is enabled). Grammar counts: L2 does more than inferred. |
| S6 | **Block and dig, block-hit.** Hold right (sword block) and hold left on dirt or grass for 5 s while walking. Then 100 block-hits on the target (alternate right and left fast). | `attack-while-using`, `use-while-using`, `attack-after-use`; HUD `exact`. | All three 0; `exact` ≥ 99.9 % (the simulation keeps the block slowdown). | `attack-while-using` > 0: Lunar sends attacks while using. Investigate with the trace before relaxing anything. |
| S7 | **Fight**, 5 minutes against the target: strafing, combos, w-taps, sprint resets, crouch-hits aimed at the head, hits at full reach. | `attacks N: accepted, rejected`; HUD verdict never red `INVALID`; console `[auth] hit rejected ... distance D`. | `rejected 0`. | Repeat with the vanilla account. Lunar-only and crouch-only: C2. Lunar-only otherwise: L11, so C1. Both clients: not a client issue. |
| S8 | 20 ender pearls (near and far), 20 `/tp Chayne ~ ~ ~3`, 5 `/tp` about 200 blocks into loaded chunks. Do it with Lunar's Smooth Teleport **on**, then **off**. | `server teleports N (accepts off M)`; HUD `exact` right after each. | `accepts off 0` in both runs; `exact` back to 100 % right away. | `accepts off` > 0 only with Smooth Teleport on: L8. |
| S9 | Staff only, if you can fly (`/fly` or creative): sprint-fly with Lunar fly boost **on**, then **off**. | HUD `exact`, console `diverged`. | Off: `exact` ≥ 99.9 %. On: divergence is expected (confirms L6). | Off still diverges: flight port issue (sim), not Lunar. |
| S10 | 2 minutes of fighting with zoom (smooth camera on), 2 minutes without. | `yaw gcd ... (turns, misses)`; console `[auth] input anomaly ... aim-gcd`. | Reference numbers only. | Feeds N7 and IN-4. |
| S11 | Bind Lunar's Drop Stack key. 30 presses while walking (hotbar), plus any Lunar keybind used in fights (hotbar features, quick swaps). | `violations N (stream S, pongs P)`. | `pongs 0`, `stream 0`. | `pongs` > 0: L10. Under enforce today that is a kick, so N4 first. |
| S12 | Staff runs `/revival auth mode enforce`. Repeat S1, S2, S4, S6, S7 and S8, shortened. | HUD `corr`; `inspect` `corrections`; any disconnect. | `corrections 0`, no "Invalid transaction replies" kick, grammar `dropped` equals the observe `broken`. | Kick: send the console line (`... broke the client protocol under authority: <reason>`). |
| S13 | Vanilla control: the vanilla account runs S3-S6. | As above. | Vanilla shows 0 everywhere, and no pots while mining. | — |
| S14 | Optional, staging only: packet enrichment's attack packet on, with C1's probe plugin. 200 hits on a moving target. | The probe log: Lunar's target position vs the mirror box per attack. | Equal (§7 C1). | L11: port the difference. |

**Afterwards, send:** `logs/latest.log`, the `traces/<uuid>/*.rvtr` files, the `inspect` snapshots, the Lunar
screenshots and Lunar's version.

**CL-1 is proven** when S1-S8 and S11 pass under observe, S12 passes under enforce, and R1 passes. That is the
`pvp-issues.md` CL-1 proof: 0 grammar violations and 0 corrections.

### 9.3 Reading the results

| Seen | Means | Change |
|---|---|---|
| `attack-after-close` > 0 on Lunar only | Lunar's `disable-miss-penalty` removes the screen drop | N5 gate; until then, don't act on `INPUT_GRAMMAR` alerts for Lunar players |
| `violations ... pongs` > 0 | Packets written outside the tick (L10) | N4 before enforce goes live |
| `violations ... stream` > 0 with S2 toggles | An extra input or sprint command (L5(b)) | N4 rate, or core disables the offending mod setting |
| `exact` drops while sprinting with toggles | L5(a) | S2 (sim) |
| `accepts off` > 0 with Smooth Teleport | L8 | K1 (off) or "unsupported" in the player FAQ |
| `rejected` > 0, Lunar only | L11 or L4 | C1 or C2 |
| Divergence with every Lunar mod off | An unknown Lunar physics change | New matrix row; trace to the sim owner |

### 9.4 Mirror verification with packet enrichment (S14, C1)

Apollo's attack message gives, per attack, where Lunar thinks the attacker and the target are (doubles, yaw, pitch).
`MirrorBook` gives where the server thinks Lunar drew the target at that tick. Agreement proves the mirror against
Lunar specifically, which the vanilla rig cannot. Disagreement localises the difference (constant offset: a different
tick convention; offsets only after pairings: a stutter fix in Lunar). This is staging-only, and the data is never used
for verdicts.

## 10. ViaVersion and Geyser (CL-2)

- **Today:**
  - `auth/Eligibility.java` turns authority off for a client whose ViaVersion protocol differs from the server's, and
    for Floodgate (Bedrock) players. They play under Paper's lenient checks with client-trusted movement (fail-open).
  - hcfr-core deploys neither plugin, so no such player can join HCFR today [src].
- **Who it would be:** mostly Lunar 1.8.9 and Badlion 1.8.9, the classic HCF clients (Badlion has no 26.x [doc]), and
  Bedrock players through Geyser. Their rules differ by nature: the 1.8 pick margin, 1.8 interpolation, no `Input`
  packet, no `ClientTickEnd`. Authority can't model them without a second port per protocol.
- **Policy (config):** `authoritative.ineligible: allow | deny | separate`, as in CL-2. On authoritative servers,
  `deny`, or route those players to non-authoritative arenas (`separate`). `allow` keeps them client-trusted next to
  authoritative players, which is a fairness hole in both directions.
- **Viewer side (N9):** ineligible viewers within 24 blocks still get K2 stepped moves, without pings or bundles,
  through their translator. Test it with a Via 1.8 viewer before allowing Via anywhere.
- **Proof (to add, from CL-2):** a join test per policy with a Via-translated client and a Geyser client, plus the N9
  viewer check.

## 11. Open questions

- Lunar's real implementation of each Apollo combat option on 26.3 (S3-S6 answer it; Lunar's developer Discord might
  confirm it).
- Whether Lunar's 1.7 Visuals sneak animation touches the entity's eye height (S7, C2).
- Whether Lunar's toggles, Drop Stack or other keybind features write packets outside the tick (S2, S11).
- What Smooth Teleport moves (S8).
- Whether a 26.3 OptiFine ships with Fast Math (F4 policy).
- Apollo versions after 1.2.9: the fly-boost restriction and any Smooth Teleport option (K1).

## Sources

Code and binaries read:

- Vanilla 26.3 client, decompiled: `~/Developer/tools/mc-src/src-26.3/net/minecraft/client/Minecraft.java`
  (1631-1789, 1817, 1965-2002), `util/Mth.java` (`sin`, `cos`), `client/multiplayer/MultiPlayerGameMode.java`
  (153-235), `client/player/RemotePlayer.java`, `server/network/ServerGamePacketListenerImpl.java` (`handlePunch`).
- Apollo API 1.2.9 (`javap`): `module.combat.CombatModule`, `mods.impl.ModOneSevenVisuals`, `ModToggleSneak`,
  `ModZoom`, `ModFreelook`, `ModSnaplook`, `module.packetenrichment.*`, `module.serverrule.ServerRuleModule`,
  `event.player.ApolloPlayerHandshakeEvent`, `player.ApolloPlayer`, `option.Options`.
- Apollo source: https://github.com/LunarClient/Apollo (`platform/bukkit/.../listener/ApolloPlayerListener.java`,
  `command/BukkitLunarClientCommand.java`).
- Lithium `CompactSineLUT`:
  https://github.com/CaffeineMC/lithium/blob/develop/common/src/main/java/net/caffeinemc/mods/lithium/common/util/math/CompactSineLUT.java
- EntityCulling `ClientWorldMixin`:
  https://github.com/tr7zw/EntityCulling/blob/main/src/main/java/dev/tr7zw/entityculling/mixin/ClientWorldMixin.java
- GrimAC `TrigHandler`:
  https://github.com/GrimAnticheat/Grim/blob/2.0/common/src/main/java/ac/grim/grimac/utils/math/TrigHandler.java
- hcfr-core: `arena/.../lunar/*`, `factions/.../lunar/*`, `deploy/lib/artifacts.sh`, `deploy/lib/render.sh`,
  `scripts/common.sh`, `pom.xml`; issue #34.
- This repo: `auth/AuthPlayer.java`, `auth/net/ClientStream.java`, `auth/input/InputRules.java`,
  `auth/view/ClientView.java`, `auth/view/PredictionRules.java`, `auth/PortSimulation.java`,
  `auth/sim/MovementSim.java`, `auth/PathWiring.java`, `auth/Eligibility.java`, `command/AuthCommand.java`,
  `auth/DebugHud.java`.

Docs and notes:

- Apollo combat module: https://lunarclient.dev/apollo/developers/modules/combat
- Apollo packet enrichment: https://lunarclient.dev/apollo/developers/modules/packetenrichment
- Apollo events: https://lunarclient.dev/apollo/developers/events
- Lunar release notes (mirror): https://releasebot.io/updates/lunarclient (26.3 support in v2.22.41; Smooth Teleport,
  fly-boost option, 1.7 Visuals fixes)
- Lunar recaps: https://www.lunarclient.com/news/lunar-client-monthly-recap-12-march-2025 (Always Swing),
  https://lunarclient.com/news/lunar-client-monthly-recap-11-february-2025 (combat module on modern versions),
  https://www.lunarclient.com/news/lunar-client-monthly-recap-june-2026
- Lunar performance guide (Sodium settings): https://support.lunarclient.com/support/solutions/articles/60000764858-fps-issues
- Brand convention: https://hangar.papermc.io/Atomatrix/ClientBrand
- Badlion versions and notes: https://www.badlion.net/faq , https://releasebot.io/updates/badlion
- Feather to Dawn: https://feathermc.com/ , https://dawn.gg/
- OptiFine 26.x status: https://texture-packs.com/de/mods/optifine/
- Click Between Ticks: https://www.curseforge.com/minecraft/mc-mods/click-between-ticks
- EntityCulling: https://modrinth.com/mod/entityculling
