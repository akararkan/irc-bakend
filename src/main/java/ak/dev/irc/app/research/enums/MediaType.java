package ak.dev.irc.app.research.enums;

/**
 * Categorizes media attached to a research publication.
 */
public enum MediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,      // PDF, DOCX, PPTX, etc.
    SPREADSHEET,   // XLSX, CSV
    DATASET,       // Raw data files
    CODE,          // Source code / notebooks
    ARCHIVE,       // ZIP, TAR, etc.
    OTHER
}
