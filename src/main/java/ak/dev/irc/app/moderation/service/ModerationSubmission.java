package ak.dev.irc.app.moderation.service;

import ak.dev.irc.app.moderation.enums.ModeratedEntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One content unit handed to the moderation pipeline: what it is, who wrote it,
 * and every text field belonging to it (MODERATION_ROADMAP.md §5.4).
 *
 * <p>Built at the call site with a small fluent builder so a content service can
 * list its fields inline without a DTO class per surface:</p>
 *
 * <pre>
 *   ModerationSubmission.of(ModeratedEntityType.POST, postId.toString(), authorId)
 *       .field("body", cmd.textContent())
 *       .field("location", cmd.locationName())
 *       .fields("tag", hashtags);
 * </pre>
 *
 * <p>Blank fields are dropped on the way in — scoring an empty string wastes a
 * model call and pollutes the stored evidence with rows nobody can review.</p>
 */
public final class ModerationSubmission {

    private final ModeratedEntityType entityType;
    private final String entityRef;
    private final UUID authorId;
    private String parentRef;
    private final List<ModerationTextField> fields = new ArrayList<>();

    private ModerationSubmission(ModeratedEntityType entityType, String entityRef, UUID authorId) {
        this.entityType = entityType;
        this.entityRef = entityRef;
        this.authorId = authorId;
    }

    public static ModerationSubmission of(ModeratedEntityType entityType, String entityRef, UUID authorId) {
        return new ModerationSubmission(entityType, entityRef, authorId);
    }

    /** Threaded content: the answer's question, the reply's comment, the comment's post. */
    public ModerationSubmission parent(String ref) {
        this.parentRef = ref;
        return this;
    }

    public ModerationSubmission field(String name, String text) {
        if (text != null && !text.isBlank()) {
            fields.add(new ModerationTextField(name, text));
        }
        return this;
    }

    /** Repeated fields (tags, alt-texts) get an index suffix so the queue can point at one. */
    public ModerationSubmission fields(String name, Iterable<String> texts) {
        if (texts == null) return this;
        int index = 0;
        for (String text : texts) {
            if (text != null && !text.isBlank()) {
                fields.add(new ModerationTextField(name + "[" + index + "]", text));
            }
            index++;
        }
        return this;
    }

    public ModeratedEntityType entityType() {
        return entityType;
    }

    public String entityRef() {
        return entityRef;
    }

    public String parentRef() {
        return parentRef;
    }

    public UUID authorId() {
        return authorId;
    }

    public List<ModerationTextField> textFields() {
        return List.copyOf(fields);
    }

    /** Nothing to score — the caller submitted media with no text at all. */
    public boolean empty() {
        return fields.isEmpty();
    }
}
