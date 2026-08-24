package example;

// Calls into the compared library so the dump has an application class with a real
// reference to check, and so a dump that dropped :compile-path is detectable.
public final class Consumer {
    public static String pad(String s) {
        return org.apache.commons.lang3.StringUtils.leftPad(s, 8, '.');
    }
}
