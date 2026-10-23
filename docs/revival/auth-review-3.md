# Code review round 3: the Ponytail audit

A Ponytail audit (report only) of the authoritative-server code at `316a191f` (2026-10-23). The report follows verbatim; line numbers are at `316a191f`. The fixes, each checked against the code first, are in the table at the end.

## The report

What this repo does: RevivalPaper is HCF Revival's Minecraft 26.3 server (a Leaf/Paper fork) that plays 1.7.10 PvP. The `revival/authoritative` branch (audited at `316a191f`: `leaf-server/.../auth/**`, patches 1011-1018, and the auth parts of `combat/` and `config/`) makes the server decide where players are and whether hits land: it holds each client tick's packets (a "window") until the tick ends, re-runs the player's movement with a port of the client physics, corrects clients that stray, sends viewers exact paths of other players, and judges each hit against what the attacker's screen showed.

Load assumed: up to 200 players on one map, 20-50 in one fight, 20 ticks a second, 12-hour sessions, mostly Lunar and vanilla clients on home internet (jitter, stalls). A kick counts as a combat-logger death.

Words used below: a *claim* is the position a client says it is at; a *pong* is the client's answer to one of our pings; *enforce* is the mode where the simulation is the truth; an *exempt* tick is one the port does not simulate, so vanilla's own move handler takes the claim.

## Must fix

1. **Every join opens a 1.5-second fly window** (`leaf-server/src/main/java/gg/hcfrevival/revival/auth/AuthPlayer.java:L1913-1927`, `L2595-2638`, `L2747-2749`)
   - **What this is:** Until a client answers its first ping after authority starts ("warm-up", at every join), each move it claims goes to Paper's normal move handler, and its last two claims later set the simulation's starting velocity.
   - **Problem:** A cheat client sends PlayerLoaded and holds every pong for ~1.4 s, just under the freeze at `max-unacked-ms`: Paper only checks "moved too quickly" (about 10 blocks a tick), so it flies ~28 ticks x 10 blocks over open air, then answers mid-air and the truth starts with up to `seed-max-move` (10 blocks a tick) of velocity. The classic audit lists warm-up as "bounded" by the 1.5 s, but that bound is ~280 blocks of flight, and no cheat row tries it (the suites wait 15 ticks before cheating).
   - **Fix:** Treat warm-up like not-loaded: in enforce, don't replay a move when `lastExempt == EXEMPT_WARMUP` (L1917-1918), and clear the seed pair for warm-up windows next to NOT_LOADED/REFUSED (L2622). An honest client is warm one round trip after authority starts, so the cost is one correction for a player who was moving when authority started mid-session.
   - **If we skip it:** A trapped player relogs and flies out over the walls.

2. **Logging in on a boat or horse gets around `vehicles: deny`** (`leaf-server/src/main/java/gg/hcfrevival/revival/auth/Authority.java:L296-303`, `leaf-server/src/main/java/gg/hcfrevival/revival/auth/AuthPlayer.java:L486-511`)
   - **What this is:** With `authoritative.vehicles: deny` (the default), authority cancels mounts for players under enforce, because a ridden vehicle moves where the client says.
   - **Problem:** The check runs only at mount time and only once the mode is enforce. At login, vanilla puts the player back on the vehicle it logged out on (`ServerPlayer.loadAndSpawnParentVehicle` calls `startRiding(vehicle, true)`) before authority starts at the first TickEnd, and nothing dismounts it later: riding is exempt, so only vanilla's vehicle checks apply and boat speed (and short boat-fly) work under enforce for the whole ride (the same for a mount made while in observe).
   - **Fix:** In `switchMode`, when the resulting mode is ENFORCE and `vehicles` is `deny`, call `player.stopRiding()` (one line). Add a cheat row: relog in a boat, claim 3 blocks a tick.
   - **If we skip it:** "Sit in a boat and relog" brings back every vehicle cheat under enforce.

3. **Players outside authority can't be hit while they stand still** (`leaf-server/src/main/java/gg/hcfrevival/revival/combat/TruthPaths.java:L24-43`, `leaf-server/src/main/java/gg/hcfrevival/revival/auth/combat/HitValidator.java:L77-84`, `leaf-server/src/main/java/gg/hcfrevival/revival/auth/AuthPlayer.java:L2207`)
   - **What this is:** When the box an attacker saw is older than its rewind cap (its RTT plus about 150 ms), the judge asks `TruthPaths` where the target was at the capped age; `TruthPaths` only knows players under authority.
   - **Problem:** A player-type entity outside authority (an NPC player such as a Citizens or player-type combat logger, or a ViaVersion/Geyser player under the default `ineligible: allow`) gets no tracker packet while it stands still and doesn't turn, apart from a resync every 60 ticks, so its box ages past every cap: each hit is INVALID REWIND, which enforce drops for players, and the target is hittable only for a few hundred ms after each 3-s resync. Review 1 confirmed this (stale-stamp-stationary-targets, never closed); only its mirror half landed (`RemoteMirror.refreshTargetStamp`), and nothing sends the keep-alives it waits for.
   - **Fix:** Past the cap, judge a target outside authority at its current position when it is still within the tracker's send threshold of what its tracker last sent (`ServerEntity.getPositionBase()`), the way `AuthPlayer.stillAt` does for players under authority (about 10 lines in `TruthPaths` or `CombatWiring.judge`).
   - **If we skip it:** A standing combat logger, Bedrock or Via player is close to unhittable under enforce.

4. **A 13-30 second stall ends in a kick** (`leaf-server/src/main/java/gg/hcfrevival/revival/auth/net/ClientStream.java:L288-294`, `L372-384`; `leaf-server/src/main/java/gg/hcfrevival/revival/auth/AuthPlayer.java:L1087-1090`)
   - **What this is:** One client tick may hold 512 items; the next gameplay packet past that is a protocol violation, which kicks in enforce.
   - **Problem:** The count includes pong and accept items, which have their own cap (`MAX_DRAIN_ITEMS`, 4096): a client back from a stall answers every queued ping in one frame, so a ~25 s outage (20 heartbeat pings a second) or a ~13 s download-only stall (plus a landing probe per tick end) leaves 512+ items, and its first catch-up move gets "Invalid transaction replies". Paper's keep-alive (30 s) would have kept that player, NW-2 promises a stall never kicks, and the stall rows stop at 12 s.
   - **Fix:** Count only gameplay packets toward `MAX_ITEMS` (a second counter in `Window`, bumped in `recordChecked`) and leave drain items to `MAX_DRAIN_ITEMS`. Test: 600 `recordAck` calls, then a move is RECORDED.
   - **If we skip it:** A Wi-Fi drop that vanilla survives becomes a combat-logger death.

## Should fix

5. **Any second move packet in a tick is an instant kick** (`leaf-server/src/main/java/gg/hcfrevival/revival/auth/net/ClientStream.java:L295-301`, `L324-331`; `leaf-server/src/main/java/gg/hcfrevival/revival/auth/AuthPlayer.java:L1085`; `leaf-server/src/test/java/gg/hcfrevival/revival/auth/net/ClientStreamTest.java:L137-141`)
   - **What this is:** Vanilla 26.3 kicks a second *position* packet in one client tick, and design §15.8 keeps exactly that as an instant kick; `ClientStream` counts every move packet, rotation-only and status-only too.
   - **Problem:** A client's reply to a server rotation packet (`MovePlayer.Rot`) counts as a reply only right after its pong, before the tick's keybinds; a rotation packet written around the choke point (PacketEvents, ProtocolLib: the bypass audit pings after it, so the Rot comes first) or a keybind packet a modded client wrote outside the tick (client-compat L10) makes the Rot the tick's claim, and the real move is then "a second move claim": kick. The unit test locks the stricter rule in.
   - **Fix:** Return SECOND_CLAIM only when both packets carry a position, send any other extra move packet down the EXTRA_INPUT path (counted, rate-kicked), and have `runWindow`'s read-ahead keep the last packet with a position as the claim; update the test.
   - **If we skip it:** Lunar/Fabric players, or anyone near a plugin that turns players by packet, can be kicked mid-fight.

6. **The geyser exemption covers any pool over buried sulfur** (`leaf-server/src/main/java/gg/hcfrevival/revival/auth/ExternalMovers.java:L224-247`)
   - **What this is:** A potent sulfur geyser launches players up its water column, which the port doesn't model, so ticks over one are exempt and vanilla takes claims within `external-movers.max-move` (1 block per axis per tick).
   - **Problem:** `geyserBelow` only asks for water at the feet and potent sulfur anywhere in the 25 blocks below, while vanilla launches only from an ERUPTING or CONTINUOUS sulfur with unbroken water above it (`PotentSulfurBlockEntity`, `PotentSulfurBlock.getTicker`). Potent sulfur drops itself and has a recipe, so a player can bury one under stone below a pool in its base and move 1 block a tick there (several times swim speed, straight up a column), with a launch of up to about 1 block a tick when it leaves; nothing tests `geyserBelow`.
   - **Fix:** Walk up from the sulfur, require water sources all the way to the feet, and require an ERUPTING or CONTINUOUS state (about 10 lines); add an `ExternalMoversTest` with sulfur under stone.
   - **If we skip it:** Any base can hold a permanent client-trusted zone.

7. **Exempt ticks show viewers a position the server refused** (`leaf-server/src/main/java/gg/hcfrevival/revival/auth/AuthPlayer.java:L2615-2617`, `L1919-1923`)
   - **What this is:** Each tick appends a step to the player's path feed, which viewers within 24 blocks are sent and lag compensation reads as the player's truth.
   - **Problem:** On an exempt tick the step is the client's claim, appended before the claim reaches vanilla, so when the server then refuses it (a mover claim past `max-move`, or vanilla's moved-too-quickly) every viewer still sees the refused spot. Case: a cheater beside an untrusted boat copy (or in finding 6's pool) claims a spot 3 blocks away each tick; the server keeps it in place while everyone sees, and swings at, a ghost.
   - **Fix:** For exempt ticks, append the step after the window's replay, from where the `ServerPlayer` ended up.
   - **If we skip it:** A player can show everyone a fake position while exempt.

8. **`honor-denied-predictions` does nothing** (`leaf-server/src/main/java/gg/hcfrevival/revival/config/AuthoritativeSettings.java:L83-84`, `L210`, `L359`)
   - **What this is:** A revival.yml key that design §15.2 and the auth-netcode.md table describe as choosing whether the truth keeps blocks the server denied.
   - **Problem:** Nothing in main code reads `honorDeniedPredictions()` (only the record and one test assert), and `WorldView` never honors a denied block whatever the value, so an owner who sets `true` gets no change and no warning.
   - **Fix:** Delete the key: record component, reader line, javadoc, `AuthoritativeSettingsTest` line 30, the doc rows. Old files that still hold it are ignored.
   - **If we skip it:** A switch that misleads owners.

## Nice to have

9. **The lag-compensation clock is the wall clock** (`leaf-server/src/main/java/gg/hcfrevival/revival/auth/Authority.java:L405-413`)
   - **What this is:** Every stamp lag compensation compares (path steps, logged packets, mirror boxes, "meant" fields) comes from `Authority.clockMillis()`, which is `System.currentTimeMillis()` minus the server's lag.
   - **Problem:** The wall clock can step (an NTP correction, a VM or NAS resume): a forward step makes every view look older than every cap for a moment, and a backward one makes old views look fresh and stops acks from clearing meant fields until the clock catches up. The rest of authority already uses `System.nanoTime()`.
   - **Fix:** Use `System.nanoTime() / 1_000_000` plus an offset fixed at startup (stamps must stay positive: `CombatWiring.displayDelay` treats `<= 0` as unknown).
   - **If we skip it:** A clock step causes a burst of wrong hit verdicts.

10. **Dead code left by the timer and stream fixes** (`leaf-server/src/main/java/gg/hcfrevival/revival/auth/net/Transactions.java:L207-211`, `L944-947`, `L1159-1172`; `leaf-server/src/main/java/gg/hcfrevival/revival/auth/net/ClientStream.java:L49-50`, `L419-422`, `L445-448`; `leaf-server/src/main/java/gg/hcfrevival/revival/auth/net/OutboundClassifier.java:L294-302`)
    - **What this is:** `settledAckSentNanos` and its two pending fields, the `FORCED` item kind and `recordForced`, `correctionAcceptExpected`, `mayMatter` and `trailingPing`.
    - **Problem:** None has a caller in main code, unit tests, live tests, patches or the rig's own scripts (grep), yet `ack()` still updates the pending fields on every pong; the timer's floor moved to `Window.timerAckNanos` long ago.
    - **Fix:** Delete them, about 40 lines.
    - **If we skip it:** Readers keep asking which of two timer floors is real.

11. **`protocol-kick-rate` above 4096 turns the rate kick off** (`leaf-server/src/main/java/gg/hcfrevival/revival/auth/AuthPlayer.java:L3894-3915`; reader at `leaf-server/src/main/java/gg/hcfrevival/revival/config/AuthoritativeSettings.java:L355`)
    - **What this is:** Ordering-only violations kick past `client-profiles.protocol-kick-rate` in 60 s, counted in a ring of at most 4096 slots.
    - **Problem:** For a higher rate, `softTimes.length != rate` is true on every call, so the ring is re-allocated (32 KB) and reset each time and the kick never fires.
    - **Fix:** Clamp the setting to 4096 where it is read.
    - **If we skip it:** A "lenient" setting silently means "never".

Left out: 8 smaller ones. `BypassAuditor.uninstall` still does the get-then-remove that review 2 fixed in `ArrivalStamps`; `SelfEcho.entityData` casts the flags value with `(Byte)` where `sharedFlags` checks `instanceof`; every pong calls `WorldView.endProvisional()`, bumping the chunk cache version; one reflection error in `ClientProfile.readApollo` turns Apollo reading off for every player until a plugin reload; PT-02's async half never landed (every `SetPlayerTeam`, for any team, is logged, and each one sent from a plugin thread costs its own ping; I found no real load where that hurts); `Transactions` and window arrays grow during a long stall and never shrink for the session (bounded, a few MB at worst); single-caller diagnostic accessors (`alongHit`, `nearestAlong`, `attackInterval`, `effectCount`, `bundleOpen`, `overflowCount`, `fedViewers`, some marked "(Rig.)"); `ClientStream.releasedEarlier` scans the window for every `UseItem`. Items the two reviews closed and the partial items in pvp-issues §0 were not re-reported; the closed fixes are in the code, except PT-02's async half (above, marked fixed). Finding 3 is review 1's stale-stamp-stationary-targets, which was handed on and never closed.

Verdict: Not cheat-proof yet: fix 1 and 2 first (movement bypasses any player can use, each a line or two to close), then 3 and 4 (hits dropped on standing players outside authority; a kick after a long stall).

Lean: -45 lines, -0 dependencies possible.

Not checked: the physics port in `auth/sim` (MovementSim, SimCollision, SimFluids, SimBlocks, MoveVelocity, SimAttributes, SimShape, about 5,000 lines), read only for loops a client value can size, not for exactness (the golden traces and fuzzer cover that); the internals of `PickRay`, `RemoteMirror` and `SolidMirrors` beyond their entry points; the use-prediction and inventory models in `ClientView`, `ClientItems` and `ClientAttributes`; visibility and `Occlusion` (off by default), `TraceRecorder`, `DebugHud`, `AuthCommand`, Neue rules beyond the click-buffer landing (Neue is parked), and the projectile hooks beyond `ProjectileRewind`. I ran no unit tests, live suites or load; all costs come from reading. Upstream Leaf/Paper code was read only where a hook calls it. The HCFR core plugin was not available, so how often findings 3 and 5 bite (player-type NPCs, rotation packets sent by plugins) is unknown.

Design questions for the lead:
- §15.5 gives `timer-queue-cap-ms` a default of 1000; the code, config migration v5 and auth-netcode.md use 400. Update §15.5?
- §15.7's rewind cap, `min(max-rewind-ms, rttBase + jitter + K·50 ms)`, is not what `CombatWiring.rewindCap` does (HR-2: `min(rttBase, rtt-ceiling-ms) + display + jitter + slack`, bounded by 600). Update §15.7?
- §15.1 still says a player past `max-unacked-ms` is "frozen, then kicked"; the code never kicks for a stall (NW-2). Update §15.1?
- §15.5 says a client with no pongs "stops moving within the ack deadline"; the code freezes it at `max-unacked-ms` (1.5 s), several deadlines later. Which one is meant?
- §15.3's exempt states don't mention warm-up at all; finding 1 proposes freezing it like not-loaded.

## Fixes (netcode, 2026-10-23)

Each finding was checked against the code at `316a191f` before it was touched. "Cheat" is cheat suite 1 (`AuthCheatScenarios`),
where every new row sits next to the rows of its topic and names "review 3 #N"; each was run on `316a191f` too (its main
sources restored, the new rows built in), where every cheat row leaked and every control was hurt as the finding says,
except the two guards marked so. Full live suites on the final code, both trackers: `classic-capped` cheat suite 145 of
145 and cheat suite 2 56 of 56 (1092 and 208 checks, none failed; the run before lost the Neue landing row to a loaded
host, as `a3236ebd` documents, and the rerun passed it); `classic` 145 of 145 and 49 of 49 (1070 and 208 checks).

| Finding | Verdict | What changed | Proof |
|---|---|---|---|
| 1 Warm-up fly window | fixed | Confirmed: with no pong the tick clock has no ack floor (windows run at real time), the freeze waits for `max-unacked-ms`, and a warm-up tick's move went to vanilla's handler (moved too quickly at 10 blocks a tick). `AuthPlayer.exemptClaimRuns`: under enforce a warm-up claim never reaches vanilla and is no seed's pair, as a not-loaded one; the truth starts where the server holds the player | unit `ReviewFixesTest.aWarmUpClaimNeverReachesVanillaUnderEnforce`; cheat "warm-up: holds its first pongs after PlayerLoaded, claims a flight of 0.5 a tick (review 3 #1)" (on `316a191f` the server rose 10.7 and the truth started 3.1 up; now 0); control "honest: walking at 150 ms when authority starts mid-session (review 3 #1, control)": the same with or without the fix (3 corrections on `316a191f`; 4 on `d020b2e2`, no claim exact while it walks), then exact; the activation seed starts the truth at rest under a walker |
| 2 Login on a boat gets around `vehicles: deny` | fixed | `AuthPlayer.switchMode`: entering enforce with `vehicles: deny`, a rider is dismounted (`stopRiding(true)`: a plugin's cancel can't keep it); `Authority.deniesVehicles` is the mount check's rule too. Found while proving it: a dismount's `SetPassengers` (the vehicle's list without the player) never classed as the player's own, so the view stayed riding, exempt with vanilla taking the claims, after every dismount, under `vehicles: vanilla` too; `OutboundClassifier` now logs every vehicle's `SetPassengers` as the passengers kind (view and solids; `carriesAny` gone) | unit `ReviewFixesTest.enforceNeverRidesWithVehiclesDenied`, `OutboundClassifierTest` (a horse's empty list); cheat "vehicles: logs in on a boat (vanilla re-mounts it before authority starts), drives it 1.5 blocks a tick (review 3 #2)" (on `316a191f` driven 15 blocks, still riding, no claim compared after; now dismounted, still, exact after) |
| 3 Standing targets outside authority unhittable past the cap | fixed | `TruthPaths`: a player outside authority answers where it stands while it is within its tracker's send threshold (`ServerEntity`'s 7.63e-6, squared) of `ServerEntity.getPositionBase()`, as `AuthPlayer.stillAt` does for a path; a rider stays REWIND (its base follows it unsent). In HCFR the combat logger is a villager and REDLINE's dummies are mannequins (not players: vanilla checks); the bots module's `Puppets` (feat/game-modes) are player-type and never send a `TickEnd`, so they stay outside authority: the real case | unit `TruthPathsTest`; controls "combat: honest hits on a player outside authority standing still (an NPC), at 0 ms / 150 ms / 250 ms (review 3 #3, control)" (0 of 6, `REWIND`, on `316a191f`; 6 of 6 `VALID` now); guard "combat: a player outside authority (an NPC) stepped 2 aside, pongs held 500 ms, the swing goes where it stood (review 3 #3)" (refused on both: `REWIND`, now `MISSED`) |
| 4 The 512-item cap counted pongs after a long stall | fixed | Confirmed: `OVERFLOW` is `protocolViolation`, a kick in enforce. `ClientStream.Window.packets`: the 512 cap counts gameplay packets only; drain replies keep their own `MAX_DRAIN_ITEMS` (4096) | unit `ClientStreamTest.repliesAfterALongStallDontCountTowardTheTicksPacketCap` (600 acks, then a move is `RECORDED`); control "stall: 30.0 s with nothing in or out, standing, then walks on as it comes back (review 3 #4)" (kicked on `316a191f`; now not kicked, 0 corrections) |
| 5 Any second move packet was an instant kick | fixed | `ClientStream`: `SECOND_CLAIM` only for a second position (vanilla's own `receivedPositionThisTick` rule, §15.8); any other extra move packet is `EXTRA_INPUT` (counted, rate-kicked). `AuthPlayer.laterClaim`: the window's claim is its last packet with a position. hcfr-core sends no rotation or position packet around the choke point (Bukkit teleports; `setRotation` only on mobs and displays): the case is third-party packet plugins and modded clients. Noted: such a reply still opens the tick group, so its pong is a counted ordering violation and the tick's keybinds after it are `AFTER_MOVE` (dropped) | unit `ClientStreamTest.onlyASecondPositionIsASecondClaim`, `ReviewFixesTest.theWindowsClaimIsItsLastPosition`; control "honest: a rotation reply out of its place (a rotation packet written around the netcode), then the tick's move (review 3 #5, control)" (kicked on `316a191f`; now counted, 0 corrections); "timer: two move claims in one window" still kicks |
| 6 Any pool over buried sulfur was exempt | fixed | `ExternalMovers.geyserBelow` ports vanilla's launch rule: an ERUPTING or CONTINUOUS sulfur (`PotentSulfurBlock.getTicker`), an unbroken column of water sources from it up to the feet, and air (or a passable block) within 4 water blocks above it (`findNoxiousGasSourceBlock`); `geyser-depth` stays the bound below the feet and the 0 switch | unit `ExternalMoversTest.onlyAnActiveGeyserUnderUnbrokenWaterReachingAirExemptsATick` (buried, dormant, wet, 6 deep, sealed, a pocket under a stone layer); cheat "geyser: a pool over potent sulfur buried under stone, the claims rise 0.9 a tick (review 3 #6)" (on `316a191f` the server rose 4.5 over 7 exempt ticks; now 0); control "geyser: in a continuous geyser's column, the claims go to vanilla within max-move (review 3 #6, control)" (exempt on both) |
| 7 Exempt ticks showed viewers a refused claim | fixed | `AuthPlayer.runWindow`: an exempt tick's path step is appended after the window's replay, from where the `ServerPlayer` ended up (none while a server teleport is pending, as before). No unit test: the step's source is the server player, which needs a server | cheat "geyser: in a continuous geyser's column, claims a spot 3 blocks off each tick; viewers are sent where the server holds it (review 3 #7)" (the path 3.0 off on `316a191f`; 0 now) |
| 8 `honor-denied-predictions` did nothing | fixed (deleted) | The record component, reader line and javadoc (`AuthoritativeSettings`), the test line, `WorldView`'s javadoc, the doc rows (§15.2, `auth-netcode.md`, pvp-issues WD-1); config v7 removes the key from existing files (`RevivalConfig`), so no owner sets it in vain | unit `RevivalConfigTest.version6DropsTheKeyThatDidNothing` |
| 9 The lag-compensation clock is the wall clock | noted | Not changed. `// shortcut:` in `Authority.clockMillis()`: the limit (an NTP step or a VM or NAS resume shifts every stamp) and the upgrade (`System.nanoTime() / 1_000_000` plus a positive offset fixed at startup, as `displayDelay` reads a stamp `<= 0` as unknown) | |
| 10 Dead code | fixed (deleted) | `Transactions.settledAckSentNanos` with its two pending fields and their updates in `ack()`; `ClientStream.FORCED`, `recordForced`, `correctionAcceptExpected`; `OutboundClassifier.mayMatter`, `trailingPing`. Grep first: no caller in main, unit tests, live tests, patches or `~/Developer/tools/auth-rig` (only snapshot copies of this repo) | build, unit suite |
| 11 `protocol-kick-rate` above 4096 never kicked | fixed | Read clamped to 4096 (`AuthoritativeSettings.MAX_PROTOCOL_KICK_RATE`), so `softViolation`'s ring is always the rate | unit `AuthoritativeSettingsTest.aProtocolKickRateIsAtMostTheRingItIsCountedIn` |
| `ClientProfile.readApollo` | fixed | A failed read leaves that player without Apollo's options (`apolloFailed`), not every player until a plugin reload (`Apollo.broken()` gone; a mismatched API is still `Apollo.get`'s) | by reading (reflection into Apollo) |
| `BypassAuditor.uninstall` | fixed | Removes its two handlers by instance and ignores a channel already torn down, as `ArrivalStamps` (review 2) | by reading (the race needs the event loop between the get and the remove) |
| `SelfEcho.entityData` cast | fixed | A shared-flags value that isn't a byte (a plugin's malformed packet) goes out as it is, never a `ClassCastException` at the choke point | unit `SelfEchoTest.aFlagsValueOfAnotherTypeGoesOutAsItIs` |
| The other five left out | noted, not fixed | `WorldView.endProvisional()` on every pong; PT-02's async half (every `SetPlayerTeam` logged, one from a plugin thread costs its own ping); `Transactions` and window arrays that grow in a long stall and never shrink; single-caller diagnostic accessors; `ClientStream.releasedEarlier` scanning the window per `UseItem` | |

Design questions, settled as doc drift in `authoritative.md` §15 (each marked "2026-10-23, review 3"): §15.5's
`timer-queue-cap-ms` is 400; §15.7's cap is `CombatWiring.rewindCap`'s (HR-2); §15.1's stalled player is frozen, never
kicked (NW-2); §15.5's client with no pongs keeps its real-time ticks until the freeze at `max-unacked-ms`; §15.3 has the
warm-up rule. §15.2 drops `honor-denied-predictions`, §15.3 the vehicles rule at enforce's start, §15.8 the overflow and
second-claim details.

Noticed, not pursued: on `d020b2e2` a player walking when authority starts mid-session takes 4 corrections and has no
exact claim until it stops, with or without these fixes (on `316a191f`: 3, and 20 of 21 exact); NW-1's correction that
waits for a gap to stop moving looks like the cause.

The live harness, found while running these rows: 26.3 reads no `spawn-monsters` from `server.properties` (a game rule
now), so mobs spawned by the cheat arena on some seeds (entity ids climbing all run long in three runs of four, none in
the clean one; most likely slimes: the arena floor is at y -60, where a slime chunk spawns them at any light) and
knocked rows' players about: an attacker displaced 4 blocks mid-row, a server player lifted 0.96, in two runs of four.
`RevivalLiveTests` turns `spawn_mobs` off at setup. `scripts/revival/live-tests.py` passes `REVIVAL_LIVE_ONLY` through as
`-Drevival.livetest.only` (the plugin's step filter).
