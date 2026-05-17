package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.enums.VerificationStatus;
import ak.dev.irc.app.user.enums.VerificationTier;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "scholar_verifications",
    indexes = {
        @Index(name = "idx_verification_user",   columnList = "user_id"),
        @Index(name = "idx_verification_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScholarVerification extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_verification_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "claimed_tier", nullable = false, length = 30)
    private VerificationTier claimedTier;

    @Column(name = "affiliation", length = 200)
    private String affiliation;

    /** Comma-separated R2 URLs of uploaded credential evidence. */
    @Column(name = "evidence_urls", columnDefinition = "TEXT")
    private String evidenceUrls;

    @Column(name = "orcid_id", length = 20)
    private String orcidId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private VerificationStatus status = VerificationStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by", foreignKey = @ForeignKey(name = "fk_verification_reviewer"))
    private User reviewedBy;

    @Column(name = "reviewer_note", columnDefinition = "TEXT")
    private String reviewerNote;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;
}
