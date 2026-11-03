package dev.superchunk.gpu;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Snapshot of the capabilities reported by an OpenCL device, not inferred hardware specs. */
public record OpenCLDevice(
        long platform, long device, int platformIndex, int deviceIndex, String name, String vendor, int vendorId,
        String driverVersion, String openclVersion, String extensions,
        int maxComputeUnits, long globalMemBytes, long localMemBytes,
        long maxMemAllocBytes, long maxWorkGroupSize, List<Long> maxWorkItemSizes,
        long singleFpConfig, long doubleFpConfig, boolean fp64,
        boolean crDivideFp32, boolean nvCompilerOptions,
        String platformName, String platformVendor) {

    private static final Pattern RX_9070_XT = Pattern.compile(
            "^(?:amd\\s+)?radeon(?:\\(tm\\))?\\s+rx\\s+9070\\s+xt(?:\\s+graphics)?$",
            Pattern.CASE_INSENSITIVE);

    public OpenCLDevice {
        maxWorkItemSizes = List.copyOf(maxWorkItemSizes);
        platformName = platformName == null ? "?" : platformName.trim();
        platformVendor = platformVendor == null ? "?" : platformVendor.trim();
    }

    /** Source-compatible fixture constructor for callers without platform metadata. */
    public OpenCLDevice(long platform, long device, int platformIndex, int deviceIndex,
                        String name, String vendor, int vendorId, String driverVersion,
                        String openclVersion, String extensions, int maxComputeUnits,
                        long globalMemBytes, long localMemBytes, long maxMemAllocBytes,
                        long maxWorkGroupSize, List<Long> maxWorkItemSizes,
                        long singleFpConfig, long doubleFpConfig, boolean fp64,
                        boolean crDivideFp32, boolean nvCompilerOptions) {
        this(platform, device, platformIndex, deviceIndex, name, vendor, vendorId,
                driverVersion, openclVersion, extensions, maxComputeUnits, globalMemBytes,
                localMemBytes, maxMemAllocBytes, maxWorkGroupSize, maxWorkItemSizes,
                singleFpConfig, doubleFpConfig, fp64, crDivideFp32, nvCompilerOptions, "?", "?");
    }

    /** True only when both stable platform identity strings were actually queried. */
    public boolean hasStablePlatformIdentity() {
        return stableIdentity(platformName) && stableIdentity(platformVendor);
    }

    private static boolean stableIdentity(String value) {
        return value != null && !value.isBlank() && !value.equals("?")
                && !value.equalsIgnoreCase("unknown");
    }

    /** Conservative model identification; generic architecture names are insufficient. */
    public boolean isRx9070Xt() {
        boolean amd = vendorId == 0x1002 && vendor != null
                && (vendor.toLowerCase(Locale.ROOT).contains("advanced micro devices")
                    || vendor.trim().equalsIgnoreCase("AMD"));
        return amd && name != null && RX_9070_XT.matcher(name.trim()).matches();
    }

    /** OpenCL extension names are space-separated tokens, not substrings. */
    static boolean hasExtension(String extensions, String extension) {
        if (extensions == null || extension == null || extension.isEmpty()) return false;
        for (String token : extensions.trim().split("\\s+")) {
            if (token.equals(extension)) return true;
        }
        return false;
    }

    public String describe() {
        return String.format("%s [%s, vendorId=0x%04x] | driver=%s | %s | CU=%d | "
                        + "global=%d local=%d maxAlloc=%d maxGroup=%d maxItems=%s | "
                        + "fp32=0x%x fp64=0x%x (%s) | platform=%s [%s] | profile=%s | extensions=%s",
                name, vendor, vendorId, driverVersion, openclVersion, maxComputeUnits,
                globalMemBytes, localMemBytes, maxMemAllocBytes, maxWorkGroupSize,
                maxWorkItemSizes, singleFpConfig, doubleFpConfig, fp64,
                platformName, platformVendor,
                isRx9070Xt() ? "RX 9070 XT (uncalibrated)" : "generic", extensions);
    }
}
