package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.UserRestriction;
import ak.dev.irc.app.user.entity.UserRestrictionId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserRestrictionRepository
        extends JpaRepository<UserRestriction, UserRestrictionId> {

    @Query("""
        SELECT COUNT(ur) > 0 FROM UserRestriction ur
        WHERE ur.restrictor.id = :restrictorId
          AND ur.restricted.id  = :restrictedId
        """)
    boolean isRestricting(@Param("restrictorId") UUID restrictorId,
                          @Param("restrictedId")  UUID restrictedId);

    @Query("""
        SELECT ur FROM UserRestriction ur
        JOIN FETCH ur.restricted u
        WHERE ur.restrictor.id = :userId
          AND u.deletedAt IS NULL
        """)
    Page<UserRestriction> findRestrictedUsers(@Param("userId") UUID userId, Pageable pageable);
}
