package dev.superchunk.diag.projectexplosive;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** Offline ASM verifier for PE 0.7.2-beta and the optional diagnostic Mixin contracts. */
public final class ProjectExplosiveMixinContractTest {
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String LEVEL_CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
    private static final String TERRAIN = "lil1llilol11/lo1i1iloioil/lo1iioil1oo1i0io01l1i1lo";
    private static final String TERRAIN_CACHE = TERRAIN + "$l0o001o0ol0ilooioooil11i";
    private static final String RADIAL = "lil1llilol11/lo1i1iloioil/l1o010il000l0o100li0oili";
    private static final String RADIAL_CACHE = RADIAL + "$ll1ol00o111oioool0l011lo";
    private static final String METHOD = "ll1ol00o111oioool0l011lo";
    private static final String TERRAIN_SWEEP_DESC = "(L" + SERVER_LEVEL + ";)V";
    private static final String TERRAIN_WORK_DESC = "(L" + SERVER_LEVEL + ";IIIIII" + "L" + TERRAIN_CACHE + ";)I";
    private static final String TERRAIN_CACHE_DESC = "(L" + SERVER_LEVEL + ";II)L" + LEVEL_CHUNK + ";";
    private static final String RADIAL_DESC = "(L" + SERVER_LEVEL + ";DDDD)V";
    private static final String RADIAL_CACHE_DESC = "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String GET_CHUNK_NOW_DESC = "(II)L" + LEVEL_CHUNK + ";";
    private static final String GET_CHUNK_DESC = "(II)L" + LEVEL_CHUNK + ";";
    private static final String SET_BLOCK_DESC = "(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z";
    private static final String SET_CHUNK_BLOCK_DESC = "(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Z)"
            + "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final Map<String, String> CLASS_HASHES = Map.of(
            TERRAIN, "40E16B01A05F1D5BFAB680F54A317C9301A0CFA3F1344DCDABB0A79816FFB90F",
            TERRAIN_CACHE, "2FE68D08F9B327798445DB91B84195E8DD02863EB783EA1CBAC1D3B4E9CB39F0",
            RADIAL, "4212D88EF69E9AE1566AA1A9086091334F81FAB1EC5CED78B90D2EEDA2D8119E",
            RADIAL_CACHE, "5282BE095320C4DE092ACF45968D2B3D4EE6EC0D5A7F2DA5A49C36604035D363");

    private ProjectExplosiveMixinContractTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the installed ProjectExplosive-0.7.2-beta.jar path");
        Path jarPath = Path.of(args[0]);
        check(Files.isRegularFile(jarPath), "PE artifact exists");
        try (InputStream input = Files.newInputStream(jarPath)) {
            check(hash(input).equals("3CBF5A76D8B6123A489DF54F0165C31DF6C5F6993DD07E9506232FCB3F3CEBB2"),
                    "PE JAR SHA-256 matches the approved artifact");
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Map<String, ClassNode> classes = readClasses(jar);
            check(classes.size() == CLASS_HASHES.size(), "all four target classes are available");
            for (Map.Entry<String, String> entry : CLASS_HASHES.entrySet()) {
                check(hash(jar.getInputStream(jar.getEntry(entry.getKey() + ".class"))).equals(entry.getValue()),
                        "class hash matches for " + entry.getKey());
            }
            verifyTerrainSweep(classes);
            verifyTerrainCache(classes);
            verifyRadial(classes);
            verifyRadialCache(classes);
        }
        verifyMixins();
        verifyMixinConfig();
        System.out.println("ProjectExplosiveMixinContractTest passed against " + jarPath);
    }

    private static Map<String, ClassNode> readClasses(JarFile jar) throws Exception {
        java.util.HashMap<String, ClassNode> classes = new java.util.HashMap<>();
        for (String name : CLASS_HASHES.keySet()) {
            ClassNode node = new ClassNode();
            try (InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
                new ClassReader(input).accept(node, 0);
            }
            classes.put(name, node);
        }
        return classes;
    }

    private static void verifyTerrainSweep(Map<String, ClassNode> classes) {
        MethodNode sweep = method(classes.get(TERRAIN), "l11oo1ilili0l0lloii0oili", TERRAIN_SWEEP_DESC);
        check(callCount(sweep, "java/lang/System", "nanoTime", "()J") == 2,
                "sweep retains its before/loop time checks");
        check(sweep.instructions.iterator().hasNext()
                        && containsLongConstant(sweep, 18_000_000L),
                "sweep retains its nominal 18 ms deadline");
        check(field(classes.get(TERRAIN), "loll01loloioooo00l00lo11", "Lnet/minecraft/core/BlockPos;")
                        && field(classes.get(TERRAIN), "ll0llol1li01lolio10011l0", "D"),
                "target origin and radius fields match the injected shadows");
        check(callCount(sweep, TERRAIN, METHOD, TERRAIN_WORK_DESC) == 1,
                "terrain sweep calls the measured terrain work method exactly once per loop body");
        MethodNode work = method(classes.get(TERRAIN), METHOD, TERRAIN_WORK_DESC);
        check(callCount(work, "lil1llilol11/lo1i1iloioil/l1iillil01o0l00i0ill0l0o", METHOD,
                        "(L" + SERVER_LEVEL + ";IIIIII)V") == 1,
                "terrain work call site requests ticket work through the audited PE method");
        check(callCount(work, LEVEL_CHUNK, "setBlockState", SET_CHUNK_BLOCK_DESC) == 0,
                "terrain work does not claim a direct chunk mutation absent from this method");
        check(callCount(classes.get(TERRAIN).methods, SERVER_LEVEL, "setBlock", SET_BLOCK_DESC) > 0,
                "terrain entity class contains instrumentable direct block mutation call sites");
        check(callCount(classes.get(TERRAIN).methods, LEVEL_CHUNK, "setBlockState", SET_CHUNK_BLOCK_DESC) > 0,
                "terrain entity class contains instrumentable direct LevelChunk mutations");
    }

    private static void verifyTerrainCache(Map<String, ClassNode> classes) {
        MethodNode cache = method(classes.get(TERRAIN_CACHE), METHOD, TERRAIN_CACHE_DESC);
        check(callCount(cache, "net/minecraft/server/level/ServerChunkCache", "getChunkNow", GET_CHUNK_NOW_DESC) == 1,
                "terrain cache calls nonblocking getChunkNow at the verified call site");
    }

    private static void verifyRadial(Map<String, ClassNode> classes) {
        MethodNode radial = method(classes.get(RADIAL), METHOD, RADIAL_DESC);
        check(callCount(radial, SERVER_LEVEL, "setBlock", SET_BLOCK_DESC) == 2,
                "radial method has exactly two direct ServerLevel.setBlock call sites");
    }

    private static void verifyRadialCache(Map<String, ClassNode> classes) {
        MethodNode cache = method(classes.get(RADIAL_CACHE), METHOD, RADIAL_CACHE_DESC);
        check(callCount(cache, SERVER_LEVEL, "getChunk", GET_CHUNK_DESC) == 1,
                "radial cache contains one synchronous ServerLevel.getChunk call site");
    }

    private static void verifyMixins() throws Exception {
        checkMixin("dev.superchunk.mixin.MixinProjectExplosiveTerrainSweep", TERRAIN,
                List.of("superchunk$terrainSweepScope", "superchunk$measureTerrainIteration",
                        "superchunk$countTicketRequest", "superchunk$measureServerSetBlock",
                        "superchunk$measureChunkSetBlock"));
        checkMixin("dev.superchunk.mixin.MixinProjectExplosiveTerrainChunkCache", TERRAIN_CACHE,
                List.of("superchunk$measureGetChunkNow"));
        checkMixin("dev.superchunk.mixin.MixinProjectExplosiveRadialDestruction", RADIAL,
                List.of("superchunk$radialDestructionScope", "superchunk$measureRadialSetBlock"));
        checkMixin("dev.superchunk.mixin.MixinProjectExplosiveRadialChunkCache", RADIAL_CACHE,
                List.of("superchunk$measureSynchronousGetChunk"));
        verifyDiagnosticHandlersDoNotLoadChunks();
    }

    private static void verifyDiagnosticHandlersDoNotLoadChunks() throws Exception {
        for (String name : List.of("MixinProjectExplosiveTerrainSweep", "MixinProjectExplosiveTerrainChunkCache",
                "MixinProjectExplosiveRadialDestruction", "MixinProjectExplosiveRadialChunkCache")) {
            ClassNode mixin = readClasspathClass("dev/superchunk/mixin/" + name + ".class");
            for (MethodNode method : mixin.methods) {
                if (!method.name.startsWith("superchunk$")) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    check(!(call.name.equals("getChunk") || call.name.equals("getChunkNow")
                                    || call.name.equals("addRegionTicket")
                                    || call.owner.startsWith("lil1llilol11/lo1i1iloioil/l1iillil")),
                            name + "." + method.name + " introduces no chunk lookup or ticket request");
                }
            }
        }
    }

    private static void checkMixin(String className, String target, List<String> handlerNames) throws Exception {
        ClassNode mixin = readClasspathClass(className.replace('.', '/') + ".class");
        check(hasAnnotation(allAnnotations(mixin.visibleAnnotations, mixin.invisibleAnnotations), "Lorg/spongepowered/asm/mixin/Pseudo;"),
                className + " is optional via @Pseudo");
        AnnotationNode mixinAnnotation = annotation(allAnnotations(mixin.visibleAnnotations, mixin.invisibleAnnotations),
                "Lorg/spongepowered/asm/mixin/Mixin;");
        check(mixinAnnotation != null && stringListValue(mixinAnnotation, "targets").contains(target.replace('/', '.')),
                className + " targets the exact class");
        for (String handlerName : handlerNames) {
            MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals(handlerName)).findFirst().orElseThrow();
            AnnotationNode wrapped = annotation(allAnnotations(handler.visibleAnnotations, handler.invisibleAnnotations),
                    "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
            boolean isWrapMethod = wrapped != null;
            if (wrapped == null) wrapped = annotation(allAnnotations(handler.visibleAnnotations, handler.invisibleAnnotations),
                    "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
            check(wrapped != null && intValue(wrapped, "require", -1) == 0,
                    className + "." + handlerName + " is optional and does not crash on a missing call site");
            String expectedSelector = expectedSelector(handlerName);
            if (expectedSelector != null) {
                String actualSelector = isWrapMethod ? stringValue(wrapped, "method") : stringValue(wrapped, "method");
                check(expectedSelector.equals(actualSelector), className + "." + handlerName
                        + " targets exact method selector " + expectedSelector);
            }
            String expectedCall = expectedCall(handlerName);
            if (expectedCall != null) {
                AnnotationNode at = nestedAnnotation(wrapped, "at", "Lorg/spongepowered/asm/mixin/injection/At;");
                check(expectedCall.equals(stringValue(at, "target")), className + "." + handlerName
                        + " wraps exact bytecode call " + expectedCall + "; got " + stringValue(at, "target"));
            }
        }
    }

    private static void verifyMixinConfig() throws Exception {
        try (InputStream input = ProjectExplosiveMixinContractTest.class.getClassLoader()
                .getResourceAsStream("superchunk.mixins.json")) {
            check(input != null, "mixin config is present");
            String config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String name : List.of("MixinProjectExplosiveTerrainSweep", "MixinProjectExplosiveTerrainChunkCache",
                    "MixinProjectExplosiveRadialDestruction", "MixinProjectExplosiveRadialChunkCache")) {
                check(config.contains("\"" + name + "\""), name + " is listed in the mixin config");
            }
            check(config.contains("\"defaultRequire\": 1"), "global required-mixin policy remains unchanged");
        }
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError("Missing method " + node.name + "." + name + descriptor));
    }

    private static int callCount(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor)) count++;
        }
        return count;
    }

    private static int callCount(List<MethodNode> methods, String owner, String name, String descriptor) {
        return methods.stream().mapToInt(method -> callCount(method, owner, name, descriptor)).sum();
    }

    private static boolean containsLongConstant(MethodNode method, long value) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Long constant && constant == value) return true;
        }
        return false;
    }

    private static boolean field(ClassNode node, String name, String descriptor) {
        return node.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(descriptor));
    }

    private static String expectedSelector(String handler) {
        return switch (handler) {
            case "superchunk$terrainSweepScope" -> "l11oo1ilili0l0lloii0oili" + TERRAIN_SWEEP_DESC;
            case "superchunk$measureTerrainIteration", "superchunk$countTicketRequest" -> METHOD + TERRAIN_WORK_DESC;
            case "superchunk$measureGetChunkNow" -> METHOD + TERRAIN_CACHE_DESC;
            case "superchunk$radialDestructionScope", "superchunk$measureRadialSetBlock" -> METHOD + RADIAL_DESC;
            case "superchunk$measureSynchronousGetChunk" -> METHOD + RADIAL_CACHE_DESC;
            case "superchunk$measureServerSetBlock", "superchunk$measureChunkSetBlock" -> "*";
            default -> throw new AssertionError("No selector fixture for " + handler);
        };
    }

    private static String expectedCall(String handler) {
        return switch (handler) {
            case "superchunk$countTicketRequest" ->
                    "Llil1llilol11/lo1i1iloioil/l1iillil01o0l00i0ill0l0o;" + METHOD
                            + "(L" + SERVER_LEVEL + ";IIIIII)V";
            case "superchunk$measureServerSetBlock", "superchunk$measureRadialSetBlock" ->
                    "L" + SERVER_LEVEL + ";setBlock" + SET_BLOCK_DESC;
            case "superchunk$measureChunkSetBlock" -> "L" + LEVEL_CHUNK + ";setBlockState" + SET_CHUNK_BLOCK_DESC;
            case "superchunk$measureGetChunkNow" ->
                    "Lnet/minecraft/server/level/ServerChunkCache;getChunkNow" + GET_CHUNK_NOW_DESC;
            case "superchunk$measureSynchronousGetChunk" ->
                    "L" + SERVER_LEVEL + ";getChunk" + GET_CHUNK_DESC;
            default -> null;
        };
    }

    private static AnnotationNode nestedAnnotation(AnnotationNode parent, String key, String descriptor) {
        if (parent == null || parent.values == null) return null;
        for (int i = 0; i < parent.values.size(); i += 2) {
            if (!parent.values.get(i).equals(key)) continue;
            Object value = parent.values.get(i + 1);
            if (value instanceof AnnotationNode annotation) {
                return annotation.desc.equals(descriptor) ? annotation : null;
            }
            // Annotation values such as MixinExtras' @WrapOperation.at are encoded as arrays,
            // which ASM exposes as List<AnnotationNode>, even when the source has one @At.
            if (value instanceof List<?> annotations) {
                for (Object entry : annotations) {
                    if (entry instanceof AnnotationNode annotation && annotation.desc.equals(descriptor)) {
                        return annotation;
                    }
                }
            }
            return null;
        }
        return null;
    }

    private static String stringValue(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (!annotation.values.get(i).equals(key)) continue;
            Object value = annotation.values.get(i + 1);
            if (value instanceof String text) return text;
            if (value instanceof List<?> list && list.size() == 1 && list.get(0) instanceof String text) return text;
            return null;
        }
        return null;
    }

    private static ClassNode readClasspathClass(String path) throws Exception {
        try (InputStream input = ProjectExplosiveMixinContractTest.class.getClassLoader().getResourceAsStream(path)) {
            check(input != null, "compiled mixin class exists: " + path);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) return null;
        return annotations.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static List<AnnotationNode> allAnnotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        java.util.ArrayList<AnnotationNode> result = new java.util.ArrayList<>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String descriptor) {
        return annotation(annotations, descriptor) != null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return List.of();
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return (List<String>) annotation.values.get(i + 1);
        }
        return List.of();
    }

    private static int intValue(AnnotationNode annotation, String key, int fallback) {
        if (annotation.values == null) return fallback;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return ((Number) annotation.values.get(i + 1)).intValue();
        }
        return fallback;
    }

    private static String hash(InputStream input) throws Exception {
        try (input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
