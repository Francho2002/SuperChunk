package dev.superchunk.gpu.calibration;

import dev.superchunk.gpu.calibration.CalibrationMeasurements.Candidate;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.CandidateResult;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.Scenario;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.ScenarioStats;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.Trial;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Conservative selector for the explicit synthetic standalone calibration only. */
public final class CalibrationSelector {
    public static final double MIN_BULK_THROUGHPUT_GAIN = 0.05;
    public static final double MAX_LATENCY_MEDIAN_REGRESSION = 0.02;
    public static final double MAX_LATENCY_P95_REGRESSION = 0.05;

    private CalibrationSelector() { }

    public record Selection(Map<Candidate, CandidateResult> results,
                            Optional<CalibrationProfile> recommendation,
                            String rejectionReason) {
        public Selection {
            results = Map.copyOf(results);
            recommendation = Objects.requireNonNull(recommendation, "recommendation");
            rejectionReason = rejectionReason == null ? "" : rejectionReason;
        }
    }

    public static Selection select(CalibrationDeviceIdentity device, CalibrationProbeSpec spec,
                                   List<Trial> trials) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(trials, "trials");
        if (!device.isComplete()) return rejected(Map.of(), "OpenCL identity is incomplete");
        if (!spec.isSupported()) return rejected(Map.of(), "Probe specification is unsupported");
        if (trials.isEmpty() || trials.stream().anyMatch(t -> !t.exactParity())) {
            return rejected(Map.of(), "One or more FP64 trials did not pass exact parity");
        }

        List<Candidate> candidates;
        try {
            candidates = spec.candidates();
        } catch (RuntimeException invalid) {
            return rejected(Map.of(), "Candidate matrix is invalid");
        }
        for (Trial trial : trials) {
            if (!candidates.contains(trial.candidate())) {
                return rejected(Map.of(), "Measurements include a candidate outside the versioned matrix");
            }
            long[] samples = trial.requestLatenciesNs();
            if (samples.length != spec.chunkCount() || trial.elapsedNs() <= 0
                    || Arrays.stream(samples).anyMatch(sample -> sample <= 0)) {
                return rejected(Map.of(), "A measured pass is missing valid per-request samples");
            }
        }
        int expectedTrials = spec.passes() * spec.repetitionsPerPass();
        int expectedSamples = expectedTrials * spec.chunkCount();
        Map<Candidate, CandidateResult> results = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            ScenarioStats latency = CalibrationMeasurements.aggregate(trials, Scenario.LATENCY, candidate);
            ScenarioStats bulk = CalibrationMeasurements.aggregate(trials, Scenario.BULK, candidate);
            boolean parity = latency.exactParity() && bulk.exactParity();
            results.put(candidate, new CandidateResult(candidate, latency, bulk, parity));
        }

        Candidate baselineCandidate = CalibrationProbeSpec.BASELINE;
        CandidateResult baseline = results.get(baselineCandidate);
        if (!complete(baseline, expectedTrials, expectedSamples)) {
            return rejected(results, "Baseline lacks complete repeated exact-parity measurements");
        }
        // A partial run cannot safely rank candidates: require every configured candidate's
        // full paired sample set, even if a partial candidate would appear to win.
        for (Candidate candidate : candidates) {
            CandidateResult result = results.get(candidate);
            if (!complete(result, expectedTrials, expectedSamples)) {
                return rejected(results, "Candidate matrix is incomplete or contains invalid measurements: "
                        + candidate.key());
            }
        }

        CandidateResult best = null;
        for (Candidate candidate : candidates) {
            CandidateResult result = results.get(candidate);
            if (candidate.equals(baselineCandidate) || !meetsThresholds(result, baseline)) continue;
            if (best == null || result.bulk().medianRequestsPerSecond()
                    > best.bulk().medianRequestsPerSecond()) {
                best = result;
            }
        }
        if (best == null) return rejected(results, "No candidate passed every conservative threshold");
        CalibrationProfile profile = new CalibrationProfile(
                CalibrationProfile.CURRENT_SCHEMA_VERSION,
                "candidate-recommendation", "candidate-only", "manual-review-required",
                device, spec.version(), spec.workloadId(), spec.fingerprint(), spec.seed(),
                baseline, best, Instant.now().toString());
        if (!profile.isValid()) return rejected(results, "Selected candidate failed profile validation");
        return new Selection(results, Optional.of(profile), "");
    }

    static boolean meetsThresholds(CandidateResult candidate, CandidateResult baseline) {
        if (candidate == null || baseline == null
                || candidate.candidate().equals(baseline.candidate())
                || !candidate.exactParity() || !baseline.exactParity()) return false;
        double baseThroughput = baseline.bulk().medianRequestsPerSecond();
        long baseLatencyMedian = baseline.latency().medianRequestLatencyNs();
        long baseLatencyP95 = baseline.latency().p95RequestLatencyNs();
        return candidate.bulk().medianRequestsPerSecond() >= baseThroughput * (1.0 + MIN_BULK_THROUGHPUT_GAIN)
                && candidate.latency().medianRequestLatencyNs()
                    <= baseLatencyMedian * (1.0 + MAX_LATENCY_MEDIAN_REGRESSION)
                && candidate.latency().p95RequestLatencyNs()
                    <= baseLatencyP95 * (1.0 + MAX_LATENCY_P95_REGRESSION);
    }

    static boolean complete(CandidateResult result, int expectedTrials, int expectedSamples) {
        return result != null && result.usable(expectedTrials, expectedSamples)
                && result.latency().trialCount() == expectedTrials
                && result.bulk().trialCount() == expectedTrials
                && result.latency().requestSamples() == expectedSamples
                && result.bulk().requestSamples() == expectedSamples;
    }

    private static Selection rejected(Map<Candidate, CandidateResult> results, String reason) {
        return new Selection(results, Optional.empty(), reason);
    }
}
