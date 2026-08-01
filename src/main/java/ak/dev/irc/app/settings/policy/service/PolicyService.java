package ak.dev.irc.app.settings.policy.service;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.settings.policy.AboutProperties;
import ak.dev.irc.app.settings.policy.dto.PolicyDtos.*;
import ak.dev.irc.app.settings.policy.entity.PolicyAcceptance;
import ak.dev.irc.app.settings.policy.entity.PolicyAcceptance.PolicyAcceptanceId;
import ak.dev.irc.app.settings.policy.repository.PolicyAcceptanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Serves app-config (min version / force-update — spec §19) and policy document
 * metadata, and records policy acceptance so a re-consent prompt fires when a
 * document version changes.
 *
 * <p>Policy bodies here are short in-app summaries; the authoritative long-form
 * documents are hosted content. What matters server-side is the version string
 * and the acceptance record.</p>
 */
@Service
@RequiredArgsConstructor
public class PolicyService {

    private final AboutProperties props;
    private final PolicyAcceptanceRepository repo;

    public AppConfigResponse appConfig() {
        return new AppConfigResponse(props.getMinSupportedVersion(),
                props.isForceUpdate(), props.getLatestVersion());
    }

    public PolicyDocResponse policyMeta(String keyRaw) {
        String key = keyRaw == null ? "" : keyRaw.trim().toLowerCase();
        return switch (key) {
            case "privacy" -> new PolicyDocResponse("privacy", props.getPrivacyPolicyVersion(),
                    props.getPrivacyPolicyVersion(), "Privacy Policy",
                    "Summary: IRC collects the minimum data needed to run the platform, "
                    + "hashes contacts before matching, strips media metadata, and lets you "
                    + "export or delete your data at any time. See the full policy on the web.");
            case "terms" -> new PolicyDocResponse("terms", props.getTermsVersion(),
                    props.getTermsVersion(), "Terms of Service",
                    "Summary: use the platform lawfully and respectfully; you own your content "
                    + "and grant IRC a licence to display it; accounts may be restricted for "
                    + "guideline violations. See the full terms on the web.");
            case "guidelines" -> new PolicyDocResponse("guidelines", props.getGuidelinesVersion(),
                    props.getGuidelinesVersion(), "Community Guidelines",
                    "Summary: this is an academic community — no harassment, hate speech, spam, "
                    + "or misinformation. Report violations from the Safety Center.");
            default -> throw new ResourceNotFoundException("Policy", "key", keyRaw);
        };
    }

    @Transactional
    public PolicyAcceptanceResponse accept(UUID userId, String keyRaw) {
        PolicyDocResponse doc = policyMeta(keyRaw); // validates key + resolves current version
        PolicyAcceptance row = PolicyAcceptance.builder()
                .id(new PolicyAcceptanceId(userId, doc.key()))
                .version(doc.version())
                .build();
        row = repo.save(row);
        return new PolicyAcceptanceResponse(doc.key(), row.getVersion(), row.getAcceptedAt());
    }

    @Transactional(readOnly = true)
    public List<PolicyAcceptanceResponse> myAcceptances(UUID userId) {
        return repo.findByIdUserId(userId).stream()
                .map(a -> new PolicyAcceptanceResponse(
                        a.getId().getPolicyKey(), a.getVersion(), a.getAcceptedAt()))
                .toList();
    }
}
