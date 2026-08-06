package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.common.messages.PostMessages;
import ak.dev.irc.app.post.cassandra.entity.MentionByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByHashtagEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraHashtagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CassandraHashtagController {

    private final CassandraHashtagService hashtagService;

    @GetMapping("/hashtags/{tag}/posts")
    public List<PostByHashtagEntity> postsForTag(@PathVariable String tag,
                                                 @RequestParam(defaultValue = "20") int pageSize,
                                                 @RequestParam(required = false) Instant cursor) {
        return cursor == null
                ? hashtagService.postsForTag(tag, pageSize)
                : hashtagService.postsForTagAfter(tag, cursor, pageSize);
    }

    @GetMapping("/hashtags/{tag}/usage")
    public Map<String, Object> usage(@PathVariable String tag) {
        return Map.of("hashtag", tag.toLowerCase(),
                      "postCount", hashtagService.postCountFor(tag));
    }

    /**
     * Legacy raw mention feed — owner-scoped: your mention inbox is private,
     * so the path id must be the caller's own. Prefer the hydrated
     * {@code GET /api/v1/mentions/me}.
     */
    @GetMapping("/users/{userId}/mentions")
    public List<MentionByUserEntity> mentionsForUser(@PathVariable UUID userId,
                                                     @RequestParam(defaultValue = "20") int pageSize) {
        UUID me = ak.dev.irc.app.security.SecurityUtils.getCurrentUserId().orElse(null);
        if (me == null || !me.equals(userId)) {
            throw new ak.dev.irc.app.common.exception.ForbiddenException(
                    PostMessages.NOT_OWNER_MSG, PostMessages.NOT_OWNER);
        }
        return hashtagService.mentionsForUser(userId, pageSize);
    }
}
