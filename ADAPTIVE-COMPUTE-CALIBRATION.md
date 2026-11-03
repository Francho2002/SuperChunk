# Adaptive compute calibration (Cycle 2.2)

## Scope and safety

`StandaloneBatchProbe` is an explicitly invoked, synthetic diagnostic. It builds a small fixed density-function AST, computes a CPU reference, and measures batched FP64 OpenCL dispatch. It does not start Minecraft, create a world, inspect a generator, grant a worldgen permit, or read/write the normal SuperChunk configuration.

The probe creates an in-memory `GpuConfig` with GPU enabled only inside its own diagnostic JVM. `GpuConfig` defaults remain unchanged (`enabled=false`, FP64, batch limit 32, window 500 microseconds, pipeline depth 2). This is not a recommendation to enable the server GPU path.

Calibration can recommend only `batchLimit` and `batchWindowMicros`. Dispatcher pipeline depth is fixed at 2 for every run and is included in the workload fingerprint, result output, and persisted candidate. The diagnostic never loads or applies a saved profile. A profile is an offline candidate that requires later manual review; no runtime profile loader or activation hook is part of Cycle 2.2.

## Synthetic workload and measurements

Workload schema v1 uses the fixed synthetic noise AST, seed `0x5C0FFEE`, 16 chunk requests, a `5 x 49 x 5` lattice, and strides `4 x 8 x 4`. Each request evaluates the same two roots. Every returned FP64 sample is compared to the CPU AST result using raw `double` bits. A failed warm-up stops the run; any failed measured parity, missing sample, timeout, rejected request, or incomplete candidate matrix prevents a recommendation.

The candidate matrix is `8:50`, `16:100`, `32:100`, `32:500` (baseline), `64:500`, and `32:1000`, with the final tuple field implicitly fixed at depth 2. Other benchmark-affecting compiler/dispatch flags are explicitly pinned by the probe (`colDedup=false`, `colHoist=true`, fixed completion polling, function deduplication on, noise-state bundling off, corner CSE on, optimized OpenCL build, helper no-inline on, automatic local size, and NVIDIA optimization level 1); these settings are included in the probe fingerprint. The probe runs two passes: candidates in forward order, then reverse order. Scenario order alternates across candidate/pass pairs. Each pass and scenario has one parity-checked warm-up and three measured repetitions. That produces six measured trials and 96 request-latency samples for each candidate in both LATENCY and BULK scenarios. Warm-ups are excluded from aggregates.

The selector creates a candidate recommendation only when the complete workload and baseline have exact parity and every candidate has all expected samples. A non-baseline candidate must meet all three conditions against the `32/500/depth-2` baseline:

- Median BULK throughput improves by at least 5%.
- Median LATENCY request latency regresses by no more than 2%.
- P95 LATENCY request latency regresses by no more than 5%.

The reported P95 is the nearest-rank 95th percentile over 96 requests. If no candidate passes, no profile is written. Candidate selection is deterministic: among passing candidates, the greatest median BULK throughput wins; ties keep the earlier matrix entry.

## OpenCL identity and hardware labels

A persistable profile is tied to OpenCL-reported platform name/vendor and device name/vendor/vendor ID, platform/device enumeration indices, driver and OpenCL versions, extension-string digest, FP capability fields, and the workload fingerprint. Unknown or absent identity/capability fields fail closed for profile persistence. Process-local OpenCL handles are excluded.

`RX9070XT_EXACT_OPENCL_IDENTITY` is emitted only when the existing conservative OpenCL vendor and exact commercial device-name predicate identifies an RX 9070 XT. Architecture strings such as `gfx1201` do not establish the commercial model and remain `GENERIC_MODEL_UNVERIFIED`. A generic but complete OpenCL identity may still have an offline candidate profile, clearly labelled as an unverified model.

## Recorded synthetic probe run — 2026-11-02

One standalone run reported platform `AMD Accelerated Parallel Processing` (platform vendor `Advanced Micro Devices, Inc.`), device `gfx1201`, driver `3661.0 (PAL,LC)`, and FP64. The device is reported as a generic, unverified model; `gfx1201` is not evidence that it is an RX 9070 XT. The probe specification fingerprint was `a8f489e36ffb3af675f5b2c1105df24061d50fb7fc3b3588143bd99eebc631cc`.

The six candidates each reported six LATENCY trials and six BULK trials, with 96 samples per scenario and exact parity. This is the summary emitted by that run:

| Candidate (limit:window:depth) | BULK median requests/s | LATENCY median (ns) | LATENCY P95 (ns) | LATENCY/BULK samples | LATENCY/BULK trials | Parity |
|---|---:|---:|---:|---:|---:|---|
| `16:100:2` | 1053.659 | 31210500 | 32019300 | 96/96 | 6/6 | exact |
| `8:50:2` | 521.144 | 15986900 | 31973400 | 96/96 | 6/6 | exact |
| `32:100:2` | 1046.395 | 30995650 | 32028900 | 96/96 | 6/6 | exact |
| `64:500:2` | 516.476 | 31125400 | 32033400 | 96/96 | 6/6 | exact |
| `32:1000:2` | 521.340 | 31132450 | 31995500 | 96/96 | 6/6 | exact |
| `32:500:2` (baseline) | 514.645 | 31185150 | 32062100 | 96/96 | 6/6 | exact |

The selector reported `16:100:2` as a **candidate-only** recommendation. No profile file was written and no runtime setting was activated. This was one synthetic run, not a repeated hardware study. It provides no evidence of performance in world generation or under server load. That historical run did not persist individual request latencies, so its P50/P95 distribution cannot be independently reconstructed from the saved logs.

## Optional raw trial archive and repeat dispersion

The explicit probe accepts `--write-trials <path>` in addition to the existing `--write-profile <path>`. When requested, it writes an independent checksummed `calibration-trials` properties artifact containing every measured repetition, its candidate, scenario, elapsed time, exact-parity result, and all per-request latency samples. Warm-up trials are excluded. Loading is an explicit offline-review operation that requires the matching OpenCL identity and current workload fingerprint; it does not run during server startup. A successful probe run contains 72 measured records (six candidates x two scenarios x six repetitions), each with 16 request samples.

The archive identifies the data as `measurement.kind=synthetic` and sets `measurement.worldgenPerformanceEvidence=false`. It also stores, for each candidate and scenario, medians, P95, trial/sample counts, median absolute deviation of per-trial throughput, and median absolute deviation of per-trial latency medians. These summaries are re-derived from the retained raw trial records during load and must match exactly. A single run remains a candidate-quality diagnostic only; repeat dispersion is reported to expose variability, not to claim statistically conclusive improvement. The artifact does not contain world-generation timing data, and this cycle does not implement or enable worldgen benchmarking.

## Candidate profile format

Profiles are Java properties with schema version 1, `profile.kind=candidate-recommendation`, `profile.status=candidate-only`, `profile.activation=manual-review-required`, exact hardware/workload identity, baseline and selected candidate settings, trial/sample counts, parity flags, median throughput, latency median/P95, and an integrity SHA-256. The file uses atomic replacement when the filesystem supports it, with a standard replace fallback otherwise; writing occurs only when `--write-profile <path>` is explicitly supplied and a candidate passes all validation. The explicit offline reader rejects checksum damage, unsupported schema/settings, incomplete metadata, changed device/driver identity, changed workload fingerprint, non-fixed depth, or failed thresholds. This reader is not called by server startup or configuration code.

The `StandaloneBatchProbe` entry point accepts:

```text
--run --write-trials build/standalone-gpu-probe/trials.properties
--run --write-profile build/standalone-gpu-probe/candidate.properties --write-trials build/standalone-gpu-probe/trials.properties
```

Without `--write-profile`, the probe does not persist a candidate profile. Without `--write-trials`, it does not persist the raw repetitions. Either output is opt-in and separate; writing raw trials does not apply or create a runtime configuration. The OpenCL hardware probe is a manual diagnostic task and is not attached to `check`.

## Regression fixtures and limits

`CalibrationProfileRegressionTest` is a hardware-free assertion program for exact identity classification, no `gfx1201` inference, fixed-depth fingerprinting, threshold decisions, exact parity, full repeated sample requirements, candidate-only profile round trips, checksum rejection, and device/workload mismatch rejection. It is intended to run as a standalone `JavaExec` verification task and must not initialize OpenCL or Minecraft.

Maintenance rule: changes to the probe AST/workload, selector criteria, dispatcher/batcher behavior, or kernels used by the probe require an explicit calibration schema/workload version update and a corresponding fingerprint change. The fingerprint records declared settings and fixture parameters; it does not detect source-code changes automatically.

These fixtures validate the offline selector and profile contract. They do not establish RX 9070 XT performance, real-world throughput, stability under server load, FP64 parity for arbitrary terrain ASTs, worldgen compatibility, or permission for any generator to use GPU execution. Those require separately approved hardware and integration work. Runtime profile loading/application remains out of scope.
