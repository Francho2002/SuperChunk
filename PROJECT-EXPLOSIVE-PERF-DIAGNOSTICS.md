# FastChunk: Project: Explosive performance diagnostics

## Scope and safety

This optional integration observes Project: Explosive 0.7.2-beta only. The audited artifact is `ProjectExplosive-0.7.2-beta.jar`, SHA-256 `3CBF5A76D8B6123A489DF54F0165C31DF6C5F6993DD07E9506232FCB3F3CEBB2`. No Project: Explosive source or binary is changed or redistributed.

The hooks are disabled by default. Enable them at JVM startup with:

```text
-Dsuperchunk.diagnostics.projectExplosive=true
```

When enabled, summaries are emitted only for scopes lasting at least 100 ms by default, with a maximum of 6 summaries per minute. These can be adjusted with `-Dsuperchunk.diagnostics.projectExplosive.minDurationMs=N` and `-Dsuperchunk.diagnostics.projectExplosive.maxLogsPerMinute=N` (the latter is capped at 60). A short operation therefore produces no summary unless the threshold is lowered.

The mixin plugin checks the raw resource SHA-256 for each target class before applying its optional Mixin. The expected hashes are:

| Target class | SHA-256 |
| --- | --- |
| `lil1llilol11.lo1i1iloioil.lo1iioil1oo1i0io01l1i1lo` | `40E16B01A05F1D5BFAB680F54A317C9301A0CFA3F1344DCDABB0A79816FFB90F` |
| `...lo1iioil1oo1i0io01l1i1lo$l0o001o0ol0ilooioooil11i` | `2FE68D08F9B327798445DB91B84195E8DD02863EB783EA1CBAC1D3B4E9CB39F0` |
| `lil1llilol11.lo1i1iloioil.l1o010il000l0o100li0oili` | `4212D88EF69E9AE1566AA1A9086091334F81FAB1EC5CED78B90D2EEDA2D8119E` |
| `...l1o010il000l0o100li0oili$ll1ol00o111oioool0l011lo` | `5282BE095320C4DE092ACF45968D2B3D4EE6EC0D5A7F2DA5A49C36604035D363` |

If Project: Explosive is absent, the target class resource is unavailable, or any class differs, the corresponding Mixin is skipped. Each injector uses `require=0`; an injector not found after an unexpected transform does not crash server startup. Static bytecode verification below establishes the point against the pinned JAR, but does not prove another runtime Mixin did not alter it.

## Verified bytecode points

The descriptors and calls below were inspected with `javap -p -s -c` from the pinned JAR and are also checked by `ProjectExplosiveMixinContractTest`.

| Scope/metric | Target and descriptor | Verified call or field |
| --- | --- | --- |
| Terrain scope and nominal budget | `lo1iioil1oo1i0io01l1i1lo.l11oo1ilili0l0lloii0oili(ServerLevel)V`, `(Lnet/minecraft/server/level/ServerLevel;)V` | Method starts its 18,000,000 ns deadline and loops while work remains and time is before that deadline. `WrapMethod` measures this invocation, including an individual call that runs beyond the check. |
| Terrain target | `ll0llol1li01lolio10011l0:D` | Serialized as `TerrainTarget` and used as a block-distance limit; the summary reports this as `radiusBlocks` and `ceil(radiusBlocks / 16)` as `radiusChunks`. Origin is the entity's `loll01loloioooo00l00lo11:BlockPos`, converted to chunk coordinates without loading a chunk. |
| Iteration count and latency | `ll1ol00o111oioool0l011lo(ServerLevel;IIIIII;TerrainChunkCache)I`; descriptor: `(Lnet/minecraft/server/level/ServerLevel;IIIIIIlil1llilol11/lo1i1iloioil/lo1iioil1oo1i0io01l1i1lo$l0o001o0ol0ilooioooil11i;)I` | The sweep loop calls this method once for each radial work item. The wrapper measures each entry/exit and computes its target chunk from the provided integer coordinates. |
| Ticket requests | Same terrain-work method | One `invokestatic` call to `l1iillil01o0l00i0ill0l0o.ll1ol00o111oioool0l011lo(ServerLevel;IIIIII)V` appears at bytecode offset 53. This counts requests made at that call site, not later ticket completion. |
| Nonblocking chunk lookup | `TerrainChunkCache.ll1ol00o111oioool0l011lo(ServerLevel;II)LevelChunk`, `(Lnet/minecraft/server/level/ServerLevel;II)Lnet/minecraft/world/level/chunk/LevelChunk;` | One `ServerChunkCache.getChunkNow(II)LevelChunk` invocation at bytecode offset 49. `null` is counted as a miss. This call is not described as a synchronous chunk load. |
| Terrain block changes | Direct call sites in the pinned terrain entity class | All direct `ServerLevel.setBlock(BlockPos,BlockState,int)Z` and `LevelChunk.setBlockState(BlockPos,BlockState,boolean)BlockState` call sites in that class are wrapped while a terrain scope is current. Boolean `true` or non-null old state is counted as a reported change. |
| Radial scope | `l1o010il000l0o100li0oili.ll1ol00o111oioool0l011lo(ServerLevel;DDDD)V`, `(Lnet/minecraft/server/level/ServerLevel;DDDD)V` | Independent wrapper around the radial helper. Its four double arguments are center X/Y/Z and radius in block coordinates; the loop ranges use `ceil(radius)`. Two direct `ServerLevel.setBlock` invocations occur at bytecode offsets 244 and 435. |
| Radial synchronous chunk access | `RadialChunkCache.ll1ol00o111oioool0l011lo(BlockPos)BlockState`, `(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;` | One `ServerLevel.getChunk(II)LevelChunk` invocation at bytecode offset 104. The wrapper times only this call, counts successful returns and exceptions, records the exception class (bounded to 80 characters), and records the requested chunk. |

The `TERRAIN_SWEEP` and `RADIAL_DESTRUCTION` scopes have separate monotonically increasing correlation IDs. If they nest on the same thread, the child summary includes its parent ID and the parent reports nested wall time. Scope state is thread-local, restored in `finally`, and contains only numeric IDs, timers, counters, coordinates, and enum values. It retains no world, entity, chunk, or block-entity references. Nesting is capped at eight live scopes; deeper scopes temporarily suppress attribution instead of assigning work to an unrelated parent.

## Summary fields

Each emitted record begins with `[FASTCHUNK-PE-PERF]` and includes `scope`, `id`, `parentId`, `outcome`, `scopeRestored`, `elapsedMs`, `cpuMs`, `maxOperation`, and the chunk for the maximum timed operation.

`TERRAIN_SWEEP` adds `iterations`, `iterationTotalMs`, `maxIterationMs`, `chunkNowCalls`, `chunkNowMisses`, `chunkNowFailures`, query total/max time, `ticketRequests`, `setBlockCalls`, `setBlockChanges`, mutation total/max time, `radiusBlocks`, `radiusChunks`, `targetChunk`, `budgetMs=18`, nested time, and `unattributedMs`.

`RADIAL_DESTRUCTION` reports its own duration, `syncGetChunkCalls`, `syncGetChunkReturned`, `syncGetChunkFailures`, their total and maximum latency and chunk, direct mutation count/results/timing, radius and target chunk, and `budgetMs=n/a`.

When a timing source is unavailable or no timed call occurred, the field is `n/a`, not `0`. CPU timing is read only when `ThreadMXBean` already supports and has enabled current-thread CPU time; this code never enables global JVM profiling. Invalid/negative elapsed values are excluded from sums and counted in `invalidDurations`.

`unattributedMs` is total scope wall time minus the directly timed chunk lookup, direct block mutation and nested-scope durations, clamped to zero to avoid negative rounding. Per-iteration time overlaps those leaf timers and is intentionally not subtracted a second time. The residual can include ordinary terrain calculations, unwrapped helper work, scheduler delays, GC pauses, or other activity; it does not identify a cause. High wall time with much lower CPU time is compatible with waiting, descheduling, or a JVM pause, but is not proof of which occurred.

The mutation counters cover the verified direct call sites in the PE classes named above, not every downstream world callback or mutation in Minecraft or other mods. They do not cause additional chunk reads, loads, tickets, per-block logs, or growing collections. While diagnostics are enabled, timed wrappers add `nanoTime()` around each instrumented operation; with the property off the mixins are not applied.

## Offline verification

The regression `ProjectExplosivePerfDiagnosticsRegressionTest` exercises OFF state, context close and exception restoration, nested scope separation, counters, sums/maxima, invalid/unavailable timing, depth bounds, rate limiting, and absence of game-object fields.

The static ASM verifier checks the pinned JAR digest, target-class hashes, the terrain loop-to-work call, ticket call, `getChunkNow`, both radial `setBlock` calls, radial synchronous `getChunk`, and the optional Mixin descriptors/config entries:

```powershell
.\gradlew.bat projectExplosiveMixinContractVerification --offline `
  -PprojectExplosiveJar="C:\Users\panch\OneDrive\Desktop\KxCraft\server\mods\ProjectExplosive-0.7.2-beta.jar"
```

Neither the ASM verifier nor `gradlew check` starts Minecraft, runs a GameTest, generates terrain, or performs OpenCL work. Successful offline checks establish only source/bytecode contracts. Runtime Mixin application and real measurements remain pending a separately authorized server session.
