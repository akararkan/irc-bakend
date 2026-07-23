package ak.dev.irc.app.chat.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Payload of a {@code LOCATION} message (also round-tripped in responses). */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LocationDto {

    @NotNull
    @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
    private Double latitude;

    @NotNull
    @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
    private Double longitude;

    /** Venue/place name (optional). */
    @Size(max = 120)
    private String name;

    @Size(max = 200)
    private String address;

    /** Live location — the sender keeps updating it client-side. */
    private boolean live;

    /** Live location validity window in seconds (client-interpreted). */
    private Integer livePeriodSeconds;
}
