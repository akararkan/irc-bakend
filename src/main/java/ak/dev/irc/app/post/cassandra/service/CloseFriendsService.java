package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.CloseFriendEntity;
import ak.dev.irc.app.post.cassandra.repository.CloseFriendRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Close-friends list for CLOSE_FRIENDS-visibility stories.
 *
 * Source-of-truth is Cassandra (one table, partition per owner). No fanout
 * needed — the relationship is read on the visibility-check path each time
 * a story is delivered, so we lookup directly.
 *
 * Privacy note: only the OWNER can read their own list. The {@link
 * #isCloseFriend} check is the only operation any other user is allowed to
 * trigger, and it returns a single boolean, never the membership list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloseFriendsService {

    private final CloseFriendRepository repo;

    public void add(UUID ownerId, UUID friendId) {
        if (ownerId.equals(friendId)) return;        // can't add yourself
        repo.save(CloseFriendEntity.builder()
                .ownerId(ownerId).friendId(friendId)
                .addedAt(Instant.now())
                .build());
    }

    public void remove(UUID ownerId, UUID friendId) {
        repo.delete(ownerId, friendId);
    }

    public List<CloseFriendEntity> listFor(UUID ownerId) {
        return repo.listFor(ownerId);
    }

    /** Hot-path predicate used by the story visibility filter. */
    public boolean isCloseFriend(UUID ownerId, UUID candidateId) {
        return repo.find(ownerId, candidateId).isPresent();
    }
}
