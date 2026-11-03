package dev.superchunk.gpu.dfc;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.lang.reflect.Field;
import java.util.stream.Collectors;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.CacheLikeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.ConstantNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.BytecodeGen;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.CompiledEntry;
import dev.superchunk.gpu.OpenCLBackend;

/**
 * Standalone dispatch-gate regression. The callback seam is called immediately around the real
 * backend submissions in delegate, cache and prefetch code; this fixture substitutes a counted
 * backend and never initializes Minecraft or OpenCL.
 */
public final class GpuWorldgenPolicyRegressionTest {

    private static int checks;
    private static int failures;

    private GpuWorldgenPolicyRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        classifierMatrix();
        dispatchSiteMatrix();
        rejectUnprovedContexts();
        Class<?> compiledClass = compileCacheReuse();
        cacheLikeCompiledClassReuse();
        scopeAndCompiledClassReuse(compiledClass);
        compactIdsTaskIdentity();
        nestedScopesMaskOuterPermit();
        scopeLeaseLinearizesRetirement();
        backendErrorFallsBack();
        asyncPermitCapture();
        expiredAsyncPermitFallsBack();
        asyncScopeCarrierRestoresWorkerContext();
        unresolvedDensityDelegateFallsBack();

        if (failures != 0) {
            System.err.println("GpuWorldgenPolicy: " + failures + " of " + checks + " checks FAILED");
            System.exit(1);
        }
        System.out.println("GpuWorldgenPolicy: " + checks
                + " classifier, exact-task, deposit-scope, target-resource, nested-scope, fallback, async permit and "
                + "async scope-carrier checks passed");
    }

    /** Compile an equal AST twice without Minecraft bootstrap and check the actual AST cache. */
    private static Class<?> compileCacheReuse() {
        CompiledEntry first = BytecodeGen.compile0(new ConstantNode(17.0));
        CompiledEntry second = BytecodeGen.compile0(new ConstantNode(17.0));
        expect(first.getClass(), second.getClass(), "BytecodeGen cache reuses class for equal AST");
        String firstFields = fieldLayout(first.getClass());
        String secondFields = fieldLayout(second.getClass());
        expect(true, firstFields.equals(secondFields), "reused compiled class has stable fields");
        expect(true, Double.compare(17.0, first.evalSingle(0, 0, 0, EvalType.NORMAL)) == 0,
                "compiled AST evaluates before task authorization");
        expect(true, Double.compare(17.0, second.evalSingle(0, 0, 0, EvalType.NORMAL)) == 0,
                "reused AST evaluates before task authorization");
        return first.getClass();
    }

    /**
     * Compile a CacheLikeNode twice with the run-constant GPU latch enabled but no OpenCL
     * backend. The test subclass bypasses CacheLikeNode's null-cache equality helpers only;
     * its inherited bytecode generation still emits the cache and GPU-delegate field slots.
     */
    private static void cacheLikeCompiledClassReuse() throws ReflectiveOperationException {
        expect(false, OpenCLBackend.isAvailable(),
                "CacheLikeNode fixture requires an unavailable backend and never initializes OpenCL");

        Field enabledField = GpuDfcHook.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        Object previousEnabled = enabledField.get(null);
        try {
            // In this branch GpuDensityFunction.compile exits on isAvailable()==false, and
            // tryBuildDelegateOrEmpty supplies the deterministic empty field-slot object.
            enabledField.set(null, Boolean.TRUE);

            CompiledEntry first = BytecodeGen.compile0(new NullCacheLikeNode(new ConstantNode(23.0)));
            CompiledEntry second = BytecodeGen.compile0(new NullCacheLikeNode(new ConstantNode(23.0)));

            expect(first.getClass(), second.getClass(),
                    "CacheLikeNode AST cache hit reuses the same generated class");
            long delegateFields = Arrays.stream(first.getClass().getDeclaredFields())
                    .filter(field -> field.getType() == GpuDfcDelegate.class).count();
            expect(1L, delegateFields,
                    "GPU-enabled CacheLikeNode class contains one GpuDfcDelegate field slot");
            expect(true, Arrays.stream(first.getClass().getDeclaredFields())
                            .anyMatch(field -> field.getType().getName().equals(
                                    "dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike")),
                    "CacheLikeNode codegen preserves its cache field even when its value is null");
            expect(true, argsHaveSameTypes(first.getArgs(), second.getArgs()),
                    "CacheLikeNode cache hit preserves constructor argument type order");
            expect(true, first.getArgs().stream().anyMatch(GpuDfcDelegate.class::isInstance)
                            && second.getArgs().stream().anyMatch(GpuDfcDelegate.class::isInstance),
                    "both CacheLikeNode codegen passes supply the delegate field argument");
            expect(true, first.getArgs().stream().filter(GpuDfcDelegate.class::isInstance)
                            .map(GpuDfcDelegate.class::cast).allMatch(delegate -> delegate.gpu() == null)
                            && second.getArgs().stream().filter(GpuDfcDelegate.class::isInstance)
                            .map(GpuDfcDelegate.class::cast).allMatch(delegate -> delegate.gpu() == null),
                    "unavailable backend yields empty delegates without GPU execution");
        } finally {
            enabledField.set(null, previousEnabled);
        }
    }

    private static boolean argsHaveSameTypes(java.util.List<Object> first, java.util.List<Object> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            Object a = first.get(i);
            Object b = second.get(i);
            if (a == null ? b != null : b == null || a.getClass() != b.getClass()) return false;
        }
        return true;
    }

    /** Avoids the base null-cache identity methods; codegen itself remains inherited unchanged. */
    private static final class NullCacheLikeNode extends CacheLikeNode {
        private NullCacheLikeNode(AstNode delegate) {
            super(null, delegate);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NullCacheLikeNode node
                    && getDelegate().equals(node.getDelegate());
        }

        @Override
        public int hashCode() {
            return 31 * NullCacheLikeNode.class.hashCode() + getDelegate().hashCode();
        }

        @Override
        public boolean relaxedEquals(AstNode other) {
            return other instanceof NullCacheLikeNode node
                    && getDelegate().relaxedEquals(node.getDelegate());
        }

        @Override
        public int relaxedHashCode() {
            return 31 * NullCacheLikeNode.class.hashCode() + getDelegate().relaxedHashCode();
        }
    }

    private static String fieldLayout(Class<?> generatedClass) {
        return Arrays.stream(generatedClass.getDeclaredFields())
                .map(field -> field.getName() + ":" + field.getType().getName())
                .sorted().collect(Collectors.joining(","));
    }

    private static void classifierMatrix() {
        classify("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", "minecraft:noise", true,
                null, GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE);
        classify("example.CustomNoiseGenerator", "example:noise", true,
                null, GpuWorldgenPolicy.GeneratorKind.CUSTOM_GENERATOR);
        classify("example.AuditedNoiseGenerator", "example:audited_noise", true,
                "example:audited_noise", GpuWorldgenPolicy.GeneratorKind.COMPATIBLE_NOISE);
        classify("example.AuditedNoiseGenerator", "example:other_codec", true,
                "example:audited_noise", GpuWorldgenPolicy.GeneratorKind.CUSTOM_GENERATOR);
        classify("example.CustomGenerator", "example:custom", false,
                null, GpuWorldgenPolicy.GeneratorKind.CUSTOM_GENERATOR);
        classify("example.Unregistered", null, false,
                null, GpuWorldgenPolicy.GeneratorKind.UNKNOWN);
        classify("com.yucareux.tellus.worldgen.EarthChunkGenerator", "tellus:earth", false,
                null, GpuWorldgenPolicy.GeneratorKind.TELLUS);
        classify("com.yucareux.tellus.worldgen.EarthChunkGenerator", null, false,
                null, GpuWorldgenPolicy.GeneratorKind.TELLUS);
        // Tellus absent: recognition is name/codec based and does not load its class.
        classify("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", "minecraft:noise", true,
                null, GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE);
    }

    /** Exercise the same callback gate wrapped around each production GPU submit category. */
    private static void dispatchSiteMatrix() {
        FakePermit vanilla = permit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE, true, true,
                true, new Object());
        route("GpuDfcDelegate.fill", vanilla, GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY, null, true);
        route("ChunkGridCache dispatch/serve", vanilla, GpuWorldgenPolicy.DispatchPath.CHUNK_GRID,
                vanilla.worldScopeIdentity(), true);
        route("BiomeClimateCache dispatch", vanilla, GpuWorldgenPolicy.DispatchPath.CLIMATE,
                vanilla.worldScopeIdentity(), true);
        route("GpuBatchPrefetch submit", vanilla, GpuWorldgenPolicy.DispatchPath.DENSITY_PREFETCH,
                vanilla.worldScopeIdentity(), true);
        route("GpuClimatePrefetch submit", vanilla, GpuWorldgenPolicy.DispatchPath.CLIMATE_PREFETCH,
                vanilla.worldScopeIdentity(), true);
        route("Distant Horizons group submit", vanilla, GpuWorldgenPolicy.DispatchPath.DISTANT_HORIZONS,
                vanilla.worldScopeIdentity(), false);
        expect(false, GpuWorldgenPolicy.distantHorizonsBatchingEnabled(),
                "DH group remains fail-closed until actual task identity is exposed");
    }

    private static void rejectUnprovedContexts() {
        for (GpuWorldgenPolicy.DispatchPath path : GpuWorldgenPolicy.DispatchPath.values()) {
            route("missing task: " + path, null, path, new Object(), false);
            route("custom generator: " + path,
                    permit(GpuWorldgenPolicy.GeneratorKind.CUSTOM_GENERATOR, true, true, true, new Object()),
                    path, null, false);
            route("Tellus generator: " + path,
                    permit(GpuWorldgenPolicy.GeneratorKind.TELLUS, true, true, true, new Object()),
                    path, null, false);
            route("unknown generator: " + path,
                    permit(GpuWorldgenPolicy.GeneratorKind.UNKNOWN, true, true, true, new Object()),
                    path, null, false);
            route("stale task: " + path,
                    permit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE, false, true, true, new Object()),
                    path, null, false);
            route("GPU disabled: " + path,
                    permit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE, true, false, true, new Object()),
                    path, null, false);
            route("device absent: " + path,
                    permit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE, true, true, false, new Object()),
                    path, null, false);
        }
    }

    private static void scopeAndCompiledClassReuse(Class<?> compiledClass) {
        Object scopeA = new Object();
        Object scopeB = new Object();
        Object sharedGenerator = new Object();
        Object sharedRandomState = new Object();
        String compiledLayout = fieldLayout(compiledClass);
        AtomicInteger gpuSubmits = new AtomicInteger();
        AtomicInteger cpuFallbacks = new AtomicInteger();
        Runnable sameCompiledDispatch = () -> {
            expect(true, compiledLayout.equals(fieldLayout(compiledClass)),
                    "same cached AST class/layout is used for the per-task dispatch");
            gpuSubmits.incrementAndGet();
        };

        // One compiled class/program is reused; the authorization is supplied per task.
        FakePermit worldA = new FakePermit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, scopeA, sharedGenerator, sharedRandomState);
        FakePermit worldB = new FakePermit(GpuWorldgenPolicy.GeneratorKind.TELLUS,
                true, true, true, scopeB, sharedGenerator, sharedRandomState);
        FakePermit sameScope = new FakePermit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, scopeA, sharedGenerator, sharedRandomState);
        FakePermit otherWorldSameInputs = new FakePermit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, scopeB, sharedGenerator, sharedRandomState);
        FakePermit otherGeneratorSameWorld = new FakePermit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, scopeA, new Object(), sharedRandomState);
        FakePermit otherRandomStateSameWorld = new FakePermit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, scopeA, sharedGenerator, new Object());
        expect(true, GpuWorldgenPolicy.sameExecutionScope(worldA, sameScope),
                "same world token, generator and RandomState share an execution scope");
        expect(false, GpuWorldgenPolicy.sameExecutionScope(worldA, otherWorldSameInputs),
                "different world token rejects otherwise identical generator and RandomState");
        expect(false, GpuWorldgenPolicy.sameExecutionScope(worldA, otherGeneratorSameWorld),
                "different generator rejects a shared world token");
        expect(false, GpuWorldgenPolicy.sameExecutionScope(worldA, otherRandomStateSameWorld),
                "different RandomState rejects a shared world token");
        boolean allowedA = GpuWorldgenPolicy.dispatchIfAuthorizedInScope(worldA,
                GpuWorldgenPolicy.DispatchPath.CHUNK_GRID, scopeA, sameCompiledDispatch);
        if (!allowedA) cpuFallbacks.incrementAndGet();
        boolean leakedToB = GpuWorldgenPolicy.dispatchIfAuthorizedInScope(worldB,
                GpuWorldgenPolicy.DispatchPath.CHUNK_GRID, scopeA, sameCompiledDispatch);
        if (!leakedToB) cpuFallbacks.incrementAndGet();
        expect(true, allowedA, "vanilla task with matching world token dispatches");
        expect(false, leakedToB, "shared generator and compiled class cannot transfer permit to Tellus world");
        expect(1, gpuSubmits.get(), "only authorized task reaches the GPU callback");
        expect(1, cpuFallbacks.get(), "denied world follows CPU fallback");
        expect(false, GpuWorldgenPolicy.isAuthorizedInScope(worldA,
                GpuWorldgenPolicy.DispatchPath.CHUNK_GRID, scopeB), "mismatched world token denied");
        expect(false, GpuWorldgenPolicy.sameScopeToken(scopeA, scopeB), "world tokens are identity-scoped");
        AtomicInteger wrongTargetDispatches = new AtomicInteger();
        boolean wrongTarget = GpuWorldgenPolicy.dispatchIfAuthorizedInScope(sameScope,
                GpuWorldgenPolicy.DispatchPath.DENSITY_PREFETCH, scopeB,
                wrongTargetDispatches::incrementAndGet);
        expect(false, wrongTarget,
                "valid permit is denied when the independently supplied batcher target scope differs");
        expect(0, wrongTargetDispatches.get(),
                "a rejected target mismatch never reaches the batcher submit callback");

        Object compiledRouter = new Object();
        FakePermit routerOwner = new FakePermit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, scopeA, sharedGenerator, sharedRandomState,
                new Object(), compiledRouter);
        expect(true, GpuWorldgenPolicy.isAuthorizedForRouter(routerOwner,
                        GpuWorldgenPolicy.DispatchPath.CLIMATE_PREFETCH, compiledRouter),
                "climate target router is the independently captured compiled router");
        expect(false, GpuWorldgenPolicy.isAuthorizedForRouter(routerOwner,
                        GpuWorldgenPolicy.DispatchPath.CLIMATE_PREFETCH, new Object()),
                "valid world token cannot authorize a different compiled router");
        AtomicInteger wrongRouterDispatches = new AtomicInteger();
        expect(false, GpuWorldgenPolicy.dispatchIfAuthorizedForRouter(routerOwner,
                        GpuWorldgenPolicy.DispatchPath.CLIMATE_PREFETCH, new Object(),
                        wrongRouterDispatches::incrementAndGet),
                "dispatcher gate rejects a token whose RandomState router differs from its target");
        expect(0, wrongRouterDispatches.get(),
                "wrong-router denial happens before climate dispatcher submission");
        Object climateDispatcher = new Object();
        Object wrongClimateDispatcher = new Object();
        Object climateFused = new Object();
        Object climateRoots = new Object();
        expect(true, BiomeClimateCache.sameBatchTarget(compiledRouter, climateDispatcher,
                        climateFused, climateRoots, compiledRouter, climateDispatcher,
                        climateFused, climateRoots),
                "current climate plan resolves to its registered dispatcher/fused resource");
        expect(false, BiomeClimateCache.sameBatchTarget(compiledRouter, climateDispatcher,
                        climateFused, climateRoots, compiledRouter, wrongClimateDispatcher,
                        climateFused, climateRoots),
                "deposit from another climate dispatcher group is rejected");
    }

    private static void compactIdsTaskIdentity() {
        Object worldA = new Object();
        Object worldB = new Object();
        Object generator = new Object();
        Object randomA = new Object();
        Object randomB = new Object();
        Object chunkA = new ChunkFixture(worldA, 0, 0);
        Object chunkB = new ChunkFixture(worldA, 1, 0);
        Object sameCoordinatesDifferentWorld = new ChunkFixture(worldB, 0, 0);
        FakePermit permitA = new FakePermit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, worldA, generator, randomA, chunkA, new Object());
        expect(true, GpuWorldgenPolicy.isAuthorizedForTask(permitA,
                        GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY, generator, randomA, chunkA),
                "CompactIds task accepts exact chunk/generator/RandomState tuple");
        expect(false, GpuWorldgenPolicy.isAuthorizedForTask(permitA,
                        GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY, generator, randomA, chunkB),
                "same-world chunk at another position cannot consume this deposit");
        expect(false, GpuWorldgenPolicy.isAuthorizedForTask(permitA,
                        GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY, generator, randomB, chunkA),
                "changed RandomState invalidates the task permit");
        expect(false, GpuWorldgenPolicy.isAuthorizedForTask(permitA,
                        GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY, new Object(), randomA, chunkA),
                "different generator cannot consume this deposit");
        FakePermit sameCoordinatesInWorldB = new FakePermit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, worldB, generator, randomA, sameCoordinatesDifferentWorld, new Object());
        expect(false, GpuWorldgenPolicy.sameExecutionScope(permitA, sameCoordinatesInWorldB),
                "equal coordinates in another world are not the same execution scope");
        expect(true, GpuWorldgenPolicy.sameDepositScope(permitA, worldA),
                "deposit stored under the permit's world scope is accepted");
        expect(false, GpuWorldgenPolicy.sameDepositScope(permitA, worldB),
                "deposit from another world scope is rejected");
        GpuWorldgenPolicy.WorldScope storedScopeA = new GpuWorldgenPolicy.WorldScope(1, null);
        GpuWorldgenPolicy.WorldScope storedScopeB = new GpuWorldgenPolicy.WorldScope(2, null);
        expect(false, GpuWorldgenPolicy.isWorldScopeLive(storedScopeA),
                "unregistered scope identity fails closed");
        GpuBatchStore.Key depositKey = new GpuBatchStore.Key(storedScopeA,
                new net.minecraft.world.level.ChunkPos(0, 0), 4, 8, 4, -64, 5, 49, 5);
        GpuBatchStore.Stored depositA = new GpuBatchStore.Stored(storedScopeA,
                null, null, new double[0], null, null, null);
        GpuBatchStore.Stored depositB = new GpuBatchStore.Stored(storedScopeB,
                null, null, new double[0], null, null, null);
        expect(true, GpuBatchStore.payloadScopeMatches(depositKey, depositA),
                "GpuBatchStore accepts payload stamped with the exact key scope");
        expect(false, GpuBatchStore.payloadScopeMatches(depositKey, depositB),
                "GpuBatchStore rejects payload stamped by another world scope");
        Object routeA = new Object();
        Object routeB = new Object();
        expect(true, GpuWorldgenPolicy.sameDepositRoute(routeA, routeA),
                "CompactIds deposit route matches its captured group identity");
        expect(false, GpuWorldgenPolicy.sameDepositRoute(routeA, routeB),
                "CompactIds deposit from another group is rejected");
    }

    private record ChunkFixture(Object worldIdentity, int x, int z) {
    }

    /** Exercise the actual nested frame masking rule without constructing Minecraft worlds. */
    private static void nestedScopesMaskOuterPermit() throws ReflectiveOperationException {
        Field currentField = GpuWorldgenPolicy.class.getDeclaredField("CURRENT");
        currentField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Object> current = (ThreadLocal<Object>) currentField.get(null);
        Object previous = current.get();
        try {
            Class<?> frameType = Class.forName(GpuWorldgenPolicy.class.getName() + "$ScopeFrame");
            var constructor = frameType.getDeclaredConstructor(frameType,
                    GpuWorldgenPolicy.DispatchPermit.class, GpuWorldgenPolicy.AuthorizationDiagnostic.class);
            constructor.setAccessible(true);
            Field permitField = frameType.getDeclaredField("permit");
            permitField.setAccessible(true);
            FakePermit outer = permit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                    true, true, true, new Object());
            Object outerFrame = constructor.newInstance(null, outer,
                    GpuWorldgenPolicy.AuthorizationDiagnostic.AUTHORIZED);
            current.set(outerFrame);
            Object deniedInnerFrame = constructor.newInstance(outerFrame, null,
                    GpuWorldgenPolicy.AuthorizationDiagnostic.GENERATOR_UNAUTHORIZED);
            current.set(deniedInnerFrame);
            expect(null, permitField.get(current.get()),
                    "nested unproved task masks the outer GPU permit");
            current.set(outerFrame);
            expect(outer, permitField.get(current.get()),
                    "closing the nested scope restores the exact outer permit");
        } finally {
            if (previous == null) current.remove();
            else current.set(previous);
        }
    }

    /** A lease linearizes admission and lets retirement proceed without waiting for enqueue. */
    private static void scopeLeaseLinearizesRetirement() throws InterruptedException {
        Object lock = new Object();
        AtomicBoolean live = new AtomicBoolean(true);
        AtomicInteger leases = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        CountDownLatch enqueueEntered = new CountDownLatch(1);
        CountDownLatch allowEnqueueReturn = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        boolean[] admitted = {false};
        int[] leaseCountAtEnqueue = {0};

        Thread drainer = new Thread(() -> admitted[0] = GpuWorldgenPolicy.runWithScopeLease(
                lock, live::get, leases::incrementAndGet, leases::decrementAndGet, () -> {
                    leaseCountAtEnqueue[0] = leases.get();
                    enqueueEntered.countDown();
                    try {
                        allowEnqueueReturn.await();
                        dispatches.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }), "world-scope-lease-regression-drainer");
        drainer.start();
        expect(true, enqueueEntered.await(1, TimeUnit.SECONDS),
                "scope lease admits one enqueue while the scope is live");

        Thread retire = new Thread(() -> {
            synchronized (lock) {
                live.set(false);
            }
            retired.countDown();
        }, "world-scope-lease-regression-retire");
        retire.start();
        expect(true, retired.await(1, TimeUnit.SECONDS),
                "retirement revokes new leases without waiting for an admitted enqueue");
        allowEnqueueReturn.countDown();
        drainer.join();
        retire.join();

        expect(true, admitted[0], "lease admitted before retirement remains accepted work");
        expect(1, leaseCountAtEnqueue[0], "enqueue runs while its short-lived lease is held");
        expect(0, leases.get(), "lease is released after enqueue returns");
        expect(1, dispatches.get(), "pre-retirement admitted enqueue completes once");
        boolean staleAdmitted = GpuWorldgenPolicy.runWithScopeLease(lock, live::get,
                leases::incrementAndGet, leases::decrementAndGet, dispatches::incrementAndGet);
        expect(false, staleAdmitted, "retired scope cannot acquire a new enqueue lease");
        expect(1, dispatches.get(), "retired-scope denial never reaches the dispatcher callback");
    }

    private static void asyncPermitCapture() throws InterruptedException {
        Object worldToken = new Object();
        FakePermit capturedPermit = permit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, worldToken);
        AtomicInteger gpuSubmits = new AtomicInteger();
        AtomicInteger cpuFallbacks = new AtomicInteger();
        boolean[] authorizedOnWorker = {false};
        Thread worker = new Thread(() -> {
            authorizedOnWorker[0] = GpuWorldgenPolicy.dispatchIfAuthorizedInScope(capturedPermit,
                    GpuWorldgenPolicy.DispatchPath.DENSITY_PREFETCH, worldToken,
                    gpuSubmits::incrementAndGet);
            if (!authorizedOnWorker[0]) cpuFallbacks.incrementAndGet();
        }, "worldgen-policy-regression-worker");
        worker.start();
        worker.join();
        expect(true, authorizedOnWorker[0], "captured explicit permit authorizes on another worker");
        expect(1, gpuSubmits.get(), "async callback submits once on receiving worker");
        expect(0, cpuFallbacks.get(), "valid captured permit avoids CPU fallback");
    }

    private static void expiredAsyncPermitFallsBack() throws InterruptedException {
        AtomicBoolean live = new AtomicBoolean(true);
        Object scope = new Object();
        Object generator = new Object();
        Object randomState = new Object();
        Object chunk = new Object();
        FakePermit captured = new FakePermit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                live, true, true, scope, generator, randomState, chunk, new Object());
        AtomicInteger gpuSubmits = new AtomicInteger();
        AtomicInteger cpuFallbacks = new AtomicInteger();
        live.set(false); // models unload/cancellation before the async continuation runs
        Thread worker = new Thread(() -> {
            boolean submitted = GpuWorldgenPolicy.isAuthorizedForTask(captured,
                    GpuWorldgenPolicy.DispatchPath.DENSITY_PREFETCH, generator, randomState, chunk);
            if (submitted) gpuSubmits.incrementAndGet();
            else cpuFallbacks.incrementAndGet();
        }, "expired-worldgen-policy-regression-worker");
        worker.start();
        worker.join();
        expect(0, gpuSubmits.get(), "expired async scope cannot submit after worker switch");
        expect(1, cpuFallbacks.get(), "expired async task takes safe fallback");
    }

    /** Exercises the exact supplier wrapper used by MixinCreateBiomesGpuAuthorization. */
    private static void asyncScopeCarrierRestoresWorkerContext() throws Exception {
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "authorization-carrier-fixture");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Object workerPrior = new Object();
            Object capturedWorldToken = new Object();
            worker.submit(() -> {
                // Establish a pre-existing worker frame to prove restoration rather than mere cleanup.
                AsyncScopeFixture.FRAME.set(workerPrior);
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);

            java.util.concurrent.atomic.AtomicReference<Object> seen = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger scopeEntered = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger scopeRestored = new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Supplier<String> wrapped = GpuTaskScopeCarrier.wrap(
                    () -> {
                        seen.set(AsyncScopeFixture.FRAME.get());
                        return "ok";
                    },
                    () -> {
                        Object previous = AsyncScopeFixture.FRAME.get();
                        AsyncScopeFixture.FRAME.set(capturedWorldToken);
                        return () -> {
                            if (previous == null) AsyncScopeFixture.FRAME.remove();
                            else AsyncScopeFixture.FRAME.set(previous);
                        };
                    },
                    ignored -> scopeEntered.incrementAndGet(),
                    () -> { },
                    scopeRestored::incrementAndGet);

            expect("ok", worker.submit(wrapped::get).get(5, java.util.concurrent.TimeUnit.SECONDS),
                    "wrapped async supplier returns its task result on the worker");
            expect(capturedWorldToken, seen.get(),
                    "supplier carries the captured task identity across the executor switch");
            expect(1, scopeEntered.get(), "task scope opens once on the receiving worker");
            expect(1, scopeRestored.get(), "task scope restores once after normal completion");
            expect(workerPrior, worker.submit(AsyncScopeFixture.FRAME::get)
                            .get(5, java.util.concurrent.TimeUnit.SECONDS),
                    "pre-existing worker context is restored after normal completion");

            java.util.concurrent.atomic.AtomicInteger exceptionalRestores = new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Supplier<Object> throwing = GpuTaskScopeCarrier.wrap(
                    () -> { throw new IllegalStateException("fixture"); },
                    () -> {
                        Object previous = AsyncScopeFixture.FRAME.get();
                        AsyncScopeFixture.FRAME.set(capturedWorldToken);
                        return () -> {
                            if (previous == null) AsyncScopeFixture.FRAME.remove();
                            else AsyncScopeFixture.FRAME.set(previous);
                        };
                    },
                    ignored -> { }, () -> { }, exceptionalRestores::incrementAndGet);
            try {
                worker.submit(throwing::get).get(5, java.util.concurrent.TimeUnit.SECONDS);
                expect(false, "throwing supplier propagates", "fixture supplier propagates its failure");
            } catch (java.util.concurrent.ExecutionException expected) {
                expect(IllegalStateException.class, expected.getCause().getClass(),
                        "fixture supplier exception remains visible");
            }
            expect(1, exceptionalRestores.get(), "task scope restores after exceptional completion");
            expect(workerPrior, worker.submit(AsyncScopeFixture.FRAME::get)
                            .get(5, java.util.concurrent.TimeUnit.SECONDS),
                    "pre-existing worker context is restored after exceptional completion");
        } finally {
            worker.shutdownNow();
        }
    }

    private static final class AsyncScopeFixture {
        private static final ThreadLocal<Object> FRAME = new ThreadLocal<>();
    }

    private static void unresolvedDensityDelegateFallsBack() {
        long unresolvedBefore = GpuAuthorizationDiagnostics.count(
                GpuAuthorizationDiagnostics.Event.DENSITY_DELEGATE_UNRESOLVED);
        GpuDfcDelegate delegate = new GpuDfcDelegate(null);
        boolean completed = delegate.fill(new double[4], new int[4], new int[4], new int[4], null);
        expect(false, completed, "unresolved GPU delegate returns to the caller's CPU path");
        expect(unresolvedBefore + 1L, GpuAuthorizationDiagnostics.count(
                        GpuAuthorizationDiagnostics.Event.DENSITY_DELEGATE_UNRESOLVED),
                "unresolved GPU delegate is counted at the actual fill decision");
    }

    private static void backendErrorFallsBack() {
        FakePermit permit = permit(GpuWorldgenPolicy.GeneratorKind.VANILLA_NOISE,
                true, true, true, new Object());
        AtomicInteger gpuSubmits = new AtomicInteger();
        AtomicInteger cpuFallbacks = new AtomicInteger();
        AtomicInteger errorReports = new AtomicInteger();
        Throwable[] reported = {null};
        boolean result = GpuWorldgenPolicy.dispatchWithFallback(permit,
                GpuWorldgenPolicy.DispatchPath.DIRECT_DENSITY,
                () -> {
                    gpuSubmits.incrementAndGet();
                    throw new IllegalStateException("synthetic backend failure");
                },
                cpuFallbacks::incrementAndGet,
                failure -> {
                    reported[0] = failure;
                    errorReports.incrementAndGet();
                });
        expect(false, result, "backend exception reports failed GPU route");
        expect(1, gpuSubmits.get(), "throwing backend callback was invoked once");
        expect(1, cpuFallbacks.get(), "backend exception triggers exactly one CPU fallback");
        expect(1, errorReports.get(), "backend exception is reported once");
        expect(true, reported[0] instanceof IllegalStateException,
                "backend failure reaches error reporter without propagation");
    }

    private static void route(String label, GpuWorldgenPolicy.DispatchPermit permit,
                              GpuWorldgenPolicy.DispatchPath path, Object expectedScope,
                              boolean expectedGpu) {
        AtomicInteger gpuSubmits = new AtomicInteger();
        AtomicInteger cpuFallbacks = new AtomicInteger();
        Runnable fakeBackendSubmit = gpuSubmits::incrementAndGet;
        boolean submitted = expectedScope == null
                ? GpuWorldgenPolicy.dispatchIfAuthorized(permit, path, fakeBackendSubmit)
                : GpuWorldgenPolicy.dispatchIfAuthorizedInScope(permit, path, expectedScope, fakeBackendSubmit);
        if (!submitted) cpuFallbacks.incrementAndGet();
        expect(expectedGpu, submitted, label + " route decision");
        expect(expectedGpu ? 1 : 0, gpuSubmits.get(), label + " backend callback count");
        expect(expectedGpu ? 0 : 1, cpuFallbacks.get(), label + " CPU fallback count");
    }

    private static FakePermit permit(GpuWorldgenPolicy.GeneratorKind kind, boolean live,
                                     boolean gpu, boolean device, Object scope) {
        return new FakePermit(kind, live, gpu, device, scope,
                new Object(), new Object());
    }

    private static void classify(String className, String codec, boolean noiseBased,
                                 String registration, GpuWorldgenPolicy.GeneratorKind expected) {
        expect(expected, GpuWorldgenPolicy.classify(className, codec, noiseBased, registration),
                "classify " + className + " with " + codec);
    }

    private static void expect(boolean expected, boolean actual, String label) {
        checks++;
        if (expected != actual) {
            failures++;
            System.err.println("FAIL: " + label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expect(int expected, int actual, String label) {
        checks++;
        if (expected != actual) {
            failures++;
            System.err.println("FAIL: " + label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expect(Object expected, Object actual, String label) {
        checks++;
        if (expected != actual) {
            failures++;
            System.err.println("FAIL: " + label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class FakePermit implements GpuWorldgenPolicy.DispatchPermit {
        private final GpuWorldgenPolicy.GeneratorKind kind;
        private final AtomicBoolean contextLive;
        private final boolean gpuEnabled;
        private final boolean deviceAvailable;
        private final Object worldScope;
        private final Object generatorIdentity;
        private final Object randomStateIdentity;
        private final Object chunkIdentity;
        private final Object routerIdentity;
        private FakePermit(GpuWorldgenPolicy.GeneratorKind kind, boolean contextLive,
                           boolean gpuEnabled, boolean deviceAvailable, Object worldScope,
                           Object generatorIdentity, Object randomStateIdentity) {
            this(kind, new AtomicBoolean(contextLive), gpuEnabled, deviceAvailable, worldScope,
                    generatorIdentity, randomStateIdentity, new Object(), new Object());
        }

        private FakePermit(GpuWorldgenPolicy.GeneratorKind kind, boolean contextLive,
                           boolean gpuEnabled, boolean deviceAvailable, Object worldScope,
                           Object generatorIdentity, Object randomStateIdentity, Object chunkIdentity,
                           Object routerIdentity) {
            this(kind, new AtomicBoolean(contextLive), gpuEnabled, deviceAvailable, worldScope,
                    generatorIdentity, randomStateIdentity, chunkIdentity, routerIdentity);
        }

        private FakePermit(GpuWorldgenPolicy.GeneratorKind kind, AtomicBoolean contextLive,
                           boolean gpuEnabled, boolean deviceAvailable, Object worldScope,
                           Object generatorIdentity, Object randomStateIdentity, Object chunkIdentity,
                           Object routerIdentity) {
            this.kind = kind;
            this.contextLive = contextLive;
            this.gpuEnabled = gpuEnabled;
            this.deviceAvailable = deviceAvailable;
            this.worldScope = worldScope;
            this.generatorIdentity = generatorIdentity;
            this.randomStateIdentity = randomStateIdentity;
            this.chunkIdentity = chunkIdentity;
            this.routerIdentity = routerIdentity;
        }

        @Override public GpuWorldgenPolicy.GeneratorKind kind() { return kind; }
        @Override public boolean contextLive() { return contextLive.get(); }
        @Override public boolean gpuEnabled() { return gpuEnabled; }
        @Override public boolean deviceAvailable() { return deviceAvailable; }
        @Override public Object worldScopeIdentity() { return worldScope; }
        @Override public Object generatorIdentity() { return generatorIdentity; }
        @Override public Object randomStateIdentity() { return randomStateIdentity; }
        @Override public Object chunkIdentity() { return chunkIdentity; }
        @Override public Object routerIdentity() { return routerIdentity; }
    }
}
