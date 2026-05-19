package ak.dev.irc.app.qna.cassandra.repository;

import ak.dev.irc.app.qna.cassandra.entity.QuestionSaveLookupEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuestionSaveLookupRepository
        extends CassandraRepository<QuestionSaveLookupEntity, MapId> {

    @Query("SELECT * FROM question_saves_lookup WHERE question_id = :questionId AND user_id = :userId")
    Optional<QuestionSaveLookupEntity> find(@Param("questionId") UUID questionId,
                                            @Param("userId") UUID userId);

    @Query("DELETE FROM question_saves_lookup WHERE question_id = :questionId AND user_id = :userId")
    void delete(@Param("questionId") UUID questionId, @Param("userId") UUID userId);
}
