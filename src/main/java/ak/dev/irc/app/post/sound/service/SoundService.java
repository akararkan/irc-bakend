package ak.dev.irc.app.post.sound.service;

import ak.dev.irc.app.post.enums.SoundCategory;
import ak.dev.irc.app.post.sound.dto.AttachSoundRequest;
import ak.dev.irc.app.post.sound.dto.SoundResponse;
import ak.dev.irc.app.post.sound.dto.UploadSoundRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface SoundService {

    Page<SoundResponse> browse(SoundCategory category, String query, Pageable pageable);

    List<SoundResponse> getTrending(int limit);

    List<SoundResponse> getRecentlyUsed(UUID userId, int limit);

    SoundResponse getById(UUID soundId);

    SoundResponse uploadSound(UploadSoundRequest req,
                              MultipartFile audioFile,
                              MultipartFile coverArt,
                              UUID uploaderId);

    SoundResponse approveSound(UUID soundId, UUID adminId);

    SoundResponse rejectSound(UUID soundId, UUID adminId, String reason);

    void archiveSound(UUID soundId, UUID adminId);

    void attachToPost(UUID postId, AttachSoundRequest req, UUID requesterId);

    void attachToStory(UUID storyId, AttachSoundRequest req, UUID requesterId);

    void detachFromPost(UUID postId, UUID requesterId);

    void detachFromStory(UUID storyId, UUID requesterId);
}
