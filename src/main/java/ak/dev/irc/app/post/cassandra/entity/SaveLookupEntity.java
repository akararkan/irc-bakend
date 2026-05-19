package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * "Has user U saved post P?" point lookup, used by the post-detail view.
 *
 * Duplicates created_at + collection_name so the unsave path has the
 * clustering key it needs to remove the matching saves_by_user row in
 * one shot — Cassandra deletes require the full primary key.
 */
@Table("saves_by_post_user")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SaveLookupEntity {

    @PrimaryKeyColumn(name = "post_id", type = PrimaryKeyType.PARTITIONED)
    private UUID postId;

    @PrimaryKeyColumn(name = "user_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID userId;

    @Column("created_at")      private Instant createdAt;
    @Column("collection_name") private String  collectionName;
}
