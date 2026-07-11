package net.exoego.uika.gradle;

import net.exoego.uika.plugin.core.ClasspathDump.Module;
import net.exoego.uika.plugin.core.DumpFormat;
import groovy.json.JsonSlurper;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Merge each module's JSON fragment and write deduplicated v2 output. */
@DisableCachingByDefault(because = "Merges small generated classpath fragments")
public abstract class MergeClasspathTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getFragments();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** Preferred prefix for the root table (usually the repository root). */
    @Input
    public abstract Property<String> getRootDirPath();

    @TaskAction
    @SuppressWarnings("unchecked")
    public void merge() throws IOException {
        List<Module> modules = new ArrayList<>();
        JsonSlurper slurper = new JsonSlurper();
        for (File fragment : getFragments().getFiles()) {
            String text = new String(Files.readAllBytes(fragment.toPath()), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                continue; // Module without Java-family plugins.
            }
            modules.add(DumpFormat.fromV1Module((Map<String, Object>) slurper.parseText(text)));
        }
        String json = DumpFormat.writeV2(modules, List.of(getRootDirPath().get()));
        File out = getOutputFile().get().getAsFile();
        File parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
        getLogger().lifecycle("uika classpath dump: {}", out);
    }
}
