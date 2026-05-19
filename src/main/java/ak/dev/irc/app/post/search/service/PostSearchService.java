package ak.dev.irc.app.post.search.service;

import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.search.document.PostSearchDocument;
import ak.dev.irc.app.post.search.repository.PostSearchRepository;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elasticsearch-backed search for posts.
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

    private final PostSearchRepository       searchRepo;
    private final ElasticsearchOperations    esOps;

    /** Index a freshly created or updated post. Best-effort — never blocks the create path. */
    @Async
    public void indexAsync(PostByIdEntity post) {
        try {
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
            searchRepo.save(doc);
        } catch (Exception e) {
            log.warn("[SEARCH] index post {} failed: {}", post.getId(), e.getMessage());
        }
    }

    /** Remove a post from the index — call from the delete service path. */
    @Async
    public void deleteAsync(UUID postId) {
        try {
            searchRepo.deleteById(postId.toString());
        } catch (Exception e) {
            log.warn("[SEARCH] delete post {} failed: {}", postId, e.getMessage());
        }
    }

    /**
     * Full-text search over post text + author fields. Returns a list of
     * post UUIDs ranked by ES BM25 — the caller hydrates from Cassandra.
     *
     * Filters: only PUBLISHED posts with PUBLIC visibility. Block-list
     * filtering happens at the caller (we don't ship user-blocks into ES).
     */
    public List<UUID> search(String query, int page, int size) {
        if (query == null || query.isBlank()) return List.of();

        Query esQuery = Query.of(q -> q.bool(b -> b
                .must(m -> m.multiMatch(mm -> mm
                        .query(query)
                        .fields("textContent^3", "hashtags^2", "authorName", "authorUsername")))
                .filter(f -> f.term(t -> t.field("status").value("PUBLISHED")))
                .filter(f -> f.term(t -> t.field("visibility").value("PUBLIC")))));

        NativeQuery native_ = NativeQuery.builder()
                .withQuery(esQuery)
                .withPageable(PageRequest.of(page, size))
                .build();

        SearchHits<PostSearchDocument> hits =
                esOps.search(native_, PostSearchDocument.class);

        return hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(PostSearchDocument::getId)
                .map(UUID::fromString)
                .toList();
    }

    private static List<String> extract(Pattern p, String s) {
        if (s == null) return List.of();
        Matcher m = p.matcher(s);
        java.util.List<String> out = new java.util.ArrayList<>();
        while (m.find()) out.add(m.group(1).toLowerCase());
        return out;
    }
}
