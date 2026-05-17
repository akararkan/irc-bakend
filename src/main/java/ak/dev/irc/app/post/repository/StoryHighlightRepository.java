package ak.dev.irc.app.post.repository;

import ak.dev.irc.app.post.entity.StoryHighlight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StoryHighlightRepository extends JpaRepository<StoryHighlight, UUID> {

    @Query("""
        SELECT h FROM StoryHighlight h
        WHERE h.author.id = :authorId
        ORDER BY h.displayOrder ASC, h.createdAt DESC
        """)
    List<StoryHighlight> findByAuthorId(@Param("authorId") UUID authorId);
}
