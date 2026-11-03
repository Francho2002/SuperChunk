package dev.superchunk.gpu.calibration;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable measurement values shared by the probe, selector, profile store and fixtures. */
public final class CalibrationMeasurements {
    private CalibrationMeasurements() { }

    public enum Scenario { LATENCY, BULK }

    /** Pipeline depth is deliberately fixed to 2 by this calibration contract. */
    public record Candidate(int batchLimit, long batchWindowMicros, int pipelineDepth) {
        public boolean withinSupportedRange() {
            return batchLimit >= 1 && batchLimit <= 128
                    && batchWindowMicros >= 0 && batchWindowMicros <= 10_000
                    && pipelineDepth == CalibrationProbeSpec.FIXED_PIPELINE_DEPTH;
        }

        public String key() {
            return batchLimit + ":" + batchWindowMicros + ":" + pipelineDepth;
        }
    }

    /** One actual submission pass. {@code requestLatenciesNs} excludes CPU parity checking. */
    public record Trial(Scenario scenario, Candidate candidate, long elapsedNs,
                        long[] requestLatenciesNs, boolean exactParity) {
        public Trial {
            Objects.requireNonNull(scenario, "scenario");
            Objects.requireNonNull(candidate, "candidate");
            requestLatenciesNs = requestLatenciesNs == null ? new long[0] : requestLatenciesNs.clone();
        }

        @Override
        public long[] requestLatenciesNs() {
            return requestLatenciesNs.clone();
        }
    }

    /** Aggregate computed from repeated trials, retaining only robust summary statistics. */
    public record ScenarioStats(double medianRequestsPerSecond,
                                long medianRequestLatencyNs,
                                long p95RequestLatencyNs,
                                int trialCount,
                                int requestSamples,
                                boolean exactParity) {
        public boolean usable(int minimumTrials, int minimumSamples) {
            return Double.isFinite(medianRequestsPerSecond) && medianRequestsPerSecond > 0
                    && medianRequestLatencyNs > 0 && p95RequestLatencyNs > 0
                    && trialCount >= minimumTrials && requestSamples >= minimumSamples
                    && exactParity;
        }
    }

    public record CandidateResult(Candidate candidate, ScenarioStats latency, ScenarioStats bulk,
                                  boolean exactParity) {
        public CandidateResult {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(latency, "latency");
            Objects.requireNonNull(bulk, "bulk");
        }

        public boolean usable(int minimumTrials, int minimumSamples) {
            return candidate.withinSupportedRange() && exactParity
                    && latency.usable(minimumTrials, minimumSamples)
                    && bulk.usable(minimumTrials, minimumSamples);
        }
    }

    /** Robust between-trial spread, computed from the independently retained raw trials. */
    public record TrialDispersion(double throughputMedianAbsoluteDeviationRequestsPerSecond,
                                  long trialLatencyMedianAbsoluteDeviationNs,
                                  int trialCount) {
    }

    /**
     * Computes median absolute deviations between repetitions. Throughput is derived per trial
     * from its completed request count and elapsed time; latency spread is derived from each
     * trial's request-latency median. This remains separate from the selector thresholds.
     */
    public static TrialDispersion dispersion(List<Trial> trials, Scenario scenario, Candidate candidate) {
        Objects.requireNonNull(trials, "trials");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(candidate, "candidate");
        double[] throughput = new double[trials.size()];
        long[] latencyMedians = new long[trials.size()];
        int count = 0;
        for (Trial trial : trials) {
            if (trial.scenario() != scenario || !trial.candidate().equals(candidate)) continue;
            long[] latencies = trial.requestLatenciesNs();
            if (trial.elapsedNs() <= 0 || latencies.length == 0
                    || Arrays.stream(latencies).anyMatch(value -> value <= 0)) continue;
            throughput[count] = 1_000_000_000.0 * latencies.length / trial.elapsedNs();
            latencyMedians[count] = median(latencies);
            count++;
        }
        throughput = Arrays.copyOf(throughput, count);
        latencyMedians = Arrays.copyOf(latencyMedians, count);
        if (count == 0) return new TrialDispersion(0.0, 0, 0);

        double throughputMedian = median(throughput);
        double[] throughputAbsoluteDeviations = new double[count];
        for (int i = 0; i < count; i++) {
            throughputAbsoluteDeviations[i] = Math.abs(throughput[i] - throughputMedian);
        }
        long latencyMedian = median(latencyMedians);
        long[] latencyAbsoluteDeviations = new long[count];
        for (int i = 0; i < count; i++) {
            latencyAbsoluteDeviations[i] = Math.abs(latencyMedians[i] - latencyMedian);
        }
        return new TrialDispersion(median(throughputAbsoluteDeviations),
                median(latencyAbsoluteDeviations), count);
    }

    static ScenarioStats aggregate(List<Trial> trials, Scenario scenario, Candidate candidate) {
        Objects.requireNonNull(trials, "trials");
        int matching = 0;
        int maxSamples = 0;
        for (Trial trial : trials) {
            if (trial.scenario() == scenario && trial.candidate().equals(candidate)) {
                matching++;
                maxSamples = Math.addExact(maxSamples, trial.requestLatenciesNs().length);
            }
        }
        double[] throughput = new double[matching];
        long[] allLatencies = new long[maxSamples];
        int trialCount = 0;
        int sampleCount = 0;
        boolean exact = true;
        for (Trial trial : trials) {
            if (trial.scenario() != scenario || !trial.candidate().equals(candidate)) continue;
            long[] latencies = trial.requestLatenciesNs();
            if (trial.elapsedNs() <= 0 || latencies.length == 0) {
                exact = false;
                continue;
            }
            throughput[trialCount++] = 1_000_000_000.0 * latencies.length / trial.elapsedNs();
            for (long latency : latencies) {
                if (latency <= 0) exact = false;
                allLatencies[sampleCount++] = latency;
            }
            exact &= trial.exactParity();
        }
        throughput = Arrays.copyOf(throughput, trialCount);
        allLatencies = Arrays.copyOf(allLatencies, sampleCount);
        return new ScenarioStats(median(throughput), median(allLatencies), percentile95(allLatencies),
                trialCount, sampleCount, exact && trialCount > 0);
    }

    private static double median(double[] source) {
        if (source.length == 0) return 0.0;
        double[] values = source.clone();
        Arrays.sort(values);
        int mid = values.length / 2;
        if ((values.length & 1) != 0) return values[mid];
        return (values[mid - 1] + values[mid]) / 2.0;
    }

    public static long median(long[] source) {
        if (source.length == 0) return 0;
        long[] values = source.clone();
        Arrays.sort(values);
        int mid = values.length / 2;
        if ((values.length & 1) != 0) return values[mid];
        return values[mid - 1] + (values[mid] - values[mid - 1]) / 2;
    }

    public static long percentile95(long[] source) {
        if (source.length == 0) return 0;
        long[] values = source.clone();
        Arrays.sort(values);
        int nearestRank = (int) Math.ceil(values.length * 0.95) - 1;
        return values[Math.max(0, Math.min(values.length - 1, nearestRank))];
    }
}
