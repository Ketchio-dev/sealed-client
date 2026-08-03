> **Read this first.** Sealed Client includes features that break the rules on
> most servers. What is allowed varies by server and changes without notice.
> If you get banned, that is on you. Try risky features on a server you own
> before you use them anywhere else.

# Sealed Client

A Fabric utility client for Minecraft Java Edition, written from scratch. No
code, assets, or binaries taken from other clients.

| Minecraft | Java | Status |
| --- | --- | --- |
| **1.21.4** | 21 | all 90 features working |
| **26.2** | 25 | all 90 features working |

Other versions are not supported yet. [The remaining work is written down as a
number](docs/DEVELOPMENT.md#what-another-minecraft-version-costs), not as
"coming soon".

---

## Install

**1. Install Fabric first.**

Sealed Client is a Fabric mod. You need Fabric Loader and Fabric API for your
Minecraft version before this will load.

**2. Drop in one jar.**

Put **one** file in your `mods` folder — the one matching your version.

| Minecraft | File |
| --- | --- |
| 1.21.4 | `sealed-client-mc1.21.4-1.0.0.jar` |
| 26.2 | `sealed-client-mc26.2-1.0.0.jar` |

Do not install both.

**3. Launch the game and press `P`.**

If the menu opens, you are done.

Where the `mods` folder lives:

- Windows: `%appdata%\.minecraft\mods`
- macOS: `~/Library/Application Support/minecraft/mods`
- Linux: `~/.minecraft/mods`

---

## First launch

Every combat and automation feature ships **turned off**. That is deliberate,
so nothing gets you banned before you have decided to turn it on. You enable
what you want.

If you are not sure what to enable, open **Presets** on the left of the `P`
menu.

| Preset | For |
| --- | --- |
| `Low Lag Utility` | Information only. The safe one |
| `Travel Safe` | Long-distance travel |
| `Crystal Practice` | Combat settings for your own practice server |

Presets do not switch risky things on behind your back. They enable the
passive features, then ask you to confirm within five seconds before anything
riskier is applied. If applying fails partway, the whole thing rolls back.
Pressing `U` undoes the last preset.

### When something goes wrong

`;sealed panic`

Turns off combat, movement, and automation, and releases any key or inventory
slot the client was holding. This is the one command worth remembering.

---

## Controls

### Keys

| Key | Does |
| --- | --- |
| `P` | Open/close the menu |
| `H` | Move HUD panels |
| `O` | Profiles |
| `K` | Presets |
| `/` or `Ctrl+F` | Search features |

### In the menu

| Mouse | Does |
| --- | --- |
| Left click | Toggle |
| Right click | Expand settings |
| Middle click | Favourite |
| Wheel | Scroll |

### Binding a key

Expand any feature's settings and the top row is `Keybind`. Click it, press
the key you want. `Esc` clears it.

Binds only fire when no screen is open, so typing in chat will not toggle
anything.

---

## Commands

Commands start with `;sealed` and are **never sent to the server**. Only your
client reads them.

The ones you will actually use:

```text
;sealed help                    show help
;sealed toggle <feature>        turn something on or off
;sealed panic                   turn everything off
;sealed friend add <name>       add a friend (excluded from targeting)
;sealed waypoint add <name>     save your current position
```

<details>
<summary>Full command list</summary>

**1.21.4**

```text
;sealed help
;sealed modules [category]
;sealed toggle <module>
;sealed bind <module> <key|none>
;sealed set <module> <setting> <value>
;sealed profile list|create|use|delete|bind
;sealed friend list|add|remove
;sealed waypoint list|add|remove
;sealed baritone status|stop
;sealed baritone goto <x> [y] <z>
;sealed baritone goto waypoint <name>
;sealed config save|reload
;sealed panic
```

**26.2**

```text
;sealed help
;sealed list [category]
;sealed status <module-id>
;sealed toggle <module-id>
;sealed friend add|remove|list [name]
;sealed waypoint add|remove|list [name]
;sealed profile list
;sealed profile save <name> [server-pattern]
;sealed profile use <name>
;sealed profile delete <name>
;sealed profile gui
;sealed hud edit|reset
;sealed baritone status|stop
;sealed baritone goto <x> <y> <z>
;sealed panic
```

</details>

---

## Features

There are 90. Searching with `/` is faster than scrolling.

| Category | Examples |
| --- | --- |
| **Combat** | Auto Crystal, Kill Aura, Auto Totem, Surround, Hole Fill, Anchor/Bed Aura |
| **Movement** | Elytra assists, Step, No Fall, Safe Walk, Auto Walk, Hole Snap |
| **Visual** | Player/Storage/Block ESP, Tracers, Nametags, Freecam, XRay, Chams |
| **World** | Waypoints, New Chunks, Logout Spots, Stash Finder, Trajectories |
| **Utility** | Auto Eat/Armor/Tool/Mend, inventory sorting, Auto Craft, Anti AFK |
| **HUD** | Coordinates, speed, FPS, ping, health, armour, radar, server TPS, target |

Anyone on your friends list is **excluded from attack and targeting features**
automatically.

### Worth knowing

**What `New Chunks` actually shows.** Chunks you are seeing for the first time
this session. That is *not* proof the server generated them recently — someone
else may have been there long before you.

**Baritone is a separate install.** For pathfinding you need to download
official Baritone yourself and put it in `mods`. It is not bundled here and
nothing is downloaded automatically. Everything else works fine without it.

<details>
<summary>Baritone setup details</summary>

1.21.4 needs the official `baritone-api-fabric` 1.13.1. The
`standalone-fabric` variant has the API classes stripped and will not work.

`Baritone Navigator` is off by default. You set a destination and enable
`Confirm Target` before it moves once.

26.2 only connects to a compatible provider if one is installed. There is no
guarantee official Baritone keeps shipping a 26.2 build, so on 26.2 you should
check where your provider came from and whether it actually matches.

</details>

---

## Config files

You should not need to touch these, but it helps to know where they are.

| Version | Location |
| --- | --- |
| 1.21.4 | `config/sealedclient/config.json` |
| 26.2 | `config/sealedclient-26.2.json` |

Config that loads cleanly is backed up automatically. **A corrupted file does
not lose your settings** — the broken one is kept aside and the client falls
back to the backup or to defaults. Writes are done so that a crash mid-save
cannot leave you with half a file.

Disconnecting or quitting also restores brightness and view bob if the client
changed them.

---

## Is this safe

Whatever the source says, what actually runs on your machine is a built jar.
So:

- **No outbound connections.** No HTTP, sockets, webhooks, or remote updates
- **No account access.** Launcher accounts, profiles, and auth tokens are
  never read
- **No external processes and no downloaded code**
- A source scan checks all of the above **on every build**
- Dependencies are checksum-pinned, and releases ship an SBOM and SHA-256 list

"No outbound connections" does not mean the game stops talking to your server.
It means Sealed Client does not open a channel of its own separate from the
Minecraft connection.

None of this proves there is no malicious code. Reading the source and
building it yourself is the strongest check available to you. See
[SECURITY.md](SECURITY.md) for detail.

**Only download jars from somewhere you trust.** A file from a Discord
attachment or an unofficial mirror may not be the file that was published.

---

## Performance

Rather than claiming this is "fast" or "accurate", the limit was worked out
first and then measured against on a real server.

| | Result |
| --- | --- |
| Reaction time | **1 tick** (12/12 samples) — nothing can be faster |
| Crystal placement | **matches optimal** (2000 randomised trials) |
| Explosion damage prediction | **7 of 10 exact**, worst case off by 0.5 |

When the prediction is wrong it is always **too high**, never too low. It does
not underestimate the damage you are about to take.

These are **not** benchmarks against other clients. They measure distance from
a theoretical ceiling, with no competitor involved. Method and reproduction
commands are in [the developer docs](docs/DEVELOPMENT.md#measured-ceilings).

---

## Building it yourself

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for how to build, run the
tests, and verify a release bundle.

---

## License

[Apache License 2.0](LICENSE). Minecraft, Fabric, Baritone and other
third-party components keep their own licenses.
