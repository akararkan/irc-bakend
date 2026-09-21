package ak.dev.irc.app.media.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Links a processing media asset to the domain entity that embedded it, so the
 * ready-dispatcher can notify that surface when the rendition ladder lands
 * (e.g. rewrite a chat message's MediaRef and broadcast). Rows are only written
 * for surfaces that registered a {@code MediaReadyHandler}; read-time hydration
 * surfaces (posts/stories) don't need them.
 */
@Entity
@Table(name = "media_attachments",
       indexes = @Index(name = "idx_media_attachment_asset", columnList = "asset_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaAttachment {

    @EmbeddedId
    private MediaAttachmentId id;

    /** Handler key — matches {@code MediaReadyHandler.surface()} (e.g. CHAT_MESSAGE). */
    @Column(name = "surface", nullable = false, length = 32)
    private String surface;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @EqualsAndHashCode
    public static class MediaAttachmentId implements Serializable {
        @Column(name = "asset_id", nullable = false)
        private UUID assetId;
        /** Surface-scoped entity reference (e.g. the chat message id). */
        @Column(name = "entity_key", nullable = false, length = 128)
        private String entityKey;
    }
}
