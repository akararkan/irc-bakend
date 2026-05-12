package ak.dev.irc.app.post.entity;

import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "post_saves",
    indexes = {
        @Index(name = "idx_psave_post", columnList = "post_id"),
        @Index(name = "idx_psave_user", columnList = "user_id"),
        @Index(name = "idx_psave_user_collection", columnList = "user_id, collection_name")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostSave {

    @EmbeddedId
    private PostSaveId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("postId")
    @JoinColumn(name = "post_id",
                foreignKey = @ForeignKey(name = "fk_psave_post"))
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("userId")
    @JoinColumn(name = "user_id",
                foreignKey = @ForeignKey(name = "fk_psave_user"))
    private User user;

    @Column(name = "collection_name", length = 100)
    private String collectionName;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
