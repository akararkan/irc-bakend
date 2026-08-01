# Privacy, Blocks, Mute & Hidden Keywords (§5, §13)

**[B] Backend-owned.** The most important part of the module: any setting that
protects a user from another user is enforced here, never in the UI.

## Field visibility policy

Stored as a single JSONB column `user_privacy.privacy = { "BIO": "FRIENDS",
"BIRTHDAY": "ONLY_ME", ... }` keyed by `FieldKey.name()`. JSONB is preferred
over 25 boolean columns because the key set grows every release. A missing key
is never `null` — it resolves to the code default in `PrivacyDefaults`.

- `FieldKey` — the controllable fields/content classes (bio, birthday, profile
  picture, cover, posts, stories, photos, videos, followers, following,
  friends-list, plus interaction permissions: who-can-follow / message / tag /
  mention / friend-request).
- `VisibilityLevel` — `EVERYONE · FRIENDS · FOLLOWERS · CLOSE_FRIENDS · CUSTOM · ONLY_ME`.

## The Visibility Resolver

`settings.privacy.service.VisibilityResolver` is the single enforcement funnel —
a pure ordered function over `(relationship, policy)` that **fails closed** and
**checks blocks first**:

```
resolve(viewerId, ownerId, FieldKey) →
  1. viewer == owner                    → ALLOW
  2. block either direction             → DENY   (SocialGuard.isBlockedBetween)
  3. owner not a live account           → DENY   (UserRepository.existsByIdAndDeletedAtIsNull)
  4. policy = ONLY_ME                   → DENY
  5. policy = EVERYONE                  → ALLOW
  6. policy = FRIENDS                   → ALLOW iff mutual follow
  7. policy = FOLLOWERS                 → ALLOW iff viewer follows owner
  8. policy = CLOSE_FRIENDS             → ALLOW iff viewer ∈ owner's Close Friends
     policy = CUSTOM                    → ALLOW iff viewer ∈ one of owner's custom lists
  9. default                            → DENY
```

Reuses the existing social graph rather than reinventing it:

| Relationship | Seam |
|--------------|------|
| Block (either way) | `common.service.SocialGuard.isBlockedBetween(a,b)` |
| Friends (mutual follow) | `UserFollowRepository.isFollowing(a,b) && isFollowing(b,a)` |
| Followers | `UserFollowRepository.isFollowing(viewer, owner)` |
| Close Friends | `CloseFriendsRepository.existsByIdOwnerIdAndIdFriendId(owner, viewer)` |
| Custom list | `PrivacyListMemberRepository.isInAnyCustomList(owner, viewer)` |

Because it is pure over `(relationship, policy)`, it is covered by a single
truth-table unit test — the same technique as the chat permission engine.

**Enforcement is two-layered** (see architecture.md): the query layer carries
the block predicate into SQL so hidden rows are never fetched, and the
serialization layer redacts denied fields. `VisibilityResolver.isVisible(...)`
is the funnel both layers call.

## Custom lists & Close Friends

- `privacy_lists` + `privacy_list_members` — user-created named audiences the
  `CUSTOM` policy resolves against (`PrivacyListService`).
- Close Friends reuses the platform's existing `CloseFriendsList` subsystem — it
  is not re-implemented here.

## Blocks (§13) — reused

Blocks already exist and are correct on this platform (`UserBlock`,
`UserBlockRepository.isBlockedBetween`, `UserSocialServiceImpl.block` which
severs follows). The resolver folds them in at step 2, and
`/api/v1/blocks` is a thin alias over the existing `UserSocialService`. Semantics:

| Action | Effect |
|--------|--------|
| **Block** | bidirectional invisibility; follows severed; new interaction fails closed |
| **Mute** | one-directional, silent; muted user's content filtered from the muter's feed; messaging still works |
| **Restrict** | Instagram-style quarantine (chat DMs today; comment-quarantine is a documented follow-up in the post subsystem) |

## Mute (§13)

`user_mutes` (muter → muted, one-directional). `MuteService.mutedIds(userId)`
returns the set a feed-assembly query passes into `NOT IN`. Muting never
notifies the muted user and severs nothing.

## Hidden keywords (§13)

`hidden_keywords` stores the **normalized** form of each keyword.
`KeywordNormalizer` (pure JDK) applies:

1. Unicode NFKC + strip combining marks (diacritics / Arabic tashkeel / tatweel).
2. Case-fold.
3. Unify Arabic/Kurdish letter variants — `ی/ى → ي`, `ک → ك`, `ة → ه`,
   `أإآ → ا` — so orthography can't bypass the filter in exactly the languages
   this platform serves.
4. Collapse whitespace.

Applied at feed assembly (server-side, item never reaches the device) and at
notification fan-out. `HiddenKeywordService.isHidden(userId, text)` is the check;
`normalizedFor(userId)` + `KeywordNormalizer.matchesAny` is the batched form.

## API

```
GET    /api/v1/settings/privacy                     resolved policy map
PUT    /api/v1/settings/privacy/{field}   {visibility}
GET    /api/v1/settings/privacy/lists
POST   /api/v1/settings/privacy/lists     {name}
DELETE /api/v1/settings/privacy/lists/{id}
GET    /api/v1/settings/privacy/lists/{id}/members
POST   /api/v1/settings/privacy/lists/{id}/members  {memberId}
DELETE /api/v1/settings/privacy/lists/{id}/members/{memberId}
GET    /api/v1/settings/privacy/muted
POST   /api/v1/settings/privacy/muted/{userId}
DELETE /api/v1/settings/privacy/muted/{userId}
GET    /api/v1/settings/privacy/keywords
POST   /api/v1/settings/privacy/keywords  {keyword}
DELETE /api/v1/settings/privacy/keywords/{id}
```

Every visibility change writes a `settings_audit` row.
