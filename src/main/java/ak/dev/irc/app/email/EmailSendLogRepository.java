package ak.dev.irc.app.email;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface EmailSendLogRepository extends JpaRepository<EmailSendLog, UUID> {

    @Query(value = "SELECT l FROM EmailSendLog l ORDER BY l.createdAt DESC",
           countQuery = "SELECT COUNT(l) FROM EmailSendLog l")
    Page<EmailSendLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<EmailSendLog> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

    @Query("""
        SELECT l.outcome, COUNT(l) FROM EmailSendLog l
        WHERE l.createdAt >= :from AND l.createdAt <= :to
        GROUP BY l.outcome
        """)
    List<Object[]> countByOutcomeBetween(@Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);
}
