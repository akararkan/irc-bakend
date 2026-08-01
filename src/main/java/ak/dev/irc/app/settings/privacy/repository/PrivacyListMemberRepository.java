package ak.dev.irc.app.settings.privacy.repository;

import ak.dev.irc.app.settings.privacy.entity.PrivacyListMember;
import ak.dev.irc.app.settings.privacy.entity.PrivacyListMember.PrivacyListMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrivacyListMemberRepository
        extends JpaRepository<PrivacyListMember, PrivacyListMemberId> {

    boolean existsByIdListIdAndIdMemberId(UUID listId, UUID memberId);

    @Query("SELECT m.id.memberId FROM PrivacyListMember m WHERE m.id.listId = :listId")
    List<UUID> findMemberIds(@Param("listId") UUID listId);

    /**
     * True when {@code memberId} belongs to at least one CUSTOM privacy list
     * owned by {@code ownerId}. Backs the CUSTOM visibility policy.
     */
    @Query("""
        SELECT COUNT(m) > 0 FROM PrivacyListMember m
        JOIN PrivacyList l ON l.id = m.id.listId
        WHERE l.ownerId = :ownerId
          AND l.type = ak.dev.irc.app.settings.privacy.enums.PrivacyListType.CUSTOM
          AND m.id.memberId = :memberId
        """)
    boolean isInAnyCustomList(@Param("ownerId") UUID ownerId, @Param("memberId") UUID memberId);

    @Modifying
    @Query("DELETE FROM PrivacyListMember m WHERE m.id.listId = :listId")
    void deleteAllByListId(@Param("listId") UUID listId);
}
