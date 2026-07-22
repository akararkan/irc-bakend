package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** Add one or more members to a group. */
@Data
public class AddMembersRequest {

    @NotEmpty(message = "userIds must not be empty")
    @Size(max = 100, message = "add at most 100 members per request")
    private List<UUID> userIds;
}
