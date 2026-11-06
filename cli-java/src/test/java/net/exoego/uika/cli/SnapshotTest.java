package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SnapshotTest {
    private static final int PROCESS_TIMEOUT_SECONDS = 30;
    private static final int CLASS_HEADER_BYTES = 4 * Short.BYTES;
    private static final int SUPER_CLASS_OFFSET = 2 * Short.BYTES;
    private static final int FIELD_HEADER_BYTES = 4 * Short.BYTES;
    private static final int ZIP_LOCAL_HEADER_BYTES = 30;
    private static final int ZIP_NAME_LENGTH_OFFSET = 26;
    private static final int ZIP_EXTRA_LENGTH_OFFSET = 28;

    @TempDir
    Path dir;

    private Path compile(String name, Map<String, String> sources, Path... dependencies) throws Exception {
        return compile(name, sources, List.of("--release", "17"), dependencies);
    }

    private Path compile(String name, Map<String, String> sources, List<String> options, Path... dependencies) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "needs a JDK compiler");
        Path root = dir.resolve(name);
        Path classes = Files.createDirectories(root.resolve("classes"));
        List<String> args = new ArrayList<>(options);
        args.addAll(List.of("-d", classes.toString()));
        if (dependencies.length > 0) {
            args.addAll(List.of("-classpath", String.join(File.pathSeparator, Arrays.stream(dependencies).map(Path::toString).toList())));
        }
        for (var source : sources.entrySet()) {
            Path file = root.resolve("src/" + source.getKey() + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        var errors = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, null, errors, args.toArray(String[]::new)), errors.toString(StandardCharsets.UTF_8));
        return classes;
    }

    private record Run(int code, String stdout, String stderr) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json() throws Exception {
            return (Map<String, Object>) Json.parse(stdout);
        }
    }

    private Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try (var stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
                var stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            int code = Main.run(args, stdout, stderr);
            return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        }
    }

    private Run validate(Path... paths) {
        String classpath = String.join(File.pathSeparator, Arrays.stream(paths).map(Path::toString).toList());
        return run("validate", "--classpath", classpath, "--json");
    }

    private Path jar(String name, Path classes) throws Exception {
        Path jar = dir.resolve(name + ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    private void invokeOnJvm(Path... paths) throws Exception {
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) {
            urls[i] = paths[i].toUri().toURL();
        }
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            var method = Class.forName("app.Main", false, loader).getMethod("run");
            method.invoke(null);
        }
    }

    private void assertJvmFailure(Class<? extends Throwable> expected, Path... paths) {
        var failure = assertThrows(InvocationTargetException.class, () -> invokeOnJvm(paths));
        assertInstanceOf(expected, failure.getCause());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> violations(Run run) throws Exception {
        return (List<Map<String, Object>>) run.json().get("violations");
    }

    private static Set<Object> ids(Run run) throws Exception {
        return violations(run).stream().map(v -> v.get("id")).collect(Collectors.toSet());
    }

    @Test
    void textReportsACompleteCleanSnapshot() throws Exception {
        Path classes = compile("app", Map.of("app/Main", "package app; public class Main {}"));
        Run result = run("validate", "--classpath", classes.toString());
        assertEquals(0, result.code(), result.stdout() + result.stderr());
        assertEquals(List.of("validated 1 classes on JDK " + Runtime.version().feature(), "0 violation(s)"), result.stdout().lines().toList());
        assertEquals("", result.stderr());
    }

    @Test
    void textReportsClassAndMemberRequirementsWithTheirSource() throws Exception {
        Path compileApi = compile("compile", Map.of("lib/Api", "package lib; public class Api { public static void m() {} }", "lib/Missing", "package lib; public class Missing {}"));
        Path api = compile("runtime", Map.of("lib/Api", "package lib; public class Api {}"));
        Path consumer = compile("app", Map.of("app/Main", """
                package app;
                import lib.Api;
                import lib.Missing;
                public class Main {
                    public Missing field;
                    public static void run() { Api.m(); }
                }
                """), compileApi);
        Run result = run("validate", "--classpath", api + File.pathSeparator + consumer);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        List<String> lines = result.stdout().lines().toList();
        assertEquals("validated 2 classes on JDK " + Runtime.version().feature(), lines.get(0));
        assertTrue(lines.contains("app/Main: class_not_found (" + consumer + ")"), result.stdout());
        assertTrue(lines.contains("app/Main: method_not_found (" + consumer + ")"), result.stdout());
        assertEquals(Set.of("    lib/Missing", "    lib/Api.m()V"), lines.stream().filter(line -> line.startsWith("    ")).collect(Collectors.toSet()));
        assertEquals("2 violation(s)", lines.get(lines.size() - 1));
        assertEquals("", result.stderr());
    }

    @Test
    void textReportsIncompleteValidationAndSendsDiagnosticsToStderr() {
        Path missing = dir.resolve("missing.jar");
        Run result = run("validate", "--classpath", missing.toString());
        assertEquals(2, result.code());
        assertEquals(List.of("validated 0 classes on JDK " + Runtime.version().feature(), "validation incomplete"), result.stdout().lines().toList());
        assertEquals(List.of("error: snapshot input is missing or unreadable: " + missing), result.stderr().lines().toList());
    }

    @Test
    void aChangedConsumerIsCheckedWithAnUnchangedProvider() throws Exception {
        Path api = compile("runtime", Map.of("lib/Api", "package lib; public class Api { public void ping() {} }"));
        Path compileApi = compile("compile", Map.of("lib/Api", "package lib; public class Api { public void absent() {} }"));
        Path baseline = compile("baseline", Map.of("app/Main", "package app; import lib.Api; public class Main { public void run() { new Api().ping(); } }"), api);
        Path candidate = compile("candidate", Map.of("app/Main", "package app; import lib.Api; public class Main { public void run() { new Api().absent(); } }"), compileApi);
        Run upgrade = run("check", "--old", api.toString(), "--new", api.toString(), "--classpath", candidate.toString(), "--json");
        assertEquals(0, upgrade.code(), upgrade.stderr());
        assertTrue(violations(upgrade).isEmpty());
        Run before = validate(api, baseline);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        assertEquals(Boolean.TRUE, before.json().get("complete"));
        Run after = validate(api, candidate);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertEquals(Boolean.TRUE, after.json().get("complete"));
        assertTrue(violations(after).stream().anyMatch(v -> v.get("kind").equals("method_not_found") && v.get("source_class").equals("app/Main")));
    }

    static Stream<Arguments> references() {
        return Stream.of(
                Arguments.of("missing method", "public void m() {}", "", "new Api().m();", "method_not_found"),
                Arguments.of("missing field", "public int value;", "", "int n = new Api().value;", "field_not_found"),
                Arguments.of("instance method became static", "public void m() {}", "public static void m() {}", "new Api().m();", "expected_instance_method"),
                Arguments.of("static method became instance", "public static void m() {}", "public void m() {}", "Api.m();", "expected_static_method"),
                Arguments.of("instance field became static", "public int value;", "public static int value;", "int n = new Api().value;", "expected_instance_field"),
                Arguments.of("static field became instance", "public static int value;", "public int value;", "int n = Api.value;", "expected_static_field"),
                Arguments.of("private method", "public void m() {}", "private void m() {}", "new Api().m();", "method_not_accessible"),
                Arguments.of("protected method", "public void m() {}", "protected void m() {}", "new Api().m();", "method_not_accessible"),
                Arguments.of("package method", "public void m() {}", "void m() {}", "new Api().m();", "method_not_accessible"),
                Arguments.of("private field", "public int value;", "private int value;", "int n = new Api().value;", "field_not_accessible"),
                Arguments.of("final field write", "public int value;", "public final int value = 1;", "new Api().value = 2;", "write_to_final_field"),
                Arguments.of("static method handle", "public static void m() {}", "public void m() {}", "Runnable r = Api::m;", "expected_static_method"),
                Arguments.of("virtual method handle", "public void m() {}", "public static void m() {}", "Consumer<Api> r = Api::m;", "expected_instance_method"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("references")
    void referenceRequirementsAreIndependentOfAnOldApi(String label, String compileMembers, String runtimeMembers, String code, String kind) throws Exception {
        Path compileApi = compile("compile", Map.of("lib/Api", "package lib; public class Api { " + compileMembers + " }"));
        Path api = compile("runtime", Map.of("lib/Api", "package lib; public class Api { " + runtimeMembers + " }"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import java.util.function.Consumer; import lib.Api; public class Main { public void run() { " + code + " } }"), compileApi);
        Run result = validate(api, consumer);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.TRUE, result.json().get("complete"));
        assertTrue(violations(result).stream().anyMatch(v -> v.get("kind").equals(kind) && v.get("source_class").equals("app/Main")), result.stdout());
    }

    @Test
    void historicalViolationsRemainVisibleAndDisappearAfterTheConsumerIsFixed() throws Exception {
        Path compileApi = compile("compile", Map.of("lib/Api", "package lib; public class Api { public void absent() {} }"));
        Path api = compile("runtime", Map.of("lib/Api", "package lib; public class Api { public void ping() {} }"));
        Path oldConsumer = compile("old", Map.of("app/Main", "package app; import lib.Api; public class Main { public void run() { new Api().absent(); } }"), compileApi);
        Path movedConsumer = compile("moved", Map.of("app/Main", "package app; import lib.Api; public class Main { public void run() { new Api().absent(); } }"), compileApi);
        Path fixedConsumer = compile("fixed", Map.of("app/Main", "package app; import lib.Api; public class Main { public void run() { new Api().ping(); } }"), api);
        Run before = validate(api, oldConsumer);
        Run after = validate(api, movedConsumer);
        assertEquals(1, before.code(), before.stdout());
        assertEquals(1, after.code(), after.stdout());
        assertEquals(ids(before), ids(after), "moving an artifact must not invent a new violation");
        assertNotEquals(violations(before).get(0).get("source"), violations(after).get(0).get("source"));
        assertEquals(api.toString(), violations(after).get(0).get("provider"));
        Run fixed = validate(api, fixedConsumer);
        assertEquals(0, fixed.code(), fixed.stdout());
        assertTrue(violations(fixed).isEmpty());
    }

    static Stream<Arguments> identityVectors() {
        return Stream.of(
                Arguments.of("method", "public class Api { public void m() {} }", "public class Api {}", "new Api().m();",
                        "4412d8d6c0380de7a94a0cb94fad52d8210448cb58cfada8a7f972e3c76fdcfb"),
                Arguments.of("field read", "public class Api { public static int value; }", "public class Api {}", "int value = Api.value;",
                        "f460526e32a6a55a1b1752028d9cec6648bcb0f9c74a72d6d832d6374e56ec2b"),
                Arguments.of("field write", "public class Api { public int value; }", "public class Api { public final int value = 1; }", "new Api().value = 2;",
                        "e6b4d8e61dd20415a76ff7ce16882325c025253c5ea22da6ffef2152caa2e42e"),
                Arguments.of("instantiation", "public class Api {}", "public abstract class Api {}", "new Api();",
                        "4e83ec67517461f9ba2e46e934a5694ecc96159e8df4dbee61f687d62d2d4d69"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("identityVectors")
    void schemaOneKeepsExistingRequirementIds(String label, String compileDeclaration, String runtimeDeclaration, String body, String expectedId) throws Exception {
        Path compileApi = compile("compile", Map.of("lib/Api", "package lib; " + compileDeclaration));
        Path api = compile("runtime", Map.of("lib/Api", "package lib; " + runtimeDeclaration));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Api; public class Main { public static void run() { " + body + " } }"), compileApi);
        Run result = validate(api, consumer);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.TRUE, result.json().get("complete"));
        assertEquals(1, ((Number) result.json().get("schema_version")).intValue());
        assertEquals(List.of(expectedId), violations(result).stream().map(v -> v.get("id")).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identicalServiceRequirementsRetainEverySourceArtifact(boolean reverseOrder) throws Exception {
        Path resources = Files.createDirectories(dir.resolve("registrations"));
        Path services = Files.createDirectories(resources.resolve("META-INF/services"));
        Files.writeString(services.resolve("java.lang.Runnable"), "missing.Provider\nmissing.Provider\n");
        Path oldJar = jar("old-registration", resources);
        Path newJar = jar("new-registration", resources);
        Run baseline = validate(oldJar);
        assertEquals(1, baseline.code(), baseline.stdout() + baseline.stderr());
        assertEquals(1, violations(baseline).size());
        assertEquals(Set.of("69682930defe378fac812c74e884e519bfbd641feddd08022f5109cc1c5efaf2"), ids(baseline));
        Run result = reverseOrder ? validate(newJar, oldJar) : validate(oldJar, newJar);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.TRUE, result.json().get("complete"));
        assertEquals(ids(baseline), ids(result));
        assertEquals(List.of(newJar.toString(), oldJar.toString()), violations(result).stream().map(v -> v.get("source")).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validationKeepsTheLaunchingJvmExports(boolean addExport) throws Exception {
        String export = "java.base/jdk.internal.misc=ALL-UNNAMED";
        Path consumer = compile("app", Map.of("app/Main", """
                package app;
                import jdk.internal.misc.Unsafe;
                public class Main {
                    public static void run() { Unsafe.getUnsafe(); }
                }
                """), List.of("--add-exports", export));
        Path java = Path.of(System.getProperty("java.home"), "bin", File.separatorChar == '\\' ? "java.exe" : "java");
        Path cli = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        if (addExport) {
            command.addAll(List.of("--add-exports", export));
        }
        command.addAll(List.of("-cp", cli.toString(), Main.class.getName(), "validate", "--classpath", consumer.toString(), "--json"));
        Path stdout = dir.resolve("stdout.json");
        Path stderr = dir.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().remove("UIKA_NO_RELAUNCH");
        Process process = builder.start();
        Run result;
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS), "validation process timed out");
            result = new Run(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } finally {
            process.destroyForcibly();
        }
        assertEquals(addExport ? 0 : 1, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.TRUE, result.json().get("complete"));
        assertEquals(addExport ? List.of() : List.of("class_not_accessible"), violations(result).stream().map(v -> v.get("kind")).toList());
    }

    @Test
    void firstClasspathDefinitionWinsAndInheritedOwnersResolveAcrossArtifacts() throws Exception {
        Path parent = compile("parent", Map.of("lib/Parent", "package lib; public class Parent { public void m() {} }"));
        Path good = compile("good", Map.of("lib/Api", "package lib; public class Api extends Parent {}"), parent);
        Path bad = compile("bad", Map.of("lib/Api", "package lib; public class Api {}"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Api; public class Main { public void run() { new Api().m(); } }"), good, parent);
        Run healthy = validate(good, bad, parent, consumer);
        assertEquals(0, healthy.code(), healthy.stdout() + healthy.stderr());
        Run broken = validate(bad, good, parent, consumer);
        assertEquals(1, broken.code(), broken.stdout() + broken.stderr());
        assertTrue(violations(broken).stream().anyMatch(v -> v.get("kind").equals("method_not_found")));
    }

    @Test
    void finalOverridesAreReportedWithoutAMethodCall() throws Exception {
        Path oldApi = compile("compile", Map.of("lib/Parent", "package lib; public class Parent { private void m() {} }", "lib/Api", "package lib; public class Api extends Parent {}"));
        Path api = compile("runtime", Map.of("lib/Parent", "package lib; public class Parent { private void m() {} }", "lib/Api", "package lib; public class Api extends Parent { public final void m() {} }"));
        Path consumer = compile("app", Map.of("app/Sub", "package app; import lib.Api; public class Sub extends Api { public void m() {} }"), oldApi);
        Run result = validate(api, consumer);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertTrue(violations(result).stream().anyMatch(v -> v.get("kind").equals("overrides_final_method") && v.get("source_class").equals("app/Sub")));
    }

    static Stream<Arguments> parents() {
        return Stream.of(
                Arguments.of("final superclass", "public class Api {}", "public final class Api {}", "extends", "extends_final_class"),
                Arguments.of("class became interface", "public class Api {}", "public interface Api {}", "extends", "expected_class"),
                Arguments.of("interface became class", "public interface Api {}", "public class Api {}", "implements", "expected_interface"),
                Arguments.of("abstract method", "public abstract class Api {}", "public abstract class Api { public abstract void m(); }", "extends", "abstract_method_unimplemented"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parents")
    void structuralRequirementsAreCheckedWithoutAnUpgrade(String label, String oldDeclaration, String declaration, String edge, String kind) throws Exception {
        Path compileApi = compile("compile", Map.of("lib/Api", "package lib; " + oldDeclaration));
        Path api = compile("runtime", Map.of("lib/Api", "package lib; " + declaration));
        Path consumer = compile("app", Map.of("app/Sub", "package app; import lib.Api; public class Sub " + edge + " Api {}"), compileApi);
        Run result = validate(api, consumer);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertTrue(violations(result).stream().anyMatch(v -> v.get("kind").equals(kind)), result.stdout());
    }

    @Test
    void conflictingDefaultsAreReported() throws Exception {
        Path oldApi = compile("compile", Map.of("lib/A", "package lib; public interface A { default void m() {} }", "lib/B", "package lib; public interface B {}"));
        Path api = compile("runtime", Map.of("lib/A", "package lib; public interface A { default void m() {} }", "lib/B", "package lib; public interface B { default void m() {} }"));
        Path consumer = compile("app", Map.of("app/Sub", "package app; import lib.A; import lib.B; public class Sub implements A, B {}"), oldApi);
        Run result = validate(api, consumer);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertTrue(violations(result).stream().anyMatch(v -> v.get("kind").equals("conflicting_default_methods")), result.stdout());
    }

    @Test
    void sealedTypesRejectAnUnlistedSubclass() throws Exception {
        Path oldApi = compile("compile", Map.of("lib/Api", "package lib; public class Api {}"));
        Path api = compile("runtime", Map.of("lib/Api", "package lib; public sealed class Api permits Allowed {}", "lib/Allowed", "package lib; public final class Allowed extends Api {}"));
        Path consumer = compile("app", Map.of("app/Sub", "package app; import lib.Api; public class Sub extends Api {}"), oldApi);
        Run result = validate(api, consumer);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertTrue(violations(result).stream().anyMatch(v -> v.get("kind").equals("not_permitted_subclass")), result.stdout());
    }

    @Test
    void missingRequiredClassIsAViolationButAMissingInputIsAnError() throws Exception {
        Path api = compile("compile", Map.of("lib/Api", "package lib; public class Api {}"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Api; public class Main { public void run() { new Api(); } }"), api);
        Run missingClass = validate(consumer);
        assertEquals(1, missingClass.code(), missingClass.stdout() + missingClass.stderr());
        assertEquals(Boolean.TRUE, missingClass.json().get("complete"));
        assertTrue(violations(missingClass).stream().anyMatch(v -> v.get("kind").equals("class_not_found")));
        Run missingInput = validate(dir.resolve("does-not-exist.jar"));
        assertEquals(2, missingInput.code());
        assertEquals(Boolean.FALSE, missingInput.json().get("complete"));
        assertFalse(((List<?>) missingInput.json().get("diagnostics")).isEmpty());
    }

    @Test
    void malformedClassesAreNotTreatedAsACleanSnapshot() throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.write(classes.resolve("Broken.class"), new byte[] {1, 2, 3});
        Run result = validate(classes);
        assertEquals(2, result.code());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void truncatedClassesWithValidMagicMakeValidationIncomplete(boolean archive) throws Exception {
        Path classes = compile("app", Map.of("app/Main", "package app; public class Main {}"));
        Path file = classes.resolve("app/Main.class");
        Files.write(file, Arrays.copyOf(Files.readAllBytes(file), CLASS_HEADER_BYTES));
        Path input = archive ? jar("truncated", classes) : classes;
        Run result = validate(input);
        assertEquals(2, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
        assertTrue(violations(result).isEmpty());
        assertTrue(result.stdout().contains("app/Main"), result.stdout());
        assertFalse(((List<?>) result.json().get("diagnostics")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aClassStoredUnderAnotherBinaryNameMakesValidationIncomplete(boolean archive) throws Exception {
        Path classes = compile("app", Map.of("app/Main", "package app; public class Main {}"));
        Files.move(classes.resolve("app/Main.class"), classes.resolve("app/Other.class"));
        Path input = archive ? jar("misnamed", classes) : classes;
        Run result = validate(input);
        assertEquals(2, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
        assertTrue(violations(result).isEmpty());
        assertTrue(result.stdout().contains("class entry does not match its binary name"), result.stdout());
        assertTrue(result.stdout().contains("app/Other"), result.stdout());
    }

    @Test
    void duplicateClassEntriesRequireNormalization() throws Exception {
        Path classes = compile("app", Map.of("app/Main", "package app; public class Main {}"));
        String entry = "app/Main.class";
        String placeholder = "app/Copy.class";
        Files.copy(classes.resolve(entry), classes.resolve(placeholder));
        Path input = jar("duplicates", classes);
        // Replace both ZIP headers; the equal lengths preserve the archive's offsets.
        assertEquals(entry.length(), placeholder.length());
        String bytes = new String(Files.readAllBytes(input), StandardCharsets.ISO_8859_1);
        Files.write(input, bytes.replace(placeholder, entry).getBytes(StandardCharsets.ISO_8859_1));
        Run result = validate(input);
        assertEquals(2, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
        assertTrue(violations(result).isEmpty());
        assertTrue(result.stdout().contains("duplicate class entry"), result.stdout());
        assertTrue(result.stdout().contains(entry), result.stdout());
    }

    @ParameterizedTest(name = "linked directory: {0}")
    @ValueSource(booleans = {false, true})
    void linkedClasspathContentsRequireNormalization(boolean directory) throws Exception {
        Path classes = compile("app", Map.of("app/Main", "package app; public class Main {}"));
        Path input = Files.createDirectories(dir.resolve("linked"));
        Path link = directory ? input.resolve("app") : Files.createDirectories(input.resolve("app")).resolve("Main.class");
        Path target = classes.resolve(directory ? "app" : "app/Main.class");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are unavailable: " + e);
        }
        Run result = validate(input);
        assertEquals(2, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
        assertTrue(violations(result).isEmpty());
        assertTrue(((List<?>) result.json().get("diagnostics")).stream().anyMatch(message -> message.toString().contains(link.toString())), result.stdout());
    }

    @ParameterizedTest(name = "missing ancestor interface: {0}")
    @ValueSource(booleans = {false, true})
    void transitiveLoadingFailuresKeepTheAncestorsRequirement(boolean iface) throws Exception {
        String kind = iface ? "interface" : "class";
        String relation = iface ? "implements" : "extends";
        Path classes = compile("app", Map.of(
                "lib/Grandparent", "package lib; public " + kind + " Grandparent {}",
                "lib/Parent", "package lib; public " + kind + " Parent extends Grandparent {}",
                "app/Main", "package app; import lib.Parent; public class Main " + relation + " Parent {}"));
        Files.delete(classes.resolve("lib/Grandparent.class"));
        Run result = validate(classes);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.TRUE, result.json().get("complete"));
        assertEquals(List.of(), result.json().get("diagnostics"));
        List<Map<String, Object>> issues = violations(result);
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().allMatch(issue -> issue.get("kind").equals("class_not_found") && issue.get("source_class").equals("lib/Parent")), result.stdout());
    }

    @Test
    void unknownHierarchyNeverBecomesSuccess() throws Exception {
        Path api = compile("compile", Map.of("lib/Parent", "package lib; public class Parent { public void m() {} }", "lib/Api", "package lib; public class Api extends Parent {}"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Api; public class Main { public void run() { new Api().m(); } }"), api);
        Files.delete(api.resolve("lib/Parent.class"));
        Run result = validate(api, consumer);
        assertEquals(2, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
    }

    @Test
    void bytecodeTypeErrorsAreSurfacedAsIncompleteValidation() throws Exception {
        Path oldApi = compile("compile", Map.of("lib/Parent", "package lib; public class Parent {}", "lib/Api", "package lib; public class Api extends Parent {}"));
        Path api = compile("runtime", Map.of("lib/Parent", "package lib; public class Parent {}", "lib/Api", "package lib; public class Api {}"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Api; import lib.Parent; public class Main { public Parent run() { return new Api(); } }"), oldApi);
        Run result = validate(api, consumer);
        assertEquals(2, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
        assertTrue(result.stdout().contains("VerifyError"), result.stdout());
    }

    @Test
    void validationDoesNotRunClassInitializersAndAcceptsLegalFinalWrites() throws Exception {
        String key = "uika.snapshot.initializer";
        System.clearProperty(key);
        Path consumer = compile("app", Map.of("app/Main", "package app; public class Main { public final int value = 1; static { System.setProperty(\"" + key + "\", \"ran\"); } public Runnable lambda() { return () -> {}; } }"));
        Run result = validate(consumer);
        assertEquals(0, result.code(), result.stdout() + result.stderr());
        assertNull(System.getProperty(key));
    }

    @Test
    void finalFieldWritesOutsideTheDeclaringConstructorAreReported() throws Exception {
        Path api = compile("app", Map.of("lib/Api", "package lib; public class Api { public int value; public void write() { value = 1; new int[0].clone(); } }"));
        Path file = api.resolve("lib/Api.class");
        byte[] bytes = Files.readAllBytes(file);
        ClassParser parser = new ClassParser();
        parser.parse(bytes, bytes.length);
        byte[] declaration = ByteBuffer.allocate(FIELD_HEADER_BYTES).putShort((short) Acc.PUBLIC).putShort((short) parser.fields[1]).putShort((short) parser.fields[2]).putShort((short) 0).array();
        int matches = 0;
        for (int i = 0; i <= bytes.length - declaration.length; i++) {
            if (Arrays.equals(bytes, i, i + declaration.length, declaration, 0, declaration.length)) {
                ByteBuffer.wrap(bytes).putShort(i, (short) (Acc.PUBLIC | Acc.FINAL));
                matches++;
            }
        }
        assertEquals(1, matches, "only the field declaration may be changed");
        Files.write(file, bytes);
        Run result = validate(api);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertTrue(violations(result).stream().anyMatch(v -> v.get("kind").equals("write_to_final_field")), result.stdout());
    }

    @Test
    void aPublicMethodMayReturnAPackagePrivateTypeToAnotherPackage() throws Exception {
        Path api = compile("api", Map.of("lib/Hidden", "package lib; class Hidden {}", "lib/Api", "package lib; public class Api { public Hidden get() { return null; } }"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Api; public class Main { public void run() { new Api().get(); } }"), api);
        Run result = validate(api, consumer);
        assertEquals(0, result.code(), result.stdout() + result.stderr());
    }

    @ParameterizedTest(name = "unsupported manifest attribute: {0}")
    @ValueSource(strings = {"Multi-Release", "Class-Path"})
    void inputsNeedingClasspathNormalizationAreRejected(String attribute) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue(attribute, attribute.equals("Multi-Release") ? "true" : "other.jar");
        Path jar = dir.resolve("input.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("placeholder.txt"));
            out.closeEntry();
        }
        Run result = validate(jar);
        assertEquals(2, result.code(), result.stdout());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
    }

    @Test
    void corruptClassEntriesInAJarAreNotSilentlySkipped() throws Exception {
        Path jar = dir.resolve("bad.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("Broken.class"));
            out.write(new byte[] {1, 2, 3});
            out.closeEntry();
        }
        Run result = validate(jar);
        assertEquals(2, result.code(), result.stdout());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
    }

    @Test
    void aMissingServiceProviderIsReported() throws Exception {
        Path api = compile("app", Map.of("app/Service", "package app; public interface Service {}"));
        Path services = Files.createDirectories(api.resolve("META-INF/services"));
        Files.writeString(services.resolve("app.Service"), "app.DoesNotExist\n");
        Run result = validate(api);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertTrue(violations(result).stream().anyMatch(v -> v.get("kind").equals("service_provider_not_found")), result.stdout());
        assertNull(violations(result).get(0).get("provider"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "public abstract class Impl implements Service {}",
            "public class Impl implements Service { private Impl() {} }",
            "class Impl implements Service {}",
            "public class Impl {}"
    })
    void anUnusableServiceProviderIsReportedEvenWhenItsClassExists(String declaration) throws Exception {
        Path classes = compile("app", Map.of("app/Service", "package app; public interface Service {}", "app/Impl", "package app; " + declaration));
        Path registration = Files.createDirectories(dir.resolve("registration"));
        Path services = Files.createDirectories(registration.resolve("META-INF/services"));
        Files.writeString(services.resolve("app.Service"), "app.Impl\n");
        Run result = validate(classes, registration);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.TRUE, result.json().get("complete"));
        List<Map<String, Object>> issues = violations(result);
        assertEquals(1, issues.size(), result.stdout());
        assertEquals("service_provider_not_instantiable", issues.get(0).get("kind"));
        assertEquals("app/Impl", issues.get(0).get("source_class"));
        assertEquals(registration.toString(), issues.get(0).get("source"));
        assertEquals(classes.toString(), issues.get(0).get("provider"));
        Path providerJar = jar("provider", classes);
        Path registrationJar = jar("registration", registration);
        Run archived = validate(providerJar, registrationJar);
        assertEquals(1, archived.code(), archived.stdout() + archived.stderr());
        assertEquals(ids(result), ids(archived));
        assertEquals(registrationJar.toString(), violations(archived).get(0).get("source"));
        assertEquals(providerJar.toString(), violations(archived).get(0).get("provider"));
    }

    @Test
    void aDifferentJdkReleaseIsAnIncompleteEnvironment() throws Exception {
        Run result = run("validate", "--classpath", dir.toString(), "--jdk-release", Runtime.version().feature() == 17 ? "21" : "17", "--json");
        assertEquals(2, result.code());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
        assertTrue(result.stdout().contains("must run on JDK"));
    }

    @Test
    void aMethodInAnotherPackageDoesNotImplementAPackagePrivateAbstractSlot() throws Exception {
        String parent = "package lib; public abstract class Parent { abstract void m(); }";
        Path oldApi = compile("compile", Map.of("lib/Parent", parent, "lib/Api", "package lib; public abstract class Api extends Parent { public void m() {} public void call() { m(); } }"));
        Path api = compile("runtime", Map.of("lib/Parent", parent, "lib/Api", "package lib; public abstract class Api extends Parent { public void call() { m(); } }"));
        Path consumer = compile("app", Map.of("app/Sub", "package app; import lib.Api; public class Sub extends Api { public void m() {} }"), oldApi);
        Run before = validate(oldApi, consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        Run after = validate(api, consumer);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertTrue(violations(after).stream().anyMatch(v -> v.get("kind").equals("abstract_method_unimplemented")), after.stdout());
        try (var loader = new URLClassLoader(new URL[] {api.toUri().toURL(), consumer.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName("app.Sub", false, loader);
            Object instance = type.getConstructor().newInstance();
            var failure = assertThrows(InvocationTargetException.class, () -> type.getMethod("call").invoke(instance));
            assertInstanceOf(AbstractMethodError.class, failure.getCause());
        }
    }

    @Test
    void aProtectedClassMethodDoesNotSatisfyAPublicInterfaceSlot() throws Exception {
        String parent = "package lib; public class Parent { protected void m() {} }";
        Path oldApi = compile("compile", Map.of("lib/Parent", parent, "lib/I", "package lib; public interface I {}"));
        Path api = compile("runtime", Map.of("lib/Parent", parent, "lib/I", "package lib; public interface I { void m(); }"));
        Path consumer = compile("app", Map.of("app/Sub", "package app; import lib.I; import lib.Parent; public class Sub extends Parent implements I {}"), oldApi);
        Run result = validate(api, consumer);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertTrue(violations(result).stream().anyMatch(v -> v.get("kind").equals("method_not_accessible")), result.stdout());
        try (var loader = new URLClassLoader(new URL[] {api.toUri().toURL(), consumer.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName("app.Sub", false, loader);
            Object instance = type.getConstructor().newInstance();
            var method = Class.forName("lib.I", false, loader).getMethod("m");
            var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(instance));
            assertInstanceOf(IllegalAccessError.class, failure.getCause());
        }
    }

    @Test
    void anUnclassifiedLoadingFailureCannotBeHiddenByAnotherViolation() throws Exception {
        Path classes = compile("app", Map.of("app/Missing", "package app; public class Missing {}", "app/Main", "package app; public class Main { public void run() { new Missing(); } }"));
        Files.delete(classes.resolve("app/Missing.class"));
        Path file = classes.resolve("app/Main.class");
        byte[] bytes = Files.readAllBytes(file);
        ClassParser parser = new ClassParser();
        parser.begin(bytes);
        parser.parseHeader(bytes.length, true);
        int header = parser.position() - (CLASS_HEADER_BYTES + parser.interfaceCount * Short.BYTES);
        ByteBuffer.wrap(bytes).putShort(header + SUPER_CLASS_OFFSET, (short) parser.thisClass);
        Files.write(file, bytes);
        Run result = validate(classes);
        assertEquals(2, result.code(), result.stdout());
        assertFalse(violations(result).isEmpty());
        assertTrue(result.stdout().contains("ClassCircularityError"), result.stdout());
    }

    @Test
    void privateNestmateAccessRemainsLegal() throws Exception {
        Path classes = compile("app", Map.of("app/Main", "package app; public class Main { private int value; static class Inner { int get(Main m) { return m.value; } } }"));
        Run result = validate(classes);
        assertEquals(0, result.code(), result.stdout() + result.stderr());
    }

    @Test
    void aNestMemberFromAnOlderHostMakesValidationIncomplete() throws Exception {
        Path oldClasses = compile("old", Map.of("app/Main", "package app; public class Main { private int value; static class Inner { int get(Main m) { return m.value; } } }"));
        Path classes = compile("runtime", Map.of("app/Main", "package app; public class Main { private int value; }"));
        Files.copy(oldClasses.resolve("app/Main$Inner.class"), classes.resolve("app/Main$Inner.class"));
        Run result = validate(classes);
        assertEquals(2, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
        assertTrue(result.stdout().contains("invalid nest membership for app/Main$Inner"), result.stdout());
    }

    @Test
    void classMethodReferencesCanResolveToAnInheritedInterfaceDefault() throws Exception {
        Path classes = compile("app", Map.of("app/I", "package app; public interface I { default void m() {} }", "app/Main", "package app; public class Main implements I { public void run() { m(); } }"));
        Run result = validate(classes);
        assertEquals(0, result.code(), result.stdout() + result.stderr());
    }

    @ParameterizedTest
    @ValueSource(strings = {"() -> new Api()", "Api::new"})
    void abstractClassesCannotBeInstantiatedThroughCodeOrConstructorHandles(String factory) throws Exception {
        Path oldApi = compile("compile", Map.of("lib/Api", "package lib; public class Api {}"));
        Path api = compile("runtime", Map.of("lib/Api", "package lib; public abstract class Api {}"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import java.util.function.Supplier; import lib.Api; public class Main { public Supplier<Api> factory() { return " + factory + "; } }"), oldApi);
        Run result = validate(api, consumer);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertTrue(violations(result).stream().anyMatch(v -> v.get("kind").equals("class_not_instantiable")), result.stdout());
    }

    @Test
    void signaturePolymorphicJdkMethodsAcceptCallSiteDescriptors() throws Exception {
        Path classes = compile("app", Map.of("app/Main", """
                package app;
                import java.lang.invoke.MethodHandle;
                import java.lang.invoke.VarHandle;
                public class Main {
                    public int invoke(MethodHandle h) throws Throwable { return (int) h.invokeExact(1); }
                    public int read(VarHandle h, int[] a) { return (int) h.getAcquire(a, 0); }
                    public void write(VarHandle h, int[] a) { h.setRelease(a, 0, 1); }
                    public boolean swap(VarHandle h, int[] a) { return h.compareAndSet(a, 0, 1, 2); }
                }
                """));
        Run result = validate(classes);
        assertEquals(0, result.code(), result.stdout() + result.stderr());
    }

    @Test
    void aMoreSpecificDefaultImplementsAnAbstractInterfaceMethod() throws Exception {
        Path classes = compile("app", Map.of("app/Parent", "package app; public interface Parent { void m(); }", "app/Child", "package app; public interface Child extends Parent { default void m() {} }", "app/Main", "package app; public class Main implements Child {}"));
        Run result = validate(classes);
        assertEquals(0, result.code(), result.stdout() + result.stderr());
    }

    @Test
    void aMoreSpecificAbstractDeclarationSuppressesAnInheritedDefault() throws Exception {
        String parent = "package lib; public interface Parent { default void m() {} }";
        Path oldApi = compile("compile", Map.of("lib/Parent", parent, "lib/Child", "package lib; public interface Child extends Parent {}"));
        Path api = compile("runtime", Map.of("lib/Parent", parent, "lib/Child", "package lib; public interface Child extends Parent { void m(); }"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Child; public class Main implements Child {}"), oldApi);
        Run result = validate(api, consumer);
        assertEquals(1, result.code(), result.stdout() + result.stderr());
        assertTrue(violations(result).stream().anyMatch(v -> v.get("kind").equals("abstract_method_unimplemented")), result.stdout());
    }

    static Stream<Arguments> specialCalls() {
        return Stream.of(Arguments.of(false, "super.m();"), Arguments.of(false, "Runnable r = super::m; r.run();"), Arguments.of(true, "Api.super.m();"), Arguments.of(true, "Runnable r = Api.super::m; r.run();"));
    }

    // javac implements super::m through a synthetic method, so change the handle itself here.
    private void useSpecialHandle(Path file, String ownerName, int referenceTag) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        ClassParser parser = new ClassParser();
        parser.parse(bytes, bytes.length);
        Scratch scratch = Scratch.current();
        int owner = 0;
        int handle = 0;
        for (int i = 1; i < parser.cpLen; i++) {
            if (parser.tag(i) == ClassParser.TAG_CLASS
                    && parser.internClassName(scratch, i) == Intern.intern(ownerName)) {
                owner = i;
            } else if (parser.tag(i) == ClassParser.TAG_METHOD_HANDLE) {
                int target = parser.handleTarget(i);
                if (parser.internUtf8(scratch, parser.operand1(parser.operand2(target))) == Intern.intern("m")) {
                    assertEquals(0, handle, "fixture must have one handle for m");
                    handle = i;
                }
            }
        }
        assertNotEquals(0, owner);
        assertNotEquals(0, handle);
        int target = parser.handleTarget(handle);
        bytes[parser.cpPos[handle] + Byte.BYTES] = ClassParser.HANDLE_INVOKE_SPECIAL;
        bytes[parser.cpPos[target]] = (byte) referenceTag;
        ByteBuffer.wrap(bytes).putShort(parser.cpPos[target] + Byte.BYTES, (short) owner);
        Files.write(file, bytes);
    }

    static Stream<Arguments> specialHandleOwners() {
        return Stream.of(
                Arguments.of("declaring class", "public class Api { public void m() {} }", "extends", "Api"),
                Arguments.of("sibling class", "public class Api { public void m() {} }", "extends", "Sibling"),
                Arguments.of("inherited default", "public interface Api { default void m() {} }", "implements", "Sibling"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specialHandleOwners")
    void specialHandlesRequireTheSelectedDeclaringType(String label, String declaration, String relation, String owner) throws Exception {
        String api = "package lib; " + declaration;
        String sibling = "package lib; public class Sibling " + relation + " Api {}";
        Path oldApi = compile("compile", Map.of("lib/Api", api, "lib/Sibling", sibling,
                "lib/Middle", "package lib; public class Middle " + relation + " Api {}"));
        Path newApi = compile("runtime", Map.of("lib/Api", api, "lib/Sibling", sibling,
                "lib/Middle", "package lib; public class Middle {}"));
        Path consumer = compile("app", Map.of("app/Main", """
                package app;
                import java.util.function.Consumer;
                import lib.%s;
                import lib.Middle;
                public class Main extends Middle {
                    public static Class<?> owner() { return %s.class; }
                    public static void run() { Consumer<Main> call = Main::m; }
                }
                """.formatted(owner, owner)), oldApi);
        useSpecialHandle(consumer.resolve("app/Main.class"), "lib/" + owner, ClassParser.TAG_METHODREF);
        invokeOnJvm(oldApi, consumer);
        Run before = validate(oldApi, consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        assertJvmFailure(IllegalAccessError.class, newApi, consumer);
        Run after = validate(newApi, consumer);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertEquals(Boolean.TRUE, after.json().get("complete"));
        assertEquals(List.of("method_not_accessible"), violations(after).stream().map(v -> v.get("kind")).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Api", "Child", ""})
    void specialInterfaceHandlesRequireADirectSuperinterface(String implemented) throws Exception {
        Path api = compile("api", Map.of("lib/Api", "package lib; public interface Api { default void m() {} }",
                "lib/Child", "package lib; public interface Child extends Api {}"));
        Path consumer = compile("app", Map.of("app/Main", """
                package app;
                import java.util.function.Consumer;
                import lib.Api;
                import lib.Child;
                public class Main %s {
                    public void m() {}
                    public static Class<?> owner() { return Api.class; }
                    public static void run() { Consumer<Main> call = Main::m; }
                }
                """.formatted(implemented.isEmpty() ? "" : "implements " + implemented)), api);
        useSpecialHandle(consumer.resolve("app/Main.class"), "lib/Api", ClassParser.TAG_INTERFACE_METHODREF);
        boolean direct = implemented.equals("Api");
        if (direct) {
            invokeOnJvm(api, consumer);
        } else {
            assertJvmFailure(IncompatibleClassChangeError.class, api, consumer);
        }
        Run result = validate(api, consumer);
        assertEquals(direct ? 0 : 1, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.TRUE, result.json().get("complete"));
        assertEquals(direct ? List.of() : List.of("method_not_accessible"), violations(result).stream().map(v -> v.get("kind")).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"default void m() {}", "private void m() {}", ""})
    void specialInterfaceHandlesCanNameTheCallingInterface(String declaration) throws Exception {
        Path consumer = compile("app", Map.of(
                "app/Parent", "package app; public interface Parent { default void m() {} }",
                "app/Main", """
                package app;
                import java.util.function.Consumer;
                public interface Main %s {
                    %s
                    static void run() { Consumer<Main> call = Main::m; }
                }
                """.formatted(declaration.isEmpty() ? "extends Parent" : "", declaration)));
        useSpecialHandle(consumer.resolve("app/Main.class"), "app/Main", ClassParser.TAG_INTERFACE_METHODREF);
        invokeOnJvm(consumer);
        Run result = validate(consumer);
        assertEquals(0, result.code(), result.stdout() + result.stderr());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void specialHandlesUseTheMostSpecificDefaultOwner(boolean implementsSelectedOwner) throws Exception {
        Path api = compile("api", Map.of(
                "lib/I", "package lib; public interface I { default void m() {} }",
                "lib/J", "package lib; public interface J extends I { default void m() {} }",
                "lib/Sibling", "package lib; public class Sibling implements I, J {}"));
        Path consumer = compile("app", Map.of("app/Main", """
                package app;
                import java.util.function.Consumer;
                import lib.I;
                import lib.J;
                import lib.Sibling;
                public class Main implements %s {
                    public static Class<?> owner() { return Sibling.class; }
                    public static void run() { Consumer<Main> call = Main::m; }
                }
                """.formatted(implementsSelectedOwner ? "J" : "I")), api);
        useSpecialHandle(consumer.resolve("app/Main.class"), "lib/Sibling", ClassParser.TAG_METHODREF);
        if (implementsSelectedOwner) {
            invokeOnJvm(api, consumer);
        } else {
            assertJvmFailure(IllegalAccessError.class, api, consumer);
        }
        Run result = validate(api, consumer);
        assertEquals(implementsSelectedOwner ? 0 : 1, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.TRUE, result.json().get("complete"));
        assertEquals(implementsSelectedOwner ? List.of() : List.of("method_not_accessible"), violations(result).stream().map(v -> v.get("kind")).toList());
    }

    @Test
    void specialHandlesCheckAccessAfterSuperclassSelection() throws Exception {
        Path oldApi = compile("compile", Map.of(
                "lib/Api", "package lib; public class Api { public void m() {} }",
                "lib/Middle", "package lib; public class Middle extends Api {}"));
        Path newApi = compile("runtime", Map.of(
                "lib/Api", "package lib; public class Api { private void m() {} }",
                "lib/Middle", "package lib; public class Middle extends Api { private void m() {} }"));
        Files.write(newApi.resolve("lib/Api.class"), Files.readAllBytes(oldApi.resolve("lib/Api.class")));
        Path selected = Files.createDirectories(dir.resolve("selected"));
        Files.createDirectories(selected.resolve("lib"));
        Files.move(newApi.resolve("lib/Middle.class"), selected.resolve("lib/Middle.class"));
        Path consumer = compile("app", Map.of("app/Main", """
                package app;
                import java.util.function.Consumer;
                import lib.Api;
                import lib.Middle;
                public class Main extends Middle {
                    public static Class<?> owner() { return Api.class; }
                    public static void run() { Consumer<Main> call = Main::m; }
                }
                """), oldApi);
        useSpecialHandle(consumer.resolve("app/Main.class"), "lib/Api", ClassParser.TAG_METHODREF);
        invokeOnJvm(oldApi, consumer);
        Run before = validate(oldApi, consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        assertJvmFailure(IllegalAccessError.class, newApi, selected, consumer);
        Run after = validate(newApi, selected, consumer);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertEquals(Boolean.TRUE, after.json().get("complete"));
        assertEquals(List.of("method_not_accessible"), violations(after).stream().map(v -> v.get("kind")).toList());
        assertEquals(selected.toString(), violations(after).get(0).get("provider"));
    }

    @ParameterizedTest
    @MethodSource("specialCalls")
    void aSuperCallCannotInvokeAnAbstractMethod(boolean iface, String call) throws Exception {
        Path oldApi = compile("compile", Map.of("lib/Api", iface ? "package lib; public interface Api { default void m() {} }" : "package lib; public class Api { public void m() {} }"));
        Path api = compile("runtime", Map.of("lib/Api", iface ? "package lib; public interface Api { void m(); }" : "package lib; public abstract class Api { public abstract void m(); }"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Api; public class Main " + (iface ? "implements" : "extends") + " Api { public void m() { " + call + " } public static void run() { new Main().m(); } }"), oldApi);
        Run before = validate(oldApi, consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        Run after = validate(api, consumer);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertEquals(Boolean.TRUE, after.json().get("complete"));
        assertTrue(violations(after).stream().anyMatch(v -> v.get("kind").equals("abstract_method_called")), after.stdout());
        assertJvmFailure(AbstractMethodError.class, api, consumer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"I.m();", "Runnable r = I::m; r.run();"})
    void staticInterfaceMethodsAreNotInherited(String call) throws Exception {
        Path oldApi = compile("compile", Map.of("lib/I", "package lib; public interface I { static void m() {} }"));
        Path api = compile("runtime", Map.of("lib/J", "package lib; public interface J { static void m() {} }", "lib/I", "package lib; public interface I extends J {}"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.I; public class Main { public static void run() { " + call + " } }"), oldApi);
        Run before = validate(oldApi, consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        Run after = validate(api, consumer);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertEquals(Boolean.TRUE, after.json().get("complete"));
        assertTrue(violations(after).stream().anyMatch(v -> v.get("kind").equals("method_not_found")), after.stdout());
        assertJvmFailure(NoSuchMethodError.class, api, consumer);
    }

    @Test
    void superInterfaceCallsRespectAMoreSpecificAbstractDeclaration() throws Exception {
        String parent = "package lib; public interface Parent { default void m() {} }";
        String iface = "package lib; public interface Api extends Parent, Child {}";
        Path oldApi = compile("compile", Map.of("lib/Parent", parent, "lib/Child", "package lib; public interface Child extends Parent {}", "lib/Api", iface));
        Path api = compile("runtime", Map.of("lib/Parent", parent, "lib/Child", "package lib; public interface Child extends Parent { void m(); }", "lib/Api", iface));
        Path selected = Files.createDirectories(dir.resolve("selected"));
        Files.createDirectories(selected.resolve("lib"));
        Files.move(api.resolve("lib/Child.class"), selected.resolve("lib/Child.class"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Api; public class Main implements Api { public void m() { Api.super.m(); } public static void run() { new Main().m(); } }"), oldApi);
        Run before = validate(oldApi, consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        Run after = validate(api, selected, consumer);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertTrue(violations(after).stream().anyMatch(v -> v.get("kind").equals("abstract_method_called")), after.stdout());
        assertEquals(selected.toString(), violations(after).get(0).get("provider"));
        assertJvmFailure(AbstractMethodError.class, api, selected, consumer);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSuperCallAttributesTheFailureToTheResolvedOrSelectedDeclaration(boolean accessibleAncestor) throws Exception {
        Path oldGrand = compile("compile-grand", Map.of("lib/Grand", "package lib; public class Grand { public void m() {} }"));
        Path grand = compile("runtime-grand", Map.of("lib/Grand", "package lib; public class Grand { " + (accessibleAncestor ? "public" : "private") + " void m() {} }"));
        Path oldApi = compile("compile", Map.of("lib/Api", "package lib; public class Api extends Grand {}"), oldGrand);
        Path api = compile("runtime", Map.of("lib/Api", "package lib; public abstract class Api extends Grand { public abstract void m(); }"), grand);
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Api; import lib.Grand; public class Main extends Api { public static Class<?> ancestor() { return Grand.class; } public void m() { super.m(); } public static void run() { new Main().m(); } }"), oldApi, oldGrand);
        Path file = consumer.resolve("app/Main.class");
        byte[] bytes = Files.readAllBytes(file);
        ClassParser parser = new ClassParser();
        parser.parse(bytes, bytes.length);
        int owner = 0;
        int reference = 0;
        Scratch scratch = Scratch.current();
        int grandName = Intern.intern("lib/Grand");
        int apiName = Intern.intern("lib/Api");
        int methodName = Intern.intern("m");
        for (int i = 1; i < parser.cpLen; i++) {
            if (parser.tag(i) == ClassParser.TAG_CLASS
                    && parser.internUtf8(scratch, parser.classNameIndex(i)) == grandName) {
                owner = i;
            } else if (parser.tag(i) == ClassParser.TAG_METHODREF
                    && parser.internUtf8(scratch, parser.classNameIndex(parser.operand1(i))) == apiName
                    && parser.internUtf8(scratch, parser.operand1(parser.operand2(i))) == methodName) {
                reference = i;
            }
        }
        assertNotEquals(0, owner);
        assertNotEquals(0, reference);
        ByteBuffer.wrap(bytes).putShort(parser.cpPos[reference] + Byte.BYTES, (short) owner);
        Files.write(file, bytes);
        Run before = validate(oldApi, oldGrand, consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        Run after = validate(api, grand, consumer);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertEquals(Boolean.TRUE, after.json().get("complete"));
        List<Map<String, Object>> issues = violations(after);
        assertEquals(1, issues.size(), after.stdout());
        assertEquals(accessibleAncestor ? "abstract_method_called" : "method_not_accessible", issues.get(0).get("kind"));
        assertEquals("lib/Grand", ((Map<?, ?>) issues.get(0).get("reference")).get("owner"));
        assertEquals(consumer.toString(), issues.get(0).get("source"));
        assertEquals((accessibleAncestor ? api : grand).toString(), issues.get(0).get("provider"));
        assertJvmFailure(accessibleAncestor ? AbstractMethodError.class : IllegalAccessError.class, api, grand, consumer);
        Path apiJar = jar("api", api);
        Path grandJar = jar("grand", grand);
        Path consumerJar = jar("consumer", consumer);
        Run archived = validate(apiJar, grandJar, consumerJar);
        assertEquals(1, archived.code(), archived.stdout() + archived.stderr());
        assertEquals(ids(after), ids(archived));
        assertEquals(consumerJar.toString(), violations(archived).get(0).get("source"));
        assertEquals((accessibleAncestor ? apiJar : grandJar).toString(), violations(archived).get(0).get("provider"));
    }

    @Test
    void interfaceMethodResolutionUsesOnlyPublicObjectMethods() throws Exception {
        Path oldApi = compile("compile", Map.of("lib/I", "package lib; public interface I { Object clone(); }"));
        Path api = compile("runtime", Map.of("lib/I", "package lib; public interface I {}"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.I; public class Main implements I { public Object clone() { return this; } public static void run() { I i = new Main(); i.clone(); } }"), oldApi);
        Run before = validate(oldApi, consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        Run after = validate(api, consumer);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertTrue(violations(after).stream().anyMatch(v -> v.get("kind").equals("method_not_found")), after.stdout());
        assertJvmFailure(NoSuchMethodError.class, api, consumer);
    }

    static Stream<Arguments> malformedServiceMetadata() {
        return Stream.of(false, true).flatMap(archive -> Stream.of(
                Arguments.of(archive, "malformed UTF-8", new byte[] {(byte) 0xff, '\n'}),
                Arguments.of(archive, "empty provider name component", "app..Impl\n".getBytes(StandardCharsets.UTF_8))));
    }

    @ParameterizedTest(name = "{1}, archive={0}")
    @MethodSource("malformedServiceMetadata")
    void malformedServiceMetadataMakesValidationIncomplete(boolean archive, String label, byte[] metadata) throws Exception {
        Path classes = compile("app", Map.of("app/Service", "package app; public interface Service {}", "app/Impl", "package app; public class Impl implements Service {}", "app/Main", "package app; import java.util.ServiceLoader; public class Main { public static void run() { ServiceLoader.load(Service.class, Service.class.getClassLoader()).iterator().next(); } }"));
        Path file = Files.createDirectories(classes.resolve("META-INF/services")).resolve("app.Service");
        Files.writeString(file, "app.Impl\n");
        Path beforePath = archive ? jar("before", classes) : classes;
        Run before = validate(beforePath);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        Files.write(file, metadata);
        Path afterPath = archive ? jar("after", classes) : classes;
        Run after = validate(afterPath);
        assertEquals(2, after.code(), after.stdout() + after.stderr());
        assertEquals(Boolean.FALSE, after.json().get("complete"));
        assertFalse(((List<?>) after.json().get("diagnostics")).isEmpty());
        assertJvmFailure(ServiceConfigurationError.class, afterPath);
    }

    @Test
    void corruptServiceEntriesAreNotSilentlySkipped() throws Exception {
        Path jar = dir.resolve("services.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/services/app.Service"));
            out.write("app.Impl\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(jar);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int data = ZIP_LOCAL_HEADER_BYTES + Short.toUnsignedInt(header.getShort(ZIP_NAME_LENGTH_OFFSET)) + Short.toUnsignedInt(header.getShort(ZIP_EXTRA_LENGTH_OFFSET));
        bytes[data] = (byte) 0xff;
        Files.write(jar, bytes);
        Run result = validate(jar);
        assertEquals(2, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
    }

    @Test
    void jarIndexesRequireNormalizationBeforeAnyClassLoading() throws Exception {
        Path jar = dir.resolve("indexed.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/INDEX.LIST"));
            out.write("JarIndex-Version: 1.0\n\nexternal.jar\nlib\n\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Run result = validate(jar);
        assertEquals(2, result.code(), result.stdout() + result.stderr());
        assertEquals(Boolean.FALSE, result.json().get("complete"));
        assertTrue(result.stdout().contains("INDEX.LIST"), result.stdout());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "MethodHandle h = MethodHandles.empty(MethodType.methodType(void.class, Object.class)); h.invoke(Api.get());",
        "Supplier<Object> supplier = Api::get; supplier.get();"
    })
    void methodHandleDescriptorsRequireAccessibleTypes(String call) throws Exception {
        Path publicApi = compile("public", Map.of(
                "lib/Hidden", "package lib; public class Hidden {}",
                "lib/Api", "package lib; public class Api { public static Hidden get() { return null; } }"));
        Path hiddenApi = compile("hidden", Map.of(
                "lib/Hidden", "package lib; class Hidden {}",
                "lib/Api", "package lib; public class Api { public static Hidden get() { return null; } }"));
        Path consumer = compile("app", Map.of("app/Main", """
                package app;
                import java.lang.invoke.MethodHandle;
                import java.lang.invoke.MethodHandles;
                import java.lang.invoke.MethodType;
                import java.util.function.Supplier;
                import lib.Api;
                public class Main {
                    public static void run() throws Throwable { %s }
                }
                """.formatted(call)), publicApi);
        Run before = validate(publicApi, consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        invokeOnJvm(publicApi, consumer);
        assertJvmFailure(IllegalAccessError.class, hiddenApi, consumer);
        Run after = validate(hiddenApi, consumer);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertEquals(Boolean.TRUE, after.json().get("complete"));
        assertEquals(1, violations(after).size(), after.stdout());
        assertEquals("class_not_accessible", violations(after).get(0).get("kind"));
        assertEquals("lib/Hidden", ((Map<?, ?>) violations(after).get(0).get("reference")).get("owner"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protectedConstructorHandlesRequireTheDeclaringPackage(boolean samePackage) throws Exception {
        Path oldApi = compile("public", Map.of("lib/Base", "package lib; public class Base { public Base() {} }"));
        Path api = compile("protected", Map.of("lib/Base", "package lib; public class Base { protected Base() {} }"));
        String consumerPackage = samePackage ? "lib" : "app";
        Path consumer = compile("app", Map.of(
                consumerPackage + "/Sub", "package " + consumerPackage + "; import java.util.function.Supplier; import lib.Base; public class Sub extends Base { public static void create() { Supplier<Base> factory = Base::new; factory.get(); } }",
                "app/Main", "package app; import " + consumerPackage + ".Sub; public class Main { public static void run() { Sub.create(); } }"), oldApi);
        Run before = validate(oldApi, consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        invokeOnJvm(oldApi, consumer);
        if (samePackage) {
            invokeOnJvm(api, consumer);
        } else {
            assertJvmFailure(IllegalAccessError.class, api, consumer);
        }
        Run after = validate(api, consumer);
        assertEquals(samePackage ? 0 : 1, after.code(), after.stdout() + after.stderr());
        assertEquals(Boolean.TRUE, after.json().get("complete"));
        assertEquals(samePackage ? 0 : 1, violations(after).size(), after.stdout());
        if (!samePackage) {
            assertEquals("method_not_accessible", violations(after).get(0).get("kind"));
        }
    }

    @Test
    void aProtectedSuperConstructorRemainsAccessible() throws Exception {
        Path api = compile("api", Map.of("lib/Base", "package lib; public class Base { protected Base() {} }"));
        Path consumer = compile("app", Map.of("app/Main", "package app; import lib.Base; public class Main extends Base { public static void run() { new Main(); } }"), api);
        Run result = validate(api, consumer);
        assertEquals(0, result.code(), result.stdout() + result.stderr());
        invokeOnJvm(api, consumer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"new int[1]", "new String[1]", "new int[1][]", "new Object[1][]"})
    void arrayMethodReferencesAreChecked(String array) throws Exception {
        Path consumer = compile("app", Map.of("app/Main", "package app; public class Main { public static void run() { (" + array + ").clone(); } }"));
        Run before = validate(consumer);
        assertEquals(0, before.code(), before.stdout() + before.stderr());
        invokeOnJvm(consumer);
        Path file = consumer.resolve("app/Main.class");
        byte[] bytes = Files.readAllBytes(file);
        ClassParser parser = new ClassParser();
        parser.parse(bytes, bytes.length);
        byte[] replacement = "absnt".getBytes(StandardCharsets.UTF_8);
        int replacements = 0;
        for (int i = 1; i < parser.cpLen; i++) {
            if (parser.tag(i) == ClassParser.TAG_UTF8 && parser.internUtf8(Scratch.current(), i) == Intern.intern("clone")) {
                assertEquals(replacement.length, parser.utf8Length(i));
                System.arraycopy(replacement, 0, bytes, parser.utf8Offset(i), replacement.length);
                replacements++;
            }
        }
        assertEquals(1, replacements);
        Files.write(file, bytes);
        assertJvmFailure(NoSuchMethodError.class, consumer);
        Run after = validate(consumer);
        assertEquals(1, after.code(), after.stdout() + after.stderr());
        assertEquals(Boolean.TRUE, after.json().get("complete"));
        assertEquals(1, violations(after).size(), after.stdout());
        assertEquals("method_not_found", violations(after).get(0).get("kind"));
        Map<?, ?> reference = (Map<?, ?>) violations(after).get(0).get("reference");
        assertTrue(((String) reference.get("owner")).startsWith("["), after.stdout());
        assertEquals("absnt", ((Map<?, ?>) reference.get("member")).get("name"));
    }
}
