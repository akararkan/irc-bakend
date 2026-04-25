package ak.dev.irc.app.rabbitmq.consumer;

import ak.dev.irc.app.rabbitmq.event.post.PostCommentReactedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostCommentedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostCreatedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostReactedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostSharedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.QuestionAnsweredEvent;
import ak.dev.irc.app.rabbitmq.event.qna.QuestionCreatedEvent;
import ak.dev.irc.app.rabbitmq.event.research.ResearchCommentedEvent;
import ak.dev.irc.app.rabbitmq.event.research.ResearchPublishedEvent;
import ak.dev.irc.app.rabbitmq.event.research.ResearchReactedEvent;
import ak.dev.irc.app.rabbitmq.event.user.UserBlockedEvent;
import ak.dev.irc.app.rabbitmq.event.user.UserFollowedEvent;
import ak.dev.irc.app.rabbitmq.event.user.UserUnblockedEvent;
import ak.dev.irc.app.rabbitmq.event.user.UserUnfollowedEvent;
import ak.dev.irc.app.post.repository.PostCommentRepository;
import ak.dev.irc.app.user.entity.Notification;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.NotificationType;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.mapper.NotificationMapper;
import ak.dev.irc.app.user.realtime.NotificationPushedEvent;
import ak.dev.irc.app.user.repository.NotificationRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.NOTIFICATION_QUEUE;

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │                    Notification Event Consumer                           │
 * │                                                                          │
 * │  Listens to: irc.queue.notifications                                    │
 * │                                                                          │
 * │  Handles:                                                                │
 * │                                                                          │
 * │  — User events —                                                         │
 * │   UserFollowedEvent         → NEW_FOLLOWER notification                 │
 * │   UserUnfollowedEvent       → removes the earlier follow notification   │
 * │   UserUnblockedEvent        → UNBLOCKED notification                    │
 * │   UserBlockedEvent          → (acknowledged silently — no notification) │
 * │                                                                          │
 * │  — Research events —                                                     │
 * │   ResearchPublishedEvent    → fans out POST_NEW to all followers         │
 * │   ResearchReactedEvent      → PUBLICATION_LIKED notification            │
 * │   ResearchCommentedEvent    → PUBLICATION_COMMENTED notification        │
 * │                                                                          │
 * │  — Post events —                                                         │
 * │   PostCreatedEvent          → fans out POST_NEW to all followers         │
 * │   PostReactedEvent          → POST_REACTED notification                 │
 * │   PostCommentedEvent        → POST_COMMENTED or POST_COMMENT_REPLIED    │
 * │   PostCommentReactedEvent   → POST_COMMENT_REACTED notification         │
 * │   PostSharedEvent           → POST_SHARED notification                  │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Running in a background thread — NO Spring Security context available here.
 * Access users and notifications directly through repositories.
 *
 * NOTE: This class is wired automatically by Spring AMQP via @RabbitListener.
 * No other class needs to inject or call it.  The broker delivers messages
 * to the queue; Spring AMQP dispatches each message to the correct
 * @RabbitHandler method based on the __TypeId__ JSON header.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RabbitListener(queues = NOTIFICATION_QUEUE, containerFactory = "rabbitListenerContainerFactory")
public class NotificationEventConsumer {

    private static final int FOLLOWER_FAN_OUT_BATCH = 500;

        private final NotificationRepository notifRepo;
        private final UserRepository         userRepo;
        private final UserFollowRepository   followRepo;
        private final PostCommentRepository  postCommentRepo;
        private final NotificationMapper     notifMapper;
        private final ApplicationEventPublisher eventPublisher;

    // ══════════════════════════════════════════════════════════════════════════
    //  User — Follow / Unfollow
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onUserFollowed(UserFollowedEvent event) {
        log.info("[CONSUMER] UserFollowed — actor={} ({}) → target={}",
                event.actorId(), event.actorUsername(), event.targetId());

        Optional<User> actorOpt  = userRepo.findActiveById(event.actorId());
        Optional<User> targetOpt = userRepo.findActiveById(event.targetId());

        if (actorOpt.isEmpty() || targetOpt.isEmpty()) {
            log.warn("[CONSUMER] UserFollowed skipped — user not found (actor={}, target={})",
                    event.actorId(), event.targetId());
            return;
        }

        User actor  = actorOpt.get();
        User target = targetOpt.get();

        Notification notification = Notification.builder()
                .user(target)
                .actor(actor)
                .type(NotificationType.NEW_FOLLOWER)
                .title("New follower")
                .body(actor.getFullName() + " (@" + actor.getUsername() + ") started following you.")
                .resourceId(actor.getId())
                .resourceType("User")
                .build();

        notifRepo.save(notification);
        pushRealtime(target.getId(), notification);
        log.debug("[CONSUMER] NEW_FOLLOWER notification saved & queued for SSE push → user={}", target.getId());
    }

    @RabbitHandler
    @Transactional
    public void onUserUnfollowed(UserUnfollowedEvent event) {
        log.info("[CONSUMER] UserUnfollowed — actor={} → target={}",
                event.actorId(), event.targetId());

        notifRepo.deleteByUserActorAndType(
                event.targetId(), event.actorId(), NotificationType.NEW_FOLLOWER);

        log.debug("[CONSUMER] NEW_FOLLOWER notification cleaned up for actor={}, target={}",
                event.actorId(), event.targetId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  User — Block / Unblock
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onUserBlocked(UserBlockedEvent event) {
        // Blocks are silent by design — no notification sent.
        // Also remove any existing follow-notifications between the pair.
        log.info("[CONSUMER] UserBlocked (silent) — actor={} → target={}",
                event.actorId(), event.targetId());

        notifRepo.deleteByUserActorAndType(
                event.targetId(), event.actorId(), NotificationType.NEW_FOLLOWER);
        notifRepo.deleteByUserActorAndType(
                event.actorId(), event.targetId(), NotificationType.NEW_FOLLOWER);
    }

    @RabbitHandler
    @Transactional
    public void onUserUnblocked(UserUnblockedEvent event) {
        log.info("[CONSUMER] UserUnblocked — actor={} ({}) → target={}",
                event.actorId(), event.actorUsername(), event.targetId());

        Optional<User> actorOpt  = userRepo.findActiveById(event.actorId());
        Optional<User> targetOpt = userRepo.findActiveById(event.targetId());

        if (actorOpt.isEmpty() || targetOpt.isEmpty()) {
            log.warn("[CONSUMER] UserUnblocked skipped — user not found");
            return;
        }

        User actor  = actorOpt.get();
        User target = targetOpt.get();

        Notification notification = Notification.builder()
                .user(target)
                .actor(actor)
                .type(NotificationType.UNBLOCKED)
                .title("You have been unblocked")
                .body(actor.getFullName() + " (@" + actor.getUsername() + ") unblocked you.")
                .resourceId(actor.getId())
                .resourceType("User")
                .build();

        notifRepo.save(notification);
        pushRealtime(target.getId(), notification);
        log.debug("[CONSUMER] UNBLOCKED notification saved & queued for SSE push → user={}", target.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Research — Published (fan-out to followers)
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onResearchPublished(ResearchPublishedEvent event) {
        log.info("[CONSUMER] ResearchPublished — researchId={} by researcher={} ({})",
                event.researchId(), event.researcherId(), event.researcherUsername());

        Optional<User> researcherOpt = userRepo.findActiveById(event.researcherId());
        if (researcherOpt.isEmpty()) {
            log.warn("[CONSUMER] ResearchPublished skipped — researcher not found id={}",
                    event.researcherId());
            return;
        }

        User researcher = researcherOpt.get();

        int savedNotifications = fanOutToFollowers(event.researcherId(), follower -> Notification.builder()
                .user(follower)
                .actor(researcher)
                .type(NotificationType.POST_NEW)   // reuse the existing "new publication" notification type
                .title("New research published")
                .body(researcher.getFullName() + " (@" + researcher.getUsername()
                        + ") published: \"" + event.researchTitle() + "\"")
                .resourceId(event.researchId())
                .resourceType("Research")
                .build());

        log.info("[CONSUMER] ResearchPublished — {} notification(s) saved & queued for SSE", savedNotifications);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Research — Reacted
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onResearchReacted(ResearchReactedEvent event) {
        log.info("[CONSUMER] ResearchReacted — researchId={} actor={} ({})",
                event.researchId(), event.actorId(), event.actorUsername());

        Optional<User> actorOpt      = userRepo.findActiveById(event.actorId());
        Optional<User> researcherOpt = userRepo.findActiveById(event.researcherId());

        if (actorOpt.isEmpty() || researcherOpt.isEmpty()) {
            log.warn("[CONSUMER] ResearchReacted skipped — user not found");
            return;
        }

        User actor      = actorOpt.get();
        User researcher = researcherOpt.get();

        String reactionLabel = toReadableReaction(event.reactionType());

        Notification notification = Notification.builder()
                .user(researcher)
                .actor(actor)
                .type(NotificationType.PUBLICATION_LIKED)
                .title("Someone reacted to your research")
                .body(actor.getFullName() + " (@" + actor.getUsername()
                        + ") reacted " + reactionLabel + " to: \"" + event.researchTitle() + "\"")
                .resourceId(event.researchId())
                .resourceType("Research")
                .build();

        notifRepo.save(notification);
        pushRealtime(researcher.getId(), notification);
        log.debug("[CONSUMER] PUBLICATION_LIKED notification saved & queued for SSE → researcher={}", researcher.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Research — Commented
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onResearchCommented(ResearchCommentedEvent event) {
        log.info("[CONSUMER] ResearchCommented — researchId={} actor={} ({})",
                event.researchId(), event.actorId(), event.actorUsername());

        Optional<User> actorOpt      = userRepo.findActiveById(event.actorId());
        Optional<User> researcherOpt = userRepo.findActiveById(event.researcherId());

        if (actorOpt.isEmpty() || researcherOpt.isEmpty()) {
            log.warn("[CONSUMER] ResearchCommented skipped — user not found");
            return;
        }

        User actor      = actorOpt.get();
        User researcher = researcherOpt.get();

        Notification notification = Notification.builder()
                .user(researcher)
                .actor(actor)
                .type(NotificationType.PUBLICATION_COMMENTED)
                .title("New comment on your research")
                .body(actor.getFullName() + " (@" + actor.getUsername()
                        + ") commented: \"" + event.commentPreview() + "\"")
                .resourceId(event.researchId())
                .resourceType("Research")
                .build();

        notifRepo.save(notification);
        pushRealtime(researcher.getId(), notification);
        log.debug("[CONSUMER] PUBLICATION_COMMENTED notification saved & queued for SSE → researcher={}", researcher.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Q&A — Question Created
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onQuestionCreated(QuestionCreatedEvent event) {
        log.info("[CONSUMER] QuestionCreated — questionId={} author={} ({})",
                event.questionId(), event.authorId(), event.authorUsername());

        Optional<User> authorOpt = userRepo.findActiveById(event.authorId());
        if (authorOpt.isEmpty()) {
            log.warn("[CONSUMER] QuestionCreated skipped — author not found id={}", event.authorId());
            return;
        }

        User author = authorOpt.get();

        int savedNotifications = fanOutToRoles(
                List.of(Role.SCHOLAR, Role.ADMIN, Role.SUPER_ADMIN),
                author.getId(),
                recipient -> Notification.builder()
                        .user(recipient)
                        .actor(author)
                        .type(NotificationType.QUESTION_NEW)
                        .title("New question to answer")
                        .body(author.getFullName() + " (@" + author.getUsername()
                                + ") asked: \"" + event.questionTitle() + "\"")
                        .resourceId(event.questionId())
                        .resourceType("Question")
                        .build());

        log.info("[CONSUMER] QuestionCreated — {} notification(s) saved & queued for SSE", savedNotifications);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Q&A — Question Answered
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onQuestionAnswered(QuestionAnsweredEvent event) {
        log.info("[CONSUMER] QuestionAnswered — questionId={} answerId={} answerAuthor={} ({})",
                event.questionId(), event.answerId(), event.answerAuthorId(), event.answerAuthorUsername());

        if (event.questionAuthorId().equals(event.answerAuthorId())) {
            log.debug("[CONSUMER] QuestionAnswered skipped — answer author is the question author");
            return;
        }

        Optional<User> questionAuthorOpt = userRepo.findActiveById(event.questionAuthorId());
        Optional<User> answerAuthorOpt = userRepo.findActiveById(event.answerAuthorId());

        if (questionAuthorOpt.isEmpty() || answerAuthorOpt.isEmpty()) {
            log.warn("[CONSUMER] QuestionAnswered skipped — user not found");
            return;
        }

        User questionAuthor = questionAuthorOpt.get();
        User answerAuthor = answerAuthorOpt.get();

        Notification notification = Notification.builder()
                .user(questionAuthor)
                .actor(answerAuthor)
                .type(NotificationType.QUESTION_ANSWERED)
                .title("Your question has an answer")
                .body(answerAuthor.getFullName() + " (@" + answerAuthor.getUsername()
                        + ") answered: \"" + event.questionTitle() + "\"")
                .resourceId(event.questionId())
                .resourceType("Question")
                .build();

        notifRepo.save(notification);
        pushRealtime(questionAuthor.getId(), notification);
        log.debug("[CONSUMER] QUESTION_ANSWERED notification saved & queued for SSE → questionAuthor={}", questionAuthor.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Post — Created (fan-out to followers)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * When a user publishes a post, all of their followers get a POST_NEW notification.
     * Only PUBLIC posts fan-out — FOLLOWERS_ONLY and ONLY_ME are excluded.
     */
    @RabbitHandler
    @Transactional
    public void onPostCreated(PostCreatedEvent event) {
        log.info("[CONSUMER] PostCreated — postId={} authorId={} type={} visibility={}",
                event.getPostId(), event.getAuthorId(), event.getPostType(), event.getVisibility());

        // Only fan-out public posts
        if (!"PUBLIC".equals(event.getVisibility())) {
            log.debug("[CONSUMER] PostCreated skipped fan-out — visibility={}", event.getVisibility());
            return;
        }

        Optional<User> authorOpt = userRepo.findActiveById(event.getAuthorId());
        if (authorOpt.isEmpty()) {
            log.warn("[CONSUMER] PostCreated skipped — author not found id={}", event.getAuthorId());
            return;
        }

        User author = authorOpt.get();

        String postLabel = toReadablePostType(event.getPostType());
        int savedNotifications = fanOutToFollowers(event.getAuthorId(), follower -> Notification.builder()
                .user(follower)
                .actor(author)
                .type(NotificationType.POST_NEW)
            .title("New " + postLabel + " from " + author.getFullName())
            .body(author.getFullName() + " (@" + author.getUsername()
                + ") posted a new " + postLabel + ".")
                .resourceId(event.getPostId())
                .resourceType("Post")
                .build());

        log.info("[CONSUMER] PostCreated — {} notification(s) saved & queued for SSE", savedNotifications);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Post — Reacted
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onPostReacted(PostReactedEvent event) {
        log.info("[CONSUMER] PostReacted — postId={} reactor={} type={}",
                event.getPostId(), event.getReactorId(), event.getReactionType());

        // Don't notify people when they react to their own post
        if (event.getReactorId().equals(event.getPostAuthorId())) {
            log.debug("[CONSUMER] PostReacted skipped — reactor is the author");
            return;
        }

        Optional<User> reactorOpt = userRepo.findActiveById(event.getReactorId());
        Optional<User> authorOpt  = userRepo.findActiveById(event.getPostAuthorId());

        if (reactorOpt.isEmpty() || authorOpt.isEmpty()) {
            log.warn("[CONSUMER] PostReacted skipped — user not found (reactor={}, author={})",
                    event.getReactorId(), event.getPostAuthorId());
            return;
        }

        User reactor = reactorOpt.get();
        User author  = authorOpt.get();

        String reactionLabel = toReadablePostReaction(event.getReactionType());

        Notification notification = Notification.builder()
                .user(author)
                .actor(reactor)
            .type(NotificationType.POST_REACTED)
            .title("Someone reacted to your post")
                .body(reactor.getFullName() + " (@" + reactor.getUsername()
                + ") reacted " + reactionLabel + " to your post.")
                .resourceId(event.getPostId())
            .resourceType("Post")
                .build();

        notifRepo.save(notification);
        pushRealtime(author.getId(), notification);
        log.debug("[CONSUMER] POST_REACTED notification saved & queued for SSE → author={}", author.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Post — Commented
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Fires for both top-level comments (POST_COMMENTED → notify post author)
     * and replies (POST_COMMENT_REPLIED → notify parent comment author).
     * If the commenter is the post author, no notification is sent.
     */
    @RabbitHandler
    @Transactional
    public void onPostCommented(PostCommentedEvent event) {
        log.info("[CONSUMER] PostCommented — postId={} commentId={} isReply={}",
                event.getPostId(), event.getCommentId(), event.isReply());

                Optional<User> commenterOpt = userRepo.findActiveById(event.getCommentAuthorId());

                if (commenterOpt.isEmpty()) {
            log.warn("[CONSUMER] PostCommented skipped — user not found");
            return;
        }

        User commenter = commenterOpt.get();

                UUID recipientId = resolvePostCommentRecipient(event);
                if (recipientId == null) {
                    log.warn("[CONSUMER] PostCommented skipped — recipient could not be resolved (postId={}, commentId={})",
                            event.getPostId(), event.getCommentId());
                    return;
                }

                Optional<User> recipientOpt = userRepo.findActiveById(recipientId);
                if (recipientOpt.isEmpty()) {
                    log.warn("[CONSUMER] PostCommented skipped — recipient user not found (recipient={}, postId={}, commentId={})",
                            recipientId, event.getPostId(), event.getCommentId());
                    return;
                }

        User recipient = recipientOpt.get();

        // Don't notify when someone comments on their own post
        if (commenter.getId().equals(recipient.getId())) {
            log.debug("[CONSUMER] PostCommented skipped — commenter is the post author");
            return;
        }

        NotificationType type  = event.isReply() ? NotificationType.POST_COMMENT_REPLIED
                : NotificationType.POST_COMMENTED;
        String title = event.isReply() ? "New reply on your comment"
                : "New comment on your post";
        String body  = commenter.getFullName() + " (@" + commenter.getUsername()
                + (event.isReply() ? ") replied to your comment." : ") commented on your post.");

        Notification notification = Notification.builder()
                .user(recipient)
                .actor(commenter)
                .type(type)
                .title(title)
                .body(body)
                .resourceId(event.getPostId())
                .resourceType("Post")
                .build();

        notifRepo.save(notification);
        pushRealtime(recipient.getId(), notification);
        log.debug("[CONSUMER] {} notification saved & queued for SSE → user={}", type, recipient.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Post — Comment Reacted
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onPostCommentReacted(PostCommentReactedEvent event) {
        log.info("[CONSUMER] PostCommentReacted — commentId={} reactor={}",
                event.getCommentId(), event.getReactorId());

        // Don't notify when someone reacts to their own comment
        if (event.getReactorId().equals(event.getCommentAuthorId())) {
            log.debug("[CONSUMER] PostCommentReacted skipped — reactor is the comment author");
            return;
        }

        Optional<User> reactorOpt       = userRepo.findActiveById(event.getReactorId());
        Optional<User> commentAuthorOpt = userRepo.findActiveById(event.getCommentAuthorId());

        if (reactorOpt.isEmpty() || commentAuthorOpt.isEmpty()) {
            log.warn("[CONSUMER] PostCommentReacted skipped — user not found");
            return;
        }

        User reactor       = reactorOpt.get();
        User commentAuthor = commentAuthorOpt.get();

        String reactionLabel = toReadablePostReaction(event.getReactionType());

        Notification notification = Notification.builder()
                .user(commentAuthor)
                .actor(reactor)
            .type(NotificationType.POST_COMMENT_REACTED)
            .title("Someone reacted to your comment")
                .body(reactor.getFullName() + " (@" + reactor.getUsername()
                + ") reacted " + reactionLabel + " to your comment.")
                .resourceId(event.getPostId())
            .resourceType("Post")
                .build();

        notifRepo.save(notification);
        pushRealtime(commentAuthor.getId(), notification);
        log.debug("[CONSUMER] POST_COMMENT_REACTED notification saved & queued for SSE → user={}", commentAuthor.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Post — Shared
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onPostShared(PostSharedEvent event) {
        log.info("[CONSUMER] PostShared — postId={} sharer={}",
                event.getPostId(), event.getSharerId());

        // Don't notify when someone shares their own post
        if (event.getSharerId().equals(event.getPostAuthorId())) {
            log.debug("[CONSUMER] PostShared skipped — sharer is the author");
            return;
        }

        Optional<User> sharerOpt = userRepo.findActiveById(event.getSharerId());
        Optional<User> authorOpt = userRepo.findActiveById(event.getPostAuthorId());

        if (sharerOpt.isEmpty() || authorOpt.isEmpty()) {
            log.warn("[CONSUMER] PostShared skipped — user not found (sharer={}, author={})",
                    event.getSharerId(), event.getPostAuthorId());
            return;
        }

        User sharer = sharerOpt.get();
        User author = authorOpt.get();
        Notification notification = Notification.builder()
                .user(author)
                .actor(sharer)
                .type(NotificationType.POST_SHARED)
                .title("Someone shared your post")
                .body(sharer.getFullName() + " (@" + sharer.getUsername() + ") shared your post.")
                .resourceId(event.getPostId())
                .resourceType("Post")
                .build();

        notifRepo.save(notification);
        pushRealtime(author.getId(), notification);
        log.debug("[CONSUMER] POST_SHARED notification saved & queued for SSE → author={}", author.getId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Fires a {@link NotificationPushedEvent} that is picked up
     * by {@link ak.dev.irc.app.user.realtime.NotificationPushEventListener}
     * <em>after the current transaction commits</em>, then published to Redis
     * and forwarded to the connected SSE emitter for the recipient.
     */
    private void pushRealtime(UUID recipientId, Notification notification) {
        eventPublisher.publishEvent(
                new NotificationPushedEvent(recipientId, notifMapper.toResponse(notification)));
    }

    /** Maps ResearchReaction enum names to display emoji */
    private String toReadableReaction(String reactionType) {
        return switch (reactionType) {
            case "LIKE"       -> "👍";
            case "LOVE"       -> "❤️";
            case "INSIGHTFUL" -> "💡";
            case "CELEBRATE"  -> "🎉";
            case "CURIOUS"    -> "🤔";
            case "SUPPORT"    -> "🤝";
            default           -> reactionType;
        };
    }

    /** Maps PostReactionType enum names to display emoji */
    private String toReadablePostReaction(String reactionType) {
        return switch (reactionType) {
            case "LIKE"       -> "👍";
            case "LOVE"       -> "❤️";
            case "HAHA"       -> "😂";
            case "WOW"        -> "😮";
            case "SAD"        -> "😢";
            case "ANGRY"      -> "😡";
            case "CARE"       -> "🤗";
            case "INSIGHTFUL" -> "💡";
            default           -> reactionType;
        };
    }

    /** Maps PostType enum names to human-readable labels */
    private String toReadablePostType(String postType) {
        return switch (postType) {
            case "TEXT"       -> "post";
            case "EMBEDDED"   -> "post";
            case "VOICE_POST" -> "voice post";
            case "REEL"       -> "reel";
            default           -> "post";
        };
    }

    private int fanOutToFollowers(UUID ownerId, Function<User, Notification> notificationFactory) {
        int totalNotifications = 0;
        int page = 0;

        while (true) {
            List<UUID> followerIds = followRepo.findFollowerIds(ownerId, PageRequest.of(page, FOLLOWER_FAN_OUT_BATCH));
            if (followerIds.isEmpty()) {
                break;
            }

            List<Notification> notifications = followerIds.stream()
                    .map(userRepo::findActiveById)
                    .flatMap(Optional::stream)
                    .map(notificationFactory)
                    .toList();

            if (!notifications.isEmpty()) {
                notifRepo.saveAll(notifications);
                notifications.forEach(n -> pushRealtime(n.getUser().getId(), n));
                totalNotifications += notifications.size();
            }

            if (followerIds.size() < FOLLOWER_FAN_OUT_BATCH) {
                break;
            }

            page++;
        }

        return totalNotifications;
    }

    private int fanOutToRoles(List<Role> roles, UUID excludedUserId, Function<User, Notification> notificationFactory) {
        int totalNotifications = 0;
        int page = 0;

        while (true) {
            var recipientsPage = userRepo.findActiveByRoles(roles, PageRequest.of(page, FOLLOWER_FAN_OUT_BATCH));
            if (recipientsPage.isEmpty()) {
                break;
            }

            List<Notification> notifications = recipientsPage.getContent().stream()
                    .filter(user -> excludedUserId == null || !user.getId().equals(excludedUserId))
                    .map(notificationFactory)
                    .toList();

            if (!notifications.isEmpty()) {
                notifRepo.saveAll(notifications);
                notifications.forEach(n -> pushRealtime(n.getUser().getId(), n));
                totalNotifications += notifications.size();
            }

            if (!recipientsPage.hasNext()) {
                break;
            }

            page++;
        }

        return totalNotifications;
    }

    private UUID resolvePostCommentRecipient(PostCommentedEvent event) {
        if (!event.isReply()) {
            return event.getPostAuthorId();
        }

        if (event.getParentCommentAuthorId() != null) {
            return event.getParentCommentAuthorId();
        }

        if (event.getParentCommentId() != null) {
            return postCommentRepo.findById(event.getParentCommentId())
                    .map(comment -> comment.getAuthor().getId())
                    .orElse(null);
        }

        return null;
    }
}