package ak.dev.irc.app.moderation.repository;

import ak.dev.irc.app.moderation.entity.ModerationCaseField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ModerationCaseFieldRepository extends JpaRepository<ModerationCaseField, UUID> {

    @Query("SELECT f FROM ModerationCaseField f WHERE f.moderationCase.id = :caseId ORDER BY f.fieldName")
    List<ModerationCaseField> findByCaseId(@Param("caseId") UUID caseId);

    @Query("SELECT f FROM ModerationCaseField f WHERE f.moderationCase.id IN :caseIds")
    List<ModerationCaseField> findByCaseIds(@Param("caseIds") List<UUID> caseIds);
}
