package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.knowledge.entity.Topic;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_topic_specializations")
@IdClass(UserTopicSpecializationId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserTopicSpecialization {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_spec_profile"))
    private UserProfile profile;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", foreignKey = @ForeignKey(name = "fk_spec_topic"))
    private Topic topic;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;
}
