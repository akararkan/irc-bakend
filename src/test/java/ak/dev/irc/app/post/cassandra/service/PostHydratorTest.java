package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.FeedByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.entity.ReelsByDayEntity;
import ak.dev.irc.app.post.cassandra.repository.CommentCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentReactionRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.repository.PostCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.ReactionByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.SaveLookupRepository;
import ak.dev.irc.app.post.dto.FeedItemResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostHydratorTest {

    @Mock private UserRepository userRepo;
    @Mock private PostByIdRepository postByIdRepo;
    @Mock private PostCounterRepository postCounterRepo;
    @Mock private CommentCounterRepository commentCounterRepo;
    @Mock private ReactionByPostRepository reactionRepo;
    @Mock private CommentReactionRepository commentReactionRepo;
    @Mock private SaveLookupRepository saveRepo;

    private PostHydrator hydrator;

    @BeforeEach
    void setUp() {
        hydrator = new PostHydrator(
                userRepo, postByIdRepo, postCounterRepo, commentCounterRepo,
                reactionRepo, commentReactionRepo, saveRepo);
    }

    @Test
    @DisplayName("home feed: skips stale deleted POST rows but keeps non-POST entities")
    void hydrateHomeFeed_skipsDeletedPosts_keepsResearchRows() {
        UUID viewerId = UUID.randomUUID();
        UUID postAuthorId = UUID.randomUUID();
        UUID researchAuthorId = UUID.randomUUID();
        UUID deletedPostId = UUID.randomUUID();
        UUID researchId = UUID.randomUUID();

        FeedByUserEntity stalePost = FeedByUserEntity.builder()
                .userId(viewerId)
                .createdAt(Instant.now())
                .postId(deletedPostId)
                .authorId(postAuthorId)
                .postType("POST")
                .textPreview("old post")
                .mediaUrl("https://cdn/post.jpg")
                .entityType("POST")
                .build();

        FeedByUserEntity research = FeedByUserEntity.builder()
                .userId(viewerId)
                .createdAt(Instant.now().minusSeconds(1))
                .postId(researchId)
                .authorId(researchAuthorId)
                .postType("PUBLICATION")
                .textPreview("paper")
                .mediaUrl("https://cdn/paper.jpg")
                .entityType("RESEARCH")
                .build();

        when(userRepo.findAllById(anySet())).thenReturn(List.of(
                user(postAuthorId, "postAuthor"),
                user(researchAuthorId, "researchAuthor")));
        when(postByIdRepo.findAllById(Set.of(deletedPostId))).thenReturn(List.of());
        when(postCounterRepo.findAllByPostIdIn(Set.of(deletedPostId))).thenReturn(List.of());

        List<FeedItemResponse> out = hydrator.hydrateHomeFeed(List.of(stalePost, research));

        assertThat(out).hasSize(1);
        assertThat(out.getFirst().id()).isEqualTo(researchId);
        assertThat(out.getFirst().entityType()).isEqualTo("RESEARCH");
    }

    @Test
    @DisplayName("profile feed: drops rows whose canonical post is missing")
    void hydrateProfileFeed_skipsMissingCanonical() {
        UUID authorId = UUID.randomUUID();
        UUID deletedPostId = UUID.randomUUID();

        PostByAuthorEntity stale = PostByAuthorEntity.builder()
                .authorId(authorId)
                .createdAt(Instant.now())
                .postId(deletedPostId)
                .postType("POST")
                .textPreview("old")
                .mediaUrl("https://cdn/old.jpg")
                .build();

        when(userRepo.findAllById(Set.of(authorId))).thenReturn(List.of(user(authorId, "author")));
        when(postByIdRepo.findAllById(Set.of(deletedPostId))).thenReturn(List.of());
        when(postCounterRepo.findAllByPostIdIn(Set.of(deletedPostId))).thenReturn(List.of());

        List<FeedItemResponse> out = hydrator.hydrateProfileFeed(List.of(stale));

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("reels feed: drops deleted rows and extracts video URL from canonical row")
    void hydrateReels_skipsMissingCanonical_andKeepsLiveReel() {
        UUID authorId = UUID.randomUUID();
        UUID liveReelId = UUID.randomUUID();
        UUID deletedReelId = UUID.randomUUID();

        ReelsByDayEntity liveRow = ReelsByDayEntity.builder()
                .dayBucket("2026-07-17")
                .createdAt(Instant.now())
                .postId(liveReelId)
                .authorId(authorId)
                .textPreview("live")
                .mediaUrl("https://cdn/live.jpg")
                .build();
        ReelsByDayEntity staleRow = ReelsByDayEntity.builder()
                .dayBucket("2026-07-17")
                .createdAt(Instant.now().minusSeconds(1))
                .postId(deletedReelId)
                .authorId(authorId)
                .textPreview("stale")
                .mediaUrl("https://cdn/stale.jpg")
                .build();

        PostByIdEntity liveCanonical = PostByIdEntity.builder()
                .id(liveReelId)
                .authorId(authorId)
                .postType("REEL")
                .mediaUrls(List.of("https://cdn/live.mp4"))
                .mediaTypes(List.of("VIDEO"))
                .build();

        when(userRepo.findAllById(Set.of(authorId))).thenReturn(List.of(user(authorId, "author")));
        when(postByIdRepo.findAllById(Set.of(liveReelId, deletedReelId))).thenReturn(List.of(liveCanonical));
        when(postCounterRepo.findAllByPostIdIn(Set.of(liveReelId, deletedReelId))).thenReturn(List.of());

        List<FeedItemResponse> out = hydrator.hydrateReels(List.of(liveRow, staleRow));

        assertThat(out).hasSize(1);
        assertThat(out.getFirst().id()).isEqualTo(liveReelId);
        assertThat(out.getFirst().videoUrl()).isEqualTo("https://cdn/live.mp4");
    }

    private static User user(UUID id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .fname("F")
                .lname("L")
                .email(username + "@test.local")
                .build();
    }
}
