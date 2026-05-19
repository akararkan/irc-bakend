package ak.dev.irc.app.qna.cassandra.repository;

import ak.dev.irc.app.qna.cassandra.entity.QnaReactionByUserEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface QnaReactionByUserRepository
        extends CassandraRepository<QnaReactionByUserEntity, MapId> {

    @Query("SELECT * FROM qna_reactions_by_user WHERE user_id = :userId LIMIT :pageSize")
    List<QnaReactionByUserEntity> recent(@Param("userId") UUID userId,
                                         @Param("pageSize") int pageSize);

    @Query("DELETE FROM qna_reactions_by_user WHERE user_id = :userId " +
           "AND created_at = :createdAt AND answer_id = :answerId")
    void delete(@Param("userId") UUID userId,
                @Param("createdAt") Instant createdAt,
                @Param("answerId") UUID answerId);
}
