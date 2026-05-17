package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.response.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CloseFriendsService {
    Page<UserResponse> getMyCloseFriends(Pageable pageable);
    void addCloseFriend(UUID friendId);
    void removeCloseFriend(UUID friendId);
}
