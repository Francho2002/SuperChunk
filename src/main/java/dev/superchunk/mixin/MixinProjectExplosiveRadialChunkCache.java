package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics.ScopeKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Measures the synchronous ServerLevel.getChunk call in PE's private radial cache only. */
@Pseudo
@Mixin(targets = "lil1llilol11.lo1i1iloioil.l1o010il000l0o100li0oili$ll1ol00o111oioool0l011lo", remap = false)
public abstract class MixinProjectExplosiveRadialChunkCache {
    @WrapOperation(
            method = "ll1ol00o111oioool0l011lo(Lnet/minecraft/core/BlockPos;)"
                    + "Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getChunk(II)"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk;", remap = false),
            require = 0)
    private LevelChunk superchunk$measureSynchronousGetChunk(ServerLevel ignored, int chunkX, int chunkZ,
                                                               Operation<LevelChunk> original) throws Throwable {
        long start = ProjectExplosivePerfDiagnostics.startOperation(ScopeKind.RADIAL_DESTRUCTION);
        boolean failed = false;
        String exceptionClass = null;
        try {
            return original.call(ignored, chunkX, chunkZ);
        } catch (Throwable thrown) {
            failed = true;
            exceptionClass = thrown.getClass().getSimpleName();
            throw thrown;
        } finally {
            ProjectExplosivePerfDiagnostics.recordSynchronousGetChunk(start, chunkX, chunkZ, failed, exceptionClass);
        }
    }
}
