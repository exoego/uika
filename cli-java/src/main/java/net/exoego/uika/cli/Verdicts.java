package net.exoego.uika.cli;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streaming JSON Lines output of every reference verdict ({@code --verdicts-json}).
 *
 * <p>The evaluation surface for tools/jvm-probe: each line carries the raw reference as it
 * sits in the constant pool plus the verdict it received, one line per reference record with
 * no call-site dedup. Lines are streamed as verdicts are computed, so memory stays flat.
 */
final class Verdicts {
    private Verdicts() {}

    static final class Writer {
        /** Null once the stream has failed. */
        private OutputStream out;
        /** The first failure, when the stream has failed. */
        private String failure;
        private String module;
        private final StringBuilder line = new StringBuilder(256);

        private Writer(OutputStream out) {
            this.out = out;
        }

        static Writer create(String path) {
            try {
                return new Writer(new BufferedOutputStream(Files.newOutputStream(Path.of(path)), 1 << 16));
            } catch (IOException e) {
                throw new UikaException("cannot create verdicts output " + path, e);
            }
        }

        static Writer to(OutputStream out) {
            return new Writer(out);
        }

        /** Labels subsequent records with the per-module run producing them; null clears it. */
        void setModule(String module) {
            this.module = module;
        }

        void record(int source, int sourceClass, SymbolRef reference, String verdict, String reason) {
            if (out == null) {
                return;
            }
            line.setLength(0);
            Json.Writer json = new Json.Writer(line, false);
            json.beginObject();
            json.key("source").sym(source);
            json.key("source_class").sym(sourceClass);
            json.key("reference");
            writeReference(json, reference);
            json.key("verdict").value(verdict);
            if (reason != null) {
                json.key("reason").value(reason);
            }
            if (module != null) {
                json.key("module").value(module);
            }
            json.endObject();
            line.append('\n');
            try {
                out.write(line.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // Latched: the scan goes on, but the command fails afterwards. A truncated
                // stream must never pass silently, the probe would answer-check only a prefix.
                failure = truncated(e);
                closeQuietly();
            }
        }

        /** Flushes, and returns the failure message if the stream failed at any point. */
        String finish() {
            if (out == null) {
                return failure;
            }
            try {
                out.flush();
                out.close();
                out = null;
                return null;
            } catch (IOException e) {
                out = null;
                return truncated(e);
            }
        }

        private void closeQuietly() {
            try {
                out.close();
            } catch (IOException ignored) {
                // the first failure is already latched
            }
            out = null;
        }

        private static String truncated(IOException e) {
            return "verdicts output failed, stream truncated: " + UikaException.describe(e);
        }
    }

    /** The reference object as every JSON surface spells it. */
    static void writeReference(Json.Writer json, SymbolRef reference) {
        json.beginObject();
        json.key("kind").value(reference.kind().json);
        json.key("owner").sym(reference.owner());
        if (reference.hasMember()) {
            json.key("member").beginObject();
            json.key("name").sym(MemberKey.name(reference.member()));
            json.key("descriptor").sym(MemberKey.descriptor(reference.member()));
            json.endObject();
        }
        if (reference.expectedStatic() != null) {
            json.key("expected_static").value(reference.expectedStatic());
        }
        if (reference.fieldWrite() != null) {
            json.key("field_write").value(reference.fieldWrite());
        }
        if (reference.instantiated() != null) {
            json.key("instantiated").value(reference.instantiated());
        }
        json.endObject();
    }
}
