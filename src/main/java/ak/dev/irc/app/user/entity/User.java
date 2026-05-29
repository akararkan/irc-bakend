package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.common.enums.Language;
import ak.dev.irc.app.user.enums.Role;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Auth-side user. Public/social fields live on {@link UserProfile} (1:1).
 *
 * <p>Authorisation surface is intentionally tiny: {@link Role} is the only
 * dimension — {@code USER}, {@code SCHOLAR}, {@code RESEARCHER}, {@code ADMIN}.
 * No separate account-type or verification-tier complexity; the badge a user
 * displays follows directly from their role (see
 * {@code UserMapper.resolveBadges}).</p>
 *
 * <h3>Spring Security note</h3>
 * <p>We do <strong>not</strong> override {@link UserDetails#getUsername()} —
 * Lombok's generated getter on the {@code username} field satisfies the
 * interface and returns the actual handle. The auth flow looks up users by
 * email-or-username via {@code CustomUserDetailsService.loadUserByUsername},
 * which accepts either form, so the principal name is irrelevant to login.
 * This used to be overridden to return {@code email}, which silently broke
 * every business call site that asked for a user's display handle.</p>
 */
@Entity
@Table(
    name = "users",
    indexes = {
        @Index(name = "idx_user_email",        columnList = "email"),
        @Index(name = "idx_user_username",     columnList = "username"),
        @Index(name = "idx_user_deleted",      columnList = "deleted_at"),
        @Index(name = "idx_user_role",         columnList = "role")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseAuditEntity implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    // ── Identity ──────────────────────────────────────────────────────────────

    @Column(name = "fname", nullable = false, length = 80)
    private String fname;

    @Column(name = "lname", nullable = false, length = 80)
    private String lname;

    /** User-chosen handle, e.g. {@code mala}. Never derived from email. */
    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password", length = 255)
    private String password;

    // ── Authorization ─────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    /** ORCID researcher identifier, e.g. 0000-0002-1825-0097. */
    @Column(name = "orcid_id", length = 20)
    private String orcidId;

    /** User's preferred UI and email language. */
    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_language", nullable = false, length = 5)
    @Builder.Default
    private Language preferredLanguage = Language.EN;

    // ── Spring Security flags ─────────────────────────────────────────────────

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private boolean isEnabled = false;

    @Column(name = "is_account_non_expired", nullable = false)
    @Builder.Default
    private boolean isAccountNonExpired = true;

    @Column(name = "is_account_non_locked", nullable = false)
    @Builder.Default
    private boolean isAccountNonLocked = true;

    @Column(name = "is_credentials_non_expired", nullable = false)
    @Builder.Default
    private boolean isCredentialsNonExpired = true;

    // ── Verification ──────────────────────────────────────────────────────────

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "two_factor_enabled", nullable = false)
    @Builder.Default
    private boolean twoFactorEnabled = false;

    @Column(name = "two_factor_secret", length = 255)
    private String twoFactorSecret;

    // ── Email notification preferences ────────────────────────────────────────

    @Column(name = "email_notifications_enabled", nullable = false)
    @Builder.Default
    private boolean emailNotificationsEnabled = true;

    @Column(name = "email_social_enabled", nullable = false)
    @Builder.Default
    private boolean emailSocialEnabled = true;

    @Column(name = "email_mentions_enabled", nullable = false)
    @Builder.Default
    private boolean emailMentionsEnabled = true;

    @Column(name = "email_system_enabled", nullable = false)
    @Builder.Default
    private boolean emailSystemEnabled = true;

    // ── Session ───────────────────────────────────────────────────────────────

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ── Relationships ─────────────────────────────────────────────────────────

    /** 1-to-1 public profile — created together with the user on registration. */
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL,
              orphanRemoval = true, fetch = FetchType.LAZY)
    private UserProfile profile;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<UserAuthority> authorities = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<RefreshToken> refreshTokens = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Notification> notifications = new ArrayList<>();

    // ── UserDetails ───────────────────────────────────────────────────────────

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // SCHOLAR carries RESEARCHER privileges (a scholar IS a researcher);
        // every other role just maps to its own authority.
        if (role == Role.SCHOLAR) {
            return List.of(
                new SimpleGrantedAuthority("ROLE_SCHOLAR"),
                new SimpleGrantedAuthority("ROLE_RESEARCHER")
            );
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword()              { return password; }
    // getUsername() is provided by Lombok's @Getter — returns the username field.
    @Override public boolean isAccountNonExpired()     { return isAccountNonExpired; }
    @Override public boolean isAccountNonLocked()      { return isAccountNonLocked; }
    @Override public boolean isCredentialsNonExpired() { return isCredentialsNonExpired; }

    @Override public boolean isEnabled()               { return isEnabled && deletedAt == null; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public String  getFullName()       { return fname + " " + lname; }
    public boolean isDeleted()         { return deletedAt != null; }
    public boolean isEmailVerified()   { return emailVerifiedAt != null; }

    /** Convenience: a scholar account (the role implies verification). */
    public boolean isVerifiedScholar() {
        return role == Role.SCHOLAR;
    }

    /** Convenience: any account allowed to publish research (scholars + researchers). */
    public boolean isResearcher() {
        return role == Role.SCHOLAR || role == Role.RESEARCHER;
    }

    // ── Profile convenience accessors (delegate to UserProfile) ──────────────
    // Used widely across post/qna/research/search modules without touching
    // each call site individually.

    public String  getProfileImage()  { return profile != null ? profile.getAvatarUrl()       : null; }
    public String  getLocation()      { return profile != null ? profile.getLocation()          : null; }
    public String  getProfileBio()    { return profile != null ? profile.getProfileBio()        : null; }
    public String  getSelfDescriber() { return profile != null ? profile.getSelfDescriber()     : null; }
    public boolean isProfileLocked()  { return profile != null && profile.isProfileLocked(); }
}
