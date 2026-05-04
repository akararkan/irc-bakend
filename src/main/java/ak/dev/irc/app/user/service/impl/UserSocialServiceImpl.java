package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.*;
import ak.dev.irc.app.rabbitmq.publisher.UserEventPublisher;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.dto.response.SocialActionResponse;
import ak.dev.irc.app.user.dto.response.SocialStatusResponse;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.*;
import ak.dev.irc.app.user.mapper.UserMapper;
import ak.dev.irc.app.user.repository.*;
import ak.dev.irc.app.user.service.UserSocialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserSocialServiceImpl implements UserSocialService {

    private final UserRepository            userRepository;
    private final UserFollowRepository      followRepository;
    private final UserBlockRepository       blockRepository;
    private final UserRestrictionRepository restrictionRepository;
    private final UserMapper                userMapper;

    // ── Replaces the old synchronous NotificationService calls ───────────────
    private final UserEventPublisher userEventPublisher;

    // ══════════════════════════════════════════════════════════════════════════
    //  FOLLOW
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Caching(evict = {
            // Evict the per-user following cache for both sides — coarse but
            // correct, and follow events are infrequent compared to feed reads.
            @CacheEvict(value = "user-following-ids", allEntries = true)
    })
    public SocialActionResponse follow(UUID targetId) {
        UUID me = authenticatedUserId();
        log.info("User [{}] attempting to follow user [{}]", me, targetId);

        guardSelfAction(me, targetId, "follow");

        User target = findActiveOrThrow(targetId);

        if (blockRepository.isBlockedBetween(me, targetId)) {
            log.warn("Follow blocked — block relationship exists between [{}] and [{}]", me, targetId);
            throw new ForbiddenException(
                    "Cannot follow this user due to an existing block relationship.",
                    "FOLLOW_BLOCKED_RELATIONSHIP");
        }

        if (target.isProfileLocked()) {
            log.warn("Follow rejected — target user [{}] profile is locked", targetId);
            throw new ForbiddenException(
                    "This user's profile is locked and cannot be followed.",
                    "FOLLOW_PROFILE_LOCKED");
        }

        UserFollowId fid = new UserFollowId(me, targetId);
        if (followRepository.existsById(fid)) {
            log.warn("User [{}] already follows user [{}]", me, targetId);
            throw new DuplicateResourceException("You are already following this user.");
        }

        User meUser = findActiveOrThrow(me);
        UserFollow follow = UserFollow.builder()
                .id(fid)
                .follower(meUser)
                .following(target)
                .build();
        follow.audit(AuditAction.FOLLOW, "Followed user: " + target.getUsername());
        followRepository.save(follow);

        userEventPublisher.publishFollowed(meUser, target);

        log.info("User [{}] ({}) now follows user [{}] ({})",
                me, meUser.getUsername(), targetId, target.getUsername());

        return SocialActionResponse.of("FOLLOWED", target.getId(), target.getUsername(),
                target.getProfileImage(), buildStatus(me, targetId));
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "user-following-ids", allEntries = true)
    })
    public SocialActionResponse unfollow(UUID targetId) {
        UUID me = authenticatedUserId();
        log.info("User [{}] attempting to unfollow user [{}]", me, targetId);

        guardSelfAction(me, targetId, "unfollow");

        UserFollowId fid = new UserFollowId(me, targetId);
        if (!followRepository.existsById(fid)) {
            log.warn("Unfollow failed — user [{}] is not following [{}]", me, targetId);
            throw new ResourceNotFoundException("You are not following this user. Cannot unfollow.");
        }

        User target = userRepository.findById(targetId).orElse(null);
        followRepository.deleteById(fid);
        userEventPublisher.publishUnfollowed(me, targetId);

        log.info("User [{}] unfollowed user [{}]", me, targetId);

        return SocialActionResponse.of("UNFOLLOWED", targetId,
            target != null ? target.getUsername() : null,
            target != null ? target.getProfileImage() : null,
            buildStatus(me, targetId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BLOCK
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Caching(evict = {
            // Block tears down follow-edges and changes the blocked-id set on
            // both sides — invalidate both caches so feeds re-read fresh state.
            @CacheEvict(value = "user-blocked-ids",   allEntries = true),
            @CacheEvict(value = "user-following-ids", allEntries = true)
    })
    public SocialActionResponse block(UUID targetId) {
        UUID me = authenticatedUserId();
        log.info("User [{}] attempting to block user [{}]", me, targetId);

        guardSelfAction(me, targetId, "block");

        if (blockRepository.isBlocking(me, targetId)) {
            log.warn("Block failed — user [{}] already blocks [{}]", me, targetId);
            throw new DuplicateResourceException("You are already blocking this user.");
        }

        User meUser = findActiveOrThrow(me);
        User target = findActiveOrThrow(targetId);

        // Remove all follow relationships between both users
        followRepository.deleteAllBetween(me, targetId);
        log.debug("Removed follow relationships between [{}] and [{}]", me, targetId);

        // Block supersedes restrict
        UserRestrictionId rid = new UserRestrictionId(me, targetId);
        if (restrictionRepository.existsById(rid)) {
            restrictionRepository.deleteById(rid);
            log.debug("Removed existing restriction [{}] → [{}] (superseded by block)", me, targetId);
        }

        UserBlock block = UserBlock.builder()
                .id(new UserBlockId(me, targetId))
                .blocker(meUser)
                .blocked(target)
                .build();
        block.audit(AuditAction.BLOCK, "Blocked user: " + target.getUsername());
        blockRepository.save(block);

        userEventPublisher.publishBlocked(me, targetId);

        log.info("User [{}] ({}) blocked user [{}] ({})",
                me, meUser.getUsername(), targetId, target.getUsername());

        return SocialActionResponse.of("BLOCKED", target.getId(), target.getUsername(),
                target.getProfileImage(), buildStatus(me, targetId));
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "user-blocked-ids", allEntries = true)
    })
    public SocialActionResponse unblock(UUID targetId) {
        UUID me = authenticatedUserId();
        log.info("User [{}] attempting to unblock user [{}]", me, targetId);

        UserBlockId bid = new UserBlockId(me, targetId);
        if (!blockRepository.existsById(bid)) {
            log.warn("Unblock failed — user [{}] has not blocked [{}]", me, targetId);
            throw new ResourceNotFoundException("You have not blocked this user. Cannot unblock.");
        }

        blockRepository.deleteById(bid);

        User meUser = findActiveOrThrow(me);
        User target = findActiveOrThrow(targetId);

        userEventPublisher.publishUnblocked(meUser, target);

        log.info("User [{}] unblocked user [{}]", me, targetId);

        return SocialActionResponse.of("UNBLOCKED", target.getId(), target.getUsername(),
                target.getProfileImage(), buildStatus(me, targetId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RESTRICT
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public SocialActionResponse restrict(UUID targetId) {
        UUID me = authenticatedUserId();
        log.info("User [{}] attempting to restrict user [{}]", me, targetId);

        guardSelfAction(me, targetId, "restrict");

        if (blockRepository.isBlocking(me, targetId)) {
            log.warn("Restrict failed — user [{}] already blocks [{}], restriction not needed", me, targetId);
            throw new BadRequestException(
                    "This user is already blocked. A restriction is unnecessary.",
                    "RESTRICT_ALREADY_BLOCKED");
        }

        UserRestrictionId rid = new UserRestrictionId(me, targetId);
        if (restrictionRepository.existsById(rid)) {
            log.warn("Restrict failed — user [{}] already restricts [{}]", me, targetId);
            throw new DuplicateResourceException("You are already restricting this user.");
        }

        User meUser = findActiveOrThrow(me);
        User target = findActiveOrThrow(targetId);

        UserRestriction restriction = UserRestriction.builder()
                .id(rid)
                .restrictor(meUser)
                .restricted(target)
                .build();
        restriction.audit(AuditAction.RESTRICT, "Restricted user: " + target.getUsername());
        restrictionRepository.save(restriction);

        // Restriction is intentionally silent — no event published to the target
        log.info("User [{}] restricted user [{}] (silent)", me, targetId);

        return SocialActionResponse.of("RESTRICTED", target.getId(), target.getUsername(),
                target.getProfileImage(), buildStatus(me, targetId));
    }

    @Override
    public SocialActionResponse unrestrict(UUID targetId) {
        UUID me = authenticatedUserId();
        log.info("User [{}] attempting to unrestrict user [{}]", me, targetId);

        UserRestrictionId rid = new UserRestrictionId(me, targetId);
        if (!restrictionRepository.existsById(rid)) {
            log.warn("Unrestrict failed — user [{}] is not restricting [{}]", me, targetId);
            throw new ResourceNotFoundException("You are not restricting this user. Cannot unrestrict.");
        }

        restrictionRepository.deleteById(rid);
        log.info("User [{}] unrestricted user [{}]", me, targetId);

        User target = findActiveOrThrow(targetId);
        return SocialActionResponse.of("UNRESTRICTED", target.getId(), target.getUsername(),
                target.getProfileImage(), buildStatus(me, targetId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  QUERIES
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> getFollowers(UUID userId, Pageable pageable) {
        log.debug("Fetching followers for user [{}] — page={}, size={}",
                userId, pageable.getPageNumber(), pageable.getPageSize());

        findActiveOrThrow(userId);

        Page<UserResponse> result = followRepository.findFollowers(userId, pageable)
                .map(uf -> userMapper.toResponse(
                        uf.getFollower(),
                        followRepository.countByFollowingId(uf.getFollower().getId()),
                        followRepository.countByFollowerId(uf.getFollower().getId())
                ));

        log.debug("User [{}] has {} total follower(s)", userId, result.getTotalElements());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> getFollowing(UUID userId, Pageable pageable) {
        log.debug("Fetching following list for user [{}]", userId);

        findActiveOrThrow(userId);

        return followRepository.findFollowing(userId, pageable)
                .map(uf -> userMapper.toResponse(
                        uf.getFollowing(),
                        followRepository.countByFollowingId(uf.getFollowing().getId()),
                        followRepository.countByFollowerId(uf.getFollowing().getId())
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> getBlockedUsers(Pageable pageable) {
        UUID me = authenticatedUserId();
        return blockRepository.findBlockedUsers(me, pageable)
                .map(ub -> userMapper.toResponse(ub.getBlocked(), 0L, 0L));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> getRestrictedUsers(Pageable pageable) {
        UUID me = authenticatedUserId();
        return restrictionRepository.findRestrictedUsers(me, pageable)
                .map(ur -> userMapper.toResponse(ur.getRestricted(), 0L, 0L));
    }

    @Override
    @Transactional(readOnly = true)
    public SocialStatusResponse getSocialStatus(UUID targetId) {
        UUID me = authenticatedUserId();
        return new SocialStatusResponse(
                followRepository.isFollowing(me, targetId),
                blockRepository.isBlocking(me, targetId),
                restrictionRepository.isRestricting(me, targetId),
                blockRepository.isBlocking(targetId, me),
                followRepository.countByFollowingId(targetId),
                followRepository.countByFollowerId(targetId)
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
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
                    log.warn("Unauthenticated access attempt in social service");
                    return new UnauthorizedException(
                            "You must be authenticated to perform this action.");
                });
    }

    private void guardSelfAction(UUID me, UUID target, String action) {
        if (me.equals(target)) {
            log.warn("User [{}] attempted to {} themselves", me, action);
            throw new BadRequestException(
                    "You cannot " + action + " yourself.", "SELF_ACTION_NOT_ALLOWED");
        }
    }

    /** Builds the current SocialStatusResponse for (me → targetId) snapshot. */
    private SocialStatusResponse buildStatus(UUID me, UUID targetId) {
        return new SocialStatusResponse(
                followRepository.isFollowing(me, targetId),
                blockRepository.isBlocking(me, targetId),
                restrictionRepository.isRestricting(me, targetId),
                blockRepository.isBlocking(targetId, me),
                followRepository.countByFollowingId(targetId),
                followRepository.countByFollowerId(targetId)
        );
    }
}
