package ak.dev.irc.app.research.enums;

/**
 * Distinguishes how a research source is referenced.
 */
public enum SourceType {
    URL,           // External web link
    DOI,           // Digital Object Identifier
    ISBN,          // Book reference
    MEDIA_FILE,    // A file uploaded as proof/source (stored in S3)
    MANUAL         // Free-text citation entered by researcher
}
