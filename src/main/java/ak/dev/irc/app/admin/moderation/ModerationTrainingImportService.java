package ak.dev.irc.app.admin.moderation;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.ModerationMessages;
import ak.dev.irc.app.moderation.entity.ModerationTrainingExample;
import ak.dev.irc.app.moderation.enums.ModerationLabel;
import ak.dev.irc.app.moderation.enums.TrainingExampleSource;
import ak.dev.irc.app.moderation.service.ModerationTrainingService;
import ak.dev.irc.app.settings.privacy.service.KeywordNormalizer;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CSV bulk import for the moderation training dataset (§12.3's file-based
 * sibling of the one-at-a-time "teach it" forms). Two file kinds:
 *
 * <ul>
 *   <li><b>sentences</b> — {@code text} + the six label columns + {@code note};
 *       each row is one labeled sentence, the highest-quality training input.</li>
 *   <li><b>words</b> — {@code word} + the six label columns +
 *       {@code blocklist}/{@code severity} + {@code note}; each row is
 *       template-expanded into training sentences exactly like the single-word
 *       endpoint, and rows carrying {@code blocklist=yes} are simultaneously
 *       pushed to the platform keyword blocklist — the instant ban, so an admin
 *       importing a slur list gets certainty now and generalisation after the
 *       next retrain.</li>
 * </ul>
 *
 * <p>CSV UTF-8 only (author in Excel, export as "CSV UTF-8") — parsing
 * {@code .xlsx} natively would need a new library dependency, which the offline
 * build rules out. The parser is a plain RFC-4180 state machine: quoted fields,
 * doubled-quote escapes, embedded commas/newlines, CRLF, and a UTF-8 BOM.</p>
 *
 * <p>Validation runs over the whole file before anything is written. With the
 * default all-or-nothing mode a single bad row means nothing is applied; with
 * {@code allowPartial} the clean rows are applied and the rejects are returned
 * in the report. Every stored row carries source {@code ADMIN_IMPORT} so a bad
 * batch can be found (and deleted) via the dataset browser's source filter.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationTrainingImportService {

    static final int MAX_ROWS = 5000;
    private static final int MAX_TEXT = 5000;
    private static final int MAX_WORD = 100;
    private static final int MAX_NOTE = 300;

    private final ModerationTrainingService trainingService;
    private final PlatformKeywordService keywordService;
    private final KeywordNormalizer normalizer;

    public enum Kind { SENTENCES, WORDS }

    public record RowError(int row, String error) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportReport(String kind, int totalRows, int applied, int updated,
                               int blocklistAdded, int trainingRowsCreated,
                               boolean dryRun, List<RowError> errors) {
    }

    /** One validated data row, ready to apply. */
    private record ParsedRow(int rowNumber, String value, Map<String, Object> labels,
                             boolean blocklist, PlatformKeyword.Severity severity, String note) {
    }

    /**
     * Validates and (unless {@code dryRun}) applies an import file.
     * File-level problems — unreadable content, missing/unknown columns, too
     * many rows — throw {@code INVALID_IMPORT_FILE}; row-level problems land in
     * the report's {@code errors} list.
     */
    @Transactional
    public ImportReport importFile(byte[] fileBytes, String kindRaw,
                                   boolean dryRun, boolean allowPartial) {
        Kind kind = parseKind(kindRaw);
        List<List<String>> records = parseCsv(decode(fileBytes));
        if (records.isEmpty()) {
            throw invalidFile("the file is empty");
        }
        if (records.size() - 1 > MAX_ROWS) {
            throw invalidFile("%d data rows — the limit is %d per file, split the corpus"
                    .formatted(records.size() - 1, MAX_ROWS));
        }

        Map<String, Integer> header = readHeader(records.get(0), kind);
        String valueColumn = kind == Kind.SENTENCES ? "text" : "word";

        List<ParsedRow> clean = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            // Row numbers in the report are 1-based over data rows, matching what
            // an admin sees in a spreadsheet with the header as row 1 removed.
            parseRow(records.get(i), i, header, valueColumn, kind, clean, errors);
        }

        int applied = 0;
        int updated = 0;
        int blocklistAdded = 0;
        int trainingRowsCreated = 0;

        boolean apply = !dryRun && (errors.isEmpty() || allowPartial);
        if (apply) {
            for (ParsedRow row : clean) {
                if (trainingService.existsByText(row.value())) {
                    updated++;
                }
                if (kind == Kind.SENTENCES) {
                    trainingService.addExample(row.value(), row.labels(),
                            TrainingExampleSource.ADMIN_IMPORT, null, row.note());
                    trainingRowsCreated++;
                } else {
                    List<ModerationTrainingExample> created = trainingService.addWord(
                            row.value(), row.labels(), row.note(), TrainingExampleSource.ADMIN_IMPORT);
                    trainingRowsCreated += created.size();
                    if (row.blocklist()) {
                        keywordService.add(row.value(), row.severity(),
                                row.note() != null ? row.note() : "moderation CSV import");
                        blocklistAdded++;
                    }
                }
                applied++;
            }
            log.info("[MODERATION] CSV import ({}) applied {} of {} rows — {} training rows, {} blocklist terms",
                    kind, applied, records.size() - 1, trainingRowsCreated, blocklistAdded);
        }

        return new ImportReport(kind.name().toLowerCase(Locale.ROOT), records.size() - 1,
                applied, apply ? updated : 0, blocklistAdded, trainingRowsCreated,
                dryRun, errors);
    }

    // ── row validation ──────────────────────────────────────────────────

    private void parseRow(List<String> record, int rowNumber, Map<String, Integer> header,
                          String valueColumn, Kind kind,
                          List<ParsedRow> clean, List<RowError> errors) {
        if (record.size() == 1 && record.get(0).isBlank()) {
            return;   // stray blank line — not worth an error
        }
        if (record.size() > header.size()) {
            errors.add(new RowError(rowNumber, "has %d fields but the header has %d columns"
                    .formatted(record.size(), header.size())));
            return;
        }

        String value = cell(record, header.get(valueColumn)).trim();
        if (value.isBlank()) {
            errors.add(new RowError(rowNumber, valueColumn + " is blank"));
            return;
        }
        int maxValue = kind == Kind.SENTENCES ? MAX_TEXT : MAX_WORD;
        if (value.length() > maxValue) {
            errors.add(new RowError(rowNumber, "%s is longer than %d characters"
                    .formatted(valueColumn, maxValue)));
            return;
        }

        Map<String, Object> labels = new LinkedHashMap<>();
        for (ModerationLabel label : ModerationLabel.values()) {
            String raw = cell(record, header.get(label.wire())).trim();
            if (raw.isEmpty() || "0".equals(raw)) {
                labels.put(label.wire(), 0);
            } else if ("1".equals(raw)) {
                labels.put(label.wire(), 1);
            } else {
                errors.add(new RowError(rowNumber, "labels must be 0 or 1, got '%s' in column '%s'"
                        .formatted(raw, label.wire())));
                return;
            }
        }

        String note = header.containsKey("note") ? cell(record, header.get("note")).trim() : "";
        if (note.length() > MAX_NOTE) {
            errors.add(new RowError(rowNumber, "note is longer than %d characters".formatted(MAX_NOTE)));
            return;
        }

        boolean blocklist = false;
        PlatformKeyword.Severity severity = PlatformKeyword.Severity.BLOCK;
        if (kind == Kind.WORDS) {
            String rawBlocklist = header.containsKey("blocklist")
                    ? cell(record, header.get("blocklist")).trim().toLowerCase(Locale.ROOT) : "";
            switch (rawBlocklist) {
                case "", "no", "false", "0" -> blocklist = false;
                case "yes", "true", "1" -> blocklist = true;
                default -> {
                    errors.add(new RowError(rowNumber,
                            "blocklist must be yes or no, got '%s'".formatted(rawBlocklist)));
                    return;
                }
            }
            String rawSeverity = header.containsKey("severity")
                    ? cell(record, header.get("severity")).trim().toUpperCase(Locale.ROOT) : "";
            if (blocklist && !rawSeverity.isEmpty()) {
                try {
                    severity = PlatformKeyword.Severity.valueOf(rawSeverity);
                } catch (IllegalArgumentException bad) {
                    errors.add(new RowError(rowNumber,
                            "severity must be BLOCK or FLAG, got '%s'".formatted(rawSeverity)));
                    return;
                }
            }
            // Catch a term the blocklist would reject (normalises to nothing —
            // e.g. pure punctuation) here, so the apply phase can never throw
            // mid-batch and break all-or-nothing semantics.
            if (blocklist && normalizer.normalize(value).isBlank()) {
                errors.add(new RowError(rowNumber,
                        "word normalises to nothing and cannot be blocklisted"));
                return;
            }
        }

        clean.add(new ParsedRow(rowNumber, value, labels, blocklist, severity,
                note.isEmpty() ? null : note));
    }

    // ── header ──────────────────────────────────────────────────────────

    private Map<String, Integer> readHeader(List<String> headerRecord, Kind kind) {
        Set<String> allowed = new HashSet<>();
        allowed.add(kind == Kind.SENTENCES ? "text" : "word");
        for (ModerationLabel label : ModerationLabel.values()) {
            allowed.add(label.wire());
        }
        allowed.add("note");
        if (kind == Kind.WORDS) {
            allowed.add("blocklist");
            allowed.add("severity");
        }

        Map<String, Integer> header = new LinkedHashMap<>();
        for (int i = 0; i < headerRecord.size(); i++) {
            String name = headerRecord.get(i).trim().toLowerCase(Locale.ROOT);
            if (!allowed.contains(name)) {
                throw invalidFile("unknown column '%s' — allowed: %s"
                        .formatted(name, String.join(", ", allowed)));
            }
            if (header.put(name, i) != null) {
                throw invalidFile("duplicate column '%s'".formatted(name));
            }
        }
        String valueColumn = kind == Kind.SENTENCES ? "text" : "word";
        if (!header.containsKey(valueColumn)) {
            throw invalidFile("missing required column '%s'".formatted(valueColumn));
        }
        for (ModerationLabel label : ModerationLabel.values()) {
            if (!header.containsKey(label.wire())) {
                throw invalidFile("missing label column '%s' — all six label columns are required"
                        .formatted(label.wire()));
            }
        }
        return header;
    }

    // ── CSV mechanics (pure JDK — the offline build forbids new deps) ───

    private static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalidFile("the file is empty");
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        // Excel's "CSV UTF-8" export leads with a BOM (U+FEFF).
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }

    /** RFC-4180: quoted fields, "" escapes, embedded commas/newlines, CRLF. */
    private static List<List<String>> parseCsv(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                inQuotes = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\r') {
                // swallowed; the \n (or end-of-file) closes the record
            } else if (c == '\n') {
                record.add(field.toString());
                field.setLength(0);
                if (!(record.size() == 1 && record.get(0).isBlank())) {
                    records.add(record);
                }
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (inQuotes) {
            throw invalidFile("unterminated quoted field at end of file");
        }
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            if (!(record.size() == 1 && record.get(0).isBlank())) {
                records.add(record);
            }
        }
        return records;
    }

    private static String cell(List<String> record, Integer index) {
        return index == null || index >= record.size() ? "" : record.get(index);
    }

    private static Kind parseKind(String raw) {
        try {
            return Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception bad) {
            throw invalidFile("kind must be 'sentences' or 'words'");
        }
    }

    private static BadRequestException invalidFile(String detail) {
        return new BadRequestException(
                ModerationMessages.INVALID_IMPORT_FILE_MSG.formatted(detail),
                ModerationMessages.INVALID_IMPORT_FILE);
    }
}
