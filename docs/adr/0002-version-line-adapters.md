# ADR 0002: one product JAR per Minecraft/Forge line

## Decision

Keep Forge 65 as the current 26.2 module with a locked API floor. A future Forge
66 line gets a separate adapter and product JAR only after an official release
and full compatibility evidence.

## Rationale

Widening `mods.toml` or copying a 65 mapping across a new major version can load
while silently breaking menus, mappings, rendering, or persistence. The
compatibility declaration therefore separates eligibility from test evidence.

## Consequence

`compat-checker` fails malformed declarations; an unverified patch remains
unverified. Forge 66 is explicitly not claimed.
