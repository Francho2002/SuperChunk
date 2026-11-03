package dev.superchunk.gpu.dfc;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Wraps an asynchronous task so its context scope is opened and closed on the thread
 * that actually runs the task. The task and scope factory are captured by the returned
 * supplier; no ThreadLocal inheritance across executors is assumed.
 */
public final class GpuTaskScopeCarrier {

    @FunctionalInterface
    public interface TaskScope extends AutoCloseable {
        @Override
        void close();
    }

    @FunctionalInterface
    public interface AsyncSubmitter<T> {
        CompletableFuture<T> submit(Supplier<T> supplier);
    }

    /**
     * Observes only cancellation of the returned future before the supplier wrapper starts.
     * It does not cancel work or alter CompletableFuture completion semantics.
     */
    public static final class Lifecycle {
        private boolean started;
        private boolean cancellationRecorded;

        public synchronized void markStarted() {
            started = true;
        }

        public void observeCancellation(CompletableFuture<?> future, Runnable onCancelledBeforeStart) {
            Objects.requireNonNull(future, "future");
            Objects.requireNonNull(onCancelledBeforeStart, "onCancelledBeforeStart");
            future.whenComplete((ignored, failure) -> {
                if (!future.isCancelled()) return;
                boolean record;
                synchronized (Lifecycle.this) {
                    record = !started && !cancellationRecorded;
                    if (record) cancellationRecorded = true;
                }
                if (record) onCancelledBeforeStart.run();
            });
        }
    }

    /** Submits the exact wrapped supplier and observes the returned future without replacing it. */
    public static <T> CompletableFuture<T> submit(
            AsyncSubmitter<T> submitter, Supplier<T> supplier, Lifecycle lifecycle,
            Runnable onSubmitted, Runnable onSubmissionFailure, Runnable onFutureReturned,
            Runnable onCancelledBeforeStart) {
        Objects.requireNonNull(submitter, "submitter");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(onSubmitted, "onSubmitted");
        Objects.requireNonNull(onSubmissionFailure, "onSubmissionFailure");
        Objects.requireNonNull(onFutureReturned, "onFutureReturned");
        Objects.requireNonNull(onCancelledBeforeStart, "onCancelledBeforeStart");
        final CompletableFuture<T> future;
        try {
            future = submitter.submit(supplier);
        } catch (RuntimeException | Error submissionFailure) {
            onSubmissionFailure.run();
            throw submissionFailure;
        }
        onSubmitted.run();
        onFutureReturned.run();
        if (future != null && GpuAuthorizationDiagnostics.enabled()) {
            lifecycle.observeCancellation(future, onCancelledBeforeStart);
        }
        return future;
    }

    private GpuTaskScopeCarrier() {
    }

    /** Compatibility overload for pure scope-carrier fixtures without lifecycle counters. */
    public static <T, S extends TaskScope> Supplier<T> wrap(
            Supplier<T> task,
            Supplier<S> enterScope,
            Consumer<S> onScopeEntered,
            Runnable onTaskStarted,
            Runnable onScopeRestored) {
        return wrap(task, enterScope, onScopeEntered, GpuAuthorizationDiagnostics.WorldBucket.UNKNOWN,
                new Lifecycle(), onTaskStarted, () -> { }, () -> { }, onScopeRestored);
    }

    public static <T, S extends TaskScope> Supplier<T> wrap(
            Supplier<T> task,
            Supplier<S> enterScope,
            Consumer<S> onScopeEntered,
            GpuAuthorizationDiagnostics.WorldBucket bucket,
            Lifecycle lifecycle,
            Runnable onTaskStarted,
            Runnable onTaskCompleted,
            Runnable onTaskException,
            Runnable onScopeRestored) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(enterScope, "enterScope");
        Objects.requireNonNull(onScopeEntered, "onScopeEntered");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(onTaskStarted, "onTaskStarted");
        Objects.requireNonNull(onTaskCompleted, "onTaskCompleted");
        Objects.requireNonNull(onTaskException, "onTaskException");
        Objects.requireNonNull(onScopeRestored, "onScopeRestored");
        return () -> {
            try (GpuAuthorizationDiagnostics.DiagnosticScope ignored =
                         GpuAuthorizationDiagnostics.openWorldScope(bucket)) {
                if (GpuAuthorizationDiagnostics.enabled()) lifecycle.markStarted();
                T result;
                try {
                    onTaskStarted.run();
                    S scope = enterScope.get();
                    try (TaskScope observedScope = () -> {
                        scope.close();
                        onScopeRestored.run();
                    }) {
                        onScopeEntered.accept(scope);
                        result = task.get();
                    }
                } catch (RuntimeException | Error failure) {
                    onTaskException.run();
                    throw failure;
                }
                onTaskCompleted.run();
                return result;
            }
        };
    }
}
