package ak.dev.irc.app.settings.privacy.service;

import ak.dev.irc.app.settings.privacy.entity.UserMute;
import ak.dev.irc.app.settings.privacy.entity.UserMute.UserMuteId;
import ak.dev.irc.app.settings.privacy.repository.UserMuteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One-directional, silent mute (spec §13). Used at feed assembly to filter the
 * muted user's content from the muter's feed. Muting never notifies the muted
 * user and severs nothing.
 */
@Service
@RequiredArgsConstructor
public class MuteService {

    private final UserMuteRepository repo;

    @Transactional
    public void mute(UUID muterId, UUID mutedId) {
        if (muterId.equals(mutedId)) return;
        if (!repo.existsByIdMuterIdAndIdMutedId(muterId, mutedId)) {
            repo.save(UserMute.builder().id(new UserMuteId(muterId, mutedId)).build());
        }
    }

    @Transactional
    public void unmute(UUID muterId, UUID mutedId) {
        repo.deleteById(new UserMuteId(muterId, mutedId));
    }

    /** The set of user-ids this user has muted — feed assembly can pass into NOT IN. */
    @Transactional(readOnly = true)
    public Set<UUID> mutedIds(UUID muterId) {
        return repo.findMutedIds(muterId).stream().collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public List<UUID> mutedList(UUID muterId) {
        return repo.findMutedIds(muterId);
    }

    @Transactional(readOnly = true)
    public boolean isMuted(UUID muterId, UUID mutedId) {
        return repo.existsByIdMuterIdAndIdMutedId(muterId, mutedId);
    }
}
