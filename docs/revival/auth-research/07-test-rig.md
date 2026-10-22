# 07 · Test rigs for the authoritative movement work

Research for the authoritative RevivalPaper (Minecraft 26.3). This report designs the rigs that prove the server's
physics matches the vanilla 26.3 client bit for bit, and proves the parts that could be proven without starting a
client or a server.

Every rig lives in `~/Developer/tools/auth-rig/` (outside this checkout). Nothing in the RevivalPaper checkout was
changed except this file. Read-only copies of the RevivalPaper build were taken from `leaf-server/build/libs`.

## Sources and notation

| Tag | Path | What |
|---|---|---|
| **V** | `~/Developer/tools/mc-src/src-26.3/net/minecraft/` | decompiled vanilla 26.3 client (common code, integrated server) |
| **P** | `~/Developer/GitHub/RevivalPaper/leaf-server/src/minecraft/java/net/minecraft/` | Minecraft with Paper, Purpur, Gale, Leaf and RevivalPaper patches applied (HEAD `ab26baad`) |
| **M** | `~/Developer/GitHub/RevivalPaper/leaf-server/src/minecraft/java/ca/spottedleaf/moonrise/` | Moonrise (Paper's collision and chunk code) |
| **LT** | `~/Developer/GitHub/RevivalPaper/leaf-server/src/revivalLiveTest/java/gg/hcfrevival/revival/livetest/` | the live-test plugin |
| **R** | `~/Developer/tools/auth-rig/` | the rigs written for this report |

## 0. What matters most

1. **Vanilla physics runs offline, fast, with no server.** A plain `javac`/`java` program against the vanilla 26.3
   server jar bootstraps in about 2.1 to 2.5 s, reads any block's collision shape and runs vanilla's own collision code
   (`BlockCollisions`, `Shapes.collide`, `Entity`'s step-up helpers) on an in-memory world. One `Entity.collide`
   costs about 1.5 µs. This is the oracle for golden tests of the physics port. (Section 1.)
2. **RevivalPaper's server collision is not bit-identical to the client's.** A differential fuzzer (vanilla jar and
   RevivalPaper jar in two class loaders, one JVM) found that Paper's `Entity.collide` (Moonrise `CollisionUtil`)
   returns different movement from vanilla in 23 % of adversarial single moves. In 0.55 % of 10-second walks through
   cluttered terrain, positions diverge, by up to 10 blocks. Every diverging walk had an exactly cardinal yaw.
   Moonrise's patched `VoxelShape.collide` on its own also diverges, in 8 of 500,000 adversarial moves. **The port must
   carry its own copy of vanilla's collision algorithm.** It must not call `Entity.collide`, `CollisionUtil` or the
   patched `VoxelShape.collide`. It must pass this fuzzer as a third implementation. (Section 1.6.)
3. **26.3 replaced GLFW with SDL3.** Mouse look runs in SDL relative mode, which reads motion deltas. The existing
   `keytap -2 x y` moves the cursor to an absolute point, so it cannot turn the camera. `keytap` also presses only one
   key at a time, so it cannot sprint-jump. A new timeline tool, `rigtap`, sends overlapping keys and relative mouse
   deltas. A client-side java agent, `rig-agent.jar`, gives tick-exact scripted inputs and logs the client's own
   position and velocity every tick, without changing any bytecode. Both are compiled. Neither has been run against a
   client. (Section 2.)
4. **The 26.3 client is not runnable yet.** Its json is installed. The jar is missing from `versions/26.3/`, but
   `mc-src/client-26.3.jar` has the same sha1, so it can be copied. Also missing: 53 library and native jars
   (35.7 MB), asset index 34 (0.6 MB) and 141 asset objects (7.6 MB). `fetch-client.py --apply` fetches all of them.
   `mc-qa/launch.py` hard-codes 26.2 and silently skips missing jars, so `R/client/launch.py` adds `--version` and the
   json's own JVM arguments. (Section 2.)
5. **The live-test framework can drive inbound packets.** The current tests call handlers directly on the main thread
   and tick players with `doTick()` from a Bukkit task. That skips `ServerGamePacketListenerImpl.tick()`, so it never
   runs `resetPosition`, keep-alive, teleport resend or `absSnapTo(firstGood…)`. It also handles packets at the wrong
   point of the tick. `R/livetest/FakeClient.java` fixes all three. It puts the connection in the server's connection
   list, answers pings, teleports (26.3's new `AcceptTeleportation(id, x, y, z, yRot, xRot)`), keep-alives and chunk
   batches, and sends each client tick in vanilla order through the server's `PacketProcessor`. It compiles against
   the current RevivalPaper build. (Section 3.)
6. **Trace format RVTR v1:** a per-client-tick record stream captured by a `ChannelDuplexHandler` placed just before
   `packet_handler`. There, inbound and outbound packets interleave in true wire order on one thread. Clientbound state
   is stored as vanilla-encoded packet bytes, so quantized velocities (`LpVec3`) replay exactly. A simulated minute of
   PvP measures **51 to 57 KB raw, about 40 KB zlib**, per player. (Section 4.)

---

## 1. Offline physics harness (proven)

### 1.1 Getting the vanilla 26.3 server jar

`~/Library/Application Support/minecraft/versions/26.3/26.3.json` → `downloads.server`:
`https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar`, sha1
`33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`, 62,294,556 bytes. The download took 2.8 s.

It is a bundler jar (main class `net.minecraft.bundler.Main`):

- `META-INF/versions/26.3/server-26.3.jar`: the real server, 26,652,008 bytes, 18,557 entries, unobfuscated
  (Mojang names, the same as the decompiled tree).
- `META-INF/libraries/**`: 40 library jars.
- `META-INF/classpath-joined`: the run classpath, `;`-separated, relative to `META-INF/`, in the order the bundler
  uses.
- `META-INF/main-class`: `net.minecraft.server.Main`.
- `version.json`: `protocol_version` 777, `world_version` 5023, `java_version` 25, `build_time`
  2026-09-15T11:20:48+00:00.

Script `R/fetch-server.sh`: reads the URL and sha1 from the json, downloads into `R/dl/server-26.3/server.jar`,
checks the sha1, and extracts `META-INF/versions/*`, `META-INF/libraries/*`, `META-INF/classpath-joined` and
`version.json` into `R/dl/server-26.3/x/`. When the jar is already present the whole script takes 0.8 s.

```bash
~/Developer/tools/auth-rig/fetch-server.sh
```

### 1.2 Classpath

Each `classpath-joined` entry, rooted at `R/dl/server-26.3/x/META-INF/`, in this order:

`lz4-java-1.10.1 azure-json-1.4.0 oshi-core-6.9.0 gson-2.14.0 failureaccess-1.0.3 guava-33.6.0-jre msal4j-1.24.1
authlib-10.0.77 brigadier-1.3.11 datafixerupper-10.0.21 jtracy-1.14.38 logging-1.7.12 commons-io-2.20.0
netty-{buffer,codec-base,codec-compression,codec-http,common,handler,resolver,transport,transport-classes-epoll,
transport-classes-kqueue}-4.2.16.Final netty-transport-native-epoll-…-linux-{x86_64,aarch_64}
netty-transport-native-kqueue-…-osx-{x86_64,aarch_64} netty-transport-native-unix-common-4.2.16.Final
fastutil-8.5.18 jna-5.17.0 jna-platform-5.17.0 jopt-simple-5.0.4 commons-lang3-3.20.0 log4j-{api,core,slf4j2-impl}-2.26.0
joml-1.10.9 jspecify-1.0.0 slf4j-api-2.0.17` and finally `versions/26.3/server-26.3.jar`.

`R/harness/build.sh` builds it with:

```bash
V=~/Developer/tools/auth-rig/dl/server-26.3/x/META-INF
CP=$(tr ';' '\n' < "$V/classpath-joined" | sed "s#^#$V/#" | paste -sd: -)
```

### 1.3 Proof program and commands

Files:

- `R/harness/src/rig/RigWorld.java`: a sparse world (`Long2ObjectOpenHashMap<BlockState>`) that implements
  `CollisionGetter`. Anything unset is air.
- `R/harness/src/rig/Proof.java`: bootstraps, prints stair shapes, builds a floor and a stair, runs
  `Shapes.collide` and the full vanilla `Entity.collide` with step-up, then benchmarks.

```bash
~/Developer/tools/auth-rig/harness/build.sh            # javac + java rig.Proof
# which is:
~/Developer/tools/jdk25/Contents/Home/bin/javac -nowarn -d ~/Developer/tools/auth-rig/harness/out -cp "$CP" \
    $(find ~/Developer/tools/auth-rig/harness/src -name '*.java')
~/Developer/tools/jdk25/Contents/Home/bin/java -cp ~/Developer/tools/auth-rig/harness/out:"$CP" rig.Proof
```

Output, verbatim apart from log prefixes:

```
bootstrap: 2484 ms (Bootstrap.bootstrapDuration=2111 ms), version 26.3
state: Block{minecraft:oak_stairs}[facing=north,half=bottom,shape=straight,waterlogged=false]
collision boxes: [AABB[0.0, 0.0, 0.0] -> [1.0, 0.5, 1.0], AABB[0.0, 0.5, 0.0] -> [1.0, 1.0, 0.5]]
state: Block{minecraft:oak_stairs}[facing=east,half=top,shape=straight,waterlogged=false]
collision boxes: [AABB[0.5, 0.0, 0.0] -> [1.0, 1.0, 1.0], AABB[0.0, 0.5, 0.0] -> [0.5, 1.0, 1.0]]
colliders for the swept box: 3
Shapes.collide(Z, box, colliders, -0.3) = -0.050000000000000044 bits=bfa99999999999a0
collide without step-up: (0.0, 0.0, -0.050000000000000044) bits=(0000000000000000, 0000000000000000, bfa99999999999a0)
collide with 0.6 step-up (Entity.collide): (0.0, 0.5, -0.3) bits=(0000000000000000, 3fe0000000000000, bfd3333333333333)
Mth.sin(0.5f)=0.47940964 ... Mth.wrapDegrees(370f)=10.0
200000 Entity.collide calls in 304 ms (1.52 us each)
```

The scene: a stone floor at y=63 and a north-facing oak stair at (0,64,0). A 0.6 × 1.8 player box stands at
(0.5, 64, 1.35) and moves (0, −0.0784000015258789, −0.3). Without step-up it stops at the stair face. With
`maxUpStep` 0.6 it steps 0.5 onto the stair's lower half.

Timing on this Mac (JDK 25.0.4.1 Temurin):

| Step | Wall time |
|---|---|
| `fetch-server.sh` (jar cached) | 0.8 s |
| `javac` of the two files | 0.54 s |
| `java rig.Proof` total | 2.74 s |
| … of which `Bootstrap.bootStrap()` | 2.1–2.5 s (`Bootstrap.bootstrapDuration`) |
| `Entity.collide` with step-up, cold JIT | 1.5 µs per call |

AppCDS (`-XX:+AutoCreateSharedArchive`) did not help: no archive was written and the run got slower. Amortise the
bootstrap by running every golden test in one JVM.

### 1.4 API facts the harness relies on

- **Bootstrap order.** Call `SharedConstants.tryDetectVersion()` (V `SharedConstants.java:196`) first. It reads the
  jar's `version.json`, and the bootstrap and registries need the current version. Then call `Bootstrap.bootStrap()`
  (V `server/Bootstrap.java:45-66`). It throws if the built-in registries are empty, then bootstraps fire, dispensers,
  cauldrons and `BuiltInRegistries`, validates creative tabs and loot params, and finally runs `wrapStreams()`
  (V `:131-139`). After that, `System.out` and `System.err` go through log4j: every `println` gets a `[STDOUT]`
  prefix. Print through `Bootstrap.STDOUT` (V `:40`) for clean output. A JDK 25 warning about `sun.misc.Unsafe` from
  joml is harmless.
- **Minimal world.** `CollisionGetter` (V `world/level/CollisionGetter.java:22-25,83`) plus `BlockGetter` and
  `LevelHeightAccessor` leave these abstract methods: `getWorldBorder()`, `getChunkForCollisions(int,int)`,
  `getEntityCollisions(Entity, AABB)`, `getBlockEntity(BlockPos)`, `getBlockState(BlockPos)`,
  `getFluidState(BlockPos)`, `getHeight()`, `getMinY()`. `WorldBorder` has a public no-argument constructor
  (V `world/level/border/WorldBorder.java:41`). `getBlockCollisionsFromContext(ctx, box)` (V `CollisionGetter.java:105-107`)
  returns vanilla's `BlockCollisions` iterator (V `world/level/BlockCollisions.java:42-63` constructor, `:80-111` `computeNext`). That is the same
  collider walk the client does: cursor bounds `floor(min − 1e-7) − 1 … floor(max + 1e-7) + 1`, the "large shape" and
  moving-piston face rules, and `Shapes.joinIsNotEmpty` against the box.
- **Player collision context.** `CollisionContext.empty()` is wrong for players: its `isAbove` returns the default
  value (V `EntityCollisionContext.java:102-104`), which changes scaffolding, powder snow and fluids. The 6-argument
  `EntityCollisionContext` constructor (`descending, placement, entityBottom, heldItem, alwaysCollideWithFluid, entity`)
  is `protected` (V `:23-37`), so the harness subclasses it (`Proof.PlayerContext`). Blocks that need a real entity
  (`PowderSnowBlock.java:117`, `LiquidBlock.java:80`) see `entity == null`. A port that handles powder snow or
  striders needs its own context for those cases.
- **Private helpers.** `Entity.collideWithShapes(Vec3, AABB, List<VoxelShape>)` (V `Entity.java:1289-1307`) and
  `Entity.collectCandidateStepUpHeights(AABB, List, float, float)` (V `:1213-1238`) are `private static`. On the
  classpath (unnamed module) `setAccessible(true)` works. The harness copies the orchestration of `Entity.collide`
  (V `:1182-1211`) and calls those two by reflection. This is vanilla's own code, apart from leaving out entity
  colliders and the world border.
- **What the harness cannot do yet: a `Level`.** `Level`'s constructor (V `world/level/Level.java:134-155`) needs a
  `RegistryAccess`, a `Holder<DimensionType>` and `DamageSources(registryAccess)`, and `Level` has many abstract
  methods (`getEntities`, `tickRateManager`, `getScoreboard`, `recipeAccess`, `clockManager`,
  `environmentAttributes`, …). So `LivingEntity.travel` and `Entity.move` cannot run offline as-is. The registries can
  be loaded offline from the jar's data pack: Paper's tests do it with `RegistryLayer.createRegistryAccess()`,
  `RegistryDataLoader.load(...)` and `ReloadableServerResources.loadResources(...)`
  (`paper-server/src/test/java/org/bukkit/support/RegistryHelper.java:68-102`). A stub `Level` is therefore possible,
  but it was not attempted. **Recommendation:** write the port against a narrow world interface (block state, fluid
  state, colliders, border, entity colliders), so the same code runs on `RigWorld` offline and on `ServerLevel` live.
  Take ground truth from real clients (Section 2.4), not from a stubbed `Level`.

### 1.5 Pitfall: code that must run on both the vanilla jar and the RevivalPaper jar

- `BlockState.is(Block)`: in vanilla it comes from a generic interface, so its erased descriptor is `is(Object)`.
  Paper declares `public final boolean is(Block)` (P `world/level/block/state/BlockBehaviour.java:736`). Code compiled
  against Paper and run on vanilla throws `NoSuchMethodError`. Use `state.getBlock() == Blocks.X`. Expect this in any
  shared code that touches `TypedInstance`-style helpers.
- Paper's `BlockGetter` adds the abstract methods `getBlockStateIfLoaded` and `getFluidIfLoaded`
  (P `world/level/BlockGetter.java:35,42`). A world class meant for both jars must define them. Leave off `@Override`
  so it also compiles against vanilla.

### 1.6 Differential collision fuzzer: vanilla client collide vs RevivalPaper server collide (proven)

`R/diff/`. `CollideDiff` (host, JDK-only classpath) creates two `URLClassLoader`s whose parent is the platform
loader:

- **vanilla:** `R/diff/out` + the vanilla server jar + its 40 libraries.
- **patched:** `R/diff/out` + `leaf-server.jar` (copied from `leaf-server/build/libs/leaf-server-26.3.local-SNAPSHOT.jar`)
  + the 147 libraries from the RevivalPaper bundler jar.

Each loader loads its own `rig.diff.Probe` and bootstraps its own Minecraft. Paper's patched `Bootstrap.bootStrap()`
(P `server/Bootstrap.java`, which calls `LaunchEntryPointHandler.enterBootstrappers()`) works with no server and no
plugins.

Both sides then run the same seeded scenes:

- `vanilla(...)`: vanilla `Entity.collide`, as in 1.4.
- `PaperPath.paper(...)`: a copy of Paper's `Entity.collide` (P `world/entity/Entity.java:1802-1880`). It calls
  `CollisionUtil.performCollisions` (M `patches/collisions/CollisionUtil.java:1811-1855`) and Paper's
  `Entity.calculateStepHeights` (P `Entity.java:501-545`). Colliders are split into single-box AABBs
  (`CollisionVoxelShape.moonrise$getSingleAABBRepresentation()`) and voxel shapes, the way
  `getCollisionsForBlocksOrWorldBorder` does (M `:1889-2087`).

Paper's real gathering needs a `Level`, so both sides gather colliders with vanilla `BlockCollisions` over the same
boxes Paper queries (`cutDownwards`/`cutUpwards` for vertical-only moves, otherwise `expandTowards`). Results are
compared as raw `double` bits.

The fuzz set is every block state whose collision shape is not empty: 30,112 states that both sides agree on. The 14
`scaffolding[bottom=true]` states differ because Gale's `EntityCollisionContext.isAbove` reads `entity.getY()` live
and ignores `entityBottom` (P `world/phys/shapes/EntityCollisionContext.java:67-69`; see Risks).

```bash
~/Developer/tools/auth-rig/diff/refresh-leaf.sh        # copy the latest RevivalPaper build (read-only)
~/Developer/tools/auth-rig/diff/run.sh 500000 5         # scenes, seed; about 31 s wall
RIG_JAVA_OPTS=-Drig.realistic=true ~/Developer/tools/auth-rig/diff/run.sh 100000 7
```

Results (seed 5, 500,000 adversarial single moves; walks = scenes/50 = 10,000 runs of 200 ticks):

| Comparison | Differ | Of which ≥ 1e-6 | Largest |
|---|---|---|---|
| vanilla collide vs Paper/Moonrise collide, single moves | 116,906 / 500,000 (23.4 %) | 1,298 | 1.196 |
| vanilla collide vs vanilla *algorithm* on patched `VoxelShape`/`BlockCollisions` | 8 / 500,000 | 4 | 0.269 |
| 200-tick walks fed back through each side's own results | 55 / 10,000 (0.55 %) | – | 10.17 blocks |
| the same single moves with the "realistic" profile (no hand-picked epsilons) | 0 / 100,000 | 0 | 0 |

The "adversarial" profile chooses movement components from {0, ±1e-9…3e-7, ±0.003…0.1, −0.0784000015258789,
0.41999998688697815, ±0.7} and positions at {k, k ± 1e-7, k + 0.5, k + 0.3 ± 1e-8, random}. The walks do not use
those picks: each runs crude ground physics (friction 0.546, sprint acceleration, gravity 0.08 × 0.98, random jumps)
through random walls, stairs and slabs, with a float yaw that is exactly cardinal in a quarter of the runs.

What causes it:

- **Tiny components.** Vanilla `VoxelShape.collideX` returns `0.0` when `|distance| < 1e-7` and the shape is not
  empty (V `world/phys/shapes/VoxelShape.java:253-258`). `Shapes.collide` checks the same thing before every shape
  (V `Shapes.java:223-233`). Moonrise's AABB path has no such zeroing. `CollisionUtil.collideX/Y/Z(AABB…)` (M `:105-167`)
  assumes `|move| ≥ 1e-7`, and `performCollisions` never checks. Example from the fuzzer: move x `5.375e-8` next to
  blocks. Vanilla gives `0.0`, Paper keeps `5.375e-8`.
- **Cardinal yaw.** In play this case is common. At yaw 90°, `Mth.cos` reads `SIN[32768]` = `(float)sin(π)` =
  1.2246469e-16, so the forward input adds about 1e-17 to z every tick. Vanilla zeroes it whenever any collider is in
  the swept box, and the floor always is. Paper keeps it. **All 55 diverging walks had an exactly cardinal yaw**
  (2.2 % of cardinal walks). They first differ at the 1e-17 level, then compound.
- **Step-up flips.** In 2 of 11 diverging walks (seed 3) the difference grew to a 0.5-block step-up. The client
  stepped onto a slab and the server did not. The cause: vanilla zeroes a tiny x, which sets `xCollision` and makes
  step-up eligible. The stepped candidate then gains a 1e-16 horizontal distance and wins
  `horizontalDistanceSqr() >` (V `Entity.java:1201-1205`). Moonrise never zeroed x, so it never stepped.
- **Moonrise `VoxelShape.collide` alone** (P `VoxelShape.java:878-898`) diverges only when the box sits exactly 1e-7
  from a shape coordinate (every diverging scene had `pos.y = 1e-7`). This is a strict versus non-strict epsilon
  comparison.

What it means for the design:

- The port must implement vanilla's collision path itself: `BlockCollisions` semantics, `Shapes.collide` with its
  1e-7 early exit, `VoxelShape.collideX` (`findIndex(min + 1e-7)`, `findIndex(max − 1e-7)`, `isFullWide` scan,
  `newDistance >= -1e-7`), `Direction.axisStepOrder` (V `core/Direction.java:390-392`: YZX when `|x| < |z|`,
  otherwise YXZ) and the step-up candidates. It must work on shape data (coordinates and discrete voxels) that the
  patched classes still expose.
- Add the port to `Probe` as `mode 2` and require **0 differences** against vanilla over at least 10⁶ adversarial
  moves and 10⁴ walks on every Minecraft or Leaf update. The rig already loads both jars, so this is a few lines.
- Check the port's collider gathering separately. Paper's gathering (`getCollisionsForBlocksOrWorldBorder`, with
  `COLLISION_EPSILON` AABB intersection tests) was replaced by vanilla's in this rig, so it is untested here.

---

## 2. Real vanilla 26.3 client automation on this Mac (planned, not run)

### 2.1 What is installed and what is missing

- `versions/26.3/26.3.json` is present (44,992 bytes, protocol 777 client). `versions/26.3/26.3.jar` is **missing**.
  `~/Developer/tools/mc-src/client-26.3.jar` has sha1 `e877b6a07acd633fb3bb475002175cec036e7b87`, which matches
  `downloads.client.sha1` (41,483,720 bytes), so copy it.
- Libraries: 26.3 lists 114 entries and 28 are present. The 53 missing entries that apply to macOS are listed by
  `fetch-client.py`: authlib 10.0.77, brigadier 1.3.11, jtracy 1.14.38 (+ macOS natives), netty 4.2.16 (12 jars),
  joml 1.10.9, and LWJGL 3.4.3 freetype, jemalloc, openal, opengl, **sdl**, shaderc, spvc, stb, vma, **vulkan** and
  core, each with natives-macos and natives-macos-arm64. Together with the asset index they come to 35.7 MB.
- Assets: index `34` (`assetIndex.url` https://piston-meta.mojang.com/v1/packages/e787e8afd149a4cefa89d341b91f5aed8f5d93a0/34.json,
  597,035 bytes) is missing. Of its 5,147 objects, 141 are missing, 7.6 MB in all: `sounds.json`,
  `poplar_leaves/*.ogg`, `programmer_art.zip`, `high_contrast.zip`, ….
- JVM arguments changed from 26.2. The 26.3 json has `-XstartOnFirstThread` (osx), `-XX:StackShadowPages=32`,
  `--enable-native-access=ALL-UNNAMED`, `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`,
  `-Djava.library.path=${natives_directory}/java`, `-Djna.tmpdir=…/jna`,
  `-Dorg.lwjgl.system.SharedLibraryExtractPath=…/lwjgl` and `-Dio.netty.native.workdir=…/netty`. 26.2 had
  `--sun-misc-unsafe-memory-access=allow` and lacked the add-exports and shadow pages.
- The client's arguments (V `client/main/Main.java:74-111`) still include `--quickPlayMultiplayer` and
  `allowsUnrecognizedOptions()`, so the old `--userType` is ignored. `--offlineDeveloperMode` is new (`:100`). It skips
  Mojang services (`Minecraft.java:425-430,724,849`), which is what an offline test client wants.

```bash
python3 -I ~/Developer/tools/auth-rig/client/fetch-client.py           # dry run: lists the 54 files, 77.2 MB incl. the jar
python3 -I ~/Developer/tools/auth-rig/client/fetch-client.py --apply   # copies the jar locally, downloads the rest, sha1-checked
```

`--apply` writes into the launcher folder, the same files the official launcher would. It was **not** run.

### 2.2 Launching

`~/Developer/tools/mc-qa/launch.py` cannot launch 26.3 as written:

- It reads `versions/26.2/26.2.json` (`launch.py:72`), adds `versions/26.2/26.2.jar` (`:91`) and passes
  `--version 26.2` (`:97`).
- It passes only `-XstartOnFirstThread -Xmx3G -Djava.library.path=… -Dorg.lwjgl.util.Debug=false` (`:95`).
- It **silently skips** missing libraries (`if os.path.exists(p)`, `:89`), so a 26.3 launch would die with
  `NoClassDefFoundError` instead of naming the missing jar.

The minimal change to `mc-qa/launch.py`:

1. Add `ap.add_argument('--version', default='26.2')`.
2. Replace the three hard-coded `26.2` with `a.version`.
3. Build the JVM arguments from `v['arguments']['jvm']` (same rule filter, substituting `${natives_directory}`,
   `${launcher_name}`, `${launcher_version}` and `${classpath}`).
4. Fail on a missing library.

`R/client/launch.py` is that change, ready to use. It also writes rig-friendly `options.txt` entries on every launch:

- `ctrlClickEmulatesRightClick:false`. On macOS, Control+click is a right click unless this is off
  (`Minecraft.java:716-718`, `Options.java:1625`).
- `key_key.sprint:key.keyboard.r` and `key_key.sneak:key.keyboard.c`. This keeps synthetic input off modifier keys and
  avoids the Control+click trap.
- `autoJump:false`, `toggleSprint:false`, `toggleCrouch:false`, `pauseOnLostFocus:false`, `mouseSensitivity:0.5`,
  `maxFps:120`, `enableVsync:false`, `renderDistance:6`, `simulationDistance:5`, `onboardAccessibility:false`, and
  sound off.

```bash
python3 -I ~/Developer/tools/auth-rig/client/launch.py 127.0.0.1:25565 --version 26.3 --name AuthRig --print   # shows the command
python3 -I ~/Developer/tools/auth-rig/client/launch.py 127.0.0.1:25565 --version 26.3 --name AuthRig > client.pid
python3 -I ~/Developer/tools/auth-rig/client/launch.py 127.0.0.1:25565 --name AuthRig \
    --agent ~/Developer/tools/auth-rig/agent/rig-agent.jar=$HOME/Developer/tools/auth-rig/client/scenarios/sprint-jump.agent,/tmp/rig-client-trace.tsv
```

`--print` was run and printed the full command, with the 26.3 JVM arguments above, the 114-entry classpath and
`--offlineDeveloperMode --quickPlayMultiplayer`. The game folder is `R/client/game-<name>/` and the log is
`client.log` there. The server must be `online-mode=false`, because the access token is `0`.

### 2.3 What 26.3 changed for input: SDL3

26.3 dropped GLFW. No class references `org.lwjgl.glfw`. `com/mojang/blaze3d/platform/Window` calls `SDLVideo.*`
(checked with javap on the client jar; that package is missing from the decompiled tree). Input:

- **Keys:** `InputConstants.isKeyDown` reads `SDL_GetKeyboardState`. `KeyboardInput.tick()` builds
  `Input(forward, backward, left, right, jump, shift, sprint)` from the `KeyMapping`s
  (V `client/player/KeyboardInput.java:23-36`). A synthetic `CGEvent` keyboard event posted at `kCGHIDEventTap` reaches
  SDL's Cocoa event pump like a real key.
- **Mouse look:** `MouseHandler.grabMouse()` (V `client/MouseHandler.java:416-431`) calls `InputConstants.grabMouse`,
  which calls `SDL_SetWindowRelativeMouseMode` (javap). While grabbed, `onMove` adds the event's **relative** `xrel`
  and `yrel` (`:233-253`) and ignores absolute position. `turnPlayer` (`:323-348`) computes
  `ss = sens × 0.6 + 0.2`, `sens' = ss³ × 8`, then `player.turn(dx × sens', dy × sens')`. `Entity.turn` multiplies by
  0.15. At sensitivity 0.5, one count of `xrel` is about 0.15000001° of yaw. The 26.2 option `rawMouseInput` is gone
  (0 matches in V `Options.java`).
- **Focus:** input is used only while `window.isFocused()` (`Minecraft.java:2776-2778`). The client window must be
  frontmost.

### 2.4 Driving it

**`mc-qa/keytap` (existing), as written:**

- `keytap <code> <hold ms> <gap ms> …` presses keys **one after another**. It never holds two at once.
- Code `-1` is a left click at the cursor.
- Code `-2 x y` warps the cursor to an absolute screen point and posts a `mouseMoved` there.
- `-within x y w h` refuses to send anything when the cursor is outside the window.
- It can do single taps, double taps and clicks. It **cannot** sprint-jump, strafe while sprinting, or hold W while
  tapping space. Its `-2` cannot turn the camera, because SDL relative mode reads deltas and `CGWarpMouseCursorPosition`
  produces none.

**`R/client/rigtap` (new, compiled, input never posted):** plays a timeline script with absolute millisecond times.

- Lines: `t down <code>`, `t up <code>`, `t tap <code> <hold>`, `t look <dx> <dy> [<over ms>]`, `t click <hold>`,
  `t rclick <hold>`, `t end`.
- `look` posts `mouseMoved` (or a drag while a button is held) with `kCGMouseEventDeltaX/Y` set explicitly, in about
  4 ms steps that add up exactly to the total.
- Keys can overlap. Everything held is released at the end and on SIGINT or SIGTERM. `-within` works as in keytap.
- Timing: it sleeps until 1 ms before each event, then spins.
- Only its guard paths were exercised (bad line → exit 2; cursor outside the rect → exit 2, nothing sent).

```bash
cd ~/Developer/tools/auth-rig/client && swiftc -O -swift-version 5 -o rigtap rigtap.swift     # 1.7 s
./rigtap -within <x y w h of the client window> scenarios/sprint-jump.rigtap
```

macOS virtual keycodes: W 13, A 0, S 1, D 2, Space 49, R 15 (rig sprint), C 8 (rig sneak), E 14, Esc 53, F3 99,
LShift 56, LCtrl 59. Example scenarios are in `R/client/scenarios/`: `sprint-jump`, `strafe`, `sneak-edge`,
`ladder-water` (`.rigtap`) and `sprint-jump.agent`.

Before relying on mouse look, run this check once: `0 look 600 0 0` should turn the yaw by about 90°. Read the yaw
from the server (the move packets) rather than a screenshot. If the yaw does not move, SDL3 is not taking the posted
deltas. Fall back to the agent (below) for turning.

Other requirements: the process that posts events needs the Accessibility permission. It is the same "responsible
app" that already runs `keytap`. Bring the client to the front first:
`osascript -e 'tell application "System Events" to set frontmost of (first process whose unix id is <pid>) to true'`.
`winlist` finds the window: grep `Minecraft 26.3` in place of `Minecraft 26.2`, as in `cycle.sh`.

**`R/agent/rig-agent.jar` (new, compiled, never run): tick-exact input and client ground truth, with no bytecode
changes.**

- `LocalPlayer.input` is a public field (V `client/player/LocalPlayer.java:137`). Vanilla itself replaces it on login
  and respawn (`ClientPacketListener.java:542,1337`). `aiStep` calls `this.input.tick()` once per client tick, before
  any movement (V `LocalPlayer.java:782,802`).
- The agent's premain starts a daemon thread. Whenever `mc.player.input` is a `KeyboardInput`, it swaps in a
  `ScriptedInput` on the client thread through `mc.execute`.
- `ScriptedInput.tick()` does three things:
  1. Logs the player's state at the start of `aiStep`: position, `getDeltaMovement`, yaw, pitch, `onGround`,
     `horizontalCollision`, `isSprinting`, `isShiftKeyDown`, keys. This is the end of the previous tick's move, but
     after this tick's `baseTick`, so fluid pushing for this tick is already in the velocity.
  2. Applies the script line for that tick: keys, plus `yaw`, `pitch` or `dyaw` per tick.
  3. Sets `moveVector` exactly as `KeyboardInput.tick()` does: `calculateImpulse`, then `Vec2(left, forward).normalized()`.
- After the last script line it hands the keyboard back by reinstalling a `KeyboardInput`.
- Script format: `<fromTick> <toTick> <keys> [yaw d] [pitch d] [dyaw d]`, where keys are `W A S D J(jump) C(sneak)
  R(sprint)` or `-`. Output is a TSV, `rig-client-trace.tsv` by default.
- Build: `~/Developer/tools/auth-rig/agent/build.sh` (javac against `client-26.3.jar` + the server bundle's libraries;
  1 s).
- The client still runs vanilla physics. Only the input source and a logger change. It gives **two** truths for one
  run: what the client computed (agent TSV) and what it sent (server trace, Section 4).
- Use OS-level `rigtap` for "pure vanilla client" acceptance runs, and the agent for repeatable golden scenarios.

### 2.5 Scenario procedure (movement golden tests)

1. Start a local RevivalPaper with the trace recorder on (Section 4), offline mode, and an arena world: flat floor,
   stair and slab runs, a ladder column, a water pool, block edges for sneaking, and soul sand, ice, honey and cobweb
   pads.
2. `launch.py --version 26.3 --agent …=<scenario>.agent` (or with no agent, then focus the window and run
   `rigtap <scenario>.rigtap`). Teleport the player to the scenario's start with a console command before the script
   runs.
3. Stop the client, then the server. Keep `<trace>.rvtr`, `rig-client-trace.tsv` and the world snapshot.
4. Offline: replay the trace through the port in the harness (Section 4.5). Assert bit equality with every reported
   position and, when the agent was used, with every client-side velocity.

---

## 3. The RevivalPaper live-test framework

### 3.1 How it works today

- **Runner** `scripts/revival/live-tests.py`. For each of "sync tracker" and "async tracker" it:
  - makes a temporary server folder whose `cache`, `libraries` and `versions` are symlinked to
    `leaf-server/build/revival-live/`;
  - copies `revival-live-tests.jar` into `plugins/`;
  - writes `config/leaf-global.yml` with `async-entity-tracker.enabled` set, `eula.txt` and `server.properties`
    (offline mode, a flat bedrock + 3 stone world, view and simulation distance 2, `pvp=true`, a free port);
  - runs `java -Drevival.livetest.nonce=<hex> -Dpaper.disableMigrationDelay=true … -jar leaf-paperclip-*.jar --nogui`
    with a 600 s timeout;
  - passes only if `REVIVAL_LIVE_DONE <nonce>` appears and no `REVIVAL_LIVE_FAIL <nonce>` does.
- **Plugin** `LT/RevivalLiveTests.java`:
  - `onEnable` (`:84`) does nothing unless the nonce property is set and the server is in offline mode.
  - `start()` (`:126-170`) creates two fake players and queues scenarios with `after(delay, name, action)`.
  - `next()` (`:514-530`) runs each scenario in a `runTaskLater` and logs `REVIVAL_LIVE_PASS` per `check`.
- **Fake players** (`fakePlayer`, `LT/RevivalLiveTests.java:450-456`) are built with
  `new GameProfile(UUID.nameUUIDFromBytes("revival-live-"+name), name)`,
  `new ServerPlayer(server, level, profile, ClientInformation.createDefault())`,
  `server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false))`
  (P `server/players/PlayerList.java:162`) and `player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket())`.
- **Recording connection** (`LT/RecordingConnection.java:15-49`) extends `Connection(PacketFlow.SERVERBOUND)`. It sets
  `channel = new EmbeddedChannel()` (so `isConnecting()`, which is `channel == null` at P `network/Connection.java:869`,
  is false) and overrides:
  - `send(Packet, ChannelFutureListener, boolean)`: appends to a `CopyOnWriteArrayList`. Leaf's async tracker may send
    from other threads.
  - `isConnected() = true`.
  - no-ops for `setupInboundProtocol`, `setupOutboundProtocol`, `setReadOnly` and `handleDisconnection`.
- **Outbound flow:** every `connection.send(...)` (P `Connection.java:441-449`) lands in `sent` as a packet **object**,
  never encoded. Assertions read objects, for example `ClientboundSetEntityMotionPacket.movement()` (`LT:485-490`).
  Packets that bypass `send`, such as `sendPacketAndScheduleNettyTask` (P `Connection.java:525`, used for compression
  during login), go into the `EmbeddedChannel` and are not seen.
- **Inbound flow:** scenarios call handlers directly on the main thread, for example
  `attacker.connection.handleAttack(new ServerboundAttackPacket(id))` (`LT:476-478`), `handlePlayerCommand(...)`
  (`:480-483`) and `handleUseItem(...)` (`:346`). `PacketUtils.ensureRunningOnSameThread` (P
  `network/protocol/PacketUtils.java:21-26`) sees the server thread and returns, so the handler runs synchronously.
- **Ticking:** the connection is not in `ServerConnectionListener.connections` (P `server/network/ServerConnectionListener.java:52`),
  so `Connection.tick()` and therefore `ServerGamePacketListenerImpl.tick()` (P `:377-404`) and `tickPlayer()`
  (`:406-…`) never run. Instead a Bukkit `runTaskTimer` calls `attacker.doTick(); victim.doTick();` every tick
  (`LT:136-139`). Bukkit tasks run at `mainThreadHeartbeat()` at the **start** of `tickChildren`
  (P `server/MinecraftServer.java:1865`), before the levels tick. Real players tick in `tickConnection()` **after** the
  levels (`:1961`).

### 3.2 Can it drive input, move, tick-end and pong to test the authoritative system? Yes, with four changes

1. **Tick like a real connection.** Add the connection to `server.getConnection().getConnections()` (a public
   synchronized list, P `ServerConnectionListener.java:329-331`) and stop calling `doTick()` from a task. Then
   `ServerConnectionListener.tick()` (`:245-293`) runs `Connection.tick()` (P `Connection.java:653-693`) →
   `ServerGamePacketListenerImpl.tick()`. That covers `resetPosition`, `xo/yo/zo`, `doTick`,
   `absSnapTo(firstGoodX…)`, the floating kick, keep-alive and teleport resend, at the real point of the tick.
   Remove the connection again before removing the player.
2. **Answer what a client must answer.** Otherwise the server stalls or kicks:
   - `ClientboundPingPacket(id)` → `ServerboundPongPacket(id)` (client: `ClientCommonPacketListenerImpl.java:154-157`).
   - `ClientboundKeepAlivePacket(id)` → `ServerboundKeepAlivePacket(id)`. `keepConnectionAlive` runs every tick once
     the connection ticks.
   - `ClientboundPlayerPositionPacket(id, change, relatives)` → in 26.3 the client sends **one**
     `ServerboundAcceptTeleportationPacket(id, x, y, z, yRot, xRot)` carrying its post-teleport position. The 26.2
     extra `PosRot` move packet is gone (V `client/multiplayer/ClientPacketListener.java:794-806`; the packet is now
     a record, V `network/protocol/game/ServerboundAcceptTeleportationPacket.java:9`). Until the ack arrives,
     `handleMovePlayer` ignores moves (`awaitingPositionFromClient`; P `ServerGamePacketListenerImpl.java:809-831`).
     Compute the absolute position with `PositionMoveRotation.calculateAbsolute(current, change, relatives)`
     (P `world/entity/PositionMoveRotation.java:38`).
   - `ClientboundChunkBatchFinishedPacket` → `ServerboundChunkBatchReceivedPacket(desiredChunksPerTick)`.
   - Unwrap `BundlePacket.subPackets()` (P `network/protocol/BundlePacket.java:12`). The tracker sends bundles.
3. **Deliver packets the way the network does.** Real inbound packets go netty → `Connection.channelRead0`
   (P `Connection.java:228-297`) → `packet.handle(listener)` on the netty thread → `ensureRunningOnSameThread` →
   `server.packetProcessor().scheduleIfPossible(listener, packet)` (P `network/PacketProcessor.java:42-58`) → run on
   the main thread, in arrival order:
   - between ticks, by `pollTask()` → `executeSinglePacket()` (P `MinecraftServer.java:1582-1586`, Paper "improve
     tick loop");
   - at tick start, all of them, by `runAllTasksAtTickStart()` (`:1225-1251`), before `tickServer`.

   A fake client should call `scheduleIfPossible` from its own thread to reproduce that. `handlePong` is a no-op that
   runs on the netty thread (P `ServerCommonPacketListenerImpl.java:157-158`, no `ensureRunningOnSameThread`), so the
   authoritative implementation must queue pongs onto the same `PacketProcessor` queue to keep them ordered with
   movement. The fake client does the same.
4. **Send a client tick in vanilla order.** `Minecraft.tick()` (V `client/Minecraft.java:1795-1886`) sends:
   - actions from `handleKeybinds` (attack, use, swing, slot);
   - then `LocalPlayer.sendChanges()` (V `LocalPlayer.java:264-286`): `ServerboundPlayerInputPacket` if the `Input`
     changed; then in `sendPosition()` (`:294-332`) a sprint `ServerboundPlayerCommandPacket` if sprinting changed;
     then `PosRot`, `Pos`, `Rot` or `StatusOnly` (move when the distance squared > 4.0E-8 or after 20 ticks; rotate
     when yaw or pitch changed; status when `onGround` or `horizontalCollision` changed);
   - then `ServerboundClientTickEndPacket.INSTANCE` (`:1883-1886`).

   Clientbound packets, pings included, are handled at the start of the frame, before that frame's ticks
   (`runTick`: `packetProcessor.processQueuedPackets()` then `runAllTasks()`, then up to 10 `tick()`s,
   V `Minecraft.java:1184-1222`). So pongs and teleport acks go first in a tick's batch.

`R/livetest/FakeClient.java` implements 1 to 4. It compiles with `javac` against the current build
(`leaf-server-26.3.local-SNAPSHOT.jar` + bundler libraries), which confirms every API it touches exists with these
signatures.

- `new FakeClient(server, level, name)` places the player, joins the connection list and sends the player-loaded
  packet. `clientState` is set before `placeNewPlayer`, because the join teleport arrives during that call.
- `clientTick(actions, input, sprinting, position, yRot, xRot, onGround, horizontalCollision)` builds the batch
  exactly as above, using `LocalPlayer.sendPosition`'s rules.
- `deliver(packet)` handles the packet now when called on the main thread, and otherwise `scheduleIfPossible`s it.
- `sent` holds `(serverTick, packet)` records.
- `disconnect()`.

The intended package is `gg.hcfrevival.revival.livetest`, so it can sit next to `RecordingConnection`.

How to use it in tests:

- **Plumbing and transaction tests** (no physics accuracy needed): feed the client positions computed by the port.
  Assert that every state change (velocity, attributes, abilities, effects, teleports, block changes) is paired with a
  ping, and that the server's sim applies it at the client tick where the pong arrived. Also assert: no corrections for
  an honest script; a correction carrying position and velocity after an injected fake (speed, fly, a skipped tick-end,
  timer at 22 tps from a client thread at 45 ms); and that `ServerboundClientTickEndPacket` counting matches the
  client thread's rate.
- **1.7 combat on authoritative velocity**: re-run the existing knockback scenarios with the victim driven by
  `clientTick`, so the motion shadow and sprint ledger see real move and input packets.
- **Live golden replays**: replay a recorded trace's serverbound frames with their recorded inter-arrival times. The
  live server picks new ping ids and its own timing, so replays are only approximately equal live. Exact golden checks
  belong offline (4.5).
- **Limit:** the fake client has no vanilla client physics. Positions it reports come from a trace or from the port.
  Real-client truth comes only from Section 2.

---

## 4. Trace format: RVTR v1

### 4.1 Where to capture

Add one `ChannelDuplexHandler` named `revival_trace`, with `pipeline.addBefore("packet_handler", ...)`, when the
connection enters PLAY. Handler names come from V `network/HandlerNames.java`, and the pipeline layout from P
`Connection.java:781-799`: inbound `splitter → decoder → bundler → … → hackfix → packet_handler`, outbound the
reverse. At that position:

- inbound sees decoded `Packet` objects in arrival order, after the bundler;
- outbound sees each `Packet` Connection writes, before the unbundler and encoder, in wire order. `sendPacket`
  always writes on the channel's event loop (P `Connection.java:535-…`).

Both run on the connection's single event-loop thread, so reads and writes **interleave in a total order that
matches the wire**. This holds even with Leaf's async tracker sending from other threads. Stamp each event with
`System.nanoTime()`. Encoding happens there too, using the game codecs:
`GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess)).codec()`
(V `network/protocol/game/GameProtocols.java:141`, `SimpleUnboundProtocol.bind`). The serverbound side is
`SERVERBOUND_TEMPLATE.bind(decorator, context)` (`:61`, `UnboundProtocol.bind`).

The server tick boundaries come from the main thread (`MinecraftServer.currentTick`, P `MinecraftServer.java:316`).
Post them to the recorder through a lock-free queue, and the recorder merges them in by timestamp. Per-tick results
from the server sim come from the main thread the same way.

### 4.2 File layout

One file per connection: `traces/<uuid>/<startEpochMs>.rvtr`, zstd- or zlib-framed in 64 KB blocks so a crash loses
at most one block.

**Header** (once):

| Field | Encoding |
|---|---|
| magic `RVTR`, version `1` | 4 + 1 bytes |
| protocol 777, data version 5023, server build string | varint, varint, UTF |
| player UUID, entity id, name | 16 bytes, varint, UTF |
| start server tick, start nanoTime, start epoch ms | varlong ×3 |
| world snapshot: sha256, dimension key, region box (min/max x, y, z) | 32 bytes, UTF, 6 zigzag varints |
| initial self state | position (3 raw doubles), velocity (3 raw doubles), yRot and xRot (2 floats), onGround, horizontalCollision, sprinting, shift, fallFlying, swimming, pose, abilities (flags byte + flyingSpeed + walkingSpeed floats), food level, held slot, the hotbar and offhand items (encoded `ItemStack`s), attributes (id + base + modifiers, encoded), effects (encoded `MobEffectInstance`s), gamemode, fallDistance |
| config fingerprint | hash of `revival.yml` and of the knockback profile in use |

**Records**: a tag byte, then the payload. Serverbound records are written in arrival order. Clientbound records are
written in wire order and interleaved with them by the single-thread capture.

| Tag | Record | Payload |
|---|---|---|
| `0x01` | TICK_END | varint µs since the previous TICK_END arrived. Closes the client-tick frame. |
| `0x02` | INPUT | 1 byte, the `Input` flags as `Input.STREAM_CODEC` writes them (V `world/entity/player/Input.java:14-26`) |
| `0x03` | MOVE | flags byte (hasPos, hasRot, onGround, horizontalCollision), then x, y, z as XOR deltas against the previous reported position (one header byte = leading zero bytes << 4 \| trailing zero bytes, then the middle bytes; bit-exact), then yRot and xRot as XOR deltas against the previous floats |
| `0x04` | COMMAND | action byte, plus a varint for riding jump |
| `0x05` | PONG | zigzag varint (id − expected next id) |
| `0x06` | TELEPORT_ACK | varint id, 3 raw doubles, 2 raw floats (26.3's `AcceptTeleportation`) |
| `0x07` | ACTION | varint packet id, varint length, vanilla-encoded body: attack, interact, use item (with sequence), use item on, swing, player action (dig, release use, swap), held slot, `PlayerAbilities` (fly toggle), vehicle move, `PlayerLoaded`, chunk batch received |
| `0x40` | SERVER_TICK | varint delta of `MinecraftServer.currentTick` (main thread, merged by timestamp) |
| `0x41` | PING | zigzag varint (id − previous ping id) |
| `0x42` | STATE | varint packet id, varint length, vanilla-encoded body. Covers: self motion (`LpVec3`, so quantization is exact), `PlayerPosition` (teleport/correction), abilities, self attributes, self effects add/remove, block update and section update within the region, block-changed ack, game event (gamemode), health/food, explode (with `playerKnockback`), self entity data, set passengers, held slot / container slots for the hotbar, world border, respawn |
| `0x43` | SIM | per client tick: the server's result after simulating that tick. Either a 4-byte CRC of the position and velocity bits plus a flags byte (the default), or full XOR-delta position and velocity (debug mode) |
| `0x44` | CORRECTION | reason code byte, then the divergence (three floats) |
| `0x45` | MARK | UTF note (scenario name, test step) |
| `0x46` | REMOTE | optional, for lag-compensation tests: the ids and encoded move-entity packets of other players sent to this client |

Ordering rules the replayer relies on:

- A client tick frame is everything between two TICK_ENDs.
- PONG and TELEPORT_ACK records come before a frame's actions and moves, because the client handles packets at frame
  start.
- Every STATE record between PING *k−1* and PING *k* belongs to transaction *k*. Its effects apply to the client tick
  whose frame contains PONG *k*, or the first tick after it.

The other reports (02, 04) define the exact transaction rule. This format only keeps the wire order, so any rule can
be replayed.

### 4.3 World snapshot

At trace start, take a palette dump of every section in the region box: an arena bounding box, or ±48 blocks around
the player. Store block-state ids under data version 5023 (`Block.BLOCK_STATE_REGISTRY` ids) and fluid states
derived from block states. Name it `snapshots/<sha256>.rvws` and reference it from the header, so the same arena is
stored once for many traces. Block changes during the trace are already in STATE records, as the client received
them. The client's own predicted placements are rebuilt from ACTION `use_item_on` and the block-changed ack. An
alternative that captures exactly what the client saw is to record `ClientboundLevelChunkWithLightPacket` bodies for
chunks in the box. That costs roughly 10 to 60 KB per chunk, once, and is better for open worlds than arenas.

### 4.4 Size (measured by simulation)

`R/trace/size_estimate.py` simulates one minute (1,200 client ticks) of HCF-style PvP and encodes it with the layout
above. The simulation: constant mouse look, sprint-strafing, jumps, about 3 input changes per second, 7 CPS of
attack + swing, knockback about once a second (STATE motion + PING), RTT 2 ticks, and a 4-byte SIM hash every tick.

| Record | Count/min | Bytes/min | Bytes each |
|---|---|---|---|
| move | 1,200 | 33,156 | 27.6 |
| sim_hash | 1,200 | 6,000 | 5.0 |
| tick_end | 1,200 | 4,800 | 4.0 |
| action | 788 | 3,360 | 4.3 |
| server_tick | 1,200 | 2,400 | 2.0 |
| state (velocity) | 62 | 660 | 10.6 |
| input / command / ping / pong | 161 / 31 / 62 / 62 | 322 / 62 / 124 / 124 | 2.0 |

- **Total: 51,008 bytes/min raw (42.5 B per client tick), 40,279 zlib-9, 39,216 xz.**
- With a ping every server tick (`--ping-every-tick`, for timer checks): 56,538 raw, 41,065 zlib.
- That is about 2.4 to 3.4 MB per player-hour, so 5 to 7 MB per hour for a 1v1 with both players traced. The world
  snapshot is extra, once per arena.
- Moves dominate. Positions change in their low mantissa bits every tick, so XOR deltas save little: 27.6 bytes
  against 34 raw. That is the floor for bit-exact traces. Full SIM records instead of the hash add about 35 B per tick.

```bash
python3 -I ~/Developer/tools/auth-rig/trace/size_estimate.py [--seed N] [--ping-every-tick]
```

### 4.5 Offline replay (golden tests)

In the harness JVM (vanilla classpath, `RigWorld` loaded from the snapshot):

1. Load the snapshot and the header state.
2. For each client tick frame:
   - apply every STATE whose transaction's PONG lies in or before this frame (decode with the vanilla codec; apply
     motion, attributes, effects, abilities, teleports and blocks to the simulated client);
   - apply the frame's actions (item use, slot);
   - take the frame's INPUT if present, otherwise the last one;
   - take the rotation from the frame's MOVE, otherwise the last one;
   - run **the port** for one client tick.
3. Assert:
   - if the frame has a MOVE with a position: the port's position equals the reported doubles, **bit for bit**;
   - otherwise: the port's position is within 2.0E-4 of the last reported one, and the reminder count is under 20;
   - `onGround` and `horizontalCollision` equal the MOVE flags;
   - sprinting equals what COMMAND implies;
   - the SIM CRC equals what the live server produced. This shows the live server and the offline port agree, so the
     live server ran the same code.
   - when an agent TSV exists for the same run (matched by tick-end count from login): the port's velocity at each
     tick equals the client's logged velocity bit for bit.
4. On the first mismatch, print the frame, the inputs, the port's intermediate values and the collider list. Most
   failures will show up as a one-ULP difference first.

Grow a corpus of real traces: walking, sprint-jumping, strafing, edge sneaking, ladders, water, lava, cobweb,
soul sand, ice, honey, slime, knockback in the air and on the ground, w-taps, sword blocking, eating while walking,
teleports, and cardinal yaw. Run it in CI next to `runRevivalTests` (the vanilla jar is fetched by sha1 and cached).
Add the same traces as live replays through `FakeClient` for plumbing.

---

## 5. Files written

| Path | What |
|---|---|
| `R/fetch-server.sh` | download + sha1 + extract the vanilla 26.3 server bundle |
| `R/harness/{build.sh, src/rig/RigWorld.java, src/rig/Proof.java}` | the offline proof (Section 1.3) |
| `R/diff/{run.sh, refresh-leaf.sh, src/rig/diff/Probe.java, src/rig/diff/PaperPath.java, src-host/rig/diffhost/CollideDiff.java}` | the vanilla vs RevivalPaper collision fuzzer (1.6) |
| `R/client/{fetch-client.py, launch.py, rigtap.swift, rigtap, scenarios/*}` | 26.3 client fetch, launch, timeline input (2) |
| `R/agent/{build.sh, src/rig/agent/RigAgent.java, rig-agent.jar}` | tick-exact input and client ground-truth agent (2.4) |
| `R/livetest/src/gg/hcfrevival/revival/livetest/FakeClient.java` | live-test fake client sketch, compiles against the current build (3.2) |
| `R/trace/size_estimate.py` | RVTR size simulation (4.4) |
| `R/dl/` | downloaded vanilla server bundle, asset index 34, copy of the RevivalPaper build (refresh with `refresh-leaf.sh`) |

## 6. Risks

See the structured summary. The main ones:

- Paper's collision code is not bit-exact (proven).
- Gale's live `EntityCollisionContext` ignores `entityBottom`.
- SDL3's response to synthetic mouse deltas is untested.
- The rig copies of the RevivalPaper build go stale.
- The fake client cannot replace real-client truth.
- Trace capture must sit at the pipeline position described in 4.1, or the async tracker scrambles the order.
