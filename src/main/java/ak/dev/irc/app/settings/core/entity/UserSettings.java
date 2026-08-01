package ak.dev.irc.app.settings.core.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Cosmetic / structured settings blob for one user (spec §22.1 "Structured" +
 * "Cosmetic" tiers). One row per user, PK == {@code user_id} (shared primary
 * key with {@code users}). Every block is a JSONB column mapping a plain bean;
 * the backend stores and syncs them but interprets only the media tier.
 *
 * <p>The <em>enforced</em> settings (privacy policies, discoverability, presence
 * policy, blocks, 2FA, sessions, notification matrix, DND) are NOT here — they
 * live in dedicated tables so they are queryable and joinable on the hot path.
 * This entity is deliberately the "single JSON column" side of §22.1.</p>
 */
@Entity
@Table(name = "user_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettings extends BaseAuditEntity {

    /** Shared primary key: equals the owning user's id. Not generated. */
    @Id
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "appearance", columnDefinition = "jsonb")
    private AppearanceSettings appearance;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "accessibility", columnDefinition = "jsonb")
    private AccessibilitySettings accessibility;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "messages", columnDefinition = "jsonb")
    private MessageSettings messages;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media", columnDefinition = "jsonb")
    private MediaSettings media;

    /** Optimistic version / schema revision counter for the blob (spec §22). */
    @Column(name = "settings_version", nullable = false,
            columnDefinition = "int not null default 0")
    @Builder.Default
    private int settingsVersion = 0;

    /** Build a fully-defaulted settings row for a user with no persisted row yet. */
    public static UserSettings defaultsFor(UUID userId) {
        return UserSettings.builder()
                .userId(userId)
                .appearance(AppearanceSettings.defaults())
                .accessibility(AccessibilitySettings.defaults())
                .messages(MessageSettings.defaults())
                .media(MediaSettings.defaults())
                .settingsVersion(0)
                .build();
    }

    /** Replace any null block with its default (defensive read-merge). */
    public UserSettings withDefaults() {
        if (appearance    == null) appearance    = AppearanceSettings.defaults();
        if (accessibility == null) accessibility = AccessibilitySettings.defaults();
        if (messages      == null) messages      = MessageSettings.defaults();
        if (media         == null) media         = MediaSettings.defaults();
        return this;
    }
}
