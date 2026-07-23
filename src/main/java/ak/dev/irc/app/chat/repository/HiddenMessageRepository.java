package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.HiddenMessage;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface HiddenMessageRepository extends JpaRepository<HiddenMessage, HiddenMessage.Key> {

    boolean existsByUserIdAndMessageId(UUID userId, long messageId);

    /** The subset of these message ids the user has hidden ("deleted for me") —
     *  one query to filter a page in the read path. */
    @Query("SELECT h.messageId FROM HiddenMessage h WHERE h.userId = :uid AND h.messageId IN :ids")
    List<Long> findHiddenAmong(@Param("uid") UUID userId, @Param("ids") Collection<Long> messageIds);

    /** Drop every "delete for me" row on a message — used when it is deleted for
     *  everyone, so no orphaned per-user hide rows survive. */
    @Modifying
    @Query("DELETE FROM HiddenMessage h WHERE h.messageId = :mid")
    int deleteByMessageId(@Param("mid") long messageId);
}
