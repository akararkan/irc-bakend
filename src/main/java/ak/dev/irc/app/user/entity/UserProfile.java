package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.common.enums.Language;
import ak.dev.irc.app.knowledge.entity.Madhhab;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "user_profiles",
    indexes = {
        @Index(name = "idx_profile_user",    columnList = "user_id"),
        @Index(name = "idx_profile_madhhab", columnList = "madhhab_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true,
                foreignKey = @ForeignKey(name = "fk_profile_user"))
    private User user;

    // ── Display identity ──────────────────────────────────────────────────────

    /**
     * Public name shown everywhere. For INSTITUTION/MEDIA accounts this is the
     * organisation name; can differ from User.fname + User.lname.
     */
    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(name = "profile_bio", columnDefinition = "TEXT")
    private String profileBio;

    /** Short tagline shown under the name. */
    @Column(name = "self_describer", columnDefinition = "TEXT")
    private String selfDescriber;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "is_profile_locked", nullable = false)
    @Builder.Default
    private boolean isProfileLocked = false;

    // ── Media ─────────────────────────────────────────────────────────────────

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(name = "avatar_s3_key", columnDefinition = "TEXT")
    private String avatarS3Key;

    @Column(name = "cover_image_url", columnDefinition = "TEXT")
    private String coverImageUrl;

    @Column(name = "cover_image_s3_key", columnDefinition = "TEXT")
    private String coverImageS3Key;

    // ── Academic / institutional ──────────────────────────────────────────────

    @Column(name = "academic_title", length = 150)
    private String academicTitle;

    @Column(name = "institution_name", length = 200)
    private String institutionName;

    /** Optional — not every user belongs to one madhhab. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "madhhab_id", foreignKey = @ForeignKey(name = "fk_profile_madhhab"))
    private Madhhab madhhab;

    @Column(name = "website_url", columnDefinition = "TEXT")
    private String websiteUrl;

    // ── Denormalized counters (kept in sync by CounterCache) ──────────────────

    @Column(name = "follower_count", nullable = false)
    @Builder.Default
    private long followerCount = 0L;

    @Column(name = "following_count", nullable = false)
    @Builder.Default
    private long followingCount = 0L;

    @Column(name = "research_count", nullable = false)
    @Builder.Default
    private int researchCount = 0;

    @Column(name = "fatwa_count", nullable = false)
    @Builder.Default
    private int fatwaCount = 0;

    // ── Availability & content preferences ───────────────────────────────────

    @Column(name = "is_for_hire", nullable = false)
    @Builder.Default
    private boolean isForHire = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_language", nullable = false, length = 5)
    @Builder.Default
    private Language contentLanguage = Language.EN;

    /** Incremented via Redis on each profile visit; reconciled daily. */
    @Column(name = "profile_views", nullable = false)
    @Builder.Default
    private long profileViews = 0L;

    // ── Relationships ─────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<UserTopicSpecialization> specializations = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<UserLink> links = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<UserContact> contacts = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<UserAttachment> attachments = new ArrayList<>();
}
