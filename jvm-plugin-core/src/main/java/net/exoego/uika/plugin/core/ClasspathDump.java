package net.exoego.uika.plugin.core;

import java.util.List;

public final class ClasspathDump {
    private ClasspathDump() {}

    public static final class Artifact {
        private final String group;
        private final String name;
        private final String version;
        private final String file;

        public Artifact(String group, String name, String version, String file) {
            this.group = group;
            this.name = name;
            this.version = version;
            this.file = file;
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
    }

    public static final class Module {
        private final String path;
        private final List<String> classesDirs;
        private final List<Artifact> artifacts;

        public Module(String path, List<String> classesDirs, List<Artifact> artifacts) {
            this.path = path;
            this.classesDirs = classesDirs;
            this.artifacts = artifacts;
        }

        public String path() {
            return path;
        }

        public List<String> classesDirs() {
            return classesDirs;
        }

        public List<Artifact> artifacts() {
            return artifacts;
        }
    }
}
