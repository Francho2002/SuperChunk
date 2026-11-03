package dev.superchunk.gpu.dfc;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic lifecycle regression fixture; creates worker threads but never initializes OpenCL/Minecraft. */
public final class GpuChunkBatcherLifecycleRegressionTest {
    private static final long TIMEOUT_SECONDS = 5;

    private GpuChunkBatcherLifecycleRegressionTest() { }

    public static void main(String[] args) throws Exception {
        checkJoinGuard();
        retirementWinsAndSharesCompletion();
        singletonInstallWaitsForOwnerQuiescence();
        shutdownWinsWithoutASecondClose();
        backendWaitsForRetirementAndVetoesFailedClose();
        System.out.println("GpuChunkBatcher lifecycle: retirement, shutdown, close failure and join checks passed");
    }

    private static void checkJoinGuard() {
        Thread current = Thread.currentThread();
        Thread other = new Thread(() -> { }, "not-started-regression-thread");
        check(!GpuChunkBatcher.shouldJoin(current, current),
                "retirement running on the drainer must skip self-join");
        check(GpuChunkBatcher.shouldJoin(current, other),
                "retirement helper must join a distinct drainer thread");
        check(!GpuChunkBatcher.shouldJoin(current, null), "missing thread must not be joined");
    }

    private static void retirementWinsAndSharesCompletion() throws Exception {
        CountDownLatch drainerPaused = new CountDownLatch(1);
        CountDownLatch allowDrainer = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger dispatchAttempts = new AtomicInteger();
        GpuChunkBatcher batcher = GpuChunkBatcher.createLifecycleTestBatcher(
                () -> {
                    closes.incrementAndGet();
                    closeEntered.countDown();
                    awaitLatch(allowClose);
                },
                () -> pauseDrainer(drainerPaused, allowDrainer),
                dispatchAttempts::incrementAndGet);
        await(drainerPaused, "drainer did not enter the deterministic pause");
        var queued = batcher.enqueueSyntheticForLifecycleTest();
        check(!queued.isDone(), "synthetic request must stay queued until owner retirement resumes the drainer");

        GpuChunkBatcher.RetirementRequest first = batcher.requestOwnerRetirement();
        check(first.disposition() == GpuChunkBatcher.RetirementDisposition.STARTED,
                "first owner retirement must start the lifecycle");
        check(batcher.ownerRetirementRequestedForLifecycleTest(),
                "first owner retirement must close admission immediately");
        check(!queued.isDone(), "queued request must remain held while the drainer is paused");
        allowDrainer.countDown();
        expectExceptional(queued, "retired queued work must resolve exceptionally instead of dispatching");
        await(closeEntered, "retirement did not reach the dispatcher closer");
        check(dispatchAttempts.get() == 0,
                "owner retirement must divert queued primary work before dispatch");

        GpuChunkBatcher.RetirementRequest duplicate = batcher.requestOwnerRetirement();
        check(duplicate.disposition() == GpuChunkBatcher.RetirementDisposition.ALREADY_RETIRING,
                "duplicate retirement must join the existing lifecycle");
        check(first.completion() == duplicate.completion(),
                "duplicate retirement must receive the identical completion stage");

        AtomicBoolean shutdownReturned = new AtomicBoolean();
        Thread shutdownCaller = new Thread(() -> {
            batcher.shutdown();
            shutdownReturned.set(true);
        }, "batcher-shutdown-waiter-retirement-first");
        shutdownCaller.start();
        check(!shutdownReturned.get(), "shutdown must wait while retirement close is blocked");

        allowClose.countDown();
        check(result(first.completion()) == GpuChunkBatcher.RetirementResult.QUIESCENT,
                "successful retirement must report quiescence");
        shutdownCaller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        check(!shutdownCaller.isAlive() && shutdownReturned.get(),
                "shutdown waiter must return after shared lifecycle completion");
        check(closes.get() == 1, "retirement and shutdown must close the dispatcher exactly once");

        GpuChunkBatcher.RetirementRequest after = batcher.requestOwnerRetirement();
        check(after.disposition() == GpuChunkBatcher.RetirementDisposition.ALREADY_COMPLETE,
                "request after completion must report ALREADY_COMPLETE");
        check(after.completion() == first.completion(), "completed requests must preserve the shared stage");
    }

    private static void shutdownWinsWithoutASecondClose() throws Exception {
        CountDownLatch drainerPaused = new CountDownLatch(1);
        CountDownLatch allowDrainer = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger dispatchAttempts = new AtomicInteger();
        GpuChunkBatcher batcher = GpuChunkBatcher.createLifecycleTestBatcher(
                () -> {
                    closes.incrementAndGet();
                    closeEntered.countDown();
                    awaitLatch(allowClose);
                },
                () -> pauseDrainer(drainerPaused, allowDrainer),
                dispatchAttempts::incrementAndGet);
        await(drainerPaused, "drainer did not enter the deterministic pause");
        var queued = batcher.enqueueSyntheticForLifecycleTest();
        check(!queued.isDone(), "synthetic request must stay queued while the drainer is paused");

        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        Thread shutdownCaller = new Thread(() -> {
            try {
                batcher.shutdown();
            } catch (Throwable failure) {
                shutdownFailure.set(failure);
            }
        }, "batcher-shutdown-owner");
        shutdownCaller.start();
        awaitShutdownStarted(batcher);

        GpuChunkBatcher.RetirementRequest late = batcher.requestOwnerRetirement();
        check(late.disposition() == GpuChunkBatcher.RetirementDisposition.SHUTDOWN_ALREADY_STARTED,
                "owner retirement after normal shutdown starts must join that shutdown");
        check(batcher.ownerRetirementRequestedForLifecycleTest(),
                "late owner retirement must close the primary dispatch gate immediately");
        GpuChunkBatcher.RetirementRequest duplicate = batcher.requestOwnerRetirement();
        check(late.completion() == duplicate.completion(),
                "late retirement requests must share the shutdown completion stage");
        check(!queued.isDone(), "queued work must remain held until the drainer resumes");

        allowDrainer.countDown();
        expectExceptional(queued, "retired queued work must resolve exceptionally instead of dispatching");
        await(closeEntered, "normal shutdown did not reach the dispatcher closer");
        check(dispatchAttempts.get() == 0,
                "owner retirement during shutdown must divert queued primary work before dispatch");
        check(closes.get() == 1, "retirement must not repeat a close owned by shutdown");
        allowClose.countDown();
        check(result(late.completion()) == GpuChunkBatcher.RetirementResult.QUIESCENT,
                "normal shutdown must publish quiescence");
        shutdownCaller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        check(!shutdownCaller.isAlive(), "normal shutdown thread must finish");
        check(shutdownFailure.get() == null, "normal shutdown must not throw: " + shutdownFailure.get());
        check(closes.get() == 1, "normal shutdown and later retirement must close once");
    }

    private static void singletonInstallWaitsForOwnerQuiescence() throws Exception {
        CountDownLatch closeAEntered = new CountDownLatch(1);
        CountDownLatch allowCloseA = new CountDownLatch(1);
        AtomicInteger closesA = new AtomicInteger();
        AtomicLong creationsA = new AtomicLong();
        GpuChunkBatcher ownerA = GpuChunkBatcher.installLifecycleTestSharedBatcher(() -> {
            closesA.incrementAndGet();
            closeAEntered.countDown();
            awaitLatch(allowCloseA);
        }, creationsA);
        check(ownerA != null && GpuChunkBatcher.shared() == ownerA,
                "test owner A must occupy the shared slot");

        GpuChunkBatcher.RetirementRequest retirementA = ownerA.requestOwnerRetirement();
        await(closeAEntered, "owner A retirement did not reach its fake dispatcher close");

        CountDownLatch installBStarted = new CountDownLatch(1);
        CountDownLatch installBDone = new CountDownLatch(1);
        AtomicInteger closesB = new AtomicInteger();
        AtomicLong creationsB = new AtomicLong();
        AtomicReference<GpuChunkBatcher> ownerBResult = new AtomicReference<>();
        AtomicReference<Throwable> installFailure = new AtomicReference<>();
        Thread installB = new Thread(() -> {
            installBStarted.countDown();
            try {
                ownerBResult.set(GpuChunkBatcher.initLifecycleTestSharedBatcher(
                        closesB::incrementAndGet, creationsB));
            } catch (Throwable failure) {
                installFailure.set(failure);
            } finally {
                installBDone.countDown();
            }
        }, "batcher-singleton-install-waiter");
        installB.start();
        await(installBStarted, "owner B installer did not start");

        awaitSharedSlotEmpty(); // init(B) detached A, then waits on A's same completion stage
        check(!retirementA.completion().toCompletableFuture().isDone(),
                "A must remain non-quiescent while its close latch is held");
        check(creationsB.get() == 0 && ownerBResult.get() == null,
                "B must not be constructed or published before A is quiescent");
        check(GpuChunkBatcher.shared() == null,
                "the singleton slot must stay empty while A retires");

        allowCloseA.countDown();
        check(result(retirementA.completion()) == GpuChunkBatcher.RetirementResult.QUIESCENT,
                "A must finish quiescing before B can install");
        await(installBDone, "owner B did not install after A completed");
        installB.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        check(!installB.isAlive() && installFailure.get() == null,
                "owner B initialization must complete without error");
        GpuChunkBatcher ownerB = ownerBResult.get();
        check(ownerB != null && GpuChunkBatcher.shared() == ownerB,
                "B must become the sole shared batcher after A quiesces");
        check(creationsA.get() == 1 && creationsB.get() == 1,
                "the shared-slot install gate must create A and B exactly once");
        check(closesA.get() == 1, "A's dispatcher must close exactly once");

        GpuChunkBatcher.shutdownShared();
        check(GpuChunkBatcher.shared() == null, "shutdown must clear B from the shared slot");
        check(closesB.get() == 1, "B's dispatcher must close once during fixture cleanup");
    }

    private static void backendWaitsForRetirementAndVetoesFailedClose() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        GpuChunkBatcher batcher = GpuChunkBatcher.createLifecycleTestBatcher(() -> {
            closes.incrementAndGet();
            closeEntered.countDown();
            awaitLatch(allowClose);
            throw new IllegalStateException("fixture close failure");
        });
        AtomicInteger otherBatcherCloses = new AtomicInteger();
        GpuChunkBatcher otherLiveBatcher = GpuChunkBatcher.createLifecycleTestBatcher(otherBatcherCloses::incrementAndGet);
        GpuChunkBatcher.RetirementRequest first = batcher.requestOwnerRetirement();
        await(closeEntered, "failing retirement did not reach the dispatcher closer");

        AtomicReference<Boolean> backendResult = new AtomicReference<>();
        CountDownLatch backendReturned = new CountDownLatch(1);
        Thread backendShutdown = new Thread(() -> {
            backendResult.set(GpuChunkBatcher.shutdownBackend());
            backendReturned.countDown();
        }, "batcher-backend-shutdown-barrier");
        backendShutdown.start();
        awaitBackendTerminal();
        check(!backendReturned.await(100, TimeUnit.MILLISECONDS),
                "backend shutdown must await an owner lifecycle in its atomic snapshot");

        GpuChunkBatcher.RetirementRequest duringBackend = batcher.requestOwnerRetirement();
        check(duringBackend.disposition() == GpuChunkBatcher.RetirementDisposition.ALREADY_RETIRING,
                "backend terminal transition must not create a second retirement");
        check(duringBackend.completion() == first.completion(),
                "backend wait and owner retirement must observe the same completion");
        GpuChunkBatcher.RetirementRequest otherDuringBackend = otherLiveBatcher.requestOwnerRetirement();
        check(otherDuringBackend.disposition() == GpuChunkBatcher.RetirementDisposition.SHUTDOWN_ALREADY_STARTED
                        || otherDuringBackend.disposition() == GpuChunkBatcher.RetirementDisposition.ALREADY_COMPLETE,
                "backend snapshot must transition every live batcher before terminal requests arrive");

        allowClose.countDown();
        check(result(first.completion()) == GpuChunkBatcher.RetirementResult.CLOSE_FAILED,
                "failed dispatcher close must be terminal");
        check(GpuChunkBatcher.retainsFailedCloseOwner(batcher),
                "dispatcher owner must remain strongly retained after close failure");
        backendShutdown.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        check(!backendShutdown.isAlive(), "backend barrier must finish after owner close resolves");
        check(Boolean.FALSE.equals(backendResult.get()),
                "failed dispatcher close must veto backend native teardown");
        check(result(otherDuringBackend.completion()) == GpuChunkBatcher.RetirementResult.QUIESCENT,
                "an unrelated live batcher must also complete before backend teardown returns");
        check(otherBatcherCloses.get() == 1, "backend must close each live primary dispatcher once");
        GpuChunkBatcher.RetirementRequest after = batcher.requestOwnerRetirement();
        check(after.disposition() == GpuChunkBatcher.RetirementDisposition.ALREADY_COMPLETE,
                "failed close must still complete the lifecycle exactly once");
        check(after.completion() == first.completion(), "close failure must retain the same completion stage");
        check(closes.get() == 1, "failed dispatcher close must never be retried");
        check(GpuChunkBatcher.createDedicated("must-not-start-after-backend-shutdown") == null,
                "dedicated batcher creation after backend terminal must fall back without starting workers");
    }

    private static void awaitBackendTerminal() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!GpuChunkBatcher.backendShutdownRequested() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        check(GpuChunkBatcher.backendShutdownRequested(), "backend shutdown did not enter its terminal barrier");
    }

    private static void awaitShutdownStarted(GpuChunkBatcher batcher) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!batcher.shutdownStartedForLifecycleTest() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        check(batcher.shutdownStartedForLifecycleTest(), "normal shutdown did not begin");
    }

    private static void awaitSharedSlotEmpty() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (GpuChunkBatcher.shared() != null && System.nanoTime() < deadline) {
            Thread.yield();
        }
        check(GpuChunkBatcher.shared() == null,
                "replacement init did not detach the retiring shared batcher");
    }

    private static void pauseDrainer(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        try {
            while (release.getCount() != 0 && System.nanoTime() < deadline) {
                try {
                    release.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (release.getCount() != 0) {
                throw new AssertionError("fixture timed out waiting to resume the drainer");
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void expectExceptional(CompletionStage<double[]> stage, String message) throws Exception {
        try {
            stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            throw new AssertionError(message);
        } catch (ExecutionException expected) {
            // The retirement gate must resolve the queued future with the existing CPU fallback signal.
        }
    }

    private static GpuChunkBatcher.RetirementResult result(CompletionStage<GpuChunkBatcher.RetirementResult> stage)
            throws Exception {
        return stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), message);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("fixture timed out waiting to release the fake dispatcher close");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("fixture close was interrupted", interrupted);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
