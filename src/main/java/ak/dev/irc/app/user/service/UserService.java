package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.request.*;
import ak.dev.irc.app.user.dto.response.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface UserService {

    UserResponse        getProfile(UUID userId);
    UserResponse        getProfileByUsername(String username);
    UserResponse        getProfileByEmail(String email);
    UserResponse        getMyProfile();
    UserResponse        updateProfile(UpdateProfileRequest request);

    UserResponse        uploadProfileImage(MultipartFile image);
    UserResponse        removeProfileImage();

    UserLinkResponse    addLink(AddLinkRequest request);
    UserLinkResponse    editLink(UUID linkId, EditLinkRequest request);
    void                removeLink(UUID linkId);

    UserContactResponse addContact(AddContactRequest request);
    UserContactResponse editContact(UUID contactId, EditContactRequest request);
    void                removeContact(UUID contactId);

    Page<UserResponse>  searchUsers(String query, Pageable pageable);

    void                deleteMyAccount();
}