package dev.superchunk.gpu.calibration;

import dev.superchunk.gpu.calibration.CalibrationMeasurements.CandidateResult;

import java.util.Objects;

/**
 * An offline candidate artifact. This record has no runtime-config or activation API.
 * A later review must explicitly decide whether any recommendation is appropriate.
 */
public record CalibrationProfile(
        int schemaVersion,
        String kind,
        String status,
        String activation,
        CalibrationDeviceIdentity device,
        int probeVersion,
        String workloadId,
        String probeFingerprint,
        long seed,
        CandidateResult baseline,
        CandidateResult selected,
        String createdAtUtc) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public CalibrationProfile {
        kind = kind == null ? "" : kind;
        status = status == null ? "" : status;
        activation = activation == null ? "" : activation;
        workloadId = workloadId == null ? "" : workloadId;
        probeFingerprint = probeFingerprint == null ? "" : probeFingerprint;
        createdAtUtc = createdAtUtc == null ? "" : createdAtUtc;
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(selected, "selected");
    }

    public String hardwareLabel() {
        return device.identifiesRx9070Xt() ? "AMD_RX_9070_XT_OPENCL_IDENTITY" : "GENERIC_OPENCL_DEVICE_MODEL_UNVERIFIED";
    }

    public boolean isValid() {
        CalibrationProbeSpec current = CalibrationProbeSpec.current();
        int minimumTrials = current.passes() * current.repetitionsPerPass();
        int minimumSamples = minimumTrials * current.chunkCount();
        return schemaVersion == CURRENT_SCHEMA_VERSION
                && kind.equals("candidate-recommendation")
                && status.equals("candidate-only")
                && activation.equals("manual-review-required")
                && device.isComplete()
                && probeVersion == current.version()
                && workloadId.equals(current.workloadId())
                && probeFingerprint.equals(current.fingerprint())
                && seed == current.seed()
                && current.isSupported()
                && baseline.candidate().equals(CalibrationProbeSpec.BASELINE)
                && current.candidates().contains(selected.candidate())
                && !selected.candidate().equals(CalibrationProbeSpec.BASELINE)
                && CalibrationSelector.complete(baseline, minimumTrials, minimumSamples)
                && CalibrationSelector.complete(selected, minimumTrials, minimumSamples)
                && CalibrationSelector.meetsThresholds(selected, baseline)
                && !createdAtUtc.isBlank();
    }
}
