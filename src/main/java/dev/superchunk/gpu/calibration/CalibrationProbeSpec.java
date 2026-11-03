package dev.superchunk.gpu.calibration;

/** Versioned, deterministic workload and candidate matrix used by the standalone probe. */
public record CalibrationProbeSpec(
        int version,
        String workloadId,
        long seed,
        int chunkCount,
        int dimX,
        int dimY,
        int dimZ,
        int strideX,
        int strideY,
        int strideZ,
        int passes,
        int repetitionsPerPass,
        int warmupsPerScenarioPerPass,
        int fixedPipelineDepth,
        String candidateMatrix,
        String parityMethod,
        String selectorVersion,
        String executionSettings) {

    public static final int CURRENT_VERSION = 1;
    public static final int FIXED_PIPELINE_DEPTH = 2;
    public static final CalibrationMeasurements.Candidate BASELINE =
            new CalibrationMeasurements.Candidate(32, 500, FIXED_PIPELINE_DEPTH);

    public static CalibrationProbeSpec current() {
        return new CalibrationProbeSpec(CURRENT_VERSION, "synthetic-noise-ast-fp64-v1",
                0x5C0FFEEL, 16, 5, 49, 5, 4, 8, 4,
                2, 3, 1, FIXED_PIPELINE_DEPTH,
                "8:50,16:100,32:100,32:500,64:500,32:1000",
                "raw-double-bits-v1", "bulk+5pct-latency-median+2pct-p95+5pct-v1",
                "enabled=true;desiredFp=fp64;compactIds=off;keepDeadGrids=true;batchPipelineDepth=2;"
                        + "colDedup=false;colHoist=true;completerPollMicros=50;dedupFunctions=true;"
                        + "bundleNoiseState=false;cornerCse=true;clOptDisable=false;noinlineHelpers=true;"
                        + "localSize=auto;nvOptLevel=1");
    }

    public String fingerprint() {
        String canonical = version + "\n" + workloadId + "\n" + Long.toUnsignedString(seed) + "\n"
                + chunkCount + ":" + dimX + ":" + dimY + ":" + dimZ + "\n"
                + strideX + ":" + strideY + ":" + strideZ + "\n"
                + passes + ":" + repetitionsPerPass + ":" + warmupsPerScenarioPerPass + "\n"
                + fixedPipelineDepth + "\n" + candidateMatrix + "\n" + parityMethod + "\n"
                + selectorVersion + "\n" + executionSettings;
        return CalibrationDeviceIdentity.sha256(canonical);
    }

    public boolean isSupported() {
        return version == CURRENT_VERSION && workloadId.equals("synthetic-noise-ast-fp64-v1")
                && chunkCount > 0 && dimX > 0 && dimY > 0 && dimZ > 0
                && strideX > 0 && strideY > 0 && strideZ > 0
                && passes == 2 && repetitionsPerPass == 3
                && warmupsPerScenarioPerPass == 1 && fixedPipelineDepth == FIXED_PIPELINE_DEPTH
                && parityMethod.equals("raw-double-bits-v1")
                && !candidateMatrix.isBlank() && !selectorVersion.isBlank()
                && !executionSettings.isBlank();
    }

    public java.util.List<CalibrationMeasurements.Candidate> candidates() {
        if (!isSupported()) throw new IllegalStateException("Unsupported calibration probe specification");
        java.util.List<CalibrationMeasurements.Candidate> result = new java.util.ArrayList<>();
        for (String tuple : candidateMatrix.split(",")) {
            String[] parts = tuple.trim().split(":");
            if (parts.length != 2) throw new IllegalStateException("Invalid candidate matrix entry: " + tuple);
            CalibrationMeasurements.Candidate candidate = new CalibrationMeasurements.Candidate(
                    Integer.parseInt(parts[0]), Long.parseLong(parts[1]), fixedPipelineDepth);
            if (!candidate.withinSupportedRange() || result.contains(candidate)) {
                throw new IllegalStateException("Invalid or duplicate candidate: " + tuple);
            }
            result.add(candidate);
        }
        if (!result.contains(BASELINE)) throw new IllegalStateException("Candidate matrix omits the baseline");
        return java.util.List.copyOf(result);
    }
}
