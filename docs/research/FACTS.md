# Verified platform facts

Last checked: 2026-08-23 02:05:00 +09:00.

## Live Forge discovery recheck

The release discovery command was rerun against the official Forge promotions
document and the 26.2 download index on 2026-08-23:

```text
python3 scripts/discover-forge-lines.py --json --check-patches
status=FAIL before the compatibility lock refresh; observed Forge 65 /
Minecraft 26.2; latest=65.1.2; missingAdapters=[];
missingPatches=[65.1.2]; stalePatches=[]
```

No Forge major >=66 is currently promoted by the official source, so the
separate-adapter policy remains active and no Forge 66 product claim is made.

## Numen architecture comparison

The public [Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen)
README describes a server-side `ServerPlayer` body, an egocentric semantic
representation, bounded skills/tools, and feedback from every tool result.
Those are useful architectural comparison points for this project, not code
or assets to copy. This repository keeps its own Forge 65 implementation,
fair first-person ray budget, vanilla menu/action path, SQLite audit trail,
and stricter no-hidden-state policy. The new `localGeometry` field is an
independent, bounded summary of surfaces already observed by the companion's
finite rays; it does not implement a world scan or Numen source transplant.

## Xiaomi MiMo authentication

- MiMo's official OpenAI-compatible examples use the `api-key` request header
  for both pay-as-you-go (`sk-...`) and Token Plan (`tp-...`) credentials.
- The endpoint remains `https://token-plan-cn.xiaomimimo.com/v1` for the China
  Token Plan cluster and the model is selected in the JSON body.
- The production gateway now selects `api-key` only for validated
  `*.xiaomimimo.com` hosts and retains `Authorization: Bearer` for other
  OpenAI-compatible hosts. It does not send or persist credentials in logs or
  evidence.
- A real one-request probe after this header correction still returned HTTP
  401 for the currently persisted credential. That is a credential/provider
  failure, not a gameplay result; the formal model gates remain `NOT_RUN`.
- MiMo's current Token Plan terms state that the subscription is intended for
  coding tools and prohibit using the Token Plan key for automated scripts or
  custom application backends. This Mod's continuous game-control gateway is
  therefore not an allowed use of a Token Plan credential unless Xiaomi gives
  explicit authorization; use an API credential whose terms permit this
  integration for formal model gates.

Primary sources:

- <https://mimo.mi.com/docs/en-US/quick-start/summary/first-api-call>
- <https://mimo.mi.com/docs/zh-CN/tokenplan/Token%20Plan/quick-access>
- <https://mimo.mi.com/docs/zh-CN/tokenplan/Token%20Plan/subscription>
- <https://mimo.mi.com/docs/zh-CN/quick-start/faq/api-integration>

## Forge 26.2

- The official Forge 26.2 download index lists **65.1.2** as Latest and
  **65.1.0** as Recommended (page rechecked 2026-08-11 UTC).
- The official index lists the complete 26.2 set as 65.0.0 through 65.0.9,
  plus 65.1.0, 65.1.1, and 65.1.2. Forge 66 is not present in the official promotion list at this
  check, so no Forge 66 adapter or compatibility claim is made.
- The official source branch is `26.2`. The observed branch head during this
  check was `f0f144156b5c8d7ccbe358772e9e33b57d849d5e` (the signed Forge
  commit dated 2026-08-11).
- Forge 26.1+ uses Java 25.
- Forge source and MDK code are LGPL-2.1-only. The project remains
  Apache-2.0; Forge is a build/runtime dependency and is not embedded in the
  product JAR.

The official index and promotions document were rechecked on 2026-08-23
02:05 JST: they report 65.1.2 as Latest and 65.1.0 as Recommended for
Minecraft 26.2, list 65.0.0--65.0.9, 65.1.0, 65.1.1, and 65.1.2, and have no
Forge 66 release entry. The 65.1.2 MDK SHA-1 published by the official index
is `cc7d327f5460068f6e7d08bc0b0089e5c5b29202`. This repository therefore
keeps a separate-adapter policy for any future 66 line and does not widen the
Forge 65 product claim.

The release workflow also runs `scripts/discover-forge-lines.py` against the
official promotions document. If a newly promoted Forge major at or above 65
has no declared adapter module, discovery fails closed; a passing discovery
result is not a compatibility or gameplay test.

Primary sources:

- <https://files.minecraftforge.net/net/minecraftforge/forge/index_26.2.html>
- <https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json>
- <https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml>
- <https://github.com/MinecraftForge/MinecraftForge/tree/26.2>
- <https://github.com/MinecraftForge/MinecraftForge/blob/26.2/LICENSE.txt>
- <https://docs.minecraftforge.net/en/latest/gettingstarted/modfiles/>

The current page also reports 65.1.2 as the 26.2 latest build and 65.1.0 as
recommended, and lists the 65.x release dates and MDK checksums (rechecked
2026-08-23 JST); this repository still treats
the runtime range as a declaration only until every listed patch has a real
load/chat/movement/menu/save regression.

## Compatibility decision

The Forge 65 product:

- compiles against the 65.0.0 API floor;
- declares Forge `[65.0.0,66.0.0)` and Minecraft exactly `26.2`;
- must run the same frozen product JAR on every published 65.x patch;
- must run full real E2E on at least 65.0.0 and current Recommended;
- must not claim Forge 66 compatibility.

The version range is only an eligibility declaration. It is not test evidence.
Current cross-patch status remains `NOT_RUN` for the present source/product
state until the 11-patch runtime matrix is completed.

## Headless client decision

HeadlessMC supports Forge and an LWJGL headless mode in general, but its public
version-specific support table does not establish Minecraft 26.2 support.
Therefore the initial black-box harness uses this repository's own small Forge
65 test-client Mod and a normal client in offscreen Linux rendering. HeadlessMC
can be added only after a 26.2 capability probe passes.

Primary sources:

- <https://headlesshq.github.io/headlessmc/launch/>
- <https://headlesshq.github.io/headlessmc/specifics/>
- <https://www.x.org/archive/X11R7.5/doc/man/man1/Xvfb.1.html>
