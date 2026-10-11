# botmaker-session

**Private displays for a bot.** A bot runs its game on a nested X server of its own (Xephyr or gamescope),
with its own pointer, keyboard focus and window stack, so its clicks never move the user's mouse and the user
keeps using the computer while it runs. The same code can target the user's real desktop or a game VM.

A library with no JavaFX and no UI. The [SDK](https://github.com/BotMakerDev/botmaker-sdk) and
[Studio](https://github.com/BotMakerDev/botmaker-studio) use it; its only BotMaker dependency is
[`botmaker-shared`](https://github.com/BotMakerDev/botmaker-shared). Its API breaks freely between releases.

## Using it

```xml
<repositories>
    <repository>
        <id>jitpack</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.BotMakerDev</groupId>
    <artifactId>botmaker-session</artifactId>
    <version><!-- latest tag: https://github.com/BotMakerDev/botmaker-session/tags --></version>
</dependency>
```

Its pom excludes shared's OpenCV: nothing here matches pictures.

## The model

`Sessions` is the one door:

| Call | Gives |
|---|---|
| `Sessions.startPrivate(SessionOptions)` | a `PrivateSession` — a fresh `:N` display, Xephyr or gamescope (`SessionBackend`) |
| `Sessions.startVm(VmOptions)` | a game VM set up in Studio, driven over VNC |
| `Sessions.host()` | the user's real desktop |
| `Sessions.offered()` | the private display the process that started this one handed over (`handoffArguments`) |
| `Sessions.reapOrphans()` | sweep sessions a crashed process left behind |

A session is a `DesktopSession`, which either attaches to a window or launches a target into itself.

- **`Capability`** says what a session can really do — `BACKGROUND_CLICK`, `ISOLATED_FOCUS`, `HARDWARE_GL`,
  `VULKAN` and the rest — so a caller fails fast instead of clicking into nothing.
- **Two backends.** gamescope gives a game a real GPU and is its own window manager; Xephyr is a plain X
  server. `SessionBackends.preferredBackend` picks gamescope for every target except an emulator app, which
  gets Xephyr (its launch is a gamescope of its own, drawn on that display).
- **Nothing is left running.** Everything a session starts lives in one systemd user scope
  (`SessionReaper`), so closing the session — or killing the JVM — takes the whole tree down. Without user
  systemd, closing still does, but a killed JVM can leave processes behind.
- **A dying display does not take its owner down.** The connection to `:N` lives in a child process
  (`DisplayAgent`, behind `DisplayLink`); when the display dies, the caller sees an error, not an `exit(1)`.
- **`LaunchIsolation`** says before bring-up whether a target can be confined to a private display, and why
  not; `BackgroundLauncher` starts or adopts a game on one and hands it to a bot.
- **`PointerPolicy`** decides whether a gesture gives the cursor back: on the user's desktop yes, inside a
  session no.
- `Preview` and `video/` encode a session's screen for the Remote Pilot (JPEG frames, or H.264 through
  ffmpeg).

Private displays need Linux with gamescope (and Xephyr, for emulator apps) installed. Game VMs need a
hypervisor — VMware Workstation or QEMU, or Tart on an Apple Silicon Mac — and are the route to a screen of
the bot's own on Windows and macOS.

## Building

```bash
mvn test                               # here
mvn -pl botmaker-session -am install   # from the umbrella root, at main's -SNAPSHOT
mvn test -Dbotmaker.live=true -Dbotmaker.live.backend=gamescope   # the suites that start a real display
```

## Releasing

From the umbrella only, with `--session <version>` plus the downstreams it forces (sdk, studio);
`/release-check` names them.
