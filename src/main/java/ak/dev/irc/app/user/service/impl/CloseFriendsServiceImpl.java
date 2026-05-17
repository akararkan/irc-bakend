package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.DuplicateResourceException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.CloseFriendsList;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.mapper.UserMapper;
import ak.dev.irc.app.user.repository.CloseFriendsRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.CloseFriendsService;
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
public class CloseFriendsServiceImpl implements CloseFriendsService {

    private final CloseFriendsRepository closeFriendsRepository;
    private final UserRepository         userRepository;
    private final UserMapper             userMapper;

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> getMyCloseFriends(Pageable pageable) {
        UUID myId = authenticatedUserId();
        return closeFriendsRepository.findByOwnerId(myId, pageable)
            .map(cf -> userMapper.toResponse(cf.getFriend()));
    }

    @Override
    public void addCloseFriend(UUID friendId) {
        UUID myId = authenticatedUserId();
        if (myId.equals(friendId))
            throw new BadRequestException("Cannot add yourself to close friends.", "SELF_ACTION");
        if (closeFriendsRepository.existsByIdOwnerIdAndIdFriendId(myId, friendId))
            throw new DuplicateResourceException("User is already a close friend.");

        User me     = findActiveOrThrow(myId);
        User friend = findActiveOrThrow(friendId);

        CloseFriendsList.CloseFriendsId id = new CloseFriendsList.CloseFriendsId(myId, friendId);
        CloseFriendsList cf = CloseFriendsList.builder()
            .id(id).owner(me).friend(friend).build();
        closeFriendsRepository.save(cf);
        log.info("User [{}] added [{}] to close friends", myId, friendId);
    }

    @Override
    public void removeCloseFriend(UUID friendId) {
        UUID myId = authenticatedUserId();
        CloseFriendsList.CloseFriendsId id = new CloseFriendsList.CloseFriendsId(myId, friendId);
        if (!closeFriendsRepository.existsById(id))
            throw new ResourceNotFoundException("Close friend not found.");
        closeFriendsRepository.deleteById(id);
        log.info("User [{}] removed [{}] from close friends", myId, friendId);
    }

    private User findActiveOrThrow(UUID id) {
        return userRepository.findActiveById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    private UUID authenticatedUserId() {
        return SecurityUtils.getCurrentUserId()
            .orElseThrow(() -> new UnauthorizedException("You must be authenticated."));
    }
}
