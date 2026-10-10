# Test matrix

Status vocabulary: `PASS` means the named, bounded test ran and its verifier
passed; `FAIL` means it ran and failed; `NOT_RUN` means required evidence is
missing. Inner-loop tests never promote an M0–M4 gate by themselves.

| Area | Required evidence | Current status | Blocker/evidence |
|---|---|---|---|
| Forge 65 lifecycle | all published patches, load + lifecycle | NOT_RUN | current-source targeted lifecycle evidence now includes Forge 65.1.1 and the 65.0.0 floor; the full chat/movement/menu/save/restart matrix remains absent |
| M0 bootstrap | 24h exact-JAR dual-client stability | NOT_RUN | no Linux/Xvfb worker |
| Chat → action | ordinary chat, valid model, physical action | NOT_RUN | current MiMo credential returned HTTP 401; real Actor/Observer absent |
| Movement | follow/waypoint/parkour in external client | NOT_RUN | controlled GameTests only |
| Inventory/mutation | ordinary menu and world mutation in external client | NOT_RUN | External gate still lacks real Actor/Observer/model; Oracle now requires Forge `PlayerEvent.ItemPickupEvent`, and deterministic mutationGate catches 10/10 fault variants |
| Restart/persistence | two exact-JAR server boots, stable UUID/revision/SavedData state | NOT_RUN | `20260809T022742Z-two-boot-audit` verifier PASS on dirty source; formal release gate remains NOT_RUN until release-eligible archive |
| Xaero waypoint | authorized shared point to physical no-teleport arrival | NOT_RUN | parser, authorization, persistence and same-dimension `move_to` playbook are covered; third-party UI + real-model client run absent |
| M1 foundation | 100 unseen Hardcore seeds, zero intervention | NOT_RUN | no statistical run |
| M2 completion | 200 unseen Hardcore seeds, dragon + return | NOT_RUN | no statistical run |
| M3 companion | 100h world, farms, transport, social scenarios | NOT_RUN | no soak/scenario archive |
| M4 speed | hidden 1,000-seed distribution | NOT_RUN | evaluator + fail-closed shard aggregator implemented; no executed 1,000-case archive |
| Render/UI/skin | two clients and manual visual acceptance | NOT_RUN | no rendered client archive |
