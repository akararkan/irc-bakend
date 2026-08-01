package ak.dev.irc.app.media.repository;

import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.enums.MediaStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    /** Dedup short-circuit (§20.5) — a READY asset with the same content hash. */
    Optional<MediaAsset> findFirstByContentHashAndStatus(String contentHash, MediaStatus status);

    /** Storage usage report (§15): total stored bytes per media type for a user. */
    @Query("""
        SELECT m.type AS type, COALESCE(SUM(m.storedBytes), 0) AS bytes
        FROM MediaAsset m
        WHERE m.ownerId = :ownerId
        GROUP BY m.type
        """)
    List<TypeBytes> sumStoredBytesByType(@Param("ownerId") UUID ownerId);

    @Query("SELECT COALESCE(SUM(m.storedBytes), 0) FROM MediaAsset m WHERE m.ownerId = :ownerId")
    long sumStoredBytes(@Param("ownerId") UUID ownerId);

    /** Originals whose 7-day retention has elapsed (§20.6 raw/ cleanup). */
    List<MediaAsset> findByPurgeOriginalAtBefore(LocalDateTime cutoff);

    interface TypeBytes {
        ak.dev.irc.app.media.enums.MediaAssetType getType();
        long getBytes();
    }
}
