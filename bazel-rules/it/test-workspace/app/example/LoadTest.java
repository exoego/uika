package example;

public final class LoadTest {
    public static void main(String[] args) throws Exception {
        // The broken references this workspace expects live in this selenium class, so
        // loading it is what moves them out of the not-proven-reachable tier. Loaded
        // without initializing, because a jdk.ClassLoad event needs no static initializer
        // to have run and running one here would only add ways for the test to fail.
        Class.forName("org.openqa.selenium.net.UrlChecker", false,
                LoadTest.class.getClassLoader());
    }
}
