package dev.superchunk.com.ishland.vmp.mixins.playerwatching;

import com.google.common.collect.ImmutableList;
import dev.superchunk.com.ishland.vmp.common.chunkwatching.AreaPlayerChunkWatchingManager;
import dev.superchunk.com.ishland.vmp.common.playerwatching.TACSExtension;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * VMP's optimized {@link ChunkMap#getPlayers(ChunkPos, boolean)} overwrite, isolated so its
 * priority can be lower than the rest of the VMP mixins.
 *
 * <p>The VMP mixin config keeps its base priority at 1050. CBC Neo Warfare 0.0.6v's camera mixin
 * runs at the default priority 1000 and modifies the {@code getLastSectionPos()} invocation in
 * this method. Keeping this overwrite at 900 lets that injector apply to the optimized body while
 * the remaining VMP mixins retain their existing priority.
 */
@Mixin(value = ChunkMap.class, priority = 900)
public abstract class MixinChunkMapGetPlayers {

    @Shadow private int serverViewDistance;

    /**
     * @author ishland
     * @reason use array for iteration &amp; use squares as cylinders are expensive
     */
    @Overwrite
    public List<ServerPlayer> getPlayers(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge) {
        final AreaPlayerChunkWatchingManager watchingManager =
                ((TACSExtension) (Object) this).getAreaPlayerChunkWatchingManager();

        Object[] set = watchingManager.getPlayersWatchingChunkArray(chunkPos.toLong());
        ImmutableList.Builder<ServerPlayer> builder = ImmutableList.builder();

        for (Object __player : set) {
            if (__player instanceof ServerPlayer serverPlayer) {
                SectionPos watchedPos = serverPlayer.getLastSectionPos();
                int chebyshevDistance = Math.max(Math.abs(watchedPos.x() - chunkPos.x), Math.abs(watchedPos.z() - chunkPos.z));
                if (chebyshevDistance > this.serverViewDistance) {
                    continue;
                }
                if (!serverPlayer.connection.chunkSender.isPending(chunkPos.toLong()) &&
                    (!onlyOnWatchDistanceEdge || chebyshevDistance == this.serverViewDistance)) {
                    builder.add(serverPlayer);
                }
            }
        }

        return builder.build();
    }
}
