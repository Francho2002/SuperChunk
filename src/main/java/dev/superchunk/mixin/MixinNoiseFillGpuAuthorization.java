package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superchunk.gpu.dfc.GpuWorldgenPolicy;
import dev.superchunk.gpu.dfc.GpuAuthorizationDiagnostics;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Binds a per-task GPU permit only while the synchronous vanilla noise fill is executing.
 * Custom subclasses are still intercepted, then denied by exact-class/codec classification.
 * Asynchronous prefetch paths carry their own permit and never rely on this scope.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseFillGpuAuthorization {

    @WrapMethod(method = "doFill(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;II)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            require = 0)
    private ChunkAccess superchunk$withGpuPermit(Blender blender, StructureManager structures,
                                                 RandomState randomState, ChunkAccess chunk,
                                                 int minCellY, int cellCountY,
                                                 Operation<ChunkAccess> original) {
        GpuAuthorizationDiagnostics.WorldBucket bucket = GpuWorldgenPolicy.diagnosticBucketForChunk(chunk);
        GpuAuthorizationDiagnostics.record(GpuAuthorizationDiagnostics.Event.NOISE_FILL_HANDLER_ENTERED, bucket);
        ChunkGenerator generator = (ChunkGenerator) (Object) this;
        GpuWorldgenPolicy.GeneratorKind kind = GpuWorldgenPolicy.classify(generator);
        GpuAuthorizationDiagnostics.record(GpuWorldgenPolicy.isGeneratorKindAllowed(kind)
                ? GpuAuthorizationDiagnostics.Event.DENSITY_GENERATOR_CLASS_ALLOWED
                : GpuAuthorizationDiagnostics.Event.DENSITY_GENERATOR_CLASS_DENIED, bucket);
        GpuWorldgenPolicy.Scope scope = GpuWorldgenPolicy.enterTask(generator, chunk, randomState);
        GpuAuthorizationDiagnostics.record(scope.permit() != null
                ? GpuAuthorizationDiagnostics.Event.NOISE_FILL_SCOPE_AUTHORIZED
                : GpuAuthorizationDiagnostics.Event.NOISE_FILL_SCOPE_DENIED, bucket);
        try (scope) {
            ChunkAccess result = original.call(blender, structures, randomState, chunk, minCellY, cellCountY);
            GpuAuthorizationDiagnostics.record(GpuAuthorizationDiagnostics.Event.NOISE_FILL_BODY_COMPLETED, bucket);
            return result;
        } finally {
            GpuAuthorizationDiagnostics.record(GpuAuthorizationDiagnostics.Event.NOISE_FILL_CONTEXT_RESTORED, bucket);
        }
    }
}
