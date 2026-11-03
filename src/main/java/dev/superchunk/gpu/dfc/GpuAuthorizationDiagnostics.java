package dev.superchunk.gpu.dfc;

import org.slf4j.Logger;

import java.util.concurrent.atomic.LongAdder;

/**
 * Low-cardinality, aggregate diagnostics for the GPU authorization seams.
 * No world, chunk, generator, sampler, or task object is retained here.
 *
 * <p>Enabled by default for a diagnostic build. Set
 * {@code -Dsuperchunk.gpu.authorizationDiagnostics=false} to disable the counters.
 * Updates use striped {@link LongAdder}s and are emitted only from the existing
 * periodic fill summary; this class never logs from a chunk or sample operation.
 */
public final class GpuAuthorizationDiagnostics {

    /** Bounded attribution buckets; no world or dimension object is retained. */
    public enum WorldBucket {
        OVERWORLD,
        NETHER,
        END,
        MODDED,
        UNKNOWN
    }

    public enum Event {
        MIXIN_CONFIG_LOADED,
        CREATE_BIOMES_MIXIN_PRE_APPLY,
        CREATE_BIOMES_MIXIN_POST_APPLY,
        NOISE_FILL_MIXIN_PRE_APPLY,
        NOISE_FILL_MIXIN_POST_APPLY,
        COMPACT_IDS_MIXIN_PRE_APPLY,
        COMPACT_IDS_MIXIN_POST_APPLY,
        CREATE_BIOMES_HANDLER_ENTERED,
        CREATE_BIOMES_CONTEXT_CAPTURED,
        CREATE_BIOMES_SUPPLIER_SUBMITTED,
        CREATE_BIOMES_SUPPLIER_SUBMISSION_FAILED,
        CREATE_BIOMES_FUTURE_RETURNED,
        CREATE_BIOMES_SUPPLIER_STARTED,
        CREATE_BIOMES_SUPPLIER_COMPLETED,
        CREATE_BIOMES_SUPPLIER_EXCEPTION,
        CREATE_BIOMES_FUTURE_CANCELLED_BEFORE_START,
        CREATE_BIOMES_SCOPE_AUTHORIZED,
        CREATE_BIOMES_SCOPE_DENIED,
        CREATE_BIOMES_CONTEXT_RESTORED,
        NOISE_FILL_HANDLER_ENTERED,
        NOISE_FILL_SCOPE_AUTHORIZED,
        NOISE_FILL_SCOPE_DENIED,
        NOISE_FILL_BODY_COMPLETED,
        NOISE_FILL_CONTEXT_RESTORED,
        BIOME_GENERATOR_CLASS_ALLOWED,
        BIOME_GENERATOR_CLASS_DENIED,
        DENSITY_GENERATOR_CLASS_ALLOWED,
        DENSITY_GENERATOR_CLASS_DENIED,
        TASK_DENIAL_NO_INPUT,
        TASK_DENIAL_CHUNK_NULL,
        TASK_DENIAL_LEVEL_ACCESSOR_NOT_SERVER_LEVEL,
        TASK_DENIAL_LEVEL_ACCESSOR_EXCEPTION,
        TASK_DENIAL_GENERATOR_MISMATCH,
        TASK_DENIAL_RANDOM_STATE_MISMATCH,
        TASK_DENIAL_SCOPE_RETIRED,
        TASK_DENIAL_WORLD_OR_DIMENSION,
        TASK_DENIAL_STALE_OR_RETIRED,
        TASK_DENIAL_GENERATOR,
        TASK_DENIAL_GPU_DISABLED,
        TASK_DENIAL_DEVICE_UNAVAILABLE,
        TASK_DENIAL_OTHER,
        CLIMATE_BEGIN_ENTERED,
        CLIMATE_BEGIN_AUTHORIZED,
        CLIMATE_OFFLOAD_DISABLED,
        CLIMATE_BATCH_STORE_BYPASSED_CONFIG,
        CLIMATE_BACKEND_UNAVAILABLE,
        CLIMATE_CHUNK_NULL,
        CLIMATE_LEVEL_ACCESSOR_NOT_SERVER_LEVEL,
        CLIMATE_LEVEL_ACCESSOR_EXCEPTION,
        CLIMATE_GENERATOR_MISMATCH,
        CLIMATE_RANDOM_STATE_MISMATCH,
        CLIMATE_SCOPE_RETIRED,
        CLIMATE_NO_AUTH_CONTEXT,
        CLIMATE_SCOPE_DENIED,
        CLIMATE_PERMIT_STALE_OR_RETIRED,
        CLIMATE_WORLD_OR_DIMENSION_MISMATCH,
        CLIMATE_CHUNK_MISMATCH,
        CLIMATE_SAMPLER_MISMATCH,
        CLIMATE_GENERATOR_UNAUTHORIZED,
        CLIMATE_GPU_UNAVAILABLE,
        CLIMATE_GROUP_UNAVAILABLE,
        CLIMATE_GROUP_MISMATCH,
        CLIMATE_OTHER_INELIGIBLE,
        CLIMATE_RESOLVER_NEVER_SAMPLES,
        CLIMATE_STORE_LOOKUP,
        CLIMATE_STORE_HIT,
        CLIMATE_STORE_MISS,
        CLIMATE_STORE_INVALID_PAYLOAD,
        CLIMATE_STORE_EXCEPTION,
        CLIMATE_STORE_COPIED,
        CLIMATE_STORE_USED,
        CLIMATE_STORE_COPIED_UNUSED,
        CLIMATE_PREFETCH_CONTEXT_CAPTURED,
        CLIMATE_PREFETCH_CONTEXT_REVALIDATED,
        CLIMATE_PREFETCH_CONTEXT_REJECTED,
        CLIMATE_PREFETCH_RESULT_REJECTED,
        DENSITY_PREFETCH_CONTEXT_CAPTURED,
        DENSITY_PREFETCH_CONTEXT_REVALIDATED,
        DENSITY_PREFETCH_CONTEXT_REJECTED,
        DENSITY_PREFETCH_AUTHORIZATION_REJECTED,
        DENSITY_PREFETCH_RESULT_REJECTED,
        DENSITY_PREFETCH_SUBMITTED,
        DENSITY_PREFETCH_STORE_PUT,
        DENSITY_DELEGATE_FILL_ENTERED,
        DENSITY_DELEGATE_ABSENT,
        DENSITY_DELEGATE_UNRESOLVED,
        DENSITY_DELEGATE_RESOLVED,
        DENSITY_DELEGATE_DISABLED,
        DENSITY_PERMIT_ABSENT,
        DENSITY_PERMIT_DENIED,
        DENSITY_GPU_UNAVAILABLE,
        DENSITY_CHUNK_NULL,
        DENSITY_LEVEL_ACCESSOR_NOT_SERVER_LEVEL,
        DENSITY_LEVEL_ACCESSOR_EXCEPTION,
        DENSITY_GENERATOR_MISMATCH,
        DENSITY_RANDOM_STATE_MISMATCH,
        DENSITY_SCOPE_RETIRED,
        DENSITY_WORLD_CONTEXT_MISMATCH,
        DENSITY_GENERATOR_UNAUTHORIZED,
        DENSITY_INPUT_SHAPE_REJECTED,
        DENSITY_FILL_SHAPE_REJECTED,
        DENSITY_GRID_CACHE_SERVED,
        DENSITY_GPU_ATTEMPT,
        DENSITY_GPU_FILL_COMPLETED,
        DENSITY_GPU_FILL_FAILED,
        DENSITY_GPU_EXECUTION_FALLBACK,
        DENSITY_FUSED_DISPATCH_REJECTED,
        DENSITY_FUSED_DISPATCH_COMPLETED,
        DENSITY_FUSED_DISPATCH_FAILED,
        DENSITY_BATCH_ND_RANGE_ENQUEUED,
        DENSITY_BATCH_READBACK_COMPLETED,
        DENSITY_BATCH_COMPLETION_FAILED,
        DENSITY_BATCH_ENQUEUE_FAILED,
        DENSITY_BATCH_POST_DISPATCH_NO_READBACK_FAILURE,
        CLIMATE_BATCH_ND_RANGE_ENQUEUED,
        CLIMATE_BATCH_READBACK_COMPLETED,
        CLIMATE_BATCH_COMPLETION_FAILED,
        CLIMATE_BATCH_ENQUEUE_FAILED,
        CLIMATE_BATCH_POST_DISPATCH_NO_READBACK_FAILURE,
        COMBINED_BATCH_DISPATCH_ENQUEUED,
        COMBINED_BATCH_READBACK_COMPLETED,
        COMBINED_BATCH_COMPLETION_FAILED,
        COMBINED_BATCH_ENQUEUE_FAILED,
        COMBINED_BATCH_POST_DISPATCH_NO_READBACK_FAILURE,
        DENSITY_CPU_FALLBACK,
        DENSITY_COMPILE_FALLBACK,
        DENSITY_COMPACT_HEAD_HANDLER_ENTERED,
        DENSITY_COMPACT_CONSUME_SUCCEEDED,
        DENSITY_COMPACT_CONSUME_FELL_BACK,
        DENSITY_COMPACT_VERIFY_HANDLER_ENTERED,
        DENSITY_COMPACT_TAIL_HANDLER_ENTERED,
        COMPILE_REJECTED_EXPANSION,
        COMPILE_DF_EXPANSION_FALLBACK,
        COMPILE_UNSUPPORTED_NODE,
        COMPILE_OPENCL_BUILD_FAILED,
        COMPILE_OTHER_FALLBACK,
        DENSITY_OPENCL_ND_RANGE_ENQUEUED,
        DENSITY_OPENCL_READBACK_COMPLETED,
        COMPILE_DF_FALLBACK,
        COMPILE_DF_GPU_READY
    }

    // Avoid initializing OpenCLBackend from the Mixin config plugin's early callbacks.
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SuperChunk-GPU");
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("superchunk.gpu.authorizationDiagnostics", "true"));
    private static final ThreadLocal<WorldBucket> CURRENT_WORLD = new ThreadLocal<>();
    private static final LongAdder[][] COUNTERS = new LongAdder[WorldBucket.values().length][Event.values().length];

    static {
        for (int world = 0; world < COUNTERS.length; world++) {
            for (int event = 0; event < COUNTERS[world].length; event++) {
                COUNTERS[world][event] = new LongAdder();
            }
        }
    }

    private GpuAuthorizationDiagnostics() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void record(Event event) {
        record(event, currentBucket());
    }

    public static void record(Event event, WorldBucket bucket) {
        if (ENABLED && event != null) {
            COUNTERS[(bucket == null ? WorldBucket.UNKNOWN : bucket).ordinal()][event.ordinal()].increment();
        }
    }

    static long count(Event event) {
        return count(event, currentBucket());
    }

    static long count(Event event, WorldBucket bucket) {
        return COUNTERS[(bucket == null ? WorldBucket.UNKNOWN : bucket).ordinal()][event.ordinal()].sum();
    }

    static long totalCount(Event event) {
        long total = 0L;
        for (WorldBucket bucket : WorldBucket.values()) {
            total += count(event, bucket);
        }
        return total;
    }

    public static WorldBucket bucketForDimension(String dimensionLocation) {
        if (dimensionLocation == null) return WorldBucket.UNKNOWN;
        return switch (dimensionLocation) {
            case "minecraft:overworld" -> WorldBucket.OVERWORLD;
            case "minecraft:the_nether" -> WorldBucket.NETHER;
            case "minecraft:the_end" -> WorldBucket.END;
            default -> dimensionLocation.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    ? WorldBucket.MODDED : WorldBucket.UNKNOWN;
        };
    }

    public static WorldBucket currentBucket() {
        WorldBucket bucket = CURRENT_WORLD.get();
        return bucket == null ? WorldBucket.UNKNOWN : bucket;
    }

    /** Installs only a bounded enum in a ThreadLocal; close restores nested attribution. */
    public static DiagnosticScope openWorldScope(WorldBucket bucket) {
        if (!ENABLED) return DiagnosticScope.NOOP;
        WorldBucket next = bucket == null ? WorldBucket.UNKNOWN : bucket;
        WorldBucket previous = CURRENT_WORLD.get();
        if (previous == next) return DiagnosticScope.NOOP;
        CURRENT_WORLD.set(next);
        return new DiagnosticScope(Thread.currentThread(), previous);
    }

    public static DiagnosticScope openWorldScope(GpuWorldgenPolicy.Permit permit) {
        return openWorldScope(permit == null || permit.worldScope() == null
                ? WorldBucket.UNKNOWN : permit.worldScope().diagnosticBucket());
    }

    public static final class DiagnosticScope implements AutoCloseable {
        private static final DiagnosticScope NOOP = new DiagnosticScope();
        private final Thread owner;
        private final WorldBucket previous;
        private boolean closed;

        private DiagnosticScope() {
            this.owner = null;
            this.previous = null;
            this.closed = true;
        }

        private DiagnosticScope(Thread owner, WorldBucket previous) {
            this.owner = owner;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed || owner != Thread.currentThread()) return;
            closed = true;
            if (previous == null) CURRENT_WORLD.remove();
            else CURRENT_WORLD.set(previous);
        }
    }

    static void recordBatchPrimaryEnqueued(BatchWorkload workload, WorldBucket bucket) {
        switch (workload) {
            case DENSITY -> record(Event.DENSITY_BATCH_ND_RANGE_ENQUEUED, bucket);
            case CLIMATE -> record(Event.CLIMATE_BATCH_ND_RANGE_ENQUEUED, bucket);
            case OTHER -> { }
        }
    }

    static void recordBatchPrimaryEnqueueFailed(BatchWorkload workload, WorldBucket bucket) {
        switch (workload) {
            case DENSITY -> record(Event.DENSITY_BATCH_ENQUEUE_FAILED, bucket);
            case CLIMATE -> record(Event.CLIMATE_BATCH_ENQUEUE_FAILED, bucket);
            case OTHER -> { }
        }
    }

    /** Records a failure after the primary NDRange, without claiming a readback existed. */
    static void recordBatchPostDispatchNoReadbackFailure(BatchWorkload workload, boolean includesClimate,
                                                         WorldBucket bucket) {
        switch (workload) {
            case DENSITY -> record(Event.DENSITY_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket);
            case CLIMATE -> record(Event.CLIMATE_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket);
            case OTHER -> { }
        }
        if (includesClimate) {
            record(Event.COMBINED_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket);
        }
    }

    /** Shared catch-path classifier, used by the dispatcher and its offline regression. */
    static void recordBatchEnqueueFailure(boolean primaryDispatchRecorded, boolean readbackEnqueued,
                                          BatchWorkload workload, boolean includesClimate,
                                          WorldBucket bucket) {
        if (!primaryDispatchRecorded) {
            recordBatchPrimaryEnqueueFailed(workload, bucket);
        } else if (!readbackEnqueued) {
            recordBatchPostDispatchNoReadbackFailure(workload, includesClimate, bucket);
        } else {
            recordBatchReadback(workload, includesClimate, false, bucket);
        }
    }

    /** A climate kernel was enqueued, but its own readback command was not. */
    static void recordClimateDispatchWithoutReadbackFailure(boolean combined, WorldBucket bucket) {
        record(Event.CLIMATE_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket);
        if (combined) record(Event.COMBINED_BATCH_POST_DISPATCH_NO_READBACK_FAILURE, bucket);
    }

    static void recordClimateChainEnqueued(boolean combined, WorldBucket bucket) {
        record(Event.CLIMATE_BATCH_ND_RANGE_ENQUEUED, bucket);
        if (combined) record(Event.COMBINED_BATCH_DISPATCH_ENQUEUED, bucket);
    }

    static void recordClimateChainEnqueueFailed(boolean combined, WorldBucket bucket) {
        record(Event.CLIMATE_BATCH_ENQUEUE_FAILED, bucket);
        if (combined) record(Event.COMBINED_BATCH_ENQUEUE_FAILED, bucket);
    }

    static void recordClimateChainCompletionFailed(boolean combined, WorldBucket bucket) {
        record(Event.CLIMATE_BATCH_COMPLETION_FAILED, bucket);
        if (combined) record(Event.COMBINED_BATCH_COMPLETION_FAILED, bucket);
    }

    static void recordBatchReadback(BatchWorkload workload, boolean includesClimate, boolean completed,
                                    WorldBucket bucket) {
        Event primary = workload == BatchWorkload.DENSITY
                ? (completed ? Event.DENSITY_BATCH_READBACK_COMPLETED : Event.DENSITY_BATCH_COMPLETION_FAILED)
                : workload == BatchWorkload.CLIMATE
                ? (completed ? Event.CLIMATE_BATCH_READBACK_COMPLETED : Event.CLIMATE_BATCH_COMPLETION_FAILED)
                : null;
        record(primary, bucket);
        if (includesClimate) {
            record(completed ? Event.CLIMATE_BATCH_READBACK_COMPLETED : Event.CLIMATE_BATCH_COMPLETION_FAILED,
                    bucket);
            if (workload == BatchWorkload.DENSITY) {
                record(completed ? Event.COMBINED_BATCH_READBACK_COMPLETED : Event.COMBINED_BATCH_COMPLETION_FAILED,
                        bucket);
            }
        }
    }

    enum BatchWorkload {
        DENSITY,
        CLIMATE,
        OTHER
    }

    public static void recordTaskDenial(GpuWorldgenPolicy.AuthorizationDiagnostic reason) {
        if (!ENABLED) return;
        Event event = switch (reason) {
            case NO_FRAME, DENIED_FRAME -> Event.TASK_DENIAL_NO_INPUT;
            case CHUNK_NULL -> Event.TASK_DENIAL_CHUNK_NULL;
            case LEVEL_ACCESSOR_NOT_SERVER_LEVEL -> Event.TASK_DENIAL_LEVEL_ACCESSOR_NOT_SERVER_LEVEL;
            case LEVEL_ACCESSOR_EXCEPTION -> Event.TASK_DENIAL_LEVEL_ACCESSOR_EXCEPTION;
            case GENERATOR_MISMATCH -> Event.TASK_DENIAL_GENERATOR_MISMATCH;
            case RANDOM_STATE_MISMATCH -> Event.TASK_DENIAL_RANDOM_STATE_MISMATCH;
            case SCOPE_RETIRED -> Event.TASK_DENIAL_SCOPE_RETIRED;
            case WORLD_DIMENSION_MISMATCH -> Event.TASK_DENIAL_WORLD_OR_DIMENSION;
            case PERMIT_STALE_OR_RETIRED -> Event.TASK_DENIAL_STALE_OR_RETIRED;
            case GENERATOR_UNAUTHORIZED -> Event.TASK_DENIAL_GENERATOR;
            case GPU_DISABLED -> Event.TASK_DENIAL_GPU_DISABLED;
            case DEVICE_UNAVAILABLE -> Event.TASK_DENIAL_DEVICE_UNAVAILABLE;
            case AUTHORIZED -> null;
            default -> Event.TASK_DENIAL_OTHER;
        };
        record(event);
    }

    public static void recordClimateAuthorizationFailure(
            GpuWorldgenPolicy.AuthorizationDiagnostic reason) {
        recordClimateAuthorizationFailure(reason, currentBucket());
    }

    public static void recordClimateAuthorizationFailure(
            GpuWorldgenPolicy.AuthorizationDiagnostic reason, WorldBucket bucket) {
        if (!ENABLED) return;
        Event event = switch (reason) {
            case NO_FRAME -> Event.CLIMATE_NO_AUTH_CONTEXT;
            case CHUNK_NULL -> Event.CLIMATE_CHUNK_NULL;
            case LEVEL_ACCESSOR_NOT_SERVER_LEVEL -> Event.CLIMATE_LEVEL_ACCESSOR_NOT_SERVER_LEVEL;
            case LEVEL_ACCESSOR_EXCEPTION -> Event.CLIMATE_LEVEL_ACCESSOR_EXCEPTION;
            case GENERATOR_MISMATCH -> Event.CLIMATE_GENERATOR_MISMATCH;
            case RANDOM_STATE_MISMATCH -> Event.CLIMATE_RANDOM_STATE_MISMATCH;
            case SCOPE_RETIRED -> Event.CLIMATE_SCOPE_RETIRED;
            case DENIED_FRAME -> Event.CLIMATE_SCOPE_DENIED;
            case PERMIT_STALE_OR_RETIRED -> Event.CLIMATE_PERMIT_STALE_OR_RETIRED;
            case WORLD_DIMENSION_MISMATCH -> Event.CLIMATE_WORLD_OR_DIMENSION_MISMATCH;
            case CHUNK_MISMATCH -> Event.CLIMATE_CHUNK_MISMATCH;
            case SAMPLER_MISMATCH -> Event.CLIMATE_SAMPLER_MISMATCH;
            case GENERATOR_UNAUTHORIZED -> Event.CLIMATE_GENERATOR_UNAUTHORIZED;
            case GPU_DISABLED, DEVICE_UNAVAILABLE -> Event.CLIMATE_GPU_UNAVAILABLE;
            case GROUP_MISMATCH -> Event.CLIMATE_GROUP_MISMATCH;
            case AUTHORIZED -> Event.CLIMATE_OTHER_INELIGIBLE;
            default -> Event.CLIMATE_OTHER_INELIGIBLE;
        };
        record(event, bucket);
    }

    public static void recordDensityAuthorizationFailure(
            GpuWorldgenPolicy.AuthorizationDiagnostic reason) {
        recordDensityAuthorizationFailure(reason, currentBucket());
    }

    public static void recordDensityAuthorizationFailure(
            GpuWorldgenPolicy.AuthorizationDiagnostic reason, WorldBucket bucket) {
        if (!ENABLED) return;
        Event event = switch (reason) {
            case NO_FRAME -> Event.DENSITY_PERMIT_ABSENT;
            case CHUNK_NULL -> Event.DENSITY_CHUNK_NULL;
            case LEVEL_ACCESSOR_NOT_SERVER_LEVEL -> Event.DENSITY_LEVEL_ACCESSOR_NOT_SERVER_LEVEL;
            case LEVEL_ACCESSOR_EXCEPTION -> Event.DENSITY_LEVEL_ACCESSOR_EXCEPTION;
            case GENERATOR_MISMATCH -> Event.DENSITY_GENERATOR_MISMATCH;
            case RANDOM_STATE_MISMATCH -> Event.DENSITY_RANDOM_STATE_MISMATCH;
            case SCOPE_RETIRED -> Event.DENSITY_SCOPE_RETIRED;
            case DENIED_FRAME -> Event.DENSITY_PERMIT_DENIED;
            case PERMIT_STALE_OR_RETIRED -> Event.DENSITY_PERMIT_DENIED;
            case WORLD_DIMENSION_MISMATCH -> Event.DENSITY_WORLD_CONTEXT_MISMATCH;
            case GENERATOR_UNAUTHORIZED -> Event.DENSITY_GENERATOR_UNAUTHORIZED;
            case GPU_DISABLED, DEVICE_UNAVAILABLE -> Event.DENSITY_GPU_UNAVAILABLE;
            case AUTHORIZED -> Event.DENSITY_PERMIT_DENIED;
            default -> Event.DENSITY_PERMIT_DENIED;
        };
        record(event, bucket);
    }

    public static void recordCompileFallback(String reason) {
        if (!ENABLED) return;
        record(Event.COMPILE_DF_FALLBACK);
        if (reason == null) {
            record(Event.COMPILE_OTHER_FALLBACK);
        } else if (reason.startsWith("kernel expansion guard:")) {
            record(Event.COMPILE_DF_EXPANSION_FALLBACK);
        } else if (reason.startsWith("unsupported node:")) {
            record(Event.COMPILE_UNSUPPORTED_NODE);
        } else if (reason.contains("clBuildProgram failed")) {
            record(Event.COMPILE_OPENCL_BUILD_FAILED);
        } else {
            record(Event.COMPILE_OTHER_FALLBACK);
        }
    }

    /** Emits stable category names and all values, including zero, once per fill summary. */
    public static void logSummary(String when) {
        if (!ENABLED) return;
        for (WorldBucket bucket : WorldBucket.values()) {
            logGroup(when, "mixin-async", bucket,
                Event.MIXIN_CONFIG_LOADED,
                Event.CREATE_BIOMES_MIXIN_PRE_APPLY, Event.CREATE_BIOMES_MIXIN_POST_APPLY,
                Event.NOISE_FILL_MIXIN_PRE_APPLY, Event.NOISE_FILL_MIXIN_POST_APPLY,
                Event.COMPACT_IDS_MIXIN_PRE_APPLY, Event.COMPACT_IDS_MIXIN_POST_APPLY,
                Event.CREATE_BIOMES_HANDLER_ENTERED, Event.CREATE_BIOMES_CONTEXT_CAPTURED,
                Event.CREATE_BIOMES_SUPPLIER_SUBMITTED, Event.CREATE_BIOMES_SUPPLIER_SUBMISSION_FAILED,
                Event.CREATE_BIOMES_FUTURE_RETURNED, Event.CREATE_BIOMES_SUPPLIER_STARTED,
                Event.CREATE_BIOMES_SUPPLIER_COMPLETED, Event.CREATE_BIOMES_SUPPLIER_EXCEPTION,
                Event.CREATE_BIOMES_FUTURE_CANCELLED_BEFORE_START, Event.CREATE_BIOMES_SCOPE_AUTHORIZED,
                Event.CREATE_BIOMES_SCOPE_DENIED, Event.CREATE_BIOMES_CONTEXT_RESTORED,
                Event.NOISE_FILL_HANDLER_ENTERED, Event.NOISE_FILL_SCOPE_AUTHORIZED,
                Event.NOISE_FILL_SCOPE_DENIED, Event.NOISE_FILL_BODY_COMPLETED,
                Event.NOISE_FILL_CONTEXT_RESTORED, Event.BIOME_GENERATOR_CLASS_ALLOWED,
                Event.BIOME_GENERATOR_CLASS_DENIED, Event.DENSITY_GENERATOR_CLASS_ALLOWED,
                Event.DENSITY_GENERATOR_CLASS_DENIED, Event.TASK_DENIAL_NO_INPUT,
                Event.TASK_DENIAL_CHUNK_NULL, Event.TASK_DENIAL_LEVEL_ACCESSOR_NOT_SERVER_LEVEL,
                Event.TASK_DENIAL_LEVEL_ACCESSOR_EXCEPTION, Event.TASK_DENIAL_GENERATOR_MISMATCH,
                Event.TASK_DENIAL_RANDOM_STATE_MISMATCH, Event.TASK_DENIAL_SCOPE_RETIRED,
                Event.TASK_DENIAL_WORLD_OR_DIMENSION, Event.TASK_DENIAL_STALE_OR_RETIRED,
                Event.TASK_DENIAL_GENERATOR, Event.TASK_DENIAL_GPU_DISABLED,
                Event.TASK_DENIAL_DEVICE_UNAVAILABLE, Event.TASK_DENIAL_OTHER);
            logGroup(when, "climate-consumer", bucket,
                Event.CLIMATE_BEGIN_ENTERED, Event.CLIMATE_BEGIN_AUTHORIZED,
                Event.CLIMATE_OFFLOAD_DISABLED, Event.CLIMATE_BATCH_STORE_BYPASSED_CONFIG,
                Event.CLIMATE_BACKEND_UNAVAILABLE, Event.CLIMATE_CHUNK_NULL,
                Event.CLIMATE_LEVEL_ACCESSOR_NOT_SERVER_LEVEL, Event.CLIMATE_LEVEL_ACCESSOR_EXCEPTION,
                Event.CLIMATE_GENERATOR_MISMATCH, Event.CLIMATE_RANDOM_STATE_MISMATCH,
                Event.CLIMATE_SCOPE_RETIRED,
                Event.CLIMATE_NO_AUTH_CONTEXT, Event.CLIMATE_SCOPE_DENIED,
                Event.CLIMATE_PERMIT_STALE_OR_RETIRED, Event.CLIMATE_WORLD_OR_DIMENSION_MISMATCH,
                Event.CLIMATE_CHUNK_MISMATCH, Event.CLIMATE_SAMPLER_MISMATCH,
                Event.CLIMATE_GENERATOR_UNAUTHORIZED, Event.CLIMATE_GPU_UNAVAILABLE,
                Event.CLIMATE_GROUP_UNAVAILABLE, Event.CLIMATE_GROUP_MISMATCH,
                Event.CLIMATE_OTHER_INELIGIBLE, Event.CLIMATE_RESOLVER_NEVER_SAMPLES,
                Event.CLIMATE_STORE_LOOKUP,
                Event.CLIMATE_STORE_HIT, Event.CLIMATE_STORE_MISS,
                Event.CLIMATE_STORE_INVALID_PAYLOAD, Event.CLIMATE_STORE_EXCEPTION,
                Event.CLIMATE_STORE_COPIED, Event.CLIMATE_STORE_USED,
                Event.CLIMATE_STORE_COPIED_UNUSED, Event.CLIMATE_PREFETCH_CONTEXT_CAPTURED,
                Event.CLIMATE_PREFETCH_CONTEXT_REVALIDATED,
                Event.CLIMATE_PREFETCH_CONTEXT_REJECTED,
                Event.CLIMATE_PREFETCH_RESULT_REJECTED);
            logGroup(when, "density-route", bucket,
                Event.DENSITY_PREFETCH_CONTEXT_CAPTURED,
                Event.DENSITY_PREFETCH_CONTEXT_REVALIDATED,
                Event.DENSITY_PREFETCH_CONTEXT_REJECTED,
                Event.DENSITY_PREFETCH_AUTHORIZATION_REJECTED,
                Event.DENSITY_PREFETCH_RESULT_REJECTED,
                Event.DENSITY_PREFETCH_SUBMITTED,
                Event.DENSITY_PREFETCH_STORE_PUT,
                Event.DENSITY_DELEGATE_FILL_ENTERED, Event.DENSITY_DELEGATE_ABSENT,
                Event.DENSITY_DELEGATE_UNRESOLVED, Event.DENSITY_DELEGATE_RESOLVED,
                Event.DENSITY_DELEGATE_DISABLED,
                Event.DENSITY_PERMIT_ABSENT, Event.DENSITY_PERMIT_DENIED, Event.DENSITY_GPU_UNAVAILABLE,
                Event.DENSITY_CHUNK_NULL, Event.DENSITY_LEVEL_ACCESSOR_NOT_SERVER_LEVEL,
                Event.DENSITY_LEVEL_ACCESSOR_EXCEPTION, Event.DENSITY_GENERATOR_MISMATCH,
                Event.DENSITY_RANDOM_STATE_MISMATCH, Event.DENSITY_SCOPE_RETIRED,
                Event.DENSITY_WORLD_CONTEXT_MISMATCH, Event.DENSITY_GENERATOR_UNAUTHORIZED,
                Event.DENSITY_INPUT_SHAPE_REJECTED, Event.DENSITY_FILL_SHAPE_REJECTED,
                Event.DENSITY_GRID_CACHE_SERVED, Event.DENSITY_GPU_ATTEMPT,
                Event.DENSITY_GPU_FILL_COMPLETED, Event.DENSITY_GPU_FILL_FAILED,
                Event.DENSITY_GPU_EXECUTION_FALLBACK,
                Event.DENSITY_FUSED_DISPATCH_REJECTED,
                Event.DENSITY_FUSED_DISPATCH_COMPLETED, Event.DENSITY_FUSED_DISPATCH_FAILED,
                Event.DENSITY_CPU_FALLBACK, Event.DENSITY_COMPILE_FALLBACK,
                Event.DENSITY_COMPACT_HEAD_HANDLER_ENTERED,
                Event.DENSITY_COMPACT_CONSUME_SUCCEEDED,
                Event.DENSITY_COMPACT_CONSUME_FELL_BACK,
                Event.DENSITY_COMPACT_VERIFY_HANDLER_ENTERED,
                Event.DENSITY_COMPACT_TAIL_HANDLER_ENTERED,
                Event.DENSITY_OPENCL_ND_RANGE_ENQUEUED,
                Event.DENSITY_OPENCL_READBACK_COMPLETED);
            logGroup(when, "gpu-batches", bucket,
                Event.DENSITY_BATCH_ND_RANGE_ENQUEUED, Event.DENSITY_BATCH_READBACK_COMPLETED,
                Event.DENSITY_BATCH_COMPLETION_FAILED, Event.DENSITY_BATCH_ENQUEUE_FAILED,
                Event.DENSITY_BATCH_POST_DISPATCH_NO_READBACK_FAILURE,
                Event.CLIMATE_BATCH_ND_RANGE_ENQUEUED, Event.CLIMATE_BATCH_READBACK_COMPLETED,
                Event.CLIMATE_BATCH_COMPLETION_FAILED, Event.CLIMATE_BATCH_ENQUEUE_FAILED,
                Event.CLIMATE_BATCH_POST_DISPATCH_NO_READBACK_FAILURE,
                Event.COMBINED_BATCH_DISPATCH_ENQUEUED, Event.COMBINED_BATCH_READBACK_COMPLETED,
                Event.COMBINED_BATCH_COMPLETION_FAILED, Event.COMBINED_BATCH_ENQUEUE_FAILED,
                Event.COMBINED_BATCH_POST_DISPATCH_NO_READBACK_FAILURE);
            logGroup(when, "density-compile", bucket,
                Event.COMPILE_DF_GPU_READY, Event.COMPILE_DF_FALLBACK,
                Event.COMPILE_REJECTED_EXPANSION, Event.COMPILE_DF_EXPANSION_FALLBACK,
                Event.COMPILE_UNSUPPORTED_NODE,
                Event.COMPILE_OPENCL_BUILD_FAILED, Event.COMPILE_OTHER_FALLBACK);
        }
    }

    private static void logGroup(String when, String group, WorldBucket bucket, Event... events) {
        StringBuilder fields = new StringBuilder();
        for (Event event : events) {
            if (!fields.isEmpty()) fields.append(' ');
            fields.append(event.name().toLowerCase(java.util.Locale.ROOT))
                    .append('=').append(count(event, bucket));
        }
        LOGGER.info("[SuperChunk-GPU] [auth-diag] ({}) {} scope={}: {}", when, group,
                bucket.name().toLowerCase(java.util.Locale.ROOT), fields);
    }
}
