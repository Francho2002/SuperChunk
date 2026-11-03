package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics.Scope;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics.ScopeKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Exact-bytecode-gated, OFF-by-default diagnostics for PE's incremental terrain sweep. */
@Pseudo
@Mixin(targets = "lil1llilol11.lo1i1iloioil.lo1iioil1oo1i0io01l1i1lo", remap = false)
public abstract class MixinProjectExplosiveTerrainSweep {
    @Shadow(remap = false)
    private BlockPos loll01loloioooo00l00lo11;

    @Shadow(remap = false)
    private double ll0llol1li01lolio10011l0;

    @WrapMethod(method = "l11oo1ilili0l0lloii0oili(Lnet/minecraft/server/level/ServerLevel;)V", require = 0)
    private void superchunk$terrainSweepScope(ServerLevel level, Operation<Void> original) throws Throwable {
        BlockPos origin = this.loll01loloioooo00l00lo11;
        Scope scope = ProjectExplosivePerfDiagnostics.beginTerrainSweep(
                origin == null ? Integer.MIN_VALUE : origin.getX() >> 4,
                origin == null ? Integer.MIN_VALUE : origin.getZ() >> 4,
                this.ll0llol1li01lolio10011l0);
        Throwable failure = null;
        try {
            original.call(level);
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            ProjectExplosivePerfDiagnostics.finishSafely(scope, failure);
        }
    }

    @WrapMethod(method = "ll1ol00o111oioool0l011lo(Lnet/minecraft/server/level/ServerLevel;IIIIII"
            + "Llil1llilol11/lo1i1iloioil/lo1iioil1oo1i0io01l1i1lo$l0o001o0ol0ilooioooil11i;)I", require = 0)
    private static int superchunk$measureTerrainIteration(ServerLevel level, int offsetX, int offsetZ,
                                                           int originX, int originY, int originZ, int remaining,
                                                           @Coerce Object chunkCache,
                                                           Operation<Integer> original) throws Throwable {
        long start = ProjectExplosivePerfDiagnostics.startOperation(ScopeKind.TERRAIN_SWEEP);
        try {
            return original.call(level, offsetX, offsetZ, originX, originY, originZ, remaining, chunkCache);
        } finally {
            ProjectExplosivePerfDiagnostics.recordIteration(start, (originX + offsetX) >> 4,
                    (originZ + offsetZ) >> 4);
        }
    }

    @WrapOperation(
            method = "ll1ol00o111oioool0l011lo(Lnet/minecraft/server/level/ServerLevel;IIIIII"
                    + "Llil1llilol11/lo1i1iloioil/lo1iioil1oo1i0io01l1i1lo$l0o001o0ol0ilooioooil11i;)I",
            at = @At(value = "INVOKE", target = "Llil1llilol11/lo1i1iloioil/l1iillil01o0l00i0ill0l0o;"
                    + "ll1ol00o111oioool0l011lo(Lnet/minecraft/server/level/ServerLevel;IIIIII)V", remap = false),
            require = 0)
    private static void superchunk$countTicketRequest(ServerLevel level, int chunkX, int chunkZ,
                                                        int originX, int originY, int originZ, int radius,
                                                        Operation<Void> original) throws Throwable {
        ProjectExplosivePerfDiagnostics.recordTicketRequest(chunkX, chunkZ);
        original.call(level, chunkX, chunkZ, originX, originY, originZ, radius);
    }

    @WrapOperation(
            method = "*",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock("
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", remap = false),
            require = 0)
    private boolean superchunk$measureServerSetBlock(ServerLevel level, BlockPos pos, BlockState state, int flags,
                                                       Operation<Boolean> original) throws Throwable {
        long start = ProjectExplosivePerfDiagnostics.startBlockMutation();
        boolean changed = false;
        boolean failed = false;
        try {
            changed = original.call(level, pos, state, flags);
            return changed;
        } catch (Throwable thrown) {
            failed = true;
            throw thrown;
        } finally {
            ProjectExplosivePerfDiagnostics.recordSetBlock(start,
                    pos == null ? Integer.MIN_VALUE : pos.getX() >> 4,
                    pos == null ? Integer.MIN_VALUE : pos.getZ() >> 4, changed, failed);
        }
    }

    @WrapOperation(
            method = "*",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState("
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)"
                    + "Lnet/minecraft/world/level/block/state/BlockState;", remap = false),
            require = 0)
    private BlockState superchunk$measureChunkSetBlock(LevelChunk chunk, BlockPos pos, BlockState state,
                                                        boolean moved, Operation<BlockState> original) throws Throwable {
        long start = ProjectExplosivePerfDiagnostics.startBlockMutation();
        BlockState previous = null;
        boolean failed = false;
        try {
            previous = original.call(chunk, pos, state, moved);
            return previous;
        } catch (Throwable thrown) {
            failed = true;
            throw thrown;
        } finally {
            ProjectExplosivePerfDiagnostics.recordSetBlock(start,
                    pos == null ? Integer.MIN_VALUE : pos.getX() >> 4,
                    pos == null ? Integer.MIN_VALUE : pos.getZ() >> 4, previous != null, failed);
        }
    }
}
