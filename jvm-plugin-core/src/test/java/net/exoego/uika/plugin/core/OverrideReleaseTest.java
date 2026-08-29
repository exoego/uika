package net.exoego.uika.plugin.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The dump side of the `jdkRelease` knob, which used to drop a below-floor value in
/// silence while the check side explained the same decision.
///
/// A separate file rather than more cases in the release-derivation suite, so this can land
/// independently; the two belong together once both are in.
final class OverrideReleaseTest {
    @Test
    void aDroppedOverrideExplainsItselfOnlyWhenItWasAMistake() {
        var log = new ArrayList<String>();

        // Zero is the documented opt-out, so it stays as silent here as on the check side.
        assertNull(UikaCli.overrideRelease(0, log::add));
        assertTrue(log.isEmpty(), () -> "zero explained itself: " + log);

        // A positive value under the floor is a mistake, and the user gets one line saying
        // what happened to it instead of a dump that quietly ignored the setting.
        assertNull(UikaCli.overrideRelease(5, log::add));
        assertEquals(1, log.size(), () -> "expected exactly one line: " + log);
        assertTrue(log.get(0).contains("5"), () -> "the message drops the value: " + log);

        // A servable value passes through without a word, like the one-argument form.
        log.clear();
        assertEquals(17, UikaCli.overrideRelease(17, log::add));
        assertTrue(log.isEmpty(), () -> "an accepted override logged: " + log);

        // Null is "no override", not a mistake.
        assertNull(UikaCli.overrideRelease(null, log::add));
        assertTrue(log.isEmpty(), () -> "an absent override logged: " + log);
    }
}
