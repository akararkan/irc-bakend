package ak.dev.irc.app.research.controller;

import ak.dev.irc.app.research.dto.response.ResearchSummaryResponse;
import ak.dev.irc.app.research.service.ResearchService;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me/saved-researches")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class UserSavedResearchController {

    private final ResearchService researchService;

    /** All saved researches across all collections */
    @GetMapping
    public ResponseEntity<Page<ResearchSummaryResponse>> mySaved(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getSavedResearches(user.getId(), pageable));
    }

    /** All distinct collection names the user has created */
    @GetMapping("/collections")
    public ResponseEntity<List<String>> myCollections(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getUserCollections(user.getId()));
    }

    /** Saved researches in a specific collection */
    @GetMapping("/collections/{collectionName}")
    public ResponseEntity<Page<ResearchSummaryResponse>> myCollection(
            @PathVariable String collectionName,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getSavedByCollection(user.getId(), collectionName, pageable));
    }
}
