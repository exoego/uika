package net.exoego.uika.cli;

/**
 * Which artifacts a violation involves and how to fix it. Coordinates are "group:name[:version]".
 *
 * @param referencedBy coordinate of the artifact whose class holds the broken reference; null
 *     for app build outputs
 * @param removedBy coordinate whose version change removed the symbol
 */
record Suggestion(String referencedBy, String removedBy, String before, String after, String advice) {}
