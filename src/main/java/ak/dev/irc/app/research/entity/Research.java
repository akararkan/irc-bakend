package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.research.enums.ResearchStatus;
import ak.dev.irc.app.research.enums.ResearchVisibility;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(
    name = "researches",
    indexes = {
        @Index(name = "idx_research_researcher",   columnList = "researcher_id"),
        @Index(name = "idx_research_status",       columnList = "status"),
        @Index(name = "idx_research_published_at", columnList = "published_at"),
        @Index(name = "idx_research_slug",         columnList = "slug"),
        @Index(name = "idx_research_deleted",      columnList = "deleted_at"),
        @Index(name = "idx_research_irc_id",       columnList = "irc_id"),
        @Index(name = "idx_research_share_token",  columnList = "share_token")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Research extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    // ── Author ────────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "researcher_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_research_researcher"))
    private User researcher;

    // ── Core fields ───────────────────────────────────────────────────────────

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "slug", nullable = false, unique = true, length = 600)
    private String slug;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "abstract_text", nullable = false, columnDefinition = "TEXT")
    private String abstractText;

    // ── IRC Official Identifier ───────────────────────────────────────────────

    /**
     * Sequential number pulled from the DB sequence {@code research_irc_seq}.
     * Assigned on creation. Used to build the IRC ID and DOI.
     * Never null after the first save.
     */
    @Column(name = "irc_sequence_number", unique = true)
    private Long ircSequenceNumber;

    /**
     * Human-readable IRC identifier: IRC-{YEAR}-{6-digit-sequence}.
     * Example: IRC-2026-000042
     * Assigned on creation, never changes.
     * This is the official paper identifier issued by IRC.
     */
    @Column(name = "irc_id", unique = true, length = 30)
    private String ircId;

    // ── Video promo (researcher's self-explanation video) ──────────────────────

    @Column(name = "video_promo_url", columnDefinition = "TEXT")
    private String videoPromoUrl;

    @Column(name = "video_promo_s3_key", columnDefinition = "TEXT")
    private String videoPromoS3Key;

    @Column(name = "video_promo_duration_seconds")
    private Integer videoPromoDurationSeconds;

    @Column(name = "video_promo_thumbnail_url", columnDefinition = "TEXT")
    private String videoPromoThumbnailUrl;

    // ── Citation ──────────────────────────────────────────────────────────────

    /** Formatted citation text (APA, MLA, etc.) auto-generated or entered */
    @Column(name = "citation", columnDefinition = "TEXT")
    private String citation;

    /**
     * Digital Object Identifier.
     * Auto-generated on publish as {@code 10.{prefix}/irc.{year}.{sequence}}
     * unless the researcher manually provided one in the create/update request.
     */
    @Column(name = "doi", length = 255)
    private String doi;

    // ── Counters (denormalised for read performance) ──────────────────────────

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private Long viewCount = 0L;

    @Column(name = "download_count", nullable = false)
    @Builder.Default
    private Long downloadCount = 0L;

    @Column(name = "reaction_count", nullable = false)
    @Builder.Default
    private Long reactionCount = 0L;

    @Column(name = "comment_count", nullable = false)
    @Builder.Default
    private Long commentCount = 0L;

    @Column(name = "save_count", nullable = false)
    @Builder.Default
    private Long saveCount = 0L;

    @Column(name = "share_count", nullable = false)
    @Builder.Default
    private Long shareCount = 0L;

    @Column(name = "citation_count", nullable = false)
    @Builder.Default
    private Long citationCount = 0L;

    // ── Publication lifecycle ─────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ResearchStatus status = ResearchStatus.PUBLISHED;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    @Builder.Default
    private ResearchVisibility visibility = ResearchVisibility.PUBLIC;

    @Column(name = "scheduled_publish_at")
    private LocalDateTime scheduledPublishAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ── SEO / Discoverability ────────────────────────────────────────────────

    @Column(name = "keywords", columnDefinition = "TEXT")
    private String keywords;

    @Column(name = "cover_image_url", columnDefinition = "TEXT")
    private String coverImageUrl;

    @Column(name = "cover_image_s3_key", columnDefinition = "TEXT")
    private String coverImageS3Key;

    // ── Shareable link ───────────────────────────────────────────────────────

    /** Short share token for public URL — e.g. /r/{shareToken} */
    @Column(name = "share_token", unique = true, length = 32)
    private String shareToken;

    // ── Allow / disallow toggles ─────────────────────────────────────────────

    @Column(name = "comments_enabled", nullable = false)
    @Builder.Default
    private boolean commentsEnabled = true;

    @Column(name = "downloads_enabled", nullable = false)
    @Builder.Default
    private boolean downloadsEnabled = true;

    // ── Optimistic locking ───────────────────────────────────────────────────

    /**
     * Prevents lost updates under concurrent edits.
     * Spring Data JPA increments this automatically on every save.
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    // ── Relationships ─────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "research", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<ResearchMedia> mediaFiles = new ArrayList<>();

    @OneToMany(mappedBy = "research", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<ResearchSource> sources = new ArrayList<>();

    @OneToMany(mappedBy = "research", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private Set<ResearchTag> tags = new LinkedHashSet<>();

    @OneToMany(mappedBy = "research", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt DESC")
    @Builder.Default
    private List<ResearchComment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "research", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private Set<ResearchReaction> reactions = new LinkedHashSet<>();

    @OneToMany(mappedBy = "research", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private Set<ResearchSave> saves = new LinkedHashSet<>();

    @OneToMany(mappedBy = "research", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ResearchView> views = new ArrayList<>();

    @OneToMany(mappedBy = "research", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ResearchDownload> downloads = new ArrayList<>();

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isDeleted()   { return deletedAt != null; }
    public boolean isPublished() { return status == ResearchStatus.PUBLISHED && publishedAt != null; }
    public boolean isDraft()     { return status == ResearchStatus.DRAFT; }
}
