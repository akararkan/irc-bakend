package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.HighlightByAuthorEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HighlightByAuthorRepository extends CassandraRepository<HighlightByAuthorEntity, MapId> {

    @Query("SELECT * FROM highlights_by_author WHERE author_id = :authorId")
    List<HighlightByAuthorEntity> listFor(@Param("authorId") UUID authorId);

    @Query("DELETE FROM highlights_by_author WHERE author_id = :authorId " +
           "AND display_order = :displayOrder AND highlight_id = :highlightId")
    void delete(@Param("authorId") UUID authorId,
                @Param("displayOrder") Integer displayOrder,
                @Param("highlightId") UUID highlightId);
}
