# Security

## Local-only boundary

Sealed Client's production code is designed never to:

- Open its own HTTP connection, socket, or webhook
- Collect telemetry or transmit play information
- Read Minecraft launcher accounts, profiles, or auth tokens
- Download or remotely load executable code
- Launch external processes
- Update itself remotely

Minecraft still talks to whatever game server you chose, and Sealed Client
exposes packet events from that connection to its modules. "Local-only" does
not mean the game connection is blocked; it means Sealed Client does not create
a channel of its own separate from that connection.

The only direct file access expected is the Fabric-provided config paths,
`config/sealedclient/` and `config/sealedclient-26.2.json`. Config writes use a
temporary file and swap atomically where the filesystem supports it. 1.21.4
keeps a backup of the last config that loaded cleanly and restores from it;
26.2 quarantines a corrupted file and falls back to safe defaults.

Clipboard profile exchange on 1.21.4 does not include friends, waypoints, or
server connection details. Imports are capped at 256 KiB and 32 levels of JSON
nesting, and only registered modules and setting values are applied. No
executable code or jars are read, and enabling combat, movement, packet, or
automation modules is skipped without a separate confirmation.

Local Fabric mods can use the Sealed Client API through the `sealedclient:addon`
entry point. Sealed Client does not download addons or scan arbitrary
directories for jars, but any mod you install yourself runs with the same
process privileges as Sealed Client, so an addon's safety is your own
assessment. The optional Baritone integration likewise only detects an official
mod you installed; it is neither downloaded nor bundled. A separately installed
Baritone jar is outside this security boundary for the same reason, and you
should verify its source and checksum independently.

## Threats this design reduces

- SHA-256 detects an unofficial jar that differs from what was published
- Dependency checksums block a Gradle download that differs from what was
  reviewed
- A source policy test catches accidental introduction of network, process, or
  token APIs
- Source jars ship alongside a runtime dependency list, so review is possible
- Combat, movement, and automation default to disabled, carry risk labels, and
  both platforms provide `;sealed panic` (disables active non-passive modules
  and releases keys, slots, combat, movement, Freecam, XRay, and any
  Sealed Client-owned Baritone state)

## What this does not prove

- A string-based source policy test is not a complete malware analyser.
- Dependency checksums confirm that a downloaded file matches; they do not
  prove that dependency is inherently safe.
- SHA-256 only means something when compared against a reference hash obtained
  through a trusted path.
- The per-platform SBOM is a list of runtime dependencies confirmed by a local
  build. It is not a vulnerability scan, a signature, or a security
  certification.
- Stripping archive timestamps and pinning file order helps reproducibility but
  does not automatically verify bit-for-bit reproducibility across different
  environments.
- Other Fabric mods you install, your Java runtime, your launcher, and your
  operating system are outside this repository's security boundary.

## Verification

Run these against the repository state you reviewed. The Gradle toolchain uses
Java 21 and 25 as appropriate per platform.

```shell
./gradlew --no-daemon clean :common:clean :platform-26.2:clean multiVersionBuild
./gradlew --no-daemon e2eTest
scripts/performance-soak.sh
scripts/baritone-integration-smoke.sh
scripts/verify-release.sh
```

`check` includes a policy test that searches production code for known
network, process execution, launcher account, and token API markers. Gradle
dependency verification compares build plugins, Minecraft, Fabric, test tooling,
and transitive dependencies against `gradle/verification-metadata.xml`. Because
Loom generates intermediate mapping jars whose bytes differ per operating
system from the same official mapping inputs, only the public CI unit-test and
build commands run in `lenient` mode. CI logs verification failures, while the
local `qualityGate` and the release scripts keep strict verification.

`e2eTest` runs an isolated 1.21.4 Fabric client against a local integrated
server. It never connects to 2b2t or any other external Minecraft server. 26.2
also verifies the real client's panic and release paths in a Client GameTest.
Public CI only runs unit tests and the build, so run the local `qualityGate`
separately before a release.

`baritone-integration-smoke.sh` downloads the Baritone API Fabric jar and its
checksum from the official GitHub release into a temporary directory, and only
when you explicitly run it. The input jar is compared against the official
checksum before it runs and is never included in the Sealed Client release
bundle.

You can also compare release checksums across two local builds:

```shell
scripts/verify-release.sh --repeat-builds 2
```

The verification script checks the SHA-256 list, basic SBOM structure, and
checksum agreement between repeated builds. That comparison only establishes
that two results match in that environment; it is not a substitute for
independent reproducible-build infrastructure or third-party attestation.

## Safe installation

- Build from source you reviewed, or use jars from a distribution path you
  trust.
- Avoid Discord attachments, unofficial mirrors, and jars redistributed by
  third-party launchers.
- Verify the jar and `SHA256SUMS` through independent trust paths where
  possible.
- Do not run a distribution that contains obfuscated jars absent from the
  source, or that requires an additional installer.
- Do not attach launcher tokens, session values, account files, or full logs to
  a bug report.

## Reporting

If you find a security problem, send the maintainers the Sealed Client version
you used, your Minecraft and Fabric versions, a minimal reproduction, and the
scope of impact. Strip real account tokens, server addresses, personal paths,
and sensitive logs. Do not post exploitable detail or secrets publicly.

The current security review scope is the Minecraft 1.21.4 and 26.2 jars built
from this repository at Sealed Client 1.0.x. Unofficial jars converted or
repackaged by third parties are not covered.
