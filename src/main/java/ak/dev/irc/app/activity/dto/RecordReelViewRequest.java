package ak.dev.irc.app.activity.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class RecordReelViewRequest {

    @Min(0)
    private Integer watchedSeconds;
}
