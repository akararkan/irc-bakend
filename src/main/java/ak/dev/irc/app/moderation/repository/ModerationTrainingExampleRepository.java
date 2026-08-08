package ak.dev.irc.app.moderation.repository;

import ak.dev.irc.app.moderation.entity.ModerationTrainingExample;
import ak.dev.irc.app.moderation.enums.TrainingExampleSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ModerationTrainingExampleRepository
        extends JpaRepository<ModerationTrainingExample, UUID> {

    Optional<ModerationTrainingExample> findByTextHash(String textHash);

    Page<ModerationTrainingExample> findBySourceOrderByAddedAtDesc(TrainingExampleSource source,
                                                                   Pageable pageable);

    Page<ModerationTrainingExample> findAllByOrderByAddedAtDesc(Pageable pageable);

    long countBySource(TrainingExampleSource source);

    long countByAddedAtAfter(LocalDateTime after);

    /** Examples not yet folded into any completed training run — the "worth retraining?" signal. */
    long countByTrainedInVersionIsNull();

    /** The export the training container consumes; capped so one run cannot OOM the container. */
    @Query("SELECT e FROM ModerationTrainingExample e ORDER BY e.addedAt ASC")
    List<ModerationTrainingExample> exportAll(Pageable pageable);

    @Query("""
            SELECT SUM(e.toxic), SUM(e.severeToxic), SUM(e.obscene),
                   SUM(e.threat), SUM(e.insult), SUM(e.identityHate), COUNT(e)
            FROM ModerationTrainingExample e
            """)
    List<Object[]> labelTotals();

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE ModerationTrainingExample e SET e.trainedInVersion = :version WHERE e.trainedInVersion IS NULL")
    int markTrained(@Param("version") String version);
}
