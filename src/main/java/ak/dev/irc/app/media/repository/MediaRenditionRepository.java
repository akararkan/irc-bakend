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

    List<MediaRendition> findByIdMediaId(UUID mediaId);

    void deleteByIdMediaId(UUID mediaId);
}
