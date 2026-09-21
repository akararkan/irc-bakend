package ak.dev.irc.app.post.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.post.search.document.PostSearchDocument;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public-content discovery for the home feed.
 *
 * <p>Real social feeds surface public posts from accounts the viewer does
 * NOT follow yet; Cassandra has no global "all public posts by time" table
 * (fanout is per-follower), but the {@code irc-posts} ES index already
 * carries every post with {@code visibility} / {@code status} /
 * {@code createdAt}, so the discovery pool is one filtered, newest-first ES
 * page. This is a POOL source, not a ranking: {@code HomeFeedService}
 * filters out followed/blocked authors and {@code FeedRankingService}
 * scores what remains (EXPLORE damp applies), exactly like the reel slice.</p>
 *
 * <p>No recency window on purpose: a young network's newest public posts can
 * be days or weeks old, and "slightly old discovery" beats an empty feed —
 * the newest-first sort keeps the pool as fresh as the corpus allows.</p>
 *
 * <p>Term filters use {@code caseInsensitive} so they match under BOTH index
 * generations — the annotated Keyword mapping and a legacy dynamic (text)
 * mapping, where the indexed tokens are lowercased.</p>
 *
 * <p>ES unavailable → empty list, never an exception: discovery is an
 * enhancement and the feed must serve without it.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostDiscoveryService {

    private final ElasticsearchOperations esOps;

    /** id + author only — hydration re-reads the canonical row from Cassandra. */
    public record DiscoveredPost(UUID postId, UUID authorId) {}

    /**
     * Newest public, published posts by anyone except the viewer. The caller
     * over-fetches (pool multiplier) and drops followed authors client-side —
     * a following list can be large and does not belong in an ES terms filter.
     */
    public List<DiscoveredPost> recentPublicPosts(UUID viewer, int limit) {
        return recentPublicPosts(viewer, limit, null);
    }

    /**
     * Keyset variant for chronological surfaces (the Latest tab): only posts
     * strictly older than {@code before}, so a page cursor computed from the
     * merged timeline+discovery page re-enters BOTH sources loss-free —
     * exactly the timeline's own {@code homeFeedAfter} contract.
     */
    public List<DiscoveredPost> recentPublicPosts(UUID viewer, int limit, java.time.Instant before) {
        if (limit <= 0) return List.of();
        try {
            Query filter = Query.of(q -> q.bool(b -> {
                if (before != null) {
                    b.must(m -> m.range(r -> r.date(d -> d.field("createdAt").lt(before.toString()))));
                }
                return b
                    // visibility: PUBLIC, or absent (pre-visibility rows default public)
                    .must(m -> m.bool(vb -> vb
                            .should(s -> s.term(t -> t.field("visibility").value("PUBLIC").caseInsensitive(true)))
                            .should(s -> s.bool(nb -> nb.mustNot(n -> n.exists(e -> e.field("visibility")))))
                            .minimumShouldMatch("1")))
                    // status: PUBLISHED, or absent (legacy rows predate the status machine)
                    .must(m -> m.bool(sb -> sb
                            .should(s -> s.term(t -> t.field("status").value("PUBLISHED").caseInsensitive(true)))
                            .should(s -> s.bool(nb -> nb.mustNot(n -> n.exists(e -> e.field("status")))))
                            .minimumShouldMatch("1")))
                    .mustNot(n -> n.term(t -> t.field("authorId")
                            .value(viewer == null ? "" : viewer.toString())
                            .caseInsensitive(true)));
            }));

            NativeQuery query = NativeQuery.builder()
                    .withQuery(filter)
                    .withSort(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .withPageable(PageRequest.of(0, limit))
                    .withTrackTotalHits(false)
                    .withSourceFilter(FetchSourceFilter.of(null, new String[]{"authorId"}, null))
                    .build();

            SearchHits<PostSearchDocument> hits = EsRetry.call(
                    () -> esOps.search(query, PostSearchDocument.class),
                    "[HOME-FEED] discovery pool");

            List<DiscoveredPost> out = new ArrayList<>(hits.getSearchHits().size());
            for (SearchHit<PostSearchDocument> h : hits.getSearchHits()) {
                try {
                    String author = h.getContent() == null ? null : h.getContent().getAuthorId();
                    out.add(new DiscoveredPost(
                            UUID.fromString(h.getId()),
                            author == null ? null : UUID.fromString(author)));
                } catch (Exception ignored) { /* malformed doc — skip */ }
            }
            return out;
        } catch (Exception e) {
            log.debug("[HOME-FEED] discovery pool unavailable: {}", e.getMessage());
            return List.of();
        }
    }
}
