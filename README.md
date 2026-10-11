# MinePilot Build

> This repository contains **only** the build source for the MinePilot / `mcai_companion` Forge mod.

`mcai_companion` is a Minecraft Forge mod (Forge 26.2, mod id `mcai_companion`, licensed LGPL-3.0).
This repo builds the mod jar. All non-build content (deployment, web control panel, docs, skills,
end-to-end tests, planning/community docs) has been moved out to a separate repository.

## Repository layout (build only)

| Path | Purpose |
|------|---------|
| `src/` | Forge mod source (Java) |
| `compat/` | Compatibility module |
| `gradle/`, `gradlew`, `gradlew.bat` | Gradle wrapper |
| `build.gradle`, `settings.gradle`, `gradle.properties` | Gradle build configuration |
| `.github/workflows/build-jar.yml` | CI: builds `mcai_companion-*.jar` on push to `main` |
| `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md` | Legal / licensing |
| `.gitignore`, `.gitattributes` | Repository configuration |

## Building

```bash
./gradlew build --no-daemon -x test
```

The artifact is produced at `build/libs/mcai_companion-*.jar`.

## Split from `minepilot-panel`

The non-build content previously in this repository has been relocated to **`minepilot-panel`**,
which is now the main project:

- Deployment bundle (Docker Compose, server config)
- `mcai-panel/` web control panel (RCON / docker.sock based)
- Usage docs, runbooks, and guides
- Skills, end-to-end tests, and planning / community documentation

👉 **https://github.com/DC1024/minepilot-panel**

`minepilot-build` is now exclusively the build / CI source for the Forge mod jar. The jar it
produces is consumed by the deployment defined in `minepilot-panel`.
