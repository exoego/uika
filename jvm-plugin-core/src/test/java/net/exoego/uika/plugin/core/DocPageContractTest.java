package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// The two rules every tool page follows, which two audits found broken by hand and nothing
/// enforced: the pages share one section order, and every knob a tool accepts is on its page.
///
/// The knob names are scraped from each tool's source, not from a list kept here, so a knob
/// added to a build fails this test until its page names it. Each scrape is anchored to the
/// declaration the tool reads the knob from (a Gradle property lookup, an sbt key, a Maven
/// `@Parameter` property, a Mill setting or command parameter, a Clojure key set, a Bazel macro
/// parameter), so a rename on either side fails as a missing name rather than passing on a
/// lookalike.
///
/// In the core test source set so both the Gradle and the Maven build run it. Both run one
/// level below the repository root, which is what the relative paths assume.
final class DocPageContractTest {
    private static final Path ROOT = Path.of("..");
    private static final List<String> PAGES = List.of("gradle", "sbt", "maven", "mill", "clojure", "leiningen", "bazel");

    @Test
    void everyToolPageHasTheSameSectionsInTheSameOrder() throws IOException {
        var order = List.of(
                "## PR gate on GitHub Actions",
                "### Caching the baseline",
                "## Options",
                "## Runtime load evidence (JFR)");
        for (String page : PAGES) {
            String text = page(page);
            var at = 0;
            for (String heading : order) {
                var found = text.indexOf("\n" + heading + "\n", at);
                assertTrue(found >= 0, "docs/" + page + ".md lacks \"" + heading + "\" after offset " + at
                        + ", or has it out of order");
                at = found + 1;
            }
        }
    }

    @Test
    void everyGradlePropertyIsOnTheGradlePage() throws IOException {
        String source = read("gradle-plugin/src/main/java/net/exoego/uika/gradle/UikaPlugin.java");
        var properties = scrape(source, "(?:findProperty|gradleProperty)\\(\"(uika[A-Za-z]+)\"\\)");
        assertAllOnPage("gradle", properties, name -> "-P" + name);
    }

    @Test
    void everySbtKeyIsOnTheSbtPage() throws IOException {
        String source = read("sbt-plugin/src/main/scala/net/exoego/uika/sbt/UikaPlugin.scala");
        var keys = scrape(source, "val (uika[A-Za-z]+) = (?:settingKey|taskKey|inputKey)\\[");
        assertAllOnPage("sbt", keys, name -> name);
    }

    @Test
    void everyMavenPropertyIsOnTheMavenPage() throws IOException {
        var properties = new LinkedHashSet<String>();
        try (var mojos = Files.list(ROOT.resolve("maven-plugin/src/main/java/net/exoego/uika/maven"))) {
            for (Path mojo : mojos.filter(p -> p.toString().endsWith("Mojo.java")).sorted().toList()) {
                properties.addAll(scrape(Files.readString(mojo), "property = \"(uika\\.[A-Za-z]+)\""));
            }
        }
        assertAllOnPage("maven", properties, name -> "-D" + name);
    }

    @Test
    void everyMillSettingAndParameterIsOnTheMillPage() throws IOException {
        String source = read("mill-plugin/src/net/exoego/uika/mill/UikaModule.scala");
        var settings = scrape(source, "(?m)^  def ([a-zA-Z]+): T\\[");
        assertTrue(settings.size() >= 6, "scraped too few Mill settings: " + settings);
        assertAllOnPage("mill", settings, name -> "def " + name);

        var parameters = new LinkedHashSet<String>();
        var command = Pattern.compile("def (?:dumpClasspath|upgradeCheck)\\(([^)]*)\\)").matcher(source);
        while (command.find()) {
            parameters.addAll(scrape(command.group(1), "([a-zA-Z]+):"));
        }
        parameters.remove("ev");
        assertTrue(parameters.size() >= 3, "scraped too few Mill parameters: " + parameters);
        assertAllOnPage("mill", parameters, name -> "--" + name);
    }

    @Test
    void everyClojureToolKeyIsOnTheClojurePage() throws IOException {
        String source = read("clojure-tool/src/exoego/uika.clj");
        var keys = new LinkedHashSet<String>();
        var set = Pattern.compile("(?:dump|check)-option-keys\\s+(?:\"[^\"]*\"\\s+)?#\\{([^}]*)\\}", Pattern.DOTALL).matcher(source);
        while (set.find()) {
            keys.addAll(scrape(set.group(1), "(:[a-z-]+)"));
        }
        assertTrue(keys.size() >= 10, "scraped too few Clojure tool keys: " + keys);
        assertAllOnPage("clojure", keys, name -> name);
    }

    @Test
    void everyLeiningenKeyIsOnTheLeiningenPage() throws IOException {
        String source = read("lein-plugin/src/leiningen/uika.clj");
        var set = Pattern.compile("option-keys\\s+\"[^\"]*\"\\s+#\\{([^}]*)\\}", Pattern.DOTALL).matcher(source);
        assertTrue(set.find(), "lein-plugin's option-keys set moved");
        var keys = scrape(set.group(1), "(:[a-z-]+)");
        assertTrue(keys.size() >= 8, "scraped too few Leiningen keys: " + keys);
        assertAllOnPage("leiningen", keys, name -> name);
    }

    @Test
    void everyBazelParameterIsOnTheBazelPage() throws IOException {
        String source = read("bazel-rules/defs.bzl");
        var parameters = new LinkedHashSet<String>();
        var macro = Pattern.compile("def (?:uika_dump|uika_upgrade_check)\\(([^)]*)\\)", Pattern.DOTALL).matcher(source);
        while (macro.find()) {
            parameters.addAll(scrape(macro.group(1), "\\b([a-z_]+) ?="));
        }
        assertTrue(parameters.size() >= 5, "scraped too few Bazel parameters: " + parameters);
        assertAllOnPage("bazel", parameters, name -> name);
    }

    private static void assertAllOnPage(String page, Set<String> names, java.util.function.UnaryOperator<String> spelling)
            throws IOException {
        String text = page(page);
        var missing = new ArrayList<String>();
        for (String name : names) {
            if (!text.contains(spelling.apply(name))) {
                missing.add(spelling.apply(name));
            }
        }
        assertTrue(missing.isEmpty(), "docs/" + page + ".md does not name " + missing);
    }

    private static Set<String> scrape(String text, String regex) {
        var found = new LinkedHashSet<String>();
        var m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static String page(String name) throws IOException {
        return read("docs/" + name + ".md");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative));
    }
}
