# Community Settings (§17)

**[B] Backend-owned — mostly satisfied by reusing the existing chat subsystem.**

The spec says communities reuse the role model already defined for group chats,
so there is **one authorization truth table for the whole platform** rather than
two that drift apart. On this codebase that subsystem already exists and is used
as-is:

| Spec §17 feature | Existing implementation reused |
|------------------|--------------------------------|
| Community / group membership + roles | `chat` conversations/channels + `GroupMemberService` |
| Role hierarchy enforcement | `chat.permission.GroupPermissions` / `ChannelRights.can` (the single enforcement funnel) |
| Invitation links (signed token, `max_uses`, `expires_at`, uses counter, revoke, creator recorded) | existing channel invite-link code |
| Join-request approval (`PENDING → APPROVED/REJECTED`) | existing `ConversationJoinRequest` flow |
| Per-member mute & pin | existing per-member conversation settings |
| Audit log | existing `audit` module (`AuditLogService`) |

## What was intentionally **not** changed

- **Role widening (`+MODERATOR`, `+RESTRICTED`).** Adding `@Enumerated(STRING)`
  values to the existing `conversation_members.role` on a live table triggers the
  enum CHECK-constraint gotcha (a stale `*_check` constraint rejects the new
  value until dropped). Doing it safely needs an `EnumCheckConstraintReconciler`
  entry for `(conversation_members, conversation_members_role_check)`; it was
  **deferred** to avoid touching the live chat table in this pass.
- **Audit hooks on role change / ownership transfer** — a one-line
  `AuditLogService.record(resourceType="COMMUNITY")` call in
  `GroupMemberService.changeRole/transferOwnership` is the documented seam.

See [implementation-notes](implementation-notes.md) for the full seam list.
