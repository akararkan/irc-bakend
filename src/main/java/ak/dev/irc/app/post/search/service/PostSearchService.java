package ak.dev.irc.app.post.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.search.document.PostSearchDocument;
import ak.dev.irc.app.post.search.repository.PostSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elasticsearch-backed indexer for posts.
 *
 * Index-only: query-time search is served by the unified
 * {@code /api/v1/search} endpoint (see {@code GlobalSearchService}), which
 * fans one BM25 multi_match across the posts / Q&A / research indices in
 * parallel. This class is responsible solely for keeping the {@code irc-posts}
 * ES index in sync with Cassandra.
 *
 * Why ES instead of Cassandra SAI or pg_trgm: relevance ranking. Cassandra
 * can find rows by index but it can't BM25-rank them. Postgres tsvector can,
 * but it doesn't scale to multi-shard / multi-region. Elasticsearch is the
 * standard answer for social-media search at any meaningful volume.
 *
 * Indexing is async and lossy by design — search results are eventually
 * consistent. The canonical store is Cassandra.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostSearchService {

    private static final Pattern HASHTAG = Pattern.compile("#(\\w+)");
    private static final Pattern MENTION = Pattern.compile("@(\\w+)");

    private final PostSearchRepository searchRepo;

    /** Index a freshly created or updated post. Best-effort — never blocks the create path. */
    @Async
    public void indexAsync(PostByIdEntity post) {
        PostSearchDocument doc = PostSearchDocument.builder()
                .id(PostSearchDocument.idOf(post.getId()))
                .authorId(post.getAuthorId() == null ? null : post.getAuthorId().toString())
                .textContent(post.getTextContent())
                .postType(post.getPostType())
                .visibility(post.getVisibility())
                .status(post.getStatus())
                .locationName(post.getLocationName())
                .locationLat(post.getLocationLat())
                .locationLng(post.getLocationLng())
                .hashtags(extract(HASHTAG, post.getTextContent()))
                .mentionedUserIds(List.of())   // resolved later when usernames available
                .reactionCount(0L)
                .commentCount(0L)
                .viewCount(0L)
                .createdAt(post.getCreatedAt())
                .build();
        try {
            EsRetry.run(() -> searchRepo.save(doc),
                    "[SEARCH] index post " + post.getId());
        } catch (Exception e) {
            log.warn("[SEARCH] index post {} failed: {}", post.getId(), e.getMessage());
        }
    }

    /** Remove a post from the index — call from the delete service path. */
    @Async
    public void deleteAsync(UUID postId) {
        try {
            EsRetry.run(() -> searchRepo.deleteById(postId.toString()),
                    "[SEARCH] delete post " + postId);
        } catch (Exception e) {
            log.warn("[SEARCH] delete post {} failed: {}", postId, e.getMessage());
        }
    }

    private static List<String> extract(Pattern p, String s) {
        if (s == null) return List.of();
        Matcher m = p.matcher(s);
        java.util.List<String> out = new java.util.ArrayList<>();
        while (m.find()) out.add(m.group(1).toLowerCase());
        return out;
    }
}
