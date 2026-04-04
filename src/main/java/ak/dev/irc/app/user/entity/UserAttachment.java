package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "user_attachments",
    indexes = @Index(name = "idx_attachment_user", columnList = "user_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAttachment extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_attachment_user"))
    private User user;

    /** S3 URL of the uploaded file */
    @Column(name = "file_url", nullable = false, columnDefinition = "TEXT")
    private String fileUrl;

    /** Original filename shown to the user */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** MIME type e.g. application/pdf, image/png */
    @Column(name = "file_type", nullable = false, length = 50)
    private String fileType;

    /** File size in bytes */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** Optional description e.g. "My CV 2025" */
    @Column(name = "description", length = 300)
    private String description;
}
