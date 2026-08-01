package ak.dev.irc.app.settings.privacy.service;

import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.settings.privacy.entity.PrivacyList;
import ak.dev.irc.app.settings.privacy.entity.PrivacyListMember;
import ak.dev.irc.app.settings.privacy.enums.PrivacyListType;
import ak.dev.irc.app.settings.privacy.repository.PrivacyListMemberRepository;
import ak.dev.irc.app.settings.privacy.repository.PrivacyListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Custom privacy lists (spec §5) — named audiences the {@code CUSTOM} visibility
 * policy resolves against. Close Friends keeps its dedicated subsystem; this is
 * only for user-defined custom lists.
 */
@Service
@RequiredArgsConstructor
public class PrivacyListService {

    private final PrivacyListRepository listRepo;
    private final PrivacyListMemberRepository memberRepo;

    @Transactional(readOnly = true)
    public List<PrivacyList> myLists(UUID ownerId) {
        return listRepo.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional
    public PrivacyList create(UUID ownerId, String name) {
        return listRepo.save(PrivacyList.builder()
                .ownerId(ownerId)
                .name(name == null || name.isBlank() ? "Custom list" : name.trim())
                .type(PrivacyListType.CUSTOM)
                .build());
    }

    @Transactional
    public void delete(UUID ownerId, UUID listId) {
        PrivacyList list = requireOwned(ownerId, listId);
        memberRepo.deleteAllByListId(list.getId());
        listRepo.delete(list);
    }

    @Transactional
    public void addMember(UUID ownerId, UUID listId, UUID memberId) {
        requireOwned(ownerId, listId);
        if (!memberRepo.existsByIdListIdAndIdMemberId(listId, memberId)) {
            memberRepo.save(PrivacyListMember.builder()
                    .id(new PrivacyListMember.PrivacyListMemberId(listId, memberId))
                    .build());
        }
    }

    @Transactional
    public void removeMember(UUID ownerId, UUID listId, UUID memberId) {
        requireOwned(ownerId, listId);
        memberRepo.deleteById(new PrivacyListMember.PrivacyListMemberId(listId, memberId));
    }

    @Transactional(readOnly = true)
    public List<UUID> memberIds(UUID ownerId, UUID listId) {
        requireOwned(ownerId, listId);
        return memberRepo.findMemberIds(listId);
    }

    private PrivacyList requireOwned(UUID ownerId, UUID listId) {
        PrivacyList list = listRepo.findById(listId)
                .orElseThrow(() -> new ResourceNotFoundException("PrivacyList", "id", listId));
        if (!list.getOwnerId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this list.", "NOT_LIST_OWNER");
        }
        return list;
    }
}
