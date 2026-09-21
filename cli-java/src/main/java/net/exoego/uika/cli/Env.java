package net.exoego.uika.cli;

import java.util.HashMap;
import java.util.Map;

/** Environment lookup with a test override, since a JVM cannot change its own environment. */
final class Env {
    private static final Map<String, String> OVERRIDES = new HashMap<>();

    private Env() {}

    static synchronized String get(String name) {
        if (OVERRIDES.containsKey(name)) {
            return OVERRIDES.get(name);
        }
        return System.getenv(name);
    }

    /** A null value reads as unset. */
    static synchronized void override(String name, String value) {
        OVERRIDES.put(name, value);
    }

    static synchronized void clearOverrides() {
        OVERRIDES.clear();
    }
}
