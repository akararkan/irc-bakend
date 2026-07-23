# Calls (voice / video)

Voice and video calls over the existing per-user SSE stream. The server owns the
**call lifecycle** (ring → answer → end) and is a **blind relay** for WebRTC
signaling (SDP offers/answers + ICE candidates). The audio/video **media never
touches the server** — it flows peer-to-peer between clients (place a TURN/SFU in
front of them for NAT traversal / large group calls). Works for 1:1 and group
conversations: starting a call rings every other active member. **One live call
per conversation.**

Base path: `/api/v1`. Auth: `Authorization: Bearer <accessToken>`.

## Lifecycle

`RINGING` → *(accept)* → `ONGOING` → *(end)* → `ENDED`. Other terminal states:
`DECLINED` (1:1 rejection), `CANCELLED` (initiator hung up before answer),
`MISSED` (rang out — set by a sweep every 20s once a call has rung ≥ 60s).

## Endpoints

| Method & path | Body | Does |
|---|---|---|
| `POST /conversations/{id}/calls` | `{ "type": "VOICE" \| "VIDEO" }` | Start (or return the in-progress) call; rings the other members. → `201 CallResponse` |
| `GET /calls/{callId}` | — | Current call state. → `CallResponse` |
| `POST /calls/{callId}/accept` | — | Answer; you join, call → `ONGOING`. → `CallResponse` |
| `POST /calls/{callId}/decline` | — | Reject. 1:1 → call ends; group → you drop out. → `200` |
| `POST /calls/{callId}/end` | — | Hang up / leave. Ends the call when nobody is left (or the initiator cancels a ring). → `204` |
| `POST /calls/{callId}/signal` | `CallSignalRequest` | Relay one WebRTC frame to a peer. → `200` |

### CallSignalRequest
```json
{ "toUserId": "<uuid>", "kind": "OFFER" | "ANSWER" | "ICE", "payload": "<opaque SDP / ICE JSON>" }
```
`payload` is relayed verbatim; the server never parses it.

### CallResponse
```json
{
  "id": "<uuid>", "conversationId": "<uuid>", "initiatorId": "<uuid>",
  "type": "VIDEO", "status": "ONGOING",
  "participants": [ { "userId": "<uuid>", "state": "JOINED", "joinedAt": "…", "leftAt": null } ],
  "startedAt": "…", "answeredAt": "…", "endedAt": null
}
```

## Realtime (multiplexed on `/messaging/stream`)

| event | payload field(s) | when |
|---|---|---|
| `call.incoming` | `call` | you're being rung |
| `call.accepted` | `call`, `userId` | someone answered |
| `call.declined` | `call`, `userId` | someone declined (group) |
| `call.participant` | `call`, `userId` | a participant joined/left (group) |
| `call.ended` | `call` | the call is over |
| `call.signal` | `signal` (`CallSignalMessage`) | a WebRTC frame addressed to you |

`CallSignalMessage`: `{ "callId", "fromUserId", "kind", "payload" }`.

## Client flow (WebRTC)
1. Caller `POST /conversations/{id}/calls` → a `call.incoming` fans out to peers.
2. Callee `POST /calls/{id}/accept`; peers then exchange
   `POST /calls/{id}/signal` OFFER/ANSWER/ICE, each delivered to the target as a
   `call.signal` event.
3. Media connects peer-to-peer. Either side `POST /calls/{id}/end` to hang up.

## Notes
- A blocked DM cannot be called (`403 BLOCKED`).
- Media transport (STUN/TURN/SFU) is deployment configuration, outside this API.
