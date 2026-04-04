package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.enums.Role;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_user_email",    columnList = "email"),
                @Index(name = "idx_user_username", columnList = "username"),
                @Index(name = "idx_user_deleted",  columnList = "deleted_at")
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

    @Column(name = "fname", nullable = false, length = 80)
    private String fname;

    @Column(name = "lname", nullable = false, length = 80)
    private String lname;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "password", length = 255)
    private String password;

    @Column(name = "profile_image", columnDefinition = "TEXT")
    private String profileImage;

    @Column(name = "profile_bio", columnDefinition = "TEXT")
    private String profileBio;

    @Column(name = "self_describer", columnDefinition = "TEXT")
    private String selfDescriber;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    @Column(name = "is_profile_locked", nullable = false)
    @Builder.Default
    private boolean isProfileLocked = false;

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

    // ── Session ───────────────────────────────────────────────────────────────

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ── Relationships ─────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<UserAuthority> authorities = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<UserLink> links = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<UserAttachment> attachments = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<UserContact> contacts = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<RefreshToken> refreshTokens = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Notification> notifications = new ArrayList<>();

    // ── UserDetails implementation ────────────────────────────────────────────

    /**
     * Derives authorities solely from the {@code role} column which is
     * always loaded with the entity — never touches the lazy {@code authorities}
     * collection, so this is safe to call outside a Hibernate session (e.g. in
     * the JWT filter).
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword()              { return password; }
    @Override public String getUsername()              { return email; }
    @Override public boolean isAccountNonExpired()     { return isAccountNonExpired; }
    @Override public boolean isAccountNonLocked()      { return isAccountNonLocked; }
    @Override public boolean isCredentialsNonExpired() { return isCredentialsNonExpired; }
    @Override public boolean isEnabled()               { return isEnabled && deletedAt == null; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public String getFullName()      { return fname + " " + lname; }
    public boolean isDeleted()       { return deletedAt != null; }
    public boolean isEmailVerified() { return emailVerifiedAt != null; }
}