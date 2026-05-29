package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.request.*;
import ak.dev.irc.app.user.dto.response.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface UserService {

    // ── Profile read ──────────────────────────────────────────────────────────
    UserResponse getProfile(UUID userId);
    UserResponse getProfileByUsername(String username);
    UserResponse getProfileByEmail(String email);
    UserResponse getMyProfile();

    // ── Auth-layer identity update (fname, lname, username) ───────────────────
    UserResponse updateProfile(UpdateProfileRequest request);

    // ── Links ─────────────────────────────────────────────────────────────────
    UserLinkResponse    addLink(AddLinkRequest request);
    UserLinkResponse    editLink(UUID linkId, EditLinkRequest request);
    void                removeLink(UUID linkId);

    // ── Contacts ──────────────────────────────────────────────────────────────
    UserContactResponse addContact(AddContactRequest request);
    UserContactResponse editContact(UUID contactId, EditContactRequest request);
    void                removeContact(UUID contactId);

    // ── Search ────────────────────────────────────────────────────────────────
    Page<UserResponse>  searchUsers(String query, Pageable pageable);

    /** Search restricted to contributor-eligible roles (RESEARCHER / SCHOLAR) for the co-author picker. */
    Page<UserResponse>  searchEligibleContributors(String query, Pageable pageable);

    // ── Account ───────────────────────────────────────────────────────────────
    void                deleteMyAccount();
}
