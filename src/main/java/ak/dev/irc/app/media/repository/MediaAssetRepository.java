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
    @Query("SELECT m FROM MediaAsset m WHERE m.purgeOriginalAt < :cutoff")
    List<MediaAsset> findByPurgeOriginalAtBefore(@Param("cutoff") LocalDateTime cutoff);

    // ── Admin surface (docs/admin/media-storage.md §5) ────────────────────

    /** Status/type/owner-filtered admin browse — every filter optional. */
    @Query("""
        SELECT m FROM MediaAsset m
        WHERE (:status IS NULL OR m.status = :status)
          AND (:type IS NULL OR m.type = :type)
          AND (:ownerId IS NULL OR m.ownerId = :ownerId)
          AND (CAST(:from AS timestamp) IS NULL OR m.createdAt >= :from)
          AND (CAST(:to   AS timestamp) IS NULL OR m.createdAt <= :to)
        ORDER BY m.createdAt DESC
        """)
    org.springframework.data.domain.Page<MediaAsset> adminBrowse(
            @Param("status") MediaStatus status,
            @Param("type") ak.dev.irc.app.media.enums.MediaAssetType type,
            @Param("ownerId") UUID ownerId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
        SELECT m FROM MediaAsset m
        WHERE m.status = :status
        ORDER BY m.createdAt DESC
        """)
    org.springframework.data.domain.Page<MediaAsset> findByStatusOrderByCreatedAtDesc(
            @Param("status") MediaStatus status, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT COUNT(m) FROM MediaAsset m WHERE m.status = :status")
    long countByStatus(@Param("status") MediaStatus status);

    @Query("SELECT m.status, COUNT(m) FROM MediaAsset m GROUP BY m.status")
    List<Object[]> countGroupedByStatus();

    @Query("SELECT COALESCE(SUM(m.storedBytes), 0) FROM MediaAsset m")
    long sumStoredBytesPlatform();

    @Query("""
        SELECT m.ownerId, COALESCE(SUM(m.storedBytes), 0) AS bytes, COUNT(m)
        FROM MediaAsset m
        GROUP BY m.ownerId
        ORDER BY bytes DESC
        """)
    List<Object[]> topOwnersByStoredBytes(org.springframework.data.domain.Pageable pageable);

    /** Other assets sharing this content hash (dedup-delete hazard guard). */
    @Query("""
        SELECT COUNT(m) FROM MediaAsset m
        WHERE m.contentHash = :contentHash
          AND m.id <> :id
        """)
    long countByContentHashAndIdNot(@Param("contentHash") String contentHash, @Param("id") UUID id);

    /** Abandoned intents: PENDING rows never completed (raw/ leak L2). */
    @Query("""
        SELECT m FROM MediaAsset m
        WHERE m.status = :status
          AND m.createdAt < :cutoff
        """)
    List<MediaAsset> findByStatusAndCreatedAtBefore(@Param("status") MediaStatus status,
                                                    @Param("cutoff") LocalDateTime cutoff);

    // ── Daily quota enforcement (idx_media_owner (owner_id, created_at)) ──

    @Query("SELECT COUNT(m) FROM MediaAsset m WHERE m.ownerId = :ownerId AND m.createdAt >= :since")
    long countByOwnerSince(@Param("ownerId") UUID ownerId, @Param("since") LocalDateTime since);

    @Query("""
        SELECT COALESCE(SUM(m.originalBytes), 0) FROM MediaAsset m
        WHERE m.ownerId = :ownerId AND m.createdAt >= :since
        """)
    long sumOriginalBytesSince(@Param("ownerId") UUID ownerId, @Param("since") LocalDateTime since);

    interface TypeBytes {
        ak.dev.irc.app.media.enums.MediaAssetType getType();
        long getBytes();
    }
}
