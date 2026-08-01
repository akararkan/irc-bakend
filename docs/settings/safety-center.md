# Safety Center (§18)

**[B] Backend-owned.** Package `settings.safety`.

## Reports — a state machine

`reports` moves through:

```
SUBMITTED → TRIAGED → ACTIONED | DISMISSED → (APPEALED → UPHELD | REVERSED)
```

`ReportService`:
- `submit(...)` is rate-limited and **dedups by `(target_id, reason)`** via a
  `group_key`: an open report by the same reporter for the same target+reason
  returns the existing row instead of a duplicate (moderators see one queue item
  with a count, not 500 identical rows).
- **The reporter sees only their own reports and a coarse outcome**
  (`UNDER_REVIEW` / `ACTION_TAKEN` / `NO_ACTION` / `APPEAL_UNDER_REVIEW`). The
  specific `resolution` taken against the target is the target's **private**
  moderation record and is never disclosed — disclosing it enables harassment.
- `appeal(...)` — reporter-only, allowed only from `ACTIONED`/`DISMISSED`.

`ReportReason`: spam, harassment, hate speech, misinformation, nudity/sexual,
violence, impersonation, self-harm, copyright, other. `ReportTargetType`: user,
post, comment, research, question, answer, message, channel, story.

## Strikes with decay

`user_strikes` accumulate with a **90-day expiry** (`StrikeService`), each linked
to the report id so an appeal can be reviewed against real evidence. Active
(non-expired) strikes drive automated restrictions.

## Security score — derived, never stored

`SecurityScoreService.computeScore(userId)` reads the user's actual config
(2FA on? email verified? recent activity? recovery configured?) and returns a
scored checklist with a level (`LOW`…`EXCELLENT`) and per-item recommendations.
Because it is **computed**, it is always current and needs no migration when a
rule is added.

## API

```
POST /api/v1/safety/reports          {targetType,targetId,reason,details}
GET  /api/v1/safety/reports          own reports (coarse outcome only)
POST /api/v1/safety/reports/{id}/appeal
GET  /api/v1/safety/strikes          active strikes
GET  /api/v1/safety/score            derived checklist
```

> The moderator-facing surface (triage, action, issue-strike) is exposed as
> service methods (`ReportService`, `StrikeService.issueStrike`) but has no
> reporter-facing endpoint — the admin queue is out of this module's scope.
