# Adaptive Compute: implementation status and review notes

This branch is **incomplete and is not an experimental release candidate**. It starts at
`54dc5d3aa78c2348dfe1d38af1d03ff88caf953c`. No game or server was started for this work.

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
fallback. These options do not change batch settings or precision. There is no calibrated
profile, automatic tuning, or evidence of a speedup in this branch.

The defaults remain GPU off, FP64, batch limit 32, window 500 microseconds and depth 2.
The pre-existing compact-IDs mode defaults to `on` when GPU is enabled; its separate
`gpu.decideFp32` setting is true, and its comments describe an empirical envelope, not
universal bit-exact parity. Use `-Dsuperchunk.gpu.compactIds=off` for the existing strict
mode; this branch has not validated any terrain path on the target device.

## Generator-policy gap

No per-dimension generator classification or enforcement is implemented yet. In particular,
this branch must not be presented as proving Tellus or unknown generators safe. Relevant
base source inspected at `54dc5d3`:

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

## Standalone diagnostic and calibration gap

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

Real calibration still needs vanilla/world-relevant parity, stage-level timings, limits and
error accounting, interleaved repetitions and variability-aware selection. Versioned atomic
profile persistence and invalidation by device, driver, OpenCL, kernels and configuration
remain unimplemented. No results are saved as profiles or applied to normal operation.

## Regression and integration status

`gradlew build` passed on Microsoft OpenJDK 21.0.11 with the project's NeoForge 21.1.215
compile dependency, including the standalone regression tasks. Device fixtures cover the
model distinctions, ambiguous identity, precision requirement and empty-platform indexing;
they do not call a real OpenCL driver. The configuration documentation round-trip and device
fixtures passed again after the final configuration edit. The diagnostic above did use an
OpenCL device, but it is not a calibration. NeoForge 21.1.265 is the intended runtime, not a
version validated by this build.

The CBC `MixinChunkMapGetPlayers` priority 900 patch is unchanged relative to the base.
`MixinNoiseConfig` also has priority 900 independently. Existing
mixins and regression tests passed static/build checks; this is not an integrated game test.
No independent C2ME OpenCL, extra LWJGL runtime or other mod was introduced. The known
independent C2ME OpenCL/LWJGL module conflict remains a deployment constraint.

Pending: complete per-generator enforcement and dimension diagnostics; unavailable-device,
disabled-GPU and driver failure integration checks; real device capability and calibration
tests; profile corruption/invalidation tests; Tellus-absent and Tellus-present runtime tests;
vanilla and unknown generator parity; CBC startup regression; and a separately authorized
temporary-world comparison of terrain, biomes, roads, structures and caves. Do not deploy or
publish this branch on the basis of these fixture and build checks.

## Future CMI integration

The existing dispatcher and batcher can inform a later internal adapter for independent
trajectory, projectile, geometry or interception batches, but their worldgen-specific buffer
and kernel contracts are not a public API. CMI must own game state; send immutable snapshots,
validate returned results before applying them, retain a CPU route, and bound or cancel work
so the main tick never waits indefinitely. No CMI, Sable, Create or CBC code is changed here.
