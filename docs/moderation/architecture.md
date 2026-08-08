# Moderation — implementation architecture

How `MODERATION_ROADMAP.md` is actually built in this codebase. The roadmap is
the design; this is the map from design to files, and the honest record of where
the two differ.

Section markers like `§5.4` refer to the roadmap.

---

## 1. Where everything lives

```
src/main/java/ak/dev/irc/app/moderation/
  ModerationProperties.java        app.moderation.* bootstrap defaults
  enums/
    ModeratedEntityType            13 surfaces + their latency budgets and policies
    ModerationStatus               PENDING | APPROVED | REJECTED | IN_REVIEW
    ModerationVerdict              APPROVE | REVIEW | REJECT (+ worstOf aggregation)
    ModerationLabel                the six model heads + lenient wire parsing
    FallbackPolicy                 FAIL_CLOSED | FAIL_OPEN_SHADOW
    TrainingExampleSource, ModelVersionStatus
  entity/ repository/              6 Postgres tables (§2 below)
  client/
    ModerationInferenceClient      JDK HttpClient → :8000, retry + circuit breaker
    ModerationTrainingClient       JDK HttpClient → :8001
    ModerationCircuitBreaker       hand-rolled sliding window
    ScoreResult, InferenceUnavailableException
  engine/
    ModerationSettingsService      DB override → yaml default resolution + cache
    ModerationThresholds           per-label (low, high) bands
    ModerationDecisionEngine       scores + thresholds → verdict  (pure, stateless)
    FieldDecision
  service/
    ContentModerationService       THE gateway every content surface calls
    ModerationSubmission / TextField / Outcome
    ModerationApplier + Registry   deferred-verdict appliers
    ModerationNotifier             author notifications
    ModerationTrainingService      dataset, retrain, gate, promote, rollback
    ModerationMetricsService       §12.5 analytics
  applier/                         one per surface with a deferred path
  worker/                          RabbitMQ event + publisher + @RabbitListener
  job/
    ModerationSlaSweeper           §5.6 — the safety net
    ModerationTrainingPoller       chases in-flight fine-tunes

src/main/java/ak/dev/irc/app/admin/moderation/
  AdminAutoModerationController        review queue, decisions, metrics
  AdminModerationModelController       dataset, retrain, registry, promote/rollback
  AdminModerationSettingsController    thresholds, hold durations, dry-run
  (AdminModerationController)          PRE-EXISTING reports/media/keyword inbox

docs/model-inference/    FastAPI + PyTorch scorer   (container, :8000)
docs/model-training/     FastAPI fine-tune runner   (container, :8001)
```

---

## 2. Data model

Six new Postgres tables, created by `ddl-auto: update` — there is no Flyway in
this project.

| Table | Roadmap | Notes |
|---|---|---|
| `moderation_cases` | §9 `moderation_entities` | One row per submitted unit. `entity_ref` is a **String**, not a UUID: posts are UUIDs, chat messages are 64-bit snowflakes, and one table holds both. |
| `moderation_case_fields` | §9 `moderation_fields` | One row per text field, with its raw score vector as JSON. |
| `moderation_training_examples` | §9 `training_examples` | `text_hash` is unique — a sentence promoted twice from two review rows lands once. |
| `moderation_golden_cases` | §17 | Regression suite. **Never trained on** — training on it would make the suite measure memorisation. |
| `moderation_model_versions` | §9 `model_versions` | The registry. Rollback re-promotes a `RETIRED` row; it never re-runs training. |
| `moderation_settings` | §8.1 | Key/value overrides. A row exists only where an admin has changed something. |

**The audit log is not a new table.** §9's `moderation_audit_log` maps onto the
existing `moderation_decisions`, written through `ModerationRecorder`. Automated
verdicts therefore sit in the same trail as admin takedowns, with actions
`AUTO_APPROVED` / `AUTO_REJECTED` / `SENT_TO_REVIEW` / `HELD` and metadata
carrying the label, score, model version and SLA flag. A second parallel log
would have to be reconciled with the first, forever.

**The case owns its own copy of the text.** Deliberately, not as denormalisation:
the review queue has to render what was submitted after a Cassandra TTL expiry, a
hard delete, or an edit — none of which this table controls, and all of which are
exactly when a moderator most needs to see the original.

### Held representation per surface

The pipeline is uniform; how "held" is stored is not, because these surfaces
store content in four different ways.

| Surface | Held as | Read gate |
|---|---|---|
| Post / Reel | `posts_by_id.status = PENDING_REVIEW` | `PostHydrator.isServable` (existing), single-post 404 gate (existing), `GlobalSearchService.DEAD_STATUSES` (already lists `PENDING_REVIEW` + `REJECTED`) |
| Post comment / reply | new `moderation_status` column on `comments_by_post`, `replies_by_comment`, `comment_lookup` | `PostHydrator.visibleToViewer` in all four hydrate methods |
| Story | new `moderation_status` on `stories_by_author`, `story_lookup` | `CassandraStoryService.canView`, evaluated **before** audience visibility |
| Story poll | none — terminal verdict only | poll endpoints are unauthenticated and apply no story-visibility check, so there is no held state that would actually hide one |
| Research / comment | new `moderation_status` JPA column | `publish()` gate + repository predicates |
| Q&A question / answer | new `moderation_status` JPA column | repository visibility queries |
| Chat message / channel post | new `moderation_status` **and `delivered`** on both Cassandra message tables | `ChatMapper` redaction (viewer-aware) + both `MessageQueryService` hydrate funnels |
| Channel / group meta | new `moderation_status` on `Conversation` | strict edit policy: the previously approved title stays visible |
| Stream meta | new `moderation_status` on `LiveStream` | `start()` gate suppresses the follower fan-out |
| Live chat | **none** — ephemeral | decided inline, released or dropped (§6) |
| Share caption / highlight title | none — terminal verdict only | no held representation exists for these rows |

Chat needs a second column, `delivered`, because "has this message been fanned
out?" is not derivable from anything else. A message held at send and later
edited looks identical to one that was delivered and then edited into a hold —
same `editedAt`, same held marker, same case revision — and the two need opposite
treatment when the verdict lands: first delivery versus an edit broadcast. Guess
wrong in one direction and every recipient gets the message twice; guess wrong in
the other and it is never delivered at all, readable on refresh but silently
never announced.

New Cassandra columns are added idempotently by
`post/cassandra/schema/ModerationSchemaInitializer` and
`chat/cassandra/ChatCassandraSchemaInitializer` — Spring Data's
`create_if_not_exists` only ever CREATEs, never ALTERs. A `NULL` status means
"predates moderation, or cleared" and reads as approved, matching the null-status
convention `isServable` already used.

---

## 3. The request path

`ContentModerationService.submit(ModerationSubmission)` — one method, called
before anything is persisted.

```java
UUID id = UUID.randomUUID();                      // 1. mint the id first
ModerationOutcome m = contentModeration.submitOrThrow(
    ModerationSubmission.of(POST, id.toString(), authorId)
        .field("body", text)
        .field("location", locationName));         // 2. score before persisting
row.setStatus(m.postStatus());                     // 3. persist held or published
postRepo.save(row);
if (m.held()) return row;                          // 4. skip publication side effects
publishSideEffects(row, ...);                      // 5. fan-out, index, tags, activity
```

Inside `submit`:

1. **Master + per-type switch.** Off → deny-list only (`blocklistOnly`), which
   preserves exactly what the four Cassandra create paths did before this system
   existed. Turning off the classifier must not quietly turn off the blocklist.
2. **No text** → passthrough. Media-only content has nothing for a text
   classifier to say; image moderation is out of scope (§14).
3. **Deny-list per field** (§8.2). A hard hit rejects without ever calling the
   model. A soft flag forces at least review.
4. **Inline batched scoring.** All fields in one `/v1/score/batch` call, bounded
   by the entity type's inline budget. Per-field verdicts, aggregated worst-wins
   (§5.4).
5. **`InferenceUnavailableException`** → the case stays `PENDING`, a
   `moderation.requested` message goes to `irc.queue.moderation`, and the SLA
   sweeper backstops it. **Never** a silent approval — §7.4 is explicit that "the
   model didn't answer" and "the model said it's clean" must stay distinguishable.

`submit` runs `REQUIRES_NEW`. Content services call it from inside their own
write transaction, and the case row — a rejection especially — has to survive the
caller rolling back; losing the record of *why* something was refused would make
the decision unexplainable, which §3.3 forbids.

### Two submit variants, and why

| Method | Refuses on | Use for |
|---|---|---|
| `submitOrThrow` | `REJECTED` only | Surfaces with a held representation — posts, comments, stories, research, Q&A, chat, channels, streams. A borderline verdict persists the content held and an applier finishes it later. |
| `submitOrRefuse` | anything but `APPROVED` | Surfaces with **no** held representation — share captions, highlight titles, story-poll prose, channel admin labels, research/Q&A sub-resource text. |

The second exists because those rows carry no status column and have no applier:
nothing would ever revisit a held verdict, so a borderline score — or an
inference outage, which returns `PENDING` — would publish permanently
unreviewed. They are strictly stricter as a result: text the model is merely
unsure about is refused rather than queued. For one-line annotations that is the
right trade.

### Why reject-before-persist

The roadmap's §5.2 writes a staging row first. Here a rejection persists nothing
at all, because the case row already holds the text. That preserves the contract
the keyword blocklist had on these endpoints (a blocked post 400s and leaves no
row), avoids a delete-cascade for content that was never real, and still gives
the moderator the full evidence.

---

## 4. The deferred path

Three ways a verdict arrives late:

- **Queue worker** (`ModerationWorker`) — drains `irc.queue.moderation` and calls
  `rescore`. It deliberately re-throws when the case is still undecided, so the
  listener's exponential back-off applies rather than acking a case nobody will
  look at again. A dead-lettered moderation message is *fine*: the row is still
  `PENDING` in Postgres and the sweeper owns it.
- **SLA sweeper** (`ModerationSlaSweeper`, every 5s) — three passes: retry inside
  the window, apply the fallback policy past the deadline, and re-drive verdicts
  whose content flip failed. It depends on nothing but the database, which is the
  whole point: it still works when the broker and the model are both down.
- **Admin decision** — `decideManually` from the review queue.

All three funnel into `applyIfNeeded` → `ModerationApplier` for that entity type.
Ten appliers exist, one per surface with a deferred path; `STORY_POLL`,
`LIVE_CHAT` and `CONTENT_ANNOTATION` have none, because their verdict is always
terminal (there is no held representation to flip later).

Appliers are discovered by Spring — no registration step — and must be
idempotent: the sweeper re-drives failed applies, and a second run of a
50k-follower fan-out would notify everyone twice.

Two wiring constraints, both load-bearing:

- **`ModerationApplierRegistry` resolves lazily.** There is a real cycle:
  `ContentModerationService → registry → PostModerationApplier →
  CassandraPostService → ContentModerationService`. Spring Boot 3 rejects
  circular references by default, so resolving appliers in the registry's
  constructor fails the context at startup. Holding an `ObjectProvider` and
  resolving on first use breaks the cycle at bean-creation time. Appliers that
  need a service rather than a repository take an `ObjectProvider` for the same
  reason.
- **`submitOrThrow` calls `submit` through the bean's own proxy.** Without the
  `@Lazy` self-reference, `REQUIRES_NEW` is silently skipped on the
  self-invocation: called from a JPA service's own transaction, the case row
  would join it, and the `CONTENT_REJECTED` exception would roll back the record
  of why the content was refused.

`ModerationCase.appliedAt` is the idempotence marker. A terminal case with a null
`appliedAt` is a verdict that never reached the content — approved posts that
never publish — which is the quietest and worst failure this system can have, so
`findUnapplied` is swept explicitly. `notifiedAt` is separate because `IN_REVIEW`
is not terminal and so never stamps `appliedAt`; without it, every re-drive would
send the author another "being reviewed" bell for the same case.

Three ordering hazards the deferred path has to handle, all of them found by
adversarial review rather than by design:

- **Admin reversal.** `decideManually` clears both stamps before re-driving.
  Content that cleared *inline* is already marked applied, so without that an
  admin rejecting it would flip the case row, write an audit entry, and change
  nothing — the content would stay visible and the author would never be told.
- **Superseded revisions.** Opening a case for an edited unit retires any still-
  open case for the same content. Otherwise the older case, holding the *previous*
  text, eventually settles and hands its stale verdict to the applier.
- **Apply-before-content-exists.** The queue message is published when `submit`'s
  `REQUIRES_NEW` transaction commits — before the caller has written the content
  row, which for Cassandra has no transaction to ride on at all. A worker winning
  that race would decide the case, find nothing to flip, and still stamp
  `appliedAt`, leaving the content held forever. `rescore` therefore refuses to
  settle a case younger than a two-second grace window.

Edits get one extra guard: `PostModerationApplier.onApproved` branches on
`revision > 1` and re-indexes rather than re-running `publishSideEffects`. A post
that already published once has a `feed_by_user` row in every follower's
timeline; a second fan-out would duplicate it up to 50,000 times and fire a
POST_NEW notification for each.

---

## 5. Decision engine

Two thresholds per label create three bands (§8.1). Both edges compare with
`>=`: a score exactly equal to `high` auto-blocks, one exactly equal to `low`
goes to review — the stricter reading of each boundary.

"Top label" is the label that most *drives the verdict*, not simply the highest
number: a threat at 0.55 crossing its 0.50 block bar matters more than an insult
at 0.70 that does not cross its 0.80 bar. The queue sorts on that.

Thresholds are a **parameter**, not an internal lookup. That is what makes
`POST /settings/dry-run` possible: proposed bands are replayed against score
vectors already stored on `moderation_case_fields`, so an admin sees exactly
which past decisions would flip before writing anything.

---

## 6. Deviations from the roadmap

Every one of these is a constraint of this codebase, not a shortcut.

| Roadmap says | Here | Why |
|---|---|---|
| Resilience4j for timeout/retry/circuit-breaker (§7.4) | Hand-rolled `ModerationCircuitBreaker` + retry loop on the JDK `HttpClient` | No Resilience4j and no spring-webflux dependency; the build runs offline. `MediaControlClient` set the same precedent for the one other outbound HTTP client in the app. **Both moderation clients pin `HTTP_1_1`** — see below. |
| `WebClient` (§7.4) | `java.net.http.HttpClient` | Same reason. Batch scoring is one round-trip per entity, so non-blocking I/O buys little here. |
| Staging table holds content, then it moves to the public store (§5.2) | Content is written in place with a held marker; a rejection persists nothing | Posts, comments, stories and chat messages live in Cassandra across denormalised tables. "Move between stores" would mean duplicating every write path. |
| Edits revert to PENDING with the old version still visible (§5.5, strict) | Posts: a rejected edit is refused and the original text kept; a borderline edit applies and re-hides the post. Channels: the previously approved title stays visible and the change is refused. | `posts_by_id` holds exactly one revision — there is no older copy to keep serving. Channels do have a stored previous value, so they get the strict behaviour. |
| Separate `moderation_audit_log` table (§9) | Existing `moderation_decisions` | Automated and manual verdicts belong in one trail. |
| Shadow deployment scores live traffic in parallel (§12.4) | `SHADOW` is a registry status; a shadow pool is not wired | Running a second inference replica pool is a deployment topology change, not application code. The status and the promote/rollback path are ready for it. |
| Live chat 2–5s rolling buffer (§6) | Scored inline against a 300ms budget, then released or dropped | A buffer adds latency to *every* message to smooth over the rare slow one. The inline path already meets the budget; the borderline-hidden default and the drop semantics are implemented exactly as specified. `livechat.buffer.ms` is stored and exposed for a client-side buffer. |

### The HTTP client must be pinned to HTTP/1.1

Not a stylistic choice. The JDK `HttpClient` defaults to `HTTP_2`, and against a
cleartext endpoint that means every request opens with an **h2c upgrade
handshake**. uvicorn/h11 does not implement h2c: it logs `Unsupported upgrade
request` and **the POST body is dropped**, so FastAPI validates an empty payload
and returns `422`.

The failure is bad in a specific, dangerous way: `GET /healthz` carries no body,
so it survives the failed upgrade untouched. The platform therefore reports
`inferenceUp: true`, `circuit: CLOSED`, and single-digit-millisecond latency —
while **every score silently fails** and all content parks in review on an SLA
breach. A green dashboard over a total outage.

Two guards exist because of this:

- Both `ModerationInferenceClient` and `ModerationTrainingClient` build with
  `.version(HttpClient.Version.HTTP_1_1)`.
- `health()` is a pure probe — excluded from the call/failure counters, the
  latency average and `lastError`. Previously a health check succeeding would
  *erase* the error from the last failed score, which is precisely what hid the
  outage. Observing the service must never alter what the service reports about
  itself.
- Scoring failures log at **WARN**, not DEBUG. A failed score is content going
  unchecked; at DEBUG a full outage produced no visible line at default levels.

Add a third client against any FastAPI/uvicorn service and it needs the same pin.

### Known open edges

Each of these is a deliberate stopping point, not an oversight. They are also
tracked in [`../admin/known-issues.md`](../admin/known-issues.md) rows Q–T.

**Platform-wide**

- **Media already uploaded.** `createMultipart` uploads to R2 before the post row
  exists, so a held post's images are addressable by direct URL. Same as the
  pre-existing behaviour for a keyword-blocked post; image moderation is out of
  scope (§14).
- **Multi-node settings cache.** `ModerationSettingsService` caches in-process for
  30s with an explicit invalidate on write, following `FeedTuningService`. On a
  multi-instance deploy the node that served the PATCH is immediate; others
  converge within 30s.
**Post surface**

- `GET /api/v1/hashtags/{tag}/posts` and `GET /api/v1/tags/{tag}/content` read
  denormalised preview rows without `PostHydrator`. Nothing held ever gets
  written there — tag extraction is a publication side effect — and
  `PostModerationApplier.onRejected` untags, so a post approved and later
  rejected does not leave its preview behind either.

**Research**

- `listSources(researchId, …)` returns source titles and citation text for any
  non-deleted paper, including a held one. Pre-existing behaviour for ordinary
  drafts — it never checked status — so the contract was left alone.
- A held *reply* is invisible to its own author when nested under someone else's
  thread: the mapper's reply filter has no viewer id, only the flat top-level
  query does.

**Q&A**

- Mention pings on a held **edit** are dropped rather than deferred. Replaying
  the delta after approval would mean storing the pre-edit text on the case.
- `maxAnswers` is not consumed by held answers, so an author can queue more
  drafts than the cap — they still cannot make more than the cap visible.
- Live SSE viewers keep a rejected answer on screen until refresh; the applier
  does not broadcast a delete.
- `acceptAnswer` / `unacceptAnswer` load by id without checking moderation state.
  A question author who guesses a held answer's id can accept it; the answer
  still does not become visible.

**Chat / channels / live**

- **A held message's *arrival* is visible, its content is not.** The inbox pointer
  still advances (it drives ordering, gap sync and unread state) carrying the same
  redacted placeholder disappearing messages already use. If the verdict is
  REJECTED that placeholder stays as the conversation's last preview — there is no
  cheap way to recompute the previous message's preview.
- **A held→rejected *edit* tombstones the whole message.** Cassandra holds one
  revision of the body, so the previously approved wording is already gone by the
  time the verdict lands.
- **Live stream media keeps flowing on a rejected title.** Title and description
  are redacted for non-hosts everywhere and the stream drops out of both
  directories, but WHEP/HLS playback is served by MediaMTX outside this app. Only
  the text was judged, so `StreamMetaModerationApplier.onRejected` deliberately
  does not end the broadcast.
- **Groups are moderated as `CHANNEL`.** There is no separate `GROUP` constant, so
  an admin queue row for a group title reads `CHANNEL`. Both are `Conversation`
  rows and the policy is identical; only the label differs.
- **An approved held DM that was routed to the requests tray** re-dispatches as a
  normal delivery (`NEW_MESSAGE` bell rather than `MESSAGE_REQUEST`). Tray
  placement itself is correct — the `MessageRequest` row is written before the
  Cassandra insert.
- **`ChannelAdminRequest.customTitle`** (an owner-set label on a channel admin,
  rendered to every subscriber) is moderated as `CONTENT_ANNOTATION`.

---

## 7. Configuration

`app.moderation.*` in `application.yaml` is the **bootstrap** layer only.
Anything an admin can retune lives in `moderation_settings` and wins. Key
namespace:

```
enabled                                   master switch
enabled.<type>                            per-entity switch
threshold.<label>.low|high                global band
threshold.<type>.<label>.low|high         per-entity override (wins)
hold.<type>.ms                            hard hold ceiling  (§5.3)
inline.<type>.ms                          synchronous budget (§5.3)
fallback.<type>                           FAIL_CLOSED | FAIL_OPEN_SHADOW (§5.6)
livechat.buffer.ms
livechat.borderline.hidden
retrain.max-f1-drop
retrain.require-human-promote
```

`inlineBudget` is clamped below half the hold ceiling. §5.3 requires the ceiling
to exceed the client timeout; without the clamp the sweeper could fire while a
request thread is still waiting on the same case, and two code paths would decide
it.

---

## 8. Testing this

The engine is pure and stateless, so the highest-value tests need no
infrastructure:

- `ModerationDecisionEngine.decide` at every boundary — `== low`, `== high`,
  between, above, empty scores, unknown label.
- `ModerationVerdict.worstOf` aggregation across fields (§5.4).
- `ModerationSettingsService` resolution order: type override → global override →
  yaml → enum default.
- `ModerationCircuitBreaker` open/half-open/close transitions.

Contract-level: `POST /v1/score/batch` must return one result per item; the
client throws rather than approving a short answer, and that behaviour is worth
pinning. See §17 for the full strategy including adversarial/evasion cases —
leetspeak, spacing, homoglyphs — which currently land on the blocklist's
`KeywordNormalizer` rather than the model.
