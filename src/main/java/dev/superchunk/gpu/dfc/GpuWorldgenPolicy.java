package dev.superchunk.gpu.dfc;

import dev.superchunk.mixin.IChunkAccessLevelHeight;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.NoiseRouter;
import dev.superchunk.gpu.OpenCLBackend;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runtime authorization for world-generation GPU work.
 *
 * <p>Compilation remains run-constant in {@link GpuDfcHook}; this class only gates dispatch. A
 * permit is tied to one live ServerLevel, its exact generator, RandomState and chunk. It retains
 * those objects weakly, so a future or cache cannot keep a closed world alive. If any identity
 * cannot be proved, callers take their existing CPU path.
 *
 * <p>The short-lived ThreadLocal frame is installed inside the actual synchronous noise-fill body
 * or inside the supplier that performs async biome generation. The latter captures the real
 * generator, RandomState and chunk before scheduling, so the frame is established on the worker
 * that executes the task. Prefetch continuations capture a separate explicit permit; none depends
 * on inheriting a ThreadLocal across a worker change.
 */
public final class GpuWorldgenPolicy {

    public enum GeneratorKind {
        VANILLA_NOISE,
        COMPATIBLE_NOISE,
        CUSTOM_GENERATOR,
        TELLUS,
        UNKNOWN
    }

    /** Diagnostic outcome only; it never grants a dispatch or changes the CPU fallback. */
    public enum AuthorizationDiagnostic {
        AUTHORIZED,
        NO_FRAME,
        DENIED_FRAME,
        CHUNK_NULL,
        LEVEL_ACCESSOR_NOT_SERVER_LEVEL,
        LEVEL_ACCESSOR_EXCEPTION,
        GENERATOR_MISMATCH,
        RANDOM_STATE_MISMATCH,
        SCOPE_RETIRED,
        PERMIT_STALE_OR_RETIRED,
        WORLD_DIMENSION_MISMATCH,
        CHUNK_MISMATCH,
        SAMPLER_MISMATCH,
        GROUP_MISMATCH,
        GENERATOR_UNAUTHORIZED,
        GPU_DISABLED,
        DEVICE_UNAVAILABLE,
        OTHER_ELIGIBILITY
    }

    public enum DispatchPath {
        DIRECT_DENSITY,
        CHUNK_GRID,
        CLIMATE,
        DENSITY_PREFETCH,
        CLIMATE_PREFETCH,
        DISTANT_HORIZONS
    }

    enum LevelAccessorOutcome {
        NOT_CHECKED,
        SERVER_LEVEL,
        NOT_SERVER_LEVEL,
        EXCEPTION
    }

    /** Small gate seam shared by production dispatch sites and standalone route tests. */
    interface DispatchPermit {
        GeneratorKind kind();
        boolean contextLive();
        boolean gpuEnabled();
        boolean deviceAvailable();
        Object worldScopeIdentity();
        Object generatorIdentity();
        Object randomStateIdentity();
        Object chunkIdentity();
        Object routerIdentity();
    }

    private static final AtomicLong NEXT_SCOPE = new AtomicLong();
    private static final List<ScopeIdentity> WORLD_SCOPES = new ArrayList<>();
    /** Weak tombstones prevent late async work from recreating a scope for a closed level. */
    private static final List<WeakReference<ServerLevel>> RETIRED_LEVELS = new ArrayList<>();
    private static final Map<Class<?>, String> COMPATIBLE_NOISE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<ScopeFrame> CURRENT = new ThreadLocal<>();

    private GpuWorldgenPolicy() {
    }

    /** DH's current group hook lacks the generator/RandomState identities; batch prefetch is disabled. */
    public static boolean distantHorizonsBatchingEnabled() {
        return false;
    }

    /**
     * An opt-in registration for a generator whose noise semantics have been separately audited.
     * The exact runtime class and codec registry id must both match. SuperChunk registers none by
     * default; custom generators therefore remain CPU-only.
     */
    static void registerCompatibleNoiseGenerator(Class<? extends ChunkGenerator> exactClass,
                                                String codecRegistryId) {
        if (exactClass == null || !NoiseBasedChunkGenerator.class.isAssignableFrom(exactClass)
                || codecRegistryId == null || codecRegistryId.isBlank()) {
            throw new IllegalArgumentException("A concrete generator class and codec id are required");
        }
        COMPATIBLE_NOISE.put(exactClass, codecRegistryId);
    }

    /** Returns the classification for this actual generator instance, failing closed. */
    public static GeneratorKind classify(ChunkGenerator generator) {
        if (generator == null) {
            return GeneratorKind.UNKNOWN;
        }
        String codecId = codecId(generator);
        return classify(generator.getClass().getName(), codecId,
                generator instanceof NoiseBasedChunkGenerator,
                COMPATIBLE_NOISE.get(generator.getClass()));
    }

    public static boolean isGeneratorKindAllowed(GeneratorKind kind) {
        return kind == GeneratorKind.VANILLA_NOISE || kind == GeneratorKind.COMPATIBLE_NOISE;
    }

    static GeneratorKind classify(String className, String codecId, boolean noiseBased,
                                  String registeredCompatibleCodec) {
        if (className == null) {
            return GeneratorKind.UNKNOWN;
        }
        if (isTellusClassName(className) || (codecId != null && codecId.startsWith("tellus:"))) {
            return GeneratorKind.TELLUS;
        }
        if (noiseBased && "minecraft:noise".equals(codecId)
                && className.equals("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator")) {
            return GeneratorKind.VANILLA_NOISE;
        }
        if (noiseBased && registeredCompatibleCodec != null
                && registeredCompatibleCodec.equals(codecId)) {
            return GeneratorKind.COMPATIBLE_NOISE;
        }
        if (codecId == null) {
            return GeneratorKind.UNKNOWN;
        }
        return GeneratorKind.CUSTOM_GENERATOR;
    }

    /** A compatible subclass is never inferred merely from inheritance. */
    private static String codecId(ChunkGenerator generator) {
        try {
            return generator.getTypeNameForDataFixer()
                    .map(ResourceKey::location)
                    .map(Object::toString)
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Optional recognition by class name only; Tellus is never loaded or linked here. */
    private static boolean isTellusClassName(String className) {
        return className.startsWith("com.yucareux.tellus.")
                && (className.endsWith(".EarthChunkGenerator")
                || className.contains(".worldgen.EarthChunkGenerator"));
    }

    /** Derives a per-task permit from the real chunk, level, generator and RandomState. */
    public static Permit forChunk(ChunkAccess chunk, ChunkGenerator expectedGenerator) {
        return forChunk(chunk, expectedGenerator, null);
    }

    /** Same as {@link #forChunk(ChunkAccess, ChunkGenerator)}, also requiring this exact level. */
    public static Permit forChunk(ChunkAccess chunk, ChunkGenerator expectedGenerator,
                                  ServerLevel expectedLevel) {
        try {
            if (chunk == null) {
                return null;
            }
            Object height = ((IChunkAccessLevelHeight) chunk).superchunk$getLevelHeightAccessor();
            if (!(height instanceof ServerLevel level)) {
                return null;
            }
            if (expectedLevel != null && expectedLevel != level) {
                return null;
            }
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (generator == null || (expectedGenerator != null && expectedGenerator != generator)) {
                return null;
            }
            return create(level, generator, level.getChunkSource().randomState(), chunk);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Opens a scoped generation-task permit and masks any outer permit when this task is denied. */
    public static Scope enterTask(ChunkGenerator generator, ChunkAccess chunk, RandomState randomState) {
        boolean diagnosticsEnabled = GpuAuthorizationDiagnostics.enabled();
        Permit permit = null;
        ServerLevel level = null;
        AuthorizationDiagnostic diagnostic = AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        if (chunk == null) {
            if (diagnosticsEnabled) {
                diagnostic = diagnoseRejectedGate(false, true, () -> diagnoseTaskInputs(false,
                        LevelAccessorOutcome.NOT_CHECKED, false, false, false));
            }
        } else {
            Object height = null;
            LevelAccessorOutcome accessorOutcome;
            try {
                height = ((IChunkAccessLevelHeight) chunk).superchunk$getLevelHeightAccessor();
                accessorOutcome = height instanceof ServerLevel
                        ? LevelAccessorOutcome.SERVER_LEVEL : LevelAccessorOutcome.NOT_SERVER_LEVEL;
            } catch (Throwable ignored) {
                accessorOutcome = LevelAccessorOutcome.EXCEPTION;
            }
            if (accessorOutcome != LevelAccessorOutcome.SERVER_LEVEL) {
                if (diagnosticsEnabled) {
                    LevelAccessorOutcome rejectedAccessor = accessorOutcome;
                    diagnostic = diagnoseRejectedGate(false, true, () -> diagnoseTaskInputs(true,
                            rejectedAccessor, false, false, false));
                }
            } else {
                level = (ServerLevel) height;
                boolean generatorMatches = false;
                boolean randomStateMatches = false;
                boolean inputsRead = false;
                try {
                    generatorMatches = generator != null && level.getChunkSource().getGenerator() == generator;
                    randomStateMatches = randomState != null
                            && level.getChunkSource().randomState() == randomState;
                    inputsRead = true;
                } catch (Throwable ignored) {
                    // This is already a rejected production input gate. Keep the residual reason
                    // without doing any further diagnostic work when diagnostics are disabled.
                }
                if (inputsRead) {
                    if (!generatorMatches || !randomStateMatches) {
                        if (diagnosticsEnabled) {
                            boolean rejectedGeneratorMatches = generatorMatches;
                            boolean rejectedRandomStateMatches = randomStateMatches;
                            LevelAccessorOutcome matchedAccessor = accessorOutcome;
                            diagnostic = diagnoseRejectedGate(false, true, () -> diagnoseTaskInputs(true,
                                    matchedAccessor, rejectedGeneratorMatches,
                                    rejectedRandomStateMatches, false));
                        }
                    } else {
                        try {
                            permit = create(level, generator, randomState, chunk);
                        } catch (Throwable ignored) {
                            // The task is rejected. Detailed classification below is diagnostic only.
                        }
                        if (permit == null) {
                            if (diagnosticsEnabled) {
                                ServerLevel rejectedLevel = level;
                                diagnostic = diagnoseRejectedGate(false, true, () -> diagnoseTaskCreationFailure(
                                        generator, chunk, randomState, rejectedLevel));
                            }
                        } else if (!isAuthorized(permit, DispatchPath.DIRECT_DENSITY)) {
                            Permit rejectedPermit = permit;
                            permit = null;
                            if (diagnosticsEnabled) {
                                diagnostic = diagnoseRejectedGate(false, true, () -> diagnosePermit(
                                        rejectedPermit, chunk,
                                        randomState == null ? null : randomState.sampler(),
                                        null, DispatchPath.DIRECT_DENSITY));
                            }
                        } else {
                            diagnostic = AuthorizationDiagnostic.AUTHORIZED;
                        }
                    }
                }
            }
        }
        GpuAuthorizationDiagnostics.WorldBucket bucket = !diagnosticsEnabled || level == null
                ? GpuAuthorizationDiagnostics.WorldBucket.UNKNOWN
                : GpuAuthorizationDiagnostics.bucketForDimension(level.dimension().location().toString());
        GpuAuthorizationDiagnostics.DiagnosticScope diagnosticScope =
                GpuAuthorizationDiagnostics.openWorldScope(bucket);
        if (permit == null && diagnostic == AuthorizationDiagnostic.AUTHORIZED) {
            diagnostic = AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        }
        if (diagnostic != AuthorizationDiagnostic.AUTHORIZED) {
            GpuAuthorizationDiagnostics.recordTaskDenial(diagnostic);
        }
        ScopeFrame previous = CURRENT.get();
        ScopeFrame installed = new ScopeFrame(previous, permit, diagnostic);
        CURRENT.set(installed); // even a denied child masks every outer task until it exits
        return new Scope(Thread.currentThread(), installed, permit, diagnosticScope);
    }

    /**
     * Runs an expensive diagnostic classifier only after the production gate rejected a task.
     * A later state change that makes the classifier say AUTHORIZED remains a rejection.
     */
    static AuthorizationDiagnostic diagnoseRejectedGate(boolean productionGateAccepted,
                                                          boolean diagnosticsEnabled,
                                                          Supplier<AuthorizationDiagnostic> classifier) {
        if (productionGateAccepted) return AuthorizationDiagnostic.AUTHORIZED;
        if (!diagnosticsEnabled) return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        try {
            AuthorizationDiagnostic result = classifier.get();
            return result == null || result == AuthorizationDiagnostic.AUTHORIZED
                    ? AuthorizationDiagnostic.OTHER_ELIGIBILITY : result;
        } catch (Throwable ignored) {
            return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        }
    }

    /** Detailed explanation for a failed permit creation; called only after create() rejects. */
    private static AuthorizationDiagnostic diagnoseTaskCreationFailure(
            ChunkGenerator expectedGenerator, ChunkAccess chunk, RandomState expectedRandomState,
            ServerLevel expectedLevel) {
        if (chunk == null) {
            return diagnoseTaskInputs(false, LevelAccessorOutcome.NOT_CHECKED, false, false, false);
        }
        final Object height;
        try {
            height = ((IChunkAccessLevelHeight) chunk).superchunk$getLevelHeightAccessor();
        } catch (Throwable accessorFailure) {
            return diagnoseTaskInputs(true, LevelAccessorOutcome.EXCEPTION, false, false, false);
        }
        if (!(height instanceof ServerLevel level)) {
            return diagnoseTaskInputs(true, LevelAccessorOutcome.NOT_SERVER_LEVEL, false, false, false);
        }
        if (expectedLevel != null && level != expectedLevel) {
            return AuthorizationDiagnostic.WORLD_DIMENSION_MISMATCH;
        }
        final ChunkGenerator actualGenerator;
        final RandomState actualRandomState;
        try {
            actualGenerator = level.getChunkSource().getGenerator();
            actualRandomState = level.getChunkSource().randomState();
        } catch (Throwable accessorFailure) {
            return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        }
        if (expectedGenerator == null || actualGenerator != expectedGenerator) {
            return AuthorizationDiagnostic.GENERATOR_MISMATCH;
        }
        if (expectedRandomState == null || actualRandomState != expectedRandomState) {
            return AuthorizationDiagnostic.RANDOM_STATE_MISMATCH;
        }
        synchronized (WORLD_SCOPES) {
            return isRetiredLevelLocked(level)
                    ? AuthorizationDiagnostic.SCOPE_RETIRED
                    : AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        }
    }

    /** Ordered input classification shared with JVM-only regressions; it never grants a permit. */
    static AuthorizationDiagnostic diagnoseTaskInputs(boolean chunkPresent, LevelAccessorOutcome accessor,
                                                       boolean generatorMatches, boolean randomStateMatches,
                                                       boolean scopeRetired) {
        if (!chunkPresent) return AuthorizationDiagnostic.CHUNK_NULL;
        if (accessor == LevelAccessorOutcome.EXCEPTION) return AuthorizationDiagnostic.LEVEL_ACCESSOR_EXCEPTION;
        if (accessor != LevelAccessorOutcome.SERVER_LEVEL) {
            return AuthorizationDiagnostic.LEVEL_ACCESSOR_NOT_SERVER_LEVEL;
        }
        if (!generatorMatches) return AuthorizationDiagnostic.GENERATOR_MISMATCH;
        if (!randomStateMatches) return AuthorizationDiagnostic.RANDOM_STATE_MISMATCH;
        if (scopeRetired) return AuthorizationDiagnostic.SCOPE_RETIRED;
        return AuthorizationDiagnostic.AUTHORIZED;
    }

    /** Read-only bounded attribution; failure to resolve a real server level is UNKNOWN. */
    public static GpuAuthorizationDiagnostics.WorldBucket diagnosticBucketForChunk(ChunkAccess chunk) {
        if (!GpuAuthorizationDiagnostics.enabled() || chunk == null) {
            return GpuAuthorizationDiagnostics.WorldBucket.UNKNOWN;
        }
        try {
            Object height = ((IChunkAccessLevelHeight) chunk).superchunk$getLevelHeightAccessor();
            if (height instanceof ServerLevel level) {
                return GpuAuthorizationDiagnostics.bucketForDimension(level.dimension().location().toString());
            }
        } catch (Throwable ignored) {
        }
        return GpuAuthorizationDiagnostics.WorldBucket.UNKNOWN;
    }

    /** Detailed, read-only diagnosis for callers that could not construct a permit. */
    public static AuthorizationDiagnostic diagnoseChunkInputsForDiagnostics(
            ChunkAccess chunk, ChunkGenerator expectedGenerator, ServerLevel expectedLevel) {
        if (chunk == null) {
            return diagnoseTaskInputs(false, LevelAccessorOutcome.NOT_CHECKED, false, false, false);
        }
        final Object height;
        try {
            height = ((IChunkAccessLevelHeight) chunk).superchunk$getLevelHeightAccessor();
        } catch (Throwable accessorFailure) {
            return diagnoseTaskInputs(true, LevelAccessorOutcome.EXCEPTION, false, false, false);
        }
        if (!(height instanceof ServerLevel level)) {
            return diagnoseTaskInputs(true, LevelAccessorOutcome.NOT_SERVER_LEVEL, false, false, false);
        }
        if (expectedLevel != null && expectedLevel != level) {
            return AuthorizationDiagnostic.WORLD_DIMENSION_MISMATCH;
        }
        final ChunkGenerator actualGenerator;
        final RandomState actualRandomState;
        try {
            actualGenerator = level.getChunkSource().getGenerator();
            actualRandomState = level.getChunkSource().randomState();
        } catch (Throwable accessorFailure) {
            return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        }
        boolean generatorMatches = actualGenerator != null
                && (expectedGenerator == null || expectedGenerator == actualGenerator);
        boolean randomStateMatches = actualRandomState != null;
        boolean retired;
        synchronized (WORLD_SCOPES) {
            retired = isRetiredLevelLocked(level);
        }
        AuthorizationDiagnostic input = diagnoseTaskInputs(true, LevelAccessorOutcome.SERVER_LEVEL,
                generatorMatches, randomStateMatches, retired);
        if (input != AuthorizationDiagnostic.AUTHORIZED) return input;
        if (!isGeneratorKindAllowed(classify(actualGenerator))) {
            return AuthorizationDiagnostic.GENERATOR_UNAUTHORIZED;
        }
        if (OpenCLBackend.config() == null || !OpenCLBackend.config().isEnabled()) {
            return AuthorizationDiagnostic.GPU_DISABLED;
        }
        if (!OpenCLBackend.isAvailable()) return AuthorizationDiagnostic.DEVICE_UNAVAILABLE;
        return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
    }

    /** Current permit for synchronous DFC / grid consumers. Null means CPU fallback. */
    public static Permit currentPermit() {
        ScopeFrame frame = CURRENT.get();
        Permit permit = frame != null && frame.permit instanceof Permit scoped ? scoped : null;
        return permit != null && permit.allowsGpu() ? permit : null;
    }

    /**
     * Reports the current task's gate result without changing it. Intended for chunk-bound
     * diagnostics only; hot sample paths continue to use {@link #currentPermit()}.
     */
    public static AuthorizationDiagnostic diagnoseCurrentAuthorization(
            ChunkAccess expectedChunk, Climate.Sampler expectedSampler, Object expectedRouter,
            DispatchPath path) {
        ScopeFrame frame = CURRENT.get();
        if (frame == null) return AuthorizationDiagnostic.NO_FRAME;
        if (!(frame.permit instanceof Permit permit)) {
            return frame.diagnostic == AuthorizationDiagnostic.AUTHORIZED
                    ? AuthorizationDiagnostic.DENIED_FRAME : frame.diagnostic;
        }
        return diagnosePermit(permit, expectedChunk, expectedSampler, expectedRouter, path);
    }

    /** Raw permit accessor for diagnostic classification only; production dispatches must use currentPermit(). */
    public static Permit currentPermitForDiagnostics() {
        ScopeFrame frame = CURRENT.get();
        return frame != null && frame.permit instanceof Permit permit ? permit : null;
    }

    /** Classifies an explicit permit without consulting or reusing the current thread's task frame. */
    public static AuthorizationDiagnostic diagnosePermitForDiagnostics(
            Permit permit, ChunkAccess expectedChunk, Object expectedRouter, DispatchPath path) {
        return permit == null ? AuthorizationDiagnostic.NO_FRAME
                : diagnosePermit(permit, expectedChunk, null, expectedRouter, path);
    }

    private static AuthorizationDiagnostic diagnosePermit(
            Permit permit, ChunkAccess expectedChunk, Climate.Sampler expectedSampler,
            Object expectedRouter, DispatchPath path) {
        if (path == DispatchPath.DISTANT_HORIZONS) return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        try {
            ServerLevel level = permit.level.get();
            ChunkGenerator generator = permit.generator.get();
            RandomState randomState = permit.randomState.get();
            ChunkAccess chunk = permit.chunk.get();
            WorldScope scope = permit.worldScope;
            if (level == null || generator == null || randomState == null || chunk == null || scope == null) {
                return AuthorizationDiagnostic.PERMIT_STALE_OR_RETIRED;
            }
            boolean scopeRetired;
            synchronized (WORLD_SCOPES) {
                scopeRetired = !scope.active || isRetiredLevelLocked(level);
            }
            if (scopeRetired) {
                return AuthorizationDiagnostic.SCOPE_RETIRED;
            }
            final Object permitChunkHeight;
            try {
                permitChunkHeight = ((IChunkAccessLevelHeight) chunk).superchunk$getLevelHeightAccessor();
            } catch (Throwable accessorFailure) {
                return AuthorizationDiagnostic.LEVEL_ACCESSOR_EXCEPTION;
            }
            if (!(permitChunkHeight instanceof ServerLevel permitChunkLevel)) {
                return AuthorizationDiagnostic.LEVEL_ACCESSOR_NOT_SERVER_LEVEL;
            }
            if (permitChunkLevel != level || !permitChunkLevel.dimension().equals(scope.dimension())) {
                return AuthorizationDiagnostic.WORLD_DIMENSION_MISMATCH;
            }
            if (expectedChunk != null) {
                final Object expectedHeight;
                try {
                    expectedHeight = ((IChunkAccessLevelHeight) expectedChunk)
                            .superchunk$getLevelHeightAccessor();
                } catch (Throwable accessorFailure) {
                    return AuthorizationDiagnostic.LEVEL_ACCESSOR_EXCEPTION;
                }
                if (!(expectedHeight instanceof ServerLevel expectedLevel)) {
                    return AuthorizationDiagnostic.LEVEL_ACCESSOR_NOT_SERVER_LEVEL;
                }
                if (expectedHeight != level || !expectedLevel.dimension().equals(scope.dimension())) {
                    return AuthorizationDiagnostic.WORLD_DIMENSION_MISMATCH;
                }
                if (chunk != expectedChunk) return AuthorizationDiagnostic.CHUNK_MISMATCH;
            }
            if (expectedSampler != null && randomState.sampler() != expectedSampler) {
                return AuthorizationDiagnostic.SAMPLER_MISMATCH;
            }
            if (expectedRouter != null && randomState.router() != expectedRouter) {
                return AuthorizationDiagnostic.GROUP_MISMATCH;
            }
            if (level.getChunkSource().getGenerator() != generator) {
                return AuthorizationDiagnostic.GENERATOR_MISMATCH;
            }
            if (level.getChunkSource().randomState() != randomState) {
                return AuthorizationDiagnostic.RANDOM_STATE_MISMATCH;
            }
            if (!isGeneratorKindAllowed(permit.kind())) {
                return AuthorizationDiagnostic.GENERATOR_UNAUTHORIZED;
            }
            if (OpenCLBackend.config() == null || !OpenCLBackend.config().isEnabled()) {
                return AuthorizationDiagnostic.GPU_DISABLED;
            }
            if (!OpenCLBackend.isAvailable()) {
                return AuthorizationDiagnostic.DEVICE_UNAVAILABLE;
            }
            if (path == null) return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
            return AuthorizationDiagnostic.AUTHORIZED;
        } catch (Throwable ignored) {
            return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        }
    }

    /** Exact-match check used by Distant Horizons' externally driven batch paths. */
    public static boolean sameExecutionScope(Permit first, Permit other) {
        return sameExecutionScope((DispatchPermit) first, other);
    }

    /** Shared identity gate for production permits and non-runtime regression fixtures. */
    static boolean sameExecutionScope(DispatchPermit first, DispatchPermit other) {
        try {
            if (!isAuthorized(first, DispatchPath.DIRECT_DENSITY)
                    || !isAuthorized(other, DispatchPath.DIRECT_DENSITY)) {
                return false;
            }
            Object firstWorld = first.worldScopeIdentity();
            Object otherWorld = other.worldScopeIdentity();
            Object firstGenerator = first.generatorIdentity();
            Object otherGenerator = other.generatorIdentity();
            Object firstRandomState = first.randomStateIdentity();
            Object otherRandomState = other.randomStateIdentity();
            return sameScopeToken(firstWorld, otherWorld)
                    && firstGenerator != null && firstGenerator == otherGenerator
                    && firstRandomState != null && firstRandomState == otherRandomState;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean sameScopeToken(Object first, Object other) {
        return first != null && first == other;
    }

    /** Exact task identity gate shared by CompactIds and its hardware-free fixtures. */
    static boolean isAuthorizedForTask(DispatchPermit permit, DispatchPath path,
                                       Object expectedGenerator, Object expectedRandomState,
                                       Object expectedChunk) {
        try {
            return expectedGenerator != null && expectedRandomState != null && expectedChunk != null
                    && isAuthorized(permit, path)
                    && permit.generatorIdentity() == expectedGenerator
                    && permit.randomStateIdentity() == expectedRandomState
                    && permit.chunkIdentity() == expectedChunk;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** A stored payload's scope is checked separately from the permit and its map key. */
    static boolean sameDepositScope(DispatchPermit permit, Object depositScope) {
        try {
            return permit != null && depositScope != null
                    && permit.worldScopeIdentity() == depositScope;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean sameDepositRoute(Object expectedRoute, Object storedRoute) {
        return expectedRoute != null && expectedRoute == storedRoute;
    }

    /** Router identity belonging to a compiled climate target, independent of its task permit. */
    static boolean isAuthorizedForRouter(DispatchPermit permit, DispatchPath path,
                                         Object compiledRouter) {
        try {
            return compiledRouter != null && permit != null
                    && permit.routerIdentity() == compiledRouter
                    && isAuthorized(permit, path);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean dispatchIfAuthorizedForRouter(DispatchPermit permit, DispatchPath path,
                                                 Object compiledRouter, Runnable gpuDispatch) {
        Objects.requireNonNull(gpuDispatch, "gpuDispatch");
        if (!isAuthorizedForRouter(permit, path, compiledRouter)) {
            return false;
        }
        gpuDispatch.run();
        return true;
    }

    /** Pure decision function kept small for non-runtime regression coverage. */
    static boolean allowsDispatch(GeneratorKind kind, boolean contextLive,
                                  boolean scopeMatches, boolean gpuEnabled,
                                  boolean deviceAvailable, DispatchPath path) {
        if (!contextLive || !scopeMatches || !gpuEnabled || !deviceAvailable || path == null) {
            return false;
        }
        return kind == GeneratorKind.VANILLA_NOISE || kind == GeneratorKind.COMPATIBLE_NOISE;
    }

    static AuthorizationDiagnostic diagnoseAuthorization(DispatchPermit permit, DispatchPath path) {
        if (path == DispatchPath.DISTANT_HORIZONS) {
            // The current DH mixin exposes only a status and wrappers, not the generator or
            // RandomState actually used by DH's batch task. A ServerLevel-derived permit is
            // insufficient evidence, so this route stays CPU/per-chunk until the seam carries
            // those identities explicitly.
            return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        }
        if (permit == null) return AuthorizationDiagnostic.NO_FRAME;
        try {
            GeneratorKind kind = permit.kind();
            boolean contextLive = permit.contextLive();
            boolean gpuEnabled = permit.gpuEnabled();
            boolean deviceAvailable = permit.deviceAvailable();
            if (allowsDispatch(kind, contextLive, true, gpuEnabled, deviceAvailable, path)) {
                return AuthorizationDiagnostic.AUTHORIZED;
            }
            if (!contextLive) return AuthorizationDiagnostic.PERMIT_STALE_OR_RETIRED;
            if (!gpuEnabled) return AuthorizationDiagnostic.GPU_DISABLED;
            if (!deviceAvailable) return AuthorizationDiagnostic.DEVICE_UNAVAILABLE;
            if (!isGeneratorKindAllowed(kind)) return AuthorizationDiagnostic.GENERATOR_UNAUTHORIZED;
            return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        } catch (Throwable ignored) {
            return AuthorizationDiagnostic.OTHER_ELIGIBILITY;
        }
    }

    static boolean isAuthorized(DispatchPermit permit, DispatchPath path) {
        return diagnoseAuthorization(permit, path) == AuthorizationDiagnostic.AUTHORIZED;
    }

    /** Executes the real backend submission only after authorization; false means caller fallback. */
    static boolean dispatchIfAuthorized(DispatchPermit permit, DispatchPath path, Runnable gpuDispatch) {
        Objects.requireNonNull(gpuDispatch, "gpuDispatch");
        if (!isAuthorized(permit, path)) {
            return false;
        }
        gpuDispatch.run();
        return true;
    }

    /** Production backend boundary used by the direct delegate; denial or backend exceptions fall back. */
    enum DispatchState {
        DISPATCHED,
        AUTHORIZATION_DENIED,
        BACKEND_FAILED
    }

    record DispatchResult(DispatchState state, AuthorizationDiagnostic authorizationDiagnostic) {
        boolean dispatched() {
            return state == DispatchState.DISPATCHED;
        }
    }

    static DispatchResult dispatchWithFallbackDetailed(DispatchPermit permit, DispatchPath path,
                                                       Runnable gpuDispatch, Runnable cpuFallback,
                                                       Consumer<Throwable> onBackendFailure) {
        Objects.requireNonNull(gpuDispatch, "gpuDispatch");
        Objects.requireNonNull(cpuFallback, "cpuFallback");
        Objects.requireNonNull(onBackendFailure, "onBackendFailure");
        AuthorizationDiagnostic decision = diagnoseAuthorization(permit, path);
        if (decision != AuthorizationDiagnostic.AUTHORIZED) {
            cpuFallback.run();
            return new DispatchResult(DispatchState.AUTHORIZATION_DENIED, decision);
        }
        try {
            gpuDispatch.run();
            return new DispatchResult(DispatchState.DISPATCHED, AuthorizationDiagnostic.AUTHORIZED);
        } catch (Throwable failure) {
            try {
                onBackendFailure.accept(failure);
            } catch (Throwable ignored) {
                // Reporting a backend fault cannot suppress the CPU fallback.
            }
            cpuFallback.run();
            return new DispatchResult(DispatchState.BACKEND_FAILED, AuthorizationDiagnostic.OTHER_ELIGIBILITY);
        }
    }

    static boolean dispatchWithFallback(DispatchPermit permit, DispatchPath path,
                                        Runnable gpuDispatch, Runnable cpuFallback,
                                        Consumer<Throwable> onBackendFailure) {
        return dispatchWithFallbackDetailed(permit, path, gpuDispatch, cpuFallback,
                onBackendFailure).dispatched();
    }

    static boolean isAuthorizedInScope(DispatchPermit permit, DispatchPath path, Object expectedScope) {
        try {
            return expectedScope != null && permit != null
                    && permit.worldScopeIdentity() == expectedScope && isAuthorized(permit, path);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean dispatchIfAuthorizedInScope(DispatchPermit permit, DispatchPath path,
                                               Object expectedScope, Runnable gpuDispatch) {
        Objects.requireNonNull(gpuDispatch, "gpuDispatch");
        if (!isAuthorizedInScope(permit, path, expectedScope)) {
            return false;
        }
        gpuDispatch.run();
        return true;
    }

    /**
     * Returns whether a scope still names the active (level, generator, RandomState) tuple.
     * The level-close hook calls {@link #retireLevel(ServerLevel)} on the server lifecycle
     * thread; asynchronous workers consult this token without reading the server's mutable
     * level registry.
     */
    public static boolean isWorldScopeLive(WorldScope scope) {
        if (scope == null || !scope.active) {
            return false;
        }
        synchronized (WORLD_SCOPES) {
            if (!scope.active || isRetiredLevelLocked(scope)) {
                return false;
            }
            for (Iterator<ScopeIdentity> iterator = WORLD_SCOPES.iterator(); iterator.hasNext();) {
                ScopeIdentity identity = iterator.next();
                ServerLevel level = identity.level.get();
                ChunkGenerator generator = identity.generator.get();
                RandomState randomState = identity.randomState.get();
                if (level == null || generator == null || randomState == null || !identity.scope.active) {
                    identity.scope.invalidate();
                    iterator.remove();
                    continue;
                }
                if (identity.scope == scope) {
                    try {
                        return !isRetiredLevelLocked(level)
                                && level.getChunkSource().getGenerator() == generator
                                && level.getChunkSource().randomState() == randomState;
                    } catch (Throwable ignored) {
                        return false;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Atomically admits a short-lived enqueue for a live scope. The reservation is acquired
     * under the same monitor used by {@link #retireLevel(ServerLevel)}, but the operation runs
     * after releasing that monitor because dispatcher enqueue may wait for an OpenCL pipeline
     * slot. A lease acquired before retirement is admitted work; retirement revokes later leases
     * and never waits for an in-flight OpenCL job or a lease holder.
     */
    public static boolean runIfWorldScopeLive(WorldScope scope, Runnable enqueue) {
        if (scope == null || enqueue == null) {
            return false;
        }
        return runWithScopeLease(WORLD_SCOPES, () -> isWorldScopeLive(scope),
                () -> scope.activeLeases++,
                () -> {
                    if (scope.activeLeases <= 0) {
                        throw new IllegalStateException("WorldScope lease counter underflow");
                    }
                    scope.activeLeases--;
                }, enqueue);
    }

    /** Package-private deterministic lease seam shared by the non-Minecraft regression fixture. */
    static boolean runWithScopeLease(Object lock, BooleanSupplier scopeLive,
                                     Runnable acquire, Runnable release, Runnable operation) {
        if (lock == null || scopeLive == null || acquire == null || release == null || operation == null) {
            return false;
        }
        synchronized (lock) {
            if (!scopeLive.getAsBoolean()) {
                return false;
            }
            acquire.run();
        }
        try {
            operation.run();
            return true;
        } finally {
            synchronized (lock) {
                release.run();
            }
        }
    }

    /**
     * Permanently retires scopes for a ServerLevel as it closes. Tombstones are weak so this
     * lifecycle guard cannot keep an unloaded world alive; late callbacks can still see the
     * tombstone while they retain the old level, but cannot register a replacement scope.
     */
    public static void retireLevel(ServerLevel level) {
        if (level == null) {
            return;
        }
        synchronized (WORLD_SCOPES) {
            purgeRetiredLevelsLocked();
            if (!isRetiredLevelLocked(level)) {
                RETIRED_LEVELS.add(new WeakReference<>(level));
            }
            for (Iterator<ScopeIdentity> iterator = WORLD_SCOPES.iterator(); iterator.hasNext();) {
                ScopeIdentity identity = iterator.next();
                if (identity.level.get() == level) {
                    identity.scope.invalidate();
                    iterator.remove();
                }
            }
        }
    }

    private static boolean isRetiredLevelLocked(WorldScope scope) {
        for (Iterator<ScopeIdentity> iterator = WORLD_SCOPES.iterator(); iterator.hasNext();) {
            ScopeIdentity identity = iterator.next();
            if (identity.scope == scope) {
                ServerLevel level = identity.level.get();
                return level == null || isRetiredLevelLocked(level);
            }
        }
        return true;
    }

    private static boolean isRetiredLevelLocked(ServerLevel level) {
        purgeRetiredLevelsLocked();
        for (WeakReference<ServerLevel> retired : RETIRED_LEVELS) {
            if (retired.get() == level) {
                return true;
            }
        }
        return false;
    }

    private static void purgeRetiredLevelsLocked() {
        RETIRED_LEVELS.removeIf(reference -> reference.get() == null);
    }

    private static Permit create(ServerLevel level, ChunkGenerator generator,
                                 RandomState randomState, ChunkAccess chunk) {
        if (level == null || generator == null || randomState == null || chunk == null
                || level.getChunkSource().getGenerator() != generator
                || level.getChunkSource().randomState() != randomState) {
            return null;
        }
        GeneratorKind kind = classify(generator);
        WorldScope scope;
        synchronized (WORLD_SCOPES) {
            if (isRetiredLevelLocked(level)) {
                return null;
            }
            ScopeIdentity matchingWorld = null;
            for (Iterator<ScopeIdentity> iterator = WORLD_SCOPES.iterator(); iterator.hasNext();) {
                ScopeIdentity identity = iterator.next();
                ServerLevel existingLevel = identity.level.get();
                if (existingLevel == null) {
                    iterator.remove();
                    continue;
                }
                if (existingLevel == level) {
                    matchingWorld = identity;
                }
            }
            if (matchingWorld != null && matchingWorld.generator.get() == generator
                    && matchingWorld.randomState.get() == randomState) {
                scope = matchingWorld.scope;
            } else {
                scope = new WorldScope(NEXT_SCOPE.incrementAndGet(), level.dimension());
                WORLD_SCOPES.add(new ScopeIdentity(level, generator, randomState, scope));
                if (matchingWorld != null) {
                    matchingWorld.scope.invalidate();
                    WORLD_SCOPES.remove(matchingWorld);
                }
            }
        }
        return new Permit(level, generator, randomState, chunk, scope, kind);
    }

    /** Scope token contains no references to the world. It is safe to retain in bounded batch keys. */
    public static final class WorldScope {
        private final long id;
        private final ResourceKey<Level> dimension;
        private final GpuAuthorizationDiagnostics.WorldBucket diagnosticBucket;
        private volatile boolean active = true;
        /** Guarded by WORLD_SCOPES; retirement revokes admission but does not wait for holders. */
        private int activeLeases;

        WorldScope(long id, ResourceKey<Level> dimension) {
            this.id = id;
            this.dimension = dimension;
            this.diagnosticBucket = GpuAuthorizationDiagnostics.bucketForDimension(
                    dimension == null ? null : dimension.location().toString());
        }

        public long id() {
            return id;
        }

        public ResourceKey<Level> dimension() {
            return dimension;
        }

        public GpuAuthorizationDiagnostics.WorldBucket diagnosticBucket() {
            return diagnosticBucket;
        }

        private void invalidate() {
            active = false;
        }
    }

    /** Weak identity snapshot for the current (world, generator, RandomState) tuple. */
    private static final class ScopeIdentity {
        private final WeakReference<ServerLevel> level;
        private final WeakReference<ChunkGenerator> generator;
        private final WeakReference<RandomState> randomState;
        private final WorldScope scope;

        private ScopeIdentity(ServerLevel level, ChunkGenerator generator,
                              RandomState randomState, WorldScope scope) {
            this.level = new WeakReference<>(level);
            this.generator = new WeakReference<>(generator);
            this.randomState = new WeakReference<>(randomState);
            this.scope = scope;
        }
    }

    /** Immutable identity snapshot for one explicit worldgen task. Strong world objects are weakly held. */
    public static final class Permit implements DispatchPermit {
        private final WeakReference<ServerLevel> level;
        private final WeakReference<ChunkGenerator> generator;
        private final WeakReference<RandomState> randomState;
        private final WeakReference<ChunkAccess> chunk;
        private final WorldScope worldScope;
        private final GeneratorKind kind;

        private Permit(ServerLevel level, ChunkGenerator generator, RandomState randomState,
                       ChunkAccess chunk, WorldScope worldScope, GeneratorKind kind) {
            this.level = new WeakReference<>(level);
            this.generator = new WeakReference<>(generator);
            this.randomState = new WeakReference<>(randomState);
            this.chunk = new WeakReference<>(chunk);
            this.worldScope = worldScope;
            this.kind = kind;
        }

        public GeneratorKind kind() {
            return kind;
        }

        @Override
        public boolean contextLive() {
            return identitiesAreCurrent();
        }

        @Override
        public boolean gpuEnabled() {
            return OpenCLBackend.config() != null && OpenCLBackend.config().isEnabled();
        }

        @Override
        public boolean deviceAvailable() {
            return OpenCLBackend.isAvailable();
        }

        @Override
        public Object worldScopeIdentity() {
            return worldScope;
        }

        @Override
        public Object generatorIdentity() {
            return generator.get();
        }

        @Override
        public Object randomStateIdentity() {
            return randomState.get();
        }

        @Override
        public Object chunkIdentity() {
            return chunk.get();
        }

        @Override
        public Object routerIdentity() {
            RandomState state = randomState.get();
            return state == null ? null : state.router();
        }

        public WorldScope worldScope() {
            return worldScope;
        }

        public boolean allowsGpu() {
            return isAuthorized(this, DispatchPath.DIRECT_DENSITY);
        }

        public boolean allows(DispatchPath path) {
            return isAuthorized(this, path);
        }

        public boolean matches(ChunkGenerator expectedGenerator, ChunkAccess expectedChunk) {
            return allowsGpu() && generator.get() == expectedGenerator && chunk.get() == expectedChunk;
        }

        public boolean matches(ChunkGenerator expectedGenerator, RandomState expectedRandomState,
                               ChunkAccess expectedChunk) {
            return allowsGpu() && generator.get() == expectedGenerator
                    && randomState.get() == expectedRandomState && chunk.get() == expectedChunk;
        }

        public boolean matchesChunk(ChunkAccess expectedChunk) {
            return contextLive() && expectedChunk != null && chunk.get() == expectedChunk;
        }

        public boolean matchesSampler(Climate.Sampler sampler) {
            RandomState state = randomState.get();
            return contextLive() && sampler != null && state != null && state.sampler() == sampler;
        }

        public boolean matchesRouter(NoiseRouter router) {
            if (!contextLive() || router == null) {
                return false;
            }
            RandomState state = randomState.get();
            return state != null && state.router() == router;
        }

        private boolean identitiesAreCurrent() {
            ServerLevel l = level.get();
            ChunkGenerator g = generator.get();
            RandomState r = randomState.get();
            ChunkAccess c = chunk.get();
            if (l == null || g == null || r == null || c == null) {
                return false;
            }
            if (worldScope == null || !worldScope.active) {
                return false;
            }
            try {
                Object height = ((IChunkAccessLevelHeight) c).superchunk$getLevelHeightAccessor();
                return height == l && l.getChunkSource().getGenerator() == g
                        && l.getChunkSource().randomState() == r;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static final class ScopeFrame {
        private final ScopeFrame previous;
        private final DispatchPermit permit;
        private final AuthorizationDiagnostic diagnostic;

        private ScopeFrame(ScopeFrame previous, DispatchPermit permit,
                           AuthorizationDiagnostic diagnostic) {
            this.previous = previous;
            this.permit = permit;
            this.diagnostic = diagnostic;
        }
    }

    /** Try-with-resources scope; restoration is same-thread and exception-safe. */
    public static final class Scope implements GpuTaskScopeCarrier.TaskScope {
        private final Thread owner;
        private final ScopeFrame installed;
        private final Permit permit;
        private final GpuAuthorizationDiagnostics.DiagnosticScope diagnosticScope;
        private boolean closed;

        private Scope(Thread owner, ScopeFrame installed, Permit permit,
                      GpuAuthorizationDiagnostics.DiagnosticScope diagnosticScope) {
            this.owner = owner;
            this.installed = installed;
            this.permit = permit;
            this.diagnosticScope = diagnosticScope;
        }

        public Permit permit() {
            return permit;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (Thread.currentThread() != owner) {
                    throw new IllegalStateException("Worldgen GPU scope closed on a different thread");
                }
                // Out-of-order closure invalidates the whole stack. Restoring an older permit from a
                // task whose frame is no longer current could authorize an unrelated nested fill.
                if (CURRENT.get() != installed) {
                    CURRENT.remove();
                    return;
                }
                ScopeFrame previous = installed.previous;
                if (previous != null && previous.permit != null
                        && isAuthorized(previous.permit, DispatchPath.DIRECT_DENSITY)) {
                    CURRENT.set(previous);
                } else {
                    CURRENT.remove();
                }
            } finally {
                diagnosticScope.close();
            }
        }
    }
}
