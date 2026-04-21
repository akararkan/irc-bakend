package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.DuplicateResourceException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.dto.request.AddContactRequest;
import ak.dev.irc.app.user.dto.request.AddLinkRequest;
import ak.dev.irc.app.user.dto.request.UpdateProfileRequest;
import ak.dev.irc.app.user.dto.response.UserContactResponse;
import ak.dev.irc.app.user.dto.response.UserLinkResponse;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.entity.UserContact;
import ak.dev.irc.app.user.entity.UserLink;
import ak.dev.irc.app.user.repository.RefreshTokenRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.mapper.UserMapper;
import ak.dev.irc.app.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository         userRepository;
    private final UserFollowRepository   followRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserMapper             userMapper;

    // ── Cloudflare R2 — same service used by ResearchServiceImpl ─────────────
    private final S3StorageService s3;

    private static final List<String> ALLOWED_IMAGE_TYPES =
            Arrays.asList("image/jpeg", "image/png", "image/webp", "image/gif");

    private static final String PROFILE_IMAGE_PREFIX = "users/profile-images";

    // ══════════════════════════════════════════════════════════════════════════
    //  PROFILE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        log.debug("Fetching profile for user [{}]", userId);

        User user      = findActiveOrThrow(userId);
        long followers = followRepository.countByFollowingId(userId);
        long following = followRepository.countByFollowerId(userId);

        log.debug("Profile loaded for user [{}] — followers={}, following={}",
                userId, followers, following);

        return userMapper.toResponse(user, followers, following);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getProfileByUsername(String username) {
        log.debug("Fetching profile for username [{}]", username);

        User user = userRepository.findByUsername(username)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> {
                    log.warn("Active user not found for username [{}]", username);
                    return new ResourceNotFoundException("User", "username", username);
                });

        long followers = followRepository.countByFollowingId(user.getId());
        long following = followRepository.countByFollowerId(user.getId());

        log.debug("Profile loaded for username [{}] — followers={}, following={}",
                username, followers, following);

        return userMapper.toResponse(user, followers, following);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getMyProfile() {
        UUID myId = authenticatedUserId();
        log.info("User [{}] fetching own profile", myId);
        return getProfile(myId);
    }

    @Override
    public UserResponse updateProfile(UpdateProfileRequest req) {
        UUID myId = authenticatedUserId();
        log.info("User [{}] updating profile — fields: fname={}, lname={}, location={}, " +
                        "profileBio={}, selfDescriber={}",
                myId,
                req.fname()         != null ? "provided" : "unchanged",
                req.lname()         != null ? "provided" : "unchanged",
                req.location()      != null ? "provided" : "unchanged",
                req.profileBio()    != null ? "provided" : "unchanged",
                req.selfDescriber() != null ? "provided" : "unchanged");

        User user = findActiveOrThrow(myId);

        int changes = 0;
        if (req.fname()         != null) { user.setFname(req.fname());               changes++; }
        if (req.lname()         != null) { user.setLname(req.lname());               changes++; }
        if (req.location()      != null) { user.setLocation(req.location());         changes++; }
        if (req.profileBio()    != null) { user.setProfileBio(req.profileBio());     changes++; }
        if (req.selfDescriber() != null) { user.setSelfDescriber(req.selfDescriber()); changes++; }

        if (changes == 0) {
            log.info("User [{}] profile update — no fields changed", myId);
        } else {
            user.audit(AuditAction.UPDATE, "Profile updated (" + changes + " field(s))");
            userRepository.save(user);
            log.info("User [{}] profile updated — {} field(s) changed", myId, changes);
        }

        long followers = followRepository.countByFollowingId(user.getId());
        long following = followRepository.countByFollowerId(user.getId());
        return userMapper.toResponse(user, followers, following);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PROFILE IMAGE — Cloudflare R2
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public UserResponse uploadProfileImage(MultipartFile image) {
        UUID myId = authenticatedUserId();
        log.info("User [{}] uploading profile image — filename='{}', size={} bytes, type='{}'",
                myId,
                image != null ? image.getOriginalFilename() : "null",
                image != null ? image.getSize() : 0,
                image != null ? image.getContentType() : "null");

        validateProfileImage(image);

        User user = findActiveOrThrow(myId);

        // ── Delete old profile image from R2 if it was uploaded there ─────────
        deleteOldProfileImageIfExists(user);

        // ── Upload new image to R2 ────────────────────────────────────────────
        String s3Key   = s3.upload(image, PROFILE_IMAGE_PREFIX + "/" + myId);
        String imageUrl = s3.getPublicUrl(s3Key);

        user.setProfileImage(imageUrl);
        user.audit(AuditAction.UPLOAD, "Profile image uploaded to R2: " + s3Key);
        userRepository.save(user);

        log.info("User [{}] profile image uploaded — s3Key='{}', url='{}'", myId, s3Key, imageUrl);

        long followers = followRepository.countByFollowingId(user.getId());
        long following = followRepository.countByFollowerId(user.getId());
        return userMapper.toResponse(user, followers, following);
    }

    @Override
    public UserResponse removeProfileImage() {
        UUID myId = authenticatedUserId();
        log.info("User [{}] removing profile image", myId);

        User user = findActiveOrThrow(myId);

        deleteOldProfileImageIfExists(user);

        user.setProfileImage(null);
        user.audit(AuditAction.UPDATE, "Profile image removed");
        userRepository.save(user);

        log.info("User [{}] profile image removed", myId);

        long followers = followRepository.countByFollowingId(user.getId());
        long following = followRepository.countByFollowerId(user.getId());
        return userMapper.toResponse(user, followers, following);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LINKS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public UserLinkResponse addLink(AddLinkRequest req) {
        UUID myId = authenticatedUserId();
        log.info("User [{}] adding link — platform={}, url='{}'",
                myId, req.platform(), req.url());

        User user = findActiveOrThrow(myId);

        boolean urlExists = user.getLinks().stream()
                .anyMatch(l -> l.getUrl().equalsIgnoreCase(req.url()));
        if (urlExists) {
            log.warn("User [{}] attempted to add duplicate link URL '{}'", myId, req.url());
            throw new DuplicateResourceException("Link", "url", req.url());
        }

        UserLink link = UserLink.builder()
                .user(user)
                .platform(req.platform())
                .description(req.description())
                .url(req.url())
                .isPublic(req.isPublic())
                .displayOrder(req.displayOrder())
                .build();
        link.audit(AuditAction.CREATE, "Link added: " + req.platform().getDisplayName());

        user.getLinks().add(link);
        userRepository.save(user);

        log.info("User [{}] link added successfully — platform={}, id={}",
                myId, req.platform(), link.getId());

        return userMapper.toLinkResponse(link);
    }

    @Override
    public void removeLink(UUID linkId) {
        UUID myId = authenticatedUserId();
        log.info("User [{}] removing link [{}]", myId, linkId);

        User user = findActiveOrThrow(myId);
        boolean removed = user.getLinks().removeIf(l -> l.getId().equals(linkId));

        if (!removed) {
            log.warn("User [{}] tried to remove non-existent link [{}]", myId, linkId);
            throw new ResourceNotFoundException("Link", "id", linkId);
        }

        userRepository.save(user);
        log.info("User [{}] link [{}] removed successfully", myId, linkId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CONTACTS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public UserContactResponse addContact(AddContactRequest req) {
        UUID myId = authenticatedUserId();
        log.info("User [{}] adding contact — platform={}, value='{}'",
                myId, req.platform(), maskContactValue(req.value()));

        User user = findActiveOrThrow(myId);

        boolean duplicate = user.getContacts().stream()
                .anyMatch(c -> c.getPlatform() == req.platform()
                        && c.getValue().equalsIgnoreCase(req.value()));
        if (duplicate) {
            log.warn("User [{}] attempted to add duplicate contact — platform={}, value='{}'",
                    myId, req.platform(), maskContactValue(req.value()));
            throw new DuplicateResourceException("Contact", "platform+value",
                    req.platform() + ":" + req.value());
        }

        UserContact contact = UserContact.builder()
                .user(user)
                .platform(req.platform())
                .value(req.value())
                .isPublic(req.isPublic())
                .build();
        contact.audit(AuditAction.CREATE, "Contact added: " + req.platform().getDisplayName());

        user.getContacts().add(contact);
        userRepository.save(user);

        log.info("User [{}] contact added — platform={}, id={}",
                myId, req.platform(), contact.getId());

        return userMapper.toContactResponse(contact);
    }

    @Override
    public void removeContact(UUID contactId) {
        UUID myId = authenticatedUserId();
        log.info("User [{}] removing contact [{}]", myId, contactId);

        User user = findActiveOrThrow(myId);
        boolean removed = user.getContacts().removeIf(c -> c.getId().equals(contactId));

        if (!removed) {
            log.warn("User [{}] tried to remove non-existent contact [{}]", myId, contactId);
            throw new ResourceNotFoundException("Contact", "id", contactId);
        }

        userRepository.save(user);
        log.info("User [{}] contact [{}] removed successfully", myId, contactId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> searchUsers(String query, Pageable pageable) {
        log.info("Searching users — query='{}', page={}, size={}",
                query, pageable.getPageNumber(), pageable.getPageSize());

        Page<User> raw;
        if (query == null || query.isBlank()) {
            log.debug("Blank query — returning all active users");
            raw = userRepository.findAllActive(pageable);
        } else {
            raw = userRepository.searchUsers(query, pageable);
        }

        Page<UserResponse> results = raw
                .map(u -> userMapper.toResponse(
                        u,
                        followRepository.countByFollowingId(u.getId()),
                        followRepository.countByFollowerId(u.getId())
                ));

        log.info("Search for '{}' returned {} result(s) (page {}/{})",
                query, results.getTotalElements(),
                results.getNumber() + 1, results.getTotalPages());

        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ACCOUNT DELETION
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void deleteMyAccount() {
        UUID myId = authenticatedUserId();
        log.warn("User [{}] requesting account deletion", myId);

        User user = findActiveOrThrow(myId);

        // ── Clean up R2 profile image before soft-delete ──────────────────────
        deleteOldProfileImageIfExists(user);

        refreshTokenRepository.revokeAllForUser(myId);
        log.info("All refresh tokens revoked for user [{}]", myId);

        user.audit(AuditAction.DELETE, "Account soft-deleted by user");
        userRepository.save(user);
        userRepository.softDelete(user.getId());

        log.warn("User [{}] ({}) account soft-deleted successfully",
                myId, user.getEmail());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private User findActiveOrThrow(UUID id) {
        return userRepository.findActiveById(id)
                .orElseThrow(() -> {
                    log.warn("Active user not found for id [{}]", id);
                    return new ResourceNotFoundException("User", "id", id);
                });
    }

    private UUID authenticatedUserId() {
        return SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> {
                    log.warn("Unauthenticated access attempt");
                    return new UnauthorizedException(
                            "You must be authenticated to perform this action.");
                });
    }

    /**
     * Validates profile image file type and basic integrity.
     */
    private void validateProfileImage(MultipartFile image) {
        if (image == null || image.isEmpty())
            throw new BadRequestException(
                    "Profile image file is required and cannot be empty.", "EMPTY_FILE");

        String contentType = image.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase()))
            throw new BadRequestException(
                    "Invalid image type. Allowed: jpeg, png, webp, gif.",
                    "INVALID_FILE_TYPE");

        String filename = image.getOriginalFilename();
        if (filename == null || filename.isBlank())
            throw new BadRequestException("File name is required.", "MISSING_FILENAME");

        if (filename.contains("..") || filename.contains("/") || filename.contains("\\"))
            throw new BadRequestException("Invalid file name.", "INVALID_FILENAME");
    }

    /**
     * Extracts the R2 S3 key from a public URL and deletes the file.
     * Silently skips if the URL is from an external provider (e.g. Google OAuth2).
     */
    private void deleteOldProfileImageIfExists(User user) {
        String existingUrl = user.getProfileImage();
        if (existingUrl == null || existingUrl.isBlank()) return;

        // Only attempt deletion if the URL belongs to our R2 bucket
        if (!existingUrl.contains(PROFILE_IMAGE_PREFIX)) {
            log.debug("Skipping R2 deletion — profile image is from external provider: {}",
                    existingUrl);
            return;
        }

        try {
            // Extract S3 key: everything after the first '/' following the host
            // e.g. https://pub-xxx.r2.dev/users/profile-images/uuid/uuid.jpg
            //   → users/profile-images/uuid/uuid.jpg
            String s3Key = existingUrl.substring(existingUrl.indexOf(PROFILE_IMAGE_PREFIX));
            s3.delete(s3Key);
            log.debug("Deleted old profile image from R2: {}", s3Key);
        } catch (Exception e) {
            // Never block the upload because of a failed deletion
            log.warn("Failed to delete old profile image from R2 — url='{}': {}",
                    existingUrl, e.getMessage());
        }
    }

    /**
     * Masks contact values in logs for privacy (e.g. +964750****567).
     */
    private String maskContactValue(String value) {
        if (value == null || value.length() <= 6) return "***";
        return value.substring(0, 3) + "****" + value.substring(value.length() - 3);
    }
}