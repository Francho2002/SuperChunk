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
fallback. These options do not change batch settings or precision. There is no benchmark,
profile, calibration, or evidence of a speedup in this branch.

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

## Calibration work still required

`GpuVanillaParityTest.checkBatchEmit` already builds representative density ASTs and uses
`GpuBatchDispatcher` with a 5x49x5 grid. It checks batched GPU against per-chunk GPU,
not vanilla, and is a useful starting point rather than a completed calibration harness.
A standalone, explicitly invoked harness still needs controlled warmups/repetitions,
separate bulk and sparse workloads, CPU/vanilla parity, timing of preparation, upload,
kernel, readback and wait, bounded memory, failure handling and variability-aware choice.
Pipeline depth and batching window must be measured through the real batcher; kernel-only
timing cannot establish their benefit. Versioned atomic persistence and invalidation by
device, driver, OpenCL version, kernel revision and relevant configuration remain to be
implemented. No configuration should be applied until its parity and measurements pass.

## Regression and integration status

`gradlew build` passed on Microsoft OpenJDK 21.0.11 with the project's NeoForge 21.1.215
compile dependency, including the standalone regression tasks. Device fixtures cover the
model distinctions, ambiguous identity, precision requirement and empty-platform indexing;
they do not call a real OpenCL driver. The configuration documentation round-trip and device
fixtures passed again after the final configuration edit. No hardware benchmark ran. NeoForge
21.1.265 is the intended runtime, not a version validated by this build.

The CBC `MixinNoiseConfig` priority 900 patch is unchanged relative to the base. Existing
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
