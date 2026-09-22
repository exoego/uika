package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScratchTest {
    @AfterEach
    void clearEnvironment() {
        Env.clearOverrides();
    }

    @Test
    void theNameBufferIsReusedAndGrowsAtLeastTwofold() {
        Scratch scratch = new Scratch();
        byte[] initial = scratch.nameBytes(1);
        assertSame(initial, scratch.nameBytes(initial.length));
        byte[] doubled = scratch.nameBytes(initial.length + 1);
        assertEquals(initial.length * 2, doubled.length);
        assertSame(doubled, scratch.nameBytes(1));
        assertEquals(initial.length * 5, scratch.nameBytes(initial.length * 5).length);
    }

    @Test
    void uikaThreadsSetsTheWorkerCountOnlyWhenItIsAPositiveNumber() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        Env.override("UIKA_THREADS", "3");
        assertEquals(3, Scratch.threads());
        Env.override("UIKA_THREADS", " 5 ");
        assertEquals(5, Scratch.threads());
        for (String ignored : new String[] {"0", "-2", "many", "", null}) {
            Env.override("UIKA_THREADS", ignored);
            assertEquals(processors, Scratch.threads(), String.valueOf(ignored));
        }
    }
}
