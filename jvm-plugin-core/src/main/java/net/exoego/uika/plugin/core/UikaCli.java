package net.exoego.uika.plugin.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Locates and runs the uika CLI distributed as {@code net.exoego.uika:uika-cli:<version>} ZIPs
 * with per-platform classifiers. The build tool resolves the ZIP through its own dependency
 * machinery (repositories, mirrors, credentials, cache); this class only maps the platform to
 * a classifier, extracts the binary, and runs it.
 */
public final class UikaCli {
    private UikaCli() {}

    public static final String GROUP = "net.exoego.uika";
    public static final String ARTIFACT = "uika-cli";

    /** Maven classifier of the published binary for the current platform, e.g. "macos-aarch64". */
    public static String platformClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("linux") && x64) {
            return "linux-x86_64";
        }
        if (os.contains("mac") && arm64) {
            return "macos-aarch64";
        }
        if (os.contains("mac") && x64) {
            return "macos-x86_64";
        }
        if (os.contains("windows") && x64) {
            return "windows-x86_64";
        }
        throw new IllegalStateException("no uika-cli binary is published for " + os + "/" + arch
                + " (available: linux-x86_64, macos-aarch64, macos-x86_64, windows-x86_64)");
    }

    /**
     * Extracts the uika binary from the distribution ZIP into {@code targetDir} and returns its
     * path. Skips extraction when the binary is already there, so callers should scope
     * {@code targetDir} by version and classifier.
     */
    public static Path extractBinary(Path zip, Path targetDir) throws IOException {
        String binaryName = platformClassifier().startsWith("windows") ? "uika.exe" : "uika";
        Path binary = targetDir.resolve(binaryName);
        if (Files.isRegularFile(binary)) {
            return binary;
        }
        Files.createDirectories(targetDir);
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            ZipEntry entry = zipFile.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().equals(binaryName)
                            || e.getName().endsWith("/" + binaryName))
                    .findFirst()
                    .orElseThrow(() -> new IOException(binaryName + " not found in " + zip));
            // Extract to a temp file and rename so a concurrent build never sees a partial binary.
            Path tmp = Files.createTempFile(targetDir, "uika", ".tmp");
            try (InputStream in = zipFile.getInputStream(entry)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp.toFile().setExecutable(true, false);
            Files.move(tmp, binary, StandardCopyOption.REPLACE_EXISTING);
        }
        return binary;
    }

    /**
     * The JDK whose {@code ct.sym} backs the API layer, and whose home is exported as
     * {@code UIKA_JDK}.
     *
     * <p>A plain class, not a record: the sbt plugin compiles these shared sources through
     * zinc, and Scala 2.12's Java source parser does not understand record declarations --
     * the symptom is "not found: type JdkSource" at the first USE, which reads like a missing
     * import rather than an unparsed declaration.
     *
     * <p>Not necessarily the JVM running the build. A module targeting a release NEWER than
     * the build JVM cannot be served by the build JVM's ct.sym at all (a JDK's ct.sym stops
     * one release below its own), so a plugin that can name the module's actual JDK should
     * pass it instead of accepting the clamp. Whatever is passed here has to be the same JDK
     * the release is measured against: the ceiling, the ct.sym existence check and the
     * UIKA_JDK export are one decision, and splitting them claims a release the CLI's ct.sym
     * cannot serve.
     */
    public static final class JdkSource {
        private final Path home;
        private final int feature;

        public JdkSource(Path home, int feature) {
            this.home = home;
            this.feature = feature;
        }

        /** The JVM running this code, which is what a plugin with no better answer has. */
        public static JdkSource current() {
            return new JdkSource(
                    Path.of(System.getProperty("java.home")), Runtime.version().feature());
        }

        public Path home() {
            return home;
        }

        public int feature() {
            return feature;
        }
    }

    /** The lowest release the JDK API layer can serve, since ct.sym carries no older stubs. */
    public static final int MIN_RELEASE = 8;

    /**
     * The API release a compiler-option list targets, or null when it declares none.
     *
     * <p>Shared by every plugin that has only the raw option list to go on, so one parser
     * covers the spellings javac and scalac actually accept. Both the space-separated and the
     * {@code --release=17} forms, since javac takes either. {@code -release} alone is scalac's
     * spelling, and {@code -java-output-version} is its Scala 3 name. Legacy {@code 1.8} and
     * {@code jvm-1.8} values name release 8, which the layer CAN serve, so they are
     * normalized rather than dropped.
     *
     * <p>Anything below {@link #MIN_RELEASE} is reported as no declaration at all. It cannot
     * be represented, and treating it as a data point would drag a whole build's minimum
     * under the floor and switch the layer off for every module.
     */
    public static Integer declaredRelease(List<String> options) {
        Integer target = null;
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            int separator = firstSeparator(option);
            String flag = separator < 0 ? option : option.substring(0, separator);
            String value = separator < 0
                    ? (i + 1 < options.size() ? options.get(i + 1) : null)
                    : option.substring(separator + 1);
            Integer release = parseRelease(value);
            if (release == null) {
                continue;
            }
            if (flag.equals("--release") || flag.equals("-release")
                    || flag.equals("--java-output-version") || flag.equals("-java-output-version")) {
                return release;
            }
            if (target == null && (flag.equals("-target") || flag.equals("--target"))) {
                target = release;
            }
        }
        return target;
    }

    private static int firstSeparator(String option) {
        int equals = option.indexOf('=');
        int colon = option.indexOf(':');
        if (equals < 0) {
            return colon;
        }
        return colon < 0 ? equals : Math.min(equals, colon);
    }

    /**
     * A release number written any of the ways the JVM toolchains spell it ({@code 17},
     * {@code 1.8}, scalac's {@code jvm-1.8}), or null when it is not one or sits below
     * {@link #MIN_RELEASE}.
     */
    public static Integer parseRelease(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.startsWith("jvm-")) {
            text = text.substring("jvm-".length());
        }
        if (text.startsWith("1.")) {
            text = text.substring("1.".length());
        }
        try {
            int release = Integer.parseInt(text);
            return release < MIN_RELEASE ? null : release;
        } catch (NumberFormatException notARelease) {
            return null;
        }
    }

    /**
     * The release an explicit override names for the DUMP, or null when it names none.
     *
     * <p>The plugins' {@code jdkRelease} knob answers two questions at once: which release
     * the API layer resolves escapes against, and — since it is the only place a build can
     * state it — which release the application runs on. The second is what the dump records,
     * and it is the escape hatch for the case the derivation cannot see: a build that
     * compiles {@code --release 11} but ships on a 21 runtime says so here.
     *
     * <p>Zero is not that statement. It means "switch the API layer off", so the dump keeps
     * its derived value rather than going silent and taking JDK-move detection down with the
     * layer. Anything below {@link #MIN_RELEASE} is dropped for a harder reason: a dump
     * naming it would send upgrade-check to ask ct.sym for a release it has never carried,
     * failing the whole run.
     */
    public static Integer overrideRelease(Integer override) {
        return override != null && override >= MIN_RELEASE ? override : null;
    }

    /**
     * Clamps a wanted {@code --jdk-release} value to what {@code jdk}'s ct.sym can serve,
     * logging the decision through {@code log}. The plugins run on a JVM by definition, so the
     * JDK API layer defaults ON there (unlike the opt-in CLI flag): the build knows its JDK
     * authoritatively. A JDK's ct.sym does not contain its own release, so the highest servable
     * release is that JDK's feature release minus one; clamping DOWN is the conservative
     * direction (members added after the clamped release resolve NotFound on both sides of the
     * check and stay unreported, the same direction as Unknown). Over-claiming is the direction
     * that loses findings silently, which is why nothing here ever rounds up.
     *
     * @param target the wanted release, or null/&lt;=0 to disable the layer
     * @param jdk the JDK supplying ct.sym, normally {@link JdkSource#current()}
     * @return the release to pass as {@code --jdk-release}, or null to omit the flag
     */
    public static Integer effectiveJdkRelease(Integer target, JdkSource jdk, Consumer<String> log) {
        if (target == null || target <= 0) {
            return null;
        }
        int ctSymMax = jdk.feature() - 1;
        int effective = Math.min(target, ctSymMax);
        // Two different reasons, two different messages. Folding them made a below-floor
        // release report a missing ct.sym, sending the user to inspect a JDK that was fine.
        if (effective < MIN_RELEASE) {
            log.accept("uika: skipping the JDK API layer (release " + effective
                    + " is below the lowest release ct.sym serves, " + MIN_RELEASE + ")");
            return null;
        }
        if (!Files.isRegularFile(jdk.home().resolve("lib").resolve("ct.sym"))) {
            log.accept("uika: skipping the JDK API layer (no usable ct.sym in " + jdk.home() + ")");
            return null;
        }
        if (effective < target) {
            log.accept("uika: JDK API layer clamped to release " + effective
                    + " (the ct.sym in " + jdk.home() + " has no release " + target + ")");
        }
        return effective;
    }

    /**
     * The test-JVM argument that records every class load (stack traces included) into a
     * JFR recording under {@code dir}. JFR generates pid-unique file names when the
     * filename option names a directory, so parallel test JVMs never clobber each other,
     * and the comma-delimited option syntax has no problem with Windows drive colons —
     * both problems the earlier {@code -Xlog} injection had to solve by hand. The one
     * character that syntax cannot carry bare is the comma itself, its own delimiter: an
     * unquoted comma in the path truncates {@code filename=} there and the JVM still
     * starts (exit 0), recording to the truncated path while the real directory stays
     * empty — silent evidence loss, so the value is quoted when the path contains one
     * (the JVM strips the quotes; verified against a real StartFlightRecording run). The
     * event settings syntax needs JDK 17+, the project's build floor. {@code stackTrace}
     * is spelled out because the triggers ({@code via ... from ...}) come from those
     * stacks and a custom JFC could have disabled the default. The Gradle, sbt and Mill
     * plugins compose the argument here so those three cannot drift. Maven users hand-write
     * an argLine (no mojo can inject into surefire), so its documented recipe must be kept
     * in sync with this format by hand.
     */
    public static String jfrClassLoadJvmArg(Path dir) {
        String filename = dir.toString();
        if (filename.contains(",")) {
            filename = "\"" + filename + "\"";
        }
        return "-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename="
                + filename;
    }

    /**
     * Runs {@code uika upgrade-check}, passing each line of the CLI's merged stdout/stderr to
     * {@code output}. The report must go through the build tool's own logger: a child process
     * that inherits file descriptors writes past the tool's log capture, so under a Gradle
     * daemon, an sbt server, or mvnd the user would never see it. Returns the CLI exit code:
     * 0 = clean, 1 = violations found (per {@code failOn}), 2 = error.
     *
     * @param failOn when the CLI should exit non-zero ({@code never}, {@code reachable}, or
     *     {@code any}); passed through as {@code --fail-on}. Null or blank leaves the CLI default.
     * @param excludeFiles TOML files of known false positives to suppress, passed through as
     *     repeated {@code --exclude-file} flags. Null or empty adds nothing.
     * @param jdkRelease resolve JDK hierarchy escapes against this API release (pass a value
     *     from {@link #effectiveJdkRelease}); null omits the flag.
     * @param jdk the JDK whose home is exported as UIKA_JDK, so the CLI reads that JDK's
     *     ct.sym regardless of the caller's JAVA_HOME. Must be the one the release was
     *     clamped against.
     * @param classLoadLogs runtime class-load logs (files or directories) from a test run of
     *     the current, not yet upgraded build, passed through as repeated
     *     {@code --class-load-log} flags. Null or empty adds nothing.
     * @param draftExcludeFile where the CLI writes draft exclude rules for symbols never
     *     observed loading ({@code --draft-exclude-file}); null omits the flag. The CLI
     *     rejects it without at least one class-load log.
     */
    public static int runUpgradeCheck(Path binary, Path before, Path after, String failOn,
            List<Path> excludeFiles, Integer jdkRelease, JdkSource jdk, List<Path> classLoadLogs,
            Path draftExcludeFile, Consumer<String> output)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                binary.toString(), "upgrade-check",
                "--before", before.toString(),
                "--after", after.toString()));
        if (failOn != null && !failOn.isBlank()) {
            command.add("--fail-on");
            command.add(failOn);
        }
        if (excludeFiles != null) {
            for (Path excludeFile : excludeFiles) {
                command.add("--exclude-file");
                command.add(excludeFile.toString());
            }
        }
        if (jdkRelease != null) {
            command.add("--jdk-release");
            command.add(jdkRelease.toString());
        }
        if (classLoadLogs != null) {
            for (Path classLoadLog : classLoadLogs) {
                command.add("--class-load-log");
                command.add(classLoadLog.toString());
            }
        }
        if (draftExcludeFile != null) {
            command.add("--draft-exclude-file");
            command.add(draftExcludeFile.toString());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        if (jdkRelease != null) {
            builder.environment().put("UIKA_JDK", jdk.home().toString());
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                output.accept(line);
            }
        }
        return process.waitFor();
    }
}
