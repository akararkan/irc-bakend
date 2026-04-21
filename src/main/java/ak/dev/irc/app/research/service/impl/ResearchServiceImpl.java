package ak.dev.irc.app.research.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.*;
import ak.dev.irc.app.rabbitmq.publisher.ResearchEventPublisher;
import ak.dev.irc.app.research.dto.request.*;
import ak.dev.irc.app.research.dto.response.*;
import ak.dev.irc.app.research.entity.*;
import ak.dev.irc.app.research.enums.MediaType;
import ak.dev.irc.app.research.enums.ReactionType;
import ak.dev.irc.app.research.enums.ResearchStatus;
import ak.dev.irc.app.research.enums.SourceType;
import ak.dev.irc.app.research.mapper.ResearchMapper;
import ak.dev.irc.app.research.repository.*;
import ak.dev.irc.app.research.service.IrcIdentifierService;
import ak.dev.irc.app.research.service.ResearchService;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ResearchServiceImpl implements ResearchService {

    private final ResearchRepository         researchRepo;
    private final ResearchMediaRepository    mediaRepo;
    private final ResearchSourceRepository   sourceRepo;
    private final ResearchTagRepository      tagRepo;
    private final ResearchCommentRepository  commentRepo;
    private final ResearchReactionRepository reactionRepo;
    private final ResearchSaveRepository     saveRepo;
    private final ResearchViewRepository     viewRepo;
    private final ResearchDownloadRepository downloadRepo;
    private final UserRepository             userRepo;

    private final S3StorageService       s3;
    private final ResearchMapper         mapper;
    private final IrcIdentifierService   ircIdentifierService;
    private final ResearchEventPublisher researchEventPublisher;

    @PersistenceContext
    private EntityManager entityManager;

    // ══════════════════════════════════════════════════════════════════════════
    //  CREATE — with optional inline media files
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Creates and immediately publishes a new research.
     *
     * <p>Media files are uploaded atomically as part of this single call.
     * Each file in {@code files} is matched by index to the corresponding
     * {@link MediaUploadMetadata} entry inside {@code req.mediaFiles()}.
     *
     * <p>If any file upload fails the entire transaction rolls back, S3 keys
     * already written are cleaned up, and the error is surfaced to the caller.
     *
     * @param req          validated create request (JSON part of multipart)
     * @param files        binary files (PDF, images, video, etc.) — may be null/empty
     * @param researcherId the authenticated researcher's UUID
     * @return full {@link ResearchResponse} of the newly published research
     */
    @Override
    @Caching(evict = {
            @CacheEvict(value = "research-feed",  allEntries = true),
            @CacheEvict(value = "trending-tags",  allEntries = true)
    })
    public ResearchResponse create(CreateResearchRequest req,
                                   List<MultipartFile> files,
                                   UUID researcherId) {

        if (req == null) throw new BadRequestException("Request body cannot be null", "NULL_REQUEST_BODY");

        User researcher = findResearcherOrThrow(researcherId);

        // S3 keys uploaded so far — used for rollback if anything fails mid-way
        List<String> uploadedS3Keys = new ArrayList<>();

        try {
            // ── IRC identifiers ────────────────────────────────────────────
            Long seqNum = ((Number) entityManager
                    .createNativeQuery("SELECT nextval('research_irc_seq')")
                    .getSingleResult()).longValue();

            String ircId      = ircIdentifierService.generateIrcId(seqNum);
            String shareToken = ircIdentifierService.generateShareToken();
            String slug       = generateSlug(req.title());

            // ── Generate DOI if not provided ─────────────────────────────
            String doi = (req.doi() != null && !req.doi().isBlank())
                    ? req.doi()
                    : ircIdentifierService.generateDoi(seqNum);

            // ── Build & persist the research entity (auto-published) ────
            Research research = Research.builder()
                    .researcher(researcher)
                    .title(req.title())
                    .slug(slug)
                    .description(req.description())
                    .abstractText(req.abstractText())
                    .keywords(req.keywords())
                    .citation(req.citation())
                    .doi(doi)
                    .visibility(req.visibility())
                    .scheduledPublishAt(req.scheduledPublishAt())
                    .commentsEnabled(req.commentsEnabled())
                    .downloadsEnabled(req.downloadsEnabled())
                    .ircSequenceNumber(seqNum)
                    .ircId(ircId)
                    .shareToken(shareToken)
                    .status(ResearchStatus.PUBLISHED)
                    .publishedAt(LocalDateTime.now())
                    .build();

            research = researchRepo.save(research);

            // ── Tags ───────────────────────────────────────────────────────
            if (!CollectionUtils.isEmpty(req.tags())) {
                saveTags(research, req.tags());
            }

            // ── Sources ────────────────────────────────────────────────────
            if (!CollectionUtils.isEmpty(req.sources())) {
                saveSources(research, req.sources());
            }

            // ── Media files (uploaded atomically with the research) ─────────
            if (!CollectionUtils.isEmpty(files)) {
                List<MediaUploadMetadata> metadataList =
                        req.mediaFiles() != null ? req.mediaFiles() : Collections.emptyList();

                for (int i = 0; i < files.size(); i++) {
                    MultipartFile file = files.get(i);
                    if (file == null || file.isEmpty()) continue;

                    // Each file is validated individually
                    validateFile(file, "media file", null);

                    MediaUploadMetadata meta = i < metadataList.size() ? metadataList.get(i) : null;

                    String s3Key = uploadFileToS3(
                            file,
                            "research/" + research.getId() + "/media",
                            "MEDIA_UPLOAD_FAILED");
                    uploadedS3Keys.add(s3Key);

                    String publicUrl = getPublicUrlFromS3(s3Key);

                    ResearchMedia media = ResearchMedia.builder()
                            .research(research)
                            .fileUrl(publicUrl)
                            .s3Key(s3Key)
                            .originalFileName(sanitizeFileName(file.getOriginalFilename()))
                            .mimeType(file.getContentType())
                            .mediaType(resolveMediaType(file.getContentType()))
                            .fileSize(file.getSize())
                            .caption(meta != null ? meta.caption()       : null)
                            .altText(meta != null ? meta.altText()        : null)
                            .displayOrder(meta != null && meta.displayOrder() != null
                                    ? meta.displayOrder() : i)
                            .build();

                    ResearchMedia saved = mediaRepo.save(media);
                    research.getMediaFiles().add(saved);

                    log.debug("Media file [{}] uploaded for research {} → s3Key={}",
                            i, research.getId(), s3Key);
                }
            }

            // ── Fire published event ──────────────────────────────────────
            researchEventPublisher.publishResearchPublished(research);

            log.info("Research created & published: id={} ircId={} DOI={} researcher={} mediaCount={}",
                    research.getId(), ircId, research.getDoi(), researcherId,
                    research.getMediaFiles().size());

            return mapper.toResponse(research, researcherId);

        } catch (DataIntegrityViolationException e) {
            rollbackS3Uploads(uploadedS3Keys);
            throw new DuplicateResourceException("Research", "title or slug", "already exists");

        } catch (AppException e) {
            // AppExceptions (upload failures, etc.) trigger S3 rollback
            rollbackS3Uploads(uploadedS3Keys);
            throw e;

        } catch (DataAccessException e) {
            rollbackS3Uploads(uploadedS3Keys);
            throw new AppException("Failed to create research due to a database error",
                    HttpStatus.INTERNAL_SERVER_ERROR, "DB_ERROR");

        } catch (Exception e) {
            rollbackS3Uploads(uploadedS3Keys);
            log.error("Unexpected error during research creation: {}", e.getMessage(), e);
            throw new AppException("An unexpected error occurred while creating the research",
                    HttpStatus.INTERNAL_SERVER_ERROR, "UNEXPECTED_ERROR");
        }
    }

    /**
     * Safely deletes already-uploaded S3 objects when a create transaction fails.
     * Errors here are only logged — we must not mask the original failure.
     */
    private void rollbackS3Uploads(List<String> s3Keys) {
        if (CollectionUtils.isEmpty(s3Keys)) return;
        s3Keys.forEach(key -> {
            try {
                s3.delete(key);
                log.warn("Rolled back S3 object: {}", key);
            } catch (Exception ex) {
                log.error("S3 rollback failed for key={}: {}", key, ex.getMessage());
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  UPDATE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Caching(evict = {
            @CacheEvict(value = "research-by-id",   key = "#researchId"),
            @CacheEvict(value = "research-by-slug", allEntries = true),
            @CacheEvict(value = "research-feed",    allEntries = true),
            @CacheEvict(value = "trending-tags",    allEntries = true)
    })
    public ResearchResponse update(UUID researchId, UpdateResearchRequest req, UUID researcherId) {
        if (researchId == null || req == null)
            throw new BadRequestException("Research ID and request body are required", "INVALID_INPUT");

        Research research = findResearchOwnedByOrThrow(researchId, researcherId);

        try {
            updateResearchFields(research, req);

            if (req.tags() != null) {
                tagRepo.deleteAllByResearchId(researchId);
                research.getTags().clear();
                if (!req.tags().isEmpty()) saveTags(research, req.tags());
            }

            if (req.sources() != null) {
                sourceRepo.deleteAllByResearchId(researchId);
                research.getSources().clear();
                if (!req.sources().isEmpty()) saveSources(research, req.sources());
            }

            research = researchRepo.save(research);
            return mapper.toResponse(research, researcherId);

        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("Research was modified by another user. Please refresh and try again.");
        } catch (DataIntegrityViolationException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("slug")) throw new DuplicateResourceException("Research", "slug", "conflict");
            throw new BadRequestException("Update violates data constraints", "CONSTRAINT_VIOLATION");
        }
    }

    private void updateResearchFields(Research research, UpdateResearchRequest req) {
        if (req.title() != null) {
            research.setTitle(req.title());
            String newSlug = generateSlug(req.title());
            if (!newSlug.equals(research.getSlug()) && !researchRepo.existsBySlug(newSlug))
                research.setSlug(newSlug);
        }
        if (req.description() != null)    research.setDescription(req.description());
        if (req.abstractText() != null)   research.setAbstractText(req.abstractText());
        if (req.keywords() != null)       research.setKeywords(req.keywords());
        if (req.citation() != null)       research.setCitation(req.citation());
        if (req.doi() != null)            research.setDoi(req.doi());
        if (req.visibility() != null)     research.setVisibility(req.visibility());
        if (req.scheduledPublishAt() != null) {
            if (req.scheduledPublishAt().isBefore(LocalDateTime.now()))
                throw new BadRequestException("Scheduled publish time must be in the future", "INVALID_SCHEDULE");
            research.setScheduledPublishAt(req.scheduledPublishAt());
        }
        if (req.commentsEnabled() != null)  research.setCommentsEnabled(req.commentsEnabled());
        if (req.downloadsEnabled() != null) research.setDownloadsEnabled(req.downloadsEnabled());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DELETE / LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Caching(evict = {
            @CacheEvict(value = "research-by-id",   key = "#researchId"),
            @CacheEvict(value = "research-by-slug", allEntries = true),
            @CacheEvict(value = "research-feed",    allEntries = true)
    })
    public void delete(UUID researchId, UUID researcherId) {
        Research research = findResearchOwnedByOrThrow(researchId, researcherId);
        cleanupS3Files(research);
        research.setDeletedAt(LocalDateTime.now());
        researchRepo.save(research);
        log.info("Research soft-deleted: {}", researchId);
    }

    private void cleanupS3Files(Research research) {
        research.getMediaFiles().forEach(m -> {
            try { if (m.getS3Key() != null) s3.delete(m.getS3Key()); }
            catch (Exception e) { log.warn("S3 delete failed for media {}: {}", m.getS3Key(), e.getMessage()); }
        });
        research.getSources().stream().filter(s -> s.getS3Key() != null).forEach(s -> {
            try { s3.delete(s.getS3Key()); }
            catch (Exception e) { log.warn("S3 delete failed for source {}: {}", s.getS3Key(), e.getMessage()); }
        });
        try { if (research.getVideoPromoS3Key() != null) s3.delete(research.getVideoPromoS3Key()); }
        catch (Exception e) { log.warn("S3 delete failed for video promo: {}", e.getMessage()); }
        try { if (research.getCoverImageS3Key() != null) s3.delete(research.getCoverImageS3Key()); }
        catch (Exception e) { log.warn("S3 delete failed for cover image: {}", e.getMessage()); }
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "research-by-id",   key = "#researchId"),
            @CacheEvict(value = "research-by-slug", allEntries = true),
            @CacheEvict(value = "research-feed",    allEntries = true)
    })
    public ResearchResponse publish(UUID researchId, UUID researcherId) {
        Research research = findResearchOwnedByOrThrow(researchId, researcherId);

        if (research.isPublished())
            throw new BadRequestException("Research is already published", "ALREADY_PUBLISHED");
        if (research.getTitle() == null || research.getTitle().isBlank())
            throw new BadRequestException("Cannot publish research without a title", "MISSING_TITLE");
        if (research.getAbstractText() == null || research.getAbstractText().isBlank())
            throw new BadRequestException("Cannot publish research without an abstract", "MISSING_ABSTRACT");

        if (research.getDoi() == null || research.getDoi().isBlank())
            research.setDoi(ircIdentifierService.generateDoi(research.getIrcSequenceNumber()));

        research.setStatus(ResearchStatus.PUBLISHED);
        research.setPublishedAt(LocalDateTime.now());
        research = researchRepo.save(research);

        researchEventPublisher.publishResearchPublished(research);
        log.info("Research published: {} [{}] DOI={}", research.getId(), research.getIrcId(), research.getDoi());
        return mapper.toResponse(research, researcherId);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "research-by-id",   key = "#researchId"),
            @CacheEvict(value = "research-by-slug", allEntries = true),
            @CacheEvict(value = "research-feed",    allEntries = true)
    })
    public ResearchResponse archive(UUID researchId, UUID researcherId) {
        Research research = findResearchOwnedByOrThrow(researchId, researcherId);
        if (research.getStatus() == ResearchStatus.ARCHIVED)
            throw new BadRequestException("Research is already archived", "ALREADY_ARCHIVED");
        research.setStatus(ResearchStatus.ARCHIVED);
        research = researchRepo.save(research);
        log.info("Research archived: {} by {}", researchId, researcherId);
        return mapper.toResponse(research, researcherId);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "research-by-id",   key = "#researchId"),
            @CacheEvict(value = "research-by-slug", allEntries = true),
            @CacheEvict(value = "research-feed",    allEntries = true)
    })
    public ResearchResponse retract(UUID researchId, UUID researcherId) {
        Research research = findResearchOwnedByOrThrow(researchId, researcherId);
        if (!research.isPublished())
            throw new BadRequestException("Only published research can be retracted", "NOT_PUBLISHED");
        research.setStatus(ResearchStatus.RETRACTED);
        research = researchRepo.save(research);
        log.info("Research retracted: {} by {}", researchId, researcherId);
        return mapper.toResponse(research, researcherId);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "research-by-id",   key = "#researchId"),
            @CacheEvict(value = "research-by-slug", allEntries = true),
            @CacheEvict(value = "research-feed",    allEntries = true)
    })
    public ResearchResponse unpublish(UUID researchId, UUID researcherId) {
        Research research = findResearchOwnedByOrThrow(researchId, researcherId);
        if (!research.isPublished())
            throw new BadRequestException("Research is not published", "NOT_PUBLISHED");
        research.setStatus(ResearchStatus.DRAFT);
        research.setPublishedAt(null);
        research = researchRepo.save(research);
        log.info("Research unpublished (reverted to draft): {} by {}", researchId, researcherId);
        return mapper.toResponse(research, researcherId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SCHEDULED AUTO-PUBLISH
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Scheduled(fixedRate = 60_000)
    public void processScheduledPublications() {
        LocalDateTime now = LocalDateTime.now();
        List<Research> scheduled = researchRepo
                .findByStatusAndScheduledPublishAtBeforeAndDeletedAtIsNull(ResearchStatus.DRAFT, now);
        for (Research research : scheduled) {
            try {
                if (research.getDoi() == null || research.getDoi().isBlank())
                    research.setDoi(ircIdentifierService.generateDoi(research.getIrcSequenceNumber()));
                research.setStatus(ResearchStatus.PUBLISHED);
                research.setPublishedAt(now);
                researchRepo.save(research);
                researchEventPublisher.publishResearchPublished(research);
                log.info("Scheduled research auto-published: {} [{}]", research.getId(), research.getIrcId());
            } catch (Exception e) {
                log.error("Failed to auto-publish scheduled research {}: {}", research.getId(), e.getMessage());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MEDIA — individual post-creation uploads
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @CacheEvict(value = "research-by-id", key = "#researchId")
    public ResearchResponse uploadVideoPromo(UUID researchId, MultipartFile video, UUID researcherId) {
        validateFile(video, "video", Arrays.asList("video/mp4", "video/webm", "video/quicktime"));
        Research research = findResearchOwnedByOrThrow(researchId, researcherId);
        try {
            if (research.getVideoPromoS3Key() != null) {
                try { s3.delete(research.getVideoPromoS3Key()); } catch (Exception e) { log.warn("Old video promo delete failed: {}", e.getMessage()); }
            }
            String s3Key = uploadFileToS3(video, "research/" + researchId + "/promo", "VIDEO_UPLOAD_FAILED");
            research.setVideoPromoS3Key(s3Key);
            research.setVideoPromoUrl(getPublicUrlFromS3(s3Key));
            researchRepo.save(research);
            return mapper.toResponse(research, researcherId);
        } catch (AppException e) { throw e; }
        catch (Exception e) {
            throw new AppException("Failed to upload video promo", HttpStatus.INTERNAL_SERVER_ERROR, "VIDEO_UPLOAD_ERROR");
        }
    }

    @Override
    @CacheEvict(value = "research-by-id", key = "#researchId")
    public ResearchResponse removeVideoPromo(UUID researchId, UUID researcherId) {
        Research research = findResearchOwnedByOrThrow(researchId, researcherId);
        if (research.getVideoPromoS3Key() != null) {
            try { s3.delete(research.getVideoPromoS3Key()); } catch (Exception e) { log.warn("Video promo S3 delete failed: {}", e.getMessage()); }
        }
        research.setVideoPromoS3Key(null);
        research.setVideoPromoUrl(null);
        research.setVideoPromoDurationSeconds(null);
        research.setVideoPromoThumbnailUrl(null);
        researchRepo.save(research);
        return mapper.toResponse(research, researcherId);
    }

    @Override
    @CacheEvict(value = "research-by-id", key = "#researchId")
    public ResearchResponse uploadCoverImage(UUID researchId, MultipartFile image, UUID researcherId) {
        validateFile(image, "image", Arrays.asList("image/jpeg", "image/png", "image/webp", "image/gif"));
        Research research = findResearchOwnedByOrThrow(researchId, researcherId);
        try {
            if (research.getCoverImageS3Key() != null) {
                try { s3.delete(research.getCoverImageS3Key()); } catch (Exception e) { log.warn("Old cover image delete failed: {}", e.getMessage()); }
            }
            String s3Key = uploadFileToS3(image, "research/" + researchId + "/cover", "COVER_UPLOAD_FAILED");
            research.setCoverImageS3Key(s3Key);
            research.setCoverImageUrl(getPublicUrlFromS3(s3Key));
            researchRepo.save(research);
            return mapper.toResponse(research, researcherId);
        } catch (AppException e) { throw e; }
        catch (Exception e) {
            throw new AppException("Failed to upload cover image", HttpStatus.INTERNAL_SERVER_ERROR, "COVER_UPLOAD_ERROR");
        }
    }

    @Override
    @CacheEvict(value = "research-by-id", key = "#researchId")
    public ResearchResponse removeCoverImage(UUID researchId, UUID researcherId) {
        Research research = findResearchOwnedByOrThrow(researchId, researcherId);
        if (research.getCoverImageS3Key() != null) {
            try { s3.delete(research.getCoverImageS3Key()); } catch (Exception e) { log.warn("Cover image S3 delete failed: {}", e.getMessage()); }
        }
        research.setCoverImageS3Key(null);
        research.setCoverImageUrl(null);
        researchRepo.save(research);
        return mapper.toResponse(research, researcherId);
    }

    @Override
    public MediaResponse addMediaFile(UUID researchId, MultipartFile file, String caption,
                                      String altText, Integer displayOrder, UUID researcherId) {
        validateFile(file, "file", null);
        Research research = findResearchOwnedByOrThrow(researchId, researcherId);
        try {
            String s3Key     = uploadFileToS3(file, "research/" + researchId + "/media", "MEDIA_UPLOAD_FAILED");
            String publicUrl = getPublicUrlFromS3(s3Key);
            ResearchMedia media = ResearchMedia.builder()
                    .research(research).fileUrl(publicUrl).s3Key(s3Key)
                    .originalFileName(sanitizeFileName(file.getOriginalFilename()))
                    .mimeType(file.getContentType())
                    .mediaType(resolveMediaType(file.getContentType()))
                    .fileSize(file.getSize())
                    .displayOrder(displayOrder != null ? displayOrder : 0)
                    .caption(caption).altText(altText).build();
            return mapper.toMediaResponse(mediaRepo.save(media));
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("Invalid media metadata", "MEDIA_METADATA_ERROR");
        } catch (Exception e) {
            throw new AppException("Failed to add media file", HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_ADD_ERROR");
        }
    }

    @Override
    public MediaResponse updateMediaMetadata(UUID researchId, UUID mediaId,
                                             UpdateMediaRequest request, UUID researcherId) {
        if (mediaId == null || request == null)
            throw new BadRequestException("Media ID and request body are required", "INVALID_INPUT");
        findResearchOwnedByOrThrow(researchId, researcherId);
        ResearchMedia media = mediaRepo.findById(mediaId)
                .orElseThrow(() -> new ResourceNotFoundException("Media", "id", mediaId));
        if (!media.getResearch().getId().equals(researchId))
            throw new ForbiddenException("Media does not belong to this research");
        if (request.caption() != null)         media.setCaption(request.caption());
        if (request.altText() != null)         media.setAltText(request.altText());
        if (request.displayOrder() != null)    media.setDisplayOrder(request.displayOrder());
        if (request.durationSeconds() != null) media.setDurationSeconds(request.durationSeconds());
        if (request.widthPx() != null)         media.setWidthPx(request.widthPx());
        if (request.heightPx() != null)        media.setHeightPx(request.heightPx());
        return mapper.toMediaResponse(mediaRepo.save(media));
    }

    @Override
    public void removeMediaFile(UUID researchId, UUID mediaId, UUID researcherId) {
        if (mediaId == null) throw new BadRequestException("Media ID is required", "MISSING_MEDIA_ID");
        findResearchOwnedByOrThrow(researchId, researcherId);
        ResearchMedia media = mediaRepo.findById(mediaId)
                .orElseThrow(() -> new ResourceNotFoundException("Media", "id", mediaId));
        if (!media.getResearch().getId().equals(researchId))
            throw new ForbiddenException("Media does not belong to this research");
        try {
            if (media.getS3Key() != null) s3.delete(media.getS3Key());
            mediaRepo.delete(media);
        } catch (DataAccessException e) {
            throw new AppException("Failed to remove media file", HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_DELETE_ERROR");
        }
    }

    @Override
    public SourceResponse uploadSourceFile(UUID researchId, UUID sourceId,
                                           MultipartFile file, UUID researcherId) {
        if (sourceId == null) throw new BadRequestException("Source ID is required", "MISSING_SOURCE_ID");
        validateFile(file, "document", Arrays.asList(
                "application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain"));
        findResearchOwnedByOrThrow(researchId, researcherId);
        ResearchSource source = sourceRepo.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Source", "id", sourceId));
        if (!source.getResearch().getId().equals(researchId))
            throw new ForbiddenException("Source does not belong to this research");
        try {
            if (source.getS3Key() != null) {
                try { s3.delete(source.getS3Key()); } catch (Exception e) { log.warn("Old source file delete failed: {}", e.getMessage()); }
            }
            String s3Key = uploadFileToS3(file, "research/" + researchId + "/sources", "SOURCE_UPLOAD_FAILED");
            source.setS3Key(s3Key);
            source.setFileUrl(getPublicUrlFromS3(s3Key));
            source.setOriginalFileName(sanitizeFileName(file.getOriginalFilename()));
            source.setMimeType(file.getContentType());
            source.setFileSize(file.getSize());
            source.setSourceType(SourceType.MEDIA_FILE);
            return mapper.toSourceResponse(sourceRepo.save(source));
        } catch (AppException e) { throw e; }
        catch (Exception e) {
            throw new AppException("Failed to upload source file", HttpStatus.INTERNAL_SERVER_ERROR, "SOURCE_UPLOAD_ERROR");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  READ
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "research-by-id", key = "#researchId", condition = "#currentUserId == null")
    public ResearchResponse getById(UUID researchId, UUID currentUserId) {
        if (researchId == null) throw new BadRequestException("Research ID is required", "MISSING_RESEARCH_ID");
        Research research = researchRepo.findByIdAndDeletedAtIsNull(researchId)
                .orElseThrow(() -> new ResourceNotFoundException("Research", "id", researchId));
        return mapper.toResponse(research, currentUserId);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "research-by-slug", key = "#slug", condition = "#currentUserId == null")
    public ResearchResponse getBySlug(String slug, UUID currentUserId) {
        if (slug == null || slug.isBlank())
            throw new BadRequestException("Slug is required", "MISSING_SLUG");
        Research research = researchRepo.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Research", "slug", slug));
        return mapper.toResponse(research, currentUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public ResearchResponse getByShareToken(String shareToken, UUID currentUserId) {
        if (shareToken == null || shareToken.isBlank())
            throw new BadRequestException("Share token is required", "MISSING_TOKEN");
        Research research = researchRepo.findByShareTokenAndDeletedAtIsNull(shareToken)
                .orElseThrow(() -> new ResourceNotFoundException("Research", "token", shareToken));
        return mapper.toResponse(research, currentUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResearchSummaryResponse> getFeed(Pageable pageable, UUID currentUserId) {
        return researchRepo
                .findByStatusAndDeletedAtIsNullOrderByPublishedAtDesc(ResearchStatus.PUBLISHED, pageable)
                .map(r -> mapper.toSummary(r, currentUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResearchSummaryResponse> getByResearcher(UUID researcherId, Pageable pageable, UUID currentUserId) {
        if (researcherId == null) throw new BadRequestException("Researcher ID is required", "MISSING_RESEARCHER_ID");
        return researchRepo
                .findByResearcherIdAndStatusAndDeletedAtIsNull(researcherId, ResearchStatus.PUBLISHED, pageable)
                .map(r -> mapper.toSummary(r, currentUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResearchSummaryResponse> search(String query, Pageable pageable, UUID currentUserId) {
        if (query == null || query.trim().length() < 2)
            throw new BadRequestException("Search query must be at least 2 characters", "INVALID_QUERY");
        return researchRepo.searchPublished(query, pageable).map(r -> mapper.toSummary(r, currentUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResearchSummaryResponse> fullTextSearch(String query, Pageable pageable, UUID currentUserId) {
        if (query == null || query.trim().length() < 2)
            throw new BadRequestException("Search query must be at least 2 characters", "INVALID_QUERY");
        String tsQuery = Arrays.stream(query.trim().split("\\s+"))
                .filter(w -> !w.isBlank())
                .map(w -> w + ":*")
                .collect(Collectors.joining(" & "));
        return researchRepo.fullTextSearch(tsQuery, pageable).map(r -> mapper.toSummary(r, currentUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResearchSummaryResponse> searchByTags(List<String> tags, Pageable pageable, UUID currentUserId) {
        if (tags == null || tags.isEmpty())
            throw new BadRequestException("At least one tag is required", "MISSING_TAGS");
        List<String> normalised = tags.stream()
                .map(t -> t != null ? t.trim().toLowerCase() : "")
                .filter(t -> !t.isEmpty()).distinct().toList();
        if (normalised.isEmpty()) throw new BadRequestException("Tags cannot be empty", "INVALID_TAGS");
        return researchRepo.findByTags(normalised, pageable).map(r -> mapper.toSummary(r, currentUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResearchSummaryResponse> getMyDrafts(UUID researcherId, Pageable pageable) {
        if (researcherId == null) throw new BadRequestException("Researcher ID is required", "MISSING_RESEARCHER_ID");
        return researchRepo
                .findByResearcherIdAndStatusAndDeletedAtIsNull(researcherId, ResearchStatus.DRAFT, pageable)
                .map(r -> mapper.toSummary(r, researcherId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResearchSummaryResponse> getMyResearches(UUID researcherId, Pageable pageable) {
        if (researcherId == null) throw new BadRequestException("Researcher ID is required", "MISSING_RESEARCHER_ID");
        return researchRepo
                .findByResearcherIdAndDeletedAtIsNull(researcherId, pageable)
                .map(r -> mapper.toSummary(r, researcherId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REACTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void react(UUID researchId, ReactRequest request, UUID userId) {
        if (researchId == null || request == null || request.reactionType() == null)
            throw new BadRequestException("Research ID and reaction type are required", "INVALID_REACTION");
        Research research = findPublishedOrThrow(researchId);
        User user = findUserOrThrow(userId);
        ResearchReactionId rId = new ResearchReactionId(researchId, userId);
        try {
            Optional<ResearchReaction> existing = reactionRepo.findById(rId);
            if (existing.isPresent()) {
                existing.get().setReactionType(request.reactionType());
                reactionRepo.save(existing.get());
            } else {
                reactionRepo.save(ResearchReaction.builder()
                        .id(rId).research(research).user(user)
                        .reactionType(request.reactionType()).build());
                researchRepo.adjustReactionCount(researchId, 1);
                researchEventPublisher.publishReacted(research, user, request.reactionType());
            }
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("Invalid reaction data", "REACTION_ERROR");
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("Reaction update conflict. Please retry.");
        }
    }

    @Override
    public void removeReaction(UUID researchId, UUID userId) {
        if (researchId == null || userId == null)
            throw new BadRequestException("Research ID and User ID are required", "INVALID_INPUT");
        ResearchReactionId rId = new ResearchReactionId(researchId, userId);
        if (reactionRepo.existsById(rId)) {
            reactionRepo.deleteById(rId);
            researchRepo.adjustReactionCount(researchId, -1);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<ReactionType, Long> getReactionBreakdown(UUID researchId) {
        if (researchId == null) throw new BadRequestException("Research ID is required", "MISSING_RESEARCH_ID");
        List<Object[]> rows = reactionRepo.countByResearchGroupedByType(researchId);
        Map<ReactionType, Long> breakdown = new EnumMap<>(ReactionType.class);
        for (Object[] row : rows) breakdown.put((ReactionType) row[0], (Long) row[1]);
        return breakdown;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COMMENTS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public CommentResponse addComment(UUID researchId, AddCommentRequest request, UUID userId) {
        if (researchId == null || request == null)
            throw new BadRequestException("Research ID and comment data are required", "INVALID_INPUT");

        // At least one of: text, media, or voice must be present
        boolean hasContent = request.content() != null && !request.content().isBlank();
        boolean hasMedia   = request.mediaUrl() != null && !request.mediaUrl().isBlank();
        boolean hasVoice   = request.voiceUrl() != null && !request.voiceUrl().isBlank();
        if (!hasContent && !hasMedia && !hasVoice)
            throw new BadRequestException("Comment must have text, media, or voice content", "EMPTY_COMMENT");
        if (hasContent && request.content().length() > 5000)
            throw new BadRequestException("Comment exceeds maximum length of 5000 characters", "COMMENT_TOO_LONG");

        Research research = findPublishedOrThrow(researchId);
        if (!research.isCommentsEnabled())
            throw new BadRequestException("Comments are disabled for this research", "COMMENTS_DISABLED");
        User user = findUserOrThrow(userId);

        try {
            ResearchComment comment = ResearchComment.builder()
                    .research(research).user(user)
                    .content(hasContent ? request.content().trim() : null)
                    .mediaUrl(request.mediaUrl())
                    .mediaType(request.mediaType())
                    .mediaThumbnailUrl(request.mediaThumbnailUrl())
                    .build();

            if (request.parentId() != null) {
                ResearchComment parent = commentRepo.findById(request.parentId())
                        .orElseThrow(() -> new ResourceNotFoundException("Parent comment", "id", request.parentId()));
                if (!parent.getResearch().getId().equals(researchId))
                    throw new BadRequestException("Parent comment does not belong to this research", "INVALID_PARENT");
                if (parent.getDeletedAt() != null)
                    throw new BadRequestException("Cannot reply to a deleted comment", "PARENT_DELETED");
                comment.setParent(parent);
                parent.setReplyCount(parent.getReplyCount() + 1);
                commentRepo.save(parent);
            }

            comment = commentRepo.save(comment);
            researchRepo.adjustCommentCount(researchId, 1);
            researchEventPublisher.publishCommented(research, user, comment);
            // return full view for the commenter (they should see their own comment even if hidden)
            return mapper.toCommentResponse(comment, true);

        } catch (ResourceNotFoundException | BadRequestException e) { throw e; }
        catch (DataIntegrityViolationException e) {
            throw new BadRequestException("Invalid comment data", "COMMENT_DATA_ERROR");
        }
    }

    @Override
    @Transactional
    public CommentResponse addCommentWithMedia(UUID researchId, AddCommentRequest request, UUID userId,
                                               MultipartFile media, MultipartFile voice) {
        String mediaUrl = request.mediaUrl();
        String mediaType = request.mediaType();
        String mediaThumbnailUrl = request.mediaThumbnailUrl();
        String voiceUrl = request.voiceUrl();

        // Upload voice recording if present
        if (voice != null && !voice.isEmpty()) {
            String voiceKey = s3.upload(voice, "research/comments/voice");
            voiceUrl = s3.getPublicUrl(voiceKey);
        }

        // Upload media file if present
        if (media != null && !media.isEmpty()) {
            String mediaKey = s3.upload(media, "research/comments/media");
            mediaUrl = s3.getPublicUrl(mediaKey);
            String contentType = media.getContentType();
            if (contentType != null && contentType.startsWith("video")) {
                mediaType = "VIDEO";
            } else {
                mediaType = "IMAGE";
            }
        }

        // Build a new request with the uploaded URLs
        AddCommentRequest enriched = new AddCommentRequest(
                request.content(), request.parentId(),
                mediaUrl, mediaType, mediaThumbnailUrl,
                voiceUrl, request.voiceDurationSeconds(),
                request.voiceTranscript(), request.waveformData()
        );

        return addComment(researchId, enriched, userId);
    }

    @Override
    public CommentResponse editComment(UUID researchId, UUID commentId,
                                       EditCommentRequest request, UUID userId) {
        if (commentId == null || request == null)
            throw new BadRequestException("Comment ID and request body are required", "INVALID_INPUT");
        if (request.content() == null || request.content().isBlank())
            throw new BadRequestException("Comment content cannot be empty", "EMPTY_COMMENT");
        if (request.content().length() > 5000)
            throw new BadRequestException("Comment exceeds maximum length of 5000 characters", "COMMENT_TOO_LONG");

        ResearchComment comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", "id", commentId));
        if (!comment.getResearch().getId().equals(researchId))
            throw new ForbiddenException("Comment does not belong to this research");
        if (!comment.getUser().getId().equals(userId))
            throw new ForbiddenException("You can only edit your own comments");
        if (comment.getDeletedAt() != null)
            throw new BadRequestException("Cannot edit a deleted comment", "COMMENT_DELETED");

        comment.setContent(request.content().trim());
        comment.setEdited(true);
        comment.setEditedAt(LocalDateTime.now());
        comment.audit(AuditAction.UPDATE, "Edited comment");
        ResearchComment saved = commentRepo.save(comment);
        // commenter should see their edited comment
        return mapper.toCommentResponse(saved, true);
    }

    @Override
    public void deleteComment(UUID researchId, UUID commentId, UUID userId) {
        if (commentId == null || userId == null)
            throw new BadRequestException("Comment ID and User ID are required", "INVALID_INPUT");
        ResearchComment comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", "id", commentId));
        if (!comment.getResearch().getId().equals(researchId))
            throw new ForbiddenException("Comment does not belong to this research");
        // Allow either the comment owner or the research owner to delete
        boolean isCommentOwner = comment.getUser().getId().equals(userId);
        boolean isResearchOwner = comment.getResearch().getResearcher() != null
                && comment.getResearch().getResearcher().getId().equals(userId);
        if (!isCommentOwner && !isResearchOwner)
            throw new ForbiddenException("You can only delete your own comments or comments on your research");
        if (comment.getDeletedAt() != null)
            throw new BadRequestException("Comment is already deleted", "ALREADY_DELETED");

        comment.setDeletedAt(LocalDateTime.now());
        comment.audit(AuditAction.DELETE, "Deleted comment");
        commentRepo.save(comment);
        researchRepo.adjustCommentCount(researchId, -1);
    }

    @Override
    public void hideComment(UUID researchId, UUID commentId, UUID userId) {
        if (commentId == null || userId == null)
            throw new BadRequestException("Comment ID and User ID are required", "INVALID_INPUT");
        ResearchComment comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", "id", commentId));
        if (!comment.getResearch().getId().equals(researchId))
            throw new ForbiddenException("Comment does not belong to this research");
        if (comment.getDeletedAt() != null)
            throw new BadRequestException("Cannot hide a deleted comment", "COMMENT_DELETED");

        // Allow research owner or the commenter to hide the comment
        boolean isCommentOwner = comment.getUser().getId().equals(userId);
        boolean isResearchOwner = comment.getResearch().getResearcher() != null
                && comment.getResearch().getResearcher().getId().equals(userId);
        if (!isCommentOwner && !isResearchOwner)
            throw new ForbiddenException("You can only hide your own comments or comments on your research");

        // set hidden metadata
        if (!comment.isHidden()) {
            comment.setHidden(true);
            comment.setHiddenAt(LocalDateTime.now());
            comment.setHiddenBy(findUserOrThrow(userId));
            comment.audit(AuditAction.UPDATE, "Hidden comment");
            commentRepo.save(comment);
        }
    }

    @Override
    public void unhideComment(UUID researchId, UUID commentId, UUID userId) {
        if (commentId == null || userId == null)
            throw new BadRequestException("Comment ID and User ID are required", "INVALID_INPUT");
        ResearchComment comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", "id", commentId));
        if (!comment.getResearch().getId().equals(researchId))
            throw new ForbiddenException("Comment does not belong to this research");
        if (!comment.isHidden())
            throw new BadRequestException("Comment is not hidden", "NOT_HIDDEN");

        // Allow research owner or the commenter to unhide
        boolean isCommentOwner = comment.getUser().getId().equals(userId);
        boolean isResearchOwner = comment.getResearch().getResearcher() != null
                && comment.getResearch().getResearcher().getId().equals(userId);
        if (!isCommentOwner && !isResearchOwner)
            throw new ForbiddenException("You can only unhide your own comments or comments on your research");

        comment.setHidden(false);
        comment.setHiddenAt(null);
        comment.setHiddenBy(null);
        comment.audit(AuditAction.UPDATE, "Unhidden comment");
        commentRepo.save(comment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CommentResponse> getComments(UUID researchId, Pageable pageable, UUID currentUserId) {
        if (researchId == null) throw new BadRequestException("Research ID is required", "MISSING_RESEARCH_ID");

        // Determine if the requester is the research owner — owners can view hidden comments
        boolean isResearchOwner = false;
        if (currentUserId != null) {
            Research research = researchRepo.findByIdAndDeletedAtIsNull(researchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Research", "id", researchId));
            isResearchOwner = research.getResearcher() != null && research.getResearcher().getId().equals(currentUserId);
        }

        Page<ResearchComment> page = isResearchOwner
                ? commentRepo.findByResearchIdAndParentIsNullAndDeletedAtIsNullOrderByCreatedAtDesc(researchId, pageable)
                : commentRepo.findByResearchIdAndParentIsNullAndDeletedAtIsNullAndIsHiddenFalseOrderByCreatedAtDesc(researchId, pageable);

        final boolean canViewHidden = isResearchOwner;
        return page.map(c -> mapper.toCommentResponse(c, canViewHidden));
    }

    @Override
    public void likeComment(UUID researchId, UUID commentId, UUID userId) {
        if (commentId == null || userId == null)
            throw new BadRequestException("Comment ID and User ID are required", "INVALID_INPUT");
        ResearchComment comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", "id", commentId));
        if (!comment.getResearch().getId().equals(researchId))
            throw new ForbiddenException("Comment does not belong to this research");
        if (comment.getDeletedAt() != null)
            throw new BadRequestException("Cannot like a deleted comment", "COMMENT_DELETED");
        if (commentRepo.existsLikeByCommentIdAndUserId(commentId, userId))
            throw new DuplicateResourceException("Comment like", "user_comment", userId + "_" + commentId);
        commentRepo.insertCommentLike(commentId, userId);
        comment.setLikeCount(comment.getLikeCount() + 1);
        commentRepo.save(comment);
    }

    @Override
    public void unlikeComment(UUID researchId, UUID commentId, UUID userId) {
        if (commentId == null || userId == null)
            throw new BadRequestException("Comment ID and User ID are required", "INVALID_INPUT");
        ResearchComment comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", "id", commentId));
        if (!comment.getResearch().getId().equals(researchId))
            throw new ForbiddenException("Comment does not belong to this research");
        if (commentRepo.existsLikeByCommentIdAndUserId(commentId, userId)) {
            commentRepo.deleteCommentLike(commentId, userId);
            comment.setLikeCount(Math.max(0, comment.getLikeCount() - 1));
            commentRepo.save(comment);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SAVE / BOOKMARK
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void saveResearch(UUID researchId, String collectionName, UUID userId) {
        if (researchId == null || userId == null)
            throw new BadRequestException("Research ID and User ID are required", "INVALID_INPUT");
        Research research = findPublishedOrThrow(researchId);
        User user = findUserOrThrow(userId);
        ResearchSaveId sId = new ResearchSaveId(researchId, userId);
        if (saveRepo.existsById(sId))
            throw new DuplicateResourceException("Research save", "user_research", userId + "_" + researchId);
        saveRepo.save(ResearchSave.builder()
                .id(sId).research(research).user(user)
                .collectionName(collectionName != null ? collectionName.trim() : "Default").build());
        researchRepo.adjustSaveCount(researchId, 1);
    }

    @Override
    public void unsaveResearch(UUID researchId, UUID userId) {
        if (researchId == null || userId == null)
            throw new BadRequestException("Research ID and User ID are required", "INVALID_INPUT");
        ResearchSaveId sId = new ResearchSaveId(researchId, userId);
        if (saveRepo.existsById(sId)) {
            saveRepo.deleteById(sId);
            researchRepo.adjustSaveCount(researchId, -1);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResearchSummaryResponse> getSavedResearches(UUID userId, Pageable pageable) {
        if (userId == null) throw new BadRequestException("User ID is required", "MISSING_USER_ID");
        return saveRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(s -> mapper.toSummary(s.getResearch(), userId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ResearchSummaryResponse> getSavedByCollection(UUID userId, String collectionName, Pageable pageable) {
        if (userId == null) throw new BadRequestException("User ID is required", "MISSING_USER_ID");
        if (collectionName == null || collectionName.isBlank())
            throw new BadRequestException("Collection name is required", "MISSING_COLLECTION_NAME");
        return saveRepo.findByUserIdAndCollectionNameOrderByCreatedAtDesc(userId, collectionName.trim(), pageable)
                .map(s -> mapper.toSummary(s.getResearch(), userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getUserCollections(UUID userId) {
        if (userId == null) throw new BadRequestException("User ID is required", "MISSING_USER_ID");
        return saveRepo.findDistinctCollectionNamesByUserId(userId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  VIEW & DOWNLOAD TRACKING
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void recordView(UUID researchId, UUID userId, String ipAddress, String userAgent) {
        if (researchId == null) throw new BadRequestException("Research ID is required", "MISSING_RESEARCH_ID");
        if (ipAddress == null || ipAddress.isBlank()) ipAddress = "unknown";
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        try {
            boolean duplicate = userId != null
                    ? viewRepo.existsByResearchIdAndUserIdAndCreatedAtAfter(researchId, userId, cutoff)
                    : viewRepo.existsByResearchIdAndIpAddressAndUserIsNullAndCreatedAtAfter(researchId, ipAddress, cutoff);
            if (!duplicate) {
                researchEventPublisher.publishViewed(researchId, userId, ipAddress, userAgent);
            }
        } catch (DataAccessException e) {
            log.error("Failed to check view duplicate for research {}: {}", researchId, e.getMessage());
        }
    }

    @Override
    public String recordDownload(UUID researchId, UUID mediaId, UUID userId, String ipAddress) {
        if (researchId == null) throw new BadRequestException("Research ID is required", "MISSING_RESEARCH_ID");
        Research research = findPublishedOrThrow(researchId);
        if (!research.isDownloadsEnabled())
            throw new BadRequestException("Downloads are disabled for this research", "DOWNLOADS_DISABLED");
        String s3Key = null;
        if (mediaId != null) {
            ResearchMedia media = mediaRepo.findById(mediaId)
                    .orElseThrow(() -> new ResourceNotFoundException("Media", "id", mediaId));
            if (!media.getResearch().getId().equals(researchId))
                throw new ForbiddenException("Media does not belong to this research");
            if (media.getS3Key() == null)
                throw new BadRequestException("Media file not available for download", "FILE_NOT_AVAILABLE");
            s3Key = media.getS3Key();
        }
        researchEventPublisher.publishDownloaded(
                researchId, mediaId, userId, ipAddress != null ? ipAddress : "unknown");
        if (s3Key != null) {
            try { return s3.getPreSignedUrl(s3Key, 30); }
            catch (Exception e) {
                log.error("Error generating pre-signed URL for {}: {}", s3Key, e.getMessage());
                throw new AppException("Failed to generate download link",
                        HttpStatus.INTERNAL_SERVER_ERROR, "URL_GENERATION_ERROR");
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SHARE, CITATIONS & TRENDING TAGS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public String getShareLink(UUID researchId) {
        if (researchId == null) throw new BadRequestException("Research ID is required", "MISSING_RESEARCH_ID");
        Research research = findPublishedOrThrow(researchId);
        try { researchRepo.incrementShareCount(researchId); }
        catch (DataAccessException e) { log.error("Error incrementing share count: {}", e.getMessage()); }
        return ircIdentifierService.buildShareUrl(research.getShareToken());
    }

    @Override
    public void incrementCitationCount(UUID researchId) {
        if (researchId == null) throw new BadRequestException("Research ID is required", "MISSING_RESEARCH_ID");
        findPublishedOrThrow(researchId);
        researchRepo.incrementCitationCount(researchId);
        log.info("Citation count incremented for research {}", researchId);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "trending-tags", key = "#limit")
    public List<String> getTrendingTags(int limit) {
        if (limit <= 0 || limit > 100) limit = 10;
        try {
            return new ArrayList<>(tagRepo.findTrendingTags(PageRequest.of(0, limit))
                    .stream().map(row -> (String) row[0]).filter(Objects::nonNull).toList());
        } catch (DataAccessException e) {
            throw new AppException("Failed to fetch trending tags", HttpStatus.INTERNAL_SERVER_ERROR, "TAG_FETCH_ERROR");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private User findResearcherOrThrow(UUID userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.getRole() != Role.SCHOLAR
                && user.getRole() != Role.RESEARCHER
                && user.getRole() != Role.ADMIN
                && user.getRole() != Role.SUPER_ADMIN)
            throw new ForbiddenException("Only researchers can manage researches");
        return user;
    }

    private User findUserOrThrow(UUID userId) {
        return userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }

    private Research findPublishedOrThrow(UUID researchId) {
        Research r = researchRepo.findByIdAndDeletedAtIsNull(researchId)
                .orElseThrow(() -> new ResourceNotFoundException("Research", "id", researchId));
        if (!r.isPublished()) throw new BadRequestException("Research is not published yet", "NOT_PUBLISHED");
        return r;
    }

    private Research findResearchOwnedByOrThrow(UUID researchId, UUID researcherId) {
        Research r = researchRepo.findByIdAndDeletedAtIsNull(researchId)
                .orElseThrow(() -> new ResourceNotFoundException("Research", "id", researchId));
        if (!r.getResearcher().getId().equals(researcherId))
            throw new ForbiddenException("You do not own this research");
        return r;
    }

    private String generateSlug(String title) {
        if (title == null || title.isBlank())
            throw new BadRequestException("Title is required to generate slug", "MISSING_TITLE");
        try {
            String base = title.toLowerCase()
                    .replaceAll("[^a-z0-9\\s-]", "")
                    .replaceAll("\\s+", "-")
                    .replaceAll("-+", "-")
                    .replaceAll("^-|-$", "");
            if (base.length() > 200) base = base.substring(0, 200);
            if (base.isEmpty()) base = "research";
            String slug = base;
            int counter = 1;
            while (researchRepo.existsBySlug(slug)) {
                String suffix = "-" + counter++;
                if (base.length() + suffix.length() > 200)
                    base = base.substring(0, 200 - suffix.length());
                slug = base + suffix;
            }
            return slug;
        } catch (Exception e) {
            return "research-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private void saveTags(Research research, List<String> tagNames) {
        tagNames.stream()
                .map(t -> t != null ? t.trim().toLowerCase() : "")
                .filter(t -> !t.isEmpty() && t.length() <= 100)
                .distinct()
                .forEach(name -> {
                    try {
                        ResearchTag tag = ResearchTag.builder().research(research).tagName(name).build();
                        tagRepo.save(tag);
                        research.getTags().add(tag);
                    } catch (DataIntegrityViolationException e) {
                        log.warn("Duplicate tag '{}' skipped for research {}", name, research.getId());
                    }
                });
    }

    private void saveSources(Research research, List<SourceRequest> sourceRequests) {
        AtomicInteger order = new AtomicInteger(0);
        sourceRequests.forEach(sr -> {
            if (sr == null) return;
            try {
                ResearchSource source = ResearchSource.builder()
                        .research(research)
                        .sourceType(sr.sourceType())
                        .title(sr.title() != null ? sr.title() : "Untitled")
                        .citationText(sr.citationText())
                        .url(sr.url())
                        .doi(sr.doi())
                        .isbn(sr.isbn())
                        .displayOrder(sr.displayOrder() != null ? sr.displayOrder() : order.getAndIncrement())
                        .build();
                sourceRepo.save(source);
                research.getSources().add(source);
            } catch (DataIntegrityViolationException e) {
                log.warn("Source constraint violation skipped: {}", e.getMessage());
            }
        });
    }

    private void validateFile(MultipartFile file, String fileType, List<String> allowedTypes) {
        if (file == null || file.isEmpty())
            throw new BadRequestException(fileType + " file is required and cannot be empty", "EMPTY_FILE");
        if (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank())
            throw new BadRequestException("File name is required", "MISSING_FILENAME");
        if (file.getSize() == 0)
            throw new BadRequestException("File cannot be empty (0 bytes)", "EMPTY_FILE");
        if (allowedTypes != null && !allowedTypes.isEmpty()) {
            String ct = file.getContentType();
            if (ct == null || !allowedTypes.contains(ct.toLowerCase()))
                throw new BadRequestException(
                        "Invalid file type. Allowed: " + String.join(", ", allowedTypes),
                        "INVALID_FILE_TYPE",
                        Map.of("receivedType", ct != null ? ct : "unknown", "allowedTypes", allowedTypes));
        }
        String fn = file.getOriginalFilename();
        if (fn.contains("..") || fn.contains("/") || fn.contains("\\"))
            throw new BadRequestException("Invalid file name", "INVALID_FILENAME");
    }

    private String sanitizeFileName(String originalFilename) {
        if (originalFilename == null) return "unnamed";
        return originalFilename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private String uploadFileToS3(MultipartFile file, String prefix, String errorCode) {
        try { return s3.upload(file, prefix); }
        catch (MaxUploadSizeExceededException e) {
            throw new BadRequestException("File size exceeds maximum allowed limit", "FILE_TOO_LARGE");
        } catch (Exception e) {
            throw new AppException("Failed to upload file to storage",
                    HttpStatus.SERVICE_UNAVAILABLE, errorCode);
        }
    }

    private String getPublicUrlFromS3(String s3Key) {
        try { return s3.getPublicUrl(s3Key); }
        catch (Exception e) {
            throw new AppException("Failed to generate file URL",
                    HttpStatus.INTERNAL_SERVER_ERROR, "URL_GENERATION_ERROR");
        }
    }

    private MediaType resolveMediaType(String mimeType) {
        if (mimeType == null) return MediaType.OTHER;
        String lower = mimeType.toLowerCase();
        if (lower.startsWith("image/"))   return MediaType.IMAGE;
        if (lower.startsWith("video/"))   return MediaType.VIDEO;
        if (lower.startsWith("audio/"))   return MediaType.AUDIO;
        if (lower.contains("pdf") || lower.contains("word") || lower.contains("presentation"))
            return MediaType.DOCUMENT;
        if (lower.contains("spreadsheet") || lower.contains("csv")) return MediaType.SPREADSHEET;
        if (lower.contains("zip") || lower.contains("tar") || lower.contains("gzip")) return MediaType.ARCHIVE;
        return MediaType.OTHER;
    }
}