package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.AnswerAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnswerAttachmentRepository extends JpaRepository<AnswerAttachment, UUID> {

    List<AnswerAttachment> findByAnswerIdOrderByDisplayOrderAsc(UUID answerId);

    void deleteAllByAnswerId(UUID answerId);
}
