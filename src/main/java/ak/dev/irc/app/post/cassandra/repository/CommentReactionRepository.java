package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.CommentReactionEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentReactionRepository extends CassandraRepository<CommentReactionEntity, MapId> {

    @Query("SELECT * FROM comment_reactions_by_comment WHERE comment_id = :commentId AND user_id = :userId")
    Optional<CommentReactionEntity> find(@Param("commentId") UUID commentId,
                                         @Param("userId")    UUID userId);

    /**
     * Bulk "which of these comments has user U reacted to?" — used by comment
     * page hydration to collapse N point reads into one driver round-trip.
     */
    @Query("SELECT * FROM comment_reactions_by_comment WHERE comment_id IN :commentIds AND user_id = :userId")
    List<CommentReactionEntity> findForUserAcrossComments(@Param("commentIds") Collection<UUID> commentIds,
                                                          @Param("userId")    UUID userId);

    @Query("DELETE FROM comment_reactions_by_comment WHERE comment_id = :commentId AND user_id = :userId")
    void delete(@Param("commentId") UUID commentId,
                @Param("userId")    UUID userId);
}
