package ak.dev.irc.app.qna.cassandra.repository;

import ak.dev.irc.app.qna.cassandra.entity.QnaReactionByAnswerEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface QnaReactionByAnswerRepository
        extends CassandraRepository<QnaReactionByAnswerEntity, MapId> {

    @Query("SELECT * FROM qna_reactions_by_answer WHERE answer_id = :answerId AND user_id = :userId")
    Optional<QnaReactionByAnswerEntity> find(@Param("answerId") UUID answerId,
                                             @Param("userId") UUID userId);

    @Query("DELETE FROM qna_reactions_by_answer WHERE answer_id = :answerId AND user_id = :userId")
    void delete(@Param("answerId") UUID answerId, @Param("userId") UUID userId);
}
