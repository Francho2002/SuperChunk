package dev.superchunk.gpu.calibration;

import dev.superchunk.gpu.OpenCLDevice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Stable OpenCL-reported identity for an offline calibration artifact. */
public record CalibrationDeviceIdentity(
        String platformName,
        String platformVendor,
        int platformIndex,
        String deviceName,
        String deviceVendor,
        int vendorId,
        int deviceIndex,
        String driverVersion,
        String openclVersion,
        String extensionSha256,
        long singleFpConfig,
        long doubleFpConfig,
        boolean fp64,
        String modelClassification,
        String sha256) {

    public CalibrationDeviceIdentity {
        platformName = clean(platformName);
        platformVendor = clean(platformVendor);
        deviceName = clean(deviceName);
        deviceVendor = clean(deviceVendor);
        driverVersion = clean(driverVersion);
        openclVersion = clean(openclVersion);
        extensionSha256 = clean(extensionSha256);
        modelClassification = clean(modelClassification);
        sha256 = clean(sha256);
    }

    /**
     * Returns a fingerprint only if the driver supplied complete stable identity. The raw
     * OpenCL handles are intentionally excluded because their values are process-specific.
     */
    public static CalibrationDeviceIdentity from(OpenCLDevice device) {
        Objects.requireNonNull(device, "device");
        if (!device.hasStablePlatformIdentity()
                || !stable(device.name()) || !stable(device.vendor())
                || device.vendorId() == 0 || !stable(device.driverVersion())
                || !stable(device.openclVersion()) || device.platformIndex() < 0
                || device.deviceIndex() < 0 || !device.fp64()
                || !stable(device.extensions()) || device.singleFpConfig() == 0
                || (device.doubleFpConfig() == 0 && !hasFp64Extension(device.extensions()))) {
            throw new IllegalArgumentException("OpenCL device identity/capabilities are incomplete for FP64 profiling");
        }
        String extensionDigest = sha256(device.extensions() == null ? "" : device.extensions().trim());
        String model = device.isRx9070Xt() ? "RX9070XT_EXACT_OPENCL_NAME" : "GENERIC_MODEL_UNVERIFIED";
        String material = canonical(device.platformName(), device.platformVendor(), device.platformIndex(),
                device.name(), device.vendor(), device.vendorId(), device.deviceIndex(),
                device.driverVersion(), device.openclVersion(), extensionDigest,
                device.singleFpConfig(), device.doubleFpConfig(), device.fp64(), model);
        return new CalibrationDeviceIdentity(device.platformName(), device.platformVendor(),
                device.platformIndex(), device.name(), device.vendor(), device.vendorId(),
                device.deviceIndex(), device.driverVersion(), device.openclVersion(), extensionDigest,
                device.singleFpConfig(), device.doubleFpConfig(), device.fp64(), model, sha256(material));
    }

    public boolean identifiesRx9070Xt() {
        return modelClassification.equals("RX9070XT_EXACT_OPENCL_NAME");
    }

    public boolean isComplete() {
        if (!stable(platformName) || !stable(platformVendor) || platformIndex < 0
                || !stable(deviceName) || !stable(deviceVendor) || vendorId == 0
                || deviceIndex < 0 || !stable(driverVersion) || !stable(openclVersion)
                || !isSha256(extensionSha256) || !fp64 || !stable(modelClassification)
                || !isSha256(sha256)) {
            return false;
        }
        String material = canonical(platformName, platformVendor, platformIndex,
                deviceName, deviceVendor, vendorId, deviceIndex, driverVersion, openclVersion,
                extensionSha256, singleFpConfig, doubleFpConfig, fp64, modelClassification);
        return sha256(material).equals(sha256);
    }

    static boolean stable(String value) {
        return value != null && !value.isBlank() && !value.equals("?")
                && !value.equalsIgnoreCase("unknown");
    }

    private static boolean hasFp64Extension(String extensions) {
        if (extensions == null) return false;
        for (String extension : extensions.trim().split("\\s+")) {
            if (extension.equals("cl_khr_fp64")) return true;
        }
        return false;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String clean(String value) {
        return value == null ? "?" : value.trim();
    }

    private static String canonical(Object... fields) {
        StringBuilder b = new StringBuilder();
        for (Object field : fields) {
            String value = String.valueOf(field);
            b.append(value.length()).append(':').append(value).append('\n');
        }
        return b.toString();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
