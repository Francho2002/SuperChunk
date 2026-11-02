package dev.superchunk.gpu;

import java.util.List;

/** Pure, deterministic selection logic. Explicit indices are resolved by the backend first. */
final class DeviceSelection {
    private DeviceSelection() { }

    static OpenCLDevice indices(List<OpenCLDevice> devices, int platform, int index) {
        for (OpenCLDevice device : devices) {
            if (device.platformIndex() == platform && device.deviceIndex() == index) return device;
        }
        return null;
    }

    static OpenCLDevice identity(List<OpenCLDevice> devices, String vendor, String name) {
        if (vendor.isEmpty() || name.isEmpty()) return null;
        OpenCLDevice found = null;
        for (OpenCLDevice device : devices) {
            if (vendor.equalsIgnoreCase(device.vendor()) && name.equalsIgnoreCase(device.name())) {
                if (found != null) return null; // indistinguishable devices: do not guess
                found = device;
            }
        }
        return found;
    }

    static OpenCLDevice preferredRx(List<OpenCLDevice> devices, boolean requireFp64) {
        OpenCLDevice found = null;
        for (OpenCLDevice device : devices) {
            if (device.isRx9070Xt() && (!requireFp64 || device.fp64())) {
                if (found != null) return null; // avoid depending on enumeration order
                found = device;
            }
        }
        return found;
    }
}
