package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.SuggestionDismissal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SuggestionDismissalRepository
        extends JpaRepository<SuggestionDismissal, SuggestionDismissal.Key> {

    @Query("SELECT d.id.candidateId FROM SuggestionDismissal d WHERE d.id.userId = :userId")
    List<UUID> findDismissedCandidateIds(@Param("userId") UUID userId);
}
