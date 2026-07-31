package ak.dev.irc.app.chat.search.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.UUID;

/**
 * Elasticsearch document for public broadcast-channel discovery.
 *
 * <p><strong>Only PUBLIC channels are ever indexed.</strong> Private
 * channels, groups and DMs never enter this index — privacy is enforced at
 * write time (the strongest place), and {@code publicChannel} is stored as a
 * belt-and-braces query filter on top. A channel that turns private is
 * deleted from the index on the same update.</p>
 *
 * <p>Postgres ({@code conversations}) stays canonical. This index powers
 * both {@code GET /api/v1/channels/discover?q=…} (relevance-ranked, replacing
 * the old sequential-scan {@code LIKE '%q%'}) and the {@code CHANNEL} type in
 * the unified {@code /api/v1/search}.</p>
 */
@Document(indexName = "irc-channels", createIndex = false)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChannelSearchDocument {

    @Id
    private String id;          // string form of the conversation UUID

    @Field(type = FieldType.Text)    private String  title;
    /** Public @handle — near-exact matches should win, hence high query-side boost. */
    @Field(type = FieldType.Text)    private String  handle;
    /** Handle pre-split on separators ("ilm_hub" → "ilm hub") — see UserSearchDocument#usernameTokens. */
    @Field(type = FieldType.Text)    private String  handleTokens;
    @Field(type = FieldType.Text)    private String  description;

    @Field(type = FieldType.Keyword) private String  category;
    @Field(type = FieldType.Boolean) private Boolean verified;
    /** Always {@code true} — see class javadoc; kept as a query-side safety filter. */
    @Field(type = FieldType.Boolean) private Boolean publicChannel;
    /** Always {@code ACTIVE} — uniform lifecycle-filter shape across indices. */
    @Field(type = FieldType.Keyword) private String  status;

    /** Subscriber count — popularity signal for ranking (log1p boost). */
    @Field(type = FieldType.Long)    private Long    subscriberCount;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant createdAt;

    public static String idOf(UUID channelId) { return channelId.toString(); }
}
