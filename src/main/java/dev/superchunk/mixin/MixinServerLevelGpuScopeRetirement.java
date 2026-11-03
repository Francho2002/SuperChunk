package dev.superchunk.mixin;

import dev.superchunk.gpu.dfc.ChunkGridCache;
import dev.superchunk.gpu.dfc.GpuWorldgenPolicy;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retires world-scoped GPU permissions and cross-chunk batching as a ServerLevel closes. */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevelGpuScopeRetirement {

    @Inject(method = "close()V", at = @At("HEAD"), require = 1)
    private void superchunk$retireGpuWorldgenScope(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        GpuWorldgenPolicy.retireLevel(level);
        ChunkGridCache.retireStaleBatcher();
    }
}
