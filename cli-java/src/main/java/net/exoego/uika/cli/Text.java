package net.exoego.uika.cli;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Text helpers that give the same output on every platform. */
final class Text {
    private Text() {}

    /**
     * Orders by Unicode code point, which is UTF-8 byte order. {@link String#compareTo} orders
     * by UTF-16 unit instead, and the two disagree above the surrogate range.
     */
    static int compareUtf8(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /**
     * The shortest decimal that reads back as the same double, the digits serde's errors print.
     * Double.toString is not that before JDK 19.
     */
    static BigDecimal shortestDecimal(double value) {
        BigDecimal exact = new BigDecimal(value);
        for (int precision = 1; ; precision++) {
            BigDecimal shortest = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (shortest.doubleValue() == value) {
                return shortest;
            }
        }
    }
}
