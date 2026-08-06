package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.enums.Language;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.knowledge.entity.Madhhab;
import ak.dev.irc.app.knowledge.entity.Topic;
import ak.dev.irc.app.knowledge.repository.MadhhabRepository;
import ak.dev.irc.app.knowledge.repository.TopicRepository;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.dto.request.UpdateSpecializationsRequest;
import ak.dev.irc.app.user.dto.request.UpdateUserProfileRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.entity.UserProfile;
import ak.dev.irc.app.user.entity.UserTopicSpecialization;
import ak.dev.irc.app.user.mapper.UserMapper;
import ak.dev.irc.app.user.repository.UserProfileRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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
public class UserProfileServiceImpl implements UserProfileService {

    private final UserRepository        userRepository;
    private final UserProfileRepository profileRepository;
    private final MadhhabRepository     madhhabRepository;
    private final TopicRepository       topicRepository;
    private final UserMapper            userMapper;
    private final S3StorageService      s3;
    private final ak.dev.irc.app.user.search.service.UserSearchService userSearch;
    private final ak.dev.irc.app.admin.analytics.FunnelTracker funnelTracker;

    private static final String AVATAR_PREFIX = "users/avatars";
    private static final String COVER_PREFIX  = "users/covers";
    private static final List<String> ALLOWED_IMAGE_TYPES =
        Arrays.asList("image/jpeg", "image/png", "image/webp", "image/gif");

    // ══════════════════════════════════════════════════════════════════════════
    //  READ
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "user-profile", key = "#userId")
    public UserResponse getPublicProfile(UUID userId) {
        User user = findActiveOrThrow(userId);
        return userMapper.toResponse(user, false);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getMyProfile() {
        User user = findActiveOrThrow(authenticatedUserId());
        return userMapper.toResponse(user, true);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  UPDATE PROFILE FIELDS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @CacheEvict(value = "user-profile", allEntries = true)
    public UserResponse updateMyProfile(UpdateUserProfileRequest req) {
        UUID myId = authenticatedUserId();
        UserProfile profile = findProfileOrThrow(myId);

        int changes = 0;
        if (req.displayName()     != null) { profile.setDisplayName(req.displayName());         changes++; }
        if (req.profileBio()      != null) { profile.setProfileBio(req.profileBio());           changes++; }
        if (req.selfDescriber()   != null) { profile.setSelfDescriber(req.selfDescriber());     changes++; }
        if (req.location()        != null) { profile.setLocation(req.location());               changes++; }
        if (req.academicTitle()   != null) { profile.setAcademicTitle(req.academicTitle());     changes++; }
        if (req.institutionName() != null) { profile.setInstitutionName(req.institutionName()); changes++; }
        if (req.websiteUrl()      != null) { profile.setWebsiteUrl(req.websiteUrl());           changes++; }
        if (req.isForHire()       != null) { profile.setForHire(req.isForHire());               changes++; }
        if (req.isProfileLocked() != null) { profile.setProfileLocked(req.isProfileLocked());   changes++; }

        if (req.madhhabId() != null) {
            Madhhab madhhab = madhhabRepository.findById(req.madhhabId())
                .orElseThrow(() -> new ResourceNotFoundException("Madhhab", "id", req.madhhabId()));
            profile.setMadhhab(madhhab);
            changes++;
        }

        if (req.contentLanguage() != null) {
            try {
                profile.setContentLanguage(Language.valueOf(req.contentLanguage().toUpperCase()));
                changes++;
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid language code: " + req.contentLanguage(), "INVALID_LANGUAGE");
            }
        }

        if (changes > 0) {
            profile.audit(AuditAction.UPDATE, "Profile updated (" + changes + " field(s))");
            profileRepository.save(profile);
            userSearch.indexAsync(myId);
            if (profile.getProfileBio() != null && !profile.getProfileBio().isBlank()) {
                funnelTracker.markProfileCompleted(myId);
            }
            log.info("User [{}] profile updated — {} field(s)", myId, changes);
        }

        return userMapper.toResponse(profile.getUser(), true);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AVATAR
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @CacheEvict(value = "user-profile", allEntries = true)
    public UserResponse uploadAvatar(MultipartFile image) {
        UUID myId = authenticatedUserId();
        validateImage(image);

        UserProfile profile = findProfileOrThrow(myId);
        deleteS3Object(profile.getAvatarS3Key());

        String s3Key  = s3.upload(image, AVATAR_PREFIX + "/" + myId);
        String url    = s3.getPublicUrl(s3Key);
        profile.setAvatarUrl(url);
        profile.setAvatarS3Key(s3Key);
        profile.audit(AuditAction.UPLOAD, "Avatar uploaded: " + s3Key);
        profileRepository.save(profile);
        funnelTracker.markProfileCompleted(myId);

        log.info("User [{}] avatar uploaded — s3Key='{}'", myId, s3Key);
        return userMapper.toResponse(profile.getUser(), true);
    }

    @Override
    @CacheEvict(value = "user-profile", allEntries = true)
    public UserResponse removeAvatar() {
        UUID myId = authenticatedUserId();
        UserProfile profile = findProfileOrThrow(myId);
        deleteS3Object(profile.getAvatarS3Key());
        profile.setAvatarUrl(null);
        profile.setAvatarS3Key(null);
        profile.audit(AuditAction.UPDATE, "Avatar removed");
        profileRepository.save(profile);
        return userMapper.toResponse(profile.getUser(), true);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COVER IMAGE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @CacheEvict(value = "user-profile", allEntries = true)
    public UserResponse uploadCover(MultipartFile image) {
        UUID myId = authenticatedUserId();
        validateImage(image);

        UserProfile profile = findProfileOrThrow(myId);
        deleteS3Object(profile.getCoverImageS3Key());

        String s3Key = s3.upload(image, COVER_PREFIX + "/" + myId);
        String url   = s3.getPublicUrl(s3Key);
        profile.setCoverImageUrl(url);
        profile.setCoverImageS3Key(s3Key);
        profile.audit(AuditAction.UPLOAD, "Cover uploaded: " + s3Key);
        profileRepository.save(profile);

        log.info("User [{}] cover uploaded — s3Key='{}'", myId, s3Key);
        return userMapper.toResponse(profile.getUser(), true);
    }

    @Override
    @CacheEvict(value = "user-profile", allEntries = true)
    public UserResponse removeCover() {
        UUID myId = authenticatedUserId();
        UserProfile profile = findProfileOrThrow(myId);
        deleteS3Object(profile.getCoverImageS3Key());
        profile.setCoverImageUrl(null);
        profile.setCoverImageS3Key(null);
        profile.audit(AuditAction.UPDATE, "Cover removed");
        profileRepository.save(profile);
        return userMapper.toResponse(profile.getUser(), true);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SPECIALIZATIONS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @CacheEvict(value = "user-profile", allEntries = true)
    public UserResponse updateSpecializations(UpdateSpecializationsRequest req) {
        UUID myId = authenticatedUserId();
        UserProfile profile = findProfileOrThrow(myId);

        profile.getSpecializations().clear();

        for (UpdateSpecializationsRequest.SpecializationItem item : req.specializations()) {
            Topic topic = topicRepository.findById(item.topicId())
                .orElseThrow(() -> new ResourceNotFoundException("Topic", "id", item.topicId()));
            UserTopicSpecialization spec = UserTopicSpecialization.builder()
                .profile(profile)
                .topic(topic)
                .displayOrder(item.displayOrder())
                .build();
            profile.getSpecializations().add(spec);
        }

        profile.audit(AuditAction.UPDATE, "Specializations updated");
        profileRepository.save(profile);
        log.info("User [{}] specializations updated — {} topic(s)", myId, req.specializations().size());

        return userMapper.toResponse(profile.getUser(), true);
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

    private void validateImage(MultipartFile image) {
        if (image == null || image.isEmpty())
            throw new BadRequestException("Image file is required.", "EMPTY_FILE");
        String ct = image.getContentType();
        if (ct == null || !ALLOWED_IMAGE_TYPES.contains(ct.toLowerCase()))
            throw new BadRequestException("Invalid image type. Allowed: jpeg, png, webp, gif.", "INVALID_FILE_TYPE");
        String fn = image.getOriginalFilename();
        if (fn == null || fn.isBlank())
            throw new BadRequestException("File name is required.", "MISSING_FILENAME");
        if (fn.contains("..") || fn.contains("/") || fn.contains("\\"))
            throw new BadRequestException("Invalid file name.", "INVALID_FILENAME");
    }

    private void deleteS3Object(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) return;
        try {
            s3.delete(s3Key);
        } catch (Exception e) {
            log.warn("Failed to delete S3 object '{}': {}", s3Key, e.getMessage());
        }
    }
}
