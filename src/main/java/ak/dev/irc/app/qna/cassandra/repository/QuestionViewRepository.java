package ak.dev.irc.app.qna.cassandra.repository;

import ak.dev.irc.app.qna.cassandra.entity.QuestionViewEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuestionViewRepository
        extends CassandraRepository<QuestionViewEntity, MapId> {

    @Query("SELECT * FROM question_views_by_question WHERE question_id = :questionId AND user_id = :userId")
    Optional<QuestionViewEntity> find(@Param("questionId") UUID questionId,
                                      @Param("userId") UUID userId);
}
