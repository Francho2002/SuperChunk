package dev.superchunk.gpu.calibration;

import dev.superchunk.gpu.OpenCLDevice;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.Candidate;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.Scenario;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.Trial;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.TrialDispersion;
import dev.superchunk.gpu.calibration.CalibrationTrialStore.Archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Hardware-free regression fixtures; invoked explicitly by the isolated Gradle task. */
public final class CalibrationProfileRegressionTest {
    private CalibrationProfileRegressionTest() { }

    public static void main(String[] args) throws Exception {
        testExactIdentityAndNoArchitectureInference();
        testConservativeSelectionAndDepthFingerprint();
        testThresholdAndParityRejections();
        testSampleCompleteness();
        testProfileRoundTripAndMismatchRejection();
        testRawTrialArchiveReconstructionAndProvenance();
        System.out.println("CalibrationProfileRegressionTest: all assertions passed");
    }

    private static void testExactIdentityAndNoArchitectureInference() {
        CalibrationDeviceIdentity gfx1201 = CalibrationDeviceIdentity.from(device(
                "AMD Radeon Graphics", "Advanced Micro Devices, Inc.", "gfx1201", "OpenCL 3.0 AMD", "AMD Platform"));
        check(!gfx1201.identifiesRx9070Xt(), "gfx1201 must remain an unverified generic model");
        CalibrationDeviceIdentity exactRx = CalibrationDeviceIdentity.from(device(
                "AMD Radeon RX 9070 XT", "Advanced Micro Devices, Inc.", "gfx1201", "OpenCL 3.0 AMD", "AMD Platform"));
        check(exactRx.identifiesRx9070Xt(), "Exact OpenCL commercial model identity should be recognized");
        expectIllegal(() -> CalibrationDeviceIdentity.from(device("AMD Radeon RX 9070 XT",
                "Advanced Micro Devices, Inc.", "gfx1201", "OpenCL 3.0 AMD", "?")),
                "Unknown platform vendor must prevent persistence identity");
    }

    private static void testConservativeSelectionAndDepthFingerprint() {
        CalibrationProbeSpec spec = CalibrationProbeSpec.current();
        check(spec.isSupported(), "Current probe specification should be supported");
        check(spec.fixedPipelineDepth() == 2, "Depth must remain fixed at 2");
        check(spec.candidates().stream().allMatch(c -> c.pipelineDepth() == 2), "Every candidate must use depth 2");
        check(spec.fingerprint().equals(copyWithDepth(spec, 3).fingerprint()) == false,
                "Pipeline depth must affect the profile fingerprint");

        CalibrationSelector.Selection selection = CalibrationSelector.select(identity(), spec,
                trials(spec, 0, false, false));
        check(selection.recommendation().isPresent(), "A candidate meeting all thresholds should be recommended");
        CalibrationProfile profile = selection.recommendation().orElseThrow();
        check(profile.selected().candidate().equals(spec.candidates().get(0)), "Expected the fastest qualifying candidate");
        check(profile.status().equals("candidate-only"), "Profile must remain candidate-only");
        check(profile.activation().equals("manual-review-required"), "Profile must require later manual review");
        check(profile.selected().candidate().pipelineDepth() == 2, "Persisted depth must be 2");
    }

    private static void testThresholdAndParityRejections() {
        CalibrationProbeSpec spec = CalibrationProbeSpec.current();
        check(CalibrationSelector.select(identity(), spec, trials(spec, 1, false, false))
                        .recommendation().isEmpty(), "A sub-5% bulk gain must not be recommended");
        check(CalibrationSelector.select(identity(), spec, trials(spec, 0, true, false))
                        .recommendation().isEmpty(), "Latency median above +2% must be rejected");
        List<Trial> p95Failure = new ArrayList<>(trials(spec, 0, false, false));
        int outliers = 0;
        for (int i = 0; i < p95Failure.size() && outliers < 2; i++) {
            Trial trial = p95Failure.get(i);
            if (trial.scenario() != Scenario.LATENCY || !trial.candidate().equals(spec.candidates().get(0))) continue;
            long[] samples = trial.requestLatenciesNs();
            samples[0] = 1_060;
            samples[1] = 1_060;
            samples[2] = 1_060;
            p95Failure.set(i, new Trial(trial.scenario(), trial.candidate(), trial.elapsedNs(), samples, true));
            outliers++;
        }
        check(CalibrationSelector.select(identity(), spec, p95Failure).recommendation().isEmpty(),
                "Latency p95 above +5% must be rejected");
        check(CalibrationSelector.select(identity(), spec, trials(spec, 0, false, true))
                        .recommendation().isEmpty(), "Any failed exact-parity trial must reject all recommendations");
        List<Trial> badDepth = new ArrayList<>(trials(spec, 0, false, false));
        Trial original = badDepth.get(0);
        badDepth.set(0, new Trial(original.scenario(), new Candidate(original.candidate().batchLimit(),
                original.candidate().batchWindowMicros(), 3), original.elapsedNs(),
                original.requestLatenciesNs(), true));
        check(CalibrationSelector.select(identity(), spec, badDepth).recommendation().isEmpty(),
                "Non-fixed dispatcher depth must not be accepted");
    }

    private static void testSampleCompleteness() {
        CalibrationProbeSpec spec = CalibrationProbeSpec.current();
        List<Trial> incomplete = new ArrayList<>(trials(spec, 0, false, false));
        incomplete.remove(incomplete.size() - 1);
        check(CalibrationSelector.select(identity(), spec, incomplete).recommendation().isEmpty(),
                "Every candidate/scenario requires all six trials and 96 request samples");
        List<Trial> sparse = new ArrayList<>(trials(spec, 0, false, false));
        Trial first = sparse.get(0);
        sparse.set(0, new Trial(first.scenario(), first.candidate(), first.elapsedNs(),
                new long[15], first.exactParity()));
        check(CalibrationSelector.select(identity(), spec, sparse).recommendation().isEmpty(),
                "Insufficient request samples must reject recommendations");
    }

    private static void testProfileRoundTripAndMismatchRejection() throws Exception {
        CalibrationProbeSpec spec = CalibrationProbeSpec.current();
        CalibrationProfile profile = CalibrationSelector.select(identity(), spec, trials(spec, 0, false, false))
                .recommendation().orElseThrow();
        Path folder = Files.createTempDirectory("superchunk-calibration-test-");
        Path file = folder.resolve("candidate.properties");
        try {
            CalibrationProfileStore.saveCandidate(file, profile);
            CalibrationProfile loaded = CalibrationProfileStore.loadForOfflineReview(file, identity(), spec);
            check(loaded.equals(profile), "Valid candidate profile must round-trip");
            expectIo(() -> CalibrationProfileStore.loadForOfflineReview(file,
                    CalibrationDeviceIdentity.from(device("Other OpenCL GPU", "Different Vendor", "other",
                            "Other Driver", "Other Platform")), spec), "Device mismatch must be rejected");
            expectIo(() -> CalibrationProfileStore.loadForOfflineReview(file, identity(),
                    new CalibrationProbeSpec(CalibrationProbeSpec.CURRENT_VERSION, "different-workload", 7,
                            16, 5, 49, 5, 4, 8, 4, 2, 3, 1, 2, spec.candidateMatrix(),
                            spec.parityMethod(), spec.selectorVersion(), spec.executionSettings())),
                    "Probe/workload mismatch must be rejected");
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            Files.writeString(file, text.replace("profile.status=candidate-only", "profile.status=active"),
                    StandardCharsets.ISO_8859_1);
            expectIo(() -> CalibrationProfileStore.loadForOfflineReview(file, identity(), spec),
                    "Edited profile contents must fail checksum validation");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(folder);
        }
    }

    private static void testRawTrialArchiveReconstructionAndProvenance() throws Exception {
        CalibrationProbeSpec spec = CalibrationProbeSpec.current();
        Candidate candidate = spec.candidates().get(0);
        List<Trial> source = new ArrayList<>(trials(spec, 0, false, false));
        int latencyRepetition = 0;
        int bulkRepetition = 0;
        for (int i = 0; i < source.size(); i++) {
            Trial trial = source.get(i);
            if (!trial.candidate().equals(candidate)) continue;
            if (trial.scenario() == Scenario.BULK) {
                source.set(i, new Trial(trial.scenario(), trial.candidate(),
                        100_000_000L + bulkRepetition++ * 25_000_000L,
                        trial.requestLatenciesNs(), trial.exactParity()));
                continue;
            }
            if (trial.scenario() != Scenario.LATENCY) continue;
            long[] samples = trial.requestLatenciesNs();
            java.util.Arrays.fill(samples, 900L + latencyRepetition * 40L);
            source.set(i, new Trial(trial.scenario(), trial.candidate(), trial.elapsedNs(), samples,
                    trial.exactParity()));
            latencyRepetition++;
        }
        check(latencyRepetition == spec.passes() * spec.repetitionsPerPass(),
                "Fixture must contain every latency repetition for the selected candidate");

        Archive archive = CalibrationTrialStore.create(identity(), spec, source);
        Path folder = Files.createTempDirectory("superchunk-trials-test-");
        Path file = folder.resolve("raw-trials.properties");
        try {
            CalibrationTrialStore.save(file, archive);
            Archive loaded = CalibrationTrialStore.loadForOfflineReview(file, identity(), spec);
            check(loaded.trials().size() == source.size(), "Every measured trial must round-trip");
            check(loaded.measurementKind().equals("synthetic") && !loaded.worldgenPerformanceEvidence(),
                    "Archive must distinguish synthetic probe data from worldgen performance");
            for (int i = 0; i < source.size(); i++) {
                Trial before = source.get(i), after = loaded.trials().get(i);
                check(before.scenario() == after.scenario() && before.candidate().equals(after.candidate())
                                && before.elapsedNs() == after.elapsedNs()
                                && before.exactParity() == after.exactParity()
                                && java.util.Arrays.equals(before.requestLatenciesNs(), after.requestLatenciesNs()),
                        "Raw trial and request latencies must be retained at index " + i);
            }
            CalibrationMeasurements.ScenarioStats before = CalibrationMeasurements.aggregate(source,
                    Scenario.LATENCY, candidate);
            CalibrationMeasurements.ScenarioStats after = loaded.aggregate(candidate, Scenario.LATENCY);
            check(before.medianRequestLatencyNs() == after.medianRequestLatencyNs()
                            && before.p95RequestLatencyNs() == after.p95RequestLatencyNs()
                            && before.medianRequestsPerSecond() == after.medianRequestsPerSecond(),
                    "Median and P95 must be reconstructible from persisted request samples");
            TrialDispersion dispersion = loaded.dispersion(candidate, Scenario.LATENCY);
            check(dispersion.trialCount() == latencyRepetition
                            && dispersion.trialLatencyMedianAbsoluteDeviationNs() == 60,
                    "Between-repetition latency dispersion must be reconstructible from raw trials");
            TrialDispersion throughputDispersion = loaded.dispersion(candidate, Scenario.BULK);
            check(throughputDispersion.trialCount() == bulkRepetition
                            && throughputDispersion.throughputMedianAbsoluteDeviationRequestsPerSecond() > 0,
                    "Between-repetition throughput dispersion must be reconstructed from elapsed trial values");
            expectIo(() -> CalibrationTrialStore.loadForOfflineReview(file,
                    CalibrationDeviceIdentity.from(device("Other OpenCL GPU", "Different Vendor", "other",
                            "Other Driver", "Other Platform")), spec),
                    "Trial archive must reject a different OpenCL identity");
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            Files.writeString(file, text.replace("measurement.kind=synthetic", "measurement.kind=worldgen"),
                    StandardCharsets.ISO_8859_1);
            expectIo(() -> CalibrationTrialStore.loadForOfflineReview(file, identity(), spec),
                    "Edited measurement provenance must fail checksum/validation");
            Archive falselyLabelled = new Archive(archive.schemaVersion(), archive.runId(), "synthetic", true,
                    archive.device(), archive.probeVersion(), archive.workloadId(), archive.probeFingerprint(),
                    archive.seed(), archive.createdAtUtc(), archive.trials());
            expectIo(() -> CalibrationTrialStore.save(file, falselyLabelled),
                    "Synthetic trial persistence must never claim worldgen evidence");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(folder);
        }
    }

    private static List<Trial> trials(CalibrationProbeSpec spec, int mode,
                                      boolean latencyMedianFails, boolean parityFails) {
        List<Trial> all = new ArrayList<>();
        int index = 0;
        for (Candidate candidate : spec.candidates()) {
            for (Scenario scenario : Scenario.values()) {
                for (int repetition = 0; repetition < spec.passes() * spec.repetitionsPerPass(); repetition++) {
                    long[] requestLatencies = new long[spec.chunkCount()];
                    long latency = 1_000;
                    if (scenario == Scenario.LATENCY && latencyMedianFails && candidate.equals(spec.candidates().get(0))) {
                        latency = 1_021;
                    }
                    java.util.Arrays.fill(requestLatencies, latency);
                    long elapsed = scenario == Scenario.BULK ? 160_000_000L : 160_000_000L;
                    if (scenario == Scenario.BULK && candidate.equals(spec.candidates().get(0))) {
                        elapsed = mode == 1 ? 153_000_000L : 150_000_000L;
                    } else if (scenario == Scenario.BULK && !candidate.equals(CalibrationProbeSpec.BASELINE)) {
                        elapsed = 170_000_000L + (index++ * 1_000L);
                    }
                    all.add(new Trial(scenario, candidate, elapsed, requestLatencies,
                            !(parityFails && candidate.equals(spec.candidates().get(0)) && repetition == 0)));
                }
            }
        }
        return all;
    }

    private static CalibrationProbeSpec copyWithDepth(CalibrationProbeSpec s, int depth) {
        return new CalibrationProbeSpec(s.version(), s.workloadId(), s.seed(), s.chunkCount(), s.dimX(), s.dimY(),
                s.dimZ(), s.strideX(), s.strideY(), s.strideZ(), s.passes(), s.repetitionsPerPass(),
                s.warmupsPerScenarioPerPass(), depth, s.candidateMatrix(), s.parityMethod(), s.selectorVersion(),
                s.executionSettings());
    }

    private static CalibrationDeviceIdentity identity() {
        return CalibrationDeviceIdentity.from(device("AMD Radeon RX 9070 XT", "Advanced Micro Devices, Inc.",
                "gfx1201", "test-driver-1", "AMD OpenCL Platform"));
    }

    private static OpenCLDevice device(String name, String vendor, String architecture,
                                       String driver, String platformVendor) {
        return new OpenCLDevice(1L, 2L, 0, 0, name, vendor, 0x1002, driver, "OpenCL 3.0", architecture,
                64, 8L << 30, 64L << 10, 1L << 30, 256, List.of(256L, 256L, 64L),
                0x1ffL, 0x1ffL, true, true, false, "AMD Platform", platformVendor);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected rejection.
        }
    }

    private static void expectIo(IoAction action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // Expected rejection.
        }
    }

    @FunctionalInterface
    private interface IoAction { void run() throws Exception; }
}
