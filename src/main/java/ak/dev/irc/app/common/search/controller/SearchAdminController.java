package ak.dev.irc.app.common.search.controller;

import ak.dev.irc.app.research.search.service.ResearchSearchService;
import ak.dev.irc.app.research.search.service.ResearchSearchService.ReindexResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-only one-shot search operations. Currently exposes a single
 * reindex hook for the {@code irc-research} index — used when a new
 * scoring field lands on {@link ak.dev.irc.app.research.search.document.ResearchSearchDocument}
 * and existing index docs need to be re-emitted so the new field is
 * populated.
 *
 * <p>Gated by {@code ROLE_ADMIN}. Runs synchronously — the response body
 * carries the final reindex counts so the caller knows when it's done.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/search")
@RequiredArgsConstructor
public class SearchAdminController {

    private final ResearchSearchService researchSearchService;

    /**
     * Reindex every PUBLISHED research record from Postgres into the
     * {@code irc-research} ES index.
     *
     * @param drop when {@code true} (default), the existing index is
     *             deleted first so Spring Data ES recreates it from the
     *             current entity mapping — landing any new
     *             {@code @Field} additions. Pass {@code drop=false} to
     *             refresh score fields without touching the mapping.
     */
    @PostMapping("/research/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReindexResult> reindexResearch(
            @RequestParam(name = "drop", defaultValue = "true") boolean drop) {
        return ResponseEntity.ok(researchSearchService.reindexAllPublished(drop));
    }
}
