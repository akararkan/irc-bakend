package ak.dev.irc.app.qna.cassandra.repository;

import ak.dev.irc.app.qna.cassandra.entity.QuestionSaveByUserEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface QuestionSaveByUserRepository
        extends CassandraRepository<QuestionSaveByUserEntity, MapId> {

    @Query("SELECT * FROM question_saves_by_user WHERE user_id = :userId LIMIT :pageSize")
    List<QuestionSaveByUserEntity> firstPage(@Param("userId") UUID userId,
                                             @Param("pageSize") int pageSize);

    @Query("DELETE FROM question_saves_by_user WHERE user_id = :userId " +
           "AND created_at = :createdAt AND question_id = :questionId")
    void delete(@Param("userId") UUID userId,
                @Param("createdAt") Instant createdAt,
                @Param("questionId") UUID questionId);
}
