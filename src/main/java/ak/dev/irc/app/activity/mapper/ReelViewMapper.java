package ak.dev.irc.app.activity.mapper;

import ak.dev.irc.app.activity.cassandra.entity.ReelViewEntity;
import ak.dev.irc.app.activity.dto.ReelViewResponse;
import ak.dev.irc.app.common.util.TimeDisplayUtil;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class ReelViewMapper {

    public ReelViewResponse toResponse(ReelViewEntity r) {
        LocalDateTime ts = r.getCreatedAt() == null
                ? null
                : LocalDateTime.ofInstant(r.getCreatedAt(), ZoneOffset.UTC);
        return ReelViewResponse.builder()
                .id(r.getReelViewId())
                .watchedSeconds(r.getWatchedSeconds())
                .reel(ReelViewResponse.ReelSummary.builder().id(r.getPostId()).build())
                .watchedAt(ts)
                .timeAgo(TimeDisplayUtil.timeAgo(ts))
                .formattedDate(TimeDisplayUtil.formattedDate(ts))
                .build();
    }
}
