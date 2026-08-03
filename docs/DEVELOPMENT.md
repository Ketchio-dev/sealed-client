# Developer documentation

For building Sealed Client yourself, checking how it is verified, or
contributing. If you only want to use the client, the
[README](../README.md) is all you need.

## Building and testing

The Gradle toolchain uses Java 21 for 1.21.4 and Java 25 for 26.2.

```shell
java -version
./gradlew --no-daemon clean :common:clean :platform-26.2:clean multiVersionBuild
./gradlew --no-daemon e2eTest
```

Full quality gate:

```shell
./gradlew --no-daemon clean qualityGate
```

GitHub Actions CI runs unit tests for both platforms plus `build`, defined in
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml). Because Loom
generates mapping jars per operating system, only that command runs Gradle
verification in `lenient` mode; pinned external dependency metadata and the
local release gate stay strict. Client GameTests and the reproducibility check
are outside public CI, so before a release you also need the local quality
gate above and `scripts/verify-release.sh --repeat-builds 2`.

Cleanup and reconnect behaviour when a connection drops without warning.
Exercising the real network path needs EULA consent; without the flag the
suite only verifies that it skips. GameTests are outside public CI, so this
gate is only guaranteed locally:

```shell
./gradlew :platform-26.2:networkResilienceTest -Psealed.minecraftEula=true
```

Performance invariants, repeated 3 times by default:

```shell
scripts/performance-soak.sh
```

Combat, movement, the Baritone-absent path, presets and the 26.2 catalogue in
one gate:

```shell
scripts/competitive-integration-gate.sh
```

Temporarily downloads the official 1.21.4 Baritone API Fabric mod, checks it
against the official checksum, and runs the full Client GameTest with it
actually installed:

```shell
scripts/baritone-integration-smoke.sh
```

This is the only script that deliberately downloads an external release file.
Loom rebuilds the local mod against development mappings, so dependency
verification for the generated temporary file runs in `lenient` mode, but the
input jar is compared against the official release checksum before it runs.

Verification levels:

| Level | Covers | Does not cover |
| --- | --- | --- |
| Common + 26.2 automated tests | 90/90 catalogue, config and state machines, render and world-detection decision logic, action arbitration, Baritone absent/incompatible paths, jar/SBOM/checksum | Real GPU rendering, real external server latency |
| 1.21.4 Client GameTest | Boot on an isolated singleplayer/integrated server, GUI and HUD, config recovery, Freecam/XRay, storage and portal detection | Connecting to 2b2t |
| 26.2 Client GameTest (singleplayer) | 90/90 catalogue in a real window, ClickGUI, HUD editor, profile and preset screens, keybind toggles, small-screen clipping | Multiplayer network paths |
| 26.2 Client GameTest (dedicated server) | **Real TCP sockets.** Server-address profile auto-apply, snapshot reapply on reconnect, TPS estimation from actually received packets, disconnect cleanup, a bounded soak with every module enabled | 2b2t queue, anticheat, real population |
| 26.2 Client GameTest (high ping / low TPS) | Measured round-trip comparison through a latency proxy, real low-TPS detection and recovery forced with `/tick rate`, telling latency apart from low TPS | Fluctuating real networks, multi-hour duration |
| 26.2 graphical boot smoke | Real game window via `:platform-26.2:runClient`, main menu and `P` ClickGUI | The chunk render path (covered by the dedicated-server E2E) |

1.21.4 E2E logs land in `build/run/clientGameTest/logs/latest.log` and
screenshots in `build/run/clientGameTest/screenshots/`. The 26.2 graphical
boot is a manual smoke test separate from the automated suites, so check
release jars in a separate test instance too.

```shell
./gradlew --no-daemon :platform-26.2:runClient
```

Booting as far as the main menu never touches the chunk render path.
`SectionCompiler` is not loaded until a world draws chunks, so if you changed
a render-related mixin you must run the 26.2 Client GameTest.

### 26.2 dedicated-server and high-ping E2E

Anything that starts a dedicated server needs Mojang EULA consent. That is the
decision of whoever runs the build, so it is never recorded automatically, and
without the flag those suites skip rather than fail.

```shell
./gradlew --no-daemon -Psealed.minecraftEula=true :platform-26.2:runClientGameTest
```

This suite connects to a local dedicated server over a real TCP socket and
checks:

- Profile auto-apply keyed on server address, and snapshot reapply on
  reconnect (an integrated singleplayer server reports `singleplayer`, so this
  path cannot be verified there)
- Server TPS estimated from actually received packets, and reset on disconnect
- Measured round-trip increase when routed through the latency proxy
  (`LatencyProxy26`)
- Real low-TPS detection forced with `/tick rate`, and **telling latency apart
  from low TPS** — that a merely distant server is not mistaken for a slow one

Still not reproduced: the 2b2t queue, anticheat, real population, fluctuating
network quality, and multi-hour soaks. Long-distance Elytra and Baritone
travel are not automatically verified either. Passing these tests is therefore
not a guarantee of long-run stability on a live server.

## Measured ceilings

Instead of asserting that combat is "fast" or "accurate", each property has a
ceiling that cannot be beaten for physical or mathematical reasons, and the
question is how close we get. The numbers below come from connecting to a
dedicated server and setting off real explosions.

| Property | Ceiling | Measured | Why nothing can do better |
| --- | --- | --- | --- |
| Reaction latency | 1 tick | 1 tick (12/12 samples) | The client reads packets, runs a tick, and sends actions at the end of that tick. A reaction cannot be sent before the tick that observed its cause |
| Explosion damage prediction | 0 error | 7 of 10 exact, worst 0.500 | See below |
| Crystal placement | Optimal | Agrees with exhaustive search (2000 randomised trials) | Picking the highest-scoring candidate is optimal by definition |

Reproduce:

```shell
./gradlew :platform-26.2:combatAccuracyTest -Psealed.minecraftEula=true
```

This gate is a Client GameTest, so it is outside public CI and only guaranteed
locally. The formula it validated, and the scenario table, are pinned as unit
tests that do run on every build.

**About the remaining 0.500.** Seven of ten scenarios match the damage the
server applied, to the decimal. The three that do not are all unarmoured
close-range hits, under-predicting by 0.500, 0.167 and 0.167.

What the residual is *not* was established by instrumentation. Not distance or
line-of-sight: the game test runs the same calculation against the server's own
coordinates and blocks and gets the identical prediction. Not fall damage or
absorption hearts, both measured and ruled out. Not timing either, since
repeated runs give identical values.

The surviving hypothesis is that the server accumulates its reductions in
`float` while we compute in `double`. It fits where the error appears —
everything going through the armour path is exact, and only large unarmoured
hits drift. It is still a hypothesis rather than an established fact, which is
why it is written here instead of being quietly folded into a tolerance.

The error is one-directional: the prediction is never lower than the damage
actually dealt. For self-damage decisions, overestimating is the safe side.

**What these numbers do not say.** They are not a benchmark against other
clients; there is no competitor in the measurement, only the distance from an
absolute ceiling. And since a client cannot see everything the server sees —
enchantments on someone else's armour, for one — the real ceiling for
prediction accuracy is not "zero error" but "the smallest error reachable from
what a client can observe".

## Release bundle and SHA-256

```shell
scripts/verify-release.sh
```

`build/multiversion-release/` will contain:

- `sealed-client-mc1.21.4-1.0.0.jar`
- `sealed-client-mc1.21.4-1.0.0-sources.jar`
- `sealed-client-mc26.2-1.0.0.jar`
- `sealed-client-mc26.2-1.0.0-sources.jar`
- `sealed-client-1.0.0.sbom.json`
- `sealed-client-26.2-1.0.0-bom.json`
- A combined `SHA256SUMS` plus per-platform checksum lists
- `SECURITY.md`, `NOTICE`

SHA-256 tells you whether a file changed after publication. It does not help
if you take both the jar and the hash from the same untrusted place, since
both can be replaced together — get the hash from the reviewed source or
another trusted path. The SBOM is an audit record of runtime dependencies and
their hashes, not a vulnerability scan and not a safety certificate.

To re-check an already generated bundle:

```shell
scripts/verify-release.sh --skip-build
```

## Reproducible build status

Jar tasks strip file timestamps and pin file ordering. That improves the odds
of identical bytes from identical inputs, but the project does not claim
certified reproducibility across different machines and JDKs.

To compare checksums from two full release builds in the current environment:

```shell
scripts/verify-release.sh --repeat-builds 2
```

The script also checks each build's `SHA256SUMS` and SBOM structure. Success
means repeated builds in *this* environment produced the same checksums. On its
own that does not prove the source, the build tooling, or the build machine is
safe.

## What another Minecraft version costs

Rather than estimating the cost of supporting more versions, it was measured:
the 1.21.4 source was compiled against 1.21.8 and the compiler counted.

| | Errors | Files affected |
| --- | --- | --- |
| Before adapters | 101 | 27 of 157 |
| After adapters | 54 | 4 |

All 47 that went away were renames — the hotbar slot, movement input, the
sword/pickaxe test, the resistance effect, armour slots, teleporting. Access
like that now goes through four adapters in `dev.sealedclient.platform` (181
lines), and a source-scanning test stops new call sites from going around them.

The remaining 54 are two things:

- **44 — the render pipeline.** `RenderSystem`'s state methods were removed and
  moved into `RenderPipeline`. That is not a rename but a change in how render
  state is described, so it needs a real port; the equivalent work on 26.2 came
  to about 894 lines. **There is no way to verify 1.21.4 render output in CI**,
  so this is not started while regressions cannot be caught automatically.
- **9 — inside the adapter files.** This number cannot reach zero. Something
  has to call the real API, and the point of the adapters is that it happens in
  three files instead of seventeen.

So 1.0 promises 1.21.4 and 26.2. Other versions are not "coming soon"; they are
the number above.
