package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;

class UikaExceptionTest {
    /** Each layer adds its context once, so a nested failure reads as one chain. */
    @Test
    void theMessageIsTheWholeCauseChain() {
        UikaException inner = new UikaException("exclude rule \"a/B\" is missing a reason");
        assertEquals(
                "invalid exclude file e.toml: exclude rule \"a/B\" is missing a reason",
                new UikaException("invalid exclude file e.toml", inner).getMessage());
    }

    /** The OS wording, not the JDK's exception text, which is only the path again. */
    @Test
    void fileErrorsReadLikeTheOperatingSystemsOwn() {
        assertEquals("No such file or directory", UikaException.describe(new NoSuchFileException("/x")));
        assertEquals("Permission denied", UikaException.describe(new AccessDeniedException("/x")));
        assertEquals("File exists", UikaException.describe(new FileAlreadyExistsException("/x")));
        assertEquals(
                "Not a directory",
                UikaException.describe(new FileSystemException("/x/y", null, "Not a directory")));
    }

    /** Other file system reasons keep the JDK's text, which names the file. */
    @Test
    void anotherFileSystemReasonKeepsTheFileName() {
        assertEquals(
                "/x/y: Too many levels of symbolic links",
                UikaException.describe(new FileSystemException("/x/y", null, "Too many levels of symbolic links")));
    }

    @Test
    void aCauseWithoutAMessageIsNamedByItsType() {
        assertEquals("cannot read x: IOException", new UikaException("cannot read x", new IOException()).getMessage());
        assertEquals("cannot read x: disk on fire", new UikaException("cannot read x", new IOException("disk on fire")).getMessage());
    }
}
