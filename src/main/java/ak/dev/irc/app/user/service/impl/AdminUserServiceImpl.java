package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.user.dto.request.AdminChangeAccountTypeRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.AccountType;
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
    public UserResponse changeAccountType(UUID targetUserId,
                                          AdminChangeAccountTypeRequest req) {
        if (req == null || req.accountType() == null)
            throw new BadRequestException("accountType is required", "INVALID_INPUT");

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", targetUserId));

        if (target.isSystemAccount() || target.getAccountType() == AccountType.PLATFORM_OFFICIAL)
            throw new ForbiddenException("The platform system account cannot be modified");

        AccountType previousType = target.getAccountType();
        target.setAccountType(req.accountType());

        if (req.verificationTier() != null)
            target.setVerificationTier(req.verificationTier());

        if (req.role() != null)
            target.setRole(req.role());

        String note = "Account type %s → %s%s".formatted(
                previousType, req.accountType(),
                req.reason() != null && !req.reason().isBlank() ? " (" + req.reason() + ")" : "");
        target.audit(AuditAction.UPDATE, note);

        userRepository.save(target);

        log.info("Admin changed account type for user [{}]: {} → {} (tier={}, role={})",
                target.getId(), previousType, req.accountType(),
                target.getVerificationTier(), target.getRole());

        return userMapper.toResponse(target);
    }
}
