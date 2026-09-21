package net.exoego.uika.cli;

/** A failure that ends the command with exit code 2. The message is the whole cause chain. */
final class UikaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UikaException(String message) {
        super(message);
    }

    UikaException(String context, Throwable cause) {
        super(context + ": " + describe(cause), cause);
    }

    static String describe(Throwable cause) {
        if (cause instanceof UikaException) {
            return cause.getMessage();
        }
        if (cause instanceof java.nio.file.NoSuchFileException) {
            return "No such file or directory (os error 2)";
        }
        if (cause instanceof java.nio.file.AccessDeniedException) {
            return "Permission denied (os error 13)";
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
