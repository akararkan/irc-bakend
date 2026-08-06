package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.PostMessages;
import ak.dev.irc.app.post.cassandra.entity.MediaByPostEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.repository.MediaByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Image grid / carousel media for a post.
 *
 * For ≤ 4 items we'd normally inline in posts_by_id.media_urls — that path
 * still works on the create endpoint. This service exists for larger albums
 * and for add / remove / reorder operations after the post is published.
 *
 * Reorder strategy: because sort_order is part of the clustering key, you
 * can't UPDATE it. Reorder = bulk delete + bulk re-insert with new ordinals.
 * That's fine — albums rarely exceed 20 items.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraMediaService {

    private final MediaByPostRepository repo;
    private final PostByIdRepository    postByIdRepo;

    /**
     * Every media mutation must come from the post's author. Without this
     * check any caller could add, delete, or reorder media on any post —
     * mutations here don't pass through the post-service author guard.
     */
    public void requireAuthor(UUID postId, UUID actorId) {
        PostByIdEntity post = postByIdRepo.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));
        if (actorId == null || !actorId.equals(post.getAuthorId())) {
            throw new SecurityException(PostMessages.NOT_POST_AUTHOR_MSG);
        }
    }

    public MediaByPostEntity addMedia(UUID postId, int sortOrder, String mediaType,
                                       String url, String thumbnailUrl, String s3Key,
                                       Integer durationSeconds, Long fileSizeBytes,
                                       String mimeType, String altText) {
        MediaByPostEntity row = MediaByPostEntity.builder()
                .postId(postId).sortOrder(sortOrder).mediaId(UUID.randomUUID())
                .mediaType(mediaType).url(url).thumbnailUrl(thumbnailUrl)
                .s3Key(s3Key).durationSeconds(durationSeconds)
                .fileSizeBytes(fileSizeBytes).mimeType(mimeType).altText(altText)
                .build();
        repo.save(row);
        return row;
    }

    public List<MediaByPostEntity> listFor(UUID postId) {
        return repo.listFor(postId);
    }

    public void removeMedia(UUID postId, Integer sortOrder, UUID mediaId) {
        repo.delete(postId, sortOrder, mediaId);
    }

    /**
     * Replace the entire media list with a new ordered set. Used when the
     * user drags-and-drops to reorder, or replaces multiple items at once.
     */
    public void replaceAll(UUID postId, List<MediaByPostEntity> newOrder) {
        repo.deleteAllFor(postId);
        for (int i = 0; i < newOrder.size(); i++) {
            MediaByPostEntity m = newOrder.get(i);
            m.setPostId(postId);
            m.setSortOrder(i);
            if (m.getMediaId() == null) m.setMediaId(UUID.randomUUID());
            repo.save(m);
        }
    }
}
