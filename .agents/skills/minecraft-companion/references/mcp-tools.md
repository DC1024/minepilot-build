# MCP tool contract

The server endpoint is loopback-only, normally `http://127.0.0.1:25766/mcp`, and requires a bearer token. Tool results are JSON encoded inside MCP text content.

## Read tools

- `observe {}`: server-authoritative body state plus separate `goalRevision`, `decisionEpoch`, `serverTick`, and body `sessionGeneration`; never an observer camera.
- `goal_status {}`: active goal, revision, status, detail code, and evaluation lock.
- `get_screenshot {}`: redacted first-person capture if supported; otherwise an explicit unavailable code.
- `get_audit_summary {}`: bounded action/provenance and memory-backpressure counters without secrets.

## Write tools

- `set_goal {"goal":"..."}`: install one high-level goal.
- `cancel_goal {}`: request cancellation at the next safe checkpoint.
- `say {"message":"..."}`: send visible `[AI]` system chat, at most 512 characters.
- `add_waypoint {"name":"...","dimension":"namespace:path","x":0,"y":64,"z":0}`: store an explicitly shared point. It does not teleport, inspect the destination, or authorize nearby terrain changes.

All write tools reject with `evaluation_locked` after a Hardcore evaluation begins. Do not retry. `accepted: false` means no requested mutation occurred. If a write response is missing, its outcome is unknown; read back the relevant revision and state before considering another action.

Important statuses:

- `RUNNING`: a goal is active.
- `CANCEL_PENDING`: local execution is moving to a safe checkpoint.
- `COMPLETED`: the requested outcome passed local verification.
- `FAILED`: execution ended with a bounded failure code.
- `SAFE_IDLE`: connectivity or safety policy stopped risky work.
