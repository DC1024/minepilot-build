# M0 bootstrap verification

## Exit criteria

- Exact installable JAR starts on the declared Forge floor and recommended
  runtime.
- AI body is present with zero humans and beside a newly logged-in player.
- Save/restart, death/respawn, chunk loading, SQLite Jar-in-Jar, and clean
  removal are verified.
- 24-hour dual-client soak has an archived manifest.

## Current result

`NOT_RUN`. The cached Forge lifecycle subset is real evidence: zero-human and
auto-presence each pass on 65.0.0, 65.0.8, 65.0.9, and 65.1.0. Exact-JAR
server smoke passes on 65.1.0. The 24-hour and external dual-client portions
are blocked by the missing Linux/Xvfb worker and are not inferred from these
server tests.
