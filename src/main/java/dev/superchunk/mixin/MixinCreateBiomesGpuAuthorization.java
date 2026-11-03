package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.superchunk.gpu.dfc.GpuWorldgenPolicy;
import dev.superchunk.gpu.dfc.GpuAuthorizationDiagnostics;
import dev.superchunk.gpu.dfc.GpuTaskScopeCarrier;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Carries the exact generator/RandomState into the asynchronous body of createBiomes. The
 * authorization frame wraps the Supplier itself, so correctness does not depend on C2ME's
 * executor redirection or on which worker executes the task.
 */
@Mixin({ChunkGenerator.class, NoiseBasedChunkGenerator.class})
public abstract class MixinCreateBiomesGpuAuthorization {

    @WrapOperation(method = "createBiomes(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
            require = 0)
    private CompletableFuture<ChunkAccess> superchunk$withBiomeTaskPermit(
            Supplier<ChunkAccess> task, Executor executor,
            Operation<CompletableFuture<ChunkAccess>> original,
            @Local(argsOnly = true) RandomState randomState,
            @Local(argsOnly = true) ChunkAccess chunk) {
        GpuAuthorizationDiagnostics.WorldBucket bucket = GpuWorldgenPolicy.diagnosticBucketForChunk(chunk);
        GpuAuthorizationDiagnostics.record(
                GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_HANDLER_ENTERED, bucket);
        ChunkGenerator generator = (ChunkGenerator) (Object) this;
        GpuWorldgenPolicy.GeneratorKind kind = GpuWorldgenPolicy.classify(generator);
        GpuAuthorizationDiagnostics.record(GpuWorldgenPolicy.isGeneratorKindAllowed(kind)
                ? GpuAuthorizationDiagnostics.Event.BIOME_GENERATOR_CLASS_ALLOWED
                : GpuAuthorizationDiagnostics.Event.BIOME_GENERATOR_CLASS_DENIED, bucket);
        // These exact identities are captured by the Supplier, then the scope is created
        // on whichever executor thread actually runs it. Never inherit a worker ThreadLocal.
        GpuAuthorizationDiagnostics.record(
                GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_CONTEXT_CAPTURED, bucket);
        GpuTaskScopeCarrier.Lifecycle lifecycle = new GpuTaskScopeCarrier.Lifecycle();
        Supplier<ChunkAccess> scoped = GpuTaskScopeCarrier.wrap(task,
                () -> GpuWorldgenPolicy.enterTask(generator, chunk, randomState),
                scope -> GpuAuthorizationDiagnostics.record(scope.permit() != null
                        ? GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_SCOPE_AUTHORIZED
                        : GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_SCOPE_DENIED, bucket),
                bucket,
                lifecycle,
                () -> GpuAuthorizationDiagnostics.record(
                        GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_SUPPLIER_STARTED, bucket),
                () -> GpuAuthorizationDiagnostics.record(
                        GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_SUPPLIER_COMPLETED, bucket),
                () -> GpuAuthorizationDiagnostics.record(
                        GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_SUPPLIER_EXCEPTION, bucket),
                () -> GpuAuthorizationDiagnostics.record(
                        GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_CONTEXT_RESTORED, bucket));
        return GpuTaskScopeCarrier.submit(
                submittedSupplier -> original.call(submittedSupplier, executor), scoped, lifecycle,
                () -> GpuAuthorizationDiagnostics.record(
                        GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_SUPPLIER_SUBMITTED, bucket),
                () -> GpuAuthorizationDiagnostics.record(
                        GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_SUPPLIER_SUBMISSION_FAILED, bucket),
                () -> GpuAuthorizationDiagnostics.record(
                        GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_FUTURE_RETURNED, bucket),
                () -> GpuAuthorizationDiagnostics.record(
                        GpuAuthorizationDiagnostics.Event.CREATE_BIOMES_FUTURE_CANCELLED_BEFORE_START, bucket));
    }
}
