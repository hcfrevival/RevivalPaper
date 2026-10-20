# Leaf defaults that change behaviour

This lists what RevivalPaper inherits from Leaf. It covers config options that are **on by default** and
patches that change behaviour with **no config gate**, wherever they differ from Purpur 26.2. It is
written for a PvP arena server (practice and duels) moving from Purpur to RevivalPaper, where the goal is
to behave like Purpur apart from the combat changes RevivalPaper makes on purpose.

- Base: Leaf branch `ver/26.3`, commit `0edc7f3` ("Fix async tracker (#949)").
- From `gradle.properties`: `mcVersion=26.3` and `paperCommit=1c92a6c3a42deda2fbefdec1a0d90d696313ff3b`.
- Sources read:
  - Leaf config modules in `leaf-server/src/main/java/org/dreeam/leaf/config/modules/**`. These generate
    `config/leaf-global.yml`, config-version 3.0.
  - Gale config in `leaf-server/src/main/java/org/galemc/gale/configuration/GaleGlobalConfiguration.java`
    (`config/gale-global.yml`) and `GaleWorldConfiguration.java` (`config/gale-world-defaults.yml` and
    per-world `gale-world.yml`).
  - The patches that read each setting: `leaf-server/minecraft-patches/features/` (317 patches),
    `leaf-server/paper-patches/features/` (67) and `leaf-api/paper-patches/features/`.
- How each item was assessed:
  - Defaults were read from the source.
  - Behaviour was read from the patch diffs.
  - Nothing was built or run.
  - Items marked **(uncertain)** need an in-game check.
- Some settings are read once at startup and need a full restart to change:
  - `async.async-mob-spawning`
  - `async.async-pathfinding`
  - `async.async-entity-tracker`
  - `gameplay-mechanisms.knockback.use-legacy-tracker-ticking`
  - `performance.sleeping-block-entity`
  - `misc.region-format`

RevivalPaper's own legacy combat (see `AGENTS.md`) touches the same code as several items below: sprint
state, the timing of velocity packets, and `PlayerVelocityEvent`. Those items are marked **[combat]**.

---

## 1. Config defaults

The recommendation column uses three values:

- **keep**: the default is fine for arena.
- **turn off**: override it in the arena config.
- **review**: test it, or decide deliberately.

Unless stated otherwise, the module paths below are relative to
`leaf-server/src/main/java/org/dreeam/leaf/config/modules/`.

### 1a. Leaf settings that are ON by default and change behaviour

| Key (`leaf-global.yml`) | Default | What it changes | Risk for a PvP server with plugins | Arena |
|---|---|---|---|---|
| `misc.including-5s-in-get-tps` | `true` | `Bukkit.getTPS()` returns **4** values `[5s, 1m, 5m, 15m]` instead of Paper/Purpur's 3 `[1m, 5m, 15m]`, so index 0 now means the 5-second average. Sources: `misc/Including5sIngetTPS.java:12,16` and `leaf-server/paper-patches/features/0019-Including-5s-in-getTPS.patch`. | **High (plugin-visible).** Plugins that read `getTPS()[0]` as the 1-minute TPS, or assume length 3, change meaning. This includes anticheat lag compensation, TPS placeholders and monitoring. | **turn off** |
| `async.async-mob-spawning.enabled` | `true` | Natural spawning counts mobs on another thread and applies the result a tick later (Pufferfish). It only runs when Paper `per-player-mob-spawns` is on and the world spawns animals or monsters. Restart required. Sources: `async/AsyncMobSpawning.java:12,35` and `minecraft-patches/features/0077-Pufferfish-Optimize-mob-spawning.patch`. | Low, because arena has no natural mobs. It is still an extra thread, and it differs from Purpur. | **turn off** (for parity; nothing is lost) |
| `performance.optimize-player-movement` | `true` | Skips `maybeBackOffFromEdge` in `Entity.move` when the server simulates player movement. That is the server-side "sneaking stops you at the edge" step, and only `Player` overrides it. It also short-circuits Paper's per-player area-map updates when the player has not changed chunk. Sources: `opt/OptimizePlayerMovementProcessing.java:12,16`, `minecraft-patches/features/0203-Optimise-player-movement-checks.patch` and `paper-patches/features/0049-Optimise-player-movement-checks.patch`. | Low to medium **[combat]**. The client still does its own edge back-off. Only the server's re-simulation is more lenient, which matters for plugins or anticheat that compare against the server's position while sneaking (edge-sneaking at map borders, bridge fights). **(uncertain: confirm in game)** | **review** (keep unless sneak-edge or movement checks show differences) |
| `performance.use-virtual-thread.async-chat-executor` | `true` | `AsyncChatEvent` and legacy async chat run on a virtual thread per message. Paper's fixed chat-executor pool is not used. Sources: `opt/VirtualThreadSupport.java:14,27`, `minecraft-patches/features/0107-Virtual-thread-support-for-chat-executor.patch` and `paper-patches/features/0025-Virtual-thread-support-for-chat-executor.patch`. | Low. The event contract is unchanged and chat is still off the main thread. A plugin that blocks for a long time inside `AsyncChatEvent` now gets unbounded concurrency instead of a queue. | keep (turn off only if a chat plugin assumes a bounded pool) |
| `performance.optimize-block-entities` | `true` | Ticks block entities with less overhead (equivalent behaviour). | Low. | keep |
| `performance.enable-cached-minecraft-to-bukkit-entitytype-convert` | `true` | Caches the Minecraft-to-Bukkit `EntityType` lookup (equivalent). | Low. | keep |
| `performance.faster-structure-gen-future-sequencing` | `true` | Speeds up world generation. | None for arena, which uses a pre-built map. | keep |
| `performance.skip-map-item-data-updates-if-map-does-not-have-craftmaprenderer` | `true` | A map whose renderers do not include the stock `CraftMapRenderer` skips the vanilla exploration update (`minecraft-patches/features/0102-SparklyPaper-Skip-MapItem-update-if-the-map-does-not.patch`). | Low. It only matters for custom map renderers that also expect vanilla exploration to keep drawing. | keep |
| `performance.create-snapshot-on-retrieving-blockstate` | `true` | Same as Paper's default (BlockState snapshots). | None. | keep |
| `network.chat-message-signature` | `true` | Vanilla secure chat, the same as Purpur. | None (parity). | keep |
| `misc.connection-message.join.enabled` / `.quit.enabled` | `true`, message `"default"` | `"default"` keeps the vanilla join and quit message (`minecraft-patches/features/0115-Configurable-connection-message.patch`). Setting `enabled: false` hides the message even if a plugin set one. | Low. | keep |
| `misc.vanilla-username-check.enforce-skull-validation` | `true` | Vanilla username validation for player skull profiles. | Low. | keep |
| `gameplay-mechanisms.death-item-drop-knockback.drop-around` | `true` (`horizontal-force: 0.5`, `vertical-force: 0.2`) | These are the vanilla constants, now configurable (`minecraft-patches/features/0204-Add-configurable-death-item-drop-knockback-settings.patch`). | None (parity). | keep |
| `gameplay-mechanisms.knockback.can-player-knockback-zombie` | `true` | Vanilla behaviour. | None. | keep |
| `gameplay-mechanisms.player.max-use-item-distance` | `1.0000001` | The vanilla use-item-on distance check, now configurable. Setting `-1` disables the check (`minecraft-patches/features/0084-Remove-UseItemOnPacket-Too-Far-check.patch`). | None at the default. | keep |

### 1b. Leaf features OFF by default that must stay off for arena

These are the async or behaviour-changing switches. All are off by default. List them in the arena
config explicitly anyway, so nobody turns one on by accident.

| Key (`leaf-global.yml`) | Default | What it would change | Risk if enabled | Arena |
|---|---|---|---|---|
| `async.async-entity-tracker.enabled` (`threads: 0`) | `false` | Moves the entity tracker off the main thread. The tracker sends position, velocity and equipment packets. `PlayerVelocityEvent` and velocity sends then happen inside the async tracker path. Marked `@Experimental` (`async/MultithreadedTracker.java:37`, `minecraft-patches/features/0259-Multithreaded-Tracker.patch`, around line 731). | **High [combat].** The timing of knockback packets and the thread `PlayerVelocityEvent` fires on both change, and AGENTS.md forbids this kind of thread move for combat state. | keep **off** |
| `async.parallel-world-ticking.enabled` (`threads: 8`) | `false` | SparklyPaper parallel world ticking. Marked `@Experimental` and deprecated for removal. | **High.** Thread-safety for every plugin. | keep **off** |
| `async.async-pathfinding.enabled` | `false` | Mob pathfinding off the main thread. | Medium (mobs only). | keep off |
| `async.async-playerdata-save.enabled` | `false` | Saves player `.dat` files asynchronously (`paper-patches/features/0048-Async-playerdata-saving.patch`). | Low to medium (save ordering on quick rejoin or server switch). | keep off |
| `network.async-switch-state` | `false` | Handles the configuration/play switch asynchronously (`network/AlternativeJoin.java`). | Medium (join flow for proxy and client-mod handshakes). | keep off |
| `network.OptimizeNonFlushPacketSending` | `false` | Changes when packets are flushed. | Medium **[combat]** (packet timing). | keep off |
| `performance.reduce-packets.reduce-entity-move-packets` | `false` | Drops entity move packets it considers useless. | Medium **[combat]**. Opponents' movement would look different. | keep off |
| `performance.reduce-packets.reduce-entity-motion-packets` | `false` | Filters `ClientboundSetEntityMotionPacket`, the velocity and knockback packet. | **High [combat].** It can drop knockback. | keep **off** |
| `performance.reduce-packets.disable-useless-particles` | `false` | Skips some particle packets. | Low (visual). | keep off |
| `performance.only-tick-items-in-hand` | `false` | Only ticks inventory items held in the hand. | Medium. Maps, compasses and plugin items that rely on inventory ticking would stop updating. | keep off |
| `performance.dab.enabled` (`start-distance: 12`, `max-tick-freq: 20`, `activation-dist-mod: 8`) | `false` | Dynamic Activation of Brain, which lowers AI tick rate for distant mobs. See also patch 0078/0099 in section 2. | Low on arena (mobs only). | keep off |
| `performance.optimize-entity-activation` | `false` | A changed version of Paper entity activation range. | Low on arena. | keep off |
| `performance.throttle-mob-spawning.enabled`, `performance.optimize-mob-spawning`, `performance.optimize-mob-despawn` | `false` | Spawning and despawn changes. | Low on arena. | keep off |
| `performance.optimize-random-tick`, `performance.reuse-random-ticking-blockpos`, `performance.faster-random-generator.enabled`, `performance.cache-biome.enabled` | `false` | Random-tick and RNG changes. `faster-random-generator` changes which RNG is used. | Low. | keep off |
| `performance.optimized-powered-rails` | `false` | Powered-rail update order. | Low (redstone). | keep off |
| `performance.sleeping-block-entity` | `false` | Puts idle block entities (hoppers and similar) to sleep. Restart required. | Low to medium (hopper and furnace timing). | keep off |
| `performance.despawn-time.proactive-weak-loading-despawn` | `false` | Despawns entities in weakly loaded chunks. | Low. | keep off |
| `performance.dont-save-entity.dont-save-primed-tnt` / `dont-save-falling-block` | `false` | Skips saving these entities. | Low. | keep off |
| `performance.check-survival-before-growth.cactus-check-survival` | `false` | Cactus growth check order. | Low. | keep off |
| `performance.optimize-no-action-time.disable-light-check` | `false` | Mob despawn light check. | Low. | keep off |
| `performance.optimize-waypoint` | `false` | Locator-bar waypoint updates. | Low. | keep off |
| `performance.datapack.skip-inactive-entity-for-execute-command` | `false` | `/execute as @e` skips inactive entities. | Medium if enabled (datapacks and command blocks). | keep off |
| `performance.entity-goal.start-tick-chance.*` | `-1` (vanilla) | Goal start probability. | None at the default. | keep |
| `performance.use-virtual-thread.bukkit-async-scheduler` / `folia-async-scheduler` / `download-pool` | `false` | Virtual threads for the Bukkit async scheduler and similar pools. | Medium if enabled (plugins that use `ThreadLocal` or expect named pool threads). | keep off |
| `fixes.prevent-moving-into-weak-loaded-chunks.enabled` / `.projectiles` | `false` | Stops movement and projectiles at weakly loaded chunk edges. | Medium if enabled (pearls). | keep off |
| `fixes.vanilla-bug-fix.mc-270656` / `mc-301114` / `mc-152094` | `false` | Optional vanilla bug fixes (`fixes/MCBugFix.java`). | Low. | keep off |
| `misc.secure-seed.enabled` | `false` | Changes seed derivation for features. | None for arena, but it does change worldgen. | keep off |
| `misc.region-format.format-name` | `MCA` | Region file format. Linear and other formats are not readable by Purpur. | High if changed (world files no longer portable). | keep `MCA` |
| `misc.disable-world-data-saving.worlds` | `[]` | Stops saving the listed worlds. | n/a | keep (or list a throwaway duel world deliberately) |

### 1c. Knockback and other gameplay settings

| Key (`leaf-global.yml`) | Default | What it changes | Risk for PvP | Arena |
|---|---|---|---|---|
| `gameplay-mechanisms.knockback.snowball-knockback-players` | `false` | When `true`, snowballs knock players back (pre-1.9 behaviour). | Off means vanilla 26.x. | keep `false` (revisit with the RevivalPaper "projectile toggle") |
| `gameplay-mechanisms.knockback.egg-knockback-players` | `false` | The same for eggs. | Same as above. | keep `false` (same as above) |
| `gameplay-mechanisms.knockback.old-blast-protection-explosion-knockback` | `false` | When `true`, Blast Protection reduces explosion knockback the old way. | Off means vanilla. | keep |
| `gameplay-mechanisms.knockback.use-legacy-tracker-ticking` | `false` | Paper PR 13094 / MC-297196. When `true`, `chunkMap.tick()` (the entity tracker, which sends position and velocity) runs **after** entity ticking in `ServerLevel` instead of before it in `ServerChunkCache.tick`. The effect is that knockback and velocity reach clients in the same tick they were applied, not the next tick. Restart required. Sources: `gameplay/Knockback.java:44` and `minecraft-patches/features/0315-Use-legacy-tracker-ticking.patch`. | **[combat]** The default `false` matches Purpur 26.2. Enabling it changes when knockback arrives, possibly by one tick. **(uncertain: how much this changes feel)** | keep `false` at cutover. A/B test it later together with RevivalPaper's SportBukkit velocity fixes. |
| `gameplay-mechanisms.use-spigot-item-merging-mechanism` | `false` | When `true`, Spigot's merge direction is used. | Off means vanilla. | keep |
| `gameplay-mechanisms.use-vanilla-hopper` | `false` | When `true`, Paper's hopper optimisations are replaced with vanilla behaviour. Off means Paper behaviour, the same as Purpur. | None. | keep |
| `gameplay-mechanisms.only-player-pushable` | `false` | When `true`, only players can be pushed. | Off means vanilla. | keep |
| `gameplay-mechanisms.allow-tripwire-dupe` | `false` | Vanilla 26.x behaviour. | None. | keep |
| `gameplay-mechanisms.ice-and-snow-chance` | `48` | The vanilla value. | None. | keep |
| `gameplay-mechanisms.locator-bar-max-distance` | `0.0` | The vanilla value. | None. | keep |
| `gameplay-mechanisms.afk-command.enabled` | `false` | Registers `/afk` (Purpur AFK). | None while off. | keep |
| `gameplay-mechanisms.inventory-overflow-event.enabled` | `false` | Fires `PlayerInventoryOverflowEvent`. | None while off. | keep |
| `gameplay-mechanisms.spawner-settings.enabled` | `false` | Spawner rules. | None while off. | keep |

### 1d. Gale settings (`config/gale-global.yml`, `config/gale-world-defaults.yml`)

Paths are relative to `leaf-server/src/main/java/org/galemc/gale/configuration/`.

| Key | Default | What it changes | Risk for PvP | Arena |
|---|---|---|---|---|
| world: `small-optimizations.reduced-intervals.check-stuck-in-wall` | `10` | Checks living entities for suffocation (in-wall damage) only every 10 ticks instead of every tick (`GaleWorldConfiguration.java:63` and `minecraft-patches/features/0018-Reduce-in-wall-checks.patch`, around `LivingEntity` line 461). Purpur also ships this Pufferfish change, but its interval setting may differ **(uncertain: compare with your `purpur.yml`)**. | Low to medium. Suffocation damage in walls can start up to 0.5 s late. This is relevant if arena maps use glitch or pearl spots or closing borders. | **review**. Set to `1` for exact vanilla timing (the cost is negligible at arena player counts). |
| world: `small-optimizations.max-projectile-chunk-loads.per-tick` | `10` | No projectile may load more than 10 chunks per tick in the world (`GaleWorldConfiguration.java:46` and `minecraft-patches/features/0009-Reduce-projectile-chunk-loading.patch`, an override of `Projectile.setPos`). | Low on small arenas, where pearls stay in loaded chunks. Above the limit a projectile stops moving into unloaded chunks. | keep |
| world: `small-optimizations.max-projectile-chunk-loads.per-projectile.max` | `10` (`reset-movement-after-reach-limit: false`, `remove-from-world-after-reach-limit: false`) | A single projectile may load at most 10 chunks over its lifetime. | Low on arenas. It matters for long-range pearls on large maps. | keep |
| world: `gameplay-mechanics.entity-wake-up-duration-ratio-standard-deviation` | `0.2` | Adds Gaussian variation to Paper entity-activation wake-up durations (`GaleWorldConfiguration.java:100` and `minecraft-patches/features/0023-Variable-entity-wake-up-duration.patch`). | Mobs only. | keep (set `0` for exact Paper parity) |
| world: `gameplay-mechanics.entities-can-random-stroll-into-non-ticking-chunks` | `true` | The vanilla value. | None. | keep |
| world: `gameplay-mechanics.try-respawn-ender-dragon-after-end-crystal-place` | `true` | The vanilla value. | None. | keep |
| world: `gameplay-mechanics.fixes.broadcast-crit-animations-as-the-entity-being-critted` | `false` | When `true`, the crit particle source changes. | Off means vanilla. | keep |
| world: `gameplay-mechanics.fixes.mc-121706` / `mc-238526` | `false` | Optional vanilla fixes (mob strafing look direction, spawner water animals). | None. | keep |
| world: `gameplay-mechanics.hide-flames-on-entities-with-fire-resistance` | `false` | When `true`, hides the fire overlay on entities with Fire Resistance. | Off means vanilla. Turning it on would hide a PvP signal. | keep `false` |
| world: `small-optimizations.save-fireworks` | `true` | The vanilla value (fireworks are saved). | None. | keep |
| world: `small-optimizations.use-optimized-sheep-offspring-color` | `true` | Equivalent result. | None. | keep |
| world: `small-optimizations.reduced-intervals.villager-item-repickup` | `100` | Villager item pickup cooldown. | None. | keep |
| world: `small-optimizations.load-chunks.to-spawn-phantoms` / `to-activate-climbing-entities` | `false` | The phantom spawn check does not load chunks. `to-activate-climbing-entities` appears unused, since no patch references it. | None. | keep |
| global: `small-optimizations.reduced-intervals.update-entity-line-of-sight` | `4` | Caches mob line-of-sight (`Sensing`) results for 4 ticks (`GaleGlobalConfiguration.java:36` and `minecraft-patches/features/0076-Reduce-line-of-sight-updates-and-cache-lookups.patch`). | Mobs only, so they react to line of sight up to 0.2 s late. Not player-vs-player. | keep (set `1` for parity if PvE ever runs on arena) |
| global: `small-optimizations.reduced-intervals.increase-time-statistics` | `1` | The vanilla value. | None. | keep |
| global: `gameplay-mechanics.enable-book-writing` | `true` | The vanilla value. | None. | keep |
| global: `misc.verify-chat-order` | `true` | The vanilla value. | None. | keep |
| global: `misc.keepalive.send-multiple` | `false` | When `true`, sends several keepalives in flight and changes ping measurement. | Off means vanilla. | keep `false` |
| global: `misc.last-tick-time-in-tps-command.*` | `false` | Adds information to the `/tps` output. | None. | keep |
| global: `misc.premium-account-slow-login-timeout` | `-1` | Disabled. | None. | keep |

---

## 2. Unconditional patches that change behaviour

There is no setting for any of these. They are grouped by how much they matter to arena. Unless noted,
paths are relative to `leaf-server/minecraft-patches/features/`.

### Combat, movement and items (test before cutover)

- **`0095-Fix-sprint-glitch.patch` [combat] (high).**
  - In `LivingEntity.setHealth` (hunk at line 1438) for players, `player.updateScaledHealth(false)` is
    replaced by setting `DATA_HEALTH_ID` to `player.getScaledHealth()`.
  - Result: the server **no longer sends `ClientboundUpdateAttributesPacket`** (all syncable attributes,
    including movement speed) every time a player's health changes. On Purpur that resend can reset the
    client's sprint-speed attribute after a hit, the long-standing "sprint glitch".
  - On RevivalPaper, sprint and knockback after a hit can therefore feel different from Purpur, which
    players coming from a Purpur server are used to.
  - Side effect **(uncertain)**: Paper's health-scale cache (`maxHealthCache`) is not refreshed on this path.
    Plugins that call `setHealthScale` should be checked.
  - Interacts directly with RevivalPaper's sprint and knockback work.
- **`0180-Paper-PR-Fix-cancelled-Projectile-Events-still-consu.patch` (high, plugin-visible).**
  - Changes `BowItem`, `CrossbowItem` and `ProjectileWeaponItem`.
  - Cancelling `EntityShootBowEvent` (fired per projectile) or `ProjectileLaunchEvent` for an arrow now
    skips that projectile without consuming the arrow or using durability. A crossbow keeps its charge if
    every projectile was cancelled, and its shoot sound only plays for projectiles that were not cancelled.
  - `EntityShootBowEvent#shouldConsumeItem()` now starts as `false` for Infinity or creative shots. It was
    `true` before.
  - When it is `false`, the arrow's pickup becomes `CREATIVE_ONLY`.
  - Check plugins that replace the bow in hand during `EntityShootBowEvent` or call
    `setConsumeItem(false)`.
- **`0142-Fix-MC-298464.patch` (medium, plugin-visible).**
  - Removing an entity (which includes a player changing world or respawning) now calls
    `combatTracker.resetCombatState()` instead of `recheckStatus()`.
  - Combat entries are cleared on world change and respawn. That affects death messages ("was doomed to
    fall by...") and Paper's `CombatTracker` API.
- **`0149-optimize-attribute.patch` (low).**
  - `AttributeMap` becomes array-backed and modifier maps become insertion-ordered `ArrayMap`s instead of
    `HashMap`s.
  - Modifier iteration order changes. The sums are the same, apart from theoretical floating-point
    rounding in edge cases.
  - `getModifiers()` returns an `ObjectArraySet`.
  - The Purpur `shouldSendAttribute` override is kept (only `Bat` overrides it).
- **`0307-Limit-pushable-entity-queries.patch` and `0277-Optimize-pushable-selector.patch` (low).**
  - Entity pushing and cramming use streaming iteration with an early abort
    (`leaf-server/src/main/java/org/dreeam/leaf/world/EntityPushState.java`).
  - They are written to keep vanilla's cramming RNG and threshold, and also respect Paper's
    `collisions.max-entity-collisions`.
- **`0014-Make-EntityCollisionContext-a-live-representation.patch` (low).** Collision context reads the
  entity's Y position, sneak/descend state and held item at query time instead of a snapshot. This matters
  for scaffolding and powder snow edge cases.
- **`0051-Skip-entity-move-if-movement-is-zero.patch` (low).** `Entity.move` with a zero vector returns
  early. Players get `onGround` corrected from the next movement packet.
- **`0228-optimize-checkInsideBlocks-calls.patch` and `0196-Sakura-Optimise-check-inside-blocks-and-traverse-blo.patch` (low, uncertain).**
  - Inside-block checks (cobwebs, portals, pressure plates, powder snow) are rewritten.
  - Unloaded chunks are treated as air.
  - `0196` was not reviewed line by line.
- **`0285-Skip-item-merge-checks-for-full-stacks.patch`, `0303` (cached max stack size) and
  `0253` (skip the effect tick loop when there are no effects): equivalent behaviour (low).**
- **`0183-Use-BFS-on-getSlopeDistance.patch` (low, uncertain).** Fluid-flow slope search uses BFS. It is
  intended to give the same flow direction. Verify with lava and water traps on arena maps.
- **`0278` (respawn anchor explosion) (low).** The water check is done lazily inside the explosion damage
  calculator.

### Events and plugin API

- **`0024-Do-not-process-chat-commands-before-player-has-joine.patch`.** Chat messages and commands
  that arrive before `PlayerJoinEvent` finishes are dropped. Auto-login or auto-queue commands from a
  client or proxy sent right at join could be lost.
- **`leaf-server/paper-patches/features/0030-Skip-event-if-no-listeners.patch`**, together with
  minecraft patches `0059`, `0230`, `0237`, `0279`, `0299` and `0306`.
  - Events with no listeners return early.
  - The early return also skips Paper's "async event fired synchronously" thread check. A plugin that
    fires such an event from the wrong thread is only caught once something listens to that event.
- **`leaf-server/paper-patches/features/0028-Remove-Timings.patch` and
  `leaf-api/paper-patches/features/0011-Remove-Timings.patch`** (high for old plugins).
  - Most of `co.aikar.timings.*` is deleted (`Timing`, `TimingsManager`, `TimedEventExecutor`, ...),
    along with `co.aikar.util.*`.
  - `Command.timings` and `/timings` are removed, and `useTimings()` is always `false`.
  - A plugin that references `Timings.of(...)` or `Timing` fails with `NoClassDefFoundError` or
    `NoSuchMethodError`. Grep plugin jars for `co/aikar/timings` before switching.
- **`0215-Add-BlockExplosionHitEvent.patch`.** New event (`org.dreeam.leaf.event`). It is additive.
- **`0229-Do-not-create-fire-if-explosion-was-cancelled.patch`.** A cancelled explosion event no longer
  leaves fire behind.
- **`0147-Paper-PR-Fix-some-beacon-event-issues.patch`.** Beacon activate and deactivate events fire in more correct places.
- **`0079-Fix-Pufferfish-and-Purpur-patches.patch`.** Bundles several fixes:
  - `PlayerBookTooLargeEvent` is actually called now.
  - Purpur shift-right-click mending is fixed: spectators are excluded and it uses
    `calculateTotalExperiencePoints`.
  - Time packets are only broadcast when the tick increment is not 1.
  - Max-air-supply save fix.
  - Fail-fast mooshroom and skeleton conversion.
  - Lapis-persistence dupe fix.
- **`0106-Improve-Purpur-AFK-system.patch`.**
  - Adds new Purpur message keys: `settings.messages.afk-title-away`, `afk-command-cooldown` and
    `afk-command-cooldown-msg`.
  - An AFK title is shown only if Purpur `gameplay-mechanics.player.idle-timeout.update-tab-list` is `true`.
  - `/afk` exists only if `gameplay-mechanisms.afk-command.enabled` is set.
- **`0257-Prevent-executing-commands-if-server-stopped.patch`.** Commands are ignored once shutdown has begun.
- **`0312-Move-ClientboundCommandsPacket-creation-off-the-main.patch`.**
  - The command tree packet is built on a worker pool and sent on a later main-thread task.
  - `PlayerCommandSendEvent` and `AsyncPlayerSendCommandsEvent` timing can shift by a tick **(uncertain)**.
- **`leaf-server/paper-patches/features/0032-Don-t-throw-exception-on-missing-ResourceKey-value.patch`.**
  A bad registry key in Paper config logs an error instead of failing startup.
- **`leaf-api/paper-patches/features/0023-Avoid-regex-in-Material-matchMaterial.patch`.**
  `Material.matchMaterial` no longer uses a regex. Unusual input (non-ASCII, odd separators) may resolve
  differently **(uncertain)**.
- **`leaf-api/paper-patches/features/0019-Disable-yaml-duplicate-keys-warning-by-default.patch`.**
  Duplicate YAML keys in plugin configs are no longer warned about.

### World, chunks, saving and network

- **`leaf-server/paper-patches/features/0064-Forge-like-startup-world-migration.patch` (high, operational).**
  - When Paper detects that the world folders need its startup migration (a legacy CraftBukkit world
    layout, or vanilla data older than the file fixer), Paper waits 30 s and continues.
  - Leaf instead **blocks startup reading stdin** until someone types `confirm` or `cancel`.
  - On EOF it loops forever, logging a warning.
  - A server whose stdin is a pipe or a service manager, not an interactive console, would hang at boot.
  - Fix: start with `-Dpaper.disableMigrationDelay=true`. That skips both the prompt and the delay.
    Alternatively, send `confirm` to the console.
- **`0195-Paper-PR-Add-ticket-on-player-join-to-avoid-chunk-lo.patch`.** Adds a 5-second chunk ticket at
  the join position so chunks do not load and unload repeatedly.
- **`0261-Rewrite-entity-despawn-time.patch`.**
  - Entity age now includes ticks missed while unticked.
  - `ticksLived` is saved with the compensated age.
  - Entities past their despawn time are not saved.
  - It only matters if Paper `entities.spawning.despawn-time` is configured, but it does change
    `Entity#getTicksLived()` for entities outside activation range.
- **`0093-Petal-reduce-work-done-by-game-event-system.patch`.** Game events more than 2 sections outside
  the loaded sections are dropped (sculk sensors and wardens).
- **`0058-Spread-out-sending-all-player-info.patch`.** Tab-list latency (ping bars) updates are spread
  over 600 ticks. Each player still updates every 30 s, but at staggered times.
- **`0048-Update-boss-bar-within-tick.patch`.** Raid boss bars update during the tick.
- **`0036-Fix-outdated-server-showing-in-ping-before-server-fu.patch`.** No ping response until the
  server version is known. During startup, proxies see the server as offline instead of outdated.
- **`0012-Optimize-random-calls-in-chunk-ticking.patch`.** The lightning chance is pre-rolled per chunk.
  The odds are the same but the RNG sequence differs.
- **`0078-Pufferfish-Dynamic-Activation-of-Brain.patch` and `0099-Plazma-Add-some-missing-Pufferfish-configurations.patch` (uncertain).**
  - Brain ticking is gated by `behaviorTick++ % activatedPriority`.
  - `activatedPriority` starts at `max-tick-freq` (20) and is only lowered when DAB is on and the mob is
    within a player's activation range.
  - With DAB off, a brain mob might tick its brain only once every 20 ticks. Mobs only; verify if arena
    ever spawns AI mobs.
- **`0137-Fix-MC-17876.patch`.** Health loaded from NBT is not clamped to max health at load. This affects
  plugins that load or save player data with a temporary max-health boost.
- **`0138-Fix-MC-65198.patch`.** Crafting and stonecutter statistics count the items actually taken.
- **`0217-Use-UUID-for-cure-reputation.patch`.** Villager cure reputation is keyed by UUID.
- **`0184-Paper-PR-Throttle-failed-spawn-attempts.patch`.** New Paper world setting
  `failed-attempts-threshold`, disabled by default.
- **`0232` io_uring.** Only with `-DLeaf.enable-io-uring`. The other Leaf system properties
  (`Leaf.enableFMA`, `Leaf.disable-vanilla-profiler`, ...) are also off unless set.

---

## 3. Recommended overrides for arena

`config/leaf-global.yml`. Only these keys differ from Leaf's defaults. The `# pinned` lines repeat
defaults on purpose, so the safety-critical switches are visible and reviewable in the config.

```yaml
async:
  async-mob-spawning:
    enabled: false          # default true; parity with Purpur, arena has no natural mobs (restart)
  async-entity-tracker:
    enabled: false          # pinned: must stay off for combat (velocity/PlayerVelocityEvent threading)
  parallel-world-ticking:
    enabled: false          # pinned
gameplay-mechanisms:
  knockback:
    use-legacy-tracker-ticking: false   # pinned; A/B test later with RevivalPaper velocity fixes (restart)
performance:
  reduce-packets:
    reduce-entity-move-packets: false   # pinned
    reduce-entity-motion-packets: false # pinned: would drop knockback packets
  only-tick-items-in-hand: false        # pinned
misc:
  including-5s-in-get-tps: false        # default true; restores the Paper/Purpur getTPS() shape [1m, 5m, 15m]
```

Optional, for exact vanilla timing (`config/gale-world-defaults.yml`):

```yaml
small-optimizations:
  reduced-intervals:
    check-stuck-in-wall: 1   # default 10; per-tick suffocation checks
```

Launch and operations:

- Add `-Dpaper.disableMigrationDelay=true` to arena's JVM flags, or watch the first boot and type
  `confirm`. Otherwise a world that needs migration hangs startup when stdin isn't a console.
- Before switching, grep the plugin jars for `co/aikar/timings`.
- Test on a staging server side by side with Purpur:
  - sprint and knockback combos (0095)
  - bows and crossbows under cancelled `EntityShootBowEvent` / `ProjectileLaunchEvent` (0180). Patch 0180
    only touches `BowItem`, `CrossbowItem`, `ProjectileWeaponItem` and `Level`, so cancelling pearl and
    wind-charge launches is not affected by it.
  - sneaking at block edges (`optimize-player-movement`)
  - death messages after a respawn or world change (0142)
