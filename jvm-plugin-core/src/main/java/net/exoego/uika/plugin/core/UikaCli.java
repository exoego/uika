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
     */
    public static int runUpgradeCheck(Path binary, Path before, Path after, String failOn,
            List<Path> excludeFiles, Consumer<String> output)
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
        ProcessBuilder builder = new ProcessBuilder(command);
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
