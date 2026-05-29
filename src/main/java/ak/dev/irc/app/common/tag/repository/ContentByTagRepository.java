package ak.dev.irc.app.common.tag.repository;

import ak.dev.irc.app.common.tag.entity.ContentByTagEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ContentByTagRepository extends CassandraRepository<ContentByTagEntity, MapId> {

    @Query("SELECT * FROM content_by_tag WHERE tag = :tag LIMIT :pageSize")
    List<ContentByTagEntity> firstPage(@Param("tag") String tag,
                                       @Param("pageSize") int pageSize);

    @Query("SELECT * FROM content_by_tag WHERE tag = :tag " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<ContentByTagEntity> nextPage(@Param("tag") String tag,
                                      @Param("cursor") Instant cursor,
                                      @Param("pageSize") int pageSize);

    /**
     * Composite-cursor variant: when two rows share a {@code created_at} (same
     * millisecond), {@code content_id} breaks the tie so pagination is exact.
     * Cassandra natively supports tuple compare on consecutive clustering
     * columns (clustering order is {@code created_at DESC, content_id ASC}).
     */
    @Query("SELECT * FROM content_by_tag WHERE tag = :tag " +
           "AND (created_at, content_id) < (:cursorAt, :cursorId) LIMIT :pageSize")
    List<ContentByTagEntity> nextPageWithTiebreaker(@Param("tag") String tag,
                                                    @Param("cursorAt") Instant cursorAt,
                                                    @Param("cursorId") UUID cursorId,
                                                    @Param("pageSize") int pageSize);
}
