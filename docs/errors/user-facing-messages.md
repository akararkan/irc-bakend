# User-Facing Messages — the Complete Catalog

> **Scope.** Every note, warning, error, validation message, notification, email
> and response header the backend returns to a human — user-facing app surfaces
> and the admin dashboard alike. This is **documentation only**: the canonical
> error **envelope** (shape, `timestamp`, `status`, `error`, `code`, `message`,
> `details`, `path`) is specified in [`error-handling.md`](error-handling.md);
> this file catalogs the actual **strings and codes** that travel in it.
>
> **How messages reach the user.** Four channels:
> 1. **HTTP error envelope** — thrown exceptions rendered by the global handler
>    (`ApiErrorResponse`): a `code` for the client to branch on, a `message`
>    for the human.
> 2. **Inline response fields** — `note` / `warning` / `source` strings inside
>    a 200 body, used where a successful response still needs a caveat.
> 3. **Notifications** — in-app rows (and their SSE push), with title + body.
> 4. **Email** — subject/verb built by `EmailTemplate` from the notification.
>
> **Conventions.** Codes are SCREAMING_SNAKE. Messages are complete sentences,
> end in a period where they are prose, never leak internals (no stack traces,
> no SQL, no object keys a user shouldn't see), and say what to do next when
> there is a next thing to do. `{braces}` in this catalog mark runtime
> interpolation.

Generated from a full-source sweep on 2026-08-06 plus the same-day admin-build
additions. When adding a message, add it here.

> **Implemented in code (2026-08-06).** These strings are no longer scattered
> inline literals — they live as named constants in
> `ak.dev.irc.app.common.messages.*`, one final class per module:
> `AuthMessages`, `UserMessages`, `SecurityMessages`, `SettingsMessages`,
> `ResearchMessages`, `QnaMessages`, `ChatMessages`, `ChannelStreamMessages`,
> `PostMessages`, `MediaMessages`, `AdminContentMessages`, `AdminOpsMessages`,
> `CommonMessages`, `EmailMessages`. Convention (see the package javadoc):
> constant name = the error code; message text carries an `_MSG` suffix;
> `VAL_`/`NOTE_`/`WARN_`/`NOTIF_` prefixes for validation, response notes,
> warnings and notification copy; interpolated messages are `%s` templates
> rendered with `.formatted(...)`. The 3-arg
> `ResourceNotFoundException(resource, field, value)` /
> `DuplicateResourceException(...)` constructors keep their copy inside the
> exception classes, and a handful of dynamically-assembled messages
> (conditional fragments) remain at their call sites by design — this file
> and those classes are maintained as a pair: change one, change the other.

---

## 1. HTTP error & validation messages

Everything below arrives inside the standard error envelope with the listed status.


### 1.1 `user/auth`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | Current password is required | ChangePasswordRequest.currentPassword blank | POST /api/v1/auth/change-password |
| 400 | `—` | Email is required | RegisterRequest.email blank | POST /api/v1/auth/register |
| 400 | `—` | First name is required | RegisterRequest.fname blank | POST /api/v1/auth/register |
| 400 | `—` | First name must be at most 80 characters | RegisterRequest.fname > 80 chars | POST /api/v1/auth/register |
| 400 | `—` | Last name is required | RegisterRequest.lname blank | POST /api/v1/auth/register |
| 400 | `—` | Last name must be at most 80 characters | RegisterRequest.lname > 80 chars | POST /api/v1/auth/register |
| 400 | `—` | Must be a valid email address | RegisterRequest.email fails @Email | POST /api/v1/auth/register |
| 400 | `—` | New password is required | ChangePasswordRequest.newPassword blank | POST /api/v1/auth/change-password |
| 400 | `—` | New password must be between 8 and 128 characters | ChangePasswordRequest.newPassword size out of 8..128 | POST /api/v1/auth/change-password |
| 400 | `—` | Password is required | LoginRequest.password or RegisterRequest.password blank | POST /api/v1/auth/login, POST /api/v1/auth/register |
| 400 | `—` | Password must be between 8 and 128 characters | RegisterRequest.password size out of 8..128 | POST /api/v1/auth/register |
| 400 | `—` | Username is required | RegisterRequest.username blank | POST /api/v1/auth/register |
| 400 | `—` | Username must be between 3 and 50 characters | RegisterRequest.username size out of 3..50 | POST /api/v1/auth/register |
| 400 | `—` | Username or email is required | LoginRequest.username blank | POST /api/v1/auth/login |

### 1.2 `user/auth (AuthServiceImpl)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_CURRENT_PASSWORD_INVALID` | Current password is incorrect. | currentPassword fails re-verification | POST /api/v1/auth/change-password |
| 400 | `AUTH_NEW_PASSWORD_SAME_AS_CURRENT` | New password must be different from the current password. | newPassword equals current password | POST /api/v1/auth/change-password |
| 401 | `AUTH_REFRESH_TOKEN_EXPIRED` | Refresh token has expired. Please log in again. | stored refresh token row past its expiry | POST /api/v1/auth/refresh |
| 401 | `AUTH_REFRESH_TOKEN_INVALID` | Refresh token is invalid or has expired. Please log in again. | JWT signature/expiry validation fails on the presented refresh token | POST /api/v1/auth/refresh |
| 401 | `AUTH_REFRESH_TOKEN_MISSING` | No refresh token provided. Include it in the request body or as a cookie. | refresh called with no token in body or cookie | POST /api/v1/auth/refresh |
| 401 | `AUTH_REFRESH_TOKEN_NOT_FOUND` | Refresh token not recognised. It may have been revoked. | token passes JWT validation but has no row in refresh_tokens | POST /api/v1/auth/refresh |
| 401 | `AUTH_REFRESH_TOKEN_REUSED` | This refresh token has been revoked. All sessions terminated for security. Please log in again. | reuse of an already-rotated/revoked refresh token (reuse detection revokes ALL sessions) | POST /api/v1/auth/refresh |
| 401 | `AUTH_REQUIRED` | Not authenticated. | change-password with no authenticated principal | POST /api/v1/auth/change-password |
| 401 | `AUTH_UNAUTHORIZED` | Not authenticated | logout-all with no authenticated principal | POST /api/v1/auth/logout-all |
| 401 | `AUTH_WRONG_TOKEN_TYPE` | The provided token is not a refresh token. | an access token (or other type) was sent to refresh; details map carries providedType/expectedType=REFRESH | POST /api/v1/auth/refresh |
| 401 | `USER_NOT_FOUND` | User no longer exists. | authenticated user id not found in DB during change-password | POST /api/v1/auth/change-password |

### 1.3 `user/auth (UserProvisioningService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 409 | `USER_DUPLICATE` | User already exists with email: {email} | registration/admin-create/invite-accept with an email already in use | POST /api/v1/auth/register, POST /api/v1/admin/users, POST /api/v1/admin/users/invite, POST /api/v1/auth/invites/accept |
| 409 | `USER_DUPLICATE` | User already exists with username: {username} | registration/admin-create/invite-accept with a username already taken | POST /api/v1/auth/register, POST /api/v1/admin/users, POST /api/v1/auth/invites/accept |

### 1.4 `user/auth (login, via common GlobalExceptionHandler)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_ACCOUNT_DISABLED` | Your account is disabled. Please verify your email or contact support. | DisabledException on login (account disabled/unverified) | POST /api/v1/auth/login |
| 401 | `AUTH_ACCOUNT_EXPIRED` | Your account has expired. Please contact support. | AccountExpiredException on login | POST /api/v1/auth/login |
| 401 | `AUTH_ACCOUNT_LOCKED` | Your account is locked. Please contact support. | LockedException on login | POST /api/v1/auth/login |
| 401 | `AUTH_BAD_CREDENTIALS` | Invalid email or password. | BadCredentialsException from AuthenticationManager (wrong password / unknown identifier — CustomUserDetailsService's internal 'No user found with email\|username: {identifier}' is masked to this) | POST /api/v1/auth/login |
| 401 | `AUTH_CREDENTIALS_EXPIRED` | Your credentials have expired. Please reset your password. | CredentialsExpiredException on login | POST /api/v1/auth/login |
| 401 | `AUTH_FAILED` | Authentication failed: {exceptionMessage} | any other Spring AuthenticationException | POST /api/v1/auth/login |
| 401 | `AUTH_INSUFFICIENT` | Full authentication is required to access this resource. | InsufficientAuthenticationException | any secured endpoint |

### 1.5 `user (UpdateProfileRequest)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | Username may only contain letters, digits, dots, hyphens, and underscores | @Pattern ^[a-zA-Z0-9._-]+$ fails on username | PATCH /api/v1/users/me |

### 1.6 `user (UserContactController)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | Authentication required | no authenticated user on contact-hash sync/clear | POST /api/v1/users/contacts/sync, DELETE /api/v1/users/contacts |

### 1.7 `user (UserProfileServiceImpl image validation)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `EMPTY_FILE` | Image file is required. | missing/empty multipart image | POST /api/v1/users/me/profile/avatar, POST /me/profile/cover |

### 1.8 `user (UserProfileServiceImpl)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_LANGUAGE` | Invalid language code: {contentLanguage} | unknown ISO language code in profile patch | PATCH /api/v1/users/me/profile |
| 404 | `MADHHAB_NOT_FOUND` | Madhhab not found with id: {madhhabId} | profile patch references unknown madhhab id | PATCH /api/v1/users/me/profile |
| 404 | `TOPIC_NOT_FOUND` | Topic not found with id: {topicId} | specialization update references unknown topic id | PATCH /api/v1/users/me/profile/specializations |

### 1.9 `user (UserServiceImpl / UserProfileServiceImpl)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `USERPROFILE_NOT_FOUND` | UserProfile not found with userId: {userId} | profile row missing for the user | PATCH /api/v1/users/me, /me/profile, links/contacts endpoints |

### 1.10 `user (UserServiceImpl image validation)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `EMPTY_FILE` | Profile image file is required and cannot be empty. | missing/empty multipart image (UserServiceImpl path) | profile-image upload via UserService |

### 1.11 `user (UserServiceImpl)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 409 | `CONTACT_DUPLICATE` | Contact already exists with platform+value: {platform}:{value} | adding a duplicate contact (same platform+value) | POST /api/v1/users/me/contacts |
| 404 | `CONTACT_NOT_FOUND` | Contact not found with id: {contactId} | edit/delete of a contact id not on the caller's profile | PATCH/DELETE /api/v1/users/me/contacts/{contactId} |
| 409 | `LINK_DUPLICATE` | Link already exists with url: {url} | adding a link whose URL already exists on the profile | POST /api/v1/users/me/links |
| 404 | `LINK_NOT_FOUND` | Link not found with id: {linkId} | edit/delete of a link id not on the caller's profile | PATCH/DELETE /api/v1/users/me/links/{linkId} |
| 409 | `USER_DUPLICATE` | User already exists with username: {username} | self-service username change to a taken handle | PATCH /api/v1/users/me |
| 404 | `USER_NOT_FOUND` | User not found with email: {email} | lookup by email finds nothing | GET /api/v1/users/email/{email} |
| 404 | `USER_NOT_FOUND` | User not found with username: {identifier} | lookup by username finds nothing | GET /api/v1/users/username/{username} |

### 1.12 `user (UserServiceImpl/UserProfileServiceImpl/UserSocialServiceImpl)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | You must be authenticated to perform this action. | no principal resolvable inside user/profile/social service calls | authenticated /api/v1/users/** endpoints |

### 1.13 `user (both image validators)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_FILENAME` | Invalid file name. | filename contains .. / or \ (path traversal guard) | POST /api/v1/users/me/profile/avatar, /me/profile/cover |
| 400 | `INVALID_FILE_TYPE` | Invalid image type. Allowed: jpeg, png, webp, gif. | content type not in image/jpeg\|png\|webp\|gif | POST /api/v1/users/me/profile/avatar, /me/profile/cover |
| 400 | `MISSING_FILENAME` | File name is required. | multipart original filename null/blank | POST /api/v1/users/me/profile/avatar, /me/profile/cover |

### 1.14 `user (dto validation, framework defaults)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | size must be between … (defaults) — UpdateProfileRequest: fname/lname ≤80, username 3..50; UpdateUserProfileRequest: displayName ≤120, selfDescriber ≤200, location ≤200, academicTitle ≤150, institutionName ≤200; AddLinkRequest: platform @NotNull, description @NotBlank ≤200, url @NotBlank; EditLinkRequest: description ≤200; AddContactRequest: platform @NotNull, value @NotBlank ≤200; EditContactRequest: value ≤200; UpdateSpecializationsRequest: specializations @NotNull, topicId @NotNull; VerificationReviewRequest: reviewerNote ≤1000 | bean validation on user-facing profile/link/contact DTOs | PATCH /api/v1/users/me, PATCH /me/profile, POST/PATCH /me/links, POST/PATCH /me/contacts, PATCH /me/profile/specializations |

### 1.15 `user (multiple services)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `USER_NOT_FOUND` | User not found with id: {id} | any active-user lookup by id fails (profile, social, close friends, notifications, admin, safety score, deletion, 2FA, step-up, phone bind) | GET /api/v1/users/{id} and all endpoints that resolve a user id |

### 1.16 `user/admin (AdminChangeRoleRequest)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | reason must not exceed 500 characters | @Size(max=500) reason too long | PATCH /api/v1/admin/users/{userId}/role |
| 400 | `—` | role is required | @NotNull role missing on role-change body | PATCH /api/v1/admin/users/{userId}/role |

### 1.17 `user/admin (AdminUserDtos, framework defaults)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | framework defaults (must not be blank / size must be between …) — AdminCreateUserRequest: fname/lname @NotBlank ≤80, username @NotBlank 3..50, email @NotBlank @Email ≤255, temporaryPassword 8..128; AdminBulkCreateRequest: 1..1000 items; AdminInviteRequest: email @NotBlank @Email; AcceptInviteRequest: token @NotBlank, fname/lname @NotBlank ≤80, username @NotBlank 3..50, password @NotBlank 8..128; AdminEditUserRequest: fname/lname ≤80, username 3..50, email @Email ≤255; AdminPasswordResetRequest: temporaryPassword 8..128; AdminReasonRequest: reason ≤500; AdminStrikeRequest: reason @NotBlank ≤200; AdminBulkActionRequest: ids @NotNull 1..100, action @NotBlank, reason ≤500 | bean validation on admin DTOs | POST /api/v1/admin/users, /bulk, /invite, /bulk-action, /strikes, PATCH /{userId}, POST /api/v1/auth/invites/accept |

### 1.18 `user/admin (AdminUserServiceImpl)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_BULK_ACTION` | Unknown action. Allowed: DISABLE, ENABLE, LOCK, UNLOCK, REQUEST_DELETION, CHANGE_ROLE. | unrecognized bulk action name | POST /api/v1/admin/users/bulk-action |
| 400 | `INVALID_INPUT` | role is required | service-level guard when role null on role change | PATCH /api/v1/admin/users/{userId}/role |
| 400 | `INVALID_INPUT` | role is required for CHANGE_ROLE | bulk action CHANGE_ROLE without a role | POST /api/v1/admin/users/bulk-action |
| 400 | `INVALID_STATUS_FILTER` | Unknown status filter. Allowed: ACTIVE, DISABLED, DELETED, LOCKED. | bad status filter on the analytics/counts path | GET /api/v1/admin/users/analytics |
| 400 | `INVALID_STATUS_FILTER` | Unknown status filter. Allowed: ACTIVE, DISABLED, DELETED. | bad ?status= filter on user list | GET /api/v1/admin/users |
| 400 | `INVITE_INVALID` | Invite is invalid, revoked, used, or expired. | accept-invite token unusable | POST /api/v1/auth/invites/accept |
| 409 | `INVITE_USED` | Invite already used. | resending an invite that was already consumed | POST /api/v1/admin/users/invite/{inviteId}/resend |
| 409 | `LAST_ADMIN` | Cannot demote the last ADMIN. | role change would leave zero admins | PATCH /api/v1/admin/users/{userId}/role |
| 400 | `PASSWORD_OR_INVITE_REQUIRED` | Provide temporaryPassword or set sendInvite=true — otherwise the account has no way to log in. | admin create with neither temp password nor invite | POST /api/v1/admin/users (also /bulk rows) |
| 403 | `SELF_ACTION_FORBIDDEN` | You cannot {what}. — where {what} ∈ change your own role \| disable yourself \| lock yourself \| admin-reset your own password (use change-password) \| admin-reset your own 2FA (use security settings) \| delete yourself via the admin surface \| purge yourself \| strike yourself | admin targets their own account with a destructive admin action | PATCH /role, POST /disable, /lock, /password/reset, /2fa/reset, /deletion/request, /purge/now, /strikes under /api/v1/admin/users/{userId} |
| 404 | `SESSION_NOT_FOUND` | Session not found with sid: {sid} | revoking a session sid that doesn't exist for that user | DELETE /api/v1/admin/users/{userId}/sessions/{sid} |
| 404 | `USERINVITE_NOT_FOUND` | UserInvite not found with id: {inviteId} | resend/revoke of unknown invite | POST /api/v1/admin/users/invite/{inviteId}/resend, DELETE /api/v1/admin/users/invite/{inviteId} |
| 409 | `USER_DUPLICATE` | User already exists with username: {username} / User already exists with email: {email} | admin edit changes username/email to a taken value | PATCH /api/v1/admin/users/{userId} |
| 404 | `USER_NOT_FOUND` | User not found with id: {userId} | any admin operation on unknown user id | /api/v1/admin/users/{userId}/** |

### 1.19 `user/close-friends (CloseFriendsServiceImpl)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | You must be authenticated. | no principal on close-friends ops | /api/v1/users/me/close-friends/** |
| 409 | `RESOURCE_DUPLICATE` | User is already a close friend. | duplicate close-friend add | POST /api/v1/users/me/close-friends/{userId} |
| 404 | `RESOURCE_NOT_FOUND` | Close friend not found. | removing a user who isn't in the close-friends list | DELETE /api/v1/users/me/close-friends/{userId} |
| 400 | `SELF_ACTION` | Cannot add yourself to close friends. | adding self as close friend | POST /api/v1/users/me/close-friends/{userId} |

### 1.20 `user/notifications (NotificationServiceImpl)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | You must be authenticated to access notifications. | no principal on notification listing/mutation | /api/v1/notifications/** |
| 404 | `NOTIFICATION_NOT_FOUND` | Notification not found with id: {notificationId} | mark-read/delete of a notification not owned by the caller or nonexistent | PATCH /api/v1/notifications/{id}/read, DELETE /api/v1/notifications/{id} |

### 1.21 `user/social (UserSocialServiceImpl)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `FOLLOW_BLOCKED_RELATIONSHIP` | Cannot follow this user due to an existing block relationship. | either side blocks the other on follow attempt | POST /api/v1/users/{id}/follow |
| 403 | `FOLLOW_PROFILE_LOCKED` | This user's profile is locked and cannot be followed. | target profile is locked | POST /api/v1/users/{id}/follow |
| 409 | `RESOURCE_DUPLICATE` | You are already blocking this user. | block when block edge already exists | POST /api/v1/users/{id}/block, POST /api/v1/blocks/{userId} |
| 409 | `RESOURCE_DUPLICATE` | You are already restricting this user. | restrict when restriction already exists | POST /api/v1/users/{id}/restrict |
| 404 | `RESOURCE_NOT_FOUND` | You are not following this user. Cannot unfollow. | unfollow with no existing follow edge | DELETE /api/v1/users/{id}/follow |
| 404 | `RESOURCE_NOT_FOUND` | You are not restricting this user. Cannot unrestrict. | unrestrict with no existing restriction | DELETE /api/v1/users/{id}/restrict |
| 404 | `RESOURCE_NOT_FOUND` | You have not blocked this user. Cannot unblock. | unblock with no existing block edge | DELETE /api/v1/users/{id}/block, DELETE /api/v1/blocks/{userId} |
| 400 | `RESTRICT_ALREADY_BLOCKED` | This user is already blocked. A restriction is unnecessary. | restrict attempted on an already-blocked user | POST /api/v1/users/{id}/restrict |
| 400 | `SELF_ACTION_NOT_ALLOWED` | You cannot {follow\|block\|restrict} yourself. | target id equals the caller's own id | POST /api/v1/users/{id}/follow, /block, /restrict |

### 1.22 `security (SecurityUtils.requireCurrentUserId)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_REQUIRED` | Authentication is required for this endpoint. | no usable principal on endpoints using requireCurrentUserId (esp. SSE endpoints reached without credentials) | SSE and other endpoints resolving the caller via SecurityUtils |

### 1.23 `security/dto validation`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | must not be blank / must not be null (framework defaults — no custom messages) | SecurityDtos.CodeRequest.code, PhoneRequest.phone, PhoneVerifyRequest.phone+code, OtpDtos.RequestOtpRequest.phone, VerifyOtpRequest.phone+code blank | POST /api/v1/security/2fa/verify, /2fa/disable, /phone/request, /phone/verify, POST /api/v1/auth/otp/request, /otp/verify |

### 1.24 `security/jwt (JwtAccessDeniedHandler)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_DENIED` | You do not have the required permissions to access this resource. | authenticated user fails a role/@PreAuthorize check (e.g. non-admin on /api/v1/admin/**) | all secured endpoints (JSON ApiErrorResponse with traceId) |

### 1.25 `security/jwt (JwtAuthenticationEntryPoint)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_REQUIRED` | You must be authenticated to access this resource. Please log in or provide a valid token. | unauthenticated request hits any secured endpoint (missing/invalid Bearer token or cookie) | all secured endpoints (JSON ApiErrorResponse with traceId) |

### 1.26 `security/otp (OtpService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `OTP_INVALID` | Invalid or expired verification code. | no open challenge, challenge expired, max attempts reached, or code mismatch (single deliberately-vague message) | POST /api/v1/auth/otp/verify, POST /api/v1/security/phone/verify |

### 1.27 `security/otp (RateLimiter)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 429 | `RATE_LIMITED` | Too many otp:ip requests — please slow down | OTP resends per client IP exceed props.resendPerIpPerHour in 1h | POST /api/v1/auth/otp/request, POST /api/v1/security/phone/request |
| 429 | `RATE_LIMITED` | Too many otp:num requests — please slow down | OTP resends per phone number exceed props.resendPerNumberPerHour in 1h; details carry action + retryAfterSeconds | POST /api/v1/auth/otp/request, POST /api/v1/security/phone/request |

### 1.28 `security/phone (PhoneNormalizer)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `PHONE_INVALID` | Phone number has no digits. | phone input contains no digits | same phone/OTP endpoints |
| 400 | `PHONE_INVALID` | Phone number is not a valid E.164 length. | normalized number outside E.164 length bounds | same phone/OTP endpoints |
| 400 | `PHONE_REQUIRED` | Phone number is required. | null/blank phone input | POST /api/v1/security/phone/request, /phone/verify, POST /api/v1/auth/otp/request, /otp/verify |

### 1.29 `security/session (SessionService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `SESSION_NOT_FOUND` | Session not found with sid: {sid} | sid unknown OR belongs to another user (deliberately identical to avoid leaking others' sessions) | DELETE /api/v1/security/sessions/{sid}, POST /api/v1/security/sessions/{sid}/trust |

### 1.30 `security/stepup (StepUpService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `STEP_UP_BAD_PASSWORD` | Password is incorrect. | wrong password submitted to arm step-up | POST /api/v1/security/step-up |
| 403 | `STEP_UP_REQUIRED` | This action requires you to confirm your identity. | sensitive action attempted without a recent step-up confirmation | POST /api/v1/security/2fa/disable, POST /api/v1/security/recovery-codes/regenerate, @RequiresStepUp admin endpoints (e.g. POST /api/v1/admin/users) |

### 1.31 `security/twofa (TwoFactorService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 409 | `TWO_FA_ALREADY_ON` | Two-factor authentication is already enabled. | 2FA setup started while already enabled | POST /api/v1/security/2fa/setup |
| 400 | `TWO_FA_INVALID` | Invalid authentication code. | TOTP code fails verification during 2FA enrolment | POST /api/v1/security/2fa/verify |
| 400 | `TWO_FA_NOT_STARTED` | Start 2FA setup first. | verify called with no pending secret | POST /api/v1/security/2fa/verify |

### 1.32 `settings dto validation (framework defaults)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | must not be blank / must not be null (defaults) — PrivacyDtos: SetVisibilityRequest.visibility, CreateListRequest.name, AddKeywordRequest.keyword @NotBlank, AddMemberRequest.memberId, MuteRequest.userId @NotNull; NotificationSettingsDtos: RegisterPushTokenRequest.provider+token @NotBlank; SafetyDtos: SubmitReportRequest.targetType+reason @NotNull; ConsentDtos: RecordConsentRequest.scope @NotBlank | bean validation on settings DTOs | PUT /settings/privacy/{field}, POST /settings/privacy/lists\|members\|keywords\|muted, POST /settings/notifications/push-tokens, POST /safety/reports, POST /settings/consent |

### 1.33 `settings/contacts (ContactsController + RateLimiter)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 429 | `RATE_LIMITED` | Too many contact:sync requests — please slow down | more than 3 contact syncs per 24h; details carry retryAfterSeconds | POST /api/v1/contacts/sync |

### 1.34 `settings/core (SettingsService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_REQUEST` | Invalid settings body for {SectionClass}: {jacksonMessage} | full-replace body fails to bind | PUT /api/v1/settings/appearance\|accessibility\|messages\|media |
| 400 | `BAD_REQUEST` | Invalid settings patch for {SectionClass}: {jacksonMessage} | merge-patch body fails to bind to the section type | PATCH /api/v1/settings/{section} |
| 400 | `BAD_REQUEST` | Unknown or non-cosmetic settings section: {section} | section path variable not one of the cosmetic sections | GET/PATCH /api/v1/settings/{section} |

### 1.35 `settings/data (AccountLifecycleService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 409 | `DELETION_PENDING` | Deletion already requested. | second deletion request while one is pending | POST /api/v1/account/deletion/request (and admin POST /api/v1/admin/users/{userId}/deletion/request) |
| 404 | `RESOURCE_NOT_FOUND` | No pending deletion to cancel. | cancel with no pending deletion request | POST /api/v1/account/deletion/cancel (and admin /deletion/cancel) |
| 404 | `RESOURCE_NOT_FOUND` | No pending deletion to hold. | admin purge-hold with no pending deletion | POST /api/v1/admin/users/{userId}/purge/hold |
| 404 | `RESOURCE_NOT_FOUND` | No pending deletion to purge. | admin purge-now with no pending deletion | POST /api/v1/admin/users/{userId}/purge/now |

### 1.36 `settings/data (DataExportService + RateLimiter)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 429 | `RATE_LIMITED` | Too many privacy:export requests — please slow down | more than 1 export request per 30 days | POST /api/v1/privacy/export |

### 1.37 `settings/data (DataExportService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `EXPORTJOB_NOT_FOUND` | ExportJob not found with id: {jobId} | unknown export job id | GET /api/v1/privacy/export/{jobId}, /download |
| 403 | `NOT_EXPORT_OWNER` | Not your export job. | job belongs to another user | GET /api/v1/privacy/export/{jobId}, /download |
| 404 | `RESOURCE_NOT_FOUND` | Export file is no longer available. | export file missing on disk | GET /api/v1/privacy/export/{jobId}/download |
| 404 | `RESOURCE_NOT_FOUND` | Export not ready or expired. | download attempted before READY or after expiry | GET /api/v1/privacy/export/{jobId}/download |

### 1.38 `settings/data (HistoryService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_REQUEST` | Unknown history type: {type} (expected search\|watch) | unsupported history type in path | DELETE /api/v1/privacy/history/{type} |

### 1.39 `settings/discovery (QrDiscoveryController)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `RESOURCE_NOT_FOUND` | User not found for this QR code. | QR token resolves but its owner no longer exists/active | GET /api/v1/discovery/qr/resolve/{opaque} |

### 1.40 `settings/discovery (QrTokenService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `RESOURCE_NOT_FOUND` | QR code is invalid or has been rotated. | opaque QR token unknown or superseded by rotation | GET /api/v1/discovery/qr/resolve/{opaque} |

### 1.41 `settings/notification (NotificationSettingsService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_CHANNEL` | Unknown notification channel: {channel} | path channel not a known NotificationChannel | PUT /api/v1/settings/notifications/{eventType}/{channel} |
| 400 | `BAD_EVENT_TYPE` | Unknown notification event type: {eventType} | path eventType not a known NotificationType | PUT /api/v1/settings/notifications/{eventType}/{channel} |
| 400 | `BAD_TIME` | Time must be HH:mm — got: {value} | DND start/end time not HH:mm | PUT /api/v1/settings/notifications/dnd |
| 400 | `BAD_TIMEZONE` | Invalid IANA timezone: {timezone} | unparseable ZoneId in DND update | PUT /api/v1/settings/notifications/dnd |

### 1.42 `settings/policy (PolicyService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `POLICY_NOT_FOUND` | Policy not found with key: {key} | unknown policy key | GET /api/v1/app/policies/{key}, POST /api/v1/app/policies/{key}/accept |

### 1.43 `settings/privacy (HiddenKeywordService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_REQUEST` | Hidden-keyword limit reached (200). | user already has 200 hidden keywords (MAX_KEYWORDS=200) | POST /api/v1/settings/privacy/keywords |
| 400 | `BAD_REQUEST` | Keyword must not be blank. | blank keyword submitted | POST /api/v1/settings/privacy/keywords |
| 400 | `BAD_REQUEST` | Keyword normalizes to empty. | keyword is only punctuation/symbols after normalization | POST /api/v1/settings/privacy/keywords |

### 1.44 `settings/privacy (PrivacyListService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `NOT_LIST_OWNER` | You do not own this list. | list belongs to another user | DELETE /lists/{id}, GET/POST/DELETE /lists/{id}/members under /api/v1/settings/privacy |
| 404 | `PRIVACYLIST_NOT_FOUND` | PrivacyList not found with id: {listId} | list id unknown | /api/v1/settings/privacy/lists/{id}(/members…) |

### 1.45 `settings/privacy (PrivacyService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_REQUEST` | Unknown privacy field: {field} | unrecognized field key in path | PUT /api/v1/settings/privacy/{field} |
| 400 | `BAD_REQUEST` | Unknown visibility level: {visibility} | unrecognized visibility value in body | PUT /api/v1/settings/privacy/{field} |

### 1.46 `settings/safety (ReportService + RateLimiter)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 429 | `RATE_LIMITED` | Too many safety:report requests — please slow down | more than 20 reports per hour | POST /api/v1/safety/reports |

### 1.47 `settings/safety (ReportService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `NOT_REPORTER` | You can only appeal your own reports. | appeal by someone other than the reporter | POST /api/v1/safety/reports/{id}/appeal |
| 409 | `REPORT_NOT_APPEALABLE` | This report cannot be appealed in its current state. | report state is not ACTIONED or DISMISSED | POST /api/v1/safety/reports/{id}/appeal |
| 404 | `REPORT_NOT_FOUND` | Report not found with id: {reportId} | appeal on unknown report id | POST /api/v1/safety/reports/{id}/appeal |
| 400 | `TARGET_REQUIRED` | targetId (or targetRef for MESSAGE targets) is required. | report submitted with neither targetId nor targetRef | POST /api/v1/safety/reports |

### 1.48 `settings/safety (SecurityScoreService)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `USER_NOT_FOUND` | User not found with id: {userId} | score computed for unknown user | GET /api/v1/safety/score |

### 1.49 `post`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_FORBIDDEN` | You do not have permission to perform this action | editing a post you don't own (SecurityException remapped) or deleting a post whose authorId != caller | PATCH /api/v1/posts/{id}, DELETE /api/v1/posts/{id} |
| 403 | `ACCESS_FORBIDDEN` | order list is required | PATCH highlight reorder with null/empty order list (note: thrown as ForbiddenException though semantically a 400) | PATCH /api/v1/highlights/order |
| 403 | `ACCESS_FORBIDDEN` | Only the poll's author can list voters | requesting a poll's voter list as a non-author | poll voters endpoint under /api/v1/posts/** |
| 403 | `NOT_OWNER` | You can only read your own mentions. | reading another user's mentions feed | GET /api/v1/mentions/me (userId mismatch) |
| 403 | `FORBIDDEN` | Not the author | editing/deleting a comment you didn't write (CassandraCommentService:175,236) or mutating a post you don't own (CassandraPostService:211) | comment edit/delete + post mutation endpoints under /api/v1/posts/** |
| 400 | `ILLEGAL_ARGUMENT` | Comment not found: {commentId} | replying to / operating on a nonexistent comment (CassandraCommentService:125,277) — surfaces as 400 not 404 | POST /api/v1/posts/{id}/comments (reply path) and comment lookups |
| 404 | `POST_NOT_FOUND` | Post not found with id: {postId} | share/media op against missing post (CassandraShareService:103,115; CassandraMediaService:41) | POST /api/v1/posts/{id}/share, /api/v1/posts/{postId}/media |
| 500 | `post_create_failed` | {"error":"post_create_failed","message":"<exception message>","rolledBackFiles":N} | DB persist fails after successful uploads; R2 keys rolled back | POST /api/v1/posts (multipart) — ad-hoc Map body (CassandraFeedController:204) |
| 502 | `upload_failed` | {"error":"upload_failed","message":"<exception message>"} | R2 upload of any multipart file fails during multipart post create; uploaded keys are rolled back | POST /api/v1/posts (multipart) — ad-hoc Map body, not the ApiErrorResponse envelope (CassandraFeedController:173) |

### 1.50 `post (highlights)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `FORBIDDEN` | Not the highlight owner | modifying (add/remove story) or deleting a highlight you don't own (CassandraHighlightService:143,184) | /api/v1/highlights/{highlightId}/** |
| 403 | `FORBIDDEN` | Not the story author | adding someone else's story to your highlight (CassandraHighlightService:136) or attaching a poll to someone else's story (CassandraStoryPollService:71) | POST /api/v1/highlights/{highlightId}/stories/{storyId}; story poll create |

### 1.51 `post (media)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `FORBIDDEN` | Not the post author | media mutation on a post you don't own (CassandraMediaService:43) | /api/v1/posts/{postId}/media |

### 1.52 `post (sounds)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `SOUND_NOT_FOUND` | Sound not found with id: {soundId} | sound id missing (CassandraSoundService:291) | /api/v1/sounds/** |

### 1.53 `post (stories)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `FORBIDDEN` | Only channel admins can view the viewer log | viewer-log request on a channel story by non-staff | GET story viewers endpoint (CassandraStoryService:373) |
| 403 | `FORBIDDEN` | Only the author can delete this story | DELETE story by non-author | DELETE /api/v1/stories/{id} |
| 403 | `FORBIDDEN` | Only the story author can view the viewer log | viewer-log request on personal story by non-author | GET story viewers endpoint (CassandraStoryService:377) |
| 404 | `STORY_NOT_FOUND` | Story not found with id: {storyId} | story id does not exist on delete/view | /api/v1/stories/** (CassandraStoryService:177) |

### 1.54 `post (story polls)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `ILLEGAL_ARGUMENT` | Choice must be A or B | story poll vote with a choice other than A/B (CassandraStoryPollService:173) | story poll vote endpoint |

### 1.55 `post+chat+activity (all controllers)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | Authentication required | @AuthenticationPrincipal User is null on any authenticated handler (requireId/user==null guard). Appears in CassandraFeedController (~20 sites), CassandraStoryController, CassandraHighlightController, CassandraMediaController, MessageController, ConversationController, GroupMemberController, ChannelController, StreamStageController, CallController, LiveStreamController, MessageRequestController, MessagingStreamController(starred), ReelViewController (4 sites), UserActivityController (3 sites) | virtually every authenticated REST endpoint under /api/v1/posts/**, /api/v1/stories/**, /api/v1/highlights/**, /api/v1/conversations/**, /api/v1/channels/**, /api/v1/streams/**, /api/v1/calls/**, /api/v1/message-requests/**, /api/v1/users/me/activity/**, /api/v1/users/me/reels/** |

### 1.56 `post/chat/activity SSE`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | Authentication required. Pass access token as ?token=<jwt>. | SSE subscribe with no session and missing/invalid ?token= ACCESS JWT | GET /api/v1/stories/{storyId}/stream (CassandraStoryController:173), GET /api/v1/stories/tray/stream (CassandraStoryTrayController:88), GET /api/v1/users/me/activity/stream (UserActivityController:148). Same text on GET /api/v1/messaging/stream (MessagingStreamController:69) but delivered as raw text/plain 401 body, NOT the JSON envelope. Note: GET /api/v1/posts/{id}/stream allows anonymous viewers (no error) |

### 1.57 `research`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | Caption must not exceed 500 characters \| Alt text must not exceed 300 characters (upload metadata) / 255 characters (media update) | bean validation on MediaUploadMetadata / UpdateMediaRequest | POST /{id}/media, PATCH /{id}/media/{mediaId} |
| 400 | `—` | Comment content is required \| Comment must not exceed 5 000 characters | bean validation on AddCommentRequest / EditCommentRequest | POST/PATCH comment endpoints |
| 400 | `—` | Source type is required \| Source title is required | bean validation on SourceRequest | POST /api/v1/researches (sources), PATCH /{id}/sources/{sourceId} |
| 400 | `—` | Title is required \| Title must not exceed 500 characters \| Description is required \| Description must not exceed 50 000 characters \| Abstract is required \| Abstract must not exceed 5 000 characters \| Keywords must not exceed 2 000 characters \| Citation must not exceed 5 000 characters \| At least one tag is required \| Maximum 30 tags allowed | bean validation on CreateResearchRequest | POST /api/v1/researches |
| 400 | `—` | userId is required \| contributionNote must not exceed 500 characters | bean validation on ContributorRequest / UpdateContributorRequest | contributor endpoints |
| 403 | `ACCESS_FORBIDDEN` | Comment does not belong to this research | commentId not under the researchId in path | PATCH/DELETE/hide/unhide/reactions on /comments/{commentId} |
| 403 | `ACCESS_FORBIDDEN` | Media does not belong to this research | mediaId under a different research (update/delete/download) | PATCH/DELETE /{id}/media/{mediaId}, POST /{researchId}/download |
| 403 | `ACCESS_FORBIDDEN` | Only researchers can manage researches | caller role is not RESEARCHER/SCHOLAR/ADMIN | all authoring endpoints under /api/v1/researches |
| 403 | `ACCESS_FORBIDDEN` | You can only delete your own comments or comments on your research | delete by neither comment author nor research owner | DELETE /comments/{commentId} |
| 403 | `ACCESS_FORBIDDEN` | You can only edit your own comments | editing another user's comment | PATCH /api/v1/researches/{researchId}/comments/{commentId} |
| 403 | `ACCESS_FORBIDDEN` | You can only hide your own comments or comments on your research | hide by non-author non-owner | POST /comments/{commentId}/hide |
| 403 | `ACCESS_FORBIDDEN` | You can only unhide your own comments or comments on your research | unhide by non-author non-owner | POST /comments/{commentId}/unhide |
| 403 | `ACCESS_FORBIDDEN` | You do not own this research | authoring action by non-owner | all owner-gated endpoints under /api/v1/researches/{id} |
| 400 | `ALREADY_ARCHIVED` | Research is already archived | archive on an archived research | POST /api/v1/researches/{id}/archive |
| 400 | `ALREADY_DELETED` | Comment is already deleted | double delete | DELETE /comments/{commentId} |
| 400 | `ALREADY_PUBLISHED` | Research is already published | publish on a published research | POST /api/v1/researches/{id}/publish |
| 400 | `COMMENTS_DISABLED` | Comments are disabled for this research | add comment while commentsEnabled=false | POST /api/v1/researches/{researchId}/comments |
| 400 | `COMMENT_DATA_ERROR` | Invalid comment data | DataIntegrityViolation saving comment | POST /api/v1/researches/{researchId}/comments |
| 400 | `COMMENT_DELETED` | Cannot edit a deleted comment | edit on soft-deleted comment | PATCH /comments/{commentId} |
| 400 | `COMMENT_DELETED` | Cannot hide a deleted comment | hide on soft-deleted comment | POST /comments/{commentId}/hide |
| 400 | `COMMENT_DELETED` | Cannot react to a deleted comment | react on soft-deleted comment | POST /comments/{commentId}/reactions |
| 400 | `COMMENT_TOO_LONG` | Comment exceeds maximum length of 5000 characters | comment content > 5000 chars (add and edit) | POST/PATCH comments under /api/v1/researches/{researchId} |
| 400 | `CONSTRAINT_VIOLATION` | Update violates data constraints | non-slug DataIntegrityViolation on update | PATCH /api/v1/researches/{id} |
| 400 | `CONTRIBUTOR_DELETED` | Contributor account is deactivated | contributor user is soft-deleted | contributor endpoints under /api/v1/researches/{id} |
| 400 | `CONTRIBUTOR_IS_OWNER` | The corresponding researcher cannot also be listed as a contributor | create/replace contributors list contains the owner | POST /api/v1/researches, PUT /{id}/contributors |
| 400 | `CONTRIBUTOR_IS_OWNER` | The corresponding researcher cannot be added as a contributor | addContributor with owner's userId | POST /api/v1/researches/{id}/contributors |
| 400 | `CONTRIBUTOR_NOT_ELIGIBLE` | Contributors must be a researcher or scholar (user {userId}) | contributor role not RESEARCHER/SCHOLAR/ADMIN | contributor endpoints under /api/v1/researches/{id} |
| 404 | `CONTRIBUTOR_NOT_FOUND` | Contributor not found with id: {contributorId} | unknown contributor id | PATCH/DELETE /{id}/contributors/{contributorId} |
| 400 | `CONTRIBUTOR_RESEARCH_MISMATCH` | Contributor does not belong to this research | contributorId under a different research (update and remove) | PATCH/DELETE /{id}/contributors/{contributorId} |
| 500 | `COVER_UPLOAD_ERROR` | Failed to upload cover image | cover image upload failure | POST /api/v1/researches/{id}/cover-image |
| 500 | `DB_ERROR` | Failed to create research due to a database error | DataAccessException during create (S3 uploads rolled back) | POST /api/v1/researches |
| 400 | `DOWNLOADS_DISABLED` | Downloads are disabled for this research | download while downloadsEnabled=false | POST /api/v1/researches/{researchId}/download |
| 400 | `DUPLICATE_CONTRIBUTOR` | Duplicate contributor in request: {userId} | same userId twice in a contributors list | POST /api/v1/researches, PUT /{id}/contributors |
| 400 | `EMPTY_COMMENT` | Comment content cannot be empty | edit comment with blank content | PATCH /api/v1/researches/{researchId}/comments/{commentId} |
| 400 | `EMPTY_COMMENT` | Comment must have text, media, or voice content | comment with no content at all | POST /api/v1/researches/{researchId}/comments[/upload] |
| 400 | `EMPTY_FILE` | File cannot be empty (0 bytes) | zero-byte upload | research file-upload endpoints |
| 400 | `EMPTY_FILE` | {fileType} file is required and cannot be empty | missing/empty multipart file; fileType placeholder is one of: media file, video, thumbnail, image, file, document | all research file-upload endpoints (media, video-promo, cover-image, sources file) |
| 400 | `FILE_NOT_AVAILABLE` | Media file not available for download | media row has no S3 key | POST /api/v1/researches/{researchId}/download |
| 400 | `FILE_TOO_LARGE` | File size exceeds maximum allowed limit | MaxUploadSizeExceededException during S3 upload | research file-upload endpoints |
| 400 | `INVALID_FILENAME` | Invalid file name | filename contains .. / or \ (path traversal guard) | research file-upload endpoints |
| 400 | `INVALID_FILE_TYPE` | Invalid file type. Allowed: {comma-separated list} | content-type not in whitelist; details carry receivedType+allowedTypes. Whitelists: video-promo=video/mp4,video/webm,video/quicktime; thumbnail=image/jpeg,image/png,image/webp; cover=image/jpeg,image/png,image/webp,image/gif; source doc=application/pdf,application/msword,docx,text/plain | POST /{id}/video-promo, /{id}/cover-image, /{id}/sources/{sourceId}/file |
| 400 | `INVALID_INPUT` | Comment ID and User ID are required | null ids on delete/hide/unhide/react comment | comment sub-endpoints under /api/v1/researches/{researchId} |
| 400 | `INVALID_INPUT` | Comment ID and request body are required | edit comment with null id/body | PATCH /api/v1/researches/{researchId}/comments/{commentId} |
| 400 | `INVALID_INPUT` | Contributor userId is required | addContributor with null userId | POST /api/v1/researches/{id}/contributors |
| 400 | `INVALID_INPUT` | Media ID and request body are required | media update with null id/body | PATCH /api/v1/researches/{id}/media/{mediaId} |
| 400 | `INVALID_INPUT` | Request body is required | updateContributor with null body | PATCH /api/v1/researches/{id}/contributors/{contributorId} |
| 400 | `INVALID_INPUT` | Research ID and User ID are required | null ids on reaction-remove/save/unsave | DELETE /reactions, POST /save, DELETE /save under /api/v1/researches/{researchId} |
| 400 | `INVALID_INPUT` | Research ID and comment data are required | null id/body on add comment | POST /api/v1/researches/{researchId}/comments |
| 400 | `INVALID_INPUT` | Research ID and request body are required | update with null id/body | PATCH /api/v1/researches/{id} |
| 400 | `INVALID_PARENT` | Parent comment does not belong to this research | reply with parentId from another research | POST /api/v1/researches/{researchId}/comments |
| 400 | `INVALID_QUERY` | Search query must be at least 2 characters | query shorter than 2 chars | GET /api/v1/researches/search/tags (both title and tag search paths) |
| 400 | `INVALID_REACTION` | Research ID is required | react with null researchId | POST /api/v1/researches/{researchId}/reactions |
| 400 | `INVALID_SCHEDULE` | Scheduled publish time must be in the future | scheduledPublishAt in the past | PATCH /api/v1/researches/{id} |
| 400 | `INVALID_TAGS` | Tags cannot be empty | tags normalize to empty set | GET /api/v1/researches/search/tags |
| 500 | `MEDIA_ADD_ERROR` | Failed to add media file | unexpected failure adding media | POST /api/v1/researches/{id}/media |
| 500 | `MEDIA_DELETE_ERROR` | Failed to remove media file | unexpected failure removing media | DELETE /api/v1/researches/{id}/media/{mediaId} |
| 400 | `MEDIA_METADATA_ERROR` | Invalid media metadata | bad metadata on add media | POST /api/v1/researches/{id}/media |
| 404 | `MEDIA_NOT_FOUND` | Media not found with id: {mediaId} | unknown media id | media endpoints under /api/v1/researches |
| 400 | `MISSING_ABSTRACT` | Cannot publish research without an abstract | publish with blank abstract | POST /api/v1/researches/{id}/publish |
| 400 | `MISSING_COLLECTION_NAME` | Collection name is required | blank collection name | GET /api/v1/researches/me/saved/collection |
| 400 | `MISSING_FILENAME` | File name is required | upload with blank original filename | research file-upload endpoints |
| 400 | `MISSING_MEDIA_ID` | Media ID is required | media delete with null id | DELETE /api/v1/researches/{id}/media/{mediaId} |
| 400 | `MISSING_MEDIA_ID` | mediaId is required — downloads are tracked per physical file (PDF/video/audio/zip). | download without mediaId | POST /api/v1/researches/{researchId}/download |
| 400 | `MISSING_NEW_NAME` | New collection name is required | rename with blank newName | PATCH /api/v1/researches/me/saved/collections |
| 400 | `MISSING_OLD_NAME` | Old collection name is required | rename with blank oldName | PATCH /api/v1/researches/me/saved/collections |
| 400 | `MISSING_RESEARCHER_ID` | Researcher ID is required | null researcher id on listing endpoints | GET /api/v1/researches/researcher/{researcherId}, /me/drafts, /me/all |
| 400 | `MISSING_RESEARCH_ID` | Research ID is required | null researchId on reads/counters/downloads/citations | multiple GET/POST endpoints under /api/v1/researches |
| 400 | `MISSING_SLUG` | Slug is required | blank slug path/param | GET /api/v1/researches/slug/{slug} |
| 400 | `MISSING_SOURCE_ID` | Source ID is required | null sourceId | PATCH/POST source endpoints |
| 400 | `MISSING_TAGS` | At least one tag is required | tag search with no tags | GET /api/v1/researches/search/tags |
| 400 | `MISSING_TITLE` | Cannot publish research without a title | publish with blank title | POST /api/v1/researches/{id}/publish |
| 400 | `MISSING_TITLE` | Title is required to generate slug | slug generation with blank title | POST /api/v1/researches (create) |
| 400 | `MISSING_TOKEN` | Share token is required | blank share token | GET /api/v1/researches/share/{shareToken} |
| 400 | `MISSING_USER_ID` | User ID is required | null userId on saved-list/collection endpoints | GET /me/saved, /me/saved/collection(s), PATCH /me/saved/collections |
| 400 | `NOT_HIDDEN` | Comment is not hidden | unhide a visible comment | POST /comments/{commentId}/unhide |
| 400 | `NOT_PUBLISHED` | Only published research can be retracted | retract on a non-published research | POST /api/v1/researches/{id}/retract |
| 400 | `NOT_PUBLISHED` | Research is not published | unpublish on a non-published research | POST /api/v1/researches/{id}/unpublish |
| 400 | `NOT_PUBLISHED` | Research is not published yet | public read/social action against an unpublished research (findPublishedOrThrow) | reactions/comments/save/view/download/share endpoints under /api/v1/researches/{researchId} |
| 400 | `NULL_REQUEST_BODY` | Request body cannot be null | create called with null request | POST /api/v1/researches |
| 400 | `PARENT_DELETED` | Cannot reply to a deleted comment | reply to soft-deleted parent | POST /api/v1/researches/{researchId}/comments |
| 409 | `RESEARCH_DUPLICATE` | Research already exists with slug: conflict | slug uniqueness violation on update | PATCH /api/v1/researches/{id} |
| 409 | `RESEARCH_DUPLICATE` | Research already exists with title or slug: already exists | DataIntegrityViolation (unique title/slug) during create | POST /api/v1/researches |
| 404 | `RESEARCH_NOT_FOUND` | Research not found with id: {id} | unknown or soft-deleted research id | all /api/v1/researches/{id}* endpoints |
| 404 | `RESEARCH_NOT_FOUND` | Research not found with slug: {slug} | unknown slug | GET /api/v1/researches/slug/{slug} |
| 404 | `RESEARCH_NOT_FOUND` | Research not found with token: {shareToken} | unknown share token | GET /api/v1/researches/share/{shareToken} |
| 409 | `RESOURCE_CONFLICT` | Reaction update conflict. Please retry. | concurrent reaction toggle conflict | POST /api/v1/researches/{researchId}/reactions |
| 409 | `RESOURCE_CONFLICT` | Research was modified by another user. Please refresh and try again. | optimistic-lock failure on update | PATCH /api/v1/researches/{id} |
| 409 | `RESOURCE_CONFLICT` | User is already a contributor on this research | adding an existing contributor (add and replace paths) | POST /{id}/contributors, PUT /{id}/contributors |
| 400 | `SOURCE_MISMATCH` | Source does not belong to this research | sourceId under a different research (update path throws 400; file-upload path throws the same text as 403 ACCESS_FORBIDDEN) | PATCH /{id}/sources/{sourceId}, POST /{id}/sources/{sourceId}/file |
| 404 | `SOURCE_NOT_FOUND` | Source not found with id: {sourceId} | unknown source id | source endpoints under /api/v1/researches |
| 500 | `SOURCE_UPLOAD_ERROR` | Failed to upload source file | source file upload failure | POST /api/v1/researches/{id}/sources/{sourceId}/file |
| 500 | `TAG_FETCH_ERROR` | Failed to fetch trending tags | trending-tag repository failure | GET /api/v1/researches/tags/trending |
| 500 | `UNEXPECTED_ERROR` | An unexpected error occurred while creating the research | any other exception during create | POST /api/v1/researches |
| 500 | `URL_GENERATION_ERROR` | Failed to generate download link | pre-signed URL generation failure | POST /api/v1/researches/{researchId}/download |
| 500 | `URL_GENERATION_ERROR` | Failed to generate file URL | public-URL generation failure | media/source upload endpoints |
| 404 | `USER_NOT_FOUND` | User not found with id: {userId} | unknown user id (contributor lookup, researcher lookup) | multiple endpoints under /api/v1/researches |
| 500 | `VIDEO_UPLOAD_ERROR` | Failed to upload video promo | video promo upload failure | POST /api/v1/researches/{id}/video-promo |
| 503 | `MEDIA_UPLOAD_FAILED / VIDEO_UPLOAD_FAILED / THUMBNAIL_UPLOAD_FAILED / COVER_UPLOAD_FAILED / SOURCE_UPLOAD_FAILED (per call-site)` | Failed to upload file to storage | any other storage upload failure | research file-upload endpoints |

### 1.58 `research (cross-cutting)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 429 | `RATE_LIMITED` | Too many {action} requests — please slow down | burst exceeded: reaction 30/10s, comment 10/30s, social(saves) 30/min; details carry action + retryAfterSeconds; fail-open when Redis is down | research reactions/comments/save endpoints |
| 403 | `RESEARCH_REACTION_BLOCKED_RELATIONSHIP / RESEARCH_COMMENT_BLOCKED_RELATIONSHIP / RESEARCH_COMMENT_REACTION_BLOCKED_RELATIONSHIP / RESEARCH_SAVE_BLOCKED_RELATIONSHIP` | This interaction is not allowed. | a block edge exists between actor and research owner (or comment author); deliberately never says who blocked whom | POST /reactions, POST /comments, POST /comments/{commentId}/reactions, POST /save under /api/v1/researches/{researchId} |

### 1.59 `research (storage backend, shared)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 500 | `FILE_UPLOAD_ERROR` | File upload failed | IOException reading the multipart upload | any storage-backed upload endpoint |
| 404 | `MEDIA_NOT_FOUND` | Failed to retrieve media file | R2 getObject failure (non-connectivity) | media streaming/read paths |
| 503 | `STORAGE_UNAVAILABLE` | File storage service is currently unavailable. Please try again later. | Cloudflare R2 unreachable (SdkClientException) on upload / getObject / putBytes | any endpoint that touches file storage (research, qna attachments, media pipeline) |
| 503 | `STORAGE_UNAVAILABLE` | File storage service is not configured | storage disabled — NoOpS3StorageService bound (upload/preview/delete/presign all throw it) | any storage-backed endpoint when S3/R2 is unconfigured |

### 1.60 `qna`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | Answer body is required \| Answer must not exceed 10000 characters (create) \| Answer must not exceed 5000 characters (edit) | bean validation on CreateAnswerRequest / EditAnswerRequest | POST/PATCH answer endpoints |
| 400 | `—` | Question title is required \| Question title must not exceed 500 characters \| Question body is required \| Question body must not exceed 10000 characters \| A question can have at most 30 tags \| Keywords must not exceed 2000 characters | bean validation on CreateQuestionRequest / EditQuestionRequest | POST /api/v1/questions, PATCH /{questionId} |
| 400 | `—` | Source type is required \| Source title is required \| Source title must not exceed 500 characters \| Citation text must not exceed 5000 characters \| ISBN must not exceed 20 characters \| Caption must not exceed 500 characters | bean validation on CreateAnswerSourceRequest / UpdateAnswerSourceRequest / UpdateAnswerAttachmentRequest | answer source/attachment endpoints |
| 403 | `ACCESS_FORBIDDEN` | Only scholars and researchers can answer questions | answer authoring by plain USER | POST /api/v1/questions/{questionId}/answers (+ reanswer/upload variants) |
| 403 | `ACCESS_FORBIDDEN` | Only scholars can post questions | question authoring by non-SCHOLAR/ADMIN (researchers excluded) | POST /api/v1/questions and question-author actions |
| 403 | `ACCESS_FORBIDDEN` | Only the question author can accept answers | accept by non-author | POST /{questionId}/answers/{answerId}/accept |
| 403 | `ACCESS_FORBIDDEN` | Only the question author can lock answers | lock by non-author | POST /{questionId}/lock-answers |
| 403 | `ACCESS_FORBIDDEN` | Only the question author can set the answer limit | answer-limit change by non-author | PATCH /{questionId}/answer-limit |
| 403 | `ACCESS_FORBIDDEN` | Only the question author can unaccept answers | unaccept by non-author | DELETE /{questionId}/answers/{answerId}/accept |
| 403 | `ACCESS_FORBIDDEN` | Only the question author can unlock answers | unlock by non-author | DELETE /{questionId}/lock-answers |
| 403 | `ACCESS_FORBIDDEN` | You can only add sources to your own answer | source add by non-author | POST /{questionId}/answers/{answerId}/sources |
| 403 | `ACCESS_FORBIDDEN` | You can only attach files to sources on your own answer | source-file upload by non-author | POST .../sources/{sourceId}/file |
| 403 | `ACCESS_FORBIDDEN` | You can only delete attachments from your own answer | attachment delete by non-author | DELETE /{questionId}/answers/{answerId}/attachments/{attachmentId} |
| 403 | `ACCESS_FORBIDDEN` | You can only delete sources from your own answer | source delete by non-author | DELETE .../sources/{sourceId} |
| 403 | `ACCESS_FORBIDDEN` | You can only delete your own answer or answers on your question | answer delete by non-author (question author/admin allowed) | DELETE /{questionId}/answers/{answerId} |
| 403 | `ACCESS_FORBIDDEN` | You can only delete your own question | delete by non-author non-admin | DELETE /api/v1/questions/{questionId} |
| 403 | `ACCESS_FORBIDDEN` | You can only edit attachments on your own answer | attachment edit by non-author | PATCH /{questionId}/answers/{answerId}/attachments/{attachmentId} |
| 403 | `ACCESS_FORBIDDEN` | You can only edit sources on your own answer | source edit by non-author | PATCH .../sources/{sourceId} |
| 403 | `ACCESS_FORBIDDEN` | You can only edit your own answer or answers on your question | answer edit by non-author (question author/admin allowed) | PATCH /{questionId}/answers/{answerId} |
| 403 | `ACCESS_FORBIDDEN` | You can only edit your own question | edit by non-author non-admin | PATCH /api/v1/questions/{questionId} |
| 403 | `ACCESS_FORBIDDEN` | You can only upload attachments to your own answer | attachment upload by non-author | POST /{questionId}/answers/{answerId}/attachments |
| 403 | `ACCESS_FORBIDDEN` | You cannot archive this question | archive by non-author non-admin | question archive endpoint |
| 403 | `ACCESS_FORBIDDEN` | You cannot close this question | close by non-author non-admin | question close endpoint |
| 403 | `ACCESS_FORBIDDEN` | You cannot reopen this question | reopen by non-author non-admin | question reopen endpoint |
| 400 | `ANSWERS_LOCKED` | Answers are locked for this question | answering while answersLocked=true | POST /{questionId}/answers (+ variants) |
| 400 | `ANSWER_LIMIT_REACHED` | Maximum number of answers ({maxAnswers}) reached | top-level answer beyond maxAnswers (reanswers exempt) | POST /{questionId}/answers |
| 404 | `ANSWER_NOT_FOUND` | Answer not found with id: {answerId} | unknown/deleted answer under the question | all answer sub-endpoints |
| 400 | `ATTACHMENT_MISMATCH` | Attachment does not belong to this answer | attachmentId under a different answer (edit and delete) | attachment sub-endpoints |
| 404 | `ATTACHMENT_NOT_FOUND` | Attachment not found with id: {attachmentId} | unknown attachment id | attachment sub-endpoints |
| 401 | `AUTH_UNAUTHORIZED` | Authentication required | authenticated endpoint reached with null principal (guard on 31 handlers) | most write endpoints under /api/v1/questions |
| 400 | `EMPTY_ANSWER` | Answer body cannot be empty | edit sets blank answer body | PATCH /{questionId}/answers/{answerId} |
| 400 | `EMPTY_BODY` | Question body cannot be empty | edit sets blank body | PATCH /api/v1/questions/{questionId} |
| 400 | `EMPTY_TITLE` | Question title cannot be empty | edit sets blank title | PATCH /api/v1/questions/{questionId} |
| 400 | `EMPTY_TITLE` | Source title cannot be empty | source edit blanks the title | PATCH .../sources/{sourceId} |
| 400 | `MISSING_COLLECTION_NAME` | Collection name is required | blank collection name | GET /api/v1/questions/me/saved/collection |
| 400 | `MISSING_FILE` | File is required | source-file upload with empty file | POST /{questionId}/answers/{answerId}/sources/{sourceId}/file |
| 400 | `MISSING_NEW_NAME` | New collection name is required | rename with blank newName | PATCH /api/v1/questions/me/saved/collections |
| 400 | `MISSING_OLD_NAME` | Old collection name is required | rename with blank oldName | PATCH /api/v1/questions/me/saved/collections |
| 404 | `PARENT_ANSWER_NOT_FOUND` | Parent answer not found with id: {parentAnswerId} | reanswer targeting missing parent | POST /{questionId}/answers (with parentAnswerId), reanswer endpoints |
| 400 | `QUESTION_CLOSED` | Question is closed | answering a CLOSED or ARCHIVED question | POST /{questionId}/answers (+ variants) |
| 404 | `QUESTION_NOT_FOUND` | Question not found with id: {questionId} | unknown or deleted question | all /api/v1/questions/{questionId}* endpoints |
| 400 | `REANSWER_NOT_ACCEPTABLE` | Reanswers cannot be accepted as best answer | accept targets a depth-1 reanswer | POST /{questionId}/answers/{answerId}/accept |
| 400 | `SOURCE_MISMATCH` | Source does not belong to this answer | sourceId under a different answer (file upload, edit, delete) | answer source sub-endpoints |
| 404 | `SOURCE_NOT_FOUND` | Source not found with id: {sourceId} | unknown source id | answer source sub-endpoints |
| 404 | `USER_NOT_FOUND` | User not found with id: {userId} | unknown actor id | qna write endpoints |

### 1.61 `qna (cross-cutting)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ANSWER_BLOCKED_RELATIONSHIP / REANSWER_BLOCKED_RELATIONSHIP / ANSWER_REACTION_BLOCKED_RELATIONSHIP / QNA_SAVE_BLOCKED_RELATIONSHIP` | This interaction is not allowed. | block edge between actor and question author / parent-answer author / answer author (per code) | POST answers, reanswers, /answers/{answerId}/react, /{questionId}/save |
| 429 | `RATE_LIMITED` | Too many {action} requests — please slow down | burst exceeded: comment(answers) 10/30s, reaction 30/10s, social(saves) 30/min; details carry action + retryAfterSeconds | answer create, answer react, question save endpoints |

### 1.62 `chat`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_REQUEST` | Title cannot be blank. | channel update (ChannelService:120) or live-stream update (LiveStreamService:422) sets an empty title | PATCH /api/v1/channels/{id}, PATCH stream |
| 403 | `BLOCKED` | This interaction is not allowed. | block relationship between the two users — on DM create (ConversationService:77), message send in DM (MessageService:135), forward into a DM (MessageService:237), call initiate (CallService:75). Deliberately vague to not reveal blocks | POST /api/v1/conversations, POST /api/v1/conversations/{id}/messages, POST forward, call initiate |

### 1.63 `chat (calls)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_REQUEST` | Target is not part of this call. | signal relayed to a user not in the call (CallService:166) | call signal endpoint |
| 400 | `BAD_REQUEST` | This call is no longer active. | signaling on an ended/cancelled call (CallService:248) | call signal endpoints |
| 403 | `NOT_A_MEMBER` | You are not an active member of this conversation. | call initiate by inactive/removed conversation member (CallService:266) | call initiate endpoint |
| 403 | `NOT_A_MEMBER` | You are not part of this call. | call op by a non-participant (CallService:254) | call endpoints |

### 1.64 `chat (channels)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_FORBIDDEN` | The owner cannot unsubscribe from their own channel. | owner calls unsubscribe | DELETE channel subscription |
| 403 | `ACCESS_FORBIDDEN` | This channel is private. | public preview/handle access of a non-public channel (ChannelService:297) | GET channel by handle/preview |
| 403 | `ADMINS_ONLY` | You cannot change this channel's photo. / You cannot change this channel's cover. | photo/cover set or delete without canChangeInfo right | channel image endpoints |
| 403 | `ADMINS_ONLY` | You cannot edit this channel's info. | channel update without canChangeInfo right | PATCH /api/v1/channels/{id} |
| 403 | `ADMINS_ONLY` | You cannot manage admins in this channel. | putAdmin/removeAdmin without canAddAdmins right | channel admin endpoints |
| 400 | `BAD_REQUEST` | A channel requires a title. | channel create with blank title | POST /api/v1/channels |
| 400 | `BAD_REQUEST` | A public channel requires a @handle. | public channel create/update without handle (ChannelService:84,133) | POST/PATCH /api/v1/channels |
| 400 | `BAD_REQUEST` | Handle must be 3–32 characters of a–z, 0–9 or underscore. | handle failing the normalization regex | POST/PATCH /api/v1/channels |
| 400 | `BAD_REQUEST` | Provide an image file. | channel photo/cover upload with empty file | channel photo/cover upload endpoints |
| 400 | `BAD_REQUEST` | That @handle is already taken. | handle uniqueness violation on create/update (ChannelService:85,135) | POST/PATCH /api/v1/channels |
| 400 | `BAD_REQUEST` | The channel photo must be an image. / The channel cover must be an image. | non-image content type on channel photo/cover upload (text varies by avatar\|cover) | channel photo/cover upload endpoints |
| 403 | `NOT_A_MEMBER` | You are not a member of this channel. | member-only channel read by non-member | channel member-scoped endpoints |
| 403 | `NOT_OWNER` | The owner cannot be demoted. | removeAdmin targeting the owner | DELETE channel admin |
| 403 | `NOT_OWNER` | The owner's rights cannot be edited. | putAdmin targeting the channel owner | PUT channel admin rights |

### 1.65 `chat (conversations)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_REQUEST` | This action applies only to group conversations. | group-only op (e.g. member list mgmt) on DIRECT convo (ConversationService:433, GroupMemberService:415) | /api/v1/conversations/{id}/** group ops |
| 400 | `BAD_REQUEST` | You cannot start a conversation with yourself. | DIRECT create where recipientId == caller | POST /api/v1/conversations |
| 400 | `BAD_REQUEST` | recipientId is required for a DIRECT conversation. | create conversation type=DIRECT without recipientId | POST /api/v1/conversations |

### 1.66 `chat (discussion)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ADMINS_ONLY` | You cannot manage this channel's discussion group. | link/unlink by non-admin | channel discussion-group endpoints |
| 400 | `BAD_REQUEST` | That group is already another channel's discussion group. | linking a group already linked elsewhere (ChannelDiscussionService:63) | channel discussion-group link endpoint |
| 400 | `BAD_REQUEST` | That message is not a post of this channel. | commenting on a message that isn't a post of the channel | channel post comment endpoint |
| 400 | `BAD_REQUEST` | This channel has no discussion group — comments are disabled. | comment on a channel post when no discussion group is linked | POST /api/v1/channels/{id}/posts/{postId}/comments |
| 403 | `READ_ONLY` | You are restricted in the discussion group. | restricted member tries to comment | channel post comment endpoint |

### 1.67 `chat (edit/delete)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_FORBIDDEN` | You can only edit your own messages. | edit by non-sender (without channel edit right) | PATCH message |
| 403 | `ACCESS_FORBIDDEN` | You cannot delete this message. | delete-for-everyone without permission | DELETE message |
| 400 | `BAD_REQUEST` | Cannot edit a deleted message. | edit of a deleted message | PATCH message |
| 400 | `BAD_REQUEST` | System messages cannot be edited. | edit of a SYSTEM message | PATCH message |

### 1.68 `chat (forward)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `PROTECTED_CONTENT` | This channel's content is protected and cannot be forwarded. | forwarding a post out of a protected-content channel | POST /api/v1/messages/{id}/forward |

### 1.69 `chat (gifts)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_REQUEST` | A gift id is required. | gift send with blank id | POST stream gift |
| 400 | `BAD_REQUEST` | Unknown gift '{id}'. | gift id not in catalog | POST stream gift |

### 1.70 `chat (groups)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ADMINS_ONLY` | You cannot change this group's settings. | group settings update or disappearing-timer change without CHANGE_SETTINGS permission (ConversationService:250,323) | PATCH /api/v1/conversations/{id}, PUT disappearing endpoint |
| 403 | `ADMINS_ONLY` | You cannot edit this group's info. | title/description/avatar change without EDIT_INFO permission | PATCH /api/v1/conversations/{id} |
| 400 | `BAD_REQUEST` | A group can start with at most 256 members. | GROUP create with more than MAX_GROUP_INITIAL_MEMBERS(256) members | POST /api/v1/conversations |
| 400 | `BAD_REQUEST` | A group requires a title. | GROUP create with blank title | POST /api/v1/conversations |
| 400 | `BAD_REQUEST` | seconds must be >= 0. | disappearing-messages timer set to negative | disappearing endpoint on /api/v1/conversations/{id} |

### 1.71 `chat (invites)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ADMINS_ONLY` | You cannot manage invite links here. | invite create/revoke without CREATE_INVITE (+canInviteUsers for channels) (364,368) | invite link endpoints |
| 400 | `BAD_REQUEST` | Could not process the invite token. | malformed invite token (GroupMemberService:459) | POST join-by-token |
| 403 | `INVITE_INVALID` | This invite link is invalid or has expired. | join by revoked/expired/over-used invite token | POST join-by-token |

### 1.72 `chat (join requests)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ADMINS_ONLY` | You cannot manage join requests here. | decide without canInviteUsers/admin right | channel join-request endpoints |
| 400 | `BAD_REQUEST` | This join request was already decided. | approve/decline of a non-pending join request | channel join-request decide endpoints |

### 1.73 `chat (live)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_FORBIDDEN` | Only the host can control this recording. | recording start/stop by non-host | stream recording endpoints |
| 403 | `ACCESS_FORBIDDEN` | Only the host can end this stream. | end-stream by non-host | POST stream end |
| 403 | `ACCESS_FORBIDDEN` | Only the host can manage this stream. | stream update/manage by non-host (LiveStreamService:666) | PATCH stream / manage endpoints |
| 400 | `BAD_REQUEST` | A stream requires a title. | stream start with blank title | POST /api/v1/streams |
| 400 | `BAD_REQUEST` | Message text is required. | live-chat send with blank text | POST stream chat |
| 403 | `NOT_A_MEMBER` | Join the stream before chatting. | live-chat send by a user who hasn't joined as viewer | POST stream chat |
| 404 | `RECORDING_NOT_FOUND` | Recording not found with streamId: {streamId} | recording download when no parts exist | GET stream recording |
| 404 | `RECORDING_PART_NOT_FOUND` | Recording part not found with file: {part} | recording part missing on disk | GET stream recording part |
| 400 | `STREAM_NOT_LIVE` | This stream is not live. | recording control on non-live stream (LiveStreamService:227, code STREAM_NOT_LIVE); same text with default BAD_REQUEST code at LiveStreamService:678 and StreamStageService:513,527 (stage/gift/chat ops on ended stream) | stream recording/chat/stage/gift endpoints |

### 1.74 `chat (media auth)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `—` | (empty body — status only) | MediaMTX auth hook: bad shared secret or bad stream key | POST /internal/media/auth/{secret} (infra-internal, reaches publishers as a WHIP/RTMP publish rejection) |

### 1.75 `chat (members)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ADMINS_ONLY` | You cannot add members to this group. / You cannot add subscribers to this channel. | addMembers without permission (GroupMemberService:89,93) | POST /api/v1/conversations/{id}/members |
| 403 | `ADMINS_ONLY` | You cannot change this member's role. | promote/demote without permission | member role endpoint |
| 400 | `BAD_REQUEST` | This action applies only to group or channel conversations. | member op on a DIRECT conversation (GroupMemberService:426) | /api/v1/conversations/{id}/members/** |
| 400 | `BAD_REQUEST` | Transfer ownership before leaving, or delete the group. | sole owner tries to leave a group that still has members (guard at GroupMemberService:220) | leave group endpoint |
| 400 | `BAD_REQUEST` | role must be ADMIN or MEMBER. | changeRole with any other role | member role endpoint |
| 403 | `CANNOT_ACT_ON_ADMIN or ADMINS_ONLY` | You cannot remove this member. / You cannot restrict this member. | permission-matrix denial; code is CANNOT_ACT_ON_ADMIN when target is admin/owner, else ADMINS_ONLY | member remove/restrict endpoints |
| 403 | `NOT_OWNER` | Only the owner can transfer ownership. | ownership transfer by non-owner | transfer-owner endpoint |
| 403 | `NOT_OWNER` | The owner cannot be removed. / The owner's role cannot be changed. / The owner cannot be restricted. | remove/changeRole/restrict targeting the owner (154,178,203) | member management endpoints |
| 403 | `SUBSCRIBERS_HIDDEN` | This channel's subscriber list is hidden. | listing subscribers of a channel with hidden list, as non-admin | GET channel subscribers |

### 1.76 `chat (membership gates)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ADMINS_ONLY` | Only admins can send messages here. | admins-only group/channel send by plain member | POST messages |
| 403 | `ADMINS_ONLY` | You do not have the right to post in this channel. | channel admin lacking granular canPostMessages right | POST channel posts |
| 403 | `NOT_A_MEMBER` | You are not a member of this conversation. | send/read on a conversation the caller isn't an active member of (MessageService:815 + requireReadableMessage/requirePostableMessage) | all /api/v1/conversations/{id}/** message endpoints |
| 403 | `READ_ONLY` | You are restricted from interacting here. | RESTRICTED member reacts/interacts (MessageService:938) | react/interaction endpoints |
| 403 | `READ_ONLY` | You are restricted from posting here. | RESTRICTED member sends a message | POST messages |

### 1.77 `chat (message requests)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_FORBIDDEN` | This request is not addressed to you. | accept/decline of a MessageRequest whose recipient is someone else | /api/v1/message-requests/{id}/accept\|decline |
| 403 | `REQUEST_LIMIT_REACHED` | This interaction is not allowed. | sending after the message request was declined/blocked (MessageService:902) | POST messages (stranger DM) |
| 403 | `REQUEST_LIMIT_REACHED` | You've reached the limit before this request is accepted. | stranger exceeds STRANGER_MESSAGE_CAP while request still pending | POST messages (stranger DM) |

### 1.78 `chat (messages)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_REQUEST` | A LOCATION message requires a location payload. / A location payload requires type LOCATION. / A CONTACT message requires a contact payload. / A contact payload requires type CONTACT. / Could not store the {location\|contact} payload. | typed-payload pairing violations or serialization failure (MessageService payloadJson 849-858) | POST /api/v1/conversations/{id}/messages |
| 400 | `BAD_REQUEST` | A poll payload requires type POLL. / A POLL message requires a poll payload. | type↔payload mismatch on send (MessageService:152,157) | POST /api/v1/conversations/{id}/messages |
| 400 | `BAD_REQUEST` | Provide a body and/or at least one file. | multipart message send with neither text body nor files | POST /api/v1/conversations/{id}/messages (multipart) — MessageController:155 |

### 1.79 `chat (moderation)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `CHANNEL_FROZEN` | Posting in this channel has been suspended by platform moderation. | admin-frozen channel — outranks all channel-local rights incl. owner | POST channel posts |

### 1.80 `chat (pin)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ADMINS_ONLY` | You cannot pin messages in this group. / You cannot pin posts in this channel. | pin without PIN permission (group) or canPinMessages (channel) | pin endpoints |

### 1.81 `chat (polls)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_FORBIDDEN` | You cannot close this poll. | close by someone who is neither poll author nor a channel admin with canEditMessages | poll close endpoint |
| 400 | `BAD_REQUEST` | A quiz cannot allow multiple answers. | poll create quiz=true with allowsMultipleAnswers | POST poll message |
| 400 | `BAD_REQUEST` | A quiz requires a valid correctOptionIndex. | quiz create with missing/out-of-range correctOptionIndex | POST poll message |
| 400 | `BAD_REQUEST` | Corrupt poll payload. | stored poll JSON fails to parse | poll read/vote endpoints |
| 400 | `BAD_REQUEST` | Could not store the poll payload. | poll payload JSON serialization failure | POST poll message |
| 400 | `BAD_REQUEST` | Option index out of range. | vote with option index outside options list | poll vote endpoint |
| 400 | `BAD_REQUEST` | Quiz answers are final. | changing (118) or retracting (142) a quiz vote | poll vote/retract endpoints |
| 400 | `BAD_REQUEST` | This message is not a poll. | poll op on a non-POLL message | poll endpoints |
| 400 | `BAD_REQUEST` | This poll allows only one answer. | multi-option vote on single-answer poll | poll vote endpoint |
| 400 | `BAD_REQUEST` | This poll is closed. | vote (PollService:104) or retract (141) on a closed poll | poll vote/retract endpoints |
| 400 | `BAD_REQUEST` | correctOptionIndex applies only to quizzes. | non-quiz poll carries correctOptionIndex | POST poll message |

### 1.82 `chat (reactions)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `BAD_REQUEST` | This reaction is not allowed in this channel. | emoji outside the channel's allowed reaction set | message react endpoint |
| 403 | `REACTIONS_DISABLED` | Reactions are disabled in this channel. | react in a channel with reactions off | message react endpoint |

### 1.83 `chat (scheduled)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_FORBIDDEN` | This scheduled message is not yours. | cancel/edit of another user's scheduled message | scheduled message endpoints |
| 400 | `BAD_REQUEST` | scheduledAt must be in the future. | scheduling a message with a past timestamp | POST /api/v1/conversations/{id}/messages/schedule |

### 1.84 `chat (stage)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_FORBIDDEN` | Only the host can manage this stream's stage. | stage management by non-host | stage host endpoints |
| 400 | `BAD_REQUEST` | No pending request from this user. | host approves a request that doesn't exist (191) | stage request approve |
| 400 | `BAD_REQUEST` | That user is not on stage. | removing/muting a user who isn't an active guest | stage remove endpoint |
| 400 | `BAD_REQUEST` | The stage is full ({maxGuests} guests). | activating a guest beyond the stage cap | stage approve/accept |
| 400 | `BAD_REQUEST` | They are already on stage. | inviting an already-ACTIVE guest | stage invite |
| 400 | `BAD_REQUEST` | You are already on stage. | duplicate stage request while ACTIVE | POST stage request |
| 400 | `BAD_REQUEST` | You are the host of this stream. | host requests to join own stage (121) or invites self (221) | stage request/invite endpoints |
| 400 | `BAD_REQUEST` | You have no pending invite to come up. | accepting a nonexistent stage invite (StreamStageService:154) | stage invite accept |
| 403 | `NOT_A_MEMBER` | Join the stream before asking to come up. | stage request from non-viewer | POST stage request |
| 403 | `NOT_A_MEMBER` | Join the stream first. | stage/reaction op requiring viewer membership (StreamStageService:536) | stream reaction/gift endpoints |

### 1.85 `chat DTO validation`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `VALIDATION` | body is required \| a message may not exceed 8000 characters \| scheduledAt is required \| clientNonce is required \| clientNonce is required for idempotent send \| type is required \| at most 10 attachments per message \| type is required (DIRECT \| GROUP) \| group title must not exceed 120 characters \| group description must not exceed 500 characters \| a group can start with at most 256 members \| userIds must not be empty \| add at most 100 members per request \| a poll requires a question \| a poll needs 2–10 options \| seconds must be >= 0 \| role is required (ADMIN \| MEMBER) \| media kind is required \| storageKey is required \| token is required \| at most 100 posts per batch \| expiresInHours must be positive \| maxUses must be positive \| lastReadMessageId is required \| emoji is required \| emoji must be a single grapheme \| newOwnerId is required \| targetConversationId is required \| channel title must not exceed 120 characters \| channel description must not exceed 500 characters \| pick at least one option | bean validation on chat request DTOs (EditMessageRequest, ScheduleMessageRequest, SendMessageRequest, CreateConversationRequest, AddMembersRequest, PollCreateDto, DisappearingRequest, ChangeRoleRequest, UpdateConversationRequest, MediaRefDto, JoinByTokenRequest, MarkViewsRequest, CreateInviteLinkRequest, ReadMarkerRequest, ReactRequest, TransferOwnerRequest, ForwardMessageRequest, UpdateChannelRequest, PollVoteRequest). activity's RecordReelViewRequest uses default @Min(0) message | corresponding POST/PATCH chat endpoints; per-field errors in the validation envelope |

### 1.86 `chat/post/activity (404 family)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `{RESOURCE}_NOT_FOUND` | {Resource} not found with {field}: {value} — Resources in scope: Conversation, Message, Channel, Member, Subscriber, User, Call, LiveStream, Recording, Recording part, ScheduledMessage, MessageRequest, JoinRequest, InviteLink, Draft, Group, Post, Story, Sound, UserActivity | any lookup miss via ResourceNotFoundException(resource, field, value); details map carries {resource, field, value} | all endpoints in scope |

### 1.87 `media`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | mime: must not be blank \| type: must not be blank \| sizeBytes: must be greater than 0 (framework default texts — no custom messages) | bean validation on UploadIntentRequest | POST /api/v1/media/upload-intent |
| 401 | `AUTH_REQUIRED` | Authentication is required for this endpoint. | SecurityUtils.requireCurrentUserId with no principal | all /api/v1/media endpoints |
| 404 | `MEDIA_NOT_FOUND` | Media not found with id: {assetId} | unknown asset id | /api/v1/media/{id} endpoints |
| 429 | `MEDIA_QUOTA_EXCEEDED` | Daily upload quota reached ({N uploads \| N MB} per day for your account tier). The quota resets at midnight UTC. | upload intent would exceed the role's daily count or byte budget (details: dimension, role, resetsAt) | POST /api/v1/media/uploads (upload intent) |
| 400 | `MEDIA_RAW_MISSING` | Uploaded file was not found. Re-upload and retry. | complete called but raw/{assetId} object missing/unreadable; asset also flips to FAILED_VALIDATION with stored errorMessage "Raw upload not found or unreadable." | POST /api/v1/media/{id}/complete |
| 400 | `MEDIA_TOO_LARGE` | File exceeds the maximum size for {TYPE} ({cap} bytes). | declared sizeBytes over the type cap (defaults: image 26,214,400 B = 25 MB; video 536,870,912 B = 512 MB) | POST /api/v1/media/upload-intent |
| 403 | `NOT_MEDIA_OWNER` | You do not own this media. | complete/get/delete on an asset owned by someone else | POST /api/v1/media/{id}/complete, GET /api/v1/media/{id}, DELETE /api/v1/media/{id} |
| 400 | `STORAGE_UNAVAILABLE` | Media storage is not configured on this server. | presignPut unsupported (NoOp storage bound) | POST /api/v1/media/upload-intent |

### 1.88 `common (fires from post+chat)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 429 | `RATE_LIMITED` | Too many {action} requests — please slow down (details: {action, retryAfterSeconds}) | RateLimiter bucket over burst. Actions in scope: social 30/min (POST /api/v1/posts JSON + multipart create, story create x2, post save toggle, share, repost), comment 10/30s (comment create + reply), chat-send 30/10s (send message), chat-slow-{conversationId} 1 msg per slowModeSeconds (non-admin group member under slow mode), stream-chat 20/10s (live stream chat), stream-stage-request 5/30s (ask to join stage), stream-react 30/10s (live reactions), stream-gift 10/10s (gift send) | POST /api/v1/posts, /api/v1/posts/{id}/comments(+replies), /save, /share, /repost, /api/v1/stories, /api/v1/conversations/{id}/messages, /api/v1/streams/{id}/chat\|reactions\|gifts\|stage/request |

### 1.89 `common/exception`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_DENIED` | You do not have permission to perform this action. | AccessDenied/AuthorizationDenied (@PreAuthorize failure). SSE-only Accept (text/event-stream, no wildcard/JSON) gets status-only, no body | all endpoints |
| 401 | `AUTH_ACCOUNT_DISABLED` | Your account is disabled. Please verify your email or contact support. | DisabledException | auth endpoints |
| 401 | `AUTH_ACCOUNT_EXPIRED` | Your account has expired. Please contact support. | AccountExpiredException | auth endpoints |
| 401 | `AUTH_ACCOUNT_LOCKED` | Your account is locked. Please contact support. | LockedException | auth endpoints |
| 401 | `AUTH_BAD_CREDENTIALS` | Invalid email or password. | BadCredentialsException on login | auth endpoints |
| 401 | `AUTH_CREDENTIALS_EXPIRED` | Your credentials have expired. Please reset your password. | CredentialsExpiredException | auth endpoints |
| 401 | `AUTH_FAILED` | Authentication failed: {exceptionMessage} | Any other AuthenticationException | all endpoints |
| 401 | `AUTH_INSUFFICIENT` | Full authentication is required to access this resource. | InsufficientAuthenticationException | all endpoints |
| 500 | `DATASTORE_QUERY_ERROR` | A datastore query failed. Please contact support with trace ID: {traceId} | CQL QueryValidationException (schema drift) | all endpoints |
| 503 | `DATASTORE_UNAVAILABLE` | A backing datastore is temporarily unavailable. Please try again shortly. | Cassandra DriverTimeout/AllNodesFailed, QueryTimeout, DataAccessResourceFailure (SSE: status-only) | all endpoints |
| 409 | `DATA_INTEGRITY_VIOLATION` | A data integrity constraint was violated. This usually means a duplicate or invalid reference exists. | DataIntegrityViolationException (unique/FK constraint) | all endpoints |
| 404 | `ENDPOINT_NOT_FOUND` | No endpoint found for %s %s | NoHandlerFound/NoResourceFound (SSE-only Accept: status-only) | all endpoints |
| 413 | `FILE_TOO_LARGE` | The uploaded file exceeds the maximum allowed size. | MaxUploadSizeExceededException; details={maxSize} | upload endpoints |
| 403 | `FORBIDDEN` | {exceptionMessage} — or fallback: You do not have permission to perform this action. | Plain java SecurityException from service-layer author/owner checks (e.g. 'Not the author') | all endpoints |
| 400 | `ILLEGAL_ARGUMENT` | {exceptionMessage} (raw IllegalArgumentException message passed through) | IllegalArgumentException anywhere | all endpoints |
| 500 | `ILLEGAL_STATE` | An unexpected state was encountered. Please try again or contact support. | IllegalStateException | all endpoints |
| 500 | `INTERNAL_ERROR` | An unexpected error occurred. Please try again later. If the problem persists, contact support with trace ID: {traceId} | Catch-all Exception (client disconnects and committed responses suppressed; SSE: status-only) | all endpoints |
| 400 | `MALFORMED_JSON` | Malformed JSON request body. Please check your JSON syntax and field types. | HttpMessageNotReadableException (unparseable body) | all endpoints |
| 404 | `MEDIA_NOT_FOUND` | The requested file does not exist. | S3 NoSuchKeyException | media endpoints |
| 405 | `METHOD_NOT_ALLOWED` | HTTP method '%s' is not supported for this endpoint. Supported: %s | Wrong HTTP verb | all endpoints |
| 400 | `MISSING_PARAMETER` | Required parameter '%s' of type '%s' is missing | Missing required query/form param; details={parameter, expectedType} | all endpoints |
| 400 | `MISSING_REQUEST_PART` | {exceptionMessage} (raw Spring message passed through) | MissingRequestHeader/MissingServletRequestPart | all endpoints |
| 409 | `OPTIMISTIC_LOCK_CONFLICT` | The resource was modified concurrently. Please reload and retry. | OptimisticLockingFailureException after retry exhaustion | all endpoints |
| 404 | `RESOURCE_NOT_FOUND` | {exceptionMessage} — or fallback: Resource not found | jakarta EntityNotFoundException from legacy JPA lookups | all endpoints |
| 502 | `STORAGE_ERROR` | File storage returned an error. Please try again later. | S3Exception (service-side) | media endpoints |
| 503 | `STORAGE_UNAVAILABLE` | File storage service is currently unavailable. Please try again later. | AWS SdkClientException (R2/S3 client failure) | media/file endpoints |
| 400 | `TYPE_MISMATCH` | Parameter '%s' must be of type '%s'. Received: '%s' | Path/query param not convertible; details={parameter, expectedType, receivedValue, hint=value_not_convertible} | all endpoints |
| 400 | `TYPE_MISMATCH` | Parameter '%s' was the JS literal '%s' — the frontend templated an unhydrated variable into the URL. Guard the call site (e.g. `if (!%s) return;`) before fetching. | Received value is literally 'undefined' or 'null'; details.hint=frontend_path_param_unhydrated | all endpoints |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content type '%s' is not supported. Supported: %s | Wrong Content-Type | all endpoints |
| 400 | `VALIDATION_FAILED` | One or more fields failed validation. Check 'fieldErrors' for details. | @Valid body failure (MethodArgumentNotValidException); per-field bean-validation messages carried in fieldErrors[] | all endpoints |
| 400 | `VALIDATION_FAILED` | One or more parameters failed validation. Check 'fieldErrors' for details. | jakarta ConstraintViolationException on @Validated path/query params | all endpoints |
| 400 | `VALIDATION_FAILED` | One or more request values failed validation. | Spring 6.1 HandlerMethodValidationException | all endpoints |

### 1.90 `common/exception (ApiErrorController)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_DENIED` | You do not have permission to perform this action. | Container-level /error dispatch with 403 | /error (all endpoints) |
| 401 | `AUTH_REQUIRED` | You must be authenticated to access this resource. | Container-level /error dispatch with 401 (filter-layer rejection before advice runs) | /error (all endpoints) |
| 404 | `ENDPOINT_NOT_FOUND` | The requested resource was not found. | Container-level /error dispatch with 404 | /error (all endpoints) |
| 5xx | `INTERNAL_ERROR` | An unexpected error occurred. Please try again later. | Container-level /error dispatch with any 5xx; other non-5xx statuses render the HTTP reason phrase with code ERROR_{status} | /error (all endpoints) |

### 1.91 `admin/activity`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_TYPE` | Unknown activity type. | Unparseable erase body type | POST /api/v1/admin/users/{userId}/activity/erase |

### 1.92 `admin/activity (break-glass)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `BREAKGLASS_CASE_REQUIRED` | Break-glass access requires an OPEN dual-control case for this user (POST /api/v1/admin/breakglass/{userId}, approved by a second admin). The default is no access. | Per-user activity/timeline/reel-history/export read without an OPEN approved case | GET /api/v1/admin/users/{userId}/activity[\|/summary\|/export], GET /api/v1/admin/users/{userId}/reels/watched |
| 400 | `CASE_NOT_PENDING` | Case is not pending approval. | Approving a case not in PENDING_APPROVAL | POST /api/v1/admin/breakglass/cases/{caseId}/approve |
| 403 | `DUAL_CONTROL_REQUIRED` | Dual control: a break-glass case cannot be approved by the admin who opened it. | Approver == opener | POST /api/v1/admin/breakglass/cases/{caseId}/approve |
| 400 | `INVALID_KIND` | Unknown kind. Allowed: {BreakGlassCase.CaseKind values array} | Unparseable case kind | POST /api/v1/admin/breakglass/{targetUserId} |

### 1.93 `admin/analytics`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_COHORT` | cohort must be YYYY-MM. | cohort param not parseable as a year-month | GET /api/v1/admin/analytics/funnel |
| 400 | `INVALID_DATASET` | Unknown dataset. Allowed: engagement, signups, content. | Unknown ?dataset= on CSV export | GET /api/v1/admin/analytics/export |
| 400 | `INVALID_DATE` | Date must be YYYY-MM-DD. | date path/param not ISO local date | POST /api/v1/admin/analytics/rollup/{date}/run; POST /backfill; GET /events/sample |
| 400 | `INVALID_METRIC` | Metric must be 1-80 chars of letters, digits, dot, dash or underscore. | metric name fails the [A-Za-z0-9._-]+ pattern | GET /api/v1/admin/analytics/series; PUT /api/v1/admin/analytics/alerts/{metric} |
| 400 | `INVALID_RANGE` | 'to' must not be before 'from'. | backfill range reversed | POST /api/v1/admin/analytics/backfill |
| 400 | `INVALID_THRESHOLDS` | Thresholds must be positive (minVolume may be 0). / zAlert must be >= zWarn. | non-positive z thresholds, negative minVolume, or zAlert < zWarn | PUT /api/v1/admin/analytics/alerts/{metric} |
| 400 | `RANGE_TOO_LARGE` | Backfill is capped at 90 days per call — split larger ranges. | backfill span > 90 days | POST /api/v1/admin/analytics/backfill |

### 1.94 `admin/chat`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 409 | `CONFLICT` | Dual control: the admin who opened a legal hold cannot approve it — a second admin must review. | opener tries to approve their own hold | POST /api/v1/admin/chat/legal-holds/{id}/approve |
| 400 | `DM_BROWSE_FORBIDDEN` | DM conversations are not browsable — aggregate stats only. | ?type=DIRECT on conversation browse (privacy rule: who-talks-to-whom is surveillance metadata) | GET /api/v1/admin/chat/conversations |
| 400 | `INVALID_INPUT` | conversationId is required. / A case reference (ticket / court order id) is required. | open without conversation or reason | POST /api/v1/admin/chat/legal-holds |
| 400 | `INVALID_STATUS` | Unknown call status. Allowed: {CallStatus values array} | Unparseable ?status= | GET /api/v1/admin/chat/calls |
| 400 | `INVALID_TYPE` | Unknown type. Allowed: GROUP, CHANNEL. — and — Unknown call type. Allowed: VOICE, VIDEO. | Unparseable ?type= | GET /api/v1/admin/chat/conversations, GET /api/v1/admin/chat/calls |
| 400 | `LEGAL_HOLD_WRONG_STATE` | Cannot {approve\|reject\|execute} a {status} hold — only {OPEN\|APPROVED} holds can be {verb}d. | state-machine violation | legal-hold approve/reject/execute |

### 1.95 `admin/chat streams`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_STATUS` | Unknown status. Allowed: LIVE, ENDED. | Unparseable ?status= | GET /api/v1/admin/streams |
| 404 | `LIVESTREAM_NOT_FOUND` | LiveStream not found with id: {id} | Unknown stream id | /api/v1/admin/streams/** |
| 400 | `STREAM_NOT_LIVE` | Stream is not live. | Force-stop on a non-LIVE stream | POST /api/v1/admin/streams/{id}/force-stop |

### 1.96 `admin/content`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `AUTHOR_SCOPE_REQUIRED` | authorId is required — posts are partitioned by author. Use search or reports to locate a post id directly. | Post browse without ?authorId | GET /api/v1/admin/content/posts |
| 404 | `POST/COMMENT/STORY/HIGHLIGHTITEM_NOT_FOUND` | Post not found with id: {id} / Comment not found with id: {id} / Story not found with id: {id} / HighlightItem not found with storyId: {storyId} | Missing target entity | /api/v1/admin/content/** |
| 400 | `POST_NOT_REMOVED` | Only REMOVED posts can be restored. | Restore on a non-REMOVED post | POST /api/v1/admin/content/posts/{postId}/restore |

### 1.97 `admin/feed`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_KNOB` | maxAuthorRun must be 1-10. / Knob values must be finite, non-negative and ≤ 1000. | knob out of bounds, NaN or infinite | PATCH /api/v1/admin/feed/config; POST /preview |
| 400 | `INVALID_ROLLOUT` | rolloutPercent must be 0-100. | rollout outside 0..100 | PATCH /api/v1/admin/feed/config |
| 400 | `MISSING_USER` | userId is required. | preview without a userId | POST /api/v1/admin/feed/preview |

### 1.98 `admin/impersonation`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `IMPERSONATION_REASON_REQUIRED` | A reason of at least 10 characters is required to impersonate. | Start impersonation with short/missing reason | impersonation start endpoint |
| 403 | `IMPERSONATION_TARGET_ADMIN` | Admins cannot be impersonated. | Target has ADMIN role | impersonation start endpoint |
| 400 | `SELF_ACTION_FORBIDDEN` | You cannot impersonate yourself. | Target == caller | impersonation start endpoint |

### 1.99 `admin/knowledge`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `DUPLICATE_VOCAB_NAME` | A topic named '{nameEn}' already exists. — and — A madhhab named '{nameEn}' already exists. | Case-insensitive nameEn collision on add/edit | POST/PATCH /api/v1/admin/knowledge/topics[/{id}], /api/v1/admin/knowledge/madhhabs[/{id}] |
| 404 | `TOPIC/MADHHAB_NOT_FOUND` | Topic not found with id: {id} / Madhhab not found with id: {id} | Missing vocab row | /api/v1/admin/knowledge/** |

### 1.100 `admin/logs`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_RULE_KIND` | Unknown rule kind. Allowed: [FAILED_LOGIN_PER_ACCOUNT, FAILED_LOGIN_PER_IP, REPORT_PILE_ON, DLQ_ARRIVALS, OTP_ABUSE, PURGE_JOB_SILENCE] | alert-rule create/update with bad kind | POST/PATCH /api/v1/admin/logs/alerts |
| 400 | `INVALID_SEVERITY` | Unknown severity. Allowed: INFO, MEDIUM, HIGH. | alert-rule with bad severity | POST/PATCH /api/v1/admin/logs/alerts |

### 1.101 `admin/media`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `ASSET_NOT_RETRYABLE` | Only failed (or stuck PROCESSING) assets can be reprocessed. | Reprocess on asset not in FAILED_*/PROCESSING | POST /api/v1/admin/media/{assetId}/reprocess |
| 400 | `INVALID_QUOTA` | Quota values must be positive — use enabled=false to lift a quota. | dailyUploads or dailyBytes < 1 | PUT /api/v1/admin/media/quotas/{role} |
| 400 | `INVALID_ROLE` | Unknown role. Allowed: [USER, RESEARCHER, SCHOLAR, MODERATOR, SUPPORT, ANALYST, ADMIN] | quota PUT with unparseable role | PUT /api/v1/admin/media/quotas/{role} |
| 400 | `INVALID_STATUS / INVALID_TYPE` | Unknown status. Allowed: {MediaStatus values array} — and — Unknown type. Allowed: {MediaAssetType values array} | Unparseable filters | GET /api/v1/admin/media |
| 404 | `MEDIAASSET_NOT_FOUND` | MediaAsset not found with id: {assetId} | Missing asset | /api/v1/admin/media/** |
| 400 | `MEDIA_RAW_MISSING` | The raw original is gone — this asset can no longer be reprocessed. | raw/{assetId} object missing from storage | POST /api/v1/admin/media/{assetId}/reprocess |

### 1.102 `admin/moderation (bulk)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_BULK_ACTION` | Unknown action. Allowed: TAKEDOWN, RESTORE, DELETE, SOUND_APPROVE, SOUND_REJECT. | Unknown bulk action (also per-target error row) | POST /api/v1/admin/moderation/bulk |
| 400 | `INVALID_BULK_TARGET` | This action needs a {POST\|SOUND} target. — and — DELETE supports COMMENT and STORY targets. | Bulk target type mismatch (per-target: surfaces as {outcome:"error", error:"..."} row, not a top-level failure) | POST /api/v1/admin/moderation/bulk |

### 1.103 `admin/moderation (keywords)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `CONTENT_BLOCKED_BY_POLICY` | This content violates platform policy and cannot be published. | END-USER facing: content-create text matches a BLOCK-severity platform keyword (fail-open on infra errors; FLAG severity records a hit silently) | content-create endpoints (posts/comments/etc. via PlatformKeywordService.blockOrFlag) |
| 400 | `INVALID_INPUT` | keyword is required | Blank keyword on blocklist add | POST /api/v1/admin/content/blocklist |
| 400 | `INVALID_KEYWORD` | Keyword normalizes to nothing. | Keyword empty after normalization | POST /api/v1/admin/content/blocklist |

### 1.104 `admin/notification`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `ANNOUNCEMENT_NOT_SCHEDULED` | Only SCHEDULED announcements can be cancelled (this one is {status}). | cancel of a sent/sending/cancelled announcement | DELETE /api/v1/admin/notifications/announcements/{id} |
| 400 | `INVALID_INPUT` | title and body are required. | Blank announcement title/body | POST /api/v1/admin/notifications/announcements |
| 400 | `INVALID_LANGUAGE` | Unknown language code. Allowed: [EN, AR, ...Language enum] | bad audienceLanguage | POST /api/v1/admin/notifications/announcements |
| 400 | `INVALID_SCHEDULE` | scheduledAt is in the past — omit it to send immediately. / scheduledAt must be ISO-8601 local date-time, e.g. 2026-08-07T09:00:00. | past or unparseable scheduledAt | POST /api/v1/admin/notifications/announcements |
| 400 | `LARGE_AUDIENCE_CONFIRMATION_REQUIRED` | This announcement targets {audience} of {total} users — set confirmLargeAudience=true to proceed. | Fat-finger guard: audience ≥ half the platform without confirmation flag | POST /api/v1/admin/notifications/announcements |

### 1.105 `admin/ops`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `DLQ_NOT_PARKED` | Only PARKED dead letters can be requeued (this one is {status}). | requeue of an already-requeued/discarded dead letter | POST /api/v1/admin/ops/queues/dlq/{id}/requeue |
| 400 | `INVALID_PREFIX` | Prefix too short — refusing a near-global flush. | Redis flush prefix under 3 chars | DELETE /api/v1/admin/ops/redis/keys |
| 400 | `INVALID_STATUS` | Unknown status. Allowed: PARKED, REQUEUED, DISCARDED. | bad status filter on DLQ browse | GET /api/v1/admin/ops/queues/dlq |
| 400 | `JOB_LOCKED` | A run of this job is already in progress. | Redis lock ops:job-lock:{jobKey} already held (10-min TTL) | POST /api/v1/admin/ops/jobs/{jobKey}/run |
| 400 | `JOB_NOT_PAUSABLE` | Job not pausable. Pausable: [retention-sweep, log-alert-sweep, analytics-daily-rollup, analytics-weekly-cohorts, analytics-anomaly-scan, trending-rebuild, trending-digest, notification-cleanup, account-purge, research-scheduled-publish] | pause/resume on a job outside the pausable set | POST /api/v1/admin/ops/jobs/{jobKey}/pause\|resume |
| 400 | `JOB_NOT_TRIGGERABLE` | Job not triggerable. Allowed: [trending-rebuild, trending-digest, account-purge, notification-cleanup, research-scheduled-publish, calls-sweep-missed, chat-scheduled-messages] — internal fallback: Unhandled job. | Manual trigger of a non-whitelisted job | POST /api/v1/admin/ops/jobs/{jobKey}/run |
| 400 | `JOB_PAUSED` | Job '{key}' is paused — resume it first (POST /api/v1/admin/ops/jobs/{key}/resume). | manual trigger of a paused job | POST /api/v1/admin/ops/jobs/{jobKey}/run |
| 400 | `PREFIX_FORBIDDEN` | Prefix '{p}' covers auth/abuse state and can never be flushed from the admin panel. | prefix overlaps sid:/stepup:/otp:/rl:/ops:job- | DELETE /api/v1/admin/ops/redis/keys |
| 400 | `PREFIX_NOT_ALLOWED` | Prefix not in the allowlist. Flushable: [irc:search:top:, irc:search:zero:, user-profile, settings:, chat:presence:, email-ctx:, trending:] | prefix outside the allowlist | DELETE /api/v1/admin/ops/redis/keys |
| 400 | `QUEUE_UNAVAILABLE` | RabbitMQ is unavailable — cannot requeue. | RabbitTemplate bean absent (broker down at boot) | POST /api/v1/admin/ops/queues/dlq/{id}/requeue |

### 1.106 `admin/qna`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 404 | `ANSWER_NOT_FOUND` | Answer not found with id: {answerId} | Missing/deleted answer | DELETE /api/v1/admin/qna/answers/{answerId} |
| 400 | `INVALID_STATUS` | Unknown status. Allowed: {QuestionStatus values array} | Unparseable ?status= | GET /api/v1/admin/qna/questions |

### 1.107 `admin/research`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_INPUT` | type is required (PLAGIARISM or QUALITY). | Flag body missing type | POST /api/v1/admin/research/{id}/flags |
| 400 | `INVALID_SORT` | Unknown 'by'. Allowed: downloads, citations. | ?by= not downloads/citations | GET /api/v1/admin/research/top |
| 400 | `INVALID_STATUS` | Unknown status. Allowed: {ResearchStatus values array} | Unparseable ?status= | GET /api/v1/admin/research |
| 404 | `RESEARCH_NOT_FOUND / RESEARCHFLAG_NOT_FOUND` | Research not found with id: {id} / ResearchFlag not found with id: {flagId} | Missing/deleted research or flag | /api/v1/admin/research/** |

### 1.108 `admin/safety`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_STATE` | Unknown state. Allowed: {ReportState values array} | Unparseable ?state= | GET /api/v1/admin/safety/reports |
| 404 | `REPORT/USERSTRIKE_NOT_FOUND` | Report not found with id: {id} / UserStrike not found with id: {id} | Missing report/strike | /api/v1/admin/safety/** |

### 1.109 `admin/safety (reports)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_RESOLUTION` | resolution is required (WARNING_ISSUED, CONTENT_REMOVED, ACCOUNT_SUSPENDED, NO_ACTION). — parse variant: Unknown resolution. Allowed: {Resolution values array} | Missing/NONE or unparseable resolution on action | POST /api/v1/admin/safety/reports/{id}/action |
| 409 | `REPORT_STATE_INVALID` | Cannot {triage\|dismiss\|action\|uphold\|reverse} a report in state {STATE}. | State-machine guard (triage only from SUBMITTED; action/dismiss from SUBMITTED\|TRIAGED; uphold/reverse only from APPEALED) | POST /api/v1/admin/safety/reports/{id}/*, /api/v1/admin/safety/appeals/{id}/* |
| 400 | `STRIKE_TARGET_AMBIGUOUS` | issueStrike inline is only supported for USER-target reports; use POST /api/v1/admin/safety/users/{userId}/strikes for content authors. | issueStrike=true on a non-USER-target report | POST /api/v1/admin/safety/reports/{id}/action |

### 1.110 `admin/safety (strikes)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 409 | `STRIKE_NOT_ACTIVE` | Strike is not active. | Revoking an already-expired strike | DELETE /api/v1/admin/safety/strikes/{strikeId} |

### 1.111 `admin/sound`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_IMPORT_BATCH` | Provide 1–100 items. | Official import with empty or >100 items | POST /api/v1/admin/sounds/import |
| 400 | `INVALID_IMPORT_ITEM` | Each item needs title and audioUrl. | Import item missing title/audioUrl | POST /api/v1/admin/sounds/import |
| 400 | `INVALID_STATUS / INVALID_CATEGORY` | Unknown status. Allowed: {SoundStatus values array} — and — Unknown category. Allowed: {SoundCategory values array} | Unparseable status/category | /api/v1/admin/sounds/** |
| 404 | `SOUND_NOT_FOUND` | Sound not found with id: {soundId} | Missing sound | /api/v1/admin/sounds/** |

### 1.112 `admin/support (step-up)`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `STEP_UP_REQUIRED` | This action requires you to confirm your identity. | Any @RequiresStepUp admin handler called without a recent step-up (re-auth) marker | all step-up-gated /api/v1/admin/** mutations |

### 1.113 `admin/trending`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `INVALID_SCOPE` | Unknown scope. Allowed: [ALL, QUESTION, RESEARCH, POST, REEL] | Unknown override scope | POST /api/v1/admin/trending/overrides |
| 400 | `INVALID_TAG` | Invalid tag. | Tag blank or >100 chars after normalization (# stripped, lowercased) | POST /api/v1/admin/trending/overrides |
| 400 | `INVALID_TYPE` | Unknown type. Allowed: PIN, BAN. | Unparseable override type | POST /api/v1/admin/trending/overrides |
| 404 | `TRENDINGTAGOVERRIDE_NOT_FOUND` | TrendingTagOverride not found with id: {id} | Missing override | DELETE /api/v1/admin/trending/overrides/{id} |

### 1.114 `admin/user`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `CANNOT_ACT_ON_ADMIN` | Bulk actions cannot target ADMIN accounts — use the individual endpoints. | bulk action list contains an admin | POST /api/v1/admin/users/bulk |

### 1.115 `activity`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 403 | `ACCESS_FORBIDDEN` | You cannot delete another user's activity | DELETE of an activity row whose userId != caller (UserActivityServiceImpl:138) | DELETE /api/v1/users/me/activity/{activityId} |

### 1.116 `audit`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 400 | `—` | (empty body — ResponseEntity.badRequest().build()) | GET /api/v1/admin/audit without required ?userId (Cassandra needs a partition scope) | GET /api/v1/admin/audit |
| 401 | `AUTH_UNAUTHORIZED` | Admin authentication required | SSE stream subscribe without an authenticated principal | GET /api/v1/admin/audit/stream |

### 1.117 `email/EmailPreferencesController`

| HTTP | Code | Message | Trigger | Surface |
|---|---|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | Authentication required | No/invalid principal or user not active | all /api/v1/users/me/email-preferences endpoints |

---

## 2. Inline response notes & warnings

Successful (2xx) responses that still carry a caveat the client should surface or log.

| Kind | Message | When | Surface |
|---|---|---|---|
| NOTE | Authentication required. Pass access token as ?token=<jwt>. | SSE stream opened without resolvable user (plain-text body written directly; bypasses JSON envelope because response is text/event-stream) | GET /api/v1/notifications/stream |
| NOTE | "{actor} created the group" \| "{actor} changed the group name to \"{title}\"" \| "{actor} changed the group description" \| "{actor} changed the group photo" \| "{actor} turned off disappearing messages" \| "{actor} set disappearing messages to {Nd\|Nh\|Nm\|Ns}" \| "{actor} added {user}" \| "{actor} removed {user}" \| "{actor} made {user} an admin" \| "{actor} removed {user} as admin" \| "{user} left" \| "{actor} transferred ownership to {user}" \| "{user} joined via invite link" \| "{user} joined" (join-request approved) \| "{user} pinned a message" | group/channel lifecycle events; written by SystemMessageService as MessageType.SYSTEM rows with systemEvent enum (GROUP_CREATED, TITLE_CHANGED, DESCRIPTION_CHANGED, AVATAR_CHANGED, DISAPPEARING_CHANGED, MEMBER_ADDED, MEMBER_REMOVED, ROLE_CHANGED, MEMBER_LEFT, OWNERSHIP_TRANSFERRED, PINNED); actor labels are @username | conversation timeline (inline SYSTEM messages), inbox preview, and MESSAGE_NEW SSE event on /api/v1/messaging/stream |
| NOTE | Rejected by moderation scan. | async worker: MediaScanner flags the upload | GET /api/v1/media/{id} → MediaStatusResponse.errorMessage |
| NOTE | {raw exception message, truncated to 300 chars} — e.g. "ffmpeg timed out", "ffmpeg exit {code}", storage errors | async worker: unexpected processing/upload exception (video transcode failures normally fall back to passthrough first) | GET /api/v1/media/{id} → MediaStatusResponse.errorMessage |
| NOTE | Image exceeds the {N} MP decode limit. | async worker: decompression-bomb guard (default N=100 megapixels) | GET /api/v1/media/{id} → MediaStatusResponse.errorMessage |
| NOTE | Unsupported or corrupt image. | async worker: image bytes not decodable | GET /api/v1/media/{id} → MediaStatusResponse.errorMessage |
| NOTE | All bean-validation messages above are delivered as field-level errors inside the standard ApiErrorResponse envelope (status/error/message/path/errorCode/traceId + fieldErrors[]); AppException subclasses map BadRequest=400, Unauthorized=401, Forbidden=403, ResourceNotFound=404, Conflict/Duplicate=409, RateLimitExceeded=429 with the codes listed per entry. | any validation failure or thrown AppException in the modules above | all JSON endpoints |
| NOTE | Class→status/code map used below: BadRequestException=400 BAD_REQUEST (or custom code), UnauthorizedException=401 AUTH_UNAUTHORIZED, ForbiddenException=403 ACCESS_FORBIDDEN (or custom code), java.lang.SecurityException=403 FORBIDDEN (raw message; blank→'You do not have permission to perform this action.'), ResourceNotFoundException=404 '{Resource} not found with {field}: {value}' code {RESOURCE}_NOT_FOUND, ConflictException=409 RESOURCE_CONFLICT, DuplicateResourceException=409 '{Resource} already exists with {field}: {value}' code {RESOURCE}_DUPLICATE, RateLimitExceededException=429 RATE_LIMITED, IllegalArgumentException=400 ILLEGAL_ARGUMENT, IllegalStateException=500 ILLEGAL_STATE ('An unexpected state was encountered. Please try again or contact support.'). All render in the ApiErrorResponse envelope {status,error,message,path,errorCode,traceId} | any thrown exception in scope | all REST endpoints |
| NOTE | (body-less response) | HttpMediaTypeNotAcceptableException — client demanded unproducible representation; writing JSON would re-trigger negotiation failure | all endpoints |
| NOTE | Exception-class → status/default-code map: BadRequestException=400/BAD_REQUEST; UnauthorizedException=401/AUTH_UNAUTHORIZED; ForbiddenException=403/ACCESS_FORBIDDEN; ResourceNotFoundException=404/RESOURCE_NOT_FOUND or {RESOURCE}_NOT_FOUND with text '%s not found with %s: %s' and details={resource,field,value}; ConflictException=409/RESOURCE_CONFLICT; DuplicateResourceException=409/{RESOURCE}_DUPLICATE or RESOURCE_DUPLICATE with text '%s already exists with %s: %s'; RateLimitExceededException=429/RATE_LIMITED with text 'Too many {action} requests — please slow down' and details={action, retryAfterSeconds}. | Thrown by any service; AppException handler renders message + errorCode + details verbatim | all endpoints |
| NOTE | ApiErrorResponse shape: { timestamp (UTC LocalDateTime), status (int), error (reason phrase), message, path, errorCode, details (map, optional), fieldErrors: [{field, message, rejectedValue}] (validation only), traceId }. @JsonInclude(NON_NULL) — null fields omitted. Every error, including container-level /error dispatches (ApiErrorController), uses this exact envelope. | Any error response platform-wide | all endpoints |
| NOTE | Admin reindex endpoints return count summaries (ReindexSummary/ReindexResult) with no message text; non-admin callers get the standard 403 envelope from security, not a module message | POST reindex operations | POST /api/v1/admin/search/{research\|posts\|questions\|users\|channels\|answers\|sounds}/reindex |
| NOTE | nextCursor: "" (empty string) when no further page exists; cursor-mode only | cursor pagination exhausted | GET /api/v1/search?cursor=... |
| NOTE | "degraded": true (JSON boolean in the response envelope; false on success and on legitimately-empty missing-index results) | Elasticsearch query failed after one stale-connection retry — results list is empty but the request still returns 200 | GET /api/v1/search (offset and cursor modes) |
| NOTE | No user-facing message strings: unknown types in ?types/scope params are silently skipped/normalized; /api/v1/admin/tags/merge returns 400 with an EMPTY body when from==to, and its success body carries "truncated": true when the 5000-row cap was hit (re-run needed) | tag browse/trending/usage/search + admin backfill/hide/unhide/merge | GET /api/v1/tags/**, POST /api/v1/admin/tags/** |
| NOTE | note: "hard cap 10k rows per call — repeat for heavier histories" (alongside deleted count) | Activity-erase response | POST /api/v1/admin/users/{userId}/activity/erase |
| NOTE | Milestones are set-once from user_first_events; users who signed up before the funnel tracker deployed only accrue milestones going forward. | always on the funnel response | GET /api/v1/admin/analytics/funnel |
| NOTE | Shadow-scored twice over the live candidate set — nothing was persisted; the two fetches may see slightly different candidates if content landed in between. | always on the preview response | POST /api/v1/admin/feed/preview |
| NOTE | content note: "posts/stories have no historical date bucket — postCreatesPerDay is collector-sourced and starts at collector deployment." — engagement source: "analytics_metric_daily + analytics_dau_by_day — the parallel fan-in collector; the private per-user activity store is never scanned (analytics-kpis.md §12 privacy contract). Series begin at collector deployment." | Always in respective bodies | GET /api/v1/admin/analytics/content, GET /api/v1/admin/analytics/engagement |
| NOTE | Projection guarantees stated as behavior (absence-of-data messages): channel/conversation rows never include last_message_preview or message bodies; stream projections never include stream_key/publish_key (rotate-key returns 204, no body); invite-link listing exposes token hashes only. | All admin chat/channel/stream reads | /api/v1/admin/channels/**, /api/v1/admin/chat/**, /api/v1/admin/streams/** |
| WARNING | This export contains private message content released under legal hold {reason}. Handle per your evidence-retention policy; the hold is now EXECUTED and cannot be re-run. | every successful execute | POST /api/v1/admin/chat/legal-holds/{id}/execute |
| NOTE | notes: ["Hashes are SHA-256 of phone/email — the server never sees raw contacts.", "Identity-hash backfill makes every active account matchable-by-default; discovery flags gate whether a match is surfaced.", "The contact:sync rate limit (3/24h) FAILS OPEN if Redis is down."] | Always in contact-sync stats body | GET /api/v1/admin/discovery/contact-sync/stats |
| WARNING | knownSeam: "QR-resolve does not yet consult discover.byQr — a rotated flag does not invalidate resolves; rotation (below) does." | Always in discovery-flags body | GET /api/v1/admin/users/{userId}/discovery |
| NOTE | An orphan is a bucket object with no media_renditions.object_key row and no parseable raw/{assetId}. raw/ objects inside their 7-day retention are never flagged. | always on the reconcile response | POST /api/v1/admin/media/reconcile |
| NOTE | Asset errorMessage set to "Abandoned upload intent purged by admin sweep." (visible in the asset's errorMessage field afterwards) | raw-purge sweep flips abandoned PENDING intents to FAILED_VALIDATION | POST /api/v1/admin/media/purge-raw/run |
| NOTE | note: "Aggregated from the legacy Postgres inbox — the live write path is Cassandra (notifications_by_user), which has no cross-user aggregate; a rollup collector is the planned successor source." | Always in stats body | GET /api/v1/admin/notifications/stats |
| NOTE | per-row note: "no NotificationKind — legacy/relational-only type" | NotificationType with no matching NotificationKind in the types registry | GET /api/v1/admin/notifications/types |
| NOTE | If the consumer still can't process it, it will re-park as a NEW row. / Row kept for the audit trail; the retention sweep prunes it at 90 days. | requeue / discard responses | DLQ requeue + discard |
| WARNING | Scheduled runs are suppressed until resumed — pausing retention/GDPR sweeps defers legally-relevant deletion work. | every successful pause | POST /api/v1/admin/ops/jobs/{jobKey}/pause |
| NOTE | queues note: "Dead letters park in the dead_letters table (browse via GET /queues/dlq) — a nonzero dead-letter QUEUE depth means the drain consumer itself is down." — degraded variant: note: "RabbitAdmin unavailable" | Always in queues body / when RabbitAdmin bean absent | GET /api/v1/admin/ops/queues |
| WARNING | ⚠ CRITICAL: permit-all is ON — every gate incl. /api/v1/admin/** is open | app.security.permit-all=true; field is null otherwise | GET /api/v1/admin/ops/config |
| NOTE | Per-dependency {status: UP\|DOWN\|DEGRADED\|UNKNOWN\|DISABLED, latencyMs, error?}. rabbitmq degraded: {status:"UNKNOWN", note:"RabbitAdmin unavailable"}; r2 unconfigured: {status:"DISABLED", note:"R2 credentials not configured"}; mail: {status: UP\|DISABLED}; queue errors render as "error: {message}" / "missing". | Composite dependency probe | GET /api/v1/admin/ops/health |
| NOTE | Per-swept-stream reason: "LIVE beyond {maxAgeHours}h" or "no publisher session past grace"; media-plane fields render "unreachable" when MediaMTX is down. | Orphaned-LIVE sweep / media-plane view | POST /api/v1/admin/ops/streams/sweep-orphans, GET /api/v1/admin/ops/media-plane |
| NOTE | Redis irc:search:top:* — anonymous counts, 8-day retention; no user ids are ever recorded (§12). / Degraded searches (ES down) are excluded — a zero here means ES answered and genuinely had nothing. | top-queries / zero-results responses | GET /api/v1/admin/search/analytics/* |
| NOTE | drift = esDocs - canonicalRows. Positive: stale docs (deleted rows still indexed). Negative: missing docs (index behind). Small transient drift is normal; persistent drift → run the reindex hook. | always on the health response | GET /api/v1/admin/search/health |
| NOTE | note: "sequential; poll GET /api/v1/admin/ops/jobs/search-reindex-all/runs" (with jobId + jobName search-reindex-all) | Async reindex-all accepted | POST /api/v1/admin/search/reindex-all |
| NOTE | Detailed rows capped at {cap} of {n} recordings; fleetBytes still covers everything. | recordings fleet larger than the detail cap | GET /api/v1/admin/streams/recordings |
| NOTE | SSE event names on the audit stream: "connected" (on subscribe), "audit" (each AuditLogResponse row), "heartbeat" ({timestamp}). AuditLogResponse fields: auditId, userId, username, operation (CREATE/READ/UPDATE/DELETE/OTHER), outcome, resourceType, resourceId, httpMethod, path, queryString, statusCode, durationMs, ipAddress, userAgent, summary, errorCode, createdAt. Stream subscribes are themselves audited as ADMIN_AUDIT_STREAM_SUBSCRIBE. | Audit read/stream endpoints; readable by ADMIN/MODERATOR/SUPPORT/ANALYST | GET /api/v1/admin/audit[/users/{userId}\|/resources/{type}/{id}\|/stream] |
| NOTE | Error envelope facts: BadRequestException→400 code default BAD_REQUEST; UnauthorizedException→401 AUTH_UNAUTHORIZED; ForbiddenException→403 ACCESS_FORBIDDEN; ResourceNotFoundException→404 text "{Resource} not found with {field}: {value}" code {RESOURCE}_NOT_FOUND; DuplicateResourceException→409 text "{Resource} already exists with {field}: {value}" code {RESOURCE}_DUPLICATE; ConflictException→409 RESOURCE_CONFLICT; RateLimitExceededException→429 RATE_LIMITED. Key source files: research/service/impl/ResearchServiceImpl.java, qna/service/impl/QuestionServiceImpl.java, qna/controller/QuestionController.java, media/service/MediaAssetService.java + MediaProcessingService.java + ImageProcessor.java, research/service/impl/CloudflareR2StorageService.java + NoOpS3StorageService.java, common/search/controller/GlobalSearchController.java, common/service/SocialGuard.java, common/cache/RateLimiter.java (all under /Users/khi/Desktop/irc/src/main/java/ak/dev/irc/app/) | reference | catalog metadata |
| NOTE | Test response: {"queued": true, "to": "{email}"} or {"queued": false, "reason": "no email on account"}. Unsubscribe-all response: {"emailNotificationsEnabled": false}. GET/PATCH return {master, social, mentions, system, trending} booleans; PATCH is partial (omitted fields keep value) and evicts the 60s email-context cache. | Email-preferences endpoints | GET/PATCH /api/v1/users/me/email-preferences, POST .../test, POST .../unsubscribe-all |
| NOTE | Throttle behavior: max ONE email per (recipientId, dedupeKey) per irc.email.throttle-minutes window (default 60 min; min 1). dedupeKey = "{type}:{resourceId}" when both present, else the notification id. Implemented as Redis SET NX EX on key irc:email:{userId}:{dedupeKey}; FAIL-OPEN if Redis is down (duplicate email preferred over dropped alert). Dispatch order: service-enabled check → throttle → cached recipient context (60s Redis) → per-user pref gates → send. Pref gates: master emailNotificationsEnabled; TRENDING_DIGEST gated solely by emailTrendingEnabled; MENTIONS→emailMentionsEnabled; SYSTEM→emailSystemEnabled; SOCIAL/POSTS/QNA/RESEARCH/CHAT→emailSocialEnabled (chat kinds are emailEligible=false, never actually emailed). Every decision writes an EmailSendLog outcome: QUEUED, THROTTLED, DISABLED ("email service off"), MUTED, NO_ADDRESS, SKIPPED ("recipient inactive"), FAILED. SMTP/Resend send: 3 attempts, exponential backoff from 500ms, then dropped (in-app notification remains). | Every NotificationPushedEvent | email pipeline |
| NOTE | No user-facing messages — read-only vocabulary lists (topics, madhhabs) with no throws or validation | n/a | GET /api/v1/topics, GET /api/v1/madhhabs |
| NOTE | Silent by design (no notification): UserUnfollowed (removes the earlier NEW_FOLLOWER row), UserBlocked, UserUnblocked (would reveal the prior block), PostUnreacted, PostDeleted, PostCommentDeleted, QuestionDeleted, AnswerDeleted, AnswerUnreacted. Also suppressed: all self-actions (reacting/commenting/sharing/answering own content), and any event where the recipient has RESTRICTED the actor (IG-style silent restriction — activity recorded, no ping). | Event families bound to irc.queue.notifications | in-app notifications |
| NOTE | No error strings are pushed inside SSE streams in scope — streams emit only data events: chat SSE 'connected'/'heartbeat'/typed chat events; post stream 'connected'/'heartbeat'+post events; story stream 'connected' ({"storyId":...})/'heartbeat' (data 'ping'); story tray 'connected'/'heartbeat'; activity stream 'connected'/'heartbeat'. Failures close the emitter (completeWithError) with no message body. The only textual realtime error is the 401 plain-text body on /api/v1/messaging/stream listed above | n/a | all SSE endpoints in scope |

---

## 3. In-app notifications

Rows in the notification inbox (and their SSE push). Title/body templates; `{braces}` interpolate at send time.

| Type / group key | Title & body | Trigger | Delivery |
|---|---|---|---|
| `SYSTEM_MESSAGE` | title: "Account warning" — body: "A moderation strike was recorded on your account: {reason}. Strikes expire automatically after 90 days." | admin issues a moderation strike | in-app notification, after POST /api/v1/admin/users/{userId}/strikes |
| `SYSTEM_MESSAGE` | title: "Two-factor authentication was reset" — body: "An administrator reset the two-factor authentication on your account. If you did not request this, contact support immediately." | admin 2FA reset | in-app notification, after POST /api/v1/admin/users/{userId}/2fa/reset |
| `SYSTEM_MESSAGE` | title: "Your account has been disabled" — body: "Your account was disabled by an administrator. Contact support if you believe this is a mistake." | admin disables the account | in-app notification, after POST /api/v1/admin/users/{userId}/disable |
| `SYSTEM_MESSAGE` | title: "Your account has been re-enabled" — body: "Your account was re-enabled by an administrator. You can log in again." | admin re-enables the account | in-app notification, after POST /api/v1/admin/users/{userId}/enable |
| `SYSTEM_MESSAGE` | title: "Your password was reset" — body: "An administrator reset your password and signed out all devices. If you did not expect this, contact support immediately." | admin password reset (all sessions revoked) | in-app notification, after POST /api/v1/admin/users/{userId}/password/reset |
| `NEW_FOLLOWER` | title: "New follower" — body: "{actorFullName} (@{actorUsername}) started following you." | someone follows the user | in-app notification + SSE + (pref-dependent) email |
| `UNBLOCKED` | title: "You have been unblocked" — body: "{actorFullName} (@{actorUsername}) unblocked you." | a user unblocks the recipient | in-app notification + SSE |
| `SYSTEM_MESSAGE` | title: {title} — body: {body} (caller-supplied; generic SYSTEM_MESSAGE carrier used by admin actions and login alerts) | any sendSystemNotification(userId, title, body) call; throws 404 USER_NOT_FOUND if recipient inactive | in-app notification + SSE |
| `SYSTEM_MESSAGE` | title: "New sign-in to your account" — body: "A new device signed in from {ip}. If this wasn't you, secure your account now." (the " from {ip}" fragment is omitted when IP is unknown) | successful login from an IP never seen before for this user (and user has prior known IPs) | in-app notification (sendSystemNotification) after POST /api/v1/auth/login |
| `—` | Your IRC verification code is {code}. It expires in {ttl/60} minutes. | OTP issued for login/phone-verify | SMS to the target phone number |
| `POST_COMMENTED` | title: "New comment on your post" — body: "{actor} commented: \"{preview}\"" | comment created on your post; aggregates POST_COMMENTED:{postId} | in-app bell + notification SSE |
| `POST_COMMENT_REACTED` | title: "Someone liked your comment" — body: "{actor} liked your comment" | LIKE on your comment | in-app bell + notification SSE |
| `POST_COMMENT_REPLIED` | title: "Someone replied to your comment" — body: "{actor} replied: \"{preview}\"" | reply under your comment; aggregates POST_COMMENT_REPLIED:{parentCommentId} | in-app bell + notification SSE |
| `POST_NEW` | title: "{author} posted" — body: {preview} or "{author} just posted." | followee publishes a post; fanned out to followers during home-feed fanout; groupKey POST_NEW:{postId}; explicitly emailEligible=false | in-app bell + notification SSE only |
| `POST_REACTED` | title: "Someone liked your post" — body: "{actor} liked your post" | LIKE toggled on on your post (self-suppressed, block-filtered); aggregates per post POST_REACTED:{postId} | in-app bell + notification SSE; email-eligible per user prefs |
| `POST_SHARED` | title: "Your post was shared" — body: "{actor} shared your post" | your post shared/reposted; aggregates POST_SHARED:{postId} | in-app bell + notification SSE |
| `USER_MENTIONED` | title: "You were mentioned" — body: "{author} mentioned you: \"{preview}\"" | @username in a post; batch-resolved, one notification per mentioned user; actor fallback "Someone" | in-app bell + notification SSE |
| `RESEARCH_CONTRIBUTOR_ADDED` | title: "You were added to a research paper" — body: "{ownerFullName} (@{ownerUsername}) added you as a {role} on \"{researchTitle}\"." (role lowercased, underscores → spaces; title falls back to "a research paper") | user added as contributor (create, add, replace-list paths); never self-notified; dispatch failure never rolls back the add | in-app notification via NotificationDispatcher (+ email per user prefs) |
| `ADDED_TO_GROUP` | title: "Added to a group" — body: "{actor} added you to \"{groupTitle}\"" (fallback: "a group") | being added to a group at create or via addMembers | in-app bell + notification SSE |
| `CALL_MISSED` | title: "Missed call" — body: "You missed a {video\|voice} call from @{caller}" (fallback actor "Someone") | call rang out or caller hung up while ringing; aggregates per conversation ("3 missed calls") | in-app bell + notification SSE |
| `CHANNEL_JOIN_APPROVED` | title: "Request approved" — body: "You joined \"{channelTitle}\"" (fallback "the channel") | admin approves the user's join request | in-app bell + notification SSE |
| `CHANNEL_JOIN_REQUEST` | title: "Join request" — body: "{requester} requested to join \"{channelTitle}\"" (fallback "your channel") | join request created on an approval-required channel; sent to admins who can approve | in-app bell + notification SSE |
| `CHANNEL_NEW_POST` | title: "New channel post" — body: "{channelTitle}: {preview}" or "{channelTitle} published a new post" (fallback label "A channel you follow") | channel post published; fan-out per subscriber, mute-aware; aggregates per channel via CHANNEL_NEW_POST:{channelId} | in-app bell + notification SSE |
| `MESSAGE_MENTION` | title: "You were mentioned" — body: "{sender} mentioned you in \"{channelTitle}\"\|in a channel\|in a chat: {preview}" (truncated 160) | @-mention in a chat message or channel post; cuts through mute; one row per mention (not aggregated); mentioned user excluded from generic NEW_MESSAGE/CHANNEL_NEW_POST | in-app bell + notification SSE |
| `MESSAGE_REQUEST` | title: "Message request" — body: "{requester} wants to send you a message" | a stranger's first message lands as a request | in-app bell + notification SSE |
| `NEW_MESSAGE` | title: "New message" — body: "{sender}: {preview}" (truncated to 160 chars with …) | message sent to an offline/backgrounded recipient; honors mute; aggregates per conversation via groupKey NEW_MESSAGE:{conversationId} | in-app bell + notification SSE ({event:"notification"} envelope); in-app only (emailEligible=false) |
| `STREAM_STARTED` | title: "{hostLabel} is live" — body: {stream title} or "Tap to watch the stream." | user goes live; fanned out to followers (keyset-paged, capped) by LiveStreamFanoutService; groupKey STREAM_STARTED:{streamId}; also emits stream.started realtime event | in-app bell + notification SSE + chat SSE stream.started |
| `ADMIN_ANOMALY` | Title: 'Metric anomaly: {metric}'. Body: "{WARN\|ALERT}: metric '{metric}' was {value} on {date} vs a 28d mean of {mean}." or "Metric '{metric}' flatlined at 0 on {date} (28d mean {mean}) — pipeline-dead detector." | nightly AnomalyScanJob z-score breach or zero-flatline; one per (day, metric) via ANOMALY:{day}:{metric} group key | in-app + email to every active ADMIN |
| `SYSTEM_MESSAGE` | Takedown — Title: "Your channel was taken down", Body: "Your channel \"{title}\" was removed by platform moderation[ ({reason})]." Restore — Title: "Your channel was restored", Body: "Your channel \"{title}\" has been restored." | Admin channel takedown/restore → channel owner | POST /api/v1/admin/channels/{id}/takedown\|restore → in-app notification |
| `SYSTEM_MESSAGE` | Force-stop — Title: "Your live stream was ended by moderation", Body: "Your live stream \"{title}\" was ended by platform moderation[ ({reason})]." Key rotation — Title: "Your stream key was rotated", Body: "For security, the stream key of \"{title}\" was rotated. Your new key: {newKey}" (the new key is delivered ONLY via this notification; the API returns 204 with no body) | Admin force-stop / rotate-key → stream host | POST /api/v1/admin/streams/{id}/force-stop\|rotate-key → in-app notification |
| `SYSTEM_MESSAGE` | Post removed — Title: "Your post was removed", Body: "A post of yours was removed for a policy violation[ ({reason})]." Post restored — Title: "Your post was restored", Body: "A previously removed post of yours has been restored." Comment — Title: "Your comment was removed", Body: "A comment of yours was removed for a policy violation[ ({reason})]." Story — Title: "Your story was removed", Body: "A story of yours was removed for a policy violation[ ({reason})]." | Admin content moderation → author (best-effort) | POST /api/v1/admin/content/posts/{id}/remove\|restore, DELETE /api/v1/admin/content/comments/{id}, DELETE /api/v1/admin/content/stories/{id} → in-app notification |
| `SYSTEM_ANNOUNCEMENT` | Title: {admin-supplied title} — Body: {admin-supplied body} (trimmed; groupKey ANNOUNCEMENT:{id}) | Platform announcement fan-out to all/filtered users in 500-recipient batches; honors SYSTEM email category | POST /api/v1/admin/notifications/announcements → in-app notification (+email) |
| `SYSTEM_MESSAGE` | Unpublish — Title: "Your research was unpublished", Body: "Your research \"{title}\" was unpublished by moderation[ ({reason})]." Retract — Title: "Your research was retracted", Body: "Your research \"{title}\" was retracted by moderation[ ({reason})]." Delete — Title: "Your research was removed", Body: "Your research \"{title}\" was removed by moderation[ ({reason})]." | Admin unpublish/retract/delete via sendSystemNotification to the owner (best-effort) | POST /api/v1/admin/research/{id}/unpublish\|retract, DELETE /api/v1/admin/research/{id} → in-app notification |
| `SYSTEM_MESSAGE` | Title: "Account warning" — Body: "A moderation strike was recorded on your account: {reason}. Strikes expire automatically after 90 days." | Admin issues a strike | POST /api/v1/admin/safety/users/{userId}/strikes → in-app notification |
| `ANSWER_ACCEPTED` | Title: "Your answer was accepted as best" — Body: "{fullName} (@{username}) accepted your answer on: \"{questionTitle}\"" | AnswerAcceptedEvent (not when author accepts own answer, not restricted) | in-app notification |
| `ANSWER_REACTED` | Title: "Someone reacted to your answer" — Body: "{fullName} (@{username}) reacted {emoji} on your answer." or "... on your reanswer." Emoji map: LIKE=👍 INSIGHTFUL=💡 BENEFICIAL=✨ AGREE=✅ DISAGREE=❌ THANKS=🙏 | AnswerReactedEvent (aggregated per answer; not self, not restricted) | in-app notification |
| `NEW_FOLLOWER` | Title: "New follower" — Body: "{fullName} (@{username}) started following you." | UserFollowedEvent; deleted again on unfollow or block (both directions) | in-app notification (+SSE, email-eligible) |
| `POST_COMMENTED / POST_COMMENT_REPLIED` | Top-level — Title: "New comment on your post", Body: "{fullName} (@{username}) commented on your post." Reply — Title: "New reply on your comment", Body: "{fullName} (@{username}) replied to your comment." | PostCommentedEvent; comments aggregate per post, replies per parent comment; reply notification silently skipped if parentCommentAuthorId missing from event | in-app notification |
| `POST_COMMENT_REACTED` | Title: "Someone reacted to your comment" — Body: "{fullName} (@{username}) reacted {emoji} to your comment." | PostCommentReactedEvent (aggregated per comment; not self, not restricted) | in-app notification |
| `POST_NEW` | Title: "New {postLabel} from {fullName}" — Body: "{fullName} (@{username}) posted a new {postLabel}." postLabel: TEXT/EMBEDDED=post, VOICE_POST=voice post, REEL=reel | PostCreatedEvent → follower fan-out; PUBLIC visibility only | in-app notification |
| `POST_NEW (research)` | Title: "New research published" — Body: "{fullName} (@{username}) published: \"{researchTitle}\"" | ResearchPublishedEvent → fan-out to all followers (batched 500) | in-app notification |
| `POST_REACTED` | Title: "Someone reacted to your post" — Body: "{fullName} (@{username}) reacted {emoji} to your post." Emoji map: LIKE=👍 LOVE=❤️ HAHA=😂 WOW=😮 SAD=😢 ANGRY=😡 CARE=🤗 INSIGHTFUL=💡 | PostReactedEvent (aggregated per post within window; not self, not restricted) | in-app notification |
| `POST_SHARED` | Title: "Someone shared your post" — Body: "{fullName} (@{username}) shared your post." | PostSharedEvent (aggregated per post; self-repost allowed but self-notification skipped; restriction-silenced) | in-app notification |
| `PUBLICATION_COMMENTED` | Title: "New comment on your research" — Body: "{fullName} (@{username}) commented: \"{commentPreview}\"" | ResearchCommentedEvent (aggregated per research) | in-app notification |
| `PUBLICATION_COMMENT_REACTED` | Title: "Someone reacted to your comment" — Body: "{fullName} (@{username}) reacted to your comment on: \"{researchTitle}\"" | ResearchCommentReactedEvent (aggregated per comment; not self, not restricted) | in-app notification |
| `PUBLICATION_LIKED` | Title: "Someone reacted to your research" — Body: "{fullName} (@{username}) reacted {emoji} to: \"{researchTitle}\"" | ResearchReactedEvent (aggregated per research, groupKey PUBLICATION_LIKED:{researchId}); emoji map LIKE=👍 LOVE=❤️ INSIGHTFUL=💡 CELEBRATE=🎉 CURIOUS=🤔 SUPPORT=🤝 | in-app notification |
| `QUESTION_ANSWERED` | Title: "Your question has an answer" — Body: "{fullName} (@{username}) answered: \"{questionTitle}\"" | QuestionAnsweredEvent (aggregated per question; not self, not restricted) | in-app notification |
| `QUESTION_NEW` | To scholars/admins — Title: "New question to answer"; to author's followers — Title: "New question from {fullName}". Body (both): "{fullName} (@{username}) asked: \"{questionTitle}\"" | QuestionCreatedEvent; two fan-out passes (SCHOLAR+ADMIN roles, then followers) deduped to one row per recipient | in-app notification |
| `SOUND_APPROVED` | Title: "Your sound was approved" — Body: "\"{soundTitle}\" is now live in the sound library." (title fallback "Untitled sound"; truncated at 80 chars + …) | SoundApprovedEvent → uploader | in-app notification |
| `SOUND_REJECTED` | Title: "Your sound was not approved" — Body: "\"{soundTitle}\" was reviewed and not approved for the sound library." + optional " Reason: {reason}." | SoundRejectedEvent → uploader | in-app notification |
| `USER_MENTIONED` | Title: "You were mentioned" — Body: "{fullName} (@{username}) mentioned you {where}." or "... {where}: \"{snippet}\"" where ∈ in a post \| in a comment \| in a research publication \| in a research comment \| in a question \| in an answer | UserMentionedEvent — direct @mentions plus optional @followers fan-out (deduped); self never notified; restriction-silenced; also mirrors into the /mentions/me feed | in-app notification |

---

## 4. Email subjects & action verbs

Built by `EmailTemplate` from the notification; gated per user by the master toggle + category toggles (SOCIAL / MENTIONS / SYSTEM / TRENDING).

| Type | Subject / verb | Trigger | Notes |
|---|---|---|---|
| `ADMIN_ANOMALY` | Action verb: 'flagged a metric anomaly' | ADMIN_ANOMALY notification is email-eligible (SYSTEM category) | email to admins |
| `—` | Subject: "[TEST] {subject}"; body echoed as plain + "<p>{body}</p>". Sent only to the calling admin's own address. Response 202: {"queuedTo": "{adminEmail}", "enabled": {bool}}. | Admin email self-test | POST /api/v1/admin/notifications/email/test |
| `—` | CTA button label by type: View profile (follower/connection) \| Read the post (POST_NEW) \| Open post (post interactions/mentions) \| Open research (publication kinds) \| Open question (QnA kinds) \| View details (system/warning) \| Explore trending tags (TRENDING_DIGEST) \| Open on IRC (fallback) | email CTA rendering when a deep link exists | email |
| `—` | Subject/body pattern "{actor} {verb} {resourceLabel}". Research verbs: PUBLICATION_LIKED="reacted to your research"; PUBLICATION_COMMENTED="commented on your research"; PUBLICATION_COMMENT_REACTED="reacted to your comment on a research"; PUBLICATION_CITED="cited your research"; RESEARCH_CONTRIBUTOR_ADDED="added you as a contributor on their research" (label "in your research"). Q&A verbs: QUESTION_NEW="posted a new question"; QUESTION_ANSWERED="answered your question"; ANSWER_REPLIED="replied to your answer"; ANSWER_REACTED="reacted to your answer"; ANSWER_ACCEPTED="marked your answer as the best answer" (label "in Q&A", CTA "Open question"). Plain-text footer "— {brandName}" | notification email rendering for research/qna NotificationTypes (defined in /Users/khi/Desktop/irc/src/main/java/ak/dev/irc/app/email/EmailTemplate.java) | email |
| `—` | Subject/headline = "{actor} {verb} {resource}" + " (+{n-1} more)" when aggregated; greeting "Hi {firstName}," or "Hi there,"; sign-off "— {brand}". Notification title, when set, overrides the generated headline. | any emailed notification | email (subject + body) |
| `—` | Subject/headline = "{actor} {verb} {resource}" + " (+N more)" when aggregateCount>1. Verb table (compact): NEW_FOLLOWER=started following you; UNFOLLOWED=unfollowed you; BLOCKED/UNBLOCKED=blocked/unblocked you; RESTRICTED=restricted your account; CONNECTION_REQUEST=sent you a connection request; CONNECTION_ACCEPTED=accepted your connection request; POST_NEW=published a new post; POST_REACTED=reacted to your post; POST_COMMENTED=commented on your post; POST_COMMENT_REPLIED=replied to your comment; POST_COMMENT_REACTED=reacted to your comment; POST_SHARED=shared your post; POST_MENTIONED/USER_MENTIONED=mentioned you; PUBLICATION_LIKED=reacted to your research; PUBLICATION_COMMENTED=commented on your research; PUBLICATION_COMMENT_REACTED=reacted to your comment on a research; PUBLICATION_CITED=cited your research; RESEARCH_CONTRIBUTOR_ADDED=added you as a contributor on their research; QUESTION_NEW=posted a new question; QUESTION_ANSWERED=answered your question; ANSWER_REPLIED=replied to your answer; ANSWER_REACTED=reacted to your answer; ANSWER_ACCEPTED=marked your answer as the best answer; SYSTEM_MESSAGE/SYSTEM_ANNOUNCEMENT=sent you a system message; ACCOUNT_WARNING=issued an account warning; NEW_MESSAGE=sent you a message; MESSAGE_REQUEST=wants to send you a message; ADDED_TO_GROUP=added you to a group; CALL_MISSED=tried to call you; MESSAGE_MENTION=mentioned you in a chat; CHANNEL_NEW_POST=posted in a channel you follow; CHANNEL_JOIN_REQUEST=requested to join your channel; CHANNEL_JOIN_APPROVED=approved your channel join request; STREAM_STARTED=went live; STORY_PUBLISHED=published a new story; STORY_REACTED=reacted to your story; STORY_REPLIED=replied to your story; SOUND_APPROVED=approved your uploaded sound; SOUND_REJECTED=reviewed your uploaded sound; TRENDING_DIGEST=shared today's trending in scholarship. CTA labels include "View profile", "Read the post", "View details", "Explore trending tags" | first notification of an email-eligible kind per groupKey per hour (Redis throttle), gated by per-user email prefs (social/mentions/system/trending). All chat kinds are emailEligible=false so chat verbs are currently dormant | email (subject + HTML/plain body via /Users/khi/Desktop/irc/src/main/java/ak/dev/irc/app/email/EmailTemplate.java + NotificationEmailFormatter) |
| `—` | Verbs by type — social: started following you / unfollowed you / blocked you / unblocked you / restricted your account / sent you a connection request / accepted your connection request. Posts: published a new post / reacted to your post / commented on your post / replied to your comment / reacted to your comment / shared your post / mentioned you. Research: reacted to your research / commented on your research / reacted to your comment on a research / cited your research / added you as a contributor on their research. QnA: posted a new question / answered your question / replied to your answer / reacted to your answer / marked your answer as the best answer. System: sent you a system message / issued an account warning. Chat (in-app only, still covered): sent you a message / wants to send you a message / added you to a group / tried to call you / mentioned you in a chat / posted in a channel you follow / requested to join your channel / approved your channel join request / went live. Stories/sounds: published a new story / reacted to your story / replied to your story / approved your uploaded sound / reviewed your uploaded sound. Digest: shared today's trending in scholarship. Null-type fallback: sent you an update about. | per NotificationType when composing email subject/body | email |
| `—` | Subject: "IRC Platform — test email". Body: "Hi {fullName}, This is a test email from IRC Platform. If you can read it, your notification pipeline is working end to end. Tip: if you don't see future activity emails, check your spam folder and mark this address as 'Not spam'. — IRC" | Self-test email | POST /api/v1/users/me/email-preferences/test → email |
| `—` | Action-verb table (per NotificationType): NEW_FOLLOWER=started following you; UNFOLLOWED=unfollowed you; BLOCKED=blocked you; UNBLOCKED=unblocked you; RESTRICTED=restricted your account; CONNECTION_REQUEST=sent you a connection request; CONNECTION_ACCEPTED=accepted your connection request; POST_NEW=published a new post; POST_REACTED=reacted to your post; POST_COMMENTED=commented on your post; POST_COMMENT_REPLIED=replied to your comment; POST_COMMENT_REACTED=reacted to your comment; POST_SHARED=shared your post; POST_MENTIONED/USER_MENTIONED=mentioned you; PUBLICATION_LIKED=reacted to your research; PUBLICATION_COMMENTED=commented on your research; PUBLICATION_COMMENT_REACTED=reacted to your comment on a research; PUBLICATION_CITED=cited your research; RESEARCH_CONTRIBUTOR_ADDED=added you as a contributor on their research; QUESTION_NEW=posted a new question; QUESTION_ANSWERED=answered your question; ANSWER_REPLIED=replied to your answer; ANSWER_REACTED=reacted to your answer; ANSWER_ACCEPTED=marked your answer as the best answer; SYSTEM_MESSAGE/SYSTEM_ANNOUNCEMENT=sent you a system message; ACCOUNT_WARNING=issued an account warning; NEW_MESSAGE=sent you a message; MESSAGE_REQUEST=wants to send you a message; ADDED_TO_GROUP=added you to a group; CALL_MISSED=tried to call you; MESSAGE_MENTION=mentioned you in a chat; CHANNEL_NEW_POST=posted in a channel you follow; CHANNEL_JOIN_REQUEST=requested to join your channel; CHANNEL_JOIN_APPROVED=approved your channel join request; STREAM_STARTED=went live; STORY_PUBLISHED=published a new story; STORY_REACTED=reacted to your story; STORY_REPLIED=replied to your story; SOUND_APPROVED=approved your uploaded sound; SOUND_REJECTED=reviewed your uploaded sound; TRENDING_DIGEST=shared today's trending in scholarship; null type=sent you an update about. The switch is exhaustive — a new NotificationType needs a case. | Renders subject, headline, and context sentence of every notification email | email |
| `—` | Greeting: "Hi {firstName}," or "Hi there," when no recipient name. Aggregate badge "+{n-1} more"; context suffix "— and {n-1} other update(s)". CTA labels: View profile (follower/connection), Read the post (POST_NEW), Open post (post interactions/mentions), Open research, Open question, View details (system/warning), Explore trending tags (TRENDING_DIGEST), default "Open on IRC"; secondary line: "Or open this link: {url}". CTA omitted entirely if app.frontend-url unset. Footer: "You're receiving this because you have email notifications enabled for {categoryLabel} on {brandName}." + "Manage notification preferences in your account settings · This is an automated message — please do not reply." categoryLabel ∈ post activity \| research activity \| Q&A activity \| social activity \| mentions \| trending digest \| system messages \| activity. Plain-text fallback mirrors: headline, action sentence, "When: {MMM d, yyyy 'at' h:mm a} UTC", body quote, CTA URL, "— {brandName}". Brand default "IRC Platform" (irc.email.from-name). | Every notification email | email |
| `—` | Subject: "{actorLabel} {actionVerb} {resourceLabel}" with " (+{n-1} more)" appended when aggregateCount>1. actorLabel = actor full name, else "@{username}", else "Someone". resourceLabel: posts="on IRC", research="in your research", Q&A="in Q&A", others empty. | Every notification email; a non-blank notification title overrides the headline (not the subject) | email |

---

## 5. Response headers with user-facing meaning

| Header | Value / message | When | Surface |
|---|---|---|---|
| `settings/data (DataPrivacyController)` | Content-Disposition: attachment; filename="irc-export.zip" | successful export download | GET /api/v1/privacy/export/{jobId}/download |
| `post (sounds)` | Deprecation: true + Link: </api/v1/admin/sounds/{id}/approve>; rel="successor-version" | call to the deprecated non-admin-scoped sound approve alias | POST /api/v1/sounds/{id}/approve response headers |
| `chat (channels)` | Deprecation: true + Link: </api/v1/admin/channels/{id}/verified>; rel="successor-version" | call to the deprecated verified-badge alias | PUT /api/v1/channels/{id}/verified response headers |
| `X-Search-Degraded` | X-Search-Degraded: true | mirrors the degraded flag for callers that skip JSON parsing; header absent when healthy | GET /api/v1/search |
| `admin/activity` | Content-Type: text/csv; Content-Disposition: attachment; filename="activity-{userId}.csv" (columns activityId,type,createdAt) | ?format=csv on break-glass export | GET /api/v1/admin/users/{userId}/activity/export |
| `admin/analytics` | Content-Disposition: attachment; filename="{dataset}-{days}d.csv" (produces text/csv) | CSV export | GET /api/v1/admin/analytics/export |
| `Deprecation` | Deprecation: true — Link: </api/v1/admin/channels/{id}/verified>; rel="successor-version" | Legacy stray admin route retained as alias until clients migrate | PUT /api/v1/channels/{id}/verified |
| `Deprecation` | Deprecation: true — Link: </api/v1/admin/sounds/{id}/approve>; rel="successor-version" | Legacy stray admin route retained as alias until clients migrate | POST /api/v1/sounds/{id}/approve |

---

## 6. Reading this catalog

- **Clients branch on `code`, humans read `message`.** Never string-match the
  message text — codes are the stable contract; wording may be tuned.
- **429 responses** (`RATE_LIMITED`, `MEDIA_QUOTA_EXCEEDED`) include
  `retryAfterSeconds` / `resetsAt` details — surface a countdown, not a dead end.
- **403 with `STEP_UP_REQUIRED`** is not a permission failure — it asks the
  admin client to re-verify the password and retry (`POST /api/v1/admin/step-up`).
- **`degraded: true`** on search responses means the result set is empty
  because Elasticsearch was unreachable, not because nothing matched — show
  "search is temporarily unavailable", never "no results".
- **Notes are part of the API.** When a response says a listing was capped or a
  series starts at collector deployment, the admin UI should render that
  caveat; silently dropping it turns an honest partial answer into a lie.
