package ak.dev.irc.app.rabbitmq.consumer;

import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.rabbitmq.event.post.PostCommentReactedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostCommentedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostCreatedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostCommentDeletedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostDeletedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostReactedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostUnreactedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.AnswerDeletedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.AnswerUnreactedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.BestAnswerVotedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.QuestionDeletedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostSharedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.AnswerAcceptedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.AnswerReactedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.QuestionAnsweredEvent;
import ak.dev.irc.app.rabbitmq.event.qna.QuestionCreatedEvent;
import ak.dev.irc.app.rabbitmq.event.research.ResearchCommentReactedEvent;
import ak.dev.irc.app.rabbitmq.event.research.ResearchCommentedEvent;
import ak.dev.irc.app.rabbitmq.event.research.ResearchPublishedEvent;
import ak.dev.irc.app.rabbitmq.event.research.ResearchReactedEvent;
import ak.dev.irc.app.research.repository.ResearchCommentRepository;
import ak.dev.irc.app.rabbitmq.event.user.MentionSource;
import ak.dev.irc.app.rabbitmq.event.user.UserBlockedEvent;
import ak.dev.irc.app.rabbitmq.event.user.UserFollowedEvent;
import ak.dev.irc.app.rabbitmq.event.user.UserMentionedEvent;
import ak.dev.irc.app.rabbitmq.event.user.UserUnblockedEvent;
import ak.dev.irc.app.rabbitmq.event.user.UserUnfollowedEvent;
import ak.dev.irc.app.user.entity.Notification;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.NotificationType;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.mapper.NotificationMapper;
import ak.dev.irc.app.user.realtime.NotificationPushedEvent;
import ak.dev.irc.app.user.repository.NotificationRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.repository.UserRestrictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * │   UserBlockedEvent          → (acknowledged silently — no notification) │
 * │   UserUnblockedEvent        → (acknowledged silently — no notification) │
 * │                              (notifying "you were unblocked" implicitly │
 * │                              reveals the prior block — block must stay  │
 * │                              invisible to the target).                  │
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
        private final UserRestrictionRepository restrictionRepo;
        private final ResearchCommentRepository researchCommentRepo;
        private final NotificationMapper     notifMapper;
        private final ApplicationEventPublisher eventPublisher;
        private final UserActivityService    userActivityService;
        private final ak.dev.irc.app.user.service.NotificationDispatcher dispatcher;

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

        dispatcher.dispatch(notification);
        log.debug("[CONSUMER] NEW_FOLLOWER dispatched → user={}", target.getId());
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
        // Unblocks are silent by design — a "you were unblocked" notification
        // implicitly tells the target they were previously blocked, defeating
        // the silence of the block itself. The visible side-effect of the
        // unblock is purely behavioural (the target can suddenly follow /
        // interact again); no notification is dispatched.
        log.info("[CONSUMER] UserUnblocked (silent) — actor={} → target={}",
                event.actorId(), event.targetId());
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

        dispatcher.dispatchAggregated(
                researcher,
                actor,
                NotificationType.PUBLICATION_LIKED,
                "PUBLICATION_LIKED:" + event.researchId(),
                "Someone reacted to your research",
                actor.getFullName() + " (@" + actor.getUsername()
                        + ") reacted " + reactionLabel + " to: \"" + event.researchTitle() + "\"",
                event.researchId(),
                "Research");
        log.debug("[CONSUMER] PUBLICATION_LIKED dispatched → researcher={}", researcher.getId());

        recordResearchReactionActivity(event);
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

        dispatcher.dispatchAggregated(
                researcher,
                actor,
                NotificationType.PUBLICATION_COMMENTED,
                "PUBLICATION_COMMENTED:" + event.researchId(),
                "New comment on your research",
                actor.getFullName() + " (@" + actor.getUsername()
                        + ") commented: \"" + event.commentPreview() + "\"",
                event.researchId(),
                "Research");
        log.debug("[CONSUMER] PUBLICATION_COMMENTED dispatched → researcher={}", researcher.getId());

        recordResearchCommentActivity(event);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Research — Comment Reacted
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onResearchCommentReacted(ResearchCommentReactedEvent event) {
        log.info("[CONSUMER] ResearchCommentReacted — commentId={} actor={} ({})",
                event.commentId(), event.reactorId(), event.reactorUsername());

        // Notify the comment author — suppressed when they reacted to their own comment.
        if (event.commentAuthorId() != null && !event.commentAuthorId().equals(event.reactorId())) {
            Optional<User> targetOpt = userRepo.findActiveById(event.commentAuthorId());
            Optional<User> actorOpt  = userRepo.findActiveById(event.reactorId());
            if (targetOpt.isPresent() && actorOpt.isPresent()) {
                User target = targetOpt.get();
                User actor  = actorOpt.get();
                if (!isSilencedByRestriction(event.commentAuthorId(), event.reactorId(), "ResearchCommentReacted")) {
                    dispatcher.dispatchAggregated(
                            target,
                            actor,
                            NotificationType.PUBLICATION_COMMENT_REACTED,
                            "PUBLICATION_COMMENT_REACTED:" + event.commentId(),
                            "Someone reacted to your comment",
                            actor.getFullName() + " (@" + actor.getUsername()
                                    + ") reacted to your comment on: \"" + event.researchTitle() + "\"",
                            event.commentId(),
                            "ResearchComment");
                    log.debug("[CONSUMER] PUBLICATION_COMMENT_REACTED dispatched → target={}", target.getId());
                }
            } else {
                log.warn("[CONSUMER] ResearchCommentReacted skipped notif — user not found");
            }
        }

        recordResearchCommentReactionActivity(event);
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

        // Track who has been notified so a scholar who is also a follower of
        // the author still receives exactly one inbox row + one email.
        Set<UUID> notified = new HashSet<>();

        // ── Pass 1: scholars / mods who can actually answer questions ──
        int scholarCount = fanOutToRoles(
                List.of(Role.SCHOLAR, Role.ADMIN),
                author.getId(),
                recipient -> {
                    if (!notified.add(recipient.getId())) return null;
                    return Notification.builder()
                            .user(recipient)
                            .actor(author)
                            .type(NotificationType.QUESTION_NEW)
                            .title("New question to answer")
                            .body(author.getFullName() + " (@" + author.getUsername()
                                    + ") asked: \"" + event.questionTitle() + "\"")
                            .resourceId(event.questionId())
                            .resourceType("Question")
                            .build();
                });

        // ── Pass 2: every follower of the author (mirrors post / research) ──
        int followerCount = fanOutToFollowers(author.getId(), follower -> {
            if (follower.getId().equals(author.getId())) return null;
            if (!notified.add(follower.getId())) return null;
            return Notification.builder()
                    .user(follower)
                    .actor(author)
                    .type(NotificationType.QUESTION_NEW)
                    .title("New question from " + author.getFullName())
                    .body(author.getFullName() + " (@" + author.getUsername()
                            + ") asked: \"" + event.questionTitle() + "\"")
                    .resourceId(event.questionId())
                    .resourceType("Question")
                    .build();
        });

        log.info("[CONSUMER] QuestionCreated — {} scholar + {} follower notification(s) saved",
                scholarCount, followerCount);
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

        if (isSilencedByRestriction(event.questionAuthorId(), event.answerAuthorId(), "QuestionAnswered")) {
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

        // Aggregate by question — many users answering the same question merge.
        dispatcher.dispatchAggregated(
                questionAuthor,
                answerAuthor,
                NotificationType.QUESTION_ANSWERED,
                "QUESTION_ANSWERED:" + event.questionId(),
                "Your question has an answer",
                answerAuthor.getFullName() + " (@" + answerAuthor.getUsername()
                        + ") answered: \"" + event.questionTitle() + "\"",
                event.questionId(),
                "Question");
        log.debug("[CONSUMER] QUESTION_ANSWERED dispatched → questionAuthor={}", questionAuthor.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Q&A — Answer Reacted (notify the answer author)
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onAnswerReacted(AnswerReactedEvent event) {
        log.info("[CONSUMER] AnswerReacted — questionId={} answerId={} reactor={} type={}",
                event.questionId(), event.answerId(), event.reactorId(), event.reactionType());

        // Self-reactions never notify.
        if (event.reactorId().equals(event.answerAuthorId())) {
            log.debug("[CONSUMER] AnswerReacted skipped — reactor is the answer author");
            return;
        }

        if (isSilencedByRestriction(event.answerAuthorId(), event.reactorId(), "AnswerReacted")) {
            return;
        }

        Optional<User> reactorOpt = userRepo.findActiveById(event.reactorId());
        Optional<User> answerAuthorOpt = userRepo.findActiveById(event.answerAuthorId());

        if (reactorOpt.isEmpty() || answerAuthorOpt.isEmpty()) {
            log.warn("[CONSUMER] AnswerReacted skipped — user not found");
            return;
        }

        User reactor = reactorOpt.get();
        User answerAuthor = answerAuthorOpt.get();

        String reactionLabel = toReadableAnswerReaction(event.reactionType());
        String suffix = event.reanswer() ? " on your reanswer." : " on your answer.";

        dispatcher.dispatchAggregated(
                answerAuthor,
                reactor,
                NotificationType.ANSWER_REACTED,
                "ANSWER_REACTED:" + event.answerId(),
                "Someone reacted to your answer",
                reactor.getFullName() + " (@" + reactor.getUsername()
                        + ") reacted " + reactionLabel + suffix,
                event.questionId(),
                "Question");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Q&A — Answer Accepted (notify the answer author)
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onAnswerAccepted(AnswerAcceptedEvent event) {
        log.info("[CONSUMER] AnswerAccepted — questionId={} answerId={} answerAuthor={}",
                event.questionId(), event.answerId(), event.answerAuthorId());

        // Don't notify if the question author accepted their own answer
        if (event.questionAuthorId().equals(event.answerAuthorId())) {
            log.debug("[CONSUMER] AnswerAccepted skipped — answer author is the question author");
            return;
        }

        if (isSilencedByRestriction(event.answerAuthorId(), event.questionAuthorId(), "AnswerAccepted")) {
            return;
        }

        Optional<User> questionAuthorOpt = userRepo.findActiveById(event.questionAuthorId());
        Optional<User> answerAuthorOpt = userRepo.findActiveById(event.answerAuthorId());

        if (questionAuthorOpt.isEmpty() || answerAuthorOpt.isEmpty()) {
            log.warn("[CONSUMER] AnswerAccepted skipped — user not found");
            return;
        }

        User questionAuthor = questionAuthorOpt.get();
        User answerAuthor = answerAuthorOpt.get();

        Notification notification = Notification.builder()
                .user(answerAuthor)
                .actor(questionAuthor)
                .type(NotificationType.ANSWER_ACCEPTED)
                .title("Your answer was accepted as best")
                .body(questionAuthor.getFullName() + " (@" + questionAuthor.getUsername()
                        + ") accepted your answer on: \"" + event.questionTitle() + "\"")
                .resourceId(event.questionId())
                .resourceType("Question")
                .build();

        dispatcher.dispatch(notification);
        log.debug("[CONSUMER] ANSWER_ACCEPTED dispatched → answerAuthor={}", answerAuthor.getId());
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

    // ══════════════════════════════════════════════════════════════════════════
    //  Lifecycle / "no notification" events
    //
    //  The notifications queue is bound to {@code post.lifecycle.#},
    //  {@code post.social.#}, {@code qna.lifecycle.#} and {@code qna.social.#},
    //  so EVERY event in those families lands here. Most of these are reversals
    //  (un-react, un-vote) or deletions that we intentionally don't notify on
    //  (un-liking should not poke the author; delete is silent), but Spring AMQP
    //  still needs a handler so it can deserialize + ack each message —
    //  otherwise it retries 3× and dead-letters with a NoSuchMethodException.
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    public void onPostUnreacted(PostUnreactedEvent event) {
        log.debug("[CONSUMER] PostUnreacted — postId={} actor={} (no notification)",
                event.postId(), event.actorId());
    }

    @RabbitHandler
    public void onPostDeleted(PostDeletedEvent event) {
        log.debug("[CONSUMER] PostDeleted — postId={} actor={} (no notification)",
                event.postId(), event.actorId());
    }

    @RabbitHandler
    public void onPostCommentDeleted(PostCommentDeletedEvent event) {
        log.debug("[CONSUMER] PostCommentDeleted — postId={} commentId={} actor={} (no notification)",
                event.postId(), event.commentId(), event.actorId());
    }

    @RabbitHandler
    public void onQuestionDeleted(QuestionDeletedEvent event) {
        log.debug("[CONSUMER] QuestionDeleted — questionId={} actor={} (no notification)",
                event.questionId(), event.actorId());
    }

    @RabbitHandler
    public void onAnswerDeleted(AnswerDeletedEvent event) {
        log.debug("[CONSUMER] AnswerDeleted — questionId={} answerId={} actor={} (no notification)",
                event.questionId(), event.answerId(), event.actorId());
    }

    @RabbitHandler
    public void onAnswerUnreacted(AnswerUnreactedEvent event) {
        log.debug("[CONSUMER] AnswerUnreacted — questionId={} answerId={} actor={} (no notification)",
                event.questionId(), event.answerId(), event.actorId());
    }

    /**
     * Best-answer vote/un-vote — the same record is published for both routing
     * keys ({@code qna.social.best.voted} and {@code qna.social.best.unvoted}),
     * distinguished by {@link BestAnswerVotedEvent#voted()}. Only the up-vote
     * notifies the answer author (N4); the un-vote just acks so the message
     * doesn't dead-letter. Aggregated per answer so multiple endorsements
     * coalesce into one inbox line.
     */
    @RabbitHandler
    @Transactional
    public void onBestAnswerVoted(BestAnswerVotedEvent event) {
        log.debug("[CONSUMER] BestAnswerVoted/Unvoted — questionId={} answerId={} voter={} voted={}",
                event.questionId(), event.answerId(), event.voterId(), event.voted());

        // Un-vote (retraction) is silent — nothing to tell the author.
        if (!event.voted()) {
            return;
        }

        // Endorsing your own answer never notifies.
        if (event.voterId().equals(event.answerAuthorId())) {
            log.debug("[CONSUMER] BestAnswerVoted skipped — voter is the answer author");
            return;
        }

        if (isSilencedByRestriction(event.answerAuthorId(), event.voterId(), "BestAnswerVoted")) {
            return;
        }

        Optional<User> voterOpt = userRepo.findActiveById(event.voterId());
        Optional<User> answerAuthorOpt = userRepo.findActiveById(event.answerAuthorId());

        if (voterOpt.isEmpty() || answerAuthorOpt.isEmpty()) {
            log.warn("[CONSUMER] BestAnswerVoted skipped — user not found");
            return;
        }

        User voter = voterOpt.get();
        User answerAuthor = answerAuthorOpt.get();

        dispatcher.dispatchAggregated(
                answerAuthor,
                voter,
                NotificationType.ANSWER_BEST_VOTED,
                "ANSWER_BEST_VOTED:" + event.answerId(),
                "Your answer was endorsed as a best answer",
                voter.getFullName() + " (@" + voter.getUsername()
                        + ") endorsed your answer as a best answer to: \"" + event.questionTitle() + "\"",
                event.questionId(),
                "Question");
        log.debug("[CONSUMER] ANSWER_BEST_VOTED dispatched → answerAuthor={}", answerAuthor.getId());
    }

    @RabbitHandler
    @Transactional
    public void onPostReacted(PostReactedEvent event) {
        log.info("[CONSUMER] PostReacted — postId={} reactor={} type={}",
                event.getPostId(), event.getReactorId(), event.getReactionType());

        recordReactionActivity(event);

        // Don't notify people when they react to their own post
        if (event.getReactorId().equals(event.getPostAuthorId())) {
            log.debug("[CONSUMER] PostReacted skipped — reactor is the author");
            return;
        }

        // IG-style restriction: activity is recorded, but the recipient is
        // never told. Silent for the actor too.
        if (isSilencedByRestriction(event.getPostAuthorId(), event.getReactorId(), "PostReacted")) {
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

        // Coalesce repeated reactions on the same post within an hour into one
        // inbox row — the latest reactor becomes the avatar, count++.
        dispatcher.dispatchAggregated(
                author,
                reactor,
                NotificationType.POST_REACTED,
                "POST_REACTED:" + event.getPostId(),
                "Someone reacted to your post",
                reactor.getFullName() + " (@" + reactor.getUsername()
                        + ") reacted " + reactionLabel + " to your post.",
                event.getPostId(),
                "Post");
        log.debug("[CONSUMER] POST_REACTED dispatched → author={}", author.getId());
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

        recordCommentActivity(event);

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

        // Silent restriction: recipient has restricted the commenter.
        if (isSilencedByRestriction(recipient.getId(), commenter.getId(), "PostCommented")) {
            return;
        }

        NotificationType type  = event.isReply() ? NotificationType.POST_COMMENT_REPLIED
                : NotificationType.POST_COMMENTED;
        String title = event.isReply() ? "New reply on your comment"
                : "New comment on your post";
        String body  = commenter.getFullName() + " (@" + commenter.getUsername()
                + (event.isReply() ? ") replied to your comment." : ") commented on your post.");

        // Comments aggregate per-post; replies aggregate per parent-comment so
        // different comment threads stay distinct in the inbox.
        String groupKey = event.isReply()
                ? "POST_COMMENT_REPLIED:" + event.getParentCommentId()
                : "POST_COMMENTED:" + event.getPostId();

        dispatcher.dispatchAggregated(
                recipient,
                commenter,
                type,
                groupKey,
                title,
                body,
                event.getPostId(),
                "Post");
        log.debug("[CONSUMER] {} dispatched → user={}", type, recipient.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Post — Comment Reacted
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onPostCommentReacted(PostCommentReactedEvent event) {
        log.info("[CONSUMER] PostCommentReacted — commentId={} reactor={}",
                event.getCommentId(), event.getReactorId());

        recordCommentReactionActivity(event);

        // Don't notify when someone reacts to their own comment
        if (event.getReactorId().equals(event.getCommentAuthorId())) {
            log.debug("[CONSUMER] PostCommentReacted skipped — reactor is the comment author");
            return;
        }

        if (isSilencedByRestriction(event.getCommentAuthorId(), event.getReactorId(), "PostCommentReacted")) {
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

        dispatcher.dispatchAggregated(
                commentAuthor,
                reactor,
                NotificationType.POST_COMMENT_REACTED,
                "POST_COMMENT_REACTED:" + event.getCommentId(),
                "Someone reacted to your comment",
                reactor.getFullName() + " (@" + reactor.getUsername()
                        + ") reacted " + reactionLabel + " to your comment.",
                event.getPostId(),
                "Post");
        log.debug("[CONSUMER] POST_COMMENT_REACTED dispatched → user={}", commentAuthor.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Post — Shared
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onPostShared(PostSharedEvent event) {
        log.info("[CONSUMER] PostShared — postId={} sharer={}",
                event.getPostId(), event.getSharerId());

        recordShareActivity(event);

        // Don't notify yourself when you repost your own post
        if (event.getSharerId().equals(event.getPostAuthorId())) {
            log.debug("[CONSUMER] PostShared notification skipped — sharer is the author");
            return;
        }

        if (isSilencedByRestriction(event.getPostAuthorId(), event.getSharerId(), "PostShared")) {
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

        dispatcher.dispatchAggregated(
                author,
                sharer,
                NotificationType.POST_SHARED,
                "POST_SHARED:" + event.getPostId(),
                "Someone shared your post",
                sharer.getFullName() + " (@" + sharer.getUsername() + ") shared your post.",
                event.getPostId(),
                "Post");
        log.debug("[CONSUMER] POST_SHARED dispatched → author={}", author.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  User — @-mentions (any source: post / comment / research / answer …)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Single handler for {@code @username} and {@code @followers} mentions
     * across every text-bearing resource. The publisher already resolved
     * direct recipients (skipping self + blocked), so this method just:
     * fetches actor + recipients in batch, writes notifications, expands
     * {@code @followers} via {@link #fanOutToFollowers}, and pushes realtime.
     */
    @RabbitHandler
    @Transactional
    public void onUserMentioned(UserMentionedEvent event) {
        log.info("[CONSUMER] UserMentioned — source={} sourceId={} mentioner={} direct={} followers={}",
                event.getSourceType(), event.getSourceId(), event.getMentionerId(),
                event.getMentionedUserIds() == null ? 0 : event.getMentionedUserIds().size(),
                event.isNotifyFollowers());

        Optional<User> actorOpt = userRepo.findActiveById(event.getMentionerId());
        if (actorOpt.isEmpty()) {
            log.warn("[CONSUMER] UserMentioned skipped — actor not found id={}", event.getMentionerId());
            return;
        }
        User actor = actorOpt.get();

        Set<UUID> directIds = event.getMentionedUserIds() == null
                ? new HashSet<>()
                : new HashSet<>(event.getMentionedUserIds());
        // Belt-and-braces: never notify the author of their own content.
        directIds.remove(actor.getId());

        String resourceType = toResourceType(event.getSourceType());
        UUID resourceId = event.getSourceParentId() != null ? event.getSourceParentId() : event.getSourceId();
        String title = "You were mentioned";
        String body = buildMentionBody(actor, event.getSourceType(), event.getSnippet());

        // ── direct mentions ──────────────────────────────────────────────
        Set<UUID> alreadyNotified = new HashSet<>();
        if (!directIds.isEmpty()) {
            List<User> recipients = userRepo.findActiveByIdIn(directIds);
            List<Notification> notifs = new ArrayList<>(recipients.size());
            for (User recipient : recipients) {
                Notification n = Notification.builder()
                        .user(recipient)
                        .actor(actor)
                        .type(NotificationType.USER_MENTIONED)
                        .title(title)
                        .body(body)
                        .resourceId(resourceId)
                        .resourceType(resourceType)
                        .build();
                notifs.add(n);
                alreadyNotified.add(recipient.getId());
            }
            if (!notifs.isEmpty()) {
                notifRepo.saveAll(notifs);
                notifs.forEach(n -> pushRealtime(n.getUser().getId(), n));
            }
        }

        // ── @followers fan-out ───────────────────────────────────────────
        if (event.isNotifyFollowers()) {
            int sent = fanOutToFollowers(actor.getId(), follower -> {
                if (alreadyNotified.contains(follower.getId())) return null; // dedupe vs direct mentions
                if (follower.getId().equals(actor.getId())) return null;
                return Notification.builder()
                        .user(follower)
                        .actor(actor)
                        .type(NotificationType.USER_MENTIONED)
                        .title(title)
                        .body(body)
                        .resourceId(resourceId)
                        .resourceType(resourceType)
                        .build();
            });
            log.info("[CONSUMER] UserMentioned — {} follower notification(s) saved & queued", sent);
        }
    }

    private static String toResourceType(MentionSource source) {
        return switch (source) {
            case POST              -> "Post";
            case POST_COMMENT      -> "PostComment";
            case RESEARCH          -> "Research";
            case RESEARCH_COMMENT  -> "ResearchComment";
            case QUESTION          -> "Question";
            case QUESTION_ANSWER   -> "QuestionAnswer";
        };
    }

    private static String buildMentionBody(User actor, MentionSource source, String snippet) {
        String where = switch (source) {
            case POST              -> "in a post";
            case POST_COMMENT      -> "in a comment";
            case RESEARCH          -> "in a research publication";
            case RESEARCH_COMMENT  -> "in a research comment";
            case QUESTION          -> "in a question";
            case QUESTION_ANSWER   -> "in an answer";
        };
        String head = actor.getFullName() + " (@" + actor.getUsername() + ") mentioned you " + where;
        return (snippet == null || snippet.isBlank()) ? (head + ".") : (head + ": \"" + snippet + "\"");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code recipientId} has restricted
     * {@code actorId} — IG-style restriction silently swallows the
     * notification while preserving the underlying activity record.
     * Defaults to {@code false} on any unexpected error so a bad
     * restriction-table read never blocks legitimate notifications.
     */
    private boolean isSilencedByRestriction(UUID recipientId, UUID actorId, String tag) {
        if (recipientId == null || actorId == null || recipientId.equals(actorId)) return false;
        try {
            if (restrictionRepo.isRestricting(recipientId, actorId)) {
                log.debug("[CONSUMER] {} muted by restriction recipient={} actor={}",
                        tag, recipientId, actorId);
                return true;
            }
        } catch (Exception ex) {
            log.warn("[CONSUMER] {} restriction check failed: {}", tag, ex.getMessage());
        }
        return false;
    }

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

    /** Maps AnswerReactionType enum names to display emoji */
    private String toReadableAnswerReaction(String reactionType) {
        if (reactionType == null) return "";
        return switch (reactionType) {
            case "LIKE"        -> "👍";
            case "INSIGHTFUL"  -> "💡";
            case "BENEFICIAL"  -> "✨";
            case "AGREE"       -> "✅";
            case "DISAGREE"    -> "❌";
            case "THANKS"      -> "🙏";
            default            -> reactionType;
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
                    .filter(java.util.Objects::nonNull)
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

        // Parent-comment author resolution used to do a JPA lookup on
        // post_comments. Comments now live in Cassandra and the event
        // payload should already carry parentCommentAuthorId. If it doesn't,
        // we silently skip the reply notification rather than do a slow
        // cross-store lookup on the hot path.
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  User-activity recording
    // ══════════════════════════════════════════════════════════════════════════

    private void recordReactionActivity(PostReactedEvent event) {
        try {
            PostReactionType type = parseReactionType(event.getReactionType());
            userActivityService.recordPostReaction(event.getReactorId(), event.getPostId(), type);
        } catch (Exception e) {
            log.warn("[ACTIVITY] failed to record post reaction activity (postId={}, userId={}): {}",
                    event.getPostId(), event.getReactorId(), e.getMessage());
        }
    }

    private void recordCommentActivity(PostCommentedEvent event) {
        try {
            userActivityService.recordPostComment(
                    event.getCommentAuthorId(), event.getPostId(), event.getCommentId());
        } catch (Exception e) {
            log.warn("[ACTIVITY] failed to record post comment activity (postId={}, commentId={}): {}",
                    event.getPostId(), event.getCommentId(), e.getMessage());
        }
    }

    private void recordShareActivity(PostSharedEvent event) {
        try {
            userActivityService.recordPostShare(event.getSharerId(), event.getPostId());
        } catch (Exception e) {
            log.warn("[ACTIVITY] failed to record post share activity (postId={}, sharerId={}): {}",
                    event.getPostId(), event.getSharerId(), e.getMessage());
        }
    }

    private void recordCommentReactionActivity(PostCommentReactedEvent event) {
        try {
            PostReactionType type = parseReactionType(event.getReactionType());
            userActivityService.recordPostCommentReaction(
                    event.getReactorId(), event.getPostId(), event.getCommentId(), type);
        } catch (Exception e) {
            log.warn("[ACTIVITY] failed to record post comment reaction activity (commentId={}, userId={}): {}",
                    event.getCommentId(), event.getReactorId(), e.getMessage());
        }
    }

    private PostReactionType parseReactionType(String raw) {
        if (raw == null) return null;
        try {
            return PostReactionType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ── Research activity recording helpers ──────────────────────────────────

    private void recordResearchReactionActivity(ResearchReactedEvent event) {
        try {
            userActivityService.recordResearchReaction(event.actorId(), event.researchId());
        } catch (Exception e) {
            log.warn("[ACTIVITY] failed to record research reaction (researchId={}, userId={}): {}",
                    event.researchId(), event.actorId(), e.getMessage());
        }
    }

    private void recordResearchCommentActivity(ResearchCommentedEvent event) {
        try {
            userActivityService.recordResearchComment(event.actorId(), event.researchId(), event.commentId());
        } catch (Exception e) {
            log.warn("[ACTIVITY] failed to record research comment (researchId={}, commentId={}): {}",
                    event.researchId(), event.commentId(), e.getMessage());
        }
    }

    private void recordResearchCommentReactionActivity(ResearchCommentReactedEvent event) {
        try {
            userActivityService.recordResearchCommentReaction(
                    event.reactorId(), event.researchId(), event.commentId());
        } catch (Exception e) {
            log.warn("[ACTIVITY] failed to record research comment reaction (commentId={}, userId={}): {}",
                    event.commentId(), event.reactorId(), e.getMessage());
        }
    }
}