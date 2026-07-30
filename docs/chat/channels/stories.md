# Channels — stories & highlights ⛔ **REMOVED**

> **This feature has been removed.** Channel stories and channel highlights —
> and every endpoint below — no longer exist. This page is kept as a tombstone
> so old links don't 404 and so the removal is on the record. User (personal)
> stories are unaffected; only the **channel**-scoped story/highlight feature
> was deleted.

## What was removed

The whole channel-story surface was deleted (controller, services, DTOs and the
`canManageStories` admin right):

| Method & path | Was |
|---|---|
| `POST /channels/{id}/stories` *(JSON & multipart)* | post a story as the channel |
| `GET /channels/{id}/stories` | the channel's active stories |
| `DELETE /channels/{id}/stories/{storyId}` | delete a channel story |
| `POST /channels/{id}/stories/{storyId}/poll` | attach an A/B poll to a channel story |
| `GET /channels/stories/tray` | the channel-story tray |
| `POST /channels/{id}/highlights` | create a highlight |
| `GET /channels/{id}/highlights` | the highlight rail |
| `POST /channels/{id}/highlights/{highlightId}/stories/{storyId}` | snapshot a story into a highlight |
| `GET /channels/{id}/highlights/{highlightId}/stories` | a highlight's snapshots |
| `DELETE /channels/{id}/highlights/{highlightId}/stories/{storyId}` | remove one snapshot |
| `DELETE /channels/{id}/highlights/{highlightId}` | delete a highlight |
| `PATCH /channels/{id}/highlights/order` | reorder the rail |

All of these now return **`404 NOT_FOUND`** (no route).

**Related removals**

- The **`canManageStories`** admin right is gone from
  [`AdminRights`](admins.md#adminrights) and the promote/edit-admin request. Any
  stale `canManageStories` still sitting in a persisted `admin_rights` JSON blob
  is ignored on read (`@JsonIgnoreProperties(ignoreUnknown = true)`), so no
  migration is needed.
- The channel **story-tray** realtime events (`new_story` / `story_removed` /
  `poll_vote_cast` emitted with a channel as the story author) are no longer
  produced.

**Still available:** everything else on a channel — [posts](posts.md),
[discussion & comments](discussion.md), [admins & invites](admins.md),
[stats & realtime](stats.md), and the channel [inbox](inbox.md).
