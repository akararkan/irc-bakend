package ak.dev.irc.app.research.cassandra.repository;

import ak.dev.irc.app.research.cassandra.entity.ResearchCommentLikeEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResearchCommentLikeRepository
        extends CassandraRepository<ResearchCommentLikeEntity, MapId> {

    @Query("SELECT * FROM research_comment_likes_by_comment " +
           "WHERE comment_id = :cid AND user_id = :uid")
    Optional<ResearchCommentLikeEntity> find(@Param("cid") UUID commentId,
                                             @Param("uid") UUID userId);

    @Query("DELETE FROM research_comment_likes_by_comment WHERE comment_id = :cid AND user_id = :uid")
    void delete(@Param("cid") UUID commentId, @Param("uid") UUID userId);
}
