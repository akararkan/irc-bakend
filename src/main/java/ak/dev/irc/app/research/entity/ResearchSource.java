package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.research.enums.SourceType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "research_sources",
    indexes = @Index(name = "idx_rsource_research", columnList = "research_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchSource extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "research_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_rsource_research"))
    private Research research;

    // ── Source details ────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    /** Display title / label e.g. "Smith et al. 2024" */
    @Column(name = "title", nullable = false, length = 500)
    private String title;

    /** Full formatted citation text (APA, MLA, etc.) */
    @Column(name = "citation_text", columnDefinition = "TEXT")
    private String citationText;

    /** URL if source type is URL or DOI */
    @Column(name = "url", columnDefinition = "TEXT")
    private String url;

    /** DOI identifier */
    @Column(name = "doi", length = 255)
    private String doi;

    /** ISBN for book sources */
    @Column(name = "isbn", length = 20)
    private String isbn;

    // ── If the source is an uploaded file ─────────────────────────────────────

    /** S3/R2 URL of the uploaded source file */
    @Column(name = "file_url", columnDefinition = "TEXT")
    private String fileUrl;

    /** S3/R2 object key */
    @Column(name = "s3_key", columnDefinition = "TEXT")
    private String s3Key;

    /** Original file name */
    @Column(name = "original_file_name", length = 500)
    private String originalFileName;

    /** MIME type of uploaded source file */
    @Column(name = "mime_type", length = 100)
    private String mimeType;

    /** File size in bytes */
    @Column(name = "file_size")
    private Long fileSize;

    // ── Ordering ──────────────────────────────────────────────────────────────

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;
}
