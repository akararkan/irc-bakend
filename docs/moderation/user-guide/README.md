# User Guide — How Content Moderation Works

Plain-language guide to what happens when **you** post, comment, message, or
create anything with text on the platform. If you're building the frontend or
writing support macros, this is the "explain it to a real person" version —
for the technical/API contract, see
[`../frontend/`](../frontend/README.md); for the underlying design, see
[`../MODERATION_ROADMAP.md`](../MODERATION_ROADMAP.md).

## The one-paragraph version

Every piece of text you submit — a post, a comment, a story caption, a
message, a question, anything — is automatically checked before anyone else
can see it. Almost all the time this happens instantly and you'll never notice
it happened at all. Occasionally something is held for a closer look, or
refused outright. This guide explains exactly when each of those happens, for
every type of content on the platform.

## The three things that can happen

Every time you submit text, exactly one of these three outcomes occurs:

### 1. ✅ It just works (the normal case)
Your content is checked in a fraction of a second, comes back clean, and goes
live immediately — exactly like there was no check at all. This is what
happens for the overwhelming majority of everything anyone posts.

### 2. ⏳ It's held for a closer look
The automatic check wasn't fully confident either way. Your content **is
saved** — you didn't lose anything — but it's temporarily visible only to
you, with a "Checking…" or "Under review" badge, until either:
- the automatic system finishes deciding (usually within seconds), or
- a human moderator takes a look.

You'll get a notification the moment it's decided either way.

### 3. 🚫 It's refused
The check was confident your content crosses a line, so it's rejected on the
spot. **Nothing is saved** — you'll see an error message and your draft stays
in the text box so you can edit and try again. Nothing was posted, so there's
nothing to delete or clean up.

## Rules that are true for *everything* you create

- **You always see your own content.** Even while something is "held," you can
  see it on your own profile/feed exactly as you wrote it. Nobody else can.
- **A refusal never deletes your draft.** Your text stays in the composer so
  you can edit it and resubmit.
- **Editing re-checks your content.** If you edit something that's already
  live and the edit looks fine, nothing changes. If the edit looks risky, that
  specific edit goes back into the "held" state until it's re-cleared — see
  the per-content-type pages below, because this behaves slightly differently
  for a few types.
- **We never tell you exactly what tripped the check.** The error message is
  intentionally general ("appears to violate the community guidelines")
  rather than naming a word or category. This isn't us being cagey for no
  reason — telling people exactly what the filter looks for would let bad
  actors test-and-tweak their way around it.
- **If you think a refusal or hold was wrong**, you can appeal from your
  account settings. Mistakes happen, especially with automated systems, and
  every appeal is reviewed by a person.

## How long does "held" actually take?

It depends what you're posting — a live chat message can't wait the same way
a research paper can. Here's the honest range, worst case:

| What you're creating | Usually decided in | Absolute maximum wait |
|---|---|---|
| Post | under 2 seconds | 30 seconds |
| Comment / reply | under half a second | 10 seconds |
| Story | under 1 second | 15 seconds |
| Research paper (on publish) | under 5 seconds | 60 seconds |
| Question / answer | under 2 seconds | 30 seconds |
| Chat / DM message | under half a second | 10 seconds |
| Channel or group name/description | under 2 seconds | 30 seconds |
| Live stream title/description | under 2 seconds | 30 seconds |
| Live chat message (while streaming) | under a third of a second | never held — see below |

The "maximum wait" column is a safety ceiling, not something you should
expect to hit. It exists so that if the automatic checker is ever slow or
briefly unavailable, your content still gets a clear outcome instead of being
stuck forever — see "What if it never seems to clear?" below.

## Read next

- [**Posts and comments**](posts-and-comments.md) — the everyday case, and
  what editing does
- [**Stories and polls**](stories-and-polls.md) — why stories behave
  differently (hint: they only last 24 hours)
- [**Research and Q&A**](research-and-qna.md) — publishing papers, questions,
  and answers
- [**Chat, channels, and live streaming**](chat-channels-and-live.md) —
  messages, group/channel names, and why live chat is a special case

## What if it never seems to clear?

If something stays "Checking…" far past the maximum times above, the
automatic checker is very likely temporarily down on our end — not something
you did. Your content stays safely held (not deleted) and will be looked at
by a moderator. There's nothing you need to do; you don't need to delete and
repost.

## A note on languages

The automatic checker currently understands English best. If you write in
another language, the automatic check is less likely to catch something
subtle, but our word-level filter (which does understand common ways people
try to dodge it — spacing things out, swapping letters, etc.) still applies in
every language. This is actively being improved.
