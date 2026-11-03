package dev.superchunk;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Static baseline-target check for the two GPU authorization mixins; does not start Minecraft. */
public final class WorldgenMixinContractTest {
    private static final String CREATE_BIOMES_DESC = "(Lnet/minecraft/world/level/levelgen/RandomState;"
            + "Lnet/minecraft/world/level/levelgen/blending/Blender;"
            + "Lnet/minecraft/world/level/StructureManager;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;";
    private static final String DO_FILL_DESC = "(Lnet/minecraft/world/level/levelgen/blending/Blender;"
            + "Lnet/minecraft/world/level/StructureManager;"
            + "Lnet/minecraft/world/level/levelgen/RandomState;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;II)"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;";
    private static final String SUPPLY_ASYNC_DESC = "(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)"
            + "Ljava/util/concurrent/CompletableFuture;";

    private WorldgenMixinContractTest() { }

    public static void main(String[] args) throws Exception {
        ClassNode createMixin = readClass("dev/superchunk/mixin/MixinCreateBiomesGpuAuthorization.class");
        check(mixinTargets(createMixin, "Lnet/minecraft/world/level/chunk/ChunkGenerator;"),
                "createBiomes authorization mixin target changed");
        MethodNode createHandler = handler(createMixin, "superchunk$withBiomeTaskPermit");
        AnnotationNode wrapOperation = annotation(createHandler,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        check(annotationHasMethod(wrapOperation, "createBiomes" + CREATE_BIOMES_DESC),
                "createBiomes wrapper descriptor changed");
        check(mixinTargets(createMixin, "Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;"),
                "createBiomes authorization mixin must also target the NoiseBasedChunkGenerator override");
        check(intValue(wrapOperation, "require", -1) == 0,
                "createBiomes require policy changed; update fail-open/fallback analysis");
        AnnotationNode at = nestedAnnotation(wrapOperation, "at",
                "Lorg/spongepowered/asm/mixin/injection/At;");
        check(("Ljava/util/concurrent/CompletableFuture;supplyAsync" + SUPPLY_ASYNC_DESC)
                        .equals(stringValue(at, "target")),
                "createBiomes supplyAsync target changed");
        check(countInvocation(createHandler, "dev/superchunk/gpu/dfc/GpuTaskScopeCarrier", "wrap",
                        "(Ljava/util/function/Supplier;Ljava/util/function/Supplier;Ljava/util/function/Consumer;"
                                + "Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$WorldBucket;"
                                + "Ldev/superchunk/gpu/dfc/GpuTaskScopeCarrier$Lifecycle;"
                                + "Ljava/lang/Runnable;Ljava/lang/Runnable;Ljava/lang/Runnable;Ljava/lang/Runnable;)"
                                + "Ljava/util/function/Supplier;") == 1,
                "createBiomes async task no longer uses the tested context carrier");
        check(countInvocation(createHandler, "dev/superchunk/gpu/dfc/GpuTaskScopeCarrier", "submit",
                        "(Ldev/superchunk/gpu/dfc/GpuTaskScopeCarrier$AsyncSubmitter;"
                                + "Ljava/util/function/Supplier;Ldev/superchunk/gpu/dfc/GpuTaskScopeCarrier$Lifecycle;"
                                + "Ljava/lang/Runnable;Ljava/lang/Runnable;Ljava/lang/Runnable;Ljava/lang/Runnable;)"
                                + "Ljava/util/concurrent/CompletableFuture;") == 1,
                "createBiomes submission and cancellation observation changed");
        check(countInvocationAcrossClass(createMixin, "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;"
                                + "Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$WorldBucket;)V") >= 10,
                "createBiomes handler lost one or more execution-stage counters");

        ClassNode noiseMixin = readClass("dev/superchunk/mixin/MixinNoiseFillGpuAuthorization.class");
        check(mixinTargets(noiseMixin, "Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;"),
                "noise-fill authorization mixin target changed");
        MethodNode noiseHandler = handler(noiseMixin, "superchunk$withGpuPermit");
        AnnotationNode wrapMethod = annotation(noiseHandler,
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
        check(annotationHasMethod(wrapMethod, "doFill" + DO_FILL_DESC), "doFill wrapper descriptor changed");
        check(intValue(wrapMethod, "require", -1) == 0,
                "doFill require policy changed; update fail-open/fallback analysis");
        check(countInvocation(noiseHandler, "dev/superchunk/gpu/dfc/GpuWorldgenPolicy", "enterTask",
                        "(Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/chunk/ChunkAccess;"
                                + "Lnet/minecraft/world/level/levelgen/RandomState;)Ldev/superchunk/gpu/dfc/GpuWorldgenPolicy$Scope;") == 1,
                "noise fill handler no longer opens a task scope");
        check(countInvocation(noiseHandler, "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;"
                                + "Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$WorldBucket;)V") >= 4,
                "noise fill handler lost its entry/scope/body/restoration counters");

        ClassNode mixinPlugin = readClass("dev/superchunk/SuperChunkMixinPlugin.class");
        MethodNode onLoad = namedMethod(mixinPlugin, "onLoad");
        check(countInvocation(onLoad, "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;)V") == 1,
                "Mixin config load counter is missing");
        MethodNode preApply = handler(mixinPlugin, "preApply");
        MethodNode postApply = handler(mixinPlugin, "postApply");
        check(countInvocation(preApply, "dev/superchunk/SuperChunkMixinPlugin",
                        "recordGpuAuthorizationMixinLifecycle", "(Ljava/lang/String;Z)V") == 1
                        && countInvocation(postApply, "dev/superchunk/SuperChunkMixinPlugin",
                        "recordGpuAuthorizationMixinLifecycle", "(Ljava/lang/String;Z)V") == 1,
                "Mixin plugin no longer counts preApply/postApply for diagnostics");
        MethodNode mixinLifecycle = handler(mixinPlugin, "recordGpuAuthorizationMixinLifecycle");
        check(countInvocation(mixinLifecycle, "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;)V") == 1
                        && hasStringConstant(mixinLifecycle, ".MixinCreateBiomesGpuAuthorization")
                        && hasStringConstant(mixinLifecycle, ".MixinNoiseFillGpuAuthorization")
                        && hasStringConstant(mixinLifecycle, ".MixinNoiseChunkGeneratorCompactIds"),
                "Mixin lifecycle diagnostics do not cover the three critical mixins");

        ClassNode climateCache = readClass("dev/superchunk/gpu/dfc/BiomeClimateCache.class");
        check(countInvocation(namedMethod(climateCache, "beginChunk"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;)V") >= 12,
                "climate consumer lost reason counters at beginChunk");
        check(countInvocation(namedMethod(climateCache, "consumeBatchedClimate"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;)V") >= 5,
                "climate store lookup/hit/miss/use counters are incomplete");
        ClassNode densityDelegate = readClass("dev/superchunk/gpu/dfc/GpuDfcDelegate.class");
        check(countInvocation(namedMethod(densityDelegate, "fillInDiagnosticScope"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;)V") >= 8
                        && countInvocation(namedMethod(densityDelegate, "fill"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "openWorldScope",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$WorldBucket;)"
                                + "Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$DiagnosticScope;") == 1,
                "density route lost fallback/eligibility counters");
        ClassNode densityKernel = readClass("dev/superchunk/gpu/dfc/GpuDensityFunction.class");
        check(countInvocation(namedMethod(densityKernel, "fillLattice"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;)V") >= 2
                        && countInvocation(namedMethod(densityKernel, "fillArrays"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;)V") >= 2,
                "density kernel enqueue/readback counters are missing from a path");
        ClassNode fusedCache = readClass("dev/superchunk/gpu/dfc/ChunkGridCache.class");
        check(countInvocation(namedMethod(fusedCache, "computeFusedGroup"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;)V") >= 4,
                "fused density dispatch lost gate/failure/completion counters");
        ClassNode batchDispatcher = readClass("dev/superchunk/gpu/dfc/GpuBatchDispatcher.class");
        String workloadOwner = "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$BatchWorkload";
        check(countFieldRead(method(batchDispatcher, "compileClimate",
                                "(Ljava/util/List;)Ldev/superchunk/gpu/dfc/GpuBatchDispatcher;"),
                        workloadOwner, "CLIMATE") == 1
                        && countFieldRead(method(batchDispatcher, "compileClimateF32D",
                                "(Ljava/util/List;)Ldev/superchunk/gpu/dfc/GpuBatchDispatcher;"),
                        workloadOwner, "CLIMATE") == 1,
                "climate dispatcher compile paths lost their climate workload tag");
        check(countFieldRead(method(batchDispatcher, "compile",
                                "(Ljava/util/List;Ldev/superchunk/gpu/dfc/GpuFusedInterpolator;)"
                                        + "Ldev/superchunk/gpu/dfc/GpuBatchDispatcher;"),
                        workloadOwner, "DENSITY") == 1,
                "live fused dispatcher compile path lost its density workload tag");
        ClassNode climateCacheClass = readClass("dev/superchunk/gpu/dfc/BiomeClimateCache.class");
        check(countInvocationAcrossClass(climateCacheClass,
                        "dev/superchunk/gpu/dfc/GpuBatchDispatcher", "compileClimate",
                        "(Ljava/util/List;)Ldev/superchunk/gpu/dfc/GpuBatchDispatcher;") == 1
                        && countInvocationAcrossClass(climateCacheClass,
                        "dev/superchunk/gpu/dfc/GpuBatchDispatcher", "compileClimateF32D",
                        "(Ljava/util/List;)Ldev/superchunk/gpu/dfc/GpuBatchDispatcher;") == 1,
                "climate cache no longer uses the climate-tagged dispatcher entry points");
        check(countInvocation(namedMethod(batchDispatcher, "doEnqueue"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "recordBatchPrimaryEnqueued",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$BatchWorkload;"
                                + "Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$WorldBucket;)V") == 1
                        && countInvocation(namedMethod(batchDispatcher, "doEnqueue"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "recordBatchEnqueueFailure",
                        "(ZZLdev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$BatchWorkload;Z"
                                + "Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$WorldBucket;)V") == 1
                        && countInvocation(namedMethod(batchDispatcher, "doEnqueue"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics",
                        "recordClimateDispatchWithoutReadbackFailure",
                        "(ZLdev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$WorldBucket;)V") == 1
                        && countInvocationAcrossClass(batchDispatcher,
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "recordClimateChainEnqueued",
                        "(ZLdev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$WorldBucket;)V") == 1,
                "batched workload/readback failure attribution is missing");
        ClassNode densityPrefetch = readClass("dev/superchunk/gpu/dfc/GpuBatchPrefetch.class");
        check(countInvocationAcrossClass(densityPrefetch,
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "record",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$Event;"
                                + "Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$WorldBucket;)V") >= 5,
                "density prefetch capture/revalidation/store counters are incomplete");
        ClassNode inFlight = readClass("dev/superchunk/gpu/dfc/GpuBatchDispatcher$InFlight.class");
        check(countInvocation(namedMethod(inFlight, "recordReadbackResult"),
                        "dev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics", "recordBatchReadback",
                        "(Ldev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$BatchWorkload;"
                                + "ZZLdev/superchunk/gpu/dfc/GpuAuthorizationDiagnostics$WorldBucket;)V") == 1,
                "workload-specific batched readback completion attribution is missing");

        ClassNode chunkGenerator = readClass("net/minecraft/world/level/chunk/ChunkGenerator.class");
        MethodNode createBiomes = targetMethod(chunkGenerator, "createBiomes", CREATE_BIOMES_DESC);
        int supplyAsyncCalls = countInvocation(createBiomes, "java/util/concurrent/CompletableFuture",
                "supplyAsync", SUPPLY_ASYNC_DESC);
        check(supplyAsyncCalls == 1,
                "baseline ChunkGenerator.createBiomes supplyAsync invocation count was " + supplyAsyncCalls);

        ClassNode noiseGenerator = readClass("net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.class");
        targetMethod(noiseGenerator, "doFill", DO_FILL_DESC);
        MethodNode noiseCreateBiomes = targetMethod(noiseGenerator, "createBiomes", CREATE_BIOMES_DESC);
        check(countInvocation(noiseCreateBiomes, "java/util/concurrent/CompletableFuture",
                        "supplyAsync", SUPPLY_ASYNC_DESC) == 1,
                "NoiseBasedChunkGenerator.createBiomes override lost the wrapped supplyAsync call");

        ClassNode subCompiled = readClass(
                "dev/superchunk/com/ishland/c2me/opts/dfc/common/gen/SubCompiledDensityFunction.class");
        MethodNode taskPermit = method(subCompiled, "currentTaskPermitForDensity",
                "(Lnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)"
                        + "Ldev/superchunk/gpu/dfc/GpuWorldgenPolicy$Permit;");
        check(countInvocation(taskPermit, "dev/superchunk/gpu/dfc/GpuWorldgenPolicy",
                        "currentPermit", "()Ldev/superchunk/gpu/dfc/GpuWorldgenPolicy$Permit;") == 1,
                "density fill no longer reads the active task permit after validating its applier type");
        check(countTypeCheck(taskPermit,
                        "dev/superchunk/com/ishland/c2me/opts/dfc/common/vif/EachApplierVanillaInterface") == 1
                        && countTypeCheck(taskPermit, "net/minecraft/world/level/levelgen/NoiseChunk") == 1,
                "density task permit must be limited to C2ME's array applier or NoiseChunk context");
        check(countInvocation(namedMethod(subCompiled, "fillArray"),
                        "dev/superchunk/com/ishland/c2me/opts/dfc/common/gen/SubCompiledDensityFunction",
                        "currentTaskPermitForDensity",
                        "(Lnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)"
                                + "Ldev/superchunk/gpu/dfc/GpuWorldgenPolicy$Permit;") == 2,
                "both DFC fillArray paths must use the task-scoped permit selector");
        ClassNode serverLevel = readClass("net/minecraft/server/level/ServerLevel.class");
        targetMethod(serverLevel, "close", "()V");

        ClassNode retireMixin = readClass("dev/superchunk/mixin/MixinServerLevelGpuScopeRetirement.class");
        check(mixinTargets(retireMixin, "Lnet/minecraft/server/level/ServerLevel;"),
                "server-level retirement mixin target changed");
        MethodNode retireHandler = handler(retireMixin, "superchunk$retireGpuWorldgenScope");
        AnnotationNode inject = annotation(retireHandler, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        check(annotationHasMethod(inject, "close()V"), "server-level close hook target changed");
        check(intValue(inject, "require", -1) == 1,
                "server-level retirement hook must fail loudly if its baseline target disappears");
        check(countInvocation(retireHandler, "dev/superchunk/gpu/dfc/GpuWorldgenPolicy", "retireLevel",
                        "(Lnet/minecraft/server/level/ServerLevel;)V") == 1
                        && countInvocation(retireHandler, "dev/superchunk/gpu/dfc/ChunkGridCache",
                        "retireStaleBatcher", "()V") == 1,
                "server-level close hook no longer retires policy and batcher owner");

        ClassNode backend = readClass("dev/superchunk/gpu/OpenCLBackend.class");
        MethodNode shutdown = targetMethod(backend, "shutdown", "()V");
        int batcherShutdown = invocationIndex(shutdown, "dev/superchunk/gpu/dfc/GpuChunkBatcher",
                "shutdownBackend", "()Z");
        int fusedShutdown = invocationIndex(shutdown, "dev/superchunk/gpu/dfc/ChunkGridCache",
                "shutdownFused", "()V");
        int climateShutdown = invocationIndex(shutdown, "dev/superchunk/gpu/dfc/BiomeClimateCache",
                "shutdown", "()V");
        int storeClear = invocationIndex(shutdown, "dev/superchunk/gpu/dfc/ChunkGridCache",
                "clearBatchStore", "()V");
        check(batcherShutdown >= 0 && batcherShutdown < fusedShutdown
                        && fusedShutdown < climateShutdown && climateShutdown < storeClear,
                "OpenCL shutdown must drain batchers before closing their fused/climate resources and clear stores last");
        check(hasFailureVetoBefore(shutdown, batcherShutdown, fusedShutdown),
                "OpenCL shutdown must stop before native cleanup when batcher close fails");
        String mixinConfig = readResource("superchunk.mixins.json");
        check(mixinConfig.contains("\"MixinCreateBiomesGpuAuthorization\"")
                        && mixinConfig.contains("\"MixinNoiseFillGpuAuthorization\"")
                        && mixinConfig.contains("\"MixinServerLevelGpuScopeRetirement\""),
                "authorization mixins are missing from the active mixin configuration");
        System.out.println("WorldgenMixinContractTest: both createBiomes implementations, exact injection points, "
                + "DFC task-permit selection, plugin lifecycle and handler diagnostics present; require=0 hooks "
                + "remain runtime-unproven");
    }

    private static ClassNode readClass(String resource) throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Class resource missing from verification classpath: " + resource);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static String readResource(String resource) throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Resource missing from verification classpath: " + resource);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static boolean mixinTargets(ClassNode mixin, String expectedDescriptor) {
        String descriptor = "Lorg/spongepowered/asm/mixin/Mixin;";
        List<AnnotationNode> annotations = new ArrayList<>();
        if (mixin.visibleAnnotations != null) annotations.addAll(mixin.visibleAnnotations);
        if (mixin.invisibleAnnotations != null) annotations.addAll(mixin.invisibleAnnotations);
        for (AnnotationNode annotation : annotations) {
            if (!descriptor.equals(annotation.desc)) continue;
            Object values = value(annotation, "value");
            if (values instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof Type type && expectedDescriptor.equals(type.getDescriptor())) return true;
                }
            }
            return false;
        }
        return false;
    }

    private static MethodNode handler(ClassNode owner, String name) {
        List<MethodNode> found = owner.methods.stream().filter(m -> name.equals(m.name)).toList();
        check(found.size() == 1, "expected one handler " + name + " in " + owner.name);
        return found.get(0);
    }

    private static MethodNode namedMethod(ClassNode owner, String name) {
        List<MethodNode> found = owner.methods.stream().filter(m -> name.equals(m.name)).toList();
        check(!found.isEmpty(), "expected method " + name + " in " + owner.name);
        return found.get(0);
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        List<MethodNode> found = owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc)).toList();
        check(found.size() == 1, "expected method " + name + descriptor + " in " + owner.name);
        return found.get(0);
    }

    private static int countFieldRead(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && Opcodes.GETSTATIC == field.getOpcode()
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
        }
        return count;
    }

    private static int countTypeCheck(MethodNode method, String internalName) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && Opcodes.INSTANCEOF == type.getOpcode()
                    && internalName.equals(type.desc)) count++;
        }
        return count;
    }

    private static boolean hasStringConstant(MethodNode method, String expected) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LdcInsnNode constant && expected.equals(constant.cst)) return true;
        }
        return false;
    }

    private static MethodNode targetMethod(ClassNode owner, String name, String descriptor) {
        List<MethodNode> found = owner.methods.stream()
                .filter(m -> name.equals(m.name) && descriptor.equals(m.desc)).toList();
        check(found.size() == 1, "expected one baseline target " + owner.name + '#' + name + descriptor);
        return found.get(0);
    }

    private static int countInvocation(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static int countInvocationAcrossClass(ClassNode owner, String methodOwner, String name, String descriptor) {
        return owner.methods.stream().mapToInt(method ->
                countInvocation(method, methodOwner, name, descriptor)).sum();
    }

    private static int invocationIndex(MethodNode method, String owner, String name, String descriptor) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) return index;
            index++;
        }
        return -1;
    }

    private static boolean hasFailureVetoBefore(MethodNode method, int batcherShutdown, int fusedShutdown) {
        int index = 0;
        boolean conditionalSeen = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (index > batcherShutdown && index < fusedShutdown) {
                int opcode = insn.getOpcode();
                if (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) conditionalSeen = true;
                if (conditionalSeen && opcode == Opcodes.RETURN) return true;
            }
            index++;
        }
        return false;
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        for (AnnotationNode annotation : annotations(method)) {
            if (descriptor.equals(annotation.desc)) return annotation;
        }
        throw new AssertionError("expected annotation " + descriptor + " on " + method.name);
    }

    private static AnnotationNode nestedAnnotation(AnnotationNode parent, String key, String descriptor) {
        Object value = value(parent, key);
        if (value instanceof AnnotationNode nested) {
            check(descriptor.equals(nested.desc), "unexpected nested annotation type for " + key);
            return nested;
        }
        if (value instanceof List<?> nestedValues) {
            List<AnnotationNode> matching = nestedValues.stream()
                    .filter(AnnotationNode.class::isInstance)
                    .map(AnnotationNode.class::cast)
                    .filter(nested -> descriptor.equals(nested.desc))
                    .toList();
            check(matching.size() == 1, "expected one nested " + descriptor + " in " + key);
            return matching.get(0);
        }
        throw new AssertionError("missing nested annotation " + key);
    }

    private static boolean annotationHasMethod(AnnotationNode annotation, String expected) {
        Object value = value(annotation, "method");
        if (value instanceof String string) return expected.equals(string);
        if (value instanceof List<?> values) return values.contains(expected);
        return false;
    }

    private static int intValue(AnnotationNode annotation, String key, int fallback) {
        Object value = value(annotation, key);
        return value instanceof Integer integer ? integer : fallback;
    }

    private static String stringValue(AnnotationNode annotation, String key) {
        Object value = value(annotation, key);
        return value instanceof String string ? string : null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
