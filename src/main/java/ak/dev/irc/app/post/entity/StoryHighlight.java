package ak.dev.irc.app.post.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "story_highlights",
    indexes = {
        @Index(name = "idx_hl_author",        columnList = "author_id"),
        @Index(name = "idx_hl_display_order", columnList = "author_id, display_order")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryHighlight extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_hl_author"))
    private User author;

    @Column(name = "title", nullable = false, length = 80)
    private String title;

    @Column(name = "cover_url", columnDefinition = "TEXT")
    private String coverUrl;

    @Column(name = "cover_s3_key", columnDefinition = "TEXT")
    private String coverS3Key;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @OneToMany(mappedBy = "highlight", fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<Story> stories = new ArrayList<>();
}
