package ak.dev.irc.app.post.sound.repository;

import ak.dev.irc.app.post.enums.SoundCategory;
import ak.dev.irc.app.post.enums.SoundStatus;
import ak.dev.irc.app.post.sound.entity.Sound;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SoundRepository extends JpaRepository<Sound, UUID> {

    @Query("""
        SELECT s FROM Sound s
        WHERE s.status = 'APPROVED'
          AND (:category IS NULL OR s.category = :category)
          AND (:q IS NULL OR LOWER(s.title) LIKE LOWER(CONCAT('%', :q, '%'))
                          OR LOWER(s.artistName) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY s.useCount DESC, s.createdAt DESC
        """)
    Page<Sound> browse(@Param("category") SoundCategory category,
                       @Param("q") String q,
                       Pageable pageable);

    @Query("SELECT s FROM Sound s WHERE s.status = 'APPROVED' ORDER BY s.useCount DESC")
    List<Sound> findTrending(Pageable pageable);

    Page<Sound> findByStatus(SoundStatus status, Pageable pageable);

    @Modifying
    @Query("UPDATE Sound s SET s.useCount = s.useCount + 1 WHERE s.id = :id")
    void incrementUseCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Sound s SET s.useCount = CASE WHEN s.useCount > 0 THEN s.useCount - 1 ELSE 0 END WHERE s.id = :id")
    void decrementUseCount(@Param("id") UUID id);
}
