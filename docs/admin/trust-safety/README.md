# Trust & Safety — keeping content safe

Three surfaces that answer three genuinely different questions. Mixing them up
is the most common mistake when building this part of the dashboard.

| Doc | The question it answers | About content that is… |
|---|---|---|
| [automated-moderation.md](automated-moderation.md) | "Is this over the line?" | **not yet visible to anyone but its author** — deciding it *publishes or buries* |
| [content-moderation.md](content-moderation.md) | "Was this report valid?" | **already live** — deciding it *takes down or keeps* |
| [safety-reports.md](safety-reports.md) | "What do we do about this account?" | the **reporter/reported relationship** — triage, strikes, appeals |

## Two queues, not one

The platform has **two moderation inboxes** and they are deliberately separate:

- `GET /api/v1/admin/moderation/review` — the **proactive** queue. Fed by the
  toxicity classifier when it could not decide on its own. Rows carry per-label
  scores, the threshold band that applied, and the model version. Content here
  has never been seen by another user.
- `GET /api/v1/admin/moderation/queue` — the **reactive** inbox. Fed by user
  reports, failed media scans and keyword hits. Rows carry reporter counts and
  report reasons. Content here is live right now.

They need different row shapes, different mental models, and a mis-click has
opposite consequences. Do not merge them in the UI.

## The two levers on bad words

| | Blocklist | Model retrain |
|---|---|---|
| Effect | immediate, next request | after a training run + a human promote |
| Precision | exact/normalised string match | learned, generalises |
| Use for | a slur trending *right now* | durable improvement |

Both are documented in [automated-moderation.md](automated-moderation.md); the
blocklist CRUD itself lives on the content-moderation endpoints.

## Deeper reading

The full moderation subsystem — design, architecture, the two Python
containers, end-user behaviour — is [`../../moderation/`](../../moderation/README.md).
This directory is only the dashboard slice.
