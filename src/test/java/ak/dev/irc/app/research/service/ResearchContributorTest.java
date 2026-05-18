package ak.dev.irc.app.research.service;

import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ConflictException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.service.MentionService;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.rabbitmq.publisher.ResearchEventPublisher;
import ak.dev.irc.app.research.dto.request.ContributorRequest;
import ak.dev.irc.app.research.dto.request.UpdateContributorRequest;
import ak.dev.irc.app.research.dto.response.ContributorResponse;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.entity.ResearchContributor;
import ak.dev.irc.app.research.enums.ContributorRole;
import ak.dev.irc.app.research.mapper.ResearchMapper;
import ak.dev.irc.app.research.realtime.ResearchRealtimeBroadcaster;
import ak.dev.irc.app.research.realtime.ResearchViewTracker;
import ak.dev.irc.app.research.repository.ResearchCommentReactionRepository;
import ak.dev.irc.app.research.repository.ResearchCommentRepository;
import ak.dev.irc.app.research.repository.ResearchContributorRepository;
import ak.dev.irc.app.research.repository.ResearchDownloadRepository;
import ak.dev.irc.app.research.repository.ResearchMediaRepository;
import ak.dev.irc.app.research.repository.ResearchReactionRepository;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.research.repository.ResearchSaveRepository;
import ak.dev.irc.app.research.repository.ResearchSourceRepository;
import ak.dev.irc.app.research.repository.ResearchTagRepository;
import ak.dev.irc.app.research.service.impl.ResearchServiceImpl;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.AccountType;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the research-contributor flow on {@link ResearchServiceImpl}.
 *
 * <p>Asserts ownership enforcement, eligibility checks (RESEARCHER / SCHOLAR
 * only), duplicate rejection, the owner-self-listing guard, and the replace
 * / update / remove flows.</p>
 */
@ExtendWith(MockitoExtension.class)
class ResearchContributorTest {

    @Mock private ResearchRepository         researchRepo;
    @Mock private ResearchMediaRepository    mediaRepo;
    @Mock private ResearchSourceRepository   sourceRepo;
    @Mock private ResearchContributorRepository contributorRepo;
    @Mock private ResearchTagRepository      tagRepo;
    @Mock private ResearchCommentRepository  commentRepo;
    @Mock private ResearchCommentReactionRepository commentReactionRepo;
    @Mock private ResearchReactionRepository reactionRepo;
    @Mock private ResearchSaveRepository     saveRepo;
    @Mock private ResearchDownloadRepository downloadRepo;
    @Mock private UserRepository             userRepo;
    @Mock private UserFollowRepository       followRepo;
    @Mock private UserBlockRepository        blockRepo;
    @Mock private S3StorageService           s3;
    @Mock private VideoMetadataExtractor     videoMetadataExtractor;
    @Mock private ResearchMapper             mapper;
    @Mock private IrcIdentifierService       ircIdentifierService;
    @Mock private ResearchEventPublisher     researchEventPublisher;
    @Mock private MentionService             mentionService;
    @Mock private ResearchRealtimeBroadcaster researchRealtime;
    @Mock private SocialGuard                socialGuard;
    @Mock private CounterCache               counterCache;
    @Mock private RateLimiter                rateLimiter;
    @Mock private ResearchViewTracker        viewTracker;
    @Mock private ak.dev.irc.app.share.FrontendUrlResolver frontendUrlResolver;
    @Mock private ak.dev.irc.app.user.service.NotificationDispatcher notificationDispatcher;
    @Mock private EntityManager              entityManager;

    @InjectMocks private ResearchServiceImpl service;

    private UUID researchId;
    private UUID ownerId;
    private UUID coAuthorId;
    private UUID scholarId;
    private UUID regularUserId;
    private UUID outsiderId;

    private User owner;
    private User coAuthor;
    private User scholar;
    private User regularUser;
    private Research research;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        researchId    = UUID.randomUUID();
        ownerId       = UUID.randomUUID();
        coAuthorId    = UUID.randomUUID();
        scholarId     = UUID.randomUUID();
        regularUserId = UUID.randomUUID();
        outsiderId    = UUID.randomUUID();

        owner       = userOf(ownerId,       Role.RESEARCHER, AccountType.VERIFIED_RESEARCHER);
        coAuthor    = userOf(coAuthorId,    Role.RESEARCHER, AccountType.VERIFIED_RESEARCHER);
        scholar     = userOf(scholarId,     Role.SCHOLAR,    AccountType.VERIFIED_SCHOLAR);
        regularUser = userOf(regularUserId, Role.USER,       AccountType.REGULAR);

        research = Research.builder()
                .id(researchId)
                .researcher(owner)
                .contributors(new ArrayList<>())
                .build();
    }

    // ── add ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addContributor: happy path persists a row and returns the mapped response")
    void addContributor_happyPath() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(contributorRepo.existsByResearchIdAndUserId(researchId, coAuthorId)).thenReturn(false);
        when(userRepo.findById(coAuthorId)).thenReturn(Optional.of(coAuthor));
        when(contributorRepo.countByResearchId(researchId)).thenReturn(0L);
        when(contributorRepo.save(any(ResearchContributor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toContributorResponse(any(ResearchContributor.class)))
                .thenAnswer(inv -> stubbedResponse((ResearchContributor) inv.getArgument(0)));

        ContributorRequest req = new ContributorRequest(
                coAuthorId, ContributorRole.CO_AUTHOR, null, "Wrote section 3");

        ContributorResponse out = service.addContributor(researchId, req, ownerId);

        ArgumentCaptor<ResearchContributor> saved = ArgumentCaptor.forClass(ResearchContributor.class);
        verify(contributorRepo).save(saved.capture());

        ResearchContributor row = saved.getValue();
        assertThat(row.getResearch()).isSameAs(research);
        assertThat(row.getUser()).isSameAs(coAuthor);
        assertThat(row.getRole()).isEqualTo(ContributorRole.CO_AUTHOR);
        assertThat(row.getDisplayOrder()).isZero();
        assertThat(row.getContributionNote()).isEqualTo("Wrote section 3");
        assertThat(out.userId()).isEqualTo(coAuthorId);
        assertThat(out.role()).isEqualTo(ContributorRole.CO_AUTHOR);
    }

    @Test
    @DisplayName("addContributor: rejects when the caller does not own the research")
    void addContributor_nonOwner_throwsForbidden() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));

        ContributorRequest req = new ContributorRequest(
                coAuthorId, ContributorRole.CO_AUTHOR, null, null);

        assertThatThrownBy(() -> service.addContributor(researchId, req, outsiderId))
                .isInstanceOf(ForbiddenException.class);

        verify(contributorRepo, never()).save(any());
    }

    @Test
    @DisplayName("addContributor: rejects listing the corresponding researcher as their own contributor")
    void addContributor_owner_isOwnContributor() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));

        ContributorRequest req = new ContributorRequest(
                ownerId, ContributorRole.CO_AUTHOR, null, null);

        assertThatThrownBy(() -> service.addContributor(researchId, req, ownerId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("corresponding researcher");

        verify(contributorRepo, never()).save(any());
    }

    @Test
    @DisplayName("addContributor: rejects duplicates with 409")
    void addContributor_duplicate_throwsConflict() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(contributorRepo.existsByResearchIdAndUserId(researchId, coAuthorId)).thenReturn(true);

        ContributorRequest req = new ContributorRequest(
                coAuthorId, ContributorRole.CO_AUTHOR, null, null);

        assertThatThrownBy(() -> service.addContributor(researchId, req, ownerId))
                .isInstanceOf(ConflictException.class);

        verify(contributorRepo, never()).save(any());
    }

    @Test
    @DisplayName("addContributor: rejects a regular USER (not RESEARCHER/SCHOLAR)")
    void addContributor_userNotEligible_throwsBadRequest() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(contributorRepo.existsByResearchIdAndUserId(researchId, regularUserId)).thenReturn(false);
        when(userRepo.findById(regularUserId)).thenReturn(Optional.of(regularUser));

        ContributorRequest req = new ContributorRequest(
                regularUserId, ContributorRole.CO_AUTHOR, null, null);

        assertThatThrownBy(() -> service.addContributor(researchId, req, ownerId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("researcher or scholar");

        verify(contributorRepo, never()).save(any());
    }

    @Test
    @DisplayName("addContributor: accepts a SCHOLAR")
    void addContributor_scholar_isEligible() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(contributorRepo.existsByResearchIdAndUserId(researchId, scholarId)).thenReturn(false);
        when(userRepo.findById(scholarId)).thenReturn(Optional.of(scholar));
        when(contributorRepo.save(any(ResearchContributor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toContributorResponse(any(ResearchContributor.class)))
                .thenAnswer(inv -> stubbedResponse((ResearchContributor) inv.getArgument(0)));

        ContributorRequest req = new ContributorRequest(
                scholarId, ContributorRole.ADVISOR, 5, "Thesis advisor");

        ContributorResponse out = service.addContributor(researchId, req, ownerId);

        assertThat(out.userId()).isEqualTo(scholarId);
        assertThat(out.role()).isEqualTo(ContributorRole.ADVISOR);
        assertThat(out.displayOrder()).isEqualTo(5);
    }

    @Test
    @DisplayName("addContributor: defaults displayOrder to the current contributor count when omitted")
    void addContributor_displayOrder_defaultsToCount() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(contributorRepo.existsByResearchIdAndUserId(researchId, coAuthorId)).thenReturn(false);
        when(userRepo.findById(coAuthorId)).thenReturn(Optional.of(coAuthor));
        when(contributorRepo.countByResearchId(researchId)).thenReturn(3L);
        when(contributorRepo.save(any(ResearchContributor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toContributorResponse(any(ResearchContributor.class)))
                .thenAnswer(inv -> stubbedResponse((ResearchContributor) inv.getArgument(0)));

        ContributorRequest req = new ContributorRequest(
                coAuthorId, ContributorRole.CO_AUTHOR, null, null);

        service.addContributor(researchId, req, ownerId);

        ArgumentCaptor<ResearchContributor> saved = ArgumentCaptor.forClass(ResearchContributor.class);
        verify(contributorRepo).save(saved.capture());
        assertThat(saved.getValue().getDisplayOrder()).isEqualTo(3);
    }

    @Test
    @DisplayName("addContributor: dispatches RESEARCH_CONTRIBUTOR_ADDED notification to the new contributor")
    void addContributor_sendsNotification() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(contributorRepo.existsByResearchIdAndUserId(researchId, coAuthorId)).thenReturn(false);
        when(userRepo.findById(coAuthorId)).thenReturn(Optional.of(coAuthor));
        when(contributorRepo.countByResearchId(researchId)).thenReturn(0L);
        when(contributorRepo.save(any(ResearchContributor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toContributorResponse(any(ResearchContributor.class)))
                .thenAnswer(inv -> stubbedResponse((ResearchContributor) inv.getArgument(0)));

        ContributorRequest req = new ContributorRequest(
                coAuthorId, ContributorRole.CO_AUTHOR, null, "Wrote section 3");

        service.addContributor(researchId, req, ownerId);

        ArgumentCaptor<ak.dev.irc.app.user.entity.Notification> sent =
                ArgumentCaptor.forClass(ak.dev.irc.app.user.entity.Notification.class);
        verify(notificationDispatcher).dispatch(sent.capture());

        ak.dev.irc.app.user.entity.Notification n = sent.getValue();
        assertThat(n.getUser().getId()).isEqualTo(coAuthorId);
        assertThat(n.getActor().getId()).isEqualTo(ownerId);
        assertThat(n.getType())
                .isEqualTo(ak.dev.irc.app.user.enums.NotificationType.RESEARCH_CONTRIBUTOR_ADDED);
        assertThat(n.getResourceId()).isEqualTo(researchId);
        assertThat(n.getResourceType()).isEqualTo("Research");
        assertThat(n.getBody())
                .as("body should name the owner and mention 'co author'")
                .contains("co author");
    }

    // ── update ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateContributor: updates only the fields supplied (null fields untouched)")
    void updateContributor_patchSemantics() {
        UUID rowId = UUID.randomUUID();
        ResearchContributor row = ResearchContributor.builder()
                .id(rowId)
                .research(research)
                .user(coAuthor)
                .role(ContributorRole.CO_AUTHOR)
                .displayOrder(2)
                .contributionNote("original")
                .build();

        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(contributorRepo.findById(rowId)).thenReturn(Optional.of(row));
        when(contributorRepo.save(any(ResearchContributor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toContributorResponse(any(ResearchContributor.class)))
                .thenAnswer(inv -> stubbedResponse((ResearchContributor) inv.getArgument(0)));

        UpdateContributorRequest req = new UpdateContributorRequest(
                ContributorRole.EDITOR, null, "updated note");

        ContributorResponse out = service.updateContributor(researchId, rowId, req, ownerId);

        assertThat(row.getRole()).isEqualTo(ContributorRole.EDITOR);
        assertThat(row.getDisplayOrder())
                .as("null displayOrder must NOT overwrite the existing value")
                .isEqualTo(2);
        assertThat(row.getContributionNote()).isEqualTo("updated note");
        assertThat(out.role()).isEqualTo(ContributorRole.EDITOR);
    }

    @Test
    @DisplayName("updateContributor: rejects a contributor that belongs to a different research")
    void updateContributor_wrongParent_throwsBadRequest() {
        UUID rowId = UUID.randomUUID();
        Research otherResearch = Research.builder().id(UUID.randomUUID()).researcher(owner).build();
        ResearchContributor row = ResearchContributor.builder()
                .id(rowId).research(otherResearch).user(coAuthor)
                .role(ContributorRole.CO_AUTHOR).displayOrder(0).build();

        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(contributorRepo.findById(rowId)).thenReturn(Optional.of(row));

        UpdateContributorRequest req = new UpdateContributorRequest(
                ContributorRole.EDITOR, null, null);

        assertThatThrownBy(() -> service.updateContributor(researchId, rowId, req, ownerId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not belong");
    }

    // ── replace ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("replaceContributors: empty list clears every contributor")
    void replaceContributors_emptyList_clears() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));

        List<ContributorResponse> out =
                service.replaceContributors(researchId, List.of(), ownerId);

        verify(contributorRepo).deleteAllByResearchId(researchId);
        verify(contributorRepo, never()).save(any());
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("replaceContributors: rejects duplicate user ids in a single request")
    void replaceContributors_duplicateInList_throwsBadRequest() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(userRepo.findById(coAuthorId)).thenReturn(Optional.of(coAuthor));

        ContributorRequest dup = new ContributorRequest(
                coAuthorId, ContributorRole.CO_AUTHOR, null, null);

        assertThatThrownBy(() -> service.replaceContributors(
                researchId, List.of(dup, dup), ownerId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Duplicate contributor");
    }

    @Test
    @DisplayName("replaceContributors: persists two distinct contributors")
    void replaceContributors_twoContributors() {
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(userRepo.findById(coAuthorId)).thenReturn(Optional.of(coAuthor));
        when(userRepo.findById(scholarId)).thenReturn(Optional.of(scholar));
        when(contributorRepo.save(any(ResearchContributor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toContributorResponse(any(ResearchContributor.class)))
                .thenAnswer(inv -> stubbedResponse((ResearchContributor) inv.getArgument(0)));

        List<ContributorRequest> reqs = List.of(
                new ContributorRequest(coAuthorId, ContributorRole.CO_AUTHOR, null, null),
                new ContributorRequest(scholarId, ContributorRole.ADVISOR, null, null)
        );

        List<ContributorResponse> out = service.replaceContributors(researchId, reqs, ownerId);

        verify(contributorRepo).deleteAllByResearchId(researchId);
        verify(contributorRepo, times(2)).save(any(ResearchContributor.class));
        assertThat(out).hasSize(2);
        assertThat(out).extracting(ContributorResponse::userId)
                .containsExactlyInAnyOrder(coAuthorId, scholarId);
    }

    // ── remove ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("removeContributor: happy path deletes the row")
    void removeContributor_happyPath() {
        UUID rowId = UUID.randomUUID();
        ResearchContributor row = ResearchContributor.builder()
                .id(rowId).research(research).user(coAuthor)
                .role(ContributorRole.CO_AUTHOR).displayOrder(0).build();

        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(contributorRepo.findById(rowId)).thenReturn(Optional.of(row));

        service.removeContributor(researchId, rowId, ownerId);

        verify(contributorRepo).delete(row);
    }

    @Test
    @DisplayName("removeContributor: rejects when caller is not the research owner")
    void removeContributor_nonOwner_throwsForbidden() {
        UUID rowId = UUID.randomUUID();
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));

        assertThatThrownBy(() -> service.removeContributor(researchId, rowId, outsiderId))
                .isInstanceOf(ForbiddenException.class);

        verify(contributorRepo, never()).delete(any(ResearchContributor.class));
    }

    @Test
    @DisplayName("removeContributor: 404 when the contributor row does not exist")
    void removeContributor_missingRow_throwsNotFound() {
        UUID rowId = UUID.randomUUID();
        when(researchRepo.findByIdAndDeletedAtIsNull(researchId)).thenReturn(Optional.of(research));
        when(contributorRepo.findById(rowId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeContributor(researchId, rowId, ownerId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── list ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getContributors: returns mapped responses in displayOrder")
    void getContributors_returnsMappedList() {
        ResearchContributor a = ResearchContributor.builder()
                .id(UUID.randomUUID()).research(research).user(coAuthor)
                .role(ContributorRole.CO_AUTHOR).displayOrder(0).build();
        ResearchContributor b = ResearchContributor.builder()
                .id(UUID.randomUUID()).research(research).user(scholar)
                .role(ContributorRole.ADVISOR).displayOrder(1).build();

        when(contributorRepo.findByResearchIdOrderByDisplayOrderAsc(researchId))
                .thenReturn(List.of(a, b));
        when(mapper.toContributorResponse(any(ResearchContributor.class)))
                .thenAnswer(inv -> stubbedResponse((ResearchContributor) inv.getArgument(0)));

        List<ContributorResponse> out = service.getContributors(researchId);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(ContributorResponse::userId)
                .containsExactly(coAuthorId, scholarId);
        assertThat(out).extracting(ContributorResponse::role)
                .containsExactly(ContributorRole.CO_AUTHOR, ContributorRole.ADVISOR);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static User userOf(UUID id, Role role, AccountType type) {
        return User.builder()
                .id(id)
                .fname("F").lname("L")
                .username("u-" + id.toString().substring(0, 6))
                .email(id + "@test")
                .role(role)
                .accountType(type)
                .build();
    }

    private static ContributorResponse stubbedResponse(ResearchContributor row) {
        User u = row.getUser();
        return new ContributorResponse(
                row.getId(),
                u.getId(),
                u.getFullName(),
                u.getUsername(),
                null,
                u.getRole(),
                u.getAccountType(),
                row.getRole(),
                row.getDisplayOrder(),
                row.getContributionNote(),
                null
        );
    }
}
