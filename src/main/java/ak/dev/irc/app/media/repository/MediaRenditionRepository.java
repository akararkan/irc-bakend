package ak.dev.irc.app.media.repository;

import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.entity.MediaRendition.MediaRenditionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MediaRenditionRepository
        extends JpaRepository<MediaRendition, MediaRenditionId> {

    @org.springframework.data.jpa.repository.Query(
            "SELECT r FROM MediaRendition r WHERE r.id.mediaId = :mediaId")
    List<MediaRendition> findByIdMediaId(
            @org.springframework.data.repository.query.Param("mediaId") UUID mediaId);

    void deleteByIdMediaId(UUID mediaId);

    /**
     * Bucket-reconcile membership check: of these listed object keys, which
     * are referenced by a rendition row? One IN-query per listing batch
     * (backed by {@code idx_rendition_object_key}) instead of a point read
     * per bucket object.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT r.objectKey FROM MediaRendition r WHERE r.objectKey IN :keys")
    java.util.Set<String> findExistingObjectKeys(
            @org.springframework.data.repository.query.Param("keys") java.util.Collection<String> keys);
}
