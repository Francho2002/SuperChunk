package dev.superchunk.gpu;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.YClampedGradientNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTNoiseNode;
import dev.superchunk.gpu.dfc.GpuBatchDispatcher;
import dev.superchunk.gpu.dfc.GpuChunkBatcher;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Explicit standalone diagnostic, not an autotuner or a world-parity certification. */
public final class StandaloneBatchProbe {
    private static final int COUNT = 16, DX = 5, DY = 49, DZ = 5, N = DX * DY * DZ;
    private static final int SX = 4, SY = 8, SZ = 4;
    private static final int REPETITIONS = 3;

    private StandaloneBatchProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("--run")) {
            throw new IllegalArgumentException("Pass --run to explicitly run the bounded OpenCL diagnostic");
        }
        System.setProperty("superchunk.gpu.compactIds", "off");
        System.setProperty("superchunk.gpu.keepDeadGrids", "true");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Properties props = new Properties();
        props.setProperty("enabled", "true");
        props.setProperty("desiredFp", "fp64");
        props.setProperty("batchPipelineDepth", "2");
        try {
            OpenCLBackend.init(GpuConfig.fromProperties(props));
            if (!OpenCLBackend.isAvailable() || OpenCLBackend.isFp32()) {
                throw new IllegalStateException("No FP64 OpenCL device; no benchmark results");
            }
            long outputBytes = (long) COUNT * N * 2 * Double.BYTES;
            if (OpenCLBackend.device().maxMemAllocBytes() < outputBytes
                    || OpenCLBackend.device().globalMemBytes() < outputBytes * 32) {
                throw new IllegalStateException("Insufficient or unknown device memory limits; no benchmark results");
            }
            RandomSource random = new XoroshiroRandomSource(0x5C0FFEEL);
            NormalNoise noise = NormalNoise.create(random, -7, 1, 1, 1, 1, 1, 1, 1, 1);
            DensityFunction.NoiseHolder holder = new DensityFunction.NoiseHolder(Holder.direct(noise.parameters()), noise);
            List<AstNode> roots = List.of(new YClampedGradientNode(-64, 320, 1.5, -1.5),
                    new DFTNoiseNode(holder, 0.25, 0.25));
            long prepStart = System.nanoTime();
            double[][] expected = reference(roots);
            long prepNs = System.nanoTime() - prepStart;
            System.out.printf(Locale.ROOT, "PROBE device=%s driver=%s opencl=%s fp64=true preparationNs=%d%n",
                    OpenCLBackend.device().name(), OpenCLBackend.device().driverVersion(),
                    OpenCLBackend.device().openclVersion(), prepNs);
            System.out.println("PROBE outputs are diagnostic only; device transfer and kernel times are not separately instrumented");
            for (int[] config : new int[][]{{32, 500}, {8, 50}, {16, 200}}) {
                runConfig(roots, expected, config[0], config[1], deadline);
            }
        } finally {
            OpenCLBackend.shutdown();
        }
    }

    private static double[][] reference(List<AstNode> roots) {
        double[][] result = new double[COUNT][roots.size() * N];
        for (int c = 0; c < COUNT; c++) {
            int ox = 96 + (c % 4) * 16, oz = -240 + (c / 4) * 16;
            for (int y = 0; y < DY; y++) for (int x = 0; x < DX; x++) for (int z = 0; z < DZ; z++) {
                int index = (y * DX + x) * DZ + z;
                for (int k = 0; k < roots.size(); k++) result[c][k * N + index] =
                        roots.get(k).evalSingle(ox + x * SX, -64 + y * SY, oz + z * SZ, EvalType.NORMAL);
            }
        }
        return result;
    }

    private static void runConfig(List<AstNode> roots, double[][] reference, int limit, int window,
                                  long deadline) throws Exception {
        remaining(deadline);
        GpuBatchDispatcher dispatcher = GpuBatchDispatcher.compile(roots);
        if (dispatcher == null) throw new IllegalStateException("Dispatcher compilation failed");
        GpuChunkBatcher batcher = GpuChunkBatcher.init(dispatcher, limit, window);
        if (batcher == null) { dispatcher.close(); throw new IllegalStateException("Batcher initialization failed"); }
        try {
            for (boolean bulk : new boolean[]{false, true}) {
                // Warm-up result must also pass parity; never retain a failed run.
                runOnce(batcher, reference, bulk, deadline);
                for (int repetition = 0; repetition < REPETITIONS; repetition++) {
                    Result result = runOnce(batcher, reference, bulk, deadline);
                    System.out.printf(Locale.ROOT, "PROBE scenario=%s batchLimit=%d windowMicros=%d depth=2 repetition=%d "
                                    + "requests=%d elapsedNs=%d medianLatencyNs=%d throughputPerSecond=%.3f parity=exact%n",
                            bulk ? "BULK" : "LATENCY", limit, window, repetition + 1, COUNT,
                            result.elapsedNs, result.medianLatencyNs, COUNT * 1_000_000_000.0 / result.elapsedNs);
                }
            }
        } finally {
            batcher.shutdown();
        }
    }

    private record Result(long elapsedNs, long medianLatencyNs) { }

    static long remaining(long deadline) throws java.util.concurrent.TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new java.util.concurrent.TimeoutException("Global probe deadline exceeded");
        return nanos;
    }

    private static Result runOnce(GpuChunkBatcher batcher, double[][] reference, boolean bulk,
                                  long deadline) throws Exception {
        @SuppressWarnings("unchecked") CompletableFuture<double[]>[] jobs = new CompletableFuture[COUNT];
        @SuppressWarnings("unchecked") CompletableFuture<double[]>[] submitted = new CompletableFuture[COUNT];
        long[] starts = new long[COUNT], latencies = new long[COUNT];
        long start = System.nanoTime();
        try {
            for (int c = 0; c < COUNT; c++) {
                int i = c;
                remaining(deadline);
                starts[c] = System.nanoTime();
                var future = batcher.submit(96 + (c % 4) * 16, -64, -240 + (c / 4) * 16,
                        SX, SY, SZ, DX, DY, DZ);
                if (future == null) throw new IllegalStateException("Batch submission rejected");
                submitted[c] = future;
                jobs[c] = future.whenComplete((ignored, error) -> latencies[i] = System.nanoTime() - starts[i]);
                if (!bulk) jobs[c].get(remaining(deadline), TimeUnit.NANOSECONDS);
            }
            double[][] outputs = new double[COUNT][];
            for (int c = 0; c < COUNT; c++) outputs[c] = jobs[c].get(remaining(deadline), TimeUnit.NANOSECONDS);
            long elapsed = System.nanoTime() - start;
            for (int c = 0; c < COUNT; c++) {
                double[] actual = outputs[c];
                if (actual == null || actual.length != reference[c].length) throw new IllegalStateException("Invalid result length");
                for (int i = 0; i < actual.length; i++) {
                    if (Double.doubleToRawLongBits(actual[i]) != Double.doubleToRawLongBits(reference[c][i])) {
                        throw new IllegalStateException("Exact CPU parity failed at chunk=" + c + " index=" + i);
                    }
                }
            }
            Arrays.sort(latencies);
            return new Result(elapsed, latencies[COUNT / 2]);
        } finally {
            for (var future : submitted) if (future != null && !future.isDone()) future.cancel(false);
        }
    }
}
