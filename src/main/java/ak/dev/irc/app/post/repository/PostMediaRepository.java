package ak.dev.irc.app.post.repository;


import ak.dev.irc.app.post.entity.PostMedia;
import ak.dev.irc.app.post.enums.PostMediaType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PostMediaRepository extends JpaRepository<PostMedia, UUID> {

    List<PostMedia> findByPostIdOrderBySortOrderAsc(UUID postId);

    List<PostMedia> findByPostIdAndMediaType(UUID postId, PostMediaType mediaType);

    void deleteByPostId(UUID postId);
}
