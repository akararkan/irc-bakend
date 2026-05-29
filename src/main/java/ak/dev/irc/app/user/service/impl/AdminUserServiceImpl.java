package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.user.dto.request.AdminChangeRoleRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.mapper.UserMapper;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminUserServiceImpl implements AdminUserService {

    private final UserRepository userRepository;
    private final UserMapper     userMapper;

    @Override
    public UserResponse changeRole(UUID targetUserId, AdminChangeRoleRequest req) {
        if (req == null || req.role() == null)
            throw new BadRequestException("role is required", "INVALID_INPUT");

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", targetUserId));

        Role previous = target.getRole();
        if (previous == req.role()) {
            log.info("Admin role change for user [{}] is a no-op (already {})", target.getId(), previous);
            return userMapper.toResponse(target);
        }

        target.setRole(req.role());

        String note = "Role %s → %s%s".formatted(
                previous, req.role(),
                req.reason() != null && !req.reason().isBlank() ? " (" + req.reason() + ")" : "");
        target.audit(AuditAction.UPDATE, note);

        userRepository.save(target);

        log.info("Admin changed role for user [{}]: {} → {}",
                target.getId(), previous, req.role());

        return userMapper.toResponse(target);
    }
}
