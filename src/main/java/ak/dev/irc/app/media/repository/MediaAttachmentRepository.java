package ak.dev.irc.app.media.repository;

import ak.dev.irc.app.media.entity.MediaAttachment;
import ak.dev.irc.app.media.entity.MediaAttachment.MediaAttachmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MediaAttachmentRepository
        extends JpaRepository<MediaAttachment, MediaAttachmentId> {

    @Query("SELECT a FROM MediaAttachment a WHERE a.id.assetId = :assetId")
    List<MediaAttachment> findByIdAssetId(@Param("assetId") UUID assetId);

    void deleteByIdAssetId(UUID assetId);
}
