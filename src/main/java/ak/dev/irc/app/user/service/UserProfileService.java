package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.request.UpdateSpecializationsRequest;
import ak.dev.irc.app.user.dto.request.UpdateUserProfileRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface UserProfileService {

    UserResponse getPublicProfile(UUID userId);

    UserResponse getMyProfile();

    UserResponse updateMyProfile(UpdateUserProfileRequest request);

    UserResponse uploadAvatar(MultipartFile image);

    UserResponse removeAvatar();

    UserResponse uploadCover(MultipartFile image);

    UserResponse removeCover();

    UserResponse updateSpecializations(UpdateSpecializationsRequest request);
}
