package ak.dev.irc.app.admin.media;

import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform storage usage (blueprint §3.7): total stored bytes + top-N owners.
 * The per-user sum existed ({@code StorageUsageService}); the platform-wide
 * and top-N queries did not. Dedup references store at 0 bytes, so the totals
 * count physical storage, not logical references.
 */
@RestController
@RequestMapping("/api/v1/admin/storage")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminStorageController {

    private final MediaAssetRepository assetRepository;

    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> usage(
            @RequestParam(defaultValue = "20") int top) {
        List<Map<String, Object>> owners = new ArrayList<>();
        for (Object[] row : assetRepository.topOwnersByStoredBytes(
                PageRequest.of(0, Pages.clamp(top)))) {
            owners.add(Map.of(
                    "ownerId", row[0],
                    "storedBytes", ((Number) row[1]).longValue(),
                    "assets", ((Number) row[2]).longValue()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalStoredBytes", assetRepository.sumStoredBytesPlatform());
        body.put("topOwners", owners);
        return ResponseEntity.ok(body);
    }
}
