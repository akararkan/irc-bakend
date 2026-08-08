# Stories and Polls

Part of the [moderation user guide](README.md).

## Why stories are handled differently

Stories only last 24 hours to begin with. Holding one "for review" for even a
few minutes eats into its whole lifespan — and if it got stuck for the
absolute maximum wait, it would have expired before anyone ever saw it,
which is functionally the same as it never existing.

So stories get the fastest possible check, **and** a different safety-net
rule than everything else on the platform:

- **Typical timing:** clears in under a second; hard ceiling is 15 seconds.
- **If the automatic checker can't decide fast enough**, most content types
  default to staying hidden until a human looks. Stories are the exception:
  if the ceiling is hit with no decision either way, your story **publishes
  anyway**, and is simply flagged so a moderator glances at it with priority.
  Availability matters more than the very small residual risk here, given how
  short-lived stories already are.

Everything else about stories works the same as posts: your caption is
checked, a clearly bad one is refused outright with nothing posted, a
borderline one is briefly visible only to you while it clears (unless the
15-second ceiling is hit, in which case — as above — it just goes live).

## Story polls

The question and both answer options on a poll you attach to your story are
checked too, but polls work a little differently: **there's no "held"
state for a poll.** A poll question is either accepted right away or refused
right away — you'll never see a poll sitting in "pending." If it's refused,
just adjust the wording and try again.

## Highlights

Adding a story to a Highlight, and the title you give that Highlight, get the
same instant accept-or-refuse treatment as poll text — no waiting state.

## Common questions

**"My story went up even though it looked a little risky — is that normal?"**
Yes, if the automatic checker didn't reach a confident decision within the
15-second window, stories are allowed to publish (rather than sit hidden and
possibly expire unseen) and are simply queued for a moderator to double-check
soon after. This trade-off is specific to stories because of how short-lived
they are.

**"Why can't I see my poll while it says it's checking?"**
Polls don't have a "checking" state at all — if you can see it, it's already
been accepted.
