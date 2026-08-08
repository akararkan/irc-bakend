# Chat, Channels, and Live Streaming

Part of the [moderation user guide](README.md). This is the one area with the
most exceptions to the general rules — because "hide it until it's checked"
doesn't make sense for a live conversation the way it does for a post.

## Direct messages and group chat

Every message you send is checked before it reaches anyone else.

- **Clean** → delivered normally, instantly. This is virtually every message
  you'll ever send.
- **Held** → the message is technically sent, but the other person/group
  doesn't see its content yet — they'll see a placeholder, the same
  mechanism used for a message you've deleted. You still see your own
  message normally. The moment it clears, it delivers to everyone as normal
  — bell notification, unread badge, all of it, just slightly delayed.
- **Refused** → the message isn't sent at all. You get an error and your
  typed message stays in the input box.

**Typical timing:** delivered in well under half a second; hard ceiling is 10
seconds.

### Editing a message

Same pattern as everywhere else: a refused edit leaves your original message
exactly as it was; a held edit temporarily hides the message's content again
(for everyone but you) until the new wording clears.

## Channel and group names/descriptions

This works a little differently from messages, on purpose: **a channel or
group can't just "go blank" while its name is being checked** — that would
be worse than the problem it's solving. So instead:

- Creating a **new** channel/group is checked before it exists — refused
  creations just don't happen, held ones exist privately until cleared.
- **Editing** the name or description of a channel/group that's already live
  works like this: if the new name/description is refused *or* held, the
  change simply doesn't apply, and everyone keeps seeing the **previous,
  already-approved** name/description. There's no "checking…" state visible
  to members — either your change goes through, or it doesn't (yet), full
  stop.

## Live streaming

### Stream title and description

These go through the normal process, same timing as a post (typically under
2 seconds, 30-second ceiling) — checked before your followers are notified
that you've gone live, so a bad title never reaches an inbox.

### Live chat, while you're streaming

This is the one part of the whole platform with genuinely no "held" state at
all, because a live chat message that sits "pending" for even a few seconds
stops being live.

- Every chat line is checked **instantly** (a third of a second or so).
- **Clean** → shown to everyone watching, right away.
- **Confidently bad** → silently never shown. No error, no notice — it's
  simply not delivered, the same way a heckler's mic being cut isn't
  announced.
- **Borderline** → also hidden by default. Unlike everything else on the
  platform, "not sure" defaults to *not showing it*, because there's no queue
  that catches up later on a live stream — either it's judged safe enough to
  show right now, or it isn't shown.

You will never see a "your chat message is being reviewed" state while
watching a stream, and you won't get a notification if a chat line of yours
doesn't appear — it happened too fast and too often to make that a good
experience.

## Common questions

**"My message shows as sent but the other person says they can't see it."**
It's likely held for a quick check — it'll appear for them within seconds in
almost all cases. If it's been much longer than that, it may have been
refused on re-check during an edit, or there's a temporary system issue (see
the [main guide](README.md) for what to do).

**"I tried to rename our group and nothing happened."**
If the new name didn't pass the check, the rename is simply not applied and
the group keeps its old name — this is intentional, so the group never ends
up with a blank or broken name while something is "pending."

**"Why didn't my message show up during the live stream?"**
Live chat has no "hold and review" step — a message that looks even slightly
risky is just not shown, instantly, with no notice. This is different from
every other kind of content on the platform, and it's the trade-off for chat
appearing in real time.
