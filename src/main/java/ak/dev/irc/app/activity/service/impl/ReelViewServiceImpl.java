package ak.dev.irc.app.activity.service.impl;

import ak.dev.irc.app.activity.dto.ReelViewResponse;
import ak.dev.irc.app.activity.entity.ReelView;
import ak.dev.irc.app.activity.mapper.ReelViewMapper;
import ak.dev.irc.app.activity.repository.ReelViewRepository;
import ak.dev.irc.app.activity.service.ReelViewService;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.enums.PostType;
import ak.dev.irc.app.post.repository.PostRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReelViewServiceImpl implements ReelViewService {

    private final ReelViewRepository reelViewRepo;
    private final UserRepository userRepo;
    private final PostRepository postRepo;
    private final ReelViewMapper mapper;

    @Override
    @Transactional
    public ReelViewResponse recordWatch(UUID userId, UUID postId, Integer watchedSeconds) {
        User user = userRepo.findActiveById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        Post post = postRepo.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));

        if (post.getPostType() != PostType.REEL) {
            throw new BadRequestException("Post is not a reel", "NOT_A_REEL");
        }

        ReelView view = ReelView.builder()
                .user(user)
                .post(post)
                .watchedSeconds(watchedSeconds)
                .build();
        ReelView saved = reelViewRepo.save(view);

        postRepo.incrementViewCount(post.getId());

        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReelViewResponse> listMyWatched(UUID userId, Pageable pageable) {
        return reelViewRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(mapper::toResponse);
    }

    @Override
    @Transactional
    public void deleteOne(UUID userId, UUID reelViewId) {
        ReelView view = reelViewRepo.findById(reelViewId)
                .orElseThrow(() -> new ResourceNotFoundException("ReelView", "id", reelViewId));
        if (!view.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You cannot delete another user's watch history");
        }
        reelViewRepo.delete(view);
    }

    @Override
    @Transactional
    public int deleteAll(UUID userId) {
        return reelViewRepo.deleteAllByUserId(userId);
    }
}
