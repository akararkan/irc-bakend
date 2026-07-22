# 03 — Permissions, Requests & the Chat Status Model

This is your Messenger-equivalent of **friends / non-friends / blocked /
restricted**. It reuses the social primitives already in
`docs/user/social.md` (follow, block, restrict, social status) — chat adds a
single decision layer on top, plus a **message request** inbox.

## The four relationship states (mapped to your existing model)

| Chat state | Derived from your social graph | Messenger analogue |
|------------|-------------------------------|--------------------|
| **CONNECTED** | mutual follow, *or* a previously accepted request | Friends |
| **STRANGER** | one-way / no follow, no prior thread | Non-friend (goes to Message Requests) |
| **RESTRICTED** | recipient has *restricted* sender | Restricted |
| **BLOCKED** | either party has *blocked* the other | Blocked |

> "Friends" on your platform = **mutual follow** by default. If you later add an
> explicit friendship edge, only the derivation in `SocialStatusService` changes;
> nothing below changes. Keep the mapping in one place.

## The permission engine (DIRECT messages)

Runs at **send time, before persistence**. Input: `senderId`, `recipientId`.
Output: one of `ALLOW`, `ROUTE_TO_REQUEST`, `DELIVER_RESTRICTED`, `DENY`.

```
function authorizeDirectSend(sender, recipient):

    # 1. Hard block wins over everything.
    if isBlocked(sender, recipient) or isBlocked(recipient, sender):
        return DENY(BLOCKED)          # send fails silently; no existence leak

    # 2. Restrict: message is accepted but quarantined.
    if isRestrictedBy(recipient, sender):
        return DELIVER_RESTRICTED     # lands in recipient's "Restricted" area,
                                      # no push, no online/typing signals shown

    # 3. Already talking? Straight through.
    if hasAcceptedThread(sender, recipient):
        return ALLOW

    # 4. Connected (mutual follow)? Straight through.
    if isConnected(sender, recipient):
        return ALLOW

    # 5. Stranger's first contact -> Message Request.
    return ROUTE_TO_REQUEST
```

### What each outcome does

- **ALLOW** — normal write + fan-out + push (subject to mute).
- **ROUTE_TO_REQUEST** — the conversation is created but flagged; a
  `message_requests` row is inserted; the recipient sees it in **Requests**, gets
  *no* push by default, and the sender cannot see read receipts, typing, or
  presence until the recipient **accepts**. On accept → `hasAcceptedThread`
  becomes true and it graduates to a normal chat. On decline → hidden; on
  block → the requester is blocked.
- **DELIVER_RESTRICTED** — written normally but the recipient's client shows it in
  a muted "Restricted" tray; sender gets no delivery/read signal and cannot tell
  they were restricted (this is the whole point of restrict vs block — it's
  quiet).
- **DENY** — the send returns a generic success-shaped or `errorCode: BLOCKED`
  response depending on your privacy stance; it is **not** written. Do not reveal
  *who* blocked *whom*.

### Anti-abuse limits on requests

A stranger should not be able to spam the Requests inbox:

- At most **1 pending request row** per (recipient, requester) — enforced by the
  `UNIQUE (recipient_id, requester_id)` constraint.
- Cap the number of *messages* a stranger may send into an unaccepted thread
  (e.g. 3), after which further sends are blocked until acceptance.
- Rate-limit new requests per sender per hour in Redis
  (`ratelimit:req:{senderId}`).

## Permission engine (GROUP messages)

Groups don't use the connection graph — they use **membership + role + status**
(see [04-group-chats.md](04-group-chats.md)):

```
function authorizeGroupSend(sender, conversation):
    member = getMember(conversation, sender)
    if member is null or member.status in (LEFT, REMOVED):
        return DENY(NOT_A_MEMBER)
    if member.status == RESTRICTED:
        return DENY(READ_ONLY)          # muted member: can read, cannot post
    if conversation.settings.adminsOnly and member.role == MEMBER:
        return DENY(ADMINS_ONLY)        # "only admins can send" mode
    return ALLOW
```

## Reading permission

Symmetric but simpler: you may read a conversation iff you are an `ACTIVE` (or
`RESTRICTED`, read-only) member. Blocked/removed users lose read access
immediately; existing already-delivered copies on their device are the client's
concern (server stops serving new reads).

## Where this plugs in

- `SocialStatusService` — already exists; expose `isBlocked`, `isRestrictedBy`,
  `isConnected` cleanly for the engine to call.
- `PermissionEngine` — new, tiny, pure-function class; unit-test it in isolation
  with a truth table of the four states × (direct/group).
- Cache the derived direct-relationship in Redis
  (`rel:{a}:{b}` → CONNECTED/STRANGER/RESTRICTED/BLOCKED, short TTL) so the hot
  send path doesn't re-query the graph every message; invalidate on
  follow/block/restrict changes (you already emit those events).

## Truth table (keep this as the test spec)

| sender→recipient | recipient→sender | prior thread | Outcome |
|---|---|---|---|
| blocked | any | any | DENY |
| any | blocked | any | DENY |
| — | restricted sender | any | DELIVER_RESTRICTED |
| mutual follow | — | — | ALLOW |
| — | — | accepted | ALLOW |
| one-way / none | — | none | ROUTE_TO_REQUEST |
