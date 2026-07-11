package net.exoego.uika.plugin.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
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
     * Runs {@code uika upgrade-check} inheriting stdout/stderr. Returns the CLI exit code:
     * 0 = clean, 1 = violations found, 2 = error.
     */
    public static int runUpgradeCheck(Path binary, Path before, Path after)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(List.of(
                binary.toString(), "upgrade-check",
                "--before", before.toString(),
                "--after", after.toString()));
        builder.inheritIO();
        return builder.start().waitFor();
    }
}
