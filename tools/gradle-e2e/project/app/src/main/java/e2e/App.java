package e2e;

import org.koin.logger.SLF4JLogger;

/** Reaches the class the break lives in, so the report marks it reachable. */
public final class App {
    public static void main(String[] args) {
        System.out.println(new SLF4JLogger());
    }
}
