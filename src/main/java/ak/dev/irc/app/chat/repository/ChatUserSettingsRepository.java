package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.ChatUserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChatUserSettingsRepository extends JpaRepository<ChatUserSettings, UUID> {

    /** Of these users, those who have turned read receipts OFF (missing row = default ON). */
    @Query("SELECT s.userId FROM ChatUserSettings s WHERE s.userId IN :ids AND s.readReceiptsEnabled = false")
    List<UUID> findReceiptDisabledAmong(@Param("ids") Collection<UUID> ids);

    /** Of these users, those who have hidden their last-seen (missing row = default visible). */
    @Query("SELECT s.userId FROM ChatUserSettings s WHERE s.userId IN :ids AND s.lastSeenVisible = false")
    List<UUID> findLastSeenHiddenAmong(@Param("ids") Collection<UUID> ids);
}
