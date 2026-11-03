package dev.superchunk.gpu;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.YClampedGradientNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTNoiseNode;
import dev.superchunk.gpu.calibration.CalibrationDeviceIdentity;
import dev.superchunk.gpu.calibration.CalibrationMeasurements;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.Candidate;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.Scenario;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.Trial;
import dev.superchunk.gpu.calibration.CalibrationProbeSpec;
import dev.superchunk.gpu.calibration.CalibrationProfile;
import dev.superchunk.gpu.calibration.CalibrationProfileStore;
import dev.superchunk.gpu.calibration.CalibrationSelector;
import dev.superchunk.gpu.calibration.CalibrationTrialStore;
import dev.superchunk.gpu.dfc.GpuBatchDispatcher;
import dev.superchunk.gpu.dfc.GpuChunkBatcher;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Explicit synthetic offline calibration; it never changes or loads runtime configuration. */
public final class StandaloneBatchProbe {
    private StandaloneBatchProbe() { }

    private record OutputOptions(Path profile, Path trials) { }

    public static void main(String[] args) throws Exception {
        OutputOptions outputOptions = parseArguments(args);
        Path profileOutput = outputOptions.profile();
        CalibrationProbeSpec spec = CalibrationProbeSpec.current();
        if (!spec.isSupported()) throw new IllegalStateException("Unsupported synthetic calibration specification");

        // This is an isolated tool JVM configuration. It does not load server properties,
        // enable GPU in GpuConfig defaults, or write configuration files.
        System.setProperty("superchunk.gpu.compactIds", "off");
        System.setProperty("superchunk.gpu.keepDeadGrids", "true");
        System.setProperty("superchunk.gpu.colDedup", "false");
        System.setProperty("superchunk.gpu.colHoist", "true");
        System.setProperty("superchunk.gpu.completerPollMicros", "50");
        System.setProperty("superchunk.gpu.dedupFunctions", "true");
        System.setProperty("superchunk.gpu.bundleNoiseState", "false");
        System.setProperty("superchunk.gpu.cornerCse", "true");
        System.setProperty("superchunk.gpu.clOptDisable", "false");
        System.setProperty("superchunk.gpu.nvVerbose", "false");
        System.setProperty("superchunk.gpu.maxRegCount", "");
        System.setProperty("superchunk.gpu.noinlineHelpers", "true");
        System.setProperty("superchunk.gpu.localSize", "auto");
        System.setProperty("superchunk.gpu.nvOptLevel", "1");
        System.setProperty("superchunk.gpu.crDivide", "true");
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        Properties props = new Properties();
        props.setProperty("enabled", "true");
        props.setProperty("desiredFp", "fp64");
        props.setProperty("batchPipelineDepth", Integer.toString(spec.fixedPipelineDepth()));

        try {
            OpenCLBackend.init(GpuConfig.fromProperties(props));
            if (!OpenCLBackend.isAvailable() || OpenCLBackend.isFp32()) {
                throw new IllegalStateException("No FP64 OpenCL device; no calibration results");
            }
            CalibrationDeviceIdentity identity = CalibrationDeviceIdentity.from(OpenCLBackend.device());
            System.out.printf(Locale.ROOT,
                    "CALIBRATION identity=%s platform=%s [%s] device=%s [%s] vendorId=0x%04x driver=%s opencl=%s fp64=true depth=%d%n",
                    identity.identifiesRx9070Xt() ? "RX9070XT_EXACT_OPENCL_IDENTITY" : "GENERIC_MODEL_UNVERIFIED",
                    identity.platformName(), identity.platformVendor(), identity.deviceName(),
                    identity.deviceVendor(), identity.vendorId(), identity.driverVersion(),
                    identity.openclVersion(), spec.fixedPipelineDepth());

            RandomSource random = new XoroshiroRandomSource(spec.seed());
            NormalNoise noise = NormalNoise.create(random, -7, 1, 1, 1, 1, 1, 1, 1, 1);
            DensityFunction.NoiseHolder holder = new DensityFunction.NoiseHolder(Holder.direct(noise.parameters()), noise);
            List<AstNode> roots = List.of(new YClampedGradientNode(-64, 320, 1.5, -1.5),
                    new DFTNoiseNode(holder, 0.25, 0.25));
            long expectedBytes = (long) Collections.max(spec.candidates(),
                    java.util.Comparator.comparingInt(Candidate::batchLimit)).batchLimit()
                    * spec.dimX() * spec.dimY() * spec.dimZ() * roots.size() * Double.BYTES;
            OpenCLDevice device = OpenCLBackend.device();
            if (device.maxMemAllocBytes() < expectedBytes || device.globalMemBytes() < expectedBytes * 2) {
                throw new IllegalStateException("Device memory limits are unknown or too small for the synthetic workload");
            }

            long prepStart = System.nanoTime();
            double[][] expected = reference(spec, roots);
            long prepNs = System.nanoTime() - prepStart;
            System.out.printf(Locale.ROOT,
                    "CALIBRATION spec=%s workload=%s seed=%s chunks=%d candidates=%d warmup=1 repetitions=%d passes=%d preparationNs=%d%n",
                    spec.fingerprint(), spec.workloadId(), Long.toUnsignedString(spec.seed()), spec.chunkCount(),
                    spec.candidates().size(), spec.repetitionsPerPass(), spec.passes(), prepNs);
            System.out.println("CALIBRATION synthetic AST only; no Minecraft bootstrap, world or runtime profile application");

            List<Trial> trials = measure(spec, roots, expected, deadline);
            if (outputOptions.trials() != null) {
                CalibrationTrialStore.Archive archive = CalibrationTrialStore.create(identity, spec, trials);
                CalibrationTrialStore.save(outputOptions.trials(), archive);
                System.out.println("CALIBRATION raw trials saved=" + outputOptions.trials().toAbsolutePath()
                        + " measurement=synthetic worldgenEvidence=false trials=" + trials.size()
                        + " runId=" + archive.runId());
            } else {
                System.out.println("CALIBRATION no raw trial archive saved; pass --write-trials <path> to persist each repetition");
            }
            CalibrationSelector.Selection selection = CalibrationSelector.select(identity, spec, trials);
            printResults(selection, trials);
            Optional<CalibrationProfile> recommendation = selection.recommendation();
            if (recommendation.isPresent()) {
                CalibrationProfile profile = recommendation.get();
                System.out.printf(Locale.ROOT,
                        "CALIBRATION candidateOnly=true singleSyntheticRunNotConclusive=true activation=%s settings=%s bulkMedian=%.3f latencyMedianNs=%d latencyP95Ns=%d%n",
                        profile.activation(), profile.selected().candidate().key(),
                        profile.selected().bulk().medianRequestsPerSecond(),
                        profile.selected().latency().medianRequestLatencyNs(),
                        profile.selected().latency().p95RequestLatencyNs());
                if (profileOutput != null) {
                    CalibrationProfileStore.saveCandidate(profileOutput, profile);
                    System.out.println("CALIBRATION candidate profile saved=" + profileOutput.toAbsolutePath());
                } else {
                    System.out.println("CALIBRATION no profile saved; pass --write-profile <path> to persist this candidate");
                }
            } else {
                System.out.println("CALIBRATION no recommendation; reason=" + selection.rejectionReason());
                if (profileOutput != null) {
                    throw new IllegalStateException("Requested profile output but no candidate passed validation");
                }
            }
        } finally {
            OpenCLBackend.shutdown();
        }
    }

    private static OutputOptions parseArguments(String[] args) {
        if (args.length < 1 || !args[0].equals("--run")) {
            throw new IllegalArgumentException("Pass --run to explicitly run the bounded synthetic OpenCL calibration");
        }
        Path profile = null;
        Path trials = null;
        for (int i = 1; i < args.length; i += 2) {
            if (i + 1 >= args.length || args[i + 1].isBlank()) {
                throw new IllegalArgumentException("Each output flag requires a non-empty path");
            }
            switch (args[i]) {
                case "--write-profile" -> {
                    if (profile != null) throw new IllegalArgumentException("Duplicate --write-profile flag");
                    profile = Path.of(args[i + 1]);
                }
                case "--write-trials" -> {
                    if (trials != null) throw new IllegalArgumentException("Duplicate --write-trials flag");
                    trials = Path.of(args[i + 1]);
                }
                default -> throw new IllegalArgumentException("Unknown output flag: " + args[i]);
            }
        }
        return new OutputOptions(profile, trials);
    }

    private static List<Trial> measure(CalibrationProbeSpec spec, List<AstNode> roots,
                                       double[][] expected, long deadline) throws Exception {
        List<Candidate> candidates = spec.candidates();
        List<Trial> trials = new ArrayList<>(candidates.size() * spec.passes()
                * spec.repetitionsPerPass() * Scenario.values().length);
        for (int pass = 0; pass < spec.passes(); pass++) {
            List<Candidate> order = new ArrayList<>(candidates);
            if ((pass & 1) == 1) Collections.reverse(order);
            for (int candidateIndex = 0; candidateIndex < order.size(); candidateIndex++) {
                Candidate candidate = order.get(candidateIndex);
                measureCandidatePass(spec, roots, expected, candidate, pass, candidateIndex, deadline, trials);
            }
        }
        return List.copyOf(trials);
    }

    private static void measureCandidatePass(CalibrationProbeSpec spec, List<AstNode> roots,
                                             double[][] expected, Candidate candidate, int pass,
                                             int candidateIndex, long deadline, List<Trial> trials) throws Exception {
        remaining(deadline);
        GpuBatchDispatcher dispatcher = GpuBatchDispatcher.compile(roots);
        if (dispatcher == null) throw new IllegalStateException("Dispatcher compilation failed for " + candidate.key());
        if (dispatcher.pipelineDepth() != spec.fixedPipelineDepth()) {
            dispatcher.close();
            throw new IllegalStateException("Dispatcher depth drifted from fixed profile depth 2");
        }
        GpuChunkBatcher batcher = GpuChunkBatcher.init(dispatcher, candidate.batchLimit(), candidate.batchWindowMicros());
        if (batcher == null) {
            dispatcher.close();
            throw new IllegalStateException("Batcher initialization failed for " + candidate.key());
        }
        try {
            boolean bulkFirst = ((pass + candidateIndex) & 1) == 1;
            Scenario first = bulkFirst ? Scenario.BULK : Scenario.LATENCY;
            Scenario second = bulkFirst ? Scenario.LATENCY : Scenario.BULK;
            for (Scenario scenario : List.of(first, second)) {
                Trial warmup = runOnce(spec, candidate, batcher, expected, scenario, deadline);
                if (!warmup.exactParity()) {
                    trials.add(warmup);
                    throw new IllegalStateException("Warm-up exact FP64 parity failed for " + candidate.key());
                }
                for (int repetition = 0; repetition < spec.repetitionsPerPass(); repetition++) {
                    Trial trial = runOnce(spec, candidate, batcher, expected, scenario, deadline);
                    trials.add(trial);
                    System.out.printf(Locale.ROOT,
                            "CALIBRATION pass=%d candidate=%s scenario=%s repetition=%d requests=%d elapsedNs=%d medianRequestNs=%d p95RequestNs=%d parity=%s depth=%d%n",
                            pass + 1, candidate.key(), scenario, repetition + 1, trial.requestLatenciesNs().length,
                            trial.elapsedNs(), CalibrationMeasurements.median(trial.requestLatenciesNs()),
                            CalibrationMeasurements.percentile95(trial.requestLatenciesNs()),
                            trial.exactParity() ? "exact" : "FAILED", spec.fixedPipelineDepth());
                }
            }
        } finally {
            batcher.shutdown();
        }
    }

    private static double[][] reference(CalibrationProbeSpec spec, List<AstNode> roots) {
        double[][] result = new double[spec.chunkCount()][roots.size() * pointCount(spec)];
        for (int c = 0; c < spec.chunkCount(); c++) {
            int ox = 96 + (c % 4) * 16, oz = -240 + (c / 4) * 16;
            for (int y = 0; y < spec.dimY(); y++) for (int x = 0; x < spec.dimX(); x++)
                for (int z = 0; z < spec.dimZ(); z++) {
                    int index = (y * spec.dimX() + x) * spec.dimZ() + z;
                    for (int k = 0; k < roots.size(); k++) {
                        result[c][k * pointCount(spec) + index] = roots.get(k).evalSingle(
                                ox + x * spec.strideX(), -64 + y * spec.strideY(),
                                oz + z * spec.strideZ(), EvalType.NORMAL);
                    }
                }
        }
        return result;
    }

    private static Trial runOnce(CalibrationProbeSpec spec, Candidate candidate, GpuChunkBatcher batcher,
                                 double[][] expected, Scenario scenario, long deadline) throws Exception {
        @SuppressWarnings("unchecked") CompletableFuture<double[]>[] jobs = new CompletableFuture[spec.chunkCount()];
        @SuppressWarnings("unchecked") CompletableFuture<double[]>[] submitted = new CompletableFuture[spec.chunkCount()];
        long[] starts = new long[spec.chunkCount()], latencies = new long[spec.chunkCount()];
        long start = System.nanoTime();
        try {
            for (int c = 0; c < spec.chunkCount(); c++) {
                int i = c;
                remaining(deadline);
                starts[c] = System.nanoTime();
                int originX = 96 + (c % 4) * 16, originZ = -240 + (c / 4) * 16;
                CompletableFuture<double[]> future = batcher.submit(originX, -64, originZ,
                        spec.strideX(), spec.strideY(), spec.strideZ(),
                        spec.dimX(), spec.dimY(), spec.dimZ());
                if (future == null) throw new IllegalStateException("Batch submission rejected");
                submitted[c] = future;
                jobs[c] = future.whenComplete((ignored, error) -> latencies[i] = System.nanoTime() - starts[i]);
                if (scenario == Scenario.LATENCY) jobs[c].get(remaining(deadline), TimeUnit.NANOSECONDS);
            }
            double[][] outputs = new double[spec.chunkCount()][];
            for (int c = 0; c < spec.chunkCount(); c++) outputs[c] = jobs[c].get(remaining(deadline), TimeUnit.NANOSECONDS);
            long elapsed = System.nanoTime() - start;
            boolean exact = true;
            int expectedLength = expected[0].length;
            for (int c = 0; c < spec.chunkCount(); c++) {
                double[] actual = outputs[c];
                if (actual == null || actual.length != expectedLength) {
                    exact = false;
                    continue;
                }
                for (int i = 0; i < actual.length; i++) {
                    if (Double.doubleToRawLongBits(actual[i]) != Double.doubleToRawLongBits(expected[c][i])) {
                        exact = false;
                        break;
                    }
                }
            }
            return new Trial(scenario, candidate, elapsed, latencies, exact);
        } finally {
            for (CompletableFuture<double[]> future : submitted) {
                if (future != null && !future.isDone()) future.cancel(false);
            }
        }
    }

    private static void printResults(CalibrationSelector.Selection selection, List<Trial> trials) {
        selection.results().forEach((candidate, result) -> System.out.printf(Locale.ROOT,
                "CALIBRATION summary candidate=%s depth=%d bulkMedianReqPerSec=%.3f bulkThroughputMad=%.3f latencyMedianNs=%d latencyP95Ns=%d latencyTrialMedianMadNs=%d samples=%d/%d trials=%d/%d parity=%s%n",
                candidate.key(), candidate.pipelineDepth(), result.bulk().medianRequestsPerSecond(),
                CalibrationMeasurements.dispersion(trials, Scenario.BULK, candidate)
                        .throughputMedianAbsoluteDeviationRequestsPerSecond(),
                result.latency().medianRequestLatencyNs(), result.latency().p95RequestLatencyNs(),
                CalibrationMeasurements.dispersion(trials, Scenario.LATENCY, candidate)
                        .trialLatencyMedianAbsoluteDeviationNs(),
                result.latency().requestSamples(), result.bulk().requestSamples(), result.latency().trialCount(),
                result.bulk().trialCount(), result.exactParity() ? "exact" : "FAILED"));
    }

    private static int pointCount(CalibrationProbeSpec spec) {
        return Math.multiplyExact(Math.multiplyExact(spec.dimX(), spec.dimY()), spec.dimZ());
    }

    static long remaining(long deadline) throws java.util.concurrent.TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new java.util.concurrent.TimeoutException("Global calibration deadline exceeded");
        return nanos;
    }
}
