# 06 - Prior art: server-side movement prediction and lag compensation for vanilla clients

Research for the authoritative RevivalPaper (26.3). Goal: learn every pitfall that existing systems hit when they
re-simulate a **vanilla** client on the server, before we build ours. We do not copy any code from these projects
(see section 12, licensing). Every claim cites the file and line it was read from.

## 0. Sources read

| Source | Where | Version read |
|---|---|---|
| GrimAnticheat (GPL-3.0) | `~/Developer/tools/prior-art/Grim` (shallow clone) | commit `8baa16f` (2026-10-21, "Fix rotation wrapping across Minecraft versions"). README says it supports 1.8 to 26.3 (`README.md:21-22`). |
| Intave (source-available, PolyForm Perimeter 1.0.0) | `~/Developer/tools/prior-art/intave` (shallow clone) | commit `56ea182` (2026-10-19). Read for ideas only, see section 12. |
| Pledge (MIT) | github.com/ThomasOM/Pledge (web) | Transaction-injection library that Grim's tick-end hook is copied from (`bukkit/.../BukkitTickEndEvent.java:25`). |
| Vanilla 26.3 client/common/server | `~/Developer/tools/mc-src/src-26.3/net/minecraft/` | Decompiled 26.3 jar. Used to check which of Grim's assumptions still hold on 26.3. |
| Vanilla 26.2 | `~/Developer/tools/mc-src/src-26.2/net/minecraft/` | For 26.2 vs 26.3 diffs. |
| Web | Minecraft Wiki (1.21.2), Bedrock server.properties docs, Thwack mod page, PacketEvents javadoc | Cited inline. |

Grim paths below are relative to `Grim/common/src/main/java/ac/grim/grimac/` unless they start with `bukkit/`.
Intave paths are relative to `intave/src/main/java/de/jpx3/intave/`. Vanilla paths are relative to
`src-26.3/net/minecraft/`.

---

## 1. The ten things that matter most for our design

1. **Grim is a verifier, not an authority.** Every accepted movement packet re-bases Grim's player on the
   client's reported position (`events/packets/CheckManagerListener.java:762-769`: `lastX = x; x = clampVector.getX()`),
   and Grim picks whichever candidate velocity best fits what the client claimed
   (`predictionengine/predictions/PredictionEngine.java:78-198`). Errors never accumulate because it re-bases on the
   client every tick. If our server keeps its own position, a non-exact physics port will drift. Adopt "snap to the
   client when within epsilon, correct beyond it" (section 7).
2. **Grim brute-forces because it lacks information.** It tries every candidate (keys, using-item yes/no, sneak-slow
   yes/no, stuck-speed masks, knockback taken/not taken, 0.03 hidden movement) and keeps the closest
   (`PredictionEngine.java:727-812`). On 1.21.2+ the input packet collapses the key loop to one combination
   (`PredictionEngine.java:737-761`), but the item-use and slow loops remain (`:764-811`). Each remaining loop is a
   piece of client-local state we must model exactly instead of guessing.
3. **Latency compensation is a "transaction" (ping/pong) clock.** Every server-to-client state change is tied to a
   ping id. The change is applied to the simulated client view when the pong comes back
   (`utils/latency/LatencyUtils.java:26-88`). Important changes get a ping before and after ("sandwich",
   `checks/impl/velocity/KnockbackHandler.java:74-77`). Between the two pongs the state is uncertain and Grim keeps
   both possibilities ("first bread" and "likely" velocity, `KnockbackHandler.java:125-151`).
4. **We can remove the sandwich uncertainty with bundles.** The 26.3 client handles a `ClientboundBundlePacket` as
   one task, running every sub-packet in order (`client/multiplayer/ClientPacketListener.java:2468-2474`). It
   answers pings on the main thread immediately (`client/multiplayer/ClientCommonPacketListenerImpl.java:154-157`),
   and it drains the packet queue once per frame before running up to 10 ticks
   (`client/Minecraft.java:1202,1215-1219`). If each server tick's per-player state goes out as one bundle with a
   ping inside it, the pong proves the whole bundle was applied before the next client tick. Grim, a plugin, does not
   do this. It is our proposal and must be tested.
5. **1.21.2+ gives us the tick clock and the keys.** `ServerboundClientTickEndPacket` is sent once per client tick
   (`client/Minecraft.java:1883-1886`). `ServerboundPlayerInputPacket` is sent only when keys change
   (`client/player/LocalPlayer.java:266-269`). Grim treats a tick-end with no preceding move packet as a tick with
   hidden movement (`checks/GrimProcessor.java:86-97`, `CheckManagerListener.java:597-610`). The client only skips a
   move packet when it is within 2.0E-4 blocks of the last position it **sent**
   (`LocalPlayer.java:303`, `player/GrimPlayer.java:655-657`).
6. **Quantize everything the client decodes.** Velocity packets are `LpVec3`, a 15-bit-per-axis encoding with a shared
   scale (`network/LpVec3.java:8-17`; `network/protocol/game/ClientboundSetEntityMotionPacket.java:15`). Grim runs
   every knockback vector through the codec before using it (`checks/impl/velocity/VectorPrecisionConverter.java:18-55`).
   Teleport velocity is full doubles (`world/entity/PositionMoveRotation.java:13-23`, `world/phys/Vec3.java:25-28`).
7. **Corrections cannot be rewound on a Java client.** Bedrock has "server-auth-with-rewind", where the client rolls back
   to the correction and replays its inputs (pingperfect knowledge base, section 11.3). The Java client just applies the
   teleport. Grim sets back to its last good position, simulates one tick forward with the "future" knockback, and keeps
   the player's rotation with relative yaw and pitch (`manager/SetbackTeleportUtil.java:159-233, 304`). To hide most of
   the rubber band, use relative position plus velocity deltas (section 7).
8. **Hit validation is a post-check with the next rotation.** The 26.3 client picks its target at the start of a client
   tick with `pick(1.0F)` (`client/Minecraft.java:1812`), and attacks in `handleKeybinds` (`:1822`). The attack packet
   therefore arrives **before** the move packet that carries the rotation it was aimed with. Grim queues the attack and
   checks it on the next tick packet (`checks/impl/combat/Reach.java:91-93, 228-256`). The target's possible positions
   come from a model of client interpolation, including 26.3's stepped paths
   (`utils/data/interpolation/stepped/SteppedEntityInterpolation.java`). Tolerance is a 0.0005 hitbox expansion
   (`Reach.java:377`).
9. **Timer = 50 ms per client tick against a floor set by the acknowledged ping clock.** The floor is the send time of the
   last acknowledged ping minus 120 ms of drift (`checks/impl/timer/Timer.java:59-97, 111`). That is robust to jitter
   because it never uses packet arrival time.
10. **Most of Grim's complexity is client-local state the server does not see**: sneak pose lag, client-predicted item
    use, client-predicted attacker slowdown, the sprint-modifier desync (MC-69459), interpolated entities you can
    collide with, fireworks, rod pulls and pistons. Each is listed in section 2 with Grim's handling and ours.

---

## 2. (a) Every known source of client/server divergence for a vanilla client

Legend for Grim's handling: **EXACT** = modelled exactly. **BAND** = uncertainty band or brute force over options.
**HACK** = offset reduction or a magic constant. **EXEMPT** = not checked, or resync.
"Ours" is the recommended handling for an authoritative 26.3 server with vanilla 26.3 clients.

### 2.1 Network and timing

| # | Source | Why it diverges | Grim | Ours |
|---|---|---|---|---|
| 1 | Which client tick first saw a server state change | Packets take RTT/2 to arrive and are applied at the next frame. | BAND. Pre and post ping around important packets (`KnockbackHandler.java:74-77`; `checks/impl/velocity/ExplosionHandler.java:63-68`; `events/packets/PacketServerTeleport.java:118-120`). Single pre-ping for blocks, attributes and effects (`events/packets/PacketEntityReplication.java:260-288`; `events/packets/worldreader/BasePacketWorldReader.java:147-158`). | EXACT. One bundle per server tick per player with a ping in it. Pong k arriving between client tick-end t and t+1 means "applied from tick t+1". |
| 2 | Split transaction: pre-pong and post-pong straddle a client tick | TCP can deliver half a server flush before a client frame. | BAND. "first bread" vs "likely" (`KnockbackHandler.java:125-151`). Entity moves get two tasks at `lastTrans` and `lastTrans+1` (`PacketEntityReplication.java:568-573`). | EXACT with bundles (item 1). Without bundles, keep both states as Grim does. |
| 3 | Hidden ticks: client sends no move packet when the move is at most 2.0E-4 from the last sent position (0.03 before 1.18.2) | Position not observed for that tick. | BAND. Threshold 0.0002 vs 0.03 (`player/GrimPlayer.java:651-657`). `PointThreeEstimator.determineCanSkipTick` decides whether a skip was possible (`predictionengine/PointThreeEstimator.java:343-396`). Extra 0.03-style candidate vectors (`PredictionEngine.java:236-288`). On 1.21.2+ the tick-end packet tells it a tick happened (`CheckManagerListener.java:597-610`). | EXACT. Simulate every tick from inputs, since the tick count is known. The client's position for that tick is only known to be within 2.0E-4 of the last **sent** position, so that is a consistency check, not an input. Compare positions only on ticks that sent one. |
| 4 | 1.17 to 1.20.x duplicate move packets | Extra position+rotation packet after some actions. | HACK (`CheckManagerListener.java:625-690`). | Not applicable on 26.3 (Grim: removed in 1.21, `:629-632`). |
| 5 | Teleport accept | Before 26.3 the client sent accept, then a `PosRot` (26.2 `ClientPacketListener.java:807-809`). 26.3 puts the position in the accept packet and sends no `PosRot` (`ClientPacketListener.java:801-802`; `network/protocol/game/ServerboundAcceptTeleportationPacket.java:9`). | EXACT. `TELEPORT_CONTAINS_POSITION` for 26.3+ servers (`CheckManagerListener.java:47, 400-412, 468-471`). | EXACT. The accept is the teleport tick. Do not expect a follow-up move packet. |
| 6 | Frame/tick coupling | The client drains packets once per frame, then runs at most 10 ticks (`client/Minecraft.java:1202, 1215`). A long frame drops ticks. | Covered by timer drift (section 9). | Honest clients after a hitch send fewer ticks than wall time ("negative timer"). Do not treat that as cheating (Grim's NegativeTimer is experimental: `checks/impl/timer/NegativeTimer.java:13`). |
| 7 | Ping vs keepalive | Ping is answered after main-thread sync. Keepalive is not (Pledge README). | Uses ping (17+) or window confirmation (`GrimPlayer.java:502-506`). Negative short ids only (`events/packets/PacketPingListener.java:63-78`). | Use `ClientboundPingPacket`. Do not use keepalive as a clock. Consider random ids, as Intave does to stop pre-answering (`module/feedback/FeedbackSender.java:305-306`). |
| 8 | Rotation precision and wrapping | Vanilla server wraps yaw with `Mth.wrapDegrees` (`server/network/ServerGamePacketListenerImpl.java:1132-1133`). The client uses its raw unbounded float. `Mth.sin` is a 65536-entry table indexed by `(long)(rad * 10430.378350470453) & 65535` (`util/Mth.java:50-56`), so a wrapped angle can hit a different table slot. | Grim's latest commit is literally "Fix rotation wrapping across Minecraft versions". It keeps a 1.21.11+ table (`utils/math/ModernVanillaMath.java:5-22`). | EXACT. Keep the client-sent `yRot`/`xRot` floats unwrapped for physics. Wrap only for what vanilla stores. |
| 9 | Client "fast math" (OptiFine/Lunar style trig) | Different sin/cos tables. | BAND. Auto-detects by which math fits better over 5 samples (`utils/math/TrigHandler.java:46-85`). | Decide policy: support the second table and detect it as Grim does, or treat it as non-vanilla. A "vanilla 26.3" target allows the latter, but many PvP players use Lunar/Feather. |

### 2.2 Inputs and client-local state

| # | Source | Why it diverges | Grim | Ours |
|---|---|---|---|---|
| 10 | Movement keys | Before 1.21.2 unknown. 26.3: `ServerboundPlayerInputPacket(Input)` with forward/backward/left/right/jump/shift/sprint bits (`world/entity/player/Input.java:6-35`), sent in `sendChanges` before the move packet (`LocalPlayer.java:264-269`), only on change. | EXACT on 1.21.2+ for keys (`PredictionEngine.java:737-761`). Flags input sent twice in one tick (`checks/impl/badpackets/BadPacketsZ.java:24-35`). | EXACT. Hold the last input until a new one arrives. Ordering within a client tick: [attack/use/action packets from `handleKeybinds`] then input, sprint command, move, tick end (`client/Minecraft.java:1812-1838, 1885`; `LocalPlayer.java:264-312, 334-342`). |
| 11 | Sneak / slow movement | 1.21.6+ sneaking comes from `input.shift` (the `START/STOP_SNEAKING` actions are gone: `network/protocol/game/ServerboundPlayerCommandPacket.java:60-68`; Grim `PacketPlayerSteer.java:77-80`). "Slow movement" depends on the crouching **pose**, which updates after movement, so it lags one tick. | BAND. Loops `isSlowMovement` both ways (`PredictionEngine.java:764-811`). For hidden ticks uses a 0.6x0.6 box because the pose is unknown (`PredictionEngine.java:92-96`). | EXACT if we run the client's pose update in the same order as the client. Port the order, not the outcome. |
| 12 | Sprint state and the sprint speed modifier | Sprint start/stop is sent in `sendIsSprintingIfNeeded` just before the move packet (`LocalPlayer.java:295, 334-342`). Separately, a server `UpdateAttributes` for movement speed carries or omits the "sprinting" modifier according to the **server's** sprint flag and overwrites the client's (MC-69459). | EXACT for the flag. The attribute is tracked separately (`hasSprintingAttributeEnabled`, `utils/latency/CompensatedEntities.java:114-133`; `predictionengine/MovementCheckRunner.java:356-368`, which also notes that multiplying by 1.3 loses precision and you must add `speed * 0.3F`). | EXACT. Model the client's attribute map as last received plus the client's own sprint toggles. Never assume the modifier follows the sprint flag. |
| 13 | Client-predicted item use (eating, bow, shield, RevivalPaper sword block) | The client starts using an item locally on right click. The server can override through the local player's `DATA_LIVING_ENTITY_FLAGS` (`client/player/LocalPlayer.java:597-607`). Changing slots cancels use. | BAND. Brute-forces using/not using (`PredictionEngine.java:770-806`). Use starts on the use packet (`events/packets/PacketPlayerDigging.java:35-57`) and stops on release. A server metadata override is applied at the **post** ping unless the client acted since (`events/packets/PacketSelfMetadataListener.java:202-226`). | EXACT, from packet order plus ack timing. **RevivalPaper pitfall:** the client sees swords as consumables that the server's copy does not have (`docs/revival/grimac-checklist.md` row 2). The sim must use the **client-visible** item stack. The slowdown is the item's `USE_EFFECTS` speed multiplier, default 0.2 (Grim `predictionengine/predictions/input/impl/ModernInputTransformer.java:58-67`). |
| 14 | Client-predicted attacker slowdown on a sprint hit | Hitting a player runs `Player.attack` on the attacker's client. `RemotePlayer.hurtClient` returns true (`client/player/RemotePlayer.java:31-34`), so `causeExtraKnockback` multiplies the attacker's own motion by (0.6, 1, 0.6) and calls `setSprinting(false)` locally (`world/entity/player/Player.java:1104-1122`). The condition is `isSprinting && attackStrengthScale > 0.9`, or a knockback weapon (`Player.java:944-951, 968-971`). | BAND. Counts min/max possible slows per tick (`events/packets/PacketPlayerAttack.java:103-143`). Applies ×0.6 per slow before the movement threshold (`PredictionEngine.java:332-336, 353-369`). | EXACT. The attack packet precedes the tick's move. Replay the client's `Player.attack` gate with **client-side** values: attack strength, sprint flag and weapon knockback. **RevivalPaper pitfall:** legacy hits keep the attacker sprinting server-side (`grimac-checklist.md` row 3), but the vanilla client still drops its own sprint locally on each full-strength sprint hit. With `legacy_no_cooldown` every hit is full strength. |
| 15 | Jump | Needs `lastOnGround` plus the jump key. | EXACT with known input (`MovementCheckRunner.java:333-334`; `PredictionEngine.java:884-889`). | EXACT. |
| 16 | Elytra start | `START_FALL_FLYING` command. | Validates, and resyncs if on the ground or with a ghost elytra (`events/packets/PacketEntityAction.java:49-75`). Elytra can be exempted entirely (`MovementCheckRunner.java:494`). | Out of scope for HCF arenas. If needed, port it exactly. |
| 17 | Riptide | Released with `RELEASE_USE_ITEM`. On the ground the client first lifts 1.2 and the server cannot tell whether it was grounded. | HACK. Guesses ground vs air by comparing both outcomes (`MovementCheckRunner.java:513-534, 702-712`). Wall-clock 450 ms debounce (`:370-380`). | Disable or port exactly. Do not use wall-clock time. |
| 18 | Client-predicted blocks (place/break with sequence ids) | The client changes the block now. Server block updates during the prediction window are held until `BlockChangedAck`. If the player is inside a reverted block, the client teleports itself back to where it was when it predicted. | EXACT-ish. Keeps original server blocks per prediction and applies them on ack (`utils/latency/CompensatedWorld.java:103-176, 274-291`). Copies the self-teleport-on-revert (`:165-174`). | EXACT. Replicate the client's `BlockStatePredictionHandler` view per player. |

### 2.3 Server-to-client state that must be applied at ack time

| # | Source | Grim | Ours |
|---|---|---|---|
| 19 | Velocity (`ClientboundSetEntityMotionPacket`, **sets** motion: `ClientPacketListener.java:631-637` calls `Entity.lerpMotion`, which is `setDeltaMovement`, `world/entity/Entity.java:2755-2757`) | Sandwich (`KnockbackHandler.java:47-79`). Quantized like the client (`VectorPrecisionConverter.java:18-26`). Rewrites a velocity of y = -0.04 to -0.04 + 1/8000, citing a client bug (`KnockbackHandler.java:65-70`, unexplained). | EXACT. Quantize through `LpVec3` write/read before simulating. Apply at the pong tick. |
| 20 | Explosion knockback (**adds**: `ClientPacketListener.java:1388`, `pushFromExplosion`) | Sandwich. Added to every candidate (`ExplosionHandler.java:51-70`; `PredictionEngine.java:415-427`). | EXACT. |
| 21 | Teleports (absolute/relative, with velocity on 1.21.2+) | Pending queue. Accepted only when the client's pong count equals the teleport's pre-ping (`SetbackTeleportUtil.java:326-374`). Converts relative teleports to absolute for 1.8 clients and vehicles (`PacketServerTeleport.java:55-88`). Strips teleport velocity in vehicles because it "does not work correctly in Grim" (`MovementCheckRunner.java:102-107`; `PacketServerTeleport.java:90-111`). The 1.21.2+ client ignores teleports while a passenger (`ClientPacketListener.java:797`; Grim `PacketServerTeleport.java:113-116`). | EXACT. See section 7. |
| 22 | Attributes (speed, gravity, step height, scale, jump strength, water movement efficiency, sneaking speed, and on 26.2+ air drag, bounciness and friction modifiers: `utils/data/packetentity/PacketEntity.java:99-113`) | Single pre-ping when it targets the player (`PacketEntityReplication.java:279-288`). Gravity changes during hidden ticks get vertical lenience (`PointThreeEstimator.java:200-202, 230-232`). | EXACT at the pong tick. Keep the client's attribute map, including the MC-69459 sprint-modifier quirk (row 12). |
| 23 | Effects (levitation, slow falling, jump boost, dolphin's grace and others) | Single pre-ping (`PacketEntityReplication.java:238-278`). Levitation during a hidden tick gives a band (`PointThreeEstimator.java:219-224, 469-481`). | EXACT at the pong tick. |
| 24 | Abilities (flying, mayfly, fly and walk speed) | Pre-ping (`events/packets/PacketPlayerAbilities.java:39,54`). Flying is EXEMPT (`MovementCheckRunner.java:494-503`: "TODO: Re-implement flying support"). | EXACT, or forbid flight in arenas. |
| 25 | Self metadata (pose, using-item flags, gliding flag, frozen ticks) | Pre and post (`PacketSelfMetadataListener.java:202-226`). | EXACT. |
| 26 | Block changes, chunks, unloads | Applied at the pre-ping (`BasePacketWorldReader.java:147-180`). Chunk sends add a post-ping only near the player (`:117-131`). Unload is deferred (`CompensatedWorld.java:751-760`). | EXACT, part of the per-player client-view world. |
| 27 | Client in an unloaded chunk | The client does not move normally there. | EXEMPT plus force resync until loaded (`MovementCheckRunner.java:71-82`; `SetbackTeleportUtil.java:434-441`). | Do not simulate. Hold the player until loaded (vanilla also gates on `ServerboundPlayerLoadedPacket`, `ServerGamePacketListenerImpl.java:580-584`). |
| 28 | World border | Ticked per client tick (`events/packets/PacketPlayerTick.java:24-38`). The hard-border clamp is **not implemented** (`PredictionEngine.java:33-36`). | EXACT, or keep arenas away from the border. |
| 29 | Mount/dismount, vehicle switch | The first tick in a new vehicle has no known start position; Grim assumes the best start inside the vehicle's possible box (`MovementCheckRunner.java:190-253`). Lenience for 10 ticks (`PredictionEngine.java:681-702`). | Out of scope for arenas. If needed, hold authority until the first accepted vehicle tick. |
| 30 | Inventory and held slot (boots for powder snow, elytra, item use, cooldowns) | Compensated inventory and cooldowns (`utils/latency/CompensatedInventory.java`, `CompensatedCooldown.java`). Slot change during use cancels the use (`PacketPlayerDigging.java:83-96`, `slotChanged` at `:88-94`). | EXACT, part of the client view. |
| 31 | Synced tags (climbable and others) | `SyncedTags` from the server tags packet (`PointThreeEstimator.java:274`). | We are the server, so the tags are known. |
| 32 | Despawn vs spawn ordering | Despawn applies at the post ping, spawn at the pre ping (`PacketEntityReplication.java:72-89, 424-446`). | EXACT with bundles. |

### 2.4 Physics and world interactions

| # | Source | Grim | Ours |
|---|---|---|---|
| 33 | Colliding with entities the client sees **interpolated** (boats, shulkers, happy ghasts) | HACK. Expands the uncertainty box to include zero ("collisions are always less than the predicted movement") and reduces the offset by 1.2 for 3 ticks: "Boats are too glitchy to check" (`PredictionEngine.java:645-679`; `predictionengine/UncertaintyHandler.java:292-297, 318-370`). | Reconstruct each such entity's client-side interpolated box at the tick (same machinery as reach, section 8). Or keep them out of arenas. |
| 34 | Entity pushing (player/mob collision push) | BAND of 0.08 per possible colliding entity per axis (`PredictionEngine.java:490, 540`). "TODO actually handle entity collisions instead of this awfulness" (`predictionengine/movementtick/MovementTicker.java:47-97`). | Either reconstruct exactly (we know where the client rendered every pusher), or **turn player collisions off** (scoreboard team `collisionRule never`). Grim respects team rules (`MovementTicker.java:77-82`). Turning it off removes a whole divergence class from PvP. |
| 35 | Pistons and moving blocks | BAND of +0.1 plus the piston push amount (`PredictionEngine.java:525-528, 707-718`; `CompensatedWorld.java:370-460`). | Keep out of arenas or port exactly. |
| 36 | Fluids (flow pushing, swim hop, bubble columns, the 0.003 minimum) | Mostly EXACT. BANDs only on hidden ticks (`PointThreeEstimator.java:398-412`). Swim hop needs horizontal collision, which is hidden behind collisions, so Grim loosens it (`PredictionEngine.java:835-878`). | EXACT. |
| 37 | Bouncy blocks (slime, bed, honey, 26.3 shelf mushroom) | BAND and HACK (`MovementCheckRunner.java:385-416`; `UncertaintyHandler.java:303-307`: "This is a section where I hack around current issues with Grim itself"). | EXACT. |
| 38 | Stuck speed (cobweb, berry bush, powder snow) | BAND over a mask of possible multipliers. 1.21.2+ uses last tick's multiplier (`MovementCheckRunner.java:467-483`; `PredictionEngine.java:788-800`). | EXACT. |
| 39 | Sneak edge back-off (`maybeBackOffFromEdge`) | HACK. "stuck on edge" detection in 0.05 steps (`MovementCheckRunner.java:154-185`), plus `SneakingEstimator` ("START HACKERY", `predictionengine/SneakingEstimator.java:58`), plus a 1.21.5+ tiny-vector special case (`PredictionEngine.java:381-399`). | EXACT, which follows from exact pose and input. |
| 40 | Movements under 1e-7 are ignored; 1.21.2 added a condition | EXACT (`MovementTicker.java:237-241`). | EXACT, as part of the port. |
| 41 | Collision epsilon for detecting a collision | Tests with ±1e-7 then removes it (`PredictionEngine.java:200-234`). | Not needed with exact collision. |
| 42 | Client `onGround` claim vs computed | Mostly computed. Trusts the client near ground when ambiguous (`MovementTicker.java:169-190`). A ground flag that changes without a position triggers a resync unless the ground is within 0.03 (`CheckManagerListener.java:719-736`). | Computed. The client's claim is a consistency check only. |
| 43 | Head-hitters during hidden ticks | BAND (`PointThreeEstimator.java:239-248, 419-424`). | EXACT, ticks are known. |
| 44 | Fireworks while gliding (client-side firework entity boosts the player) | BAND of up to 1.7 blocks/tick scaled by look (`UncertaintyHandler.java:143-212`). Firework removed at the post ping. A 1000 ms max-ping guard sets back players who stall pongs to keep a boost (`PacketEntityReplication.java:448-461`). | Out of scope (no elytra in arenas), or port exactly. |
| 45 | Fishing-rod pull on the hooked player (entity event 31) | BAND box. "We don't transaction sandwich this, it's too rare" (`PacketEntityReplication.java:307-316`; `UncertaintyHandler.java:143-172`). | **RevivalPaper pitfall:** the rod is currently server-side only; the hooked client is not pulled (`grimac-checklist.md` row 6). Under authority, server-only motion that the client never sees **is** a divergence. Either send it as velocity or keep it out of authoritative state. |
| 46 | Riptide hitting an entity reverses motion ×-0.2 | BAND (`GrimPlayer.java:384-386`). | Port exactly or disable tridents. |
| 47 | Vehicles (boat, horse, pig, strider, camel, happy ghast, nautilus) | Separate tickers (`MovementCheckRunner.java:540-573`). Several HACK lines: boost time -0.01 (`UncertaintyHandler.java:309-313`). | Out of scope for the esport build. |
| 48 | Dead or sleeping | EXEMPT (`MovementCheckRunner.java:255-261, 489-493`). | EXEMPT. |

### 2.5 Combat

| # | Source | Grim | Ours |
|---|---|---|---|
| 49 | Where the attacker's client rendered the target (interpolation) | BAND. Box of every position the target could be at, from the interpolation step range and transaction confirmation (section 8). | EXACT with bundles and exact paths. The client model collapses to one point when nothing is uncertain (Grim already does this: `SteppedEntityInterpolation.java:117-119, 248-260`). |
| 50 | Attack rotation vs packet order (mouse delay, MC-67665) | Post-check on the next tick packet using current yaw/pitch, plus `(lastYaw, pitch)` and `(lastYaw, lastPitch)` (`Reach.java:266-280`). | On 26.3: the rotation in that tick's move packet, or the previous one if no rotation was sent (`client/Minecraft.java:1812, 1822, 1838`). Verify whether the `lastYaw` variants are still needed for 26.3. |
| 51 | Attacker eye position | The position at the time of the attack (last reported), all possible eye heights for the pose (`Reach.java:185-188, 285-292`; `GrimPlayer.java:697-712`). | EXACT. The eye is at the end-of-last-tick position because `pick(1.0F)` uses partial tick 1.0 (`client/player/LocalPlayer.java:1292-1304`). |
| 52 | Reach limit source | `ENTITY_INTERACTION_RANGE` attribute, or the item's `attack_range` component. The item stack at the **start of the tick** governs (`Reach.java:131-181, 344-372`). | EXACT. |
| 53 | Hitbox margin | 0.0005 threshold, +0.1 for clients older than 1.9, + movement threshold if ticks can be skipped (`Reach.java:344-372, 377`). | Small epsilon only (section 8). |

---

## 3. Grim architecture, as it bears on us

### 3.1 Where it runs

- Everything runs on the Netty thread, per player (README "Fully asynchronous and multithreaded design").
  `MovementCheckRunner.processAndCheckMovementPacket` runs inline in packet receive (`predictionengine/MovementCheckRunner.java:66-92`).
- A per-player replica of the world (`CompensatedWorld`) and entities (`CompensatedEntities`) is built from outgoing
  packets and mutated at transaction-ack time.

### 3.2 Per-movement flow (`MovementCheckRunner.check`, `:145-687`)

1. If it is a teleport, handle it (`:94-143`): reset velocity (or take the teleport velocity), force-exempt knockback and explosions.
2. Re-base: `actualMovement = (x - lastX, ...)` (`:327`). `lastX` is the previous **client-reported** position.
3. Set environment flags: stepping on slime/honey/bed/ice/bubble column/scaffolding (`:382-429`), stuck speed (`:467-483`), sprint attribute (`:356-368`).
4. Base tick, then `livingEntityAIStep` through the `PredictionEngine` variant (normal, water, lava, elytra) (`:536-539`).
5. `offset = |predicted - actual|`, reduced by HACKs (`:576-577`; `UncertaintyHandler.reduceOffset`, `:291-316`).
6. Force a resync if the player ignored setback knockback or jumped off a setback (`:588-605`).
7. `onPredictionFinish`: checks such as `OffsetHandler` flag, accumulate and setback (section 7).

### 3.3 The prediction engine

- Start vectors: the last tick's carried velocity, swim hop, climbing, knockback (set), explosion (add), riptide and
  slime-piston bounces (`GrimPlayer.java:355-406`; `PredictionEngine.java:318-341`). Order matters:
  fluid pushing, then attack slow, then 0.003 zeroing, then jumps (`:329-339`).
- Inputs: up to 9 key combinations before 1.21.2, one with known input (`:732-761`), ×2 item use ×2 slow, ×stuck speed (`:764-811`).
- Candidates are sorted (knockback and explosions first, then 0.03, normal, first-bread, ground-spoof, flip-item; `:429-487`),
  each is collided, and the closest wins. It exits early below `1e-5` (`:177-180`).
- `handleStartingVelocityUncertainty` (`:489-720`) widens each candidate into a box (entity pushing, 0.03, pistons,
  fireworks, rod, levitation, sneaking hidden velocity) and cuts the box toward the actual movement. **This is the
  core trick: uncertainty is applied as an axis-aligned box on the start velocity, and the client's movement is
  projected into it.** It is safe for a verifier and meaningless for an authority. An authority needs one answer.

### 3.4 Offset policy (`checks/impl/prediction/OffsetHandler.java:36-115`)

- `Simulation.threshold` 0.001. Offsets below it are ignored completely (`:105`).
- At or above it: `advantageGained += offset`. Setback when advantage reaches 1 (`max-advantage`) or a single
  offset reaches 0.1 (`immediate-setback-threshold`) (`:43-58, 106-107`).
- Advantage decays ×0.999 per clean tick and is capped at 4 (`:61-64, 104, 108`).
- The flagged offset (capped at 1) is carried as lenience into the next tick (`:79-90`), so one bad tick does not cascade.

### 3.5 What is directly reusable as an idea

- **Order of operations inside a tick is the main source of falses.** Grim's comments repeatedly note "done BEFORE
  0.003" or "inputs AFTER fluid pushing" (`PredictionEngine.java:329-339`). Our port must reproduce the client's exact
  `LocalPlayer.aiStep` order.
- **Seed the next tick from the best-fit velocity and the real position.** That is why Grim never drifts.

---

## 4. Transactions (ping/pong) in detail

### 4.1 Grim's clock

- Ids are negative shorts, `-(counter & 0x7FFF)` (`GrimPlayer.java:498`). Ids Grim did not send are ignored
  (`PacketPingListener.java:63-95`).
- Send path: `addTransactionSend(id)` then write the `ClientboundPingPacket` (`GrimPlayer.java:487-520`).
  `lastTransactionSent` increments when the ping actually goes out (`PacketPingListener.java:80-95`).
- Receive path: `addTransactionResponse` pops every sent id up to the received one. Skipped ids are flagged after
  5 s from join (`TransactionOrder`). Each pop increments `lastTransactionReceived` and sets
  `playerClockAtLeast = sentNanos` and `transactionPing` (`GrimPlayer.java:413-451`).
- Then `latencyUtils.handleNettySyncTransaction(lastTransactionReceived)` runs every queued task whose transaction
  is at or below the received one (`LatencyUtils.java:36-88`). `addRealTimeTask(t, r)` runs `r` immediately if `t`
  was already acknowledged, otherwise queues it (`:26-34`).
- Heartbeat: if no transaction was sent for 80 ms, send one (`GrimPlayer.java:562-570`). Kick if the oldest
  unanswered ping is older than `max-transaction-time` (default 60 s, `GrimPlayer.java:263, 571-573, 977`).

### 4.2 The sandwich

- **Pre ping** (`sendTransaction()` just before the packet), the packet itself, then a **post ping** in
  `event.getTasksAfterSend()` (`KnockbackHandler.java:74-77`; `PacketServerTeleport.java:118-120`;
  `ExplosionHandler.java:63-68`; `PacketSelfMetadataListener.java:205-226`).
- State is applied as "possible" when pong(pre) arrives and "certain" when pong(post) arrives.
  For velocity: pre acknowledged but post not means a "first bread" candidate; both acknowledged means "likely"
  (required) (`KnockbackHandler.java:125-151`; `CheckManagerListener.java:444-457`).
- Entity movement: one pre ping per wave per tick (`hasSentPreWavePacket`, `PacketEntityReplication.java:70, 509-510, 665-667`).
  Never hang two relative moves on one transaction (`:562-566`). The same `EntityMovementTransaction` runs at `lastTrans`
  (begin interpolation) and `lastTrans+1` (confirm) (`:568-573`; `utils/data/interpolation/EntityMovementTransaction.java:25-44`).
- Server tick end: a transaction at the end of every server tick, after the flush. It is optional in Grim
  (`Reach.enable-post-packet`, `manager/init/start/AbstractTickEndEvent.java:14-20`). It needs Paper's tick-end event or
  `-Dpaper.explicit-flush=true` (`bukkit/.../BukkitTickEndEvent.java:43-71`), injected Pledge-style into the connection
  list (`:90-120`).

### 4.3 What the 26.3 client guarantees (verified in vanilla source)

- Pings are answered on the main thread during packet processing: `handlePing` calls
  `ensureRunningOnSameThread`, then sends the pong (`client/multiplayer/ClientCommonPacketListenerImpl.java:154-157`).
- The packet queue is drained once per frame, then up to 10 ticks run (`client/Minecraft.java:1197-1219`).
  So a pong is always written **between** two client ticks. Every tick packet sent after the pong was simulated with
  every packet processed before the ping.
- A bundle is one task. `handleBundlePacket` loops the sub-packets inline (`ClientPacketListener.java:2468-2474`).
  The bundle limit is 4096 packets (`network/protocol/BundlerInfo.java:12, 46`).
  **Proposal:** put each server tick's per-player packets plus one ping inside one bundle. The pong then exactly marks
  the first client tick that saw the whole tick's state, with no split-transaction case. Things to test: that `Ping`
  is allowed inside a bundle on the decode side (only "terminal" packets are rejected, `network/PacketBundlePacker.java:44`),
  and how bundle size interacts with Leaf/Paper flush batching.

### 4.4 Attacks on the clock (why authority helps)

- A modified client can answer pings early, before processing the packets in front of them, or late (ping spoof).
  Grim cannot stop either. It only flags skipped ids and enforces timeouts.
- Under authority, the physics truth does not depend on pong timing. Pong timing only decides when the client is
  *assumed* to have seen a state. A lying client can choose its knockback tick only within the lag it is willing to
  show, and corrections catch the rest.
- **Reach/backtrack is the real risk.** Delaying pongs while still processing packets makes the server think the
  client saw older target positions, which widens what counts as a valid hit. Cap the lag-compensation window
  (Thwack uses `min(maxRewindMs, ping + 100)`, curseforge.com/minecraft/mc-mods/thwack). Intave buffers outbound
  packets when pongs or moves stop (section 11.1).

---

## 5. 1.21.2+ input packet and tick-end packet usage

- **Why Mojang added them.** On the server, the input packet sets `lastClientInput` and the shift key
  (`server/network/ServerGamePacketListenerImpl.java:434-441`). It is used by advancement `input` predicates
  (`advancements/predicates/entity/PlayerPredicate.java:134`). The tick-end packet zeroes the player's
  "known movement" when no move came that tick (`ServerGamePacketListenerImpl.java:2264-2272`). That fixes stationary
  players reading as moving (MC-273828, listed fixed in 1.21.2 on minecraft.wiki/w/Java_Edition_1.21.2).
  Known movement feeds projectile inheritance, kinetic weapons (through `getKnownSpeed`) and leashes
  (`world/entity/projectile/Projectile.java:192`; `world/item/component/KineticWeapon.java:83`;
  `world/entity/Leashable.java:222-229`). Neither packet was designed
  for anticheat, but both make exact simulation possible.
- **How Grim uses them.**
  - The tick packet is a flying packet, or a tick-end when no flying packet came before it this tick
    (`checks/GrimProcessor.java:76-97`; `CheckManagerListener.java:597-610, 780-782`).
  - With an end tick the "ticking reliably" flag is true, so reach interpolation uses exact step counts
    (`GrimPlayer.java:664-683`).
  - `TickTimer` flags more than one move per tick-end, or a move without a tick-end (`checks/impl/timer/TickTimer.java:30-44`).
  - `BadPacketsZ` flags two input packets in one tick (`checks/impl/badpackets/BadPacketsZ.java:24-35`).
  - `ViaBackwards` emulates tick-end for older clients, so Grim ignores it pre-Via (`GrimProcessor.java:64-70`).
  - Known keys collapse the input loop (`PredictionEngine.java:737-761`). Before 1.21.5 the key vector scale is
    0.98 (`PacketPlayerSteer.java:70-72`). 1.21.5+ normalizes, then scales by 0.98, the item-use multiplier and sneak
    speed, then applies "square movement" (`ModernInputTransformer.java:16-56`).
- **Ordering inside one 26.3 client tick** (vanilla, verified):
  1. Frame start: process queued packets (pongs go out here) (`client/Minecraft.java:1202`).
  2. `pick(1.0F)` (`:1812`), then `handleKeybinds` (`:1822`): attack, use item, swing, hotbar and release packets.
  3. `level.tickEntities()` (`:1833`): local player movement, then remote entity interpolation step.
  4. `player.sendChanges()` (`:1838`): input packet if changed (`LocalPlayer.java:266-269`), then sprint command if
     changed, then move packet (`:295-313`).
  5. `ClientTickEnd` (`client/Minecraft.java:1883-1886`).

---

## 6. Compensated world and the knockback/explosion handlers

- Block updates are scheduled at the pre-ping and only send a ping if within 16 blocks and none was sent in the last 2 ms
  (`BasePacketWorldReader.java:147-180`). Applying at the pre-ping (not post) means a short window where Grim thinks a
  block changed before the client does. Grim lives with it, with resyncs as the backstop (`predictionengine/GhostBlockDetector.java:43-63`).
- 1.19+ prediction sequences are handled in `CompensatedWorld.java:103-217`, 274-291 (section 2, row 18).
- Knockback ignored or delayed: if the best candidate was not knockback while a required knockback was pending, flag,
  and set back with the knockback re-applied (`KnockbackHandler.java:179-231`; `MovementCheckRunner.java:588-605`).
  Defaults: threshold 0.001, immediate setback 0.1, max advantage 1, ceiling 4 (`KnockbackHandler.java:251-260`).
- Setbacks carry the pending ("future") knockback so it cannot be dodged by provoking a setback (`SetbackTeleportUtil.java:172-187`).
- **For us:** knockback is just state applied at the ack tick, in the order of the bundle. Velocity sets, explosion
  adds. The only real work is quantization (`LpVec3`) and making RevivalPaper's 1.7 knockback write the
  **quantized** vector into the authoritative state.

---

## 7. (b) Correcting a vanilla client with minimum visible rubber-banding

### 7.1 What Grim does (`manager/SetbackTeleportUtil.java`)

- **Last known good position:** updated after each accepted prediction with the after-friction velocity (`:77-97`).
- **Setback** (`:159-233`): start from the last good position and velocity, swap in future knockback/explosion,
  then run one collision step and friction ("mini prediction engine", `:194-219`). That way the player continues the
  motion they should have had, instead of being frozen.
- **Sending** (`:250-318`): ping, then an absolute-position teleport with **relative yaw and pitch at 0** so the camera
  does not snap (`:231, 304`), then ping, then a separate velocity packet if non-zero (`:312-314`). The teleport id is
  random with the sign bit set (`:292`). `onGround` for the setback is computed (`:235-248, 294`) and then forced onto
  the client's next move packet (`CheckManagerListener.java:587-595`).
- **One outstanding setback at a time.** `isPendingSetback` ignores relative setbacks (`SetbackTeleportUtil.java:420-427`).
  While a setback is pending, offsets are blocked and attacks and places are cancelled (`shouldBlockMovement`, `:414-418`;
  `Reach.java:97-102`; `CheckManagerListener.java:538`).
- **Accept only between the pre and post pongs**: `lastTransactionReceived == teleport.transaction`. If the client
  answers later, it "ignored the teleport" and gets a resend (`SetbackTeleportUtil.java:344-368`).
- **World resync** at most every 5 s around the player (`:166-170`).
- **Pitfalls Grim documents:** 1.21.2+ clients ignore teleports while riding (`PacketServerTeleport.java:113-116`).
  Relative teleports are converted to absolute for 1.8 and vehicles (`:55-88`). Teleport velocity in vehicles is
  stripped (`:90-111`). Riding exits are "glitchy" (`MovementCheckRunner.java:190-253`).

### 7.2 What vanilla does (for contrast)

- `teleport(x, y, z, yRot, xRot)` sends **zero velocity with no relative flags** (`server/network/ServerGamePacketListenerImpl.java:1321-1323`, sent at `:1325-1334`).
  So every vanilla "moved wrongly" correction also stops the player dead.
- It resends the teleport every 20 ticks until accepted (`:1285-1302`) and ignores the client's moves in between.
- In 26.3 the client's teleport handler also stops block breaking and resets block prediction
  (`client/multiplayer/ClientPacketListener.java:801-806`). Corrections have gameplay side effects beyond position.

### 7.3 Recommendations for our server

1. **Correct rarely.** Port exactly, apply server state at the exact ack tick, and treat any correction as a bug report.
   In Grim's experience most corrections come from state the server sent and failed to model (knockback, blocks,
   item use, attributes), not from physics.
2. **Re-base inside epsilon.** When the client's reported position is within ε of ours, adopt the client's position,
   as Grim does every tick. That stops floating drift from accumulating. Suggested ε: start at 1e-7 and widen only with
   measured need. Grim tolerates 1e-3 per tick (`OffsetHandler.java:105`), too loose for esports.
3. **Accumulate before correcting**, Grim-style: correct when the summed excess passes a budget, or one tick is grossly
   off. Grim uses budget 1.0 and immediate 0.1 (`OffsetHandler.java:106-107`). Pick tighter values once exactness is proven.
4. **Correct relatively when possible.** Send `Relative` X/Y/Z with Δpos and DELTA_X/Y/Z with Δvel, plus Y_ROT and X_ROT
   relative at 0 (`world/entity/Relative.java:11-23`; `PositionMoveRotation.calculateAbsolute`, `world/entity/PositionMoveRotation.java:38-65`).
   - Why it is smoother: for relative axes the client also shifts its previous-tick position by the same delta
     (`setOldPosAndRot(calculateAbsolute(oldPos, change, relatives))`, `ClientPacketListener.java:824-826`). Frame
     interpolation keeps its motion and only the offset jumps. An absolute teleport sets the old position equal to
     the new one, which shows a one-frame hitch.
   - Why it loses fewer inputs: Δ is computed against the client's state at the tick we judged (tick k). The client
     applies it n ticks later. If movement over those n ticks is translation-invariant (no collision differences), the
     client lands exactly on our state at tick k+n, with no lost inputs. If collisions differ, the next tick shows a
     small residual and a second correction fixes it. Under absolute correction, the client restarts from S(k) at tick
     k+n and loses about one RTT of input. That is the visible rubber band.
   - The server must not snap on accept. Vanilla's `handleAcceptTeleportPacket` does `absSnapTo(awaitingPositionFromClient)`
     (`ServerGamePacketListenerImpl.java:555-578`). Patch it so the sim applies the same relative Δ to its own record
     of the client view and keeps simulating.
5. **Always carry velocity.** Teleport velocity is full-precision doubles (`Vec3.STREAM_CODEC`, `world/phys/Vec3.java:25-28`),
   unlike the quantized motion packet. Never send vanilla's zero velocity.
6. **Keep the camera.** Rotation must be relative 0/0 (Grim `SetbackTeleportUtil.java:231`), or 26.3's `ROTATE_DELTA`
   rules apply (`PositionMoveRotation.java:48-53`).
7. **One correction in flight.** Ping before and after it. While it is pending, keep simulating the client's ticks,
   because they are still real inputs from before the client saw the correction. Do not judge them.
8. **Prefer fixing velocity over position.** If the divergence is only in velocity (for example a missed knockback
   that has not yet moved the player far), a relative DELTA correction moves no position at all.
9. **Never correct in unknown states** (unloaded chunk, riding, just-respawned, sleeping, dead). Grim exempts all of
   these (section 2).
10. **Avoid correction side effects in fights.** A 26.3 teleport stops block breaking (`ClientPacketListener.java:804-806`).

---

## 8. (c) Reach and lag-compensation model and its tolerance

### 8.1 Grim's model

- **Entity tracking:** `TrackedPosition` mirrors the server's 1/4096 delta packing (`utils/data/TrackedPosition.java:7-47`).
  26.3 move packets are converted from packetevents' `VecDelta` or `PositionPath` (`PacketEntityReplication.java:140-196`).
- **Interpolation models, chosen per client version and entity** (`utils/data/interpolation/EntityInterpolations.java:15-33`):
  - **26.3 stepped** for living entities except shulkers. Interval 2 for player/allay/mannequin, 3 otherwise.
    Matches vanilla `SteppedInterpolationHandler`: queued steps; `interpolationSpeed = lerp(1/interval, speed,
    max(remaining/interval, 1))`; sample, then advance; reset speed to 1 on finishing
    (`world/entity/SteppedInterpolationHandler.java:27-40, 68-80, 180-199`; Grim `SteppedInterpolationState.java:126-197`).
    It tracks a **progress range** (min/max path progress, min/max speed, float rounding error) when transactions are
    split or ticks unreliable (`SteppedInterpolationRange.java:14-49`). It collapses to a single exact point otherwise
    (`SteppedEntityInterpolation.java:117-119, 248-260`). Distant (over 64 block) position syncs shift queued steps
    (`:160-201`). Duplicate targets are "optional steps" that the client may ignore (`:63-78, 220-246`).
  - **Legacy:** steps of 3 for living, 10 for boats, 5 for minecarts, 1 otherwise (`utils/data/ReachInterpolationData.java:65-75`).
    The low/high step bound becomes a box (`:190-226`). The old and new packet locations are combined until the
    second transaction arrives (`utils/data/interpolation/LegacyEntityInterpolation.java:111-152`). 1.21.9+ ignores
    packets that restate the current target (`:40-69`). MC-255263 "freeze" for 1.15 to 1.20.1 and 1.21.5 to 1.21.8 (`:77-97`).
- **Attack processing** (`checks/impl/combat/Reach.java`):
  - On attack: cancel during a pending setback (`:97-102`). Cancel unknown or untracked entities (`:104-115`).
    Exempt dead, creative/spectator, riding, ridden, boats and shulkers (`:62-65, 117-129`).
  - Real-time pre-check `isKnownInvalid`: minimum distance from the eye to the target box, without rotation. Cancels
    impossible hits immediately (`:191-226`).
  - Queued post-check on the next tick packet with the new rotation (`:91-93, 228-256`). Ray from each possible eye
    height along each possible look (`:266-304`). Ray length `maxReach + 3` (`:283`). Flag **REACH** if the closest
    intercept exceeds the limit, **HITBOX** if the ray misses (`:306-317`).
- **Tolerance:**
  - Base hitbox expansion `Reach.threshold` = **0.0005** (`:377`).
  - +0.1 for clients older than 1.9: "this is vanilla and not uncertainty" (`:352-357`).
  - + the movement threshold (0.03 or 2e-4) only when ticks may be skipped. On 1.21.2+ that applies only if the last
    move had no position (`:360-367`).
  - Item `attack_range` component: min of start-of-tick and current ranges and margins (`:139-181`).
  - The target box is the union over every position the client could have rendered (section 2 row 49). Uncertainty
    lives in the box, not the margin.
- **Intave for comparison:** hitbox expansion baseline 0.04 for tick-end clients, 0.13 for modern clients without it,
  0.1 or 0.13 for 1.8 (`check/combat/AttackRaytrace.java:923-941`). It checks all 3 interpolation positions and takes
  the lowest reach (`:571-590`). It uses the current and last yaw and fixes the mouse-delay quirk only for protocol
  ≥ 314 (`:902-907`).

### 8.2 What we should do

- With per-tick exact paths, bundles and the stepped model, the target position the attacker's client used is a
  **single point**: the remote entity's position after its interpolation step in client tick t-1, where t is the tick
  whose `handleKeybinds` sent the attack. Use an epsilon margin (≤ 1e-6) instead of 0.0005. Fall back to Grim's box
  only when an ack is missing.
- Eye: the attacker's position at the end of tick t-1 (the last authoritative position) plus the pose eye height.
  Rotation: from tick t's move packet (`client/Minecraft.java:1812, 1822, 1838`).
- Cap the window (section 4.4). Grim has no cap besides `max-transaction-time` (60 s kick).

---

## 9. (d) Timer and balance model

### 9.1 Grim

- `Timer` (`checks/impl/timer/Timer.java:15-112`):
  - `balance += 50 ms` per tick packet (`:66-69`).
  - If `balance > System.nanoTime()`, flag, cancel the packet and optionally set back, then `balance -= 50 ms` (`:74-90`).
  - **Floor:** `balance = max(balance, lastMovementPlayerClock - drift)`, with drift 120 ms by default (`:95-97, 111`).
    `lastMovementPlayerClock` is the send time of the most recent acknowledged ping, sampled at the first transaction
    after each movement (`:59-64`; `GrimPlayer.java:440`). The proof sketch is in comments `:27-53`: the client's
    clock is at least the send time of the newest ping it has answered.
- `TimerLimit`: same, but when the transaction ping exceeds 1000 ms the floor uses `now - 1000 ms` instead, limiting
  banked time for very laggy players (`checks/impl/timer/TimerLimit.java:20-55`).
- `NegativeTimer` (experimental): flags when the balance falls more than 1200 ms behind (`checks/impl/timer/NegativeTimer.java:13-55`).
- `TickTimer` (1.21.2+): exactly one move per tick-end (`TickTimer.java:30-44`).
- Vanilla's own guard: more than 5 move packets in one server tick is only logged and treated as 1
  (`ServerGamePacketListenerImpl.java:1154-1159`).

### 9.2 Intave (ideas)

- `Balance`: `+50 ms - delta` per move, clamped to `[-allowedLag, +1000 ms]` (`check/movement/timer/Balance.java:70-79`). Deprecated.
- `BalanceButActuallyGood`: send a transaction every 50 ms of server time; +1 per acknowledgement, -1 per client move;
  flag when below 0 (`check/movement/timer/BalanceButActuallyGood.java:37-47, 53-75, 88-94, 100-107`). Counts client ticks against
  **acknowledged server ticks** rather than arrival time. Deprecated too, but clean.

### 9.3 Ours

- Count client ticks by tick-end packets. Every tick is simulated, so a speed hack moves more per second than allowed.
  Grim's floor (ack clock minus a small drift) is the right reference.
- Under authority, **refuse surplus ticks**: run a tick budget refilled at 20 per second, measured from the ack clock.
  Queue ticks that exceed it; if the queue grows past a cap, drop the surplus and correct. That bounds speed without
  any flags.
- Blink and lag-switch: when tick-ends stop arriving, freeze the player for viewers (do not extrapolate, or viewers see
  a path the server later rewrites). Cap the backlog the server will replay (Grim's 1000 ms `TimerLimit` is a good
  default). Consider Intave's anti-blink buffer for outbound packets (section 11.1).

---

## 10. Cases Grim gave up on, or handles by hack

1. Flying (creative, spectator, `mayfly`): EXEMPT, "TODO: Re-implement flying support" (`MovementCheckRunner.java:494-503`).
2. Elytra can be EXEMPT by feature flag (`MovementCheckRunner.java:494`; `manager/player/features/types/ExemptElytraFeature.java`).
3. Uncontrollable vehicles: `wasChecked = false` (`MovementCheckRunner.java:570-573`). Vehicle switching guesses the start position (`:190-253`).
4. Boats and other hard-colliding interpolated entities: offset -1.2, box to zero, "Boats are too glitchy to check" (`UncertaintyHandler.java:292-297`; `PredictionEngine.java:645-679`).
5. Entity pushing: a band, not simulated (`MovementTicker.java:69`; `PredictionEngine.java:540`).
6. World-border hard clamp: TODO, returns input unchanged (`PredictionEngine.java:33-36`).
7. Pistons, fireworks and rod pulls: bands (`PredictionEngine.java:525-528, 618-634`; `UncertaintyHandler.java:143-212`). Rod pull is not sandwiched (`PacketEntityReplication.java:313`).
8. Flight toggles: +0.3 lenience for 4 ticks (`PredictionEngine.java:507-514`).
9. Ground riptide: a heuristic guess (`MovementCheckRunner.java:513-534, 702-712`).
10. Sneak edge: "START HACKERY" (`SneakingEstimator.java:58`).
11. Bouncy blocks: -0.03 "hack around current issues with Grim itself" (`UncertaintyHandler.java:303-307`).
12. Vehicle boost timing: -0.01 "I can't figure out how the client exactly tracks boost time" (`UncertaintyHandler.java:309-313`).
13. Before 1.18.2: knockback within 0.03 can be ignored (`PointThreeEstimator.java:164-166`).
14. Reach: boats and shulkers blacklisted, dragon parts TODO, vehicles exempt (`Reach.java:62-65, 104-129`).
15. Ghost blocks on old clients: resync instead of predict (`GhostBlockDetector.java:17-63`).
16. Velocity y = -0.04 is rewritten because of an unexplained client bug (`KnockbackHandler.java:65-70`).
17. Dead and sleeping players are not checked (`MovementCheckRunner.java:255-261, 489-493`).

For an HCF/1.7 esport most of these can be **designed out**: no elytra, tridents, vehicles, pistons or player
collisions in arenas, and arenas inside the world border.

---

## 11. Other approaches

### 11.1 Intave (source-available since mid-2025; PolyForm Perimeter 1.0.0)

- README: movement simulation from 1.8 to 26.3. It "simulates multiple ticks between the currently sent and previous
  movement" for 1.9-1.20.1. It uses "double latency compensation" so a late ack does not false. Every build is
  unit-tested against recorded movement scenarios (`intave/README.md`, Detection section). **The recorded-scenario
  regression suite is the idea worth copying** (the idea only, not the code).
- `PacketDelayer` "reverse blink": when the oldest unanswered transaction or the last move packet is older than a
  tolerance, **buffer outbound packets** to that player and flush later. Attacks are blocked 250 ms after a flush
  (`module/feedback/PacketDelayer.java:140-175` timeouts and `requestBuffer`, `:192-201` buffering, `:249` attack block). It limits what a lag-switching player learns while frozen.
- Random transaction ids, so a client cannot predict and pre-answer pings (`module/feedback/FeedbackSender.java:296-340`).
- Reach tolerance is listed in section 8.1.

### 11.2 Pledge (MIT)

- Library for "predicting when a server packet reaches a Minecraft client" by bracketing data between pings, with a
  scheduler that pings at tick start and end (github.com/ThomasOM/Pledge README). Grim's end-of-tick injection is
  copied from it (`bukkit/.../BukkitTickEndEvent.java:25`).

### 11.3 Bedrock server-authoritative movement (Mojang)

- `server-authoritative-movement` = `client-auth`, `server-auth` or `server-auth-with-rewind`. Under server-auth the
  server replays player input and corrects on divergence. With rewind the client rolls back to the correction and
  replays its later inputs: smoother, with more frequent corrections. `correct-player-movement` and
  `player-movement-score-threshold` gate corrections (pingperfect.com Bedrock server configuration; a Mojang BDS bug
  report notes the distance/duration thresholds are disabled under server-auth).
- **Lesson:** this is exactly the design we want, minus the client half. Java clients cannot rewind, so correction
  quality depends on section 7.3 (relative deltas and rarity).

### 11.4 Vanilla Java validation over time (from 26.3 source unless noted)

- "Moved too quickly": squared distance from the tick's first good position minus expected squared speed, over
  100 per packet (300 when gliding) (`ServerGamePacketListenerImpl.java:1141-1167`). Gated by game rules
  `PLAYER_MOVEMENT_CHECK` and `ELYTRA_MOVEMENT_CHECK` (`:1246-1255`). `disablePlayerMovementCheck` was added in 1.21.2
  (minecraft.wiki/w/Java_Edition_1.21.2).
- "Moved wrongly": the server re-moves the player by the client's delta with collisions and checks the residual,
  squared, against 0.0625 (0.25 blocks). The Y residual is always zeroed because of an always-true condition,
  `if (yDist > -0.5 || yDist < 0.5) yDist = 0.0` (`:1185-1203`). Exempt during post-impulse grace
  (`isInPostImpulseGraceTime`, `world/entity/LivingEntity.java:1857-1859`).
- Floating kick after `ceil(80 × max(0.08/gravity, 1))` ticks (`:336-340, 373-380`). Collision-with-new-shape check
  (`:1305-1319`).
- 1.21.2: client tick-end and input packets (section 5). 26.3: teleport accept carries position and rotation
  (`ServerboundAcceptTeleportationPacket.java:9`; server `handleAcceptTeleportPacket` `:555-578`).
- **Lesson:** vanilla validation is a coarse tolerance check with snap-backs that zero velocity. We replace it and
  must patch out its teleports, or they will fight ours.

### 11.5 Minestom, Polar, Hypixel

- No public Minestom movement-prediction or anticheat implementation was found (web search returned only
  Grim-related results).
- Polar is SaaS. Movement checks that need real-time setbacks run in the plugin; the rest runs in the cloud
  (builtbybit Polar Q&A). No technical write-up found.
- Hypixel Watchdog: no public technical write-up found.

### 11.6 Generic lag compensation (Source engine, Thwack)

- Keep per-tick history, rewind the target to the shooter's view time, and cap the rewind. Thwack (a Fabric mod that
  needs both sides, so not usable with vanilla clients) keeps 1 s of per-tick hitboxes, refuses hits that match
  no snapshot within 0.75 blocks, and caps the window at `min(maxRewindMs, ping + 100)` (curseforge.com/minecraft/mc-mods/thwack).
- **Lesson:** with transactions we do not need to estimate view time from ping. The pong tells us exactly which
  server tick's state the attacker's client had. Use the ping only for the cap.

---

## 12. (e) Performance

- Grim keeps rolling averages of milliseconds per prediction, over 500 and 20 000 samples, shown by `/grim perf`
  (`MovementCheckRunner.java:56-59, 84-91`; `command/commands/GrimPerf.java`). The seed values are **0.3 ms**, with the
  comment "Defaults set slightly above my 3600x results", so typical cost was a bit under 0.3 ms per prediction on a
  Ryzen 5 3600X. At 20 Hz that is under about 6 ms of CPU per player per second, roughly 0.6 % of a core per player,
  or about 0.6 cores for 100 players. It runs on Netty threads, off the main thread.
- The cost comes from brute force: candidates × collision runs, with early exit at a squared error of 1e-10
  (`PredictionEngine.java:177-180`). With 1.21.2+ known input the 9-way key loop drops to 1, but ×2 item use, ×2 slow
  and ×stuck-speed variants remain (`:764-811`).
- An exact authoritative step is one vanilla movement step plus one collision resolution per tick. That should be
  near a vanilla `ServerPlayer` movement tick, which is microseconds to tens of microseconds. **Unmeasured. Benchmark
  it** (Intave ships a JMH-style simulator benchmark harness: `src/bench/java/de/jpx3/intave/benchmark/SimulatorBenchmark.java`).
- Reach: one ray-box intersection per eye height and look per attack (`Reach.java:286-304`). Negligible.
- Memory: Grim keeps a whole per-player world copy, compressed to 16-64 KB per chunk (README "Full world replication").
  Our server already has the world. Only per-player pending block predictions and unacknowledged changes need storing.

---

## 13. (f) Licensing notes for reimplementing ideas

- **Grim: GPL-3.0** (`Grim/LICENSE`). Headers say "version 3 ... or any later version" (for example
  `utils/data/packetentity/PacketEntity.java:1-15`). Several files carry the extra line "You may not copy this check
  unless your anticheat is licensed under GPL" (`PacketEntity.java:48`; `checks/impl/combat/Reach.java:57`;
  `utils/data/ReachInterpolationData.java:31`).
  - RevivalPaper binaries are GPL-3.0-only and patches are MIT unless a header says otherwise (RevivalPaper `LICENSE.md`).
    Copying Grim code would force those patches to carry a GPL header. The decision is not to copy: implement
    clean-room from vanilla source plus the ideas here. Ideas, algorithms and facts about vanilla behaviour are not
    copyrightable. Do not transcribe Grim's structure or comments.
- **Intave: PolyForm Perimeter 1.0.0** (`intave/LICENSE.md`). Any use is permitted **except** "providing to others any
  product that competes with the software". An authoritative server that makes an anticheat unnecessary could be
  argued to compete. Read for ideas only, never copy or closely paraphrase code, and keep any Intave-derived idea at
  the level described in this report.
- **Pledge: MIT.** Copying with attribution is allowed, but we do not need it, because the server can send pings natively.
- **PacketEvents** (Grim's packet layer) is GPL-3.0. We do not need it.
- **Mojang code:** the physics port should reuse server-jar classes (`LivingEntity.travel`, `Entity.move`, collision)
  through patches, as Paper does. Client-only logic (`LocalPlayer.aiStep` input handling, `KeyboardInput`, sprint rules)
  is not in the server jar. Re-implement the few lines we need instead of copying whole decompiled client classes
  into our source tree.

---

## 14. Open questions to verify before building

1. Can a `ClientboundPingPacket` live inside a bundle, and does the pong ordering hold under real TCP splits? (Section 4.3.)
2. Does a 26.3 client still need the `lastYaw`-based look variants for attacks (MC-67665), or is the tick-t rotation exact? (Section 8.)
3. The unexplained y = -0.04 velocity client bug (`KnockbackHandler.java:65-70`): reproduce it on 26.3.
4. MC-69459: confirm on 26.3 how the server's movement-speed `UpdateAttributes` interacts with the client's own sprint modifier.
5. How RevivalPaper's server-only rod pull and the motion shadow's input-free 1.7 model coexist with an authoritative velocity (sections 2.4 row 45 and 6).
