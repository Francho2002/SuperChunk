package dev.superchunk.gpu.dfc;

import dev.superchunk.gpu.OpenCLBackend;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * SuperChunk GPU — thread-safe cross-chunk <b>AGGREGATOR</b> (producer/consumer).
 *
 * <p>Pools many concurrent per-chunk density-grid requests and serves them with as
 * few batched GPU dispatches as possible, returning a {@link CompletableFuture} per
 * chunk. Submitters (worldgen worker threads, or the self-test's executor) merely
 * {@link #submit} an origin + geometry and get a future back; they do NOT touch the
 * GPU.
 *
 * <p><b>PIPELINED (GPU-throughput rework).</b> Two threads now drive the dispatcher's
 * slot ring: the DRAINER collects/buckets requests and {@link
 * GpuBatchDispatcher#enqueueBatch enqueues} batches WITHOUT waiting (up to the ring's
 * {@code gpu.batchPipelineDepth} batches in flight), and the COMPLETER waits each
 * batch's readback event in FIFO order, copies the per-chunk slices straight out of
 * the slot's pinned staging, completes the futures, and recycles the slot. The GPU
 * therefore never idles while host-side collection/slicing/completion runs, and the
 * drainer only blocks when the GPU is a full pipeline behind — exactly when pooled
 * requests should be forming bigger batches anyway. Workers still never touch the
 * driver.
 *
 * <p><b>PROGRAM SLOTS.</b> A batcher carries an optional PRIMARY dispatcher (slot for
 * the density fused group — {@link #submit}/{@link #submitCompact}) and can serve any
 * number of AUX program slots via {@link #submitTo}: each request may name its own
 * {@link GpuBatchDispatcher} (e.g. the per-dimension CLIMATE fused group's
 * {@code df_batch_lattice_multichunk} program), and requests only ever share a batch
 * with requests targeting the SAME dispatcher (the target is part of the grouping
 * key). All driver calls still happen on this ONE drainer thread — submitters never
 * touch the GPU regardless of slot. Aux dispatchers are owned (closed) by their
 * registrants, NOT by {@link #shutdown()}.
 *
 * <p><b>Grouping + flush policy.</b> Requests are grouped by their geometry key
     * {@code (target,worldScope,strx,stry,strz,dimX,dimY,dimZ)} — only same-target,
     * same-scope, same-geometry requests can share one batch (all chunks in one world
     * share one geometry, so in practice there is one group per program slot and scope).
 * A group is flushed when it reaches {@code batchLimit} pending requests OR
 * {@code batchWindowMicros} have elapsed since its oldest pending request — whichever
 * comes first. On flush, up to {@code batchLimit} chunks' origins are packed into an
 * {@code int[K*3]}, dispatched in ONE {@code batchFill}, and each chunk's future is
 * completed with its {@code M*n} slice ({@code out[(c*M+k)*n + i]} -> future result
 * {@code [k*n + i]}), where {@code M = }{@link GpuBatchDispatcher#rootCount()} of the
 * batch's target dispatcher.
 *
 * <p><b>Stage scope.</b> This is standalone infrastructure plus a multi-threaded
 * self-test ({@link GpuVanillaParityTest}) ONLY — it is NOT wired into the live
 * worldgen pipeline (zero risk to terrain). Gated by the {@code batchChunks} family of
 * config flags for the next stage. Never throws across {@link #submit}; a dispatch
 * failure completes the affected futures exceptionally rather than hanging them.
 */
public final class GpuChunkBatcher implements AutoCloseable {

    /**
     * Kill switch for the dead-grid skip ({@code -Dsuperchunk.gpu.keepDeadGrids=true}
     * restores the old always-read-and-copy behaviour). Terrain cannot depend on it —
     * a chunk that unexpectedly wants grids finds none and takes the existing
     * per-chunk dispatch fallback — but the switch makes an A/B one flag wide.
     */
    private static final boolean SKIP_DEAD_GRIDS = !Boolean.getBoolean("superchunk.gpu.keepDeadGrids");
    /** Shared sentinel handed to futures whose corner grids are provably unread. */
    static final double[] EMPTY_GRIDS = new double[0];

    /** True iff {@code grids} is the dead-grid sentinel (identity, not a length heuristic). */
    static boolean isDeadGrids(double[] grids) {
        return grids == EMPTY_GRIDS;
    }

    /**
     * Nothing can send this chunk down the original per-block loop, so its 78.4 KB corner slice
     * is provably unread and need not be copied out of pinned staging.
     *
     * <p>{@link CompactConsume#willConsume()} is the global half of {@code consumeFill}'s entry
     * gate (mode, sticky-disable, and the unbindable-foreign-Lithium case); the rest is per
     * chunk. Deliberately conservative — every uncertain term falls to false and keeps the grids.
     * Evaluated per chunk on purpose: a sticky-disable landing mid-batch must be seen by the
     * chunks after it.
     */
    private static boolean gridsAreDead(Req r) {
        return SKIP_DEAD_GRIDS
                && CompactConsume.willConsume()
                && r.ids != null
                && r.aux != null
                && !r.aux.beardOnCpu();
    }

    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /** Bounded submit queue capacity (producer backpressure). */
    private static final int MAX_QUEUE = 1 << 16;
    /** Idle park when no group is pending; a submit wakes the drainer immediately anyway. */
    private static final long IDLE_PARK_NANOS = 10_000_000L; // 10 ms
    /** Live-path singleton for the next stage (and the {@link OpenCLBackend#shutdown()} hook). */
    private static volatile GpuChunkBatcher SHARED;
    /** Once backend teardown starts, callbacks may not install a replacement singleton. */
    private static final AtomicBoolean BACKEND_SHUTDOWN = new AtomicBoolean();
    private static final Object SHARED_LIFECYCLE_LOCK = new Object();
    /** Every batcher lifecycle shutdown/retirement in progress; backend snapshots under the same lock. */
    private static final java.util.Set<GpuChunkBatcher> ACTIVE_LIFECYCLES = ConcurrentHashMap.newKeySet();
    /** Every started batcher, including dedicated climate instances, participates in backend teardown. */
    private static final java.util.Set<GpuChunkBatcher> LIVE_BATCHERS = ConcurrentHashMap.newKeySet();
    /** Failed close is terminal; retain its owner and native handles without retrying close(). */
    private static final java.util.Set<GpuChunkBatcher> FAILED_CLOSE_BATCHERS = ConcurrentHashMap.newKeySet();
    /** A failed native-dispatcher close permanently vetoes further batcher/backend teardown. */
    private static final AtomicBoolean DISPATCHER_CLOSE_FAILED = new AtomicBoolean();

    /**
     * The PRIMARY program slot ({@link #submit}/{@link #submitCompact} target), or
     * {@code null} for a dedicated aux-only batcher ({@link #createDedicated}) whose
     * every request names its own dispatcher via {@link #submitTo}. Owned by this
     * batcher: closed in {@link #shutdown()} when present.
     */
    private final GpuBatchDispatcher dispatcher;
    /** Close action is injectable only for the standalone lifecycle regression fixture. */
    private final Runnable primaryDispatcherCloser;
    /** Null in production; test-only gates let the lifecycle fixture hold queued work deterministically. */
    private final Runnable beforeDrainLoopForTest;
    private final Runnable beforeBatchDispatchForTest;
    /** Scope whose fused ASTs the primary dispatcher was compiled for; null for synthetic/unscoped instances. */
    private final GpuWorldgenPolicy.WorldScope ownerWorldScope;
    private final int batchLimit;         // max chunks coalesced into one dispatch
    private final long batchWindowNanos;  // max time a request waits before its group flushes

    private final LinkedBlockingQueue<Req> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread drainer;

    // Pipeline handoff: enqueued batches (drainer) -> completion (completer thread).
    // Effectively bounded by the dispatcher slot ring (enqueueBatch blocks on slots).
    private final LinkedBlockingQueue<PendingBatch> completionQueue = new LinkedBlockingQueue<>();
    private final Thread completer;
    /** Serializes the last retirement check with dispatcher enqueue. */
    private final Object dispatchBoundary = new Object();
    private final Object lifecycleBoundary = new Object();
    private final AtomicBoolean ownerRetirementRequested = new AtomicBoolean();
    private final AtomicBoolean retirementOnDrainer = new AtomicBoolean();
    private final CountDownLatch retirementModeChosen = new CountDownLatch(1);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final AtomicBoolean primaryDispatcherCloseAttempted = new AtomicBoolean();
    private volatile boolean primaryDispatcherCloseSucceeded;
    private final CompletableFuture<RetirementResult> lifecycleCompletion = new CompletableFuture<>();
    /** Sentinel pushed after the drainer joined; the completer drains then exits. */
    private static final PendingBatch POISON = new PendingBatch(null, null, 0, 0, false);

    // Drainer-thread-only scratch (no concurrency).
    private final Req[] liveScratch;
    // Grow-only origin scratch reused across dispatchBatch calls (the dispatcher copies
    // it into slot staging synchronously during enqueueBatch, so reuse is safe). Never
    // shrinks, so enqueueBatch may receive an array LONGER than K*3 (guards relaxed to
    // <). Only the drainer thread touches this.
    private int[] originScratch;

    // Observability counters.
    private final AtomicLong requestCount = new AtomicLong();   // submit() calls accepted
    private final AtomicLong rejectedCount = new AtomicLong();  // submit() calls REJECTED (queue full / not running)
    private final AtomicLong dispatchCount = new AtomicLong();  // batchFill() calls issued
    private final AtomicLong servedCount = new AtomicLong();    // chunks completed OK
    private final AtomicLong batchSizeSum = new AtomicLong();   // sum of K over all dispatches (for mean)
    private final AtomicLong maxBatchSize = new AtomicLong();   // largest K observed
    private final AtomicLong deadGridsSkipped = new AtomicLong(); // fast-path chunks whose corner slice nobody reads
    /**
     * Cell-corner COLUMN redundancy inside a batch (diagnostic).
     *
     * <p>A chunk's corner lattice is {@code dimX x dimZ} columns wide where
     * {@code (dimX-1)*strx == 16} — i.e. 5x5 columns for a 4-block cell, whose last row and
     * column sit exactly ON the neighbouring chunk's first. Two adjacent chunks in the same
     * batch therefore evaluate that shared column's whole {@code dimY}-tall stack of density
     * functions TWICE. In a fully tiled sweep only 16 of each chunk's 25 columns are new, so
     * up to 36% of the corner kernel — which is ~73% of all GPU time — is duplicate work.
     *
     * <p>Whether that bound is reachable depends entirely on how spatially clustered the
     * chunks in one batch actually are, which is a property of Chunky's traversal and the
     * batching window, not of the geometry. So: measure before building anything.
     */
    private final AtomicLong colTotal = new AtomicLong();
    private final AtomicLong colUnique = new AtomicLong();
    private long[] colScratch = null;   // drainer-thread only
    /** Achieved batch-size histogram: bins 1, 2-4, 5-8, 9-16, 17-32, 33-64, 65+. */
    private final AtomicLong[] batchHistogram = new AtomicLong[]{
            new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(),
            new AtomicLong(), new AtomicLong(), new AtomicLong()};

    // Pipeline observability: host-side enqueue time, completer GPU-wait time, slice/
    // complete time, and the in-flight depth sampled at each enqueue (bins 1,2,3,4+).
    private final AtomicLong pipeEnqueueNanos = new AtomicLong();
    private final AtomicLong pipeAwaitNanos = new AtomicLong();
    private final AtomicLong pipeSliceNanos = new AtomicLong();
    private final AtomicLong[] inFlightHistogram = new AtomicLong[]{
            new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};

    private GpuChunkBatcher(GpuBatchDispatcher dispatcher, int batchLimit, long batchWindowMicros,
                            String threadName, GpuWorldgenPolicy.WorldScope ownerWorldScope) {
        this(dispatcher, dispatcher == null ? null : dispatcher::close, batchLimit,
                batchWindowMicros, threadName, ownerWorldScope);
    }

    private GpuChunkBatcher(GpuBatchDispatcher dispatcher, Runnable primaryDispatcherCloser,
                            int batchLimit, long batchWindowMicros,
                            String threadName, GpuWorldgenPolicy.WorldScope ownerWorldScope) {
        this(dispatcher, primaryDispatcherCloser, batchLimit, batchWindowMicros,
                threadName, ownerWorldScope, null, null);
    }

    private GpuChunkBatcher(GpuBatchDispatcher dispatcher, Runnable primaryDispatcherCloser,
                            int batchLimit, long batchWindowMicros,
                            String threadName, GpuWorldgenPolicy.WorldScope ownerWorldScope,
                            Runnable beforeDrainLoopForTest, Runnable beforeBatchDispatchForTest) {
        this.dispatcher = dispatcher;
        this.primaryDispatcherCloser = primaryDispatcherCloser;
        this.beforeDrainLoopForTest = beforeDrainLoopForTest;
        this.beforeBatchDispatchForTest = beforeBatchDispatchForTest;
        this.ownerWorldScope = ownerWorldScope;
        this.primaryDispatcherCloseSucceeded = primaryDispatcherCloser == null;
        this.batchLimit = Math.max(1, batchLimit);
        this.batchWindowNanos = Math.max(0L, batchWindowMicros) * 1_000L;
        this.liveScratch = new Req[this.batchLimit];
        this.drainer = new Thread(this::drainLoop, threadName);
        this.drainer.setDaemon(true);
        this.completer = new Thread(this::completeLoop, threadName + "-completer");
        this.completer.setDaemon(true);
        synchronized (SHARED_LIFECYCLE_LOCK) {
            if (BACKEND_SHUTDOWN.get()) {
                throw new IllegalStateException("OpenCL batcher backend is already shutting down");
            }
            // Register before starting either thread while holding the same barrier used
            // by shutdownBackend's live-owner snapshot.
            LIVE_BATCHERS.add(this);
            this.completer.start();
            this.drainer.start();
        }
    }

    // ----------------------------------------------------------------------
    // Lifecycle (static singleton for the live path + the backend shutdown hook).
    // ----------------------------------------------------------------------

    public enum RetirementDisposition {
        STARTED,
        ALREADY_RETIRING,
        SHUTDOWN_ALREADY_STARTED,
        ALREADY_COMPLETE,
        BACKEND_TERMINAL
    }

    public enum RetirementResult {
        QUIESCENT,
        CLOSE_FAILED
    }

    /** Idempotent retirement request plus the one lifecycle result shared by all callers. */
    public record RetirementRequest(RetirementDisposition disposition,
                                    CompletionStage<RetirementResult> completion) { }

    /**
     * Creates the shared batcher over {@code dispatcher}, reading {@code batchLimit} /
     * {@code batchWindowMicros} from {@link dev.superchunk.gpu.GpuConfig}. Replaces any
     * existing shared batcher (shutting it down first). Returns the new instance.
     */
    public static GpuChunkBatcher init(GpuBatchDispatcher dispatcher) {
        return init(dispatcher, configBatchLimit(), configWindowMicros(), null);
    }

    /** Creates the shared batcher with explicit knobs. Replaces any existing one. */
    public static GpuChunkBatcher init(GpuBatchDispatcher dispatcher, int batchLimit, long batchWindowMicros) {
        return init(dispatcher, batchLimit, batchWindowMicros, null);
    }

    /** Creates a world-owned shared batcher whose every primary request carries this scope. */
    public static GpuChunkBatcher init(GpuBatchDispatcher dispatcher,
                                       GpuWorldgenPolicy.WorldScope ownerWorldScope) {
        return init(dispatcher, configBatchLimit(), configWindowMicros(), ownerWorldScope);
    }

    private static GpuChunkBatcher init(GpuBatchDispatcher dispatcher, int batchLimit,
                                        long batchWindowMicros,
                                        GpuWorldgenPolicy.WorldScope ownerWorldScope) {
        if (dispatcher == null) {
            return null;
        }
        return initSharedBatcher(() -> new GpuChunkBatcher(dispatcher, batchLimit,
                batchWindowMicros, "SuperChunk-GPU-batcher", ownerWorldScope));
    }

    /** Shared creation/replacement loop used by production init and the local lifecycle fixture. */
    private static GpuChunkBatcher initSharedBatcher(Supplier<GpuChunkBatcher> factory) {
        shutdownShared();
        while (true) {
            java.util.List<GpuChunkBatcher> pending;
            synchronized (SHARED_LIFECYCLE_LOCK) {
                if (BACKEND_SHUTDOWN.get() || DISPATCHER_CLOSE_FAILED.get()) return null;
                if (SHARED != null) return null;
                if (ACTIVE_LIFECYCLES.isEmpty()) {
                    return installSharedBatcherLocked(factory);
                }
                pending = java.util.List.copyOf(ACTIVE_LIFECYCLES);
            }
            // Never hold the singleton lock while waiting for accepted CL work or callbacks.
            for (GpuChunkBatcher batcher : pending) batcher.awaitLifecycleCompletion();
        }
    }

    /** Caller holds SHARED_LIFECYCLE_LOCK; this is the single atomic shared-slot install gate. */
    private static GpuChunkBatcher installSharedBatcherLocked(Supplier<GpuChunkBatcher> factory) {
        if (BACKEND_SHUTDOWN.get() || DISPATCHER_CLOSE_FAILED.get()
                || SHARED != null || !ACTIVE_LIFECYCLES.isEmpty()) return null;
        GpuChunkBatcher batcher = factory.get();
        if (batcher != null) SHARED = batcher;
        return batcher;
    }

    /**
     * Creates a DEDICATED batcher with NO primary dispatcher — every request must name
     * its program slot via {@link #submitTo}. Does NOT touch {@link #SHARED} (the caller
     * owns the instance and must {@link #shutdown()} it; aux dispatchers are the caller's
     * to close). Reads {@code batchLimit}/{@code batchWindowMicros} from the config like
     * {@link #init(GpuBatchDispatcher)}. Used by the biome-climate cross-chunk batching
     * ({@code BiomeClimateCache}), whose lifecycle (RandomState build) is independent of
     * the density batcher's ({@code batchChunks} + fused-group discovery).
     */
    /** Returns null after backend teardown begins; no worker is started in that case. */
    public static GpuChunkBatcher createDedicated(String threadName) {
        synchronized (SHARED_LIFECYCLE_LOCK) {
            if (BACKEND_SHUTDOWN.get()) return null;
            return new GpuChunkBatcher(null, configBatchLimit(), configWindowMicros(), threadName, null);
        }
    }

    /** Creates a real worker pair with a fake close action for the non-Minecraft lifecycle fixture. */
    static GpuChunkBatcher createLifecycleTestBatcher(Runnable closeAction) {
        return createLifecycleTestBatcher(closeAction, null, null);
    }

    static GpuChunkBatcher createLifecycleTestBatcher(Runnable closeAction,
                                                      Runnable beforeDrainLoop,
                                                      Runnable beforeBatchDispatch) {
        if (closeAction == null) throw new IllegalArgumentException("closeAction must not be null");
        synchronized (SHARED_LIFECYCLE_LOCK) {
            if (BACKEND_SHUTDOWN.get()) throw new IllegalStateException("backend is terminal in fixture JVM");
            return new GpuChunkBatcher(null, closeAction, 1, 0L,
                    "SuperChunk-GPU-batcher-lifecycle-fixture", null,
                    beforeDrainLoop, beforeBatchDispatch);
        }
    }

    static GpuChunkBatcher installLifecycleTestSharedBatcher(Runnable closeAction,
                                                             AtomicLong creations) {
        if (closeAction == null || creations == null) throw new IllegalArgumentException("test hooks are required");
        synchronized (SHARED_LIFECYCLE_LOCK) {
            return installSharedBatcherLocked(() -> {
                creations.incrementAndGet();
                return new GpuChunkBatcher(null, closeAction, 1, 0L,
                        "SuperChunk-GPU-batcher-lifecycle-fixture-shared", null);
            });
        }
    }

    static GpuChunkBatcher initLifecycleTestSharedBatcher(Runnable closeAction,
                                                          AtomicLong creations) {
        if (closeAction == null || creations == null) throw new IllegalArgumentException("test hooks are required");
        return initSharedBatcher(() -> {
            creations.incrementAndGet();
            return new GpuChunkBatcher(null, closeAction, 1, 0L,
                    "SuperChunk-GPU-batcher-lifecycle-fixture-replacement", null);
        });
    }

    CompletableFuture<double[]> enqueueSyntheticForLifecycleTest() {
        CompletableFuture<double[]> future = new CompletableFuture<>();
        GeometryKey key = new GeometryKey(null, null, 1, 1, 1, 1, 1, 1);
        enqueueReq(new Req(0, 0, 0, key, future, null), future);
        return future;
    }

    boolean shutdownStartedForLifecycleTest() {
        return shutdownStarted.get();
    }

    boolean ownerRetirementRequestedForLifecycleTest() {
        return ownerRetirementRequested.get();
    }

    private static int configBatchLimit() {
        try {
            if (OpenCLBackend.config() != null) {
                return OpenCLBackend.config().batchLimit();
            }
        } catch (Throwable ignored) {
        }
        return 32;
    }

    private static long configWindowMicros() {
        try {
            if (OpenCLBackend.config() != null) {
                return OpenCLBackend.config().batchWindowMicros();
            }
        } catch (Throwable ignored) {
        }
        return 500L;
    }

    /** The current shared batcher, or {@code null} if none is installed. */
    public static GpuChunkBatcher shared() {
        return SHARED;
    }

    /** True once OpenCL backend shutdown has begun; prevents late scope-retirement rebinds. */
    public static boolean backendShutdownRequested() {
        return BACKEND_SHUTDOWN.get();
    }

    static boolean retainsFailedCloseOwner(GpuChunkBatcher batcher) {
        return FAILED_CLOSE_BATCHERS.contains(batcher);
    }

    /** Terminal backend teardown entry point. False means native resources must be retained. */
    public static boolean shutdownBackend() {
        java.util.List<GpuChunkBatcher> startShutdown = new java.util.ArrayList<>();
        java.util.List<GpuChunkBatcher> pending;
        synchronized (SHARED_LIFECYCLE_LOCK) {
            BACKEND_SHUTDOWN.set(true);
            SHARED = null;
            for (GpuChunkBatcher batcher : java.util.List.copyOf(LIVE_BATCHERS)) {
                synchronized (batcher.lifecycleBoundary) {
                    if (!batcher.ownerRetirementRequested.get()
                            && !batcher.lifecycleCompletion.isDone()
                            && batcher.shutdownStarted.compareAndSet(false, true)) {
                        batcher.running.set(false);
                        ACTIVE_LIFECYCLES.add(batcher);
                        startShutdown.add(batcher);
                    }
                }
            }
            // Setting BACKEND_SHUTDOWN, marking every live batcher and taking this snapshot
            // share the admission lock with creation and requestOwnerRetirement(). No new
            // batcher or retirement can appear behind the terminal barrier.
            pending = java.util.List.copyOf(ACTIVE_LIFECYCLES);
        }
        // Wake all drainers before joining any one of them; never strand another owner
        // behind a long in-flight CL event in the first batcher.
        for (GpuChunkBatcher batcher : startShutdown) batcher.drainer.interrupt();
        for (GpuChunkBatcher batcher : startShutdown) batcher.finishNormalShutdown();
        boolean quiescent = true;
        for (GpuChunkBatcher active : pending) {
            if (active.awaitLifecycleCompletion() != RetirementResult.QUIESCENT) quiescent = false;
        }
        return quiescent && !DISPATCHER_CLOSE_FAILED.get();
    }

    /**
     * Shuts down the shared batcher if present (drains/serves pending requests, stops
     * the batcher thread, closes the dispatcher). Idempotent no-op when none is set.
     * Used when replacing the singleton; backend teardown uses {@link #shutdownBackend()}.
     */
    public static void shutdownShared() {
        GpuChunkBatcher b;
        boolean startShutdown = false;
        synchronized (SHARED_LIFECYCLE_LOCK) {
            b = SHARED;
            SHARED = null;
            if (b != null) {
                synchronized (b.lifecycleBoundary) {
                    if (!b.ownerRetirementRequested.get()
                            && b.shutdownStarted.compareAndSet(false, true)) {
                        b.running.set(false);
                        ACTIVE_LIFECYCLES.add(b);
                        startShutdown = true;
                    }
                }
            }
        }
        if (b != null) {
            if (startShutdown) b.finishNormalShutdown();
            else b.shutdown();
        }
    }

    // ----------------------------------------------------------------------
    // Public API.
    // ----------------------------------------------------------------------

    /**
     * Enqueues one chunk's density-grid request and returns a future that completes
     * with that chunk's {@code M*n} doubles (root {@code k} cell {@code i} at
     * {@code [k*n + i]}, {@code n = dimX*dimY*dimZ}). Safe to call concurrently from
     * many threads; the caller does not block on the GPU. The future completes
     * exceptionally if the batcher is shutting down, the bounded queue is full, or the
     * batched dispatch fails — never hangs.
     */
    public CompletableFuture<double[]> submit(int ox, int oy, int oz,
                                              int strx, int stry, int strz,
                                              int dimX, int dimY, int dimZ) {
        return submitCompact(ox, oy, oz, strx, stry, strz, dimX, dimY, dimZ, null).future();
    }

    /**
     * COMPACT-IDS (Stage 5) submit: like {@link #submit} but optionally carries the
     * chunk's decide-kernel aux ({@code null} = plain corner-only request, identical
     * to {@link #submit}). The returned handle exposes {@link SubmitHandle#ids()},
     * populated (drainer-side, before the future completes — the completion is the
     * happens-before edge) with the chunk's 1-byte/block ids when the chained decide
     * dispatch produced them; {@code null} ids = the chunk follows the normal
     * corner-only path.
     */
    public SubmitHandle submitCompact(int ox, int oy, int oz,
                                      int strx, int stry, int strz,
                                      int dimX, int dimY, int dimZ,
                                      CompactIds.ChunkAux aux) {
        CompletableFuture<double[]> f = new CompletableFuture<>();
        if (dispatcher == null) {
            f.completeExceptionally(new IllegalStateException(
                    "GpuChunkBatcher has no primary dispatcher — use submitTo(target, ...)"));
            return new SubmitHandle(f, null);
        }
        if (ownerWorldScope != null && !GpuWorldgenPolicy.isWorldScopeLive(ownerWorldScope)) {
            f.completeExceptionally(new java.util.concurrent.CancellationException(
                    "GpuChunkBatcher owner world scope is no longer live"));
            return new SubmitHandle(f, null);
        }
        GeometryKey key = new GeometryKey(null, ownerWorldScope, strx, stry, strz, dimX, dimY, dimZ);
        Req r = new Req(ox, oy, oz, key, f, aux);
        SubmitHandle handle = new SubmitHandle(f, r);
        enqueueReq(r, f);
        return handle;
    }

    /**
     * AUX-PROGRAM-SLOT submit: like {@link #submit} but the request targets
     * {@code target} (e.g. a dimension's CLIMATE fused-group batched dispatcher)
     * instead of this batcher's primary dispatcher. Requests only batch with
     * same-target, same-geometry requests; the single drainer thread issues the
     * dispatch, so the submitter never touches the GPU. The returned future
     * completes with the chunk's {@code target.rootCount()*n} doubles, or
     * exceptionally on shutdown / queue-full / dispatch failure — never hangs.
     * {@code target} must stay open until its futures complete (its owner closes
     * it only after shutting this batcher down).
     */
    public CompletableFuture<double[]> submitTo(GpuBatchDispatcher target,
                                                int ox, int oy, int oz,
                                                int strx, int stry, int strz,
                                                int dimX, int dimY, int dimZ) {
        return submitTo(null, target, ox, oy, oz, strx, stry, strz, dimX, dimY, dimZ);
    }

    /**
     * Scope-bound AUX submit. The scope is part of the grouping identity and is checked
     * when enqueued and again by the drainer immediately before dispatcher submission.
     * This closes the async gap between the caller's authorization and a delayed batch
     * flush. A missing or expired production scope fails the future; it never dispatches.
     */
    public CompletableFuture<double[]> submitTo(GpuWorldgenPolicy.WorldScope worldScope,
                                                GpuBatchDispatcher target,
                                                int ox, int oy, int oz,
                                                int strx, int stry, int strz,
                                                int dimX, int dimY, int dimZ) {
        CompletableFuture<double[]> f = new CompletableFuture<>();
        if (target == null) {
            f.completeExceptionally(new IllegalArgumentException("submitTo: null target dispatcher"));
            return f;
        }
        if (worldScope != null && !GpuWorldgenPolicy.isWorldScopeLive(worldScope)) {
            f.completeExceptionally(new java.util.concurrent.CancellationException(
                    "GpuChunkBatcher target world scope is no longer live"));
            return f;
        }
        GeometryKey key = new GeometryKey(target, worldScope, strx, stry, strz, dimX, dimY, dimZ);
        enqueueReq(new Req(ox, oy, oz, key, f, null, null, worldScope), f);
        return f;
    }

    /** Common enqueue tail shared by {@link #submitCompact}, {@link #submitCombined} and {@link #submitTo}. */
    private void enqueueReq(Req r, CompletableFuture<double[]> f) {
        if (!running.get() || ownerRetirementRequested.get()) {
            rejectedCount.incrementAndGet();
            failReq(r, f, new IllegalStateException("GpuChunkBatcher is not running"));
            return;
        }
        if (!queue.offer(r)) {
            // Queue-full used to be COMPLETELY uncounted (it also skips requestCount, so it
            // didn't even show as a requests-vs-served divergence) — a saturated batcher was
            // indistinguishable from a healthy one. Count it; logStats echoes it.
            rejectedCount.incrementAndGet();
            failReq(r, f, new RejectedExecutionException("GpuChunkBatcher queue full"));
            return;
        }
        requestCount.incrementAndGet();
        // A late shutdown could have flipped running after our check; if so, make sure
        // this request can't be orphaned (the drainer may have already exited).
        if ((!running.get() || ownerRetirementRequested.get()) && queue.remove(r)) {
            requestCount.decrementAndGet();
            failReq(r, f, new IllegalStateException("GpuChunkBatcher is shutting down"));
        }
    }

    /** Fails a request's density future AND (when present) its climate future — no orphans. */
    private static void failReq(Req r, CompletableFuture<double[]> f, RuntimeException ex) {
        try {
            f.completeExceptionally(ex);
        } catch (Throwable ignored) {
        }
        if (r != null && r.climFuture != null) {
            try {
                r.climFuture.completeExceptionally(ex);
            } catch (Throwable ignored) {
            }
        }
    }

    /** The primary program slot's dispatcher, or {@code null} (aux-only batcher). */
    public GpuBatchDispatcher primaryDispatcher() {
        return dispatcher;
    }

    /**
     * CLIMATE-CHAIN combined submit (the "free ride"): ONE request whose batch computes
     * the chunk's density corner grids AND — via the primary dispatcher's attached
     * climate chain — its packed climate quart grids, in one slot round trip. The
     * density future behaves exactly like {@link #submit}; the climate future completes
     * with the {@code climM*climN} packed climate doubles, or exceptionally when the
     * chain didn't run for this batch (caller funnels the miss). Both futures complete
     * exceptionally on shutdown / queue-full / dispatch failure — never hang.
     */
    public CombinedHandle submitCombined(int ox, int oy, int oz,
                                         int strx, int stry, int strz,
                                         int dimX, int dimY, int dimZ) {
        CompletableFuture<double[]> f = new CompletableFuture<>();
        CompletableFuture<double[]> cf = new CompletableFuture<>();
        if (dispatcher == null) {
            RuntimeException ex = new IllegalStateException(
                    "GpuChunkBatcher has no primary dispatcher — combined submits need one");
            f.completeExceptionally(ex);
            cf.completeExceptionally(ex);
            return new CombinedHandle(f, cf);
        }
        if (ownerWorldScope != null && !GpuWorldgenPolicy.isWorldScopeLive(ownerWorldScope)) {
            java.util.concurrent.CancellationException ex = new java.util.concurrent.CancellationException(
                    "GpuChunkBatcher owner world scope is no longer live");
            f.completeExceptionally(ex);
            cf.completeExceptionally(ex);
            return new CombinedHandle(f, cf);
        }
        GeometryKey key = new GeometryKey(null, ownerWorldScope, strx, stry, strz, dimX, dimY, dimZ);
        Req r = new Req(ox, oy, oz, key, f, null, cf);
        enqueueReq(r, f);
        return new CombinedHandle(f, cf);
    }

    /** A combined submit's two result futures (density corner grids + climate quart grids). */
    public record CombinedHandle(CompletableFuture<double[]> density, CompletableFuture<double[]> climate) {
    }

    /** Handle for a compact submit: the grids future + the chunk's decide-kernel ids (if any). */
    public static final class SubmitHandle {
        private final CompletableFuture<double[]> future;
        private final Req req;

        SubmitHandle(CompletableFuture<double[]> future, Req req) {
            this.future = future;
            this.req = req;
        }

        public CompletableFuture<double[]> future() {
            return future;
        }

        /** The chunk's 1-byte/block ids, or {@code null}. Read AFTER the future completed. */
        public byte[] ids() {
            return req == null ? null : req.ids;
        }
    }

    public int batchLimit() {
        return batchLimit;
    }

    /** Max time (microseconds) a pooled request waits before its group flushes. */
    public long batchWindowMicros() {
        return batchWindowNanos / 1_000L;
    }

    /** Mean achieved batch size (chunks per dispatch), or 0 when nothing dispatched. */
    public double meanBatchSize() {
        long d = dispatchCount.get();
        return d == 0L ? 0.0 : (double) batchSizeSum.get() / d;
    }

    /**
     * Logs the achieved cross-chunk coalescing: requests pooled, batched dispatches
     * issued, the launch-reduction factor, and the batch-size distribution. Safe to
     * call any time (e.g. at server stop alongside the density-fill summary).
     */
    public void logStats(String when) {
        long req = requestCount.get();
        long disp = dispatchCount.get();
        long served = servedCount.get();
        long rejected = rejectedCount.get();
        if (req == 0L && disp == 0L && rejected == 0L) {
            return;
        }
        double mean = meanBatchSize();
        double reduction = disp == 0L ? 0.0 : (double) served / disp;
        LOGGER.info("[SuperChunk-GPU] [batch] cross-chunk batching '{}' ({}): requests pooled={}, served={}, REJECTED "
                        + "(queue full / not running)={}, batched dispatches={} "
                        + "(batchLimit={}, windowMicros={}); mean batch size={} chunks/dispatch, max={}; "
                        + "GPU-launch reduction ~{}x vs one-dispatch-per-chunk; dead corner grids skipped={} "
                        + "(fast-path chunks whose 78.4KB slice nothing reads).",
                drainer.getName(), when, req, served, rejected, disp, batchLimit, batchWindowMicros(),
                String.format("%.1f", mean), maxBatchSize.get(), String.format("%.1f", reduction),
                deadGridsSkipped.get());
        long ct = colTotal.get(), cu = colUnique.get();
        if (ct > 0) {
            LOGGER.info("[SuperChunk-GPU] [batch] corner-column redundancy '{}' ({}): columns evaluated={}, "
                            + "DISTINCT={} -> {}% of the corner kernel is duplicate work "
                            + "(shared cell columns between chunks that landed in the same batch).",
                    drainer.getName(), when, ct, cu, String.format("%.1f", 100.0 * (ct - cu) / ct));
        }
        LOGGER.info("[SuperChunk-GPU] [batch] achieved batch-size distribution '{}' ({}): "
                        + "[1]={} [2-4]={} [5-8]={} [9-16]={} [17-32]={} [33-64]={} [65+]={} dispatches.",
                drainer.getName(), when,
                batchHistogram[0].get(), batchHistogram[1].get(), batchHistogram[2].get(),
                batchHistogram[3].get(), batchHistogram[4].get(), batchHistogram[5].get(),
                batchHistogram[6].get());
        if (disp > 0L) {
            String depth = dispatcher != null ? String.valueOf(dispatcher.pipelineDepth()) : "per-target";
            String slotWait = dispatcher != null
                    ? String.format("%.1fms over %d acquires", dispatcher.slotWaitNanos() / 1e6, dispatcher.slotAcquires())
                    : "n/a (aux-only)";
            LOGGER.info("[SuperChunk-GPU] [batch-pipe] pipeline '{}' ({}): depth={}; per-batch host-enqueue={}us, "
                            + "gpu-await={}us, slice+complete={}us; drainer slot-wait {}; "
                            + "in-flight@enqueue [1]={} [2]={} [3]={} [4+]={}.",
                    drainer.getName(), when, depth,
                    String.format("%.1f", pipeEnqueueNanos.get() / 1e3 / disp),
                    String.format("%.1f", pipeAwaitNanos.get() / 1e3 / disp),
                    String.format("%.1f", pipeSliceNanos.get() / 1e3 / disp),
                    slotWait,
                    inFlightHistogram[0].get(), inFlightHistogram[1].get(),
                    inFlightHistogram[2].get(), inFlightHistogram[3].get());
        }
    }

    /** Records one dispatch's achieved batch size {@code K} into the mean/max/histogram. */
    private void recordBatchSize(int k) {
        batchSizeSum.addAndGet(k);
        // monotonic max
        long prev;
        while (k > (prev = maxBatchSize.get()) && !maxBatchSize.compareAndSet(prev, k)) {
            // retry
        }
        int bin;
        if (k <= 1) bin = 0;
        else if (k <= 4) bin = 1;
        else if (k <= 8) bin = 2;
        else if (k <= 16) bin = 3;
        else if (k <= 32) bin = 4;
        else if (k <= 64) bin = 5;
        else bin = 6;
        batchHistogram[bin].incrementAndGet();
    }

    /** Total accepted {@link #submit} calls. */
    public long requestCount() {
        return requestCount.get();
    }

    /** Total {@link GpuBatchDispatcher#batchFill} dispatches issued (coalesced). */
    public long dispatchCount() {
        return dispatchCount.get();
    }

    /** Total chunks completed successfully. */
    public long servedCount() {
        return servedCount.get();
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Stops accepting submits, lets the drainer flush every pending request, joins the
     * batcher thread, fails any straggler that slipped in, then closes the dispatcher.
     * Idempotent.
     */
    public void shutdown() {
        boolean beginShutdown = false;
        synchronized (SHARED_LIFECYCLE_LOCK) {
            synchronized (lifecycleBoundary) {
                if (!ownerRetirementRequested.get()
                        && shutdownStarted.compareAndSet(false, true)) {
                    running.set(false);
                    ACTIVE_LIFECYCLES.add(this);
                    beginShutdown = true;
                }
            }
        }
        if (!beginShutdown) {
            awaitLifecycleCompletion();
            return;
        }
        finishNormalShutdown();
    }

    private void finishNormalShutdown() {
        drainer.interrupt(); // wake the drainer out of poll(); it will flush + exit
        joinUninterruptibly(drainer, "normal batcher drainer");
        // Fail anything that slipped into the queue after running flipped (drainer gone).
        RuntimeException ex = new RuntimeException("GpuChunkBatcher shut down");
        Req r;
        while ((r = queue.poll()) != null) {
            failReq(r, r.future, ex); // also the climate future a chained request waits on
        }
        // Stop the completer: poison AFTER the drainer stopped enqueueing, so every
        // in-flight batch is drained + served first (each bounded by one GPU round
        // trip). Longer join than the drainer's — it may have a full pipeline to eat.
        putPoisonUninterruptibly();
        joinUninterruptibly(completer, "normal batcher completer");
        // The PRIMARY dispatcher is owned by this batcher; aux program-slot dispatchers
        // (submitTo targets) are owned + closed by their registrants AFTER this join.
        boolean closed = closePrimaryDispatcher();
        LOGGER.info("[SuperChunk-GPU] [batch-agg] batcher '{}' shut down (requests={} dispatches={} served={}).",
                drainer.getName(), requestCount.get(), dispatchCount.get(), servedCount.get());
        completeLifecycle(closed ? RetirementResult.QUIESCENT : RetirementResult.CLOSE_FAILED);
    }

    /**
     * Retires the world-owned singleton without flushing queued work into a retired scope.
     * Admission closes immediately. The drainer fails queued/bucketed futures; batches already
     * handed to the completer are awaited and completed before the primary dispatcher is closed.
     * The ServerLevel close hook uses {@link #requestOwnerRetirement()} and observes one shared
     * completion stage after both worker threads and admitted CL jobs stop.
     */
    public RetirementRequest requestOwnerRetirement() {
        RetirementDisposition disposition;
        boolean startRetirement = false;
        boolean wakeExistingDrainer = false;
        synchronized (SHARED_LIFECYCLE_LOCK) {
            synchronized (lifecycleBoundary) {
                if (lifecycleCompletion.isDone()) {
                    disposition = RetirementDisposition.ALREADY_COMPLETE;
                } else if (ownerRetirementRequested.get()) {
                    disposition = RetirementDisposition.ALREADY_RETIRING;
                } else if (shutdownStarted.get()) {
                    // Normal shutdown may still be flushing queued primary work. A world
                    // retirement that arrives in that window must switch the drainer to
                    // its fail-pending branch without starting a second retirement worker.
                    ownerRetirementRequested.set(true);
                    running.set(false);
                    retirementModeChosen.countDown();
                    disposition = RetirementDisposition.SHUTDOWN_ALREADY_STARTED;
                    wakeExistingDrainer = true;
                } else if (BACKEND_SHUTDOWN.get()) {
                    disposition = RetirementDisposition.BACKEND_TERMINAL;
                } else {
                    ownerRetirementRequested.set(true);
                    running.set(false);
                    ACTIVE_LIFECYCLES.add(this);
                    disposition = RetirementDisposition.STARTED;
                    startRetirement = true;
                }
            }
        }
        if (startRetirement) startOwnerRetirementAsync();
        else if (wakeExistingDrainer) drainer.interrupt();
        return new RetirementRequest(disposition, lifecycleCompletion);
    }

    private void startOwnerRetirementAsync() {
        Thread retire = new Thread(this::finishOwnerRetirement,
                "SuperChunk-GPU-batcher-retire");
        retire.setDaemon(true);
        try {
            retire.start();
            retirementModeChosen.countDown();
            drainer.interrupt();
        } catch (Throwable failure) {
            // Never make ServerLevel.close wait for queue/CL quiescence. If a helper
            // thread cannot be created, hand finalization to the already-running drainer.
            LOGGER.warn("[SuperChunk-GPU] [batch] could not start retirement helper; "
                    + "the drainer will finish retirement asynchronously.", failure);
            retirementOnDrainer.set(true);
            retirementModeChosen.countDown();
            drainer.interrupt();
        }
    }

    private void finishOwnerRetirement() {
        // Joining the drainer fences an enqueue already in progress. No resources are
        // released until both it and the completer have stopped using their CL objects.
        if (Thread.currentThread() != drainer) {
            joinUninterruptibly(drainer, "owner-retirement drainer");
        }
        putPoisonUninterruptibly();
        joinUninterruptibly(completer, "owner-retirement completer");
        boolean closed = closePrimaryDispatcher();
        if (!closed) {
            LOGGER.error("[SuperChunk-GPU] [batch] owner retirement stopped its workers but dispatcher close failed; "
                    + "backend native teardown is vetoed and the dispatcher will not be closed a second time.");
        } else {
            LOGGER.info("[SuperChunk-GPU] [batch] retired world-owned batcher '{}' after admitted CL work resolved.",
                    drainer.getName());
        }
        completeLifecycle(closed ? RetirementResult.QUIESCENT : RetirementResult.CLOSE_FAILED);
    }

    private void completeLifecycle(RetirementResult result) {
        if (result == RetirementResult.CLOSE_FAILED) {
            DISPATCHER_CLOSE_FAILED.set(true);
            FAILED_CLOSE_BATCHERS.add(this);
        }
        // Publish terminal state before invoking callbacks: a retirement callback may
        // immediately install a replacement and must not wait on its own completion.
        synchronized (SHARED_LIFECYCLE_LOCK) {
            synchronized (lifecycleBoundary) {
                shutdownStarted.set(true);
            }
            if (SHARED == this) SHARED = null;
            ACTIVE_LIFECYCLES.remove(this);
            LIVE_BATCHERS.remove(this);
        }
        // Workers have joined and the single dispatcher close attempt has resolved. Do
        // not hold the admission lock while dependent cleanup/rebind callbacks run.
        lifecycleCompletion.complete(result);
    }

    private void awaitRetirementModeChosen() {
        boolean interrupted = false;
        while (true) {
            try {
                retirementModeChosen.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private RetirementResult awaitLifecycleCompletion() {
        boolean interrupted = false;
        long nextWarning = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            while (true) {
                try {
                    return lifecycleCompletion.get(30, TimeUnit.SECONDS);
                } catch (TimeoutException timeout) {
                    if (System.nanoTime() >= nextWarning) {
                        LOGGER.warn("[SuperChunk-GPU] [batch] still waiting for '{}' lifecycle quiescence; "
                                        + "native resources remain retained while workers are active.",
                                drainer.getName());
                        nextWarning = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    }
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException failure) {
                    LOGGER.error("[SuperChunk-GPU] [batch] lifecycle completion failed unexpectedly.",
                            failure.getCause());
                    return RetirementResult.CLOSE_FAILED;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void putPoisonUninterruptibly() {
        boolean interrupted = false;
        while (true) {
            try {
                completionQueue.put(POISON);
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void joinUninterruptibly(Thread thread, String phase) {
        if (!shouldJoin(Thread.currentThread(), thread)) return;
        boolean interrupted = false;
        long nextWarning = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (thread.isAlive()) {
            try {
                thread.join(30_000L);
                if (thread.isAlive() && System.nanoTime() >= nextWarning) {
                    LOGGER.warn("[SuperChunk-GPU] [batch] still waiting for {} thread '{}'; "
                                    + "native resources remain retained.", phase, thread.getName());
                    nextWarning = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                }
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    static boolean shouldJoin(Thread current, Thread target) {
        return target != null && target != current;
    }

    private boolean closePrimaryDispatcher() {
        if (primaryDispatcherCloser == null) return true;
        if (primaryDispatcherCloseSucceeded) return true;
        if (!primaryDispatcherCloseAttempted.compareAndSet(false, true)) return primaryDispatcherCloseSucceeded;
        try {
            primaryDispatcherCloser.run();
            primaryDispatcherCloseSucceeded = true;
            return true;
        } catch (Throwable failure) {
            LOGGER.warn("[SuperChunk-GPU] [batch] primary dispatcher close failed.", failure);
            // close() may have partially released native state before throwing. Retrying
            // could double-free it, so fail closed and veto backend native teardown.
            DISPATCHER_CLOSE_FAILED.set(true);
            return false;
        }
    }

    // ----------------------------------------------------------------------
    // Drainer (single consumer thread — the ONLY thread that touches the dispatcher).
    // ----------------------------------------------------------------------

    private void drainLoop() {
        if (beforeDrainLoopForTest != null) beforeDrainLoopForTest.run();
        final Map<GeometryKey, ArrayDeque<Req>> buckets = new HashMap<>();
        final Map<GeometryKey, Long> deadlineNanos = new HashMap<>();
        while (true) {
            try {
                long wait = computeWaitNanos(buckets, deadlineNanos);
                Req first = null;
                try {
                    first = queue.poll(wait, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ie) {
                    // shutdown signal — fall through; the exit check below handles it.
                }
                if (first != null) {
                    bucketOrFail(first, buckets, deadlineNanos);
                    Req more;
                    while ((more = queue.poll()) != null) {
                        bucketOrFail(more, buckets, deadlineNanos);
                    }
                }
                boolean stopping = !running.get();
                if (ownerRetirementRequested.get()) {
                    awaitRetirementModeChosen();
                    RuntimeException ex = new IllegalStateException("GpuChunkBatcher world owner retired");
                    Req queued;
                    while ((queued = queue.poll()) != null) {
                        failReq(queued, queued.future, ex);
                    }
                    for (ArrayDeque<Req> bucket : buckets.values()) {
                        Req pending;
                        while ((pending = bucket.poll()) != null) {
                            failReq(pending, pending.future, ex);
                        }
                    }
                    buckets.clear();
                    deadlineNanos.clear();
                    if (retirementOnDrainer.get()) {
                        finishOwnerRetirement();
                    }
                    return;
                }
                flushReady(buckets, deadlineNanos, stopping);
                if (stopping && queue.isEmpty() && buckets.isEmpty()) {
                    return;
                }
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [batch-agg] drainer iteration failed — continuing.", t);
            }
        }
    }

    private long computeWaitNanos(Map<GeometryKey, ArrayDeque<Req>> buckets,
                                  Map<GeometryKey, Long> deadlineNanos) {
        if (buckets.isEmpty()) {
            return IDLE_PARK_NANOS;
        }
        long now = System.nanoTime();
        long min = Long.MAX_VALUE;
        for (long d : deadlineNanos.values()) {
            if (d < min) {
                min = d;
            }
        }
        long w = min - now;
        return w < 0 ? 0 : w;
    }

    /**
     * Inserts {@code r} into its geometry bucket, but never lets a failed handoff orphan
     * the request: a {@code queue.poll()} has already removed {@code r}, so if
     * {@link #addToBucket} throws (e.g. OOM allocating the bucket/map node) {@code r}
     * would be in NO bucket and unreachable by the per-bucket flush timeout, hanging its
     * submitter forever. Instead we complete its future exceptionally and let the drainer
     * continue flushing the already-bucketed requests. CompletableFuture completion is
     * idempotent.
     */
    private void bucketOrFail(Req r, Map<GeometryKey, ArrayDeque<Req>> buckets,
                              Map<GeometryKey, Long> deadlineNanos) {
        try {
            addToBucket(r, buckets, deadlineNanos);
        } catch (Throwable t) {
            failReq(r, r.future, new RuntimeException("GpuChunkBatcher bucket insert failed", t));
        }
    }

    private void addToBucket(Req r, Map<GeometryKey, ArrayDeque<Req>> buckets,
                             Map<GeometryKey, Long> deadlineNanos) {
        if (r.worldScope != null && !GpuWorldgenPolicy.isWorldScopeLive(r.worldScope)) {
            failReq(r, r.future, new java.util.concurrent.CancellationException(
                    "GpuChunkBatcher target world scope expired before batching"));
            return;
        }
        ArrayDeque<Req> b = buckets.get(r.key);
        if (b == null) {
            b = new ArrayDeque<>();
            buckets.put(r.key, b);
        }
        if (b.isEmpty()) {
            // Anchor the flush deadline to this group's oldest pending request — at its
            // SUBMIT time, not bucket time. Requests that arrived during the previous
            // synchronous dispatch (the steady-state case) have already waited part or all
            // of their window in the queue, so their group flushes on this drainer pass
            // instead of paying a fresh full window on top — the collection window now
            // overlaps the previous dispatch (free batching, no added GPU idle).
            deadlineNanos.put(r.key, r.submitNanos + batchWindowNanos);
        }
        b.add(r);
    }

    private void flushReady(Map<GeometryKey, ArrayDeque<Req>> buckets,
                            Map<GeometryKey, Long> deadlineNanos, boolean forceAll) {
        if (buckets.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        Iterator<Map.Entry<GeometryKey, ArrayDeque<Req>>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<GeometryKey, ArrayDeque<Req>> e = it.next();
            GeometryKey key = e.getKey();
            ArrayDeque<Req> b = e.getValue();
            Long dl = deadlineNanos.get(key);
            boolean expired = forceAll || (dl != null && now >= dl);
            // Each dispatchBatch removes >=1 element, so the bucket strictly shrinks.
            while (!b.isEmpty() && (b.size() >= batchLimit || expired)) {
                dispatchBatch(key, b);
            }
            if (b.isEmpty()) {
                it.remove();
                deadlineNanos.remove(key);
            }
        }
    }

    /**
     * Pops up to {@code batchLimit} live (non-cancelled) requests and ENQUEUES them as
     * ONE pipelined batch — no waiting here: the {@link GpuBatchDispatcher.InFlight}
     * handle goes to the completer thread, and this drainer immediately returns to
     * collecting the next batch. Blocks only inside {@code enqueueBatch} when every
     * pipeline slot is in flight (GPU a full pipeline behind = correct backpressure).
     */
    /**
     * Accumulates this batch's total vs DISTINCT corner columns into {@link #colTotal} /
     * {@link #colUnique}. Drainer-thread only; a linear-probe set over a power-of-two table
     * sized 4x the column count, so it is a few microseconds against a batch whose GPU work
     * is tens of milliseconds. Purely observational.
     */
    private void countColumnRedundancy(int[] origins, int K, int strx, int strz, int dimX, int dimZ) {
        final int cols = K * dimX * dimZ;
        int cap = Integer.highestOneBit(Math.max(16, cols * 4 - 1)) << 1;
        if (colScratch == null || colScratch.length < cap) {
            colScratch = new long[cap];
        }
        final long[] tab = colScratch;
        java.util.Arrays.fill(tab, 0, cap, Long.MIN_VALUE);   // MIN_VALUE = empty
        final int mask = cap - 1;
        int distinct = 0;
        for (int c = 0; c < K; c++) {
            final int ox = origins[c * 3], oz = origins[c * 3 + 2];
            for (int ix = 0; ix < dimX; ix++) {
                final long xs = ((long) (ox + ix * strx)) << 32;
                for (int iz = 0; iz < dimZ; iz++) {
                    long key = xs | ((oz + iz * strz) & 0xFFFFFFFFL);
                    int h = (int) ((key ^ (key >>> 32)) * 0x9E3779B1L) & mask;
                    while (true) {
                        long cur = tab[h];
                        if (cur == Long.MIN_VALUE) {
                            tab[h] = key;
                            distinct++;
                            break;
                        }
                        if (cur == key) {
                            break;
                        }
                        h = (h + 1) & mask;
                    }
                }
            }
        }
        colTotal.addAndGet(cols);
        colUnique.addAndGet(distinct);
    }

    private void dispatchBatch(GeometryKey key, ArrayDeque<Req> b) {
        if (beforeBatchDispatchForTest != null) beforeBatchDispatchForTest.run();
        if (key.worldScope() != null && !GpuWorldgenPolicy.isWorldScopeLive(key.worldScope())) {
            failPendingScope(b, key.worldScope(), "expired before dispatch");
            return;
        }
        int K = 0;
        while (K < batchLimit && !b.isEmpty()) {
            Req r = b.poll();
            if (r.future.isCancelled()) {
                continue; // dropped; its future is already completed (cancelled)
            }
            liveScratch[K++] = r;
        }
        if (K == 0) {
            return; // only cancelled requests in this slice — no GPU work
        }
        // Program slot: the batch's target dispatcher (aux slot) or the primary.
        final GpuBatchDispatcher disp = key.target() != null ? key.target() : this.dispatcher;
        if (disp == null) {
            // Defensive — submit paths reject slotless requests on a primary-less batcher.
            RuntimeException ex = new IllegalStateException("GpuChunkBatcher batch has no dispatcher");
            for (int c = 0; c < K; c++) {
                try {
                    liveScratch[c].future.completeExceptionally(ex);
                } catch (Throwable ignored) {
                }
                liveScratch[c] = null;
            }
            return;
        }
        recordBatchSize(K);
        // Copy the popped requests OUT of the reusable scratch: the completer thread
        // reads them after this method returns, while the scratch is already being
        // reused for the next batch.
        final Req[] batchReqs = new Req[K];
        System.arraycopy(liveScratch, 0, batchReqs, 0, K);
        for (int c = 0; c < K; c++) {
            liveScratch[c] = null;
        }
        // Everything below is guarded: any throwable (OOM on the aux array, an
        // interrupted handoff, an enqueue fault) must still terminate EVERY popped
        // request's future — the Reqs are gone from the bucket, so an orphan would
        // hang its submitter forever.
        GpuBatchDispatcher.InFlight inf = null;
        boolean handedOff = false;
        try {
            final int M = disp.rootCount();
            final int n = key.dimX() * key.dimY() * key.dimZ();
            final int sliceLen = M * n;
            if (originScratch == null || originScratch.length < K * 3) {
                originScratch = new int[K * 3];
            }
            int[] origins = originScratch;
            for (int c = 0; c < K; c++) {
                Req r = batchReqs[c];
                origins[c * 3] = r.ox;
                origins[c * 3 + 1] = r.oy;
                origins[c * 3 + 2] = r.oz;
            }
            countColumnRedundancy(origins, K, key.strx(), key.strz(), key.dimX(), key.dimZ());
            // COMPACT-IDS: collect the popped requests' auxes (if any) so the dispatcher
            // can chain the decide kernel; per-chunk id slices are read completer-side.
            CompactIds.ChunkAux[] auxes = null;
            for (int c = 0; c < K; c++) {
                if (batchReqs[c].aux != null) {
                    auxes = new CompactIds.ChunkAux[K];
                    for (int c2 = 0; c2 < K; c2++) {
                        auxes[c2] = batchReqs[c2].aux;
                    }
                    break;
                }
            }
            // CLIMATE CHAIN: any combined request in the batch turns the chained
            // climate program on for the WHOLE batch (climate origins derive from the
            // density origins, so every chunk's slice is valid; slices of plain
            // density requests are simply never read).
            boolean anyClim = false;
            for (int c = 0; c < K; c++) {
                if (batchReqs[c].climFuture != null) {
                    anyClim = true;
                    break;
                }
            }
            long t0 = System.nanoTime();
            final int batchK = K;
            final CompactIds.ChunkAux[] batchAuxes = auxes;
            final boolean batchAnyClim = anyClim;
            synchronized (dispatchBoundary) {
                if (ownerRetirementRequested.get()) {
                    throw new java.util.concurrent.CancellationException("GpuChunkBatcher world owner retired");
                }
                GpuBatchDispatcher.InFlight[] submitted = new GpuBatchDispatcher.InFlight[1];
                Runnable enqueue = () -> submitted[0] = disp.enqueueBatch(origins, batchK,
                        key.strx(), key.stry(), key.strz(),
                        key.dimX(), key.dimY(), key.dimZ(), batchAuxes, batchAnyClim,
                        key.worldScope() == null ? GpuAuthorizationDiagnostics.WorldBucket.UNKNOWN
                                : key.worldScope().diagnosticBucket());
                boolean scopeAccepted;
                if (key.worldScope() == null) {
                    // Primary requests belong to this batcher's own scope. The retirement
                    // flag and this boundary define their admission point; requestOwnerRetirement()
                    // closes that gate; retirement joins the drainer before closing resources.
                    if (ownerRetirementRequested.get()) {
                        throw new java.util.concurrent.CancellationException(
                                "GpuChunkBatcher world owner retired");
                    }
                    enqueue.run();
                    scopeAccepted = true;
                } else {
                    // The policy grants a short-lived dispatch lease under the same lock
                    // used by retireLevel(), then releases that lock before enqueueBatch
                    // can wait for a free pipeline slot. A pre-retirement lease is admitted;
                    // the target scope is rechecked above and is never guessed from a permit.
                    scopeAccepted = GpuWorldgenPolicy.runIfWorldScopeLive(key.worldScope(), enqueue);
                }
                if (!scopeAccepted) {
                    throw new java.util.concurrent.CancellationException(
                            "GpuChunkBatcher target world scope expired before dispatcher enqueue");
                }
                inf = submitted[0];
            }
            pipeEnqueueNanos.addAndGet(System.nanoTime() - t0);
            dispatchCount.incrementAndGet();
            if (inf != null) {
                recordInFlight(completionQueue.size() + 1);
                completionQueue.put(new PendingBatch(inf, batchReqs, K, sliceLen, auxes != null));
                handedOff = true;
            }
        } catch (Throwable t) {
            if (!ownerRetirementRequested.get()) {
                LOGGER.warn("[SuperChunk-GPU] [batch-agg] batch enqueue failed (K={}).", K, t);
            }
        }
        if (!handedOff) {
            RuntimeException ex = new RuntimeException("GPU batched dispatch failed");
            failBatchReqs(batchReqs, ex);
            if (inf != null) {
                try {
                    inf.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Fails queued entries from an expired target scope without touching its dispatcher. */
    private static void failPendingScope(ArrayDeque<Req> requests,
                                         GpuWorldgenPolicy.WorldScope scope, String reason) {
        var iterator = requests.iterator();
        while (iterator.hasNext()) {
            Req request = iterator.next();
            if (request.worldScope == scope) {
                iterator.remove();
                failReq(request, request.future, new java.util.concurrent.CancellationException(
                        "GpuChunkBatcher target world scope " + reason));
            }
        }
    }

    // ----------------------------------------------------------------------
    // Completer (single consumer of the pipeline handoff queue).
    // ----------------------------------------------------------------------

    private void completeLoop() {
        while (true) {
            PendingBatch p;
            try {
                p = completionQueue.take();
            } catch (InterruptedException ie) {
                continue; // exit is poison-driven, not interrupt-driven
            }
            if (p == POISON) {
                return;
            }
            try {
                completeBatch(p);
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [batch-agg] completer iteration failed — continuing.", t);
            }
        }
    }

    /** Waits one in-flight batch, serves its futures from the slot's pinned staging, recycles the slot. */
    private void completeBatch(PendingBatch p) {
        boolean ok;
        long t0 = System.nanoTime();
        try {
            ok = p.inf.awaitParked();
        } catch (Throwable t) {
            ok = false;
        }
        pipeAwaitNanos.addAndGet(System.nanoTime() - t0);
        try {
            if (ok) {
                servedCount.addAndGet(p.k);
                long ts = System.nanoTime();
                if (p.anyAux && p.inf.decidePresent() > 0) {
                    long ti = System.nanoTime();
                    for (int c = 0; c < p.k; c++) {
                        // Written BEFORE the future completes (completion is the
                        // happens-before edge for readers of Req.ids).
                        p.reqs[c].ids = p.inf.ids(c);
                    }
                    CompactIds.recordDecide(p.inf.decidePresent(), p.inf.decideKernelNs(),
                            System.nanoTime() - ti);
                }
                // Whole in-batch device timeline (no-op unless the slot queue profiles).
                // MUST run before release() — that frees the events.
                CompactIds.recordTimeline(p.inf.deviceTimeline());
                final boolean climOk = p.inf.climateAvailable();
                final int climLen = p.inf.climateSliceLenLive();
                for (int c = 0; c < p.k; c++) {
                    Req r = p.reqs[c];
                    // CLIMATE CHAIN: serve the climate future FIRST — the biomes-seam
                    // Completable gates on it, and its consumer runs a stage EARLIER
                    // than the density consumer.
                    if (r.climFuture != null) {
                        double[] cs = climOk ? new double[climLen] : null;
                        if (cs != null && p.inf.readClimSlice(c, cs)) {
                            r.climFuture.complete(cs);
                        } else {
                            try {
                                r.climFuture.completeExceptionally(new IllegalStateException(climOk
                                        ? "batch staging released (dispatcher closed)"
                                        : "climate chain unavailable for this batch"));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    // DEAD-GRID SKIP. On the compact-consume FAST path doFill is cancelled at
                    // HEAD, so ChunkGridCache.consumeBatchedGrid — the ONLY reader of
                    // Stored.grids — never runs and this slice is allocated, copied and dropped.
                    // Measured: 'served from the batched grid store' equals the hybrid count
                    // EXACTLY (5220/5220, 5222/5222 on two r2048 runs), i.e. 92% of chunks never
                    // read it — ~4.7 GB of pointless allocation + copy per run, on the completer
                    // thread, inside the slot-held window.
                    if (!gridsAreDead(r)) {
                        double[] s = new double[p.sliceLen];
                        if (p.inf.readSlice(c, s)) {
                            r.future.complete(s); // no-op if the future was cancelled meanwhile
                        } else {
                            failReq(r, r.future, new RuntimeException("GPU batch staging released (dispatcher closed)"));
                        }
                    } else {
                        deadGridsSkipped.incrementAndGet();
                        r.future.complete(EMPTY_GRIDS);
                    }
                }
                pipeSliceNanos.addAndGet(System.nanoTime() - ts);
            } else {
                failBatchReqs(p.reqs, new RuntimeException("GPU batched dispatch failed"));
            }
        } catch (Throwable t) {
            // completeExceptionally is idempotent, so failing the whole batch also
            // covers any already-completed heads — no future is ever orphaned.
            LOGGER.warn("[SuperChunk-GPU] [batch-agg] batch completion failed (K={}).", p.k, t);
            failBatchReqs(p.reqs, new RuntimeException("GpuChunkBatcher completion aborted", t));
        } finally {
            try {
                p.inf.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void failBatchReqs(Req[] reqs, RuntimeException ex) {
        if (reqs == null) {
            return;
        }
        for (Req r : reqs) {
            if (r != null) {
                try {
                    r.future.completeExceptionally(ex);
                } catch (Throwable ignored) {
                }
                if (r.climFuture != null) {
                    try {
                        r.climFuture.completeExceptionally(ex);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void recordInFlight(int depth) {
        int bin = depth <= 1 ? 0 : depth == 2 ? 1 : depth == 3 ? 2 : 3;
        inFlightHistogram[bin].incrementAndGet();
    }

    /** One enqueued batch riding the pipeline from drainer to completer. */
    private record PendingBatch(GpuBatchDispatcher.InFlight inf, Req[] reqs, int k, int sliceLen,
                                boolean anyAux) {
    }

    // ----------------------------------------------------------------------
    // Value types.
    // ----------------------------------------------------------------------

    /**
     * Grouping key — only requests with the SAME program slot ({@code target};
     * {@code null} = this batcher's primary dispatcher, compared by identity since
     * {@link GpuBatchDispatcher} does not override {@code equals}) AND the same
     * geometry share a batch.
     */
    private record GeometryKey(GpuBatchDispatcher target,
                               GpuWorldgenPolicy.WorldScope worldScope,
                               int strx, int stry, int strz, int dimX, int dimY, int dimZ) {
    }

    private static final class Req {
        final int ox;
        final int oy;
        final int oz;
        final GeometryKey key;
        final CompletableFuture<double[]> future;
        /** COMPACT-IDS: the chunk's decide-kernel aux, or null (plain corner request). */
        final CompactIds.ChunkAux aux;
        /**
         * CLIMATE CHAIN: completed with the chunk's packed {@code climM*climN} climate
         * quart grids when the batch ran the chained climate program, exceptionally when
         * it could not (caller funnels the miss); {@code null} = plain density request.
         */
        final CompletableFuture<double[]> climFuture;
        /** Optional authorization target for async AUX requests; null is reserved for legacy/synthetic callers. */
        final GpuWorldgenPolicy.WorldScope worldScope;
        /**
         * SUBMIT time (nanoTime at enqueue): the documented flush-window contract anchors
         * at the group's OLDEST PENDING REQUEST, so the deadline must be derived from when
         * the submitter enqueued, NOT from when the drainer eventually bucketed it (which
         * can be a full synchronous dispatch later — silently adding up to one dispatch
         * duration of extra wait per request and idling the GPU during every window).
         */
        final long submitNanos;
        /**
         * COMPACT-IDS: this chunk's 1-byte/block ids, written by the drainer BEFORE it
         * completes {@link #future} (completion is the happens-before edge for readers).
         */
        volatile byte[] ids;

        Req(int ox, int oy, int oz, GeometryKey key, CompletableFuture<double[]> future,
            CompactIds.ChunkAux aux) {
            this(ox, oy, oz, key, future, aux, null, key.worldScope());
        }

        Req(int ox, int oy, int oz, GeometryKey key, CompletableFuture<double[]> future,
            CompactIds.ChunkAux aux, CompletableFuture<double[]> climFuture) {
            this(ox, oy, oz, key, future, aux, climFuture, key.worldScope());
        }

        Req(int ox, int oy, int oz, GeometryKey key, CompletableFuture<double[]> future,
            CompactIds.ChunkAux aux, CompletableFuture<double[]> climFuture,
            GpuWorldgenPolicy.WorldScope worldScope) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.key = key;
            this.future = future;
            this.aux = aux;
            this.climFuture = climFuture;
            this.worldScope = worldScope;
            this.submitNanos = System.nanoTime();
        }
    }
}
