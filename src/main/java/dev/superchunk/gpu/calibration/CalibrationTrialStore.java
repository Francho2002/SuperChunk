package dev.superchunk.gpu.calibration;

import dev.superchunk.gpu.calibration.CalibrationMeasurements.Candidate;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.Scenario;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.ScenarioStats;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.Trial;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.TrialDispersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

/** Optional, offline archive of per-repetition synthetic calibration measurements. */
public final class CalibrationTrialStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final String CHECKSUM_KEY = "integrity.sha256";
    private static final String SYNTHETIC_KIND = "synthetic";

    private CalibrationTrialStore() { }

    /** Immutable raw-trial archive; it never represents world-generation performance. */
    public record Archive(int schemaVersion, String runId, String measurementKind,
                          boolean worldgenPerformanceEvidence, CalibrationDeviceIdentity device,
                          int probeVersion, String workloadId, String probeFingerprint, long seed,
                          String createdAtUtc, List<Trial> trials) {
        public Archive {
            runId = runId == null ? "" : runId;
            measurementKind = measurementKind == null ? "" : measurementKind;
            workloadId = workloadId == null ? "" : workloadId;
            probeFingerprint = probeFingerprint == null ? "" : probeFingerprint;
            createdAtUtc = createdAtUtc == null ? "" : createdAtUtc;
            trials = trials == null ? List.of() : List.copyOf(trials);
            if (device == null) throw new IllegalArgumentException("device is required");
        }

        public ScenarioStats aggregate(Candidate candidate, Scenario scenario) {
            return CalibrationMeasurements.aggregate(trials, scenario, candidate);
        }

        public TrialDispersion dispersion(Candidate candidate, Scenario scenario) {
            return CalibrationMeasurements.dispersion(trials, scenario, candidate);
        }
    }

    public static Archive create(CalibrationDeviceIdentity device, CalibrationProbeSpec spec,
                                 List<Trial> trials) {
        return new Archive(CURRENT_SCHEMA_VERSION, UUID.randomUUID().toString(), SYNTHETIC_KIND,
                false, device, spec.version(), spec.workloadId(), spec.fingerprint(), spec.seed(),
                Instant.now().toString(), trials);
    }

    /** Save only when explicitly requested; never writes a runtime profile or settings file. */
    public static void save(Path destination, Archive archive) throws IOException {
        if (destination == null || !isValid(archive, CalibrationProbeSpec.current())) {
            throw new IOException("Refusing to save an invalid calibration trial archive");
        }
        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Trial archive destination must have a parent");
        Files.createDirectories(parent);
        Map<String, String> values = encode(archive);
        Properties properties = new Properties();
        properties.putAll(values);
        properties.setProperty(CHECKSUM_KEY, checksum(values));
        Path temporary = Files.createTempFile(parent, "sc-trials-" + absolute.getFileName(), ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "SuperChunk synthetic calibration trials; offline review only");
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Load raw trials only for explicit offline review and verify their hardware/workload identity. */
    public static Archive loadForOfflineReview(Path source, CalibrationDeviceIdentity expectedDevice,
                                               CalibrationProbeSpec expectedSpec) throws IOException {
        if (source == null || expectedDevice == null || expectedSpec == null
                || !expectedDevice.isComplete() || !expectedSpec.isSupported()) {
            throw new IOException("Expected device identity or probe specification is incomplete");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(source)) {
            properties.load(input);
        }
        Map<String, String> values = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.equals(CHECKSUM_KEY)) values.put(key, properties.getProperty(key));
        }
        String persistedChecksum = properties.getProperty(CHECKSUM_KEY, "");
        if (!MessageDigest.isEqual(persistedChecksum.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                checksum(values).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IOException("Trial archive checksum is missing or invalid");
        }
        Archive archive;
        try {
            archive = decode(values);
        } catch (RuntimeException invalid) {
            throw new IOException("Trial archive schema or values are invalid", invalid);
        }
        if (!isValid(archive, expectedSpec) || !encode(archive).equals(values)) {
            throw new IOException("Trial archive is incomplete or contains inconsistent fields");
        }
        if (!archive.device().sha256().equals(expectedDevice.sha256())) {
            throw new IOException("Trial archive OpenCL identity does not match");
        }
        if (archive.probeVersion() != expectedSpec.version()
                || !archive.probeFingerprint().equals(expectedSpec.fingerprint())) {
            throw new IOException("Trial archive workload/specification does not match");
        }
        return archive;
    }

    private static boolean isValid(Archive archive, CalibrationProbeSpec spec) {
        return archive != null && spec != null && spec.isSupported()
                && archive.schemaVersion() == CURRENT_SCHEMA_VERSION
                && !archive.runId().isBlank() && archive.measurementKind().equals(SYNTHETIC_KIND)
                && !archive.worldgenPerformanceEvidence()
                && archive.device().isComplete()
                && archive.probeVersion() == spec.version()
                && archive.workloadId().equals(spec.workloadId())
                && archive.probeFingerprint().equals(spec.fingerprint())
                && archive.seed() == spec.seed() && !archive.createdAtUtc().isBlank()
                && !archive.trials().isEmpty()
                && archive.trials().stream().allMatch(trial -> spec.candidates().contains(trial.candidate())
                && trial.elapsedNs() > 0
                && trial.requestLatenciesNs().length == spec.chunkCount()
                && java.util.Arrays.stream(trial.requestLatenciesNs()).allMatch(sample -> sample > 0));
    }

    private static Map<String, String> encode(Archive archive) {
        Map<String, String> values = new TreeMap<>();
        put(values, "artifact.schema", archive.schemaVersion());
        put(values, "artifact.kind", "calibration-trials");
        put(values, "run.id", archive.runId());
        put(values, "run.createdAtUtc", archive.createdAtUtc());
        put(values, "measurement.kind", archive.measurementKind());
        put(values, "measurement.worldgenPerformanceEvidence", archive.worldgenPerformanceEvidence());
        put(values, "device.identitySha256", archive.device().sha256());
        put(values, "device.modelClassification", archive.device().modelClassification());
        CalibrationDeviceIdentity device = archive.device();
        put(values, "device.platformName", device.platformName());
        put(values, "device.platformVendor", device.platformVendor());
        put(values, "device.platformIndex", device.platformIndex());
        put(values, "device.name", device.deviceName());
        put(values, "device.vendor", device.deviceVendor());
        put(values, "device.vendorId", device.vendorId());
        put(values, "device.index", device.deviceIndex());
        put(values, "device.driverVersion", device.driverVersion());
        put(values, "device.openclVersion", device.openclVersion());
        put(values, "device.extensionSha256", device.extensionSha256());
        put(values, "device.singleFpConfig", Long.toUnsignedString(device.singleFpConfig()));
        put(values, "device.doubleFpConfig", Long.toUnsignedString(device.doubleFpConfig()));
        put(values, "device.fp64", device.fp64());
        put(values, "probe.version", archive.probeVersion());
        put(values, "probe.workloadId", archive.workloadId());
        put(values, "probe.fingerprint", archive.probeFingerprint());
        put(values, "probe.seed", Long.toUnsignedString(archive.seed()));
        put(values, "trials.count", archive.trials().size());
        for (int i = 0; i < archive.trials().size(); i++) {
            Trial trial = archive.trials().get(i);
            String prefix = "trial." + i + ".";
            put(values, prefix + "index", i);
            put(values, prefix + "scenario", trial.scenario().name());
            put(values, prefix + "candidate", trial.candidate().key());
            put(values, prefix + "elapsedNs", trial.elapsedNs());
            put(values, prefix + "exactParity", trial.exactParity());
            long[] latencies = trial.requestLatenciesNs();
            put(values, prefix + "requestCount", latencies.length);
            StringBuilder samples = new StringBuilder();
            for (long latency : latencies) {
                if (samples.length() > 0) samples.append(',');
                samples.append(latency);
            }
            put(values, prefix + "requestLatenciesNs", samples);
        }
        for (Candidate candidate : CalibrationProbeSpec.current().candidates()) {
            for (Scenario scenario : Scenario.values()) {
                ScenarioStats stats = archive.aggregate(candidate, scenario);
                CalibrationMeasurements.TrialDispersion dispersion = archive.dispersion(candidate, scenario);
                String prefix = "summary." + candidate.key().replace(':', '_') + "." + scenario.name() + ".";
                put(values, prefix + "medianRequestsPerSecond", Double.toString(stats.medianRequestsPerSecond()));
                put(values, prefix + "medianRequestLatencyNs", stats.medianRequestLatencyNs());
                put(values, prefix + "p95RequestLatencyNs", stats.p95RequestLatencyNs());
                put(values, prefix + "trialCount", stats.trialCount());
                put(values, prefix + "requestSamples", stats.requestSamples());
                put(values, prefix + "exactParity", stats.exactParity());
                put(values, prefix + "throughputMedianAbsoluteDeviationRequestsPerSecond",
                        Double.toString(dispersion.throughputMedianAbsoluteDeviationRequestsPerSecond()));
                put(values, prefix + "trialLatencyMedianAbsoluteDeviationNs",
                        dispersion.trialLatencyMedianAbsoluteDeviationNs());
            }
        }
        return values;
    }

    private static Archive decode(Map<String, String> values) {
        int count = integer(values, "trials.count");
        if (count < 1 || count > 100_000) throw new IllegalArgumentException("Invalid trial count");
        List<Trial> trials = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String prefix = "trial." + i + ".";
            if (integer(values, prefix + "index") != i) throw new IllegalArgumentException("Trial index mismatch");
            Candidate candidate = parseCandidate(required(values, prefix + "candidate"));
            long[] latencies = parseLatencies(required(values, prefix + "requestLatenciesNs"),
                    integer(values, prefix + "requestCount"));
            trials.add(new Trial(Scenario.valueOf(required(values, prefix + "scenario")), candidate,
                    signedLong(values, prefix + "elapsedNs"), latencies,
                    bool(values, prefix + "exactParity")));
        }
        return new Archive(integer(values, "artifact.schema"), required(values, "run.id"),
                required(values, "measurement.kind"),
                bool(values, "measurement.worldgenPerformanceEvidence"),
                identity(values), integer(values, "probe.version"), required(values, "probe.workloadId"),
                required(values, "probe.fingerprint"), Long.parseUnsignedLong(required(values, "probe.seed")),
                required(values, "run.createdAtUtc"), trials);
    }

    private static CalibrationDeviceIdentity identity(Map<String, String> values) {
        return new CalibrationDeviceIdentity(required(values, "device.platformName"),
                required(values, "device.platformVendor"), integer(values, "device.platformIndex"),
                required(values, "device.name"), required(values, "device.vendor"),
                integer(values, "device.vendorId"), integer(values, "device.index"),
                required(values, "device.driverVersion"), required(values, "device.openclVersion"),
                required(values, "device.extensionSha256"),
                Long.parseUnsignedLong(required(values, "device.singleFpConfig")),
                Long.parseUnsignedLong(required(values, "device.doubleFpConfig")),
                bool(values, "device.fp64"), required(values, "device.modelClassification"),
                required(values, "device.identitySha256"));
    }

    private static Candidate parseCandidate(String key) {
        String[] fields = key.split(":", -1);
        if (fields.length != 3) throw new IllegalArgumentException("Invalid candidate key");
        return new Candidate(Integer.parseInt(fields[0]), Long.parseLong(fields[1]), Integer.parseInt(fields[2]));
    }

    private static long[] parseLatencies(String encoded, int expectedCount) {
        if (expectedCount < 0 || expectedCount > CalibrationProbeSpec.current().chunkCount()) {
            throw new IllegalArgumentException("Invalid request count");
        }
        if (expectedCount == 0 && encoded.isEmpty()) return new long[0];
        String[] parts = encoded.split(",", -1);
        if (parts.length != expectedCount) throw new IllegalArgumentException("Latency sample count mismatch");
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) values[i] = Long.parseLong(parts[i]);
        return values;
    }

    private static void put(Map<String, String> values, String key, Object value) {
        values.put(key, String.valueOf(value));
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static int integer(Map<String, String> values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static long signedLong(Map<String, String> values, String key) {
        return Long.parseLong(required(values, key));
    }

    private static boolean bool(Map<String, String> values, String key) {
        String value = required(values, key);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean");
        return Boolean.parseBoolean(value);
    }

    private static String checksum(Map<String, String> values) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
                update(digest, entry.getKey());
                update(digest, entry.getValue());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("Java runtime does not provide SHA-256", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
