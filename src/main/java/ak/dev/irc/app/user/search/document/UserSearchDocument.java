package ak.dev.irc.app.user.search.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.UUID;

/**
 * Elasticsearch document for people search in the unified search bar
 * ({@code /api/v1/search?types=USER}).
 *
 * <p>Postgres owns the canonical account data — and keeps its own dedicated
 * FTS people-search at {@code GET /api/v1/users/search} (tsvector + trigram).
 * This index exists so the <em>global</em> search bar can rank people against
 * posts/questions/research inside one BM25 query with one score scale,
 * instead of merging two differently-scored result lists client-side.</p>
 *
 * <p>Emails are deliberately NOT indexed — same privacy rule as the Postgres
 * people search. Soft-deleted / disabled accounts are removed from the index.
 * Locked (private) profiles stay discoverable by name/handle — matching the
 * Postgres search and the usual private-account social-media behavior — since
 * every indexed field here is public-profile data.</p>
 */
@Document(indexName = "irc-users", createIndex = false)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserSearchDocument {

    @Id
    private String id;          // string form of the user UUID

    /** Handle — the strongest people-search signal ("@ahmad.rashid"). */
    @Field(type = FieldType.Text)    private String username;
    /**
     * The handle pre-split on separators ("ahmad.rashid" → "ahmad rashid").
     * The standard analyzer keeps letter.letter as ONE token (UAX#29), so
     * without this a search for just "rashid" could never match the handle.
     */
    @Field(type = FieldType.Text)    private String usernameTokens;

    @Field(type = FieldType.Text)    private String fname;
    @Field(type = FieldType.Text)    private String lname;
    @Field(type = FieldType.Text)    private String displayName;

    @Field(type = FieldType.Text)    private String bio;
    @Field(type = FieldType.Text)    private String academicTitle;
    @Field(type = FieldType.Text)    private String institutionName;

    @Field(type = FieldType.Keyword) private String role;
    /** Always {@code ACTIVE} — present so the global lifecycle filter shape stays uniform. */
    @Field(type = FieldType.Keyword) private String status;

    /** Popularity signal for the global function_score (log1p boost). */
    @Field(type = FieldType.Long)    private Long   followerCount;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant createdAt;

    public static String idOf(UUID userId) { return userId.toString(); }
}
