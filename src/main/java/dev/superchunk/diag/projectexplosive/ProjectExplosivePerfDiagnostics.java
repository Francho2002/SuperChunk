package dev.superchunk.diag.projectexplosive;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Optional, bounded diagnostics for the verified Project: Explosive 0.7.2-beta call sites.
 * This class deliberately has no Minecraft types or references to game objects.
 */
public final class ProjectExplosivePerfDiagnostics {
    public static final String PROPERTY = "superchunk.diagnostics.projectExplosive";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    private static final int MAX_DEPTH = 8;
    private static final long NO_TIMER = Long.MIN_VALUE;
    private static final long UNKNOWN = Integer.MIN_VALUE;
    private static final AtomicLong IDS = new AtomicLong();
    private static final Tracker PRODUCTION = new Tracker(
            ENABLED,
            System::nanoTime,
            ENABLED ? cpuClock() : () -> -1L,
            line -> dev.superchunk.SuperChunk.LOGGER.info(line),
            ENABLED ? intProperty("superchunk.diagnostics.projectExplosive.minDurationMs", 100) : 100,
            ENABLED ? intProperty("superchunk.diagnostics.projectExplosive.maxLogsPerMinute", 6) : 6,
            IDS::incrementAndGet);

    private ProjectExplosivePerfDiagnostics() {
    }

    public enum ScopeKind {
        TERRAIN_SWEEP,
        RADIAL_DESTRUCTION
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void reportBootstrap() {
        if (!ENABLED) return;
        dev.superchunk.SuperChunk.LOGGER.info(
                "[FASTCHUNK-PE-PERF] diagnostics enabled; bytecodeTargets terrain={}, terrainChunkCache={}, radial={}, radialChunkCache={}",
                targetMatches("lil1llilol11.lo1i1iloioil.lo1iioil1oo1i0io01l1i1lo"),
                targetMatches("lil1llilol11.lo1i1iloioil.lo1iioil1oo1i0io01l1i1lo$l0o001o0ol0ilooioooil11i"),
                targetMatches("lil1llilol11.lo1i1iloioil.l1o010il000l0o100li0oili"),
                targetMatches("lil1llilol11.lo1i1iloioil.l1o010il000l0o100li0oili$ll1ol00o111oioool0l011lo"));
    }

    /** True only when this exact target class has the bytecode audited for this integration. */
    public static boolean targetMatches(String dottedClassName) {
        return ENABLED && targetBytecodeMatches(dottedClassName);
    }

    /** Verifies the pinned optional target class independently of whether performance logs are enabled. */
    public static boolean targetBytecodeMatches(String dottedClassName) {
        if (dottedClassName == null) return false;
        try {
            String expected = expectedClassHash(dottedClassName);
            return expected != null && expected.equals(classResourceHash(dottedClassName));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String expectedClassHash(String name) {
        return switch (name) {
            case "lil1llilol11.lo1i1iloioil.lo1iioil1oo1i0io01l1i1lo" ->
                    "40E16B01A05F1D5BFAB680F54A317C9301A0CFA3F1344DCDABB0A79816FFB90F";
            case "lil1llilol11.lo1i1iloioil.lo1iioil1oo1i0io01l1i1lo$l0o001o0ol0ilooioooil11i" ->
                    "2FE68D08F9B327798445DB91B84195E8DD02863EB783EA1CBAC1D3B4E9CB39F0";
            case "lil1llilol11.lo1i1iloioil.l1o010il000l0o100li0oili" ->
                    "4212D88EF69E9AE1566AA1A9086091334F81FAB1EC5CED78B90D2EEDA2D8119E";
            case "lil1llilol11.lo1i1iloioil.l1o010il000l0o100li0oili$ll1ol00o111oioool0l011lo" ->
                    "5282BE095320C4DE092ACF45968D2B3D4EE6EC0D5A7F2DA5A49C36604035D363";
            default -> null;
        };
    }

    private static String classResourceHash(String dottedClassName) {
        String resource = dottedClassName.replace('.', '/') + ".class";
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader ownLoader = ProjectExplosivePerfDiagnostics.class.getClassLoader();
        try (InputStream input = contextLoader == null ? null : contextLoader.getResourceAsStream(resource)) {
            if (input != null) return hash(input);
        } catch (IOException | SecurityException ignored) {
            return null;
        }
        try (InputStream input = ownLoader == null ? null : ownLoader.getResourceAsStream(resource)) {
            return input == null ? null : hash(input);
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private static String hash(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    public static Scope beginTerrainSweep(int targetChunkX, int targetChunkZ, double targetRadiusBlocks) {
        return beginTerrainSweep(targetChunkX, targetChunkZ, targetRadiusBlocks, Double.NaN, Double.NaN);
    }

    public static Scope beginTerrainSweep(int targetChunkX, int targetChunkZ, double targetRadiusBlocks,
                                          double originalOuterRadiusBlocks, double effectiveOuterRadiusBlocks) {
        try {
            return PRODUCTION.begin(ScopeKind.TERRAIN_SWEEP, targetChunkX, targetChunkZ, targetRadiusBlocks, 18.0,
                    originalOuterRadiusBlocks, effectiveOuterRadiusBlocks);
        } catch (Throwable ignored) {
            return Scope.NOOP;
        }
    }

    public static Scope beginRadialDestruction(double centerX, double centerZ, double radiusBlocks) {
        try {
            return PRODUCTION.begin(ScopeKind.RADIAL_DESTRUCTION,
                    floorChunk(centerX), floorChunk(centerZ), radiusBlocks, Double.NaN);
        } catch (Throwable ignored) {
            return Scope.NOOP;
        }
    }

    public static long startOperation(ScopeKind expectedKind) {
        try {
            return PRODUCTION.startOperation(expectedKind);
        } catch (Throwable ignored) {
            return NO_TIMER;
        }
    }

    public static long startBlockMutation() {
        try {
            return PRODUCTION.startBlockMutation();
        } catch (Throwable ignored) {
            return NO_TIMER;
        }
    }

    public static boolean isActive(ScopeKind expectedKind) {
        try {
            return PRODUCTION.isActive(expectedKind);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void recordIteration(long startNanos, int chunkX, int chunkZ) {
        try { PRODUCTION.recordIteration(startNanos, chunkX, chunkZ); } catch (Throwable ignored) { }
    }

    public static void recordChunkNow(long startNanos, int chunkX, int chunkZ, boolean missing, boolean failed) {
        try { PRODUCTION.recordChunkNow(startNanos, chunkX, chunkZ, missing, failed); } catch (Throwable ignored) { }
    }

    public static void recordTicketRequest(int chunkX, int chunkZ) {
        try { PRODUCTION.recordTicketRequest(chunkX, chunkZ); } catch (Throwable ignored) { }
    }

    public static void recordSetBlock(long startNanos, int chunkX, int chunkZ, boolean changed, boolean failed) {
        try { PRODUCTION.recordSetBlock(startNanos, chunkX, chunkZ, changed, failed); } catch (Throwable ignored) { }
    }

    public static void recordSynchronousGetChunk(long startNanos, int chunkX, int chunkZ, boolean failed,
                                                 String exceptionClass) {
        try { PRODUCTION.recordSynchronousGetChunk(startNanos, chunkX, chunkZ, failed, exceptionClass); } catch (Throwable ignored) { }
    }

    public static void finishSafely(Scope scope, Throwable failure) {
        if (scope == null) return;
        try {
            scope.finish(failure);
        } catch (Throwable ignored) {
            // Optional diagnostics must never replace or suppress Project: Explosive behavior.
        }
    }

    private static CpuClock cpuClock() {
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!bean.isCurrentThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) return () -> -1L;
            return () -> {
                try {
                    return bean.getCurrentThreadCpuTime();
                } catch (UnsupportedOperationException | SecurityException ignored) {
                    return -1L;
                }
            };
        } catch (SecurityException | UnsupportedOperationException ignored) {
            return () -> -1L;
        }
    }

    private static int intProperty(String property, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(System.getProperty(property, Integer.toString(fallback))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int floorChunk(double blockCoordinate) {
        if (!Double.isFinite(blockCoordinate)) return Integer.MIN_VALUE;
        double chunk = Math.floor(blockCoordinate / 16.0);
        return chunk < Integer.MIN_VALUE || chunk > Integer.MAX_VALUE ? Integer.MIN_VALUE : (int) chunk;
    }

    private static long elapsed(long start, long end) {
        if (start == NO_TIMER) return -1L;
        long value = end - start;
        return value < 0 ? -1L : value;
    }

    private static long addSaturated(long current, long addition) {
        if (addition <= 0) return current;
        return Long.MAX_VALUE - current < addition ? Long.MAX_VALUE : current + addition;
    }

    private static String ms(long nanos) {
        return nanos < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String coordinate(int x, int z) {
        return x == Integer.MIN_VALUE || z == Integer.MIN_VALUE ? "n/a" : x + "," + z;
    }

    @FunctionalInterface
    interface CpuClock {
        long currentThreadCpuNanos();
    }

    @FunctionalInterface
    interface LogSink {
        void log(String line);
    }

    /** A thread-confined tracker is injectable so JVM regressions can use deterministic clocks. */
    static final class Tracker {
        private final boolean enabled;
        private final LongSupplier wallClock;
        private final CpuClock cpuClock;
        private final LogSink logSink;
        private final int minimumDurationMs;
        private final int maximumLogsPerMinute;
        private final LongSupplier idSupplier;
        private final ThreadLocal<Context> contexts = new ThreadLocal<>();
        private final Object rateLimitLock = new Object();
        private long rateWindowStart;
        private int logsInWindow;

        Tracker(boolean enabled, LongSupplier wallClock, CpuClock cpuClock, LogSink logSink,
                int minimumDurationMs, int maximumLogsPerMinute, LongSupplier idSupplier) {
            this.enabled = enabled;
            this.wallClock = wallClock;
            this.cpuClock = cpuClock;
            this.logSink = logSink;
            this.minimumDurationMs = Math.max(1, minimumDurationMs);
            this.maximumLogsPerMinute = Math.max(1, Math.min(60, maximumLogsPerMinute));
            this.idSupplier = idSupplier;
        }

        Scope begin(ScopeKind kind, int chunkX, int chunkZ, double radiusBlocks, double budgetMs) {
            return begin(kind, chunkX, chunkZ, radiusBlocks, budgetMs, Double.NaN, Double.NaN);
        }

        Scope begin(ScopeKind kind, int chunkX, int chunkZ, double radiusBlocks, double budgetMs,
                    double originalOuterRadiusBlocks, double effectiveOuterRadiusBlocks) {
            if (!enabled) return Scope.NOOP;
            Context context = contexts.get();
            if (context == null) {
                context = new Context();
                contexts.set(context);
            }
            if (context.overflowDepth > 0 || context.depth >= MAX_DEPTH) {
                if (context.overflowDepth < Integer.MAX_VALUE) context.overflowDepth++;
                return new Scope(this, true);
            }
            Scope parent = context.top;
            long startWall = wallClock.getAsLong();
            long startCpu = safeCpuTime();
            Scope scope = new Scope(this, kind, idSupplier.getAsLong(), parent == null ? -1L : parent.id,
                    startWall, startCpu, chunkX, chunkZ, radiusBlocks, budgetMs,
                    originalOuterRadiusBlocks, effectiveOuterRadiusBlocks, parent);
            context.top = scope;
            context.depth++;
            return scope;
        }

        private long safeCpuTime() {
            try {
                return cpuClock.currentThreadCpuNanos();
            } catch (RuntimeException ignored) {
                return -1L;
            }
        }

        boolean isActive(ScopeKind kind) {
            return enabled && current(kind) != null;
        }

        long startOperation(ScopeKind kind) {
            return isActive(kind) ? wallClock.getAsLong() : NO_TIMER;
        }

        long startBlockMutation() {
            return enabled && current(null) != null ? wallClock.getAsLong() : NO_TIMER;
        }

        private Scope current(ScopeKind kind) {
            if (!enabled) return null;
            Context context = contexts.get();
            if (context == null || context.overflowDepth > 0 || context.top == null || context.top.closed
                    || (kind != null && context.top.kind != kind)) return null;
            return context.top;
        }

        void recordIteration(long start, int chunkX, int chunkZ) {
            if (start == NO_TIMER) return;
            Scope scope = current(ScopeKind.TERRAIN_SWEEP);
            if (scope != null) scope.recordIterationNanos(elapsed(start, wallClock.getAsLong()), chunkX, chunkZ);
        }

        void recordChunkNow(long start, int chunkX, int chunkZ, boolean missing, boolean failed) {
            if (start == NO_TIMER) return;
            Scope scope = current(ScopeKind.TERRAIN_SWEEP);
            if (scope != null) scope.recordChunkNowNanos(elapsed(start, wallClock.getAsLong()), chunkX, chunkZ, missing, failed);
        }

        void recordTicketRequest(int chunkX, int chunkZ) {
            Scope scope = current(ScopeKind.TERRAIN_SWEEP);
            if (scope != null) scope.recordTicketRequest(chunkX, chunkZ);
        }

        void recordSetBlock(long start, int chunkX, int chunkZ, boolean changed, boolean failed) {
            if (start == NO_TIMER) return;
            Scope scope = current(null);
            if (scope != null) scope.recordSetBlockNanos(elapsed(start, wallClock.getAsLong()), chunkX, chunkZ, changed, failed);
        }

        void recordSynchronousGetChunk(long start, int chunkX, int chunkZ, boolean failed, String exceptionClass) {
            if (start == NO_TIMER) return;
            Scope scope = current(ScopeKind.RADIAL_DESTRUCTION);
            if (scope != null) scope.recordSynchronousGetChunkNanos(elapsed(start, wallClock.getAsLong()),
                    chunkX, chunkZ, failed, exceptionClass);
        }

        private void finish(Scope scope, Throwable failure) {
            if (scope.overflow) {
                Context context = contexts.get();
                if (context == null || context.overflowDepth <= 0) return;
                context.overflowDepth--;
                cleanupIfEmpty(context);
                scope.closed = true;
                return;
            }
            if (scope.closed) return;
            long endWall = wallClock.getAsLong();
            long elapsedNanos = elapsed(scope.startWallNanos, endWall);
            long endCpu = safeCpuTime();
            long cpuNanos = scope.startCpuNanos < 0 || endCpu < scope.startCpuNanos ? -1L : endCpu - scope.startCpuNanos;
            Context context = contexts.get();
            boolean restored = context != null && context.top == scope;
            if (restored) {
                context.top = scope.previous;
                context.depth--;
                if (scope.previous != null && elapsedNanos >= 0) {
                    scope.previous.nestedElapsedNanos = addSaturated(scope.previous.nestedElapsedNanos, elapsedNanos);
                }
            } else if (context != null) {
                context.top = null;
                context.depth = 0;
                context.overflowDepth = 0;
            }
            scope.closed = true;
            scope.previous = null;
            if (context != null) cleanupIfEmpty(context);
            if (elapsedNanos >= 0 && elapsedNanos >= minimumDurationMs * 1_000_000L && allowLog(endWall)) {
                logSink.log(scope.summary(elapsedNanos, cpuNanos, failure, restored));
            }
        }

        private void cleanupIfEmpty(Context context) {
            if (context.top == null && context.depth == 0 && context.overflowDepth == 0) contexts.remove();
        }

        private boolean allowLog(long now) {
            synchronized (rateLimitLock) {
                if (rateWindowStart == 0 || now - rateWindowStart >= 60_000_000_000L || now < rateWindowStart) {
                    rateWindowStart = now == 0 ? 1 : now;
                    logsInWindow = 0;
                }
                if (logsInWindow >= maximumLogsPerMinute) return false;
                logsInWindow++;
                return true;
            }
        }
    }

    private static final class Context {
        private Scope top;
        private int depth;
        private int overflowDepth;
    }

    public static final class Scope implements AutoCloseable {
        private static final Scope NOOP = new Scope();
        private final Tracker owner;
        private final boolean overflow;
        private final ScopeKind kind;
        private final long id;
        private final long parentId;
        private final long startWallNanos;
        private final long startCpuNanos;
        private final int targetChunkX;
        private final int targetChunkZ;
        private final double radiusBlocks;
        private final double budgetMs;
        private final double originalOuterRadiusBlocks;
        private final double effectiveOuterRadiusBlocks;
        private Scope previous;
        private boolean closed;
        private long iterations;
        private long timedIterations;
        private long iterationTotalNanos;
        private long maxIterationNanos;
        private int maxIterationChunkX = Integer.MIN_VALUE;
        private int maxIterationChunkZ = Integer.MIN_VALUE;
        private long chunkNowCalls;
        private long timedChunkNowCalls;
        private long chunkNowMisses;
        private long chunkNowFailures;
        private long chunkNowTotalNanos;
        private long chunkNowMaxNanos;
        private int chunkNowMaxX = Integer.MIN_VALUE;
        private int chunkNowMaxZ = Integer.MIN_VALUE;
        private long ticketRequests;
        private long setBlockCalls;
        private long timedSetBlockCalls;
        private long setBlockChanges;
        private long setBlockFailures;
        private long setBlockTotalNanos;
        private long setBlockMaxNanos;
        private int setBlockMaxX = Integer.MIN_VALUE;
        private int setBlockMaxZ = Integer.MIN_VALUE;
        private long syncChunkCalls;
        private long syncChunkReturns;
        private long timedSyncChunkCalls;
        private long syncChunkFailures;
        private long syncChunkTotalNanos;
        private long syncChunkMaxNanos;
        private String syncChunkExceptionClass = "n/a";
        private int syncChunkMaxX = Integer.MIN_VALUE;
        private int syncChunkMaxZ = Integer.MIN_VALUE;
        private long nestedElapsedNanos;
        private long invalidDurations;

        private Scope() {
            this.owner = null;
            this.overflow = false;
            this.kind = null;
            this.id = -1;
            this.parentId = -1;
            this.startWallNanos = 0;
            this.startCpuNanos = -1;
            this.targetChunkX = Integer.MIN_VALUE;
            this.targetChunkZ = Integer.MIN_VALUE;
            this.radiusBlocks = Double.NaN;
            this.budgetMs = Double.NaN;
            this.originalOuterRadiusBlocks = Double.NaN;
            this.effectiveOuterRadiusBlocks = Double.NaN;
            this.closed = true;
        }

        private Scope(Tracker owner, boolean overflow) {
            this.owner = owner;
            this.overflow = overflow;
            this.kind = null;
            this.id = -1;
            this.parentId = -1;
            this.startWallNanos = 0;
            this.startCpuNanos = -1;
            this.targetChunkX = Integer.MIN_VALUE;
            this.targetChunkZ = Integer.MIN_VALUE;
            this.radiusBlocks = Double.NaN;
            this.budgetMs = Double.NaN;
            this.originalOuterRadiusBlocks = Double.NaN;
            this.effectiveOuterRadiusBlocks = Double.NaN;
        }

        private Scope(Tracker owner, ScopeKind kind, long id, long parentId, long startWallNanos, long startCpuNanos,
                      int targetChunkX, int targetChunkZ, double radiusBlocks, double budgetMs,
                      double originalOuterRadiusBlocks, double effectiveOuterRadiusBlocks, Scope previous) {
            this.owner = owner;
            this.overflow = false;
            this.kind = kind;
            this.id = id;
            this.parentId = parentId;
            this.startWallNanos = startWallNanos;
            this.startCpuNanos = startCpuNanos;
            this.targetChunkX = targetChunkX;
            this.targetChunkZ = targetChunkZ;
            this.radiusBlocks = radiusBlocks;
            this.budgetMs = budgetMs;
            this.originalOuterRadiusBlocks = originalOuterRadiusBlocks;
            this.effectiveOuterRadiusBlocks = effectiveOuterRadiusBlocks;
            this.previous = previous;
        }

        void recordIterationNanos(long duration, int chunkX, int chunkZ) {
            if (kind != ScopeKind.TERRAIN_SWEEP) return;
            iterations = increment(iterations);
            if (!valid(duration)) return;
            boolean firstTimed = timedIterations == 0;
            timedIterations = increment(timedIterations);
            iterationTotalNanos = addSaturated(iterationTotalNanos, duration);
            if (firstTimed || duration > maxIterationNanos) {
                maxIterationNanos = duration;
                maxIterationChunkX = chunkX;
                maxIterationChunkZ = chunkZ;
            }
        }

        void recordChunkNowNanos(long duration, int chunkX, int chunkZ, boolean missing, boolean failed) {
            if (kind != ScopeKind.TERRAIN_SWEEP) return;
            chunkNowCalls = increment(chunkNowCalls);
            if (missing) chunkNowMisses = increment(chunkNowMisses);
            if (failed) chunkNowFailures = increment(chunkNowFailures);
            if (addDuration(duration, true)) {
                boolean firstTimed = timedChunkNowCalls == 0;
                timedChunkNowCalls = increment(timedChunkNowCalls);
                chunkNowTotalNanos = addSaturated(chunkNowTotalNanos, duration);
                if (firstTimed || duration > chunkNowMaxNanos) {
                    chunkNowMaxNanos = duration;
                    chunkNowMaxX = chunkX;
                    chunkNowMaxZ = chunkZ;
                }
            }
        }

        void recordTicketRequest(int chunkX, int chunkZ) {
            if (kind == ScopeKind.TERRAIN_SWEEP) ticketRequests = increment(ticketRequests);
        }

        void recordSetBlockNanos(long duration, int chunkX, int chunkZ, boolean changed, boolean failed) {
            if (kind == null) return;
            setBlockCalls = increment(setBlockCalls);
            if (changed) setBlockChanges = increment(setBlockChanges);
            if (failed) setBlockFailures = increment(setBlockFailures);
            if (addDuration(duration, true)) {
                boolean firstTimed = timedSetBlockCalls == 0;
                timedSetBlockCalls = increment(timedSetBlockCalls);
                setBlockTotalNanos = addSaturated(setBlockTotalNanos, duration);
                if (firstTimed || duration > setBlockMaxNanos) {
                    setBlockMaxNanos = duration;
                    setBlockMaxX = chunkX;
                    setBlockMaxZ = chunkZ;
                }
            }
        }

        void recordSynchronousGetChunkNanos(long duration, int chunkX, int chunkZ, boolean failed,
                                             String exceptionClass) {
            if (kind != ScopeKind.RADIAL_DESTRUCTION) return;
            syncChunkCalls = increment(syncChunkCalls);
            if (failed) {
                syncChunkFailures = increment(syncChunkFailures);
                if (syncChunkExceptionClass.equals("n/a") && exceptionClass != null) {
                    syncChunkExceptionClass = exceptionClass.length() > 80 ? exceptionClass.substring(0, 80) : exceptionClass;
                }
            }
            else syncChunkReturns = increment(syncChunkReturns);
            if (addDuration(duration, true)) {
                boolean firstTimed = timedSyncChunkCalls == 0;
                timedSyncChunkCalls = increment(timedSyncChunkCalls);
                syncChunkTotalNanos = addSaturated(syncChunkTotalNanos, duration);
                if (firstTimed || duration > syncChunkMaxNanos) {
                    syncChunkMaxNanos = duration;
                    syncChunkMaxX = chunkX;
                    syncChunkMaxZ = chunkZ;
                }
            }
        }

        private boolean addDuration(long duration, boolean attributed) {
            if (duration >= 0) return true;
            invalidDurations = increment(invalidDurations);
            return false;
        }

        private boolean valid(long duration) {
            if (duration >= 0) return true;
            invalidDurations = increment(invalidDurations);
            return false;
        }

        private String summary(long elapsedNanos, long cpuNanos, Throwable failure, boolean restored) {
            long attributed = addSaturated(addSaturated(chunkNowTotalNanos, setBlockTotalNanos), syncChunkTotalNanos);
            attributed = addSaturated(attributed, nestedElapsedNanos);
            long unattributed = elapsedNanos >= attributed ? elapsedNanos - attributed : 0;
            String outcome = failure == null ? "completed" : "exception:" + failure.getClass().getSimpleName();
            String prefix = "[FASTCHUNK-PE-PERF] scope=" + kind + " id=" + id + " parentId="
                    + (parentId < 0 ? "n/a" : Long.toString(parentId)) + " outcome=" + outcome
                    + " scopeRestored=" + restored + " elapsedMs=" + ms(elapsedNanos) + " cpuMs=" + ms(cpuNanos)
                    + " maxOperation=" + maxOperationName() + " maxOperationMs="
                    + (maxOperationName().equals("n/a") ? "n/a" : ms(maxOperationNanos()))
                    + " maxOperationChunk=" + maxOperationChunk();
            if (kind == ScopeKind.TERRAIN_SWEEP) {
                return prefix + " iterations=" + iterations + " iterationTotalMs=" + (timedIterations == 0 ? "n/a" : ms(iterationTotalNanos))
                        + " maxIterationMs=" + (timedIterations == 0 ? "n/a" : ms(maxIterationNanos))
                        + " maxIterationChunk=" + coordinate(maxIterationChunkX, maxIterationChunkZ)
                        + " chunkNowCalls=" + chunkNowCalls + " chunkNowMisses=" + chunkNowMisses
                        + " chunkNowFailures=" + chunkNowFailures + " chunkNowTotalMs=" + (timedChunkNowCalls == 0 ? "n/a" : ms(chunkNowTotalNanos))
                        + " chunkNowMaxMs=" + (timedChunkNowCalls == 0 ? "n/a" : ms(chunkNowMaxNanos)) + " chunkNowMaxChunk="
                        + coordinate(chunkNowMaxX, chunkNowMaxZ) + " ticketRequests=" + ticketRequests
                        + " setBlockCalls=" + setBlockCalls + " setBlockChanges=" + setBlockChanges
                        + " setBlockFailures=" + setBlockFailures + " setBlockTotalMs=" + (timedSetBlockCalls == 0 ? "n/a" : ms(setBlockTotalNanos))
                        + " setBlockMaxMs=" + (timedSetBlockCalls == 0 ? "n/a" : ms(setBlockMaxNanos)) + " setBlockMaxChunk="
                        + coordinate(setBlockMaxX, setBlockMaxZ) + " syncGetChunkCalls=n/a"
                        + " radiusBlocks=" + numeric(radiusBlocks) + " radiusChunks=" + radiusChunks(radiusBlocks)
                        + " outerSweepOriginalRadiusBlocks=" + numeric(originalOuterRadiusBlocks)
                        + " outerSweepEffectiveRadiusBlocks=" + numeric(effectiveOuterRadiusBlocks)
                        + " outerSweepCandidateColumnsAvoidedEstimate=" + candidateColumnsAvoided()
                        + " targetChunk=" + coordinate(targetChunkX, targetChunkZ) + " budgetMs=" + numeric(budgetMs)
                        + " nestedMs=" + ms(nestedElapsedNanos) + " unattributedMs=" + ms(unattributed)
                        + " invalidDurations=" + invalidDurations;
            }
            return prefix + " iterations=n/a chunkNowCalls=n/a ticketRequests=n/a setBlockCalls=" + setBlockCalls
                    + " setBlockChanges=" + setBlockChanges + " setBlockFailures=" + setBlockFailures
                    + " setBlockTotalMs=" + (timedSetBlockCalls == 0 ? "n/a" : ms(setBlockTotalNanos)) + " setBlockMaxMs=" + (timedSetBlockCalls == 0 ? "n/a" : ms(setBlockMaxNanos))
                    + " syncGetChunkCalls=" + syncChunkCalls + " syncGetChunkReturned=" + syncChunkReturns
                    + " syncGetChunkFailures=" + syncChunkFailures
                    + " syncGetChunkException=" + syncChunkExceptionClass
                    + " syncGetChunkTotalMs=" + (timedSyncChunkCalls == 0 ? "n/a" : ms(syncChunkTotalNanos)) + " syncGetChunkMaxMs=" + (timedSyncChunkCalls == 0 ? "n/a" : ms(syncChunkMaxNanos))
                    + " syncGetChunkMaxChunk=" + coordinate(syncChunkMaxX, syncChunkMaxZ)
                    + " radiusBlocks=" + numeric(radiusBlocks) + " radiusChunks=" + radiusChunks(radiusBlocks)
                    + " targetChunk=" + coordinate(targetChunkX, targetChunkZ) + " budgetMs=n/a"
                    + " nestedMs=" + ms(nestedElapsedNanos) + " unattributedMs=" + ms(unattributed)
                    + " invalidDurations=" + invalidDurations;
        }

        private String maxOperationName() {
            long max = -1;
            String name = "n/a";
            if (timedIterations > 0 && kind == ScopeKind.TERRAIN_SWEEP && maxIterationNanos > max) { max = maxIterationNanos; name = "terrain-iteration"; }
            if (timedChunkNowCalls > 0 && chunkNowMaxNanos > max) { max = chunkNowMaxNanos; name = "getChunkNow"; }
            if (timedSyncChunkCalls > 0 && syncChunkMaxNanos > max) { max = syncChunkMaxNanos; name = "getChunk"; }
            if (timedSetBlockCalls > 0 && setBlockMaxNanos > max) { max = setBlockMaxNanos; name = "block-mutation"; }
            return name;
        }

        private long maxOperationNanos() {
            long max = kind == ScopeKind.TERRAIN_SWEEP && timedIterations > 0 ? maxIterationNanos : -1;
            if (timedChunkNowCalls > 0) max = Math.max(max, chunkNowMaxNanos);
            if (timedSyncChunkCalls > 0) max = Math.max(max, syncChunkMaxNanos);
            if (timedSetBlockCalls > 0) max = Math.max(max, setBlockMaxNanos);
            return max;
        }

        private String maxOperationChunk() {
            long max = kind == ScopeKind.TERRAIN_SWEEP && timedIterations > 0 ? maxIterationNanos : -1;
            int x = timedIterations > 0 ? maxIterationChunkX : Integer.MIN_VALUE;
            int z = timedIterations > 0 ? maxIterationChunkZ : Integer.MIN_VALUE;
            if (timedChunkNowCalls > 0 && chunkNowMaxNanos > max) { max = chunkNowMaxNanos; x = chunkNowMaxX; z = chunkNowMaxZ; }
            if (timedSyncChunkCalls > 0 && syncChunkMaxNanos > max) { max = syncChunkMaxNanos; x = syncChunkMaxX; z = syncChunkMaxZ; }
            if (timedSetBlockCalls > 0 && setBlockMaxNanos > max) { x = setBlockMaxX; z = setBlockMaxZ; }
            return coordinate(x, z);
        }

        private static String radiusChunks(double radiusBlocks) {
            if (!Double.isFinite(radiusBlocks) || radiusBlocks < 0 || radiusBlocks / 16.0 > Integer.MAX_VALUE) return "n/a";
            return Integer.toString((int) Math.ceil(radiusBlocks / 16.0));
        }

        private String candidateColumnsAvoided() {
            if (!Double.isFinite(originalOuterRadiusBlocks) || !Double.isFinite(effectiveOuterRadiusBlocks)
                    || originalOuterRadiusBlocks < effectiveOuterRadiusBlocks || effectiveOuterRadiusBlocks < 0.0) {
                return "n/a";
            }
            long original = openCircleColumnCount(originalOuterRadiusBlocks);
            long effective = openCircleColumnCount(effectiveOuterRadiusBlocks);
            return original < 0 || effective < 0 ? "n/a" : Long.toString(original - effective);
        }

        /** Exact integer X/Z candidates omitted by the audited open-annulus iterator, bounded by its 600-block cap. */
        private static long openCircleColumnCount(double radiusBlocks) {
            if (!Double.isFinite(radiusBlocks) || radiusBlocks < 0.0 || radiusBlocks > 600.0) return -1L;
            double radiusSquared = radiusBlocks * radiusBlocks;
            int limit = (int) Math.ceil(radiusBlocks);
            long count = 0L;
            for (int z = -limit; z <= limit; z++) {
                double zSquared = (double) z * z;
                if (zSquared >= radiusSquared) continue;
                int maxX = (int) Math.floor(Math.sqrt(radiusSquared - zSquared));
                if (zSquared + (double) maxX * maxX >= radiusSquared) maxX--;
                if (maxX >= 0) count += 2L * maxX + 1L;
            }
            return count;
        }

        private static String numeric(double number) {
            return Double.isFinite(number) ? String.format(java.util.Locale.ROOT, "%.3f", number) : "n/a";
        }

        private static long increment(long value) {
            return value == Long.MAX_VALUE ? value : value + 1;
        }

        void finish(Throwable failure) {
            if (owner != null) owner.finish(this, failure);
        }

        @Override
        public void close() {
            finish(null);
        }
    }
}
