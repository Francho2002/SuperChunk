package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics.ScopeKind;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Measures only the getChunkNow call used by the verified PE terrain cache. */
@Pseudo
@Mixin(targets = "lil1llilol11.lo1i1iloioil.lo1iioil1oo1i0io01l1i1lo$l0o001o0ol0ilooioooil11i", remap = false)
public abstract class MixinProjectExplosiveTerrainChunkCache {
    @WrapOperation(
            method = "ll1ol00o111oioool0l011lo(Lnet/minecraft/server/level/ServerLevel;II)"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkNow(II)"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk;", remap = false),
            require = 0)
    private LevelChunk superchunk$measureGetChunkNow(ServerChunkCache cache, int chunkX, int chunkZ,
                                                       Operation<LevelChunk> original) throws Throwable {
        long start = ProjectExplosivePerfDiagnostics.startOperation(ScopeKind.TERRAIN_SWEEP);
        LevelChunk result = null;
        boolean failed = false;
        try {
            result = original.call(cache, chunkX, chunkZ);
            return result;
        } catch (Throwable thrown) {
            failed = true;
            throw thrown;
        } finally {
            ProjectExplosivePerfDiagnostics.recordChunkNow(start, chunkX, chunkZ, result == null, failed);
        }
    }
}
