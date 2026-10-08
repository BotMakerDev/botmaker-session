# ROADMAP

Completed work up to 2026-10-05: see CHANGELOG.md, docs/refactor/, and `git show f986d23:ROADMAP.md`.
History before 2026-07-30 (when this stack was `com.botmaker.shared.session`) is in
`../botmaker-shared`'s `git show ed3d1aa:ROADMAP.md`.

## Open

- **Game VM, what's left** (`../docs/refactor/44-windows-isolation.md` §4b):
  - VMware Workstation's route is unit-tested only: check `VmSetupLiveTest` and `VmSessionLiveTest` with
    VMware installed, and a launcher install through `vmrun runProgramInGuest`, which runs as the guest's
    user rather than SYSTEM, so a per-machine `msiexec /qn` may be refused without elevation;
  - VirtualBox as a third hypervisor (it serves VNC through an extension pack);
  - DirectX 12 and Vulkan in the guest: neither hypervisor offers them, so a DX12- or Vulkan-only game can't
    run in a VM; a Linux guest has no GPU at all (Wine and Proton draw in software);
  - the Remote Pilot's background mode for a VM (it drives a Linux private display only);
  - a Linux game VM on VMware: setup, displays (reached at the guest's NAT address) and the game copy
    (`vmhgfs-fuse`) are QEMU-only;
  - a Linux VM's Steam client runs in the game unit of the display that started it, so it counts as that
    display's game, and a Steam game started from a second display goes to that client;
  - every display of a Linux VM reads its window list each second over the VM's one guest agent;
  - each bot of a Linux VM keeps a VNC connection to the VM's console, which only says when the VM ended;
  - a Linux VM's screen window shows nothing until its guest answers;
  - the frame rate over VNC while the guest's screen moves, measured with a game;
  - a VM shut down while a bot runs ends the run only at its next recovery (the watchdog's stuck timeout, or
    a crash): until then the bot sees the last frame. The SDK could stop the run when `endedBecause()` turns
    up;
  - *when nothing has used it for* counts QEMU's VNC clients; a VMware VM has no such count and is never
    shut down as unused;
  - Epic in the VM accepting a game copied from this PC (`GameCopy`, Firestone in `live` and `vmw`) is checked
    once someone signs in there; so is `legendary import` and launch in the Linux VM (Firestone is in `lin`,
    and runs there under Wine up to its sign-in). The 🎮 game dialog still lists this PC's games for a VM bot,
    not the VM's.
- **Waydroid on gamescope instead of Xephyr — measure first.** A Waydroid session is Wayland → inner
  `gamescope --backend sdl` → Xephyr (software, CPU-blitting) → host X; users report it laggy where a
  gamescope-only launch is not. Try swapping the *outer* display to gamescope, keeping the inner
  `--backend sdl` (still a mapped X11 client, so `PaintedSurface` is unchanged). A lone gamescope does not
  work (`docs/display-pipeline.md` §6). Gains if it works: hardware compositing and resizability. Needs a box
  with gamescope and a real GPU.
- **Remove Xephyr** once a gamescope-only run has soaked: `NestedDisplay`, `SessionHostWindow`'s raise path,
  `windowManagerFor`, the openbox dependency and the `xephyr` wire id (keep parsing the id forever).
- **Waydroid session input is unverified**: taps route through the session pointer (XTest on `:N` →
  gamescope → Waydroid) and none has been landed in Android yet. Fallback: ADB gestures for that route.
- **Waydroid container resolution**: it boots at whatever `persist.waydroid.width/height` say;
  `WaydroidResolution.apply()` exists unused.
- **Capture the gamescope output window instead of `adb screencap`** for Waydroid — one backend shared with
  the Windows emulators' host-window capture (see `botmaker-shared`'s ROADMAP). Incompatible with
  `--backend headless`.
- **Untested out-of-process seam.** `RemoteDisplay`'s caller half (point it at a pipe served by a stub agent;
  this would also pin the driven-window sync recursion fix), `LocalDisplay`, and `DisplayAgentProcess`'s
  spawn forms (live-gated suite).
- **`DisplayReadiness.await`** still opens a short-lived in-process connection to `:N` during bring-up; move
  it behind the agent when convenient.
- **One agent per session.** A shared agent multiplexing displays if session counts grow.
- **A session could observe whether anything mapped on its Xwayland root**, so an X11 game under
  `--expose-wayland` is recognised as capturable; needs a client-count probe through `DisplayLink`.
- **Fidelity probe at `-Dbotmaker.fidelity.size=1080x1920`** to baseline the portrait downscale.
- **Teardown artefacts**: a black blip on close and a gray drag trail may survive `repaintHostBehind`. Next
  step is reading the timestamped transition log from a real close, then host compositor state or
  `--backend headless` plus a capture check — measured, not blind.
- **Isolated-launch phase 13 leftovers** (unverified whether done): name the pid behind a "close Heroic and
  try again" refusal; attach provisionally and gate readiness on the target's own window; SIGTERM the game's
  tree before SIGKILLing the launcher (the Electron/CEF `SIGTRAP` coredumps).
