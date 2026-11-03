package dev.superchunk.diag.projectexplosive;

import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics.Scope;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics.ScopeKind;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics.Tracker;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** JVM-only deterministic checks of the diagnostics state machine and metric accumulation. */
public final class ProjectExplosivePerfDiagnosticsRegressionTest {
    private ProjectExplosivePerfDiagnosticsRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        disabledTrackerHasNoScopeOrRecords();
        operationCountersAndMaximaAreExact();
        nestedScopesStaySeparateAndRestore();
        exceptionClosesAndRestoresScope();
        overflowIsBoundedAndSuppressesAttribution();
        rateLimitAndThresholdAreEnforced();
        unavailableCpuAndInvalidDurationsAreNotReportedAsZero();
        diagnosticsRetainNoGameObjects();
        System.out.println("ProjectExplosivePerfDiagnosticsRegressionTest passed");
    }

    private static void disabledTrackerHasNoScopeOrRecords() {
        Fixture fixture = new Fixture(false, -1);
        Scope scope = fixture.tracker.begin(ScopeKind.TERRAIN_SWEEP, 1, 2, 600.0, 18.0);
        check(!fixture.tracker.isActive(ScopeKind.TERRAIN_SWEEP), "OFF creates no current context");
        check(fixture.tracker.startOperation(ScopeKind.TERRAIN_SWEEP) == Long.MIN_VALUE,
                "OFF returns the no-timer sentinel");
        scope.finish(null);
        check(fixture.lines.isEmpty(), "OFF emits no record");
    }

    private static void operationCountersAndMaximaAreExact() {
        Fixture fixture = new Fixture(true, 700L);
        Scope scope = fixture.tracker.begin(ScopeKind.TERRAIN_SWEEP, 10, -4, 600.0, 18.0);
        scope.recordIterationNanos(2_000_000L, 10, -4);
        scope.recordIterationNanos(9_000_000L, 11, -4);
        scope.recordChunkNowNanos(1_500_000L, 11, -4, false, false);
        scope.recordChunkNowNanos(3_500_000L, 12, -4, true, false);
        scope.recordTicketRequest(12, -4);
        scope.recordSetBlockNanos(800_000L, 12, -4, true, false);
        fixture.wall.addAndGet(1_200_000_000L);
        fixture.cpu.addAndGet(900_000_000L);
        scope.finish(null);
        String line = onlyLine(fixture.lines);
        contains(line, "iterations=2", "iteration count");
        contains(line, "iterationTotalMs=11.000", "iteration duration sum");
        contains(line, "maxIterationMs=9.000", "maximum iteration duration");
        contains(line, "chunkNowCalls=2", "getChunkNow count");
        contains(line, "chunkNowMisses=1", "missing chunk count");
        contains(line, "chunkNowTotalMs=5.000", "query duration sum");
        contains(line, "chunkNowMaxMs=3.500", "query maximum");
        contains(line, "ticketRequests=1", "ticket request count");
        contains(line, "setBlockCalls=1", "mutation call count");
        contains(line, "setBlockChanges=1", "successful mutation count");
        contains(line, "radiusChunks=38", "block radius conversion");
        contains(line, "budgetMs=18.000", "nominal budget");
        contains(line, "maxOperation=terrain-iteration", "largest leaf/iteration label");
        contains(line, "unattributedMs=1194.200", "unattributed elapsed duration");
    }

    private static void nestedScopesStaySeparateAndRestore() {
        Fixture fixture = new Fixture(true, 0L);
        Scope terrain = fixture.tracker.begin(ScopeKind.TERRAIN_SWEEP, 0, 0, 100.0, 18.0);
        terrain.recordChunkNowNanos(2_000_000L, 1, 0, false, false);
        Scope radial = fixture.tracker.begin(ScopeKind.RADIAL_DESTRUCTION, 1, 0, 32.0, Double.NaN);
        radial.recordSynchronousGetChunkNanos(7_000_000L, 1, 0, false, null);
        radial.recordSynchronousGetChunkNanos(1_000_000L, 2, 0, true, "IllegalStateException");
        radial.recordSetBlockNanos(2_000_000L, 1, 0, true, false);
        check(fixture.tracker.isActive(ScopeKind.RADIAL_DESTRUCTION), "nested radial scope is current");
        check(!fixture.tracker.isActive(ScopeKind.TERRAIN_SWEEP), "child events cannot be attributed to terrain");
        fixture.wall.addAndGet(20_000_000L);
        radial.finish(null);
        check(fixture.tracker.isActive(ScopeKind.TERRAIN_SWEEP), "parent scope restored after child");
        fixture.wall.addAndGet(40_000_000L);
        terrain.finish(null);
        String terrainLine = fixture.lines.stream().filter(s -> s.contains("scope=TERRAIN_SWEEP")).findFirst().orElseThrow();
        String radialLine = fixture.lines.stream().filter(s -> s.contains("scope=RADIAL_DESTRUCTION")).findFirst().orElseThrow();
        contains(radialLine, "parentId=" + idOf(terrainLine), "nested correlation id");
        contains(radialLine, "syncGetChunkCalls=2", "radial sync calls stay radial");
        contains(radialLine, "syncGetChunkReturned=1", "radial successful results stay explicit");
        contains(radialLine, "syncGetChunkFailures=1", "radial failures are separate from results");
        contains(radialLine, "syncGetChunkException=IllegalStateException", "radial exception class is retained");
        contains(radialLine, "chunkNowCalls=n/a", "not applicable metric stays n/a");
        contains(terrainLine, "chunkNowCalls=1", "terrain query remains terrain");
        contains(terrainLine, "syncGetChunkCalls=n/a", "radial query is not copied to terrain");
        contains(terrainLine, "nestedMs=20.000", "nested elapsed time is explicit");
    }

    private static void exceptionClosesAndRestoresScope() {
        Fixture fixture = new Fixture(true, 0L);
        Scope scope = fixture.tracker.begin(ScopeKind.TERRAIN_SWEEP, 0, 0, 16.0, 18.0);
        RuntimeException failure = new RuntimeException("test");
        fixture.wall.addAndGet(1_100_000_000L);
        scope.finish(failure);
        check(!fixture.tracker.isActive(ScopeKind.TERRAIN_SWEEP), "exceptional completion restores ThreadLocal state");
        contains(onlyLine(fixture.lines), "outcome=exception:RuntimeException", "exception result is recorded");
        contains(onlyLine(fixture.lines), "scopeRestored=true", "restoration status is reported");
    }

    private static void overflowIsBoundedAndSuppressesAttribution() {
        Fixture fixture = new Fixture(true, 0L);
        List<Scope> scopes = new ArrayList<>();
        for (int i = 0; i < 8; i++) scopes.add(fixture.tracker.begin(ScopeKind.TERRAIN_SWEEP, 0, 0, 16.0, 18.0));
        Scope overflow = fixture.tracker.begin(ScopeKind.RADIAL_DESTRUCTION, 0, 0, 16.0, Double.NaN);
        check(!fixture.tracker.isActive(ScopeKind.TERRAIN_SWEEP)
                        && !fixture.tracker.isActive(ScopeKind.RADIAL_DESTRUCTION),
                "overflow suppresses attribution rather than leaking into its parent");
        overflow.finish(null);
        check(fixture.tracker.isActive(ScopeKind.TERRAIN_SWEEP), "overflow unwind restores prior top scope");
        for (int i = scopes.size() - 1; i >= 0; i--) scopes.get(i).finish(null);
        check(!fixture.tracker.isActive(ScopeKind.TERRAIN_SWEEP), "bounded scope chain fully releases");
    }

    private static void rateLimitAndThresholdAreEnforced() {
        Fixture fixture = new Fixture(true, 0L, 1, 1);
        fixture.runScope(1_000_000_000L);
        fixture.runScope(1_000_000_000L);
        check(fixture.lines.size() == 1, "maximum summaries per minute enforced");
        Fixture belowThreshold = new Fixture(true, 0L, 50, 10);
        belowThreshold.runScope(49_000_000L);
        check(belowThreshold.lines.isEmpty(), "minimum duration threshold enforced");
    }

    private static void unavailableCpuAndInvalidDurationsAreNotReportedAsZero() {
        Fixture fixture = new Fixture(true, -1L);
        Scope scope = fixture.tracker.begin(ScopeKind.TERRAIN_SWEEP, 0, 0, Double.NaN, 18.0);
        scope.recordIterationNanos(-1L, 0, 0);
        fixture.wall.addAndGet(1_100_000_000L);
        scope.finish(null);
        String line = onlyLine(fixture.lines);
        contains(line, "cpuMs=n/a", "unsupported CPU time is explicit");
        contains(line, "maxIterationMs=n/a", "invalid iteration timing is not shown as zero");
        contains(line, "radiusChunks=n/a", "invalid radius is explicit");
        contains(line, "invalidDurations=1", "invalid duration is counted");
    }

    private static void diagnosticsRetainNoGameObjects() {
        for (Class<?> type : List.of(Scope.class, Tracker.class)) {
            for (Field field : type.getDeclaredFields()) {
                check(!field.getType().getName().startsWith("net.minecraft."),
                        "diagnostic state contains no game object field: " + type.getSimpleName() + "." + field.getName());
            }
        }
    }

    private static long idOf(String line) {
        int start = line.indexOf(" id=") + 4;
        int end = line.indexOf(' ', start);
        return Long.parseLong(line.substring(start, end));
    }

    private static String onlyLine(List<String> lines) {
        check(lines.size() == 1, "expected one summary, got " + lines.size());
        return lines.get(0);
    }

    private static void contains(String text, String expected, String description) {
        check(text.contains(expected), description + "; missing '" + expected + "' in " + text);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture {
        private final AtomicLong wall = new AtomicLong();
        private final AtomicLong cpu;
        private final List<String> lines = new ArrayList<>();
        private final Tracker tracker;

        private Fixture(boolean enabled, long cpuStart) {
            this(enabled, cpuStart, 1, 10);
        }

        private Fixture(boolean enabled, long cpuStart, int minimumMs, int maxLogsPerMinute) {
            this.cpu = new AtomicLong(cpuStart);
            LongSupplier ids = new AtomicLong()::incrementAndGet;
            tracker = new Tracker(enabled, wall::get, cpu::get, lines::add,
                    minimumMs, maxLogsPerMinute, ids);
        }

        private void runScope(long elapsed) {
            Scope scope = tracker.begin(ScopeKind.TERRAIN_SWEEP, 0, 0, 16.0, 18.0);
            wall.addAndGet(elapsed);
            scope.finish(null);
        }
    }
}
