package ak.dev.irc.app.admin.chat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface LegalHoldRepository extends JpaRepository<LegalHold, UUID> {

    @Query(value = """
        SELECT h FROM LegalHold h
        WHERE (:status IS NULL OR h.status = :status)
        ORDER BY h.openedAt DESC
        """,
        countQuery = """
        SELECT COUNT(h) FROM LegalHold h
        WHERE (:status IS NULL OR h.status = :status)
        """)
    Page<LegalHold> browse(@Param("status") LegalHold.Status status, Pageable pageable);
}
