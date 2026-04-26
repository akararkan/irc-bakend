package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.AnswerSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnswerSourceRepository extends JpaRepository<AnswerSource, UUID> {

    List<AnswerSource> findByAnswerIdOrderByDisplayOrderAsc(UUID answerId);

    void deleteAllByAnswerId(UUID answerId);
}
