package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ports the `verdicts.rs` tests. */
class VerdictsTest {
    @TempDir
    Path dir;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void streamsOneJsonLinePerRecord() throws Exception {
        Path path = dir.resolve("verdicts.jsonl");

        Verdicts.Writer w = Verdicts.Writer.create(path.toString());
        SymbolRef broken =
                new SymbolRef(RefKind.METHOD, Intern.intern("com/example/Owner"), MemberKey.of("gone", "()V"), Boolean.TRUE, null, null);
        w.record(Intern.intern("app.jar"), Intern.intern("com/example/Caller"), broken, "broken", "method removed");
        SymbolRef ok = SymbolRef.ofClass(Intern.intern("com/example/Owner"));
        w.record(Intern.intern("app.jar"), Intern.intern("com/example/Caller"), ok, "ok", null);
        assertNull(w.finish());

        String text = Files.readString(path, StandardCharsets.UTF_8);
        List<String> lines = text.lines().toList();
        assertEquals(2, lines.size());
        Map<String, Object> first = object(Json.parse(lines.get(0)));
        assertEquals("broken", first.get("verdict"));
        assertEquals("method removed", first.get("reason"));
        assertEquals("com/example/Owner", object(first.get("reference")).get("owner"));
        assertEquals("gone", object(object(first.get("reference")).get("member")).get("name"));
        assertEquals(Boolean.TRUE, object(first.get("reference")).get("expected_static"));
        Map<String, Object> second = object(Json.parse(lines.get(1)));
        assertEquals("ok", second.get("verdict"));
        assertEquals("class", object(second.get("reference")).get("kind"));
        assertFalse(second.containsKey("reason"));

        // The stream is an evaluation surface other tools parse, so the bytes are pinned too:
        // serde field order, absent fields skipped, one line feed per record.
        assertEquals(
                "{\"source\":\"app.jar\",\"source_class\":\"com/example/Caller\",\"reference\":{\"kind\":\"method\","
                        + "\"owner\":\"com/example/Owner\",\"member\":{\"name\":\"gone\",\"descriptor\":\"()V\"},"
                        + "\"expected_static\":true},\"verdict\":\"broken\",\"reason\":\"method removed\"}\n"
                        + "{\"source\":\"app.jar\",\"source_class\":\"com/example/Caller\",\"reference\":{\"kind\":\"class\","
                        + "\"owner\":\"com/example/Owner\"},\"verdict\":\"ok\"}\n",
                text);
    }

    @Test
    void writeFailureLatchesAndFinishReportsIt() {
        int[] writes = {0};
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                writes[0]++;
                throw new IOException("disk full");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                writes[0]++;
                throw new IOException("disk full");
            }
        };

        Verdicts.Writer w = Verdicts.Writer.to(failing);
        SymbolRef r = SymbolRef.ofClass(Intern.intern("com/example/Owner"));
        w.record(Intern.intern("a.jar"), Intern.intern("com/example/C"), r, "ok", null);
        // Latched, so further records are no-ops and finish surfaces the first error.
        w.record(Intern.intern("a.jar"), Intern.intern("com/example/C"), r, "ok", null);
        assertEquals(1, writes[0], "nothing is written after the first failure");
        String msg = w.finish();
        assertNotNull(msg, "failure must be reported");
        assertTrue(msg.contains("stream truncated"), msg);
        assertTrue(msg.contains("disk full"), msg);
        assertEquals("verdicts output failed, stream truncated: disk full", msg);
    }

    /** A failure that only shows at flush time is a truncated stream all the same. */
    @Test
    void aFlushFailureIsReportedByFinish() {
        OutputStream failsOnFlush = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                throw new IOException("quota exceeded");
            }
        };
        Verdicts.Writer w = Verdicts.Writer.to(failsOnFlush);
        w.record(Intern.intern("a.jar"), Intern.intern("com/example/C"), SymbolRef.ofClass(Intern.intern("com/example/Owner")), "ok", null);
        assertEquals("verdicts output failed, stream truncated: quota exceeded", w.finish());
    }

    /** The module label is only on per-module runs, so plain check records stay unchanged. */
    @Test
    void labelsRecordsWithTheModuleOnlyWhileOneIsSet() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Verdicts.Writer w = Verdicts.Writer.to(out);
        SymbolRef field = new SymbolRef(
                RefKind.FIELD, Intern.intern("com/example/Owner"), MemberKey.of("count", "I"), Boolean.FALSE, Boolean.TRUE, null);
        SymbolRef created = new SymbolRef(RefKind.CLASS, Intern.intern("com/example/Owner"), MemberKey.NONE, null, null, Boolean.TRUE);
        w.record(Intern.intern("a.jar"), Intern.intern("com/example/C"), field, "unknown", null);
        w.setModule(":app,:lib");
        w.record(Intern.intern("a.jar"), Intern.intern("com/example/C"), created, "broken", "class became abstract");
        w.setModule(null);
        w.record(Intern.intern("a.jar"), Intern.intern("com/example/C"), created, "ok", null);
        assertNull(w.finish());

        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(3, lines.size());
        assertEquals(
                "{\"source\":\"a.jar\",\"source_class\":\"com/example/C\",\"reference\":{\"kind\":\"field\","
                        + "\"owner\":\"com/example/Owner\",\"member\":{\"name\":\"count\",\"descriptor\":\"I\"},"
                        + "\"expected_static\":false,\"field_write\":true},\"verdict\":\"unknown\"}",
                lines.get(0));
        assertEquals(
                "{\"source\":\"a.jar\",\"source_class\":\"com/example/C\",\"reference\":{\"kind\":\"class\","
                        + "\"owner\":\"com/example/Owner\",\"instantiated\":true},\"verdict\":\"broken\","
                        + "\"reason\":\"class became abstract\",\"module\":\":app,:lib\"}",
                lines.get(1));
        assertFalse(object(Json.parse(lines.get(2))).containsKey("module"));
    }

    @Test
    void anUncreatableOutputEndsTheCommand() {
        String path = dir.resolve("no/such/dir/verdicts.jsonl").toString();
        UikaException e = assertThrows(UikaException.class, () -> Verdicts.Writer.create(path));
        assertEquals("cannot create verdicts output " + path + ": No such file or directory (os error 2)", e.getMessage());
    }
}
