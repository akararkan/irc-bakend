package ak.dev.irc.app.activity.repository;

import ak.dev.irc.app.activity.entity.ReelView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReelViewRepository extends JpaRepository<ReelView, UUID> {

    Page<ReelView> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM ReelView v WHERE v.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
