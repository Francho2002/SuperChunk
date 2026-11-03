package dev.superchunk.gpu.dfc;

/** Exercises same-world fused-group upgrade selection without compiling OpenCL programs. */
public final class ChunkGridCacheBatcherSelectionRegressionTest {
    private ChunkGridCacheBatcherSelectionRegressionTest() { }

    public static void main(String[] args) {
        GpuWorldgenPolicy.WorldScope worldA = new GpuWorldgenPolicy.WorldScope(101, null);
        GpuWorldgenPolicy.WorldScope worldAOtherIdentity = new GpuWorldgenPolicy.WorldScope(101, null);
        GpuWorldgenPolicy.WorldScope worldB = new GpuWorldgenPolicy.WorldScope(202, null);

        // Registration order: first a small group, then a strictly more complete group
        // belonging to the very same world. The real ensureBatcher path uses this selector
        // before it stages an upgrade and retires its current batcher.
        check(ChunkGridCache.isStrictlyBetterBatcherGroup(worldA, 2, worldA, 7),
                "a larger same-world group must supersede the earlier small group");
        check(!ChunkGridCache.isStrictlyBetterBatcherGroup(worldA, 7, worldA, 3),
                "a later smaller group must not replace the pending/active larger group");
        check(!ChunkGridCache.isStrictlyBetterBatcherGroup(worldA, 7, worldA, 7),
                "equal-size registration must preserve the existing owner without churn");

        // Numeric IDs and root counts cannot transfer authorization or ownership between
        // distinct WorldScope objects, even if their test metadata is deliberately equal.
        check(!ChunkGridCache.isStrictlyBetterBatcherGroup(worldA, 2, worldAOtherIdentity, 99),
                "a different scope object with the same id must not be treated as this world");
        check(!ChunkGridCache.isStrictlyBetterBatcherGroup(worldA, 2, worldB, 99),
                "a more complete group from another world must not replace the active owner");
        check(!ChunkGridCache.isStrictlyBetterBatcherGroup(null, 0, worldA, 7),
                "an ownerless slot has no current group to supersede");
        check(!ChunkGridCache.isStrictlyBetterBatcherGroup(worldA, 7, null, 99),
                "an unscoped candidate cannot supersede an authorized group");

        System.out.println("ChunkGridCache batcher selection: same-scope strict upgrades and identity isolation passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
