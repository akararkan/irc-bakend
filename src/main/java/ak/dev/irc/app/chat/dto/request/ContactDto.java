package ak.dev.irc.app.chat.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/** Payload of a {@code CONTACT} message (also round-tripped in responses). */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContactDto {

    @NotBlank
    @Size(max = 32)
    private String phone;

    @NotBlank
    @Size(max = 80)
    private String firstName;

    @Size(max = 80)
    private String lastName;

    /** Platform user id when the contact is an IRC user (optional). */
    private UUID userId;
}
