package net.exoego.uika.plugin.core;

import java.util.List;

public final class ClasspathDump {
    private ClasspathDump() {}

    public static final class Artifact {
        private final String group;
        private final String name;
        private final String version;
        private final String file;
        private final String project;

        public Artifact(String group, String name, String version, String file) {
            this(group, name, version, file, null);
        }

        /**
         * @param project producing module path for a project/reactor dependency (":shared"),
         *     null for external artifacts. Lets uika substitute that module's classesDirs when
         *     the artifact file has not been built.
         */
        public Artifact(String group, String name, String version, String file, String project) {
            this.group = group;
            this.name = name;
            this.version = version;
            this.file = file;
            this.project = project;
        }

        public String group() {
            return group;
        }

        public String name() {
            return name;
        }

        public String version() {
            return version;
        }

        public String file() {
            return file;
        }

        public String project() {
            return project;
        }
    }

    public static final class Module {
        private final String path;
        private final List<String> classesDirs;
        private final List<Artifact> artifacts;
        private final Integer jdkRelease;

        public Module(String path, List<String> classesDirs, List<Artifact> artifacts) {
            this(path, classesDirs, artifacts, null);
        }

        /**
         * @param jdkRelease the API release THIS module compiles for, null when it declares
         *     none. Per module rather than per dump because a build is free to mix them, and
         *     upgrade-check pairs the releases module by module to decide which modules a JDK
         *     move has to be checked over.
         */
        public Module(String path, List<String> classesDirs, List<Artifact> artifacts,
                Integer jdkRelease) {
            this.path = path;
            this.classesDirs = classesDirs;
            this.artifacts = artifacts;
            this.jdkRelease = jdkRelease;
        }

        public String path() {
            return path;
        }

        public Integer jdkRelease() {
            return jdkRelease;
        }

        public List<String> classesDirs() {
            return classesDirs;
        }

        public List<Artifact> artifacts() {
            return artifacts;
        }
    }
}
