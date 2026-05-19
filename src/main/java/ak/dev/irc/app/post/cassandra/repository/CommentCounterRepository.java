package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.CommentCounterEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentCounterRepository extends CassandraRepository<CommentCounterEntity, UUID> {

    @Query("SELECT * FROM comment_counters WHERE comment_id = :commentId")
    Optional<CommentCounterEntity> findByCommentId(@Param("commentId") UUID commentId);
}
