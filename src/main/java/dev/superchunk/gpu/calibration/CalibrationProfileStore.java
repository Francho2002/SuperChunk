package dev.superchunk.gpu.calibration;

import dev.superchunk.gpu.calibration.CalibrationMeasurements.Candidate;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.CandidateResult;
import dev.superchunk.gpu.calibration.CalibrationMeasurements.ScenarioStats;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Explicit offline persistence and validation for candidate-only calibration profiles. */
public final class CalibrationProfileStore {
    private static final String CHECKSUM_KEY = "integrity.sha256";

    private CalibrationProfileStore() { }

    /** Writes only a valid candidate; uses atomic replacement when supported, otherwise standard replacement. */
    public static void saveCandidate(Path destination, CalibrationProfile profile) throws IOException {
        if (destination == null || profile == null || !profile.isValid()) {
            throw new IOException("Refusing to persist an invalid or non-candidate calibration profile");
        }
        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Profile destination must have a parent directory");
        Files.createDirectories(parent);

        Map<String, String> values = encode(profile);
        String checksum = checksum(values);
        Properties properties = new Properties();
        properties.putAll(values);
        properties.setProperty(CHECKSUM_KEY, checksum);
        Path temporary = Files.createTempFile(parent, "sc-cal-" + absolute.getFileName(), ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "SuperChunk offline calibration candidate; manual review required");
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

    /**
     * Reads an artifact only for explicit offline review. It is not referenced from
     * GpuConfig, OpenCLBackend initialization or any worldgen runtime path.
     */
    public static CalibrationProfile loadForOfflineReview(Path source,
                                                           CalibrationDeviceIdentity expectedDevice,
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
            throw new IOException("Calibration profile checksum is missing or invalid");
        }

        CalibrationProfile profile;
        try {
            profile = decode(values);
        } catch (RuntimeException invalid) {
            throw new IOException("Calibration profile schema or values are invalid", invalid);
        }
        if (!profile.isValid()) throw new IOException("Calibration profile fails candidate validation");
        if (!encode(profile).equals(values)) {
            throw new IOException("Calibration profile contains missing, unexpected, or inconsistent fields");
        }
        if (!profile.device().sha256().equals(expectedDevice.sha256())) {
            throw new IOException("Calibration profile OpenCL platform/device identity does not match");
        }
        if (profile.probeVersion() != expectedSpec.version()
                || !profile.probeFingerprint().equals(expectedSpec.fingerprint())) {
            throw new IOException("Calibration profile workload/specification does not match");
        }
        return profile;
    }

    private static Map<String, String> encode(CalibrationProfile profile) {
        Map<String, String> values = new TreeMap<>();
        put(values, "schema.version", profile.schemaVersion());
        put(values, "profile.kind", profile.kind());
        put(values, "profile.status", profile.status());
        put(values, "profile.activation", profile.activation());
        put(values, "profile.hardwareLabel", profile.hardwareLabel());
        put(values, "profile.createdAtUtc", profile.createdAtUtc());
        CalibrationDeviceIdentity d = profile.device();
        put(values, "device.platformName", d.platformName());
        put(values, "device.platformVendor", d.platformVendor());
        put(values, "device.platformIndex", d.platformIndex());
        put(values, "device.name", d.deviceName());
        put(values, "device.vendor", d.deviceVendor());
        put(values, "device.vendorId", d.vendorId());
        put(values, "device.index", d.deviceIndex());
        put(values, "device.driverVersion", d.driverVersion());
        put(values, "device.openclVersion", d.openclVersion());
        put(values, "device.extensionSha256", d.extensionSha256());
        put(values, "device.singleFpConfig", Long.toUnsignedString(d.singleFpConfig()));
        put(values, "device.doubleFpConfig", Long.toUnsignedString(d.doubleFpConfig()));
        put(values, "device.fp64", d.fp64());
        put(values, "device.modelClassification", d.modelClassification());
        put(values, "device.identitySha256", d.sha256());
        put(values, "probe.version", profile.probeVersion());
        put(values, "probe.workloadId", profile.workloadId());
        put(values, "probe.fingerprint", profile.probeFingerprint());
        put(values, "probe.seed", Long.toUnsignedString(profile.seed()));
        put(values, "probe.fixedPipelineDepth", CalibrationProbeSpec.FIXED_PIPELINE_DEPTH);
        putCandidate(values, "baseline", profile.baseline());
        putCandidate(values, "selected", profile.selected());
        return values;
    }

    private static void putCandidate(Map<String, String> values, String prefix, CandidateResult result) {
        Candidate c = result.candidate();
        put(values, prefix + ".batchLimit", c.batchLimit());
        put(values, prefix + ".batchWindowMicros", c.batchWindowMicros());
        put(values, prefix + ".pipelineDepth", c.pipelineDepth());
        put(values, prefix + ".exactParity", result.exactParity());
        putStats(values, prefix + ".latency", result.latency());
        putStats(values, prefix + ".bulk", result.bulk());
    }

    private static void putStats(Map<String, String> values, String prefix, ScenarioStats s) {
        put(values, prefix + ".medianRequestsPerSecond", Double.toString(s.medianRequestsPerSecond()));
        put(values, prefix + ".medianRequestLatencyNs", s.medianRequestLatencyNs());
        put(values, prefix + ".p95RequestLatencyNs", s.p95RequestLatencyNs());
        put(values, prefix + ".trialCount", s.trialCount());
        put(values, prefix + ".requestSamples", s.requestSamples());
        put(values, prefix + ".exactParity", s.exactParity());
    }

    private static CalibrationProfile decode(Map<String, String> v) {
        CalibrationDeviceIdentity device = new CalibrationDeviceIdentity(
                required(v, "device.platformName"), required(v, "device.platformVendor"),
                integer(v, "device.platformIndex"), required(v, "device.name"),
                required(v, "device.vendor"), integer(v, "device.vendorId"), integer(v, "device.index"),
                required(v, "device.driverVersion"), required(v, "device.openclVersion"),
                required(v, "device.extensionSha256"), unsignedLong(v, "device.singleFpConfig"),
                unsignedLong(v, "device.doubleFpConfig"), bool(v, "device.fp64"),
                required(v, "device.modelClassification"), required(v, "device.identitySha256"));
        if (integer(v, "probe.fixedPipelineDepth") != CalibrationProbeSpec.FIXED_PIPELINE_DEPTH) {
            throw new IllegalArgumentException("Pipeline depth is not fixed at 2");
        }
        return new CalibrationProfile(integer(v, "schema.version"), required(v, "profile.kind"),
                required(v, "profile.status"), required(v, "profile.activation"), device,
                integer(v, "probe.version"), required(v, "probe.workloadId"),
                required(v, "probe.fingerprint"), unsignedLong(v, "probe.seed"),
                candidate(v, "baseline"), candidate(v, "selected"), required(v, "profile.createdAtUtc"));
    }

    private static CandidateResult candidate(Map<String, String> values, String prefix) {
        Candidate c = new Candidate(integer(values, prefix + ".batchLimit"),
                signedLong(values, prefix + ".batchWindowMicros"), integer(values, prefix + ".pipelineDepth"));
        return new CandidateResult(c, stats(values, prefix + ".latency"),
                stats(values, prefix + ".bulk"), bool(values, prefix + ".exactParity"));
    }

    private static ScenarioStats stats(Map<String, String> values, String prefix) {
        return new ScenarioStats(Double.parseDouble(required(values, prefix + ".medianRequestsPerSecond")),
                signedLong(values, prefix + ".medianRequestLatencyNs"),
                signedLong(values, prefix + ".p95RequestLatencyNs"), integer(values, prefix + ".trialCount"),
                integer(values, prefix + ".requestSamples"), bool(values, prefix + ".exactParity"));
    }

    private static void put(Map<String, String> values, String key, Object value) {
        values.put(key, String.valueOf(value));
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static int integer(Map<String, String> values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static long signedLong(Map<String, String> values, String key) {
        return Long.parseLong(required(values, key));
    }

    private static long unsignedLong(Map<String, String> values, String key) {
        return Long.parseUnsignedLong(required(values, key));
    }

    private static boolean bool(Map<String, String> values, String key) {
        String value = required(values, key);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean " + key);
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
