package dev.superchunk.gpu.dfc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM-only regressions for batch attribution and async-scope diagnostics. */
public final class GpuAuthorizationDiagnosticsRegressionTest {

    private GpuAuthorizationDiagnosticsRegressionTest() {
    }

    public static void main(String[] args) {
        taskInputReasonsAreDistinct();
        permitRejectionReasonsAreDistinct();
        authorizationGateIsStableWithDiagnosticsOnOrOff();
        worldBucketsAreBounded();
        climateBatchDoesNotCountAsDensity();
        postDispatchFailureWithoutReadbackIsNotReadbackFailure();
        asyncSubmissionAndSupplierLifecycleAreDistinct();
        System.out.println("GpuAuthorizationDiagnosticsRegressionTest passed");
    }

    private static void taskInputReasonsAreDistinct() {
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.CHUNK_NULL,
                GpuWorldgenPolicy.diagnoseTaskInputs(false,
                        GpuWorldgenPolicy.LevelAccessorOutcome.NOT_CHECKED, false, false, false),
                "null chunk is classified before reading the accessor");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.LEVEL_ACCESSOR_EXCEPTION,
                GpuWorldgenPolicy.diagnoseTaskInputs(true,
                        GpuWorldgenPolicy.LevelAccessorOutcome.EXCEPTION, false, false, false),
                "accessor exception is distinct from a non-server accessor");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.LEVEL_ACCESSOR_NOT_SERVER_LEVEL,
                GpuWorldgenPolicy.diagnoseTaskInputs(true,
                        GpuWorldgenPolicy.LevelAccessorOutcome.NOT_SERVER_LEVEL, false, false, false),
                "non-server accessor is classified independently");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.GENERATOR_MISMATCH,
                GpuWorldgenPolicy.diagnoseTaskInputs(true,
                        GpuWorldgenPolicy.LevelAccessorOutcome.SERVER_LEVEL, false, true, false),
                "generator mismatch precedes RandomState checks");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.RANDOM_STATE_MISMATCH,
                GpuWorldgenPolicy.diagnoseTaskInputs(true,
                        GpuWorldgenPolicy.LevelAccessorOutcome.SERVER_LEVEL, true, false, false),
                "RandomState mismatch is separately classified");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.SCOPE_RETIRED,
                GpuWorldgenPolicy.diagnoseTaskInputs(true,
                        GpuWorldgenPolicy.LevelAccessorOutcome.SERVER_LEVEL, true, true, true),
                "retired scope is distinct from mismatched identities");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.AUTHORIZED,
                GpuWorldgenPolicy.diagnoseTaskInputs(true,
                        GpuWorldgenPolicy.LevelAccessorOutcome.SERVER_LEVEL, true, true, false),
                "input classifier marks only complete identity as eligible for later checks");
    }

    private static void worldBucketsAreBounded() {
        expect(GpuAuthorizationDiagnostics.WorldBucket.OVERWORLD,
                GpuAuthorizationDiagnostics.bucketForDimension("minecraft:overworld"), "overworld bucket");
        expect(GpuAuthorizationDiagnostics.WorldBucket.NETHER,
                GpuAuthorizationDiagnostics.bucketForDimension("minecraft:the_nether"), "nether bucket");
        expect(GpuAuthorizationDiagnostics.WorldBucket.END,
                GpuAuthorizationDiagnostics.bucketForDimension("minecraft:the_end"), "end bucket");
        expect(GpuAuthorizationDiagnostics.WorldBucket.MODDED,
                GpuAuthorizationDiagnostics.bucketForDimension("example:moon"), "modded bucket");
        expect(GpuAuthorizationDiagnostics.WorldBucket.UNKNOWN,
                GpuAuthorizationDiagnostics.bucketForDimension("not-a-resource-location"), "invalid is unknown");
        expect(GpuAuthorizationDiagnostics.WorldBucket.UNKNOWN,
                GpuAuthorizationDiagnostics.bucketForDimension(null), "missing is unknown");
    }

    private static void permitRejectionReasonsAreDistinct() {
        Object scope = new Object();
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.GENERATOR_UNAUTHORIZED,
                GpuWorldgenPolicy.diagnoseAuthorization(new FixturePermit(
                        GpuWorldgenPolicy.GeneratorKind.CUSTOM_GENERATOR, true, true, true, scope),
                        GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY),
                "unsupported generator is distinct from device state");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.PERMIT_STALE_OR_RETIRED,
                GpuWorldgenPolicy.diagnoseAuthorization(new FixturePermit(
                        GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE, false, true, true, scope),
                        GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY),
                "expired permit is classified before device state");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.GPU_DISABLED,
                GpuWorldgenPolicy.diagnoseAuthorization(new FixturePermit(
                        GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE, true, false, true, scope),
                        GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY),
                "configuration-disabled GPU is distinct from unavailable device");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.DEVICE_UNAVAILABLE,
                GpuWorldgenPolicy.diagnoseAuthorization(new FixturePermit(
                        GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE, true, true, false, scope),
                        GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY),
                "enabled GPU with unavailable device has its own reason");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.OTHER_ELIGIBILITY,
                GpuWorldgenPolicy.diagnoseAuthorization(new FixturePermit(
                        GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE, true, true, true, scope),
                        GpuWorldgenPolicy.DispatchPath.DISTANT_HORIZONS),
                "unsupported route stays in the residual category");
    }

    private static void authorizationGateIsStableWithDiagnosticsOnOrOff() {
        FixturePermit allowed = new FixturePermit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, new Object());
        boolean allowedWithDiagnostics = GpuWorldgenPolicy.isAuthorized(
                allowed, GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY);
        AtomicInteger allowedClassifiers = new AtomicInteger();
        GpuWorldgenPolicy.AuthorizationDiagnostic allowedReason = GpuWorldgenPolicy.diagnoseRejectedGate(
                true, true, () -> {
                    allowedClassifiers.incrementAndGet();
                    return GpuWorldgenPolicy.AuthorizationDiagnostic.GPU_DISABLED;
                });
        boolean allowedWithoutDiagnostics = GpuWorldgenPolicy.isAuthorized(
                allowed, GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY);
        expect(true, allowedWithDiagnostics, "authorized fixture passes the production boolean gate");
        expect(allowedWithDiagnostics, allowedWithoutDiagnostics,
                "diagnostic configuration cannot change an authorization decision");
        expect(0, allowedClassifiers.get(),
                "detailed classifier is not run after an accepted production gate");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.AUTHORIZED, allowedReason,
                "accepted gate remains accepted without diagnostic classification");

        FixturePermit denied = new FixturePermit(GpuWorldgenPolicy.GeneratorKind.TELLUS,
                true, true, true, new Object());
        boolean deniedWithDiagnostics = GpuWorldgenPolicy.isAuthorized(
                denied, GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY);
        AtomicInteger diagnosticOffCalls = new AtomicInteger();
        GpuWorldgenPolicy.AuthorizationDiagnostic reasonOff = GpuWorldgenPolicy.diagnoseRejectedGate(
                false, false, () -> {
                    diagnosticOffCalls.incrementAndGet();
                    return GpuWorldgenPolicy.AuthorizationDiagnostic.AUTHORIZED;
                });
        AtomicInteger diagnosticOnCalls = new AtomicInteger();
        GpuWorldgenPolicy.AuthorizationDiagnostic reasonOn = GpuWorldgenPolicy.diagnoseRejectedGate(
                false, true, () -> {
                    diagnosticOnCalls.incrementAndGet();
                    // Simulates a state race where a later detailed read appears to pass.
                    return GpuWorldgenPolicy.AuthorizationDiagnostic.AUTHORIZED;
                });
        boolean deniedWithoutDiagnostics = GpuWorldgenPolicy.isAuthorized(
                denied, GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY);
        expect(false, deniedWithDiagnostics, "denied fixture fails the production boolean gate");
        expect(deniedWithDiagnostics, deniedWithoutDiagnostics,
                "diagnostic configuration cannot promote a denied permit");
        expect(0, diagnosticOffCalls.get(), "disabled diagnostics skip the detailed classifier");
        expect(1, diagnosticOnCalls.get(), "enabled diagnostics classify only the rejected gate");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.OTHER_ELIGIBILITY, reasonOff,
                "disabled diagnostic classification remains residual while denied");
        expect(GpuWorldgenPolicy.AuthorizationDiagnostic.OTHER_ELIGIBILITY, reasonOn,
                "a contradictory post-rejection diagnosis remains residual");
    }

    private static void climateBatchDoesNotCountAsDensity() {
        if (!GpuAuthorizationDiagnostics.enabled()) return;
        GpuAuthorizationDiagnostics.WorldBucket bucket = GpuAuthorizationDiagnostics.WorldBucket.OVERWORLD;
        long densityEnqueued = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_BATCH_ND_RANGE_ENQUEUED, bucket);
        long densityReadback = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_BATCH_READBACK_COMPLETED, bucket);
        long climateEnqueued = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.CLIMATE_BATCH_ND_RANGE_ENQUEUED, bucket);
        long climateReadback = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.CLIMATE_BATCH_READBACK_COMPLETED, bucket);

        GpuAuthorizationDiagnostics.recordBatchPrimaryEnqueued(
                GpuAuthorizationDiagnostics.BatchWorkload.CLIMATE, bucket);
        GpuAuthorizationDiagnostics.recordBatchReadback(
                GpuAuthorizationDiagnostics.BatchWorkload.CLIMATE, false, true, bucket);

        expect(densityEnqueued, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_BATCH_ND_RANGE_ENQUEUED, bucket),
                "standalone climate batch cannot increment density NDRange count");
        expect(densityReadback, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_BATCH_READBACK_COMPLETED, bucket),
                "standalone climate readback cannot increment density completion count");
        expect(climateEnqueued + 1, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.CLIMATE_BATCH_ND_RANGE_ENQUEUED, bucket),
                "standalone climate batch increments climate NDRange count");
        expect(climateReadback + 1, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.CLIMATE_BATCH_READBACK_COMPLETED, bucket),
                "standalone climate readback increments climate completion count");

        long combinedBefore = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.COMBINED_BATCH_READBACK_COMPLETED, bucket);
        GpuAuthorizationDiagnostics.recordBatchPrimaryEnqueued(
                GpuAuthorizationDiagnostics.BatchWorkload.DENSITY, bucket);
        GpuAuthorizationDiagnostics.recordClimateChainEnqueued(true, bucket);
        GpuAuthorizationDiagnostics.recordBatchReadback(
                GpuAuthorizationDiagnostics.BatchWorkload.DENSITY, true, true, bucket);
        expect(combinedBefore + 1, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.COMBINED_BATCH_READBACK_COMPLETED, bucket),
                "combined density and climate chain has an explicit completion count");
    }

    private static void postDispatchFailureWithoutReadbackIsNotReadbackFailure() {
        if (!GpuAuthorizationDiagnostics.enabled()) return;
        GpuAuthorizationDiagnostics.WorldBucket bucket = GpuAuthorizationDiagnostics.WorldBucket.NETHER;

        long densityCompleted = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_BATCH_READBACK_COMPLETED, bucket);
        long densityFailedReadback = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_BATCH_COMPLETION_FAILED, bucket);
        long densityPostDispatch = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket);
        GpuAuthorizationDiagnostics.recordBatchEnqueueFailure(true, false,
                GpuAuthorizationDiagnostics.BatchWorkload.DENSITY, false, bucket);
        expect(densityCompleted, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_BATCH_READBACK_COMPLETED, bucket),
                "failure before a density readback is not reported as a completed readback");
        expect(densityFailedReadback, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_BATCH_COMPLETION_FAILED, bucket),
                "failure before a density readback is not reported as a failed readback");
        expect(densityPostDispatch + 1, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket),
                "density NDRange followed by missing readback has a distinct counter");

        long climatePostDispatch = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.CLIMATE_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket);
        long combinedPostDispatch = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.COMBINED_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket);
        long combinedReadbackFailed = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.COMBINED_BATCH_COMPLETION_FAILED, bucket);
        GpuAuthorizationDiagnostics.recordClimateDispatchWithoutReadbackFailure(true, bucket);
        expect(climatePostDispatch + 1, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.CLIMATE_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket),
                "climate kernel without its readback has a climate-specific counter");
        expect(combinedPostDispatch + 1, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.COMBINED_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket),
                "combined climate chain without readback has a combined-specific counter");
        expect(combinedReadbackFailed, GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.COMBINED_BATCH_COMPLETION_FAILED, bucket),
                "combined chain without readback does not claim a failed readback");
    }

    private static void asyncSubmissionAndSupplierLifecycleAreDistinct() {
        GpuAuthorizationDiagnostics.WorldBucket bucket = GpuAuthorizationDiagnostics.WorldBucket.END;
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger submitFailed = new AtomicInteger();
        AtomicInteger futureReturned = new AtomicInteger();
        AtomicInteger cancelledBeforeStart = new AtomicInteger();
        GpuTaskScopeCarrier.Lifecycle lifecycle = new GpuTaskScopeCarrier.Lifecycle();
        CompletableFuture<String> cancelled = GpuTaskScopeCarrier.submit(
                ignored -> new CompletableFuture<>(), () -> "never", lifecycle,
                submitted::incrementAndGet, submitFailed::incrementAndGet,
                futureReturned::incrementAndGet, cancelledBeforeStart::incrementAndGet);
        expect(1, submitted.get(), "successful submit is counted after a future is returned");
        expect(1, futureReturned.get(), "returned future is counted");
        cancelled.cancel(false);
        expect(1, cancelledBeforeStart.get(), "observable pre-start cancellation is counted once");
        GpuTaskScopeCarrier.Lifecycle alreadyStarted = new GpuTaskScopeCarrier.Lifecycle();
        alreadyStarted.markStarted();
        AtomicInteger lateCancellation = new AtomicInteger();
        CompletableFuture<String> cancelledAfterStart = new CompletableFuture<>();
        alreadyStarted.observeCancellation(cancelledAfterStart, lateCancellation::incrementAndGet);
        cancelledAfterStart.cancel(false);
        expect(0, lateCancellation.get(), "cancellation after supplier start is not labeled pre-start");

        AtomicInteger failedSubmitCount = new AtomicInteger();
        try {
            GpuTaskScopeCarrier.submit(supplier -> {
                        throw new java.util.concurrent.RejectedExecutionException("fixture");
                    }, () -> "not-submitted", new GpuTaskScopeCarrier.Lifecycle(),
                    () -> { }, failedSubmitCount::incrementAndGet, () -> { }, () -> { });
            expect(false, "submit failure propagates", "rejected submission remains visible");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            expect(1, failedSubmitCount.get(), "failed submission has its own counter");
        }

        AtomicInteger scopeClosed = new AtomicInteger();
        AtomicInteger supplierStarted = new AtomicInteger();
        AtomicInteger supplierCompleted = new AtomicInteger();
        AtomicInteger supplierException = new AtomicInteger();
        AtomicInteger scopeRestored = new AtomicInteger();
        GpuTaskScopeCarrier.Lifecycle startedLifecycle = new GpuTaskScopeCarrier.Lifecycle();
        var throwing = GpuTaskScopeCarrier.wrap(
                () -> { throw new IllegalStateException("supplier-fixture"); },
                () -> scopeClosed::incrementAndGet, scope -> { }, bucket, startedLifecycle,
                supplierStarted::incrementAndGet, supplierCompleted::incrementAndGet,
                supplierException::incrementAndGet, scopeRestored::incrementAndGet);
        try {
            throwing.get();
            expect(false, "supplier exception propagates", "wrapped supplier retains its exception");
        } catch (IllegalStateException expected) {
            expect("supplier-fixture", expected.getMessage(), "original supplier failure is preserved");
        }
        expect(1, supplierStarted.get(), "supplier start is distinct from submission");
        expect(0, supplierCompleted.get(), "throwing supplier is not counted as normal completion");
        expect(1, supplierException.get(), "supplier exception has a dedicated counter");
        expect(1, scopeClosed.get(), "scope closes for a failing supplier");
        expect(1, scopeRestored.get(), "context restoration is counted after a failing supplier");

        AtomicInteger failedCloseRestored = new AtomicInteger();
        AtomicInteger failedCloseExceptions = new AtomicInteger();
        var failedClose = GpuTaskScopeCarrier.wrap(
                () -> "ok", () -> () -> { throw new IllegalStateException("close-fixture"); },
                scope -> { }, bucket, new GpuTaskScopeCarrier.Lifecycle(), () -> { }, () -> { },
                failedCloseExceptions::incrementAndGet, failedCloseRestored::incrementAndGet);
        try {
            failedClose.get();
            expect(false, "scope close exception propagates", "failed close is visible");
        } catch (IllegalStateException expected) {
            expect("close-fixture", expected.getMessage(), "scope close failure is preserved");
        }
        expect(1, failedCloseExceptions.get(), "scope close failure is an exceptional supplier completion");
        expect(0, failedCloseRestored.get(), "failed close is not reported as a restored scope");

        AtomicInteger normalCompleted = new AtomicInteger();
        var normal = GpuTaskScopeCarrier.wrap(
                () -> "ok", () -> () -> { }, scope -> { }, bucket,
                new GpuTaskScopeCarrier.Lifecycle(), () -> { }, normalCompleted::incrementAndGet,
                () -> { }, () -> { });
        expect("ok", normal.get(), "normal async wrapper returns the original result");
        expect(1, normalCompleted.get(), "normal completion is counted separately");
    }

    private static void expect(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expect(boolean expected, String label, String message) {
        if (!expected) throw new AssertionError(label + ": " + message);
    }

    private record FixturePermit(GpuWorldgenPolicy.GeneratorKind kind, boolean contextLive,
                                 boolean gpuEnabled, boolean deviceAvailable, Object worldScopeIdentity)
            implements GpuWorldgenPolicy.DispatchPermit {
        @Override public Object generatorIdentity() { return this; }
        @Override public Object randomStateIdentity() { return this; }
        @Override public Object chunkIdentity() { return this; }
        @Override public Object routerIdentity() { return this; }
    }
}
