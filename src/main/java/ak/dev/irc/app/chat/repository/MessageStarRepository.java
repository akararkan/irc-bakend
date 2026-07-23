package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.MessageStar;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageStarRepository extends JpaRepository<MessageStar, UUID> {

    Optional<MessageStar> findByUserIdAndMessageId(UUID userId, long messageId);

    Page<MessageStar> findByUserIdOrderByStarredAtDesc(UUID userId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM MessageStar s WHERE s.userId = :uid AND s.messageId = :mid")
    int deleteStar(@Param("uid") UUID userId, @Param("mid") long messageId);

    /** Drop every star on a message — used when it is deleted for everyone, so no
     *  orphaned star rows survive and no tombstone renders {@code starred:true}. */
    @Modifying
    @Query("DELETE FROM MessageStar s WHERE s.messageId = :mid")
    int deleteByMessageId(@Param("mid") long messageId);

    /** Which of these message ids the user has starred — one query to flag a page. */
    @Query("SELECT s.messageId FROM MessageStar s WHERE s.userId = :uid AND s.messageId IN :ids")
    List<Long> findStarredAmong(@Param("uid") UUID userId, @Param("ids") Collection<Long> messageIds);
}
