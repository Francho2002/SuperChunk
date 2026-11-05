package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.superchunk.diag.projectexplosive.ProjectExplosiveNuclearTerrainScale;
import dev.superchunk.diag.projectexplosive.ProjectExplosiveTerrainScaleState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opt-in reduction of the direct nuclear bomb's outer terrain sweep only. The raw current and
 * previous wave radii remain intact for visual propagation, while the independently stored terrain
 * target is scaled before the sweep consumes it. NBT stores the unscaled target so OFF restores it.
 */
@Pseudo
@Mixin(targets = "lil1llilol11.lo1i1iloioil.lo1iioil1oo1i0io01l1i1lo", remap = false)
public abstract class MixinProjectExplosiveNuclearTerrainScale implements ProjectExplosiveTerrainScaleState {
    @Unique
    private static final String superchunk$PERSISTED_UNSCALED_TARGET = "FastChunkNuclearTerrainTargetUnscaled";

    @Unique
    private double superchunk$originalTerrainTarget = Double.NaN;

    @Unique
    private double superchunk$effectiveTerrainTarget = Double.NaN;

    @Shadow(remap = false)
    private double ll0llol1li01lolio10011l0;

    @Override
    public double superchunk$originalTerrainTarget() {
        return this.superchunk$originalTerrainTarget;
    }

    @Override
    public double superchunk$effectiveTerrainTarget() {
        return this.superchunk$effectiveTerrainTarget;
    }

    @WrapOperation(
            method = "l11oo1ilili0l0lloii0oili()V",
            at = @At(value = "FIELD",
                    target = "Llil1llilol11/lo1i1iloioil/lo1iioil1oo1i0io01l1i1lo;"
                            + "ll0llol1li01lolio10011l0:D",
                    opcode = Opcodes.PUTFIELD,
                    remap = false),
            require = 0)
    private void superchunk$scaleNuclearTerrainTarget(@Coerce Object receiver, double originalRadius,
                                                       Operation<Void> original) throws Throwable {
        double effectiveRadius = originalRadius;
        if (receiver == (Object) this && receiver instanceof Entity entity
                && ProjectExplosiveNuclearTerrainScale.enabled() && Double.isFinite(originalRadius)
                && originalRadius >= 0.0) {
            try {
                if (superchunk$isSelected(entity)) {
                    effectiveRadius = ProjectExplosiveNuclearTerrainScale.scaleIfSelected(
                            ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE, originalRadius);
                    this.superchunk$originalTerrainTarget = originalRadius;
                    this.superchunk$effectiveTerrainTarget = effectiveRadius;
                }
            } catch (RuntimeException ignored) {
                // If optional registry identity cannot be read, preserve PE's original target.
            }
        }
        original.call(receiver, effectiveRadius);
    }

    @ModifyExpressionValue(
            method = "l11oo1ilili0l0lloii0oili()V",
            at = @At(value = "FIELD",
                    target = "Llil1llilol11/lo1i1iloioil/lo1iioil1oo1i0io01l1i1lo;"
                            + "ll0llol1li01lolio10011l0:D",
                    opcode = Opcodes.GETFIELD,
                    remap = false),
            require = 0)
    private double superchunk$preserveVisualWaveStartRadius(double effectivePreviousRadius) {
        try {
            if (ProjectExplosiveNuclearTerrainScale.enabled()
                    && (Object) this instanceof Entity entity && superchunk$isSelected(entity)
                    && Double.isFinite(this.superchunk$originalTerrainTarget)) {
                return this.superchunk$originalTerrainTarget;
            }
        } catch (RuntimeException ignored) {
            // If optional registry identity cannot be read, preserve the original visual value.
        }
        return effectivePreviousRadius;
    }

    @Inject(method = "addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"),
            require = 0, remap = false)
    private void superchunk$saveTerrainTargetForCompatibility(CompoundTag tag, CallbackInfo callback) {
        if (!ProjectExplosiveNuclearTerrainScale.enabled() || !Double.isFinite(this.superchunk$originalTerrainTarget)
                || this.superchunk$originalTerrainTarget < 0.0 || !superchunk$isSelected((Entity) (Object) this)) return;
        // Saving the unscaled value lets a later startup with this feature OFF resume the original sweep.
        tag.putDouble("TerrainTarget", ProjectExplosiveNuclearTerrainScale.normalizeTargetForSave(
                ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE,
                this.superchunk$originalTerrainTarget, this.ll0llol1li01lolio10011l0));
        tag.putBoolean(superchunk$PERSISTED_UNSCALED_TARGET, true);
    }

    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"),
            require = 0, remap = false)
    private void superchunk$restoreTerrainTargetOnLoad(CompoundTag tag, CallbackInfo callback) {
        if (!ProjectExplosiveNuclearTerrainScale.enabled() || !superchunk$isSelected((Entity) (Object) this)) return;
        if (tag.getBoolean(superchunk$PERSISTED_UNSCALED_TARGET)) {
            double originalRadius = tag.getDouble("TerrainTarget");
            if (!Double.isFinite(originalRadius) || originalRadius < 0.0) return;
            double effectiveRadius = ProjectExplosiveNuclearTerrainScale.restoreTargetOnLoad(
                    true, ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE,
                    originalRadius, this.ll0llol1li01lolio10011l0);
            this.superchunk$originalTerrainTarget = originalRadius;
            this.superchunk$effectiveTerrainTarget = effectiveRadius;
            this.ll0llol1li01lolio10011l0 = effectiveRadius;
        } else if (Double.isFinite(this.ll0llol1li01lolio10011l0)
                && this.ll0llol1li01lolio10011l0 >= 0.0) {
            // A pre-feature save has an unmarked PE target. Keep it as-is until the next wave update;
            // this also avoids accidentally scaling an entity whose save hook was unavailable.
            this.superchunk$originalTerrainTarget = this.ll0llol1li01lolio10011l0;
            this.superchunk$effectiveTerrainTarget = this.ll0llol1li01lolio10011l0;
        }
    }

    @Unique
    private static boolean superchunk$isSelected(Entity entity) {
        try {
            ResourceLocation entityTypeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return entityTypeKey != null
                    && ProjectExplosiveNuclearTerrainScale.NUCLEAR_BOMB_TYPE.equals(entityTypeKey.toString());
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
