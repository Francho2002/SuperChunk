# Adaptive Compute: implementation status and review notes

This branch remains **incomplete and is not an experimental release candidate**. Cycles 2.1
and 2.2 start from the reviewed incoming HEAD `842eed1712823063849dd3904105e430595b0f7a`,
which contains the prior work based on `54dc5d3aa78c2348dfe1d38af1d03ff88caf953c`. No game
or server was started for these cycles.

## OpenCL characterization

The backend queries the device name, vendor and vendor ID, driver and OpenCL versions,
extension string, compute units, global and local memory, maximum allocation, work-group
and per-dimension work-item limits, and FP32/FP64 capability bitfields. Failed optional
queries yield zero or an empty list/string; these values are unknown, not inferred specs.
The existing FP64 availability check and CPU fallback remain in place.

`gpu.platformIndex` plus `gpu.deviceIndex` still have first priority. Indices correspond to
the actual logged platform and device indices even if an earlier platform has no devices.
Alternatively set both `gpu.deviceVendor` and `gpu.deviceName` to the exact strings in the
log. An absent or non-unique match falls back to automatic selection. The optional
`gpu.preferRx9070Xt=false` can prefer one uniquely recognized RX 9070 XT. Recognition
requires AMD vendor ID 0x1002, an AMD vendor string and an unambiguous model name; an
architecture string such as `gfx1201` is insufficient. An unrecognized name gets the
generic path. A manual choice lacking required FP64 still triggers the existing CPU
fallback. These options do not change batch settings or precision. Cycle 2.2 adds only an
offline, candidate-only calibration profile flow; it does not load or apply profiles at
runtime. Its single synthetic run and limitations are documented in
`ADAPTIVE-COMPUTE-CALIBRATION.md`; it is not evidence of world-generation speedup.

The defaults remain GPU off, FP64, batch limit 32, window 500 microseconds and depth 2.
The pre-existing compact-IDs mode defaults to `on` when GPU is enabled; its separate
`gpu.decideFp32` setting is true, and its comments describe an empirical envelope, not
universal bit-exact parity. Use `-Dsuperchunk.gpu.compactIds=off` for the existing strict
mode; this branch has not validated any terrain path on the target device.

## Cycle 2.1: runtime generator authorization

This cycle separates compilation from dispatch authorization. `MixinNoiseConfig`,
`BytecodeGen`, `CacheLikeNode` and `GpuDfcHook` remain unchanged: the existing run-constant
compile latch and AST-keyed class cache keep the same generated class structure. A task permit
is checked only when a live consumer is about to read or submit GPU work. Missing, stale,
unrecognized or mismatched context follows the existing CPU path.

### Generator classification

`GpuWorldgenPolicy` gets the actual generator from
`ServerLevel.getChunkSource().getGenerator()` and reads its codec key through
`ChunkGenerator.getTypeNameForDataFixer()`. Vanilla GPU authorization requires the exact
`NoiseBasedChunkGenerator` runtime class and codec `minecraft:noise`. A compatible subclass
requires an explicit registration of both its exact class and codec id; none is registered by
default. Other known codecs/classes are custom and unknown/missing codec information is
`UNKNOWN`. Tellus detection checks the class-name prefix/suffix or a `tellus:` codec string,
without resolving Tellus classes. Every custom, Tellus and unknown classification is denied GPU.
Thus having Tellus installed does not affect unrelated dimensions, and SuperChunk does not
require Tellus.

### Permit identity and lifetime

Each permit is made from one real `ChunkAccess`, its `ServerLevel`, that level's exact current
`ChunkGenerator` and `RandomState`, and a token for that exact tuple. Creation and each later
authorization recheck those object identities. A synchronized weak-reference identity table
retains weak `ServerLevel`, generator and RandomState references and creates a new token when
either generator or RandomState identity changes; each token contains only an id and dimension
key. Permits also hold weak references to level, generator, random state and chunk. Cache/store
keys include the token, so equal chunk coordinates,
a shared generator object or a changed RandomState cannot transfer a deposit between tasks or
worlds. The weak references and tokens do not retain unloaded worlds.

The synchronous noise fill uses a scoped per-worker frame around `NoiseBasedChunkGenerator.doFill`.
Biome generation wraps the actual `CompletableFuture.supplyAsync` supplier in
`ChunkGenerator.createBiomes`, carrying that method's `RandomState`, generator and chunk into
the worker that calls `ChunkAccess.fillBiomesFromNoise`. `BiomeClimateCache.beginChunk` accepts
only this current frame, the exact chunk, and the exact `RandomState.sampler()` identity passed
to the fill; it does not reconstruct authorization from the dimension. The frame is pushed even
for a denied child task, masking any outer authorization,
and restored only when that exact frame closes on its owner thread. An out-of-order close clears
the stack instead of restoring a potentially stale permit. Prefetch operations derive a permit
directly from the actual chunk and generation context, capture it in the submitted operation and
revalidate it when the asynchronous result completes. No asynchronous continuation relies on
the `ThreadLocal`.

The mapped Minecraft jar used by this build is
`build/moddev/artifacts/neoforge-21.1.215-merged.jar`. Static `javap -private -c -s` inspection
confirmed that `fillFromNoise(Blender, RandomState, StructureManager, ChunkAccess)` creates a
`CompletableFuture.supplyAsync` task; its generated worker lambda directly invokes synchronous
`doFill(Blender, StructureManager, RandomState, ChunkAccess, int, int)`. That `doFill` method
starts with `ChunkAccess.getOrCreateNoiseChunk` and then executes the interpolation loop before
returning. `MixinNoiseFillGpuAuthorization` wraps that exact mapped descriptor, so it encloses
the body where `SubCompiledDensityFunction.fillArray` is called, not just the future factory.
Static inspection also confirmed `ChunkGenerator.createBiomes(RandomState, Blender,
StructureManager, ChunkAccess)` returns `CompletableFuture.supplyAsync`; its task lambda passes
that same `RandomState.sampler()` to `ChunkAccess.fillBiomesFromNoise`. The new wrapper is on the
`supplyAsync` call and wraps its supplier, so the permit follows the actual async task even if an
executor runs it on a different thread. This establishes source/mapping targets, not that either
mixin applies successfully at runtime.

### Protected consumers and fallback

The shared dispatch callback gate is used at the actual GPU submit sites:

* `GpuDfcDelegate.fill` for direct density work; `SubCompiledDensityFunction.fillArray` supplies
  a permit only for `NoiseChunk` callers.
* `ChunkGridCache.beginChunk`, `tryServe` and its eager `dispatchAsync` path; the context is
  bound only after authorization and carries the permit and world token. On-device interpolation
  is not bound for denied chunks.
* `BiomeClimateCache` arm/serve and its batcher or async dispatch points, keyed by the task token
  and entered only under the `createBiomes` supplier's exact task frame.
* `GpuBatchPrefetch` and `GpuClimatePrefetch` submits, chained density/climate submits, and their
  asynchronous deposits. The captured permit is revalidated before storing results.
* `CompactConsume` direct processing and store consumption use the current scoped permit; no
  permit means the original CPU fill.

`GpuBatchStore`, `GpuClimateStore`, geometry membership and the single global grid batcher are
world-scoped. The grid batcher is tied to one world token at a time; a different token cannot
reuse its fused geometry and falls back to the authorized per-chunk path or CPU. Disabled GPU,
unavailable device, submission failure and denied permits all leave the caller's CPU path
available; no new client dependency or runtime mod is added.

### Tellus and Distant Horizons

No Tellus internals, terrain, noise, biome geography, roads, rivers, buildings or structures are
modified. `EarthChunkGenerator` is recognized optionally and gets CPU-only policy. In addition,
`BiomeClimateCache` only serves under a task frame whose exact `ChunkAccess` matches, and any
prefetch deposit must have the same tuple token and fused group. Tellus cannot consume a vanilla
world deposit. This is a conservative source-level guard, not production Tellus compatibility
evidence.

The current `MixinDhWorldGenStep` hook supplies only a `ChunkStatus` and a list of wrappers; it
does not expose the generator or `RandomState` actually used by DH's group. Deriving those values
from a wrapper's `ServerLevel` would not prove that they belong to DH's generation task. Therefore
DH group batch prefetch is explicitly disabled, including both BIOMES and NOISE group submits.
Regular per-chunk consumers still require the live chunk/level/generator/random-state checks;
this does not validate any DH integration.

### Shared generator mixins

`MixinFeatureOutsideRegion` now retains its exception-skip optimization only for the exact
recognized vanilla noise generator. Custom, Tellus and unknown generators call the original
method without swallowing out-of-region exceptions. The vanilla path can still skip a placed
feature when that specific region exception is observed; whether every modded feature under a
vanilla generator has the intended exception semantics requires an integrated worldgen test.

`MixinPlainWorldgenRandomSites` is unchanged. It changes the random object's synchronization
strategy at constructor sites, not generator-specific math; its bit-identical/thread-confined
assumptions were reviewed from source but were not exercised under modded worldgen. The
decoration-step cache is also unchanged: it caches the same registry grouping map by registry
object identity, with a new registry identity naturally invalidating it. Reload races and
modded decoration behavior still need runtime coverage. These mixins are not evidence of full
generator compatibility.

### Regression and static checks

`gpuWorldgenPolicyRegressionTest` compiles equal `ConstantNode` ASTs through the real
`BytecodeGen.compile0` cache without bootstrapping Minecraft, then compiles a test subclass that
inherits `CacheLikeNode` bytecode generation with GPU routing latched on and OpenCL unavailable.
The latter verifies reuse of the generated class, presence of the `GpuDfcDelegate` slot and
compatibility of constructor argument order on the cache hit. It then checks the same dispatch
callback against different per-task permits.
Counted fake backend callbacks cover direct density, chunk grid, climate, density prefetch,
climate prefetch and the disabled DH group route. The test also covers CPU fallback counts,
missing/stale context, custom/Tellus/unknown generators, disabled GPU, missing device, world-token
mismatch, a shared generator/program object, an explicit permit captured on another worker, and
a throwing GPU callback that must report once, fall back once and not propagate. It does not
initialize OpenCL or a Minecraft world. The `CacheLikeNode` fixture uses a null cache object
and overrides only the test subclass's equality/hash behavior because a concrete
`IFastCacheLike` initializes Minecraft registries; it exercises inherited production code
generation but does not test a live cache object or a successful OpenCL delegate build.

The latest full `gradlew check` passed on Microsoft OpenJDK 21.0.11 with the project's NeoForge
21.1.215 compile dependency (24 tasks, including `compileJava`, the standalone regression
tasks and `test`). The policy fixture reported 184 checks, and the calibration profile fixture
passed. The following static check was also completed:

* `javap -private -c -s` inspection of the mapped `NoiseBasedChunkGenerator.fillFromNoise` / `doFill`
  descriptors and call path described above.

No client, server, GameTest, world, Minecraft bootstrap or runtime mixin application was run.
There is no evidence yet of runtime startup or terrain/biome parity with the new gates. The
shared-mixin risks above, DH's disabled group batching, actual MixinExtras application, world
unload behavior under load, and Tellus-present/absent behavior remain runtime-only or pending.
Do not call Tellus production-compatible or deploy this branch until those checks are performed.

## Prior Cycle 2 generator-policy gap (recorded at incoming HEAD 842eed1)

The following notes describe the state before Cycle 2.1, not the implementation state above.
Relevant base source inspected at `54dc5d3`:

* `MixinNoiseConfig` targets `RandomState`, compiles its router and climate sampler and
  calls `GpuDfcHook`, without receiving the active generator. `BytecodeGen` caches generated
  classes by AST and `CacheLikeNode` relies on stable GPU-field layouts.
* `GpuDfcDelegate.fill`, `ChunkGridCache` and `BiomeClimateCache` can run outside the
  batched prefetch seam. Blocking only `GpuBatchPrefetch` or `GpuClimatePrefetch` would
  not prohibit all direct or per-chunk GPU execution. `DhWorldGenBridge` is another caller.
* `MixinFeatureOutsideRegion`, `MixinPlainWorldgenRandomSites` and
  `MixinChunkGeneratorDecorationStepCache` target shared generator behavior. For example,
  the first can skip a feature on an out-of-region exception and defaults enabled; this
  requires separate compatibility analysis rather than an assumption of non-intervention.
* The NeoForge 21.1.215 `ChunkMap` source constructs `RandomState` using the
  `NoiseBasedChunkGenerator` settings or dummy settings for other generator types.
  The active generator is available at that constructor, but attaching a policy only
  there must account for generated-class caching, other `RandomState` creation contexts,
  asynchronous scheduling, and all direct consumers before it can be considered safe.

Engineering inspected the installed Tellus 0.8.3 JAR with `javap`: its final
`com.yucareux.tellus.worldgen.EarthChunkGenerator` extends
`EarthChunkGeneratorVersionCompat`, which extends `ChunkGenerator` directly, not
`NoiseBasedChunkGenerator`. This excludes it from mixins aimed solely at the latter type;
it does not establish non-intervention by shared mixins or prove world-generation parity.
No Tellus source or code was changed by this branch.

Read-only listing of the server `mods` directory showed active filenames for Tellus 0.8.3,
Chunky 1.4.23, MineColonies 1.1.1403, Structurize 1.0.832, Create 6.0.10, Create
Aeronautics 1.3.2, Sable 2.0.6, CBC Neo Warfare 0.0.6v, CMI dev, and SuperChunk 0.6.0.
The server's top-level `datapacks` directory was empty; world datapacks and actual enabled
pack state were not inspected. Presence of a JAR does not establish its active generator.

## Earlier standalone diagnostic (before Cycle 2.2)

`gradlew standaloneGpuProbe` explicitly runs a diagnostic in a separate headless JVM. It
does not run on `build`, `check`, or mod startup, nor read the server configuration. It
uses two directly constructed ASTs (Y gradient and seeded normal noise), the existing
`GpuBatchDispatcher` and `GpuChunkBatcher`, FP64, 16 origins and 5x49x5 points. It checks
every returned double bit-for-bit against those ASTs' CPU `evalSingle` results before
printing each repetition. This is AST parity, not vanilla, chunks or world parity. Compact
IDs are off and grids retained. Each configuration and scenario gets one warmup and three
repetitions; LATENCY submits sequentially, BULK submits all 16 before waiting. Order is fixed,
not randomized. Preparation time covers CPU reference generation only. Aggregate time
includes submission and completion but excludes parity checking; latency is the ninth of 16
sorted completion times. Neither separate kernel, upload, readback nor GPU wait times are
measured. The internal 30-second deadline bounds checks and future waits; the Gradle task has
a 60-second timeout for an unresponsive native call. Pending futures are cancelled on error
and the batcher and backend are closed in `finally`; a driver stuck in native code may require
process termination and cannot be guaranteed to run Java cleanup.

On 2026-11-02 the direct-AST probe completed. CIM reported an AMD Radeon RX 9070 XT with
Windows driver 32.0.22042.14002; OpenCL reported only `gfx1201`, AMD driver `3661.0
(PAL,LC)`, OpenCL 2.0 and FP64. CIM alone does not prove that the OpenCL device maps to that
exact model. CPU reference preparation took 50.563 ms. The table shows ranges of the three
aggregate throughputs and median latencies, not a recommendation or measured world throughput:

| Scenario | Limit / window / depth | Requests per second | Median latency, ms |
| --- | --- | ---: | ---: |
| LATENCY | 32 / 500 us / 2 | 32.151–32.287 | 30.994–31.049 |
| BULK | 32 / 500 us / 2 | 512.469–1055.172 | 15.057–30.989 |
| LATENCY | 8 / 50 us / 2 | 48.599–57.547 | 15.529–15.798 |
| BULK | 8 / 50 us / 2 | 507.751–525.088 | 30.351–31.404 |
| LATENCY | 16 / 200 us / 2 | 31.954–33.173 | 30.980–31.131 |
| BULK | 16 / 200 us / 2 | 1033.545–1082.471 | 14.660–15.415 |

All repetitions in this run passed exact AST comparison. The spread, fixed order, small
workload and missing stage timings preclude selecting a winner. Recorded SHA-256 values:
probe class `0cfea6cd08b5fe4700c14f2edc01d63ae8284b6277ca892bf52dac9cdcdf09dd`;
dispatcher class `84a814fa1ba92f0db3350ecb88e58e806e035f45f7ac790665f8dbeba7b78701`;
emitter class `41e13ea7be595b575db69e9bdf3dc9d9faa9c785128b2f2cfa9c201133f552dc`;
`noise.cl` `9a5ceed3aae126bc5d185a67b2e10f756e3d8edc9dc7b17491b27feafab7bc0e`;
`dfc_support.cl` `b09e70b13862e88813c92732c73a75746fea6a556fc70e8e9aecd5e9dad03477`;
captured log `dfc8c8e6655bca726f5f24a6dac2bedda8e1e6ce60d7a20c01663926a9bf4245`.
The probe source was subsequently changed only to put initialization inside the cleanup
`try` and expose its deadline helper for a fixture; this hash is the measured class, not a
claim that the later class is byte-identical.

An earlier attempted standalone run explicitly called Minecraft `Bootstrap.bootStrap()`.
That was a violation of the instruction not to load Minecraft. It failed during registry
initialization because NeoForge's `LoadingModList` was null; no client, server or world was
started and the attempt produced no benchmark measurements. The call was removed and was not
repeated. A later direct-AST attempt failed on `CompactConsume` initialization before producing
measurements; the successful diagnostic keeps those grids and disables compact IDs.

The measurements above are historical results from the earlier direct-AST diagnostic, not
Cycle 2.2 calibration results. Versioned candidate persistence and selection were not present
in that earlier diagnostic. The current candidate protocol and its synthetic run are in
`ADAPTIVE-COMPUTE-CALIBRATION.md`; neither flow measures world-generation parity or stage-level
server performance, and no profile is loaded or applied at runtime.

## Regression and integration status

The latest `gradlew check` passed on Microsoft OpenJDK 21.0.11 with the project's NeoForge
21.1.215 compile dependency, including the standalone regression tasks. Device fixtures cover the
model distinctions, ambiguous identity, precision requirement and empty-platform indexing;
they do not call a real OpenCL driver. The configuration documentation round-trip and device
fixtures passed again after the final configuration edit. The earlier diagnostic above used an
OpenCL device, but did not use the Cycle 2.2 selector. NeoForge 21.1.265 is the intended runtime, not a
version validated by this build.

The CBC `MixinChunkMapGetPlayers` priority 900 patch is unchanged relative to the base.
`MixinNoiseConfig` also has priority 900 independently. Existing
mixins and regression tests passed static/build checks; this is not an integrated game test.
No independent C2ME OpenCL, extra LWJGL runtime or other mod was introduced. The known
independent C2ME OpenCL/LWJGL module conflict remains a deployment constraint.

Pending after Cycle 2.1: runtime mixin application, disabled-GPU/device-loss behavior under
server load, Tellus-absent/present tests, vanilla/custom generator parity, DH integration, CBC
startup regression, and separately authorized temporary-world comparisons of terrain, biomes,
roads, structures and caves. Do not deploy or publish this branch on the basis of static and
standalone checks.

## Future CMI integration

The existing dispatcher and batcher can inform a later internal adapter for independent
trajectory, projectile, geometry or interception batches, but their worldgen-specific buffer
and kernel contracts are not a public API. CMI must own game state; send immutable snapshots,
validate returned results before applying them, retain a CPU route, and bound or cancel work
so the main tick never waits indefinitely. No CMI, Sable, Create or CBC code is changed here.

## Cycle 2.1R / 2.2R — audit corrections (2026-11-02)

### Review baseline and outcome

- Review branch: `feature/kxcraft-adaptive-compute`.
- Reference and final HEAD: `842eed1712823063849dd3904105e430595b0f7a`; the review changes remain uncommitted in the working tree.
- No tracked files were deleted. The current review set contains 19 modified tracked files and 16 new untracked files (35 paths total); the original 29-file implementation set was retained and the additional files are regression coverage and review support.
- Proposed state: **GO for GPT-6 code re-audit only; NO-GO for deployment, runtime compatibility claims, or release**.

### Observation closure matrix

| Observation | Implementation and evidence | Status / remaining limit |
|---|---|---|
| H1 — chunk identity | `CompactConsume` now checks the permit against the exact `ChunkAccess`, generator, current `RandomState`, authorized scope, and payload route before consuming a deposit. The fast path removes only the identical validated payload before mutation; failed checks leave CPU generation in control. Regression coverage includes same-world distinct chunks, same coordinates in distinct worlds, stale state, wrong group/deposit, nested scope, and expired async context. | Closed by static regression evidence; runtime chunk-generation behavior remains unverified. |
| H2 — global batcher lifecycle | The primary batcher records its owner scope. Retirement blocks new owner dispatch, cancels queued work, drains active work before closing shared resources, and allows a later candidate only after safe teardown; shutdown ordering was updated and regression fixtures exercise retirement/rebind and lease behavior. | Implementation and hardware-free lifecycle regression pass. Unload/rebind under real server and driver scheduling remains unverified. |
| H3 — destination resource identity | Density and climate paths validate against independently obtained target ownership/registration state rather than comparing a permit scope with itself. Climate dispatch rechecks registered router/group/dispatcher/root identity; geometry and fused-group barriers remain. | Closed by static regression evidence; no runtime dispatcher or Distant Horizons integration test was run. |
| H4 — measurement quality | Optional checksummed trial archives retain individual measured trials and request latencies, identify the workload as synthetic, distinguish it from world-generation evidence, and reconstruct median/P95 plus trial dispersion. Profile selection and persistence remain offline and manual. | Implemented and fixture-tested. No new prolonged calibration run was authorized or performed; no profile was applied. Earlier probe results are historical only. |
| H5 — mixin contract diagnostics | The ASM contract test checks target descriptors, injection points, config entries, close-retirement hook, and shutdown ordering. `require=0` is explicitly detected and reported. | Static baseline checks pass. `require=0` may silently skip an injection if runtime transformation changes its target; only runtime mixin application can settle that. |

### Agent reports and integration

**Agent A — H1/H3, GPU isolation:** Changed `GpuWorldgenPolicy`, `CompactConsume`, `GpuBatchStore`, density/climate prefetch and registration checks, the CompactIds mixin, and policy regression fixtures. Agent A's own `git diff --check` passed; the agent did not run Gradle. The coordinator's full `check` subsequently exercised the integrated regression tasks.

**Agent B — H2/H4, lifecycle and calibration:** Changed `ChunkGridCache`, `GpuChunkBatcher`, backend shutdown ordering, the `ServerLevel.close` retirement hook, calibration measurement/trial persistence and probe wiring, documentation, and lifecycle/calibration fixtures. The integrated regression tasks passed in the coordinator's final `check`. No new probe or extended benchmark was run during this correction cycle.

**Coordinator — H5 and integration:** Added the static ASM mixin contract regression task and checked the combined diff, CBC patch baseline, Tellus policy, test suite, and ZIP evidence. The CBC `MixinChunkMapGetPlayers` priority-900 patch has no diff from the reference HEAD. No CBC, CMI, Tellus, Sable, Create, server, world, or installed JAR was changed.

### Verification performed

- Environment: Microsoft OpenJDK 21.0.11.
- `.\gradlew.bat check`: **BUILD SUCCESSFUL**; 26 actionable tasks, 21 executed and 5 up-to-date. This included the policy (216 checks), lifecycle, calibration-profile and mixin-contract regression tasks.
- `.\gradlew.bat worldgenMixinContractRegressionTest`: **BUILD SUCCESSFUL**. The scanner reports that both `require=0` hooks were statically identified and that runtime application remains unproven.
- `git diff --check`: passed; Git emitted only line-ending conversion notices for three working files, not whitespace errors.
- No Minecraft bootstrap, client, server, GameTest, world, OpenCL benchmark, or runtime probe was started in this correction cycle.

### Compatibility boundaries and residual risks

Tellus remains optional and unrecognized/unapproved generator paths are denied GPU world-generation authorization, leaving the CPU path in control. No production compatibility claim for Tellus is made. The mixin diagnostic verifies mapped/static contracts only; competing runtime transformers and actual injection application are untested. Batcher retirement and native OpenCL resource quiescence have not been exercised against a running server/device. The H4 trial archive and reconstruction path have fixture coverage, but no new measurements were collected; synthetic measurements do not establish terrain throughput or server performance. CBC camera functionality is not validated by this work.

The branch is ready for GPT-6's independent re-audit only. Runtime integration, deployment, profile activation, commits, push and publication remain out of scope until separately authorized.

## Cycle 2.2R-FINAL — lifecycle audit closure (2026-11-02)

### Scope and state

- Branch: `feature/kxcraft-adaptive-compute`; base and HEAD remain `842eed1712823063849dd3904105e430595b0f7a`.
- The review working tree now contains 19 modified tracked paths and 17 new untracked paths (36 total), with no deletions. The index remains empty.
- This is a static and automated-regression closure proposal for GPT-6 review. It is not runtime validation and does not authorize deployment or release.

### Agent reports

**Agent A — ChunkGridCache owner selection:** only `ChunkGridCache.java` changed. Same-scope replacement requires exact `WorldScope` identity and a strictly larger `rootCount`; the best valid candidate remains pending while the current batcher retires. Retirement reasons distinguish a superseded fused group from a retired world. A superseded group preserves the world's other fused memberships; a retired world removes its full scope only after batcher quiescence. Failed close or terminal backend state cannot rebind. The owner generation guards stale completions. `git diff --check` passed; Agent A did not run Gradle.

**Agent B — batcher lifecycle:** changed `GpuChunkBatcher.java` and `GpuChunkBatcherLifecycleRegressionTest.java`. Retirement callers share one completion stage with explicit request dispositions and final results. A retirement arriving after normal shutdown begins closes the dispatch gate and wakes the drainer, which fails queued work instead of flushing it. Shared installation waits for prior lifecycle completion and uses one atomic singleton gate. Backend shutdown marks every live shared or dedicated batcher under the admission lock before taking its lifecycle snapshot. Dispatcher close is attempted once; failure retains the owner, prevents rebind and makes backend teardown return failure. Joins remain unbounded for native safety, with a warning every 30 seconds while workers remain alive. Agent B's `git diff --check` passed; the coordinator ran Gradle.

**Coordinator — OpenCL teardown, selector test and H5 scanner:** `OpenCLBackend.shutdown()` now stops before closing the OpenCL context if batcher teardown reports failure, disables GPU use, and refuses reinitialization in that process. The H5 ASM contract check now asserts the boolean batcher shutdown result has an early-return veto before fused/climate/context cleanup. Added `ChunkGridCacheBatcherSelectionRegressionTest`. No Minecraft/runtime execution was performed.

### H2 evidence matrix

| Scenario | Regression evidence | Result |
|---|---|---|
| Smaller fused group followed by a larger group in the same world | Production selector is exercised with increasing, decreasing and equal root counts. | Strictly larger same-scope candidate wins; equal/smaller candidates do not churn the owner. |
| Distinct world registration during retirement | Selector fixture rejects a higher-root candidate with a distinct scope identity; lifecycle fixture keeps the shared slot empty while owner A's close is held. | No cross-world ownership transfer or early install. |
| Work admitted before retirement | A synthetic queued request is paused deterministically, then retirement closes admission before the drainer resumes. | Its future resolves exceptionally and dispatch attempts remain zero. |
| Completion and safe reassignment | Fake dispatcher close for A is latch-blocked; B initialization uses the production shared-install gate. | B creation remains zero until A returns `QUIESCENT`; then B installs alone and is shut down once. |
| `shutdown()` before `retireOwner()` | Fixture starts normal shutdown with a queued request, then requests world retirement. | Retirement joins the same stage, flips the dispatch gate, wakes the drainer, and prevents the queued dispatch. |
| Retirement before `shutdownBackend()` | Backend barrier begins while owner A retirement is blocked in fake close. | Backend waits for the same stage and also shuts down another live dedicated test batcher before returning. |
| Dispatcher close failure | Fake close action throws. | Result is `CLOSE_FAILED`; close count stays one; owner remains strongly retained; `shutdownBackend()` returns false and `OpenCLBackend` preserves native context handles. |
| Exactly-once completion/cleanup | Duplicate retire requests receive the same stage; shutdown/retire share the lifecycle result. | One dispatcher-close attempt; completed retirement reports `ALREADY_COMPLETE`. |
| No work after retirement | Both retirement-first and shutdown-first queued-work cases inspect future result and dispatch counter. | No queued job reaches the dispatch hook after the retirement gate closes. |
| No stale successful-retirement slot | A's completion removes the active lifecycle before the shared future is completed; the fixture observes A quiescent before B is installed. | No completed batcher lifecycle retains the `ACTIVE_LIFECYCLES` install gate; ChunkGridCache retains its separate cleanup latch until manager cleanup finishes. Failed close intentionally remains fail-closed. |

### H1/H3/H4/H5 preservation and verification

H1/H3/H4 regression tasks remain in `check` and passed. The H5 scanner passed and confirms the expected static targets/injection points, the required `ServerLevel.close` hook, and shutdown cleanup ordering. The two authorization hooks still use `require=0`; runtime transformation can still skip them if the runtime target differs. Static checks do not prove mixin application.

Final verification on Microsoft OpenJDK 21.0.11:

- `.\gradlew.bat check`: **BUILD SUCCESSFUL**, 27 actionable tasks (24 executed, 3 up-to-date). Includes lifecycle, selection, 216 worldgen-policy checks, calibration, mixin contract, and existing regression tasks. The close-failure fixture intentionally emits a warning/error log while asserting fail-closed behavior.
- `git diff --check`: passed; Git reported only CRLF conversion notices for three files.
- No Minecraft bootstrap, client, server, GameTest, world, OpenCL probe, or calibration benchmark was run.
- CBC's priority-900 compatibility patch is unchanged from the reference HEAD.

### Remaining risks and proposed state

An indefinitely blocked driver event can keep the retirement/backend shutdown waiting indefinitely. The code reports prolonged joins and intentionally retains native resources; it does not free them while a worker may still use them. The runtime interaction among actual `ServerLevel` unload, OpenCL event completion, fused-resource cleanup, mixin application, CBC, and Tellus remains untested.

**Proposed: GO for GPT-6 static re-audit of H2. NO-GO for deployment, runtime compatibility claims, or release until separately validated and approved.**

## Cycle 2.3A — deterministic GPU authorization diagnostics (2026-11-02)

### Scope and decision boundary

This change adds low-cardinality counters and static/JVM regression checks. It does not change generator authorization, OpenCL configuration, density calculations, kernels, seeds, or biome results. Counters are enabled by default; `-Dsuperchunk.gpu.authorizationDiagnostics=false` disables them. They retain no world, chunk, generator, sampler, or task references. `LongAdder` updates are aggregated and emitted only through the existing `GpuFillStats.logSummary()` path; no per-chunk log line or new thread is added.

### Injection and route matrix

| Hook | Exact target and point | Compiled-bytecode evidence | Conflict surface and limits |
|---|---|---|---|
| `MixinCreateBiomesGpuAuthorization` | `ChunkGenerator#createBiomes(RandomState, Blender, StructureManager, ChunkAccess): CompletableFuture`; `@WrapOperation` at `CompletableFuture.supplyAsync(Supplier, Executor): CompletableFuture`. | The installed NeoForge 21.1.265 server JAR's `ChunkGenerator#createBiomes` contains one `supplyAsync` call (offset 17) after building the task supplier and obtaining `Util.backgroundExecutor()`. The pure JVM ASM contract checks the exact descriptor and call count against the mapped build artifact. | Bundled C2ME redirects `Util.backgroundExecutor()` in `createBiomes`; it redirects the executor call, not this `supplyAsync` invocation, so the seams are distinct in the inspected source. Runtime transformer order/application is unproven. |
| `MixinNoiseFillGpuAuthorization` | `NoiseBasedChunkGenerator#doFill(Blender, StructureManager, RandomState, ChunkAccess, int, int): ChunkAccess`; `@WrapMethod` around the complete method. | The method descriptor exists in the 1.21.1 server SRG class and the available mapped development artifact; the contract test checks the method descriptor and compiled handler contract. The local 21.1.265 NeoForge server JAR is a patched subset, not a full mapped merged class artifact for this target. | C2ME redirects `Util.backgroundExecutor()` at `fillFromNoise`, outside `doFill`. SuperChunk also has `CompactIds` HEAD/RETURN injections, `MixinNoiseFillAirCells`, `MixinNoiseFillHash`, and other `doFill` hooks. These use distinct injection shapes but may be ordered around the wrapper. Lithium 0.15.4's inspected generator mixin targets the constructor; ModernFix 5.27.27's inspected `NoiseBasedChunkGeneratorMixin` injects at `buildSurface`, not `doFill`. This is source/annotation inspection, not proof of final transformed bytecode. |

Both authorization injections retain `require=0`. A miss therefore need not fail startup. The Mixin plugin now counts `onLoad`, `preApply`, and `postApply` for these hooks and the `CompactIds` mixin; handlers separately count actual entry. `postApply > 0` means the mixin class was applied to the target class, not that an injector found its exact instruction. With `require=0`, `postApply > 0` and handler-entry `0` still cannot by themselves distinguish an unvisited method from an injector miss. A positive handler-entry counter proves that handler ran. The actual post-transform bytecode has not been captured offline.

### Async authorization context

`createBiomes` instrumentation records handler entry, identity capture, supplier submission, future return, worker supplier start, scope authorization/denial, and scope restoration. `GpuTaskScopeCarrier` opens the scope inside the supplier on the executor that runs it; it does not rely on inherited `ThreadLocal` state. Density and climate prefetches separately record explicit permit capture, completion-time revalidation, submission/store outcome, context rejection, and non-context result rejection (e.g. failed task, stale plan, or invalid payload). A stored climate result is only counted as used on the first successful `serve()` that reads that chunk's copied grids.

### Climate consumer counters

The `climate-consumer` summary distinguishes `beginChunk` entry; offload/backend eligibility; whether batched-store mode was bypassed by configuration; missing/denied/stale authorization; world/dimension, chunk, sampler, generator, router/group mismatches; a resolver known not to sample climate; other eligibility exits; store lookup, hit, miss, invalid payload, exception; copied grids, grids actually used, and copied-but-unused grids. A failed prefetch context is not conflated with a task/result failure. `GpuClimateStore.take()` counts successful removals only; cancellation removals and cap evictions remain separate counters. This instrumentation does not infer that every old unconsumed result lacked authorization.

### Density route counters

The `density-route` summary separates generator classification, missing/invalid permission, denied world context, absent/unresolved/disabled delegate, rejected input or fill shape, route attempt, grid-cache service, fused-dispatch authorization rejection/completion/failure, cross-chunk density-kernel enqueue/readback completion/failure, GPU fill success/failure, execution fallback, and CPU fallback. `density-compile` separates expansion-guard rejection, per-DF expansion fallback, unsupported nodes, OpenCL build failures, other compile fallback, and compiled delegates.

`density_gpu_attempt` means a compiled delegate passed its initial authorization/input checks and entered the GPU route; it can still be served from a grid cache or rejected by the fill-size gate. It is not evidence that an OpenCL kernel ran. For the direct `GpuDensityFunction` path, `density_opencl_nd_range_enqueued` is the actual enqueue point and `density_opencl_readback_completed` is its successful readback. The fused-grid completion counter is incremented only after the fused output has been read and accepted. Cross-chunk `density_batch_nd_range_enqueued` is recorded after the corner NDRange is enqueued; its matching readback completion counter records a successful event wait. A compiled delegate or successful cache serving must not be reported as a new direct kernel execution. The independent CompactIds route has its own doFill handler, consume-success, and CPU-fallback counters; the existing `[compact-ids]` report includes actual decide-dispatch counts and faults. These values are not folded into the DFC `gpuFillAttempts` counter.

### Static and automated evidence

- The ASM contract test checks the exact target owners/descriptors, the create-biomes invocation descriptor, the `require=0` values, the async carrier call, plugin lifecycle counters, and presence of climate/density stage counters.
- The JVM `GpuWorldgenPolicyRegressionTest` exercises explicit async scope transport on a worker executor, restoration of the worker's prior nested scope on normal and exceptional completion, rejected authorization, and CPU fallback without starting Minecraft or OpenCL.
- Final verification for this cycle: `.\\gradlew.bat check` **BUILD SUCCESSFUL**, 27 actionable tasks (21 executed, 6 up-to-date). The integrated policy fixture reported 226 classifier, exact-task, deposit-scope, target-resource, nested-scope, fallback, async permit and scope-carrier checks; `WorldgenMixinContractTest` passed its offline bytecode/config/diagnostic contract checks. `git diff --check` passed; Git printed only line-ending conversion notices for three files.
- Static inspection confirmed that `BiomeClimateCache.consumeBatchedClimate()` is the consumer of `GpuClimateStore.take()`. No static result explains the historical 8,877 puts / zero consumed / zero misses; those counters did not identify whether `beginChunk` exited before lookup or whether the consumer ran on another path.
- Offline bytecode inspection confirmed the exact `ChunkGenerator#createBiomes` supplyAsync call in the installed 21.1.265 server artifact. The full final transform chain and transformed `NoiseBasedChunkGenerator#doFill` bytecode were not available offline. No server/client/GameTest, world generation, or OpenCL operation was run.

### Hypothesis status and next-log guide

| Hypothesis | Status |
|---|---|
| The old climate `puts/takes` values alone prove authorization failure. | **Discarded.** They do not identify whether the consumer entered, consulted the store, or bypassed it. |
| A handler counter can distinguish a loaded mixin config from execution. | **Confirmed.** `mixin_config_loaded`, `*_mixin_pre_apply/post_apply`, and `*_handler_entered` are separate stages. A zero handler count remains ambiguous with `require=0` unless the method is known to have run. |
| The create-biomes async supplier can carry its exact generator/chunk/RandomState context to whichever worker executes it. | **Implemented and JVM-fixture tested; runtime injection pending.** |
| The 26 historical density compile rejections explain every missing GPU fill. | **Pending.** New compile and dispatch counters separate expansion-guard refusals, usable delegates, route exits, actual direct NDRange enqueue, and CPU fallback. |
| The running world had a usable GPU delegate for its density fills. | **Pending.** `compile_df_gpu_ready` means compiled, not dispatched. Compare with delegate route and enqueue/readback counters. |

In the next authorized `latest.log`, read the latest `[SuperChunk-GPU] [auth-diag] (running|shutdown) mixin-async`, `climate-consumer`, `density-route`, `gpu-batches`, and `density-compile` lines. The counters are cumulative for the process and are emitted by the existing fill summary. For authorization hooks, require `mixin_config_loaded > 0`, the matching `*_mixin_post_apply > 0`, and finally `*_handler_entered > 0`; then compare supplier start/authorized-or-denied/restored. For climate, compare `climate_begin_entered` to each rejection category, then `climate_store_lookup`, `hit/miss`, `copied`, and `used/copied_unused`; compare climate prefetch `captured`, `revalidated`, `context_rejected`, and `result_rejected`. For density, compare prefetch `context_captured`, `submitted`, `context_revalidated/rejected`, `result_rejected`, and `store_put` with `compile_df_gpu_ready` and compile fallback categories, `density_delegate_fill_entered`, denial/shape/cache counters, direct `density_opencl_nd_range_enqueued/readback_completed`, `density_fused_dispatch_completed`, batched `density_batch_nd_range_enqueued/readback_completed`, CompactIds `decide dispatches`, and `density_cpu_fallback`. Do not interpret compilation or a route attempt as kernel execution.

**Cycle 2.3A state:** GO for independent static code review and a future authorized instrumented run; NO-GO for performance or runtime-compatibility claims, deployment, commit, push, or release. The CBC priority-900 patch remains outside this diagnostic scope and has not been changed by these instrumentation edits.


## Cycle 2.3A.1 — Correcciones finales de observabilidad (2026-11-02)

### Alcance

Este incremento corrige la atribución de eventos y completa la cobertura diagnóstica. No cambia permisos GPU, fórmulas de densidad, kernels, geometrías, límites de compilación, datapacks ni datos de mundo. No se inició Minecraft, cliente, servidor, GameTest, worldgen ni OpenCL. La rama y HEAD permanecen en `feature/kxcraft-adaptive-compute` / `842eed1712823063849dd3904105e430595b0f7a`.

### Workload de batch

`GpuBatchDispatcher` conserva un tipo de trabajo desde la compilación hasta el readback:

- `compile(roots, fused)` marca los batchers de densidad en vivo como `DENSITY`.
- `compileClimate(roots)` y `compileClimateF32D(roots)` marcan batchers climáticos como `CLIMATE`.
- La entrada genérica usada por probes/parity queda como `OTHER` y no atribuye trabajo de prueba a una dimensión.
- Una ejecución solo climática incrementa únicamente `climate_batch_nd_range_enqueued/readback_completed`. No puede producir un falso positivo de densidad.
- Cuando una batch de densidad encola su NDRange climático acoplado, se registran los eventos de densidad, clima y combinado. La finalización combinada solo cuenta si también se encoló el readback climático y el evento final de la cola in-order se completó.
- `InFlight` retiene únicamente enums de workload/bucket y una marca booleana, nunca referencias a mundo, chunk o generador.

Los eventos de envío se registran tras el enqueue efectivo del kernel. Fallos antes del enqueue usan la familia `*_enqueue_failed`; fallos de lectura o espera usan `*_completion_failed`. Compilar un programa o disponer de un delegado no equivale a ejecutar un NDRange.

### Rechazos y resultado de densidad

`enterTask()` distingue chunk nulo, accessor que lanza, accessor que no es `ServerLevel`, generador discordante, `RandomState` discordante, scope retirado, generador no autorizado, GPU deshabilitada, dispositivo no disponible y elegibilidad residual. El clasificador de entradas está compartido con una regresión JVM. Los puntos de prefetch que no consiguen crear un permiso usan una lectura diagnóstica de solo observación; no crea permisos ni altera el fallback.

`GpuDfcDelegate.fill()` toma la decisión de la autorización y del backend del `DispatchResult` producido por el propio despacho. No vuelve a consultar el permiso después de terminar para inferir la causa. La política y el fallback CPU quedan intactos.

### Supplier asíncrono

El contador de envío exitoso ocurre después de que `supplyAsync` devuelve el future. Una excepción al enviar tiene contador propio. Se conserva y devuelve el mismo `CompletableFuture`; el observer no reemplaza su semántica. Los contadores separan supplier iniciado, completado normalmente tras cerrar el scope, finalizado con excepción, cancelado antes de empezar cuando se observa, y contexto restaurado cuando el cierre del scope termina correctamente.

Los fixtures verifican restauración después de una excepción del supplier, que un cierre fallido no se reporte como restauración, y que una cancelación posterior al inicio no se clasifique como cancelación pre-start.

### Atribución y costo

El diagnóstico usa cinco buckets fijos: `overworld`, `nether`, `end`, `modded` y `unknown`. No conserva `ServerLevel`, chunk, generador ni nombre del directorio del mundo. Por eso `world-normal` aparece bajo `scope=overworld`; otros guardados con la misma dimensión se agregan deliberadamente. Cualquier accessor ausente, inválido o que lanza queda como `unknown`.

Con `-Dsuperchunk.gpu.authorizationDiagnostics=false`, se omiten los incrementos, no se adjunta el observer de cancelación y el lookup de bucket no consulta el accessor del chunk. No hay logging por chunk ni nuevos hilos. El resumen existente imprime etiquetas `scope=` para cada bucket.

### Diferencias respecto de 2.3A

Este paquete reemplaza la interpretación anterior del contador genérico `density_batch_kernel_enqueued` con `density_batch_nd_range_enqueued` y separa los resultados en `gpu-batches`: densidad, clima y combinado. Añade clasificación explícita de fallos de autorización frente a backend, las razones detalladas de `enterTask`, estados del supplier/cancelación/restauración y atribución por bucket. Las regresiones focales cubren la ruta climática sin falso positivo de densidad, la clasificación del gate, los buckets, el submit/cancel del future, la excepción y la restauración.

### Evidencia y estado

`GpuAuthorizationDiagnosticsRegressionTest` aprobó la separación climate/density, el resultado combined, las categorías de rechazo, los buckets, las transiciones de envío/cancelación y la restauración ante excepciones. `WorldgenMixinContractTest` aprobó los puntos de inyección estáticos y comprobó que los sitios de compilación climáticos llevan el tag `CLIMATE`, el sitio de densidad en vivo lleva `DENSITY` y que el método de enqueue/readback utiliza el tag guardado.

Verificación final en Java 21: `gradlew check` **BUILD SUCCESSFUL**, 28 tareas (22 ejecutadas, 6 al día), incluidas 22 tareas de regresión JVM/offline y la tarea `test`. `git diff --check` terminó con código 0; mostró avisos de conversión CRLF en tres archivos, sin errores de whitespace. El resultado completo de Gradle y el estado Git están en el ZIP de revisión.

No se inició Minecraft, servidor, cliente, GameTest, generación de mundo, probe de calibración ni operación OpenCL real. La prueba ASM no demuestra que los handlers se hayan ejecutado en un servidor; `require=0` continúa siendo fail-open. No se puede atribuir retrospectivamente el log que registró 8.877 puts climáticos.

El archivo original del parche CBC de prioridad 900 no aparece en el diff; su contenido permanece igual al HEAD base.

**Estado 2.3A.1:** listo para revisión estática de GPT-6. NO-GO para claims de compatibilidad o rendimiento, despliegue, commit, push o release. No iniciar Ciclo 2.3B sin la revisión.

## Cierre focal de 2.3A.1 — Puertas diagnósticas y fallos parciales (2026-11-02)

- `GpuDfcDelegate.fill()` usa primero `GpuWorldgenPolicy.isAuthorized()` como puerta booleana. Solo ante rechazo, y con `superchunk.gpu.authorizationDiagnostics` activado, ejecuta la clasificación detallada. Una clasificación posterior que diga `AUTHORIZED` se reduce a `OTHER_ELIGIBILITY`; no cambia el resultado denegado.
- `enterTask()` conserva la validación de identidad y creación segura del permiso, pero quitó el chequeo diagnóstico adicional de `WORLD_SCOPES` en tareas aceptadas. La comprobación de `isAuthorized()` no toma ese monitor; la clasificación detallada (que sí puede consultarlo) queda en ramas de rechazo. Con los diagnósticos desactivados no se abre el scope de bucket ni se consulta el clasificador detallado.
- El dispatcher marca por separado el NDRange primario y el readback primario; la cadena climática también registra si llegó a encolar su readback. Una excepción después del NDRange pero antes del readback incrementa `*_BATCH_POST_DISPATCH_NO_READBACK_FAILURE`; no incrementa `*_READBACK_COMPLETED` ni `*_COMPLETION_FAILED`. Densidad, clima y combinación mantienen contadores separados.
- Regresiones focales: la decisión booleana permanece igual con diagnóstico habilitado/deshabilitado; el clasificador no se ejecuta tras aceptación ni cuando los diagnósticos están desactivados; una respuesta diagnóstica contradictoria queda como residual; los fallos sin readback no falsifican finalización/lectura fallida y conservan la etiqueta density/climate/combined.
- Runtime Minecraft, aplicación efectiva de mixins y despacho OpenCL siguen pendientes. No se ejecutaron.
