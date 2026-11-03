# Project: Explosive nuclear terrain sweep scale

## Target and bytecode evidence

This optional feature targets only the direct PE entity registered as `projectexplosive:nuclear_bomb`, displayed by the pinned artifact as “Fat Man”. The registry maps that key to the base nuclear projectile class `lil1llilol11.lo1i1iloioil.lo1iioil1oo1i0io01l1i1lo`. `projectexplosive:icbm` is registered as a separate subclass and reports its own entity type; it is deliberately excluded, including its `nuclear` warhead variant. Small bombs, big bombs, fire bombs, and unrelated weapons are also excluded.

The inspected artifact is `ProjectExplosive-0.7.2-beta.jar`, SHA-256 `3CBF5A76D8B6123A489DF54F0165C31DF6C5F6993DD07E9506232FCB3F3CEBB2`. The target class SHA-256 is `40E16B01A05F1D5BFAB680F54A317C9301A0CFA3F1344DCDABB0A79816FFB90F`. The scale Mixin is applied only when that exact class resource is present and scaling is explicitly enabled. Its injection selector is the inspected method `l11oo1ilili0l0lloii0oili()V`; it wraps the unique `PUTFIELD ll0llol1li01lolio10011l0:D`, the saved `TerrainTarget` used by the incremental terrain ring sweep.

The static bytecode shows a separate crater path: detonation schedules crater carving and ejecta independently of `TerrainTarget`. The terrain sweep consults `TerrainTarget` while advancing radial rings, so reducing that stored target prevents positions outside the smaller radius from reaching the sweep's per-position work and ticket-request path. The existing `512` ticket budget, `39`-chunk ticket coverage and `18 ms` per-tick deadline are untouched.

## Preserved effects

The interception scales only the stored terrain target. It leaves the raw current wave-radius local unchanged and restores the previous unscaled radius on the field read used as the visual wave's starting radius. The existing visual-wave helper therefore still receives its original start/end distances. The crater scheduler remains a separate call from the detonation method; its radius input and crater/ejecta path are untouched. Mushroom-cloud state, particles, sprites, lighting, sounds, and other explosion methods are not targeted by this Mixin. Static contracts verify the crater call, terrain-target write, and visual-read call sites in the pinned class; actual in-game visual fidelity still requires runtime validation.

## Configuration

The feature is OFF by default. Set both JVM properties at startup to enable the target 50% distance:

```text
-Dsuperchunk.projectExplosive.nuclearTerrainScale.enabled=true
-Dsuperchunk.projectExplosive.nuclearTerrainScale.multiplier=0.5
```

The multiplier defaults to `0.5` but does nothing while the feature is off. Set it to `1.0` to restore the original target while the feature remains enabled, or disable the feature property. Accepted values are finite numbers greater than zero and at most `1.0`; malformed or out-of-range values fall back to `1.0` to preserve the original distance.

The target distance is multiplied, not the set of blocks randomly sampled. At the audited maximum target of 600 blocks, `0.5` yields a 300-block terrain-sweep limit (about 19 chunks instead of 38 by the existing ceiling conversion). The circular candidate footprint is approximately one quarter of the former footprint. This is a geometric expectation, not a performance measurement.

For in-flight bomb entities, the enabled feature writes the original unscaled `TerrainTarget` to the save tag with a small marker. The same feature scales that marked value once on reload. A startup with the feature OFF reads the original target normally, so disabling the setting restores the original remaining sweep distance instead of leaving an effective half-radius saved in the world. Unmarked legacy tags are left unchanged on load to avoid scaling an already-effective saved value twice.

## Diagnostics and limits

To log the original and effective terrain targets, enable the existing diagnostics property as well:

```text
-Dsuperchunk.diagnostics.projectExplosive=true
```

Terrain summaries include `outerSweepOriginalRadiusBlocks`, `outerSweepEffectiveRadiusBlocks`, and `outerSweepCandidateColumnsAvoidedEstimate`. The last value counts integer X/Z candidates outside the effective open disk according to the audited ring geometry; it is an estimate of candidate positions not visited, not a count of blocks actually changed or a TPS result. Logs still obey the diagnostics duration threshold and rate limit.

Offline JVM regressions cover opt-in defaults, the `0.5` and `1.0` multipliers, exact entity-key selection, safe invalid-property fallback, and the reported candidate estimate. The ASM contract verifies the pinned JAR hashes, the unique `TerrainTarget` write, the independent registered ICBM identity, and the unscaled radius read used for visual propagation. No Minecraft, server, GameTest, world generation, or OpenCL execution has been performed for this change. Mixin application, crater/animation fidelity, destructive radius in a live world, server load reduction, and runtime interaction with other PE mixins remain unverified.
