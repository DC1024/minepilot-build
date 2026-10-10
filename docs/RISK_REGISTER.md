# Risk register

| ID | Risk | Impact | Mitigation | Current state |
|---|---|---|---|---|
| R1 | Provider key/entitlement invalid | no model action | secret-store restore, visible error, local emergency lane, no false retry | observed MiMo HTTP 401 |
| R2 | Provider latency or stale response | “speaks but does not act” | single-flight, bounded deadlines, revision rejection, local safety | code covered; external gate NOT_RUN |
| R3 | Headless spawn deadlock | body absent/server hang | async anchor load; ordinary PlayerList lifecycle | fixed; 4 cached patch lifecycle smoke passes |
| R4 | Physics/AI control oscillation | death or unnatural motion | 20-TPS local controller, skill leases, emergency ownership | controlled fixtures only |
| R5 | Prompt injection through world text | unauthorized actions | untrusted-source labels and typed allowlist | unit coverage; external gate NOT_RUN |
| R6 | Secret leakage in saves/logs | credential compromise | redaction, OS/process secret boundary, audit scan | code/audit coverage |
| R7 | Forge patch/API drift | load or behavior break | locked compat declaration and checker, per-patch archive | formal matrix NOT_RUN |
| R8 | Model claims success without evidence | user loses trust | server completion predicates and truthful speech boundary | implemented; natural tasks NOT_RUN |
| R9 | Long-term memory growth/corruption | degraded companion | SQLite WAL, bounded context, migrations and backups | soak gates NOT_RUN |
| R10 | Overclaiming professional companion | unsafe expectation | status docs and release gates remain NOT_RUN until evidence | active restriction |
