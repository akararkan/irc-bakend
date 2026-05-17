package ak.dev.irc.app.user.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class UserTopicSpecializationId implements Serializable {

    private UUID profile;
    private Integer topic;

    public UserTopicSpecializationId() {}

    public UserTopicSpecializationId(UUID profile, Integer topic) {
        this.profile = profile;
        this.topic   = topic;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserTopicSpecializationId that)) return false;
        return Objects.equals(profile, that.profile) && Objects.equals(topic, that.topic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, topic);
    }
}
