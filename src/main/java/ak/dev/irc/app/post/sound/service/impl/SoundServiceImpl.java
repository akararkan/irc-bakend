package ak.dev.irc.app.post.sound.service.impl;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.Story;
import ak.dev.irc.app.post.enums.SoundCategory;
import ak.dev.irc.app.post.enums.SoundStatus;
import ak.dev.irc.app.post.repository.PostRepository;
import ak.dev.irc.app.post.repository.StoryRepository;
import ak.dev.irc.app.post.sound.dto.AttachSoundRequest;
import ak.dev.irc.app.post.sound.dto.SoundResponse;
import ak.dev.irc.app.post.sound.dto.UploadSoundRequest;
import ak.dev.irc.app.post.sound.entity.PostSound;
import ak.dev.irc.app.post.sound.entity.Sound;
import ak.dev.irc.app.post.sound.repository.PostSoundRepository;
import ak.dev.irc.app.post.sound.repository.SoundRepository;
import ak.dev.irc.app.post.sound.service.SoundService;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
public class SoundServiceImpl implements SoundService {

    private final SoundRepository     soundRepository;
    private final PostSoundRepository postSoundRepository;
    private final UserRepository      userRepository;
    private final PostRepository      postRepository;
    private final StoryRepository     storyRepository;
    private final S3StorageService    s3;

    private static final String SOUND_PREFIX    = "sounds/audio";
    private static final String COVER_PREFIX    = "sounds/covers";
    private static final int    MAX_DURATION_S  = 600;
    private static final List<String> ALLOWED_AUDIO =
        Arrays.asList("audio/mpeg", "audio/webm", "audio/ogg", "audio/mp4", "audio/wav");

    // ══════════════════════════════════════════════════════════════════════════
    //  BROWSE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<SoundResponse> browse(SoundCategory category, String query, Pageable pageable) {
        return soundRepository.browse(category, query, pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SoundResponse> getTrending(int limit) {
        return soundRepository.findTrending(PageRequest.of(0, limit))
            .stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SoundResponse> getRecentlyUsed(UUID userId, int limit) {
        List<UUID> ids = postSoundRepository.findRecentlyUsedSoundIdsByUser(userId, limit);
        return ids.stream()
            .map(id -> soundRepository.findById(id).orElse(null))
            .filter(s -> s != null && s.getStatus() == SoundStatus.APPROVED)
            .map(this::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SoundResponse getById(UUID soundId) {
        return toResponse(findSoundOrThrow(soundId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  UPLOAD
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public SoundResponse uploadSound(UploadSoundRequest req, MultipartFile audioFile,
                                      MultipartFile coverArt, UUID uploaderId) {
        validateAudioFile(audioFile);
        User uploader = findUserOrThrow(uploaderId);

        String audioS3Key = s3.upload(audioFile, SOUND_PREFIX + "/" + uploaderId);
        String audioUrl   = s3.getPublicUrl(audioS3Key);

        String coverS3Key = null, coverUrl = null;
        if (coverArt != null && !coverArt.isEmpty()) {
            coverS3Key = s3.upload(coverArt, COVER_PREFIX + "/" + uploaderId);
            coverUrl   = s3.getPublicUrl(coverS3Key);
        }

        Sound sound = Sound.builder()
            .title(req.title())
            .artistName(req.artistName())
            .description(req.description())
            .category(req.category())
            .status(SoundStatus.PENDING_REVIEW)
            .audioUrl(audioUrl)
            .audioS3Key(audioS3Key)
            .coverArtUrl(coverUrl)
            .coverArtS3Key(coverS3Key)
            .durationSeconds(0) // extracted async
            .mimeType(audioFile.getContentType())
            .fileSizeBytes(audioFile.getSize())
            .sourceUrl(req.sourceUrl())
            .license(req.license())
            .uploader(uploader)
            .build();
        soundRepository.save(sound);

        log.info("User [{}] uploaded sound [{}] — status=PENDING_REVIEW", uploaderId, sound.getId());
        return toResponse(sound);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ADMIN
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public SoundResponse approveSound(UUID soundId, UUID adminId) {
        Sound sound = findSoundOrThrow(soundId);
        sound.setStatus(SoundStatus.APPROVED);
        soundRepository.save(sound);
        log.info("Admin [{}] approved sound [{}]", adminId, soundId);
        return toResponse(sound);
    }

    @Override
    public SoundResponse rejectSound(UUID soundId, UUID adminId, String reason) {
        Sound sound = findSoundOrThrow(soundId);
        sound.setStatus(SoundStatus.REJECTED);
        soundRepository.save(sound);
        log.info("Admin [{}] rejected sound [{}] — reason: {}", adminId, soundId, reason);
        return toResponse(sound);
    }

    @Override
    public void archiveSound(UUID soundId, UUID adminId) {
        Sound sound = findSoundOrThrow(soundId);
        sound.setStatus(SoundStatus.ARCHIVED);
        soundRepository.save(sound);
        log.info("Admin [{}] archived sound [{}]", adminId, soundId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ATTACH / DETACH
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void attachToPost(UUID postId, AttachSoundRequest req, UUID requesterId) {
        Post post = postRepository.findById(postId)
            .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));
        if (!post.getAuthor().getId().equals(requesterId))
            throw new ForbiddenException("Only the post author can attach a sound.", "NOT_AUTHOR");

        Sound sound = findApprovedSoundOrThrow(req.soundId());

        // Remove old sound if any
        postSoundRepository.findByPostId(postId).ifPresent(old -> {
            soundRepository.decrementUseCount(old.getSound().getId());
            postSoundRepository.delete(old);
        });

        PostSound ps = PostSound.builder()
            .post(post).sound(sound)
            .clipStartSeconds(req.clipStartSeconds()).volume(req.volume()).build();
        postSoundRepository.save(ps);
        soundRepository.incrementUseCount(sound.getId());
    }

    @Override
    public void attachToStory(UUID storyId, AttachSoundRequest req, UUID requesterId) {
        Story story = storyRepository.findById(storyId)
            .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));
        if (!story.getAuthor().getId().equals(requesterId))
            throw new ForbiddenException("Only the story author can attach a sound.", "NOT_AUTHOR");

        Sound sound = findApprovedSoundOrThrow(req.soundId());

        postSoundRepository.findByStoryId(storyId).ifPresent(old -> {
            soundRepository.decrementUseCount(old.getSound().getId());
            postSoundRepository.delete(old);
        });

        PostSound ps = PostSound.builder()
            .story(story).sound(sound)
            .clipStartSeconds(req.clipStartSeconds()).volume(req.volume()).build();
        postSoundRepository.save(ps);
        soundRepository.incrementUseCount(sound.getId());
    }

    @Override
    public void detachFromPost(UUID postId, UUID requesterId) {
        postSoundRepository.findByPostId(postId).ifPresent(ps -> {
            soundRepository.decrementUseCount(ps.getSound().getId());
            postSoundRepository.delete(ps);
        });
    }

    @Override
    public void detachFromStory(UUID storyId, UUID requesterId) {
        postSoundRepository.findByStoryId(storyId).ifPresent(ps -> {
            soundRepository.decrementUseCount(ps.getSound().getId());
            postSoundRepository.delete(ps);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SoundResponse toResponse(Sound s) {
        return new SoundResponse(
            s.getId(), s.getTitle(), s.getArtistName(), s.getDescription(),
            s.getCategory(), s.getStatus(),
            s.getAudioUrl(), s.getCoverArtUrl(),
            s.getDurationSeconds(), s.getDefaultClipStart(),
            s.getMimeType(), s.getFileSizeBytes(), s.getWaveformData(),
            s.getSourceUrl(), s.getLicense(),
            s.getUploader() != null ? s.getUploader().getId() : null,
            s.getUploader() != null ? s.getUploader().getUsername() : null,
            s.getUseCount(), s.getCreatedAt()
        );
    }

    private Sound findSoundOrThrow(UUID id) {
        return soundRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Sound", "id", id));
    }

    private Sound findApprovedSoundOrThrow(UUID id) {
        Sound s = findSoundOrThrow(id);
        if (s.getStatus() != SoundStatus.APPROVED)
            throw new BadRequestException("Sound is not approved for use.", "SOUND_NOT_APPROVED");
        return s;
    }

    private User findUserOrThrow(UUID id) {
        return userRepository.findActiveById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    private void validateAudioFile(MultipartFile file) {
        if (file == null || file.isEmpty())
            throw new BadRequestException("Audio file is required.", "EMPTY_FILE");
        String ct = file.getContentType();
        if (ct == null || !ALLOWED_AUDIO.contains(ct.toLowerCase()))
            throw new BadRequestException("Unsupported audio type. Allowed: mp3, webm, ogg, mp4, wav.", "INVALID_FILE_TYPE");
    }
}
