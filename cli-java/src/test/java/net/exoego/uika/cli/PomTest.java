package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PomTest {
    /**
     * The shape from https://github.com/exoego/uika/issues/96. google-auth declares slf4j-api
     * optional inside an activeByDefault profile, not in the top-level dependencies.
     */
    @Test
    void optionalInsideAProfileCounts() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                    </dependency>
                  </dependencies>
                  <profiles>
                    <profile>
                      <id>slf4j2x</id>
                      <activation><activeByDefault>true</activeByDefault></activation>
                      <dependencies>
                        <dependency>
                          <groupId>org.slf4j</groupId>
                          <artifactId>slf4j-api</artifactId>
                          <version>${project.slf4j.version}</version>
                          <optional>true</optional>
                        </dependency>
                      </dependencies>
                    </profile>
                  </profiles>
                </project>
                """;
        assertTrue(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"));
        assertFalse(Pom.declaresOptional(pom, "com.google.guava", "guava"));
    }

    @Test
    void plainRequiredDependencyIsNotOptional() {
        String pom = """
                <project><dependencies>
                  <dependency>
                    <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                    <version>2.0.18</version>
                  </dependency>
                </dependencies></project>
                """;
        assertFalse(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"));
    }

    @Test
    void optionalFalseIsNotOptional() {
        String pom = """
                <project><dependencies>
                  <dependency>
                    <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                    <optional>false</optional>
                  </dependency>
                </dependencies></project>
                """;
        assertFalse(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"));
    }

    /**
     * The shape of netty-transport-native-epoll, which is in real caches. The plain coordinate
     * is a hard top-level requirement and the classifier-ed native variant is optional inside
     * OS-gated profiles. Either rule alone answers this correctly. Both are pinned because
     * each covers cases the other does not.
     */
    @Test
    void anUnconditionalRequirementBeatsAProfileScopedOptional() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>io.netty</groupId><artifactId>netty-transport-native-unix-common</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                  <profiles><profile>
                    <id>linux</id>
                    <dependencies>
                      <dependency>
                        <groupId>io.netty</groupId><artifactId>netty-transport-native-unix-common</artifactId>
                        <classifier>${jni.classifier}</classifier>
                        <optional>true</optional>
                      </dependency>
                    </dependencies>
                  </profile></profiles>
                </project>
                """;
        assertFalse(Pom.declaresOptional(pom, "io.netty", "netty-transport-native-unix-common"));
    }

    /**
     * A classifier names a different artifact file, so its optionality says nothing about the
     * plain coordinate.
     */
    @Test
    void aClassifierEdOptionalDoesNotCoverThePlainCoordinate() {
        String pom = """
                <project><dependencies>
                  <dependency>
                    <groupId>g</groupId><artifactId>a</artifactId>
                    <classifier>linux-x86_64</classifier><optional>true</optional>
                  </dependency>
                </dependencies></project>
                """;
        assertFalse(Pom.declaresOptional(pom, "g", "a"));
    }

    /**
     * Only an ALWAYS-ACTIVE requirement overrides. Both of google-auth's slf4j-api
     * declarations sit in profiles, so the motivating case still reads optional.
     */
    @Test
    void aProfileScopedRequirementDoesNotOverride() {
        String pom = """
                <project><profiles>
                  <profile>
                    <id>slf4j2x</id>
                    <activation><activeByDefault>true</activeByDefault></activation>
                    <dependencies><dependency>
                      <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                      <optional>true</optional>
                    </dependency></dependencies>
                  </profile>
                  <profile>
                    <id>slf4j2x-test</id>
                    <dependencies><dependency>
                      <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                    </dependency></dependencies>
                  </profile>
                </profiles></project>
                """;
        assertTrue(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"));
    }

    /** A plugin's {@code <dependencies>} is the plugin's classpath, never this artifact's. */
    @Test
    void pluginDependenciesAreNotThisArtifactsDependencies() {
        for (String section : List.of(
                "<build><plugins><plugin>",
                "<build><pluginManagement><plugins><plugin>",
                "<reporting><plugins><plugin>")) {
            String pom = "<project>" + section + "<dependencies><dependency>\n"
                    + "  <groupId>g</groupId><artifactId>a</artifactId><optional>true</optional>\n"
                    + "</dependency></dependencies></project>";
            assertFalse(Pom.declaresOptional(pom, "g", "a"), section);
        }
    }

    /**
     * XML allows whitespace before the {@code >} of an end tag, so {@code elementEnd} can fail
     * on a well-formed POM. Losing the {@code <dependencyManagement>} bound must not promote
     * its entries to real declarations. An unterminated element swallows the remainder instead.
     */
    @Test
    void anUnboundedDependencyManagementStillExcludesItsEntries() {
        for (String close : List.of("</dependencyManagement >", "")) {
            String pom = """
                    <project>
                      <dependencyManagement><dependencies>
                        <dependency>
                          <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                          <optional>true</optional>
                        </dependency>
                      </dependencies>{close}
                    </project>""".replace("{close}", close);
            assertFalse(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"), close);
        }
    }

    /** A processing instruction's content is not markup, the same as a comment or CDATA. */
    @Test
    void processingInstructionContentsAreNotDeclarations() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <?tool <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                         <optional>true</optional></dependency> ?>
                  <dependencies><dependency>
                    <groupId>com.example</groupId><artifactId>thing</artifactId><optional>true</optional>
                  </dependency></dependencies>
                </project>""";
        assertFalse(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"));
        assertTrue(Pom.declaresOptional(pom, "com.example", "thing"));
    }

    /**
     * dependencyManagement sets versions for dependencies declared elsewhere. An optional
     * flag there does not make this artifact's own dependency optional.
     */
    @Test
    void dependencyManagementEntriesAreIgnored() {
        String pom = """
                <project>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                      <version>2.0.18</version><optional>true</optional>
                    </dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;
        assertFalse(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"));
    }

    /**
     * A POM's prose is free to carry example XML in CDATA. Scanning it would be the one way
     * this file reports optional for a dependency the artifact never declared.
     */
    @Test
    void cdataContentsAreNotDeclarations() {
        String pom = """
                <project>
                  <description><![CDATA[
                    To enable logging add:
                    <dependency>
                      <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                      <optional>true</optional>
                    </dependency>
                  ]]></description>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>thing</artifactId>
                      <optional>true</optional>
                    </dependency>
                  </dependencies>
                </project>
                """;
        assertFalse(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"));
        assertTrue(Pom.declaresOptional(pom, "com.example", "thing"));
    }

    /**
     * Namespace-prefixed element names are not matched. Reading as not-optional keeps the
     * original advice, which is the safe direction.
     */
    @Test
    void namespacePrefixedElementsReadAsNotOptional() {
        String pom = """
                <m:project xmlns:m="http://maven.apache.org/POM/4.0.0"><m:dependencies>
                  <m:dependency>
                    <m:groupId>g</m:groupId><m:artifactId>a</m:artifactId>
                    <m:optional>true</m:optional>
                  </m:dependency>
                </m:dependencies></m:project>
                """;
        assertFalse(Pom.declaresOptional(pom, "g", "a"));
    }

    @Test
    void commentedOutDeclarationsDoNotCount() {
        String pom = """
                <project><dependencies>
                  <!-- <dependency>
                    <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                    <optional>true</optional>
                  </dependency> -->
                </dependencies></project>
                """;
        assertFalse(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"));
    }

    /**
     * An {@code <exclusions>} block carries its own groupId/artifactId. Reading those as the
     * dependency's own coordinate would attribute a neighbouring dependency's optional flag
     * to whatever it excludes.
     */
    @Test
    void exclusionsDoNotShadowTheDependencyCoordinate() {
        String pom = """
                <project><dependencies>
                  <dependency>
                    <groupId>com.example</groupId><artifactId>thing</artifactId>
                    <optional>true</optional>
                    <exclusions>
                      <exclusion>
                        <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                      </exclusion>
                    </exclusions>
                  </dependency>
                </dependencies></project>
                """;
        assertTrue(Pom.declaresOptional(pom, "com.example", "thing"));
        assertFalse(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"));
    }

    /**
     * netty-common puts {@code <optional>} after {@code <exclusions>}. Truncating the block at
     * the exclusions (rather than blanking them) hid the flag on 18 of 4000 cached real POMs.
     */
    @Test
    void optionalAfterExclusionsIsFound() {
        String pom = """
                <project><dependencies>
                  <dependency>
                    <groupId>org.apache.logging.log4j</groupId>
                    <artifactId>log4j-1.2-api</artifactId>
                    <scope>compile</scope>
                    <exclusions>
                      <exclusion><artifactId>mail</artifactId><groupId>javax.mail</groupId></exclusion>
                      <exclusion><artifactId>jms</artifactId><groupId>javax.jms</groupId></exclusion>
                    </exclusions>
                    <optional>true</optional>
                  </dependency>
                </dependencies></project>
                """;
        assertTrue(Pom.declaresOptional(pom, "org.apache.logging.log4j", "log4j-1.2-api"));
        assertFalse(Pom.declaresOptional(pom, "javax.mail", "mail"));
    }

    /**
     * A self-closing {@code <dependency/>} declares nothing and must be stepped over rather
     * than opening an element whose {@code elementEnd} then swallows the real declaration
     * after it.
     */
    @Test
    void attributesAndSelfClosingTagsDoNotDerailTheScan() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <dependencies>
                    <dependency/>
                    <dependency scope="compile" />
                    <dependency >
                      <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                      <optional>true</optional>
                    </dependency>
                  </dependencies>
                </project>
                """;
        assertTrue(Pom.declaresOptional(pom, "org.slf4j", "slf4j-api"));
    }

    /**
     * Maven and Coursier put the POM next to the JAR. Gradle gives each artifact of a version
     * its own checksum directory, so the POM is one directory over. Both are probed.
     */
    @Test
    void locateFindsThePomInBothCacheLayouts(@TempDir Path root) throws IOException {
        Path m2 = root.resolve("m2/com/example/thing/1.0");
        Files.createDirectories(m2);
        Files.write(m2.resolve("thing-1.0.jar"), new byte[0]);
        Files.writeString(m2.resolve("thing-1.0.pom"), "<project/>", StandardCharsets.UTF_8);
        assertEquals(
                m2.resolve("thing-1.0.pom").toString(),
                Pom.locate(m2.resolve("thing-1.0.jar").toString(), "thing", "1.0"));

        Path version = root.resolve("gradle/com.example/thing/1.0");
        Path jarDir = version.resolve("aaaaaaaa");
        Path pomDir = version.resolve("bbbbbbbb");
        Files.createDirectories(jarDir);
        Files.createDirectories(pomDir);
        Files.write(jarDir.resolve("thing-1.0.jar"), new byte[0]);
        Files.writeString(pomDir.resolve("thing-1.0.pom"), "<project/>", StandardCharsets.UTF_8);
        assertEquals(
                pomDir.resolve("thing-1.0.pom").toString(),
                Pom.locate(jarDir.resolve("thing-1.0.jar").toString(), "thing", "1.0"));

        // A POM for another version, present and reachable by the same walk, must not answer.
        Files.writeString(pomDir.resolve("thing-2.0.pom"), "<project/>", StandardCharsets.UTF_8);
        assertNull(Pom.locate(jarDir.resolve("thing-1.0.jar").toString(), "thing", "2.0"));
        // A bare file name would make the sibling probe read relative to the CWD.
        assertNull(Pom.locate("thing-1.0.jar", "thing", "1.0"));
    }

    /**
     * The file name alone does not identify an artifact. Outside a cache layout the walk must
     * stay quiet rather than answer with a same-named POM belonging to another group.
     */
    @Test
    void locateDoesNotCrossIntoAnUnrelatedDirectory(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("libs"));
        Files.createDirectories(root.resolve("other"));
        Files.write(root.resolve("libs/thing-1.0.jar"), new byte[0]);
        Files.writeString(root.resolve("other/thing-1.0.pom"), "<project/>", StandardCharsets.UTF_8);
        assertNull(Pom.locate(root.resolve("libs/thing-1.0.jar").toString(), "thing", "1.0"));
    }

    @Test
    void truncatedPomDoesNotPanic() {
        assertFalse(Pom.declaresOptional("<project><dependencies><dependency>", "g", "n"));
        assertFalse(Pom.declaresOptional("", "g", "n"));
        assertFalse(Pom.declaresOptional("<!-- unterminated", "g", "n"));
    }
}
