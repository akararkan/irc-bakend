package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.Question;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    Page<Question> findByDeletedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    Page<Question> findByAuthorIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID authorId, Pageable pageable);

    Optional<Question> findByIdAndDeletedAtIsNull(UUID id);
}