package ak.dev.irc.app.settings.privacy.repository;

import ak.dev.irc.app.settings.privacy.entity.PrivacyList;
import ak.dev.irc.app.settings.privacy.enums.PrivacyListType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrivacyListRepository extends JpaRepository<PrivacyList, UUID> {

    List<PrivacyList> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    List<PrivacyList> findByOwnerIdAndType(UUID ownerId, PrivacyListType type);
}
