package ak.dev.irc.app.post.repository;


import ak.dev.irc.app.post.entity.PostShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostShareRepository extends JpaRepository<PostShare, UUID> {

    boolean existsByPostIdAndSharerId(UUID postId, UUID sharerId);

    long countByPostId(UUID postId);

    Optional<PostShare> findByPostIdAndSharerId(UUID postId, UUID sharerId);
}
