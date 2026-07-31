package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.DuplicateResourceException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.dto.request.AddContactRequest;
import ak.dev.irc.app.user.dto.request.AddLinkRequest;
import ak.dev.irc.app.user.dto.request.EditContactRequest;
import ak.dev.irc.app.user.dto.request.EditLinkRequest;
import ak.dev.irc.app.user.dto.request.UpdateProfileRequest;
import ak.dev.irc.app.user.dto.response.UserContactResponse;
import ak.dev.irc.app.user.dto.response.UserLinkResponse;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.entity.UserContact;
import ak.dev.irc.app.user.entity.UserLink;
import ak.dev.irc.app.user.entity.UserProfile;
import ak.dev.irc.app.user.mapper.UserMapper;
import ak.dev.irc.app.user.repository.RefreshTokenRepository;
import ak.dev.irc.app.user.repository.UserProfileRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository         userRepository;
    private final UserProfileRepository  profileRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserMapper             userMapper;
    private final ak.dev.irc.app.user.search.service.UserSearchService userSearch;

    // ══════════════════════════════════════════════════════════════════════════
    //  PROFILE READ
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        User user = findActiveOrThrow(userId);
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getProfileByUsername(String identifier) {
        boolean isEmail = identifier != null && identifier.contains("@");

        User user = isEmail
            ? userRepository.findByEmailAndDeletedAtIsNull(identifier).orElse(null)
            : userRepository.findByUsernameAndDeletedAtIsNull(identifier).orElse(null);

        if (user == null) {
            user = isEmail
                ? userRepository.findByUsernameAndDeletedAtIsNull(identifier).orElse(null)
                : userRepository.findByEmailAndDeletedAtIsNull(identifier).orElse(null);
        }

        if (user == null) throw new ResourceNotFoundException("User", "username", identifier);
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getProfileByEmail(String email) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getMyProfile() {
        UUID myId = authenticatedUserId();
        User user = findActiveOrThrow(myId);
        return userMapper.toResponse(user, true);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AUTH-LAYER IDENTITY UPDATE (fname, lname, username)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public UserResponse updateProfile(UpdateProfileRequest req) {
        UUID myId = authenticatedUserId();
        User user = findActiveOrThrow(myId);

        int changes = 0;
        if (req.fname() != null) { user.setFname(req.fname()); changes++; }
        if (req.lname() != null) { user.setLname(req.lname()); changes++; }

        if (req.username() != null && !req.username().equalsIgnoreCase(user.getUsername())) {
            if (userRepository.existsByUsernameAndDeletedAtIsNull(req.username())) {
                throw new DuplicateResourceException("User", "username", req.username());
            }
            user.setUsername(req.username());
            changes++;
        }

        if (changes > 0) {
            user.audit(AuditAction.UPDATE, "Identity updated (" + changes + " field(s))");
            userRepository.save(user);
            userSearch.indexAsync(myId);
        }

        return userMapper.toResponse(user, true);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LINKS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public UserLinkResponse addLink(AddLinkRequest req) {
        UUID myId = authenticatedUserId();
        UserProfile profile = findProfileOrThrow(myId);

        boolean urlExists = profile.getLinks().stream()
            .anyMatch(l -> l.getUrl().equalsIgnoreCase(req.url()));
        if (urlExists) throw new DuplicateResourceException("Link", "url", req.url());

        UserLink link = UserLink.builder()
            .profile(profile)
            .platform(req.platform())
            .description(req.description())
            .url(req.url())
            .isPublic(req.isPublic())
            .displayOrder(req.displayOrder())
            .build();
        link.audit(AuditAction.CREATE, "Link added: " + req.platform().getDisplayName());

        profile.getLinks().add(link);
        profileRepository.save(profile);

        log.info("User [{}] link added — platform={}", myId, req.platform());
        return userMapper.toLinkResponse(link);
    }

    @Override
    public UserLinkResponse editLink(UUID linkId, EditLinkRequest req) {
        UUID myId = authenticatedUserId();
        UserProfile profile = findProfileOrThrow(myId);

        UserLink link = profile.getLinks().stream()
            .filter(l -> l.getId().equals(linkId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Link", "id", linkId));

        if (req.platform() != null)     link.setPlatform(req.platform());
        if (req.description() != null)  link.setDescription(req.description());
        if (req.url() != null)          link.setUrl(req.url());
        if (req.isPublic() != null)     link.setPublic(req.isPublic());
        if (req.displayOrder() != null) link.setDisplayOrder(req.displayOrder());

        link.audit(AuditAction.UPDATE, "Link updated");
        profileRepository.save(profile);
        return userMapper.toLinkResponse(link);
    }

    @Override
    public void removeLink(UUID linkId) {
        UUID myId = authenticatedUserId();
        UserProfile profile = findProfileOrThrow(myId);
        boolean removed = profile.getLinks().removeIf(l -> l.getId().equals(linkId));
        if (!removed) throw new ResourceNotFoundException("Link", "id", linkId);
        profileRepository.save(profile);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CONTACTS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public UserContactResponse addContact(AddContactRequest req) {
        UUID myId = authenticatedUserId();
        UserProfile profile = findProfileOrThrow(myId);

        boolean duplicate = profile.getContacts().stream()
            .anyMatch(c -> c.getPlatform() == req.platform()
                        && c.getValue().equalsIgnoreCase(req.value()));
        if (duplicate) throw new DuplicateResourceException("Contact", "platform+value",
            req.platform() + ":" + req.value());

        UserContact contact = UserContact.builder()
            .profile(profile)
            .platform(req.platform())
            .value(req.value())
            .isPublic(req.isPublic())
            .build();
        contact.audit(AuditAction.CREATE, "Contact added: " + req.platform().getDisplayName());

        profile.getContacts().add(contact);
        profileRepository.save(profile);

        log.info("User [{}] contact added — platform={}", myId, req.platform());
        return userMapper.toContactResponse(contact);
    }

    @Override
    public UserContactResponse editContact(UUID contactId, EditContactRequest req) {
        UUID myId = authenticatedUserId();
        UserProfile profile = findProfileOrThrow(myId);

        UserContact contact = profile.getContacts().stream()
            .filter(c -> c.getId().equals(contactId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Contact", "id", contactId));

        if (req.platform() != null) contact.setPlatform(req.platform());
        if (req.value() != null)    contact.setValue(req.value());
        if (req.isPublic() != null) contact.setPublic(req.isPublic());

        contact.audit(AuditAction.UPDATE, "Contact updated");
        profileRepository.save(profile);
        return userMapper.toContactResponse(contact);
    }

    @Override
    public void removeContact(UUID contactId) {
        UUID myId = authenticatedUserId();
        UserProfile profile = findProfileOrThrow(myId);
        boolean removed = profile.getContacts().removeIf(c -> c.getId().equals(contactId));
        if (!removed) throw new ResourceNotFoundException("Contact", "id", contactId);
        profileRepository.save(profile);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Hard cap on how many ranked ids a text search will pull. Search UIs
     * never page this deep; the cap bounds the IN-clause batch load and the
     * response-time worst case no matter what page/size the client sends.
     */
    private static final int MAX_SEARCH_RESULTS = 200;

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> searchUsers(String query, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return userRepository.findAllActive(pageable).map(userMapper::toResponse);
        }
        return pageRanked(searchRanked(query.trim(), fetchLimit(pageable)), pageable, u -> true);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> searchEligibleContributors(String query, Pageable pageable) {
        var roles = java.util.List.of(
                ak.dev.irc.app.user.enums.Role.RESEARCHER,
                ak.dev.irc.app.user.enums.Role.SCHOLAR);
        if (query == null || query.isBlank()) {
            return userRepository.findActiveByRoles(roles, pageable).map(userMapper::toResponse);
        }
        return pageRanked(searchRanked(query.trim(), fetchLimit(pageable)),
                pageable, u -> roles.contains(u.getRole()));
    }

    /**
     * Ranked id search riding the GIN indexes instead of a sequential-scan
     * {@code LIKE '%q%'}: full-text first, trigram fuzzy as fallback, and the
     * prefix matcher for queries too short to form trigrams. Emails are
     * deliberately NOT searchable here — use the dedicated email lookup.
     */
    private java.util.List<User> searchRanked(String q, int fetchLimit) {
        java.util.List<Object[]> hits = q.length() < 3
            ? userRepository.findMentionCandidates(q, fetchLimit)
            : userRepository.searchUsersFts(q, fetchLimit);
        if (hits.isEmpty() && q.length() >= 3) {
            hits = userRepository.searchUsersTrgm(q, fetchLimit);
        }
        if (hits.isEmpty()) return java.util.List.of();

        java.util.List<UUID> ids = hits.stream().map(r -> (UUID) r[0]).toList();
        java.util.Map<UUID, User> byId = userRepository.findActiveByIdIn(ids).stream()
            .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        // Re-apply the ranking order lost by the batch load.
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private Page<UserResponse> pageRanked(java.util.List<User> ranked, Pageable pageable,
                                          java.util.function.Predicate<User> filter) {
        java.util.List<User> filtered = ranked.stream().filter(filter).toList();
        java.util.List<UserResponse> slice = filtered.stream()
            .skip(pageable.getOffset())
            .limit(pageable.getPageSize())
            .map(userMapper::toResponse)
            .toList();
        return new org.springframework.data.domain.PageImpl<>(slice, pageable, filtered.size());
    }

    private int fetchLimit(Pageable pageable) {
        return (int) Math.min(pageable.getOffset() + pageable.getPageSize(), MAX_SEARCH_RESULTS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ACCOUNT DELETION
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void deleteMyAccount() {
        UUID myId = authenticatedUserId();
        log.warn("User [{}] requesting account deletion", myId);

        User user = findActiveOrThrow(myId);

        // Clean up R2 avatar from profile before soft-delete
        UserProfile profile = user.getProfile();
        if (profile != null && profile.getAvatarS3Key() != null) {
            log.info("Profile avatar cleanup for deleted user [{}]", myId);
        }

        refreshTokenRepository.revokeAllForUser(myId);
        user.audit(AuditAction.DELETE, "Account soft-deleted by user");
        userRepository.save(user);
        userRepository.softDelete(user.getId());
        userSearch.deleteAsync(myId);

        log.warn("User [{}] ({}) account soft-deleted", myId, user.getEmail());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private User findActiveOrThrow(UUID id) {
        return userRepository.findActiveById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    private UserProfile findProfileOrThrow(UUID userId) {
        return profileRepository.findByUserId(userId)
            .orElseThrow(() -> new ResourceNotFoundException("UserProfile", "userId", userId));
    }

    private UUID authenticatedUserId() {
        return SecurityUtils.getCurrentUserId()
            .orElseThrow(() -> new UnauthorizedException(
                "You must be authenticated to perform this action."));
    }

    private void validateProfileImage(org.springframework.web.multipart.MultipartFile image) {
        if (image == null || image.isEmpty())
            throw new BadRequestException("Profile image file is required and cannot be empty.", "EMPTY_FILE");
        String ct = image.getContentType();
        if (ct == null || !java.util.Arrays.asList(
                "image/jpeg", "image/png", "image/webp", "image/gif").contains(ct.toLowerCase()))
            throw new BadRequestException("Invalid image type. Allowed: jpeg, png, webp, gif.", "INVALID_FILE_TYPE");
        String fn = image.getOriginalFilename();
        if (fn == null || fn.isBlank())
            throw new BadRequestException("File name is required.", "MISSING_FILENAME");
        if (fn.contains("..") || fn.contains("/") || fn.contains("\\"))
            throw new BadRequestException("Invalid file name.", "INVALID_FILENAME");
    }
}
