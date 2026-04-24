package ak.dev.irc.app.post.dto;

import ak.dev.irc.app.post.enums.PostVisibility;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdatePostRequest {

    @Size(max = 5000, message = "Text content must not exceed 5000 characters")
    private String textContent;

    private PostVisibility visibility;

    private String audioTrackUrl;
    private String audioTrackName;

    private String locationName;
    private Double locationLat;
    private Double locationLng;
}