package ak.dev.irc.app.post.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.common.search.dto.ReindexSummary;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.search.document.PostSearchDocument;
import ak.dev.irc.app.post.search.repository.PostSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

    private final PostSearchRepository    searchRepo;
    private final PostByIdRepository      postByIdRepo;
    private final ElasticsearchOperations esOps;

    /** Index a freshly created or updated post. Best-effort — never blocks the create path. */
    @Async
    public void indexAsync(PostByIdEntity post) {
        try {
            EsRetry.run(() -> searchRepo.save(buildDoc(post)),
                    "[SEARCH] index post " + post.getId());
        } catch (Exception e) {
            log.warn("[SEARCH] index post {} failed: {}", post.getId(), e.getMessage());
        }
    }

    private static PostSearchDocument buildDoc(PostByIdEntity post) {
        return PostSearchDocument.builder()
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
    }

    /**
     * One-shot reindex of every post from Cassandra ({@code posts_by_id},
     * walked in driver pages so nothing is held in memory). The main use is
     * repairing a dynamically-created legacy mapping: with
     * {@code dropFirst=true} the index is dropped and explicitly recreated
     * from the entity mapping (Keyword lifecycle fields), which is what makes
     * the visibility/status/postType term filters actually work.
     */
    public ReindexSummary reindexAll(boolean dropFirst) {
        Instant start = Instant.now();
        boolean dropped = false;
        if (dropFirst) {
            try {
                EsRetry.run(() -> esOps.indexOps(PostSearchDocument.class).delete(),
                        "[SEARCH] drop irc-posts");
                dropped = true;
            } catch (Exception e) {
                log.warn("[SEARCH] irc-posts drop best-effort failed (often harmless): {}", e.getMessage());
            }
            // Recreate from the annotated mapping immediately — relying on the
            // first save() would dynamic-map every Keyword field back to text.
            try {
                EsRetry.run(() -> esOps.indexOps(PostSearchDocument.class).createWithMapping(),
                        "[SEARCH] recreate irc-posts mapping");
            } catch (Exception e) {
                log.warn("[SEARCH] irc-posts mapping recreate failed: {}", e.getMessage());
            }
        }

        long indexed = 0;
        int pages = 0;
        Pageable pageable = CassandraPageRequest.first(200);
        while (true) {
            Slice<PostByIdEntity> slice = postByIdRepo.findAll(pageable);
            if (slice.isEmpty()) break;
            List<PostSearchDocument> docs = new ArrayList<>(slice.getNumberOfElements());
            for (PostByIdEntity p : slice.getContent()) docs.add(buildDoc(p));
            try {
                EsRetry.run(() -> searchRepo.saveAll(docs),
                        "[SEARCH] reindex posts page " + pages);
                indexed += docs.size();
                pages++;
            } catch (Exception e) {
                log.warn("[SEARCH] post reindex page failed ({} docs skipped): {}",
                        docs.size(), e.getMessage());
            }
            if (!slice.hasNext()) break;
            pageable = slice.nextPageable();
        }

        long ms = Duration.between(start, Instant.now()).toMillis();
        String note = String.format("Reindexed %d post(s) across %d page(s)%s.",
                indexed, pages, dropped ? " (index was dropped and mapping recreated)" : "");
        log.info("[SEARCH] {} ({}ms)", note, ms);
        return new ReindexSummary(dropped, indexed, pages, ms, note);
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
