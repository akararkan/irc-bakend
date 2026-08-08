# Moderation — Frontend & Support Contract

**Audience: frontend developers, product, and support staff.** What changes for
someone using the platform, exactly what the client must render, and what to
tell a user who asks.

> **Three moderation docs, three audiences — don't mix them up:**
>
> | Doc | For | Contains |
> |---|---|---|
> | **this one** | frontend devs, support | response shapes, status codes, UI states, notification copy, support answers |
> | [`../user-guide/`](../user-guide/README.md) | **end users** | plain language, no code — suitable for a help-center article |
> | [`../admin-guide.md`](../admin-guide.md) | moderators/admins | how to work the review queue and tune the system |

---

## 1. The short version

Nothing changes for the overwhelming majority of posts. Clean content is scored
and published inside the same request, in well under a second, and the user sees
exactly what they saw before.

Three things can happen instead:

| Outcome | What the user sees | Who else can see it |
|---|---|---|
| **Published** | normal | everyone, as before |
| **Held** | their content, marked "being checked" | nobody but them |
| **Blocked** | an error on submit; nothing is created | nobody |

Authors always see their own content. Hiding it from them too is
indistinguishable from the platform losing their post, which is why §5.1
recommends against it.

---

## 2. Blocked on submit

The create request fails with `400`:

```json
{
  "status": 400,
  "errorCode": "CONTENT_REJECTED",
  "message": "This content was blocked because it appears to violate the community guidelines. If you believe this is a mistake, you can appeal from your account settings.",
  "path": "/api/v1/posts",
  "traceId": "…"
}
```

**Render `message` verbatim.** Do not add "your text contained: …" or name a
category. The copy is deliberately vague: telling an author exactly which word
tripped which threshold hands them a working oracle for probing the classifier
until they find phrasing that gets through.

Nothing is persisted. The user's draft is still in the client — do **not** clear
the composer on this error. Let them edit and resubmit.

Applies to: posts, post comments and replies, stories, story polls, share
captions, highlight titles, research papers on publish, Q&A questions and
answers, chat messages, channel and group details, stream titles.

## 3. Held for review

The create request **succeeds**. The content exists, belongs to the user, and is
invisible to everyone else until it clears.

Post responses carry the existing `status` field:

```json
{ "id": "post_9f2a", "status": "PENDING_REVIEW", … }
```

Client should:

- Render the item normally in the author's own feed and profile.
- Overlay a quiet badge — "Checking…" or "Under review" — not an error state.
- Not show engagement affordances that cannot work yet (nobody can like content
  they cannot see).
- Re-fetch after a few seconds. Most held content clears quickly; the hold
  ceilings are 10s for comments, 30s for posts, 60s for research papers.

Suggested copy, matching the server-side notes:

> **Checking…** Your post is being checked automatically. It becomes visible to
> others as soon as it clears — usually within a few seconds.

If it escalates to a human:

> **Under review.** Automatic checking took longer than expected, so a moderator
> is taking a look. It stays hidden from others until then.

**Comments and replies** have no status field on the wire. A held comment simply
does not appear for other viewers, and does appear for its author — the read
filter is server-side. Clients need no change, though showing the author a
"checking" badge is a better experience than showing nothing.

**Chat messages** are held by redacting the body for everyone except the sender,
using the same mechanism a deleted message already uses. The sender sees their
message; recipients do not receive it until it clears.

**Live-stream chat** is different by design (§6): a line is either broadcast or
silently dropped within ~300ms. There is no held state and no error — a stream is
not a place to explain moderation policy to a heckler in real time.

## 4. Notifications the user receives

Delivered as system notifications, through the same path as admin takedowns.

| Event | Title | Body |
|---|---|---|
| Content blocked | *Your content was removed* | "Your {post} was removed because it appears to violate the community guidelines. You can appeal this decision from your account settings." |
| Escalated to a human | *Your content is being reviewed* | "Your {post} is waiting on a moderator and is not visible to others yet. We will let you know as soon as it is reviewed." |
| Cleared after a wait | *Your content is live* | "Your {post} cleared review and is now visible to everyone." |

`{post}` is the entity label — post, comment, story, research paper, question,
answer, message, channel details, stream details.

The "cleared" notification only fires when the content actually waited. Something
that cleared inline never left the author's sight, so telling them about it would
be noise.

No notification is sent for live-chat lines. A bell for every dropped chat
message would be its own kind of abuse.

## 5. Editing

Edits re-enter moderation (§5.5). This closes a real hole: before, content could
be laundered past the filters by posting something benign and immediately editing
it.

- **Comments, posts:** the new text is checked synchronously. A rejection refuses
  the edit and keeps the original wording — the user gets `CONTENT_REJECTED` and
  their published content is untouched. A borderline result applies the edit and
  puts the item back into a held state.
- **Channel and group details:** a rejected or held change is refused outright
  and the previously approved title/description stays live. Blanking a live
  channel's name because someone tried to change it would be worse than refusing.

## 6. What support should know

**"My post disappeared."** It is almost certainly held, not deleted. Ask the user
whether they can still see it on their own profile — if yes, it is in the queue.
Check `GET /api/v1/admin/moderation/review?status=IN_REVIEW` filtered by their
author id.

**"Why was my post blocked? I didn't say anything wrong."** False positives are
real, especially right now — the model is trained on a small seed dataset and is
English-only. The correct answer is the appeal path, and the correct internal
action is to approve it in the queue with `teachModel: true`, which feeds the
correction straight back into the next training run.

**"Everything I post is stuck pending."** Check whether the inference container is
running. When it is down, fail-closed entity types hold content until a human
reviews it. That is intended behaviour, but it is also the loudest possible
symptom of an ops problem — see [../operations.md](../operations.md).

**Non-English content.** The model is English-only (§18.2). It will under-detect
in other languages, meaning false *negatives*, not false positives. Users writing
in Arabic or Kurdish will mostly see no change at all; the keyword blocklist,
which is normalization-aware for those scripts, still applies.

## 7. Privacy

Submitted text is retained in `moderation_case_fields` so a moderator can review
what was actually written even after the content itself is deleted. Treat it as
user content for retention and erasure purposes.

**Chat, DM and live-chat bodies are never shown to staff.** The admin review
queue redacts them — a moderator sees the per-label scores, which field tripped,
and the threshold band, but not the message. That is the platform's absolute
content-privacy boundary and a moderation queue is not an exception to it; the
whole point of scoring private messages automatically is that nobody has to read
them. For the same reason a private message can never be promoted into the
training set: that would ship it out of the platform entirely and bake it into
model weights, which is a far larger disclosure than a screen.

Retraining on real user-submitted content is a legitimate and expected use of
moderation data, but §15 asks that it be stated explicitly in the data-use policy
rather than assumed. Get sign-off from whoever owns privacy policy before wiring
flagged user content into the training set at scale — the dashboard makes that
one click, which is exactly why it is worth deciding deliberately.

Moderation surfaces never expose one user's content to another. Chat and DM
bodies are never rendered in any admin view, including this one.
