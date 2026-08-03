# Changelog

## 1.0.0

First public release.

### Version numbering was reset

Tags had climbed to 3.3.1, but nothing was ever published: no releases, no
stars, no forks. Those numbers recorded development churn rather than anything
a user received, so calling the first published build 1.0.0 is the honest
description. The old tags are kept, since CI history and commit messages point
at them and deleting them buys nothing.

This was only possible while nobody depended on the numbering. After the first
user it would have been a breaking change rather than a correction.

### What 1.0 promises

- **Minecraft 1.21.4 and 26.2**, with all 90 features working on both.
- Config format and command names will not break within 1.x. If that becomes
  unavoidable, there will be an automatic migration and a note here.
- Only measured numbers go in the docs. Anything not confirmed is labelled a
  hypothesis rather than being quietly absorbed into a tolerance.
- No outbound connections, no telemetry, no launcher token access.

### Documentation rewritten for users

The README used to open with a support matrix, a verification table, and a
discussion of dependency checksums — all true, none of it answering the
question someone actually arrives with. Install instructions started 94 lines
in, after roughly sixty lines about test strategy.

The user-facing half is now the README; build and verification detail moved to
`docs/DEVELOPMENT.md`. Ordering follows what a new user needs: the ban warning
first, since it is the only irreversible thing here, then install, then first
launch, then controls and commands.

Things that were buried are now stated plainly: everything dangerous ships
disabled, `;sealed panic` is the one command worth memorising, and config
survives corruption. Long reference material is collapsed rather than deleted.

Both documents are in English. Claims were re-checked against the code rather
than carried across: jar names against built artifacts, keys against the GLFW
constants, the command prefix against `CommandManager`, config paths against
`ConfigManager`, and the catalogue count against
`PlatformCatalogParityIntegrationTest`.

### Supporting more Minecraft versions

The cost of another version was measured rather than estimated: the 1.21.4
source was compiled against 1.21.8 and the compiler counted.

| | Errors | Files affected |
| --- | --- | --- |
| Before | 101 | 27 of 157 |
| After | 54 | 4 |

Sixty percent of the original errors were a single rename. `Inventory.selected`
and `setSelectedHotbarSlot` were spelled out at 61 call sites across 17 files,
so one line of upstream change detonated across the module tree.

Version-sensitive access now goes through four adapters in
`dev.sealedclient.platform`: the hotbar slot, movement input, the
sword/pickaxe test, and entity access. The sword test was not a rename but a
deletion — those classes were folded into components — and asking the item tag
instead is more correct anyway, so a datapack sword that never extended the
vanilla class now counts as one.

A source-scanning test stops new call sites from going around the adapters. It
was checked by deliberately reintroducing a direct reference and watching it
fail.

What remains for 1.21.8 is 44 render pipeline errors, which is a real port
rather than renaming, and 9 inside the adapters themselves. The second number
cannot reach zero — something has to call the real API — and having it be nine
lines in three files is the entire point.

The render port is deliberately not started: there is no way to verify 1.21.4
render output in CI, and moving ~894 lines while regressions cannot be caught
automatically would relocate the debt rather than repay it.

### Bugs found and fixed

**Blast damage through cover.** Two engines in the 26.2 platform each carried
their own copy of the explosion curve, and both returned zero damage for a
fully obstructed target. The server deals at least 1. Three live paths read
those values, so targets behind obsidian were being judged safe. Both now use
the measured formula, and a test cross-checks them across the full distance and
exposure grid so they cannot diverge again.

**Overlay alpha inverting.** Extracting colour maths for testing exposed a real
defect: an alpha scale above 1 wrapped a nearly opaque overlay into a
transparent one, because only the upper bound was clamped and only after
multiplying. Both ends are clamped now, and a non-finite scale draws nothing
rather than painting the whole screen.

**Durability rounding disagreement.** The two platforms computed remaining
durability differently — one rounded, one truncated. On a diamond chestplate
that is a different answer for half of all durability values, moving the
auto-repair threshold by two points. The existing test never caught it because
it used a maximum of 100, exactly where the two formulas coincide. A test at a
real chestplate maximum now pins it, verified by restoring the old formula and
watching it fail.

**ClickGUI going blank.** Collapsing an expanded module shortened the list
while the scroll offset stayed, pushing every remaining row off the top. The
search path already handled this; collapsing did not. Both now share one
clamp.

### Measured ceilings

Rather than claiming combat is fast or accurate, each property has a ceiling
that cannot be beaten, measured against a real dedicated server.

- **Reaction latency: 1 tick**, 12 of 12 samples. A reaction cannot be sent
  before the tick that observed its cause.
- **Crystal placement: matches exhaustive search** across 2000 randomised
  trials.
- **Explosion damage: 7 of 10 scenarios exact**, worst case 0.500 low. The
  error is one-directional — the prediction is never below the damage actually
  dealt, which is the safe side for self-damage decisions.

The surviving explanation for the residual (server `float` accumulation versus
our `double`) is recorded as a hypothesis, not a fact.

---

Earlier entries covered pre-release development and were written in Korean.
They are preserved in the git history under tags `v2.2.0` through `v3.3.1`.
