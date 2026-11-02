package dev.superchunk.gpu;

import java.util.List;

/** Pure fixtures: no driver, game or hardware required. */
public final class OpenCLDeviceRegressionTest {
    private static OpenCLDevice device(String name, String vendor, int vendorId, boolean fp64) {
        return new OpenCLDevice(1, 1, 0, 0, name, vendor, vendorId, "driver", "OpenCL 3.0", "",
                1, 1, 1, 1, 1, List.of(1L, 2L, 3L), 1, fp64 ? 1 : 0, fp64, false, false);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var rx = device("AMD Radeon RX 9070 XT", "Advanced Micro Devices, Inc.", 0x1002, true);
        var other = device("AMD Radeon RX 9070", "Advanced Micro Devices, Inc.", 0x1002, true);
        check(rx.isRx9070Xt(), "exact RX 9070 XT");
        check(!other.isRx9070Xt(), "RX 9070 is distinct");
        check(!device("AMD Radeon RX 7900 XT", "AMD", 0x1002, true).isRx9070Xt(), "7900 XT");
        check(!device("AMD Radeon RX 7800 XT", "AMD", 0x1002, true).isRx9070Xt(), "7800 XT");
        check(!device("gfx1201", "AMD", 0x1002, true).isRx9070Xt(), "ambiguous architecture");
        check(!device("AMD Radeon RX 9070 XT", "NVIDIA", 0x10de, true).isRx9070Xt(), "vendor");
        check(!device("AMD Radeon RX 9070 XT PRO", "AMD", 0x1002, true).isRx9070Xt(), "suffix");
        check(DeviceSelection.identity(List.of(other, rx), rx.vendor(), rx.name()) == rx, "identity");
        check(DeviceSelection.identity(List.of(rx, rx), rx.vendor(), rx.name()) == null, "duplicate identity");
        check(DeviceSelection.identity(List.of(rx), "", rx.name()) == null, "incomplete identity");
        var afterEmptyPlatform = new OpenCLDevice(2, 2, 1, 0, rx.name(), rx.vendor(), 0x1002,
                "driver", "OpenCL 3.0", "", 1, 1, 1, 1, 1, List.of(1L), 1, 1, true, false, false);
        check(DeviceSelection.indices(List.of(afterEmptyPlatform), 1, 0) == afterEmptyPlatform,
                "platform index survives an empty preceding platform");
        check(DeviceSelection.indices(List.of(afterEmptyPlatform), 0, 0) == null, "empty platform index");
        check(DeviceSelection.preferredRx(List.of(other, rx), true) == rx, "preference");
        check(DeviceSelection.preferredRx(List.of(rx, rx), true) == null, "ambiguous preference");
        check(DeviceSelection.preferredRx(List.of(device(rx.name(), rx.vendor(), 0x1002, false)), true) == null,
                "precision requirement");
        System.out.println("OpenCL device fixtures passed");
    }
}
