# ADR 0003: model is a planner, not a privileged client

## Decision

The single configured model returns only a revision-bound `DecisionEnvelope`.
The local skill supervisor, 20-TPS physics and emergency controller perform
legal actions and are authoritative for completion.

## Rationale

This limits prompt injection, stale responses, token latency, and cheating. It
also makes “speech without action” observable: acknowledgement is not reported
as physical progress until a skill starts and evidence advances.

## Consequence

When the provider is unavailable the body remains visible and can defend/eat/
retreat, but it does not invent high-level movement, menu, boat, or minecart
actions. A model run must use a real credential and the real client/server
topology to count toward formal gates.
