package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics;
import dev.superchunk.diag.projectexplosive.ProjectExplosivePerfDiagnostics.Scope;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Independent scope around PE's radial destruction helper (its own sync chunk cache is measured separately). */
@Pseudo
@Mixin(targets = "lil1llilol11.lo1i1iloioil.l1o010il000l0o100li0oili", remap = false)
public abstract class MixinProjectExplosiveRadialDestruction {

    @WrapMethod(method = "ll1ol00o111oioool0l011lo(Lnet/minecraft/server/level/ServerLevel;DDDD)V", require = 0)
    private static void superchunk$radialDestructionScope(ServerLevel level, double centerX, double centerY,
                                                            double centerZ, double radiusBlocks,
                                                            Operation<Void> original) throws Throwable {
        Scope scope = ProjectExplosivePerfDiagnostics.beginRadialDestruction(centerX, centerZ, radiusBlocks);
        Throwable failure = null;
        try {
            original.call(level, centerX, centerY, centerZ, radiusBlocks);
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            ProjectExplosivePerfDiagnostics.finishSafely(scope, failure);
        }
    }

    @WrapOperation(
            method = "ll1ol00o111oioool0l011lo(Lnet/minecraft/server/level/ServerLevel;DDDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock("
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", remap = false),
            require = 0)
    private static boolean superchunk$measureRadialSetBlock(ServerLevel level, BlockPos pos, BlockState state,
                                                               int flags, Operation<Boolean> original) throws Throwable {
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
}
