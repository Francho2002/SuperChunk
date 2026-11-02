# CBC Neo Warfare compatibility

## Scope and status

This change targets CBC Neo Warfare 0.0.6v for Minecraft 1.21.1 on NeoForge, with SuperChunk 0.6.0's NeoForge 1.21.1 source. It addresses only the documented `ChunkMap.getPlayers(ChunkPos, boolean)` mixin conflict. It does not change CBC, CMI, Create, or gameplay behavior.

The source correction and static checks are complete. The server has not been launched with these sources and CBC, so runtime compatibility remains unverified.

## Cause

SuperChunk's `vmp.mixins.json` sets `mixinPriority` to 1050. `MixinThreadedAnvilChunkStorage` used that default for its `@Overwrite` of `ChunkMap.getPlayers(ChunkPos, boolean)`. CBC Neo Warfare 0.0.6v's `cbcmodernwarfare-common.mixins.json` declares `camera.ChunkMapMixin`; that mixin has no explicit priority, so it uses Mixin's default 1000. Its `@ModifyExpressionValue` targets the `ServerPlayer.getLastSectionPos()` invocation in `getPlayers`.

Mixin applies lower-priority mixins first. An injector must have priority higher than the priority recorded on a method it targets; CBC at 1000 therefore cannot inject into the 1050 overwrite. Mixin's source documents per-mixin annotation priority as an override of the config's default and documents the target-priority check: [@Mixin priority](https://github.com/SpongePowered/Mixin/blob/master/src/main/java/org/spongepowered/asm/mixin/Mixin.java), [injection-point priority check](https://github.com/SpongePowered/Mixin/blob/master/src/main/java/org/spongepowered/asm/mixin/injection/InjectionPoint.java).

## Correction

The optimized `getPlayers` overwrite now lives in `MixinChunkMapGetPlayers`, annotated with `@Mixin(value = ChunkMap.class, priority = 900)`, and is registered in `vmp.mixins.json`. The config's 1050 base priority remains unchanged. This lets CBC's default-priority 1000 injector modify the optimized method body, while the other VMP mixins retain their previous ordering.

The method preserves the existing VMP path: it queries `AreaPlayerChunkWatchingManager` for candidate players, checks the Chebyshev distance against `serverViewDistance`, excludes pending chunk sends, and applies the watch-distance-edge condition. It obtains the existing manager through `TACSExtension`; it adds no CBC reference or client dependency. With CBC absent, this same VMP overwrite remains active.

## Patch provenance

The full-history clone contains no reachable NeoForge 1.21.1 commit for the withdrawn 2026-10-26 patch, and `git fsck --no-reflogs --unreachable` found no unreachable objects. The analogous change is present in upstream commit [`48162b71c668e00918b9fc81cba35b9cfa4ed103`](https://github.com/TheProKnightGamer/SuperChunk/commit/48162b71c668e00918b9fc81cba35b9cfa4ed103) on the `forge-1.20.1` line, dated 2026-10-30. It moves the overwrite into a dedicated priority-900 mixin. That branch's method body differs from 1.21.1 (it lacks the newer `chunkSender.isPending` check), so its implementation was used as design evidence, not copied verbatim.

The current NeoForge implementation is a minimal adaptation of that split to the existing 1.21.1 VMP algorithm. SuperChunk's existing LGPL-3.0 notices and bundled-source attributions are unchanged.

## Files changed

- `src/main/java/dev/superchunk/com/ishland/vmp/mixins/playerwatching/MixinThreadedAnvilChunkStorage.java`: removes only the `getPlayers` overwrite and its now-unused imports.
- `src/main/java/dev/superchunk/com/ishland/vmp/mixins/playerwatching/MixinChunkMapGetPlayers.java`: adds the same optimized overwrite at priority 900.
- `src/main/resources/vmp.mixins.json`: registers the dedicated mixin; keeps `mixinPriority` at 1050.
- `compatibility.txt`: records the static patch and pending runtime validation.

## Verification and limitations

- CBC jar metadata and bytecode were inspected without loading the game: `cbcmodernwarfare-common.mixins.json` registers `camera.ChunkMapMixin`; its `getPlayers` injector targets `ServerPlayer.getLastSectionPos()` and has `require = 0`; the mixin class has no explicit priority.
- Static inspection confirmed the compiled `@Mixin` annotation records priority 900, `vmp.mixins.json` retains base priority 1050 and registers the new class, and the old VMP mixin no longer declares `getPlayers`. The core JAR contains both the updated config and the class; the distributable shell contains that core JAR.
- Static source review found no other SuperChunk overwrite or injector for `ChunkMap.getPlayers`. C2ME's no-tick-distance mixin shadows the same method signature but does not overwrite or inject into it.
- `gradlew clean build` completed with Microsoft OpenJDK 21.0.11 and Gradle 9.2.0: `BUILD SUCCESSFUL`, 26 tasks (25 executed). The build emitted seven existing deprecation/removal warnings in other source files.
- The build's 15 standalone regression tasks passed, including `mixinTargetScanRegressionTest`; they do not launch Minecraft.
- No Minecraft client, server, GameTest, or playable test was run. Runtime application of both mixins and server startup remain pending.
